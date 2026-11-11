package com.example.engine

import com.example.model.ClipFx
import com.example.model.ClipFxLibrary
import com.example.model.TimelineClip
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Alight Motion `repeat` — the plain linear echo: the layer drawn once per copy, each copy moved
 * by the offset, turned by the angle, scaled and faded. Pure Kotlin (no Android classes) so it can
 * be unit tested; the C++ side needs no change (the unresolved type 56 is skipped by fxParseOps:
 * it sits above FX_MAX, like Motion Blur).
 *
 * What Alight stores (all 3 plain-repeat instances in the corpus — on the "Horizontal Lines" /
 * "Vertical Lines" shapes — have angle 0, scale 1, alpha 1, time 0, so only count + offset is
 * pinned by real files; the rest is the plain reading of the parameter names, to check on the
 * phone comparison):
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
 * [undoRotationDeg] is for a layer drawn into its own upright picture (a pose / camera layer): the
 * renderer turns that picture by the layer's own rotation when it places it, so the copies are
 * placed by the offset undone by that turn — pass the rendered layer's rotationDeg there, 0
 * otherwise. It is exact for a centred pivot (the usual case); an off-centre pivot leaves the
 * copies shifted by the pivot error, which the phone comparison can measure.
 *
 * Known limits (same family as Motion Blur): parent motion is already in the rendered clip the
 * copies are made from, so a repeated group member moves with its group; the copies are drawn
 * into the layer's own picture, so a layer with Repeat always gets one (see `hasEffects`).
 */
internal object RepeatEval {

    /** Largest copy count drawn: Alight allows hundreds, which would stall a frame. */
    const val MAX_COUNT = 50

    /** The layer's enabled Repeat effect, or null. */
    fun repeatOf(clip: TimelineClip): ClipFx? =
        clip.effects.firstOrNull { it.enabled && it.type == ClipFxLibrary.REPEAT }

    /** Copy count of a Repeat effect: values[0], 1..[MAX_COUNT]. */
    fun countOf(fx: ClipFx): Int = (fx.values.getOrNull(0) ?: 1f).roundToInt().coerceIn(1, MAX_COUNT)

    /**
     * The layer drawn once per copy (the first entry is always [clip] itself). No Repeat effect,
     * a disabled one, or a count of 1 all return just the layer.
     */
    fun copiesOf(clip: TimelineClip, undoRotationDeg: Float = 0f): List<TimelineClip> {
        val fx = repeatOf(clip) ?: return listOf(clip)
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
}
