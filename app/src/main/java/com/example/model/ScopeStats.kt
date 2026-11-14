package com.example.model

import kotlin.math.roundToInt

/**
 * VIDEO SCOPES (build 196): the statistics behind the editor's scope panel —
 * an RGB + luma histogram, a luma waveform and a vectorscope, all computed
 * from ONE pass over the composed preview frame at the playhead.
 *
 * THE COLOUR LAW is BT.601 full-range YCbCr — the matrix the engine itself
 * documents for its RGB→YCbCr conversion (fx_plan.cpp: Y = 0.299R + 0.587G +
 * 0.114B, Cb = −0.14713R − 0.28886G + 0.436B, Cr = 0.615R − 0.51499G −
 * 0.10001B). The vectorscope needs Cb/Cr, and that conversion is the only
 * one the codebase documents, so the histogram's luma and the waveform use
 * the SAME Y: all three scopes always agree about a pixel. (Several fx
 * ports use Rec.709 luma weights for their own internal thresholds — those
 * are per-effect laws from the Alight defs, not a frame-analysis standard.)
 *
 * THE FRAME the stats describe is the preview surface's picture: every
 * layer and effect as composed at the playhead, over the canvas colour.
 * That picture is opaque by construction (the engine clears at alpha 1),
 * so the alpha byte of the input is ignored.
 *
 * ANALYSIS RESOLUTION: the pass never runs on the full frame. [analyze]
 * first box-averages the pixels down so the longest side is at most
 * [MAX_SIDE] (an exact box mean — a scope reads distributions, and the box
 * mean is the honest downscale: every source pixel weighs the same).
 */
data class ScopeData(
    /** Analysis frame size (after the [ScopeStats.MAX_SIDE] cap). */
    val width: Int,
    val height: Int,
    val pixelCount: Int,
    /** Exact per-value counts, 256 bins each. [histY] bins the rounded BT.601 luma. */
    val histR: IntArray,
    val histG: IntArray,
    val histB: IntArray,
    val histY: IntArray,
    /**
     * Waveform density: `wave[x * 256 + level]` = how many pixels of analysis
     * column x sit at luma [level] (0 = black at the bottom, 255 = white).
     */
    val wave: IntArray,
    /**
     * Vectorscope density on a [ScopeStats.VEC_GRID] × [ScopeStats.VEC_GRID]
     * grid: `vec[row * VEC_GRID + col]`, col from Cb (left = −, right = +),
     * row from Cr (row 0 = highest Cr, the top of the scope). Neutral colours
     * (R = G = B) have Cb = Cr = 0 and land in the centre bin.
     */
    val vec: IntArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScopeData) return false
        return width == other.width && height == other.height && pixelCount == other.pixelCount &&
            histR.contentEquals(other.histR) && histG.contentEquals(other.histG) &&
            histB.contentEquals(other.histB) && histY.contentEquals(other.histY) &&
            wave.contentEquals(other.wave) && vec.contentEquals(other.vec)
    }

    override fun hashCode(): Int {
        var h = width * 31 + height
        h = h * 31 + pixelCount
        h = h * 31 + histR.contentHashCode()
        h = h * 31 + histG.contentHashCode()
        h = h * 31 + histB.contentHashCode()
        h = h * 31 + histY.contentHashCode()
        h = h * 31 + wave.contentHashCode()
        h = h * 31 + vec.contentHashCode()
        return h
    }
}

object ScopeStats {

    /** Histogram / waveform bins: one per 8-bit value. */
    const val BINS = 256

    /** The analysis frame's longest side never exceeds this. */
    const val MAX_SIDE = 320

    /** Vectorscope grid resolution (bins per axis). */
    const val VEC_GRID = 96

    /**
     * Vectorscope half-range in Cb/Cr units. The documented matrix reaches
     * |Cb| ≤ 0.436 and |Cr| ≤ 0.615, so ±0.65 holds every reachable colour
     * with a small margin; neutral sits at the exact centre.
     */
    const val VEC_FULL_SCALE = 0.65f

    /** BT.601 luma of an 8-bit pixel, 0..255 (the Y of the engine's YCbCr matrix). */
    fun luma(r: Int, g: Int, b: Int): Float = 0.299f * r + 0.587f * g + 0.114f * b

