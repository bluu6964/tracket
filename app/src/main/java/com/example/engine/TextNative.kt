package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import com.example.model.FILL_GRADIENT
import com.example.model.FILL_IMAGE
import com.example.model.FILL_NONE
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.depthX
import com.example.model.depthY
import com.example.model.effectiveScalePercent
import com.example.model.opacityFraction
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Raw JNI functions of libam_engine.so (text part, cpp/engine/text_jni.cpp). Use [NativeTextPainter]. */
internal object TextNative {
    @JvmStatic external fun nativeAvailable(): Boolean

    /** True when the engine can draw colour emoji on this phone (PNG decoder + a bitmap emoji font), see text_engine.h. */
    @JvmStatic external fun nativeEmojiAvailable(): Boolean

    /**
     * p = 37 floats (+ 2 that the engine fills: the layout box, see [NativeTextPainter.lastBoxW]), c = 6 ARGB ints (+ the text animator colour as the 7th); see text_jni.cpp. [anim] = 11 floats of the text animators (null = none): a texttransform group and a textprogress group, each with its own on flag. Draws onto an ARGB_8888 bitmap, false = not drawn.
     * [fillBitmap]: the picture of a picture fill (ARGB_8888), or null.
     */
    @JvmStatic external fun nativeDraw(
        bitmap: Bitmap, text: String, fontPath: String?, family: String?, p: FloatArray, c: IntArray,
        fillBitmap: Bitmap?, levels: ByteArray?, paraRtl: ByteArray?, anim: FloatArray?
    ): Boolean

    /** True when the library is loaded AND was built with FreeType + HarfBuzz. */
    val isAvailable: Boolean by lazy {
        DecoderPool.isAvailable && try { nativeAvailable() } catch (_: Throwable) { false }
    }

    /** Emoji are drawn by C++ only when this is true (a colour emoji font of the bitmap kind on Android 11+, or of the COLR kind); otherwise Compose / Canvas draw them. */
    val emojiAvailable: Boolean by lazy {
        val ok = isAvailable && try { nativeEmojiAvailable() } catch (_: Throwable) { false }
        android.util.Log.i("AmText", "emoji native available = $ok (sdk ${Build.VERSION.SDK_INT})")
        ok
    }
}

/**
 * Draws text layers with the C++ text engine (HarfBuzz shaping, FreeType glyphs) straight into the export's overlay
 * bitmap. Same layout rules as [LayerPainter]'s Canvas version (sizes are fractions of the frame width, same padding,
 * outline, shadow, background box) but it uses the layer's chosen font file, which the Canvas version ignored.
 * Emoji are drawn from the system's colour emoji font (bitmap font, PNG pictures decoded by the system). [draw] returns
 * false when the layer needs something it cannot do (emoji on a phone where [TextNative.emojiAvailable] is false,
 * Android older than 10, no font): the caller then draws it with the Canvas, so nothing is ever lost.
 */
