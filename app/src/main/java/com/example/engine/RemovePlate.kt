package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.opengl.GLES30
import com.example.model.CornerPinApply
import com.example.model.PlanarRemove
import com.example.model.PlanarResult
import com.example.model.PlanarSample
import com.example.model.TimelineClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Raw JNI functions of libam_engine.so (clean plate, cpp/engine/remove_jni.cpp). Use [RemoveBuilder]. */
internal object RemoveNative {
    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeDestroy(p: Long)
    @JvmStatic external fun nativeSetMask(p: Long, quad: FloatArray, mask: ByteArray, mw: Int, mh: Int, picW: Int, picH: Int, maxSide: Int): Boolean
    @JvmStatic external fun nativePlateInfo(p: Long, size: IntArray, dom: FloatArray)
    @JvmStatic external fun nativeRingLuma(p: Long, gray: FloatArray, w: Int, h: Int, quad: FloatArray): Float
    @JvmStatic external fun nativeAddFrame(p: Long, argb: IntArray, w: Int, h: Int, quad: FloatArray): Boolean
    @JvmStatic external fun nativeBuild(p: Long, useFrames: Boolean, out: ByteArray): Boolean
    @JvmStatic external fun nativeSubQuad(quad: FloatArray, dom: FloatArray, out: FloatArray): Boolean
}

/** The patch picture on disk: width, height (ints), then width * height * 4 bytes, premultiplied RGBA, first row = top. */
internal object RemovePatchFile {
    fun write(file: File, w: Int, h: Int, rgba: ByteArray) {
        file.parentFile?.mkdirs()
        DataOutputStream(file.outputStream().buffered()).use { o ->
            o.writeInt(w)
            o.writeInt(h)
            o.write(rgba, 0, w * h * 4)
        }
    }

    class Patch(val w: Int, val h: Int, val rgba: ByteArray)

