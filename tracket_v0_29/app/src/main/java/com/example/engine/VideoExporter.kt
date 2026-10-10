package com.example.engine

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
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.media3.common.Effect
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.example.model.AspectRatio
import com.example.model.ExportSettings
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.TimelineTrack
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Real export: renders the main track (trim, flip, mute, project aspect ratio) and mixes in
 * the audio layers (volume, fades, speed)
 * to an H.264/AAC MP4 with Media3 Transformer, then saves it to Movies/Tracket.
 *
 * Export is allowed when at least one layer of any type exists (like Alight Motion).
 * If there is no video/photo media, a solid black background of the project duration is used
 * so shape / text / audio-only projects can still be exported.
 * Must be used from the main thread.
 */
@UnstableApi
class VideoExporter(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var poller: Runnable? = null

    fun start(
        tracks: List<TimelineTrack>,
        aspectRatio: AspectRatio,
        settings: ExportSettings,
        onProgress: (Float) -> Unit,
        onDone: (Uri) -> Unit,
        onError: (String) -> Unit
    ) {
        val allClips = tracks.flatMap { it.clips }
        // Like Alight Motion: export is allowed when at least one layer exists (any type).
        if (allClips.isEmpty()) {
            onError("Nothing to export: add at least one layer.")
            return
        }
        val videoClips = allClips
            .filter { it.type == LayerType.VIDEO && it.uri != null && it.isVisible }
            .sortedBy { it.startMs }
        val (w, h) = outputSize(settings.resolution, aspectRatio)

        val items: List<EditedMediaItem>
        val videoEndMs: Long
        val forceSilentAudio: Boolean
        if (videoClips.isNotEmpty()) {
            items = videoClips.map { buildItem(it, w, h) }
            videoEndMs = videoClips.maxOf { it.endMs }
            forceSilentAudio = videoClips.any { it.isImage }
        } else {
            // No media layer: create a solid black background video spanning all layers
            // (shapes / text / audio-only projects), so export always succeeds with ≥1 layer.
            videoEndMs = allClips.maxOf { it.endMs }.coerceAtLeast(100L)
            val blankUri = createBlankImageUri(w, h) ?: run {
                onError("Could not create background for export.")
                return
            }
            items = listOf(buildBlankItem(blankUri, videoEndMs, w, h))
            forceSilentAudio = true
        }

        // Shape + text layers burned into the export via OverlayEffect (Alight Motion style).
        val overlayClips = tracks
            .asReversed() // bottom lane first → top lane last (drawn on top)
            .flatMap { it.clips }
            .filter { it.isVisible && (it.type == LayerType.SHAPE || it.type == LayerType.TEXT) }

        val sequenceBuilder = EditedMediaItemSequence.Builder(items)
        // Photos / blank background have no audio; force a silent track so mixed sequences stay consistent.
        if (forceSilentAudio) sequenceBuilder.experimentalSetForceAudioTrack(true)
        // Audio layers: one sequence per layer, each one starts after a silent gap, and all of
        // them are mixed with the video's own sound by Transformer.
        val sequences = mutableListOf(sequenceBuilder.build())
        tracks.forEach { track ->
            track.clips.forEach { c ->
                if (c.type == LayerType.AUDIO && c.uri != null && c.isVisible && !c.isMuted && !track.isMuted) {
                    buildAudioSequence(c, videoEndMs)?.let { sequences += it }
                }
            }
        }

        val compositionBuilder = Composition.Builder(sequences)
        if (overlayClips.isNotEmpty()) {
            val overlay = LayerBitmapOverlay(overlayClips, w, h)
            val overlayEffect = OverlayEffect(listOf(overlay))
            compositionBuilder.setEffects(Effects(/* audio */ emptyList(), /* video */ listOf(overlayEffect)))
        }
        val composition = compositionBuilder.build()

        val out = File(context.cacheDir, "tracket_${System.currentTimeMillis()}.mp4")
        val t = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    stopPolling()
                    // Copying a large file must not block the UI thread.
                    Thread {
                        val result = runCatching { saveToMovies(out) }
                        main.post {
                            result.onSuccess(onDone)
                                .onFailure { onError(it.message ?: "Could not save the video") }
                        }
                    }.start()
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    stopPolling()
                    val detail = buildString {
                        append(exportException.message ?: "Export failed")
                        var c: Throwable? = exportException.cause
                        var depth = 0
                        while (c != null && depth < 4) {
                            c.message?.let { append(" → ").append(it) }
                            c = c.cause
                            depth++
                        }
                    }
                    onError(detail)
                }
            })
            .build()
        transformer = t
        t.start(composition, out.absolutePath)
        startPolling(t, onProgress)
    }

    fun cancel() {
        stopPolling()
        transformer?.cancel()
        transformer = null
    }

    private fun buildItem(c: TimelineClip, w: Int, h: Int): EditedMediaItem {
        val mi = MediaItem.Builder().setUri(c.uri!!.toUri())
        c.mimeType?.let { mi.setMimeType(it) }
        if (c.isImage) {
            // Required by Media3 AssetLoader for still images.
            mi.setImageDurationMs(c.durationMs.coerceAtLeast(100L))
        } else {
            mi.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(c.trimStartMs)
                    .setEndPositionMs(c.trimStartMs + c.durationMs)
                    .build()
            )
        }
        val video = mutableListOf<Effect>()
        if (c.isFlippedH || c.isFlippedV) {
            video += ScaleAndRotateTransformation.Builder()
                .setScale(if (c.isFlippedH) -1f else 1f, if (c.isFlippedV) -1f else 1f)
                .build()
        }
        video += Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT)

        val b = EditedMediaItem.Builder(mi.build())
            .setEffects(Effects(emptyList(), video))
            .setRemoveAudio(c.isMuted)
        if (c.isImage) b.setFrameRate(30)
        return b.build()
    }

    /**
     * Small solid black JPEG used as a background track when the project has layers
     * (shape / text / audio) but no video/photo media. Enables export with any layer,
     * similar to Alight Motion.
     *
     * A tiny image is used on purpose: Media3 AssetLoader is more reliable with small
     * stills; [Presentation] scales it to the full export resolution.
     */
    private fun createBlankImageUri(w: Int, h: Int): Uri? {
        return try {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "blank_${System.currentTimeMillis()}.jpg")
            // Keep the still tiny so the image decoder never OOMs / fails to init.
            val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawColor(Color.BLACK)
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            bmp.recycle()
            // content:// via FileProvider is more reliable for Media3 AssetLoader than file://
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun buildBlankItem(uri: Uri, durationMs: Long, w: Int, h: Int): EditedMediaItem {
        val dur = durationMs.coerceAtLeast(100L)
        // setImageDurationMs is required by Media3 AssetLoader for image inputs.
        val mi = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(MimeTypes.IMAGE_JPEG)
            .setImageDurationMs(dur)
            .build()
        // SCALE_TO_FIT expands the tiny black still to the full output size.
        val video = listOf(
            Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT)
        )
        return EditedMediaItem.Builder(mi)
            .setEffects(Effects(emptyList(), video))
            .setFrameRate(30)
            .build()
    }

    /** One audio layer -> a sequence: [silent gap until the layer starts] + the trimmed audio. */
    private fun buildAudioSequence(c: TimelineClip, videoEndMs: Long): EditedMediaItemSequence? {
        val durationMs = minOf(c.durationMs, videoEndMs - c.startMs)
        if (durationMs < 100L) return null
        val sourceSpanMs = (durationMs * c.speed).toLong()

        val mi = MediaItem.Builder().setUri(c.uri!!.toUri())
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(c.trimStartMs)
                    .setEndPositionMs(c.trimStartMs + sourceSpanMs)
                    .build()
            )
            .build()

        // Order matters: speed first, then volume/fades (which use the timeline clock).
        val processors = mutableListOf<AudioProcessor>()
        if (kotlin.math.abs(c.speed - 1f) > 0.001f) {
            processors += SonicAudioProcessor().apply {
                setSpeed(c.speed)
                setPitch(1f)
            }
        }
        if (c.volume != 1f || c.fadeInMs > 0L || c.fadeOutMs > 0L) {
            processors += GainFadeAudioProcessor(c.volume, c.fadeInMs, c.fadeOutMs, c.durationMs)
        }
        val item = EditedMediaItem.Builder(mi)
            .setRemoveVideo(true)
            .setEffects(Effects(processors, emptyList()))
            .build()

        val seq = EditedMediaItemSequence.Builder()
        if (c.startMs > 0L) seq.addGap(c.startMs * 1_000L)
        seq.addItem(item)
        return seq.build()
    }

    /** "1080p FHD" + 9:16 -> 1080x1920; short side = the number, both sides even. */
    private fun outputSize(resolution: String, ratio: AspectRatio): Pair<Int, Int> {
        val short = Regex("\\d{3,4}").find(resolution)?.value?.toInt() ?: 1080
        val r = ratio.ratioFloat
        val (w, h) = if (r >= 1f) (short * r) to short.toFloat() else short.toFloat() to (short / r)
        fun even(x: Float) = (x.roundToInt() / 2) * 2
        return even(w) to even(h)
    }

    private fun startPolling(t: Transformer, onProgress: (Float) -> Unit) {
        val holder = ProgressHolder()
        val r = object : Runnable {
            override fun run() {
                if (t.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    onProgress(holder.progress / 100f)
                }
                main.postDelayed(this, 200)
            }
        }
        poller = r
        main.post(r)
    }

    private fun stopPolling() {
        poller?.let(main::removeCallbacks)
        poller = null
    }

    private fun saveToMovies(file: File): Uri {
        val name = "Tracket_${System.currentTimeMillis()}.mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Tracket")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create a gallery entry")
            resolver.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            file.delete()
            return uri
        }
        // API 24-28: app-specific folder (no storage permission needed).
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val dest = File(dir, name)
        file.copyTo(dest, overwrite = true)
        file.delete()
        return Uri.fromFile(dest)
    }
}

