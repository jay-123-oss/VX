package com.example.vx

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Generates short stereo PCM cues locally. Left/right gain provides a dependable fallback when
 * the device does not expose Android Spatializer or the user has no spatial-audio earbuds.
 */
class SpatialBeepPlayer {
    private val sampleRate = 44_100
    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
        )
        .setBufferSizeInBytes(sampleRate * 2 * 2 / 5)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    private val stereoBuffer = ShortArray(sampleRate * 2 / 5)

    @Synchronized
    fun play(leftGain: Float, rightGain: Float, frequencyHz: Double, durationMs: Int) {
        val frames = (sampleRate * durationMs / 1000).coerceIn(1, stereoBuffer.size / 2)
        val safeLeft = leftGain.coerceIn(0f, 1f)
        val safeRight = rightGain.coerceIn(0f, 1f)
        val fadeFrames = (sampleRate * 0.012).toInt().coerceAtLeast(1)
        for (frame in 0 until frames) {
            val envelope = when {
                frame < fadeFrames -> frame.toFloat() / fadeFrames
                frame >= frames - fadeFrames -> (frames - frame).toFloat() / fadeFrames
                else -> 1f
            }.coerceIn(0f, 1f)
            val wave = sin(2.0 * PI * frequencyHz * frame / sampleRate).toFloat()
            stereoBuffer[frame * 2] = (wave * safeLeft * envelope * Short.MAX_VALUE * 0.45f).toInt().toShort()
            stereoBuffer[frame * 2 + 1] = (wave * safeRight * envelope * Short.MAX_VALUE * 0.45f).toInt().toShort()
        }
        track.play()
        track.write(stereoBuffer, 0, frames * 2)
    }

    fun release() {
        track.stop()
        track.release()
    }
}
