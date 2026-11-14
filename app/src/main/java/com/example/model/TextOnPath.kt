package com.example.model

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Text-on-path (build 191): a text layer attached to a PATH shape layer
 * ([TimelineClip.textPathLayerId]) lays its glyphs along that layer's outline — each glyph
 * sits on the curve at its arc-length position, rotated to the curve's tangent there.
 *
 * This file is the pure layout law, shared by every renderer of an attached text layer:
 *  - the C++ text engine (its `PathWalk` in text_layout.cpp implements the same walk over
 *    the same polyline — the numbers pinned here are the numbers it must reproduce),
 *  - the Canvas fallback in LayerPainter (its per-paragraph offsets come from [layout]).
 *
 * THE LAW
 *  - The path is a polyline: the stored segments flattened by [flattenPathSegments]
 *    (24 samples per curve — the model's standard flattening, the same one the importer's
 *    bounds and the Pen tool use; chord error at 24 samples is far below a pixel at text
 *    sizes). All distances are arc lengths along that polyline.
 *  - Glyphs (in the engine: shaped clusters) advance along the path in reading order by
 *    their advance widths — the advances already include the layer's letter spacing, so
 *    spacing composes with the path unchanged.
 *  - A glyph is drawn iff its ORIGIN's arc distance is < the path's total length: text
 *    longer than the path is clipped at the path's end (a glyph whose origin is inside
 *    is drawn whole, even when its ink crosses the end slightly).
 *  - The tangent at an exact joint between two segments is the OUTGOING segment's
 *    tangent (the segment the pen is about to travel), except at the very end of the
 *    path, where it is the last segment's.
 *  - [TimelineClip.textPathStart] is a fraction of the total length: the first glyph's
 *    origin sits at start x total. Distances past the end clamp onto the end point.
 *
 * No Android types — unit tested (TextOnPathTest).
 */
object TextOnPath {

    /** A point on the path at an arc distance: position + tangent angle (degrees, y down, 0 = +x). */
    data class PathPoint(val x: Float, val y: Float, val angleDeg: Float)

    /** One laid-out cluster: its anchor (the point at its start distance) + whether any of it is drawn. */
    data class Placement(
        val startDist: Float,
        val x: Float,
        val y: Float,
        val angleDeg: Float,
        val drawn: Boolean
    )

    /**
     * Arc-length table over a polyline (frame pixels, y down). Consecutive duplicate
     * points are dropped (they carry neither arc nor tangent). A table over fewer than
     * 2 distinct points has total 0 and places nothing.
     */
    class ArcTable(points: List<Pair<Float, Float>>) {
        val points: List<Pair<Float, Float>>
        private val cum: FloatArray // cum[i] = arc length at points[i]; cum[0] = 0
        val total: Float

        init {
            val kept = ArrayList<Pair<Float, Float>>(points.size)
            for (p in points) {
                val last = kept.lastOrNull()
                if (last == null || last.first != p.first || last.second != p.second) kept += p
            }
            this.points = kept
            cum = FloatArray(kept.size)
            var acc = 0f
            for (i in 1 until kept.size) {
                acc += hypot(kept[i].first - kept[i - 1].first, kept[i].second - kept[i - 1].second)
                cum[i] = acc
            }
            total = acc
        }

        /** The point at arc distance [d] (clamped to [0, total]); see the joint rule in the class doc. */
        fun pointAt(d: Float): PathPoint {
            if (points.isEmpty()) return PathPoint(0f, 0f, 0f)
            if (points.size == 1 || total <= 0f) return PathPoint(points[0].first, points[0].second, 0f)
            val dd = d.coerceIn(0f, total)
            // First segment whose END is strictly past dd: at an exact joint this is the
            // outgoing segment. dd == total falls through to the last segment.
            var lo = 0
            var hi = points.size - 2
            var seg = points.size - 2
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (cum[mid + 1] > dd) { seg = mid; hi = mid - 1 } else lo = mid + 1
            }
            val a = points[seg]
            val b = points[seg + 1]
            val segLen = cum[seg + 1] - cum[seg]
            val t = if (segLen > 0f) (dd - cum[seg]) / segLen else 0f
            val angle = Math.toDegrees(atan2((b.second - a.second).toDouble(), (b.first - a.first).toDouble())).toFloat()
            return PathPoint(a.first + (b.first - a.first) * t, a.second + (b.second - a.second) * t, angle)
        }
    }

    fun arcTable(points: List<Pair<Float, Float>>): ArcTable = ArcTable(points)

    /** The arc table of a stored outline: segments flattened at the model's standard rate. */
    fun arcTableOfSegments(segments: List<PathSeg>, stepsPerCurve: Int = 24): ArcTable =
        ArcTable(flattenPathSegments(segments, stepsPerCurve))

    /**
     * Lays [advances] (cluster advances, in the same units as the table — they already
     * include letter spacing) along [table], starting at [startFrac] x the total length.
     * One [Placement] per advance, in order; a cluster is drawn iff its start distance is
     * inside the path (the engine refines this to glyph origins — same rule, finer grain).
     */
    fun layout(advances: List<Float>, startFrac: Float, table: ArcTable): List<Placement> {
        val out = ArrayList<Placement>(advances.size)
        var pen = startFrac.coerceIn(0f, 1f) * table.total
        for (adv in advances) {
            val p = table.pointAt(pen)
            out += Placement(pen, p.x, p.y, p.angleDeg, table.total > 0f && pen < table.total)
            pen += adv
        }
        return out
    }
}

/**
 * True when this layer carries a drawable path outline a text layer can attach to: a SHAPE
 * layer whose stored segments are a Pen / freehand / imported path (shapePattern == "PATH").
 * Built-in kinds (circle, rect, ...) have parametric outlines, not stored segments, and are
 * not attachable in build 191.
 */
val TimelineClip.isPathBearing: Boolean
    get() = type == LayerType.SHAPE &&
        (shapePattern ?: "").uppercase() == "PATH" && pathSegments.isNotEmpty()
