#pragma once
#include <array>
#include <cmath>
#include "EngineTypes.h"

// ─────────────────────────────────────────────────────────────────────────────
// RollingRMS — sliding-window Root Mean Square of the filtered audio signal.
//
// Uses a fixed-size std::array of squared samples + incremental sum → O(1)
// per sample with NO heap allocations ever. Single-threaded use only
// (called exclusively from the Oboe audio callback thread).
// ─────────────────────────────────────────────────────────────────────────────

class RollingRMS {
public:
    RollingRMS() { reset(); }

    float update(float sample) noexcept;
    float getRMS() const noexcept { return mCurrentRMS; }
    float getThreshold(float multiplier = kThresholdMultiplier) const noexcept {
        return mCurrentRMS * multiplier;
    }
    /**
     * Returns true once the rolling window has been fully populated.
     * Before this, the RMS is unreliable (computed from only a few samples)
     * and the detector should NOT trigger — otherwise startup transients
     * cause false beats that poison the BPH auto-detection.
     */
    bool isWarm() const noexcept { return mFillCount >= kNoiseWindowSamples; }

    void reset() noexcept;

private:
    std::array<float, kNoiseWindowSamples> mBuffer{};
    int   mWriteIdx   = 0;
    float mSumSq      = 0.0f;
    float mCurrentRMS = 0.0f;
    int   mFillCount  = 0;
};
