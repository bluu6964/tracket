package com.example

import com.example.engine.ParentRig
import com.example.model.AnimProp
import com.example.model.CarouselParams
import com.example.model.CarouselSpec
import com.example.model.CarouselTemplate
import com.example.model.ClipFx
import com.example.model.ControllerFanOut
import com.example.model.Easing
import com.example.model.FILL_IMAGE
import com.example.model.FILL_SOLID
import com.example.model.GroupOps
import com.example.model.IMG_FILL
import com.example.model.Keyframe
import com.example.model.LayerType
import com.example.model.ParentLink
import com.example.model.TimelineClip
import com.example.model.at
import com.example.model.renderAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Builds 142-144 + 146 (Templates v1, the in-project group rework, the NULL-RIG rework,
 * the Controller fork): the Carousel template's measured motion law, pinned against the
 * model it inserts. The reference law (720x450, 30 fps, 5.03 s; fit in
 * after-motion-dev/carousel-ref): one clockwise revolution per 5.0 s, seamless, eased by
 * cubic-bezier(0.78, 0.013, 0.194, 1.0); ring centre (364, 224), radius 133.2; cards
 * 95x95, corner 14, upright, constant scale. Expected values here are recomputed from
 * those spec numbers with the app's own pre-existing [Easing] evaluator — an independent
 * code path from the builder's Double bezier.
 *
 * Build 143 model (Sarowar's correction): inserting the template adds ONE Group layer to
 * the project at the playhead. The group's own transform is the canvas fit (see
 * [CarouselTemplate.carouselFit]); the composed pins below run the children through
 * ParentRig — the same composition the preview and the export use.
 *
 * Build 144 rig (Sarowar's second correction: the animation must be driven by a rig layer
 * in the timeline, not by keys baked into every child): the group's first child is a NULL
 * on the ring centre carrying the ENTIRE motion as one ROTATION track (the measured law
 * as degrees); the 8 cards + 8 shadow twins are parented to it at their fixed t=0 ring
 * poses with rotation-locked links and NO keyframes of their own; the Background is a
 * static child of the group.
 *
 * Build 146 (Sarowar's fork decision, after build 145 made the driver a render-time
 * cascading Controller — "কন্ট্রোলার তো null er মতন আচরণ করছে"): each tool stays where its
 * job is. The orbit is a transform job -> the build-144 NULL rig stands verbatim. The
 * multi-edit job is the CONTROLLER's: the group also holds a "Carousel Controller",
 * neutral at insert, connected to the 16 card/shadow layers purely as their fan-out edit
 * handle ([ControllerFanOut]) — it parents nothing, drives nothing, and ParentRig ignores
 * controller links entirely. The last pins below prove the division of labour: the null
 * alone moves the ring, the controller's presence changes no motion, and a controller
 * edit fans out to exactly the 16 connected layers, each in its own place.
 *
 * Build 154 (Templates v2 — the parameterised insert): [CarouselParams] — card count
 * 4-12, one turn in 2-10 s, clockwise / counter-clockwise. The last pins below prove the
 * defaults build the build-146 group EXACTLY (deep equality), the ring-radius law
 * (r(N) = max(133.2, (95 x 1.04 / 2) / sin(pi / N))), the scaled structure / connections,
 * the duration-scaled null track, the counter-clockwise negation, and the grown ring's
 * in-frame fit at 16:9 and 9:16.
 */
class CarouselTemplateTest {

    private val pw = 1440f               // preview width the insert is authored against
    private val ph = 810f                // a 16:9 canvas at that width
    private val k = pw / 720f            // stored units per scene px (= 2)
    private val at0 = 2000L              // the playhead the insert lands on
    private val bgUri = "content://test/bg.png"
    private val cardUris = (1..8).map { "content://test/card$it.png" }
    private val ins = CarouselTemplate.buildGroup(pw, ph, bgUri, cardUris, at0, idBase = "test")
    private val children = ins.children
    private val cards = children.filter { it.id.startsWith("test-card-") }
    private val shadows = children.filter { it.id.startsWith("test-shadow-") }
    private val law = Easing(0.78f, 0.013f, 0.194f, 1f)

    /** The insert placed in an otherwise empty project: one lane per clip, group lane on top. */
    private val projectTracks = ins.lanesTopFirst.mapIndexed { i, c ->
        com.example.model.TimelineTrack("lane-$i", c.name, clips = listOf(c))
    }
    private val allClips = projectTracks.flatMap { it.clips }
    private val rig = ParentRig(allClips) { c, t -> c.renderAt(t) }

    /** Child centre in TEMPLATE scene px (720x450 space): undo the group fit, then the stored-units basis. */
    private fun templateSceneOf(clip: TimelineClip, tMs: Long): Pair<Double, Double> {
        val a = rig.adjusted(clip, tMs)
        val (_, _, fit) = CarouselTemplate.carouselFit(pw, ph)
        val localX = (a.transformX - ins.group.transformX) / fit
        val localY = (a.transformY - ins.group.transformY) / fit
        return (localX / k + 360.0) to (localY / k + 225.0)
    }

    private fun expectedCenter(loopMs: Long, index: Int): Pair<Double, Double> {
        val ang = Math.toRadians(index * 45.0 + 360.0 * law.apply(loopMs / 5000f))
        return (364.0 + 133.2 * cos(ang)) to (224.0 + 133.2 * sin(ang))
    }

