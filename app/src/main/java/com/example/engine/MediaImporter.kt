package com.example.engine

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.example.model.ImportedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object MediaImporter {

    private const val DEFAULT_IMAGE_MS = 5_000L

    /**
     * Reads name, mime type and duration of each picked file, then **copies** the bytes into
     * the app's private storage so the URI stays readable for preview and export.
     *
     * Photo Picker / GetContent only grant a temporary read permission. Without a local copy,
     * Media3 Transformer later fails with "AssetLoader failed".
     * Unreadable files are skipped.
     */
    suspend fun read(context: Context, uris: List<Uri>): List<ImportedMedia> =
        withContext(Dispatchers.IO) {
            val mediaDir = File(context.filesDir, "imports").apply { mkdirs() }
            uris.mapNotNull { uri ->
                runCatching {
                    val mime = context.contentResolver.getType(uri)
                    val isImage = mime?.startsWith("image/") == true
                    val name = displayName(context, uri)
                    var aspect = 0f
                    val duration = if (isImage) {
                        DEFAULT_IMAGE_MS
                    } else {
                        val r = MediaMetadataRetriever()
                        try {
                            r.setDataSource(context, uri)
                            val vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 0f
                            val vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 0f
                            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                            if (vw > 0f && vh > 0f) aspect = if (rot == 90 || rot == 270) vh / vw else vw / vh
                            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                ?.toLongOrNull() ?: 0L
                        } finally {
                            r.release()
                        }
                    }
                    if (duration <= 0L) return@runCatching null

                    // Copy into app storage so export/preview always have a stable URI.
                    val ext = extensionFor(mime, name, isImage)
                    val dest = File(mediaDir, "imp_${System.currentTimeMillis()}_${(name.hashCode() and 0xFFFF)}.$ext")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@runCatching null
                    if (dest.length() == 0L) {
                        dest.delete()
                        return@runCatching null
                    }

                    if (isImage) {
                        runCatching {
                            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            android.graphics.BitmapFactory.decodeFile(dest.absolutePath, o)
                            val orient = android.media.ExifInterface(dest.absolutePath)
                                .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
                            val swap = orient == 6 || orient == 8 || orient == 5 || orient == 7
                            if (o.outWidth > 0 && o.outHeight > 0) {
                                aspect = if (swap) o.outHeight.toFloat() / o.outWidth else o.outWidth.toFloat() / o.outHeight
                            }
                        }
                    }

                    // Prefer FileProvider content:// (works reliably with Media3 AssetLoader).
                    val stableUri = try {
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            dest
                        ).toString()
                    } catch (_: Exception) {
                        Uri.fromFile(dest).toString()
                    }

                    ImportedMedia(
                        uri = stableUri,
                        name = name,
                        mimeType = mime,
                        durationMs = duration,
                        isImage = isImage,
                        aspect = aspect
                    )
                }.getOrNull()
            }
        }

    private fun displayName(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: "Clip"

    private fun extensionFor(mime: String?, name: String, isImage: Boolean): String {
        val fromName = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (fromName in listOf("mp4", "mov", "webm", "mkv", "3gp", "jpg", "jpeg", "png", "webp", "gif", "heic")) {
            return fromName
        }
        return when {
            mime?.contains("jpeg") == true || mime?.contains("jpg") == true -> "jpg"
            mime?.contains("png") == true -> "png"
            mime?.contains("webp") == true -> "webp"
            mime?.contains("webm") == true -> "webm"
            mime?.contains("quicktime") == true -> "mov"
            isImage -> "jpg"
            else -> "mp4"
        }
    }
}
