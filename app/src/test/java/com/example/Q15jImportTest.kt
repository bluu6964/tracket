package com.example

import com.example.engine.AlightXmlImporter
import com.example.engine.AmFxEval
import com.example.model.AnimProp
import com.example.model.ClipFxLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Queue 15(j) (build 147): the second-wave Alight ports — 19 effect ids whose defs were
 * staged in hunt rounds 5 / batch 8 (Ultra originals in hunt-staging/round5, TrueMotion
 * conversions in hunt-staging/defs-batch8), plus the scaleassist per-axis completion.
 * The instances below are verbatim corpus blocks (file named per test); the pins are the
 * fx_plan.h slot layouts and the AmFxEval tail resolutions, and every test also pins that
 * the import produces no "not supported yet" warning for its effect.
 */
class Q15jImportTest {

    private fun scene(layers: String): String =
        """<?xml version='1.0' encoding='UTF-8' ?>
<scene title="Q15J" width="1080" height="1920" bgcolor="#ff102030" totalTime="2000" fps="30">
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

    @Test
    fun negativeVerbatim() {
        // omniframe-montagem-guerreirro-pf.xml verbatim: every property at the def default.
        val r = parse(
            """<effect id="com.alightcreative.effects.negative" locallyApplied="true"><property name="contrast" type="float" value="1.400000" /><property name="brightness" type="float" value="0.000000" /><property name="blackPoint" type="float" value="0.080000" /><property name="whitePoint" type="float" value="0.920000" /><property name="sharpness" type="float" value="0.600000" /><property name="grainAmount" type="float" value="0.030000" /></effect>"""
        )
        val fx = fxOf(r, ClipFxLibrary.NEGATIVE_AM)
        assertEquals(listOf(1.4f, 0f, 0.08f, 0.92f, 0.6f, 0.03f, 0f), fx.values)
        assertEquals(185f, ClipFxLibrary.toNative(listOf(fx))!![0], 0f)
        assertTrue(r.warnings.none { it.contains("negative") })
    }

    @Test
    fun tritoneVerbatim() {
        // omniframe-montagem-guerreirro-pf.xml verbatim: midtones #ff34346f.
        val r = parse(
            """<effect id="com.alightcreative.effects.tritone" locallyApplied="true"><property name="highlights" type="color" value="#ffffffff" /><property name="midtones" type="color" value="#ff34346f" /><property name="shadows" type="color" value="#ff000000" /><property name="blend" type="float" value="0.000000" /></effect>"""
        )
        val fx = fxOf(r, ClipFxLibrary.TRITONE_AM)
        // Interleaved layout: colour, its alpha, colour, its alpha, colour, its alpha, blend.
        assertEquals(0xFFFFFF.toFloat(), fx.values[0], 0f)
        assertEquals(1f, fx.values[1], 0f)
        assertEquals(0x34346F.toFloat(), fx.values[2], 0f)
        assertEquals(1f, fx.values[3], 0f)
        assertEquals(0f, fx.values[4], 0f)
        assertEquals(1f, fx.values[5], 0f)
        assertEquals(0f, fx.values[6], 0f)
        assertTrue(r.warnings.none { it.contains("tritone") })
    }

    @Test
    fun unmultVerbatim() {
        // omniframe-lawless-pf.xml verbatim: all def defaults; quality rides the EXT group.
        val r = parse(
            """<effect id="com.alightcreative.effects.unmult" locallyApplied="true"><property name="blackLevel" type="float" value="0.000000" /><property name="softness" type="float" value="1.000000" /><property name="featherPx" type="float" value="10.000000" /><property name="cleanupPx" type="float" value="0.000000" /><property name="edgeGamma" type="float" value="1.000000" /><property name="preserveLuma" type="bool" value="false" /><property name="previewMatte" type="bool" value="false" /><property name="quality" type="float" value="1.000000" /></effect>"""
        )
        val fx = fxOf(r, ClipFxLibrary.UNMULT_AM)
        assertEquals(listOf(0f, 1f, 10f, 0f, 1f, 0f, 0f, 1f), fx.values.take(8))
        val flat = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(187f, flat[0], 0f)
        assertEquals(188f, flat[8], 0f)   // the EXT group rides right behind its owner
        assertTrue(r.warnings.none { it.contains("unmult") })
    }

    @Test
    fun denoiseVerbatim() {
        // omniframe-loser-pf.xml verbatim: PIXEL_MULTIPLIER pushed to 3.
        val r = parse(
            """<effect id="com.alightcreative.effects.denoise" locallyApplied="true"><property name="SAMPLES" type="float" value="60.000000" /><property name="PIXEL_MULTIPLIER" type="float" value="3.000000" /><property name="alpha" type="float" value="1.000000" /><property name="strength" type="float" value="0.000000" /></effect>"""
        )
        val fx = fxOf(r, ClipFxLibrary.DENOISE_AM)
        assertEquals(listOf(60f, 3f, 1f, 0f), fx.values.take(4))
        assertTrue(r.warnings.none { it.contains("denoise") })
    }

    @Test
    fun minimaxRadiusTailResolves() {
        // omniframe-lawless-pf.xml verbatim: radius keyframed 0 -> 5.5 -> 0 (keys
        // serialised out of order); the tail starts at index 7.
        val r = parse(
            """<effect id="com.alightcreative.effects.minimaxblur" locallyApplied="true"><property name="operation" type="int" value="1" /><property name="radius" type="float"><kf t="0.014905" v="0.000000" e="cubicBezier 0.42 0.0 1.0 0.51" /><kf t="1.000000" v="0.000000" e="cubicBezier 0.0 0.49 0.58000004 1.0" /><kf t="0.509485" v="5.500000" e="cubicBezier 0.41999996 0.0 1.0 0.51" /></property><property name="channel" type="int" value="4" /><property name="direction" type="int" value="0" /><property name="keepEdge" type="bool" value="false" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.single { it.type == ClipFxLibrary.MINIMAX_AM }
        assertEquals(1f, fx.values[0], 0f)      // operation max
        assertEquals(3f, fx.values[7], 0f)      // tail: 3 keys
        val peak = AmFxEval.resolve(listOf(fx), c, c.startMs + 1019L).single()   // rel 0.5095
        assertEquals(5.5f, peak.values[1], 0.05f)
        val start = AmFxEval.resolve(listOf(fx), c, c.startMs).single()
        assertEquals(0f, start.values[1], 0.05f)
        assertTrue(r.warnings.none { it.contains("minimaxblur") })
    }

    @Test
    fun rgbGlitchProgressaTailResolves() {
        // omniframe-mi-mi-mi-pf.xml verbatim: progressa keyframed 3.07 -> 13.04.
        val r = parse(
            """<effect id="com.alightcreative.effects.rgbglitch" locallyApplied="true"><property name="progressd" type="float" value="0.100000" /><property name="progressb" type="float" value="0.430000" /><property name="progressa" type="float"><kf t="0.056762" v="3.070000" /><kf t="0.974958" v="13.040000" /></property><property name="progressc" type="float" value="0.080000" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.single { it.type == ClipFxLibrary.RGBGLITCH_AM }
        assertEquals(3.07f, fx.values[0], 0.001f)
        assertEquals(0.43f, fx.values[1], 0.001f)
        assertEquals(0.08f, fx.values[2], 0.001f)
        assertEquals(0.1f, fx.values[3], 0.001f)
        val end = AmFxEval.resolve(listOf(fx), c, c.startMs + 1950L).single()   // rel 0.975
        assertEquals(13.04f, end.values[0], 0.05f)
        assertTrue(r.warnings.none { it.contains("rgbglitch") })
    }

    @Test
    fun fisheyePowerTailResolves() {
        // omniframe-hardtrekk-hero.xml verbatim: power keyframed 0.5 -> 0.
        val r = parse(
            """<effect id="com.alightcreative.effects.fisheye_antifisheye" locallyApplied="true"><property name="power" type="float"><kf t="1.000000" v="0.000000" e="cubicBezier 0.0 1.0 0.22666666 1.0" /><kf t="0.000000" v="0.500000" /></property><property name="centerX" type="float" value="0.500000" /><property name="centerY" type="float" value="0.500000" /><property name="aspectMode" type="int" value="1" /><property name="alpha" type="float" value="1.000000" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.single { it.type == ClipFxLibrary.FISHEYE_AM }
        assertEquals(0.5f, fx.values[0], 0.001f)
        assertEquals(0.5f, fx.values[1], 0.001f)   // centerX constant (placeholder tail behind power)
        val end = AmFxEval.resolve(listOf(fx), c, c.startMs + 1999L).single()
        assertEquals(0f, end.values[0], 0.01f)
        assertTrue(r.warnings.none { it.contains("fisheye") })
    }

    @Test
    fun vortexStrengthTailResolves() {
        // omniframe-loser-pf.xml verbatim: strength keyframed 0 -> -0.218, radius 0.8.
        val r = parse(
            """<effect id="com.alightcreative.effects.chromaticvortexblur" locallyApplied="true"><property name="strength" type="float"><kf t="0.631579" v="-0.218000" e="cubicBezier 0.49999997 0.0 1.0 0.0" /><kf t="0.000000" v="0.000000" /></property><property name="radius" type="float" value="0.800000" /><property name="colorAberration" type="float" value="1.000000" /><property name="centerChannel" type="int" value="1" /><property name="mode" type="int" value="2" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.single { it.type == ClipFxLibrary.VORTEX_BLUR_AM }
        assertEquals(0f, fx.values[0], 0.001f)
        assertEquals(0.8f, fx.values[1], 0.001f)
        assertEquals(1f, fx.values[2], 0.001f)
        assertEquals(ClipFxLibrary.packPoint(0f, 0f), fx.values[5], 0f)   // centerPoint never serialised
        val at = AmFxEval.resolve(listOf(fx), c, c.startMs + 1263L).single()   // rel 0.6316
        assertEquals(-0.218f, at.values[0], 0.01f)
        assertTrue(r.warnings.none { it.contains("vortex") })
    }

    @Test
    fun refractionValuesAndIntensityTail() {
        // omniframe-mi-mi-mi-pf.xml verbatim params (the corpus instance is hidden there;
        // imported visible here): intensity keyframed 3.95 -> 0, tail past the EXT2 group.
        val r = parse(
            """<effect id="com.alightcreative.effects.refractionwarp" locallyApplied="true"><property name="autop" type="bool" value="true" /><property name="PROGRESS" type="float" value="1.000000" /><property name="SPEEED" type="float" value="2.450000" /><property name="scale" type="float" value="1.000000" /><property name="intensity" type="float"><kf t="0.312030" v="0.000000" e="cubicBezier 0.0 0.8147059 0.19333333 1.0" /><kf t="0.030075" v="3.950000" /></property><property name="frequency" type="float" value="1.400000" /><property name="emboss" type="float" value="0.240000" /><property name="steps" type="float" value="15.000000" /><property name="angle" type="float" value="6.000000" /><property name="delta" type="float" value="60.579998" /><property name="gain" type="float" value="799.000000" /><property name="reflectionCutOff" type="float" value="0.241000" /><property name="reflectionIntensity" type="float" value="205.839996" /><property name="speed" type="float" value="0.200000" /><property name="speed_x" type="float" value="0.300000" /><property name="speed_y" type="float" value="0.300000" /><property name="alpha" type="float" value="1.000000" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.single { it.type == ClipFxLibrary.REFRACTION_AM }
        assertEquals(1f, fx.values[0], 0f)          // autop
        assertEquals(2.45f, fx.values[2], 0.001f)   // SPEEED
        assertEquals(3.95f, fx.values[5], 0.001f)   // intensity at t = 0
        assertEquals(0.24f, fx.values[7], 0.001f)   // emboss (EXT)
        assertEquals(15f, fx.values[8], 0f)         // steps
        assertEquals(205.84f, fx.values[13], 0.01f) // reflectionIntensity
        assertEquals(0.2f, fx.values[14], 0.001f)   // speed (EXT2)
        assertEquals(2f, fx.values[21], 0f)         // intensity tail: 2 keys, from index 21
        val at = AmFxEval.resolve(listOf(fx), c, c.startMs + 624L).single()   // rel 0.312
        assertEquals(0f, at.values[5], 0.02f)
        val flat = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(194f, flat[0], 0f)
        assertEquals(195f, flat[8], 0f)
        assertEquals(196f, flat[16], 0f)
        assertTrue(r.warnings.none { it.contains("refraction") })
    }

    @Test
    fun twitchAmplitudeTailResolves() {
        // omniframe-hardtrekk-hero.xml verbatim: amplitude 0.372 -> 0; samples 41 rides
        // through (the shader clamps to the def's 32 taps).
        val r = parse(
            """<effect id="com.alightcreative.effects.smartMotionBlurShake" locallyApplied="true"><property name="amplitude" type="float"><kf t="0.000000" v="0.372000" /><kf t="0.785408" v="0.000000" e="cubicBezier 0.0 0.87647057 0.28333333 1.0" /></property><property name="shakeInterval" type="float" value="0.030000" /><property name="samples" type="float" value="41.000000" /><property name="angle" type="float" value="0.000000" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.single { it.type == ClipFxLibrary.TWITCH_AM }
        assertEquals(0.372f, fx.values[0], 0.001f)
        assertEquals(41f, fx.values[2], 0f)
        val at = AmFxEval.resolve(listOf(fx), c, c.startMs + 1571L).single()   // rel 0.7854
        assertEquals(0f, at.values[0], 0.01f)
        assertTrue(r.warnings.none { it.contains("twitch") })
    }

    @Test
    fun turbulent2DirectionIsAValueAndIntensityTailResolves() {
        // omniframe-mi-mi-mi-pf.xml verbatim: direction serialises as the def's option
        // VALUE (0 = X here; the def default 4 proves values, not indices), intensity
        // keyframed 0.096 -> 0 with its tail past the EXT group (index 14).
        val r = parse(
            """<effect id="com.alightcreative.effects.turbulentdisplace2" locallyApplied="false"><property name="intensity" type="float"><kf t="0.001880" v="0.096000" /><kf t="0.543233" v="0.000000" e="cubicBezier 0.0 0.7046652 0.5485992 0.9982368" /></property><property name="evolution" type="float" value="0.000000" /><property name="direction" type="int" value="0" /><property name="scale" type="float" value="0.010000" /><property name="stretch" type="float" value="26.699999" /><property name="seed" type="float" value="0.000000" /><property name="singleAxis" type="bool" value="true" /><property name="screenSpace" type="bool" value="false" /><property name="octaves" type="float" value="1.000000" /><property name="offs" type="float" value="0.000000" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.single { it.type == ClipFxLibrary.TURBULENT_DISPLACE2_AM }
        assertEquals(0f, fx.values[2], 0f)        // direction 0 (X), NOT remapped
        assertEquals(26.7f, fx.values[4], 0.01f)  // stretch
        assertEquals(1f, fx.values[8], 0f)        // octaves (EXT)
        assertEquals(2f, fx.values[14], 0f)       // intensity tail: 2 keys, from index 14
        val at = AmFxEval.resolve(listOf(fx), c, c.startMs + 1086L).single()   // rel 0.543
        assertEquals(0f, at.values[0], 0.01f)
        assertTrue(r.warnings.none { it.contains("turbulentdisplace2") })
    }

    @Test
    fun shapefieldVerbatim() {
        // omniframe-hardtrekk-hero.xml verbatim: evolution keyframed 0 -> 1.52 (its tail
        // starts past the EXT2 group at index 21), shapeSize constant 0.247, lockLuminance
        // on -> flags 1.
        val r = parse(
            """<effect id="com.alightcreative.effects.shapefield" locallyApplied="true"><property name="density" type="float" value="0.007000" /><property name="evolution" type="float"><kf t="0.009804" v="0.000000" /><kf t="1.245098" v="1.520000" /></property><property name="seed" type="float" value="0.000000" /><property name="scale" type="float" value="0.790000" /><property name="shapeSize" type="float" value="0.247000" /><property name="shapeAngle" type="float" value="0.000000" /><property name="sizeVariance" type="float" value="0.500000" /><property name="angleVariance" type="float" value="0.000000" /><property name="layerCount" type="float" value="15.000000" /><property name="fade" type="float" value="1.000000" /><property name="alpha" type="float" value="1.000000" /><property name="slack" type="float" value="1.000000" /><property name="blendMode" type="int" value="0" /><property name="coloring" type="int" value="0" /><property name="color1" type="color" value="#ffff55aa" /><property name="color2" type="color" value="#ff55aaff" /><property name="color3" type="color" value="#ffffaa55" /><property name="lockLuminance" type="bool" value="true" /><property name="lockSaturation" type="bool" value="false" /><property name="interpHue" type="bool" value="false" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.single { it.type == ClipFxLibrary.SHAPEFIELD_AM }
        assertEquals(0.007f, fx.values[3], 0.0001f)  // density
        assertEquals(0.247f, fx.values[7], 0.001f)   // shapeSize (EXT slot 0)
        assertEquals(0xFF55AA.toFloat(), fx.values[17], 0f)
        assertEquals(0x55AAFF.toFloat(), fx.values[18], 0f)
        assertEquals(1f, fx.values[20], 0f)          // flags: lockLuminance only
        assertEquals(2f, fx.values[21], 0f)          // evolution tail: 2 keys, from index 21
        // rel 1.0 sits between the keys: 1.52 * (1 - 0.009804) / (1.245098 - 0.009804).
        val at = AmFxEval.resolve(listOf(fx), c, c.startMs + 2000L).single()
        assertEquals(1.218f, at.values[4], 0.01f)
        val flat = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(200f, flat[0], 0f)
        assertEquals(201f, flat[8], 0f)
        assertEquals(202f, flat[16], 0f)
        assertTrue(r.warnings.none { it.contains("shapefield") })
    }

    @Test
    fun ccGlassVerbatim() {
        // omniframe-montagem-guerreirro-pf.xml verbatim: metal pushed to 100, ambient 27 /
        // diffuse 73 / specular 100, lightPosition raw normalised (0.5, 0.5).
        val r = parse(
            """<effect id="com.alightcreative.effects.cc_glass" locallyApplied="true"><property name="property" type="int" value="0" /><property name="softness" type="float" value="2.000000" /><property name="height" type="float" value="15.000000" /><property name="displacement" type="float" value="0.000000" /><property name="lightType" type="int" value="0" /><property name="lightIntensity" type="float" value="100.000000" /><property name="lightColor" type="color" value="#00ffffff" /><property name="lightHeight" type="float" value="50.000000" /><property name="lightPosition" type="vec2" value="0.500000,0.500000" /><property name="lightDirection" type="float" value="0.000000" /><property name="ambient" type="float" value="27.000000" /><property name="diffuse" type="float" value="73.000000" /><property name="specular" type="float" value="100.000000" /><property name="roughness" type="float" value="0.050000" /><property name="metal" type="float" value="100.000000" /></effect>"""
        )
        val fx = fxOf(r, ClipFxLibrary.CC_GLASS_AM)
        assertEquals(0xFFFFFF.toFloat(), fx.values[6], 0f)   // lightColor (alpha byte dropped)
        assertEquals(0.5f, fx.values[8], 0.001f)             // lightPosition x (EXT)
        assertEquals(0.5f, fx.values[9], 0.001f)             // lightPosition y
        assertEquals(27f, fx.values[11], 0f)                 // ambient
        assertEquals(0.05f, fx.values[14], 0.001f)           // roughness (EXT2)
        assertEquals(100f, fx.values[15], 0f)                // metal
        assertTrue(r.warnings.none { it.contains("cc_glass") })
    }

    @Test
    fun bokehRadiusTailResolves() {
        // omniframe-hardtrekk-hero.xml verbatim: blurShape 6 (polygon), blurRadius
        // keyframed with a key before the layer start (t = -0.917).
        val r = parse(
            """<effect id="com.creative.cinematic_bokeh" locallyApplied="true"><property name="blurShape" type="int" value="6" /><property name="blurWidth" type="float" value="1.460000" /><property name="blurHeight" type="float" value="1.460000" /><property name="blurRadius" type="float"><kf t="0.040100" v="0.014000" e="cubicBezier 0.42 0.0 1.0 0.17" /><kf t="-0.917293" v="0.000000" e="cubicBezier 0.42 0.0 1.0 0.17" /><kf t="1.000000" v="0.000000" e="cubicBezier 0.0 0.83 0.58000004 1.0" /></property><property name="highlightThreshold" type="float" value="0.500000" /><property name="highlightGain" type="float" value="4.000000" /><property name="quality" type="float" value="48.000000" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.single { it.type == ClipFxLibrary.BOKEH_AM }
        assertEquals(6f, fx.values[0], 0f)        // blurShape
        assertEquals(1.46f, fx.values[1], 0.001f) // blurWidth
        val at = AmFxEval.resolve(listOf(fx), c, c.startMs + 80L).single()   // rel 0.0401
        assertEquals(0.014f, at.values[3], 0.002f)
        val end = AmFxEval.resolve(listOf(fx), c, c.startMs + 1999L).single()
        assertEquals(0f, end.values[3], 0.002f)
        assertTrue(r.warnings.none { it.contains("bokeh") })
    }

    @Test
    fun lensBlurObsProgressaTailResolves() {
        // Def-shaped instance: progressa keyframed 0 -> 1 (the corpus pattern).
        val r = parse(
            """<effect id="com.alightcreative.effects.lensblurobs" locallyApplied="true"><property name="ITERATIONS" type="float" value="100.000000" /><property name="progressa" type="float"><kf t="0.000000" v="0.000000" /><kf t="1.000000" v="1.000000" /></property><property name="RADIUS" type="float" value="1.000000" /><property name="bright" type="float" value="1.000000" /><property name="alpha" type="float" value="1.000000" /><property name="blendMode" type="int" value="0" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.single { it.type == ClipFxLibrary.LENSBLUR_OBS_AM }
        assertEquals(100f, fx.values[0], 0f)
        val mid = AmFxEval.resolve(listOf(fx), c, c.startMs + 1000L).single()
        assertEquals(0.5f, mid.values[1], 0.01f)
        assertTrue(r.warnings.none { it.contains("lensblurobs") })
    }

    @Test
    fun solidWipeVerbatim() {
        // omniframe-hardtrekk-hero.xml verbatim: fill #ffffe200, band from 0.602.
        val r = parse(
            """<effect id="com.alightcreative.effects.solid_color_wipe" locallyApplied="true"><property name="fillColor" type="color" value="#ffffe200" /><property name="alpha" type="float" value="1.000000" /><property name="blendMode" type="int" value="0" /><property name="start" type="float" value="0.602000" /><property name="end" type="float" value="1.000000" /><property name="angle" type="float" value="0.000000" /><property name="feather" type="float" value="0.000000" /></effect>"""
        )
        val fx = fxOf(r, ClipFxLibrary.SOLID_WIPE_AM)
        assertEquals(0xFFE200.toFloat(), fx.values[0], 0f)
        assertEquals(1f, fx.values[1], 0f)        // fill alpha (carried, unread by the def)
        assertEquals(0.602f, fx.values[4], 0.001f)
        val flat = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(210f, flat[0], 0f)
        assertEquals(211f, flat[8], 0f)
        assertTrue(r.warnings.none { it.contains("solid") })
    }

    @Test
    fun softblooomVerbatim() {
        // omniframe-tiki-tiki-pf.xml verbatim: strength 1.28, intensity 0.54.
        val r = parse(
            """<effect id="com.alightcreative.effects.softblooom" locallyApplied="true"><property name="strength" type="float" value="1.280000" /><property name="intensity" type="float" value="0.540000" /><property name="threshold" type="float" value="0.150000" /><property name="glowStretch" type="vec2" value="1.000000,1.000000" /><property name="galpha" type="float" value="1.000000" /><property name="alpha" type="float" value="1.000000" /></effect>"""
        )
        val fx = fxOf(r, ClipFxLibrary.SOFTBLOOM_AM)
        assertEquals(listOf(1.28f, 0.54f, 0.15f, 1f, 1f, 1f, 1f), fx.values)
        assertTrue(r.warnings.none { it.contains("softblooom") })
    }

    @Test
    fun lensdAmpTailResolves() {
        // omniframe-shakes.xml verbatim: amp keyframed -1.54 -> 0, tail past the EXT group.
        val r = parse(
            """<effect id="com.alightcreative.effects.lensd" locallyApplied="true"><property name="offs" type="bool" value="false" /><property name="amp" type="float"><kf t="0.000000" v="-1.540000" /><kf t="1.000000" v="0.000000" e="cubicBezier 0.0 0.0 0.39952236 1.0" /></property><property name="strength" type="float" value="100.000000" /><property name="blurMax" type="float" value="0.200000" /><property name="aberrationMax" type="float" value="1.200000" /><property name="numIters" type="float" value="50.000000" /><property name="bright" type="float" value="1.000000" /><property name="alpha" type="float" value="1.000000" /><property name="new" type="bool" value="true" /><property name="iter" type="float" value="1.000000" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.single { it.type == ClipFxLibrary.LENSD_AM }
        assertEquals(-1.54f, fx.values[1], 0.001f)
        assertEquals(1f, fx.values[8], 0f)        // new (EXT)
        assertEquals(2f, fx.values[14], 0f)       // amp tail: 2 keys, from index 14
        val end = AmFxEval.resolve(listOf(fx), c, c.startMs + 1999L).single()
        assertEquals(0f, end.values[1], 0.01f)
        val flat = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(213f, flat[0], 0f)
        assertEquals(214f, flat[8], 0f)
        assertTrue(r.warnings.none { it.contains("lensd") })
    }

    @Test
    fun halftoneDotsVerbatim() {
        // omniframe-montagem-guerreirro-pf.xml verbatim: all def defaults.
        val r = parse(
            """<effect id="com.alightcreative.effects.halftone_dots" locallyApplied="true"><property name="dotsMode" type="int" value="0" /><property name="dotsFrequency" type="float" value="45.000000" /><property name="dotsAngle" type="float" value="33.000000" /><property name="dotsRelWidth" type="float" value="1.000000" /><property name="dotsSharpness" type="float" value="4.000000" /><property name="dotsLighten" type="float" value="0.000000" /><property name="smoothSource" type="float" value="0.000000" /><property name="color1" type="color" value="#ffffffff" /><property name="color0" type="color" value="#ff000000" /><property name="shiftX" type="float" value="0.000000" /><property name="shiftY" type="float" value="0.000000" /><property name="alpha" type="float" value="1.000000" /></effect>"""
        )
        val fx = fxOf(r, ClipFxLibrary.HALFTONE_DOTS_AM)
        assertEquals(listOf(0f, 45f, 33f, 1f, 4f, 0f, 0f), fx.values.take(7))
        assertEquals(0xFFFFFF.toFloat(), fx.values[7], 0f)   // color1 (EXT)
        assertEquals(0f, fx.values[8], 0f)                   // color0
        assertEquals(1f, fx.values[11], 0f)                  // alpha
        assertTrue(r.warnings.none { it.contains("halftone_dots") })
    }

    // ---- scaleassist per-axis completion ----

    @Test
    fun scaleAssistAxis3KeyframedSquash() {
        // ST Intro verbatim scale track (axis 3, damp 1): at the 1.175 peak the composed
        // SCALE key's height component takes 1 - (1.175 - 1) * 1 = 0.825, NOT 1.175.
        val r = parse(
            """<effect id="com.alightcreative.effects.scaleassist" locallyApplied="true"><property name="axis" type="int" value="3" /><property name="scale" type="float"><kf t="0.573776" v="1.000000" /><kf t="0.721327" v="1.175000" e="cubicBezier 0.87 0.0 0.56 1.0" /><kf t="0.826540" v="1.000000" e="cubicBezier 0.44 0.0 0.13 1.0" /></property><property name="damp" type="float" value="1.000000" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val keys = c.keyframes[AnimProp.SCALE]!!
        val peak = keys.single { it.timeMs == 1443L }   // 0.721327 * 2000
        // A size-100 shape's base SCALE components are 200 / box * 100 (it renders
        // 200 scene px across, the AlightXmlImporterTest convention).
        val box = 1080f * 130f / 360f
        val base = 200f / box * 100f
        assertEquals(base * 1.175f, peak.a, 0.15f)     // X: base * 1.175
        assertEquals(base * 0.825f, peak.b, 0.15f)     // Y: base * (1 - 0.175 * 1) squash
        // At t = 0 the factor is 1 on both axes, so the static shape scales are untouched.
        assertEquals(200f / box, c.shapeScaleY, 0.001f)
        assertTrue(r.warnings.none { it.contains("scaleassist") })
    }

    @Test
    fun scaleAssistAxis2ConstantIsUniform() {
        // omniframe-warps pattern: axis 2 damp 1 constant — uniform on both axes.
        val r = parse(
            """<effect id="com.alightcreative.effects.scaleassist" locallyApplied="true"><property name="axis" type="int" value="2" /><property name="scale" type="float" value="1.500000" /><property name="damp" type="float" value="1.000000" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val box = 1080f * 130f / 360f
        // Base shape scale for size 100 is 200 / box (renders 200 px); x1.5 uniform.
        assertEquals(300f / box, c.shapeScaleX, 0.001f)
        assertEquals(300f / box, c.shapeScaleY, 0.001f)
        assertEquals(200f / box * 100f * 1.5f, c.scalePercent, 0.01f)
        assertTrue(r.warnings.none { it.contains("scaleassist") })
    }

    @Test
    fun scaleAssistAxis0ConstantScalesXOnly() {
        val r = parse(
            """<effect id="com.alightcreative.effects.scaleassist" locallyApplied="true"><property name="axis" type="int" value="0" /><property name="scale" type="float" value="2.000000" /><property name="damp" type="float" value="1.000000" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val box = 1080f * 130f / 360f
        assertEquals(400f / box, c.shapeScaleX, 0.001f)   // X doubled from the 200 / box base
        assertEquals(200f / box, c.shapeScaleY, 0.001f)   // Y untouched on axis 0
        assertEquals(200f / box * 100f * 2f, c.scalePercent, 0.01f)
    }

    @Test
    fun catalogueGrewTo165() {
        assertEquals(165, ClipFxLibrary.all.size)
    }
}
