// JNI bridge for com.example.engine.StabilizeNative (stabilize.h).
#include <jni.h>

#include <vector>

#include "stabilize.h"

extern "C" {

// quads / out = n * 8 floats. autoZoom: least zoom that hides the moving edges. Returns the zoom (>= 1), -1 = failed.
JNIEXPORT jfloat JNICALL Java_com_example_engine_StabilizeNative_nativeQuads(JNIEnv* env, jclass, jfloatArray quads, jint n,
                                                                            jboolean autoZoom, jfloat fixedZoom,
                                                                            jfloatArray out) {
    if (!quads || !out || n < 1) return -1.f;
    if (env->GetArrayLength(quads) < n * 8 || env->GetArrayLength(out) < n * 8) return -1.f;
    std::vector<jfloat> q((size_t)n * 8), o((size_t)n * 8);
    env->GetFloatArrayRegion(quads, 0, n * 8, q.data());
    const float z = am::stabilizeQuads(q.data(), n, autoZoom == JNI_TRUE, fixedZoom, o.data());
    if (z < 0.f) return z;
    env->SetFloatArrayRegion(out, 0, n * 8, o.data());
    return z;
}

// Auto stabilize (no points): camera path smoothed with a Gaussian of sigmaFrames frames (<= 0: tripod). aspect = width / height.
JNIEXPORT jfloat JNICALL Java_com_example_engine_StabilizeNative_nativeSmooth(JNIEnv* env, jclass, jfloatArray quads, jint n,
                                                                             jfloat aspect, jfloat sigmaFrames, jboolean autoZoom,
                                                                             jfloat fixedZoom, jfloatArray out) {
    if (!quads || !out || n < 1) return -1.f;
    if (env->GetArrayLength(quads) < n * 8 || env->GetArrayLength(out) < n * 8) return -1.f;
    std::vector<jfloat> q((size_t)n * 8), o((size_t)n * 8);
    env->GetFloatArrayRegion(quads, 0, n * 8, q.data());
    const float z = am::stabilizeSmooth(q.data(), n, aspect, sigmaFrames, autoZoom == JNI_TRUE, fixedZoom, o.data());
    if (z < 0.f) return z;
    env->SetFloatArrayRegion(out, 0, n * 8, o.data());
    return z;
}

}  // extern "C"
