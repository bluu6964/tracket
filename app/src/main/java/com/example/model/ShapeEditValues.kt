package com.example.model

/**
 * Value rules for the shape editor's sliders for the fields the Alight Motion import added
 * (corner radius, stroke trim) — pure Kotlin, so the standalone harness can test them.
 *
 * The editor shows corner radius in dp inside the 130 dp shape box (the same unit the model
 * field [TimelineClip.shapeCornerRadius] uses) and the trim window as 0..100 % of the outline
 * length (the model stores it as 0..1 fractions in [TimelineClip.strokeTrimStart]/[strokeTrimEnd]).
 * The sliders clamp against each other (start never passes end); these helpers are the safety net
 * for anything that writes the fields directly, e.g. [com.example.viewmodel.VideoEditorViewModel.addClip].
 */
object ShapeEditValues {

    /** Corner radius lives in dp inside the 130 dp shape box, so half the box is the max. */
    const val MAX_CORNER_RADIUS_DP = 65f

    /** Corner radius, clamped to the shape box (0 = square corners). */
    fun coerceCornerRadiusDp(dp: Float): Float = dp.coerceIn(0f, MAX_CORNER_RADIUS_DP)

    /**
     * Trim window as fractions of the outline length: both clamped to 0..1 and ordered, so a start
     * dragged past the end pulls the end along instead of producing an inverted window. An empty
     * window (start == end) draws no stroke, exactly like the renderers treat it.
     */
    fun coerceTrim(start: Float, end: Float): Pair<Float, Float> {
        val s = start.coerceIn(0f, 1f)
        val e = end.coerceIn(s, 1f)
        return s to e
    }

    /**
     * True when the shape's corner radius is keyframed (build 173): the shape editor's slider
     * must not silently mislead — the build-86 text-reveal decision, same class: while the
     * radius is animated the slider stays hidden and the sheet says so, with a chip that
     * replaces the animation by a static radius frozen at the playhead (see
     * [staticCornerRadiusAt]).
     */
    fun isCornerRadiusKeyframed(clip: TimelineClip): Boolean =
        clip.keyframes[AnimProp.SHAPE_CORNER_RADIUS].orEmpty().isNotEmpty()

    /** The corner radius as it looks at [playheadMs]: the value a "Use static radius" freeze keeps. */
    fun staticCornerRadiusAt(clip: TimelineClip, playheadMs: Long): Float =
        clip.at(playheadMs).shapeCornerRadius
}
