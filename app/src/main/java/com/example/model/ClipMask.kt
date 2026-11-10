package com.example.model

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Shape of a mask. The numbers are am::MaskShape in cpp/engine/mask_plan.h. */
object MaskShape {
    const val RECT = 0
    const val ELLIPSE = 1
    const val POLYGON = 2
    const val STAR = 3
    const val LINE = 4
    const val MIRROR = 5
    const val PATH = 6

    /** The shapes of the "add mask" row (a heart is a ready-made pen path). */
    val names = mapOf(
        RECT to "Rectangle", ELLIPSE to "Ellipse", POLYGON to "Polygon", STAR to "Star",
        LINE to "Line", MIRROR to "Mirror", PATH to "Pen"
    )
}

/** How a mask is combined with the masks above it in the list. The numbers are am::MaskMode. */
object MaskMode {
    const val ADD = 0
    const val SUBTRACT = 1
    const val INTERSECT = 2
    const val DIFFERENCE = 3

    val names = listOf(ADD to "Add", SUBTRACT to "Subtract", INTERSECT to "Intersect", DIFFERENCE to "Difference")
}

/** A control point of a pen path, inside the mask's own box: x and y from -0.5 to 0.5 (0,0 = box centre). */
@Serializable
data class MaskPoint(val x: Float, val y: Float)

/**
 * The part of a mask that can be animated. Position and size are fractions of the PICTURE (0..1, top-left origin): the
 * mask belongs to the picture, so it moves, scales and turns with the layer.
 */
@Serializable
data class MaskState(
    val cx: Float = 0.5f,
    val cy: Float = 0.5f,
    val w: Float = 0.6f,
    val h: Float = 0.6f,
    /** Degrees, clockwise. */
    val rot: Float = 0f,
    /** Soft edge 0..100. */
    val feather: Float = 0f,
    /** Grow (+) / shrink (-) the shape, -100..100. */
    val expand: Float = 0f,
    /** 0..100. */
    val opacity: Float = 100f
) {
    fun lerp(o: MaskState, t: Float): MaskState = MaskState(
        cx + (o.cx - cx) * t, cy + (o.cy - cy) * t, w + (o.w - w) * t, h + (o.h - h) * t,
        rot + (o.rot - rot) * t, feather + (o.feather - feather) * t, expand + (o.expand - expand) * t,
        opacity + (o.opacity - opacity) * t
    )
}

/**
 * Motion track of a mask (Mocha-style "track the mask to the object"): for every tracked video frame the object's place
 * ([u], [v], fractions of the picture), turn ([rotDeg], clockwise) and zoom ([scale]) since the first frame. The mask's
 * own state is where it sits on that first frame; at any other time it is moved / turned / zoomed with the object about
 * that first position. Frame by frame (no keyframes, no smoothing): [indexAt] holds a frame until the next one.
 * Plain class on purpose (identity equality): the arrays are never changed after construction.
 */
