package com.example

import com.example.engine.AlightXmlImporter
import com.example.engine.AmFxEval
import com.example.model.ClipFxLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build 127: Alight's `extrude` (s3d-extrude) and `extrudeflat` imports. The effect
 * elements below are verbatim from the corpus files named in each test (corpus round 2:
 * khanbh-cooking x4, khanbh-scene-6 x3, text-toji-pack x1, khanbh-op02 x16 flat).
 * Slot layout: see ClipFxLibrary.EXTRUDE_AM.
 */
class ExtrudeImportTest {

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

    private fun onlyClip(xml: String) =
        AlightXmlImporter.parse(xml, 1080f).snapshot.tracks.flatMap { it.clips }[0]

    @Test
    fun extrudeCookingVerbatim() {
        // khanbh-cooking.xml, the first of its four extrude instances (all static,
        // flat shading). In the file the effect sits on an embedScene group; the
        // importer path is the shared one.
        val fxXml = """<effect id="com.alightcreative.effects.extrude" locallyApplied="true">
      <property name="rotate" type="vec3" value="-44.000000,-26.000000,0.000000" />
      <property name="widthRatio" type="float" value="1.000000" />
      <property name="heightRatio" type="float" value="1.000000" />
      <property name="depth" type="float" value="0.040000" />
      <property name="strength" type="float" value="0.100000" />
      <property name="position" type="vec3" value="0.000000,100.000000,500.000000" />
      <property name="scale" type="float" value="1.000000" />
      <property name="interiorColor" type="color" value="#ff3366aa" />
      <property name="interiorBlend" type="float" value="0.000000" />
      <property name="fadeColor" type="color" value="#ffaa3366" />
      <property name="fadeBlend" type="float" value="0.000000" />
      <property name="shadingType" type="int" value="0" />
      <property name="lightIntensity" type="float" value="1.000000" />
      <property name="shine" type="float" value="1.000000" />
      <property name="spec" type="float" value="30.000000" />
      <property name="ambient" type="float" value="0.100000" />
      <property name="lightColor" type="color" value="#ffffffff" />
      <property name="lightDir" type="vec2" value="75.000000,-75.000000" />
      <property name="lightDepth" type="float" value="100.000000" />
      <property name="MAX_ITER" type="float" value="200.000000" />
    </effect>"""
        val r = AlightXmlImporter.parse(shapeWith(fxXml), 1080f)
        assertTrue(r.warnings.none { it.contains("extrude") })
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects.single { it.type == ClipFxLibrary.EXTRUDE_AM }
        val v = fx.values
        assertEquals(21, v.size)   // fully static: no tails
        assertEquals(0.04f, v[0], 0.0001f)          // depth
        assertEquals(1f, v[1], 0.0001f)             // scale
        assertEquals(0f, v[6], 0.0001f)             // shadingType: flat
        assertEquals(-44f, v[7], 0.0001f)           // rotate x
        assertEquals(-26f, v[8], 0.0001f)           // rotate y
        assertEquals(100f, v[11], 0.0001f)          // position y
        assertEquals(500f, v[12], 0.0001f)          // position z
        assertEquals(0x3366AA.toFloat(), v[13], 0.5f)   // interior colour packed
        assertEquals(0xAA3366.toFloat(), v[14], 0.5f)   // fade colour packed
        assertEquals(0xFFFFFF.toFloat(), v[15], 0.5f)   // light colour packed
        assertEquals(8697781f, v[16], 0.5f)             // lightDir (75, -75) packPoint
        assertEquals(2660f, v[17], 0.5f)                // intensity 1.00 + strength 0.10 packed
        assertEquals(12810f, v[18], 0.5f)               // shine 1.00 + ambient 0.10 packed
        assertEquals(30f, v[19], 0.0001f)               // spec
        assertEquals(410000f, v[20], 0.5f)              // lightDepth 100 + MAX_ITER 200 packed
        val arr = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(24, arr.size)                      // three groups of 8
        assertEquals(123f, arr[0], 0.001f)
        assertEquals(124f, arr[8], 0.001f)
        assertEquals(125f, arr[16], 0.001f)
        assertEquals(410000f, arr[23], 0.5f)
    }

