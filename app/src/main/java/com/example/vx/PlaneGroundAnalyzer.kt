package com.example.vx

import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState

/**
 * Tracks ARCore's upward-facing horizontal planes as a ground reference. Plane availability is
 * used as confidence metadata; depth remains the primary collision signal.
 */
class PlaneGroundAnalyzer {
    data class Assessment(
        val horizontalGroundTracked: Boolean,
        val trackedPlaneCount: Int,
        val messageHindi: String
    )

    fun assess(frame: Frame): Assessment {
        val planes = frame.getUpdatedTrackables(Plane::class.java)
        val trackedGround = planes.count {
            it.trackingState == TrackingState.TRACKING &&
                it.type == Plane.Type.HORIZONTAL_UPWARD_FACING
        }
        return if (trackedGround > 0) {
            Assessment(true, trackedGround, "जमीन का ARCore प्लेन ट्रैक हो रहा है")
        } else {
            Assessment(false, 0, "जमीन का प्लेन अभी स्पष्ट नहीं है")
        }
    }
}
