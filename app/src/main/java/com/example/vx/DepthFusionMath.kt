package com.example.vx

import android.media.Image
import java.nio.ByteOrder

/** CPU depth utilities. A zero result always means unknown, never safe. */
object DepthFusionMath {
    fun getAverageDepth(normX: Float, normY: Float, depthImage: Image): Int {
        val width = depthImage.width
        val height = depthImage.height
        val plane = depthImage.planes.firstOrNull() ?: return 0
        val buffer = plane.buffer.duplicate().order(ByteOrder.nativeOrder())
        val centerX = (normX * width).toInt().coerceIn(0, width - 1)
        val centerY = (normY * height).toInt().coerceIn(0, height - 1)
        var sumDepth = 0L
        var count = 0

        for (dy in -2..2) {
            for (dx in -2..2) {
                val px = (centerX + dx).coerceIn(0, width - 1)
                val py = (centerY + dy).coerceIn(0, height - 1)
                val index = py * plane.rowStride + px * plane.pixelStride
                if (index >= 0 && index + 1 < buffer.limit()) {
                    val value = buffer.getShort(index).toInt() and 0xFFFF
                    if (value > 0) {
                        sumDepth += value
                        count++
                    }
                }
            }
        }
        return if (count == 0) 0 else (sumDepth / count).toInt()
    }

    /** Samples a narrow vertical ground profile at a normalized x coordinate. */
    fun sampleVerticalProfile(depthImage: Image, normX: Float, normalizedYs: FloatArray): IntArray {
        val result = IntArray(normalizedYs.size)
        normalizedYs.forEachIndexed { index, y ->
            result[index] = getAverageDepth(normX, y, depthImage)
        }
        return result
    }
}
