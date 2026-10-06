package com.example.engine

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.example.model.ImportedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MediaImporter {

    private const val DEFAULT_IMAGE_MS = 5_000L

    /** Reads name, mime type and duration of each picked file. Unreadable files are skipped. */
    suspend fun read(context: Context, uris: List<Uri>): List<ImportedMedia> =
        withContext(Dispatchers.IO) {
            uris.mapNotNull { uri ->
                runCatching {
                    val mime = context.contentResolver.getType(uri)
                    val isImage = mime?.startsWith("image/") == true
                    val duration = if (isImage) {
                        DEFAULT_IMAGE_MS
                    } else {
                        val r = MediaMetadataRetriever()
                        try {
                            r.setDataSource(context, uri)
                            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                ?.toLongOrNull() ?: 0L
                        } finally {
                            r.release()
                        }
                    }
                    if (duration <= 0L) return@runCatching null
                    ImportedMedia(
                        uri = uri.toString(),
                        name = displayName(context, uri),
                        mimeType = mime,
                        durationMs = duration,
                        isImage = isImage
                    )
                }.getOrNull()
            }
        }

    private fun displayName(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: "Clip"
}
