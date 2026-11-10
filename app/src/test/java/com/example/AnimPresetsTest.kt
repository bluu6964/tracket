package com.example

import com.example.model.ANIM_TAG_IN
import com.example.model.ANIM_TAG_OUT
import com.example.model.AnimDir
import com.example.model.AnimPresets
import com.example.model.AnimProp
import com.example.model.AnimSlot
import com.example.model.Easing
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.at
import com.example.model.maxAnimMs
import com.example.model.withClipAnim
import com.example.model.withEndMoved
import com.example.model.withKeyframe
import com.example.model.withTrimmedStartKeyframes
import com.example.model.withoutClipAnim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimPresetsTest {

    private val W = 1000f
    private val H = 562f

    private fun clip() = TimelineClip(
        id = "a", name = "a", type = LayerType.SHAPE, startMs = 1000, durationMs = 4000, color = 0xFF000000
    )

    private fun preset(id: String) = AnimPresets.byId(id)!!

    private fun TimelineClip.apply(
        slot: AnimSlot, id: String, dir: AnimDir = AnimDir.LEFT, ms: Long = 500, easing: Easing? = Easing.LINEAR
    ) = withClipAnim(slot, preset(id), dir, ms, easing, W, H)

    @Test
    fun fadeIn_startsInvisible_endsOpaque_andLayerKeepsItsValueAfter() {
        val c = clip().apply(AnimSlot.IN, "fade")
        assertEquals(0f, c.at(1000).opacity, 0.01f)
        assertEquals(50f, c.at(1250).opacity, 1f)
        assertEquals(100f, c.at(1500).opacity, 0.01f)
        assertEquals(100f, c.at(4000).opacity, 0.01f)
    }

    @Test
    fun fadeOut_holdsThenFadesToZeroAtTheEnd() {
        val c = clip().apply(AnimSlot.OUT, "fade")
        assertEquals(100f, c.at(1000).opacity, 0.01f)
        assertEquals(100f, c.at(4400).opacity, 0.01f)
        assertEquals(50f, c.at(4750).opacity, 1f)
        assertEquals(0f, c.at(5000).opacity, 0.01f)
    }

    @Test
    fun inAndOutTogether_keepTheMiddleSteady() {
        val c = clip().apply(AnimSlot.IN, "fade").apply(AnimSlot.OUT, "fade")
        assertEquals(0f, c.at(1000).opacity, 0.01f)
        assertEquals(100f, c.at(3000).opacity, 0.01f)
        assertEquals(0f, c.at(5000).opacity, 0.01f)
        assertNotNull(c.animIn)
        assertNotNull(c.animOut)
    }

    @Test
    fun slideFromEveryDirection_startsOutsideTheCanvas() {
        val c0 = clip()
        val l = c0.apply(AnimSlot.IN, "slide", AnimDir.LEFT).at(1000)
        val r = c0.apply(AnimSlot.IN, "slide", AnimDir.RIGHT).at(1000)
        val u = c0.apply(AnimSlot.IN, "slide", AnimDir.UP).at(1000)
        val d = c0.apply(AnimSlot.IN, "slide", AnimDir.DOWN).at(1000)
        assertTrue(l.transformX <= -W && l.transformY == 0f)
        assertTrue(r.transformX >= W && r.transformY == 0f)
        assertTrue(u.transformY <= -H && u.transformX == 0f)
        assertTrue(d.transformY >= H && d.transformX == 0f)
        // and arrives exactly at the normal position
        assertEquals(0f, c0.apply(AnimSlot.IN, "slide", AnimDir.LEFT).at(1500).transformX, 0.01f)
    }

    @Test
    fun slideOut_leavesTowardsTheChosenSide() {
        val c = clip().apply(AnimSlot.OUT, "slide", AnimDir.RIGHT)
        assertEquals(0f, c.at(3000).transformX, 0.01f)
        assertTrue(c.at(5000).transformX >= W)
    }

    @Test
    fun slideRespectsABasePosition() {
        val base = clip().copy(transformX = 120f, transformY = -40f)
        val c = base.apply(AnimSlot.IN, "slide", AnimDir.LEFT)
        assertEquals(120f, c.at(2000).transformX, 0.01f)
        assertEquals(-40f, c.at(1000).transformY, 0.01f)
    }

    @Test
    fun zoomAndPop_growFromNothing_popOvershoots() {
        val z = clip().apply(AnimSlot.IN, "zoom")
        assertEquals(0f, z.at(1000).scalePercent, 0.01f)
        assertEquals(100f, z.at(2000).scalePercent, 0.01f)
        val pop = clip().apply(AnimSlot.IN, "pop", easing = null)
        var max = 0f
        for (t in 1000..1600 step 10) max = maxOf(max, pop.at(t.toLong()).scalePercent)
        assertTrue("Back easing must overshoot", max > 100.5f)
        assertEquals(100f, pop.at(2000).scalePercent, 0.01f)
    }

    @Test
    fun spin_turnsInTheChosenSense_andLandsOnTheNormalAngle() {
        val cw = clip().apply(AnimSlot.IN, "spin", AnimDir.RIGHT)
        val ccw = clip().apply(AnimSlot.IN, "spin", AnimDir.LEFT)
        assertEquals(-180f, cw.at(1000).rotationDeg, 0.01f)
        assertEquals(180f, ccw.at(1000).rotationDeg, 0.01f)
        assertEquals(0f, cw.at(1500).rotationDeg, 0.01f)
        val out = clip().apply(AnimSlot.OUT, "spin", AnimDir.RIGHT)
        assertEquals(180f, out.at(5000).rotationDeg, 0.01f)
    }

    @Test
    fun wipeFromLeft_growsFromTheLeftEdge() {
        val c = clip().apply(AnimSlot.IN, "wipe", AnimDir.LEFT)
        val s = c.at(1000)
        assertEquals(5f, s.cropWidthPercent, 0.01f)
        assertEquals(100f, s.cropHeightPercent, 0.01f)
        // left edge fraction = (1 - w) * pos  must stay 0 the whole time
        for (t in 1000..1500 step 50) {
            val k = c.at(t.toLong())
            assertEquals(0f, (1f - k.cropWidthPercent / 100f) * (k.cropHorizontalPercent / 100f), 0.001f)
        }
        assertEquals(100f, c.at(1500).cropWidthPercent, 0.01f)
    }

    @Test
    fun wipeFromRight_keepsTheRightEdgeAtTheCanvasEdge() {
        val c = clip().apply(AnimSlot.IN, "wipe", AnimDir.RIGHT)
        for (t in 1000..1500 step 50) {
            val k = c.at(t.toLong())
            val l = (1f - k.cropWidthPercent / 100f) * (k.cropHorizontalPercent / 100f)
            assertEquals(1f, l + k.cropWidthPercent / 100f, 0.001f)
        }
    }

    @Test
    fun wipeFromTop_andBottom_useTheVerticalAxis() {
        val up = clip().apply(AnimSlot.IN, "wipe", AnimDir.UP).at(1000)
        assertEquals(5f, up.cropHeightPercent, 0.01f)
        assertEquals(100f, up.cropWidthPercent, 0.01f)
        val down = clip().apply(AnimSlot.IN, "wipe", AnimDir.DOWN)
        for (t in 1000..1500 step 100) {
            val k = down.at(t.toLong())
            val top = (1f - k.cropHeightPercent / 100f) * (k.cropVerticalPercent / 100f)
            assertEquals(1f, top + k.cropHeightPercent / 100f, 0.001f)
        }
    }

    @Test
    fun applyingAnotherAnimation_replacesTheOldOneCompletely() {
        val c = clip().apply(AnimSlot.IN, "slide").apply(AnimSlot.IN, "fade")
        assertNull(c.keyframes[AnimProp.POSITION])
        assertEquals("fade", c.animIn?.preset)
        assertEquals(0f, c.at(1000).transformX, 0.01f)
    }

    @Test
    fun none_removesTheAnimation_andTheLayerIsPlainAgain() {
        val c = clip().apply(AnimSlot.IN, "slide_fade").withoutClipAnim(AnimSlot.IN)
        assertTrue(c.keyframes.isEmpty())
        assertNull(c.animIn)
        assertEquals(100f, c.at(1000).opacity, 0.01f)
    }

    @Test
    fun removingIn_keepsOut() {
        val c = clip().apply(AnimSlot.IN, "fade").apply(AnimSlot.OUT, "fade").withoutClipAnim(AnimSlot.IN)
        assertTrue(c.keyframes[AnimProp.OPACITY]!!.all { it.tag == ANIM_TAG_OUT })
        assertEquals(100f, c.at(1000).opacity, 0.01f)
        assertEquals(0f, c.at(5000).opacity, 0.01f)
    }

    @Test
    fun theAnimationIsMadeOfNormalTaggedKeyframes_withTheChosenEasing() {
        val e = Easing(0.42f, 0f, 1f, 1f)
        val c = clip().apply(AnimSlot.IN, "fade", easing = e)
        val l = c.keyframes[AnimProp.OPACITY]!!
        assertEquals(2, l.size)
        assertTrue(l.all { it.tag == ANIM_TAG_IN })
        assertTrue(l[0].easing.sameAs(e))
        // ease-in is slower than linear at the half way point
        assertTrue(c.at(1250).opacity < 45f)
    }

    @Test
    fun userKeyframesOutsideTheWindowSurvive_insideAreReplaced() {
        val base = clip()
            .withKeyframe(AnimProp.OPACITY, 2000, 70f, 0f)
            .withKeyframe(AnimProp.OPACITY, 200, 10f, 0f)
        val c = base.apply(AnimSlot.IN, "fade")
        val times = c.keyframes[AnimProp.OPACITY]!!.map { it.timeMs }
        assertEquals(listOf(0L, 500L, 2000L), times)
    }

    @Test
    fun durationIsClampedToWhatIsLeftAfterTheOtherSlot() {
        val c = clip().apply(AnimSlot.OUT, "fade", ms = 3000)
        assertEquals(1000L - 50L, c.maxAnimMs(AnimSlot.IN) - 0L)
        val withIn = c.apply(AnimSlot.IN, "fade", ms = 3000)
        val inEnd = withIn.keyframes[AnimProp.OPACITY]!!.filter { it.tag == ANIM_TAG_IN }.maxOf { it.timeMs }
        val outStart = withIn.keyframes[AnimProp.OPACITY]!!.filter { it.tag == ANIM_TAG_OUT }.minOf { it.timeMs }
        assertTrue("In must end before Out starts", inEnd < outStart)
    }

    @Test
    fun tooShortLayer_isLeftUntouched() {
        val tiny = clip().copy(durationMs = 80)
        val c = tiny.apply(AnimSlot.IN, "fade")
        assertTrue(c.keyframes.isEmpty())
    }

    @Test
    fun trimmingTheLeftEdge_keepsInAtTheStart_butMovesOutWithTheEnd() {
        val c = clip().apply(AnimSlot.IN, "fade").apply(AnimSlot.OUT, "fade")
        val t = c.copy(startMs = c.startMs + 1000, durationMs = c.durationMs - 1000).withTrimmedStartKeyframes(1000)
        val l = t.keyframes[AnimProp.OPACITY]!!
        assertEquals(listOf(0L, 500L), l.filter { it.tag == ANIM_TAG_IN }.map { it.timeMs })
        assertEquals(listOf(2500L, 3000L), l.filter { it.tag == ANIM_TAG_OUT }.map { it.timeMs })
        assertEquals(0f, t.at(t.startMs).opacity, 0.01f)
        assertEquals(0f, t.at(t.endMs).opacity, 0.01f)
    }

    @Test
    fun trimmingTheRightEdge_keepsOutAtTheEnd() {
        val c = clip().apply(AnimSlot.OUT, "fade")
        val t = c.copy(durationMs = 3000).withEndMoved(-1000)
        assertEquals(0f, t.at(t.endMs).opacity, 0.01f)
        assertEquals(100f, t.at(t.startMs + 2000).opacity, 0.01f)
        val longer = c.copy(durationMs = 5000).withEndMoved(1000)
        assertEquals(0f, longer.at(longer.endMs).opacity, 0.01f)
    }

    @Test
    fun everyPreset_inAndOut_everyDirection_endsAtTheRestPose_andIsFiniteEverywhere() {
        val rest = clip().copy(transformX = 30f, transformY = -20f, scalePercent = 80f, rotationDeg = 15f)
        for (p in AnimPresets.ALL) {
            val dirs = if (p.dirs.isEmpty()) listOf(AnimDir.LEFT) else p.dirs
            for (d in dirs) for (slot in AnimSlot.values()) {
                val c = rest.withClipAnim(slot, p, d, p.defaultMs, null, W, H)
                assertNotNull(p.id, if (slot == AnimSlot.IN) c.animIn else c.animOut)
                // At rest (middle of the layer) nothing is animated away.
                val mid = c.at(3000)
                assertEquals(p.id, 30f, mid.transformX, 0.01f)
                assertEquals(p.id, -20f, mid.transformY, 0.01f)
                assertEquals(p.id, 80f, mid.scalePercent, 0.01f)
                assertEquals(p.id, 15f, mid.rotationDeg, 0.01f)
                assertEquals(p.id, 100f, mid.opacity, 0.01f)
                for (t in 1000..5000 step 100) {
                    val k = c.at(t.toLong())
                    assertFalse(p.id, k.transformX.isNaN() || k.scalePercent.isNaN() || k.rotationDeg.isNaN() || k.opacity.isNaN())
                }
            }
        }
    }

    @Test
    fun inKeyframesAreTaggedIn() {
        val c = clip().apply(AnimSlot.IN, "zoom_fade")
        assertTrue(c.keyframes.values.flatten().all { it.tag == ANIM_TAG_IN })
        assertEquals(setOf(AnimProp.SCALE, AnimProp.OPACITY), c.keyframes.keys)
    }
}
