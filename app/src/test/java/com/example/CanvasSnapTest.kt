package com.example

import com.example.model.CanvasSnap
import com.example.model.SnapRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Build 176 — the canvas snap rule (model/CanvasSnap.kt): centre / edge / other-layer
 * matching per axis, the threshold, and the winning-candidate choice. Canvas 1000x800,
 * threshold 16 px throughout.
 */
class CanvasSnapTest {

    private val w = 1000f
    private val h = 800f
    private val th = 16f

    @Test
    fun centre_snapsToCanvasCentre_guideShown() {
        val r = CanvasSnap.snap(SnapRect.of(505f, 300f, 100f, 100f), w, h, emptyList(), th)
        assertEquals(-5f, r.dx, 0f)
        assertEquals(500f, r.guideX!!, 0f)
        assertEquals(0f, r.dy, 0f)
        assertNull(r.guideY)
    }

    @Test
    fun edge_snapsToCanvasEdge() {
        val r = CanvasSnap.snap(SnapRect.of(56f, 300f, 100f, 100f), w, h, emptyList(), th)
        assertEquals(-6f, r.dx, 0f)
        assertEquals(0f, r.guideX!!, 0f)
    }

    @Test
    fun beyondThreshold_noSnapNoGuide() {
        val r = CanvasSnap.snap(SnapRect.of(520f, 300f, 100f, 100f), w, h, emptyList(), th)
        assertEquals(0f, r.dx, 0f)
        assertNull(r.guideX)
    }

    @Test
    fun otherLayer_centreAligns() {
        val other = SnapRect.of(300f, 200f, 100f, 100f)
        val r = CanvasSnap.snap(SnapRect.of(308f, 600f, 100f, 100f), w, h, listOf(other), th)
        assertEquals(-8f, r.dx, 0f)
        assertEquals(300f, r.guideX!!, 0f)
    }

    @Test
    fun otherLayer_edgeToEdge() {
        val other = SnapRect.of(300f, 200f, 100f, 100f) // left edge = 250
        // Dragged right edge at 258 -> snaps onto the other's left edge (250).
        val r = CanvasSnap.snap(SnapRect.of(208f, 600f, 100f, 100f), w, h, listOf(other), th)
        assertEquals(-8f, r.dx, 0f)
        assertEquals(250f, r.guideX!!, 0f)
    }

    @Test
    fun smallestDeltaWins_overCanvasCentre() {
        val other = SnapRect.of(505f, 200f, 100f, 100f)
        // Dragged centre 512: canvas centre is 12 away, the other layer's centre 7 away.
        val r = CanvasSnap.snap(SnapRect.of(512f, 600f, 100f, 100f), w, h, listOf(other), th)
        assertEquals(-7f, r.dx, 0f)
        assertEquals(505f, r.guideX!!, 0f)
    }

    @Test
    fun yAxis_topEdgeSnapsToCanvasCentreLine() {
        val r = CanvasSnap.snap(SnapRect.of(300f, 456f, 100f, 100f), w, h, emptyList(), th)
        assertEquals(-6f, r.dy, 0f)
        assertEquals(400f, r.guideY!!, 0f)
        assertEquals(0f, r.dx, 0f)
        assertNull(r.guideX)
    }

    @Test
    fun alreadyOnTheGuide_zeroCorrectionButGuideShows() {
        val r = CanvasSnap.snap(SnapRect.of(500f, 300f, 100f, 100f), w, h, emptyList(), th)
        assertEquals(0f, r.dx, 0f)
        assertEquals(500f, r.guideX!!, 0f)
    }
}
