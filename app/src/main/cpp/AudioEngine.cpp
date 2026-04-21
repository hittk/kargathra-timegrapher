#include "AudioEngine.h"
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <chrono>
#include <time.h>

#define TAG "AudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

AudioEngine::AudioEngine() {
    // Detector fires on the audio thread → enqueue for worker dispatch
    mDetector.setBeatCallback([this](const BeatEvent& evt) noexcept {
        onBeatFromDetector(evt);
    });

    // Start the dispatch worker thread up-front; it idles until beats arrive.
    mWorkerShouldRun.store(true);
    mDispatchThread = std::thread([this]() { dispatchWorkerLoop(); });
}

AudioEngine::~AudioEngine() {
    stop();

    // Shut down worker thread cleanly
    mWorkerShouldRun.store(false);
    mWorkerCv.notify_all();
    if (mDispatchThread.joinable()) mDispatchThread.join();
}

// ─────────────────────────────────────────────────────────────────────────────
// Lifecycle
// ─────────────────────────────────────────────────────────────────────────────

bool AudioEngine::start(int preferredDeviceId) {
    // M6: guard against overlapping stream operations
    bool expected = false;
    if (!mOperationInFlight.compare_exchange_strong(expected, true)) {
        LOGW("start() ignored: another stream operation is in flight");
        return false;
    }

    std::lock_guard<std::mutex> lock(mStreamMutex);

    if (mRunning.load()) {
        mOperationInFlight.store(false);
        return true;
    }

    mDetector.reset();
    mDetector.setTriggersEnabled(false);        // Gated by phase state machine
    mDetector.setAmbientBaseline(0.0f);         // Will be set at end of AMBIENT phase
    mAutoDetectCount = 0;
    mBPHState.store(BPHState::AUTO_DETECTING);
    mLockedBPH.store(0);
    mTotalBeats.store(0);
    mBeatQueueWrite.store(0);
    mBeatQueueRead.store(0);

    // ── Phase state machine: start in AMBIENT unless manual BPH was preset ──
    // If the user already chose a specific BPH before starting, we skip the
    // BPH-detection phase but still do ambient noise learning + watch-placement
    // countdown so the detection is still calibrated.
    mPhase.store(Phase::AMBIENT);
    mPhaseStartSamples.store(0);
    mTotalSamplesProcessed.store(0);
    mAmbientRMS.store(0.0f);

    if (!openStream(preferredDeviceId)) {
        mOperationInFlight.store(false);
        return false;
    }

    oboe::Result result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start stream: %s", oboe::convertToText(result));
        closeStream();
        mOperationInFlight.store(false);
        return false;
    }

    mRunning.store(true);
    LOGI("AudioEngine started. RequestedDeviceId=%d ActualSampleRate=%d SessionId=%d",
         preferredDeviceId, mStream->getSampleRate(), mSessionId.load());

    mOperationInFlight.store(false);
    return true;
}

void AudioEngine::stop() {
    bool expected = false;
    if (!mOperationInFlight.compare_exchange_strong(expected, true)) {
        // Let the in-flight op finish; then try again briefly.
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
        expected = false;
        if (!mOperationInFlight.compare_exchange_strong(expected, true)) {
            LOGW("stop() abandoned: operation still in flight");
            return;
        }
    }

    {
        std::lock_guard<std::mutex> lock(mStreamMutex);
        if (mRunning.load()) {
            mRunning.store(false);
            closeStream();
        }
    }
    mOperationInFlight.store(false);
    LOGI("AudioEngine stopped.");
}

// ─────────────────────────────────────────────────────────────────────────────
// Stream open / close
// ─────────────────────────────────────────────────────────────────────────────

