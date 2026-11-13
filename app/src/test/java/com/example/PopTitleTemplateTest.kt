package com.example

import com.example.engine.ParentRig
import com.example.model.AnimProp
import com.example.model.FILL_SOLID
import com.example.model.GroupOps
import com.example.model.LayerType
import com.example.model.PopTitleSpec
import com.example.model.PopTitleTemplate
import com.example.model.TimelineClip
import com.example.model.at
import com.example.model.renderAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The "Pop Title" text-animation template (Templates programme, text template #1): ONE
 * group holding ONE ordinary text layer that performs the whole title (rise + fade + pop
 * in, hold, fade + rise out) and one accent bar under it. The pins cover the insert
 * structure, the exact keyframe design, the evaluated curve through the real ParentRig
 * (the composition the preview and the export use), the text layer's plainness (it stays
 * a retypable text clip — Bengali sample intact), and the in-frame guarantee on 16:9 and
 * 9:16 canvases.
 */
class PopTitleTemplateTest {

    private val pw = 1920f               // 16:9 canvas
    private val ph = 1080f
    private val at0 = 1500L              // the playhead the insert lands on
    private val ins = PopTitleTemplate.buildGroup(pw, ph, at0, idBase = "test")
    private val box = pw * 130f / 360f   // the app shape box in stored units
    private val rise = PopTitleSpec.RISE_FRAC * pw
    private val exitRise = PopTitleSpec.EXIT_RISE_FRAC * pw

    private val projectTracks = ins.lanesTopFirst.mapIndexed { i, c ->
        com.example.model.TimelineTrack("lane-$i", c.name, clips = listOf(c))
    }
    private val rig = ParentRig(ins.lanesTopFirst) { c, t -> c.renderAt(t) }

    private fun textAt(relMs: Long) = rig.adjusted(ins.textLayer, at0 + relMs)
    private fun barAt(relMs: Long) = rig.adjusted(ins.bar, at0 + relMs)

    @Test
    fun insertIsOneGroupWithTextAndBarInOrder() {
        // Exactly ONE top-level layer is added: the group, at the playhead, 3 s long, REST pose.
        val topLevel = GroupOps.scopeTracks(projectTracks, null).flatMap { it.clips }
        assertEquals(listOf(ins.group.id), topLevel.map { it.id })
        assertEquals(LayerType.GROUP, ins.group.type)
        assertEquals("Pop Title", ins.group.name)
        assertEquals(at0, ins.group.startMs)
        assertEquals(3000L, ins.group.durationMs)
        assertEquals(0f, ins.group.transformX, 0f)
        assertEquals(0f, ins.group.transformY, 0f)
        assertEquals(100f, ins.group.scalePercent, 0f)
        assertTrue("group carries no keyframes", ins.group.keyframes.isEmpty())
        // Children in lane order: the text's lane above the bar's; read back through GroupOps.
        assertEquals(listOf("test-text", "test-bar"), ins.children.map { it.id })
        assertEquals(
            ins.children.map { it.id },
            GroupOps.childrenOf(projectTracks, ins.group.id).map { it.id }
        )
        assertEquals("Title Text", ins.textLayer.name)
        assertEquals("Accent Bar", ins.bar.name)
        // Every child: an ordinary clip of the group, spanning the whole template, linked
        // to the group with the REST reference pose.
        for (c in ins.children) {
            assertEquals(ins.group.id, c.groupId)
            assertEquals(at0, c.startMs)
            assertEquals(3000L, c.durationMs)
            val link = c.parent ?: error("child ${c.id} has no parent link")
            assertEquals(ins.group.id, link.id)
            assertEquals(0f, link.refX, 0f)
            assertEquals(0f, link.refY, 0f)
            assertEquals(100f, link.refScale, 0f)
            assertEquals(0f, link.refRot, 0f)
        }
    }

