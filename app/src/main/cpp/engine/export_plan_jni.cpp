// JNI bridge for com.example.engine.ExportPlanNative -> am::export_plan.
#include <jni.h>

#include <string>
#include <vector>

#include "export_plan.h"

extern "C" {

JNIEXPORT jint JNICALL Java_com_example_engine_ExportPlanNative_nativeTotalFrames(JNIEnv*, jclass, jlong durationMs,
                                                                                jint fps) {
    return am::totalFrames(durationMs, fps);
}

JNIEXPORT jint JNICALL Java_com_example_engine_ExportPlanNative_nativeBitrate(JNIEnv* env, jclass, jint w, jint h,
                                                                            jint fps, jint customKbps,
                                                                            jstring quality) {
    std::string q;
    if (quality) {
        const char* s = env->GetStringUTFChars(quality, nullptr);
        if (s) { q = s; env->ReleaseStringUTFChars(quality, s); }
    }
    return am::videoBitrate(w, h, fps, customKbps, q);
}

// flags[n] (am::AudioClipFlags), nums[n * 4] = startMs, durationMs, fadeInMs, fadeOutMs, vols[n].
// out* arrays have room for n entries. Returns how many clips were picked.
JNIEXPORT jint JNICALL Java_com_example_engine_ExportPlanNative_nativeAudioPick(
    JNIEnv* env, jclass, jintArray flagsA, jlongArray numsA, jfloatArray volsA, jlong videoEndMs, jintArray outIdxA,
    jfloatArray outVolA, jlongArray outFadeInA, jlongArray outFadeOutA) {
    const jsize n = env->GetArrayLength(flagsA);
    if (n <= 0 || env->GetArrayLength(numsA) < n * 4 || env->GetArrayLength(volsA) < n) return 0;
    std::vector<jint> flags((size_t)n);
    std::vector<jlong> nums((size_t)n * 4);
    std::vector<jfloat> vols((size_t)n);
    env->GetIntArrayRegion(flagsA, 0, n, flags.data());
    env->GetLongArrayRegion(numsA, 0, n * 4, nums.data());
    env->GetFloatArrayRegion(volsA, 0, n, vols.data());
    std::vector<am::AudioCandidate> clips((size_t)n);
    for (jsize i = 0; i < n; ++i) {
        am::AudioCandidate& c = clips[(size_t)i];
        c.flags = flags[(size_t)i];
        c.startMs = nums[(size_t)i * 4];
        c.durationMs = nums[(size_t)i * 4 + 1];
        c.fadeInMs = nums[(size_t)i * 4 + 2];
        c.fadeOutMs = nums[(size_t)i * 4 + 3];
        c.volume = vols[(size_t)i];
    }
    const std::vector<am::AudioPick> picks = am::pickAudioClips(clips, videoEndMs);
    const jsize m = (jsize)picks.size();
    std::vector<jint> idx((size_t)m);
    std::vector<jfloat> vol((size_t)m);
    std::vector<jlong> fin((size_t)m), fout((size_t)m);
    for (jsize i = 0; i < m; ++i) {
        idx[(size_t)i] = picks[(size_t)i].index;
        vol[(size_t)i] = picks[(size_t)i].volume;
        fin[(size_t)i] = picks[(size_t)i].fadeInMs;
        fout[(size_t)i] = picks[(size_t)i].fadeOutMs;
    }
    if (m > 0) {
        env->SetIntArrayRegion(outIdxA, 0, m, idx.data());
        env->SetFloatArrayRegion(outVolA, 0, m, vol.data());
        env->SetLongArrayRegion(outFadeInA, 0, m, fin.data());
        env->SetLongArrayRegion(outFadeOutA, 0, m, fout.data());
    }
    return m;
}

}  // extern "C"
