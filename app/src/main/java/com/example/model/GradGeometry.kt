package com.example.model

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The gradient-fill geometry of Alight's `gradientoverlay2` — the def's own shader maths
 * (recovered build 120 from the public dump, boiniArun2006/am-xml
 * `assets/effects/gradientoverlay2.xml`): in layer space the gradient is centred at
 * C = (0.5 + offset.x / 500, 0.5 + offset.y / 500) (AM's effect point params run 500 to the
 * layer box, y down as stored); a linear gradient's t grows along (sin a, -cos a) — angle 0
 * points UP, 90 right — with t = 0 / 1 at C -/+ that direction x scale/2; a radial
 * gradient's t is twice the centre distance / scale; a sweep's t runs clockwise (screen)
 * from the (-cos a, -sin a) ray.
 *
 * The importer stores the geometry as the two end points (gradX1..gradY2) of the FIRST
 * angle/scale; a keyframed angle/scale rides [AnimProp.GRAD_GEOMETRY] (a = angle in
 * degrees, b = scale) and [TimelineClip.at] rebuilds the points per frame from the same
 * centre, so the fill animates exactly like the group op's keyframed angle/scale tails
 * (build 172). The centre is recovered from the stored points (linear: midpoint;
 * radial/sweep: first point), so a handle drag that moves the centre re-anchors the
 * animation with it. One implementation serves the importer and the runtime evaluation —
 * the two can never drift apart.
 */
object GradGeometry {

    /**
     * End points (x1, y1, x2, y2) in layer-norm units for a gradient of [gradType]
     * (GRAD_LINEAR / GRAD_RADIAL / GRAD_SWEEP) centred at ([cx], [cy]) with [angleDeg]
     * and [scale]. Unknown types fall back to linear, like the importer.
     */
    fun endpoints(gradType: Int, cx: Float, cy: Float, angleDeg: Float, scale: Float): FloatArray {
        val rad = Math.toRadians(angleDeg.toDouble())
        val half = 0.5f * scale
        return when (gradType) {
            GRAD_RADIAL -> floatArrayOf(cx, cy, cx + half, cy)
            GRAD_SWEEP -> floatArrayOf(
                cx, cy,
                cx - Math.cos(rad).toFloat() * half, cy - Math.sin(rad).toFloat() * half
            )
            else -> {
                val dx = Math.sin(rad).toFloat()
                val dy = -Math.cos(rad).toFloat()
                floatArrayOf(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half)
            }
        }
    }

    /** The centre of a stored gradient, recovered from its end points. */
    fun center(gradType: Int, x1: Float, y1: Float, x2: Float, y2: Float): Pair<Float, Float> =
        if (gradType == GRAD_LINEAR) (x1 + x2) / 2f to (y1 + y2) / 2f else x1 to y1

    /**
     * (angle in degrees, scale) of a stored gradient, recovered from its end points — the
     * inverse of [endpoints]. The angle is normalised to [0, 360): a full turn is the same
     * geometry, so the stored points cannot tell 90 from 450. A degenerate (zero-length)
     * gradient reads as angle 0, scale 0.
     */
    fun angleScale(gradType: Int, x1: Float, y1: Float, x2: Float, y2: Float): Pair<Float, Float> {
        val dx = x2 - x1
        val dy = y2 - y1
        return when (gradType) {
            GRAD_RADIAL -> 0f to 2f * dx
            GRAD_SWEEP -> {
                val half = hypot(dx, dy)
                if (half < 1e-6f) 0f to 0f
                else {
                    val a = Math.toDegrees(atan2(-dy.toDouble(), -dx.toDouble())).toFloat()
                    (if (a < 0f) a + 360f else a) to 2f * half
                }
            }
            else -> {
                val len = hypot(dx, dy)
                if (len < 1e-6f) 0f to 0f
                else {
                    val a = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat()
                    (if (a < 0f) a + 360f else a) to len
                }
            }
        }
    }
}