@Serializable
class MaskTrack(
    /** Source time of every tracked video frame, ascending. */
    val srcMs: LongArray,
    val u: FloatArray,
    val v: FloatArray,
    val rotDeg: FloatArray,
    val scale: FloatArray,
    /** Picture width / height (turning is done in pixels, so the picture's shape matters). */
    val aspect: Float,
    val followRotation: Boolean,
    val followScale: Boolean
) {
    init {
        require(srcMs.size == u.size && u.size == v.size && v.size == rotDeg.size && rotDeg.size == scale.size) { "bad mask track size" }
    }

    val size: Int get() = srcMs.size

    /** The tracked frame on screen at source time [src]: the last one at or before it (-1 when empty). */
    fun indexAt(src: Long): Int {
        val n = srcMs.size
        if (n == 0) return -1
        val key = src + TOL_MS
        if (key < srcMs[0]) return 0
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (srcMs[mid] <= key) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** The same track with turn / zoom following switched on or off. */
    fun withFollow(rotation: Boolean, scaleToo: Boolean) = MaskTrack(srcMs, u, v, rotDeg, scale, aspect, rotation, scaleToo)

    private fun turn(i: Int) = if (followRotation) rotDeg[i] else 0f
    private fun zoom(i: Int) = if (followScale) scale[i].coerceIn(0.05f, 20f) else 1f

    /** The mask state [s] (as placed on the first tracked frame) moved with the object at source time [src]. */
    fun apply(s: MaskState, src: Long, scaleSize: Boolean = true): MaskState {
        val i = indexAt(src)
        if (i < 0) return s
        val th = Math.toRadians(turn(i).toDouble())
        val c = cos(th).toFloat()
        val sn = sin(th).toFloat()
        val k = zoom(i)
        val du = (s.cx - u[0]) * aspect
        val dv = s.cy - v[0]
        val rx = (du * c - dv * sn) * k
        val ry = (du * sn + dv * c) * k
        return s.copy(
            cx = u[i] + rx / aspect, cy = v[i] + ry,
            w = if (scaleSize) s.w * k else s.w, h = if (scaleSize) s.h * k else s.h,
            rot = s.rot + turn(i)
        )
    }

    /** Inverse of [apply]: the first-frame state that would show as [shown] at source time [src]. */
    fun unapply(shown: MaskState, src: Long, scaleSize: Boolean = true): MaskState {
        val i = indexAt(src)
        if (i < 0) return shown
        val th = Math.toRadians(turn(i).toDouble())
        val c = cos(th).toFloat()
        val sn = sin(th).toFloat()
        val k = zoom(i)
        val rx = (shown.cx - u[i]) * aspect / k
        val ry = (shown.cy - v[i]) / k
        val du = rx * c + ry * sn
        val dv = -rx * sn + ry * c
        return shown.copy(
            cx = u[0] + du / aspect, cy = v[0] + dv,
            w = if (scaleSize) shown.w / k else shown.w, h = if (scaleSize) shown.h / k else shown.h,
            rot = shown.rot - turn(i)
        )
    }

    companion object {
        const val TOL_MS = 2L

        /** Builds the track from a motion-track result; null when there is nothing to follow. */
        fun from(result: TrackResult, followRotation: Boolean, followScale: Boolean): MaskTrack? {
            val ss = result.samples
            if (ss.isEmpty()) return null
            val n = ss.size
            return MaskTrack(
                LongArray(n) { ss[it].srcMs }, FloatArray(n) { ss[it].u }, FloatArray(n) { ss[it].v },
                FloatArray(n) { ss[it].rotDeg }, FloatArray(n) { ss[it].scale },
                if (result.aspect > 0.05f) result.aspect else 1f, followRotation, followScale
            )
        }
    }
}

/** A keyframe of a mask: [timeMs] is relative to the layer start (like [Keyframe]); [easing] shapes the way to the next one. */
@Serializable
data class MaskKey(
    val timeMs: Long,
    val state: MaskState,
    val easing: Easing = Easing(0.42f, 0f, 0.58f, 1f)
)

/**
 * One mask of a layer. Run by the C++ engine ([ClipMaskLibrary.toNative]). While [keys] is empty the mask is [state];
 * with keys the animated values come from [stateAt] and an edit at the playhead writes a key ([withStateAt]).
 */
@Serializable
data class ClipMask(
    val id: String = UUID.randomUUID().toString(),
    val shape: Int,
    val mode: Int = MaskMode.ADD,
    val inverted: Boolean = false,
    val enabled: Boolean = true,
    val state: MaskState = MaskState(),
    /** Rectangle corner radius 0..100 (% of the shorter half side). */
    val corner: Float = 0f,
    /** Polygon sides / star points, 3..12. */
    val sides: Int = 5,
    /** Star: inner radius in % of the outer radius, 5..100 (100 = a polygon). */
    val inner: Float = 45f,
    /** Pen path: 3..[ClipMaskLibrary.MAX_POINTS] control points inside the box, see [MaskPoint]. */
    val points: List<MaskPoint> = emptyList(),
    /** Pen path: a smooth curve through the points instead of straight edges. */
    val smooth: Boolean = false,
    val keys: List<MaskKey> = emptyList(),
    /** Follows an object of the video (see [MaskTrack]); null = not tracked. */
    val track: MaskTrack? = null
) {
    val isAnimated: Boolean get() = keys.isNotEmpty()

    /** The size of a line mask is not a size (only its angle and place count), so the track does not zoom it. */
    private val scalesWithTrack: Boolean get() = shape != MaskShape.LINE

    /** The look at [relMs] as it is DRAWN: [stateAt] moved with the tracked object at source time [srcMs]. */
    fun shownStateAt(relMs: Long, srcMs: Long): MaskState {
        val s = stateAt(relMs)
        val t = track ?: return s
        return t.apply(s, srcMs, scalesWithTrack)
    }

    /** Edit made on the picture as it is DRAWN (canvas handles, sliders): stored back relative to the object. */
    fun withShownStateAt(relMs: Long, srcMs: Long, shown: MaskState): ClipMask {
        val t = track ?: return withStateAt(relMs, shown)
        return withStateAt(relMs, t.unapply(shown, srcMs, scalesWithTrack))
    }

    /** The animated look at [relMs] (ms after the layer start). */
    fun stateAt(relMs: Long): MaskState {
        if (keys.isEmpty()) return state
        val first = keys.first()
        if (relMs <= first.timeMs) return first.state
        val last = keys.last()
        if (relMs >= last.timeMs) return last.state
        var i = 0
        while (i < keys.size - 1 && keys[i + 1].timeMs <= relMs) i++
        val k0 = keys[i]
        val k1 = keys[i + 1]
        val span = (k1.timeMs - k0.timeMs).coerceAtLeast(1L)
        val t = (relMs - k0.timeMs).toFloat() / span
        val e = if (k0.easing.isLinear) t else k0.easing.apply(t)
        return k0.state.lerp(k1.state, e)
    }

    /** Index of the key the time sits on (within [KEYFRAME_TOL_MS]), or -1. */
    fun keyIndexAt(relMs: Long): Int {
        var best = -1
        var bestD = Long.MAX_VALUE
        for (i in keys.indices) {
            val d = abs(keys[i].timeMs - relMs)
            if (d <= KEYFRAME_TOL_MS && d < bestD) {
                best = i
                bestD = d
            }
        }
        return best
    }

    /** Edit: without keys the base state changes, with keys the key at [relMs] is replaced / added. */
    fun withStateAt(relMs: Long, s: MaskState): ClipMask {
        if (keys.isEmpty()) return copy(state = s)
        val idx = keyIndexAt(relMs)
        val next = if (idx >= 0) keys.toMutableList().also { it[idx] = it[idx].copy(state = s) }
        else (keys + MaskKey(relMs, s)).sortedBy { it.timeMs }
        return copy(keys = next)
    }

    /** Diamond button: add a key with the current look at [relMs], or remove the key under the playhead. */
    fun toggleKeyAt(relMs: Long): ClipMask {
        val idx = keyIndexAt(relMs)
        if (idx < 0) {
            val cur = stateAt(relMs)
            return copy(state = cur, keys = (keys + MaskKey(relMs, cur)).sortedBy { it.timeMs })
        }
        val removed = keys[idx]
        val next = keys.filterIndexed { i, _ -> i != idx }
        // the last key goes: the mask stops being animated and keeps the look that key had
        return if (next.isEmpty()) copy(keys = emptyList(), state = removed.state) else copy(keys = next)
    }

    fun withKeyEasing(index: Int, e: Easing): ClipMask {
        if (index !in keys.indices) return this
        return copy(keys = keys.toMutableList().also { it[index] = it[index].copy(easing = e) })
    }

    fun withShiftedKeys(deltaMs: Long): ClipMask =
        if (keys.isEmpty() || deltaMs == 0L) this else copy(keys = keys.map { it.copy(timeMs = it.timeMs + deltaMs) })

    fun withScaledKeys(factor: Float): ClipMask =
        if (keys.isEmpty() || abs(factor - 1f) < 1e-4f) this
        else copy(keys = keys.map { it.copy(timeMs = (it.timeMs * factor).toLong()) })
}

/** The masks of a layer, shifted / stretched together with the other keyframes (trim, split, speed change). */
fun List<ClipMask>.shiftedKeys(deltaMs: Long): List<ClipMask> = if (isEmpty()) this else map { it.withShiftedKeys(deltaMs) }
fun List<ClipMask>.scaledKeys(factor: Float): List<ClipMask> = if (isEmpty()) this else map { it.withScaledKeys(factor) }

object ClipMaskLibrary {
    const val MAX_MASKS = 8
    const val MAX_POINTS = 16
    const val MIN_POINTS = 3

    /** Floats per mask in the JNI array (am::kMaskStride). */
    const val STRIDE = 16 + 2 * MAX_POINTS

    /**
     * A new mask for a layer whose picture is [pictureAspect] (width / height) wide: polygon / star boxes are square in
     * pixels. Starts in the middle, about 60 % of the picture height.
     */
    fun create(shape: Int, pictureAspect: Float, mode: Int = MaskMode.ADD): ClipMask {
        val a = pictureAspect.coerceIn(0.2f, 5f)
        val square = MaskState(w = 0.6f / a, h = 0.6f)
        return when (shape) {
            MaskShape.RECT -> ClipMask(shape = shape, mode = mode, state = MaskState(w = 0.6f, h = 0.6f))
            MaskShape.ELLIPSE -> ClipMask(shape = shape, mode = mode, state = MaskState(w = 0.6f, h = 0.6f))
            MaskShape.POLYGON -> ClipMask(shape = shape, mode = mode, sides = 6, state = square)
            MaskShape.STAR -> ClipMask(shape = shape, mode = mode, sides = 5, inner = 45f, state = square)
            MaskShape.LINE -> ClipMask(shape = shape, mode = mode, state = MaskState(cx = 0.5f, cy = 0.5f, w = 1f, h = 1f, feather = 8f))
            MaskShape.MIRROR -> ClipMask(shape = shape, mode = mode, state = MaskState(w = 1f, h = 0.4f, feather = 8f))
            else -> ClipMask(shape = MaskShape.PATH, mode = mode, state = MaskState(w = 1f, h = 1f))
        }
    }

    /** A heart: a smooth pen path of 16 points (its box is square in pixels). */
    fun heart(pictureAspect: Float, mode: Int = MaskMode.ADD): ClipMask {
        val a = pictureAspect.coerceIn(0.2f, 5f)
        val pts = (0 until MAX_POINTS).map { i ->
            val t = (2.0 * PI * i / MAX_POINTS)
            val x = 16.0 * sin(t) * sin(t) * sin(t)
            val y = 13.0 * cos(t) - 5.0 * cos(2 * t) - 2.0 * cos(3 * t) - cos(4 * t)
            MaskPoint((x / 16.0 * 0.5).toFloat(), (-(y + 2.5) / 14.5 * 0.5).toFloat())
        }
        return ClipMask(
            shape = MaskShape.PATH, mode = mode, points = pts, smooth = true,
            state = MaskState(w = 0.6f / a, h = 0.6f)
        )
    }

    /**
     * Pen path drawn on the canvas: [pictureXY] are points in picture coordinates (0..1). The box is fitted around them
     * and the points are stored inside it (so the box handles can still move / scale / turn the path).
     */
    fun fromPenPoints(pictureXY: List<Pair<Float, Float>>, smooth: Boolean, mode: Int = MaskMode.ADD): ClipMask? {
        if (pictureXY.size < MIN_POINTS) return null
        val pts = pictureXY.take(MAX_POINTS)
        val minX = pts.minOf { it.first }
        val maxX = pts.maxOf { it.first }
        val minY = pts.minOf { it.second }
        val maxY = pts.maxOf { it.second }
        val w = (maxX - minX).coerceAtLeast(0.02f)
        val h = (maxY - minY).coerceAtLeast(0.02f)
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        return ClipMask(
            shape = MaskShape.PATH, mode = mode, smooth = smooth,
            state = MaskState(cx = cx, cy = cy, w = w, h = h),
            points = pts.map { MaskPoint((it.first - cx) / w, (it.second - cy) / h) }
        )
    }

    fun isValid(m: ClipMask): Boolean = m.shape != MaskShape.PATH || m.points.size >= MIN_POINTS

    /**
     * The enabled masks at [relMs] (ms after the layer start) as the flat array the C++ engine reads (groups of
     * [STRIDE] floats, see mask_plan.h), or null when there is nothing to run. Max [MAX_MASKS]. [srcMs] = the layer's
     * source time at [relMs] (only a tracked mask needs it).
     */
    fun toNative(masks: List<ClipMask>, relMs: Long, srcMs: Long = relMs): FloatArray? {
        val on = masks.filter { it.enabled && isValid(it) }.take(MAX_MASKS)
        if (on.isEmpty()) return null
        val out = FloatArray(on.size * STRIDE)
        on.forEachIndexed { i, m ->
            val s = m.shownStateAt(relMs, srcMs)
            val o = i * STRIDE
            out[o] = m.shape.toFloat()
            out[o + 1] = m.mode.toFloat()
            out[o + 2] = if (m.inverted) 1f else 0f
            out[o + 3] = s.cx
            out[o + 4] = s.cy
            out[o + 5] = s.w
            out[o + 6] = s.h
            out[o + 7] = s.rot
            out[o + 8] = s.feather
            out[o + 9] = s.expand
            out[o + 10] = s.opacity
            out[o + 11] = m.corner
            out[o + 12] = m.sides.toFloat()
            out[o + 13] = m.inner
            val n = if (m.shape == MaskShape.PATH) m.points.size.coerceAtMost(MAX_POINTS) else 0
            out[o + 14] = n.toFloat()
            out[o + 15] = if (m.smooth) 1f else 0f
            for (k in 0 until n) {
                out[o + 16 + 2 * k] = m.points[k].x
                out[o + 17 + 2 * k] = m.points[k].y
            }
        }
        return out
    }
}
