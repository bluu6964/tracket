package com.example

import com.example.engine.AlightXmlImporter
import com.example.engine.textLayoutBoxWidth
import com.example.model.AnimProp
import com.example.model.LayerType
import com.example.model.PATH_OP_CLOSE
import com.example.model.PATH_OP_LINE
import com.example.model.PATH_OP_MOVE
import com.example.model.valueAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Phone rounds 8 + 9 (build 135): the Graph counter's two-line wrap and Test-1 ("3")'s four
 * defects, each pinned at the level the harness can observe. See
 * PARITY-FINDINGS-GRAPH-R8.md and PARITY-FINDINGS-TEST1-R9.md.
 */
class PhoneRoundsImportTest {

    // ---- Round 8: the counter's layout box -----------------------------------------------------

    @Test
    fun graphCounterBoxKeepsAlightsAllowance() {
        // The Graph counter (text 1494 of 7e363d75), measured in rounds 7-8: authored wrapWidth
        // 239 in a 1920 frame; the painters lay it out at fontPx = 0.11 x 1920 = 211.2 px with
        // padH = 52.8 and a composed scale of 0.9027 (GraphParityTest pins the import numbers).
        // Alight's allowance in ems: the em on screen is 48 (size) x 1.267782 (layer scale)
        // x 2005/640 (the build-110 text unit over the embed's 2005-wide scene) = 190.63 px, so
        // the wrap allows 239 / 190.63 = 1.2537 em — a two-digit value (~1.11-1.24 em in the
        // device fonts) fits on one line. The layout box must BE that allowance.
        val box = textLayoutBoxWidth(1920f, 239f / 1920f, 0.9027f, 52.8f)
        assertEquals(239f / 0.9027f, box, 1f)
        assertEquals(1.2537f, box / 211.2f, 0.005f)
        // Before build 135 the padding was subtracted from the authored width too: the box was
        // 0.5 em narrower (0.7537 em), and "12" wrapped onto two lines on device.
        assertTrue(box > 250f)
        // Composed through the outer group instead (scale 0.7131, the ParentRig root pose), the
        // allowance in ems is the same — the box grows by exactly the scale ratio.
        val grouped = textLayoutBoxWidth(1920f, 239f / 1920f, 0.7131f, 52.8f)
        assertEquals(239f / 0.7131f, grouped, 1.5f)
        assertEquals(1.2537f, grouped / (211.2f * 0.9027f / 0.7131f), 0.005f)
        // App-made text (no authored width) keeps its historical box, padding inside.
        assertEquals(1920f * 0.9f - 2f * 52.8f, textLayoutBoxWidth(1920f, 0f, 0.9027f, 52.8f), 0.5f)
    }

    // ---- Round 9, P1: Exclude Mask is a mask, not the Exclusion blend --------------------------

    @Test
    fun excludeMaskLayerIsHiddenAndUnlinked() {
        // Test-1's Shape embed holds one layer: "Rounded Rectangle 1 Copy", a green roundrect
        // with blending="exclude" — Alight's Exclude Mask. It is never drawn in Alight; until
        // build 135 the importer mapped "exclude" to the Exclusion BLEND mode and painted it as
        // a giant solid green pill (round 9, P1). It stays unlinked: the renderers' mask links
        // intersect, an exclude mask subtracts — linking it would cut its target TO the pill.
        val xml = """<?xml version='1.0' encoding='UTF-8' ?><scene title="t" width="1920" height="1080" bgcolor="#ff000000" totalTime="2000" fps="30">
<shape id="1" label="Plate" startTime="0" endTime="2000" fillType="color" s=".rect"><transform><location value="960,540,0" /></transform><fillColor value="#ff333333" /><property name="size" type="vec2" value="400,200" /></shape>
<shape id="2" label="Cut" startTime="0" endTime="2000" fillType="color" blending="mask" s=".circle"><transform><location value="960,540,0" /></transform><fillColor value="#ffffffff" /><property name="size" type="vec2" value="100,100" /></shape>
<shape id="3" label="Rounded Rectangle 1 Copy" startTime="0" endTime="2000" fillType="color" blending="exclude" s=".roundrect"><transform><location value="960,540,0" /></transform><fillColor value="#ff1ed91e" /><property name="size" type="vec2" value="261,68" /></shape>
</scene>"""
        val r = AlightXmlImporter.parse(xml, 1920f)
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val plate = clips.first { it.name == "Plate" }
        val cut = clips.first { it.name == "Cut" }
        val excl = clips.first { it.name == "Rounded Rectangle 1 Copy" }
        // The plain mask still links and hides exactly as before.
        assertTrue(cut.isMask)
        assertFalse(cut.isVisible)
        assertEquals(plate.id, cut.maskTargetId)
        // The exclude mask: hidden, a mask, no blend, NO target, and a precise warning.
        assertTrue(excl.isMask)
        assertFalse(excl.isVisible)
        assertEquals("Normal", excl.blendMode)
        assertEquals(null, excl.maskTargetId)
        assertTrue(r.warnings.any { it.contains("Exclude mask layer") && it.contains("Rounded Rectangle 1 Copy") })
    }

