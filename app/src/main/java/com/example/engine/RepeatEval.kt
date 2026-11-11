package com.example.engine

import com.example.model.ClipFx
import com.example.model.ClipFxLibrary
import com.example.model.TimelineClip
import com.example.model.unpackColor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Alight Motion's repeat family — plain `repeat`, `repeat.radial`, `repeat.scatter`: the layer drawn
 * once per copy, the copies arranged in a row / on a ring / scattered over a disc. Pure Kotlin (no
 * Android classes) so it can be unit tested; the C++ side needs no change (the unresolved types
 * 56..58 are skipped by fxParseOps: they sit above FX_MAX, like Motion Blur).
 *
 * What Alight stores for the plain repeat (all 3 plain-repeat instances in the corpus — on the
 * "Horizontal Lines" / "Vertical Lines" shapes — have angle 0, scale 1, alpha 1, time 0, so only
 * count + offset is pinned by real files; the rest is the plain reading of the parameter names, to
 * check on the phone comparison):
 * ```
 * <effect id="com.alightcreative.effects.repeat" locallyApplied="true">
 *   <property name="count" type="float" value="5.000000" />
 *   <property name="time" type="float" value="0.000000" />
 *   <property name="offset" type="vec2" value="0.000000,93.000000" />
 *   <property name="angle" type="float" value="0.000000" />
 *   <property name="scale" type="float" value="1.000000" />
 *   <property name="alpha" type="float" value="1.000000" />
 * </effect>
 * ```
 * The app stores it as the Repeat effect (see [ClipFxLibrary.REPEAT]): values =
 * [count, offsetX, offsetY, angle, scale %, alpha %] — the offset converted to preview px by the
 * importer, like every other position. `time` (a per-copy time shift) is NOT stored: no corpus
 * instance uses it, and the draw loops only have the layer as it is at the frame time.
 *
 * Copy i (0 = the layer itself): position + i * offset, rotation + i * angle, scale * scale^i,
 * opacity * alpha^i. The turn and the scale are about the layer's own centre (its pivot), the
 * offset is in scene space. A shape keeps its aspect: only the uniform [TimelineClip.scalePercent]
 * is multiplied, [TimelineClip.shapeScaleX] / `shapeScaleY` stay — the painters draw the size from
 * those (see `shapeDrawScaleX`).
 *
 * `repeat.radial` (the ST Intro "Line 1" layers) puts [count] copies on a ring and does NOT draw
 * the layer at the centre: copy i sits at angle startAngle + i * (sweep / count), turned by that
 * angle + `orientation`, scaled by `baseScale` * `scale`^i. Two of those readings are frame-measured
 * against Alight's own ST Intro export (2026-11-11), not guessed: the ring angles run CLOCKWISE
 * FROM STRAIGHT UP — the direction is (sin a, -cos a), which lands all four measured dashes of both
 * line layers on their copies to ~0.1 degrees, where the (cos, sin) reading leaves one copy in
 * four off by ~7 degrees — and the ring radius is TWICE the `radius` property (Alight's radius
 * behaves like its shape sizes, a half extent: the measured dash centroids sit 394..416 px from the
 * centre for radius 223, reachable only from a 446 px anchor; 223 px cannot reach them). The
 * copies' remaining offset from their anchors (the dash's own place on the line) comes from the
 * copy's rotation, so it follows automatically. Values: [count, radiusPx, startAngle, sweep,
 * orientation, baseScale %, alpha %, angle, scaleStep %, altColour...] (see [ClipFxLibrary]).
 *
 * `repeat.scatter` (the ST Intro "Circle 1" particles) puts [count] copies over a disc around the
 * layer. Alight places them from a pseudo-random field keyed by `scatterSeed`; that generator is
 * not public and the field cannot be read off the exported frames (the copies wander smoothly,
 * each on its own path, as `evolution` runs), so the field here is a deterministic hash field,
 * uniform over the disc, with per-copy scale spread between the effect's `scale` floor and 1 (the
 * measured dots span 0.16..0.86 of the base circle). What the field's SHAPE is was settled by
 * frame measurement against Alight's export (round 5, 2026-11-11): the disc is centred on the
 * LAYER — every copy stays within the written radius of the layer's position even with a large
 * `offset` written, so the offset reads as a translation of Alight's field (a different pattern,
 * not a moved centre) and is folded into the hash seed here — and the written radius is read in
 * height units: every copy stays within radius x sceneH / sceneW of the layer (1242 -> 699 px on
 * the 1920x1080 scene; the importer converts). The drift is measured too: each copy's angle turns
 * ONE way as evolution grows (three tracked copies, all the same sign, 0.2 .. 0.4 of a turn per
 * evolution unit) and its radius breathes between a tenth and the full disc radius (radii measured
 * growing x3 and shrinking x0.25 over 1.5 s). Positions therefore still differ from Alight's copy
 * for copy; counts, sizes, coverage and drift match, and the phone comparison can judge them. With
 * `colorAltCopies` the odd copies take the effect's fill colour (the measured dots alternate
 * white / lavender exactly so). Values: [count, radiusPx, offsetX, offsetY, seed, scaleFloor %,
 * alpha %, fieldAngle, evolution (t0, v0, t1, v1), altColour...].
 *
 * [undoRotationDeg] is for a layer drawn into its own upright picture (a pose / camera layer): the
 * renderer turns that picture by the layer's own rotation when it places it, so the copies are
 * placed by the offset undone by that turn — pass the rendered layer's rotationDeg there, 0
 * otherwise. It is exact for a centred pivot (the usual case); an off-centre pivot leaves the
 * copies shifted by the pivot error, which the phone comparison can measure.
 *
 * The overlay loops above draw copy CLIPS with the painters. The picture paths (a video / photo
 * texture, a Group layer's composed picture) cannot do that — they place ONE finished picture with
 * renderer pose parameters — so they expand the same arrangements into per-copy pose deltas
 * ([copyDeltas], see RepeatDraw.kt): the same texture drawn once per copy, posed by the delta.
 * The arrangements are additive in position / rotation and multiplicative in scale / opacity, so a
 * copy's delta against the layer's own pose does not depend on that pose; [copyDeltas] reads the
 * deltas off [copiesOf] run on a neutral-pose probe of the layer, which keeps the two expansions
 * in lockstep by construction. A Group layer's copies pose its frame-sized picture about the
 * group's own anchor ([groupCopyOffset]).
 *
 * Known limits (same family as Motion Blur): parent motion is already in the rendered clip the
 * copies are made from, so a repeated group member moves with its group; the copies are drawn
 * into the layer's own picture, so a layer with a repeat-family effect always gets one (see
 * `hasEffects`). The scatter evolution only knows its first and last keyframes (the importer
 * stores just those two). On the picture paths: a corner-pinned or camera-placed video / photo
 * keeps its single quad (the pin IS its pose, copies would stack on it); a Null parent's turn
 * does not turn a picture layer's copy offsets (they stay scene-space, as in the overlay
 * expansion); and the alternating-copy colour has no picture-path counterpart — a copy of a
 * photo or a group cannot be re-tinted per copy, so those copies keep the picture's colours.
 */
