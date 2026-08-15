package com.example.vx

/**
 * Detects a likely negative obstacle from a vertical depth profile. This is not a promise of
 * pothole recognition: the detector only escalates when the ground discontinuity is persistent.
 */
class NegativeObstacleDetector {
    data class Assessment(
        val dropDetected: Boolean,
        val unknownGround: Boolean,
        val dropMillimeters: Int,
        val messageHindi: String
    )

    private var consecutiveDropFrames = 0

    fun assess(profileMillimeters: IntArray, groundPlaneTracked: Boolean = true): Assessment {
        if (profileMillimeters.size < 3 || !groundPlaneTracked) return unknown()
        if (profileMillimeters.any { it <= 0 || it >= FAR_SATURATION_MM }) {
            consecutiveDropFrames = 0
            return unknown()
        }
        val upper = profileMillimeters[0]
        val middle = profileMillimeters[profileMillimeters.size / 2]
        val lower = profileMillimeters.last()
        if (upper <= 0 || middle <= 0 || lower <= 0) {
            consecutiveDropFrames = 0
            return unknown()
        }

        val expectedRise = (middle - upper).coerceAtLeast(0)
        val observedRise = lower - middle
        val excessRise = observedRise - expectedRise
        val likelyDrop = excessRise >= MIN_EXCESS_DROP_MM && lower >= middle + MIN_NEAR_RISE_MM
        consecutiveDropFrames = if (likelyDrop) consecutiveDropFrames + 1 else 0

        return if (consecutiveDropFrames >= REQUIRED_CONFIRMATION_FRAMES) {
            Assessment(
                dropDetected = true,
                unknownGround = false,
                dropMillimeters = excessRise,
                messageHindi = "आगे गड्ढा या नीचे उतरती सीढ़ी हो सकती है, तुरंत रुकें"
            )
        } else {
            Assessment(false, false, excessRise.coerceAtLeast(0), "जमीन का प्रोफाइल जांचा जा रहा है")
        }
    }

    fun reset() {
        consecutiveDropFrames = 0
    }

    private fun unknown(): Assessment = Assessment(
        dropDetected = false,
        unknownGround = true,
        dropMillimeters = 0,
        messageHindi = "जमीन स्पष्ट नहीं है, सावधानी रखें"
    )

    companion object {
        private const val MIN_EXCESS_DROP_MM = 450
        private const val MIN_NEAR_RISE_MM = 700
        private const val REQUIRED_CONFIRMATION_FRAMES = 2
        private const val FAR_SATURATION_MM = 7800
    }
}
