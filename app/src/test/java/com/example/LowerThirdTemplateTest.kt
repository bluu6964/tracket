package com.example

import com.example.engine.ParentRig
import com.example.model.AnimProp
import com.example.model.FILL_SOLID
import com.example.model.GroupOps
import com.example.model.LayerType
import com.example.model.LowerThirdSpec
import com.example.model.LowerThirdTemplate
import com.example.model.at
import com.example.model.renderAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The "Lower Third" template (Templates programme; the design banked in the 2026-11-13
 * STOP note): ONE group holding an amber accent bar that grows at the bottom left, a
 * plain NAME text staging in beside it (150..550 ms) and a plain SUBTITLE text at a
 * static 58% staging in after it (300..700 ms), a hold, and a shared slide/fade exit
 * (3500..4000 ms) ending at exactly opacity 0. The pins cover the insert structure, the
 * texts' plainness (retypable text clips — Bengali samples intact), the exact keyframe
 * design, the anchor law (left margin 0.055 x width, bottom margin 0.065 x height, text
 * centres from the HarfBuzz-measured advances), the staging through the real ParentRig,
 * the accent grow law, the shared exit, and the in-frame guarantee on 9:16 and 16:9.
 */
class LowerThirdTemplateTest {

    private val pw = 1080f               // 9:16 canvas — the lower third's home format
    private val ph = 1920f
    private val at0 = 1500L              // the playhead the insert lands on
    private val ins = LowerThirdTemplate.buildGroup(pw, ph, at0, idBase = "test")
    private val box = pw * 130f / 360f   // the app shape box in stored units
    private val slide = LowerThirdSpec.SLIDE_FRAC * pw
    private val nameX = LowerThirdSpec.nameCentreXPx(pw)
    private val nameY = LowerThirdSpec.nameCentreYPx(pw, ph)
    private val subX = LowerThirdSpec.subtitleCentreXPx(pw)
    private val subY = LowerThirdSpec.subtitleCentreYPx(pw, ph)
    private val barX = LowerThirdSpec.barCentreXPx(pw)
    private val barY = LowerThirdSpec.barCentreYPx(pw, ph)
    private val barH = LowerThirdSpec.barHeightPx(pw)

    private val projectTracks = ins.lanesTopFirst.mapIndexed { i, c ->
        com.example.model.TimelineTrack("lane-$i", c.name, clips = listOf(c))
    }
    private val rig = ParentRig(ins.lanesTopFirst) { c, t -> c.renderAt(t) }

    private fun nameAt(relMs: Long) = rig.adjusted(ins.nameLayer, at0 + relMs)
    private fun subAt(relMs: Long) = rig.adjusted(ins.subtitleLayer, at0 + relMs)
    private fun barAt(relMs: Long) = rig.adjusted(ins.bar, at0 + relMs)

    @Test
    fun insertIsOneGroupWithNameSubtitleAndBarInOrder() {
        // Exactly ONE top-level layer is added: the group, at the playhead, 4 s long, REST pose.
        val topLevel = GroupOps.scopeTracks(projectTracks, null).flatMap { it.clips }
        assertEquals(listOf(ins.group.id), topLevel.map { it.id })
        assertEquals(LayerType.GROUP, ins.group.type)
        assertEquals("Lower Third", ins.group.name)
        assertEquals(at0, ins.group.startMs)
        assertEquals(4000L, ins.group.durationMs)
        assertEquals(0f, ins.group.transformX, 0f)
        assertEquals(0f, ins.group.transformY, 0f)
        assertEquals(100f, ins.group.scalePercent, 0f)
        assertTrue("group carries no keyframes", ins.group.keyframes.isEmpty())
        // Children in lane order: name, subtitle, bar; read back through GroupOps.
        assertEquals(listOf("test-name", "test-subtitle", "test-bar"), ins.children.map { it.id })
        assertEquals(
            ins.children.map { it.id },
            GroupOps.childrenOf(projectTracks, ins.group.id).map { it.id }
        )
        assertEquals("Name", ins.nameLayer.name)
        assertEquals("Subtitle", ins.subtitleLayer.name)
        assertEquals("Accent Bar", ins.bar.name)
        // Every child: an ordinary clip of the group, spanning the whole template, linked
        // to the group with the REST reference pose — no rig anywhere (no Null, no
        // Controller: nothing here has that job).
        for (c in ins.children) {
            assertEquals(ins.group.id, c.groupId)
            assertEquals(at0, c.startMs)
            assertEquals(4000L, c.durationMs)
            assertEquals(null, c.controllerId)
            val link = c.parent ?: error("child ${c.id} has no parent link")
            assertEquals(ins.group.id, link.id)
            assertEquals(0f, link.refX, 0f)
            assertEquals(0f, link.refY, 0f)
            assertEquals(100f, link.refScale, 0f)
            assertEquals(0f, link.refRot, 0f)
        }
    }