    @Test
    fun insertIsOneGroupWithNineteenChildrenInOrder() {
        // Exactly ONE top-level layer is added: the group. The 19 children are inside it.
        assertEquals(20, allClips.size)
        val topLevel = GroupOps.scopeTracks(projectTracks, null).flatMap { it.clips }
        assertEquals(listOf(ins.group.id), topLevel.map { it.id })
        assertEquals(LayerType.GROUP, ins.group.type)
        assertEquals("Carousel", ins.group.name)
        assertEquals(at0, ins.group.startMs)
        assertEquals(5000L, ins.group.durationMs)
        // Lane order (top lane first): the null's lane, the controller's lane, then build
        // 142's track order for the picture layers — per card the photo lane above its
        // shadow lane, Background last.
        val expected = ArrayList<String>()
        expected += "test-null"
        expected += "test-controller"
        for (i in 1..8) {
            expected += "test-card-$i"
            expected += "test-shadow-$i"
        }
        expected += "test-bg"
        assertEquals(expected, children.map { it.id })
        assertEquals(children.map { it.id }.toSet(), GroupOps.childrenOf(projectTracks, ins.group.id).map { it.id }.toSet())
        // Every child: an ordinary clip of the group, spanning the whole template.
        for (c in children) {
            assertEquals(ins.group.id, c.groupId)
            assertEquals(at0, c.startMs)
            assertEquals(5000L, c.durationMs)
        }
        // The null: an ordinary Null layer of the group (REST link, like the background),
        // parked on the ring centre in stored units — (364-360, 224-225) x k.
        val n = ins.nullLayer
        assertEquals(LayerType.NULL, n.type)
        assertEquals("Carousel Null", n.name)
        assertEquals(children.first().id, n.id)
        assertEquals(4f * k, n.transformX, 0.01f)
        assertEquals(-1f * k, n.transformY, 0.01f)
        val nLink = n.parent ?: error("the null has no parent link")
        assertEquals(ins.group.id, nLink.id)
        assertEquals(0f, nLink.refX, 0f)
        assertEquals(0f, nLink.refY, 0f)
        assertEquals(100f, nLink.refScale, 0f)
        assertEquals(0f, nLink.refRot, 0f)
        // The controller: an ordinary Controller layer of the group (REST link), parked on
        // the ring centre too, NEUTRAL — no keyframes, opacity 100, blend Normal, no
        // effects, connected to nothing itself.
        val ctrl = ins.controller
        assertEquals(LayerType.CONTROLLER, ctrl.type)
        assertEquals("Carousel Controller", ctrl.name)
        assertEquals(children[1].id, ctrl.id)
        assertEquals(4f * k, ctrl.transformX, 0.01f)
        assertEquals(-1f * k, ctrl.transformY, 0.01f)
        val cLink = ctrl.parent ?: error("the controller has no parent link")
        assertEquals(ins.group.id, cLink.id)
        assertEquals(100f, cLink.refScale, 0f)
        assertTrue("controller carries no keyframes", ctrl.keyframes.isEmpty())
        assertEquals(100f, ctrl.opacity, 0f)
        assertEquals("Normal", ctrl.blendMode)
        assertTrue("controller carries no effects", ctrl.effects.isEmpty())
        assertEquals(null, ctrl.controllerId)
        // Cards + shadows: SHAPE layers parented to the NULL — ref = its rest pose
        // (ring centre, scale 100, rotation 0), rotation LOCKED (the Parent Helper lock
        // that keeps them upright while their positions ride the null's turn) — AND
        // connected to the Controller (their fan-out edit handle).
        for (c in cards + shadows) {
            assertEquals(LayerType.SHAPE, c.type)
            val link = c.parent ?: error("child ${c.id} has no parent link")
            assertEquals(n.id, link.id)
            assertEquals(4f * k, link.refX, 0.01f)
            assertEquals(-1f * k, link.refY, 0.01f)
            assertEquals(100f, link.refScale, 0f)
            assertEquals(0f, link.refRot, 0f)
            assertTrue("card/shadow link must be rotation-locked: ${c.id}", link.lockRotate)
            assertEquals("connected to the controller: ${c.id}", ctrl.id, c.controllerId)
        }
        // The background stays a static child of the group (REST ref pose), connected to
        // nothing, as in build 143.
        val bg = children.single { it.id == "test-bg" }
        val bgLink = bg.parent ?: error("background has no parent link")
        assertEquals(ins.group.id, bgLink.id)
        assertEquals(0f, bgLink.refX, 0f)
        assertEquals(0f, bgLink.refY, 0f)
        assertEquals(100f, bgLink.refScale, 0f)
        assertEquals(0f, bgLink.refRot, 0f)
        assertEquals(null, bg.controllerId)
        assertEquals(null, n.controllerId)
        // Placement read-back: the group's children come back in exactly the builder's lane order.
        assertEquals(
            children.map { it.id },
            GroupOps.childrenOf(projectTracks, ins.group.id).map { it.id }
        )
    }

    @Test
    fun cardGeometryMatchesTheMeasuredCard() {
        for ((i, c) in cards.withIndex()) {
            assertEquals("ROUNDED_RECT", c.shapePattern)
            assertEquals(95f / 260f, c.shapeScaleX, 1e-5f)
            assertEquals(95f / 260f, c.shapeScaleY, 1e-5f)
            assertEquals(95f / 260f * 100f, c.scalePercent, 1e-3f)
            // Stored corner = 65 x radius / half-extent = 65 x 14 / 47.5.
            assertEquals(65f * 14f / 47.5f, c.shapeCornerRadius, 0.01f)
            // A replaceable image slot, in the exact state the Color panel's image pick writes.
            assertEquals(FILL_IMAGE, c.fillMode)
            assertEquals(IMG_FILL, c.fillImageMode)
            assertEquals(cardUris[i], c.fillImageUri)
            assertEquals("Photo ${i + 1}", c.name)
        }
    }

    @Test
    fun backgroundCoversTheTemplateFrame() {
        val bg = children.single { it.id == "test-bg" }
        assertEquals("RECT", bg.shapePattern)
        assertEquals(FILL_IMAGE, bg.fillMode)
        assertEquals(bgUri, bg.fillImageUri)
        // Drawn size = shapeScale x the 260 scene-px box: 740x470 scene px over a 720x450 frame.
        assertEquals(740f, bg.shapeScaleX * 260f, 0.1f)
        assertEquals(470f, bg.shapeScaleY * 260f, 0.1f)
        assertEquals(0f, bg.transformX, 0f)
        assertEquals(0f, bg.transformY, 0f)
    }

    @Test
    fun progressIsTheMeasuredBezier() {
        assertEquals(0.0, CarouselTemplate.progress(0.0), 0.0)
        assertEquals(1.0, CarouselTemplate.progress(1.0), 0.0)
        // Cross-check the builder's Double bezier against the app's Float Easing evaluator.
        for (x in listOf(0.05, 0.13, 0.25, 0.4, 0.5, 0.62, 0.75, 0.88, 0.97)) {
            assertEquals("progress($x)", law.apply(x.toFloat()).toDouble(), CarouselTemplate.progress(x), 0.002)
        }
        // The law's shape: slow start (only ~5% of the turn in the first quarter of the time),
        // fast middle sweep, long settle — at half time the ring is at 55.1% of the turn.
        assertEquals(0.551, CarouselTemplate.progress(0.5), 0.01)
        assertEquals(0.0547, CarouselTemplate.progress(0.25), 0.01)
        assertEquals(0.9542, CarouselTemplate.progress(0.75), 0.01)
        var prev = -1.0
        for (i in 0..50) {
            val p = CarouselTemplate.progress(i / 50.0)
            assertTrue("monotonic at $i", p > prev)
            prev = p
        }
    }

    @Test
    fun cardCentresRideTheRingPerTheLaw() {
        // Children evaluated through the rig chain card -> null -> group (ParentRig),
        // un-fitted back into template units. Rotation is rigid, so there is no chord
        // sagitta: the radius is exact, and the only residual is the tangential error of
        // the null track's slice easings vs the master law (same class as the build-142/143
        // bake, well inside 1 px mid-segment).
        for (loopMs in listOf(0L, 137L, 1250L, 2500L, 2625L, 3750L, 4999L)) {
            for (i in 0..7) {
                val (sx, sy) = templateSceneOf(cards[i], at0 + loopMs)
                val (ex, ey) = expectedCenter(loopMs, i)
                assertEquals("card $i radius at $loopMs", 133.2, hypot(sx - 364.0, sy - 224.0), 0.35)
                assertEquals("card $i x at $loopMs", ex, sx, 1.2)
                assertEquals("card $i y at $loopMs", ey, sy, 1.2)
            }
        }
        // Exactly on keys the centres are the circle points themselves.
        for (loopMs in listOf(0L, 1000L, 2000L, 3000L, 4000L)) {
            for (i in 0..7) {
                val (sx, sy) = templateSceneOf(cards[i], at0 + loopMs)
                val (ex, ey) = expectedCenter(loopMs, i)
                assertEquals("key card $i x at $loopMs", ex, sx, 0.35)
                assertEquals("key card $i y at $loopMs", ey, sy, 0.35)
            }
        }
    }

