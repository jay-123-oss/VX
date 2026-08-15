package com.example.vx

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * LiteRT-LM adapter. It is intentionally not constructed by MainActivity until a validated model
 * path is provided. Reflex Shield never calls this class from its frame callback.
 */
class LiteRtLmStorytellerEngine(
    private val context: Context,
    private val modelPath: String,
    private val mainBackend: Backend = Backend.CPU(),
    private val visionBackend: Backend = Backend.CPU(),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vx-storyteller").apply { priority = Thread.NORM_PRIORITY - 2 }
    }
) : StorytellerEngine {
    private val latestFrame = LatestStorytellerFrame()
    private val inferenceInFlight = AtomicBoolean(false)
    private val lock = Any()
    @Volatile private var closed = false
    @Volatile private var initialized = false
    @Volatile private var result: StorytellerResult? = null
    @Volatile private var failureReason = "ऑफलाइन स्टोरीटेलर मॉडल अभी तैयार नहीं है"
    private var engine: Engine? = null
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null

    override val isAvailable: Boolean
        get() = initialized && !closed

    override val unavailableReasonHindi: String
        get() = failureReason

    /** Must be called from a background thread; model initialization can take seconds. */
    fun initializeBlocking(): Boolean {
        synchronized(lock) {
            if (closed || initialized) return initialized
            if (modelPath.isBlank()) {
                failureReason = "स्टोरीटेलर मॉडल पथ उपलब्ध नहीं है"
                return false
            }
            return runCatching {
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = mainBackend,
                    visionBackend = visionBackend,
                    maxNumImages = 1,
                    maxNumTokens = MAX_OUTPUT_TOKENS
                )
                val createdEngine = Engine(config)
                createdEngine.initialize()
                engine = createdEngine
                conversation = createdEngine.createConversation()
                initialized = true
                failureReason = ""
                true
            }.getOrElse { error ->
                failureReason = "स्टोरीटेलर शुरू नहीं हो सका: ${error.message ?: "अज्ञात त्रुटि"}"
                closeResourcesLocked()
                false
            }
        }
    }

    /** Starts initialization without blocking the caller, typically from Enhanced-tier setup. */
    fun initializeAsync(onComplete: ((Boolean) -> Unit)? = null) {
        runCatching {
            executor.execute {
                val ready = initializeBlocking()
                onComplete?.invoke(ready)
            }
        }.onFailure { error ->
            if (error !is RejectedExecutionException) throw error
        }
    }

    override fun submitLatestFrame(frame: StorytellerFrame): Boolean {
        if (!isAvailable || closed || !latestFrame.offer(frame.jpegBytes, frame.capturedAtNs)) return false
        if (inferenceInFlight.compareAndSet(false, true)) {
            try {
                executor.execute {
                    try {
                        val pending = latestFrame.takeLatest() ?: return@execute
                        inferOnce(pending)
                    } finally {
                        inferenceInFlight.set(false)
                        if (!closed && latestFrame.hasFreshPending()) {
                            submitPendingWork()
                        }
                    }
                }
            } catch (_: RejectedExecutionException) {
                inferenceInFlight.set(false)
                return false
            }
        }
        return true
    }

    private fun submitPendingWork() {
        if (!inferenceInFlight.compareAndSet(false, true)) return
        try {
            executor.execute {
                try {
                    val pending = latestFrame.takeLatest() ?: return@execute
                    inferOnce(pending)
                } finally {
                    inferenceInFlight.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            inferenceInFlight.set(false)
        }
    }

    /** Synchronous developer benchmark primitive; call only off the main thread. */
    fun inferOnce(frame: StorytellerFrame): StorytellerResult? {
        val activeConversation = synchronized(lock) { conversation } ?: return null
        if (closed || !frame.isFreshAt(System.nanoTime())) return null
        return runCatching {
            val response: Message = activeConversation.sendMessage(
                Contents.of(
                    Content.ImageBytes(frame.jpegBytes),
                    Content.Text(SAFETY_PROMPT)
                )
            )
            val responseText = response.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString(" ") { it.text.orEmpty() }
            parseResult(responseText, frame)
        }.onFailure { error ->
            failureReason = "स्टोरीटेलर विश्लेषण विफल हुआ: ${error.message ?: "अज्ञात त्रुटि"}"
        }.getOrNull()?.also { parsed ->
            result = parsed
        }
    }

    override fun latestResult(nowNs: Long): StorytellerResult? =
        result?.takeIf { it.isFreshAt(nowNs) }

    private fun parseResult(rawText: String, frame: StorytellerFrame): StorytellerResult {
        val json = extractJson(rawText)
        val hazard = parseHazard(json?.optString("hazard", "unknown") ?: "unknown")
        val severity = parseSeverity(json?.optString("severity", "unknown") ?: "unknown")
        val region = parseRegion(json?.optString("region", "unknown") ?: "unknown")
        val confidence = json?.optDouble("confidence", 0.0)?.toFloat()?.coerceIn(0f, 1f) ?: 0f
        val evidence = json?.optString("evidence", "")?.take(MAX_EVIDENCE_CHARS).orEmpty()
        return StorytellerResult(
            schemaVersion = json?.optInt("schema_version", StorytellerResult.SCHEMA_VERSION)
                ?: StorytellerResult.SCHEMA_VERSION,
            hazard = hazard,
            severity = severity,
            region = region,
            confidence = confidence,
            evidence = evidence,
            capturedAtNs = frame.capturedAtNs,
            expiresAtNs = frame.expiresAtNs
        )
    }

    private fun extractJson(rawText: String): JSONObject? {
        val start = rawText.indexOf('{')
        val end = rawText.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(rawText.substring(start, end + 1)) }.getOrNull()
    }

    private fun parseHazard(value: String): StorytellerHazard = when (value.lowercase(Locale.US)) {
        "pothole" -> StorytellerHazard.POTHOLE
        "downward_stairs" -> StorytellerHazard.DOWNWARD_STAIRS
        "uneven_ground" -> StorytellerHazard.UNEVEN_GROUND
        "obstacle" -> StorytellerHazard.OBSTACLE
        "crowd" -> StorytellerHazard.CROWD
        "none" -> StorytellerHazard.NONE
        else -> StorytellerHazard.UNKNOWN
    }

    private fun parseSeverity(value: String): StorytellerSeverity = when (value.lowercase(Locale.US)) {
        "caution" -> StorytellerSeverity.CAUTION
        "warning" -> StorytellerSeverity.WARNING
        "emergency" -> StorytellerSeverity.EMERGENCY
        else -> StorytellerSeverity.UNKNOWN
    }

    private fun parseRegion(value: String): StorytellerRegion = when (value.lowercase(Locale.US)) {
        "left" -> StorytellerRegion.LEFT
        "center" -> StorytellerRegion.CENTER
        "right" -> StorytellerRegion.RIGHT
        else -> StorytellerRegion.UNKNOWN
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            latestFrame.close()
            closeResourcesLocked()
        }
        executor.shutdownNow()
    }

    private fun closeResourcesLocked() {
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        conversation = null
        engine = null
        initialized = false
    }

    companion object {
        private const val MAX_OUTPUT_TOKENS = 128
        private const val MAX_EVIDENCE_CHARS = StorytellerResult.MAX_EVIDENCE_CHARS
        private const val SAFETY_PROMPT = """
            Analyze this camera image only as a supplementary visual safety cross-check.
            Return JSON only with exactly these keys: schema_version, hazard, severity, region,
            confidence, evidence. Use hazard values pothole, downward_stairs, uneven_ground,
            obstacle, crowd, none, unknown. Use severity caution, warning, emergency, unknown.
            Use region left, center, right, unknown. Never claim the path is safe. If uncertain,
            use unknown with confidence 0. Keep evidence short.
        """
    }
}

