package com.example.vx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartAlertEngineTest {
    private fun detection(
        id: String = "car-1",
        area: Float = 0.10f,
        centerX: Float = 0.5f,
        at: Long = 0L
    ) = TrackedDetection(
        objectId = id,
        label = "car",
        classId = 2,
        confidence = 0.9f,
        centerX = centerX,
        centerY = 0.5f,
        width = area,
        height = 1f,
        timestampMs = at
    )

    @Test
    fun mediumObjectSpeaksOnceDuringCooldownAndRecoversToSafe() {
        val engine = SmartAlertEngine()
        val first = engine.evaluate(listOf(detection()), nowMs = 0L)
        val repeated = engine.evaluate(listOf(detection(at = 1_000L)), nowMs = 1_000L)
        val safe = engine.evaluate(emptyList(), nowMs = 2_000L)
        val idle = engine.evaluate(emptyList(), nowMs = 3_000L)

        assertEquals(SmartAlertLevel.MEDIUM, first.level)
        assertTrue(first.speechHindi != null)
        assertEquals(SmartAlertLevel.MEDIUM, repeated.level)
        assertNull(repeated.speechHindi)
        assertEquals(SmartAlertLevel.SAFE, safe.level)
        assertEquals(SmartAlertLevel.IDLE, idle.level)
    }

    @Test
    fun criticalSpeechIsNotRepeatedWhileCriticalStatePersists() {
        val engine = SmartAlertEngine()
        val first = engine.evaluate(listOf(detection(area = 0.65f)), nowMs = 0L)
        val repeated = engine.evaluate(listOf(detection(area = 0.65f, at = 100L)), nowMs = 100L)

        assertEquals(SmartAlertLevel.CRITICAL, first.level)
        assertTrue(first.speechHindi != null)
        assertEquals(SmartAlertLevel.CRITICAL, repeated.level)
        assertNull(repeated.speechHindi)
    }

    @Test
    fun criticalHysteresisKeepsStateAboveExitThreshold() {
        val engine = SmartAlertEngine()
        engine.evaluate(listOf(detection(area = 0.65f)), nowMs = 0L)
        val stillCritical = engine.evaluate(listOf(detection(area = 0.52f, at = 100L)), nowMs = 100L)

        assertEquals(SmartAlertLevel.CRITICAL, stillCritical.level)
    }

    @Test
    fun centerBlockRequiresFiveSecondsOfReliableSideClearance() {
        val memory = TemporalMemoryBuffer()
        val engine = SmartAlertEngine(memory)
        for (time in 0L..5_000L step 1_000L) {
            val detections = listOf(detection(area = 0.65f, centerX = 0.5f, at = time))
            engine.evaluate(detections, nowMs = time)
        }
        val decision = engine.evaluate(
            listOf(detection(area = 0.65f, centerX = 0.5f, at = 6_000L)),
            nowMs = 6_000L
        )

        assertEquals(RouteSuggestion.TURN_LEFT, decision.routeSuggestion)
    }
}
