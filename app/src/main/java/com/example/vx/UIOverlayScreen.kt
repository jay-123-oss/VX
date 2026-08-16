package com.example.vx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import java.util.Locale

/** State exposed by the live AR/TFLite frame loop to the Compose dashboard. */
data class OverlayDashboardState(
    val detections: List<FusedDetection> = emptyList(),
    val highestZone: ThreatZone = ThreatZone.SAFE,
    val fps: Float = 0f,
    val batteryPercent: Int = 0,
    val cpuPercent: Float = 0f,
    val gpuPercent: Float = 0f,
    val tracking: Boolean = false,
    val acceleration: String = "CPU"
)

@Composable
fun UIOverlayScreen(
    state: OverlayDashboardState,
    modifier: Modifier = Modifier
) {
    val zoneColor = Color(state.highestZone.colorArgb)
    Box(modifier = modifier.fillMaxSize()) {
        DetectionCanvas(
            detections = state.detections,
            modifier = Modifier.fillMaxSize()
        )
        Surface(
            color = zoneColor.copy(alpha = 0.92f),
            contentColor = Color.Black,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = state.highestZone.labelHindi,
                        style = MaterialTheme.typography.titleLarge,
                        fontSize = 22.sp
                    )
                    Text(
                        text = if (state.tracking) "ARCore tracking सक्रिय" else "Tracking स्पष्ट नहीं",
                        fontSize = 12.sp
                    )
                }
                Text(
                    text = when (state.highestZone) {
                        ThreatZone.SAFE -> "SAFE"
                        ThreatZone.WARNING -> "WARNING"
                        ThreatZone.CAUTION -> "CAUTION"
                        ThreatZone.EMERGENCY -> "STOP"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
        Surface(
            color = Color.Black.copy(alpha = 0.72f),
            contentColor = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("FPS ${state.fps.formatOne()}\nBattery ${state.batteryPercent}%", fontSize = 12.sp)
                Text("CPU ${state.cpuPercent.formatOne()}%\nGPU ${state.gpuPercent.formatOne()}%", fontSize = 12.sp)
                Text("AI ${state.acceleration}\n${state.detections.size} objects", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun DetectionCanvas(
    detections: List<FusedDetection>,
    modifier: Modifier
) {
    val density = LocalDensity.current
    Canvas(modifier = modifier.alpha(0.98f)) {
        detections.forEach { fused ->
            val box = fused.detection
            val color = Color(fused.zone.colorArgb)
            val left = box.left.coerceIn(0f, 1f) * size.width
            val top = box.top.coerceIn(0f, 1f) * size.height
            val right = box.right.coerceIn(0f, 1f) * size.width
            val bottom = box.bottom.coerceIn(0f, 1f) * size.height
            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size((right - left).coerceAtLeast(1f), (bottom - top).coerceAtLeast(1f)),
                style = Stroke(width = with(density) { 3.dp.toPx() })
            )
            val distanceText = fused.distanceMeters?.let { String.format(Locale.US, "%.1fm", it) } ?: "?m"
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color.toArgb()
                    textSize = with(density) { 15.sp.toPx() }
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setShadowLayer(5f, 0f, 0f, android.graphics.Color.BLACK)
                }
                canvas.nativeCanvas.drawText(
                    "${box.label} - $distanceText",
                    left,
                    (top - with(density) { 6.dp.toPx() }).coerceAtLeast(paint.textSize),
                    paint
                )
            }
        }
    }
}

private fun Float.formatOne(): String = String.format(Locale.US, "%.1f", this)
