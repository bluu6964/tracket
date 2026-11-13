package com.example.model

/**
 * The "Pie Counter" template (Templates programme, reference commission batch 2,
 * 2026-11-13): Sarowar's uploaded reference video (720x1200, 30 fps, 3.97 s) — on a
 * black background a pie fills CLOCKWISE from 12 o'clock (blue -> white gradient)
 * while a big percentage number above it counts up; the part not yet filled stays
 * visible as a thin outline wedge ahead of the fill.
 *
 * Built entirely from pieces the app already has (no new tool was needed for the
 * sweep itself): the fill and the outline are `.pie` wedge layers — the parametric
 * shape the Alight importer builds (see [PieOutline]) — whose angle tracks are
 * ordinary keyframes the engines rebuild per frame (`pieSegmentsAt`); the number is
 * a plain text layer carrying the app's own Count Up/Down animator (the `counter`
 * text animator the importer stores, evaluated by CounterEval in both render
 * paths): its authored text is "0%" and the animator's offset track counts it
 * 0 -> 100 with the same Cubic-out pacing as the wedge, so number and wedge stay in
 * sync. (The one piece that WAS missing — `.pie` / path shapes rendering in the
 * live preview — is fixed in the same build: the preview's shape pass now falls
 * back to the Canvas painter for shapes the native painter declines, exactly like
 * the export already did.)
 *
 * THE ANGLE LAW (Alight's pie wrap rule, verified against [PieOutline] in the
 * harness): the drawn wedge is the disc minus the slice between the two authored
 * angles, i.e. the run from the LARGER angle in the direction of increasing
 * authored angle (authored angles grow counterclockwise on screen, 0 at +x, 90 at
 * the top; a point is (r.cos, -r.sin)). A clockwise fill from the top of sweep
 * phi is therefore the pair {90, 450 - phi}: startAngle fixed at 90, endAngle
 * running 450 -> 90. Both endpoints of that run are the def's closed cases
 * (delta = 0 and delta = 360 both draw NOTHING), so the authored keys stop half a
 * degree short at both ends: endAngle 449.5 (a 0.5-degree sliver filled) -> 89.5
 * (359.5 degrees filled — a full disc to the eye). The outline wedge is the
 * complementary slice {90 - phi + 360k}: startAngle running 89.5 -> -269.5 with
 * endAngle fixed at 90, drawing exactly the part the fill has not reached.
 *
 * DEVIATIONS from the video, honestly: in the source recording (a screen capture
 * with a visible cursor) the wedge races ahead of the number and the count stalls
 * at 15% — the template builds the coherent version the design intends: wedge and
 * number in sync, counting 0% -> 100%. The number's grey -> white vertical
 * gradient is the text layer's gradient fill, as in the video.
 */
object PieCounterSpec {
    const val DURATION_MS = 4000L
    /** The sweep (and the count) run from 0 to here; the last 800 ms hold at 100%. */
    const val SWEEP_MS = 3200L

    const val BG = 0xFF000000L
    const val GRAD_BLUE = 0xFF1E8CFFL
    const val GRAD_WHITE = 0xFFF4F8FFL
    const val OUTLINE = 0xFFB9C4CEL
    const val TEXT_TOP = 0xFF8A8F98L
    const val TEXT_BOTTOM = 0xFFFFFFFFL

    /** Pie radius as a fraction of pw; the pie centre sits this far (of ph) below centre. */
    const val RADIUS_FRAC = 0.30f
    const val PIE_CY_FRAC = 0.07f
    /** The % number's centre, of ph above the canvas centre. */
    const val TEXT_CY_FRAC = -0.13f
    const val TEXT_FONT_FRAC = 0.17f

    /** Authored angles: the fill's fixed edge (12 o'clock) and its moving edge's travel. */
    const val START_ANGLE = 90f
    const val END_FROM = 449.5f
    const val END_TO = 89.5f
    /** The outline's moving edge: same travel, mirrored (it bounds the unfilled slice). */
    const val OUTLINE_FROM = 89.5f
    const val OUTLINE_TO = -269.5f

