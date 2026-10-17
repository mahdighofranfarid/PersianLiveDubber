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
import android.view.ScaleGestureDetector
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
    val requestCount: Int = 0,
    val subtitleScale: Float = 1.0f
)

class AudioCaptureService : Service() {
    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_TEST_VOICE = "ACTION_TEST_VOICE"
        const val ACTION_SHOW_SUBS_AGAIN = "ACTION_SHOW_SUBS_AGAIN"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        const val EXTRA_API_KEY = "EXTRA_API_KEY"
        const val EXTRA_MODEL_NAME = "EXTRA_MODEL_NAME"
        const val EXTRA_TARGET_LANG = "EXTRA_TARGET_LANG"
        const val EXTRA_VOICE_STYLE = "EXTRA_VOICE_STYLE"
        const val EXTRA_ORIG_VOL = "EXTRA_ORIG_VOL"
        const val EXTRA_DUB_VOL = "EXTRA_DUB_VOL"
        const val EXTRA_CHUNK_SEC = "EXTRA_CHUNK_SEC"
        const val EXTRA_SHOW_SUBS = "EXTRA_SHOW_SUBS"
        const val EXTRA_ENABLE_TTS = "EXTRA_ENABLE_TTS"

        val telemetryFlow = MutableStateFlow(DubbingTelemetry())

        @Volatile var currentApiKey: String = ""
        @Volatile var currentModelName: String = "gemini-2.5-flash"
        @Volatile var currentTargetLang: String = "فارسی"
        @Volatile var currentVoiceStyle: String = "زن طبیعی WaveNet (لهجه تهرانی - سریع و ضد فیلتر)"
        @Volatile var currentOrigVol: Float = 0.25f
        @Volatile var currentDubVol: Float = 1.0f
        @Volatile var currentChunkSec: Int = 3
        @Volatile var currentShowSubs: Boolean = true
        @Volatile var currentEnableTts: Boolean = true
        @Volatile var currentScaleFactor: Float = 1.0f

        private var activeServiceInstance: AudioCaptureService? = null

