package com.example.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.model.AspectRatio
import com.example.model.ExportFormats
import com.example.model.ExportRange
import com.example.model.ExportSettings
import com.example.model.TimelineTrack
import com.example.model.windowTracksForRange
import java.io.File

/**
 * Export entry point. The C++ engine ([NativeProjectExporter]) renders every project; the
 * chosen [ExportSettings.format] decides the container: an H.264/AAC MP4 saved to
 * Movies/MaxCut, an animated GIF saved to Pictures/MaxCut, or a PNG sequence saved to a
 * Pictures/MaxCut folder (build 189 — the raster formats ride the same frame-rendering
 * path and the same progress / cancel / error surface). There is no Media3 / Transformer
 * path any more: if the native export cannot run or fails, [onError] gets the reason.
 *
 * Export is allowed when at least one layer of any type exists. Must be used from the main thread.
 */
class VideoExporter(private val context: Context) {

    private var nativeExporter: NativeProjectExporter? = null

    fun start(
        tracks: List<TimelineTrack>,
        aspectRatio: AspectRatio,
        settings: ExportSettings,
        previewFrameWidthPx: Float = 0f,
        previewDensity: Float = 1f,
        onProgress: (Float) -> Unit,
        onDone: (Uri) -> Unit,
        onError: (String) -> Unit,
        // Build 199 (A10): when set, only this window of the project is exported — the tracks
        // are windowed + re-based once here (model.windowTracksForRange), so MP4, GIF and the
        // PNG sequence all honour the range through the one path, and the GIF frame cap then
        // applies to the ranged length. Null = the whole project, exactly as before.
        range: ExportRange? = null
    ) {
        // LayerPainter (text / shape / sticker) scales preview units to the export size with these.
        PreviewFrameRef.widthPx = previewFrameWidthPx
        PreviewFrameRef.density = previewDensity
        val effectiveTracks = if (range != null) windowTracksForRange(tracks, range) else tracks
        val reason = NativeProjectExporter.unsupportedReason(effectiveTracks)
        if (reason != null) {
            Log.w("VideoExporter", "export refused: $reason")
            onError("Cannot export: $reason")
            return
        }
        Log.i("VideoExporter", "native C++ export")
        val raster = when (settings.format) {
            ExportFormats.GIF -> RasterFormat.GIF
            ExportFormats.PNG_SEQUENCE -> RasterFormat.PNG_SEQUENCE
            else -> null
        }
        val native = NativeProjectExporter(context)
        nativeExporter = native
        native.start(
            effectiveTracks, aspectRatio, settings,
            save = { file ->
                when (raster) {
                    RasterFormat.GIF -> saveGifToPictures(file)
                    RasterFormat.PNG_SEQUENCE -> savePngSequence(file)
                    null -> saveToMovies(file)
                }
            },
            onProgress = onProgress,
            onDone = { nativeExporter = null; onDone(it) },
            onError = { msg ->
                nativeExporter = null
                Log.w("VideoExporter", "native export failed: $msg")
                onError(msg)
            },
            rasterFormat = raster
        )
    }

    fun cancel() {
        nativeExporter?.cancel()
        nativeExporter = null
    }

    private fun saveToMovies(file: File): Uri {
        val name = "MaxCut_${System.currentTimeMillis()}.mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MaxCut")
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

    /** The finished GIF goes to Pictures/MaxCut, beside the frame saves. */
    private fun saveGifToPictures(file: File): Uri {
        val name = "MaxCut_${System.currentTimeMillis()}.gif"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MaxCut")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create a gallery entry")
            try {
                resolver.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            file.delete()
            return uri
        }
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir, "MaxCut")
        dir.mkdirs()
        val dest = File(dir, name)
        file.copyTo(dest, overwrite = true)
        file.delete()
        return Uri.fromFile(dest)
    }

    /**
     * The PNG frames of [dir] go to their own Pictures/MaxCut/MaxCut_<stamp> folder, in
     * frame order. Returns the first frame's Uri. If any frame fails to save, the ones
     * already saved are removed again and the error propagates — a partial sequence is
     * never presented as a finished export.
     */
    private fun savePngSequence(dir: File): Uri {
        val frames = dir.listFiles { f -> f.isFile && f.name.endsWith(".png") }
            ?.sortedBy { it.name } ?: emptyList()
        require(frames.isNotEmpty()) { "The PNG sequence is empty." }
        val folder = "MaxCut_${System.currentTimeMillis()}"
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val saved = ArrayList<Uri>()
            try {
                for (frame in frames) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, frame.name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MaxCut/$folder")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: error("Could not create a gallery entry for ${frame.name}")
                    saved.add(uri)
                    resolver.openOutputStream(uri)!!.use { o -> frame.inputStream().use { it.copyTo(o) } }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            } catch (t: Throwable) {
                saved.forEach { runCatching { resolver.delete(it, null, null) } }
                throw t
            }
            dir.deleteRecursively()
            return saved.first()
        }
        val base = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir, "MaxCut")
        val destDir = File(base, folder)
        destDir.mkdirs()
        for (frame in frames) frame.copyTo(File(destDir, frame.name), overwrite = true)
        dir.deleteRecursively()
        return Uri.fromFile(File(destDir, frames.first().name))
    }
}
