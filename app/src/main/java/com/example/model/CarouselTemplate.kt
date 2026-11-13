package com.example.model

import kotlin.math.cos
import kotlin.math.sin

/**
 * The Carousel template (Templates v1, build 142; in-project group model, build 143): 8 photo
 * cards on a rotating ring, matching Sarowar's reference video (720x450, 30 fps, 5.03 s) as
 * measured frame by frame on 2026-11-13 (frames + fit scripts in
 * ~/workspace/after-motion-dev/carousel-ref/):
 *
 *  - exactly ONE revolution per 5.0 s, seamless loop (reference frame 150 == frame 0);
 *  - direction clockwise on screen (a card travels left -> top -> right -> bottom);
 *  - angular law = cubic-bezier(0.78, 0.013, 0.194, 1.0) over the loop (least-squares fit on the
 *    tracked yellow card: RMS 0.38 deg; symmetric easings were 6-20x worse);
 *  - ring centre (364, 224) of 720x450, radius 133.2 px; cards 95x95 px, corner radius 14 px,
 *    upright at all times, constant scale (no depth, no breathing), never overlapping
 *    (adjacent centres 101.8 px apart > the 95 px card);
 *  - shadow: barely there in the reference (<= ~4% luminance dip within ~2 px of the bottom
 *    edge) — modelled as a soft dark twin layer 2.5 px below each card at 16% opacity;
 *  - background: vertical gradient (210,210,210) -> (147,146,151) with a 34 px grid lifted
 *    +8 luminance (vertical lines at x = 19 mod 34, horizontal at y = 18 mod 34).
 *
 * Build 143 (Sarowar's correction of the build-142 model): a template is NOT a separate
 * project. It lives inside the project like any layer — inserting it drops ONE Group layer
 * into the current timeline (see [buildGroup]) holding the whole composition as 17 ordinary
 * children (background + 8 shadow twins + 8 cards), authored in the template's own units
 * exactly the way [com.example.engine.AlightXmlImporter] authors an imported 720x450 Alight
 * scene (positions (px - centre) x previewW/720, shape box 260 scene px, scalePercent =
 * shapeScale x 100, corner = 65 x radius/half-extent). The children link to the group with
 * the REST reference pose, so the group's own transform IS the fit: [carouselFit] centres
 * the ring on the project canvas and scales it to fit, and ParentRig composes that onto
 * every child — the measured law runs in template units whatever the canvas, and moving /
 * scaling / rotating the one group layer moves the whole carousel. Because the Compose
 * preview and the native export both evaluate [TimelineClip.at] and compose parents through
 * the same ParentRig maths, they draw the identical motion.
 *
 * The position keys are baked every [CarouselSpec.STEP_MS] with each segment carrying the
 * renormalised slice of the measured bezier for that segment, so the eased law holds at
 * every instant, not only at key times; the residual radial error is the chord sagitta
 * (<= 0.24 px). Cards carry no ROTATION / SCALE keys: upright and constant size by
 * construction. Each card is a SHAPE layer (ROUNDED_RECT) with an image fill — the app's
 * existing replace path (select card -> Color -> Fill -> Image) swaps the picture, exactly
 * like any shape fill.
 */
object CarouselSpec {
    const val SCENE_W = 720f
    const val SCENE_H = 450f
    const val CENTER_X = 364f
    const val CENTER_Y = 224f
    const val RADIUS = 133.2f
    const val CARD_SIZE = 95f
    const val CARD_CORNER = 14f
    const val CARD_COUNT = 8
    const val DURATION_MS = 5000L
    const val STEP_MS = 50L
    const val SHADOW_DY = 2.5f
    const val SHADOW_OPACITY = 16f

    /** The measured angular easing, cubic-bezier(0.78, 0.013, 0.194, 1.0). */
    const val EASE_X1 = 0.78
    const val EASE_Y1 = 0.013
    const val EASE_X2 = 0.194
    const val EASE_Y2 = 1.0

    val BG_TOP: Long = 0xFFD2D2D2L        // (210, 210, 210)
    val BG_BOTTOM: Long = 0xFF939297L     // (147, 146, 151)
    val CANVAS_COLOR: Long = 0xFFB2B2B4L  // gradient mid (178, 178, 180)
    const val GRID_STEP = 34f
    const val GRID_OFF_X = 19f
    const val GRID_OFF_Y = 18f
    const val GRID_LIFT = 8

