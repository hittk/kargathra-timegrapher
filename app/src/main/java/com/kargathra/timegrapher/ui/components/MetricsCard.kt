package com.kargathra.timegrapher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kargathra.timegrapher.ui.theme.KargathraColors
import kotlin.math.abs

/**
 * Primary metric card.
 *
 * Designed for readability from across a workbench. The numeric value
 * uses a serif face at 40sp — large enough to glance at while working
 * on the watch. Label sits above in muted gold at 11sp for hierarchy.
 *
 * Colour of the value changes based on status (good/warn/bad) so the
 * watchmaker can see out-of-spec readings at a glance.
 */
@Composable
fun MetricsCard(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    valueColor: Color = KargathraColors.GoldPrimary,
    isInvalid: Boolean = false
) {
    val displayColor = if (isInvalid) KargathraColors.GoldDim else valueColor

    Box(
        modifier = modifier
            .background(KargathraColors.NavySurface, RoundedCornerShape(3.dp))
            .border(0.5.dp, KargathraColors.NavyBorder, RoundedCornerShape(3.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            // Label (small, muted, spaced)
            Text(
                text          = label,
                color         = KargathraColors.GoldMuted,
                fontSize      = 10.sp,
                fontWeight    = FontWeight.Medium,
                letterSpacing = 2.sp,
                textAlign     = TextAlign.Center
            )

            Spacer(Modifier.height(4.dp))

            // Primary value (large, serif)
            Text(
                text       = if (isInvalid) "—" else value,
                color      = displayColor,
                fontSize   = 34.sp,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Light,
                textAlign  = TextAlign.Center,
                maxLines   = 1
            )

            // Unit (small, muted)
            Text(
                text          = unit,
                color         = KargathraColors.CreamMuted,
                fontSize      = 9.sp,
                letterSpacing = 1.5.sp,
                textAlign     = TextAlign.Center
            )
        }
    }
}

/** Rate card: green/gold if on-spec, amber if marginal, red if off. */
@Composable
fun RateCard(rateSecPerDay: Float, modifier: Modifier = Modifier) {
    // Physically, a watch cannot deviate more than ±1800 s/day (that would be
    // a 30-minute error per day — visibly broken). Values outside this range
    // are detection artefacts and should not be displayed as a number.
    val isValid = rateSecPerDay == 0f || abs(rateSecPerDay) <= 1800f
    val absRate = abs(rateSecPerDay)
    val color = when {
        !isValid       -> KargathraColors.GoldDim
        absRate <= 10f -> KargathraColors.StatusGood
        absRate <= 30f -> KargathraColors.StatusWarn
        else           -> KargathraColors.StatusBad
    }
    val sign = if (rateSecPerDay >= 0) "+" else ""
    MetricsCard(
        label      = "RATE",
        value      = "$sign${"%.1f".format(rateSecPerDay)}",
        unit       = "SEC / DAY",
        modifier   = modifier,
        valueColor = color,
        isInvalid  = !isValid
    )
}

/** Amplitude card: colour-coded to horological norms. */
@Composable
fun AmplitudeCard(
    amplitudeDeg: Float,
    amplitudeValid: Boolean,
    modifier: Modifier = Modifier
) {
    val color = when {
        !amplitudeValid              -> KargathraColors.GoldDim
        amplitudeDeg in 260f..310f   -> KargathraColors.StatusGood
        amplitudeDeg in 220f..330f   -> KargathraColors.StatusWarn
        else                         -> KargathraColors.StatusBad
    }
    MetricsCard(
        label      = "AMPLITUDE",
        value      = "${"%.0f".format(amplitudeDeg)}°",
        unit       = "DEGREES",
        modifier   = modifier,
        valueColor = color,
        isInvalid  = !amplitudeValid
    )
}

/** Beat error card. */
@Composable
fun BeatErrorCard(beatErrorMs: Float, modifier: Modifier = Modifier) {
    val color = when {
        beatErrorMs <= 0.5f -> KargathraColors.StatusGood
        beatErrorMs <= 1.0f -> KargathraColors.StatusWarn
        else                -> KargathraColors.StatusBad
    }
    MetricsCard(
        label      = "BEAT ERROR",
        value      = "%.1f".format(beatErrorMs),
        unit       = "MS",
        modifier   = modifier,
        valueColor = color
    )
}
