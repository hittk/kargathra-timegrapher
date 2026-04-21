package com.kargathra.timegrapher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ─────────────────────────────────────────────────────────────────────────────
// KARGATHRA & Co. brand palette — navy + champagne gold.
//
// Use these named Color constants everywhere rather than raw hex values so
// the palette can be adjusted in one place.
// ─────────────────────────────────────────────────────────────────────────────

object KargathraColors {
    // Navy (backgrounds)
    val NavyDeep      = Color(0xFF0B1A2E)  // Primary app background
    val NavySurface   = Color(0xFF152641)  // Card / elevated surface
    val NavyElevated  = Color(0xFF1B2F4F)  // Dialog / overlay
    val NavyBorder    = Color(0xFF1E3555)  // Hairline divider

    // Gold (brand + primary accent)
    val GoldPrimary   = Color(0xFFD4A84B)  // Brand, logo, key data
    val GoldBright    = Color(0xFFEFC667)  // Active / selected
    val GoldMuted     = Color(0xFF8B7344)  // Labels, secondary
    val GoldDim       = Color(0xFF5A4A2C)  // Inactive controls

    // Cream (high-contrast text)
    val CreamPrimary  = Color(0xFFF5E8C8)  // Body text
    val CreamMuted    = Color(0xFFA89B7F)  // Secondary labels

    // Status (used sparingly; gold = good so the brand colour reads as OK)
    val StatusGood    = GoldPrimary
    val StatusWarn    = Color(0xFFE89B3E)  // Amber
    val StatusBad     = Color(0xFFCC4E3A)  // Oxide red
}

private val KargathraColorScheme = darkColorScheme(
    primary            = KargathraColors.GoldPrimary,
    onPrimary          = KargathraColors.NavyDeep,
    primaryContainer   = KargathraColors.GoldDim,
    onPrimaryContainer = KargathraColors.GoldBright,

    secondary          = KargathraColors.GoldBright,
    onSecondary        = KargathraColors.NavyDeep,

    background         = KargathraColors.NavyDeep,
    onBackground       = KargathraColors.CreamPrimary,
    surface            = KargathraColors.NavySurface,
    onSurface          = KargathraColors.CreamPrimary,
    surfaceVariant     = KargathraColors.NavyElevated,
    onSurfaceVariant   = KargathraColors.CreamMuted,

    outline            = KargathraColors.NavyBorder,
    outlineVariant     = KargathraColors.GoldDim,

    error              = KargathraColors.StatusBad,
    onError            = KargathraColors.CreamPrimary
)

// ─────────────────────────────────────────────────────────────────────────────
// Typography
//
// Uses system Serif for display / brand (heritage feel) and system Default
// for body. Monospace is used explicitly in metric readouts where digits
// need to align. Sizes are tuned for phone readability and avoid anything
// below 12sp.
// ─────────────────────────────────────────────────────────────────────────────

private val KargathraTypography = Typography(
    // Large primary metric readouts (Rate, Amplitude, Beat Error, Lift Time)
    displayLarge = TextStyle(
        fontFamily  = FontFamily.Serif,
        fontWeight  = FontWeight.Light,
        fontSize    = 44.sp,
        letterSpacing = (-0.5).sp
    ),
    // Brand wordmark
    displayMedium = TextStyle(
        fontFamily  = FontFamily.Serif,
        fontWeight  = FontWeight.Normal,
        fontSize    = 22.sp,
        letterSpacing = 1.sp
    ),
    // Section headers / tape labels
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize   = 14.sp,
        letterSpacing = 0.8.sp
    ),
    // Metric labels (uppercase small)
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize   = 12.sp,
        letterSpacing = 2.sp
    ),
    // Smallest readable caption
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize   = 11.sp,
        letterSpacing = 1.5.sp
    ),
    // Body text
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize   = 14.sp
    )
)

@Composable
fun KargathraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KargathraColorScheme,
        typography  = KargathraTypography,
        content     = content
    )
}
