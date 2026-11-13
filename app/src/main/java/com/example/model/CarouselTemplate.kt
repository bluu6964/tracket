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
 * into the current timeline (see [buildGroup]) holding the whole composition as ordinary
 * children, authored in the template's own units exactly the way
 * [com.example.engine.AlightXmlImporter] authors an imported 720x450 Alight scene
 * (positions (px - centre) x previewW/720, shape box 260 scene px, scalePercent =
 * shapeScale x 100, corner = 65 x radius/half-extent). The children link to the group with
 * the REST reference pose, so the group's own transform IS the fit: [carouselFit] centres
 * the ring on the project canvas and scales it to fit, and ParentRig composes that onto
 * every child — the measured law runs in template units whatever the canvas, and moving /
 * scaling / rotating the one group layer moves the whole carousel. Because the Compose
 * preview and the native export both evaluate [TimelineClip.at] and compose parents through
 * the same ParentRig maths, they draw the identical motion.
 *
 * Build 144 (Sarowar's second correction, after inspecting build 143): the motion is NOT
 * baked into the children any more. Build 142/143 baked ~101 POSITION keys into each of the
 * 16 card/shadow layers — visually right, but the user had no single control over the
 * animation. The group therefore carries a rig layer on the ring centre holding the ENTIRE
 * motion as one ROTATION track: 0 -> 360 degrees over the 5 s loop, keyed every
 * [CarouselSpec.STEP_MS] with each segment carrying the renormalised slice of the measured
 * bezier — the ring's angular law, transferred verbatim to the rig layer's rotation.
 * Every card and shadow is parented to it (ParentRig) at its fixed t=0 ring pose with
 * NO keyframes of its own; the link's ref is the rig layer's rest pose (the ring centre)
 * and the link is rotation-LOCKED (the Parent Helper mechanism): ParentRig then swings
 * the layer's position around the centre by the rig's rotation while the layer's own
 * rotation never inherits it — the cards ride the ring and stay upright by rig
 * construction, and editing the rig layer's rotation track (values, timing, easing)
 * re-times / re-shapes the whole carousel at once.
 *
 * Build 145 (Sarowar's refinement of the driver, same day) briefly made the rig layer a
 * CONTROLLER that cascaded its values through ParentRig. Build 146 (his correction after
 * testing 145: "কন্ট্রোলার তো null er মতন আচরণ করছে" — the Controller behaved like a Null)
 * splits the two jobs by his principle "যেটার কাজ যেখানে সেটা সেখানে রাখবে" — each tool
 * stays where its job is, and nothing unneeded is kept:
 *  - the ORBIT is a transform-driving job, so it belongs to the NULL: the build-144 rig
 *    stands exactly as it was ("Carousel Null", one rotation track, rotation-locked cards).
 *  - the MULTI-EDIT job belongs to the CONTROLLER: the group also holds a "Carousel
 *    Controller", connected to the 16 card/shadow layers purely as their fan-out edit
 *    handle (see [com.example.model.ControllerFanOut]) — neutral values at insert, no
 *    keyframes, no parenting, no driving. Editing it (move / turn / scale / opacity /
 *    blend / effects) rewrites each connected card/shadow in its own place; the Null and
 *    the Background are not connected to it and are never touched by it.
 *
 * One deliberate nuance vs the build-142/143 bake: a shadow twin's 2.5 px offset rotates
 * WITH the ring (its local offset from the null is fixed, so the offset direction turns
 * and its distance from its card stays exactly 2.5 px), where the bake kept the offset
 * pointing straight down the screen. A screen-fixed offset cannot be expressed by a static
 * local pose under a rotating parent; the twin is a 16%-opacity, <=4%-luminance effect, and
 * the rotating offset is what a real Alight rig produces. Card centres, orientation, scale,
 * the background and the law itself are unchanged to the pixel.
 *
 * Each card is a SHAPE layer (ROUNDED_RECT) with an image fill — the app's existing replace
 * path (select card -> Color -> Fill -> Image) swaps the picture, exactly like any shape
 * fill.
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

    /**
     * The null's ROTATION track: the measured angular law as keyframed degrees —
     * 0 at 0 ms rising monotonically to exactly 360 at [CarouselSpec.DURATION_MS], one key
     * every [CarouselSpec.STEP_MS], each segment carrying the renormalised slice of the
     * master bezier for that segment (the same slicing the build-142 position bake used), so
     * the eased law holds at every instant, not only at key times. Keyframe times are
     * relative to the layer start, like every track in the app.
     */
    private fun rotationKeys(): List<Keyframe> {
        val steps = (CarouselSpec.DURATION_MS / CarouselSpec.STEP_MS).toInt()
        return (0..steps).map { j ->
            val tMs = j * CarouselSpec.STEP_MS
            val x = tMs.toDouble() / CarouselSpec.DURATION_MS
            Keyframe(
                timeMs = tMs,
                a = (360.0 * progress(x)).toFloat(),
                easing = if (j < steps) {
                    sliceEasing(x, (tMs + CarouselSpec.STEP_MS).toDouble() / CarouselSpec.DURATION_MS)
                } else Easing.LINEAR
            )
        }
    }

    /**
     * A ring point in stored units: the t=0 pose of the card at ring phase [phaseDeg] (plus
     * [dyScene] scene px of vertical offset, the shadow twin's drop). This is the fixed local
     * pose a card/shadow keeps for the whole loop — the null's rotation does the travelling.
     */
    private fun ringStored(phaseDeg: Double, k: Float, dyScene: Float): Pair<Float, Float> {
        val a = Math.toRadians(phaseDeg)
        val sx = CarouselSpec.CENTER_X + CarouselSpec.RADIUS * cos(a)
        val sy = CarouselSpec.CENTER_Y + CarouselSpec.RADIUS * sin(a) + dyScene
        return ((sx - CarouselSpec.SCENE_W / 2.0) * k).toFloat() to
            ((sy - CarouselSpec.SCENE_H / 2.0) * k).toFloat()
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

    /** What [buildGroup] hands back: the Group layer, the rig's Null layer, the edit handle's
     *  Controller layer, its children, and the lanes to add. */
    data class Insert(
        val group: TimelineClip,
        /** The "Carousel Null" inside the group: the one layer carrying the whole motion. */
        val nullLayer: TimelineClip,
        /** The "Carousel Controller" inside the group: the fan-out edit handle the 16
         *  card/shadow layers are connected to (neutral at insert; it drives nothing). */
        val controller: TimelineClip,
        /** The layers inside the group in lane order (top lane first): the Null, the
         *  Controller, then per card the photo lane above its shadow lane, the Background
         *  last (build 142's track order for the 17 picture layers, with the two rig lanes
         *  on top of them). */
        val children: List<TimelineClip>,
        /** Lanes to place on top of the project, top lane first: the group, then [children]. */
        val lanesTopFirst: List<TimelineClip>
    )

    /**
     * The template as ONE Group layer + its children, ready to insert into the project that is
     * open (build 143 — the build-142 standalone-project builder is gone; build 144 — the
     * null rig; build 146 — the Controller alongside it as the edit handle). [previewWidthPx] /
     * [previewHeightPx] are the editor preview frame's size (the stored-units basis, like
     * the importer); [backgroundUri] / [cardUris] point at the generated background /
     * placeholder pictures (engine TemplateAssets); the group starts at [insertAtMs] (the
     * playhead) and spans the template's 5 s. Every call builds fresh clips under [idBase] —
     * inserting twice gives two independent carousels, and nothing shared is ever mutated.
     *
     * The rig (build 144, restored verbatim by build 146): the group's first child is a
     * Null layer on the ring centre carrying the ONLY keyframes of the composition — the
     * ROTATION track of [rotationKeys]. Cards and shadows link to it with a rotation-locked
     * [ParentLink] whose ref is the null's rest pose (the ring centre, scale 100, rotation
     * 0): ParentRig composes a card as group-fit ∘ (null pose) ∘ (card's fixed offset from
     * the centre, swung by the null's rotation), which is exactly build 143's baked world
     * path, and the lock keeps every card's own rotation at 0 while its position rides.
     * Beside it sits the "Carousel Controller" (build 146): an ordinary Controller layer of
     * the group, neutral (no keyframes, opacity 100, blend Normal, no effects), with the 16
     * cards/shadows CONNECTED to it ([TimelineClip.controllerId]) — their fan-out edit
     * handle and nothing more. The background stays a static child of the group, connected
     * to nothing, as before.
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

        // The rig's heart (build 144, restored verbatim by build 146): a Null layer on the
        // ring centre, a child of the group like every other layer inside (REST link, so
        // the group fit reaches it unchanged), carrying the whole motion as its ROTATION
        // track. Its colour is the app's own Null colour (the importer's NULL_COLOR / the
        // lane colour of LayerType.NULL).
        val nullId = "$idBase-null"
        val centreX = (CarouselSpec.CENTER_X - CarouselSpec.SCENE_W / 2f) * k
        val centreY = (CarouselSpec.CENTER_Y - CarouselSpec.SCENE_H / 2f) * k
        val nullClip = TimelineClip(
            id = nullId,
            name = "Carousel Null",
            type = LayerType.NULL,
            startMs = insertAtMs,
            durationMs = CarouselSpec.DURATION_MS,
            color = 0xFFE8A33DL,
            transformX = centreX,
            transformY = centreY,
            keyframes = mapOf(AnimProp.ROTATION to rotationKeys()),
            groupId = groupId,
            parent = link
        )
        // Cards/shadows link to the null: the ref is its rest pose (ring centre, scale 100,
        // rotation 0) and the link is rotation-LOCKED (Parent Helper): the position follows
        // the null's turn in full, the layer's own rotation never inherits it — upright
        // cards with no keyframes at all.
        val nullLink = ParentLink(nullId, centreX, centreY, 100f, 0f, lockRotate = true)

        // The edit handle (build 146): a Controller layer of the group, parked beside the
        // null on the ring centre with NEUTRAL values (no keyframes, opacity 100, blend
        // Normal, no effects). The 16 cards/shadows below are CONNECTED to it
        // (controllerId): editing it fans the edit out onto each of them in its own place.
        // It parents nothing and drives nothing — the null above does the driving.
        val controllerId = "$idBase-controller"
        val controllerClip = TimelineClip(
            id = controllerId,
            name = "Carousel Controller",
            type = LayerType.CONTROLLER,
            startMs = insertAtMs,
            durationMs = CarouselSpec.DURATION_MS,
            color = 0xFF00B8A9L,
            transformX = centreX,
            transformY = centreY,
            groupId = groupId,
            parent = link
        )

        // Children in lane order (top lane first): the null's lane, the controller's lane,
        // then per card the photo lane above its shadow lane, the Background lane last
        // (last track = painted first — the renderers walk the lanes reversed, and
        // GroupOps keeps a group's children in lane order, so the inserted group paints
        // exactly like the build-142 project did).
        val children = ArrayList<TimelineClip>(19)
        children += nullClip
        children += controllerClip
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
            // Fixed local poses (the t=0 ring positions): the null's rotation does the
            // travelling, so neither layer carries a single keyframe (build 144). Both are
            // connected to the Controller (their fan-out edit handle, build 146).
            val (shX, shY) = ringStored(phase, k, CarouselSpec.SHADOW_DY)
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
                transformX = shX,
                transformY = shY,
                groupId = groupId,
                parent = nullLink,
                controllerId = controllerId
            )
            val (cdX, cdY) = ringStored(phase, k, 0f)
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
                transformX = cdX,
                transformY = cdY,
                groupId = groupId,
                parent = nullLink,
                controllerId = controllerId
            )
            children += card
            children += shadow
        }
        children += background

        // The group's lane sits above its children (the GroupOps.group arrangement).
        val lanesTopFirst = listOf(group) + children
        return Insert(group, nullClip, controllerClip, children, lanesTopFirst)
    }
}
