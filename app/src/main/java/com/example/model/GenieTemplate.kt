package com.example.model

import kotlin.math.cos
import kotlin.math.sin

/**
 * The "Genie" template (Templates programme, reference commission 2026-11-13): Sarowar's
 * uploaded reference video (720x1280, 60 fps, 16.04 s) — a small white pill with a dark
 * flower mark sits centre-frame on black; a blue-white light streak smears it sideways
 * and it stretches into a long white bar, tilted about 20 degrees, as the background
 * crossfades to light grey; the bar carries a row of circular photo avatars with name
 * labels that scrolls slowly sideways; one by one (Liam, Noah, Emma, Chloe) a big tilted
 * profile card pops up — full-bleed photo, huge name, a dark "Genie suggests" pill with
 * an activity line and an Invite pill — while the featured person's avatar carries a
 * selection ring; at the end the cards are gone, the background fades back to black and
 * the bar shrinks back into the pill, so the piece loops. The full measured analysis is
 * ~/workspace/after-motion-dev/genie-ref/SPEC.md.
 *
 * Like the other templates, inserting it drops ONE Group layer into the current timeline
 * at the playhead (see [buildGroup]) and everything inside is ordinary layers the user
 * edits in place: photos are image-filled shapes replaced via Color -> Fill -> Image,
 * every label / name / activity line is a plain text layer, all motion is ordinary
 * keyframes. There is NO rig (no Controller, no Null — nothing here has their job), and
 * the group's own transform is a pure user control on top of the authored design.
 *
 * Geometry (fractions of the preview width [pw] unless noted; vertical anchors that the
 * video holds against the frame height ride [ph]): the pill is 0.203 x 0.098 of the
 * width at the exact frame centre (measured on the reference: bbox x 0.397..0.600,
 * y 0.472..0.527 of the 720x1280 frame, and the first and last frames' bboxes are
 * identical — the loop closes on the same pixels). The pill and the bar are ONE shape:
 * a rounded rect whose SCALE track runs (56.2%, 27.1%) -> (254.8%, 36.0%) — the corner
 * radius is rho = 1, so at pill size it reads as a capsule and at bar size as a
 * stadium-ended bar, exactly the video's morph. The bar settles at centre + 0.03 x ph,
 * tilted +20 degrees (the app's positive rotation is screen-clockwise: the bar's right
 * end sits lower, as in the video). Avatar positions are computed in the bar's own
 * tilted frame ([GenieSpec.barPoint]); the avatar circles themselves stay upright (the
 * faces in the video do not tilt) while their name labels carry the bar's tilt.
 *
 * DEVIATIONS from the video, honestly (also in ROADMAP): (1) the cards' 3D perspective
 * swing is approximated with real ROT_X / ROT_Y keyframes (the engine does support
 * keyframed 3D rotation end to end) plus the 2D tilt — each card element rotates about
 * its OWN centre, because the card's parts are independent layers sharing one track
 * design (a rigid per-card subgroup is not something the group model offers); (2) the
 * light streak is a radial-gradient shape carrying the panel's own Glow and Directional
 * Blur effects — the smear is an effect stack on an ordinary layer, not a bespoke
 * render; (3) the flower mark is built from 8 small ellipse petals (no font glyph is
 * guaranteed to carry the video's asterisk-flower on every phone) — the same petal
 * flower sits on the pill, travels (invisible) to the bar's right end while the bar is
 * up, and returns for the outro; (4) the video crossfades the background black -> grey
 * between about 1.8 s and 2.8 s and back at the tail — carried here by FILL_COLOR keys
 * on the background shape.
 */
object GenieSpec {
    const val DURATION_MS = 16000L

    // ---- timing (ms from the template's start) ----
    const val STRETCH_START_MS = 1000L
    const val STRETCH_END_MS = 2100L
    const val SCROLL_START_MS = 2600L
    const val SCROLL_END_MS = 13500L
    const val SHRINK_START_MS = 13800L
    const val SHRINK_END_MS = 15250L
    const val CARD_FIRST_MS = 3500L
    const val CARD_CYCLE_MS = 2500L
    const val CARD_ENTER_MS = 550L
    const val CARD_EXIT_START_MS = 2000L

    /** Card [index]'s window start (0 = Liam, 1 = Noah, 2 = Emma, 3 = Chloe). */
    fun cardStartMs(index: Int): Long = CARD_FIRST_MS + index * CARD_CYCLE_MS

    // ---- colours ----
    const val BG_BLACK = 0xFF000000L
    const val BG_GREY = 0xFFE9E9ECL
    const val BAR_WHITE = 0xFFFFFFFFL
    const val BAR_SHADOW = 0xFF8E8E96L
    const val FLOWER_DARK = 0xFF202024L
    const val NAME_DARK = 0xFF2B2B30L
    const val SUGGEST_PILL = 0xB31E1E24L
    const val INVITE_PILL = 0x40FFFFFFL
    const val ACTIVITY_GREY = 0xFFD8D8DEL

    // ---- bar / pill geometry (fractions of pw; the bar centre rides ph) ----
    const val BAR_W_FRAC = 0.92f
    const val BAR_H_FRAC = 0.13f
    const val PILL_W_FRAC = 0.203f
    const val PILL_H_FRAC = 0.098f
    const val BAR_CY_FRAC = 0.03f          // of ph, below the canvas centre
    const val BAR_TILT_DEG = 20f
    const val BAR_CORNER = 65f             // rho = 1: capsule / stadium at any size

