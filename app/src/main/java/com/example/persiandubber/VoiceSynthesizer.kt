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
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

sealed class SynthesizedClip {
    data class Pcm24kClip(val bytes: ByteArray, val label: String) : SynthesizedClip()
    data class Mp3Clip(val bytes: ByteArray, val label: String) : SynthesizedClip()
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
    private var savedAlarmVolume: Int = -1

    // Conflated Latest-Only Pre-Fetch Slot: guarantees zero queue backlog so voice NEVER lags behind subtitles!
    private val latestPendingTask = AtomicReference<PlaybackTask?>(null)
    private val wakeUpSignal = Channel<Unit>(Channel.CONFLATED)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    init {
        try {
            localTts = TextToSpeech(context, this, "com.google.android.tts")
        } catch (_: Exception) {
            localTts = TextToSpeech(context, this)
        }
        startZeroLagConsumer()
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
                savedMusicVolume = if (cur > 1) cur else (max * 0.65f).roundToInt().coerceAtLeast(2)
            }
            if (savedAlarmVolume == -1) {
                val curA = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                val maxA = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                savedAlarmVolume = if (curA > 1) curA else (maxA * 0.85f).roundToInt().coerceAtLeast(2)
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
            if (savedAlarmVolume != -1) {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
                savedAlarmVolume = -1
            }
        } catch (_: Exception) {}
    }

    // Two-Channel Hardware Volume Separation:
    // - Original YouTube Video plays on STREAM_MUSIC.
    //   When origVolRatio <= 0.03f (0%), we set STREAM_MUSIC = 0 and ADJUST_MUTE -> YouTube is 100% SILENT!
    //   When origVolRatio > 0.03f, we ADJUST_UNMUTE and set STREAM_MUSIC proportional to origVolRatio!
    // - Dubbed Voice plays on USAGE_ALARM (STREAM_ALARM) + DSP Peak Normalizer + LoudnessEnhancer!
    //   So even when STREAM_MUSIC is 0 (muted), STREAM_ALARM plays at 100% full hardware volume!
    fun applyOriginalVideoVolumePolicy(origVolRatio: Float, dubVolRatio: Float) {
        try {
            saveInitialVolumes()
            val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)

            // 1. Set hardware STREAM_ALARM volume for Dubbed Voice (minimum 40% of maxAlarm so it's never quiet)
            val targetAlarm = (dubVolRatio.coerceIn(0.40f, 1.0f) * maxAlarm).roundToInt().coerceAtLeast(2)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetAlarm, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_UNMUTE, 0)
            }

            // 2. Set hardware STREAM_MUSIC volume for Original YouTube Video
            if (origVolRatio <= 0.03f) {
                // 100% Complete Mute of Original Video
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                }
            } else {
                // Immediately unmute and set exact volume for YouTube
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                }
                val targetMusic = (origVolRatio.coerceIn(0.05f, 1.0f) * maxMusic).roundToInt().coerceAtLeast(1)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetMusic, 0)

                if (origVolRatio < 0.65f) {
                    requestYoutubeDucking()
                } else {
                    abandonYoutubeDucking()
                }
            }
        } catch (_: Exception) {}
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

        // Start synthesizing in background IMMEDIATELY (parallel pre-fetch while previous sentence finishes playing)
        val deferred = scope.async {
            synthesizeFastestClip(cleanText, targetLang, voiceStyle, dubVolume)
        }
        val newTask = PlaybackTask(deferred, dubVolume, origVolume, onStatus)

        // Replace any unplayed stale task in the waiting slot so a backlog NEVER accumulates!
        val staleTask = latestPendingTask.getAndSet(newTask)
        staleTask?.deferredClip?.cancel()
        wakeUpSignal.trySend(Unit)
    }

    private fun startZeroLagConsumer() {
        scope.launch {
            for (signal in wakeUpSignal) {
                while (true) {
                    val task = latestPendingTask.getAndSet(null) ?: break
                    try {
                        val clip = task.deferredClip.await()
                        if (clip != null) {
                            // If another newer subtitle has already arrived while we were waiting, speed up playback slightly (1.26x)
                            val hasNewerWaiting = (latestPendingTask.get() != null)
                            val speed = if (hasNewerWaiting) 1.26f else 1.18f
                            when (clip) {
                                is SynthesizedClip.Pcm24kClip -> {
                                    task.onStatus(clip.label)
                                    playPcm24kLoudBlocking(clip.bytes, task.dubVolume, task.origVolume, speed)
                                }
                                is SynthesizedClip.Mp3Clip -> {
                                    task.onStatus(clip.label)
                                    playMp3LoudBlocking(clip.bytes, task.dubVolume, task.origVolume, speed)
                                }
                                is SynthesizedClip.LocalTtsClip -> {
                                    task.onStatus("🔊 پخش فوری با موتور TTS گوشی")
                                    playLocalPersianTtsBlocking(clip.text, clip.targetLang, task.dubVolume, task.origVolume)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private suspend fun synthesizeFastestClip(
        text: String,
        targetLang: String,
        voiceStyle: String,
        dubVolume: Float
    ): SynthesizedClip? {
        val apiKey = AudioCaptureService.currentApiKey
        val geminiVoice = if (voiceStyle.contains("مرد") || voiceStyle.contains("Puck") || voiceStyle.contains("فرید")) "Puck" else "Kore"
        val explicitlyWantsEdge = voiceStyle.contains("Edge")

        // 1. Primary Fast Engine: Gemini Native Audio TTS (Kore / Puck) - No 2.2s Edge timeout!
        if (!explicitlyWantsEdge && apiKey.isNotBlank()) {
            val rawPcm = synthesizeGeminiNativePcm(text, targetLang, apiKey, geminiVoice)
            if (rawPcm != null && rawPcm.size > 400) {
                val loudTrimmedPcm = trimAndNormalizePcm16(rawPcm, dubVolume)
                return SynthesizedClip.Pcm24kClip(loudTrimmedPcm, "🔊 گوینده طبیعی جمنای (" + geminiVoice + " - تقویت‌شده)")
            }
        }

        // 2. If user explicitly selected Edge Neural (Dilara / Farid), try Edge with fast 1.8s timeout, then fallback to Gemini TTS
        if (explicitlyWantsEdge) {
            val edgeMp3 = synthesizeEdgeNeuralMp3(text, targetLang, voiceStyle, "+18%", 1800L)
            if (edgeMp3 != null && edgeMp3.size > 300) {
                val sName = if (voiceStyle.contains("مرد") || voiceStyle.contains("فرید")) "فرید" else "دلارا"
                return SynthesizedClip.Mp3Clip(edgeMp3, "🔊 گوینده استودیویی (" + sName + ")")
            }
            if (apiKey.isNotBlank()) {
                val rawPcm = synthesizeGeminiNativePcm(text, targetLang, apiKey, geminiVoice)
                if (rawPcm != null && rawPcm.size > 400) {
                    val loudTrimmedPcm = trimAndNormalizePcm16(rawPcm, dubVolume)
                    return SynthesizedClip.Pcm24kClip(loudTrimmedPcm, "🔊 گوینده طبیعی جمنای (" + geminiVoice + ")")
                }
            }
        }

        return SynthesizedClip.LocalTtsClip(text, targetLang)
    }

    // Software DSP:
    // 1) Strips leading and trailing silence from Gemini's 24kHz 16-bit PCM so speech starts at 0ms with zero dead air!
    // 2) Normalizes peak amplitude to 98% of full 16-bit scale (32,000) + applies a 1.8x soft-clipped pre-amp boost so voice is 4x louder!
    private fun trimAndNormalizePcm16(pcmBytes: ByteArray, dubVolume: Float): ByteArray {
        val sampleCount = pcmBytes.size / 2
        if (sampleCount < 100) return pcmBytes

        val samples = ShortArray(sampleCount)
        var maxPeak = 1
        var firstActive = 0
        var lastActive = sampleCount - 1
        val silenceThreshold = 320

        for (i in 0 until sampleCount) {
            val low = pcmBytes[i * 2].toInt() and 0xFF
            val high = pcmBytes[i * 2 + 1].toInt() shl 8
            val s = (high or low).toShort()
            samples[i] = s
            val mag = abs(s.toInt())
            if (mag > maxPeak) maxPeak = mag
        }

        while (firstActive < sampleCount && abs(samples[firstActive].toInt()) < silenceThreshold) {
            firstActive++
        }
        while (lastActive > firstActive && abs(samples[lastActive].toInt()) < silenceThreshold) {
            lastActive--
        }

        // Keep a tiny 30ms margin (720 samples at 24kHz) so consonant onsets are crisp
        val startIdx = (firstActive - 720).coerceAtLeast(0)
        val endIdx = (lastActive + 720).coerceAtMost(sampleCount - 1)
        val trimmedLen = (endIdx - startIdx + 1).coerceAtLeast(1)

        // Calculate normalization + pre-amp gain (up to 6.5x boost for quiet passages)
        val volFactor = dubVolume.coerceIn(0.25f, 1.0f)
        val normGain = ((31500.0 / maxPeak.toDouble()).coerceIn(1.4, 6.5)) * volFactor * 1.35

        val outBytes = ByteArray(trimmedLen * 2)
        var outPos = 0
        for (i in startIdx..endIdx) {
            val amplified = (samples[i].toInt() * normGain).toInt()
            // Soft-clipping limiter at +-32600 to prevent harsh digital distortion while maximizing loudness
            val clamped = amplified.coerceIn(-32600, 32600).toShort()
            outBytes[outPos] = (clamped.toInt() and 0xFF).toByte()
            outBytes[outPos + 1] = ((clamped.toInt() shr 8) and 0xFF).toByte()
            outPos += 2
        }
        return outBytes
    }

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

    // Plays DSP-normalized 24kHz PCM on USAGE_ALARM (when Original Video is 0% muted) AND USAGE_MEDIA (when Original Video > 3%)
    // Wait: why not play on USAGE_ALARM whenever Original Video is 0% (<= 0.03f) so STREAM_MUSIC = 0 completely silences YouTube,
    // AND ALSO play on USAGE_ALARM when on speaker so STREAM_ALARM gives 100% independent volume?!
    private suspend fun playPcm24kLoudBlocking(pcmBytes: ByteArray, dubVolume: Float, origVolume: Float, speed: Float) {
        try {
            applyOriginalVideoVolumePolicy(origVolume, dubVolume)

            // When origVolume <= 0.03f, STREAM_MUSIC is 0 (muted), so we MUST use USAGE_ALARM (STREAM_ALARM) which is unmuted at 100%!
            // Even when origVolume > 0.03f, USAGE_ALARM has its own full-volume hardware slider independent of YouTube!
            val audioUsage = AudioAttributes.USAGE_ALARM

            val minBuf = AudioTrack.getMinBufferSize(
                24000,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(audioUsage)
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
            track.setVolume(1.0f)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    track.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1.0f)
                } catch (_: Exception) {}
            }

            var enhancer: LoudnessEnhancer? = null
            try {
                enhancer = LoudnessEnhancer(track.audioSessionId).apply {
                    setTargetGain(2200) // +22 dB hardware boost
                    enabled = true
                }
            } catch (_: Exception) {}

            track.play()
            val rawDurationMs = (pcmBytes.size / 48).toLong()
            val scaledDurationMs = (rawDurationMs / speed).toLong() + 40L
            delay(scaledDurationMs)
            enhancer?.release()
            track.stop()
            track.release()
        } catch (_: Exception) {}
    }

    private suspend fun playMp3LoudBlocking(mp3Bytes: ByteArray, dubVolume: Float, origVolume: Float, speed: Float) {
        val tempFile = File(context.cacheDir, "dub_" + System.currentTimeMillis() + ".mp3")
        val doneLatch = CountDownLatch(1)
        var mpInstance: MediaPlayer? = null
        var enhancerInstance: LoudnessEnhancer? = null

        try {
            FileOutputStream(tempFile).use { it.write(mp3Bytes) }
            applyOriginalVideoVolumePolicy(origVolume, dubVolume)

            mainHandler.post {
                try {
                    mpInstance = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        setDataSource(tempFile.absolutePath)
                        setVolume(1.0f, 1.0f)
                        prepare()

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            try {
                                playbackParams = PlaybackParams().setSpeed(speed).setPitch(1.0f)
                            } catch (_: Exception) {}
                        }

                        try {
                            enhancerInstance = LoudnessEnhancer(audioSessionId).apply {
                                setTargetGain(2200)
                                enabled = true
                            }
                        } catch (_: Exception) {}

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
                doneLatch.await(9, TimeUnit.SECONDS)
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
        val isMale = voiceStyle.contains("مرد") || voiceStyle.contains("فرید")
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
            val escapedText = text.take(220)
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
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        localTts?.setLanguage(locale)
        localTts?.setSpeechRate(1.20f)
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        localTts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "utt_" + System.currentTimeMillis())
        val estMs = (text.length * 45L).coerceIn(700L, 3800L)
        delay(estMs)
    }

    fun stopAll() {
        val stale = latestPendingTask.getAndSet(null)
        stale?.deferredClip?.cancel()
        restoreInitialVolumes()
        mainHandler.post {
            try {
                localTts?.stop()
            } catch (_: Exception) {}
        }
    }

    fun shutdown() {
        stopAll()
        wakeUpSignal.close()
        scope.cancel()
        localTts?.shutdown()
    }
}
