package com.example

import com.example.model.AlignRect
import com.example.model.SpatialAction
import com.example.model.SpatialAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The canvas spatial align / distribute math (model/SpatialAlign.kt, build 188).
 * Rects are canvas-px bounds; deltas are what each layer must move by. Align reads
 * against the selection's bounding box; distribute spaces CENTRES evenly and never
 * moves the two extreme centres.
 */
class SpatialAlignTest {

    private fun r(id: String, l: Float, t: Float, w: Float, h: Float) =
        AlignRect(id, l, t, l + w, t + h)

    private fun assertDelta(map: Map<String, Pair<Float, Float>>, id: String, dx: Float, dy: Float) {
        val d = map[id] ?: throw AssertionError("no delta for $id in $map")
        assertEquals("dx of $id", dx, d.first, 0.001f)
        assertEquals("dy of $id", dy, d.second, 0.001f)
    }

    // Three uneven rects: a = (10,20) 40x20, b = (100,5) 20x60, c = (60,90) 30x10.
    // Bounds: left 10, right 120, top 5, bottom 100, centre (65, 52.5).
    private val three = listOf(
        r("a", 10f, 20f, 40f, 20f),
        r("b", 100f, 5f, 20f, 60f),
        r("c", 60f, 90f, 30f, 10f)
    )

    @Test
    fun alignLeftMovesEveryLeftEdgeToTheSmallest() {
        val d = SpatialAlign.deltas(three, SpatialAction.ALIGN_LEFT)
        assertEquals(2, d.size)                       // a is already at 10: no entry
        assertDelta(d, "b", -90f, 0f)
        assertDelta(d, "c", -50f, 0f)
    }

    @Test
    fun alignRightMovesEveryRightEdgeToTheLargest() {
        val d = SpatialAlign.deltas(three, SpatialAction.ALIGN_RIGHT)
        assertEquals(2, d.size)                       // b already ends at 120
        assertDelta(d, "a", 70f, 0f)                  // 120 - 50
        assertDelta(d, "c", 30f, 0f)                  // 120 - 90
    }

    @Test
    fun alignCenterHLinesUpCentresOnTheBoundsCentre() {
        val d = SpatialAlign.deltas(three, SpatialAction.ALIGN_CENTER_H)
        assertEquals(3, d.size)
        assertDelta(d, "a", 35f, 0f)                  // 65 - 30
        assertDelta(d, "b", -45f, 0f)                 // 65 - 110
        assertDelta(d, "c", -10f, 0f)                 // 65 - 75
    }

    @Test
    fun alignTopMovesEveryTopEdgeToTheSmallest() {
        val d = SpatialAlign.deltas(three, SpatialAction.ALIGN_TOP)
        assertEquals(2, d.size)                       // b already at 5
        assertDelta(d, "a", 0f, -15f)
        assertDelta(d, "c", 0f, -85f)
    }

    @Test
    fun alignBottomMovesEveryBottomEdgeToTheLargest() {
        val d = SpatialAlign.deltas(three, SpatialAction.ALIGN_BOTTOM)
        assertEquals(2, d.size)                       // c already ends at 100
        assertDelta(d, "a", 0f, 60f)                  // 100 - 40
        assertDelta(d, "b", 0f, 35f)                  // 100 - 65
    }

    @Test
    fun alignCenterVLinesUpCentresOnTheBoundsCentre() {
        val d = SpatialAlign.deltas(three, SpatialAction.ALIGN_CENTER_V)
        assertEquals(3, d.size)
        assertDelta(d, "a", 0f, 22.5f)                // 52.5 - 30
        assertDelta(d, "b", 0f, 17.5f)                // 52.5 - 35
        assertDelta(d, "c", 0f, -42.5f)               // 52.5 - 95
    }

    @Test
    fun alignNeedsTwoRects() {
        assertTrue(SpatialAlign.deltas(emptyList(), SpatialAction.ALIGN_LEFT).isEmpty())
        assertTrue(SpatialAlign.deltas(listOf(three[0]), SpatialAction.ALIGN_LEFT).isEmpty())
        // Already aligned = a no-op (no entries at all).
        val flat = listOf(r("a", 10f, 0f, 20f, 10f), r("b", 10f, 50f, 30f, 10f))
        assertTrue(SpatialAlign.deltas(flat, SpatialAction.ALIGN_LEFT).isEmpty())
    }

