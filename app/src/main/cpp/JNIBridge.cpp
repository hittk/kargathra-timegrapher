#include "AudioEngine.h"
#include <jni.h>
#include <android/log.h>
#include <memory>
#include <atomic>
#include <mutex>

#define TAG "JNIBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)

// ─────────────────────────────────────────────────────────────────────────────
// Global JNI callback state
// ─────────────────────────────────────────────────────────────────────────────
// The C++ AudioEngine invokes its beat callback on a dispatch worker thread
// (drained from a lock-free SPSC queue — see AudioEngine.cpp). That worker
// thread attaches itself to the JVM and calls AudioEngineJNI.onNativeBeat(...)
// on the Kotlin side, which marshals to the main thread via StateFlow.
//
// We deliberately do NOT call JNI from the Oboe audio callback thread itself
// (REQ-5.3: audio thread must not block).
// ─────────────────────────────────────────────────────────────────────────────

static JavaVM*   gJvm            = nullptr;
static jobject   gCallbackObj    = nullptr;    // Global ref to AudioEngineJNI instance
static jmethodID gOnBeatMethod   = nullptr;
static jmethodID gOnStatusMethod = nullptr;
static std::mutex gCallbackMutex;

jint JNI_OnLoad(JavaVM* vm, void*) {
    gJvm = vm;
    return JNI_VERSION_1_6;
}

// RAII helper: attach the current thread to the JVM, detach on scope exit.
class ScopedJniAttach {
public:
    ScopedJniAttach() : mEnv(nullptr), mAttached(false) {
        if (!gJvm) return;
        jint status = gJvm->GetEnv(reinterpret_cast<void**>(&mEnv), JNI_VERSION_1_6);
        if (status == JNI_EDETACHED) {
            if (gJvm->AttachCurrentThread(&mEnv, nullptr) == JNI_OK) {
                mAttached = true;
            }
        } else if (status != JNI_OK) {
            mEnv = nullptr;
        }
    }
    ~ScopedJniAttach() {
        if (mAttached && gJvm) gJvm->DetachCurrentThread();
    }
    JNIEnv* env() const { return mEnv; }
private:
    JNIEnv* mEnv;
    bool    mAttached;
};

static AudioEngine* getEngine(jlong handle) {
    return reinterpret_cast<AudioEngine*>(handle);
}

