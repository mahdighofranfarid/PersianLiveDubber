package com.example.persiandubber

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class DubbingTelemetry(
    val status: String = "آماده به کار",
    val inputRms: Int = 0,
    val latestTranscript: String = "",
    val isCapturing: Boolean = false,
    val requestCount: Int = 0
)

class AudioCaptureService : Service() {
    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_TEST_VOICE = "ACTION_TEST_VOICE"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        const val EXTRA_API_KEY = "EXTRA_API_KEY"
        const val EXTRA_MODEL_NAME = "EXTRA_MODEL_NAME"
        const val EXTRA_TARGET_LANG = "EXTRA_TARGET_LANG"
        const val EXTRA_ORIG_VOL = "EXTRA_ORIG_VOL"
        const val EXTRA_DUB_VOL = "EXTRA_DUB_VOL"
        const val EXTRA_CHUNK_SEC = "EXTRA_CHUNK_SEC"
        const val EXTRA_SHOW_SUBS = "EXTRA_SHOW_SUBS"
        const val EXTRA_ENABLE_TTS = "EXTRA_ENABLE_TTS"

        val telemetryFlow = MutableStateFlow(DubbingTelemetry())

        @Volatile var currentApiKey: String = ""
        @Volatile var currentModelName: String = "gemini-2.5-flash"
        @Volatile var currentTargetLang: String = "فارسی"
        @Volatile var currentOrigVol: Float = 0.25f
        @Volatile var currentDubVol: Float = 1.0f
        @Volatile var currentChunkSec: Int = 3
        @Volatile var currentShowSubs: Boolean = true
        @Volatile var currentEnableTts: Boolean = true

        private var activeServiceInstance: AudioCaptureService? = null

        fun updateLiveConfig(
            apiKey: String,
            modelName: String,
            targetLang: String,
            origVol: Float,
            dubVol: Float,
            chunkSec: Int,
            showSubs: Boolean,
            enableTts: Boolean
        ) {
            currentApiKey = apiKey
            currentModelName = modelName
            currentTargetLang = targetLang
            currentOrigVol = origVol
            currentDubVol = dubVol
            currentChunkSec = chunkSec.coerceIn(2, 10)
            currentShowSubs = showSubs
            currentEnableTts = enableTts

            activeServiceInstance?.applySmartDuckingPolicy()
            activeServiceInstance?.updateOverlayVisibility()
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var voiceSynthesizer: VoiceSynthesizer? = null
    private val isRequestInFlight = AtomicBoolean(false)
    private var totalRequestsSent = 0

    private var windowManager: WindowManager? = null
    private var overlayContainer: LinearLayout? = null
    private var overlaySubtitleText: TextView? = null

    private val fallbackModels = listOf(
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite",
        "gemini-3.5-flash-lite",
        "gemini-2.0-flash-lite",
        "gemini-1.5-flash",
        "gemini-3.8-flash"
    )
    private var fallbackIndex = 0

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        activeServiceInstance = this
        voiceSynthesizer = VoiceSynthesizer(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TEST_VOICE -> {
                val apiKey = intent.getStringExtra(EXTRA_API_KEY) ?: currentApiKey
                val targetLang = intent.getStringExtra(EXTRA_TARGET_LANG) ?: currentTargetLang
                val dubVol = intent.getFloatExtra(EXTRA_DUB_VOL, currentDubVol)
                val testPhrase = when (targetLang) {
                    "فارسی" -> "سلام! صدای گوینده دوبله فارسی با موفقیت فعال شد و در هدفون و اسپیکر پخش می‌شود."
                    "انگلیسی" -> "Hello! The live dubbing voice is working properly."
                    "عربی" -> "مرحباً! صوت الدبلجة المباشرة يعمل بنجاح."
                    else -> "سلام! صدای گوینده دوبله فعال است."
                }
                voiceSynthesizer?.speak(
                    text = testPhrase,
                    targetLang = targetLang,
                    apiKey = apiKey,
                    dubVolume = dubVol,
                    origVolume = 1.0f,
                    onStatus = { msg ->
                        telemetryFlow.value = telemetryFlow.value.copy(status = msg)
                    }
                )
            }
            ACTION_START -> {
                startForegroundChannel("دوبله و زیرنویس شناور روی یوتیوب فعال است")
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                currentApiKey = intent.getStringExtra(EXTRA_API_KEY) ?: ""
                currentModelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: "gemini-2.5-flash"
                currentTargetLang = intent.getStringExtra(EXTRA_TARGET_LANG) ?: "فارسی"
                currentOrigVol = intent.getFloatExtra(EXTRA_ORIG_VOL, 0.25f)
                currentDubVol = intent.getFloatExtra(EXTRA_DUB_VOL, 1.0f)
                currentChunkSec = intent.getIntExtra(EXTRA_CHUNK_SEC, 3).coerceIn(2, 10)
                currentShowSubs = intent.getBooleanExtra(EXTRA_SHOW_SUBS, true)
                currentEnableTts = intent.getBooleanExtra(EXTRA_ENABLE_TTS, true)

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    applySmartDuckingPolicy()
                    showFloatingSubtitleOverlay()
                    startSystemAudioCapture(resultCode, resultData)
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopCapture()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    fun applySmartDuckingPolicy() {
        if (!isRunning) return
        if (currentOrigVol < 0.35f) {
            voiceSynthesizer?.requestYoutubeDucking(currentOrigVol)
        } else if (currentOrigVol >= 0.85f) {
            voiceSynthesizer?.abandonYoutubeDucking()
        }
    }

    private fun showFloatingSubtitleOverlay() {
        mainHandler.post {
            if (!Settings.canDrawOverlays(this)) return@post
            if (overlayContainer != null) {
                updateOverlayVisibility()
                return@post
            }

            try {
                windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

                val container = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(32, 20, 32, 20)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#D9000000"))
                        cornerRadius = 28f
                        setStroke(2, Color.parseColor("#55FFD600"))
                    }
                }

                val tv = TextView(this).apply {
                    text = "🎬 زیرنویس و دوبله صوتی آماده است"
                    setTextColor(Color.parseColor("#FFD600"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setShadowLayer(4f, 0f, 2f, Color.BLACK)
                }

                container.addView(tv)

                val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    (resources.displayMetrics.widthPixels * 0.92).toInt(),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutFlag,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = (resources.displayMetrics.heightPixels * 0.12).toInt()
                }

                container.setOnTouchListener(object : View.OnTouchListener {
                    private var initialY = 0
                    private var initialTouchY = 0f

                    override fun onTouch(v: View, event: MotionEvent): Boolean {
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                initialY = params.y
                                initialTouchY = event.rawY
                                return true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                params.y = (initialY + (event.rawY - initialTouchY)).toInt()
                                    .coerceIn(50, resources.displayMetrics.heightPixels - 180)
                                windowManager?.updateViewLayout(container, params)
                                return true
                            }
                        }
                        return false
                    }
                })

                windowManager?.addView(container, params)
                overlayContainer = container
                overlaySubtitleText = tv
                updateOverlayVisibility()
            } catch (_: Exception) {}
        }
    }

