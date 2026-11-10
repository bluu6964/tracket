package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cutout test with ML Kit Selfie Segmentation: keeps the person, makes the background transparent.
 * Works on a single picture (a photo layer, or one frame of a video). The result is saved as a PNG
 * with transparency in the app's files folder.
 */
object CutoutEngine {

    /** Longest side of the picture given to the model (keeps memory and time reasonable). */
    private const val MAX_SIDE = 1280

    /** Mask confidence below LOW is background, above HIGH is person; in between is a soft edge. */
    private const val LOW = 0.35f
    private const val HIGH = 0.75f

    /** Photo from a uri, upright (EXIF applied), downscaled to [MAX_SIDE]. */
    fun loadPhoto(context: Context, uri: Uri): Bitmap? {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val orientation = try {
            cr.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        }
        if (!m.isIdentity) {
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (r !== bmp) bmp.recycle()
            bmp = r
        }
        return bmp
    }

    /** One video frame at [timeMs] of the source (already upright), downscaled to [MAX_SIDE]. */
    fun loadVideoFrame(context: Context, uri: Uri, timeMs: Long): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val f = r.getFrameAtTime(timeMs.coerceAtLeast(0L) * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: return null
            val longest = maxOf(f.width, f.height)
            if (longest <= MAX_SIDE) f
            else {
                val k = MAX_SIDE / longest.toFloat()
                val s = Bitmap.createScaledBitmap(f, (f.width * k).toInt().coerceAtLeast(1), (f.height * k).toInt().coerceAtLeast(1), true)
                if (s !== f) f.recycle()
                s
            }
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /** The person of [src] on a transparent background. */
    suspend fun cutout(src: Bitmap): Bitmap {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        val segmenter = Segmentation.getClient(options)
        try {
            val mask = suspendCancellableCoroutine { cont ->
                segmenter.process(InputImage.fromBitmap(src, 0))
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            val mw = mask.width
            val mh = mask.height
            val buf = mask.buffer
            buf.rewind()
            val conf = FloatArray(mw * mh)
            buf.asFloatBuffer().get(conf)

            val w = src.width
            val h = src.height
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)
            for (y in 0 until h) {
                val my = (y * mh / h).coerceAtMost(mh - 1)
                for (x in 0 until w) {
                    val mx = (x * mw / w).coerceAtMost(mw - 1)
                    val a = smooth(conf[my * mw + mx])
                    val c = px[y * w + x]
                    px[y * w + x] = ((a * 255f + 0.5f).toInt() shl 24) or (c and 0x00FFFFFF)
                }
            }
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(px, 0, w, 0, 0, w, h)
            return out
        } finally {
            segmenter.close()
        }
    }

    /** RVM when its model file is present, otherwise ML Kit. Returns the picture and the engine used. */
    suspend fun cutoutAuto(context: Context, src: Bitmap): Pair<Bitmap, String> {
        if (!RvmEngine.isAvailable(context)) return cutout(src) to "ML Kit (RVM model file missing)"
        return try {
            RvmEngine.matte(context, src) to "RVM"
        } catch (t: Throwable) {
            cutout(src) to "ML Kit (RVM failed: ${t.message})"
        }
    }

    private fun smooth(c: Float): Float {
        val t = ((c - LOW) / (HIGH - LOW)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Saves [bmp] as a PNG (keeps transparency) and returns its uri string. */
    fun savePng(context: Context, bmp: Bitmap): String {
        val dir = File(context.filesDir, "cutouts").apply { mkdirs() }
        val file = File(dir, "cutout_${UUID.randomUUID()}.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file.toUri().toString()
    }
}
