package com.example.model

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Node editing of PATH outlines (build 177 — Sarowar's correction of 2026-11-14: the
 * build-166 Draw is FREEHAND; real vector drawing is POINT-based, "অন্য অ্যাপ এ যেরকম").
 *
 * A stored outline ([TimelineClip.pathSegments]: M / L / Q / C / Z ops with absolute
 * points) becomes a list of [PathAnchor]s — each anchor has a position and optional
 * in / out bezier handles — and back, losslessly:
 *
 * - L round-trips as L (an anchor pair with no handles on the joining sides).
 * - C round-trips as C (the start anchor's out handle + the end anchor's in handle).
 * - Q is converted to C on entry by exact degree elevation
 *   (c1 = p0 + 2/3 (q − p0), c2 = p3 + 2/3 (q − p3)) — the same curve, not an approximation.
 * - H / V / S / T never reach storage: the importer already absolutizes them
 *   (H/V → L, S → C, T → Q) when it parses the path data, so Q is the only op that
 *   needs converting here.
 *
 * A [PathAnchor.smooth] point keeps its two handles COLLINEAR: dragging one handle
 * swings the other to the opposite direction, keeping its own length (the pen tool
 * creates smooth points with symmetric handles; an imported point is detected smooth
 * when its handles already sit on one straight line through the anchor).
 *
 * Closing segments: a stored CLOSE with no handles on the joining sides is just the
 * op (the painters draw the straight line back to the start). A closing segment WITH
 * handles is stored as an explicit C whose end point is the first anchor's position,
 * followed by CLOSE; on entry that end point merges back into the first anchor (its
 * in handle is the curve's second control point), so the pair round-trips.
 *
 * Everything here is pure and coordinate-space agnostic: the pen builder works in
 * frame fractions, the node editor in stored segment units. Unit tested (PenPathTest).
 */
data class PathAnchor(
    val x: Float, val y: Float,
    /** Bezier handle on the side of the PREVIOUS anchor (null = no handle: corner). */
    val inX: Float? = null, val inY: Float? = null,
    /** Bezier handle on the side of the NEXT anchor (null = no handle: corner). */
    val outX: Float? = null, val outY: Float? = null,
    /** Smooth point: the two handles stay on one straight line through the anchor. */
    val smooth: Boolean = false
) {
    val hasIn: Boolean get() = inX != null && inY != null
    val hasOut: Boolean get() = outX != null && outY != null
}

/** One continuous outline: its anchors in order, plus whether the last joins back to the first. */
data class PathSubpath(
    val anchors: List<PathAnchor> = emptyList(),
    val closed: Boolean = false
)

/** A whole editable outline (a stored path can hold several subpaths, one per MOVE). */
data class EditablePath(
    val subpaths: List<PathSubpath> = emptyList()
)

object PathEdit {

    // ------------------------------------------------------------------ segments <-> anchors

    /**
     * Parses stored [segments] into anchors. Malformed tails (a drawing op before any
     * MOVE) are tolerated by anchoring at the op's end point rather than dropping them.
     */
    fun fromSegments(segments: List<PathSeg>): EditablePath {
        val subs = ArrayList<PathSubpath>()
        var anchors = ArrayList<PathAnchor>()
        var closed = false
        fun flush() {
            if (anchors.isNotEmpty()) {
                // A curved closing segment lands exactly on the first anchor: that end
                // point is not a separate anchor — its in handle belongs to the first.
                if (closed && anchors.size >= 3) {
                    val f = anchors.first()
                    val l = anchors.last()
                    if (f.x == l.x && f.y == l.y) {
                        if (l.hasIn && !f.hasIn) anchors[0] = f.copy(inX = l.inX, inY = l.inY)
                        anchors.removeAt(anchors.lastIndex)
                    }
                }
                subs += PathSubpath(anchors.toList(), closed)
            }
            anchors = ArrayList()
            closed = false
        }
        fun setLast(a: PathAnchor) {
            if (anchors.isNotEmpty()) anchors[anchors.lastIndex] = a
        }
        fun lastOrStart(): PathAnchor {
            // A drawing op before any MOVE: the implicit current point is (0, 0),
            // the same assumption flattenPathSegments makes.
            if (anchors.isEmpty()) anchors += PathAnchor(0f, 0f)
            return anchors.last()
        }
        for (seg in segments) {
            val q = seg.pts
            when (seg.op) {
                PATH_OP_MOVE -> if (q.size >= 2) {
                    flush()
                    anchors += PathAnchor(q[0], q[1])
                }
                PATH_OP_LINE -> if (q.size >= 2) {
                    lastOrStart()
                    anchors += PathAnchor(q[0], q[1])
                }
                PATH_OP_QUAD -> if (q.size >= 4) {
                    val prev = lastOrStart()
                    val cx = q[0]; val cy = q[1]; val ex = q[2]; val ey = q[3]
                    // Exact degree elevation of the quadratic (prev, c, end) to a cubic.
                    val c1x = prev.x + 2f / 3f * (cx - prev.x)
                    val c1y = prev.y + 2f / 3f * (cy - prev.y)
                    val c2x = ex + 2f / 3f * (cx - ex)
                    val c2y = ey + 2f / 3f * (cy - ey)
                    setLast(prev.copy(outX = c1x, outY = c1y))
                    anchors += PathAnchor(ex, ey, inX = c2x, inY = c2y)
                }
                PATH_OP_CUBIC -> if (q.size >= 6) {
                    val prev = lastOrStart()
                    setLast(prev.copy(outX = q[0], outY = q[1]))
                    anchors += PathAnchor(q[4], q[5], inX = q[2], inY = q[3])
                }
                PATH_OP_CLOSE -> {
                    closed = true
                    flush()
                }
            }
        }
        flush()
        return EditablePath(subs.map { sp ->
            sp.copy(anchors = sp.anchors.map { a ->
                if (a.hasIn && a.hasOut) a.copy(smooth = isSmooth(a)) else a
            })
        })
    }

    /** True when the anchor's two handles sit on one straight line through it, pointing opposite ways. */
    private fun isSmooth(a: PathAnchor): Boolean {
        val ax = a.inX!! - a.x; val ay = a.inY!! - a.y
        val bx = a.outX!! - a.x; val by = a.outY!! - a.y
        val la = hypot(ax, ay); val lb = hypot(bx, by)
        if (la < 1e-9f || lb < 1e-9f) return false
        val cross = abs(ax * by - ay * bx)
        val dot = ax * bx + ay * by
        return dot < 0f && cross <= 1e-3f * la * lb + 1e-9f
    }

    /** One stored segment between anchors [a] and [b]: a line only when neither side has a handle. */
    private fun segBetween(a: PathAnchor, b: PathAnchor): PathSeg =
        if (!a.hasOut && !b.hasIn) {
            PathSeg(PATH_OP_LINE, listOf(b.x, b.y))
        } else {
            PathSeg(
                PATH_OP_CUBIC,
                listOf(a.outX ?: a.x, a.outY ?: a.y, b.inX ?: b.x, b.inY ?: b.y, b.x, b.y)
            )
        }

    /** Writes anchors back to stored segments (M / L / C / Z only — never Q). */
    fun toSegments(path: EditablePath): List<PathSeg> {
        val out = ArrayList<PathSeg>()
        for (sp in path.subpaths) {
            val n = sp.anchors.size
            if (n == 0) continue
            val first = sp.anchors[0]
            out += PathSeg(PATH_OP_MOVE, listOf(first.x, first.y))
            if (n == 1) continue
            for (i in 0 until n - 1) out += segBetween(sp.anchors[i], sp.anchors[i + 1])
            if (sp.closed) {
                // A handle-less closing segment is the bare CLOSE (the painters draw
                // the straight line back); a handled one is written out as a curve
                // landing on the first anchor, then CLOSE.
                val wrap = segBetween(sp.anchors[n - 1], first)
                if (wrap.op == PATH_OP_CUBIC) out += wrap
                out += PathSeg(PATH_OP_CLOSE)
            }
        }
        return out
    }

    // ------------------------------------------------------------------ edits

    private fun mapSubpath(path: EditablePath, sub: Int, f: (PathSubpath) -> PathSubpath): EditablePath {
        if (sub !in path.subpaths.indices) return path
        return EditablePath(path.subpaths.mapIndexed { i, sp -> if (i == sub) f(sp) else sp })
    }

    private fun mapAnchor(sp: PathSubpath, idx: Int, f: (PathAnchor) -> PathAnchor): PathSubpath {
        if (idx !in sp.anchors.indices) return sp
        return sp.copy(anchors = sp.anchors.mapIndexed { i, a -> if (i == idx) f(a) else a })
    }

    /** Moves an anchor; its handles travel with it (the curve keeps its shape). */
    fun moveAnchor(path: EditablePath, sub: Int, idx: Int, x: Float, y: Float): EditablePath =
        mapSubpath(path, sub) { sp ->
            mapAnchor(sp, idx) { a ->
                val dx = x - a.x; val dy = y - a.y
                a.copy(
                    x = x, y = y,
                    inX = a.inX?.plus(dx), inY = a.inY?.plus(dy),
                    outX = a.outX?.plus(dx), outY = a.outY?.plus(dy)
                )
            }
        }

    /**
     * Moves one handle of an anchor to ([x], [y]). On a smooth point the other handle
     * swings to stay collinear (opposite direction), keeping its own length; on a
     * corner point only the dragged handle moves.
     */
    fun moveHandle(path: EditablePath, sub: Int, idx: Int, out: Boolean, x: Float, y: Float): EditablePath =
        mapSubpath(path, sub) { sp ->
            mapAnchor(sp, idx) { a ->
                var r = if (out) a.copy(outX = x, outY = y) else a.copy(inX = x, inY = y)
                if (a.smooth) {
                    // Vector from the dragged handle through the anchor, extended past it:
                    // the other handle must sit on that ray, at its own distance.
                    val dx = a.x - x; val dy = a.y - y
                    val len = hypot(dx, dy)
                    if (len > 1e-9f) {
                        if (out && r.hasIn) {
                            val ol = hypot(r.inX!! - a.x, r.inY!! - a.y)
                            r = r.copy(inX = a.x + dx / len * ol, inY = a.y + dy / len * ol)
                        } else if (!out && r.hasOut) {
                            val ol = hypot(r.outX!! - a.x, r.outY!! - a.y)
                            r = r.copy(outX = a.x + dx / len * ol, outY = a.y + dy / len * ol)
                        }
                    }
                }
                r
            }
        }

    /**
     * Splits the segment that STARTS at anchor [seg] of subpath [sub] at fraction [t],
     * inserting a new anchor there (de Casteljau — the curve does not change shape).
     * On a closed subpath [seg] may be the last anchor (the closing segment). Returns
     * the new path and the inserted anchor's index (−1 when there is no such segment).
     * A split line stays two lines (the new anchor is a corner); a split curve gives
     * the new anchor smooth handles lying on the curve.
     */
    fun splitSegment(path: EditablePath, sub: Int, seg: Int, t: Float): Pair<EditablePath, Int> {
        if (sub !in path.subpaths.indices) return path to -1
        val sp = path.subpaths[sub]
        val n = sp.anchors.size
        val segCount = if (sp.closed) n else n - 1
        if (n < 2 || seg !in 0 until segCount) return path to -1
        val a = sp.anchors[seg]
        val b = sp.anchors[(seg + 1) % n]
        val tt = t.coerceIn(0.02f, 0.98f)
        fun lerp(p: Float, q: Float) = p + (q - p) * tt
        val p0x = a.x; val p0y = a.y
        val p1x = a.outX ?: a.x; val p1y = a.outY ?: a.y
        val p2x = b.inX ?: b.x; val p2y = b.inY ?: b.y
        val p3x = b.x; val p3y = b.y
        val q0x = lerp(p0x, p1x); val q0y = lerp(p0y, p1y)
        val q1x = lerp(p1x, p2x); val q1y = lerp(p1y, p2y)
        val q2x = lerp(p2x, p3x); val q2y = lerp(p2y, p3y)
        val r0x = lerp(q0x, q1x); val r0y = lerp(q0y, q1y)
        val r1x = lerp(q1x, q2x); val r1y = lerp(q1y, q2y)
        val sx = lerp(r0x, r1x); val sy = lerp(r0y, r1y)
        val wasLine = !a.hasOut && !b.hasIn
        val list = sp.anchors.toMutableList()
        if (!wasLine) {
            list[seg] = a.copy(outX = q0x, outY = q0y)
            val bi = (seg + 1) % n
            list[bi] = list[bi].copy(inX = q2x, inY = q2y)
        }
        val inserted = PathAnchor(
            sx, sy,
            inX = if (wasLine) null else r0x, inY = if (wasLine) null else r0y,
            outX = if (wasLine) null else r1x, outY = if (wasLine) null else r1y,
            smooth = !wasLine
        )
        list.add(seg + 1, inserted)
        return mapSubpath(path, sub) { it.copy(anchors = list.toList()) } to (seg + 1)
    }

    /**
     * Removes an anchor; the two segments beside it merge into one (the neighbours'
     * handles now face each other). Refused when the subpath would drop below a
     * drawable minimum: 2 anchors for an open path, 3 for a closed one.
     */
    fun deleteAnchor(path: EditablePath, sub: Int, idx: Int): EditablePath {
        if (sub !in path.subpaths.indices) return path
        val sp = path.subpaths[sub]
        if (idx !in sp.anchors.indices) return path
        if (sp.closed && sp.anchors.size <= 3) return path
        if (!sp.closed && sp.anchors.size <= 2) return path
        return mapSubpath(path, sub) { it.copy(anchors = it.anchors.filterIndexed { i, _ -> i != idx }) }
    }
}

/**
 * The point-based Pen tool's builder (build 177): the anchors the user places on the
 * canvas (frame fractions, like the freehand Draw's points) become a PATH shape layer
 * through the exact normalisation the freehand tool uses ([VectorDraw.normalize]), so
 * a pen layer is the same storage as a drawn or imported path and every existing tool
 * (fill, stroke, trim, masks, effects, keyframes) works on it unchanged.
 *
 * The interaction the model encodes: a TAP adds a corner anchor (straight segments);
 * a TAP-DRAG pulls symmetric bezier handles out of the new anchor (a smooth point,
 * curved segments); finishing with the path closed makes it a filled shape, open
 * leaves it stroke-only — the freehand tool's own rule.
 */
object PenPath {

    /** A plain tap: a corner anchor at ([x], [y]) (frame fractions). */
    fun tap(anchors: List<PathAnchor>, x: Float, y: Float): List<PathAnchor> =
        anchors + PathAnchor(x, y)

    /**
     * The finger drags after a tap: the last anchor grows handles — the out handle at
     * the finger, the in handle mirrored symmetrically through the anchor.
     */
    fun pull(anchors: List<PathAnchor>, x: Float, y: Float): List<PathAnchor> {
        val a = anchors.lastOrNull() ?: return anchors
        return anchors.dropLast(1) + a.copy(
            outX = x, outY = y,
            inX = 2f * a.x - x, inY = 2f * a.y - y,
            smooth = true
        )
    }

    /** A tap that never turned into a drag: the last anchor keeps no handles. */
    fun releaseTap(anchors: List<PathAnchor>): List<PathAnchor> {
        val a = anchors.lastOrNull() ?: return anchors
        if (!a.hasIn && !a.hasOut) return anchors
        return anchors.dropLast(1) + a.copy(inX = null, inY = null, outX = null, outY = null, smooth = false)
    }

    /** Removes the last placed anchor (the pen panel's Undo point). */
    fun undoPoint(anchors: List<PathAnchor>): List<PathAnchor> = anchors.dropLast(1)

    /**
     * Builds the layer geometry for the placed [anchors] (frame fractions) on a canvas
     * of [canvasW] x [canvasH] stored units. Null with fewer than two anchors or no extent.
     */
    fun build(
        anchors: List<PathAnchor>, closed: Boolean,
        canvasW: Float, canvasH: Float, density: Float
    ): VectorDraw.DrawnPath? {
        if (anchors.size < 2 || canvasW <= 0f || canvasH <= 0f) return null
        val aspect = canvasW / canvasH
        // Uniform space (u = x * aspect, v = y) — the space the segments are normalised in.
        fun conv(v: Float?, xAxis: Boolean): Float? = v?.let { if (xAxis) it * aspect else it }
        val uni = anchors.map { a ->
            PathAnchor(
                a.x * aspect, a.y,
                inX = conv(a.inX, true), inY = a.inY,
                outX = conv(a.outX, true), outY = a.outY,
                smooth = a.smooth
            )
        }
        val raw = PathEdit.toSegments(EditablePath(listOf(PathSubpath(uni, closed))))
        return VectorDraw.normalize(raw, canvasW, canvasH, density)
    }
}