/**
 * Draws shape + text layers onto each exported frame.
 * presentationTimeUs is composition time (from the start of the export).
 * Clips are already ordered bottom→top so later draws sit on top.
 */
@UnstableApi
private class LayerBitmapOverlay(
    private val clips: List<TimelineClip>,
    private val frameW: Int,
    private val frameH: Int
) : BitmapOverlay() {

    private var cached: Bitmap? = null
    private val clearPaint = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val bmp = cached?.takeIf { it.width == frameW && it.height == frameH }
            ?: Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888).also { cached = it }
        val canvas = Canvas(bmp)
        canvas.drawPaint(clearPaint) // transparent every frame

        val timeMs = presentationTimeUs / 1_000L
        for (clip in clips) {
            if (!clip.isActiveAt(timeMs)) continue
            if (clip.type == LayerType.SHAPE) {
                drawShape(canvas, clip)
            } else if (clip.type == LayerType.TEXT || clip.text != null) {
                drawText(canvas, clip)
            }
        }
        return bmp
    }

    private fun drawShape(canvas: Canvas, clip: TimelineClip) {
        val cx = frameW / 2f + clip.transformX
        val cy = frameH / 2f + clip.transformY
        val scale = (clip.scalePercent / 100f).coerceAtLeast(0.05f)
        val base = minOf(frameW, frameH) * 0.18f * scale
        val color = argb(clip.textColor.takeIf { it != 0L } ?: clip.color, fallback = 0xFFFF3377)
        val strokeCol = argb(clip.strokeColor, fallback = 0xFFFFFFFF)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = strokeCol
            style = Paint.Style.STROKE
            strokeWidth = (clip.strokeWidth.coerceAtLeast(0f) * scale).coerceAtLeast(0f)
        }

        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(clip.rotationDeg)
        canvas.scale(scale, scale)

        val pattern = clip.shapePattern ?: "CIRCLE"
        when (pattern.uppercase()) {
            "RECT", "RECTANGLE", "SQUARE" -> {
                val r = RectF(-base, -base, base, base)
                canvas.drawRect(r, paint)
                if (stroke.strokeWidth > 0f) canvas.drawRect(r, stroke)
            }
            "ROUNDED", "ROUNDED_RECT" -> {
                val r = RectF(-base, -base, base, base)
                val rad = base * 0.25f
                canvas.drawRoundRect(r, rad, rad, paint)
                if (stroke.strokeWidth > 0f) canvas.drawRoundRect(r, rad, rad, stroke)
            }
            "OVAL", "ELLIPSE" -> {
                val r = RectF(-base * 1.2f, -base * 0.75f, base * 1.2f, base * 0.75f)
                canvas.drawOval(r, paint)
                if (stroke.strokeWidth > 0f) canvas.drawOval(r, stroke)
            }
            "TRIANGLE" -> {
                val path = Path().apply {
                    moveTo(0f, -base)
                    lineTo(base, base)
                    lineTo(-base, base)
                    close()
                }
                canvas.drawPath(path, paint)
                if (stroke.strokeWidth > 0f) canvas.drawPath(path, stroke)
            }
            else -> { // CIRCLE
                canvas.drawCircle(0f, 0f, base, paint)
                if (stroke.strokeWidth > 0f) canvas.drawCircle(0f, 0f, base, stroke)
            }
        }
        canvas.restore()
    }

    private fun drawText(canvas: Canvas, clip: TimelineClip) {
        val content = clip.text?.ifBlank { null } ?: "Text"
        val textColor = argb(clip.textColor, fallback = 0xFFFFFFFF)
        val scale = (clip.scalePercent / 100f).coerceAtLeast(0.05f)
        val textSizePx = (minOf(frameW, frameH) * 0.06f * scale).coerceAtLeast(12f)

        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = textSizePx
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

        val maxWidth = (frameW * 0.85f).toInt().coerceAtLeast(1)
        val align = when (clip.textAlign) {
            0 -> Layout.Alignment.ALIGN_NORMAL
            2 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_CENTER
        }
        @Suppress("DEPRECATION")
        val layout = StaticLayout(content, tp, maxWidth, align, clip.lineHeightPercent / 100f, 0f, false)

        val cx = frameW / 2f + clip.transformX
        val cy = frameH / 2f + clip.transformY

        canvas.save()
        canvas.translate(cx - layout.width / 2f, cy - layout.height / 2f)
        canvas.rotate(clip.rotationDeg, layout.width / 2f, layout.height / 2f)

        // Optional background box
        if (clip.bgOpacity > 0f) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = argb(clip.bgColor, fallback = 0xFF000000)
                alpha = (clip.bgOpacity.coerceIn(0f, 100f) / 100f * 255).toInt()
            }
            val pad = clip.bgPadding.coerceAtLeast(0f)
            val rr = RectF(-pad, -pad, layout.width + pad, layout.height + pad)
            canvas.drawRoundRect(rr, clip.bgCorner, clip.bgCorner, bg)
        }

        // Optional stroke outline (draw text multiple offsets)
        if (clip.textStrokeThickness > 0f) {
            val strokePaint = TextPaint(tp).apply {
                style = Paint.Style.STROKE
                strokeWidth = clip.textStrokeThickness
                color = argb(clip.textStrokeColor, fallback = 0xFF000000)
            }
            @Suppress("DEPRECATION")
            val strokeLayout = StaticLayout(content, strokePaint, maxWidth, align, clip.lineHeightPercent / 100f, 0f, false)
            strokeLayout.draw(canvas)
        }

        layout.draw(canvas)
        canvas.restore()
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
