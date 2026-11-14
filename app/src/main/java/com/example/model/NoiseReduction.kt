package com.example.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Audio noise reduction (build 195): the pure spectral-gating law. A layer's
 * steady background noise (hiss, hum, fan noise) is estimated from the layer's
 * OWN sound and gated down per frequency bin, while the wanted signal —
 * whose bins sit well above that floor — passes near-unity.
 *
 * THE LAW, end to end:
 *  1. NOISE PROFILE. The clip's played window is decoded at the mixer's own
 *     48 kHz (engine NoiseProfileDecode, build 193's window discipline) and
 *     cut into STFT frames ([FFT_SIZE] Hann-windowed, [HOP] apart). The
 *     profile of a frequency bin is the [PROFILE_PERCENTILE] percentile of
 *     its magnitudes across the whole window — the level the bin returns to
 *     in its quietest decile IS the steady noise under it (a bin holding
 *     only noise reads its own floor; a bin holding signal reads the floor
 *     under the signal's quietest moments, the classic minimum-statistics
 *     trade-off). [noiseProfile] is the exact law over held frames;
 *     [ProfileAccumulator] is its streaming form (a per-bin log histogram —
 *     a 10-minute window is ~56k frames and cannot be held whole), agreeing
 *     with the exact law within one histogram bucket (~2.5%).
 *  2. GATE. Each frame's per-bin gain is power-domain spectral subtraction
 *     against the profile: g = sqrt(max(1 − (over·n/m)², [FLOOR_GAIN]²)),
 *     where m is the bin's magnitude, n its profile value and
 *     over = [MAX_OVER] × strength. A bin at its noise floor (m ≈ n) is
 *     driven to the floor gain at full strength; a bin well above it
 *     (m >> n) keeps g ≈ 1. Strength scales `over` linearly, so strength 0
 *     is an EXACT passthrough (every gain 1) — as is the effect being off.
 *     The floor gain (0.05 ≈ −26 dB) keeps a whisper of the noise bed: pure
 *     zero gains chop the residual into "musical noise".
 *  3. SMOOTHING. Raw per-bin gains jump around with the noise's own
 *     fluctuations, which is itself audible. Gains are therefore smoothed
 *     across frequency (a 3-tap [0.25, 0.5, 0.25]) and across time (a
 *     one-pole follower per bin: [ATTACK_MS] when the gain falls,
 *     [RELEASE_MS] when it recovers) — the same attack/release shape the
 *     ducking law uses, at audio-frame rate. The first frame's gains are
 *     taken directly, so a clip does not leak one loud frame of noise at
 *     its start.
 *  4. RECONSTRUCTION. Gated spectra are inverse-transformed and overlap-
 *     added with the same Hann window; the output is divided by the
 *     accumulated squared-window sum, so edges and stream boundaries stay
 *     exact and the output length equals the input length sample-for-sample.
 *
 * The C++ gate (engine/noise_gate.h, a FrameSource in the shared mixer's
 * per-clip source chain) implements this law 1:1 — same framing, window,
 * gains, smoothing and reconstruction; in stereo the gain of a bin comes
 * from the louder channel's magnitude and applies to both, so the stereo
 * image never wobbles. Because preview and export build their sources
 * through the same two factory chains into the ONE AudioMixer, both hear
 * the gate identically. The profile is computed from the clip's file and
 * played window only — never from its volume or fades (the gate sits
 * inside the source, ahead of the mixer's gain stage).
 *
 * Pure Kotlin over plain arrays: no Android types, harness-pinned
 * (NoiseReductionTest drives synthetic tone + noise through every clause).
 */
object NoiseReduction {

    /** The shared mixer's rate (cpp kAudioRate): all framing is stated at 48 kHz. */
    const val SAMPLE_RATE = 48000

    /** STFT frame length (2048 ≈ 42.7 ms: fine frequency resolution for a steady floor). */
    const val FFT_SIZE = 2048

    /** Frame hop (512 = 75% overlap, the Hann overlap-add grid). */
    const val HOP = 512

    /** Magnitude bins of one frame (0..Nyquist). */
    const val BINS = FFT_SIZE / 2 + 1

    /** The profile takes this percentile of each bin's magnitudes over the window. */
    const val PROFILE_PERCENTILE = 0.10

    /** The lowest gain any bin is driven to (0.05 ≈ −26 dB): a whisper of bed, no musical noise. */
    const val FLOOR_GAIN = 0.05f

    /** Over-subtraction factor at strength 1 (strength scales it linearly to 0). */
    const val MAX_OVER = 4f

    /** Gain-fall time constant of the temporal smoother (ms). */
    const val ATTACK_MS = 10.0

    /** Gain-recovery time constant of the temporal smoother (ms). */
    const val RELEASE_MS = 80.0

    /**
     * The over-subtraction factor of a strength 0..1. Build 198: a NaN strength
     * (coerceIn passes NaN through) reads as 0 — no processing — never as a
     * NaN factor that would turn every gate gain NaN downstream.
     */
    fun overSubtraction(strength: Float): Float {
        val s = strength.coerceIn(0f, 1f)
        return if (s.isNaN()) 0f else MAX_OVER * s
    }

    /**
     * One bin's raw (unsmoothed) gate gain for magnitude [magnitude] against
     * profile value [noise] at [strength]: 1 when strength <= 0 (exact
     * passthrough) or the bin towers over its floor; [FLOOR_GAIN] when the
     * bin sits at (or under) over × its floor. A silent bin ([magnitude] 0)
     * takes the floor gain — its phase is zero, so it contributes nothing.
     */
    fun rawGain(magnitude: Float, noise: Float, strength: Float): Float {
        val s = strength.coerceIn(0f, 1f)
        if (!(s > 0f)) return 1f   // NaN included (build 198): no strength, no gating
        if (magnitude <= 0f) return FLOOR_GAIN
        val r = (overSubtraction(s) * noise / magnitude).toDouble()
        val g2 = 1.0 - r * r
        val floor2 = (FLOOR_GAIN * FLOOR_GAIN).toDouble()
        return if (g2 <= floor2) FLOOR_GAIN else sqrt(g2).toFloat()
    }

    /** The 3-tap frequency smoothing of one frame's raw gains (edges clamp). */
    fun smoothAcrossFrequency(raw: FloatArray): FloatArray {
        val out = FloatArray(raw.size)
        for (k in raw.indices) {
            val a = raw[maxOf(0, k - 1)]
            val b = raw[k]
            val c = raw[minOf(raw.size - 1, k + 1)]
            out[k] = 0.25f * a + 0.5f * b + 0.25f * c
        }
        return out
    }

    /** One-pole coefficient per STFT frame for a time constant of [tauMs] ms. */
    fun stepCoef(tauMs: Double): Double = 1.0 - exp(-HOP * 1000.0 / (tauMs * SAMPLE_RATE))

    /** The temporal smoother's attack coefficient (gain falling). */
    val attackCoef: Double = stepCoef(ATTACK_MS)

    /** The temporal smoother's release coefficient (gain recovering). */
    val releaseCoef: Double = stepCoef(RELEASE_MS)

    /**
     * The [p] percentile of [values] (they are copied + sorted; the element
     * at floor(p × (n − 1))). Empty input -> 0.
     */
    fun percentile(values: FloatArray, p: Double): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.copyOf()
        sorted.sort()
        val idx = (p.coerceIn(0.0, 1.0) * (sorted.size - 1)).toInt()
        return sorted[idx]
    }

    /**
     * The exact noise profile of a set of magnitude frames (one [BINS]
     * array per STFT frame): per bin, the [PROFILE_PERCENTILE] percentile
     * over the frames. No frames -> all zeros (silence gates to silence).
     */
    fun noiseProfile(frames: List<FloatArray>): FloatArray {
        val out = FloatArray(BINS)
        if (frames.isEmpty()) return out
        val column = FloatArray(frames.size)
        for (k in 0 until BINS) {
            for (f in frames.indices) column[f] = frames[f][k]
            out[k] = percentile(column, PROFILE_PERCENTILE)
        }
        return out
    }

    /**
     * The streaming form of [noiseProfile]: each bin keeps a log-spaced
     * histogram of the magnitudes it has seen (a whole played window's
     * frames cannot be held in memory), and [profile] reads the
     * [PROFILE_PERCENTILE] percentile off it — the bucket containing the
     * percentile rank, valued at the bucket's geometric centre. Agrees with
     * the exact law within one bucket (~2.5% of magnitude).
     */
    class ProfileAccumulator {
        private val counts = Array(BINS) { IntArray(BUCKETS) }
        private var frames = 0

        /** How many magnitude frames have been added. */
        val frameCount: Int get() = frames

        fun add(magnitudes: FloatArray) {
            require(magnitudes.size == BINS) { "a magnitude frame has $BINS bins" }
            for (k in 0 until BINS) counts[k][bucketOf(magnitudes[k])]++
            frames++
        }

        /** The profile of everything added so far (all zeros when nothing was). */
        fun profile(): FloatArray {
            val out = FloatArray(BINS)
            if (frames == 0) return out
            val rank = (PROFILE_PERCENTILE * frames).toInt().coerceIn(1, frames)
            for (k in 0 until BINS) {
                var cum = 0
                var b = 0
                while (b < BUCKETS) {
                    cum += counts[k][b]
                    if (cum >= rank) break
                    b++
                }
                out[k] = bucketValue(minOf(b, BUCKETS - 1))
            }
            return out
        }

        companion object {
            /** Histogram resolution: 256 log buckets over [MIN_MAG], [MAX_MAG]. */
            const val BUCKETS = 256
            const val MIN_MAG = 1e-5
            const val MAX_MAG = 2.0
            private val LOG_MIN = log10(MIN_MAG)
            private val LOG_STEP = (log10(MAX_MAG) - LOG_MIN) / BUCKETS

            /** The bucket a magnitude falls into (<= 0 clamps into bucket 0). */
            fun bucketOf(magnitude: Float): Int {
                if (magnitude <= MIN_MAG) return 0
                val b = ((log10(magnitude.toDouble()) - LOG_MIN) / LOG_STEP).toInt()
                return b.coerceIn(0, BUCKETS - 1)
            }

            /** The geometric centre of bucket [b] — the value a percentile read reports. */
            fun bucketValue(b: Int): Float =
                Math.pow(10.0, LOG_MIN + (b + 0.5) * LOG_STEP).toFloat()
        }
    }

    /** The periodic Hann window of the STFT (the TimeStretcher's window law). */
    fun hann(i: Int): Float = 0.5f - 0.5f * cos(2.0 * PI * i / FFT_SIZE).toFloat()

    /** [hann] over the whole frame, computed once (the profile route runs tens of thousands of frames). */
    val window: FloatArray by lazy { FloatArray(FFT_SIZE) { hann(it) } }

    /**
     * The magnitude spectrum ([BINS] values) of one mono frame of exactly
     * [FFT_SIZE] samples, Hann-windowed. The profile route's per-frame step.
     */
    fun frameMagnitudes(frame: FloatArray): FloatArray {
        require(frame.size == FFT_SIZE) { "a frame has $FFT_SIZE samples" }
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        for (i in 0 until FFT_SIZE) re[i] = frame[i] * window[i]
        fft(re, im)
        val out = FloatArray(BINS)
        for (k in 0 until BINS) out[k] = sqrt(re[k] * re[k] + im[k] * im[k])
        return out
    }

    /**
     * The whole-buffer mono reference processor: the gate law applied to a
     * complete signal, framed exactly as the streaming C++ gate frames its
     * stream (first frame at sample 0, hop grid, zero-padded tail), so the
     * harness pins the very numbers the phone produces. Output length ==
     * input length. Strength <= 0 or a malformed profile -> an exact copy
     * (passthrough).
     */
    class Processor(private val profile: FloatArray, private val strength: Float) {
        private val gains = FloatArray(BINS) { 1f }
        private var started = false

        fun process(input: FloatArray): FloatArray {
            if (input.isEmpty()) return FloatArray(0)
            if (!(strength > 0f) || profile.size != BINS) return input.copyOf()   // NaN strength = passthrough (build 198)
            val acc = FloatArray(input.size + FFT_SIZE)
            val norm = FloatArray(input.size + FFT_SIZE)
            val re = FloatArray(FFT_SIZE)
            val im = FloatArray(FFT_SIZE)
            var start = 0
            while (start < input.size) {
                for (i in 0 until FFT_SIZE) {
                    val s = if (start + i < input.size) input[start + i] else 0f
                    re[i] = s * window[i]
                    im[i] = 0f
                }
                fft(re, im)
                val raw = FloatArray(BINS)
                for (k in 0 until BINS) {
                    val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                    raw[k] = rawGain(mag, profile[k], strength)
                }
                val sm = smoothAcrossFrequency(raw)
                if (!started) {
                    sm.copyInto(gains)
                    started = true
                } else {
                    for (k in 0 until BINS) {
                        val a = if (sm[k] < gains[k]) attackCoef else releaseCoef
                        gains[k] = (gains[k] + (sm[k] - gains[k]) * a).toFloat()
                    }
                }
                for (k in 0 until FFT_SIZE) {
                    val g = gains[minOf(k, FFT_SIZE - k)]
                    re[k] *= g
                    im[k] *= g
                }
                ifft(re, im)
                for (i in 0 until FFT_SIZE) {
                    val w = window[i]
                    acc[start + i] += re[i] * w
                    norm[start + i] += w * w
                }
                start += HOP
            }
            val out = FloatArray(input.size)
            for (i in input.indices) out[i] = if (norm[i] > 1e-6f) acc[i] / norm[i] else 0f
            return out
        }
    }

    /** Iterative radix-2 FFT (in place, unscaled; [re]/[im] a power of two). */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cwr = 1f
                var cwi = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cwr - im[i + k + len / 2] * cwi
                    val vi = re[i + k + len / 2] * cwi + im[i + k + len / 2] * cwr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val nwr = cwr * wr - cwi * wi
                    cwi = cwr * wi + cwi * wr
                    cwr = nwr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Inverse FFT via the conjugate trick, scaled by 1/n. */
    private fun ifft(re: FloatArray, im: FloatArray) {
        val n = re.size
        for (i in 0 until n) im[i] = -im[i]
        fft(re, im)
        for (i in 0 until n) {
            re[i] = re[i] / n
            im[i] = -im[i] / n
        }
    }
}
