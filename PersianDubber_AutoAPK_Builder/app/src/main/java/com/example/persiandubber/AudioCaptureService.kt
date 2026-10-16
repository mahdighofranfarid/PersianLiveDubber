package com.example.persiandubber

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

data class DubbingTelemetry(
    val status: String = "آماده",
    val inputRms: Int = 0,
    val e2eLatencyMs: Long = 0L,
    val isCapturing: Boolean = false
)

class AudioCaptureService : Service() {
    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        const val EXTRA_CAPTURE_ONLY_TEST = "EXTRA_CAPTURE_ONLY_TEST"
        const val EXTRA_PROXY_URL = "EXTRA_PROXY_URL"

        val telemetryFlow = MutableStateFlow(DubbingTelemetry())
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var player: AudioTrackPlayer? = null
    private var aecManager: AcousticEchoCancelerManager? = null
    private var wsClient: GeminiLiveWebSocketClient? = null
    @Volatile private var isRunning = false
    @Volatile private var lastSpeechTimestamp = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundWithProjectionType()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val captureOnlyTest = intent.getBooleanExtra(EXTRA_CAPTURE_ONLY_TEST, true)
                val proxyUrl = intent.getStringExtra(EXTRA_PROXY_URL) ?: "ws://10.0.2.2:8080/ws/live-dub"

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startCapture(resultCode, resultData, captureOnlyTest, proxyUrl)
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

    private fun startForegroundWithProjectionType() {
        val channelId = "persian_dub_channel"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Persian Live Dubber", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("دوبله زنده فارسی فعال است")
            .setContentText("در حال دریافت صدای ویدیو...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(101, notification)
        }
    }

    private fun startCapture(resultCode: Int, data: Intent, captureOnlyTest: Boolean, proxyUrl: String) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            // CRITICAL: Prevent digital loopback of our own Persian dubbing AudioTrack!
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
            .setBufferSizeInBytes(minBuf * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        aecManager = AcousticEchoCancelerManager().also {
            it.attachToSession(audioRecord!!.audioSessionId)
        }

        player = AudioTrackPlayer(24000) { playing ->
            aecManager?.isDubbingPlaying = playing
        }.also { it.start() }

        if (!captureOnlyTest) {
            wsClient = GeminiLiveWebSocketClient(
                proxyUrl = proxyUrl,
                onStateChange = { st ->
                    telemetryFlow.value = telemetryFlow.value.copy(status = st)
                },
                onDubbedAudioReceived = { pcm24k, clientTs ->
                    val now = System.currentTimeMillis()
                    val refTs = if (clientTs > 0) clientTs else lastSpeechTimestamp
                    val measuredLatency = if (refTs > 0) (now - refTs) + 35L else 0L
                    Log.i("PersianDub", "Real E2E Latency: ${measuredLatency}ms")
                    telemetryFlow.value = telemetryFlow.value.copy(e2eLatencyMs = measuredLatency)
                    player?.writePcm(pcm24k)
                }
            ).also { it.connect() }
        } else {
            telemetryFlow.value = telemetryFlow.value.copy(status = "حالت تست مرحله ۱: فقط کپچر صدای ویدیو")
        }

        audioRecord?.startRecording()
        isRunning = true
        telemetryFlow.value = telemetryFlow.value.copy(isCapturing = true)

        scope.launch {
            val buffer = ByteArray(1280) // 40ms frame at 16kHz 16-bit mono
            while (isRunning) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    val rms = aecManager?.processAndMeasureRms(buffer, read, applyDuckingGate = false) ?: 0.0
                    telemetryFlow.value = telemetryFlow.value.copy(inputRms = rms.toInt())
                    if (rms > 300) {
                        lastSpeechTimestamp = System.currentTimeMillis()
                    }
                    if (!captureOnlyTest) {
                        wsClient?.sendPcmFrame(buffer.copyOf(read), lastSpeechTimestamp)
                    } else {
                        Log.d("AudioCaptureService", "Captured $read bytes | RMS=${rms.toInt()}")
                    }
                }
            }
        }
    }

    private fun stopCapture() {
        isRunning = false
        scope.cancel()
        wsClient?.disconnect()
        audioRecord?.stop()
        audioRecord?.release()
        player?.stop()
        aecManager?.release()
        mediaProjection?.stop()
        telemetryFlow.value = DubbingTelemetry(status = "متوقف شد", isCapturing = false)
    }
}
