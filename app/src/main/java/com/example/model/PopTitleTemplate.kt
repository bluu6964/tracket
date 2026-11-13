package com.example.model

/**
 * The "Pop Title" template (Templates programme, text-animation template #1): a short title
 * that pops in, holds, and leaves — ONE ordinary TEXT layer doing the whole performance,
 * with a thin accent bar under it as the single supporting element.
 *
 * Like the Carousel (build 143's model), a template lives inside the project: inserting it
 * drops ONE Group layer into the current timeline at the playhead (see [buildGroup]) and
 * the user customises it in place with the ordinary tools. Unlike the Carousel there is no
 * fixed internal scene and no rig: the title is authored directly against the live preview
 * size, the way [com.example.viewmodel.VideoEditorViewModel.addCaptionLayers] authors
 * captions — every size is a fraction of the preview width (the text engine's font is
 * 11% of the frame width, so width is the unit that keeps the design proportional on any
 * canvas), the text sits on the canvas centre, and the group itself stays at the REST
 * pose: moving / scaling / rotating the one group layer moves the whole title.
 *
 * The star is a PLAIN text layer: the sample text ("আপনার লেখা") is a normal [TimelineClip.text],
 * so the app's existing Bengali shaping applies unchanged, and the animation rides on the
 * layer's own POSITION / SCALE / OPACITY keyframes — retyping the text keeps the animation
 * working, because nothing about it depends on the words. No per-letter splitting, no
 * Controller, no Null: per Sarowar's principle ("যেটার কাজ যেখানে সেটা সেখানে রাখবে") the
 * only elements kept are the ones with a job — the text performs, the bar underlines.
 *
 * The performance (3 s, all ordinary keyframes with the app's own easing presets):
 *  - entrance, 0..600 ms: the text rises from [PopTitleSpec.RISE_FRAC] of the width below
 *    centre (Cubic out), fades 0 -> 100 (Cubic out) and scales 80% -> 100% on the
 *    "Back out" preset, whose curve overshoots to ~101% mid-flight and settles — the pop;
 *  - hold, 600..2600 ms: rest pose, fully visible;
 *  - exit, 2600..3000 ms: fade 100 -> 0 and a shorter rise up (Cubic in), ending clean
 *    (opacity exactly 0 at the layer's last instant).
 * The accent bar is a stadium-ended ROUNDED_RECT shape in a warm amber: its SCALE track
 * grows its width 0 -> full over the same entrance (its height never changes), and it
 * fades out with the text over the exit. Its stored size uses the app's shape convention
 * (the 130 dp box measured against a 360 dp-wide canvas: box = previewWidth x 130/360 in
 * stored units, the importer's own basis), so its SCALE keyframes drive shapeScaleX/Y and
 * scalePercent together exactly as the Transform panel's do.
 */
object PopTitleSpec {
    const val DURATION_MS = 3000L
    /** Entrance length: rise + fade + pop, all inside this window. */
    const val ENTER_MS = 600L
    /** The exit (fade + rise) runs from here to [DURATION_MS] — 400 ms. */
    const val EXIT_START_MS = 2600L

    /** The sample title. A normal text clip's text: retype it and the animation follows. */
    const val SAMPLE_TEXT = "আপনার লেখা"

    /** Rest scale of the text layer (the app's plain text size: font = 11% of frame width). */
    const val TEXT_SCALE = 100f
    /** The entrance starts at this scale and pops to [TEXT_SCALE]. */
    const val ENTER_SCALE = 80f
    /** Entrance rise: how far below centre the text starts, as a fraction of preview width. */
    const val RISE_FRAC = 0.035f
    /** Exit rise: how far above centre the text ends, as a fraction of preview width. */
    const val EXIT_RISE_FRAC = 0.025f

    /** The accent bar's colour (a warm amber; the text layer's Fill colour is its own white). */
    val ACCENT: Long = 0xFFFFB300L
    /** Bar width / height / centre drop below the canvas centre, as fractions of preview width. */
    const val BAR_W_FRAC = 0.30f
    const val BAR_H_FRAC = 0.011f
    const val BAR_Y_FRAC = 0.105f

    /** The app shape box in stored units is previewWidth x 130 / 360 (importer's basis). */
    const val BOX_NUM = 130f
    const val BOX_DEN = 360f

    // The app's own easing presets (Easing.PRESETS), by name.
    val EASE_BACK_OUT = Easing(0.175f, 0.885f, 0.32f, 1.2f)     // "Back out"
    val EASE_CUBIC_OUT = Easing(0.215f, 0.61f, 0.355f, 1f)      // "Cubic out"
    val EASE_CUBIC_IN = Easing(0.55f, 0.055f, 0.675f, 0.19f)    // "Cubic in"
}

object PopTitleTemplate {

    /** What [buildGroup] hands back: the Group layer, its two children, and the lanes to add. */
    data class Insert(
        val group: TimelineClip,
        /** The star: one ordinary TEXT layer carrying the whole title animation. */
        val textLayer: TimelineClip,
        /** The supporting element: the accent bar shape under the text. */
        val bar: TimelineClip,
        /** The layers inside the group in lane order (top lane first): the text, then the bar. */
        val children: List<TimelineClip>,
        /** Lanes to place on top of the project, top lane first: the group, then [children]. */
        val lanesTopFirst: List<TimelineClip>
    )

