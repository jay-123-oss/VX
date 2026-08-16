package com.example.vx

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.extensions.OrtxPackage
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.util.Collections
import java.util.EnumSet

/**
 * Offline YOLOv8 detector with CPU baseline and optional strict NNAPI/NPU acceleration.
 * NNAPI is accepted only if a session can be created with CPU fallback disabled; otherwise the
 * detector falls back to ORT CPU kernels so unsupported operators cannot silently change the path.
 */
class OnnxObjectDetector(
    private val context: Context,
    private val preferHardware: Boolean = true
) : AutoCloseable {
    enum class Provider { CPU, NNAPI }

    data class ProviderBenchmark(
        val provider: Provider,
        val available: Boolean,
        val averageMs: Double,
        val failedRuns: Int,
        val message: String
    )

    data class Detection(
        val label: String,
        val classId: Int,
        val confidence: Float,
        val centerX: Float,
        val centerY: Float,
        val width: Float,
        val height: Float
    ) {
        val left: Float get() = (centerX - width / 2f).coerceIn(0f, 1f)
        val top: Float get() = (centerY - height / 2f).coerceIn(0f, 1f)
        val right: Float get() = (centerX + width / 2f).coerceIn(0f, 1f)
        val bottom: Float get() = (centerY + height / 2f).coerceIn(0f, 1f)
    }

    private val lock = Any()
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var labels: List<String> = emptyList()
    @Volatile private var activeProvider: Provider = Provider.CPU
    @Volatile private var closed = false

    fun isAvailable(): Boolean = synchronized(lock) { ensureSessionLocked() != null }

    fun providerName(): String = activeProvider.name

    /**
     * Runs only when explicitly requested by the user. The input is a JPEG/PNG byte array from
     * the current camera frame; no network or cloud path exists.
     */
    fun detect(imageBytes: ByteArray, requestedLabelHindi: String? = null): List<Detection> {
        if (imageBytes.isEmpty() || closed) return emptyList()
        val activeEnv: OrtEnvironment
        val activeSession: OrtSession
        synchronized(lock) {
            val ready = ensureSessionLocked() ?: return emptyList()
            activeEnv = env ?: return emptyList()
            activeSession = ready
        }

        val startNs = SystemClock.elapsedRealtimeNanos()
        return runCatching {
            val rows = runForRows(activeEnv, activeSession, imageBytes)
            val dimensions = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, dimensions)
            val imageWidth = dimensions.outWidth.coerceAtLeast(1).toFloat()
            val imageHeight = dimensions.outHeight.coerceAtLeast(1).toFloat()
            rows.mapNotNull { row -> parseRow(row, imageWidth, imageHeight, requestedLabelHindi) }
                .sortedByDescending { it.confidence }
                .take(MAX_RESULTS)
        }.onFailure { error ->
            Log.e(TAG, "Offline ONNX inference failed provider=${activeProvider.name}", error)
        }.also {
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000.0
            Log.i(TAG, "Inference provider=${activeProvider.name} latencyMs=${"%.1f".format(elapsedMs)}")
        }.getOrDefault(emptyList())
    }

    /**
     * Explicit developer benchmark. Run this off the main thread with the same camera image for
     * CPU and NNAPI. It does not change the active session or the app's safety loop.
     */
    fun benchmarkProviders(imageBytes: ByteArray, runs: Int = 3): List<ProviderBenchmark> {
        if (imageBytes.isEmpty() || closed) return emptyList()
        val runtime = synchronized(lock) { env ?: OrtEnvironment.getEnvironment().also { env = it } }
        return Provider.values().map { provider ->
            var failed = 0
            var completed = 0
            val temporarySession = runCatching { createSession(runtime, provider) }.getOrElse { error ->
                return@map ProviderBenchmark(provider, false, 0.0, 1, error.message ?: "provider unavailable")
            }
            val startNs = SystemClock.elapsedRealtimeNanos()
            temporarySession.use { candidate ->
                repeat(runs.coerceIn(1, 5)) {
                    runCatching { runForRows(runtime, candidate, imageBytes) }
                        .onSuccess { completed++ }
                        .onFailure { failed++ }
                }
            }
            val average = if (completed == 0) 0.0 else {
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000.0
                elapsedMs / completed
            }
            ProviderBenchmark(
                provider = provider,
                available = completed > 0,
                averageMs = average,
                failedRuns = failed,
                message = if (completed > 0) "benchmark complete" else "all runs failed"
            )
        }.also { results ->
            results.forEach { result ->
                Log.i(TAG, "Benchmark provider=${result.provider} available=${result.available} avgMs=${result.averageMs} failed=${result.failedRuns}")
            }
        }
    }

    private fun runForRows(
        runtime: OrtEnvironment,
        activeSession: OrtSession,
        imageBytes: ByteArray
    ): Array<FloatArray> {
        val input = OnnxTensor.createTensor(
            runtime,
            ByteBuffer.wrap(imageBytes),
            longArrayOf(imageBytes.size.toLong()),
            OnnxJavaType.UINT8
        )
        input.use {
            activeSession.run(
                Collections.singletonMap("image", input),
                setOf("scaled_box_out_next")
            ).use { result ->
                val value = result.get(0).value
                val rows = when (value) {
                    is Array<*> -> value.mapNotNull { it as? FloatArray }.toTypedArray()
                    is FloatArray -> value
                        .asList()
                        .chunked(OUTPUT_COLUMNS)
                        .filter { it.size == OUTPUT_COLUMNS }
                        .map { it.toFloatArray() }
                        .toTypedArray()
                    else -> emptyArray()
                }
                val invalidRows = rows.count { it.size != OUTPUT_COLUMNS }
                if (invalidRows > 0) {
                    Log.e(TAG, "Unexpected detection output rows=${rows.size} invalidRows=$invalidRows expectedColumns=$OUTPUT_COLUMNS")
                    return emptyArray()
                }
                Log.d(TAG, "Detection output rows=${rows.size} columns=$OUTPUT_COLUMNS")
                return rows
            }
        }
    }

    private fun parseRow(
        row: FloatArray,
        imageWidth: Float,
        imageHeight: Float,
        requestedLabelHindi: String?
    ): Detection? {
        if (row.size < 6) return null
        val score = row[4]
        val classId = row[5].toInt()
        if (!score.isFinite() || score < CONFIDENCE_THRESHOLD || classId !in labels.indices) return null
        val label = labels[classId]
        if (!requestedLabelHindi.isNullOrBlank() && !matchesPrompt(label, requestedLabelHindi)) return null
        return Detection(
            label = label,
            classId = classId,
            confidence = score,
            centerX = row[0] / imageWidth,
            centerY = row[1] / imageHeight,
            width = row[2] / imageWidth,
            height = row[3] / imageHeight
        )
    }

    private fun matchesPrompt(label: String, prompt: String): Boolean {
        val normalizedPrompt = prompt.trim().lowercase()
        val aliases = when (normalizedPrompt) {
            "पानी", "बोतल", "water", "bottle" -> setOf("bottle", "cup", "wine glass")
            "व्यक्ति", "आदमी", "महिला", "इंसान", "person", "insaan" -> setOf("person")
            "साइकिल", "cycle", "bicycle" -> setOf("bicycle")
            "बाइक", "मोटरसाइकिल", "bike", "motorbike" -> setOf("motorbike")
            "कुर्सी", "chair" -> setOf("chair")
            "कार", "गाड़ी", "car" -> setOf("car", "truck", "bus")
            "बस", "bus" -> setOf("bus")
            "ट्रेन", "train" -> setOf("train")
            "कुत्ता", "dog" -> setOf("dog")
            "बिल्ली", "cat" -> setOf("cat")
            "पक्षी", "चिड़िया", "bird" -> setOf("bird")
            "छाता", "umbrella" -> setOf("umbrella")
            "बैग", "backpack", "bag" -> setOf("backpack", "handbag", "suitcase")
            "मोबाइल", "मोबाइल फोन", "cell phone", "phone" -> setOf("cell phone")
            "किताब", "book" -> setOf("book")
            "मेज़", "टेबल", "table", "dining table" -> setOf("diningtable")
            "टीवी", "tv", "television" -> setOf("tvmonitor")
            else -> setOf(normalizedPrompt)
        }
        return aliases.any { label.lowercase().contains(it) }
    }

    private fun ensureSessionLocked(): OrtSession? {
        if (closed) return null
        if (session != null) return session
        val runtime = env ?: OrtEnvironment.getEnvironment().also { env = it }
        labels = runCatching {
            context.assets.open(LABEL_ASSET).bufferedReader().use { it.readLines() }
        }.getOrDefault(emptyList())
        if (preferHardware && Build.VERSION.SDK_INT >= 29) {
            runCatching { createSession(runtime, Provider.NNAPI) }
                .onSuccess {
                    activeProvider = Provider.NNAPI
                    session = it
                    Log.i(TAG, "NNAPI/NPU provider selected with CPU fallback disabled")
                }
                .onFailure { error ->
                    Log.w(TAG, "NNAPI unavailable; using CPU baseline: ${error.message}")
                }
            if (session != null) return session
        }
        return runCatching { createSession(runtime, Provider.CPU) }
            .onSuccess {
                activeProvider = Provider.CPU
                session = it
                Log.i(TAG, "CPU provider selected")
            }
            .onFailure { error -> Log.e(TAG, "Offline ONNX model unavailable", error) }
            .getOrNull()
    }

    private fun createSession(runtime: OrtEnvironment, provider: Provider): OrtSession {
        val options = OrtSession.SessionOptions()
        options.registerCustomOpLibrary(OrtxPackage.getLibraryPath())
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        if (provider == Provider.NNAPI) {
            options.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))
        }
        val model = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        return runtime.createSession(model, options)
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            session?.close()
            session = null
            env = null
        }
    }

    companion object {
        private const val TAG = "OnnxObjectDetector"
        private const val MODEL_ASSET = "models/yolov8n_with_pre_post_processing.onnx"
        private const val LABEL_ASSET = "models/coco_classes.txt"
        private const val CONFIDENCE_THRESHOLD = 0.35f
        private const val MAX_RESULTS = 8
        // The bundled asset is patched so its built-in NMS emits [N, 6]: cx, cy, w, h, score, class.
        private const val OUTPUT_COLUMNS = 6
    }
}
