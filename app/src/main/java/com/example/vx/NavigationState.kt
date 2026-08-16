package com.example.vx

/** Silent baseline is distinct from SAFE recovery so the app never repeats safe messages. */
enum class SmartAlertLevel {
    IDLE,
    MEDIUM,
    HIGH_RISK,
    CRITICAL,
    SAFE,
    DEGRADED
}

enum class GridZone {
    LEFT,
    CENTER,
    RIGHT;

    companion object {
        fun fromCenterX(centerX: Float): GridZone = when {
            centerX < 0.333f -> LEFT
            centerX > 0.667f -> RIGHT
            else -> CENTER
        }
    }
}

enum class RouteSuggestion {
    TURN_LEFT,
    TURN_RIGHT,
    STOP_AND_SCAN
}

enum class HapticSignal {
    NONE,
    MEDIUM,
    HIGH_RISK,
    CRITICAL,
    STOP
}

data class TrackedDetection(
    val objectId: String,
    val label: String,
    val classId: Int,
    val confidence: Float,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val timestampMs: Long
) {
    val areaFraction: Float
        get() = width.coerceAtLeast(0f) * height.coerceAtLeast(0f)

    val zone: GridZone
        get() = GridZone.fromCenterX(centerX)

    val isValid: Boolean
        get() = confidence.isFinite() && confidence >= 0f &&
            centerX.isFinite() && centerY.isFinite() &&
            width.isFinite() && height.isFinite() && width > 0f && height > 0f
}

data class AlertDecision(
    val level: SmartAlertLevel,
    val speechHindi: String? = null,
    val haptic: HapticSignal = HapticSignal.NONE,
    val routeSuggestion: RouteSuggestion? = null,
    val objectId: String? = null,
    val zone: GridZone? = null,
    val reason: String = ""
)

internal fun TrackedDetection.riskScore(): Float =
    (areaFraction.coerceIn(0f, 1f) * 0.7f) + (confidence.coerceIn(0f, 1f) * 0.3f)
