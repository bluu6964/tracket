package com.example

import com.example.model.Ducking
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.fadeGainAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

/**
 * Audio ducking (model/Ducking.kt, build 194): the pure sidechain law over
 * SYNTHETIC envelopes — the field defaults (old projects load unducked), the
 * dB floor, the threshold / over-range target law, the peak envelope, the
 * attack / release follower (silence stays at 1, a constant loud trigger
 * settles at the floor, gaps recover, per-step change is bounded = no
 * clicks), the source -> timeline envelope mapping under trim + speed (the
 * build-193 inversion law), and the breakpoint curve + its interpolation
 * contract (the same contract audio_mixer.cpp implements).
 */
class DuckingTest {

    private fun audioClip(
        id: String = "a",
        startMs: Long = 0L,
        durationMs: Long = 8000L,
        speed: Float = 1f,
        trimStartMs: Long = 0L,
        fadeInMs: Long = 0L,
        fadeOutMs: Long = 0L
    ) = TimelineClip(
        id = id, name = id, type = LayerType.AUDIO,
        startMs = startMs, durationMs = durationMs, color = 0xFF000000,
        speed = speed, trimStartMs = trimStartMs, fadeInMs = fadeInMs, fadeOutMs = fadeOutMs
    )

    // ---- Stored fields --------------------------------------------------

    @Test
    fun fieldDefaultsAreUnducked() {
        val c = audioClip()
        assertTrue(!c.duckEnabled)
        assertNull(c.duckTriggerId)
        assertEquals(12f, c.duckAmountDb, 0f)
        assertEquals(0.5f, c.duckSmoothness, 0f)
    }

    // ---- The dB floor + the target law ----------------------------------

    @Test
    fun floorGainFollowsDb() {
        assertEquals(1f, Ducking.floorGain(0f), 0f)
        assertEquals(1f, Ducking.floorGain(-6f), 0f)
        assertEquals(0.1f, Ducking.floorGain(20f), 0.0001f)
        assertEquals(0.25119f, Ducking.floorGain(12f), 0.0001f)
        // Past the slider's end the amount clamps at MAX_AMOUNT_DB.
        assertEquals(Ducking.floorGain(Ducking.MAX_AMOUNT_DB), Ducking.floorGain(60f), 0f)
    }

    @Test
    fun targetGainLaw() {
        val floor = Ducking.floorGain(20f) // 0.1
        assertEquals(1f, Ducking.targetGain(0f, floor), 0f)
        assertEquals(1f, Ducking.targetGain(Ducking.THRESHOLD, floor), 0f)
        assertEquals(1f, Ducking.targetGain(0.01f, floor), 0f)
        assertEquals(floor, Ducking.targetGain(1f, floor), 0.0001f)
        assertEquals(floor, Ducking.targetGain(1.4f, floor), 0.0001f)
        // Midway over the threshold range -> midway down the drop.
        val midLevel = Ducking.THRESHOLD + (1f - Ducking.THRESHOLD) / 2f
        assertEquals(1f - 0.5f * (1f - floor), Ducking.targetGain(midLevel, floor), 0.0001f)
        // A zero amount never ducks, whatever the level.
        assertEquals(1f, Ducking.targetGain(1f, 1f), 0f)
    }

    // ---- Peak envelope ----------------------------------------------------

    @Test
    fun peakEnvelopeTakesPerStepPeak() {
        val rate = 16000 // 10 ms = 160 samples
        val samples = FloatArray(rate) // 1 s
        samples[5] = 0.3f
        samples[160 + 40] = -0.8f // negative peak counts by magnitude
        samples[999] = 0.05f
        val env = Ducking.peakEnvelope(samples, rate)
        assertEquals(100, env.size)
        assertEquals(0.3f, env[0], 0.0001f)
        assertEquals(0.8f, env[1], 0.0001f)
        assertEquals(0.05f, env[6], 0.0001f)
        assertEquals(0f, env[50], 0f)
        assertTrue(Ducking.peakEnvelope(FloatArray(0), rate).isEmpty())
    }

    // ---- The follower -----------------------------------------------------