    /** BT.601 Cb of an 8-bit pixel, in units of the full channel range (≈ −0.436..0.436). */
    fun cb(r: Int, g: Int, b: Int): Float = (-0.14713f * r - 0.28886f * g + 0.436f * b) / 255f

    /** BT.601 Cr of an 8-bit pixel, in units of the full channel range (≈ −0.615..0.615). */
    fun cr(r: Int, g: Int, b: Int): Float = (0.615f * r - 0.51499f * g - 0.10001f * b) / 255f

    /** Vectorscope column for a Cb value: −[VEC_FULL_SCALE] → 0, +[VEC_FULL_SCALE] → [VEC_GRID] − 1. */
    fun vecBinX(cb: Float): Int =
        (((cb / VEC_FULL_SCALE) * 0.5f + 0.5f) * VEC_GRID).toInt().coerceIn(0, VEC_GRID - 1)

    /** Vectorscope row for a Cr value: +[VEC_FULL_SCALE] → 0 (the top), −[VEC_FULL_SCALE] → [VEC_GRID] − 1. */
    fun vecBinY(cr: Float): Int =
        ((0.5f - (cr / VEC_FULL_SCALE) * 0.5f) * VEC_GRID).toInt().coerceIn(0, VEC_GRID - 1)

    /** A pixel block at analysis resolution (see [downscale]). */
    class Downscaled(val pixels: IntArray, val width: Int, val height: Int)

    /**
     * Box-average [argb] (row-major ARGB, alpha ignored) down to the
     * [MAX_SIDE] cap: each output pixel is the rounded mean of its source
     * box, every source pixel in exactly one box. At or under the cap the
     * input is returned unchanged. Null on a degenerate size or a pixel
     * array shorter than width × height.
     */
    fun downscale(argb: IntArray, width: Int, height: Int): Downscaled? {
        if (width <= 0 || height <= 0 || argb.size < width * height) return null
        val longest = maxOf(width, height)
        if (longest <= MAX_SIDE) return Downscaled(argb, width, height)
        val scale = MAX_SIDE.toFloat() / longest
        val tw = (width * scale).roundToInt().coerceAtLeast(1)
        val th = (height * scale).roundToInt().coerceAtLeast(1)
        val out = IntArray(tw * th)
        for (ty in 0 until th) {
            val y0 = ty * height / th
            val y1 = maxOf(y0 + 1, (ty + 1) * height / th)
            for (tx in 0 until tw) {
                val x0 = tx * width / tw
                val x1 = maxOf(x0 + 1, (tx + 1) * width / tw)
                var sr = 0L
                var sg = 0L
                var sb = 0L
                var n = 0L
                for (y in y0 until y1) {
                    var i = y * width + x0
                    for (x in x0 until x1) {
                        val p = argb[i++]
                        sr += (p shr 16) and 0xFF
                        sg += (p shr 8) and 0xFF
                        sb += p and 0xFF
                        n++
                    }
                }
                val r = ((sr + n / 2) / n).toInt()
                val g = ((sg + n / 2) / n).toInt()
                val b = ((sb + n / 2) / n).toInt()
                out[ty * tw + tx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Downscaled(out, tw, th)
    }

    /**
     * The full scope statistics of one frame: downscale to the cap, then one
     * pass filling the histograms, the waveform columns and the vectorscope
     * grid. Every analysis pixel is counted exactly once in every scope.
     * Null when the input is degenerate (see [downscale]).
     */
    fun analyze(argb: IntArray, width: Int, height: Int): ScopeData? {
        val d = downscale(argb, width, height) ?: return null
        val w = d.width
        val h = d.height
        val px = d.pixels
        val histR = IntArray(BINS)
        val histG = IntArray(BINS)
        val histB = IntArray(BINS)
        val histY = IntArray(BINS)
        val wave = IntArray(w * BINS)
        val vec = IntArray(VEC_GRID * VEC_GRID)
        for (y in 0 until h) {
            var i = y * w
            for (x in 0 until w) {
                val p = px[i++]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                histR[r]++
                histG[g]++
                histB[b]++
                val level = luma(r, g, b).roundToInt().coerceIn(0, BINS - 1)
                histY[level]++
                wave[x * BINS + level]++
                vec[vecBinY(cr(r, g, b)) * VEC_GRID + vecBinX(cb(r, g, b))]++
            }
        }
        return ScopeData(w, h, w * h, histR, histG, histB, histY, wave, vec)
    }
}