    fun updateOverlayVisibility() {
        mainHandler.post {
            overlayContainer?.visibility = if (currentShowSubs && isRunning) View.VISIBLE else View.GONE
        }
    }

    private fun updateFloatingSubtitleText(text: String) {
        mainHandler.post {
            overlaySubtitleText?.text = text
            if (currentShowSubs && overlayContainer?.visibility != View.VISIBLE) {
                overlayContainer?.visibility = View.VISIBLE
            }
        }
    }

    private fun removeFloatingSubtitleOverlay() {
        mainHandler.post {
            try {
                if (overlayContainer != null) {
                    windowManager?.removeView(overlayContainer)
                    overlayContainer = null
                    overlaySubtitleText = null
                }
            } catch (_: Exception) {}
        }
    }

    private fun startForegroundChannel(subtitleText: String) {
        val channelId = "smart_dub_channel"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "استودیو دوبله هوشمند", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("دوبله زنده (" + currentModelName + ")")
            .setContentText(subtitleText)
            .setStyle(Notification.BigTextStyle().bigText(subtitleText))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(102, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(102, notification)
        }
    }

    private fun startSystemAudioCapture(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .excludeUid(Process.myUid())
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(16000)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuf * 4)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        try {
            audioRecord?.startRecording()
            isRunning = true
            totalRequestsSent = 0
            telemetryFlow.value = DubbingTelemetry(
                status = "فعال روی یوتیوب (" + currentModelName + ")",
                isCapturing = true,
                requestCount = 0
            )

            scope.launch {
                val buffer = ByteArray(3200)
                val pcmAccumulator = ByteArrayOutputStream()
                var activeSpeechChunks = 0
                var silentTrailingChunks = 0

                while (isRunning) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read step 2) {
                            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                            sum += sample * sample
                        }
                        val rms = kotlin.math.sqrt(sum / (read / 2)).toInt()

                        if (rms > 160) {
                            activeSpeechChunks++
                            silentTrailingChunks = 0
                        } else {
                            silentTrailingChunks++
                        }

                        pcmAccumulator.write(buffer, 0, read)

                        val accumulatedSeconds = pcmAccumulator.size() / 32000.0
                        val naturalPauseReached = (accumulatedSeconds >= 2.2 && activeSpeechChunks >= 5 && silentTrailingChunks >= 3)
                        val maxWindowReached = (accumulatedSeconds >= currentChunkSec)

                        if (naturalPauseReached || maxWindowReached) {
                            val audioBytes = pcmAccumulator.toByteArray()
                            pcmAccumulator.reset()
                            val hadSpeech = activeSpeechChunks >= 3
                            activeSpeechChunks = 0
                            silentTrailingChunks = 0

                            if (currentApiKey.isBlank()) {
                                val msg = "لطفاً کلید API شخصی جمنای را در برنامه وارد کنید!"
                                telemetryFlow.value = telemetryFlow.value.copy(inputRms = rms, latestTranscript = msg)
                                updateFloatingSubtitleText(msg)
                            } else if (hadSpeech && !isRequestInFlight.get()) {
                                sendAudioToGemini(audioBytes, currentApiKey, currentModelName, currentTargetLang, 0)
                            } else {
                                telemetryFlow.value = telemetryFlow.value.copy(inputRms = rms)
                            }
                        } else {
                            telemetryFlow.value = telemetryFlow.value.copy(inputRms = rms)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            telemetryFlow.value = DubbingTelemetry(status = "خطا در کپچر: " + e.message, isCapturing = false)
        }
    }

