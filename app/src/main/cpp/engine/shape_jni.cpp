// JNI bridge between com.example.engine.ShapeNative (Kotlin) and am::shape.
#include <jni.h>
#include <android/bitmap.h>

#include "shape_engine.h"

namespace {

// Locks a bitmap (RGBA_8888 only) for the lifetime of the object.
struct Locked {
    JNIEnv* env;
    jobject bmp;
    AndroidBitmapInfo info{};
    void* px = nullptr;
    bool ok = false;
    Locked(JNIEnv* e, jobject b) : env(e), bmp(b) {
        if (!b) return;
        if (AndroidBitmap_getInfo(e, b, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return;
        if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 || info.height == 0) return;
        if (AndroidBitmap_lockPixels(e, b, &px) != ANDROID_BITMAP_RESULT_SUCCESS || !px) return;
        ok = true;
    }
    ~Locked() { if (ok) AndroidBitmap_unlockPixels(env, bmp); }
};

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_example_engine_ShapeNative_nativeAvailable(JNIEnv*, jclass) { return JNI_TRUE; }

// p[24]: 0 kind, 1 base, 2 cx, 3 cy, 4 scale, 5 rotationDeg, 6 flipX, 7 flipY, 8 alpha, 9..12 crop l t r b, 13 hasCrop,
//        14 fillOn, 15 strokeW, 16 fillKind (0 solid, 1 gradient, 2 picture), 17 gradType, 18..21 gradient x1 y1 x2 y2,
//        22 picture mode, 23 corner radius (ROUNDED)
// c[4]: fill, stroke, gradient colour 1, gradient colour 2 (ARGB ints)
JNIEXPORT jboolean JNICALL Java_com_example_engine_ShapeNative_nativeDrawShape(
    JNIEnv* env, jclass, jobject bitmap, jfloatArray pA, jintArray cA, jobject fillBitmap) {
    if (!bitmap || !pA || !cA || env->GetArrayLength(pA) < 24 || env->GetArrayLength(cA) < 4) return JNI_FALSE;
    float p[24];
    jint c[4];
    env->GetFloatArrayRegion(pA, 0, 24, p);
    env->GetIntArrayRegion(cA, 0, 4, c);

    am::shape::Request r;
    r.kind = (int)p[0]; r.base = p[1]; r.cx = p[2]; r.cy = p[3]; r.scale = p[4]; r.rotationDeg = p[5];
    r.flipX = p[6] < 0 ? -1.f : 1.f; r.flipY = p[7] < 0 ? -1.f : 1.f; r.alpha = p[8];
    for (int i = 0; i < 4; ++i) r.crop[i] = p[9 + i];
    r.hasCrop = p[13] > 0.5f;
    r.fillOn = p[14] > 0.5f; r.strokeW = p[15];
    r.fillKind = (int)p[16]; r.gradType = (int)p[17];
    r.gx1 = p[18]; r.gy1 = p[19]; r.gx2 = p[20]; r.gy2 = p[21]; r.fillImgMode = (int)p[22]; r.corner = p[23];
    r.fillArgb = (uint32_t)c[0]; r.strokeArgb = (uint32_t)c[1]; r.grad1 = (uint32_t)c[2]; r.grad2 = (uint32_t)c[3];

    Locked dst(env, bitmap);
    if (!dst.ok) return JNI_FALSE;
    Locked fill(env, fillBitmap);
    if (fill.ok) {
        r.fillPix = (const uint8_t*)fill.px;
        r.fillW = (int)fill.info.width; r.fillH = (int)fill.info.height; r.fillStride = (int)fill.info.stride;
    }
    return am::shape::drawShape((uint8_t*)dst.px, (int)dst.info.width, (int)dst.info.height, (int)dst.info.stride, r)
               ? JNI_TRUE : JNI_FALSE;
}

// p[9]: 0 halfW, 1 halfH, 2 cx, 3 cy, 4 scale, 5 rotationDeg, 6 flipX, 7 flipY, 8 alpha
JNIEXPORT jboolean JNICALL Java_com_example_engine_ShapeNative_nativeDrawSticker(
    JNIEnv* env, jclass, jobject bitmap, jobject sticker, jfloatArray pA) {
    if (!bitmap || !sticker || !pA || env->GetArrayLength(pA) < 9) return JNI_FALSE;
    float p[9];
    env->GetFloatArrayRegion(pA, 0, 9, p);
    am::shape::StickerRequest r;
    r.halfW = p[0]; r.halfH = p[1]; r.cx = p[2]; r.cy = p[3]; r.scale = p[4]; r.rotationDeg = p[5];
    r.flipX = p[6] < 0 ? -1.f : 1.f; r.flipY = p[7] < 0 ? -1.f : 1.f; r.alpha = p[8];

    Locked dst(env, bitmap);
    if (!dst.ok) return JNI_FALSE;
    Locked st(env, sticker);
    if (!st.ok) return JNI_FALSE;
    r.pix = (const uint8_t*)st.px;
    r.w = (int)st.info.width; r.h = (int)st.info.height; r.stride = (int)st.info.stride;
    return am::shape::drawSticker((uint8_t*)dst.px, (int)dst.info.width, (int)dst.info.height, (int)dst.info.stride, r)
               ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