    /** Solid colour under each card's picture (shown while / if the picture cannot load). */
    val CARD_TINTS: List<Long> = listOf(
        0xFFEF5350L, 0xFFFFA726L, 0xFFFFEE58L, 0xFF66BB6AL,
        0xFF42A5F5L, 0xFF7E57C2L, 0xFFEC407AL, 0xFF26A69AL
    )
}

object CarouselTemplate {

    // ---- the measured bezier law, in Double (the app's Easing evaluator is the Float twin) ----

    private fun bx(s: Double): Double {
        val u = 1.0 - s
        return 3.0 * u * u * s * CarouselSpec.EASE_X1 + 3.0 * u * s * s * CarouselSpec.EASE_X2 + s * s * s
    }

    private fun by(s: Double): Double {
        val u = 1.0 - s
        return 3.0 * u * u * s * CarouselSpec.EASE_Y1 + 3.0 * u * s * s * CarouselSpec.EASE_Y2 + s * s * s
    }

    /** Bezier parameter whose x is [x] (bisection; bx is monotonic for this curve). */
    private fun paramAtX(x: Double): Double {
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        var lo = 0.0
        var hi = 1.0
        var s = x
        repeat(60) {
            s = (lo + hi) / 2.0
            if (bx(s) < x) lo = s else hi = s
        }
        return s
    }

    /** Loop progress 0..1 at loop fraction [x] 0..1, per the measured easing. */
    fun progress(x: Double): Double {
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        return by(paramAtX(x))
    }

    /** The easing of the master curve restricted to loop fractions [xa]..[xb], renormalised. */
    private fun sliceEasing(xa: Double, xb: Double): Easing {
        val ctrl = arrayOf(
            doubleArrayOf(0.0, 0.0),
            doubleArrayOf(CarouselSpec.EASE_X1, CarouselSpec.EASE_Y1),
            doubleArrayOf(CarouselSpec.EASE_X2, CarouselSpec.EASE_Y2),
            doubleArrayOf(1.0, 1.0)
        )
        val sa = paramAtX(xa)
        val sb = paramAtX(xb)
        val right = subdivide(ctrl, sa).second
        val seg = subdivide(right, (sb - sa) / (1.0 - sa)).first
        val dx = seg[3][0] - seg[0][0]
        val dy = seg[3][1] - seg[0][1]
        if (dx <= 0.0 || dy <= 0.0) return Easing.LINEAR
        return Easing(
            ((seg[1][0] - seg[0][0]) / dx).toFloat(), ((seg[1][1] - seg[0][1]) / dy).toFloat(),
            ((seg[2][0] - seg[0][0]) / dx).toFloat(), ((seg[2][1] - seg[0][1]) / dy).toFloat()
        )
    }

    /** de Casteljau split of a cubic bezier at parameter [t]: (left, right) control polygons. */
    private fun subdivide(
        p: Array<DoubleArray>, t: Double
    ): Pair<Array<DoubleArray>, Array<DoubleArray>> {
        fun lerp(a: DoubleArray, b: DoubleArray) =
            doubleArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
        val q0 = lerp(p[0], p[1]); val q1 = lerp(p[1], p[2]); val q2 = lerp(p[2], p[3])
        val r0 = lerp(q0, q1); val r1 = lerp(q1, q2)
        val s = lerp(r0, r1)
        return arrayOf(p[0], q0, r0, s) to arrayOf(s, r1, q2, p[3])
    }

    /** Ring angle (degrees, screen-clockwise) of card [cardIndex] at loop time [tMs]. */
    fun angleDegAt(tMs: Long, cardIndex: Int): Double =
        cardIndex * 45.0 + 360.0 * progress(tMs.toDouble() / CarouselSpec.DURATION_MS)

    /** Card centre in reference scene pixels (720x450) at loop time [tMs]. */
    fun centerSceneAt(tMs: Long, cardIndex: Int): Pair<Float, Float> {
        val a = Math.toRadians(angleDegAt(tMs, cardIndex))
        return (CarouselSpec.CENTER_X + CarouselSpec.RADIUS * cos(a)).toFloat() to
            (CarouselSpec.CENTER_Y + CarouselSpec.RADIUS * sin(a)).toFloat()
    }

