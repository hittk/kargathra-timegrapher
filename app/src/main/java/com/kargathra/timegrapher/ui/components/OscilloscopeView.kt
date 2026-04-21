package com.kargathra.timegrapher.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.kargathra.timegrapher.audio.WaveformData
import com.kargathra.timegrapher.ui.theme.KargathraColors
import kotlin.math.max

/**
 * Oscilloscope viewport (REQ-5.2).
 *
 * Shows the bandpass-filtered audio signal in real time. Horizontal
 * reference lines indicate:
 *   - Dynamic noise floor RMS (muted gold, thin)
 *   - Trigger threshold (amber, slightly brighter)
 *   - Last detected Unlock event (bright gold vertical line)
 *
 * Lets the watchmaker visually confirm mic placement and detection quality.
 */
@Composable
fun OscilloscopeView(
    waveform: WaveformData?,
    modifier: Modifier = Modifier,
    waveColor: Color            = KargathraColors.GoldPrimary,
    noiseFloorColor: Color      = KargathraColors.GoldMuted,
    thresholdColor: Color       = KargathraColors.StatusWarn,
    triggerMarkerColor: Color   = KargathraColors.GoldBright,
    backgroundColor: Color      = KargathraColors.NavyDeep
) {
    Canvas(
        modifier = modifier
            .background(backgroundColor)
            .fillMaxSize()
    ) {
        if (waveform == null || waveform.samples.isEmpty()) {
            drawIdleGrid(size.width, size.height)
            return@Canvas
        }

        val w  = size.width
        val h  = size.height
        val cy = h / 2f

        val peak = waveform.samples.maxOf { kotlin.math.abs(it) }
        val scale = max(peak, waveform.triggerThreshold * 1.2f).takeIf { it > 0f } ?: 1f

        drawOscGrid(w, h, cy)

        // Threshold lines (mirrored above + below centre)
        val thresholdY  = cy - (waveform.triggerThreshold / scale) * cy
        val thresholdYN = cy + (waveform.triggerThreshold / scale) * cy
        drawDashedHLine(thresholdY,  w, thresholdColor, strokeWidth = 1.5f)
        drawDashedHLine(thresholdYN, w, thresholdColor, strokeWidth = 1.5f)

        // Noise floor lines
        val noiseY  = cy - (waveform.noiseFloorRMS / scale) * cy
        val noiseYN = cy + (waveform.noiseFloorRMS / scale) * cy
        drawDashedHLine(noiseY,  w, noiseFloorColor, strokeWidth = 1f, dashLength = 4f)
        drawDashedHLine(noiseYN, w, noiseFloorColor, strokeWidth = 1f, dashLength = 4f)

        // Waveform path
        val samples = waveform.samples
        val stepX   = w / (samples.size - 1).toFloat()
        val path    = Path().apply {
            moveTo(0f, cy - (samples[0] / scale) * cy)
            for (i in 1 until samples.size) {
                lineTo(i * stepX, cy - (samples[i] / scale) * cy)
            }
        }
        drawPath(path = path, color = waveColor, style = Stroke(width = 1.5f))

        // Last trigger marker
        if (waveform.triggerMarkerIdx in samples.indices) {
            val markerX = waveform.triggerMarkerIdx * stepX
            drawLine(
                color       = triggerMarkerColor,
                start       = Offset(markerX, 0f),
                end         = Offset(markerX, h),
                strokeWidth = 1f,
                alpha       = 0.5f
            )
            val markerY = cy - (waveform.triggerThreshold / scale) * cy
            drawCircle(
                color  = triggerMarkerColor,
                radius = 3.dp.toPx(),
                center = Offset(markerX, markerY)
            )
        }
    }
}

private fun DrawScope.drawOscGrid(w: Float, h: Float, cy: Float) {
    drawLine(
        color       = KargathraColors.NavyBorder,
        start       = Offset(0f, cy),
        end         = Offset(w, cy),
        strokeWidth = 0.5f
    )
    for (i in 1..9) {
        val x = w * i / 10f
        drawLine(
            color       = KargathraColors.NavyBorder,
            start       = Offset(x, 0f),
            end         = Offset(x, h),
            strokeWidth = 0.3f,
            alpha       = 0.5f
        )
    }
}

private fun DrawScope.drawIdleGrid(w: Float, h: Float) {
    val cy = h / 2f
    drawLine(
        color       = KargathraColors.NavyBorder,
        start       = Offset(0f, cy),
        end         = Offset(w, cy),
        strokeWidth = 0.5f
    )
}

private fun DrawScope.drawDashedHLine(
    y: Float,
    w: Float,
    color: Color,
    strokeWidth: Float = 1f,
    dashLength: Float = 8f,
    gapLength: Float = 6f
) {
    var x = 0f
    var drawDash = true
    while (x < w) {
        val segEnd = minOf(x + if (drawDash) dashLength else gapLength, w)
        if (drawDash) {
            drawLine(
                color       = color,
                start       = Offset(x, y),
                end         = Offset(segEnd, y),
                strokeWidth = strokeWidth
            )
        }
        x = segEnd
        drawDash = !drawDash
    }
}
