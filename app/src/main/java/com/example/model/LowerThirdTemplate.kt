package com.example.model

/**
 * The "Lower Third" template (Templates programme): the classic name strap — an amber
 * accent bar at the bottom left, the person's NAME sliding in beside it, and a smaller
 * subtitle line ("আপনার পরিচিতি" — role / title) staging in just after; the whole strap
 * holds, then slides and fades out together. This is the design banked in the 2026-11-13
 * Lower Third STOP note (CYCLE-LOG), built exactly as specified there.
 *
 * Like the other templates it lives inside the project: inserting it drops ONE Group
 * layer into the current timeline at the playhead (see [buildGroup]) and the user
 * customises it in place. No fixed internal scene and no rig — per Sarowar's principle
 * ("যেটার কাজ যেখানে সেটা সেখানে রাখবে") only what has a job is kept: the bar marks the
 * strap, the two plain text layers carry the words, the keyframes perform.
 *
 * THE ANCHOR LAW (from the STOP note): the strap is anchored to the frame's bottom-left,
 * not to the centre — the block's left edge sits [LowerThirdSpec.LEFT_FRAC] of the width
 * in from the frame's left edge, and the block's bottom edge sits
 * [LowerThirdSpec.BOTTOM_FRAC] of the height up from the frame's bottom. The vertical
 * layout rides on the width (the text engine's font is 11% of the frame width): the name
 * line is one font tall, the subtitle line is 58% of that, a small gap between them, and
 * the accent bar spans exactly the two lines (its top is the name line's top, its bottom
 * is the block's bottom). Horizontally the bar stands at the block's left edge and the
 * texts start after it plus a gap.
 *
 * THE CENTRE LAW: both text painters draw a layer's line centred on the layer's own
 * position (the layout box hugs the line and centres on the position — see LayerPainter /
 * NativeTextPainter), scaled by the layer's scalePercent — so a left-aligned strap is
 * placed by the texts' CENTRES, and the centres come from the words' measured advances,
 * the same quantities the text engine sums when it lays out a line. The pure-Kotlin
 * builder has no font, so the sample advances were measured once with HarfBuzz over Noto
 * Sans Bengali Bold (upem 1000 — Android's Bengali fallback family, at the layers' weight
 * 700; the same measurement the Word Pop pins rest on, cross-checked: its words reproduce
 * exactly — আপনার 3.596, নতুন 2.000, লেখা 2.228, space 0.260, sentence 8.344 em):
 * "আপনার নাম" = 5.439 em, "আপনার পরিচিতি" = 7.487 em. A text's left edge is therefore
 * centre − advance/2 exactly, both texts share the same left edge, and retyping a line
 * keeps its authored centre (the animation never depends on the words) — the user nudges
 * a much longer name the same way they would in any editor.
 *
 * The performance (4 s, all ordinary keyframes with the app's own easing presets):
 *  - the accent bar grows first, 0..250 ms: its SCALE height share runs from a hair to
 *    full (Cubic out) with its width share constant — a vertical amber strip growing
 *    beside where the words will land. (The grow starts from 0.1% of the shape box, not
 *    0: on a shape's SCALE track a zero height share is the uniform-scale meaning, so
 *    0.1% is the smallest true separate height — sub-pixel, invisible.);
 *  - the name stages in at 150..550 ms and the subtitle at 300..700 ms: each slides in
 *    from [LowerThirdSpec.SLIDE_FRAC] of the width to the left of its place while fading
 *    0 -> 100 (Cubic out). Before its turn a line waits invisible at its start pose (the
 *    evaluator holds its first key, the Logo Reveal convention);
 *  - hold, 700..3500 ms: everything at rest, fully visible;
 *  - the exit is SHARED, 3500..4000 ms: all three layers slide back left by the same
 *    distance and fade 100 -> 0 on the identical Cubic-in track, ending at exactly
 *    opacity 0 at the template's last instant.
 * The name is a plain text layer at scale 100; the subtitle is a plain text layer at a
 * STATIC scalePercent of 58 (the captions convention — a smaller line under the name, no
 * SCALE keyframes). Neither text has animIn/animOut, effects, masks or animators.
 */
object LowerThirdSpec {
    const val DURATION_MS = 4000L
    /** The shared exit (slide back + fade) runs from here to [DURATION_MS] — 500 ms. */
    const val EXIT_START_MS = 3500L