    @Test
    fun directionIsClockwiseAndOneRevolution() {
        // Card 0 starts at angle 0 (right of centre); a quarter loop later it must be at the
        // BOTTOM on screen (angle +90 in y-down terms) because the ring turns clockwise.
        val (x0, y0) = templateSceneOf(cards[0], at0)
        assertEquals(364.0 + 133.2, x0, 0.35)
        assertEquals(224.0, y0, 0.35)
        val quarter = (0..200).map { it * 25L }.firstOrNull { t ->
            abs(CarouselTemplate.progress(t / 5000.0) - 0.25) < 0.002
        } ?: error("no quarter time")
        val (qx, qy) = templateSceneOf(cards[0], at0 + quarter)
        val ang = Math.toDegrees(atan2(qy - 224.0, qx - 364.0))
        assertEquals(90.0, ang, 2.0)
        // A full loop later every card is back where it started (seamless).
        for (i in 0..7) {
            val (ax, ay) = templateSceneOf(cards[i], at0)
            val (bx, by) = templateSceneOf(cards[i], at0 + 5000L)
            assertEquals(ax, bx, 1e-3)
            assertEquals(ay, by, 1e-3)
        }
    }

    @Test
    fun cardsStayUprightAtConstantScale() {
        // Build 144: no baked keys anywhere on the picture layers — the cards and shadows
        // hold their fixed t=0 ring poses and the null does all the travelling.
        for ((i, c) in (cards + shadows).withIndex()) {
            assertTrue("no keyframes at all on ${c.id}", c.keyframes.isEmpty())
            assertEquals(0f, c.rotationDeg, 0f)
            // The stored pose IS the t=0 ring point (in stored units).
            val idx = if (c.id.contains("card")) i else i - 8
            val a = Math.toRadians(idx * 45.0)
            val dy = if (c.id.contains("shadow")) CarouselSpec.SHADOW_DY else 0f
            assertEquals(((364.0 + 133.2 * cos(a) - 360.0) * k).toFloat(), c.transformX, 0.05f)
            assertEquals(((224.0 + 133.2 * sin(a) + dy - 225.0) * k).toFloat(), c.transformY, 0.05f)
        }
        // Through the rig the cards still never turn and never change size: the rotation
        // lock holds their own rotation at 0 while the null swings their positions, and
        // the composed scale is just their stored scale x the group fit.
        val (_, _, fit) = CarouselTemplate.carouselFit(pw, ph)
        for (c in cards + shadows) {
            for (t in listOf(at0, at0 + 1234L, at0 + 2500L, at0 + 4999L)) {
                val a = rig.adjusted(c, t)
                assertEquals("upright at $t: ${c.id}", 0f, a.rotationDeg, 0f)
                assertEquals("constant scale at $t: ${c.id}", c.scalePercent * fit, a.scalePercent, 0.01f)
            }
        }
    }

    @Test
    fun shadowsTrailTheirCardsByTheMeasuredOffset() {
        for (i in 0..7) {
            val s = shadows.single { it.id == "test-shadow-${i + 1}" }
            assertEquals(FILL_SOLID, s.fillMode)
            assertEquals(CarouselSpec.SHADOW_OPACITY, s.opacity, 0f)
            for (loopMs in listOf(0L, 1750L, 3200L, 4999L)) {
                val (cx, cy) = templateSceneOf(cards[i], at0 + loopMs)
                val (sx, sy) = templateSceneOf(s, at0 + loopMs)
                // Build 144: the twin rides the same null as its card, so the offset is the
                // measured 2.5 px drop ROTATED by the null's own rotation (a static local
                // offset under a rotating parent — exactly what a real Alight rig
                // produces): the distance from the card is exactly the drop, in the rig's
                // direction, whatever the card's own ring phase.
                val th = Math.toRadians(360.0 * CarouselTemplate.progress(loopMs / 5000.0))
                assertEquals("shadow $i dx at $loopMs", -CarouselSpec.SHADOW_DY * sin(th), sx - cx, 0.05)
                assertEquals("shadow $i dy at $loopMs", CarouselSpec.SHADOW_DY * cos(th), sy - cy, 0.05)
                assertEquals(
                    "shadow $i distance at $loopMs",
                    CarouselSpec.SHADOW_DY.toDouble(), hypot(sx - cx, sy - cy), 0.05
                )
            }
        }
    }

    @Test
    fun nullLayerCarriesTheOnlyMotionTrack() {
        // The null's ROTATION track is the measured law in degrees: 101 keys, 0 -> 360 over
        // the 5 s, cross-checked against the app's Float Easing evaluator (independent path).
        val keys = ins.nullLayer.keyframes.getValue(AnimProp.ROTATION)
        assertEquals(1, ins.nullLayer.keyframes.size)
        assertEquals(101, keys.size)
        assertEquals(0L, keys.first().timeMs)
        assertEquals(5000L, keys.last().timeMs)
        assertEquals(0f, keys.first().a, 0f)
        assertEquals(360f, keys.last().a, 0f)
        var prev = -1f
        for (key in keys) {
            assertEquals(key.timeMs % 50L, 0L)
            val want = 360.0 * law.apply(key.timeMs / 5000f).toDouble()
            assertEquals("null rotation at ${key.timeMs}", want, key.a.toDouble(), 1.0)
            assertTrue("monotonic at ${key.timeMs}", key.a > prev)
            prev = key.a
        }
        // And it is the ONLY motion in the group: the group itself, the controller and
        // every picture child carry no keyframes at all — no POSITION keys anywhere, no
        // second rotation track.
        assertTrue("group has no keys", ins.group.keyframes.isEmpty())
        for (c in children) {
            if (c.id == ins.nullLayer.id) continue
            assertTrue("no keys on ${c.id}", c.keyframes.isEmpty())
        }
        for (c in allClips) {
            assertTrue("no POSITION keys on ${c.id}", c.keyframes[AnimProp.POSITION].isNullOrEmpty())
        }
    }

    @Test
    fun editingTheNullRotationMovesEveryCard() {
        // The driving property (build 144): the NULL alone drives the ring. Replace its
        // track by one fixed rotation of 137 degrees — every card must sit on the ring at
        // its phase + 137, at any time, with the radius untouched.
        val fixed = ins.nullLayer.copy(rotationDeg = 137f, keyframes = emptyMap())
        val clips = ins.lanesTopFirst.map { if (it.id == fixed.id) fixed else it }
        val rig2 = ParentRig(clips) { c, t -> c.renderAt(t) }
        val (_, _, fit) = CarouselTemplate.carouselFit(pw, ph)
        fun sceneOf(clip: TimelineClip, tMs: Long): Pair<Double, Double> {
            val a = rig2.adjusted(clip, tMs)
            return ((a.transformX - ins.group.transformX) / fit / k + 360.0) to
                ((a.transformY - ins.group.transformY) / fit / k + 225.0)
        }
        for (t in listOf(at0, at0 + 2600L)) {
            for (i in 0..7) {
                val card = clips.single { it.id == "test-card-${i + 1}" }
                val (sx, sy) = sceneOf(card, t)
                val ang = Math.toRadians(i * 45.0 + 137.0)
                assertEquals("moved card $i x at $t", 364.0 + 133.2 * cos(ang), sx, 0.35)
                assertEquals("moved card $i y at $t", 224.0 + 133.2 * sin(ang), sy, 0.35)
                assertEquals("moved card $i radius at $t", 133.2, hypot(sx - 364.0, sy - 224.0), 0.35)
            }
        }
    }

