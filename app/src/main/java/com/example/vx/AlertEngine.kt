package com.example.vx

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/** Priority feedback controller: emergency > warning > search/context. */
class AlertEngine(private val context: Context) : TextToSpeech.OnInitListener {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    private val soundPool: SoundPool
    private val spatialBeep = SpatialBeepPlayer()
    private var ttsReady = false
    private var lastVibrateTime = 0L
    private var lastState = SafetyState.CAUTION
    private val tts = TextToSpeech(context.applicationContext, this)

    init {
        val attr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attr).build()
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts.language = Locale("hi", "IN")
            tts.setSpeechRate(0.92f)
        }
    }

    /** Existing distance API retained for compatibility with existing VX callers. */
    fun processAlert(distanceMm: Int, xPos: Float) {
        val snapshot = if (distanceMm <= 0) {
            SafetySnapshot(SafetyState.CAUTION, null, 0f, null, false, "आगे की सतह स्पष्ट नहीं है, सावधानी रखें")
        } else {
            SafetyDecisionEngine().evaluate(
                distanceMeters = distanceMm / 1000f,
                relativeApproachMetersPerSecond = null,
                confidence = 0.9f,
                trackingReliable = true
            )
        }
        processSnapshot(snapshot, xPos)
    }

    fun processSnapshot(snapshot: SafetySnapshot, xPos: Float) {
        val now = System.currentTimeMillis()
        val interval = when (snapshot.state) {
            SafetyState.EMERGENCY -> 120L
            SafetyState.WARNING -> 320L
            SafetyState.CAUTION -> 800L
            SafetyState.SAFE -> Long.MAX_VALUE
        }
        if (snapshot.state == SafetyState.SAFE || now - lastVibrateTime < interval) return

        val stateEscalated = snapshot.state.ordinal > lastState.ordinal
        lastState = snapshot.state
        lastVibrateTime = now
        when (snapshot.state) {
            SafetyState.CAUTION -> {
                triggerVibration(longArrayOf(0, 35), 90)
                playSpatialBeep(xPos, 1200)
            }
            SafetyState.WARNING -> {
                triggerVibration(longArrayOf(0, 90, 100, 90), 180)
                playSpatialBeep(xPos, 800)
                if (stateEscalated) speak(snapshot.messageHindi, TextToSpeech.QUEUE_FLUSH)
            }
            SafetyState.EMERGENCY -> {
                triggerVibration(longArrayOf(0, 180, 80, 180, 80, 180), 255)
                playSpatialBeep(xPos, 350)
                speak(snapshot.messageHindi, TextToSpeech.QUEUE_FLUSH)
            }
            SafetyState.SAFE -> Unit
        }
    }

    fun speakSearch(messageHindi: String) {
        speak(messageHindi, TextToSpeech.QUEUE_ADD)
    }

    private fun speak(text: String, queueMode: Int) {
        if (ttsReady) tts.speak(text, queueMode, null, "vx-${System.currentTimeMillis()}")
    }

    private fun triggerVibration(pattern: LongArray, amplitude: Int) {
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
        Log.v("AlertEngine", "Vibration amplitude=$amplitude")
    }

    private fun playSpatialBeep(xPos: Float, distanceMm: Int) {
        val intensity = ((2000f - distanceMm) / 2000f).coerceIn(0.15f, 1f)
        val leftVol = if (xPos < 0) 1f else 1f - xPos.coerceIn(0f, 1f)
        val rightVol = if (xPos > 0) 1f else 1f + xPos.coerceIn(-1f, 0f)
        val frequency = when {
            distanceMm <= 500 -> 880.0
            distanceMm <= 1000 -> 660.0
            else -> 440.0
        }
        spatialBeep.play(leftVol * intensity, rightVol * intensity, frequency, 110)
        Log.v("AlertEngine", "Beep left=$leftVol right=$rightVol intensity=$intensity")
    }

    fun release() {
        tts.stop()
        tts.shutdown()
        soundPool.release()
        spatialBeep.release()
    }
}