    /** The accent bar's grow window: it is full height by here. */
    const val BAR_GROW_END_MS = 250L
    /** The name's staging window (slide-from-left + fade). */
    const val NAME_START_MS = 150L
    const val NAME_END_MS = 550L
    /** The subtitle's staging window — starts after the name's, lands after it. */
    const val SUB_START_MS = 300L
    const val SUB_END_MS = 700L

    /** The sample name. A normal text clip's text: retype it and the animation follows. */
    const val NAME_TEXT = "আপনার নাম"
    /** The sample subtitle (role / title line under the name). */
    const val SUBTITLE_TEXT = "আপনার পরিচিতি"

    /**
     * The samples' advances in ems of the layout font, measured with HarfBuzz over Noto
     * Sans Bengali Bold (upem 1000) — see the centre law in the file header.
     */
    const val NAME_ADV_EM = 5.439f
    const val SUBTITLE_ADV_EM = 7.487f
    /** The text engine's layout font, as a fraction of the frame width. */
    const val FONT_FRAC = 0.11f

    /** Rest scale of the name (the app's plain text size). */
    const val NAME_SCALE = 100f
    /** Rest scale of the subtitle — static, the captions convention for a smaller line. */
    const val SUBTITLE_SCALE = 58f

    /** The accent bar's colour (the same warm amber as the Pop Title's bar). */
    val ACCENT: Long = 0xFFFFB300L

    /** Anchor law: the block's left edge, as a fraction of the frame width from the left. */
    const val LEFT_FRAC = 0.055f
    /** Anchor law: the block's bottom edge, as a fraction of the frame height from the bottom. */
    const val BOTTOM_FRAC = 0.065f
    /** The accent bar's width, as a fraction of the frame width. */
    const val BAR_W_FRAC = 0.013f
    /** The horizontal gap between the bar and the texts, as a fraction of the width. */
    const val TEXT_GAP_FRAC = 0.018f
    /** The vertical gap between the name line and the subtitle line, fraction of width. */
    const val LINE_GAP_FRAC = 0.016f
    /** Entrance/exit slide distance, as a fraction of the width (from / back to the left). */
    const val SLIDE_FRAC = 0.045f
    /**
     * The bar grow's first height share, in SCALE-track percent of the shape box: the
     * smallest separate height (a zero share would mean "uniform — follow the width").
     */
    const val GROW_START_PCT = 0.1f

    /** The app shape box in stored units is previewWidth x 130 / 360 (importer's basis). */
    const val BOX_NUM = 130f
    const val BOX_DEN = 360f

    /** The layout font in stored px on a canvas [pw] wide. */
    fun fontPx(pw: Float): Float = FONT_FRAC * pw
    /** The name line's height in stored px (one font at scale 100). */
    fun nameLineHPx(pw: Float): Float = fontPx(pw) * NAME_SCALE / 100f
    /** The subtitle line's height in stored px (one font at its static 58%). */
    fun subtitleLineHPx(pw: Float): Float = fontPx(pw) * SUBTITLE_SCALE / 100f
    /** The accent bar's height: it spans exactly the two lines and the gap between them. */
    fun barHeightPx(pw: Float): Float =
        nameLineHPx(pw) + LINE_GAP_FRAC * pw + subtitleLineHPx(pw)

    /** The block's bottom edge in stored px from the canvas centre (+y is down). */
    fun blockBottomPx(ph: Float): Float = ph / 2f - BOTTOM_FRAC * ph
    /** The block's left edge in stored px from the canvas centre. */
    fun leftEdgePx(pw: Float): Float = -pw / 2f + LEFT_FRAC * pw
    /** The texts' shared left edge: the bar and the gap sit between it and the block edge. */
    fun textLeftPx(pw: Float): Float = leftEdgePx(pw) + BAR_W_FRAC * pw + TEXT_GAP_FRAC * pw

    /** The accent bar's centre: at the block's left edge, spanning the two lines. */
    fun barCentreXPx(pw: Float): Float = leftEdgePx(pw) + BAR_W_FRAC * pw / 2f
    fun barCentreYPx(pw: Float, ph: Float): Float = blockBottomPx(ph) - barHeightPx(pw) / 2f

    /** The name's centre: its left edge is [textLeftPx], its line tops the block. */
    fun nameCentreXPx(pw: Float): Float =
        textLeftPx(pw) + NAME_ADV_EM * fontPx(pw) * NAME_SCALE / 100f / 2f
    fun nameCentreYPx(pw: Float, ph: Float): Float =
        blockBottomPx(ph) - subtitleLineHPx(pw) - LINE_GAP_FRAC * pw - nameLineHPx(pw) / 2f

