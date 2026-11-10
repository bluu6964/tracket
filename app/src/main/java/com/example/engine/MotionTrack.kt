package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import com.example.model.TimelineClip
import com.example.model.TrackResult
import com.example.model.TrackSample
import com.example.model.sourceOffsetMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/**
 * Motion tracking of a video layer: the video is decoded once, front to back (MediaCodec, no
 * seeking), every frame is reduced to a small grey picture and handed to [CircleTracker].
 * Only the brightness (Y) plane is read, so there is no colour conversion and no model.
 */
object MotionTrack {

    /** Longest side of the grey pictures the tracker works on. */
    private const val TRACK_SIDE = 480
    /**
     * Highest frame rate that is tracked frame for frame. A follower is held with its video frame, so
     * every video frame needs its own sample (at 60 fps with a 30 fps cap every second frame would be
     * missing and the layer would trail the object by one frame half of the time).
     */
    private const val MAX_FPS = 60f

    /**
     * What the tracker sees after each frame, for the live display. ([u], [v]) is the circle centre
     * and [rNorm] its radius (fraction of the picture width); [pts] are the followed corners as
     * u, v, u, v ... (0..1). [samples] is the list built so far: read it right away (do not keep it).
     */
    class LiveFrame(
        val srcMs: Long,
        val u: Float,
        val v: Float,
        val rNorm: Float,
        val pts: FloatArray,
        val aspect: Float,
        val samples: List<TrackSample>
    )

    /** The circle holds too little texture to be followed. */
    class NoTexture : Exception("Not enough detail inside the circle. Move it onto something with edges or texture.")

