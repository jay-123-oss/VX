package com.example.vx

import android.graphics.Color
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** Four-zone distance policy requested by the safety specification. */
enum class ThreatZone(
    val priority: Int,
    val labelHindi: String,
    val colorArgb: Int
) {
    SAFE(0, "सुरक्षित", Color.GREEN),
    WARNING(1, "चेतावनी", Color.YELLOW),
    CAUTION(2, "सावधान", 0xFFFF9800.toInt()),
    EMERGENCY(3, "तुरंत रुकें", Color.RED)
}

data class FusedDetection(
    val detection: ObjectDetectorHelper.Detection,
    val distanceMeters: Float?,
    val zone: ThreatZone,
    val trigger: Boolean
)

/** Bridges normalized TFLite boxes with a single ARCore 16-bit depth image. */
class SpatialFusionEngine(
    private val cooldownMs: Long = 1_200L,
    private val confidenceFloor: Float = 0.35f
) {
    private data class TriggerRecord(val zone: ThreatZone, val atMs: Long)
    private val lastTriggers = ConcurrentHashMap<String, TriggerRecord>()

    fun fuse(
        detections: List<ObjectDetectorHelper.Detection>,
        depthSampler: DepthFusionMath.Sampler?,
        nowMs: Long = System.currentTimeMillis()
    ): List<FusedDetection> {
        purge(nowMs)
        return detections
            .asSequence()
            .filter { it.confidence >= confidenceFloor }
            .map { detection ->
                val depthMm = depthSampler?.getAverageDepth(detection.centerX, detection.centerY) ?: 0
                val distance = depthMm.takeIf { it > 0 }?.div(1000f)
                val zone = classify(distance)
                val key = triggerKey(detection)
                val previous = lastTriggers[key]
                val override = previous == null ||
                    nowMs - previous.atMs >= cooldownMs ||
                    zone.priority > previous.zone.priority
                if (override) lastTriggers[key] = TriggerRecord(zone, nowMs)
                FusedDetection(detection, distance, zone, trigger = override)
            }
            .sortedWith(compareByDescending<FusedDetection> { it.zone.priority }.thenByDescending { it.detection.confidence })
            .toList()
    }

    fun classify(distanceMeters: Float?): ThreatZone = when {
        distanceMeters == null || !distanceMeters.isFinite() -> ThreatZone.CAUTION
        distanceMeters > 4f -> ThreatZone.SAFE
        distanceMeters >= 2.5f -> ThreatZone.WARNING
        distanceMeters >= 1f -> ThreatZone.CAUTION
        else -> ThreatZone.EMERGENCY
    }

    fun highestZone(detections: List<FusedDetection>): ThreatZone =
        detections.maxByOrNull { it.zone.priority }?.zone ?: ThreatZone.SAFE

    fun reset() = lastTriggers.clear()

    private fun triggerKey(detection: ObjectDetectorHelper.Detection): String =
        "${detection.classId}:${(detection.centerX * 10f).roundToInt()}:${(detection.centerY * 10f).roundToInt()}"

    private fun purge(nowMs: Long) {
        lastTriggers.entries.removeIf { nowMs - it.value.atMs > cooldownMs * 4L }
    }
}
