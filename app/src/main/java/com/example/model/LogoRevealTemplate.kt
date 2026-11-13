package com.example.model

/**
 * The "Logo Reveal" template (Templates programme, logo-animation template #1): a logo
 * badge pops in at the top, the brand name rises in under it, and the composition then
 * HOLDS — a logo reveal is an end card, so there is deliberately no exit: the tail is
 * static and the user extends the group for as long as the reveal should stay on screen.
 *
 * Like the Carousel and the Pop Title, a template lives inside the project: inserting it
 * drops ONE Group layer into the current timeline at the playhead (see [buildGroup]) and
 * the user customises it in place with the ordinary tools. Like the Pop Title there is no
 * fixed internal scene and no rig — the composition is authored directly against the live
 * preview size, every size a fraction of the preview width (the text engine's font is 11%
 * of the frame width, so width is the unit that keeps the design proportional on any
 * canvas), and the group itself stays at the REST pose: moving / scaling / rotating the
 * one group layer moves the whole reveal. Per Sarowar's principle ("যেটার কাজ যেখানে
 * সেটা সেখানে রাখবে") only what has a job is kept — no Controller, no Null: the logo slot
 * shows the brand mark, the text layer names it, the keyframes perform.
 *
 * The slots (both ordinary layers):
 *  - the LOGO: a ROUNDED_RECT shape layer with an image fill (FILL_IMAGE / IMG_FILL) fed
 *    by a picture generated at run time (see
 *    [com.example.engine.TemplateAssets.logoBitmap] — abstract art of our own, never a
 *    real brand). It is a slot exactly like a Carousel card: tap it, then
 *    Color → Fill → Image and pick the real logo.
 *  - the BRAND NAME: a PLAIN text layer under the logo — the sample ("আপনার ব্র্যান্ড") is
 *    a normal [TimelineClip.text], so the app's existing Bengali shaping applies
 *    unchanged, and the animation rides on the layer's own keyframes: retyping the text
 *    keeps the animation working, because nothing about it depends on the words.
 *
 * The performance (3 s, all ordinary keyframes with the app's own easing presets):
 *  - logo, 0..500 ms: SCALE from 60% of its rest size to rest on the "Back out" preset
 *    (the curve overshoots to ~102% mid-flight and settles — the pop) and OPACITY
 *    0 -> 100 (Cubic out);
 *  - brand text, 350..800 ms: POSITION rises from [LogoRevealSpec.TEXT_RISE_FRAC] of the
 *    width below its rest place (Cubic out) as OPACITY fades 0 -> 100 (Cubic out) — it
 *    starts just as the logo is landing and settles just after;
 *  - 800..3000 ms: hold — every track is at its rest value, nothing moves, so stretching
 *    the group longer just holds the end card.
 * The logo's stored size uses the app's shape convention (the 130 dp box measured against
 * a 360 dp-wide canvas: box = previewWidth x 130/360 in stored units, the importer's own
 * basis), and its SCALE keyframes carry absolute scale percents against that box with a
 * zero height share — the uniform meaning, so the badge grows evenly on both axes.
 */
object LogoRevealSpec {
    const val DURATION_MS = 3000L
    /** The logo's pop (scale + fade) runs inside this window. */
    const val LOGO_ENTER_MS = 500L
    /** The brand text starts rising once the logo is landing… */
    const val TEXT_START_MS = 350L
    /** …and has fully settled by here; everything after is the static hold. */
    const val TEXT_END_MS = 800L

    /** The sample brand name. A normal text clip's text: retype it and the animation follows. */
    const val SAMPLE_TEXT = "আপনার ব্র্যান্ড"

    /** The logo badge's side, as a fraction of preview width (square). */
    const val LOGO_SIDE_FRAC = 0.28f
    /** The badge's centre sits this far above the canvas centre, as a fraction of width. */
    const val LOGO_Y_FRAC = -0.10f
    /** Badge corner radius as stored: 65 x (radius / half side) = 65 x 0.4 — the radius is
     *  40% of the half side (20% of the side), the classic rounded-badge look, in the same
     *  convention the Carousel's cards use. */
    const val LOGO_CORNER = 26f
    /** The logo's pop starts at this share of its rest scale. */
    const val ENTER_SCALE = 60f

    /** The brand text's centre sits this far below the canvas centre, as a fraction of width. */
    const val TEXT_Y_FRAC = 0.13f
    /** The text's entrance rise: how far below its rest place it starts, fraction of width. */
    const val TEXT_RISE_FRAC = 0.03f

    /** The app shape box in stored units is previewWidth x 130 / 360 (importer's basis). */
    const val BOX_NUM = 130f
    const val BOX_DEN = 360f

    // The app's own easing presets (Easing.PRESETS), by name.
    val EASE_BACK_OUT = Easing(0.175f, 0.885f, 0.32f, 1.2f)     // "Back out"
    val EASE_CUBIC_OUT = Easing(0.215f, 0.61f, 0.355f, 1f)      // "Cubic out"
}

object LogoRevealTemplate {

    /** What [buildGroup] hands back: the Group layer, its two children, and the lanes to add. */
    data class Insert(
        val group: TimelineClip,
        /** The logo slot: a shape layer whose image fill the user replaces with their logo. */
        val logoLayer: TimelineClip,
        /** The brand name: one ordinary TEXT layer carrying its own entrance. */
        val textLayer: TimelineClip,
        /** The layers inside the group in lane order (top lane first): the logo, then the name. */
        val children: List<TimelineClip>,
        /** Lanes to place on top of the project, top lane first: the group, then [children]. */
        val lanesTopFirst: List<TimelineClip>
    )