    @Test
    fun textLayersArePlainEditableTextClips() {
        val n = ins.nameLayer
        assertEquals(LayerType.TEXT, n.type)
        // The Bengali samples, exactly (9 and 13 UTF-16 code units).
        assertEquals(LowerThirdSpec.NAME_TEXT, n.text)
        assertEquals("আপনার নাম", n.text)
        assertEquals(9, n.text!!.length)
        val s = ins.subtitleLayer
        assertEquals(LayerType.TEXT, s.type)
        assertEquals(LowerThirdSpec.SUBTITLE_TEXT, s.text)
        assertEquals("আপনার পরিচিতি", s.text)
        assertEquals(13, s.text!!.length)
        for (t in listOf(n, s)) {
            assertEquals(0xFFFFFFFFL, t.textColor)
            assertEquals(700, t.fontWeight)
            // No special flags: plain text clips with ordinary keyframes.
            assertEquals(null, t.animIn)
            assertEquals(null, t.animOut)
            assertTrue(t.effects.isEmpty())
            assertTrue(t.masks.isEmpty())
            assertTrue(t.textAnimators.isEmpty())
            assertTrue(!t.isMask)
            assertEquals(100f, t.opacity, 0f)
        }
        // The scales are STATIC fields — the name at the plain text size, the subtitle
        // at the captions-convention 58% — with no SCALE track on either layer.
        assertEquals(100f, n.scalePercent, 0f)
        assertEquals(58f, s.scalePercent, 0f)
        assertTrue("name scale is static", !n.keyframes.containsKey(AnimProp.SCALE))
        assertTrue("subtitle scale is static", !s.keyframes.containsKey(AnimProp.SCALE))
        // Stored values are the rest pose; the keyframes carry the performance.
        assertEquals(nameX, n.transformX, 0.01f)
        assertEquals(nameY, n.transformY, 0.01f)
        assertEquals(subX, s.transformX, 0.01f)
        assertEquals(subY, s.transformY, 0.01f)
        // Retyping keeps the animation: copies with different words evaluate identically.
        val retypedName = n.copy(text = "সারোয়ার হোসেন")
        val retypedSub = s.copy(text = "ভিডিও এডিটর")
        for (rel in listOf(0L, 350L, 550L, 3750L, 4000L)) {
            val a = n.at(at0 + rel)
            val b = retypedName.at(at0 + rel)
            assertEquals("name opacity after retype at $rel", a.opacity, b.opacity, 0.001f)
            assertEquals("name x after retype at $rel", a.transformX, b.transformX, 0.001f)
        }
        for (rel in listOf(0L, 500L, 700L, 3750L, 4000L)) {
            val a = s.at(at0 + rel)
            val b = retypedSub.at(at0 + rel)
            assertEquals("subtitle opacity after retype at $rel", a.opacity, b.opacity, 0.001f)
            assertEquals("subtitle x after retype at $rel", a.transformX, b.transformX, 0.001f)
        }
    }