internal object RepeatEval {

    /** Largest copy count drawn: Alight allows hundreds, which would stall a frame. */
    const val MAX_COUNT = 50

    /** The layer's enabled plain Repeat effect, or null. */
    fun repeatOf(clip: TimelineClip): ClipFx? =
        clip.effects.firstOrNull { it.enabled && it.type == ClipFxLibrary.REPEAT }

    /** The layer's enabled repeat-family effect (plain / radial / scatter), or null. */
    fun anyRepeatOf(clip: TimelineClip): ClipFx? =
        clip.effects.firstOrNull {
            it.enabled && (it.type == ClipFxLibrary.REPEAT ||
                it.type == ClipFxLibrary.REPEAT_RADIAL ||
                it.type == ClipFxLibrary.REPEAT_SCATTER)
        }

    /** Copy count of a repeat-family effect: values[0], 1..[MAX_COUNT]. */
    fun countOf(fx: ClipFx): Int = (fx.values.getOrNull(0) ?: 1f).roundToInt().coerceIn(1, MAX_COUNT)

    /**
     * The layer drawn once per copy (for the plain repeat the first entry is always [clip] itself).
     * No repeat-family effect, or a disabled one, returns just the layer. [timeMs] is the frame
     * time: only the scatter field reads it (its evolution); the other arrangements are static.
     */
    fun copiesOf(clip: TimelineClip, undoRotationDeg: Float = 0f, timeMs: Long = Long.MIN_VALUE): List<TimelineClip> {
        val fx = anyRepeatOf(clip) ?: return listOf(clip)
        return when (fx.type) {
            ClipFxLibrary.REPEAT_RADIAL -> radialCopiesOf(clip, fx, undoRotationDeg)
            ClipFxLibrary.REPEAT_SCATTER -> scatterCopiesOf(clip, fx, undoRotationDeg, timeMs)
            else -> plainCopiesOf(clip, fx, undoRotationDeg)
        }
    }

