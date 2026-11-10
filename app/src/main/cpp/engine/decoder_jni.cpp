// JNI bridge between com.example.engine.DecoderPoolNative (Kotlin) and am::DecoderPool.
#include <android/native_window_jni.h>
#include <jni.h>

#include "decoder_pool.h"

using am::DecoderPool;

namespace {
inline DecoderPool* P(jlong p) { return reinterpret_cast<DecoderPool*>(p); }
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_example_engine_DecoderPoolNative_nativeCreate(JNIEnv*, jclass, jint maxHw,
                                                                              jint maxSw) {
    return reinterpret_cast<jlong>(new DecoderPool(maxHw, maxSw));
}

JNIEXPORT void JNICALL Java_com_example_engine_DecoderPoolNative_nativeDestroy(JNIEnv*, jclass, jlong p) {
    delete P(p);
}

// info = long[5]: width, height, rotation, durationUs, fps * 1000
JNIEXPORT jint JNICALL Java_com_example_engine_DecoderPoolNative_nativeOpen(
    JNIEnv* env, jclass, jlong p, jint fd, jlong offset, jlong length, jobject surface, jlongArray info,
    jboolean preferSoftware) {
    ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    am::ClipInfo ci;
    const int id = P(p)->open(fd, offset, length, window, &ci, preferSoftware == JNI_TRUE);  // takes ownership of window
    if (id > 0 && info) {
        const jlong v[5] = {ci.width, ci.height, ci.rotation, (jlong)ci.durationUs, (jlong)(ci.fps * 1000.f)};
        env->SetLongArrayRegion(info, 0, 5, v);
    }
    return id;
}

JNIEXPORT void JNICALL Java_com_example_engine_DecoderPoolNative_nativeClose(JNIEnv*, jclass, jlong p, jint id) {
    P(p)->close(id);
}

JNIEXPORT jint JNICALL Java_com_example_engine_DecoderPoolNative_nativeSeek(JNIEnv* env, jclass, jlong p, jint id,
                                                                          jlong targetUs, jlongArray out) {
    int64_t pts = 0;
    const int st = P(p)->seek(id, targetUs, &pts);
    if (st >= 0 && out) {
        const jlong v = pts;
        env->SetLongArrayRegion(out, 0, 1, &v);
    }
    return st;
}

JNIEXPORT jint JNICALL Java_com_example_engine_DecoderPoolNative_nativeNext(JNIEnv* env, jclass, jlong p, jint id,
                                                                          jlongArray out) {
    int64_t pts = 0;
    const int st = P(p)->next(id, &pts);
    if (st >= 0 && out) {
        const jlong v = pts;
        env->SetLongArrayRegion(out, 0, 1, &v);
    }
    return st;
}

JNIEXPORT void JNICALL Java_com_example_engine_DecoderPoolNative_nativeSetMaxHw(JNIEnv*, jclass, jlong p, jint n) {
    P(p)->setMaxHw(n);
}

// out = int[8]: activeHw, activeSw, maxHw, maxSw, opened, evictions, fallbacks, errors
JNIEXPORT void JNICALL Java_com_example_engine_DecoderPoolNative_nativeStats(JNIEnv* env, jclass, jlong p,
                                                                           jintArray out) {
    const am::PoolStats s = P(p)->stats();
    const jint v[8] = {s.activeHw, s.activeSw, s.maxHw, s.maxSw, s.opened, s.evictions, s.fallbacks, s.errors};
    env->SetIntArrayRegion(out, 0, 8, v);
}

}  // extern "C"
