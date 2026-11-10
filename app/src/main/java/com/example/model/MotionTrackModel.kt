package com.example.model

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** One tracked position: source time and the point in the upright picture (0..1 of its width / height). */
data class TrackSample(
    val srcMs: Long,
    val u: Float,
    val v: Float,
    /** Turn of the object since the first frame, degrees clockwise (picture pixels). */
    val rotDeg: Float = 0f,
    /** Zoom of the object since the first frame (1 = same size). */
    val scale: Float = 1f
)

/** Result of a motion track on one video layer. [aspect] is the picture's width / height. */
class TrackResult(
    val clipId: String,
    val samples: List<TrackSample>,
    val aspect: Float,
    /** Source time where the object was lost (the track stops there), or null when it ran to the end. */
    val lostAtSrcMs: Long? = null
)

data class CanvasPt(val x: Float, val y: Float)

/**
 * A layer that follows a tracked object, frame by frame. No keyframes: for every frame of the
 * tracked video there is one entry ([timesMs] = project time at which that video frame is on screen,
 * [dx] / [dy] = how far the object has moved since the first frame, in the same canvas units as
 * the layer's transformX / transformY). The layer's own position stays the base, so moving the layer
 * by hand moves it relative to the object.
 *
 * Plain class on purpose (identity equality): the arrays are never changed after construction.
 */
