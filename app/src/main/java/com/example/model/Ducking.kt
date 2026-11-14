package com.example.model

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Audio ducking (build 194): the pure sidechain law. One layer (the trigger —
 * typically a voice) automatically lowers another layer's volume (typically
 * music) while the trigger is loud, and lets it back up in the gaps.
 *
 * THE LAW, end to end:
 *  1. The trigger's sound is decoded exactly as it plays (engine
 *     AudioBeatDecode over the trigger's played window, build 193) and cut
 *     into [STEP_MS] steps; [peakEnvelope] keeps the peak |sample| per step —
 *     the trigger's amplitude envelope in SOURCE time.
 *  2. [timelineLevels] puts that envelope on the trigger's TIMELINE grid:
 *     each timeline step reads the source step the layer's own time law
 *     ([sourceOffsetMs] — trim / speed / speed-ramp, the build-193 inversion
 *     law) places at the step's centre, scaled by how loud the trigger is
 *     actually heard there (its mix volume x its fades, supplied by the
 *     caller). Envelope time is therefore always TIMELINE time, never raw
 *     file time.
 *  3. [targetGain] turns a level into the gain the ducked layer should sit
 *     at: 1 at or below [THRESHOLD], falling linearly (in level) to the
 *     amount's floor at full scale — the drop is proportional to how far
 *     over the threshold the trigger is, like a gentle compressor.
 *  4. [gainsForLevels] smooths the targets with a one-pole attack / release
 *     follower (attack = falling toward a lower gain, release = recovering):
 *     the per-step gain change is bounded by construction
 *     (|dg| <= (1 - floor) x the step coefficient), so there are no clicks
 *     or zipper noise.
 *  5. [curve] samples the smoothed gains over the DUCKED layer's span into
 *     breakpoints (the layer's own clock); the shared C++ mixer multiplies
 *     them into its per-sample gain next to volume and fades
 *     (volume x fade x duck), interpolating linearly between breakpoints —
 *     [gainAt] is that interpolation contract, mirrored 1:1 in
 *     audio_mixer.cpp. Because preview and export share the one mixer, both
 *     duck identically.
 *
 * The curve is computed LIVE from the trigger's current placement whenever
 * either layer changes (the preview caches by the layers' exact fields; the
 * exporter computes during setup) — nothing is baked into keyframes, and
 * this app has no volume keyframes to bake into: ducking multiplies the
 * layer's constant volume and fades without ever replacing them. A dangling
 * trigger (the layer was deleted), a trigger that makes no sound in the mix,
 * a silent trigger, or a 0 dB amount all yield NO curve (null), never a
 * stuck-low volume.
 *
 * Pure Kotlin over plain arrays: no Android types, harness-pinned
 * (DuckingTest drives synthetic envelopes through every clause above).
 */
object Ducking {

    /** The envelope / gain control rate: one step per 10 ms of timeline. */
    const val STEP_MS = 10L

    /**
     * Trigger peak level at or below which nothing ducks (about -34 dBFS):
     * room tone and quiet breaths do not pump the music.
     */
    const val THRESHOLD = 0.02f

    /** The most the Amount slider offers (a 36 dB drop is effectively mute for music under voice). */
    const val MAX_AMOUNT_DB = 36f

    /** Gains this close to 1 count as "not ducking" (curve compression + the no-curve rule). */
    const val GAIN_EPS = 0.001f

    /** One breakpoint of a duck curve, on the ducked layer's own clock (ms from its start). */
    data class Point(val relMs: Long, val gain: Float)

    /** The gain a full-scale trigger settles at for [amountDb] dB of drop (1 when amount <= 0). */
    fun floorGain(amountDb: Float): Float =
        if (amountDb <= 0f) 1f
        else 10.0.pow(-amountDb.coerceAtMost(MAX_AMOUNT_DB).toDouble() / 20.0).toFloat()

    /** Attack time constant (ms) for a smoothness 0..1: 2 ms (snappy) .. 50 ms (soft), exponential. */
    fun attackMs(smoothness: Float): Double =
        exp(lerp(ln(2.0), ln(50.0), smoothness.coerceIn(0f, 1f).toDouble()))

    /** Release time constant (ms) for a smoothness 0..1: 60 ms (quick) .. 1000 ms (slow), exponential. */
    fun releaseMs(smoothness: Float): Double =
        exp(lerp(ln(60.0), ln(1000.0), smoothness.coerceIn(0f, 1f).toDouble()))

    /**
     * The peak |sample| of mono PCM per [stepMs] step (the last, partial step
     * counts). Empty input -> empty envelope.
     */
    fun peakEnvelope(samples: FloatArray, sampleRate: Int, stepMs: Long = STEP_MS): FloatArray {
        if (samples.isEmpty() || sampleRate <= 0) return FloatArray(0)
        val stepSamples = maxOf(1, (sampleRate.toLong() * stepMs / 1000L).toInt())
        val steps = (samples.size + stepSamples - 1) / stepSamples
        val out = FloatArray(steps)
        for (k in 0 until steps) {
            val from = k * stepSamples
            val to = minOf(samples.size, from + stepSamples)
            var peak = 0f
            for (i in from until to) {
                val a = abs(samples[i])
                if (a > peak) peak = a
            }
            out[k] = peak
        }
        return out
    }

    /**
     * The ducked layer's target gain under a trigger [level] (a peak, >= 0):
     * 1 at or below [THRESHOLD], the floor at full scale (level >= 1),
     * linear in level between. [floor] is [floorGain] of the amount.
     */
    fun targetGain(level: Float, floor: Float): Float {
        if (floor >= 1f) return 1f
        val l = abs(level)
        if (l <= THRESHOLD) return 1f
        val over = ((l - THRESHOLD) / (1f - THRESHOLD)).coerceIn(0f, 1f)
        return 1f - over * (1f - floor)
    }

    /**
     * Smooths the per-step target gains of [levels] with the attack / release
     * follower, starting from gain 1. The result has one gain per input step.
     */
    fun gainsForLevels(
        levels: FloatArray,
        amountDb: Float,
        smoothness: Float,
        stepMs: Long = STEP_MS
    ): FloatArray {
        val floor = floorGain(amountDb)
        val out = FloatArray(levels.size)
        if (levels.isEmpty()) return out
        val aAttack = 1.0 - exp(-stepMs.toDouble() / attackMs(smoothness))
        val aRelease = 1.0 - exp(-stepMs.toDouble() / releaseMs(smoothness))
        var g = 1.0
        for (k in levels.indices) {
            val t = targetGain(levels[k], floor).toDouble()
            val a = if (t < g) aAttack else aRelease
            g += (t - g) * a
            out[k] = g.toFloat()
        }
        return out
    }

    /**
     * The trigger's amplitude envelope on its own TIMELINE grid: one level
     * per [STEP_MS] step of the trigger's span, step k covering the trigger-
     * relative [k*STEP, (k+1)*STEP). The source step read for a timeline
     * step is the one the layer's own [sourceOffsetMs] law places at the
     * step's centre, so a trimmed / sped / ramped trigger envelopes exactly
     * where it is heard. [heardGainAt] (trigger-relative ms -> the trigger's
     * mix volume x fade there) scales the file's level into the heard level.
     * Steps past the decoded sound (a decode shorter than the played span)
     * read as silence.
     */
    fun timelineLevels(
        trigger: TimelineClip,
        samples: FloatArray,
        sampleRate: Int,
        heardGainAt: (Long) -> Float
    ): FloatArray {
        if (trigger.durationMs <= 0L) return FloatArray(0)
        val peaks = peakEnvelope(samples, sampleRate)
        val steps = ((trigger.durationMs + STEP_MS - 1L) / STEP_MS).toInt()
        val out = FloatArray(steps)
        for (k in 0 until steps) {
            val relMs = k * STEP_MS
            val centre = minOf(relMs + STEP_MS / 2L, trigger.durationMs)
            val srcStep = (trigger.sourceOffsetMs(centre) / STEP_MS).toInt()
            val peak = if (srcStep in peaks.indices) peaks[srcStep] else 0f
            out[k] = peak * heardGainAt(relMs).coerceAtLeast(0f)
        }
        return out
    }

    /**
     * The duck curve of one ducked layer: breakpoints on the layer's own
     * clock covering [0, ducked.durationMs], or null when nothing in the
     * span actually ducks (silent / absent / non-overlapping trigger, or a
     * 0 dB amount) — the caller then passes no curve and the layer plays at
     * its plain volume. [levels] is the trigger's timeline envelope from
     * [timelineLevels] ([triggerStartMs] = where its step 0 sits).
     */
    fun curve(
        triggerStartMs: Long,
        levels: FloatArray,
        ducked: TimelineClip,
        amountDb: Float,
        smoothness: Float
    ): List<Point>? {
        if (amountDb <= 0f || ducked.durationMs <= 0L) return null
        val steps = ((ducked.durationMs + STEP_MS - 1L) / STEP_MS).toInt().coerceAtLeast(1)
        // The trigger level heard inside each of the ducked layer's steps is
        // the loudest trigger step overlapping it (levels live on the same
        // global 10 ms grid offset by the layers' starts).
        val spanLevels = FloatArray(steps)
        for (k in 0 until steps) {
            val winStart = ducked.startMs + k * STEP_MS
            val winEnd = winStart + STEP_MS
            val first = Math.floorDiv(winStart - triggerStartMs, STEP_MS)
            val last = Math.floorDiv(winEnd - 1L - triggerStartMs, STEP_MS)
            var peak = 0f
            var idx = first
            while (idx <= last) {
                if (idx >= 0L && idx < levels.size) {
                    val v = levels[idx.toInt()]
                    if (v > peak) peak = v
                }
                idx++
            }
            spanLevels[k] = peak
        }
        val gains = gainsForLevels(spanLevels, amountDb, smoothness)
        if (gains.all { it >= 1f - GAIN_EPS }) return null
        // Compress: the span's start, every step whose gain moved by more
        // than GAIN_EPS since the last kept point, and the span's end.
        val out = ArrayList<Point>()
        out.add(Point(0L, gains[0]))
        var lastGain = gains[0]
        for (k in 1 until steps) {
            if (abs(gains[k] - lastGain) > GAIN_EPS) {
                out.add(Point(k * STEP_MS, gains[k]))
                lastGain = gains[k]
            }
        }
        if (out.last().relMs < ducked.durationMs) out.add(Point(ducked.durationMs, gains[steps - 1]))
        return out
    }

    /**
     * The gain of a duck [curve] at [relMs] on the ducked layer's clock:
     * linear between breakpoints, clamped to the end gains outside them, 1
     * for an empty curve. This is the exact contract the C++ mixer
     * implements (audio_mixer.cpp's duckGain) — keep the two in step.
     */
    fun gainAt(curve: List<Point>, relMs: Long): Float {
        if (curve.isEmpty()) return 1f
        if (relMs <= curve.first().relMs) return curve.first().gain
        if (relMs >= curve.last().relMs) return curve.last().gain
        var lo = 0
        var hi = curve.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (curve[mid].relMs <= relMs) lo = mid else hi = mid
        }
        val a = curve[lo]
        val b = curve[hi]
        val span = (b.relMs - a.relMs).toDouble()
        if (span <= 0.0) return b.gain
        val f = (relMs - a.relMs).toDouble() / span
        return (a.gain + (b.gain - a.gain) * f).toFloat()
    }

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t
}
