package com.example

import com.example.model.BoolPt
import com.example.model.FILL_NONE
import com.example.model.FILL_SOLID
import com.example.model.LayerType
import com.example.model.PATH_OP_CLOSE
import com.example.model.PATH_OP_LINE
import com.example.model.PATH_OP_MOVE
import com.example.model.PathSeg
import com.example.model.ShapeBoolean
import com.example.model.ShapeBooleanOp
import com.example.model.TimelineClip
import com.example.model.flattenPathRings
import com.example.model.isBooleanShapeCandidate
import com.example.model.primitiveShapeRings
import com.example.model.shapeBooleanRings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The shape boolean core (model/ShapeBoolean.kt, build 192): polygon booleans over
 * flattened rings under the NON-ZERO winding rule — the rule the shape painters fill
 * with (android.graphics.Path's default fill type; LayerPainter: "The fill follows
 * the path's own winding"). Geometry expectations are exact for the polygonal cases
 * and tolerance-checked for the sampled curves; inside/outside is always asserted
 * through the core's own winding query, the same test the classifier runs.
 */
class ShapeBooleanTest {

    private fun pt(x: Double, y: Double) = BoolPt(x, y)

    /** Axis-aligned square ring, positive winding, corners (x0,y0)-(x1,y1). */
    private fun square(x0: Double, y0: Double, x1: Double, y1: Double): List<BoolPt> =
        listOf(pt(x0, y0), pt(x1, y0), pt(x1, y1), pt(x0, y1))

    /** The same square wound the other way (a hole when nested in an outer ring). */
    private fun squareRev(x0: Double, y0: Double, x1: Double, y1: Double): List<BoolPt> =
        square(x0, y0, x1, y1).reversed()

    private fun regionArea(region: List<List<BoolPt>>): Double = ShapeBoolean.regionArea(region)

    private fun inside(region: List<List<BoolPt>>, x: Double, y: Double): Boolean =
        ShapeBoolean.isInside(pt(x, y), region)

    private fun assertArea(expected: Double, region: List<List<BoolPt>>, tol: Double = 1e-6) {
        assertEquals("region area of $region", expected, regionArea(region), tol)
    }

    // ----------------------------------------------------------------------------------
    // The four ops on overlapping squares: A = (0,0)-(2,2), B = (1,1)-(3,3), overlap 1.
    // ----------------------------------------------------------------------------------

    private val a = listOf(square(0.0, 0.0, 2.0, 2.0))
    private val b = listOf(square(1.0, 1.0, 3.0, 3.0))

    @Test
    fun unionOfOverlappingSquares() {
        val u = ShapeBoolean.combine(a, b, ShapeBooleanOp.UNION)
        assertArea(7.0, u)
        assertTrue(inside(u, 0.5, 0.5))
        assertTrue(inside(u, 2.5, 2.5))
        assertTrue(inside(u, 1.5, 1.5))
        assertFalse(inside(u, 2.5, 0.5))
        assertFalse(inside(u, -0.5, 1.0))
        // One outer ring, wound positive.
        assertEquals(1, u.size)
        assertTrue(ShapeBoolean.signedArea(u[0]) > 0.0)
    }

    @Test
    fun intersectOfOverlappingSquares() {
        val i = ShapeBoolean.combine(a, b, ShapeBooleanOp.INTERSECT)
        assertArea(1.0, i)
        assertTrue(inside(i, 1.5, 1.5))
        assertFalse(inside(i, 0.5, 0.5))
        assertFalse(inside(i, 2.5, 2.5))
    }

    @Test
    fun subtractBothDirections() {
        val amb = ShapeBoolean.combine(a, b, ShapeBooleanOp.SUBTRACT)
        assertArea(3.0, amb)
        assertTrue(inside(amb, 0.5, 0.5))
        assertFalse(inside(amb, 1.5, 1.5))
        assertFalse(inside(amb, 2.5, 2.5))
        val bma = ShapeBoolean.combine(b, a, ShapeBooleanOp.SUBTRACT)
        assertArea(3.0, bma)
        assertTrue(inside(bma, 2.5, 2.5))
        assertFalse(inside(bma, 0.5, 0.5))
    }

    @Test
    fun xorOfOverlappingSquares() {
        val x = ShapeBoolean.combine(a, b, ShapeBooleanOp.XOR)
        assertArea(6.0, x)
        assertTrue(inside(x, 0.5, 0.5))
        assertTrue(inside(x, 2.5, 2.5))
        assertFalse(inside(x, 1.5, 1.5))
    }

    // ----------------------------------------------------------------------------------
    // Disjoint / containment / identical
    // ----------------------------------------------------------------------------------

    @Test
    fun disjointShapes() {
        val far = listOf(square(5.0, 5.0, 6.0, 6.0))
        val u = ShapeBoolean.combine(a, far, ShapeBooleanOp.UNION)
        assertArea(5.0, u)
        assertEquals("union keeps both subpaths", 2, u.size)
        assertTrue(ShapeBoolean.combine(a, far, ShapeBooleanOp.INTERSECT).isEmpty())
        assertArea(4.0, ShapeBoolean.combine(a, far, ShapeBooleanOp.SUBTRACT))
        assertArea(5.0, ShapeBoolean.combine(a, far, ShapeBooleanOp.XOR))
    }

    @Test
    fun containment() {
        val inner = listOf(square(0.5, 0.5, 1.5, 1.5))
        assertArea(4.0, ShapeBoolean.combine(a, inner, ShapeBooleanOp.UNION))
        assertArea(1.0, ShapeBoolean.combine(a, inner, ShapeBooleanOp.INTERSECT))
        // A − inner = A with a hole: two rings, the hole wound negative, and the
        // winding test reads the hole as OUTSIDE (the renderer's non-zero rule).
        val cut = ShapeBoolean.combine(a, inner, ShapeBooleanOp.SUBTRACT)
        assertArea(3.0, cut)
        assertEquals(2, cut.size)
        assertTrue(cut.any { ShapeBoolean.signedArea(it) < 0.0 })
        assertFalse(inside(cut, 1.0, 1.0))
        assertTrue(inside(cut, 0.25, 0.25))
        assertTrue(ShapeBoolean.combine(inner, a, ShapeBooleanOp.SUBTRACT).isEmpty())
    }

    @Test
    fun identicalShapes() {
        assertArea(4.0, ShapeBoolean.combine(a, a, ShapeBooleanOp.UNION))
        assertArea(4.0, ShapeBoolean.combine(a, a, ShapeBooleanOp.INTERSECT))
        assertTrue(ShapeBoolean.combine(a, a, ShapeBooleanOp.SUBTRACT).isEmpty())
        assertTrue(ShapeBoolean.combine(a, a, ShapeBooleanOp.XOR).isEmpty())
    }

    // ----------------------------------------------------------------------------------
    // Holes and winding semantics of the INPUTS
    // ----------------------------------------------------------------------------------

    @Test
    fun inputWithAHole() {
        // A donut: outer (0,0)-(4,4) with a reversed inner square as its hole.
        val donut = listOf(square(0.0, 0.0, 4.0, 4.0), squareRev(1.0, 1.0, 3.0, 3.0))
        assertFalse(inside(donut, 2.0, 2.0))
        assertTrue(inside(donut, 0.5, 0.5))
        // A plug exactly over the hole: union fills the hole back in.
        val plug = listOf(square(1.0, 1.0, 3.0, 3.0))
        val filled = ShapeBoolean.combine(donut, plug, ShapeBooleanOp.UNION)
        assertArea(16.0, filled)
        assertTrue(inside(filled, 2.0, 2.0))
        // The exact-fit plug only TOUCHES the donut's region along the hole's
        // boundary (inside the plug the donut's winding is 1 - 1 = 0): no overlap.
        assertTrue(ShapeBoolean.combine(donut, plug, ShapeBooleanOp.INTERSECT).isEmpty())
        // Subtracting the plug from the donut changes nothing (it was already empty).
        assertArea(12.0, ShapeBoolean.combine(donut, plug, ShapeBooleanOp.SUBTRACT))
        // A plug bigger than the hole: the intersect is the plug minus the hole.
        val bigPlug = listOf(square(0.5, 0.5, 3.5, 3.5))
        val inter = ShapeBoolean.combine(donut, bigPlug, ShapeBooleanOp.INTERSECT)
        assertArea(5.0, inter)
        assertTrue(inside(inter, 0.75, 2.0))
        assertFalse(inside(inter, 2.0, 2.0))
    }

    @Test
    fun sameWindingNestedRingIsSolidNotAHole() {
        // Under the renderer's NON-ZERO rule a nested ring wound the SAME way adds
        // winding: the middle is filled (winding 2), unlike even-odd.
        val nested = listOf(square(0.0, 0.0, 4.0, 4.0), square(1.0, 1.0, 3.0, 3.0))
        assertEquals(2, ShapeBoolean.windingNumber(pt(2.0, 2.0), nested))
        assertTrue(inside(nested, 2.0, 2.0))
        // Union with a far square keeps the whole 4x4 filled.
        val u = ShapeBoolean.combine(nested, listOf(square(9.0, 9.0, 10.0, 10.0)), ShapeBooleanOp.UNION)
        assertArea(17.0, u)
        assertTrue(inside(u, 2.0, 2.0))
    }

    @Test
    fun pentagramCentreIsFilled() {
        // A {5/2} star polygon as ONE self-intersecting ring: the centre's winding
        // is 2 under the non-zero rule (an even-odd core would hole it).
        val star = (0 until 5).map { i ->
            val t = Math.toRadians(-90.0 + 144.0 * i)
            pt(kotlin.math.cos(t), kotlin.math.sin(t))
        }
        val region = listOf(star)
        assertEquals(2, ShapeBoolean.windingNumber(pt(0.0, 0.0), region))
        // Intersect with a small centre square: the square is inside the filled
        // centre, so it survives whole.
        val probe = listOf(square(-0.1, -0.1, 0.1, 0.1))
        val cut = ShapeBoolean.combine(region, probe, ShapeBooleanOp.INTERSECT)
        assertArea(0.04, cut, 1e-4)
        assertTrue(inside(cut, 0.0, 0.0))
        // Subtract the centre square: a hole appears at the centre.
        val holed = ShapeBoolean.combine(region, probe, ShapeBooleanOp.SUBTRACT)
        assertFalse(inside(holed, 0.0, 0.0))
        assertTrue(inside(holed, 0.0, -0.8))
    }

    // ----------------------------------------------------------------------------------
    // Degenerate and touching geometry
    // ----------------------------------------------------------------------------------

    @Test
    fun touchingAtASharedEdge() {
        // B shares A's full right edge (collinear overlap along x=2).
        val side = listOf(square(2.0, 0.0, 4.0, 2.0))
        val u = ShapeBoolean.combine(a, side, ShapeBooleanOp.UNION)
        assertArea(8.0, u)
        assertTrue(inside(u, 1.0, 1.0))
        assertTrue(inside(u, 3.0, 1.0))
        assertTrue(ShapeBoolean.combine(a, side, ShapeBooleanOp.INTERSECT).isEmpty())
        assertArea(4.0, ShapeBoolean.combine(a, side, ShapeBooleanOp.SUBTRACT))
    }

    @Test
    fun touchingAtASharedCorner() {
        // B touches A only at the point (2,2): the union is both squares.
        val corner = listOf(square(2.0, 2.0, 4.0, 4.0))
        val u = ShapeBoolean.combine(a, corner, ShapeBooleanOp.UNION)
        assertArea(8.0, u)
        assertTrue(inside(u, 1.0, 1.0))
        assertTrue(inside(u, 3.0, 3.0))
        assertFalse(inside(u, 3.0, 1.0))
    }

    @Test
    fun partiallyOverlappingCollinearEdges() {
        // B's bottom edge overlaps half of A's top edge (collinear, partial).
        val over = listOf(square(1.0, -2.0, 3.0, 0.0))
        val u = ShapeBoolean.combine(a, over, ShapeBooleanOp.UNION)
        assertArea(8.0, u)
        assertTrue(inside(u, 1.5, -1.0))
        assertTrue(inside(u, 0.5, 1.0))
    }

    @Test
    fun tangentCirclesDoNotCorrupt() {
        // Two 64-sample circles, tangent at (1, 0): the union is both discs; every
        // emitted point is finite and each ring's area is sane.
        fun circle(cx: Double): List<BoolPt> = (0 until 64).map { i ->
            val t = i.toDouble() / 64.0 * 2.0 * Math.PI
            pt(cx + kotlin.math.cos(t), kotlin.math.sin(t))
        }
        val u = ShapeBoolean.combine(listOf(circle(0.0)), listOf(circle(2.0)), ShapeBooleanOp.UNION)
        assertTrue(u.isNotEmpty())
        for (ring in u) for (p in ring) {
            assertTrue(p.x.isFinite() && p.y.isFinite())
            assertTrue(abs(p.x) <= 3.0 + 1e-6 && abs(p.y) <= 1.0 + 1e-6)
        }
        assertEquals(2.0 * Math.PI, regionArea(u), 0.05)
        assertTrue(inside(u, 0.0, 0.0))
        assertTrue(inside(u, 2.0, 0.0))
    }

    @Test
    fun degenerateInputsYieldEmptyNotGarbage() {
        // A zero-area "ring" (a line closed back on itself) is no region at all.
        val line = listOf(listOf(pt(0.0, 0.0), pt(2.0, 0.0), pt(0.0, 0.0)))
        assertTrue(ShapeBoolean.combine(line, a, ShapeBooleanOp.INTERSECT).isEmpty())
        assertArea(4.0, ShapeBoolean.combine(line, a, ShapeBooleanOp.UNION))
        // Empty regions on either side follow plain set logic.
        assertArea(4.0, ShapeBoolean.combine(emptyList(), a, ShapeBooleanOp.UNION))
        assertTrue(ShapeBoolean.combine(a, emptyList(), ShapeBooleanOp.INTERSECT).isEmpty())
        assertArea(4.0, ShapeBoolean.combine(a, emptyList(), ShapeBooleanOp.SUBTRACT))
        assertTrue(ShapeBoolean.combine(emptyList(), a, ShapeBooleanOp.SUBTRACT).isEmpty())
        // Duplicate points and a repeated closing point in the input are absorbed.
        val dup = listOf(
            listOf(pt(0.0, 0.0), pt(0.0, 0.0), pt(2.0, 0.0), pt(2.0, 2.0), pt(0.0, 2.0), pt(0.0, 0.0))
        )
        assertArea(7.0, ShapeBoolean.combine(dup, b, ShapeBooleanOp.UNION))
    }

    @Test
    fun multiRegionResultKeepsEveryRegion() {
        // B bridges two disjoint A squares: the INTERSECT has two regions.
        val twoA = listOf(square(0.0, 0.0, 1.0, 1.0), square(3.0, 0.0, 4.0, 1.0))
        val bridge = listOf(square(0.5, 0.25, 3.5, 0.75))
        val i = ShapeBoolean.combine(twoA, bridge, ShapeBooleanOp.INTERSECT)
        assertEquals(2, i.size)
        assertArea(0.5, i)
        // And the SUBTRACT leaves both squares notched, still two regions.
        val s = ShapeBoolean.combine(twoA, bridge, ShapeBooleanOp.SUBTRACT)
        assertArea(1.5, s)
        assertTrue(inside(s, 0.25, 0.5))
        assertFalse(inside(s, 0.75, 0.5))
    }

    // ----------------------------------------------------------------------------------
    // Path flattening + primitive outlines + clip-level rings
    // ----------------------------------------------------------------------------------

    @Test
    fun flattenPathRingsSplitsSubpathsAndClosesOpenOnes() {
        val segs = listOf(
            PathSeg(PATH_OP_MOVE, listOf(0f, 0f)),
            PathSeg(PATH_OP_LINE, listOf(1f, 0f)),
            PathSeg(PATH_OP_LINE, listOf(1f, 1f)),
            PathSeg(PATH_OP_CLOSE),
            PathSeg(PATH_OP_MOVE, listOf(2f, 2f)),
            PathSeg(PATH_OP_LINE, listOf(3f, 2f)),
            PathSeg(PATH_OP_LINE, listOf(3f, 3f))
        )
        val rings = flattenPathRings(segs)
        assertEquals(2, rings.size)
        assertEquals(3, rings[0].size) // the CLOSE's repeat of the first point is dropped
        assertEquals(3, rings[1].size) // the open subpath stays a ring (implicitly closed)
    }

    @Test
    fun primitiveOutlinesMatchTheRenderers() {
        fun clip(pattern: String) = TimelineClip(
            id = "x", name = "x", type = LayerType.SHAPE,
            startMs = 0L, durationMs = 1000L, color = 0xFFFF3377,
            shapePattern = pattern
        )
        // Rect over the box basis: area 4.
        assertArea(4.0, primitiveShapeRings(clip("RECT")), 1e-9)
        // Circle: the 64-gon area, a hair under pi (the polygon undershoot ~0.005).
        val circle = primitiveShapeRings(clip("CIRCLE"))
        assertEquals(1, circle.size)
        assertEquals(64, circle[0].size)
        assertEquals(Math.PI, abs(ShapeBoolean.signedArea(circle[0])), 0.01)
        // Unknown kinds fall back to the circle, like both painters.
        assertEquals(64, primitiveShapeRings(clip("WHATEVER"))[0].size)
        // Pentagon: (5/2) sin(72 deg) for circumradius 1.
        assertEquals(2.5 * kotlin.math.sin(Math.toRadians(72.0)),
            abs(ShapeBoolean.signedArea(primitiveShapeRings(clip("PENTAGON"))[0])), 1e-9)
        // Star: 10 vertices alternating 1 / 0.45 — area = 10 * (1*0.45*sin(36))/2... the
        // shoelace of alternating radii: 5 * r1 * r2 * sin(36 deg) * ... pinned numerically.
        val starArea = abs(ShapeBoolean.signedArea(primitiveShapeRings(clip("STAR"))[0]))
        assertEquals(5.0 * 1.0 * 0.45 * kotlin.math.sin(Math.toRadians(36.0)) * 2.0 / 2.0, starArea, 1e-9)
        // Triangle: base 2, height 2 -> 2.
        assertArea(2.0, primitiveShapeRings(clip("TRIANGLE")), 1e-9)
        // Oval: ellipse 1.2 x 0.75 -> pi * 0.9, polygon-approximated.
        assertEquals(Math.PI * 0.9, abs(ShapeBoolean.signedArea(primitiveShapeRings(clip("OVAL"))[0])), 0.005)
        // Rounded rect with no stored radius: the built-in 16/65 corner.
        val rounded = primitiveShapeRings(clip("ROUNDED_SQUARE"))
        val r = 16.0 / 65.0
        assertEquals(4.0 - (4.0 - Math.PI) * r * r, abs(ShapeBoolean.signedArea(rounded[0])), 0.01)
    }

    @Test
    fun clipRingsRespectFillAndCrop() {
        val rect = TimelineClip(
            id = "x", name = "x", type = LayerType.SHAPE,
            startMs = 0L, durationMs = 1000L, color = 0xFFFF3377,
            shapePattern = "RECT", fillMode = FILL_SOLID
        )
        assertArea(4.0, rect.shapeBooleanRings()!!, 1e-9)
        assertTrue(rect.isBooleanShapeCandidate)
        // No fill -> no region (a stroke alone has nothing to combine).
        val noFill = rect.copy(fillMode = FILL_NONE)
        assertNull(noFill.shapeBooleanRings())
        assertFalse(noFill.isBooleanShapeCandidate)
        // A half-width centred crop cuts the drawn region to 2 x 4 -> area... the box
        // basis: window is half the width (2 of 4 sq units) -> area 2.
        val cropped = rect.copy(cropWidthPercent = 50f, cropHorizontalPercent = 50f)
        assertArea(2.0, cropped.shapeBooleanRings()!!, 1e-6)
        // Non-shape layers never qualify.
        assertNull(rect.copy(type = LayerType.TEXT).shapeBooleanRings())
    }
}