    /**
     * The template as ONE Group layer + its children, ready to insert into the project that
     * is open. [previewWidthPx] / [previewHeightPx] are the editor preview frame's size
     * (the stored-units basis, like the importer's); [logoUri] is the generated placeholder
     * picture for the logo slot; the group starts at [insertAtMs] (the playhead) and spans
     * [LogoRevealSpec.DURATION_MS]. Every call builds fresh clips under [idBase] —
     * inserting twice gives two independent reveals.
     *
     * The group stays at the REST pose and the children link to it with the REST reference
     * pose (the Carousel's convention): the group's own transform is then a pure user
     * control on top of the authored design, and ParentRig composes it onto both children.
     */
    fun buildGroup(
        previewWidthPx: Float,
        previewHeightPx: Float,
        logoUri: String,
        insertAtMs: Long,
        idBase: String = "logoreveal"
    ): Insert {
        val pw = if (previewWidthPx > 1f) previewWidthPx else 1080f
        // Sizes ride on the width (the text engine's font does too); the design is
        // centre-anchored, so any aspect keeps it in frame (pinned for 16:9 and 9:16).
        // [previewHeightPx] is accepted for symmetry with the other templates' insert API.
        val box = pw * LogoRevealSpec.BOX_NUM / LogoRevealSpec.BOX_DEN

        val groupId = "$idBase-group"
        val link = GroupOps.linkTo(groupId, GroupOps.Pose.REST)
        val group = TimelineClip(
            id = groupId,
            name = "Logo Reveal",
            type = LayerType.GROUP,
            startMs = insertAtMs,
            durationMs = LogoRevealSpec.DURATION_MS,
            color = 0xFF7E57C2L
        )

        // The logo slot: a square ROUNDED_RECT badge in the app's shape convention, its
        // image fill fed by the generated placeholder. The SCALE keys are absolute scale
        // percents against the box with a zero height share (the uniform meaning): the pop
        // runs from ENTER_SCALE % of the rest size to the rest size itself, so the settled
        // evaluation lands exactly on the stored pose.
        val logoScale = LogoRevealSpec.LOGO_SIDE_FRAC * pw / box   // shapeScale units
        val restPct = logoScale * 100f
        val logo = TimelineClip(
            id = "$idBase-logo",
            name = "Logo",
            type = LayerType.SHAPE,
            startMs = insertAtMs,
            durationMs = LogoRevealSpec.DURATION_MS,
            color = 0xFF5E35B1L,
            fillMode = FILL_IMAGE,
            fillImageUri = logoUri,
            fillImageMode = IMG_FILL,
            shapePattern = "ROUNDED_RECT",
            shapeCornerRadius = LogoRevealSpec.LOGO_CORNER,
            shapeScaleX = logoScale,
            shapeScaleY = logoScale,
            scalePercent = restPct,
            transformY = LogoRevealSpec.LOGO_Y_FRAC * pw,
            keyframes = mapOf(
                AnimProp.SCALE to listOf(
                    Keyframe(
                        0L, LogoRevealSpec.ENTER_SCALE / 100f * restPct, 0f,
                        LogoRevealSpec.EASE_BACK_OUT
                    ),
                    Keyframe(LogoRevealSpec.LOGO_ENTER_MS, restPct, 0f)
                ),
                AnimProp.OPACITY to listOf(
                    Keyframe(0L, 0f, 0f, LogoRevealSpec.EASE_CUBIC_OUT),
                    Keyframe(LogoRevealSpec.LOGO_ENTER_MS, 100f, 0f)
                )
            ),
            groupId = groupId,
            parent = link
        )

        // The brand name: a plain text layer under the logo. Its POSITION keys carry
        // absolute places (the rest place is TEXT_Y_FRAC below centre, not zero): before
        // TEXT_START_MS the evaluator holds the first key, so the layer waits invisible
        // at its risen-from place until its entrance begins.
        val textY = LogoRevealSpec.TEXT_Y_FRAC * pw
        val textRise = LogoRevealSpec.TEXT_RISE_FRAC * pw
        val text = TimelineClip(
            id = "$idBase-text",
            name = "Brand Name",
            type = LayerType.TEXT,
            startMs = insertAtMs,
            durationMs = LogoRevealSpec.DURATION_MS,
            color = 0xFF3992CEL,
            text = LogoRevealSpec.SAMPLE_TEXT,
            textColor = 0xFFFFFFFFL,
            fontWeight = 700,
            transformY = textY,
            keyframes = mapOf(
                AnimProp.POSITION to listOf(
                    Keyframe(LogoRevealSpec.TEXT_START_MS, 0f, textY + textRise, LogoRevealSpec.EASE_CUBIC_OUT),
                    Keyframe(LogoRevealSpec.TEXT_END_MS, 0f, textY)
                ),
                AnimProp.OPACITY to listOf(
                    Keyframe(LogoRevealSpec.TEXT_START_MS, 0f, 0f, LogoRevealSpec.EASE_CUBIC_OUT),
                    Keyframe(LogoRevealSpec.TEXT_END_MS, 100f, 0f)
                )
            ),
            groupId = groupId,
            parent = link
        )

        // Lane order (top lane first): the logo's lane above the name's (last lane =
        // painted first, so the name is drawn first and the badge over it — they never
        // overlap geometrically, but the badge is the star and keeps the top lane).
        val children = listOf(logo, text)
        val lanesTopFirst = listOf(group) + children
        return Insert(group, logo, text, children, lanesTopFirst)
    }
}
