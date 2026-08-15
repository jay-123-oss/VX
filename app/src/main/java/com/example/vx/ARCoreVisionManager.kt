package com.example.vx

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.Image
import android.util.Log
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import androidx.core.content.ContextCompat
import com.google.ar.core.exceptions.NotYetAvailableException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns exactly one ARCore session. Lifecycle methods must be called from Activity lifecycle,
 * never from the per-frame render callback. This prevents repeated Resume calls and timestamp
 * drift in ARCore's VIO/IMU pipeline.
 */
class ARCoreVisionManager(private val context: Context) {
    private var session: Session? = null
    private var isResumed = false
    private var pendingTextureName: Int? = null
    private var pendingGeometry: Triple<Int, Int, Int>? = null
    private val isProcessing = AtomicBoolean(false)
    private var lastProcessTimeNs = 0L
    @Volatile private var processIntervalNs = 100_000_000L // default 10 Hz maximum CPU/depth work

    @Synchronized
    fun createIfNeeded(): Session? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w("ARCoreVision", "Camera permission is not granted; session creation skipped")
            return null
        }
        if (session != null) return session
        return runCatching {
            Session(context).also { created ->
                val config = Config(created)
                if (created.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    config.depthMode = Config.DepthMode.AUTOMATIC
                    Log.i("ARCoreVision", "Depth mode enabled")
                } else {
                    Log.w("ARCoreVision", "Depth mode unavailable; safety must remain CAUTION")
                }
                config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                created.configure(config)
                session = created
            }
        }.onFailure { error ->
            Log.e("ARCoreVision", "ARCore session creation failed", error)
        }.getOrNull()
    }

    @Synchronized
    fun resume() {
        val current = createIfNeeded() ?: return
        if (!isResumed) {
            runCatching { current.resume() }
                .onSuccess {
                    isResumed = true
                    pendingTextureName?.let { current.setCameraTextureName(it) }
                    pendingGeometry?.let { (rotation, width, height) -> current.setDisplayGeometry(rotation, width, height) }
                    Log.i("ARCoreVision", "Session resumed")
                }
                .onFailure { Log.e("ARCoreVision", "Session resume failed", it) }
        }
    }

    @Synchronized
    fun pause() {
        if (isResumed) {
            runCatching { session?.pause() }
                .onFailure { Log.w("ARCoreVision", "Session pause failed", it) }
            isResumed = false
        }
        isProcessing.set(false)
    }

    fun currentSession(): Session? = session?.takeIf { isResumed }

    fun setTargetFps(fps: Int) {
        val safeFps = fps.coerceIn(1, 30)
        processIntervalNs = 1_000_000_000L / safeFps
    }

    fun setCameraTextureName(textureName: Int) {
        pendingTextureName = textureName
        currentSession()?.setCameraTextureName(textureName)
    }

    fun setDisplayGeometry(rotation: Int, width: Int, height: Int) {
        pendingGeometry = Triple(rotation, width, height)
        currentSession()?.setDisplayGeometry(rotation, width, height)
    }

    /**
     * Acquires at most one camera/depth pair. The caller owns both Images and must close them.
     * If tracking or depth is unavailable, the caller still receives a CAUTION signal elsewhere.
     */
    fun onFrame(frame: Frame, callback: (Image, Image?) -> Unit) {
        if (!isResumed || frame.camera.trackingState != TrackingState.TRACKING) return
        val nowNs = System.nanoTime()
        if (isProcessing.get() || nowNs - lastProcessTimeNs < processIntervalNs) return

        try {
            val cameraImage = frame.acquireCameraImage()
            val depthImage = try {
                frame.acquireDepthImage16Bits()
            } catch (_: NotYetAvailableException) {
                null
            }
            isProcessing.set(true)
            lastProcessTimeNs = nowNs
            callback(cameraImage, depthImage)
        } catch (_: NotYetAvailableException) {
            // Camera image is not ready yet; do not treat this as free space.
        } catch (error: Exception) {
            Log.e("ARCoreVision", "Frame acquisition failed", error)
        }
    }

    fun doneProcessing() {
        isProcessing.set(false)
    }

    @Synchronized
    fun shutdown() {
        isProcessing.set(false)
        runCatching { session?.close() }
        session = null
        isResumed = false
        pendingTextureName = null
        pendingGeometry = null
    }
}
