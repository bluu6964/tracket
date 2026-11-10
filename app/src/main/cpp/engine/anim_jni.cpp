// JNI bridge between com.example.engine.AnimNative (Kotlin) and am::LayerAnim.
// No GL, no thread rules: a handle may be used from any one thread at a time.
#include <jni.h>

#include <vector>

#include "camera.h"
#include <cmath>
#include "layer_anim.h"
#include "overlay_pose.h"

using am::LayerAnim;

namespace {
inline LayerAnim* A(jlong p) { return reinterpret_cast<LayerAnim*>(p); }

template <typename Arr, typename T, void (JNIEnv::*Get)(Arr, jsize, jsize, T*)>
std::vector<T> readArray(JNIEnv* env, Arr arr) {
    std::vector<T> v;
    if (!arr) return v;
    const jsize n = env->GetArrayLength(arr);
    v.resize((size_t)n);
    if (n > 0) (env->*Get)(arr, 0, n, v.data());
    return v;
}
}  // namespace

extern "C" {

// meta[3] = startMs, durationMs, trimStartMs
// stat[17] = transformX, transformY, transformZ, scalePercent, rotationDeg, pivotX, pivotY,
//            cropWidth%, cropHeight%, cropHorizontal%, cropVertical%, opacity%, speed, rotation3dX, rotation3dY,
//            strokeTrimStart, strokeTrimEnd (the last two only when present, stat[15..16])
// flags: bit0 = flipH, bit1 = flipV
// keyCounts[P_COUNT] (prop order = enum Prop), keyTimes[total], keyVals[total * 6] = a, b, x1, y1, x2, y2
// track* may be null (layer is not tracked). Returns 0 when the arguments are malformed.
JNIEXPORT jlong JNICALL Java_com_example_engine_AnimNative_nativeCreate(
    JNIEnv* env, jclass, jlongArray metaA, jfloatArray statA, jint flags, jintArray countsA, jlongArray timesA,
    jfloatArray valsA, jlongArray trackTimesA, jfloatArray trackDxA, jfloatArray trackDyA,
    jfloatArray trackRotA, jfloatArray trackScaleA) {
    auto meta = readArray<jlongArray, jlong, &JNIEnv::GetLongArrayRegion>(env, metaA);
    auto stat = readArray<jfloatArray, jfloat, &JNIEnv::GetFloatArrayRegion>(env, statA);
    auto counts = readArray<jintArray, jint, &JNIEnv::GetIntArrayRegion>(env, countsA);
    auto times = readArray<jlongArray, jlong, &JNIEnv::GetLongArrayRegion>(env, timesA);
    auto vals = readArray<jfloatArray, jfloat, &JNIEnv::GetFloatArrayRegion>(env, valsA);
    auto tt = readArray<jlongArray, jlong, &JNIEnv::GetLongArrayRegion>(env, trackTimesA);
    auto tdx = readArray<jfloatArray, jfloat, &JNIEnv::GetFloatArrayRegion>(env, trackDxA);
    auto tdy = readArray<jfloatArray, jfloat, &JNIEnv::GetFloatArrayRegion>(env, trackDyA);
    auto trot = readArray<jfloatArray, jfloat, &JNIEnv::GetFloatArrayRegion>(env, trackRotA);
    auto tscl = readArray<jfloatArray, jfloat, &JNIEnv::GetFloatArrayRegion>(env, trackScaleA);

    if (meta.size() < 3 || stat.size() < 15 || counts.size() != (size_t)am::P_COUNT) return 0;
    size_t total = 0;
    for (jint c : counts) {
        if (c < 0) return 0;
        total += (size_t)c;
    }
    if (times.size() != total || vals.size() != total * 6) return 0;
    if (tt.size() != tdx.size() || tt.size() != tdy.size()) return 0;
    if ((!trot.empty() && trot.size() != tt.size()) || (!tscl.empty() && tscl.size() != tt.size())) return 0;

    auto* a = new LayerAnim();
    a->st.startMs = meta[0];
    a->st.durationMs = meta[1];
    a->st.trimStartMs = meta[2];
    a->st.transformX = stat[0];
    a->st.transformY = stat[1];
    a->st.transformZ = stat[2];
    a->st.scalePercent = stat[3];
    a->st.rotationDeg = stat[4];
    a->st.pivotX = stat[5];
    a->st.pivotY = stat[6];
    a->st.cropWidthPercent = stat[7];
    a->st.cropHeightPercent = stat[8];
    a->st.cropHorizontalPercent = stat[9];
    a->st.cropVerticalPercent = stat[10];
    a->st.opacity = stat[11];
    a->st.speed = stat[12];
    a->st.rotation3dX = stat[13];
    a->st.rotation3dY = stat[14];
    if (stat.size() >= 17) {
        a->st.strokeTrimStart = stat[15];
        a->st.strokeTrimEnd = stat[16];
    }
    a->st.flipH = (flags & 1) != 0;
    a->st.flipV = (flags & 2) != 0;
    size_t i = 0;
    for (int p = 0; p < am::P_COUNT; ++p) {
        for (jint n = 0; n < counts[(size_t)p]; ++n, ++i) {
            am::Key k;
            k.timeMs = times[i];
            const jfloat* v = &vals[i * 6];
            k.a = v[0];
            k.b = v[1];
            k.easing.x1 = v[2];
            k.easing.y1 = v[3];
            k.easing.x2 = v[4];
            k.easing.y2 = v[5];
            a->keys[p].push_back(k);
        }
    }
    a->trackTimes.assign(tt.begin(), tt.end());
    a->trackDx.assign(tdx.begin(), tdx.end());
    a->trackDy.assign(tdy.begin(), tdy.end());
    a->trackRot.assign(trot.begin(), trot.end());
    a->trackScale.assign(tscl.begin(), tscl.end());
    a->finalize();
    return reinterpret_cast<jlong>(a);
}

JNIEXPORT void JNICALL Java_com_example_engine_AnimNative_nativeDestroy(JNIEnv*, jclass, jlong p) { delete A(p); }

JNIEXPORT jfloat JNICALL Java_com_example_engine_AnimNative_nativeExportSpeed(JNIEnv*, jclass, jlong p) {
    return A(p)->exportSpeed();
}

JNIEXPORT jlong JNICALL Java_com_example_engine_AnimNative_nativeSourceMs(JNIEnv*, jclass, jlong p, jlong timeMs) {
    return A(p)->sourceMsAt(timeMs);
}

// out[17] = offsetX, offsetY, scaleX, scaleY, rotationDeg, opacity (0..1), cropX, cropY, cropW, cropH,
//           rotX, rotY, pivotX, pivotY, baseX, baseY, perspD  (3D, see FrameParams)
JNIEXPORT void JNICALL Java_com_example_engine_AnimNative_nativeEval(JNIEnv* env, jclass, jlong p, jlong timeMs,
                                                                     jint outW, jint outH, jfloat previewW,
                                                                     jfloatArray out) {
    const am::FrameParams f = A(p)->eval(timeMs, outW, outH, previewW);
    const jfloat v[17] = {f.offsetX, f.offsetY, f.scaleX, f.scaleY, f.rotationDeg, f.opacity, f.cropX, f.cropY,
                          f.cropW,   f.cropH,   f.rotX,   f.rotY,   f.pivotX,      f.pivotY,  f.baseX, f.baseY,
                          f.perspD};
    env->SetFloatArrayRegion(out, 0, env->GetArrayLength(out) < 17 ? env->GetArrayLength(out) : 17, v);
}

// Preview: out[21] = posX, posY, posZ, scale%, rotationDeg, cropW%, cropH%, cropX%, cropY%, opacity%, speed, rotX, rotY,
//            fill a/b, grad1 a/b, grad2 a/b (packed colours), strokeTrimStart, strokeTrimEnd (outline fractions)
JNIEXPORT void JNICALL Java_com_example_engine_AnimNative_nativeClipAt(JNIEnv* env, jclass, jlong p, jlong timeMs,
                                                                       jlong trackMs, jfloatArray out) {
    const am::ClipValues c = A(p)->clipAt(timeMs, trackMs);
    const jfloat v[21] = {c.posX,         c.posY,         c.posZ,         c.scalePercent,   c.rotationDeg,  c.cropWPercent,
                          c.cropHPercent, c.cropXPercent, c.cropYPercent, c.opacityPercent, c.speed,
                          c.rotX,         c.rotY,         c.fillA,        c.fillB,          c.grad1A,       c.grad1B,
                          c.grad2A,       c.grad2B,       c.strokeTrimStart, c.strokeTrimEnd};
    const jsize n = env->GetArrayLength(out) < 21 ? env->GetArrayLength(out) : 21;
    env->SetFloatArrayRegion(out, 0, n, v);
}

// Text / shape / sticker layer that is turned in 3D or pivots around a point other than its centre (overlay_pose.h).
// in[13] = boxW, boxH, scale, flipH (0/1), flipV (0/1), rotationDeg, rotX, rotY, pivotX, pivotY, transX, transY, perspD
// out[11] = offsetX, offsetY, rotationDeg, rotX, rotY, pivotX, pivotY, baseX, baseY, perspD, is3d (0 / 1)
JNIEXPORT void JNICALL Java_com_example_engine_AnimNative_nativeOverlayPose(JNIEnv* env, jclass, jfloatArray inA,
                                                                           jfloatArray outA) {
    if (!inA || !outA || env->GetArrayLength(inA) < 13 || env->GetArrayLength(outA) < 11) return;
    jfloat v[13];
    env->GetFloatArrayRegion(inA, 0, 13, v);
    am::OverlayPoseIn in;
    in.boxW = v[0]; in.boxH = v[1]; in.scale = v[2];
    in.flipH = v[3] > 0.5f; in.flipV = v[4] > 0.5f;
    in.rotationDeg = v[5]; in.rotX = v[6]; in.rotY = v[7];
    in.pivotX = v[8]; in.pivotY = v[9];
    in.transX = v[10]; in.transY = v[11];
    in.perspD = v[12];
    const am::OverlayPoseOut o = am::overlayPose(in);
    const jfloat r[11] = {o.offsetX, o.offsetY, o.rotationDeg, o.rotX, o.rotY, o.pivotX,
                          o.pivotY,  o.baseX,   o.baseY,       o.perspD, o.is3d ? 1.f : 0.f};
    env->SetFloatArrayRegion(outA, 0, 11, r);
}

// 3D camera (camera.h): where the corners of a layer land on the canvas. in[am::kCamIn], out[8]. false = behind the camera.
JNIEXPORT jboolean JNICALL Java_com_example_engine_AnimNative_nativeCameraQuad(JNIEnv* env, jclass, jfloatArray inA,
                                                                             jfloatArray outA) {
    if (!inA || !outA || env->GetArrayLength(inA) < am::kCamIn || env->GetArrayLength(outA) < 8) return JNI_FALSE;
    jfloat v[am::kCamIn];
    env->GetFloatArrayRegion(inA, 0, am::kCamIn, v);
    float q[8];
    if (!am::cameraQuad(v, q)) return JNI_FALSE;
    env->SetFloatArrayRegion(outA, 0, 8, q);
    return JNI_TRUE;
}

// 3D camera (camera.h): depth of the layer's centre (bigger = farther) for the stacking order. NaN = behind the camera.
JNIEXPORT jfloat JNICALL Java_com_example_engine_AnimNative_nativeCameraDepth(JNIEnv* env, jclass, jfloatArray inA) {
    if (!inA || env->GetArrayLength(inA) < am::kCamIn) return NAN;
    jfloat v[am::kCamIn];
    env->GetFloatArrayRegion(inA, 0, am::kCamIn, v);
    float d = 0.f;
    return am::cameraDepth(v, &d) ? d : NAN;
}

}  // extern "C"
