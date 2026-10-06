package com.example.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.media3.common.Effect
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
import kotlin.math.roundToInt

/**
 * Real export: renders the main track (trim, flip, mute, project aspect ratio)
 * to an H.264/AAC MP4 with Media3 Transformer, then saves it to Movies/Tracket.
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
        val clips = tracks.filter { it.type == LayerType.VIDEO }
            .flatMap { it.clips }.filter { it.uri != null }.sortedBy { it.startMs }
        if (clips.isEmpty()) {
            onError("Nothing to export: import a video or photo first.")
            return
        }
        val (w, h) = outputSize(settings.resolution, aspectRatio)
        val items = clips.map { buildItem(it, w, h) }

        val sequenceBuilder = EditedMediaItemSequence.Builder(items)
        // Photos have no audio; force a silent track so mixed sequences stay consistent.
        if (clips.any { it.isImage }) sequenceBuilder.experimentalSetForceAudioTrack(true)
        val composition = Composition.Builder(sequenceBuilder.build()).build()

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
