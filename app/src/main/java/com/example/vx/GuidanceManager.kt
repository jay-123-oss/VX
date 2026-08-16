package com.example.vx

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/** Android-only feedback adapter; policy remains inside SmartAlertEngine. */
class GuidanceManager(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        appContext.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    private val hasVibrator = vibrator.hasVibrator()
    private val tts = TextToSpeech(appContext, this)
    @Volatile private var ttsReady = false
    private var currentHaptic = HapticSignal.NONE
    private var lastPulseMs = 0L
    private var released = false

    init {
        Log.i(TAG, "initialized hasVibrator=$hasVibrator sdk=${Build.VERSION.SDK_INT}")
    }

    override fun onInit(status: Int) {
        if (released || status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS init failed status=$status")
            return
        }
        tts.language = Locale("hi", "IN")
        tts.setSpeechRate(0.92f)
        ttsReady = true
        Log.i(TAG, "TTS ready")
    }

    @Synchronized
    fun apply(decision: AlertDecision, nowMs: Long = System.currentTimeMillis()) {
        if (released) return
        Log.d(TAG, "apply level=${decision.level} haptic=${decision.haptic} speech=${decision.speechHindi != null} reason=${decision.reason}")

        // Start haptics before speaking. TTS can involve a binder/engine queue; it must never
        // delay the physical stop signal for a critical alert.
        when (decision.haptic) {
            HapticSignal.CRITICAL, HapticSignal.STOP -> {
                if (currentHaptic != decision.haptic) {
                    vibrate(
                        CRITICAL_PATTERN,
                        255,
                        repeat = if (decision.haptic == HapticSignal.CRITICAL) 0 else -1
                    )
                    currentHaptic = decision.haptic
                }
            }
            HapticSignal.HIGH_RISK -> {
                if (currentHaptic != HapticSignal.HIGH_RISK || nowMs - lastPulseMs >= HIGH_PULSE_INTERVAL_MS) {
                    vibrate(HIGH_PATTERN, 190, repeat = -1)
                    currentHaptic = HapticSignal.HIGH_RISK
                    lastPulseMs = nowMs
                }
            }
            HapticSignal.MEDIUM -> {
                if (currentHaptic != HapticSignal.MEDIUM || nowMs - lastPulseMs >= MEDIUM_PULSE_INTERVAL_MS) {
                    vibrate(MEDIUM_PATTERN, 120, repeat = -1)
                    currentHaptic = HapticSignal.MEDIUM
                    lastPulseMs = nowMs
                }
            }
            HapticSignal.NONE -> stopHaptic()
        }

        decision.speechHindi?.let { text ->
            when (decision.level) {
                SmartAlertLevel.CRITICAL, SmartAlertLevel.DEGRADED -> speak(text, flush = true)
                SmartAlertLevel.HIGH_RISK -> speak(text, flush = currentHaptic == HapticSignal.CRITICAL)
                SmartAlertLevel.MEDIUM, SmartAlertLevel.SAFE -> speak(text, flush = false)
                SmartAlertLevel.IDLE -> Unit
            }
        }
    }

    @Synchronized
    fun speakSearch(messageHindi: String) {
        if (!released) speak(messageHindi, flush = false)
    }

    @Synchronized
    fun stopHaptic() {
        if (released) return
        vibrator.cancel()
        currentHaptic = HapticSignal.NONE
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        vibrator.cancel()
        tts.stop()
        tts.shutdown()
        ttsReady = false
    }

    private fun speak(text: String, flush: Boolean) {
        if (!ttsReady || text.isBlank()) {
            if (!ttsReady) Log.d(TAG, "speech skipped: TTS not ready")
            return
        }
        if (flush) tts.stop()
        tts.speak(
            text,
            if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
            null,
            "vx-${System.currentTimeMillis()}"
        )
    }

    private fun vibrate(pattern: LongArray, amplitude: Int, repeat: Int) {
        if (!hasVibrator) {
            Log.e(TAG, "vibration skipped: device reports no vibrator")
            return
        }
        Log.i(TAG, "vibrate pattern=${pattern.contentToString()} amplitude=$amplitude repeat=$repeat")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitudes = pattern.map { if (it == 0L) 0 else amplitude.coerceIn(1, 255) }.toIntArray()
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, repeat))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, repeat)
        }
    }

    companion object {
        private const val TAG = "GuidanceManager"
        private val CRITICAL_PATTERN = longArrayOf(0, 220, 80, 220, 80, 220)
        private val HIGH_PATTERN = longArrayOf(0, 130, 100, 130, 900)
        private val MEDIUM_PATTERN = longArrayOf(0, 80, 140, 80, 1_000)
        private const val HIGH_PULSE_INTERVAL_MS = 2_000L
        private const val MEDIUM_PULSE_INTERVAL_MS = 3_000L
    }
}
