package com.example.engine

import com.example.model.ClipFx
import com.example.model.ClipFxLibrary
import com.example.model.Easing
import com.example.model.TimelineClip

/**
 * Per-frame resolution of the Alight effects whose parameters are keyframed in the corpus.
 * The v162/v163 tier started it (Mirror's `alpha` — 15 of 51 instances, 0<->1 flip-flash
 * pulses; Glow Scan's `threshold` — all 8, the sweep IS the effect; Luma Key's `lowThreshold`;
 * Turbulent Displace's `intensity` AND `scale`; Swirl's `strength`; Pixelate's `size`), and
 * build 117 rounds it out to every imported effect whose keyframed parameters the fx slots
 * can carry: Exposure (EV / offset / gamma), Tile (scale + phase, each driving both axes),
 * Gaussian / Directional / Radial Blur strengths, Hue/Saturation (a keyframed hueshift hue on
 * a non-bakeable layer, satvib's saturation), RGB Split (strength + angle), Motion Blur's
 * tune (resolved here, consumed by MotionBlurEval downstream), Bulge strength, Brightness &
 * Contrast, Long Shadow's size, Light Rays' strength and centre, and the Mirror / Glow Scan
 * secondary scalars (offset; radius / power / strength / fill / alpha). Pure Kotlin (no
 * Android classes) so it can be unit tested; the resolved values ride the normal fx array
 * into the C++ engine.
 *
 * The native fx stride has no room for an animation, so the importer stores a parameter's
 * keyframe track in the effect's values TAIL (the Repeat Scatter evolution-track precedent):
 * the tail starts at [TAIL_START] for a plain effect and at [EXT_TAIL_START] for an extended
 * one (whose values [7..13] are its EXT group). A tail is a sequence of tracks, one per tracked
 * slot group in the order [tracked] declares: each is a key count followed by six floats per
 * key — the time as a fraction of the layer's span (Alight's own `t`; it may sit outside
 * 0..1, the evaluation clamps like Alight's tracks do), the value in Alight units, and the
 * four cubic-bezier components of the easing of the segment ENDING at that key (a null easing
 * is stored as [Easing.LINEAR]). A slot group whose parameter was constant gets a count-0
 * placeholder when a later group of the same effect IS animated, so the sequence stays
 * aligned; a wholly constant effect carries no tail at all. [resolve] evaluates each track at
 * the frame time and rewrites the parameter's slot(s) (value x the group's scale: 100 for
 * the %-shaped slots, 50 for the app Blur's compressed range, 1 where the slot already holds
 * AM units — hueshift's hue in raw degrees, which the engine fmods by 360 — or where the
 * importer pre-converted the tail into the slot's own units, Long Shadow's length % and Light
 * Rays' length / centre %, whose conversions depend on the layer's box), exactly the
 * [SolidColorEval] pattern: the tail is never touched, so resolving an already-resolved list
 * changes nothing. Colour parameters are never tracked: a packed 0xRRGGBB float cannot be
 * interpolated, so keyframed colours keep the importer's first-value note.
 */
internal object AmFxEval {

    /** Index of the key-count float of an importer-written keyframe track tail. */
    const val TAIL_START = 7

    /** Tail start of an EXTENDED effect (its values [7..13] are the EXT group, see ClipFx). */
    const val EXT_TAIL_START = 14

    /** Tail start of a twice-extended effect (values [7..13] EXT, [14..20] EXT2; the extrude tier). */
    const val EXT2_TAIL_START = 21

    private const val KEY_STRIDE = 6

    /** One animated parameter: the fx slot(s) [resolve] rewrites, and the scale it applies. */
    private data class Tracked(val slots: List<Int>, val scale: Float) {
        constructor(slot: Int, scale: Float) : this(listOf(slot), scale)
    }

