package com.example.vx

/**
 * Deterministic alert policy. It consumes summarized detections and never performs audio or
 * vibration itself, which keeps it unit-testable and independent from Android hardware.
 */
class SmartAlertEngine(
    private val memory: TemporalMemoryBuffer = TemporalMemoryBuffer(),
    private val nowProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val lastSeenByObject = mutableMapOf<String, Long>()
    private val lastHighSpeechByObject = mutableMapOf<String, Long>()
    private val lastMediumSpeechByObject = mutableMapOf<String, Long>()
    private var previousLevel = SmartAlertLevel.IDLE
    private var lastCriticalObjectId: String? = null
    private var lastDegradedSpeechMs = Long.MIN_VALUE

    @Synchronized
    fun evaluate(
        detections: List<TrackedDetection>,
        frameReliable: Boolean = true,
        nowMs: Long = nowProvider()
    ): AlertDecision {
        if (!frameReliable) return degradedDecision(nowMs)

        val valid = detections.filter { it.isValid && it.confidence >= MIN_CONFIDENCE }
        val meaningful = valid.filter { it.areaFraction >= MEDIUM_AREA }
        val blockedZones = meaningful.map { it.zone }.toSet()
        memory.add(GridObservation(nowMs, blockedZones, reliable = true))

        val critical = meaningful.firstOrNull { isCritical(it) }
        val highRisk = meaningful.firstOrNull { isHighRisk(it) }
        val medium = meaningful.firstOrNull()
        val hadDanger = previousLevel == SmartAlertLevel.MEDIUM ||
            previousLevel == SmartAlertLevel.HIGH_RISK ||
            previousLevel == SmartAlertLevel.CRITICAL

        val decision = when {
            critical != null -> criticalDecision(critical, nowMs)
            highRisk != null -> highRiskDecision(highRisk, nowMs)
            medium != null -> mediumDecision(medium, nowMs)
            meaningful.isEmpty() && hadDanger -> AlertDecision(
                level = SmartAlertLevel.SAFE,
                speechHindi = "रास्ता सुरक्षित है",
                haptic = HapticSignal.NONE,
                reason = "verified clear frame after danger"
            )
            else -> AlertDecision(SmartAlertLevel.IDLE, reason = "no state transition")
        }

        valid.forEach { lastSeenByObject[it.objectId] = nowMs }
        previousLevel = decision.level
        if (decision.level == SmartAlertLevel.SAFE) {
            lastCriticalObjectId = null
        }
        return decision
    }

    @Synchronized
    fun onFrameUnavailable(nowMs: Long = nowProvider()): AlertDecision = degradedDecision(nowMs)

    @Synchronized
    fun reset() {
        memory.clear()
        lastSeenByObject.clear()
        lastHighSpeechByObject.clear()
        lastMediumSpeechByObject.clear()
        previousLevel = SmartAlertLevel.IDLE
        lastCriticalObjectId = null
        lastDegradedSpeechMs = Long.MIN_VALUE
    }

    private fun criticalDecision(detection: TrackedDetection, nowMs: Long): AlertDecision {
        val shouldSpeak = lastCriticalObjectId != detection.objectId || previousLevel != SmartAlertLevel.CRITICAL
        if (shouldSpeak) lastCriticalObjectId = detection.objectId
        val route = if (detection.zone == GridZone.CENTER) routeSuggestion(nowMs) else null
        val routeSpeech = when (route) {
            RouteSuggestion.TURN_LEFT -> "तुरंत रुकें। बाईं ओर रास्ता जांचें"
            RouteSuggestion.TURN_RIGHT -> "तुरंत रुकें। दाईं ओर रास्ता जांचें"
            RouteSuggestion.STOP_AND_SCAN -> "तुरंत रुकें। सुरक्षित दिशा स्पष्ट नहीं है"
            null -> "तुरंत रुकें"
        }
        return AlertDecision(
            level = SmartAlertLevel.CRITICAL,
            speechHindi = if (shouldSpeak) routeSpeech else null,
            haptic = HapticSignal.CRITICAL,
            routeSuggestion = route,
            objectId = detection.objectId,
            zone = detection.zone,
            reason = "critical bounding-box risk"
        )
    }

    private fun highRiskDecision(detection: TrackedDetection, nowMs: Long): AlertDecision {
        val lastSpoken = lastHighSpeechByObject[detection.objectId] ?: Long.MIN_VALUE
        val shouldSpeak = cooldownElapsed(lastSpoken, nowMs, HIGH_COOLDOWN_MS) || previousLevel != SmartAlertLevel.HIGH_RISK
        if (shouldSpeak) lastHighSpeechByObject[detection.objectId] = nowMs
        return AlertDecision(
            level = SmartAlertLevel.HIGH_RISK,
            speechHindi = if (shouldSpeak) "सावधान, आगे ${HindiObjectLabels.labelFor(detection.label)} है" else null,
            haptic = HapticSignal.HIGH_RISK,
            objectId = detection.objectId,
            zone = detection.zone,
            reason = "high-risk bounding-box threshold"
        )
    }

    private fun mediumDecision(detection: TrackedDetection, nowMs: Long): AlertDecision {
        val lastSpoken = lastMediumSpeechByObject[detection.objectId] ?: Long.MIN_VALUE
        val shouldSpeak = cooldownElapsed(lastSpoken, nowMs, MEDIUM_COOLDOWN_MS)
        if (shouldSpeak) lastMediumSpeechByObject[detection.objectId] = nowMs
        return AlertDecision(
            level = SmartAlertLevel.MEDIUM,
            speechHindi = if (shouldSpeak) "चेतावनी, आगे ${HindiObjectLabels.labelFor(detection.label)} है" else null,
            haptic = HapticSignal.MEDIUM,
            objectId = detection.objectId,
            zone = detection.zone,
            reason = "new medium-risk object"
        )
    }

    private fun degradedDecision(nowMs: Long): AlertDecision {
        val shouldSpeak = cooldownElapsed(lastDegradedSpeechMs, nowMs, DEGRADED_COOLDOWN_MS)
        if (shouldSpeak) lastDegradedSpeechMs = nowMs
        previousLevel = SmartAlertLevel.DEGRADED
        return AlertDecision(
            level = SmartAlertLevel.DEGRADED,
            speechHindi = if (shouldSpeak) "स्कैन उपलब्ध नहीं है, कृपया रुकें" else null,
            haptic = HapticSignal.STOP,
            reason = "camera, depth, or detector data is unreliable"
        )
    }

    private fun routeSuggestion(nowMs: Long): RouteSuggestion? {
        val leftClear = memory.hasBeenClear(GridZone.LEFT, nowMs, ROUTE_VALIDATION_MS)
        val rightClear = memory.hasBeenClear(GridZone.RIGHT, nowMs, ROUTE_VALIDATION_MS)
        return when {
            leftClear && !rightClear -> RouteSuggestion.TURN_LEFT
            rightClear && !leftClear -> RouteSuggestion.TURN_RIGHT
            leftClear && rightClear -> chooseLessBlockedZone(nowMs)
            else -> RouteSuggestion.STOP_AND_SCAN
        }
    }

    private fun chooseLessBlockedZone(nowMs: Long): RouteSuggestion {
        val recent = memory.snapshot().filter { it.timestampMs >= nowMs - ROUTE_VALIDATION_MS }
        val leftBlocked = recent.count { GridZone.LEFT in it.blockedZones }
        val rightBlocked = recent.count { GridZone.RIGHT in it.blockedZones }
        return if (leftBlocked <= rightBlocked) RouteSuggestion.TURN_LEFT else RouteSuggestion.TURN_RIGHT
    }

    private fun cooldownElapsed(lastMs: Long, nowMs: Long, cooldownMs: Long): Boolean =
        lastMs == Long.MIN_VALUE || (nowMs >= lastMs && nowMs - lastMs >= cooldownMs)

    private fun isNewObject(detection: TrackedDetection, nowMs: Long): Boolean {
        val lastSeen = lastSeenByObject[detection.objectId] ?: return true
        return nowMs - lastSeen >= MEDIUM_COOLDOWN_MS
    }

    private fun isCritical(detection: TrackedDetection): Boolean {
        val threshold = if (previousLevel == SmartAlertLevel.CRITICAL) CRITICAL_EXIT_AREA else CRITICAL_ENTER_AREA
        return detection.areaFraction >= threshold
    }

    private fun isHighRisk(detection: TrackedDetection): Boolean {
        val threshold = if (previousLevel == SmartAlertLevel.HIGH_RISK) HIGH_EXIT_AREA else HIGH_ENTER_AREA
        return detection.areaFraction >= threshold
    }

    companion object {
        const val CRITICAL_ENTER_AREA = 0.60f
        const val CRITICAL_EXIT_AREA = 0.50f
        const val HIGH_ENTER_AREA = 0.40f
        const val HIGH_EXIT_AREA = 0.32f
        const val MEDIUM_AREA = 0.08f
        const val MIN_CONFIDENCE = 0.55f
        const val HIGH_COOLDOWN_MS = 5_000L
        const val MEDIUM_COOLDOWN_MS = 10_000L
        const val ROUTE_VALIDATION_MS = 5_000L
        const val DEGRADED_COOLDOWN_MS = 3_000L
    }
}
