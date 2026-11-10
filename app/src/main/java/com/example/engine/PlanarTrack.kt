package com.example.engine

import android.content.Context
import com.example.model.PlanarResult
import com.example.model.PlanarSample
import com.example.model.TimelineClip
import com.example.model.sourceOffsetMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/** Raw JNI functions of libam_engine.so (planar tracker, cpp/engine/planar_jni.cpp). Use [PlanarTrack]. */
internal object PlanarNative {
    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeDestroy(p: Long)
    @JvmStatic external fun nativeSetGlobal(p: Long, on: Boolean)
    @JvmStatic external fun nativeStart(p: Long, gray: FloatArray, w: Int, h: Int, quad: FloatArray): Boolean
    @JvmStatic external fun nativeStep(p: Long, gray: FloatArray, w: Int, h: Int, out: FloatArray): Boolean
    @JvmStatic external fun nativePoints(p: Long, out: FloatArray): Int
}

/**
 * Planar track (the core of Mocha): follows a flat surface of a video layer - screen, poster, wall, table - with
 * perspective, from the 4 corners the user put on the picture. The video is decoded once front to back with the same
 * decoder as [MotionTrack]; the maths runs in C++ ([PlanarNative]).
 */
object PlanarTrack {

    /** The surface holds too little detail to be followed. */
    class NoTexture : Exception("Not enough detail inside the 4 corners. Move them onto a part with edges or texture.")

    /** The native library could not be loaded. */
    class Unavailable : Exception("The native engine is not available on this phone.")

    /**
     * What the tracker sees after each frame, for the live display. [quad] = the 4 corners (u, v pairs, 0..1),
     * [pts] = the corners followed (u, v pairs), [samples] = built so far (read it right away, do not keep it).
     */
    class LiveFrame(
        val srcMs: Long,
        val quad: FloatArray,
        val pts: FloatArray,
        val aspect: Float,
        val samples: List<PlanarSample>
    )

    val isAvailable: Boolean get() = DecoderPool.isAvailable

