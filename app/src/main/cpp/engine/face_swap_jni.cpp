// JNI bridge for com.example.engine.FaceSwapNative (face_swap.h): light face swap along the 468-point face mesh.
#include <jni.h>

#include "face_swap.h"

namespace {
inline am::FaceSource* S(jlong p) { return reinterpret_cast<am::FaceSource*>(p); }
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_example_engine_FaceSwapNative_nativeCreate(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new am::FaceSource());
}

JNIEXPORT void JNICALL Java_com_example_engine_FaceSwapNative_nativeDestroy(JNIEnv*, jclass, jlong p) { delete S(p); }

// The photo face: rgba (w * h * 4, top row first) and its 468 landmarks (x, y in pixels, top-left origin).
JNIEXPORT jboolean JNICALL Java_com_example_engine_FaceSwapNative_nativeSetSource(JNIEnv* env, jclass, jlong p, jbyteArray rgba,
                                                                                 jint w, jint h, jfloatArray lm) {
    if (!p || !rgba || !lm || w <= 0 || h <= 0) return JNI_FALSE;
    if (env->GetArrayLength(rgba) < w * h * 4 || env->GetArrayLength(lm) < am::kFaceLm * 2) return JNI_FALSE;
    jbyte* px = env->GetByteArrayElements(rgba, nullptr);
    jfloat* l = env->GetFloatArrayElements(lm, nullptr);
    const bool ok = am::swapSetSource(S(p), reinterpret_cast<const uint8_t*>(px), w, h, l);
    env->ReleaseByteArrayElements(rgba, px, JNI_ABORT);
    env->ReleaseFloatArrayElements(lm, l, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Pastes the photo face onto the face with landmarks lm in dst (w * h RGBA8, in place; the landmarks use dst's row order).
JNIEXPORT jboolean JNICALL Java_com_example_engine_FaceSwapNative_nativeApply(JNIEnv* env, jclass, jlong p, jbyteArray dst,
                                                                             jint w, jint h, jfloatArray lm, jfloat strength) {
    if (!p || !dst || !lm || w <= 0 || h <= 0) return JNI_FALSE;
    if (env->GetArrayLength(dst) < w * h * 4 || env->GetArrayLength(lm) < am::kFaceLm * 2) return JNI_FALSE;
    jbyte* px = env->GetByteArrayElements(dst, nullptr);
    jfloat* l = env->GetFloatArrayElements(lm, nullptr);
    const bool ok = am::swapApply(*S(p), reinterpret_cast<uint8_t*>(px), w, h, l, strength);
    env->ReleaseByteArrayElements(dst, px, ok ? 0 : JNI_ABORT);
    env->ReleaseFloatArrayElements(lm, l, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
