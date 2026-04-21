package com.kargathra.timegrapher.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kargathra.timegrapher.audio.EngineState
import com.kargathra.timegrapher.timing.STANDARD_BPH
import com.kargathra.timegrapher.timing.TimingViewModel
import com.kargathra.timegrapher.ui.components.*
import com.kargathra.timegrapher.ui.theme.KargathraColors

/**
 * Main screen.
 *
 * Visual hierarchy (top to bottom):
 *   1. Brand header      — identity + start/stop primary action (56dp)
 *   2. BPH status strip  — current lock state (24dp)
 *   3. Metrics quadrant  — 4 large readable metric cards (~25% height)
 *   4. Paper tape        — primary visual output (~35% height)
 *   5. Oscilloscope      — diagnostic monitor (~15% height)
 *   6. Controls          — BPH selector, lift angle slider, collapsed advanced
 */
@Composable
fun MainScreen(
    viewModel: TimingViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KargathraColors.NavyDeep)
            .systemBarsPadding()
    ) {

        // ── 1. Brand header ────────────────────────────────────────────────
        BrandHeader(
            isRunning       = state.isRunning,
            usbCAvailable   = state.usbCAvailable,
            useUsbCInput    = state.useUsbCInput,
            onStartStop     = {
                if (state.isRunning) viewModel.stopMeasurement()
                else viewModel.startMeasurement()
            },
            onClearTape     = { viewModel.clearTape() },
            onToggleUsbC    = { viewModel.setUseUsbCInput(!state.useUsbCInput) }
        )

        // ── 2. BPH status strip ────────────────────────────────────────────
        StatusStrip(
            engineState           = state.engineState,
            lockedBPH             = state.lockedBPH,
            isManual              = state.isManualBPH,
            autoProgress          = state.autoDetectProgress,
            phaseSecondsRemaining = state.phaseSecondsRemaining
        )

        // ── 3. Metrics quadrant ────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .height(96.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RateCard(
                rateSecPerDay = state.rateSecPerDay,
                modifier      = Modifier.weight(1f).fillMaxHeight()
            )
            AmplitudeCard(
                amplitudeDeg   = state.amplitudeDeg,
                amplitudeValid = state.amplitudeValid,
                modifier       = Modifier.weight(1f).fillMaxHeight()
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .height(96.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BeatErrorCard(
                beatErrorMs = state.beatErrorMs,
                modifier    = Modifier.weight(1f).fillMaxHeight()
            )
            MetricsCard(
                label     = "LIFT TIME",
                value     = "%.1f".format(state.liftTimeMs),
                unit      = "MS",
                modifier  = Modifier.weight(1f).fillMaxHeight(),
                isInvalid = state.liftTimeMs == 0f
            )
        }

        Spacer(Modifier.height(10.dp))

        // ── 4. Paper tape ──────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(2.2f)
                .padding(horizontal = 12.dp)
                .border(0.5.dp, KargathraColors.NavyBorder, RoundedCornerShape(3.dp))
                .clip(RoundedCornerShape(3.dp))
        ) {
            PaperTapeView(
                points   = state.tapePoints,
                modifier = Modifier.fillMaxSize()
            )
            SectionLabel("TIMING DEVIATION", Modifier.align(Alignment.TopStart))

            val showOverlay = state.engineState == EngineState.CALIBRATING
                || state.engineState == EngineState.PLACE_WATCH
                || state.engineState == EngineState.DETECTING
            if (showOverlay) {
                DetectingOverlay(
                    phase                 = state.engineState,
                    phaseSecondsRemaining = state.phaseSecondsRemaining,
                    progress              = state.autoDetectProgress
                )
            }
            if (!state.isRunning) {
                IdlePlaceholder()
            }
        }

        Spacer(Modifier.height(6.dp))

        // ── 5. Oscilloscope ────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp)
                .border(0.5.dp, KargathraColors.NavyBorder, RoundedCornerShape(3.dp))
                .clip(RoundedCornerShape(3.dp))
        ) {
            OscilloscopeView(
                waveform = state.waveform,
                modifier = Modifier.fillMaxSize()
            )
            SectionLabel("FILTERED SIGNAL", Modifier.align(Alignment.TopStart))
        }

        Spacer(Modifier.height(8.dp))

        // ── 6. Controls ────────────────────────────────────────────────────
        ControlsPanel(
            selectedBPH          = state.selectedBPH,
            liftAngleDeg         = state.liftAngleDeg,
            thresholdMultiplier  = state.thresholdMultiplier,
            onBPHSelected        = viewModel::setSelectedBPH,
            onLiftAngleChanged   = viewModel::setLiftAngle,
            onThresholdChanged   = viewModel::setThresholdMultiplier
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. Brand header
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BrandHeader(
    isRunning: Boolean,
    usbCAvailable: Boolean,
    useUsbCInput: Boolean,
    onStartStop: () -> Unit,
    onClearTape: () -> Unit,
    onToggleUsbC: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KargathraLogo(size = 38.dp)

        Spacer(Modifier.width(12.dp))

        Column {
            Text(
                text          = "KARGATHRA & CO.",
                color         = KargathraColors.GoldPrimary,
                fontSize      = 16.sp,
                fontFamily    = FontFamily.Serif,
                fontWeight    = FontWeight.Normal,
                letterSpacing = 2.sp
            )
            Text(
                text          = "TIMEGRAPHER",
                color         = KargathraColors.GoldMuted,
                fontSize      = 9.sp,
                letterSpacing = 4.sp
            )
        }

        Spacer(Modifier.weight(1f))

        // Mic source toggle — only shown when USB-C is currently plugged in.
        // When hidden (no USB-C available), the app uses the built-in mic,
        // which is the default.
        if (usbCAvailable) {
            MicSourceToggle(
                useUsbC   = useUsbCInput,
                onToggle  = onToggleUsbC
            )
            Spacer(Modifier.width(8.dp))
        }

        // Clear tape (only while running)
        if (isRunning) {
            IconButton(
                onClick  = onClearTape,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector        = Icons.Default.ClearAll,
                    contentDescription = "Clear tape",
                    tint               = KargathraColors.GoldMuted,
                    modifier           = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
        }

        // Start / Stop — 48dp primary action
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (isRunning) KargathraColors.NavySurface
                    else KargathraColors.GoldPrimary
                )
                .border(
                    1.dp,
                    if (isRunning) KargathraColors.StatusBad else KargathraColors.GoldBright,
                    CircleShape
                )
                .clickable(onClick = onStartStop),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector        = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = if (isRunning) "Stop" else "Start",
                tint               = if (isRunning) KargathraColors.StatusBad else KargathraColors.NavyDeep,
                modifier           = Modifier.size(24.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. BPH status strip
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StatusStrip(
    engineState: EngineState,
    lockedBPH: Int,
    isManual: Boolean,
    autoProgress: Int,
    phaseSecondsRemaining: Int
) {
    val (label, color) = when (engineState) {
        EngineState.IDLE ->
            "READY" to KargathraColors.GoldMuted
        EngineState.CALIBRATING ->
            "CALIBRATING  ·  ${phaseSecondsRemaining}s" to KargathraColors.StatusWarn
        EngineState.PLACE_WATCH ->
            "PLACE WATCH  ·  ${phaseSecondsRemaining}s" to KargathraColors.StatusWarn
        EngineState.DETECTING ->
            "DETECTING BPH  ·  ${phaseSecondsRemaining}s" to KargathraColors.StatusWarn
        EngineState.RUNNING -> when {
            isManual        -> "LOCKED MANUAL  ·  $lockedBPH BPH" to KargathraColors.GoldBright
            lockedBPH > 0   -> "LOCKED AUTO  ·  $lockedBPH BPH" to KargathraColors.GoldPrimary
            else            -> "—" to KargathraColors.GoldMuted
        }
        EngineState.ERROR ->
            "ERROR" to KargathraColors.StatusBad
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(KargathraColors.NavySurface)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text          = label,
            color         = color,
            fontSize      = 11.sp,
            letterSpacing = 2.sp,
            fontWeight    = FontWeight.Medium
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 6. Controls panel
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ControlsPanel(
    selectedBPH: Int,
    liftAngleDeg: Float,
    thresholdMultiplier: Float,
    onBPHSelected: (Int) -> Unit,
    onLiftAngleChanged: (Float) -> Unit,
    onThresholdChanged: (Float) -> Unit
) {
    var advancedOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KargathraColors.NavySurface)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // BPH selector
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text          = "BPH",
                color         = KargathraColors.GoldMuted,
                fontSize      = 11.sp,
                letterSpacing = 2.sp,
                modifier      = Modifier.width(56.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                for (bph in STANDARD_BPH) {
                    val label  = if (bph == 0) "AUTO" else "${bph / 1000}K"
                    val active = selectedBPH == bph
                    Text(
                        text          = label,
                        color         = if (active) KargathraColors.NavyDeep else KargathraColors.GoldMuted,
                        fontSize      = 10.sp,
                        fontWeight    = if (active) FontWeight.Bold else FontWeight.Normal,
                        fontFamily    = FontFamily.Monospace,
                        textAlign     = TextAlign.Center,
                        modifier      = Modifier
                            .weight(1f)
                            .background(
                                if (active) KargathraColors.GoldPrimary
                                else KargathraColors.NavyDeep,
                                RoundedCornerShape(2.dp)
                            )
                            .border(
                                0.5.dp,
                                if (active) KargathraColors.GoldPrimary
                                else KargathraColors.NavyBorder,
                                RoundedCornerShape(2.dp)
                            )
                            .clickable { onBPHSelected(bph) }
                            .padding(vertical = 7.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Lift angle slider
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text          = "LIFT",
                color         = KargathraColors.GoldMuted,
                fontSize      = 11.sp,
                letterSpacing = 2.sp,
                modifier      = Modifier.width(56.dp)
            )
            Slider(
                value         = liftAngleDeg,
                onValueChange = onLiftAngleChanged,
                valueRange    = 35f..70f,
                steps         = 34,
                modifier      = Modifier.weight(1f).height(28.dp),
                colors        = SliderDefaults.colors(
                    thumbColor            = KargathraColors.GoldBright,
                    activeTrackColor      = KargathraColors.GoldPrimary,
                    inactiveTrackColor    = KargathraColors.NavyBorder
                )
            )
            Text(
                text       = "${"%.0f".format(liftAngleDeg)}°",
                color      = KargathraColors.GoldPrimary,
                fontSize   = 13.sp,
                fontFamily = FontFamily.Monospace,
                modifier   = Modifier.width(44.dp).padding(start = 10.dp),
                textAlign  = TextAlign.End
            )
        }

        // Advanced toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { advancedOpen = !advancedOpen }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text          = "ADVANCED",
                color         = KargathraColors.GoldMuted,
                fontSize      = 10.sp,
                letterSpacing = 2.sp
            )
            Icon(
                imageVector        = if (advancedOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint               = KargathraColors.GoldMuted,
                modifier           = Modifier.size(14.dp)
            )
        }

        AnimatedVisibility(visible = advancedOpen) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text          = "THR",
                    color         = KargathraColors.GoldMuted,
                    fontSize      = 11.sp,
                    letterSpacing = 2.sp,
                    modifier      = Modifier.width(56.dp)
                )
                Slider(
                    value         = thresholdMultiplier,
                    onValueChange = onThresholdChanged,
                    valueRange    = 2f..30f,
                    steps         = 55,
                    modifier      = Modifier.weight(1f).height(28.dp),
                    colors        = SliderDefaults.colors(
                        thumbColor         = KargathraColors.StatusWarn,
                        activeTrackColor   = KargathraColors.StatusWarn,
                        inactiveTrackColor = KargathraColors.NavyBorder
                    )
                )
                Text(
                    text       = "×${"%.1f".format(thresholdMultiplier)}",
                    color      = KargathraColors.StatusWarn,
                    fontSize   = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier   = Modifier.width(44.dp).padding(start = 10.dp),
                    textAlign  = TextAlign.End
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Small helpers
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text          = text,
        color         = KargathraColors.GoldMuted,
        fontSize      = 9.sp,
        letterSpacing = 2.sp,
        modifier      = modifier.padding(6.dp)
    )
}

@Composable
private fun DetectingOverlay(
    phase: EngineState,
    phaseSecondsRemaining: Int,
    progress: Int
) {
    val (title, subtitle) = when (phase) {
        EngineState.CALIBRATING -> "LEARNING AMBIENT NOISE" to
            "Hold still — the app is measuring room noise"
        EngineState.PLACE_WATCH -> "PLACE WATCH ON PHONE"  to
            "Position the movement near the bottom mic"
        EngineState.DETECTING   -> "DETECTING BPH"         to
            "Measuring tick rate ($progress beats collected)"
        else                    -> "CALIBRATING"           to ""
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC0B1A2E)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color       = KargathraColors.GoldPrimary,
                modifier    = Modifier.size(36.dp),
                strokeWidth = 2.dp
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text          = title,
                color         = KargathraColors.GoldPrimary,
                fontSize      = 11.sp,
                letterSpacing = 3.sp,
                fontWeight    = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text       = "${phaseSecondsRemaining}s",
                color      = KargathraColors.GoldBright,
                fontSize   = 28.sp,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Light
            )
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text       = subtitle,
                    color      = KargathraColors.CreamMuted,
                    fontSize   = 10.sp,
                    textAlign  = TextAlign.Center,
                    modifier   = Modifier.padding(horizontal = 24.dp)
                )
            }
        }
    }
}

