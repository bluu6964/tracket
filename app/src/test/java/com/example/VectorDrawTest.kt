package com.example

import com.example.model.PATH_OP_CLOSE
import com.example.model.PATH_OP_LINE
import com.example.model.PATH_OP_MOVE
import com.example.model.PATH_OP_QUAD
import com.example.model.VectorDraw
import com.example.model.flattenPathSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Build 166, item 1: the vector drawing tool's pure geometry — a freehand stroke on the
 * canvas becomes the stored outline of a PATH shape layer (the importer's basis).
 */
class VectorDrawTest {

    @Test
    fun straightStrokeNormalisesOntoItsBoundingBox() {
        // A horizontal line across the middle of a square canvas.
        val d = VectorDraw.build(
            listOf(0.25f to 0.5f, 0.5f to 0.5f, 0.75f to 0.5f),
            canvasW = 1000f, canvasH = 1000f, density = 2f, smooth = false, close = false
        )
        assertNotNull(d)
        d!!
        assertEquals(PATH_OP_MOVE, d.segments[0].op)
        assertTrue(d.segments.drop(1).all { it.op == PATH_OP_LINE })
        // Centred on the box, divided by the larger half extent (0.25): the ends are ±1, y = 0.
        val flat = flattenPathSegments(d.segments)
        assertEquals(-1f, flat.first().first, 1e-4f)
        assertEquals(1f, flat.last().first, 1e-4f)
        assertTrue(flat.all { it.second == 0f })
        // The box centre is the frame centre: no offset. Scale: half (0.25 of the height)
        // over the shape box half (65 dp * density 2) = 250 / 130 stored px.
        assertEquals(0f, d.offsetX, 1e-3f)
        assertEquals(0f, d.offsetY, 1e-3f)
        assertEquals(250f / 130f, d.shapeScale, 1e-3f)
    }

    @Test
    fun strokeInACornerPlacesTheLayerThere() {
        // A small stroke in the top-left: the layer's centre must follow it.
        val d = VectorDraw.build(
            listOf(0.1f to 0.1f, 0.2f to 0.2f, 0.3f to 0.1f),
            canvasW = 1000f, canvasH = 1000f, density = 1f, smooth = false, close = false
        )!!
        // Box centre (0.2, 0.15) in frame fractions -> (-0.3, -0.35) of the canvas from centre.
        assertEquals(-300f, d.offsetX, 1e-2f)
        assertEquals(-350f, d.offsetY, 1e-2f)
        // The flattened frame outline reproduces the drawing (straight segments, no smoothing).
        assertEquals(0.1f, d.flatFrame.first().first, 1e-4f)
        assertEquals(0.1f, d.flatFrame.first().second, 1e-4f)
        assertEquals(0.3f, d.flatFrame.last().first, 1e-4f)
    }

    @Test
    fun wideCanvasKeepsAFrameSquareSquare() {
        // A square drawn in frame fractions on a 2:1 canvas: in uniform space it is
        // 0.4 wide x 0.5 high, so the normalised outline spans ±0.8 in x and ±1 in y —
        // and replaying it (x divided back by the aspect) reproduces the square.
        val sq = listOf(0.4f to 0.25f, 0.6f to 0.25f, 0.6f to 0.75f, 0.4f to 0.75f)
        val d = VectorDraw.build(sq, canvasW = 2000f, canvasH = 1000f, density = 1f, smooth = false, close = true)!!
        val flat = flattenPathSegments(d.segments)
        assertEquals(0.8f, flat.maxOf { it.first }, 1e-4f)
        assertEquals(1f, flat.maxOf { it.second }, 1e-4f)
        assertEquals(PATH_OP_CLOSE, d.segments.last().op)
        // flatFrame returns the drawing itself (closed: last point = first point).
        assertEquals(0.4f, d.flatFrame.first().first, 1e-4f)
        assertEquals(0.25f, d.flatFrame.first().second, 1e-4f)
        assertEquals(d.flatFrame.first(), d.flatFrame.last())
    }

    @Test
    fun smoothingBendsThroughQuadsAndStillEndsOnTheStroke() {
        val pts = listOf(0.2f to 0.8f, 0.4f to 0.3f, 0.6f to 0.7f, 0.8f to 0.35f)
        val d = VectorDraw.build(pts, canvasW = 1000f, canvasH = 1000f, density = 1f, smooth = true, close = false)!!
        assertTrue("a smoothed stroke uses quads", d.segments.any { it.op == PATH_OP_QUAD })
        assertEquals(PATH_OP_MOVE, d.segments.first().op)
        // The curve starts and ends exactly on the stroke's ends: in frame fractions the
        // built outline (flatFrame) opens on the first raw point and closes on the last.
        // (The normalised segments of the smooth and straight builds are NOT compared:
        // each build normalises over its own outline's bounding box, and the smoothed
        // curve cuts the corners, so its box is honestly smaller.)
        assertEquals(pts.first().first, d.flatFrame.first().first, 1e-4f)
        assertEquals(pts.first().second, d.flatFrame.first().second, 1e-4f)
        assertEquals(pts.last().first, d.flatFrame.last().first, 1e-4f)
        assertEquals(pts.last().second, d.flatFrame.last().second, 1e-4f)
        val flat = flattenPathSegments(d.segments)
        // The larger axis of the normalised outline touches ±1 by construction.
        val span = maxOf(flat.maxOf { abs(it.first) }, flat.maxOf { abs(it.second) })
        assertEquals(1f, span, 1e-3f)
    }

    @Test
    fun jitterCollapsesAndATapIsNotADrawing() {
        // Twenty samples inside the min-step radius are one point: nothing to draw.
        val jitter = (0 until 20).map { 0.5f + it * 0.0001f to 0.5f - it * 0.0001f }
        assertNull(VectorDraw.build(jitter, 1000f, 1000f, 1f, smooth = true, close = false))
        assertNull(VectorDraw.build(listOf(0.5f to 0.5f), 1000f, 1000f, 1f, smooth = false, close = false))
        assertNull(VectorDraw.build(emptyList(), 1000f, 1000f, 1f, smooth = false, close = false))
        // Two distinct points are the smallest drawing: a straight segment.
        val d = VectorDraw.build(listOf(0.4f to 0.5f, 0.6f to 0.5f), 1000f, 1000f, 1f, smooth = true, close = false)
        assertNotNull(d)
        assertEquals(2, d!!.segments.size)
    }
}
