package com.example

import com.example.engine.AlightXmlImporter
import com.example.engine.AmFxEval
import com.example.engine.RepeatEval
import com.example.model.ClipFxLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build 167: the four banked round-4 effect ports — pagecurl, tileshift2, fractalwarp3
 * and repeat.line. The instances below are verbatim corpus blocks (file named per test);
 * the pins are the fx_plan.h slot layouts and the AmFxEval tail resolutions, and every
 * test also pins that the import produces no "not supported yet" warning for its effect.
 *
 * repeat.line is the exception that proves the tail rule: its keyframed angle / scale /
 * phase take their FIRST value (the pass-2 rule) with a warning naming the lost sweep —
 * the draw loops run RepeatEval on the STORED effect, never through AmFxEval, so no tail
 * can carry them (verified: AmFxEval.resolve feeds only the shader path, fxOpsOf in
 * NativePreviewEngine / NativeProjectExporter, while the repeat expansions read the
 * clip's own effects). The window warning fires too: the corpus phases dip to -1.
 */
class Round4bImportTest {

    private fun scene(layers: String): String =
        """<?xml version='1.0' encoding='UTF-8' ?>
<scene title="R4B" width="1080" height="1920" bgcolor="#ff102030" totalTime="2000" fps="30">
$layers
</scene>"""

    private fun shapeWith(fx: String): String = scene(
        """<shape id="1" label="Box" startTime="0" endTime="2000" fillType="color" s=".rect">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <fillColor value="#ffffffff" />
  $fx
  <property name="size" type="vec2" value="100,100" /></shape>"""
    )

    private fun parse(fx: String) = AlightXmlImporter.parse(shapeWith(fx), 1080f)

    private fun fxOf(r: AlightXmlImporter.ImportResult, type: String) =
        r.snapshot.tracks.flatMap { it.clips }[0].effects.single { it.type == type }

    // ---- pagecurl ----

    @Test
    fun pagecurlVerbatimProgressTailResolves() {
        // khanbh-scene-2b.xml verbatim (first of its 2 instances): progress keyframed
        // 0.23 -> 0.31, everything else at the def defaults; the tail starts at 21.
        val r = parse(
            """<effect id="com.alightcreative.effects.pagecurl" locallyApplied="true"><property name="progress" type="float"><kf t="0.001023" v="0.230000" /><kf t="0.767061" v="0.310000" e="random 0.5 0.5 0.5 0.5 0.5 0.0" /></property><property name="radius" type="float" value="0.100000" /><property name="angle" type="float" value="38.000000" /><property name="alpha" type="float" value="1.000000" /><property name="strength" type="float" value="0.100000" /><property name="backColor" type="color" value="#ff888888" /><property name="backAlpha" type="float" value="1.000000" /><property name="backBlendMode" type="int" value="1" /><property name="shineColor" type="color" value="#ffffffff" /><property name="shineAlpha" type="float" value="1.000000" /><property name="shineSpec" type="float" value="30.000000" /><property name="shineBlendMode" type="int" value="2" /><property name="dropShadowColor" type="color" value="#ff000000" /><property name="dropShadowAlpha" type="float" value="0.800000" /><property name="softDropShadow" type="bool" value="true" /><property name="frontShadowColor" type="color" value="#ffffffff" /><property name="frontShadowAlpha" type="float" value="1.000000" /><property name="frontShadowBlendMode" type="int" value="2" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.single { it.type == ClipFxLibrary.PAGECURL_AM }
        assertEquals(0.23f, fx.values[0], 0.001f)   // progress at t = 0
        assertEquals(0.1f, fx.values[1], 0.001f)    // radius
        assertEquals(38f, fx.values[2], 0.001f)     // angle
        assertEquals(1f, fx.values[3], 0.001f)      // alpha
        assertEquals(0.1f, fx.values[4], 0.001f)    // strength
        assertEquals(0x888888.toFloat(), fx.values[5], 0f)   // backColor packed
        assertEquals(1f, fx.values[6], 0f)          // backAlpha
        assertEquals(1f, fx.values[7], 0f)          // backBlendMode Multiply (EXT)
        assertEquals(0xFFFFFF.toFloat(), fx.values[8], 0f)   // shineColor packed
        assertEquals(1f, fx.values[9], 0f)          // shineAlpha
        assertEquals(30f, fx.values[10], 0.001f)    // shineSpec
        assertEquals(2f, fx.values[11], 0f)         // shineBlendMode Screen
        assertEquals(0f, fx.values[12], 0f)         // dropShadowColor packed black
        assertEquals(0.8f, fx.values[13], 0.001f)   // dropShadowAlpha
        assertEquals(1f, fx.values[14], 0f)         // softDropShadow (EXT2)
        assertEquals(0xFFFFFF.toFloat(), fx.values[15], 0f)  // frontShadowColor packed
        assertEquals(1f, fx.values[16], 0f)         // frontShadowAlpha
        assertEquals(2f, fx.values[17], 0f)         // frontShadowBlendMode Screen
        assertEquals(2f, fx.values[21], 0f)         // progress tail: 2 keys, from index 21
        // The "random" easing parses as null -> LINEAR, so the tail resolves cleanly.
        val end = AmFxEval.resolve(listOf(fx), c, c.startMs + 1535L).single()   // rel 0.7675
        assertEquals(0.31f, end.values[0], 0.01f)
        val start = AmFxEval.resolve(listOf(fx), c, c.startMs).single()
        assertEquals(0.23f, start.values[0], 0.01f)
        val flat = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(24, flat.size)
        assertEquals(220f, flat[0], 0f)
        assertEquals(221f, flat[8], 0f)    // the EXT group rides right behind its owner
        assertEquals(222f, flat[16], 0f)   // the EXT2 group behind that
        assertTrue(r.warnings.none { it.contains("pagecurl") })
    }

    @Test
    fun pagecurlSecondInstanceFirstValue() {
        // khanbh-scene-2b.xml verbatim (second instance, keys serialised out of order):
        // progress 0.18 -> 0.24; the first value is the earliest key, not the first listed.
        val r = parse(
            """<effect id="com.alightcreative.effects.pagecurl" locallyApplied="true"><property name="progress" type="float"><kf t="0.767061" v="0.240000" e="random 0.5 0.5 0.5 0.5 0.5 0.0" /><kf t="0.001023" v="0.180000" /></property><property name="radius" type="float" value="0.100000" /><property name="angle" type="float" value="38.000000" /><property name="alpha" type="float" value="1.000000" /><property name="strength" type="float" value="0.100000" /><property name="backColor" type="color" value="#ff888888" /><property name="backAlpha" type="float" value="1.000000" /><property name="backBlendMode" type="int" value="1" /><property name="shineColor" type="color" value="#ffffffff" /><property name="shineAlpha" type="float" value="1.000000" /><property name="shineSpec" type="float" value="30.000000" /><property name="shineBlendMode" type="int" value="2" /><property name="dropShadowColor" type="color" value="#ff000000" /><property name="dropShadowAlpha" type="float" value="0.800000" /><property name="softDropShadow" type="bool" value="true" /><property name="frontShadowColor" type="color" value="#ffffffff" /><property name="frontShadowAlpha" type="float" value="1.000000" /><property name="frontShadowBlendMode" type="int" value="2" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.single { it.type == ClipFxLibrary.PAGECURL_AM }
        assertEquals(0.18f, fx.values[0], 0.001f)
        val end = AmFxEval.resolve(listOf(fx), c, c.startMs + 1535L).single()
        assertEquals(0.24f, end.values[0], 0.01f)
        assertTrue(r.warnings.none { it.contains("pagecurl") })
    }

    @Test
    fun pagecurlPanelCreateCarriesTheDefDefaults() {
        val fx = ClipFxLibrary.create(ClipFxLibrary.PAGECURL_AM)!!
        assertEquals(21, fx.values.size)
        assertEquals(listOf(0.4f, 0.1f, 38f, 1f, 0.1f, 0x888888.toFloat(), 1f), fx.values.take(7))
        assertEquals(listOf(1f, 0xFFFFFF.toFloat(), 1f, 30f, 2f, 0f, 0.8f), fx.values.subList(7, 14))
        assertEquals(listOf(1f, 0xFFFFFF.toFloat(), 1f, 2f, 0f, 0f, 0f), fx.values.subList(14, 21))
        val flat = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(220f, flat[0], 0f)
        assertEquals(221f, flat[8], 0f)
        assertEquals(222f, flat[16], 0f)
    }

    // ---- tileshift2 ----

    @Test
    fun tileshift2VerbatimTailsResolve() {
        // shake-anthonyraj-fire.xml verbatim: strength keyframed 0.131 -> 0, position
        // keyframed (8, 0) -> (31, 0) -> (-7, 0) (keys serialised out of order),
        // amount 10, stretch (1, 1), angle 45, seed 0, screenSpace false.
        val r = parse(
            """<effect id="com.alightcreative.effects.tileshift2" locallyApplied="false"><property name="strength" type="float"><kf t="0.000000" v="0.131000" /><kf t="0.451807" v="0.000000" /></property><property name="amount" type="float" value="10.000000" /><property name="stretch" type="vec2" value="1.000000,1.000000" /><property name="angle" type="float" value="45.000000" /><property name="seed" type="float" value="0.000000" /><property name="screenSpace" type="bool" value="false" /><property name="position" type="vec2"><kf t="0.013087" v="8.000000,0.000000" /><kf t="0.885296" v="-7.000000,0.000000" /><kf t="0.551205" v="31.000000,0.000000" /></property></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.single { it.type == ClipFxLibrary.TILESHIFT2_AM }
        assertEquals(0.131f, fx.values[0], 0.001f)  // strength at t = 0
        assertEquals(10f, fx.values[1], 0.001f)     // amount
        assertEquals(45f, fx.values[2], 0.001f)     // angle
        assertEquals(0f, fx.values[3], 0f)          // seed
        assertEquals(0f, fx.values[4], 0f)          // screenSpace off
        assertEquals(8f, fx.values[5], 0.001f)      // position x at t = 0 (first key)
        assertEquals(0f, fx.values[6], 0f)          // position y
        assertEquals(1f, fx.values[7], 0f)          // stretch x (EXT)
        assertEquals(1f, fx.values[8], 0f)          // stretch y
        assertEquals(2f, fx.values[14], 0f)         // strength tail: 2 keys, from index 14
        assertEquals(3f, fx.values[27], 0f)         // position x tail: 3 keys, after strength's 13
        assertEquals(3f, fx.values[46], 0f)         // position y tail: 3 keys, after x's 19
        val faded = AmFxEval.resolve(listOf(fx), c, c.startMs + 904L).single()   // rel 0.452
        assertEquals(0f, faded.values[0], 0.01f)
        val mid = AmFxEval.resolve(listOf(fx), c, c.startMs + 1102L).single()    // rel 0.551
        assertEquals(31f, mid.values[5], 0.05f)
        val end = AmFxEval.resolve(listOf(fx), c, c.startMs + 1999L).single()
        assertEquals(-7f, end.values[5], 0.05f)
        val flat = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(16, flat.size)
        assertEquals(223f, flat[0], 0f)
        assertEquals(224f, flat[8], 0f)
        assertTrue(r.warnings.none { it.contains("tileshift2") })
    }

    // ---- fractalwarp3 ----

    @Test
    fun fractalWarp3VerbatimTailsResolve() {
        // shake-lucifer-rc.xml verbatim: offs keyframed (25, -33.78) -> (1504, -746)
        // (keys serialised out of order), mag 0.05, scale 2.97, intensity 0.53,
        // octaves 6, parr (172, 40), screenSpace false.
        val r = parse(
            """<effect id="com.alightcreative.effects.fractalwarp3" locallyApplied="true"><property name="offs" type="vec2"><kf t="1.000000" v="1504.000000,-746.000000" /><kf t="0.002771" v="25.000000,-33.782219" /></property><property name="mag" type="float" value="0.050000" /><property name="scale" type="float" value="2.970000" /><property name="intensity" type="float" value="0.530000" /><property name="screenSpace" type="bool" value="false" /><property name="octaves" type="float" value="6.000000" /><property name="parr" type="vec2" value="172.000000,40.000000" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.single { it.type == ClipFxLibrary.FRACTAL_WARP3_AM }
        assertEquals(0.05f, fx.values[0], 0.001f)   // mag
        assertEquals(2.97f, fx.values[1], 0.001f)   // scale
        assertEquals(0.53f, fx.values[2], 0.001f)   // intensity
        assertEquals(6f, fx.values[3], 0f)          // octaves
        assertEquals(25f, fx.values[4], 0.001f)     // offs x at t = 0
        assertEquals(-33.782219f, fx.values[5], 0.001f)  // offs y at t = 0
        assertEquals(172f, fx.values[6], 0.001f)    // parr x
        assertEquals(40f, fx.values[7], 0.001f)     // parr y (EXT)
        assertEquals(0f, fx.values[14], 0f)         // mag: constant, placeholder tail
        assertEquals(2f, fx.values[15], 0f)         // offs x tail: 2 keys
        assertEquals(2f, fx.values[28], 0f)         // offs y tail: 2 keys
        assertEquals(0f, fx.values[41], 0f)         // parr x: constant, placeholder
        assertEquals(0f, fx.values[42], 0f)         // parr y: constant, placeholder
        assertEquals(0f, fx.values[43], 0f)         // scale: constant, placeholder
        val at0 = AmFxEval.resolve(listOf(fx), c, c.startMs).single()
        assertEquals(25f, at0.values[4], 0.01f)
        assertEquals(-33.78f, at0.values[5], 0.01f)
        val end = AmFxEval.resolve(listOf(fx), c, c.startMs + 2000L).single()   // rel 1.0
        assertEquals(1504f, end.values[4], 0.01f)
        assertEquals(-746f, end.values[5], 0.01f)
        val flat = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(16, flat.size)
        assertEquals(225f, flat[0], 0f)
        assertEquals(226f, flat[8], 0f)
        assertTrue(r.warnings.none { it.contains("fractalwarp3") })
    }

    // ---- repeat.line ----

    @Test
    fun repeatLineVerbatimFirstValuesAndWarnings() {
        // shake-rcf-10mar2.xml verbatim (both instances are identical): count single-key
        // 18, offset (2, 0), angle keyframed 6 keys -31 -> 154, scale keyframed 4 keys,
        // phase keyframed 3 keys dipping to -1, position single-key (0, 0), alpha 1,
        // fill #ff2d1ef6 with colorAltCopies off, blend 0.
        val r = parse(
            """<effect id="com.alightcreative.effects.repeat.line" locallyApplied="false"><property name="count" type="float"><kf t="1.347213" v="18.000000" /></property><property name="offset" type="vec2" value="2.000000,0.000000" /><property name="angle" type="float"><kf t="2.928721" v="0.000000" e="cubicBezier 0.0 0.6245989 0.12736319 1.0" /><kf t="5.623147" v="154.000000" e="cubicBezier 0.87 0.0 1.0 0.38" /><kf t="0.000000" v="-23.000000" /><kf t="0.920150" v="-6.000000" /><kf t="0.241158" v="-6.000000" /><kf t="-0.011264" v="-31.000000" /></property><property name="scale" type="float"><kf t="0.044629" v="0.160000" /><kf t="5.623147" v="0.160000" e="cubicBezier 0.87 0.0 1.0 0.38" /><kf t="4.001040" v="1.020000" /><kf t="0.151661" v="0.870000" /></property><property name="alpha" type="float" value="1.000000" /><property name="fillColor" type="color" value="#ff2d1ef6" /><property name="blend" type="float" value="0.000000" /><property name="colorAltCopies" type="bool" value="false" /><property name="start" type="float" value="0.000000" /><property name="end" type="float" value="1.000000" /><property name="phase" type="float"><kf t="0.000000" v="0.018000" /><kf t="5.623147" v="0.018000" /><kf t="2.928721" v="-1.000000" /></property><property name="easeIn" type="float" value="0.000000" /><property name="easeOut" type="float" value="0.000000" /><property name="overlap" type="float" value="0.000000" /><property name="shape" type="int" value="0" /><property name="invert" type="bool" value="false" /><property name="randomOrder" type="bool" value="false" /><property name="seed" type="float" value="0.000000" /><property name="position" type="vec2"><kf t="1.347213" v="0.000000,0.000000" /></property></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.single { it.type == ClipFxLibrary.REPEAT_LINE }
        assertEquals(18f, fx.values[0], 0f)     // count (single key, no warning)
        assertEquals(0f, fx.values[1], 0f)      // position x
        assertEquals(0f, fx.values[2], 0f)      // position y
        assertEquals(2f, fx.values[3], 0f)      // offset x (preview px, k = 1)
        assertEquals(0f, fx.values[4], 0f)      // offset y
        assertEquals(-23f, fx.values[5], 0.001f) // angle: first value at t = 0
        assertEquals(16f, fx.values[6], 0.001f) // scale: 0.16 -> 16%
        assertEquals(100f, fx.values[7], 0f)    // alpha: 1 -> 100%
        assertEquals(0.018f, fx.values[8], 0.001f) // phase: first value at t = 0
        assertEquals(0f, fx.values[9], 0f)      // alt colour off
        assertEquals(0f, fx.values[10], 0f)
        assertEquals(0f, fx.values[11], 0f)
        assertEquals(0f, fx.values[12], 0f)     // padding
        assertEquals(0f, fx.values[13], 0f)
        // The lost sweeps are named, one warning each, plus the window warning.
        assertTrue(r.warnings.contains("Effect repeat.line: keyframed angle is not supported yet: the first value is used"))
        assertTrue(r.warnings.contains("Effect repeat.line: keyframed scale is not supported yet: the first value is used"))
        assertTrue(r.warnings.contains("Effect repeat.line: keyframed phase is not supported yet: the first value is used"))
        assertTrue(r.warnings.contains("Effect repeat.line is mapped approximately: its start / end window and copy ordering are not reproduced"))
        assertEquals(4, r.warnings.count { it.contains("repeat.line") })
        assertTrue(r.warnings.none { it.contains("Effect line is not supported yet") })
        // Kotlin-resolved like the rest of the family: never reaches the shaders.
        assertNull(ClipFxLibrary.toNative(listOf(fx)))
        assertTrue(ClipFxLibrary.hasEffects(listOf(fx)))
    }

    @Test
    fun repeatLineLawFollowsTheImportedValues() {
        // The imported instance above, drawn: copy 0 is the layer (position 0,0),
        // copy 1 steps R(-23 deg) * (2, 0) and shrinks to 16%, copy 2 doubles both.
        val r = parse(
            """<effect id="com.alightcreative.effects.repeat.line" locallyApplied="false"><property name="count" type="float"><kf t="1.347213" v="18.000000" /></property><property name="offset" type="vec2" value="2.000000,0.000000" /><property name="angle" type="float"><kf t="0.000000" v="-23.000000" /></property><property name="scale" type="float"><kf t="0.044629" v="0.160000" /></property><property name="alpha" type="float" value="1.000000" /><property name="phase" type="float" value="0.018000" /><property name="position" type="vec2"><kf t="1.347213" v="0.000000,0.000000" /></property></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val copies = RepeatEval.copiesOf(c)
        assertEquals(18, copies.size)
        assertEquals(c.transformX, copies[0].transformX, 0.001f)
        assertEquals(c.transformY, copies[0].transformY, 0.001f)
        // R(-23 deg) * (2, 0) = (1.841, -0.781).
        assertEquals(c.transformX + 1.841f, copies[1].transformX, 0.01f)
        assertEquals(c.transformY - 0.781f, copies[1].transformY, 0.01f)
        assertEquals(c.scalePercent * 0.16f, copies[1].scalePercent, 0.05f)
        assertEquals(c.opacity, copies[1].opacity, 0.01f)
        assertEquals(c.rotationDeg, copies[1].rotationDeg, 0.001f)   // no per-copy turn
        assertEquals(c.transformX + 3.682f, copies[2].transformX, 0.01f)
        assertEquals(c.transformY - 1.563f, copies[2].transformY, 0.01f)
        assertEquals(c.scalePercent * 0.0256f, copies[2].scalePercent, 0.05f)
        // The picture path reads the same law off the deltas.
        val deltas = RepeatEval.copyDeltas(c)!!
        assertEquals(18, deltas.size)
        assertEquals(0f, deltas[0].dx, 0.001f)
        assertEquals(1.841f, deltas[1].dx, 0.01f)
        assertEquals(-0.781f, deltas[1].dy, 0.01f)
        assertEquals(0f, deltas[1].rotDeg, 0.001f)
        assertEquals(0.16f, deltas[1].scaleMul, 0.001f)
        assertEquals(1f, deltas[1].opacityMul, 0.001f)
    }

    @Test
    fun repeatLineLawWithNonzeroPosition() {
        // A panel-made line (def defaults: position (500, 0), offset (0, 0)) at angle
        // 90: copy 0 sits R(90) * (500, 0) = (0, 500) — NOT on the layer — and the
        // copies step along the turned line; no copy is ever turned itself.
        val fx = ClipFxLibrary.create(ClipFxLibrary.REPEAT_LINE)!!.copy(
            values = listOf(3f, 500f, 0f, 100f, 0f, 90f, 100f, 100f, 0f, 0f, 0f, 0f, 0f, 0f)
        )
        val c = parse(
            """<effect id="com.alightcreative.effects.repeat.line" locallyApplied="false"><property name="count" type="float" value="1.000000" /></effect>"""
        ).snapshot.tracks.flatMap { it.clips }[0]
        val clip = c.copy(effects = listOf(fx))
        val copies = RepeatEval.copiesOf(clip)
        assertEquals(3, copies.size)
        assertEquals(c.transformX, copies[0].transformX, 0.001f)
        assertEquals(c.transformY + 500f, copies[0].transformY, 0.01f)
        assertEquals(c.transformX, copies[1].transformX, 0.001f)
        assertEquals(c.transformY + 600f, copies[1].transformY, 0.01f)
        assertEquals(c.transformX, copies[2].transformX, 0.001f)
        assertEquals(c.transformY + 700f, copies[2].transformY, 0.01f)
        assertTrue(copies.all { it.rotationDeg == c.rotationDeg })
        assertTrue(copies.all { it.scalePercent == c.scalePercent })
    }

    @Test
    fun repeatLinePanelCreateCarriesTheDefDefaults() {
        val fx = ClipFxLibrary.create(ClipFxLibrary.REPEAT_LINE)!!
        assertEquals(14, fx.values.size)
        assertEquals(listOf(5f, 500f, 0f, 0f, 0f, 0f, 100f, 100f, 0f, 0f, 0f, 0f, 0f, 0f), fx.values)
        assertNull(ClipFxLibrary.toNative(listOf(fx)))
    }
}
