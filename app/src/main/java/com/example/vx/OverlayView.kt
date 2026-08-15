package com.example.vx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.Locale

/**
 * Circular Alert UI for visually impaired assistance.
 */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    
    private var currentDistanceMm: Int = 0
    private var alertText: String = "READY"
    private var alertColor: Int = Color.GREEN

    private val circlePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 20f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        textAlign = Paint.Align.CENTER
        textSize = 80f
        isFakeBoldText = true
        setShadowLayer(10f, 0f, 0f, Color.BLACK)
    }

    fun updateAlert(distanceMm: Int) {
        this.currentDistanceMm = distanceMm

        when {
            distanceMm <= 0 -> {
                alertText = "SCANNING..."
                alertColor = Color.WHITE
            }
            distanceMm < 1000 -> {
                alertText = "!!! STOP !!!"
                alertColor = Color.RED
            }
            distanceMm < 2000 -> {
                alertText = "CAUTION"
                alertColor = Color.YELLOW
            }
            else -> {
                alertText = "CLEAR"
                alertColor = Color.GREEN
            }
        }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val cx = width / 2f
        val cy = height / 2f
        val radius = if (width < height) width / 3f else height / 3f

        // Draw Alert Circle
        circlePaint.color = alertColor
        canvas.drawCircle(cx, cy, radius, circlePaint)

        // Draw Status Text
        textPaint.color = alertColor
        textPaint.textSize = 80f
        canvas.drawText(alertText, cx, cy - 20f, textPaint)
        
        // Draw Distance
        if (currentDistanceMm > 0) {
            val distStr = String.format(Locale.US, "%.1fm", currentDistanceMm / 1000f)
            textPaint.textSize = 50f
            canvas.drawText(distStr, cx, cy + 60f, textPaint)
        }
    }
}
