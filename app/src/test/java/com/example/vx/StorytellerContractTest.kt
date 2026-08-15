package com.example.vx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorytellerContractTest {
    @Test
    fun latestFrameReplacesOlderFrameWithoutQueueing() {
        val store = LatestStorytellerFrame(ttlNs = 10_000L)
        assertTrue(store.offer(byteArrayOf(1), capturedAtNs = 100L, nowNs = 100L))
        assertTrue(store.offer(byteArrayOf(2), capturedAtNs = 200L, nowNs = 200L))

        val latest = store.takeLatest(nowNs = 250L)
        assertNotNull(latest)
        assertEquals(2, latest!!.jpegBytes.single().toInt())
        assertNull(store.takeLatest(nowNs = 250L))
    }

    @Test
    fun expiredFrameIsDiscarded() {
        val store = LatestStorytellerFrame(ttlNs = 100L)
        assertTrue(store.offer(byteArrayOf(7), capturedAtNs = 100L, nowNs = 100L))
        assertNull(store.takeLatest(nowNs = 200L))
    }

    @Test
    fun closedStoreRejectsNewFrames() {
        val store = LatestStorytellerFrame()
        store.close()
        assertFalse(store.offer(byteArrayOf(1), capturedAtNs = 100L, nowNs = 100L))
        assertNull(store.takeLatest(nowNs = 100L))
    }

    @Test
    fun unavailableEngineNeverProducesFakeResult() {
        val engine = UnavailableStorytellerEngine()
        assertFalse(engine.isAvailable)
        assertFalse(engine.submitLatestFrame(StorytellerFrame(byteArrayOf(1), 100L, 200L)))
        assertNull(engine.latestResult(nowNs = 150L))
        engine.close()
    }

    @Test
    fun storytellerWarningMayRaiseSupplementaryAlertButEmergencyCannotBeSoleAuthority() {
        val warning = StorytellerResult(
            schemaVersion = StorytellerResult.SCHEMA_VERSION,
            hazard = StorytellerHazard.POTHOLE,
            severity = StorytellerSeverity.WARNING,
            region = StorytellerRegion.CENTER,
            confidence = 0.8f,
            evidence = "सड़क असमान लगती है",
            capturedAtNs = 100L,
            expiresAtNs = 1_000L
        )
        val emergency = warning.copy(severity = StorytellerSeverity.EMERGENCY)
        assertTrue(warning.canRaiseSafetyAlert)
        assertFalse(emergency.canRaiseSafetyAlert)
    }
}
