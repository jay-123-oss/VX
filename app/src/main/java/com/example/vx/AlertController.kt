package com.example.vx

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/** Offline-first Hindi voice and haptic feedback for spatial hazard events. */
class AlertController(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        appContext.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    private val tts = TextToSpeech(appContext, this)
    private var ttsReady = false
    private var released = false
    private var lastSpokenAtMs = 0L
    private var lastSpokenKey = ""

    override fun onInit(status: Int) {
        if (released || status != TextToSpeech.SUCCESS) return
        ttsReady = true
        tts.language = Locale("hi", "IN")
        tts.setSpeechRate(0.9f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts.voices.firstOrNull { voice ->
                voice.locale.language == "hi" &&
                    !voice.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS)
            }?.let { tts.voice = it }
        }
    }

    /**
     * priorityLevel: 0 safe, 1 warning, 2 caution, 3 emergency.
     * The method is safe to call from a worker thread; TTS and vibrator calls are internally queued
     * by Android. Repeated identical alerts are throttled to avoid speech and vibration spam.
     */
    fun speakAlert(priorityLevel: Int, hazardName: String, distance: Float) {
        if (released) return
        val level = priorityLevel.coerceIn(0, 3)
        val zone = when (level) {
            3 -> ThreatZone.EMERGENCY
            2 -> ThreatZone.CAUTION
            1 -> ThreatZone.WARNING
            else -> ThreatZone.SAFE
        }
        val now = System.currentTimeMillis()
        val key = "$level:${hazardName.trim()}:${distance.toInt()}"
        val interval = if (level >= 3) EMERGENCY_INTERVAL_MS else ALERT_INTERVAL_MS
        if (key == lastSpokenKey && now - lastSpokenAtMs < interval) return
        lastSpokenKey = key
        lastSpokenAtMs = now
        vibrate(zone)
        if (level == 0) return
        val distanceText = if (distance.isFinite() && distance > 0f) {
            String.format(Locale.US, "%.1f मीटर", distance)
        } else {
            "दूरी स्पष्ट नहीं"
        }
        val prefix = when (zone) {
            ThreatZone.EMERGENCY -> "तुरंत रुकें"
            ThreatZone.CAUTION -> "सावधान"
            ThreatZone.WARNING -> "चेतावनी"
            ThreatZone.SAFE -> "सुरक्षित"
        }
        val sentence = "$prefix, ${hazardName.ifBlank { "रुकावट" }}, $distanceText दूर"
        if (ttsReady) {
            tts.speak(sentence, TextToSpeech.QUEUE_FLUSH, null, "vx-alert-${now}")
        }
    }

    fun handle(fused: FusedDetection) {
        if (!fused.trigger) return
        speakAlert(
            priorityLevel = fused.zone.priority,
            hazardName = fused.detection.label,
            distance = fused.distanceMeters ?: Float.NaN
        )
    }

    private fun vibrate(zone: ThreatZone) {
        if (!vibrator.hasVibrator()) return
        val pattern: LongArray
        val amplitudes: IntArray
        val repeat: Int
        when (zone) {
            ThreatZone.WARNING -> {
                pattern = longArrayOf(0L, 70L, 120L, 70L)
                amplitudes = intArrayOf(0, 120, 0, 120)
                repeat = -1
            }
            ThreatZone.CAUTION -> {
                pattern = longArrayOf(0L, 45L, 180L, 45L)
                amplitudes = intArrayOf(0, 80, 0, 80)
                repeat = -1
            }
            ThreatZone.EMERGENCY -> {
                pattern = longArrayOf(0L, 220L, 70L)
                amplitudes = intArrayOf(0, 255, 0)
                repeat = 0
            }
            ThreatZone.SAFE -> return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, repeat))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, repeat)
        }
    }

    fun release() {
        if (released) return
        released = true
        ttsReady = false
        vibrator.cancel()
        tts.stop()
        tts.shutdown()
        Log.d(TAG, "AlertController released")
    }

    companion object {
        private const val TAG = "AlertController"
        private const val ALERT_INTERVAL_MS = 1_000L
        private const val EMERGENCY_INTERVAL_MS = 650L
    }
}
