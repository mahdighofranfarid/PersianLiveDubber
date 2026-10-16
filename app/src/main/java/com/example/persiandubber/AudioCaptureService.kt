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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

data class DubbingTelemetry(
    val status: String = "آماده به کار",
    val inputRms: Int = 0,
    val latestTranscript: String = "",
    val isCapturing: Boolean = false
)

class AudioCaptureService : Service() {
    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        const val EXTRA_API_KEY = "EXTRA_API_KEY"
        const val EXTRA_TARGET_LANG = "EXTRA_TARGET_LANG"
        const val EXTRA_ORIG_VOL = "EXTRA_ORIG_VOL"
        const val EXTRA_DUB_VOL = "EXTRA_DUB_VOL"

        val telemetryFlow = MutableStateFlow(DubbingTelemetry())
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var isRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundChannel()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val apiKey = intent.getStringExtra(EXTRA_API_KEY) ?: ""
                val targetLang = intent.getStringExtra(EXTRA_TARGET_LANG) ?: "فارسی"

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startSystemAudioCapture(resultCode, resultData, apiKey, targetLang)
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

    private fun startForegroundChannel() {
        val channelId = "smart_dub_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "استودیو دوبله هوشمند", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("دوبله زنده یوتیوب و نرم‌افزارها فعال است")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(102, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(102, notification)
        }
    }

    private fun startSystemAudioCapture(resultCode: Int, data: Intent, apiKey: String, targetLang: String) {
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
            .setBufferSizeInBytes(minBuf * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        try {
            audioRecord?.startRecording()
            isRunning = true
            telemetryFlow.value = DubbingTelemetry(
                status = "در حال ضبط صدای یوتیوب/برنامه‌ها (ترجمه به $targetLang)",
                isCapturing = true
            )

            scope.launch {
                val buffer = ByteArray(1280)
                while (isRunning) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read step 2) {
                            val sample = ((buffer[i+1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                            sum += sample * sample
                        }
                        val rms = kotlin.math.sqrt(sum / (read / 2)).toInt()
                        
                        // Simulate live subtitle update based on audio RMS activity
                        val sampleTranscript = if (rms > 500) "در حال ترجمه و دوبله زنده صدا..." else "منتظر صدای ویدیو..."
                        telemetryFlow.value = telemetryFlow.value.copy(
                            inputRms = rms,
                            latestTranscript = sampleTranscript
                        )
                    }
                }
            }
        } catch (e: Exception) {
            telemetryFlow.value = DubbingTelemetry(status = "خطا در شروع کپچر: ${e.message}", isCapturing = false)
        }
    }

    private fun stopCapture() {
        isRunning = false
        scope.cancel()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        mediaProjection?.stop()
        telemetryFlow.value = DubbingTelemetry(status = "متوقف شد", isCapturing = false)
    }
}
