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
    private lateinit var statusTextView: android.widget.TextView
    private lateinit var safetyEngine: SafetyDecisionEngine
    private lateinit var deviceProfile: DeviceProfile

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surface = findViewById(R.id.surfaceview)
        overlay = findViewById(R.id.overlayView)
        statusTextView = findViewById(R.id.statusTextView)

        safetyEngine = SafetyDecisionEngine()
        deviceProfile = DeviceCapabilityDetector(this).detect()
        statusTextView.text = deviceProfile.messageHindi

        arManager = ARCoreVisionManager(this)
        yolo = YoloDetector(this)
        alerts = AlertEngine(this)

        findViewById<android.widget.Button>(R.id.vlmButton).setOnClickListener {
            val message = when (deviceProfile.tier) {
                CapabilityTier.ENHANCED -> "स्थानीय स्टोरीटेलर मॉडल तैयार होने पर दृश्य का वर्णन मिलेगा"
                CapabilityTier.STANDARD -> "इस फोन पर दृश्य विश्लेषण कम ऊर्जा मोड में उपलब्ध होगा"
                CapabilityTier.BASIC -> "इस फोन पर अभी सुरक्षा मोड सक्रिय है"
            }
            alerts.speakSearch(message)
            statusTextView.text = message
        }

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
                    // Safety remains independent from YOLO and VLM. Unknown depth is caution,
                    // never a simulated clear path.
                    val normX = 0.5f
                    val normY = 0.5f
                    val distanceMm = depthImage?.let {
                        DepthFusionMath.getAverageDepth(normX, normY, it)
                    } ?: 0
                    val hasReliableDepth = depthImage != null && distanceMm > 0
                    val snapshot = safetyEngine.evaluate(
                        distanceMeters = distanceMm.takeIf { it > 0 }?.div(1000f),
                        relativeApproachMetersPerSecond = null,
                        confidence = if (hasReliableDepth) 0.85f else 0f,
                        trackingReliable = hasReliableDepth
                    )

                    alerts.processSnapshot(snapshot, (normX * 2) - 1.0f)

                    runOnUiThread {
                        overlay.updateSnapshot(snapshot)
                        statusTextView.text = snapshot.messageHindi
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
