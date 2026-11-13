package com.example

import com.example.engine.MaskCleanup
import com.example.engine.RotoFrames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build 140: the Roto Brush audit. The frame grid ([RotoFrames]) is shared by the generator, the
 * brush screen's scrub snapping and the saved-mask lookup; [MaskCleanup] removes the two classic
 * EdgeSAM defects (tiny enclosed holes, tiny specks) from saved masks. Both are pure, so they are
 * pinned here. The engine itself was verified end-to-end off-device with the exact ONNX files the
 * app downloads (synthetic circle: mask IoU 0.992 at the clicked centre).
 */
class RotoBrushLogicTest {

    // ---- RotoFrames -------------------------------------------------------------

    @Test
    fun frameCountCoversTheSpanPlusBothEnds() {
        assertEquals(102, RotoFrames.frameCount(10_000L, 10f))
        assertEquals(3, RotoFrames.frameCount(100L, 10f))
        assertEquals(2, RotoFrames.frameCount(0L, 10f))
        assertEquals(60, RotoFrames.frameCount(5_833L, 10f))
    }

    @Test
    fun frameMsWalksTheGrid() {
        assertEquals(1_500L, RotoFrames.frameMs(1_500L, 0, 10f))
        assertEquals(2_300L, RotoFrames.frameMs(2_000L, 3, 10f))
        assertEquals(41L, RotoFrames.frameMs(0L, 1, 24f))
    }

    @Test
    fun indexOfRoundTripsEveryGridFrame() {
        for (i in 0..50) {
            val ms = RotoFrames.frameMs(1_500L, i, 10f)
            assertEquals("frame $i", i, RotoFrames.indexOf(1_500L, ms, 10f, 10_000))
        }
        // The screen's snapping lands on the same keys the generator will use.
        assertEquals(2_300L, RotoFrames.frameMs(2_000L, RotoFrames.indexOfUnclamped(2_000L, 2_312L, 10f), 10f))
        assertEquals(2_300L, RotoFrames.frameMs(2_000L, RotoFrames.indexOfUnclamped(2_000L, 2_349L, 10f), 10f))
    }

    @Test
    fun indexOfClampsOutsideTheClip() {
        assertEquals(0, RotoFrames.indexOf(1_500L, 0L, 10f, 60))
        assertEquals(59, RotoFrames.indexOf(1_500L, 999_999L, 10f, 60))
        assertEquals(0, RotoFrames.indexOfUnclamped(1_500L, 1_499L, 10f))
    }

    // ---- MaskCleanup --------------------------------------------------------------

    private fun grid(w: Int, h: Int, fill: (Int, Int) -> Float): FloatArray {
        val a = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) a[y * w + x] = fill(x, y)
        return a
    }

    @Test
    fun tinyEnclosedHoleIsFilledAndInputIsNotMutated() {
        val a = grid(11, 11) { x, y -> if (x == 5 && y == 5) 0f else 1f }
        val out = MaskCleanup.clean(a, 11, 11)
        assertEquals(1f, out[5 * 11 + 5], 0f)
        assertEquals("input must stay raw (propagation chains it)", 0f, a[5 * 11 + 5], 0f)
    }

    @Test
    fun realHoleOfAnObjectIsPreserved() {
        // Annulus (a ring): its centre hole is ~25 px, above the tiny-hole cap at this size.
        val a = grid(13, 13) { x, y ->
            val d2 = (x - 6) * (x - 6) + (y - 6) * (y - 6)
            if (d2 in 9..36) 1f else 0f
        }
        val out = MaskCleanup.clean(a, 13, 13)
        assertEquals(0f, out[6 * 13 + 6], 0f)
        assertEquals(1f, out[6], 0f) // ring pixel at the top middle stays object
    }

    @Test
    fun tinySpeckIsDroppedButTheObjectStays() {
        val a = grid(21, 21) { x, y ->
            when {
                x < 10 && y < 10 -> 1f
                (x == 18 || x == 19) && y == 18 -> 1f // 2-px speck
                else -> 0f
            }
        }
        val out = MaskCleanup.clean(a, 21, 21)
        assertEquals(0f, out[18 * 21 + 18], 0f)
        assertEquals(0f, out[18 * 21 + 19], 0f)
        assertEquals(1f, out[5 * 21 + 5], 0f)
    }

    @Test
    fun aLoneTinyObjectIsNeverRemoved() {
        val a = grid(21, 21) { x, y -> if ((x == 10 || x == 11) && y == 10 || x == 10 && y == 11) 1f else 0f }
        val out = MaskCleanup.clean(a, 21, 21)
        assertEquals(1f, out[10 * 21 + 10], 0f)
        assertEquals(1f, out[10 * 21 + 11], 0f)
        assertEquals(1f, out[11 * 21 + 10], 0f)
    }

    @Test
    fun softEdgeValuesAreUntouched() {
        val a = grid(10, 10) { x, _ ->
            when {
                x == 4 -> 0.7f
                x == 5 -> 0.3f
                x < 4 -> 1f
                else -> 0f
            }
        }
        val out = MaskCleanup.clean(a, 10, 10)
        assertTrue(a.contentEquals(out))
    }

    @Test
    fun emptyAndFullMasksPassThrough() {
        val empty = FloatArray(25)
        assertTrue(empty.contentEquals(MaskCleanup.clean(empty, 5, 5)))
        val full = FloatArray(25) { 1f }
        assertTrue(full.contentEquals(MaskCleanup.clean(full, 5, 5)))
    }
}
