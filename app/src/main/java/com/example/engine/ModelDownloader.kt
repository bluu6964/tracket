package com.example.engine

import android.content.Context
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Downloads the cutout model (RVM, ONNX, ~14 MB) the first time it is needed, so it does not have
 * to ship inside the APK. The file is kept in the app's files folder.
 */
object ModelDownloader {

    const val MODEL_URL =
        "https://github.com/PeterL1n/RobustVideoMatting/releases/download/v1.0.0/rvm_mobilenetv3_fp32.onnx"

    /** SHA-256 (hex) of the model file. Empty = only the size is checked. Fill it in to verify the file. */
    private const val SHA256 = ""

    private const val MIN_BYTES = 8_000_000L

    fun modelFile(context: Context): File =
        File(File(context.filesDir, "models"), "rvm_mobilenetv3_fp32.onnx")

    fun isDownloaded(context: Context): Boolean = modelFile(context).length() >= MIN_BYTES

    /** Downloads the model. Cancelling the coroutine stops it and removes the half file. */
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
            val digest = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done / total.toFloat()).coerceIn(0f, 1f))
                    }
                }
            }
            if (tmp.length() < MIN_BYTES || (total > 0 && tmp.length() != total)) {
                throw IOException("Downloaded file is incomplete, try again")
            }
            if (SHA256.isNotEmpty()) {
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                if (!hex.equals(SHA256, ignoreCase = true)) throw IOException("Downloaded file is damaged, try again")
            }
            dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("Could not save the model")
        } finally {
            conn?.disconnect()
            if (tmp.exists()) tmp.delete()
        }
    }
}
