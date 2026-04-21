#pragma once
#include <array>
#include <cmath>

// ─────────────────────────────────────────────────────────────────────────────
// CascadedBandpassFilter
//
// Implements a 4th-order bandpass filter as two cascaded biquad IIR sections.
// One highpass biquad at kBPF_LowHz (removes rumble below 1500 Hz).
// One lowpass biquad at kBPF_HighHz (removes hiss above 10000 Hz).
//
// Uses Direct Form II Transposed for numerical stability.
// Designed to run on the real-time C++ audio thread — no allocations.
// ─────────────────────────────────────────────────────────────────────────────

class BandpassFilter {
public:
    BandpassFilter();

    // Recalculate coefficients for a given sample rate.
    // Call once on construction or if sample rate changes.
    void configure(float sampleRate, float lowCutHz, float highCutHz);

    // Process a single sample in-place. Returns filtered output.
    // Must be called for every sample in sequence.
    float process(float input) noexcept;

    // Reset filter state (zero the delay lines). Call on stream restart.
    void reset() noexcept;

private:
    // Biquad coefficients: b0, b1, b2, a1, a2 (a0 normalised to 1)
    struct BiquadCoeffs {
        float b0, b1, b2, a1, a2;
    };

    // Direct Form II Transposed state variables
    struct BiquadState {
        float w1 = 0.0f;
        float w2 = 0.0f;
    };

    BiquadCoeffs mHighpassCoeffs{};
    BiquadCoeffs mLowpassCoeffs{};
    BiquadState  mHighpassState{};
    BiquadState  mLowpassState{};

    // Apply a single biquad section
    static float applyBiquad(float x, BiquadCoeffs& c, BiquadState& s) noexcept;

    // Design a 2nd-order Butterworth highpass biquad
    static BiquadCoeffs designHighpass(float cutoffHz, float sampleRate);

    // Design a 2nd-order Butterworth lowpass biquad
    static BiquadCoeffs designLowpass(float cutoffHz, float sampleRate);
};
