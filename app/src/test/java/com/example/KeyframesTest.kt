package com.example

import com.example.model.exportSpeed
import com.example.model.sourceOffsetMs
import com.example.model.AnimProp
import com.example.model.Easing
import com.example.model.Keyframe
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.at
import com.example.model.packColor
import com.example.model.unpackColor
import com.example.model.indexOnTime
import com.example.model.segmentIndexAt
import com.example.model.valueAt
import com.example.model.withEasing
import com.example.model.withKeyframe
import com.example.model.withShiftedKeyframes
import com.example.model.withoutKeyframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyframesTest {

    private fun clip() = TimelineClip(
        id = "a", name = "a", type = LayerType.SHAPE, startMs = 1000, durationMs = 4000, color = 0xFF000000
    )

    @Test
    fun linear_midpoint_holdBeforeAndAfter() {
        val l = listOf(Keyframe(0, 0f), Keyframe(1000, 100f))
        assertEquals(50f, l.valueAt(500).first, 0.2f)
        assertEquals(0f, l.valueAt(-300).first, 0f)
        assertEquals(100f, l.valueAt(5000).first, 0f)
    }

    @Test
    fun easeIn_isSlowerThanLinearInTheFirstHalf() {
        val l = listOf(Keyframe(0, 0f, easing = Easing(0.42f, 0f, 1f, 1f)), Keyframe(1000, 100f))
        assertTrue(l.valueAt(500).first < 40f)
        assertEquals(100f, l.valueAt(1000).first, 0.01f)
    }

    @Test
    fun easing_endpointsAreExact_andMonotonicForNormalCurves() {
        for ((_, e) in Easing.PRESETS) {
            assertEquals(0f, e.apply(0f), 0f)
            assertEquals(1f, e.apply(1f), 0f)
        }
        val e = Easing(0.42f, 0f, 0.58f, 1f)
        var prev = -1f
        for (i in 0..20) {
            val v = e.apply(i / 20f)
            assertTrue(v >= prev - 1e-4f)
            prev = v
        }
    }

    @Test
    fun segmentAndKeyframeLookup() {
        val l = listOf(Keyframe(0, 0f), Keyframe(1000, 1f), Keyframe(2000, 2f))
        assertEquals(0, l.segmentIndexAt(0))
        assertEquals(0, l.segmentIndexAt(999))
        assertEquals(1, l.segmentIndexAt(1500))
        assertEquals(-1, l.segmentIndexAt(2000))
        assertEquals(-1, l.segmentIndexAt(-5))
        assertEquals(1, l.indexOnTime(1020))
        assertEquals(-1, l.indexOnTime(1500))
    }

    @Test
    fun at_usesTimeRelativeToLayerStart() {
        val c = clip()
            .withKeyframe(AnimProp.SCALE, 0, 100f, 0f)
            .withKeyframe(AnimProp.SCALE, 2000, 200f, 0f)
            .withKeyframe(AnimProp.POSITION, 0, 0f, 0f)
            .withKeyframe(AnimProp.POSITION, 2000, 100f, -50f)
        val r = c.at(1000 + 1000) // layer starts at 1000, so this is 1000 ms into it
        assertEquals(150f, r.scalePercent, 0.5f)
        assertEquals(50f, r.transformX, 0.5f)
        assertEquals(-25f, r.transformY, 0.5f)
        // No keyframes: same object back
        val plain = clip()
        assertTrue(plain.at(2000) === plain)
    }

    @Test
    fun withKeyframe_replacesNearOne_insertsSorted() {
        var c = clip().withKeyframe(AnimProp.ROTATION, 1000, 10f, 0f)
        c = c.withKeyframe(AnimProp.ROTATION, 1010, 20f, 0f) // within tolerance: same keyframe
        assertEquals(1, c.keyframes[AnimProp.ROTATION]!!.size)
        assertEquals(20f, c.keyframes[AnimProp.ROTATION]!![0].a, 0f)
        c = c.withKeyframe(AnimProp.ROTATION, 0, 5f, 0f)
        assertEquals(listOf(0L, 1000L), c.keyframes[AnimProp.ROTATION]!!.map { it.timeMs })
    }

    @Test
    fun removingLastKeyframe_keepsItsValue() {
        val c = clip().withKeyframe(AnimProp.ROTATION, 500, 33f, 0f).withoutKeyframe(AnimProp.ROTATION, 0)
        assertTrue(c.keyframes.isEmpty())
        assertEquals(33f, c.rotationDeg, 0f)
    }

    @Test
    fun easing_isStoredOnTheSegmentStart() {
        val e = Easing(0.1f, 0.2f, 0.3f, 0.4f)
        val c = clip()
            .withKeyframe(AnimProp.SCALE, 0, 100f, 0f)
            .withKeyframe(AnimProp.SCALE, 1000, 200f, 0f)
            .withEasing(AnimProp.SCALE, 0, e)
        assertEquals(e, c.keyframes[AnimProp.SCALE]!![0].easing)
        assertTrue(c.keyframes[AnimProp.SCALE]!![1].easing.isLinear)
    }

    @Test
    fun shift_movesAllKeyframes() {
        val c = clip().withKeyframe(AnimProp.SCALE, 1000, 100f, 0f).withShiftedKeyframes(-400)
        assertEquals(600L, c.keyframes[AnimProp.SCALE]!![0].timeMs)
    }

    @Test
    fun color_packRoundTrip() {
        val c = 0x80FF4411L
        val (a, b) = packColor(c)
        assertEquals(c, unpackColor(a, b))
    }

    @Test
    fun color_keyframesBlendPerChannel() {
        val (a0, b0) = packColor(0xFF000000L)
        val (a1, b1) = packColor(0xFFFFFFFFL)
        val c = clip()
            .withKeyframe(AnimProp.FILL_COLOR, 0, a0, b0)
            .withKeyframe(AnimProp.FILL_COLOR, 1000, a1, b1)
        // clip starts at 1000 ms, so project time 1500 = halfway
        val mid = c.at(1500).textColor
        assertEquals(0xFF, ((mid shr 24) and 0xFF).toInt())
        assertTrue(kotlin.math.abs(((mid shr 16) and 0xFF).toInt() - 127) <= 1)
        assertEquals(0xFFFFFFFFL, c.at(5000).textColor)
    }

    @Test
    fun crop_sizeAndPositionAreAnimated() {
        val c = clip()
            .withKeyframe(AnimProp.CROP_SIZE, 0, 100f, 100f)
            .withKeyframe(AnimProp.CROP_SIZE, 1000, 50f, 40f)
            .withKeyframe(AnimProp.CROP_POS, 0, 50f, 50f)
            .withKeyframe(AnimProp.CROP_POS, 1000, 0f, 100f)
        val mid = c.at(1500) // clip starts at 1000 ms -> halfway
        assertEquals(75f, mid.cropWidthPercent, 0.2f)
        assertEquals(70f, mid.cropHeightPercent, 0.2f)
        assertEquals(25f, mid.cropHorizontalPercent, 0.2f)
        assertEquals(75f, mid.cropVerticalPercent, 0.2f)
    }

    @Test
    fun opacity_isAnimatedWithEasing() {
        val c = clip()
            .withKeyframe(AnimProp.OPACITY, 0, 0f, 0f)
            .withKeyframe(AnimProp.OPACITY, 1000, 100f, 0f)
        assertEquals(50f, c.at(1500).opacity, 0.5f)
        assertEquals(100f, c.at(9000).opacity, 0.001f)
    }

    @Test
    fun sticker_frameIndexLoopsAtFps() {
        val item = com.example.engine.StickerLibrary.Item("x", "x", animated = true, frameCount = 12, fps = 12f)
        assertEquals(0, com.example.engine.StickerLibrary.frameIndexAt(item, 0))
        assertEquals(6, com.example.engine.StickerLibrary.frameIndexAt(item, 500))
        assertEquals(0, com.example.engine.StickerLibrary.frameIndexAt(item, 1000))
        val still = item.copy(animated = false, frameCount = 1)
        assertEquals(0, com.example.engine.StickerLibrary.frameIndexAt(still, 3000))
    }

    @Test
    fun position_keyframeFollowsResolvedValue() {
        val c = clip()
            .withKeyframe(AnimProp.POSITION, 0, 0f, 0f)
            .withKeyframe(AnimProp.POSITION, 1000, 100f, 0f)
        // at 1500 ms (halfway) the layer sits at x = 50
        assertEquals(50f, c.at(1500).transformX, 0.5f)
    }

    @Test
    fun speed_sourceOffsetFollowsCurve() {
        // no keyframes: plain product
        val plain = clip().copy(speed = 2f)
        assertEquals(1000L, plain.sourceOffsetMs(500))
        // speed keyframes 1x at 0 -> 3x at 1000, then held at 3x (layer is 4000 ms long)
        val c = clip()
            .withKeyframe(AnimProp.SPEED, 0, 1f, 0f)
            .withKeyframe(AnimProp.SPEED, 1000, 3f, 0f)
        // integral 0..500 of (1 + 0.002 t) = 750 ms; 0..1000 = 2000 ms
        assertTrue(kotlin.math.abs(c.sourceOffsetMs(500) - 750L) <= 15L)
        assertTrue(kotlin.math.abs(c.sourceOffsetMs(1000) - 2000L) <= 15L)
        // whole layer: (2000 + 3 * 3000) / 4000 = 2.75x average
        assertEquals(2.75f, c.exportSpeed, 0.02f)
    }
}
