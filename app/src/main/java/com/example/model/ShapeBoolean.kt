package com.example.model

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Shape boolean operations (build 192 — production-push item 5): combine two shape
 * layers' outlines into a new PATH shape layer (Union / Subtract / Intersect / XOR).
 *
 * THE CORE works on flattened rings — lists of [BoolPt] in a shared coordinate space,
 * each ring an implicitly closed polygon (the last point joins back to the first; the
 * closing point is NOT repeated). A region is a list of rings read under the NON-ZERO
 * WINDING rule, because that is the rule the renderer fills shape outlines with:
 * `LayerPainter.drawShape` replays a PATH shape's segments into an
 * `android.graphics.Path` and fills it with the platform default fill type (WINDING =
 * non-zero — the painter's own comment: "The fill follows the path's own winding"), and
 * nothing anywhere in the app sets an even-odd fill for shapes. So a hole is a subpath
 * wound against its outer ring, a same-winding nested subpath is solid, and a
 * self-overlapping outline (a pentagram) fills its centre — and the boolean's
 * inside/outside test below is exactly that winding test, so what the user sees is
 * what gets combined.
 *
 * THE ALGORITHM is the segment-arrangement method: every edge of both regions is split
 * at every intersection (proper crossings, shared vertices, collinear overlaps — and
 * self-crossings inside one region, so a pentagram's edges are classified piecewise).
 * Each resulting sub-segment is classified by sampling the winding numbers of BOTH
 * original regions a hair to its left and right (direct point queries against the
 * untouched inputs, which is what makes coincident / shared edges classify cleanly);
 * a sub-segment is a result boundary iff the op's inside test differs across it, and
 * it is oriented so the result's interior lies on its LEFT (positive-area outer rings,
 * negative-area holes — the representation the non-zero fill renders back identically).
 * Kept segments are then stitched into rings by a face walk (at a vertex, the outgoing
 * edge whose direction is closest counter-clockwise-before the reversed incoming edge;
 * a walk that returns to its start vertex closes — two lobes touching at a point come
 * out as two rings, which render the same as one pinched ring).
 *
 * NUMERICAL HONESTY: all maths is Double. Intersections computed from a pair are
 * inserted into both edges as the SAME point instance (interned), and a crossing that
 * lands on an existing endpoint reuses that endpoint, so stitching joins match exactly
 * in the ordinary cases. Near-zero edges are collapsed before splitting and in the
 * output; collinear middle points are removed. A truly degenerate input (a zero-area
 * outline, a sliver result under the area floor) yields an empty region — an
 * acceptable, documented outcome; the core never emits a non-finite or self-evidently
 * corrupt ring (every emitted ring has >= 3 points and area above the floor).
 *
 * Deliberately NOT modelled (outline-level op): a shape's stroke (only its filled
 * region combines), its `drawing.progress` trim window, its own masks and its effects
 * — those paint over / gate the outline but are not the outline. An open PATH subpath
 * counts as its implicitly closed region — exactly what the fill paints for it, since
 * the platform fill closes open subpaths; a straight open line has ~zero area and so
 * reads as an empty region (ineligible), which is the honest answer for a stroke-only
 * path.
 */
enum class ShapeBooleanOp(val displayName: String) {
    UNION("Union"),
    SUBTRACT("Subtract"),
    INTERSECT("Intersect"),
    XOR("XOR");

    /** The op's inside test over (inside A, inside B). SUBTRACT is A − B: order matters. */
    fun inside(inA: Boolean, inB: Boolean): Boolean = when (this) {
        UNION -> inA || inB
        SUBTRACT -> inA && !inB
        INTERSECT -> inA && inB
        XOR -> inA != inB
    }
}

/** A point of the boolean core (Double; the model works in Float everywhere else). */
data class BoolPt(val x: Double, val y: Double) {
    operator fun plus(o: BoolPt) = BoolPt(x + o.x, y + o.y)
    operator fun minus(o: BoolPt) = BoolPt(x - o.x, y - o.y)
    operator fun times(s: Double) = BoolPt(x * s, y * s)
}

object ShapeBoolean {

