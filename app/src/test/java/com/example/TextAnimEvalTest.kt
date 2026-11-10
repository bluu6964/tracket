package com.example

import com.example.engine.AlightXmlImporter
import com.example.engine.TextAnimEval
import com.example.model.AnimKey
import com.example.model.AnimValue
import com.example.model.TextAnimator
import com.example.model.packColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextAnimEvalTest {

    private val riseUp = TextAnimator(
        AlightXmlImporter.TEXTTRANSFORM,
        mapOf(
            "end" to AnimValue("float", keys = listOf(AnimKey(0f, listOf(0f)), AnimKey(0.45f, listOf(1f)), AnimKey(1f, listOf(1f)))),
            "offset" to AnimValue("vec2", value = listOf(0f, 70f)),
            "component" to AnimValue("int", value = listOf(2f)),
            "useFillColor" to AnimValue("bool", value = listOf(0f)),
            "fillColor" to AnimValue("color", value = packColor(0xFF00FF00L).let { listOf(it.first, it.second) })
        ),
        refSize = 30f
    )

    @Test
    fun endKeyframesAreInterpolated() {
        assertEquals(0f, TextAnimEval.evaluate(riseUp, 0f, 120f).end, 1e-6f)
        assertEquals(0.5f, TextAnimEval.evaluate(riseUp, 0.225f, 120f).end, 1e-5f)
        assertEquals(1f, TextAnimEval.evaluate(riseUp, 0.45f, 120f).end, 1e-6f)
        assertEquals(1f, TextAnimEval.evaluate(riseUp, 1f, 120f).end, 1e-6f)
    }

    @Test
    fun offsetIsRelativeToTheFontHeight() {
        // 70 of 30 scene px = 2.333 font heights; the engine font is 120 px high here
        val r = TextAnimEval.evaluate(riseUp, 0f, 120f)
        assertEquals(0f, r.dx, 1e-4f)
        assertEquals(70f / 30f * 120f, r.dy, 1e-3f)
        assertEquals(2, r.component)
    }

    @Test
    fun defaultsWhenAPropertyIsMissing() {
        val r = TextAnimEval.evaluate(TextAnimator(AlightXmlImporter.TEXTTRANSFORM), 0.5f, 100f)
        assertEquals(0f, r.start, 0f)
        assertEquals(1f, r.end, 0f)
        assertEquals(0, r.component)
        assertEquals(0f, r.dx, 0f)
        assertEquals(1f, r.alpha, 0f)
        assertFalse(r.recolor)
    }

    @Test
    fun colourIsUnpackedAndSteps() {
        val r = TextAnimEval.evaluate(riseUp, 0.5f, 100f)
        assertEquals(0xFF00FF00.toInt(), r.argb)
        assertFalse(r.recolor)
        val on = riseUp.copy(props = riseUp.props + ("useFillColor" to AnimValue("bool", value = listOf(1f))))
        assertTrue(TextAnimEval.evaluate(on, 0.5f, 100f).recolor)
    }

    @Test
    fun boolKeyframesStepInsteadOfBlending() {
        val v = AnimValue("bool", keys = listOf(AnimKey(0f, listOf(0f)), AnimKey(1f, listOf(1f))))
        assertEquals(0f, TextAnimEval.valueAt(v, 0.9f)[0], 0f)
        assertEquals(1f, TextAnimEval.valueAt(v, 1f)[0], 0f)
    }

    @Test
    fun clipWithoutTextTransformGivesNothing() {
        val clip = com.example.model.TimelineClip(
            id = "a", name = "a", type = com.example.model.LayerType.TEXT, startMs = 0L, durationMs = 1000L, color = 0L,
            textAnimators = listOf(TextAnimator(AlightXmlImporter.RANDOMDISPLACE))
        )
        assertNull(TextAnimEval.evaluate(clip, 500L, 100f))
        val withTt = clip.copy(textAnimators = listOf(riseUp))
        val r = TextAnimEval.evaluate(withTt, 225L, 100f)
        assertNotNull(r)
        assertEquals(0.5f, r!!.end, 1e-5f)
    }

    @Test
    fun timeIsRelativeToTheClipStart() {
        val clip = com.example.model.TimelineClip(
            id = "a", name = "a", type = com.example.model.LayerType.TEXT, startMs = 1000L, durationMs = 2000L, color = 0L,
            textAnimators = listOf(riseUp)
        )
        assertEquals(0f, TextAnimEval.evaluate(clip, 500L, 100f)!!.end, 1e-6f)   // before the clip: clamped to the start
        assertEquals(1f, TextAnimEval.evaluate(clip, 1000L + 900L, 100f)!!.end, 1e-6f)
    }
}
