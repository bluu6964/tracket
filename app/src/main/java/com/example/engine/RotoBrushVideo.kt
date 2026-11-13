package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import com.example.model.TimelineClip
import com.example.model.exportSpeed
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Roto Brush for a whole video (After Effects style): the user brushes the object on one or more frames
 * ("keyframes"), EdgeSAM finds the object there, and this carries it frame by frame to the rest of the clip:
 * forward from every keyframe up to the next one, and backward from the first keyframe to the start.
 *
 * The result is written in the SAME folder format as the person cutout ([CutoutVideo]: m_00000.jpg, white =
 * object), so the preview and the C++ export already know how to use it (clip.cutoutDir / cutoutFps / ...).
 *
 * How the object is followed: for each new frame the previous mask gives (1) a box around the object, (2) a few
 * points deep inside it and (3) its low-resolution logits as a hint; EdgeSAM answers with the candidate that
 * overlaps the previous mask most. When the answer is clearly wrong (object vanished / filled the picture) the
 * previous mask is kept, so one bad frame does not break the rest.
 */
object RotoBrushVideo {

    /** Masks are made at this rate at most (a 30 fps clip uses every 3rd frame; the preview picks the nearest). */
    const val MASK_FPS = 10f

    /**
     * Longest side of the saved masks. The decoder's own detail caps at its 256x256 logits, but the
     * preview / export upscale the saved mask to the full frame: at 512 a 1080p frame stretched every
     * edge over ~4 px, at 1024 over ~2 px — the saved size is the edge sharpness the user actually sees.
     */
    const val MASK_SIDE = 1024

    /** A brush point, normalised to 0..1 of the picture (so it does not depend on the size it was drawn at). */
    class NPt(val x: Float, val y: Float, val positive: Boolean)

    /** The brush of one frame: [srcMs] = time in the SOURCE video. */
    class Key(val srcMs: Long, val points: List<NPt>)

    fun maskFps(context: Context, clip: TimelineClip): Float =
        minOf(CutoutVideo.sourceFps(context, clip).coerceIn(10f, 30f), MASK_FPS)

    fun frameCount(clip: TimelineClip, fps: Float): Int {
        val startMs = clip.trimStartMs
        var spanMs = (clip.durationMs * clip.exportSpeed).toLong().coerceAtLeast(100L)
        if (clip.sourceDurationMs > 0L) spanMs = minOf(spanMs, (clip.sourceDurationMs - startMs).coerceAtLeast(100L))
        return RotoFrames.frameCount(spanMs, fps)
    }

    private class FrameReader(context: Context, clip: TimelineClip) {
        private val r = MediaMetadataRetriever()
        private var last: Bitmap? = null

        init {
            r.setDataSource(context, clip.uri!!.toUri())
        }

        /** Frame at source time [ms], longest side at most 1024. */
        fun at(ms: Long): Bitmap {
            val raw = r.getFrameAtTime(ms.coerceAtLeast(0L) * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST)
            if (raw == null) return last ?: throw IllegalStateException("Could not read the video frame")
            val longest = maxOf(raw.width, raw.height)
            // Remember the SCALED picture, not the raw one: a raw 4K frame kept for the whole run
            // pins ~33 MB for nothing (the raw is only ever used through its scaled form).
            last = if (longest <= EdgeSamEngine.SIDE) raw
            else Bitmap.createScaledBitmap(
                raw,
                (raw.width * EdgeSamEngine.SIDE / longest.toFloat()).toInt().coerceAtLeast(1),
                (raw.height * EdgeSamEngine.SIDE / longest.toFloat()).toInt().coerceAtLeast(1),
                true
            )
            return last!!
        }

        fun close() {
            try { r.release() } catch (_: Exception) {}
        }
    }

