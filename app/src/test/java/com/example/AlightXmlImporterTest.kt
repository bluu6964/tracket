package com.example

import com.example.engine.AlightXmlImporter
import com.example.engine.CounterEval
import com.example.engine.ParentRig
import com.example.engine.RepeatEval
import com.example.engine.textLayoutBoxWidth
import com.example.model.AnimProp
import com.example.model.ClipFxLibrary
import com.example.model.LayerType
import com.example.model.effectiveScalePercent
import com.example.model.packColor
import com.example.model.TimelineClip
import com.example.model.renderAt
import com.example.model.shapeCornerRadii
import com.example.model.shapeDrawScaleX
import com.example.model.shapeDrawScaleY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

class AlightXmlImporterTest {

    private fun scene(layers: String, w: Int = 1080, h: Int = 1920) =
        """<?xml version='1.0' encoding='UTF-8' ?><!-- test -->
<scene title="My Scene" width="$w" height="$h" bgcolor="#ff102030" totalTime="2000" fps="30">
$layers
</scene>"""

    private val riseUp = scene(
        """<text id="1" label="YOUR TEXT" startTime="0" endTime="2000" fillType="color" size="30.000000" font="googlefonts?name=Roboto&amp;weight=900" wrapWidth="900" align="center">
    <transform>
      <location value="540.000000,960.000000,0.000000" />
      <scale value="2.000000,2.000000" />
    </transform>
    <effect id="com.alightcreative.effects.texttransform" locallyApplied="false">
      <property name="end" type="float">
        <kf t="0.000000" v="0.000000" />
        <kf t="0.450000" v="1.000000" />
        <kf t="1.000000" v="1.000000" />
      </property>
      <property name="offset" type="vec2" value="0.000000,70.000000" />
      <property name="component" type="int" value="2" />
      <property name="useFillColor" type="bool" value="false" />
      <property name="fillColor" type="color" value="#ff00ff00" />
    </effect>
    <content>YOUR TEXT</content>
  </text>"""
    )

    @Test
    fun sceneBecomesProjectWithOneTextLayer() {
        val r = AlightXmlImporter.parse(riseUp, 1080f)
        assertEquals("My Scene", r.name)
        assertEquals("9:16", r.snapshot.aspectRatio.label)
        assertEquals(0xFF102030L, r.snapshot.canvasColor)
        val clips = r.snapshot.tracks.flatMap { it.clips }
        assertEquals(1, clips.size)
        val c = clips[0]
        assertEquals(LayerType.TEXT, c.type)
        assertEquals("YOUR TEXT", c.text)
        assertEquals(900, c.fontWeight)
        assertEquals("Roboto", c.fontFamily)
        assertEquals(0L, c.startMs)
        assertEquals(2000L, c.durationMs)
        assertEquals(1, r.snapshot.tracks.size)
    }

    @Test
    fun sizeTimesScaleBecomesScalePercent() {
        val c = AlightXmlImporter.parse(riseUp, 1080f).snapshot.tracks.flatMap { it.clips }[0]
        // 30 * 2 = 60 Alight text units; one unit is sceneWidth / 512 px, so the font renders
        // 60 * 1080 / 512 = 126.6 scene px tall; the app's text is 0.11 * 1080 = 118.8 px at 100 %.
        // (Until the ST Intro parity fix this read size x scale as plain scene px, ~3.75x too small.)
        assertEquals(60f * 1080f / 512f / (0.11f * 1080f) * 100f, c.scalePercent, 0.01f)
    }

    @Test
    fun locationIsMovedFromCentreAndScaledToPreview() {
        val xml = scene(
            """<text id="1" label="A" startTime="0" endTime="1000" size="30" font="googlefonts?name=Roboto&amp;weight=400">
  <transform><location value="640,1060,0" /><scale value="1,1" /></transform>
  <content>A</content></text>"""
        )
        val c = AlightXmlImporter.parse(xml, 540f).snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(50f, c.transformX, 0.001f)   // (640 - 540) * 540 / 1080
        assertEquals(50f, c.transformY, 0.001f)   // (1060 - 960) * 0.5
    }

    @Test
    fun centredLayerHasNoOffset() {
        val c = AlightXmlImporter.parse(riseUp, 777f).snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(0f, c.transformX, 0.001f)
        assertEquals(0f, c.transformY, 0.001f)
    }

    @Test
    fun texttransformIsStoredWithAllValues() {
        val c = AlightXmlImporter.parse(riseUp, 1080f).snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(1, c.textAnimators.size)
        val a = c.textAnimators[0]
        assertEquals(AlightXmlImporter.TEXTTRANSFORM, a.effectId)
        val end = a.props["end"]!!
        assertEquals(3, end.keys.size)
        assertEquals(0.45f, end.keys[1].t, 1e-6f)
        assertEquals(listOf(1f), end.keys[1].v)
        assertEquals(listOf(0f, 70f), a.props["offset"]!!.value)
        assertEquals(listOf(2f), a.props["component"]!!.value)
        assertEquals(listOf(0f), a.props["useFillColor"]!!.value)
        val (pa, pb) = packColor(0xFF00FF00L)
        assertEquals(listOf(pa, pb), a.props["fillColor"]!!.value)
    }

    @Test
    fun animatorRemembersTheTextSizeForItsOffset() {
        val a = AlightXmlImporter.parse(riseUp, 1080f).snapshot.tracks.flatMap { it.clips }[0].textAnimators[0]
        assertEquals(30f, a.refSize, 0f)
    }

    @Test
    fun keyframedLocationAndOpacityBecomeKeyframes() {
        val xml = scene(
            """<text id="1" label="A" startTime="500" endTime="2500" size="30" font="googlefonts?name=Roboto&amp;weight=400">
  <transform>
    <location><kf t="0" v="540,960,0" /><kf t="0.5" v="540,760,0" /></location>
    <scale value="1,1" />
    <opacity><kf t="0" v="0" /><kf t="1" v="1" /></opacity>
  </transform>
  <content>A</content></text>"""
        )
        val c = AlightXmlImporter.parse(xml, 1080f).snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(500L, c.startMs)
        assertEquals(2000L, c.durationMs)
        val pos = c.keyframes[AnimProp.POSITION]!!
        assertEquals(listOf(0L, 1000L), pos.map { it.timeMs })
        assertEquals(-200f, pos[1].b, 0.001f)
        val op = c.keyframes[AnimProp.OPACITY]!!
        assertEquals(listOf(0f, 100f), op.map { it.a })
        assertEquals(2000L, op[1].timeMs)
        assertEquals(0f, c.opacity, 0.001f)   // fallback = first keyframe
    }

