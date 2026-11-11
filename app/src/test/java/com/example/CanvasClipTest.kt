package com.example

import com.example.engine.CanvasClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The final-frame clip (engine/CanvasClip.kt): the rule the frame composites apply — the C++
 * renderer with its canvas scissor (preview + export) and LayerPainter with a clip to the same
 * rect. A layer fully outside the canvas contributes nothing; a layer crossing an edge is cut
 * exactly at the edge.
 */
class CanvasClipTest {

    private fun assertRect(want: FloatArray, got: FloatArray?) {
        assertTrue("expected a rect, got null", got != null)
        assertEquals(want.size, got!!.size)
        for (i in want.indices) assertEquals(want[i], got[i], 0.001f)
    }

    @Test
    fun frameRectIsTheWholeCanvas() {
        assertRect(floatArrayOf(0f, 0f, 1920f, 1080f), CanvasClip.frameRect(1920, 1080))
        assertRect(floatArrayOf(0f, 0f, 1080f, 1920f), CanvasClip.frameRect(1080, 1920))
    }

    @Test
    fun layerFullyOutsideTheCanvasContributesNothing() {
        // The ST Intro case: an element parked outside the scene area (past the right edge).
        assertNull(CanvasClip.intersectFrame(2000f, 100f, 2300f, 400f, 1920, 1080))
        // Fully past each other edge, and one that only touches the edge (zero area on canvas).
        assertNull(CanvasClip.intersectFrame(-300f, 100f, -10f, 400f, 1920, 1080))
        assertNull(CanvasClip.intersectFrame(100f, -300f, 400f, -10f, 1920, 1080))
        assertNull(CanvasClip.intersectFrame(100f, 1200f, 400f, 1500f, 1920, 1080))
        assertNull(CanvasClip.intersectFrame(1920f, 100f, 2100f, 400f, 1920, 1080))
        assertNull(CanvasClip.intersectFrame(100f, 1080f, 400f, 1200f, 1920, 1080))
    }

    @Test
    fun layerCrossingAnEdgeIsCutExactlyAtTheEdge() {
        // Crosses the right and top edges: the visible part runs to x = 1920 and starts at y = 0.
        assertRect(
            floatArrayOf(1800f, 0f, 1920f, 300f),
            CanvasClip.intersectFrame(1800f, -50f, 2100f, 300f, 1920, 1080)
        )
        // Crosses the left and bottom edges.
        assertRect(
            floatArrayOf(0f, 900f, 200f, 1080f),
            CanvasClip.intersectFrame(-120f, 900f, 200f, 1400f, 1920, 1080)
        )
        // Bigger than the canvas on every side: the whole canvas, nothing more.
        assertRect(
            floatArrayOf(0f, 0f, 1920f, 1080f),
            CanvasClip.intersectFrame(-100f, -100f, 2100f, 1200f, 1920, 1080)
        )
    }

    @Test
    fun layerInsideTheCanvasIsUnchanged() {
        assertRect(
            floatArrayOf(100f, 100f, 500f, 400f),
            CanvasClip.intersectFrame(100f, 100f, 500f, 400f, 1920, 1080)
        )
    }
}
