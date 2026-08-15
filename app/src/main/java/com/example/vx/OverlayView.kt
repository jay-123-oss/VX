package com.example.vx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.Locale

/** Circular safety UI for visually impaired assistance. */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var currentDistanceMm: Int = 0
    private var alertText: String = "तैयार"
    private var alertColor: Int = Color.WHITE

    private val circlePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 20f
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        textAlign = Paint.Align.CENTER
        textSize = 72f
        isFakeBoldText = true
        setShadowLayer(10f, 0f, 0f, Color.BLACK)
    }

    fun updateSnapshot(snapshot: SafetySnapshot) {
        currentDistanceMm = snapshot.distanceMeters?.times(1000f)?.toInt() ?: 0
        when (snapshot.state) {
            SafetyState.SAFE -> {
                alertText = "सुरक्षित"
                alertColor = Color.GREEN
            }
            SafetyState.CAUTION -> {
                alertText = "सावधानी"
                alertColor = Color.YELLOW
            }
            SafetyState.WARNING -> {
                alertText = "चेतावनी"
                alertColor = 0xFFFF8800.toInt()
            }
            SafetyState.EMERGENCY -> {
                alertText = "तुरंत रुकें"
                alertColor = Color.RED
            }
        }
        postInvalidate()
    }

    /** Compatibility helper for existing callers. */
    fun updateAlert(distanceMm: Int) {
        val snapshot = SafetyDecisionEngine().evaluate(
            distanceMeters = distanceMm.takeIf { it > 0 }?.div(1000f),
            relativeApproachMetersPerSecond = null,
            confidence = if (distanceMm > 0) 0.9f else 0f,
            trackingReliable = distanceMm > 0
        )
        updateSnapshot(snapshot)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = if (width < height) width / 3f else height / 3f

        circlePaint.color = alertColor
        canvas.drawCircle(cx, cy, radius, circlePaint)
        textPaint.color = alertColor
        textPaint.textSize = 72f
        canvas.drawText(alertText, cx, cy - 20f, textPaint)

        if (currentDistanceMm > 0) {
            val distStr = String.format(Locale.US, "%.1fm", currentDistanceMm / 1000f)
            textPaint.textSize = 50f
            canvas.drawText(distStr, cx, cy + 60f, textPaint)
        }
    }
}