@Serializable
class TrackFollow(
    val videoId: String,
    val timesMs: LongArray,
    val dx: FloatArray,
    val dy: FloatArray,
    /** Turn of the object per entry (degrees, relative to the first frame), or null = the layer does not turn with it. */
    val rotDeg: FloatArray? = null,
    /** Zoom of the object per entry (ratio, 1 = first frame), or null = the layer does not zoom with it. */
    val scale: FloatArray? = null
) {
    init {
        require(timesMs.size == dx.size && dx.size == dy.size) { "bad track size" }
        require(rotDeg == null || rotDeg.size == timesMs.size) { "bad track rotation size" }
        require(scale == null || scale.size == timesMs.size) { "bad track scale size" }
    }

    val size: Int get() = timesMs.size

    /**
     * Entry of the video frame that is on screen at project time [t]: the last one that starts at or
     * before [t] (a video frame is held until the next one comes, so the layer is held with it).
     * [FRAME_TOL_MS] covers the whole-millisecond rounding of frame times. Before the first frame
     * the first entry is used; -1 only when the track is empty.
     */
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

/**
 * Maps points of a video picture to canvas pixels and back, with exactly the transforms the
 * preview draws (see MediaLayerOnCanvas): fit into the frame, flip, crop, then the layer's scale,
 * rotation and position. 3D rotation and warp are not followed.
 */
class VideoPlacement(
    c: TimelineClip,
    /** Picture width / height (as shown, i.e. after the video's own rotation). */
    pictureAspect: Float,
    private val fw: Float,
    private val fh: Float
) {
    private val a = if (pictureAspect > 0.01f) pictureAspect else fw / fh
    val contentW = if (a > fw / fh) fw else fh * a
    val contentH = if (a > fw / fh) fw / a else fh
    private val x0 = (fw - contentW) / 2f
    private val y0 = (fh - contentH) / 2f

    // inner layer: flip + crop
    private val sx: Float
    private val sy: Float
    private val tx: Float
    private val ty: Float

    // outer layer: scale / rotation about the pivot, then position
    private val outerScale = c.effectiveScalePercent / 100f
    private val pivotX = c.pivotX * fw
    private val pivotY = c.pivotY * fh
    private val cosR: Float
    private val sinR: Float
    private val posX = c.depthX
    private val posY = c.depthY

    init {
        val fx = if (c.isFlippedH) -1f else 1f
        val fy = if (c.isFlippedV) -1f else 1f
        val cW = (c.cropWidthPercent / 100f).coerceIn(0.05f, 1f)
        val cH = (c.cropHeightPercent / 100f).coerceIn(0.05f, 1f)
        val cL = (1f - cW) * (c.cropHorizontalPercent / 100f)
        val cT = (1f - cH) * (c.cropVerticalPercent / 100f)
        if (cW < 0.999f || cH < 0.999f) {
            val s = minOf(fw / (cW * contentW), fh / (cH * contentH))
            sx = s * fx
            sy = s * fy
            tx = -((cL + cW / 2f) - 0.5f) * contentW * s * fx
            ty = -((cT + cH / 2f) - 0.5f) * contentH * s * fy
        } else {
            sx = fx
            sy = fy
            tx = 0f
            ty = 0f
        }
        val rad = Math.toRadians(c.rotationDeg.toDouble())
        cosR = cos(rad).toFloat()
        sinR = sin(rad).toFloat()
    }

    /** Canvas pixel of the picture point ([u], [v]) (0..1). */
    fun toCanvas(u: Float, v: Float): CanvasPt {
        val p0x = x0 + u * contentW
        val p0y = y0 + v * contentH
        val cx = fw / 2f
        val cy = fh / 2f
        val p1x = cx + sx * (p0x - cx) + tx
        val p1y = cy + sy * (p0y - cy) + ty
        val dx = (p1x - pivotX) * outerScale
        val dy = (p1y - pivotY) * outerScale
        return CanvasPt(
            pivotX + dx * cosR - dy * sinR + posX,
            pivotY + dx * sinR + dy * cosR + posY
        )
    }

    /** Picture point (u, v) under the canvas pixel ([x], [y]); may lie outside 0..1. */
    fun fromCanvas(x: Float, y: Float): CanvasPt {
        val dx = x - posX - pivotX
        val dy = y - posY - pivotY
        val rx = (dx * cosR + dy * sinR) / outerScale
        val ry = (-dx * sinR + dy * cosR) / outerScale
        val p1x = pivotX + rx
        val p1y = pivotY + ry
        val cx = fw / 2f
        val cy = fh / 2f
        val p0x = cx + (p1x - tx - cx) / sx
        val p0y = cy + (p1y - ty - cy) / sy
        return CanvasPt((p0x - x0) / contentW, (p0y - y0) / contentH)
    }

    /** Canvas pixels that one picture-width covers (used to turn a circle radius into picture units). */
    val pixelsPerPictureWidth: Float get() = abs(sx) * outerScale * contentW
}

object MotionTrackApply {

    /** Timeline time at which [clip] shows source time [srcMs] (inverse of the layer's time remap). */
    fun timelineMsAt(clip: TimelineClip, srcMs: Long): Long {
        val want = srcMs - clip.trimStartMs
        if (want <= 0L) return clip.startMs
        if (clip.keyframes[AnimProp.SPEED].orEmpty().isEmpty()) {
            val rel = (want / clip.speed.coerceAtLeast(MIN_SPEED)).toLong()
            return clip.startMs + rel.coerceIn(0L, clip.durationMs)
        }
        var lo = 0L
        var hi = clip.durationMs
        if (clip.sourceOffsetMs(hi) <= want) return clip.startMs + hi
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (clip.sourceOffsetMs(mid) < want) lo = mid else hi = mid
        }
        return clip.startMs + hi
    }

    /**
     * Camera track: no layer follows the object, the VIDEO itself moves the other way so the tracked point sits
     * exactly in the middle of the canvas in every frame. One entry per tracked video frame (same format and
     * timing as [MotionTrackApply.follow]); the offset is added to the video's own position (see renderAt).
     * Returns null when there is nothing to follow.
     */
    fun cameraFollow(
        video: TimelineClip,
        result: TrackResult,
        frameW: Float,
        frameH: Float
    ): TrackFollow? {
        if (result.samples.isEmpty()) return null
        val ts = ArrayList<Long>(result.samples.size)
        val xs = ArrayList<Float>(result.samples.size)
        val ys = ArrayList<Float>(result.samples.size)
        for (s in result.samples) {
            val t = timelineMsAt(video, s.srcMs)
            if (ts.isNotEmpty() && t <= ts.last()) continue
            // where the point is on the canvas at that moment WITHOUT any track offset (video.at ignores trackFollow)
            val p = VideoPlacement(video.at(t), result.aspect, frameW, frameH).toCanvas(s.u, s.v)
            ts.add(t)
            xs.add(frameW / 2f - p.x)
            ys.add(frameH / 2f - p.y)
        }
        if (ts.isEmpty()) return null
        val n = ts.size
        return TrackFollow(
            videoId = video.id,
            timesMs = LongArray(n) { ts[it] },
            dx = FloatArray(n) { xs[it] },
            dy = FloatArray(n) { ys[it] }
        )
    }

    /**
     * Makes a layer follow the tracked object, one entry per tracked video frame (no keyframes, no
     * smoothing, no thinning: the layer sits exactly where the tracker found the object in that frame).
     * Each entry is stamped with the project time at which [video] shows that frame.
     * Returns null when there is nothing to follow.
     */
    fun follow(
        video: TimelineClip,
        result: TrackResult,
        frameW: Float,
        frameH: Float,
        followRotation: Boolean = false,
        followScale: Boolean = false
    ): TrackFollow? {
        if (result.samples.isEmpty()) return null
        val ts = ArrayList<Long>(result.samples.size)
        val xs = ArrayList<Float>(result.samples.size)
        val ys = ArrayList<Float>(result.samples.size)
        val rs = ArrayList<Float>(result.samples.size)
        val ks = ArrayList<Float>(result.samples.size)
        for (s in result.samples) {
            val t = timelineMsAt(video, s.srcMs)
            if (ts.isNotEmpty() && t <= ts.last()) continue
            // the video as it looks at that moment (it may be moving / zooming itself)
            val at = video.at(t)
            val p = VideoPlacement(at, result.aspect, frameW, frameH).toCanvas(s.u, s.v)
            ts.add(t)
            xs.add(p.x)
            ys.add(p.y)
            // A flip (one axis) mirrors the turn; the video's own turn / zoom do not change the object's turn
            // relative to the picture, and a layer on top of the video turns by the same angle.
            rs.add(if (at.isFlippedH != at.isFlippedV) -s.rotDeg else s.rotDeg)
            ks.add(s.scale)
        }
        if (ts.isEmpty()) return null
        val n = ts.size
        return TrackFollow(
            videoId = video.id,
            timesMs = LongArray(n) { ts[it] },
            dx = FloatArray(n) { xs[it] - xs[0] },
            dy = FloatArray(n) { ys[it] - ys[0] },
            rotDeg = if (followRotation) FloatArray(n) { rs[it] } else null,
            scale = if (followScale) FloatArray(n) { ks[it] } else null
        )
    }
}