    @Test
    fun excludeMaskGroupIsHidden() {
        // A whole embedScene can be the exclude mask (6 corpus instances): the group clip itself
        // is the mask layer and must not be drawn either.
        val xml = """<?xml version='1.0' encoding='UTF-8' ?><scene title="t" width="1920" height="1080" bgcolor="#ff000000" totalTime="2000" fps="30">
<shape id="1" label="Plate" startTime="0" endTime="2000" fillType="color" s=".rect"><transform><location value="960,540,0" /></transform><fillColor value="#ff333333" /><property name="size" type="vec2" value="400,200" /></shape>
<embedScene id="9" label="Group 1" startTime="0" endTime="2000" blending="exclude" fillType="intrinsic"><transform><location value="960,540,0" /></transform>
<scene title="" width="1920" height="1080" totalTime="2000" fps="30">
<shape id="2" label="In" startTime="0" endTime="2000" fillType="color" s=".circle"><transform><location value="960,540,0" /></transform><fillColor value="#ffffffff" /><property name="size" type="vec2" value="100,100" /></shape>
</scene>
</embedScene>
</scene>"""
        val r = AlightXmlImporter.parse(xml, 1920f)
        val group = r.snapshot.tracks.flatMap { it.clips }.first { it.type == LayerType.GROUP }
        assertTrue(group.isMask)
        assertFalse(group.isVisible)
        assertEquals(null, group.maskTargetId)
        assertTrue(r.warnings.any { it.contains("Exclude mask layer") })
    }

    // ---- Round 9, P2/P3: the arrow's pivot + head ----------------------------------------------

    @Test
    fun arrowWithPivotLandsOnItsAnchor() {
        // Test-1's Arrow 1, verbatim (Group 1's scene is the root scene here): location
        // (691.896, 328.907), pivot (-161.5, 221.5), scale 0.419444, start (-164, 78), end
        // (-164, 360), lineWidth 27, headWidth 106, headLength 130. Alight composes the anchor
        // as location + pivot, so the glyph lands on the blue disc at (529, 549) — in the old
        // import (pivot dropped) it sat at (691, 328), a stray dash above the pill (P2/P3).
        val xml = """<?xml version='1.0' encoding='UTF-8' ?><scene title="3" width="1920" height="1080" bgcolor="#ff000000" totalTime="5833" fps="30">
<shape id="634458" label="Arrow 1" startTime="0" endTime="1132" fillType="color" mediaFillMode="stretch" s=".arrow"><transform><location value="691.895996,328.907013,0.000000" /><pivot value="-161.500000,221.500000" /><scale value="0.419444,0.419444" /></transform><fillColor value="#ffffffff" /><property name="start" type="vec2" value="-164.000000,78.000000" /><property name="end" type="vec2" value="-164.000000,360.000000" /><property name="lineWidth" type="float" value="27.000000" /><property name="headWidth" type="float" value="106.000000" /><property name="headLength" type="float" value="130.000000" /></shape>
</scene>"""
        val r = AlightXmlImporter.parse(xml, 1920f)
        val arrow = r.snapshot.tracks.flatMap { it.clips }.single()
        assertEquals("PATH", arrow.shapePattern)
        assertEquals(529.35f - 960f, arrow.transformX, 0.6f)
        assertEquals(549.35f - 540f, arrow.transformY, 0.6f)
        // The stored outline: MOVE + 6 LINEs + CLOSE, normalised on the outline's box (centre
        // (529.35, 549.35), half 59.15). The tip is the 4th point: straight below the centre.
        val ops = arrow.pathSegments.map { it.op }
        assertEquals(listOf(PATH_OP_MOVE, PATH_OP_LINE, PATH_OP_LINE, PATH_OP_LINE, PATH_OP_LINE, PATH_OP_LINE, PATH_OP_LINE, PATH_OP_CLOSE), ops)
        val tip = arrow.pathSegments[3].pts
        assertEquals(0f, tip[0], 0.02f)
        assertEquals(1f, tip[1], 0.02f)
        // The tail's left corner: the outline's top-left extreme in y, inset by the shaft.
        val tail = arrow.pathSegments[0].pts
        assertTrue(tail[1] < -0.99f)
        assertTrue(abs(tail[0]) < 0.2f)
        // Drawn whole: no "head is not drawn" warning any more.
        assertTrue(r.warnings.none { it.contains("plain line") })
    }

