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

class VoiceSynthesizer(private val context: Context) : TextToSpeech.OnInitListener {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var localTts: TextToSpeech? = null
    private var localTtsReady = false
    private var mediaPlayer: MediaPlayer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var activeFocusRequest: AudioFocusRequest? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    init {
        localTts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            localTtsReady = true
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            localTts?.setAudioAttributes(attrs)
        }
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
        val cleanText = text.replace("*", "").replace("\"", "").replace("'", "").trim()
        if (cleanText.isEmpty()) return

        scope.launch {
            if (origVolume < 0.85f) {
                requestYoutubeDucking(origVolume)
            }

            // Engine 1: Microsoft Edge Neural TTS (fa-IR-DilaraNeural)
            val edgeMp3 = synthesizeEdgeNeuralMp3(cleanText, targetLang)
            if (edgeMp3 != null && edgeMp3.size > 200) {
                onStatus("🔊 پخش صدای گوینده طبیعی فارسی (Edge Neural)")
                playMp3Bytes(edgeMp3, dubVolume, origVolume)
                return@launch
            }

            // Engine 2: Gemini 2.5 Flash Native Audio TTS
            if (apiKey.isNotBlank()) {
                val geminiPcm = synthesizeGeminiTtsPcm(cleanText, targetLang, apiKey)
                if (geminiPcm != null && geminiPcm.size > 400) {
                    onStatus("🔊 پخش صدای گوینده هوش مصنوعی (Gemini TTS)")
                    playPcm24kHzBytes(geminiPcm, dubVolume, origVolume)
                    return@launch
                }
            }

            // Engine 3: Google GTX Stream
            val gtxMp3 = synthesizeGoogleGtxMp3(cleanText, targetLang)
            if (gtxMp3 != null && gtxMp3.size > 200) {
                onStatus("🔊 پخش صدای گوینده جایگزین (GTX Audio)")
                playMp3Bytes(gtxMp3, dubVolume, origVolume)
                return@launch
            }

            // Engine 4: Local Android TextToSpeech
            onStatus("🔊 پخش با موتور TTS داخلی گوشی")
            playLocalAndroidTts(cleanText, targetLang, dubVolume, origVolume)
        }
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

    private fun synthesizeEdgeNeuralMp3(text: String, targetLang: String): ByteArray? {
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
                    val timestamp = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US).format(Date())
                    val crlf = "\r\n"
                    val configMsg = "X-Timestamp:" + timestamp + crlf +
                            "Content-Type:application/json; charset=utf-8" + crlf +
                            "Path:speech.config" + crlf + crlf +
                            configJson
                    webSocket.send(configMsg)

                    val reqId = UUID.randomUUID().toString().replace("-", "")
                    val ssmlBody = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='" + langLocale + "'>" +
                            "<voice name='" + voiceName + "'><prosody pitch='+0Hz' rate='+15%' volume='+0%'>" + escapedText + "</prosody></voice></speak>"
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

            latch.await(4500, TimeUnit.MILLISECONDS)
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
                        JSONObject().put("text", "Read this " + targetLang + " text aloud naturally and clearly: " + text)
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
            val encoded = URLEncoder.encode(text.take(160), "UTF-8")
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

    private fun playMp3Bytes(mp3Bytes: ByteArray, dubVolume: Float, origVolume: Float) {
        try {
            val tempFile = File(context.cacheDir, "dub_" + System.currentTimeMillis() + ".mp3")
            FileOutputStream(tempFile).use { it.write(mp3Bytes) }

            mainHandler.post {
                try {
                    mediaPlayer?.release()
                    loudnessEnhancer?.release()

                    mediaPlayer = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        setDataSource(tempFile.absolutePath)
                        val vol = dubVolume.coerceIn(0f, 1f)
                        setVolume(vol, vol)
                        prepare()

                        if (dubVolume > 0.5f) {
                            try {
                                loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                                    val boostMb = ((dubVolume - 0.5f) * 2400).toInt()
                                    setTargetGain(boostMb)
                                    enabled = true
                                }
                            } catch (_: Exception) {}
                        }

                        setOnCompletionListener { mp ->
                            mp.release()
                            tempFile.delete()
                            if (origVolume in 0.35f..0.85f) {
                                abandonYoutubeDucking()
                            }
                        }
                        start()
                    }
                } catch (_: Exception) {
                    tempFile.delete()
                }
            }
        } catch (_: Exception) {}
    }

    private fun playPcm24kHzBytes(pcmBytes: ByteArray, dubVolume: Float, origVolume: Float) {
        scope.launch {
            try {
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
                track.setVolume(dubVolume.coerceIn(0f, 1f))
                if (dubVolume > 0.5f) {
                    try {
                        LoudnessEnhancer(track.audioSessionId).apply {
                            setTargetGain(((dubVolume - 0.5f) * 2400).toInt())
                            enabled = true
                        }
                    } catch (_: Exception) {}
                }
                track.play()
                val durationMs = (pcmBytes.size / 48).toLong() + 200L
                delay(durationMs)
                track.stop()
                track.release()
                if (origVolume in 0.35f..0.85f) {
                    abandonYoutubeDucking()
                }
            } catch (_: Exception) {}
        }
    }

    private fun playLocalAndroidTts(text: String, targetLang: String, dubVolume: Float, origVolume: Float) {
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
        val res = localTts?.setLanguage(locale)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            localTts?.setLanguage(Locale("ur"))
        }
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, dubVolume.coerceIn(0f, 1f))
        localTts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "utt_" + System.currentTimeMillis())
    }

    fun stopAll() {
        abandonYoutubeDucking()
        mainHandler.post {
            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
                loudnessEnhancer?.release()
                loudnessEnhancer = null
                localTts?.stop()
            } catch (_: Exception) {}
        }
    }

    fun shutdown() {
        stopAll()
        scope.cancel()
        localTts?.shutdown()
    }
}
