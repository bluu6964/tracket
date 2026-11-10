package com.example.model

import kotlinx.serialization.Serializable

/**
 * One planar-track frame: source time of the video frame and the 4 corners of the tracked surface in the upright
 * picture (u, v pairs, 0..1 of its width / height): top-left, top-right, bottom-right, bottom-left.
 */
class PlanarSample(val srcMs: Long, val quad: FloatArray) {
    init {
        require(quad.size == 8) { "a planar sample needs 8 numbers" }
    }
}

/** Result of a planar track on one video layer. [aspect] is the picture's width / height. */
class PlanarResult(
    val clipId: String,
    val samples: List<PlanarSample>,
    val aspect: Float,
    /** Source time where the surface was lost (the track stops there), or null when it ran to the end. */
    val lostAtSrcMs: Long? = null
)

/**
 * Corner pin (Mocha-style): this layer's picture is drawn on a flat surface of [videoId] that was planar-tracked, with
 * perspective, frame by frame. For every tracked frame of the video there is one entry: [timesMs] = project time at which
 * that frame is on screen, [quad] = 8 floats per entry (the surface's corners in the video's picture, 0..1).
 * The layer's own position / scale / rotation / 3D are ignored while it is pinned; crop, opacity, blend mode and effects work.
 *
 * Plain class on purpose (identity equality): the arrays are never changed after construction.
 */
@Serializable
class CornerPin(
    val videoId: String,
    val timesMs: LongArray,
    val quad: FloatArray,
    /** Picture width / height of the tracked video (as shown). */
    val aspect: Float
) {
    init {
        require(quad.size == timesMs.size * 8) { "bad corner pin size" }
    }

    val size: Int get() = timesMs.size

    /** Entry of the video frame on screen at project time [t] (held until the next frame comes); -1 only when empty. */
    fun indexAt(t: Long): Int {
        val n = timesMs.size
        if (n == 0) return -1
        val key = t + FRAME_TOL_MS
        if (key < timesMs[0]) return 0
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (timesMs[mid] <= key) lo = mid else hi = mid - 1
        }
        return lo
    }

    companion object {
        const val FRAME_TOL_MS = 2L
    }
}

object CornerPinApply {
    /** Builds the pin of a follower from the planar track of [video]; null when there is nothing to follow. */
    fun from(video: TimelineClip, res: PlanarResult): CornerPin? {
        if (res.samples.isEmpty()) return null
        val n = res.samples.size
        val times = LongArray(n)
        val q = FloatArray(n * 8)
        for (i in 0 until n) {
            val s = res.samples[i]
            times[i] = MotionTrackApply.timelineMsAt(video, s.srcMs)
            System.arraycopy(s.quad, 0, q, i * 8, 8)
        }
        return CornerPin(video.id, times, q, if (res.aspect > 0f) res.aspect else 16f / 9f)
    }
}
