package com.example

import com.example.engine.TextRevealValues
import com.example.model.AnimKey
import com.example.model.AnimValue
import com.example.model.TextAnimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRevealValuesTest {

    private fun constantAnimator(start: Float, end: Float, extraProps: Map<String, AnimValue> = emptyMap()) =
        TextAnimator(
            effectId = "com.alightcreative.effects.textprogress",
            props = mapOf(
                "start" to AnimValue(type = "float", value = listOf(start)),
                "end" to AnimValue(type = "float", value = listOf(end))
            ) + extraProps,
            refSize = 42f
        )

    private fun keyframedAnimator() = TextAnimator(
        effectId = "com.alightcreative.effects.textprogress",
        props = mapOf(
            "start" to AnimValue(type = "float", value = listOf(0f)),
            "end" to AnimValue(
                type = "float",
                keys = listOf(AnimKey(0f, listOf(0f)), AnimKey(1f, listOf(1f)))
            )
        ),
        refSize = 24f
    )

    @Test
    fun absentAnimatorIsTheFullWindow() {
        val (s, e) = TextRevealValues.windowAt(null, 0.5f)
        assertEquals(0f, s, 0f)
        assertEquals(1f, e, 0f)
        assertFalse(TextRevealValues.isKeyframed(null))
    }

    @Test
    fun constantWindowIsReadBack() {
        val (s, e) = TextRevealValues.windowAt(constantAnimator(0.25f, 0.75f), 0.5f)
        assertEquals(0.25f, s, 0f)
        assertEquals(0.75f, e, 0f)
        assertFalse(TextRevealValues.isKeyframed(constantAnimator(0.25f, 0.75f)))
    }

    @Test
    fun keyframedRevealIsDetectedAndEvaluated() {
        val a = keyframedAnimator()
        assertTrue(TextRevealValues.isKeyframed(a))
        val (s0, e0) = TextRevealValues.windowAt(a, 0f)
        assertEquals(0f, s0, 0f)
        assertEquals(0f, e0, 0f)
        val (s1, e1) = TextRevealValues.windowAt(a, 1f)
        assertEquals(0f, s1, 0f)
        assertEquals(1f, e1, 0f)
    }

    @Test
    fun windowIsClampedToUnitFractions() {
        val (s, e) = TextRevealValues.coerceWindow(-0.2f, 1.5f)
        assertEquals(0f, s, 0f)
        assertEquals(1f, e, 0f)
    }

    @Test
    fun windowStartPastEndPullsEndAlong() {
        // A start dragged past the end must not invert the window: the end follows the start.
        val (s, e) = TextRevealValues.coerceWindow(0.8f, 0.3f)
        assertEquals(0.8f, s, 0f)
        assertEquals(0.8f, e, 0f)
    }

    @Test
    fun emptyWindowStaysEmpty() {
        val (s, e) = TextRevealValues.coerceWindow(0.5f, 0.5f)
        assertEquals(0.5f, s, 0f)
        assertEquals(0.5f, e, 0f)
    }

    @Test
    fun staticWindowIsAppendedWhenAbsent() {
        val out = TextRevealValues.withStaticWindow(emptyList(), 0.2f, 0.6f)
        assertEquals(1, out.size)
        assertEquals("com.alightcreative.effects.textprogress", out[0].effectId)
        assertEquals(0.2f, out[0].props["start"]!!.value[0], 0f)
        assertEquals(0.6f, out[0].props["end"]!!.value[0], 0f)
        assertTrue(out[0].props["start"]!!.keys.isEmpty())
        assertTrue(out[0].props["end"]!!.keys.isEmpty())
    }

    @Test
    fun staticWindowReplacesKeyframesButKeepsOtherProps() {
        val keyframed = keyframedAnimator().copy(
            props = keyframedAnimator().props + ("cursor" to AnimValue(type = "int", value = listOf(0f)))
        )
        val other = TextAnimator(effectId = "com.alightcreative.effects.texttransform", refSize = 10f)
        val out = TextRevealValues.withStaticWindow(listOf(other, keyframed), 0f, 1f)
        assertEquals(2, out.size)
        assertEquals(other, out[0])
        assertEquals(0f, out[1].props["start"]!!.value[0], 0f)
        assertEquals(1f, out[1].props["end"]!!.value[0], 0f)
        assertTrue(out[1].props["start"]!!.keys.isEmpty())
        assertTrue(out[1].props["end"]!!.keys.isEmpty())
        // The cursor prop and the refSize survive the replacement.
        assertEquals(0f, out[1].props["cursor"]!!.value[0], 0f)
        assertEquals(24f, out[1].refSize, 0f)
        assertFalse(TextRevealValues.isKeyframed(out[1]))
    }

    @Test
    fun staticWindowKeepsRefSizeAndConstantValues() {
        val a = constantAnimator(0.1f, 0.9f)
        val out = TextRevealValues.withStaticWindow(listOf(a), 0.3f, 0.7f)
        assertEquals(1, out.size)
        assertEquals(42f, out[0].refSize, 0f)
        assertEquals(0.3f, out[0].props["start"]!!.value[0], 0f)
        assertEquals(0.7f, out[0].props["end"]!!.value[0], 0f)
    }
}
