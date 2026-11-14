package com.example

import com.example.model.AnimProp
import com.example.model.BeatDetect
import com.example.model.Keyframe
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.sourceOffsetMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * Auto beat detection (model/BeatDetect.kt, build 193): the spectral-flux
 * onset detector over SYNTHETIC PCM generated right here — click tracks
 * (30 ms decaying noise bursts, the drum-hit shape spectral flux answers to)
 * at known tempos, silence, a steady tone, a tempo change — plus the beat
 * merge rule and the source-offset -> timeline inversion that lands detected
 * beats on the ruler under trim + constant/ramped speed.
 *
 * Measured on these generators the detector's timing error is under 10 ms;
 * the pinned tolerance is 50 ms so the test guards the law, not the noise.
 */
class BeatDetectTest {

    /** Deterministic pseudo-noise (LCG): the same track on every run, no Random seed drift. */
    private class Lcg(seed: Long) {
        private var s = seed
        fun next(): Float {
            s = s * 6364136223846793005L + 1442695040888963407L
            return ((s ushr 33) % 2000 - 1000) / 1000f
        }
    }

    /** Silence with a 30 ms decaying broadband burst at every beat time. */
    private fun clickTrack(rate: Int, seconds: Double, beatTimesMs: List<Long>, seed: Long = 42): FloatArray {
        val out = FloatArray((rate * seconds).toInt())
        val rng = Lcg(seed)
        val burst = (rate * 0.030).toInt()
        for (t in beatTimesMs) {
            val start = (t * rate / 1000).toInt()
            for (i in 0 until burst) {
                val idx = start + i
                if (idx >= out.size) break
                out[idx] += 0.9f * (1f - i.toFloat() / burst) * rng.next()
            }
        }
        return out
    }

    /** Every true beat has a detected beat within [tol] ms, and there are no others. */
    private fun assertGrid(trueBeats: List<Long>, found: List<Long>, tol: Long = 50L) {
        assertEquals("beat count (found=$found)", trueBeats.size, found.size)
        for (t in trueBeats) {
            val near = found.minByOrNull { abs(it - t) }!!
            assertTrue("true beat $t: nearest found $near is off by ${near - t} ms", abs(near - t) <= tol)
        }
    }

    @Test
    fun detects120BpmGrid() {
        val beats = (1..15).map { it * 500L }
        val r = BeatDetect.detect(clickTrack(16000, 8.5, beats), 16000)
        assertGrid(beats, r.beatsMs)
    }

    @Test
    fun detects60BpmGrid() {
        val beats = (1..7).map { it * 1000L }
        val r = BeatDetect.detect(clickTrack(16000, 8.5, beats), 16000)
        assertGrid(beats, r.beatsMs)
    }

    @Test
    fun detects128BpmGridAndTempo() {
        val beats = (1..16).map { (it * 468.75).toLong() }
        val r = BeatDetect.detect(clickTrack(16000, 8.5, beats), 16000)
        assertGrid(beats, r.beatsMs)
        val bpm = r.bpm!!
        assertTrue("bpm $bpm should read as 128", bpm in 125.0..131.0)
    }

    @Test
    fun detectsAtNativeMusicRate() {
        val beats = (1..15).map { it * 500L }
        val r = BeatDetect.detect(clickTrack(44100, 8.5, beats), 44100)
        assertGrid(beats, r.beatsMs)
        val bpm = r.bpm!!
        assertTrue("bpm $bpm should read as 120", bpm in 117.0..123.0)
    }

    @Test
    fun bpmEstimateIsTheMedianInterval() {
        val bpm = BeatDetect.detect(clickTrack(16000, 8.5, (1..15).map { it * 500L }), 16000).bpm!!
        assertTrue("bpm $bpm should read as 120", bpm in 117.0..123.0)
        // A doubled interval (a missed beat) does not drag the median.
        assertEquals(120.0, BeatDetect.estimateBpm(listOf(0, 500, 1500, 2000, 2500))!!, 0.01)
        assertNull(BeatDetect.estimateBpm(listOf(1000)))
        assertNull(BeatDetect.estimateBpm(emptyList()))
    }

    @Test
    fun silenceGivesNoBeats() {
        val r = BeatDetect.detect(FloatArray(16000 * 4), 16000)
        assertTrue(r.beatsMs.isEmpty())
        assertNull(r.bpm)
    }

    @Test
    fun steadyToneGivesAtMostItsAttack() {
        // A constant spectrum has zero spectral flux after the first frame:
        // the only mark a steady tone may earn is its attack at ~0.
        val tone = FloatArray(16000 * 4) { (0.5 * sin(2.0 * Math.PI * 440.0 * it / 16000.0)).toFloat() }
        val r = BeatDetect.detect(tone, 16000)
        assertTrue("tone beats ${r.beatsMs}", r.beatsMs.size <= 1)
        if (r.beatsMs.isNotEmpty()) assertTrue(r.beatsMs.first() < 100L)
        assertNull(r.bpm)
    }

    @Test
    fun tempoChangeIsFollowed() {
        // 100 BPM (600 ms) for five beats, then 150 BPM (400 ms) to the end.
        val beats = listOf(
            600L, 1200L, 1800L, 2400L, 3000L,
            3400L, 3800L, 4200L, 4600L, 5000L, 5400L, 5800L, 6200L, 6600L, 7000L, 7400L, 7800L
        )
        val r = BeatDetect.detect(clickTrack(16000, 8.5, beats), 16000)
        assertGrid(beats, r.beatsMs)
    }