internal class NativeTextPainter(
    private val fontFile: (String) -> String?,
    /** Picture of a picture fill: the export waits for it ([FillImages.loadSync]), the preview must not ([FillImages.peek]). */
    private val fillImage: (String) -> Bitmap? = { FillImages.loadSync(it) }
) {

    private val p = FloatArray(39)
    private val c = IntArray(7)
    private val anim = FloatArray(11)

    /** Layout box (frame pixels, scale 1) of the last layer this painter drew; the pivot of the layer is a fraction of it. */
    @Volatile var lastBoxW = 0f
        private set
    @Volatile var lastBoxH = 0f
        private set

    fun draw(
        bmp: Bitmap, clip: TimelineClip, frameW: Int, frameH: Int, k: Float,
        applyOpacity: Boolean, crop: FloatArray?, timeMs: Long = -1L
    ): Boolean {
        if (bmp.config != Bitmap.Config.ARGB_8888) return false

        val authored = clip.text?.ifBlank { null } ?: "Text"
        // Imported counter (Alight "Count Up/Down"): the number(s) in the text are replaced by
        // number × scale + offset at this frame BEFORE the string reaches the engine (see CounterEval),
        // so the engine lays out and draws the counted value — and the textprogress window /
        // texttransform units below operate on the counted string, the only order in which they see
        // the text that is actually shown. -1 = unknown time = the authored text, like the animators.
        val content = if (timeMs >= 0L) CounterEval.displayText(authored, clip, timeMs) ?: authored else authored
        val fontPx = frameW * 0.11f
        val bgOn = clip.bgOpacity > 0f
        val padH = maxOf(fontPx * 0.25f, if (bgOn) fontPx * clip.bgPadding / 100f * 0.9f else 0f)
        val padV = maxOf(fontPx * 0.12f, if (bgOn) fontPx * clip.bgPadding / 100f * 0.5f else 0f)
        val maxTextW = (frameW * (if (clip.textWrapFrac > 0f) clip.textWrapFrac else 0.9f) - 2f * padH).toInt().coerceAtLeast(1)

        p[0] = fontPx
        p[1] = fontPx * clip.letterSpacing / 100f
        p[2] = clip.lineHeightPercent / 100f
        p[3] = when (clip.textAlign) { 0 -> 0f; 2 -> 2f; else -> 1f }
        p[4] = maxTextW.toFloat()
        p[5] = padH
        p[6] = padV
        p[7] = (clip.effectiveScalePercent / 100f).coerceAtLeast(0.01f)
        p[8] = clip.rotationDeg
        p[9] = if (clip.isFlippedH) -1f else 1f
        p[10] = if (clip.isFlippedV) -1f else 1f
        p[11] = frameW / 2f + clip.depthX * k
        p[12] = frameH / 2f + clip.depthY * k
        p[13] = if (applyOpacity) clip.opacityFraction.coerceIn(0f, 1f) else 1f
        p[14] = crop?.get(0) ?: 0f
        p[15] = crop?.get(1) ?: 0f
        p[16] = crop?.get(2) ?: 1f
        p[17] = crop?.get(3) ?: 1f
        p[18] = clip.fontWeight.coerceIn(100, 900).toFloat()
        p[19] = if (clip.isItalic) 1f else 0f
        p[20] = if (clip.isUnderline) 1f else 0f
        p[21] = if (clip.isStrikethrough) 1f else 0f
        p[22] = if (clip.fillMode == FILL_NONE) 0f else 1f
        p[23] = if (clip.textStrokeThickness > 0f) fontPx * clip.textStrokeThickness / 100f * 0.4f else 0f

        if (clip.shadowOpacity > 0f) {
            val rad = Math.toRadians(clip.shadowAngle.toDouble())
            val dist = fontPx * clip.shadowDistance / 100f * 0.4f
            val base = argb(clip.shadowColor, 0xFF000000)
            val a = (Color.alpha(base) * clip.shadowOpacity.coerceIn(0f, 100f) / 100f).roundToInt().coerceIn(0, 255)
            p[24] = 1f
            p[25] = (fontPx * clip.shadowBlur / 100f * 0.5f).coerceAtLeast(0.01f)
            p[26] = (cos(rad) * dist).toFloat()
            p[27] = (sin(rad) * dist).toFloat()
            c[2] = Color.argb(a, Color.red(base), Color.green(base), Color.blue(base))
        } else {
            p[24] = 0f; p[25] = 0f; p[26] = 0f; p[27] = 0f; c[2] = 0
        }
        if (bgOn) {
            val bg = argb(clip.bgColor, 0xFF000000)
            val a = (clip.bgOpacity.coerceIn(0f, 100f) / 100f * 255).toInt()
            c[3] = Color.argb(a, Color.red(bg), Color.green(bg), Color.blue(bg))
            p[28] = 1f
            p[29] = (clip.bgCorner / 2f).toInt().coerceIn(0, 50) / 100f
        } else {
            p[28] = 0f; p[29] = 0f; c[3] = 0
        }
        c[0] = argb(clip.textColor, 0xFFFFFFFF)
        c[1] = argb(clip.textStrokeColor, 0xFF000000)
        // Gradient / picture fill: the colour comes from the position in the text block (C++: compositeFill).
        var fillBmp: Bitmap? = null
        when (clip.fillMode) {
            FILL_GRADIENT -> {
                p[30] = 1f
                p[31] = clip.gradType.toFloat()
                p[32] = clip.gradX1; p[33] = clip.gradY1; p[34] = clip.gradX2; p[35] = clip.gradY2
                c[4] = clip.gradColor1.toInt()   // raw ARGB, like buildGradientShader
                c[5] = clip.gradColor2.toInt()
            }
            FILL_IMAGE -> {
                p[30] = 2f
                p[36] = clip.fillImageMode.toFloat()
                // Missing / not loaded picture = no fill (like the Canvas version); the C++ side draws nothing for it.
                fillBmp = clip.fillImageUri?.let(fillImage)?.takeIf { it.config == Bitmap.Config.ARGB_8888 && !it.isRecycled }
            }
            else -> p[30] = 0f
        }

        val family = when (clip.fontFamily) {
            "Serif" -> "serif"
            "Monospace" -> "monospace"
            "Cursive" -> "cursive"
            else -> "sans-serif"
        }
        val path = fontFile(clip.fontFamily)
        // Imported text animators (Alight texttransform + textprogress): need the time of this frame,
        // -1 = unknown = not animated. Each group carries its own on flag (anim[0] / anim[8]).
        var animArr: FloatArray? = null
        if (timeMs >= 0L && clip.textAnimators.isNotEmpty()) {
            val tr = TextAnimEval.evaluate(clip, timeMs, fontPx)
            val pr = TextProgressEval.evaluate(clip, timeMs)
            if (tr != null || pr != null) {
                anim.fill(0f)
                if (tr != null) {
                    anim[0] = 1f; anim[1] = tr.component.toFloat(); anim[2] = tr.start; anim[3] = tr.end
                    anim[4] = tr.dx; anim[5] = tr.dy; anim[6] = tr.alpha; anim[7] = if (tr.recolor) 1f else 0f
                    c[6] = tr.argb
                }
                if (pr != null) {
                    anim[8] = 1f; anim[9] = pr.start; anim[10] = pr.end
                }
                animArr = anim
            }
        }
        return try {
            val bidi = bidiLevelsOf(content)
            val ok = TextNative.nativeDraw(bmp, content, path, family, p, c, fillBmp, bidi?.first, bidi?.second, animArr)
            if (ok) { lastBoxW = p[37]; lastBoxH = p[38] }
            ok
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
        return Color.argb(a, r, g, b)
    }
}


/**
 * True when the C++ text engine can draw this layer (same rules as [NativeTextPainter.draw] plus a cheap scan for
 * emoji, which need [TextNative.emojiAvailable]). The C++ preview and the Compose text
 * (hidden then, it only keeps the touch handling) use this one rule, so exactly one of them draws a layer.
 */
internal fun nativeTextEligible(clip: TimelineClip): Boolean {
    if (clip.type == LayerType.SHAPE || clip.type == LayerType.STICKER) return false
    if (clip.type != LayerType.TEXT && clip.text == null) return false
    if (!TextNative.isAvailable) return false
    // Turned in 3D / own pivot (v126): drawn upright into its own picture and placed by the renderer, see OverlayPose.kt.
    val s = clip.text ?: return true
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        i += Character.charCount(cp)
        // Same ranges as emojiish() in text_engine.cpp: drawn from the emoji font, which not every phone can use yet.
        if (cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp == 0xFE0F || cp in 0x2B00..0x2BFF) {
            return TextNative.emojiAvailable
        }
    }
    return true
}

