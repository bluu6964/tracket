package com.example

import com.example.engine.AlightXmlImporter
import com.example.engine.TextProgressEval
import com.example.model.AnimKey
import com.example.model.AnimValue
import com.example.model.LayerType
import com.example.model.TextAnimator
import com.example.model.TimelineClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextProgressEvalTest {

    // The effect of the ST Intro template (1c30cfdb…xml, text "Channel Name"), as the importer stores it.
    private val introReveal = TextAnimator(
        AlightXmlImporter.TEXTPROGRESS,
        mapOf(
            "start" to AnimValue("float", value = listOf(0f)),
            "end" to AnimValue("float", keys = listOf(AnimKey(0.007613f, listOf(0f)), AnimKey(0.619794f, listOf(1f)))),
            "cursor" to AnimValue("int", value = listOf(0f)),
            "blink" to AnimValue("bool", value = listOf(0f))
        ),
        refSize = 18f
    )

    @Test
    fun endKeyframesAreInterpolated() {
        assertEquals(0f, TextProgressEval.evaluate(introReveal, 0f).end, 1e-6f)
        assertEquals(0f, TextProgressEval.evaluate(introReveal, 0.007613f).end, 1e-6f)
        assertEquals(0.5f, TextProgressEval.evaluate(introReveal, (0.007613f + 0.619794f) / 2f).end, 1e-5f)
        assertEquals(1f, TextProgressEval.evaluate(introReveal, 0.619794f).end, 1e-6f)
        assertEquals(1f, TextProgressEval.evaluate(introReveal, 1f).end, 1e-6f)
        assertEquals(0f, TextProgressEval.evaluate(introReveal, 0.3f).start, 0f)
        assertEquals(0, TextProgressEval.evaluate(introReveal, 0.3f).cursor)
        assertFalse(TextProgressEval.evaluate(introReveal, 0.3f).blink)
    }

    @Test
    fun defaultsWhenAPropertyIsMissing() {
        val r = TextProgressEval.evaluate(TextAnimator(AlightXmlImporter.TEXTPROGRESS), 0.5f)
        assertEquals(0f, r.start, 0f)
        assertEquals(1f, r.end, 0f)
        assertEquals(0, r.cursor)
        assertFalse(r.blink)
    }

    @Test
    fun cursorAndBlinkAreRead() {
        val a = TextAnimator(
            AlightXmlImporter.TEXTPROGRESS,
            mapOf(
                "cursor" to AnimValue("int", value = listOf(2f)),
                "blink" to AnimValue("bool", value = listOf(1f))
            )
        )
        val r = TextProgressEval.evaluate(a, 0.5f)
        assertEquals(2, r.cursor)
        assertTrue(r.blink)
    }

    @Test
    fun clipTimeIsRelativeToTheClipStart() {
        val clip = TimelineClip(
            id = "a", name = "a", type = LayerType.TEXT, startMs = 666L, durationMs = 2233L, color = 0L,
            textAnimators = listOf(introReveal)
        )
        assertEquals(0f, TextProgressEval.evaluate(clip, 0L)!!.end, 1e-6f)          // before the clip: clamped
        assertEquals(1f, TextProgressEval.evaluate(clip, 666L + 2233L)!!.end, 1e-6f) // at its end: fully typed
        assertNull(TextProgressEval.evaluate(clip.copy(textAnimators = emptyList()), 1000L))
        assertNull(
            TextProgressEval.evaluate(
                clip.copy(textAnimators = listOf(TextAnimator(AlightXmlImporter.TEXTTRANSFORM))), 1000L
            )
        )
    }

    @Test
    fun visibleRangeIsFloorLike() {
        // 5 clusters: window [0, 0.6] shows exactly 3 (a cluster shows only when fully inside)
        assertEquals(0..2, TextProgressEval.visibleRange(5, 0f, 0.6f))
        assertEquals(0..4, TextProgressEval.visibleRange(5, 0f, 1f))
        assertEquals(2..4, TextProgressEval.visibleRange(5, 0.4f, 1f))
        assertTrue(TextProgressEval.visibleRange(5, 0f, 0f).isEmpty())
        assertTrue(TextProgressEval.visibleRange(5, 0.5f, 0.5f).isEmpty())
        assertTrue(TextProgressEval.visibleRange(0, 0f, 1f).isEmpty())
        // the window's ends are order-free, like the texttransform arrived rule
        assertEquals(0..4, TextProgressEval.visibleRange(5, 1f, 0f))
        assertFalse(TextProgressEval.isVisible(3, 5, 0f, 0.6f))
        assertTrue(TextProgressEval.isVisible(2, 5, 0f, 0.6f))
        assertFalse(TextProgressEval.isVisible(5, 5, 0f, 1f))
    }

    @Test
    fun visibleTextCutsAtClusterBoundaries() {
        assertEquals("HEL", TextProgressEval.visibleText("HELLO", 0f, 0.6f))
        assertEquals("LLO", TextProgressEval.visibleText("HELLO", 0.4f, 1f))
        assertEquals("", TextProgressEval.visibleText("HELLO", 0f, 0f))
        assertEquals("HELLO", TextProgressEval.visibleText("HELLO", 0f, 1f))
        assertEquals("", TextProgressEval.visibleText("", 0f, 1f))
    }

    @Test
    fun visibleTextUsesTheRealFileNumbers() {
        // "Channel Name" has 12 clusters (the space counts); at the layer's end keyframe value mid-flight,
        // end = 0.619794 shows the 7 clusters whose slice ends by then: "Channel".
        assertEquals(12, TextProgressEval.clustersOf("Channel Name").size)
        assertEquals("Channel", TextProgressEval.visibleText("Channel Name", 0f, 0.619794f))
    }

    @Test
    fun newlineIsAClusterOfItsOwn() {
        assertEquals(5, TextProgressEval.clustersOf("AB\nCD").size)
        assertEquals("AB\n", TextProgressEval.visibleText("AB\nCD", 0f, 0.6f))
        assertEquals("CD", TextProgressEval.visibleText("AB\nCD", 0.6f, 1f))
    }

    @Test
    fun bengaliClustersAreNotSplit() {
        // আমি = আ + ম + ি (vowel sign): 2 grapheme clusters, the sign stays with its consonant.
        assertEquals(listOf("আ", "মি"), TextProgressEval.clustersOf("আমি"))
        assertEquals("আ", TextProgressEval.visibleText("আমি", 0f, 0.5f))
        assertEquals("মি", TextProgressEval.visibleText("আমি", 0.5f, 1f))
        assertEquals("আমি", TextProgressEval.visibleText("আমি", 0f, 1f))
        // ক্তি = ক + ্ (virama) + ত + ি: the linker joins both sides (GB9c), the sign joins too — one cluster.
        assertEquals(listOf("ক্তি"), TextProgressEval.clustersOf("ক্তি"))
    }
}
