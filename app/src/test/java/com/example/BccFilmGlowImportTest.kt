package com.example

import com.example.engine.AlightXmlImporter
import com.example.model.ClipFxLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build 150 (v181): bccfilmglow, un-parked by the fx op model's EXT3 / EXT4
 * banks — the last effect in the 105-file corpus with a staged definition.
 * The instance below is the corpus's only one (omniframe-hardtrekk-hero.xml),
 * verbatim; it is fully static, so no AmFxEval tail rides it.
 */
class BccFilmGlowImportTest {

    private fun scene(layers: String): String =
        """<?xml version='1.0' encoding='UTF-8' ?>
<scene title="BCC" width="1080" height="1920" bgcolor="#ff102030" totalTime="2000" fps="30">
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

    @Test
    fun bccFilmGlowVerbatim() {
        val r = parse(
            """<effect id="com.alightcreative.effects.bccfilmglow" locallyApplied="true"><property name="glowAmount" type="float" value="0.010000" /><property name="glowRadius" type="float" value="80.000000" /><property name="glowSoftness" type="float" value="0.730000" /><property name="glowSpread" type="float" value="2.500000" /><property name="threshold" type="float" value="0.500000" /><property name="softKnee" type="float" value="0.300000" /><property name="threshGamma" type="float" value="1.000000" /><property name="hlGlowStr" type="float" value="0.500000" /><property name="hlThreshold" type="float" value="0.700000" /><property name="hlSaturation" type="float" value="0.800000" /><property name="innerWeight" type="float" value="1.000000" /><property name="outerWeight" type="float" value="0.500000" /><property name="tailWeight" type="float" value="1.000000" /><property name="glowTint" type="color" value="#00ffffff" /><property name="glowTintStr" type="float" value="0.000000" /><property name="glowWarmth" type="float" value="0.000000" /><property name="glowSatBoost" type="float" value="1.000000" /><property name="colorPreserve" type="float" value="0.700000" /><property name="halStr" type="float" value="0.000000" /><property name="halColor" type="color" value="#00ff2200" /><property name="diffAmount" type="float" value="0.000000" /><property name="diffHighlights" type="float" value="0.300000" /><property name="filmGrain" type="float" value="0.000000" /><property name="filmFade" type="float" value="0.000000" /><property name="filmSaturation" type="float" value="1.000000" /><property name="filmContrast" type="float" value="0.000000" /><property name="filmWarmth" type="float" value="0.000000" /><property name="softClipKnee" type="float" value="5.000000" /><property name="blendMode" type="int" value="1" /><property name="quality" type="int" value="2" /><property name="glowOnly" type="bool" value="false" /><property name="ditherAmt" type="float" value="0.008000" /><property name="globalMix" type="float" value="1.000000" /></effect>"""
        )
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects.single { it.type == ClipFxLibrary.BCC_FILM_GLOW_AM }
        // The five-bank slot map (fx_plan.h): the two colours pack as 0xRRGGBB
        // (the def reads .rgb only; the instance serialises a 0x00 alpha byte).
        assertEquals(
            listOf(
                0.01f, 80f, 0.73f, 2.5f, 0.5f, 0.3f, 1f,
                0.5f, 0.7f, 0.8f, 1f, 0.5f, 1f, 0xFFFFFF.toFloat(),
                0f, 0f, 1f, 0.7f, 0f, 0xFF2200.toFloat(), 0f,
                0.3f, 0f, 0f, 1f, 0f, 0f, 5f,
                1f, 2f, 0f, 0.008f, 1f, 0f, 0f
            ),
            fx.values
        )
        assertTrue(r.warnings.none { it.contains("bccfilmglow") })
    }

    @Test
    fun bccFilmGlowNativeGroups() {
        val r = parse(
            """<effect id="com.alightcreative.effects.bccfilmglow" locallyApplied="true"><property name="glowAmount" type="float" value="0.010000" /></effect>"""
        )
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects.single { it.type == ClipFxLibrary.BCC_FILM_GLOW_AM }
        val flat = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(40, flat.size)   // five groups of 8
        assertEquals(215f, flat[0], 0f)
        assertEquals(216f, flat[8], 0f)    // EXT
        assertEquals(217f, flat[16], 0f)   // EXT2
        assertEquals(218f, flat[24], 0f)   // EXT3
        assertEquals(219f, flat[32], 0f)   // EXT4
        assertEquals(1f, flat[33], 0f)     // blendMode Add rides EXT4
        assertEquals(2f, flat[34], 0f)     // quality
        assertEquals(1f, flat[37], 0f)     // globalMix
    }

    @Test
    fun bccFilmGlowCreateDefaults() {
        val g = ClipFxLibrary.create(ClipFxLibrary.BCC_FILM_GLOW_AM)!!
        assertEquals(35, g.values.size)   // 7 panel params + 28 EXT..EXT4 defaults
        assertEquals(listOf(0.5f, 20f, 0.5f, 2.5f, 0.5f, 0.3f, 1f), g.values.take(7))
        assertEquals(ClipFxLibrary.extraValues(ClipFxLibrary.BCC_FILM_GLOW_AM), g.values.drop(7))
        val flat = ClipFxLibrary.toNative(listOf(g))!!
        assertEquals(40, flat.size)
        assertEquals(215f, flat[0], 0f)
        assertEquals(219f, flat[32], 0f)
    }
}
