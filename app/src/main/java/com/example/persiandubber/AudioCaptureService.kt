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
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

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
        const val EXTRA_MODEL_NAME = "EXTRA_MODEL_NAME"
        const val EXTRA_TARGET_LANG = "EXTRA_TARGET_LANG"
        const val EXTRA_ORIG_VOL = "EXTRA_ORIG_VOL"
        const val EXTRA_DUB_VOL = "EXTRA_DUB_VOL"

        val telemetryFlow = MutableStateFlow(DubbingTelemetry())
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private val client = OkHttpClient()
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
                val modelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: "gemini-3.8-flash"
                val targetLang = intent.getStringExtra(EXTRA_TARGET_LANG) ?: "فارسی"

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startSystemAudioCapture(resultCode, resultData, apiKey, modelName, targetLang)
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
                NotificationChannel(channelId, "استودیو دوبله هوشمند چندمدلی", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("دوبله زنده یوتیوب فعال است")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(102, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(102, notification)
        }
    }

    private fun startSystemAudioCapture(resultCode: Int, data: Intent, apiKey: String, modelName: String, targetLang: String) {
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
                status = "متصل به مدل $modelName (ترجمه به $targetLang)",
                isCapturing = true
            )

            scope.launch {
                val buffer = ByteArray(4096)
                val pcmAccumulator = java.io.ByteArrayOutputStream()

                while (isRunning) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read step 2) {
                            val sample = ((buffer[i+1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                            sum += sample * sample
                        }
                        val rms = kotlin.math.sqrt(sum / (read / 2)).toInt()
                        
                        pcmAccumulator.write(buffer, 0, read)

                        if (pcmAccumulator.size() > 32000) {
                            val audioBytes = pcmAccumulator.toByteArray()
                            pcmAccumulator.reset()

                            if (apiKey.isNotBlank()) {
                                sendAudioToGemini(audioBytes, apiKey, modelName, targetLang)
                            } else {
                                telemetryFlow.value = telemetryFlow.value.copy(
                                    inputRms = rms,
                                    latestTranscript = "لطفاً کلید API شخصی جمنای را در تنظیمات وارد کنید!"
                                )
                            }
                        } else {
                            telemetryFlow.value = telemetryFlow.value.copy(inputRms = rms)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            telemetryFlow.value = DubbingTelemetry(status = "خطا در کپچر: ${e.message}", isCapturing = false)
        }
    }

    private fun sendAudioToGemini(pcmData: ByteArray, apiKey: String, modelName: String, targetLang: String) {
        try {
            val base64Audio = Base64.encodeToString(pcmData, Base64.NO_WRAP)
            
            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray()
                        .put(JSONObject().put("text", "Translate this audio stream in real-time to $targetLang and provide short live dubbing translation subtitles."))
                        .put(JSONObject().put("inline_data", JSONObject()
                            .put("mime_type", "audio/pcm")
                            .put("data", base64Audio)
                        ))
                    )
                ))
            }

            val body = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"

            val request = Request.Builder().url(url).post(body).build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    telemetryFlow.value = telemetryFlow.value.copy(latestTranscript = "خطای شبکه: ${e.localizedMessage}")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val respStr = resp.body?.string() ?: ""
                        if (!resp.isSuccessful) {
                            var errorMsg = "خطای API (${resp.code})"
                            try {
                                val errObj = JSONObject(respStr)
                                if (errObj.has("error")) {
                                    errorMsg = errObj.getJSONObject("error").getString("message")
                                }
                            } catch (_: Exception) {}
                            telemetryFlow.value = telemetryFlow.value.copy(latestTranscript = "خطا: $errorMsg")
                            return
                        }
                        try {
                            val root = JSONObject(respStr)
                            val candidates = root.getJSONArray("candidates")
                            if (candidates.length() > 0) {
                                val content = candidates.getJSONObject(0).getJSONObject("content")
                                val parts = content.getJSONArray("parts")
                                if (parts.length() > 0) {
                                    val text = parts.getJSONObject(0).getString("text")
                                    telemetryFlow.value = telemetryFlow.value.copy(latestTranscript = text)
                                }
                            }
                        } catch (e: Exception) {
                            telemetryFlow.value = telemetryFlow.value.copy(latestTranscript = "خطای پردازش پاسخ: ${e.message}")
                        }
                    }
                }
            })
        } catch (e: Exception) {
            telemetryFlow.value = telemetryFlow.value.copy(latestTranscript = "خطا: ${e.message}")
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