/**
 * The layer as it is DRAWN (preview, selection frame, export): [at] (keyframes) plus the tracking
 * offset of the video frame shown at [trackMs]. Editing panels keep using [at], so a tracked layer's
 * Transform values are its own offset on top of the object and are never mixed with the track.
 *
 * [timeMs] is the playhead; [trackMs] is the project time of the video picture really on screen
 * (while playing the preview video can be a little off the playhead, see CanvasArea).
 */
fun TimelineClip.renderAt(timeMs: Long, trackMs: Long = timeMs): TimelineClip {
    val c = at(timeMs)
    val f = trackFollow ?: return c
    val i = f.indexAt(trackMs)
    if (i < 0) return c
    var r = c.withProp(AnimProp.POSITION, c.transformX + f.dx[i], c.transformY + f.dy[i])
    val rot = f.rotDeg
    if (rot != null) r = r.copy(rotationDeg = r.rotationDeg + rot[i])
    val k = f.scale
    if (k != null) {
        // multiply, so a shape that has its own size keeps it (shapes are sized by shapeScale, not scalePercent)
        r = r.copy(scalePercent = r.scalePercent * k[i], shapeScaleX = r.shapeScaleX * k[i], shapeScaleY = r.shapeScaleY * k[i])
    }
    return r
}

/** Tracking offset (dx, dy) of the video frame shown at [trackMs]; zero when not tracked. */
fun TimelineClip.trackOffsetAt(trackMs: Long): Pair<Float, Float> {
    val f = trackFollow ?: return 0f to 0f
    val i = f.indexAt(trackMs)
    return if (i < 0) 0f to 0f else f.dx[i] to f.dy[i]
}
