#pragma once
#include "EngineTypes.h"
#include <cmath>

// ─────────────────────────────────────────────────────────────────────────────
// AmplitudeCalculator
//
// Implements REQ-4.2:
//   A = θ_lift / sin(π × (BPH / 7200) × Δt_lift)
//
// Guards against degenerate sin() inputs per the requirement.
// ─────────────────────────────────────────────────────────────────────────────

class AmplitudeCalculator {
public:
    struct Result {
        float amplitudeDeg;
        bool  valid;          // false if sin argument was degenerate
    };

    // Calculate amplitude.
    // liftTimeSec: Δt_lift in seconds (Unlock → Drop)
    // bph:         target BPH (snapped or manual)
    // liftAngleDeg: θ_lift from slider (35–70°)
    static Result calculate(float liftTimeSec, int bph, float liftAngleDeg) noexcept {
        if (liftTimeSec <= 0.0f || bph <= 0) {
            return { 0.0f, false };
        }

        // Argument to sin: π × (BPH / 7200) × Δt_lift
        const float arg = static_cast<float>(M_PI)
                          * (static_cast<float>(bph) / 7200.0f)
                          * liftTimeSec;

        // Guard: sin argument must produce a value strictly in (0, 1)
        // to avoid division by zero or physically impossible results
        const float sinVal = std::sin(arg);
        if (sinVal <= 0.0f || sinVal >= 1.0f) {
            return { 0.0f, false };
        }

        const float amplitude = liftAngleDeg / sinVal;

        // Sanity bounds: physically plausible amplitude range (degrees)
        if (amplitude < 100.0f || amplitude > 360.0f) {
            return { amplitude, false };
        }

        return { amplitude, true };
    }
};
