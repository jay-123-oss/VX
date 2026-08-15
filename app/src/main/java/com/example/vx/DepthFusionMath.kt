package com.example.vx

import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Expert Math for RGB to Depth mapping and 5x5 window averaging.
 */
object DepthFusionMath {

    /**
     * Maps YOLO center (0.0-1.0) to Depth Image coordinates and calculates average depth.
     */
    fun getAverageDepth(
        normX: Float,
        normY: Float,
        depthImage: Image
    ): Int {
        val width = depthImage.width
        val height = depthImage.height
        val buffer = depthImage.planes[0].buffer.apply { order(ByteOrder.nativeOrder()) }
        val rowStride = depthImage.planes[0].rowStride

        // Center pixel in depth map
        val centerX = (normX * width).toInt().coerceIn(0, width - 1)
        val centerY = (normY * height).toInt().coerceIn(0, height - 1)

        var sumDepth = 0
        var count = 0

        // 5x5 Window Averaging
        for (dy in -2..2) {
            for (dx in -2..2) {
                val px = (centerX + dx).coerceIn(0, width - 1)
                val py = (centerY + dy).coerceIn(0, height - 1)
                
                val index = (py * rowStride) + (px * 2) // 16-bit depth
                if (index + 1 < buffer.limit()) {
                    val depth = buffer.getShort(index).toInt() and 0xFFFF
                    if (depth > 0) { // Valid depth only
                        sumDepth += depth
                        count++
                    }
                }
            }
        }

        return if (count > 0) sumDepth / count else 0
    }

    /**
     * Fallback Heuristic: If depth fails, estimate by Bounding Box area.
     */
    fun estimateDistanceByArea(normW: Float, normH: Float): Int {
        val area = normW * normH
        return when {
            area > 0.45f -> 600    // ~0.6m
            area > 0.25f -> 1200   // ~1.2m
            area > 0.10f -> 2000   // ~2.0m
            else -> 3500           // Far
        }
    }
}
