package com.example

import com.example.engine.CaptionProgress
import com.example.engine.CaptionProgress.Stage
import com.example.engine.SpeechClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionProgressTest {

    @Test
    fun barNeverGoesBackAndLabelMatchesValue() {
        val seen = ArrayList<Pair<String, Float>>()
        val p = CaptionProgress { l, v -> seen += l to v }
        p.report(Stage.READ, 1f, "Reading")
        p.report(Stage.WRITE, 0.5f, "Writing A")
        p.report(Stage.WRITE, 0.3f, "Writing OLD")   // late, smaller: ignored with its label
        p.report(Stage.WRITE, 0.8f, "Writing B")
        p.report(Stage.BUILD, 1f, "Preparing")
        assertEquals(listOf("Reading", "Writing A", "Writing B", "Preparing"), seen.map { it.first })
        assertTrue(seen.zipWithNext().all { (a, b) -> b.second >= a.second })
        assertEquals(Stage.BUILD.to, seen.last().second, 0.0001f)
    }

    @Test
    fun stagesCoverZeroToOneWithoutGaps() {
        val st = Stage.values().toList()
        assertEquals(0f, st.first().from, 0f)
        assertEquals(1f, st.last().to, 0f)
        st.zipWithNext().forEach { (a, b) -> assertEquals(a.to, b.from, 0f) }
    }

    @Test
    fun clockUsesRealPositionAndCreepsWithinTheWindow() {
        var now = 0L
        val c = SpeechClock(120_000L) { now }
        assertEquals(0L, c.estimateMs())
        now = 10_000L
        val creep = c.estimateMs()
        assertTrue(creep in 1L..27_000L)            // moved a little, capped at 90 % of 30 s
        c.reached(30_000L)                          // whisper really finished 30 s (took 10 s)
        assertEquals(30_000L, c.realMs())
        now = 20_000L                               // 10 s later: speed is now ~3 audio-s per s
        val e = c.estimateMs()
        assertTrue(e > 30_000L && e <= 57_000L)
    }

    @Test
    fun estimateNeverDecreasesAndNeverPassesTheEnd() {
        var now = 0L
        val c = SpeechClock(40_000L) { now }
        var prev = 0L
        for (step in 0 until 200) {
            now += 500
            if (step == 50) c.reached(25_000L)
            if (step == 120) c.reached(40_000L)
            val e = c.estimateMs()
            assertTrue("step $step: $e < $prev", e >= prev)
            assertTrue(e <= 40_000L)
            prev = e
        }
        assertEquals(40_000L, prev)
    }
}