    @Test
    fun silenceNeverDucks() {
        val gains = Ducking.gainsForLevels(FloatArray(500), amountDb = 20f, smoothness = 0.5f)
        assertTrue(gains.all { it == 1f })
        // Below-threshold levels are silence to the law.
        val quiet = Ducking.gainsForLevels(FloatArray(500) { 0.01f }, amountDb = 20f, smoothness = 0.5f)
        assertTrue(quiet.all { it == 1f })
        // And a zero amount never ducks even under a loud trigger.
        val noAmount = Ducking.gainsForLevels(FloatArray(500) { 1f }, amountDb = 0f, smoothness = 0.5f)
        assertTrue(noAmount.all { it == 1f })
    }

    @Test
    fun constantLoudTriggerSettlesAtFloor() {
        val floor = Ducking.floorGain(20f)
        val gains = Ducking.gainsForLevels(FloatArray(1000) { 1f }, amountDb = 20f, smoothness = 0.5f)
        // Monotone fall, and settled at the floor well inside 10 s.
        for (k in 1 until gains.size) assertTrue(gains[k] <= gains[k - 1] + 1e-6f)
        assertEquals(floor, gains.last(), 0.001f)
        // The first step is exactly one attack coefficient down from 1.
        val aAttack = 1.0 - exp(-Ducking.STEP_MS / Ducking.attackMs(0.5f))
        assertEquals(1.0 + (floor - 1.0) * aAttack, gains[0].toDouble(), 0.0005)
    }

    @Test
    fun gapsRecoverTowardOne() {
        // 1 s loud, then 4 s of gaps.
        val levels = FloatArray(500) { if (it < 100) 1f else 0f }
        val gains = Ducking.gainsForLevels(levels, amountDb = 20f, smoothness = 0.5f)
        assertTrue(gains[99] < 0.2f) // fully ducked by the end of the loud second
        for (k in 101 until gains.size) assertTrue(gains[k] >= gains[k - 1] - 1e-6f) // monotone recovery
        assertTrue(gains.last() > 0.99f) // and essentially back after 4 s of release
    }

    @Test
    fun perStepGainChangeIsBounded() {
        // Worst case: a full-scale square wave of levels (attack every other step).
        val levels = FloatArray(2000) { if (it % 2 == 0) 1f else 0f }
        val amountDb = 36f
        val smoothness = 0f // the snappiest setting: the largest legal steps
        val gains = Ducking.gainsForLevels(levels, amountDb, smoothness)
        val floor = Ducking.floorGain(amountDb)
        val maxCoeff = 1.0 - exp(-Ducking.STEP_MS / Ducking.attackMs(smoothness))
        val bound = (1.0 - floor) * maxCoeff
        for (k in 1 until gains.size) {
            val d = abs(gains[k] - gains[k - 1]).toDouble()
            assertTrue("step $k moved $d > bound $bound", d <= bound + 1e-4)
        }
        assertTrue(gains.all { it in floor - 0.001f..1.001f })
        // Smoother settings move strictly slower.
        val smooth = Ducking.gainsForLevels(FloatArray(50) { 1f }, amountDb, smoothness = 1f)
        val snappy = Ducking.gainsForLevels(FloatArray(50) { 1f }, amountDb, smoothness = 0f)
        assertTrue(smooth[10] > snappy[10])
    }

    // ---- Envelope onto the timeline --------------------------------------

    @Test
    fun timelineLevelsAtConstantSpeed() {
        val rate = 16000
        val samples = FloatArray(rate * 8) // 8 s of decoded source
        // A loud burst at source 2.0..2.5 s.
        for (i in (rate * 2) until (rate * 5 / 2)) samples[i] = 0.9f
        val trigger = audioClip(durationMs = 8000L)
        val levels = Ducking.timelineLevels(trigger, samples, rate) { 1f }
        assertEquals(800, levels.size)
        assertEquals(0f, levels[100], 0f)          // timeline 1.0 s: quiet
        assertTrue(levels[220] > 0.85f)            // timeline 2.2 s: the burst
        assertEquals(0f, levels[400], 0f)          // timeline 4.0 s: quiet again
    }

