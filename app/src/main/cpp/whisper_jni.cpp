// JNI bridge between com.example.engine.WhisperNative (Kotlin) and whisper.cpp.
//
// Everything runs on the thread that calls nativeTranscribe: whisper.cpp calls the segment and
// progress callbacks on that thread, so the JNIEnv of the call can be used for them. The abort
// callback is the exception - ggml calls it from its worker threads, so it only reads an atomic.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstring>

#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

std::atomic<bool> g_cancel{false};

struct CallbackState {
    JNIEnv *env;
    jobject listener;
    jmethodID onSegment;   // (JJ[B)V   start ms, end ms, UTF-8 text
    jmethodID onProgress;  // (I)V      0..100
};

void onNewSegment(struct whisper_context *ctx, struct whisper_state * /*state*/, int nNew, void *user) {
    auto *s = static_cast<CallbackState *>(user);
    const int total = whisper_full_n_segments(ctx);
    for (int i = total - nNew; i < total; ++i) {
        if (i < 0) continue;
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text == nullptr) continue;
        // whisper time stamps are in units of 10 ms
        const jlong t0 = (jlong) whisper_full_get_segment_t0(ctx, i) * 10;
        const jlong t1 = (jlong) whisper_full_get_segment_t1(ctx, i) * 10;
        const size_t len = strlen(text);
        // Raw bytes, not NewStringUTF: a segment can end in the middle of a multi-byte character,
        // and invalid UTF-8 aborts the VM under CheckJNI. Kotlin decodes the bytes.
        jbyteArray bytes = s->env->NewByteArray((jsize) len);
        if (bytes == nullptr) continue;
        s->env->SetByteArrayRegion(bytes, 0, (jsize) len, reinterpret_cast<const jbyte *>(text));
        s->env->CallVoidMethod(s->listener, s->onSegment, t0, t1, bytes);
        s->env->DeleteLocalRef(bytes);
        if (s->env->ExceptionCheck()) {
            s->env->ExceptionClear();
            g_cancel.store(true);
        }
    }
}

void onProgress(struct whisper_context * /*ctx*/, struct whisper_state * /*state*/, int progress, void *user) {
    auto *s = static_cast<CallbackState *>(user);
    s->env->CallVoidMethod(s->listener, s->onProgress, (jint) progress);
    if (s->env->ExceptionCheck()) {
        s->env->ExceptionClear();
        g_cancel.store(true);
    }
}

bool abortRequested(void * /*user*/) {
    return g_cancel.load();
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_engine_WhisperNative_nativeInit(JNIEnv *env, jclass, jstring path) {
    const char *cpath = env->GetStringUTFChars(path, nullptr);
    if (cpath == nullptr) return 0;
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;  // CPU only build
    whisper_context *ctx = whisper_init_from_file_with_params(cpath, cparams);
    env->ReleaseStringUTFChars(path, cpath);
    if (ctx == nullptr) LOGE("could not load the model");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_example_engine_WhisperNative_nativeFree(JNIEnv *, jclass, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx != nullptr) whisper_free(ctx);
}

JNIEXPORT void JNICALL
Java_com_example_engine_WhisperNative_nativeSetCancel(JNIEnv *, jclass, jboolean cancel) {
    g_cancel.store(cancel == JNI_TRUE);
}

JNIEXPORT jstring JNICALL
Java_com_example_engine_WhisperNative_nativeSystemInfo(JNIEnv *env, jclass) {
    return env->NewStringUTF(whisper_print_system_info());
}

// Returns 0 on success, non-zero when whisper_full failed or was cancelled.
JNIEXPORT jint JNICALL
Java_com_example_engine_WhisperNative_nativeTranscribe(
        JNIEnv *env, jclass, jlong ctxPtr, jfloatArray samples, jstring language,
        jint threads, jobject listener) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx == nullptr) return -1;

    jclass listenerClass = env->GetObjectClass(listener);
    CallbackState state{env, listener,
                        env->GetMethodID(listenerClass, "onSegment", "(JJ[B)V"),
                        env->GetMethodID(listenerClass, "onProgress", "(I)V")};
    if (state.onSegment == nullptr || state.onProgress == nullptr) {
        env->ExceptionClear();
        return -2;
    }

    const jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    const char *lang = env->GetStringUTFChars(language, nullptr);
    if (data == nullptr || lang == nullptr) {
        if (data != nullptr) env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
        if (lang != nullptr) env->ReleaseStringUTFChars(language, lang);
        return -3;
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = lang;            // "auto" = detect from the first 30 s of every block
    params.detect_language = false;    // true would only detect and stop
    params.translate = false;
    params.no_context = true;          // every block is decoded on its own
    params.single_segment = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.greedy.best_of = 1;
    params.new_segment_callback = onNewSegment;
    params.new_segment_callback_user_data = &state;
    params.progress_callback = onProgress;
    params.progress_callback_user_data = &state;
    params.abort_callback = abortRequested;
    params.abort_callback_user_data = nullptr;

    const int rc = whisper_full(ctx, params, data, (int) n);

    env->ReleaseStringUTFChars(language, lang);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    return rc;
}

}  // extern "C"