    @Test
    fun keyframeDesignIsExactlyThePerformance() {
        // --- the two staged texts share one design, offset by their staging windows ---
        fun checkText(
            t: com.example.model.TimelineClip, startMs: Long, endMs: Long,
            x: Float, y: Float
        ) {
            assertEquals(setOf(AnimProp.POSITION, AnimProp.OPACITY), t.keyframes.keys)
            val pos = t.keyframes.getValue(AnimProp.POSITION)
            assertEquals(listOf(startMs, endMs, 3500L, 4000L), pos.map { it.timeMs })
            assertEquals(listOf(x - slide, x, x, x - slide), pos.map { it.a })
            for (k in pos) assertEquals(y, k.b, 0f)
            assertEquals(LowerThirdSpec.EASE_CUBIC_OUT, pos[0].easing)
            assertEquals(LowerThirdSpec.EASE_CUBIC_IN, pos[2].easing)
            val op = t.keyframes.getValue(AnimProp.OPACITY)
            assertEquals(listOf(startMs, endMs, 3500L, 4000L), op.map { it.timeMs })
            assertEquals(listOf(0f, 100f, 100f, 0f), op.map { it.a })
            assertEquals(LowerThirdSpec.EASE_CUBIC_OUT, op[0].easing)
            assertEquals(LowerThirdSpec.EASE_CUBIC_IN, op[2].easing)
        }
        checkText(ins.nameLayer, 150L, 550L, nameX, nameY)
        checkText(ins.subtitleLayer, 300L, 700L, subX, subY)

        // --- the bar: grow, hold, shared exit ---
        val b = ins.bar
        assertEquals(
            setOf(AnimProp.SCALE, AnimProp.POSITION, AnimProp.OPACITY),
            b.keyframes.keys
        )
        val bScale = b.keyframes.getValue(AnimProp.SCALE)
        assertEquals(listOf(0L, 250L), bScale.map { it.timeMs })
        // Width share constant across the grow; height share from the hair to full.
        assertEquals(bScale[0].a, bScale[1].a, 0f)
        assertEquals(b.shapeScaleX * 100f, bScale[1].a, 0.01f)
        assertEquals(LowerThirdSpec.GROW_START_PCT, bScale[0].b, 0f)
        assertEquals(b.shapeScaleY * 100f, bScale[1].b, 0.01f)
        assertEquals(LowerThirdSpec.EASE_CUBIC_OUT, bScale[0].easing)
        val bPos = b.keyframes.getValue(AnimProp.POSITION)
        assertEquals(listOf(0L, 3500L, 4000L), bPos.map { it.timeMs })
        assertEquals(listOf(barX, barX, barX - slide), bPos.map { it.a })
        for (k in bPos) assertEquals(barY, k.b, 0f)
        assertEquals(LowerThirdSpec.EASE_CUBIC_IN, bPos[1].easing)
        val bOp = b.keyframes.getValue(AnimProp.OPACITY)
        assertEquals(listOf(0L, 3500L, 4000L), bOp.map { it.timeMs })
        assertEquals(listOf(100f, 100f, 0f), bOp.map { it.a })
        assertEquals(LowerThirdSpec.EASE_CUBIC_IN, bOp[1].easing)
    }

    @Test
    fun anchorLawPlacesTheStrap() {
        // The measured advances the centre law rests on (HarfBuzz over Noto Sans Bengali
        // Bold — the same measurement that reproduces the Word Pop pins exactly).
        assertEquals(5.439f, LowerThirdSpec.NAME_ADV_EM, 0f)
        assertEquals(7.487f, LowerThirdSpec.SUBTITLE_ADV_EM, 0f)
        // The block's left edge: 5.5% of the width in from the frame's left edge.
        val barLeft = barX - LowerThirdSpec.BAR_W_FRAC * pw / 2f
        assertEquals(-pw / 2f + 0.055f * pw, barLeft, 0.5f)
        // The block's bottom edge: 6.5% of the height up from the frame's bottom — the
        // subtitle's line bottom, and the bar's bottom, are both exactly there.
        val blockBottom = LowerThirdSpec.blockBottomPx(ph)
        assertEquals(ph / 2f - 0.065f * ph, blockBottom, 0.5f)
        assertEquals(blockBottom, subY + LowerThirdSpec.subtitleLineHPx(pw) / 2f, 0.01f)
        assertEquals(blockBottom, barY + barH / 2f, 0.01f)
        // The bar's top is the name line's top (the bar spans exactly the two lines).
        assertEquals(nameY - LowerThirdSpec.nameLineHPx(pw) / 2f, barY - barH / 2f, 0.01f)
        // Both texts share ONE left edge: bar + gap from the block edge; recomputed from
        // the raw fractions and the measured advances, not from the spec's helpers.
        val textLeft = -pw / 2f + (0.055f + 0.013f + 0.018f) * pw
        assertEquals(textLeft, LowerThirdSpec.textLeftPx(pw), 0.5f)
        val nameAdvPx = 5.439f * 0.11f * pw
        val subAdvPx = 7.487f * 0.11f * pw * 0.58f
        assertEquals(textLeft, nameX - nameAdvPx / 2f, 0.5f)
        assertEquals(textLeft, subX - subAdvPx / 2f, 0.5f)
        // The vertical stack: name line, gap, subtitle line — nothing else.
        val gapPx = (subY - LowerThirdSpec.subtitleLineHPx(pw) / 2f) -
            (nameY + LowerThirdSpec.nameLineHPx(pw) / 2f)
        assertEquals(0.016f * pw, gapPx, 0.01f)
    }

