package com.example

import com.example.model.BezierHandles
import com.example.model.EasingType
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.TransformKeyframe
import com.example.model.applyEasing
import com.example.model.cubicBezierEase
import com.example.model.interpolateKeyframes
import com.example.model.moveKeyframe
import com.example.model.setKeyframeCurve
import com.example.model.shiftKeyframes
import com.example.model.upsertKeyframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyframeCurveTest {

    private fun clip(start: Long, dur: Long, kfs: Map<String, List<TransformKeyframe>> = emptyMap()) = TimelineClip(
        id = "c", name = "c", type = LayerType.SHAPE, startMs = start, durationMs = dur,
        color = 0xFF000000, transformKeyframes = kfs
    )

    @Test
    fun presets_startAtZero_endAtOne_andNeverGoBackwards() {
        val presets = EasingType.values().filter { it != EasingType.HOLD && it != EasingType.CUSTOM }
        for (p in presets) {
            assertEquals(0f, applyEasing(0f, p), 1e-4f)
            assertEquals(1f, applyEasing(1f, p), 1e-4f)
            var prev = 0f
            for (i in 0..40) {
                val v = applyEasing(i / 40f, p)
                assertTrue("$p must be monotonic", v >= prev - 1e-3f)
                prev = v
            }
        }
    }

    @Test
    fun easeIn_isSlowAtStart_easeOut_isFastAtStart() {
        assertTrue(applyEasing(0.25f, EasingType.EASE_IN) < 0.25f)
        assertTrue(applyEasing(0.25f, EasingType.EASE_OUT) > 0.25f)
    }

    @Test
    fun customHandles_canOvershoot() {
        val h = BezierHandles(0.3f, -0.5f, 0.7f, 1.5f)
        assertTrue(cubicBezierEase(0.15f, h) < 0f)
        assertTrue(cubicBezierEase(0.85f, h) > 1f)
        assertEquals(0.5f, cubicBezierEase(0.5f, h), 1e-3f)
    }

    @Test
    fun hold_keepsFirstValueUntilNextKeyframe() {
        val kfs = listOf(
            TransformKeyframe(0, 10f, EasingType.HOLD),
            TransformKeyframe(1000, 50f)
        )
        assertEquals(10f, interpolateKeyframes(kfs, 999), 1e-3f)
        assertEquals(50f, interpolateKeyframes(kfs, 1000), 1e-3f)
    }

    @Test
    fun linear_midpoint() {
        val kfs = listOf(TransformKeyframe(0, 0f), TransformKeyframe(1000, 100f))
        assertEquals(50f, interpolateKeyframes(kfs, 500), 1e-3f)
    }

    @Test
    fun customCurve_isUsedByInterpolation() {
        val base = mapOf("transformX" to listOf(TransformKeyframe(0, 0f), TransformKeyframe(1000, 100f)))
        val custom = setKeyframeCurve(base, "transformX", 0, BezierHandles(0.9f, 0f, 1f, 0.2f))
        val v = interpolateKeyframes(custom["transformX"]!!, 500)
        assertTrue("custom curve should differ from linear, was $v", v < 40f)
    }

    @Test
    fun upsert_existingKeyframe_keepsItsCurve() {
        var m = upsertKeyframe(emptyMap(), "scalePercent", 0, 100f)
        m = upsertKeyframe(m, "scalePercent", 1000, 200f)
        m = setKeyframeCurve(m, "scalePercent", 0, BezierHandles(0.2f, 0.8f, 0.8f, 0.2f))
        // slider tweak at the same time: only the value changes
        m = upsertKeyframe(m, "scalePercent", 0, 120f)
        val first = m["scalePercent"]!!.first()
        assertEquals(120f, first.value, 1e-4f)
        assertEquals(EasingType.CUSTOM, first.easing)
    }

    @Test
    fun move_keepsCurve_andCannotCrossNeighbours() {
        var m = upsertKeyframe(emptyMap(), "rotationDeg", 0, 0f)
        m = upsertKeyframe(m, "rotationDeg", 1000, 90f, EasingType.EASE_IN)
        m = upsertKeyframe(m, "rotationDeg", 2000, 180f)
        m = moveKeyframe(m, "rotationDeg", 1000, 5000, 45f)
        val mid = m["rotationDeg"]!![1]
        assertTrue(mid.timeMs < 2000)
        assertEquals(45f, mid.value, 1e-4f)
        assertEquals(EasingType.EASE_IN, mid.easing)
        assertEquals(3, m["rotationDeg"]!!.size)
    }

    @Test
    fun keyframes_followTheLayer_whenItMoves() {
        val kfs = mapOf("transformX" to listOf(TransformKeyframe(0, 0f), TransformKeyframe(1000, 100f)))
        val atZero = clip(0, 4000, kfs)
        val moved = clip(5000, 4000, kfs)
        assertEquals(50f, atZero.evaluateTransform("transformX", 500, 0f), 1e-3f)
        assertEquals(50f, moved.evaluateTransform("transformX", 5500, 0f), 1e-3f)
    }

    @Test
    fun trimLeft_shiftKeepsAnimationOnTheSameTimelineSpot() {
        val kfs = mapOf("transformX" to listOf(TransformKeyframe(0, 0f), TransformKeyframe(2000, 100f)))
        val before = clip(1000, 4000, kfs)
        // trim 500 ms off the left edge: start moves +500, keyframes shift -500
        val after = clip(1500, 3500, shiftKeyframes(kfs, -500))
        assertEquals(
            before.evaluateTransform("transformX", 2000, 0f),
            after.evaluateTransform("transformX", 2000, 0f),
            1e-3f
        )
    }

    @Test
    fun keyframesAbs_addsLayerStart() {
        val c = clip(3000, 2000, mapOf("pivotX" to listOf(TransformKeyframe(250, 0.2f))))
        assertEquals(3250L, c.keyframesAbs("pivotX").first().timeMs)
        assertTrue(c.hasKeyframeNear(3250L))
        assertTrue(!c.hasKeyframeNear(250L))
    }
}
