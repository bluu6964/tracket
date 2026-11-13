package com.example

import com.example.engine.ParentRig
import com.example.model.DonutChartSpec
import com.example.model.DonutChartTemplate
import com.example.model.LayerType
import com.example.model.flattenPathSegments
import com.example.model.pieSegmentsAt
import com.example.model.renderAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

/**
 * The "Donut Chart" template (Templates programme, reference commission batch 2):
 * ONE group holding five `.pie` segment layers that build one by one around a
 * punched-out centre, each with its % label and its legend line. The pins cover
 * the structure (the full inventory, the exact shares/spans/legend texts), the
 * SWEEP LAW per segment measured from the evaluated outlines (area against the
 * disc's, through the same `pieSegmentsAt` the renderers use): empty at its
 * window's start, its exact share at its window's end, the five shares summing
 * to the full disc; the GROWTH DIRECTION (segment 1 fills the left half, the
 * others run clockwise down the right side); label/legend timing through the
 * real ParentRig; and two-insert independence.
 */
class DonutChartTemplateTest {

    private val pw = 720f
    private val ph = 1280f
    private val at0 = 2000L
    private val ins = DonutChartTemplate.buildGroup(pw, ph, at0, idBase = "test")

    private val rig = ParentRig(ins.lanesTopFirst) { c, t -> c.renderAt(t) }
    private fun at(clip: com.example.model.TimelineClip, relMs: Long) = rig.adjusted(clip, at0 + relMs)

    /** The filled fraction of the disc for [clip]'s evaluated outline at [relMs]. */
    private fun filledFraction(clip: com.example.model.TimelineClip, relMs: Long): Double {
        val pts = flattenPathSegments(clip.pieSegmentsAt(relMs))
        var area = 0.0
        for (i in pts.indices) {
            val (x1, y1) = pts[i]
            val (x2, y2) = pts[(i + 1) % pts.size]
            area += x1.toDouble() * y2.toDouble() - x2.toDouble() * y1.toDouble()
        }
        return abs(area) / 2.0 / PI
    }

    /** The centroid angle (authored degrees: 0 at +x, 90 at the top, CCW) of the arc points. */
    private fun centroidAngleDeg(clip: com.example.model.TimelineClip, relMs: Long): Double {
        val pts = flattenPathSegments(clip.pieSegmentsAt(relMs))
            .filter { kotlin.math.hypot(it.first.toDouble(), it.second.toDouble()) > 0.5 }
        val mx = pts.map { it.first }.average()
        val my = pts.map { it.second }.average()
        var deg = Math.toDegrees(atan2(-my, mx))
        if (deg < -180.0) deg += 360.0
        return deg
    }

    @Test
    fun structureOneGroupFiveSegments() {
        assertEquals(LayerType.GROUP, ins.group.type)
        assertEquals("Donut Chart", ins.group.name)
        assertEquals(DonutChartSpec.DURATION_MS, ins.group.durationMs)
        // bg + title + hole + 2 arcs + 5 x (wedge + label + dot + legend)
        assertEquals(25, ins.children.size)
        assertEquals(26, ins.lanesTopFirst.size)
        for (c in ins.children) {
            assertEquals(ins.group.id, c.groupId)
            assertEquals(ins.group.id, c.parent?.id)
            assertTrue(c.controllerId == null)
        }
        assertEquals(listOf(50, 20, 15, 10, 5), DonutChartSpec.PERCENTS)
        assertEquals(100, DonutChartSpec.PERCENTS.sum())
        assertEquals(DonutChartSpec.PERCENTS.map { "$it%" }, ins.segments.map { it.label.text })
        assertEquals(DonutChartSpec.LEGEND, ins.segments.map { it.legendText.text })
        assertEquals("0x", ins.title.text)
        for (s in ins.segments) {
            assertEquals("PATH", s.wedge.shapePattern)
        }
        // The hole is the background's own black, sized by the spec.
        assertEquals(DonutChartSpec.BG, ins.hole.textColor)
        assertEquals(
            DonutChartSpec.HOLE_FRAC * pw / (pw * 130f / 360f),
            ins.hole.shapeScaleX, 0.001f
        )
    }