    @Test
    fun barGrowsFirstThenNameThenSubtitleStageIn() {
        // The bar grows 0..250 ms (see barDesignAndGrowLaw for the shape law): by the
        // time either text lands, the strip is full height.
        assertEquals(barH, barAt(250L).shapeScaleY * box, 0.5f)
        // Before its turn the name waits invisible at its slid-left pose.
        for (rel in listOf(0L, 100L, 149L)) {
            val a = nameAt(rel)
            assertEquals("name waiting invisible at $rel", 0f, a.opacity, 0.01f)
            assertEquals("name waiting left at $rel", nameX - slide, a.transformX, 0.01f)
            assertEquals(nameY, a.transformY, 0.01f)
        }
        // The subtitle waits too — even while the name is already mid-flight.
        for (rel in listOf(0L, 200L, 299L)) {
            val a = subAt(rel)
            assertEquals("subtitle waiting invisible at $rel", 0f, a.opacity, 0.01f)
            assertEquals("subtitle waiting left at $rel", subX - slide, a.transformX, 0.01f)
        }
        assertTrue("name underway at 299", nameAt(299L).opacity in 1f..99f)
        // Mid-staging: opacity strictly rising, x settling from the left.
        var prevOp = -1f
        for (rel in listOf(250L, 350L, 450L)) {
            val a = nameAt(rel)
            assertTrue("name opacity rising at $rel", a.opacity > prevOp && a.opacity < 100f)
            prevOp = a.opacity
            assertTrue("name x settling at $rel", a.transformX > nameX - slide && a.transformX < nameX)
            assertEquals(nameY, a.transformY, 0.01f)
        }
        var prevSub = -1f
        for (rel in listOf(400L, 500L, 600L)) {
            val a = subAt(rel)
            assertTrue("subtitle opacity rising at $rel", a.opacity > prevSub && a.opacity < 100f)
            prevSub = a.opacity
            assertTrue("subtitle x settling at $rel", a.transformX > subX - slide && a.transformX < subX)
        }
        // Landed in order: name at 550 (subtitle still travelling), subtitle at 700 —
        // both exactly on their rest poses, scales untouched throughout.
        val nameLanded = nameAt(550L)
        assertEquals(100f, nameLanded.opacity, 0.01f)
        assertEquals(nameX, nameLanded.transformX, 0.01f)
        assertTrue("subtitle still travelling at 550", subAt(550L).opacity < 100f)
        val subLanded = subAt(700L)
        assertEquals(100f, subLanded.opacity, 0.01f)
        assertEquals(subX, subLanded.transformX, 0.01f)
        for (rel in listOf(0L, 350L, 700L, 2000L)) {
            assertEquals("name scale static at $rel", 100f, nameAt(rel).scalePercent, 0f)
            assertEquals("subtitle scale static at $rel", 58f, subAt(rel).scalePercent, 0f)
        }
    }

