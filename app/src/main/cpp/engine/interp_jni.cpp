// JNI bridge for com.example.engine.InterpNative (frame_interp.h): slow-motion frame interpolation.
#include <jni.h>

#include "frame_interp.h"

namespace {
inline am::FlowField* F(jlong p) { return reinterpret_cast<am::FlowField*>(p); }
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_example_engine_InterpNative_nativeCreate(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new am::FlowField());
}

JNIEXPORT void JNICALL Java_com_example_engine_InterpNative_nativeDestroy(JNIEnv*, jclass, jlong p) { delete F(p); }

// The motion a -> b (both w * h RGBA8). False: too small / bad arrays.
JNIEXPORT jboolean JNICALL Java_com_example_engine_InterpNative_nativeEstimate(JNIEnv* env, jclass, jlong p, jbyteArray a,
                                                                              jbyteArray b, jint w, jint h) {
    if (!p || !a || !b || w <= 0 || h <= 0) return JNI_FALSE;
    const jsize need = (jsize)((int64_t)w * h * 4);
    if (env->GetArrayLength(a) < need || env->GetArrayLength(b) < need) return JNI_FALSE;
    jbyte* pa = env->GetByteArrayElements(a, nullptr);
    jbyte* pb = env->GetByteArrayElements(b, nullptr);
    const bool ok = am::interpEstimateFlow(reinterpret_cast<const uint8_t*>(pa), reinterpret_cast<const uint8_t*>(pb), w, h, F(p));
    env->ReleaseByteArrayElements(a, pa, JNI_ABORT);
    env->ReleaseByteArrayElements(b, pb, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// The picture at time f (0..1) between a and b, with the motion of the last nativeEstimate. out = w * h * 4 bytes.
JNIEXPORT jboolean JNICALL Java_com_example_engine_InterpNative_nativeRender(JNIEnv* env, jclass, jlong p, jbyteArray a,
                                                                            jbyteArray b, jint w, jint h, jfloat f,
                                                                            jbyteArray out) {
    if (!p || !a || !b || !out || w <= 0 || h <= 0 || F(p)->gw < 1) return JNI_FALSE;
    const jsize need = (jsize)((int64_t)w * h * 4);
    if (env->GetArrayLength(a) < need || env->GetArrayLength(b) < need || env->GetArrayLength(out) < need) return JNI_FALSE;
    jbyte* pa = env->GetByteArrayElements(a, nullptr);
    jbyte* pb = env->GetByteArrayElements(b, nullptr);
    jbyte* po = env->GetByteArrayElements(out, nullptr);
    am::interpRender(reinterpret_cast<const uint8_t*>(pa), reinterpret_cast<const uint8_t*>(pb), w, h, *F(p), f,
                     reinterpret_cast<uint8_t*>(po));
    env->ReleaseByteArrayElements(a, pa, JNI_ABORT);
    env->ReleaseByteArrayElements(b, pb, JNI_ABORT);
    env->ReleaseByteArrayElements(out, po, 0);
    return JNI_TRUE;
}

}  // extern "C"