    /** Filled sweep in degrees for authored angles ([startDeg], [endDeg]) — the wrap rule itself. */
    fun sweepDegrees(startDeg: Float, endDeg: Float): Double =
        PieOutline.sweepDegrees(startDeg.toDouble(), endDeg.toDouble())

    /** The % the counter shows at [relMs] (0..100, Cubic-out over the sweep window). */
    fun percentAt(relMs: Long): Float {
        if (relMs <= 0L) return 0f
        if (relMs >= SWEEP_MS) return 100f
        return EASE_CUBIC_OUT.apply(relMs.toFloat() / SWEEP_MS) * 100f
    }

    // The app's own easing presets (Easing.PRESETS), copied by value.
    val EASE_CUBIC_OUT = Easing(0.215f, 0.61f, 0.355f, 1f)
    val EASE_CUBIC_IN_OUT = Easing(0.645f, 0.045f, 0.355f, 1f)

    /** The Count Up/Down animator (Alight's counter effect id), offset keyed 0 -> 100. */
    fun counterAnimator(): TextAnimator = TextAnimator(
        effectId = "com.alightcreative.effects.counter",
        props = mapOf(
            "offset" to AnimValue(
                type = "float",
                keys = listOf(
                    AnimKey(0f, listOf(0f)),
                    AnimKey(SWEEP_MS.toFloat() / DURATION_MS, listOf(100f), easing = EASE_CUBIC_OUT)
                )
            ),
            "scale" to AnimValue(type = "float", value = listOf(1f))
        )
    )
}

object PieCounterTemplate {

    /** What [buildGroup] hands back: the Group layer, its three layers, and the lanes. */
    data class Insert(
        val group: TimelineClip,
        val background: TimelineClip,
        /** The filled wedge (a `.pie` layer, gradient fill). */
        val fill: TimelineClip,
        /** The unfilled slice's outline (a `.pie` layer, stroke only). */
        val outline: TimelineClip,
        /** The counting % text (plain text + the counter animator). */
        val percentText: TimelineClip,
        /** Every layer inside the group in lane order (top lane first). */
        val children: List<TimelineClip>,
        /** Lanes to place on top of the project, top lane first: the group, then [children]. */
        val lanesTopFirst: List<TimelineClip>
    )

