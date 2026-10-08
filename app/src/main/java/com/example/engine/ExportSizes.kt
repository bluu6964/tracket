package com.example.engine

import com.example.model.AspectRatio
import kotlin.math.roundToInt

/**
 * Output frame size of an export. Kept in one place so the export dialog and the
 * encoder can never disagree about what "1080p" means for a given aspect ratio.
 */
/** Frame rate used when a still photo is turned into video frames; video keeps its source rate. */
const val IMAGE_FRAME_RATE = 30

object ExportSizes {

    /**
     * "1080p FHD" + 9:16 -> 1080x1920: the number in the label is the SHORT side,
     * the long side follows the aspect ratio. Both sides are even (H.264 needs it).
     */
    fun forResolution(resolution: String, ratio: AspectRatio): Pair<Int, Int> {
        val short = Regex("\\d{3,4}").find(resolution)?.value?.toInt() ?: 1080
        val r = ratio.ratioFloat
        val (w, h) = if (r >= 1f) (short * r) to short.toFloat() else short.toFloat() to (short / r)
        fun even(x: Float) = (x.roundToInt() / 2) * 2
        return even(w) to even(h)
    }
}
