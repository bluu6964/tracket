package com.example.model

import kotlin.math.abs

/**
 * One layer's axis-aligned bounds on the canvas, in canvas pixels — the same visible
 * bounds the snapping measures (build 188): the caller builds these from the build-176
 * `snapRectOf` rects, so a cropped shape / text layer aligns by its CROPPED bounds, a
 * media layer by its visible (fitted, cropped) picture, and a picture-less layer
 * (Null / Controller / Camera) by its centre point (a zero-size rect).
 */
data class AlignRect(
    val id: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val cx: Float get() = (left + right) / 2f
    val cy: Float get() = (top + bottom) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** The spatial actions of the multi-select bar (build 188): six aligns + two distributes. */
enum class SpatialAction {
    ALIGN_LEFT, ALIGN_CENTER_H, ALIGN_RIGHT,
    ALIGN_TOP, ALIGN_CENTER_V, ALIGN_BOTTOM,
    DISTRIBUTE_H, DISTRIBUTE_V
}

/**
 * Canvas spatial align / distribute for multi-selected layers (build 188) — pure math in
 * canvas pixels: given the layers' rects and an action, the per-layer delta each layer
 * must move by. The caller converts a delta back to stored units (through the layer's
 * depth factor, the build-176/184 rule) and writes it through the ViewModel's transform
 * funnel, so keyframing / undo / Controller fan-out behave exactly like a manual move.
 *
 * Align reads against the selection's own bounding box: LEFT / TOP take the smallest
 * edge, RIGHT / BOTTOM the largest, and the two CENTRE actions the bounding box's
 * centre. Distribute spaces the layer CENTRES evenly between the two extreme centres
 * (which do not move) — equal centre spacing regardless of the layers' sizes.
 * Layers already in place get no entry, so an all-aligned selection is a no-op.
 */
object SpatialAlign {

    /** Deltas smaller than this (canvas px) count as "already there" and are dropped. */
    private const val EPS = 0.01f

    /**
     * The deltas for any [SpatialAction]; empty when the action cannot apply.
     * Build 198: a rect with ANY non-finite bound drops out first — one layer
     * with an extreme (infinite / NaN) evaluated bounds must not poison the
     * shared bounding box and fling every other selected layer with it.
     */
    fun deltas(rects: List<AlignRect>, action: SpatialAction): Map<String, Pair<Float, Float>> {
        val finite = rects.filter {
            it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite()
        }
        return when (action) {
            SpatialAction.DISTRIBUTE_H, SpatialAction.DISTRIBUTE_V -> distributeDeltas(finite, horizontal = action == SpatialAction.DISTRIBUTE_H)
            else -> alignDeltas(finite, action)
        }
    }

    /** Align needs at least 2 rects. */
    fun alignDeltas(rects: List<AlignRect>, action: SpatialAction): Map<String, Pair<Float, Float>> {
        if (rects.size < 2) return emptyMap()
        val minL = rects.minOf { it.left }
        val maxR = rects.maxOf { it.right }
        val minT = rects.minOf { it.top }
        val maxB = rects.maxOf { it.bottom }
        val midX = (minL + maxR) / 2f
        val midY = (minT + maxB) / 2f
        val out = LinkedHashMap<String, Pair<Float, Float>>()
        for (r in rects) {
            val dx = when (action) {
                SpatialAction.ALIGN_LEFT -> minL - r.left
                SpatialAction.ALIGN_RIGHT -> maxR - r.right
                SpatialAction.ALIGN_CENTER_H -> midX - r.cx
                else -> 0f
            }
            val dy = when (action) {
                SpatialAction.ALIGN_TOP -> minT - r.top
                SpatialAction.ALIGN_BOTTOM -> maxB - r.bottom
                SpatialAction.ALIGN_CENTER_V -> midY - r.cy
                else -> 0f
            }
            if (abs(dx) > EPS || abs(dy) > EPS) out[r.id] = dx to dy
        }
        return out
    }

    /** Distribute needs at least 3 rects and a non-zero span between the extreme centres. */
    fun distributeDeltas(rects: List<AlignRect>, horizontal: Boolean): Map<String, Pair<Float, Float>> {
        if (rects.size < 3) return emptyMap()
        val sorted = if (horizontal) rects.sortedBy { it.cx } else rects.sortedBy { it.cy }
        val centre: (AlignRect) -> Float = if (horizontal) { r -> r.cx } else { r -> r.cy }
        val first = centre(sorted.first())
        val last = centre(sorted.last())
        val span = last - first
        if (span <= EPS) return emptyMap()
        val step = span / (sorted.size - 1)
        val out = LinkedHashMap<String, Pair<Float, Float>>()
        sorted.forEachIndexed { i, r ->
            val d = first + step * i - centre(r)
            if (abs(d) > EPS) out[r.id] = if (horizontal) d to 0f else 0f to d
        }
        return out
    }
}
