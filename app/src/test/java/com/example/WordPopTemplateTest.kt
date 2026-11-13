package com.example

import com.example.engine.ParentRig
import com.example.model.AnimProp
import com.example.model.GroupOps
import com.example.model.LayerType
import com.example.model.TimelineTrack
import com.example.model.WordPopSpec
import com.example.model.WordPopTemplate
import com.example.model.renderAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The "Word Pop" text-animation template (Templates programme, text template #2): ONE
 * group holding THREE ordinary TEXT layers — one per word of the sample sentence — each
 * popping in in turn (Back-out scale + fade + a small rise), a shared hold, and one
 * shared fade-out. The pins cover the insert structure, the exact keyframe design per
 * word, the stagger law evaluated through the real ParentRig, each word layer's
 * plainness (a retypable text clip — Bengali samples intact), the measured layout law
 * (the word centres come from advances shaped with HarfBuzz over Noto Sans Bengali
 * Bold: আপনার 3.596 em, নতুন 2.000 em, লেখা 2.228 em, space 0.260 em — recomputed here
 * from those literals, not from the spec's own functions), and the in-frame guarantee
 * on 16:9 and 9:16 canvases.
 */
class WordPopTemplateTest {

    private val pw = 1920f               // 16:9 canvas
    private val ph = 1080f
    private val at0 = 1500L              // the playhead the insert lands on
    private val ins = WordPopTemplate.buildGroup(pw, ph, at0, idBase = "test")
    private val fontPx = 0.11f * pw      // the text engine's layout font on this canvas
    private val rise = WordPopSpec.RISE_FRAC * pw

    // The measured advances, as literals: the layout law's ground truth (see the
    // builder's header). The sentence total is exactly 3.596+0.260+2.000+0.260+2.228.
    private val advEm = listOf(3.596f, 2.000f, 2.228f)
    private val spaceEm = 0.260f
    private val totalEm = advEm.sum() + 2f * spaceEm   // 8.344 em

    /** Word [i]'s centre recomputed from the measured literals alone. */
    private fun expectedCentre(i: Int): Float {
        var before = i * spaceEm
        for (j in 0 until i) before += advEm[j]
        return (-totalEm / 2f + before + advEm[i] / 2f) * fontPx
    }

    private val projectTracks = ins.lanesTopFirst.mapIndexed { i, c ->
        TimelineTrack("lane-$i", c.name, clips = listOf(c))
    }
    private val rig = ParentRig(ins.lanesTopFirst) { c, t -> c.renderAt(t) }

    private fun wordAt(i: Int, relMs: Long) = rig.adjusted(ins.wordLayers[i], at0 + relMs)

    @Test
    fun insertIsOneGroupWithThreeWordLayersInOrder() {
        // Exactly ONE top-level layer is added: the group, at the playhead, 3 s, REST pose.
        val topLevel = GroupOps.scopeTracks(projectTracks, null).flatMap { it.clips }
        assertEquals(listOf(ins.group.id), topLevel.map { it.id })
        assertEquals(LayerType.GROUP, ins.group.type)
        assertEquals("Word Pop", ins.group.name)
        assertEquals(at0, ins.group.startMs)
        assertEquals(3000L, ins.group.durationMs)
        assertEquals(0f, ins.group.transformX, 0f)
        assertEquals(0f, ins.group.transformY, 0f)
        assertEquals(100f, ins.group.scalePercent, 0f)
        assertTrue("group carries no keyframes", ins.group.keyframes.isEmpty())
        // Children in lane order: the three word layers, read back through GroupOps —
        // and nothing else: no rig layers, no shapes, exactly three plain TEXT clips.
        assertEquals(listOf("test-word1", "test-word2", "test-word3"), ins.children.map { it.id })
        assertEquals(
            ins.children.map { it.id },
            GroupOps.childrenOf(projectTracks, ins.group.id).map { it.id }
        )
        assertEquals(ins.wordLayers, ins.children)
        for ((i, c) in ins.children.withIndex()) {
            assertEquals("Word ${i + 1}", c.name)
            assertEquals(LayerType.TEXT, c.type)
            assertEquals(ins.group.id, c.groupId)
            assertEquals(at0, c.startMs)
            assertEquals(3000L, c.durationMs)
            assertEquals(null, c.controllerId)
            val link = c.parent ?: error("word ${c.id} has no parent link")
            assertEquals(ins.group.id, link.id)
            assertEquals(0f, link.refX, 0f)
            assertEquals(0f, link.refY, 0f)
            assertEquals(100f, link.refScale, 0f)
            assertEquals(0f, link.refRot, 0f)
        }
    }

    @Test
    fun wordLayersArePlainEditableTextClips() {
        val expectedTexts = listOf("আপনার", "নতুন", "লেখা")
        val expectedLengths = listOf(5, 4, 4)   // UTF-16 code units, all BMP
        for ((i, w) in ins.wordLayers.withIndex()) {
            assertEquals(WordPopSpec.WORDS[i], w.text)
            assertEquals(expectedTexts[i], w.text)
            assertEquals(expectedLengths[i], w.text!!.length)
            assertEquals(0xFFFFFFFFL, w.textColor)
            assertEquals(700, w.fontWeight)
            assertEquals(1, w.textAlign)   // centred: the line centres on the layer position
            // No special flags: nothing marks these layers as template-made.
            assertEquals(null, w.animIn)
            assertEquals(null, w.animOut)
            assertTrue(w.effects.isEmpty())
            assertTrue(w.masks.isEmpty())
            assertTrue(w.textAnimators.isEmpty())
            assertTrue(!w.isMask)
            // Stored values are the REST pose (its authored centre, full size, visible);
            // the keyframes carry the performance.
            assertEquals(expectedCentre(i), w.transformX, 0.01f)
            assertEquals(0f, w.transformY, 0f)
            assertEquals(100f, w.scalePercent, 0f)
            assertEquals(100f, w.opacity, 0f)
            // Retyping keeps the animation: a copy with different words evaluates identically.
            val retyped = w.copy(text = "অন্য শব্দ")
            for (rel in listOf(0L, 200L, 400L, 710L, 2800L)) {
                val a = w.renderAt(at0 + rel)
                val b = retyped.renderAt(at0 + rel)
                assertEquals("opacity after retype word $i at $rel", a.opacity, b.opacity, 0.001f)
                assertEquals("scale after retype word $i at $rel", a.scalePercent, b.scalePercent, 0.001f)
                assertEquals("x after retype word $i at $rel", a.transformX, b.transformX, 0.001f)
                assertEquals("y after retype word $i at $rel", a.transformY, b.transformY, 0.001f)
            }
        }
    }

    @Test
    fun keyframeDesignIsExactlyThePerformance() {
        for ((i, w) in ins.wordLayers.withIndex()) {
            val start = i * 180L
            val landed = start + 350L
            assertEquals(
                setOf(AnimProp.POSITION, AnimProp.SCALE, AnimProp.OPACITY),
                w.keyframes.keys
            )
            val pos = w.keyframes.getValue(AnimProp.POSITION)
            assertEquals(listOf(start, landed), pos.map { it.timeMs })
            assertEquals(expectedCentre(i), pos[0].a, 0.01f)
            assertEquals(rise, pos[0].b, 0.01f)
            assertEquals(expectedCentre(i), pos[1].a, 0.01f)
            assertEquals(0f, pos[1].b, 0f)
            assertEquals(WordPopSpec.EASE_CUBIC_OUT, pos[0].easing)
            val scale = w.keyframes.getValue(AnimProp.SCALE)
            assertEquals(listOf(start, landed), scale.map { it.timeMs })
            assertEquals(60f, scale[0].a, 0f)
            assertEquals(100f, scale[1].a, 0f)
            assertEquals(WordPopSpec.EASE_BACK_OUT, scale[0].easing)
            val op = w.keyframes.getValue(AnimProp.OPACITY)
            assertEquals(listOf(start, landed, 2600L, 3000L), op.map { it.timeMs })
            assertEquals(listOf(0f, 100f, 100f, 0f), op.map { it.a })
            assertEquals(WordPopSpec.EASE_CUBIC_OUT, op[0].easing)
            assertEquals(WordPopSpec.EASE_CUBIC_IN, op[2].easing)
        }
    }

    @Test
    fun layoutLawCentresTheSentenceAsMeasured() {
        // The spec's own arithmetic agrees with the literals it was measured from.
        assertEquals(totalEm * fontPx, WordPopSpec.totalWidthPx(pw), 0.5f)
        for (i in 0..2) {
            assertEquals(expectedCentre(i), WordPopSpec.centreXPx(i, pw), 0.01f)
            assertEquals(advEm[i] * fontPx, WordPopSpec.advancePx(i, pw), 0.01f)
            assertEquals(expectedCentre(i), ins.wordLayers[i].transformX, 0.01f)
        }
        // Reading order, left to right.
        assertTrue(ins.wordLayers[0].transformX < ins.wordLayers[1].transformX)
        assertTrue(ins.wordLayers[1].transformX < ins.wordLayers[2].transformX)
        // The sentence spans exactly [-total/2, +total/2]: word 1's left edge and
        // word 3's right edge sit symmetric about the canvas centre.
        val leftEdge = ins.wordLayers[0].transformX - advEm[0] * fontPx / 2f
        val rightEdge = ins.wordLayers[2].transformX + advEm[2] * fontPx / 2f
        assertEquals(-totalEm * fontPx / 2f, leftEdge, 0.5f)
        assertEquals(totalEm * fontPx / 2f, rightEdge, 0.5f)
        assertEquals(abs(leftEdge), rightEdge, 0.5f)
        // The visible gap between neighbouring word edges is exactly one space advance.
        for (i in 0..1) {
            val gap = (ins.wordLayers[i + 1].transformX - advEm[i + 1] * fontPx / 2f) -
                (ins.wordLayers[i].transformX + advEm[i] * fontPx / 2f)
            assertEquals("gap after word $i", spaceEm * fontPx, gap, 0.5f)
        }
    }

    @Test
    fun staggerLawThroughParentRig() {
        // Before anything starts (and while word 1 is mid-pop at 90 ms): words 2 and 3
        // wait at their start pose — invisible, at 60%, risen — held by their first keys.
        for (rel in listOf(0L, 90L)) {
            for (i in listOf(1, 2)) {
                val w = wordAt(i, rel)
                assertEquals("word $i hidden at $rel", 0f, w.opacity, 0f)
                assertEquals("word $i at enter scale at $rel", 60f, w.scalePercent, 0f)
                assertEquals("word $i risen at $rel", rise, w.transformY, 0.01f)
                assertEquals("word $i x at $rel", expectedCentre(i), w.transformX, 0.01f)
            }
        }
        // Word 1 mid-pop at 90 ms: everything strictly on its way.
        val w1mid = wordAt(0, 90L)
        assertTrue("word 1 fading in: ${w1mid.opacity}", w1mid.opacity > 0f && w1mid.opacity < 100f)
        assertTrue("word 1 growing: ${w1mid.scalePercent}", w1mid.scalePercent > 60f && w1mid.scalePercent < 103f)
        assertTrue("word 1 rising: ${w1mid.transformY}", w1mid.transformY > 0f && w1mid.transformY < rise)
        // At 355 ms word 1 has landed exactly, word 2 is mid-pop, word 3 still waits.
        val w1done = wordAt(0, 355L)
        assertEquals(100f, w1done.opacity, 0.01f)
        assertEquals(100f, w1done.scalePercent, 0.01f)
        assertEquals(0f, w1done.transformY, 0.01f)
        val w2mid = wordAt(1, 355L)
        assertTrue("word 2 fading in: ${w2mid.opacity}", w2mid.opacity > 0f && w2mid.opacity < 100f)
        assertTrue("word 2 growing: ${w2mid.scalePercent}", w2mid.scalePercent > 60f && w2mid.scalePercent < 103f)
        assertEquals(0f, wordAt(2, 355L).opacity, 0f)
        // Word 3 starts at 360 ms: just after, it is on its way too.
        val w3early = wordAt(2, 370L)
        assertTrue("word 3 fading in: ${w3early.opacity}", w3early.opacity > 0f && w3early.opacity < 100f)
    }

    @Test
    fun eachWordsPopOvershootsSoftly() {
        // The Back-out pop: each word's scale overshoots 100% softly (peak ~101%),
        // then settles exactly at rest at its landed time.
        for (i in 0..2) {
            val start = i * 180L
            var peak = 0f
            var rel = start + 25L
            while (rel <= start + 350L) {
                peak = maxOf(peak, wordAt(i, rel).scalePercent)
                rel += 25L
            }
            assertTrue("word $i overshoot above 100: $peak", peak > 100.4f)
            assertTrue("word $i overshoot stays soft: $peak", peak < 103f)
            assertEquals(100f, wordAt(i, start + 350L).scalePercent, 0.01f)
        }
    }

    @Test
    fun allWordsLandedBy710AndHeldAtRest() {
        // The last word lands at 360 + 350 = 710 ms — well inside the ~1.2 s budget —
        // and from there to the exit every word sits exactly at its rest pose.
        for (rel in listOf(710L, 1000L, 1200L, 2000L, 2600L)) {
            for (i in 0..2) {
                val w = wordAt(i, rel)
                assertEquals("word $i opacity at $rel", 100f, w.opacity, 0.01f)
                assertEquals("word $i scale at $rel", 100f, w.scalePercent, 0.01f)
                assertEquals("word $i x at $rel", expectedCentre(i), w.transformX, 0.01f)
                assertEquals("word $i y at $rel", 0f, w.transformY, 0.01f)
                assertEquals("word $i rotation at $rel", 0f, w.rotationDeg, 0f)
            }
        }
    }

    @Test
    fun exitFadesAllWordsTogetherToExactlyZero() {
        // One shared fade: at every exit instant all three words carry the SAME opacity,
        // strictly falling, and the fade moves nothing else.
        var prev = 101f
        for (rel in listOf(2700L, 2800L, 2900L)) {
            val ops = (0..2).map { wordAt(it, rel).opacity }
            assertEquals("words fade together at $rel", ops[0], ops[1], 0.001f)
            assertEquals("words fade together at $rel", ops[0], ops[2], 0.001f)
            assertTrue("exit opacity falling at $rel: ${ops[0]}", ops[0] > 0f && ops[0] < prev)
            prev = ops[0]
            for (i in 0..2) {
                val w = wordAt(i, rel)
                assertEquals("word $i scale rests during exit at $rel", 100f, w.scalePercent, 0.01f)
                assertEquals("word $i x rests during exit at $rel", expectedCentre(i), w.transformX, 0.01f)
                assertEquals("word $i y rests during exit at $rel", 0f, w.transformY, 0.01f)
            }
        }
        // The last instant: every word exactly invisible — a clean end, no pop-out.
        for (i in 0..2) assertEquals(0f, wordAt(i, 3000L).opacity, 0f)
    }

    /** In-frame check for one canvas: every word stays inside it at every instant. */
    private fun assertInFrame(w: Float, h: Float, idBase: String) {
        val i2 = WordPopTemplate.buildGroup(w, h, 0L, idBase = idBase)
        val rig2 = ParentRig(i2.lanesTopFirst) { c, t -> c.renderAt(t) }
        val fpx = 0.11f * w
        var rel = 0L
        while (rel <= 3000L) {
            for (i in 0..2) {
                val a = rig2.adjusted(i2.wordLayers[i], rel)
                // Half the word's advance, grown by its current (possibly overshooting)
                // scale: the word's glyphs hug its centre, so this is its true extent.
                val halfW = advEm[i] * fpx / 2f * a.scalePercent / 100f
                assertTrue("word $i left in frame $idBase at $rel", a.transformX - halfW >= -w / 2f - 0.5f)
                assertTrue("word $i right in frame $idBase at $rel", a.transformX + halfW <= w / 2f + 0.5f)
                // Vertical: the centre never leaves the rise band and a full em of
                // half-height (more than the line box's half) always fits.
                assertTrue("word $i y in rise band $idBase at $rel", abs(a.transformY) <= WordPopSpec.RISE_FRAC * w + 0.01f)
                assertTrue("word $i vertical in frame $idBase at $rel", abs(a.transformY) + fpx <= h / 2f)
            }
            rel += 30L
        }
        // The group itself is the identity on both canvases.
        assertEquals(100f, i2.group.scalePercent, 0f)
        assertEquals(0f, i2.group.transformX, 0f)
        assertEquals(0f, i2.group.transformY, 0f)
    }

    @Test
    fun sentenceStaysInFrameOn169And916() {
        assertInFrame(1920f, 1080f, "f169")
        assertInFrame(1080f, 1920f, "f916")
    }

    @Test
    fun twoInsertsAreIndependentGroups() {
        val a = WordPopTemplate.buildGroup(pw, ph, 0L, idBase = "one")
        val b = WordPopTemplate.buildGroup(pw, ph, 7000L, idBase = "two")
        assertTrue("fresh group objects", a.group !== b.group)
        val idsA = a.lanesTopFirst.map { it.id }.toSet()
        val idsB = b.lanesTopFirst.map { it.id }.toSet()
        assertTrue("no shared ids", idsA.intersect(idsB).isEmpty())
        // Moving one group moves only its own sentence.
        val moved = a.group.copy(transformX = 300f)
        val rigA = ParentRig(listOf(moved) + a.children) { c, t -> c.renderAt(t) }
        val rigB = ParentRig(b.lanesTopFirst) { c, t -> c.renderAt(t) }
        for (i in 0..2) {
            assertEquals(300f + expectedCentre(i), rigA.adjusted(a.wordLayers[i], 1000L).transformX, 0.01f)
            assertEquals(expectedCentre(i), rigB.adjusted(b.wordLayers[i], 8000L).transformX, 0.01f)
        }
        // Retyping one sentence leaves the other's words untouched.
        assertEquals(WordPopSpec.WORDS, b.wordLayers.map { it.text })
    }
}