    /** Signed area of one ring (positive = the winding that fills its interior with +1). */
    fun signedArea(ring: List<BoolPt>): Double {
        var s = 0.0
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            s += a.x * b.y - b.x * a.y
        }
        return s / 2.0
    }

    /** Total signed area of a region (outer rings positive, holes negative). */
    fun regionArea(region: List<List<BoolPt>>): Double = region.sumOf { signedArea(it) }

    /**
     * The non-zero winding number of [p] against [region]'s rings as authored (the
     * standard upward/downward crossing count; a point is inside iff this is != 0).
     */
    fun windingNumber(p: BoolPt, region: List<List<BoolPt>>): Int {
        var wn = 0
        for (ring in region) {
            val n = ring.size
            if (n < 2) continue
            for (i in 0 until n) {
                val a = ring[i]
                val b = ring[(i + 1) % n]
                if (a.y <= p.y) {
                    if (b.y > p.y && cross(b - a, p - a) > 0.0) wn++
                } else {
                    if (b.y <= p.y && cross(b - a, p - a) < 0.0) wn--
                }
            }
        }
        return wn
    }

    /** Inside test under the renderer's rule (non-zero winding). */
    fun isInside(p: BoolPt, region: List<List<BoolPt>>): Boolean = windingNumber(p, region) != 0

    /**
     * Combines regions [a] and [b] under [op]. SUBTRACT is [a] − [b]. The result is a
     * list of rings oriented positive-outer / negative-hole; it may be empty (an
     * intersect of disjoint shapes, a subtract that removes everything, ...).
     */
    fun combine(
        a: List<List<BoolPt>>,
        b: List<List<BoolPt>>,
        op: ShapeBooleanOp
    ): List<List<BoolPt>> {
        val extent = extentOf(a, b)
        val tol = 1e-9 * maxOf(1.0, extent)
        val minArea = (1e-6 * extent) * (1e-6 * extent)
        val ra = cleanRegion(a, tol)
        val rb = cleanRegion(b, tol)
        if (ra.isEmpty() || rb.isEmpty()) {
            // One side has no region: the ops degenerate to plain set logic.
            return when (op) {
                ShapeBooleanOp.UNION -> if (ra.isEmpty()) outputOrEmpty(rb, minArea) else outputOrEmpty(ra, minArea)
                ShapeBooleanOp.INTERSECT -> emptyList()
                ShapeBooleanOp.SUBTRACT -> if (rb.isEmpty()) outputOrEmpty(ra, minArea) else emptyList()
                ShapeBooleanOp.XOR -> if (ra.isEmpty()) outputOrEmpty(rb, minArea) else outputOrEmpty(ra, minArea)
            }
        }

        // Intern every vertex so geometrically shared points are one instance.
        val intern = HashMap<BoolPt, BoolPt>()
        fun canon(p: BoolPt): BoolPt = intern.getOrPut(p) { p }
        val edges = ArrayList<Edge>()
        for (ring in ra + rb) {
            val pts = ring.map { canon(it) }
            for (i in pts.indices) {
                val p = pts[i]
                val q = pts[(i + 1) % pts.size]
                if (p !== q) edges += Edge(p, q)
            }
        }

        // Split every edge at every intersection with every other edge (cross-region,
        // intra-region and self-crossings alike; adjacent edges only re-add their
        // shared endpoint, which the parameter dedupe then drops).
        for (i in edges.indices) {
            for (j in i + 1 until edges.size) {
                splitPair(edges[i], edges[j], tol)
            }
        }

        // Unique sub-segments (coincident copies merge — the side test below is purely
        // geometric, so one representative classifies for all of them).
        val subs = LinkedHashMap<SubKey, Sub>()
        for (e in edges) {
            val cuts = e.sortedCuts()
            for (s in 0 until cuts.size - 1) {
                val p = cuts[s].second
                val q = cuts[s + 1].second
                if (p === q) continue
                if (dist(p, q) <= tol) continue
                val key = if (less(p, q)) SubKey(p, q) else SubKey(q, p)
                if (!subs.containsKey(key)) subs[key] = Sub(p, q)
            }
        }

        // Classify: kept iff the op's inside test differs across the segment, oriented
        // with the result's interior on the left.
        val kept = ArrayList<Sub>()
        val deltaBase = 0.001 * maxOf(1.0, extent / 1000.0)
        for (s in subs.values) {
            val d = s.b - s.a
            val len = hypot(d.x, d.y)
            if (len <= tol) continue
            val delta = min(len * 0.25, deltaBase)
            val nx = -d.y / len
            val ny = d.x / len
            val mid = BoolPt((s.a.x + s.b.x) / 2.0, (s.a.y + s.b.y) / 2.0)
            val inL = op.inside(
                isInside(BoolPt(mid.x + nx * delta, mid.y + ny * delta), ra),
                isInside(BoolPt(mid.x + nx * delta, mid.y + ny * delta), rb)
            )
            val inR = op.inside(
                isInside(BoolPt(mid.x - nx * delta, mid.y - ny * delta), ra),
                isInside(BoolPt(mid.x - nx * delta, mid.y - ny * delta), rb)
            )
            if (inL == inR) continue
            kept += if (inL) s else Sub(s.b, s.a)
        }

        // Stitch kept segments into rings (face walk; see the header).
        val outgoing = HashMap<BoolPt, MutableList<Sub>>()
        for (s in kept) outgoing.getOrPut(s.a) { ArrayList() } += s
        val used = HashSet<Sub>()
        val out = ArrayList<List<BoolPt>>()
        for (first in kept) {
            if (first in used) continue
            val ring = ArrayList<BoolPt>()
            var cur = first
            var closed = false
            var guard = kept.size + 1
            while (guard-- > 0) {
                used += cur
                ring += cur.a
                val v = cur.b
                if (v == first.a && ring.size >= 3) {
                    closed = true
                    break
                }
                val next = chooseNext(v, cur, outgoing, used)
                if (next == null) break
                cur = next
            }
            if (closed) {
                val cleaned = cleanRing(ring, tol, minArea)
                if (cleaned != null) out += cleaned
            }
            // An unclosed walk is a degenerate remainder: dropped, never emitted
            // half-open (its segments stay consumed so they cannot corrupt a later ring).
        }
        return out
    }

    // ----------------------------------------------------------------------------------
    // Internals
    // ----------------------------------------------------------------------------------

    private class Edge(val a: BoolPt, val b: BoolPt) {
        /** Split parameters (t along a->b -> the shared point instance). */
        val cuts = ArrayList<Pair<Double, BoolPt>>()

        init {
            cuts += 0.0 to a
            cuts += 1.0 to b
        }

        fun sortedCuts(): List<Pair<Double, BoolPt>> {
            val sorted = cuts.sortedBy { it.first }
            val out = ArrayList<Pair<Double, BoolPt>>(sorted.size)
            for (c in sorted) {
                val last = out.lastOrNull()
                if (last == null || dist(last.second, c.second) > 0.0) out += c
            }
            return out
        }
    }

    private data class SubKey(val a: BoolPt, val b: BoolPt)

    private class Sub(val a: BoolPt, val b: BoolPt)

    private fun cross(u: BoolPt, v: BoolPt): Double = u.x * v.y - u.y * v.x

    private fun dist(p: BoolPt, q: BoolPt): Double = hypot(p.x - q.x, p.y - q.y)

    private fun less(p: BoolPt, q: BoolPt): Boolean = p.x < q.x || (p.x == q.x && p.y < q.y)

    private fun extentOf(a: List<List<BoolPt>>, b: List<List<BoolPt>>): Double {
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (ring in a + b) for (p in ring) {
            minX = minOf(minX, p.x); minY = minOf(minY, p.y)
            maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y)
        }
        if (minX > maxX) return 1.0
        return hypot(maxX - minX, maxY - minY).coerceAtLeast(1.0)
    }

    /** Drops consecutive near-duplicate points and rings that cannot hold a region. */
    private fun cleanRegion(region: List<List<BoolPt>>, tol: Double): List<List<BoolPt>> {
        val out = ArrayList<List<BoolPt>>()
        for (ring in region) {
            val pts = ArrayList<BoolPt>(ring.size)
            for (p in ring) {
                if (pts.isEmpty() || dist(pts.last(), p) > tol) pts += p
            }
            while (pts.size > 1 && dist(pts.first(), pts.last()) <= tol) pts.removeAt(pts.size - 1)
            if (pts.size >= 3) out += pts
        }
        return out
    }

    /** Passthrough for the degenerate one-side-empty ops, with the same output hygiene. */
    private fun outputOrEmpty(region: List<List<BoolPt>>, minArea: Double): List<List<BoolPt>> {
        val tol = 0.0
        val out = ArrayList<List<BoolPt>>()
        for (ring in region) {
            val cleaned = cleanRing(ring, tol, minArea)
            if (cleaned != null) out += cleaned
        }
        return out
    }

    /**
     * Output hygiene for one stitched ring: drop collinear middle points (a straight
     * continuation within float dust) and refuse rings under the area floor.
     */
    private fun cleanRing(ring: List<BoolPt>, tol: Double, minArea: Double): List<BoolPt>? {
        val pts = ArrayList<BoolPt>(ring)
        if (pts.size < 3) return null
        var changed = true
        while (changed && pts.size > 3) {
            changed = false
            var i = 0
            while (i < pts.size && pts.size > 3) {
                val prev = pts[(i - 1 + pts.size) % pts.size]
                val cur = pts[i]
                val next = pts[(i + 1) % pts.size]
                val u = cur - prev
                val v = next - cur
                val lu = hypot(u.x, u.y)
                val lv = hypot(v.x, v.y)
                val straight = lu > 0.0 && lv > 0.0 &&
                    abs(cross(u, v)) <= 1e-9 * lu * lv && (u.x * v.x + u.y * v.y) > 0.0
                if (straight || dist(cur, next) <= tol) {
                    pts.removeAt(i)
                    changed = true
                } else i++
            }
        }
        if (pts.size < 3) return null
        if (abs(signedArea(pts)) <= minArea) return null
        return pts
    }

    /**
     * Records the split points of one edge pair on both edges. Proper crossings insert
     * one shared point into both; a crossing on an existing endpoint reuses it;
     * collinear overlaps split both edges at the overlap's ends (the other edge's own
     * endpoints, so they are shared instances too).
     */
    private fun splitPair(e1: Edge, e2: Edge, tol: Double) {
        val r = e1.b - e1.a
        val s = e2.b - e2.a
        val lr = hypot(r.x, r.y)
        val ls = hypot(s.x, s.y)
        if (lr <= 0.0 || ls <= 0.0) return
        val denom = cross(r, s)
        val qp = e2.a - e1.a
        if (abs(denom) > 1e-12 * lr * ls) {
            val t = cross(qp, s) / denom
            val u = cross(qp, r) / denom
            val tt = tol / lr
            val uu = tol / ls
            if (t < -tt || t > 1.0 + tt || u < -uu || u > 1.0 + uu) return
            val tc = t.coerceIn(0.0, 1.0)
            val uc = u.coerceIn(0.0, 1.0)
            val p = when {
                tc == 0.0 -> e1.a
                tc == 1.0 -> e1.b
                uc == 0.0 -> e2.a
                uc == 1.0 -> e2.b
                else -> BoolPt(e1.a.x + r.x * tc, e1.a.y + r.y * tc)
            }
            e1.cuts += tc to p
            e2.cuts += uc to p
            return
        }
        // Parallel: only a collinear pair can still meet.
        if (abs(cross(qp, r)) > tol * lr) return
        // Project e2's endpoints onto e1's parameter (and vice versa).
        val tc = (qp.x * r.x + qp.y * r.y) / (lr * lr)
        val td = tc + (s.x * r.x + s.y * r.y) / (lr * lr)
        val lo = maxOf(0.0, minOf(tc, td))
        val hi = minOf(1.0, maxOf(tc, td))
        if (hi - lo <= tol / lr) return // touches at a point at most: the endpoint cuts suffice
        fun atE1(t: Double): BoolPt = when (t) {
            0.0 -> e1.a
            1.0 -> e1.b
            tc -> e2.a
            td -> e2.b
            else -> BoolPt(e1.a.x + r.x * t, e1.a.y + r.y * t)
        }
        e1.cuts += lo to atE1(lo)
        e1.cuts += hi to atE1(hi)
        // Parameters of e1.a / e1.b along e2, computed directly:
        val u1 = ((e1.a.x - e2.a.x) * s.x + (e1.a.y - e2.a.y) * s.y) / (ls * ls)
        val u2 = ((e1.b.x - e2.a.x) * s.x + (e1.b.y - e2.a.y) * s.y) / (ls * ls)
        val ulo = maxOf(0.0, minOf(u1, u2))
        val uhi = minOf(1.0, maxOf(u1, u2))
        fun atE2(u: Double): BoolPt = when (u) {
            0.0 -> e2.a
            1.0 -> e2.b
            u1 -> e1.a
            u2 -> e1.b
            else -> BoolPt(e2.a.x + s.x * u, e2.a.y + s.y * u)
        }
        if (uhi - ulo > tol / ls) {
            e2.cuts += ulo to atE2(ulo)
            e2.cuts += uhi to atE2(uhi)
        }
    }

    /**
     * The face-walk choice at vertex [v]: among the UNUSED outgoing kept segments, the
     * one whose direction is closest counter-clockwise-before the reversed incoming
     * direction (score = the counter-clockwise angle from the candidate back to the
     * reversed incoming direction, in (0, 2π]; the literal back-track scores the full
     * turn, so it is only ever the last resort). Ties break to the longer segment.
     */
    private fun chooseNext(
        v: BoolPt,
        incoming: Sub,
        outgoing: Map<BoolPt, List<Sub>>,
        used: Set<Sub>
    ): Sub? {
        val cands = outgoing[v] ?: return null
        val din = incoming.b - incoming.a
        val rev = atan2(-din.y, -din.x)
        var best: Sub? = null
        var bestScore = Double.POSITIVE_INFINITY
        var bestLen = -1.0
        for (c in cands) {
            if (c in used) continue
            val d = c.b - c.a
            val ang = atan2(d.y, d.x)
            var score = (rev - ang) % (2.0 * Math.PI)
            if (score <= 0.0) score += 2.0 * Math.PI
            val len = hypot(d.x, d.y)
            if (score < bestScore - 1e-12 || (abs(score - bestScore) <= 1e-12 && len > bestLen)) {
                best = c
                bestScore = score
                bestLen = len
            }
        }
        return best
    }
}

