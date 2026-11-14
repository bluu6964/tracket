#include "prof.h"
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <vector>
#include "native_export_encoder.h"
#include "renderer.h"

using am::NativeExportEncoder;
using am::Renderer;
namespace { inline NativeExportEncoder* E(jlong p) { return reinterpret_cast<NativeExportEncoder*>(p); } }

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_engine_NativeExportNative_nativeCreate(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new NativeExportEncoder());
}
extern "C" JNIEXPORT void JNICALL
Java_com_example_engine_NativeExportNative_nativeDestroy(JNIEnv*, jclass, jlong p) { delete E(p); }
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_engine_NativeExportNative_nativeStart(JNIEnv* env, jclass, jlong p, jlong renderer,
        jstring path, jint width, jint height, jint fps, jint bitrate) {
    const char* utf = env->GetStringUTFChars(path, nullptr);
    if (!utf) return JNI_FALSE;
    const bool ok = E(p)->start(reinterpret_cast<Renderer*>(renderer), utf, width, height, fps, bitrate);
    env->ReleaseStringUTFChars(path, utf);
    return ok ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_engine_NativeExportNative_nativeRenderSolidFrame(JNIEnv*, jclass, jlong p,
        jfloat r, jfloat g, jfloat b, jfloat a, jlong ptsNs) {
    return E(p)->renderSolidFrame(r, g, b, a, ptsNs) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_engine_NativeExportNative_nativeSubmitFrame(JNIEnv*, jclass, jlong p, jlong ptsNs) {
    return E(p)->submitFrame(ptsNs) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_engine_NativeExportNative_nativeFinish(JNIEnv*, jclass, jlong p) {
    return E(p)->finish() ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL
Java_com_example_engine_NativeExportNative_nativeCancel(JNIEnv*, jclass, jlong p) { E(p)->cancel(); }
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_engine_NativeExportNative_nativeError(JNIEnv* env, jclass, jlong p) {
    return env->NewStringUTF(E(p)->error().c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_engine_NativeExportNative_nativeAddAudioClip(JNIEnv* env, jclass, jlong p, jint fd, jlong length,
        jlong startUs, jlong durUs, jlong trimStartUs, jdouble speed, jfloat volume, jlong fadeInUs, jlong fadeOutUs,
        jlongArray duckRelUs, jfloatArray duckGains, jfloatArray noiseProfile, jfloat nrStrength) {
    std::vector<int64_t> rel;
    std::vector<float> gains;
    if (duckRelUs && duckGains) {
        const jsize n = std::min(env->GetArrayLength(duckRelUs), env->GetArrayLength(duckGains));
        if (n > 0) {
            rel.resize((size_t)n);
            gains.resize((size_t)n);
            env->GetLongArrayRegion(duckRelUs, 0, n, rel.data());
            env->GetFloatArrayRegion(duckGains, 0, n, gains.data());
        }
    }
    // Noise reduction (build 195): the clip's per-bin noise profile + gate strength;
    // null / empty = the source passes through ungated.
    std::vector<float> profile;
    float strength = 0.f;
    if (noiseProfile) {
        const jsize n = env->GetArrayLength(noiseProfile);
        if (n > 0) {
            profile.resize((size_t)n);
            env->GetFloatArrayRegion(noiseProfile, 0, n, profile.data());
            strength = nrStrength;
        }
    }
    return E(p)->addAudioClip(fd, length, startUs, durUs, trimStartUs, speed, volume, fadeInUs, fadeOutUs,
                              rel, gains, profile, strength)
               ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_engine_NativeExportNative_nativeStartAudio(JNIEnv*, jclass, jlong p, jlong totalUs, jint bitrate) {
    return E(p)->startAudio(totalUs, bitrate) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_engine_NativeExportNative_nativePumpAudio(JNIEnv*, jclass, jlong p, jlong upToUs) {
    return E(p)->pumpAudio(upToUs) ? JNI_TRUE : JNI_FALSE;
}

// Wall-clock counters (ns) of the slow parts of the audio path, see prof.h.
extern "C" JNIEXPORT jlong JNICALL
Java_com_example_engine_NativeExportNative_nativeProfNs(JNIEnv*, jclass, jint id) {
    return (jlong)am::profCounter(id).load(std::memory_order_relaxed);
}
