package com.example

import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.cropVisibleBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
