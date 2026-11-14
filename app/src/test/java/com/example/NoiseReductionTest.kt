package com.example

import com.example.model.LayerType
import com.example.model.NoiseReduction
import com.example.model.TimelineClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Audio noise reduction (model/NoiseReduction.kt, build 195): the pure
 * spectral-gating law over SYNTHETIC signals — the field defaults (old
 * projects load unprocessed), the raw gain law's anchor points, the
 * percentile profile (exact + the streaming histogram accumulator), the
 * frequency / temporal smoothing constants, and the whole reference
 * processor driven with a voice-like harmonic tone over white noise at a
 * known SNR: the noise-only segments must drop by a clear margin while the
 * tone segments keep most of their energy. The C++ gate in the shared mixer
 * (audio_selftest blocks 7-10) pins the same numbers on the production path.
 */
class NoiseReductionTest {

    private fun audioClip() = TimelineClip(
        id = "a", name = "a", type = LayerType.AUDIO,
        startMs = 0L, durationMs = 8000L, color = 0xFF000000
    )

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from))
    }

    /** 3 s at 48 kHz: noise, harmonic tone over the same noise, noise. Uniform noise, sigma ~0.098. */
    private fun toneNoiseSignal(): FloatArray {
        val rnd = Random(1234)
        val out = FloatArray(NoiseReduction.SAMPLE_RATE * 3)
        for (i in out.indices) {
            var v = (rnd.nextFloat() * 2f - 1f) * 0.17f
            if (i >= NoiseReduction.SAMPLE_RATE && i < NoiseReduction.SAMPLE_RATE * 2) {
                val ph = 2.0 * PI * i / NoiseReduction.SAMPLE_RATE
                v += (0.5 * sin(440.0 * ph) + 0.25 * sin(880.0 * ph) + 0.125 * sin(1320.0 * ph)).toFloat()
            }
            out[i] = v
        }
        return out
    }

    /** Magnitude frames of a signal on the gate's own grid (the production framing). */
    private fun framesOf(signal: FloatArray): List<FloatArray> {
        val frames = ArrayList<FloatArray>()
        val frame = FloatArray(NoiseReduction.FFT_SIZE)
        var start = 0
        while (start < signal.size) {
            java.util.Arrays.fill(frame, 0f)
            val n = minOf(NoiseReduction.FFT_SIZE, signal.size - start)
            System.arraycopy(signal, start, frame, 0, n)
            frames.add(NoiseReduction.frameMagnitudes(frame))
            start += NoiseReduction.HOP
        }
        return frames
    }

    // ---- Stored fields ---------------------------------------------------

    @Test
    fun fieldDefaultsAreUnprocessed() {
        val c = audioClip()
        assertTrue(!c.nrEnabled)
        assertEquals(0.6f, c.nrStrength, 0f)
    }

    // ---- The raw gain law --------------------------------------------------

    @Test
    fun rawGainAnchorPoints() {
        // Strength 0 is an exact passthrough at every level.
        assertEquals(1f, NoiseReduction.rawGain(0.001f, 0.5f, 0f), 0f)
        assertEquals(1f, NoiseReduction.rawGain(3f, 0.5f, 0f), 0f)
        // A silent bin takes the floor gain (its phase is zero anyway).
        assertEquals(NoiseReduction.FLOOR_GAIN, NoiseReduction.rawGain(0f, 0.5f, 1f), 0f)
        // A bin at over x its floor is driven exactly to the floor gain.
        assertEquals(
            NoiseReduction.FLOOR_GAIN,
            NoiseReduction.rawGain(NoiseReduction.MAX_OVER * 0.25f, 0.25f, 1f), 1e-6f
        )
        // A bin far above its floor: sqrt(1 - (4 x 0.25 / 10)^2) = sqrt(0.99).
        assertEquals(0.99499f, NoiseReduction.rawGain(10f, 0.25f, 1f), 1e-4f)
        // Half strength halves the over-subtraction: sqrt(1 - (2 x 0.25 / 1)^2) = sqrt(0.75).
        assertEquals(0.86603f, NoiseReduction.rawGain(1f, 0.25f, 0.5f), 1e-4f)
        // Monotone: more noise against the same bin never raises the gain.
        var prev = Float.MAX_VALUE
        for (n in listOf(0.01f, 0.05f, 0.1f, 0.2f, 0.4f)) {
            val g = NoiseReduction.rawGain(1f, n, 1f)
            assertTrue("gain must not rise with noise", g <= prev)
            prev = g
        }
    }

    // ---- The profile law ---------------------------------------------------

    @Test
    fun percentileLaw() {
        assertEquals(0f, NoiseReduction.percentile(FloatArray(0), 0.10), 0f)
        assertEquals(7f, NoiseReduction.percentile(floatArrayOf(7f), 0.10), 0f)
        val shuffled = floatArrayOf(9f, 3f, 5f, 0f, 8f, 2f, 7f, 1f, 6f, 4f)
        // sorted 0..9: index floor(0.10 x 9) = 0 -> 0; floor(0.5 x 9) = 4 -> 4.
        assertEquals(0f, NoiseReduction.percentile(shuffled, 0.10), 0f)
        assertEquals(4f, NoiseReduction.percentile(shuffled, 0.5), 0f)
        assertEquals(9f, NoiseReduction.percentile(shuffled, 1.0), 0f)
    }

    @Test
    fun exactProfileReadsTheQuietDecile() {
        // One bin, magnitudes 1..100 across 100 frames -> p10 = sorted[floor(0.1 x 99)] = 10.
        val frames = (1..100).map { f ->
            FloatArray(NoiseReduction.BINS) { k -> if (k == 5) f.toFloat() else 2f }
        }
        val profile = NoiseReduction.noiseProfile(frames)
        assertEquals(10f, profile[5], 0f)
        assertEquals(2f, profile[100], 0f)
        // No frames -> silence profile.
        assertTrue(NoiseReduction.noiseProfile(emptyList()).all { it == 0f })
    }

    @Test
    fun accumulatorAgreesWithExactProfile() {
        val rnd = Random(99)
        val frames = (0 until 300).map {
            FloatArray(NoiseReduction.BINS) { k ->
                // A steady floor per bin with wide fluctuations (log-normal-ish spread).
                val base = 0.02f * (1 + k % 7)
                base * (0.2f + rnd.nextFloat() * 3f)
            }
        }
        val exact = NoiseReduction.noiseProfile(frames)
        val acc = NoiseReduction.ProfileAccumulator()
        frames.forEach { acc.add(it) }
        assertEquals(300, acc.frameCount)
        val streamed = acc.profile()
        for (k in listOf(0, 3, 100, 511, 1024)) {
            val ratio = streamed[k] / exact[k]
            assertTrue("bin $k: streamed ${streamed[k]} vs exact ${exact[k]}", ratio in 0.93f..1.07f)
        }
        assertTrue(NoiseReduction.ProfileAccumulator().profile().all { it == 0f })
    }

    @Test
    fun estimatorFindsTheNoiseFloor() {
        // 400 frames of the synthetic noise alone: every bin's p10 must land near the
        // analytic floor (sigma ~0.098, Hann sum w^2 = 768 -> p10 ~ 9.0 x sigma ~ 0.88).
        val rnd = Random(7)
        val noise = FloatArray(NoiseReduction.SAMPLE_RATE * 2) { (rnd.nextFloat() * 2f - 1f) * 0.17f }
        val profile = NoiseReduction.noiseProfile(framesOf(noise))
        for (k in listOf(10, 100, 300, 700, 1000)) {
            assertTrue("bin $k profile ${profile[k]}", profile[k] in 0.45f..1.45f)
        }
    }

    // ---- Smoothing ----------------------------------------------------------

    @Test
    fun frequencySmoothingKernel() {
        val sm = NoiseReduction.smoothAcrossFrequency(floatArrayOf(0f, 1f, 0f))
        assertEquals(0.25f, sm[0], 1e-6f)
        assertEquals(0.5f, sm[1], 1e-6f)
        assertEquals(0.25f, sm[2], 1e-6f)
        val flat = NoiseReduction.smoothAcrossFrequency(FloatArray(16) { 0.4f })
        assertTrue(flat.all { it == 0.4f })
    }

    @Test
    fun temporalSmoothingConstants() {
        // 1 - exp(-512 / (10 ms in frames = 480)) and 1 - exp(-512 / (80 ms = 3840)).
        assertEquals(0.65585, NoiseReduction.attackCoef, 1e-4)
        assertEquals(0.12483, NoiseReduction.releaseCoef, 1e-4)
        assertTrue(NoiseReduction.attackCoef > NoiseReduction.releaseCoef)
    }

    @Test
    fun frameMagnitudesFindTheTone() {
        val frame = FloatArray(NoiseReduction.FFT_SIZE) { i ->
            (0.5 * sin(2.0 * PI * 440.0 * i / NoiseReduction.SAMPLE_RATE)).toFloat()
        }
        val mags = NoiseReduction.frameMagnitudes(frame)
        var peak = 0
        for (k in 1 until NoiseReduction.BINS) if (mags[k] > mags[peak]) peak = k
        // 440 Hz at 48 kHz with N=2048 -> bin 18.8; the Hann main lobe peaks at 18 or 19.
        assertTrue("peak bin $peak", peak in 18..19)
        assertTrue("peak magnitude ${mags[peak]}", mags[peak] in 150f..270f)
    }

    // ---- The whole processor over synthetic tone + noise ---------------------

    @Test
    fun processorSuppressesNoiseAndKeepsTone() {
        val signal = toneNoiseSignal()
        val profile = NoiseReduction.noiseProfile(framesOf(signal))
        val out = NoiseReduction.Processor(profile, 1f).process(signal)
        assertEquals(signal.size, out.size)
        assertTrue(out.none { it.isNaN() })
        val sr = NoiseReduction.SAMPLE_RATE
        // Middle halves of the segments (steady state, away from the boundaries).
        val noiseRatio = rms(out, sr / 4, sr * 3 / 4) / rms(signal, sr / 4, sr * 3 / 4)
        val toneRatio = rms(out, sr * 5 / 4, sr * 7 / 4) / rms(signal, sr * 5 / 4, sr * 7 / 4)
        val tailRatio = rms(out, sr * 9 / 4, sr * 11 / 4) / rms(signal, sr * 9 / 4, sr * 11 / 4)
        println("NR evidence: noise x$noiseRatio, tone x$toneRatio, tail x$tailRatio")
        assertTrue("noise segment ratio $noiseRatio (want < 0.30, ~-10 dB or better)", noiseRatio < 0.30)
        assertTrue("noise tail ratio $tailRatio (want < 0.30)", tailRatio < 0.30)
        assertTrue("tone segment ratio $toneRatio (want > 0.80)", toneRatio > 0.80)
    }

    @Test
    fun processorStrengthScalesTheReduction() {
        val signal = toneNoiseSignal()
        val profile = NoiseReduction.noiseProfile(framesOf(signal))
        val sr = NoiseReduction.SAMPLE_RATE
        val full = NoiseReduction.Processor(profile, 1f).process(signal)
        val half = NoiseReduction.Processor(profile, 0.5f).process(signal)
        val inRms = rms(signal, sr / 4, sr * 3 / 4)
        val fullRatio = rms(full, sr / 4, sr * 3 / 4) / inRms
        val halfRatio = rms(half, sr / 4, sr * 3 / 4) / inRms
        assertTrue("half strength ($halfRatio) must reduce less than full ($fullRatio)", halfRatio > fullRatio)
    }

    @Test
    fun processorPureTonePreserved() {
        val sr = NoiseReduction.SAMPLE_RATE
        val tone = FloatArray(sr) { i -> (0.5 * sin(2.0 * PI * 440.0 * i / sr)).toFloat() }
        // The profile of unrelated white noise at the synthetic SNR (the estimator's answer).
        val rnd = Random(7)
        val noise = FloatArray(sr) { (rnd.nextFloat() * 2f - 1f) * 0.17f }
        val profile = NoiseReduction.noiseProfile(framesOf(noise))
        val out = NoiseReduction.Processor(profile, 1f).process(tone)
        val ratio = rms(out, sr / 4, sr * 3 / 4) / rms(tone, sr / 4, sr * 3 / 4)
        assertTrue("pure tone ratio $ratio (want > 0.90)", ratio > 0.90)
    }

    @Test
    fun processorSilenceAndPassthrough() {
        val silence = FloatArray(10000)
        val profile = FloatArray(NoiseReduction.BINS) { 0.5f }
        val outSilence = NoiseReduction.Processor(profile, 1f).process(silence)
        assertEquals(silence.size, outSilence.size)
        assertTrue(outSilence.all { it == 0f })

        val signal = toneNoiseSignal()
        // Strength 0 -> exact copy.
        val passthrough = NoiseReduction.Processor(profile, 0f).process(signal)
        assertTrue(passthrough.contentEquals(signal))
        // A malformed profile -> exact copy, never a crash.
        val malformed = NoiseReduction.Processor(FloatArray(10), 1f).process(signal)
        assertTrue(malformed.contentEquals(signal))
        // Empty input -> empty output.
        assertEquals(0, NoiseReduction.Processor(profile, 1f).process(FloatArray(0)).size)
    }

    // ---- build 198 hardening: non-finite strength ---------------------------------------------------
    // coerceIn passes NaN through, so the law must treat a NaN strength as
    // "no processing" itself — never as a gain computation (a NaN gain in the
    // shared mixer's gate would poison the whole render).

    @Test
    fun nonFiniteStrengthIsAnExactPassthrough() {
        assertEquals(0f, NoiseReduction.overSubtraction(Float.NaN), 0f)
        assertEquals(1f, NoiseReduction.rawGain(0.5f, 0.1f, Float.NaN), 0f)
        val input = FloatArray(4096) { sin(it * 0.1).toFloat() }
        val out = NoiseReduction.Processor(FloatArray(NoiseReduction.BINS) { 0.01f }, Float.NaN)
            .process(input)
        assertTrue(input.contentEquals(out))
    }
}
