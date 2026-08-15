package com.example.vx

import org.junit.Assert.assertEquals
import org.junit.Test

class DepthCorridorClassificationTest {
    @Test
    fun safeDistanceIsGreenState() {
        val snapshot = SafetyDecisionEngine().evaluate(
            distanceMeters = 3.0f,
            relativeApproachMetersPerSecond = 0f,
            confidence = 0.8f,
            trackingReliable = true
        )
        assertEquals(SafetyState.SAFE, snapshot.state)
    }

    @Test
    fun closeSurfaceIsWarningOrEmergency() {
        val snapshot = SafetyDecisionEngine().evaluate(
            distanceMeters = 0.8f,
            relativeApproachMetersPerSecond = 0f,
            confidence = 0.8f,
            trackingReliable = true
        )
        assertEquals(SafetyState.WARNING, snapshot.state)
    }

    @Test
    fun unknownDepthNeverBecomesGreen() {
        val snapshot = SafetyDecisionEngine().evaluate(
            distanceMeters = null,
            relativeApproachMetersPerSecond = null,
            confidence = 0f,
            trackingReliable = false
        )
        assertEquals(SafetyState.CAUTION, snapshot.state)
    }
}
