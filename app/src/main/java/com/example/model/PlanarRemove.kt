package com.example.model

import kotlinx.serialization.Serializable

/**
 * Remove (Mocha-style "Remove module"): a clean patch is pasted over a flaw (a mark, a wire, text, dust, a small object) on a
 * flat surface of this video layer. The surface was planar-tracked; the patch is made from the straightened surface (other
 * frames and / or the clean pixels around the flaw, see cpp/engine/remove_plate.h) and is drawn on the moving surface with
 * perspective after the layer's own picture, in the preview and in the export alike.
 *
 * Plain class on purpose (identity equality): nothing inside is changed after construction.
 */
@Serializable
class PlanarRemove(
    /**
     * Where the patch goes: for every tracked frame the 4 corners of the WINDOW of the surface the patch covers, in the
     * picture of the layer itself ([CornerPin.videoId] = the layer's own id).
     */
    val pin: CornerPin,
    /** Brightness factor of the patch for every [pin] entry (the light of the video, 1 = as the first frame). */
    val gains: FloatArray,
    /** File with the patch picture, see [com.example.engine.RemovePatchFile]. */
    val patchPath: String,
    /** The other frames took part in the patch (not only the surrounding). */
    val usedFrames: Boolean
) {
    init {
        require(gains.size == pin.size) { "bad remove gains size" }
    }

    /** Project time range in which the patch is drawn: the part that was tracked. */
    val firstMs: Long get() = if (pin.size > 0) pin.timesMs[0] else 0L
    val lastMs: Long get() = if (pin.size > 0) pin.timesMs[pin.size - 1] else 0L

    /** Is there a tracked frame at project time [t] (a little tolerance: one frame)? */
    fun coversAt(t: Long): Boolean = pin.size > 0 && t >= firstMs - COVER_TOL_MS && t <= lastMs + COVER_TOL_MS

    fun gainAt(t: Long): Float {
        val i = pin.indexAt(t)
        return if (i < 0) 1f else gains[i]
    }

    companion object {
        const val COVER_TOL_MS = 70L
    }
}
