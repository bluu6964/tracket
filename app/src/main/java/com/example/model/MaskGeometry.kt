package com.example.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The geometry behind editing a mask on the canvas (outline, handles, drag maths). Pure Kotlin, no Compose, so it is
 * unit tested (MaskGeometryTest). Everything here is in PICTURE UNITS: the picture is [aspect] wide and 1 high, origin
 * top-left, so a "square" is square on the screen whatever the picture's shape. The shader uses the same layout
 * (picture pixels = picture units * height).
 */
data class Pt(val x: Float, val y: Float)

object MaskHandle {
    const val NONE = -1
    const val BODY = 0

    /** 10..13 = corners (top-left, top-right, bottom-right, bottom-left). */
    const val CORNER = 10

    /** 20..23 = edge middles (top, right, bottom, left). */
    const val EDGE = 20
    const val ROTATE = 30

    /** 100 + i = control point i of a pen path. */
    const val POINT = 100
}

/** A mask in picture units: centre, half size, rotation. */
class MaskFrame(val mask: ClipMask, val s: MaskState, val aspect: Float) {
    val cx: Float = s.cx * aspect
    val cy: Float = s.cy
    val hw: Float = max(MIN_HALF, s.w * aspect / 2f)
    val hh: Float = max(MIN_HALF, s.h / 2f)
    private val rad: Double = Math.toRadians(s.rot.toDouble())
    val cosR: Float = cos(rad).toFloat()
    val sinR: Float = sin(rad).toFloat()

    fun toPicture(lx: Float, ly: Float): Pt = Pt(cx + lx * cosR - ly * sinR, cy + lx * sinR + ly * cosR)

    fun toLocal(p: Pt): Pt {
        val dx = p.x - cx
        val dy = p.y - cy
        return Pt(dx * cosR + dy * sinR, -dx * sinR + dy * cosR)
    }

    /** 0 = top-left, 1 = top-right, 2 = bottom-right, 3 = bottom-left. */
    fun corner(i: Int): Pt = toPicture(if (i == 0 || i == 3) -hw else hw, if (i == 0 || i == 1) -hh else hh)

    /** 0 = top, 1 = right, 2 = bottom, 3 = left. */
    fun edge(i: Int): Pt = when (i) {
        0 -> toPicture(0f, -hh)
        1 -> toPicture(hw, 0f)
        2 -> toPicture(0f, hh)
        else -> toPicture(-hw, 0f)
    }

    fun rotateHandle(): Pt =
        if (mask.shape == MaskShape.LINE) toPicture(0f, -ROTATE_OFFSET) else toPicture(0f, -hh - ROTATE_OFFSET)

    fun controlPoint(i: Int): Pt = toPicture(mask.points[i].x * hw * 2f, mask.points[i].y * hh * 2f)

    /** The handles of this shape and where they are (picture units). */
    fun handles(): List<Pair<Int, Pt>> {
        val out = ArrayList<Pair<Int, Pt>>()
        when (mask.shape) {
            MaskShape.LINE -> out.add(MaskHandle.ROTATE to rotateHandle())
            MaskShape.MIRROR -> {
                out.add((MaskHandle.EDGE + 0) to edge(0))
                out.add((MaskHandle.EDGE + 2) to edge(2))
                out.add(MaskHandle.ROTATE to rotateHandle())
            }
            else -> {
                for (i in 0..3) out.add((MaskHandle.CORNER + i) to corner(i))
                for (i in 0..3) out.add((MaskHandle.EDGE + i) to edge(i))
                out.add(MaskHandle.ROTATE to rotateHandle())
                if (mask.shape == MaskShape.PATH) {
                    for (i in mask.points.indices) out.add((MaskHandle.POINT + i) to controlPoint(i))
                }
            }
        }
        return out
    }

