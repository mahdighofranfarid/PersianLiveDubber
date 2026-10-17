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
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class QueuedSpeechItem(
    val text: String,
    val targetLang: String,
    val apiKey: String,
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
    private var isMusicMutedByUs = false

    private val speechQueue = Channel<QueuedSpeechItem>(Channel.UNLIMITED)
    private val pendingQueueCount = AtomicInteger(0)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    init {
        localTts = TextToSpeech(context, this)
        startSpeechQueueConsumer()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            localTtsReady = true
        }
    }

    fun saveInitialVolumes() {
        try {
            if (savedMusicVolume == -1) {
                savedMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
            if (savedAlarmVolume == -1) {
                savedAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            }
        } catch (_: Exception) {}
    }

    fun restoreInitialVolumes() {
        try {
            abandonYoutubeDucking()
            if (savedMusicVolume != -1) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
                savedMusicVolume = -1
            }
            if (savedAlarmVolume != -1) {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
                savedAlarmVolume = -1
            }
            isMusicMutedByUs = false
        } catch (_: Exception) {}
    }

    // Applies the user's exact preference for original video volume:
    // - If origVol <= 0.05f (0% - 5%): 100% Mutes STREAM_MUSIC so YouTube has ZERO sound, and routes dubbing to STREAM_ALARM!
    // - If origVol > 0.05f: Restores STREAM_MUSIC and uses AudioFocus Ducking + proportional level!
    fun applyOriginalVideoVolumePolicy(origVolRatio: Float, dubVolRatio: Float) {
        try {
            saveInitialVolumes()
            if (origVolRatio <= 0.05f) {
                // Complete Mute of Original Video
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                isMusicMutedByUs = true
                val maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                val targetAlarm = (dubVolRatio.coerceIn(0.15f, 1f) * maxAlarm).toInt().coerceAtLeast(1)
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetAlarm, 0)
                requestYoutubeDucking(0f)
            } else {
                // Mix Mode: restore STREAM_MUSIC if we muted it earlier, or scale it smoothly
                if (isMusicMutedByUs && savedMusicVolume != -1) {
                    val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val targetMusic = (origVolRatio.coerceIn(0.1f, 1f) * maxMusic).toInt().coerceAtLeast(1)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetMusic, 0)
                    isMusicMutedByUs = false
                }
                if (origVolRatio < 0.85f) {
                    requestYoutubeDucking(origVolRatio)
                } else {
                    abandonYoutubeDucking()
                }
            }
        } catch (_: Exception) {}
    }

    fun requestYoutubeDucking(origVolRatio: Float) {
        if (origVolRatio >= 0.85f) {
            abandonYoutubeDucking()
            return
        }
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

    fun speak(
        text: String,
        targetLang: String,
        apiKey: String,
        dubVolume: Float,
        origVolume: Float,
        onStatus: (String) -> Unit = {}
    ) {
        val cleanText = text.replace("*", "").replace("\"", "").replace("'", "").replace("\n", " ").trim()
        if (cleanText.length < 2) return

        // If queue has too many old items (> 3), drop stale ones so live dubbing stays in sync
        if (pendingQueueCount.get() <= 3) {
            pendingQueueCount.incrementAndGet()
            speechQueue.trySend(QueuedSpeechItem(cleanText, targetLang, apiKey, dubVolume, origVolume, onStatus))
        }
    }

    private fun startSpeechQueueConsumer() {
        scope.launch {
            for (item in speechQueue) {
                try {
                    processAndPlaySpeechItem(item)
                } catch (_: Exception) {
                } finally {
                    pendingQueueCount.decrementAndGet()
                }
            }
        }
    }

    private suspend fun processAndPlaySpeechItem(item: QueuedSpeechItem) {
        applyOriginalVideoVolumePolicy(item.origVolume, item.dubVolume)

        // Dynamic speed: if another sentence is already waiting in queue, speak slightly faster (+25%) so it flows smoothly without lag!
        val fastRate = if (pendingQueueCount.get() > 1) "+26%" else "+14%"
        val playbackSpeed = if (pendingQueueCount.get() > 1) 1.22f else 1.10f

        // 1. Try Engine 1: Microsoft Edge Neural TTS (Natural Persian Voice: DilaraNeural)
        val edgeMp3 = synthesizeEdgeNeuralMp3(item.text, item.targetLang, fastRate)
        if (edgeMp3 != null && edgeMp3.size > 200) {
            item.onStatus("🔊 دوبله روان (گوینده طبیعی فارسی)")
            playMp3Blocking(edgeMp3, item.dubVolume, item.origVolume, 1.05f)
            return
        }

        // 2. Try Engine 2: Gemini 2.5 Flash Native Audio TTS
        if (item.apiKey.isNotBlank()) {
            val geminiPcm = synthesizeGeminiTtsPcm(item.text, item.targetLang, item.apiKey)
            if (geminiPcm != null && geminiPcm.size > 400) {
                item.onStatus("🔊 دوبله روان (گوینده Gemini TTS)")
                playPcm24kHzBlocking(geminiPcm, item.dubVolume, item.origVolume)
                return
            }
        }

        // 3. Try Engine 3: Google GTX Stream
        val gtxMp3 = synthesizeGoogleGtxMp3(item.text, item.targetLang)
        if (gtxMp3 != null && gtxMp3.size > 200) {
            item.onStatus("🔊 دوبله روان (موتور صوتی جایگزین)")
            playMp3Blocking(gtxMp3, item.dubVolume, item.origVolume, playbackSpeed)
            return
        }

        // 4. Engine 4: Local Android TTS
        item.onStatus("🔊 پخش با موتور TTS داخلی")
        playLocalAndroidTtsBlocking(item.text, item.targetLang, item.dubVolume, item.origVolume)
    }

    private fun generateSecMsGec(): String {
        var ticks = (System.currentTimeMillis() / 1000.0) + 11644473600.0
        ticks -= ticks % 300.0
        ticks *= 1e7
        val strToHash = String.format(Locale.US, "%.0f", ticks) + "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        val digest = MessageDigest.getInstance("SHA-256").digest(strToHash.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun getEdgeVoiceName(langName: String): Pair<String, String> {
        return when (langName) {
            "فارسی" -> Pair("fa-IR", "fa-IR-DilaraNeural")
            "انگلیسی" -> Pair("en-US", "en-US-AriaNeural")
            "عربی" -> Pair("ar-SA", "ar-SA-ZariyahNeural")
            "ترکی استانبولی" -> Pair("tr-TR", "tr-TR-EmelNeural")
            "فرانسوی" -> Pair("fr-FR", "fr-FR-DeniseNeural")
            "آلمانی" -> Pair("de-DE", "de-DE-KatjaNeural")
            else -> Pair("fa-IR", "fa-IR-DilaraNeural")
        }
    }

    private fun synthesizeEdgeNeuralMp3(text: String, targetLang: String, rateStr: String): ByteArray? {
        return try {
            val pair = getEdgeVoiceName(targetLang)
            val langLocale = pair.first
            val voiceName = pair.second
            val secMsGec = generateSecMsGec()
            val connId = UUID.randomUUID().toString().replace("-", "")
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
                .build()

            val audioOut = ByteArrayOutputStream()
            val latch = CountDownLatch(1)
            val escapedText = text.take(280)
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

            latch.await(5000, TimeUnit.MILLISECONDS)
            ws.cancel()
            val result = audioOut.toByteArray()
            if (result.isNotEmpty()) result else null
        } catch (_: Exception) {
            null
        }
    }

    private fun synthesizeGeminiTtsPcm(text: String, targetLang: String, apiKey: String): ByteArray? {
        return try {
            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(
                        JSONObject().put("text", "Read this " + targetLang + " sentence smoothly and naturally like a movie dubber: " + text)
                    ))
                ))
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("speechConfig", JSONObject().put("voiceConfig",
                        JSONObject().put("prebuiltVoiceConfig",
                            JSONObject().put("voiceName", "Aoede")
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

    private fun synthesizeGoogleGtxMp3(text: String, targetLang: String): ByteArray? {
        return try {
            val tl = when (targetLang) {
                "فارسی" -> "ur"
                "انگلیسی" -> "en"
                "عربی" -> "ar"
                "ترکی استانبولی" -> "tr"
                "فرانسوی" -> "fr"
                "آلمانی" -> "de"
                else -> "ur"
            }
            val encoded = URLEncoder.encode(text.take(200), "UTF-8")
            val url = "https://translate.googleapis.com/translate_tts?ie=UTF-8&client=gtx&tl=" + tl + "&q=" + encoded
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    // Plays MP3 and waits until the sentence finishes completely so the next sentence never cuts it off!
    private suspend fun playMp3Blocking(mp3Bytes: ByteArray, dubVolume: Float, origVolume: Float, speed: Float) {
        val tempFile = File(context.cacheDir, "dub_" + System.currentTimeMillis() + ".mp3")
        val doneLatch = CountDownLatch(1)
        var mpInstance: MediaPlayer? = null
        var enhancerInstance: LoudnessEnhancer? = null

        try {
            FileOutputStream(tempFile).use { it.write(mp3Bytes) }

            // If original video is muted (<= 5%), play on USAGE_ALARM so it plays loudly even while STREAM_MUSIC is 0!
            // Otherwise play on USAGE_MEDIA with LoudnessEnhancer!
            val audioUsage = if (origVolume <= 0.05f) {
                AudioAttributes.USAGE_ALARM
            } else {
                AudioAttributes.USAGE_MEDIA
            }

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

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            try {
                                playbackParams = PlaybackParams().setSpeed(speed)
                            } catch (_: Exception) {}
                        }

                        if (dubVolume > 0.4f) {
                            try {
                                enhancerInstance = LoudnessEnhancer(audioSessionId).apply {
                                    val boostMb = ((dubVolume - 0.4f) * 2500).toInt()
                                    setTargetGain(boostMb)
                                    enabled = true
                                }
                            } catch (_: Exception) {}
                        }

                        setOnCompletionListener {
                            doneLatch.countDown()
                        }
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
            if (origVolume in 0.35f..0.85f && pendingQueueCount.get() <= 1) {
                abandonYoutubeDucking()
            }
        }
    }

    private suspend fun playPcm24kHzBlocking(pcmBytes: ByteArray, dubVolume: Float, origVolume: Float) {
        try {
            val audioUsage = if (origVolume <= 0.05f) {
                AudioAttributes.USAGE_ALARM
            } else {
                AudioAttributes.USAGE_MEDIA
            }
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
            val durationMs = (pcmBytes.size / 48).toLong() + 150L
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
        val audioUsage = if (origVolume <= 0.05f) {
            AudioAttributes.USAGE_ALARM
        } else {
            AudioAttributes.USAGE_MEDIA
        }
        localTts?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(audioUsage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        val res = localTts?.setLanguage(locale)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            localTts?.setLanguage(Locale("ur"))
        }
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, dubVolume.coerceIn(0f, 1f))
        localTts?.speak(text, TextToSpeech.QUEUE_ADD, params, "utt_" + System.currentTimeMillis())
        val estMs = (text.length * 65L).coerceIn(1200L, 6000L)
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
        speechQueue.close()
        scope.cancel()
        localTts?.shutdown()
    }
}
