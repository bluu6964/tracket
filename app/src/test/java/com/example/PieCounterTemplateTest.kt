package com.example

import com.example.engine.CounterEval
import com.example.model.FILL_GRADIENT
import com.example.model.FILL_NONE
import com.example.model.LayerType
import com.example.model.PieCounterSpec
import com.example.model.PieCounterTemplate
import com.example.model.flattenPathSegments
import com.example.model.pieSegmentsAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/**
 * The "Pie Counter" template (Templates programme, reference commission batch 2):
 * ONE group holding a background, a `.pie` fill layer and a `.pie` outline layer
 * (the parametric wedge shape the Alight importer builds) and the counting % text
 * (a plain text layer + the app's counter animator). The pins cover the structure,
 * the authored angle law (the fill's startAngle fixed at the top, its endAngle
 * travelling 449.5 -> 89.5 — Alight's wrap rule read as a clockwise fill, with the
 * two closed endpoints avoided by half a degree), the SWEEP LAW measured from the
 * evaluated outlines themselves (the filled polygon's area against the disc's,
 * through the same `pieSegmentsAt` the renderers use), the outline as the exact
 * complement, counter/wedge synchrony through the real CounterEval, and two-insert
 * independence.
 */
class PieCounterTemplateTest {

    private val pw = 720f
    private val ph = 1200f
    private val at0 = 2000L
    private val ins = PieCounterTemplate.buildGroup(pw, ph, at0, idBase = "test")

    /** The filled fraction of the disc for [clip]'s evaluated outline at [relMs]. */
    private fun filledFraction(clip: com.example.model.TimelineClip, relMs: Long): Double {
        val pts = flattenPathSegments(clip.pieSegmentsAt(relMs))
        var area = 0.0
        for (i in pts.indices) {
            val (x1, y1) = pts[i]
            val (x2, y2) = pts[(i + 1) % pts.size]
            area += x1.toDouble() * y2.toDouble() - x2.toDouble() * y1.toDouble()
        }
        return abs(area) / 2.0 / PI   // the outline is normalised to radius 1
    }

    private fun shownPercent(relMs: Long): String =
        CounterEval.displayText(ins.percentText.text ?: "0%", ins.percentText, at0 + relMs) ?: "?"

    @Test
    fun structureOneGroupThreeLayers() {
        assertEquals(LayerType.GROUP, ins.group.type)
        assertEquals("Pie Counter", ins.group.name)
        assertEquals(PieCounterSpec.DURATION_MS, ins.group.durationMs)
        assertEquals(4, ins.children.size)
        assertEquals(5, ins.lanesTopFirst.size)
        for (c in ins.children) {
            assertEquals(ins.group.id, c.groupId)
            assertEquals(ins.group.id, c.parent?.id)
            assertTrue(c.controllerId == null)
        }
        assertEquals("PATH", ins.fill.shapePattern)
        assertEquals("PATH", ins.outline.shapePattern)
        assertEquals(FILL_GRADIENT, ins.fill.fillMode)
        assertEquals(FILL_NONE, ins.outline.fillMode)
        assertEquals("0%", ins.percentText.text)
        assertEquals(1, ins.percentText.textAnimators.size)
        assertEquals(
            "com.alightcreative.effects.counter",
            ins.percentText.textAnimators[0].effectId
        )
    }

    @Test
    fun authoredAngleLaw() {
        // The fill: startAngle pinned at 90 (12 o'clock); endAngle 449.5 -> 89.5.
        assertEquals(1, ins.fill.pieStartKeys.size)
        assertEquals(PieCounterSpec.START_ANGLE, ins.fill.pieStartKeys[0].a, 0.001f)
        assertEquals(2, ins.fill.pieEndKeys.size)
        assertEquals(PieCounterSpec.END_FROM, ins.fill.pieEndKeys[0].a, 0.001f)
        assertEquals(PieCounterSpec.END_TO, ins.fill.pieEndKeys[1].a, 0.001f)
        assertEquals(PieCounterSpec.SWEEP_MS, ins.fill.pieEndKeys[1].timeMs)
        // The outline: the mirror image (endAngle pinned, startAngle travels).
        assertEquals(1, ins.outline.pieEndKeys.size)
        assertEquals(PieCounterSpec.START_ANGLE, ins.outline.pieEndKeys[0].a, 0.001f)
        assertEquals(PieCounterSpec.OUTLINE_FROM, ins.outline.pieStartKeys[0].a, 0.001f)
        assertEquals(PieCounterSpec.OUTLINE_TO, ins.outline.pieStartKeys[1].a, 0.001f)
        // The wrap rule itself, at the law's own checkpoints.
        assertEquals(0.5, PieCounterSpec.sweepDegrees(90f, 449.5f), 0.01)
        assertEquals(180.0, PieCounterSpec.sweepDegrees(90f, 270f), 0.01)
        assertEquals(359.5, PieCounterSpec.sweepDegrees(90f, 89.5f), 0.01)
    }

    @Test
    fun sweepLawFromTheEvaluatedOutlines() {
        assertTrue("starts as a sliver", filledFraction(ins.fill, 0L) < 0.01)
        val mid = filledFraction(ins.fill, 1600L)
        assertTrue("cubic-out mid-sweep ~0.87 (was $mid)", mid in 0.80..0.94)
        val full = filledFraction(ins.fill, PieCounterSpec.SWEEP_MS)
        assertTrue("ends full (was $full)", full in 0.97..1.0)
        assertEquals(full, filledFraction(ins.fill, PieCounterSpec.DURATION_MS - 1L), 0.01)
        // The outline is the complement at every sampled instant.
        for (t in listOf(400L, 1200L, 2000L, 2800L)) {
            val sum = filledFraction(ins.fill, t) + filledFraction(ins.outline, t)
            assertEquals("fill + outline = the disc at $t", 1.0, sum, 0.04)
        }
    }

    @Test
    fun counterStaysInSyncWithTheWedge() {
        assertEquals("0%", shownPercent(0L))
        assertEquals("100%", shownPercent(PieCounterSpec.SWEEP_MS))
        assertEquals("100%", shownPercent(PieCounterSpec.DURATION_MS - 1L))
        for (t in listOf(800L, 1600L, 2400L)) {
            val pct = shownPercent(t).removeSuffix("%").toFloat()
            val sweepPct = filledFraction(ins.fill, t) * 100.0
            assertTrue("counter $pct vs wedge $sweepPct at $t", abs(pct - sweepPct) < 7.0)
        }
    }

    @Test
    fun inFramePortraitLandscapeCentreOnly() {
        for ((w, h) in listOf(720f to 1200f, 1280f to 720f)) {
            val g = PieCounterTemplate.buildGroup(w, h, 0L, idBase = "frame")
            val r = PieCounterSpec.RADIUS_FRAC * w
            // Horizontally the disc always fits (r < w/2); the centre stays in frame.
            assertTrue(r < w / 2f)
            assertEquals(0f, g.fill.transformX, 0.001f)
            if (h >= w) {
                // On portrait canvases the whole disc fits vertically too.
                val cy = PieCounterSpec.PIE_CY_FRAC * h
                assertTrue(abs(cy) + r <= h / 2f)
            }
        }
    }

    @Test
    fun twoInsertsAreIndependent() {
        val other = PieCounterTemplate.buildGroup(pw, ph, at0, idBase = "other")
        val idsA = ins.lanesTopFirst.map { it.id }.toSet()
        val idsB = other.lanesTopFirst.map { it.id }.toSet()
        assertTrue(idsA.intersect(idsB).isEmpty())
        assertEquals(ins.children.size, other.children.size)
    }
}
