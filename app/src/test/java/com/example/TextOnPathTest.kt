package com.example

import com.example.model.LayerType
import com.example.model.PATH_OP_CUBIC
import com.example.model.PATH_OP_LINE
import com.example.model.PATH_OP_MOVE
import com.example.model.PathSeg
import com.example.model.TextOnPath
import com.example.model.TimelineClip
import com.example.model.isPathBearing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The text-on-path layout law (model/TextOnPath.kt, build 191): arc length over the
 * flattened outline, point + tangent at an arc distance (incl. the joint rule and the
 * clamps), the cluster layout (offset, over-length clipping), and the model fields'
 * defaults. The C++ text engine's PathWalk implements the same walk; its selftest pins
 * the same numbers on that side.
 */
class TextOnPathTest {

    private fun tableOf(vararg pts: Pair<Float, Float>) = TextOnPath.arcTable(pts.toList())

    @Test
    fun straightAndCornerArcLength() {
        val t = tableOf(0f to 0f, 100f to 0f, 100f to 50f)
        assertEquals(150f, t.total, 1e-4f)
        assertEquals(3, t.points.size)
    }

    @Test
    fun pointAtWalksSegmentsAndClamps() {
        val t = tableOf(0f to 0f, 100f to 0f, 100f to 50f)
        var p = t.pointAt(0f)
        assertEquals(0f, p.x, 1e-4f); assertEquals(0f, p.y, 1e-4f); assertEquals(0f, p.angleDeg, 1e-4f)
        p = t.pointAt(50f)
        assertEquals(50f, p.x, 1e-4f); assertEquals(0f, p.angleDeg, 1e-4f)
        p = t.pointAt(125f)
        assertEquals(100f, p.x, 1e-4f); assertEquals(25f, p.y, 1e-4f); assertEquals(90f, p.angleDeg, 1e-4f)
        // Clamped onto the ends, never past them.
        p = t.pointAt(-10f)
        assertEquals(0f, p.x, 1e-4f); assertEquals(0f, p.y, 1e-4f)
        p = t.pointAt(10_000f)
        assertEquals(100f, p.x, 1e-4f); assertEquals(50f, p.y, 1e-4f); assertEquals(90f, p.angleDeg, 1e-4f)
    }

    @Test
    fun jointTakesTheOutgoingTangent() {
        val t = tableOf(0f to 0f, 100f to 0f, 100f to 50f)
        // Exactly on the corner: the tangent is the segment the pen is ABOUT to travel.
        val p = t.pointAt(100f)
        assertEquals(100f, p.x, 1e-4f); assertEquals(0f, p.y, 1e-4f)
        assertEquals(90f, p.angleDeg, 1e-4f)
        // Just before the corner it is still the incoming segment's.
        assertEquals(0f, t.pointAt(99.9f).angleDeg, 1e-3f)
    }

    @Test
    fun diagonalPointAndAngle() {
        val t = tableOf(0f to 0f, 10f to 10f)
        assertEquals(14.14214f, t.total, 1e-3f)
        val p = t.pointAt(t.total / 2f)
        assertEquals(5f, p.x, 1e-3f); assertEquals(5f, p.y, 1e-3f); assertEquals(45f, p.angleDeg, 1e-3f)
    }

    @Test
    fun duplicatePointsCarryNoArc() {
        val t = tableOf(0f to 0f, 0f to 0f, 50f to 0f, 50f to 0f, 50f to 0f)
        assertEquals(50f, t.total, 1e-4f)
        assertEquals(2, t.points.size)
    }

    @Test
    fun degenerateTablesPlaceNothing() {
        assertEquals(0f, TextOnPath.arcTable(emptyList()).total, 0f)
        val one = tableOf(7f to 9f)
        assertEquals(0f, one.total, 0f)
        val lay = TextOnPath.layout(listOf(10f, 10f), 0f, one)
        assertTrue(lay.none { it.drawn })
    }

    @Test
    fun segmentsArcLengthLinesAndCubic() {
        val lines = TextOnPath.arcTableOfSegments(
            listOf(
                PathSeg(PATH_OP_MOVE, listOf(0f, 0f)),
                PathSeg(PATH_OP_LINE, listOf(30f, 0f)),
                PathSeg(PATH_OP_LINE, listOf(30f, 40f))
            )
        )
        assertEquals(70f, lines.total, 1e-3f)
        // A cubic bowed far out of its chord is longer than the chord, and its walk
        // starts and ends exactly on the stored end points.
        val bow = TextOnPath.arcTableOfSegments(
            listOf(
                PathSeg(PATH_OP_MOVE, listOf(0f, 0f)),
                PathSeg(PATH_OP_CUBIC, listOf(0f, 100f, 100f, 100f, 100f, 0f))
            )
        )
        assertTrue("cubic length ${bow.total} must exceed the 100 chord", bow.total > 150f)
        assertTrue("cubic length ${bow.total} stays sane", bow.total < 260f)
        assertEquals(0f, bow.pointAt(0f).x, 1e-3f)
        assertEquals(100f, bow.pointAt(bow.total).x, 1e-3f)
        assertEquals(0f, bow.pointAt(bow.total).y, 1e-3f)
    }

