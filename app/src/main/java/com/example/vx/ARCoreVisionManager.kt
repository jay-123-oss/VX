package com.example.vx

import android.content.Context
import android.media.Image
import android.util.Log
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages ARCore Session and Strict Frame Throttling.
 */
class ARCoreVisionManager(private val context: Context) {
    private var session: Session? = null
    private val isProcessing = AtomicBoolean(false)
    private var lastProcessTime = 0L
    private val processInterval = 100L // ~10 FPS target

    fun resume(): Session? {
        if (session == null) {
            session = Session(context)
            val config = Config(session)
            if (session!!.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                config.depthMode = Config.DepthMode.AUTOMATIC
                Log.i("ARCoreVision", "Depth Mode Enabled")
            }
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            session!!.configure(config)
        }
        session?.resume()
        return session
    }

    fun pause() {
        session?.pause()
    }

    fun onFrame(frame: Frame, callback: (Image, Image?) -> Unit) {
        if (frame.camera.trackingState != TrackingState.TRACKING) return

        val now = System.currentTimeMillis()
        if (isProcessing.get() || (now - lastProcessTime < processInterval)) {
            // Drop frame to prevent OOM
            return
        }

        try {
            val cameraImage = frame.acquireCameraImage()
            val depthImage = try { frame.acquireDepthImage16Bits() } catch (e: Exception) { null }
            
            isProcessing.set(true)
            lastProcessTime = now
            
            callback(cameraImage, depthImage)
            
        } catch (e: NotYetAvailableException) {
            // Wait for next frame
        } catch (e: Exception) {
            Log.e("ARCoreVision", "Frame acquisition failed", e)
        }
    }

    fun doneProcessing() {
        isProcessing.set(false)
    }

    fun shutdown() {
        session?.close()
        session = null
    }
}