enum class StorytellerBenchmarkBackend { CPU, GPU, NPU }

data class StorytellerBenchmarkResult(
    val backend: StorytellerBenchmarkBackend,
    val available: Boolean,
    val averageMs: Double,
    val failedRuns: Int,
    val message: String
)

/**
 * Enhanced-tier developer benchmark. It requires an externally supplied local model path and must
 * be run while stationary. It never changes MainActivity's active disabled fallback.
 */
class EnhancedStorytellerBenchmark(private val context: Context) {
    fun run(
        modelPath: String,
        frame: StorytellerFrame,
        runs: Int = 1
    ): List<StorytellerBenchmarkResult> {
        val safeRuns = runs.coerceIn(1, 3)
        return StorytellerBenchmarkBackend.entries.map { backendKind ->
            var completed = 0
            var failed = 0
            var startNs = 0L
            val engine = runCatching {
                LiteRtLmStorytellerEngine(
                    context = context,
                    modelPath = modelPath,
                    mainBackend = backend(backendKind),
                    visionBackend = backend(backendKind)
                )
            }.getOrElse { error ->
                return@map StorytellerBenchmarkResult(
                    backendKind,
                    false,
                    0.0,
                    1,
                    error.message ?: "backend unavailable"
                )
            }
            engine.use { candidate ->
                if (!candidate.initializeBlocking()) {
                    return@map StorytellerBenchmarkResult(
                        backendKind,
                        false,
                        0.0,
                        safeRuns,
                        candidate.unavailableReasonHindi
                    )
                }
                startNs = System.nanoTime()
                repeat(safeRuns) {
                    if (candidate.inferOnce(frame) != null) completed++ else failed++
                }
            }
            val averageMs = if (completed == 0) 0.0 else
                (System.nanoTime() - startNs) / 1_000_000.0 / completed
            StorytellerBenchmarkResult(
                backend = backendKind,
                available = completed > 0,
                averageMs = averageMs,
                failedRuns = failed,
                message = if (completed > 0) "benchmark complete" else "all runs failed"
            )
        }
    }

    private fun backend(kind: StorytellerBenchmarkBackend): Backend = when (kind) {
        StorytellerBenchmarkBackend.CPU -> Backend.CPU()
        StorytellerBenchmarkBackend.GPU -> Backend.GPU()
        StorytellerBenchmarkBackend.NPU -> Backend.NPU(context.applicationInfo.nativeLibraryDir)
    }
}