    @Test
    fun layoutOnAStraightLine() {
        val t = tableOf(0f to 0f, 100f to 0f)
        val lay = TextOnPath.layout(listOf(10f, 10f, 10f), 0f, t)
        assertEquals(3, lay.size)
        assertEquals(0f, lay[0].x, 1e-4f)
        assertEquals(10f, lay[1].x, 1e-4f)
        assertEquals(20f, lay[2].x, 1e-4f)
        assertTrue(lay.all { it.drawn && it.angleDeg == 0f && it.y == 0f })
    }

    @Test
    fun layoutStartOffsetIsAFractionOfTheLength() {
        val t = tableOf(0f to 0f, 100f to 0f, 100f to 100f) // total 200
        val lay = TextOnPath.layout(listOf(10f), 0.5f, t)
        assertEquals(100f, lay[0].startDist, 1e-4f)
        assertEquals(100f, lay[0].x, 1e-4f)
        assertEquals(0f, lay[0].y, 1e-4f)
        // Fractions outside 0..1 clamp.
        assertEquals(0f, TextOnPath.layout(listOf(10f), -1f, t)[0].startDist, 1e-4f)
        assertEquals(200f, TextOnPath.layout(listOf(10f), 2f, t)[0].startDist, 1e-4f)
    }

    @Test
    fun layoutClipsPastTheEnd() {
        val t = tableOf(0f to 0f, 50f to 0f)
        val lay = TextOnPath.layout(listOf(20f, 20f, 20f, 20f), 0f, t)
        // Starts at 0 / 20 / 40 are inside; the one starting at 60 is past the end.
        assertTrue(lay[0].drawn && lay[1].drawn && lay[2].drawn)
        assertFalse(lay[3].drawn)
        // The straddling cluster (start 40, advance 20 over a 50 path) IS drawn.
        assertEquals(40f, lay[2].x, 1e-4f)
    }

    @Test
    fun layoutOnACurveFollowsTheTangent() {
        // Quarter circle, radius 100, centre (0,0), from (100,0) to (0,100) in y-down
        // coordinates, as 16 chords.
        val pts = (0..16).map { i ->
            val a = (PI / 2.0) * i / 16.0
            (100.0 * cos(a)).toFloat() to (100.0 * sin(a)).toFloat()
        }
        val t = TextOnPath.arcTable(pts)
        assertEquals((PI / 2.0 * 100.0).toFloat(), t.total, 1.5f)
        val mid = t.pointAt(t.total / 2f)
        assertEquals(70.71f, mid.x, 2f)
        assertEquals(70.71f, mid.y, 2f)
        // Travelling from (100,0) towards (0,100), halfway round the tangent points
        // down-left: 135 degrees in y-down angle convention.
        assertEquals(135f, mid.angleDeg, 4f)
    }

    @Test
    fun modelFieldsDefaultToUnattached() {
        val clip = TimelineClip(
            id = "t", name = "t", type = LayerType.TEXT, startMs = 0, durationMs = 1000, color = 0
        )
        assertEquals(null, clip.textPathLayerId)
        assertEquals(0f, clip.textPathStart, 0f)
        val attached = clip.copy(textPathLayerId = "p1", textPathStart = 0.25f)
        assertEquals("p1", attached.textPathLayerId)
        assertEquals(0.25f, attached.textPathStart, 0f)
    }

    @Test
    fun pathBearingPredicate() {
        val segs = listOf(PathSeg(PATH_OP_MOVE, listOf(0f, 0f)), PathSeg(PATH_OP_LINE, listOf(1f, 0f)))
        val pen = TimelineClip(
            id = "p", name = "p", type = LayerType.SHAPE, startMs = 0, durationMs = 1000, color = 0,
            shapePattern = "PATH", pathSegments = segs
        )
        assertTrue(pen.isPathBearing)
        assertFalse(pen.copy(shapePattern = "CIRCLE").isPathBearing)
        assertFalse(pen.copy(pathSegments = emptyList()).isPathBearing)
        assertFalse(pen.copy(type = LayerType.TEXT).isPathBearing)
    }
}