    // ---- avatars ----
    val NAMES = listOf("Me", "Liam", "Noah", "Emma", "Chloe", "Steph")
    const val AVATAR_D_FRAC = 0.115f
    const val AVATAR_STEP_FRAC = 0.138f
    const val NAME_V_FRAC = 0.088f         // name label centre below the bar axis
    const val NAME_FONT_FRAC = 0.030f
    const val SCROLL_FRAC = 0.138f   // one avatar step; the drift is centred on the row
    // (scroll runs -travel/2 -> +travel/2) so the row never slides off the bar's ends —
    // the video's row stays on the bar for the whole piece.
    const val AVATAR_IN_FIRST_MS = 1900L
    const val AVATAR_IN_STAGGER_MS = 90L
    const val AVATAR_IN_MS = 350L
    const val AVATARS_OUT_START_MS = 13800L
    const val AVATARS_OUT_END_MS = 14400L

    /** Avatar [index]'s bar-axis coordinate (u) at scroll 0, in stored px. */
    fun avatarU(index: Int, pw: Float): Float = (index - 2.5f) * AVATAR_STEP_FRAC * pw

    /** The row's scroll offset (px) at [relMs]: -SCROLL_FRAC/2 x pw until SCROLL_START,
     *  linear to +SCROLL_FRAC/2 x pw at SCROLL_END (the drift centred on the row). */
    fun scrollAt(relMs: Long, pw: Float): Float {
        val half = SCROLL_FRAC * pw / 2f
        if (relMs <= SCROLL_START_MS) return -half
        if (relMs >= SCROLL_END_MS) return half
        val f = (relMs - SCROLL_START_MS).toFloat() / (SCROLL_END_MS - SCROLL_START_MS)
        return -half + SCROLL_FRAC * pw * f
    }

