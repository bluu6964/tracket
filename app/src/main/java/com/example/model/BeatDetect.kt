package com.example.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Auto beat detection (build 193): finds the beats in a stretch of decoded mono
 * PCM and estimates the tempo, so the editor can drop beat marks on the ruler
 * for an audio layer in one tap (the marks themselves are the app's ordinary
 * beat marks — this file only computes times, it owns no store).
 *
 * THE DETECTOR is the classic spectral-flux onset detector (Bello et al.):
 * the signal is cut into Hann-windowed frames (75% overlap), each frame's
 * magnitude spectrum is measured with a radix-2 FFT, and the novelty of a
 * frame is the sum over bins of the POSITIVE magnitude growth since the
 * previous frame — a drum hit injects broadband energy at once, so its flux
 * spikes, while a steady tone's spectrum stands still and its flux stays at
 * zero. Peaks of the novelty curve are picked against an ADAPTIVE threshold
 * (the local median novelty, so a loud chorus and a quiet verse each get a
 * threshold in their own scale) plus a small absolute floor, and accepted
 * strongest-first under a minimum beat spacing of [MIN_BEAT_SPACING_MS].
 * Frame 0's novelty is its whole spectrum (the signal attacks from silence),
 * which is what puts a beat at the very start of a song that starts on one;
 * a steady tone therefore reports at most that single attack.
 *
 * The times this returns are in the DECODED WINDOW's own milliseconds
 * (sample 0 = 0). Turning a window time into a timeline time is the caller's
 * trim/speed mapping — see [timelineMsForSourceOffsets], the exact inverse
 * of the model's own [sourceOffsetMs] law.
 *
 * Pure Kotlin over plain arrays: no Android types, harness-pinned
 * (BeatDetectTest synthesises click tracks at known tempos).
 */
object BeatDetect {

    /** A detection result: beat times (ms in the decoded window, sorted) + the tempo estimate. */
    data class Result(val beatsMs: List<Long>, val bpm: Double?)

    /** Two beats are never closer than this (250 BPM is already beyond any real grid). */
    const val MIN_BEAT_SPACING_MS = 240L

    /** The slowest / fastest interval that counts towards the tempo estimate. */
    const val MIN_TEMPO_INTERVAL_MS = 240L
    const val MAX_TEMPO_INTERVAL_MS = 2000L

