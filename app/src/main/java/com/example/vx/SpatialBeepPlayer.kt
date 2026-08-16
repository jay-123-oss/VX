package com.example.vx

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.SparseIntArray

/**
 * Low-latency reusable alert player. Preloaded short clips avoid per-alert PCM generation and
 * continuous AudioTrack writes, which were producing underruns in the runtime log.
 */
class SpatialBeepPlayer(context: Context) {
    private val soundPool: SoundPool
    private val soundIds = SparseIntArray(3)
    @Volatile private var loadedCount = 0
    @Volatile private var released = false
    @Volatile private var activeStreamId = 0

    init {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(attributes)
            .build()
        soundPool.setOnLoadCompleteListener { _, _, status ->
            if (status == 0) loadedCount++
        }
        soundIds.put(BEEP_LOW, soundPool.load(context, R.raw.beep_low, 1))
        soundIds.put(BEEP_MID, soundPool.load(context, R.raw.beep_mid, 1))
        soundIds.put(BEEP_HIGH, soundPool.load(context, R.raw.beep_high, 1))
    }

    fun play(leftGain: Float, rightGain: Float, frequencyHz: Double, durationMs: Int = 140) {
        if (released || loadedCount < 1) return
        val clip = when {
            frequencyHz >= 820.0 -> BEEP_HIGH
            frequencyHz >= 560.0 -> BEEP_MID
            else -> BEEP_LOW
        }
        val sampleId = soundIds.get(clip, 0)
        if (sampleId == 0) return
        val left = leftGain.coerceIn(0f, 1f)
        val right = rightGain.coerceIn(0f, 1f)
        activeStreamId = soundPool.play(sampleId, left, right, 1, 0, 1f)
    }

    fun stop() {
        val streamId = activeStreamId
        if (streamId != 0) {
            soundPool.stop(streamId)
            activeStreamId = 0
        }
    }

    fun release() {
        if (released) return
        stop()
        released = true
        soundPool.release()
    }

    companion object {
        private const val BEEP_LOW = 1
        private const val BEEP_MID = 2
        private const val BEEP_HIGH = 3
    }
}
