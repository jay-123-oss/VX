package com.example.vx

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.extensions.OrtxPackage
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import java.nio.ByteBuffer
import java.util.Collections

/**
 * Offline YOLOv8 detector backed by the official ONNX Runtime pre/post-processing model.
 * The model is loaded lazily so app startup remains safe on devices without enough memory.
 * This model uses a fixed COCO vocabulary; open-vocabulary prompts require a YOLO-World export.
 */
class OnnxObjectDetector(private val context: Context) : AutoCloseable {
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
    @Volatile private var closed = false

    fun isAvailable(): Boolean = synchronized(lock) {
        ensureSessionLocked() != null
    }

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

        return runCatching {
            val input = OnnxTensor.createTensor(
                activeEnv,
                ByteBuffer.wrap(imageBytes),
                longArrayOf(imageBytes.size.toLong()),
                OnnxJavaType.UINT8
            )
            input.use {
                activeSession.run(
                    Collections.singletonMap("image", input),
                    setOf("scaled_box_out_next")
                ).use { result ->
                    val raw = result.get(0).value
                    @Suppress("UNCHECKED_CAST")
                    val rows = raw as? Array<FloatArray> ?: return@use emptyList()
                    val dimensions = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, dimensions)
                    val imageWidth = dimensions.outWidth.coerceAtLeast(1).toFloat()
                    val imageHeight = dimensions.outHeight.coerceAtLeast(1).toFloat()
                    rows.mapNotNull { row -> parseRow(row, imageWidth, imageHeight, requestedLabelHindi) }
                        .sortedByDescending { it.confidence }
                        .take(MAX_RESULTS)
                }
            }
        }.onFailure { error ->
            Log.e(TAG, "Offline ONNX inference failed", error)
        }.getOrDefault(emptyList())
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
            "व्यक्ति", "आदमी", "महिला", "person", "insaan" -> setOf("person")
            "कुर्सी", "chair" -> setOf("chair")
            "कार", "गाड़ी", "car" -> setOf("car", "truck", "bus")
            else -> setOf(normalizedPrompt)
        }
        return aliases.any { label.lowercase().contains(it) }
    }

    private fun ensureSessionLocked(): OrtSession? {
        if (closed) return null
        if (session != null) return session
        return runCatching {
            val runtime = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            options.registerCustomOpLibrary(OrtxPackage.getLibraryPath())
            options.setIntraOpNumThreads(2)
            val model = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            labels = context.assets.open(LABEL_ASSET).bufferedReader().use { it.readLines() }
            env = runtime
            runtime.createSession(model, options).also { session = it }
        }.onFailure { error ->
            Log.e(TAG, "Offline ONNX model unavailable", error)
        }.getOrNull()
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            session?.close()
            session = null
            // OrtEnvironment is process-shared; do not close the global environment here.
            env = null
        }
    }

    companion object {
        private const val TAG = "OnnxObjectDetector"
        private const val MODEL_ASSET = "models/yolov8n_with_pre_post_processing.onnx"
        private const val LABEL_ASSET = "models/coco_classes.txt"
        private const val CONFIDENCE_THRESHOLD = 0.35f
        private const val MAX_RESULTS = 8
    }
}