    private fun pcmToWav(pcmData: ByteArray, sampleRate: Int = 16000, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val totalDataLen = pcmData.size + 36
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign)
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmData.size)

        val out = ByteArrayOutputStream(44 + pcmData.size)
        out.write(header.array())
        out.write(pcmData)
        return out.toByteArray()
    }

    private fun sendAudioToGemini(pcmData: ByteArray, apiKey: String, modelName: String, targetLang: String, retryDepth: Int) {
        if (retryDepth == 0 && !isRequestInFlight.compareAndSet(false, true)) return

        try {
            val wavBytes = pcmToWav(pcmData)
            val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

            val prompt = "Translate the spoken words or lyrics in this audio clip into concise, natural " + targetLang + " for live dubbing. Output ONLY the translated " + targetLang + " sentence without any quotes, markdown, or extra notes. If there is no speech, output nothing."

            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray()
                        .put(JSONObject().put("text", prompt))
                        .put(JSONObject().put("inline_data", JSONObject()
                            .put("mime_type", "audio/wav")
                            .put("data", base64Audio)
                        ))
                    )
                ))
            }

            val body = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val cleanModel = modelName.removePrefix("models/").trim()
            val url = "https://generativelanguage.googleapis.com/v1beta/models/" + cleanModel + ":generateContent?key=" + apiKey

            val request = Request.Builder().url(url).post(body).build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    isRequestInFlight.set(false)
                    val msg = "خطای اینترنت: " + e.localizedMessage
                    telemetryFlow.value = telemetryFlow.value.copy(latestTranscript = msg)
                    updateFloatingSubtitleText(msg)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val respStr = resp.body?.string() ?: ""
                        if (!resp.isSuccessful) {
                            if ((resp.code == 429 || resp.code == 404 || resp.code == 503) && retryDepth < fallbackModels.size) {
                                val nextModel = fallbackModels[fallbackIndex % fallbackModels.size]
                                fallbackIndex++
                                currentModelName = nextModel
                                telemetryFlow.value = telemetryFlow.value.copy(
                                    status = "سوئیچ خودکار به " + nextModel
                                )
                                sendAudioToGemini(pcmData, apiKey, nextModel, targetLang, retryDepth + 1)
                                return
                            }

                            isRequestInFlight.set(false)
                            var errorMsg = "خطای API (" + resp.code + ")"
                            try {
                                val errObj = JSONObject(respStr)
                                if (errObj.has("error")) {
                                    errorMsg = errObj.getJSONObject("error").getString("message")
                                }
                            } catch (_: Exception) {}
                            telemetryFlow.value = telemetryFlow.value.copy(latestTranscript = "خطا: " + errorMsg)
                            updateFloatingSubtitleText("⚠️ " + errorMsg)
                            return
                        }

                        isRequestInFlight.set(false)
                        totalRequestsSent++
                        try {
                            val root = JSONObject(respStr)
                            val candidates = root.optJSONArray("candidates")
                            if (candidates != null && candidates.length() > 0) {
                                val content = candidates.getJSONObject(0).optJSONObject("content")
                                val parts = content?.optJSONArray("parts")
                                if (parts != null && parts.length() > 0) {
                                    val text = parts.getJSONObject(0).optString("text", "").trim()
                                    if (text.isNotEmpty()) {
                                        telemetryFlow.value = telemetryFlow.value.copy(
                                            latestTranscript = text,
                                            requestCount = totalRequestsSent,
                                            status = "در حال دوبله زنده (" + cleanModel + ")"
                                        )
                                        updateFloatingSubtitleText(text)
                                        if (currentEnableTts) {
                                            voiceSynthesizer?.speak(
                                                text = text,
                                                targetLang = currentTargetLang,
                                                apiKey = currentApiKey,
                                                dubVolume = currentDubVol,
                                                origVolume = currentOrigVol,
                                                onStatus = { voiceStatus ->
                                                    telemetryFlow.value = telemetryFlow.value.copy(status = voiceStatus)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            telemetryFlow.value = telemetryFlow.value.copy(latestTranscript = "خطای پردازش: " + e.message)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            isRequestInFlight.set(false)
            telemetryFlow.value = telemetryFlow.value.copy(latestTranscript = "خطا: " + e.message)
        }
    }

    private fun stopCapture() {
        isRunning = false
        scope.cancel()
        removeFloatingSubtitleOverlay()
        voiceSynthesizer?.stopAll()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        mediaProjection?.stop()
        telemetryFlow.value = DubbingTelemetry(status = "متوقف شد", isCapturing = false)
    }

    override fun onDestroy() {
        activeServiceInstance = null
        removeFloatingSubtitleOverlay()
        voiceSynthesizer?.shutdown()
        super.onDestroy()
    }
}