    @Test
    fun textLayerIsAPlainEditableTextClip() {
        val t = ins.textLayer
        assertEquals(LayerType.TEXT, t.type)
        // The Bengali sample, exactly (10 UTF-16 code units: আপনার + space + লেখা).
        assertEquals(PopTitleSpec.SAMPLE_TEXT, t.text)
        assertEquals("আপনার লেখা", t.text)
        assertEquals(10, t.text!!.length)
        assertEquals(0xFFFFFFFFL, t.textColor)
        assertEquals(700, t.fontWeight)
        // No special flags: nothing marks this layer as template-made — it is a plain text
        // clip with ordinary keyframes, editable by every ordinary tool.
        assertEquals(null, t.animIn)
        assertEquals(null, t.animOut)
        assertTrue(t.effects.isEmpty())
        assertTrue(t.masks.isEmpty())
        assertTrue(t.textAnimators.isEmpty())
        assertEquals(null, t.controllerId)
        assertTrue(!t.isMask)
        // Stored values are the REST pose; the keyframes carry the performance.
        assertEquals(0f, t.transformX, 0f)
        assertEquals(0f, t.transformY, 0f)
        assertEquals(100f, t.scalePercent, 0f)
        assertEquals(100f, t.opacity, 0f)
        // Retyping keeps the animation: a copy with different words evaluates identically.
        val retyped = t.copy(text = "নতুন শিরোনাম")
        for (rel in listOf(0L, 300L, 600L, 2800L)) {
            val a = t.at(at0 + rel)
            val b = retyped.at(at0 + rel)
            assertEquals("opacity after retype at $rel", a.opacity, b.opacity, 0.001f)
            assertEquals("scale after retype at $rel", a.scalePercent, b.scalePercent, 0.001f)
            assertEquals("y after retype at $rel", a.transformY, b.transformY, 0.001f)
        }
    }

    @Test
    fun keyframeDesignIsExactlyThePerformance() {
        val t = ins.textLayer
        assertEquals(
            setOf(AnimProp.POSITION, AnimProp.SCALE, AnimProp.OPACITY),
            t.keyframes.keys
        )
        val pos = t.keyframes.getValue(AnimProp.POSITION)
        assertEquals(listOf(0L, 600L, 2600L, 3000L), pos.map { it.timeMs })
        assertEquals(rise, pos[0].b, 0.01f)
        assertEquals(0f, pos[1].b, 0f)
        assertEquals(0f, pos[2].b, 0f)
        assertEquals(-exitRise, pos[3].b, 0.01f)
        for (k in pos) assertEquals(0f, k.a, 0f)
        assertEquals(PopTitleSpec.EASE_CUBIC_OUT, pos[0].easing)
        assertEquals(PopTitleSpec.EASE_CUBIC_IN, pos[2].easing)
        val scale = t.keyframes.getValue(AnimProp.SCALE)
        assertEquals(listOf(0L, 600L), scale.map { it.timeMs })
        assertEquals(80f, scale[0].a, 0f)
        assertEquals(100f, scale[1].a, 0f)
        assertEquals(PopTitleSpec.EASE_BACK_OUT, scale[0].easing)
        val op = t.keyframes.getValue(AnimProp.OPACITY)
        assertEquals(listOf(0L, 600L, 2600L, 3000L), op.map { it.timeMs })
        assertEquals(listOf(0f, 100f, 100f, 0f), op.map { it.a })
        assertEquals(PopTitleSpec.EASE_CUBIC_OUT, op[0].easing)
        assertEquals(PopTitleSpec.EASE_CUBIC_IN, op[2].easing)

        val b = ins.bar
        assertEquals(setOf(AnimProp.SCALE, AnimProp.OPACITY), b.keyframes.keys)
        val bScale = b.keyframes.getValue(AnimProp.SCALE)
        assertEquals(listOf(0L, 600L), bScale.map { it.timeMs })
        assertEquals(0f, bScale[0].a, 0f)                       // width starts at nothing
        assertEquals(bScale[0].b, bScale[1].b, 0f)              // height share never changes
        assertEquals(b.shapeScaleX * 100f, bScale[1].a, 0.01f)  // and ends at the stored size
        val bOp = b.keyframes.getValue(AnimProp.OPACITY)
        assertEquals(listOf(0L, 2600L, 3000L), bOp.map { it.timeMs })
        assertEquals(listOf(100f, 100f, 0f), bOp.map { it.a })
    }

