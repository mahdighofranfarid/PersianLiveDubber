package com.example.persiandubber

import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class AcousticEchoCancelerManager {
    private var aec: AcousticEchoCanceler? = null
    @Volatile var isDubbingPlaying: Boolean = false

    fun attachToSession(audioSessionId: Int) {
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(audioSessionId)?.apply {
                enabled = true
                Log.i("PersianDub", "Hardware AEC enabled on session: $audioSessionId")
            }
        } else {
            Log.w("PersianDub", "Hardware AEC not available on this audio source; relying on excludeUid + Ducking Gate.")
        }
    }

    /**
     * Soft Ducking Gate: attenuates input buffer to 5% when speaker dubbing is active
     * and calculates RMS level for real-time verification & VAD timestamping.
     */
    fun processAndMeasureRms(pcmBuffer: ByteArray, bytesRead: Int, applyDuckingGate: Boolean): Double {
        var sumSquares = 0.0
        val shortBuffer = ByteBuffer.wrap(pcmBuffer, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val numSamples = shortBuffer.remaining()

        for (i in 0 until numSamples) {
            var sample = shortBuffer.get(i).toInt()
            if (applyDuckingGate && isDubbingPlaying) {
                sample = (sample * 0.05f).toInt()
                pcmBuffer[i * 2] = (sample and 0xFF).toByte()
                pcmBuffer[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
            }
            sumSquares += (sample * sample).toDouble()
        }
        return if (numSamples > 0) sqrt(sumSquares / numSamples) else 0.0
    }

    fun release() {
        aec?.release()
        aec = null
    }
}
