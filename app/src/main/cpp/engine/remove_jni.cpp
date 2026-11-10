// JNI bridge between com.example.engine.RemoveNative (Kotlin) and am::RemovePlate (the clean plate of the Remove tool).
// No GL, no thread rules: a handle may be used from one thread at a time.
#include <jni.h>

#include <cstdint>
#include <vector>

#include "remove_plate.h"

using am::RemovePlate;

namespace {
inline RemovePlate* P(jlong p) { return reinterpret_cast<RemovePlate*>(p); }
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_example_engine_RemoveNative_nativeCreate(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new RemovePlate());
}

JNIEXPORT void JNICALL Java_com_example_engine_RemoveNative_nativeDestroy(JNIEnv*, jclass, jlong p) { delete P(p); }

// quad = 8 floats (u, v of the 4 corners on the reference frame), mask = mw * mh bytes (painted area on that frame's picture).
JNIEXPORT jboolean JNICALL Java_com_example_engine_RemoveNative_nativeSetMask(JNIEnv* env, jclass, jlong p, jfloatArray quad,
                                                                             jbyteArray mask, jint mw, jint mh, jint picW,
                                                                             jint picH, jint maxSide) {
    if (!p || !quad || !mask || mw <= 0 || mh <= 0) return JNI_FALSE;
    if (env->GetArrayLength(quad) < 8 || env->GetArrayLength(mask) < mw * mh) return JNI_FALSE;
    jfloat q[8];
    env->GetFloatArrayRegion(quad, 0, 8, q);
    std::vector<jbyte> m((size_t)mw * mh);
    env->GetByteArrayRegion(mask, 0, mw * mh, m.data());
    return P(p)->setMask(q, reinterpret_cast<const uint8_t*>(m.data()), mw, mh, picW, picH, maxSide) ? JNI_TRUE : JNI_FALSE;
}

// out[0] = plate width, out[1] = plate height; dom = 4 floats s0, t0, s1, t1 (the window of the surface the plate covers).
JNIEXPORT void JNICALL Java_com_example_engine_RemoveNative_nativePlateInfo(JNIEnv* env, jclass, jlong p, jintArray size,
                                                                           jfloatArray dom) {
    if (!p) return;
    if (size && env->GetArrayLength(size) >= 2) {
        const jint v[2] = {P(p)->plateW(), P(p)->plateH()};
        env->SetIntArrayRegion(size, 0, 2, v);
    }
    if (dom && env->GetArrayLength(dom) >= 4) env->SetFloatArrayRegion(dom, 0, 4, P(p)->domain());
}

// Mean grey level of the ring around the painted area on one tracked frame (0..255 grey floats), 0 = unknown.
JNIEXPORT jfloat JNICALL Java_com_example_engine_RemoveNative_nativeRingLuma(JNIEnv* env, jclass, jlong p, jfloatArray gray,
                                                                            jint w, jint h, jfloatArray quad) {
    if (!p || !gray || !quad || w <= 0 || h <= 0) return 0.f;
    if (env->GetArrayLength(gray) < w * h || env->GetArrayLength(quad) < 8) return 0.f;
    std::vector<jfloat> g((size_t)w * h);
    env->GetFloatArrayRegion(gray, 0, w * h, g.data());
    jfloat q[8];
    env->GetFloatArrayRegion(quad, 0, 8, q);
    return P(p)->ringLuma(g.data(), w, h, q);
}

// argb = w * h ints (Bitmap.getPixels), quad = the 4 corners on this frame (u, v). The first frame added is the reference.
JNIEXPORT jboolean JNICALL Java_com_example_engine_RemoveNative_nativeAddFrame(JNIEnv* env, jclass, jlong p, jintArray argb,
                                                                              jint w, jint h, jfloatArray quad) {
    if (!p || !argb || !quad || w <= 0 || h <= 0) return JNI_FALSE;
    if (env->GetArrayLength(argb) < w * h || env->GetArrayLength(quad) < 8) return JNI_FALSE;
    std::vector<jint> px((size_t)w * h);
    env->GetIntArrayRegion(argb, 0, w * h, px.data());
    jfloat q[8];
    env->GetFloatArrayRegion(quad, 0, 8, q);
    return P(p)->addFrame(reinterpret_cast<const uint32_t*>(px.data()), w, h, q) ? JNI_TRUE : JNI_FALSE;
}

// out = plateW * plateH * 4 bytes (premultiplied RGBA, row 0 = top).
JNIEXPORT jboolean JNICALL Java_com_example_engine_RemoveNative_nativeBuild(JNIEnv* env, jclass, jlong p, jboolean useFrames,
                                                                           jbyteArray out) {
    if (!p || !out) return JNI_FALSE;
    std::vector<uint8_t> rgba;
    if (!P(p)->build(useFrames == JNI_TRUE, &rgba)) return JNI_FALSE;
    if ((size_t)env->GetArrayLength(out) < rgba.size()) return JNI_FALSE;
    env->SetByteArrayRegion(out, 0, (jsize)rgba.size(), reinterpret_cast<const jbyte*>(rgba.data()));
    return JNI_TRUE;
}

// The 4 corners (u, v) of the plate's window when the surface has the corners [quad]; out = 8 floats. False = bad quad.
JNIEXPORT jboolean JNICALL Java_com_example_engine_RemoveNative_nativeSubQuad(JNIEnv* env, jclass, jfloatArray quad,
                                                                             jfloatArray dom, jfloatArray out) {
    if (!quad || !dom || !out) return JNI_FALSE;
    if (env->GetArrayLength(quad) < 8 || env->GetArrayLength(dom) < 4 || env->GetArrayLength(out) < 8) return JNI_FALSE;
    jfloat q[8], d[4], o[8];
    env->GetFloatArrayRegion(quad, 0, 8, q);
    env->GetFloatArrayRegion(dom, 0, 4, d);
    if (!RemovePlate::subQuad(q, d, o)) return JNI_FALSE;
    env->SetFloatArrayRegion(out, 0, 8, o);
    return JNI_TRUE;
}

}  // extern "C"