    private fun positionKeys(phaseDeg: Double, k: Float, dyScene: Float): List<Keyframe> {
        val steps = (CarouselSpec.DURATION_MS / CarouselSpec.STEP_MS).toInt()
        return (0..steps).map { j ->
            val tMs = j * CarouselSpec.STEP_MS
            val x = tMs.toDouble() / CarouselSpec.DURATION_MS
            val a = Math.toRadians(phaseDeg + 360.0 * progress(x))
            val sx = CarouselSpec.CENTER_X + CarouselSpec.RADIUS * cos(a)
            val sy = CarouselSpec.CENTER_Y + CarouselSpec.RADIUS * sin(a) + dyScene
            Keyframe(
                timeMs = tMs,
                a = ((sx - CarouselSpec.SCENE_W / 2.0) * k).toFloat(),
                b = ((sy - CarouselSpec.SCENE_H / 2.0) * k).toFloat(),
                easing = if (j < steps) {
                    sliceEasing(x, (tMs + CarouselSpec.STEP_MS).toDouble() / CarouselSpec.DURATION_MS)
                } else Easing.LINEAR
            )
        }
    }

    /**
     * The fit of the template onto a project canvas of [previewWidthPx] x [previewHeightPx]
     * stored units: the Group layer's (transformX, transformY, scalePercent / 100).
     *
     * The children are authored against the template frame (720x450 at [CarouselSpec]'s own
     * basis). ParentRig composes a child pose as groupPos + (childPos - refPos) x fit with
     * the link's ref at the REST pose (0, 0), so the ring centre — which sits at stored
     * ((364 - 360) k, (224 - 225) k) with k = previewWidthPx / 720 — lands on the canvas
     * centre when groupPos = -(ring centre) x fit, and the whole composition scales by the
     * fit. The fit fills 96% of the tighter canvas dimension with the template frame:
     * fit = 0.96 x min(canvasW / 720, canvasH / 450) / k, which in canvas fractions is just
     * 0.96 x min(1, canvasAspect / (720/450)) for width and the mirror for height — the ring
     * (radius 133.2 + half a card = 180.7 of the 450 height) therefore stays fully in frame
     * on any canvas, with a 2% margin on the tight axis.
     */
    fun carouselFit(previewWidthPx: Float, previewHeightPx: Float): Triple<Float, Float, Float> {
        val pw = if (previewWidthPx > 1f) previewWidthPx else CarouselSpec.SCENE_W
        val ph = if (previewHeightPx > 1f) previewHeightPx else pw * CarouselSpec.SCENE_H / CarouselSpec.SCENE_W
        val k = pw / CarouselSpec.SCENE_W
        val fit = 0.96f * minOf(pw / CarouselSpec.SCENE_W, ph / CarouselSpec.SCENE_H) / k
        val cx = (CarouselSpec.CENTER_X - CarouselSpec.SCENE_W / 2f) * k
        val cy = (CarouselSpec.CENTER_Y - CarouselSpec.SCENE_H / 2f) * k
        return Triple(-cx * fit, -cy * fit, fit)
    }

    /** What [buildGroup] hands back: the Group layer, its 17 children, and the lanes to add. */
    data class Insert(
        val group: TimelineClip,
        /** The children in lane order (top lane first, exactly build 142's track order):
         *  Photo 1, Card 1 shadow, …, Photo 8, Card 8 shadow, Background last. */
        val children: List<TimelineClip>,
        /** Lanes to place on top of the project, top lane first: the group, then [children]. */
        val lanesTopFirst: List<TimelineClip>
    )