    /**
     * One copy's pose against the layer's own pose, for the draw paths that place a finished
     * picture (a video / photo texture, a Group layer's composed picture): the copy sits
     * ([dx], [dy]) preview px away from the layer, turned [rotDeg] more, scaled by [scaleMul]
     * about its own anchor and faded by [opacityMul]. For the plain repeat the first delta is
     * the identity (the layer itself); radial / scatter have no centre copy.
     */
    data class CopyDelta(val dx: Float, val dy: Float, val rotDeg: Float, val scaleMul: Float, val opacityMul: Float)

    /**
     * The copies of [clip]'s repeat-family effect as pose deltas ([CopyDelta]), or null when the
     * layer has no enabled repeat-family effect. The deltas are read off [copiesOf] run on a
     * neutral-pose probe of the layer (position 0, no turn, scale / opacity 100): every
     * arrangement is additive in position / rotation and multiplicative in scale / opacity, so a
     * copy's delta is the same whatever pose the layer itself is in — including a keyframed one
     * the probe cannot see — and the picture paths stay in lockstep with the overlay path by
     * construction. [timeMs] is the frame time (only the scatter field reads it).
     */
    fun copyDeltas(clip: TimelineClip, timeMs: Long = Long.MIN_VALUE): List<CopyDelta>? {
        anyRepeatOf(clip) ?: return null
        val probe = clip.copy(
            transformX = 0f, transformY = 0f, rotationDeg = 0f, scalePercent = 100f, opacity = 100f
        )
        return copiesOf(probe, 0f, timeMs).map { c ->
            CopyDelta(
                dx = c.transformX, dy = c.transformY, rotDeg = c.rotationDeg,
                scaleMul = c.scalePercent / 100f, opacityMul = (c.opacity / 100f).coerceIn(0f, 1f)
            )
        }
    }

    /**
     * Canvas-space offset for drawing a frame-sized group picture as one repeat copy: the group
     * picture is drawn with the renderer's plain 2D placement (offset = the picture centre's
     * canvas px from the canvas centre, turn / scale about that centre), and a copy must come out
     * as the picture transformed about the group's own anchor — the anchor point ([anchorX],
     * [anchorY], canvas px from the canvas centre) lands on anchor + the copy's offset, and every
     * other point follows by the copy's turn / scale about it:
     * offset = anchor + delta * [k] - R(rot) * S(scale) * anchor (the same folding the layer
     * anim uses for a layer's pivot). [k] = canvas px per preview px.
     */
    fun groupCopyOffset(anchorX: Float, anchorY: Float, d: CopyDelta, k: Float): Pair<Float, Float> {
        val rad = d.rotDeg * PI.toFloat() / 180f
        val cosr = cos(rad)
        val sinr = sin(rad)
        val rx = d.scaleMul * (cosr * anchorX - sinr * anchorY)
        val ry = d.scaleMul * (sinr * anchorX + cosr * anchorY)
        return Pair(anchorX + d.dx * k - rx, anchorY + d.dy * k - ry)
    }