    @Test
    fun sweepLawEachSegmentBuildsToItsShare() {
        for ((i, s) in ins.segments.withIndex()) {
            val (t0, t1) = DonutChartSpec.WINDOWS[i]
            val share = DonutChartSpec.PERCENTS[i] / 100.0
            assertTrue("segment $i empty at its window start", filledFraction(s.wedge, t0) < 0.01)
            val atEnd = filledFraction(s.wedge, t1)
            assertTrue("segment $i at its share $share (was $atEnd)", abs(atEnd - share) < 0.03)
            val mid = filledFraction(s.wedge, (t0 + t1) / 2L)
            assertTrue("segment $i mid-build between (was $mid)", mid > 0.005 && mid < share)
        }
        // After the last window the five shares tile the whole disc.
        val total = ins.segments.sumOf { filledFraction(it.wedge, 9900L) }
        assertTrue("segments tile the disc (was $total)", abs(total - 1.0) < 0.04)
    }

    @Test
    fun growthDirectionFirstSegmentLeftOthersClockwise() {
        // Segment 1 sweeps counterclockwise from the top: mid-build its mass sits
        // in the upper-left (authored angles between 90 and 270).
        val a1 = centroidAngleDeg(ins.segments[0].wedge, 1150L)
        assertTrue("segment 1 mid-build centroid in the left half (was $a1)", a1 in 120.0..240.0)
        // Segment 2 grows clockwise from the top into the upper-right (18..90).
        val a2 = centroidAngleDeg(ins.segments[1].wedge, 2650L)
        assertTrue("segment 2 mid-build centroid upper-right (was $a2)", a2 in 20.0..88.0)
        // Segment 5 ends at the bottom (its span is -90..-72).
        val a5 = centroidAngleDeg(ins.segments[4].wedge, 6300L)
        assertTrue("segment 5 sits at the bottom (was $a5)", a5 in -95.0..-67.0)
    }

    @Test
    fun labelsAndLegendFollowTheirSegment() {
        for ((i, s) in ins.segments.withIndex()) {
            val (_, t1) = DonutChartSpec.WINDOWS[i]
            assertTrue("label $i hidden before its segment lands", at(s.label, t1 - 200L).opacity < 50f)
            assertTrue("label $i shown after", at(s.label, t1 + 250L).opacity > 50f)
            // The label sits outside the ring at its span's mid-angle.
            val lp = DonutChartSpec.labelPos(i, pw, ph)
            assertEquals(lp.first, s.label.transformX, 0.5f)
            assertEquals(lp.second, s.label.transformY, 0.5f)
            val legendStart = DonutChartSpec.WINDOWS[i].first + 400L
            assertTrue("legend $i hidden before", at(s.legendText, legendStart - 1L).opacity < 50f)
            assertTrue("legend $i shown after", at(s.legendText, legendStart + 450L).opacity > 50f)
            assertEquals(DonutChartSpec.GRADIENTS[i].second, s.dot.textColor)
        }
    }

    @Test
    fun inFramePortraitLandscapeRingCentred() {
        for ((w, h) in listOf(720f to 1280f, 1280f to 720f)) {
            val g = DonutChartTemplate.buildGroup(w, h, 0L, idBase = "frame")
            val r = DonutChartSpec.R_FRAC * w
            assertTrue("ring fits horizontally", r < w / 2f)
            for (s in g.segments) {
                assertTrue("label x in frame", abs(s.label.transformX) < w / 2f)
            }
            if (h >= w) {
                val cy = DonutChartSpec.CY_FRAC * h
                assertTrue("ring fits vertically on portrait", abs(cy) + r <= h / 2f)
                for (s in g.segments) {
                    assertTrue("legend y in frame", abs(s.legendText.transformY) < h / 2f)
                }
            }
        }
    }

    @Test
    fun twoInsertsAreIndependent() {
        val other = DonutChartTemplate.buildGroup(pw, ph, at0, idBase = "other")
        val idsA = ins.lanesTopFirst.map { it.id }.toSet()
        val idsB = other.lanesTopFirst.map { it.id }.toSet()
        assertTrue(idsA.intersect(idsB).isEmpty())
        assertEquals(ins.children.size, other.children.size)
    }
}
