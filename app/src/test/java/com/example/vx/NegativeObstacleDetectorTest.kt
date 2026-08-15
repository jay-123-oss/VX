package com.example.vx

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NegativeObstacleDetectorTest {
    @Test
    fun oneNoisyDropDoesNotImmediatelyTriggerEmergency() {
        val detector = NegativeObstacleDetector()
        val result = detector.assess(intArrayOf(900, 1100, 1200, 2200))
        assertFalse(result.dropDetected)
    }

    @Test
    fun persistentDropTriggersEmergencyCandidate() {
        val detector = NegativeObstacleDetector()
        val profile = intArrayOf(900, 1100, 1200, 2200)
        detector.assess(profile)
        val result = detector.assess(profile)
        assertTrue(result.dropDetected)
    }

    @Test
    fun missingDepthIsUnknownNotClear() {
        val result = NegativeObstacleDetector().assess(intArrayOf(0, 0, 0, 0))
        assertTrue(result.unknownGround)
        assertFalse(result.dropDetected)
    }
}
