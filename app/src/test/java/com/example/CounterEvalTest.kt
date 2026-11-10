package com.example

import com.example.engine.AlightXmlImporter
import com.example.engine.CounterEval
import com.example.model.AnimKey
import com.example.model.AnimValue
import com.example.model.LayerType
import com.example.model.TextAnimator
import com.example.model.TimelineClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CounterEvalTest {

    // The counter of the animated-graph file (7e363d75…xml), as the importer stores it: on the text
    // "0", offset keyframed 0 -> 12 over the first ~29 % of the 7032 ms layer, scale constant 0.14.
    // (The file's offset keys carry a cubicBezier easing, which AnimKey does not store — animator
    // evaluation is linear, so the counts below are the linear pacing of the same 0 -> 12 run.)
    private val graphCounter = TextAnimator(
        AlightXmlImporter.COUNTER,
        mapOf(
            "offset" to AnimValue(
                "float",
                keys = listOf(AnimKey(0.002276f, listOf(0f)), AnimKey(0.286689f, listOf(12f)))
            ),
            "scale" to AnimValue("float", value = listOf(0.14f))
        ),
        refSize = 48f
    )

    private val graphClip = TimelineClip(
        id = "g", name = "g", type = LayerType.TEXT, startMs = 0L, durationMs = 7032L, color = 0L,
        text = "0", textAnimators = listOf(graphCounter)
    )

    @Test
    fun keyframedOffsetIsInterpolatedAndHeld() {
        assertEquals(0f, CounterEval.evaluate(graphCounter, 0f).offset, 1e-6f)
        assertEquals(0f, CounterEval.evaluate(graphCounter, 0.002276f).offset, 1e-6f)
        assertEquals(12f, CounterEval.evaluate(graphCounter, 0.286689f).offset, 1e-5f)
        assertEquals(12f, CounterEval.evaluate(graphCounter, 1f).offset, 1e-6f)   // held after the last key
        assertEquals(0.14f, CounterEval.evaluate(graphCounter, 0.5f).scale, 1e-6f)
    }

    @Test
    fun defaultsWhenAPropertyIsMissing() {
        val r = CounterEval.evaluate(TextAnimator(AlightXmlImporter.COUNTER), 0.5f)
        assertEquals(0f, r.offset, 0f)   // Alight "Add" default
        assertEquals(1f, r.scale, 0f)    // Alight "Multiply" default
    }

    @Test
    fun graphFileCountsZeroToTwelve() {
        // The original number is 0, so the display IS the offset track: 0 at the start, 12 once the
        // offset keys end (~2016 ms into the layer), 12 held to the layer's end.
        assertEquals("0", CounterEval.displayText("0", graphClip, 0L))
        assertEquals("6", CounterEval.displayText("0", graphClip, 1008L))   // linear half-way: offset ≈ 5.95
        assertEquals("12", CounterEval.displayText("0", graphClip, 2016L))
        assertEquals("12", CounterEval.displayText("0", graphClip, 7032L))
    }

    @Test
    fun clipTimeIsRelativeToTheClipStart() {
        val shifted = graphClip.copy(startMs = 500L)
        assertEquals("0", CounterEval.displayText("0", shifted, 500L))
        assertEquals("12", CounterEval.displayText("0", shifted, 500L + 2016L))
        assertNull(CounterEval.evaluate(graphClip.copy(textAnimators = emptyList()), 1000L))
        assertNull(
            CounterEval.evaluate(
                graphClip.copy(textAnimators = listOf(TextAnimator(AlightXmlImporter.TEXTPROGRESS))), 1000L
            )
        )
        assertNull(CounterEval.displayText("0", graphClip.copy(textAnimators = emptyList()), 1000L))
    }

    @Test
    fun formulaMultipliesThenAdds() {
        // Alight's Count Up/Down: Scale multiplies the original number, Offset is added to the product.
        assertEquals("25", CounterEval.substitute("10", offset = 5.0, scale = 2.0))
        assertEquals("50", CounterEval.substitute("100", offset = 0.0, scale = 0.5))
        assertEquals("0", CounterEval.substitute("300", offset = 0.0, scale = 0.0))   // countdown end state
        assertEquals("7", CounterEval.substitute("7", offset = 0.0, scale = 1.0))
    }

    @Test
    fun everyNumberInTheTextCounts() {
        assertEquals("From 20 to 40", CounterEval.substitute("From 10 to 20", offset = 0.0, scale = 2.0))
        assertEquals("Hours", CounterEval.substitute("Hours", offset = 5.0, scale = 2.0))   // no number: untouched
        assertEquals("", CounterEval.substitute("", offset = 5.0, scale = 2.0))
    }

    @Test
    fun decimalsFollowTheOriginalToken() {
        assertEquals("1.0", CounterEval.substitute("0.5", offset = 0.0, scale = 2.0))
        assertEquals("4.14", CounterEval.substitute("3.14", offset = 1.0, scale = 1.0))
        assertEquals("1.8", CounterEval.substitute("1.5", offset = 0.25, scale = 1.0))   // 1.75 at 1 dp -> 1.8
        assertEquals("0.10", CounterEval.substitute("0.05", offset = 0.0, scale = 2.0))
        assertEquals("0.50", CounterEval.substitute(".25", offset = 0.0, scale = 2.0))   // leading '.' token, 2 dp kept
    }

    @Test
    fun integersStayIntegersAndRoundHalfAwayFromZero() {
        assertEquals("3", CounterEval.substitute("5", offset = 0.0, scale = 0.5))   // 2.5 -> 3
        assertEquals("2", CounterEval.substitute("5", offset = 0.0, scale = 0.4))   // exactly 2
        assertEquals("5", CounterEval.substitute("7", offset = -2.0, scale = 1.0))
        assertEquals("0", CounterEval.substitute("1", offset = 0.0, scale = 0.4))   // 0.4 -> 0
    }

    @Test
    fun groupingIsKeptWhenTheOriginalWasGrouped() {
        // The Alight guide's own count-up example types the target as "100,000".
        assertEquals("100,000", CounterEval.substitute("100,000", offset = 0.0, scale = 1.0))
        assertEquals("50,000", CounterEval.substitute("100,000", offset = 0.0, scale = 0.5))
        assertEquals("1,234,567", CounterEval.substitute("1,234,567", offset = 0.0, scale = 1.0))
        assertEquals("1000", CounterEval.substitute("1000", offset = 0.0, scale = 1.0))   // ungrouped stays ungrouped
    }

    @Test
    fun signsAndDashes() {
        assertEquals("-10", CounterEval.substitute("-5", offset = 0.0, scale = 2.0))   // attached minus is part of the number
        assertEquals("-2", CounterEval.substitute("5", offset = -7.0, scale = 1.0))    // result crosses zero
        assertEquals("10-6", CounterEval.substitute("5-3", offset = 0.0, scale = 2.0))  // dash after a digit is text
        assertEquals("0", CounterEval.substitute("-1", offset = 0.0, scale = 0.4))      // -0.4 -> 0, never "-0"
    }
}