    @Test
    fun timelineLevelsFollowSpeedAndTrim() {
        val rate = 16000
        val samples = FloatArray(rate * 8)
        for (i in (rate * 2) until (rate * 5 / 2)) samples[i] = 0.9f // burst at source 2.0..2.5 s
        // Speed 2: the source burst at 2.0 s is heard at timeline 1.0 s.
        val fast = audioClip(durationMs = 4000L, speed = 2f)
        val fastLevels = Ducking.timelineLevels(fast, samples, rate) { 1f }
        assertTrue(fastLevels[110] > 0.85f)  // timeline 1.1 s
        assertEquals(0f, fastLevels[220], 0f) // timeline 2.2 s is past it
        // Trim does not move the envelope: the decoded window STARTS at the
        // trim, so a trimmed copy of the same layer envelopes identically.
        val trimmed = audioClip(durationMs = 4000L, speed = 2f, trimStartMs = 5000L)
        val trimmedLevels = Ducking.timelineLevels(trimmed, samples, rate) { 1f }
        assertTrue(trimmedLevels.contentEquals(fastLevels))
    }

    @Test
    fun timelineLevelsScaleByHeardGain() {
        val rate = 16000
        val samples = FloatArray(rate * 2) { 0.8f } // constant loud source
        val trigger = audioClip(durationMs = 2000L, fadeInMs = 1000L)
        // What the mix hears: volume 1 x the layer's own fade (fadeGainAt).
        val levels = Ducking.timelineLevels(trigger, samples, rate) { rel -> trigger.fadeGainAt(rel) }
        assertEquals(0f, levels[0], 0.0001f)          // faded fully out at its start
        assertTrue(levels[50] in 0.35f..0.45f)        // halfway through the fade-in
        assertTrue(levels[150] > 0.75f)               // fade done: the full level
    }

    // ---- The curve + its interpolation contract ---------------------------

    private fun loudLevels(steps: Int, fromStep: Int, toStep: Int, level: Float = 1f): FloatArray =
        FloatArray(steps) { if (it in fromStep until toStep) level else 0f }

    @Test
    fun curveDipsUnderTheTriggerAndRecovers() {
        val trigger = audioClip(id = "voice", startMs = 2000L, durationMs = 4000L)
        val levels = loudLevels(400, 100, 300) // loud at trigger-relative 1..3 s
        val music = audioClip(id = "music", startMs = 0L, durationMs = 8000L)
        val curve = Ducking.curve(trigger.startMs, levels, music, amountDb = 20f, smoothness = 0.5f)
        assertTrue(curve != null)
        curve!!
        assertEquals(0L, curve.first().relMs)
        assertEquals(8000L, curve.last().relMs)
        assertEquals(1f, curve.first().gain, 0.001f) // music starts before the voice: unducked
        // Timeline 3.5 s = well inside the voice's loud stretch: fully ducked.
        assertTrue(Ducking.gainAt(curve, 3500L) < 0.2f)
        // Timeline 7.5 s = long after the voice ended: recovered.
        assertTrue(Ducking.gainAt(curve, 7500L) > 0.95f)
        // Gains never leave [floor, 1].
        assertTrue(curve.all { it.gain in 0.09f..1.001f })
    }

    @Test
    fun curveIsNullWhenNothingDucks() {
        val music = audioClip(id = "music", startMs = 0L, durationMs = 8000L)
        // Silent trigger.
        assertNull(Ducking.curve(0L, FloatArray(400), music, amountDb = 20f, smoothness = 0.5f))
        // Loud trigger that never overlaps the music.
        assertNull(
            Ducking.curve(20000L, loudLevels(400, 0, 400), music, amountDb = 20f, smoothness = 0.5f)
        )
        // Zero amount.
        val trigger = audioClip(id = "voice", startMs = 0L, durationMs = 4000L)
        assertNull(
            Ducking.curve(trigger.startMs, loudLevels(400, 0, 400), music, amountDb = 0f, smoothness = 0.5f)
        )
    }

    @Test
    fun gainAtContract() {
        assertEquals(1f, Ducking.gainAt(emptyList(), 100L), 0f)
        val pts = listOf(
            Ducking.Point(0L, 1f),
            Ducking.Point(1000L, 0.5f),
            Ducking.Point(2000L, 0.5f),
            Ducking.Point(3000L, 1f)
        )
        assertEquals(1f, Ducking.gainAt(pts, -50L), 0f)       // clamped to the first gain
        assertEquals(0.75f, Ducking.gainAt(pts, 500L), 0.0001f) // linear between points
        assertEquals(0.5f, Ducking.gainAt(pts, 1500L), 0f)
        assertEquals(1f, Ducking.gainAt(pts, 4000L), 0f)      // clamped to the last gain
    }
}