    /** Closed outline of the shape (picture units); a LINE / MIRROR gives its lines, see [isOutlineClosed]. */
    fun outline(): List<Pt> {
        val pts = ArrayList<Pt>()
        when (mask.shape) {
            MaskShape.RECT -> {
                val r = (mask.corner / 100f * min(hw, hh)).coerceAtLeast(0f)
                if (r < 1e-4f) {
                    for (i in 0..3) pts.add(corner(i))
                } else {
                    // corner centres and the quarter arcs around them
                    val cs = listOf(Pair(-1f, -1f), Pair(1f, -1f), Pair(1f, 1f), Pair(-1f, 1f))
                    val start = listOf(180.0, 270.0, 0.0, 90.0)
                    for (k in 0..3) {
                        val ccx = cs[k].first * (hw - r)
                        val ccy = cs[k].second * (hh - r)
                        for (j in 0..ARC_STEPS) {
                            val a = Math.toRadians(start[k] + 90.0 * j / ARC_STEPS)
                            pts.add(toPicture(ccx + (r * cos(a)).toFloat(), ccy + (r * sin(a)).toFloat()))
                        }
                    }
                }
            }
            MaskShape.ELLIPSE -> for (i in 0 until ELLIPSE_STEPS) {
                val a = 2.0 * PI * i / ELLIPSE_STEPS
                pts.add(toPicture((hw * cos(a)).toFloat(), (hh * sin(a)).toFloat()))
            }
            MaskShape.POLYGON, MaskShape.STAR -> {
                val n = mask.sides.coerceIn(3, 12)
                val an = PI / n
                val rho = if (mask.shape == MaskShape.POLYGON) cos(an) else cos(an) * mask.inner.coerceIn(5f, 100f) / 100.0
                val count = if (mask.shape == MaskShape.POLYGON) n else 2 * n
                for (k in 0 until count) {
                    val outer = mask.shape == MaskShape.POLYGON || k % 2 == 0
                    val a = k * an * (if (mask.shape == MaskShape.POLYGON) 2.0 else 1.0)
                    val r = if (outer) 1.0 else rho
                    // vertex up (screen up = local -y), same as the shader
                    pts.add(toPicture((hw * r * sin(a)).toFloat(), (-hh * r * cos(a)).toFloat()))
                }
            }
            MaskShape.LINE -> {
                pts.add(toPicture(-LINE_LEN, 0f))
                pts.add(toPicture(LINE_LEN, 0f))
            }
            MaskShape.MIRROR -> {
                pts.add(toPicture(-LINE_LEN, -hh))
                pts.add(toPicture(LINE_LEN, -hh))
                pts.add(toPicture(-LINE_LEN, hh))
                pts.add(toPicture(LINE_LEN, hh))
            }
            else -> {
                val flat = flattenControlPoints(mask.points, mask.smooth)
                for (p in flat) pts.add(toPicture(p.x * hw * 2f, p.y * hh * 2f))
            }
        }
        return pts
    }

    val isOutlineClosed: Boolean get() = mask.shape != MaskShape.LINE && mask.shape != MaskShape.MIRROR

    /** True when [p] is on / inside the shape (a hit for "drag the whole mask"). */
    fun containsPoint(p: Pt, lineTol: Float): Boolean {
        val l = toLocal(p)
        return when (mask.shape) {
            MaskShape.LINE -> abs(l.y) <= lineTol
            MaskShape.MIRROR -> abs(l.y) <= hh + lineTol
            MaskShape.PATH -> pointInPolygon(p, outline()) || nearEdge(p, outline(), lineTol)
            else -> pointInPolygon(p, outline())
        }
    }

    // ---- drags (every function returns the new state, nothing is mutated) ----

    /** Dragging corner [i]: the opposite corner stays where it is. */
    fun dragCorner(i: Int, p: Pt): MaskState {
        val sx = if (i == 0 || i == 3) -1f else 1f
        val sy = if (i == 0 || i == 1) -1f else 1f
        val fx = -sx * hw
        val fy = -sy * hh
        val q = toLocal(p)
        val nx = fx + sx * max((q.x - fx) * sx, MIN_HALF * 2f)
        val ny = fy + sy * max((q.y - fy) * sy, MIN_HALF * 2f)
        return resized(Pt((fx + nx) / 2f, (fy + ny) / 2f), abs(nx - fx), abs(ny - fy))
    }

    /** Dragging an edge middle: the opposite edge stays (a mirror band grows on both sides). */
    fun dragEdge(i: Int, p: Pt): MaskState {
        val q = toLocal(p)
        if (mask.shape == MaskShape.MIRROR) {
            return s.copy(h = (max(abs(q.y), MIN_HALF) * 2f).coerceAtMost(4f))
        }
        return when (i) {
            0, 2 -> {
                val sy = if (i == 0) -1f else 1f
                val fy = -sy * hh
                val ny = fy + sy * max((q.y - fy) * sy, MIN_HALF * 2f)
                resized(Pt(0f, (fy + ny) / 2f), hw * 2f, abs(ny - fy))
            }
            else -> {
                val sx = if (i == 3) -1f else 1f
                val fx = -sx * hw
                val nx = fx + sx * max((q.x - fx) * sx, MIN_HALF * 2f)
                resized(Pt((fx + nx) / 2f, 0f), abs(nx - fx), hh * 2f)
            }
        }
    }

