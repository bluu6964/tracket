package com.example

import com.example.engine.RepeatEval
import com.example.model.ClipFx
import com.example.model.ClipFxLibrary
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.packColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class RepeatEvalTest {

    private fun shape(
        x: Float = 10f, y: Float = 20f, rotation: Float = 0f, scale: Float = 100f, opacity: Float = 100f,
        fx: ClipFx? = null
    ) = TimelineClip(
        id = "s1", name = "Line", type = LayerType.SHAPE, startMs = 0L, durationMs = 2000L, color = 0xFF000000L,
        transformX = x, transformY = y, rotationDeg = rotation, scalePercent = scale, opacity = opacity,
        effects = if (fx == null) emptyList() else listOf(fx)
    )

    /** values = [count, offsetX, offsetY, angle, scale %, alpha %]. */
    private fun repeat(vararg values: Float) = ClipFx(type = ClipFxLibrary.REPEAT, values = values.toList())

    @Test
    fun noRepeatReturnsTheLayerAlone() {
        val c = shape()
        assertEquals(listOf(c), RepeatEval.copiesOf(c))
        // A disabled Repeat, or a count of 1, is also just the layer (same clip instance, so the
        // ClipFx's random id cannot make two equal-looking clips differ).
        val disabled = shape(fx = repeat(5f, 0f, 93f, 0f, 100f, 100f).copy(enabled = false))
        assertEquals(listOf(disabled), RepeatEval.copiesOf(disabled))
        val single = shape(fx = repeat(1f, 0f, 93f, 0f, 100f, 100f))
        assertEquals(listOf(single), RepeatEval.copiesOf(single))
    }

    @Test
    fun countAndOffsetAccumulate() {
        // The corpus case: 5 copies of a horizontal line, 93 preview px apart, vertically.
        val c = shape(fx = repeat(5f, 0f, 93f, 0f, 100f, 100f))
        val copies = RepeatEval.copiesOf(c)
        assertEquals(5, copies.size)
        assertTrue("the first copy is the layer itself", c === copies[0])
        for (i in 0 until 5) {
            assertEquals(10f, copies[i].transformX, 0f)
            assertEquals(20f + i * 93f, copies[i].transformY, 0.01f)
            assertEquals(0f, copies[i].rotationDeg, 0f)
            assertEquals(100f, copies[i].scalePercent, 0f)
            assertEquals(100f, copies[i].opacity, 0f)
        }
    }

    @Test
    fun angleScaleAlphaCompoundPerCopy() {
        // Each copy turns 30 more, halves in size and halves in opacity, 10 px to the right.
        val copies = RepeatEval.copiesOf(
            shape(rotation = 10f, scale = 200f, opacity = 80f, fx = repeat(3f, 10f, 0f, 30f, 50f, 50f))
        )
        assertEquals(3, copies.size)
        assertEquals(20f, copies[1].transformX, 0.01f)
        assertEquals(40f, copies[1].rotationDeg, 0.01f)
        assertEquals(100f, copies[1].scalePercent, 0.01f)
        assertEquals(40f, copies[1].opacity, 0.01f)
        assertEquals(30f, copies[2].transformX, 0.01f)
        assertEquals(70f, copies[2].rotationDeg, 0.01f)
        assertEquals(50f, copies[2].scalePercent, 0.01f)
        assertEquals(20f, copies[2].opacity, 0.01f)
    }

    @Test
    fun countIsClamped() {
        assertEquals(1, RepeatEval.copiesOf(shape(fx = repeat(0f, 0f, 10f, 0f, 100f, 100f))).size)
        assertEquals(RepeatEval.MAX_COUNT, RepeatEval.copiesOf(shape(fx = repeat(500f, 1f, 0f, 0f, 100f, 100f))).size)
        assertEquals(50, RepeatEval.countOf(repeat(500f)))
        assertEquals(1, RepeatEval.countOf(repeat(0f)))
    }

    @Test
    fun uprightOffsetIsUndoneByTheTurn() {
        // The layer is drawn into its own upright picture (rotation already 0 there) and the picture
        // is turned 90 degrees clockwise when placed: a copy that must land 93 px below the layer is
        // drawn 93 px to the RIGHT inside the picture, and the picture's turn puts it below.
        val upright = shape(x = 0f, y = 0f, fx = repeat(2f, 0f, 93f, 0f, 100f, 100f))
        val copies = RepeatEval.copiesOf(upright, undoRotationDeg = 90f)
        assertEquals(93f, copies[1].transformX, 0.01f)
        assertEquals(0f, copies[1].transformY, 0.01f)
        // No turn to undo: the copy is drawn where it lands.
        val plain = RepeatEval.copiesOf(upright)
        assertEquals(0f, plain[1].transformX, 0.01f)
        assertEquals(93f, plain[1].transformY, 0.01f)
        // A 180-degree turn flips both axes.
        val flipped = RepeatEval.copiesOf(upright, undoRotationDeg = 180f)
        assertEquals(0f, flipped[1].transformX, 0.01f)
        assertEquals(-93f, flipped[1].transformY, 0.01f)
    }

    @Test
    fun uprightCopiesKeepTheirOwnTurnAndScale() {
        // Inside the upright picture the copy's own turn is just its angle (the picture adds the rest).
        val upright = shape(x = 0f, y = 0f, scale = 200f, fx = repeat(2f, 10f, 0f, 30f, 50f, 100f))
        val copies = RepeatEval.copiesOf(upright, undoRotationDeg = 90f)
        assertEquals(30f, copies[1].rotationDeg, 0.01f)
        assertEquals(100f, copies[1].scalePercent, 0.01f)
        assertEquals(0f, copies[1].transformX, 0.01f)
        assertEquals(-10f, copies[1].transformY, 0.01f)
    }

    @Test
    fun repeatOfFindsTheEnabledOne() {
        val off = repeat(9f, 0f, 0f, 0f, 100f, 100f).copy(enabled = false)
        val on = repeat(4f, 5f, 0f, 0f, 100f, 100f)
        val c = shape().copy(effects = listOf(off, on))
        assertEquals(4, RepeatEval.countOf(RepeatEval.repeatOf(c)!!))
        assertTrue(RepeatEval.repeatOf(shape()) == null)
    }

    // ---- repeat.radial: copies on a ring, none at the centre -----------------------------

    /** values = [count, radius, startAngle, sweep, orientation, baseScale %, alpha %, angle, scale %, alt...]. */
    private fun radial(
        count: Float = 4f, radius: Float = 100f, startAngle: Float = 0f, sweep: Float = 360f,
        orientation: Float = 0f, baseScale: Float = 50f, alpha: Float = 100f, angle: Float = 0f,
        scaleStep: Float = 100f, alt: List<Float> = listOf(0f, 0f, 0f)
    ) = ClipFx(
        type = ClipFxLibrary.REPEAT_RADIAL,
        values = listOf(count, radius, startAngle, sweep, orientation, baseScale, alpha, angle, scaleStep) + alt
    )

    @Test
    fun radialPutsCopiesOnARingAndSkipsTheCentre() {
        val copies = RepeatEval.copiesOf(shape(fx = radial()))
        assertEquals(4, copies.size)
        // The layer sits at (10, 20). Angles 0 / 90 / 180 / 270 clockwise from straight up; the ring
        // radius is TWICE the stored radius (frame-measured against Alight's ST Intro export).
        val expected = listOf(10f to -180f, 210f to 20f, 10f to 220f, -190f to 20f)
        copies.forEachIndexed { i, copy ->
            assertEquals(expected[i].first, copy.transformX, 0.01f)
            assertEquals(expected[i].second, copy.transformY, 0.01f)
        }
        assertTrue(copies.none { it.transformX == 10f && it.transformY == 20f })
        // Each copy is turned by its ring angle; all are drawn at baseScale.
        assertEquals(listOf(0f, 90f, 180f, 270f), copies.map { it.rotationDeg })
        copies.forEach { assertEquals(50f, it.scalePercent, 0.001f) }
    }

    @Test
    fun radialStartAngleAndOrientationTurnTheRing() {
        // The ST Intro ring: start -175, sweep 353 over 4 copies, orientation -45, baseScale 0.35.
        val copies = RepeatEval.copiesOf(
            shape(fx = radial(startAngle = -175f, sweep = 353f, orientation = -45f, baseScale = 35f))
        )
        val angles = listOf(-175.0, -86.75, 1.5, 89.75)
        copies.forEachIndexed { i, copy ->
            val dx = (copy.transformX - 10f).toDouble()
            val dy = (copy.transformY - 20f).toDouble()
            assertEquals(angles[i], Math.toDegrees(kotlin.math.atan2(dx, -dy)), 0.01)
            assertEquals(200.0, hypot(dx, dy), 0.01)
            // Turned by ring angle + orientation; scaled by baseScale.
            assertEquals((angles[i] - 45.0).toFloat(), copy.rotationDeg, 0.01f)
            assertEquals(35f, copy.scalePercent, 0.001f)
        }
    }

    @Test
    fun radialAltColourPaintsTheOddCopies() {
        val (pa, pb) = packColor(0xFFB891FFL)
        val c = shape(fx = radial(alt = listOf(pa, pb, 1f))).copy(textColor = 0xFFFFFFFFL)
        val copies = RepeatEval.copiesOf(c)
        assertEquals(0xFFFFFFFFL, copies[0].textColor)
        assertEquals(0xFFB891FFL, copies[1].textColor)
        assertEquals(0xFFFFFFFFL, copies[2].textColor)
        assertEquals(0xFFB891FFL, copies[3].textColor)
    }

    // ---- repeat.scatter: copies over a disc, drifting with the evolution ------------------

    /** values = [count, radius, offsetX, offsetY, seed, scaleFloor %, alpha %, angle, evolution..., alt...]. */
    private fun scatter(
        count: Float = 10f, radius: Float = 500f, seed: Float = 0.23f,
        alt: List<Float> = listOf(0f, 0f, 0f)
    ) = ClipFx(
        type = ClipFxLibrary.REPEAT_SCATTER,
        // The ST Intro Circle 1 evolution (0 -> 1.79, 1 -> 2.45); offsets zero.
        values = listOf(count, radius, 0f, 0f, seed, 13f, 100f, 0f, 0f, 1.79f, 1f, 2.45f) + alt
    )

    @Test
    fun scatterKeepsCopiesInTheDiscAndDriftsWithEvolution() {
        val c = shape(fx = scatter())   // clip spans 0..2000 ms, sits at (10, 20)
        val atStart = RepeatEval.copiesOf(c, timeMs = 0L)
        val atMid = RepeatEval.copiesOf(c, timeMs = 1000L)
        assertEquals(10, atMid.size)
        atMid.forEach { copy ->
            assertTrue(hypot(copy.transformX - 10f, copy.transformY - 20f) <= 500.5f)
            assertTrue(copy.scalePercent in 12.9f..100.1f)   // between the 13% floor and full size
            assertEquals(100f, copy.opacity, 0.001f)
        }
        // Deterministic: the same frame time gives the same field.
        assertEquals(atMid, RepeatEval.copiesOf(c, timeMs = 1000L))
        // The evolution (1.79 -> 2.45 across the clip) moves the field.
        val moved = atStart.indices.count { i ->
            hypot(atStart[i].transformX - atMid[i].transformX, atStart[i].transformY - atMid[i].transformY) > 20f
        }
        assertTrue(moved >= 4)
    }

    @Test
    fun scatterAltColourPaintsTheOddCopies() {
        val (pa, pb) = packColor(0xFFB891FFL)
        val c = shape(fx = scatter(alt = listOf(pa, pb, 1f))).copy(textColor = 0xFFFFFFFFL)
        val copies = RepeatEval.copiesOf(c, timeMs = 1000L)
        copies.forEachIndexed { i, copy ->
            assertEquals(if (i % 2 == 1) 0xFFB891FFL else 0xFFFFFFFFL, copy.textColor)
        }
    }

    @Test
    fun scatterCountIsCappedAtTheDrawCap() {
        val copies = RepeatEval.copiesOf(shape(fx = scatter(count = 150f)), timeMs = 0L)
        assertEquals(RepeatEval.MAX_COUNT, copies.size)
    }

    // ---- copy deltas: the picture / group draw paths (RepeatDraw) -------------------------

    private fun video(
        x: Float = 30f, y: Float = -40f, rotation: Float = 15f, scale: Float = 160f, opacity: Float = 64f,
        fx: ClipFx? = null
    ) = TimelineClip(
        id = "v1", name = "Clip", type = LayerType.VIDEO, startMs = 0L, durationMs = 2000L,
        color = 0xFF000000L, uri = "content://media/external/video/1",
        transformX = x, transformY = y, rotationDeg = rotation, scalePercent = scale, opacity = opacity,
        effects = if (fx == null) emptyList() else listOf(fx)
    )

    private fun group(fx: ClipFx? = null) =
        video(fx = fx).copy(id = "g1", name = "Group", type = LayerType.GROUP, uri = null)

    @Test
    fun copyDeltasAreNullWithoutARepeat() {
        assertTrue(RepeatEval.copyDeltas(shape()) == null)
        assertTrue(RepeatEval.copyDeltas(video()) == null)
        val off = video(fx = repeat(3f, 10f, 0f, 0f, 100f, 100f).copy(enabled = false))
        assertTrue(RepeatEval.copyDeltas(off) == null)
    }

    @Test
    fun copyDeltasComposeTheSamePosesAsCopiesOf() {
        // The picture paths pose a video / photo layer's copies by deltas; the deltas must
        // reproduce copiesOf's poses on the layer's own pose exactly (the two expansions of the
        // repeat family stay in lockstep), whatever that pose is.
        val c = video(fx = repeat(4f, 12f, -7f, 20f, 80f, 75f))
        val copies = RepeatEval.copiesOf(c)
        val deltas = RepeatEval.copyDeltas(c)!!
        assertEquals(copies.size, deltas.size)
        copies.forEachIndexed { i, copy ->
            val d = deltas[i]
            assertEquals(copy.transformX, c.transformX + d.dx, 0.01f)
            assertEquals(copy.transformY, c.transformY + d.dy, 0.01f)
            assertEquals(copy.rotationDeg, c.rotationDeg + d.rotDeg, 0.01f)
            assertEquals(copy.scalePercent, c.scalePercent * d.scaleMul, 0.05f)
            assertEquals(copy.opacity, c.opacity * d.opacityMul, 0.05f)
        }
        // The plain repeat's first copy is the layer itself: an identity delta.
        assertEquals(RepeatEval.CopyDelta(0f, 0f, 0f, 1f, 1f), deltas[0])
    }

    @Test
    fun radialDeltasOnAGroupLayer() {
        val g = group(fx = radial(orientation = -45f, baseScale = 35f))
        val deltas = RepeatEval.copyDeltas(g)!!
        assertEquals(4, deltas.size)
        // Ring anchors around the group's own position (stored radius 100, drawn at twice that),
        // clockwise from straight up; no centre copy, so no identity delta.
        val anchors = listOf(0f to -200f, 200f to 0f, 0f to 200f, -200f to 0f)
        deltas.forEachIndexed { i, d ->
            assertEquals(anchors[i].first, d.dx, 0.01f)
            assertEquals(anchors[i].second, d.dy, 0.01f)
            assertEquals(0.35f, d.scaleMul, 0.001f)
            assertEquals(1f, d.opacityMul, 0.001f)
        }
        // Each copy is turned by its ring angle + the orientation.
        assertEquals(listOf(-45f, 45f, 135f, 225f), deltas.map { it.rotDeg })
        assertTrue(deltas.none { it == RepeatEval.CopyDelta(0f, 0f, 0f, 1f, 1f) })
    }

    @Test
    fun scatterDeltasOnAVideoLayerStayInTheDisc() {
        val c = video(fx = scatter())
        val deltas = RepeatEval.copyDeltas(c, timeMs = 1000L)!!
        assertEquals(10, deltas.size)
        deltas.forEach { d ->
            assertTrue(hypot(d.dx, d.dy) <= 500.5f)
            assertTrue(d.scaleMul in 0.129f..1.001f)   // the 13% floor .. full size
            assertEquals(1f, d.opacityMul, 0.001f)
            assertEquals(0f, d.rotDeg, 0f)
        }
        // The same poses copiesOf gives the overlay path (the layer's pose + the delta).
        val copies = RepeatEval.copiesOf(c, timeMs = 1000L)
        copies.forEachIndexed { i, copy ->
            assertEquals(copy.transformX, c.transformX + deltas[i].dx, 0.01f)
            assertEquals(copy.transformY, c.transformY + deltas[i].dy, 0.01f)
        }
        assertEquals(deltas, RepeatEval.copyDeltas(c, timeMs = 1000L))
    }

    // ---- groupCopyOffset: posing a frame-sized group picture about its anchor --------------

    @Test
    fun groupCopyOffsetPosesThePictureAboutTheGroupAnchor() {
        // Identity copy: the picture is drawn as-is, whatever the anchor.
        assertEquals(
            0f to 0f,
            RepeatEval.groupCopyOffset(120f, -80f, RepeatEval.CopyDelta(0f, 0f, 0f, 1f, 1f), 2f)
        )
        // A pure step (no turn / scale) is just the step, in canvas px (k = 2 here).
        assertEquals(
            60f to -20f,
            RepeatEval.groupCopyOffset(120f, -80f, RepeatEval.CopyDelta(30f, -10f, 0f, 1f, 1f), 2f)
        )
        // A quarter turn about an anchor on the x axis: offset = anchor - R90 * anchor.
        val (qx, qy) = RepeatEval.groupCopyOffset(100f, 0f, RepeatEval.CopyDelta(0f, 0f, 90f, 1f, 1f), 1f)
        assertEquals(100f, qx, 0.01f)
        assertEquals(-100f, qy, 0.01f)
        // Doubling about the anchor (100, 50): offset = anchor - 2 * anchor.
        val (sx, sy) = RepeatEval.groupCopyOffset(100f, 50f, RepeatEval.CopyDelta(0f, 0f, 0f, 2f, 1f), 1f)
        assertEquals(-100f, sx, 0.01f)
        assertEquals(-50f, sy, 0.01f)
        // The fixed point: the anchor itself, run through the renderer's placement with the
        // computed offset (screen = offset + R * S * point), lands on anchor + the copy's step.
        val d = RepeatEval.CopyDelta(30f, -10f, 40f, 1.5f, 1f)
        val (ox, oy) = RepeatEval.groupCopyOffset(120f, -80f, d, 2f)
        val rad = d.rotDeg.toDouble() * PI / 180.0
        val px = ox + d.scaleMul * (cos(rad) * 120.0 - sin(rad) * -80.0)
        val py = oy + d.scaleMul * (sin(rad) * 120.0 + cos(rad) * -80.0)
        assertEquals(120.0 + 60.0, px, 0.05)
        assertEquals(-80.0 - 20.0, py, 0.05)
    }
}
