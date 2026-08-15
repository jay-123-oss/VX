package com.example.vx

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.ar.core.TrackingState
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private lateinit var arManager: ARCoreVisionManager
    private lateinit var objectDetector: OnnxObjectDetector
    private lateinit var alerts: AlertEngine
    private lateinit var overlay: OverlayView
    private lateinit var surface: GLSurfaceView
    private lateinit var statusTextView: android.widget.TextView
    private lateinit var safetyEngine: SafetyDecisionEngine
    private lateinit var corridorAnalyzer: DepthCorridorAnalyzer
    private lateinit var planeGroundAnalyzer: PlaneGroundAnalyzer
    private lateinit var deviceProfile: DeviceProfile
    private lateinit var motionManager: SensorMotionManager
    private lateinit var negativeObstacleDetectors: Array<NegativeObstacleDetector>
    private lateinit var thermalDutyManager: ThermalDutyManager
    private lateinit var storytellerEngine: StorytellerEngine
    private val safetyCorridorXs = floatArrayOf(0.35f, 0.50f, 0.65f)
    private val groundProfileYs = floatArrayOf(0.64f, 0.74f, 0.84f, 0.94f)
    private val groundProfileBuffers = Array(safetyCorridorXs.size) { IntArray(groundProfileYs.size) }
    private var lastAppliedFps = -1
    private var previousDistanceMeters: Float? = null
    private var previousDistanceTimeNs: Long = 0L
    private var lastSafetyLogNs: Long = 0L
    @Volatile private var lastSafetySnapshotNs: Long = 0L
    @Volatile private var watchdogAlerted = false
    @Volatile private var lastUiUpdateNs: Long = 0L
    @Volatile private var lastUiState: SafetyState? = null
    private var cameraPipelineStarted = false
    @Volatile private var renderLoopRunning = false
    @Volatile private var renderPeriodMs = 33L
    private val renderHandler = Handler(Looper.getMainLooper())
    private val renderRunnable = object : Runnable {
        override fun run() {
            if (!renderLoopRunning) return
            surface.requestRender()
            renderHandler.postDelayed(this, renderPeriodMs)
        }
    }
    private val safetyWatchdogRunnable = object : Runnable {
        override fun run() {
            if (!renderLoopRunning) return
            val last = lastSafetySnapshotNs
            if (last > 0L && System.nanoTime() - last > SAFETY_WATCHDOG_NS && !watchdogAlerted) {
                watchdogAlerted = true
                val caution = SafetySnapshot(
                    state = SafetyState.CAUTION,
                    distanceMeters = null,
                    confidence = 0f,
                    timeToCollisionSeconds = null,
                    trackingReliable = false,
                    messageHindi = "सुरक्षा जांच रुक गई है, कृपया रुकें"
                )
                alerts.processSnapshot(caution, 0f)
                overlay.updateSnapshot(caution)
                statusTextView.text = formatSafetyMessage(caution)
            }
            renderHandler.postDelayed(this, SAFETY_WATCHDOG_PERIOD_MS)
        }
    }
    @Volatile private var searchRequested = false
    private val perceptionExecutor: ExecutorService = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
        { runnable -> Thread(runnable, "vx-perception").apply { priority = Thread.NORM_PRIORITY - 1 } },
        ThreadPoolExecutor.AbortPolicy()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surface = findViewById(R.id.surfaceview)
        overlay = findViewById(R.id.overlayView)
        statusTextView = findViewById(R.id.statusTextView)

        safetyEngine = SafetyDecisionEngine()
        corridorAnalyzer = DepthCorridorAnalyzer()
        planeGroundAnalyzer = PlaneGroundAnalyzer()
        deviceProfile = DeviceCapabilityDetector(this).detect()
        statusTextView.text = deviceProfile.messageHindi
        motionManager = SensorMotionManager(this)
        negativeObstacleDetectors = Array(safetyCorridorXs.size) { NegativeObstacleDetector() }
        thermalDutyManager = ThermalDutyManager(this, deviceProfile.tier)

        arManager = ARCoreVisionManager(this)
        objectDetector = OnnxObjectDetector(this)
        storytellerEngine = UnavailableStorytellerEngine()
        alerts = AlertEngine(this)

        findViewById<android.widget.Button>(R.id.searchButton).setOnClickListener {
            searchRequested = true
            statusTextView.text = "ऑफलाइन वस्तु खोज शुरू हो रही है"
            alerts.speakSearch("ऑफलाइन वस्तु खोज शुरू हो रही है")
        }

        findViewById<android.widget.Button>(R.id.vlmButton).setOnClickListener {
            val message = if (!storytellerEngine.isAvailable) {
                storytellerEngine.unavailableReasonHindi
            } else {
                when (deviceProfile.tier) {
                    CapabilityTier.ENHANCED -> "स्थानीय स्टोरीटेलर दृश्य विश्लेषण उपलब्ध है"
                    CapabilityTier.STANDARD -> "इस फोन पर दृश्य विश्लेषण कम ऊर्जा मोड में उपलब्ध है"
                    CapabilityTier.BASIC -> "इस फोन पर अभी सुरक्षा मोड सक्रिय है"
                }
            }
            alerts.speakSearch(message)
            statusTextView.text = message
        }

        surface.setEGLContextClientVersion(2)
        surface.setRenderer(this)
        // Drive ARCore at the current workload rate instead of rendering continuously at display FPS.
        surface.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 101)
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = arManager.currentSession() ?: return
        val frame = try {
            session.update()
        } catch (error: Exception) {
            Log.e("MainActivity", "ARCore frame update failed", error)
            return
        }

        val planeAssessment = planeGroundAnalyzer.assess(frame)
        val thermalPolicy = thermalDutyManager.policy()
        val motionFps = motionManager.recommendedFps()
        val targetFps = minOf(motionFps, thermalPolicy.cameraFps)
        if (targetFps != lastAppliedFps) {
            arManager.setTargetFps(targetFps)
            renderPeriodMs = (1_000L / targetFps.coerceAtLeast(1)).coerceAtLeast(16L)
            lastAppliedFps = targetFps
            Log.i("WorkloadPolicy", "Applied camera FPS=$targetFps periodMs=$renderPeriodMs motion=${motionManager.currentState()} thermal=${thermalPolicy.cameraFps}")
        }

        if (frame.camera.trackingState != TrackingState.TRACKING) {
            runOnUiThread {
                val unknown = SafetySnapshot(
                    state = SafetyState.CAUTION,
                    distanceMeters = null,
                    confidence = 0f,
                    timeToCollisionSeconds = null,
                    trackingReliable = false,
                    messageHindi = "कैमरा/डेप्थ स्पष्ट नहीं है, रुकें"
                )
                lastSafetySnapshotNs = System.nanoTime()
                watchdogAlerted = false
                postSafetyUi(unknown, null)
            }
            return
        }

        arManager.onFrame(frame) { camImage, depthImage ->
            try {
                perceptionExecutor.execute {
                    try {
                    // Safety remains independent from YOLO and VLM. Unknown depth is caution,
                    // never a simulated clear path.
                    val normX = 0.5f
                    val depthSampler = depthImage?.let { DepthFusionMath.Sampler(it) }
                    val corridorResult = depthSampler?.let { corridorAnalyzer.analyze(it) }
                    val rawNearestDepthMm = corridorResult?.nearestDepthMm ?: 0
                    val hasReliableDepth = corridorResult?.reliable == true
                    val distanceMm = rawNearestDepthMm.takeIf { hasReliableDepth } ?: 0
                    val distanceMeters = distanceMm.takeIf { it > 0 }?.div(1000f)
                    val nowNs = System.nanoTime()
                    val deltaSeconds = if (previousDistanceTimeNs > 0L) {
                        (nowNs - previousDistanceTimeNs) / 1_000_000_000f
                    } else 0f
                    val relativeApproach = if (distanceMeters != null && previousDistanceMeters != null && deltaSeconds > 0f) {
                        ((previousDistanceMeters!! - distanceMeters) / deltaSeconds).coerceAtLeast(0f)
                    } else null
                    if (distanceMeters != null) previousDistanceMeters = distanceMeters
                    previousDistanceTimeNs = nowNs

                    var dropDetected = false
                    var unknownGround = false
                    if (depthImage != null) {
                        for (index in safetyCorridorXs.indices) {
                            val profile = groundProfileBuffers[index]
                            depthSampler?.fillVerticalProfile(
                                normX = safetyCorridorXs[index],
                                normalizedYs = groundProfileYs,
                                output = profile
                            )
                            val assessment = negativeObstacleDetectors[index].assess(
                                profileMillimeters = profile,
                                groundPlaneTracked = planeAssessment.horizontalGroundTracked
                            )
                            dropDetected = dropDetected || assessment.dropDetected
                            unknownGround = unknownGround || assessment.unknownGround
                        }
                    }
                    val cameraBlockedWhileMoving = !hasReliableDepth && motionManager.isMoving()
                    val snapshot = safetyEngine.evaluate(
                        distanceMeters = distanceMeters,
                        relativeApproachMetersPerSecond = relativeApproach,
                        confidence = corridorResult?.confidence ?: 0f,
                        trackingReliable = hasReliableDepth,
                        negativeDropOff = dropDetected,
                        cameraBlockedWhileMoving = cameraBlockedWhileMoving,
                        groundPlaneReliable = planeAssessment.horizontalGroundTracked
                    )

                    lastSafetySnapshotNs = System.nanoTime()
                    watchdogAlerted = false
                    alerts.processSnapshot(snapshot, (normX * 2) - 1.0f)

                    var searchResultMessage: String? = null
                    if (searchRequested) {
                        searchRequested = false
                        if (!thermalPolicy.runSearch) {
                            alerts.speakSearch("फोन गर्म है, वस्तु खोज अभी रोक दी गई है")
                        } else {
                            val imageBytes = ImageFrameEncoder.toJpeg(camImage)
                            val detections = imageBytes?.let { objectDetector.detect(it) }.orEmpty()
                            val best = detections.maxByOrNull { it.confidence }
                            val searchMessage = if (best == null) {
                                "वस्तु नहीं मिली"
                            } else {
                                val objectDistanceMm = depthSampler?.getAverageDepth(best.centerX, best.centerY) ?: 0
                                val distanceText = if (objectDistanceMm > 0) {
                                    String.format(java.util.Locale.US, "%.1f मीटर", objectDistanceMm / 1000f)
                                } else {
                                    "दूरी स्पष्ट नहीं"
                                }
                                "${toHindiObjectLabel(best.label)} ${clockDirection(best.centerX)}, $distanceText"
                            }
                            alerts.speakSearch(searchMessage)
                            searchResultMessage = searchMessage
                        }
                    }

                    postSafetyUi(snapshot, searchResultMessage)
                    if (nowNs - lastSafetyLogNs > 1_000_000_000L) {
                        Log.i(
                            "ReflexShield",
                            "tracking=${hasReliableDepth} rawDepthMm=$rawNearestDepthMm depthMm=$distanceMm depthReason=${corridorResult?.unknownReason} drop=$dropDetected unknownGround=$unknownGround plane=${planeAssessment.horizontalGroundTracked} planes=${planeAssessment.trackedPlaneCount} motion=${motionManager.currentState()} fps=$targetFps"
                        )
                        lastSafetyLogNs = nowNs
                    }

                } finally {
                    camImage.close()
                    depthImage?.close()
                    arManager.doneProcessing()
                    }
                }
            } catch (_: RejectedExecutionException) {
                camImage.close()
                depthImage?.close()
                arManager.doneProcessing()
            }
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        arManager.setCameraTextureName(textures[0])
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        arManager.setDisplayGeometry(0, w, h)
    }

    override fun onResume() {
        super.onResume()
        if (!hasCameraPermission()) {
            cameraPipelineStarted = false
            statusTextView.text = "कैमरा अनुमति आवश्यक है"
            return
        }
        startCameraPipeline()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_PERMISSION_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            statusTextView.text = "कैमरा अनुमति मिल गई, सुरक्षा शुरू हो रही है"
            startCameraPipeline()
        } else {
            cameraPipelineStarted = false
            statusTextView.text = "कैमरा अनुमति नहीं मिली, ऐप सुरक्षा जांच नहीं कर सकता"
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCameraPipeline() {
        if (cameraPipelineStarted || !hasCameraPermission()) return
        cameraPipelineStarted = true
        surface.onResume()
        motionManager.start()
        thermalDutyManager.start()
        arManager.resume()
        renderLoopRunning = true
        renderHandler.removeCallbacks(renderRunnable)
        renderHandler.removeCallbacks(safetyWatchdogRunnable)
        lastSafetySnapshotNs = System.nanoTime()
        watchdogAlerted = false
        renderHandler.post(renderRunnable)
        renderHandler.postDelayed(safetyWatchdogRunnable, SAFETY_WATCHDOG_PERIOD_MS)
    }

    override fun onPause() {
        super.onPause()
        cameraPipelineStarted = false
        renderLoopRunning = false
        renderHandler.removeCallbacks(renderRunnable)
        renderHandler.removeCallbacks(safetyWatchdogRunnable)
        surface.onPause()
        motionManager.stop()
        thermalDutyManager.stop()
        arManager.pause()
    }

    private fun postSafetyUi(snapshot: SafetySnapshot, searchResultMessage: String?) {
        val nowNs = System.nanoTime()
        val stateChanged = snapshot.state != lastUiState
        val urgent = snapshot.state == SafetyState.WARNING || snapshot.state == SafetyState.EMERGENCY
        if (searchResultMessage == null && !stateChanged && !urgent && nowNs - lastUiUpdateNs < UI_UPDATE_INTERVAL_NS) return
        lastUiUpdateNs = nowNs
        lastUiState = snapshot.state
        runOnUiThread {
            overlay.updateSnapshot(snapshot)
            statusTextView.text = searchResultMessage ?: formatSafetyMessage(snapshot)
        }
    }

    private fun formatSafetyMessage(snapshot: SafetySnapshot): String {
        val distance = snapshot.distanceMeters?.let { String.format(java.util.Locale.US, "%.1f m", it) }
        return when {
            snapshot.state == SafetyState.SAFE && distance != null -> "हरा: रास्ता साफ • दूरी $distance"
            distance != null -> "रुकावट • दूरी $distance • ${snapshot.messageHindi}"
            else -> snapshot.messageHindi
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        renderLoopRunning = false
        renderHandler.removeCallbacks(renderRunnable)
        renderHandler.removeCallbacks(safetyWatchdogRunnable)
        perceptionExecutor.shutdownNow()
        arManager.shutdown()
        objectDetector.close()
        storytellerEngine.close()
        alerts.release()
    }

    private fun toHindiObjectLabel(label: String): String = HindiObjectLabels.labelFor(label)

    private fun clockDirection(centerX: Float): String = when {
        centerX < 0.33f -> "9 बजे की दिशा में"
        centerX < 0.45f -> "10 बजे की दिशा में"
        centerX < 0.55f -> "12 बजे की दिशा में"
        centerX < 0.67f -> "2 बजे की दिशा में"
        else -> "3 बजे की दिशा में"
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 101
        private const val UI_UPDATE_INTERVAL_NS = 150_000_000L
        private const val SAFETY_WATCHDOG_NS = 1_500_000_000L
        private const val SAFETY_WATCHDOG_PERIOD_MS = 500L
    }
}
