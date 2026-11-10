package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.example.model.AnimProp
import com.example.model.TimelineClip
import com.example.model.sourceOffsetMs
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Raw JNI functions of libam_engine.so (cpp/engine/face_swap_jni.cpp, face_swap.h). Use [FaceSwapBuilder]. */
internal object FaceSwapNative {
    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeDestroy(p: Long)
    @JvmStatic external fun nativeSetSource(p: Long, rgba: ByteArray, w: Int, h: Int, lm: FloatArray): Boolean
    @JvmStatic external fun nativeApply(p: Long, dst: ByteArray, w: Int, h: Int, lm: FloatArray, strength: Float): Boolean
}

/**
 * Face swap (light version, no neural network): the face of a photo is bent onto the face in the video along the 468-point
 * MediaPipe face mesh, matched to the video's colour and light and blended with a soft edge. The layer is pre-rendered into a
 * new video file (like [SlowMoBuilder]); the new file carries an "AI-edited" marker box and has no sound.
 *
 * Only for faces of people who agreed (the screen asks for it before starting). One face is followed: the biggest one in the
 * first frame, then the one nearest to where it was.
 */
object FaceSwapBuilder {
    class Result(val file: File, val durationMs: Long, val frames: Int, val swapped: Int)

    /** The photo has no face the model can use. */
    class NoFace(message: String) : RuntimeException(message)

    private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    private const val MARKER = "AI-edited: face swap (After Motion)"

    suspend fun render(
        context: Context,
        clip: TimelineClip,
        photo: Uri,
        onProgress: (Float) -> Unit = {}
    ): Result {
        val job = coroutineContext[Job]
        val uri = clip.uri ?: error("This layer has no video.")
        check(DecoderPool.isAvailable) { "The native engine is not available on this phone." }
        check(FaceModelDownloader.isDownloaded(context)) { "The face model is not downloaded yet." }
        return withContext(Dispatchers.Default) {
            val outDir = File(context.filesDir, "faceswap").apply { mkdirs() }
            val outFile = File(outDir, "swap_${clip.id}_${System.currentTimeMillis()}.mp4")
            val handle = FaceSwapNative.nativeCreate()
            try {
                val source = loadPhoto(context, photo)
                val srcLm = detectOne(context, source) ?: throw NoFace("No face found in the photo. Use a clear, front-facing picture.")
                val bytes = ByteBuffer.allocate(source.width * source.height * 4)
                source.copyPixelsToBuffer(bytes)
                if (!FaceSwapNative.nativeSetSource(handle, bytes.array(), source.width, source.height, srcLm)) {
                    throw NoFace("The face in the photo could not be used. Try another picture (face straight, fully visible).")
                }
                source.recycle()
                GlThread("am-faceswap").use { gl ->
                    gl.call {
                        run(context, clip, Uri.parse(uri), outFile, handle, onProgress) { job?.isActive == false }
                    }
                }
            } catch (t: Throwable) {
                outFile.delete()
                throw t
            } finally {
                FaceSwapNative.nativeDestroy(handle)
            }
        }
    }

    // ---- the photo -----------------------------------------------------------------------------------------------

    private fun loadPhoto(context: Context, uri: Uri): Bitmap {
        val cr = context.contentResolver
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (max(opts.outWidth, opts.outHeight) / sample > 1600) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
            ?: error("Could not open the photo.")
        val orientation = runCatching {
            cr.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
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
        if (bmp.config != Bitmap.Config.ARGB_8888) bmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
        return bmp
    }

    private fun createLandmarker(context: Context, mode: RunningMode, faces: Int): FaceLandmarker {
        val model = FaceModelDownloader.modelFile(context).readBytes()
        val buf = ByteBuffer.allocateDirect(model.size)
        buf.put(model)
        buf.rewind()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(buf).build())
            .setRunningMode(mode)
            .setNumFaces(faces)
            .setMinFaceDetectionConfidence(0.4f)
            .setMinFacePresenceConfidence(0.4f)
            .setMinTrackingConfidence(0.4f)
            .setOutputFaceBlendshapes(false)
            .build()
        return FaceLandmarker.createFromOptions(context, options)
    }

