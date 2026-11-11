package com.example.engine

import com.example.model.ClipFx
import com.example.model.ClipFxLibrary
import com.example.model.Easing
import com.example.model.TimelineClip

/**
 * Per-frame resolution of the two Alight effects whose signature parameter is keyframed in the
 * corpus: Mirror's `alpha` (15 of 51 instances — 0<->1 flip-flash pulses) and Glow Scan's
 * `threshold` (all 8 instances — the sweep IS the effect). Pure Kotlin (no Android classes) so
 * it can be unit tested; the resolved values ride the normal fx array into the C++ engine.
 *
 * The native fx stride has no room for an animation, so the importer stores the parameter's
 * keyframe track in the effect's values TAIL, from [TAIL_START] on (the Repeat Scatter
 * evolution-track precedent): [TAIL_START] is the key count, then per key six floats — the
 * time as a fraction of the layer's span (Alight's own `t`; it may sit outside 0..1, the
 * evaluation clamps like Alight's tracks do), the value in Alight units, and the four
 * cubic-bezier components of the easing of the segment ENDING at that key (a null easing is
 * stored as [Easing.LINEAR]). [resolve] evaluates the track at the frame time and rewrites
 * the parameter's own slot (Mirror: values[2] alpha %; Glow Scan: values[0] threshold %),
 * exactly the [SolidColorEval] pattern: the tail is never touched, so resolving an
 * already-resolved list changes nothing.
 */
internal object AmFxEval {

    /** Index of the key-count float of an importer-written keyframe track tail. */
    const val TAIL_START = 7

    private const val KEY_STRIDE = 6

    /** The fx slot [resolve] rewrites for a tracked type, or -1 when the type has no track. */
    private fun trackedSlot(type: String): Int = when (type) {
        ClipFxLibrary.AM_MIRROR -> 2     // alpha
        ClipFxLibrary.GLOW_SCAN -> 0     // threshold
        else -> -1
    }

    /** Encodes (t, value, easing) keys — sorted by t — as a values tail (see the class doc). */
    fun encodeTrack(keys: List<Triple<Float, Float, Easing?>>): List<Float> {
        val out = ArrayList<Float>(1 + keys.size * KEY_STRIDE)
        out += keys.size.toFloat()
        for ((t, v, e) in keys) {
            val ez = e ?: Easing.LINEAR
            out += t
            out += v
            out += ez.x1
            out += ez.y1
            out += ez.x2
            out += ez.y2
        }
        return out
    }

    /**
     * The tracked parameter's value (Alight units) at the layer-relative time [rel] (a fraction
     * of the layer's span), or null when [values] carry no usable track tail.
     */
    fun trackValue(values: List<Float>, rel: Float): Float? {
        if (values.size <= TAIL_START) return null
        val n = values[TAIL_START].toInt()
        if (n < 2 || values.size < TAIL_START + 1 + n * KEY_STRIDE) return null
        fun t(i: Int) = values[TAIL_START + 1 + i * KEY_STRIDE]
        fun v(i: Int) = values[TAIL_START + 1 + i * KEY_STRIDE + 1]
        if (rel <= t(0)) return v(0)
        if (rel >= t(n - 1)) return v(n - 1)
        var i = 0
        while (i < n - 2 && t(i + 1) <= rel) i++
        val span = (t(i + 1) - t(i)).coerceAtLeast(1e-6f)
        val f = ((rel - t(i)) / span).coerceIn(0f, 1f)
        val b = TAIL_START + 1 + (i + 1) * KEY_STRIDE
        val eased = Easing(values[b + 2], values[b + 3], values[b + 4], values[b + 5]).apply(f)
        return v(i) + (v(i + 1) - v(i)) * eased
    }

    /**
     * [effects] with every tracked parameter (Mirror alpha, Glow Scan threshold) set from its
     * tail track at [timeMs] (project time). Effects without a track, and a tracked effect on a
     * clip with no span, pass through untouched; the input list is returned as-is when nothing
     * needs resolving.
     */
    fun resolve(effects: List<ClipFx>, clip: TimelineClip, timeMs: Long): List<ClipFx> {
        if (effects.none { it.enabled && trackedSlot(it.type) >= 0 && trackValue(it.values, 0f) != null }) return effects
        val rel = if (clip.durationMs > 0L) (timeMs - clip.startMs).toFloat() / clip.durationMs else 0f
        return effects.map { fx ->
            val slot = if (fx.enabled) trackedSlot(fx.type) else -1
            val v = if (slot >= 0) trackValue(fx.values, rel) else null
            if (slot < 0 || v == null) fx
            else fx.copy(values = List(maxOf(slot + 1, fx.values.size)) { i ->
                if (i == slot) v * 100f else fx.values.getOrNull(i) ?: 0f
            })
        }
    }
}
