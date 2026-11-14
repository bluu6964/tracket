package com.example

import com.example.model.ScopeStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The video-scope statistics (model/ScopeStats.kt, build 196): exact
 * histogram counts for known pixels, the documented BT.601 luma, per-column
 * waveform aggregation, vectorscope binning (neutral grey at the centre,
 * saturated colours at their matrix positions) and the box-average
 * downscale to the analysis cap. The UI draws exactly these arrays.
 */
class ScopeStatsTest {

    private fun px(r: Int, g: Int, b: Int, a: Int = 0xFF): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun histogramCountsAreExactForKnownPixels() {
        // 2x2, row-major: red, green / blue, white.
        val d = ScopeStats.analyze(
            intArrayOf(px(255, 0, 0), px(0, 255, 0), px(0, 0, 255), px(255, 255, 255)), 2, 2
        )!!
        assertEquals(2, d.width)
        assertEquals(2, d.height)
        assertEquals(4, d.pixelCount)
        assertEquals(2, d.histR[255]); assertEquals(2, d.histR[0])
        assertEquals(2, d.histG[255]); assertEquals(2, d.histG[0])
        assertEquals(2, d.histB[255]); assertEquals(2, d.histB[0])
        assertEquals(4, d.histR.sum()); assertEquals(4, d.histG.sum()); assertEquals(4, d.histB.sum())
        // Luma of the primaries + white, one pixel each.
        assertEquals(1, d.histY[76]); assertEquals(1, d.histY[150])
        assertEquals(1, d.histY[29]); assertEquals(1, d.histY[255])
        assertEquals(4, d.histY.sum())
    }

    @Test
    fun lumaIsTheDocumented601Y() {
        // The engine's documented YCbCr matrix (fx_plan.cpp): Y = .299/.587/.114.
        assertEquals(76.245, ScopeStats.luma(255, 0, 0).toDouble(), 0.01)
        assertEquals(149.685, ScopeStats.luma(0, 255, 0).toDouble(), 0.01)
        assertEquals(29.07, ScopeStats.luma(0, 0, 255).toDouble(), 0.01)
        assertEquals(255.0, ScopeStats.luma(255, 255, 255).toDouble(), 0.01)
        assertEquals(0.0, ScopeStats.luma(0, 0, 0).toDouble(), 0.0)
        assertEquals(128.0, ScopeStats.luma(128, 128, 128).toDouble(), 0.01)
    }

    @Test
    fun waveformAggregatesPerColumn() {
        // 2 wide x 2 tall: left column black over white, right column grey twice.
        val d = ScopeStats.analyze(
            intArrayOf(px(0, 0, 0), px(128, 128, 128), px(255, 255, 255), px(128, 128, 128)), 2, 2
        )!!
        assertEquals(1, d.wave[0 * 256 + 0])
        assertEquals(1, d.wave[0 * 256 + 255])
        assertEquals(2, d.wave[1 * 256 + 128])
        assertEquals(4, d.wave.sum())
    }

    @Test
    fun neutralColoursLandInTheVectorscopeCentre() {
        // Black, two greys and white: Cb = Cr = 0 for all of them (the matrix
        // coefficients cancel), so every pixel lands in the centre bin.
        val d = ScopeStats.analyze(
            intArrayOf(px(0, 0, 0), px(128, 128, 128), px(37, 37, 37), px(255, 255, 255)), 2, 2
        )!!
        val centre = 48 * ScopeStats.VEC_GRID + 48
        assertEquals(4, d.vec[centre])
        assertEquals(4, d.vec.sum())
    }

    @Test
    fun saturatedColoursLandAtTheirMatrixPositions() {
        // Hand-computed from the documented matrix through the bin mapping:
        // red Cb -0.14713 / Cr +0.615 -> bin (37, 2); green Cb -0.28886 /
        // Cr -0.51499 -> (26, 86); blue Cb +0.436 / Cr -0.10001 -> (80, 55).
        val red = ScopeStats.analyze(intArrayOf(px(255, 0, 0)), 1, 1)!!
        assertEquals(1, red.vec[2 * ScopeStats.VEC_GRID + 37])
        val green = ScopeStats.analyze(intArrayOf(px(0, 255, 0)), 1, 1)!!
        assertEquals(1, green.vec[86 * ScopeStats.VEC_GRID + 26])
        val blue = ScopeStats.analyze(intArrayOf(px(0, 0, 255)), 1, 1)!!
        assertEquals(1, blue.vec[55 * ScopeStats.VEC_GRID + 80])
    }

