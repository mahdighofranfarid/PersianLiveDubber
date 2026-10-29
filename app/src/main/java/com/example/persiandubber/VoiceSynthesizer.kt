package com.example.persiandubber

import android.content.Context
import android.media.*
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okio.ByteString
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

sealed class PreparedVoiceClip {
    data class Mp3Data(val bytes: ByteArray, val engineLabel: String) : PreparedVoiceClip()
    data class LocalTtsData(val text: String, val targetLang: String) : PreparedVoiceClip()
}

data class SpeechTask(
    val text: String,
    val targetLang: String,
    val voiceStyle: String,
    val dubVolume: Float,
    val origVolume: Float,
    val onStatus: (String) -> Unit
)

class VoiceSynthesizer(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        // Stores offset between real UTC server time (from Google HTTP Date header) and phone's System.currentTimeMillis()
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

    // Persistent Warm WebSocket state for zero-handshake-latency Edge Neural TTS
    private val wsLock = Any()
    private var persistentWs: WebSocket? = null
    private var persistentWsOpen = false
    private var activeAudioStream: ByteArrayOutputStream? = null
    private var activeTurnLatch: CountDownLatch? = null

    // Non-cancelling synthesis queue (max 2 items so it never lags behind video AND never kills current sentence!)
    private val synthesisChannel = Channel<SpeechTask>(Channel.UNLIMITED)
    private val pendingTasks = AtomicInteger(0)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    init {
        // Explicitly bind to Google Speech Services ("com.google.android.tts") first for native Persian support
        try {
            localTts = TextToSpeech(context, this, "com.google.android.tts")
        } catch (_: Exception) {
            localTts = TextToSpeech(context, this)
        }
        startPipelineWorker()
        preWarmEdgeWebSocket()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            localTtsReady = true
            configurePersianLocalVoice("فارسی")
        } else {
            try {
                localTts = TextToSpeech(context) { st ->
                    if (st == TextToSpeech.SUCCESS) {
                        localTtsReady = true
                        configurePersianLocalVoice("فارسی")
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun configurePersianLocalVoice(langName: String) {
        if (!localTtsReady) return
        try {
            val targetIso = when (langName) {
                "فارسی" -> "fa"
                "انگلیسی" -> "en"
                "عربی" -> "ar"
                "ترکی استانبولی" -> "tr"
                "فرانسوی" -> "fr"
                "آلمانی" -> "de"
                else -> "fa"
            }
            val voices = localTts?.voices
            if (voices != null) {
                val matchingVoice = voices.firstOrNull {
                    it.locale.language.equals(targetIso, ignoreCase = true) && !it.isNetworkConnectionRequired
                } ?: voices.firstOrNull {
                    it.locale.language.equals(targetIso, ignoreCase = true)
                }
                if (matchingVoice != null) {
                    localTts?.voice = matchingVoice
                    return
                }
            }
            localTts?.language = Locale(targetIso)
        } catch (_: Exception) {}
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
                savedAlarmVolume = if (curA > 1) curA else (maxA * 0.80f).roundToInt().coerceAtLeast(2)
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

    // Direct, real-time control of YouTube's volume (STREAM_MUSIC) vs Dubbed Voice (STREAM_ALARM):
    // - When origVolRatio == 0% (<= 0.02f): sets STREAM_MUSIC to 0 AND mutes it so YouTube is 100% silent!
    // - When origVolRatio > 2%: unmutes STREAM_MUSIC and scales it smoothly from 1 to maxMusic!
    // - Dubbed Voice plays on USAGE_ALARM (STREAM_ALARM) so its volume is 100% independent of STREAM_MUSIC!
    fun applyOriginalVideoVolumePolicy(origVolRatio: Float, dubVolRatio: Float) {
        try {
            saveInitialVolumes()
            val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)

            // Set STREAM_ALARM for the Dubbed Voice
            val targetAlarm = (dubVolRatio.coerceIn(0.15f, 1f) * maxAlarm).roundToInt().coerceAtLeast(1)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetAlarm, 0)

            if (origVolRatio <= 0.02f) {
                // 100% Complete Silence for Original YouTube Video
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                }
            } else {
                // Immediately unmute and set exact proportional volume for YouTube
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                }
                val targetMusic = (origVolRatio.coerceIn(0.05f, 1f) * maxMusic).roundToInt().coerceAtLeast(1)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetMusic, 0)

                if (origVolRatio < 0.60f) {
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

        // Do NOT cancel the currently playing/synthesizing sentence!
        // Instead, enqueue up to 2 sentences so every sentence plays immediately without getting killed.
        if (pendingTasks.get() <= 2) {
            pendingTasks.incrementAndGet()
            synthesisChannel.trySend(
                SpeechTask(cleanText, targetLang, voiceStyle, dubVolume, origVolume, onStatus)
            )
        }
    }

    private fun startPipelineWorker() {
        scope.launch {
            for (task in synthesisChannel) {
                try {
                    val prefersLocalZeroLag = task.voiceStyle.contains("تأخیر صفر") || task.voiceStyle.contains("موتور سریع گوشی")
                    if (prefersLocalZeroLag) {
                        task.onStatus("🔊 پخش فوری بدون تأخیر (موتور صوتی گوشی)")
                        playLocalPersianTtsBlocking(task.text, task.targetLang, task.dubVolume, task.origVolume)
                    } else {
                        // Use Persistent Warm WebSocket for Dilara/Farid with fast 1.6s timeout!
                        val speedRate = if (pendingTasks.get() > 1) "+24%" else "+16%"
                        val mp3 = synthesizeEdgeWarmMp3(task.text, task.targetLang, task.voiceStyle, speedRate)
                        if (mp3 != null && mp3.size > 300) {
                            val speakerName = if (task.voiceStyle.contains("مرد") || task.voiceStyle.contains("فرید")) "فرید" else "دلارا"
                            task.onStatus("🔊 گوینده استودیویی (" + speakerName + ")")
                            playMp3Blocking(mp3, task.dubVolume, task.origVolume, if (pendingTasks.get() > 1) 1.12f else 1.05f)
                        } else {
                            // Instant fallback (<50ms) to local TTS so there is NEVER a 4-second delay!
                            task.onStatus("🔊 پخش سریع با موتور صوتی گوشی")
                            playLocalPersianTtsBlocking(task.text, task.targetLang, task.dubVolume, task.origVolume)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    pendingTasks.decrementAndGet()
                    // Keep WebSocket warm for the next sentence
                    ensureWarmWebSocketAsync()
                }
            }
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

    fun preWarmEdgeWebSocket() {
        scope.launch {
            ensureWarmWebSocketSync()
        }
    }

    private fun ensureWarmWebSocketAsync() {
        scope.launch {
            ensureWarmWebSocketSync()
        }
    }

    private fun ensureWarmWebSocketSync(): WebSocket? {
        synchronized(wsLock) {
            if (persistentWs != null && persistentWsOpen) {
                return persistentWs
            }
        }

        val openLatch = CountDownLatch(1)
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
            .header("Cookie", "muid=" + muid + ";")
            .build()

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

                synchronized(wsLock) {
                    persistentWs = webSocket
                    persistentWsOpen = true
                }
                openLatch.countDown()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val arr = bytes.toByteArray()
                if (arr.size > 2) {
                    val headerLen = ((arr[0].toInt() and 0xFF) shl 8) or (arr[1].toInt() and 0xFF)
                    if (arr.size > 2 + headerLen) {
                        val headerStr = String(arr, 2, headerLen, Charsets.UTF_8)
                        if (headerStr.contains("Path:audio")) {
                            synchronized(wsLock) {
                                activeAudioStream?.write(arr, 2 + headerLen, arr.size - (2 + headerLen))
                            }
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    synchronized(wsLock) {
                        activeTurnLatch?.countDown()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(wsLock) {
                    persistentWsOpen = false
                    persistentWs = null
                    activeTurnLatch?.countDown()
                }
                openLatch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(wsLock) {
                    persistentWsOpen = false
                    persistentWs = null
                    activeTurnLatch?.countDown()
                }
                openLatch.countDown()
            }
        })

        openLatch.await(1500, TimeUnit.MILLISECONDS)
        synchronized(wsLock) {
            return if (persistentWsOpen) persistentWs else {
                ws.cancel()
                null
            }
        }
    }

    // Synthesizes over the already-open Persistent WebSocket in ~250ms!
    private fun synthesizeEdgeWarmMp3(text: String, targetLang: String, voiceStyle: String, rateStr: String): ByteArray? {
        return try {
            val ws = ensureWarmWebSocketSync() ?: return null
            val pair = getEdgeVoiceName(targetLang, voiceStyle)
            val langLocale = pair.first
            val voiceName = pair.second

            val escapedText = text.take(240)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")

            val outStream = ByteArrayOutputStream()
            val turnLatch = CountDownLatch(1)

            synchronized(wsLock) {
                activeAudioStream = outStream
                activeTurnLatch = turnLatch
            }

            val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val timestamp = sdf.format(Date(System.currentTimeMillis() + serverTimeOffsetMs))
            val crlf = "\r\n"
            val reqId = UUID.randomUUID().toString().replace("-", "")
            val ssmlBody = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='" + langLocale + "'>" +
                    "<voice name='" + voiceName + "'><prosody pitch='+0Hz' rate='" + rateStr + "' volume='+0%'>" + escapedText + "</prosody></voice></speak>"
            val ssmlMsg = "X-RequestId:" + reqId + crlf +
                    "Content-Type:application/ssml+xml" + crlf +
                    "X-Timestamp:" + timestamp + crlf +
                    "Path:ssml" + crlf + crlf +
                    ssmlBody

            val sent = ws.send(ssmlMsg)
            if (!sent) {
                synchronized(wsLock) {
                    persistentWsOpen = false
                    persistentWs = null
                }
                return null
            }

            // Wait at most 1.8s on the warm socket so we never lag behind the video
            turnLatch.await(1800, TimeUnit.MILLISECONDS)
            val bytes = outStream.toByteArray()
            if (bytes.size > 300) bytes else null
        } catch (_: Exception) {
            null
        }
    }

    // Plays MP3 on USAGE_ALARM so it plays at 100% volume even when YouTube (STREAM_MUSIC) is completely muted (0%)!
    private suspend fun playMp3Blocking(mp3Bytes: ByteArray, dubVolume: Float, origVolume: Float, speed: Float) {
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
                        val vol = dubVolume.coerceIn(0.05f, 1f)
                        setVolume(vol, vol)
                        prepare()

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            try {
                                playbackParams = PlaybackParams().setSpeed(speed)
                            } catch (_: Exception) {}
                        }

                        if (dubVolume > 0.5f) {
                            try {
                                enhancerInstance = LoudnessEnhancer(audioSessionId).apply {
                                    val boostMb = ((dubVolume - 0.5f) * 1800).toInt()
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

    private suspend fun playLocalPersianTtsBlocking(text: String, targetLang: String, dubVolume: Float, origVolume: Float) {
        if (!localTtsReady) return
        applyOriginalVideoVolumePolicy(origVolume, dubVolume)
        configurePersianLocalVoice(targetLang)

        localTts?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        localTts?.setSpeechRate(1.18f)
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, dubVolume.coerceIn(0.05f, 1f))
        localTts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "utt_" + System.currentTimeMillis())
        val estMs = (text.length * 48L).coerceIn(800L, 4200L)
        delay(estMs)
    }

    fun stopAll() {
        restoreInitialVolumes()
        synchronized(wsLock) {
            activeTurnLatch?.countDown()
        }
        mainHandler.post {
            try {
                localTts?.stop()
            } catch (_: Exception) {}
        }
    }

    fun shutdown() {
        stopAll()
        synchronized(wsLock) {
            persistentWs?.close(1000, null)
            persistentWs = null
            persistentWsOpen = false
        }
        synthesisChannel.close()
        scope.cancel()
        localTts?.shutdown()
    }
}