        fun updateLiveConfig(
            apiKey: String,
            modelName: String,
            targetLang: String,
            voiceStyle: String,
            origVol: Float,
            dubVol: Float,
            chunkSec: Int,
            showSubs: Boolean,
            enableTts: Boolean
        ) {
            currentApiKey = apiKey
            currentModelName = modelName
            currentTargetLang = targetLang
            currentVoiceStyle = voiceStyle
            currentOrigVol = origVol
            currentDubVol = dubVol
            currentChunkSec = chunkSec.coerceIn(2, 8)
            currentShowSubs = showSubs
            currentEnableTts = enableTts

            activeServiceInstance?.applySmartVolumePolicy()
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
    @Volatile private var previousSentenceContext: String = ""

    private var windowManager: WindowManager? = null
    private var overlayContainer: LinearLayout? = null
    private var overlaySubtitleText: TextView? = null
    private var overlayCloseBtn: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        activeServiceInstance = this
        voiceSynthesizer = VoiceSynthesizer(this)
        val prefs = getSharedPreferences("dubber_prefs", Context.MODE_PRIVATE)
        currentScaleFactor = prefs.getFloat("sub_scale", 1.0f).coerceIn(0.6f, 2.4f)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_SUBS_AGAIN -> {
                currentShowSubs = true
                getSharedPreferences("dubber_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("show_subs", true).apply()
                showFloatingSubtitleOverlay()
                updateOverlayVisibility()
            }
            ACTION_TEST_VOICE -> {
                val targetLang = intent.getStringExtra(EXTRA_TARGET_LANG) ?: currentTargetLang
                val voiceStyle = intent.getStringExtra(EXTRA_VOICE_STYLE) ?: currentVoiceStyle
                val dubVol = intent.getFloatExtra(EXTRA_DUB_VOL, currentDubVol)
                val testPhrase = when (targetLang) {
                    "فارسی" -> "سلام! من گوینده هوشمند شما هستم و صدای دوبله اکنون با کیفیت عالی و بدون تأخیر پخش می‌شود."
                    "انگلیسی" -> "Hello! I am your live dubbing voice and the audio is playing clearly."
                    "عربی" -> "مرحباً! أنا صوت الدبلجة المباشرة والصوت يعمل بوضوح."
                    else -> "سلام! صدای گوینده دوبله فعال است."
                }
                val testPhonetic = if (targetLang == "فارسی") {
                    "Selam! Men guyendeye huşmende şoma hestem ve sedaye duble eknun ba keyfiyete ali ve bedune tehir pehş mişeved."
                } else {
                    ""
                }
                voiceSynthesizer?.speak(
                    text = testPhrase,
                    phoneticHint = testPhonetic,
                    targetLang = targetLang,
                    voiceStyle = voiceStyle,
                    dubVolume = dubVol,
                    origVolume = 0.5f,
                    onStatus = { msg ->
                        telemetryFlow.value = telemetryFlow.value.copy(status = msg)
                    }
                )
            }
            ACTION_START -> {
                startForegroundChannel("دوبله زنده و زیرنویس شناور فعال است")
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
                currentVoiceStyle = intent.getStringExtra(EXTRA_VOICE_STYLE) ?: "زن طبیعی WaveNet (لهجه تهرانی - سریع و ضد فیلتر)"
                currentOrigVol = intent.getFloatExtra(EXTRA_ORIG_VOL, 0.25f)
                currentDubVol = intent.getFloatExtra(EXTRA_DUB_VOL, 1.0f)
                currentChunkSec = intent.getIntExtra(EXTRA_CHUNK_SEC, 3).coerceIn(2, 8)
                currentShowSubs = intent.getBooleanExtra(EXTRA_SHOW_SUBS, true)
                currentEnableTts = intent.getBooleanExtra(EXTRA_ENABLE_TTS, true)

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    isRunning = true
                    previousSentenceContext = ""
                    voiceSynthesizer?.saveInitialVolumes()
                    applySmartVolumePolicy()
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

    fun applySmartVolumePolicy() {
        voiceSynthesizer?.applyOriginalVideoVolumePolicy(currentOrigVol, currentDubVol)
    }

    private fun applySubtitleScale(scale: Float) {
        val clamped = scale.coerceIn(0.6f, 2.4f)
        currentScaleFactor = clamped
        telemetryFlow.value = telemetryFlow.value.copy(subtitleScale = clamped)
        getSharedPreferences("dubber_prefs", Context.MODE_PRIVATE)
            .edit().putFloat("sub_scale", clamped).apply()

        mainHandler.post {
            overlaySubtitleText?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * clamped)
            overlayCloseBtn?.setTextSize(TypedValue.COMPLEX_UNIT_SP, (13f * clamped).coerceIn(11f, 22f))
            val padH = (28 * clamped).toInt().coerceIn(16, 64)
            val padV = (16 * clamped).toInt().coerceIn(10, 44)
            overlayContainer?.setPadding(padH, padV, padH, padV)

            val p = overlayParams
            val container = overlayContainer
            if (p != null && container != null) {
                val screenW = resources.displayMetrics.widthPixels
                val widthRatio = (0.86f * clamped).coerceIn(0.50f, 0.98f)
                p.width = (screenW * widthRatio).toInt()
                try {
                    windowManager?.updateViewLayout(container, p)
                } catch (_: Exception) {}
            }
        }
    }

    private fun showFloatingSubtitleOverlay() {
        mainHandler.post {
            if (!Settings.canDrawOverlays(this)) return@post
            if (overlayContainer != null) {
                updateOverlayVisibility()
                applySubtitleScale(currentScaleFactor)
                return@post
            }

            try {
                windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

                val rootBox = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(28, 16, 28, 16)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#E0000000"))
                        cornerRadius = 30f
                        setStroke(2, Color.parseColor("#66FFD600"))
                    }
                }

                val closeBtn = TextView(this).apply {
                    text = "✕"
                    setTextColor(Color.parseColor("#FF8A80"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(18, 8, 18, 8)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#33FFFFFF"))
                        cornerRadius = 40f
                    }
                    setOnClickListener {
                        currentShowSubs = false
                        getSharedPreferences("dubber_prefs", Context.MODE_PRIVATE)
                            .edit().putBoolean("show_subs", false).apply()
                        updateOverlayVisibility()
                    }
                }

                val tv = TextView(this).apply {
                    text = "🎬 زیرنویس آماده (۱ انگشت حرکت، ۲ انگشت زوم، ✕ بستن)"
                    setTextColor(Color.parseColor("#FFD600"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * currentScaleFactor)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setShadowLayer(4f, 0f, 2f, Color.BLACK)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = 12
                    }
                }

                rootBox.addView(closeBtn)
                rootBox.addView(tv)

                val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val screenW = resources.displayMetrics.widthPixels
                val widthRatio = (0.86f * currentScaleFactor).coerceIn(0.50f, 0.98f)

                val params = WindowManager.LayoutParams(
                    (screenW * widthRatio).toInt(),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutFlag,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    x = 0
                    y = (resources.displayMetrics.heightPixels * 0.10).toInt()
                }

                val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        val newScale = (currentScaleFactor * detector.scaleFactor).coerceIn(0.6f, 2.4f)
                        applySubtitleScale(newScale)
                        return true
                    }
                })

                rootBox.setOnTouchListener(object : View.OnTouchListener {
                    private var initialX = 0
                    private var initialY = 0
                    private var initialTouchX = 0f
                    private var initialTouchY = 0f
                    private var isMultiTouch = false

                    override fun onTouch(v: View, event: MotionEvent): Boolean {
                        scaleDetector.onTouchEvent(event)

                        if (event.pointerCount > 1) {
                            isMultiTouch = true
                            return true
                        }

                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                isMultiTouch = false
                                initialX = params.x
                                initialY = params.y
                                initialTouchX = event.rawX
                                initialTouchY = event.rawY
                                return true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (!isMultiTouch && !scaleDetector.isInProgress) {
                                    params.x = (initialX + (event.rawX - initialTouchX)).toInt()
                                    params.y = (initialY + (event.rawY - initialTouchY)).toInt()
                                        .coerceIn(40, resources.displayMetrics.heightPixels - 140)
                                    try {
                                        windowManager?.updateViewLayout(rootBox, params)
                                    } catch (_: Exception) {}
                                }
                                return true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                isMultiTouch = false
                                return true
                            }
                        }
                        return false
                    }
                })

                windowManager?.addView(rootBox, params)
                overlayContainer = rootBox
                overlaySubtitleText = tv
                overlayCloseBtn = closeBtn
                overlayParams = params
                applySubtitleScale(currentScaleFactor)
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
                    overlayCloseBtn = null
                    overlayParams = null
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
        val showSubsIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = ACTION_SHOW_SUBS_AGAIN
        }
        val pendingShowSubs = PendingIntent.getService(
            this, 201, showSubsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("دوبله زنده فعال است (" + currentModelName + ")")
            .setContentText(subtitleText)
            .setStyle(Notification.BigTextStyle().bigText(subtitleText))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(android.R.drawable.ic_menu_view, "نمایش مجدد زیرنویس شناور", pendingShowSubs)
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
                requestCount = 0,
                subtitleScale = currentScaleFactor
            )

            scope.launch {
                val buffer = ByteArray(3200)
                val pcmAccumulator = ByteArrayOutputStream()
                var overlapTail = ByteArray(0)
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

                        if (rms > 140) {
                            activeSpeechChunks++
                            silentTrailingChunks = 0
                        } else {
                            silentTrailingChunks++
                        }

                        pcmAccumulator.write(buffer, 0, read)

                        val accumulatedSeconds = pcmAccumulator.size() / 32000.0
                        val minPauseSec = (currentChunkSec * 0.65).coerceAtLeast(1.9)
                        val naturalClauseEnd = (accumulatedSeconds >= minPauseSec && activeSpeechChunks >= 5 && silentTrailingChunks >= 3)
                        val maxWindowReached = (accumulatedSeconds >= currentChunkSec)

                        if (naturalClauseEnd || maxWindowReached) {
                            val rawChunk = pcmAccumulator.toByteArray()
                            pcmAccumulator.reset()

                            val combinedOut = ByteArrayOutputStream(overlapTail.size + rawChunk.size)
                            if (overlapTail.isNotEmpty()) {
                                combinedOut.write(overlapTail)
                            }
                            combinedOut.write(rawChunk)
                            val fullAudio = combinedOut.toByteArray()

                            val tailLen = minOf(8000, rawChunk.size)
                            overlapTail = rawChunk.copyOfRange(rawChunk.size - tailLen, rawChunk.size)

                            val hadSpeech = activeSpeechChunks >= 4
                            activeSpeechChunks = 0
                            silentTrailingChunks = 0

                            if (currentApiKey.isBlank()) {
                                val msg = "لطفاً کلید API شخصی جمنای را در برنامه وارد کنید!"
                                telemetryFlow.value = telemetryFlow.value.copy(inputRms = rms, latestTranscript = msg)
                                updateFloatingSubtitleText(msg)
                            } else if (hadSpeech && !isRequestInFlight.get()) {
                                sendAudioToGemini(fullAudio, currentApiKey, currentModelName, currentTargetLang, 0)
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

            val contextHint = if (previousSentenceContext.isNotBlank()) {
                " Previous line: (" + previousSentenceContext + "). Continue naturally without repeating."
            } else {
                ""
            }

            val prompt = if (targetLang == "فارسی") {
                "You are a live movie dubber. Translate the speech in this audio into a smooth, natural conversational Persian (فارسی) sentence." +
                        contextHint +
                        " Output in this exact format separated by ||| : " +
                        "Persian Subtitle ||| Tehrani pronunciation written in Turkish alphabet (using e, a, i, o, u, ş for ش, ç for چ, c for ج, g for گ, h for خ/ه). " +
                        "Example: من همیشه به تو فکر می‌کنم ||| Men hemişe be to fekr mikonem " +
                        "If there is no speech, output nothing."
            } else {
                "You are a live movie dubber. Translate the speech in this audio into a smooth, natural conversational sentence in " + targetLang + "." +
                        contextHint +
                        " Output ONLY the translated " + targetLang + " sentence. If there is no speech, output nothing."
            }

            val cleanModel = modelName.removePrefix("models/").trim()

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
                if (cleanModel.contains("2.5-flash")) {
                    put("generationConfig", JSONObject().apply {
                        put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                    })
                }
            }

            val body = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
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
                            if ((resp.code == 429 || resp.code == 404 || resp.code == 503 || resp.code == 400) && retryDepth < fallbackModels.size) {
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
                                    val rawOutput = parts.getJSONObject(0).optString("text", "").trim()
                                    val splitParts = rawOutput.split("|||")
                                    val subtitleText = splitParts[0].trim()
                                    val phoneticHint = if (splitParts.size > 1) splitParts[1].trim() else ""

                                    if (subtitleText.length >= 2 && subtitleText != previousSentenceContext) {
                                        previousSentenceContext = subtitleText
                                        telemetryFlow.value = telemetryFlow.value.copy(
                                            latestTranscript = subtitleText,
                                            requestCount = totalRequestsSent,
                                            status = "ترجمه شد (" + cleanModel + ")"
                                        )
                                        updateFloatingSubtitleText(subtitleText)
                                        if (currentEnableTts) {
                                            voiceSynthesizer?.speak(
                                                text = subtitleText,
                                                phoneticHint = phoneticHint,
                                                targetLang = currentTargetLang,
                                                voiceStyle = currentVoiceStyle,
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
        telemetryFlow.value = DubbingTelemetry(status = "متوقف شد (صدای گوشی به حالت عادی برگشت)", isCapturing = false)
    }

    override fun onDestroy() {
        activeServiceInstance = null
        removeFloatingSubtitleOverlay()
        voiceSynthesizer?.shutdown()
        super.onDestroy()
    }
}
