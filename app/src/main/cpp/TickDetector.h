#pragma once

#include "EngineTypes.h"
#include "BandpassFilter.h"
#include "NoiseFloor.h"
#include "AmplitudeCalculator.h"

#include <array>
#include <functional>
#include <atomic>
#include <cstdint>

// ─────────────────────────────────────────────────────────────────────────────
// TickDetector
//
// DSP detection pipeline (REQ-2.x and REQ-4.1):
//
//   1. Bandpass filter every incoming sample (REQ-2.1)
//   2. Update the rolling RMS noise floor (REQ-2.2)
//   3. Macro detection: fire on threshold crossing → 65ms hold-off (REQ-2.3, REQ-2.4)
//   4. Micro-window: capture ~32ms of |signal| after Unlock, then scan for
//      three local maxima (Unlock, Impulse, Drop) in time order (REQ-4.1)
//   5. On window close, compute lift time, amplitude, and per-beat deviation,
//      then emit a BeatEvent via the callback
// ─────────────────────────────────────────────────────────────────────────────

class TickDetector {
public:
    using BeatCallback = std::function<void(const BeatEvent&)>;

    TickDetector();

    void setBeatCallback(BeatCallback cb) { mBeatCallback = std::move(cb); }
    void setLockedBPH(int bph) noexcept { mLockedBPH = bph; }
    void setLiftAngle(float degrees) noexcept { mLiftAngleDeg = degrees; }
    void setThresholdMultiplier(float mult) noexcept { mThresholdMultiplier = mult; }

    /**
     * Override the macro hold-off duration in samples.
     * Default (kHoldOffSamples = 65ms) suits built-in mic.
     * For piezo contact mic, use ~80ms to mask the mechanical rebound
     * that follows the initial impulse and would otherwise fire a
     * spurious second detection within the same beat cycle.
     */
    void setHoldOffSamples(int samples) noexcept { mHoldOffSamples = samples; }

    /**
     * Reconfigure the bandpass filter at runtime.
     * Called when the user switches between built-in mic and USB-C piezo
     * contact mic, which have different frequency characteristics:
     *   Built-in (airborne):  3000–8000 Hz  — rejects HVAC/speech/traffic
     *   USB-C piezo (contact): 1000–6000 Hz — lower range captures the
     *     mechanical impulse through direct contact; airborne noise isn't
     *     an issue because the piezo only responds to touch.
     * Also resets the noise floor and filter state so the baseline is
     * relearned immediately after the switch.
     */
    void setBandpass(float lowHz, float highHz) noexcept {
        mFilter.configure(static_cast<float>(kSampleRate), lowHz, highHz);
        mNoiseFloor.reset();
    }

    /**
     * Enable or disable beat emission without stopping sample processing.
     * When disabled, the pipeline still updates the noise floor and fills
     * the oscilloscope waveform, but onUnlockDetected() is never called.
     * Used by the phase state machine to suppress triggers during AMBIENT
     * and PLACE_WATCH phases.
     */
    void setTriggersEnabled(bool enabled) noexcept { mTriggersEnabled = enabled; }

    /**
     * Lock a baseline noise level learned during the AMBIENT phase.
     * After this is set, detection triggers when the instantaneous filtered
     * energy exceeds max(rollingRMS, ambient) × threshold_multiplier.
     * This prevents the watch's own ticking from raising the "noise floor"
     * high enough that subsequent ticks don't trigger.
     * Pass 0.0f to revert to pure rolling RMS.
     */
    void setAmbientBaseline(float ambientRMS) noexcept { mAmbientBaseline = ambientRMS; }

    /** Access the current rolling RMS for phase-transition bookkeeping. */
    float getCurrentRMS() const noexcept { return mLastNoiseFloor; }

    // Called for every audio sample (hot path)
    void processSample(float rawSample, int64_t sampleTimeNs) noexcept;

    void reset() noexcept;

    void getWaveformSnapshot(WaveformSnapshot& out) const noexcept;

private:
    // ── Sub-systems ──────────────────────────────────────────────────────────
    BandpassFilter      mFilter;
    RollingRMS          mNoiseFloor;

    // ── Macro state ──────────────────────────────────────────────────────────
    int                 mHoldOffRemaining = 0;     // samples
    bool                mNextIsTock       = false;
    int64_t             mLastMacroTickNs  = -1;

    // ── Micro-window state ────────────────────────────────────────────────────
    // Capture the absolute filtered signal over ~32ms after each Unlock,
    // then analyse the captured buffer to find three ordered peaks.
    bool                mMicroWindowOpen       = false;
    int                 mMicroSamplesCollected = 0;
    int64_t             mUnlockTimeNs          = 0;
    std::array<float, kMicroWindowSamples>   mMicroBuffer{};

    // ── Configuration ─────────────────────────────────────────────────────────
    int                 mLockedBPH           = 28800;
    float               mLiftAngleDeg        = kDefaultLiftAngle;
    float               mThresholdMultiplier = kThresholdMultiplier;
    int                 mHoldOffSamples      = kHoldOffSamples; // overridable per mic type
    bool                mTriggersEnabled     = false;   // Gated by phase state machine
    float               mAmbientBaseline     = 0.0f;    // Learned during AMBIENT phase

    // ── Waveform ring buffer (for UI oscilloscope) ────────────────────────────
    static constexpr int kWaveRingSize = WaveformSnapshot::kSize * 4;
    float               mWaveRing[kWaveRingSize] = {};
    std::atomic<int>    mWaveWriteIdx{0};
    float               mLastNoiseFloor = 0.0f;
    float               mLastThreshold  = 0.0f;
    std::atomic<int>    mLastTriggerWaveIdx{-1};

    // ── Callback ──────────────────────────────────────────────────────────────
    BeatCallback        mBeatCallback;

    // ── Internal helpers ──────────────────────────────────────────────────────
    void onUnlockDetected(int64_t timestampNs, float firstSample) noexcept;
    void closeMicroWindowAndEmit() noexcept;
    float computeDeviationMs(int64_t tickNs) const noexcept;
};
