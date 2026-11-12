package com.example

import com.example.engine.AlightXmlImporter
import com.example.model.ClipFxLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build 128: Alight's `edgeglow` imports — the faithful port of its def's pass chain
 * (findedges-edgeglow -> the host gaussianblur at strength -> the def's composite),
 * replacing the build-≤127 Glow approximation. The effect elements below are verbatim
 * from the corpus files named in each test (80 instances over 9 files; none keyframed).
 * Slot layout: see ClipFxLibrary.EDGE_GLOW_AM.
 */
class EdgeGlowImportTest {

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

    private fun onlyFx(fx: String) =
        parse(fx).snapshot.tracks.flatMap { it.clips }[0].effects
            .single { it.type == ClipFxLibrary.EDGE_GLOW_AM }

    @Test
    fun edgeGlowInvertInstanceVerbatim() {
        // blxze-brepizx-pack.xml, "1719970923667.png Copy 18": the corpus's only
        // invert=true instance (smoothing at the 10 end of the range).
        val fx = onlyFx(
            """<effect id="com.alightcreative.effects.edgeglow" locallyApplied="true"><property name="smoothing" type="float" value="10.000000" /><property name="threshold" type="float" value="0.700000" /><property name="invert" type="bool" value="true" /><property name="strength" type="float" value="0.220000" /><property name="spread" type="float" value="2.020000" /><property name="fillColor" type="color" value="#ff0088ff" /><property name="blend" type="float" value="0.000000" /><property name="gamma" type="float" value="1.000000" /><property name="brightness" type="float" value="0.160000" /><property name="tintColor" type="color" value="#ffffffff" /><property name="tint" type="float" value="1.000000" /></effect>"""
        )
        assertEquals(10f, fx.values[0], 0.001f)
        assertEquals(0.7f, fx.values[1], 0.001f)
        assertEquals(1f, fx.values[2], 0.001f)       // invert
        assertEquals(0.22f, fx.values[3], 0.001f)
        assertEquals(2.02f, fx.values[4], 0.001f)
        assertEquals(0x0088FF.toFloat(), fx.values[5], 0.01f)
        assertEquals(0f, fx.values[6], 0.001f)
        assertEquals(1f, fx.values[7], 0.001f)       // gamma
        assertEquals(0.16f, fx.values[8], 0.001f)    // brightness
        assertEquals(0xFFFFFF.toFloat(), fx.values[9], 0.01f)
        assertEquals(1f, fx.values[10], 0.001f)      // tint (white: visually neutral)
    }

    @Test
    fun edgeGlowStrengthTwoInstanceVerbatim() {
        // blxze-brepizx-pack.xml: the corpus's strongest blur (strength 2.0) with the
        // #ffdd44dd fill colour.
        val fx = onlyFx(
            """<effect id="com.alightcreative.effects.edgeglow" locallyApplied="true"><property name="smoothing" type="float" value="9.490000" /><property name="threshold" type="float" value="0.700000" /><property name="invert" type="bool" value="false" /><property name="strength" type="float" value="2.000000" /><property name="spread" type="float" value="1.190000" /><property name="fillColor" type="color" value="#ffdd44dd" /><property name="blend" type="float" value="0.000000" /><property name="gamma" type="float" value="1.000000" /><property name="brightness" type="float" value="0.000000" /><property name="tintColor" type="color" value="#ffffffff" /><property name="tint" type="float" value="1.000000" /></effect>"""
        )
        assertEquals(9.49f, fx.values[0], 0.001f)
        assertEquals(2f, fx.values[3], 0.001f)
        assertEquals(1.19f, fx.values[4], 0.001f)
        assertEquals(0xDD44DD.toFloat(), fx.values[5], 0.01f)
        assertEquals(1f, fx.values[10], 0.001f)
    }

    @Test
    fun edgeGlowGammaAndBlendInstanceVerbatim() {
        // blxze-new-project-38.xml: the corpus's only blend != 0 instance (0.54), also
        // carrying gamma 1.15; most params absent -> def defaults.
        val fx = onlyFx(
            """<effect id="com.alightcreative.effects.edgeglow" locallyApplied="true"><property name="threshold" type="float" value="0.140000" /><property name="blend" type="float" value="0.540000" /><property name="gamma" type="float" value="1.150000" /><property name="smoothing" type="float" value="2.800000" /><property name="spread" type="float" value="0.810000" /></effect>"""
        )
        assertEquals(2.8f, fx.values[0], 0.001f)
        assertEquals(0.14f, fx.values[1], 0.001f)
        assertEquals(0.1f, fx.values[3], 0.001f)     // strength: the def's default
        assertEquals(0.81f, fx.values[4], 0.001f)
        assertEquals(0x0088FF.toFloat(), fx.values[5], 0.01f)   // fillColor: the def's default
        assertEquals(0.54f, fx.values[6], 0.001f)
        assertEquals(1.15f, fx.values[7], 0.001f)
    }

    @Test
    fun edgeGlowBareInstanceTakesDefDefaults() {
        val r = parse("""<effect id="com.alightcreative.effects.edgeglow" locallyApplied="true"></effect>""")
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects
            .single { it.type == ClipFxLibrary.EDGE_GLOW_AM }
        assertEquals(
            listOf(5f, 0.7f, 0f, 0.1f, 1.5f, 0x0088FF.toFloat(), 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f),
            fx.values
        )
        assertTrue(r.warnings.none { it.contains("edgeglow") })
    }

    @Test
    fun edgeGlowKeyframedParamTakesFirstValueWithNote() {
        // No corpus instance keyframes an edgeglow param; if one ever does, the first
        // value is used with the standard note (no AmFxEval tail — the def's params
        // are not on the tracked list).
        val r = parse(
            """<effect id="com.alightcreative.effects.edgeglow" locallyApplied="true"><property name="threshold" type="float"><kf t="0.000000" v="0.200000" /><kf t="0.500000" v="0.900000" /></property></effect>"""
        )
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects
            .single { it.type == ClipFxLibrary.EDGE_GLOW_AM }
        assertEquals(0.2f, fx.values[1], 0.001f)
        assertTrue(r.warnings.any { it.contains("keyframed threshold is not supported yet") })
    }

    @Test
    fun edgeGlowPanelCreateCarriesAmDefaults() {
        val fx = ClipFxLibrary.create(ClipFxLibrary.EDGE_GLOW_AM)!!
        assertEquals(14, fx.values.size)   // 7 params + the 7 EXT values
        assertEquals(5f, fx.values[0], 0f)
        assertEquals(0.7f, fx.values[1], 0f)
        assertEquals(0.1f, fx.values[3], 0f)
        assertEquals(0x0088FF.toFloat(), fx.values[5], 0f)
        assertEquals(1f, fx.values[7], 0f)           // gamma, from extraValues
        val flat = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(16, flat.size)
        assertEquals(129f, flat[0], 0f)
        assertEquals(130f, flat[8], 0f)
    }
}
