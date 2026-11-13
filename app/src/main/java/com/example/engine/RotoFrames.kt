package com.example.engine

/**
 * Pure frame-grid math of the Roto Brush (no Android types, so the standalone harness can pin it).
 *
 * Roto masks are made on a fixed-rate grid over the clip's SOURCE time: mask frame `i` shows the
 * source at `frameMs(startMs, i, fps)`, and a brushed source time belongs to the frame
 * [indexOf] rounds to. The generator ([RotoBrushVideo]), the brush screen's scrub snapping and the
 * saved-mask lookup all use these same formulas, so a frame the user brushes is exactly the frame
 * that gets the mask — they can never drift apart.
 */
object RotoFrames {

    /** Number of mask frames for a source span of [spanMs] at [fps] (the +2 covers both ends' rounding). */
    fun frameCount(spanMs: Long, fps: Float): Int = (spanMs / 1000f * fps).toInt() + 2

    /** Source time (ms) of mask frame [index]. */
    fun frameMs(startMs: Long, index: Int, fps: Float): Long = startMs + (index * 1000f / fps).toLong()

    /** Mask frame index nearest to source time [srcMs], clamped to the [frames] made frames. */
    fun indexOf(startMs: Long, srcMs: Long, fps: Float, frames: Int): Int =
        indexOfUnclamped(startMs, srcMs, fps).coerceIn(0, frames - 1)

    /** Same rounding as [indexOf] without the frame-count clamp (never negative). */
    fun indexOfUnclamped(startMs: Long, srcMs: Long, fps: Float): Int =
        (((srcMs - startMs) * fps / 1000f) + 0.5f).toInt().coerceAtLeast(0)
}
