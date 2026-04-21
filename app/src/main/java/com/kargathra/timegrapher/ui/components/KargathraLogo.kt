package com.kargathra.timegrapher.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kargathra.timegrapher.ui.theme.KargathraColors

/**
 * KARGATHRA & Co. logo mark.
 *
 * An inverted triangle outline containing a serif capital K.
 * Drawn with Canvas primitives so it renders crisply at any size and is
 * always a single cohesive glyph (rather than relying on font fallback
 * for the K character inside a separately-drawn triangle).
 *
 * Use this in headers, splash screens, and anywhere else the brand mark
 * is needed in-app. The launcher icon is a separate vector drawable
 * ([ic_launcher_foreground]) since Android requires a static XML drawable.
 */
@Composable
fun KargathraLogo(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    color: Color = KargathraColors.GoldPrimary,
    strokeWidth: Dp = 1.5.dp
) {
    val textMeasurer = rememberTextMeasurer()

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height

            // ── Inverted triangle (outline) ────────────────────────────────
            // Top-left → top-right → bottom point, padded inside the canvas.
            val pad = w * 0.05f
            val triangle = Path().apply {
                moveTo(pad,         h * 0.18f)
                lineTo(w - pad,     h * 0.18f)
                lineTo(w / 2f,      h * 0.90f)
                close()
            }
            drawPath(
                path  = triangle,
                color = color,
                style = Stroke(width = strokeWidth.toPx())
            )

            // ── Serif K, centred inside the triangle ───────────────────────
            // Use drawText with FontFamily.Serif so the K is a real glyph —
            // this renders a properly-shaped serif K without having to
            // reconstruct one from path primitives.
            val kStyle = TextStyle(
                color      = color,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Normal,
                fontSize   = (size.value * 0.52f).sp
            )
            val layout = textMeasurer.measure("K", kStyle)
            val kW = layout.size.width.toFloat()
            val kH = layout.size.height.toFloat()

            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    x = (w - kW) / 2f,
                    // Nudge the K visually toward the vertical centre of the
                    // triangle. The triangle's geometric centre is roughly
                    // 43% down from the top.
                    y = h * 0.43f - kH / 2f
                )
            )
        }
    }
}
