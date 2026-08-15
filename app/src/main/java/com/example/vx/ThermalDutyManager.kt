package com.example.vx

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlin.math.max

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
        val messageHindi: String,
        val thermalStatus: Int,
        val thermalHeadroom: Float,
        val searchCooldownSeconds: Int
    )

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    @Volatile private var currentStatus = PowerManager.THERMAL_STATUS_NONE
    @Volatile private var thermalHeadroom = Float.NaN
    @Volatile private var cachedPolicy = makePolicy(currentStatus, thermalHeadroom)
    private var lastHeadroomCheckNanos = 0L
    @Volatile private var listenerRegistered = false
    private val listener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        PowerManager.OnThermalStatusChangedListener { status ->
            currentStatus = status
            cachedPolicy = makePolicy(status, thermalHeadroom)
        }
    } else {
        null
    }

    @Synchronized
    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || listenerRegistered) return
        val thermalListener = listener ?: return
        runCatching {
            powerManager.addThermalStatusListener(thermalListener)
            listenerRegistered = true
        }.onFailure { error ->
            Log.w("ThermalDuty", "Thermal listener registration failed", error)
        }
        currentStatus = powerManager.currentThermalStatus
        cachedPolicy = makePolicy(currentStatus, thermalHeadroom)
    }

    @Synchronized
    fun stop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !listenerRegistered) return
        val thermalListener = listener ?: return
        runCatching { powerManager.removeThermalStatusListener(thermalListener) }
            .onFailure { error -> Log.w("ThermalDuty", "Thermal listener removal failed", error) }
        listenerRegistered = false
    }

    fun policy(): Policy {
        refreshHeadroomIfDue()
        return cachedPolicy
    }

    private fun refreshHeadroomIfDue() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val now = System.nanoTime()
        if (now - lastHeadroomCheckNanos < HEADROOM_INTERVAL_NS) return
        synchronized(this) {
            if (now - lastHeadroomCheckNanos < HEADROOM_INTERVAL_NS) return
            lastHeadroomCheckNanos = now
            val sample = runCatching { powerManager.getThermalHeadroom(10) }.getOrDefault(Float.NaN)
            if (sample.isFinite() && sample > 0f) {
                thermalHeadroom = sample
                cachedPolicy = makePolicy(currentStatus, sample)
            }
        }
    }

    private fun makePolicy(status: Int, headroom: Float): Policy {
        val headroomStatus = when {
            headroom >= 1.0f -> PowerManager.THERMAL_STATUS_SEVERE
            headroom >= 0.95f -> PowerManager.THERMAL_STATUS_MODERATE
            headroom >= 0.85f -> PowerManager.THERMAL_STATUS_LIGHT
            else -> PowerManager.THERMAL_STATUS_NONE
        }
        val effectiveStatus = max(status, headroomStatus)
        return when {
                        effectiveStatus >= PowerManager.THERMAL_STATUS_CRITICAL -> Policy(true, false, false, 5, "तापमान बहुत अधिक है, सुरक्षा चालू है; वस्तु खोज थोड़ी देर बाद उपलब्ध होगी", effectiveStatus, headroom, 30)
            effectiveStatus >= PowerManager.THERMAL_STATUS_SEVERE -> Policy(true, tier != CapabilityTier.BASIC, false, 10, "फोन बहुत गर्म है, स्टोरीटेलर रुका है; सुरक्षा चालू है", effectiveStatus, headroom, 15)
            effectiveStatus >= PowerManager.THERMAL_STATUS_MODERATE -> Policy(true, tier != CapabilityTier.BASIC, false, 15, "फोन गर्म है, कम ऊर्जा मोड सक्रिय है", effectiveStatus, headroom, 8)
            effectiveStatus >= PowerManager.THERMAL_STATUS_LIGHT -> Policy(true, tier != CapabilityTier.BASIC, false, 20, "तापमान बढ़ रहा है, हल्का मोड सक्रिय है", effectiveStatus, headroom, 5)
            else -> Policy(true, tier != CapabilityTier.BASIC, tier == CapabilityTier.ENHANCED, 30, "सामान्य ऑफलाइन मोड", effectiveStatus, headroom, 2)
        }
    }

    companion object {
        private const val HEADROOM_INTERVAL_NS = 10_000_000_000L
    }
}
