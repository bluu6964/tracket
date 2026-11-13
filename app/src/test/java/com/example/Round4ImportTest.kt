package com.example

import com.example.engine.AlightXmlImporter
import com.example.engine.AmFxEval
import com.example.model.AnimProp
import com.example.model.ClipFxLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hunt round 4 (build 141): the last two parked effect ids get real definitions.
 *
 * `opticcompesation` — round 3's staged def was a legacy conversion of an ENCRYPTED copy
 * (a native pass naming the effect itself, an identity shader stage). Round 4 found the def
 * in ORIGINAL AM XML format with the full fragment shader inline (AlightMotionUltra's
 * UltraMotionEffects/Optic Compensation.xml — the deepglow2 / dropshadow precedent),
 * corroborated tap-for-tap by TrueMotion's afterz-561 SkSL conversion of the same def
 * (identical math, its source file "561.xml" + sha256 recorded in hunt-staging/round4).
 * The port is that shader (native 183 + EXT 184); the pins below are the corpus instances'
 * values and the AmFxEval tail resolutions.
 *
 * `pulseopacity2` — AM's STOCK def (the am-xml dump's blueprint/effect-defs/
 * pulse_opacity2.xml, AM's own shipped text, corroborated character-for-character by the
 * AlightMotionUltra copy) carries the same script as the conversion: env.time is read and
 * never used, so the multiplier (1 - strength) + ((m + 1) / 2) * strength with
 * m = sin((freq + phase) * pi) (Sine) or AM.triangle((freq + phase) / 2) is a CONSTANT —
 * the pins below are that formula evaluated by hand at the corpus instances' parameters
 * (AM.triangle(a) = 4 * |frac(a) - 0.5| - 1, recovered from openmotion's AM script env).
 */
class Round4ImportTest {

    private fun scene(layers: String): String =
        """<?xml version='1.0' encoding='UTF-8' ?>
<scene title="R4" width="1080" height="1920" bgcolor="#ff102030" totalTime="2000" fps="30">
$layers
</scene>"""

    private fun shapeWith(fx: String, transformExtra: String = ""): String = scene(
        """<shape id="1" label="Box" startTime="0" endTime="2000" fillType="color" s=".rect">
  <transform><location value="540,960,0" /><scale value="1,1" />$transformExtra</transform>
  <fillColor value="#ffffffff" />
  $fx
  <property name="size" type="vec2" value="100,100" /></shape>"""
    )

    private fun parse(fx: String, transformExtra: String = "") =
        AlightXmlImporter.parse(shapeWith(fx, transformExtra), 1080f)

    // ---- opticcompesation ----

    @Test
    fun opticCompLoserVerbatim() {
        // omniframe-loser-pf.xml verbatim (one of its 2 instances): iterations 10,
        // distortion keyframed 2.19 -> 0 (keys serialised out of order), rest at zero /
        // defaults; position is never serialised in any corpus instance.
        val r = parse(
            """<effect id="com.alightcreative.effects.opticcompesation" locallyApplied="true"><property name="iterations" type="float" value="10.000000" /><property name="distortion" type="float"><kf t="0.224638" v="0.000000" e="cubicBezier 0.0 1.0 0.58 1.0" /><kf t="0.000000" v="2.190000" /></property><property name="strength" type="float" value="0.000000" /><property name="separation" type="float" value="0.000000" /><property name="blendMode" type="int" value="0" /><property name="scale" type="float" value="1.000000" /><property name="alpha" type="float" value="1.000000" /><property name="stretch" type="vec2" value="1.000000,1.000000" /><property name="hardness" type="float" value="1.000000" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.single { it.type == ClipFxLibrary.OPTIC_COMP_AM }
        assertEquals(10f, fx.values[0], 0.001f)   // iterations
        assertEquals(2.19f, fx.values[1], 0.001f) // distortion at t = 0
        assertEquals(0f, fx.values[2], 0.001f)    // strength
        assertEquals(0f, fx.values[3], 0.001f)    // separation
        assertEquals(0f, fx.values[4], 0.001f)    // blendMode NORMAL
        assertEquals(1f, fx.values[5], 0.001f)    // scale
        assertEquals(1f, fx.values[6], 0.001f)    // alpha
        assertEquals(0f, fx.values[7], 0.001f)    // position x (def default)
        assertEquals(0f, fx.values[8], 0.001f)    // position y
        assertEquals(1f, fx.values[9], 0.001f)    // stretch x
        assertEquals(1f, fx.values[10], 0.001f)   // stretch y
        assertEquals(1f, fx.values[11], 0.001f)   // hardness
        assertEquals(2f, fx.values[14], 0.001f)   // distortion tail: 2 keys, from index 14
        assertTrue(r.warnings.none { it.contains("opticcompesation") })
        // The tail resolves: at the second key (rel 0.224638) distortion is 0.
        val at = AmFxEval.resolve(listOf(fx), c, c.startMs + 449L).single()
        assertEquals(0f, at.values[1], 0.01f)
        val flat = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(16, flat.size)
        assertEquals(183f, flat[0], 0.001f)
        assertEquals(184f, flat[8], 0.001f)
    }

    @Test
    fun opticCompTikiVerbatimIterationsOne() {
        // omniframe-tiki-tiki-pf.xml verbatim (x6 in that file): iterations 1, distortion
        // keyframed 5.437561 -> 0; the warp at t = 0 is the strong end.
        val r = parse(
            """<effect id="com.alightcreative.effects.opticcompesation" locallyApplied="true"><property name="iterations" type="float" value="1.000000" /><property name="distortion" type="float"><kf t="0.000000" v="5.437561" /><kf t="1.000000" v="0.000000" /></property><property name="strength" type="float" value="0.000000" /><property name="separation" type="float" value="0.000000" /><property name="blendMode" type="int" value="0" /><property name="scale" type="float" value="1.000000" /><property name="alpha" type="float" value="1.000000" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.single { it.type == ClipFxLibrary.OPTIC_COMP_AM }
        assertEquals(1f, fx.values[0], 0.001f)
        assertEquals(5.437561f, fx.values[1], 0.001f)
        val at0 = AmFxEval.resolve(listOf(fx), c, c.startMs).single()
        assertEquals(5.437561f, at0.values[1], 0.01f)
        val atEnd = AmFxEval.resolve(listOf(fx), c, c.startMs + 1999L).single()
        assertEquals(0f, atEnd.values[1], 0.01f)
        assertTrue(r.warnings.none { it.contains("opticcompesation") })
    }

    @Test
    fun opticCompStrengthTailResolvesToo() {
        // The spider-man pattern: distortion AND strength both keyframed — two tails ride
        // (distortion first, then strength, the trackedParams order).
        val r = parse(
            """<effect id="com.alightcreative.effects.opticcompesation" locallyApplied="true"><property name="iterations" type="float" value="15.000000" /><property name="distortion" type="float"><kf t="0.000000" v="0.000000" /><kf t="1.000000" v="2.500000" /></property><property name="strength" type="float"><kf t="0.000000" v="0.000000" /><kf t="1.000000" v="0.030000" /></property></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.single { it.type == ClipFxLibrary.OPTIC_COMP_AM }
        assertEquals(15f, fx.values[0], 0.001f)
        val mid = AmFxEval.resolve(listOf(fx), c, c.startMs + 1000L).single()
        assertEquals(1.25f, mid.values[1], 0.01f)   // distortion halfway (linear keys)
        assertEquals(0.015f, mid.values[2], 0.001f) // strength halfway
        assertTrue(r.warnings.none { it.contains("opticcompesation") })
    }

    @Test
    fun opticCompPanelCreateCarriesTheDefDefaults() {
        val fx = ClipFxLibrary.create(ClipFxLibrary.OPTIC_COMP_AM)!!
        assertEquals(14, fx.values.size)
        assertEquals(10f, fx.values[0], 0.001f)
        assertEquals(0.01f, fx.values[2], 0.001f)
        assertEquals(0.1f, fx.values[3], 0.001f)
        assertEquals(1f, fx.values[9], 0.001f)   // stretch x (EXT)
        assertEquals(1f, fx.values[11], 0.001f)  // hardness (EXT)
        val flat = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(183f, flat[0], 0.001f)
        assertEquals(184f, flat[8], 0.001f)
    }

    // ---- pulseopacity2 ----

    private fun pulseFx(props: String) =
        """<effect id="com.alightcreative.effects.pulseopacity2" locallyApplied="true">$props</effect>"""

    @Test
    fun pulseSineConstantHalvesOpacity() {
        // loser-pf pattern (x5 in that file, all strength 1, integer freq, phase 0):
        // sin(4 * pi) = 0 -> m remapped 0.5 -> multiplier 1 - 1/2 = 0.5 exactly.
        val r = parse(
            pulseFx("""<property name="freq" type="float" value="4.000000"/><property name="strength" type="float" value="1.000000"/>""")
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        assertTrue(c.effects.isEmpty())
        assertEquals(50f, c.opacity, 0.01f)
        val keys = c.keyframes[AnimProp.OPACITY]!!
        assertTrue(keys.all { kotlin.math.abs(it.a - 50f) < 0.01f })
        assertTrue(r.warnings.none { it.contains("pulseopacity2") })
    }

    @Test
    fun pulseSinePeakLeavesTheClipUntouched() {
        // sin(0.5 * pi) = 1 -> pulse 1 -> multiplier 1 at strength 1: no bake at all.
        val r = parse(
            pulseFx("""<property name="freq" type="float" value="0.500000"/><property name="strength" type="float" value="1.000000"/>""")
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(100f, c.opacity, 0.001f)
        assertTrue(c.keyframes[AnimProp.OPACITY] == null)
    }

    @Test
    fun pulseSineTroughZeroesOpacity() {
        // sin(1.5 * pi) = -1 -> pulse 0 -> multiplier 1 - strength = 0 at strength 1.
        val r = parse(
            pulseFx("""<property name="freq" type="float" value="1.500000"/><property name="strength" type="float" value="1.000000"/>""")
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(0f, c.opacity, 0.01f)
    }

    @Test
    fun pulseTriangleWavePoints() {
        // AM.triangle((freq + phase) / 2), phase 0, strength 1:
        // freq 0.5 -> triangle(0.25) = 0 -> multiplier 0.5.
        val half = parse(
            pulseFx("""<property name="freq" type="float" value="0.500000"/><property name="strength" type="float" value="1.000000"/><property name="type" type="int" value="1"/>""")
        ).snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(50f, half.opacity, 0.01f)
        // freq 1 -> triangle(0.5) = -1 -> multiplier 0.
        val zero = parse(
            pulseFx("""<property name="freq" type="float" value="1.000000"/><property name="strength" type="float" value="1.000000"/><property name="type" type="int" value="1"/>""")
        ).snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(0f, zero.opacity, 0.01f)
        // freq 2 -> triangle(1) = +1 -> multiplier 1: untouched.
        val full = parse(
            pulseFx("""<property name="freq" type="float" value="2.000000"/><property name="strength" type="float" value="1.000000"/><property name="type" type="int" value="1"/>""")
        ).snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(100f, full.opacity, 0.001f)
        assertTrue(full.keyframes[AnimProp.OPACITY] == null)
    }

    @Test
    fun pulsePhaseShiftsTheWave() {
        // freq 0 isolates the phase: sin((0 + 0.5) * pi) = 1 -> untouched;
        // sin((0 + 1.5) * pi) = -1 -> opacity 0 at strength 1.
        val peak = parse(
            pulseFx("""<property name="freq" type="float" value="0.000000"/><property name="phase" type="float" value="0.500000"/><property name="strength" type="float" value="1.000000"/>""")
        ).snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(100f, peak.opacity, 0.001f)
        val trough = parse(
            pulseFx("""<property name="freq" type="float" value="0.000000"/><property name="phase" type="float" value="1.500000"/><property name="strength" type="float" value="1.000000"/>""")
        ).snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(0f, trough.opacity, 0.01f)
    }

    @Test
    fun pulseKeyframedStrengthBakeFollowsTheTrack() {
        // Params can be keyframed in AM (none are in the corpus): strength 0 -> 1 with
        // freq 4 (pulse 0.5) takes the multiplier 1 -> 0.5 across the layer.
        val r = parse(
            pulseFx("""<property name="freq" type="float" value="4.000000"/><property name="strength" type="float"><kf t="0.000000" v="0.000000"/><kf t="1.000000" v="1.000000"/></property>""")
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val keys = c.keyframes[AnimProp.OPACITY]!!
        assertEquals(100f, keys.first().a, 0.5f)
        assertEquals(50f, keys.last().a, 0.5f)
        assertTrue(r.warnings.none { it.contains("pulseopacity2") })
    }

    @Test
    fun pulseMultipliesExistingOpacityKeys() {
        // The bake multiplies the layer's OWN opacity track (flicker2's contract):
        // base keys 1.0 -> 0.5 (100 -> 50 on the 0..100 scale) at multiplier 0.5 give 50 -> 25.
        val r = parse(
            pulseFx("""<property name="freq" type="float" value="4.000000"/><property name="strength" type="float" value="1.000000"/>"""),
            transformExtra = """<opacity><kf t="0.000000" v="1.000000"/><kf t="1.000000" v="0.500000"/></opacity>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val keys = c.keyframes[AnimProp.OPACITY]!!
        assertEquals(50f, keys.first().a, 0.5f)
        assertEquals(25f, keys.last().a, 0.5f)
    }
}
