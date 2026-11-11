package com.example

import com.example.engine.AmFxEval
import com.example.model.ClipFx
import com.example.model.ClipFxLibrary
import com.example.model.Easing
import com.example.model.LayerType
import com.example.model.TimelineClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AmFxEval: the keyframed-parameter tails of Alight's Mirror (alpha) and Glow Scan
 * (threshold). The tracks pinned here are the corpus' own: celz-preset-9x16's glowscan
 * threshold (1 -> 0 across t = 0.908925, the end key eased (0, 0, 0, 1)) and
 * winkoko-preset-may24's second mirror alpha (keys OUTSIDE the span: -0.875269 -> 0,
 * 0.959140 -> 1, eased (0.42, 0, 0.58, 1)).
 */
class AmFxEvalTest {

    private fun clip() = TimelineClip(
        id = "c1", name = "Box", type = LayerType.SHAPE, startMs = 500L, durationMs = 2000L,
        color = 0xFF000000L
    )

    private fun valuesWithTail(slotValue: Float, slot: Int, tail: List<Float>): List<Float> {
        val base = MutableList(7) { 0f }
        base[slot] = slotValue
        return base + tail
    }

    @Test
    fun celzThresholdTrackEvaluatesWithItsEasing() {
        val tail = AmFxEval.encodeTrack(
            listOf(
                Triple(0f, 1f, null),
                Triple(0.908925f, 0f, Easing(0f, 0f, 0f, 1f))
            )
        )
        val values = valuesWithTail(100f, 0, tail)
        assertEquals(1f, AmFxEval.trackValue(values, 0f)!!, 0.001f)
        // Half-way through the segment the (0,0,0,1) curve is already at ~0.89 of the way:
        // threshold = 1 - 0.89 = 0.11.
        assertEquals(0.1101f, AmFxEval.trackValue(values, 0.4544625f)!!, 0.002f)
        assertEquals(0f, AmFxEval.trackValue(values, 0.908925f)!!, 0.001f)
        assertEquals(0f, AmFxEval.trackValue(values, 1f)!!, 0.001f)   // clamped after the last key
    }

    @Test
    fun outOfSpanKeysInterpolateAcrossTheLayer() {
        val tail = AmFxEval.encodeTrack(
            listOf(
                Triple(-0.875269f, 0f, null),
                Triple(0.959140f, 1f, Easing(0.42f, 0f, 0.58f, 1f))
            )
        )
        val values = valuesWithTail(0f, 2, tail)
        // At the layer's start the pulse is already ~46 % up (the segment spans the layer).
        assertEquals(0.4606f, AmFxEval.trackValue(values, 0f)!!, 0.002f)
        assertEquals(1f, AmFxEval.trackValue(values, 1f)!!, 0.001f)
    }

    @Test
    fun resolveRewritesOnlyTheTrackedSlot() {
        val tail = AmFxEval.encodeTrack(listOf(Triple(0f, 0f, null), Triple(1f, 1f, null)))
        val mirror = ClipFx(
            type = ClipFxLibrary.AM_MIRROR,
            values = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f) + tail
        )
        val clip = clip()
        val mid = AmFxEval.resolve(listOf(mirror), clip, 1500L).single()   // rel 0.5
        assertEquals(50f, mid.values[2], 0.001f)
        assertEquals(mirror.values.subList(7, mirror.values.size), mid.values.subList(7, mid.values.size))
        val atStart = AmFxEval.resolve(listOf(mirror), clip, 500L).single()
        assertEquals(0f, atStart.values[2], 0.001f)
        // A glow scan with the same tail resolves its threshold (slot 0), not an alpha.
        val scan = ClipFx(type = ClipFxLibrary.GLOW_SCAN, values = listOf(0f, 10f, 2f, 5f, 100f, 100f, 35071f) + tail)
        val scanMid = AmFxEval.resolve(listOf(scan), clip, 1500L).single()
        assertEquals(50f, scanMid.values[0], 0.001f)
        assertEquals(35071f, scanMid.values[6], 0.001f)   // the packed colour is untouched
    }

    @Test
    fun untailedAndDisabledEffectsPassThrough() {
        val plain = listOf(ClipFx(type = ClipFxLibrary.AM_MIRROR, values = listOf(0f, 0f, 100f, 0f)))
        assertSame(plain, AmFxEval.resolve(plain, clip(), 1500L))
        assertNull(AmFxEval.trackValue(plain[0].values, 0.5f))
        val tail = AmFxEval.encodeTrack(listOf(Triple(0f, 0f, null), Triple(1f, 1f, null)))
        val off = listOf(
            ClipFx(type = ClipFxLibrary.AM_MIRROR, enabled = false, values = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f) + tail)
        )
        assertSame(off, AmFxEval.resolve(off, clip(), 1500L))
        assertTrue(ClipFxLibrary.create(ClipFxLibrary.GLOW_SCAN)!!.values[6] == 0x0088FF.toFloat())
    }
}