    fun read(path: String): Patch? = try {
        DataInputStream(File(path).inputStream().buffered()).use { i ->
            val w = i.readInt()
            val h = i.readInt()
            if (w !in 1..4096 || h !in 1..4096) null
            else {
                val b = ByteArray(w * h * 4)
                i.readFully(b)
                Patch(w, h, b)
            }
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Remove: makes the clean patch of a video layer. The surface (4 corners) is planar-tracked ([PlanarTrack]); a few of the
 * tracked frames are read in colour, straightened and combined in C++ into the patch ([RemoveNative]); a brightness factor
 * per frame (the ring around the flaw) keeps the patch matching the light of the video.
 */
object RemoveBuilder {

    /** The tool cannot do it (nothing painted, frame cannot be read, ...). The message is shown to the user. */
    class Failed(message: String) : Exception(message)

    val isAvailable: Boolean get() = DecoderPool.isAvailable

    /** Longest side (pixels) of the picture the painted area is made on. */
    const val MASK_SIDE = 512
    private const val MAX_FRAMES = 9
    private const val PLATE_SIDE = 768
    private const val FRAME_SIDE = 1280

    /**
     * [quadUv] = 8 floats, the 4 corners on the picture at [startSrcMs]; [mask] = [maskW] x [maskH] bytes, the painted area on
     * that picture (255 = remove). [useFrames] = also take what other frames show; false = only fill from the surrounding.
     * [onProgress] gets 0..1 and a short label. The track stops when [shouldStop] says so; what was tracked is used.
     */
    suspend fun run(
        context: Context,
        clip: TimelineClip,
        startSrcMs: Long,
        quadUv: FloatArray,
        mask: ByteArray,
        maskW: Int,
        maskH: Int,
        useFrames: Boolean,
        onProgress: (Float, String) -> Unit,
        onLive: (PlanarTrack.LiveFrame) -> Unit = {},
        shouldStop: () -> Boolean = { false }
    ): PlanarRemove = withContext(Dispatchers.Default) {
        if (!isAvailable) throw PlanarTrack.Unavailable()
        val uriStr = clip.uri ?: throw Failed("This layer has no video")
        val retriever = MediaMetadataRetriever()
        var handle = 0L
        try {
            retriever.setDataSource(context, Uri.parse(uriStr))
            // size of the colour pictures (upright): the plate has about the same pixel scale
            val vw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val vh = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (vw <= 0 || vh <= 0) throw Failed("Could not read this video")
            val uw = if (rot == 90 || rot == 270) vh else vw
            val uh = if (rot == 90 || rot == 270) vw else vh
            val sc = min(1f, FRAME_SIDE.toFloat() / max(uw, uh))
            val fw = max(2, (uw * sc).roundToInt())
            val fh = max(2, (uh * sc).roundToInt())

            handle = RemoveNative.nativeCreate()
            if (handle == 0L) throw PlanarTrack.Unavailable()
            if (!RemoveNative.nativeSetMask(handle, quadUv, mask, maskW, maskH, fw, fh, PLATE_SIDE)) {
                throw Failed("Paint over the thing to remove, inside the 4 corners")
            }
            val h = handle

            // 1) follow the surface; on every frame also measure the light around the flaw
            val lumas = ArrayList<Float>()
            val res = PlanarTrack.run(
                context, clip, startSrcMs, quadUv,
                onProgress = { onProgress(it * 0.7f, "Tracking the surface…") },
                onLive = onLive,
                shouldStop = shouldStop,
                onSample = { img, q -> lumas.add(RemoveNative.nativeRingLuma(h, img.px, img.w, img.h, q)) }
            )
            val samples = res.samples
            val n = samples.size
            if (n == 0) throw Failed("Nothing could be tracked")

            // 2) a few tracked frames in colour, straightened (the first one is the reference)
            val picks = ArrayList<Int>()
            if (useFrames && n >= 3) {
                val k = min(MAX_FRAMES, n)
                for (i in 0 until k) {
                    val idx = (i.toLong() * (n - 1) / (k - 1)).toInt()
                    if (picks.isEmpty() || picks.last() != idx) picks.add(idx)
                }
            } else {
                picks.add(0)
            }
            var added = 0
            for ((j, idx) in picks.withIndex()) {
                ensureActive()
                onProgress(0.7f + 0.25f * j / picks.size, "Reading frames…")
                val s = samples[idx]
                val bmp = frameAt(retriever, s.srcMs, fw, fh)
                if (bmp == null) {
                    if (idx == 0) throw Failed("Could not read the video frame")
                    continue
                }
                val bw = bmp.width
                val bh = bmp.height
                val px = IntArray(bw * bh)
                bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
                bmp.recycle()
                if (RemoveNative.nativeAddFrame(handle, px, bw, bh, s.quad)) added++
                else if (idx == 0) throw Failed("The 4 corners are not a proper surface")
            }
            if (added == 0) throw Failed("Could not read the video frames")

            // 3) the patch
            onProgress(0.96f, "Making the clean patch…")
            val size = IntArray(2)
            val dom = FloatArray(4)
            RemoveNative.nativePlateInfo(handle, size, dom)
            val pw = size[0]
            val ph = size[1]
            val rgba = ByteArray(pw * ph * 4)
            val frames = added >= 3 && useFrames
            if (!RemoveNative.nativeBuild(handle, frames, rgba)) throw Failed("Could not make the clean patch")
            val dir = File(context.filesDir, "remove")
            val file = File(dir, clip.id + "_" + System.currentTimeMillis() + ".rgba")
            RemovePatchFile.write(file, pw, ph, rgba)

            // 4) where the patch goes on every tracked frame, and how bright
            val sub = ArrayList<PlanarSample>(n)
            var last = FloatArray(8)
            var haveLast = false
            for (s in samples) {
                val o = FloatArray(8)
                if (RemoveNative.nativeSubQuad(s.quad, dom, o)) {
                    last = o
                    haveLast = true
                    sub.add(PlanarSample(s.srcMs, o))
                } else if (haveLast) {
                    sub.add(PlanarSample(s.srcMs, last.copyOf()))
                } else {
                    throw Failed("The 4 corners are not a proper surface")
                }
            }
            val pin = CornerPinApply.from(clip, PlanarResult(clip.id, sub, res.aspect, res.lostAtSrcMs))
                ?: throw Failed("Nothing could be tracked")
            val gains = gainsFrom(lumas, pin.size)
            onProgress(1f, "Done")
            PlanarRemove(pin, gains, file.absolutePath, frames)
        } finally {
            if (handle != 0L) RemoveNative.nativeDestroy(handle)
            runCatching { retriever.release() }
        }
    }

    /** Light of every tracked frame relative to the first one, smoothed (the measure is noisy), kept in a sane range. */
    internal fun gainsFrom(lumas: List<Float>, count: Int): FloatArray {
        val g = FloatArray(count) { 1f }
        if (lumas.size != count || count == 0) return g
        val l0 = lumas[0]
        if (l0 < 1f) return g
        val raw = FloatArray(count) { i ->
            val l = lumas[i]
            if (l < 1f) 1f else (l / l0).coerceIn(0.5f, 2f)
        }
        val r = 2
        for (i in 0 until count) {
            var s = 0f
            var c = 0
            for (k in max(0, i - r)..min(count - 1, i + r)) {
                s += raw[k]
                c++
            }
            g[i] = s / c
        }
        return g
    }

    private fun frameAt(r: MediaMetadataRetriever, srcMs: Long, w: Int, h: Int): Bitmap? {
        val us = srcMs * 1000L
        val scaled = try {
            r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
        } catch (e: Exception) {
            null
        }
        if (scaled != null) return scaled
        return try {
            r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
        } catch (e: Exception) {
            null
        }
    }
}

/** One patch picture as a GL texture. */
internal class PatchTex(val tex: Int, val w: Int, val h: Int)

/** GL textures of the patches (preview and export): loaded on first use, freed when the layer or the patch goes away. */
internal class RemovePatchCache {
    private class Entry(val path: String, val patch: PatchTex?)

    private val map = HashMap<String, Entry>()

    /** The patch texture of [clipId]; null when the file cannot be read (tried once per file). Needs the GL thread. */
    fun get(r: NativeRenderer, clipId: String, path: String): PatchTex? {
        val e = map[clipId]
        if (e != null && e.path == path) return e.patch
        if (e?.patch != null) r.deleteTexture(e.patch.tex)
        val data = RemovePatchFile.read(path)
        var patch: PatchTex? = null
        if (data != null) {
            val tex = r.createTexture2d()
            val buf = ByteBuffer.allocateDirect(data.rgba.size).order(ByteOrder.nativeOrder())
            buf.put(data.rgba).position(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, data.w, data.h, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf
            )
            patch = PatchTex(tex, data.w, data.h)
        }
        map[clipId] = Entry(path, patch)
        return patch
    }

    /** Frees the textures of every layer not in [keep]. */
    fun retain(r: NativeRenderer, keep: Set<String>) {
        if (map.isEmpty()) return
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in keep) {
                e.value.patch?.let { p -> r.deleteTexture(p.tex) }
                it.remove()
            }
        }
    }

    fun release(r: NativeRenderer?) {
        if (r != null) map.values.forEach { e -> e.patch?.let { p -> r.deleteTexture(p.tex) } }
        map.clear()
    }
}

/** Remove at draw time: the patch goes on the moving surface right after the layer's own picture. Preview and export alike. */
internal object RemoveRuntime {
    /** Texture matrix of a plain 2D texture whose first row is the TOP of the picture. */
    private val FLIP_V = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, -1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 1f, 0f, 1f
    )

