package com.example

import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.cropVisibleBox
import com.example.model.cropVisibleRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crop window -> visible box rule (model/CropBounds.kt): the selection frame and the
 * snap rects hug the part of a shape / text layer's box that stays visible after Crop —
 * the painters clip the box in place, so the visible part is a sub-rectangle of the box,
 * not a resized / re-centred layer. Box 200 x 100 throughout (any units — the rule is
 * proportional).
 */
class CropBoundsTest {

    private fun clip(
        cropW: Float = 100f, cropH: Float = 100f, cropX: Float = 50f, cropY: Float = 50f
    ) = TimelineClip(
        id = "a", name = "a", type = LayerType.SHAPE, startMs = 0, durationMs = 1000,
        color = 0xFF000000,
        cropWidthPercent = cropW, cropHeightPercent = cropH,
        cropHorizontalPercent = cropX, cropVerticalPercent = cropY
    )

    @Test
    fun noCrop_fullBoxNoOffset() {
        val v = clip().cropVisibleBox(200f, 100f)!!
        assertEquals(200f, v.width, 0f)
        assertEquals(100f, v.height, 0f)
        assertEquals(0f, v.offX, 0f)
        assertEquals(0f, v.offY, 0f)
    }

    @Test
    fun centredCrop_shrinksAroundTheCentre() {
        // Half width / half height kept, anchor centred: the visible part is the middle quarter.
        val v = clip(cropW = 50f, cropH = 50f).cropVisibleBox(200f, 100f)!!
        assertEquals(100f, v.width, 0f)
        assertEquals(50f, v.height, 0f)
        assertEquals(0f, v.offX, 0f)
        assertEquals(0f, v.offY, 0f)
    }

    @Test
    fun cropAnchoredToTheLeftTop_shiftsTheVisibleCentre() {
        // Anchor 0: the window starts at the box's left / top edge, so its centre sits a
        // quarter-box left / up from the box centre.
        val v = clip(cropW = 50f, cropH = 50f, cropX = 0f, cropY = 0f).cropVisibleBox(200f, 100f)!!
        assertEquals(100f, v.width, 0f)
        assertEquals(50f, v.height, 0f)
        assertEquals(-50f, v.offX, 0f)
        assertEquals(-25f, v.offY, 0f)
    }

    @Test
    fun cropAnchoredToTheRightBottom_shiftsTheOtherWay() {
        val v = clip(cropW = 50f, cropH = 50f, cropX = 100f, cropY = 100f).cropVisibleBox(200f, 100f)!!
        assertEquals(50f, v.offX, 0f)
        assertEquals(25f, v.offY, 0f)
    }

    @Test
    fun widthOnlyCrop_heightStaysTheFullBox() {
        val v = clip(cropW = 25f, cropX = 100f).cropVisibleBox(200f, 100f)!!
        assertEquals(50f, v.width, 0f)
        assertEquals(100f, v.height, 0f)
        assertEquals(75f, v.offX, 0f)
        assertEquals(0f, v.offY, 0f)
    }

    @Test
    fun storedZeroCrop_readsAsTheRenderersFloor_notAZeroBox() {
        // The renderers never crop below 5%: a stored 0% bounds the same 5% sliver they
        // draw — never a zero-size (crash / phantom) box.
        val v = clip(cropW = 0f, cropH = 0f).cropVisibleBox(200f, 100f)!!
        assertEquals(10f, v.width, 0f)
        assertEquals(5f, v.height, 0f)
    }

    @Test
    fun degenerateBox_nothingToBound() {
        assertNull(clip(cropW = 50f).cropVisibleBox(0f, 100f))
        assertNull(clip(cropW = 50f).cropVisibleBox(200f, 0f))
        assertNull(clip().cropVisibleBox(-1f, 100f))
    }

    @Test
    fun outOfRangeAnchor_clampedInsideTheBox() {
        // A stored anchor beyond 0..100 cannot push the visible part outside the box:
        // it clamps to the far edge, like the native renderer clamps its crop window.
        val v = clip(cropW = 50f, cropX = 150f).cropVisibleBox(200f, 100f)!!
        assertEquals(100f, v.width, 0f)
        assertEquals(50f, v.offX, 0f) // same as anchored to the right edge
    }

