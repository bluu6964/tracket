package com.example.engine

import android.content.Context
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Downloads the speech model (whisper "small", 5-bit quantised, ~181 MB) the first time Auto
 * Captions is used, so it does not have to ship inside the APK (same idea as [ModelDownloader]
 * for the cutout model). The file is kept in the app's files folder and works offline afterwards.
 *
 * Unlike the small cutout model, this one is big, so an interrupted download is RESUMED next time
 * (the ".part" file is kept after a failure or Cancel and continued with an HTTP Range request).
 */
object WhisperModelDownloader {

    const val MODEL_NAME = "ggml-small-q5_1.bin"

    const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$MODEL_NAME"

    /** Shown in the "Download?" dialog. */
    const val APPROX_SIZE_TEXT = "about 181 MB"

    /** The real file is about 181 MiB; anything smaller is a broken download or an error page. */
    private const val MIN_BYTES = 170_000_000L

    /** Whisper model files start with the bytes "lmgg" (the ggml magic number, little endian). */
    private val MAGIC = byteArrayOf('l'.code.toByte(), 'm'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte())

    fun modelFile(context: Context): File = File(File(context.filesDir, "models"), MODEL_NAME)

    fun isDownloaded(context: Context): Boolean = modelFile(context).length() >= MIN_BYTES

    /** Removes the model and any half-finished download. */
    fun delete(context: Context) {
        modelFile(context).delete()
        File(modelFile(context).parentFile, MODEL_NAME + ".part").delete()
    }

    /** Downloads (or continues downloading) the model. Cancelling the coroutine stops it and keeps the ".part" file. */
    suspend fun download(
        context: Context,
        onBytes: (done: Long, total: Long) -> Unit = { _, _ -> },
        onProgress: (Float) -> Unit
    ) {
        val dest = modelFile(context)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, "$MODEL_NAME.part")
        var conn: HttpURLConnection? = null
        try {
            var have = if (tmp.exists()) tmp.length() else 0L
            conn = open(have)
            var code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK && have > 0L) {
                // the server ignored the Range header: start from zero
                have = 0L
                tmp.delete()
            }
            if (code == 416) {
                // asked for bytes beyond the end: the .part file is stale, start over
                conn.disconnect()
                tmp.delete()
                have = 0L
                conn = open(0L)
                code = conn.responseCode
            }
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("Download failed (HTTP $code)")
            }
            val remaining = conn.contentLengthLong
            val total = if (remaining > 0) have + remaining else -1L
            conn.inputStream.use { input ->
                RandomAccessFile(tmp, "rw").use { out ->
                    out.seek(have)
                    val buf = ByteArray(128 * 1024)
                    var done = have
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.nanoTime()
                        if (total > 0 && now - lastReport > 100_000_000L) {
                            lastReport = now
                            onBytes(done, total)
                            onProgress((done / total.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                    out.setLength(done)
                }
            }
            if (tmp.length() < MIN_BYTES || (total > 0 && tmp.length() != total)) {
                // connection dropped half way: keep the file, the next try continues from here
                throw IOException("Download was interrupted, try again to continue")
            }
            if (!hasMagic(tmp)) {
                tmp.delete()
                throw IOException("Downloaded file is not a speech model, try again")
            }
            onProgress(1f)
            onBytes(tmp.length(), tmp.length())
            dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("Could not save the model")
        } finally {
            conn?.disconnect()
        }
    }

    private fun open(from: Long): HttpURLConnection =
        (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (from > 0L) setRequestProperty("Range", "bytes=$from-")
        }

    private fun hasMagic(file: File): Boolean = try {
        file.inputStream().use { s ->
            val head = ByteArray(4)
            s.read(head) == 4 && head.contentEquals(MAGIC)
        }
    } catch (e: IOException) {
        false
    }
}
