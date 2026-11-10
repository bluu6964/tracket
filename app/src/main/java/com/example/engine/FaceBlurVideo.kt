package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache
import com.example.model.TimelineClip
import com.example.model.exportSpeed
import com.example.model.sourceOffsetMs
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Face AI (first tool: blur). Every frame of the clip's visible part goes through the MediaPipe Face
 * Landmarker (478 points per face, tracked from frame to frame in VIDEO mode); the chosen region
 * (whole face, eyes, nose or lips) of every face is drawn white on black, softened, and saved as a
 * small grey JPEG. Preview and export both read these masks ([FaceBlurMasks]) and blur the picture
 * only where the mask is white, so the blur follows the face.
 *
 * The decoder is the one of the cutout ([CutoutVideo.decodeFast], MediaCodec front to back, with the
 * seek-per-frame fallback), so the speed and the phone support are the same.
 */
object FaceBlurVideo {

    /** Longest side of the frames given to the landmarker (a bit bigger than the cutout: eyes and lips are small). */
    private const val FRAME_SIDE = 640
    /** Longest side of the saved masks. */
    private const val MASK_SIDE = 256
    private const val MAX_FPS = 30f
    private const val MAX_FACES = 5
    /** A frame where the face is lost keeps the last mask for this many frames (no blink of an unblurred face). */
    private const val HOLD_FRAMES = 8

    data class Result(
        val dir: String,
        val fps: Float,
        val startMs: Long,
        val frames: Int,
        val framesWithFace: Int,
        val summary: String
    )

    private class ProcessFailed(cause: Throwable) : Exception(cause)

