package com.example.vx

import java.util.ArrayDeque

/** A compact observation rather than a raw camera frame, keeping memory predictable. */
data class GridObservation(
    val timestampMs: Long,
    val blockedZones: Set<GridZone>,
    val reliable: Boolean
)

class TemporalMemoryBuffer(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {
    private val observations = ArrayDeque<GridObservation>()

    @Synchronized
    fun add(observation: GridObservation) {
        observations.addLast(observation)
        trim(observation.timestampMs)
    }

    @Synchronized
    fun clear() = observations.clear()

    @Synchronized
    fun snapshot(): List<GridObservation> = observations.toList()

    /** Returns true only when every retained sample in the interval was reliable and clear. */
    @Synchronized
    fun hasBeenClear(zone: GridZone, nowMs: Long, requiredMs: Long): Boolean {
        val cutoff = nowMs - requiredMs.coerceAtLeast(0L)
        val relevant = observations.filter { it.timestampMs >= cutoff && it.timestampMs <= nowMs }
        if (relevant.isEmpty()) return false
        if (relevant.first().timestampMs > cutoff) return false
        return relevant.all { it.reliable && zone !in it.blockedZones }
    }

    @Synchronized
    fun lastReliableObservation(nowMs: Long): GridObservation? =
        observations.lastOrNull { it.timestampMs <= nowMs && it.reliable }

    private fun trim(nowMs: Long) {
        val cutoff = nowMs - windowMs
        while (observations.size > maxEntries || observations.firstOrNull()?.timestampMs?.let { it < cutoff } == true) {
            observations.removeFirst()
        }
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 20_000L
        const val DEFAULT_MAX_ENTRIES = 40
    }
}
