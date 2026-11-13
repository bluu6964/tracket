package com.example

import com.example.engine.ParentRig
import com.example.model.AnimProp
import com.example.model.CarouselSpec
import com.example.model.CarouselTemplate
import com.example.model.Easing
import com.example.model.FILL_IMAGE
import com.example.model.FILL_SOLID
import com.example.model.GroupOps
import com.example.model.IMG_FILL
import com.example.model.LayerType
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
 * Builds 142-144 (Templates v1, the in-project group rework, the NULL-RIG rework): the
 * Carousel template's measured motion law, pinned against the model it inserts. The
 * reference law (720x450, 30 fps, 5.03 s; fit in after-motion-dev/carousel-ref): one
 * clockwise revolution per 5.0 s, seamless, eased by cubic-bezier(0.78, 0.013, 0.194, 1.0);
 * ring centre (364, 224), radius 133.2; cards 95x95, corner 14, upright, constant scale.
 * Expected values here are recomputed from those spec numbers with the app's own
 * pre-existing [Easing] evaluator — an independent code path from the builder's Double
 * bezier.
 *
 * Build 143 model (Sarowar's correction): inserting the template adds ONE Group layer to
 * the project at the playhead. The group's own transform is the canvas fit (see
 * [CarouselTemplate.carouselFit]); the composed pins below run the children through
 * ParentRig — the same composition the preview and the export use.
 *
 * Build 144 rig (Sarowar's second correction: the animation must be driven by a Null layer
 * in the timeline, not by keys baked into every child): the group holds 18 ordinary
 * children — a "Carousel Null" on the ring centre carrying the ENTIRE motion as one
 * ROTATION track (the measured law as degrees), the 8 cards + 8 shadow twins parented to
 * that null at their fixed t=0 ring poses with rotation-locked links and NO keyframes of
 * their own, and the static Background linked to the group. The pins below prove the
 * composed result matches build 142/143's baked law, and that the null alone controls it.
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
    fun insertIsOneGroupWithEighteenChildrenInOrder() {
        // Exactly ONE top-level layer is added: the group. The 18 children are inside it.
        assertEquals(19, allClips.size)
        val topLevel = GroupOps.scopeTracks(projectTracks, null).flatMap { it.clips }
        assertEquals(listOf(ins.group.id), topLevel.map { it.id })
        assertEquals(LayerType.GROUP, ins.group.type)
        assertEquals("Carousel", ins.group.name)
        assertEquals(at0, ins.group.startMs)
        assertEquals(5000L, ins.group.durationMs)
        // Lane order (top lane first): the null's lane, then build 142's track order for the
        // picture layers — per card the photo lane above its shadow lane, Background last.
        val expected = ArrayList<String>()
        expected += "test-null"
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
        // Cards + shadows: SHAPE layers parented to the NULL — ref = the null's rest pose
        // (ring centre, scale 100, rotation 0), rotation LOCKED (the Parent Helper lock that
        // keeps them upright while their positions ride the null's turn).
        for (c in cards + shadows) {
            assertEquals(LayerType.SHAPE, c.type)
            val link = c.parent ?: error("child ${c.id} has no parent link")
            assertEquals(n.id, link.id)
            assertEquals(4f * k, link.refX, 0.01f)
            assertEquals(-1f * k, link.refY, 0.01f)
            assertEquals(100f, link.refScale, 0f)
            assertEquals(0f, link.refRot, 0f)
            assertTrue("card/shadow link must be rotation-locked: ${c.id}", link.lockRotate)
        }
        // The background stays a static child of the group (REST ref pose), as in build 143.
        val bgLink = children.single { it.id == "test-bg" }.parent ?: error("background has no parent link")
        assertEquals(ins.group.id, bgLink.id)
        assertEquals(0f, bgLink.refX, 0f)
        assertEquals(0f, bgLink.refY, 0f)
        assertEquals(100f, bgLink.refScale, 0f)
        assertEquals(0f, bgLink.refRot, 0f)
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
        // sagitta any more: the radius is exact, and the only residual is the tangential
        // error of the null track's slice easings vs the master law (same class as the
        // build-142/143 bake, well inside 1 px mid-segment).
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
                // offset under a rotating parent — exactly what a real Alight null rig
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
        // And it is the ONLY motion in the group: the group itself and every other child
        // carry no keyframes at all — no POSITION keys anywhere, no second rotation track.
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
        // The control property (Sarowar's requirement): the null alone drives the ring.
        // Replace its track by one fixed rotation of 137 degrees — every card must sit on
        // the ring at its phase + 137, at any time, with the radius untouched.
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
}
