package com.example.vx

import android.content.Context
import android.os.Build
import android.os.PowerManager

/** Thermal policy for expensive semantic workloads; safety is never disabled. */
class ThermalDutyManager(
    context: Context,
    private val tier: CapabilityTier
) {
    data class Policy(
        val runSafety: Boolean = true,
        val runSearch: Boolean,
        val runStoryteller: Boolean,
        val cameraFps: Int,
        val messageHindi: String
    )

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var currentStatus = PowerManager.THERMAL_STATUS_NONE
    private val listener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        PowerManager.OnThermalStatusChangedListener { status -> currentStatus = status }
    } else {
        null
    }

    fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && listener != null) {
            powerManager.addThermalStatusListener(listener)
            currentStatus = powerManager.currentThermalStatus
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && listener != null) {
            powerManager.removeThermalStatusListener(listener)
        }
    }

    fun policy(): Policy {
        val status = currentStatus
        return when {
            status >= PowerManager.THERMAL_STATUS_CRITICAL -> Policy(
                runSearch = false,
                runStoryteller = false,
                cameraFps = 5,
                messageHindi = "तापमान बहुत अधिक है, केवल सुरक्षा मोड"
            )
            status >= PowerManager.THERMAL_STATUS_SEVERE -> Policy(
                runSearch = tier != CapabilityTier.BASIC,
                runStoryteller = false,
                cameraFps = 10,
                messageHindi = "फोन गर्म है, स्टोरीटेलर रोक दिया गया"
            )
            status >= PowerManager.THERMAL_STATUS_MODERATE -> Policy(
                runSearch = tier != CapabilityTier.BASIC,
                runStoryteller = tier == CapabilityTier.ENHANCED,
                cameraFps = 15,
                messageHindi = "कम ऊर्जा मोड सक्रिय है"
            )
            else -> Policy(
                runSearch = tier != CapabilityTier.BASIC,
                runStoryteller = tier == CapabilityTier.ENHANCED,
                cameraFps = 30,
                messageHindi = "सामान्य ऑफलाइन मोड"
            )
        }
    }
}
