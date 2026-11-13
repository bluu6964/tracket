package com.example

import com.example.engine.ParentRig
import com.example.model.AnimProp
import com.example.model.FILL_IMAGE
import com.example.model.GroupOps
import com.example.model.IMG_FILL
import com.example.model.LayerType
import com.example.model.LogoRevealSpec
import com.example.model.LogoRevealTemplate
import com.example.model.at
import com.example.model.renderAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The "Logo Reveal" logo-animation template (Templates programme, logo template #1): ONE
 * group holding a logo slot (an image-filled shape the user replaces with their own logo,
 * exactly like a Carousel card) that pops in with a soft Back-out overshoot, a plain
 * brand-name text layer that rises and fades in under it just after, and a static hold to
 * the end (a reveal is an end card — no exit). The pins cover the insert structure, the
 * slot's replacability, the text layer's plainness (a retypable text clip — Bengali
 * sample intact), the exact keyframe design, the evaluated curves through the real
 * ParentRig, the in-frame guarantee on 16:9 and 9:16 canvases, and two-insert
 * independence. Carousel + Pop Title pins live in their own tests, untouched.
 */
class LogoRevealTemplateTest {

    private val pw = 1920f               // 16:9 canvas
    private val ph = 1080f
    private val at0 = 1500L              // the playhead the insert lands on
    private val uri = "content://test/logo.png"
    private val ins = LogoRevealTemplate.buildGroup(pw, ph, uri, at0, idBase = "test")
    private val box = pw * 130f / 360f   // the app shape box in stored units
    private val logoScale = LogoRevealSpec.LOGO_SIDE_FRAC * pw / box
    private val restPct = logoScale * 100f
    private val logoY = LogoRevealSpec.LOGO_Y_FRAC * pw
    private val textY = LogoRevealSpec.TEXT_Y_FRAC * pw
    private val textRise = LogoRevealSpec.TEXT_RISE_FRAC * pw

    private val projectTracks = ins.lanesTopFirst.mapIndexed { i, c ->
        com.example.model.TimelineTrack("lane-$i", c.name, clips = listOf(c))
    }
    private val rig = ParentRig(ins.lanesTopFirst) { c, t -> c.renderAt(t) }

    private fun logoAt(relMs: Long) = rig.adjusted(ins.logoLayer, at0 + relMs)
    private fun textAt(relMs: Long) = rig.adjusted(ins.textLayer, at0 + relMs)

    @Test
    fun insertIsOneGroupWithLogoAndTextInOrder() {
        // Exactly ONE top-level layer is added: the group, at the playhead, 3 s long, REST pose.
        val topLevel = GroupOps.scopeTracks(projectTracks, null).flatMap { it.clips }
        assertEquals(listOf(ins.group.id), topLevel.map { it.id })
        assertEquals(LayerType.GROUP, ins.group.type)
        assertEquals("Logo Reveal", ins.group.name)
        assertEquals(at0, ins.group.startMs)
        assertEquals(3000L, ins.group.durationMs)
        assertEquals(0f, ins.group.transformX, 0f)
        assertEquals(0f, ins.group.transformY, 0f)
        assertEquals(100f, ins.group.scalePercent, 0f)
        assertTrue("group carries no keyframes", ins.group.keyframes.isEmpty())
        // Children in lane order: the logo's lane above the name's; read back through GroupOps.
        assertEquals(listOf("test-logo", "test-text"), ins.children.map { it.id })
        assertEquals(
            ins.children.map { it.id },
            GroupOps.childrenOf(projectTracks, ins.group.id).map { it.id }
        )
        assertEquals("Logo", ins.logoLayer.name)
        assertEquals("Brand Name", ins.textLayer.name)
        // Every child: an ordinary clip of the group, spanning the whole template, linked
        // to the group with the REST reference pose — no rig anywhere (no Null, no
        // Controller: nothing here has that job).
        for (c in ins.children) {
            assertEquals(ins.group.id, c.groupId)
            assertEquals(at0, c.startMs)
            assertEquals(3000L, c.durationMs)
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
    fun logoSlotIsAnImageFilledShapeFedByTheGeneratedPlaceholder() {
        val l = ins.logoLayer
        assertEquals(LayerType.SHAPE, l.type)
        // The slot is the Carousel card's field set: an image fill over a rounded rect —
        // the Color panel's Fill → Image pick replaces it the same way.
        assertEquals(FILL_IMAGE, l.fillMode)
        assertEquals(uri, l.fillImageUri)
        assertEquals(IMG_FILL, l.fillImageMode)
        assertEquals("ROUNDED_RECT", l.shapePattern)
        assertEquals(LogoRevealSpec.LOGO_CORNER, l.shapeCornerRadius, 0f)
        // Stored geometry: a square badge, 28% of the frame wide, centred 10% of the
        // width above the canvas centre, in the app's shape convention.
        assertEquals(logoScale, l.shapeScaleX, 0.001f)
        assertEquals(logoScale, l.shapeScaleY, 0.001f)
        assertEquals(0.28f * pw, l.shapeScaleX * box, 0.5f)
        assertEquals(restPct, l.scalePercent, 0.001f)
        assertEquals(0f, l.transformX, 0f)
        assertEquals(logoY, l.transformY, 0.01f)
        // Nothing marks the layer as template-made: an ordinary shape clip.
        assertEquals(null, l.animIn)
        assertEquals(null, l.animOut)
        assertTrue(l.effects.isEmpty())
        assertTrue(l.masks.isEmpty())
        assertTrue(!l.isMask)
        // The slot accepts any picture: a copy pointing at another image is the same
        // layer with the same animation (this is what the Fill → Image replace writes).
        val swapped = l.copy(fillImageUri = "content://user/real-logo.png")
        for (rel in listOf(0L, 250L, 500L, 1200L)) {
            val a = l.at(at0 + rel)
            val b = swapped.at(at0 + rel)
            assertEquals("opacity after swap at $rel", a.opacity, b.opacity, 0.001f)
            assertEquals("scale after swap at $rel", a.scalePercent, b.scalePercent, 0.001f)
        }
    }

    @Test
    fun textLayerIsAPlainEditableTextClip() {
        val t = ins.textLayer
        assertEquals(LayerType.TEXT, t.type)
        // The Bengali sample, exactly (15 UTF-16 code units).
        assertEquals(LogoRevealSpec.SAMPLE_TEXT, t.text)
        assertEquals("আপনার ব্র্যান্ড", t.text)
        assertEquals(15, t.text!!.length)
        assertEquals(0xFFFFFFFFL, t.textColor)
        assertEquals(700, t.fontWeight)
        // No special flags: a plain text clip with ordinary keyframes.
        assertEquals(null, t.animIn)
        assertEquals(null, t.animOut)
        assertTrue(t.effects.isEmpty())
        assertTrue(t.masks.isEmpty())
        assertTrue(t.textAnimators.isEmpty())
        assertEquals(null, t.controllerId)
        assertTrue(!t.isMask)
        // Stored values are the rest pose; the keyframes carry the entrance.
        assertEquals(0f, t.transformX, 0f)
        assertEquals(textY, t.transformY, 0.01f)
        assertEquals(100f, t.scalePercent, 0f)
        assertEquals(100f, t.opacity, 0f)
        // Retyping keeps the animation: a copy with different words evaluates identically.
        val retyped = t.copy(text = "নতুন ব্র্যান্ড")
        for (rel in listOf(0L, 350L, 600L, 800L, 2000L)) {
            val a = t.at(at0 + rel)
            val b = retyped.at(at0 + rel)
            assertEquals("opacity after retype at $rel", a.opacity, b.opacity, 0.001f)
            assertEquals("y after retype at $rel", a.transformY, b.transformY, 0.001f)
        }
    }

    @Test
    fun keyframeDesignIsExactlyThePerformance() {
        val l = ins.logoLayer
        assertEquals(setOf(AnimProp.SCALE, AnimProp.OPACITY), l.keyframes.keys)
        val scale = l.keyframes.getValue(AnimProp.SCALE)
        assertEquals(listOf(0L, 500L), scale.map { it.timeMs })
        assertEquals(LogoRevealSpec.ENTER_SCALE / 100f * restPct, scale[0].a, 0.01f)
        assertEquals(restPct, scale[1].a, 0.01f)
        for (k in scale) assertEquals(0f, k.b, 0f)   // uniform: the badge grows evenly
        assertEquals(LogoRevealSpec.EASE_BACK_OUT, scale[0].easing)
        val op = l.keyframes.getValue(AnimProp.OPACITY)
        assertEquals(listOf(0L, 500L), op.map { it.timeMs })
        assertEquals(listOf(0f, 100f), op.map { it.a })
        assertEquals(LogoRevealSpec.EASE_CUBIC_OUT, op[0].easing)

        val t = ins.textLayer
        assertEquals(setOf(AnimProp.POSITION, AnimProp.OPACITY), t.keyframes.keys)
        val pos = t.keyframes.getValue(AnimProp.POSITION)
        assertEquals(listOf(350L, 800L), pos.map { it.timeMs })
        assertEquals(textY + textRise, pos[0].b, 0.01f)
        assertEquals(textY, pos[1].b, 0.01f)
        for (k in pos) assertEquals(0f, k.a, 0f)
        assertEquals(LogoRevealSpec.EASE_CUBIC_OUT, pos[0].easing)
        val tOp = t.keyframes.getValue(AnimProp.OPACITY)
        assertEquals(listOf(350L, 800L), tOp.map { it.timeMs })
        assertEquals(listOf(0f, 100f), tOp.map { it.a })
        assertEquals(LogoRevealSpec.EASE_CUBIC_OUT, tOp[0].easing)
    }

    @Test
    fun logoPopsInWithASoftOvershootAndFades() {
        // First instant: invisible at 60% of its rest size, already at its place.
        val start = logoAt(0L)
        assertEquals(0f, start.opacity, 0.01f)
        assertEquals(LogoRevealSpec.ENTER_SCALE / 100f * restPct, start.scalePercent, 0.01f)
        assertEquals(0f, start.transformX, 0f)
        assertEquals(logoY, start.transformY, 0.01f)
        // The fade is strictly rising through the pop.
        var prevOp = -1f
        for (rel in listOf(100L, 200L, 300L, 400L)) {
            val a = logoAt(rel)
            assertTrue("opacity rising at $rel", a.opacity > prevOp && a.opacity < 100f)
            prevOp = a.opacity
        }
        // The pop: relative to its rest size, the Back-out scale overshoots softly into
        // the same (100.4, 103) band the Pop Title pinned, then settles.
        var peak = 0f
        var rel = 25L
        while (rel <= 500L) {
            peak = maxOf(peak, logoAt(rel).scalePercent / restPct * 100f)
            rel += 25L
        }
        assertTrue("overshoot above 100: $peak", peak > 100.4f)
        assertTrue("overshoot stays soft: $peak", peak < 103f)
        // Uniform at every instant, never moving or turning: the badge only grows/fades.
        for (s in listOf(0L, 125L, 250L, 375L, 500L, 1500L)) {
            val a = logoAt(s)
            assertEquals("square at $s", a.shapeScaleX, a.shapeScaleY, 0.0001f)
            assertEquals("x fixed at $s", 0f, a.transformX, 0f)
            assertEquals("y fixed at $s", logoY, a.transformY, 0.01f)
            assertEquals("no turn at $s", 0f, a.rotationDeg, 0f)
        }
        // Settled at the end of the pop, exactly on the stored pose.
        val settled = logoAt(500L)
        assertEquals(restPct, settled.scalePercent, 0.01f)
        assertEquals(logoScale, settled.shapeScaleX, 0.001f)
        assertEquals(100f, settled.opacity, 0.01f)
    }

    @Test
    fun brandTextRisesInJustAfterTheLogoLands() {
        // The timing relationship: the name starts while the logo is still landing and
        // settles after it (350 < 500 < 800).
        assertTrue(LogoRevealSpec.TEXT_START_MS < LogoRevealSpec.LOGO_ENTER_MS)
        assertTrue(LogoRevealSpec.LOGO_ENTER_MS < LogoRevealSpec.TEXT_END_MS)
        // Before its start the name waits invisible at its risen-from place.
        for (rel in listOf(0L, 200L, 349L)) {
            val a = textAt(rel)
            assertEquals("waiting invisible at $rel", 0f, a.opacity, 0.01f)
            assertEquals("waiting low at $rel", textY + textRise, a.transformY, 0.01f)
        }
        // At its first instant the logo is well underway but the name has not appeared.
        assertTrue("logo underway at 350", logoAt(350L).opacity > 50f)
        assertEquals(0f, textAt(350L).opacity, 0.01f)
        // Mid-entrance: opacity strictly rising, place settling from below.
        var prevOp = -1f
        for (rel in listOf(450L, 550L, 650L, 750L)) {
            val a = textAt(rel)
            assertTrue("opacity rising at $rel", a.opacity > prevOp && a.opacity < 100f)
            prevOp = a.opacity
            assertTrue("y settling at $rel", a.transformY > textY && a.transformY < textY + textRise)
            assertEquals(0f, a.transformX, 0f)
            assertEquals(100f, a.scalePercent, 0.01f)   // the name does not pop, it rises
        }
        // Settled by 800, exactly at its rest place.
        val done = textAt(800L)
        assertEquals(100f, done.opacity, 0.01f)
        assertEquals(textY, done.transformY, 0.01f)
    }

    @Test
    fun tailIsAStaticHoldWithNoExit() {
        // From the end of the entrances to the last instant nothing moves at all: a logo
        // reveal holds (the user stretches the group for a longer hold). No track on
        // either layer has a key after 800 ms.
        for (c in ins.children) {
            for ((_, keys) in c.keyframes) {
                assertTrue("no late keys on ${c.id}", keys.all { it.timeMs <= 800L })
            }
        }
        val early = logoAt(1000L)
        val late = logoAt(3000L)
        assertEquals(early.opacity, late.opacity, 0f)
        assertEquals(early.scalePercent, late.scalePercent, 0f)
        assertEquals(early.shapeScaleX, late.shapeScaleX, 0f)
        val tEarly = textAt(1000L)
        val tLate = textAt(3000L)
        assertEquals(100f, tLate.opacity, 0.01f)
        assertEquals(tEarly.transformY, tLate.transformY, 0f)
        assertEquals(textY, tLate.transformY, 0.01f)
        // Both fully visible at the very end — the opposite of the Pop Title's clean exit.
        assertEquals(100f, late.opacity, 0.01f)
    }

    /** In-frame check for one canvas: the whole design stays inside it at every instant. */
    private fun assertInFrame(w: Float, h: Float, idBase: String) {
        val i2 = LogoRevealTemplate.buildGroup(w, h, uri, 0L, idBase = idBase)
        val rig2 = ParentRig(i2.lanesTopFirst) { c, t -> c.renderAt(t) }
        val box2 = w * 130f / 360f
        for (rel in listOf(0L, 150L, 300L, 500L, 650L, 800L, 1500L, 3000L)) {
            val l = rig2.adjusted(i2.logoLayer, rel)
            val half = l.shapeScaleX * box2 / 2f
            assertTrue("logo left in frame $idBase at $rel", l.transformX - half >= -w / 2f - 0.5f)
            assertTrue("logo right in frame $idBase at $rel", l.transformX + half <= w / 2f + 0.5f)
            assertTrue("logo top in frame $idBase at $rel", l.transformY - half >= -h / 2f - 0.5f)
            assertTrue("logo bottom in frame $idBase at $rel", l.transformY + half <= h / 2f + 0.5f)
            val t = rig2.adjusted(i2.textLayer, rel)
            // The name stays centred horizontally and inside its design rise band; its
            // words are laid out by the app in its default box (90% of the frame,
            // centred) — the same in-frame guarantee every user text layer has.
            assertEquals("text x centred $idBase at $rel", 0f, t.transformX, 0.01f)
            assertTrue(
                "text y in design range $idBase at $rel",
                t.transformY >= LogoRevealSpec.TEXT_Y_FRAC * w - 0.01f &&
                    t.transformY <= (LogoRevealSpec.TEXT_Y_FRAC + LogoRevealSpec.TEXT_RISE_FRAC) * w + 0.01f
            )
            assertTrue("text opacity sane $idBase at $rel", t.opacity in 0f..100f)
        }
        // The group itself is the identity on both canvases: authored against the real
        // canvas, nothing needs rescaling to fit.
        assertEquals(100f, i2.group.scalePercent, 0f)
    }

    @Test
    fun revealStaysInFrameOn169And916() {
        assertInFrame(1920f, 1080f, "f169")
        assertInFrame(1080f, 1920f, "f916")
    }

    @Test
    fun twoInsertsAreIndependentGroups() {
        val a = LogoRevealTemplate.buildGroup(pw, ph, uri, 0L, idBase = "one")
        val b = LogoRevealTemplate.buildGroup(pw, ph, "content://test/other.png", 7000L, idBase = "two")
        assertTrue("fresh group objects", a.group !== b.group)
        val idsA = a.lanesTopFirst.map { it.id }.toSet()
        val idsB = b.lanesTopFirst.map { it.id }.toSet()
        assertTrue("no shared ids", idsA.intersect(idsB).isEmpty())
        // Moving one group moves only its own reveal.
        val moved = a.group.copy(transformX = 300f)
        val rigA = ParentRig(listOf(moved) + a.children) { c, t -> c.renderAt(t) }
        val rigB = ParentRig(b.lanesTopFirst) { c, t -> c.renderAt(t) }
        assertEquals(300f, rigA.adjusted(a.logoLayer, 1000L).transformX, 0.01f)
        assertEquals(0f, rigB.adjusted(b.logoLayer, 8000L).transformX, 0.01f)
        // Each reveal keeps its own logo picture and its own words.
        assertEquals(uri, a.logoLayer.fillImageUri)
        assertEquals("content://test/other.png", b.logoLayer.fillImageUri)
        assertEquals(LogoRevealSpec.SAMPLE_TEXT, b.textLayer.text)
    }
}