    /**
     * Frame position (stored px from the canvas centre) of the bar-local point
     * ([u] along the bar's axis, [v] across it), in the bar's settled pose.
     */
    fun barPoint(u: Float, v: Float, pw: Float, ph: Float): Pair<Float, Float> {
        val rad = Math.toRadians(BAR_TILT_DEG.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        return (u * c - v * s) to (BAR_CY_FRAC * ph + u * s + v * c)
    }

    /** Avatar [index]'s centre at [relMs] (scroll applied), in stored px from the canvas centre. */
    fun avatarPosAt(index: Int, relMs: Long, pw: Float, ph: Float): Pair<Float, Float> =
        barPoint(avatarU(index, pw) - scrollAt(relMs, pw), 0f, pw, ph)

    /** Avatar [index]'s name-label centre at [relMs]. */
    fun avatarNamePosAt(index: Int, relMs: Long, pw: Float, ph: Float): Pair<Float, Float> =
        barPoint(avatarU(index, pw) - scrollAt(relMs, pw), NAME_V_FRAC * pw, pw, ph)

    // ---- selection rings ----
    val RING_COLORS = listOf(0xFFE53935L, 0xFFFFFFFFL, 0xFFFFFFFFL, 0xFFFFFFFFL)
    const val RING_D_FRAC = 0.13f
    const val RING_STROKE_FRAC = 0.0075f   // of pw, rendered

    // ---- the flower mark (8 ellipse petals) ----
    const val PETALS = 8
    const val PETAL_RING_FRAC = 0.0145f    // petal centres' distance from the flower centre
    const val PETAL_W_FRAC = 0.013f
    const val PETAL_H_FRAC = 0.026f
    const val BAR_END_U_FRAC = 0.428f      // the flower's bar-local u at the bar's right end

    /** Petal [k]'s centre offset from the flower centre (flower untilted), stored px. */
    fun petalOffset(k: Int, pw: Float): Pair<Float, Float> {
        val a = Math.toRadians((k * 45).toDouble())
        return (PETAL_RING_FRAC * pw * sin(a).toFloat()) to (-PETAL_RING_FRAC * pw * cos(a).toFloat())
    }

    /** The flower centre at the bar's right end (bar settled), stored px from the canvas centre. */
    fun barEndFlowerCentre(pw: Float, ph: Float): Pair<Float, Float> =
        barPoint(BAR_END_U_FRAC * pw, 0f, pw, ph)

    // ---- cards ----
    val CARD_TITLES = listOf("LIAM", "NOAH", "EMMA", "CHLOE")
    val CARD_NAMES = listOf("Liam", "Noah", "Emma", "Chloe")
    val ACTIVITIES = listOf(
        "Scenic walk together",
        "Birdwatching with Noah",
        "Picnic by the lake",
        "Eating tapas at The Ranch"
    )
    const val SUGGESTS = "Genie suggests"
    const val INVITE = "Invite"
    const val CARD_CX_FRAC = 0.02f         // of pw
    const val CARD_CY_FRAC = -0.05f        // of ph
    const val CARD_TILT_DEG = -16f
    const val PHOTO_W_FRAC = 0.60f         // of pw
    const val PHOTO_H_FRAC = 0.42f         // of ph
    const val PHOTO_CORNER_FRAC = 0.030f   // of pw (frame-space radius at rest)
    const val TITLE_FONT_FRAC = 0.150f
    const val TITLE_X_FRAC = -0.03f
    const val TITLE_Y_FRAC = -0.115f       // of ph
    const val PILL_CX_FRAC = -0.055f
    const val PILL_CY_FRAC = 0.150f        // of ph
    const val SUGGEST_W_FRAC = 0.44f
    const val SUGGEST_H_FRAC = 0.050f      // of ph
    const val FLOWER_CX_FRAC = -0.245f
    const val CARD_PETAL_RING_FRAC = 0.0095f
    const val CARD_PETAL_W_FRAC = 0.009f
    const val CARD_PETAL_H_FRAC = 0.018f
    const val TEXT_X_FRAC = -0.115f
    const val SUGGEST_FONT_FRAC = 0.0255f
    const val SUGGEST_Y_FRAC = 0.1375f     // of ph
    const val ACTIVITY_FONT_FRAC = 0.022f
    const val ACTIVITY_Y_FRAC = 0.1625f    // of ph
    const val INVITE_CX_FRAC = 0.212f
    const val INVITE_W_FRAC = 0.125f
    const val INVITE_FONT_FRAC = 0.026f

    /** A point in the card's local frame -> frame position (card settled at its tilt). */
    fun cardPoint(lx: Float, ly: Float, pw: Float, ph: Float): Pair<Float, Float> {
        val rad = Math.toRadians(CARD_TILT_DEG.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        return (CARD_CX_FRAC * pw + lx * c - ly * s) to (CARD_CY_FRAC * ph + lx * s + ly * c)
    }

    // The app's own easing presets (Easing.PRESETS), copied by value like the other templates.
    val EASE_CUBIC_OUT = Easing(0.215f, 0.61f, 0.355f, 1f)
    val EASE_CUBIC_IN = Easing(0.55f, 0.055f, 0.675f, 0.19f)
    val EASE_CUBIC_IN_OUT = Easing(0.645f, 0.045f, 0.355f, 1f)
    val EASE_BACK_OUT = Easing(0.175f, 0.885f, 0.32f, 1.2f)
}

object GenieTemplate {

    /** One profile card's layers (front-to-back inside the subtree as listed in [all]). */
    data class Card(
        val inviteText: TimelineClip,
        val invitePill: TimelineClip,
        val activityText: TimelineClip,
        val suggestsText: TimelineClip,
        val petals: List<TimelineClip>,
        val suggestPill: TimelineClip,
        val title: TimelineClip,
        val photo: TimelineClip,
        /** The subtree's layers in lane order (top lane first). */
        val all: List<TimelineClip>
    )

    /** What [buildGroup] hands back: the Group layer, the named parts, and the lanes. */
    data class Insert(
        val group: TimelineClip,
        val background: TimelineClip,
        val bar: TimelineClip,
        val barShadow: TimelineClip,
        val streak: TimelineClip,
        /** The pill/bar flower: 8 ellipse petals, one ordinary shape layer each. */
        val petals: List<TimelineClip>,
        /** The 6 circular photo slots, in bar order (Me, Liam, Noah, Emma, Chloe, Steph). */
        val avatars: List<TimelineClip>,
        /** The 6 name labels under the avatars, same order. */
        val avatarNames: List<TimelineClip>,
        /** The 4 selection rings (on Liam, Noah, Emma, Chloe), in card order. */
        val rings: List<TimelineClip>,
        /** The 4 profile cards (Liam, Noah, Emma, Chloe). */
        val cards: List<Card>,
        /** Every layer inside the group in lane order (top lane first). */
        val children: List<TimelineClip>,
        /** Lanes to place on top of the project, top lane first: the group, then [children]. */
        val lanesTopFirst: List<TimelineClip>
    )

    /**
     * The template as ONE Group layer + its 88 ordinary layers, ready to insert into the
     * project that is open. [previewWidthPx] / [previewHeightPx] are the editor preview
     * frame's size (the stored-units basis, like the importer's); the group starts at
     * [insertAtMs] (the playhead) and spans [GenieSpec.DURATION_MS]. [avatarUris] (6) and
     * [photoUris] (4) feed the slots' image fills (generated placeholders; the user
     * replaces them via Color -> Fill -> Image). Every call builds fresh clips under
     * [idBase] — inserting twice gives two independent pieces.
     */
    fun buildGroup(
        previewWidthPx: Float,
        previewHeightPx: Float,
        avatarUris: List<String>,
        photoUris: List<String>,
        insertAtMs: Long,
        idBase: String = "genie"
    ): Insert {
        require(avatarUris.size == GenieSpec.NAMES.size) { "genie needs ${GenieSpec.NAMES.size} avatar pictures" }
        require(photoUris.size == GenieSpec.CARD_TITLES.size) { "genie needs ${GenieSpec.CARD_TITLES.size} card photos" }
        val pw = if (previewWidthPx > 1f) previewWidthPx else 1080f
        val ph = if (previewHeightPx > 1f) previewHeightPx else 1920f
        val box = pw * 130f / 360f
        val dur = GenieSpec.DURATION_MS
        val barCY = GenieSpec.BAR_CY_FRAC * ph

        val groupId = "$idBase-group"
        val link = GroupOps.linkTo(groupId, GroupOps.Pose.REST)
        val group = TimelineClip(
            id = groupId,
            name = "Genie",
            type = LayerType.GROUP,
            startMs = insertAtMs,
            durationMs = dur,
            color = 0xFF3949ABL
        )

        fun base(
            id: String, name: String, type: LayerType, color: Long
        ): TimelineClip = TimelineClip(
            id = id, name = name, type = type,
            startMs = insertAtMs, durationMs = dur, color = color,
            groupId = groupId, parent = link
        )

        /** A shape's SCALE-track percent for a size given as a fraction of pw (width-riding). */
        fun pctW(frac: Float): Float = frac * pw / box * 100f
        /** A shape's SCALE-track percent for a height given in stored px. */
        fun pctPx(px: Float): Float = px / box * 100f

        // ---------- background: black at the head/tail, light grey for the middle ----------
        val bgW = pw
        val bgH = ph
        val background = base("$idBase-bg", "Background", LayerType.SHAPE, 0xFF455A64L).copy(
            fillMode = FILL_SOLID,
            textColor = GenieSpec.BG_BLACK,
            shapePattern = "RECT",
            shapeScaleX = bgW / box,
            shapeScaleY = bgH / box,
            scalePercent = bgW / box * 100f,
            keyframes = mapOf(
                AnimProp.FILL_COLOR to listOf(
                    Keyframe(0L, packColor(GenieSpec.BG_BLACK).first, packColor(GenieSpec.BG_BLACK).second),
                    Keyframe(1800L, packColor(GenieSpec.BG_BLACK).first, packColor(GenieSpec.BG_BLACK).second, GenieSpec.EASE_CUBIC_IN_OUT),
                    Keyframe(2800L, packColor(GenieSpec.BG_GREY).first, packColor(GenieSpec.BG_GREY).second),
                    Keyframe(13500L, packColor(GenieSpec.BG_GREY).first, packColor(GenieSpec.BG_GREY).second, GenieSpec.EASE_CUBIC_IN_OUT),
                    Keyframe(14800L, packColor(GenieSpec.BG_BLACK).first, packColor(GenieSpec.BG_BLACK).second),
                    Keyframe(dur, packColor(GenieSpec.BG_BLACK).first, packColor(GenieSpec.BG_BLACK).second)
                )
            )
        )

        // ---------- the pill / bar: ONE shape whose SCALE track is the morph ----------
        val barWPct = pctW(GenieSpec.BAR_W_FRAC)
        val barHPct = pctW(GenieSpec.BAR_H_FRAC)
        val pillWPct = pctW(GenieSpec.PILL_W_FRAC)
        val pillHPct = pctW(GenieSpec.PILL_H_FRAC)
        fun barKeys(offX: Float, offY: Float, scaleMul: Float): Map<AnimProp, List<Keyframe>> = mapOf(
            AnimProp.POSITION to listOf(
                Keyframe(0L, offX, offY),
                Keyframe(GenieSpec.STRETCH_START_MS, offX, offY, GenieSpec.EASE_CUBIC_OUT),
                Keyframe(GenieSpec.STRETCH_END_MS, offX, barCY + offY),
                Keyframe(GenieSpec.SHRINK_START_MS, offX, barCY + offY, GenieSpec.EASE_CUBIC_IN_OUT),
                Keyframe(GenieSpec.SHRINK_END_MS, offX, offY)
            ),
            AnimProp.ROTATION to listOf(
                Keyframe(0L, 0f, 0f),
                Keyframe(GenieSpec.STRETCH_START_MS, 0f, 0f, GenieSpec.EASE_CUBIC_OUT),
                Keyframe(GenieSpec.STRETCH_END_MS, GenieSpec.BAR_TILT_DEG, 0f),
                Keyframe(GenieSpec.SHRINK_START_MS, GenieSpec.BAR_TILT_DEG, 0f, GenieSpec.EASE_CUBIC_IN_OUT),
                Keyframe(GenieSpec.SHRINK_END_MS, 0f, 0f)
            ),
            AnimProp.SCALE to listOf(
                Keyframe(0L, pillWPct * scaleMul, pillHPct * scaleMul),
                Keyframe(GenieSpec.STRETCH_START_MS, pillWPct * scaleMul, pillHPct * scaleMul, GenieSpec.EASE_CUBIC_OUT),
                Keyframe(GenieSpec.STRETCH_END_MS, barWPct * scaleMul, barHPct * scaleMul),
                Keyframe(GenieSpec.SHRINK_START_MS, barWPct * scaleMul, barHPct * scaleMul, GenieSpec.EASE_CUBIC_IN_OUT),
                Keyframe(GenieSpec.SHRINK_END_MS, pillWPct * scaleMul, pillHPct * scaleMul)
            )
        )
        val bar = base("$idBase-bar", "Pill / Bar", LayerType.SHAPE, 0xFF90A4AEL).copy(
            fillMode = FILL_SOLID,
            textColor = GenieSpec.BAR_WHITE,
            shapePattern = "ROUNDED_RECT",
            shapeCornerRadius = GenieSpec.BAR_CORNER,
            shapeScaleX = GenieSpec.BAR_W_FRAC * pw / box,
            shapeScaleY = GenieSpec.BAR_H_FRAC * pw / box,
            scalePercent = barWPct,
            transformY = barCY,
            rotationDeg = GenieSpec.BAR_TILT_DEG,
            keyframes = barKeys(0f, 0f, 1f)
        )
        val shadowOffX = 0.004f * pw
        val shadowOffY = 0.008f * pw
        val barShadow = base("$idBase-barshadow", "Bar Shadow", LayerType.SHAPE, 0xFF90A4AEL).copy(
            fillMode = FILL_SOLID,
            textColor = GenieSpec.BAR_SHADOW,
            shapePattern = "ROUNDED_RECT",
            shapeCornerRadius = GenieSpec.BAR_CORNER,
            shapeScaleX = GenieSpec.BAR_W_FRAC * pw / box * 1.02f,
            shapeScaleY = GenieSpec.BAR_H_FRAC * pw / box * 1.02f,
            scalePercent = barWPct * 1.02f,
            transformX = shadowOffX,
            transformY = barCY + shadowOffY,
            rotationDeg = GenieSpec.BAR_TILT_DEG,
            opacity = 45f,
            keyframes = barKeys(shadowOffX, shadowOffY, 1.02f) + mapOf(
                AnimProp.OPACITY to listOf(
                    Keyframe(GenieSpec.STRETCH_END_MS, 0f, 0f, GenieSpec.EASE_CUBIC_OUT),
                    Keyframe(2600L, 45f, 0f),
                    Keyframe(13500L, 45f, 0f, GenieSpec.EASE_CUBIC_IN),
                    Keyframe(14200L, 0f, 0f)
                )
            )
        )

        // ---------- the light streak: a soft radial-gradient shape + Glow + Directional Blur ----------
        val streakWPct = pctW(0.98f)
        val streakHPct = pctW(0.055f)
        val streak = base("$idBase-streak", "Light Streak", LayerType.SHAPE, 0xFF90A4AEL).copy(
            fillMode = FILL_GRADIENT,
            gradType = GRAD_RADIAL,
            gradColor1 = 0xFFFFFFFFL,
            gradColor2 = 0x00A9C6FFL,
            shapePattern = "ROUNDED_RECT",
            shapeCornerRadius = 65f,
            shapeScaleX = 0.98f * pw / box,
            shapeScaleY = 0.055f * pw / box,
            scalePercent = streakWPct,
            transformY = barCY * 0.5f,
            opacity = 0f,
            effects = listOf(
                ClipFx(type = ClipFxLibrary.GLOW, values = listOf(30f, 60f, 160f)),
                ClipFx(type = ClipFxLibrary.DIR_BLUR, values = listOf(0f, 45f, 20f))
            ),
            keyframes = mapOf(
                AnimProp.OPACITY to listOf(
                    Keyframe(GenieSpec.STRETCH_START_MS, 0f, 0f, GenieSpec.EASE_CUBIC_OUT),
                    Keyframe(1250L, 100f, 0f),
                    Keyframe(1900L, 100f, 0f, GenieSpec.EASE_CUBIC_IN),
                    Keyframe(2250L, 0f, 0f)
                ),
                AnimProp.SCALE to listOf(
                    Keyframe(GenieSpec.STRETCH_START_MS, streakWPct * 0.30f, streakHPct * 0.55f, GenieSpec.EASE_CUBIC_OUT),
                    Keyframe(1600L, streakWPct, streakHPct)
                )
            )
        )

        // ---------- the flower: 8 ellipse petals, pill centre -> bar end -> pill centre ----------
        val endCentre = GenieSpec.barEndFlowerCentre(pw, ph)
        val petals = (0 until GenieSpec.PETALS).map { k ->
            val ang = (k * 45).toFloat()
            val off = GenieSpec.petalOffset(k, pw)
            val rad = Math.toRadians(GenieSpec.BAR_TILT_DEG.toDouble())
            val endOff = (off.first * cos(rad) - off.second * sin(rad)).toFloat() to
                (off.first * sin(rad) + off.second * cos(rad)).toFloat()
            val endPos = (endCentre.first + endOff.first) to (endCentre.second + endOff.second)
            base("$idBase-petal${k + 1}", "Flower Petal ${k + 1}", LayerType.SHAPE, 0xFF8D6E63L).copy(
                fillMode = FILL_SOLID,
                textColor = GenieSpec.FLOWER_DARK,
                shapePattern = "OVAL",
                shapeScaleX = GenieSpec.PETAL_W_FRAC * pw / box,
                shapeScaleY = GenieSpec.PETAL_H_FRAC * pw / box,
                scalePercent = pctW(GenieSpec.PETAL_W_FRAC),
                transformX = off.first,
                transformY = off.second,
                rotationDeg = ang,
                keyframes = mapOf(
                    AnimProp.POSITION to listOf(
                        Keyframe(0L, off.first, off.second),
                        Keyframe(1200L, off.first, off.second),
                        Keyframe(2350L, endPos.first, endPos.second),
                        Keyframe(14100L, endPos.first, endPos.second),
                        Keyframe(14700L, off.first, off.second)
                    ),
                    AnimProp.ROTATION to listOf(
                        Keyframe(0L, ang, 0f),
                        Keyframe(1200L, ang, 0f),
                        Keyframe(2350L, ang + GenieSpec.BAR_TILT_DEG, 0f),
                        Keyframe(14100L, ang + GenieSpec.BAR_TILT_DEG, 0f),
                        Keyframe(14700L, ang, 0f)
                    ),
                    AnimProp.OPACITY to listOf(
                        Keyframe(0L, 100f, 0f),
                        Keyframe(GenieSpec.STRETCH_START_MS, 100f, 0f, GenieSpec.EASE_CUBIC_IN),
                        Keyframe(1200L, 0f, 0f),
                        Keyframe(2350L, 0f, 0f, GenieSpec.EASE_CUBIC_OUT),
                        Keyframe(2650L, 100f, 0f),
                        Keyframe(GenieSpec.SHRINK_START_MS, 100f, 0f, GenieSpec.EASE_CUBIC_IN),
                        Keyframe(14100L, 0f, 0f),
                        Keyframe(14700L, 0f, 0f, GenieSpec.EASE_CUBIC_OUT),
                        Keyframe(GenieSpec.SHRINK_END_MS, 100f, 0f)
                    )
                )
            )
        }

        // ---------- avatars + their name labels (scroll together along the bar) ----------
        val avatarRestPct = pctW(GenieSpec.AVATAR_D_FRAC)
        fun scrollPosKeys(index: Int, v: Float): List<Keyframe> {
            val p0 = GenieSpec.barPoint(GenieSpec.avatarU(index, pw), v, pw, ph)
            val p1 = GenieSpec.barPoint(GenieSpec.avatarU(index, pw) - GenieSpec.SCROLL_FRAC * pw, v, pw, ph)
            return listOf(
                Keyframe(GenieSpec.SCROLL_START_MS, p0.first, p0.second),
                Keyframe(GenieSpec.SCROLL_END_MS, p1.first, p1.second)
            )
        }
        fun avatarOpacityKeys(index: Int): List<Keyframe> {
            val fi = GenieSpec.AVATAR_IN_FIRST_MS + index * GenieSpec.AVATAR_IN_STAGGER_MS
            return listOf(
                Keyframe(fi, 0f, 0f, GenieSpec.EASE_CUBIC_OUT),
                Keyframe(fi + GenieSpec.AVATAR_IN_MS, 100f, 0f),
                Keyframe(GenieSpec.AVATARS_OUT_START_MS, 100f, 0f, GenieSpec.EASE_CUBIC_IN),
                Keyframe(GenieSpec.AVATARS_OUT_END_MS, 0f, 0f)
            )
        }
        val avatars = (0 until 6).map { i ->
            val fi = GenieSpec.AVATAR_IN_FIRST_MS + i * GenieSpec.AVATAR_IN_STAGGER_MS
            val p0 = GenieSpec.barPoint(GenieSpec.avatarU(i, pw), 0f, pw, ph)
            base("$idBase-avatar${i + 1}", "Avatar ${GenieSpec.NAMES[i]}", LayerType.SHAPE, 0xFF66BB6AL).copy(
                fillMode = FILL_IMAGE,
                fillImageUri = avatarUris[i],
                fillImageMode = IMG_FILL,
                shapePattern = "CIRCLE",
                shapeScaleX = GenieSpec.AVATAR_D_FRAC * pw / box,
                shapeScaleY = GenieSpec.AVATAR_D_FRAC * pw / box,
                scalePercent = avatarRestPct,
                transformX = p0.first,
                transformY = p0.second,
                keyframes = mapOf(
                    AnimProp.POSITION to scrollPosKeys(i, 0f),
                    AnimProp.SCALE to listOf(
                        Keyframe(fi, avatarRestPct * 0.4f, 0f, GenieSpec.EASE_BACK_OUT),
                        Keyframe(fi + GenieSpec.AVATAR_IN_MS, avatarRestPct, 0f)
                    ),
                    AnimProp.OPACITY to avatarOpacityKeys(i)
                )
            )
        }
        val avatarNames = (0 until 6).map { i ->
            val v = GenieSpec.NAME_V_FRAC * pw
            val p0 = GenieSpec.barPoint(GenieSpec.avatarU(i, pw), v, pw, ph)
            base("$idBase-avname${i + 1}", "Name ${GenieSpec.NAMES[i]}", LayerType.TEXT, 0xFF3992CEL).copy(
                text = GenieSpec.NAMES[i],
                textColor = GenieSpec.NAME_DARK,
                fontWeight = 600,
                scalePercent = GenieSpec.NAME_FONT_FRAC / 0.11f * 100f,
                transformX = p0.first,
                transformY = p0.second,
                rotationDeg = GenieSpec.BAR_TILT_DEG,
                keyframes = mapOf(
                    AnimProp.POSITION to scrollPosKeys(i, v),
                    AnimProp.OPACITY to avatarOpacityKeys(i)
                )
            )
        }

        // ---------- selection rings on the featured avatars ----------
        val rings = (0 until 4).map { i ->
            val avatarIdx = i + 1
            val s = GenieSpec.cardStartMs(i)
            val p0 = GenieSpec.barPoint(GenieSpec.avatarU(avatarIdx, pw), 0f, pw, ph)
            base("$idBase-ring${i + 1}", "Ring ${GenieSpec.CARD_NAMES[i]}", LayerType.SHAPE, 0xFFFFCA28L).copy(
                fillMode = FILL_NONE,
                shapePattern = "CIRCLE",
                shapeScaleX = GenieSpec.RING_D_FRAC * pw / box,
                shapeScaleY = GenieSpec.RING_D_FRAC * pw / box,
                scalePercent = pctW(GenieSpec.RING_D_FRAC),
                strokeColor = GenieSpec.RING_COLORS[i],
                strokeWidth = 360f * GenieSpec.RING_STROKE_FRAC,
                transformX = p0.first,
                transformY = p0.second,
                opacity = 0f,
                keyframes = mapOf(
                    AnimProp.POSITION to scrollPosKeys(avatarIdx, 0f),
                    AnimProp.OPACITY to listOf(
                        Keyframe(s - 50L, 0f, 0f, GenieSpec.EASE_CUBIC_OUT),
                        Keyframe(s + 250L, 100f, 0f),
                        Keyframe(s + GenieSpec.CARD_EXIT_START_MS - 50L, 100f, 0f, GenieSpec.EASE_CUBIC_IN),
                        Keyframe(s + GenieSpec.CARD_CYCLE_MS - 50L, 0f, 0f)
                    )
                )
            )
        }

        // ---------- the four profile cards ----------
        val cards = (0 until 4).map { i ->
            val s = GenieSpec.cardStartMs(i)
            val baseRot = GenieSpec.CARD_TILT_DEG
            fun cardKeys(
                pos: Pair<Float, Float>, restA: Float, restB: Float, rotBase: Float
            ): Map<AnimProp, List<Keyframe>> = mapOf(
                AnimProp.POSITION to listOf(
                    Keyframe(s, pos.first, pos.second + 0.035f * ph, GenieSpec.EASE_CUBIC_OUT),
                    Keyframe(s + GenieSpec.CARD_ENTER_MS, pos.first, pos.second),
                    Keyframe(s + GenieSpec.CARD_EXIT_START_MS, pos.first, pos.second, GenieSpec.EASE_CUBIC_IN),
                    Keyframe(s + GenieSpec.CARD_CYCLE_MS, pos.first, pos.second + 0.05f * ph)
                ),
                AnimProp.SCALE to listOf(
                    Keyframe(s, restA * 0.55f, restB * 0.55f, GenieSpec.EASE_BACK_OUT),
                    Keyframe(s + GenieSpec.CARD_ENTER_MS, restA, restB),
                    Keyframe(s + GenieSpec.CARD_EXIT_START_MS, restA, restB, GenieSpec.EASE_CUBIC_IN),
                    Keyframe(s + GenieSpec.CARD_CYCLE_MS, restA * 0.72f, restB * 0.72f)
                ),
                AnimProp.ROTATION to listOf(
                    Keyframe(s, rotBase + 26f, 0f, GenieSpec.EASE_CUBIC_OUT),
                    Keyframe(s + GenieSpec.CARD_ENTER_MS, rotBase, 0f),
                    Keyframe(s + GenieSpec.CARD_EXIT_START_MS, rotBase, 0f, GenieSpec.EASE_CUBIC_IN),
                    Keyframe(s + GenieSpec.CARD_CYCLE_MS, rotBase - 11f, 0f)
                ),
                AnimProp.ROT_X to listOf(
                    Keyframe(s, 12f, 0f, GenieSpec.EASE_CUBIC_OUT),
                    Keyframe(s + GenieSpec.CARD_ENTER_MS, 0f, 0f),
                    Keyframe(s + GenieSpec.CARD_EXIT_START_MS, 0f, 0f, GenieSpec.EASE_CUBIC_IN),
                    Keyframe(s + GenieSpec.CARD_CYCLE_MS, -8f, 0f)
                ),
                AnimProp.ROT_Y to listOf(
                    Keyframe(s, -38f, 0f, GenieSpec.EASE_CUBIC_OUT),
                    Keyframe(s + GenieSpec.CARD_ENTER_MS, 0f, 0f),
                    Keyframe(s + GenieSpec.CARD_EXIT_START_MS, 0f, 0f, GenieSpec.EASE_CUBIC_IN),
                    Keyframe(s + GenieSpec.CARD_CYCLE_MS, 30f, 0f)
                ),
                AnimProp.OPACITY to listOf(
                    Keyframe(s, 0f, 0f, GenieSpec.EASE_CUBIC_OUT),
                    Keyframe(s + 300L, 100f, 0f),
                    Keyframe(s + GenieSpec.CARD_EXIT_START_MS, 100f, 0f, GenieSpec.EASE_CUBIC_IN),
                    Keyframe(s + GenieSpec.CARD_CYCLE_MS, 0f, 0f)
                )
            )
            fun shapeChild(
                id: String, name: String, local: Pair<Float, Float>,
                wPx: Float, hPx: Float, restB: Float
            ): TimelineClip {
                val pos = GenieSpec.cardPoint(local.first, local.second, pw, ph)
                val restA = pctPx(wPx)
                return base(id, name, LayerType.SHAPE, 0xFFAB47BCL).copy(
                    shapeScaleX = wPx / box,
                    shapeScaleY = hPx / box,
                    scalePercent = restA,
                    transformX = pos.first,
                    transformY = pos.second,
                    rotationDeg = baseRot,
                    keyframes = cardKeys(pos, restA, restB, baseRot)
                )
            }
            fun textChild(
                id: String, name: String, local: Pair<Float, Float>,
                text: String, fontFrac: Float, color: Long, weight: Int, italic: Boolean = false
            ): TimelineClip {
                val pos = GenieSpec.cardPoint(local.first, local.second, pw, ph)
                val f = fontFrac / 0.11f * 100f
                return base(id, name, LayerType.TEXT, 0xFF3992CEL).copy(
                    text = text,
                    textColor = color,
                    fontWeight = weight,
                    isItalic = italic,
                    scalePercent = f,
                    transformX = pos.first,
                    transformY = pos.second,
                    rotationDeg = baseRot,
                    keyframes = cardKeys(pos, f, 0f, baseRot)
                )
            }
            val photoW = GenieSpec.PHOTO_W_FRAC * pw
            val photoH = GenieSpec.PHOTO_H_FRAC * ph
            val photoPos = GenieSpec.cardPoint(0f, 0f, pw, ph)
            val photoRestA = pctPx(photoW)
            val photoRestB = pctPx(photoH)
            val cornerRho = (GenieSpec.PHOTO_CORNER_FRAC * pw) / (minOf(photoW, photoH) / 2f)
            val photo = base("$idBase-card${i + 1}-photo", "Photo ${GenieSpec.CARD_NAMES[i]}", LayerType.SHAPE, 0xFFAB47BCL).copy(
                fillMode = FILL_IMAGE,
                fillImageUri = photoUris[i],
                fillImageMode = IMG_FILL,
                shapePattern = "ROUNDED_RECT",
                shapeCornerRadius = 65f * cornerRho.coerceIn(0f, 1f),
                shapeScaleX = photoW / box,
                shapeScaleY = photoH / box,
                scalePercent = photoRestA,
                transformX = photoPos.first,
                transformY = photoPos.second,
                rotationDeg = baseRot,
                keyframes = cardKeys(photoPos, photoRestA, photoRestB, baseRot)
            )
            val title = textChild(
                "$idBase-card${i + 1}-title", "Name ${GenieSpec.CARD_NAMES[i]}",
                GenieSpec.TITLE_X_FRAC * pw to GenieSpec.TITLE_Y_FRAC * ph,
                GenieSpec.CARD_TITLES[i], GenieSpec.TITLE_FONT_FRAC, 0xFFFFFFFFL, 800, italic = true
            )
            val pillH = GenieSpec.SUGGEST_H_FRAC * ph
            val suggestPill = shapeChild(
                "$idBase-card${i + 1}-sugpill", "Suggests Pill ${GenieSpec.CARD_NAMES[i]}",
                GenieSpec.PILL_CX_FRAC * pw to GenieSpec.PILL_CY_FRAC * ph,
                GenieSpec.SUGGEST_W_FRAC * pw, pillH, pctPx(pillH)
            ).copy(
                fillMode = FILL_SOLID, textColor = GenieSpec.SUGGEST_PILL,
                shapePattern = "ROUNDED_RECT", shapeCornerRadius = 65f
            )
            val cardPetals = (0 until GenieSpec.PETALS).map { k ->
                val ang = (k * 45).toFloat()
                val a = Math.toRadians((k * 45).toDouble())
                val off = (GenieSpec.CARD_PETAL_RING_FRAC * pw * sin(a)).toFloat() to
                    (-GenieSpec.CARD_PETAL_RING_FRAC * pw * cos(a)).toFloat()
                val local = (GenieSpec.FLOWER_CX_FRAC * pw + off.first) to (GenieSpec.PILL_CY_FRAC * ph + off.second)
                val pos = GenieSpec.cardPoint(local.first, local.second, pw, ph)
                val wPx = GenieSpec.CARD_PETAL_W_FRAC * pw
                val hPx = GenieSpec.CARD_PETAL_H_FRAC * pw
                val restA = pctPx(wPx)
                base("$idBase-card${i + 1}-petal${k + 1}", "Card Flower ${GenieSpec.CARD_NAMES[i]} ${k + 1}", LayerType.SHAPE, 0xFF8D6E63L).copy(
                    fillMode = FILL_SOLID,
                    textColor = 0xFFFFFFFFL,
                    shapePattern = "OVAL",
                    shapeScaleX = wPx / box,
                    shapeScaleY = hPx / box,
                    scalePercent = restA,
                    transformX = pos.first,
                    transformY = pos.second,
                    rotationDeg = baseRot + ang,
                    keyframes = cardKeys(pos, restA, pctPx(hPx), baseRot + ang)
                )
            }
            val suggestsText = textChild(
                "$idBase-card${i + 1}-sug", "Suggests ${GenieSpec.CARD_NAMES[i]}",
                GenieSpec.TEXT_X_FRAC * pw to GenieSpec.SUGGEST_Y_FRAC * ph,
                GenieSpec.SUGGESTS, GenieSpec.SUGGEST_FONT_FRAC, 0xFFFFFFFFL, 600
            )
            val activityText = textChild(
                "$idBase-card${i + 1}-act", "Activity ${GenieSpec.CARD_NAMES[i]}",
                GenieSpec.TEXT_X_FRAC * pw to GenieSpec.ACTIVITY_Y_FRAC * ph,
                GenieSpec.ACTIVITIES[i], GenieSpec.ACTIVITY_FONT_FRAC, GenieSpec.ACTIVITY_GREY, 500
            )
            val inviteH = pillH
            val invitePill = shapeChild(
                "$idBase-card${i + 1}-invitepill", "Invite Pill ${GenieSpec.CARD_NAMES[i]}",
                GenieSpec.INVITE_CX_FRAC * pw to GenieSpec.PILL_CY_FRAC * ph,
                GenieSpec.INVITE_W_FRAC * pw, inviteH, pctPx(inviteH)
            ).copy(
                fillMode = FILL_SOLID, textColor = GenieSpec.INVITE_PILL,
                shapePattern = "ROUNDED_RECT", shapeCornerRadius = 65f
            )
            val inviteText = textChild(
                "$idBase-card${i + 1}-invite", "Invite ${GenieSpec.CARD_NAMES[i]}",
                GenieSpec.INVITE_CX_FRAC * pw to GenieSpec.PILL_CY_FRAC * ph,
                GenieSpec.INVITE, GenieSpec.INVITE_FONT_FRAC, 0xFFFFFFFFL, 700
            )
            val all = listOf(
                inviteText, invitePill, activityText, suggestsText
            ) + cardPetals + listOf(suggestPill, title, photo)
            Card(inviteText, invitePill, activityText, suggestsText, cardPetals, suggestPill, title, photo, all)
        }

        // Lane order (top lane first): cards (front), rings, names, avatars, streak,
        // flower, bar, its shadow, background (last lane = painted first).
        val children = ArrayList<TimelineClip>()
        for (c in cards) children += c.all
        children += rings
        children += avatarNames
        children += avatars
        children += streak
        children += petals
        children += bar
        children += barShadow
        children += background
        val lanesTopFirst = listOf(group) + children
        return Insert(
            group, background, bar, barShadow, streak, petals, avatars, avatarNames,
            rings, cards, children, lanesTopFirst
        )
    }
}
