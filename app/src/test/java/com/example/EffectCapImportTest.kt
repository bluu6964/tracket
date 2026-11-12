package com.example

import com.example.engine.AlightXmlImporter
import com.example.model.ClipFx
import com.example.model.ClipFxLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build 130 (Queue 14h): the per-layer effect cap. It was 12 — a fixed op-array size in the
 * renderer (kFxMaxOps), mirrored in [ClipFxLibrary.MAX_EFFECTS] — and it silently ate real,
 * implemented effects off the corpus's biggest stacks. The bound is 32 now: the largest stack
 * in the 93-file corpus is 29 (pinned verbatim below), and a refusal is reported once per
 * layer, naming how many effects were dropped and which ids.
 */
class EffectCapImportTest {

    private fun scene(layer: String): String =
        """<?xml version='1.0' encoding='UTF-8' ?>
<scene title="Cap" width="1080" height="1350" bgcolor="#ff000000" totalTime="60000" fps="30">
$layer
</scene>"""

    @Test
    fun boundCoversTheCorpusMaxStack() {
        // The 93-file corpus census (build 130): the largest authored stack is 29 effects on
        // one blxze-brepizx-pack layer. The bound must cover it, or the cap eats real effects.
        assertTrue(ClipFxLibrary.MAX_EFFECTS >= 29)
    }

    @Test
    fun brepizxCopy14VerbatimFullStack() {
        // The <shape> element below is verbatim from blxze-brepizx-pack.xml ("1719970923667.png
        // Copy 14", the corpus's largest stack): 70 effect elements, of which the hidden ones
        // stay skipped and 29 reach the layer. Under the old cap of 12 the last 17 were dropped
        // — both edge glows among them (build 128's accounting).
        val r = AlightXmlImporter.parse(scene(BREPIZX_LAYER), 1080f)
        val clip = r.snapshot.tracks.flatMap { it.clips }.single()
        assertEquals(29, clip.effects.size)
        val k = ClipFxLibrary
        assertEquals(
            listOf(
                k.CHROMA_KEY_AM, k.CHROMA_KEY_AM, k.CHROMA_KEY_AM, k.CHROMA_KEY_AM, k.CHROMA_KEY_AM,
                k.SOFT_GLOW_AM,
                k.CHROMA_KEY_AM, k.CHROMA_KEY_AM, k.CHROMA_KEY_AM,
                k.HUE_SAT, k.COLOR_HOT_AM, k.COLORIZE_AM, k.HIGHLIGHT_SHADOW_AM, k.COLOR_HOT_AM,
                k.LIGHT_GLOW_AM, k.CHROMA_KEY_AM, k.TURBULENT_DISPLACE3_AM, k.EDGE_GLOW_AM,
                k.HUE_SAT, k.COLOR_HOT_AM, k.EDGE_GLOW_AM, k.COLORIZE_AM, k.HIGHLIGHT_SHADOW_AM,
                k.COLOR_TEMP_AM, k.HIGHLIGHT_SHADOW_AM, k.HUE_SAT, k.EXPOSURE, k.HUE_SAT, k.HUE_SAT
            ),
            clip.effects.map { it.type }
        )
        assertFalse(r.warnings.any { it.contains("not added") })
        // The whole stack reaches the renderer: 29 ops; the EXT groups of softglow,
        // turbulentdisplace3 and the two edge glows ride behind their owners (33 groups of
        // 8 floats).
        val flat = ClipFxLibrary.toNative(clip.effects)!!
        assertEquals(33 * 8, flat.size)
        fun groupType(g: Int) = flat[g * 8]
        assertEquals(k.def(k.CHROMA_KEY_AM)!!.nativeType.toFloat(), groupType(0), 0f)
        // Group 5 = soft glow, 6 = its EXT; group 17 = turbulentdisplace3, 18 = its EXT;
        // group 19 = first edge glow, 20 = its EXT; group 23 = second edge glow; the last
        // group is the final hue/saturation.
        assertEquals(k.def(k.SOFT_GLOW_AM)!!.nativeType.toFloat(), groupType(5), 0f)
        assertEquals(k.extNativeType(k.SOFT_GLOW_AM)!!.toFloat(), groupType(6), 0f)
        assertEquals(k.def(k.TURBULENT_DISPLACE3_AM)!!.nativeType.toFloat(), groupType(17), 0f)
        assertEquals(k.extNativeType(k.TURBULENT_DISPLACE3_AM)!!.toFloat(), groupType(18), 0f)
        assertEquals(k.def(k.EDGE_GLOW_AM)!!.nativeType.toFloat(), groupType(19), 0f)
        assertEquals(k.extNativeType(k.EDGE_GLOW_AM)!!.toFloat(), groupType(20), 0f)
        assertEquals(k.def(k.EDGE_GLOW_AM)!!.nativeType.toFloat(), groupType(23), 0f)
        assertEquals(k.def(k.HUE_SAT)!!.nativeType.toFloat(), groupType(32), 0f)
    }