    suspend fun generate(
        context: Context,
        clip: TimelineClip,
        keys: List<Key>,
        onProgress: (Float) -> Unit
    ): CutoutVideo.Result {
        require(keys.isNotEmpty()) { "Brush the object on at least one frame first" }
        val t0 = System.nanoTime()
        val fps = maskFps(context, clip)
        val startMs = clip.trimStartMs
        val frames = frameCount(clip, fps)
        fun frameMs(i: Int): Long = RotoFrames.frameMs(startMs, i, fps)
        fun indexOf(srcMs: Long): Int = RotoFrames.indexOf(startMs, srcMs, fps, frames)

        // One brush per frame index (a later one on the same frame replaces the earlier one).
        val byFrame = java.util.TreeMap<Int, Key>()
        for (k in keys) byFrame[indexOf(k.srcMs)] = k
        val keyFrames = byFrame.keys.toList()

        val dir = File(context.filesDir, "cutouts/" + UUID.randomUUID()).apply { mkdirs() }
        val reader = FrameReader(context, clip)
        var ok = false
        var done = 0
        try {
            var mw = 0
            var mh = 0
            fun report() { onProgress((done / frames.toFloat()).coerceIn(0f, 1f)) }

            fun saveMask(index: Int, alpha: FloatArray) {
                // Saved masks get the small-defect cleanup (tiny enclosed holes filled, tiny specks
                // dropped); the mask chained into the next frame's prompt stays the raw one.
                val shown = MaskCleanup.clean(alpha, mw, mh)
                val px = IntArray(mw * mh)
                for (p in px.indices) {
                    val g = (shown[p].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                    px[p] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
                }
                val bmp = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
                bmp.setPixels(px, 0, mw, 0, 0, mw, mh)
                val bos = ByteArrayOutputStream(48 * 1024)
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, bos)
                bmp.recycle()
                FileOutputStream(File(dir, CutoutMasks.fileName(index))).use { it.write(bos.toByteArray()) }
                done++
                report()
            }

            /** Mask of the keyframe itself, from the user's brush. */
            suspend fun keyMask(index: Int, key: Key): Pair<FloatArray, FloatArray> {
                coroutineContext.ensureActive()
                val frame = reader.at(frameMs(index))
                if (mw == 0) {
                    val kk = MASK_SIDE / maxOf(frame.width, frame.height).toFloat()
                    mw = (frame.width * kk).toInt().coerceAtLeast(16)
                    mh = (frame.height * kk).toInt().coerceAtLeast(16)
                }
                val emb = EdgeSamEngine.encode(context, frame)
                val pts = key.points.map { EdgeSamEngine.Pt(it.x * frame.width, it.y * frame.height, it.positive) }
                val m = EdgeSamEngine.decode(context, emb, pts, null, null, null, mw, mh)
                saveMask(index, m.alpha)
                return Pair(m.alpha, m.logits)
            }

            /** Carries the previous mask to the frame [index]. */
            suspend fun follow(index: Int, prevAlpha: FloatArray, prevLogits: FloatArray): Pair<FloatArray, FloatArray> {
                coroutineContext.ensureActive()
                val frame = reader.at(frameMs(index))
                val emb = EdgeSamEngine.encode(context, frame)
                val box = EdgeSamEngine.boxOf(prevAlpha, mw, mh, frame.width, frame.height, 0.10f)
                    ?: run {
                        saveMask(index, prevAlpha)
                        return Pair(prevAlpha, prevLogits)
                    }
                val inside = EdgeSamEngine.insidePoints(prevAlpha, mw, mh, frame.width, frame.height, 3)
                val m = EdgeSamEngine.decode(context, emb, inside, box, prevLogits, prevAlpha, mw, mh)
                val before = areaOf(prevAlpha)
                val after = areaOf(m.alpha)
                // Object vanished or grew wildly: keep the previous mask for this frame.
                val lost = before > 0 && (after < before * 0.25f || after > before * 3f)
                if (lost) {
                    saveMask(index, prevAlpha)
                    return Pair(prevAlpha, prevLogits)
                }
                saveMask(index, m.alpha)
                return Pair(m.alpha, m.logits)
            }

            // Forward: from every keyframe to just before the next one (or the end).
            val keyResults = HashMap<Int, Pair<FloatArray, FloatArray>>()
            for ((n, kf) in keyFrames.withIndex()) {
                var cur = keyMask(kf, byFrame.getValue(kf))
                keyResults[kf] = cur
                val stop = if (n + 1 < keyFrames.size) keyFrames[n + 1] else frames
                for (i in (kf + 1) until stop) cur = follow(i, cur.first, cur.second)
            }
            // Backward: from the first keyframe to the start of the clip.
            var back = keyResults.getValue(keyFrames.first())
            for (i in (keyFrames.first() - 1) downTo 0) back = follow(i, back.first, back.second)

            ok = true
            val sec = (System.nanoTime() - t0) / 1e9
            val summary = String.format(java.util.Locale.US, "%.0f s, %d frames, %d keyframe(s)", sec, frames, keyFrames.size)
            return CutoutVideo.Result(dir.absolutePath, fps, startMs, frames, summary)
        } finally {
            reader.close()
            if (!ok) dir.deleteRecursively()
        }
    }

    private fun areaOf(alpha: FloatArray): Int {
        var n = 0
        for (v in alpha) if (v > 0.5f) n++
        return n
    }
}
