package com.example

import com.example.engine.AlightXmlImporter
import com.example.engine.AmFxEval
import com.example.engine.ParentRig
import com.example.model.AnimProp
import com.example.model.ClipFxLibrary
import com.example.model.renderAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build 129 (v175): Alight's effects tier 4 imports — the def-backed ids of corpus
 * round 2 (defs-batch7; every def recovered from two dumps, shader-identical
 * between them). The effect elements below are verbatim from the corpus files named
 * in each test, except where a test says it shaped one to pin a convention.
 * Slot layouts: see the ClipFxLibrary consts.
 */
class Tier4ImportTest {

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

    @Test
    fun thresholdVerbatim() {
        // blxze-brepizx-pack.xml (one of its many 0.71 instances).
        val r = parse(
            """<effect id="com.alightcreative.effects.threshold" locallyApplied="false"><property name="threshold" type="float" value="0.710000" /><property name="feather" type="float" value="0.000000" /><property name="invert" type="bool" value="false" /><property name="blendMode" type="int" value="0" /></effect>"""
        )
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects
            .single { it.type == ClipFxLibrary.THRESHOLD_AM }
        assertEquals(listOf(0.71f, 0f, 0f, 0f), fx.values)
        assertTrue(r.warnings.none { it.contains("threshold") })
    }

