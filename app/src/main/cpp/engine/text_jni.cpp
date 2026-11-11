// JNI bridge between com.example.engine.TextNative (Kotlin) and am::text::draw.
#include <jni.h>
#include <android/bitmap.h>

#include <string>

#include "text_engine.h"

namespace {

std::string toUtf8(JNIEnv* env, jstring s) {
    std::string out;
    if (!s) return out;
    const jsize n = env->GetStringLength(s);
    const jchar* u = env->GetStringChars(s, nullptr);
    if (!u) return out;
    for (jsize i = 0; i < n; ++i) {
        uint32_t c = u[i];
        if (c >= 0xD800 && c < 0xDC00 && i + 1 < n && u[i + 1] >= 0xDC00 && u[i + 1] < 0xE000) {
            c = 0x10000 + ((c - 0xD800) << 10) + (u[i + 1] - 0xDC00);
            ++i;
        }
        if (c < 0x80) out += (char)c;
        else if (c < 0x800) { out += (char)(0xC0 | (c >> 6)); out += (char)(0x80 | (c & 0x3F)); }
        else if (c < 0x10000) { out += (char)(0xE0 | (c >> 12)); out += (char)(0x80 | ((c >> 6) & 0x3F)); out += (char)(0x80 | (c & 0x3F)); }
        else { out += (char)(0xF0 | (c >> 18)); out += (char)(0x80 | ((c >> 12) & 0x3F)); out += (char)(0x80 | ((c >> 6) & 0x3F)); out += (char)(0x80 | (c & 0x3F)); }
    }
    env->ReleaseStringChars(s, u);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_example_engine_TextNative_nativeAvailable(JNIEnv*, jclass) {
    return am::text::available() ? JNI_TRUE : JNI_FALSE;
}

// True when emoji can be drawn natively: the PNG decoder exists (Android 11+) and the device's emoji font is a bitmap
// font the engine can read. Layers with emoji are only given to nativeDraw when this is true.
JNIEXPORT jboolean JNICALL Java_com_example_engine_TextNative_nativeEmojiAvailable(JNIEnv*, jclass) {
    return am::text::emojiAvailable() ? JNI_TRUE : JNI_FALSE;
}

// p[37] (+2 out): 0 fontPx, 1 letterSpacingPx, 2 lineMult, 3 align, 4 maxTextW, 5 padH, 6 padV, 7 scale, 8 rotationDeg,
//        9 flipX, 10 flipY, 11 cx, 12 cy, 13 alpha, 14..17 crop l t r b, 18 weight, 19 italic, 20 underline,
//        21 strike, 22 fillOn, 23 strokeW, 24 shadowOn, 25 shadowBlur, 26 shadowDx, 27 shadowDy, 28 bgOn, 29 bgCornerFrac,
//        30 fillKind (0 solid, 1 gradient, 2 picture), 31 gradType, 32..35 gradient x1 y1 x2 y2, 36 picture mode
// c[6]: fill, stroke, shadow, background colour, gradient colour 1, gradient colour 2 (ARGB ints)
// fillBitmap: the fill picture (premultiplied RGBA8888) or null
// levelsA / paraRtlA: bidi embedding level per code point, 1 flag per paragraph (see Request::bidiLevels) or null
// animA (null = no text animator, up to 15 floats; each group has its own on flag so the three can travel together):
//        0 texttransform on (0 / 1), 1 component (0 letter, 1 word, 2 line), 2 start, 3 end, 4 dx, 5 dy, 6 alpha,
//        7 recolour (0 / 1) - the recolour ARGB is the 7th entry of cA (when cA has 7), see Request::animOn;
//        8 textprogress on (0 / 1), 9 progress start, 10 progress end, see Request::progressOn;
//        11 drawing progress on (0 / 1), 12 draw start, 13 draw end, 14 outline width px, see Request::drawOn.
JNIEXPORT jboolean JNICALL Java_com_example_engine_TextNative_nativeDraw(
    JNIEnv* env, jclass, jobject bitmap, jstring text, jstring fontPath, jstring family, jfloatArray pA, jintArray cA,
    jobject fillBitmap, jbyteArray levelsA, jbyteArray paraRtlA, jfloatArray animA) {
    if (!bitmap || !pA || !cA || env->GetArrayLength(pA) < 37 || env->GetArrayLength(cA) < 6) return JNI_FALSE;
    float p[37];
    jint c[7] = {0, 0, 0, 0, 0, 0, (jint)0xFF00FF00};
    env->GetFloatArrayRegion(pA, 0, 37, p);
    env->GetIntArrayRegion(cA, 0, env->GetArrayLength(cA) >= 7 ? 7 : 6, c);

    am::text::Request r;
    r.text = toUtf8(env, text);
    r.fontPath = toUtf8(env, fontPath);
    const std::string fam = toUtf8(env, family);
    if (!fam.empty()) r.family = fam;
    r.fontPx = p[0]; r.letterSpacing = p[1]; r.lineMult = p[2]; r.align = (int)p[3];
    r.maxTextW = p[4]; r.padH = p[5]; r.padV = p[6];
    r.scale = p[7]; r.rotationDeg = p[8]; r.flipX = p[9] < 0 ? -1.f : 1.f; r.flipY = p[10] < 0 ? -1.f : 1.f;
    r.cx = p[11]; r.cy = p[12]; r.alpha = p[13];
    for (int i = 0; i < 4; ++i) r.crop[i] = p[14 + i];
    r.weight = (int)p[18]; r.italic = p[19] > 0.5f; r.underline = p[20] > 0.5f; r.strike = p[21] > 0.5f;
    r.fillOn = p[22] > 0.5f; r.strokeW = p[23];
    r.shadowOn = p[24] > 0.5f; r.shadowBlur = p[25]; r.shadowDx = p[26]; r.shadowDy = p[27];
    r.bgOn = p[28] > 0.5f; r.bgCornerFrac = p[29];
    r.fillKind = (int)p[30]; r.gradType = (int)p[31];
    r.gx1 = p[32]; r.gy1 = p[33]; r.gx2 = p[34]; r.gy2 = p[35]; r.fillImgMode = (int)p[36];
    r.grad1 = (uint32_t)c[4]; r.grad2 = (uint32_t)c[5];
    if (levelsA) {
        const jsize n = env->GetArrayLength(levelsA);
        r.bidiLevels.resize((size_t)n);
        if (n > 0) env->GetByteArrayRegion(levelsA, 0, n, reinterpret_cast<jbyte*>(r.bidiLevels.data()));
    }
    if (paraRtlA) {
        const jsize n = env->GetArrayLength(paraRtlA);
        r.paraRtl.resize((size_t)n);
        if (n > 0) env->GetByteArrayRegion(paraRtlA, 0, n, reinterpret_cast<jbyte*>(r.paraRtl.data()));
    }
    if (animA && env->GetArrayLength(animA) >= 11) {
        float a[11];
        env->GetFloatArrayRegion(animA, 0, 11, a);
        if (a[0] > 0.5f) {
            r.animOn = true;
            r.animComponent = a[1] < 0.5f ? 0 : a[1] < 1.5f ? 1 : 2;
            r.animStart = a[2]; r.animEnd = a[3]; r.animDx = a[4]; r.animDy = a[5]; r.animAlpha = a[6];
            r.animRecolor = a[7] > 0.5f;
            r.animArgb = (uint32_t)c[6];
        }
        if (a[8] > 0.5f) {
            r.progressOn = true;
            r.progressStart = a[9]; r.progressEnd = a[10];
        }
    }
    if (animA && env->GetArrayLength(animA) >= 15) {
        float a[4];
        env->GetFloatArrayRegion(animA, 11, 4, a);
        if (a[0] > 0.5f) {
            r.drawOn = true;
            r.drawStart = a[1]; r.drawEnd = a[2]; r.drawStrokeW = a[3];
        }
    }
    r.fillArgb = (uint32_t)c[0]; r.strokeArgb = (uint32_t)c[1]; r.shadowArgb = (uint32_t)c[2]; r.bgArgb = (uint32_t)c[3];

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) return JNI_FALSE;
    // Picture fill: lock the picture too (a different Bitmap object, premultiplied RGBA8888).
    void* fillPixels = nullptr;
    bool fillLocked = false;
    if (fillBitmap) {
        AndroidBitmapInfo fi;
        if (AndroidBitmap_getInfo(env, fillBitmap, &fi) == ANDROID_BITMAP_RESULT_SUCCESS &&
            fi.format == ANDROID_BITMAP_FORMAT_RGBA_8888 && fi.width > 0 && fi.height > 0 &&
            AndroidBitmap_lockPixels(env, fillBitmap, &fillPixels) == ANDROID_BITMAP_RESULT_SUCCESS && fillPixels) {
            fillLocked = true;
            r.fillPix = (const uint8_t*)fillPixels;
            r.fillW = (int)fi.width; r.fillH = (int)fi.height; r.fillStride = (int)fi.stride;
        }
    }
    am::text::Stats stats;
    const bool ok = am::text::draw((uint8_t*)pixels, (int)info.width, (int)info.height, (int)info.stride, r, &stats);
    // p[37], p[38] (when the array has them): the layout box at scale 1, frame pixels. Kotlin needs it for a pivot that is
    // not the centre and for the 3D turn (NativeTextPainter.lastBoxW / lastBoxH).
    if (ok && env->GetArrayLength(pA) >= 39) {
        const jfloat box[2] = {stats.boxW, stats.boxH};
        env->SetFloatArrayRegion(pA, 37, 2, box);
    }
    if (fillLocked) AndroidBitmap_unlockPixels(env, fillBitmap);
    AndroidBitmap_unlockPixels(env, bitmap);
    return ok ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