    @Test
    fun extrudeScene6KeyframedRotateRidesTails() {
        // khanbh-scene-6.xml, the first of its three extrude instances: rotate spins
        // 0 -> 1000 degrees about y; shadingType 1 (point light), shine 0.07.
        val fxXml = """<effect id="com.alightcreative.effects.extrude" locallyApplied="true">
      <property name="rotate" type="vec3">
        <kf t="0.005277" v="0.000000,0.000000,0.000000" />
        <kf t="0.994723" v="0.000000,1000.000000,0.000000" />
      </property>
      <property name="widthRatio" type="float" value="1.000000" />
      <property name="heightRatio" type="float" value="1.000000" />
      <property name="depth" type="float" value="0.032000" />
      <property name="strength" type="float" value="0.100000" />
      <property name="position" type="vec3" value="0.000000,100.000000,500.000000" />
      <property name="scale" type="float" value="1.000000" />
      <property name="interiorColor" type="color" value="#ff3366aa" />
      <property name="interiorBlend" type="float" value="0.000000" />
      <property name="fadeColor" type="color" value="#ffaa3366" />
      <property name="fadeBlend" type="float" value="0.000000" />
      <property name="shadingType" type="int" value="1" />
      <property name="lightIntensity" type="float" value="1.000000" />
      <property name="shine" type="float" value="0.070000" />
      <property name="spec" type="float" value="30.000000" />
      <property name="ambient" type="float" value="0.100000" />
      <property name="lightColor" type="color" value="#ffffffff" />
      <property name="lightDir" type="vec2" value="75.000000,-75.000000" />
      <property name="lightDepth" type="float" value="100.000000" />
      <property name="MAX_ITER" type="float" value="200.000000" />
    </effect>"""
        val r = AlightXmlImporter.parse(shapeWith(fxXml), 1080f)
        assertTrue(r.warnings.none { it.contains("extrude") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.EXTRUDE_AM }
        assertEquals(1f, fx.values[6], 0.0001f)         // shadingType: point light
        assertEquals(906f, fx.values[18], 0.5f)         // shine 0.07 + ambient 0.10 packed
        assertEquals(0f, fx.values[8], 0.0001f)         // rotate y slot: the first key
        assertEquals(2f, fx.values[21], 0.0001f)        // the tail starts at 21: rotate x, two keys
        val start = AmFxEval.resolve(listOf(fx), clip, clip.startMs).single()
        assertEquals(0f, start.values[8], 0.01f)
        val end = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs).single()
        assertEquals(1000f, end.values[8], 0.01f)       // rotate y at the end of the span
        assertEquals(500f, end.values[12], 0.01f)       // position untouched (constant)
    }

