#pragma once

#include <cstdint>
#include <cmath>
#include <array>
#include <atomic>

// ─────────────────────────────────────────────────────────────────────────────
// Audio constants
// ─────────────────────────────────────────────────────────────────────────────

static constexpr int     kSampleRate          = 48000;   // Native Pixel 8 Pro ADC rate
static constexpr int     kChannelCount        = 1;       // Mono
static constexpr int     kBitsPerSample       = 16;

// ─────────────────────────────────────────────────────────────────────────────
// DSP constants
// ─────────────────────────────────────────────────────────────────────────────

// Bandpass filter passband (REQ-2.1)
// Tuned for phone-mic capture of watch escapement ticks. The 3–8kHz band
// centres on the actual spectral content of a lever escapement tick as heard
// through a Pixel bottom mic at ~5cm distance. The original 1.5–10kHz band
// was too wide — it let through HVAC rumble, speech, and other room noise
// that polluted the noise floor and caused false triggers.
static constexpr float   kBPF_LowHz           = 3000.0f;
static constexpr float   kBPF_HighHz          = 8000.0f;

// Noise floor rolling window: 50ms in samples (REQ-2.2)
static constexpr int     kNoiseWindowSamples  = kSampleRate * 50 / 1000;  // 2400

// Dynamic threshold multiplier default (REQ-2.3)
// Set to ×10.0 — empirically, a contact piezo mic needs a higher threshold
// than an airborne mic because its signal is dominated by a sharp, large
// impulse relative to a low-noise baseline. A higher default means the app
// works well out of the box for piezo contact mics; users with noisy
// environments can lower it via the THR slider.
static constexpr float   kThresholdMultiplier = 10.0f;

// Macro hold-off: 65ms (REQ-2.4)
// Must be shorter than half the shortest expected beat interval (36000 BPH
// = 100ms/beat, so 50ms is safe). 65ms is safe for all beats ≤ 36000 BPH.
static constexpr int     kHoldOffSamples      = kSampleRate * 65 / 1000;  // 3120

// Micro-window for lift time extraction: 32ms (REQ-4.1)
static constexpr int     kMicroWindowSamples  = kSampleRate * 32 / 1000;  // 1536

// ─────────────────────────────────────────────────────────────────────────────
// Calibration phase durations (in samples)
//
// Three-phase startup:
//   1. AMBIENT      (5s)  — learn the true ambient noise floor before any
//                           watch is placed near the mic. No triggers are
//                           emitted. At end of phase, the measured ambient
//                           RMS is locked as the detection baseline.
//   2. PLACE_WATCH  (3s)  — countdown while the user places the watch.
//                           No triggers. Noise floor still computed but
//                           not used for threshold (ambient baseline locked).
//   3. DETECTING    (5s)  — BPH auto-detection. Real triggers accepted,
//                           median interval calculated, BPH locked on
//                           phase-end if inlier ratio is high enough.
//   4. RUNNING            — normal measurement. BPH stays locked.
// ─────────────────────────────────────────────────────────────────────────────

static constexpr int kAmbientPhaseSamples     = kSampleRate * 5;  // 5s
static constexpr int kPlaceWatchPhaseSamples  = kSampleRate * 3;  // 3s
static constexpr int kDetectingPhaseSamples   = kSampleRate * 5;  // 5s

// ─────────────────────────────────────────────────────────────────────────────
// Timing & BPH constants
// ─────────────────────────────────────────────────────────────────────────────

// Standard horological frequencies (REQ-3.2)
static constexpr std::array<int, 7> kStandardBPH = {
    14400, 18000, 19800, 21600, 25200, 28800, 36000
};

// ─────────────────────────────────────────────────────────────────────────────
// Amplitude constants
// ─────────────────────────────────────────────────────────────────────────────

static constexpr float   kDefaultLiftAngle    = 53.0f;   // degrees (REQ-4.3)
static constexpr float   kMinLiftAngle        = 35.0f;
static constexpr float   kMaxLiftAngle        = 70.0f;

// ─────────────────────────────────────────────────────────────────────────────
// Shared data structures (passed from C++ → Kotlin via JNI)
// ─────────────────────────────────────────────────────────────────────────────

struct BeatEvent {
    int64_t  timestampNs;       // System nanosecond timestamp of Unlock peak
    float    deviationMs;       // Timing deviation from ideal BPH (ms), + = fast
    float    amplitudeDeg;      // Balance wheel amplitude (degrees), -1 = invalid
    float    liftTimeMs;        // Unlock→Drop lift time (ms)
    bool     isTock;            // false = tick, true = tock (alternates each beat)
    bool     amplitudeValid;    // false if sin() argument was degenerate
};

struct WaveformSnapshot {
    static constexpr int kSize = 2048;      // ~42ms at 48kHz
    float    samples[kSize];                // Filtered audio samples
    float    noiseFloorRMS;                 // Current noise floor level
    float    triggerThreshold;             // noiseFloorRMS × multiplier
    int      triggerMarkerIdx;             // Sample index of last tick event (-1 = none)
    int      validSamples;                 // How many samples are populated
};

struct EngineStatus {
    enum class State : int {
        IDLE            = 0,
        CALIBRATING     = 1,   // Phase 1: learning ambient noise (5s)
        PLACE_WATCH     = 2,   // Phase 2: countdown for watch placement (3s)
        DETECTING       = 3,   // Phase 3: auto-detecting BPH (5s)
        RUNNING         = 4,   // Locked and measuring
        ERROR           = 5
    };
    State   state;
    int     lockedBPH;              // 0 = not yet locked
    int     detectedTickCount;      // Beats collected during DETECTING phase
    int     phaseSecondsRemaining;  // For CALIBRATING / PLACE_WATCH / DETECTING
    bool    isManualBPH;
    char    errorMessage[128];
};