    /** The 468 landmarks (pixels) of the biggest face of [bmp], or null. */
    private fun detectOne(context: Context, bmp: Bitmap): FloatArray? {
        val lmk = createLandmarker(context, RunningMode.IMAGE, 1)
        try {
            val res = lmk.detect(BitmapImageBuilder(bmp).build())
            val face = res.faceLandmarks().firstOrNull() ?: return null
            if (face.size < 468) return null
            return FloatArray(936) { i -> if (i % 2 == 0) face[i / 2].x() * bmp.width else face[i / 2].y() * bmp.height }
        } finally {
            runCatching { lmk.close() }
        }
    }

    // ---- the video -----------------------------------------------------------------------------------------------

    private fun run(
        context: Context, clip: TimelineClip, uri: Uri, outFile: File, handle: Long,
        onProgress: (Float) -> Unit, cancelled: () -> Boolean
    ): Result {
        val renderer = NativeRenderer.create() ?: error("Native GLES renderer is unavailable on this device.")
        val pool = DecoderPool(maxHardware = 2, maxSoftware = 2)
        val encoder = NativeExportEncoder.create()
        if (encoder == null) {
            pool.close()
            renderer.close()
            error("Native export needs Android 8.0 (API 26) or newer and the native engine.")
        }
        var toClose: VideoLayerSource? = null
        var landmarker: FaceLandmarker? = null
        var tex2d = 0
        try {
            val source = VideoLayerSource.open(context, uri, renderer, pool) ?: error("Could not open the video.")
            toClose = source
            val cw = source.contentWidth.coerceAtLeast(2)
            val ch = source.contentHeight.coerceAtLeast(2)
            val k = min(1f, 1080f / max(cw, ch))
            val W = max(2, ((cw * k).roundToInt() / 2) * 2)
            val H = max(2, ((ch * k).roundToInt() / 2) * 2)
            val outFps = 30
            val bitrate = (W.toLong() * H * outFps / 6).coerceIn(2_000_000L, 24_000_000L).toInt()
            outFile.parentFile?.mkdirs()
            if (outFile.exists()) outFile.delete()
            require(encoder.start(renderer, outFile.absolutePath, W, H, outFps, bitrate)) {
                encoder.errorMessage().ifBlank { "Could not start the video encoder." }
            }
            tex2d = renderer.createTexture2d()
            landmarker = createLandmarker(context, RunningMode.VIDEO, 4)

            val bytes = W * H * 4
            val buf = ByteArray(bytes)            // RGBA, rows bottom-to-top (what readPixels gives)
            val top = ByteArray(bytes)            // the same, top row first (for the landmarker)
            val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val topBuf = ByteBuffer.wrap(top)
            val row = W * 4

            fun seekPatient(us: Long): Int {
                var st = source.seek(max(0L, us))
                var tries = 0
                while (st == DecoderPool.BUSY && tries++ < 100) {
                    Thread.sleep(10)
                    st = source.seek(max(0L, us))
                }
                return st
            }

            val total = ((clip.durationMs.coerceAtLeast(1L) * outFps + 999L) / 1000L).coerceAtLeast(1L).toInt()
            val constantSpeed = clip.keyframes[AnimProp.SPEED].isNullOrEmpty()
            var swapped = 0
            var prevLm: FloatArray? = null     // the followed face, top-left origin, pixels
            var lost = 0
            for (j in 0 until total) {
                if (cancelled()) throw CancellationException("Cancelled")
                val relMs = j * 1000.0 / outFps
                val srcMs = if (constantSpeed) clip.trimStartMs + relMs * clip.speed
                else (clip.trimStartMs + clip.sourceOffsetMs(relMs.toLong())).toDouble()
                val st = seekPatient((srcMs * 1000.0).toLong())
                if (st < 0 && st != DecoderPool.END_OF_STREAM) error("Could not decode the video at ${srcMs.toLong()} ms.")
                renderer.beginFrame(0f, 0f, 0f, 1f)
                renderer.drawLayer(
                    source.textureId, true, source.texMatrix, source.contentWidth, source.contentHeight,
                    source.drawRotation, LayerParams()
                )
                require(renderer.readPixels(0, 0, W, H, buf)) { "Could not read the frame." }

                // landmarker wants the picture upright (top row first)
                for (y in 0 until H) System.arraycopy(buf, (H - 1 - y) * row, top, y * row, row)
                topBuf.rewind()
                bitmap.copyPixelsFromBuffer(topBuf)
                val res = landmarker!!.detectForVideo(BitmapImageBuilder(bitmap).build(), (j * 1000L / outFps))
                val faces = res.faceLandmarks().filter { it.size >= 468 }.map { f ->
                    FloatArray(936) { i -> if (i % 2 == 0) f[i / 2].x() * W else f[i / 2].y() * H }
                }
                var chosen: FloatArray? = null
                if (faces.isNotEmpty()) {
                    val prev = prevLm
                    chosen = if (prev == null) faces.maxByOrNull { faceSize(it) }
                    else faces.minByOrNull { dist(center(it), center(prev)) }
                    if (prev != null && chosen != null && dist(center(chosen), center(prev)) > 1.2f * faceSize(prev)) chosen = null   // another person
                }
                if (chosen != null) {
                    val smooth = smooth(prevLm, chosen)
                    prevLm = smooth
                    lost = 0
                    chosen = smooth
                } else if (prevLm != null && ++lost <= 2) {
                    chosen = prevLm                        // a short gap: hold the last place
                } else if (lost > 2) {
                    prevLm = null                          // really gone: look for the biggest face again
                }
                val use = chosen
                if (use != null) {
                    // the frame buffer is bottom-to-top: flip the landmarks' y to match
                    val lm = FloatArray(936) { i -> if (i % 2 == 0) use[i] else H - use[i] }
                    if (FaceSwapNative.nativeApply(handle, buf, W, H, lm, 0.9f)) swapped++
                }

                require(renderer.uploadRgba(tex2d, W, H, buf)) { "Could not upload the frame." }
                renderer.beginFrame(0f, 0f, 0f, 1f)
                renderer.drawLayer(tex2d, false, IDENTITY, W, H, 0, LayerParams())
                require(encoder.submitFrame(j.toLong() * 1_000_000_000L / outFps)) {
                    encoder.errorMessage().ifBlank { "The video encoder failed on frame $j." }
                }
                if (j % 3 == 0 || j == total - 1) onProgress((j + 1).toFloat() / total)
            }
            require(encoder.finish()) { encoder.errorMessage().ifBlank { "Could not finish the new video." } }
            runCatching { appendMarker(outFile) }
            return Result(outFile, clip.durationMs.coerceAtLeast(1L), total, swapped)
        } catch (t: Throwable) {
            runCatching { encoder.cancel() }
            outFile.delete()
            throw t
        } finally {
            if (tex2d != 0) runCatching { renderer.deleteTexture(tex2d) }
            runCatching { landmarker?.close() }
            runCatching { toClose?.close() }
            runCatching { encoder.close() }
            runCatching { pool.close() }
            runCatching { renderer.close() }
        }
    }

