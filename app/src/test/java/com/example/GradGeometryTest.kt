package com.example

import com.example.model.AnimProp
import com.example.model.Easing
import com.example.model.GRAD_LINEAR
import com.example.model.GRAD_RADIAL
import com.example.model.GRAD_SWEEP
import com.example.model.GradGeometry
import com.example.model.Keyframe
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.at
import com.example.model.propValues
import com.example.model.withProp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build 173: the gradient fill's keyframed angle / scale (GRAD_GEOMETRY) — the shared
 * geometry (GradGeometry) the importer and the runtime evaluation both use, so the two
 * can never drift apart.
 */
class GradGeometryTest {

    @Test
    fun linearEndpointsFollowTheDef() {
        // Angle 0 points UP: from the middle of the bottom edge to the middle of the top one.
        val p0 = GradGeometry.endpoints(GRAD_LINEAR, 0.5f, 0.5f, 0f, 1f)
        assertEquals(0.5f, p0[0], 0.001f)
        assertEquals(1f, p0[1], 0.001f)
        assertEquals(0.5f, p0[2], 0.001f)
        assertEquals(0f, p0[3], 0.001f)
        // Angle 90 points RIGHT.
        val p90 = GradGeometry.endpoints(GRAD_LINEAR, 0.5f, 0.5f, 90f, 1f)
        assertEquals(0f, p90[0], 0.001f)
        assertEquals(0.5f, p90[1], 0.001f)
        assertEquals(1f, p90[2], 0.001f)
        assertEquals(0.5f, p90[3], 0.001f)
        // Verbatim ST Intro instance (shape 633194 of 1c30cfdb): angle 45.5, centre
        // (0.136, 0.5), scale 1.32 — the same numbers the importer's own pin asserts.
        val p = GradGeometry.endpoints(GRAD_LINEAR, 0.136f, 0.5f, 45.5f, 1.32f)
        assertEquals(-0.3347f, p[0], 0.002f)
        assertEquals(0.9626f, p[1], 0.002f)
        assertEquals(0.6067f, p[2], 0.002f)
        assertEquals(0.0374f, p[3], 0.002f)
    }

    @Test
    fun radialAndSweepEndpointsFollowTheDef() {
        // Radial: the second point sits scale/2 from the offset centre along +x; angle plays no part.
        val r = GradGeometry.endpoints(GRAD_RADIAL, 0.7f, 0.4f, 30f, 2f)
        assertEquals(0.7f, r[0], 0.001f)
        assertEquals(0.4f, r[1], 0.001f)
        assertEquals(1.7f, r[2], 0.001f)
        assertEquals(0.4f, r[3], 0.001f)
        // Sweep, angle 0: the second point sits on the leftward ray from the centre.
        val s = GradGeometry.endpoints(GRAD_SWEEP, 0.5f, 0.5f, 0f, 1f)
        assertEquals(0.5f, s[0], 0.001f)
        assertEquals(0.5f, s[1], 0.001f)
        assertEquals(0f, s[2], 0.001f)
        assertEquals(0.5f, s[3], 0.001f)
    }

    @Test
    fun centreAndAngleScaleRoundTrip() {
        fun check(type: Int, cx: Float, cy: Float, angle: Float, scale: Float) {
            val pts = GradGeometry.endpoints(type, cx, cy, angle, scale)
            val (rcx, rcy) = GradGeometry.center(type, pts[0], pts[1], pts[2], pts[3])
            assertEquals(cx, rcx, 0.001f)
            assertEquals(cy, rcy, 0.001f)
            val (ra, rs) = GradGeometry.angleScale(type, pts[0], pts[1], pts[2], pts[3])
            // Radial has no angle: the recovery reports 0, and the scale still round-trips.
            if (type != GRAD_RADIAL) assertEquals(angle, ra, 0.01f)
            assertEquals(scale, rs, 0.001f)
            // And back again: the recovered angle/scale rebuild the same points.
            val pts2 = GradGeometry.endpoints(type, rcx, rcy, ra, rs)
            for (i in 0..3) assertEquals(pts[i], pts2[i], 0.002f)
        }
        check(GRAD_LINEAR, 0.5f, 0.5f, 0f, 1f)
        check(GRAD_LINEAR, 0.136f, 0.5f, 45.5f, 1.32f)
        check(GRAD_LINEAR, 0.5f, 0.792f, 112.5f, 0.54f)
        check(GRAD_RADIAL, 0.548f, 0.5f, 0f, 1.91f)
        check(GRAD_SWEEP, 0.5f, 0.5f, 270f, 2f)
        // A full turn is the same geometry: 450 reads back as 90.
        val p = GradGeometry.endpoints(GRAD_LINEAR, 0.5f, 0.5f, 450f, 1f)
        assertEquals(90f, GradGeometry.angleScale(GRAD_LINEAR, p[0], p[1], p[2], p[3]).first, 0.01f)
        // Degenerate: zero-length reads as angle 0, scale 0.
        assertEquals(0f to 0f, GradGeometry.angleScale(GRAD_LINEAR, 0.5f, 0.5f, 0.5f, 0.5f))
    }

