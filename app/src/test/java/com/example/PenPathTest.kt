package com.example

import com.example.model.EditablePath
import com.example.model.PATH_OP_CLOSE
import com.example.model.PATH_OP_CUBIC
import com.example.model.PATH_OP_LINE
import com.example.model.PATH_OP_MOVE
import com.example.model.PATH_OP_QUAD
import com.example.model.PathAnchor
import com.example.model.PathEdit
import com.example.model.PathSeg
import com.example.model.PathSubpath
import com.example.model.PenPath
import com.example.model.flattenPathSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Build 177: the point-based Pen tool + node editing — Sarowar's correction that real
 * vector drawing is done WITH POINTS (tap = anchor, tap-drag = bezier handles), not
 * freehand. Pins the pen builder (taps / pulls -> PATH layer geometry through the
 * shared normalisation) and the PathEdit model (segments <-> anchors, QUAD -> CUBIC
 * conversion, split / delete / move with smooth-handle mirroring).
 */
class PenPathTest {

    private fun seg(op: Int, vararg pts: Float) = PathSeg(op, pts.toList())

    private fun assertFlatClose(a: List<PathSeg>, b: List<PathSeg>, tol: Float) {
        val fa = flattenPathSegments(a)
        val fb = flattenPathSegments(b)
        assertEquals("flattened point counts differ", fa.size, fb.size)
        for (i in fa.indices) {
            assertEquals("x at $i", fa[i].first, fb[i].first, tol)
            assertEquals("y at $i", fa[i].second, fb[i].second, tol)
        }
    }