/** Crop of a text / shape layer as [left, top, right, bottom] fractions of its box, or null. */
internal fun cropFractionsOfClip(clip: TimelineClip): FloatArray? {
    val cW = (clip.cropWidthPercent / 100f).coerceIn(0.05f, 1f)
    val cH = (clip.cropHeightPercent / 100f).coerceIn(0.05f, 1f)
    if (cW >= 0.999f && cH >= 0.999f) return null
    val l = (1f - cW) * (clip.cropHorizontalPercent / 100f)
    val t = (1f - cH) * (clip.cropVerticalPercent / 100f)
    return floatArrayOf(l, t, l + cW, t + cH)
}

/**
 * Bidi (UAX #9) data for the C++ text engine, from java.text.Bidi: the embedding level of every code point and one
 * flag per paragraph (1 = right to left). The text is cut into code points exactly like the engine does (new line
 * starts a paragraph, '\r' and control characters are dropped, tab = space). Null when everything runs left to right.
 * A paragraph's direction is the one of its first strong letter, left to right when there is none (Android's default).
 */
internal fun bidiLevelsOf(text: String): Pair<ByteArray, ByteArray>? {
    val levels = ArrayList<Byte>(text.length)
    val paras = ArrayList<Byte>()
    var any = false
    val cur = StringBuilder()
    fun flush() {
        val s = cur.toString()
        cur.setLength(0)
        var rtlPara = 0.toByte()
        if (s.isNotEmpty() && java.text.Bidi.requiresBidi(s.toCharArray(), 0, s.length)) {
            val b = java.text.Bidi(s, java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
            if (!b.baseIsLeftToRight()) rtlPara = 1
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                levels.add(b.getLevelAt(i).toByte())
                i += Character.charCount(cp)
            }
            any = true
        } else {
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                levels.add(0)
                i += Character.charCount(cp)
            }
        }
        paras.add(rtlPara)
    }
    var i = 0
    while (i < text.length) {
        var cp = text.codePointAt(i)
        i += Character.charCount(cp)
        if (cp == '\n'.code) { flush(); continue }
        if (cp == '\r'.code) continue
        if (cp == '\t'.code) cp = ' '.code
        if (cp < 0x20) continue
        cur.appendCodePoint(cp)
    }
    flush()
    return if (any) levels.toByteArray() to paras.toByteArray() else null
}
