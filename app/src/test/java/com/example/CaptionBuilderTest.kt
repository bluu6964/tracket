package com.example

import com.example.engine.CaptionBuilder
import com.example.engine.CaptionBuilder.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionBuilderTest {

    private fun build(vararg p: Piece, max: Int = 30, dur: Long = 600_000L) =
        CaptionBuilder.build(p.toList(), max, dur) { it }

    @Test
    fun shortTextStaysOneLine() {
        assertEquals(listOf("Hello world"), CaptionBuilder.splitText("Hello world", 30))
    }

    @Test
    fun linesAreBalancedNotGreedy() {
        // greedy would give "one two three four five six" (27) + "seven" ; balanced gives two even lines
        val lines = CaptionBuilder.splitText("one two three four five six seven", 30)
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.length <= 30 })
        assertTrue(lines.minOf { it.length } >= 12)
    }

    @Test
    fun prefersBreakAfterPunctuation() {
        val lines = CaptionBuilder.splitText("I went home, and then I slept for hours", 30)
        assertEquals(listOf("I went home,", "and then I slept for hours"), lines)
    }

    @Test
    fun noLonelyShortWord() {
        val lines = CaptionBuilder.splitText("this sentence is almost thirty-one a", 30)
        assertTrue(lines.none { it == "a" })
    }

    @Test
    fun longSingleWordStaysWhole() {
        val lines = CaptionBuilder.splitText("a supercalifragilisticexpialidocious b", 10)
        assertTrue(lines.contains("supercalifragilisticexpialidocious"))
    }

    @Test
    fun bengaliTextIsCutByCharactersNotBytes() {
        val text = "আমি আজকে একটা নতুন ভিডিও বানাচ্ছি যেটা সবার ভালো লাগবে"
        val lines = CaptionBuilder.splitText(text, 30)
        assertEquals(text, lines.joinToString(" "))
        assertTrue(lines.all { it.codePointCount(0, it.length) <= 30 })
    }

    @Test
    fun timeIsSharedByLength() {
        val c = build(Piece(1000, 5000, "aaaa bbbb cccc dddd eeee ffff gggg hhhh iiii jjjj"), max = 25)
        assertEquals(2, c.size)
        assertEquals(1000, c[0].relStartMs)
        // second caption starts where the first one's share of the time ends
        assertTrue(c[1].relStartMs in 2900..3100)
    }

    @Test
    fun noOverlapAndNothingLost() {
        val c = build(
            Piece(0, 3000, "First sentence here."),
            Piece(3050, 3200, "Second."),            // very short, next starts very soon
            Piece(3250, 6000, "Third sentence is longer.")
        )
        for (i in 0 until c.size - 1) assertTrue(c[i].relEndMs <= c[i + 1].relStartMs)
        assertTrue(c.all { it.relEndMs > it.relStartMs })
        assertTrue(c[0].text.startsWith("First sentence here."))
        assertTrue(c.any { it.text.contains("Second.") })
        assertTrue(c.any { it.text.contains("Third") })
    }

    @Test
    fun tinyNeighboursAreJoined() {
        val c = build(Piece(0, 400, "Yes."), Piece(500, 1500, "I know."))
        assertEquals(1, c.size)
        assertEquals("Yes. I know.", c[0].text)
    }

    @Test
    fun threeRepeatsOfOneSentenceKeepTwo() {
        val c = build(
            Piece(0, 2000, "Thank you."), Piece(2500, 4500, "Thank you."),
            Piece(5000, 7000, "Thank you."), Piece(7500, 9500, "Thank you!")
        )
        assertEquals(2, c.size)
    }

    @Test
    fun captionsPastTheEndOfTheLayerAreDropped_andTheLastIsClipped() {
        val c = build(Piece(0, 3000, "Inside the layer"), Piece(9000, 12000, "Outside"), dur = 5000)
        assertEquals(1, c.size)
        assertTrue(c[0].relEndMs <= 5000)
    }

    @Test
    fun timesFollowTheConversionToTheTimeline() {
        // layer played at 2x speed: source ms / 2
        val c = CaptionBuilder.build(listOf(Piece(2000, 6000, "Fast speech")), 30, 100_000L) { it / 2 }
        assertEquals(1000, c[0].relStartMs)
        assertTrue(c[0].relEndMs in 3000..3300)
    }

    @Test
    fun clockFormat() {
        assertEquals("0:05", CaptionBuilder.clock(5_400))
        assertEquals("2:10", CaptionBuilder.clock(130_000))
        assertEquals("1:01:01", CaptionBuilder.clock(3_661_000))
    }
}