    /**
     * The fit rule (see [CarouselTemplate.carouselFit]): on a 16:9 canvas the template's
     * height is the tight axis — the composition fills 96% of the canvas height, centred;
     * on 9:16 the width is tight. Either way the ring (radius 133.2 + half card 47.5 =
     * 180.7 scene px around the centre) stays fully in frame, and the ring centre lands
     * exactly on the canvas centre.
     */
    @Test
    fun groupFitCentresTheRingAndKeepsItInFrameOn169() {
        val ins169 = CarouselTemplate.buildGroup(1920f, 1080f, bgUri, cardUris, 0L, idBase = "f169")
        // fit in canvas px: 0.96 x 1080/450 of the height; stored basis k = 1920/720.
        val fitPx = 0.96f * 1080f / 450f
        assertEquals(fitPx * (720f / 1920f) * 100f, ins169.group.scalePercent, 0.05f)
        val clips169 = listOf(ins169.group) + ins169.children
        val rig169 = ParentRig(clips169) { c, t -> c.renderAt(t) }
        for (i in 0..7) {
            val a = rig169.adjusted(ins169.children.filter { it.id.startsWith("f169-card-") }[i], 0L)
            // Stored units ARE canvas px here (previewW = canvasW): a centre at
            // (canvasW/2 + x, canvasH/2 + y) must keep the card's half size in frame.
            val half = 95f / 2f * fitPx
            assertTrue("card $i left in frame", 1920f / 2f + a.transformX - half >= -0.5f)
            assertTrue("card $i right in frame", 1920f / 2f + a.transformX + half <= 1920f + 0.5f)
            assertTrue("card $i top in frame", 1080f / 2f + a.transformY - half >= -0.5f)
            assertTrue("card $i bottom in frame", 1080f / 2f + a.transformY + half <= 1080f + 0.5f)
        }
        // The ring centre itself sits on the canvas centre: average of the 8 card centres = 0.
        val cards169 = ins169.children.filter { it.id.startsWith("f169-card-") }
        var mx = 0.0
        var my = 0.0
        for (c in cards169) {
            val a = rig169.adjusted(c, 0L)
            mx += a.transformX
            my += a.transformY
        }
        assertEquals(0.0, mx / 8.0, 1.0)
        assertEquals(0.0, my / 8.0, 1.0)
        // And the ring really spans the fitted height: topmost card centre at -133.2 x fitPx.
        val top = cards169.map { rig169.adjusted(it, 0L).transformY }.minOrNull()!!
        assertEquals(-133.2 * fitPx, top.toDouble(), 2.0)
    }

    @Test
    fun groupFitCentresTheRingAndKeepsItInFrameOn916() {
        val ins916 = CarouselTemplate.buildGroup(1080f, 1920f, bgUri, cardUris, 0L, idBase = "f916")
        // Width is the tight axis: the composition fills 96% of the canvas width.
        val fitPx = 0.96f * 1080f / 720f
        assertEquals(fitPx * (720f / 1080f) * 100f, ins916.group.scalePercent, 0.05f)
        val clips916 = listOf(ins916.group) + ins916.children
        val rig916 = ParentRig(clips916) { c, t -> c.renderAt(t) }
        val cards916 = ins916.children.filter { it.id.startsWith("f916-card-") }
        val half = 95f / 2f * fitPx
        for (c in cards916) {
            val a = rig916.adjusted(c, 0L)
            assertTrue("card left in frame", 1080f / 2f + a.transformX - half >= -0.5f)
            assertTrue("card right in frame", 1080f / 2f + a.transformX + half <= 1080f + 0.5f)
            assertTrue("card top in frame", 1920f / 2f + a.transformY - half >= -0.5f)
            assertTrue("card bottom in frame", 1920f / 2f + a.transformY + half <= 1920f + 0.5f)
        }
        var mx = 0.0
        var my = 0.0
        for (c in cards916) {
            val a = rig916.adjusted(c, 0L)
            mx += a.transformX
            my += a.transformY
        }
        assertEquals(0.0, mx / 8.0, 1.0)
        assertEquals(0.0, my / 8.0, 1.0)
    }

    @Test
    fun twoInsertsAreIndependentGroups() {
        val a = CarouselTemplate.buildGroup(pw, ph, bgUri, cardUris, 0L, idBase = "one")
        val b = CarouselTemplate.buildGroup(pw, ph, bgUri, cardUris, 7000L, idBase = "two")
        assertTrue("fresh group objects", a.group !== b.group)
        assertTrue(a.group.id != b.group.id)
        val idsA = (listOf(a.group) + a.children).map { it.id }.toSet()
        val idsB = (listOf(b.group) + b.children).map { it.id }.toSet()
        assertTrue("no shared ids", idsA.intersect(idsB).isEmpty())
        // Moving one group leaves the other's children untouched (fresh objects, fresh keys).
        val moved = a.group.copy(transformX = 500f, scalePercent = 42f)
        val rigA = ParentRig(listOf(moved) + a.children) { c, t -> c.renderAt(t) }
        val rigB = ParentRig(listOf(b.group) + b.children) { c, t -> c.renderAt(t) }
        val aBefore = ParentRig(listOf(a.group) + a.children) { c, t -> c.renderAt(t) }
            .adjusted(a.children.last(), 0L)
        val aAfter = rigA.adjusted(a.children.last(), 0L)
        val bAfter = rigB.adjusted(b.children.last(), 7000L)
        assertTrue("group A move reaches its children", abs(aAfter.transformX - aBefore.transformX) > 100f)
        val bFresh = CarouselTemplate.buildGroup(pw, ph, bgUri, cardUris, 7000L, idBase = "two")
        val bExpected = ParentRig(listOf(bFresh.group) + bFresh.children) { c, t -> c.renderAt(t) }
            .adjusted(bFresh.children.last(), 7000L)
        assertEquals(bExpected.transformX, bAfter.transformX, 0.001f)
        assertEquals(bExpected.transformY, bAfter.transformY, 0.001f)
    }

    // ---- Build 146: the Controller is an edit handle, nothing more ----------------------

    @Test
    fun controllerPresenceChangesNoMotion() {
        // Neutrality: the inserted motion is the build-144 rig's, byte for byte — a rig
        // built WITHOUT the controller clip composes every card/shadow to the identical
        // pose as the full insert (the connection is invisible to ParentRig).
        val without = ParentRig(ins.lanesTopFirst.filter { it.id != ins.controller.id }) { c, t -> c.renderAt(t) }
        for (loopMs in listOf(0L, 137L, 1250L, 2500L, 3750L, 4999L)) {
            for (c in cards + shadows) {
                val a = rig.adjusted(c, at0 + loopMs)
                val b = without.adjusted(c, at0 + loopMs)
                assertEquals("x without controller ${c.id} at $loopMs", b.transformX, a.transformX, 0.001f)
                assertEquals("y without controller ${c.id} at $loopMs", b.transformY, a.transformY, 0.001f)
                assertEquals("rot without controller ${c.id} at $loopMs", b.rotationDeg, a.rotationDeg, 0.001f)
                assertEquals("opacity without controller ${c.id} at $loopMs", b.opacity, a.opacity, 0.001f)
            }
        }
    }

    @Test
    fun controllerIsConnectedToExactlyTheSixteen() {
        val connected = ControllerFanOut.connected(allClips, ins.controller.id)
        assertEquals((cards + shadows).map { it.id }.toSet(), connected.map { it.id }.toSet())
        assertEquals(16, connected.size)
        // Not connected: the group, the null, the background, the controller itself.
        for (c in listOf(ins.group, ins.nullLayer, ins.controller, children.single { it.id == "test-bg" })) {
            assertTrue("${c.id} must not be connected", !ControllerFanOut.isConnected(c, ins.controller.id))
        }
    }

