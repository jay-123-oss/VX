package com.example.vx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnnxSearchSchedulerTest {
    @Test
    fun coolProfileKeepsHighestSearchQuality() {
        val profile = OnnxSearchScheduler.profile(thermalStatus = 0, thermalHeadroom = Float.NaN)
        assertEquals("HIGH", profile.name)
        assertEquals(1280, profile.maxImageDimension)
    }

    @Test
    fun warmProfileReducesResolutionAndIncreasesCooldown() {
        val cool = OnnxSearchScheduler.profile(0, Float.NaN)
        val warm = OnnxSearchScheduler.profile(2, Float.NaN)
        assertEquals("LOW", warm.name)
        assertTrue(warm.maxImageDimension < cool.maxImageDimension)
        assertTrue(warm.minIntervalMs > cool.minIntervalMs)
    }

    @Test
    fun hotHeadroomReducesSearchBeforeStatusEscalates() {
        val profile = OnnxSearchScheduler.profile(0, 0.90f)
        assertEquals("LOW", profile.name)
    }

    @Test
    fun schedulerBlocksRequestsInsideCooldown() {
        val profile = OnnxSearchScheduler.BALANCED
        assertTrue(OnnxSearchScheduler.canRun(10_000L, 0L, profile))
        assertFalse(OnnxSearchScheduler.canRun(11_000L, 10_000L, profile))
        assertTrue(OnnxSearchScheduler.canRun(12_500L, 10_000L, profile))
    }
}
