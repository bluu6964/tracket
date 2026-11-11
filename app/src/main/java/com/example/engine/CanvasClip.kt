package com.example.engine

/**
 * The final-frame clip: nothing a layer draws may land outside the project canvas
 * (0, 0, frameW, frameH in frame pixels) — the same rule Alight Motion applies to its scene,
 * where content placed outside the scene area never shows in the exported frame.
 *
 * The clip is applied at the frame-composite level, not per layer: the C++ renderer keeps a
 * scissor on the canvas rect enabled for the whole frame (renderer.cpp, beginFrame; the preview
 * and the export share that renderer), and [LayerPainter] clips the canvas it draws on to
 * [frameRect]. In-frame bleed (a glow or shadow that stays inside the frame) is untouched; only
 * drawing outside the canvas is cut.
 *
 * This object is the shared Kotlin definition of that rectangle and of what "clipped to the
 * frame" means for an axis-aligned box, so the rule is pinned by tests without a device.
 */
object CanvasClip {

    /** [left, top, right, bottom] of the frame, in frame pixels. */
    fun frameRect(frameW: Int, frameH: Int): FloatArray = floatArrayOf(
        0f, 0f,
        frameW.coerceAtLeast(0).toFloat(), frameH.coerceAtLeast(0).toFloat()
    )

    /**
     * The part of the axis-aligned box (l, t, r, b) that lands on the canvas, or null when the
     * box is fully outside — it then contributes nothing to the rendered frame. A box crossing
     * an edge is cut exactly at that edge.
     */
    fun intersectFrame(
        l: Float, t: Float, r: Float, b: Float, frameW: Int, frameH: Int
    ): FloatArray? {
        val cl = maxOf(l, 0f)
        val ct = maxOf(t, 0f)
        val cr = minOf(r, frameW.coerceAtLeast(0).toFloat())
        val cb = minOf(b, frameH.coerceAtLeast(0).toFloat())
        if (cr <= cl || cb <= ct) return null
        return floatArrayOf(cl, ct, cr, cb)
    }
}