    @Test
    fun entranceRisesFadesAndPopsWithASoftOvershoot() {
        // First instant: invisible, at 80%, risen-from position (below centre).
        val start = textAt(0L)
        assertEquals(0f, start.opacity, 0.01f)
        assertEquals(80f, start.scalePercent, 0.01f)
        assertEquals(rise, start.transformY, 0.01f)
        assertEquals(0f, start.transformX, 0f)
        // Mid-entrance: everything is on its way, opacity strictly rising.
        var prevOp = -1f
        for (rel in listOf(100L, 200L, 300L, 400L, 500L)) {
            val a = textAt(rel)
            assertTrue("opacity rising at $rel", a.opacity > prevOp && a.opacity < 100f)
            prevOp = a.opacity
            assertTrue("y settling at $rel", a.transformY >= 0f && a.transformY < rise)
            assertEquals(0f, a.transformX, 0f)
        }
        // The pop: the Back-out scale overshoots 100% softly (peak ~101%), then settles.
        var peak = 0f
        var rel = 25L
        while (rel <= 600L) {
            peak = maxOf(peak, textAt(rel).scalePercent)
            rel += 25L
        }
        assertTrue("overshoot above 100: $peak", peak > 100.4f)
        assertTrue("overshoot stays soft: $peak", peak < 103f)
        // Settled at the end of the entrance, and held through the middle.
        for (holdRel in listOf(600L, 1500L, 2600L)) {
            val a = textAt(holdRel)
            assertEquals("opacity held at $holdRel", 100f, a.opacity, 0.01f)
            assertEquals("scale held at $holdRel", 100f, a.scalePercent, 0.01f)
            assertEquals("y held at $holdRel", 0f, a.transformY, 0.01f)
        }
    }

    @Test
    fun exitFadesAndRisesAndEndsClean() {
        val mid = textAt(2800L)
        assertTrue("exit opacity falling: ${mid.opacity}", mid.opacity > 40f && mid.opacity < 99f)
        assertTrue("exit rising: ${mid.transformY}", mid.transformY < 0f && mid.transformY > -exitRise)
        assertEquals(100f, mid.scalePercent, 0.01f)   // the exit does not shrink the text
        val almost = textAt(2999L)
        assertTrue("nearly gone at 2999: ${almost.opacity}", almost.opacity < 5f)
        // The last instant: exactly invisible, at the exit rise — a clean end, no pop-out.
        val end = textAt(3000L)
        assertEquals(0f, end.opacity, 0f)
        assertEquals(-exitRise, end.transformY, 0.01f)
    }

    @Test
    fun accentBarDesignAndMotion() {
        val b = ins.bar
        assertEquals(LayerType.SHAPE, b.type)
        assertEquals("ROUNDED_RECT", b.shapePattern)
        assertEquals(FILL_SOLID, b.fillMode)
        assertEquals(PopTitleSpec.ACCENT, b.textColor)
        assertEquals(65f, b.shapeCornerRadius, 0f)   // stadium ends
        // Stored geometry: 30% of the frame wide, 1.1% tall, centred 10.5% below centre.
        assertEquals(0.30f * pw, b.shapeScaleX * box, 0.5f)
        assertEquals(0.011f * pw, b.shapeScaleY * box, 0.2f)
        assertEquals(0f, b.transformX, 0f)
        assertEquals(0.105f * pw, b.transformY, 0.01f)
        // Motion: no width at the first instant, full width when the entrance settles,
        // visible through the hold, gone at the end (fading with the text).
        assertEquals(0f, barAt(0L).shapeScaleX, 0f)
        assertEquals(0.30f * pw, barAt(600L).shapeScaleX * box, 0.5f)
        assertEquals(100f, barAt(1500L).opacity, 0.01f)
        assertEquals(0f, barAt(3000L).opacity, 0f)
        // The bar never moves or turns: its centre is the same at every instant.
        for (rel in listOf(0L, 300L, 600L, 2000L, 2999L)) {
            val a = barAt(rel)
            assertEquals(0f, a.transformX, 0f)
            assertEquals(0.105f * pw, a.transformY, 0.01f)
            assertEquals(0f, a.rotationDeg, 0f)
        }
    }