    @Test
    fun controllerEditFansOutToTheSixteenInTheirOwnPlaces() {
        // One edit of the Carousel Controller — moved by (+24, -10), turned +30 degrees,
        // opacity 50, blend Multiply, one effect — fans out onto all 16 connected layers:
        // each keeps its own place (shifted by the delta only), turns in place by +30,
        // and takes the values. The null, the background and the group are untouched.
        val fx = ClipFx(type = "gaussian_blur", values = listOf(12f))
        val before = ins.controller
        val after = before.copy(
            transformX = before.transformX + 24f, transformY = before.transformY - 10f,
            rotationDeg = 30f, opacity = 50f, blendMode = "Multiply", effects = listOf(fx)
        )
        val t = at0 + 1000L
        for (c in cards + shadows) {
            val f = ControllerFanOut.fanned(c, before, after, t)
            assertEquals("fan-out x ${c.id}", c.transformX + 24f, f.transformX, 0.001f)
            assertEquals("fan-out y ${c.id}", c.transformY - 10f, f.transformY, 0.001f)
            assertEquals("fan-out rotation ${c.id}", 30f, f.rotationDeg, 0.001f)
            assertEquals("fan-out opacity ${c.id}", 50f, f.opacity, 0.001f)
            assertEquals("fan-out blend ${c.id}", "Multiply", f.blendMode)
            assertEquals("fan-out effects ${c.id}", listOf(fx), f.effects)
            // The ring geometry survives: the fanned layer is still on the ring, just
            // turned in place — its centre relative to the ring centre is unchanged in
            // length (the delta is a pure translation).
            assertEquals(
                "fan-out keeps the local ring offset ${c.id}",
                hypot(c.transformX - before.transformX, c.transformY - before.transformY),
                hypot(f.transformX - after.transformX, f.transformY - after.transformY),
                0.01f
            )
        }
        // The unconnected layers are not the fan-out's business: fanned() is only ever
        // called for connected layers, and the connection set excludes them (pinned above).
        // The controller itself is never fanned onto itself.
        assertTrue(ControllerFanOut.fanned(ins.controller, before, after, t) === ins.controller)
    }

    @Test
    fun duplicatingTheGroupRemapsTheControllerConnection() {
        // A copied Carousel is independent (build 143's rule): its cards connect to the
        // COPY's controller, never to the original's — GroupOps.duplicate remaps the
        // connection exactly like it remaps the parent links.
        val dup = GroupOps.duplicate(projectTracks, ins.group.id) ?: error("duplicate failed")
        val kids = GroupOps.childrenOf(dup.tracks, dup.groupId)
        assertEquals(19, kids.size)
        val newCtrl = kids.single { it.type == LayerType.CONTROLLER }
        assertTrue(newCtrl.id != ins.controller.id)
        val newSixteen = kids.filter { it.controllerId != null }
        assertEquals(16, newSixteen.size)
        for (c in newSixteen) assertEquals(newCtrl.id, c.controllerId)
        // The new null drives the copy: the copied cards' parent links point at it.
        val newNull = kids.single { it.type == LayerType.NULL }
        for (c in newSixteen) assertEquals(newNull.id, c.parent?.id)
        // The originals are untouched.
        assertEquals(ins.controller.id, cards[0].controllerId)
        assertEquals(ins.nullLayer.id, cards[0].parent?.id)
    }

    // ---- Build 146: the fan-out contract, on synthetic layers --------------------------

    private fun layer(
        id: String, type: LayerType, x: Float = 0f, y: Float = 0f, rot: Float = 0f,
        scale: Float = 100f, opacity: Float = 100f, blend: String = "Normal",
        effects: List<ClipFx> = emptyList(), parent: ParentLink? = null,
        controllerId: String? = null,
        keyframes: Map<AnimProp, List<Keyframe>> = emptyMap()
    ) = TimelineClip(
        id = id, name = id, type = type, startMs = 0L, durationMs = 5000L, color = 0L,
        transformX = x, transformY = y, rotationDeg = rot, scalePercent = scale,
        opacity = opacity, blendMode = blend, effects = effects, parent = parent,
        controllerId = controllerId, keyframes = keyframes
    )

    @Test
    fun fanOutRotationTurnsEachLayerInPlaceNoOrbit() {
        // Sarowar's decisive example: rotating the Controller rotates each connected layer
        // around its OWN pivot — the centres do NOT move (no orbit around the Controller).
        val ctrlB = layer("ctrl", LayerType.CONTROLLER, x = 500f, y = 300f)
        val ctrlA = ctrlB.copy(rotationDeg = 30f)
        val a = layer("a", LayerType.SHAPE, x = -100f, y = 50f, rot = 10f, controllerId = "ctrl")
        val b = layer("b", LayerType.SHAPE, x = 200f, y = -80f, rot = -20f, controllerId = "ctrl")
        val fa = ControllerFanOut.fanned(a, ctrlB, ctrlA, 1000L)
        val fb = ControllerFanOut.fanned(b, ctrlB, ctrlA, 1000L)
        assertEquals(40f, fa.rotationDeg, 0.001f)
        assertEquals(10f, fb.rotationDeg, 0.001f)
        assertEquals("centre of a must not move", -100f, fa.transformX, 0f)
        assertEquals("centre of a must not move", 50f, fa.transformY, 0f)
        assertEquals("centre of b must not move", 200f, fb.transformX, 0f)
        assertEquals("centre of b must not move", -80f, fb.transformY, 0f)
        // A keyframed layer: the delta lands as a keyframe at the playhead holding the
        // layer's OWN value there + the delta; the other keys and the stored field stay.
        val keyed = layer(
            "keyed", LayerType.SHAPE, rot = 10f, controllerId = "ctrl",
            keyframes = mapOf(AnimProp.ROTATION to listOf(Keyframe(0L, 10f), Keyframe(4000L, 20f)))
        )
        val fk = ControllerFanOut.fanned(keyed, ctrlB, ctrlA, 2000L)
        val keys = fk.keyframes.getValue(AnimProp.ROTATION)
        assertEquals(listOf(0L, 2000L, 4000L), keys.map { it.timeMs })
        assertEquals(10f, keys[0].a, 0.001f)
        assertEquals(45f, keys[1].a, 0.001f)   // own 15 at the playhead (linear) + 30
        assertEquals(20f, keys[2].a, 0.001f)
        assertEquals("stored fallback untouched", 10f, fk.rotationDeg, 0f)
    }

    @Test
    fun fanOutPositionShiftsByTheDeltaOnly() {
        val ctrlB = layer("ctrl", LayerType.CONTROLLER, x = 0f, y = 0f)
        val ctrlA = ctrlB.copy(transformX = 40f, transformY = -15f)
        val a = layer("a", LayerType.SHAPE, x = 100f, y = 50f, controllerId = "ctrl")
        val b = layer("b", LayerType.SHAPE, x = -30f, y = 200f, controllerId = "ctrl")
        val fa = ControllerFanOut.fanned(a, ctrlB, ctrlA, 500L)
        val fb = ControllerFanOut.fanned(b, ctrlB, ctrlA, 500L)
        assertEquals(140f, fa.transformX, 0.001f)
        assertEquals(35f, fa.transformY, 0.001f)
        assertEquals(10f, fb.transformX, 0.001f)
        assertEquals(185f, fb.transformY, 0.001f)
        // The geometry between the layers is preserved exactly (a pure shared delta).
        assertEquals(b.transformX - a.transformX, fb.transformX - fa.transformX, 0f)
        assertEquals(b.transformY - a.transformY, fb.transformY - fa.transformY, 0f)
        // Nothing else changed.
        assertEquals(a.rotationDeg, fa.rotationDeg, 0f)
        assertEquals(a.scalePercent, fa.scalePercent, 0f)
        assertEquals(a.opacity, fa.opacity, 0f)
    }

