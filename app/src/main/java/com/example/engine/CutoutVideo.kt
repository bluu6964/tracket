package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.core.net.toUri
import com.example.model.TimelineClip
import com.example.model.exportSpeed
import com.example.model.sourceOffsetMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * Cutout of a whole video clip with RVM: every frame of the clip's visible part is matted (the
 * model keeps its state from frame to frame, so the edges stay steady) and the alpha of each
 * frame is saved as a small grey JPEG. Preview and export both read these masks ([CutoutMasks]).
 *
 * Speed: the video is decoded once, front to back, by MediaCodec (no seeking), reduced to
 * [INPUT_SIDE] px while converting, and three stages run in parallel: decode -> model -> save.
 */
object CutoutVideo {

    /**
     * Longest side of the frames given to the model; also the size of the saved masks. Small on
     * purpose: the edge refiner works at this size (before it refined at 640 px, which cost a lot
     * for very little detail). [MODEL_SIDE] stays a bit smaller, so the ratio is below 1 like it
     * always was (the exact-1 path of the model was never tried on a phone).
     */
    internal const val INPUT_SIDE = 384
    /** The model works on a copy of about this size (smaller = faster, softer detail). */
    private const val MODEL_SIDE = 320
    private const val MAX_FPS = 30f
    /**
     * Masks are made at this rate at most (a 30 fps clip needs half the model runs). Preview and
     * export use the nearest mask in time, so a mask is never more than 1/30 s off.
     */
    private const val MASK_FPS = 15f

    /** [summary] is a short speed report, e.g. "3.1 s, 12 ms/frame (xnnpack)". */
    data class Result(val dir: String, val fps: Float, val startMs: Long, val frames: Int, val summary: String = "")

    private class Stats {
        val inferNs = AtomicLong()
        val frames = AtomicLong()
    }

    internal class Frame(val index: Int, val w: Int, val h: Int, val chw: FloatArray)
    private class Alpha(val index: Int, val w: Int, val h: Int, val pha: FloatArray)
    private class DecodeFailed(cause: Throwable) : Exception(cause)

    suspend fun generate(context: Context, clip: TimelineClip, onProgress: (Float) -> Unit): Result {
        val fps = minOf(sourceFps(context, clip).coerceIn(10f, MAX_FPS), MASK_FPS)
        val t0 = System.nanoTime()
        var stats = Stats()
        CutoutTrace.begin(context)
        val startMs = clip.trimStartMs
        var spanMs = (clip.durationMs * clip.exportSpeed).toLong().coerceAtLeast(100L)
        if (clip.sourceDurationMs > 0L) spanMs = minOf(spanMs, (clip.sourceDurationMs - startMs).coerceAtLeast(100L))
        val frames = (spanMs / 1000f * fps).toInt() + 2

        val dir = File(context.filesDir, "cutouts/" + UUID.randomUUID()).apply { mkdirs() }
        var ok = false
        try {
            try {
                runPipeline(context, dir, frames, stats, onProgress) { send ->
                    decodeFast(context, clip, fps, startMs, frames, send = send)
                }
            } catch (e: DecodeFailed) {
                // This phone's decoder does not give readable frames: slower, safe way (seek per frame).
                dir.listFiles()?.forEach { it.delete() }
                stats = Stats()
                runPipeline(context, dir, frames, stats, onProgress) { send ->
                    decodeSlow(context, clip, fps, startMs, frames, send = send)
                }
            }
            ok = true
            val sec = (System.nanoTime() - t0) / 1e9
            val n = stats.frames.get().coerceAtLeast(1L)
            val msPerFrame = stats.inferNs.get() / 1e6 / n
            val summary = String.format(java.util.Locale.US, "%.1f s, model %.0f ms/frame (%s)", sec, msPerFrame, RvmEngine.provider)
            android.util.Log.i("CutoutVideo", "$summary, ${stats.frames.get()} model frames, mask fps $fps")
            CutoutTrace.mark(context, "done")
            return Result(dir.absolutePath, fps, startMs, frames, summary)
        } catch (t: Throwable) {
            CutoutTrace.mark(context, "error: " + t.javaClass.simpleName)
            throw t
        } finally {
            if (!ok) dir.deleteRecursively()
        }
    }

