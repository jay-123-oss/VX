package com.example.vx

import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import android.util.Log
import java.io.ByteArrayOutputStream

/** Converts one camera Image to compressed bytes only when Search is explicitly requested. */
object ImageFrameEncoder {
    fun toJpeg(image: Image, quality: Int = 72): ByteArray? {
        return runCatching {
            val width = image.width
            val height = image.height
            val output = ByteArray(width * height * 3 / 2)
            copyLuma(image.planes[0], width, height, output)
            copyChroma(image.planes[1], image.planes[2], width, height, output, width * height)
            val yuv = android.graphics.YuvImage(output, ImageFormat.NV21, width, height, null)
            ByteArrayOutputStream().use { stream ->
                check(yuv.compressToJpeg(Rect(0, 0, width, height), quality, stream)) {
                    "YUV JPEG compression returned false"
                }
                stream.toByteArray()
            }
        }.onFailure { error ->
            Log.e("ImageFrameEncoder", "Camera frame encoding failed", error)
        }.getOrNull()
    }

    private fun copyLuma(plane: Image.Plane, width: Int, height: Int, output: ByteArray) {
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var destination = 0
        for (row in 0 until height) {
            val rowStart = row * rowStride
            for (column in 0 until width) {
                val source = rowStart + column * pixelStride
                output[destination++] = if (source < buffer.limit()) buffer.get(source) else 0
            }
        }
    }

    private fun copyChroma(
        uPlane: Image.Plane,
        vPlane: Image.Plane,
        width: Int,
        height: Int,
        output: ByteArray,
        destinationStart: Int
    ) {
        val u = uPlane.buffer.duplicate()
        val v = vPlane.buffer.duplicate()
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        var destination = destinationStart
        for (row in 0 until chromaHeight) {
            for (column in 0 until chromaWidth) {
                val uIndex = row * uPlane.rowStride + column * uPlane.pixelStride
                val vIndex = row * vPlane.rowStride + column * vPlane.pixelStride
                output[destination++] = if (vIndex < v.limit()) v.get(vIndex) else 0
                output[destination++] = if (uIndex < u.limit()) u.get(uIndex) else 0
            }
        }
    }
}