    /** The subtitle's centre: same left edge as the name; its line bottoms the block. */
    fun subtitleCentreXPx(pw: Float): Float =
        textLeftPx(pw) + SUBTITLE_ADV_EM * fontPx(pw) * SUBTITLE_SCALE / 100f / 2f
    fun subtitleCentreYPx(pw: Float, ph: Float): Float =
        blockBottomPx(ph) - subtitleLineHPx(pw) / 2f

    /** The slide distance in stored px. */
    fun slidePx(pw: Float): Float = SLIDE_FRAC * pw

    // The app's own easing presets (Easing.PRESETS), by name.
    val EASE_CUBIC_OUT = Easing(0.215f, 0.61f, 0.355f, 1f)      // "Cubic out"
    val EASE_CUBIC_IN = Easing(0.55f, 0.055f, 0.675f, 0.19f)    // "Cubic in"
}

object LowerThirdTemplate {

    /** What [buildGroup] hands back: the Group layer, its three children, and the lanes. */
    data class Insert(
        val group: TimelineClip,
        /** The name: one ordinary TEXT layer at scale 100, staging in at 150..550 ms. */
        val nameLayer: TimelineClip,
        /** The subtitle: one ordinary TEXT layer at a static 58%, staging in at 300..700 ms. */
        val subtitleLayer: TimelineClip,
        /** The accent bar: a vertical amber ROUNDED_RECT strip growing 0..250 ms. */
        val bar: TimelineClip,
        /** The layers inside the group in lane order (top lane first): name, subtitle, bar. */
        val children: List<TimelineClip>,
        /** Lanes to place on top of the project, top lane first: the group, then [children]. */
        val lanesTopFirst: List<TimelineClip>
    )

