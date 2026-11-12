package com.example.engine

import com.example.model.AnimValue
import com.example.model.TextAnimator
import com.example.model.TimelineClip
import com.example.model.unpackColor

/**
 * Evaluates an imported Alight Motion `texttransform` text animator for one frame (step 2 of the XML import).
 * Pure Kotlin (no Android classes) so it can be unit tested. The result is handed to the C++ text engine
 * (`text::Request::anim*`), which cuts the text into letters / words / lines and draws every unit with its own
 * move, opacity and colour.
 *
 * What is understood (names are the Alight property names seen in the sample files):
 *  - `component`  int: 0 = every letter, 1 = every word, 2 = every line (a missing value = 0)
 *  - `start`, `end`  float 0..1: how much of the text (all units together) has "arrived" (default 0..1)
 *  - `offset`  vec2: the move of a unit that has NOT arrived, in the layer's own units (kept relative to the font
 *    height by the importer: [TextAnimator.refSize])
 *  - `opacity` / `alpha`  float 0..1 (guessed names): opacity of a unit that has not arrived (default 1 = no fade)
 *  - `useFillColor` bool + `fillColor` colour: colour of a unit that has not arrived
 * A unit arrives smoothly: its "arrived" part is how much of its slice of the text lies inside start..end.
 * Properties with other names (scale, rotation, ... of other presets) are ignored for now.
 *
 * ASSUMPTION (one sample file only): the offset / fade / colour belong to the units that are OUTSIDE start..end
 * (so `end` 0 -> 1 makes the text arrive: "rise up"). If a real Alight Motion shows the opposite, only [evaluate] and
 * the "arrived" rule in text_engine.cpp (draw: `arrived`) change.
 */
internal object TextAnimEval {

    class Result(
        val component: Int,
        val start: Float,
        val end: Float,
        val dx: Float,
        val dy: Float,
        val alpha: Float,
        val recolor: Boolean,
        val argb: Int
    )

    /** [fontPx]: font height in export pixels at scale 1 (the unit of the offset). Null = nothing to animate. */
    fun evaluate(clip: TimelineClip, timeMs: Long, fontPx: Float): Result? {
        val a = clip.textAnimators.firstOrNull { it.effectId == com.example.engine.AlightXmlImporter.TEXTTRANSFORM } ?: return null
        val dur = clip.durationMs.coerceAtLeast(1L)
        val p = ((timeMs - clip.startMs).toFloat() / dur).coerceIn(0f, 1f)
        return evaluate(a, p, fontPx)
    }

    /** [p]: 0..1 of the layer's length. */
    fun evaluate(a: TextAnimator, p: Float, fontPx: Float): Result {
        fun v(name: String): List<Float>? = a.props[name]?.let { valueAt(it, p) }
        var start = v("start")?.firstOrNull() ?: 0f
        var end = v("end")?.firstOrNull() ?: 1f
        start = start.coerceIn(0f, 1f)
        end = end.coerceIn(0f, 1f)
        val component = (v("component")?.firstOrNull() ?: 0f).toInt().coerceIn(0, 2)
        val off = v("offset") ?: listOf(0f, 0f)
        val ref = if (a.refSize > 1e-3f) a.refSize else 30f
        val dx = (off.getOrNull(0) ?: 0f) / ref * fontPx
        val dy = (off.getOrNull(1) ?: 0f) / ref * fontPx
        val alpha = (v("opacity") ?: v("alpha"))?.firstOrNull()?.coerceIn(0f, 1f) ?: 1f
        val recolor = (v("useFillColor")?.firstOrNull() ?: 0f) > 0.5f
        val col = v("fillColor")
        val argb = if (col != null && col.size >= 2) unpackColor(col[0], col[1]).toInt() else 0xFF00FF00.toInt()

        // The offset / fade / colour belong to what is OUTSIDE start..end: C++ moves a unit by (1 - arrived).
        return Result(component, start, end, dx, dy, alpha, recolor, argb)
    }

    /** Value of a property at [p]: constant, or linear between keyframes (colours and bools step, they do not blend). */
    fun valueAt(v: AnimValue, p: Float): List<Float> {
        val ks = v.keys
        if (ks.isEmpty()) return v.value
        if (p <= ks.first().t) return ks.first().v
        if (p >= ks.last().t) return ks.last().v
        val i = ks.indexOfLast { it.t <= p }
        val k0 = ks[i]
        val k1 = ks[i + 1]
        if (v.type == "color" || v.type == "bool" || v.type == "int") return k0.v
        val span = (k1.t - k0.t).coerceAtLeast(1e-6f)
        val raw = ((p - k0.t) / span).coerceIn(0f, 1f)
        // The segment's easing sits on the keyframe it ENDS at (Alight's `e` attribute), the same
        // convention the fx tails (AmFxEval) and the layer tracks use. Before the Graph round-6
        // link AnimKey stored no easing and this ran linear — the Graph counter's fast-start
        // cubicBezier count (11 of 12 at 1.0 s in Alight) came out a steady 6 here.
        val f = k1.easing?.apply(raw) ?: raw
        return List(minOf(k0.v.size, k1.v.size)) { j -> k0.v[j] + (k1.v[j] - k0.v[j]) * f }
    }
}