    @Test
    fun unsupportedLayersAndEffectsAreReported() {
        val xml = scene(
            """<shape id="2" label="Box" startTime="0" endTime="1000" />
<text id="1" label="A" startTime="0" endTime="1000" size="30" font="googlefonts?name=Pacifico&amp;weight=400">
  <effect id="com.alightcreative.effects.blur" /><content>A</content></text>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        assertEquals(1, r.snapshot.tracks.flatMap { it.clips }.size)
        assertTrue(r.warnings.any { it.contains("<shape>") })
        assertTrue(r.warnings.any { it.contains("blur") })
        assertTrue(r.warnings.any { it.contains("Pacifico") })
    }

    @Test
    fun laterLayerIsOnTop() {
        val xml = scene(
            """<text id="1" label="Bottom" startTime="0" endTime="1000" size="30"><content>B</content></text>
<text id="2" label="Top" startTime="0" endTime="1000" size="30"><content>T</content></text>"""
        )
        val lanes = AlightXmlImporter.parse(xml, 1080f).snapshot.tracks
        assertEquals("Top", lanes.first().clips.first().name)
    }

    @Test
    fun customSceneSizeGetsItsOwnAspect() {
        val r = AlightXmlImporter.parse(scene("", 1000, 1000), 1080f)
        assertEquals(1f, r.snapshot.aspectRatio.ratioFloat, 1e-4f)
        assertTrue(r.warnings.any { it.contains("No supported layer") })
    }

    @Test
    fun wrongRootIsRefused() {
        try {
            AlightXmlImporter.parse("<project/>", 1080f)
            fail("expected an error")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
        try {
            AlightXmlImporter.parse("not xml at all", 1080f)
            fail("expected an error")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun doctypeIsRefusedOrIgnored() {
        val xml = "<?xml version='1.0'?><!DOCTYPE scene [<!ENTITY x 'boom'>]><scene width='10' height='10'/>"
        // Either the parser refuses the DOCTYPE (IllegalArgumentException) or it parses without expanding anything.
        try {
            val r = AlightXmlImporter.parse(xml, 1080f)
            assertTrue(r.snapshot.tracks.isEmpty())
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun sniffingTellsXmlFromZip() {
        assertTrue(AlightXmlImporter.looksLikeAlightXml(riseUp.toByteArray()))
        assertTrue(AlightXmlImporter.looksLikeAlightXml(("\uFEFF" + riseUp).toByteArray()))
        assertFalse(AlightXmlImporter.looksLikeAlightXml(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0)))
        assertFalse(AlightXmlImporter.looksLikeAlightXml("{\"json\":true}".toByteArray()))
    }

    @Test
    fun colourAndFontParsing() {
        assertEquals(0xFF00FF00L, AlightXmlImporter.parseColor("#ff00ff00"))
        assertEquals(0xFF112233L, AlightXmlImporter.parseColor("#112233"))
        assertEquals(null, AlightXmlImporter.parseColor("red"))
        val w = ArrayList<String>()
        assertEquals("Roboto" to 900, AlightXmlImporter.parseFont("googlefonts?name=Roboto&weight=900", w))
        assertTrue(w.isEmpty())
        assertEquals("Roboto" to 600, AlightXmlImporter.parseFont("", w))
    }

    // ---- Shapes and groups: a trimmed excerpt of the real export "3" (the last embedScene "Shape" is left out) ----

    private val three = """<?xml version='1.0' encoding='UTF-8' ?>
<scene title="3" width="1920" height="1080" exportWidth="1920" exportHeight="1080" precompose="dynamicResolution" bgcolor="#00000000" totalTime="5833" fps="30" modifiedTime="1786786409045" amver="1002592" ffver="106" am="com.alightcreative.motion/5.0.275" amplatform="android" retime="freeze" retimeAdaptFPS="false">
<shape id="634455" label="Rectangle 1" startTime="0" endTime="5832" fillType="color" mediaFillMode="stretch" s=".rect"><transform lockAspectRatio="false"><location value="960.000000,540.000000,0.000000" /><scale value="10.700000,5.760000" /></transform><fillColor value="#ff0e0e0e" /><property name="size" type="vec2" value="100.000000,100.000000" /></shape>
<embedScene id="634615" label="Group 2" startTime="0" endTime="5833" fillType="intrinsic" outTime="5833" mediaFillMode="stretch"><transform><location value="960.000000,527.500000,0.000000" /><pivot value="0.000000,26.620605" /><scale value="0.617748,0.617748" /></transform><fillColor value="#ff000000" />
<scene title="" width="1920" height="1080" exportWidth="1920" exportHeight="1080" precompose="dynamicResolution" bgcolor="#00000000" totalTime="5833" fps="30" modifiedTime="0" amver="1002592" ffver="106" am="com.alightcreative.motion/5.0.275" amplatform="android" retime="off" retimeAdaptFPS="false">
<shape id="634608" label="Circle 3" startTime="1666" endTime="3199" fillType="none" mediaFillMode="stretch" s=".circle"><transform><location value="960.000000,540.000000,0.000000" /><scale value="1.340000,1.340000" /></transform><fillColor value="#fffae965" /><effect id="com.alightcreative.effects.drawing.progress" locallyApplied="false"><property name="start" type="float"><kf t="0.010437" v="100.000000" /><kf t="0.988911" v="0.000000" e="cubicBezier 1.0 0.0 0.63666666 1.0" /></property><property name="end" type="float" value="100.000000" /></effect><path-stroke direction="centered" end-size="1.500000"><color value="#ffffffff" /><size value="29.500000" /></path-stroke><property name="size" type="vec2" value="100.000000,100.000000" /></shape>
<shape id="634609" label="Outline" startTime="0" endTime="5833" fillType="none" mediaFillMode="stretch" s=".roundrect"><transform><location value="960.000000,540.000000,0.000000" /><scale value="1.122605,1.122605" /></transform><fillColor value="#ffb59b70" /><effect id="com.alightcreative.solidcolor" locallyApplied="false"><property name="color" type="color"><kf t="0.694326" v="#ffffffff" /><kf t="0.705640" v="#ff14fd6a" /></property><property name="alpha" type="float" value="1.000000" /><property name="blendMode" type="int" value="0" /></effect><path-stroke direction="centered" end-size="1.500000"><color value="#ff009dff" /><size value="10.000000" /></path-stroke><property name="size" type="vec2"><kf t="0.174182" v="522.000000,136.000000" /><kf t="0.259900" v="136.000000,136.000000" e="cubicBezier 0.49 0.0 0.22 1.0" /><kf t="0.545688" v="136.000000,136.000000" /><kf t="0.660038" v="372.000000,136.000000" e="cubicBezier 0.49 0.0 0.22 1.0" /></property><property name="cornerRadius" type="float" value="373.000000" /></shape>
<embedScene id="634610" label="Text" startTime="0" endTime="5832" fillType="intrinsic" inTime="33" outTime="5965" mediaFillMode="stretch"><transform><location value="960.000000,540.000000,0.000000" /></transform><fillColor value="#ff000000" />
<scene title="" width="1920" height="1080" exportWidth="1920" exportHeight="1080" precompose="dynamicResolution" bgcolor="#00000000" totalTime="5398" fps="30" modifiedTime="0" amver="1002592" ffver="106" am="com.alightcreative.motion/5.0.275" amplatform="android" retime="off" retimeAdaptFPS="false">
<text id="634464" startTime="0" endTime="5398" fillType="color" mediaFillMode="stretch" size="20.000000" font="imported?name=ArgentumSans-Medium.ttf" wrapWidth="512" align="left"><transform><location><kf t="0.169692" v="1151.978882,530.500000,0.000000" /><kf t="0.262320" v="1151.978882,728.973022,0.000000" e="cubicBezier 0.49 0.0 0.22 1.0" /></location><scale value="1.380859,1.380859" /></transform><content>Download</content></text>
<text id="634488" label="Download Copy" startTime="3133" endTime="5398" fillType="color" mediaFillMode="stretch" size="20.000000" font="imported?name=ArgentumSans-Medium.ttf" wrapWidth="512" align="center"><transform><location><kf t="0.404856" v="957.978882,719.473022,0.000000" /><kf t="0.640177" v="957.978882,530.500000,0.000000" e="cubicBezier 0.49 0.0 0.22 1.0" /></location><scale value="1.380859,1.380859" /></transform><content>Download</content></text>
<shape id="634465" label="Rounded Rectangle 1 Copy" startTime="33" endTime="5398" fillType="color" blending="mask" mediaFillMode="stretch" s=".roundrect"><transform><location value="960.000000,540.000000,0.000000" /><scale value="1.070881,1.070881" /></transform><fillColor value="#ff1ed91e" /><path-stroke direction="centered" enabled="false" end-size="1.500000"><color value="#ffffffff" /><size value="13.000000" /></path-stroke><property name="size" type="vec2" value="522.000000,136.000000" /><property name="cornerRadius" type="float" value="373.000000" /></shape>
</scene>
</embedScene>
<embedScene id="634611" label="Group 1" startTime="0" endTime="3199" fillType="intrinsic" outTime="3199" mediaFillMode="stretch"><transform><location><kf t="0.317599" v="959.500000,540.000000,0.000000" /><kf t="0.473898" v="1394.030762,540.000000,0.000000" e="cubicBezier 0.49 0.0 0.22333324 1.0" /></location><pivot value="-432.019958,0.000000" /><scale value="1.093878,1.093878" /><rotation><kf t="0.317599" v="0.000000" /><kf t="0.473898" v="180.000000" e="cubicBezier 0.49 0.0 0.22 1.0" /></rotation></transform><fillColor value="#ff000000" />
<scene title="" width="1920" height="1080" exportWidth="1920" exportHeight="1080" precompose="dynamicResolution" bgcolor="#00000000" totalTime="3199" fps="30" modifiedTime="0" amver="1002592" ffver="106" am="com.alightcreative.motion/5.0.275" amplatform="android" retime="off" retimeAdaptFPS="false">
<shape id="634457" label="Circle 1" startTime="0" endTime="3199" fillType="color" mediaFillMode="stretch" s=".circle"><transform><location value="528.980042,540.000000,0.000000" /><scale value="1.225000,1.225000" /></transform><fillColor value="#ff0898ff" /><property name="size" type="vec2" value="100.000000,100.000000" /></shape>
<embedScene id="634478" label="Group and Mask 1" startTime="1666" endTime="3199" fillType="intrinsic" outTime="3231" mediaFillMode="stretch"><transform><location value="960.000000,540.000000,0.000000" /><pivot value="-431.019958,0.000000" /><scale value="0.892593,0.892593" /><rotation value="180.000000" /><opacity value="0.199219" /></transform><fillColor value="#ff000000" />
<scene title="" width="1920" height="1080" exportWidth="1920" exportHeight="1080" precompose="dynamicResolution" bgcolor="#00000000" totalTime="2132" fps="30" modifiedTime="0" amver="1002592" ffver="106" am="com.alightcreative.motion/5.0.275" amplatform="android" retime="off" retimeAdaptFPS="false">
<shape id="634475" label="Circle 4" startTime="0" endTime="2100" fillType="color" mediaFillMode="stretch" s=".circle"><transform lockAspectRatio="false"><location><kf t="0.015714" v="528.980042,810.000000,0.000000" /><kf t="0.730000" v="528.980042,540.000000,0.000000" e="cubicBezier 1.0 0.0 0.64 1.0" /></location><scale value="2.025001,1.320000" /></transform><fillColor value="#ff000000" /><property name="size" type="vec2" value="100.000000,100.000000" /></shape>
<shape id="634476" label="Circle 4 Copy" startTime="0" endTime="2132" fillType="color" blending="mask" mediaFillMode="stretch" s=".circle"><transform><location value="528.980042,540.000000,0.000000" /><scale value="1.350000,1.350000" /></transform><fillColor value="#ff1ed91e" /><property name="size" type="vec2" value="100.000000,100.000000" /></shape>
</scene>
</embedScene>
<shape id="634458" label="Arrow 1" startTime="0" endTime="1132" fillType="color" mediaFillMode="stretch" s=".arrow"><transform><location value="691.895996,328.907013,0.000000" /><pivot value="-161.500000,221.500000" /><scale value="0.419444,0.419444" /></transform><property name="start" type="vec2" value="-164.000000,78.000000" /><property name="end" type="vec2" value="-164.000000,360.000000" /><property name="lineWidth" type="float" value="27.000000" /><property name="headWidth" type="float" value="106.000000" /><property name="headLength" type="float" value="130.000000" /></shape>
<shape id="634463" label="Rounded Rectangle 1" startTime="1133" endTime="3199" fillType="color" mediaFillMode="stretch" s=".roundrect"><transform><location value="528.980042,540.000000,0.000000" /><scale value="0.450000,0.450000" /></transform><property name="size" type="vec2" value="100.000000,100.000000" /><property name="cornerRadius" type="float" value="32.000000" /></shape>
</scene>
</embedScene>
<shape id="634612" label="Circle 2" startTime="1666" endTime="3199" fillType="none" mediaFillMode="stretch" s=".circle"><transform><location value="960.000000,540.000000,0.000000" /><scale value="1.340000,1.340000" /><opacity value="0.000000" /></transform><fillColor value="#fffae965" /><effect id="com.alightcreative.effects.drawing.progress" locallyApplied="false"><property name="start" type="float"><kf t="0.010437" v="100.000000" /><kf t="0.988911" v="0.000000" e="cubicBezier 1.0 0.0 0.64 1.0" /></property><property name="end" type="float" value="100.000000" /></effect><path-stroke direction="centered" end-size="1.500000"><color value="#ffffffff" /><size value="29.500000" /></path-stroke><property name="size" type="vec2" value="100.000000,100.000000" /></shape>
<shape id="634613" label="Circle 1" startTime="1466" endTime="3199" fillType="color" mediaFillMode="stretch" s=".circle"><transform><location value="819.000000,564.500000,0.000000" /><scale><kf t="0.990190" v="0.365000,0.365000" e="cubicBezier 0.61 0.0 0.64 1.0" /><kf t="0.009810" v="0.000000,0.000000" /><kf t="0.124639" v="0.245000,0.245000" e="cubicBezier 0.4 0.0 0.26333332 1.0" /></scale></transform><fillColor value="#fffef6fa" /><effect id="com.alightcreative.effect.movealongpath3" locallyApplied="false"><property name="progress" type="float"><kf t="0.124639" v="1.000000" /><kf t="0.990190" v="0.000000" e="cubicBezier 1.0 0.0 0.64 1.0" /></property><property name="angle" type="float" value="0.000000" /><property name="tangent" type="bool" value="true" /><property name="inset" type="float" value="5.000000" /></effect><property name="size" type="vec2" value="100.000000,100.000000" /></shape>
</scene>
</embedScene>
</scene>"""

    /** The app's shape box (130 dp on a 360 dp canvas) in the pixels of the 1920-wide test scene. */
    private val box3 = 1920f * 130f / 360f

    private fun threeResult() = AlightXmlImporter.parse(three, 1920f)
    private fun threeClips() = threeResult().snapshot.tracks.flatMap { it.clips }

    @Test
    fun shapesFromRealFileHaveKindFillAndSize() {
        val r = threeResult()
        assertEquals("3", r.name)
        val clips = r.snapshot.tracks.flatMap { it.clips }
        assertEquals(17, clips.size)   // 11 shapes + 2 texts + 4 groups
        val rect = clips.first { it.name == "Rectangle 1" }
        assertEquals(LayerType.SHAPE, rect.type)
        assertEquals("RECT", rect.shapePattern)
        assertEquals(0xFF0E0E0EL, rect.textColor)
        assertEquals(com.example.model.FILL_SOLID, rect.fillMode)
        // size 100x100 x scale (10.7, 5.76); Alight's size is a HALF extent (centre to edge), so the
        // rendered size is 2 * 100 * 10.7 = 2140x1152 scene px, against the 130 dp box. (Until the ST
        // Intro parity fix the factor 2 was missing and every imported shape rendered at half size.)
        assertEquals(2140f / box3, rect.shapeScaleX, 0.001f)
        assertEquals(1152f / box3, rect.shapeScaleY, 0.001f)
        assertEquals(2140f / box3 * 100f, rect.scalePercent, 0.01f)
        assertEquals(0f, rect.transformX, 0.001f)   // centred in the scene
        assertEquals(0f, rect.transformY, 0.001f)
        assertEquals(5832L, rect.durationMs)
        assertEquals(null, rect.groupId)
    }

    // ---- ST Intro parity (2026-11-11): the layers below are the real file's Rectangle 1, Circle 3 and
    // "Channel Name" text, verbatim except their gradient / spacing effects, which do not affect geometry.
    // In Alight's own export the rect covers the frame, the circle grows past it, and the text is one
    // ~57 px line; in After Motion's build-90 export the rect covered 52 % of the frame, the text was
    // 15 px and wrapped to two lines. These tests pin the corrected unit readings.

    private val stIntro = scene(
        """<shape id="633194" label="Rectangle 1" startTime="0" endTime="8032" fillType="color" mediaFillMode="stretch" s=".rect"><transform><location value="960.000000,540.000000,0.000000" /><scale value="9.914999,5.577187" /></transform><fillColor value="#ff141620" /><property name="size" type="vec2" value="100.000000,100.000000" /></shape>
<shape id="633202" label="Circle 3" startTime="4333" endTime="8032" fillType="color" mediaFillMode="stretch" s=".circle"><transform><location value="960.000000,540.000000,0.000000" /><scale><kf t="0.004596" v="0.000000,0.000000" /><kf t="0.229792" v="11.745000,11.745000" e="cubicBezier 0.0 0.6401906 1.0 0.9554121" /></scale></transform><property name="size" type="vec2" value="100.000000,100.000000" /></shape>
<text id="633209" startTime="5166" endTime="8032" fillType="color" mediaFillMode="stretch" size="18.000000" font="googlefonts?name=Roboto&amp;weight=700" wrapWidth="512" align="center"><transform><location value="960.000000,726.500000,0.000000" /><scale value="0.839844,0.839844" /></transform><fillColor value="#ff9257ff" /><content>Channel Name</content></text>""",
        1920, 1080
    )

    @Test
    fun stIntroBackgroundCoversTheFrame() {
        val clips = AlightXmlImporter.parse(stIntro, 1920f).snapshot.tracks.flatMap { it.clips }
        val rect = clips.first { it.name == "Rectangle 1" }
        // Rendered width = shapeScaleX x the 130 dp box, as a share of the 1920 scene: 2*100*9.915 = 1983 px.
        val fracW = rect.shapeScaleX * box3 / 1920f
        assertEquals(1983f / 1920f, fracW, 0.01f)
        assertTrue(fracW > 0.95f && fracW < 1.1f)   // covers the frame (it was 0.52 before the fix)
        // Circle 3 grows from nothing to scale 11.745: its peak diameter must pass the frame width.
        val circle = clips.first { it.name == "Circle 3" }
        val peak = circle.keyframes[AnimProp.SCALE]!!.maxOf { it.a }
        val diameter = peak / 100f * box3
        assertTrue("peak diameter $diameter", diameter >= 1920f)
    }

    @Test
    fun stIntroTextHasAlightFontSizeAndWrapWidth() {
        val clips = AlightXmlImporter.parse(stIntro, 1920f).snapshot.tracks.flatMap { it.clips }
        val t = clips.first { it.type == LayerType.TEXT }
        assertEquals("Channel Name", t.text)
        // Font height at render: 18 * 0.839844 * 1920 / 512 = 56.7 scene px (Alight's export measures
        // about 57; the old reading gave 15.1, which is why the text was tiny and wrapped).
        val emPx = t.scalePercent / 100f * 0.11f * 1920f
        assertEquals(56.7f, emPx, 1.5f)
        // wrapWidth 512 scene px of a 1920 scene; at a ~57 px em the 12 glyphs need roughly 340 px,
        // so the line fits its box on one line, as in Alight.
        assertEquals(512f / 1920f, t.textWrapFrac, 0.001f)
        assertTrue(t.textWrapFrac * 1920f > 400f)
    }

    @Test
    fun importGeometryDoesNotDependOnThePreviewWidthBasis() {
        // Positions are stored in preview pixels, so a stored offset grows with the preview width —
        // but the rendered geometry (offset / basis, and every size) must be the same for any basis,
        // including 0, which falls back to scene pixels. Regression guard for the fresh-import bug:
        // the import used to run with whatever PreviewFrameRef happened to hold (0 before any export),
        // while the export used the screen's real preview width, shifting every off-centre layer.
        val xml = scene(
            """<shape id="1" label="Box" startTime="0" endTime="1000" fillType="color" s=".rect">
  <transform><location value="700,500,0" /><scale value="2,1" /></transform>
  <fillColor value="#ffffffff" />
  <property name="size" type="vec2" value="100,50" /></shape>
<text id="2" label="T" startTime="0" endTime="1000" size="20" font="googlefonts?name=Roboto&amp;weight=400" wrapWidth="512" align="center">
  <transform><location value="300,1500,0" /><scale value="1.5,1.5" /></transform>
  <content>Hi</content></text>""", 1080, 1920
        )
        fun clipsAt(pw: Float) = AlightXmlImporter.parse(xml, pw).snapshot.tracks.flatMap { it.clips }
        val base = clipsAt(1080f)
        for (pw in listOf(0f, 777f, 1920f)) {
            val basis = if (pw > 1f) pw else 1080f   // parse falls back to the scene width
            val clips = clipsAt(pw)
            val box0 = base.first { it.name == "Box" }
            val boxN = clips.first { it.name == "Box" }
            assertEquals(box0.shapeScaleX, boxN.shapeScaleX, 0.001f)
            assertEquals(box0.shapeScaleY, boxN.shapeScaleY, 0.001f)
            assertEquals(box0.scalePercent, boxN.scalePercent, 0.01f)
            assertEquals(box0.transformX / 1080f, boxN.transformX / basis, 0.001f)
            assertEquals(box0.transformY / 1080f, boxN.transformY / basis, 0.001f)
            val t0 = base.first { it.type == LayerType.TEXT }
            val tN = clips.first { it.type == LayerType.TEXT }
            assertEquals(t0.scalePercent, tN.scalePercent, 0.01f)
            assertEquals(t0.textWrapFrac, tN.textWrapFrac, 0.001f)
            assertEquals(t0.transformX / 1080f, tN.transformX / basis, 0.001f)
        }
    }

    @Test
    fun strokeOnlyCircleKeepsItsStrokeAndGroupLink() {
        val clips = threeClips()
        val group2 = clips.first { it.name == "Group 2" }
        val c = clips.first { it.name == "Circle 3" }
        assertEquals("CIRCLE", c.shapePattern)
        assertEquals(com.example.model.FILL_NONE, c.fillMode)
        assertEquals(0xFFFFFFFFL, c.strokeColor)
        // The app scales a stroke with its layer, so the stored dp keeps the stroke's share of the
        // shape's rendered width: 130 * 29.5 / (2 * 100). (Was a plain scene-px -> dp conversion,
        // which left imported strokes several times too thin once the layer scale was applied.)
        assertEquals(130f * 29.5f / 200f, c.strokeWidth, 0.001f)   // relative to the shape's full width
        assertEquals(1666L, c.startMs)
        assertEquals(1533L, c.durationMs)
        assertEquals(group2.id, c.groupId)
        val link = c.parent!!
        assertEquals(group2.id, link.id)
        // The link's ref point is the group's pivot (0, 26.62) in the children's own frame: rest scale / rotation.
        // (Build 93: the ref used to be pivot - child centre, which displaced every group child by about
        // half a frame in build 91's phone comparison; Alight's <pivot> is the anchor's offset FROM the
        // child scene's centre.)
        assertEquals(0f, link.refX, 0.001f)
        assertEquals(26.620605f, link.refY, 0.001f)
        assertEquals(100f, link.refScale, 0f)
        assertEquals(0f, link.refRot, 0f)
    }

    @Test
    fun groupsNestAndChildrenFollow() {
        val clips = threeClips()
        val group2 = clips.first { it.name == "Group 2" }
        assertEquals(LayerType.GROUP, group2.type)
        assertEquals(0L, group2.startMs)
        assertEquals(5833L, group2.durationMs)
        assertEquals(61.7748f, group2.scalePercent, 0.001f)
        // The group's stored position is its anchor: loc (960, 527.5) + pivot (0, 26.62), centre-relative
        // (build 93; before, the pivot was left out and the children carried the error instead).
        assertEquals(14.120605f, group2.transformY, 0.001f)
        // A group inside it is itself a child of it.
        val group1 = clips.first { it.name == "Group 1" }
        assertEquals(group2.id, group1.groupId)
        assertEquals(group2.id, group1.parent!!.id)
        assertEquals(109.3878f, group1.scalePercent, 0.001f)
        // The group's own keyframed move / turn, with the easing of the segment it ends at moved one key back.
        // The position keys are the anchor's: loc + pivot (-432.02, 0), centre-relative (build 93).
        val pos = group1.keyframes[AnimProp.POSITION]!!
        assertEquals(listOf(1016L, 1516L), pos.map { it.timeMs })
        assertEquals(-432.51996f, pos[0].a, 0.001f)
        assertEquals(2.0108f, pos[1].a, 0.001f)
        assertEquals(com.example.model.Easing(0.49f, 0f, 0.22333324f, 1f), pos[0].easing)
        val rot = group1.keyframes[AnimProp.ROTATION]!!
        assertEquals(listOf(0f, 180f), rot.map { it.a })
        assertEquals(com.example.model.Easing(0.49f, 0f, 0.22f, 1f), rot[0].easing)
        // Three levels: Group 2 > Group 1 > Group and Mask 1, whose own turn / opacity are plain fields.
        val mask = clips.first { it.name == "Group and Mask 1" }
        assertEquals(group1.id, mask.groupId)
        assertEquals(180f, mask.rotationDeg, 0.001f)
        assertEquals(19.9219f, mask.opacity, 0.001f)
        // The texts of the "Text" group belong to it.
        val textGroup = clips.first { it.name == "Text" && it.type == LayerType.GROUP }
        val texts = clips.filter { it.type == LayerType.TEXT }
        assertEquals(2, texts.size)
        assertTrue(texts.all { it.groupId == textGroup.id })
    }


    // ---- Build 93: group-child geometry (ST Intro parity). The scene below is verbatim from the real
    // ST Intro export: the "Group 1 Copy" pill group, the "Logo Here" group and the root-level end-card
    // text. Alight reference (build-91 phone comparison): at 2.0 s the pill is 781x157 px centred at
    // (960.5, 538.5); at the end card the logo sits at the frame centre with "Channel Name" on ONE line.

    private val stIntroGroups = """<?xml version='1.0' encoding='UTF-8' ?>
<scene title="ST Intro" width="1920" height="1080" exportWidth="1920" exportHeight="1080" precompose="dynamicResolution" bgcolor="#ff000000" totalTime="8032" fps="60" modifiedTime="1751521510607" amver="1002506" ffver="106" am="com.alightcreative.motion/5.0.199" amplatform="android" retime="freeze" retimeAdaptFPS="false">
<embedScene id="633599" label="Group 1 Copy" startTime="0" endTime="8032" fillType="intrinsic" mediaFillMode="stretch"><transform><location value="959.000000,544.500000,0.000000" /><pivot value="7.500000,-8.500000" /></transform><fillColor value="#ff000000" /><shadow direction="outside" enabled="false"><color value="#ff2f1363" /><opacity value="0.400000" /><size value="63.000000" /><offset value="25.000000,25.000000" /></shadow><scene title="" width="1920" height="1080" exportWidth="1920" exportHeight="1080" precompose="dynamicResolution" bgcolor="#00000000" totalTime="8032" fps="30" modifiedTime="0" amver="1002592" ffver="106" am="com.alightcreative.motion/5.0.271.1002592" amplatform="android" retime="off" retimeAdaptFPS="false"><shape id="633216" label="Circle 2" startTime="1900" endTime="5065" fillType="color" mediaFillMode="stretch" s=".roundrect"><transform><location><kf t="0.352607" v="962.033203,534.500000,0.000000" /><kf t="0.552923" v="1360.459595,534.500000,0.000000" e="cubicBezier 0.44 0.0 0.13 1.0" /><kf t="0.573776" v="1360.459595,534.500000,0.000000" /><kf t="0.774092" v="962.033203,534.500000,0.000000" e="cubicBezier 0.87 0.0 0.56 1.0" /></location><scale><kf t="0.721327" v="0.792888,0.792888" /><kf t="0.826540" v="0.007222,0.007222" e="cubicBezier 0.44 0.0 0.13 1.0" /></scale></transform><fillColor><kf t="0.721327" v="#ffffffff" /><kf t="0.826540" v="#ffffffff" /></fillColor><effect id="com.alightcreative.effects.scaleassist" locallyApplied="true"><property name="axis" type="int" value="3" /><property name="scale" type="float"><kf t="0.573776" v="1.000000" /><kf t="0.721327" v="1.175000" e="cubicBezier 0.87 0.0 0.56 1.0" /><kf t="0.826540" v="1.000000" e="cubicBezier 0.44 0.0 0.13 1.0" /></property><property name="damp" type="float" value="1.000000" /></effect><property name="size" type="vec2"><kf t="-0.595261" v="100.000000,100.000000" /><kf t="-0.384518" v="494.000000,100.000000" e="cubicBezier 0.43666667 0.0 0.12666665 1.0" /><kf t="0.247393" v="494.000000,100.000000" /><kf t="0.552923" v="100.000000,100.000000" e="cubicBezier 0.44 0.0 0.13 1.0" /></property><property name="cornerRadius" type="float" value="100.000000" /></shape><shape id="633218" label="Circle Copy" startTime="0" endTime="8032" fillType="color" mediaFillMode="stretch" s=".roundrect"><transform><location><kf t="0.375498" v="962.033264,534.500000,0.000000" /><kf t="0.541584" v="962.033264,534.500000,0.000000" e="cubicBezier 0.87 0.0 0.56 1.0" /><kf t="0.454432" v="560.038818,534.500000,0.000000" e="cubicBezier 0.36333334 0.0 0.28666666 1.0" /><kf t="0.462649" v="560.038818,534.500000,0.000000" /></location><scale><kf t="0.520792" v="0.792888,0.792888" /><kf t="0.562251" v="1.335000,1.335000" e="cubicBezier 0.44 0.0 0.13 1.0" /></scale></transform><fillColor><kf t="0.541584" v="#ffffffff" /><kf t="0.562251" v="#ff7a32ff" /></fillColor><effect id="com.alightcreative.effects.scaleassist" locallyApplied="true"><property name="axis" type="int" value="3" /><property name="scale" type="float"><kf t="0.462649" v="1.000000" /><kf t="0.520792" v="1.175000" e="cubicBezier 0.87 0.0 0.56 1.0" /><kf t="0.562251" v="1.000000" e="cubicBezier 0.44 0.0 0.13 1.0" /></property><property name="damp" type="float" value="1.000000" /></effect><property name="size" type="vec2"><kf t="0.001992" v="100.000000,100.000000" /><kf t="0.085035" v="494.000000,100.000000" e="cubicBezier 0.43666667 0.0 0.12666665 1.0" /><kf t="0.334039" v="494.000000,100.000000" /><kf t="0.454432" v="100.000000,100.000000" e="cubicBezier 0.44 0.0 0.13 1.0" /></property><property name="cornerRadius" type="float" value="100.000000" /></shape><shape id="633219" label="send-message.png" startTime="0" endTime="2899" fillType="media" fillImage="content://media/external/images/media/53288" mediaFillMode="stretch" s=".rect"><transform><location><kf t="0.000000" v="967.187012,534.500000,0.000000" /><kf t="0.235598" v="1276.017090,534.500000,0.000000" e="cubicBezier 0.44 0.0 0.13 1.0" /></location><scale><kf t="0.741290" v="0.066808,0.066808" /><kf t="0.890997" v="0.066808,0.066808" /><kf t="0.810280" v="0.040379,0.040379" /></scale><opacity><kf t="0.925492" v="1.000000" /><kf t="0.994481" v="0.000000" /></opacity></transform><effect id="com.alightcreative.solidcolor" locallyApplied="true"><property name="color" type="color" value="#ffb891ff" /><property name="alpha" type="float" value="1.000000" /><property name="blendMode" type="int" value="0" /></effect><property name="size" type="vec2" value="540.000000,540.000000" /></shape><text id="633220" startTime="666" endTime="2899" fillType="color" mediaFillMode="stretch" size="18.000000" font="imported?name=MontserratAlternates-Bold.otf" wrapWidth="512" align="left"><transform><location value="870.454651,532.517761,0.000000" /><scale value="0.792888,0.792888" /><opacity><kf t="0.903269" v="1.000000" /><kf t="0.992835" v="0.000000" /></opacity></transform><fillColor value="#ffb891ff" /><effect id="com.alightcreative.effects.textprogress" locallyApplied="true"><property name="start" type="float" value="0.000000" /><property name="end" type="float"><kf t="0.007613" v="0.000000" /><kf t="0.619794" v="1.000000" /></property><property name="cursor" type="int" value="0" /><property name="blink" type="bool" value="false" /></effect><content>Channel Name</content></text></scene></embedScene>
<embedScene id="633205" label="Logo Here" startTime="4133" endTime="8032" fillType="intrinsic" outTime="-2147482782" mediaFillMode="stretch"><transform><location value="960.000000,540.000000,0.000000" /><scale><kf t="0.012824" v="0.000000,0.000000" /><kf t="0.132342" v="0.380216,0.380216" e="cubicBezier 0.44 0.0 0.13 1.0" /></scale></transform><fillColor value="#ff000000" /><scene title="" width="1920" height="1080" exportWidth="1920" exportHeight="1080" precompose="dynamicResolution" bgcolor="#00000000" totalTime="3032" fps="30" modifiedTime="0" amver="1002592" ffver="106" am="com.alightcreative.motion/5.0.271.1002592" amplatform="android" retime="off" retimeAdaptFPS="false"><shape id="633203" label="images.png" startTime="0" endTime="3032" fillType="media" fillImage="content://media/external/images/media/1000033464" mediaFillMode="stretch" s=".rect"><transform><location value="960.000000,540.000000,0.000000" /></transform><property name="size" type="vec2" value="960.000000,503.500000" /></shape><shape id="633204" label="Mask" startTime="0" endTime="3032" fillType="color" blending="mask" mediaFillMode="stretch" s=".circle"><transform><location value="960.000000,540.000000,0.000000" /><scale value="3.235000,3.235000" /></transform><fillColor value="#ffd1c251" /><property name="size" type="vec2" value="100.000000,100.000000" /></shape></scene></embedScene>
<text id="633209" startTime="5166" endTime="8032" fillType="color" mediaFillMode="stretch" size="18.000000" font="googlefonts?name=Roboto&amp;weight=700" wrapWidth="512" align="center"><transform><location value="960.000000,726.500000,0.000000" /><scale value="0.839844,0.839844" /></transform><fillColor value="#ff9257ff" /><effect id="com.alightcreative.effects.textspacing" locallyApplied="true"><property name="letterspacing" type="float" value="0.130000" /><property name="linespacing" type="float" value="1.000000" /></effect><content>Channel Name</content></text>
</scene>"""

    @Test
    fun stIntroGroupChildrenLandWhereAlightPutsThem() {
        val clips = AlightXmlImporter.parse(stIntroGroups, 1920f).snapshot.tracks.flatMap { it.clips }
        val rig = ParentRig(clips) { c, t -> c.renderAt(t) }
        // The pill (Circle Copy) at 2.0 s: its location holds the first key (962.03, 534.5); composed
        // through the group's anchor (loc 959,544.5 + pivot 7.5,-8.5) the centre lands at (1, -1) stored
        // = (961, 539) absolute — Alight measured (960.5, 538.5). Before build 93 it landed at the
        // bottom-right corner.
        val pill = rig.adjusted(clips.first { it.name == "Circle Copy" }, 2000L)!!
        assertEquals(1f, pill.transformX, 6f)
        assertEquals(-1f, pill.transformY, 6f)
        // And it keeps its shape: 783x159 px on screen (the size keyframes animate 100x100 -> 494x100).
        // Before build 93 the height followed the width and it drew as a 783x783 disk.
        val boxW = 1920f * 130f / 360f
        assertEquals(783f, pill.shapeDrawScaleX * boxW, 40f)
        assertEquals(159f, pill.shapeDrawScaleY * boxW, 25f)
        // The end card: "Logo Here" scales 0 -> 0.380216 about the frame centre, so its Mask child
        // composes to the centre — and, being a mask, it is hidden (in build 91 it painted a yellow
        // circle over the card). Drawn diameter if it were shown: 0.9332 (its own size-based scale)
        // x 0.380216 (the group's) x the 693 px app box = 246 px, Alight's 647 x 0.380216.
        val mask = clips.first { it.name == "Mask" }
        assertFalse(mask.isVisible)
        // Build 94: the mask is flagged as a mask and linked to the layer below it (images.png), whose
        // picture the renderers cut with it — it is no longer just a hidden layer.
        assertTrue(mask.isMask)
        assertEquals(clips.first { it.name == "images.png" }.id, mask.maskTargetId)
        val atCard = rig.adjusted(mask, 5500L)!!
        assertEquals(0f, atCard.transformX, 6f)
        assertEquals(0f, atCard.transformY, 6f)
        assertEquals(246f, atCard.shapeDrawScaleX * (1920f * 130f / 360f), 8f)
    }

    @Test
    fun stIntroPillCornerRadiiMakeAStadium() {
        val clips = AlightXmlImporter.parse(stIntroGroups, 1920f).snapshot.tracks.flatMap { it.clips }
        val rig = ParentRig(clips) { c, t -> c.renderAt(t) }
        val pill = rig.adjusted(clips.first { it.name == "Circle Copy" }, 2000L)!!
        // cornerRadius 100 on a shape whose smaller half extent is 100: Alight clamps the radius to
        // that half extent, so rho = 1 and the stored value is the full 65 dp.
        assertEquals(65f, pill.shapeCornerRadius, 0.01f)
        // At 2.0 s the pill is drawn 783x159 (see the group test above): the frame-space corner
        // radius must come out as half the height (79.5 px) along BOTH axes — a stadium. Before
        // build 97 the painters used one local radius (the full box half) and the non-uniform draw
        // scale stretched the local circle into an ellipse with pointed ends.
        val boxW = 1920f * 130f / 360f
        val radii = pill.shapeCornerRadii(boxW / 2f)!!
        assertEquals(79.5f, radii.first * pill.shapeDrawScaleX, 15f)
        assertEquals(79.5f, radii.second * pill.shapeDrawScaleY, 15f)
        // Along the smaller axis the local radius saturates the box half (the local arc is a
        // half-ellipse cap there); along the wide axis it shrinks by the aspect.
        assertEquals(boxW / 2f, radii.second, 1f)
        assertTrue(radii.first < radii.second / 4f)
    }

    @Test
    fun cornerRadiiOfAUniformShapeAreTheStoredDp() {
        // rho = 0.5 on a square shape: both local radii are the stored dp (32.5) — the reading the
        // painters used before per-axis radii, so uniform shapes (and small radii generally) render
        // exactly as they did.
        val c = TimelineClip(
            id = "x", name = "Sq", type = LayerType.SHAPE, startMs = 0L, durationMs = 1000L,
            color = 0xFFFFFFFFL, shapePattern = "ROUNDED_RECT", shapeCornerRadius = 32.5f,
            shapeScaleX = 1f, shapeScaleY = 1f, scalePercent = 100f
        )
        val radii = c.shapeCornerRadii(65f)!!
        assertEquals(32.5f, radii.first, 0.01f)
        assertEquals(32.5f, radii.second, 0.01f)
        // No stored radius -> null (the painters fall back to their built-in corners).
        assertTrue(c.copy(shapeCornerRadius = -1f).shapeCornerRadii(65f) == null)
    }

    @Test
    fun stIntroEndCardTextBoxFitsItsLine() {
        val clips = AlightXmlImporter.parse(stIntroGroups, 1920f).snapshot.tracks.flatMap { it.clips }
        // The end-card "Channel Name" is a ROOT-level layer (not a group child): wrapWidth 512 of 1920.
        val text = clips.first { it.type == LayerType.TEXT && it.parent == null }
        assertEquals(512f / 1920f, text.textWrapFrac, 0.001f)
        // Its layout box lives in unscaled layout units (font 211.2 px) and is scaled by ~0.268 when
        // drawn, so the box must be ~512/0.268 wide there: "Channel Name" needs ~1400 layout px on one
        // line. Build 91 used the raw fraction and got a ~110 px on-screen box (a 2-letter column).
        val box = textLayoutBoxWidth(1920f, text.textWrapFrac, text.effectiveScalePercent / 100f, 52.8f)
        assertTrue(box > 1400f)
        assertEquals(483.6f, box * text.effectiveScalePercent / 100f, 20f)   // on screen: ~512 - padding
        // App-made text (no authored width) is untouched by the conversion.
        assertEquals(1920f * 0.9f - 2f * 52.8f, textLayoutBoxWidth(1920f, 0f, 0.2684f, 52.8f), 0.5f)
    }

    @Test
    fun nestedGroupWithSmallerSceneUsesTheChildBasis() {
        // Root 1920x1080; the group's own scene is 960x540 and the group scales it x2 about its anchor
        // (loc + pivot). Children are stored in the CHILD scene's basis and composed around the pivot.
        val xml = scene(
            """<embedScene id="g" label="G" startTime="0" endTime="2000" fillType="intrinsic">
<transform><location value="960.000000,540.000000,0.000000" /><pivot value="10.000000,20.000000" /><scale value="2.000000,2.000000" /></transform>
<scene title="" width="960" height="540" totalTime="2000" fps="30">
<shape id="c1" label="Centre" startTime="0" endTime="2000" fillType="color" s=".rect"><transform><location value="480.000000,270.000000,0.000000" /></transform><fillColor value="#ffff0000" /><property name="size" type="vec2" value="100.000000,100.000000" /></shape>
<shape id="c2" label="Corner" startTime="0" endTime="2000" fillType="color" s=".rect"><transform><location value="960.000000,540.000000,0.000000" /></transform><fillColor value="#ff00ff00" /><property name="size" type="vec2" value="100.000000,100.000000" /></shape>
</scene>
</embedScene>""", w = 1920, h = 1080
        )
        val clips = AlightXmlImporter.parse(xml, 1920f).snapshot.tracks.flatMap { it.clips }
        val g = clips.first { it.name == "G" }
        assertEquals(10f, g.transformX, 0.001f)    // anchor = loc + pivot, centre-relative
        assertEquals(20f, g.transformY, 0.001f)
        val rig = ParentRig(clips) { c, t -> c.renderAt(t) }
        // Child centre -> anchor + 2 x (centre - anchor-in-child-frame) = (10,20) + 2 x (-10,-20).
        val centre = rig.adjusted(clips.first { it.name == "Centre" }, 0L)!!
        assertEquals(-10f, centre.transformX, 0.01f)
        assertEquals(-20f, centre.transformY, 0.01f)
        // Child bottom-right corner (960,540 of a 960x540 scene) -> (10,20) + 2 x (470,250) = (950,520),
        // i.e. just inside the root's own bottom-right (960,540 stored): the x2 scene covers the frame.
        val corner = rig.adjusted(clips.first { it.name == "Corner" }, 0L)!!
        assertEquals(950f, corner.transformX, 0.01f)
        assertEquals(520f, corner.transformY, 0.01f)
    }

    @Test
    fun groupWindowRemapsChildTimes() {
        val clips = threeClips()
        // "Group and Mask 1" plays its 3231 ms of content in 1533 ms: Circle 4 (0..2100 inside) lands at 1666..2662.
        val circle4 = clips.first { it.name == "Circle 4" }
        assertEquals(1666L, circle4.startMs)
        assertEquals(996L, circle4.durationMs)
        // The "Text" group starts its content at 33 ms and stretches 5932 ms onto 5832 ms.
        val copy = clips.first { it.name == "Download Copy" }
        assertEquals(3048L, copy.startMs)
        assertEquals(2227L, copy.durationMs)
    }

    @Test
    fun keyframedShapeSizeBecomesScaleKeyframesWithEasing() {
        val outline = threeClips().first { it.name == "Outline" }
        assertEquals("ROUNDED_RECT", outline.shapePattern)
        assertEquals(com.example.model.FILL_NONE, outline.fillMode)
        assertEquals(0xFF009DFFL, outline.strokeColor)
        assertEquals(130f * 10f / (2f * 522f), outline.strokeWidth, 0.001f)   // stroke relative to the shape (see Circle 3)
        // Fallback size = the first keyframe (522x136) x the transform scale 1.122605, x2 (half extent).
        assertEquals(522f * 2f * 1.122605f / box3, outline.shapeScaleX, 0.001f)
        assertEquals(136f * 2f * 1.122605f / box3, outline.shapeScaleY, 0.001f)
        val keys = outline.keyframes[AnimProp.SCALE]!!
        assertEquals(listOf(1016L, 1516L, 3183L, 3850L), keys.map { it.timeMs })
        assertEquals(522f * 2f * 1.122605f / box3 * 100f, keys[0].a, 0.01f)
        assertEquals(136f * 2f * 1.122605f / box3 * 100f, keys[1].a, 0.01f)
        assertEquals(136f * 2f * 1.122605f / box3 * 100f, keys[2].a, 0.01f)
        assertEquals(372f * 2f * 1.122605f / box3 * 100f, keys[3].a, 0.01f)
        // The height rides in `b` (build 93): the size's height is 136 in every keyframe, so every key
        // carries the same height % — and the layer keeps its 522x136 -> 136x136 aspect while animating.
        assertEquals(136f * 2f * 1.122605f / box3 * 100f, keys[0].b, 0.01f)
        assertEquals(136f * 2f * 1.122605f / box3 * 100f, keys[3].b, 0.01f)
        // The easing sits on the Alight keyframe a segment ends at; the app wants it on the one it starts at.
        assertEquals(com.example.model.Easing(0.49f, 0f, 0.22f, 1f), keys[0].easing)
        assertTrue(keys[1].easing.isLinear)
        assertEquals(com.example.model.Easing(0.49f, 0f, 0.22f, 1f), keys[2].easing)
        assertTrue(keys[3].easing.isLinear)
    }

    @Test
    fun keyframedTransformScaleBecomesSizeKeyframes() {
        // "Circle 1" (the one at 1466 ms) has only its transform scale keyframed, out of order in the file.
        val c = threeClips().first { it.name == "Circle 1" && it.keyframes.containsKey(AnimProp.SCALE) }
        val keys = c.keyframes[AnimProp.SCALE]!!
        assertEquals(listOf(17L, 216L, 1716L), keys.map { it.timeMs })   // of its 1733 ms
        assertEquals(0f, keys[0].a, 0.001f)
        assertEquals(200f * 0.245f / box3 * 100f, keys[1].a, 0.01f)   // x2: size is a half extent
        assertEquals(200f * 0.365f / box3 * 100f, keys[2].a, 0.01f)
        assertEquals(com.example.model.Easing(0.4f, 0f, 0.26333332f, 1f), keys[0].easing)
        assertEquals(com.example.model.Easing(0.61f, 0f, 0.64f, 1f), keys[1].easing)
        assertTrue(keys[2].easing.isLinear)
    }

    @Test
    fun arrowBecomesAThinRectangle() {
        val clips = threeClips()
        val group1 = clips.first { it.name == "Group 1" }
        val arrow = clips.first { it.name == "Arrow 1" }
        assertEquals(LayerType.SHAPE, arrow.type)
        assertEquals("RECT", arrow.shapePattern)
        assertEquals(group1.id, arrow.groupId)
        // The shaft runs from (690.8, 268.7) to (690.8, 387.0) scene px: straight down, 118.28 px long, 27 x scale thick.
        assertEquals(90f, arrow.rotationDeg, 0.01f)
        assertEquals(-269.153f, arrow.transformX, 0.5f)
        assertEquals(-212.142f, arrow.transformY, 0.5f)
        assertEquals(118.283f / box3, arrow.shapeScaleX, 0.001f)
        assertEquals(27f * 0.419444f / box3, arrow.shapeScaleY, 0.0005f)
    }

    @Test
    fun laterPassEffectsStayWarningsOnTheRealFile() {
        val r = threeResult()
        assertTrue(r.warnings.any { it.contains("solidcolor") })
        assertTrue(r.warnings.any { it.contains("movealongpath3") })
        // Both mask layers of the file sit on a shape / text layer, so since build 94 they are applied
        // (linked to the layer below, see LayerMaskImportTest) and no mask warning is left. Before build
        // 94 they were imported hidden with an "is a mask in Alight" warning.
        assertTrue(r.warnings.none { it.contains("Mask layer") })
        assertTrue(r.warnings.any { it.contains("plain line") })
    }

    @Test
    fun roundRectCornerRadiusIsImportedAsBoxDp() {
        val r = threeResult()
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val radii = clips.filter { it.shapePattern == "ROUNDED_RECT" }.map { it.shapeCornerRadius }
        assertTrue(radii.isNotEmpty())
        assertTrue(radii.all { it >= 0f })
        // Real file "3": size 100 x 100 with cornerRadius 32 -> rho = 32 / 100 of the smaller half
        // extent -> 65 * 32 / 100 dp of the shape box (build 97: the radius is stored against the
        // shape's smaller side, not its width).
        assertTrue(radii.any { abs(it - 65f * 32f / 100f) < 0.01f })
        // Size 522 x 136 with cornerRadius 373: Alight clamps to half the smaller side — the smaller
        // half extent itself (136) in size units — so rho = 1 and the shape is a stadium: 65 dp.
        // (Before build 97 the clamp halved a second time and tied the radius to the width: 16.9 dp,
        // nearly square corners on a shape Alight draws fully rounded.)
        assertTrue(radii.any { abs(it - 65f) < 0.01f })
        // No shape keeps the old "not supported" warning, and shapes without the property stay unset (-1).
        assertFalse(r.warnings.any { it.contains("corner radius") })
        assertTrue(clips.filter { it.shapePattern != "ROUNDED_RECT" }.all { it.shapeCornerRadius == -1f })
    }

    @Test
    fun keyframedCornerRadiusUsesTheFirstValueWithANote() {
        val xml = scene(
            """<shape id="9" label="Pill" startTime="0" endTime="1000" fillType="color" mediaFillMode="stretch" s=".roundrect">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <property name="size" type="vec2" value="200,100" />
  <property name="cornerRadius" type="float"><kf t="0.000000" v="20.000000" /><kf t="1.000000" v="80.000000" /></property></shape>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        // First value 20 (smaller half extent 100, so no clamp) -> 65 * 20 / 100.
        assertEquals(65f * 20f / 100f, c.shapeCornerRadius, 0.01f)
        assertTrue(r.warnings.any { it.contains("corner radius") && it.contains("first value") })
    }

    @Test
    fun cornerRadiusOnAPlainRectIsIgnored() {
        val xml = scene(
            """<shape id="9" label="Sq" startTime="0" endTime="1000" fillType="color" mediaFillMode="stretch" s=".rect">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <property name="size" type="vec2" value="200,100" />
  <property name="cornerRadius" type="float" value="20.000000" /></shape>"""
        )
        val c = AlightXmlImporter.parse(xml, 1080f).snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(-1f, c.shapeCornerRadius, 0f)
    }

    @Test
    fun motionblur4BecomesMotionBlurWithTuneAsStrength() {
        val xml = scene(
            """<shape id="9" label="Box" startTime="0" endTime="2000" fillType="color" mediaFillMode="stretch" s=".rect">
  <transform><location><kf t="0.000000" v="340,960,0" /><kf t="1.000000" v="740,960,0" /></location><scale value="1,1" /></transform>
  <effect id="com.alightcreative.effects.motionblur4" locallyApplied="true">
    <property name="tune" type="float" value="0.200000" />
    <property name="usePos" type="bool" value="true" />
    <property name="useScale" type="bool" value="true" />
    <property name="useAngle" type="bool" value="true" />
  </effect>
  <property name="size" type="vec2" value="200,100" /></shape>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(1, c.effects.size)
        assertEquals(ClipFxLibrary.MOTION_BLUR, c.effects[0].type)
        assertEquals(20f, c.effects[0].values[0], 0.01f)   // tune 0.2 -> Strength 20
        assertEquals(1f, c.effects[0].values[1], 0f)   // usePos
        assertEquals(1f, c.effects[0].values[2], 0f)   // useScale
        assertEquals(1f, c.effects[0].values[3], 0f)   // useAngle
        // Position blur is reproduced; scale / rotation blur is reported, not guessed.
        assertTrue(r.warnings.any { it.contains("motionblur4") && it.contains("approximately") })
        assertTrue(r.warnings.any { it.contains("scale") && it.contains("rotation") })
        assertFalse(r.warnings.any { it.contains("motionblur4") && it.contains("skipped") })
    }

    @Test
    fun motionblurWithoutPositionMapsToRadialOnly() {
        val xml = scene(
            """<shape id="9" label="Box" startTime="0" endTime="2000" fillType="color" mediaFillMode="stretch" s=".rect">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <effect id="com.alightcreative.effects.motionblur" locallyApplied="true">
    <property name="tune" type="float" value="1.000000" />
    <property name="usePos" type="bool" value="false" />
    <property name="useScale" type="bool" value="true" />
    <property name="useAngle" type="bool" value="false" />
  </effect>
  <property name="size" type="vec2" value="200,100" /></shape>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        // Scale-only now maps (radial blur) instead of being skipped; only the scale flag is on.
        assertEquals(1, c.effects.size)
        assertEquals(ClipFxLibrary.MOTION_BLUR, c.effects[0].type)
        assertEquals(listOf(100f, 0f, 1f, 0f), c.effects[0].values)
        assertTrue(r.warnings.any { it.contains("motionblur") && it.contains("radial") })
        assertFalse(r.warnings.any { it.contains("motionblur") && it.contains("skipped") })
    }

    @Test
    fun motionblur2WithOnlyTuneHasNoScaleWarning() {
        val xml = scene(
            """<shape id="9" label="Box" startTime="0" endTime="2000" fillType="color" mediaFillMode="stretch" s=".rect">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <effect id="com.alightcreative.effects.motionblur2" locallyApplied="true">
    <property name="tune" type="float" value="1.000000" />
  </effect>
  <property name="size" type="vec2" value="200,100" /></shape>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(1, c.effects.size)
        assertEquals(ClipFxLibrary.MOTION_BLUR, c.effects[0].type)
        assertEquals(100f, c.effects[0].values[0], 0.01f)
        assertEquals(listOf(100f, 1f, 0f, 0f), c.effects[0].values)   // no flags listed: position only
        assertTrue(r.warnings.any { it.contains("motionblur2") && it.contains("approximately") })
        assertFalse(r.warnings.any { it.contains("scale") && it.contains("rotation") })
    }

    @Test
    fun repeatBecomesRepeatWithOffsetInPreviewPx() {
        // The corpus case: 5 copies of a horizontal line, 93 scene px apart, vertically.
        val xml = scene(
            """<shape id="9" label="Horizontal Lines" startTime="0" endTime="2000" fillType="color" mediaFillMode="stretch" s=".rect">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <effect id="com.alightcreative.effects.repeat" locallyApplied="true">
    <property name="count" type="float" value="5.000000" />
    <property name="time" type="float" value="0.000000" />
    <property name="offset" type="vec2" value="0.000000,93.000000" />
    <property name="angle" type="float" value="0.000000" />
    <property name="scale" type="float" value="1.000000" />
    <property name="alpha" type="float" value="1.000000" />
  </effect>
  <property name="size" type="vec2" value="200,100" /></shape>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(1, c.effects.size)
        assertEquals(ClipFxLibrary.REPEAT, c.effects[0].type)
        // 1080 px preview of a 1080 px wide scene: the offset stays 93.
        assertEquals(listOf(5f, 0f, 93f, 0f, 100f, 100f), c.effects[0].values)
        assertFalse(r.warnings.any { it.contains("repeat") && it.contains("skipped") })
    }

    @Test
    fun repeatOffsetScalesWithTheScene() {
        // A 1920 px wide scene on a 1080 px preview: 146 scene px become 82.125 preview px.
        val xml = scene(
            """<shape id="9" label="Vertical Lines" startTime="0" endTime="2000" fillType="color" mediaFillMode="stretch" s=".rect">
  <transform><location value="960,540,0" /><scale value="1,1" /></transform>
  <effect id="com.alightcreative.effects.repeat" locallyApplied="true">
    <property name="count" type="float" value="8.000000" />
    <property name="time" type="float" value="0.000000" />
    <property name="offset" type="vec2" value="146.000000,0.000000" />
    <property name="angle" type="float" value="0.000000" />
    <property name="scale" type="float" value="1.000000" />
    <property name="alpha" type="float" value="1.000000" />
  </effect>
  <property name="size" type="vec2" value="200,100" /></shape>""", 1920, 1080
        )
        val c = AlightXmlImporter.parse(xml, 1080f).snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(listOf(8f, 82.125f, 0f, 0f, 100f, 100f), c.effects[0].values)
    }

    @Test
    fun repeatWithTimeWarnsApproximately() {
        val xml = scene(
            """<shape id="9" label="Box" startTime="0" endTime="2000" fillType="color" mediaFillMode="stretch" s=".rect">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <effect id="com.alightcreative.effects.repeat" locallyApplied="true">
    <property name="count" type="float" value="3.000000" />
    <property name="time" type="float" value="0.500000" />
    <property name="offset" type="vec2" value="10.000000,0.000000" />
    <property name="angle" type="float" value="0.000000" />
    <property name="scale" type="float" value="1.000000" />
    <property name="alpha" type="float" value="1.000000" />
  </effect>
  <property name="size" type="vec2" value="200,100" /></shape>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(ClipFxLibrary.REPEAT, c.effects[0].type)
        assertTrue(r.warnings.any { it.contains("repeat") && it.contains("time offset") })
        assertFalse(r.warnings.any { it.contains("repeat") && it.contains("skipped") })
    }

    @Test
    fun repeatKeyframedCountUsesFirstValue() {
        val xml = scene(
            """<shape id="9" label="Box" startTime="0" endTime="2000" fillType="color" mediaFillMode="stretch" s=".rect">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <effect id="com.alightcreative.effects.repeat" locallyApplied="true">
    <property name="count" type="float"><kf t="0.000000" v="2.000000" /><kf t="1.000000" v="8.000000" /></property>
    <property name="time" type="float" value="0.000000" />
    <property name="offset" type="vec2" value="0.000000,50.000000" />
    <property name="angle" type="float" value="0.000000" />
    <property name="scale" type="float" value="1.000000" />
    <property name="alpha" type="float" value="1.000000" />
  </effect>
  <property name="size" type="vec2" value="200,100" /></shape>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(2f, c.effects[0].values[0], 0f)
        assertTrue(r.warnings.any { it.contains("keyframed count") })
    }

    @Test
    fun repeatRadialAndScatterImport() {
        // repeat.radial / repeat.scatter used to be skipped with a warning ("the app cannot repeat a
        // layer in a circle / scattered") — which is why ST Intro's particle layers (build 94 parity
        // round) imported as single shapes and drew nothing at all. They now map onto the Repeat
        // Radial / Repeat Scatter effects (see RepeatEval).
        val xml = scene(
            """<shape id="9" label="Circle 1" startTime="0" endTime="2000" fillType="color" mediaFillMode="stretch" s=".circle">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <effect id="com.alightcreative.effects.repeat.radial" locallyApplied="true">
    <property name="count" type="float" value="4.000000" />
    <property name="radius" type="float" value="223.000000" />
  </effect>
  <effect id="com.alightcreative.effects.repeat.scatter" locallyApplied="true">
    <property name="count" type="float" value="10.000000" />
    <property name="radius" type="float" value="1242.000000" />
  </effect>
  <property name="size" type="vec2" value="200,100" /></shape>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(2, c.effects.size)
        assertEquals(ClipFxLibrary.REPEAT_RADIAL, c.effects[0].type)
        // [count, radius, startAngle, sweep, orientation, baseScale %, alpha %, angle, scale %, alt...]
        assertEquals(listOf(4f, 223f, 0f, 360f, 0f, 100f, 100f, 0f, 100f, 0f, 0f, 0f), c.effects[0].values)
        assertEquals(ClipFxLibrary.REPEAT_SCATTER, c.effects[1].type)
        // [count, radius, offsetX, offsetY, seed, scale %, alpha %, angle, evolution..., alt...]
        assertEquals(
            listOf(10f, 1242f, 0f, 0f, 0f, 0f, 100f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f),
            c.effects[1].values
        )
        assertFalse(r.warnings.any { it.contains("repeat.radial") || it.contains("repeat.scatter") })
    }

    /** The three particle layers of the real ST Intro, verbatim (ids 633223 / 633206 / 633208). */
    private val stIntroParticles = """
<shape id="633223" label="Circle 1" startTime="0" endTime="5365" fillType="color" mediaFillMode="stretch" s=".circle"><transform><location value="960.000000,540.000000,0.000000" /><scale value="0.255000,0.255000" /></transform><effect id="com.alightcreative.effects.repeat.scatter" locallyApplied="true"><property name="count" type="float" value="10.000000" /><property name="radius" type="float" value="1242.000000" /><property name="evolution" type="float"><kf t="0.002982" v="1.790000" /><kf t="0.997204" v="2.450000" /></property><property name="scatterSeed" type="float" value="0.230000" /><property name="offset" type="vec2" value="-778.000000,210.000000" /><property name="angle" type="float" value="0.000000" /><property name="scale" type="float" value="0.130000" /><property name="alpha" type="float" value="1.000000" /><property name="fillColor" type="color" value="#ffb891ff" /><property name="blend" type="float" value="1.160000" /><property name="colorAltCopies" type="bool" value="true" /><property name="start" type="float" value="0.000000" /><property name="end" type="float" value="1.000000" /><property name="phase" type="float" value="0.000000" /><property name="easeIn" type="float" value="0.000000" /><property name="easeOut" type="float" value="0.000000" /><property name="overlap" type="float" value="0.000000" /><property name="shape" type="int" value="0" /><property name="invert" type="bool" value="false" /><property name="randomOrder" type="bool" value="false" /><property name="seed" type="float" value="0.000000" /></effect><property name="size" type="vec2" value="100.000000,100.000000" /></shape>
<shape id="633206" label="Line 1" startTime="4466" endTime="5599" fillType="none" mediaFillMode="stretch" s=".line"><transform><location value="960.000000,540.000000,0.000000" /><scale value="1.450000,1.450000" /><rotation value="39.512196" /><opacity><kf t="0.721094" v="1.000000" /><kf t="0.985878" v="0.000000" /></opacity></transform><fillColor value="#ff5bfc63" /><effect id="com.alightcreative.effects.repeat.radial" locallyApplied="true"><property name="count" type="float" value="4.000000" /><property name="radius" type="float" value="223.000000" /><property name="orientation" type="float" value="-45.000000" /><property name="startAngle" type="float" value="-175.000000" /><property name="sweep" type="float" value="353.000000" /><property name="baseScale" type="float" value="0.350000" /><property name="angle" type="float" value="0.000000" /><property name="scale" type="float" value="1.000000" /><property name="alpha" type="float" value="1.000000" /><property name="fillColor" type="color" value="#ff2d1ef6" /><property name="blend" type="float" value="0.000000" /><property name="colorAltCopies" type="bool" value="false" /><property name="start" type="float" value="0.000000" /><property name="end" type="float" value="1.000000" /><property name="phase" type="float" value="0.000000" /><property name="easeIn" type="float" value="0.000000" /><property name="easeOut" type="float" value="0.000000" /><property name="overlap" type="float" value="0.000000" /><property name="shape" type="int" value="0" /><property name="invert" type="bool" value="false" /><property name="randomOrder" type="bool" value="false" /><property name="seed" type="float" value="0.000000" /></effect><effect id="com.alightcreative.effects.drawing.progress" locallyApplied="true"><property name="start" type="float"><kf t="0.015004" v="100.000000" /><kf t="0.661959" v="0.000000" e="cubicBezier 0.0 0.44 0.37 1.0" /></property><property name="end" type="float"><kf t="0.338041" v="100.000000" /><kf t="0.985878" v="0.000000" e="cubicBezier 0.0 0.43676466 0.37 1.0" /></property></effect><path-stroke direction="centered" end-size="1.500000"><color value="#ffb891ff" /><size value="20.000000" /></path-stroke><property name="p1" type="vec2" value="-100.000000,100.000000" /><property name="p2" type="vec2" value="100.000000,-100.000000" /></shape>
<shape id="633208" label="Line 1 Copy" startTime="4366" endTime="5147" fillType="none" speed="1.450000" mediaFillMode="stretch" s=".line"><transform><location value="960.000000,540.000000,0.000000" /><scale><kf t="0.985878" v="1.645000,1.645000" /><kf t="0.338923" v="1.240000,1.240000" /></scale><rotation value="-7.682922" /><opacity><kf t="0.721094" v="1.000000" /><kf t="0.985878" v="0.000000" /></opacity></transform><fillColor value="#ff5bfc63" /><effect id="com.alightcreative.effects.repeat.radial" locallyApplied="true"><property name="count" type="float" value="4.000000" /><property name="radius" type="float" value="223.000000" /><property name="orientation" type="float" value="-45.000000" /><property name="startAngle" type="float" value="-175.000000" /><property name="sweep" type="float" value="353.000000" /><property name="baseScale" type="float" value="0.350000" /><property name="angle" type="float" value="0.000000" /><property name="scale" type="float" value="1.000000" /><property name="alpha" type="float" value="1.000000" /><property name="fillColor" type="color" value="#ff2d1ef6" /><property name="blend" type="float" value="0.000000" /><property name="colorAltCopies" type="bool" value="false" /><property name="start" type="float" value="0.000000" /><property name="end" type="float" value="1.000000" /><property name="phase" type="float" value="0.000000" /><property name="easeIn" type="float" value="0.000000" /><property name="easeOut" type="float" value="0.000000" /><property name="overlap" type="float" value="0.000000" /><property name="shape" type="int" value="0" /><property name="invert" type="bool" value="false" /><property name="randomOrder" type="bool" value="false" /><property name="seed" type="float" value="0.000000" /></effect><effect id="com.alightcreative.effects.drawing.progress" locallyApplied="true"><property name="start" type="float"><kf t="0.015004" v="100.000000" /><kf t="0.661959" v="0.000000" e="cubicBezier 0.0 0.44 0.37 1.0" /></property><property name="end" type="float"><kf t="0.338041" v="100.000000" /><kf t="0.985878" v="0.000000" e="cubicBezier 0.0 0.43676466 0.37 1.0" /></property></effect><path-stroke direction="centered" end-size="1.500000"><color value="#ff767676" /><size value="8.000000" /></path-stroke><property name="p1" type="vec2" value="-100.000000,100.000000" /><property name="p2" type="vec2" value="100.000000,-100.000000" /></shape>
"""

    @Test
    fun stIntroParticlesExpandToVisibleCopies() {
        val r = AlightXmlImporter.parse(scene(stIntroParticles, 1920, 1080), 1920f)
        val clips = r.snapshot.tracks.flatMap { it.clips }
        assertFalse(r.warnings.any { it.contains("not supported yet") && it.contains("repeat") })
        val box = 1920f * 130f / 360f

        // --- The 2.0 s dots: "Circle 1" (a 51 px circle at the scene centre) + repeat.scatter.
        val circle = clips.first { it.name == "Circle 1" }
        val scatter = circle.effects.single { it.type == ClipFxLibrary.REPEAT_SCATTER }
        assertEquals(10f, scatter.values[0], 0.001f)              // count
        assertEquals(1242f, scatter.values[1], 0.001f)            // radius (preview px, k = 1)
        assertEquals(-778f, scatter.values[2], 0.001f)            // offset x
        assertEquals(210f, scatter.values[3], 0.001f)             // offset y
        assertEquals(0.23f, scatter.values[4], 0.001f)            // scatter seed
        assertEquals(13f, scatter.values[5], 0.001f)              // copy scale floor %
        assertEquals(2.45f, scatter.values[11], 0.001f)           // evolution, last keyframe
        assertEquals(1f, scatter.values[14], 0.001f)              // alternating colour on
        val at2s = circle.renderAt(2000L)
        val dots = RepeatEval.copiesOf(at2s, timeMs = 2000L)
        assertEquals(10, dots.size)
        var biggest = 0f
        var inFrame = 0
        dots.forEachIndexed { i, dot ->
            assertTrue(dot.opacity > 0f)
            val diameter = dot.shapeDrawScaleX * box           // base circle is 51 px; copies 0.13..1 x
            assertTrue(diameter in 5f..52f)
            if (diameter > biggest) biggest = diameter
            // Centres stay inside the effect's disc around the layer + offset (scene coords).
            val cx = 960f + dot.transformX
            val cy = 540f + dot.transformY
            val d = kotlin.math.hypot(cx - (960f - 778f), cy - (540f + 210f))
            assertTrue(d <= 1242.5f)
            if (cx in 0f..1920f && cy in 0f..1080f) inFrame++
            // colorAltCopies: odd copies take the effect's lavender, even ones keep the white fill.
            assertEquals(if (i % 2 == 1) 0xFFB891FFL else 0xFFFFFFFFL, dot.textColor)
        }
        // Alight's export at 2.0 s shows dots of 9..45 px; the field's copies span the same range.
        // (How many land inside the frame depends on the field's exact draw, which is Alight's own
        // generator — see RepeatEval's doc — so only a floor is asserted here.)
        assertTrue(biggest >= 28f)
        assertTrue(inFrame >= 2)
        // The field drifts with the evolution track (1.79 -> 2.45): 0.5 s and 2.0 s differ.
        val at05 = RepeatEval.copiesOf(circle.renderAt(500L), timeMs = 500L)
        val moved = dots.indices.count { i ->
            kotlin.math.hypot(dots[i].transformX - at05[i].transformX, dots[i].transformY - at05[i].transformY) > 20f
        }
        assertTrue(moved >= 4)

        // --- The end-card dashes: "Line 1" / "Line 1 Copy" + repeat.radial (ring of 4, no centre copy).
        for ((name, t) in listOf("Line 1" to 5500L, "Line 1 Copy" to 5000L)) {
            val line = clips.first { it.name == name }
            val radial = line.effects.single { it.type == ClipFxLibrary.REPEAT_RADIAL }
            assertEquals(4f, radial.values[0], 0.001f)
            assertEquals(223f, radial.values[1], 0.001f)
            assertEquals(-175f, radial.values[2], 0.001f)         // start angle
            assertEquals(353f, radial.values[3], 0.001f)          // sweep
            assertEquals(-45f, radial.values[4], 0.001f)          // orientation
            assertEquals(35f, radial.values[5], 0.001f)           // base scale %
            val at = line.renderAt(t)
            assertTrue(at.opacity > 0f)                           // mid-fade, still visible
            val copies = RepeatEval.copiesOf(at, timeMs = t)
            assertEquals(4, copies.size)
            // Frame-measured against Alight's export: anchors sit at TWICE the radius, at angles
            // startAngle + i * sweep/count measured clockwise from straight up.
            val expected = listOf(-175.0, -86.75, 1.5, 89.75)
            val seen = mutableListOf<Double>()
            copies.forEach { copy ->
                val dx = (copy.transformX - at.transformX).toDouble()
                val dy = (copy.transformY - at.transformY).toDouble()
                assertEquals(446.0, kotlin.math.hypot(dx, dy), 1.0)
                seen += Math.toDegrees(kotlin.math.atan2(dx, -dy))
                assertEquals(0.35f, copy.scalePercent / at.scalePercent, 0.01f)
            }
            expected.forEach { e -> assertTrue(seen.any { kotlin.math.abs(it - e) < 0.5 }) }
        }
    }

    @Test
    fun scatterCountAboveTheDrawCapIsClampedWithAWarning() {
        // The collected winkoko preset scatters 150 copies; the app draws at most 50 (RepeatEval).
        val xml = scene(
            """<shape id="s1" label="Field" startTime="0" endTime="20032" fillType="color" s=".circle"><transform><location value="540.000000,960.000000,0.000000" /></transform><fillColor value="#ffffffff" />
<effect id="com.alightcreative.effects.repeat.scatter" locallyApplied="true"><property name="count" type="float" value="150.000000" /><property name="radius" type="float" value="1300.000000" /></effect>
</shape>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.single().clips.single()
        assertEquals(50f, c.effects.single().values[0], 0.001f)
        assertTrue(r.warnings.any { it.contains("repeat.scatter") && it.contains("clamped") })
        assertEquals(50, RepeatEval.copiesOf(c).size)
    }

    @Test
    fun mediaFillShapeKeepsItsPictureUri() {
        val xml = scene(
            """<shape id="9" label="pic.png" startTime="0" endTime="1000" fillType="media" fillImage="content://media/external/images/media/53288" mediaFillMode="stretch" s=".rect">
  <transform><location value="540,960,0" /><scale value="2,1" /></transform>
  <property name="size" type="vec2" value="100,50" /></shape>""", 1080, 1920
        )
        val c = AlightXmlImporter.parse(xml, 1080f).snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(com.example.model.FILL_IMAGE, c.fillMode)
        assertEquals("content://media/external/images/media/53288", c.fillImageUri)
        assertEquals(com.example.model.IMG_STRETCH, c.fillImageMode)
        // size 100x50 at scale (2,1), half extent: the rendered size is 400x100 scene px.
        assertEquals(400f / (1080f * 130f / 360f), c.shapeScaleX, 0.001f)
        assertEquals(100f / (1080f * 130f / 360f), c.shapeScaleY, 0.001f)
    }

    @Test
    fun pathShapeIsSkippedWithAWarning() {
        val xml = scene(
            """<shape id="9" label="Scribble" startTime="0" endTime="1000" fillType="none">
  <transform><location value="540,960,0" /></transform><path d="M 0 0L 10 10" /></shape>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        assertTrue(r.snapshot.tracks.flatMap { it.clips }.isEmpty())
        assertTrue(r.warnings.any { it.contains("freehand path") })
    }

    @Test
    fun easingParses() {
        assertEquals(
            com.example.model.Easing(0.49f, 0f, 0.22f, 1f),
            AlightXmlImporter.parseEasing("cubicBezier 0.49 0.0 0.22 1.0")
        )
        assertEquals(null, AlightXmlImporter.parseEasing(""))
        assertEquals(null, AlightXmlImporter.parseEasing("nonsense"))
    }

    // ---- Pass 2: fills, spacing, scale assist, baked oscillation -------------------------------------------------

    @Test
    fun solidColorSetsTextColourAndMultipliesOpacity() {
        val xml = scene(
            """<text id="1" label="A" startTime="0" endTime="1000" size="30" font="googlefonts?name=Roboto&amp;weight=400">
  <transform><location value="540,960,0" /><scale value="1,1" /><opacity value="0.5" /></transform>
  <effect id="com.alightcreative.solidcolor" locallyApplied="true"><property name="color" type="color" value="#ff112233" /><property name="alpha" type="float" value="0.5" /><property name="blendMode" type="int" value="0" /></effect>
  <content>A</content></text>"""
        )
        val c = AlightXmlImporter.parse(xml, 1080f).snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(0xFF112233L, c.textColor)
        assertEquals(25f, c.opacity, 0.001f)   // layer 50 % x the effect's alpha 0.5
    }

    @Test
    fun keyframedSolidColorBecomesFillColorKeyframes() {
        val xml = scene(
            """<shape id="1" label="Box" startTime="0" endTime="2000" fillType="color" s=".rect">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <fillColor value="#ffffffff" />
  <effect id="com.alightcreative.solidcolor" locallyApplied="true"><property name="color" type="color"><kf t="0" v="#ff000000" /><kf t="1" v="#ffffffff" /></property><property name="alpha" type="float" value="1" /><property name="blendMode" type="int" value="0" /></effect>
  <property name="size" type="vec2" value="100,100" /></shape>"""
        )
        val c = AlightXmlImporter.parse(xml, 1080f).snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(0xFF000000L, c.textColor)   // fallback = the first keyframe
        val keys = c.keyframes[AnimProp.FILL_COLOR]!!
        assertEquals(listOf(0L, 2000L), keys.map { it.timeMs })
        assertEquals(packColor(0xFF000000L), keys[0].a to keys[0].b)
        assertEquals(packColor(0xFFFFFFFFL), keys[1].a to keys[1].b)
    }

    @Test
    fun solidColorOnTheRealStrokeOnlyShapeKeepsTheStrokeColour() {
        // The "Outline" of the real file "3": solidcolor flashes white -> green, but its only paint is the stroke.
        val outline = threeClips().first { it.name == "Outline" }
        assertEquals(0xFF009DFFL, outline.strokeColor)
        val keys = outline.keyframes[AnimProp.FILL_COLOR]!!
        assertEquals(packColor(0xFFFFFFFFL), keys[0].a to keys[0].b)
        assertEquals(packColor(0xFF14FD6AL), keys[1].a to keys[1].b)
    }

    @Test
    fun gradientOverlayBecomesTheGradientFill() {
        val xml = scene(
            """<shape id="1" label="Box" startTime="0" endTime="1000" fillType="color" s=".rect">
  <transform><location value="500,500,0" /><scale value="10,10" /></transform>
  <fillColor value="#ff000000" />
  <effect id="com.alightcreative.gradientoverlay2" locallyApplied="true"><property name="color1" type="color" value="#ff0000ff" /><property name="color2" type="color" value="#ffff0000" /><property name="angle" type="float" value="0" /><property name="offset" type="vec2" value="0,0" /><property name="scale" type="float" value="1" /><property name="alpha" type="float" value="1" /><property name="gradType" type="int" value="0" /><property name="blendMode" type="int" value="0" /></effect>
  <property name="size" type="vec2" value="100,100" /></shape>""", 1000, 1000
        )
        val c = AlightXmlImporter.parse(xml, 1000f).snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(com.example.model.FILL_GRADIENT, c.fillMode)
        assertEquals(com.example.model.GRAD_LINEAR, c.gradType)
        assertEquals(0xFF0000FFL, c.gradColor1)
        assertEquals(0xFFFF0000L, c.gradColor2)
        // Angle 0, no offset, scale 1: the gradient runs from the middle of the left edge to the right one.
        assertEquals(0f, c.gradX1, 0.001f)
        assertEquals(0.5f, c.gradY1, 0.001f)
        assertEquals(1f, c.gradX2, 0.001f)
        assertEquals(0.5f, c.gradY2, 0.001f)
    }

    @Test
    fun textSpacingMapsToLetterSpacingAndLineHeight() {
        val xml = scene(
            """<text id="1" label="A" startTime="0" endTime="1000" size="30" font="googlefonts?name=Roboto&amp;weight=400">
  <effect id="com.alightcreative.effects.textspacing" locallyApplied="true"><property name="letterspacing" type="float" value="0.13" /><property name="linespacing" type="float" value="1.0" /></effect>
  <content>A</content></text>"""
        )
        val c = AlightXmlImporter.parse(xml, 1080f).snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(13f, c.letterSpacing, 0.01f)   // 0.13 of the font size, in percent
        assertEquals(100f, c.lineHeightPercent, 0.01f)
    }

    @Test
    fun textProgressIsStoredAsAnAnimator() {
        val xml = scene(
            """<text id="1" label="A" startTime="0" endTime="2000" size="30" font="googlefonts?name=Roboto&amp;weight=400">
  <effect id="com.alightcreative.effects.textprogress" locallyApplied="true"><property name="start" type="float" value="0" /><property name="end" type="float"><kf t="0" v="0" /><kf t="0.6" v="1" /></property><property name="cursor" type="int" value="0" /><property name="blink" type="bool" value="false" /></effect>
  <content>HELLO</content></text>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val a = c.textAnimators.first { it.effectId == AlightXmlImporter.TEXTPROGRESS }
        assertEquals(2, a.props["end"]!!.keys.size)
        assertEquals(listOf(0f), a.props["cursor"]!!.value)
        // cursor = 0 (no cursor): the reveal is drawn now, so no textprogress warning stays.
        assertFalse(r.warnings.any { it.contains("textprogress") })
    }

    @Test
    fun textProgressCursorStaysAWarning() {
        // The exact effect block of the ST Intro template (1c30cfdb…) but with a cursor switched on:
        // storage stays complete, only the cursor keeps a (precise) warning.
        val xml = scene(
            """<text id="1" label="A" startTime="666" endTime="2899" size="18" font="imported?name=MontserratAlternates-Bold.otf" align="left">
  <effect id="com.alightcreative.effects.textprogress" locallyApplied="true"><property name="start" type="float" value="0.000000" /><property name="end" type="float"><kf t="0.007613" v="0.000000" /><kf t="0.619794" v="1.000000" /></property><property name="cursor" type="int" value="1" /><property name="blink" type="bool" value="true" /></effect>
  <content>Channel Name</content></text>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val a = c.textAnimators.first { it.effectId == AlightXmlImporter.TEXTPROGRESS }
        assertEquals(2, a.props["end"]!!.keys.size)
        assertEquals(listOf(1f), a.props["cursor"]!!.value)
        assertTrue(r.warnings.any { it.contains("textprogress cursor is not drawn yet") })
        assertFalse(r.warnings.any { it.contains("textprogress is saved") })
    }

    @Test
    fun drawingProgressBecomesStrokeTrimKeyframes() {
        val xml = scene(
            """<shape id="1" label="Ring" startTime="0" endTime="2000" fillType="none" s=".circle">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <effect id="com.alightcreative.effects.drawing.progress" locallyApplied="false"><property name="start" type="float"><kf t="0" v="100" /><kf t="1" v="0" /></property><property name="end" type="float" value="100" /></effect>
  <path-stroke direction="centered"><color value="#ffffffff" /><size value="20" /></path-stroke>
  <property name="size" type="vec2" value="100,100" /></shape>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        // Percent -> fractions; the fields hold the first values, the animation is on the STROKE_TRIM track.
        assertEquals(1f, c.strokeTrimStart, 0.001f)
        assertEquals(1f, c.strokeTrimEnd, 0.001f)
        assertFalse(c.strokeTrimFill)
        val keys = c.keyframes[AnimProp.STROKE_TRIM]!!
        assertEquals(listOf(0L, 2000L), keys.map { it.timeMs })
        assertEquals(1f, keys[0].a, 0.001f)
        assertEquals(1f, keys[0].b, 0.001f)
        assertEquals(0f, keys[1].a, 0.001f)
        assertEquals(1f, keys[1].b, 0.001f)
        assertFalse(r.warnings.any { it.contains("drawing.progress") })
    }

    @Test
    fun drawingProgressConstantValuesBecomeAStaticTrim() {
        val xml = scene(
            """<shape id="1" label="Box" startTime="0" endTime="1000" fillType="color" s=".rect">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <fillColor value="#ffffffff" />
  <effect id="com.alightcreative.effects.drawing.progress" locallyApplied="false"><property name="start" type="float" value="25" /><property name="end" type="float" value="75" /></effect>
  <path-stroke direction="centered"><color value="#ffffffff" /><size value="10" /></path-stroke>
  <property name="size" type="vec2" value="100,100" /></shape>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(0.25f, c.strokeTrimStart, 0.001f)
        assertEquals(0.75f, c.strokeTrimEnd, 0.001f)
        assertFalse(c.keyframes.containsKey(AnimProp.STROKE_TRIM))
        assertFalse(r.warnings.any { it.contains("drawing.progress") })
    }

    @Test
    fun drawingProgressOnALineTrimsTheFillAndUnionsBothTracks() {
        // The "ST Intro" file's lines: start runs 100 -> 0 first, end follows 100 -> 0 (a comet sweep).
        val xml = scene(
            """<shape id="7" label="Line 1" startTime="0" endTime="2000" fillType="none" s=".line">
  <transform><location value="960,540,0" /><scale value="1,1" /></transform>
  <effect id="com.alightcreative.effects.drawing.progress" locallyApplied="true"><property name="start" type="float"><kf t="0.015004" v="100.000000" /><kf t="0.661959" v="0.000000" e="cubicBezier 0.0 0.44 0.37 1.0" /></property><property name="end" type="float"><kf t="0.338041" v="100.000000" /><kf t="0.985878" v="0.000000" e="cubicBezier 0.0 0.43676466 0.37 1.0" /></property></effect>
  <path-stroke direction="centered" end-size="1.500000"><color value="#ffb891ff" /><size value="20.000000" /></path-stroke>
  <property name="p1" type="vec2" value="-100.000000,100.000000" />
  <property name="p2" type="vec2" value="100.000000,-100.000000" /></shape>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        // A line's paint is its path: the trim gates the baked rectangle's fill.
        assertTrue(c.strokeTrimFill)
        assertEquals(1f, c.strokeTrimStart, 0.001f)
        assertEquals(1f, c.strokeTrimEnd, 0.001f)
        val keys = c.keyframes[AnimProp.STROKE_TRIM]!!
        // The union of both tracks' key times; each track is sampled at the other's times.
        assertEquals(listOf(30L, 676L, 1324L, 1972L), keys.map { it.timeMs })
        assertEquals(1f, keys[0].a, 0.001f)
        assertEquals(1f, keys[0].b, 0.001f)
        assertEquals(0.5007f, keys[1].a, 0.001f)   // start, halfway between its own keys
        assertEquals(1f, keys[1].b, 0.001f)
        assertEquals(0f, keys[2].a, 0.001f)
        assertEquals(0.5f, keys[2].b, 0.001f)      // end, halfway between its own keys
        assertEquals(0f, keys[3].a, 0.001f)
        assertEquals(0f, keys[3].b, 0.001f)
        // The easing of a segment comes from the Alight keyframe it ends at (start track first).
        assertTrue(keys[0].easing.isLinear)
        assertEquals(com.example.model.Easing(0f, 0.44f, 0.37f, 1f), keys[1].easing)
        assertEquals(com.example.model.Easing(0f, 0.43676466f, 0.37f, 1f), keys[2].easing)
        assertTrue(keys[3].easing.isLinear)
        assertFalse(r.warnings.any { it.contains("drawing.progress") })
    }

    @Test
    fun drawingProgressOnTextStaysAWarning() {
        val xml = scene(
            """<text id="1" label="A" startTime="0" endTime="2000" size="30" font="googlefonts?name=Roboto&amp;weight=400">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <effect id="com.alightcreative.effects.drawing.progress" locallyApplied="true"><property name="start" type="float" value="0" /><property name="end" type="float" value="50" /></effect>
  <content>A</content></text>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(0f, c.strokeTrimStart, 0f)
        assertEquals(1f, c.strokeTrimEnd, 0f)
        assertTrue(r.warnings.any { it.contains("drawing.progress") })
    }

    @Test
    fun drawingProgressFromTheRealFileTrimsTheCircleStrokes() {
        val r = threeResult()
        val clips = r.snapshot.tracks.flatMap { it.clips }
        // "Circle 3" of the file "3": start keyframed 100 -> 0 (eased), end constant 100, on a stroked circle.
        val circle = clips.first { it.name == "Circle 3" }
        assertTrue(circle.strokeWidth > 0f)
        assertFalse(circle.strokeTrimFill)
        assertEquals(1f, circle.strokeTrimEnd, 0.001f)
        val keys = circle.keyframes[AnimProp.STROKE_TRIM]!!
        assertEquals(2, keys.size)
        assertEquals(1f, keys.first().a, 0.001f)
        assertEquals(0f, keys.last().a, 0.001f)
        assertEquals(1f, keys.first().b, 0.001f)
        assertEquals(com.example.model.Easing(1f, 0f, 0.63666666f, 1f), keys.first().easing)
        assertFalse(r.warnings.any { it.contains("drawing.progress") })
    }

    @Test
    fun scaleAssistComposesIntoTheScaleKeyframes() {
        // The pop of the "ST Intro" file: 1 -> 1.175 -> 1 on top of a constant text scale.
        val xml = scene(
            """<text id="1" label="A" startTime="0" endTime="2000" size="30" font="googlefonts?name=Roboto&amp;weight=400">
  <transform><location value="540,960,0" /><scale value="2,2" /></transform>
  <effect id="com.alightcreative.effects.scaleassist" locallyApplied="true"><property name="axis" type="int" value="3" /><property name="scale" type="float"><kf t="0.25" v="1" /><kf t="0.5" v="1.175" /><kf t="0.75" v="1" /></property><property name="damp" type="float" value="1" /></effect>
  <content>A</content></text>"""
        )
        val c = AlightXmlImporter.parse(xml, 1080f).snapshot.tracks.flatMap { it.clips }[0]
        val base = 60f * 1080f / 512f / (0.11f * 1080f) * 100f   // text units of sceneWidth / 512 px (see sizeTimesScaleBecomesScalePercent)
        val keys = c.keyframes[AnimProp.SCALE]!!
        assertEquals(listOf(0L, 500L, 1000L, 1500L, 2000L), keys.map { it.timeMs })
        assertEquals(base, keys[0].a, 0.01f)
        assertEquals(base * 1.175f, keys[2].a, 0.01f)
        assertEquals(base, keys[4].a, 0.01f)
    }

    @Test
    fun scaleAssistConstantFactorScalesAShape() {
        val xml = scene(
            """<shape id="1" label="Box" startTime="0" endTime="1000" fillType="color" s=".rect">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <fillColor value="#ffffffff" />
  <effect id="com.alightcreative.effects.scaleassist" locallyApplied="true"><property name="axis" type="int" value="3" /><property name="scale" type="float" value="2" /><property name="damp" type="float" value="1" /></effect>
  <property name="size" type="vec2" value="100,50" /></shape>"""
        )
        val c = AlightXmlImporter.parse(xml, 1080f).snapshot.tracks.flatMap { it.clips }[0]
        val box = 1080f * 130f / 360f
        // size 100x50 renders 200x100 (half extent), then scaleassist x2 on both axes: 400x200.
        assertEquals(400f / box, c.shapeScaleX, 0.001f)
        assertEquals(200f / box, c.shapeScaleY, 0.001f)
        assertEquals(400f / box * 100f, c.scalePercent, 0.01f)
    }

    @Test
    fun oscillateBakesPositionKeyframes() {
        // Angle 90 = straight down in Alight's scene pixels; mag 100 px, 1 Hz, layer of 2 s.
        val xml = scene(
            """<shape id="1" label="Box" startTime="0" endTime="2000" fillType="color" s=".rect">
  <transform><location value="600,500,0" /><scale value="1,1" /></transform>
  <fillColor value="#ffffffff" />
  <effect id="com.alightcreative.effects.oscillate" locallyApplied="false"><property name="angle" type="float" value="90" /><property name="freq" type="float" value="1" /><property name="mag" type="float" value="100" /><property name="type" type="int" value="0" /></effect>
  <property name="size" type="vec2" value="100,100" /></shape>""", 1000, 1000
        )
        val c = AlightXmlImporter.parse(xml, 1000f).snapshot.tracks.flatMap { it.clips }[0]
        val pos = c.keyframes[AnimProp.POSITION]!!
        assertTrue(pos.size > 20)
        assertEquals(0L, pos.first().timeMs)
        assertEquals(2000L, pos.last().timeMs)
        assertEquals(0f, pos.first().b, 0.01f)               // sin(0) = 0
        assertTrue(pos.all { abs(it.a - 100f) < 0.01f })     // the base X (600 - 500) never changes
        assertTrue(pos.maxOf { it.b } > 95f)                 // +mag at the quarter period
        assertTrue(pos.minOf { it.b } < -95f)                // -mag at three quarters
    }

    @Test
    fun oscillateComposesWithAKeyframedLocation() {
        // The layer itself moves 0 -> 200 px while the oscillation (angle 0, mag 50) rides on top.
        val xml = scene(
            """<shape id="1" label="Box" startTime="0" endTime="2000" fillType="color" s=".rect">
  <transform><location><kf t="0" v="500,500,0" /><kf t="1" v="700,500,0" /></location><scale value="1,1" /></transform>
  <fillColor value="#ffffffff" />
  <effect id="com.alightcreative.effects.oscillate" locallyApplied="false"><property name="angle" type="float" value="0" /><property name="freq" type="float" value="1" /><property name="mag" type="float" value="50" /><property name="type" type="int" value="0" /></effect>
  <property name="size" type="vec2" value="100,100" /></shape>""", 1000, 1000
        )
        val c = AlightXmlImporter.parse(xml, 1000f).snapshot.tracks.flatMap { it.clips }[0]
        val pos = c.keyframes[AnimProp.POSITION]!!
        assertEquals(0f, pos.first().a, 0.01f)               // base 0 + sin(0) * 50
        assertEquals(200f, pos.last().a, 0.01f)              // base 200 + sin(4 PI) * 50
        assertTrue(pos.any { it.a > 165f })                  // base + full swing near 1.25 s
        assertTrue(pos.any { it.a < 60f })                   // base - full swing near 0.75 s
    }

    @Test
    fun swingBakesRotationKeyframes() {
        val xml = scene(
            """<shape id="1" label="Box" startTime="0" endTime="2000" fillType="color" s=".rect">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <fillColor value="#ffffffff" />
  <effect id="com.alightcreative.effects.swing" locallyApplied="false"><property name="freq" type="float" value="1" /><property name="a1" type="float" value="-10" /><property name="a2" type="float" value="10" /><property name="phase" type="float" value="0" /><property name="type" type="int" value="0" /></effect>
  <property name="size" type="vec2" value="100,100" /></shape>"""
        )
        val c = AlightXmlImporter.parse(xml, 1080f).snapshot.tracks.flatMap { it.clips }[0]
        val rot = c.keyframes[AnimProp.ROTATION]!!
        assertTrue(rot.size > 20)
        assertEquals(0f, rot.first().a, 0.01f)
        assertTrue(rot.maxOf { it.a } > 9.5f)
        assertTrue(rot.minOf { it.a } < -9.5f)
    }

    @Test
    fun swingOnAGroupBakesIntoTheGroupRotation() {
        val xml = scene(
            """<embedScene id="2" label="G" startTime="0" endTime="2000" fillType="intrinsic" outTime="2000">
  <transform><location value="540,960,0" /></transform>
  <effect id="com.alightcreative.effects.swing2" locallyApplied="false"><property name="a1" type="float" value="-10" /><property name="a2" type="float" value="10" /><property name="freq" type="float" value="1" /><property name="phase" type="float" value="0" /><property name="type" type="int" value="0" /></effect>
  <scene title="" width="1080" height="1920" totalTime="2000" fps="30">
  <shape id="3" label="Inner" startTime="0" endTime="2000" fillType="color" s=".rect"><transform><location value="540,960,0" /><scale value="1,1" /></transform><fillColor value="#ffffffff" /><property name="size" type="vec2" value="100,100" /></shape>
  </scene>
</embedScene>"""
        )
        val clips = AlightXmlImporter.parse(xml, 1080f).snapshot.tracks.flatMap { it.clips }
        val g = clips.first { it.type == LayerType.GROUP }
        val rot = g.keyframes[AnimProp.ROTATION]!!
        assertTrue(rot.maxOf { it.a } > 9.5f)
    }

    @Test
    fun solidColorOnAGroupStaysAWarning() {
        val xml = scene(
            """<embedScene id="2" label="G" startTime="0" endTime="1000" fillType="intrinsic" outTime="1000">
  <transform><location value="540,960,0" /></transform>
  <effect id="com.alightcreative.solidcolor" locallyApplied="true"><property name="color" type="color" value="#ff000000" /><property name="alpha" type="float" value="1" /><property name="blendMode" type="int" value="0" /></effect>
  <scene title="" width="1080" height="1920" totalTime="1000" fps="30">
  <shape id="3" label="Inner" startTime="0" endTime="1000" fillType="color" s=".rect"><transform><location value="540,960,0" /><scale value="1,1" /></transform><fillColor value="#ffffffff" /><property name="size" type="vec2" value="100,100" /></shape>
  </scene>
</embedScene>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        assertTrue(r.warnings.any { it.contains("solidcolor on a group") })
    }

    // ---- Pass 3: native fx mappings, wipe masks, bakes, audio --------------------------------------------------

    private fun shapeWith(fx: String, extra: String = ""): String = scene(
        """<shape id="1" label="Box" startTime="0" endTime="2000" fillType="color" s=".rect">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <fillColor value="#ffffffff" />
  $fx
  <property name="size" type="vec2" value="100,100" /></shape>$extra"""
    )

    private fun onlyClip(xml: String) =
        AlightXmlImporter.parse(xml, 1080f).snapshot.tracks.flatMap { it.clips }[0]

    @Test
    fun gaussianBlurBecomesNativeBlur() {
        val c = onlyClip(shapeWith(
            """<effect id="com.alightcreative.effects.gaussianblur" locallyApplied="true"><property name="strength" type="float" value="0.5" /></effect>"""
        ))
        assertEquals(1, c.effects.size)
        assertEquals(com.example.model.ClipFxLibrary.BLUR, c.effects[0].type)
        assertEquals(25f, c.effects[0].values[0], 0.01f)   // 0.5 of Alight's 0..1 onto 0..50
    }

    @Test
    fun exposureBecomesNativeExposure() {
        // The values of the "Subscriber & follow animation" file.
        val c = onlyClip(shapeWith(
            """<effect id="com.alightcreative.effects.exposure" locallyApplied="true"><property name="exposure" type="float" value="0.16" /><property name="gamma" type="float" value="1.285" /><property name="offset" type="float" value="-0.69" /></effect>"""
        ))
        val fx = c.effects.first { it.type == com.example.model.ClipFxLibrary.EXPOSURE }
        assertEquals(0.16f, fx.values[0], 0.001f)
        assertEquals(-69f, fx.values[1], 0.01f)
        assertEquals(128.5f, fx.values[2], 0.01f)
    }

    @Test
    fun keyframedExposureUsesFirstValueWithNote() {
        val xml = shapeWith(
            """<effect id="com.alightcreative.effects.exposure" locallyApplied="true"><property name="exposure" type="float"><kf t="0" v="0.5" /><kf t="1" v="1.5" /></property><property name="gamma" type="float" value="1" /><property name="offset" type="float" value="0" /></effect>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.first { it.type == com.example.model.ClipFxLibrary.EXPOSURE }
        assertEquals(0.5f, fx.values[0], 0.001f)
        assertTrue(r.warnings.any { it.contains("Effect exposure: keyframed exposure") })
    }

    @Test
    fun wipeBecomesAKeyframedRectMask() {
        // A bar of the "Animated Graph" file: the wipe opens downward from start 1.006 to 0.373, end stays 1.
        val c = onlyClip(shapeWith(
            """<effect id="com.alightcreative.effects.wipe2" locallyApplied="true"><property name="start" type="float"><kf t="0" v="1.006" /><kf t="0.25" v="0.373" e="cubicBezier 0.17 0.0 0.0 1.0" /></property><property name="end" type="float" value="1" /><property name="angle" type="float" value="90" /><property name="feather" type="float" value="0" /></effect>"""
        ))
        assertEquals(1, c.masks.size)
        val m = c.masks[0]
        assertEquals(com.example.model.MaskShape.RECT, m.shape)
        assertEquals(listOf(0L, 500L, 2000L), m.keys.map { it.timeMs })   // + the end key holding the final band
        val k0 = m.keys[0]
        assertEquals(0f, k0.state.w, 0.001f)          // band [1.006, 1] is empty: nothing visible yet
        assertEquals(1.003f, k0.state.cy, 0.001f)
        assertEquals(0.17f, k0.easing.x1, 0.001f)     // the easing of the AM keyframe the segment ends at
        val k1 = m.keys[1]
        assertEquals(0.627f, k1.state.w, 0.001f)      // band [0.373, 1] along the wipe direction
        assertEquals(2.5f, k1.state.h, 0.001f)        // long enough across the band, even rotated
        assertEquals(0.6865f, k1.state.cy, 0.001f)    // band centre
        assertEquals(90f, k1.state.rot, 0.001f)
        assertEquals(0.627f, m.keys[2].state.w, 0.001f)
    }

    @Test
    fun wipeFullyOpenAddsNoMask() {
        val c = onlyClip(shapeWith(
            """<effect id="com.alightcreative.effects.wipe2" locallyApplied="true"><property name="start" type="float" value="0" /><property name="end" type="float" value="1" /><property name="angle" type="float" value="90" /><property name="feather" type="float" value="0" /></effect>"""
        ))
        assertTrue(c.masks.isEmpty())
    }

    @Test
    fun edgeGlowBecomesNativeGlow() {
        val c = onlyClip(shapeWith(
            """<effect id="com.alightcreative.effects.edgeglow" locallyApplied="true"><property name="threshold" type="float" value="0.23" /><property name="strength" type="float" value="0.3" /><property name="spread" type="float" value="1.5" /><property name="fillColor" type="color" value="#ffffffff" /></effect>"""
        ))
        val fx = c.effects.first { it.type == com.example.model.ClipFxLibrary.GLOW }
        assertEquals(23f, fx.values[0], 0.01f)
        assertEquals(49.5f, fx.values[1], 0.01f)
        assertEquals(30f, fx.values[2], 0.01f)
    }

    @Test
    fun darkGlowOnAGroupBecomesGlowWithApproximationNote() {
        val xml = scene(
            """<embedScene id="2" label="Glow 1" startTime="0" endTime="2000" fillType="intrinsic" outTime="2000">
  <transform><location value="540,960,0" /></transform>
  <effect id="com.alightcreative.effects.darkglow" locallyApplied="true"><property name="color" type="color" value="#ffff5566" /><property name="threshold" type="float" value="0.473" /><property name="strength" type="float" value="0.25" /><property name="spread" type="float" value="1" /><property name="alpha" type="float" value="0.75" /></effect>
  <scene title="" width="1080" height="1920" totalTime="2000" fps="30">
  <shape id="3" label="Inner" startTime="0" endTime="2000" fillType="color" s=".rect"><transform><location value="540,960,0" /><scale value="1,1" /></transform><fillColor value="#ffffffff" /><property name="size" type="vec2" value="100,100" /></shape>
  </scene>
</embedScene>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val g = r.snapshot.tracks.flatMap { it.clips }.first { it.type == LayerType.GROUP }
        val fx = g.effects.first { it.type == com.example.model.ClipFxLibrary.GLOW }
        assertEquals(47.3f, fx.values[0], 0.01f)
        assertEquals(25f, fx.values[2], 0.01f)
        assertTrue(r.warnings.any { it.contains("darkglow is approximated") })
    }

    @Test
    fun gaussianBlurOnAGroupAppliesToTheGroup() {
        // The "Shadow" group of the "ST Intro" file carries a gaussianblur of strength 0.505.
        val xml = scene(
            """<embedScene id="2" label="Shadow" startTime="0" endTime="2000" fillType="intrinsic" outTime="2000">
  <transform><location value="540,960,0" /></transform>
  <effect id="com.alightcreative.effects.gaussianblur" locallyApplied="true"><property name="strength" type="float" value="0.505" /></effect>
  <scene title="" width="1080" height="1920" totalTime="2000" fps="30">
  <shape id="3" label="Inner" startTime="0" endTime="2000" fillType="color" s=".rect"><transform><location value="540,960,0" /><scale value="1,1" /></transform><fillColor value="#ffffffff" /><property name="size" type="vec2" value="100,100" /></shape>
  </scene>
</embedScene>"""
        )
        val g = AlightXmlImporter.parse(xml, 1080f).snapshot.tracks.flatMap { it.clips }.first { it.type == LayerType.GROUP }
        val fx = g.effects.first { it.type == com.example.model.ClipFxLibrary.BLUR }
        assertEquals(25.25f, fx.values[0], 0.01f)
    }

    @Test
    fun shakeBakesSeededPositionKeys() {
        // The shake of the "Subscriber & follow animation" file: mag pulses 0 -> 20 -> 0 at 4.14 Hz.
        val xml = shapeWith(
            """<effect id="com.alightcreative.effects.shake2" locallyApplied="true"><property name="mag" type="float"><kf t="0.294947" v="0" /><kf t="0.414722" v="0" /><kf t="0.354706" v="20" /></property><property name="freq" type="float" value="4.14" /><property name="evolution" type="float" value="0" /><property name="seed" type="float" value="0" /><property name="angle" type="float" value="45" /><property name="slack" type="float" value="0.25" /><property name="zshake" type="float" value="0" /></effect>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val pos = c.keyframes[AnimProp.POSITION]!!
        assertTrue(pos.size >= 8)   // the flat stretches simplify away; the pulse's keys stay
        assertTrue(pos.all { abs(it.a) <= 24f && abs(it.b) <= 24f })   // never beyond the shake's reach
        assertTrue(pos.maxOf { Math.hypot(it.a.toDouble(), it.b.toDouble()) } > 8.0)
        val again = AlightXmlImporter.parse(xml, 1080f).snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(pos, again.keyframes[AnimProp.POSITION])          // the seed makes it deterministic
        assertTrue(r.warnings.any { it.contains("Effect shake: its slack") })
    }

    @Test
    fun flipOnAGroupBecomes3DRotationKeys() {
        // "Group 1" of the "Subscriber" file: axis 24 degrees, angle 28.7 -> 0 -> -28.1.
        val xml = scene(
            """<embedScene id="2" label="Group 1" startTime="0" endTime="2000" fillType="intrinsic" outTime="2000">
  <transform><location value="540,960,0" /></transform>
  <effect id="com.alightcreative.effects.flip3" locallyApplied="true"><property name="angle" type="float"><kf t="0" v="28.7" /><kf t="0.5" v="0" /><kf t="1" v="-28.1" /></property><property name="axis" type="float" value="24" /><property name="crop" type="float" value="1" /><property name="zdist" type="float" value="0" /><property name="solid" type="bool" value="false" /><property name="enableLight" type="bool" value="false" /></effect>
  <scene title="" width="1080" height="1920" totalTime="2000" fps="30">
  <shape id="3" label="Inner" startTime="0" endTime="2000" fillType="color" s=".rect"><transform><location value="540,960,0" /><scale value="1,1" /></transform><fillColor value="#ffffffff" /><property name="size" type="vec2" value="100,100" /></shape>
  </scene>
</embedScene>"""
        )
        val g = AlightXmlImporter.parse(xml, 1080f).snapshot.tracks.flatMap { it.clips }.first { it.type == LayerType.GROUP }
        val ky = g.keyframes[AnimProp.ROT_Y]!!
        val kx = g.keyframes[AnimProp.ROT_X]!!
        assertEquals(28.7f * 0.9135f, ky.first().a, 0.05f)    // angle * cos(axis)
        assertEquals(28.7f * 0.4067f, kx.first().a, 0.05f)    // angle * sin(axis)
        assertEquals(-28.1f * 0.9135f, ky.last().a, 0.05f)
        assertEquals(ky.first().a, g.rotation3dY, 0.001f)
    }

    @Test
    fun fadeBecomesClipFades() {
        val c = onlyClip(shapeWith(
            """<effect id="com.alightcreative.effects.fade" locallyApplied="true"><property name="inTime" type="float" value="0.3" /><property name="outTime" type="float" value="0.5" /></effect>"""
        ))
        assertEquals(300L, c.fadeInMs)
        assertEquals(500L, c.fadeOutMs)
    }

    @Test
    fun blinkBakesOpacitySquareWave() {
        val c = onlyClip(shapeWith(
            """<effect id="com.alightcreative.effects.blink" locallyApplied="true"><property name="freq" type="float" value="2" /></effect>"""
        ))
        val keys = c.keyframes[AnimProp.OPACITY]!!
        assertEquals(100f, keys[0].a, 0.001f)   // starts ON
        assertEquals(0f, keys[1].a, 0.001f)     // off after half a period (250 ms at 2 Hz)
        assertEquals(250L, keys[1].timeMs)
        assertEquals(100f, keys[2].a, 0.001f)
        assertTrue(keys.all { it.a == 0f || it.a == 100f })
    }

    @Test
    fun tileBecomesMotionTile() {
        val c = onlyClip(shapeWith(
            """<effect id="com.alightcreative.effects.tile" locallyApplied="true"><property name="scale" type="float" value="2" /><property name="mirror" type="bool" value="true" /><property name="phase" type="float" value="0" /><property name="angle" type="float" value="0" /></effect>"""
        ))
        val fx = c.effects.first { it.type == com.example.model.ClipFxLibrary.TILE }
        assertEquals(listOf(2f, 2f, 1f, 0f, 0f, 0f, 0f), fx.values)
    }

    @Test
    fun rgbSepBecomesRgbSplit() {
        val xml = shapeWith(
            """<effect id="com.alightcreative.effects.rgbsep" locallyApplied="true"><property name="strength" type="float" value="0.14" /><property name="angle" type="float" value="118.5" /></effect>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.first { it.type == com.example.model.ClipFxLibrary.RGB_SPLIT }
        assertEquals(14f, fx.values[0], 0.01f)
        assertEquals(118.5f, fx.values[1], 0.01f)
        assertTrue(r.warnings.any { it.contains("rgbsep is mapped to RGB Split approximately") })
    }

    @Test
    fun hueShiftConstantBecomesHueSaturation() {
        val c = onlyClip(shapeWith(
            """<effect id="com.alightcreative.effects.hueshift" locallyApplied="true"><property name="hue" type="float" value="297.5" /></effect>"""
        ))
        val fx = c.effects.first { it.type == com.example.model.ClipFxLibrary.HUE_SAT }
        assertEquals(-62.5f, fx.values[0], 0.01f)   // 297.5 wrapped into -180..180
    }

    @Test
    fun hueShiftKeyframedBakesIntoFillColour() {
        val xml = scene(
            """<shape id="1" label="Box" startTime="0" endTime="2000" fillType="color" s=".rect">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <fillColor value="#ffff0000" />
  <effect id="com.alightcreative.effects.hueshift" locallyApplied="true"><property name="hue" type="float"><kf t="0" v="0" /><kf t="1" v="120" /></property></effect>
  <property name="size" type="vec2" value="100,100" /></shape>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val keys = c.keyframes[AnimProp.FILL_COLOR]!!
        assertEquals(packColor(0xFFFF0000L), keys.first().a to keys.first().b)   // red, unshifted
        assertEquals(packColor(0xFF00FF00L), keys.last().a to keys.last().b)     // red turned by 120 degrees = green
        assertTrue(r.warnings.any { it.contains("hueshift is baked into the layer's colour keyframes") })
    }

    @Test
    fun satVibBrightContAndInvertMap() {
        fun clipNamed(xml: String, name: String) =
            AlightXmlImporter.parse(xml, 1080f).snapshot.tracks.flatMap { it.clips }.first { it.name == name }
        val sat = clipNamed(shapeWith(
            """<effect id="com.alightcreative.effects.satvib" locallyApplied="true"><property name="saturation" type="float" value="0.225" /><property name="vibrance" type="float" value="1.39" /></effect>"""
        ), "Box")
        val hs = sat.effects.first { it.type == com.example.model.ClipFxLibrary.HUE_SAT }
        assertEquals(listOf(0f, 22.5f, 0f, 39f), hs.values)
        val bc = clipNamed(shapeWith(
            """<effect id="com.alightcreative.effects.brightcont2" locallyApplied="true"><property name="brightness" type="float" value="0.1" /><property name="contrast" type="float" value="-0.2" /></effect>"""
        ), "Box")
        val bcfx = bc.effects.first { it.type == com.example.model.ClipFxLibrary.BRIGHT_CONTRAST }
        assertEquals(listOf(10f, -20f), bcfx.values)
        val inv = clipNamed(shapeWith(
            """<effect id="com.alightcreative.effects.invert" locallyApplied="true"><property name="red" type="bool" value="true" /><property name="green" type="bool" value="true" /><property name="blue" type="bool" value="true" /></effect>"""
        ), "Box")
        val invfx = inv.effects.first { it.type == com.example.model.ClipFxLibrary.INVERT }
        assertEquals(listOf(100f, 0f), invfx.values)
    }

    @Test
    fun vignetteBecomesNativeVignette() {
        val c = onlyClip(shapeWith(
            """<effect id="com.alightcreative.effects.vignette" locallyApplied="true"><property name="strength" type="float" value="0.8" /><property name="scale" type="float" value="0.808" /><property name="feather" type="float" value="1" /></effect>"""
        ))
        val fx = c.effects.first { it.type == com.example.model.ClipFxLibrary.VIGNETTE }
        assertEquals(listOf(80f, 80.8f, 100f), fx.values)
    }

    @Test
    fun zoomBlurAndDirectionalBlurMap() {
        val z = onlyClip(shapeWith(
            """<effect id="com.alightcreative.effects.zoomblur2" locallyApplied="true"><property name="strength" type="float" value="0.45" /></effect>"""
        ))
        val zfx = z.effects.first { it.type == com.example.model.ClipFxLibrary.RADIAL_BLUR }
        assertEquals(listOf(45f, 100f, 50f, 50f), zfx.values)
        val d = onlyClip(shapeWith(
            """<effect id="com.alightcreative.effects.dblur" locallyApplied="true"><property name="angle" type="float" value="270" /><property name="strength" type="float" value="1" /></effect>"""
        ))
        val dfx = d.effects.first { it.type == com.example.model.ClipFxLibrary.DIR_BLUR }
        assertEquals(listOf(270f, 100f, 0f), dfx.values)
    }

    @Test
    fun sharpenAndLensBlurMap() {
        val s = onlyClip(shapeWith(
            """<effect id="com.alightcreative.effects.sharpen" locallyApplied="true"><property name="strength" type="float" value="1" /><property name="radius" type="float" value="2" /></effect>"""
        ))
        val sfx = s.effects.first { it.type == com.example.model.ClipFxLibrary.SHARPEN }
        assertEquals(listOf(100f, 20f, 0f, 40f), sfx.values)
        val l = onlyClip(shapeWith(
            """<effect id="com.alightcreative.effects.lensblur2" locallyApplied="true"><property name="radius" type="float" value="0.5" /><property name="strength" type="float" value="1" /></effect>"""
        ))
        val lfx = l.effects.first { it.type == com.example.model.ClipFxLibrary.LENS_BLUR }
        assertEquals(listOf(50f, 40f, 70f), lfx.values)
    }

    @Test
    fun pinchBulgeBecomesBulge() {
        val xml = shapeWith(
            """<effect id="com.alightcreative.effects.pinchbulge" locallyApplied="true"><property name="strength" type="float" value="0.5" /><property name="radius" type="float" value="0.8" /></effect>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.first { it.type == com.example.model.ClipFxLibrary.BULGE }
        assertEquals(listOf(50f, 80f, 50f, 50f), fx.values)
        assertTrue(r.warnings.any { it.contains("pinchbulge is mapped approximately") })
    }

    @Test
    fun pulsateBakesScaleKeys() {
        val xml = scene(
            """<text id="1" label="A" startTime="0" endTime="2000" size="30" font="googlefonts?name=Roboto&amp;weight=400">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <effect id="com.alightcreative.effects.pulsate2" locallyApplied="true"><property name="minsize" type="float" value="1" /><property name="maxsize" type="float" value="1.2" /><property name="freq" type="float" value="1" /><property name="phase" type="float" value="0" /><property name="type" type="int" value="0" /></effect>
  <content>A</content></text>"""
        )
        val c = AlightXmlImporter.parse(xml, 1080f).snapshot.tracks.flatMap { it.clips }[0]
        val base = 30f * 1080f / 512f / (0.11f * 1080f) * 100f   // text units of sceneWidth / 512 px
        val keys = c.keyframes[AnimProp.SCALE]!!
        assertEquals(base * 1.1f, keys.first().a, base * 0.01f)        // mid + amp * sin(0)
        assertTrue(keys.maxOf { it.a } > base * 1.18f)                  // up to maxsize
        assertTrue(keys.minOf { it.a } < base * 1.02f)                  // down to minsize
    }

    @Test
    fun transformEffectComposesScaleAndAlpha() {
        val c = onlyClip(shapeWith(
            """<effect id="com.alightcreative.effects.transform" locallyApplied="true"><property name="scale" type="float" value="2" /><property name="alpha" type="float" value="0.5" /><property name="angle" type="float" value="0" /></effect>"""
        ))
        val box = 1080f * 130f / 360f
        assertEquals(400f / box * 100f, c.scalePercent, 0.01f)   // the shape's own scale (200/box after the half-extent fix) x 2
        assertEquals(50f, c.opacity, 0.001f)
    }

    @Test
    fun counterIsStoredWithTheTextLayer() {
        val xml = scene(
            """<text id="1" label="N" startTime="0" endTime="2000" size="30" font="googlefonts?name=Roboto&amp;weight=400">
  <effect id="com.alightcreative.effects.counter" locallyApplied="true"><property name="offset" type="float"><kf t="0" v="0" /><kf t="1" v="12" /></property><property name="scale" type="float" value="0.14" /></effect>
  <content>0</content></text>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        assertTrue(c.textAnimators.any { it.effectId == AlightXmlImporter.COUNTER })
        // The counter is drawn now (CounterEval) — no "not drawn yet" warning remains for it.
        assertFalse(r.warnings.any { it.contains("counter") })
    }

    @Test
    fun counterFromTheGraphFileCountsZeroToTwelve() {
        // The exact effect block of 7e363d75…xml ("Animated Graph"), on a text layer 0..7032 ms
        // whose content is "0": the display is the keyframed offset itself, 0 -> 12.
        val xml = scene(
            """<text id="1494" label="N" startTime="0" endTime="7032" size="48" font="googlefonts?name=Montserrat&amp;weight=700" align="center">
  <effect id="com.alightcreative.effects.counter" locallyApplied="true"><property name="offset" type="float"><kf t="0.002276" v="0.000000" /><kf t="0.286689" v="12.000000" e="cubicBezier 0.0 0.60428476 0.252069 1.0" /></property><property name="scale" type="float" value="0.140000" /></effect>
  <content>0</content></text>"""
        )
        val r = AlightXmlImporter.parse(xml, 1920f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        assertTrue(c.textAnimators.any { it.effectId == AlightXmlImporter.COUNTER })
        assertFalse(r.warnings.any { it.contains("counter") })
        assertEquals("0", CounterEval.displayText("0", c, 0L))
        assertEquals("12", CounterEval.displayText("0", c, 2016L))
        assertEquals("12", CounterEval.displayText("0", c, 7032L))
    }

    @Test
    fun counterOnAShapeStaysAWarning() {
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.counter" locallyApplied="true"><property name="offset" type="float" value="0" /></effect>"""
        ), 1080f)
        assertTrue(r.warnings.any { it.contains("counter is not supported yet") })
    }

    @Test
    fun repeatRadialImportsInsteadOfWarning() {
        // Used to stay a warning ("the app cannot repeat a layer in a circle"); since the particles
        // fix it imports as a Repeat Radial effect like any other.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.repeat.radial" locallyApplied="true"><property name="count" type="int" value="6" /></effect>"""
        ), 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(1, c.effects.size)
        assertEquals(ClipFxLibrary.REPEAT_RADIAL, c.effects[0].type)
        assertEquals(6f, c.effects[0].values[0], 0f)
        assertFalse(r.warnings.any { it.contains("cannot repeat a layer") })
    }

    @Test
    fun liftWithOnlyZeroFillIsSilent() {
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.lift" locallyApplied="true"><property name="fill" type="float" value="0" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("lift") })
        assertTrue(r.snapshot.tracks.flatMap { it.clips }[0].effects.isEmpty())
    }

    @Test
    fun audioLayerBecomesAnAudioClip() {
        // The audio of the "ST Intro" file: 599 ms of a file section starting at 3500 ms, stretched to fit.
        val xml = scene(
            """<audio id="9" label="Minimal Intro SOUND.mp3" startTime="0" endTime="599" src="content://com.android.providers.media.documents/document/audio%3A53292" inTime="3500" outTime="4067" mediaFillMode="stretch" />"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(LayerType.AUDIO, c.type)
        assertEquals("content://com.android.providers.media.documents/document/audio%3A53292", c.uri)
        assertEquals("audio/mpeg", c.mimeType)
        assertEquals(599L, c.durationMs)
        assertEquals(3500L, c.trimStartMs)
        assertEquals(4067L, c.sourceDurationMs)
        assertEquals(567f / 599f, c.speed, 0.001f)
        assertTrue(r.warnings.any { it.contains("imported silent") })
    }

    @Test
    fun groupWipeMaskKeysAreRemapped() {
        // A wiped shape inside a group whose window is stretched 2:1: the mask keys follow the remap.
        val xml = scene(
            """<embedScene id="2" label="G" startTime="0" endTime="1000" fillType="intrinsic" outTime="2000">
  <transform><location value="540,960,0" /></transform>
  <scene title="" width="1080" height="1920" totalTime="2000" fps="30">
  <shape id="3" label="Inner" startTime="0" endTime="2000" fillType="color" s=".rect"><transform><location value="540,960,0" /><scale value="1,1" /></transform><fillColor value="#ffffffff" /><effect id="com.alightcreative.effects.wipe2" locallyApplied="true"><property name="start" type="float"><kf t="0" v="1.006" /><kf t="1" v="0.5" /></property><property name="end" type="float" value="1" /><property name="angle" type="float" value="90" /><property name="feather" type="float" value="0" /></effect><property name="size" type="vec2" value="100,100" /></shape>
  </scene>
</embedScene>"""
        )
        val inner = AlightXmlImporter.parse(xml, 1080f).snapshot.tracks.flatMap { it.clips }.first { it.name == "Inner" }
        assertEquals(1000L, inner.durationMs)
        assertEquals(listOf(0L, 1000L), inner.masks[0].keys.map { it.timeMs })
    }
}
