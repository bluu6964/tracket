package com.example.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.model.AspectRatio
import com.example.model.ExportSettings
import com.example.model.TimelineTrack
import java.io.File

/**
 * Export entry point. The C++ engine ([NativeProjectExporter]) renders every project to an
 * H.264/AAC MP4, then the file is saved to Movies/After Motion. There is no Media3 / Transformer
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
        onError: (String) -> Unit
    ) {
        // LayerPainter (text / shape / sticker) scales preview units to the export size with these.
        PreviewFrameRef.widthPx = previewFrameWidthPx
        PreviewFrameRef.density = previewDensity
        val reason = NativeProjectExporter.unsupportedReason(tracks)
        if (reason != null) {
            Log.w("VideoExporter", "export refused: $reason")
            onError("Cannot export: $reason")
            return
        }
        Log.i("VideoExporter", "native C++ export")
        val native = NativeProjectExporter(context)
        nativeExporter = native
        native.start(
            tracks, aspectRatio, settings,
            save = { saveToMovies(it) },
            onProgress = onProgress,
            onDone = { nativeExporter = null; onDone(it) },
            onError = { msg ->
                nativeExporter = null
                Log.w("VideoExporter", "native export failed: $msg")
                onError(msg)
            }
        )
    }

    fun cancel() {
        nativeExporter?.cancel()
        nativeExporter = null
    }

    private fun saveToMovies(file: File): Uri {
        val name = "AfterMotion_${System.currentTimeMillis()}.mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/After Motion")
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
