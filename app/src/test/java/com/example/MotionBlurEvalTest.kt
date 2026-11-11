package com.example

import com.example.engine.MotionBlurEval
import com.example.model.ClipFx
import com.example.model.ClipFxLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionBlurEvalTest {

    // A layer sliding right at 0.3 preview-px per ms (9.6 px per 33 ms frame) on a 1920 px tall canvas.
    private val slideRight: (Long) -> MotionBlurEval.Pose? = { ms -> MotionBlurEval.Pose(ms * 0.3f, 0f, 0f) }
    private val still: (Long) -> MotionBlurEval.Pose? = { MotionBlurEval.Pose(100f, 50f, 0f) }

    @Test
    fun lengthScalesWithSpeedAndTune() {
        // Crossing the canvas height in 30 frames: 675 / 30 = 22.5 at full strength.
        assertEquals(22.5f, MotionBlurEval.lengthFor(1f / 30f, 1f), 0.01f)
        assertEquals(11.25f, MotionBlurEval.lengthFor(1f / 30f, 0.5f), 0.01f)
        assertEquals(0f, MotionBlurEval.lengthFor(1f / 30f, 0f), 0f)
        assertEquals(100f, MotionBlurEval.lengthFor(1f, 1f), 0f)   // clamped: a full canvas height per frame
        assertEquals(0.675f, MotionBlurEval.lengthFor(0.001f, 1f), 0.01f)
    }

    @Test
    fun slideRightBlursRight() {
        val b = MotionBlurEval.blurAt(slideRight, 1000L, 33L, 1920f, 1f)
        assertTrue(b != null)
        assertEquals(0f, b!!.first, 0.01f)
        // 32 ms sampled (t - 16 .. t + 16) at 0.3 px/ms = 9.6 px per frame: 9.6 / 1920 * 675.
        assertEquals(9.6f / 1920f * 675f, b.second, 0.01f)
    }

    @Test
    fun angleFollowsTheMotionDirection() {
        val down: (Long) -> MotionBlurEval.Pose? = { ms -> MotionBlurEval.Pose(0f, ms * 0.3f, 0f) }
        assertEquals(270f, MotionBlurEval.blurAt(down, 1000L, 33L, 1920f, 1f)!!.first, 0.01f)
        val up: (Long) -> MotionBlurEval.Pose? = { ms -> MotionBlurEval.Pose(0f, -ms * 0.3f, 0f) }
        assertEquals(90f, MotionBlurEval.blurAt(up, 1000L, 33L, 1920f, 1f)!!.first, 0.01f)
        val left: (Long) -> MotionBlurEval.Pose? = { ms -> MotionBlurEval.Pose(-ms * 0.3f, 0f, 0f) }
        assertEquals(180f, MotionBlurEval.blurAt(left, 1000L, 33L, 1920f, 1f)!!.first, 0.01f)
        // Down-right at 45 degrees: counter-clockwise on screen that is 315.
        val diag: (Long) -> MotionBlurEval.Pose? = { ms -> MotionBlurEval.Pose(ms * 0.3f, ms * 0.3f, 0f) }
        assertEquals(315f, MotionBlurEval.blurAt(diag, 1000L, 33L, 1920f, 1f)!!.first, 0.01f)
    }

    @Test
    fun rotationTurnsTheBlurWithThePicture() {
        // The picture is turned 30 degrees clockwise, so the blur along screen-right is 30 in picture space.
        val turned: (Long) -> MotionBlurEval.Pose? = { ms -> MotionBlurEval.Pose(ms * 0.3f, 0f, 30f) }
        assertEquals(30f, MotionBlurEval.blurAt(turned, 1000L, 33L, 1920f, 1f)!!.first, 0.01f)
        val wrapped: (Long) -> MotionBlurEval.Pose? = { ms -> MotionBlurEval.Pose(ms * 0.3f, 0f, -10f) }
        assertEquals(350f, MotionBlurEval.blurAt(wrapped, 1000L, 33L, 1920f, 1f)!!.first, 0.01f)
    }

    @Test
    fun stillOrBarelyMovingIsNoBlur() {
        assertNull(MotionBlurEval.blurAt(still, 1000L, 33L, 1920f, 1f))
        assertNull(MotionBlurEval.blurAt(slideRight, 1000L, 33L, 1920f, 0f))   // tune 0
        assertNull(MotionBlurEval.blurAt(slideRight, 1000L, 0L, 1920f, 1f))    // no frame duration
        assertNull(MotionBlurEval.blurAt(slideRight, 1000L, 33L, 0f, 1f))      // no canvas height
        assertNull(MotionBlurEval.blurAt({ null }, 1000L, 33L, 1920f, 1f))     // no pose at all
        val crawl: (Long) -> MotionBlurEval.Pose? = { ms -> MotionBlurEval.Pose(ms * 0.001f, 0f, 0f) }
        assertNull(MotionBlurEval.blurAt(crawl, 1000L, 33L, 1920f, 1f))        // 0.03 px per frame
    }

    @Test
    fun resolveTurnsMotionBlurIntoDirectionalBlur() {
        val fx = ClipFx(type = ClipFxLibrary.MOTION_BLUR, values = listOf(100f))
        val out = MotionBlurEval.resolve(listOf(fx), slideRight, 1000L, 33L, 1920f)
        assertEquals(1, out.size)
        assertEquals(ClipFxLibrary.DIR_BLUR, out[0].type)
        assertEquals(fx.id, out[0].id)   // the panel still sees the same effect
        assertEquals(0f, out[0].values[0], 0.01f)
        assertEquals(9.6f / 1920f * 675f, out[0].values[1], 0.01f)
        assertEquals(0f, out[0].values[2], 0f)
    }

    @Test
    fun resolveDropsMotionBlurOnAStillFrameAndKeepsTheRest() {
        val mb = ClipFx(type = ClipFxLibrary.MOTION_BLUR, values = listOf(100f))
        val blur = ClipFx(type = ClipFxLibrary.BLUR, values = listOf(20f))
        assertTrue(MotionBlurEval.resolve(listOf(mb), still, 1000L, 33L, 1920f).isEmpty())
        val mixed = MotionBlurEval.resolve(listOf(mb, blur), slideRight, 1000L, 33L, 1920f)
        assertEquals(2, mixed.size)
        assertEquals(ClipFxLibrary.DIR_BLUR, mixed[0].type)
        assertEquals(ClipFxLibrary.BLUR, mixed[1].type)
        assertEquals(20f, mixed[1].values[0], 0f)
    }

    @Test
    fun resolveLeavesOtherEffectsAlone() {
        val blur = ClipFx(type = ClipFxLibrary.BLUR, values = listOf(20f))
        val list = listOf(blur)
        assertTrue(MotionBlurEval.resolve(list, slideRight, 1000L, 33L, 1920f) === list)
        val off = ClipFx(type = ClipFxLibrary.MOTION_BLUR, enabled = false, values = listOf(100f))
        val kept = MotionBlurEval.resolve(listOf(off), slideRight, 1000L, 33L, 1920f)
        assertEquals(1, kept.size)
        assertEquals(ClipFxLibrary.MOTION_BLUR, kept[0].type)   // disabled: untouched, never resolved
    }

    @Test
    fun spinBlursSpinOnly() {
        // Turning 0.3 deg/ms: 9.6 degrees over the sampled 32 ms frame. Half-span rule (same as
        // the directional blur): amount = 2 * dRad * 100, spin/zoom = 0.
        val spin: (Long) -> MotionBlurEval.Pose? = { ms -> MotionBlurEval.Pose(0f, 0f, ms * 0.3f) }
        val r = MotionBlurEval.radialAt(spin, 1000L, 33L, 1f)
        assertTrue(r != null)
        assertEquals(2f * Math.toRadians(9.6).toFloat() * 100f, r!!.first, 0.05f)
        assertEquals(0f, r.second, 0.01f)
    }

    @Test
    fun zoomBlursZoomOnly() {
        // Growing 0.3 scale-points/ms: 9.6 points over the frame, mid-scale 400 -> ds = 0.024.
        // amount = 2 * ds / 0.6 * 100, spin/zoom = 100.
        val zoom: (Long) -> MotionBlurEval.Pose? = { ms -> MotionBlurEval.Pose(0f, 0f, 0f, 100f + ms * 0.3f) }
        val r = MotionBlurEval.radialAt(zoom, 1000L, 33L, 1f)
        assertTrue(r != null)
        assertEquals(2f * 0.024f / 0.6f * 100f, r!!.first, 0.05f)
        assertEquals(100f, r.second, 0.01f)
    }

    @Test
    fun spinAndZoomShareOneRadialBlur() {
        // 9.6 deg of spin (spinPart 0.335) + ds 0.024 of zoom (zoomPart 0.08): one op, mixed.
        val both: (Long) -> MotionBlurEval.Pose? = { ms -> MotionBlurEval.Pose(0f, 0f, ms * 0.3f, 100f + ms * 0.3f) }
        val r = MotionBlurEval.radialAt(both, 1000L, 33L, 1f)
        assertTrue(r != null)
        assertEquals((0.3351f + 0.08f) * 100f, r!!.first, 0.1f)
        assertEquals(0.08f / 0.4151f * 100f, r.second, 0.1f)
        // Half strength halves the amount, not the mix.
        val half = MotionBlurEval.radialAt(both, 1000L, 33L, 0.5f)
        assertEquals(r.first / 2f, half!!.first, 0.1f)
        assertEquals(r.second, half.second, 0.01f)
    }

    @Test
    fun rotationWrapsAcross180() {
        // 179 -> -179 is a 2 degree turn, not 358.
        val wrap: (Long) -> MotionBlurEval.Pose? = { ms ->
            MotionBlurEval.Pose(0f, 0f, if (ms < 1000L) 179f else -179f)
        }
        val r = MotionBlurEval.radialAt(wrap, 1000L, 33L, 1f)
        assertTrue(r != null)
        assertEquals(2f * Math.toRadians(2.0).toFloat() * 100f, r!!.first, 0.05f)
        assertEquals(0f, r.second, 0.01f)
    }

    @Test
    fun radialStillOrDisabledIsNoBlur() {
        val spin: (Long) -> MotionBlurEval.Pose? = { ms -> MotionBlurEval.Pose(0f, 0f, ms * 0.3f) }
        val zoom: (Long) -> MotionBlurEval.Pose? = { ms -> MotionBlurEval.Pose(0f, 0f, 0f, 100f + ms * 0.3f) }
        assertNull(MotionBlurEval.radialAt(still, 1000L, 33L, 1f))
        assertNull(MotionBlurEval.radialAt(spin, 1000L, 33L, 0f))          // tune 0
        assertNull(MotionBlurEval.radialAt(spin, 1000L, 0L, 1f))           // no frame duration
        assertNull(MotionBlurEval.radialAt(spin, 1000L, 33L, 1f, useAngle = false))  // spin disabled
        assertNull(MotionBlurEval.radialAt(zoom, 1000L, 33L, 1f, useScale = false))  // zoom disabled
        assertNull(MotionBlurEval.radialAt(spin, 1000L, 33L, 1f, useScale = false, useAngle = false))
        val crawl: (Long) -> MotionBlurEval.Pose? = { ms -> MotionBlurEval.Pose(0f, 0f, ms * 0.001f) }
        assertNull(MotionBlurEval.radialAt(crawl, 1000L, 33L, 1f))         // 0.03 deg per frame
    }

    @Test
    fun resolveExpandsMotionBlurIntoDirectionalAndRadial() {
        // Sliding + turning + growing at once: one Motion Blur becomes two ops, in order.
        val moving: (Long) -> MotionBlurEval.Pose? = { ms ->
            MotionBlurEval.Pose(ms * 0.3f, 0f, ms * 0.3f, 100f + ms * 0.3f)
        }
        val fx = ClipFx(type = ClipFxLibrary.MOTION_BLUR, values = listOf(100f, 1f, 1f, 1f))
        val out = MotionBlurEval.resolve(listOf(fx), moving, 1000L, 33L, 1920f)
        assertEquals(2, out.size)
        assertEquals(ClipFxLibrary.DIR_BLUR, out[0].type)
        assertEquals(9.6f / 1920f * 675f, out[0].values[1], 0.01f)
        assertEquals(ClipFxLibrary.RADIAL_BLUR, out[1].type)
        assertEquals((0.3351f + 0.08f) * 100f, out[1].values[0], 0.1f)
        assertEquals(0.08f / 0.4151f * 100f, out[1].values[1], 0.1f)
        assertEquals(50f, out[1].values[2], 0f)   // centre = layer centre
        assertEquals(50f, out[1].values[3], 0f)
        assertEquals(fx.id, out[1].id)
    }

    @Test
    fun resolveHonoursTheUseFlags() {
        val moving: (Long) -> MotionBlurEval.Pose? = { ms ->
            MotionBlurEval.Pose(ms * 0.3f, 0f, ms * 0.3f, 100f + ms * 0.3f)
        }
        // Scale only: the spin is ignored, the position is ignored — one pure-zoom radial blur.
        val scaleOnly = ClipFx(type = ClipFxLibrary.MOTION_BLUR, values = listOf(100f, 0f, 1f, 0f))
        val s = MotionBlurEval.resolve(listOf(scaleOnly), moving, 1000L, 33L, 1920f)
        assertEquals(1, s.size)
        assertEquals(ClipFxLibrary.RADIAL_BLUR, s[0].type)
        assertEquals(2f * 0.024f / 0.6f * 100f, s[0].values[0], 0.05f)
        assertEquals(100f, s[0].values[1], 0.01f)
        // Rotation only: one pure-spin radial blur.
        val angleOnly = ClipFx(type = ClipFxLibrary.MOTION_BLUR, values = listOf(100f, 0f, 0f, 1f))
        val a = MotionBlurEval.resolve(listOf(angleOnly), moving, 1000L, 33L, 1920f)
        assertEquals(1, a.size)
        assertEquals(ClipFxLibrary.RADIAL_BLUR, a[0].type)
        assertEquals(2f * Math.toRadians(9.6).toFloat() * 100f, a[0].values[0], 0.05f)
        assertEquals(0f, a[0].values[1], 0.01f)
        // All three off: nothing to blur.
        val none = ClipFx(type = ClipFxLibrary.MOTION_BLUR, values = listOf(100f, 0f, 0f, 0f))
        assertTrue(MotionBlurEval.resolve(listOf(none), moving, 1000L, 33L, 1920f).isEmpty())
        // A Motion Blur made in the app's panel (only Strength) blurs all three kinds.
        val panel = ClipFx(type = ClipFxLibrary.MOTION_BLUR, values = listOf(100f))
        assertEquals(2, MotionBlurEval.resolve(listOf(panel), moving, 1000L, 33L, 1920f).size)
    }

    @Test
    fun canvasHeightFromPreviewWidth() {
        assertEquals(1920f, MotionBlurEval.canvasHeightPx(1080f, 1080, 1920), 0.01f)
        assertEquals(960f, MotionBlurEval.canvasHeightPx(540f, 1080, 1920), 0.01f)
        assertEquals(1920f, MotionBlurEval.canvasHeightPx(0f, 1080, 1920), 0f)   // no preview yet: surface size
        assertEquals(1920f, MotionBlurEval.canvasHeightPx(1080f, 0, 1920), 0f)
    }
}