    private fun center(lm: FloatArray): FloatArray {
        var x = 0f; var y = 0f
        for (i in 0 until 468) { x += lm[2 * i]; y += lm[2 * i + 1] }
        return floatArrayOf(x / 468f, y / 468f)
    }

    private fun faceSize(lm: FloatArray): Float {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until 468) {
            minX = min(minX, lm[2 * i]); maxX = max(maxX, lm[2 * i])
            minY = min(minY, lm[2 * i + 1]); maxY = max(maxY, lm[2 * i + 1])
        }
        return max(maxX - minX, maxY - minY)
    }

    private fun dist(a: FloatArray, b: FloatArray): Float = hypot(a[0] - b[0], a[1] - b[1])

    /** Takes the edge off the landmarks' jitter: a still face is averaged over frames, a fast move follows at once. */
    private fun smooth(prev: FloatArray?, now: FloatArray): FloatArray {
        if (prev == null) return now
        val size = faceSize(now).coerceAtLeast(1f)
        val move = dist(center(now), center(prev)) / size
        val a = (0.4f + move * 8f).coerceIn(0.4f, 1f)   // weight of the new landmarks
        return FloatArray(936) { i -> prev[i] + (now[i] - prev[i]) * a }
    }

    /**
     * Adds a small top-level "uuid" box with the text [MARKER] at the end of the MP4 (players skip unknown boxes; tools that
     * look for it find it), so the file says it was made with AI.
     */
    private fun appendMarker(file: File) {
        val text = MARKER.toByteArray(Charsets.UTF_8)
        val uuid = byteArrayOf(0x41, 0x4D, 0x41, 0x49, 0x45, 0x44, 0x49, 0x54, 0x45, 0x44, 0x46, 0x53, 0x57, 0x41, 0x50, 0x31)
        val size = 4 + 4 + 16 + text.size
        RandomAccessFile(file, "rw").use { f ->
            f.seek(f.length())
            f.write(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
            f.write("uuid".toByteArray(Charsets.US_ASCII))
            f.write(uuid)
            f.write(text)
        }
    }
}