    private fun clip(gradType: Int, x1: Float, y1: Float, x2: Float, y2: Float, keys: List<Keyframe>) =
        TimelineClip(
            id = "a", name = "a", type = LayerType.SHAPE, startMs = 1000, durationMs = 4000,
            color = 0xFF000000, fillMode = com.example.model.FILL_GRADIENT, gradType = gradType,
            gradX1 = x1, gradY1 = y1, gradX2 = x2, gradY2 = y2,
            keyframes = mapOf(AnimProp.GRAD_GEOMETRY to keys)
        )

    @Test
    fun atEvaluatesKeyframedAngle() {
        // Evergreen's "NEZUKO" text, linear, angle 0 -> 112.5 over the layer: the end points
        // rotate around the centre (0.5, 0.5) with the angle.
        val c = clip(
            GRAD_LINEAR, 0.5f, 1f, 0.5f, 0f,
            listOf(Keyframe(0, 0f, 1f), Keyframe(1000, 112.5f, 1f, Easing.LINEAR))
        )
        val mid = c.at(1500)   // rel 500: angle 56.25
        assertEquals(0.0843f, mid.gradX1, 0.002f)
        assertEquals(0.7778f, mid.gradY1, 0.002f)
        assertEquals(0.9157f, mid.gradX2, 0.002f)
        assertEquals(0.2222f, mid.gradY2, 0.002f)
        val end = c.at(2000)   // rel 1000: angle 112.5
        assertEquals(0.0381f, end.gradX1, 0.002f)
        assertEquals(0.3086f, end.gradY1, 0.002f)
        assertEquals(0.9619f, end.gradX2, 0.002f)
        assertEquals(0.6914f, end.gradY2, 0.002f)
        // Before the first / after the last keyframe the geometry holds.
        assertEquals(0.5f, c.at(500).gradX1, 0.001f)
        assertEquals(0.9619f, c.at(9000).gradX2, 0.002f)
    }

    @Test
    fun atEvaluatesKeyframedScaleOnRadial() {
        // shake-rcf-10mar2's shape, radial, scale 0.13 -> 1.91: the radius grows, the
        // centre stays put and the (inert) angle stays 160.
        val c = clip(
            GRAD_RADIAL, 0.548f, 0.5f, 0.613f, 0.5f,
            listOf(Keyframe(0, 160f, 0.13f), Keyframe(1000, 160f, 1.91f, Easing.LINEAR))
        )
        val mid = c.at(1500)   // rel 500: scale 1.02
        assertEquals(0.548f, mid.gradX1, 0.001f)
        assertEquals(0.5f, mid.gradY1, 0.001f)
        assertEquals(1.058f, mid.gradX2, 0.002f)
        assertEquals(0.5f, mid.gradY2, 0.001f)
        val end = c.at(2000)   // rel 1000: scale 1.91
        assertEquals(1.503f, end.gradX2, 0.002f)
    }

    @Test
    fun propValuesRoundTripsThroughWithProp() {
        // The generic keyframe machinery (commitKeyframed, ControllerFanOut) leans on
        // propValues / withProp being inverses: reading the angle/scale back out of the
        // stored points and writing them again must leave the points unchanged.
        val c = clip(
            GRAD_LINEAR, -0.3347f, 0.9626f, 0.6067f, 0.0374f,
            listOf(Keyframe(0, 45.5f, 1.32f))
        )
        val (a, b) = c.propValues(AnimProp.GRAD_GEOMETRY)
        val back = c.withProp(AnimProp.GRAD_GEOMETRY, a, b)
        assertEquals(c.gradX1, back.gradX1, 0.002f)
        assertEquals(c.gradY1, back.gradY1, 0.002f)
        assertEquals(c.gradX2, back.gradX2, 0.002f)
        assertEquals(c.gradY2, back.gradY2, 0.002f)
        assertTrue(a in 45.49f..45.51f)
        assertEquals(1.32f, b, 0.001f)
    }
}
