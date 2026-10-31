package com.example.persiandubber

import android.content.Context
import android.media.*
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

sealed class SynthesizedClip {
    data class Mp3Clip(val bytes: ByteArray, val label: String) : SynthesizedClip()
    data class Pcm24kClip(val bytes: ByteArray, val label: String) : SynthesizedClip()
    data class LocalTtsClip(val text: String, val targetLang: String) : SynthesizedClip()
}

data class PlaybackTask(
    val deferredClip: Deferred<SynthesizedClip?>,
    val dubVolume: Float,
    val origVolume: Float,
    val onStatus: (String) -> Unit
)

class VoiceSynthesizer(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        @Volatile var serverTimeOffsetMs: Long = 0L

        fun syncUtcFromHttpDate(dateHeader: String?) {
            if (dateHeader.isNullOrBlank()) return
            try {
                val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                val parsed = sdf.parse(dateHeader)
                if (parsed != null) {
                    serverTimeOffsetMs = parsed.time - System.currentTimeMillis()
                }
            } catch (_: Exception) {}
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var localTts: TextToSpeech? = null
    private var localTtsReady = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var activeFocusRequest: AudioFocusRequest? = null

    private var savedMusicVolume: Int = -1

    // Remembers if Edge WebSocket was blocked/slow recently so we don't waste 2.5s waiting for it on every sentence!
    @Volatile private var edgeCooldownUntilMs: Long = 0L

    private val playbackChannel = Channel<PlaybackTask>(Channel.UNLIMITED)
    private val queuedCount = AtomicInteger(0)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    init {
        try {
            localTts = TextToSpeech(context, this, "com.google.android.tts")
        } catch (_: Exception) {
            localTts = TextToSpeech(context, this)
        }
        startPlaybackConsumer()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            localTtsReady = true
            localTts?.setLanguage(Locale("fa", "IR"))
        }
    }

    fun saveInitialVolumes() {
        try {
            if (savedMusicVolume == -1) {
                val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                savedMusicVolume = if (cur > 2) cur else (max * 0.65f).roundToInt().coerceAtLeast(3)
            }
        } catch (_: Exception) {}
    }

    fun restoreInitialVolumes() {
        try {
            abandonYoutubeDucking()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }
            if (savedMusicVolume != -1) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
                savedMusicVolume = -1
            }
        } catch (_: Exception) {}
    }

    // Differential Mixer on USAGE_MEDIA (works on both Bluetooth headphones AND Speaker!):
    // - Never mutes STREAM_MUSIC to 0 (because USAGE_MEDIA needs STREAM_MUSIC > 0 to be heard on Bluetooth!).
    // - Instead, when origVolRatio is 0% (or low), it holds continuous AudioFocus Ducking (which cuts YouTube to 20%)
    //   AND lowers STREAM_MUSIC to ~15-20% (so YouTube is 0.20 * 0.15 = 3% whisper),
    //   WHILE boosting our Dubbed Voice with LoudnessEnhancer(+2800 mB = +28dB) so the Dubbed Voice is 100% loud and clear!
    fun applyOriginalVideoVolumePolicy(origVolRatio: Float, dubVolRatio: Float) {
        try {
            saveInitialVolumes()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }
            val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val baseUserVol = if (savedMusicVolume > 2) savedMusicVolume else (maxMusic * 0.70f).roundToInt().coerceAtLeast(3)

            if (origVolRatio <= 0.05f) {
                // Near-zero background video: keep STREAM_MUSIC at minimal step (2) so Bluetooth A2DP stays open,
                // duck YouTube by 80%, and compensate Dubbed Voice with +28dB LoudnessEnhancer!
                val minAudibleStep = (maxMusic * 0.14f).roundToInt().coerceAtLeast(2)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, minAudibleStep, 0)
                requestYoutubeDucking()
            } else if (origVolRatio < 0.75f) {
                val scaledStep = (baseUserVol * (0.35f + origVolRatio * 0.65f)).roundToInt().coerceIn(2, maxMusic)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, scaledStep, 0)
                requestYoutubeDucking()
            } else {
                val fullStep = (baseUserVol * origVolRatio).roundToInt().coerceIn(3, maxMusic)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, fullStep, 0)
                abandonYoutubeDucking()
            }
        } catch (_: Exception) {}
    }

    // Computes how much LoudnessEnhancer boost (in millibels, mB) to apply to our Dubbed Voice
    // so that when STREAM_MUSIC is lowered to quiet YouTube, our Dubbed Voice stays at full 100% loudness!
    private fun computeCompensatedBoostMb(dubVolRatio: Float, origVolRatio: Float): Int {
        val baseBoost = ((dubVolRatio.coerceIn(0.1f, 1f) - 0.3f).coerceAtLeast(0f) * 1800f).toInt()
        val duckingCompensation = when {
            origVolRatio <= 0.05f -> 1800 // +18 dB extra compensation when YouTube is at 0%
            origVolRatio < 0.40f -> 1100  // +11 dB extra compensation when YouTube is low
            origVolRatio < 0.75f -> 500
            else -> 0
        }
        return (baseBoost + duckingCompensation).coerceIn(0, 3000)
    }

    fun requestYoutubeDucking() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (activeFocusRequest == null) {
                    val focusAttrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    activeFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(focusAttrs)
                        .setWillPauseWhenDucked(false)
                        .build()
                }
                audioManager.requestAudioFocus(activeFocusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (_: Exception) {}
    }

    fun abandonYoutubeDucking() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activeFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (_: Exception) {}
    }

    fun speak(
        text: String,
        phoneticHint: String,
        targetLang: String,
        voiceStyle: String,
        dubVolume: Float,
        origVolume: Float,
        onStatus: (String) -> Unit = {}
    ) {
        val cleanText = text.replace("*", "").replace("\"", "").replace("'", "").replace("\n", " ").trim()
        if (cleanText.length < 2) return

        // Never cancel an already-synthesizing sentence!
        // Start synthesizing this sentence IMMEDIATELY in background (parallel pre-fetch) and queue its playback!
        if (queuedCount.get() <= 3) {
            val currentBacklog = queuedCount.incrementAndGet()
            val deferred = scope.async {
                synthesizeFastestStudioClip(cleanText, targetLang, voiceStyle, currentBacklog)
            }
            playbackChannel.trySend(PlaybackTask(deferred, dubVolume, origVolume, onStatus))
        }
    }

    private fun startPlaybackConsumer() {
        scope.launch {
            for (task in playbackChannel) {
                try {
                    val clip = task.deferredClip.await()
                    if (clip != null) {
                        val speed = if (queuedCount.get() > 1) 1.24f else 1.15f
                        when (clip) {
                            is SynthesizedClip.Mp3Clip -> {
                                task.onStatus(clip.label)
                                playMp3OnUsageMediaBlocking(clip.bytes, task.dubVolume, task.origVolume, speed)
                            }
                            is SynthesizedClip.Pcm24kClip -> {
                                task.onStatus(clip.label)
                                playPcm24kOnUsageMediaBlocking(clip.bytes, task.dubVolume, task.origVolume, speed)
                            }
                            is SynthesizedClip.LocalTtsClip -> {
                                task.onStatus("🔊 پخش با موتور TTS فارسی گوشی")
                                playLocalPersianTtsBlocking(clip.text, clip.targetLang, task.dubVolume, task.origVolume)
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    val remaining = queuedCount.decrementAndGet()
                    if (remaining <= 0 && AudioCaptureService.currentOrigVol >= 0.75f) {
                        abandonYoutubeDucking()
                    }
                }
            }
        }
    }

    // Races/selects the fastest working studio voice so we NEVER waste 4.8s waiting on a blocked socket!
    private suspend fun synthesizeFastestStudioClip(
        text: String,
        targetLang: String,
        voiceStyle: String,
        backlog: Int
    ): SynthesizedClip? = coroutineScope {
        val apiKey = AudioCaptureService.currentApiKey
        val prefersGemini = voiceStyle.contains("جمنای") || voiceStyle.contains("Kore") || voiceStyle.contains("Puck")
        val geminiVoice = if (voiceStyle.contains("مرد") || voiceStyle.contains("فرید") || voiceStyle.contains("Puck")) "Puck" else "Kore"
        val rateStr = if (backlog > 1) "+22%" else "+14%"

        // 1. If user explicitly chose Gemini voice (Kore/Puck)
        if (prefersGemini && apiKey.isNotBlank()) {
            val pcm = synthesizeGeminiNativePcm(text, targetLang, apiKey, geminiVoice)
            if (pcm != null && pcm.size > 400) {
                return@coroutineScope SynthesizedClip.Pcm24kClip(pcm, "🔊 گوینده طبیعی جمنای (" + geminiVoice + ")")
            }
        }

        // 2. If Edge Neural is not in cooldown, try Edge Neural with a fast 2.2s timeout!
        val now = System.currentTimeMillis()
        if (now >= edgeCooldownUntilMs) {
            val edgeMp3 = synthesizeEdgeNeuralMp3(text, targetLang, voiceStyle, rateStr, 2200L)
            if (edgeMp3 != null && edgeMp3.size > 300) {
                val speakerName = if (voiceStyle.contains("مرد") || voiceStyle.contains("فرید")) "فرید (استودیویی)" else "دلارا (استودیویی)"
                return@coroutineScope SynthesizedClip.Mp3Clip(edgeMp3, "🔊 گوینده: " + speakerName)
            } else {
                // Edge timed out or was blocked by VPN -> put Edge on 45s cooldown so next sentences go straight to Gemini TTS with 0ms wasted wait!
                edgeCooldownUntilMs = System.currentTimeMillis() + 45_000L
            }
        }

        // 3. Studio Engine 2: Gemini Native Audio TTS (Restored from v16!)
        if (apiKey.isNotBlank()) {
            val pcm = synthesizeGeminiNativePcm(text, targetLang, apiKey, geminiVoice)
            if (pcm != null && pcm.size > 400) {
                return@coroutineScope SynthesizedClip.Pcm24kClip(pcm, "🔊 گوینده طبیعی جمنای (" + geminiVoice + ")")
            }
        }

        // 4. Final Retry on Edge with longer timeout if Gemini TTS also didn't return
        val retryEdge = synthesizeEdgeNeuralMp3(text, targetLang, voiceStyle, rateStr, 3500L)
        if (retryEdge != null && retryEdge.size > 300) {
            edgeCooldownUntilMs = 0L
            return@coroutineScope SynthesizedClip.Mp3Clip(retryEdge, "🔊 گوینده استودیویی")
        }

        return@coroutineScope SynthesizedClip.LocalTtsClip(text, targetLang)
    }

    private fun generateSecMsGec(): String {
        val correctedUtcMs = System.currentTimeMillis() + serverTimeOffsetMs
        val unixSeconds = correctedUtcMs / 1000L
        var ticks = unixSeconds + 11644473600L
        ticks -= (ticks % 300L)
        val fileTimeTicks = ticks * 10_000_000L
        val strToHash = fileTimeTicks.toString() + "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        val digest = MessageDigest.getInstance("SHA-256").digest(strToHash.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun getEdgeVoiceName(langName: String, voiceStyle: String): Pair<String, String> {
        val isMale = voiceStyle.contains("مرد") || voiceStyle.contains("فرید") || voiceStyle.contains("Puck")
        return when (langName) {
            "فارسی" -> if (isMale) Pair("fa-IR", "fa-IR-FaridNeural") else Pair("fa-IR", "fa-IR-DilaraNeural")
            "انگلیسی" -> if (isMale) Pair("en-US", "en-US-GuyNeural") else Pair("en-US", "en-US-AriaNeural")
            "عربی" -> if (isMale) Pair("ar-SA", "ar-SA-HamedNeural") else Pair("ar-SA", "ar-SA-ZariyahNeural")
            "ترکی استانبولی" -> if (isMale) Pair("tr-TR", "tr-TR-AhmetNeural") else Pair("tr-TR", "tr-TR-EmelNeural")
            "فرانسوی" -> if (isMale) Pair("fr-FR", "fr-FR-HenriNeural") else Pair("fr-FR", "fr-FR-DeniseNeural")
            "آلمانی" -> if (isMale) Pair("de-DE", "de-DE-ConradNeural") else Pair("de-DE", "de-DE-KatjaNeural")
            else -> if (isMale) Pair("fa-IR", "fa-IR-FaridNeural") else Pair("fa-IR", "fa-IR-DilaraNeural")
        }
    }

    private fun synthesizeEdgeNeuralMp3(
        text: String,
        targetLang: String,
        voiceStyle: String,
        rateStr: String,
        timeoutMs: Long
    ): ByteArray? {
        return try {
            val pair = getEdgeVoiceName(targetLang, voiceStyle)
            val langLocale = pair.first
            val voiceName = pair.second
            val secMsGec = generateSecMsGec()
            val connId = UUID.randomUUID().toString().replace("-", "")
            val muid = UUID.randomUUID().toString().replace("-", "").uppercase(Locale.US)
            val wsUrl = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
                    "?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4" +
                    "&Sec-MS-GEC=" + secMsGec +
                    "&Sec-MS-GEC-Version=1-130.0.2849.68" +
                    "&ConnectionId=" + connId

            val request = Request.Builder()
                .url(wsUrl)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0")
                .header("Cookie", "MUID=" + muid + ";")
                .build()

            val audioOut = ByteArrayOutputStream()
            val latch = CountDownLatch(1)
            val escapedText = text.take(240)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")

            val configJson = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("synthesis", JSONObject().apply {
                        put("audio", JSONObject().apply {
                            put("metadataoptions", JSONObject().apply {
                                put("sentenceBoundaryEnabled", "false")
                                put("wordBoundaryEnabled", "false")
                            })
                            put("outputFormat", "audio-24khz-48kbitrate-mono-mp3")
                        })
                    })
                })
            }.toString()

            val ws = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    val timestamp = sdf.format(Date(System.currentTimeMillis() + serverTimeOffsetMs))
                    val crlf = "\r\n"
                    val configMsg = "X-Timestamp:" + timestamp + crlf +
                            "Content-Type:application/json; charset=utf-8" + crlf +
                            "Path:speech.config" + crlf + crlf +
                            configJson
                    webSocket.send(configMsg)

                    val reqId = UUID.randomUUID().toString().replace("-", "")
                    val ssmlBody = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='" + langLocale + "'>" +
                            "<voice name='" + voiceName + "'><prosody pitch='+0Hz' rate='" + rateStr + "' volume='+0%'>" + escapedText + "</prosody></voice></speak>"
                    val ssmlMsg = "X-RequestId:" + reqId + crlf +
                            "Content-Type:application/ssml+xml" + crlf +
                            "X-Timestamp:" + timestamp + crlf +
                            "Path:ssml" + crlf + crlf +
                            ssmlBody
                    webSocket.send(ssmlMsg)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val arr = bytes.toByteArray()
                    if (arr.size > 2) {
                        val headerLen = ((arr[0].toInt() and 0xFF) shl 8) or (arr[1].toInt() and 0xFF)
                        if (arr.size > 2 + headerLen) {
                            val headerStr = String(arr, 2, headerLen, Charsets.UTF_8)
                            if (headerStr.contains("Path:audio")) {
                                audioOut.write(arr, 2 + headerLen, arr.size - (2 + headerLen))
                            }
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("Path:turn.end")) {
                        webSocket.close(1000, null)
                        latch.countDown()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    latch.countDown()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    latch.countDown()
                }
            })

            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            ws.cancel()
            val result = audioOut.toByteArray()
            if (result.size > 300) result else null
        } catch (_: Exception) {
            null
        }
    }

    // Restored from v16: Gemini Native Audio TTS (24kHz PCM)
    private fun synthesizeGeminiNativePcm(text: String, targetLang: String, apiKey: String, voiceName: String): ByteArray? {
        return try {
            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(
                        JSONObject().put("text", text)
                    ))
                ))
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("speechConfig", JSONObject().put("voiceConfig",
                        JSONObject().put("prebuiltVoiceConfig",
                            JSONObject().put("voiceName", voiceName)
                        )
                    ))
                })
            }

            val reqBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent?key=" + apiKey
            val req = Request.Builder().url(url).post(reqBody).build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bodyStr = resp.body?.string() ?: return null
                val root = JSONObject(bodyStr)
                val parts = root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts") ?: return null
                for (i in 0 until parts.length()) {
                    val inlineData = parts.getJSONObject(i).optJSONObject("inlineData")
                    if (inlineData != null) {
                        val b64 = inlineData.optString("data", "")
                        if (b64.isNotEmpty()) {
                            return Base64.decode(b64, Base64.DEFAULT)
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    // Plays MP3 on USAGE_MEDIA (100% compatible with Bluetooth & Speaker) with LoudnessEnhancer compensation!
    private suspend fun playMp3OnUsageMediaBlocking(mp3Bytes: ByteArray, dubVolume: Float, origVolume: Float, speed: Float) {
        val tempFile = File(context.cacheDir, "dub_" + System.currentTimeMillis() + ".mp3")
        val doneLatch = CountDownLatch(1)
        var mpInstance: MediaPlayer? = null
        var enhancerInstance: LoudnessEnhancer? = null

        try {
            FileOutputStream(tempFile).use { it.write(mp3Bytes) }
            applyOriginalVideoVolumePolicy(origVolume, dubVolume)
            val boostMb = computeCompensatedBoostMb(dubVolume, origVolume)

            mainHandler.post {
                try {
                    mpInstance = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        setDataSource(tempFile.absolutePath)
                        val vol = dubVolume.coerceIn(0.05f, 1f)
                        setVolume(vol, vol)
                        prepare()

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            try {
                                playbackParams = PlaybackParams().setSpeed(speed).setPitch(1.0f)
                            } catch (_: Exception) {}
                        }

                        if (boostMb > 0) {
                            try {
                                enhancerInstance = LoudnessEnhancer(audioSessionId).apply {
                                    setTargetGain(boostMb)
                                    enabled = true
                                }
                            } catch (_: Exception) {}
                        }

                        setOnCompletionListener { doneLatch.countDown() }
                        setOnErrorListener { _, _, _ ->
                            doneLatch.countDown()
                            true
                        }
                        start()
                    }
                } catch (_: Exception) {
                    doneLatch.countDown()
                }
            }

            withContext(Dispatchers.IO) {
                doneLatch.await(10, TimeUnit.SECONDS)
            }
        } catch (_: Exception) {
        } finally {
            mainHandler.post {
                try {
                    enhancerInstance?.release()
                    mpInstance?.release()
                } catch (_: Exception) {}
            }
            tempFile.delete()
        }
    }

    // Plays 24kHz PCM from Gemini Native Audio on USAGE_MEDIA at 1.15x-1.24x speed with LoudnessEnhancer compensation!
    private suspend fun playPcm24kOnUsageMediaBlocking(pcmBytes: ByteArray, dubVolume: Float, origVolume: Float, speed: Float) {
        try {
            applyOriginalVideoVolumePolicy(origVolume, dubVolume)
            val boostMb = computeCompensatedBoostMb(dubVolume, origVolume)

            val minBuf = AudioTrack.getMinBufferSize(
                24000,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(24000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, pcmBytes.size))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(pcmBytes, 0, pcmBytes.size)
            track.setVolume(dubVolume.coerceIn(0.05f, 1f))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    track.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1.0f)
                } catch (_: Exception) {}
            }

            var enhancer: LoudnessEnhancer? = null
            if (boostMb > 0) {
                try {
                    enhancer = LoudnessEnhancer(track.audioSessionId).apply {
                        setTargetGain(boostMb)
                        enabled = true
                    }
                } catch (_: Exception) {}
            }

            track.play()
            val rawDurationMs = (pcmBytes.size / 48).toLong()
            val scaledDurationMs = (rawDurationMs / speed).toLong() + 80L
            delay(scaledDurationMs)
            enhancer?.release()
            track.stop()
            track.release()
        } catch (_: Exception) {}
    }

    private suspend fun playLocalPersianTtsBlocking(text: String, targetLang: String, dubVolume: Float, origVolume: Float) {
        if (!localTtsReady) return
        applyOriginalVideoVolumePolicy(origVolume, dubVolume)
        val locale = when (targetLang) {
            "فارسی" -> Locale("fa", "IR")
            "انگلیسی" -> Locale.US
            "عربی" -> Locale("ar")
            "ترکی استانبولی" -> Locale("tr", "TR")
            "فرانسوی" -> Locale.FRENCH
            "آلمانی" -> Locale.GERMAN
            else -> Locale("fa", "IR")
        }
        localTts?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        localTts?.setLanguage(locale)
        localTts?.setSpeechRate(1.18f)
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, dubVolume.coerceIn(0.05f, 1f))
        localTts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "utt_" + System.currentTimeMillis())
        val estMs = (text.length * 48L).coerceIn(800L, 4000L)
        delay(estMs)
    }

    fun stopAll() {
        restoreInitialVolumes()
        mainHandler.post {
            try {
                localTts?.stop()
            } catch (_: Exception) {}
        }
    }

    fun shutdown() {
        stopAll()
        playbackChannel.close()
        scope.cancel()
        localTts?.shutdown()
    }
}
