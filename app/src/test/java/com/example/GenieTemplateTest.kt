package com.example

import com.example.engine.ParentRig
import com.example.model.FILL_IMAGE
import com.example.model.GenieSpec
import com.example.model.GenieTemplate
import com.example.model.LayerType
import com.example.model.TimelineTrack
import com.example.model.renderAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The "Genie" template (Templates programme, reference commission 2026-11-13): ONE group
 * holding the pill -> bar stretch, the scrolling avatar row with its selection rings,
 * and four profile-card subtrees cycling one at a time — all ordinary layers. The pins
 * cover the structure (the full children inventory incl. the exact sample names and
 * activities), the intro law through the real ParentRig (the pill's stored pose IS the
 * bar's rest pose; the loop closes: the frame at the end equals the frame at the
 * start), card-cycle exclusivity with the matching ring lit, the scroll law (avatar
 * centres strictly decreasing, evenly spaced), the background's black -> grey -> black
 * states, in-frame checks on the native 9:16 canvas and on 16:9, and two-insert
 * independence. Reference numbers are recomputed from the SPEC's measured literals,
 * not from the builder's own helpers.
 */
class GenieTemplateTest {

    private val pw = 720f                // the reference's own canvas, 9:16
    private val ph = 1280f
    private val at0 = 2000L
    private val avatars = (0 until 6).map { "file:///avatar-${it + 1}.png" }
    private val photos = (0 until 4).map { "file:///photo-${it + 1}.png" }
    private val ins = GenieTemplate.buildGroup(pw, ph, avatars, photos, at0, idBase = "test")
    private val box = pw * 130f / 360f   // 260: the shape box at this width

    private val rig = ParentRig(ins.lanesTopFirst) { c, t -> c.renderAt(t) }
    private fun at(clip: com.example.model.TimelineClip, relMs: Long) = rig.adjusted(clip, at0 + relMs)

    @Test
    fun structureOneGroupFullInventory() {
        assertEquals(LayerType.GROUP, ins.group.type)
        assertEquals("Genie", ins.group.name)
        assertEquals(at0, ins.group.startMs)
        assertEquals(GenieSpec.DURATION_MS, ins.group.durationMs)
        // bg + bar + shadow + streak + 8 petals + 6 avatars + 6 names + 4 rings + 4 cards x 15
        assertEquals(88, ins.children.size)
        assertEquals(89, ins.lanesTopFirst.size)
        assertEquals(ins.group, ins.lanesTopFirst.first())
        for (c in ins.children) {
            assertEquals("child ${c.name} must belong to the group", ins.group.id, c.groupId)
            assertEquals("child ${c.name} must REST-link to the group", ins.group.id, c.parent?.id)
            assertTrue("no rig in the Genie template (${c.name})", c.controllerId == null)
        }
        assertEquals(6, ins.avatars.size)
        assertEquals(listOf("Me", "Liam", "Noah", "Emma", "Chloe", "Steph"), ins.avatarNames.map { it.text })
        for ((i, a) in ins.avatars.withIndex()) {
            assertEquals(FILL_IMAGE, a.fillMode)
            assertEquals(avatars[i], a.fillImageUri)
            assertEquals("CIRCLE", a.shapePattern)
        }
        assertEquals(listOf("LIAM", "NOAH", "EMMA", "CHLOE"), ins.cards.map { it.title.text })
        assertEquals(
            listOf("Scenic walk together", "Birdwatching with Noah", "Picnic by the lake", "Eating tapas at The Ranch"),
            ins.cards.map { it.activityText.text }
        )
        for ((i, card) in ins.cards.withIndex()) {
            assertEquals(15, card.all.size)
            assertEquals(FILL_IMAGE, card.photo.fillMode)
            assertEquals(photos[i], card.photo.fillImageUri)
            assertEquals("Genie suggests", card.suggestsText.text)
            assertEquals("Invite", card.inviteText.text)
            assertEquals(8, card.petals.size)
        }
        assertEquals(4, ins.rings.size)
        assertEquals(8, ins.petals.size)
    }

    @Test
    fun introLawPillStretchesIntoTheBarAndTheLoopCloses() {
        // The evaluated first frame IS the pill (the video's first frame).
        val first = at(ins.bar, 0L)
        assertEquals(0.203f * pw / box, first.shapeScaleX, 0.01f)
        assertEquals(0.098f * pw / box, first.shapeScaleY, 0.01f)
        assertEquals(0f, first.rotationDeg, 0.1f)
        // At the stretch's end the bar is at its full measured size and tilt.
        val barEnd = at(ins.bar, 2100L)
        assertEquals(0.92f * pw / box, barEnd.shapeScaleX, 0.01f)
        assertEquals(0.13f * pw / box, barEnd.shapeScaleY, 0.01f)
        assertEquals(20f, barEnd.rotationDeg, 0.1f)
        assertEquals(GenieSpec.BAR_CY_FRAC * ph, barEnd.transformY, 0.5f)
        // Mid-stretch the bar is between pill and bar on both axes.
        val mid = at(ins.bar, 1550L)
        assertTrue(mid.shapeScaleX > first.shapeScaleX && mid.shapeScaleX < barEnd.shapeScaleX)
        // The loop closes: the last frame's bar pose equals the first frame's.
        val tail = at(ins.bar, GenieSpec.DURATION_MS - 1L)
        assertEquals(first.shapeScaleX, tail.shapeScaleX, 0.01f)
        assertEquals(first.shapeScaleY, tail.shapeScaleY, 0.01f)
        assertEquals(0f, tail.rotationDeg, 0.1f)
        assertEquals(0f, tail.transformY, 0.5f)
        // The streak flashes only during the stretch.
        assertTrue(at(ins.streak, 1500L).opacity > 50f)
        assertTrue(at(ins.streak, 5000L).opacity < 1f)
    }

