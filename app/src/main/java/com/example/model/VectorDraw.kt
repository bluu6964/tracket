package com.example.model

import kotlin.math.hypot

/**
 * The freehand drawing tool (build 166 — Sarowar's directive 2026-11-13 ~20:22, item 1;
 * named FREEHAND, not vector, since his correction of 2026-11-14: vector drawing is the
 * point-based Pen tool, [PenPath]): a freehand stroke drawn on the canvas becomes a real
 * PATH shape layer
 * ([TimelineClip.shapePattern] == "PATH" + [TimelineClip.pathSegments]) — the same
 * storage the Alight importer writes for a `<path>` shape, so the layer renders through
 * the exact same painters in preview and export and stays editable with the existing
 * tools (Transform, Color fill, stroke via the shape fields, Effects, masks, trim ...).
 *
 * This file is the pure geometry: raw stroke points in frame fractions in, the stored
 * (normalised) segments plus the layer placement out. No Compose, no Android — unit
 * tested (VectorDrawTest).
 *
 * Coordinates: the input points are fractions of the frame (x: 0..1 of the width,
 * y: 0..1 of the height). All distance / bounds maths runs in a UNIFORM space
 * (u = x * aspect, v = y, so one unit is one frame height on both axes) — the same
 * trick [com.example.model.ClipMaskLibrary.fromPenPoints] uses for pen masks and the
 * importer uses for path shapes: the stored segments are centred on the outline's
 * bounding box and divided by its LARGER half extent (a uniform divisor, so a flat
 * stroke is not squashed), and [DrawnPath.shapeScale] carries the true size back.
 */
object VectorDraw {

    /** Consecutive raw points closer than this (uniform units) are one point: a finger never holds still. */
    const val MIN_STEP = 0.0035f

    /** A stroke needs at least this many distinct points. */
    const val MIN_POINTS = 2

    /**
     * The built layer geometry.
     * [segments] = the stored outline (the [TimelineClip.pathSegments] basis: centred on
     * the bounding box, divided by its larger half extent — coordinates span [-1, 1] on
     * the larger axis). [offsetX] / [offsetY] = the layer position in stored units (the
     * drawing's centre folded like the importer's: frame centre = 0). [shapeScale] = the
     * shapeScaleX = shapeScaleY value; with scalePercent = shapeScale * 100 the writers'
     * invariant holds, and the painters replay the segments at the drawn size (the shape
     * box is 130 dp: half = 65 * density stored px, hence the divisor).
     */
    class DrawnPath(
        val segments: List<PathSeg>,
        val offsetX: Float,
        val offsetY: Float,
        val shapeScale: Float,
        /** The flattened outline in frame fractions (the drawing as the user made it; for tests / previews). */
        val flatFrame: List<Pair<Float, Float>>
    )

    /**
     * Builds the layer geometry for a stroke of [rawPoints] (frame fractions) on a canvas
     * of [canvasW] x [canvasH] stored units with the given display [density].
     * [smooth] runs the stroke through midpoint-quadratic smoothing (the classic
     * draw-a-curve-through-the-points scheme: the curve passes through the midpoints of
     * consecutive raw points and bends at the raw points); without it the segments are
     * straight lines. [close] appends a CLOSE op (a filled shape instead of an open line).
     * Null when the stroke has too few distinct points or no extent.
     */
    fun build(
        rawPoints: List<Pair<Float, Float>>,
        canvasW: Float, canvasH: Float, density: Float,
        smooth: Boolean, close: Boolean
    ): DrawnPath? {
        if (canvasW <= 0f || canvasH <= 0f) return null
        val aspect = canvasW / canvasH
        // Uniform space: (x * aspect, y).
        val pts = ArrayList<Pair<Float, Float>>()
        for ((x, y) in rawPoints) {
            val u = x.coerceIn(0f, 1f) * aspect
            val v = y.coerceIn(0f, 1f)
            val last = pts.lastOrNull()
            if (last == null || hypot(u - last.first, v - last.second) >= MIN_STEP) pts += u to v
        }
        if (pts.size < MIN_POINTS) return null

        val raw = ArrayList<PathSeg>()
        raw += PathSeg(PATH_OP_MOVE, listOf(pts[0].first, pts[0].second))
        if (smooth && pts.size >= 3) {
            fun mid(a: Pair<Float, Float>, b: Pair<Float, Float>) = (a.first + b.first) / 2f to (a.second + b.second) / 2f
            raw += PathSeg(PATH_OP_LINE, mid(pts[0], pts[1]).toList())
            for (i in 1 until pts.size - 1) {
                val m = mid(pts[i], pts[i + 1])
                raw += PathSeg(PATH_OP_QUAD, listOf(pts[i].first, pts[i].second, m.first, m.second))
            }
            raw += PathSeg(PATH_OP_LINE, pts.last().toList())
        } else {
            for (i in 1 until pts.size) raw += PathSeg(PATH_OP_LINE, pts[i].toList())
        }
        if (close) raw += PathSeg(PATH_OP_CLOSE)

        return normalize(raw, canvasW, canvasH, density)
    }

    /**
     * Normalises raw segments drawn in uniform space (u = x * aspect, v = y) onto their
     * bounding box and works out the layer placement — the law the importer's
     * normalisePathSegs writes and both painters replay. Shared by the freehand stroke
     * (above) and the point-based Pen tool ([PenPath.build]), so a path layer's storage
     * means the same thing however it was drawn. Null when the outline has no extent.
     */
    fun normalize(
        raw: List<PathSeg>,
        canvasW: Float, canvasH: Float, density: Float
    ): DrawnPath? {
        if (canvasW <= 0f || canvasH <= 0f) return null
        val aspect = canvasW / canvasH
        val flat = flattenPathSegments(raw)
        if (flat.isEmpty()) return null
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for ((x, y) in flat) {
            minX = minOf(minX, x); minY = minOf(minY, y)
            maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
        }
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        val half = maxOf((maxX - minX) / 2f, (maxY - minY) / 2f)
        if (half < 1e-6f) return null
        val segments = raw.map { seg ->
            PathSeg(seg.op, seg.pts.mapIndexed { i, v -> if (i % 2 == 0) (v - cx) / half else (v - cy) / half })
        }
        // Placement: the box centre in frame fractions -> stored-unit offset from the frame
        // centre; the scale so the painters' 130 dp box (half = 65 * density) replays the
        // larger half extent at exactly `half` frame heights.
        val offsetX = (cx / aspect - 0.5f) * canvasW
        val offsetY = (cy - 0.5f) * canvasH
        val shapeScale = half * canvasH / (65f * density.coerceAtLeast(0.1f))
        val flatFrame = flat.map { (u, v) -> (u / aspect) to v }
        return DrawnPath(segments, offsetX, offsetY, shapeScale, flatFrame)
    }
}

private fun Pair<Float, Float>.toList(): List<Float> = listOf(first, second)