    @Test
    fun holdIsStillAndSharedExitEndsAtExactlyZero() {
        // The hold: from the last landing (700) to the exit start (3500) nothing moves.
        for (rel in listOf(700L, 1500L, 2500L, 3500L)) {
            assertEquals("name held at $rel", 100f, nameAt(rel).opacity, 0.01f)
            assertEquals(nameX, nameAt(rel).transformX, 0.01f)
            assertEquals("subtitle held at $rel", 100f, subAt(rel).opacity, 0.01f)
            assertEquals(subX, subAt(rel).transformX, 0.01f)
            assertEquals("bar held at $rel", 100f, barAt(rel).opacity, 0.01f)
            assertEquals(barX, barAt(rel).transformX, 0.01f)
            assertEquals(barH, barAt(rel).shapeScaleY * box, 0.5f)
        }
        // The exit is SHARED: at every sampled instant all three layers carry the same
        // opacity and have slid left by the same amount.
        for (rel in listOf(3600L, 3750L, 3900L)) {
            val no = nameAt(rel).opacity
            assertEquals("shared opacity at $rel (subtitle)", no, subAt(rel).opacity, 0.001f)
            assertEquals("shared opacity at $rel (bar)", no, barAt(rel).opacity, 0.001f)
            val nd = nameX - nameAt(rel).transformX
            assertEquals("shared slide at $rel (subtitle)", nd, subX - subAt(rel).transformX, 0.001f)
            assertEquals("shared slide at $rel (bar)", nd, barX - barAt(rel).transformX, 0.001f)
            assertTrue("sliding at $rel", nd > 0f && nd < slide)
        }
        val mid = nameAt(3750L)
        assertTrue("mid-exit opacity: ${mid.opacity}", mid.opacity > 40f && mid.opacity < 95f)
        // Nearly gone just before the end…
        for (rel in listOf(3999L)) {
            assertTrue("name nearly gone at $rel", nameAt(rel).opacity < 5f)
            assertTrue("subtitle nearly gone at $rel", subAt(rel).opacity < 5f)
            assertTrue("bar nearly gone at $rel", barAt(rel).opacity < 5f)
        }
        // …and at the last instant the exit ends at EXACTLY opacity 0 on all three.
        assertEquals(0f, nameAt(4000L).opacity, 0f)
        assertEquals(0f, subAt(4000L).opacity, 0f)
        assertEquals(0f, barAt(4000L).opacity, 0f)
        assertEquals(nameX - slide, nameAt(4000L).transformX, 0.01f)
        assertEquals(subX - slide, subAt(4000L).transformX, 0.01f)
        assertEquals(barX - slide, barAt(4000L).transformX, 0.01f)
    }

    @Test
    fun barDesignAndGrowLaw() {
        val b = ins.bar
        assertEquals(LayerType.SHAPE, b.type)
        assertEquals("ROUNDED_RECT", b.shapePattern)
        assertEquals(FILL_SOLID, b.fillMode)
        assertEquals(LowerThirdSpec.ACCENT, b.textColor)
        assertEquals(0xFFFFB300L, LowerThirdSpec.ACCENT)
        assertEquals(65f, b.shapeCornerRadius, 0f)   // stadium ends
        // Stored geometry: a thin vertical strip spanning the two text lines.
        assertEquals(0.013f * pw, b.shapeScaleX * box, 0.5f)
        assertEquals(barH, b.shapeScaleY * box, 0.5f)
        assertEquals(barX, b.transformX, 0.01f)
        assertEquals(barY, b.transformY, 0.01f)
        assertEquals(b.shapeScaleX * 100f, b.scalePercent, 0.01f)
        // The grow: at the first instant the height is a hair (under 1% of full); it
        // rises monotonically and is exactly full at 250 ms; the WIDTH never changes.
        assertTrue("hair at 0: ${barAt(0L).shapeScaleY * box}", barAt(0L).shapeScaleY * box < barH * 0.01f)
        var prev = -1f
        var rel = 25L
        while (rel <= 250L) {
            val h = barAt(rel).shapeScaleY * box
            assertTrue("growing at $rel", h > prev && h <= barH + 0.5f)
            prev = h
            assertEquals("width constant at $rel", 0.013f * pw, barAt(rel).shapeScaleX * box, 0.5f)
            rel += 25L
        }
        assertEquals(barH, barAt(250L).shapeScaleY * box, 0.5f)
        // The bar never turns, and its centre is fixed until the exit slide.
        for (r in listOf(0L, 125L, 250L, 1000L, 3500L)) {
            val a = barAt(r)
            assertEquals("no turn at $r", 0f, a.rotationDeg, 0f)
            assertEquals("bar x fixed at $r", barX, a.transformX, 0.01f)
            assertEquals("bar y fixed at $r", barY, a.transformY, 0.01f)
        }
    }