    @Test
    fun fanOutScaleIsRelativeToEachLayer() {
        val ctrlB = layer("ctrl", LayerType.CONTROLLER, scale = 100f)
        val ctrlA = ctrlB.copy(scalePercent = 120f)
        val small = layer("s", LayerType.SHAPE, scale = 50f, controllerId = "ctrl")
        val big = layer("g", LayerType.SHAPE, scale = 200f, controllerId = "ctrl")
        assertEquals(60f, ControllerFanOut.fanned(small, ctrlB, ctrlA, 0L).scalePercent, 0.001f)
        assertEquals(240f, ControllerFanOut.fanned(big, ctrlB, ctrlA, 0L).scalePercent, 0.001f)
        // Scaling the controller back down divides by the same ratio.
        assertEquals(50f, ControllerFanOut.fanned(
            ControllerFanOut.fanned(small, ctrlB, ctrlA, 0L), ctrlA, ctrlB, 0L
        ).scalePercent, 0.001f)
    }

    @Test
    fun fanOutValuesReachConnectedLayersOnly() {
        val fxA = ClipFx(type = "gaussian_blur", values = listOf(3f))
        val fxB = ClipFx(type = "glow", values = listOf(7f))
        val ctrlB = layer("ctrl", LayerType.CONTROLLER)
        val ctrlA = ctrlB.copy(opacity = 45f, blendMode = "Multiply", effects = listOf(fxA))
        val byField = layer("f", LayerType.SHAPE, opacity = 90f, effects = listOf(fxB), controllerId = "ctrl")
        // Legacy build-145 connection: the parent link points at the controller.
        val byLink = layer("l", LayerType.SHAPE, opacity = 70f, parent = ParentLink("ctrl", 0f, 0f, 100f, 0f))
        val free = layer("free", LayerType.SHAPE, opacity = 80f, effects = listOf(fxB))
        val conn = ControllerFanOut.connected(listOf(ctrlB, byField, byLink, free), "ctrl")
        assertEquals(setOf("f", "l"), conn.map { it.id }.toSet())
        for (c in listOf(byField, byLink)) {
            val f = ControllerFanOut.fanned(c, ctrlB, ctrlA, 100L)
            assertEquals("opacity value fan-out ${c.id}", 45f, f.opacity, 0.001f)
            assertEquals("blend value fan-out ${c.id}", "Multiply", f.blendMode)
            assertEquals("effects value fan-out ${c.id}", listOf(fxA), f.effects)
        }
        // An unchanged controller fans nothing out.
        assertTrue(ControllerFanOut.fanned(byField, ctrlB, ctrlB, 100L) === byField)
    }

    @Test
    fun parentRigIgnoresControllerLinksEntirely() {
        // The render-time contract: a layer whose (legacy build-145) parent link points at
        // a Controller is composed as ITSELF — no transform swing, no opacity multiply, no
        // blend / effects inheritance — while a Null parent in the same rig still parents.
        val fxA = ClipFx(type = "gaussian_blur", values = listOf(3f))
        val fxB = ClipFx(type = "glow", values = listOf(7f))
        val ctrl = layer(
            "ctrl", LayerType.CONTROLLER, x = 500f, y = 300f, rot = 45f, scale = 200f,
            opacity = 40f, blend = "Overlay", effects = listOf(fxA)
        )
        val legacy = layer(
            "legacy", LayerType.SHAPE, x = 10f, y = 20f, rot = 5f, opacity = 80f,
            effects = listOf(fxB), parent = ParentLink("ctrl", 0f, 0f, 100f, 0f)
        )
        val nullP = layer("np", LayerType.NULL, x = 100f, y = 0f, rot = 90f)
        val nullChild = layer(
            "nchild", LayerType.SHAPE, x = 200f, y = 0f, rot = 5f,
            parent = ParentLink("np", 100f, 0f, 100f, 0f)
        )
        val rig3 = ParentRig(listOf(ctrl, legacy, nullP, nullChild)) { c, t -> c.renderAt(t) }

        val a = rig3.adjusted(legacy, 1000L)
        val plain = legacy.renderAt(1000L)
        assertEquals(plain.transformX, a.transformX, 0f)
        assertEquals(plain.transformY, a.transformY, 0f)
        assertEquals(plain.rotationDeg, a.rotationDeg, 0f)
        assertEquals(plain.scalePercent, a.scalePercent, 0f)
        assertEquals(80f, a.opacity, 0f)
        assertEquals("Normal", a.blendMode)
        assertEquals(listOf(fxB), a.effects)

        // The media path agrees: applyToFp leaves the placement numbers untouched.
        val fp = FloatArray(17) { it * 0.25f }
        val fpBefore = fp.copyOf()
        rig3.applyToFp(legacy, 1000L, fp, 1f)
        assertTrue("applyToFp must not touch a controller-linked layer", fp.contentEquals(fpBefore))

        // The Null in the same rig still parents exactly as before (the scoping guard):
        // the child at (200, 0) swings around the null at (100, 0) by its +90 degrees.
        val nc = rig3.adjusted(nullChild, 1000L)
        assertEquals(100f, nc.transformX, 0.01f)
        assertEquals(100f, nc.transformY, 0.01f)
        assertEquals(95f, nc.rotationDeg, 0.01f)
    }

    // ---- Build 154: the parameterised insert (Templates v2) ----------------------------

    private fun rigOf(insert: CarouselTemplate.Insert) =
        ParentRig(insert.lanesTopFirst) { c, t -> c.renderAt(t) }

    /** Scene-px centre of [clip] of an arbitrary [insert], through its real rig chain. */
    private fun sceneOf(
        insert: CarouselTemplate.Insert, rigX: ParentRig, clip: TimelineClip, tMs: Long
    ): Pair<Double, Double> {
        val a = rigX.adjusted(clip, tMs)
        val cardCount = insert.children.count { it.id.contains("-card-") }
        val (_, _, fit) = CarouselTemplate.carouselFit(pw, ph, CarouselTemplate.ringRadius(cardCount))
        return ((a.transformX - insert.group.transformX) / fit / k + 360.0) to
            ((a.transformY - insert.group.transformY) / fit / k + 225.0)
    }

    private fun expectedCenter(loopMs: Long, index: Int, p: CarouselParams): Pair<Double, Double> {
        val r = CarouselTemplate.ringRadius(p.cardCount)
        val dir = if (p.clockwise) 1.0 else -1.0
        val ang = Math.toRadians(
            index * (360.0 / p.cardCount) + dir * 360.0 * law.apply(loopMs / p.durationMs.toFloat())
        )
        return (364.0 + r * cos(ang)) to (224.0 + r * sin(ang))
    }