bool AudioEngine::openStream(int deviceId) {
    oboe::AudioStreamBuilder builder;

    builder.setAudioApi(oboe::AudioApi::AAudio)             // REQ-1.1
        ->setInputPreset(oboe::InputPreset::Unprocessed)    // REQ-1.2
        ->setDirection(oboe::Direction::Input)
        ->setChannelCount(oboe::ChannelCount::Mono)         // REQ-1.3
        ->setSampleRate(kSampleRate)                        // REQ-1.3
        ->setFormat(oboe::AudioFormat::Float)               // REQ-1.3
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)      // May fall back to Shared
        ->setDeviceId(deviceId)                             // REQ-1.4
        ->setDataCallback(this)
        ->setErrorCallback(this);

    oboe::Result result = builder.openStream(mStream);
    if (result != oboe::Result::OK) {
        LOGE("openStream failed: %s", oboe::convertToText(result));
        mStream.reset();
        return false;
    }

    // Record session ID for Kotlin-side AEC/AGC disable via AudioEffect API
    mSessionId.store(mStream->getSessionId());
    mActiveDeviceId.store(mStream->getDeviceId());

    // Verify we got the low-latency path (REQ-1.1) and report if not.
    // Oboe's AudioStream has no direct isMMapUsed() — we approximate by
    // checking that AAudio was successfully negotiated (MMAP is the only
    // low-latency path AAudio can take on a modern device with Exclusive
    // sharing mode).
    const bool isMMap     = (mStream->getAudioApi() == oboe::AudioApi::AAudio);
    const auto sharing    = mStream->getSharingMode();
    const auto perfMode   = mStream->getPerformanceMode();

    LOGI("Stream opened. MMAP=%d SharingMode=%s PerfMode=%s Format=%d DeviceId=%d SessionId=%d",
         isMMap ? 1 : 0,
         sharing == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared",
         perfMode == oboe::PerformanceMode::LowLatency ? "LowLatency" : "Other",
         static_cast<int>(mStream->getFormat()),
         mStream->getDeviceId(),
         mSessionId.load());

    if (!isMMap) {
        LOGW("MMAP path denied — latency will be higher than spec (REQ-1.1)");
    }

    return true;
}

void AudioEngine::closeStream() {
    if (mStream) {
        mStream->requestStop();
        mStream->close();
        mStream.reset();
    }
    mSessionId.store(-1);
}

// ─────────────────────────────────────────────────────────────────────────────
// Oboe audio callback (real-time thread — DO NOT BLOCK)
// ─────────────────────────────────────────────────────────────────────────────

oboe::DataCallbackResult AudioEngine::onAudioReady(
    oboe::AudioStream* stream,
    void* audioData,
    int32_t numFrames)
{
    auto* samples = static_cast<float*>(audioData);

    // Stable wall-clock baseline for the first frame in this callback.
    // Using CLOCK_MONOTONIC avoids NTP adjustments; for relative timing
    // over seconds this is ideal.
    struct timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    const int64_t baseNs = static_cast<int64_t>(ts.tv_sec) * 1'000'000'000LL + ts.tv_nsec;

    const double nsPerSample = 1e9 / static_cast<double>(stream->getSampleRate());

    for (int32_t i = 0; i < numFrames; ++i) {
        const int64_t sampleNs = baseNs + static_cast<int64_t>(i * nsPerSample);
        mDetector.processSample(samples[i], sampleNs);
    }

    // Track total samples processed and advance phase as time elapses.
    // Phase transitions are cheap — atomic compare-exchange only fires once
    // per phase boundary — so we call this every callback.
    mTotalSamplesProcessed.fetch_add(numFrames, std::memory_order_relaxed);
    advancePhaseIfNeeded();

    return oboe::DataCallbackResult::Continue;
}

// ─────────────────────────────────────────────────────────────────────────────
// Error callback — restart stream on disconnect
// ─────────────────────────────────────────────────────────────────────────────

void AudioEngine::onErrorAfterClose(oboe::AudioStream*, oboe::Result error) {
    LOGW("Stream error: %s — attempting restart", oboe::convertToText(error));

    std::thread([this]() {
        std::this_thread::sleep_for(std::chrono::milliseconds(200));

        bool expected = false;
        if (!mOperationInFlight.compare_exchange_strong(expected, true)) return;

        {
            std::lock_guard<std::mutex> lock(mStreamMutex);
            if (!mRunning.load()) {
                mOperationInFlight.store(false);
                return;
            }
            closeStream();
            if (openStream(mActiveDeviceId.load()) && mStream) {
                mStream->requestStart();
            } else {
                mRunning.store(false);
            }
        }
        mOperationInFlight.store(false);
    }).detach();
}

// ─────────────────────────────────────────────────────────────────────────────
// Beat events from detector → enqueue for dispatch worker
// ─────────────────────────────────────────────────────────────────────────────

void AudioEngine::onBeatFromDetector(const BeatEvent& event) noexcept {
    mTotalBeats.fetch_add(1, std::memory_order_relaxed);
    enqueueBeat(event);
    mWorkerCv.notify_one();

    // Auto-detection can happen directly on the audio thread (tiny work)
    if (mManualOverride.load() || mBPHState.load() == BPHState::LOCKED) return;
    processAutoDetection(event.timestampNs);
}

