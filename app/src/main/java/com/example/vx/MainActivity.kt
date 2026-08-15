package com.example.vx

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private lateinit var arManager: ARCoreVisionManager
    private lateinit var yolo: YoloDetector
    private lateinit var alerts: AlertEngine
    private lateinit var overlay: OverlayView
    private lateinit var surface: GLSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surface = findViewById(R.id.surfaceview)
        overlay = findViewById(R.id.overlayView)
        
        arManager = ARCoreVisionManager(this)
        yolo = YoloDetector(this)
        alerts = AlertEngine(this)

        surface.setEGLContextClientVersion(2)
        surface.setRenderer(this)
        surface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 101)
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = arManager.resume() ?: return
        val frame = try { session.update() } catch (e: Exception) { return }

        arManager.onFrame(frame) { camImage, depthImage ->
            lifecycleScope.launch(Dispatchers.Default) {
                try {
                    // Logic Simulation for UI Testing (If model output is empty)
                    // In real: yolo.detect(camImage)

                    // Expert Fusion: Center point analysis
                    val normX = 0.5f; val normY = 0.5f
                    var distance = 0
                    
                    if (depthImage != null) {
                        distance = DepthFusionMath.getAverageDepth(normX, normY, depthImage)
                    }
                    
                    // Fallback to Heuristic if depth is 0
                    if (distance <= 0) {
                        distance = DepthFusionMath.estimateDistanceByArea(0.3f, 0.4f)
                    }

                    // Trigger Haptics/Audio
                    alerts.processAlert(distance, (normX * 2) - 1.0f)

                    // Update Circular UI
                    runOnUiThread {
                        overlay.updateAlert(distance)
                    }

                } finally {
                    camImage.close()
                    depthImage?.close()
                    arManager.doneProcessing()
                }
            }
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        arManager.resume()?.setCameraTextureName(textures[0])
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        arManager.resume()?.setDisplayGeometry(0, w, h)
    }

    override fun onResume() {
        super.onResume()
        surface.onResume()
        arManager.resume()
    }

    override fun onPause() {
        super.onPause()
        surface.onPause()
        arManager.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        arManager.shutdown()
        yolo.close()
        alerts.release()
    }
}
