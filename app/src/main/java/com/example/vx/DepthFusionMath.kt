package com.example.vx

import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** CPU depth utilities. A zero result always means unknown, never safe. */
object DepthFusionMath {
    /** One sampler owns one read-only view of a depth image for the lifetime of that image. */
    class Sampler(depthImage: Image) {
        private val width = depthImage.width
        private val height = depthImage.height
        private val plane = depthImage.planes.firstOrNull()
        private val buffer: ByteBuffer? = plane?.buffer?.duplicate()?.order(ByteOrder.nativeOrder())

        fun getAverageDepth(normX: Float, normY: Float): Int {
            val activePlane = plane ?: return 0
            val activeBuffer = buffer ?: return 0
            val centerX = (normX * width).toInt().coerceIn(0, width - 1)
            val centerY = (normY * height).toInt().coerceIn(0, height - 1)
            var sumDepth = 0L
            var count = 0
            for (dy in -2..2) {
                for (dx in -2..2) {
                    val px = (centerX + dx).coerceIn(0, width - 1)
                    val py = (centerY + dy).coerceIn(0, height - 1)
                    val index = py * activePlane.rowStride + px * activePlane.pixelStride
                    if (index >= 0 && index + 1 < activeBuffer.limit()) {
                        val value = activeBuffer.getShort(index).toInt() and 0xFFFF
                        if (value > 0) {
                            sumDepth += value
                            count++
                        }
                    }
                }
            }
            return if (count == 0) 0 else (sumDepth / count).toInt()
        }

        fun fillVerticalProfile(normX: Float, normalizedYs: FloatArray, output: IntArray) {
            val limit = minOf(normalizedYs.size, output.size)
            for (index in 0 until limit) {
                output[index] = getAverageDepth(normX, normalizedYs[index])
            }
            for (index in limit until output.size) output[index] = 0
        }
    }

    fun getAverageDepth(normX: Float, normY: Float, depthImage: Image): Int =
        Sampler(depthImage).getAverageDepth(normX, normY)

    /** Legacy helper retained for tests and non-hot callers. */
    fun sampleVerticalProfile(depthImage: Image, normX: Float, normalizedYs: FloatArray): IntArray {
        val output = IntArray(normalizedYs.size)
        Sampler(depthImage).fillVerticalProfile(normX, normalizedYs, output)
        return output
    }
}
