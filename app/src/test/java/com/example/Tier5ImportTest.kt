package com.example

import com.example.engine.AlightXmlImporter
import com.example.engine.AmFxEval
import com.example.model.ClipFxLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build 134 (v177): Queue 15(f) — the tier-5 batch imports: bbmaker, unsharpmask,
 * newsharpen, gradshadow, gradientflow, partx, edgeline, lightsweep, 3colorgrad,
 * deepglow (v1) and spill-cleanup as native ports (defs-batch8), moblurplus routed
 * to the motion-blur family mapping; opticcompesation / pulseopacity2 were parked
 * here with precise reasons and un-parked in build 141 (hunt round 4 found their real
 * definitions — Round4ImportTest carries the full pins; the two tests below now assert
 * the shipped behaviour on the same verbatim fragments). The
 * effect elements below are verbatim from the corpus files named in each test.
 * Slot layouts: see the ClipFxLibrary consts / fx_plan.h.
 */
class Tier5ImportTest {

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

    // ---- BB Maker ----

    @Test
    fun bbMakerVerbatimKeyframedStart() {
        // omniframe-evergreen.xml: start keyframed in every corpus instance (this
        // one 32.85 -> 24.45 -> 24.45 -> 17.85); opacity is never serialized.
        val r = parse(
            """<effect id="com.alightcreative.effects.bbmaker" locallyApplied="true"><property name="start" type="float"><kf t="0.144000" v="24.450001" e="cubicBezier 0.0 0.0 0.0 1.0" /><kf t="0.000000" v="32.850002" /><kf t="0.965429" v="24.450001" /><kf t="1.000000" v="17.850000" e="cubicBezier 0.42 0.0 1.0 0.55441177" /></property><property name="angle" type="float" value="90.000000" /><property name="colorbg" type="color" value="#ff000000" /><property name="startA" type="float" value="0.000000" /><property name="endA" type="float" value="1.000000" /><property name="angleA" type="float" value="0.000000" /><property name="featherA" type="float" value="0.000000" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.single { it.type == ClipFxLibrary.BBMAKER_AM }
        assertEquals(32.85f, fx.values[0], 0.001f)    // the t = 0 key
        assertEquals(90f, fx.values[1], 0.001f)       // angle
        assertEquals(0f, fx.values[2], 0.5f)          // colorbg black
        assertEquals(1f, fx.values[3], 0.001f)        // its alpha
        assertEquals(0f, fx.values[4], 0.001f)        // startA
        assertEquals(1f, fx.values[5], 0.001f)        // endA
        assertEquals(0f, fx.values[7], 0.001f)        // featherA (EXT)
        assertEquals(1f, fx.values[8], 0.001f)        // opacity (def default, unserialized)
        assertEquals(4f, fx.values[14], 0.001f)       // start tail: 4 keys, from index 14
        assertTrue(r.warnings.none { it.contains("bbmaker") })
        val at = AmFxEval.resolve(listOf(fx), c, c.startMs + 288L).single()   // rel 0.144
        assertEquals(24.45f, at.values[0], 0.01f)
        val flat = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(16, flat.size)
        assertEquals(155f, flat[0], 0.001f)
        assertEquals(156f, flat[8], 0.001f)
    }

    // ---- Unsharp Mask / New Sharpen ----

    @Test
    fun unsharpMaskVerbatim() {
        // omniframe-cc-5plus1.xml.
        val fx = onlyFx(
            """<effect id="com.alightcreative.effects.unsharpmask" locallyApplied="true"><property name="strength" type="float" value="0.050000" /><property name="amount" type="float" value="0.500000" /><property name="threshold" type="float" value="0.500000" /><property name="showMask" type="bool" value="false" /></effect>""",
            ClipFxLibrary.UNSHARPMASK_AM
        )
        assertEquals(0.05f, fx.values[0], 0.001f)
        assertEquals(0.5f, fx.values[1], 0.001f)
        assertEquals(0.5f, fx.values[2], 0.001f)
        assertEquals(0f, fx.values[3], 0.001f)        // showMask off
        assertEquals(157f, ClipFxLibrary.toNative(listOf(fx))!![0], 0.001f)
    }

    @Test
    fun newSharpenVerbatimSlotOrder() {
        // omniframe-cc-5plus1.xml. The XML serializes strength / brig / iter /
        // whiteonly / bluredonly; the native slot layout is strength, whiteonly,
        // bluredonly, brig, iter — this pins the remapping.
        val fx = onlyFx(
            """<effect id="com.alightcreative.effects.newsharpen" locallyApplied="true"><property name="strength" type="float" value="0.150000" /><property name="brig" type="float" value="0.100000" /><property name="iter" type="float" value="2.000000" /><property name="whiteonly" type="bool" value="false" /><property name="bluredonly" type="bool" value="false" /></effect>""",
            ClipFxLibrary.NEWSHARPEN_AM
        )
        assertEquals(0.15f, fx.values[0], 0.001f)     // strength
        assertEquals(0f, fx.values[1], 0.001f)        // whiteonly
        assertEquals(0f, fx.values[2], 0.001f)        // bluredonly
        assertEquals(0.1f, fx.values[3], 0.001f)      // brig
        assertEquals(2f, fx.values[4], 0.001f)        // iter
        assertEquals(158f, ClipFxLibrary.toNative(listOf(fx))!![0], 0.001f)
    }

    // ---- The blend-generator family ----

    @Test
    fun gradShadowVerbatim() {
        // khanbh-edit-cutsgrow.xml.
        val fx = onlyFx(
            """<effect id="com.alightcreative.effects.gradshadow" locallyApplied="true"><property name="color" type="color" value="#ff000000" /><property name="start" type="float" value="0.950000" /><property name="angle" type="float" value="90.000000" /><property name="alphb" type="float" value="1.000000" /><property name="feather" type="float" value="100.000000" /><property name="end" type="float" value="1.500000" /><property name="bright" type="float" value="1.000000" /><property name="blendMode" type="int" value="0" /><property name="alphab" type="float" value="1.000000" /></effect>""",
            ClipFxLibrary.GRADSHADOW_AM
        )
        assertEquals(0.95f, fx.values[0], 0.001f)     // start
        assertEquals(1.5f, fx.values[1], 0.001f)      // end
        assertEquals(100f, fx.values[2], 0.001f)      // feather (raw; / 100 in the shader)
        assertEquals(90f, fx.values[3], 0.001f)       // angle
        assertEquals(0f, fx.values[4], 0.5f)          // colour black
        assertEquals(1f, fx.values[6], 0.001f)        // bright
        assertEquals(0f, fx.values[7], 0.001f)        // blendMode (EXT)
        assertEquals(1f, fx.values[8], 0.001f)        // alphb
        assertEquals(1f, fx.values[9], 0.001f)        // alphab
        val flat = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(159f, flat[0], 0.001f)
        assertEquals(160f, flat[8], 0.001f)
    }

    @Test
    fun gradientFlowVerbatimAutopClockBaked() {
        // khanbh-op02.xml: autop on, PROGRESS static 1, SPEED 1 — over this scene's
        // 2000 ms clip the shader clock runs 1 -> 3, baked as a linear tail.
        val r = parse(
            """<effect id="com.alightcreative.effects.gradientflow" locallyApplied="true"><property name="autop" type="bool" value="true" /><property name="PROGRESS" type="float" value="1.000000" /><property name="SPEED" type="float" value="1.000000" /><property name="color1" type="color" value="#ff000321" /><property name="color2" type="color" value="#ff001c6b" /><property name="color3" type="color" value="#ff00135c" /><property name="color4" type="color" value="#ff000033" /><property name="FREQ" type="float" value="0.000000" /><property name="AMP" type="float" value="22.000000" /><property name="scale" type="float" value="1.000000" /><property name="angle" type="float" value="0.000000" /><property name="stretch" type="vec2" value="1.000000,1.000000" /><property name="alpha" type="float" value="1.000000" /><property name="hardness" type="float" value="1.000000" /><property name="blendMode" type="int" value="0" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.single { it.type == ClipFxLibrary.GRADIENTFLOW_AM }
        assertEquals(1f, fx.values[3], 0.001f)                       // progress base
        assertEquals(0x000321.toFloat(), fx.values[7], 0.5f)         // color1 (EXT)
        assertEquals(0x001C6B.toFloat(), fx.values[8], 0.5f)         // color2
        assertEquals(0x00135C.toFloat(), fx.values[9], 0.5f)         // color3
        assertEquals(0x000033.toFloat(), fx.values[10], 0.5f)        // color4
        assertEquals(22f, fx.values[12], 0.001f)                     // AMP
        assertEquals(ClipFxLibrary.packPoint(1f, 1f), fx.values[13], 0.5f)   // stretch
        assertEquals(1f, fx.values[14], 0.001f)                      // alpha (EXT2)
        assertEquals(2f, fx.values[21], 0.001f)                      // tail: 2 keys from 21
        assertTrue(r.warnings.none { it.contains("gradientflow") })
        val mid = AmFxEval.resolve(listOf(fx), c, c.startMs + 1000L).single()
        assertEquals(2f, mid.values[3], 0.01f)                       // 1 + 1 s x SPEED 1
        val end = AmFxEval.resolve(listOf(fx), c, c.startMs + 2000L).single()
        assertEquals(3f, end.values[3], 0.01f)
        val flat = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(24, flat.size)
        assertEquals(161f, flat[0], 0.001f)
        assertEquals(162f, flat[8], 0.001f)
        assertEquals(163f, flat[16], 0.001f)
    }

    @Test
    fun partxVerbatimAutopClockBaked() {
        // khanbh-edit-cutsgrow.xml: EVO 1.39 static, autop on — the local clock
        // EVO + time x 0.5 runs 1.39 -> 2.39 over the 2000 ms clip.
        val r = parse(
            """<effect id="com.alightcreative.effects.partx" locallyApplied="true"><property name="colorfg" type="color" value="#ffffffff" /><property name="colorbg" type="color" value="#00000000" /><property name="autop" type="bool" value="true" /><property name="EVO" type="float" value="1.390000" /><property name="kStarDensity" type="float" value="0.200000" /><property name="kMotionBlur" type="float" value="1.000000" /><property name="kNumAngles" type="float" value="200.000000" /><property name="scale" type="float" value="1.000000" /><property name="stretch" type="vec2" value="1.000000,1.000000" /><property name="bright" type="float" value="1.000000" /><property name="alpha" type="float" value="1.000000" /><property name="angle" type="float" value="0.000000" /><property name="blendMode" type="int" value="3" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.single { it.type == ClipFxLibrary.PARTX_AM }
        assertEquals(0xFFFFFF.toFloat(), fx.values[0], 0.5f)   // colorfg white
        assertEquals(1f, fx.values[1], 0.001f)                 // its alpha
        assertEquals(0f, fx.values[3], 0.001f)                 // colorbg alpha 0 (#00000000)
        assertEquals(200f, fx.values[4], 0.001f)               // kNumAngles
        assertEquals(0.2f, fx.values[5], 0.001f)               // kStarDensity
        assertEquals(1.39f, fx.values[7], 0.001f)              // EVO (EXT slot 0)
        assertEquals(3f, fx.values[13], 0.001f)                // blendMode
        assertEquals(1f, fx.values[14], 0.001f)                // alpha (EXT2)
        val end = AmFxEval.resolve(listOf(fx), c, c.startMs + 2000L).single()
        assertEquals(2.39f, end.values[7], 0.01f)
        val flat = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(164f, flat[0], 0.001f)
        assertEquals(165f, flat[8], 0.001f)
        assertEquals(166f, flat[16], 0.001f)
    }

    @Test
    fun edgeLineVerbatim() {
        // khanbh-edit-cutsgrow.xml: cyan over black, mode 0.
        val fx = onlyFx(
            """<effect id="com.alightcreative.effects.edgeline" locallyApplied="true"><property name="smoothing" type="float" value="1.910000" /><property name="threshold" type="float" value="0.140000" /><property name="amp" type="float" value="3.990000" /><property name="alpha" type="float" value="1.000000" /><property name="color2" type="color" value="#ff000000" /><property name="color1" type="color" value="#ff00ffff" /><property name="blendMode" type="int" value="0" /><property name="fix" type="bool" value="true" /><property name="newMode" type="bool" value="true" /><property name="invert" type="bool" value="false" /></effect>""",
            ClipFxLibrary.EDGELINE_AM
        )
        assertEquals(1.91f, fx.values[0], 0.001f)              // smoothing
        assertEquals(0.14f, fx.values[1], 0.001f)              // threshold
        assertEquals(3.99f, fx.values[2], 0.001f)              // amp
        assertEquals(0x00FFFF.toFloat(), fx.values[3], 0.5f)   // color1 cyan
        assertEquals(0f, fx.values[5], 0.5f)                   // color2 black
        assertEquals(1f, fx.values[7], 0.001f)                 // alpha (EXT)
        assertEquals(1f, fx.values[9], 0.001f)                 // fix
        assertEquals(1f, fx.values[10], 0.001f)                // newMode
        assertEquals(0f, fx.values[11], 0.001f)                // invert
        val flat = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(167f, flat[0], 0.001f)
        assertEquals(168f, flat[8], 0.001f)
    }

    @Test
    fun lightSweepVerbatim() {
        // khanbh-op02.xml: static PROGRESS, autop off; color2 is #00ffffff — its
        // alpha 0 rides its own slot.
        val fx = onlyFx(
            """<effect id="com.alightcreative.effects.lightsweep" locallyApplied="true"><property name="autop" type="bool" value="false" /><property name="PROGRESS" type="float" value="0.530000" /><property name="SPEED" type="float" value="1.000000" /><property name="amount" type="float" value="3.000000" /><property name="sharp" type="float" value="0.790000" /><property name="scale" type="float" value="0.800000" /><property name="angle" type="float" value="-22.500000" /><property name="stretch" type="vec2" value="1.000000,1.000000" /><property name="alpha" type="float" value="0.210000" /><property name="hardness" type="float" value="1.000000" /><property name="blendMode" type="int" value="3" /><property name="color1" type="color" value="#ff000000" /><property name="color2" type="color" value="#00ffffff" /></effect>""",
            ClipFxLibrary.LIGHTSWEEP_AM
        )
        assertEquals(15, fx.values.size)                       // no tail
        assertEquals(0.8f, fx.values[0], 0.001f)               // scale
        assertEquals(-22.5f, fx.values[1], 0.001f)             // angle
        assertEquals(0.53f, fx.values[3], 0.001f)              // progress
        assertEquals(3f, fx.values[5], 0.001f)                 // amount
        assertEquals(0.79f, fx.values[6], 0.001f)              // sharp
        assertEquals(0xFFFFFF.toFloat(), fx.values[10], 0.5f)  // color2 white (EXT)
        assertEquals(0f, fx.values[11], 0.001f)                // its alpha: 0x00
        assertEquals(3f, fx.values[13], 0.001f)                // blendMode
        assertEquals(0.21f, fx.values[14], 0.001f)             // alpha (EXT2)
        val flat = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(169f, flat[0], 0.001f)
        assertEquals(170f, flat[8], 0.001f)
        assertEquals(171f, flat[16], 0.001f)
    }

    @Test
    fun lightSweepKeyframedProgressRidesTail() {
        // omniframe-onyankopon.xml: PROGRESS 0.98 -> 1.67 keyframed, alpha 0.
        val r = parse(
            """<effect id="com.alightcreative.effects.lightsweep" locallyApplied="true"><property name="autop" type="bool" value="false" /><property name="PROGRESS" type="float"><kf t="0.000000" v="0.980000" /><kf t="1.000000" v="1.670000" e="cubicBezier 0.42 0.0 0.58 1.0" /></property><property name="SPEED" type="float" value="1.000000" /><property name="amount" type="float" value="1.040000" /><property name="sharp" type="float" value="1.110000" /><property name="scale" type="float" value="2.180000" /><property name="angle" type="float" value="-22.500000" /><property name="stretch" type="vec2" value="1.000000,1.000000" /><property name="alpha" type="float" value="0.000000" /><property name="hardness" type="float" value="1.000000" /><property name="blendMode" type="int" value="1" /><property name="color1" type="color" value="#ff000000" /><property name="color2" type="color" value="#ffffffff" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.single { it.type == ClipFxLibrary.LIGHTSWEEP_AM }
        assertEquals(0.98f, fx.values[3], 0.001f)
        assertEquals(2f, fx.values[21], 0.001f)                // tail: 2 keys from 21
        val end = AmFxEval.resolve(listOf(fx), c, c.startMs + 2000L).single()
        assertEquals(1.67f, end.values[3], 0.01f)
    }

    @Test
    fun colorGrad3VerbatimKeyframedAngle() {
        // khanbh-op02.xml: angle keyframed 0 -> 73.5; color2 is fully transparent.
        val r = parse(
            """<effect id="com.alightcreative.3colorgrad" locallyApplied="true"><property name="color1" type="color" value="#ff0f0f0f" /><property name="color2" type="color" value="#00000000" /><property name="color3" type="color" value="#ff0f0f0f" /><property name="progressa" type="float" value="0.500000" /><property name="progressb" type="float" value="0.500000" /><property name="offsetx" type="float" value="0.000000" /><property name="scale" type="float" value="0.600000" /><property name="alpha" type="float" value="1.000000" /><property name="angle" type="float"><kf t="0.003010" v="0.000000" /><kf t="1.000000" v="73.500000" /></property><property name="bright" type="float" value="1.000000" /><property name="blendMode" type="int" value="0" /><property name="alphab" type="float" value="1.000000" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.single { it.type == ClipFxLibrary.COLORGRAD3_AM }
        assertEquals(0x0F0F0F.toFloat(), fx.values[0], 0.5f)   // color1
        assertEquals(0f, fx.values[3], 0.001f)                 // color2 alpha 0
        assertEquals(0.5f, fx.values[6], 0.001f)               // progressa
        assertEquals(0.5f, fx.values[7], 0.001f)               // progressb (EXT)
        assertEquals(0.6f, fx.values[9], 0.001f)               // scale
        assertEquals(0f, fx.values[10], 0.001f)                // angle at t = 0
        assertEquals(1f, fx.values[14], 0.001f)                // alphab (EXT2)
        assertEquals(2f, fx.values[21], 0.001f)                // angle tail from 21
        assertTrue(r.warnings.none { it.contains("3colorgrad") })
        val end = AmFxEval.resolve(listOf(fx), c, c.startMs + 2000L).single()
        assertEquals(73.5f, end.values[10], 0.05f)
        val flat = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(172f, flat[0], 0.001f)
        assertEquals(173f, flat[8], 0.001f)
        assertEquals(174f, flat[16], 0.001f)
    }

    // ---- Deep Glow (v1) / Spill Cleanup ----

    @Test
    fun deepGlow1Verbatim() {
        // omniframe-mi-mi-mi-pf.xml: the corpus's modal deepglow (strength 0.365).
        val r = parse(
            """<effect id="com.alightcreative.effects.deepglow" locallyApplied="true"><property name="strength" type="float" value="0.365000" /><property name="rrr" type="float" value="1.510000" /><property name="satvib" type="vec2" value="0.000000,1.000000" /><property name="hue" type="float" value="360.000000" /><property name="oalpha" type="float" value="1.000000" /><property name="calpha" type="float" value="1.000000" /><property name="sshadow" type="bool" value="false" /><property name="power" type="float" value="0.150000" /><property name="alphaa" type="float" value="0.750000" /><property name="cblend" type="bool" value="false" /><property name="color" type="color" value="#ffaa0000" /><property name="alpb" type="float" value="0.500000" /><property name="thrs" type="bool" value="false" /><property name="lowThreshold" type="float" value="0.000000" /><property name="highThreshold" type="float" value="1.000000" /><property name="feather" type="float" value="3.000000" /><property name="iter" type="float" value="1.000000" /></effect>"""
        )
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects
            .single { it.type == ClipFxLibrary.DEEP_GLOW_AM }
        assertEquals(21, fx.values.size)
        assertEquals(0.365f, fx.values[0], 0.001f)             // strength
        assertEquals(1.51f, fx.values[1], 0.001f)              // rrr
        assertEquals(360f, fx.values[2], 0.001f)               // hue
        assertEquals(0.5f, fx.values[5], 0.001f)               // alpb
        assertEquals(0xAA0000.toFloat(), fx.values[6], 0.5f)   // colour
        assertEquals(0f, fx.values[7], 0.001f)                 // higsha x (unserialized)
        assertEquals(1f, fx.values[10], 0.001f)                // satvib y (EXT)
        assertEquals(ClipFxLibrary.packPoint(0f, 0f), fx.values[11], 0.5f)   // position (def default)
        assertEquals(0.75f, fx.values[13], 0.001f)             // alphaa
        assertEquals(3f, fx.values[16], 0.001f)                // feather (EXT2)
        assertEquals(0f, fx.values[17], 0.001f)                // flags: no switches
        assertEquals(1f, fx.values[18], 0.001f)                // iter
        assertEquals(0.15f, fx.values[19], 0.001f)             // power (inert, stored)
        assertTrue(r.warnings.none { it.contains("deepglow") && it.contains("not supported") })
        val flat = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(24, flat.size)
        assertEquals(175f, flat[0], 0.001f)
        assertEquals(176f, flat[8], 0.001f)
        assertEquals(177f, flat[16], 0.001f)
    }

    @Test
    fun spillCleanupVerbatim() {
        // blxze-brepizx-pack.xml: locallyApplied false, strength 2.
        val fx = onlyFx(
            """<effect id="com.alightcreative.effects.spill-cleanup" locallyApplied="false"><property name="strength" type="float" value="2.000000" /></effect>""",
            ClipFxLibrary.SPILL_CLEANUP_AM
        )
        assertEquals(2f, fx.values[0], 0.001f)
        assertEquals(178f, ClipFxLibrary.toNative(listOf(fx))!![0], 0.001f)
    }

    // ---- Family mapping + parks ----

    @Test
    fun moblurPlusRoutesToMotionBlur() {
        // khanbh-ae-like-animation.xml: tune 1, all three motion kinds on.
        val r = parse(
            """<effect id="com.alightcreative.effects.moblurplus" locallyApplied="true"><property name="tune" type="float" value="1.000000" /><property name="PANGLE" type="vec2" value="0.500000,0.500000" /><property name="PSCALE" type="vec2" value="0.500000,0.500000" /><property name="usePos" type="bool" value="true" /><property name="useScale" type="bool" value="true" /><property name="useAngle" type="bool" value="true" /></effect>"""
        )
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects
            .single { it.type == ClipFxLibrary.MOTION_BLUR }
        assertEquals(100f, fx.values[0], 0.001f)               // tune x 100
        assertTrue(r.warnings.any { it.contains("moblurplus") && it.contains("mapped approximately") })
        assertTrue(r.warnings.none { it.contains("moblurplus") && it.contains("not supported") })
    }

    @Test
    fun opticCompesationUnparkedInRound4() {
        // omniframe-loser-pf.xml verbatim. Was parked in build 134 (the only staged def
        // delegated to a native pass); hunt round 4 found the original-format def, and the
        // effect now imports natively — Round4ImportTest pins the values / tails in full.
        val r = parse(
            """<effect id="com.alightcreative.effects.opticcompesation" locallyApplied="true"><property name="iterations" type="float" value="10.000000" /><property name="distortion" type="float"><kf t="0.224638" v="0.000000" e="cubicBezier 0.0 1.0 0.58 1.0" /><kf t="0.000000" v="2.190000" /></property><property name="strength" type="float" value="0.000000" /><property name="separation" type="float" value="0.000000" /><property name="blendMode" type="int" value="0" /><property name="scale" type="float" value="1.000000" /><property name="alpha" type="float" value="1.000000" /><property name="stretch" type="vec2" value="1.000000,1.000000" /><property name="hardness" type="float" value="1.000000" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        assertTrue(c.effects.any { it.type == ClipFxLibrary.OPTIC_COMP_AM })
        assertTrue(r.warnings.none { it.contains("opticcompesation") })
    }

    @Test
    fun pulseOpacity2UnparkedInRound4() {
        // blxze-pack-v4.xml verbatim. Was parked in build 134 (the staged def's script never
        // uses its time variable); round 4 proved from AM's STOCK def that the constant IS
        // the effect — the import now bakes it: multiplier (1 - 0.45) + 0.5 x 0.45 = 0.775.
        val r = parse(
            """<effect id="com.alightcreative.effects.pulseopacity2" locallyApplied="true"><property name="freq" type="float" value="16.000000"/><property name="strength" type="float" value="0.450000"/></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        assertTrue(c.effects.isEmpty())
        assertEquals(77.5f, c.opacity, 0.01f)
        assertTrue(r.warnings.none { it.contains("pulseopacity2") })
    }

    // ---- Panel creates ----

    @Test
    fun panelCreatesCarryTheDefDefaults() {
        val bb = ClipFxLibrary.create(ClipFxLibrary.BBMAKER_AM)!!
        assertEquals(14, bb.values.size)
        val flatBb = ClipFxLibrary.toNative(listOf(bb))!!
        assertEquals(155f, flatBb[0], 0.001f)
        assertEquals(156f, flatBb[8], 0.001f)

        val gf = ClipFxLibrary.create(ClipFxLibrary.GRADIENTFLOW_AM)!!
        assertEquals(21, gf.values.size)
        assertEquals(1f, gf.values[14], 0.001f)                // alpha default (EXT2)
        val flatGf = ClipFxLibrary.toNative(listOf(gf))!!
        assertEquals(161f, flatGf[0], 0.001f)
        assertEquals(163f, flatGf[16], 0.001f)

        val dg = ClipFxLibrary.create(ClipFxLibrary.DEEP_GLOW_AM)!!
        assertEquals(21, dg.values.size)
        val flatDg = ClipFxLibrary.toNative(listOf(dg))!!
        assertEquals(175f, flatDg[0], 0.001f)
        assertEquals(177f, flatDg[16], 0.001f)

        val ns = ClipFxLibrary.create(ClipFxLibrary.NEWSHARPEN_AM)!!
        assertEquals(0.15f, ns.values[0], 0.001f)
        assertEquals(2f, ns.values[4], 0.001f)                 // iter default
        assertEquals(158f, ClipFxLibrary.toNative(listOf(ns))!![0], 0.001f)

        val sp = ClipFxLibrary.create(ClipFxLibrary.SPILL_CLEANUP_AM)!!
        assertEquals(178f, ClipFxLibrary.toNative(listOf(sp))!![0], 0.001f)
    }
}
