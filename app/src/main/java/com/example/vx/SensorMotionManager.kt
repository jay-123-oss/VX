package com.example.vx

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Low-allocation motion signal used as a confidence input, not as an obstacle detector.
 * A camera/depth failure while the user is moving is treated more conservatively than stillness.
 */
class SensorMotionManager(context: Context) : SensorEventListener {
    enum class State { MOVING, STILL, UNKNOWN }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var smoothedMagnitude = 0f
    private var lastEventNanos = 0L
    private var stillSinceNanos = 0L
    @Volatile private var state: State = if (sensor == null) State.UNKNOWN else State.STILL

    fun start() {
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    fun currentState(): State = state

    fun isMoving(): Boolean = state == State.MOVING

    fun hasRecentSample(nowNanos: Long = System.nanoTime()): Boolean =
        lastEventNanos > 0L && nowNanos - lastEventNanos < 1_000_000_000L

    override fun onSensorChanged(event: SensorEvent) {
        if (event.values.size < 3) return
        val rawMagnitude = sqrt(
            event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2]
        )
        // Exponential smoothing reduces sensor noise without allocating arrays per callback.
        smoothedMagnitude = smoothedMagnitude * 0.82f + rawMagnitude * 0.18f
        lastEventNanos = event.timestamp

        if (smoothedMagnitude >= MOVING_THRESHOLD) {
            state = State.MOVING
            stillSinceNanos = 0L
        } else if (smoothedMagnitude <= STILL_THRESHOLD) {
            if (stillSinceNanos == 0L) stillSinceNanos = event.timestamp
            state = if (event.timestamp - stillSinceNanos >= STILL_CONFIRMATION_NS) {
                State.STILL
            } else {
                State.UNKNOWN
            }
        } else {
            state = State.UNKNOWN
            stillSinceNanos = 0L
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val MOVING_THRESHOLD = 0.45f
        private const val STILL_THRESHOLD = 0.16f
        private const val STILL_CONFIRMATION_NS = 1_000_000_000L
    }
}