    @Test
    fun defaultParamsBuildTheBuild146GroupExactly() {
        // The whole point of the defaults: an explicit-default insert and a no-settings
        // insert are the build-146 group, clip for clip, key for key (deep equality —
        // TimelineClip / Keyframe / Easing / ParentLink are all data classes and the
        // builder is a pure function of its inputs).
        val explicit = CarouselTemplate.buildGroup(
            pw, ph, bgUri, cardUris, at0, idBase = "test", params = CarouselParams()
        )
        val spelled = CarouselTemplate.buildGroup(
            pw, ph, bgUri, cardUris, at0, idBase = "test",
            params = CarouselParams(cardCount = 8, durationMs = 5000L, clockwise = true)
        )
        assertEquals(ins.lanesTopFirst, explicit.lanesTopFirst)
        assertEquals(ins.lanesTopFirst, spelled.lanesTopFirst)
        assertEquals(ins.group, explicit.group)
        assertEquals(ins.nullLayer, explicit.nullLayer)
        assertEquals(ins.controller, explicit.controller)
    }

    @Test
    fun ringRadiusLawGrowsOnlyWhenCardsWouldCrowd() {
        // The measured ring stands while the chord keeps 95 x 1.04 = 98.8: N = 4..8.
        for (n in 4..8) assertEquals("radius at $n", 133.2f, CarouselTemplate.ringRadius(n), 0f)
        // N = 9 is the first crowded count; the law is (95 x 1.04 / 2) / sin(pi / N).
        assertEquals(49.4 / sin(Math.PI / 9), CarouselTemplate.ringRadius(9).toDouble(), 0.05)
        assertEquals(49.4 / sin(Math.PI / 10), CarouselTemplate.ringRadius(10).toDouble(), 0.05)
        assertEquals(49.4 / sin(Math.PI / 12), CarouselTemplate.ringRadius(12).toDouble(), 0.05)
        assertEquals(190.86, CarouselTemplate.ringRadius(12).toDouble(), 0.05)
        // Every offered count: the chord clears the card with its 4% gap, and the radius
        // never shrinks as the count grows.
        var prev = 0f
        for (n in CarouselSpec.MIN_CARDS..CarouselSpec.MAX_CARDS) {
            val r = CarouselTemplate.ringRadius(n)
            val chord = 2.0 * r * sin(Math.PI / n)
            assertTrue("chord at $n must clear 98.8 (is $chord)", chord >= 98.8 - 0.01)
            assertTrue("radius monotonic at $n", r >= prev)
            prev = r
        }
    }
    @Test
    fun fourCardInsertStructurePhasesAndConnections() {
        val p = CarouselParams(cardCount = 4)
        val uris4 = (1..4).map { "content://test/c$it.png" }
        val ins4 = CarouselTemplate.buildGroup(pw, ph, bgUri, uris4, at0, idBase = "four", params = p)
        // 2 rig layers + 4 cards + 4 shadows + background = 11 children, lane order kept.
        assertEquals(11, ins4.children.size)
        val expected = ArrayList<String>()
        expected += "four-null"
        expected += "four-controller"
        for (i in 1..4) {
            expected += "four-card-$i"
            expected += "four-shadow-$i"
        }
        expected += "four-bg"
        assertEquals(expected, ins4.children.map { it.id })
        assertEquals(5000L, ins4.group.durationMs)
        // The controller's connection set scales: exactly the 2 x 4 cards/shadows.
        val connected = ControllerFanOut.connected(ins4.lanesTopFirst, ins4.controller.id)
        assertEquals(8, connected.size)
        // Phases spread over the full turn: 0 / 90 / 180 / 270 degrees at the measured
        // radius, centres exact on the circle at t=0 through the real rig.
        val rig4 = rigOf(ins4)
        val cards4 = ins4.children.filter { it.id.startsWith("four-card-") }
        for (i in 0..3) {
            val (sx, sy) = sceneOf(ins4, rig4, cards4[i], at0)
            val (ex, ey) = expectedCenter(0L, i, p)
            assertEquals("card $i x", ex, sx, 0.35)
            assertEquals("card $i y", ey, sy, 0.35)
        }
        // And they ride the same law (quarter phases stay quarter phases).
        for (loopMs in listOf(1250L, 2500L, 4999L)) {
            for (i in 0..3) {
                val (sx, sy) = sceneOf(ins4, rig4, cards4[i], at0 + loopMs)
                val (ex, ey) = expectedCenter(loopMs, i, p)
                assertEquals("card $i x at $loopMs", ex, sx, 1.2)
                assertEquals("card $i y at $loopMs", ey, sy, 1.2)
            }
        }
    }

    @Test
    fun twelveCardInsertGrowsTheRingAndNeverOverlaps() {
        val p = CarouselParams(cardCount = 12)
        val uris12 = (1..12).map { "content://test/c$it.png" }
        val ins12 = CarouselTemplate.buildGroup(pw, ph, bgUri, uris12, at0, idBase = "tw", params = p)
        assertEquals(27, ins12.children.size)
        val connected = ControllerFanOut.connected(ins12.lanesTopFirst, ins12.controller.id)
        assertEquals(24, connected.size)
        val rig12 = rigOf(ins12)
        val cards12 = ins12.children.filter { it.id.startsWith("tw-card-") }
        assertEquals(12, cards12.size)
        val centres = cards12.map { sceneOf(ins12, rig12, it, at0) }
        // Every centre sits on the grown ring…
        for ((sx, sy) in centres) {
            assertEquals(190.86, hypot(sx - 364.0, sy - 224.0), 0.4)
        }
        // …and every adjacent pair (incl. the wrap) keeps the law's chord — the cards
        // (95 px) can never overlap.
        for (i in 0..11) {
            val (ax, ay) = centres[i]
            val (bx, by) = centres[(i + 1) % 12]
            val d = hypot(bx - ax, by - ay)
            assertEquals("adjacent chord $i", 98.8, d, 0.4)
            assertTrue("no overlap $i", d > CarouselSpec.CARD_SIZE.toDouble())
        }
        // The cards themselves are unchanged — same 95 px box, same corner.
        for (c in cards12) {
            assertEquals(95f / 260f, c.shapeScaleX, 1e-5f)
            assertEquals(65f * 14f / 47.5f, c.shapeCornerRadius, 0.01f)
        }
    }
    @Test
    fun twelveCardsStayInFrameAfterFitOn169And916() {
        val p = CarouselParams(cardCount = 12)
        val uris12 = (1..12).map { "content://test/c$it.png" }
        // 16:9: the grown ring is the fit's content on the height axis (476.7 > 450).
        val insA = CarouselTemplate.buildGroup(1920f, 1080f, bgUri, uris12, 0L, idBase = "twa", params = p)
        val fitPxA = 0.96f * 1080f / (2f * (CarouselTemplate.ringRadius(12) + 47.5f))
        assertEquals(fitPxA * (720f / 1920f) * 100f, insA.group.scalePercent, 0.05f)
        val rigA = ParentRig(insA.lanesTopFirst) { c, t -> c.renderAt(t) }
        val halfA = 95f / 2f * fitPxA
        for (c in insA.children.filter { it.id.startsWith("twa-card-") }) {
            for (t in listOf(0L, 1300L, 4999L)) {
                val a = rigA.adjusted(c, t)
                assertTrue("16:9 left in frame", 1920f / 2f + a.transformX - halfA >= -0.5f)
                assertTrue("16:9 right in frame", 1920f / 2f + a.transformX + halfA <= 1920f + 0.5f)
                assertTrue("16:9 top in frame", 1080f / 2f + a.transformY - halfA >= -0.5f)
                assertTrue("16:9 bottom in frame", 1080f / 2f + a.transformY + halfA <= 1080f + 0.5f)
            }
        }
        // 9:16: the width axis rules instead — the frame (720) still dominates it, so the
        // fit is the plain frame fit; the ring fits inside it on both axes.
        val insB = CarouselTemplate.buildGroup(1080f, 1920f, bgUri, uris12, 0L, idBase = "twb", params = p)
        val fitPxB = 0.96f * 1080f / 720f
        assertEquals(fitPxB * (720f / 1080f) * 100f, insB.group.scalePercent, 0.05f)
        val rigB = ParentRig(insB.lanesTopFirst) { c, t -> c.renderAt(t) }
        val halfB = 95f / 2f * fitPxB
        for (c in insB.children.filter { it.id.startsWith("twb-card-") }) {
            for (t in listOf(0L, 2600L, 4999L)) {
                val a = rigB.adjusted(c, t)
                assertTrue("9:16 left in frame", 1080f / 2f + a.transformX - halfB >= -0.5f)
                assertTrue("9:16 right in frame", 1080f / 2f + a.transformX + halfB <= 1080f + 0.5f)
                assertTrue("9:16 top in frame", 1920f / 2f + a.transformY - halfB >= -0.5f)
                assertTrue("9:16 bottom in frame", 1920f / 2f + a.transformY + halfB <= 1920f + 0.5f)
            }
        }
    }

