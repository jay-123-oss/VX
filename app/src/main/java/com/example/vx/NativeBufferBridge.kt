package com.example.vx

import java.nio.ByteBuffer

/**
 * JNI Bridge for the C++ Ring Buffer.
 * Handles passing raw YUV_420_888 planes from Kotlin to Native memory.
 */
object NativeBufferBridge {
    init {
        System.loadLibrary("native-lib")
    }

    /**
     * Initializes the native ring buffer with a fixed capacity.
     */
    external fun initRingBuffer(capacity: Int)

    /**
     * Pushes Y, U, V planes directly to native memory.
     */
    external fun pushFrame(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        width: Int,
        height: Int,
        yStride: Int,
        uvStride: Int,
        uvPixelStride: Int,
        timestamp: Long
    )

    /**
     * Locks the latest frame in native memory so it isn't overwritten during inference.
     */
    external fun lockLatestFrame()

    /**
     * Returns a Direct ByteBuffer mapped to the native Y plane of the locked frame.
     */
    external fun getLatestYBuffer(): ByteBuffer?

    /**
     * Unlocks the frame after inference is complete.
     */
    external fun unlockFrame()

    /**
     * Preprocesses the locked frame: YUV -> RGB, Resize, Normalize.
     * Populates the provided direct float buffer.
     */
    external fun preprocessFrame(
        outBuffer: ByteBuffer,
        targetWidth: Int,
        targetHeight: Int,
        normalize: Boolean,
    )
}