    /**
     * Draws the patch of [clip] at project time [timeMs]. [base] = the pose the layer itself was just drawn with (the patch
     * follows it: scale, rotation, position, crop, 3D). [blendId] = the layer's blend mode (same as the layer; a matte has
     * nothing to patch).
     */
    fun draw(
        r: NativeRenderer, cache: RemovePatchCache, clip: TimelineClip, base: LayerParams, timeMs: Long,
        w: Int, h: Int, blendId: Int, canvasRgb: FloatArray
    ) {
        val rm = clip.planarRemove ?: return
        if (clip.cornerPin != null) return   // a layer that is itself pinned is not drawn by its own pose
        if (blendId in 1..4) return
        if (!rm.coversAt(timeMs)) return   // not tracked here: the video is shown as it is
        val tex = cache.get(r, clip.id, rm.patchPath) ?: return
        val quad = CornerPinRuntime.quadOnCanvas(rm.pin, timeMs, base, w, h) ?: return
        val p = LayerParams()
        p.opacity = base.opacity
        p.pin = quad
        p.gain = rm.gainAt(timeMs)
        if (blendId == 0) r.drawLayer(tex.tex, false, FLIP_V, tex.w, tex.h, 0, p)
        else r.drawLayerBlended(tex.tex, false, FLIP_V, tex.w, tex.h, 0, p, blendId, canvasRgb)
    }
}
