package com.example.vx

import android.content.Context

/**
 * Compatibility facade for existing depth callers plus the detection-driven alert path.
 *
 * Depth and object detection run at different cadences. They therefore update separate decisions
 * and this facade applies only the highest-priority effective decision. This prevents a harmless
 * depth SAFE frame from cancelling a still-active object CRITICAL vibration.
 */
class AlertEngine(context: Context) {
    private val spatialBeep = SpatialBeepPlayer(context.applicationContext)
    private val guidance = GuidanceManager(context)
    private val smartEngine = SmartAlertEngine()
    private var lastSafetyState: SafetyState? = null
    private var lastSafetySpeechMs = Long.MIN_VALUE
    private var latestDepthDecision = AlertDecision(SmartAlertLevel.IDLE, reason = "no depth decision")
    private var latestSmartDecision = AlertDecision(SmartAlertLevel.IDLE, reason = "no smart decision")

    /** Existing distance API retained for compatibility with current VX callers. */
    fun processAlert(distanceMm: Int, xPos: Float) {
        val snapshot = if (distanceMm <= 0) {
            SafetySnapshot(
                SafetyState.CAUTION,
                null,
                0f,
                null,
                false,
                "आगे की सतह स्पष्ट नहीं है, सावधानी रखें"
            )
        } else {
            SafetyDecisionEngine().evaluate(
                distanceMeters = distanceMm / 1000f,
                relativeApproachMetersPerSecond = null,
                confidence = 0.9f,
                trackingReliable = true
            )
        }
        processSnapshot(snapshot, xPos)
    }

    /** Existing depth/plane safety path. Unknown input never becomes SAFE. */
    @Synchronized
    fun processSnapshot(snapshot: SafetySnapshot, xPos: Float) {
        val now = System.currentTimeMillis()
        val stateChanged = snapshot.state != lastSafetyState
        val speechCooldown = when (snapshot.state) {
            SafetyState.EMERGENCY -> 5_000L
            SafetyState.WARNING -> 7_000L
            SafetyState.CAUTION -> 10_000L
            SafetyState.SAFE -> Long.MAX_VALUE
        }
        val shouldSpeak = stateChanged || cooldownElapsed(lastSafetySpeechMs, now, speechCooldown)
        latestDepthDecision = when (snapshot.state) {
            SafetyState.SAFE -> AlertDecision(
                level = SmartAlertLevel.SAFE,
                speechHindi = if (lastSafetyState != SafetyState.SAFE && lastSafetyState != null) {
                    "रास्ता सुरक्षित है"
                } else null,
                reason = "depth path recovered"
            )
            SafetyState.CAUTION -> AlertDecision(
                level = SmartAlertLevel.MEDIUM,
                speechHindi = if (shouldSpeak) snapshot.messageHindi else null,
                haptic = HapticSignal.MEDIUM,
                reason = "depth caution"
            )
            SafetyState.WARNING -> AlertDecision(
                level = SmartAlertLevel.HIGH_RISK,
                speechHindi = if (shouldSpeak) snapshot.messageHindi else null,
                haptic = HapticSignal.HIGH_RISK,
                reason = "depth warning"
            )
            SafetyState.EMERGENCY -> AlertDecision(
                level = SmartAlertLevel.CRITICAL,
                speechHindi = if (shouldSpeak) snapshot.messageHindi else null,
                haptic = HapticSignal.CRITICAL,
                reason = "depth emergency"
            )
        }
        if (latestDepthDecision.speechHindi != null) lastSafetySpeechMs = now
        lastSafetyState = snapshot.state
        applyEffective(xPos, now)
    }

    /** Sends tracked detections through the event-driven FSM and applies only the effective action. */
    @Synchronized
    fun processSmartDetections(
        detections: List<TrackedDetection>,
        frameReliable: Boolean = true,
        nowMs: Long = System.currentTimeMillis()
    ): AlertDecision {
        latestSmartDecision = smartEngine.evaluate(detections, frameReliable, nowMs)
        applyEffective(0f, nowMs)
        return latestSmartDecision
    }

    @Synchronized
    fun onSmartFrameUnavailable(nowMs: Long = System.currentTimeMillis()): AlertDecision {
        latestSmartDecision = smartEngine.onFrameUnavailable(nowMs)
        applyEffective(0f, nowMs)
        return latestSmartDecision
    }

    fun speakSearch(messageHindi: String) = guidance.speakSearch(messageHindi)

    private fun applyEffective(xPos: Float, nowMs: Long) {
        val effective = chooseEffectiveDecision()
        guidance.apply(effective, nowMs)
        when {
            effective.level == SmartAlertLevel.SAFE || effective.level == SmartAlertLevel.IDLE ||
                effective.level == SmartAlertLevel.DEGRADED -> spatialBeep.stop()
            effective.zone != null -> {
                val beepX = (effective.zone.ordinal - 1) * 0.65f
                playSpatialBeep(beepX, if (effective.level == SmartAlertLevel.CRITICAL) 350 else 900)
            }
            else -> playSpatialBeep(xPos, 900)
        }
    }

    private fun chooseEffectiveDecision(): AlertDecision {
        val depthRank = priority(latestDepthDecision.level)
        val smartRank = priority(latestSmartDecision.level)
        return if (smartRank >= depthRank) latestSmartDecision else latestDepthDecision
    }

    private fun priority(level: SmartAlertLevel): Int = when (level) {
        SmartAlertLevel.IDLE -> 0
        SmartAlertLevel.SAFE -> 1
        SmartAlertLevel.MEDIUM -> 2
        SmartAlertLevel.DEGRADED -> 3
        SmartAlertLevel.HIGH_RISK -> 4
        SmartAlertLevel.CRITICAL -> 5
    }

    private fun cooldownElapsed(lastMs: Long, nowMs: Long, cooldownMs: Long): Boolean =
        lastMs == Long.MIN_VALUE || (nowMs >= lastMs && nowMs - lastMs >= cooldownMs)

    private fun playSpatialBeep(xPos: Float, distanceMm: Int) {
        val intensity = ((2_000f - distanceMm) / 2_000f).coerceIn(0.15f, 1f)
        val leftVol = if (xPos < 0) 1f else 1f - xPos.coerceIn(0f, 1f)
        val rightVol = if (xPos > 0) 1f else 1f + xPos.coerceIn(-1f, 0f)
        val frequency = when {
            distanceMm <= 500 -> 880.0
            distanceMm <= 1_000 -> 660.0
            else -> 440.0
        }
        spatialBeep.play(leftVol * intensity, rightVol * intensity, frequency, 110)
    }

    fun release() {
        spatialBeep.release()
        guidance.release()
    }
}