    @Test
    fun extrudeTojiVerbatimIdentityOrient() {
        // text-toji-pack.xml, the corpus' only lit, fully keyframed extrude — and the
        // only serialised `orient` anywhere: the identity quaternion, so no warning.
        val fxXml = """<effect id="com.alightcreative.effects.extrude" locallyApplied="true">
          <property name="orient" type="quat" value="0.000000,-0.000000,0.000000,1.000000" />
          <property name="rotate" type="vec3">
            <kf t="0.000000" v="650.000000,-359.000000,0.000000" />
            <kf t="1.000000" v="352.000000,-361.000000,0.000000" e="cubicBezier 0.0 0.94 0.54 1.0" />
          </property>
          <property name="widthRatio" type="float" value="0.805000" />
          <property name="heightRatio" type="float" value="1.027000" />
          <property name="depth" type="float" value="0.055000" />
          <property name="strength" type="float" value="0.100000" />
          <property name="position" type="vec3">
            <kf t="1.000000" v="0.000000,100.000000,500.000000" e="cubicBezier 0.0 0.94 0.54 1.0" />
            <kf t="0.000000" v="0.000000,122.000000,2948.000000" />
          </property>
          <property name="scale" type="float" value="1.000000" />
          <property name="interiorColor" type="color" value="#ff888888" />
          <property name="interiorBlend" type="float" value="0.410000" />
          <property name="fadeColor" type="color" value="#ff888888" />
          <property name="fadeBlend" type="float" value="0.580000" />
          <property name="shadingType" type="int" value="2" />
          <property name="lightIntensity" type="float" value="2.000000" />
          <property name="shine" type="float" value="1.000000" />
          <property name="spec" type="float" value="150.000000" />
          <property name="ambient" type="float" value="0.460000" />
          <property name="lightColor" type="color" value="#ff3d4cf5" />
          <property name="lightDir" type="vec2" value="-554.000000,-75.000000" />
          <property name="lightDepth" type="float" value="780.500000" />
          <property name="MAX_ITER" type="float" value="800.000000" />
        </effect>"""
        val r = AlightXmlImporter.parse(shapeWith(fxXml), 1080f)
        assertTrue(r.warnings.none { it.contains("extrude") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.EXTRUDE_AM }
        val v = fx.values
        assertEquals(0.055f, v[0], 0.0001f)
        assertEquals(0.805f, v[2], 0.0001f)
        assertEquals(1.027f, v[3], 0.0001f)
        assertEquals(0.41f, v[4], 0.0001f)
        assertEquals(0.58f, v[5], 0.0001f)
        assertEquals(2f, v[6], 0.0001f)                 // shadingType: directional light
        assertEquals(650f, v[7], 0.0001f)               // rotate x, first key
        assertEquals(-359f, v[8], 0.0001f)
        assertEquals(122f, v[11], 0.0001f)              // position y, first key (keys sorted)
        assertEquals(2948f, v[12], 0.0001f)             // position z, first key
        assertEquals(0x888888.toFloat(), v[13], 0.5f)
        assertEquals(0x3D4CF5.toFloat(), v[15], 0.5f)   // light colour
        assertEquals(6121397f, v[16], 0.5f)             // lightDir (-554, -75) packPoint
        assertEquals(2760f, v[17], 0.5f)                // intensity 2.00 + strength 0.10
        assertEquals(12846f, v[18], 0.5f)               // shine 1.00 + ambient 0.46
        assertEquals(150f, v[19], 0.0001f)              // spec
        assertEquals(1640161f, v[20], 0.5f)             // lightDepth 780.5 + MAX_ITER 800
        val end = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs).single()
        assertEquals(352f, end.values[7], 0.01f)        // rotate x at the end key
        assertEquals(500f, end.values[12], 0.01f)       // position z pulled in to 500
    }