    /** New centre (local [c] = where the middle of the new box is) and full size in picture units. */
    private fun resized(c: Pt, fullW: Float, fullH: Float): MaskState {
        val wp = toPicture(c.x, c.y)
        return s.copy(
            cx = wp.x / aspect, cy = wp.y,
            w = (fullW / aspect).coerceIn(0.001f, 8f), h = fullH.coerceIn(0.001f, 8f)
        )
    }

    /** The rotate handle follows the finger: 0 deg = handle straight above the centre. Snaps to 45 deg steps near them. */
    fun rotateTo(p: Pt): MaskState {
        var deg = Math.toDegrees(atan2((p.y - cy).toDouble(), (p.x - cx).toDouble())).toFloat() + 90f
        while (deg > 180f) deg -= 360f
        while (deg < -180f) deg += 360f
        val snapped = Math.round(deg / 45f) * 45f
        if (abs(deg - snapped) < 2.5f) deg = if (snapped > 180f) snapped - 360f else snapped
        return s.copy(rot = deg)
    }

    /** Moves the centre by the finger travel from [from] to [to] (picture units), starting at the state [start]. */
    fun movedBy(start: MaskState, from: Pt, to: Pt): MaskState =
        start.copy(cx = start.cx + (to.x - from.x) / aspect, cy = start.cy + (to.y - from.y))

    /** Control point [i] to where the finger is. */
    fun dragPoint(i: Int, p: Pt): ClipMask {
        if (i !in mask.points.indices) return mask
        val q = toLocal(p)
        val np = MaskPoint((q.x / (hw * 2f)).coerceIn(-1.5f, 1.5f), (q.y / (hh * 2f)).coerceIn(-1.5f, 1.5f))
        return mask.copy(points = mask.points.toMutableList().also { it[i] = np })
    }

    /**
     * A tap near an edge of the control polygon inserts a point there. Returns the mask with the new point and its
     * index, or null when the tap is farther than [tol] from every edge or the path is full.
     */
    fun insertPointNear(p: Pt, tol: Float): Pair<ClipMask, Int>? {
        val n = mask.points.size
        if (n < MaskLimits.MIN_POINTS || n >= MaskLimits.MAX_POINTS) return null
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in 0 until n) {
            val a = controlPoint(i)
            val b = controlPoint((i + 1) % n)
            val d = distToSegment(p, a, b)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        if (best < 0 || bestD > tol) return null
        val q = toLocal(p)
        val np = MaskPoint(q.x / (hw * 2f), q.y / (hh * 2f))
        val list = mask.points.toMutableList().also { it.add(best + 1, np) }
        return mask.copy(points = list) to (best + 1)
    }

    companion object {
        const val MIN_HALF = 0.004f
        const val ROTATE_OFFSET = 0.09f
        private const val LINE_LEN = 20f
        private const val ARC_STEPS = 6
        private const val ELLIPSE_STEPS = 64
    }
}

/** Limits shared with [ClipMaskLibrary] (kept here so the geometry file has no other dependency). */
object MaskLimits {
    const val MIN_POINTS = ClipMaskLibrary.MIN_POINTS
    const val MAX_POINTS = ClipMaskLibrary.MAX_POINTS
}

/** Same flattening as am::maskFlattenPath (C++): closed Catmull-Rom curve or the plain polygon. Points in the mask's box. */
fun flattenControlPoints(c: List<MaskPoint>, smooth: Boolean): List<Pt> {
    val n = min(c.size, ClipMaskLibrary.MAX_POINTS)
    if (n < 3) return emptyList()
    if (!smooth) return (0 until n).map { Pt(c[it].x, c[it].y) }
    val spp = max(1, min(8, 96 / n))
    val out = ArrayList<Pt>()
    for (i in 0 until n) {
        val p0 = c[(i + n - 1) % n]
        val p1 = c[i]
        val p2 = c[(i + 1) % n]
        val p3 = c[(i + 2) % n]
        for (s in 0 until spp) {
            val t = s.toFloat() / spp
            val t2 = t * t
            val t3 = t2 * t
            fun f(a: Float, b: Float, cc: Float, d: Float): Float =
                0.5f * ((2f * b) + (-a + cc) * t + (2f * a - 5f * b + 4f * cc - d) * t2 + (-a + 3f * b - 3f * cc + d) * t3)
            out.add(Pt(f(p0.x, p1.x, p2.x, p3.x), f(p0.y, p1.y, p2.y, p3.y)))
        }
    }
    return out
}