    /**
     * The template as ONE Group layer + its children, ready to insert into the project
     * that is open. [previewWidthPx] / [previewHeightPx] are the editor preview frame's
     * size (the stored-units basis, like the importer's); the group starts at
     * [insertAtMs] (the playhead) and spans [LowerThirdSpec.DURATION_MS]. Every call
     * builds fresh clips under [idBase] — inserting twice gives two independent straps.
     *
     * The group stays at the REST pose and the children link to it with the REST
     * reference pose (the Carousel's convention): the group's own transform is then a
     * pure user control on top of the authored design, and ParentRig composes it onto
     * every child.
     */
    fun buildGroup(
        previewWidthPx: Float,
        previewHeightPx: Float,
        insertAtMs: Long,
        idBase: String = "lowerthird"
    ): Insert {
        val pw = if (previewWidthPx > 1f) previewWidthPx else 1080f
        val ph = if (previewHeightPx > 1f) previewHeightPx else 1080f
        val box = pw * LowerThirdSpec.BOX_NUM / LowerThirdSpec.BOX_DEN
        val slide = LowerThirdSpec.slidePx(pw)

        val groupId = "$idBase-group"
        val link = GroupOps.linkTo(groupId, GroupOps.Pose.REST)
        val group = TimelineClip(
            id = groupId,
            name = "Lower Third",
            type = LayerType.GROUP,
            startMs = insertAtMs,
            durationMs = LowerThirdSpec.DURATION_MS,
            color = 0xFF00695CL
        )

        // One staged text line: waits invisible at its slid-left pose, slides + fades in
        // over [startMs]..[endMs], holds, then rides the shared exit (slide back left +
        // fade on the same Cubic-in track as every other child) to exactly opacity 0.
        fun stagedText(
            id: String, name: String, text: String, scale: Float,
            centreX: Float, centreY: Float, startMs: Long, endMs: Long
        ) = TimelineClip(
            id = id,
            name = name,
            type = LayerType.TEXT,
            startMs = insertAtMs,
            durationMs = LowerThirdSpec.DURATION_MS,
            color = 0xFF3992CEL,
            text = text,
            textColor = 0xFFFFFFFFL,
            fontWeight = 700,
            scalePercent = scale,
            transformX = centreX,
            transformY = centreY,
            keyframes = mapOf(
                AnimProp.POSITION to listOf(
                    Keyframe(startMs, centreX - slide, centreY, LowerThirdSpec.EASE_CUBIC_OUT),
                    Keyframe(endMs, centreX, centreY),
                    Keyframe(
                        LowerThirdSpec.EXIT_START_MS, centreX, centreY,
                        LowerThirdSpec.EASE_CUBIC_IN
                    ),
                    Keyframe(LowerThirdSpec.DURATION_MS, centreX - slide, centreY)
                ),
                AnimProp.OPACITY to listOf(
                    Keyframe(startMs, 0f, 0f, LowerThirdSpec.EASE_CUBIC_OUT),
                    Keyframe(endMs, 100f, 0f),
                    Keyframe(
                        LowerThirdSpec.EXIT_START_MS, 100f, 0f, LowerThirdSpec.EASE_CUBIC_IN
                    ),
                    Keyframe(LowerThirdSpec.DURATION_MS, 0f, 0f)
                )
            ),
            groupId = groupId,
            parent = link
        )

        val name = stagedText(
            "$idBase-name", "Name", LowerThirdSpec.NAME_TEXT, LowerThirdSpec.NAME_SCALE,
            LowerThirdSpec.nameCentreXPx(pw), LowerThirdSpec.nameCentreYPx(pw, ph),
            LowerThirdSpec.NAME_START_MS, LowerThirdSpec.NAME_END_MS
        )
        val subtitle = stagedText(
            "$idBase-subtitle", "Subtitle", LowerThirdSpec.SUBTITLE_TEXT,
            LowerThirdSpec.SUBTITLE_SCALE,
            LowerThirdSpec.subtitleCentreXPx(pw), LowerThirdSpec.subtitleCentreYPx(pw, ph),
            LowerThirdSpec.SUB_START_MS, LowerThirdSpec.SUB_END_MS
        )

        // The accent bar: a vertical stadium-ended strip in the app's shape convention.
        // Its SCALE track is (width %, height %) against the box: the width share is the
        // same at both grow keys (constant), the height share runs from the smallest
        // separate height to full over the grow window. It carries no entrance slide —
        // it grows in place — but it rides the shared exit like the texts.
        val barScaleX = LowerThirdSpec.BAR_W_FRAC * pw / box * 100f
        val barScaleY = LowerThirdSpec.barHeightPx(pw) / box * 100f
        val barX = LowerThirdSpec.barCentreXPx(pw)
        val barY = LowerThirdSpec.barCentreYPx(pw, ph)
        val bar = TimelineClip(
            id = "$idBase-bar",
            name = "Accent Bar",
            type = LayerType.SHAPE,
            startMs = insertAtMs,
            durationMs = LowerThirdSpec.DURATION_MS,
            color = LowerThirdSpec.ACCENT,
            fillMode = FILL_SOLID,
            textColor = LowerThirdSpec.ACCENT,
            shapePattern = "ROUNDED_RECT",
            shapeCornerRadius = 65f,   // rho = 1 on the smaller half extent: stadium ends
            shapeScaleX = barScaleX / 100f,
            shapeScaleY = barScaleY / 100f,
            scalePercent = barScaleX,
            transformX = barX,
            transformY = barY,
            keyframes = mapOf(
                AnimProp.SCALE to listOf(
                    Keyframe(
                        0L, barScaleX, LowerThirdSpec.GROW_START_PCT,
                        LowerThirdSpec.EASE_CUBIC_OUT
                    ),
                    Keyframe(LowerThirdSpec.BAR_GROW_END_MS, barScaleX, barScaleY)
                ),
                AnimProp.POSITION to listOf(
                    Keyframe(0L, barX, barY),
                    Keyframe(
                        LowerThirdSpec.EXIT_START_MS, barX, barY, LowerThirdSpec.EASE_CUBIC_IN
                    ),
                    Keyframe(LowerThirdSpec.DURATION_MS, barX - slide, barY)
                ),
                AnimProp.OPACITY to listOf(
                    Keyframe(0L, 100f, 0f),
                    Keyframe(
                        LowerThirdSpec.EXIT_START_MS, 100f, 0f, LowerThirdSpec.EASE_CUBIC_IN
                    ),
                    Keyframe(LowerThirdSpec.DURATION_MS, 0f, 0f)
                )
            ),
            groupId = groupId,
            parent = link
        )

        // Lane order (top lane first): the name above the subtitle above the bar (last
        // lane = painted first, so the bar is drawn first and the words over it — they
        // never overlap geometrically, but the words are the stars and keep the top lanes).
        val children = listOf(name, subtitle, bar)
        val lanesTopFirst = listOf(group) + children
        return Insert(group, name, subtitle, bar, children, lanesTopFirst)
    }
}