    // ---- The touch-box rect (build 186): the same window in box-local coordinates ----
    // The Compose touch boxes (the shape drag box, the text layout box) accept touches
    // only inside this rect; a point on the cropped-away part falls through.

    @Test
    fun rect_noCrop_isTheWholeBoxAtTheOrigin() {
        val r = clip().cropVisibleRect(200f, 100f)!!
        assertEquals(0f, r.left, 0f)
        assertEquals(0f, r.top, 0f)
        assertEquals(200f, r.width, 0f)
        assertEquals(100f, r.height, 0f)
        assertTrue(r.contains(0f, 0f))
        assertTrue(r.contains(199.9f, 99.9f))
        assertFalse(r.contains(200f, 50f)) // half-open: the far edges are out
        assertFalse(r.contains(100f, 100f))
    }

    @Test
    fun rect_centredCrop_sitsInTheMiddle_quarterPointsFallOutside() {
        val r = clip(cropW = 50f, cropH = 50f).cropVisibleRect(200f, 100f)!!
        assertEquals(50f, r.left, 0f)
        assertEquals(25f, r.top, 0f)
        assertEquals(100f, r.width, 0f)
        assertEquals(50f, r.height, 0f)
        assertTrue(r.contains(100f, 50f)) // the box centre is visible
        assertTrue(r.contains(50f, 25f)) // the window's own top-left corner is in
        assertFalse(r.contains(49.9f, 50f)) // just left of the window: cropped away
        assertFalse(r.contains(100f, 24.9f)) // just above the window: cropped away
        assertFalse(r.contains(150f, 50f)) // the window's right edge is out (half-open)
        assertFalse(r.contains(10f, 10f)) // the stored box's corner: cropped away
    }

    @Test
    fun rect_anchoredLeftTop_startsAtTheBoxOrigin() {
        val r = clip(cropW = 50f, cropH = 50f, cropX = 0f, cropY = 0f).cropVisibleRect(200f, 100f)!!
        assertEquals(0f, r.left, 0f)
        assertEquals(0f, r.top, 0f)
        assertTrue(r.contains(99.9f, 49.9f))
        assertFalse(r.contains(100f, 25f))
        assertFalse(r.contains(50f, 50f))
    }

    @Test
    fun rect_anchoredRightBottom_endsAtTheBoxFarCorner() {
        val r = clip(cropW = 50f, cropH = 50f, cropX = 100f, cropY = 100f).cropVisibleRect(200f, 100f)!!
        assertEquals(100f, r.left, 0f)
        assertEquals(50f, r.top, 0f)
        assertEquals(200f, r.right, 0f)
        assertEquals(100f, r.bottom, 0f)
        assertTrue(r.contains(199.9f, 99.9f))
        assertFalse(r.contains(99.9f, 75f))
    }

    @Test
    fun rect_agreesWithTheVisibleBoxEverywhere() {
        // The rect is the visible box re-expressed: same size, and its centre sits at
        // the box centre plus the visible box's offset — for every anchor.
        for (ax in listOf(0f, 25f, 50f, 75f, 100f)) {
            val c = clip(cropW = 40f, cropH = 60f, cropX = ax, cropY = 100f - ax)
            val v = c.cropVisibleBox(200f, 100f)!!
            val r = c.cropVisibleRect(200f, 100f)!!
            assertEquals(v.width, r.width, 0f)
            assertEquals(v.height, r.height, 0f)
            assertEquals(100f + v.offX, r.left + r.width / 2f, 0.001f)
            assertEquals(50f + v.offY, r.top + r.height / 2f, 0.001f)
            // Contained in the box, up to float dust in the proportional arithmetic
            // (the model clamps the rect inside; 1-ulp sums can still straddle the edge).
            assertTrue(r.left >= -0.01f && r.right <= 200.01f && r.top >= -0.01f && r.bottom <= 100.01f)
        }
    }

    @Test
    fun rect_degenerateBox_isNullLikeTheVisibleBox() {
        assertNull(clip(cropW = 50f).cropVisibleRect(0f, 100f))
        assertNull(clip(cropW = 50f).cropVisibleRect(200f, 0f))
    }
}
