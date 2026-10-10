package com.example

import com.example.model.KeyframeEasing
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.TransformValues
import com.example.model.keyframeIndexAt
import com.example.model.splitKeyframes
import com.example.model.transformAt
import com.example.model.withKeyframeAt
import com.example.model.withNextEasingAt
import com.example.model.withoutKeyframeAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyframesTest {

    private fun clip() = TimelineClip(
        id = "a", name = "a", type = LayerType.SHAPE, startMs = 1000, durationMs = 4000, color = 0xFF000000
    )

    private fun v(x: Float, scale: Float = 100f) = TransformValues(x = x, scalePercent = scale)

    @Test
    fun noKeyframes_usesFixedTransform() {
        val c = clip().copy(transformX = 12f, scalePercent = 150f)
        val tf = c.transformAt(3000)
        assertEquals(12f, tf.x, 0.001f)
        assertEquals(150f, tf.scalePercent, 0.001f)
    }

    @Test
    fun linearInterpolation_betweenTwoKeyframes() {
        val c = clip().withKeyframeAt(1000, v(0f, 100f)).withKeyframeAt(3000, v(100f, 200f))
        assertEquals(2, c.keyframes.size)
        assertEquals(0f, c.transformAt(1000).x, 0.001f)
        assertEquals(50f, c.transformAt(2000).x, 0.001f)
        assertEquals(150f, c.transformAt(2000).scalePercent, 0.001f)
        assertEquals(100f, c.transformAt(3000).x, 0.001f)
    }

    @Test
    fun holdsFirstAndLastValueOutsideKeyframes() {
        val c = clip().withKeyframeAt(2000, v(10f)).withKeyframeAt(3000, v(50f))
        assertEquals(10f, c.transformAt(1000).x, 0.001f)
        assertEquals(50f, c.transformAt(5000).x, 0.001f)
    }

    @Test
    fun addingAtSameTime_updatesInsteadOfDuplicating() {
        val c = clip().withKeyframeAt(2000, v(10f)).withKeyframeAt(2005, v(30f))
        assertEquals(1, c.keyframes.size)
        assertEquals(30f, c.keyframes[0].values.x, 0.001f)
        assertEquals(0, c.keyframeIndexAt(2000))
    }

    @Test
    fun keyframesStaySortedAndRelativeToLayerStart() {
        val c = clip().withKeyframeAt(4000, v(1f)).withKeyframeAt(2000, v(2f))
        assertEquals(listOf(1000L, 3000L), c.keyframes.map { it.timeMs })
    }

    @Test
    fun removingLastKeyframe_keepsItsValueAsFixedTransform() {
        val c = clip().withKeyframeAt(2000, v(42f)).withoutKeyframeAt(2000)
        assertTrue(c.keyframes.isEmpty())
        assertEquals(42f, c.transformX, 0.001f)
        assertEquals(42f, c.transformAt(4000).x, 0.001f)
    }

    @Test
    fun hold_keepsValueUntilNextKeyframe() {
        var c = clip().withKeyframeAt(1000, v(0f)).withKeyframeAt(3000, v(100f))
        repeat(4) { c = c.withNextEasingAt(1000) } // LINEAR -> ... -> HOLD
        assertEquals(KeyframeEasing.HOLD, c.keyframes[0].easing)
        assertEquals(0f, c.transformAt(2900).x, 0.001f)
        assertEquals(100f, c.transformAt(3000).x, 0.001f)
    }

    @Test
    fun easeInOut_isSlowAtTheStartAndSymmetric() {
        val e = KeyframeEasing.EASE_IN_OUT
        assertEquals(0f, e.apply(0f), 0.001f)
        assertEquals(1f, e.apply(1f), 0.001f)
        assertEquals(0.5f, e.apply(0.5f), 0.01f)
        assertTrue(e.apply(0.2f) < 0.2f)
    }

    @Test
    fun split_bothHalvesGetAKeyframeWithTheExactCutValue() {
        val c = clip().withKeyframeAt(1000, v(0f)).withKeyframeAt(5000, v(100f))
        val (left, right) = c.splitKeyframes(3000)
        assertEquals(50f, left.last().values.x, 0.001f)
        assertEquals(2000L, left.last().timeMs)
        assertEquals(0L, right.first().timeMs)
        assertEquals(50f, right.first().values.x, 0.001f)
        assertEquals(2000L, right.last().timeMs)
        assertEquals(100f, right.last().values.x, 0.001f)
    }
}
