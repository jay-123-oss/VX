package com.example.vx

/** Closed vocabulary prevents free-form VLM text from becoming a safety command. */
enum class StorytellerHazard {
    POTHOLE,
    DOWNWARD_STAIRS,
    UNEVEN_GROUND,
    OBSTACLE,
    CROWD,
    NONE,
    UNKNOWN
}

enum class StorytellerSeverity {
    CAUTION,
    WARNING,
    EMERGENCY,
    UNKNOWN
}

enum class StorytellerRegion {
    LEFT,
    CENTER,
    RIGHT,
    UNKNOWN
}

data class StorytellerResult(
    val schemaVersion: Int,
    val hazard: StorytellerHazard,
    val severity: StorytellerSeverity,
    val region: StorytellerRegion,
    val confidence: Float,
    val evidence: String,
    val capturedAtNs: Long,
    val expiresAtNs: Long
) {
    val isFresh: Boolean
        get() = isFreshAt(System.nanoTime())

    fun isFreshAt(nowNs: Long): Boolean =
        capturedAtNs > 0L && expiresAtNs > capturedAtNs && nowNs in capturedAtNs until expiresAtNs

    /** VLM may add a conservative warning, but it cannot create a safety emergency alone. */
    val canRaiseSafetyAlert: Boolean
        get() = hazard != StorytellerHazard.UNKNOWN &&
            hazard != StorytellerHazard.NONE &&
            confidence >= MIN_ALERT_CONFIDENCE &&
            (severity == StorytellerSeverity.CAUTION || severity == StorytellerSeverity.WARNING)

    companion object {
        const val SCHEMA_VERSION = 1
        const val MIN_ALERT_CONFIDENCE = 0.70f
        const val MAX_EVIDENCE_CHARS = 160

        fun unavailable(nowNs: Long = System.nanoTime()): StorytellerResult = StorytellerResult(
            schemaVersion = SCHEMA_VERSION,
            hazard = StorytellerHazard.UNKNOWN,
            severity = StorytellerSeverity.UNKNOWN,
            region = StorytellerRegion.UNKNOWN,
            confidence = 0f,
            evidence = "विजुअल स्टोरीटेलर उपलब्ध नहीं है",
            capturedAtNs = nowNs,
            expiresAtNs = nowNs
        )
    }
}

/** Immutable ownership-transfer object for a single latest camera image. */
data class StorytellerFrame(
    val jpegBytes: ByteArray,
    val capturedAtNs: Long,
    val expiresAtNs: Long
) {
    fun isFreshAt(nowNs: Long): Boolean =
        capturedAtNs > 0L && expiresAtNs > capturedAtNs && nowNs in capturedAtNs until expiresAtNs
}

/**
 * Stores at most one pending image. `offer` takes ownership of the ByteArray; callers must not
 * mutate it after submitting. Replacing an older frame is intentional and avoids a backlog.
 */
class LatestStorytellerFrame(
    private val ttlNs: Long = DEFAULT_TTL_NS
) {
    private var pending: StorytellerFrame? = null
    private var closed = false

    @Synchronized
    fun offer(jpegBytes: ByteArray, capturedAtNs: Long, nowNs: Long = System.nanoTime()): Boolean {
        if (closed || jpegBytes.isEmpty() || capturedAtNs <= 0L) return false
        val expiresAtNs = (capturedAtNs + ttlNs).coerceAtLeast(nowNs + 1L)
        pending = StorytellerFrame(jpegBytes, capturedAtNs, expiresAtNs)
        return true
    }

    @Synchronized
    fun takeLatest(nowNs: Long = System.nanoTime()): StorytellerFrame? {
        if (closed) return null
        val frame = pending
        pending = null
        return frame?.takeIf { it.isFreshAt(nowNs) }
    }

    @Synchronized
    fun hasFreshPending(nowNs: Long = System.nanoTime()): Boolean =
        !closed && pending?.isFreshAt(nowNs) == true

    @Synchronized
    fun clear() {
        pending = null
    }

    @Synchronized
    fun close() {
        closed = true
        pending = null
    }

    companion object {
        private const val DEFAULT_TTL_NS = 10_000_000_000L
    }
}

/** Model adapter boundary. Implementations must remain off the Reflex Shield frame callback. */
interface StorytellerEngine : AutoCloseable {
    val isAvailable: Boolean
    val unavailableReasonHindi: String

    /** Returns false when the request is rejected; the caller retains no processing obligation. */
    fun submitLatestFrame(frame: StorytellerFrame): Boolean

    /** Returns only a non-expired result. */
    fun latestResult(nowNs: Long = System.nanoTime()): StorytellerResult?

    override fun close()
}

/** Safe v1 implementation until a validated offline multimodal model is selected and benchmarked. */
class UnavailableStorytellerEngine(
    override val unavailableReasonHindi: String = "ऑफलाइन स्टोरीटेलर मॉडल अभी उपलब्ध नहीं है"
) : StorytellerEngine {
    override val isAvailable: Boolean = false

    override fun submitLatestFrame(frame: StorytellerFrame): Boolean = false

    override fun latestResult(nowNs: Long): StorytellerResult? = null

    override fun close() {
        // No Android logging here so the disabled adapter remains JVM-unit-test safe.
    }
}