    suspend fun generate(
        context: Context,
        clip: TimelineClip,
        region: FaceRegion,
        onProgress: (Float) -> Unit
    ): Result {
        val modelFile = FaceModelDownloader.modelFile(context)
        if (!FaceModelDownloader.isDownloaded(context)) throw IllegalStateException("Face model is missing")
        val fps = CutoutVideo.sourceFps(context, clip).coerceIn(10f, MAX_FPS)
        val t0 = System.nanoTime()
        val startMs = clip.trimStartMs
        var spanMs = (clip.durationMs * clip.exportSpeed).toLong().coerceAtLeast(100L)
        if (clip.sourceDurationMs > 0L) spanMs = minOf(spanMs, (clip.sourceDurationMs - startMs).coerceAtLeast(100L))
        val frames = (spanMs / 1000f * fps).toInt() + 2

        val dir = File(context.filesDir, "facemasks/" + UUID.randomUUID()).apply { mkdirs() }
        var ok = false
        try {
            var withFace = 0
            try {
                withFace = runPass(context, modelFile, dir, clip, region, fps, startMs, frames, onProgress, fast = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProcessFailed) {
                throw e.cause ?: e
            } catch (e: Exception) {
                // This phone's decoder does not give readable frames: slower, safe way (seek per frame).
                dir.listFiles()?.forEach { it.delete() }
                try {
                    withFace = runPass(context, modelFile, dir, clip, region, fps, startMs, frames, onProgress, fast = false)
                } catch (e2: ProcessFailed) {
                    throw e2.cause ?: e2
                }
            }
            if (withFace == 0) throw IllegalStateException("No face found in this layer")
            ok = true
            val sec = (System.nanoTime() - t0) / 1e9
            val summary = String.format(java.util.Locale.US, "%.1f s", sec)
            return Result(dir.absolutePath, fps, startMs, frames, withFace, summary)
        } finally {
            if (!ok) dir.deleteRecursively()
        }
    }

    /** One run over the clip (fresh landmarker, because VIDEO mode needs rising time stamps). Returns frames that had a face. */
    private suspend fun runPass(
        context: Context,
        modelFile: File,
        dir: File,
        clip: TimelineClip,
        region: FaceRegion,
        fps: Float,
        startMs: Long,
        frames: Int,
        onProgress: (Float) -> Unit,
        fast: Boolean
    ): Int {
        val landmarker = createLandmarker(context, modelFile)
        try {
            val painter = MaskPainter(region)
            var lastIdx = -1
            var lastBytes: ByteArray? = null
            var miss = 0
            var withFace = 0
            fun write(i: Int, data: ByteArray) {
                FileOutputStream(File(dir, FaceBlurMasks.fileName(i))).use { it.write(data) }
            }
            val handle: suspend (CutoutVideo.Frame) -> Unit = { f ->
                try {
                    val bmp = toBitmap(f)
                    val mp = BitmapImageBuilder(bmp).build()
                    val res = try {
                        landmarker.detectForVideo(mp, (f.index * 1000f / fps).toLong())
                    } finally {
                        mp.close()
                        bmp.recycle()
                    }
                    val data: ByteArray
                    if (res.faceLandmarks().isNotEmpty()) {
                        data = painter.draw(res.faceLandmarks(), f.w, f.h)
                        miss = 0
                        withFace++
                    } else {
                        miss++
                        data = if (miss <= HOLD_FRAMES && lastBytes != null) lastBytes!! else painter.empty(f.w, f.h)
                    }
                    for (g in (lastIdx + 1) until f.index) write(g, lastBytes ?: data)
                    write(f.index, data)
                    lastIdx = f.index
                    lastBytes = data
                    onProgress(((f.index + 1) / frames.toFloat()).coerceAtMost(1f))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    throw ProcessFailed(e)
                }
            }
            if (fast) CutoutVideo.decodeFast(context, clip, fps, startMs, frames, FRAME_SIDE, handle)
            else CutoutVideo.decodeSlow(context, clip, fps, startMs, frames, FRAME_SIDE, handle)
            val last = lastBytes ?: throw IllegalStateException("No frame could be processed")
            for (g in (lastIdx + 1) until frames) write(g, last)
            return withFace
        } finally {
            try { landmarker.close() } catch (_: Exception) {}
        }
    }

    private fun createLandmarker(context: Context, modelFile: File): FaceLandmarker {
        val bytes = modelFile.readBytes()
        val buf = ByteBuffer.allocateDirect(bytes.size)
        buf.put(bytes)
        buf.rewind()
        val base = BaseOptions.builder().setModelAssetBuffer(buf).build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(MAX_FACES)
            .setMinFaceDetectionConfidence(0.4f)
            .setMinFacePresenceConfidence(0.4f)
            .setMinTrackingConfidence(0.4f)
            .setOutputFaceBlendshapes(false)
            .build()
        return FaceLandmarker.createFromOptions(context, options)
    }

    /** Planar RGB (0..1) of the decoder -> ARGB bitmap for the landmarker. */
    private fun toBitmap(f: CutoutVideo.Frame): Bitmap {
        val wh = f.w * f.h
        val px = IntArray(wh)
        val c = f.chw
        for (i in 0 until wh) {
            val r = (c[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val g = (c[wh + i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val b = (c[2 * wh + i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bmp = Bitmap.createBitmap(f.w, f.h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, f.w, 0, 0, f.w, f.h)
        return bmp
    }

    /** Draws the region of every face into a soft white-on-black mask and returns it as JPEG bytes. */
    private class MaskPainter(private val region: FaceRegion) {
        private var bmp: Bitmap? = null
        private var canvas: Canvas? = null
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL_AND_STROKE
        }
        private var emptyBytes: ByteArray? = null

        private fun size(w: Int, h: Int): Pair<Int, Int> {
            val k = MASK_SIDE / maxOf(w, h).toFloat()
            return Pair((w * k).roundToInt().coerceAtLeast(16), (h * k).roundToInt().coerceAtLeast(16))
        }

        private fun prepare(mw: Int, mh: Int): Canvas {
            var b = bmp
            if (b == null || b.width != mw || b.height != mh) {
                b = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
                bmp = b
                canvas = Canvas(b)
            }
            canvas!!.drawColor(Color.BLACK)
            return canvas!!
        }

        private fun jpeg(): ByteArray {
            val bos = ByteArrayOutputStream(16 * 1024)
            bmp!!.compress(Bitmap.CompressFormat.JPEG, 90, bos)
            return bos.toByteArray()
        }

        fun empty(w: Int, h: Int): ByteArray {
            emptyBytes?.let { return it }
            val (mw, mh) = size(w, h)
            prepare(mw, mh)
            return jpeg().also { emptyBytes = it }
        }

        fun draw(faces: List<List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>>, w: Int, h: Int): ByteArray {
            val (mw, mh) = size(w, h)
            val cv = prepare(mw, mh)
            val soft = (mw * 0.012f).coerceAtLeast(1.5f)
            for (face in faces) {
                val polys = FaceRegions.polygons(region, face.size, mw, mh) { i ->
                    val p = face[i]
                    Pair(p.x(), p.y())
                }
                for ((poly, stroke) in polys) {
                    if (poly.size < 3) continue
                    val path = Path()
                    path.moveTo(poly[0].x, poly[0].y)
                    for (k in 1 until poly.size) path.lineTo(poly[k].x, poly[k].y)
                    path.close()
                    fill.strokeWidth = stroke
                    fill.maskFilter = BlurMaskFilter(soft, BlurMaskFilter.Blur.NORMAL)
                    cv.drawPath(path, fill)
                }
            }
            return jpeg()
        }
    }
}

/** How strong the blur looks. Shared by the preview (Compose) and the export (GL shader). */
object FaceBlurLook {
    /** Width of the small picture the blur works on: strength 0 -> 224 px, 1 -> 96 px (smaller = stronger). */
    fun lowWidth(strength: Float): Int = (224f + (96f - 224f) * strength.coerceIn(0f, 1f)).roundToInt()

    /** Distance between two blur taps, in pixels of that small picture. */
    const val TAP_STEP = 1.3f

    /** Gaussian sigma of the blur as a fraction of the picture width (13 taps, sigma = 3 taps). */
    fun sigmaFraction(strength: Float): Float = 3f * TAP_STEP / lowWidth(strength)
}

/** Reads the saved face masks (shared by the preview and the export). */
object FaceBlurMasks {

    fun fileName(index: Int) = String.format("f_%05d.jpg", index)

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 24).toInt().coerceAtLeast(2 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** Source time (ms) that the clip shows at timeline time [timelineMs]. */
    fun sourceMs(clip: TimelineClip, timelineMs: Long): Long =
        clip.trimStartMs + clip.sourceOffsetMs((timelineMs - clip.startMs).coerceAtLeast(0L))

    /** Mask frame (grey picture, white = blur) for source time [srcMs], or null. */
    fun bitmap(clip: TimelineClip, srcMs: Long): Bitmap? {
        val dir = clip.faceBlurDir ?: return null
        if (clip.faceBlurFrames <= 0 || clip.faceBlurFps <= 0f) return null
        val idx = (((srcMs - clip.faceBlurStartMs) * clip.faceBlurFps / 1000f) + 0.5f).toInt()
            .coerceIn(0, clip.faceBlurFrames - 1)
        val key = "$dir#$idx"
        cache.get(key)?.let { return it }
        val bmp = BitmapFactory.decodeFile(File(dir, fileName(idx)).absolutePath) ?: return null
        cache.put(key, bmp)
        return bmp
    }
}
