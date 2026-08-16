package com.example.vx

import android.content.Context
import java.util.Locale

/** Compatibility facade for existing depth callers plus the new detection-driven alert path. */
class AlertEngine(context: Context) {
    private val spatialBeep = SpatialBeepPlayer(context.applicationContext)
    private val guidance = GuidanceManager(context)
    private val smartEngine = SmartAlertEngine()
    private var lastSafetyState: SafetyState? = null
    private var lastSafetySpeechMs = Long.MIN_VALUE

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
    fun processSnapshot(snapshot: SafetySnapshot, xPos: Float) {
        val now = System.currentTimeMillis()
        val stateChanged = snapshot.state != lastSafetyState
        val speechCooldown = when (snapshot.state) {
            SafetyState.EMERGENCY -> 5_000L
            SafetyState.WARNING -> 7_000L
            SafetyState.CAUTION -> 10_000L
            SafetyState.SAFE -> Long.MAX_VALUE
        }
        val shouldSpeak = stateChanged || now - lastSafetySpeechMs >= speechCooldown
        val decision = when (snapshot.state) {
            SafetyState.SAFE -> AlertDecision(
                level = SmartAlertLevel.SAFE,
                speechHindi = if (lastSafetyState != SafetyState.SAFE && lastSafetyState != null) "रास्ता सुरक्षित है" else null,
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
        guidance.apply(decision, now)
        if (decision.speechHindi != null) lastSafetySpeechMs = now
        if (snapshot.state == SafetyState.SAFE) {
            spatialBeep.stop()
        } else {
            playSpatialBeep(xPos, snapshot.distanceMeters?.times(1000f)?.toInt() ?: 2_000)
        }
        lastSafetyState = snapshot.state
    }

    /** Sends tracked detections through the event-driven FSM and applies only resulting actions. */
    fun processSmartDetections(
        detections: List<TrackedDetection>,
        frameReliable: Boolean = true,
        nowMs: Long = System.currentTimeMillis()
    ): AlertDecision {
        val decision = smartEngine.evaluate(detections, frameReliable, nowMs)
        guidance.apply(decision, nowMs)
        if (decision.level == SmartAlertLevel.SAFE || decision.level == SmartAlertLevel.IDLE) {
            spatialBeep.stop()
        } else if (decision.zone != null) {
            playSpatialBeep((decision.zone.ordinal - 1) * 0.65f, if (decision.level == SmartAlertLevel.CRITICAL) 350 else 900)
        }
        return decision
    }

    fun onSmartFrameUnavailable(nowMs: Long = System.currentTimeMillis()): AlertDecision {
        val decision = smartEngine.onFrameUnavailable(nowMs)
        guidance.apply(decision, nowMs)
        spatialBeep.stop()
        return decision
    }

    fun speakSearch(messageHindi: String) = guidance.speakSearch(messageHindi)

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
