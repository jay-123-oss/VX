package com.example.vx

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Handles Pulsed Haptics and Spatial Audio Panning.
 */
class AlertEngine(private val context: Context) {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private var soundPool: SoundPool
    private var alertSoundId: Int = 0
    private var lastVibrateTime = 0L

    init {
        val attr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attr).build()
        // alertSoundId = soundPool.load(context, R.raw.beep, 1)
    }

    /**
     * distanceMm: distance in millimeters
     * xPos: normalized X coordinate (-1.0 Left to 1.0 Right)
     */
    fun processAlert(distanceMm: Int, xPos: Float) {
        if (distanceMm > 2000 || distanceMm <= 0) return

        val now = System.currentTimeMillis()
        
        // Pulse frequency based on distance: Closer = Faster pulse
        val interval = when {
            distanceMm < 500 -> 100L // Continuous-like
            distanceMm < 1000 -> 300L
            distanceMm < 1500 -> 600L
            else -> 1000L
        }

        if (now - lastVibrateTime > interval) {
            triggerVibration(distanceMm)
            playSpatialBeep(xPos, distanceMm)
            lastVibrateTime = now
        }
    }

    private fun triggerVibration(dist: Int) {
        val amplitude = if (dist < 800) 255 else 120
        val duration = if (dist < 800) 200L else 100L
        vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
    }

    private fun playSpatialBeep(xPos: Float, dist: Int) {
        // Panning: -1.0 (Left), 0.0 (Center), 1.0 (Right)
        val leftVol = if (xPos < 0) 1.0f else (1.0f - xPos)
        val rightVol = if (xPos > 0) 1.0f else (1.0f + xPos)
        val intensity = (2000f - dist) / 2000f

        // soundPool.play(alertSoundId, leftVol * intensity, rightVol * intensity, 1, 0, 1.0f + intensity)
        Log.v("AlertEngine", "Beep: Dist=$dist, Pan=$xPos")
    }

    fun release() {
        soundPool.release()
    }
}
