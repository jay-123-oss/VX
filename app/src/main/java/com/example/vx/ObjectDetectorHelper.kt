package com.example.vx

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Offline TensorFlow Lite object detector for camera frames.
 *
 * The default model path is intentionally configurable so a production model can be supplied as
 * app/src/main/assets/models/yolov8n-int8.tflite without changing the integration code. The
 * parser supports the common YOLO [1, channels, anchors] / [1, anchors, channels] output and the
 * MobileNet SSD four-output contract.
 */
class ObjectDetectorHelper(
    private val context: Context,
    private val modelAssetPath: String = DEFAULT_MODEL_ASSET,
    private val labelsAssetPath: String = DEFAULT_LABELS_ASSET,
    private val inputSize: Int = DEFAULT_INPUT_SIZE,
    private val confidenceThreshold: Float = 0.35f,
    private val iouThreshold: Float = 0.45f,
    private val preferGpu: Boolean = true
) : Closeable {
    enum class Acceleration { GPU, NNAPI, CPU, UNAVAILABLE }

    data class Detection(
        val label: String,
        val classId: Int,
        val confidence: Float,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val centerX: Float get() = ((left + right) / 2f).coerceIn(0f, 1f)
        val centerY: Float get() = ((top + bottom) / 2f).coerceIn(0f, 1f)
        val width: Float get() = (right - left).coerceAtLeast(0f)
        val height: Float get() = (bottom - top).coerceAtLeast(0f)
    }

    private val lock = Any()
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var labels: List<String> = emptyList()
    @Volatile private var closed = false
    @Volatile var acceleration: Acceleration = Acceleration.UNAVAILABLE
        private set

    fun isReady(): Boolean {
        synchronized(lock) { return ensureInterpreterLocked() != null }
    }

    /** Run inference off the main thread. Returns an empty list when the optional asset is absent. */
    fun detect(bitmap: Bitmap): List<Detection> {
        if (closed || bitmap.width <= 0 || bitmap.height <= 0) return emptyList()
        val active = synchronized(lock) { ensureInterpreterLocked() } ?: return emptyList()
        val input = preprocess(bitmap, active.getInputTensor(0).dataType())
        return runCatching {
            if (active.outputTensorCount >= 4) {
                decodeSsd(active, input)
            } else {
                decodeYolo(active, input)
            }.let { nonMaximumSuppression(it) }
        }.onFailure { Log.e(TAG, "TFLite inference failed", it) }.getOrDefault(emptyList())
    }

    private fun ensureInterpreterLocked(): Interpreter? {
        if (closed) return null
        interpreter?.let { return it }
        labels = runCatching {
            context.assets.open(labelsAssetPath).bufferedReader().use { reader ->
                reader.readLines().map(String::trim).filter(String::isNotEmpty)
            }
        }.getOrDefault(emptyList())
        val modelBytes = runCatching { context.assets.open(modelAssetPath).use { it.readBytes() } }
            .onFailure { Log.w(TAG, "TFLite model asset unavailable: $modelAssetPath") }
            .getOrNull() ?: return null
        val model = ByteBuffer.allocateDirect(modelBytes.size)
            .order(ByteOrder.nativeOrder())
            .apply {
                put(modelBytes)
                rewind()
            }

        var options = Interpreter.Options().apply {
            setNumThreads(2)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setUseNNAPI(true)
        }
        if (preferGpu) {
            runCatching {
                GpuDelegate().also { delegate ->
                    gpuDelegate = delegate
                    options = Interpreter.Options().apply {
                        addDelegate(delegate)
                        setNumThreads(2)
                    }
                    interpreter = Interpreter(model.duplicate().order(ByteOrder.nativeOrder()), options)
                    acceleration = Acceleration.GPU
                }
            }.onFailure { error ->
                gpuDelegate?.close()
                gpuDelegate = null
                Log.w(TAG, "GPU delegate unavailable; trying NNAPI/CPU", error)
            }
        }
        if (interpreter == null) {
            runCatching {
                interpreter = Interpreter(model.duplicate().order(ByteOrder.nativeOrder()), options)
                acceleration = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Acceleration.NNAPI
                } else {
                    Acceleration.CPU
                }
            }.onFailure { error ->
                Log.w(TAG, "NNAPI interpreter unavailable; trying CPU", error)
                interpreter = null
            }
        }
        if (interpreter == null) {
            runCatching {
                interpreter = Interpreter(model.duplicate().order(ByteOrder.nativeOrder()), Interpreter.Options().setNumThreads(2))
                acceleration = Acceleration.CPU
            }.onFailure { error ->
                Log.e(TAG, "CPU TFLite interpreter unavailable", error)
                acceleration = Acceleration.UNAVAILABLE
            }
        }
        return interpreter
    }

    private fun preprocess(bitmap: Bitmap, dataType: DataType): ByteBuffer {
        val scaled = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val bytesPerChannel = if (dataType == DataType.FLOAT32) 4 else 1
        val buffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * bytesPerChannel).order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        pixels.forEach { pixel ->
            val channels = intArrayOf((pixel shr 16) and 0xFF, (pixel shr 8) and 0xFF, pixel and 0xFF)
            channels.forEach { channel ->
                if (dataType == DataType.FLOAT32) {
                    buffer.putFloat(channel / 255f)
                } else if (dataType == DataType.INT8) {
                    buffer.put((channel - 128).toByte())
                } else {
                    buffer.put(channel.toByte())
                }
            }
        }
        buffer.rewind()
        if (scaled !== bitmap) scaled.recycle()
        return buffer
    }

    private fun decodeYolo(active: Interpreter, input: ByteBuffer): List<Detection> {
        val tensor = active.getOutputTensor(0)
        val shape = tensor.shape()
        if (shape.size != 3 || shape[0] != 1) return emptyList()
        val output = Array(1) { Array(shape[1]) { FloatArray(shape[2]) } }
        active.run(input, output)
        val channelsFirst = shape[1] <= 128 && shape[2] > shape[1]
        val detections = mutableListOf<Detection>()
        val anchorCount = if (channelsFirst) shape[2] else shape[1]
        val channelCount = if (channelsFirst) shape[1] else shape[2]
        if (channelCount < 5) return emptyList()
        for (anchor in 0 until anchorCount) {
            val cx = value(output, channelsFirst, 0, anchor)
            val cy = value(output, channelsFirst, 1, anchor)
            val width = value(output, channelsFirst, 2, anchor)
            val height = value(output, channelsFirst, 3, anchor)
            var bestClass = -1
            var bestScore = 0f
            for (channel in 4 until channelCount) {
                val score = value(output, channelsFirst, channel, anchor)
                if (score > bestScore) {
                    bestScore = score
                    bestClass = channel - 4
                }
            }
            if (bestClass >= 0 && bestScore >= confidenceThreshold) {
                val (left, top, right, bottom) = boxFromCenter(cx, cy, width, height)
                detections += Detection(labelFor(bestClass), bestClass, bestScore, left, top, right, bottom)
            }
        }
        return detections
    }

    private fun decodeSsd(active: Interpreter, input: ByteBuffer): List<Detection> {
        val locationsShape = active.getOutputTensor(0).shape()
        val classesShape = active.getOutputTensor(1).shape()
        val scoresShape = active.getOutputTensor(2).shape()
        if (locationsShape.size != 3 || locationsShape[2] < 4) return emptyList()
        val count = locationsShape[1]
        val locations = Array(1) { Array(count) { FloatArray(locationsShape[2]) } }
        val classes = Array(1) { FloatArray(max(count, classesShape.lastOrNull() ?: count)) }
        val scores = Array(1) { FloatArray(max(count, scoresShape.lastOrNull() ?: count)) }
        val numDetections = FloatArray(1)
        val outputs = hashMapOf<Int, Any>(0 to locations, 1 to classes, 2 to scores, 3 to numDetections)
        active.runForMultipleInputsOutputs(arrayOf(input), outputs)
        val result = mutableListOf<Detection>()
        val limit = minOf(count, numDetections.firstOrNull()?.toInt() ?: count)
        for (index in 0 until limit) {
            val score = scores[0].getOrElse(index) { 0f }
            if (score < confidenceThreshold) continue
            val box = locations[0][index]
            val top = box[0].coerceIn(0f, 1f)
            val left = box[1].coerceIn(0f, 1f)
            val bottom = box[2].coerceIn(0f, 1f)
            val right = box[3].coerceIn(0f, 1f)
            val classId = classes[0].getOrElse(index) { 0f }.toInt()
            result += Detection(labelFor(classId), classId, score, left, top, right, bottom)
        }
        return result
    }

    private fun value(output: Array<Array<FloatArray>>, channelsFirst: Boolean, channel: Int, anchor: Int): Float =
        if (channelsFirst) output[0][channel][anchor] else output[0][anchor][channel]

    private fun boxFromCenter(cx: Float, cy: Float, width: Float, height: Float): FloatArray {
        val normalizedCx = if (cx > 1f) cx / inputSize else cx
        val normalizedCy = if (cy > 1f) cy / inputSize else cy
        val normalizedWidth = if (width > 1f) width / inputSize else width
        val normalizedHeight = if (height > 1f) height / inputSize else height
        return floatArrayOf(
            (normalizedCx - normalizedWidth / 2f).coerceIn(0f, 1f),
            (normalizedCy - normalizedHeight / 2f).coerceIn(0f, 1f),
            (normalizedCx + normalizedWidth / 2f).coerceIn(0f, 1f),
            (normalizedCy + normalizedHeight / 2f).coerceIn(0f, 1f)
        )
    }

    private fun labelFor(classId: Int): String = labels.getOrNull(classId) ?: "class_$classId"

    private fun nonMaximumSuppression(input: List<Detection>): List<Detection> = input
        .sortedByDescending { it.confidence }
        .fold(mutableListOf<Detection>()) { kept: MutableList<Detection>, candidate: Detection ->
            if (kept.none { iou(it, candidate) > iouThreshold || it.classId != candidate.classId }) kept += candidate
            kept
        }
        .take(MAX_RESULTS)

    private fun iou(a: Detection, b: Detection): Float {
        val intersection = ((minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f) *
            (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f))
        val union = a.width * a.height + b.width * b.height - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            interpreter?.close()
            interpreter = null
            gpuDelegate?.close()
            gpuDelegate = null
            acceleration = Acceleration.UNAVAILABLE
        }
    }

    companion object {
        private const val TAG = "ObjectDetectorHelper"
        const val DEFAULT_MODEL_ASSET = "models/yolov8n-int8.tflite"
        const val DEFAULT_LABELS_ASSET = "models/coco_classes.txt"
        const val DEFAULT_INPUT_SIZE = 320
        private const val MAX_RESULTS = 12
    }
}

private operator fun FloatArray.component1(): Float = this[0]
private operator fun FloatArray.component2(): Float = this[1]
private operator fun FloatArray.component3(): Float = this[2]
private operator fun FloatArray.component4(): Float = this[3]