/**
 * A stored PATH outline as boolean rings: the segments are flattened at the model's
 * standard rate (24 samples per curve — the same points [flattenPathSegments] walks
 * and the shape's own fill draws through), split into subpaths at every MOVE. Every
 * subpath is one ring: an open subpath counts as its implicitly closed region, which
 * is what the platform fill paints for it. Points come back as Doubles in the stored
 * segments' own basis ([TimelineClip.pathSegments]: centred, the larger half extent
 * spanning [-1, 1]).
 */
fun flattenPathRings(segments: List<PathSeg>, stepsPerCurve: Int = 24): List<List<BoolPt>> {
    val rings = ArrayList<List<BoolPt>>()
    var ring = ArrayList<BoolPt>()
    var cur = BoolPt(0.0, 0.0)
    var start = cur
    fun flush() {
        // Drop a closing repeat of the first point (a CLOSE lands back on it).
        while (ring.size > 1 && ring.last() == ring.first()) ring.removeAt(ring.size - 1)
        if (ring.size >= 2) rings += ring
        ring = ArrayList()
    }
    fun quad(p0: BoolPt, p1: BoolPt, p2: BoolPt, t: Double): BoolPt {
        val u = 1.0 - t
        return BoolPt(
            u * u * p0.x + 2.0 * u * t * p1.x + t * t * p2.x,
            u * u * p0.y + 2.0 * u * t * p1.y + t * t * p2.y
        )
    }
    fun cubic(p0: BoolPt, p1: BoolPt, p2: BoolPt, p3: BoolPt, t: Double): BoolPt {
        val u = 1.0 - t
        return BoolPt(
            u * u * u * p0.x + 3.0 * u * u * t * p1.x + 3.0 * u * t * t * p2.x + t * t * t * p3.x,
            u * u * u * p0.y + 3.0 * u * u * t * p1.y + 3.0 * u * t * t * p2.y + t * t * t * p3.y
        )
    }
    for (seg in segments) {
        val q = seg.pts
        when (seg.op) {
            PATH_OP_MOVE -> if (q.size >= 2) {
                flush()
                cur = BoolPt(q[0].toDouble(), q[1].toDouble())
                start = cur
                ring += cur
            }
            PATH_OP_LINE -> if (q.size >= 2) {
                cur = BoolPt(q[0].toDouble(), q[1].toDouble())
                ring += cur
            }
            PATH_OP_QUAD -> if (q.size >= 4) {
                val c = BoolPt(q[0].toDouble(), q[1].toDouble())
                val e = BoolPt(q[2].toDouble(), q[3].toDouble())
                for (i in 1..stepsPerCurve) ring += quad(cur, c, e, i.toDouble() / stepsPerCurve)
                cur = e
            }
            PATH_OP_CUBIC -> if (q.size >= 6) {
                val c1 = BoolPt(q[0].toDouble(), q[1].toDouble())
                val c2 = BoolPt(q[2].toDouble(), q[3].toDouble())
                val e = BoolPt(q[4].toDouble(), q[5].toDouble())
                for (i in 1..stepsPerCurve) ring += cubic(cur, c1, c2, e, i.toDouble() / stepsPerCurve)
                cur = e
            }
            PATH_OP_CLOSE -> {
                ring += start
                cur = start
            }
        }
    }
    flush()
    return rings
}

