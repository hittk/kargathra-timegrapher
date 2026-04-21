#include "TickDetector.h"
#include "AmplitudeCalculator.h"

#include <cmath>
#include <algorithm>
#include <android/log.h>

#define TAG "TickDetector"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

TickDetector::TickDetector()
    : mFilter()
    , mNoiseFloor()
{}

void TickDetector::reset() noexcept {
    mFilter.reset();
    mNoiseFloor.reset();
    mHoldOffRemaining       = 0;
    mMicroWindowOpen        = false;
    mMicroSamplesCollected  = 0;
    mNextIsTock             = false;
    mLastMacroTickNs        = -1;
    mUnlockTimeNs           = 0;
    mLastNoiseFloor         = 0.0f;
    mLastThreshold          = 0.0f;
    mMicroBuffer.fill(0.0f);
    mWaveWriteIdx.store(0, std::memory_order_relaxed);
    mLastTriggerWaveIdx.store(-1, std::memory_order_relaxed);
}

// ─────────────────────────────────────────────────────────────────────────────
// Hot path — one sample per call, runs on the audio thread
// ─────────────────────────────────────────────────────────────────────────────
void TickDetector::processSample(float rawSample, int64_t sampleTimeNs) noexcept {
    // 1. Bandpass filter (REQ-2.1)
    const float filtered = mFilter.process(rawSample);
    const float energy   = std::fabs(filtered);

    // 2. Update rolling noise floor (REQ-2.2).
    //    During normal operation this tracks the current audio ambient.
    //    During AMBIENT phase, AudioEngine will read this value to learn
    //    the baseline room noise level.
    const float rollingRMS = mNoiseFloor.update(filtered);

    // 3. Compute trigger threshold.
    //    Use the max of (rolling RMS, ambient baseline) so that once a watch
    //    is placed near the mic, its own ticking can't raise the rolling RMS
    //    high enough to suppress subsequent triggers. The ambient baseline
    //    is locked at the end of the AMBIENT phase and represents the true
    //    room noise with no watch present.
    const float floor     = std::max(rollingRMS, mAmbientBaseline);
    const float threshold = floor * mThresholdMultiplier;
    mLastNoiseFloor = floor;
    mLastThreshold  = threshold;

    // 4. Write to waveform ring buffer for oscilloscope UI.
    //    Always done — the oscilloscope is useful during calibration too,
    //    so the user can confirm the watch is being picked up.
    const int wIdx = mWaveWriteIdx.load(std::memory_order_relaxed);
    mWaveRing[wIdx] = filtered;
    mWaveWriteIdx.store((wIdx + 1) % kWaveRingSize, std::memory_order_release);

    // 5. Collect into micro-window if open (REQ-4.1)
    if (mMicroWindowOpen) {
        mMicroBuffer[mMicroSamplesCollected++] = energy;
        if (mMicroSamplesCollected >= kMicroWindowSamples) {
            closeMicroWindowAndEmit();
        }
    }

    // 6. Macro hold-off (REQ-2.4)
    if (mHoldOffRemaining > 0) {
        --mHoldOffRemaining;
        return;
    }

    // 7. Triggers gated by phase state machine — noise floor must be warm,
    //    triggers must be enabled (set only in DETECTING/RUNNING phases),
    //    and we need a valid baseline (either rolling or ambient).
    if (!mTriggersEnabled) return;
    if (!mNoiseFloor.isWarm()) return;
    if (floor <= 0.0f) return;

    if (energy > threshold) {
        onUnlockDetected(sampleTimeNs, energy);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Unlock detected — arm hold-off and micro-window
// ─────────────────────────────────────────────────────────────────────────────
void TickDetector::onUnlockDetected(int64_t timestampNs, float firstSample) noexcept {
    mHoldOffRemaining      = mHoldOffSamples;
    mMicroWindowOpen       = true;
    mMicroSamplesCollected = 0;
    mUnlockTimeNs          = timestampNs;
    mMicroBuffer.fill(0.0f);
    // The Unlock sample itself is the first entry in the buffer
    mMicroBuffer[mMicroSamplesCollected++] = firstSample;

    mLastTriggerWaveIdx.store(
        mWaveWriteIdx.load(std::memory_order_relaxed),
        std::memory_order_release);
}

// ─────────────────────────────────────────────────────────────────────────────
// Micro-window analysis: find Unlock/Impulse/Drop as three ordered local maxima
// (M5 fix — replaces the fragile rising/falling heuristic)
// ─────────────────────────────────────────────────────────────────────────────
void TickDetector::closeMicroWindowAndEmit() noexcept {
    mMicroWindowOpen = false;

    const int n = mMicroSamplesCollected;
    if (n < 8) {
        // Not enough data — emit with invalid amplitude
        BeatEvent e{};
        e.timestampNs    = mUnlockTimeNs;
        e.deviationMs    = computeDeviationMs(mUnlockTimeNs);
        e.amplitudeDeg   = 0.0f;
        e.liftTimeMs     = 0.0f;
        e.isTock         = mNextIsTock;
        e.amplitudeValid = false;
        mNextIsTock      = !mNextIsTock;
        mLastMacroTickNs = mUnlockTimeNs;
        if (mBeatCallback) mBeatCallback(e);
        return;
    }

    // Threshold for what counts as a "real" peak inside the micro-window:
    // must be at least 30% of the dynamic threshold (lower than macro threshold
    // because the Impulse/Drop are often smaller than the Unlock).
    const float peakThreshold = mLastThreshold * 0.3f;

    // Find all local maxima in the energy signal.
    // A sample i is a local max if energy[i] > energy[i-1] && energy[i] > energy[i+1]
    // and energy[i] exceeds the minimum peak threshold.
    // We also enforce a minimum inter-peak gap (about 2ms = 96 samples) to
    // avoid counting ringing from a single impact as multiple peaks.
    struct Peak { int idx; float energy; };
    std::array<Peak, 16> peaks{};
    int peakCount = 0;
    const int minPeakGap = 96;  // ~2ms at 48kHz

    int lastPeakIdx = -minPeakGap;
    for (int i = 1; i < n - 1 && peakCount < 16; ++i) {
        const float e = mMicroBuffer[i];
        if (e > peakThreshold &&
            e >= mMicroBuffer[i - 1] &&
            e >= mMicroBuffer[i + 1] &&
            (i - lastPeakIdx) >= minPeakGap)
        {
            peaks[peakCount++] = { i, e };
            lastPeakIdx = i;
        }
    }

    BeatEvent e{};
    e.timestampNs = mUnlockTimeNs;
    e.deviationMs = computeDeviationMs(mUnlockTimeNs);  // Per-beat, not cumulative
    e.isTock      = mNextIsTock;

    // We need at least 3 peaks for valid amplitude. The three earliest
    // (by index order) are: Unlock, Impulse, Drop.
    if (peakCount >= 3) {
        const int unlockIdx = peaks[0].idx;
        const int dropIdx   = peaks[2].idx;   // Third peak in time order
        const int liftSamp  = dropIdx - unlockIdx;
        const float liftSec = static_cast<float>(liftSamp) / static_cast<float>(kSampleRate);

        const auto amp = AmplitudeCalculator::calculate(liftSec, mLockedBPH, mLiftAngleDeg);
        e.liftTimeMs     = liftSec * 1000.0f;
        e.amplitudeDeg   = amp.amplitudeDeg;
        e.amplitudeValid = amp.valid;
    } else {
        e.liftTimeMs     = 0.0f;
        e.amplitudeDeg   = 0.0f;
        e.amplitudeValid = false;
    }

    mNextIsTock      = !mNextIsTock;
    mLastMacroTickNs = mUnlockTimeNs;

    if (mBeatCallback) mBeatCallback(e);
}

// ─────────────────────────────────────────────────────────────────────────────
// Per-beat deviation (M7 fix: no longer accumulated)
//
// Returns the deviation in ms of THIS beat's inter-beat interval from the
// ideal interval for the locked BPH. Positive = fast (beat arrived early),
// negative = slow. Returns 0 if no previous beat is known yet.
// ─────────────────────────────────────────────────────────────────────────────
float TickDetector::computeDeviationMs(int64_t tickNs) const noexcept {
    if (mLastMacroTickNs < 0 || mLockedBPH <= 0) return 0.0f;

    const float idealIntervalMs  = (3600.0f / static_cast<float>(mLockedBPH)) * 1000.0f;
    const float actualIntervalMs = static_cast<float>(tickNs - mLastMacroTickNs) * 1e-6f;

    // Note: a single beat's deviation oscillates between tick and tock if
    // beat error is present. That's correct behaviour — the Kotlin rate
    // calculation should use linear regression over many beats to extract
    // the underlying rate from this alternating pattern.
    return idealIntervalMs - actualIntervalMs;
}

// ─────────────────────────────────────────────────────────────────────────────
// UI thread: fill WaveformSnapshot (lock-free ring read)
// ─────────────────────────────────────────────────────────────────────────────
void TickDetector::getWaveformSnapshot(WaveformSnapshot& out) const noexcept {
    const int writeIdx = mWaveWriteIdx.load(std::memory_order_acquire);
    const int count    = WaveformSnapshot::kSize;

    for (int i = 0; i < count; ++i) {
        const int ringIdx = (writeIdx - count + i + kWaveRingSize) % kWaveRingSize;
        out.samples[i] = mWaveRing[ringIdx];
    }
    out.validSamples     = count;
    out.noiseFloorRMS    = mLastNoiseFloor;
    out.triggerThreshold = mLastThreshold;

    const int trigIdx = mLastTriggerWaveIdx.load(std::memory_order_acquire);
    if (trigIdx >= 0) {
        const int bufStart = (writeIdx - count + kWaveRingSize) % kWaveRingSize;
        const int relative = (trigIdx - bufStart + kWaveRingSize) % kWaveRingSize;
        out.triggerMarkerIdx = (relative < count) ? relative : -1;
    } else {
        out.triggerMarkerIdx = -1;
    }
}
