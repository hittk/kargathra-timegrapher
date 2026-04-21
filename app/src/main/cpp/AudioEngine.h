#pragma once

#include "EngineTypes.h"
#include "TickDetector.h"

#include <oboe/Oboe.h>
#include <atomic>
#include <mutex>
#include <array>
#include <functional>
#include <vector>
#include <thread>
#include <condition_variable>

// ─────────────────────────────────────────────────────────────────────────────
// AudioEngine
//
// Owns the Oboe audio stream. Beat events detected on the audio thread are
// enqueued into a lock-free SPSC ring buffer and drained by a dedicated
// dispatch worker thread, so the audio thread never blocks on JNI or user
// callbacks (REQ-5.3).
// ─────────────────────────────────────────────────────────────────────────────

class AudioEngine : public oboe::AudioStreamDataCallback
                  , public oboe::AudioStreamErrorCallback {
public:
    using StatusCallback = std::function<void(const EngineStatus&)>;
    using BeatCallback   = std::function<void(const BeatEvent&)>;

    AudioEngine();
    ~AudioEngine();

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    bool start(int preferredDeviceId = oboe::kUnspecified);
    void stop();
    bool isRunning() const noexcept { return mRunning.load(); }

    // Audio session ID — Kotlin uses this to disable AEC/AGC via the
    // AudioEffect Java API (REQ-1.2 hardening)
    int  getSessionId() const noexcept { return mSessionId.load(); }

    // ── Configuration ─────────────────────────────────────────────────────────
    void setManualBPH(int bph);
    void setLiftAngle(float degrees);
    void setThresholdMultiplier(float mult);
    /**
     * Set the bandpass filter profile without touching the stream.
     * Call this before start() when the mic source is known, or use
     * requestRoutingSwitch() to switch source mid-session which calls
     * this automatically.
     *   isUsbC=true  → piezo contact profile: 1000–6000 Hz
     *   isUsbC=false → built-in airborne profile: 3000–8000 Hz
     */
    void setBandpassProfile(bool isUsbC);

    // ── Routing ───────────────────────────────────────────────────────────────
    void onAudioDeviceAdded(int deviceId, bool isUsbC);
    void onAudioDeviceRemoved(int deviceId);
    bool requestRoutingSwitch(int deviceId, bool isUsbC);    // User-initiated (REQ-9.3)

    // ── Callbacks to Kotlin ────────────────────────────────────────────────────
    void setStatusCallback(StatusCallback cb);
    void setBeatCallback(BeatCallback cb);

    // ── Snapshots (UI thread polling) ─────────────────────────────────────────
    void         getWaveformSnapshot(WaveformSnapshot& out) const noexcept;
    EngineStatus getStatus() const noexcept;

    // ── Oboe callback interface ───────────────────────────────────────────────
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* stream,
        void* audioData,
        int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    // ── Stream management ─────────────────────────────────────────────────────
    bool openStream(int deviceId);
    void closeStream();

    // ── BPH auto-detection ────────────────────────────────────────────────────
    void onBeatFromDetector(const BeatEvent& event) noexcept;
    void processAutoDetection(int64_t timestampNs);
    int  snapToStandardBPH(float rawBPH) const;
    void attemptBPHLock();           // Called at end of DETECTING phase
    void advancePhaseIfNeeded();     // Called each audio callback
    int  phaseSecondsRemaining() const noexcept;

    // ── Dispatch worker ───────────────────────────────────────────────────────
    void dispatchWorkerLoop();
    void enqueueBeat(const BeatEvent& e) noexcept;

    // ── Members ───────────────────────────────────────────────────────────────
    std::shared_ptr<oboe::AudioStream> mStream;
    mutable std::mutex                 mStreamMutex;
    std::atomic<bool>                  mOperationInFlight{false};

    TickDetector                       mDetector;
    std::atomic<bool>                  mRunning{false};
    std::atomic<int>                   mSessionId{-1};

    // ── Three-phase startup state machine ────────────────────────────────────
    // AMBIENT: measure ambient noise, no triggers accepted
    // PLACE_WATCH: user places watch, no triggers accepted
    // DETECTING: collect beat intervals, compute BPH, then transition
    // RUNNING: locked, normal measurement
    enum class Phase { AMBIENT, PLACE_WATCH, DETECTING, RUNNING };
    std::atomic<Phase>                 mPhase{Phase::AMBIENT};
    std::atomic<int64_t>               mPhaseStartSamples{0};
    std::atomic<int64_t>               mTotalSamplesProcessed{0};

    // Ambient noise floor learned in AMBIENT phase; locked as detection baseline.
    std::atomic<float>                 mAmbientRMS{0.0f};

    enum class BPHState { AUTO_DETECTING, LOCKED };
    std::atomic<BPHState>              mBPHState{BPHState::AUTO_DETECTING};
    std::atomic<int>                   mLockedBPH{0};
    std::atomic<bool>                  mManualOverride{false};

    // Buffer for BPH detection — grows during DETECTING phase
    static constexpr int kMaxDetectBuffer = 64;
    std::array<int64_t, kMaxDetectBuffer> mAutoDetectBuffer{};
    int                                mAutoDetectCount = 0;

    std::atomic<int>                   mActiveDeviceId{oboe::kUnspecified};
    std::atomic<bool>                  mUsbCConnected{false};

    // Lock-free SPSC beat queue
    static constexpr int kBeatQueueSize = 64;
    BeatEvent            mBeatQueue[kBeatQueueSize];
    std::atomic<int>     mBeatQueueWrite{0};
    std::atomic<int>     mBeatQueueRead{0};

    // Dispatch worker
    std::thread                        mDispatchThread;
    std::atomic<bool>                  mWorkerShouldRun{false};
    std::condition_variable            mWorkerCv;
    std::mutex                         mWorkerMutex;

    // User callbacks (protected by mCallbackMutex)
    StatusCallback                     mStatusCallback;
    BeatCallback                       mBeatCallback;
    mutable std::mutex                 mCallbackMutex;

    std::atomic<int>                   mTotalBeats{0};
};