    /** Width / height of the video picture as it is shown (the video's own rotation applied), 0 if unknown. */
    fun pictureAspect(context: Context, uri: String): Float {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri.toUri())
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 0f
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 0f
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (w <= 0f || h <= 0f) 0f else if (rot % 180 != 0) h / w else w / h
        } catch (e: Exception) {
            0f
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /**
     * Follows the circle from source time [startSrcMs] to the end of the layer's visible part.
     * ([u], [v]) is the circle centre in the picture (0..1) and [rNorm] its radius as a fraction of
     * the picture width. Throws [NoTexture] when there is nothing to follow in the circle.
     */
    suspend fun run(
        context: Context,
        clip: TimelineClip,
        startSrcMs: Long,
        u: Float,
        v: Float,
        rNorm: Float,
        onProgress: (Float) -> Unit,
        /** Called after every frame (on a background thread). */
        onLive: (LiveFrame) -> Unit = {},
        /** When this turns true the track stops and keeps what it has so far. */
        shouldStop: () -> Boolean = { false }
    ): TrackResult = withContext(Dispatchers.Default) {
        val fps = min(CutoutVideo.sourceFps(context, clip).coerceIn(10f, MAX_FPS), MAX_FPS)
        var endSrc = clip.trimStartMs + clip.sourceOffsetMs(clip.durationMs)
        if (clip.sourceDurationMs > 0L) endSrc = min(endSrc, clip.sourceDurationMs)
        val spanMs = max(1L, endSrc - startSrcMs)

        var tracker = CircleTracker()
        val samples = ArrayList<TrackSample>()
        var lostAt: Long? = null
        var aspect = 1f
        var first = true

        /** Handles one frame; false = stop decoding. */
        fun onFrame(ms: Long, img: GrayImage, asp: Float): Boolean {
            if (first) {
                first = false
                aspect = asp
                if (!tracker.start(img, u * img.w, v * img.h, rNorm * img.w)) throw NoTexture()
                samples.add(TrackSample(ms, u, v, 0f, 1f))
            } else if (tracker.step(img)) {
                samples.add(TrackSample(ms, tracker.cx / img.w, tracker.cy / img.h, tracker.rotationDeg, tracker.scaleTotal))
            } else {
                lostAt = ms
                return false
            }
            onProgress(((ms - startSrcMs).toFloat() / spanMs).coerceIn(0f, 1f))
            val pw = img.w.toFloat()
            val ph = img.h.toFloat()
            val raw = tracker.copyPoints()
            val pts = FloatArray(raw.size) { if (it % 2 == 0) raw[it] / pw else raw[it] / ph }
            onLive(LiveFrame(ms, tracker.cx / pw, tracker.cy / ph, tracker.radius / pw, pts, aspect, samples))
            return ms < endSrc && !shouldStop()
        }

        try {
            decodeFast(context, clip, fps, startSrcMs, endSrc) { ms, img, asp -> onFrame(ms, img, asp) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoTexture) {
            throw e
        } catch (e: Exception) {
            if (samples.isNotEmpty()) throw e
            // This phone's decoder gives no readable frames: slower, safe way (seek per frame).
            tracker = CircleTracker()
            first = true
            decodeSlow(context, clip, fps, startSrcMs, endSrc) { ms, img, asp -> onFrame(ms, img, asp) }
        }
        if (samples.isEmpty()) throw IllegalStateException("No frames could be read")
        onProgress(1f)
        TrackResult(clip.id, samples, aspect, lostAt)
    }

    // ---- fast decode: MediaCodec, front to back -------------------------------------------

    internal suspend fun decodeFast(
        context: Context,
        clip: TimelineClip,
        fps: Float,
        startMs: Long,
        endMs: Long,
        send: (Long, GrayImage, Float) -> Boolean
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
            val endUs = endMs * 1_000L
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val info = MediaCodec.BufferInfo()
            val conv = GrayConverter()
            val halfFrameUs = (500_000f / fps).toLong()
            var inputDone = false
            var done = false
            val minGapUs = (900_000f / MAX_FPS).toLong()
            var lastUs = -1L
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
                    var gray: GrayImage? = null
                    var ptsMs = 0L
                    if (info.size > 0 && info.presentationTimeUs >= startUs - halfFrameUs) {
                        if (info.presentationTimeUs > endUs + halfFrameUs) {
                            done = true
                        } else {
                            // Every decoded frame is tracked (the follower is held with its video frame, so
                            // a missing sample would make it trail by a frame). Only a source faster than
                            // MAX_FPS is thinned, by time, so a wrong frame-rate tag cannot drop frames.
                            if (lastUs < 0L || info.presentationTimeUs - lastUs >= minGapUs) {
                                val img = c.getOutputImage(out) ?: throw IllegalStateException("Decoder gave no image")
                                try {
                                    gray = conv.convert(img, rot)
                                } finally {
                                    img.close()
                                }
                                ptsMs = max(startMs, info.presentationTimeUs / 1_000L)
                                lastUs = info.presentationTimeUs
                            }
                        }
                    }
                    c.releaseOutputBuffer(out, false)
                    if (eos) done = true
                    if (gray != null) {
                        emitted++
                        if (!send(ptsMs, gray, conv.aspect)) done = true
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
     * Decoder picture -> small upright grey picture. Only the Y plane is read; every output pixel
     * is the average of a small block of source pixels (plain sub-sampling would alias and make
     * the tracking jitter). Source positions are worked out once per picture size.
     */
    private class GrayConverter {
        private data class Key(
            val srcW: Int, val srcH: Int, val left: Int, val top: Int, val rot: Int,
            val yRow: Int, val yPix: Int
        )

        private var key: Key? = null
        private var tw = 0
        private var th = 0
        private var bs = 1
        private var yRow = 0
        private var yPix = 1
        private var off = IntArray(0)
        private var ya = ByteArray(0)

        /** Width / height of the pictures made (after the rotation). */
        var aspect = 1f
            private set

        private fun copy(buf: ByteBuffer, old: ByteArray): ByteArray {
            val d = buf.duplicate()
            d.clear()
            val n = d.limit()
            val a = if (old.size >= n) old else ByteArray(n)
            d.get(a, 0, n)
            return a
        }

        fun convert(img: Image, rot: Int): GrayImage {
            val crop = img.cropRect
            val yP = img.planes[0]
            val k = Key(crop.width(), crop.height(), crop.left, crop.top, rot, yP.rowStride, yP.pixelStride)
            if (k != key) {
                build(k)
                key = k
            }
            ya = copy(yP.buffer, ya)
            val wh = tw * th
            val out = FloatArray(wh)
            val y8 = ya
            val area = (bs * bs).toFloat()
            for (i in 0 until wh) {
                val o = off[i]
                var sum = 0
                for (dy in 0 until bs) {
                    val row = o + dy * yRow
                    for (dx in 0 until bs) sum += y8[row + dx * yPix].toInt() and 0xFF
                }
                out[i] = sum / area
            }
            return GrayImage(tw, th, out)
        }

        private fun build(k: Key) {
            val quarter = k.rot % 180 != 0
            val uw = if (quarter) k.srcH else k.srcW
            val uh = if (quarter) k.srcW else k.srcH
            val scale = min(1f, TRACK_SIDE / max(uw, uh).toFloat())
            tw = (uw * scale).toInt().coerceAtLeast(16)
            th = (uh * scale).toInt().coerceAtLeast(16)
            bs = max(1, (1f / scale).toInt()).coerceAtMost(min(k.srcW, k.srcH))
            yRow = k.yRow
            yPix = k.yPix
            aspect = uw.toFloat() / uh
            off = IntArray(tw * th)
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
                    val bx = (k.left + sx - bs / 2).coerceIn(k.left, k.left + k.srcW - bs)
                    val by = (k.top + sy - bs / 2).coerceIn(k.top, k.top + k.srcH - bs)
                    off[y * tw + x] = by * k.yRow + bx * k.yPix
                }
            }
        }
    }

    // ---- slow decode (fallback): one seek per frame ----------------------------------------

    internal suspend fun decodeSlow(
        context: Context,
        clip: TimelineClip,
        fps: Float,
        startMs: Long,
        endMs: Long,
        send: (Long, GrayImage, Float) -> Boolean
    ) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, clip.uri!!.toUri())
            var last: Bitmap? = null
            var w = 0
            var h = 0
            var i = 0
            while (true) {
                coroutineContext.ensureActive()
                val tMs = startMs + (i * 1000f / fps).toLong()
                if (tMs > endMs) break
                val raw = retriever.getFrameAtTime(tMs * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                val frame = raw ?: last ?: throw IllegalStateException("Could not read frame $i")
                if (w == 0) {
                    val k = min(1f, TRACK_SIDE / max(frame.width, frame.height).toFloat())
                    w = (frame.width * k).toInt().coerceAtLeast(16)
                    h = (frame.height * k).toInt().coerceAtLeast(16)
                }
                val scaled = if (frame.width == w && frame.height == h) frame
                else Bitmap.createScaledBitmap(frame, w, h, true)
                val px = IntArray(w * h)
                scaled.getPixels(px, 0, w, 0, 0, w, h)
                val grey = FloatArray(w * h)
                for (p in px.indices) {
                    val c = px[p]
                    grey[p] = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
                }
                if (scaled !== frame) scaled.recycle()
                if (raw != null) {
                    last?.let { if (it !== raw && !it.isRecycled) it.recycle() }
                    last = raw
                }
                if (!send(tMs, GrayImage(w, h, grey), w.toFloat() / h)) break
                i++
            }
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
}