fun pointInPolygon(p: Pt, poly: List<Pt>): Boolean {
    if (poly.size < 3) return false
    var inside = false
    var j = poly.size - 1
    for (i in poly.indices) {
        val a = poly[i]
        val b = poly[j]
        if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
        j = i
    }
    return inside
}

fun distToSegment(p: Pt, a: Pt, b: Pt): Float {
    val ex = b.x - a.x
    val ey = b.y - a.y
    val ee = ex * ex + ey * ey
    val t = if (ee > 1e-12f) (((p.x - a.x) * ex + (p.y - a.y) * ey) / ee).coerceIn(0f, 1f) else 0f
    return hypot(p.x - (a.x + ex * t), p.y - (a.y + ey * t))
}

private fun nearEdge(p: Pt, poly: List<Pt>, tol: Float): Boolean {
    if (poly.size < 2) return false
    var j = poly.size - 1
    for (i in poly.indices) {
        if (distToSegment(p, poly[i], poly[j]) <= tol) return true
        j = i
    }
    return false
}

/** Nearest handle to [p] within [radius] (picture units), else [MaskHandle.NONE]. Rotate / points win over corners. */
fun MaskFrame.hitHandle(p: Pt, radius: Float): Int {
    var best = MaskHandle.NONE
    var bestD = radius
    for ((kind, pos) in handles()) {
        // control points sit on the shape, so they must beat the body but not each other's neighbours: plain nearest wins
        val d = hypot(p.x - pos.x, p.y - pos.y)
        if (d <= bestD) {
            bestD = d
            best = kind
        }
    }
    return best
}

/**
 * Where the picture sits inside the box a layer is drawn in (crop, flip). Picture coordinates are fractions 0..1 of the
 * whole picture; box coordinates are in px from the centre of the visible box, like [com.example.ui.components.FrameGeo].
 */
class MaskLayerMap(
    private val cropL: Float,
    private val cropT: Float,
    private val cropW: Float,
    private val cropH: Float,
    private val boxW: Float,
    private val boxH: Float,
    private val flipH: Boolean,
    private val flipV: Boolean
) {
    fun pictureToBox(u: Float, v: Float): Pt {
        var qx = (u - (cropL + cropW / 2f)) / cropW * boxW
        var qy = (v - (cropT + cropH / 2f)) / cropH * boxH
        if (flipH) qx = -qx
        if (flipV) qy = -qy
        return Pt(qx, qy)
    }

    fun boxToPicture(qx: Float, qy: Float): Pt {
        val x = if (flipH) -qx else qx
        val y = if (flipV) -qy else qy
        return Pt(cropL + cropW / 2f + x / boxW * cropW, cropT + cropH / 2f + y / boxH * cropH)
    }

    companion object {
        /** [clip] as drawn at the playhead (keyframes applied), [boxW] x [boxH] = its visible size on the canvas. */
        fun of(clip: TimelineClip, boxW: Float, boxH: Float): MaskLayerMap {
            val cw = (clip.cropWidthPercent / 100f).coerceIn(0.05f, 1f)
            val ch = (clip.cropHeightPercent / 100f).coerceIn(0.05f, 1f)
            return MaskLayerMap(
                (1f - cw) * clip.cropHorizontalPercent / 100f, (1f - ch) * clip.cropVerticalPercent / 100f,
                cw, ch, boxW.coerceAtLeast(1f), boxH.coerceAtLeast(1f), clip.isFlippedH, clip.isFlippedV
            )
        }
    }
}

/** Index of the next key after [relMs] (or the previous one when [forward] is false) among all keys of the mask, or -1. */
fun ClipMask.neighbourKey(relMs: Long, forward: Boolean): Int {
    if (keys.isEmpty()) return -1
    return if (forward) keys.indexOfFirst { it.timeMs > relMs + KEYFRAME_TOL_MS }
    else keys.indexOfLast { it.timeMs < relMs - KEYFRAME_TOL_MS }
}
