package com.example.vx

import android.media.Image
import java.util.Arrays

/**
 * Samples a small fixed grid in front of the user. No unbounded collections or per-frame model
 * allocations are used. A zero result means that the corridor is unknown, never clear.
 */
class DepthCorridorAnalyzer {
    data class Result(
        val nearestDepthMm: Int,
        val representativeDepthMm: Int,
        val validSamples: Int,
        val totalSamples: Int,
        val confidence: Float
    ) {
        val reliable: Boolean
            get() = validSamples >= 3 && nearestDepthMm > 0
    }

    private val sampleBuffer = IntArray(SAMPLE_XS.size * SAMPLE_YS.size)

    fun analyze(depthImage: Image): Result = analyze(DepthFusionMath.Sampler(depthImage))

    fun analyze(sampler: DepthFusionMath.Sampler): Result {
        var count = 0
        for (x in SAMPLE_XS) {
            for (y in SAMPLE_YS) {
                val depth = sampler.getAverageDepth(x, y)
                if (depth in MIN_VALID_DEPTH_MM..MAX_VALID_DEPTH_MM && count < sampleBuffer.size) {
                    sampleBuffer[count++] = depth
                }
            }
        }
        if (count == 0) {
            return Result(0, 0, 0, sampleBuffer.size, 0f)
        }
        Arrays.sort(sampleBuffer, 0, count)
        val representativeIndex = (count / 2).coerceIn(0, count - 1)
        val confidence = (count.toFloat() / sampleBuffer.size).coerceIn(0f, 1f)
        return Result(
            nearestDepthMm = sampleBuffer[0],
            representativeDepthMm = sampleBuffer[representativeIndex],
            validSamples = count,
            totalSamples = sampleBuffer.size,
            confidence = confidence
        )
    }

    companion object {
        private val SAMPLE_XS = floatArrayOf(0.25f, 0.375f, 0.50f, 0.625f, 0.75f)
        private val SAMPLE_YS = floatArrayOf(0.38f, 0.52f, 0.66f, 0.80f)
        private const val MIN_VALID_DEPTH_MM = 150
        private const val MAX_VALID_DEPTH_MM = 8000
    }
}
