package com.example.vx

import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Converts one camera Image to compressed bytes only when Search is explicitly requested.
 * Downsampling happens while copying YUV, so the full-resolution search buffer is never created
 * for thermal profiles. Reflex Shield never uses this encoder or its reduced image.
 */
object ImageFrameEncoder {
    fun toJpeg(
        image: Image,
        maxDimension: Int = 1280,
        quality: Int = 72
    ): ByteArray? {
        return runCatching {
            val sourceWidth = image.width
            val sourceHeight = image.height
            val safeMaxDimension = maxDimension.coerceAtLeast(320)
            val scale = max(1, (max(sourceWidth, sourceHeight) + safeMaxDimension - 1) / safeMaxDimension)
            val width = ((sourceWidth / scale).coerceAtLeast(2) / 2) * 2
            val height = ((sourceHeight / scale).coerceAtLeast(2) / 2) * 2
            val output = ByteArray(width * height * 3 / 2)
            copyLuma(image.planes[0], sourceWidth, sourceHeight, width, height, output)
            copyChroma(image.planes[1], image.planes[2], sourceWidth, sourceHeight, width, height, output, width * height)
            val yuv = android.graphics.YuvImage(output, ImageFormat.NV21, width, height, null)
            ByteArrayOutputStream((width * height / 2).coerceAtLeast(8_192)).use { stream ->
                check(yuv.compressToJpeg(Rect(0, 0, width, height), quality.coerceIn(45, 90), stream)) {
                    "YUV JPEG compression returned false"
                }
                stream.toByteArray()
            }
        }.onFailure { error ->
            Log.e("ImageFrameEncoder", "Camera frame encoding failed", error)
        }.getOrNull()
    }

    private fun copyLuma(
        plane: Image.Plane,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        output: ByteArray
    ) {
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var destination = 0
        for (row in 0 until targetHeight) {
            val sourceY = (row * sourceHeight / targetHeight).coerceIn(0, sourceHeight - 1)
            val rowStart = sourceY * rowStride
            for (column in 0 until targetWidth) {
                val sourceX = (column * sourceWidth / targetWidth).coerceIn(0, sourceWidth - 1)
                val source = rowStart + sourceX * pixelStride
                output[destination++] = if (source < buffer.limit()) buffer.get(source) else 0
            }
        }
    }

    private fun copyChroma(
        uPlane: Image.Plane,
        vPlane: Image.Plane,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        output: ByteArray,
        destinationStart: Int
    ) {
        val u = uPlane.buffer.duplicate()
        val v = vPlane.buffer.duplicate()
        val targetChromaWidth = targetWidth / 2
        val targetChromaHeight = targetHeight / 2
        var destination = destinationStart
        for (row in 0 until targetChromaHeight) {
            val sourceY = ((row * 2 * sourceHeight) / targetHeight).coerceIn(0, sourceHeight - 2)
            for (column in 0 until targetChromaWidth) {
                val sourceX = ((column * 2 * sourceWidth) / targetWidth).coerceIn(0, sourceWidth - 2)
                val uIndex = (sourceY / 2) * uPlane.rowStride + (sourceX / 2) * uPlane.pixelStride
                val vIndex = (sourceY / 2) * vPlane.rowStride + (sourceX / 2) * vPlane.pixelStride
                output[destination++] = if (vIndex < v.limit()) v.get(vIndex) else 0
                output[destination++] = if (uIndex < u.limit()) u.get(uIndex) else 0
            }
        }
    }
}
