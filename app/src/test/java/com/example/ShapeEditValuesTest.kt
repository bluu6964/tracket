package com.example

import com.example.model.AnimProp
import com.example.model.Easing
import com.example.model.Keyframe
import com.example.model.LayerType
import com.example.model.ShapeEditValues
import com.example.model.TimelineClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeEditValuesTest {

    @Test
    fun cornerRadiusIsClampedToTheShapeBox() {
        assertEquals(0f, ShapeEditValues.coerceCornerRadiusDp(-5f), 0f)
        assertEquals(0f, ShapeEditValues.coerceCornerRadiusDp(0f), 0f)
        assertEquals(20f, ShapeEditValues.coerceCornerRadiusDp(20f), 0f)
        assertEquals(65f, ShapeEditValues.coerceCornerRadiusDp(65f), 0f)
        assertEquals(65f, ShapeEditValues.coerceCornerRadiusDp(200f), 0f)
    }

    @Test
    fun trimIsClampedToUnitFractions() {
        val (s, e) = ShapeEditValues.coerceTrim(-0.2f, 1.5f)
        assertEquals(0f, s, 0f)
        assertEquals(1f, e, 0f)
    }

    @Test
    fun trimStartPastEndPullsEndAlong() {
        // A start dragged past the end must not invert the window: the end follows the start.
        val (s, e) = ShapeEditValues.coerceTrim(0.8f, 0.3f)
        assertEquals(0.8f, s, 0f)
        assertEquals(0.8f, e, 0f)
    }

    @Test
    fun trimKeepsAValidWindow() {
        val (s, e) = ShapeEditValues.coerceTrim(0.25f, 0.75f)
        assertEquals(0.25f, s, 0f)
        assertEquals(0.75f, e, 0f)
    }

    @Test
    fun emptyTrimWindowStaysEmpty() {
        val (s, e) = ShapeEditValues.coerceTrim(0.5f, 0.5f)
        assertEquals(0.5f, s, 0f)
        assertEquals(0.5f, e, 0f)
    }

    @Test
    fun cornerRadiusKeyframeAwareness() {
        // Build 173: the editor's slider must know when the radius is keyframed (same class
        // as the build-86 text-reveal decision) — and a "Use static radius" freeze keeps the
        // radius as it looks at the playhead, mid-segment included.
        val plain = TimelineClip(
            id = "a", name = "a", type = LayerType.SHAPE, startMs = 0, durationMs = 2000,
            color = 0xFF000000, shapeCornerRadius = 20f
        )
        assertFalse(ShapeEditValues.isCornerRadiusKeyframed(plain))
        assertEquals(20f, ShapeEditValues.staticCornerRadiusAt(plain, 1000), 0f)
        val keyed = plain.copy(
            keyframes = mapOf(
                AnimProp.SHAPE_CORNER_RADIUS to listOf(
                    Keyframe(0, 10f, 0f, Easing.LINEAR), Keyframe(2000, 30f, 0f, Easing.LINEAR)
                )
            )
        )
        assertTrue(ShapeEditValues.isCornerRadiusKeyframed(keyed))
        assertEquals(10f, ShapeEditValues.staticCornerRadiusAt(keyed, 0), 0f)
        assertEquals(20f, ShapeEditValues.staticCornerRadiusAt(keyed, 1000), 0f)
        assertEquals(30f, ShapeEditValues.staticCornerRadiusAt(keyed, 2000), 0f)
    }

    @Test
    fun trimKeyframeAwareness() {
        // Build 175: same class as the corner radius — while the trim window is keyframed
        // (Alight drawing.progress) the editor's sliders must know, and a "Use static trim"
        // freeze keeps the window as it looks at the playhead, mid-segment included.
        val plain = TimelineClip(
            id = "a", name = "a", type = LayerType.SHAPE, startMs = 0, durationMs = 2000,
            color = 0xFF000000, strokeTrimStart = 0.2f, strokeTrimEnd = 0.6f
        )
        assertFalse(ShapeEditValues.isTrimKeyframed(plain))
        assertEquals(0.2f, ShapeEditValues.staticTrimAt(plain, 1000).first, 0f)
        assertEquals(0.6f, ShapeEditValues.staticTrimAt(plain, 1000).second, 0f)
        val keyed = plain.copy(
            keyframes = mapOf(
                AnimProp.STROKE_TRIM to listOf(
                    Keyframe(0, 0.2f, 0.6f, Easing.LINEAR), Keyframe(2000, 0.4f, 0.9f, Easing.LINEAR)
                )
            )
        )
        assertTrue(ShapeEditValues.isTrimKeyframed(keyed))
        assertEquals(0.2f, ShapeEditValues.staticTrimAt(keyed, 0).first, 0f)
        assertEquals(0.6f, ShapeEditValues.staticTrimAt(keyed, 0).second, 0f)
        assertEquals(0.3f, ShapeEditValues.staticTrimAt(keyed, 1000).first, 0.001f)
        assertEquals(0.75f, ShapeEditValues.staticTrimAt(keyed, 1000).second, 0.001f)
        assertEquals(0.4f, ShapeEditValues.staticTrimAt(keyed, 2000).first, 0f)
        assertEquals(0.9f, ShapeEditValues.staticTrimAt(keyed, 2000).second, 0f)
    }
}
