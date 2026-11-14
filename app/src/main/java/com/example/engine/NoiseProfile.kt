package com.example.engine

import android.content.Context
import com.example.model.NoiseReduction
import com.example.model.TimelineClip
import com.example.model.sourceOffsetMs

/**
 * Audio noise reduction (build 195): turns a layer's noise-reduction settings
 * into the per-bin noise profile the shared C++ mixer's gate consumes — the
 * ONE computation both sound paths use (the preview through
 * [NativeAudioPreview]'s cache, the exporter during setup), so preview and
 * export are cleaned identically by construction.
 *
 * The profile depends ONLY on the layer's own sound: its file and the source
 * window it plays ([TimelineClip.trimStartMs] .. + its played source span,
 * the layer's own time law). It is estimated from a streaming decode at the
 * mixer's 48 kHz ([NoiseProfileDecode]) framed exactly as the gate frames
 * its stream (first frame at window sample 0, [NoiseReduction.HOP] grid,
 * zero-padded tail), accumulated by [NoiseReduction.ProfileAccumulator] —
 * the pure law's streaming percentile. Volume, fades and strength do NOT
 * feed the profile: the gate sits inside the source chain, ahead of the
 * mixer's gain stage, and sees the same raw decoded level the profile was
 * estimated from.
 *
 * Any failure along the way (no file, no sound, an over-long span, a decode
 * error) yields null = no processing, never a broken mix. A played span
 * over [AudioBeatDecode.MAX_SPAN_MS] is honestly left unprocessed rather
 * than decoded whole (the ducking route's cap).
 */
internal object NoiseProfile {

    /**
     * Everything a profile depends on, for the preview's cache: the layer's
     * file + played window (trim and the span its own time law — base speed
     * + SPEED keys — produces). Any edit to any of these recomputes; the
     * strength is NOT part of it (the same profile serves every strength).
     */
    data class Key(
        val clipId: String,
        val uri: String,
        val trimStartMs: Long,
        val spanMs: Long
    )

    /** The cache key for [clip]'s profile, or null when it cannot be processed. */
    fun keyFor(clip: TimelineClip): Key? {
        // !(x > 0) also refuses a NaN strength (build 198): with no key, no profile is
        // computed and both mix paths pass strength 0 — a NaN can never reach the gate.
        if (!clip.nrEnabled || !(clip.nrStrength > 0f)) return null
        val uri = clip.uri ?: return null
        val spanMs = clip.sourceOffsetMs(clip.durationMs)
        if (spanMs <= 0L) return null
        return Key(clip.id, uri, clip.trimStartMs, spanMs)
    }

    /**
     * Computes [clip]'s noise profile ([NoiseReduction.BINS] magnitudes), or
     * null when it cannot be computed. Suspending: the decode runs on
     * [NoiseProfileDecode]'s own dispatcher.
     */
    suspend fun compute(context: Context, clip: TimelineClip): FloatArray? {
        val key = keyFor(clip) ?: return null
        if (key.spanMs > AudioBeatDecode.MAX_SPAN_MS) return null
        val accumulator = NoiseReduction.ProfileAccumulator()
        val framer = Framer(accumulator)
        try {
            NoiseProfileDecode.decodeWindow(context, key.uri, key.trimStartMs, key.spanMs) { chunk ->
                framer.push(chunk)
            }
        } catch (e: NoiseProfileDecode.Stopped) {
            return null
        } catch (e: Exception) {
            return null
        }
        framer.finish()
        if (accumulator.frameCount == 0) return null
        return accumulator.profile()
    }

    /**
     * Cuts the decoded mono stream into STFT frames on the gate's own grid
     * (first frame at stream sample 0, one per [NoiseReduction.HOP], the
     * tail frame zero-padded) and feeds their magnitudes to the profile
     * accumulator. Samples are consumed as they arrive; only the not-yet-
     * framed tail (under two frames) is ever held.
     */
    private class Framer(private val accumulator: NoiseReduction.ProfileAccumulator) {
        private var buf = FloatArray(0)   // samples [base, base + buf.size)
        private var base = 0L
        private var total = 0L
        private var nextFrame = 0L

        fun push(chunk: FloatArray) {
            if (chunk.isEmpty()) return
            val merged = FloatArray(buf.size + chunk.size)
            System.arraycopy(buf, 0, merged, 0, buf.size)
            System.arraycopy(chunk, 0, merged, buf.size, chunk.size)
            buf = merged
            total += chunk.size
            emit(final = false)
        }

        fun finish() = emit(final = true)

        private fun emit(final: Boolean) {
            val frame = FloatArray(NoiseReduction.FFT_SIZE)
            while (nextFrame < total) {
                if (!final && nextFrame + NoiseReduction.FFT_SIZE > total) break
                val from = (nextFrame - base).toInt()
                val avail = minOf(NoiseReduction.FFT_SIZE, buf.size - from)
                java.util.Arrays.fill(frame, 0f)
                if (avail > 0) System.arraycopy(buf, from, frame, 0, avail)
                accumulator.add(NoiseReduction.frameMagnitudes(frame))
                nextFrame += NoiseReduction.HOP
            }
            // Drop the samples no future frame can touch (frames start at nextFrame).
            val keepFrom = (nextFrame - base).toInt()
            if (keepFrom > 65536) {
                buf = buf.copyOfRange(keepFrom, buf.size)
                base = nextFrame
            }
        }
    }
}
