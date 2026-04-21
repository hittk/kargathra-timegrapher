package com.kargathra.timegrapher.audio

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Kotlin wrapper for the native C++ AudioEngine.
 *
 * Lifecycle: create() → start() → [measure] → stop() → destroy()
 *
 * Beat events arrive via native callback (onNativeBeat) and are published
 * on [beatEvents] as a SharedFlow that the ViewModel collects.
 *
 * Status updates arrive via onNativeStatus and are published on [statusEvents].
 */
class AudioEngineJNI {

    private var handle: Long = 0L
    private var isCreated = false

    // AudioEffect handles for REQ-1.2 (explicitly disable AEC/AGC/NS)
    private var aec: AcousticEchoCanceler?  = null
    private var agc: AutomaticGainControl?  = null
    private var ns:  NoiseSuppressor?       = null

    // Beat events pushed by C++ via onNativeBeat()
    private val _beatEvents = MutableSharedFlow<BeatEvent>(
        replay         = 0,
        extraBufferCapacity = 128,
        onBufferOverflow    = BufferOverflow.DROP_OLDEST
    )
    val beatEvents: SharedFlow<BeatEvent> = _beatEvents.asSharedFlow()

    private val _statusEvents = MutableSharedFlow<EngineStatus>(
        replay = 1,
        extraBufferCapacity = 16,
        onBufferOverflow    = BufferOverflow.DROP_OLDEST
    )
    val statusEvents: SharedFlow<EngineStatus> = _statusEvents.asSharedFlow()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun create() {
        if (!isCreated) {
            handle = nativeCreate()
            isCreated = true
        }
    }

    fun destroy() {
        if (isCreated) {
            releaseAudioEffects()
            nativeDestroy(handle)
            handle = 0L
            isCreated = false
        }
    }

    fun start(deviceId: Int = 0): Boolean {
        check(isCreated) { "AudioEngine not created" }
        val started = nativeStart(handle, deviceId)
        if (started) applyAudioEffectDisable()
        return started
    }

    fun stop() {
        if (isCreated) {
            releaseAudioEffects()
            nativeStop(handle)
        }
    }