    @Test
    fun lineWithPivotAndScaleComposesLikeAGroup() {
        // The same composition on a plain .line: scale 2 about pivot (10, 20) at location
        // (960, 540), segment (-50, 0) -> (50, 0). Group composition gives the centre
        // loc + pivot + 2 * (mid - pivot) = (950, 520); dropping the pivot gave (940, 500).
        val xml = """<?xml version='1.0' encoding='UTF-8' ?><scene title="t" width="1920" height="1080" bgcolor="#ff000000" totalTime="2000" fps="30">
<shape id="1" label="Rail" startTime="0" endTime="2000" fillType="color" s=".line"><transform><location value="960,540,0" /><pivot value="10,20" /><scale value="2,2" /></transform><fillColor value="#ffffffff" /><path-stroke direction="centered"><color value="#ffffffff" /><size value="4" /></path-stroke><property name="p1" type="vec2" value="-50,0" /><property name="p2" type="vec2" value="50,0" /></shape>
</scene>"""
        val r = AlightXmlImporter.parse(xml, 1920f)
        val line = r.snapshot.tracks.flatMap { it.clips }.single()
        assertEquals("RECT", line.shapePattern)
        assertEquals(950f - 960f, line.transformX, 0.5f)
        assertEquals(520f - 540f, line.transformY, 0.5f)
    }

    // ---- Round 9, P4: the traveller's phase ------------------------------------------------------

    @Test
    fun travellerAtTest1sStopMomentIsAtNineOClock() {
        // Test-1's Group 2 ride, verbatim (same fixture as the corpus ring-ride test): Circle 1
        // follows Circle 2's ring with movealongpath3, progress keyframed 1.0 -> 0.0. At the
        // follower's local 1134 ms (scene ~2.6 s, where round 9 compared the apps) the eased
        // progress is 0.758; fraction 0 is the TOP of the ring and the walk runs clockwise, so
        // the dot sits at 9 o'clock: (129 * sin(2 pi * 0.758), -129 * cos(2 pi * 0.758)) =
        // (-128.8, -6.3). The old +x-start walk put it at the top (12 o'clock) there instead.
        val xml = """<?xml version='1.0' encoding='UTF-8' ?><scene title="3" width="1920" height="1080" bgcolor="#ff000000" totalTime="5833" fps="30">
<embedScene id="634615" label="Group 2" startTime="2166" endTime="5833" fillType="intrinsic" hidden="false" outTime="3666">
<scene width="1920" height="1080" totalTime="5833" fps="30" bgcolor="#00000000">
<shape id="634612" label="Circle 2" startTime="1666" endTime="3199" fillType="none" mediaFillMode="stretch" s=".circle"><transform><location value="960.000000,540.000000,0.000000" /><scale value="1.340000,1.340000" /><opacity value="0.000000" /></transform><fillColor value="#fffae965" /><effect id="com.alightcreative.effects.drawing.progress" locallyApplied="false"><property name="start" type="float"><kf t="0.010437" v="100.000000" /><kf t="0.988911" v="0.000000" e="cubicBezier 1.0 0.0 0.64 1.0" /></property><property name="end" type="float" value="100.000000" /></effect><path-stroke direction="centered" end-size="1.500000"><color value="#ffffffff" /><size value="29.500000" /></path-stroke><property name="size" type="vec2" value="100.000000,100.000000" /></shape>
<shape id="634613" label="Circle 1" startTime="1466" endTime="3199" fillType="color" mediaFillMode="stretch" s=".circle"><transform><location value="819.000000,564.500000,0.000000" /><scale><kf t="0.990190" v="0.365000,0.365000" /><kf t="0.009810" v="0.000000,0.000000" /><kf t="0.124639" v="0.245000,0.245000" e="cubicBezier 0.4 0.0 0.26333332 1.0" /></scale></transform><fillColor value="#fffef6fa" /><effect id="com.alightcreative.effect.movealongpath3" locallyApplied="false"><property name="progress" type="float"><kf t="0.124639" v="1.000000" /><kf t="0.990190" v="0.000000" e="cubicBezier 1.0 0.0 0.64 1.0" /></property><property name="angle" type="float" value="0.000000" /><property name="tangent" type="bool" value="true" /><property name="inset" type="float" value="5.000000" /></effect><property name="size" type="vec2" value="100.000000,100.000000" /></shape>
</scene>
</embedScene>
</scene>"""
        val r = AlightXmlImporter.parse(xml, 1920f)
        val dot = r.snapshot.tracks.flatMap { it.clips }.first { it.name == "Circle 1" }
        val pos = dot.keyframes[AnimProp.POSITION]!!
        // Before the first progress key the value holds 1.0: the dot waits at the top.
        val start = pos.valueAt(100L)
        assertEquals(0f, start.first, 2f)
        assertEquals(-129f, start.second, 2f)
        // The round-9 comparison moment.
        val at = pos.valueAt(1134L)
        assertEquals(-128.8f, at.first, 7f)
        assertEquals(-6.3f, at.second, 7f)
    }
}
