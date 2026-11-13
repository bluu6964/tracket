package com.example.model

/**
 * The "Social Growth" template (Templates programme, reference commission batch 2,
 * 2026-11-13): Sarowar's uploaded reference video (720x1280, 30 fps, 9.92 s) — a
 * social-media growth promo on a black background: an "UPLOAD" pill is clicked and
 * morphs into a green progress ring that draws itself and earns a checkmark; the
 * title flips CREATE -> POST... -> ELEVATE (turning green at the end); a profile
 * header fades in (circular avatar slot, POSTS and FOLLOWERS counters that climb —
 * slowly at first, then fast) above a 3 x 4 grid of glowing green tiles whose
 * checkmarks pop in one by one. (The written batch-2 note described a different
 * video for this slot; the template follows the video that was actually
 * uploaded — see ROADMAP.)
 *
 * The counters are the app's own Count Up/Down animator (the `counter` text
 * animator, evaluated by CounterEval in both render paths) on plain "0" text
 * layers: the offset tracks carry the counts the video shows (posts 0 -> 59,
 * followers 0 -> 9716, with the same slow-then-fast pacing read off the frames).
 * The progress ring is a stroked circle whose STROKE_TRIM track draws it
 * clockwise from the top. Tiles are gradient-filled rounded rects; each
 * checkmark is two thin rotated rounded rects (a glyph check would risk the
 * emoji font on some phones and could not keep the neon green). The three
 * titles are separate text layers crossfading in sequence. Everything is an
 * ordinary layer in ONE group; no rig.
 *
 * DEVIATIONS from the video, honestly: the mouse cursor that clicks the button
 * is a screen-recording artefact and is not reproduced; the video's POST title
 * animates its dots (POST. / POST.. / POST...) — the template's middle title is
 * a static "POST..."; between the ring and the grid the video holds a single
 * centred tile for a beat — the template crossfades straight to the header +
 * grid; and at the tail the video's grid scrolls and re-pops its rows — the
 * template gives the tiles a soft second opacity wave instead of a scroll.
 */
object SocialGrowthSpec {
    const val DURATION_MS = 10000L

    const val BG = 0xFF000000L
    const val GREEN = 0xFF2EE66BL
    const val GREEN_BRIGHT = 0xFF4DFF7AL
    const val TITLE_GREEN = 0xFF3DFF6EL
    const val LABEL_GREY = 0xFFB9B9C0L

    // ---- titles ----
    val TITLES = listOf("CREATE", "POST...", "ELEVATE")
    const val TITLE_Y_FRAC = -0.345f       // of ph
    const val TITLE_FONT_FRAC = 0.052f

    // ---- upload pill + progress ring (the intro, centre frame) ----
    const val PILL_Y_FRAC = -0.06f         // of ph
    const val PILL_W_FRAC = 0.235f
    const val PILL_H_FRAC = 0.042f         // of ph
    const val RING_Y_FRAC = 0.04f          // of ph
    const val RING_D_FRAC = 0.185f

    // ---- header ----
    const val AVATAR_X_FRAC = -0.235f
    const val HEADER_Y_FRAC = -0.278f      // of ph
    const val AVATAR_D_FRAC = 0.088f
    const val POSTS_X_FRAC = -0.075f
    const val FOLLOWERS_X_FRAC = 0.115f
    const val COUNT_FONT_FRAC = 0.040f
    const val LABEL_FONT_FRAC = 0.020f
    const val LABEL_DY_FRAC = 0.036f       // of ph, number -> its label

    /** Counter keyframes (normalised t -> value) read off the reference frames. */
    val POSTS_KEYS = listOf(
        0.52f to 0f, 0.62f to 2f, 0.70f to 6f, 0.78f to 12f,
        0.85f to 24f, 0.92f to 37f, 1.0f to 59f
    )
    val FOLLOWERS_KEYS = listOf(
        0.52f to 0f, 0.62f to 3f, 0.70f to 13f, 0.78f to 77f,
        0.85f to 896f, 0.92f to 3938f, 1.0f to 9716f
    )