/** Samples of a full circle / ellipse outline (the primitives are analytic in the
 * renderers — no stored flattening exists to copy; 64 is the boolean's own sampling). */
private const val PRIMITIVE_CURVE_SAMPLES = 64

/** Samples of one rounded-rect corner arc. */
private const val CORNER_ARC_SAMPLES = 16

/**
 * The outline of a built-in shape kind in the shape box's own basis (the half box =
 * 1 on both axes — the [TimelineClip.pathSegments] basis), mirroring the geometry the
 * renderers draw: `shape_engine.cpp`'s SDF kinds (via `ShapeNative`'s kind map —
 * RECT / ROUNDED family / TRIANGLE / PENTAGON / STAR exact vertices, OVAL = ellipse
 * 1.2 x 0.75, everything else — CIRCLE and any unknown name — a circle, exactly the
 * painters' own fallback) and `LayerPainter`'s replay for the same names. Rounded
 * corners take the clip's own radii ([TimelineClip.shapeCornerRadii] at base 1 — the
 * per-axis box-unit radii), else the engine's built-in corner (16/65 of the half box,
 * 10/65 for WIDE_ROUNDED_RECT — the values `ShapeNative` passes when none is stored).
 * Rings come back wound positive (their interior fills +1).
 */
fun primitiveShapeRings(clip: TimelineClip): List<List<BoolPt>> {
    fun ringOf(pts: List<Pair<Double, Double>>): List<BoolPt> = pts.map { BoolPt(it.first, it.second) }
    fun ellipse(rx: Double, ry: Double): List<BoolPt> {
        val out = ArrayList<BoolPt>(PRIMITIVE_CURVE_SAMPLES)
        for (i in 0 until PRIMITIVE_CURVE_SAMPLES) {
            val t = i.toDouble() / PRIMITIVE_CURVE_SAMPLES * 2.0 * Math.PI
            out += BoolPt(rx * cos(t), ry * sin(t))
        }
        return out
    }
    return when ((clip.shapePattern ?: "CIRCLE").uppercase()) {
        "RECT", "RECTANGLE", "SQUARE" ->
            listOf(ringOf(listOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0)))
        "ROUNDED", "ROUNDED_SQUARE", "ROUNDED_RECT", "WIDE_ROUNDED_RECT" -> {
            val stored = clip.shapeCornerRadii(1f)
            val def = if ((clip.shapePattern ?: "").uppercase() == "WIDE_ROUNDED_RECT") 10.0 / 65.0 else 16.0 / 65.0
            val rx = (stored?.first?.toDouble() ?: def).coerceIn(0.0, 1.0)
            val ry = (stored?.second?.toDouble() ?: def).coerceIn(0.0, 1.0)
            if (rx <= 0.0 || ry <= 0.0) {
                listOf(ringOf(listOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0)))
            } else {
                val out = ArrayList<BoolPt>()
                // Corners in walk order (positive winding): top-right, bottom-right,
                // bottom-left, top-left — each a quarter ellipse around its centre.
                val corners = listOf(
                    Triple(1.0 - rx, -1.0 + ry, -Math.PI / 2.0),
                    Triple(1.0 - rx, 1.0 - ry, 0.0),
                    Triple(-1.0 + rx, 1.0 - ry, Math.PI / 2.0),
                    Triple(-1.0 + rx, -1.0 + ry, Math.PI)
                )
                for ((cx, cy, a0) in corners) {
                    for (i in 0..CORNER_ARC_SAMPLES) {
                        val t = a0 + (i.toDouble() / CORNER_ARC_SAMPLES) * (Math.PI / 2.0)
                        val p = BoolPt(cx + rx * cos(t), cy + ry * sin(t))
                        if (out.isEmpty() || out.last() != p) out += p
                    }
                }
                // The walk's final arc point is the first point again: drop the repeat.
                while (out.size > 1 && out.last() == out.first()) out.removeAt(out.size - 1)
                listOf(out)
            }
        }
        "OVAL", "ELLIPSE" -> listOf(ellipse(1.2, 0.75))
        "TRIANGLE" -> listOf(ringOf(listOf(0.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0)))
        "PENTAGON" -> listOf((0 until 5).map { i ->
            val t = Math.toRadians(-90.0 + 72.0 * i)
            BoolPt(cos(t), sin(t))
        })
        "STAR" -> listOf((0 until 10).map { i ->
            val t = Math.toRadians(-90.0 + 36.0 * i)
            val r = if (i % 2 == 0) 1.0 else 0.45
            BoolPt(r * cos(t), r * sin(t))
        })
        else -> listOf(ellipse(1.0, 1.0)) // CIRCLE, and the painters' fallback for unknown kinds
    }
}

