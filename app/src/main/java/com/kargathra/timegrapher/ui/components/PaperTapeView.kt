package com.kargathra.timegrapher.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import com.kargathra.timegrapher.timing.TapePoint
import com.kargathra.timegrapher.ui.theme.KargathraColors

/**
 * Paper Tape Display (REQ-5.1).
 *
 * Scrolls right-to-left. Each point:
 *  - X = time (newest at right edge)
 *  - Y = per-beat timing deviation from ideal BPH (ms)
 *
 * Tick beats are drawn in gold; tock beats are drawn in cream. When beat
 * error is present, the tick and tock dots settle at different Y levels,
 * producing the classic two-parallel-lines visual.
 *
 * Hardware-accelerated via Compose Canvas (REQ-5.3). Fully decoupled from
 * the C++ audio thread.
 */
@Composable
fun PaperTapeView(
    points: List<TapePoint>,
    modifier: Modifier = Modifier,
    deviationRangeMs: Float = 20f,    // ±ms shown on Y axis
    pointSpacingDp: Float = 7f,
    dotRadiusDp: Float = 3f,
    tickColor: Color = KargathraColors.GoldPrimary,
    tockColor: Color = KargathraColors.CreamPrimary,
    gridColor: Color = KargathraColors.NavyBorder,
    axisColor: Color = KargathraColors.GoldDim,
    backgroundColor: Color = KargathraColors.NavyDeep
) {
    Canvas(
        modifier = modifier
            .background(backgroundColor)
            .fillMaxSize()
    ) {
        val w = size.width
        val h = size.height
        val cy = h / 2f

        drawTapeGrid(w, h, cy, gridColor, axisColor)

        if (points.isEmpty()) return@Canvas

        val spacingPx = pointSpacingDp.dp.toPx()
        val dotRadius = dotRadiusDp.dp.toPx()
        val maxVisible = (w / spacingPx).toInt() + 1
        val visiblePoints = points.takeLast(maxVisible)
        val count = visiblePoints.size

        clipRect(0f, 0f, w, h) {
            for (i in visiblePoints.indices) {
                val point = visiblePoints[i]
                val x = w - (count - 1 - i) * spacingPx

                val deviationClamped = point.deviationMs.coerceIn(-deviationRangeMs, deviationRangeMs)
                val yNorm = deviationClamped / deviationRangeMs
                val y = cy - (yNorm * (cy - dotRadius * 2))

                val color = if (point.isTock) tockColor else tickColor

                drawCircle(
                    color  = color,
                    radius = dotRadius,
                    center = Offset(x, y)
                )
            }
        }
    }
}

private fun DrawScope.drawTapeGrid(
    w: Float,
    h: Float,
    cy: Float,
    gridColor: Color,
    axisColor: Color
) {
    // Centre axis (0ms deviation) — gold, slightly brighter
    drawLine(
        color       = axisColor,
        start       = Offset(0f, cy),
        end         = Offset(w, cy),
        strokeWidth = 1f,
        alpha       = 0.6f
    )

    // Minor grid lines at 25% intervals
    for (frac in listOf(0.25f, 0.5f, 0.75f)) {
        val yAbove = cy - frac * cy
        val yBelow = cy + frac * cy
        for (y in listOf(yAbove, yBelow)) {
            drawLine(
                color       = gridColor,
                start       = Offset(0f, y),
                end         = Offset(w, y),
                strokeWidth = 0.5f,
                alpha       = 0.5f
            )
        }
    }
}
