package com.example.model

import kotlin.math.ceil

/**
 * Build 201 (audit S-1): the audio side of a SPEED keyframe ramp.
 *
 * The picture has always followed a layer's SPEED keyframes exactly (the
 * exporter seeks by the integrated [TimelineClip.sourceOffsetMs]; the preview
 * does the same per frame). The SOUND did not: preview and export both played
 * it at the one constant average [TimelineClip.exportSpeed], so on a ramped
 * layer the voice drifted against the picture mid-layer and only re-landed at
 * the ends.
 *
 * The fix samples the ramp into a per-block speed table that the shared C++
 * audio path consumes: the TimeStretcher advances by one entry per produce()
 * block of [BLOCK_FRAMES] output frames (48 kHz), and the mixer's start
 * offset spends the SAME table through the native speedAdvanceFrames
 * integral — so a mid-layer start, the stretch and the layer's end all agree.
 * Preview (NativeAudioPreview) and export (NativeProjectExporter) both build
 * the table here; a layer without SPEED keyframes returns null and keeps the
 * constant-speed path bit-identical.
 */
object SpeedRamp {

    /** Output frames per table entry — the native stretcher's hop (audio_dsp.h kSpeedBlockFrames). */
    const val BLOCK_FRAMES = 512

    /** The mixer's sample rate (audio_dsp.h kAudioRate). */
    const val RATE = 48000

    /** One block on the layer's own clock: 512 / 48000 s = 10.6667 ms. */
    const val BLOCK_MS: Double = BLOCK_FRAMES * 1000.0 / RATE

    /**
     * The speed table for [clip], or null when the layer has no SPEED
     * keyframes (the constant path). Entry i is the keyframed speed at the
     * MIDPOINT of output block i, clamped to the same [MIN_SPEED]..[MAX_SPEED]
     * the native stretcher clamps to. The table covers the layer's whole
     * duration; the native side holds the last entry past the end, matching
     * the keyframe evaluator's own hold law.
     */
    fun tableFor(clip: TimelineClip): FloatArray? {
        val keys = clip.keyframes[AnimProp.SPEED]
        if (keys.isNullOrEmpty()) return null
        val blocks = maxOf(1, ceil(clip.durationMs / BLOCK_MS).toInt())
        val out = FloatArray(blocks)
        for (i in 0 until blocks) {
            val midMs = ((i + 0.5) * BLOCK_MS).toLong()
            out[i] = keys.valueAt(midMs).first.coerceIn(MIN_SPEED, MAX_SPEED)
        }
        return out
    }

    /** Source milliseconds the table spends over the layer's whole duration (the discrete integral). */
    fun sourceMs(table: FloatArray): Double = table.sumOf { it.toDouble() } * BLOCK_MS
}
