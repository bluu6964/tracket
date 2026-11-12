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
import com.example.model.PATH_OP_CLOSE
import com.example.model.PATH_OP_CUBIC
import com.example.model.PATH_OP_LINE
import com.example.model.PATH_OP_MOVE
import com.example.model.PATH_OP_QUAD
import com.example.model.TimelineClip
import com.example.model.TimelineTrack
import com.example.model.opacityFraction
import com.example.model.at
import com.example.model.renderAt
import com.example.model.depthX
import com.example.model.depthY
import com.example.model.effectiveScalePercent
import com.example.model.isBehindCamera
import com.example.model.shapeCornerRadii
import com.example.model.shapeDrawScaleX
import com.example.model.shapeDrawScaleY
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
    private val frameH: Int,
    /** The preview reads the real screen density itself (PreviewFrameRef.density is stale there). */
    private val densityOverride: Float? = null
) {
    /** [applyOpacity]: false when the caller applies the layer's opacity itself (blend shader). */
    /** [timeMs]: timeline time of this frame (animated stickers pick their frame from it). */
    fun draw(canvas: Canvas, clip: TimelineClip, applyOpacity: Boolean, timeMs: Long = -1L) {
        // Final-frame clip (see CanvasClip): the whole layer is clipped to the canvas rect, so a
        // layer placed outside the scene area contributes nothing to the frame and one crossing
        // an edge is cut exactly at the edge — the same rule the C++ renderer applies with its
        // canvas scissor, and the same result Alight Motion gives for off-scene elements.
        val fr = CanvasClip.frameRect(frameW, frameH)
        val frameSave = canvas.save()
        canvas.clipRect(fr[0], fr[1], fr[2], fr[3])
        try {
            drawClipped(canvas, clip, applyOpacity, timeMs)
        } finally {
            canvas.restoreToCount(frameSave)
        }
    }

    private fun drawClipped(canvas: Canvas, clip: TimelineClip, applyOpacity: Boolean, timeMs: Long = -1L) {
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
    private val density: Float = densityOverride ?: PreviewFrameRef.density

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
        // Per-axis layer scale: X is the effective scale, Y also carries the shape's own aspect
        // (shapeScaleY / shapeScaleX); both are the plain layer scale for a uniform shape.
        val scaleX = clip.shapeDrawScaleX
        val scaleY = clip.shapeDrawScaleY
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
            com.example.model.FILL_VIDEO -> {
                // The poster frame of the fill video (FillImages resolves a video uri to its first
                // frame): the render engines composite the playing video over this where they can
                // (build 104); this Canvas path is the fallback and shows the poster.
                val uri = clip.fillVideoUri
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
        canvas.scale(scaleX, scaleY)
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
                // The clip's own corner radii (per axis, so the frame-space arc stays circular under
                // the shape's aspect — see TimelineClip.shapeCornerRadii) when set, else the built-in
                // corner. Canvas takes the two radii directly (drawRoundRect / addRoundRect rx, ry).
                val radii = clip.shapeCornerRadii(base)
                val radX = radii?.first ?: base * 0.25f
                val radY = radii?.second ?: base * 0.25f
                canvas.drawRoundRect(r, radX, radY, paint)
                drawStrokePath(Path().apply { addRoundRect(r, radX, radY, Path.Direction.CW) })
            }
            "OVAL", "ELLIPSE" -> {
                val r = RectF(-base * 1.2f, -base * 0.75f, base * 1.2f, base * 0.75f)
                canvas.drawOval(r, paint)
                // Start at the TOP, clockwise — the trim convention of the C++ painter (and of
                // Alight's own circle path, build 120); addOval would start at the +x axis.
                drawStrokePath(Path().apply { addArc(r, -90f, 360f) })
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
            "PATH" -> {
                // A freehand outline (an imported Alight <path> shape): replay the stored segments in
                // the box's local units. The fill follows the path's own winding; the stroke goes
                // through drawStrokePath, so a drawing.progress trim measures this same outline.
                val outline = Path()
                for (seg in clip.pathSegments) {
                    val q = seg.pts
                    when (seg.op) {
                        PATH_OP_MOVE -> if (q.size >= 2) outline.moveTo(q[0] * base, q[1] * base)
                        PATH_OP_LINE -> if (q.size >= 2) outline.lineTo(q[0] * base, q[1] * base)
                        PATH_OP_QUAD -> if (q.size >= 4) outline.quadTo(q[0] * base, q[1] * base, q[2] * base, q[3] * base)
                        PATH_OP_CUBIC -> if (q.size >= 6) outline.cubicTo(q[0] * base, q[1] * base, q[2] * base, q[3] * base, q[4] * base, q[5] * base)
                        PATH_OP_CLOSE -> outline.close()
                    }
                }
                canvas.drawPath(outline, paint)
                drawStrokePath(outline)
                // End caps (Alight path-stroke `start` / `end="circle-filled"`): a filled circle in
                // the stroke colour at each open end of the outline, its diameter the cap size x
                // the stroke width. A trim moves the visible ends, so the caps ride the trim
                // window like the stroke itself; a closed outline has no ends to cap.
                if ((clip.strokeCapStartSize > 0f || clip.strokeCapEndSize > 0f) &&
                    stroke.strokeWidth > 0f && clip.pathSegments.none { it.op == PATH_OP_CLOSE }
                ) {
                    val measure = android.graphics.PathMeasure(outline, false)
                    val len = measure.length
                    val windowEmpty = trimOn && !clip.strokeTrimFill &&
                        clip.strokeTrimEnd <= clip.strokeTrimStart
                    if (len > 0f && !windowEmpty) {
                        val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            this.color = strokeCol
                            style = Paint.Style.FILL
                        }
                        val pos = FloatArray(2)
                        if (clip.strokeCapEndSize > 0f) {
                            val f = if (trimOn && !clip.strokeTrimFill) clip.strokeTrimEnd else 1f
                            if (measure.getPosTan(len * f, pos, null)) {
                                canvas.drawCircle(pos[0], pos[1], stroke.strokeWidth * 0.5f * clip.strokeCapEndSize, capPaint)
                            }
                        }
                        if (clip.strokeCapStartSize > 0f) {
                            val f = if (trimOn && !clip.strokeTrimFill) clip.strokeTrimStart else 0f
                            if (measure.getPosTan(len * f, pos, null)) {
                                canvas.drawCircle(pos[0], pos[1], stroke.strokeWidth * 0.5f * clip.strokeCapStartSize, capPaint)
                            }
                        }
                    }
                }
            }
            else -> { // CIRCLE
                canvas.drawCircle(0f, 0f, base, paint)
                // Start at the TOP, clockwise, like the C++ painter and Alight's circle path
                // (build 120); addCircle would start at the +x axis, a quarter turn out.
                drawStrokePath(Path().apply { addArc(RectF(-base, -base, base, base), -90f, 360f) })
            }
        }
        canvas.restore()
    }

    private fun drawText(canvas: Canvas, clip: TimelineClip, timeMs: Long = -1L) {
        if (clip.isBehindCamera) return
        val full = clip.text?.ifBlank { null } ?: "Text"
        // Imported counter (Alight "Count Up/Down") substitutes the text's number(s) first (see
        // CounterEval — the native path hands the counted string to the engine instead); imported
        // textprogress (typewriter reveal) then applies to the counted string: the C++ engine hides the
        // clusters outside the window and keeps the layout, this Canvas fallback cuts the string to the
        // shown part instead (same window and cluster counting, see TextProgressEval).
        // -1 = unknown time = the whole authored text, as before.
        val counted = if (timeMs >= 0L) CounterEval.displayText(full, clip, timeMs) ?: full else full
        // With drawing.progress (strokeTrimText) the trim window over the FULL text governs what is
        // drawn, so the textprogress string cut does not apply (the C++ engine likewise counts the
        // hidden clusters' outline lengths in the total).
        val content = if (timeMs >= 0L && !clip.strokeTrimText) {
            TextProgressEval.evaluate(clip, timeMs)?.let { TextProgressEval.visibleText(counted, it.start, it.end) } ?: counted
        } else counted
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
            // The layer's own weight (100..900), not just bold-or-not: the weight-aware create picks
            // the closest cut of the family (and synthesizes when the family has no such cut).
            typeface = Typeface.create(Typeface.DEFAULT, clip.fontWeight.coerceIn(100, 900), clip.isItalic)
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

        // Same conversion as the native painter (see textLayoutBoxWidth): an authored wrap width is an
        // on-screen share of the frame, the layout here is unscaled and scaled only when drawn below.
        val maxTextW = textLayoutBoxWidth(frameW.toFloat(), clip.textWrapFrac, scale, padH).toInt()
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

        // Imported drawing.progress on a text layer: the glyph outlines are drawn, trimmed to the
        // [start, end] window of their concatenated length, instead of the fill (and of the stroke
        // underlay below) — the same window and width rule as the C++ text engine (see textTrimAt /
        // drawOnStrokeWidthPx).
        if (clip.strokeTrimText) {
            val (ts, te) = textTrimAt(clip, timeMs)
            val seg = trimPath(textOutlinePath(tp, layout, content), ts, te)
            if (seg != null) {
                val sp = TextPaint(tp).apply {
                    style = Paint.Style.STROKE
                    strokeJoin = Paint.Join.ROUND
                    strokeCap = Paint.Cap.ROUND
                    strokeWidth = drawOnStrokeWidthPx(clip, fontPx)
                    color = textColor
                }
                canvas.drawPath(seg, sp)
            }
            canvas.restore()
            return
        }

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

    /** The glyph outlines of every laid-out line as one path (each line's glyphs at their layout positions). */
    private fun textOutlinePath(tp: TextPaint, layout: Layout, content: String): Path {
        val path = Path()
        for (i in 0 until layout.lineCount) {
            val ls = layout.getLineStart(i)
            val le = layout.getLineEnd(i)
            if (le <= ls) continue
            tp.getTextPath(content, ls, le, layout.getLineLeft(i), layout.getLineBaseline(i).toFloat(), path)
        }
        return path
    }

    /**
     * The [start, end] window (fractions, order-free) of a path's concatenated contour length:
     * every contour is measured in order and the overlapping pieces are kept — the Canvas twin of
     * the glyph trim in the C++ text engine. Null when the window is empty.
     */
    private fun trimPath(path: Path, start: Float, end: Float): Path? {
        val lo = minOf(start, end).coerceIn(0f, 1f)
        val hi = maxOf(start, end).coerceIn(0f, 1f)
        if (hi <= lo) return null
        var total = 0f
        val measure = android.graphics.PathMeasure(path, false)
        do {
            total += measure.length
        } while (measure.nextContour())
        if (total <= 0f) return null
        val s = lo * total
        val e = hi * total
        val out = Path()
        var off = 0f
        val m = android.graphics.PathMeasure(path, false)
        do {
            val len = m.length
            if (len > 0f) {
                val a = (s - off).coerceIn(0f, len)
                val b = (e - off).coerceIn(0f, len)
                if (b > a) m.getSegment(a, b, out, true)
            }
            off += len
        } while (m.nextContour())
        return out
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
