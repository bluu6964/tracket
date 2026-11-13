package com.example

import com.example.engine.AlightXmlImporter
import com.example.engine.AmFxEval
import com.example.model.ClipFxLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build 171: Alight's ripplelight — the round-5 union port (Ultra original
 * "Ripple (Radial Light)" + TrueMotion afterz-438). The instance below is
 * verbatim from the corpus (omniframe-hardtrekk-hero, both of its 2 instances
 * are identical); the pins are the fx_plan.h slot layout and the AmFxEval tail
 * resolutions, and every test also pins that the import produces no
 * "not supported yet" warning for the effect.
 *
 * The union reading: DEF-A carries lightColor but no fixedUV, DEF-B carries
 * fixedUV but no lightColor — the instance serialises BOTH, so the shipped
 * def carries both. The port implements DEF-A's shader (layer-norm UVs,
 * oriented light) + DEF-B's fixedUV output gate; the three formula
 * divergences (UV space, light term, fixedUV gate) are recorded in fx_plan.h,
 * and the phone comparison on omniframe-hardtrekk-hero arbitrates — nothing
 * about them is guessed here.
 */
class RipplelightImportTest {

    private fun scene(layers: String): String =
        """<?xml version='1.0' encoding='UTF-8' ?>
<scene title="RL" width="1080" height="1920" bgcolor="#ff102030" totalTime="2000" fps="30">
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
    fun ripplelightVerbatimInstanceImports() {
        // omniframe-hardtrekk-hero.xml verbatim (one of its 2 identical
        // instances): all 12 props land in the 228/229 slot layout, no tail
        // (the instance is fully static), and the "not supported" warning is gone.
        val r = parse(
            """<effect id="com.alightcreative.effects.ripplelight" locallyApplied="true"><property name="fixedUV" type="bool" value="true" /><property name="strength" type="float" value="0.020000" /><property name="frequency" type="float" value="7.900000" /><property name="speed" type="float" value="4.000000" /><property name="radius" type="float" value="1.000000" /><property name="decay" type="float" value="0.000000" /><property name="centerX" type="float" value="0.500000" /><property name="centerY" type="float" value="0.500000" /><property name="lightIntensity" type="float" value="1.000000" /><property name="lightSharpness" type="float" value="2.300000" /><property name="blend" type="float" value="1.000000" /><property name="lightColor" type="color" value="#ffffffff" /></effect>"""
        )
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects.single { it.type == ClipFxLibrary.RIPPLELIGHT_AM }
        assertEquals(14, fx.values.size)   // 7 main + 7 EXT, no tail (static)
        assertEquals(0.02f, fx.values[0], 0.001f)   // strength
        assertEquals(7.9f, fx.values[1], 0.001f)    // frequency
        assertEquals(4f, fx.values[2], 0.001f)      // speed
        assertEquals(1f, fx.values[3], 0.001f)      // radius
        assertEquals(0f, fx.values[4], 0.001f)      // decay
        assertEquals(0.5f, fx.values[5], 0.001f)    // centerX
        assertEquals(0.5f, fx.values[6], 0.001f)    // centerY
        assertEquals(1f, fx.values[7], 0.001f)      // lightIntensity (EXT)
        assertEquals(2.3f, fx.values[8], 0.001f)    // lightSharpness (EXT)
        assertEquals(0xFFFFFF.toFloat(), fx.values[9], 0f)   // lightColor packed white
        assertEquals(1f, fx.values[10], 0.001f)     // blend (EXT)
        assertEquals(1f, fx.values[11], 0f)         // fixedUV on (EXT)
        assertTrue(r.warnings.none { it.contains("ripplelight") })
    }

    @Test
    fun ripplelightDefaultsWhenPropsAbsent() {
        // An instance with only strength: the other 11 props fall back to the
        // def's own defaults (fixedUV defaults ON, per DEF-B).
        val r = parse(
            """<effect id="com.alightcreative.effects.ripplelight" locallyApplied="true"><property name="strength" type="float" value="0.100000" /></effect>"""
        )
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects.single { it.type == ClipFxLibrary.RIPPLELIGHT_AM }
        assertEquals(0.1f, fx.values[0], 0.001f)
        assertEquals(20f, fx.values[1], 0.001f)     // frequency default
        assertEquals(1f, fx.values[2], 0.001f)      // speed default
        assertEquals(1f, fx.values[3], 0.001f)      // radius default
        assertEquals(0.5f, fx.values[4], 0.001f)    // decay default
        assertEquals(0.5f, fx.values[5], 0.001f)    // centerX default
        assertEquals(0.5f, fx.values[6], 0.001f)    // centerY default
        assertEquals(0.3f, fx.values[7], 0.001f)    // lightIntensity default
        assertEquals(2f, fx.values[8], 0.001f)      // lightSharpness default
        assertEquals(0xFFFFFF.toFloat(), fx.values[9], 0f)   // lightColor default white
        assertEquals(1f, fx.values[10], 0.001f)     // blend default
        assertEquals(1f, fx.values[11], 0f)         // fixedUV default on
        // fixedUV off is honoured too.
        val r2 = parse(
            """<effect id="com.alightcreative.effects.ripplelight" locallyApplied="true"><property name="fixedUV" type="bool" value="false" /><property name="lightColor" type="color" value="#ffff0000" /></effect>"""
        )
        val fx2 = r2.snapshot.tracks.flatMap { it.clips }[0].effects.single { it.type == ClipFxLibrary.RIPPLELIGHT_AM }
        assertEquals(0f, fx2.values[11], 0f)
        assertEquals(0xFF0000.toFloat(), fx2.values[9], 0f)   // red light, alpha stripped
    }

    @Test
    fun ripplelightKeyframedTailsResolve() {
        // strength 0.02 -> 0.10 and blend 1 -> 0 over the clip; the tails start
        // at 14 (past the EXT group) in AmFxEval's order — strength first, then
        // count-0 placeholders for the constant frequency / speed / radius /
        // decay, then lightIntensity / lightSharpness placeholders, then blend.
        val r = parse(
            """<effect id="com.alightcreative.effects.ripplelight" locallyApplied="true"><property name="strength" type="float"><kf t="0" v="0.020000" /><kf t="1" v="0.100000" /></property><property name="frequency" type="float" value="7.900000" /><property name="speed" type="float" value="4.000000" /><property name="radius" type="float" value="1.000000" /><property name="decay" type="float" value="0.000000" /><property name="centerX" type="float" value="0.500000" /><property name="centerY" type="float" value="0.500000" /><property name="lightIntensity" type="float" value="1.000000" /><property name="lightSharpness" type="float" value="2.300000" /><property name="blend" type="float"><kf t="0" v="1.000000" /><kf t="1" v="0.000000" /></property><property name="lightColor" type="color" value="#ffffffff" /><property name="fixedUV" type="bool" value="true" /></effect>"""
        )
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.single { it.type == ClipFxLibrary.RIPPLELIGHT_AM }
        assertEquals(2f, fx.values[14], 0f)   // strength tail: 2 keys, from index 14
        assertEquals(0f, fx.values[15], 0f)   // frequency: constant placeholder
        val start = AmFxEval.resolve(listOf(fx), c, c.startMs).single()
        assertEquals(0.02f, start.values[0], 0.001f)
        assertEquals(1f, start.values[10], 0.001f)
        val end = AmFxEval.resolve(listOf(fx), c, c.startMs + 2000L).single()
        assertEquals(0.10f, end.values[0], 0.01f)
        assertEquals(0f, end.values[10], 0.001f)
    }

    @Test
    fun ripplelightToNativeEmitsTwoGroups() {
        val fx = ClipFxLibrary.create(ClipFxLibrary.RIPPLELIGHT_AM)!!
        assertEquals(14, fx.values.size)   // 7 panel params + 7 EXT defaults
        val flat = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(16, flat.size)
        assertEquals(228f, flat[0], 0f)    // main group
        assertEquals(0.02f, flat[1], 0.001f)
        assertEquals(229f, flat[8], 0f)    // EXT group rides right behind its owner
        assertEquals(0.3f, flat[9], 0.001f)    // lightIntensity
        assertEquals(2f, flat[10], 0.001f)     // lightSharpness
        assertEquals(0xFFFFFF.toFloat(), flat[11], 0f)   // lightColor packed
        assertEquals(1f, flat[12], 0.001f)     // blend
        assertEquals(1f, flat[13], 0f)         // fixedUV on
    }
}
