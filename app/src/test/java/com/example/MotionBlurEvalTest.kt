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
    fun canvasHeightFromPreviewWidth() {
        assertEquals(1920f, MotionBlurEval.canvasHeightPx(1080f, 1080, 1920), 0.01f)
        assertEquals(960f, MotionBlurEval.canvasHeightPx(540f, 1080, 1920), 0.01f)
        assertEquals(1920f, MotionBlurEval.canvasHeightPx(0f, 1080, 1920), 0f)   // no preview yet: surface size
        assertEquals(1920f, MotionBlurEval.canvasHeightPx(1080f, 0, 1920), 0f)
    }
}