extern "C" {

// ─────────────────────────────────────────────────────────────────────────────
// Engine lifecycle
// ─────────────────────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_com_kargathra_timegrapher_audio_AudioEngineJNI_nativeCreate(JNIEnv* env, jobject thiz) {
    auto* engine = new AudioEngine();

    // Register this AudioEngineJNI instance as the callback target
    {
        std::lock_guard<std::mutex> lock(gCallbackMutex);
        if (gCallbackObj) env->DeleteGlobalRef(gCallbackObj);
        gCallbackObj    = env->NewGlobalRef(thiz);
        jclass cls      = env->GetObjectClass(thiz);
        gOnBeatMethod   = env->GetMethodID(cls, "onNativeBeat",   "(JFFFZZ)V");
        gOnStatusMethod = env->GetMethodID(cls, "onNativeStatus", "(IIIIZ)V");
        env->DeleteLocalRef(cls);
    }

    // Wire C++ beat callback → Kotlin onNativeBeat(...)
    engine->setBeatCallback([](const BeatEvent& e) {
        ScopedJniAttach attach;
        JNIEnv* jEnv = attach.env();
        if (!jEnv) return;
        std::lock_guard<std::mutex> lock(gCallbackMutex);
        if (!gCallbackObj || !gOnBeatMethod) return;
        jEnv->CallVoidMethod(gCallbackObj, gOnBeatMethod,
            static_cast<jlong>(e.timestampNs),
            static_cast<jfloat>(e.deviationMs),
            static_cast<jfloat>(e.amplitudeDeg),
            static_cast<jfloat>(e.liftTimeMs),
            static_cast<jboolean>(e.isTock),
            static_cast<jboolean>(e.amplitudeValid));
        if (jEnv->ExceptionCheck()) { jEnv->ExceptionDescribe(); jEnv->ExceptionClear(); }
    });

    // Wire C++ status callback → Kotlin onNativeStatus(...)
    engine->setStatusCallback([](const EngineStatus& s) {
        ScopedJniAttach attach;
        JNIEnv* jEnv = attach.env();
        if (!jEnv) return;
        std::lock_guard<std::mutex> lock(gCallbackMutex);
        if (!gCallbackObj || !gOnStatusMethod) return;
        jEnv->CallVoidMethod(gCallbackObj, gOnStatusMethod,
            static_cast<jint>(s.state),
            static_cast<jint>(s.lockedBPH),
            static_cast<jint>(s.detectedTickCount),
            static_cast<jint>(s.phaseSecondsRemaining),
            static_cast<jboolean>(s.isManualBPH));
        if (jEnv->ExceptionCheck()) { jEnv->ExceptionDescribe(); jEnv->ExceptionClear(); }
    });

    LOGI("AudioEngine created. Handle=%p", engine);
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_kargathra_timegrapher_audio_AudioEngineJNI_nativeDestroy(JNIEnv* env, jobject, jlong handle) {
    auto* engine = getEngine(handle);
    if (engine) {
        engine->setBeatCallback(nullptr);
        engine->setStatusCallback(nullptr);
        delete engine;
    }
    std::lock_guard<std::mutex> lock(gCallbackMutex);
    if (gCallbackObj) {
        env->DeleteGlobalRef(gCallbackObj);
        gCallbackObj    = nullptr;
        gOnBeatMethod   = nullptr;
        gOnStatusMethod = nullptr;
    }
    LOGI("AudioEngine destroyed.");
}

JNIEXPORT jboolean JNICALL
Java_com_kargathra_timegrapher_audio_AudioEngineJNI_nativeStart(
    JNIEnv*, jobject, jlong handle, jint deviceId)
{
    return getEngine(handle)->start(static_cast<int>(deviceId)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_kargathra_timegrapher_audio_AudioEngineJNI_nativeStop(JNIEnv*, jobject, jlong handle) {
    getEngine(handle)->stop();
}

JNIEXPORT jint JNICALL
Java_com_kargathra_timegrapher_audio_AudioEngineJNI_nativeGetSessionId(
    JNIEnv*, jobject, jlong handle)
{
    return static_cast<jint>(getEngine(handle)->getSessionId());
}

// ─────────────────────────────────────────────────────────────────────────────
// Configuration
// ─────────────────────────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_kargathra_timegrapher_audio_AudioEngineJNI_nativeSetManualBPH(
    JNIEnv*, jobject, jlong handle, jint bph)
{ getEngine(handle)->setManualBPH(static_cast<int>(bph)); }

JNIEXPORT void JNICALL
Java_com_kargathra_timegrapher_audio_AudioEngineJNI_nativeSetLiftAngle(
    JNIEnv*, jobject, jlong handle, jfloat degrees)
{ getEngine(handle)->setLiftAngle(static_cast<float>(degrees)); }

JNIEXPORT void JNICALL
Java_com_kargathra_timegrapher_audio_AudioEngineJNI_nativeSetThresholdMultiplier(
    JNIEnv*, jobject, jlong handle, jfloat mult)
{ getEngine(handle)->setThresholdMultiplier(static_cast<float>(mult)); }

JNIEXPORT void JNICALL
Java_com_kargathra_timegrapher_audio_AudioEngineJNI_nativeSetBandpassProfile(
    JNIEnv*, jobject, jlong handle, jboolean isUsbC)
{ getEngine(handle)->setBandpassProfile(static_cast<bool>(isUsbC)); }

// ─────────────────────────────────────────────────────────────────────────────
// Input device routing
// ─────────────────────────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_kargathra_timegrapher_audio_AudioEngineJNI_nativeOnDeviceAdded(
    JNIEnv*, jobject, jlong handle, jint deviceId, jboolean isUsbC)
{ getEngine(handle)->onAudioDeviceAdded(static_cast<int>(deviceId), static_cast<bool>(isUsbC)); }

JNIEXPORT void JNICALL
Java_com_kargathra_timegrapher_audio_AudioEngineJNI_nativeOnDeviceRemoved(
    JNIEnv*, jobject, jlong handle, jint deviceId)
{ getEngine(handle)->onAudioDeviceRemoved(static_cast<int>(deviceId)); }

/**
 * User-initiated routing switch (REQ-9.3).
 * deviceId = 0 → built-in mic; deviceId > 0 → specific Oboe device ID.
 * isUsbC = true → apply piezo contact bandpass (1–6 kHz); false → built-in
 *   airborne bandpass (3–8 kHz).
 */
JNIEXPORT jboolean JNICALL
Java_com_kargathra_timegrapher_audio_AudioEngineJNI_nativeRequestRoutingSwitch(
    JNIEnv*, jobject, jlong handle, jint deviceId, jboolean isUsbC)
{ return getEngine(handle)->requestRoutingSwitch(static_cast<int>(deviceId), static_cast<bool>(isUsbC)) ? JNI_TRUE : JNI_FALSE; }

// ─────────────────────────────────────────────────────────────────────────────
// Polling: waveform snapshot
// ─────────────────────────────────────────────────────────────────────────────

JNIEXPORT jfloatArray JNICALL
Java_com_kargathra_timegrapher_audio_AudioEngineJNI_nativeGetWaveform(
    JNIEnv* env, jobject, jlong handle)
{
    WaveformSnapshot snap{};
    getEngine(handle)->getWaveformSnapshot(snap);

    const int headerLen = 3;
    const int totalLen  = headerLen + snap.validSamples;
    jfloatArray arr = env->NewFloatArray(totalLen);
    if (!arr) return nullptr;

    auto* buf = env->GetFloatArrayElements(arr, nullptr);
    buf[0] = snap.noiseFloorRMS;
    buf[1] = snap.triggerThreshold;
    buf[2] = static_cast<float>(snap.triggerMarkerIdx);
    for (int i = 0; i < snap.validSamples; ++i) {
        buf[headerLen + i] = snap.samples[i];
    }
    env->ReleaseFloatArrayElements(arr, buf, 0);
    return arr;
}

JNIEXPORT jintArray JNICALL
Java_com_kargathra_timegrapher_audio_AudioEngineJNI_nativeGetStatus(
    JNIEnv* env, jobject, jlong handle)
{
    EngineStatus s = getEngine(handle)->getStatus();
    jintArray arr = env->NewIntArray(5);
    jint buf[5] = {
        static_cast<jint>(s.state),
        static_cast<jint>(s.lockedBPH),
        static_cast<jint>(s.detectedTickCount),
        static_cast<jint>(s.phaseSecondsRemaining),
        static_cast<jint>(s.isManualBPH ? 1 : 0)
    };
    env->SetIntArrayRegion(arr, 0, 5, buf);
    return arr;
}

} // extern "C"