    /** In-frame check for one canvas: the whole strap stays inside it at every instant. */
    private fun assertInFrame(w: Float, h: Float, idBase: String) {
        val i2 = LowerThirdTemplate.buildGroup(w, h, 0L, idBase = idBase)
        val rig2 = ParentRig(i2.lanesTopFirst) { c, t -> c.renderAt(t) }
        val box2 = w * 130f / 360f
        val font = 0.11f * w
        val nameAdv = LowerThirdSpec.NAME_ADV_EM * font
        val subAdv = LowerThirdSpec.SUBTITLE_ADV_EM * font * 0.58f
        for (rel in listOf(0L, 125L, 250L, 400L, 550L, 700L, 2000L, 3500L, 3750L, 4000L)) {
            val b = rig2.adjusted(i2.bar, rel)
            val bHalfW = b.shapeScaleX * box2 / 2f
            val bHalfH = b.shapeScaleY * box2 / 2f
            assertTrue("bar left in frame $idBase at $rel", b.transformX - bHalfW >= -w / 2f - 0.5f)
            assertTrue("bar right in frame $idBase at $rel", b.transformX + bHalfW <= w / 2f + 0.5f)
            assertTrue("bar top in frame $idBase at $rel", b.transformY - bHalfH >= -h / 2f - 0.5f)
            assertTrue("bar bottom in frame $idBase at $rel", b.transformY + bHalfH <= h / 2f + 0.5f)
            val n = rig2.adjusted(i2.nameLayer, rel)
            // The name's drawn line is its advance wide, centred on the layer position —
            // even mid-slide (its leftmost pose) it stays inside the frame.
            assertTrue("name left in frame $idBase at $rel", n.transformX - nameAdv / 2f >= -w / 2f - 0.5f)
            assertTrue("name right in frame $idBase at $rel", n.transformX + nameAdv / 2f <= w / 2f + 0.5f)
            assertTrue("name top in frame $idBase at $rel", n.transformY - font / 2f >= -h / 2f - 0.5f)
            assertTrue("name bottom in frame $idBase at $rel", n.transformY + font / 2f <= h / 2f + 0.5f)
            val s = rig2.adjusted(i2.subtitleLayer, rel)
            assertTrue("subtitle left in frame $idBase at $rel", s.transformX - subAdv / 2f >= -w / 2f - 0.5f)
            assertTrue("subtitle right in frame $idBase at $rel", s.transformX + subAdv / 2f <= w / 2f + 0.5f)
            assertTrue(
                "subtitle bottom in frame $idBase at $rel",
                s.transformY + font * 0.58f / 2f <= h / 2f + 0.5f
            )
            assertTrue("opacity sane $idBase at $rel", n.opacity in 0f..100f && s.opacity in 0f..100f)
        }
        // The group itself is the identity on both canvases: the insert was authored
        // against the real canvas, so nothing needs rescaling to fit.
        assertEquals(100f, i2.group.scalePercent, 0f)
    }

    @Test
    fun strapStaysInFrameOn916And169() {
        assertInFrame(1080f, 1920f, "f916")
        assertInFrame(1920f, 1080f, "f169")
    }

    @Test
    fun twoInsertsAreIndependentGroups() {
        val a = LowerThirdTemplate.buildGroup(pw, ph, 0L, idBase = "one")
        val b = LowerThirdTemplate.buildGroup(pw, ph, 7000L, idBase = "two")
        assertTrue("fresh group objects", a.group !== b.group)
        val idsA = a.lanesTopFirst.map { it.id }.toSet()
        val idsB = b.lanesTopFirst.map { it.id }.toSet()
        assertTrue("no shared ids", idsA.intersect(idsB).isEmpty())
        // Moving one group moves only its own strap.
        val moved = a.group.copy(transformX = 300f)
        val rigA = ParentRig(listOf(moved) + a.children) { c, t -> c.renderAt(t) }
        val rigB = ParentRig(b.lanesTopFirst) { c, t -> c.renderAt(t) }
        assertEquals(300f + nameX, rigA.adjusted(a.nameLayer, 2000L).transformX, 0.01f)
        assertEquals(nameX, rigB.adjusted(b.nameLayer, 9000L).transformX, 0.01f)
        // Retyping one strap leaves the other's words untouched.
        assertEquals(LowerThirdSpec.NAME_TEXT, b.nameLayer.text)
        assertEquals(LowerThirdSpec.SUBTITLE_TEXT, b.subtitleLayer.text)
    }
}
