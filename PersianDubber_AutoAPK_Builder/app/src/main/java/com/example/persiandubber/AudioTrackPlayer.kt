package com.example.persiandubber

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

class AudioTrackPlayer(
    private val sampleRate: Int = 24000,
    private val onPlaybackStateChanged: (Boolean) -> Unit
) {
    private var audioTrack: AudioTrack? = null

    fun start() {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
    }

    fun writePcm(pcm24kBytes: ByteArray) {
        onPlaybackStateChanged(true)
        audioTrack?.write(pcm24kBytes, 0, pcm24kBytes.size)
        onPlaybackStateChanged(false)
    }

    fun stop() {
        onPlaybackStateChanged(false)
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }
}
