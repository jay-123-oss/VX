package com.example.vx

import android.media.Image
import java.util.Arrays

/**
 * Samples a small fixed grid in front of the user. Saturated far readings are not treated as a
 * reliable clear corridor; a zero/mostly-saturated result means unknown and must remain CAUTION.
 */
class DepthCorridorAnalyzer {
    data class Result(
        val nearestDepthMm: Int,
        val representativeDepthMm: Int,
        val validSamples: Int,
        val totalSamples: Int,
        val confidence: Float,
        val saturatedFarSamples: Int,
        val unknownReason: String?
    ) {
        val reliable: Boolean
            get() = validSamples >= MIN_RELIABLE_SAMPLES &&
                nearestDepthMm in MIN_VALID_DEPTH_MM until MAX_VALID_DEPTH_MM &&
                saturatedFarSamples < validSamples
    }

    private val sampleBuffer = IntArray(SAMPLE_XS.size * SAMPLE_YS.size)

    fun analyze(depthImage: Image): Result = analyze(DepthFusionMath.Sampler(depthImage))

    fun analyze(sampler: DepthFusionMath.Sampler): Result {
        var count = 0
        var saturated = 0
        for (x in SAMPLE_XS) {
            for (y in SAMPLE_YS) {
                val depth = sampler.getAverageDepth(x, y)
                if (depth in MIN_VALID_DEPTH_MM..MAX_VALID_DEPTH_MM && count < sampleBuffer.size) {
                    sampleBuffer[count++] = depth
                    if (depth >= FAR_SATURATION_MM) saturated++
                }
            }
        }
        if (count == 0) {
            return Result(0, 0, 0, sampleBuffer.size, 0f, 0, "डेप्थ सैंपल उपलब्ध नहीं है")
        }
        Arrays.sort(sampleBuffer, 0, count)
        val representativeIndex = (count / 2).coerceIn(0, count - 1)
        val nearest = sampleBuffer[0]
        val confidence = (count.toFloat() / sampleBuffer.size).coerceIn(0f, 1f)
        val reason = when {
            count < MIN_RELIABLE_SAMPLES -> "डेप्थ सैंपल बहुत कम हैं"
            saturated == count -> "सभी डेप्थ सैंपल अधिकतम दूरी पर हैं"
            else -> null
        }
        return Result(
            nearestDepthMm = nearest,
            representativeDepthMm = sampleBuffer[representativeIndex],
            validSamples = count,
            totalSamples = sampleBuffer.size,
            confidence = confidence,
            saturatedFarSamples = saturated,
            unknownReason = reason
        )
    }

    companion object {
        private val SAMPLE_XS = floatArrayOf(0.25f, 0.375f, 0.50f, 0.625f, 0.75f)
        private val SAMPLE_YS = floatArrayOf(0.38f, 0.52f, 0.66f, 0.80f)
        private const val MIN_VALID_DEPTH_MM = 150
        private const val MAX_VALID_DEPTH_MM = 8000
        private const val FAR_SATURATION_MM = 7800
        private const val MIN_RELIABLE_SAMPLES = 3
    }
}