    /** The animated parameters of a type, in the order their tails are stored. */
    private fun tracked(type: String): List<Tracked> = when (type) {
        ClipFxLibrary.AM_MIRROR -> listOf(Tracked(2, 100f), Tracked(3, 100f))   // alpha, offset
        ClipFxLibrary.GLOW_SCAN -> listOf(
            Tracked(0, 100f), Tracked(1, 100f), Tracked(2, 1f),
            Tracked(3, 100f), Tracked(4, 100f), Tracked(5, 100f)
        )   // threshold, radius, power, strength, fill, alpha (the packed colour in [6] is never tracked)
        ClipFxLibrary.LUMA_KEY -> listOf(Tracked(0, 100f))                     // low threshold
        ClipFxLibrary.TURBULENT_DISPLACE_AM -> listOf(Tracked(0, 100f), Tracked(1, 100f))  // intensity, scale
        ClipFxLibrary.SWIRL_AM -> listOf(Tracked(0, 100f))                     // strength
        ClipFxLibrary.PIXELATE_AM -> listOf(Tracked(0, 1f))                    // size (already AM units)
        ClipFxLibrary.EXPOSURE -> listOf(Tracked(0, 1f), Tracked(1, 100f), Tracked(2, 100f))  // EV, offset, gamma
        ClipFxLibrary.TILE -> listOf(Tracked(listOf(0, 1), 1f), Tracked(listOf(3, 4), 100f))  // scale, phase (both axes)
        ClipFxLibrary.BLUR -> listOf(Tracked(0, 50f))                          // strength (the app maps 0..1 onto 0..50)
        ClipFxLibrary.DIR_BLUR -> listOf(Tracked(0, 1f), Tracked(1, 100f))     // angle (degrees), strength
        ClipFxLibrary.HUE_SAT -> listOf(Tracked(0, 1f), Tracked(1, 100f))      // hue (raw degrees, fmod 360 in the engine), saturation
        ClipFxLibrary.RGB_SPLIT -> listOf(Tracked(0, 100f), Tracked(1, 1f))    // strength, angle (degrees)
        ClipFxLibrary.RADIAL_BLUR -> listOf(Tracked(0, 100f))                  // strength
        ClipFxLibrary.MOTION_BLUR -> listOf(Tracked(0, 100f))                  // tune (MotionBlurEval reads the resolved slot)
        ClipFxLibrary.MOTION_BLUR4 -> listOf(Tracked(0, 100f))                 // tune (same: MotionBlurEval reads the resolved slot)
        ClipFxLibrary.BULGE -> listOf(Tracked(0, 100f))                        // strength
        ClipFxLibrary.BRIGHT_CONTRAST -> listOf(Tracked(0, 100f), Tracked(1, 100f))  // brightness, contrast
        ClipFxLibrary.LONG_SHADOW -> listOf(Tracked(0, 1f))                    // length (the tail is pre-converted to the slot's %)
        ClipFxLibrary.LIGHT_RAYS -> listOf(Tracked(1, 1f), Tracked(3, 1f), Tracked(4, 1f))  // length, centre x, centre y (pre-converted)
        // v164 tier:
        ClipFxLibrary.CHROMA_KEY_AM -> listOf(Tracked(1, 100f), Tracked(2, 100f))  // threshold, feather
        ClipFxLibrary.COLOR_BALANCE_AM -> listOf(
            Tracked(0, 1f), Tracked(1, 1f), Tracked(2, 1f), Tracked(3, 1f), Tracked(4, 1f),
            Tracked(5, 1f), Tracked(6, 1f), Tracked(7, 1f), Tracked(8, 1f)
        )  // the nine band values (AM units already); fix luminance is a flag, never tracked
        ClipFxLibrary.COLOR_TEMP_AM -> listOf(Tracked(0, 1f), Tracked(1, 100f))  // temperature, strength
        ClipFxLibrary.COLOR_TINT_AM -> listOf(Tracked(0, 1f), Tracked(1, 100f))  // hue (raw degrees), strength
        ClipFxLibrary.SPIN_BLUR_AM -> listOf(Tracked(0, 1f))                      // angle (raw degrees)
        ClipFxLibrary.OFFSET_AM -> listOf(Tracked(0, 1f), Tracked(1, 1f))         // offset x, offset y (AM units)
        ClipFxLibrary.RADIAL_WIPE_AM -> listOf(Tracked(0, 1f))                    // progress (degrees)
        ClipFxLibrary.HEX_SHIFT_AM -> listOf(Tracked(0, 1f), Tracked(4, 1f))      // strength (raw), angle (raw degrees)
        ClipFxLibrary.SOFT_GLOW_AM -> listOf(Tracked(0, 100f))                    // strength
        ClipFxLibrary.DARK_GLOW_AM -> listOf(Tracked(0, 100f), Tracked(1, 100f))  // strength, threshold
        ClipFxLibrary.FILL_BEHIND_AM -> listOf(Tracked(0, 100f), Tracked(10, 1f), Tracked(11, 1f))  // strength, bg offset x / y
        // v165 tier (slots hold AM units, so every scale is 1; point components are
        // separate slots and ride separate tails):
        ClipFxLibrary.WAVE_WARP_AM -> listOf(Tracked(0, 1f), Tracked(3, 1f), Tracked(1, 1f), Tracked(4, 1f))  // phase, magnitude, angle 1, angle 2
        ClipFxLibrary.CIRCULAR_RIPPLE_AM -> listOf(Tracked(2, 1f), Tracked(0, 1f), Tracked(5, 1f), Tracked(6, 1f))  // frequency, strength, centre x, centre y
        ClipFxLibrary.STRETCH_AM -> listOf(Tracked(0, 1f), Tracked(1, 1f))         // scale, angle
        ClipFxLibrary.SQUEEZE_AM -> listOf(Tracked(0, 1f))                         // strength
        ClipFxLibrary.FRACTAL_WARP_AM -> listOf(Tracked(0, 1f), Tracked(4, 1f), Tracked(5, 1f), Tracked(6, 1f), Tracked(7, 1f), Tracked(1, 1f))  // magnitude, offs x, offs y, parallax x, parallax y, scale
        ClipFxLibrary.STRIPES_AM -> listOf(Tracked(3, 1f), Tracked(1, 1f))         // phase, width
        ClipFxLibrary.TURBULENT_DISPLACE3_AM -> listOf(Tracked(0, 1f), Tracked(1, 1f))  // intensity, scale
        ClipFxLibrary.ROUGHEN_EDGES_AM -> listOf(Tracked(2, 1f))                   // evolution
        ClipFxLibrary.STREAKS_AM -> listOf(Tracked(0, 1f))                         // strength
        ClipFxLibrary.SIMPLE_STARS_AM -> listOf(Tracked(7, 1f), Tracked(8, 1f), Tracked(9, 1f), Tracked(1, 1f))  // parallax x, y, z, star size
        ClipFxLibrary.DOTS_AM -> listOf(Tracked(1, 1f), Tracked(6, 1f))            // phase, density
        ClipFxLibrary.RIDGES_AM -> listOf(Tracked(7, 1f), Tracked(8, 1f), Tracked(9, 1f), Tracked(10, 1f), Tracked(2, 1f))  // offs x, offs y, parallax x, parallax y, evolution
        ClipFxLibrary.DISSOLVE_AM -> listOf(Tracked(0, 1f))                        // amount
        ClipFxLibrary.BEND_WARP_AM -> listOf(Tracked(0, 1f), Tracked(1, 1f))      // amount, offset
        ClipFxLibrary.DISPLACE_MAP_AM -> listOf(Tracked(0, 1f), Tracked(1, 1f))   // offs x, offs y
        ClipFxLibrary.DISPLACE_MAP_RAD_AM -> listOf(Tracked(0, 1f))               // offs angle
        ClipFxLibrary.SMOOTH_BEVEL_AM -> listOf(Tracked(2, 1f))                   // specular angle
        // v173: the extrude tier — rotate xyz then position xyz (absolute slots 7..12,
        // AM units, scale 1), tails from index 21 (past the EXT2 group).
        ClipFxLibrary.EXTRUDE_AM, ClipFxLibrary.EXTRUDE_FLAT_AM -> listOf(
            Tracked(7, 1f), Tracked(8, 1f), Tracked(9, 1f),
            Tracked(10, 1f), Tracked(11, 1f), Tracked(12, 1f)
        )  // rotate x/y/z, position x/y/z
        // v175 tier 4: AM units everywhere (scale 1). The plain effects' tails start
        // at 7; the two EXT2 effects' tails start at 21 (see tailStart).
        ClipFxLibrary.MOSAIC_AM -> listOf(Tracked(0, 1f))                        // size
        ClipFxLibrary.HALFTONE_LINES_AM -> listOf(Tracked(2, 1f), Tracked(3, 1f))  // angle, phase
        ClipFxLibrary.INNER_BLUR_AM -> listOf(Tracked(0, 1f))                    // strength
        ClipFxLibrary.FEATHER_AM -> listOf(Tracked(0, 1f))                       // strength
        ClipFxLibrary.ELECTRIC_EDGES_AM -> listOf(Tracked(9, 1f))                // evolution (EXT slot 2, absolute 9)
        ClipFxLibrary.TURBULENCE2_AM -> listOf(
            Tracked(7, 1f), Tracked(8, 1f), Tracked(9, 1f), Tracked(10, 1f)
        )  // offs x / y, parallax x / y (EXT slots 0..3, absolute 7..10)
        // v176: AM units (scale 1). Deep Glow 2's tails start at 21 (see tailStart).
        ClipFxLibrary.GAUSSIAN_BLUR_AM -> listOf(Tracked(0, 1f))                 // strength
        ClipFxLibrary.DEEP_GLOW2_AM -> listOf(Tracked(0, 1f), Tracked(1, 1f))    // strength, oalpha
        // v177 tier 5: AM units (scale 1). BB Maker's tails start at 14 (EXT);
        // the four EXT2 effects' tails start at 21.
        ClipFxLibrary.BBMAKER_AM -> listOf(Tracked(0, 1f), Tracked(1, 1f))       // start, angle
        ClipFxLibrary.GRADIENTFLOW_AM -> listOf(Tracked(3, 1f))                  // progress (real or the synthetic clock tail)
        ClipFxLibrary.PARTX_AM -> listOf(Tracked(7, 1f))                         // EVO (EXT slot 0, absolute 7)
        ClipFxLibrary.LIGHTSWEEP_AM -> listOf(Tracked(3, 1f))                    // progress
        ClipFxLibrary.COLORGRAD3_AM -> listOf(Tracked(10, 1f))                   // angle (EXT slot 3, absolute 10)
        // v178 (build 141): Optic Compensation — AM units (scale 1); tails start at 14 (EXT).
        ClipFxLibrary.OPTIC_COMP_AM -> listOf(Tracked(1, 1f), Tracked(2, 1f))    // distortion, strength
        // Build 167 (round 4 banked ports): AM units (scale 1). Page Curl's
        // progress tail starts at 21 (past the EXT2 group); Tile Shift 2's
        // strength / position tails and Fractal Warp 3's six start at 14 (EXT).
        // Repeat Line takes NO tail: its keyframed angle / scale / phase take
        // their first value at import (the draw loops run RepeatEval on the
        // stored effect, never through this resolver).
        ClipFxLibrary.PAGECURL_AM -> listOf(Tracked(0, 1f))                     // progress
        ClipFxLibrary.TILESHIFT2_AM -> listOf(Tracked(0, 1f), Tracked(5, 1f), Tracked(6, 1f))  // strength, position x, position y
        ClipFxLibrary.FRACTAL_WARP3_AM -> listOf(Tracked(0, 1f), Tracked(4, 1f), Tracked(5, 1f), Tracked(6, 1f), Tracked(7, 1f), Tracked(1, 1f))  // magnitude, offs x, offs y, parallax x, parallax y, scale
        // v180 (Queue 15j): the second-wave ports — AM units (scale 1). Plain tails
        // start at 7, EXT effects' at 14 (LENSD), EXT2 effects' at 21 (REFRACTION,
        // SHAPEFIELD); see tailStart.
        ClipFxLibrary.MINIMAX_AM -> listOf(Tracked(1, 1f))                       // radius
        ClipFxLibrary.RGBGLITCH_AM -> listOf(Tracked(0, 1f))                     // progressa
        ClipFxLibrary.FISHEYE_AM -> listOf(Tracked(0, 1f), Tracked(1, 1f))       // power, centerX
        ClipFxLibrary.VORTEX_BLUR_AM -> listOf(Tracked(0, 1f))                   // strength
        ClipFxLibrary.REFRACTION_AM -> listOf(Tracked(5, 1f))                    // intensity
        ClipFxLibrary.TWITCH_AM -> listOf(Tracked(0, 1f))                        // amplitude
        ClipFxLibrary.TURBULENT_DISPLACE2_AM -> listOf(Tracked(0, 1f))          // intensity
        ClipFxLibrary.SHAPEFIELD_AM -> listOf(Tracked(4, 1f), Tracked(7, 1f))    // evolution, shapeSize (EXT slot 0, absolute 7)
        ClipFxLibrary.BOKEH_AM -> listOf(Tracked(3, 1f), Tracked(1, 1f), Tracked(2, 1f))  // blurRadius, blurWidth, blurHeight
        ClipFxLibrary.LENSBLUR_OBS_AM -> listOf(Tracked(1, 1f))                  // progressa
        ClipFxLibrary.LENSD_AM -> listOf(Tracked(1, 1f))                         // amp
        else -> emptyList()
    }