    /**
     * The template as ONE Group layer + its children, ready to insert into the project that
     * is open. [previewWidthPx] / [previewHeightPx] are the editor preview frame's size
     * (the stored-units basis, like the importer's); the group starts at [insertAtMs] (the
     * playhead) and spans [PopTitleSpec.DURATION_MS]. Every call builds fresh clips under
     * [idBase] — inserting twice gives two independent titles.
     *
     * The group stays at the REST pose and the children link to it with the REST reference
     * pose (the Carousel's convention): the group's own transform is then a pure user
     * control on top of the authored design, and ParentRig composes it onto both children.
     */
    fun buildGroup(
        previewWidthPx: Float,
        previewHeightPx: Float,
        insertAtMs: Long,
        idBase: String = "poptitle"
    ): Insert {
        val pw = if (previewWidthPx > 1f) previewWidthPx else 1080f
        // Sizes ride on the width (the text engine's font does too); the design is
        // centre-anchored, so any aspect keeps it in frame (pinned for 16:9 and 9:16).
        // [previewHeightPx] is accepted for symmetry with the Carousel's insert API.
        val box = pw * PopTitleSpec.BOX_NUM / PopTitleSpec.BOX_DEN

        val groupId = "$idBase-group"
        val link = GroupOps.linkTo(groupId, GroupOps.Pose.REST)
        val group = TimelineClip(
            id = groupId,
            name = "Pop Title",
            type = LayerType.GROUP,
            startMs = insertAtMs,
            durationMs = PopTitleSpec.DURATION_MS,
            color = 0xFF5BB85DL
        )

        val rise = PopTitleSpec.RISE_FRAC * pw
        val exitRise = PopTitleSpec.EXIT_RISE_FRAC * pw
        val text = TimelineClip(
            id = "$idBase-text",
            name = "Title Text",
            type = LayerType.TEXT,
            startMs = insertAtMs,
            durationMs = PopTitleSpec.DURATION_MS,
            color = 0xFF3992CEL,
            text = PopTitleSpec.SAMPLE_TEXT,
            textColor = 0xFFFFFFFFL,
            fontWeight = 700,
            keyframes = mapOf(
                AnimProp.POSITION to listOf(
                    Keyframe(0L, 0f, rise, PopTitleSpec.EASE_CUBIC_OUT),
                    Keyframe(PopTitleSpec.ENTER_MS, 0f, 0f),
                    Keyframe(PopTitleSpec.EXIT_START_MS, 0f, 0f, PopTitleSpec.EASE_CUBIC_IN),
                    Keyframe(PopTitleSpec.DURATION_MS, 0f, -exitRise)
                ),
                AnimProp.SCALE to listOf(
                    Keyframe(0L, PopTitleSpec.ENTER_SCALE, 0f, PopTitleSpec.EASE_BACK_OUT),
                    Keyframe(PopTitleSpec.ENTER_MS, PopTitleSpec.TEXT_SCALE, 0f)
                ),
                AnimProp.OPACITY to listOf(
                    Keyframe(0L, 0f, 0f, PopTitleSpec.EASE_CUBIC_OUT),
                    Keyframe(PopTitleSpec.ENTER_MS, 100f, 0f),
                    Keyframe(PopTitleSpec.EXIT_START_MS, 100f, 0f, PopTitleSpec.EASE_CUBIC_IN),
                    Keyframe(PopTitleSpec.DURATION_MS, 0f, 0f)
                )
            ),
            groupId = groupId,
            parent = link
        )

        // The accent bar: full width [BAR_W_FRAC] of the frame, [BAR_H_FRAC] tall, centred
        // [BAR_Y_FRAC] below the canvas centre (just under the text's line box). Its SCALE
        // track is (width %, height %) in the shape convention: the height share is the
        // same at both keys, so only the width grows — the bar sweeps out from the centre.
        val barScaleX = PopTitleSpec.BAR_W_FRAC * pw / box * 100f
        val barScaleY = PopTitleSpec.BAR_H_FRAC * pw / box * 100f
        val bar = TimelineClip(
            id = "$idBase-bar",
            name = "Accent Bar",
            type = LayerType.SHAPE,
            startMs = insertAtMs,
            durationMs = PopTitleSpec.DURATION_MS,
            color = PopTitleSpec.ACCENT,
            fillMode = FILL_SOLID,
            textColor = PopTitleSpec.ACCENT,
            shapePattern = "ROUNDED_RECT",
            shapeCornerRadius = 65f,   // rho = 1 on the smaller half extent: stadium ends
            shapeScaleX = barScaleX / 100f,
            shapeScaleY = barScaleY / 100f,
            scalePercent = barScaleX,
            transformY = PopTitleSpec.BAR_Y_FRAC * pw,
            keyframes = mapOf(
                AnimProp.SCALE to listOf(
                    Keyframe(0L, 0f, barScaleY, PopTitleSpec.EASE_CUBIC_OUT),
                    Keyframe(PopTitleSpec.ENTER_MS, barScaleX, barScaleY)
                ),
                AnimProp.OPACITY to listOf(
                    Keyframe(0L, 100f, 0f),
                    Keyframe(PopTitleSpec.EXIT_START_MS, 100f, 0f, PopTitleSpec.EASE_CUBIC_IN),
                    Keyframe(PopTitleSpec.DURATION_MS, 0f, 0f)
                )
            ),
            groupId = groupId,
            parent = link
        )

        // Lane order (top lane first): the text's lane above the bar's (last lane = painted
        // first, so the bar is drawn first and the text over it — and on screen the bar
        // sits below the words, geometrically and in z).
        val children = listOf(text, bar)
        val lanesTopFirst = listOf(group) + children
        return Insert(group, text, bar, children, lanesTopFirst)
    }
}
