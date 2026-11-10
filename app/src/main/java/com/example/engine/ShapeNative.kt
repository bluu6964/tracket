package com.example.engine

import android.graphics.Bitmap
import com.example.model.FILL_GRADIENT
import com.example.model.FILL_IMAGE
import com.example.model.FILL_NONE
import com.example.model.TimelineClip
import com.example.model.depthX
import com.example.model.depthY
import com.example.model.effectiveScalePercent
import com.example.model.opacityFraction

/** Raw JNI functions of libam_engine.so (shape + sticker part, cpp/engine/shape_jni.cpp). Use [NativeShapePainter]. */
internal object ShapeNative {
    @JvmStatic external fun nativeAvailable(): Boolean

    /** p = 27 floats, c = 4 ARGB ints; see shape_jni.cpp. Draws onto an ARGB_8888 bitmap, false = not drawn. */
    @JvmStatic external fun nativeDrawShape(bitmap: Bitmap, p: FloatArray, c: IntArray, fillBitmap: Bitmap?): Boolean

    /** p = 9 floats; see shape_jni.cpp. */
    @JvmStatic external fun nativeDrawSticker(bitmap: Bitmap, sticker: Bitmap, p: FloatArray): Boolean

    val isAvailable: Boolean by lazy {
        DecoderPool.isAvailable && try { nativeAvailable() } catch (_: Throwable) { false }
    }
}

/**
 * Draws shape and sticker layers with the C++ engine (shape_engine.cpp) straight into the export's overlay bitmap.
 * Same box, transform order, fill, stroke, crop and opacity as [LayerPainter.drawShape] / `drawSticker`; both return
 * false when they cannot (library missing, bitmap not ARGB_8888), then the Canvas version runs as before.
 */