    /**
     * The template as ONE Group layer + background + two pie layers + the counter
     * text, ready to insert into the project that is open. Sizes ride the preview
     * width (the pie is round on any canvas: its radius is a fraction of [pw] on
     * both axes); the group starts at [insertAtMs] and spans [PieCounterSpec.DURATION_MS].
     */
    fun buildGroup(
        previewWidthPx: Float,
        previewHeightPx: Float,
        insertAtMs: Long,
        idBase: String = "pie"
    ): Insert {
        val pw = if (previewWidthPx > 1f) previewWidthPx else 1080f
        val ph = if (previewHeightPx > 1f) previewHeightPx else 1800f
        val box = pw * 130f / 360f
        val dur = PieCounterSpec.DURATION_MS
        val rPx = PieCounterSpec.RADIUS_FRAC * pw
        val pieCY = PieCounterSpec.PIE_CY_FRAC * ph

        val groupId = "$idBase-group"
        val link = GroupOps.linkTo(groupId, GroupOps.Pose.REST)
        val group = TimelineClip(
            id = groupId,
            name = "Pie Counter",
            type = LayerType.GROUP,
            startMs = insertAtMs,
            durationMs = dur,
            color = 0xFF1E88E5L
        )
        fun base(id: String, name: String, type: LayerType, color: Long): TimelineClip = TimelineClip(
            id = id, name = name, type = type,
            startMs = insertAtMs, durationMs = dur, color = color,
            groupId = groupId, parent = link
        )

        val background = base("$idBase-bg", "Background", LayerType.SHAPE, 0xFF455A64L).copy(
            fillMode = FILL_SOLID,
            textColor = PieCounterSpec.BG,
            shapePattern = "RECT",
            shapeScaleX = pw / box,
            shapeScaleY = ph / box,
            scalePercent = pw / box * 100f
        )

        // A .pie layer the way the importer builds one: PATH pattern, the stored
        // outline is the normalised wedge at its most open instant, the angle
        // tracks ride on the clip, and the shape scale carries the disc's size.
        fun pieLayer(
            id: String, name: String,
            startKeys: List<Keyframe>, endKeys: List<Keyframe>,
            storedStart: Float, storedEnd: Float
        ): TimelineClip = base(id, name, LayerType.SHAPE, 0xFF42A5F5L).copy(
            shapePattern = "PATH",
            pathSegments = PieOutline.normalisedSegments(storedStart.toDouble(), storedEnd.toDouble()),
            pieStartKeys = startKeys,
            pieEndKeys = endKeys,
            pieRadius = 1f,
            shapeScaleX = 2f * rPx / box,
            shapeScaleY = 2f * rPx / box,
            scalePercent = 2f * rPx / box * 100f,
            transformY = pieCY
        )

        val fill = pieLayer(
            "$idBase-fill", "Pie Fill",
            startKeys = listOf(Keyframe(0L, PieCounterSpec.START_ANGLE, 0f)),
            endKeys = listOf(
                Keyframe(0L, PieCounterSpec.END_FROM, 0f, PieCounterSpec.EASE_CUBIC_OUT),
                Keyframe(PieCounterSpec.SWEEP_MS, PieCounterSpec.END_TO, 0f)
            ),
            storedStart = PieCounterSpec.START_ANGLE, storedEnd = PieCounterSpec.END_TO
        ).copy(
            fillMode = FILL_GRADIENT,
            gradType = GRAD_LINEAR,
            gradColor1 = PieCounterSpec.GRAD_BLUE,
            gradColor2 = PieCounterSpec.GRAD_WHITE,
            gradX1 = 0.10f, gradY1 = 0.05f, gradX2 = 0.95f, gradY2 = 0.95f
        )

        val outline = pieLayer(
            "$idBase-outline", "Pie Outline",
            startKeys = listOf(
                Keyframe(0L, PieCounterSpec.OUTLINE_FROM, 0f, PieCounterSpec.EASE_CUBIC_OUT),
                Keyframe(PieCounterSpec.SWEEP_MS, PieCounterSpec.OUTLINE_TO, 0f)
            ),
            endKeys = listOf(Keyframe(0L, PieCounterSpec.START_ANGLE, 0f)),
            storedStart = PieCounterSpec.OUTLINE_FROM, storedEnd = PieCounterSpec.START_ANGLE
        ).copy(
            fillMode = FILL_NONE,
            strokeColor = PieCounterSpec.OUTLINE,
            strokeWidth = 360f * 0.0035f
        )

        val percentText = base("$idBase-percent", "Percent", LayerType.TEXT, 0xFF3992CEL).copy(
            text = "0%",
            fillMode = FILL_GRADIENT,
            gradType = GRAD_LINEAR,
            gradColor1 = PieCounterSpec.TEXT_TOP,
            gradColor2 = PieCounterSpec.TEXT_BOTTOM,
            gradX1 = 0.5f, gradY1 = 0f, gradX2 = 0.5f, gradY2 = 1f,
            fontWeight = 800,
            scalePercent = PieCounterSpec.TEXT_FONT_FRAC / 0.11f * 100f,
            transformY = PieCounterSpec.TEXT_CY_FRAC * ph,
            textAnimators = listOf(PieCounterSpec.counterAnimator()),
            keyframes = mapOf(
                AnimProp.OPACITY to listOf(
                    Keyframe(0L, 0f, 0f, PieCounterSpec.EASE_CUBIC_OUT),
                    Keyframe(300L, 100f, 0f)
                )
            )
        )

        // Lane order (top lane first): the number, the outline, the fill, the background.
        val children = listOf(percentText, outline, fill, background)
        val lanesTopFirst = listOf(group) + children
        return Insert(group, background, fill, outline, percentText, children, lanesTopFirst)
    }
}
