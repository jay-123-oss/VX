package com.example.vx

/**
 * Search-only quality policy. Reflex Shield depth sampling never uses this profile.
 */
data class OnnxSearchProfile(
    val name: String,
    val maxImageDimension: Int,
    val jpegQuality: Int,
    val minIntervalMs: Long,
    val messageHindi: String
)

object OnnxSearchScheduler {
    val HIGH = OnnxSearchProfile(
        name = "HIGH",
        maxImageDimension = 1280,
        jpegQuality = 82,
        minIntervalMs = 1_500L,
        messageHindi = "उच्च गुणवत्ता वस्तु खोज"
    )

    val BALANCED = OnnxSearchProfile(
        name = "BALANCED",
        maxImageDimension = 960,
        jpegQuality = 74,
        minIntervalMs = 2_500L,
        messageHindi = "कम ऊर्जा वस्तु खोज"
    )

    val LOW = OnnxSearchProfile(
        name = "LOW",
        maxImageDimension = 640,
        jpegQuality = 64,
        minIntervalMs = 5_000L,
        messageHindi = "धीमी वस्तु खोज"
    )

    fun profile(thermalStatus: Int, thermalHeadroom: Float): OnnxSearchProfile {
        return when {
            thermalStatus >= 4 -> LOW // CRITICAL: caller should normally disable search.
            thermalStatus >= 2 -> LOW
            thermalStatus >= 1 -> BALANCED
            thermalHeadroom.isFinite() && thermalHeadroom >= 0.85f -> LOW
            thermalHeadroom.isFinite() && thermalHeadroom >= 0.70f -> BALANCED
            else -> HIGH
        }
    }

    fun canRun(nowMs: Long, lastRunMs: Long, profile: OnnxSearchProfile): Boolean {
        return lastRunMs <= 0L || nowMs - lastRunMs >= profile.minIntervalMs
    }
}
