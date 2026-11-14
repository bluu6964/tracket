package com.example

import com.example.model.OnionGhost
import com.example.model.OnionSkin
import com.example.model.OnionSkinSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The onion-skin ghost plan (model/OnionSkin.kt, build 190): which neighbouring
 * frame times are drawn, in which order, at what alpha and with which tint hue.
 * The engine renders exactly this list; the plan itself is pure.
 */
class OnionSkinTest {

    private fun plan(
        head: Long = 1000L,
        fps: Int = 30,
        duration: Long = 10_000L,
        count: Int = 2,
        opacity: Float = 0.4f
    ): List<OnionGhost> = OnionSkin.plan(head, fps, duration, count, opacity)

    @Test
    fun noGhostsWhenOffOrUnusable() {
        assertTrue(plan(count = 0).isEmpty())
        assertTrue(plan(fps = 0).isEmpty())
        assertTrue(plan(fps = -24).isEmpty())
        assertTrue(plan(duration = 0L).isEmpty())
        assertTrue(plan(opacity = 0f).isEmpty())
    }

    @Test
    fun stepsAreWholeFramesAtTheProjectRate() {
        // 30 fps: one frame = 33.33 ms -> steps land on 33 / 67 / 100 ms.
        assertEquals(33L, OnionSkin.frameStepMs(30, 1))
        assertEquals(67L, OnionSkin.frameStepMs(30, 2))
        assertEquals(100L, OnionSkin.frameStepMs(30, 3))
        // 24 fps: 41.67 ms frames. 60 fps: 16.67 ms frames.
        assertEquals(42L, OnionSkin.frameStepMs(24, 1))
        assertEquals(83L, OnionSkin.frameStepMs(24, 2))
        assertEquals(125L, OnionSkin.frameStepMs(24, 3))
        assertEquals(17L, OnionSkin.frameStepMs(60, 1))
        assertEquals(50L, OnionSkin.frameStepMs(60, 3))
        assertEquals(0L, OnionSkin.frameStepMs(0, 1))
        assertEquals(0L, OnionSkin.frameStepMs(30, 0))
    }

    @Test
    fun planCoversBothSidesAtFrameSteps() {
        val ghosts = plan()
        val byTime = ghosts.associateBy { it.timeMs }
        assertEquals(4, ghosts.size)
        assertEquals(-1, byTime.getValue(967L).side)
        assertEquals(1, byTime.getValue(967L).step)
        assertEquals(-1, byTime.getValue(933L).side)
        assertEquals(2, byTime.getValue(933L).step)
        assertEquals(+1, byTime.getValue(1033L).side)
        assertEquals(1, byTime.getValue(1033L).step)
        assertEquals(+1, byTime.getValue(1067L).side)
        assertEquals(2, byTime.getValue(1067L).step)
    }

    @Test
    fun alphaFallsOffWithDistanceAndTintsFollowTheConvention() {
        val ghosts = plan()
        val near = ghosts.first { it.step == 1 }
        val far = ghosts.first { it.step == 2 }
        // The nearest ghost carries the full base opacity; step 2 of 2 gets half.
        assertEquals(0.4f, near.alpha, 0.0001f)
        assertEquals(0.2f, far.alpha, 0.0001f)
        // Previous frames warm red, next frames green — on both steps.
        for (g in ghosts) {
            if (g.side < 0) assertEquals(OnionSkin.PREV_HUE_DEG, g.hueDeg, 0.0001f)
            else assertEquals(OnionSkin.NEXT_HUE_DEG, g.hueDeg, 0.0001f)
        }
        assertEquals(0f, OnionSkin.PREV_HUE_DEG, 0.0001f)
        assertEquals(120f, OnionSkin.NEXT_HUE_DEG, 0.0001f)
    }

    @Test
    fun drawOrderIsFarthestFirst() {
        // Farthest ghosts are drawn first so nearer ones composite over them;
        // at the same distance the previous frame precedes the next frame.
        assertEquals(
            listOf(933L, 1067L, 967L, 1033L),
            plan().map { it.timeMs }
        )
    }

    @Test
    fun ghostsClampAtTheProjectStart() {
        // Playhead 50 ms in, 30 fps, 3 per side: only the -33 ms ghost survives
        // on the previous side (-67 / -100 fall before 0 and are dropped, not
        // clamped onto 0); the next side is complete.
        val ghosts = plan(head = 50L, count = 3)
        assertEquals(listOf(17L), ghosts.filter { it.side < 0 }.map { it.timeMs })
        assertEquals(listOf(83L, 117L, 150L), ghosts.filter { it.side > 0 }.map { it.timeMs }.sorted())
        // A playhead before the project (defensive) behaves like one at 0.
        val atZero = plan(head = -500L, count = 1)
        assertTrue(atZero.none { it.side < 0 })
        assertEquals(listOf(33L), atZero.map { it.timeMs })
    }

    @Test
    fun ghostsClampAtTheProjectEnd() {
        // Playhead 40 ms before the end: the +33 ms ghost survives, +67 does not.
        val ghosts = plan(head = 9960L, count = 2)
        assertEquals(listOf(9993L), ghosts.filter { it.side > 0 }.map { it.timeMs })
        assertEquals(listOf(9893L, 9927L), ghosts.filter { it.side < 0 }.map { it.timeMs }.sorted())
        // A playhead past the end behaves like one at the end.
        val atEnd = plan(head = 99_999L, count = 1)
        assertTrue(atEnd.none { it.side > 0 })
        assertEquals(listOf(9967L), atEnd.map { it.timeMs })
    }

    @Test
    fun countIsCappedAtThreePerSide() {
        val ghosts = plan(count = 9)
        assertEquals(3, ghosts.count { it.side < 0 })
        assertEquals(3, ghosts.count { it.side > 0 })
        assertEquals(6, ghosts.size)
    }

    @Test
    fun normalizeClampsTheSettings() {
        val wild = OnionSkin.normalize(OnionSkinSettings(enabled = true, countPerSide = 12, opacity = 2f))
        assertEquals(OnionSkin.MAX_PER_SIDE, wild.countPerSide)
        assertEquals(OnionSkin.MAX_OPACITY, wild.opacity, 0.0001f)
        val low = OnionSkin.normalize(OnionSkinSettings(countPerSide = 0, opacity = 0f))
        assertEquals(1, low.countPerSide)
        assertEquals(OnionSkin.MIN_OPACITY, low.opacity, 0.0001f)
        // Defaults are the shipped starting point: off, 2 per side, 35%.
        val d = OnionSkinSettings()
        assertEquals(false, d.enabled)
        assertEquals(OnionSkin.DEFAULT_COUNT, d.countPerSide)
        assertEquals(OnionSkin.DEFAULT_OPACITY, d.opacity, 0.0001f)
    }

    // ---- build 198 hardening: NaN opacity -------------------------------------------------------------
    // coerceIn passes NaN through: the plan must draw nothing and normalize
    // must fall back to the default rather than carrying a NaN alpha into
    // the renderer's colorize pass.

    @Test
    fun nanOpacityDrawsNothingAndNormalizesToTheDefault() {
        assertTrue(OnionSkin.plan(1000L, 30, 5000L, 2, Float.NaN).isEmpty())
        assertEquals(
            OnionSkin.DEFAULT_OPACITY,
            OnionSkin.normalize(OnionSkinSettings(opacity = Float.NaN)).opacity, 0f
        )
    }
}
