#include "BandpassFilter.h"
#include "EngineTypes.h"
#include <cmath>

static constexpr float kPi = 3.14159265358979323846f;

BandpassFilter::BandpassFilter() {
    configure(static_cast<float>(kSampleRate), kBPF_LowHz, kBPF_HighHz);
}

void BandpassFilter::configure(float sampleRate, float lowCutHz, float highCutHz) {
    mHighpassCoeffs = designHighpass(lowCutHz, sampleRate);
    mLowpassCoeffs  = designLowpass(highCutHz, sampleRate);
    reset();
}

void BandpassFilter::reset() noexcept {
    mHighpassState = {};
    mLowpassState  = {};
}

float BandpassFilter::process(float input) noexcept {
    float hp = applyBiquad(input, mHighpassCoeffs, mHighpassState);
    float bp = applyBiquad(hp,    mLowpassCoeffs,  mLowpassState);
    return bp;
}

float BandpassFilter::applyBiquad(float x, BiquadCoeffs& c, BiquadState& s) noexcept {
    // Direct Form II Transposed
    float y = c.b0 * x + s.w1;
    s.w1    = c.b1 * x - c.a1 * y + s.w2;
    s.w2    = c.b2 * x - c.a2 * y;
    return y;
}

BandpassFilter::BiquadCoeffs BandpassFilter::designHighpass(float cutoffHz, float sampleRate) {
    // 2nd-order Butterworth highpass via bilinear transform
    float w0   = 2.0f * kPi * cutoffHz / sampleRate;
    float cosW = std::cos(w0);
    float sinW = std::sin(w0);
    float alpha = sinW / (2.0f * 0.7071f); // Q = 1/sqrt(2) for Butterworth

    float b0 =  (1.0f + cosW) / 2.0f;
    float b1 = -(1.0f + cosW);
    float b2 =  (1.0f + cosW) / 2.0f;
    float a0 =   1.0f + alpha;
    float a1 =  -2.0f * cosW;
    float a2 =   1.0f - alpha;

    return { b0/a0, b1/a0, b2/a0, a1/a0, a2/a0 };
}

BandpassFilter::BiquadCoeffs BandpassFilter::designLowpass(float cutoffHz, float sampleRate) {
    // 2nd-order Butterworth lowpass via bilinear transform
    float w0   = 2.0f * kPi * cutoffHz / sampleRate;
    float cosW = std::cos(w0);
    float sinW = std::sin(w0);
    float alpha = sinW / (2.0f * 0.7071f); // Q = 1/sqrt(2) for Butterworth

    float b0 =  (1.0f - cosW) / 2.0f;
    float b1 =   1.0f - cosW;
    float b2 =  (1.0f - cosW) / 2.0f;
    float a0 =   1.0f + alpha;
    float a1 =  -2.0f * cosW;
    float a2 =   1.0f - alpha;

    return { b0/a0, b1/a0, b2/a0, a1/a0, a2/a0 };
}
