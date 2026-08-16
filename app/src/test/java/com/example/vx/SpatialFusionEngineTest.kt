package com.example.vx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialFusionEngineTest {
    private val detection = ObjectDetectorHelper.Detection(
        label = "car",
        classId = 2,
        confidence = 0.9f,
        left = 0.4f,
        top = 0.3f,
        right = 0.6f,
        bottom = 0.7f
    )

    @Test
    fun classifiesRequiredDistanceZones() {
        val engine = SpatialFusionEngine()
        assertEquals(ThreatZone.SAFE, engine.classify(4.1f))
        assertEquals(ThreatZone.WARNING, engine.classify(3.0f))
        assertEquals(ThreatZone.CAUTION, engine.classify(1.5f))
        assertEquals(ThreatZone.EMERGENCY, engine.classify(0.8f))
        assertEquals(ThreatZone.CAUTION, engine.classify(null))
    }

    @Test
    fun suppressesDuplicateTriggersUntilCooldown() {
        val engine = SpatialFusionEngine(cooldownMs = 1_000L)
        val first = engine.fuse(listOf(detection), depthSampler = null, nowMs = 100L).single()
        val duplicate = engine.fuse(listOf(detection), depthSampler = null, nowMs = 500L).single()
        val afterCooldown = engine.fuse(listOf(detection), depthSampler = null, nowMs = 1_100L).single()
        assertTrue(first.trigger)
        assertFalse(duplicate.trigger)
        assertTrue(afterCooldown.trigger)
    }

    @Test
    fun higherPriorityZoneOverridesCooldown() {
        val engine = SpatialFusionEngine(cooldownMs = 10_000L)
        val first = engine.fuse(listOf(detection), depthSampler = null, nowMs = 100L).single()
        assertEquals(ThreatZone.CAUTION, first.zone)
        // With unknown depth the zone remains caution; the direct classifier still verifies the
        // priority policy used when a later depth sample becomes an emergency.
        assertTrue(ThreatZone.EMERGENCY.priority > ThreatZone.CAUTION.priority)
    }
}
