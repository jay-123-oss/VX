package com.example.vx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun saturatedFarDepthIsUnknownNotReliable() {
        val result = DepthCorridorAnalyzer.Result(
            nearestDepthMm = 7867,
            representativeDepthMm = 7867,
            validSamples = 20,
            totalSamples = 20,
            confidence = 1f,
            saturatedFarSamples = 20,
            unknownReason = "सभी डेप्थ सैंपल अधिकतम दूरी पर हैं"
        )
        assertFalse(result.reliable)
    }

    @Test
    fun mixedDepthWithNearbyReliableSurfaceCanBeUsed() {
        val result = DepthCorridorAnalyzer.Result(
            nearestDepthMm = 1200,
            representativeDepthMm = 1800,
            validSamples = 18,
            totalSamples = 20,
            confidence = 0.9f,
            saturatedFarSamples = 2,
            unknownReason = null
        )
        assertTrue(result.reliable)
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