    /** Plain `repeat`: a row of copies, each shifted / turned / scaled / faded one more step. */
    private fun plainCopiesOf(clip: TimelineClip, fx: ClipFx, undoRotationDeg: Float): List<TimelineClip> {
        val n = countOf(fx)
        if (n <= 1) return listOf(clip)
        val ox = fx.values.getOrNull(1) ?: 0f
        val oy = fx.values.getOrNull(2) ?: 0f
        val angle = fx.values.getOrNull(3) ?: 0f
        val scale = (fx.values.getOrNull(4) ?: 100f) / 100f
        val alpha = (fx.values.getOrNull(5) ?: 100f) / 100f
        // Undo the upright picture's own turn (identity unless the caller passes one): the picture
        // is turned clockwise by undoRotationDeg on screen, so a copy that must land i * offset away
        // from the layer is placed i * R(-undoRotationDeg) * offset inside the picture. Screen y is
        // down, so the clockwise turn is (x, y) -> (x cos - y sin, x sin + y cos).
        val rad = undoRotationDeg * PI.toFloat() / 180f
        val cosR = cos(rad)
        val sinR = sin(rad)
        val ux = ox * cosR + oy * sinR
        val uy = -ox * sinR + oy * cosR
        return List(n) { i ->
            if (i == 0) clip
            else clip.copy(
                transformX = clip.transformX + i * ux,
                transformY = clip.transformY + i * uy,
                rotationDeg = clip.rotationDeg + i * angle,
                scalePercent = (clip.scalePercent * scale.pow(i)).coerceAtLeast(0.01f),
                opacity = (clip.opacity * alpha.pow(i)).coerceIn(0f, 100f)
            )
        }
    }

    /**
     * `repeat.radial`: [count] copies on a ring around the layer (see the class doc for the two
     * frame-measured readings: clockwise-from-up angles, twice the stored radius).
     */
    private fun radialCopiesOf(clip: TimelineClip, fx: ClipFx, undoRotationDeg: Float): List<TimelineClip> {
        val n = countOf(fx)
        val radius = (fx.values.getOrNull(1) ?: 0f) * 2f
        val startAngle = fx.values.getOrNull(2) ?: 0f
        val sweep = fx.values.getOrNull(3) ?: 360f
        val orientation = fx.values.getOrNull(4) ?: 0f
        val baseScale = (fx.values.getOrNull(5) ?: 100f) / 100f
        val alpha = (fx.values.getOrNull(6) ?: 100f) / 100f
        val angle = fx.values.getOrNull(7) ?: 0f
        val scaleStep = (fx.values.getOrNull(8) ?: 100f) / 100f
        val alt = altColorOf(fx, 9)
        val rad = undoRotationDeg * PI.toFloat() / 180f
        val cosU = cos(rad)
        val sinU = sin(rad)
        return List(n) { i ->
            val aDeg = startAngle + i * (sweep / n)
            val a = aDeg * PI.toFloat() / 180f
            // Clockwise from straight up: 0 deg points up, 90 deg points right (screen y is down).
            val ax = radius * sin(a)
            val ay = -radius * cos(a)
            val ux = ax * cosU + ay * sinU
            val uy = -ax * sinU + ay * cosU
            var c = clip.copy(
                transformX = clip.transformX + ux,
                transformY = clip.transformY + uy,
                rotationDeg = clip.rotationDeg + aDeg + orientation + i * angle,
                scalePercent = (clip.scalePercent * baseScale * scaleStep.pow(i)).coerceAtLeast(0.01f),
                opacity = (clip.opacity * alpha).coerceIn(0f, 100f)
            )
            if (alt != null && i % 2 == 1) c = c.copy(textColor = alt)
            c
        }
    }

