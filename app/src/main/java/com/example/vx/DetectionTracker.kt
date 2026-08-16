package com.example.vx

import kotlin.math.max

/**
 * Small greedy tracker intended for the bounded on-device detection stream. It is deliberately
 * conservative: when matching is uncertain, a new ID is created instead of merging objects.
 */
class DetectionTracker(
    private val maxAgeMs: Long = 1_500L,
    private val minimumIou: Float = 0.25f
) {
    private data class Track(
        val id: String,
        var detection: OnnxObjectDetector.Detection,
        var lastSeenMs: Long
    )

    private val tracks = mutableListOf<Track>()
    private var nextId = 1

    @Synchronized
    fun update(detections: List<OnnxObjectDetector.Detection>, nowMs: Long): List<TrackedDetection> {
        tracks.removeAll { nowMs - it.lastSeenMs > maxAgeMs }
        val unmatched = detections.toMutableList()
        val matched = mutableListOf<TrackedDetection>()

        tracks.sortedByDescending { it.lastSeenMs }.forEach { track ->
            val bestIndex = unmatched.indices.maxByOrNull { index ->
                if (unmatched[index].classId != track.detection.classId) 0f
                else intersectionOverUnion(unmatched[index], track.detection)
            }
            if (bestIndex != null) {
                val candidate = unmatched[bestIndex]
                val overlap = intersectionOverUnion(candidate, track.detection)
                if (candidate.classId == track.detection.classId && overlap >= minimumIou) {
                    track.detection = candidate
                    track.lastSeenMs = nowMs
                    unmatched.removeAt(bestIndex)
                    matched += candidate.toTracked(track.id, nowMs)
                }
            }
        }

        unmatched.forEach { detection ->
            val track = Track("${detection.label}-${nextId++}", detection, nowMs)
            tracks += track
            matched += detection.toTracked(track.id, nowMs)
        }
        return matched.sortedByDescending { it.riskScore() }
    }

    @Synchronized
    fun reset() {
        tracks.clear()
        nextId = 1
    }

    private fun intersectionOverUnion(
        first: OnnxObjectDetector.Detection,
        second: OnnxObjectDetector.Detection
    ): Float {
        val left = max(first.left, second.left)
        val top = max(first.top, second.top)
        val right = minOf(first.right, second.right)
        val bottom = minOf(first.bottom, second.bottom)
        val intersection = ((right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f))
        val union = first.width * first.height + second.width * second.height - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun OnnxObjectDetector.Detection.toTracked(id: String, nowMs: Long) =
        TrackedDetection(id, label, classId, confidence, centerX, centerY, width, height, nowMs)
}
