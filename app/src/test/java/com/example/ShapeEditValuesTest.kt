package com.example

import com.example.model.ShapeEditValues
import org.junit.Assert.assertEquals
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
}