    /**
     * `repeat.scatter`: [count] copies over a disc around the layer (see the class doc: a hash
     * field of the same shape as Alight's, drifting with the stored evolution track).
     */
    private fun scatterCopiesOf(clip: TimelineClip, fx: ClipFx, undoRotationDeg: Float, timeMs: Long): List<TimelineClip> {
        val n = countOf(fx)
        val radius = fx.values.getOrNull(1) ?: 0f
        val ox = fx.values.getOrNull(2) ?: 0f
        val oy = fx.values.getOrNull(3) ?: 0f
        val seed = fx.values.getOrNull(4) ?: 0f
        val scaleFloor = ((fx.values.getOrNull(5) ?: 0f) / 100f).coerceIn(0f, 1f)
        val alpha = (fx.values.getOrNull(6) ?: 100f) / 100f
        val fieldAngle = (fx.values.getOrNull(7) ?: 0f) * PI.toFloat() / 180f
        val e = evolutionAt(fx, clip, timeMs)
        val alt = altColorOf(fx, 12)
        // Alight's `offset` translates its field rather than moving the field's centre (see the
        // class doc), so here it shifts the hash seed: a different offset, a different pattern.
        val seedBits = (seed * 1000f).roundToInt() + (ox * 131f + oy * 57f).roundToInt()
        val rad = undoRotationDeg * PI.toFloat() / 180f
        val cosU = cos(rad)
        val sinU = sin(rad)
        val twoPi = 2f * PI.toFloat()
        return List(n) { i ->
            val u1 = hash01(seedBits, i, 1)
            val u2 = hash01(seedBits, i, 2)
            val u3 = hash01(seedBits, i, 3)
            val u4 = hash01(seedBits, i, 4)
            val u5 = hash01(seedBits, i, 5)
            // Each copy keeps its hashed place in the disc but wanders with the evolution: its angle
            // turns ONE way at its own hashed rate (up to 0.375 of a turn per evolution unit — the
            // tracked copies in Alight's export all turn the same way, 0.2 .. 0.4) and its radius
            // breathes between 0.1 and 1.0 of its hashed radius (radii there grow x3 and shrink
            // x0.25 over 1.5 s), so a copy never leaves the disc. At evolution 0 the copies sit
            // near their hashed places.
            val theta = twoPi * u1 + fieldAngle - twoPi * u4 * 0.375f * e
            val rho = radius * sqrt(u2) * (0.55f + 0.45f * sin(twoPi * (0.8f * e + u5)))
            val ax = rho * cos(theta)
            val ay = rho * sin(theta)
            val ux = ax * cosU + ay * sinU
            val uy = -ax * sinU + ay * cosU
            var c = clip.copy(
                transformX = clip.transformX + ux,
                transformY = clip.transformY + uy,
                scalePercent = (clip.scalePercent * (scaleFloor + (1f - scaleFloor) * u3)).coerceAtLeast(0.01f),
                opacity = (clip.opacity * alpha).coerceIn(0f, 100f)
            )
            if (alt != null && i % 2 == 1) c = c.copy(textColor = alt)
            c
        }
    }

    /** The scatter `evolution` at the frame time, from the two-key track stored in values [8..11]. */
    private fun evolutionAt(fx: ClipFx, clip: TimelineClip, timeMs: Long): Float {
        val v0 = fx.values.getOrNull(9) ?: 0f
        if (timeMs == Long.MIN_VALUE || clip.endMs <= clip.startMs) return v0
        val t0 = fx.values.getOrNull(8) ?: 0f
        val t1 = fx.values.getOrNull(10) ?: 1f
        val v1 = fx.values.getOrNull(11) ?: v0
        val u = ((timeMs - clip.startMs).toFloat() / (clip.endMs - clip.startMs)).coerceIn(0f, 1f)
        val span = t1 - t0
        val f = if (span > 0.0001f) ((u - t0) / span).coerceIn(0f, 1f) else if (u < t0) 0f else 1f
        return v0 + (v1 - v0) * f
    }

    /**
     * The alternating copies' fill colour the importer stored at values [at] (a [packColor] pair
     * plus a flag at [at] + 2), or null when copies keep the layer's own colour.
     */
    private fun altColorOf(fx: ClipFx, at: Int): Long? {
        if ((fx.values.getOrNull(at + 2) ?: 0f) == 0f) return null
        return unpackColor(fx.values.getOrNull(at) ?: 0f, fx.values.getOrNull(at + 1) ?: 0f)
    }

    /** A SplitMix-style integer hash to a uniform float in [0, 1): stable across runs and platforms. */
    private fun hash01(seed: Int, index: Int, salt: Int): Float {
        var z = seed.toLong() * -7046029254386353131L +
            index.toLong() * -4658895280553007687L + salt.toLong() * -7723592293113298319L
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293113298319L
        z = z xor (z ushr 31)
        return ((z ushr 40) and 0xFFFFFFL).toFloat() / 16777216f
    }
}