    /**
     * Detects the beats in [samples] (mono, roughly -1..1) at [sampleRate] Hz.
     * Empty / silent / too-short input yields an empty result, never a crash.
     */
    fun detect(samples: FloatArray, sampleRate: Int): Result {
        if (sampleRate <= 0 || samples.size < MIN_FRAME_SAMPLES) return Result(emptyList(), null)
        val frameSize = frameSizeFor(sampleRate)
        if (samples.size < frameSize) return Result(emptyList(), null)
        val hop = frameSize / 4
        val frameCount = (samples.size - frameSize) / hop + 1

        // ---- Spectral flux novelty -------------------------------------
        val flux = FloatArray(frameCount)
        val re = FloatArray(frameSize)
        val im = FloatArray(frameSize)
        val mag = FloatArray(frameSize / 2 + 1)
        val prev = FloatArray(frameSize / 2 + 1)
        val window = FloatArray(frameSize) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * i / (frameSize - 1))).toFloat()
        }
        var maxFlux = 0f
        for (k in 0 until frameCount) {
            val start = k * hop
            for (i in 0 until frameSize) {
                re[i] = samples[start + i] * window[i]
                im[i] = 0f
            }
            fft(re, im)
            var f = 0f
            for (b in 0..frameSize / 2) {
                val m = sqrt(re[b] * re[b] + im[b] * im[b])
                mag[b] = m
                val growth = m - prev[b]
                if (growth > 0f) f += growth
                prev[b] = m
            }
            flux[k] = f
            if (f > maxFlux) maxFlux = f
        }
        if (maxFlux <= 0f) return Result(emptyList(), null)
        for (k in flux.indices) flux[k] /= maxFlux

        // ---- Adaptive threshold + peak picking -------------------------
        // Local median over +/-0.5 s of novelty: robust because most frames
        // in any second are NOT onsets, whatever the section's loudness.
        val halfWin = ((sampleRate / 2) / hop).coerceAtLeast(1)
        val spacingFrames =
            ((MIN_BEAT_SPACING_MS * sampleRate) / (1000L * hop)).toInt().coerceAtLeast(1)
        val candidates = ArrayList<Int>()
        val slice = FloatArray(2 * halfWin + 1)
        for (k in flux.indices) {
            val v = flux[k]
            if (v <= ABSOLUTE_FLOOR) continue
            if (k > 0 && v < flux[k - 1]) continue
            if (k < flux.size - 1 && v < flux[k + 1]) continue
            var n = 0
            for (j in maxOf(0, k - halfWin)..minOf(flux.size - 1, k + halfWin)) slice[n++] = flux[j]
            val med = median(slice, n)
            if (v > med * MEDIAN_FACTOR + ABSOLUTE_FLOOR) candidates.add(k)
        }
        // Strongest first; a weaker peak inside the spacing of an accepted
        // stronger one is the same hit's shoulder and drops out.
        candidates.sortByDescending { flux[it] }
        val accepted = ArrayList<Int>()
        for (k in candidates) {
            if (accepted.all { kotlin.math.abs(it - k) >= spacingFrames }) accepted.add(k)
        }
        accepted.sort()
        val beats = accepted.map { k -> frameTimeMs(k, hop, frameSize, sampleRate) }
        return Result(beats, estimateBpm(beats))
    }

    /**
     * The tempo of a beat list: 60000 / the MEDIAN inter-beat interval
     * (intervals outside [MIN_TEMPO_INTERVAL_MS]..[MAX_TEMPO_INTERVAL_MS] —
     * a doubled or missed beat — do not vote). Null when no interval votes.
     */
    fun estimateBpm(beatsMs: List<Long>): Double? {
        if (beatsMs.size < 2) return null
        val intervals = ArrayList<Long>(beatsMs.size - 1)
        for (i in 1 until beatsMs.size) {
            val d = beatsMs[i] - beatsMs[i - 1]
            if (d in MIN_TEMPO_INTERVAL_MS..MAX_TEMPO_INTERVAL_MS) intervals.add(d)
        }
        if (intervals.isEmpty()) return null
        intervals.sort()
        val med = intervals[intervals.size / 2].toDouble()
        if (med <= 0.0) return null
        return kotlin.math.round(60000.0 / med * 10.0) / 10.0
    }

    /**
     * Merges [detected] beat marks into [existing]: a detected mark within
     * [toleranceMs] of an existing one (or of a detected one already taken)
     * is a duplicate and drops out — running detection NEVER removes or moves
     * a mark the user placed by hand. Returns the merged sorted list; how
     * many were added is `result.size - existing.size`.
     */
    fun merge(existing: List<Long>, detected: List<Long>, toleranceMs: Long): List<Long> {
        if (detected.isEmpty()) return existing.sorted()
        val out = existing.sorted().toMutableList()
        for (d in detected.sorted()) {
            if (out.none { kotlin.math.abs(it - d) <= toleranceMs }) out.add(d)
        }
        return out.sorted()
    }

    /**
     * The inverse of the model's time law: for each source offset (ms into
     * the clip's source, counted from the clip's trim start — exactly what
     * [sourceOffsetMs] returns for a layer-relative time), the layer-relative
     * timeline time (ms from the clip's start) at which the layer plays it.
     * Detected beats come out of the file in source time; this is what lands
     * them on the ruler at the moments the user actually hears them, under a
     * constant speed AND under a speed ramp (the ramp is integrated once,
     * with [sourceOffsetMs]'s own 20 ms midpoint rule, and inverted by
     * marching it — never approximated with the average speed). Offsets
     * outside the played span clamp to the clip's ends.
     */
    fun timelineMsForSourceOffsets(clip: TimelineClip, sourceOffsetsMs: List<Long>): List<Long> {
        if (sourceOffsetsMs.isEmpty()) return emptyList()
        val keys = sourceOffsetsMs.mapIndexed { index, off -> index to off }.sortedBy { it.second }
        val out = LongArray(sourceOffsetsMs.size)
        val span = clip.sourceOffsetMs(clip.durationMs)
        val speedKeys = clip.keyframes[AnimProp.SPEED].orEmpty()
        if (speedKeys.isEmpty()) {
            val speed = clip.speed.toDouble().coerceAtLeast(0.01)
            for ((index, off) in keys) {
                out[index] = when {
                    off <= 0L -> 0L
                    off >= span -> clip.durationMs
                    else -> (off / speed).toLong().coerceIn(0L, clip.durationMs)
                }
            }
            return out.toList()
        }
        // Speed ramp: walk the same midpoint integral sourceOffsetMs walks,
        // once, answering every offset on the way (offsets are sorted).
        val step = 20L
        var t = 0L
        var acc = 0.0
        var next = 0
        while (next < keys.size) {
            val target = keys[next].second
            when {
                target <= 0L -> {
                    out[keys[next].first] = 0L
                    next++
                }
                target >= span -> {
                    out[keys[next].first] = clip.durationMs
                    next++
                }
                acc >= target -> {
                    out[keys[next].first] = t
                    next++
                }
                else -> {
                    val dt = minOf(step, clip.durationMs - t)
                    if (dt <= 0L) {
                        out[keys[next].first] = clip.durationMs
                        next++
                    } else {
                        acc += speedKeys.valueAt(t + dt / 2).first.toDouble() * dt
                        t += dt
                    }
                }
            }
        }
        return out.toList()
    }

    /** One offset through [timelineMsForSourceOffsets] (tests / one-off callers). */
    fun timelineMsForSourceOffset(clip: TimelineClip, sourceOffsetMs: Long): Long =
        timelineMsForSourceOffsets(clip, listOf(sourceOffsetMs)).first()

    /** The frame whose spectrum is frame [k]'s: its centre, in ms — where an onset inside it is reported. */
    private fun frameTimeMs(k: Int, hop: Int, frameSize: Int, sampleRate: Int): Long =
        ((k * hop + frameSize / 2) * 1000L) / sampleRate

    /** FFT size for a rate: the largest power of two up to ~1/16 s of audio (46 ms at 44.1 kHz). */
    private fun frameSizeFor(sampleRate: Int): Int {
        var size = 512
        while (size < 4096 && size * 2 <= sampleRate / 16) size *= 2
        return size
    }

    private fun median(values: FloatArray, count: Int): Float {
        val copy = values.copyOf(count)
        copy.sort()
        return if (count == 0) 0f else copy[count / 2]
    }

    /** Iterative radix-2 FFT (in place, [re]/[im] same length, a power of two). */
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

    private const val MIN_FRAME_SAMPLES = 512

    /** Peaks must clear the local median by this factor (plus the floor) to count as onsets. */
    private const val MEDIAN_FACTOR = 1.5f

    /** Novelty floor (novelty is normalised so the loudest frame is 1): below this, dust. */
    private const val ABSOLUTE_FLOOR = 0.04f
}