internal class NativeShapePainter(
    /** Picture of a picture fill: the export waits for it ([FillImages.loadSync]). */
    private val fillImage: (String) -> Bitmap? = { FillImages.loadSync(it) }
) {
    private val p = FloatArray(27)
    private val c = IntArray(4)
    private val sp = FloatArray(9)

    fun drawShape(
        bmp: Bitmap, clip: TimelineClip, frameW: Int, frameH: Int, k: Float, density: Float,
        applyOpacity: Boolean, crop: FloatArray?
    ): Boolean {
        if (!ShapeNative.isAvailable || bmp.config != Bitmap.Config.ARGB_8888 || bmp.isRecycled) return false
        val base = 65f * density * k
        // Same shapes as the editor canvas (Compose, CanvasArea.kt) draws them: the editor's "OVAL" and any unknown
        // pattern are circles, "Wide Rect" is the full box with 10 dp corners, rounded = 16 dp corners.
        p[0] = when ((clip.shapePattern ?: "CIRCLE").uppercase()) {
            "RECT", "RECTANGLE", "SQUARE" -> 1f
            "ROUNDED", "ROUNDED_SQUARE", "ROUNDED_RECT", "WIDE_ROUNDED_RECT" -> 2f
            "TRIANGLE" -> 4f
            "PENTAGON" -> 5f
            "STAR" -> 6f
            else -> 0f
        }
        // Corner radius slot (px; the C++ side clamps it to the box and reads <= 0 as its own default): the clip's
        // own radius when one is set (dp of the 130 dp box, like the built-in corners below), else the built-in
        // corners. The 0.01 px floor keeps a stored 0 a square corner instead of the C++ "default" reading.
        p[23] = if (p[0] == 2f && clip.shapeCornerRadius >= 0f) {
            (base * clip.shapeCornerRadius / 65f).coerceIn(0.01f, base)
        } else when ((clip.shapePattern ?: "").uppercase()) {
            "WIDE_ROUNDED_RECT" -> base * 10f / 65f
            else -> base * 16f / 65f
        }
        p[1] = base
        p[2] = frameW / 2f + clip.depthX * k
        p[3] = frameH / 2f + clip.depthY * k
        p[4] = (clip.effectiveScalePercent / 100f).coerceAtLeast(0.01f)
        p[5] = clip.rotationDeg
        p[6] = if (clip.isFlippedH) -1f else 1f
        p[7] = if (clip.isFlippedV) -1f else 1f
        p[8] = if (applyOpacity) clip.opacityFraction.coerceIn(0f, 1f) else 1f
        p[9] = crop?.get(0) ?: 0f
        p[10] = crop?.get(1) ?: 0f
        p[11] = crop?.get(2) ?: 1f
        p[12] = crop?.get(3) ?: 1f
        p[13] = if (crop != null) 1f else 0f
        p[14] = if (clip.fillMode == FILL_NONE) 0f else 1f
        p[15] = (clip.strokeWidth.coerceAtLeast(0f) * density * k).coerceAtLeast(0f)
        // Stroke trim (draw-on): the visible outline fractions, and whether the trim gates the fill
        // (an imported line, whose paint is its path) instead of a stroke.
        p[24] = clip.strokeTrimStart.coerceIn(0f, 1f)
        p[25] = clip.strokeTrimEnd.coerceIn(0f, 1f)
        p[26] = if (clip.strokeTrimFill) 1f else 0f
        c[0] = argb(clip.textColor.takeIf { it != 0L } ?: clip.color, 0xFFFF3377)
        c[1] = argb(clip.strokeColor, 0xFFFFFFFF)
        var fillBmp: Bitmap? = null
        when (clip.fillMode) {
            FILL_GRADIENT -> {
                p[16] = 1f
                p[17] = clip.gradType.toFloat()
                p[18] = clip.gradX1; p[19] = clip.gradY1; p[20] = clip.gradX2; p[21] = clip.gradY2
                c[2] = clip.gradColor1.toInt()   // raw ARGB, like buildGradientShader
                c[3] = clip.gradColor2.toInt()
            }
            FILL_IMAGE -> {
                p[16] = 2f
                p[22] = clip.fillImageMode.toFloat()
                // Missing picture = no fill (like the Canvas version): the C++ side draws no fill without pixels.
                fillBmp = clip.fillImageUri?.let(fillImage)?.takeIf { it.config == Bitmap.Config.ARGB_8888 && !it.isRecycled }
            }
            else -> p[16] = 0f
        }
        return try {
            ShapeNative.nativeDrawShape(bmp, p, c, fillBmp)
        } catch (_: Throwable) {
            false
        }
    }

    fun drawSticker(
        bmp: Bitmap, sticker: Bitmap, clip: TimelineClip, frameW: Int, frameH: Int, k: Float, density: Float,
        applyOpacity: Boolean
    ): Boolean {
        if (!ShapeNative.isAvailable || bmp.config != Bitmap.Config.ARGB_8888 || bmp.isRecycled) return false
        if (sticker.config != Bitmap.Config.ARGB_8888 || sticker.isRecycled) return false
        val base = 65f * density * k
        val bw = sticker.width.toFloat()
        val bh = sticker.height.toFloat()
        // Picture fitted inside the 130 dp box keeping its aspect (same rule as LayerPainter.drawSticker).
        if (bw >= bh) { sp[0] = base; sp[1] = base * bh / bw } else { sp[0] = base * bw / bh; sp[1] = base }
        sp[2] = frameW / 2f + clip.depthX * k
        sp[3] = frameH / 2f + clip.depthY * k
        sp[4] = (clip.effectiveScalePercent / 100f).coerceAtLeast(0.01f)
        sp[5] = clip.rotationDeg
        sp[6] = if (clip.isFlippedH) -1f else 1f
        sp[7] = if (clip.isFlippedV) -1f else 1f
        sp[8] = if (applyOpacity) clip.opacityFraction.coerceIn(0f, 1f) else 1f
        return try {
            ShapeNative.nativeDrawSticker(bmp, sticker, sp)
        } catch (_: Throwable) {
            false
        }
    }

    /** Same rule as LayerPainter.argb: 0 = fallback colour, alpha 0 = opaque. */
    private fun argb(value: Long, fallback: Long): Int {
        val v = if (value == 0L) fallback else value
        val a = ((v shr 24) and 0xFF).toInt().let { if (it == 0) 255 else it }
        val r = ((v shr 16) and 0xFF).toInt()
        val g = ((v shr 8) and 0xFF).toInt()
        val b = (v and 0xFF).toInt()
        return android.graphics.Color.argb(a, r, g, b)
    }
}


/**
 * True when the C++ preview draws this shape / sticker layer ([NativePreviewEngine]); the Compose layer then stays
 * invisible and only keeps size, selection and touch. 3D rotation and a pivot that is not the centre (v126) are done by the
 * renderer too: the layer is drawn upright into its own picture and placed like a video layer (OverlayPose.kt).
 */
internal fun nativeShapeEligible(clip: TimelineClip): Boolean {
    if (clip.type != com.example.model.LayerType.SHAPE && clip.type != com.example.model.LayerType.STICKER) return false
    if (!ShapeNative.isAvailable) return false
    return true
}