void AudioEngine::enqueueBeat(const BeatEvent& e) noexcept {
    const int w = mBeatQueueWrite.load(std::memory_order_relaxed);
    const int next = (w + 1) & (kBeatQueueSize - 1);
    // Drop the event if queue is full (consumer is behind); better to drop
    // one beat than to block the audio thread.
    if (next == mBeatQueueRead.load(std::memory_order_acquire)) return;
    mBeatQueue[w] = e;
    mBeatQueueWrite.store(next, std::memory_order_release);
}

// ─────────────────────────────────────────────────────────────────────────────
// Dispatch worker loop (runs on its own thread, does JNI callbacks safely)
// ─────────────────────────────────────────────────────────────────────────────

void AudioEngine::dispatchWorkerLoop() {
    while (mWorkerShouldRun.load()) {
        std::unique_lock<std::mutex> lk(mWorkerMutex);
        mWorkerCv.wait_for(lk, std::chrono::milliseconds(100), [this]() {
            return !mWorkerShouldRun.load() ||
                   mBeatQueueRead.load() != mBeatQueueWrite.load();
        });
        lk.unlock();

        // Drain all queued beats
        while (true) {
            const int r = mBeatQueueRead.load(std::memory_order_relaxed);
            const int w = mBeatQueueWrite.load(std::memory_order_acquire);
            if (r == w) break;

            BeatEvent event = mBeatQueue[r];
            mBeatQueueRead.store((r + 1) & (kBeatQueueSize - 1), std::memory_order_release);

            // Invoke user callback safely (protected against concurrent set)
            BeatCallback cb;
            {
                std::lock_guard<std::mutex> lock(mCallbackMutex);
                cb = mBeatCallback;
            }
            if (cb) cb(event);
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Phase state machine
//
// Advances through AMBIENT → PLACE_WATCH → DETECTING → RUNNING based on
// elapsed audio samples. Called from every onAudioReady callback (cheap).
//
// Transitions:
//   AMBIENT (5s):
//     - Triggers disabled. Detector updates its rolling RMS normally.
//     - At phase end, we snapshot the current RMS as the ambient baseline
//       and pass it to the detector. The baseline persists as a minimum
//       floor for the threshold calculation — this means that once the
//       watch starts ticking (which legitimately raises the short-term RMS),
//       the detector's threshold stays grounded in "what the room really
//       sounded like without a watch", not "the watch + room".
//
//   PLACE_WATCH (3s):
//     - User places the watch on the phone. Triggers remain disabled so
//       any handling noise (finger taps, friction) doesn't enter the
//       auto-detect buffer.
//
//   DETECTING (5s):
//     - Triggers enabled. Every beat timestamp goes into mAutoDetectBuffer
//       (up to kMaxDetectBuffer entries). Interval sanity check still
//       applies. At phase end, attemptBPHLock() computes the median and
//       locks if it passes the inlier check.
//
//   RUNNING:
//     - Steady state. Triggers remain enabled. No more phase transitions.
// ─────────────────────────────────────────────────────────────────────────────

void AudioEngine::advancePhaseIfNeeded() {
    // Skip phase advancement entirely if the user already locked a manual BPH
    // before starting. In that case we jump straight to RUNNING after one
    // sample, but still do the AMBIENT phase to learn the noise floor.
    const int64_t samples = mTotalSamplesProcessed.load(std::memory_order_relaxed);
    const Phase phase = mPhase.load(std::memory_order_relaxed);

    switch (phase) {
        case Phase::AMBIENT:
            if (samples >= kAmbientPhaseSamples) {
                // Snapshot ambient RMS. Guard against zero — use a tiny floor
                // so we never divide by zero downstream. If the user is in a
                // VERY quiet room and ambient RMS is measured as 0, we fall
                // back to pure rolling RMS in the detector.
                const float ambient = mDetector.getCurrentRMS();
                mAmbientRMS.store(ambient);
                mDetector.setAmbientBaseline(ambient);
                LOGI("Phase AMBIENT complete. Baseline RMS=%.6f", ambient);

                mPhase.store(Phase::PLACE_WATCH);
                mPhaseStartSamples.store(samples);
            }
            break;

        case Phase::PLACE_WATCH: {
            const int64_t elapsed = samples - mPhaseStartSamples.load(std::memory_order_relaxed);
            if (elapsed >= kPlaceWatchPhaseSamples) {
                LOGI("Phase PLACE_WATCH complete. Enabling triggers.");

                // If the user already chose a manual BPH, skip DETECTING
                // entirely and go straight to RUNNING.
                if (mManualOverride.load()) {
                    mPhase.store(Phase::RUNNING);
                    mBPHState.store(BPHState::LOCKED);
                    mDetector.setTriggersEnabled(true);
                } else {
                    mPhase.store(Phase::DETECTING);
                    mPhaseStartSamples.store(samples);
                    mAutoDetectCount = 0;
                    mDetector.setTriggersEnabled(true);
                }

                // Fire status update so UI reflects phase change immediately
                StatusCallback cb;
                {
                    std::lock_guard<std::mutex> lock(mCallbackMutex);
                    cb = mStatusCallback;
                }
                if (cb) cb(getStatus());
            }
            break;
        }

        case Phase::DETECTING: {
            const int64_t elapsed = samples - mPhaseStartSamples.load(std::memory_order_relaxed);
            if (elapsed >= kDetectingPhaseSamples) {
                LOGI("Phase DETECTING complete. Attempting BPH lock (%d beats collected).",
                     mAutoDetectCount);
                attemptBPHLock();
                mPhase.store(Phase::RUNNING);

                StatusCallback cb;
                {
                    std::lock_guard<std::mutex> lock(mCallbackMutex);
                    cb = mStatusCallback;
                }
                if (cb) cb(getStatus());
            }
            break;
        }

        case Phase::RUNNING:
            // No further transitions
            break;
    }
}

int AudioEngine::phaseSecondsRemaining() const noexcept {
    const int64_t samples = mTotalSamplesProcessed.load(std::memory_order_relaxed);
    const Phase phase = mPhase.load(std::memory_order_relaxed);
    int64_t remaining = 0;
    switch (phase) {
        case Phase::AMBIENT:
            remaining = kAmbientPhaseSamples - samples;
            break;
        case Phase::PLACE_WATCH:
            remaining = mPhaseStartSamples.load() + kPlaceWatchPhaseSamples - samples;
            break;
        case Phase::DETECTING:
            remaining = mPhaseStartSamples.load() + kDetectingPhaseSamples - samples;
            break;
        default:
            return 0;
    }
    if (remaining < 0) remaining = 0;
    // Round up so the countdown reads "5..4..3..2..1..0" rather than flooring
    // to "4..4..4..3..3..3..2..2..2..".
    return static_cast<int>((remaining + kSampleRate - 1) / kSampleRate);
}

// ─────────────────────────────────────────────────────────────────────────────
// BPH auto-detection (REQ-3.1 / REQ-3.2)
//
// Called ONLY during the DETECTING phase. Collects validated beat timestamps
// into mAutoDetectBuffer. The actual median-based BPH lock happens in
// attemptBPHLock(), called at the end of the DETECTING phase.
// ─────────────────────────────────────────────────────────────────────────────

void AudioEngine::processAutoDetection(int64_t timestampNs) {
    // Only collect during the DETECTING phase
    if (mPhase.load(std::memory_order_relaxed) != Phase::DETECTING) return;

    // Interval sanity check: reject implausible timings. The physically
    // plausible range is 80ms–500ms (equivalent to 7200–45000 BPH, which
    // covers every real watch including ultra-slow pocket watches and the
    // fastest high-beat movements).
    if (mAutoDetectCount > 0) {
        const int64_t prevNs     = mAutoDetectBuffer[mAutoDetectCount - 1];
        const int64_t intervalNs = timestampNs - prevNs;
        const float   intervalMs = static_cast<float>(intervalNs) * 1e-6f;

        if (intervalMs < 80.0f || intervalMs > 500.0f) {
            LOGW("BPH collect: rejecting implausible interval %.1fms", intervalMs);
            return;
        }
    }

    if (mAutoDetectCount < kMaxDetectBuffer) {
        mAutoDetectBuffer[mAutoDetectCount++] = timestampNs;
    }
    // If buffer is full we just stop collecting — 64 beats is plenty.
}

// ─────────────────────────────────────────────────────────────────────────────
// Attempt to lock BPH using the collected buffer. Called at the END of the
// DETECTING phase.
//
// Algorithm:
//   1. Require at least 8 beats collected (otherwise detection was too noisy).
//   2. Compute all consecutive intervals.
//   3. Take the median (robust to outliers).
//   4. Verify ≥70% of intervals are within ±10% of the median (inlier check).
//   5. Snap the median-derived BPH to the nearest standard value.
//
// If any check fails, we fall back to the most common default (28800 BPH)
// and log a warning. The user can override manually via the UI.
// ─────────────────────────────────────────────────────────────────────────────

void AudioEngine::attemptBPHLock() {
    // Need at least a handful of beats
    if (mAutoDetectCount < 8) {
        LOGW("BPH lock: too few beats collected (%d), defaulting to 28800",
             mAutoDetectCount);
        mLockedBPH.store(28800);
        mBPHState.store(BPHState::LOCKED);
        mDetector.setLockedBPH(28800);
        return;
    }

    // Compute all consecutive intervals
    const int nIntervals = mAutoDetectCount - 1;
    std::array<float, kMaxDetectBuffer> intervalsMs{};
    for (int i = 0; i < nIntervals; ++i) {
        intervalsMs[i] = static_cast<float>(
            mAutoDetectBuffer[i + 1] - mAutoDetectBuffer[i]
        ) * 1e-6f;
    }

    // Median of first nIntervals entries
    std::array<float, kMaxDetectBuffer> sorted = intervalsMs;
    std::sort(sorted.begin(), sorted.begin() + nIntervals);
    const float medianMs = sorted[nIntervals / 2];

    // Inlier check: how many are within ±10% of median?
    int inlierCount = 0;
    for (int i = 0; i < nIntervals; ++i) {
        if (std::abs(intervalsMs[i] - medianMs) / medianMs <= 0.10f) ++inlierCount;
    }
    const float inlierRatio = static_cast<float>(inlierCount) / static_cast<float>(nIntervals);

    if (inlierRatio < 0.70f) {
        LOGW("BPH lock: intervals too variable (%.1f%% inliers of %d), defaulting to 28800",
             inlierRatio * 100.0f, nIntervals);
        mLockedBPH.store(28800);
        mBPHState.store(BPHState::LOCKED);
        mDetector.setLockedBPH(28800);
        return;
    }

    const float rawBPH  = 3600.0f / (medianMs * 1e-3f);
    const int   snapped = snapToStandardBPH(rawBPH);

    mLockedBPH.store(snapped);
    mBPHState.store(BPHState::LOCKED);
    mDetector.setLockedBPH(snapped);

    LOGI("BPH locked: median=%.2fms raw=%.1f snapped=%d (inliers=%d/%d)",
         medianMs, rawBPH, snapped, inlierCount, nIntervals);
}

int AudioEngine::snapToStandardBPH(float rawBPH) const {
    int   best     = kStandardBPH[0];
    float bestDiff = std::abs(rawBPH - static_cast<float>(kStandardBPH[0]));
    for (int candidate : kStandardBPH) {
        float diff = std::abs(rawBPH - static_cast<float>(candidate));
        if (diff < bestDiff) { bestDiff = diff; best = candidate; }
    }
    return best;
}

// ─────────────────────────────────────────────────────────────────────────────
// USB-C routing (REQ-1.4)
// ─────────────────────────────────────────────────────────────────────────────

void AudioEngine::onAudioDeviceAdded(int deviceId, bool isUsbC) {
    if (!isUsbC) return;
    LOGI("USB-C audio device added: deviceId=%d — switching stream", deviceId);
    mUsbCConnected.store(true);
    // Do NOT auto-switch routing on plug-in — user must opt in via the toggle.
    // Just note the device is available (the UI will show the toggle button).
}

void AudioEngine::onAudioDeviceRemoved(int deviceId) {
    if (!mUsbCConnected.load() || deviceId != mActiveDeviceId.load()) return;
    LOGI("USB-C audio device removed — falling back to built-in mic");
    mUsbCConnected.store(false);
    // Fall back to built-in (not USB-C) with built-in bandpass profile
    requestRoutingSwitch(oboe::kUnspecified, /*isUsbC=*/false);
}

bool AudioEngine::requestRoutingSwitch(int deviceId, bool isUsbC) {
    bool expected = false;
    if (!mOperationInFlight.compare_exchange_strong(expected, true)) {
        LOGW("Routing switch deferred: operation in flight");
        return false;
    }

    // Apply the correct bandpass profile for the selected mic type.
    // This is safe to call on any thread — setBandpass only writes floats
    // to the filter coefficients and resets the noise floor.
    setBandpassProfile(isUsbC);

    {
        std::lock_guard<std::mutex> lock(mStreamMutex);
        if (mRunning.load()) {
            closeStream();
            if (openStream(deviceId) && mStream) {
                mStream->requestStart();
                mActiveDeviceId.store(mStream->getDeviceId());
            } else {
                mRunning.store(false);
            }
        }
    }
    mOperationInFlight.store(false);
    return true;
}

// ─────────────────────────────────────────────────────────────────────────────
// Configuration setters
// ─────────────────────────────────────────────────────────────────────────────

void AudioEngine::setBandpassProfile(bool isUsbC) {
    if (isUsbC) {
        LOGI("Bandpass → piezo contact profile (1000–6000 Hz, 80ms hold-off)");
        mDetector.setBandpass(1000.0f, 6000.0f);
        // Piezo impulses have a sharp mechanical rebound 5–30ms after the
        // initial strike. A longer hold-off masks it and prevents the
        // rebound from being detected as a second beat.
        mDetector.setHoldOffSamples(static_cast<int>(kSampleRate * 80 / 1000)); // 80ms
    } else {
        LOGI("Bandpass → built-in airborne profile (3000–8000 Hz, 65ms hold-off)");
        mDetector.setBandpass(3000.0f, 8000.0f);
        mDetector.setHoldOffSamples(kHoldOffSamples); // 65ms default
    }
}

void AudioEngine::setManualBPH(int bph) {
    if (bph <= 0) {
        // Revert to AUTO. If currently RUNNING, restart phase state machine
        // from DETECTING (skip ambient — we already have that baseline).
        mManualOverride.store(false);
        mBPHState.store(BPHState::AUTO_DETECTING);
        mAutoDetectCount = 0;
        mLockedBPH.store(0);
        if (mRunning.load() && mPhase.load() == Phase::RUNNING) {
            mPhase.store(Phase::DETECTING);
            mPhaseStartSamples.store(mTotalSamplesProcessed.load());
        }
        LOGI("BPH reset to AUTO");
    } else {
        mManualOverride.store(true);
        mBPHState.store(BPHState::LOCKED);
        mLockedBPH.store(bph);
        mDetector.setLockedBPH(bph);
        // If we're mid-detection, jump to RUNNING immediately
        if (mRunning.load() && mPhase.load() == Phase::DETECTING) {
            mPhase.store(Phase::RUNNING);
        }
        LOGI("BPH manually set to %d", bph);
    }
    StatusCallback cb;
    {
        std::lock_guard<std::mutex> lock(mCallbackMutex);
        cb = mStatusCallback;
    }
    if (cb) cb(getStatus());
}

void AudioEngine::setLiftAngle(float degrees)          { mDetector.setLiftAngle(degrees); }
void AudioEngine::setThresholdMultiplier(float mult)   { mDetector.setThresholdMultiplier(mult); }

void AudioEngine::setBeatCallback(BeatCallback cb) {
    std::lock_guard<std::mutex> lock(mCallbackMutex);
    mBeatCallback = std::move(cb);
}

void AudioEngine::setStatusCallback(StatusCallback cb) {
    std::lock_guard<std::mutex> lock(mCallbackMutex);
    mStatusCallback = std::move(cb);
}

void AudioEngine::getWaveformSnapshot(WaveformSnapshot& out) const noexcept {
    mDetector.getWaveformSnapshot(out);
}

EngineStatus AudioEngine::getStatus() const noexcept {
    EngineStatus s{};
    const bool running = mRunning.load();

    if (!running) {
        s.state = EngineStatus::State::IDLE;
    } else {
        switch (mPhase.load(std::memory_order_relaxed)) {
            case Phase::AMBIENT:     s.state = EngineStatus::State::CALIBRATING; break;
            case Phase::PLACE_WATCH: s.state = EngineStatus::State::PLACE_WATCH; break;
            case Phase::DETECTING:   s.state = EngineStatus::State::DETECTING;   break;
            case Phase::RUNNING:     s.state = EngineStatus::State::RUNNING;     break;
        }
    }

    s.lockedBPH             = mLockedBPH.load();
    s.detectedTickCount     = std::min(mAutoDetectCount, kMaxDetectBuffer);
    s.phaseSecondsRemaining = phaseSecondsRemaining();
    s.isManualBPH           = mManualOverride.load();
    return s;
}
