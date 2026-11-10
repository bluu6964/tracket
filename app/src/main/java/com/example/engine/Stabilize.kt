package com.example.engine

import android.content.Context
import com.example.model.CornerPin
import com.example.model.CornerPinApply
import com.example.model.PlanarResult
import com.example.model.PlanarSample
import com.example.model.TimelineClip

/** Raw JNI function of libam_engine.so (cpp/engine/stabilize_jni.cpp). Use [StabilizeBuilder]. */
internal object StabilizeNative {
    @JvmStatic external fun nativeQuads(quads: FloatArray, n: Int, autoZoom: Boolean, fixedZoom: Float, out: FloatArray): Float

    /** Auto stabilize: camera path smoothed (Gaussian of [sigmaFrames] frames, <= 0 = tripod). */
    @JvmStatic external fun nativeSmooth(
        quads: FloatArray, n: Int, aspect: Float, sigmaFrames: Float, autoZoom: Boolean, fixedZoom: Float, out: FloatArray
    ): Float
}

/**
 * Stabilize (Mocha-style): the surface the user marked with 4 corners is held still. The surface is planar-tracked, then on
 * every frame the picture is moved (perspective included) so the corners return to where they were on the first frame, and
 * zoomed a little so no empty edge shows. The result is a corner pin of the layer on ITSELF ([CornerPin.videoId] = its own
 * id), drawn by the same code as every corner pin, in the preview and in the export.
 */
object StabilizeBuilder {

    class Failed(message: String) : Exception(message)

    /** True when [pin] is a stabilize (the layer pinned on itself) and not a follower of another video. */
    fun isStabilize(clip: TimelineClip): Boolean = clip.cornerPin?.videoId == clip.id

    /** The whole picture (a little inside its edge) as the area to follow: no points needed. */
    val FULL_FRAME_QUAD = floatArrayOf(0.03f, 0.03f, 0.97f, 0.03f, 0.97f, 0.97f, 0.03f, 0.97f)

    /** Slider 0..1 -> how many seconds of camera path are averaged. 1 = tripod (the camera is held on the first frame). */
    fun sigmaSeconds(strength: Float): Float =
        if (strength >= 0.995f) 0f else 0.25f * Math.pow(32.0, strength.coerceIn(0f, 1f).toDouble()).toFloat()

    /**
     * [smoothStrength] null = Lock surface (Mocha-style, the 4 corners are held exactly, [quadUv] = the marked corners).
     * Otherwise Auto: [quadUv] = [FULL_FRAME_QUAD], and the camera path is smoothed (0 = light .. 1 = tripod): only the shake goes.
     */
    suspend fun run(
        context: Context,
        clip: TimelineClip,
        startSrcMs: Long,
        quadUv: FloatArray,
        onProgress: (Float) -> Unit,
        onLive: (PlanarTrack.LiveFrame) -> Unit = {},
        shouldStop: () -> Boolean = { false },
        smoothStrength: Float? = null
    ): CornerPin {
        if (!PlanarTrack.isAvailable) throw PlanarTrack.Unavailable()
        val res = PlanarTrack.run(context, clip, startSrcMs, quadUv, onProgress, onLive, shouldStop, global = smoothStrength != null)
        val n = res.samples.size
        if (n == 0) throw Failed("Nothing could be tracked")
        val flat = FloatArray(n * 8)
        for (i in 0 until n) System.arraycopy(res.samples[i].quad, 0, flat, i * 8, 8)
        val out = FloatArray(n * 8)
        val zoom = if (smoothStrength == null) {
            StabilizeNative.nativeQuads(flat, n, true, 1f, out)
        } else {
            val dtMs = if (n > 1) (res.samples[n - 1].srcMs - res.samples[0].srcMs).toFloat() / (n - 1) else 33f
            val sigmaFrames = sigmaSeconds(smoothStrength) * 1000f / dtMs.coerceAtLeast(1f)
            StabilizeNative.nativeSmooth(flat, n, res.aspect, sigmaFrames, true, 1f, out)
        }
        if (zoom < 0f) throw Failed(if (smoothStrength == null) "The 4 corners are not a proper surface" else "The picture could not be followed")
        val moved = ArrayList<PlanarSample>(n)
        for (i in 0 until n) moved.add(PlanarSample(res.samples[i].srcMs, out.copyOfRange(i * 8, i * 8 + 8)))
        return CornerPinApply.from(clip, PlanarResult(clip.id, moved, res.aspect, res.lostAtSrcMs))
            ?: throw Failed("Nothing could be tracked")
    }
}