    @Test
    fun thresholdHiddenStaysSkipped() {
        // The same instance with hidden="true" (the corpus carries several): Alight
        // disables it, so nothing imports and the layer's one hidden-effects note
        // names it.
        val r = parse(
            """<effect id="com.alightcreative.effects.threshold" hidden="true" locallyApplied="false"><property name="threshold" type="float" value="0.710000" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        assertTrue(c.effects.none { it.type == ClipFxLibrary.THRESHOLD_AM })
        assertTrue(r.warnings.any { it.contains("hidden effects are disabled") && it.contains("threshold") })
    }

    @Test
    fun spectralmapVerbatim() {
        // blxze-brepizx-pack.xml: white for red, black for green / blue, saturation 1,
        // scaleBrightness on.
        val fx = onlyFx(
            """<effect id="com.alightcreative.spectralmap" locallyApplied="false"><property name="blueColor" type="color" value="#ff000000" /><property name="greenColor" type="color" value="#ff000000" /><property name="redColor" type="color" value="#ffffffff" /><property name="saturation" type="float" value="1.000000" /><property name="scaleBrightness" type="bool" value="true" /><property name="phase" type="float" value="0.000000" /><property name="repeat" type="float" value="1.000000" /><property name="alpha" type="float" value="1.000000" /><property name="squeeze" type="float" value="0.000000" /><property name="reverse" type="bool" value="false" /></effect>""",
            ClipFxLibrary.SPECTRALMAP_AM
        )
        assertEquals(0f, fx.values[0], 0.001f)        // phase
        assertEquals(1f, fx.values[1], 0.001f)        // repeat
        assertEquals(1f, fx.values[2], 0.001f)        // alpha
        assertEquals(0f, fx.values[3], 0.001f)        // squeeze
        assertEquals(1f, fx.values[4], 0.001f)        // saturation
        assertEquals(0f, fx.values[5], 0.001f)        // reverse
        assertEquals(1f, fx.values[6], 0.001f)        // scale brightness
        assertEquals(0xFFFFFF.toFloat(), fx.values[7], 0.01f)   // red colour
        assertEquals(0f, fx.values[8], 0.001f)        // green colour
        assertEquals(0f, fx.values[9], 0.001f)        // blue colour
        val flat = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(132f, flat[0], 0f)
        assertEquals(133f, flat[8], 0f)               // the EXT group rides behind
    }

    @Test
    fun channelmapVerbatim() {
        // blxze-pack-v4.xml: only blue is carried (blue <- green); the rest are the
        // def's identity defaults.
        val fx = onlyFx(
            """<effect id="com.alightcreative.effects.channelmap" locallyApplied="false"><property name="blue" type="int" value="1"/></effect>""",
            ClipFxLibrary.CHANNELMAP_AM
        )
        assertEquals(listOf(0f, 1f, 1f, 3f), fx.values)
    }

    @Test
    fun halftoneLinesKeyframedVerbatim() {
        // blxze-brepizx-pack.xml: angle 0 -> 90 and phase -1.15 -> 4.4 keyframed;
        // both ride AmFxEval tails (angle first, then phase).
        val r = parse(
            """<effect id="com.alightcreative.effects.halftonelines" locallyApplied="false"><property name="amount" type="float" value="120.000000" /><property name="angle" type="float"><kf t="0.000000" v="0.000000" /><kf t="1.000000" v="90.000000" /></property><property name="color1" type="color" value="#ff000000" /><property name="color2" type="color" value="#ffff4444" /><property name="phase" type="float"><kf t="0.000000" v="-1.150000" /><kf t="1.000000" v="4.400000" /></property><property name="strength" type="float" value="0.000000" /></effect>"""
        )
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.HALFTONE_LINES_AM }
        assertEquals(120f, fx.values[0], 0.001f)
        assertEquals(0f, fx.values[1], 0.001f)        // strength
        assertEquals(0f, fx.values[2], 0.001f)        // angle, first key
        assertEquals(-1.15f, fx.values[3], 0.001f)    // phase, first key
        assertEquals(0f, fx.values[4], 0.01f)         // colour 1
        assertEquals(0xFF4444.toFloat(), fx.values[5], 0.01f)
        assertEquals(2f, fx.values[7], 0f)            // angle tail: 2 keys
        assertEquals(90f, fx.values[15], 0.001f)      // angle tail: second key value
        assertEquals(2f, fx.values[20], 0f)           // phase tail: 2 keys
        assertEquals(4.4f, fx.values[28], 0.001f)
        val mid = AmFxEval.resolve(listOf(fx), clip, 1000L)[0]
        assertEquals(45f, mid.values[2], 0.01f)       // angle at the layer's midpoint
        assertEquals(1.625f, mid.values[3], 0.01f)    // phase at the midpoint
        assertTrue(r.warnings.none { it.contains("halftonelines") })
    }

    @Test
    fun turbulence2Verbatim() {
        // khanbh-ekka1.xml, its only instance: offs / parallax keyframed as points.
        val fx = onlyFx(
            """<effect id="com.alightcreative.effects.turbulence2" locallyApplied="true"><property name="parr" type="vec2"><kf t="0.002928" v="407.000000,32.000000" /><kf t="1.539982" v="321.000000,32.000000" /></property><property name="offs" type="vec2"><kf t="0.002928" v="182.000000,0.000000" /><kf t="1.539982" v="1089.000000,0.000000" /></property><property name="evolution" type="float" value="1.850000" /><property name="scale" type="float" value="1.000000" /><property name="stretch" type="vec2" value="1.520000,1.000000" /><property name="intensity" type="float" value="1.500000" /><property name="seed" type="float" value="0.000000" /><property name="color1" type="color" value="#ff000000" /><property name="color2" type="color" value="#ffff4444" /><property name="alpha" type="float" value="1.000000" /><property name="blendMode" type="int" value="0" /><property name="bias" type="float" value="0.000000" /><property name="octaves" type="float" value="6.000000" /></effect>""",
            ClipFxLibrary.TURBULENCE2_AM
        )
        assertEquals(1.5f, fx.values[0], 0.001f)      // intensity
        assertEquals(1f, fx.values[1], 0.001f)        // scale
        assertEquals(1.85f, fx.values[2], 0.001f)     // evolution
        assertEquals(0xFF4444.toFloat(), fx.values[5], 0.01f)
        assertEquals(182f, fx.values[7], 0.01f)       // offs x (first key)
        assertEquals(0f, fx.values[8], 0.001f)        // offs y
        assertEquals(407f, fx.values[9], 0.01f)       // parallax x
        assertEquals(32f, fx.values[10], 0.001f)      // parallax y
        assertEquals(1.52f, fx.values[11], 0.001f)    // stretch x
        assertEquals(1f, fx.values[12], 0.001f)       // stretch y
        assertEquals(1f, fx.values[14], 0.001f)       // alpha (EXT2)
        assertEquals(6f, fx.values[15], 0.001f)       // octaves (EXT2)
        assertEquals(2f, fx.values[21], 0f)           // offs x tail: 2 keys
        val flat = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(24, flat.size)
        assertEquals(136f, flat[0], 0f)
        assertEquals(137f, flat[8], 0f)
        assertEquals(138f, flat[16], 0f)
    }

    @Test
    fun checker2Verbatim() {
        // blxze-pack-v4.xml: amount + black; everything else the def's defaults.
        val fx = onlyFx(
            """<effect id="com.alightcreative.effects.checker2" locallyApplied="true"><property name="amount" type="float" value="132.600006"/><property name="color" type="color" value="#FF000000"/></effect>""",
            ClipFxLibrary.CHECKER2_AM
        )
        assertEquals(132.6f, fx.values[0], 0.001f)
        assertEquals(0f, fx.values[1], 0.001f)        // angle
        assertEquals(0f, fx.values[2], 0.01f)         // colour (black)
        assertEquals(0f, fx.values[5], 0.001f)        // position x
        assertEquals(1f, fx.values[7], 0.001f)        // stretch x
        assertEquals(1f, fx.values[8], 0.001f)        // stretch y
    }

    @Test
    fun mosaicVerbatimAndKeyframed() {
        // khanbh-op02.xml: size 12.5 on a text layer.
        val fx = onlyFx(
            """<effect id="com.alightcreative.effects.mosaic" locallyApplied="true"><property name="size" type="float" value="12.500000" /></effect>""",
            ClipFxLibrary.MOSAIC_AM
        )
        assertEquals(listOf(12.5f, 0f, 0f), fx.values)
        // blxze-brepizx-pack.xml: phase (190, 83) constant, size keyframed (keys run
        // outside 0..1 as serialized; the value at 0 interpolates the -0.08 / 0.462
        // keys, and the track rides a tail).
        val kf = onlyFx(
            """<effect id="com.alightcreative.effects.mosaic" locallyApplied="false"><property name="phase" type="vec2" value="190.000000,83.000000" /><property name="size" type="float"><kf t="1.484652" v="1.000000" /><kf t="1.850275" v="3.500000" /><kf t="1.113489" v="4.000000" /><kf t="1.000000" v="1.000000" /><kf t="0.462311" v="4.000000" /><kf t="-0.080402" v="1.000000" /><kf t="-0.365623" v="4.000000" /><kf t="-0.731247" v="1.000000" /></property></effect>""",
            ClipFxLibrary.MOSAIC_AM
        )
        assertEquals(190f, kf.values[1], 0.01f)
        assertEquals(83f, kf.values[2], 0.01f)
        assertEquals(1.44f, kf.values[0], 0.05f)      // size at t = 0
        assertEquals(8f, kf.values[7], 0f)            // the size tail: 8 keys
    }

    @Test
    fun glowVerbatimAndKeyframedColor() {
        // khanbh-scene-1.xml: screen blend (2), white at full alpha.
        val fx = onlyFx(
            """<effect id="com.alightcreative.glow" locallyApplied="true"><property name="radius" type="float" value="23.700001" /><property name="alpha" type="float" value="0.750000" /><property name="hardness" type="float" value="0.500000" /><property name="color" type="color" value="#ffffffff" /><property name="blendMode" type="int" value="2" /></effect>""",
            ClipFxLibrary.GLOW_AM
        )
        assertEquals(23.7f, fx.values[0], 0.001f)
        assertEquals(0.75f, fx.values[1], 0.001f)
        assertEquals(0.5f, fx.values[2], 0.001f)
        assertEquals(0xFFFFFF.toFloat(), fx.values[3], 0.01f)
        assertEquals(1f, fx.values[4], 0.001f)        // colour alpha (FF)
        assertEquals(2f, fx.values[5], 0.001f)
        // blxze-pack-v4.xml: the corpus's keyframed-colour instance — first value
        // (#FFFFE200) + the note; blendMode omitted -> the def's default 2.
        val r = parse(
            """<effect id="com.alightcreative.glow" locallyApplied="false"><property name="alpha" type="float" value="1.000000"/><property name="color" type="color"><kf t="0.000000" v="#FFFFE200"/><kf t="0.500493" v="#FFFFFFFF"/><kf t="1.000000" v="#FFDD44DD"/></property><property name="hardness" type="float" value="0.080000"/><property name="radius" type="float" value="121.700005"/></effect>"""
        )
        val kf = r.snapshot.tracks.flatMap { it.clips }[0].effects
            .single { it.type == ClipFxLibrary.GLOW_AM }
        assertEquals(121.7f, kf.values[0], 0.001f)
        assertEquals(0xFFE200.toFloat(), kf.values[3], 0.01f)
        assertEquals(2f, kf.values[5], 0.001f)
        assertTrue(r.warnings.any { it.contains("keyframed color is not supported yet") })
    }

    @Test
    fun innerBlurVerbatim() {
        // blxze-brepizx-pack.xml: strength keyframed (unsorted keys as serialized),
        // iter 2. The slot holds the eased value at 0 (the t = 0 key: 0.1) and the
        // track rides a tail.
        val fx = onlyFx(
            """<effect id="com.alightcreative.effects.innerblur" locallyApplied="false"><property name="strength" type="float"><kf t="0.388778" v="0.000000" e="cubicBezier 0.0 0.58390915 0.47188583 1.0" /><kf t="0.000000" v="0.100000" /><kf t="1.000000" v="0.095000" e="cubicBezier 0.8306778 0.0 1.0 0.23267633" /><kf t="0.639279" v="0.001224" /></property><property name="iter" type="float" value="2.000000" /></effect>""",
            ClipFxLibrary.INNER_BLUR_AM
        )
        assertEquals(0.1f, fx.values[0], 0.001f)
        assertEquals(2f, fx.values[1], 0.001f)
        assertEquals(4f, fx.values[7], 0f)            // the strength tail: 4 keys
    }

    @Test
    fun boxBlur3Verbatim() {
        // blxze-brepizx-pack.xml: its strongest boxblur3.
        val fx = onlyFx(
            """<effect id="com.alightcreative.effects.boxblur3" locallyApplied="false"><property name="strength" type="float" value="1.589000" /></effect>""",
            ClipFxLibrary.BOX_BLUR3_AM
        )
        assertEquals(listOf(1.589f), fx.values)
    }

    @Test
    fun featherVerbatim() {
        // blxze-pack-v4.xml: iter 5, strength keyframed 2 -> 0.
        val fx = onlyFx(
            """<effect id="com.alightcreative.effects.feather" locallyApplied="false"><property name="iter" type="float" value="5.000000"/><property name="strength" type="float"><kf t="0.000000" v="2.000000"/><kf t="0.938087" v="0.000000" e="cubicBezier 0.0 0.4578948 0.3535826 1.0"/></property></effect>""",
            ClipFxLibrary.FEATHER_AM
        )
        assertEquals(2f, fx.values[0], 0.001f)
        assertEquals(5f, fx.values[1], 0.001f)
        assertEquals(2f, fx.values[7], 0f)            // the strength tail: 2 keys
    }

    @Test
    fun electricEdgesVerbatim() {
        // blxze-brepizx-pack.xml: evolution keyframed 0 -> 3.27 (rides the tail at
        // index 21, EXT slot 2); everything else at the instance's values.
        val fx = onlyFx(
            """<effect id="com.alightcreative.effects.electricedges" locallyApplied="false"><property name="color1" type="color" value="#ffff4444" /><property name="color2" type="color" value="#ffffffff" /><property name="complexity" type="float" value="3.150000" /><property name="disort" type="float" value="-0.700000" /><property name="distance" type="float" value="0.613000" /><property name="evolution" type="float"><kf t="0.000000" v="0.000000" /><kf t="1.000000" v="3.270000" /></property><property name="maskColor" type="color" value="#ffff4444" /><property name="thickness" type="float" value="0.526000" /><property name="strength" type="float" value="0.420000" /><property name="alpha" type="float" value="1.000000" /><property name="glowAlpha" type="float" value="0.500000" /><property name="seed" type="float" value="0.000000" /><property name="fill" type="float" value="1.000000" /><property name="maskStrength" type="float" value="0.000000" /><property name="maskEvolution" type="float" value="0.000000" /><property name="maskScale" type="float" value="1.000000" /></effect>""",
            ClipFxLibrary.ELECTRIC_EDGES_AM
        )
        assertEquals(0.613f, fx.values[0], 0.001f)    // distance
        assertEquals(0.526f, fx.values[1], 0.001f)    // thickness
        assertEquals(3.15f, fx.values[2], 0.001f)     // complexity
        assertEquals(-0.7f, fx.values[3], 0.001f)     // disort
        assertEquals(0xFF4444.toFloat(), fx.values[4], 0.01f)
        assertEquals(0xFFFFFF.toFloat(), fx.values[5], 0.01f)
        assertEquals(1f, fx.values[6], 0.001f)        // alpha
        assertEquals(0.5f, fx.values[7], 0.001f)      // glow alpha (EXT)
        assertEquals(0f, fx.values[9], 0.001f)        // evolution, first key
        assertEquals(0xFF4444.toFloat(), fx.values[15], 0.01f)  // mask colour (EXT2)
        assertEquals(0.42f, fx.values[20], 0.001f)    // strength (EXT2)
        assertEquals(2f, fx.values[21], 0f)           // the evolution tail: 2 keys
        assertEquals(3.27f, fx.values[29], 0.001f)    // its second key value
        val flat = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(24, flat.size)
        assertEquals(146f, flat[0], 0f)
        assertEquals(147f, flat[8], 0f)
        assertEquals(148f, flat[16], 0f)
    }

    @Test
    fun smoothEdgesVerbatim() {
        // khanbh-iphone-17-animation.xml: strength 0.07, not masked to the layer.
        val fx = onlyFx(
            """<effect id="com.alightcreative.effects.smoothedges" locallyApplied="true"><property name="strength" type="float" value="0.070000" /><property name="maskToLayer" type="bool" value="false" /></effect>""",
            ClipFxLibrary.SMOOTH_EDGES_AM
        )
        assertEquals(listOf(0.07f, 0f), fx.values)
    }

    @Test
    fun oscillate2BakesAlongItsOwnDirection() {
        // Corpus-shaped (freq / mag of the blxze-pack-v4 instance) with angle 0: the
        // def's direction is (sin a, cos a) = (0, 1), so the bake moves ONLY in y —
        // oscillate's (cos a, sin a) would move only in x.
        val r = parse(
            """<effect id="com.alightcreative.effects.oscillate2" locallyApplied="false"><property name="angle" type="float" value="0.000000"/><property name="freq" type="float" value="2.000000"/><property name="mag" type="float" value="25.000000"/><property name="type" type="int" value="0"/><property name="phase" type="float" value="0.000000"/></effect>"""
        )
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val keys = clip.keyframes[AnimProp.POSITION]
        assertTrue(keys != null && keys.size > 4)
        val baseX = clip.transformX
        val baseY = clip.transformY
        assertTrue(keys!!.all { kotlin.math.abs(it.a - baseX) < 0.01f })
        assertTrue(keys.any { kotlin.math.abs(it.b - baseY) > 20f })
        assertTrue(r.warnings.none { it.contains("oscillate2") })
    }

    @Test
    fun flicker2VerbatimBakesOpacity() {
        // blxze-pack-v4.xml, its only instance: freq / mag keyframed; the bake must
        // produce opacity keys that dip under the layer's own opacity.
        val r = parse(
            """<effect id="com.alightcreative.effects.flicker2" locallyApplied="true"><property name="freq" type="float"><kf t="0.987250" v="7.000000"/><kf t="0.014572" v="10.000000"/></property><property name="mag" type="float"><kf t="0.000000" v="0.750000"/><kf t="0.987250" v="0.000000" e="cubicBezier 0.42 0.0 0.58 1.0"/></property></effect>"""
        )
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val keys = clip.keyframes[AnimProp.OPACITY]
        assertTrue(keys != null && keys.size >= 3)
        assertTrue(keys!!.any { it.a < 99f })   // the layer's own opacity is 100; the flicker dips it
        assertTrue(keys.all { it.a in 0f..100f })
        assertTrue(r.warnings.none { it.contains("flicker2") })
    }

    @Test
    fun parentHelperLocksRotation() {
        // khanbh-scene-5.xml's helper (verbatim props) on a shape parented to a Null:
        // rotateMode 1 locks the link's rotation; the position still follows.
        val layers =
            """<nullobj id="5050" label="Null" startTime="0" endTime="2000" mediaFillMode="stretch" type="perspective"><transform><location value="540.000000,960.000000,0.000000" /><scale value="2.000000,2.000000" /><rotation value="30.000000" /></transform></nullobj>""" +
            """<shape id="5045" label="Pic" startTime="0" endTime="2000" fillType="color" parent="5050" mediaFillMode="stretch" s=".rect"><transform><location value="100.000000,50.000000,0.000000" /><scale value="1.000000,1.000000" /></transform><fillColor value="#ff00ff00" /><effect id="com.alightcreative.effects.parenthelper" locallyApplied="true"><property name="scaleMode" type="int" value="0" /><property name="rotateMode" type="int" value="1" /><property name="scaleWeight" type="float" value="1.000000" /><property name="rotateWeight" type="float" value="1.000000" /><property name="autoRotate" type="int" value="0" /><property name="radiusAdjust" type="float" value="0.000000" /></effect><property name="size" type="vec2" value="100.000000,100.000000" /></shape>"""
        val r = AlightXmlImporter.parse(scene(layers), 1080f)
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val child = clips.single { it.name == "Pic" }
        val link = child.parent
        assertTrue(link != null)
        assertTrue(link!!.lockRotate)
        assertFalse(link.lockScale)
        assertTrue(r.warnings.none { it.contains("parenthelper") || it.contains("Parent Helper") })
        val adj = ParentRig(clips) { c, t -> c.renderAt(t) }.adjusted(child, 0L)
        assertEquals(child.rotationDeg, adj.rotationDeg, 0.01f)   // locked: no inherited turn
        assertTrue(kotlin.math.abs(adj.transformX - child.transformX) > 1f)   // but the position followed
    }

    @Test
    fun repeatPathIsParkedWithItsReason() {
        // khanbh-edit-cutsgrow.xml's instance (trimmed): the def carries no shader
        // and no script — the copies are host behaviour this app does not reproduce.
        val r = parse(
            """<effect id="com.alightcreative.effects.repeat.path" locallyApplied="true"><property name="count" type="float" value="28.000000" /><property name="fillColor" type="color" value="#ff2d1ef6" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        assertTrue(c.effects.isEmpty())
        assertTrue(r.warnings.any { it.contains("repeat.path") && it.contains("not reproduced yet") })
    }

    @Test
    fun liftFillMapsToCopyBackground() {
        // khanbh-op02.xml's instance is the corpus's only fill != 0 lift (0.2):
        // the def is Copy Background with fill inverted.
        val r = parse(
            """<effect id="com.alightcreative.effects.lift" locallyApplied="true"><property name="fill" type="float" value="0.200000" /></effect>"""
        )
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects
            .single { it.type == ClipFxLibrary.COPY_BG }
        assertEquals(80f, fx.values[0], 0.001f)
        assertTrue(r.warnings.none { it.contains("lift") })
    }
}
