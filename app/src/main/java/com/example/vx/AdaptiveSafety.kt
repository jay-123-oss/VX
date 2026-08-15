package com.example.vx

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.google.ar.core.ArCoreApk
import kotlin.math.max

/** Runtime modes keep the app usable across multiple Android phones. */
enum class CapabilityTier { BASIC, STANDARD, ENHANCED }

enum class SafetyState { SAFE, CAUTION, WARNING, EMERGENCY }

data class DeviceProfile(
    val totalRamMb: Long,
    val arCoreAvailable: Boolean,
    val tier: CapabilityTier,
    val messageHindi: String
)

data class SafetySnapshot(
    val state: SafetyState,
    val distanceMeters: Float?,
    val confidence: Float,
    val timeToCollisionSeconds: Float?,
    val trackingReliable: Boolean,
    val messageHindi: String
)

class DeviceCapabilityDetector(private val context: Context) {
    fun detect(): DeviceProfile {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memoryInfo)
        val ramMb = memoryInfo.totalMem / (1024 * 1024)
        val arCoreAvailable = runCatching {
            ArCoreApk.getInstance().checkAvailability(context).isSupported
        }.getOrDefault(false)

        val tier = when {
            ramMb >= 7000 && arCoreAvailable -> CapabilityTier.ENHANCED
            ramMb >= 4000 && arCoreAvailable -> CapabilityTier.STANDARD
            else -> CapabilityTier.BASIC
        }
        val message = when (tier) {
            CapabilityTier.ENHANCED -> "उन्नत मोड: डेप्थ, सर्च और स्टोरीटेलर"
            CapabilityTier.STANDARD -> "मानक मोड: सुरक्षा प्राथमिक, सर्च नियंत्रित"
            CapabilityTier.BASIC -> "सुरक्षा मोड: इस फोन पर भारी AI सीमित है"
        }
        return DeviceProfile(ramMb, arCoreAvailable, tier, message)
    }
}

class SafetyDecisionEngine {
    fun evaluate(
        distanceMeters: Float?,
        relativeApproachMetersPerSecond: Float?,
        confidence: Float,
        trackingReliable: Boolean,
        negativeDropOff: Boolean = false,
        cameraBlockedWhileMoving: Boolean = false
    ): SafetySnapshot {
        if (negativeDropOff) {
            return SafetySnapshot(
                state = SafetyState.EMERGENCY,
                distanceMeters = distanceMeters,
                confidence = confidence,
                timeToCollisionSeconds = null,
                trackingReliable = trackingReliable,
                messageHindi = "आगे गड्ढा या नीचे उतरती सीढ़ी हो सकती है, तुरंत रुकें"
            )
        }
        if (cameraBlockedWhileMoving) {
            return SafetySnapshot(
                state = SafetyState.CAUTION,
                distanceMeters = distanceMeters,
                confidence = confidence,
                timeToCollisionSeconds = null,
                trackingReliable = false,
                messageHindi = "कैमरा स्पष्ट नहीं है, चलते समय रुकें"
            )
        }
        if (!trackingReliable || distanceMeters == null || confidence < 0.55f) {
            return SafetySnapshot(
                state = SafetyState.CAUTION,
                distanceMeters = distanceMeters,
                confidence = confidence,
                timeToCollisionSeconds = null,
                trackingReliable = trackingReliable,
                messageHindi = "आगे की सतह स्पष्ट नहीं है, सावधानी रखें"
            )
        }

        val speed = max(relativeApproachMetersPerSecond ?: 0f, 0f)
        val ttc = if (speed > 0.05f) distanceMeters / speed else null
        val state = when {
            ttc != null && ttc <= 1.0f -> SafetyState.EMERGENCY
            distanceMeters <= 0.55f -> SafetyState.EMERGENCY
            ttc != null && ttc <= 2.2f -> SafetyState.WARNING
            distanceMeters <= 1.1f -> SafetyState.WARNING
            distanceMeters <= 1.8f -> SafetyState.CAUTION
            else -> SafetyState.SAFE
        }
        val message = when (state) {
            SafetyState.SAFE -> "रास्ता अभी साफ है"
            SafetyState.CAUTION -> "आगे कुछ पास है, सावधानी रखें"
            SafetyState.WARNING -> "सावधान, आगे रुकावट है"
            SafetyState.EMERGENCY -> "खतरा, तुरंत रुकें"
        }
        return SafetySnapshot(state, distanceMeters, confidence, ttc, true, message)
    }
}

class AdaptiveWorkloadManager {
    data class Policy(
        val runSafety: Boolean = true,
        val runSearch: Boolean,
        val runStoryteller: Boolean,
        val contextIntervalSeconds: Int,
        val messageHindi: String
    )

    fun policy(tier: CapabilityTier, thermalStatus: Int): Policy {
        return when {
            thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL -> Policy(true, false, false, Int.MAX_VALUE, "तापमान अधिक: केवल सुरक्षा मोड")
            thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> Policy(true, tier != CapabilityTier.BASIC, false, Int.MAX_VALUE, "गर्मी अधिक: स्टोरीटेलर रोक दिया गया")
            thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> Policy(true, tier != CapabilityTier.BASIC, tier == CapabilityTier.ENHANCED, 20, "फोन गर्म है: कम ऊर्जा मोड")
            else -> Policy(true, tier != CapabilityTier.BASIC, tier == CapabilityTier.ENHANCED, 10, "सामान्य ऑफलाइन मोड")
        }
    }
}