    /**
     * Follows the surface from source time [startSrcMs] to the end of the layer's visible part. [quad] = 8 floats
     * (u, v pairs, 0..1 of the upright picture): top-left, top-right, bottom-right, bottom-left.
     */
    suspend fun run(
        context: Context,
        clip: TimelineClip,
        startSrcMs: Long,
        quad: FloatArray,
        onProgress: (Float) -> Unit,
        onLive: (LiveFrame) -> Unit = {},
        shouldStop: () -> Boolean = { false },
        /** Called with the grey picture and the 4 corners (u, v) of every sample that was just added (Remove measures the light). */
        onSample: (GrayImage, FloatArray) -> Unit = { _, _ -> },
        /** Auto stabilize: follow the whole picture with a similarity (dense points), not a marked surface. */
        global: Boolean = false
    ): PlanarResult = withContext(Dispatchers.Default) {
        if (!DecoderPool.isAvailable) throw Unavailable()
        val fps = min(CutoutVideo.sourceFps(context, clip).coerceIn(10f, 60f), 60f)
        var endSrc = clip.trimStartMs + clip.sourceOffsetMs(clip.durationMs)
        if (clip.sourceDurationMs > 0L) endSrc = min(endSrc, clip.sourceDurationMs)
        val spanMs = max(1L, endSrc - startSrcMs)

        var handle = PlanarNative.nativeCreate()
        PlanarNative.nativeSetGlobal(handle, global)
        val samples = ArrayList<PlanarSample>()
        var lostAt: Long? = null
        var aspect = 1f
        var first = true
        val outQ = FloatArray(8)
        val px = FloatArray(2 * 360)
        val q0 = FloatArray(8)

        fun reset() {
            if (handle != 0L) PlanarNative.nativeDestroy(handle)
            handle = PlanarNative.nativeCreate()
            PlanarNative.nativeSetGlobal(handle, global)
            first = true
            samples.clear()
        }

        /** Handles one frame; false = stop decoding. */
        fun onFrame(ms: Long, img: GrayImage, asp: Float): Boolean {
            val w = img.w
            val h = img.h
            if (first) {
                first = false
                aspect = asp
                for (i in 0 until 4) {
                    q0[2 * i] = quad[2 * i] * w
                    q0[2 * i + 1] = quad[2 * i + 1] * h
                }
                if (!PlanarNative.nativeStart(handle, img.px, w, h, q0)) throw NoTexture()
                samples.add(PlanarSample(ms, quad.copyOf(8)))
                onSample(img, samples.last().quad)
            } else {
                val ok = PlanarNative.nativeStep(handle, img.px, w, h, outQ)
                if (!ok) {
                    lostAt = ms
                    return false
                }
                val uv = FloatArray(8)
                for (i in 0 until 4) {
                    uv[2 * i] = outQ[2 * i] / w
                    uv[2 * i + 1] = outQ[2 * i + 1] / h
                }
                samples.add(PlanarSample(ms, uv))
                onSample(img, uv)
            }
            onProgress(((ms - startSrcMs).toFloat() / spanMs).coerceIn(0f, 1f))
            val n = PlanarNative.nativePoints(handle, px)
            val pts = FloatArray(2 * n)
            for (i in 0 until n) {
                pts[2 * i] = px[2 * i] / w
                pts[2 * i + 1] = px[2 * i + 1] / h
            }
            onLive(LiveFrame(ms, samples.last().quad, pts, aspect, samples))
            return ms < endSrc && !shouldStop()
        }

        try {
            try {
                MotionTrack.decodeFast(context, clip, fps, startSrcMs, endSrc) { ms, img, asp -> onFrame(ms, img, asp) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoTexture) {
                throw e
            } catch (e: Exception) {
                if (samples.isNotEmpty()) throw e
                // This phone's decoder gives no readable frames: slower, safe way (seek per frame).
                reset()
                MotionTrack.decodeSlow(context, clip, fps, startSrcMs, endSrc) { ms, img, asp -> onFrame(ms, img, asp) }
            }
        } finally {
            if (handle != 0L) PlanarNative.nativeDestroy(handle)
            handle = 0L
        }
        if (samples.isEmpty()) throw IllegalStateException("No frames could be read")
        onProgress(1f)
        PlanarResult(clip.id, samples.toList(), aspect, lostAt)
    }
}

/**
 * Corner pin at draw time: where the tracked surface of a video is on the canvas, for the preview and the export alike.
 * The surface corners (picture 0..1) go through exactly the matrix the renderer draws the video with (fit, crop, flip,
 * the layer's scale / rotation / position / 3D), so the pinned layer lands on the surface.
 */
internal object CornerPinRuntime {

    /**
     * The 8 canvas-pixel corners (TL, TR, BR, BL; origin top-left) for [pin] at project time [timeMs]. [base] = the pose of
     * the tracked video at that time. Null when the surface cannot be placed (the layer is then not drawn).
     */
    fun quadOnCanvas(pin: com.example.model.CornerPin, timeMs: Long, base: LayerParams, canvasW: Int, canvasH: Int): FloatArray? {
        if (!DecoderPool.isAvailable) return null
        val i = pin.indexAt(timeMs)
        if (i < 0) return null
        val uv = FloatArray(8)
        System.arraycopy(pin.quad, i * 8, uv, 0, 8)
        val params = FloatArray(45)
        // Only the shape of the picture matters for the fit (width : height), not its pixel size.
        params[16] = (pin.aspect.coerceIn(0.05f, 20f) * 1000f)
        params[17] = 1000f
        params[19] = base.offsetX
        params[20] = base.offsetY
        params[21] = base.scaleX
        params[22] = base.scaleY
        params[23] = base.rotationDeg
        params[24] = 1f
        params[25] = base.cropX
        params[26] = base.cropY
        params[27] = base.cropW
        params[28] = base.cropH
        params[29] = base.rotX
        params[30] = base.rotY
        params[31] = base.pivotX
        params[32] = base.pivotY
        params[33] = base.baseX
        params[34] = base.baseY
        params[35] = base.perspD
        val out = FloatArray(8)
        return if (RendererNative.nativeQuadToCanvas(params, canvasW, canvasH, uv, out)) out else null
    }
}