    @Test
    fun backgroundFadesBlackGreyBlack() {
        assertEquals(GenieSpec.BG_BLACK, at(ins.background, 0L).textColor)
        assertEquals(GenieSpec.BG_BLACK, at(ins.background, 1500L).textColor)
        assertEquals(GenieSpec.BG_GREY, at(ins.background, 9000L).textColor)
        assertEquals(GenieSpec.BG_BLACK, at(ins.background, GenieSpec.DURATION_MS - 1L).textColor)
    }

    @Test
    fun cardCycleExclusivityAndMatchingRing() {
        val midCycle = listOf(4750L, 7250L, 9750L, 12250L)
        for ((i, t) in midCycle.withIndex()) {
            val visible = ins.cards.indices.filter { at(ins.cards[it].photo, t).opacity > 50f }
            assertEquals("exactly card $i visible at $t", listOf(i), visible)
            // The featured avatar (card i shows avatar i+1) wears the lit ring.
            for ((r, ring) in ins.rings.withIndex()) {
                val o = at(ring, t).opacity
                if (r == i) assertTrue("ring $r lit at $t", o > 50f) else assertTrue("ring $r dark at $t", o < 50f)
            }
        }
        // Between cycles nothing shows; before the first cycle nothing shows.
        for (t in listOf(1000L, 3400L, 13500L)) {
            assertTrue("no card at $t", ins.cards.all { at(it.photo, t).opacity < 50f })
        }
    }

    @Test
    fun scrollLawRowMovesSteadilyAndStaysEvenlySpaced() {
        fun xs(t: Long) = ins.avatars.map { at(it, t).transformX }
        val early = xs(3000L)
        val mid = xs(8000L)
        val late = xs(13000L)
        for (i in 0 until 6) {
            assertTrue("avatar $i scrolls left", early[i] > mid[i] && mid[i] > late[i])
        }
        // Even spacing is preserved while scrolling (rigid row): consecutive gaps equal.
        val gaps = (0 until 5).map { mid[it + 1] - mid[it] }
        for (g in gaps) assertEquals(gaps[0], g, 1.5f)
        // The whole scroll travel (window endpoints) is the spec's drift, projected
        // on x by the bar's tilt.
        val travelX = GenieSpec.SCROLL_FRAC * pw *
            kotlin.math.cos(Math.toRadians(GenieSpec.BAR_TILT_DEG.toDouble())).toFloat()
        assertEquals(travelX, xs(2600L)[0] - xs(13500L)[0], 2f)
    }

    @Test
    fun inFrameOnPortraitAndLandscape() {
        for ((w, h) in listOf(720f to 1280f, 1280f to 720f)) {
            val g = GenieTemplate.buildGroup(w, h, avatars, photos, 0L, idBase = "frame")
            val r = ParentRig(g.lanesTopFirst) { c, t -> c.renderAt(t) }
            for (t in listOf(0L, 1500L, 5000L, 8000L, 11000L, 14500L)) {
                val bar = r.adjusted(g.bar, t)
                assertTrue("bar centre in frame at $t on ${w}x$h", abs(bar.transformX) <= w / 2f && abs(bar.transformY) <= h / 2f)
                for (a in g.avatars) {
                    val e = r.adjusted(a, t)
                    assertTrue("avatar centre in frame at $t", abs(e.transformX) <= w / 2f && abs(e.transformY) <= h / 2f)
                }
                for (card in g.cards) {
                    val p = r.adjusted(card.photo, t)
                    assertTrue("card centre in frame at $t", abs(p.transformX) <= w / 2f && abs(p.transformY) <= h / 2f)
                }
            }
        }
    }

    @Test
    fun twoInsertsAreIndependent() {
        val other = GenieTemplate.buildGroup(pw, ph, avatars, photos, at0, idBase = "other")
        val idsA = ins.lanesTopFirst.map { it.id }.toSet()
        val idsB = other.lanesTopFirst.map { it.id }.toSet()
        assertTrue(idsA.intersect(idsB).isEmpty())
        assertTrue(ins.group.id != other.group.id)
        assertEquals(ins.children.size, other.children.size)
    }
}