@Composable
private fun IdlePlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text          = "PRESS START TO BEGIN",
            color         = KargathraColors.GoldDim,
            fontSize      = 11.sp,
            letterSpacing = 3.sp
        )
    }
}

@Composable
private fun MicSourceToggle(
    useUsbC: Boolean,
    onToggle: () -> Unit
) {
    // A two-state pill showing the currently active mic source and letting
    // the user flip between them. Shown only when a USB-C mic is connected;
    // when hidden, the app is silently using the built-in mic (REQ-9.3).
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(KargathraColors.NavySurface)
            .border(0.5.dp, KargathraColors.NavyBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Icon(
            imageVector        = if (useUsbC) Icons.Default.Cable else Icons.Default.Mic,
            contentDescription = if (useUsbC) "Using USB-C mic, tap to switch to built-in"
                                 else "Using built-in mic, tap to switch to USB-C",
            tint               = if (useUsbC) KargathraColors.GoldBright else KargathraColors.GoldPrimary,
            modifier           = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text          = if (useUsbC) "USB-C" else "BUILT-IN",
            color         = if (useUsbC) KargathraColors.GoldBright else KargathraColors.GoldPrimary,
            fontSize      = 9.sp,
            letterSpacing = 1.sp,
            fontWeight    = FontWeight.Medium
        )
    }
}
