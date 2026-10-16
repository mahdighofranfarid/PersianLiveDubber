package com.example.persiandubber

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlin.math.min

class GeminiLiveWebSocketClient(
    private val proxyUrl: String,
    private val onStateChange: (String) -> Unit,
    private val onDubbedAudioReceived: (ByteArray, Long) -> Unit
) {
    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val offlineRingBuffer = ArrayDeque<ByteArray>(30)

    @Volatile var isConnected = false
        private set
    private var retryAttempt = 0
    private var isStoppedManually = false

    fun connect() {
        isStoppedManually = false
        onStateChange("در حال اتصال به سرور پروکسی...")
        val request = Request.Builder().url(proxyUrl).build()
        webSocket = okHttpClient.newWebSocket(request, listener)
    }

    fun sendPcmFrame(pcm16k: ByteArray, speechCaptureTimestampMs: Long) {
        if (!isConnected) {
            synchronized(offlineRingBuffer) {
                if (offlineRingBuffer.size >= 30) {
                    offlineRingBuffer.pollFirst() // Drop oldest packet to avoid latency buildup
                }
                offlineRingBuffer.addLast(pcm16k)
            }
            return
        }
        sendPayload(pcm16k, speechCaptureTimestampMs)
    }

    private fun sendPayload(pcm16k: ByteArray, timestampMs: Long) {
        val b64 = Base64.encodeToString(pcm16k, Base64.NO_WRAP)
        val payload = JSONObject().apply {
            put("type", "audio_chunk")
            put("clientTimestampMs", timestampMs)
            put("pcmBase64", b64)
        }
        webSocket?.send(payload.toString())
    }

    private fun flushRingBuffer() {
        synchronized(offlineRingBuffer) {
            while (offlineRingBuffer.isNotEmpty() && isConnected) {
                val frame = offlineRingBuffer.pollFirst() ?: break
                sendPayload(frame, System.currentTimeMillis())
            }
        }
    }

    private fun scheduleReconnect() {
        if (isStoppedManually) return
        retryAttempt++
        val delayMs = min(15000L, (1000L * (1 shl min(retryAttempt, 4))))
        onStateChange("قطع شبکه؛ تلاش مجدد در ${delayMs / 1000} ثانیه (صف آفلاین: ${offlineRingBuffer.size}/30)...")
        scope.launch {
            delay(delayMs)
            if (!isConnected && !isStoppedManually) {
                connect()
            }
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            isConnected = true
            retryAttempt = 0
            onStateChange("متصل به Gemini Live (آماده دوبله)")
            flushRingBuffer()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val json = JSONObject(text)
                if (json.optString("type") == "dubbed_audio") {
                    val b64 = json.optString("pcmBase64", "")
                    val clientTs = json.optLong("clientTimestampMs", 0L)
                    if (b64.isNotEmpty()) {
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        onDubbedAudioReceived(bytes, clientTs)
                    }
                }
            } catch (e: Exception) {
                Log.e("PersianDub", "WS Parse Error: ${e.message}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            isConnected = false
            Log.w("PersianDub", "WebSocket disconnected: ${t.message}")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            isConnected = false
            if (!isStoppedManually) scheduleReconnect()
        }
    }

    fun disconnect() {
        isStoppedManually = true
        isConnected = false
        scope.cancel()
        webSocket?.close(1000, "Stopped by user")
    }
}
