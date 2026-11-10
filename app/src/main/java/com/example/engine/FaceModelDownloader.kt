package com.example.engine

import android.content.Context
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Downloads the MediaPipe Face Landmarker model (face_landmarker.task, ~4 MB) the first time Face AI
 * is used, like the cutout and speech models. Kept in the app's files folder, works offline after that.
 */
object FaceModelDownloader {

    const val MODEL_URL =
        "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"

    const val APPROX_SIZE_TEXT = "about 4 MB"

    private const val MIN_BYTES = 1_500_000L

    fun modelFile(context: Context): File =
        File(File(context.filesDir, "models"), "face_landmarker.task")

    fun isDownloaded(context: Context): Boolean = modelFile(context).length() >= MIN_BYTES

    suspend fun download(context: Context, onProgress: (Float) -> Unit) {
        val dest = modelFile(context)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("Download failed (HTTP $code)")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done / total.toFloat()).coerceIn(0f, 1f))
                    }
                }
            }
            if (tmp.length() < MIN_BYTES || (total > 0 && tmp.length() != total)) {
                throw IOException("Downloaded file is incomplete, try again")
            }
            dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("Could not save the model")
        } finally {
            conn?.disconnect()
            if (tmp.exists()) tmp.delete()
        }
    }
}
