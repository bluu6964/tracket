package com.example

import com.example.engine.AlightXmlImporter
import com.example.engine.AmFxEval
import com.example.model.ClipFxLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build 132 (v176): Queue 15 top batch imports — Deep Glow 2 (deepglow2 +
 * deepglow_enc), Drop Shadow, and the standalone gaussianblur's move to its own
 * op (the calibration fix: AM raw strength stored, the def's sigma computed in
 * the renderer). The effect elements below are verbatim from the corpus files
 * named in each test. Slot layouts: see the ClipFxLibrary consts.
 */
class Queue15ImportTest {

    private fun scene(layers: String): String =
        """<?xml version='1.0' encoding='UTF-8' ?><!-- test -->
<scene title="My Scene" width="1080" height="1920" bgcolor="#ff102030" totalTime="2000" fps="30">
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

    private fun onlyFx(fx: String, type: String) =
        parse(fx).snapshot.tracks.flatMap { it.clips }[0].effects.single { it.type == type }

    // ---- Deep Glow 2 ----

    @Test
    fun deepGlow2Verbatim() {
        // khanbh-op02.xml (its most common shape: LINEAR add, centre channel 1).
        val r = parse(
            """<effect id="com.alightcreative.effects.deepglow2" locallyApplied="true"><property name="bmode" type="int" value="2" /><property name="overd" type="bool" value="false" /><property name="strength" type="float" value="1.675000" /><property name="oalpha" type="float" value="0.620000" /><property name="lowThreshold" type="float" value="0.100000" /><property name="highThreshold" type="float" value="1.000000" /><property name="feather" type="float" value="0.300000" /><property name="satvib" type="vec2" value="0.000000,1.000000" /><property name="hue" type="float" value="360.000000" /><property name="calpha" type="float" value="1.000000" /><property name="GCE" type="bool" value="false" /><property name="CHROM" type="bool" value="false" /><property name="strengthhh" type="float" value="0.150000" /><property name="angleee" type="float" value="0.000000" /><property name="centerChannel" type="int" value="1" /><property name="cblend" type="bool" value="false" /><property name="cmode" type="int" value="0" /><property name="colblend" type="color" value="#ffffa800" /><property name="alpb" type="float" value="0.500000" /><property name="iter" type="float" value="1.000000" /></effect>"""
        )
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects
            .single { it.type == ClipFxLibrary.DEEP_GLOW2_AM }
        assertEquals(21, fx.values.size)   // no tails: nothing keyframed
        assertEquals(1.675f, fx.values[0], 0.001f)   // strength
        assertEquals(0.62f, fx.values[1], 0.001f)    // oalpha
        assertEquals(0.1f, fx.values[2], 0.001f)     // lowThreshold
        assertEquals(1f, fx.values[3], 0.001f)       // highThreshold
        assertEquals(0.3f, fx.values[4], 0.001f)     // feather
        assertEquals(0.5f, fx.values[5], 0.001f)     // alpb
        assertEquals(2f, fx.values[6], 0.001f)       // bmode LINEAR
        assertEquals(1f, fx.values[7], 0.001f)       // calpha (EXT)
        assertEquals(360f, fx.values[8], 0.001f)     // hue, raw degrees
        assertEquals(0f, fx.values[9], 0.001f)       // satvib x
        assertEquals(1f, fx.values[10], 0.001f)      // satvib y
        assertEquals(0xFFA800.toFloat(), fx.values[11], 0.5f)   // colblend packed
        assertEquals(0.15f, fx.values[12], 0.001f)   // strengthhh
        assertEquals(0f, fx.values[13], 0.001f)      // angleee
        assertEquals(ClipFxLibrary.packPoint(0f, 0f), fx.values[14], 0.5f)   // position (def default)
        assertEquals(32f, fx.values[17], 0.001f)     // flags: centre channel 1, no switches
        assertEquals(1f, fx.values[18], 0.001f)      // iter
        assertTrue(r.warnings.none { it.contains("deepglow2") })
        // The native stream carries all three groups (main 151, EXT 152, EXT2 153).
        val flat = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(24, flat.size)
        assertEquals(151f, flat[0], 0.001f)
        assertEquals(152f, flat[8], 0.001f)
        assertEquals(153f, flat[16], 0.001f)
        assertEquals(32f, flat[16 + 4], 0.001f)      // flags ride EXT2 slot 3
    }

    @Test
    fun deepGlow2KeyframedStrengthAndOalphaRideTails() {
        // khanbh-edit-cutsgrow.xml: the corpus's only keyframed deepglow2 — strength
        // and oalpha both keyed (no easings serialized).
        val r = parse(
            """<effect id="com.alightcreative.effects.deepglow2" locallyApplied="true"><property name="bmode" type="int" value="2" /><property name="overd" type="bool" value="false" /><property name="strength" type="float"><kf t="0.567880" v="0.150000" /><kf t="0.551402" v="0.215000" /><kf t="0.526808" v="0.025000" /></property><property name="oalpha" type="float"><kf t="0.526808" v="0.610000" /><kf t="0.567880" v="0.610000" /><kf t="0.551402" v="3.000000" /></property><property name="lowThreshold" type="float" value="0.100000" /><property name="highThreshold" type="float" value="1.000000" /><property name="feather" type="float" value="0.300000" /><property name="satvib" type="vec2" value="0.000000,1.000000" /><property name="hue" type="float" value="360.000000" /><property name="calpha" type="float" value="1.000000" /><property name="GCE" type="bool" value="false" /><property name="CHROM" type="bool" value="false" /><property name="strengthhh" type="float" value="0.150000" /><property name="angleee" type="float" value="0.000000" /><property name="centerChannel" type="int" value="1" /><property name="cblend" type="bool" value="false" /><property name="cmode" type="int" value="0" /><property name="colblend" type="color" value="#ffffa800" /><property name="alpb" type="float" value="0.500000" /><property name="iter" type="float" value="1.000000" /></effect>"""
        )
        assertFalse(r.warnings.any { it.contains("keyframed") })
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.single { it.type == ClipFxLibrary.DEEP_GLOW2_AM }
        assertEquals(0.025f, fx.values[0], 0.001f)   // clamped to the first key at t = 0
        assertEquals(0.61f, fx.values[1], 0.001f)
        assertEquals(3f, fx.values[21], 0.001f)      // strength tail: three keys, from index 21
        assertEquals(3f, fx.values[40], 0.001f)      // oalpha tail follows (21 + 1 + 3 x 6)
        val peak = AmFxEval.resolve(listOf(fx), c, c.startMs + 1103L).single()   // rel 0.5515, the shared peak key
        assertEquals(0.215f, peak.values[0], 0.01f)
        assertEquals(3f, peak.values[1], 0.05f)
    }

    @Test
    fun deepGlow2GceFlagVerbatim() {
        // khanbh-edit-cutsgrow.xml: the corpus's only GCE instance (strength 0.965).
        val fx = onlyFx(
            """<effect id="com.alightcreative.effects.deepglow2" locallyApplied="true"><property name="bmode" type="int" value="2" /><property name="overd" type="bool" value="false" /><property name="strength" type="float" value="0.965000" /><property name="oalpha" type="float" value="0.830000" /><property name="lowThreshold" type="float" value="0.100000" /><property name="highThreshold" type="float" value="1.000000" /><property name="feather" type="float" value="0.300000" /><property name="satvib" type="vec2" value="0.000000,1.000000" /><property name="hue" type="float" value="360.000000" /><property name="calpha" type="float" value="1.000000" /><property name="GCE" type="bool" value="true" /><property name="CHROM" type="bool" value="false" /><property name="strengthhh" type="float" value="0.150000" /><property name="angleee" type="float" value="0.000000" /><property name="centerChannel" type="int" value="1" /><property name="cblend" type="bool" value="false" /><property name="cmode" type="int" value="0" /><property name="colblend" type="color" value="#ffffa800" /><property name="alpb" type="float" value="0.500000" /><property name="iter" type="float" value="1.000000" /></effect>""",
            ClipFxLibrary.DEEP_GLOW2_AM
        )
        assertEquals(0.965f, fx.values[0], 0.001f)
        assertEquals(34f, fx.values[17], 0.001f)     // flags: centre 32 + GCE 2
    }

    @Test
    fun deepGlow2OverdVerbatim() {
        // omniframe-tiki-tiki-pf.xml: overd on, COLOR dodge (bmode 1).
        val fx = onlyFx(
            """<effect id="com.alightcreative.effects.deepglow2" locallyApplied="true"><property name="bmode" type="int" value="1" /><property name="overd" type="bool" value="true" /><property name="strength" type="float" value="0.180000" /><property name="oalpha" type="float" value="0.500000" /><property name="lowThreshold" type="float" value="0.100000" /><property name="highThreshold" type="float" value="1.000000" /><property name="feather" type="float" value="0.300000" /><property name="satvib" type="vec2" value="0.000000,1.000000" /><property name="hue" type="float" value="360.000000" /><property name="calpha" type="float" value="1.000000" /><property name="GCE" type="bool" value="false" /><property name="CHROM" type="bool" value="false" /><property name="strengthhh" type="float" value="0.150000" /><property name="angleee" type="float" value="0.000000" /><property name="centerChannel" type="int" value="1" /><property name="cblend" type="bool" value="false" /><property name="cmode" type="int" value="0" /><property name="colblend" type="color" value="#ffffa800" /><property name="alpb" type="float" value="0.500000" /><property name="iter" type="float" value="1.000000" /></effect>""",
            ClipFxLibrary.DEEP_GLOW2_AM
        )
        assertEquals(1f, fx.values[6], 0.001f)       // bmode COLOR
        assertEquals(33f, fx.values[17], 0.001f)     // flags: centre 32 + overd 1
    }

    @Test
    fun deepGlowEncMapsToTheSameOp() {
        // omniframe-loser-pf.xml: deepglow_enc, SCREEN (bmode 0), strength 0.55.
        val fx = onlyFx(
            """<effect id="com.alightcreative.effects.deepglow_enc" locallyApplied="true"><property name="bmode" type="int" value="0" /><property name="overd" type="bool" value="false" /><property name="strength" type="float" value="0.550000" /><property name="oalpha" type="float" value="1.570000" /><property name="lowThreshold" type="float" value="0.100000" /><property name="highThreshold" type="float" value="1.000000" /><property name="feather" type="float" value="0.300000" /><property name="satvib" type="vec2" value="0.000000,1.000000" /><property name="hue" type="float" value="360.000000" /><property name="calpha" type="float" value="1.000000" /><property name="GCE" type="bool" value="false" /><property name="CHROM" type="bool" value="false" /><property name="strengthhh" type="float" value="0.150000" /><property name="angleee" type="float" value="0.000000" /><property name="centerChannel" type="int" value="1" /><property name="cblend" type="bool" value="false" /><property name="cmode" type="int" value="0" /><property name="colblend" type="color" value="#ffffa800" /><property name="alpb" type="float" value="0.500000" /><property name="iter" type="float" value="1.000000" /></effect>""",
            ClipFxLibrary.DEEP_GLOW2_AM
        )
        assertEquals(0.55f, fx.values[0], 0.001f)
        assertEquals(1.57f, fx.values[1], 0.001f)
        assertEquals(0f, fx.values[6], 0.001f)       // bmode SCREEN
    }

    @Test
    fun deepGlow2HiddenStaysSkipped() {
        val r = parse(
            """<effect id="com.alightcreative.effects.deepglow2" hidden="true" locallyApplied="true"><property name="strength" type="float" value="0.150000" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        assertTrue(c.effects.none { it.type == ClipFxLibrary.DEEP_GLOW2_AM })
    }

    // ---- Drop Shadow ----

    @Test
    fun dropShadowVerbatim() {
        // khanbh-op02.xml: #ba000000 (alpha 186/255 in its own slot), position (0, 12).
        val r = parse(
            """<effect id="com.alightcreative.effects.dropshadow" locallyApplied="true"><property name="colorshadow" type="color" value="#ba000000" /><property name="position" type="vec2" value="0.000000,12.000000" /><property name="strength" type="float" value="0.155000" /><property name="alpha" type="float" value="0.210000" /><property name="alphac" type="float" value="1.000000" /></effect>"""
        )
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects
            .single { it.type == ClipFxLibrary.DROP_SHADOW_AM }
        assertEquals(0f, fx.values[0], 0.5f)                       // colour RGB: black
        assertEquals(186f / 255f, fx.values[1], 0.002f)            // its alpha, own slot
        assertEquals(ClipFxLibrary.packPoint(0f, 12f), fx.values[2], 0.5f)
        assertEquals(0.155f, fx.values[3], 0.001f)                 // strength
        assertEquals(0.21f, fx.values[4], 0.001f)                  // alpha
        assertEquals(1f, fx.values[5], 0.001f)                     // alphac
        assertTrue(r.warnings.none { it.contains("dropshadow") })
        val flat = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(154f, flat[0], 0.001f)
    }

    @Test
    fun dropShadowDefaultsVerbatim() {
        // robo-full-cr7-edit.xml: no position serialized (the def default (0, 0)),
        // opaque black.
        val fx = onlyFx(
            """<effect id="com.alightcreative.effects.dropshadow" locallyApplied="true"><property name="colorshadow" type="color" value="#ff000000" /><property name="strength" type="float" value="0.120000" /><property name="alpha" type="float" value="0.300000" /><property name="alphac" type="float" value="1.000000" /></effect>""",
            ClipFxLibrary.DROP_SHADOW_AM
        )
        assertEquals(1f, fx.values[1], 0.001f)                     // alpha 0xFF
        assertEquals(ClipFxLibrary.packPoint(0f, 0f), fx.values[2], 0.5f)
        assertEquals(0.12f, fx.values[3], 0.001f)
        assertEquals(0.3f, fx.values[4], 0.001f)
    }

    // ---- Panel creates ----

    @Test
    fun panelCreatesCarryTheDefDefaults() {
        val dg = ClipFxLibrary.create(ClipFxLibrary.DEEP_GLOW2_AM)!!
        assertEquals(21, dg.values.size)   // 7 panel params + EXT 7 + EXT2 7
        assertEquals(0.54f, dg.values[0], 0.001f)
        assertEquals(1f, dg.values[7], 0.001f)       // calpha default
        assertEquals(360f, dg.values[8], 0.001f)     // hue default
        assertEquals(32f, dg.values[17], 0.001f)     // flags: centre channel 1
        assertEquals(1f, dg.values[18], 0.001f)      // iter default
        val flat = ClipFxLibrary.toNative(listOf(dg))!!
        assertEquals(24, flat.size)
        assertEquals(151f, flat[0], 0.001f)
        assertEquals(152f, flat[8], 0.001f)
        assertEquals(153f, flat[16], 0.001f)

        val gb = ClipFxLibrary.create(ClipFxLibrary.GAUSSIAN_BLUR_AM)!!
        assertEquals(listOf(0.15f), gb.values)
        assertEquals(150f, ClipFxLibrary.toNative(listOf(gb))!![0], 0.001f)

        val ds = ClipFxLibrary.create(ClipFxLibrary.DROP_SHADOW_AM)!!
        assertEquals(6, ds.values.size)
        assertEquals(0.73f, ds.values[1], 0.001f)    // AM's default colour alpha (#ba...)
        assertEquals(154f, ClipFxLibrary.toNative(listOf(ds))!![0], 0.001f)
    }
}
