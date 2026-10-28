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
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var activeFocusRequest: AudioFocusRequest? = null

    private var currentMediaPlayer: MediaPlayer? = null
    private var currentEnhancer: LoudnessEnhancer? = null
    private var activeSpeechJob: Job? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    init {
        localTts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            localTtsReady = true
            localTts?.setLanguage(Locale("fa", "IR"))
        }
    }

    fun saveInitialVolumes() {
        // Do not alter hardware STREAM_MUSIC volume so phone volume and YouTube audio never get stuck
    }

    fun restoreInitialVolumes() {
        abandonYoutubeDucking()
    }

    fun applyOriginalVideoVolumePolicy(origVolRatio: Float, dubVolRatio: Float) {
        if (origVolRatio < 0.70f) {
            requestYoutubeDucking()
        } else {
            abandonYoutubeDucking()
        }
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

        activeSpeechJob?.cancel()
        activeSpeechJob = scope.launch {
            onStatus("⏳ در حال تولید صدای طبیعی فارسی...")

            val prefersGemini = voiceStyle.contains("جمنای") || voiceStyle.contains("Kore") || voiceStyle.contains("Puck")
            val apiKey = AudioCaptureService.currentApiKey

            if (prefersGemini && apiKey.isNotBlank()) {
                val geminiVoice = if (voiceStyle.contains("مرد") || voiceStyle.contains("Puck")) "Puck" else "Kore"
                val pcmBytes = synthesizeGeminiNativePcm(cleanText, targetLang, apiKey, geminiVoice)
                if (!isActive) return@launch
                if (pcmBytes != null && pcmBytes.size > 400) {
                    onStatus("🔊 گوینده طبیعی جمنای (" + geminiVoice + ")")
                    playPcm24kHzImmediate(pcmBytes, dubVolume, origVolume)
                    return@launch
                }
            }

            // 1. Primary Studio Engine: Microsoft Edge Neural TTS (fa-IR-DilaraNeural / fa-IR-FaridNeural)
            val edgeMp3 = synthesizeEdgeNeuralMp3(cleanText, targetLang, voiceStyle)
            if (!isActive) return@launch
            if (edgeMp3 != null && edgeMp3.size > 300) {
                val speakerName = if (voiceStyle.contains("مرد") || voiceStyle.contains("فرید")) "فرید (مرد طبیعی)" else "دلارا (زن طبیعی)"
                onStatus("🔊 گوینده استودیویی: " + speakerName)
                playMp3Immediate(edgeMp3, dubVolume, origVolume)
                return@launch
            }

            // 2. Secondary Studio Engine: Gemini Native Audio TTS
            if (apiKey.isNotBlank()) {
                val geminiVoice = if (voiceStyle.contains("مرد") || voiceStyle.contains("فرید")) "Puck" else "Kore"
                val pcmBytes = synthesizeGeminiNativePcm(cleanText, targetLang, apiKey, geminiVoice)
                if (!isActive) return@launch
                if (pcmBytes != null && pcmBytes.size > 400) {
                    onStatus("🔊 گوینده طبیعی جمنای (" + geminiVoice + ")")
                    playPcm24kHzImmediate(pcmBytes, dubVolume, origVolume)
                    return@launch
                }
            }

            // 3. Fallback: Local Android Persian TTS (No Urdu/Turkish accent substitution)
            onStatus("🔊 پخش با موتور TTS فارسی گوشی")
            playLocalPersianTts(cleanText, targetLang, dubVolume)
        }
    }

    private fun generateSecMsGec(offsetSeconds: Long = 0L): String {
        val unixSeconds = (System.currentTimeMillis() / 1000L) + offsetSeconds
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

    private fun synthesizeEdgeNeuralMp3(text: String, targetLang: String, voiceStyle: String): ByteArray? {
        return try {
            val pair = getEdgeVoiceName(targetLang, voiceStyle)
            val langLocale = pair.first
            val voiceName = pair.second
            val secMsGec = generateSecMsGec(0L)
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

            val audioOut = ByteArrayOutputStream()
            val latch = CountDownLatch(1)
            val escapedText = text.take(250)
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
                    val timestamp = sdf.format(Date())
                    val crlf = "\r\n"
                    val configMsg = "X-Timestamp:" + timestamp + crlf +
                            "Content-Type:application/json; charset=utf-8" + crlf +
                            "Path:speech.config" + crlf + crlf +
                            configJson
                    webSocket.send(configMsg)

                    val reqId = UUID.randomUUID().toString().replace("-", "")
                    val ssmlBody = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='" + langLocale + "'>" +
                            "<voice name='" + voiceName + "'><prosody pitch='+0Hz' rate='+10%' volume='+0%'>" + escapedText + "</prosody></voice></speak>"
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

            latch.await(4800, TimeUnit.MILLISECONDS)
            ws.cancel()
            val result = audioOut.toByteArray()
            if (result.size > 300) result else null
        } catch (_: Exception) {
            null
        }
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

    private fun playMp3Immediate(mp3Bytes: ByteArray, dubVolume: Float, origVolume: Float) {
        val tempFile = File(context.cacheDir, "dub_" + System.currentTimeMillis() + ".mp3")
        try {
            FileOutputStream(tempFile).use { it.write(mp3Bytes) }
            if (origVolume < 0.70f) {
                requestYoutubeDucking()
            }

            mainHandler.post {
                try {
                    currentEnhancer?.release()
                    currentMediaPlayer?.release()

                    currentMediaPlayer = MediaPlayer().apply {
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

                        if (dubVolume > 0.5f) {
                            try {
                                currentEnhancer = LoudnessEnhancer(audioSessionId).apply {
                                    val boostMb = ((dubVolume - 0.5f) * 1800).toInt()
                                    setTargetGain(boostMb)
                                    enabled = true
                                }
                            } catch (_: Exception) {}
                        }

                        setOnCompletionListener { mp ->
                            mp.release()
                            tempFile.delete()
                            if (origVolume >= 0.35f) {
                                abandonYoutubeDucking()
                            }
                        }
                        start()
                    }
                } catch (_: Exception) {
                    tempFile.delete()
                }
            }
        } catch (_: Exception) {
            tempFile.delete()
        }
    }

    private suspend fun playPcm24kHzImmediate(pcmBytes: ByteArray, dubVolume: Float, origVolume: Float) {
        try {
            if (origVolume < 0.70f) {
                requestYoutubeDucking()
            }
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
            var enhancer: LoudnessEnhancer? = null
            if (dubVolume > 0.5f) {
                try {
                    enhancer = LoudnessEnhancer(track.audioSessionId).apply {
                        setTargetGain(((dubVolume - 0.5f) * 1800).toInt())
                        enabled = true
                    }
                } catch (_: Exception) {}
            }
            track.play()
            val durationMs = (pcmBytes.size / 48).toLong() + 100L
            delay(durationMs)
            enhancer?.release()
            track.stop()
            track.release()
            if (origVolume >= 0.35f) {
                abandonYoutubeDucking()
            }
        } catch (_: Exception) {}
    }

    private fun playLocalPersianTts(text: String, targetLang: String, dubVolume: Float) {
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
        localTts?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        localTts?.setLanguage(locale)
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, dubVolume.coerceIn(0.05f, 1f))
        localTts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "utt_" + System.currentTimeMillis())
    }

    fun stopAll() {
        activeSpeechJob?.cancel()
        abandonYoutubeDucking()
        mainHandler.post {
            try {
                currentEnhancer?.release()
                currentEnhancer = null
                currentMediaPlayer?.stop()
                currentMediaPlayer?.release()
                currentMediaPlayer = null
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
