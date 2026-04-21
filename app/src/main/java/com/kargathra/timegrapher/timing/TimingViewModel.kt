package com.kargathra.timegrapher.timing

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kargathra.timegrapher.audio.AudioDeviceMonitor
import com.kargathra.timegrapher.audio.AudioEngineJNI
import com.kargathra.timegrapher.audio.BeatEvent
import com.kargathra.timegrapher.audio.EngineState
import com.kargathra.timegrapher.audio.EngineStatus
import com.kargathra.timegrapher.audio.WaveformData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val TAG = "TimingViewModel"

// Standard BPH values (REQ-3.2), 0 = AUTO sentinel
val STANDARD_BPH = listOf(0, 14400, 18000, 19800, 21600, 25200, 28800, 36000)

data class TapePoint(
    val deviationMs: Float,
    val isTock: Boolean,
    val timestampNs: Long
)

data class MeasurementState(
    val isRunning: Boolean              = false,
    val engineState: EngineState        = EngineState.IDLE,
    val phaseSecondsRemaining: Int      = 0,
    val errorMessage: String?           = null,

    val lockedBPH: Int                  = 0,
    val selectedBPH: Int                = 0,
    val isManualBPH: Boolean            = false,
    val autoDetectProgress: Int         = 0,

    val rateSecPerDay: Float            = 0f,
    val beatErrorMs: Float              = 0f,
    val amplitudeDeg: Float             = 0f,
    val amplitudeValid: Boolean         = false,
    val liftTimeMs: Float               = 0f,

    val liftAngleDeg: Float             = 53f,
    val thresholdMultiplier: Float      = 10f,

    val tapePoints: List<TapePoint>     = emptyList(),
    val waveform: WaveformData?         = null,

    // Input source state (REQ-9.3)
    val usbCAvailable: Boolean          = false,    // Is a USB-C mic currently plugged in?
    val useUsbCInput: Boolean           = false,    // User's current preference (default: false = built-in)
)

class TimingViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(MeasurementState())
    val state: StateFlow<MeasurementState> = _state.asStateFlow()

    private val engine = AudioEngineJNI()
    private lateinit var deviceMonitor: AudioDeviceMonitor

    // Rolling window of recent beats for rate/beat-error computation
    private val recentBeats = ArrayDeque<BeatEvent>()
    private val maxBuffered     = 64
    private val maxTapePoints   = 300

    private var beatConsumerJob: Job? = null
    private var statusConsumerJob: Job? = null
    private var waveformPollJob: Job? = null

    // Timestamp of the last time metric cards (Rate/Amplitude/BeatError/LiftTime)
    // were pushed to state. Metrics only update every 3 seconds to keep the
    // display readable. Tape points still accumulate on every beat.
    @Volatile private var lastMetricUpdateMs: Long = 0L
    private val metricUpdateIntervalMs = 3_000L

    init {
        engine.create()
        // Seed USB-C availability immediately so the mic toggle button
        // appears in the header even before the user presses Start.
        val audioManager = getApplication<Application>()
            .getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val usbCPresent = audioManager
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .any { it.isSource && AudioDeviceMonitor.isUsbCDevice(it) }
        if (usbCPresent) _state.update { it.copy(usbCAvailable = true) }
    }

    override fun onCleared() {
        super.onCleared()
        stopMeasurement()
        engine.destroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Start / Stop
    // ─────────────────────────────────────────────────────────────────────────

    fun startMeasurement() {
        if (_state.value.isRunning) return

        deviceMonitor = AudioDeviceMonitor(
            context             = getApplication(),
            engineJNI           = engine,
            onUsbCAvailable     = { _state.update { it.copy(usbCAvailable = true) } },
            onUsbCUnavailable   = { _state.update {
                // If the user was routed through USB-C, flip them back to built-in
                // (the engine-side handler does the actual stream switch).
                it.copy(usbCAvailable = false, useUsbCInput = false)
            } }
        )
        deviceMonitor.register()

        // Respect the user's current input choice. Default is built-in mic.
        val deviceId = deviceMonitor.selectInputDevice(preferUsbC = _state.value.useUsbCInput)

        // Also seed the initial availability flag, in case the user launched
        // with a USB-C device already plugged in.
        _state.update { it.copy(usbCAvailable = deviceMonitor.isUsbCAvailable()) }

        engine.setLiftAngle(_state.value.liftAngleDeg)
        engine.setThresholdMultiplier(_state.value.thresholdMultiplier)
        if (_state.value.selectedBPH != 0) {
            engine.setManualBPH(_state.value.selectedBPH)
        }

        // Apply correct bandpass profile BEFORE opening the stream so the
        // filter is configured from the very first sample.
        engine.setBandpassProfile(_state.value.useUsbCInput)

        val started = engine.start(deviceId)
        if (!started) {
            _state.update { it.copy(errorMessage = "Failed to open audio stream") }
            return
        }

        _state.update {
            it.copy(
                isRunning       = true,
                engineState     = EngineState.CALIBRATING,
                phaseSecondsRemaining = 5,
                errorMessage    = null,
                tapePoints      = emptyList(),
                rateSecPerDay   = 0f,
                beatErrorMs     = 0f,
                amplitudeDeg    = 0f,
                autoDetectProgress = 0
            )
        }
        recentBeats.clear()
        lastMetricUpdateMs = 0L

        startConsumers()
        Log.i(TAG, "Measurement started on device $deviceId (useUsbC=${_state.value.useUsbCInput})")
    }

    fun stopMeasurement() {
        beatConsumerJob?.cancel();    beatConsumerJob = null
        statusConsumerJob?.cancel();  statusConsumerJob = null
        waveformPollJob?.cancel();    waveformPollJob = null

        engine.stop()
        if (::deviceMonitor.isInitialized) deviceMonitor.unregister()
        _state.update { it.copy(isRunning = false, engineState = EngineState.IDLE) }
        Log.i(TAG, "Measurement stopped")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Consumer coroutines (single long-lived, replaces per-beat launch)
    // ─────────────────────────────────────────────────────────────────────────

    private fun startConsumers() {
        // Beat consumer
        beatConsumerJob = viewModelScope.launch(Dispatchers.Default) {
            engine.beatEvents.collect { event ->
                handleBeat(event)
            }
        }

        // Status consumer
        statusConsumerJob = viewModelScope.launch(Dispatchers.Default) {
            engine.statusEvents.collect { status ->
                _state.update {
                    it.copy(
                        engineState           = status.state,
                        phaseSecondsRemaining = status.phaseSecondsRemaining,
                        lockedBPH             = status.lockedBPH,
                        isManualBPH           = status.isManualBPH,
                        autoDetectProgress    = status.detectedTickCount
                    )
                }
            }
        }

        // Waveform poll (for oscilloscope — cheap, no beat events to miss)
        waveformPollJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                val wave   = engine.getWaveform()
                val status = engine.getStatus()
                _state.update {
                    it.copy(
                        waveform              = wave,
                        engineState           = status.state,
                        phaseSecondsRemaining = status.phaseSecondsRemaining,
                        lockedBPH             = status.lockedBPH,
                        isManualBPH           = status.isManualBPH,
                        autoDetectProgress    = status.detectedTickCount
                    )
                }
                delay(66L)   // ~15fps for oscilloscope and tape (half previous rate)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Beat event processing
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleBeat(event: BeatEvent) {
        recentBeats.addLast(event)
        while (recentBeats.size > maxBuffered) recentBeats.removeFirst()

        // Always add the tape point so the graph history is complete.
        val newPoint = TapePoint(
            deviationMs = event.deviationMs,
            isTock      = event.isTock,
            timestampNs = event.timestampNs
        )

        val now = System.currentTimeMillis()
        val shouldUpdateMetrics = (now - lastMetricUpdateMs) >= metricUpdateIntervalMs

        if (shouldUpdateMetrics) {
            lastMetricUpdateMs = now
            val rate      = calculateRateSecPerDay()
            val beatError = calculateBeatErrorMs()
            val amplitude = calculateSmoothedAmplitude()

            _state.update { current ->
                val updatedTape = (current.tapePoints + newPoint).takeLast(maxTapePoints)
                current.copy(
                    rateSecPerDay  = rate,
                    beatErrorMs    = beatError,
                    amplitudeDeg   = amplitude,
                    amplitudeValid = event.amplitudeValid,
                    liftTimeMs     = event.liftTimeMs,
                    tapePoints     = updatedTape
                )
            }
        } else {
            // Metrics unchanged — only append the tape point.
            _state.update { current ->
                current.copy(tapePoints = (current.tapePoints + newPoint).takeLast(maxTapePoints))
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Metrics (M7 / M8 fixes)
    //
    // deviationMs is now per-beat, not cumulative. Rate is derived from the
    // slope of actual-vs-ideal intervals across recent beats.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Rate in seconds per day.
     *
     * Method:
     *   1. Compute all consecutive beat intervals.
     *   2. Find the median interval (robust to outliers).
     *   3. Discard any beat whose interval to its predecessor is >20% away
     *      from the median — these are double-triggers, missed beats, or
     *      mic handling noise.
     *   4. Compare total actual time of clean beats to ideal time for that
     *      many beats → drift ratio → seconds/day.
     *
     * This makes the rate stable even when 1–2 spurious beats slip through
     * the DSP threshold, which is common with contact piezo mics.
     */
    private fun calculateRateSecPerDay(): Float {
        val beats = recentBeats.toList()
        if (beats.size < 8) return 0f

        val bph = _state.value.lockedBPH.takeIf { it > 0 } ?: return 0f
        val idealIntervalMs = 3600_000f / bph.toFloat()

        // Compute all consecutive intervals in ms
        val intervals = (1 until beats.size).map { i ->
            (beats[i].timestampNs - beats[i - 1].timestampNs) / 1_000_000f
        }

        // Median interval — sort a copy, take middle value
        val sortedIntervals = intervals.sorted()
        val medianMs = sortedIntervals[sortedIntervals.size / 2]

        // Keep only beats where interval to previous is within ±20% of median.
        // Rebuild a filtered list of beats starting from index 0 (always kept).
        val cleanBeats = mutableListOf(beats[0])
        for (i in 1 until beats.size) {
            val intervalMs = (beats[i].timestampNs - beats[i - 1].timestampNs) / 1_000_000f
            if (kotlin.math.abs(intervalMs - medianMs) / medianMs <= 0.20f) {
                cleanBeats.add(beats[i])
            }
        }

        if (cleanBeats.size < 8) return 0f

        val totalActualSec  = (cleanBeats.last().timestampNs - cleanBeats.first().timestampNs) / 1e9f
        val totalIdealSec   = (idealIntervalMs / 1000f) * (cleanBeats.size - 1)
        if (totalIdealSec <= 0f) return 0f

        val drift = (totalIdealSec - totalActualSec) / totalIdealSec
        return drift * 86400f
    }

    /**
     * Beat error in milliseconds (M8 fix — computed directly from timestamps,
     * independent of deviationMs).
     *
     * Beat error = |mean(tick→tock interval) − mean(tock→tick interval)|
     */
    private fun calculateBeatErrorMs(): Float {
        val beats = recentBeats.toList()
        if (beats.size < 4) return 0f

        var tickToTockSum = 0.0; var tickToTockCount = 0
        var tockToTickSum = 0.0; var tockToTickCount = 0

        for (i in 1 until beats.size) {
            val prev = beats[i - 1]
            val curr = beats[i]
            val intervalMs = (curr.timestampNs - prev.timestampNs) / 1_000_000.0

            // If prev was tick (isTock=false), this is a tick→tock transition
            if (!prev.isTock && curr.isTock) {
                tickToTockSum += intervalMs; tickToTockCount++
            } else if (prev.isTock && !curr.isTock) {
                tockToTickSum += intervalMs; tockToTickCount++
            }
        }

        if (tickToTockCount == 0 || tockToTickCount == 0) return 0f

        val meanTickToTock = tickToTockSum / tickToTockCount
        val meanTockToTick = tockToTickSum / tockToTickCount
        return abs(meanTickToTock - meanTockToTick).toFloat()
    }

    /** Smoothed amplitude: mean of last 8 VALID readings (REQ-4.2). */
    private fun calculateSmoothedAmplitude(): Float {
        val validAmplitudes = recentBeats
            .filter { it.amplitudeValid }
            .takeLast(8)
            .map { it.amplitudeDeg }
        return if (validAmplitudes.isEmpty()) 0f
        else validAmplitudes.average().toFloat()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Settings changes
    // ─────────────────────────────────────────────────────────────────────────

    fun setSelectedBPH(bph: Int) {
        _state.update { it.copy(selectedBPH = bph) }
        if (_state.value.isRunning) {
            engine.setManualBPH(bph)
            if (bph == 0) {
                recentBeats.clear()
                _state.update { it.copy(tapePoints = emptyList(), autoDetectProgress = 0) }
            }
        }
    }

    fun setLiftAngle(degrees: Float) {
        val clamped = degrees.coerceIn(35f, 70f)
        _state.update { it.copy(liftAngleDeg = clamped) }
        engine.setLiftAngle(clamped)
    }

    fun setThresholdMultiplier(mult: Float) {
        _state.update { it.copy(thresholdMultiplier = mult) }
        engine.setThresholdMultiplier(mult)
    }

    fun clearTape() {
        recentBeats.clear()
        _state.update { it.copy(tapePoints = emptyList()) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Input source selection (REQ-9.3)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Toggle between built-in microphone (default) and USB-C audio input.
     *
     * If measurement is running, the audio stream is torn down and reopened
     * targeting the newly-selected device, preserving detection state.
     * If measurement is idle, we just remember the preference for the next
     * [startMeasurement] call.
     */
    fun setUseUsbCInput(useUsbC: Boolean) {
        // No-op if user picks USB-C but none is plugged in
        if (useUsbC && !_state.value.usbCAvailable) {
            Log.w(TAG, "USB-C requested but none available — ignoring")
            return
        }
        if (useUsbC == _state.value.useUsbCInput) return

        _state.update { it.copy(useUsbCInput = useUsbC) }

        if (_state.value.isRunning) {
            val newDeviceId = if (useUsbC) {
                deviceMonitor.selectInputDevice(preferUsbC = true)
            } else {
                0   // Built-in
            }
            engine.requestRoutingSwitch(newDeviceId, useUsbC)
            Log.i(TAG, "Input switched to ${if (useUsbC) "USB-C piezo" else "built-in mic"}")
        }
    }
}