/**
 * This layer's filled region as boolean rings in the shape box's basis, or null when
 * the layer has no combinable region. The rules (see [ShapeBoolean]'s header): a
 * SHAPE layer with a fill (`fillMode != FILL_NONE` — a fill-less layer's only paint
 * is its stroke, which has no region); a PATH layer contributes [flattenPathRings] of
 * its stored segments, a built-in kind its [primitiveShapeRings]; and a crop window,
 * when one is set, cuts the region first (the painters clip the fill to the window in
 * the box's own space — the model's [cropVisibleRect] law at box 2 x 2, re-centred),
 * so the boolean sees the shape AS DRAWN. A region whose every ring is degenerate
 * (zero area — a straight open line, a fully cropped sliver) reads as null.
 *
 * The clip must be the layer AS EVALUATED at the playhead (keyframed corner radius,
 * a `.pie` / `.arc`'s rebuilt segments, ...) — the caller evaluates; this function
 * only reads the fields it is handed.
 */
fun TimelineClip.shapeBooleanRings(): List<List<BoolPt>>? {
    if (type != LayerType.SHAPE) return null
    if (fillMode == FILL_NONE) return null
    var rings = if ((shapePattern ?: "").uppercase() == "PATH") {
        if (pathSegments.isEmpty()) return null
        flattenPathRings(pathSegments)
    } else {
        primitiveShapeRings(this)
    }
    val win = cropVisibleRect(2f, 2f)
    if (win != null && (win.width < 2f - 1e-4f || win.height < 2f - 1e-4f)) {
        val l = win.left.toDouble() - 1.0
        val t = win.top.toDouble() - 1.0
        val r = win.right.toDouble() - 1.0
        val b = win.bottom.toDouble() - 1.0
        val rect = listOf(BoolPt(l, t), BoolPt(r, t), BoolPt(r, b), BoolPt(l, b))
        rings = ShapeBoolean.combine(rings, listOf(rect), ShapeBooleanOp.INTERSECT)
    }
    val real = rings.filter { it.size >= 3 && abs(ShapeBoolean.signedArea(it)) > 1e-9 }
    return real.ifEmpty { null }
}

/**
 * Cheap stored-fields eligibility for the boolean row (the playhead evaluation in
 * the engine can still refuse — a pose placement, behind the camera, a `.pie` closed
 * at this instant): a visible, non-mask SHAPE layer whose stored outline holds a
 * filled region. The selection / timing gates live with the caller.
 */
val TimelineClip.isBooleanShapeCandidate: Boolean
    get() = isVisible && !isMask && shapeBooleanRings() != null