    // ── REQ-1.2: Disable AEC/AGC/NS via AudioEffect API on the Oboe session ──
    private fun applyAudioEffectDisable() {
        val sessionId = nativeGetSessionId(handle)
        if (sessionId < 0) {
            Log.w(TAG, "No valid session ID — AEC/AGC disable skipped")
            return
        }

        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = false }
                Log.i(TAG, "AEC disabled on session $sessionId")
            }
        } catch (t: Throwable) { Log.w(TAG, "AEC disable failed: $t") }

        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId)?.apply { enabled = false }
                Log.i(TAG, "AGC disabled on session $sessionId")
            }
        } catch (t: Throwable) { Log.w(TAG, "AGC disable failed: $t") }

        try {
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sessionId)?.apply { enabled = false }
                Log.i(TAG, "NoiseSuppressor disabled on session $sessionId")
            }
        } catch (t: Throwable) { Log.w(TAG, "NS disable failed: $t") }
    }

    private fun releaseAudioEffects() {
        aec?.release(); aec = null
        agc?.release(); agc = null
        ns?.release();  ns  = null
    }

    // ── Configuration ─────────────────────────────────────────────────────────

    /** Set manual BPH. Pass 0 to re-enable AUTO detection. */
    fun setManualBPH(bph: Int) { if (isCreated) nativeSetManualBPH(handle, bph) }

    /** Set lift angle in degrees (35–70). */
    fun setLiftAngle(degrees: Float) { if (isCreated) nativeSetLiftAngle(handle, degrees) }

    /** Set noise floor threshold multiplier (default 10.0). */
    fun setThresholdMultiplier(mult: Float) {
        if (isCreated) nativeSetThresholdMultiplier(handle, mult)
    }

    /**
     * Apply the correct bandpass filter profile for the selected mic source
     * without touching the audio stream. Call before start().
     *   isUsbC=true  → piezo contact profile (1–6 kHz)
     *   isUsbC=false → built-in airborne profile (3–8 kHz)
     */
    fun setBandpassProfile(isUsbC: Boolean) {
        if (isCreated) nativeSetBandpassProfile(handle, isUsbC)
    }

    // ── Input device routing ─────────────────────────────────────────────────

    fun onDeviceAdded(deviceId: Int, isUsbC: Boolean) {
        if (isCreated) nativeOnDeviceAdded(handle, deviceId, isUsbC)
    }

    fun onDeviceRemoved(deviceId: Int) {
        if (isCreated) nativeOnDeviceRemoved(handle, deviceId)
    }

    /**
     * User-initiated routing switch (REQ-9.3).
     * Pass 0 for built-in microphone, or a specific Oboe device ID for USB-C.
     * [isUsbC] tells the engine which bandpass profile to apply:
     *   true  → piezo contact mic profile (1–6 kHz)
     *   false → built-in airborne mic profile (3–8 kHz)
     */
    fun requestRoutingSwitch(deviceId: Int, isUsbC: Boolean): Boolean =
        isCreated && nativeRequestRoutingSwitch(handle, deviceId, isUsbC)

    // ── Polling (UI thread) ───────────────────────────────────────────────────

    fun getWaveform(): WaveformData {
        val raw = nativeGetWaveform(handle)
        return if (raw.size < 3) {
            WaveformData(FloatArray(0), 0f, 0f, -1)
        } else {
            WaveformData(
                samples         = raw.sliceArray(3 until raw.size),
                noiseFloorRMS   = raw[0],
                triggerThreshold = raw[1],
                triggerMarkerIdx = raw[2].toInt()
            )
        }
    }

    fun getStatus(): EngineStatus {
        val raw = nativeGetStatus(handle)
        return if (raw.size < 5) {
            EngineStatus(EngineState.IDLE, 0, 0, 0, false)
        } else {
            EngineStatus(
                state                 = EngineState.fromInt(raw[0]),
                lockedBPH             = raw[1],
                detectedTickCount     = raw[2],
                phaseSecondsRemaining = raw[3],
                isManualBPH           = raw[4] != 0
            )
        }
    }

    // ── Native callback targets (called from C++ dispatch worker thread) ──────
    // Keep these non-blocking: push into a flow and return.

    @Suppress("unused") // Called from native
    fun onNativeBeat(
        timestampNs: Long,
        deviationMs: Float,
        amplitudeDeg: Float,
        liftTimeMs: Float,
        isTock: Boolean,
        amplitudeValid: Boolean
    ) {
        val event = BeatEvent(
            timestampNs    = timestampNs,
            deviationMs    = deviationMs,
            amplitudeDeg   = amplitudeDeg,
            liftTimeMs     = liftTimeMs,
            isTock         = isTock,
            amplitudeValid = amplitudeValid
        )
        // tryEmit is non-blocking; on buffer overflow the oldest event is dropped.
        _beatEvents.tryEmit(event)
    }

    @Suppress("unused") // Called from native
    fun onNativeStatus(
        state: Int,
        lockedBPH: Int,
        detectedTickCount: Int,
        phaseSecondsRemaining: Int,
        isManual: Boolean
    ) {
        _statusEvents.tryEmit(
            EngineStatus(
                state                 = EngineState.fromInt(state),
                lockedBPH             = lockedBPH,
                detectedTickCount     = detectedTickCount,
                phaseSecondsRemaining = phaseSecondsRemaining,
                isManualBPH           = isManual
            )
        )
    }

    // ── JNI declarations ──────────────────────────────────────────────────────

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeStart(handle: Long, deviceId: Int): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativeGetSessionId(handle: Long): Int
    private external fun nativeSetManualBPH(handle: Long, bph: Int)
    private external fun nativeSetLiftAngle(handle: Long, degrees: Float)
    private external fun nativeSetThresholdMultiplier(handle: Long, mult: Float)
    private external fun nativeSetBandpassProfile(handle: Long, isUsbC: Boolean)
    private external fun nativeOnDeviceAdded(handle: Long, deviceId: Int, isUsbC: Boolean)
    private external fun nativeOnDeviceRemoved(handle: Long, deviceId: Int)
    private external fun nativeRequestRoutingSwitch(handle: Long, deviceId: Int, isUsbC: Boolean): Boolean
    private external fun nativeGetWaveform(handle: Long): FloatArray
    private external fun nativeGetStatus(handle: Long): IntArray

    companion object {
        private const val TAG = "AudioEngineJNI"
        init { System.loadLibrary("kargathra_engine") }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Data classes
// ─────────────────────────────────────────────────────────────────────────────

data class WaveformData(
    val samples: FloatArray,
    val noiseFloorRMS: Float,
    val triggerThreshold: Float,
    val triggerMarkerIdx: Int    // -1 = no recent trigger in window
) {
    // FloatArray equality handling for Compose recomposition
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WaveformData) return false
        return noiseFloorRMS == other.noiseFloorRMS &&
               triggerThreshold == other.triggerThreshold &&
               triggerMarkerIdx == other.triggerMarkerIdx &&
               samples.contentEquals(other.samples)
    }
    override fun hashCode(): Int {
        var r = samples.contentHashCode()
        r = 31 * r + noiseFloorRMS.hashCode()
        r = 31 * r + triggerThreshold.hashCode()
        r = 31 * r + triggerMarkerIdx
        return r
    }
}

enum class EngineState(val value: Int) {
    IDLE(0),
    CALIBRATING(1),    // Phase 1: learning ambient noise (5s)
    PLACE_WATCH(2),    // Phase 2: user placing watch (3s)
    DETECTING(3),      // Phase 3: auto-detecting BPH (5s)
    RUNNING(4),        // Locked and measuring
    ERROR(5);
    companion object {
        fun fromInt(v: Int) = entries.firstOrNull { it.value == v } ?: IDLE
    }
}

data class EngineStatus(
    val state: EngineState,
    val lockedBPH: Int,
    val detectedTickCount: Int,
    val phaseSecondsRemaining: Int,
    val isManualBPH: Boolean
)

data class BeatEvent(
    val timestampNs: Long,
    val deviationMs: Float,    // Per-beat deviation (not cumulative)
    val amplitudeDeg: Float,
    val liftTimeMs: Float,
    val isTock: Boolean,
    val amplitudeValid: Boolean
)
