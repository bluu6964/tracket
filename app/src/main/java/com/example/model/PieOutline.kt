package com.example.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The outline of Alight Motion's `.pie` shape: a wedge — an arc of [radius] about the layer's
 * centre, closed by two straight radii to the centre point. This is a line-by-line port of the
 * getPath / createArc / createSmallArc script in AM's own shape definition (recovered from the
 * public decompiled AM assets, `assets/shapes/pie.xml`; copy in
 * `after-motion-dev/hunt-staging/shape-defs/def_pie.xml`), in double precision throughout, like
 * the JavaScript original — including its angle conventions:
 *
 *  - the authored angles are NEGATED into the script's frame (and offset by +2000 turns, so the
 *    mod operations below always see positive values), which puts an authored angle θ at the
 *    on-screen point (r·cos θ, −r·sin θ): angles grow counterclockwise on screen, 0° at +x,
 *    90° at the top — the same frame the `.poly` / `.star` ports build in;
 *  - the "diff-ratio wrap rule": with Δ = |start − end| (in turns, mod 2), when Δ < 1 the LOWER
 *    of the two negated angles is pushed a full turn up, so the arc is drawn the LONG way around —
 *    the drawn sweep is 360° − Δ, starting at the LARGER authored angle's point and running in
 *    the direction of increasing authored angle. In other words the wedge is the disc MINUS the
 *    slice from the smaller angle up to the larger. When Δ ≥ 1 the upper angle is pushed up
 *    instead and the sweep is Δ − 360°. Δ = 0 and Δ = 360° both close the wedge completely
 *    (no arc at all — the discontinuity at Δ = 0 is the script's own: an ε difference still
 *    sweeps nearly the whole disc);
 *  - the arc is a Riskus cubic-bezier approximation, at most 90° per segment.
 *
 * Because every corpus `.pie` keyframes both angles, the wedge is rebuilt from the evaluated
 * angles per frame (see [pieSegmentsAt]); [segments] is that rebuild.
 */
object PieOutline {

    /** [startDeg] / [endDeg] prepared by getPath's wrap rule: the (low, high) pair createArc walks. */
    private fun preparedAngles(startDeg: Double, endDeg: Double): Pair<Double, Double> {
        var startAngle = -startDeg * PI / 180.0
        var endAngle = -endDeg * PI / 180.0
        startAngle += 2000.0 * PI
        endAngle += 2000.0 * PI
        var lowAngle = min(startAngle, endAngle)
        var highAngle = max(startAngle, endAngle)
        val diff = ((highAngle - lowAngle) / (2.0 * PI)) % 2.0
        lowAngle %= (2.0 * PI)
        highAngle %= (2.0 * PI)
        if (diff < 1.0) {
            lowAngle += 2.0 * PI
        } else {
            highAngle += 2.0 * PI
        }
        return lowAngle to highAngle
    }

    /** The arc's sweep in degrees for the authored angles — 0 when the wrap rule closes the wedge. */
    fun sweepDegrees(startDeg: Double, endDeg: Double): Double {
        val (low, high) = preparedAngles(startDeg, endDeg)
        val twoPI = 2.0 * PI
        val angleDiff = (high - low) % twoPI
        val startN = if (abs(low) > twoPI) low % twoPI else low
        val endN = startN + angleDiff
        return Math.toDegrees(min(twoPI, abs(endN - startN)))
    }

    /**
     * The wedge outline for the authored angles at [radius], as [PathSeg]s in AM units (arc start
     * first, then one cubic per arc segment, a line to the centre, closed). Empty when the wrap
     * rule closes the wedge at these angles — the layer draws nothing then, as in Alight.
     */
    fun segments(startDeg: Double, endDeg: Double, radius: Double): List<PathSeg> {
        val (low, high) = preparedAngles(startDeg, endDeg)
        val curves = createArc(radius, low, high)
        if (curves.isEmpty()) return emptyList()
        val segs = ArrayList<PathSeg>(curves.size + 3)
        segs += PathSeg(PATH_OP_MOVE, listOf(curves[0][0].toFloat(), curves[0][1].toFloat()))
        for (c in curves) {
            segs += PathSeg(
                PATH_OP_CUBIC,
                listOf(
                    c[2].toFloat(), c[3].toFloat(), c[4].toFloat(),
                    c[5].toFloat(), c[6].toFloat(), c[7].toFloat()
                )
            )
        }
        segs += PathSeg(PATH_OP_LINE, listOf(0f, 0f))
        segs += PathSeg(PATH_OP_CLOSE)
        return segs
    }

    /** The wedge outline normalised to radius 1 — the basis a pie clip's stored segments use. */
    fun normalisedSegments(startDeg: Double, endDeg: Double): List<PathSeg> =
        segments(startDeg, endDeg, 1.0)

    /** createArc of the def: the arc as a list of Riskus cubics (x1, y1, x2, y2, x3, y3, x4, y4 each). */
    private fun createArc(radius: Double, startAngle: Double, endAngle: Double): List<DoubleArray> {
        val twoPI = 2.0 * PI
        val angleDiff = (endAngle - startAngle) % twoPI
        val startAngleN = if (abs(startAngle) > twoPI) startAngle % twoPI else startAngle
        val endAngleN = startAngleN + angleDiff
        val curves = ArrayList<DoubleArray>()
        val piOverTwo = PI / 2.0
        val sgn = if (startAngle < endAngle) 1.0 else -1.0
        var a1 = startAngle
        var totalAngle = min(twoPI, abs(endAngleN - startAngleN))
        while (totalAngle > 0.00001) {
            val a2 = a1 + sgn * min(totalAngle, piOverTwo)
            curves += createSmallArc(radius, a1, a2)
            totalAngle -= abs(a2 - a1)
            a1 = a2
        }
        return curves
    }

    /**
     * createSmallArc of the def: the Riskus cubic for an arc of at most 90°
     * (A. Riskus, "Approximation of a cubic Bezier Curve by Circular Arcs and Vice Versa",
     * Information Technology and Control, 35(4) 2006 — the def's own citation).
     */
    private fun createSmallArc(r: Double, a1: Double, a2: Double): DoubleArray {
        val a = (a2 - a1) / 2.0
        val x4 = r * cos(a)
        val y4 = r * sin(a)
        val x1 = x4
        val y1 = -y4
        val q1 = x1 * x1 + y1 * y1
        val q2 = q1 + x1 * x4 + y1 * y4
        val k2 = 4.0 / 3.0 * (sqrt(2.0 * q1 * q2) - q2) / (x1 * y4 - y1 * x4)
        val x2 = x1 - k2 * y1
        val y2 = y1 + k2 * x1
        val x3 = x2
        val y3 = -y2
        val ar = a + a1
        val cosAr = cos(ar)
        val sinAr = sin(ar)
        return doubleArrayOf(
            r * cos(a1), r * sin(a1),
            x2 * cosAr - y2 * sinAr, x2 * sinAr + y2 * cosAr,
            x3 * cosAr - y3 * sinAr, x3 * sinAr + y3 * cosAr,
            r * cos(a2), r * sin(a2)
        )
    }
}