    /** In-frame check for one canvas: the whole design stays inside it at every instant. */
    private fun assertInFrame(w: Float, h: Float, idBase: String) {
        val i2 = PopTitleTemplate.buildGroup(w, h, 0L, idBase = idBase)
        val rig2 = ParentRig(i2.lanesTopFirst) { c, t -> c.renderAt(t) }
        val box2 = w * 130f / 360f
        for (rel in listOf(0L, 150L, 300L, 450L, 600L, 1500L, 2600L, 2800L, 3000L)) {
            val t = rig2.adjusted(i2.textLayer, rel)
            // The text stays centred horizontally, never rises/sinks past its design
            // offsets, and its on-screen font (11% of width x scale) stays a title size.
            assertEquals("text x centred $idBase at $rel", 0f, t.transformX, 0.01f)
            assertTrue("text y in design range $idBase at $rel", abs(t.transformY) <= PopTitleSpec.RISE_FRAC * w + 0.01f)
            assertTrue("text scale sane $idBase at $rel", t.scalePercent in 79.9f..103.1f)
            // The words themselves are laid out by the app in its default box (90% of the
            // frame, centred) — the same in-frame guarantee every user text layer has.
            assertEquals(0f, i2.textLayer.textWrapFrac, 0f)
            val b = rig2.adjusted(i2.bar, rel)
            val halfW = b.shapeScaleX * box2 / 2f
            val halfH = b.shapeScaleY * box2 / 2f
            assertTrue("bar left in frame $idBase at $rel", b.transformX - halfW >= -w / 2f - 0.5f)
            assertTrue("bar right in frame $idBase at $rel", b.transformX + halfW <= w / 2f + 0.5f)
            assertTrue("bar top in frame $idBase at $rel", b.transformY - halfH >= -h / 2f - 0.5f)
            assertTrue("bar bottom in frame $idBase at $rel", b.transformY + halfH <= h / 2f + 0.5f)
        }
        // The group itself is the identity on both canvases: the insert was authored
        // against the real canvas, so nothing needs rescaling to fit.
        assertEquals(100f, i2.group.scalePercent, 0f)
    }

    @Test
    fun titleStaysInFrameOn169And916() {
        assertInFrame(1920f, 1080f, "f169")
        assertInFrame(1080f, 1920f, "f916")
    }

    @Test
    fun twoInsertsAreIndependentGroups() {
        val a = PopTitleTemplate.buildGroup(pw, ph, 0L, idBase = "one")
        val b = PopTitleTemplate.buildGroup(pw, ph, 7000L, idBase = "two")
        assertTrue("fresh group objects", a.group !== b.group)
        val idsA = a.lanesTopFirst.map { it.id }.toSet()
        val idsB = b.lanesTopFirst.map { it.id }.toSet()
        assertTrue("no shared ids", idsA.intersect(idsB).isEmpty())
        // Moving one group moves only its own title.
        val moved = a.group.copy(transformX = 300f)
        val rigA = ParentRig(listOf(moved) + a.children) { c, t -> c.renderAt(t) }
        val rigB = ParentRig(b.lanesTopFirst) { c, t -> c.renderAt(t) }
        assertEquals(300f, rigA.adjusted(a.textLayer, 1000L).transformX, 0.01f)
        assertEquals(0f, rigB.adjusted(b.textLayer, 8000L).transformX, 0.01f)
        // Retyping one title leaves the other's words untouched.
        assertEquals(PopTitleSpec.SAMPLE_TEXT, b.textLayer.text)
    }
}