    // ---- tile grid: 3 x 4 ----
    const val COLS = 3
    const val ROWS = 4
    const val TILE_FRAC = 0.168f           // square, of pw
    const val TILE_PITCH_X_FRAC = 0.196f
    const val TILE_PITCH_Y_FRAC = 0.1105f  // of ph
    const val GRID_Y0_FRAC = -0.155f       // of ph, first row's centre
    const val TILE_IN_FIRST_MS = 5200L
    const val TILE_IN_STAGGER_MS = 130L
    const val TILE_IN_MS = 380L
    const val WAVE_FIRST_MS = 8600L
    const val WAVE_STAGGER_MS = 60L

    /** Tile [index]'s centre (row-major), stored px from the canvas centre. */
    fun tilePos(index: Int, pw: Float, ph: Float): Pair<Float, Float> {
        val col = index % COLS
        val row = index / COLS
        return ((col - (COLS - 1) / 2f) * TILE_PITCH_X_FRAC * pw) to
            ((GRID_Y0_FRAC + row * TILE_PITCH_Y_FRAC) * ph)
    }

    /** The Count Up/Down animator with [keys] as its offset track (scale fixed 1). */
    fun counterAnimator(keys: List<Pair<Float, Float>>): TextAnimator = TextAnimator(
        effectId = "com.alightcreative.effects.counter",
        props = mapOf(
            "offset" to AnimValue(
                type = "float",
                keys = keys.map { (t, v) -> AnimKey(t, listOf(v)) }
            ),
            "scale" to AnimValue(type = "float", value = listOf(1f))
        )
    )

    val EASE_CUBIC_OUT = Easing(0.215f, 0.61f, 0.355f, 1f)
    val EASE_CUBIC_IN = Easing(0.55f, 0.055f, 0.675f, 0.19f)
    val EASE_CUBIC_IN_OUT = Easing(0.645f, 0.045f, 0.355f, 1f)
    val EASE_BACK_OUT = Easing(0.175f, 0.885f, 0.32f, 1.2f)
}

object SocialGrowthTemplate {

    /** What [buildGroup] hands back: the Group layer, the named parts, and the lanes. */
    data class Insert(
        val group: TimelineClip,
        val background: TimelineClip,
        val titles: List<TimelineClip>,
        val uploadPill: TimelineClip,
        val uploadText: TimelineClip,
        val ring: TimelineClip,
        /** The ring's checkmark: two thin rounded-rect layers. */
        val ringCheck: List<TimelineClip>,
        val avatar: TimelineClip,
        val avatarRing: TimelineClip,
        val avatarRingGreen: TimelineClip,
        val postsCount: TimelineClip,
        val postsLabel: TimelineClip,
        val followersCount: TimelineClip,
        val followersLabel: TimelineClip,
        val tiles: List<TimelineClip>,
        /** Each tile's checkmark: two thin rounded-rect layers per tile, same order as [tiles]. */
        val tileChecks: List<List<TimelineClip>>,
        /** Every layer inside the group in lane order (top lane first). */
        val children: List<TimelineClip>,
        /** Lanes to place on top of the project, top lane first: the group, then [children]. */
        val lanesTopFirst: List<TimelineClip>
    )

