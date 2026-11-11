// JNI bridge between com.example.engine.RendererNative (Kotlin) and am::Renderer.
// EVERY call must come from the same thread (the GL thread that called nativeCreate).
#include <android/native_window_jni.h>
#include <GLES3/gl3.h>
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <vector>

#include "image_loader.h"
#include "quad_warp.h"
#include "renderer.h"

using am::Renderer;

namespace {
inline Renderer* R(jlong p) { return reinterpret_cast<Renderer*>(p); }
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_example_engine_RendererNative_nativeCreate(JNIEnv*, jclass) {
    auto* r = new Renderer();
    if (!r->init()) {
        delete r;
        return 0;
    }
    return reinterpret_cast<jlong>(r);
}

JNIEXPORT void JNICALL Java_com_example_engine_RendererNative_nativeDestroy(JNIEnv*, jclass, jlong p) {
    delete R(p);
}

JNIEXPORT jint JNICALL Java_com_example_engine_RendererNative_nativeCreateTexture(JNIEnv*, jclass, jlong p,
                                                                                jboolean oes) {
    return (jint)R(p)->createTexture(oes == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_example_engine_RendererNative_nativeDeleteTexture(JNIEnv*, jclass, jlong p,
                                                                                jint tex) {
    R(p)->deleteTexture((uint32_t)tex);
}

// surface == null: back to the 1x1 bootstrap output.
JNIEXPORT jboolean JNICALL Java_com_example_engine_RendererNative_nativeSetOutput(JNIEnv* env, jclass, jlong p,
                                                                                jobject surface) {
    ANativeWindow* w = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    const bool ok = R(p)->setOutputWindow(w);
    if (w) ANativeWindow_release(w);  // EGL keeps its own reference
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_example_engine_RendererNative_nativeSetOffscreen(JNIEnv*, jclass, jlong p,
                                                                                   jint w, jint h) {
    return R(p)->setOutputOffscreen(w, h) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_example_engine_RendererNative_nativeOutputSize(JNIEnv* env, jclass, jlong p,
                                                                             jintArray out) {
    const jint v[2] = {R(p)->width(), R(p)->height()};
    env->SetIntArrayRegion(out, 0, 2, v);
}

JNIEXPORT void JNICALL Java_com_example_engine_RendererNative_nativeBeginFrame(JNIEnv*, jclass, jlong p, jfloat r,
                                                                             jfloat g, jfloat b, jfloat a) {
    R(p)->beginFrame(r, g, b, a);
}

// params (25 or 29 floats): 0-15 SurfaceTexture matrix, 16 contentW, 17 contentH, 18 contentRotation,
// 19 offsetX, 20 offsetY, 21 scaleX, 22 scaleY, 23 rotationDeg, 24 opacity,
// optional crop (fractions of the upright picture): 25 left, 26 top, 27 width, 28 height
// optional 3D (v121): 29 rotX, 30 rotY, 31 pivotX, 32 pivotY, 33 baseX, 34 baseY, 35 perspD (see LayerDraw)
// optional corner pin (v142): 36 on, 37..44 quad TL, TR, BR, BL (canvas pixels, origin top-left)
// Group pass: see Renderer::beginGroup / endGroup.
JNIEXPORT jboolean JNICALL Java_com_example_engine_RendererNative_nativeBeginGroup(JNIEnv*, jclass, jlong p) {
    return R(p)->beginGroup() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_com_example_engine_RendererNative_nativeEndGroup(JNIEnv*, jclass, jlong p) {
    return (jint)R(p)->endGroup();
}

JNIEXPORT void JNICALL Java_com_example_engine_RendererNative_nativeDrawLayer(JNIEnv* env, jclass, jlong p,
                                                                            jint tex, jboolean oes,
                                                                            jfloatArray params) {
    jfloat v[46] = {0};
    const jsize len = env->GetArrayLength(params);
    env->GetFloatArrayRegion(params, 0, len < 46 ? len : 46, v);
    am::LayerDraw d;
    d.texture = (uint32_t)tex;
    d.oes = oes == JNI_TRUE;
    for (int i = 0; i < 16; ++i) d.texMatrix[i] = v[i];
    d.contentW = (int)v[16];
    d.contentH = (int)v[17];
    d.contentRotation = (int)v[18];
    d.offsetX = v[19];
    d.offsetY = v[20];
    d.scaleX = v[21];
    d.scaleY = v[22];
    d.rotationDeg = v[23];
    d.opacity = v[24];
    if (len >= 29) {
        d.cropX = v[25];
        d.cropY = v[26];
        d.cropW = v[27];
        d.cropH = v[28];
    }
    if (len >= 36) {  // 3D: 29 rotX, 30 rotY, 31 pivotX, 32 pivotY, 33 baseX, 34 baseY, 35 perspD
        d.rotX = v[29];
        d.rotY = v[30];
        d.pivotX = v[31];
        d.pivotY = v[32];
        d.baseX = v[33];
        d.baseY = v[34];
        d.perspD = v[35];
    }
    if (len >= 45) {  // corner pin (v142): 36 on (> 0.5), 37..44 = TL, TR, BR, BL in canvas pixels from the top-left
        d.pin = v[36] > 0.5f;
        for (int i = 0; i < 8; ++i) d.pinQ[i] = v[37 + i];
    }
    if (len >= 46) d.gain = v[45];  // brightness factor (v143, Remove patch)
    R(p)->drawLayer(d);
}


// Blend-mode variants. params: same layout as nativeDrawLayer (opacity at 24 is ignored, [opacity] is used).
// canvas: 3 floats (canvas colour r, g, b) used by the matte modes.
JNIEXPORT void JNICALL Java_com_example_engine_RendererNative_nativeDrawLayerBlended(
    JNIEnv* env, jclass, jlong p, jint tex, jboolean oes, jfloatArray params, jint mode, jfloat opacity,
    jfloatArray canvas) {
    jfloat v[46] = {0};
    const jsize len = env->GetArrayLength(params);
    env->GetFloatArrayRegion(params, 0, len < 46 ? len : 46, v);
    jfloat rgb[3] = {0, 0, 0};
    if (canvas && env->GetArrayLength(canvas) >= 3) env->GetFloatArrayRegion(canvas, 0, 3, rgb);
    am::LayerDraw d;
    d.texture = (uint32_t)tex;
    d.oes = oes == JNI_TRUE;
    for (int i = 0; i < 16; ++i) d.texMatrix[i] = v[i];
    d.contentW = (int)v[16];
    d.contentH = (int)v[17];
    d.contentRotation = (int)v[18];
    d.offsetX = v[19];
    d.offsetY = v[20];
    d.scaleX = v[21];
    d.scaleY = v[22];
    d.rotationDeg = v[23];
    d.opacity = opacity;
    if (len >= 29) {
        d.cropX = v[25];
        d.cropY = v[26];
        d.cropW = v[27];
        d.cropH = v[28];
    }
    if (len >= 36) {  // 3D: 29 rotX, 30 rotY, 31 pivotX, 32 pivotY, 33 baseX, 34 baseY, 35 perspD
        d.rotX = v[29];
        d.rotY = v[30];
        d.pivotX = v[31];
        d.pivotY = v[32];
        d.baseX = v[33];
        d.baseY = v[34];
        d.perspD = v[35];
    }
    if (len >= 45) {  // corner pin (v142): 36 on (> 0.5), 37..44 = TL, TR, BR, BL in canvas pixels from the top-left
        d.pin = v[36] > 0.5f;
        for (int i = 0; i < 8; ++i) d.pinQ[i] = v[37 + i];
    }
    if (len >= 46) d.gain = v[45];  // brightness factor (v143, Remove patch)
    R(p)->drawLayerBlended(d, (int)mode, opacity, rgb);
}

JNIEXPORT jboolean JNICALL Java_com_example_engine_RendererNative_nativeLayerLooksBlack(
    JNIEnv* env, jclass, jlong p, jint tex, jboolean oes, jfloatArray params) {
    jfloat v[46] = {0};
    const jsize len = env->GetArrayLength(params);
    env->GetFloatArrayRegion(params, 0, len < 46 ? len : 46, v);
    am::LayerDraw d;
    d.texture = (uint32_t)tex;
    d.oes = oes == JNI_TRUE;
    for (int i = 0; i < 16; ++i) d.texMatrix[i] = v[i];
    d.contentW = (int)v[16];
    d.contentH = (int)v[17];
    d.contentRotation = (int)v[18];
    d.offsetX = v[19];
    d.offsetY = v[20];
    d.scaleX = v[21];
    d.scaleY = v[22];
    d.rotationDeg = v[23];
    d.opacity = 1.f;
    if (len >= 29) {
        d.cropX = v[25];
        d.cropY = v[26];
        d.cropW = v[27];
        d.cropH = v[28];
    }
    if (len >= 36) {  // 3D: 29 rotX, 30 rotY, 31 pivotX, 32 pivotY, 33 baseX, 34 baseY, 35 perspD
        d.rotX = v[29];
        d.rotY = v[30];
        d.pivotX = v[31];
        d.pivotY = v[32];
        d.baseX = v[33];
        d.baseY = v[34];
        d.perspD = v[35];
    }
    if (len >= 45) {  // corner pin (v142): 36 on (> 0.5), 37..44 = TL, TR, BR, BL in canvas pixels from the top-left
        d.pin = v[36] > 0.5f;
        for (int i = 0; i < 8; ++i) d.pinQ[i] = v[37 + i];
    }
    return R(p)->layerLooksBlack(d) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_example_engine_RendererNative_nativeBlendTexture(JNIEnv* env, jclass, jlong p,
                                                                               jint tex, jint mode, jfloat opacity,
                                                                               jfloatArray canvas) {
    jfloat rgb[3] = {0, 0, 0};
    if (canvas && env->GetArrayLength(canvas) >= 3) env->GetFloatArrayRegion(canvas, 0, 3, rgb);
    R(p)->blendTexture((uint32_t)tex, (int)mode, opacity, rgb);
}

// ---- Face blur / cutout (renderer_fx.cpp) ----

// Loads (or re-uses) the mask picture of `slot` from a grey JPEG file. Returns an R8 texture id, 0 on failure.
JNIEXPORT jint JNICALL Java_com_example_engine_RendererNative_nativeLoadMask(JNIEnv* env, jclass, jlong p,
                                                                           jint slot, jstring path) {
    if (!path) return 0;
    const char* c = env->GetStringUTFChars(path, nullptr);
    if (!c) return 0;
    const uint32_t tex = R(p)->loadMask((int)slot, c);
    env->ReleaseStringUTFChars(path, c);
    return (jint)tex;
}

// params: same layout as nativeDrawLayer. Returns the texture of the processed, upright picture (draw it
// with an identity texture matrix, rotation 0, same content size), or 0 when nothing could be done.
JNIEXPORT jint JNICALL Java_com_example_engine_RendererNative_nativeApplyFx(
    JNIEnv* env, jclass, jlong p, jint tex, jboolean oes, jfloatArray params, jint faceMask, jfloat faceStrength,
    jint cutMask, jboolean flatten, jfloatArray canvas, jfloatArray ops, jint grainFrame, jfloatArray masks,
    jboolean bgAligned) {
    jfloat v[46] = {0};
    const jsize len = env->GetArrayLength(params);
    env->GetFloatArrayRegion(params, 0, len < 46 ? len : 46, v);
    am::LayerDraw d;
    d.texture = (uint32_t)tex;
    d.oes = oes == JNI_TRUE;
    for (int i = 0; i < 16; ++i) d.texMatrix[i] = v[i];
    d.contentW = (int)v[16];
    d.contentH = (int)v[17];
    d.contentRotation = (int)v[18];
    // Crop (same slots as nativeDrawLayer): the effects run on this part of the picture only.
    if (len >= 29 && v[27] > 0.f && v[28] > 0.f) {
        d.cropX = v[25];
        d.cropY = v[26];
        d.cropW = v[27];
        d.cropH = v[28];
    }
    am::LayerFx fx;
    fx.faceMask = (uint32_t)faceMask;
    fx.faceStrength = faceStrength;
    fx.cutMask = (uint32_t)cutMask;
    fx.flatten = flatten == JNI_TRUE;
    fx.bgAligned = bgAligned == JNI_TRUE;
    if (canvas && env->GetArrayLength(canvas) >= 3) env->GetFloatArrayRegion(canvas, 0, 3, fx.canvasRgb);
    // The layer's effects (Effects panel): groups of am::kFxStride floats = type + 5 parameters.
    if (ops) {
        jfloat flat[am::kFxMaxOps * am::kFxStride] = {0};
        jsize n = env->GetArrayLength(ops);
        if (n > am::kFxMaxOps * am::kFxStride) n = am::kFxMaxOps * am::kFxStride;
        env->GetFloatArrayRegion(ops, 0, n, flat);
        fx.nOps = am::fxParseOps(flat, (int)n, fx.ops);
    }
    fx.grainFrame = grainFrame;
    // The layer's masks (Masks panel): groups of am::kMaskStride floats, laid out for this picture's size.
    if (masks) {
        jfloat mflat[am::kMaskMax * am::kMaskStride] = {0};
        jsize n = env->GetArrayLength(masks);
        if (n > am::kMaskMax * am::kMaskStride) n = am::kMaskMax * am::kMaskStride;
        env->GetFloatArrayRegion(masks, 0, n, mflat);
        const int mW = std::max(1, (int)std::lround((double)d.contentW * std::fmin(1.f, std::fmax(0.01f, d.cropW))));
        const int mH = std::max(1, (int)std::lround((double)d.contentH * std::fmin(1.f, std::fmax(0.01f, d.cropH))));
        fx.nMasks = am::maskParseOps(mflat, (int)n, mW, mH, fx.masks);
    }
    am::LayerDraw out;
    if (!R(p)->applyFx(d, fx, &out)) return 0;
    return (jint)out.texture;
}

// Layer mask over a ready picture (a group layer's composed picture): params = the same layout as
// nativeDrawLayer for the picture; maskTex = a plain 2D texture of the mask layer (first row = top
// of the canvas, e.g. an uploaded Bitmap). Returns the cut picture's texture (draw it with an
// identity texture matrix, rotation 0, same content size), or 0 when nothing could be done.
JNIEXPORT jint JNICALL Java_com_example_engine_RendererNative_nativeApplyTexMask(
    JNIEnv* env, jclass, jlong p, jint tex, jboolean oes, jfloatArray params, jint maskTex) {
    jfloat v[46] = {0};
    const jsize len = env->GetArrayLength(params);
    env->GetFloatArrayRegion(params, 0, len < 46 ? len : 46, v);
    am::LayerDraw d;
    d.texture = (uint32_t)tex;
    d.oes = oes == JNI_TRUE;
    for (int i = 0; i < 16; ++i) d.texMatrix[i] = v[i];
    d.contentW = (int)v[16];
    d.contentH = (int)v[17];
    d.contentRotation = (int)v[18];
    if (len >= 29 && v[27] > 0.f && v[28] > 0.f) {
        d.cropX = v[25];
        d.cropY = v[26];
        d.cropW = v[27];
        d.cropH = v[28];
    }
    am::LayerDraw out;
    if (!R(p)->applyTexMask(d, (uint32_t)maskTex, &out)) return 0;
    return (jint)out.texture;
}

JNIEXPORT jboolean JNICALL Java_com_example_engine_RendererNative_nativeReadPixels(JNIEnv* env, jclass, jlong p,
                                                                                 jint x, jint y, jint w, jint h,
                                                                                 jbyteArray out) {
    if (env->GetArrayLength(out) < w * h * 4) return JNI_FALSE;
    jbyte* dst = env->GetByteArrayElements(out, nullptr);
    const bool ok = R(p)->readPixels(reinterpret_cast<uint8_t*>(dst), x, y, w, h);
    env->ReleaseByteArrayElements(out, dst, 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// w * h RGBA8 pixels (rows bottom-to-top) into the plain 2D texture [tex]. GL thread only.
JNIEXPORT jboolean JNICALL Java_com_example_engine_RendererNative_nativeUploadRgba(JNIEnv* env, jclass, jlong p, jint tex,
                                                                                 jint w, jint h, jbyteArray data) {
    if (!p || !data || w <= 0 || h <= 0 || env->GetArrayLength(data) < w * h * 4) return JNI_FALSE;
    jbyte* src = env->GetByteArrayElements(data, nullptr);
    const bool ok = R(p)->uploadRgba((uint32_t)tex, w, h, reinterpret_cast<const uint8_t*>(src));
    env->ReleaseByteArrayElements(data, src, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_example_engine_RendererNative_nativeEndFrame(JNIEnv*, jclass, jlong p,
                                                                               jlong ptsNs) {
    return R(p)->endFrame(ptsNs) ? JNI_TRUE : JNI_FALSE;
}

// Decodes a photo (fd opened by the caller, stays owned by it) with AImageDecoder and uploads it as a GL_TEXTURE_2D.
// out[0] = width, out[1] = height. Returns the texture id, 0 when the native decoder cannot do it (API < 30 or a
// decode error): the caller then falls back to the Kotlin decoder. GL thread only.
JNIEXPORT jint JNICALL Java_com_example_engine_RendererNative_nativeLoadImage(JNIEnv* env, jclass, jlong p, jint fd,
                                                                            jint maxSide, jintArray out) {
    std::vector<uint8_t> px;
    int w = 0, h = 0;
    if (!am::decodePhotoRgba((int)fd, (int)maxSide, &px, &w, &h)) return 0;
    const uint32_t tex = R(p)->createTexture(false);
    if (!tex) return 0;
    glGetError();  // forget older errors: only the upload below is judged
    glBindTexture(GL_TEXTURE_2D, tex);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
    if (glGetError() != GL_NO_ERROR) {
        R(p)->deleteTexture(tex);
        return 0;
    }
    const jint v[2] = {w, h};
    env->SetIntArrayRegion(out, 0, 2, v);
    return (jint)tex;
}

// Corner pin helper (no GL, no renderer): where the points of a video picture land on the canvas when that video is drawn
// with [params] (same layout as nativeDrawLayer; only offset / scale / rotation / crop / 3D / content size are read).
// uv = u, v pairs (0..1 of the upright picture, origin top-left), n pairs; out = x, y pairs in canvas pixels (origin top-left).
// Returns false when a point cannot be placed (behind the camera); out is then not complete.
JNIEXPORT jboolean JNICALL Java_com_example_engine_RendererNative_nativeQuadToCanvas(
    JNIEnv* env, jclass, jfloatArray params, jint canvasW, jint canvasH, jfloatArray uv, jfloatArray out) {
    jfloat v[46] = {0};
    const jsize len = env->GetArrayLength(params);
    env->GetFloatArrayRegion(params, 0, len < 46 ? len : 46, v);
    am::LayerDraw d;
    d.contentW = (int)v[16];
    d.contentH = (int)v[17];
    d.offsetX = v[19];
    d.offsetY = v[20];
    d.scaleX = v[21];
    d.scaleY = v[22];
    d.rotationDeg = v[23];
    if (len >= 29) {
        d.cropX = v[25];
        d.cropY = v[26];
        d.cropW = v[27];
        d.cropH = v[28];
    }
    if (len >= 36) {
        d.rotX = v[29];
        d.rotY = v[30];
        d.pivotX = v[31];
        d.pivotY = v[32];
        d.baseX = v[33];
        d.baseY = v[34];
        d.perspD = v[35];
    }
    const jsize n = env->GetArrayLength(uv) / 2;
    if (n <= 0 || d.contentW <= 0 || d.contentH <= 0 || env->GetArrayLength(out) < 2 * n) return JNI_FALSE;
    std::vector<jfloat> in((size_t)(2 * n)), res((size_t)(2 * n));
    env->GetFloatArrayRegion(uv, 0, 2 * n, in.data());
    const float cw = std::fmin(1.f, std::fmax(0.01f, d.cropW));
    const float ch = std::fmin(1.f, std::fmax(0.01f, d.cropH));
    const float cx = std::fmin(1.f - cw, std::fmax(0.f, d.cropX));
    const float cy = std::fmin(1.f - ch, std::fmax(0.f, d.cropY));
    const am::Mat4 model = am::layerModelFor(d, canvasW, canvasH);
    bool all = true;
    for (jsize i = 0; i < n; ++i) {
        float ox = 0, oy = 0;
        if (!am::pictureToCanvas(model, cx, cy, cw, ch, in[2 * i], in[2 * i + 1], &ox, &oy)) all = false;
        res[2 * i] = ox;
        res[2 * i + 1] = oy;
    }
    env->SetFloatArrayRegion(out, 0, 2 * n, res.data());
    return all ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