    @Test
    fun clicksOverAToneBedAreFound() {
        // A quiet steady chord under the clicks: the flux of the bed is ~0,
        // so every click still lands (plus, honestly, the bed's own attack at ~0).
        val bed = FloatArray(16000 * 8) { (0.15 * sin(2.0 * Math.PI * 220.0 * it / 16000.0)).toFloat() }
        val beats = (1..15).map { it * 500L }
        val clicks = clickTrack(16000, 8.5, beats, seed = 7)
        for (i in bed.indices) bed[i] += clicks[i]
        val r = BeatDetect.detect(bed, 16000)
        for (t in beats) {
            val near = r.beatsMs.minByOrNull { abs(it - t) }!!
            assertTrue("beat $t missed (nearest $near)", abs(near - t) <= 50L)
        }
    }

    @Test
    fun degenerateInputIsEmptyNotACrash() {
        assertTrue(BeatDetect.detect(FloatArray(0), 16000).beatsMs.isEmpty())
        assertTrue(BeatDetect.detect(FloatArray(100), 16000).beatsMs.isEmpty())
        assertTrue(BeatDetect.detect(FloatArray(16000), 0).beatsMs.isEmpty())
        assertTrue(BeatDetect.detect(FloatArray(16000) { 0.001f }, -44100).beatsMs.isEmpty())
    }

    // ---- The merge rule --------------------------------------------------

    @Test
    fun mergeNeverMovesAHandPlacedMark() {
        val existing = listOf(1000L, 3000L)
        // 1050 and 3050 duplicate the hand marks (within 100 ms): dropped.
        // 2000 / 4000 are new. Nothing existing is removed or shifted.
        val merged = BeatDetect.merge(existing, listOf(1050L, 2000L, 3050L, 4000L), 100L)
        assertEquals(listOf(1000L, 2000L, 3000L, 4000L), merged)
        assertEquals(2, merged.size - existing.size)
    }

    @Test
    fun mergeDedupesDetectedAgainstThemselves() {
        val merged = BeatDetect.merge(emptyList(), listOf(2000L, 2050L, 3000L), 100L)
        assertEquals(listOf(2000L, 3000L), merged)
        assertEquals(emptyList<Long>(), BeatDetect.merge(listOf(5L), emptyList(), 100L).minus(5L))
        assertEquals(listOf(500L), BeatDetect.merge(emptyList(), listOf(500L), 100L))
    }

    // ---- Source offset -> timeline inversion ------------------------------

    private fun audioClip(speed: Float = 1f, durationMs: Long = 8000L, keys: List<Keyframe> = emptyList()) =
        TimelineClip(
            id = "a", name = "a", type = LayerType.AUDIO,
            startMs = 2000L, durationMs = durationMs, color = 0xFF000000,
            speed = speed,
            keyframes = if (keys.isEmpty()) emptyMap() else mapOf(AnimProp.SPEED to keys)
        )

    @Test
    fun inversionAtConstantSpeed() {
        val fast = audioClip(speed = 2f)
        assertEquals(0L, BeatDetect.timelineMsForSourceOffset(fast, 0L))
        assertEquals(500L, BeatDetect.timelineMsForSourceOffset(fast, 1000L))
        assertEquals(4000L, BeatDetect.timelineMsForSourceOffset(fast, 8000L))
        // Past the played span: clamps to the clip's end.
        assertEquals(8000L, BeatDetect.timelineMsForSourceOffset(fast, 999999L))
        val slow = audioClip(speed = 0.5f)
        assertEquals(2000L, BeatDetect.timelineMsForSourceOffset(slow, 1000L))
        val plain = audioClip(speed = 1f)
        assertEquals(3456L, BeatDetect.timelineMsForSourceOffset(plain, 3456L))
    }

    @Test
    fun inversionUnderASpeedRampMatchesTheModelLaw() {
        val clip = audioClip(
            keys = listOf(Keyframe(0L, 1f), Keyframe(8000L, 3f))
        )
        // Reference: binary search over the model's own sourceOffsetMs.
        fun bruteForce(target: Long): Long {
            var lo = 0L
            var hi = clip.durationMs
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (clip.sourceOffsetMs(mid) < target) lo = mid + 1 else hi = mid
            }
            return lo
        }
        val span = clip.sourceOffsetMs(clip.durationMs)
        assertTrue("ramp span $span", span > 8000L)
        for (target in listOf(1L, 1000L, 4000L, 8000L, 12000L, span - 1)) {
            val got = BeatDetect.timelineMsForSourceOffset(clip, target)
            val want = bruteForce(target)
            assertTrue("offset $target: got $got, model law says $want", abs(got - want) <= 25L)
        }
        assertEquals(0L, BeatDetect.timelineMsForSourceOffset(clip, 0L))
        assertEquals(clip.durationMs, BeatDetect.timelineMsForSourceOffset(clip, span))
    }

    @Test
    fun inversionKeepsInputOrder() {
        val clip = audioClip(speed = 2f)
        // Unsorted input comes back in the caller's order.
        assertEquals(
            listOf(2000L, 500L, 1000L),
            BeatDetect.timelineMsForSourceOffsets(clip, listOf(4000L, 1000L, 2000L))
        )
        assertEquals(emptyList<Long>(), BeatDetect.timelineMsForSourceOffsets(clip, emptyList()))
    }
}
