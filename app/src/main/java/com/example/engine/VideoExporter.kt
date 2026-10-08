package com.example.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.media3.common.Effect
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
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
            .filter { it.type == LayerType.VIDEO && it.uri != null }
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

        val sequenceBuilder = EditedMediaItemSequence.Builder(items)
        // Photos / blank background have no audio; force a silent track so mixed sequences stay consistent.
        if (forceSilentAudio) sequenceBuilder.experimentalSetForceAudioTrack(true)
        // Audio layers: one sequence per layer, each one starts after a silent gap, and all of
        // them are mixed with the video's own sound by Transformer.
        val sequences = mutableListOf(sequenceBuilder.build())
        tracks.forEach { track ->
            track.clips.forEach { c ->
                if (c.type == LayerType.AUDIO && c.uri != null && !c.isMuted && !track.isMuted) {
                    buildAudioSequence(c, videoEndMs)?.let { sequences += it }
                }
            }
        }
        val composition = Composition.Builder(sequences).build()

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
                    onError(exportException.message ?: "Export failed")
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
        if (!c.isImage) {
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
        if (c.isImage) b.setDurationUs(c.durationMs * 1_000L).setFrameRate(30)
        return b.build()
    }

    /**
     * Solid black image used as a background track when the project has layers
     * (shape / text / audio) but no video/photo media. Enables export with any layer,
     * similar to Alight Motion.
     */
    private fun createBlankImageUri(w: Int, h: Int): Uri? {
        return try {
            val bmp = Bitmap.createBitmap(w.coerceAtLeast(2), h.coerceAtLeast(2), Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawColor(Color.BLACK)
            val file = File(context.cacheDir, "tracket_blank_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bmp.recycle()
            Uri.fromFile(file)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildBlankItem(uri: Uri, durationMs: Long, w: Int, h: Int): EditedMediaItem {
        val mi = MediaItem.Builder().setUri(uri).setMimeType(MimeTypes.IMAGE_PNG).build()
        val video = listOf(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT))
        return EditedMediaItem.Builder(mi)
            .setEffects(Effects(emptyList(), video))
            .setDurationUs(durationMs * 1_000L)
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