    @Test
    fun extrudeFlatOp02Verbatim() {
        // khanbh-op02.xml, the first of its sixteen extrudeflat instances.
        val fxXml = """<effect id="com.alightcreative.effects.extrudeflat" locallyApplied="true">
      <property name="rotate" type="vec3">
        <kf t="0.006894" v="26.000000,-125.000000,0.000000" />
        <kf t="1.236821" v="-28.000000,47.000000,0.000000" />
      </property>
      <property name="position" type="vec3" value="0.000000,100.000000,500.000000" />
      <property name="stretch" type="vec2" value="1.000000,1.000000" />
      <property name="depth" type="float" value="0.075000" />
      <property name="scale" type="float" value="1.000000" />
      <property name="MAX_ITER" type="float" value="300.000000" />
      <property name="inton" type="bool" value="true" />
      <property name="interiorColor" type="color" value="#ff0011d7" />
      <property name="interiorBlend" type="float" value="1.000000" />
      <property name="fadeColor" type="color" value="#ff2c3eff" />
      <property name="fadeBlend" type="float" value="1.000000" />
    </effect>"""
        val r = AlightXmlImporter.parse(shapeWith(fxXml), 1080f)
        assertTrue(r.warnings.none { it.contains("extrudeflat") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.EXTRUDE_FLAT_AM }
        val v = fx.values
        assertEquals(0.075f, v[0], 0.0001f)             // depth
        assertEquals(1f, v[2], 0.0001f)                 // stretch x
        assertEquals(1f, v[4], 0.0001f)                 // interior blend
        assertEquals(1f, v[6], 0.0001f)                 // inton
        assertEquals(26f, v[7], 0.0001f)                // rotate x, first key
        assertEquals(-125f, v[8], 0.0001f)              // rotate y, first key
        assertEquals(0x0011D7.toFloat(), v[13], 0.5f)   // interior colour
        assertEquals(0x2C3EFF.toFloat(), v[14], 0.5f)   // fade colour
        assertEquals(300f, v[15], 0.0001f)              // MAX_ITER
        val arr = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(126f, arr[0], 0.001f)
        assertEquals(127f, arr[8], 0.001f)
        assertEquals(128f, arr[16], 0.001f)
        // The second rotate key sits past the span (t = 1.236821): evaluation clamps
        // like Alight's tracks, so mid-span the value is the linear blend.
        val mid = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs).single()
        assertEquals(13.88f, mid.values[8], 0.5f)
    }