    /** Where the tail sequence of [type] starts in its values. */
    private fun tailStart(type: String): Int = when (type) {
        ClipFxLibrary.TURBULENT_DISPLACE_AM, ClipFxLibrary.PIXELATE_AM,
        ClipFxLibrary.COLOR_BALANCE_AM, ClipFxLibrary.HEX_SHIFT_AM,
        ClipFxLibrary.SOFT_GLOW_AM, ClipFxLibrary.FILL_BEHIND_AM,
        ClipFxLibrary.WAVE_WARP_AM, ClipFxLibrary.FRACTAL_WARP_AM,
        ClipFxLibrary.TURBULENT_DISPLACE3_AM, ClipFxLibrary.STREAKS_AM,
        ClipFxLibrary.SIMPLE_STARS_AM, ClipFxLibrary.DOTS_AM, ClipFxLibrary.RIDGES_AM,
        ClipFxLibrary.DISPLACE_MAP_RAD_AM, ClipFxLibrary.SMOOTH_BEVEL_AM,
        ClipFxLibrary.BBMAKER_AM, ClipFxLibrary.OPTIC_COMP_AM,
        ClipFxLibrary.LENSD_AM, ClipFxLibrary.TURBULENT_DISPLACE2_AM,
        ClipFxLibrary.TILESHIFT2_AM, ClipFxLibrary.FRACTAL_WARP3_AM -> EXT_TAIL_START
        ClipFxLibrary.EXTRUDE_AM, ClipFxLibrary.EXTRUDE_FLAT_AM,
        ClipFxLibrary.ELECTRIC_EDGES_AM, ClipFxLibrary.TURBULENCE2_AM,
        ClipFxLibrary.DEEP_GLOW2_AM, ClipFxLibrary.PAGECURL_AM,
        ClipFxLibrary.GRADIENTFLOW_AM, ClipFxLibrary.PARTX_AM,
        ClipFxLibrary.LIGHTSWEEP_AM, ClipFxLibrary.COLORGRAD3_AM,
        ClipFxLibrary.DEEP_GLOW_AM,
        ClipFxLibrary.REFRACTION_AM, ClipFxLibrary.SHAPEFIELD_AM -> EXT2_TAIL_START
        else -> TAIL_START
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
     * of the layer's span), reading the track that starts at index [start] in [values], or null
     * when there is no usable track there.
     */
    fun trackValueAt(values: List<Float>, rel: Float, start: Int): Float? {
        if (values.size <= start) return null
        val n = values[start].toInt()
        if (n < 2 || values.size < start + 1 + n * KEY_STRIDE) return null
        fun t(i: Int) = values[start + 1 + i * KEY_STRIDE]
        fun v(i: Int) = values[start + 1 + i * KEY_STRIDE + 1]
        if (rel <= t(0)) return v(0)
        if (rel >= t(n - 1)) return v(n - 1)
        var i = 0
        while (i < n - 2 && t(i + 1) <= rel) i++
        val span = (t(i + 1) - t(i)).coerceAtLeast(1e-6f)
        val f = ((rel - t(i)) / span).coerceIn(0f, 1f)
        val b = start + 1 + (i + 1) * KEY_STRIDE
        val eased = Easing(values[b + 2], values[b + 3], values[b + 4], values[b + 5]).apply(f)
        return v(i) + (v(i + 1) - v(i)) * eased
    }

    /** [trackValueAt] at the plain tail start (the Mirror / Glow Scan layout). */
    fun trackValue(values: List<Float>, rel: Float): Float? = trackValueAt(values, rel, TAIL_START)

    /**
     * [effects] with every tracked parameter set from its tail track at [timeMs] (project time).
     * Effects without a track, and a tracked effect on a clip with no span, pass through
     * untouched; the input list is returned as-is when nothing needs resolving.
     */
    fun resolve(effects: List<ClipFx>, clip: TimelineClip, timeMs: Long): List<ClipFx> {
        val rel = if (clip.durationMs > 0L) (timeMs - clip.startMs).toFloat() / clip.durationMs else 0f
        var changed = false
        val out = effects.map { fx ->
            val slots = if (fx.enabled) tracked(fx.type) else emptyList()
            if (slots.isEmpty()) return@map fx
            var pos = tailStart(fx.type)
            var values = fx.values
            for ((targets, scale) in slots) {
                if (pos >= values.size) break
                val n = values[pos].toInt()
                if (n < 0 || pos + 1 + n * KEY_STRIDE > values.size) break
                val v = if (n >= 2) trackValueAt(values, rel, pos) else null
                if (v != null) {
                    val nv = v * scale
                    if (targets.any { values.getOrNull(it) != nv }) {
                        val size = maxOf(targets.max() + 1, values.size)
                        values = List(size) { i ->
                            if (i in targets) nv else values.getOrNull(i) ?: 0f
                        }
                        changed = true
                    }
                }
                pos += 1 + n * KEY_STRIDE
            }
            if (values === fx.values) fx else fx.copy(values = values)
        }
        return if (changed) out else effects
    }
}
