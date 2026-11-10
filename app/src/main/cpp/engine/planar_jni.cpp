// JNI bridge between com.example.engine.PlanarNative (Kotlin) and am::PlanarTracker. No GL, no thread rules:
// a handle may be used from one thread at a time.
#include <jni.h>

#include <vector>

#include "planar_track.h"

using am::PlanarTracker;

namespace {
inline PlanarTracker* T(jlong p) { return reinterpret_cast<PlanarTracker*>(p); }
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_example_engine_PlanarNative_nativeCreate(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new PlanarTracker());
}

JNIEXPORT void JNICALL Java_com_example_engine_PlanarNative_nativeDestroy(JNIEnv*, jclass, jlong p) { delete T(p); }

// Global mode (auto stabilize): follow the whole picture with a similarity. Before nativeStart.
JNIEXPORT void JNICALL Java_com_example_engine_PlanarNative_nativeSetGlobal(JNIEnv*, jclass, jlong p, jboolean on) {
    if (p) T(p)->setGlobal(on == JNI_TRUE);
}

// gray = w * h floats (0..255); quad = 8 floats (TL, TR, BR, BL, picture pixels). False = too little texture / bad quad.
JNIEXPORT jboolean JNICALL Java_com_example_engine_PlanarNative_nativeStart(JNIEnv* env, jclass, jlong p,
                                                                           jfloatArray gray, jint w, jint h,
                                                                           jfloatArray quad) {
    if (!p || !gray || !quad || w <= 0 || h <= 0) return JNI_FALSE;
    if (env->GetArrayLength(gray) < w * h || env->GetArrayLength(quad) < 8) return JNI_FALSE;
    std::vector<jfloat> g((size_t)w * h);
    env->GetFloatArrayRegion(gray, 0, w * h, g.data());
    jfloat q[8];
    env->GetFloatArrayRegion(quad, 0, 8, q);
    return T(p)->start(g.data(), w, h, q) ? JNI_TRUE : JNI_FALSE;
}

// out = 8 floats, the corners after this frame (also when the frame was bridged / lost: the last good place).
JNIEXPORT jboolean JNICALL Java_com_example_engine_PlanarNative_nativeStep(JNIEnv* env, jclass, jlong p, jfloatArray gray,
                                                                          jint w, jint h, jfloatArray out) {
    if (!p || !gray || !out || w <= 0 || h <= 0) return JNI_FALSE;
    if (env->GetArrayLength(gray) < w * h || env->GetArrayLength(out) < 8) return JNI_FALSE;
    std::vector<jfloat> g((size_t)w * h);
    env->GetFloatArrayRegion(gray, 0, w * h, g.data());
    const bool ok = T(p)->step(g.data(), w, h);
    env->SetFloatArrayRegion(out, 0, 8, T(p)->quad());
    return ok ? JNI_TRUE : JNI_FALSE;
}

// The corners followed right now (x, y pairs, picture pixels) for the live display; returns the number of points.
JNIEXPORT jint JNICALL Java_com_example_engine_PlanarNative_nativePoints(JNIEnv* env, jclass, jlong p, jfloatArray out) {
    if (!p || !out) return 0;
    const int cap = env->GetArrayLength(out) / 2;
    std::vector<jfloat> tmp((size_t)(2 * PlanarTracker::kMaxPoints));
    const int n = T(p)->copyPoints(tmp.data(), cap < PlanarTracker::kMaxPoints ? cap : PlanarTracker::kMaxPoints);
    env->SetFloatArrayRegion(out, 0, 2 * n, tmp.data());
    return n;
}

}  // extern "C"