    @Test
    fun twoSecondTurnSpansAndFollowsTheScaledLaw() {
        val p = CarouselParams(durationMs = 2000L)
        val fast = CarouselTemplate.buildGroup(pw, ph, bgUri, cardUris, at0, idBase = "fast", params = p)
        // The whole composition spans the chosen 2 s.
        assertEquals(2000L, fast.group.durationMs)
        for (c in fast.children) assertEquals(2000L, c.durationMs)
        // The null's track: 41 keys, 0 -> exactly 360 over 2000 ms, law values throughout.
        val keys = fast.nullLayer.keyframes.getValue(AnimProp.ROTATION)
        assertEquals(41, keys.size)
        assertEquals(0L, keys.first().timeMs)
        assertEquals(2000L, keys.last().timeMs)
        assertEquals(360f, keys.last().a, 0f)
        for (key in keys) {
            val want = 360.0 * law.apply(key.timeMs / 2000f).toDouble()
            assertEquals("null rotation at ${key.timeMs}", want, key.a.toDouble(), 1.0)
        }
        // Composed centres follow the same law, time-scaled.
        val rigF = rigOf(fast)
        val cardsF = fast.children.filter { it.id.startsWith("fast-card-") }
        for (loopMs in listOf(0L, 500L, 1000L, 1500L, 1999L)) {
            for (i in 0..7) {
                val (sx, sy) = sceneOf(fast, rigF, cardsF[i], at0 + loopMs)
                val (ex, ey) = expectedCenter(loopMs, i, p)
                assertEquals("card $i x at $loopMs", ex, sx, 1.2)
                assertEquals("card $i y at $loopMs", ey, sy, 1.2)
            }
        }
        // Loop closure at the chosen period.
        for (i in 0..7) {
            val (ax, ay) = sceneOf(fast, rigF, cardsF[i], at0)
            val (bx, by) = sceneOf(fast, rigF, cardsF[i], at0 + 2000L)
            assertEquals(ax, bx, 1e-3)
            assertEquals(ay, by, 1e-3)
        }
    }
    @Test
    fun tenSecondTurnSpansAndFollowsTheScaledLaw() {
        val p = CarouselParams(durationMs = 10000L)
        val slow = CarouselTemplate.buildGroup(pw, ph, bgUri, cardUris, at0, idBase = "slow", params = p)
        assertEquals(10000L, slow.group.durationMs)
        for (c in slow.children) assertEquals(10000L, c.durationMs)
        val keys = slow.nullLayer.keyframes.getValue(AnimProp.ROTATION)
        assertEquals(201, keys.size)
        assertEquals(10000L, keys.last().timeMs)
        assertEquals(360f, keys.last().a, 0f)
        for (key in keys) {
            val want = 360.0 * law.apply(key.timeMs / 10000f).toDouble()
            assertEquals("null rotation at ${key.timeMs}", want, key.a.toDouble(), 1.0)
        }
        val rigS = rigOf(slow)
        val cardsS = slow.children.filter { it.id.startsWith("slow-card-") }
        for (loopMs in listOf(0L, 2500L, 5000L, 7500L, 9999L)) {
            for (i in 0..7) {
                val (sx, sy) = sceneOf(slow, rigS, cardsS[i], at0 + loopMs)
                val (ex, ey) = expectedCenter(loopMs, i, p)
                assertEquals("card $i x at $loopMs", ex, sx, 1.2)
                assertEquals("card $i y at $loopMs", ey, sy, 1.2)
            }
        }
    }

    @Test
    fun counterClockwiseTurnNegatesTheLaw() {
        val p = CarouselParams(clockwise = false)
        val ccw = CarouselTemplate.buildGroup(pw, ph, bgUri, cardUris, at0, idBase = "ccw", params = p)
        // The null's track is the measured law negated: 0 -> exactly -360, decreasing.
        val keys = ccw.nullLayer.keyframes.getValue(AnimProp.ROTATION)
        assertEquals(101, keys.size)
        assertEquals(0f, keys.first().a, 0f)
        assertEquals(-360f, keys.last().a, 0f)
        var prev = 1f
        for (key in keys) {
            val want = -360.0 * law.apply(key.timeMs / 5000f).toDouble()
            assertEquals("null rotation at ${key.timeMs}", want, key.a.toDouble(), 1.0)
            assertTrue("monotonic down at ${key.timeMs}", key.a < prev)
            prev = key.a
        }
        // Card 0 travels the other way: at quarter progress it is at the TOP of the ring
        // (angle -90), where clockwise puts it at the bottom.
        val rigC = rigOf(ccw)
        val cardsC = ccw.children.filter { it.id.startsWith("ccw-card-") }
        val quarter = (0..200).map { it * 25L }.firstOrNull { t ->
            abs(CarouselTemplate.progress(t / 5000.0) - 0.25) < 0.002
        } ?: error("no quarter time")
        val (qx, qy) = sceneOf(ccw, rigC, cardsC[0], at0 + quarter)
        val ang = Math.toDegrees(atan2(qy - 224.0, qx - 364.0))
        assertEquals(-90.0, ang, 2.0)
        // Every composed centre mirrors the clockwise insert about the centre line:
        // CCW card i sits where CW card (8 - i) mod 8 sits with y reflected (its angle
        // is the negation) — and the scaled-law helper agrees outright.
        for (loopMs in listOf(0L, 1250L, 2500L, 3750L, 4999L)) {
            for (i in 0..7) {
                val (sx, sy) = sceneOf(ccw, rigC, cardsC[i], at0 + loopMs)
                val (ex, ey) = expectedCenter(loopMs, i, p)
                assertEquals("card $i x at $loopMs", ex, sx, 1.2)
                assertEquals("card $i y at $loopMs", ey, sy, 1.2)
                val (cx2, cy2) = templateSceneOf(cards[(8 - i) % 8], at0 + loopMs)
                assertEquals("mirror x $i at $loopMs", cx2, sx, 1.2)
                assertEquals("mirror y $i at $loopMs", 224.0 - (cy2 - 224.0), sy, 1.2)
            }
        }
        // Loop closure.
        for (i in 0..7) {
            val (ax, ay) = sceneOf(ccw, rigC, cardsC[i], at0)
            val (bx, by) = sceneOf(ccw, rigC, cardsC[i], at0 + 5000L)
            assertEquals(ax, bx, 1e-3)
            assertEquals(ay, by, 1e-3)
        }
    }
}
