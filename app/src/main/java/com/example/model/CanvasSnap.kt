package com.example.model

import kotlin.math.abs

/**
 * Canvas snapping while a layer is dragged on the preview canvas (build 176 — Sarowar's
 * tool 3 from the missing-tools audit). Pure decision logic in canvas pixels: given the
 * dragged layer's axis-aligned rect, the canvas size and the other layers' rects, decide
 * the correction that snaps the layer and which guide lines to show.
 *
 * Per axis independently, the dragged rect's centre and two edges are matched against
 * the canvas centre, the canvas edges and every other rect's centre / edges; the closest
 * match inside [thresholdPx] wins (ties keep the earliest candidate: canvas centre first,
 * then canvas edges, then other layers — and the centre feature before the edges).
 */
data class SnapRect(
    val cx: Float,
    val cy: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    companion object {
        /** A rect from its centre and size (a zero size gives a centre-only rect). */
        fun of(cx: Float, cy: Float, w: Float, h: Float): SnapRect =
            SnapRect(cx, cy, cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }
}

/** [dx] / [dy]: the correction to add to the dragged rect's centre. [guideX] / [guideY]: the canvas x / y of the active vertical / horizontal guide line, or null. */
data class SnapResult(val dx: Float, val dy: Float, val guideX: Float?, val guideY: Float?)

object CanvasSnap {

    fun snap(
        rect: SnapRect,
        canvasW: Float,
        canvasH: Float,
        others: List<SnapRect>,
        thresholdPx: Float
    ): SnapResult {
        val xCandidates = ArrayList<Float>(3 + others.size * 3)
        xCandidates += canvasW / 2f
        xCandidates += 0f
        xCandidates += canvasW
        for (o in others) {
            xCandidates += o.cx
            xCandidates += o.left
            xCandidates += o.right
        }
        val yCandidates = ArrayList<Float>(3 + others.size * 3)
        yCandidates += canvasH / 2f
        yCandidates += 0f
        yCandidates += canvasH
        for (o in others) {
            yCandidates += o.cy
            yCandidates += o.top
            yCandidates += o.bottom
        }
        val (dx, guideX) = bestAxis(listOf(rect.cx, rect.left, rect.right), xCandidates, thresholdPx)
        val (dy, guideY) = bestAxis(listOf(rect.cy, rect.top, rect.bottom), yCandidates, thresholdPx)
        return SnapResult(dx, dy, guideX, guideY)
    }

    /** The smallest in-threshold correction over every feature x candidate pair, and the winning candidate (the guide line). */
    private fun bestAxis(features: List<Float>, candidates: List<Float>, thresholdPx: Float): Pair<Float, Float?> {
        var bestDelta = 0f
        var bestGuide: Float? = null
        var bestAbs = Float.MAX_VALUE
        for (f in features) {
            for (c in candidates) {
                val d = c - f
                val a = abs(d)
                if (a <= thresholdPx && a < bestAbs) {
                    bestAbs = a
                    bestDelta = d
                    bestGuide = c
                }
            }
        }
        return bestDelta to bestGuide
    }
}