    /**
     * The template as ONE Group layer + its ordinary layers, ready to insert into
     * the project that is open. [avatarUri] feeds the profile picture slot (a
     * generated placeholder; replaced via Color -> Fill -> Image). The group
     * starts at [insertAtMs] and spans [SocialGrowthSpec.DURATION_MS].
     */
    fun buildGroup(
        previewWidthPx: Float,
        previewHeightPx: Float,
        avatarUri: String,
        insertAtMs: Long,
        idBase: String = "growth"
    ): Insert {
        val pw = if (previewWidthPx > 1f) previewWidthPx else 1080f
        val ph = if (previewHeightPx > 1f) previewHeightPx else 1920f
        val box = pw * 130f / 360f
        val dur = SocialGrowthSpec.DURATION_MS

        val groupId = "$idBase-group"
        val link = GroupOps.linkTo(groupId, GroupOps.Pose.REST)
        val group = TimelineClip(
            id = groupId,
            name = "Social Growth",
            type = LayerType.GROUP,
            startMs = insertAtMs,
            durationMs = dur,
            color = 0xFF2EE66BL
        )
        fun base(id: String, name: String, type: LayerType, color: Long): TimelineClip = TimelineClip(
            id = id, name = name, type = type,
            startMs = insertAtMs, durationMs = dur, color = color,
            groupId = groupId, parent = link
        )
        fun pctW(frac: Float): Float = frac * pw / box * 100f
        fun opacityKeys(vararg ks: Pair<Long, Float>): List<Keyframe> {
            val out = ArrayList<Keyframe>(ks.size)
            for ((i, kv) in ks.withIndex()) {
                val easing = when {
                    i == ks.lastIndex -> null
                    ks[i + 1].second > kv.second -> SocialGrowthSpec.EASE_CUBIC_OUT
                    ks[i + 1].second < kv.second -> SocialGrowthSpec.EASE_CUBIC_IN
                    else -> null
                }
                out += Keyframe(kv.first, kv.second, 0f, easing ?: Easing.LINEAR)
            }
            return out
        }

        val background = base("$idBase-bg", "Background", LayerType.SHAPE, 0xFF455A64L).copy(
            fillMode = FILL_SOLID,
            textColor = SocialGrowthSpec.BG,
            shapePattern = "RECT",
            shapeScaleX = pw / box,
            shapeScaleY = ph / box,
            scalePercent = pw / box * 100f
        )

        // Titles: CREATE (0.6-5.3 s), POST... (5.3-7.7 s), ELEVATE (7.7 s -, green).
        val titleWindows = listOf(
            listOf(600L to 0f, 1000L to 100f, 4900L to 100f, 5300L to 0f),
            listOf(5300L to 0f, 5700L to 100f, 7300L to 100f, 7700L to 0f),
            listOf(7700L to 0f, 8100L to 100f)
        )
        val titles = SocialGrowthSpec.TITLES.mapIndexed { i, text ->
            base("$idBase-title${i + 1}", "Title $text", LayerType.TEXT, 0xFF3992CEL).copy(
                text = text,
                textColor = if (i == 2) SocialGrowthSpec.TITLE_GREEN else 0xFFFFFFFFL,
                fontWeight = 800,
                scalePercent = SocialGrowthSpec.TITLE_FONT_FRAC / 0.11f * 100f,
                transformY = SocialGrowthSpec.TITLE_Y_FRAC * ph,
                opacity = 0f,
                keyframes = mapOf(AnimProp.OPACITY to opacityKeys(*titleWindows[i].toTypedArray()))
            )
        }

        // The UPLOAD pill + its label (0.8-2.7 s), then the progress ring (2.6-4.9 s).
        val pillW = SocialGrowthSpec.PILL_W_FRAC * pw
        val pillH = SocialGrowthSpec.PILL_H_FRAC * ph
        val pillY = SocialGrowthSpec.PILL_Y_FRAC * ph
        val uploadKeys = opacityKeys(800L to 0f, 1100L to 100f, 2400L to 100f, 2700L to 0f)
        val uploadPill = base("$idBase-upload", "Upload Button", LayerType.SHAPE, 0xFF78909CL).copy(
            fillMode = FILL_SOLID,
            textColor = 0xFF15151AL,
            shapePattern = "ROUNDED_RECT",
            shapeCornerRadius = 65f,
            shapeScaleX = pillW / box,
            shapeScaleY = pillH / box,
            scalePercent = pillW / box * 100f,
            transformY = pillY,
            strokeColor = 0xFF3A3A42L,
            strokeWidth = 1.0f,
            opacity = 0f,
            keyframes = mapOf(AnimProp.OPACITY to uploadKeys)
        )
        val uploadText = base("$idBase-uploadtext", "Upload Label", LayerType.TEXT, 0xFF3992CEL).copy(
            text = "UPLOAD",
            textColor = 0xFFFFFFFFL,
            fontWeight = 600,
            scalePercent = 0.026f / 0.11f * 100f,
            transformY = pillY,
            opacity = 0f,
            keyframes = mapOf(AnimProp.OPACITY to uploadKeys)
        )

        val ringD = SocialGrowthSpec.RING_D_FRAC * pw
        val ringY = SocialGrowthSpec.RING_Y_FRAC * ph
        val ring = base("$idBase-ring", "Progress Ring", LayerType.SHAPE, 0xFF66BB6AL).copy(
            fillMode = FILL_NONE,
            shapePattern = "CIRCLE",
            shapeScaleX = ringD / box,
            shapeScaleY = ringD / box,
            scalePercent = ringD / box * 100f,
            strokeColor = SocialGrowthSpec.GREEN,
            strokeWidth = 360f * 0.008f,
            transformY = ringY,
            opacity = 0f,
            strokeTrimStart = 0f,
            strokeTrimEnd = 1f,
            keyframes = mapOf(
                AnimProp.OPACITY to opacityKeys(2600L to 0f, 2750L to 100f, 4600L to 100f, 4900L to 0f),
                AnimProp.STROKE_TRIM to listOf(
                    Keyframe(2600L, 0f, 0f, SocialGrowthSpec.EASE_CUBIC_IN_OUT),
                    Keyframe(4200L, 0f, 1f)
                )
            )
        )
        // A checkmark from two thin rounded rects (see the header doc).
        fun checkMark(
            idPrefix: String, namePrefix: String, cx: Float, cy: Float,
            keys: Map<AnimProp, List<Keyframe>>
        ): List<TimelineClip> {
            val segA = base("$idPrefix-a", "$namePrefix A", LayerType.SHAPE, 0xFF66BB6AL).copy(
                fillMode = FILL_SOLID,
                textColor = SocialGrowthSpec.GREEN_BRIGHT,
                shapePattern = "ROUNDED_RECT",
                shapeCornerRadius = 65f,
                shapeScaleX = 0.037f * pw / box,
                shapeScaleY = 0.0095f * pw / box,
                scalePercent = pctW(0.037f),
                transformX = cx - 0.017f * pw,
                transformY = cy + 0.001f * pw,
                rotationDeg = 48f,
                opacity = 0f,
                keyframes = keys
            )
            val segB = base("$idPrefix-b", "$namePrefix B", LayerType.SHAPE, 0xFF66BB6AL).copy(
                fillMode = FILL_SOLID,
                textColor = SocialGrowthSpec.GREEN_BRIGHT,
                shapePattern = "ROUNDED_RECT",
                shapeCornerRadius = 65f,
                shapeScaleX = 0.051f * pw / box,
                shapeScaleY = 0.0095f * pw / box,
                scalePercent = pctW(0.051f),
                transformX = cx + 0.015f * pw,
                transformY = cy - 0.003f * pw,
                rotationDeg = -52f,
                opacity = 0f,
                keyframes = keys
            )
            return listOf(segA, segB)
        }
        val ringCheck = checkMark(
            "$idBase-ringcheck", "Ring Check", 0f, ringY,
            mapOf(AnimProp.OPACITY to opacityKeys(4350L to 0f, 4600L to 100f, 4900L to 100f, 5150L to 0f))
        )

        // Header: avatar + two counters, fading in 5.1-5.6 s; the avatar ring turns
        // green with the ELEVATE title (a grey ring fades out as a green one fades in).
        val headerKeys = opacityKeys(5100L to 0f, 5600L to 100f)
        val headerFade = mapOf(AnimProp.OPACITY to headerKeys)
        val avatarD = SocialGrowthSpec.AVATAR_D_FRAC * pw
        val headerY = SocialGrowthSpec.HEADER_Y_FRAC * ph
        val avatar = base("$idBase-avatar", "Profile Photo", LayerType.SHAPE, 0xFF66BB6AL).copy(
            fillMode = FILL_IMAGE,
            fillImageUri = avatarUri,
            fillImageMode = IMG_FILL,
            shapePattern = "CIRCLE",
            shapeScaleX = avatarD / box,
            shapeScaleY = avatarD / box,
            scalePercent = avatarD / box * 100f,
            transformX = SocialGrowthSpec.AVATAR_X_FRAC * pw,
            transformY = headerY,
            opacity = 0f,
            keyframes = headerFade
        )
        fun avatarRingLayer(id: String, name: String, stroke: Long, keys: List<Keyframe>): TimelineClip {
            val d = avatarD * 1.09f
            return base(id, name, LayerType.SHAPE, 0xFFB0BEC5L).copy(
                fillMode = FILL_NONE,
                shapePattern = "CIRCLE",
                shapeScaleX = d / box,
                shapeScaleY = d / box,
                scalePercent = d / box * 100f,
                strokeColor = stroke,
                strokeWidth = 360f * 0.004f,
                transformX = SocialGrowthSpec.AVATAR_X_FRAC * pw,
                transformY = headerY,
                opacity = 0f,
                keyframes = mapOf(AnimProp.OPACITY to keys)
            )
        }
        val avatarRing = avatarRingLayer(
            "$idBase-avring", "Avatar Ring", 0xFF565660L,
            opacityKeys(5100L to 0f, 5600L to 100f, 7700L to 100f, 8100L to 0f)
        )
        val avatarRingGreen = avatarRingLayer(
            "$idBase-avringgreen", "Avatar Ring Green", SocialGrowthSpec.GREEN,
            opacityKeys(7700L to 0f, 8100L to 100f)
        )
        fun counterText(
            id: String, name: String, x: Float, keys: List<Pair<Float, Float>>
        ): TimelineClip = base(id, name, LayerType.TEXT, 0xFF3992CEL).copy(
            text = "0",
            textColor = 0xFFFFFFFFL,
            fontWeight = 800,
            scalePercent = SocialGrowthSpec.COUNT_FONT_FRAC / 0.11f * 100f,
            transformX = x,
            transformY = headerY - 0.006f * ph,
            opacity = 0f,
            textAnimators = listOf(SocialGrowthSpec.counterAnimator(keys)),
            keyframes = headerFade
        )
        fun counterLabel(id: String, name: String, x: Float, text: String): TimelineClip =
            base(id, name, LayerType.TEXT, 0xFF3992CEL).copy(
                text = text,
                textColor = SocialGrowthSpec.LABEL_GREY,
                fontWeight = 600,
                scalePercent = SocialGrowthSpec.LABEL_FONT_FRAC / 0.11f * 100f,
                transformX = x,
                transformY = headerY + SocialGrowthSpec.LABEL_DY_FRAC * ph,
                opacity = 0f,
                keyframes = headerFade
            )
        val postsCount = counterText("$idBase-posts", "Posts Count", SocialGrowthSpec.POSTS_X_FRAC * pw, SocialGrowthSpec.POSTS_KEYS)
        val postsLabel = counterLabel("$idBase-postslabel", "Posts Label", SocialGrowthSpec.POSTS_X_FRAC * pw, "POSTS")
        val followersCount = counterText("$idBase-followers", "Followers Count", SocialGrowthSpec.FOLLOWERS_X_FRAC * pw, SocialGrowthSpec.FOLLOWERS_KEYS)
        val followersLabel = counterLabel("$idBase-followerslabel", "Followers Label", SocialGrowthSpec.FOLLOWERS_X_FRAC * pw, "FOLLOWERS")

        // The tile grid: gradient tiles + shape checkmarks, popping in one by one,
        // with a soft second opacity wave near the tail.
        val tilePx = SocialGrowthSpec.TILE_FRAC * pw
        val tileRestA = tilePx / box * 100f
        val tiles = ArrayList<TimelineClip>()
        val tileChecks = ArrayList<List<TimelineClip>>()
        for (i in 0 until SocialGrowthSpec.COLS * SocialGrowthSpec.ROWS) {
            val (tx, ty) = SocialGrowthSpec.tilePos(i, pw, ph)
            val tin = SocialGrowthSpec.TILE_IN_FIRST_MS + i * SocialGrowthSpec.TILE_IN_STAGGER_MS
            val wave = SocialGrowthSpec.WAVE_FIRST_MS + i * SocialGrowthSpec.WAVE_STAGGER_MS
            val tileKeys = mapOf(
                AnimProp.SCALE to listOf(
                    Keyframe(tin, tileRestA * 0.3f, tileRestA * 0.3f, SocialGrowthSpec.EASE_BACK_OUT),
                    Keyframe(tin + SocialGrowthSpec.TILE_IN_MS, tileRestA, tileRestA)
                ),
                AnimProp.OPACITY to listOf(
                    Keyframe(tin, 0f, 0f, SocialGrowthSpec.EASE_CUBIC_OUT),
                    Keyframe(tin + SocialGrowthSpec.TILE_IN_MS, 100f, 0f),
                    Keyframe(wave, 100f, 0f, SocialGrowthSpec.EASE_CUBIC_IN),
                    Keyframe(wave + 150L, 55f, 0f, SocialGrowthSpec.EASE_CUBIC_OUT),
                    Keyframe(wave + 320L, 100f, 0f)
                )
            )
            tiles += base("$idBase-tile${i + 1}", "Tile ${i + 1}", LayerType.SHAPE, 0xFF66BB6AL).copy(
                fillMode = FILL_GRADIENT,
                gradType = GRAD_LINEAR,
                gradColor1 = 0xFF081C0DL,
                gradColor2 = 0xFF23C75CL,
                gradX1 = 0.5f, gradY1 = 0f, gradX2 = 0.5f, gradY2 = 1f,
                shapePattern = "ROUNDED_RECT",
                shapeCornerRadius = 65f * (0.012f / (SocialGrowthSpec.TILE_FRAC / 2f)),
                shapeScaleX = tilePx / box,
                shapeScaleY = tilePx / box,
                scalePercent = tileRestA,
                transformX = tx,
                transformY = ty,
                opacity = 0f,
                keyframes = tileKeys
            )
            val checkKeys = mapOf(
                AnimProp.OPACITY to listOf(
                    Keyframe(tin + 120L, 0f, 0f, SocialGrowthSpec.EASE_CUBIC_OUT),
                    Keyframe(tin + SocialGrowthSpec.TILE_IN_MS + 60L, 100f, 0f),
                    Keyframe(wave, 100f, 0f, SocialGrowthSpec.EASE_CUBIC_IN),
                    Keyframe(wave + 150L, 55f, 0f, SocialGrowthSpec.EASE_CUBIC_OUT),
                    Keyframe(wave + 320L, 100f, 0f)
                )
            )
            tileChecks += checkMark("$idBase-tile${i + 1}check", "Tile ${i + 1} Check", tx, ty, checkKeys)
        }

        // Lane order (top lane first): titles, header, tiles + their checks, ring,
        // upload pill, background. (Overlaps are minimal; the checks ride above
        // their tiles, the header above the grid's top edge.)
        val children = ArrayList<TimelineClip>()
        children += titles
        children += followersLabel
        children += followersCount
        children += postsLabel
        children += postsCount
        children += avatarRingGreen
        children += avatarRing
        children += avatar
        for (i in tiles.indices.reversed()) {
            children += tileChecks[i]
            children += tiles[i]
        }
        children += ringCheck
        children += ring
        children += uploadText
        children += uploadPill
        children += background
        val lanesTopFirst = listOf(group) + children
        return Insert(
            group, background, titles, uploadPill, uploadText, ring, ringCheck,
            avatar, avatarRing, avatarRingGreen, postsCount, postsLabel,
            followersCount, followersLabel, tiles, tileChecks, children, lanesTopFirst
        )
    }
}