    @Test
    fun packV4TailEffectsImport() {
        // Verbatim <shape> from blxze-pack-v4.xml ("New_Project_13.mov", start 52900): the layer
        // build 129's tier-4 imports pushed over the old cap — its last two effects are the
        // fractal warp and the hue shift the build-129 entry names as truncated.
        val r = AlightXmlImporter.parse(scene(PACKV4_LAYER), 1080f)
        val clip = r.snapshot.tracks.flatMap { it.clips }.single()
        val k = ClipFxLibrary
        assertEquals(
            listOf(
                k.CHROMA_KEY_AM, k.COLOR_HOT_AM, k.BOX_BLUR3_AM, k.BRIGHT_CONTRAST, k.EXPOSURE,
                k.FRACTAL_WARP_AM, k.HUE_SAT, k.BOX_BLUR3_AM, k.BRIGHT_CONTRAST, k.EXPOSURE,
                k.HUE_SAT, k.HUE_SAT, k.FRACTAL_WARP_AM, k.HUE_SAT
            ),
            clip.effects.map { it.type }
        )
        assertFalse(r.warnings.any { it.contains("not added") })
    }

    @Test
    fun overCapWarningNamesCountAndIds() {
        // 34 identical blurs on one shape: the layer keeps the first 32 (AM's stack order) and
        // the parse reports ONE line for the layer naming the count and the dropped ids.
        val one = """<effect id="com.alightcreative.effects.gaussianblur" locallyApplied="true">""" +
            """<property name="strength" type="float" value="0.500000" /></effect>"""
        val layer = """<shape id="1" label="Box" startTime="0" endTime="2000" fillType="color" s=".rect">
  <transform><location value="540,675,0" /><scale value="1,1" /></transform>
  <fillColor value="#ffffffff" />
  """ + one.repeat(ClipFxLibrary.MAX_EFFECTS + 2) + """
  <property name="size" type="vec2" value="100,100" /></shape>"""
        val r = AlightXmlImporter.parse(scene(layer), 1080f)
        val clip = r.snapshot.tracks.flatMap { it.clips }.single()
        assertEquals(ClipFxLibrary.MAX_EFFECTS, clip.effects.size)
        assertTrue(clip.effects.all { it.type == ClipFxLibrary.GAUSSIAN_BLUR_AM })   // v176: gaussianblur has its own op
        val capLines = r.warnings.filter { it.contains("not added") }
        assertEquals(1, capLines.size)
        assertTrue(capLines[0].contains("2 effects are not added"))
        assertTrue(capLines[0].contains("gaussianblur, gaussianblur"))
        assertTrue(capLines[0].contains("at most " + ClipFxLibrary.MAX_EFFECTS))
    }

