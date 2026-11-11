package com.example

import com.example.engine.RepeatEval
import com.example.model.ClipFx
import com.example.model.ClipFxLibrary
import com.example.model.LayerType
import com.example.model.TimelineClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatEvalTest {

    private fun shape(
        x: Float = 10f, y: Float = 20f, rotation: Float = 0f, scale: Float = 100f, opacity: Float = 100f,
        fx: ClipFx? = null
    ) = TimelineClip(
        id = "s1", name = "Line", type = LayerType.SHAPE, startMs = 0L, durationMs = 2000L, color = 0xFF000000L,
        transformX = x, transformY = y, rotationDeg = rotation, scalePercent = scale, opacity = opacity,
        effects = if (fx == null) emptyList() else listOf(fx)
    )

    /** values = [count, offsetX, offsetY, angle, scale %, alpha %]. */
    private fun repeat(vararg values: Float) = ClipFx(type = ClipFxLibrary.REPEAT, values = values.toList())

    @Test
    fun noRepeatReturnsTheLayerAlone() {
        val c = shape()
        assertEquals(listOf(c), RepeatEval.copiesOf(c))
        // A disabled Repeat, or a count of 1, is also just the layer (same clip instance, so the
        // ClipFx's random id cannot make two equal-looking clips differ).
        val disabled = shape(fx = repeat(5f, 0f, 93f, 0f, 100f, 100f).copy(enabled = false))
        assertEquals(listOf(disabled), RepeatEval.copiesOf(disabled))
        val single = shape(fx = repeat(1f, 0f, 93f, 0f, 100f, 100f))
        assertEquals(listOf(single), RepeatEval.copiesOf(single))
    }

    @Test
    fun countAndOffsetAccumulate() {
        // The corpus case: 5 copies of a horizontal line, 93 preview px apart, vertically.
        val c = shape(fx = repeat(5f, 0f, 93f, 0f, 100f, 100f))
        val copies = RepeatEval.copiesOf(c)
        assertEquals(5, copies.size)
        assertTrue("the first copy is the layer itself", c === copies[0])
        for (i in 0 until 5) {
            assertEquals(10f, copies[i].transformX, 0f)
            assertEquals(20f + i * 93f, copies[i].transformY, 0.01f)
            assertEquals(0f, copies[i].rotationDeg, 0f)
            assertEquals(100f, copies[i].scalePercent, 0f)
            assertEquals(100f, copies[i].opacity, 0f)
        }
    }

    @Test
    fun angleScaleAlphaCompoundPerCopy() {
        // Each copy turns 30 more, halves in size and halves in opacity, 10 px to the right.
        val copies = RepeatEval.copiesOf(
            shape(rotation = 10f, scale = 200f, opacity = 80f, fx = repeat(3f, 10f, 0f, 30f, 50f, 50f))
        )
        assertEquals(3, copies.size)
        assertEquals(20f, copies[1].transformX, 0.01f)
        assertEquals(40f, copies[1].rotationDeg, 0.01f)
        assertEquals(100f, copies[1].scalePercent, 0.01f)
        assertEquals(40f, copies[1].opacity, 0.01f)
        assertEquals(30f, copies[2].transformX, 0.01f)
        assertEquals(70f, copies[2].rotationDeg, 0.01f)
        assertEquals(50f, copies[2].scalePercent, 0.01f)
        assertEquals(20f, copies[2].opacity, 0.01f)
    }

    @Test
    fun countIsClamped() {
        assertEquals(1, RepeatEval.copiesOf(shape(fx = repeat(0f, 0f, 10f, 0f, 100f, 100f))).size)
        assertEquals(RepeatEval.MAX_COUNT, RepeatEval.copiesOf(shape(fx = repeat(500f, 1f, 0f, 0f, 100f, 100f))).size)
        assertEquals(50, RepeatEval.countOf(repeat(500f)))
        assertEquals(1, RepeatEval.countOf(repeat(0f)))
    }

    @Test
    fun uprightOffsetIsUndoneByTheTurn() {
        // The layer is drawn into its own upright picture (rotation already 0 there) and the picture
        // is turned 90 degrees clockwise when placed: a copy that must land 93 px below the layer is
        // drawn 93 px to the RIGHT inside the picture, and the picture's turn puts it below.
        val upright = shape(x = 0f, y = 0f, fx = repeat(2f, 0f, 93f, 0f, 100f, 100f))
        val copies = RepeatEval.copiesOf(upright, undoRotationDeg = 90f)
        assertEquals(93f, copies[1].transformX, 0.01f)
        assertEquals(0f, copies[1].transformY, 0.01f)
        // No turn to undo: the copy is drawn where it lands.
        val plain = RepeatEval.copiesOf(upright)
        assertEquals(0f, plain[1].transformX, 0.01f)
        assertEquals(93f, plain[1].transformY, 0.01f)
        // A 180-degree turn flips both axes.
        val flipped = RepeatEval.copiesOf(upright, undoRotationDeg = 180f)
        assertEquals(0f, flipped[1].transformX, 0.01f)
        assertEquals(-93f, flipped[1].transformY, 0.01f)
    }

    @Test
    fun uprightCopiesKeepTheirOwnTurnAndScale() {
        // Inside the upright picture the copy's own turn is just its angle (the picture adds the rest).
        val upright = shape(x = 0f, y = 0f, scale = 200f, fx = repeat(2f, 10f, 0f, 30f, 50f, 100f))
        val copies = RepeatEval.copiesOf(upright, undoRotationDeg = 90f)
        assertEquals(30f, copies[1].rotationDeg, 0.01f)
        assertEquals(100f, copies[1].scalePercent, 0.01f)
        assertEquals(0f, copies[1].transformX, 0.01f)
        assertEquals(-10f, copies[1].transformY, 0.01f)
    }

    @Test
    fun repeatOfFindsTheEnabledOne() {
        val off = repeat(9f, 0f, 0f, 0f, 100f, 100f).copy(enabled = false)
        val on = repeat(4f, 5f, 0f, 0f, 100f, 100f)
        val c = shape().copy(effects = listOf(off, on))
        assertEquals(4, RepeatEval.countOf(RepeatEval.repeatOf(c)!!))
        assertTrue(RepeatEval.repeatOf(shape()) == null)
    }
}