    /** Distance from point [p] to the polyline [line] (the flattened curve). */
    private fun distToPolyline(p: Pair<Float, Float>, line: List<Pair<Float, Float>>): Float {
        var best = Float.MAX_VALUE
        for (i in 0 until line.size - 1) {
            val (ax, ay) = line[i]
            val (bx, by) = line[i + 1]
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 < 1e-12f) 0f else (((p.first - ax) * dx + (p.second - ay) * dy) / len2).coerceIn(0f, 1f)
            val ex = ax + dx * t - p.first; val ey = ay + dy * t - p.second
            best = minOf(best, hypot(ex, ey))
        }
        return best
    }

    /**
     * Two outlines draw the same curve: every flattened sample of each lies on the
     * other. (Splitting a segment adds vertices ON the curve, so point counts and
     * positions legitimately differ — this is the comparison that must hold.)
     */
    private fun assertSameCurve(a: List<PathSeg>, b: List<PathSeg>, tol: Float) {
        val fa = flattenPathSegments(a, 48)
        val fb = flattenPathSegments(b, 48)
        for (p in fa) assertTrue("point $p strays from the other curve", distToPolyline(p, fb) <= tol)
        for (p in fb) assertTrue("point $p strays from the other curve", distToPolyline(p, fa) <= tol)
    }

    // ------------------------------------------------------------------ the pen builder

    @Test
    fun penTapsMakeStraightSegmentsPlacedLikeADrawing() {
        var anchors = emptyList<PathAnchor>()
        anchors = PenPath.tap(anchors, 0.25f, 0.5f)
        anchors = PenPath.tap(anchors, 0.5f, 0.25f)
        anchors = PenPath.tap(anchors, 0.75f, 0.5f)
        val d = PenPath.build(anchors, closed = false, canvasW = 1000f, canvasH = 1000f, density = 1f)
        assertNotNull(d)
        d!!
        assertEquals(PATH_OP_MOVE, d.segments[0].op)
        assertTrue(d.segments.drop(1).all { it.op == PATH_OP_LINE })
        // The flattened frame outline is the drawing itself (straight taps, no smoothing).
        assertEquals(0.25f, d.flatFrame.first().first, 1e-4f)
        assertEquals(0.5f, d.flatFrame.first().second, 1e-4f)
        assertTrue(d.flatFrame.any { it.first == 0.5f && it.second == 0.25f })
        assertEquals(0.75f, d.flatFrame.last().first, 1e-4f)
    }

    @Test
    fun penClosedPathClosesAndRepeatsItsFirstPoint() {
        var anchors = emptyList<PathAnchor>()
        anchors = PenPath.tap(anchors, 0.3f, 0.3f)
        anchors = PenPath.tap(anchors, 0.7f, 0.3f)
        anchors = PenPath.tap(anchors, 0.5f, 0.7f)
        val d = PenPath.build(anchors, closed = true, canvasW = 1000f, canvasH = 1000f, density = 1f)!!
        assertEquals(PATH_OP_CLOSE, d.segments.last().op)
        assertEquals(d.flatFrame.first(), d.flatFrame.last())
    }

    @Test
    fun penDragPullsSymmetricHandlesAndCurvesTheSegments() {
        var anchors = emptyList<PathAnchor>()
        anchors = PenPath.tap(anchors, 0.2f, 0.8f)
        anchors = PenPath.tap(anchors, 0.5f, 0.5f)
        anchors = PenPath.pull(anchors, 0.65f, 0.35f)
        val pulled = anchors.last()
        assertTrue(pulled.smooth)
        assertEquals(0.65f, pulled.outX!!, 1e-6f)
        assertEquals(0.35f, pulled.outY!!, 1e-6f)
        // The in handle is the exact mirror of the out handle through the anchor.
        assertEquals(2f * 0.5f - 0.65f, pulled.inX!!, 1e-6f)
        assertEquals(2f * 0.5f - 0.35f, pulled.inY!!, 1e-6f)
        anchors = PenPath.tap(anchors, 0.8f, 0.8f)
        // As segments, the pulled anchor's neighbours join it with cubics.
        val segs = PathEdit.toSegments(EditablePath(listOf(PathSubpath(anchors))))
        assertTrue(segs.any { it.op == PATH_OP_CUBIC })
        // A built layer's curve passes through every placed anchor.
        val d = PenPath.build(anchors, closed = false, canvasW = 1000f, canvasH = 1000f, density = 1f)!!
        assertTrue(d.flatFrame.any { abs(it.first - 0.5f) < 1e-3f && abs(it.second - 0.5f) < 1e-3f })
    }

    @Test
    fun penReleaseWithoutPullKeepsACornerAndBuildNeedsTwoPoints() {
        var anchors = PenPath.tap(emptyList(), 0.4f, 0.4f)
        anchors = PenPath.pull(anchors, 0.45f, 0.45f)
        anchors = PenPath.releaseTap(anchors)
        assertFalse(anchors.last().hasIn)
        assertFalse(anchors.last().hasOut)
        assertFalse(anchors.last().smooth)
        assertNull(PenPath.build(anchors, closed = false, canvasW = 1000f, canvasH = 1000f, density = 1f))
        assertNull(PenPath.build(emptyList(), closed = false, canvasW = 1000f, canvasH = 1000f, density = 1f))
        val two = PenPath.tap(anchors, 0.6f, 0.6f)
        assertNotNull(PenPath.build(two, closed = false, canvasW = 1000f, canvasH = 1000f, density = 1f))
        // Undo point drops the last anchor.
        assertEquals(1, PenPath.undoPoint(two).size)
    }

    // ------------------------------------------------------------------ segments <-> anchors

    @Test
    fun linesAndCubicsRoundTripExactly() {
        val segs = listOf(
            seg(PATH_OP_MOVE, 0f, 0f),
            seg(PATH_OP_LINE, 1f, 0f),
            seg(PATH_OP_CUBIC, 1.5f, 0.5f, 2f, -0.5f, 2.5f, 0.25f),
            seg(PATH_OP_LINE, 3f, 1f),
            seg(PATH_OP_CLOSE)
        )
        val back = PathEdit.toSegments(PathEdit.fromSegments(segs))
        assertEquals(segs, back)
    }

    @Test
    fun quadConvertsToTheSameCurveAsCubic() {
        val segs = listOf(
            seg(PATH_OP_MOVE, 0f, 0f),
            seg(PATH_OP_QUAD, 1f, 2f, 2f, 0f),
            seg(PATH_OP_LINE, 3f, 0f)
        )
        val anchors = PathEdit.fromSegments(segs)
        val back = PathEdit.toSegments(anchors)
        assertTrue("no quad survives conversion", back.none { it.op == PATH_OP_QUAD })
        assertTrue(back.any { it.op == PATH_OP_CUBIC })
        // Degree elevation is exact: the flattened curves agree point for point.
        assertFlatClose(segs, back, 1e-3f)
        // The elevated handles: c1 = p0 + 2/3 (q - p0), c2 = p3 + 2/3 (q - p3).
        val a0 = anchors.subpaths[0].anchors[0]
        assertEquals(2f / 3f, a0.outX!!, 1e-5f)
        assertEquals(4f / 3f, a0.outY!!, 1e-5f)
        val a1 = anchors.subpaths[0].anchors[1]
        assertEquals(2f - 2f / 3f, a1.inX!!, 1e-5f)
        assertEquals(4f / 3f, a1.inY!!, 1e-5f)
    }

    @Test
    fun smoothIsDetectedFromCollinearHandlesOnly() {
        // An S-reflected cubic pair: handles on one line through the middle anchor.
        val smooth = listOf(
            seg(PATH_OP_MOVE, 0f, 0f),
            seg(PATH_OP_CUBIC, 0.5f, 1f, 0.5f, 1f, 1f, 1f),
            seg(PATH_OP_CUBIC, 1.5f, 1f, 1.5f, 0f, 2f, 0f)
        )
        val mid = PathEdit.fromSegments(smooth).subpaths[0].anchors[1]
        assertTrue(mid.smooth)
        // Same start, but the second curve's first handle leaves at an angle: a corner.
        val corner = listOf(
            seg(PATH_OP_MOVE, 0f, 0f),
            seg(PATH_OP_CUBIC, 0.5f, 1f, 0.5f, 1f, 1f, 1f),
            seg(PATH_OP_CUBIC, 1.2f, 1.8f, 1.5f, 0f, 2f, 0f)
        )
        assertFalse(PathEdit.fromSegments(corner).subpaths[0].anchors[1].smooth)
    }

    @Test
    fun curvedClosingSegmentRoundTripsThroughTheFirstAnchor() {
        // A closing curve is stored as a cubic landing exactly on the start point + CLOSE.
        val segs = listOf(
            seg(PATH_OP_MOVE, 0f, 0f),
            seg(PATH_OP_LINE, 2f, 0f),
            seg(PATH_OP_CUBIC, 2.5f, 1f, 1f, 1.5f, 0f, 0f),
            seg(PATH_OP_CLOSE)
        )
        val path = PathEdit.fromSegments(segs)
        val anchors = path.subpaths[0].anchors
        // The landing point merged into the first anchor (no duplicate anchor).
        assertEquals(2, anchors.size)
        assertEquals(1f, anchors[0].inX!!, 1e-6f)
        assertEquals(1.5f, anchors[0].inY!!, 1e-6f)
        assertEquals(2.5f, anchors[1].outX!!, 1e-6f)
        assertEquals(segs, PathEdit.toSegments(path))
    }

    @Test
    fun multipleSubpathsSurviveTheRoundTrip() {
        val segs = listOf(
            seg(PATH_OP_MOVE, 0f, 0f), seg(PATH_OP_LINE, 1f, 1f),
            seg(PATH_OP_MOVE, 2f, 2f), seg(PATH_OP_LINE, 3f, 2f), seg(PATH_OP_CLOSE)
        )
        val path = PathEdit.fromSegments(segs)
        assertEquals(2, path.subpaths.size)
        assertFalse(path.subpaths[0].closed)
        assertTrue(path.subpaths[1].closed)
        assertEquals(segs, PathEdit.toSegments(path))
    }

    // ------------------------------------------------------------------ split / delete / move

    @Test
    fun splitLineInsertsACornerAnchorOnTheLine() {
        val path = EditablePath(listOf(PathSubpath(listOf(PathAnchor(0f, 0f), PathAnchor(2f, 0f)))))
        val (split, idx) = PathEdit.splitSegment(path, 0, 0, 0.5f)
        assertEquals(1, idx)
        val anchors = split.subpaths[0].anchors
        assertEquals(3, anchors.size)
        assertEquals(1f, anchors[1].x, 1e-6f)
        assertFalse(anchors[1].hasIn)
        assertFalse(anchors[1].hasOut)
        assertSameCurve(PathEdit.toSegments(path), PathEdit.toSegments(split), 1e-3f)
    }

    @Test
    fun splitCubicKeepsTheCurveAndGivesSmoothHandles() {
        val path = EditablePath(
            listOf(
                PathSubpath(
                    listOf(
                        PathAnchor(0f, 0f, outX = 0f, outY = 2f),
                        PathAnchor(3f, 0f, inX = 3f, inY = 2f)
                    )
                )
            )
        )
        val (split, idx) = PathEdit.splitSegment(path, 0, 0, 0.5f)
        assertEquals(1, idx)
        val anchors = split.subpaths[0].anchors
        assertEquals(3, anchors.size)
        // Midpoint of the symmetric hump: (1.5, 1.5).
        assertEquals(1.5f, anchors[1].x, 1e-5f)
        assertEquals(1.5f, anchors[1].y, 1e-5f)
        assertTrue(anchors[1].smooth)
        assertTrue(anchors[1].hasIn && anchors[1].hasOut)
        assertSameCurve(PathEdit.toSegments(path), PathEdit.toSegments(split), 1e-3f)
    }

    @Test
    fun splitClosingSegmentOfAClosedPathAppendsAtTheWrap() {
        val tri = EditablePath(
            listOf(
                PathSubpath(
                    listOf(PathAnchor(0f, 0f), PathAnchor(2f, 0f), PathAnchor(1f, 2f)),
                    closed = true
                )
            )
        )
        val (split, idx) = PathEdit.splitSegment(tri, 0, 2, 0.5f)
        assertEquals(3, idx)
        assertEquals(4, split.subpaths[0].anchors.size)
        // The inserted anchor is the midpoint of the closing edge (1,2) -> (0,0).
        assertEquals(0.5f, split.subpaths[0].anchors[3].x, 1e-6f)
        assertEquals(1f, split.subpaths[0].anchors[3].y, 1e-6f)
        assertSameCurve(PathEdit.toSegments(tri), PathEdit.toSegments(split), 1e-3f)
        // Out-of-range segments are refused.
        assertEquals(-1, PathEdit.splitSegment(tri, 0, 3, 0.5f).second)
        val open = EditablePath(listOf(PathSubpath(listOf(PathAnchor(0f, 0f), PathAnchor(1f, 0f)))))
        assertEquals(-1, PathEdit.splitSegment(open, 0, 1, 0.5f).second)
    }

    @Test
    fun deleteAnchorMergesSegmentsAndRespectsTheMinimum() {
        val path = EditablePath(
            listOf(
                PathSubpath(
                    listOf(
                        PathAnchor(0f, 0f, outX = 0.5f, outY = 1f),
                        PathAnchor(1f, 0f, inX = 0.5f, inY = -1f, outX = 1.5f, outY = 1f),
                        PathAnchor(2f, 0f, inX = 1.5f, inY = -1f)
                    )
                )
            )
        )
        val merged = PathEdit.deleteAnchor(path, 0, 1)
        val anchors = merged.subpaths[0].anchors
        assertEquals(2, anchors.size)
        // One cubic remains, joining the surviving handles.
        val segs = PathEdit.toSegments(merged)
        assertEquals(listOf(PATH_OP_MOVE, PATH_OP_CUBIC), segs.map { it.op })
        assertEquals(listOf(0.5f, 1f, 1.5f, -1f, 2f, 0f), segs[1].pts)
        // Minimum sizes: an open path keeps 2 anchors, a closed one 3.
        assertEquals(2, PathEdit.deleteAnchor(merged, 0, 0).subpaths[0].anchors.size)
        val tri = EditablePath(
            listOf(
                PathSubpath(
                    listOf(PathAnchor(0f, 0f), PathAnchor(2f, 0f), PathAnchor(1f, 2f)),
                    closed = true
                )
            )
        )
        assertEquals(3, PathEdit.deleteAnchor(tri, 0, 1).subpaths[0].anchors.size)
        val quad = EditablePath(
            listOf(
                PathSubpath(
                    listOf(PathAnchor(0f, 0f), PathAnchor(2f, 0f), PathAnchor(2f, 2f), PathAnchor(0f, 2f)),
                    closed = true
                )
            )
        )
        assertEquals(3, PathEdit.deleteAnchor(quad, 0, 0).subpaths[0].anchors.size)
    }

    @Test
    fun moveAnchorCarriesItsHandles() {
        val path = EditablePath(
            listOf(
                PathSubpath(
                    listOf(PathAnchor(1f, 1f, inX = 0.5f, inY = 1f, outX = 1.5f, outY = 0.5f))
                )
            )
        )
        val moved = PathEdit.moveAnchor(path, 0, 0, 3f, 2f).subpaths[0].anchors[0]
        assertEquals(3f, moved.x, 1e-6f)
        assertEquals(2f, moved.y, 1e-6f)
        assertEquals(2.5f, moved.inX!!, 1e-6f)
        assertEquals(3.5f, moved.outX!!, 1e-6f)
        assertEquals(1.5f, moved.outY!!, 1e-6f)
    }

    @Test
    fun moveHandleMirrorsDirectionOnSmoothPointsOnly() {
        // Smooth anchor at the origin: in handle 2 units to the left, out handle 1 unit right.
        val smoothPath = EditablePath(
            listOf(
                PathSubpath(
                    listOf(PathAnchor(0f, 0f, inX = -2f, inY = 0f, outX = 1f, outY = 0f, smooth = true))
                )
            )
        )
        // Drag the out handle up: the in handle must swing to the opposite ray, keeping length 2.
        val dragged = PathEdit.moveHandle(smoothPath, 0, 0, out = true, x = 0f, y = 1f).subpaths[0].anchors[0]
        assertEquals(0f, dragged.outX!!, 1e-6f)
        assertEquals(1f, dragged.outY!!, 1e-6f)
        assertEquals(0f, dragged.inX!!, 1e-5f)
        assertEquals(-2f, dragged.inY!!, 1e-5f)
        assertEquals(2f, hypot(dragged.inX!! - dragged.x, dragged.inY!! - dragged.y), 1e-5f)
        // A corner point's other handle does not move.
        val cornerPath = EditablePath(
            listOf(
                PathSubpath(
                    listOf(PathAnchor(0f, 0f, inX = -2f, inY = 0f, outX = 1f, outY = 0f, smooth = false))
                )
            )
        )
        val cd = PathEdit.moveHandle(cornerPath, 0, 0, out = true, x = 0f, y = 1f).subpaths[0].anchors[0]
        assertEquals(-2f, cd.inX!!, 1e-6f)
        assertEquals(0f, cd.inY!!, 1e-6f)
    }
}