    companion object {
        private const val BREPIZX_LAYER = """<shape id="10065143" label="1719970923667.png Copy 14" startTime="23383" endTime="23915" fillType="media" fillImage="content://media/external/images/media/1000002751" mediaFillMode="fill" s=".rect">
    <transform>
      <location value="470.769165,705.769226,0.000000" />
    </transform>
    <effect id="com.alightcreative.effects.chromakey" locallyApplied="false">
      <property name="keyColor" type="color" value="#ff005540" />
      <property name="threshold" type="float" value="0.156400" />
      <property name="feather" type="float" value="0.050000" />
      <property name="defringe" type="bool" value="false" />
      <property name="invert" type="bool" value="false" />
    </effect>
    <effect id="com.alightcreative.effects.mirror" hidden="true" locallyApplied="false">
      <property name="type" type="int" value="0" />
      <property name="blendMode" type="int" value="0" />
      <property name="alpha" type="float" value="1.000000" />
      <property name="offset" type="float" value="0.000000" />
    </effect>
    <effect id="com.alightcreative.effects.chromakey" locallyApplied="false">
      <property name="defringe" type="bool" value="true" />
      <property name="keyColor" type="color" value="#ff1ed91e" />
      <property name="threshold" type="float" value="0.200000" />
      <property name="feather" type="float" value="0.050000" />
      <property name="invert" type="bool" value="false" />
    </effect>
    <effect id="com.alightcreative.effects.spill-cleanup" locallyApplied="false">
      <property name="strength" type="float" value="2.000000" />
    </effect>
    <effect id="com.alightcreative.effects.colortemperature" hidden="true" locallyApplied="false">
      <property name="temperature" type="float" value="6.700000" />
      <property name="strength" type="float" value="1.000000" />
    </effect>
    <effect id="com.alightcreative.effects.highlightshadow" hidden="true" locallyApplied="false">
      <property name="highlights" type="float" value="2.000000" />
      <property name="shadows" type="float" value="0.700000" />
    </effect>
    <effect id="com.alightcreative.effects.satvib" hidden="true" locallyApplied="false">
      <property name="saturation" type="float" value="-0.500000" />
      <property name="vib" type="float" value="2.000000" />
    </effect>
    <effect id="com.alightcreative.effects.exposure" hidden="true" locallyApplied="false">
      <property name="exposure" type="float" value="0.280000" />
      <property name="gamma" type="float" value="1.045000" />
      <property name="offset" type="float" value="0.000000" />
    </effect>
    <effect id="com.alightcreative.effects.colorhot" hidden="true" locallyApplied="false">
      <property name="color" type="vec3" value="229.649261,1.000000,0.000000" />
      <property name="tint" type="vec3" value="243.406693,1.000000,0.000000" />
    </effect>
    <effect id="com.alightcreative.effects.satvib" hidden="true" locallyApplied="false">
      <property name="saturation" type="float" value="1.000000" />
      <property name="vib" type="float" value="1.000000" />
    </effect>
    <effect id="com.alightcreative.effects.exposure" hidden="true" locallyApplied="false">
      <property name="exposure" type="float" value="0.800000" />
      <property name="gamma" type="float" value="1.000000" />
      <property name="offset" type="float" value="0.000000" />
    </effect>
    <effect id="com.alightcreative.effects.chromakey" locallyApplied="false">
      <property name="defringe" type="bool" value="true" />
      <property name="keyColor" type="color" value="#ff00b140" />
      <property name="threshold" type="float" value="0.200000" />
      <property name="feather" type="float" value="0.050000" />
      <property name="invert" type="bool" value="false" />
    </effect>
    <effect id="com.alightcreative.effects.chromakey" locallyApplied="false">
      <property name="defringe" type="bool" value="true" />
      <property name="keyColor" type="color" value="#ff00b140" />
      <property name="threshold" type="float" value="0.200000" />
      <property name="feather" type="float" value="0.050000" />
      <property name="invert" type="bool" value="false" />
    </effect>
    <effect id="com.alightcreative.effects.chromakey" locallyApplied="false">
      <property name="defringe" type="bool" value="true" />
      <property name="keyColor" type="color" value="#ff00b140" />
      <property name="threshold" type="float" value="0.200000" />
      <property name="feather" type="float" value="0.050000" />
      <property name="invert" type="bool" value="false" />
    </effect>
    <effect id="com.alightcreative.effects.threshold" hidden="true" locallyApplied="false">
      <property name="threshold" type="float" value="0.710000" />
      <property name="feather" type="float" value="0.000000" />
      <property name="invert" type="bool" value="false" />
      <property name="blendMode" type="int" value="0" />
    </effect>
    <effect id="com.alightcreative.spectralmap" hidden="true" locallyApplied="false">
      <property name="blueColor" type="color" value="#ff000000" />
      <property name="greenColor" type="color" value="#ff000000" />
      <property name="redColor" type="color" value="#ffffffff" />
      <property name="saturation" type="float" value="1.000000" />
      <property name="scaleBrightness" type="bool" value="true" />
      <property name="phase" type="float" value="0.000000" />
      <property name="repeat" type="float" value="1.000000" />
      <property name="alpha" type="float" value="1.000000" />
      <property name="squeeze" type="float" value="0.000000" />
      <property name="reverse" type="bool" value="false" />
    </effect>
    <effect id="com.alightcreative.gradientoverlay2" locallyApplied="false">
      <property name="blendMode" type="int" value="1" />
      <property name="color1" type="color" value="#ffffffff" />
      <property name="color2" type="color" value="#ff000000" />
      <property name="angle" type="float" value="0.000000" />
      <property name="scale" type="float" value="1.000000" />
      <property name="alpha" type="float" value="0.750000" />
      <property name="gradType" type="int" value="0" />
    </effect>
    <effect id="com.alightcreative.effects.exposure" hidden="true" locallyApplied="false">
      <property name="exposure" type="float" value="0.920000" />
      <property name="gamma" type="float" value="1.000000" />
      <property name="offset" type="float" value="0.000000" />
    </effect>
    <effect id="com.alightcreative.effects.colortune2" hidden="true" locallyApplied="false">
      <property name="gain" type="vec3" value="0.000000,0.000000,-0.103778" />
      <property name="gamma" type="vec3" value="0.000000,0.000000,-0.340889" />
      <property name="lift" type="vec3" value="77.693146,0.385753,-0.678667" />
      <property name="offset" type="vec3" value="0.000000,0.000000,-0.123556" />
    </effect>
    <effect id="com.alightcreative.effects.softglow" locallyApplied="false">
      <property name="strength" type="float" value="0.100000" />
      <property name="brightness" type="float" value="1.000000" />
      <property name="contrast" type="float" value="0.200000" />
      <property name="highlights" type="float" value="0.500000" />
      <property name="color" type="color" value="#ffff5566" />
      <property name="blend" type="float" value="0.250000" />
      <property name="alpha" type="float" value="1.000000" />
      <property name="outsideAlpha" type="float" value="0.000000" />
    </effect>
    <effect id="com.alightcreative.effects.chromakey" locallyApplied="false">
      <property name="defringe" type="bool" value="true" />
      <property name="keyColor" type="color" value="#ff00b140" />
      <property name="threshold" type="float" value="0.200000" />
      <property name="feather" type="float" value="0.050000" />
      <property name="invert" type="bool" value="false" />
    </effect>
    <effect id="com.alightcreative.effects.chromakey" locallyApplied="false">
      <property name="defringe" type="bool" value="true" />
      <property name="keyColor" type="color" value="#ff00b140" />
      <property name="threshold" type="float" value="0.200000" />
      <property name="feather" type="float" value="0.050000" />
      <property name="invert" type="bool" value="false" />
    </effect>
    <effect id="com.alightcreative.effects.chromakey" locallyApplied="false">
      <property name="defringe" type="bool" value="true" />
      <property name="keyColor" type="color" value="#ff00b140" />
      <property name="threshold" type="float" value="0.200000" />
      <property name="feather" type="float" value="0.050000" />
      <property name="invert" type="bool" value="false" />
    </effect>
    <effect id="com.alightcreative.spectralmap" hidden="true" locallyApplied="false">
      <property name="blueColor" type="color" value="#ff000000" />
      <property name="greenColor" type="color" value="#ff000000" />
      <property name="redColor" type="color" value="#ffffffff" />
      <property name="saturation" type="float" value="1.000000" />
      <property name="scaleBrightness" type="bool" value="true" />
      <property name="phase" type="float" value="0.000000" />
      <property name="repeat" type="float" value="1.000000" />
      <property name="alpha" type="float" value="1.000000" />
      <property name="squeeze" type="float" value="0.000000" />
      <property name="reverse" type="bool" value="false" />
    </effect>
    <effect id="com.alightcreative.gradientoverlay2" hidden="true" locallyApplied="false">
      <property name="blendMode" type="int" value="1" />
      <property name="color1" type="color" value="#ffffffff" />
      <property name="color2" type="color" value="#ff000000" />
      <property name="angle" type="float" value="0.000000" />
      <property name="scale" type="float" value="1.000000" />
      <property name="alpha" type="float" value="0.750000" />
      <property name="gradType" type="int" value="0" />
    </effect>
    <effect id="com.alightcreative.effects.exposure" hidden="true" locallyApplied="false">
      <property name="exposure" type="float" value="0.920000" />
      <property name="gamma" type="float" value="1.000000" />
      <property name="offset" type="float" value="0.000000" />
    </effect>
    <effect id="com.alightcreative.effects.colortune2" hidden="true" locallyApplied="false">
      <property name="gain" type="vec3" value="0.000000,0.000000,-0.103778" />
      <property name="gamma" type="vec3" value="0.000000,0.000000,-0.340889" />
      <property name="lift" type="vec3" value="77.693146,0.385753,-0.678667" />
      <property name="offset" type="vec3" value="0.000000,0.000000,-0.123556" />
    </effect>
    <effect id="com.alightcreative.effects.softglow" hidden="true" locallyApplied="false">
      <property name="strength" type="float" value="0.100000" />
      <property name="brightness" type="float" value="1.000000" />
      <property name="contrast" type="float" value="0.200000" />
      <property name="highlights" type="float" value="0.500000" />
      <property name="color" type="color" value="#ffff5566" />
      <property name="blend" type="float" value="0.250000" />
      <property name="alpha" type="float" value="1.000000" />
      <property name="outsideAlpha" type="float" value="0.000000" />
    </effect>
    <effect id="com.alightcreative.effects.chromakey" hidden="true" locallyApplied="false">
      <property name="defringe" type="bool" value="true" />
      <property name="keyColor" type="color" value="#ff00b140" />
      <property name="threshold" type="float" value="0.200000" />
      <property name="feather" type="float" value="0.050000" />
      <property name="invert" type="bool" value="false" />
    </effect>
    <effect id="com.alightcreative.effects.chromakey" hidden="true" locallyApplied="false">
      <property name="defringe" type="bool" value="true" />
      <property name="keyColor" type="color" value="#ff00b140" />
      <property name="threshold" type="float" value="0.200000" />
      <property name="feather" type="float" value="0.050000" />
      <property name="invert" type="bool" value="false" />
    </effect>
    <effect id="com.alightcreative.effects.threshold" hidden="true" locallyApplied="false">
      <property name="threshold" type="float" value="0.710000" />
      <property name="feather" type="float" value="0.000000" />
      <property name="invert" type="bool" value="false" />
      <property name="blendMode" type="int" value="0" />
    </effect>
    <effect id="com.alightcreative.spectralmap" hidden="true" locallyApplied="false">
      <property name="blueColor" type="color" value="#ff000000" />
      <property name="greenColor" type="color" value="#ff000000" />
      <property name="redColor" type="color" value="#ffffffff" />
      <property name="saturation" type="float" value="1.000000" />
      <property name="scaleBrightness" type="bool" value="true" />
      <property name="phase" type="float" value="0.000000" />
      <property name="repeat" type="float" value="1.000000" />
      <property name="alpha" type="float" value="1.000000" />
      <property name="squeeze" type="float" value="0.000000" />
      <property name="reverse" type="bool" value="false" />
    </effect>
    <effect id="com.alightcreative.gradientoverlay2" hidden="true" locallyApplied="false">
      <property name="blendMode" type="int" value="1" />
      <property name="color1" type="color" value="#ffffffff" />
      <property name="color2" type="color" value="#ff000000" />
      <property name="angle" type="float" value="0.000000" />
      <property name="scale" type="float" value="1.000000" />
      <property name="alpha" type="float" value="0.750000" />
      <property name="gradType" type="int" value="0" />
    </effect>
    <effect id="com.alightcreative.effects.exposure" hidden="true" locallyApplied="false">
      <property name="exposure" type="float" value="0.920000" />
      <property name="gamma" type="float" value="1.000000" />
      <property name="offset" type="float" value="0.000000" />
    </effect>
    <effect id="com.alightcreative.effects.colortune2" hidden="true" locallyApplied="false">
      <property name="gain" type="vec3" value="0.000000,0.000000,-0.103778" />
      <property name="gamma" type="vec3" value="0.000000,0.000000,-0.340889" />
      <property name="lift" type="vec3" value="77.693146,0.385753,-0.678667" />
      <property name="offset" type="vec3" value="0.000000,0.000000,-0.123556" />
    </effect>
    <effect id="com.alightcreative.effects.softglow" hidden="true" locallyApplied="false">
      <property name="strength" type="float" value="0.100000" />
      <property name="brightness" type="float" value="1.000000" />
      <property name="contrast" type="float" value="0.200000" />
      <property name="highlights" type="float" value="0.500000" />
      <property name="color" type="color" value="#ffff5566" />
      <property name="blend" type="float" value="0.250000" />
      <property name="alpha" type="float" value="1.000000" />
      <property name="outsideAlpha" type="float" value="0.000000" />
    </effect>
    <effect id="com.alightcreative.effects.chromakey" hidden="true" locallyApplied="false">
      <property name="defringe" type="bool" value="true" />
      <property name="keyColor" type="color" value="#ff00b140" />
      <property name="threshold" type="float" value="0.200000" />
      <property name="feather" type="float" value="0.050000" />
      <property name="invert" type="bool" value="false" />
    </effect>
    <effect id="com.alightcreative.effects.chromakey" hidden="true" locallyApplied="false">
      <property name="defringe" type="bool" value="true" />
      <property name="keyColor" type="color" value="#ff00b140" />
      <property name="threshold" type="float" value="0.200000" />
      <property name="feather" type="float" value="0.050000" />
      <property name="invert" type="bool" value="false" />
    </effect>
    <effect id="com.alightcreative.effects.chromakey" hidden="true" locallyApplied="false">
      <property name="defringe" type="bool" value="true" />
      <property name="keyColor" type="color" value="#ff00b140" />
      <property name="threshold" type="float" value="0.200000" />
      <property name="feather" type="float" value="0.050000" />
      <property name="invert" type="bool" value="false" />
    </effect>
    <effect id="com.alightcreative.effects.turbulentdisplace3" hidden="true" locallyApplied="false">
      <property name="FIXTURB" type="bool" value="false" />
      <property name="direction" type="int" value="2" />
      <property name="evolution" type="float">
        <kf t="-8.641827" v="0.000000" />
        <kf t="377.266815" v="1.200000" />
        <kf t="174.024033" v="3.000000" />
      </property>
      <property name="intensity" type="float" value="0.085000" />
      <property name="octaves" type="float" value="1.000000" />
      <property name="offs" type="float" value="0.100000" />
      <property name="scale" type="float" value="0.040000" />
      <property name="screenSpace" type="bool" value="true" />
      <property name="stretch" type="vec2" value="1.000000,1.000000" />
      <property name="seed" type="float" value="0.000000" />
      <property name="singleAxis" type="bool" value="true" />
    </effect>
    <effect id="com.alightcreative.effects.edgeglow" hidden="true" locallyApplied="false">
      <property name="brightness" type="float" value="0.150000" />
      <property name="fillColor" type="color" value="#ffff0000" />
      <property name="smoothing" type="float" value="9.000000" />
      <property name="spread" type="float" value="2.220000" />
      <property name="strength" type="float" value="0.210000" />
      <property name="tint" type="float" value="1.000000" />
      <property name="tintColor" type="color" value="#ffffffff" />
      <property name="threshold" type="float" value="0.700000" />
      <property name="invert" type="bool" value="false" />
      <property name="blend" type="float" value="0.000000" />
      <property name="gamma" type="float" value="1.000000" />
    </effect>
    <effect id="com.alightcreative.effects.satvib" locallyApplied="false">
      <property name="saturation" type="float" value="1.000000" />
      <property name="vib" type="float" value="1.000000" />
    </effect>
    <effect id="com.alightcreative.effects.colorhot" locallyApplied="false">
      <property name="color" type="vec3" value="358.431732,1.000000,0.000000" />
      <property name="tint" type="vec3" value="0.156622,1.000000,0.006000" />
    </effect>
    <effect id="com.alightcreative.effects.edgeglow" hidden="true" locallyApplied="false">
      <property name="brightness" type="float" value="0.240000" />
      <property name="smoothing" type="float" value="9.490000" />
      <property name="spread" type="float" value="2.390000" />
      <property name="strength" type="float" value="1.750000" />
      <property name="tint" type="float" value="1.000000" />
      <property name="tintColor" type="color" value="#ffffffff" />
      <property name="threshold" type="float" value="0.700000" />
      <property name="invert" type="bool" value="false" />
      <property name="fillColor" type="color" value="#ff0088ff" />
      <property name="blend" type="float" value="0.000000" />
      <property name="gamma" type="float" value="1.000000" />
    </effect>
    <effect id="com.alightcreative.effects.colorize" locallyApplied="false">
      <property name="tint" type="vec3" value="238.095428,1.000000,0.000000" />
    </effect>
    <effect id="com.alightcreative.effects.highlightshadow" hidden="true" locallyApplied="false">
      <property name="highlights" type="float" value="2.000000" />
      <property name="shadows" type="float" value="1.020000" />
    </effect>
    <effect id="com.alightcreative.gradientoverlay2" locallyApplied="false">
      <property name="alpha" type="float" value="0.463000" />
      <property name="blendMode" type="int" value="1" />
      <property name="color1" type="color" value="#ffffffff" />
      <property name="color2" type="color" value="#ffbebebe" />
      <property name="gradType" type="int" value="1" />
      <property name="scale" type="float" value="1.250000" />
      <property name="angle" type="float" value="0.000000" />
    </effect>
    <effect id="com.alightcreative.effects.colortemperature" hidden="true" locallyApplied="false">
      <property name="temperature" type="float" value="6.700000" />
      <property name="strength" type="float" value="1.000000" />
    </effect>
    <effect id="com.alightcreative.effects.highlightshadow" locallyApplied="false">
      <property name="highlights" type="float" value="2.000000" />
      <property name="shadows" type="float" value="0.700000" />
    </effect>
    <effect id="com.alightcreative.effects.satvib" hidden="true" locallyApplied="false">
      <property name="saturation" type="float" value="-0.500000" />
      <property name="vib" type="float" value="2.000000" />
    </effect>
    <effect id="com.alightcreative.effects.exposure" hidden="true" locallyApplied="false">
      <property name="exposure" type="float" value="0.280000" />
      <property name="gamma" type="float" value="1.045000" />
      <property name="offset" type="float" value="0.000000" />
    </effect>
    <effect id="com.alightcreative.effects.colorhot" locallyApplied="false">
      <property name="color" type="vec3" value="271.957611,0.145735,0.000000" />
      <property name="tint" type="vec3" value="254.926483,0.017474,0.000000" />
    </effect>
    <effect id="com.alightcreative.effects.satvib" hidden="true" locallyApplied="false">
      <property name="saturation" type="float" value="1.000000" />
      <property name="vib" type="float" value="1.000000" />
    </effect>
    <effect id="com.alightcreative.effects.exposure" hidden="true" locallyApplied="false">
      <property name="exposure" type="float" value="0.800000" />
      <property name="gamma" type="float" value="1.000000" />
      <property name="offset" type="float" value="0.000000" />
    </effect>
    <effect id="com.alightcreative.effects.lightglow" locallyApplied="false">
      <property name="blend" type="float" value="1.000000" />
      <property name="color" type="color" value="#ff4490dd" />
      <property name="intensity" type="float" value="1.140000" />
      <property name="strength" type="float" value="0.770000" />
      <property name="threshold" type="float" value="0.700000" />
      <property name="halo" type="float" value="0.000000" />
      <property name="alpha" type="float" value="0.750000" />
    </effect>
    <effect id="com.alightcreative.effects.chromakey" locallyApplied="false">
      <property name="defringe" type="bool" value="true" />
      <property name="keyColor" type="color" value="#ff00b140" />
      <property name="threshold" type="float" value="0.200000" />
      <property name="feather" type="float" value="0.050000" />
      <property name="invert" type="bool" value="false" />
    </effect>
    <effect id="com.alightcreative.effects.turbulentdisplace3" locallyApplied="false">
      <property name="FIXTURB" type="bool" value="false" />
      <property name="evolution" type="float">
        <kf t="0.000000" v="0.000000" />
        <kf t="2.675481" v="0.840000" />
      </property>
      <property name="intensity" type="float" value="0.087000" />
      <property name="octaves" type="float" value="2.000000" />
      <property name="offs" type="float" value="0.019000" />
      <property name="parr" type="vec2">
        <kf t="0.000000" v="17.701681,-254.000000" />
        <kf t="2.701923" v="-716.000000,280.814758" />
      </property>
      <property name="position" type="vec2">
        <kf t="2.675481" v="0.000000,0.000000" />
        <kf t="0.014423" v="0.000000,150.000000" />
      </property>
      <property name="scale" type="float" value="4.000000" />
      <property name="stretch" type="vec2" value="0.020000,0.010000" />
      <property name="direction" type="int" value="4" />
      <property name="seed" type="float" value="0.000000" />
      <property name="singleAxis" type="bool" value="true" />
      <property name="screenSpace" type="bool" value="false" />
    </effect>
    <effect id="com.alightcreative.effects.edgeglow" locallyApplied="false">
      <property name="brightness" type="float" value="0.150000" />
      <property name="fillColor" type="color" value="#ffff0000" />
      <property name="smoothing" type="float" value="10.000000" />
      <property name="spread" type="float" value="2.220000" />
      <property name="strength" type="float" value="0.210000" />
      <property name="tint" type="float" value="1.000000" />
      <property name="tintColor" type="color" value="#ffffffff" />
      <property name="threshold" type="float" value="0.700000" />
      <property name="invert" type="bool" value="false" />
      <property name="blend" type="float" value="0.000000" />
      <property name="gamma" type="float" value="1.000000" />
    </effect>
    <effect id="com.alightcreative.effects.satvib" locallyApplied="false">
      <property name="saturation" type="float" value="1.000000" />
      <property name="vib" type="float" value="1.000000" />
    </effect>
    <effect id="com.alightcreative.effects.colorhot" locallyApplied="false">
      <property name="color" type="vec3" value="358.431732,1.000000,0.000000" />
      <property name="tint" type="vec3" value="0.156622,1.000000,0.006000" />
    </effect>
    <effect id="com.alightcreative.effects.edgeglow" locallyApplied="false">
      <property name="brightness" type="float" value="0.240000" />
      <property name="smoothing" type="float" value="9.490000" />
      <property name="spread" type="float" value="2.390000" />
      <property name="strength" type="float" value="1.750000" />
      <property name="tint" type="float" value="1.000000" />
      <property name="tintColor" type="color" value="#ffffffff" />
      <property name="threshold" type="float" value="0.700000" />
      <property name="invert" type="bool" value="false" />
      <property name="fillColor" type="color" value="#ff0088ff" />
      <property name="blend" type="float" value="0.000000" />
      <property name="gamma" type="float" value="1.000000" />
    </effect>
    <effect id="com.alightcreative.effects.colorize" locallyApplied="false">
      <property name="tint" type="vec3" value="192.237305,0.525621,0.000000" />
    </effect>
    <effect id="com.alightcreative.gradientoverlay2" locallyApplied="false">
      <property name="alpha" type="float" value="0.363000" />
      <property name="angle" type="float" value="70.000000" />
      <property name="blendMode" type="int" value="1" />
      <property name="color1" type="color" value="#ffffffff" />
      <property name="color2" type="color" value="#ff000000" />
      <property name="gradType" type="int" value="1" />
      <property name="offset" type="vec2" value="116.000000,0.000000" />
      <property name="scale" type="float" value="0.670000" />
    </effect>
    <effect id="com.alightcreative.effects.highlightshadow" locallyApplied="false">
      <property name="highlights" type="float" value="2.000000" />
      <property name="shadows" type="float" value="1.020000" />
    </effect>
    <effect id="com.alightcreative.effects.colortemperature" locallyApplied="false">
      <property name="temperature" type="float" value="1.000000" />
      <property name="strength" type="float" value="1.000000" />
    </effect>
    <effect id="com.alightcreative.effects.highlightshadow" locallyApplied="false">
      <property name="highlights" type="float" value="2.000000" />
      <property name="shadows" type="float" value="0.700000" />
    </effect>
    <effect id="com.alightcreative.effects.satvib" locallyApplied="false">
      <property name="saturation" type="float" value="-0.500000" />
      <property name="vib" type="float" value="2.000000" />
    </effect>
    <effect id="com.alightcreative.effects.exposure" locallyApplied="false">
      <property name="exposure" type="float" value="0.280000" />
      <property name="gamma" type="float" value="1.045000" />
      <property name="offset" type="float" value="0.000000" />
    </effect>
    <effect id="com.alightcreative.effects.satvib" locallyApplied="false">
      <property name="saturation" type="float" value="1.000000" />
      <property name="vib" type="float" value="1.000000" />
    </effect>
    <effect id="com.alightcreative.effects.hueshift" locallyApplied="false">
      <property name="hue" type="float" value="174.000000" />
    </effect>
    <property name="size" type="vec2" value="501.500000,675.000000" />
  </shape>
  """

        private const val PACKV4_LAYER = """<shape id="10095049" label="New_Project_13.mov" startTime="52900" endTime="53449" fillType="media" fillVideo="am-internal:///BB76A2197842C4F0746777F6092C7DA1124773DE.mov" inTime="550" mediaFillMode="fill" s=".rect">
    <transform>
      <location value="540.000000,675.000000,0.000000" />
    </transform>
    <effect id="com.alightcreative.effects.chromakey" locallyApplied="true">
      <property name="threshold" type="float" value="0.534000" />
    </effect>
    <effect id="com.alightcreative.effects.colorhot" locallyApplied="false">
      <property name="tint" type="vec3" value="-0.058268,1.000000,0.000000" />
    </effect>
    <effect id="com.alightcreative.effects.boxblur3" locallyApplied="false">
      <property name="strength" type="float" value="0.289000" />
    </effect>
    <effect id="com.alightcreative.effects.brightcont2" locallyApplied="false" />
    <effect id="com.alightcreative.effects.exposure" locallyApplied="false">
      <property name="exposure" type="float" value="0.840000" />
    </effect>
    <effect id="com.alightcreative.effects.fractalwarp4" locallyApplied="false">
      <property name="mag" type="float" value="0.020000" />
      <property name="offs" type="vec2">
        <kf t="0.000000" v="31.000000,0.000000" />
        <kf t="1.000000" v="1056.000000,0.000000" />
      </property>
      <property name="parr" type="vec2">
        <kf t="0.000000" v="36.000000,0.000000" />
        <kf t="1.111235" v="1021.000000,0.000000" />
      </property>
    </effect>
    <effect id="com.alightcreative.effects.hueshift" locallyApplied="false">
      <property name="hue" type="float">
        <kf t="0.000000" v="0.000000" />
        <kf t="1.111235" v="556.500000" />
      </property>
    </effect>
    <effect id="com.alightcreative.effects.boxblur3" locallyApplied="false">
      <property name="strength" type="float" value="0.200000" />
    </effect>
    <effect id="com.alightcreative.effects.brightcont2" locallyApplied="false" />
    <effect id="com.alightcreative.effects.exposure" locallyApplied="false">
      <property name="exposure" type="float" value="0.840000" />
    </effect>
    <effect id="com.alightcreative.effects.satvib" locallyApplied="false">
      <property name="saturation" type="float" value="1.000000" />
    </effect>
    <effect id="com.alightcreative.effects.satvib" locallyApplied="false">
      <property name="saturation" type="float" value="1.000000" />
    </effect>
    <effect id="com.alightcreative.effects.fractalwarp4" locallyApplied="false">
      <property name="octaves" type="float" value="4.000000" />
      <property name="offs" type="vec2">
        <kf t="0.000000" v="31.000000,0.000000" />
        <kf t="1.111235" v="1056.000000,0.000000" />
      </property>
      <property name="parr" type="vec2">
        <kf t="0.000000" v="36.000000,0.000000" />
        <kf t="1.111235" v="1021.000000,0.000000" />
      </property>
    </effect>
    <effect id="com.alightcreative.effects.hueshift" locallyApplied="false">
      <property name="hue" type="float">
        <kf t="0.000000" v="0.000000" />
        <kf t="1.111235" v="556.500000" />
      </property>
    </effect>
    <property name="size" type="vec2" value="540.000000,675.000000" />
  </shape>
  """
    }
}