    // ---- pipeline: decode -> model -> save, in parallel -----------------------------------

    private suspend fun runPipeline(
        context: Context,
        dir: File,
        frames: Int,
        stats: Stats,
        onProgress: (Float) -> Unit,
        produce: suspend (suspend (Frame) -> Unit) -> Unit
    ) = coroutineScope {
        val toInfer = Channel<Frame>(2)
        val toSave = Channel<Alpha>(4)

        launch(Dispatchers.Default) {
            try {
                produce { toInfer.send(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw DecodeFailed(e)
            } finally {
                toInfer.close()
            }
        }
        launch(Dispatchers.Default) {
            val stream = RvmEngine.Stream(context)
            try {
                var first = true
                for (f in toInfer) {
                    if (first) {
                        CutoutTrace.mark(context, "first frame " + f.w + "x" + f.h)
                        // The model starts with an empty memory, so its very first answer is a rough
                        // blob. Feeding the first frame a few times lets it settle before frame 0 is kept.
                        repeat(3) { stream.step(f.chw, f.w, f.h, MODEL_SIDE) }
                        CutoutTrace.mark(context, "warm-up ok")
                        first = false
                    }
                    val t = System.nanoTime()
                    val pha = stream.step(f.chw, f.w, f.h, MODEL_SIDE)
                    stats.inferNs.addAndGet(System.nanoTime() - t)
                    stats.frames.incrementAndGet()
                    toSave.send(Alpha(f.index, f.w, f.h, pha))
                }
            } finally {
                stream.close()
                toSave.close()
            }
        }
        launch(Dispatchers.IO) {
            var mask: Bitmap? = null
            var px = IntArray(0)
            var lastIdx = -1
            var bytes: ByteArray? = null
            fun write(i: Int, data: ByteArray) {
                FileOutputStream(File(dir, CutoutMasks.fileName(i))).use { it.write(data) }
            }
            for (a in toSave) {
                if (mask == null) {
                    mask = Bitmap.createBitmap(a.w, a.h, Bitmap.Config.ARGB_8888)
                    px = IntArray(a.w * a.h)
                }
                for (p in a.pha.indices) {
                    val g = (a.pha[p].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                    px[p] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
                }
                mask.setPixels(px, 0, a.w, 0, 0, a.w, a.h)
                val bos = ByteArrayOutputStream(48 * 1024)
                mask.compress(Bitmap.CompressFormat.JPEG, 92, bos)
                val data = bos.toByteArray()
                // Source frames skipped (lower mask fps) or missing: they reuse this mask.
                for (g in (lastIdx + 1) until a.index) write(g, data)
                write(a.index, data)
                lastIdx = a.index
                bytes = data
                onProgress(((a.index + 1) / frames.toFloat()).coerceAtMost(1f))
            }
            val last = bytes ?: throw IllegalStateException("No frame could be processed")
            for (g in (lastIdx + 1) until frames) write(g, last)
        }
    }

    // ---- fast decode: MediaCodec, front to back ------------------------------------------

    internal suspend fun decodeFast(
        context: Context,
        clip: TimelineClip,
        fps: Float,
        startMs: Long,
        frames: Int,
        side: Int = INPUT_SIDE,
        send: suspend (Frame) -> Unit
    ) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, clip.uri!!.toUri(), null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) throw IllegalStateException("No video track")
            ex.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val rot = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(format, null, null, 0)
            c.start()

            val startUs = startMs * 1_000L
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val info = MediaCodec.BufferInfo()
            val conv = Converter(side)
            val halfFrameUs = (500_000f / fps).toLong()
            var inputDone = false
            var done = false
            var nextIdx = 0
            var emitted = 0
            var idle = 0
            while (!done) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val out = c.dequeueOutputBuffer(info, 10_000)
                if (out >= 0) {
                    idle = 0
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    var frame: Frame? = null
                    if (info.size > 0 && info.presentationTimeUs >= startUs - halfFrameUs) {
                        val idx = ((info.presentationTimeUs - startUs) / 1_000_000f * fps).roundToInt()
                        if (idx >= frames) {
                            done = true
                        } else if (idx >= nextIdx) {
                            val img = c.getOutputImage(out) ?: throw IllegalStateException("Decoder gave no image")
                            try {
                                frame = conv.convert(img, rot, idx)
                            } finally {
                                img.close()
                            }
                            nextIdx = idx + 1
                        }
                    }
                    c.releaseOutputBuffer(out, false)
                    if (eos) done = true
                    if (frame != null) {
                        send(frame)
                        emitted++
                    }
                } else if (inputDone && out == MediaCodec.INFO_TRY_AGAIN_LATER && ++idle > 300) {
                    throw IllegalStateException("Decoder stalled")
                }
            }
            if (emitted == 0) throw IllegalStateException("No frames decoded")
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            ex.release()
        }
    }

    /**
     * YUV decoder picture -> planar RGB (0..1), turned upright and reduced to [INPUT_SIDE] px, in
     * one pass. The source position of every output pixel is worked out once per picture size and
     * kept in tables, and the three planes are copied out in bulk, so the per-frame loop is plain
     * array reads (the old loop did a ByteBuffer.get() per sample plus float maths for the position).
     */
    private class Converter(private val side: Int) {
        private data class Key(
            val srcW: Int, val srcH: Int, val left: Int, val top: Int, val rot: Int,
            val yRow: Int, val yPix: Int, val uRow: Int, val uPix: Int, val vRow: Int, val vPix: Int
        )

        private var key: Key? = null
        private var tw = 0
        private var th = 0
        private var yOff = IntArray(0)
        private var uOff = IntArray(0)
        private var vOff = IntArray(0)
        private var ya = ByteArray(0)
        private var ua = ByteArray(0)
        private var va = ByteArray(0)

        private fun copy(buf: ByteBuffer, old: ByteArray): ByteArray {
            val d = buf.duplicate()
            d.clear()
            val n = d.limit()
            val a = if (old.size >= n) old else ByteArray(n)
            d.get(a, 0, n)
            return a
        }

        fun convert(img: Image, rot: Int, index: Int): Frame {
            val crop = img.cropRect
            val srcW = crop.width()
            val srcH = crop.height()
            val yP = img.planes[0]
            val uP = img.planes[1]
            val vP = img.planes[2]
            val k = Key(srcW, srcH, crop.left, crop.top, rot,
                yP.rowStride, yP.pixelStride, uP.rowStride, uP.pixelStride, vP.rowStride, vP.pixelStride)
            if (k != key) {
                build(k)
                key = k
            }
            ya = copy(yP.buffer, ya)
            ua = copy(uP.buffer, ua)
            va = copy(vP.buffer, va)

            val wh = tw * th
            val out = FloatArray(3 * wh)
            val yo = yOff
            val uo = uOff
            val vo = vOff
            val y8 = ya
            val u8 = ua
            val v8 = va
            for (i in 0 until wh) {
                val yy = ((y8[yo[i]].toInt() and 0xFF) - 16) * 1.164f
                val cb = (u8[uo[i]].toInt() and 0xFF) - 128
                val cr = (v8[vo[i]].toInt() and 0xFF) - 128
                out[i] = ((yy + 1.793f * cr) / 255f).coerceIn(0f, 1f)
                out[wh + i] = ((yy - 0.213f * cb - 0.533f * cr) / 255f).coerceIn(0f, 1f)
                out[2 * wh + i] = ((yy + 2.112f * cb) / 255f).coerceIn(0f, 1f)
            }
            return Frame(index, tw, th, out)
        }

        private fun build(k: Key) {
            val quarter = k.rot % 180 != 0
            val uw = if (quarter) k.srcH else k.srcW
            val uh = if (quarter) k.srcW else k.srcH
            val scale = minOf(1f, side / maxOf(uw, uh).toFloat())
            tw = (uw * scale).toInt().coerceAtLeast(16)
            th = (uh * scale).toInt().coerceAtLeast(16)
            val wh = tw * th
            yOff = IntArray(wh)
            uOff = IntArray(wh)
            vOff = IntArray(wh)
            for (y in 0 until th) {
                val v = (((y + 0.5f) / th) * uh).toInt().coerceIn(0, uh - 1)
                for (x in 0 until tw) {
                    val u = (((x + 0.5f) / tw) * uw).toInt().coerceIn(0, uw - 1)
                    val sx: Int
                    val sy: Int
                    when (k.rot) {
                        90 -> { sx = v; sy = k.srcH - 1 - u }
                        180 -> { sx = k.srcW - 1 - u; sy = k.srcH - 1 - v }
                        270 -> { sx = k.srcW - 1 - v; sy = u }
                        else -> { sx = u; sy = v }
                    }
                    val px = k.left + sx
                    val py = k.top + sy
                    val i = y * tw + x
                    yOff[i] = py * k.yRow + px * k.yPix
                    uOff[i] = (py shr 1) * k.uRow + (px shr 1) * k.uPix
                    vOff[i] = (py shr 1) * k.vRow + (px shr 1) * k.vPix
                }
            }
        }
    }

    // ---- slow decode (fallback): one seek per frame ---------------------------------------

    internal suspend fun decodeSlow(
        context: Context,
        clip: TimelineClip,
        fps: Float,
        startMs: Long,
        frames: Int,
        side: Int = INPUT_SIDE,
        send: suspend (Frame) -> Unit
    ) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, clip.uri!!.toUri())
            var last: Bitmap? = null
            var w = 0
            var h = 0
            for (i in 0 until frames) {
                coroutineContext.ensureActive()
                val tUs = (startMs + (i * 1000f / fps).toLong()) * 1_000L
                val raw = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
                val frame = raw ?: last ?: throw IllegalStateException("Could not read frame $i")
                if (w == 0) {
                    val k = minOf(1f, side / maxOf(frame.width, frame.height).toFloat())
                    w = (frame.width * k).toInt().coerceAtLeast(16)
                    h = (frame.height * k).toInt().coerceAtLeast(16)
                }
                val scaled = if (frame.width == w && frame.height == h) frame
                else Bitmap.createScaledBitmap(frame, w, h, true)
                val px = IntArray(w * h)
                scaled.getPixels(px, 0, w, 0, 0, w, h)
                val wh = w * h
                val chw = FloatArray(3 * wh)
                for (p in 0 until wh) {
                    val c = px[p]
                    chw[p] = ((c shr 16) and 0xFF) / 255f
                    chw[wh + p] = ((c shr 8) and 0xFF) / 255f
                    chw[2 * wh + p] = (c and 0xFF) / 255f
                }
                if (scaled !== frame) scaled.recycle()
                if (raw != null) {
                    last?.let { if (it !== raw && !it.isRecycled) it.recycle() }
                    last = raw
                }
                send(Frame(i, w, h, chw))
            }
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /** Frame rate of the clip's video track (default 30). */
    internal fun sourceFps(context: Context, clip: TimelineClip): Float {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(context, clip.uri!!.toUri(), null)
            var fps = 30f
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") &&
                    f.containsKey(MediaFormat.KEY_FRAME_RATE)
                ) {
                    fps = try { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() } catch (e: Exception) { f.getFloat(MediaFormat.KEY_FRAME_RATE) }
                    break
                }
            }
            fps
        } catch (e: Exception) {
            30f
        } finally {
            ex.release()
        }
    }
}

/** Reads the saved masks (shared by the preview and the export). */
object CutoutMasks {

    fun fileName(index: Int) = String.format("m_%05d.jpg", index)

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt().coerceAtLeast(4 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** Source time (ms) that the clip shows at timeline time [timelineMs]. */
    fun sourceMs(clip: TimelineClip, timelineMs: Long): Long =
        clip.trimStartMs + clip.sourceOffsetMs((timelineMs - clip.startMs).coerceAtLeast(0L))

    /** Mask frame (grey picture, white = person) for source time [srcMs], or null. */
    fun bitmap(clip: TimelineClip, srcMs: Long): Bitmap? {
        val dir = clip.cutoutDir ?: return null
        if (clip.cutoutFrames <= 0 || clip.cutoutFps <= 0f) return null
        val idx = (((srcMs - clip.cutoutStartMs) * clip.cutoutFps / 1000f) + 0.5f).toInt()
            .coerceIn(0, clip.cutoutFrames - 1)
        val key = "$dir#$idx"
        cache.get(key)?.let { return it }
        val bmp = BitmapFactory.decodeFile(File(dir, fileName(idx)).absolutePath)
            ?: return null
        cache.put(key, bmp)
        return bmp
    }
}
