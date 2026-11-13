package com.example

import com.example.model.AnimProp
import com.example.model.CarouselSpec
import com.example.model.CarouselTemplate
import com.example.model.Easing
import com.example.model.FILL_IMAGE
import com.example.model.FILL_SOLID
import com.example.model.IMG_FILL
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.at
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Build 142 (Templates v1): the Carousel template's measured motion law, pinned against the
 * project model it builds. The reference law (720x450, 30 fps, 5.03 s; fit in
 * after-motion-dev/carousel-ref): one clockwise revolution per 5.0 s, seamless, eased by
 * cubic-bezier(0.78, 0.013, 0.194, 1.0); ring centre (364, 224), radius 133.2; cards 95x95,
 * corner 14, upright, constant scale. Expected values here are recomputed from those spec
 * numbers with the app's own pre-existing [Easing] evaluator — an independent code path from
 * the builder's Double bezier.
 */
class CarouselTemplateTest {

    private val pw = 1440f               // preview width the snapshot is authored against
    private val k = pw / 720f            // stored units per scene px (= 2)
    private val bgUri = "content://test/bg.png"
    private val cardUris = (1..8).map { "content://test/card$it.png" }
    private val snap = CarouselTemplate.build(pw, bgUri, cardUris, idBase = "test")
    private val clips = snap.tracks.flatMap { it.clips }
    private val cards = clips.filter { it.id.startsWith("test-card-") }
    private val shadows = clips.filter { it.id.startsWith("test-shadow-") }
    private val law = Easing(0.78f, 0.013f, 0.194f, 1f)

    private fun sceneOf(clip: TimelineClip, tMs: Long): Pair<Double, Double> {
        val at = clip.at(tMs)
        return (at.transformX / k + 360.0) to (at.transformY / k + 225.0)
    }

    private fun expectedCenter(tMs: Long, index: Int): Pair<Double, Double> {
        val ang = Math.toRadians(index * 45.0 + 360.0 * law.apply(tMs / 5000f))
        return (364.0 + 133.2 * cos(ang)) to (224.0 + 133.2 * sin(ang))
    }

    @Test
    fun structureIsEightSlotsEightShadowsOneBackground() {
        assertEquals(17, snap.tracks.size)
        assertEquals(17, clips.size)
        assertEquals(8, cards.size)
        assertEquals(8, shadows.size)
        val bg = clips.single { it.id == "test-bg" }
        // Background is the LAST track: the renderers paint tracks reversed, so it lands first.
        assertEquals(bg.id, snap.tracks.last().clips.single().id)
        for (c in clips) {
            assertEquals(0L, c.startMs)
            assertEquals(5000L, c.durationMs)
            assertEquals(LayerType.SHAPE, c.type)
        }
        assertEquals("16:10", snap.aspectRatio.label)
        assertEquals(1.6f, snap.aspectRatio.ratioFloat, 1e-6f)
        assertEquals(30, snap.exportSettings.frameRate)
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
    fun backgroundCoversTheFrame() {
        val bg = clips.single { it.id == "test-bg" }
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
        // Between keys (worst case mid-segment): chord sagitta at 50 ms steps is ~0.24 px and
        // the slice easings track the master law, so centres stay within ~1 px of the circle
        // point the law prescribes, at the prescribed angle.
        for (t in listOf(0L, 137L, 1250L, 2500L, 2625L, 3750L, 4999L)) {
            for (i in 0..7) {
                val (sx, sy) = sceneOf(cards[i], t)
                val (ex, ey) = expectedCenter(t, i)
                assertEquals("card $i radius at $t", 133.2, hypot(sx - 364.0, sy - 224.0), 1.0)
                assertEquals("card $i x at $t", ex, sx, 1.2)
                assertEquals("card $i y at $t", ey, sy, 1.2)
            }
        }
        // Exactly on keys the centres are the circle points themselves.
        for (t in listOf(0L, 1000L, 2000L, 3000L, 4000L)) {
            for (i in 0..7) {
                val (sx, sy) = sceneOf(cards[i], t)
                val (ex, ey) = expectedCenter(t, i)
                assertEquals("key card $i x at $t", ex, sx, 0.35)
                assertEquals("key card $i y at $t", ey, sy, 0.35)
            }
        }
    }

    @Test
    fun directionIsClockwiseAndOneRevolution() {
        // Card 0 starts at angle 0 (right of centre); a quarter loop later it must be at the
        // BOTTOM on screen (angle +90 in y-down terms) because the ring turns clockwise.
        val (x0, y0) = sceneOf(cards[0], 0L)
        assertEquals(364.0 + 133.2, x0, 0.35)
        assertEquals(224.0, y0, 0.35)
        val quarter = (0..200).map { it * 25L }.firstOrNull { t ->
            abs(CarouselTemplate.progress(t / 5000.0) - 0.25) < 0.002
        } ?: error("no quarter time")
        val (qx, qy) = sceneOf(cards[0], quarter)
        val ang = Math.toDegrees(atan2(qy - 224.0, qx - 364.0))
        assertEquals(90.0, ang, 2.0)
        // A full loop later every card is back where it started (seamless).
        for (i in 0..7) {
            val (ax, ay) = sceneOf(cards[i], 0L)
            val (bx, by) = sceneOf(cards[i], 5000L)
            assertEquals(ax, bx, 1e-3)
            assertEquals(ay, by, 1e-3)
        }
    }

    @Test
    fun cardsStayUprightAtConstantScale() {
        for (c in cards + shadows) {
            assertEquals(0f, c.rotationDeg, 0f)
            assertTrue("no rotation keys", c.keyframes[AnimProp.ROTATION].isNullOrEmpty())
            assertTrue("no scale keys", c.keyframes[AnimProp.SCALE].isNullOrEmpty())
            val keys = c.keyframes.getValue(AnimProp.POSITION)
            assertEquals(101, keys.size)
            assertEquals(0L, keys.first().timeMs)
            assertEquals(5000L, keys.last().timeMs)
            // Loop closure is exact: the last key is the first key's position.
            assertEquals(keys.first().a, keys.last().a, 0f)
            assertEquals(keys.first().b, keys.last().b, 0f)
            for (t in listOf(0L, 1234L, 2500L, 4999L)) {
                assertEquals(0f, c.at(t).rotationDeg, 0f)
                assertEquals(c.scalePercent, c.at(t).scalePercent, 0f)
            }
        }
    }

    @Test
    fun shadowsTrailTheirCardsByTheMeasuredOffset() {
        for (i in 0..7) {
            val s = shadows.single { it.id == "test-shadow-${i + 1}" }
            assertEquals(FILL_SOLID, s.fillMode)
            assertEquals(CarouselSpec.SHADOW_OPACITY, s.opacity, 0f)
            for (t in listOf(0L, 1750L, 3200L, 4999L)) {
                val (cx, cy) = sceneOf(cards[i], t)
                val (sx, sy) = sceneOf(s, t)
                assertEquals("shadow $i x at $t", cx, sx, 0.05)
                assertEquals("shadow $i dy at $t", cy + CarouselSpec.SHADOW_DY, sy, 0.05)
            }
        }
    }
}