    @Test
    fun distributeHorizontalSpacesCentresEvenlyAndKeepsTheExtremes() {
        // Centres at 0, 30, 100 (uneven sizes on purpose: spacing is by centre).
        val rects = listOf(
            r("a", -10f, 0f, 20f, 10f),               // centre 0
            r("b", 10f, 0f, 40f, 10f),                // centre 30
            r("c", 95f, 0f, 10f, 10f)                 // centre 100
        )
        val d = SpatialAlign.deltas(rects, SpatialAction.DISTRIBUTE_H)
        assertEquals(1, d.size)                       // only the middle layer moves
        assertDelta(d, "b", 20f, 0f)                  // 30 -> 50 (0, 50, 100)
    }

    @Test
    fun distributeVerticalSpacesCentresEvenly() {
        // Centres at 10, 90, 100 -> evenly: 10, 55, 100.
        val rects = listOf(
            r("a", 0f, 0f, 10f, 20f),                 // centre 10
            r("b", 0f, 80f, 10f, 20f),                // centre 90
            r("c", 0f, 95f, 10f, 10f)                 // centre 100
        )
        val d = SpatialAlign.deltas(rects, SpatialAction.DISTRIBUTE_V)
        assertEquals(1, d.size)
        assertDelta(d, "b", 0f, -35f)
    }

    @Test
    fun distributeHandlesUnsortedInputAndFourLayers() {
        // Centres 90, 0, 30, 60 (given out of order) -> evenly: 0, 30, 60, 90 = no move.
        val even = listOf(
            r("d", 85f, 0f, 10f, 10f),
            r("a", -5f, 0f, 10f, 10f),
            r("c", 55f, 0f, 10f, 10f),
            r("b", 25f, 0f, 10f, 10f)
        )
        assertTrue(SpatialAlign.deltas(even, SpatialAction.DISTRIBUTE_H).isEmpty())
        // Centres 0, 10, 20, 90 -> evenly: 0, 30, 60, 90: the two middle ones move.
        val uneven = listOf(
            r("a", -5f, 0f, 10f, 10f),
            r("b", 5f, 0f, 10f, 10f),
            r("c", 15f, 0f, 10f, 10f),
            r("d", 85f, 0f, 10f, 10f)
        )
        val d = SpatialAlign.deltas(uneven, SpatialAction.DISTRIBUTE_H)
        assertEquals(2, d.size)
        assertDelta(d, "b", 20f, 0f)
        assertDelta(d, "c", 40f, 0f)
    }

    @Test
    fun distributeGuards() {
        // Fewer than 3 rects: nothing.
        assertTrue(SpatialAlign.deltas(three.take(2), SpatialAction.DISTRIBUTE_H).isEmpty())
        assertTrue(SpatialAlign.deltas(three.take(1), SpatialAction.DISTRIBUTE_V).isEmpty())
        // All centres stacked on one spot: no span to distribute over.
        val stacked = listOf(
            r("a", 0f, 0f, 10f, 10f),
            r("b", 2f, 20f, 6f, 10f),
            r("c", 4f, 40f, 2f, 10f)
        )
        assertTrue(SpatialAlign.deltas(stacked, SpatialAction.DISTRIBUTE_H).isEmpty())
    }

    @Test
    fun aZeroSizeRectAlignsByItsCentrePoint() {
        // A picture-less layer (Null / Controller) measures as its centre point.
        val rects = listOf(
            AlignRect("n", 50f, 50f, 50f, 50f),
            r("a", 0f, 0f, 20f, 20f)
        )
        val d = SpatialAlign.deltas(rects, SpatialAction.ALIGN_LEFT)
        assertEquals(1, d.size)
        assertDelta(d, "n", -50f, 0f)
        val dc = SpatialAlign.deltas(rects, SpatialAction.ALIGN_CENTER_V)
        assertDelta(dc, "n", 0f, -25f)                // bounds centre y = 25
        assertDelta(dc, "a", 0f, 15f)
    }
}
