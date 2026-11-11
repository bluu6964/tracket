package com.example.engine

import com.example.model.AnimProp
import com.example.model.ClipFx
import com.example.model.ClipFxLibrary
import com.example.model.TimelineClip
import com.example.model.unpackColor
import com.example.model.valueAt

/**
 * Resolves the Solid Color effect's colour for one frame. Pure Kotlin (no Android classes) so it can
 * be unit tested; the resolved values ride the normal fx array into the C++ engine (kFxColor mode 16).
 *
 * A constant colour needs no resolution — the importer's stored values are already the colour. The
 * one keyframed corpus instance (the "Outline" of the file "3": white -> green at ~70 % of the layer)
 * cannot ride a static value, so the importer flags it in values[4] and ALSO stores the colour
 * animation as the clip's FILL_COLOR track (the app's own colour track, evaluated into `textColor`
 * by renderAt); [resolve] rewrites the effect's [0..2] from that track at [timeMs], the same
 * idempotent read [textTrimAt] does for the drawing-progress window: an already-evaluated clip
 * carries the same values in its fields, so resolving twice changes nothing.
 */
internal object SolidColorEval {

    /** values[4] of an imported Solid Color: 1 = the colour follows the clip's FILL_COLOR track. */
    const val FOLLOWS_TRACK = 4

    /**
     * [effects] with every flagged Solid Color's colour set from [clip]'s FILL_COLOR track at
     * [timeMs] (project time). Unflagged effects, and a flagged one whose track is gone, pass
     * through untouched; the input list is returned as-is when nothing needs resolving.
     */
    fun resolve(effects: List<ClipFx>, clip: TimelineClip, timeMs: Long): List<ClipFx> {
        if (effects.none { it.enabled && it.type == ClipFxLibrary.SOLID_COLOR && followsTrack(it) }) return effects
        val keys = clip.keyframes[AnimProp.FILL_COLOR]
        if (keys.isNullOrEmpty()) return effects
        val (a, b) = keys.valueAt(timeMs - clip.startMs)
        val argb = unpackColor(a, b)
        val r = ((argb shr 16) and 0xFF).toFloat()
        val g = ((argb shr 8) and 0xFF).toFloat()
        val bl = (argb and 0xFF).toFloat()
        return effects.map { fx ->
            if (!fx.enabled || fx.type != ClipFxLibrary.SOLID_COLOR || !followsTrack(fx)) fx
            else fx.copy(values = List(maxOf(5, fx.values.size)) { i ->
                when (i) {
                    0 -> r
                    1 -> g
                    2 -> bl
                    else -> fx.values.getOrNull(i) ?: 0f
                }
            })
        }
    }

    private fun followsTrack(fx: ClipFx): Boolean = (fx.values.getOrNull(FOLLOWS_TRACK) ?: 0f) > 0.5f
}
