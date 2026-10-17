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

sealed class PreparedAudioClip {
    data class Mp3Clip(val bytes: ByteArray, val label: String) : PreparedAudioClip()
    data class Pcm24kClip(val bytes: ByteArray, val label: String) : PreparedAudioClip()
    data class LocalFallbackClip(val text: String, val targetLang: String) : PreparedAudioClip()
}

data class ReadyPlaybackItem(
    val clip: PreparedAudioClip,
    val dubVolume: Float,
    val origVolume: Float,
    val onStatus: (String) -> Unit
)

class VoiceSynthesizer(private val context: Context) : TextToSpeech.OnInitListener {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var localTts: TextToSpeech? = null
    private var localTtsReady = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var activeFocusRequest: AudioFocusRequest? = null

    private var savedMusicVolume: Int = -1
    private var savedAlarmVolume: Int = -1

    private val playbackQueue = Channel<ReadyPlaybackItem>(Channel.UNLIMITED)
    private val pendingPlaybackCount = AtomicInteger(0)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    init {
        localTts = TextToSpeech(context, this)
        startPlaybackQueueConsumer()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            localTtsReady = true
        }
    }

    fun saveInitialVolumes() {
        try {
            if (savedMusicVolume == -1) {
                val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                savedMusicVolume = if (cur > 0) cur else (max * 0.6f).roundToInt().coerceAtLeast(1)
            }
            if (savedAlarmVolume == -1) {
                savedAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
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

    private fun isBluetoothOrHeadsetConnected(): Boolean {
        return try {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
        } catch (_: Exception) {
            false
        }
    }

    // Immediate, reversible volume mixer for original video vs dubbed voice:
    // - Whenever user moves the slider to 0%, it mutes STREAM_MUSIC (on speaker) or ducks YouTube (on Bluetooth).
    // - Whenever user moves the slider back up (> 2%), it IMMEDIATELY unmutes STREAM_MUSIC and sets the exact volume!
    fun applyOriginalVideoVolumePolicy(origVolRatio: Float, dubVolRatio: Float) {
        try {
            saveInitialVolumes()
            val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)

            // Always ensure STREAM_ALARM has enough volume for speaker-mode dubbing
            val targetAlarm = (dubVolRatio.coerceIn(0.2f, 1f) * maxAlarm).roundToInt().coerceAtLeast(1)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetAlarm, 0)

            if (origVolRatio <= 0.02f) {
                // User set Original Video Volume to 0%
                if (!isBluetoothOrHeadsetConnected()) {
                    // On phone speaker: we can set STREAM_MUSIC to 0 because dubbing plays on STREAM_ALARM!
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                } else {
                    // On Bluetooth headphones: keep STREAM_MUSIC audible for Bluetooth A2DP and use AudioFocus Ducking
                    val minBtVol = (maxMusic * 0.25f).roundToInt().coerceAtLeast(1)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, minBtVol, 0)
                    requestYoutubeDucking()
                }
            } else {
                // User raised Original Video Volume (> 2%): IMMEDIATELY unmute and set proportional volume!
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                }
                val targetMusic = (origVolRatio.coerceIn(0.08f, 1f) * maxMusic).roundToInt().coerceAtLeast(1)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetMusic, 0)

                if (origVolRatio >= 0.55f) {
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
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
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

    // Synthesizes audio IMMEDIATELY in background (parallel pre-fetching) and enqueues ready audio for seamless playback!
    fun speak(
        text: String,
        targetLang: String,
        voiceStyle: String,
        apiKey: String,
        dubVolume: Float,
        origVolume: Float,
        onStatus: (String) -> Unit = {}
    ) {
        val cleanText = text.replace("*", "").replace("\"", "").replace("'", "").replace("\n", " ").trim()
        if (cleanText.length < 2) return

        // Drop stale items if backlog exceeds 2 so dubbing never falls behind the video
        if (pendingPlaybackCount.get() > 2) return

        scope.launch {
            val fastRate = if (pendingPlaybackCount.get() >= 1) "+16%" else "+8%"
            val clip = synthesizeBestClip(cleanText, targetLang, voiceStyle, apiKey, fastRate)
            if (clip != null) {
                pendingPlaybackCount.incrementAndGet()
                playbackQueue.trySend(ReadyPlaybackItem(clip, dubVolume, origVolume, onStatus))
            }
        }
    }

    private fun startPlaybackQueueConsumer() {
        scope.launch {
            for (item in playbackQueue) {
                try {
                    if (item.origVolume in 0.03f..0.60f) {
                        requestYoutubeDucking()
                    }
                    when (val c = item.clip) {
                        is PreparedAudioClip.Mp3Clip -> {
                            item.onStatus(c.label)
                            playMp3Blocking(c.bytes, item.dubVolume, item.origVolume)
                        }
                        is PreparedAudioClip.Pcm24kClip -> {
                            item.onStatus(c.label)
                            playPcm24kHzBlocking(c.bytes, item.dubVolume, item.origVolume)
                        }
                        is PreparedAudioClip.LocalFallbackClip -> {
                            item.onStatus("🔊 پخش با موتور TTS داخلی گوشی")
                            playLocalAndroidTtsBlocking(c.text, c.targetLang, item.dubVolume, item.origVolume)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    if ( pendingPlaybackCount.decrementAndGet() == 0 && item.origVolume > 0.05f ) {
                        abandonYoutubeDucking()
                    }
                }
            }
        }
    }

    private fun synthesizeBestClip(
        text: String,
        targetLang: String,
        voiceStyle: String,
        apiKey: String,
        rateStr: String
    ): PreparedAudioClip? {
        val prefersGeminiVoice = voiceStyle.contains("جمنای") || voiceStyle.contains("Gemini")

        if (prefersGeminiVoice && apiKey.isNotBlank()) {
            val geminiVoiceName = if (voiceStyle.contains("مرد") || voiceStyle.contains("Puck")) "Puck" else "Kore"
            val pcm = synthesizeGeminiTtsPcm(text, targetLang, apiKey, geminiVoiceName)
            if (pcm != null && pcm.size > 400) {
                return PreparedAudioClip.Pcm24kClip(pcm, "🔊 گوینده استودیویی جمنای (" + geminiVoiceName + ")")
            }
        }

        // Primary Studio Engine: Microsoft Edge Neural TTS (Fixed 64-bit Long Sec-MS-GEC token!)
        val edgeMp3 = synthesizeEdgeNeuralMp3(text, targetLang, voiceStyle, rateStr)
        if (edgeMp3 != null && edgeMp3.size > 200) {
            val vLabel = if (voiceStyle.contains("مرد")) "فرید - صدای مرد طبیعی" else "دلارا - صدای زن طبیعی"
            return PreparedAudioClip.Mp3Clip(edgeMp3, "🔊 گوینده استودیویی (" + vLabel + ")")
        }

        // Secondary Studio Engine: Gemini 2.5 Flash Native Audio TTS
        if (apiKey.isNotBlank()) {
            val geminiVoiceName = if (voiceStyle.contains("مرد")) "Puck" else "Kore"
            val pcm = synthesizeGeminiTtsPcm(text, targetLang, apiKey, geminiVoiceName)
            if (pcm != null && pcm.size > 400) {
                return PreparedAudioClip.Pcm24kClip(pcm, "🔊 گوینده هوشمند جمنای (" + geminiVoiceName + ")")
            }
        }

        return PreparedAudioClip.LocalFallbackClip(text, targetLang)
    }

    // FIXED: Uses exact 64-bit Long integer math (no Double precision loss!) so Bing Neural WebSocket never returns 403!
    private fun generateSecMsGec(): String {
        val unixSeconds = System.currentTimeMillis() / 1000L
        var ticks = unixSeconds + 11644473600L
        ticks -= (ticks % 300L)
        val fileTimeTicks = ticks * 10_000_000L
        val strToHash = fileTimeTicks.toString() + "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        val digest = MessageDigest.getInstance("SHA-256").digest(strToHash.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun generateRandomMuid(): String {
        return (UUID.randomUUID().toString().replace("-", "")).uppercase(Locale.US)
    }

    private fun getEdgeVoiceName(langName: String, voiceStyle: String): Pair<String, String> {
        val isMale = voiceStyle.contains("مرد") || voiceStyle.contains("Farid") || voiceStyle.contains("Puck")
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

    private fun synthesizeEdgeNeuralMp3(text: String, targetLang: String, voiceStyle: String, rateStr: String): ByteArray? {
        return try {
            val pair = getEdgeVoiceName(targetLang, voiceStyle)
            val langLocale = pair.first
            val voiceName = pair.second
            val secMsGec = generateSecMsGec()
            val connId = UUID.randomUUID().toString().replace("-", "")
            val muid = generateRandomMuid()
            val wsUrl = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
                    "?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4" +
                    "&Sec-MS-GEC=" + secMsGec +
                    "&Sec-MS-GEC-Version=1-132.0.2957.140" +
                    "&ConnectionId=" + connId

            val request = Request.Builder()
                .url(wsUrl)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36 Edg/132.0.0.0")
                .header("Cookie", "MUID=" + muid + ";")
                .build()

            val audioOut = ByteArrayOutputStream()
            val latch = CountDownLatch(1)
            val escapedText = text.take(260)
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
                    val timestamp = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US).format(Date())
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

            latch.await(3200, TimeUnit.MILLISECONDS)
            ws.cancel()
            val result = audioOut.toByteArray()
            if (result.isNotEmpty()) result else null
        } catch (_: Exception) {
            null
        }
    }

    private fun synthesizeGeminiTtsPcm(text: String, targetLang: String, apiKey: String, voiceName: String): ByteArray? {
        return try {
            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(
                        JSONObject().put("text", "Say in natural " + targetLang + ": " + text)
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

    private suspend fun playMp3Blocking(mp3Bytes: ByteArray, dubVolume: Float, origVolume: Float) {
        val tempFile = File(context.cacheDir, "dub_" + System.currentTimeMillis() + ".mp3")
        val doneLatch = CountDownLatch(1)
        var mpInstance: MediaPlayer? = null
        var enhancerInstance: LoudnessEnhancer? = null

        try {
            FileOutputStream(tempFile).use { it.write(mp3Bytes) }

            val useAlarmChannel = (origVolume <= 0.02f && !isBluetoothOrHeadsetConnected())
            val audioUsage = if (useAlarmChannel) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA

            mainHandler.post {
                try {
                    mpInstance = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(audioUsage)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        setDataSource(tempFile.absolutePath)
                        val vol = dubVolume.coerceIn(0f, 1f)
                        setVolume(vol, vol)
                        prepare()

                        if (dubVolume > 0.4f) {
                            try {
                                enhancerInstance = LoudnessEnhancer(audioSessionId).apply {
                                    val boostMb = ((dubVolume - 0.4f) * 2500).toInt()
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
                doneLatch.await(12, TimeUnit.SECONDS)
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

    private suspend fun playPcm24kHzBlocking(pcmBytes: ByteArray, dubVolume: Float, origVolume: Float) {
        try {
            val useAlarmChannel = (origVolume <= 0.02f && !isBluetoothOrHeadsetConnected())
            val audioUsage = if (useAlarmChannel) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA

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
            track.setVolume(dubVolume.coerceIn(0f, 1f))
            var enhancer: LoudnessEnhancer? = null
            if (dubVolume > 0.4f) {
                try {
                    enhancer = LoudnessEnhancer(track.audioSessionId).apply {
                        setTargetGain(((dubVolume - 0.4f) * 2500).toInt())
                        enabled = true
                    }
                } catch (_: Exception) {}
            }
            track.play()
            val durationMs = (pcmBytes.size / 48).toLong() + 120L
            delay(durationMs)
            enhancer?.release()
            track.stop()
            track.release()
        } catch (_: Exception) {}
    }

    private suspend fun playLocalAndroidTtsBlocking(text: String, targetLang: String, dubVolume: Float, origVolume: Float) {
        if (!localTtsReady) return
        val locale = when (targetLang) {
            "فارسی" -> Locale("fa", "IR")
            "انگلیسی" -> Locale.US
            "عربی" -> Locale("ar")
            "ترکی استانبولی" -> Locale("tr", "TR")
            "فرانسوی" -> Locale.FRENCH
            "آلمانی" -> Locale.GERMAN
            else -> Locale("fa", "IR")
        }
        val useAlarmChannel = (origVolume <= 0.02f && !isBluetoothOrHeadsetConnected())
        val audioUsage = if (useAlarmChannel) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA

        localTts?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(audioUsage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        localTts?.setLanguage(locale)
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, dubVolume.coerceIn(0f, 1f))
        localTts?.speak(text, TextToSpeech.QUEUE_ADD, params, "utt_" + System.currentTimeMillis())
        val estMs = (text.length * 60L).coerceIn(1000L, 5000L)
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
        playbackQueue.close()
        scope.cancel()
        localTts?.shutdown()
    }
}
