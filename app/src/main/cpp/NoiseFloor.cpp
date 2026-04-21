#include "NoiseFloor.h"
#include <algorithm>
#include <cmath>

float RollingRMS::update(float sample) noexcept {
    const float newSq = sample * sample;
    const float oldSq = mBuffer[mWriteIdx];

    // Sliding window: subtract outgoing, add incoming
    mSumSq = mSumSq - oldSq + newSq;
    if (mSumSq < 0.0f) mSumSq = 0.0f;  // floating-point drift guard

    mBuffer[mWriteIdx] = newSq;
    mWriteIdx = (mWriteIdx + 1) % kNoiseWindowSamples;

    if (mFillCount < kNoiseWindowSamples) ++mFillCount;

    mCurrentRMS = std::sqrt(mSumSq / static_cast<float>(mFillCount));
    return mCurrentRMS;
}

void RollingRMS::reset() noexcept {
    mBuffer.fill(0.0f);
    mWriteIdx   = 0;
    mSumSq      = 0.0f;
    mCurrentRMS = 0.0f;
    mFillCount  = 0;
}
