package com.example.engine

import com.example.model.sourceOffsetMs
import com.example.model.exportSpeed
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.util.Log
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.example.model.AspectRatio
import com.example.model.BlendKind
import com.example.model.BlendModes
import com.example.model.ExportSettings
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.TimelineTrack
import com.example.model.opacityFraction
import com.example.model.at
import com.example.model.renderAt
import com.example.model.depthX
import com.example.model.depthY
import com.example.model.effectiveScalePercent
import com.example.model.isBehindCamera
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Size of the canvas frame in the editor preview (device px) and the screen density. Shapes, text
 * and moves are stored in preview units; the painter scales them to the export size with this.
 */
internal object PreviewFrameRef {
    @Volatile var widthPx: Float = 0f
    @Volatile var density: Float = 1f
}

/**
 * Draws one shape / text layer onto a canvas (the export's version of the preview's layers).
 * Used by the overlay for normal layers and by [BlendEffect] for blended ones.
 */
internal class LayerPainter(
    private val frameW: Int,
    private val frameH: Int
) {
    /** [applyOpacity]: false when the caller applies the layer's opacity itself (blend shader). */
    /** [timeMs]: timeline time of this frame (animated stickers pick their frame from it). */
    fun draw(canvas: Canvas, clip: TimelineClip, applyOpacity: Boolean, timeMs: Long = -1L) {
        // Text can be drawn by the C++ text engine straight into the overlay bitmap (used by the native exporter).
        // It returns false for anything it cannot do, then the Canvas version below runs as before.
        if (clip.type != LayerType.SHAPE && clip.type != LayerType.STICKER &&
            (clip.type == LayerType.TEXT || clip.text != null)
        ) {
            val np = nativeText
            val tb = nativeTarget
            if (np != null && tb != null && !clip.isBehindCamera &&
                np.draw(tb, clip, frameW, frameH, k, applyOpacity, cropFractionsOf(clip), timeMs)
            ) return
        }
        // Shapes and stickers: the C++ shape engine draws straight into the overlay bitmap (v119). It returns false
        // for anything it cannot do (library missing), then the Canvas code below runs as before.
        val ns = nativeShape
        val nt = nativeTarget
        if (ns != null && nt != null && (clip.type == LayerType.SHAPE || clip.type == LayerType.STICKER)) {
            if (clip.isBehindCamera) return
            if (clip.type == LayerType.SHAPE) {
                if (ns.drawShape(nt, clip, frameW, frameH, k, density, applyOpacity, cropFractionsOf(clip))) return
            } else {
                val st = com.example.engine.StickerLibrary.frameFor(
                    clip.uri, clip.sourceOffsetMs(if (timeMs >= 0L) timeMs - clip.startMs else 0L)
                )
                if (st == null) return  // same as drawSticker: no picture, nothing drawn
                if (ns.drawSticker(nt, st, clip, frameW, frameH, k, density, applyOpacity)) return
            }
        }
        val alpha = if (applyOpacity) (clip.opacityFraction * 255f).roundToInt().coerceIn(0, 255) else 255
        val saved = if (alpha < 255) {
            canvas.saveLayerAlpha(0f, 0f, frameW.toFloat(), frameH.toFloat(), alpha)
        } else -1
        if (clip.type == LayerType.SHAPE) {
            drawShape(canvas, clip)
        } else if (clip.type == LayerType.STICKER) {
            drawSticker(canvas, clip, if (timeMs >= 0L) timeMs - clip.startMs else 0L)
        } else if (clip.type == LayerType.TEXT || clip.text != null) {
            drawText(canvas, clip, timeMs)
        }
        if (saved >= 0) canvas.restoreToCount(saved)
    }

    /**
     * Box of [clip] at scale 1 in frame pixels, which its pivot is a fraction of (OverlayPose.kt): a shape / sticker is the
     * 130 dp square, text is what the C++ text engine laid out last (0 x 0 when the Canvas drew it).
     */
    fun boxOf(clip: TimelineClip): Pair<Float, Float> =
        if (clip.type == LayerType.SHAPE || clip.type == LayerType.STICKER) shapeBoxPx(density, k).let { it to it }
        else (nativeText?.lastBoxW ?: 0f) to (nativeText?.lastBoxH ?: 0f)

    /** C++ text engine + the bitmap [canvas] draws on; both null = text is drawn with the Canvas only. */
    var nativeText: NativeTextPainter? = null
    /** C++ shape / sticker engine; draws into [nativeTarget] too. */
    var nativeShape: NativeShapePainter? = null
    var nativeTarget: android.graphics.Bitmap? = null

    /** Export pixels per preview pixel (the preview frame is smaller than the export frame). */
    private val k: Float = PreviewFrameRef.widthPx.let { if (it > 0f) frameW / it else 1f }
    private val density: Float = PreviewFrameRef.density

    /**
     * Sticker (PNG or animated frame folder): same placement as the preview's 130 dp box
     * (position, scale, rotation, flip), picture fitted inside it keeping its aspect.
     */
    private fun drawSticker(canvas: Canvas, clip: TimelineClip, elapsedMs: Long) {
        if (clip.isBehindCamera) return
        val bmp = com.example.engine.StickerLibrary.frameFor(clip.uri, clip.sourceOffsetMs(elapsedMs)) ?: return
        val cx = frameW / 2f + clip.depthX * k
        val cy = frameH / 2f + clip.depthY * k
        val scale = (clip.effectiveScalePercent / 100f).coerceAtLeast(0.01f)
        val base = 65f * density * k
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val fit = if (bw >= bh) floatArrayOf(base, base * bh / bw) else floatArrayOf(base * bw / bh, base)
        val dst = android.graphics.RectF(-fit[0], -fit[1], fit[0], fit[1])
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(clip.rotationDeg)
        canvas.scale(
            scale * (if (clip.isFlippedH) -1f else 1f),
            scale * (if (clip.isFlippedV) -1f else 1f)
        )
        canvas.drawBitmap(bmp, null, dst, paint)
        canvas.restore()
    }

    private fun drawShape(canvas: Canvas, clip: TimelineClip) {
        if (clip.isBehindCamera) return
        val cx = frameW / 2f + clip.depthX * k
        val cy = frameH / 2f + clip.depthY * k
        val scale = (clip.effectiveScalePercent / 100f).coerceAtLeast(0.01f)
        // The preview draws a 130 dp box; base is half of it. The layer scale is applied by canvas.scale below.
        val base = 65f * density * k
        val color = argb(clip.textColor.takeIf { it != 0L } ?: clip.color, fallback = 0xFFFF3377)
        val strokeCol = argb(clip.strokeColor, fallback = 0xFFFFFFFF)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = strokeCol
            style = Paint.Style.STROKE
            strokeWidth = (clip.strokeWidth.coerceAtLeast(0f) * density * k).coerceAtLeast(0f)
        }
        // Fill: none / solid (already set) / gradient. Shapes are drawn around (0,0), so the
        // gradient is built in that same local space.
        when (clip.fillMode) {
            com.example.model.FILL_NONE -> paint.alpha = 0
            com.example.model.FILL_GRADIENT ->
                paint.shader = com.example.engine.buildGradientShader(clip, base * 2f, base * 2f, -base, -base)
            com.example.model.FILL_IMAGE -> {
                val uri = clip.fillImageUri
                val bmp = uri?.let { com.example.engine.FillImages.loadSync(it) }
                if (bmp != null) {
                    paint.shader = com.example.engine.buildImageShader(
                        bmp, uri, clip.fillImageMode, (base * 2f).toInt(), (base * 2f).toInt(), -base, -base
                    )
                } else paint.alpha = 0
            }
        }

        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(clip.rotationDeg)
        canvas.scale(scale, scale)
        canvas.scale(if (clip.isFlippedH) -1f else 1f, if (clip.isFlippedV) -1f else 1f)
        cropFractionsOf(clip)?.let { f ->
            canvas.clipRect(
                -base + f[0] * 2f * base, -base + f[1] * 2f * base,
                -base + f[2] * 2f * base, -base + f[3] * 2f * base
            )
        }

        // Stroke trim (draw-on): only the outline between the clip's trim fractions is drawn. An imported
        // line (strokeTrimFill) has no stroke — its fill IS the path, so the trim clips the fill along the
        // box's x axis (the line runs along it before the layer's own rotation is applied by the canvas).
        val trimOn = clip.strokeTrimStart > 0f || clip.strokeTrimEnd < 1f
        if (trimOn && clip.strokeTrimFill) {
            canvas.clipRect(
                -base + clip.strokeTrimStart * 2f * base, -base,
                -base + clip.strokeTrimEnd * 2f * base, base
            )
        }
        fun drawStrokePath(outline: Path) {
            if (stroke.strokeWidth <= 0f) return
            if (!trimOn || clip.strokeTrimFill) {
                canvas.drawPath(outline, stroke)
                return
            }
            val measure = android.graphics.PathMeasure(outline, false)
            val len = measure.length
            if (len <= 0f || clip.strokeTrimEnd <= clip.strokeTrimStart) return
            val seg = Path()
            if (measure.getSegment(len * clip.strokeTrimStart, len * clip.strokeTrimEnd, seg, true)) {
                canvas.drawPath(seg, stroke)
            }
        }

        val pattern = clip.shapePattern ?: "CIRCLE"
        when (pattern.uppercase()) {
            "RECT", "RECTANGLE", "SQUARE" -> {
                val r = RectF(-base, -base, base, base)
                canvas.drawRect(r, paint)
                drawStrokePath(Path().apply { addRect(r, Path.Direction.CW) })
            }
            "ROUNDED", "ROUNDED_RECT" -> {
                val r = RectF(-base, -base, base, base)
                // The clip's own corner radius (dp of the shape box) when set, else the built-in corner.
                val rad = if (clip.shapeCornerRadius >= 0f) {
                    (clip.shapeCornerRadius * density * k).coerceIn(0f, base)
                } else base * 0.25f
                canvas.drawRoundRect(r, rad, rad, paint)
                drawStrokePath(Path().apply { addRoundRect(r, rad, rad, Path.Direction.CW) })
            }
            "OVAL", "ELLIPSE" -> {
                val r = RectF(-base * 1.2f, -base * 0.75f, base * 1.2f, base * 0.75f)
                canvas.drawOval(r, paint)
                drawStrokePath(Path().apply { addOval(r, Path.Direction.CW) })
            }
            "TRIANGLE" -> {
                val path = Path().apply {
                    moveTo(0f, -base)
                    lineTo(base, base)
                    lineTo(-base, base)
                    close()
                }
                canvas.drawPath(path, paint)
                drawStrokePath(path)
            }
            else -> { // CIRCLE
                canvas.drawCircle(0f, 0f, base, paint)
                drawStrokePath(Path().apply { addCircle(0f, 0f, base, Path.Direction.CW) })
            }
        }
        canvas.restore()
    }

    private fun drawText(canvas: Canvas, clip: TimelineClip, timeMs: Long = -1L) {
        if (clip.isBehindCamera) return
        val full = clip.text?.ifBlank { null } ?: "Text"
        // Imported textprogress (typewriter reveal): the C++ engine hides the clusters outside the window
        // and keeps the layout; this Canvas fallback cuts the string to the shown part instead (same window
        // and cluster counting, see TextProgressEval). -1 = unknown time = the whole text, as before.
        val content = if (timeMs >= 0L) {
            TextProgressEval.evaluate(clip, timeMs)?.let { TextProgressEval.visibleText(full, it.start, it.end) } ?: full
        } else full
        val textColor = argb(clip.textColor, fallback = 0xFFFFFFFF)
        val scale = (clip.effectiveScalePercent / 100f).coerceAtLeast(0.01f)
        // Same rules as the preview (TextLayerContent): every size is a fraction of the frame width.
        val fontPx = frameW * 0.11f
        val bgOn = clip.bgOpacity > 0f
        val padH = maxOf(fontPx * 0.25f, if (bgOn) fontPx * clip.bgPadding / 100f * 0.9f else 0f)
        val padV = maxOf(fontPx * 0.12f, if (bgOn) fontPx * clip.bgPadding / 100f * 0.5f else 0f)

        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = fontPx
            typeface = Typeface.create(
                Typeface.DEFAULT,
                when {
                    clip.fontWeight >= 700 && clip.isItalic -> Typeface.BOLD_ITALIC
                    clip.fontWeight >= 700 -> Typeface.BOLD
                    clip.isItalic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                }
            )
            isUnderlineText = clip.isUnderline
            isStrikeThruText = clip.isStrikethrough
            letterSpacing = clip.letterSpacing / 100f
        }
        if (clip.shadowOpacity > 0f) {
            val rad = Math.toRadians(clip.shadowAngle.toDouble())
            val dist = fontPx * clip.shadowDistance / 100f * 0.4f
            val base = argb(clip.shadowColor, fallback = 0xFF000000)
            val a = (Color.alpha(base) * clip.shadowOpacity.coerceIn(0f, 100f) / 100f).roundToInt().coerceIn(0, 255)
            tp.setShadowLayer(
                (fontPx * clip.shadowBlur / 100f * 0.5f).coerceAtLeast(0.01f),
                (Math.cos(rad) * dist).toFloat(), (Math.sin(rad) * dist).toFloat(),
                Color.argb(a, Color.red(base), Color.green(base), Color.blue(base))
            )
        }

        val maxTextW = (frameW * 0.9f - 2f * padH).toInt().coerceAtLeast(1)
        val align = when (clip.textAlign) {
            0 -> Layout.Alignment.ALIGN_NORMAL
            2 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_CENTER
        }
        @Suppress("DEPRECATION")
        val layout = StaticLayout(content, tp, maxTextW, align, clip.lineHeightPercent / 100f, 0f, false)
        // The preview box hugs the text: its width is the longest line.
        var tightW = 1f
        for (i in 0 until layout.lineCount) tightW = maxOf(tightW, layout.getLineWidth(i))
        tightW = minOf(tightW, maxTextW.toFloat())
        val boxW = tightW + 2f * padH
        val boxH = layout.height + 2f * padV
        val dx = when (align) {
            Layout.Alignment.ALIGN_NORMAL -> 0f
            Layout.Alignment.ALIGN_OPPOSITE -> tightW - maxTextW
            else -> (tightW - maxTextW) / 2f
        }

        canvas.save()
        canvas.translate(frameW / 2f + clip.depthX * k, frameH / 2f + clip.depthY * k)
        canvas.rotate(clip.rotationDeg)
        canvas.scale(scale * (if (clip.isFlippedH) -1f else 1f), scale * (if (clip.isFlippedV) -1f else 1f))
        canvas.translate(-boxW / 2f, -boxH / 2f)
        cropFractionsOf(clip)?.let { f ->
            canvas.clipRect(f[0] * boxW, f[1] * boxH, f[2] * boxW, f[3] * boxH)
        }

        // Optional background box
        if (bgOn) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = argb(clip.bgColor, fallback = 0xFF000000)
                alpha = (clip.bgOpacity.coerceIn(0f, 100f) / 100f * 255).toInt()
            }
            val corner = (clip.bgCorner / 2f).toInt().coerceIn(0, 50) / 100f * minOf(boxW, boxH)
            canvas.drawRoundRect(RectF(0f, 0f, boxW, boxH), corner, corner, bg)
        }

        canvas.translate(padH + dx, padV)

        // Optional stroke outline (drawn underneath the fill)
        if (clip.textStrokeThickness > 0f) {
            val strokePaint = TextPaint(tp).apply {
                clearShadowLayer()
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
                strokeWidth = fontPx * clip.textStrokeThickness / 100f * 0.4f
                color = argb(clip.textStrokeColor, fallback = 0xFF000000)
            }
            @Suppress("DEPRECATION")
            val strokeLayout = StaticLayout(content, strokePaint, maxTextW, align, clip.lineHeightPercent / 100f, 0f, false)
            strokeLayout.draw(canvas)
        }

        // Text fill: none / solid (already set) / gradient over the text block.
        when (clip.fillMode) {
            com.example.model.FILL_NONE -> tp.color = android.graphics.Color.TRANSPARENT
            com.example.model.FILL_GRADIENT -> tp.shader =
                com.example.engine.buildGradientShader(clip, layout.width.toFloat(), layout.height.toFloat())
            com.example.model.FILL_IMAGE -> {
                val uri = clip.fillImageUri
                val bmp = uri?.let { com.example.engine.FillImages.loadSync(it) }
                if (bmp != null) {
                    tp.shader = com.example.engine.buildImageShader(
                        bmp, uri, clip.fillImageMode, layout.width, layout.height
                    )
                } else tp.color = android.graphics.Color.TRANSPARENT
            }
        }
        layout.draw(canvas)
        canvas.restore()
    }

    /** Crop of a shape / text layer as [left, top, right, bottom] fractions of its box, or null. */
    private fun cropFractionsOf(clip: TimelineClip): FloatArray? {
        val cW = (clip.cropWidthPercent / 100f).coerceIn(0.05f, 1f)
        val cH = (clip.cropHeightPercent / 100f).coerceIn(0.05f, 1f)
        if (cW >= 0.999f && cH >= 0.999f) return null
        val l = (1f - cW) * (clip.cropHorizontalPercent / 100f)
        val t = (1f - cH) * (clip.cropVerticalPercent / 100f)
        return floatArrayOf(l, t, l + cW, t + cH)
    }

    private fun argb(value: Long, fallback: Long): Int {
        val v = if (value == 0L) fallback else value
        val a = ((v shr 24) and 0xFF).toInt().let { if (it == 0) 255 else it }
        val r = ((v shr 16) and 0xFF).toInt()
        val g = ((v shr 8) and 0xFF).toInt()
        val b = (v and 0xFF).toInt()
        return Color.argb(a, r, g, b)
    }
}