    @Test
    fun extrudeFlatKeyframedPositionVerbatim() {
        // khanbh-op02.xml: an instance whose position is keyframed too (keys out of
        // document order in the file; the importer sorts them).
        val fxXml = """<effect id="com.alightcreative.effects.extrudeflat" locallyApplied="true">
      <property name="rotate" type="vec3">
        <kf t="0.563602" v="0.000000,0.000000,0.000000" e="cubicBezier 0.42 0.0 0.36291248 1.0" />
        <kf t="0.000000" v="180.000000,0.000000,0.000000" />
      </property>
      <property name="position" type="vec3">
        <kf t="0.468318" v="0.000000,100.000000,500.000000" e="cubicBezier 0.25 0.25 0.34791458 1.0" />
        <kf t="0.007623" v="0.000000,-48.000000,500.000000" />
      </property>
      <property name="stretch" type="vec2" value="1.000000,1.000000" />
      <property name="depth" type="float" value="0.005000" />
      <property name="scale" type="float" value="1.000000" />
      <property name="MAX_ITER" type="float" value="300.000000" />
      <property name="inton" type="bool" value="true" />
      <property name="interiorColor" type="color" value="#ff000000" />
      <property name="interiorBlend" type="float" value="1.000000" />
      <property name="fadeColor" type="color" value="#ff434343" />
      <property name="fadeBlend" type="float" value="1.000000" />
    </effect>"""
        val clip = onlyClip(shapeWith(fxXml))
        val fx = clip.effects.single { it.type == ClipFxLibrary.EXTRUDE_FLAT_AM }
        assertEquals(180f, fx.values[7], 0.0001f)       // rotate x: the t=0 key
        assertEquals(-48f, fx.values[11], 0.0001f)      // position y: the first key
        val start = AmFxEval.resolve(listOf(fx), clip, clip.startMs).single()
        assertEquals(180f, start.values[7], 0.01f)
        assertEquals(-48f, start.values[11], 0.01f)
        val end = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs).single()
        assertEquals(0f, end.values[7], 0.01f)          // rotate settled at its last key
        assertEquals(100f, end.values[11], 0.01f)       // position settled at its last key
    }

    @Test
    fun extrudeFlatIntonFalseIsReproduced() {
        // Build 136: the recovered def gates the interior / fade colour mixes on
        // inton (hunt-staging/defs-batch8), so a false value imports silently into
        // values[6] = 0 — before the def arrived it was reported as unreproduced.
        val fxXml = """<effect id="com.alightcreative.effects.extrudeflat" locallyApplied="true">
      <property name="rotate" type="vec3" value="0.000000,90.000000,0.000000" />
      <property name="position" type="vec3" value="0.000000,100.000000,500.000000" />
      <property name="stretch" type="vec2" value="1.000000,1.000000" />
      <property name="depth" type="float" value="0.100000" />
      <property name="scale" type="float" value="1.000000" />
      <property name="MAX_ITER" type="float" value="300.000000" />
      <property name="inton" type="bool" value="false" />
      <property name="interiorColor" type="color" value="#ff00ff00" />
      <property name="interiorBlend" type="float" value="1.000000" />
      <property name="fadeColor" type="color" value="#ff00ff00" />
      <property name="fadeBlend" type="float" value="1.000000" />
    </effect>"""
        val r = AlightXmlImporter.parse(shapeWith(fxXml), 1080f)
        assertTrue(r.warnings.none { it.contains("inton") })
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects.single { it.type == ClipFxLibrary.EXTRUDE_FLAT_AM }
        assertEquals(0f, fx.values[6], 0.0001f)          // inton false
    }

    @Test
    fun extrudeFlatMissingDepthUsesTheDefDefault() {
        // Build 136: the recovered def's depth default is 0.005 (the serialised-schema
        // port used 0.05); no corpus instance omits depth, so this pins the default.
        val fxXml = """<effect id="com.alightcreative.effects.extrudeflat" locallyApplied="true">
      <property name="rotate" type="vec3" value="0.000000,0.000000,0.000000" />
      <property name="position" type="vec3" value="0.000000,100.000000,500.000000" />
      <property name="stretch" type="vec2" value="1.000000,1.000000" />
      <property name="scale" type="float" value="1.000000" />
      <property name="MAX_ITER" type="float" value="300.000000" />
      <property name="inton" type="bool" value="true" />
      <property name="interiorColor" type="color" value="#ff000000" />
      <property name="interiorBlend" type="float" value="1.000000" />
      <property name="fadeColor" type="color" value="#ff000000" />
      <property name="fadeBlend" type="float" value="1.000000" />
    </effect>"""
        val fx = onlyClip(shapeWith(fxXml)).effects.single { it.type == ClipFxLibrary.EXTRUDE_FLAT_AM }
        assertEquals(0.005f, fx.values[0], 0.0001f)
    }

    @Test
    fun extrudePanelCreateCarriesAmDefaults() {
        val fx = ClipFxLibrary.create(ClipFxLibrary.EXTRUDE_AM)!!
        assertEquals(21, fx.values.size)
        val arr = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(123f, arr[0], 0.001f)
        assertEquals(124f, arr[8], 0.001f)
        assertEquals(125f, arr[16], 0.001f)
        assertEquals(410000f, arr[23], 0.5f)            // def defaults: lightDepth 100 + MAX_ITER 200
        val flat = ClipFxLibrary.create(ClipFxLibrary.EXTRUDE_FLAT_AM)!!
        assertEquals(21, flat.values.size)
        assertEquals(300f, flat.values[15], 0.0001f)    // the corpus' MAX_ITER
    }

    @Test
    fun extrudeNonIdentityOrientIsReported() {
        val fxXml = """<effect id="com.alightcreative.effects.extrude" locallyApplied="true"><property name="orient" type="quat" value="0.500000,0.500000,0.500000,0.500000" /><property name="depth" type="float" value="0.100000" /></effect>"""
        val r = AlightXmlImporter.parse(shapeWith(fxXml), 1080f)
        assertTrue(r.warnings.any { it.contains("orient") && it.contains("not identity") })
        // ... but the effect itself still imports (with the identity orientation).
        assertTrue(r.snapshot.tracks.flatMap { it.clips }[0].effects.any { it.type == ClipFxLibrary.EXTRUDE_AM })
    }
}
