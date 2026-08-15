package com.example.vx

import org.junit.Assert.assertEquals
import org.junit.Test

class SafetyDecisionEngineTest {
    private val engine = SafetyDecisionEngine()

    @Test
    fun unknownTrackingFallsBackToCaution() {
        val snapshot = engine.evaluate(null, null, 0f, false)
        assertEquals(SafetyState.CAUTION, snapshot.state)
    }

    @Test
    fun stableFarSurfaceIsSafe() {
        val snapshot = engine.evaluate(3.0f, 0f, 0.9f, true)
        assertEquals(SafetyState.SAFE, snapshot.state)
    }

    @Test
    fun unknownGroundPlaneCannotBecomeSafe() {
        val snapshot = engine.evaluate(3.0f, 0f, 0.9f, true, groundPlaneReliable = false)
        assertEquals(SafetyState.CAUTION, snapshot.state)
    }

    @Test
    fun fastApproachTriggersEmergencyByTtc() {
        val snapshot = engine.evaluate(1.2f, 2.0f, 0.9f, true)
        assertEquals(SafetyState.EMERGENCY, snapshot.state)
    }
}
