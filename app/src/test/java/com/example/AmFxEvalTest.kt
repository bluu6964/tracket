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

    @Test
    fun lumaKeyLowThresholdTailResolves() {
        // am-renderer-preset2's lumakey3: lowThreshold sweeps 1 -> 0 by t = 0.201057 (the end
        // key eased (0, 0, 0.58, 1)); after that the band stays open at the bottom.
        val tail = AmFxEval.encodeTrack(
            listOf(
                Triple(0f, 1f, null),
                Triple(0.201057f, 0f, Easing(0f, 0f, 0.58f, 1f))
            )
        )
        val fx = ClipFx(type = ClipFxLibrary.LUMA_KEY, values = listOf(100f, 100f, 5f, 0f, 1f, 0f, 0f) + tail)
        val clip = clip()
        assertEquals(100f, AmFxEval.resolve(listOf(fx), clip, 500L).single().values[0], 0.001f)
        val after = AmFxEval.resolve(listOf(fx), clip, 1000L).single()   // rel 0.25, past the last key
        assertEquals(0f, after.values[0], 0.001f)
        assertEquals(100f, after.values[1], 0.001f)   // the high threshold is not the tracked slot
    }

    @Test
    fun turbulentTwoTailsResolveInOrder() {
        // The corpus profile (am-renderer-preset2): intensity 0.153 -> 0 over the span, scale
        // 3.32 -> 0.01 over keys OUTSIDE the span. The tails sit back to back from index 14.
        val intTail = AmFxEval.encodeTrack(listOf(Triple(0f, 0.153f, null), Triple(1f, 0f, null)))
        val scaleTail = AmFxEval.encodeTrack(listOf(Triple(-0.036364f, 3.32f, null), Triple(1.872727f, 0.01f, null)))
        val base = MutableList(14) { 0f }
        base[0] = 15.3f
        base[1] = 332f
        val fx = ClipFx(type = ClipFxLibrary.TURBULENT_DISPLACE_AM, values = base + intTail + scaleTail)
        val mid = AmFxEval.resolve(listOf(fx), clip(), 1500L).single()   // rel 0.5
        assertEquals(7.65f, mid.values[0], 0.01f)      // intensity half-way down its linear track
        // scale: f = 0.536364 / 1.909091 = 0.28094 -> 3.32 - 3.31 * 0.28094 = 2.3901 (x100)
        assertEquals(239.01f, mid.values[1], 0.5f)
        assertEquals(2f, mid.values[14], 0.001f)        // the tails survive resolution
        assertEquals(2f, mid.values[27], 0.001f)
    }

    @Test
    fun turbulentPlaceholderKeepsTheSecondTailAligned() {
        // Intensity constant, scale keyframed: the importer writes a count-0 placeholder for
        // intensity, and the resolver must step over it to reach the scale track.
        val scaleTail = AmFxEval.encodeTrack(listOf(Triple(0f, 3.32f, null), Triple(1f, 0.01f, null)))
        val base = MutableList(14) { 0f }
        base[0] = 15.3f
        base[1] = 332f
        val fx = ClipFx(type = ClipFxLibrary.TURBULENT_DISPLACE_AM, values = base + listOf(0f) + scaleTail)
        val mid = AmFxEval.resolve(listOf(fx), clip(), 1500L).single()
        assertEquals(15.3f, mid.values[0], 0.001f)   // untouched
        assertEquals(166.5f, mid.values[1], 0.5f)    // (3.32 + 0.01) / 2 x 100
    }

    @Test
    fun pixelateSizeTailUsesScaleOne() {
        // likk-preset-33's pixelate2: size 24 -> 1 over the span; the slot holds AM units, so
        // the tail resolves at scale 1 (no x100).
        val tail = AmFxEval.encodeTrack(listOf(Triple(0.009798f, 24f, null), Triple(1f, 1f, null)))
        val base = MutableList(14) { 0f }
        base[0] = 24f
        val fx = ClipFx(type = ClipFxLibrary.PIXELATE_AM, values = base + tail)
        val mid = AmFxEval.resolve(listOf(fx), clip(), 1500L).single()   // rel 0.5
        assertEquals(12.61f, mid.values[0], 0.05f)
    }

    @Test
    fun swirlStrengthTailResolves() {
        // celz-preset-9x16's swirl3: strength 0 -> -0.111 -> 0, the middle key at t = 0.441379.
        val tail = AmFxEval.encodeTrack(
            listOf(
                Triple(0f, 0f, Easing(0.48f, 0f, 1f, 0f)),
                Triple(0.441379f, -0.111f, Easing(0.48f, 0f, 1f, 0f)),
                Triple(1f, 0f, Easing(0f, 1f, 0.52f, 1f))
            )
        )
        val fx = ClipFx(type = ClipFxLibrary.SWIRL_AM, values = listOf(0f, 51f, 0f, 0f, 3f, 0f, 0f) + tail)
        // 883 ms into the 2000 ms clip = rel 0.4415, just past the -0.111 key (0.441379): the
        // segment's easing (0, 1, 0.52, 1) rises steeply, so the value is already back at -0.1071.
        val mid = AmFxEval.resolve(listOf(fx), clip(), 500L + 883L).single()
        assertEquals(-10.71f, mid.values[0], 0.05f)
        assertEquals(51f, mid.values[1], 0.001f)   // radius untouched
    }
}