    /**
     * The template as ONE Group layer + its children, ready to insert into the project that is
     * open (build 143 — the build-142 standalone-project builder is gone). [previewWidthPx] /
     * [previewHeightPx] are the editor preview frame's size (the stored-units basis, like the
     * importer); [backgroundUri] / [cardUris] point at the generated background / placeholder
     * pictures (engine TemplateAssets); the group starts at [insertAtMs] (the playhead) and
     * spans the template's 5 s. Every call builds fresh clips under [idBase] — inserting
     * twice gives two independent carousels, and nothing shared is ever mutated.
     */
    fun buildGroup(
        previewWidthPx: Float,
        previewHeightPx: Float,
        backgroundUri: String,
        cardUris: List<String>,
        insertAtMs: Long,
        idBase: String = "carousel"
    ): Insert {
        require(cardUris.size == CarouselSpec.CARD_COUNT) { "carousel needs 8 card pictures" }
        val pw = if (previewWidthPx > 1f) previewWidthPx else CarouselSpec.SCENE_W
        val k = pw / CarouselSpec.SCENE_W
        // The importer's shape box for a 720-wide scene: 720 x 130/360 = 260 scene px.
        val boxPx = CarouselSpec.SCENE_W * 130f / 360f
        val cardScale = CarouselSpec.CARD_SIZE / boxPx
        val cornerStored = 65f * CarouselSpec.CARD_CORNER / (CarouselSpec.CARD_SIZE / 2f)

        val groupId = "$idBase-group"
        val link = GroupOps.linkTo(groupId, GroupOps.Pose.REST)
        val (gx, gy, fit) = carouselFit(previewWidthPx, previewHeightPx)
        val group = TimelineClip(
            id = groupId,
            name = "Carousel",
            type = LayerType.GROUP,
            startMs = insertAtMs,
            durationMs = CarouselSpec.DURATION_MS,
            color = 0xFF5BB85DL,
            transformX = gx,
            transformY = gy,
            scalePercent = fit * 100f
        )

        // Children in build 142's lane order (top lane first): per card the photo lane above
        // its shadow lane, the Background lane last (last track = painted first — the
        // renderers walk the lanes reversed, and GroupOps keeps a group's children in lane
        // order, so the inserted group paints exactly like the build-142 project did).
        val children = ArrayList<TimelineClip>(17)
        // Background: a generated gradient+grid picture on a frame-covering rect (10 scene px
        // of bleed per side), in the template frame's own coordinates.
        val bgScaleX = (CarouselSpec.SCENE_W + 20f) / boxPx
        val bgScaleY = (CarouselSpec.SCENE_H + 20f) / boxPx
        val background = TimelineClip(
            id = "$idBase-bg",
            name = "Background",
            type = LayerType.SHAPE,
            startMs = insertAtMs,
            durationMs = CarouselSpec.DURATION_MS,
            color = 0xFFB2B2B4L,
            fillMode = FILL_IMAGE,
            fillImageUri = backgroundUri,
            fillImageMode = IMG_FILL,
            shapePattern = "RECT",
            shapeScaleX = bgScaleX,
            shapeScaleY = bgScaleY,
            scalePercent = bgScaleX * 100f,
            groupId = groupId,
            parent = link
        )
        for (i in 0 until CarouselSpec.CARD_COUNT) {
            val phase = i * 45.0
            val shadowKeys = positionKeys(phase, k, CarouselSpec.SHADOW_DY)
            val shadow = TimelineClip(
                id = "$idBase-shadow-${i + 1}",
                name = "Card ${i + 1} shadow",
                type = LayerType.SHAPE,
                startMs = insertAtMs,
                durationMs = CarouselSpec.DURATION_MS,
                color = 0xFF9E9E9EL,
                fillMode = FILL_SOLID,
                textColor = 0xFF000000L,
                opacity = CarouselSpec.SHADOW_OPACITY,
                shapePattern = "ROUNDED_RECT",
                shapeScaleX = cardScale,
                shapeScaleY = cardScale,
                scalePercent = cardScale * 100f,
                shapeCornerRadius = cornerStored,
                transformX = shadowKeys.first().a,
                transformY = shadowKeys.first().b,
                keyframes = mapOf(AnimProp.POSITION to shadowKeys),
                groupId = groupId,
                parent = link
            )
            val cardKeys = positionKeys(phase, k, 0f)
            val card = TimelineClip(
                id = "$idBase-card-${i + 1}",
                name = "Photo ${i + 1}",
                type = LayerType.SHAPE,
                startMs = insertAtMs,
                durationMs = CarouselSpec.DURATION_MS,
                color = 0xFF90A4AEL,
                fillMode = FILL_IMAGE,
                fillImageUri = cardUris[i],
                fillImageMode = IMG_FILL,
                textColor = CarouselSpec.CARD_TINTS[i],
                shapePattern = "ROUNDED_RECT",
                shapeScaleX = cardScale,
                shapeScaleY = cardScale,
                scalePercent = cardScale * 100f,
                shapeCornerRadius = cornerStored,
                transformX = cardKeys.first().a,
                transformY = cardKeys.first().b,
                keyframes = mapOf(AnimProp.POSITION to cardKeys),
                groupId = groupId,
                parent = link
            )
            children += card
            children += shadow
        }
        children += background

        // The group's lane sits above its children (the GroupOps.group arrangement).
        val lanesTopFirst = listOf(group) + children
        return Insert(group, children, lanesTopFirst)
    }
}
