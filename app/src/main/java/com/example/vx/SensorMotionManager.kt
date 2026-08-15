package com.example.vx

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Low-allocation motion signal used as a confidence input, not as an obstacle detector.
 * Hysteresis keeps brief sensor noise from repeatedly switching the camera workload.
 */
class SensorMotionManager(context: Context) : SensorEventListener {
    enum class State { MOVING, STILL, UNKNOWN }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var smoothedMagnitude = 0f
    private var lastEventNanos = 0L
    private var candidateSinceNanos = 0L
    private var candidateMode = State.STILL
    @Volatile private var state: State = if (sensor == null) State.UNKNOWN else State.STILL
    @Volatile private var stableMode: State = if (sensor == null) State.UNKNOWN else State.STILL

    fun start() {
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    fun currentState(): State = state

    fun isMoving(): Boolean = stableMode == State.MOVING

    /** Returns the workload target; unknown sensor data fails safe to full-rate safety. */
    fun recommendedFps(nowNanos: Long = System.nanoTime()): Int {
        if (sensor == null || lastEventNanos == 0L || nowNanos - lastEventNanos > SENSOR_STALE_NS) return 30
        return if (stableMode == State.STILL) 5 else 30
    }

    fun hasRecentSample(nowNanos: Long = System.nanoTime()): Boolean =
        lastEventNanos > 0L && nowNanos - lastEventNanos < SENSOR_STALE_NS

    override fun onSensorChanged(event: SensorEvent) {
        if (event.values.size < 3) return
        val rawMagnitude = sqrt(
            event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2]
        )
        smoothedMagnitude = smoothedMagnitude * 0.86f + rawMagnitude * 0.14f
        lastEventNanos = event.timestamp

        val desiredMode = when {
            smoothedMagnitude >= MOVING_ENTER_THRESHOLD -> State.MOVING
            smoothedMagnitude <= STILL_ENTER_THRESHOLD -> State.STILL
            else -> stableMode
        }
        if (desiredMode != stableMode) {
            if (candidateMode != desiredMode) {
                candidateMode = desiredMode
                candidateSinceNanos = event.timestamp
            }
            val confirmationNs = if (desiredMode == State.MOVING) MOVING_CONFIRMATION_NS else STILL_CONFIRMATION_NS
            if (event.timestamp - candidateSinceNanos >= confirmationNs) {
                stableMode = desiredMode
                candidateMode = desiredMode
                candidateSinceNanos = event.timestamp
            }
            state = State.UNKNOWN
        } else {
            candidateMode = desiredMode
            candidateSinceNanos = event.timestamp
            state = stableMode
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val MOVING_ENTER_THRESHOLD = 0.55f
        private const val STILL_ENTER_THRESHOLD = 0.12f
        private const val MOVING_CONFIRMATION_NS = 450_000_000L
        private const val STILL_CONFIRMATION_NS = 1_500_000_000L
        private const val SENSOR_STALE_NS = 2_000_000_000L
    }
}