    @Test
    fun vecBinEndpointsAndCentre() {
        assertEquals(48, ScopeStats.vecBinX(0f))
        assertEquals(48, ScopeStats.vecBinY(0f))
        assertEquals(0, ScopeStats.vecBinX(-ScopeStats.VEC_FULL_SCALE))
        assertEquals(ScopeStats.VEC_GRID - 1, ScopeStats.vecBinX(ScopeStats.VEC_FULL_SCALE))
        assertEquals(0, ScopeStats.vecBinY(ScopeStats.VEC_FULL_SCALE))
        assertEquals(ScopeStats.VEC_GRID - 1, ScopeStats.vecBinY(-ScopeStats.VEC_FULL_SCALE))
        // Out-of-range values clamp to the edge bins, never outside the grid.
        assertEquals(0, ScopeStats.vecBinX(-3f))
        assertEquals(ScopeStats.VEC_GRID - 1, ScopeStats.vecBinY(-3f))
    }

    @Test
    fun everyPixelCountedOnceInEveryScope() {
        val w = 17
        val h = 13
        val img = IntArray(w * h) { i -> px((i * 37) % 256, (i * 91 + 13) % 256, (i * 53 + 7) % 256) }
        val d = ScopeStats.analyze(img, w, h)!!
        assertEquals(w * h, d.pixelCount)
        assertEquals(w * h, d.histR.sum())
        assertEquals(w * h, d.histG.sum())
        assertEquals(w * h, d.histB.sum())
        assertEquals(w * h, d.histY.sum())
        assertEquals(w * h, d.wave.sum())
        assertEquals(w * h, d.vec.sum())
    }

    @Test
    fun downscaleCapsTheLongestSide() {
        // A uniform colour box-averages to itself, at the capped size.
        val big = IntArray(640 * 480) { px(200, 100, 50) }
        val d = ScopeStats.analyze(big, 640, 480)!!
        assertEquals(320, d.width)
        assertEquals(240, d.height)
        assertEquals(320 * 240, d.pixelCount)
        assertEquals(320 * 240, d.histR[200])
        assertEquals(320 * 240, d.histG[100])
        assertEquals(320 * 240, d.histB[50])
        // A wide-short frame: width drives the cap, height scales with it.
        val wide = ScopeStats.downscale(IntArray(1000 * 100) { px(9, 9, 9) }, 1000, 100)!!
        assertEquals(320, wide.width)
        assertEquals(32, wide.height)
    }

    @Test
    fun downscaleAveragesEachBox() {
        // 640x1 alternating black/white: every 2-pixel box averages to 128.
        val img = IntArray(640) { i -> if (i % 2 == 0) px(0, 0, 0) else px(255, 255, 255) }
        val d = ScopeStats.analyze(img, 640, 1)!!
        assertEquals(320, d.width)
        assertEquals(1, d.height)
        assertEquals(320, d.histR[128])
        assertEquals(320, d.histY[128])
        // The average is neutral, so the vectorscope centre holds all of it.
        assertEquals(320, d.vec[48 * ScopeStats.VEC_GRID + 48])
    }

    @Test
    fun downscaleIsIdentityAtOrUnderTheCap() {
        val img = IntArray(100 * 50) { i -> px(i % 256, (i * 3) % 256, (i * 7) % 256) }
        val d = ScopeStats.downscale(img, 100, 50)!!
        assertEquals(100, d.width)
        assertEquals(50, d.height)
        assertTrue(d.pixels.contentEquals(img))
        // Exactly at the cap is also identity.
        val atCap = ScopeStats.downscale(IntArray(320 * 180) { px(1, 2, 3) }, 320, 180)!!
        assertEquals(320, atCap.width)
        assertEquals(180, atCap.height)
    }

    @Test
    fun alphaIsIgnored() {
        // The composed preview frame is opaque by construction; a stray
        // alpha byte must not hide a pixel from the scopes.
        val d = ScopeStats.analyze(intArrayOf(px(0, 0, 255, a = 0)), 1, 1)!!
        assertEquals(1, d.pixelCount)
        assertEquals(1, d.histB[255])
    }

    @Test
    fun degenerateInputsReturnNull() {
        assertNull(ScopeStats.analyze(IntArray(0), 0, 0))
        assertNull(ScopeStats.analyze(IntArray(3), 2, 2))
        assertNull(ScopeStats.analyze(IntArray(4), 2, -2))
        assertNull(ScopeStats.downscale(IntArray(0), 0, 5))
        assertNull(ScopeStats.downscale(IntArray(10), 11, 1))
    }

    @Test
    fun absurdDimensionsReturnNull() {
        // width x height here overflows Int (3e9 wraps negative), which used
        // to bypass the "array shorter than width x height" guard entirely
        // and read out of bounds (build 198: the size check is Long math).
        assertNull(ScopeStats.analyze(IntArray(4), 1_000_000, 3_000))
        assertNull(ScopeStats.downscale(IntArray(4), 1_000_000, 3_000))
    }
}
