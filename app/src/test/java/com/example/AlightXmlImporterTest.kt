package com.example

import com.example.engine.AlightXmlImporter
import com.example.engine.AmFxEval
import com.example.engine.CounterEval
import com.example.engine.ParentRig
import com.example.engine.RepeatEval
import com.example.engine.TextProgressEval
import com.example.engine.textLayoutBoxWidth
import com.example.model.AnimProp
import com.example.model.at
import com.example.model.CalloutOutline
import com.example.model.ClipFxLibrary
import com.example.model.LayerType
import com.example.model.effectiveScalePercent
import com.example.model.PieOutline
import com.example.model.flattenPathSegments
import com.example.model.hasPie
import com.example.model.packColor
import com.example.model.TimelineClip
import com.example.model.renderAt
import com.example.model.valueAt
import com.example.model.shapeCornerRadii
import com.example.model.shapeDrawScaleX
import com.example.model.shapeDrawScaleY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

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
        // 30 * 2 = 60 Alight text units; one unit is sceneWidth / 640 px, so the font renders
        // 60 * 1080 / 640 = 101.25 scene px tall; the app's text is 0.11 * 1080 = 118.8 px at 100 %.
        // (Build 91 read / 512 off a text WIDTH; round 5's cap-height measurements — spacing-
        // independent, two fonts — give / 640 exactly. Before build 91 this read size x scale as
        // plain scene px, ~3.75x too small.)
        assertEquals(60f * 1080f / 640f / (0.11f * 1080f) * 100f, c.scalePercent, 0.01f)
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
        // No weight in the spec = Alight's regular weight (400). This used to default to 600, which
        // made every `imported`-font layer (they never carry a weight) render semibold / faux-bold.
        assertEquals("Roboto" to 400, AlightXmlImporter.parseFont("", w))
        assertEquals("Roboto" to 400, AlightXmlImporter.parseFont("imported?name=MontserratAlternates-Bold.otf", w))
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
        // Font height at render: 18 * 0.839844 * 1920 / 640 = 45.4 scene px. (Build 91's / 512
        // reading gave 56.7, off a width measurement; round 5 measured the export's CAP HEIGHT —
        // 32 px at 1920 wide for this text, Roboto's cap ratio 0.71 — which is 45.4 px of em, and
        // the pill text in its own font confirms the same / 640. The old pre-91 reading gave 15.1,
        // which is why the text was tiny and wrapped.)
        val emPx = t.scalePercent / 100f * 0.11f * 1920f
        assertEquals(45.4f, emPx, 1.5f)
        // wrapWidth 512 scene px of a 1920 scene; at a ~45 px em the 12 glyphs need roughly 270 px,
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
        // Its layout box lives in unscaled layout units (font 211.2 px) and is scaled by ~0.215 when
        // drawn, so the box must be ~512/0.215 wide there: "Channel Name" needs ~1400 layout px on one
        // line. Build 91 used the raw fraction and got a ~110 px on-screen box (a 2-letter column).
        // (The scale factor is the / 640 text reading since build 110; the on-screen width below is
        // the wrap width itself, so it does not move with the text-unit rule.)
        val box = textLayoutBoxWidth(1920f, text.textWrapFrac, text.effectiveScalePercent / 100f, 52.8f)
        assertTrue(box > 1400f)
        // On screen the box is the wrap width itself: the engines add the padding OUTSIDE the line
        // width, so it is not subtracted from an authored width (build 135 — before that this read
        // ~512 - padding = 483.6, the 0.5 em shrink that wrapped the Graph counter on device).
        assertEquals(512f, box * text.effectiveScalePercent / 100f, 20f)
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
    fun wrappedOutTimeDecodesToTheRealWindow() {
        // rax-preset-249's "Edit Group" slices, verbatim pattern: the embed sits at 600..832 of the
        // timeline, inTime == startTime, and outTime is the wrapped form of 832 for a 6932 ms scene
        // (832 - 6932 + Int.MAX_VALUE = 2147477547). The window is [600, 832], factor 1: the
        // full-length child is cut to the slice, the child outside the window stays out, and no
        // "timing was stretched" note is owed. Read plainly (the pre-fix bug), the factor collapsed
        // to ~0 and BOTH children dropped as sub-millisecond clips — the group imported empty.
        val xml = scene(
            """<embedScene id="s" label="Slice" startTime="600" endTime="832" fillType="intrinsic" inTime="600" outTime="2147477547" mediaFillMode="fill">
<transform><location value="540.000000,960.000000,0.000000" /></transform>
<scene title="" width="1080" height="1920" totalTime="6932" fps="30">
<shape id="a" label="Full" startTime="0" endTime="6932" fillType="color" s=".rect"><transform><location value="540.000000,960.000000,0.000000" /></transform><fillColor value="#ffff0000" /><property name="size" type="vec2" value="100.000000,100.000000" /></shape>
<shape id="b" label="Late" startTime="3000" endTime="4000" fillType="color" s=".rect"><transform><location value="540.000000,960.000000,0.000000" /></transform><fillColor value="#ff00ff00" /><property name="size" type="vec2" value="100.000000,100.000000" /></shape>
</scene>
</embedScene>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val g = clips.first { it.name == "Slice" }
        assertEquals(600L, g.startMs)
        assertEquals(232L, g.durationMs)
        val full = clips.first { it.name == "Full" }
        assertEquals(600L, full.startMs)
        assertEquals(232L, full.durationMs)
        assertTrue(clips.none { it.name == "Late" })
        assertTrue(r.warnings.none { it.contains("Slice") && it.contains("stretched") })
    }

    @Test
    fun wrappedOutTimeKeepsTheNestedSubtree() {
        // likenya's "Group 2 Copy 4", verbatim pattern: outTime 2147483197 is the wrapped form of
        // 499 for a 949 ms scene, so the outer window is [0, 499] at factor 1 and the inner group
        // (the whole 949 ms, its own window the full 1131 ms scene) is clipped to it — not dropped.
        // Pre-fix the outer factor was ~0 and the inner group clip itself remapped below 1 ms, so
        // the entire subtree (12 group clips and 64 pie shapes in the real file) vanished.
        val xml = scene(
            """<embedScene id="o" label="Outer" startTime="0" endTime="499" fillType="intrinsic" outTime="2147483197" mediaFillMode="stretch">
<transform><location value="540.000000,960.000000,0.000000" /></transform>
<scene title="" width="1080" height="1920" totalTime="949" fps="30">
<embedScene id="i" label="Inner" startTime="0" endTime="949" fillType="intrinsic" mediaFillMode="stretch">
<transform><location value="540.000000,960.000000,0.000000" /></transform>
<scene title="" width="1080" height="1920" totalTime="1131" fps="30">
<shape id="d" label="Deep" startTime="0" endTime="1131" fillType="color" s=".rect"><transform><location value="540.000000,960.000000,0.000000" /></transform><fillColor value="#ffff0000" /><property name="size" type="vec2" value="100.000000,100.000000" /></shape>
</scene>
</embedScene>
</scene>
</embedScene>"""
        )
        val clips = AlightXmlImporter.parse(xml, 1080f).snapshot.tracks.flatMap { it.clips }
        val outer = clips.first { it.name == "Outer" }
        assertEquals(499L, outer.durationMs)
        val inner = clips.first { it.name == "Inner" }
        assertEquals(0L, inner.startMs)
        assertEquals(499L, inner.durationMs)   // its 949 ms span clipped to the outer window
        val deep = clips.first { it.name == "Deep" }
        assertEquals(0L, deep.startMs)
        assertEquals(499L, deep.durationMs)
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
    fun arrowBecomesAFilledPathWithItsHead() {
        val clips = threeClips()
        val group1 = clips.first { it.name == "Group 1" }
        val arrow = clips.first { it.name == "Arrow 1" }
        assertEquals(LayerType.SHAPE, arrow.type)
        // Since build 135 the arrow (shaft + head) is one filled PATH, baked in scene space: the
        // shaft centreline runs from (529.35, 490.2) to the tip (529.35, 608.5) in the Group 1
        // scene — location + pivot + scale * (p - pivot), the pivot (-161.5, 221.5) added back.
        // Before build 135 the pivot was dropped, the shaft was a RECT at (690.8, 327.9) — ~270 px
        // from its disc — and the head was not drawn at all (round 9, P2/P3).
        assertEquals("PATH", arrow.shapePattern)
        assertEquals(group1.id, arrow.groupId)
        assertEquals(0f, arrow.rotationDeg, 0.01f)
        // The outline's bounding box is 44.5 x 118.3 scene px (head width 106 x scale across,
        // shaft length 282 x scale down), centred on (529.35, 549.35) in the scene.
        assertEquals(529.35f - 960f, arrow.transformX, 0.6f)
        assertEquals(549.35f - 540f, arrow.transformY, 0.6f)
        assertTrue(arrow.pathSegments.isNotEmpty())
        // Both shape scales are the normalisation extent (the larger half, 59.15) over the box.
        assertEquals(118.3f / box3, arrow.shapeScaleX, 0.002f)
        assertEquals(118.3f / box3, arrow.shapeScaleY, 0.002f)
    }

    @Test
    fun laterPassEffectsStayWarningsOnTheRealFile() {
        val r = threeResult()
        // solidcolor is drawn since build 110 (the Solid Color effect), so the file's one instance
        // (on the stroke-only Outline) no longer warns at all.
        assertTrue(r.warnings.none { it.contains("solidcolor") })
        // The file's one movealongpath3 (Circle 1 on Circle 2's ring, in Group 2) is baked into
        // POSITION keyframes since build 99, so no movealongpath3 warning is left either.
        assertTrue(r.warnings.none { it.contains("movealongpath3") })
        // Both mask layers of the file sit on a shape / text layer, so since build 94 they are applied
        // (linked to the layer below, see LayerMaskImportTest) and no mask warning is left. Before build
        // 94 they were imported hidden with an "is a mask in Alight" warning.
        assertTrue(r.warnings.none { it.contains("Mask layer") })
        // The file's arrow carries head dimensions, so since build 135 it imports whole (shaft +
        // head as one filled path) and the old "plain line" warning is gone. (This excerpt leaves
        // the file's Shape embed out, so its Exclude Mask layer is pinned in PhoneRoundsImportTest
        // instead.)
        assertTrue(r.warnings.none { it.contains("plain line") })
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
    fun motionblur4BecomesMotionBlur4WithTuneAsStrength() {
        // Build 136: motionblur4 has its own effect now (its def was recovered and
        // ported); before, it took the Motion Blur approximation below.
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
        assertEquals(ClipFxLibrary.MOTION_BLUR4, c.effects[0].type)
        assertEquals(20f, c.effects[0].values[0], 0.01f)   // tune 0.2 -> 20 (x100, def range 0..4)
        assertEquals(1f, c.effects[0].values[1], 0f)   // usePos
        assertEquals(1f, c.effects[0].values[2], 0f)   // useScale
        assertEquals(1f, c.effects[0].values[3], 0f)   // useAngle
        assertEquals(1f, c.effects[0].values[4], 0f)   // samplesMin (def default)
        assertEquals(100f, c.effects[0].values[5], 0f) // samplesMax (def default)
        // The def is ported: no approximation verdict, nothing skipped.
        assertFalse(r.warnings.any { it.contains("motionblur4") && it.contains("approximately") })
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
        // The radius is stored in height units (build 110): this scene is 1080x1920, so
        // 1242 x 1920/1080 = 2208. The radial radius above is NOT converted (frame-proven as-is).
        assertEquals(
            listOf(10f, 2208f, 0f, 0f, 0f, 0f, 100f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f),
            c.effects[1].values
        )
        assertFalse(r.warnings.any { it.contains("repeat.radial") || it.contains("repeat.scatter") })
    }

    @Test
    fun repeatOnAGroupIsStoredAndExpands() {
        // A repeat on an <embedScene> (a Group layer) is stored on the group layer like on any
        // other — it used to import with a "stored but not drawn repeated yet" warning; the draw
        // paths now pose the group's composed picture once per copy (RepeatDraw).
        val xml = scene(
            """<embedScene id="7" label="Grp" startTime="0" endTime="2000" fillType="intrinsic" mediaFillMode="stretch">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <effect id="com.alightcreative.effects.repeat" locallyApplied="true">
    <property name="count" type="float" value="3.000000" />
    <property name="time" type="float" value="0.000000" />
    <property name="offset" type="vec2" value="0.000000,50.000000" />
    <property name="angle" type="float" value="0.000000" />
    <property name="scale" type="float" value="1.000000" />
    <property name="alpha" type="float" value="1.000000" />
  </effect>
  <scene title="" width="1080" height="1920" totalTime="2000" fps="30">
    <shape id="8" label="Box" startTime="0" endTime="2000" fillType="color" s=".rect">
      <transform><location value="540,960,0" /><scale value="1,1" /></transform>
      <fillColor value="#ffffffff" />
      <property name="size" type="vec2" value="100,100" /></shape>
  </scene>
</embedScene>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val g = r.snapshot.tracks.flatMap { it.clips }.first { it.type == LayerType.GROUP }
        assertEquals(1, g.effects.size)
        assertEquals(ClipFxLibrary.REPEAT, g.effects[0].type)
        assertEquals(listOf(3f, 0f, 50f, 0f, 100f, 100f), g.effects[0].values)
        assertFalse(r.warnings.any { it.contains("not drawn") })
        // And the stored effect expands into the copy poses the group draw uses.
        val deltas = RepeatEval.copyDeltas(g)!!
        assertEquals(3, deltas.size)
        assertEquals(50f, deltas[1].dy, 0.01f)
        assertEquals(100f, deltas[2].dy, 0.01f)
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
        // The written radius 1242 is read in height units (build 110, round-5 measurement):
        // 1242 x 1080/1920 = 698.6 preview px on this 1920x1080 scene.
        assertEquals(698.625f, scatter.values[1], 0.001f)         // radius (preview px, k = 1)
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
            // Centres stay inside the effect's disc around the LAYER (scene coords): the written
            // offset translates Alight's field rather than moving its centre (round 5), so the
            // disc is the layer's own position with the height-unit radius.
            val cx = 960f + dot.transformX
            val cy = 540f + dot.transformY
            val d = kotlin.math.hypot(cx - 960f, cy - 540f)
            assertTrue(d <= 699.5f)
            if (cx in 0f..1920f && cy in 0f..1080f) inFrame++
            // colorAltCopies: odd copies take the effect's lavender, even ones keep the white fill.
            assertEquals(if (i % 2 == 1) 0xFFB891FFL else 0xFFFFFFFFL, dot.textColor)
        }
        // Alight's export at 2.0 s shows dots of 9..45 px; the field's copies span the same range.
        assertTrue(biggest >= 28f)
        // And Alight shows 5 clear dots in frame at 2.0 s (5..8 across 2.0/3.0/4.2 s): with the
        // field centred on the layer at the height-unit radius, most copies land in frame — the
        // pre-110 field (offset centre, full radius) put only 1..3 there.
        assertTrue(inFrame >= 5)
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
    fun pathShapeImportsWithItsSegments() {
        // A freehand <path> shape (no `s` kind): the segments are stored centred on the path's box
        // and divided by its larger half extent; the box centre's offset folds into the position.
        val xml = scene(
            """<shape id="9" label="Scribble" startTime="0" endTime="1000" fillType="none">
  <transform><location value="540,960,0" /></transform><path d="M 0 0L 10 10" /></shape>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }.single()
        assertEquals("PATH", c.shapePattern)
        assertEquals(com.example.model.FILL_NONE, c.fillMode)
        assertEquals(2, c.pathSegments.size)
        assertEquals(com.example.model.PATH_OP_MOVE, c.pathSegments[0].op)
        assertEquals(-1f, c.pathSegments[0].pts[0], 0.001f)
        assertEquals(-1f, c.pathSegments[0].pts[1], 0.001f)
        assertEquals(com.example.model.PATH_OP_LINE, c.pathSegments[1].op)
        assertEquals(1f, c.pathSegments[1].pts[0], 0.001f)
        // Box 10x10 (half extent 5) at scale 1 against the 390 px shape box; anchor = location + (5, 5).
        assertEquals(10f / 390f, c.shapeScaleX, 0.001f)
        assertEquals(5f, c.transformX, 0.001f)
        assertEquals(5f, c.transformY, 0.001f)
        assertTrue(r.warnings.none { it.contains("freehand path") })
    }

    @Test
    fun freehandRectPathImportsWithSegmentsAndScale() {
        // Modelled on shake-997's colour plate (id 10102560): a 200x200 path rect under a strongly
        // non-uniform scale. The uniform segment divisor keeps the outline exact on both axes while
        // shapeScaleX / shapeScaleY carry the two scales separately.
        val xml = scene(
            """<shape id="10102560" label="Vetri EDITZ Official" startTime="0" endTime="2000" fillType="color">
  <transform><location value="540.000000,1170.000000,0.000000" /><scale value="5.956158,12.905000" /></transform>
  <fillColor value="#ff3a5f8a" />
  <path d="M -100.0 -100.0L 100.0 -100.0L 100.0 100.0L -100.0 100.0L -100.0 -100.0Z" />
</shape>"""
        )
        val c = AlightXmlImporter.parse(xml, 1080f).snapshot.tracks.flatMap { it.clips }.single()
        assertEquals("PATH", c.shapePattern)
        assertEquals(com.example.model.FILL_SOLID, c.fillMode)
        assertEquals(0xFF3A5F8AL, c.textColor)
        assertEquals(
            listOf(
                com.example.model.PATH_OP_MOVE, com.example.model.PATH_OP_LINE, com.example.model.PATH_OP_LINE,
                com.example.model.PATH_OP_LINE, com.example.model.PATH_OP_LINE, com.example.model.PATH_OP_CLOSE
            ),
            c.pathSegments.map { it.op }
        )
        assertEquals(-1f, c.pathSegments[0].pts[0], 0.001f)
        assertEquals(1f, c.pathSegments[2].pts[1], 0.001f)
        val box = 1080f * 130f / 360f
        assertEquals(2f * 100f * 5.956158f / box, c.shapeScaleX, 0.001f)
        assertEquals(2f * 100f * 12.905f / box, c.shapeScaleY, 0.001f)
        assertEquals(0f, c.transformX, 0.001f)
        assertEquals(210f, c.transformY, 0.001f)
        // Rendered bounds in scene pixels: anchor (540, 1170) + scale x (half extent x the segments).
        val pts = com.example.model.flattenPathSegments(c.pathSegments)
        val xs = pts.map { 540f + it.first * 100f * 5.956158f }
        val ys = pts.map { 1170f + it.second * 100f * 12.905f }
        assertEquals(-55.6f, xs.minOrNull()!!, 1f)
        assertEquals(1135.6f, xs.maxOrNull()!!, 1f)
        assertEquals(-120.5f, ys.minOrNull()!!, 1f)
        assertEquals(2460.5f, ys.maxOrNull()!!, 1f)
    }

    @Test
    fun freehandLinePathImportsStrokeOnlyAroundItsBoxCentre() {
        // Verbatim geometry of the Subscriber file's "Shape 1" (id 635433): a stroke-only horizontal
        // line whose points sit at y = +124.57 in the layer's frame — the box centre is NOT the
        // anchor, so the offset folds into the position and the (flat) box still carries the stroke.
        val xml = scene(
            """<shape id="635433" label="Shape 1" startTime="0" endTime="2000" fillType="none">
  <transform><location value="976.500000,540.000000,0.000000" /><scale value="1.126037,1.000000" /><opacity value="0.283203" /></transform>
  <fillColor value="#ffa48656" />
  <path-stroke direction="centered" join="round" end-size="1.500000"><color value="#ffffffff" /><size value="6.500000" /></path-stroke>
  <path d="M -318.0 124.572205L 285.0 124.572205L -318.0 124.572205" />
</shape>""", 1920, 1080
        )
        val c = AlightXmlImporter.parse(xml, 1920f).snapshot.tracks.flatMap { it.clips }.single()
        assertEquals("PATH", c.shapePattern)
        assertEquals(com.example.model.FILL_NONE, c.fillMode)
        assertEquals(28.3203f, c.opacity, 0.01f)
        assertEquals(0xFFFFFFFFL, c.strokeColor)
        // Box centre (-16.5, 124.572205), larger half extent 301.5; stroke dp = 130 x 6.5 / (2 x 301.5).
        assertEquals(1.4017f, c.strokeWidth, 0.001f)
        val box = 1920f * 130f / 360f
        assertEquals(2f * 301.5f * 1.126037f / box, c.shapeScaleX, 0.001f)
        assertEquals(2f * 301.5f / box, c.shapeScaleY, 0.001f)
        // Anchor = location + scale x box centre = (957.92, 664.57) in scene pixels.
        assertEquals(-2.08f, c.transformX, 0.01f)
        assertEquals(124.572f, c.transformY, 0.01f)
        assertEquals(3, c.pathSegments.size)
        assertEquals(-1f, c.pathSegments[0].pts[0], 0.001f)
        assertEquals(0f, c.pathSegments[0].pts[1], 0.001f)
        assertEquals(1f, c.pathSegments[1].pts[0], 0.001f)
        // And the rendered points land exactly where Alight puts them: location + scale x raw point.
        val pts = com.example.model.flattenPathSegments(c.pathSegments)
        val xs = pts.map { 957.9204f + it.first * 301.5f * 1.126037f }
        val ys = pts.map { 664.5722f + it.second * 301.5f }
        assertEquals(618.42f, xs.minOrNull()!!, 0.5f)
        assertEquals(1297.42f, xs.maxOrNull()!!, 0.5f)
        assertEquals(664.5722f, ys.minOrNull()!!, 0.01f)
    }

    @Test
    fun freehandCurvePathImportsWithFlattenedBounds() {
        // A cubic the corpus does not use (its paths are all polylines) but the parser supports:
        // M 0 0 C 0 100, 100 100, 100 0 — the curve's apex is y = 75, so the box is 100 x 75.
        val xml = scene(
            """<shape id="7" label="Arc" startTime="0" endTime="2000" fillType="color">
  <transform><location value="500.000000,500.000000" /></transform>
  <fillColor value="#ff224466" />
  <path d="M 0.0 0.0C 0.0 100.0 100.0 100.0 100.0 0.0" />
</shape>""", 1000, 1000
        )
        val c = AlightXmlImporter.parse(xml, 1000f).snapshot.tracks.flatMap { it.clips }.single()
        assertEquals("PATH", c.shapePattern)
        assertEquals(
            listOf(com.example.model.PATH_OP_MOVE, com.example.model.PATH_OP_CUBIC),
            c.pathSegments.map { it.op }
        )
        // Box centre (50, 37.5), larger half extent 50: the move is (-1, -0.75), the apex control
        // points reach y = (100 - 37.5) / 50 = 1.25 (past 1 on the minor axis, as the uniform
        // divisor intends).
        assertEquals(-1f, c.pathSegments[0].pts[0], 0.001f)
        assertEquals(-0.75f, c.pathSegments[0].pts[1], 0.001f)
        assertEquals(1.25f, c.pathSegments[1].pts[1], 0.001f)
        assertEquals(50f, c.transformX, 0.001f)
        assertEquals(37.5f, c.transformY, 0.001f)
        val pts = com.example.model.flattenPathSegments(c.pathSegments)
        val xs = pts.map { 550f + it.first * 50f }
        val ys = pts.map { 537.5f + it.second * 50f }
        assertEquals(500f, xs.minOrNull()!!, 0.5f)
        assertEquals(600f, xs.maxOrNull()!!, 0.5f)
        assertEquals(500f, ys.minOrNull()!!, 0.5f)
        assertEquals(575f, ys.maxOrNull()!!, 0.5f)
    }

    @Test
    fun keyframedAndArcPathsStaySkipped() {
        // Keyframed path data (a <path> with keyframe children instead of a `d`) is not read yet.
        val kf = AlightXmlImporter.parse(
            scene(
                """<shape id="8" label="Morph" startTime="0" endTime="2000" fillType="color"><transform><location value="540,960" /></transform><path><kf t="0" v="M 0 0L 10 10" /></path></shape>"""
            ), 1080f
        )
        assertTrue(kf.snapshot.tracks.flatMap { it.clips }.isEmpty())
        assertTrue(kf.warnings.any { it.contains("keyframed path data is not supported yet") })
        // An arc command has no honest polyline reading: the layer is skipped, not drawn wrong.
        val arc = AlightXmlImporter.parse(
            scene(
                """<shape id="9" label="Arc" startTime="0" endTime="2000" fillType="color"><transform><location value="540,960" /></transform><path d="M 0 0A 50 50 0 0 1 100 100" /></shape>"""
            ), 1080f
        )
        assertTrue(arc.snapshot.tracks.flatMap { it.clips }.isEmpty())
        assertTrue(arc.warnings.any { it.contains("path command \"A\" is not supported yet") })
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
    fun solidColorBecomesTheFxAndMultipliesOpacity() {
        val xml = scene(
            """<text id="1" label="A" startTime="0" endTime="1000" size="30" font="googlefonts?name=Roboto&amp;weight=400">
  <transform><location value="540,960,0" /><scale value="1,1" /><opacity value="0.5" /></transform>
  <effect id="com.alightcreative.solidcolor" locallyApplied="true"><property name="color" type="color" value="#ff112233" /><property name="alpha" type="float" value="0.5" /><property name="blendMode" type="int" value="0" /></effect>
  <content>A</content></text>"""
        )
        val c = AlightXmlImporter.parse(xml, 1080f).snapshot.tracks.flatMap { it.clips }[0]
        // Since build 110 the colour rides the Solid Color effect (the picture is flattened at
        // render); the layer's own colour is NOT overwritten (before, textColor became the
        // effect's colour and a media fill was replaced outright).
        val fx = c.effects.single { it.type == ClipFxLibrary.SOLID_COLOR }
        assertEquals(listOf(17f, 34f, 51f, 100f, 0f), fx.values)
        assertEquals(0xFFFFFFFFL, c.textColor)
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
        // And the Solid Color effect itself, flagged (values[4] = 1) to follow that track per
        // frame (SolidColorEval) — its stored colour is the first keyframe's.
        val fx = c.effects.single { it.type == ClipFxLibrary.SOLID_COLOR }
        assertEquals(listOf(0f, 0f, 0f, 100f, 1f), fx.values)
    }

    @Test
    fun solidColorOnTheRealStrokeOnlyShapeKeepsTheStrokeColour() {
        // The "Outline" of the real file "3": solidcolor flashes white -> green, and its only paint
        // is the stroke — the Solid Color effect flattens that stroke at render (build 110), so the
        // importer keeps the stroke's own colour AND the colour track AND attaches the flagged fx.
        val outline = threeClips().first { it.name == "Outline" }
        assertEquals(0xFF009DFFL, outline.strokeColor)
        val keys = outline.keyframes[AnimProp.FILL_COLOR]!!
        assertEquals(packColor(0xFFFFFFFFL), keys[0].a to keys[0].b)
        assertEquals(packColor(0xFF14FD6AL), keys[1].a to keys[1].b)
        val fx = outline.effects.single { it.type == ClipFxLibrary.SOLID_COLOR }
        assertEquals(listOf(255f, 255f, 255f, 100f, 1f), fx.values)   // first keyframe's colour
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
        // Angle 0, no offset, scale 1: AM's angle 0 points UP, so the gradient runs from the
        // middle of the bottom edge to the middle of the top one.
        assertEquals(0.5f, c.gradX1, 0.001f)
        assertEquals(1f, c.gradY1, 0.001f)
        assertEquals(0.5f, c.gradX2, 0.001f)
        assertEquals(0f, c.gradY2, 0.001f)
    }

    @Test
    fun gradientOverlayGeometryFollowsTheDef() {
        // Verbatim ST Intro instance (shape 633194 of 1c30cfdb): angle 45.5, offset (-182, 0),
        // scale 1.32. Centre = (0.5 - 182/500, 0.5) = (0.136, 0.5); the direction (sin a, -cos a)
        // = (0.7133, -0.7009); t = 0 / 1 at centre -/+ direction x 0.66.
        val xml = scene(
            """<shape id="1" label="Pill" startTime="0" endTime="1000" fillType="color" s=".rect">
  <transform><location value="500,500,0" /><scale value="10,10" /></transform>
  <fillColor value="#ff000000" />
  <effect id="com.alightcreative.gradientoverlay2" locallyApplied="true"><property name="color1" type="color" value="#ffb891ff" /><property name="color2" type="color" value="#ff7a32ff" /><property name="angle" type="float" value="45.5" /><property name="offset" type="vec2" value="-182,0" /><property name="scale" type="float" value="1.32" /><property name="alpha" type="float" value="1" /><property name="gradType" type="int" value="0" /><property name="blendMode" type="int" value="0" /></effect>
  <property name="size" type="vec2" value="100,100" /></shape>""", 1000, 1000
        )
        val r = AlightXmlImporter.parse(xml, 1000f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(0xFFB891FFL, c.gradColor1)
        assertEquals(0xFF7A32FFL, c.gradColor2)
        assertEquals(-0.3347f, c.gradX1, 0.002f)
        assertEquals(0.9626f, c.gradY1, 0.002f)
        assertEquals(0.6067f, c.gradX2, 0.002f)
        assertEquals(0.0374f, c.gradY2, 0.002f)
        assertTrue(r.warnings.none { it.contains("approximately") })

        // Verbatim Graph instance (the day-label gradient): angle 0, offset (0, 146), scale 0.54.
        // Centre = (0.5, 0.5 + 146/500) = (0.5, 0.792); direction (0, -1); half = 0.27.
        val xml2 = scene(
            """<shape id="1" label="Bar" startTime="0" endTime="1000" fillType="color" s=".rect">
  <transform><location value="500,500,0" /><scale value="10,10" /></transform>
  <fillColor value="#ff000000" />
  <effect id="com.alightcreative.gradientoverlay2" locallyApplied="true"><property name="color1" type="color" value="#ff0088ff" /><property name="color2" type="color" value="#ff052243" /><property name="angle" type="float" value="0" /><property name="offset" type="vec2" value="0,146" /><property name="scale" type="float" value="0.54" /><property name="alpha" type="float" value="1" /><property name="gradType" type="int" value="0" /><property name="blendMode" type="int" value="0" /></effect>
  <property name="size" type="vec2" value="100,100" /></shape>""", 1000, 1000
        )
        val c2 = AlightXmlImporter.parse(xml2, 1000f).snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(0.5f, c2.gradX1, 0.001f)
        assertEquals(1.062f, c2.gradY1, 0.001f)
        assertEquals(0.5f, c2.gradX2, 0.001f)
        assertEquals(0.522f, c2.gradY2, 0.001f)

        // Radial (the def: t = 2 x centre distance / scale): the second point sits scale/2 from
        // the offset centre along +x; angle plays no part.
        val xml3 = scene(
            """<shape id="1" label="Dot" startTime="0" endTime="1000" fillType="color" s=".circle">
  <transform><location value="500,500,0" /><scale value="10,10" /></transform>
  <fillColor value="#ff000000" />
  <effect id="com.alightcreative.gradientoverlay2" locallyApplied="true"><property name="color1" type="color" value="#ffffffff" /><property name="color2" type="color" value="#ff000000" /><property name="angle" type="float" value="30" /><property name="offset" type="vec2" value="100,-50" /><property name="scale" type="float" value="2" /><property name="alpha" type="float" value="1" /><property name="gradType" type="int" value="1" /><property name="blendMode" type="int" value="0" /></effect>
  <property name="size" type="vec2" value="100,100" /></shape>""", 1000, 1000
        )
        val c3 = AlightXmlImporter.parse(xml3, 1000f).snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(com.example.model.GRAD_RADIAL, c3.gradType)
        assertEquals(0.7f, c3.gradX1, 0.001f)
        assertEquals(0.4f, c3.gradY1, 0.001f)
        assertEquals(1.7f, c3.gradX2, 0.001f)
        assertEquals(0.4f, c3.gradY2, 0.001f)

        // Sweep (the def: t runs clockwise from the (-cos a, -sin a) ray): angle 0 starts at the
        // leftward ray, and the wrap difference is reported.
        val xml4 = scene(
            """<shape id="1" label="Fan" startTime="0" endTime="1000" fillType="color" s=".circle">
  <transform><location value="500,500,0" /><scale value="10,10" /></transform>
  <fillColor value="#ff000000" />
  <effect id="com.alightcreative.gradientoverlay2" locallyApplied="true"><property name="color1" type="color" value="#ffffffff" /><property name="color2" type="color" value="#ff000000" /><property name="angle" type="float" value="0" /><property name="offset" type="vec2" value="0,0" /><property name="scale" type="float" value="1" /><property name="alpha" type="float" value="1" /><property name="gradType" type="int" value="2" /><property name="blendMode" type="int" value="0" /></effect>
  <property name="size" type="vec2" value="100,100" /></shape>""", 1000, 1000
        )
        val r4 = AlightXmlImporter.parse(xml4, 1000f)
        val c4 = r4.snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(com.example.model.GRAD_SWEEP, c4.gradType)
        assertEquals(0.5f, c4.gradX1, 0.001f)
        assertEquals(0.5f, c4.gradY1, 0.001f)
        assertEquals(0f, c4.gradX2, 0.001f)
        assertEquals(0.5f, c4.gradY2, 0.001f)
        assertTrue(r4.warnings.any { it.contains("sweep wraps") })
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
    fun drawingProgressOnTextBecomesAnOutlineTrim() {
        val xml = scene(
            """<text id="1" label="A" startTime="0" endTime="2000" size="30" font="googlefonts?name=Roboto&amp;weight=400">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <effect id="com.alightcreative.effects.drawing.progress" locallyApplied="true"><property name="start" type="float" value="0" /><property name="end" type="float" value="50" /></effect>
  <content>A</content></text>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        // Text layers take the trim too (build 106): the renderers stroke the glyph outlines
        // trimmed to the window instead of filling the glyphs.
        assertTrue(c.strokeTrimText)
        assertEquals(0f, c.strokeTrimStart, 0f)
        assertEquals(0.5f, c.strokeTrimEnd, 0f)
        assertTrue(c.keyframes[AnimProp.STROKE_TRIM].isNullOrEmpty())
        assertFalse(r.warnings.any { it.contains("drawing.progress") })
    }

    @Test
    fun drawingProgressOnTheRealTextPresetImportsKeyframedOutlineTrim() {
        // Verbatim <text> from collected/text-motion-graphics-preset.xml ("Text Preset") — the only
        // text-layer drawing.progress in the corpus. start stays 0; end draws 0 -> 100 over the
        // first third of the 2999 ms span with AM's easeInOut cubic bezier on the end keyframe.
        // The animator carries no fill behaviour: the fill is replaced by the outline.
        val xml = """<?xml version='1.0' encoding='UTF-8' ?>
<scene title="(8)alight motion  preset" width="1920" height="1080" bgcolor="#ff000000" totalTime="44415" fps="30">
<text id="6301534" startTime="31033" endTime="34032" speed="0.677559" fillType="color" mediaFillMode="stretch" size="64.000000" font="imported?name=Destoria-Free.otf" wrapWidth="2836" align="center">
    <transform>
      <location value="954.500000,741.000000,0.000000" />
    </transform>
    <effect id="com.alightcreative.effects.texttransform" locallyApplied="false">
      <property name="start" type="float" value="0.000000" />
      <property name="end" type="float" value="1.000000" />
      <property name="phase" type="float" value="0.000000" />
      <property name="component" type="int" value="1" />
      <property name="anchor" type="int" value="0" />
      <property name="offset" type="vec2">
        <kf t="0.024594" v="-550.000000,0.000000" />
        <kf t="0.418101" v="0.000000,0.000000" e="cubicBezier 0.0 0.7334559 0.2183333 1.0" />
        <kf t="0.992130" v="-550.000000,0.000000" e="cubicBezier 0.78 0.0 1.0 0.26999998" />
      </property>
      <property name="angle" type="float">
        <kf t="0.418101" v="0.000000" />
        <kf t="0.024594" v="-5.000000" />
        <kf t="0.992130" v="5.000000" />
      </property>
      <property name="scale" type="float" value="0.000000" />
      <property name="stretch" type="float" value="0.000000" />
      <property name="alpha" type="float" value="0.000000" />
      <property name="useFillColor" type="bool" value="false" />
      <property name="fillColor" type="color" value="#ff00ff00" />
      <property name="easeIn" type="float" value="0.000000" />
      <property name="easeOut" type="float" value="0.000000" />
      <property name="overlap" type="float" value="0.000000" />
      <property name="shape" type="int" value="0" />
      <property name="randomOrder" type="bool" value="false" />
      <property name="seed" type="float" value="0.000000" />
    </effect>
    <effect id="com.alightcreative.effects.drawing.progress" locallyApplied="false">
      <property name="start" type="float" value="0.000000" />
      <property name="end" type="float">
        <kf t="0.000000" v="0.000000" />
        <kf t="0.335445" v="100.000000" e="cubicBezier 0.42 0.0 0.58 1.0" />
      </property>
    </effect>
    <shadow direction="outside" />
    <content>Text Preset</content>
  </text>
</scene>"""
        val r = AlightXmlImporter.parse(xml, 1920f)
        val c = r.snapshot.tracks.flatMap { it.clips }.single { it.text == "Text Preset" }
        assertTrue(c.strokeTrimText)
        assertEquals(0f, c.strokeTrimStart, 0f)
        assertEquals(0f, c.strokeTrimEnd, 0f)
        val keys = c.keyframes[AnimProp.STROKE_TRIM]!!
        assertEquals(2, keys.size)
        assertEquals(0L, keys[0].timeMs)
        assertEquals(1006L, keys[1].timeMs)
        assertEquals(0f, keys[0].a, 0f); assertEquals(0f, keys[0].b, 0f)
        assertEquals(0f, keys[1].a, 0f); assertEquals(1f, keys[1].b, 0f)
        assertEquals(com.example.model.Easing(0.42f, 0f, 0.58f, 1f), keys[0].easing)
        // The window evaluates through TimelineClip.at: closed at the start, half open at the
        // easing's midpoint (503 ms is exactly half the segment), fully open at the last key.
        assertEquals(0f, c.at(c.startMs).strokeTrimEnd, 0f)
        assertEquals(0.5f, c.at(c.startMs + 503).strokeTrimEnd, 0.02f)
        assertEquals(1f, c.at(c.startMs + 1006).strokeTrimEnd, 0f)
        assertEquals(0f, c.at(c.startMs + 503).strokeTrimStart, 0f)
        assertFalse(r.warnings.any { it.contains("drawing.progress") })
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
        val base = 60f * 1080f / 640f / (0.11f * 1080f) * 100f   // text units of sceneWidth / 640 px (see sizeTimesScaleBecomesScalePercent)
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
    fun solidColorOnAGroupBecomesTheFx() {
        // A group is flattened as its composed picture by the same effect (ST Intro's "Shadow"
        // pass is exactly this); before build 110 it was skipped with a warning.
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
        val g = r.snapshot.tracks.flatMap { it.clips }.first { it.type == LayerType.GROUP }
        val fx = g.effects.single { it.type == ClipFxLibrary.SOLID_COLOR }
        assertEquals(listOf(0f, 0f, 0f, 100f, 0f), fx.values)
        assertFalse(r.warnings.any { it.contains("solidcolor") })
    }

    @Test
    fun solidColorOnTheStIntroIconKeepsThePictureFill() {
        // The proving instance of round 5: ST Intro's send-message.png carries solidcolor
        // #ffb891ff. Alight flattens the ICON's silhouette to that colour (invisible against the
        // white pill); the pre-110 importer replaced the fill with the colour and painted the
        // shape's whole rectangle lavender. The fill must stay the picture now, with the colour
        // on the Solid Color effect instead.
        val clips = AlightXmlImporter.parse(stIntroGroups, 1920f).snapshot.tracks.flatMap { it.clips }
        val icon = clips.first { it.name == "send-message.png" }
        assertEquals(com.example.model.FILL_IMAGE, icon.fillMode)
        assertEquals("content://media/external/images/media/53288", icon.fillImageUri)
        val fx = icon.effects.single { it.type == ClipFxLibrary.SOLID_COLOR }
        assertEquals(listOf(184f, 145f, 255f, 100f, 0f), fx.values)
    }

    @Test
    fun pillTextProgressMatchesAlightAt1200And2000() {
        // The other proving instance of round 5: text 633220 ("Channel Name", the pill text)
        // starts at scene 666 ms inside its (factor-1) groups; its textprogress end runs
        // 0 -> 1 over t = 0.007613..0.619794 of its 2233 ms. Alight's export shows "Chann" at
        // 1.2 s (window 0.378) and the complete text at 2.0 s (window 0.963) — the intersection
        // visibility rule (TextProgressEval) is what makes those counts come out; the timing
        // chain itself (embedScene offsets, keyframe fractions) was already exact.
        val clips = AlightXmlImporter.parse(stIntroGroups, 1920f).snapshot.tracks.flatMap { it.clips }
        val pill = clips.first { c -> c.textAnimators.any { it.effectId == AlightXmlImporter.TEXTPROGRESS } }
        assertEquals(666L, pill.startMs)
        assertEquals(2233L, pill.durationMs)
        fun shownAt(ms: Long): String {
            val p = TextProgressEval.evaluate(pill, ms)!!
            return TextProgressEval.visibleText(pill.text ?: "", p.start, p.end)
        }
        assertEquals("", shownAt(683L))            // the end keyframe has not left 0 yet
        assertEquals("Chann", shownAt(1200L))      // window 0.378: 5 of 12 clusters
        assertEquals("Channel Name", shownAt(2000L))   // window 0.963: all 12
    }

    @Test
    fun unsetOutTimeSentinelMeansTheWholeNestedScene() {
        // ST Intro's "Logo Here" embed carries outTime="-2147482782" — AM's unset marker (near
        // Long.MIN_VALUE; the Graph file's "Week" has the sibling -2147483215). The window is
        // the whole 3032 ms nested scene, stretched onto the embed's 3899 ms span; before build
        // 110 the sentinel silently read as factor 1 and the logo ended at 7165 of 8032 ms.
        val xml = """<?xml version='1.0' encoding='UTF-8' ?>
<scene title="ST Intro" width="1920" height="1080" totalTime="8032" fps="30">
<embedScene id="633205" label="Logo Here" startTime="4133" endTime="8032" fillType="intrinsic" outTime="-2147482782">
  <transform><location value="960,540,0" /></transform>
  <scene title="" width="1920" height="1080" totalTime="3032" fps="30">
  <shape id="633203" label="images.png" startTime="0" endTime="3032" fillType="color" s=".rect"><transform><location value="960,540,0" /></transform><fillColor value="#ffffffff" /><property name="size" type="vec2" value="100,100" /></shape>
  </scene>
</embedScene>
</scene>"""
        val r = AlightXmlImporter.parse(xml, 1920f)
        val child = r.snapshot.tracks.flatMap { it.clips }.first { it.name == "images.png" }
        assertEquals(4133L, child.startMs)
        assertEquals(3899L, child.durationMs)   // 3032 stretched by 3899 / 3032
        assertEquals(8032L, child.endMs)
        assertTrue(r.warnings.any { it.contains("timing was stretched") })
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
        // Since v176 the standalone gaussianblur is its own op holding AM's raw
        // strength (the renderer computes the def's resolution-aware sigma); before,
        // it mapped onto the app's Blur at strength x 50.
        val c = onlyClip(shapeWith(
            """<effect id="com.alightcreative.effects.gaussianblur" locallyApplied="true"><property name="strength" type="float" value="0.5" /></effect>"""
        ))
        assertEquals(1, c.effects.size)
        assertEquals(com.example.model.ClipFxLibrary.GAUSSIAN_BLUR_AM, c.effects[0].type)
        assertEquals(0.5f, c.effects[0].values[0], 0.01f)   // AM raw strength, unscaled
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
    fun keyframedExposureRidesTheTailAndResolves() {
        val xml = shapeWith(
            """<effect id="com.alightcreative.effects.exposure" locallyApplied="true"><property name="exposure" type="float"><kf t="0" v="0.5" /><kf t="1" v="1.5" /></property><property name="gamma" type="float" value="1" /><property name="offset" type="float" value="0" /></effect>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.first { it.type == com.example.model.ClipFxLibrary.EXPOSURE }
        assertEquals(0.5f, fx.values[0], 0.001f)
        assertFalse(r.warnings.any { it.contains("keyframed exposure") })
        assertEquals(2f, fx.values[7], 0.001f)   // the EV tail: two keys, from index 7
        val mid = AmFxEval.resolve(listOf(fx), c, c.startMs + c.durationMs / 2).single()
        assertEquals(1.0f, mid.values[0], 0.001f)   // linear 0.5 -> 1.5, half-way
        val end = AmFxEval.resolve(listOf(fx), c, c.startMs + c.durationMs).single()
        assertEquals(1.5f, end.values[0], 0.001f)
        assertEquals(100f, end.values[2], 0.001f)   // gamma untouched (constant, no tail of its own)
    }

    @Test
    fun keyframedExposureAndOffsetResolveTogether() {
        // Verbatim from edit-winkoko-preset.xml: exposure pulses 0 -> 1 -> 0 (the peak key at
        // t = 0.385422 eased), offset follows it 0 -> 0.1; gamma constant. Both tracks ride
        // the tail in EV / offset / gamma order and resolve per frame.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.exposure" locallyApplied="false"><property name="exposure" type="float"><kf t="1.000000" v="0.000000" e="cubicBezier 0.0 0.0 0.0 1.0" /><kf t="0.000000" v="0.000000" /><kf t="0.385422" v="1.000000" e="cubicBezier 0.69499993 0.0 1.0 0.0" /></property><property name="gamma" type="float" value="1.000000" /><property name="offset" type="float"><kf t="0.000000" v="0.000000" /><kf t="0.385422" v="0.100000" e="cubicBezier 0.87730336 0.0 1.0 0.0" /></property></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("keyframed") })
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.first { it.type == com.example.model.ClipFxLibrary.EXPOSURE }
        assertEquals(0f, fx.values[0], 0.001f)
        assertEquals(3f, fx.values[7], 0.001f)   // EV tail: 3 keys
        // 771 ms in = rel 0.3855, just past the peak key at t = 0.385422: EV is back at
        // ~0.993 on the eased fall, the offset track (2 keys) clamps to its last value.
        val peak = AmFxEval.resolve(listOf(fx), c, c.startMs + 771L).single()
        assertEquals(1.0f, peak.values[0], 0.01f)    // the EV peak
        assertEquals(10f, peak.values[1], 0.01f)     // offset 0.1 -> 10 at its last key
        assertEquals(100f, peak.values[2], 0.001f)   // gamma constant
    }

    @Test
    fun tileKeyframedScaleResolvesBothAxes() {
        // Verbatim from am-renderer-preset10.xml: scale 1.2 -> 1.0 -> 1.2 (the segment easings
        // are AM's own), phase constant, mirrored edges, no vertical offset.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.tile" locallyApplied="false"><property name="mirror" type="bool" value="true" /><property name="scale" type="float"><kf t="0.475269" v="1.000000" e="cubicBezier 0.0 0.0 0.0 1.0" /><kf t="0.023656" v="1.200000" e="cubicBezier 0.0 0.0 0.0 1.0" /><kf t="0.950538" v="1.200000" e="cubicBezier 1.0 0.0 1.0 1.0" /></property><property name="phase" type="float" value="0.000000" /><property name="vertoffs" type="bool" value="false" /><property name="angle" type="float" value="0.000000" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("keyframed") })
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.first { it.type == ClipFxLibrary.TILE }
        assertEquals(1.2f, fx.values[0], 0.001f)   // clamped to the first key at t = 0
        assertEquals(1.2f, fx.values[1], 0.001f)
        assertEquals(1f, fx.values[2], 0.001f)     // mirror
        val dip = AmFxEval.resolve(listOf(fx), c, c.startMs + 950L).single()   // rel 0.475, at the 1.0 key
        assertEquals(1.0f, dip.values[0], 0.01f)
        assertEquals(1.0f, dip.values[1], 0.01f)   // one track drives both axes
        val back = AmFxEval.resolve(listOf(fx), c, c.startMs + c.durationMs).single()
        assertEquals(1.2f, back.values[0], 0.01f)
    }

    @Test
    fun tileKeyframedPhaseSweepsWholeCycles() {
        // Verbatim from am-renderer-preset10.xml: phase 0 -> 7.015 -> 0 across the last sixth
        // of the layer (whole cycles — the tile shader's fract/mod keeps the scroll faithful),
        // scale constant. vertoffs is on: a discrete mode with no counterpart, still reported.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.tile" locallyApplied="true"><property name="scale" type="float" value="1.000000" /><property name="phase" type="float"><kf t="0.834290" v="0.000000" /><kf t="0.915164" v="7.015000" e="cubicBezier 0.0 0.0 0.0 1.0" /><kf t="1.000000" v="0.000000" e="cubicBezier 1.0 0.0 1.0 1.0" /></property><property name="mirror" type="bool" value="true" /><property name="vertoffs" type="bool" value="true" /><property name="angle" type="float" value="0.000000" /></effect>"""
        ), 1080f)
        assertTrue(r.warnings.any { it.contains("its vertical offset is not reproduced") })
        assertFalse(r.warnings.any { it.contains("keyframed") })
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.first { it.type == ClipFxLibrary.TILE }
        assertEquals(0f, fx.values[7], 0.001f)   // scale is constant: a count-0 placeholder
        assertEquals(3f, fx.values[8], 0.001f)   // then the phase track: three keys
        val peak = AmFxEval.resolve(listOf(fx), c, c.startMs + 1830L).single()   // rel 0.915, at the 7.015 key
        assertEquals(701.5f, peak.values[3], 0.5f)
        assertEquals(701.5f, peak.values[4], 0.5f)
        assertEquals(1f, peak.values[0], 0.001f)   // scale untouched
    }

    @Test
    fun satvibKeyframedSaturationResolvesAndVibIsRead() {
        // Verbatim from am-renderer-preset7.xml: saturation -1 -> 0 (keys out of order, the
        // closing key eased), vib constant 1. The tail sits in the Hue / Saturation order:
        // a count-0 hue placeholder, then the saturation track.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.satvib" locallyApplied="false"><property name="saturation" type="float"><kf t="1.359515" v="0.000000" /><kf t="0.000000" v="-1.000000" /><kf t="0.994891" v="0.000000" e="cubicBezier 0.0 0.0 0.0 1.0" /></property><property name="vib" type="float" value="1.000000" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("keyframed") })
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.first { it.type == ClipFxLibrary.HUE_SAT }
        assertEquals(-100f, fx.values[1], 0.001f)   // saturation -1 at t = 0
        assertEquals(0f, fx.values[7], 0.001f)      // hue placeholder
        assertEquals(3f, fx.values[8], 0.001f)      // saturation track: three keys
        val done = AmFxEval.resolve(listOf(fx), c, c.startMs + c.durationMs).single()
        assertEquals(0f, done.values[1], 0.001f)
        // Verbatim from am-renderer-preset3.xml: vib is 2 there — before build 117 the
        // importer read a `vibrance` property Alight never writes and lost it.
        val c2 = onlyClip(shapeWith(
            """<effect id="com.alightcreative.effects.satvib" locallyApplied="true"><property name="saturation" type="float" value="-1.000000" /><property name="vib" type="float" value="2.000000" /></effect>"""
        ))
        val fx2 = c2.effects.first { it.type == ClipFxLibrary.HUE_SAT }
        assertEquals(-100f, fx2.values[1], 0.001f)
        assertEquals(100f, fx2.values[3], 0.001f)   // (2 - 1) x 100
    }

    @Test
    fun hueshiftKeyframedOnAMediaShapeRidesTheTailInRawDegrees() {
        // Verbatim from am-renderer-preset2.xml: hue sweeps 1190 -> 0 (three full turns and a
        // third) on a PICTURE-filled shape — not bakeable into a fill colour, so the shift
        // rides the Hue / Saturation tail in raw degrees (the engine fmods by 360).
        val xml = scene(
            """<shape id="9" label="pic.png" startTime="0" endTime="2000" fillType="media" fillImage="content://media/external/images/media/123" mediaFillMode="stretch" s=".rect">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <effect id="com.alightcreative.effects.hueshift" locallyApplied="false"><property name="hue" type="float"><kf t="0.956522" v="0.000000" /><kf t="0.000000" v="1190.000000" /></property></effect>
  <property name="size" type="vec2" value="100,100" /></shape>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        assertFalse(r.warnings.any { it.contains("keyframed hue") })
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        assertNull(c.keyframes[AnimProp.FILL_COLOR])   // nothing was baked
        val fx = c.effects.first { it.type == ClipFxLibrary.HUE_SAT }
        assertEquals(1190f, fx.values[0], 0.001f)   // raw degrees at t = 0
        val mid = AmFxEval.resolve(listOf(fx), c, c.startMs + 957L).single()   // rel 0.4785
        assertEquals(594.7f, mid.values[0], 1.5f)   // linear: 1190 x (1 - 0.4785 / 0.956522)
        val end = AmFxEval.resolve(listOf(fx), c, c.startMs + c.durationMs).single()
        assertEquals(0f, end.values[0], 0.001f)
    }

    @Test
    fun gaussianBlurKeyframedStrengthResolves() {
        // Verbatim from am-renderer-preset10.xml: strength 0.3 -> 0, the closing key eased.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.gaussianblur" locallyApplied="false"><property name="strength" type="float"><kf t="0.000000" v="0.300000" /><kf t="0.384120" v="0.000000" e="cubicBezier 0.0 0.7117647 0.58 1.0" /></property></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("keyframed") })
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.first { it.type == ClipFxLibrary.GAUSSIAN_BLUR_AM }
        assertEquals(0.3f, fx.values[0], 0.001f)   // AM raw strength at t = 0
        assertEquals(2f, fx.values[7], 0.001f)    // the tail: two keys
        val end = AmFxEval.resolve(listOf(fx), c, c.startMs + c.durationMs).single()
        assertEquals(0f, end.values[0], 0.001f)
    }

    @Test
    fun dblurKeyframedStrengthResolves() {
        // Verbatim from am-renderer-preset8.xml: strength blips 0 -> 0.05 -> 0 mid-layer.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.dblur" locallyApplied="false"><property name="strength" type="float"><kf t="0.486789" v="0.050000" e="cubicBezier 0.8458706 0.0 1.0 1.0" /><kf t="0.166533" v="0.000000" /><kf t="0.794235" v="0.000000" e="cubicBezier 0.0 0.0 0.14999998 1.0" /></property><property name="angle" type="float" value="0.000000" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("keyframed") })
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.first { it.type == ClipFxLibrary.DIR_BLUR }
        assertEquals(0f, fx.values[1], 0.001f)   // clamped to the first key (0) at t = 0
        val peak = AmFxEval.resolve(listOf(fx), c, c.startMs + 974L).single()   // rel 0.487, just past the blip key
        assertEquals(5f, peak.values[1], 0.25f)   // 0.05 x 100
    }

    @Test
    fun zoomblur3KeyframedStrengthResolves() {
        // Verbatim from am-renderer-preset2.xml: strength 0 -> 0.65 -> 0, both segments eased.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.zoomblur3" locallyApplied="false"><property name="strength" type="float"><kf t="0.000000" v="0.000000" /><kf t="0.541118" v="0.650000" e="cubicBezier 1.0 0.0 1.0 1.0" /><kf t="0.989309" v="0.000000" e="cubicBezier 0.0 0.0 0.0 1.0" /></property><property name="adaptive" type="bool" value="true" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("keyframed") })
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.first { it.type == ClipFxLibrary.RADIAL_BLUR }
        assertEquals(0f, fx.values[0], 0.001f)
        val near = AmFxEval.resolve(listOf(fx), c, c.startMs + 1083L).single()   // rel 0.5415, just past the peak key
        assertEquals(63.4f, near.values[0], 0.5f)   // 0.65 easing out on the (0,0,0,1) curve
    }

    @Test
    fun motionblur4KeyframedTuneResolves() {
        // Verbatim from am-renderer-preset6.xml: tune 1.0 -> 1.7 over the last sixth, all
        // three motion kinds on.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.motionblur4" locallyApplied="true"><property name="tune" type="float"><kf t="0.834339" v="1.000000" /><kf t="0.998298" v="1.700000" /></property><property name="usePos" type="bool" value="true" /><property name="useScale" type="bool" value="true" /><property name="useAngle" type="bool" value="true" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("keyframed tune") })
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.first { it.type == ClipFxLibrary.MOTION_BLUR4 }
        assertEquals(100f, fx.values[0], 0.001f)   // clamped to the first key at t = 0
        val end = AmFxEval.resolve(listOf(fx), c, c.startMs + c.durationMs).single()
        assertEquals(170f, end.values[0], 0.001f)
    }

    @Test
    fun motionblur3ImportsAndItsTuneResolvesPastOne() {
        // Verbatim from am-renderer-preset3.xml: tune 1.0 -> 4.0 over the second half.
        // motionblur3 carries the family's exact property set (109 corpus instances) but was
        // missing from the dispatch until build 117 — it warn-and-skipped like an unknown id.
        // The tail holds tune raw, so the resolved strength keeps the 4x peak (the fx slot's
        // own 0..100 coercion applies only to the stored t = 0 value).
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.motionblur3" locallyApplied="true"><property name="tune" type="float"><kf t="0.500000" v="1.000000" /><kf t="0.991266" v="4.000000" /></property></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("motionblur3") && it.contains("not supported") })
        assertFalse(r.warnings.any { it.contains("keyframed tune") })
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.first { it.type == ClipFxLibrary.MOTION_BLUR }
        assertEquals(100f, fx.values[0], 0.001f)
        assertEquals(1f, fx.values[1], 0.001f)   // usePos defaults on
        val end = AmFxEval.resolve(listOf(fx), c, c.startMs + c.durationMs).single()
        assertEquals(400f, end.values[0], 0.001f)   // tune 4.0 x 100, uncoerced from the tail
    }

    @Test
    fun pinchbulge2KeyframedStrengthResolves() {
        // Verbatim from presetrezz.xml: strength dips to -0.44 mid-layer (the dip key's easing
        // overshoots below -1 in y — AM's own curve), radius 0.61.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.pinchbulge2" locallyApplied="false"><property name="strength" type="float"><kf t="0.000000" v="0.000000" e="cubicBezier 0.0 0.0 0.19 1.0" /><kf t="1.000000" v="0.000000" e="cubicBezier 0.0 0.0 0.19 1.0" /><kf t="0.523297" v="-0.440000" e="cubicBezier 0.79 -1.0514705 1.0 1.0" /></property><property name="radius" type="float" value="0.610000" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("keyframed") })
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.first { it.type == ClipFxLibrary.BULGE }
        assertEquals(61f, fx.values[1], 0.01f)   // radius %
        val dip = AmFxEval.resolve(listOf(fx), c, c.startMs + 1047L).single()   // rel 0.5235, just past the dip key
        assertEquals(-44f, dip.values[0], 1f)
    }

    @Test
    fun brightcont2KeyframedBrightnessResolves() {
        // Verbatim from zooms-toji-pack.xml: brightness 1 -> 0 over the first two thirds,
        // contrast constant 0.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.brightcont2" locallyApplied="true"><property name="brightness" type="float"><kf t="0.631890" v="0.000000" e="cubicBezier 0.0 0.0 0.0 1.0" /><kf t="0.000000" v="1.000000" /></property><property name="contrast" type="float" value="0.000000" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("keyframed") })
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.first { it.type == ClipFxLibrary.BRIGHT_CONTRAST }
        assertEquals(100f, fx.values[0], 0.001f)
        assertEquals(2f, fx.values[7], 0.001f)   // the brightness track: two keys
        val end = AmFxEval.resolve(listOf(fx), c, c.startMs + c.durationMs).single()
        assertEquals(0f, end.values[0], 0.001f)
        assertEquals(0f, end.values[1], 0.001f)
    }

    @Test
    fun mirrorKeyframedOffsetResolvesBehindTheAlphaPlaceholder() {
        // No corpus instance animates mirror's offset (type / blendMode never animate either —
        // they are discrete and stay first-value); the plumbing carries it as the second tail,
        // behind a count-0 placeholder for the constant alpha.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.mirror" locallyApplied="false"><property name="type" type="int" value="0" /><property name="blendMode" type="int" value="0" /><property name="alpha" type="float" value="1.000000" /><property name="offset" type="float"><kf t="0.000000" v="0.000000" /><kf t="1.000000" v="0.500000" /></property></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("keyframed") })
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.first { it.type == ClipFxLibrary.AM_MIRROR }
        assertEquals(0f, fx.values[7], 0.001f)   // alpha placeholder
        assertEquals(2f, fx.values[8], 0.001f)   // offset track: two keys
        val mid = AmFxEval.resolve(listOf(fx), c, c.startMs + c.durationMs / 2).single()
        assertEquals(25f, mid.values[3], 0.001f)   // 0.25 x 100
        assertEquals(100f, mid.values[2], 0.001f)  // alpha untouched
    }

    @Test
    fun glowScanKeyframedStrengthResolvesBehindPlaceholders() {
        // No corpus instance animates glowscan's secondaries either; strength rides as the
        // fourth tail, behind placeholders for the constant threshold / radius / power.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.glowscan" locallyApplied="false"><property name="threshold" type="float" value="0.500000" /><property name="radius" type="float" value="0.100000" /><property name="power" type="float" value="2.000000" /><property name="strength" type="float"><kf t="0.000000" v="0.050000" /><kf t="1.000000" v="0.200000" /></property><property name="fillColor" type="color" value="#ff0088ff" /><property name="blend" type="float" value="0.000000" /><property name="fill" type="float" value="1.000000" /><property name="alpha" type="float" value="1.000000" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("keyframed") })
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.first { it.type == ClipFxLibrary.GLOW_SCAN }
        assertEquals(0f, fx.values[7], 0.001f)    // threshold placeholder
        assertEquals(0f, fx.values[8], 0.001f)    // radius placeholder
        assertEquals(0f, fx.values[9], 0.001f)    // power placeholder
        assertEquals(2f, fx.values[10], 0.001f)   // strength track: two keys
        val end = AmFxEval.resolve(listOf(fx), c, c.startMs + c.durationMs).single()
        assertEquals(20f, end.values[3], 0.001f)   // 0.2 x 100
        assertEquals(50f, end.values[0], 0.001f)   // threshold untouched
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
    fun edgeGlowBecomesNativeEdgeGlow() {
        // Build 128: edgeglow is a faithful native port (its def's pass chain), not the
        // old Glow approximation. Params the fixture omits take AM's def defaults.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.edgeglow" locallyApplied="true"><property name="threshold" type="float" value="0.23" /><property name="strength" type="float" value="0.3" /><property name="spread" type="float" value="1.5" /><property name="fillColor" type="color" value="#ffffffff" /></effect>"""
        ), 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.single { it.type == com.example.model.ClipFxLibrary.EDGE_GLOW_AM }
        assertEquals(5f, fx.values[0], 0.01f)        // smoothing: the def's default
        assertEquals(0.23f, fx.values[1], 0.01f)
        assertEquals(0f, fx.values[2], 0.01f)        // invert
        assertEquals(0.3f, fx.values[3], 0.01f)      // strength (the blur)
        assertEquals(1.5f, fx.values[4], 0.01f)      // spread
        assertEquals(0xFFFFFF.toFloat(), fx.values[5], 0.01f)
        assertEquals(0f, fx.values[6], 0.01f)        // blend: the def's default
        assertEquals(1f, fx.values[7], 0.01f)        // gamma
        assertEquals(0f, fx.values[8], 0.01f)        // brightness
        assertEquals(0f, fx.values[9], 0.01f)        // tintColor: black packed
        assertEquals(0f, fx.values[10], 0.01f)       // tint
        val flat = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(129f, flat[0], 0f)
        assertEquals(130f, flat[8], 0f)              // the EXT group rides behind its owner
        assertTrue(r.warnings.none { it.contains("edgeglow") })
    }

    @Test
    fun darkGlowOnAGroupBecomesNativeDarkGlow() {
        // All three corpus darkglow instances sit on (nested) groups in the Graph preset;
        // since build 120 the group carries the native Dark Glow, not the Glow approximation.
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
        val fx = g.effects.first { it.type == ClipFxLibrary.DARK_GLOW_AM }
        assertEquals(25f, fx.values[0], 0.01f)
        assertEquals(47.3f, fx.values[1], 0.01f)
        assertEquals(0xFF5566.toFloat(), fx.values[2], 0.01f)
        assertEquals(25f, fx.values[3], 0.01f)   // no blend in the fixture: the def's default 0.25
        assertEquals(75f, fx.values[4], 0.01f)
        assertTrue(r.warnings.none { it.contains("darkglow is approximated") })
    }

    @Test
    fun longShadowStackImportsFromTheFreefirePreset() {
        // The freefire preset stacks longshadow AND longshadow2 on one text layer (a soft faint one
        // under a hard full one); both map onto the app's Long Shadow. A text layer's box is the
        // scene, so Alight's size (a fraction of the scene height) becomes that % directly.
        val xml = scene(
            """<text id="291408" startTime="0" endTime="2000" fillType="color" size="32.000000" font="googlefonts?name=Open Sans&amp;weight=800&amp;italic=1" wrapWidth="937" align="center">
  <transform><location value="540.000000,960.000000" /></transform>
  <effect id="com.alightcreative.effects.longshadow">
    <property name="size" type="float" value="0.019000" /><property name="angle" type="float" value="45.000000" />
    <property name="color" type="color" value="#ff000000" /><property name="alpha" type="float" value="0.153000" />
    <property name="solidity" type="float" value="0.626000" /><property name="fill" type="float" value="1.000000" />
    <property name="fade" type="float" value="0.000000" /><property name="fadeType" type="int" value="0" />
  </effect>
  <effect id="com.alightcreative.effects.longshadow2">
    <property name="size" type="float" value="0.019000" /><property name="angle" type="float" value="45.000000" />
    <property name="color" type="color" value="#ff000000" /><property name="alpha" type="float" value="1.000000" />
    <property name="solidity" type="float" value="1.000000" /><property name="fill" type="float" value="1.000000" />
    <property name="fade" type="float" value="0.000000" /><property name="fadeType" type="int" value="0" />
  </effect>
  <content>MENTAL</content>
</text>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.filter { it.type == ClipFxLibrary.LONG_SHADOW }
        assertEquals(2, fx.size)
        assertEquals(1.9f, fx[0].values[0], 0.01f)
        assertEquals(45f, fx[0].values[1], 0.01f)
        assertEquals(15.3f, fx[0].values[2], 0.01f)
        assertEquals(62.6f, fx[0].values[3], 0.01f)
        assertEquals(0f, fx[0].values[4], 0.01f)
        assertEquals(0f, fx[0].values[5], 0.01f)   // black, packed 0xRRGGBB
        assertEquals(100f, fx[1].values[2], 0.01f)
        assertEquals(100f, fx[1].values[3], 0.01f)
        assertFalse(r.warnings.any { it.contains("longshadow") && it.contains("skipped") })
    }

    @Test
    fun longShadow2KeyframedSizeRidesTheTailPreConverted() {
        // The toji pack: size keyframed 0.075 -> 0.014 under a 202 degree angle, in a square scene.
        val xml = scene(
            """<text id="7" startTime="0" endTime="2000" fillType="color" size="56.000000" font="googlefonts?name=Roboto&amp;weight=900" wrapWidth="900" align="center">
  <transform><location value="540.000000,540.000000" /></transform>
  <effect id="com.alightcreative.effects.longshadow2" locallyApplied="false">
    <property name="alpha" type="float" value="1.000000" />
    <property name="angle" type="float" value="202.000000" />
    <property name="size" type="float"><kf t="0.000000" v="0.075000" /><kf t="2.164339" v="0.014000" /></property>
    <property name="color" type="color" value="#ff000000" />
    <property name="solidity" type="float" value="1.000000" />
    <property name="fill" type="float" value="1.000000" />
    <property name="fade" type="float" value="0.000000" />
    <property name="fadeType" type="int" value="0" />
  </effect>
  <content>TOJI</content>
</text>""", 1080, 1080
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.first { it.type == ClipFxLibrary.LONG_SHADOW }
        assertEquals(7.5f, fx.values[0], 0.01f)
        assertEquals(202f, fx.values[1], 0.01f)
        assertFalse(r.warnings.any { it.contains("longshadow2") && it.contains("keyframed size") })
        // A text layer's box is the scene, so the tail keys are pre-converted at x100:
        // 0.075 -> 7.5 and 0.014 -> 1.4. At the layer's end the segment (which runs to
        // t = 2.164339, past the span) is at f = 1 / 2.164339: 7.5 - 6.1 x 0.46204 = 4.68.
        val end = AmFxEval.resolve(listOf(fx), c, c.startMs + c.durationMs).single()
        assertEquals(4.68f, end.values[0], 0.05f)
    }

    @Test
    fun raysImportsFromTheTextMotionPreset() {
        // Text-motion preset, first rays: centre keyframed (the earliest key wins), blue fill colour.
        val xml = scene(
            """<text id="6301517" startTime="0" endTime="2000" fillType="color" size="32.000000" font="googlefonts?name=Roboto&amp;weight=900" wrapWidth="961" align="center">
  <transform><location value="960.000000,540.000000" /></transform>
  <effect id="com.alightcreative.effects.rays" locallyApplied="false">
    <property name="centerPoint" type="vec2"><kf t="0.500000" v="130.000000,40.000000" /><kf t="0.007874" v="-160.000000,40.000000" /><kf t="0.975886" v="-160.000000,40.000000" /></property>
    <property name="strength" type="float" value="0.300000" />
    <property name="intensity" type="float" value="3.080000" />
    <property name="threshold" type="float" value="0.990000" />
    <property name="thresholdColor" type="color" value="#ff000000" />
    <property name="fillColor" type="color" value="#ff2d1ef6" />
    <property name="blend" type="float" value="0.000000" />
    <property name="quality" type="float" value="150.000000" />
  </effect>
  <content>RAY</content>
</text>""", 1920, 1080
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.first { it.type == ClipFxLibrary.LIGHT_RAYS }
        assertEquals(99f, fx.values[0], 0.01f)          // threshold 0.99 -> %
        assertEquals(9.9f, fx.values[1], 0.01f)         // strength 0.3 x 33
        assertEquals(300f, fx.values[2], 0.01f)         // intensity 3.08 x 100, capped at 300
        assertEquals(41.67f, fx.values[3], 0.05f)       // centre: 50 - 160 / 1920 x 100
        assertEquals(53.70f, fx.values[4], 0.05f)       // 50 + 40 / 1080 x 100
        assertEquals(244.17f, fx.values[5], 0.5f)       // hue of #2d1ef6
        assertEquals(100f, fx.values[6], 0.01f)
        assertFalse(r.warnings.any { it.contains("rays") && it.contains("keyframed centerPoint") })
        assertTrue(r.warnings.any { it.contains("rays is approximated by the app's Light Rays") })
        assertFalse(r.warnings.any { it.contains("rays") && it.contains("blend") })
        // The centre rides two pre-converted tails: at rel 0.5 the key (130, 40) lands exactly:
        // cx = 50 + 130 / 1920 x 100 = 56.77, cy = 50 + 40 / 1080 x 100 = 53.70.
        val mid = AmFxEval.resolve(listOf(fx), c, c.startMs + c.durationMs / 2).single()
        assertEquals(56.77f, mid.values[3], 0.05f)
        assertEquals(53.70f, mid.values[4], 0.05f)
    }

    @Test
    fun raysKeyframedStrengthRidesTheTailPreConverted() {
        // Toji pack, second rays: strength keyframed from 0, centre constant at (65, 0) layer px.
        val xml = scene(
            """<text id="8" startTime="0" endTime="2000" fillType="color" size="56.000000" font="googlefonts?name=Roboto&amp;weight=900" wrapWidth="900" align="center">
  <transform><location value="540.000000,540.000000" /></transform>
  <effect id="com.alightcreative.effects.rays" locallyApplied="false">
    <property name="centerPoint" type="vec2" value="65.000000,0.000000" />
    <property name="strength" type="float"><kf t="0.083158" v="0.000000" /><kf t="0.364211" v="3.030000" /><kf t="0.995789" v="0.000000" /></property>
    <property name="intensity" type="float" value="4.650000" />
    <property name="threshold" type="float" value="0.600000" />
    <property name="thresholdColor" type="color" value="#ff000000" />
    <property name="fillColor" type="color" value="#ff2d1ef6" />
    <property name="blend" type="float" value="0.000000" />
    <property name="quality" type="float" value="150.000000" />
  </effect>
  <content>TOJI</content>
</text>""", 1080, 1080
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = c.effects.first { it.type == ClipFxLibrary.LIGHT_RAYS }
        assertEquals(60f, fx.values[0], 0.01f)
        assertEquals(0f, fx.values[1], 0.01f)           // the first strength key is 0
        assertEquals(300f, fx.values[2], 0.01f)         // 4.65 x 100, capped
        assertEquals(56.02f, fx.values[3], 0.05f)       // 50 + 65 / 1080 x 100
        assertEquals(50f, fx.values[4], 0.01f)
        assertFalse(r.warnings.any { it.contains("rays") && it.contains("keyframed strength") })
        // The strength tail is pre-converted at x33: at the peak key (t = 0.364211, ~728 ms)
        // the length is 3.03 x 33 = 99.99; the constant centre contributes placeholders only.
        val peak = AmFxEval.resolve(listOf(fx), c, c.startMs + 728L).single()
        assertEquals(99.99f, peak.values[1], 0.5f)
        assertEquals(56.02f, peak.values[3], 0.05f)
    }

    @Test
    fun colorTuneImportsFromTheVetriPreset() {
        // The vetri preset's colortune2 values: lift / gain vec3s of (hue, strength, luminance shift).
        // The preset's own element carries hidden="true" — since build 102 a hidden effect is disabled
        // and skipped at import (see hiddenEffectsAreSkippedWithOneWarningPerLayer), so the attribute is
        // dropped here to keep verifying the value mapping on an active effect.
        val c = onlyClip(shapeWith(
            """<effect id="com.alightcreative.effects.colortune2" locallyApplied="false"><property name="lift" type="vec3" value="-118.477776,0.347525,-0.080692" /><property name="gain" type="vec3" value="-117.208038,0.258421,0.000000" /></effect>"""
        ))
        val fx = c.effects.first { it.type == ClipFxLibrary.COLOR_TUNE }
        assertEquals(241.52f, fx.values[0], 0.05f)   // -118.478 wrapped into 0..360
        assertEquals(34.75f, fx.values[1], 0.01f)
        assertEquals(-8.07f, fx.values[2], 0.01f)
        assertEquals(242.79f, fx.values[3], 0.05f)
        assertEquals(25.84f, fx.values[4], 0.01f)
        assertEquals(0f, fx.values[5], 0.01f)
    }

    @Test
    fun hiddenEffectOnTheSubscriberLayerIsSkipped() {
        // The "Subscriber & follow animation" file (1c3ff763…): the images.jpeg layer's only effect is
        // a hidden exposure. hidden="true" disables an effect in Alight, so nothing of it is imported
        // (before build 102 it became a live Exposure fx) and the layer reports it once.
        val xml = scene(
            """<shape id="635471" label="images.jpeg" hidden="true" startTime="0" endTime="6332" fillType="media" fillImage="content://media/external/images/media/1000184628" mediaFillMode="stretch" s=".rect"><transform><location value="960.000000,540.000000,0.000000" /><scale value="1.370139,1.370139" /></transform><effect id="com.alightcreative.effects.exposure" hidden="true" locallyApplied="true"><property name="exposure" type="float" value="0.160000" /><property name="gamma" type="float" value="1.285000" /><property name="offset" type="float" value="-0.690000" /></effect><property name="size" type="vec2" value="720.000000,540.000000" /></shape>""",
            1920, 1080
        )
        val r = AlightXmlImporter.parse(xml, 1920f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        assertTrue(c.effects.isEmpty())
        val hiddenWarnings = r.warnings.filter { it.contains("hidden effects are disabled") }
        assertEquals(1, hiddenWarnings.size)
        assertEquals(
            "Layer \"images.jpeg\": hidden effects are disabled in Alight Motion: skipped (exposure)",
            hiddenWarnings[0]
        )
    }

    @Test
    fun hiddenOscillateBakesNoPositionKeys() {
        // A hidden oscillate is disabled in Alight: it must not bake the position keyframes a visible
        // one bakes (see oscillateBakesPositionKeyframes for the visible control).
        val c = onlyClip(shapeWith(
            """<effect id="com.alightcreative.effects.oscillate" hidden="true" locallyApplied="false"><property name="angle" type="float" value="90" /><property name="freq" type="float" value="1" /><property name="mag" type="float" value="100" /><property name="type" type="int" value="0" /></effect>"""
        ))
        assertTrue(c.effects.isEmpty())
        assertTrue(c.keyframes[AnimProp.POSITION].isNullOrEmpty())
    }

    @Test
    fun hiddenTextProgressIsNotStored() {
        // The text reader stores textprogress as an animator; a hidden one is disabled in Alight, so
        // nothing is stored (applyLayerEffects still reports it in the layer's hidden-effects warning).
        val xml = scene(
            """<text id="1" label="A" startTime="0" endTime="2000" size="30" font="googlefonts?name=Roboto&amp;weight=400">
  <effect id="com.alightcreative.effects.textprogress" hidden="true" locallyApplied="true"><property name="start" type="float" value="0" /><property name="end" type="float"><kf t="0" v="0" /><kf t="0.6" v="1" /></property><property name="cursor" type="int" value="0" /><property name="blink" type="bool" value="false" /></effect>
  <content>HELLO</content></text>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        assertTrue(c.textAnimators.isEmpty())
        val hiddenWarnings = r.warnings.filter { it.contains("hidden effects are disabled") }
        assertEquals(1, hiddenWarnings.size)
        assertTrue(hiddenWarnings[0].contains("(textprogress)"))
    }

    @Test
    fun hiddenEffectsOnTheFreefirePathLayerAreSkippedNowThatItImports() {
        // Verbatim layer 291434 of collected/edit-freefire-mental.xml: SIX hidden effects (oscillate,
        // swing, oscillate, motionblur2, exposure, spotcolor2) sit between visible ones. Until build
        // 102 the layer — a freehand-path media shape (no `s` kind, a <path> child) — was skipped
        // before any effect was read, and this test pinned that. Since build 103 the path imports,
        // so the layer produces a clip and its effects ARE read: the hidden six are skipped by the
        // hidden check (13 of the corpus's 16 hidden effects sit on such path layers — this file
        // holds 8 of them, shake-997 the other 5) with ONE aggregate warning, and only the visible
        // tile / exposure / oscillate / swing apply.
        val xml = scene(
            """<shape id="291434" label="Kasih Nama Woi 2_720p.mp4" startTime="11100" endTime="13499" fillType="media" fillVideo="content://media/external/video/media/813" inTime="11066" outTime="2147483613">
    <transform>
      <location value="540.000000,960.000000" />
    </transform>
    <effect id="com.alightcreative.effects.tile">
      <property name="scale" type="float">
        <kf t="0.000000" v="2.000000" />
        <kf t="0.467213" v="1.000000" e="cubicBezier 0.0 1.0 0.58 1.0" />
        <kf t="1.000000" v="2.000000" e="cubicBezier 0.42 0.0 1.0 0.0" />
      </property>
      <property name="phase" type="float" value="0.000000" />
      <property name="mirror" type="bool" value="true" />
      <property name="vertoffs" type="bool" value="false" />
      <property name="angle" type="float" value="0.000000" />
    </effect>
    <effect id="com.alightcreative.effects.oscillate" hidden="true">
      <property name="angle" type="float">
        <kf t="0.000000" v="118.800003" />
        <kf t="1.000000" v="32.400002" e="cubicBezier 0.42 0.0 0.58 1.0" />
      </property>
      <property name="freq" type="float" value="4.452000" />
      <property name="mag" type="float">
        <kf t="0.000000" v="334.329987" />
        <kf t="1.000000" v="1.000000" e="cubicBezier 0.0 0.61455435 0.100250795 1.0" />
      </property>
      <property name="type" type="int" value="0" />
    </effect>
    <effect id="com.alightcreative.effects.swing" hidden="true">
      <property name="freq" type="float" value="2.000000" />
      <property name="a1" type="float">
        <kf t="0.000000" v="-10.000000" />
        <kf t="1.000000" v="0.000000" e="cubicBezier 0.0 0.52464634 0.47215346 1.0" />
      </property>
      <property name="a2" type="float">
        <kf t="0.000000" v="10.000000" />
        <kf t="1.000000" v="0.000000" e="cubicBezier 0.0 0.60694385 0.58 1.0" />
      </property>
      <property name="phase" type="float" value="0.980000" />
      <property name="type" type="int" value="0" />
    </effect>
    <effect id="com.alightcreative.effects.oscillate" hidden="true">
      <property name="angle" type="float" value="0.000000" />
      <property name="freq" type="float" value="2.000000" />
      <property name="mag" type="float">
        <kf t="0.000000" v="499.000000" />
        <kf t="1.000000" v="1.000000" e="cubicBezier 0.0 0.6326618 0.58 1.0" />
      </property>
      <property name="type" type="int" value="0" />
    </effect>
    <effect id="com.alightcreative.effects.motionblur2" hidden="true">
      <property name="tune" type="float" value="1.000000" />
      <property name="usePos" type="bool" value="true" />
      <property name="useScale" type="bool" value="true" />
      <property name="useAngle" type="bool" value="true" />
    </effect>
    <effect id="com.alightcreative.effects.exposure" hidden="true">
      <property name="exposure" type="float">
        <kf t="0.000000" v="2.000000" />
        <kf t="0.485866" v="0.000000" e="cubicBezier 0.0 0.5306417 0.58 1.0" />
      </property>
      <property name="gamma" type="float" value="1.000000" />
      <property name="offset" type="float" value="0.000000" />
    </effect>
    <effect id="com.alightcreative.spotcolor2" hidden="true">
      <property name="choke" type="float" value="2.220000" />
      <property name="color" type="color" value="#ffff4444" />
      <property name="feather" type="float" value="0.000000" />
      <property name="threshold" type="float" value="2.000000" />
      <property name="saturation" type="float" value="0.000000" />
    </effect>
    <effect id="com.alightcreative.effects.exposure">
      <property name="exposure" type="float" value="0.800000" />
      <property name="gamma" type="float" value="0.620000" />
      <property name="offset" type="float" value="0.000000" />
    </effect>
    <effect id="com.alightcreative.effects.oscillate">
      <property name="angle" type="float" value="0.000000" />
      <property name="freq" type="float" value="1.272000" />
      <property name="mag" type="float" value="25.000000" />
      <property name="type" type="int" value="0" />
    </effect>
    <effect id="com.alightcreative.effects.swing">
      <property name="freq" type="float" value="0.290000" />
      <property name="a1" type="float">
        <kf t="1.343738" v="0.000000" e="cubicBezier 0.0 0.52464634 0.47215346 1.0" />
        <kf t="0.000000" v="-3.000000" />
      </property>
      <property name="a2" type="float">
        <kf t="1.343738" v="0.000000" e="cubicBezier 0.0 0.60694385 0.58 1.0" />
        <kf t="0.000000" v="3.000000" />
      </property>
      <property name="phase" type="float" value="0.980000" />
      <property name="type" type="int" value="0" />
    </effect>
    <effect id="com.alightcreative.effects.tile">
      <property name="scale" type="float" value="1.000000" />
      <property name="phase" type="float" value="0.000000" />
      <property name="mirror" type="bool" value="true" />
      <property name="vertoffs" type="bool" value="false" />
      <property name="angle" type="float" value="0.000000" />
    </effect>
    <gain value="0.000000" />
    <path d="M -540.0 -960.0L 540.0 -960.0L 540.0 960.0L -540.0 960.0L -540.0 -960.0Z" />
  </shape>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        // The layer imports as a PATH clip (the full-frame media quad, 1080x1920 in layer units).
        val clips = r.snapshot.tracks.flatMap { it.clips }
        assertEquals(1, clips.size)
        val c = clips[0]
        assertEquals("PATH", c.shapePattern)
        assertEquals(6, c.pathSegments.size)
        assertEquals(-0.5625f, c.pathSegments[0].pts[0], 0.001f)
        assertEquals(-1f, c.pathSegments[0].pts[1], 0.001f)
        assertTrue(r.warnings.none { it.contains("freehand path shapes are not supported yet") })
        // The hidden six: skipped, named in ONE warning for the layer.
        val hiddenWarnings = r.warnings.filter { it.contains("hidden effects are disabled") }
        assertEquals(1, hiddenWarnings.size)
        assertEquals(
            "Layer \"Kasih Nama Woi 2_720p.mp4\": hidden effects are disabled in Alight Motion: " +
                "skipped (oscillate, swing, oscillate, motionblur2, exposure, spotcolor2)",
            hiddenWarnings[0]
        )
        // The visible oscillate (mag 25, along x) baked the position keys and the visible swing
        // (a ∓3 deg) the rotation keys; the hidden oscillate (mag up to 499) and hidden swing
        // (∓10 deg) baked nothing — every key stays inside the visible effects' reach.
        val pos = c.keyframes[AnimProp.POSITION]
        assertNotNull(pos)
        assertTrue(pos!!.all { abs(it.a) <= 25.5f && abs(it.b) <= 25.5f })
        val rot = c.keyframes[AnimProp.ROTATION]
        assertNotNull(rot)
        assertTrue(rot!!.all { abs(it.a) <= 3.5f })
    }

    @Test
    fun hiddenAndVisibleEffectsOnOneLayerOnlyTheVisibleOnesLand() {
        // Modelled on the freefire stack — hidden oscillate / motionblur2 / spotcolor2 alongside
        // visible tile and exposure, on a layer that does import: only the visible effects land, the
        // hidden ones contribute nothing (no Motion Blur, no baked position keys, no "not supported"
        // warning for the hidden spotcolor2) and are reported in ONE warning for the layer.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.tile" locallyApplied="true"><property name="scale" type="float" value="1" /><property name="phase" type="float" value="0" /><property name="mirror" type="bool" value="true" /><property name="vertoffs" type="bool" value="false" /><property name="angle" type="float" value="0" /></effect>
  <effect id="com.alightcreative.effects.oscillate" hidden="true" locallyApplied="false"><property name="angle" type="float" value="90" /><property name="freq" type="float" value="1" /><property name="mag" type="float" value="100" /><property name="type" type="int" value="0" /></effect>
  <effect id="com.alightcreative.effects.motionblur2" hidden="true"><property name="tune" type="float" value="1" /><property name="usePos" type="bool" value="true" /><property name="useScale" type="bool" value="true" /><property name="useAngle" type="bool" value="true" /></effect>
  <effect id="com.alightcreative.spotcolor2" hidden="true"><property name="choke" type="float" value="2.22" /><property name="color" type="color" value="#ffff4444" /><property name="feather" type="float" value="0" /><property name="threshold" type="float" value="2" /><property name="saturation" type="float" value="0" /></effect>
  <effect id="com.alightcreative.effects.exposure" locallyApplied="true"><property name="exposure" type="float" value="0.8" /><property name="gamma" type="float" value="0.62" /><property name="offset" type="float" value="0" /></effect>"""
        ), 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val hiddenWarnings = r.warnings.filter { it.contains("hidden effects are disabled") }
        assertEquals(1, hiddenWarnings.size)
        assertEquals(
            "Layer \"Box\": hidden effects are disabled in Alight Motion: skipped (oscillate, motionblur2, spotcolor2)",
            hiddenWarnings[0]
        )
        assertTrue(r.warnings.none { it.contains("spotcolor2 is not supported") })
        assertEquals(2, c.effects.size)
        assertEquals(1, c.effects.count { it.type == ClipFxLibrary.TILE })
        val exposures = c.effects.filter { it.type == ClipFxLibrary.EXPOSURE }
        assertEquals(1, exposures.size)
        assertEquals(0.8f, exposures[0].values[0], 0.001f)
        assertTrue(c.effects.none { it.type == ClipFxLibrary.MOTION_BLUR })
        assertTrue(c.keyframes[AnimProp.POSITION].isNullOrEmpty())
    }

    @Test
    fun mirrorImportsAsTheAlightMirrorFx() {
        // Verbatim from edit-winkoko-preset.xml (the static-alpha majority: 36 of 51 instances).
        // AM's mirror is the whole-layer flip (type 0 = Horizontal), NOT the app's centre/angle
        // Mirror: it lands as its own fx with no warning.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.mirror" locallyApplied="false"><property name="type" type="int" value="0" /><property name="blendMode" type="int" value="0" /><property name="alpha" type="float" value="1.000000" /><property name="offset" type="float" value="0.000000" /></effect>"""
        ), 1080f)
        assertTrue(r.warnings.none { it.contains("mirror is not supported") })
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects.single { it.type == ClipFxLibrary.AM_MIRROR }
        assertEquals(listOf(0f, 0f, 100f, 0f, 0f, 0f, 0f), fx.values)   // no tail: a static alpha
        val arr = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(62f, arr[0], 0.001f)   // native type 62 (kFxWarp mode 11)
        assertEquals(100f, arr[3], 0.001f)   // alpha rides at values[2]
    }

    @Test
    fun keyframedMirrorAlphaRidesTheTailAndResolves() {
        // Verbatim from winkoko-preset-may24.xml: alpha pulses 0 -> 1 over keys at t = 0.032407
        // and t = 1.875 (outside the layer span — Alight clamps / interpolates across it), the
        // end key eased (0.42, 0, 0.58, 1). The track rides the values tail; AmFxEval resolves
        // values[2] per frame.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.mirror" locallyApplied="false"><property name="type" type="int" value="0" /><property name="blendMode" type="int" value="0" /><property name="alpha" type="float"><kf t="1.875000" v="1.000000" e="cubicBezier 0.42 0.0 0.58 1.0" /><kf t="0.032407" v="0.000000" /></property><property name="offset" type="float" value="0.000000" /></effect>"""
        ), 1080f)
        assertTrue(r.warnings.none { it.contains("mirror is not supported") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.AM_MIRROR }
        assertEquals(0f, fx.values[2], 0.001f)          // the value at the layer's start
        assertEquals(2f, fx.values[7], 0.001f)          // tail: two keys
        assertEquals(0.032407f, fx.values[8], 0.0001f)  // key 0: t, v, linear easing
        assertEquals(0f, fx.values[9], 0.001f)
        assertEquals(1.875f, fx.values[14], 0.0001f)    // key 1: t past the span, eased
        assertEquals(1f, fx.values[15], 0.001f)
        assertEquals(0.42f, fx.values[16], 0.001f)
        assertEquals(0.58f, fx.values[18], 0.001f)
        // Half-way through the 2000 ms layer the eased pulse is at ~13.3 %.
        val mid = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs / 2).single()
        assertEquals(13.318f, mid.values[2], 0.05f)
        // The tail survives resolution (resolving twice changes nothing).
        assertEquals(mid.values, AmFxEval.resolve(listOf(mid), clip, clip.startMs + clip.durationMs / 2).single().values)
    }

    @Test
    fun glowScanImportsWithItsKeyframedThresholdTail() {
        // Verbatim from text-motion-graphics-preset.xml (the visible instance; two more in that
        // file are hidden="true" and stay skipped as hidden). Threshold sweeps 0.08 -> 1.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.glowscan" locallyApplied="false"><property name="threshold" type="float"><kf t="0.016508" v="0.080000" /><kf t="0.983492" v="1.000000" /></property><property name="radius" type="float" value="0.100000" /><property name="power" type="float" value="2.000000" /><property name="strength" type="float" value="0.050000" /><property name="fillColor" type="color" value="#ff0088ff" /><property name="blend" type="float" value="0.000000" /><property name="fill" type="float" value="1.000000" /><property name="alpha" type="float" value="1.000000" /></effect>"""
        ), 1080f)
        assertTrue(r.warnings.none { it.contains("glowscan is not supported") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.GLOW_SCAN }
        assertEquals(
            listOf(8f, 10f, 2f, 5f, 100f, 100f, 0x0088FF.toFloat()),
            fx.values.subList(0, 7)
        )
        assertEquals(2f, fx.values[7], 0.001f)   // the threshold tail: two keys
        val start = AmFxEval.resolve(listOf(fx), clip, clip.startMs).single()
        assertEquals(8f, start.values[0], 0.001f)
        val end = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs).single()
        assertEquals(100f, end.values[0], 0.001f)
        val arr = ClipFxLibrary.toNative(listOf(end))!!
        assertEquals(63f, arr[0], 0.001f)        // native type 63
        assertEquals(100f, arr[1], 0.001f)       // the resolved threshold rides at values[0]
    }

    @Test
    fun glowScanYuraProfileDiffersInPowerAndStrength() {
        // Verbatim from yura-preset-9x16.xml: the second-value evidence that un-parked the
        // effect — power 4 (not 2), strength 0.10 (not 0.05), threshold sweeping 0 -> 1.
        val c = onlyClip(shapeWith(
            """<effect id="com.alightcreative.effects.glowscan" locallyApplied="false"><property name="threshold" type="float"><kf t="0.000000" v="0.000000" /><kf t="1.000000" v="1.000000" /></property><property name="radius" type="float" value="0.100000" /><property name="power" type="float" value="4.000000" /><property name="strength" type="float" value="0.100000" /><property name="fillColor" type="color" value="#ff0088ff" /><property name="blend" type="float" value="0.000000" /><property name="fill" type="float" value="1.000000" /><property name="alpha" type="float" value="1.000000" /></effect>"""
        ))
        val fx = c.effects.single { it.type == ClipFxLibrary.GLOW_SCAN }
        assertEquals(0f, fx.values[0], 0.001f)
        assertEquals(4f, fx.values[2], 0.001f)
        assertEquals(10f, fx.values[3], 0.001f)
        val quarter = AmFxEval.resolve(listOf(fx), c, c.startMs + c.durationMs / 4).single()
        assertEquals(25f, quarter.values[0], 0.001f)   // linear sweep: a quarter in, a quarter up
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
        val fx = g.effects.first { it.type == com.example.model.ClipFxLibrary.GAUSSIAN_BLUR_AM }
        assertEquals(0.505f, fx.values[0], 0.01f)   // AM raw strength
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
        val base = 30f * 1080f / 640f / (0.11f * 1080f) * 100f   // text units of sceneWidth / 640 px
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
    fun counterOnAShapeIsNotedAsTextOnly() {
        // Counter rewrites a text layer's characters; on a shape Alight shows nothing
        // either, so since v165 the import notes it as a text-only no-op instead of an
        // unsupported effect.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.counter" locallyApplied="true"><property name="offset" type="float" value="0" /></effect>"""
        ), 1080f)
        assertTrue(r.warnings.any { it.contains("counter only affects text layers") })
        assertFalse(r.warnings.any { it.contains("counter is not supported yet") })
        assertTrue(r.snapshot.tracks.flatMap { it.clips }[0].effects.isEmpty())
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
        // v175: lift's def IS Copy Background (mix(comp * a, tex, fill)), so fill 0 is
        // a full Copy Background at amount 100 — imported, with no warning.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.lift" locallyApplied="true"><property name="fill" type="float" value="0" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("lift") })
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects
        assertEquals(1, fx.size)
        assertEquals(ClipFxLibrary.COPY_BG, fx[0].type)
        assertEquals(100f, fx[0].values[0], 0.001f)
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

    // ---- movealongpath3 -------------------------------------------------------------------------------------------------

    @Test
    fun moveAlongPathBakesTheCorpusRingRide() {
        // Verbatim from the file "3" (Group 2): Circle 1 carries movealongpath3 and rides the outline
        // of Circle 2, the layer directly below it — an invisible ring (opacity 0) of radius
        // 100 x 1.34 = 134 whose drawing.progress keys are exactly Circle 1's progress keys x 100
        // (the author synced the dot to the ring's drawing tip). Inset 5 rides inside the outline,
        // so every baked position sits at radius 134 - 5 = 129 around the ring's centre (stored 0,0).
        val xml = """<?xml version='1.0' encoding='UTF-8' ?><scene title="3" width="1920" height="1080" bgcolor="#ff000000" totalTime="5833" fps="30">
<embedScene id="634615" label="Group 2" startTime="2166" endTime="5833" fillType="intrinsic" hidden="false" outTime="3666">
<scene width="1920" height="1080" totalTime="5833" fps="30" bgcolor="#00000000">
<shape id="634612" label="Circle 2" startTime="1666" endTime="3199" fillType="none" mediaFillMode="stretch" s=".circle"><transform><location value="960.000000,540.000000,0.000000" /><scale value="1.340000,1.340000" /><opacity value="0.000000" /></transform><fillColor value="#fffae965" /><effect id="com.alightcreative.effects.drawing.progress" locallyApplied="false"><property name="start" type="float"><kf t="0.010437" v="100.000000" /><kf t="0.988911" v="0.000000" e="cubicBezier 1.0 0.0 0.64 1.0" /></property><property name="end" type="float" value="100.000000" /></effect><path-stroke direction="centered" end-size="1.500000"><color value="#ffffffff" /><size value="29.500000" /></path-stroke><property name="size" type="vec2" value="100.000000,100.000000" /></shape>
<shape id="634613" label="Circle 1" startTime="1466" endTime="3199" fillType="color" mediaFillMode="stretch" s=".circle"><transform><location value="819.000000,564.500000,0.000000" /><scale><kf t="0.990190" v="0.365000,0.365000" e="cubicBezier 0.61 0.0 0.64 1.0" /><kf t="0.009810" v="0.000000,0.000000" /><kf t="0.124639" v="0.245000,0.245000" e="cubicBezier 0.4 0.0 0.26333332 1.0" /></scale></transform><fillColor value="#fffef6fa" /><effect id="com.alightcreative.effect.movealongpath3" locallyApplied="false"><property name="progress" type="float"><kf t="0.124639" v="1.000000" /><kf t="0.990190" v="0.000000" e="cubicBezier 1.0 0.0 0.64 1.0" /></property><property name="angle" type="float" value="0.000000" /><property name="tangent" type="bool" value="true" /><property name="inset" type="float" value="5.000000" /></effect><property name="size" type="vec2" value="100.000000,100.000000" /></shape>
</scene>
</embedScene>
</scene>"""
        val r = AlightXmlImporter.parse(xml, 1920f)
        assertFalse(r.warnings.any { it.contains("movealongpath3") })
        val dot = r.snapshot.tracks.flatMap { it.clips }.first { it.name == "Circle 1" }
        val pos = dot.keyframes[AnimProp.POSITION]!!
        assertTrue(pos.size >= 10)
        // Every key on the ring at the inset radius — the authored location (819, 564.5) is NOT added.
        assertTrue(pos.all { abs(hypot(it.a, it.b) - 129f) < 2f })
        // Fraction 0 is the TOP of the ring (the trim's convention, build 135): progress holds 1.0
        // before its first key, so the ride starts at the top point. Until build 135 the walk
        // started at the +x point, a quarter turn ahead — round 9's P4 (ours 12 o'clock where
        // Alight has 9 o'clock at the same XML progress).
        assertEquals(0f, pos.first().a, 1.5f)
        assertEquals(-129f, pos.first().b, 1.5f)
        // Mid-travel (eased progress 0.5) the dot is at the bottom of the ring...
        assertTrue(pos.any { abs(it.a) < 20f && it.b > 120f })
        // ...and at progress ~0.75 it passes 9 o'clock — the position Alight shows at ~2.6 s of the
        // file, with the ring's own drawing tip synced to the same fraction.
        assertTrue(pos.any { it.a < -120f && abs(it.b) < 25f })
        // Tangent on: rotation aligns with the path's tangent — heading +x (0 deg) at the top.
        // Progress runs 1 -> 0, so the alignment angle sweeps one full turn in reverse, unwrapped
        // (last - first = -360, not 0).
        val rot = dot.keyframes[AnimProp.ROTATION]!!
        assertEquals(0f, rot.first().a, 1.5f)
        assertEquals(-360f, rot.last().a - rot.first().a, 3f)
    }

    @Test
    fun moveAlongPathRectCornerAndNoRotationWithoutTangent() {
        // A rectangle 200 x 100 (half extents 100 x 50) at the scene centre, progress pinned at 0.25:
        // perimeter 600, a quarter is 150 along the outline from the top-left corner -> (50, -50).
        val xml = scene(
            """<shape id="1" label="Track" startTime="0" endTime="2000" fillType="color" s=".rect">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <fillColor value="#ffffffff" />
  <property name="size" type="vec2" value="100,50" /></shape>
<shape id="2" label="Dot" startTime="0" endTime="2000" fillType="color" s=".circle">
  <transform><location value="100,100,0" /><scale value="1,1" /></transform>
  <fillColor value="#ffffffff" />
  <effect id="com.alightcreative.effect.movealongpath3" locallyApplied="false"><property name="progress" type="float" value="0.25" /><property name="angle" type="float" value="0" /><property name="inset" type="float" value="0" /><property name="tangent" type="bool" value="false" /></effect>
  <property name="size" type="vec2" value="20,20" /></shape>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        assertFalse(r.warnings.any { it.contains("movealongpath3") })
        val dot = r.snapshot.tracks.flatMap { it.clips }.first { it.name == "Dot" }
        val pos = dot.keyframes[AnimProp.POSITION]!!
        assertTrue(pos.all { abs(it.a - 50f) < 0.6f && abs(it.b - (-50f)) < 0.6f })
        assertEquals(50f, dot.transformX, 0.6f)
        assertEquals(-50f, dot.transformY, 0.6f)
        // No tangent and a zero angle: no rotation is baked.
        assertEquals(null, dot.keyframes[AnimProp.ROTATION])
    }

    @Test
    fun moveAlongPathLineWithTangentAndClamping() {
        // A line from local (-100, 0) to (100, 100) at the scene centre: the open outline clamps
        // the fraction instead of wrapping, and the tangent heading is atan2(100, 200) = 26.57 deg.
        fun fixture(progress: String) = scene(
            """<shape id="1" label="Rail" startTime="0" endTime="2000" fillType="color" s=".line">
  <transform><location value="540,960,0" /></transform>
  <property name="p1" type="vec2" value="-100,0" />
  <property name="p2" type="vec2" value="100,100" /></shape>
<shape id="2" label="Dot" startTime="0" endTime="2000" fillType="color" s=".circle">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <fillColor value="#ffffffff" />
  <effect id="com.alightcreative.effect.movealongpath3" locallyApplied="false">$progress<property name="angle" type="float" value="0" /><property name="inset" type="float" value="0" /><property name="tangent" type="bool" value="true" /></effect>
  <property name="size" type="vec2" value="20,20" /></shape>"""
        )
        val ride = AlightXmlImporter.parse(
            fixture("""<property name="progress" type="float"><kf t="0" v="0" /><kf t="1" v="1" /></property>"""), 1080f
        )
        assertFalse(ride.warnings.any { it.contains("movealongpath3") })
        val dot = ride.snapshot.tracks.flatMap { it.clips }.first { it.name == "Dot" }
        val pos = dot.keyframes[AnimProp.POSITION]!!
        assertEquals(-100f, pos.first().a, 0.6f)
        assertEquals(0f, pos.first().b, 0.6f)
        assertEquals(100f, pos.last().a, 0.6f)
        assertEquals(100f, pos.last().b, 0.6f)
        val mid = pos.valueAt(1000L)
        assertEquals(0f, mid.first, 1.5f)
        assertEquals(50f, mid.second, 1.5f)
        val rot = dot.keyframes[AnimProp.ROTATION]!!
        assertTrue(rot.all { abs(it.a - 26.565f) < 0.75f })

        // Progress past the end of an open path clamps to the end point.
        val clamped = AlightXmlImporter.parse(
            fixture("""<property name="progress" type="float" value="2" />"""), 1080f
        )
        val dot2 = clamped.snapshot.tracks.flatMap { it.clips }.first { it.name == "Dot" }
        val pos2 = dot2.keyframes[AnimProp.POSITION]!!
        assertTrue(pos2.all { abs(it.a - 100f) < 0.6f && abs(it.b - 100f) < 0.6f })
    }

    @Test
    fun moveAlongPathHonoursTheProgressEasing() {
        // cubicBezier(1, 0, 0.64, 1) reaches eased progress 0.5 only at segment fraction 0.74, so at
        // 1480 ms of 2000 the dot is halfway around the ring (0, 100 — the bottom, fraction 0 being
        // the top since build 135); a linear reading of the same keys would put it near (100, 0)
        // instead.
        val xml = scene(
            """<shape id="1" label="Ring" startTime="0" endTime="2000" fillType="color" s=".circle">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <fillColor value="#ffffffff" />
  <property name="size" type="vec2" value="100,100" /></shape>
<shape id="2" label="Dot" startTime="0" endTime="2000" fillType="color" s=".circle">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <fillColor value="#ffffffff" />
  <effect id="com.alightcreative.effect.movealongpath3" locallyApplied="false"><property name="progress" type="float"><kf t="0" v="0" /><kf t="1" v="1" e="cubicBezier 1.0 0.0 0.64 1.0" /></property><property name="angle" type="float" value="0" /><property name="inset" type="float" value="0" /><property name="tangent" type="bool" value="false" /></effect>
  <property name="size" type="vec2" value="20,20" /></shape>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        assertFalse(r.warnings.any { it.contains("movealongpath3") })
        val dot = r.snapshot.tracks.flatMap { it.clips }.first { it.name == "Dot" }
        val pos = dot.keyframes[AnimProp.POSITION]!!
        assertTrue(pos.all { abs(hypot(it.a, it.b) - 100f) < 1.5f })
        val start = pos.valueAt(0L)
        assertEquals(0f, start.first, 1.5f)
        assertEquals(-100f, start.second, 1.5f)
        val mid = pos.valueAt(1480L)
        assertEquals(0f, mid.first, 3f)
        assertEquals(100f, mid.second, 3f)
    }

    @Test
    fun moveAlongPathWarnsWhenThereIsNothingToFollow() {
        val fx = """<effect id="com.alightcreative.effect.movealongpath3" locallyApplied="false"><property name="progress" type="float" value="0.5" /><property name="angle" type="float" value="0" /><property name="inset" type="float" value="0" /><property name="tangent" type="bool" value="true" /></effect>"""
        val dotShape = """<shape id="2" label="Dot" startTime="0" endTime="2000" fillType="color" s=".circle">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <fillColor value="#ffffffff" />
  $fx
  <property name="size" type="vec2" value="20,20" /></shape>"""

        // The follower is the only layer: nothing below it.
        val solo = AlightXmlImporter.parse(scene(dotShape), 1080f)
        assertTrue(solo.warnings.any { it.contains("movealongpath3") && it.contains("no imported layer below") })
        assertEquals(null, solo.snapshot.tracks.flatMap { it.clips }[0].keyframes[AnimProp.POSITION])

        // The layer below is a text: it has no vector outline.
        val overText = AlightXmlImporter.parse(
            scene(
                """<text id="1" label="Cap" startTime="0" endTime="2000" fillType="color" size="30" font="googlefonts?name=Roboto" wrapWidth="900" align="center"><transform><location value="540,960,0" /></transform><content>HI</content></text>
""" + dotShape
            ), 1080f
        )
        assertTrue(overText.warnings.any { it.contains("movealongpath3") && it.contains("no vector outline") })

        // A parented follower would follow its parent's outline in Alight — not imported.
        val parented = AlightXmlImporter.parse(
            scene(
                """<shape id="1" label="Ring" startTime="0" endTime="2000" fillType="color" s=".circle">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <fillColor value="#ffffffff" />
  <property name="size" type="vec2" value="100,100" /></shape>
""" + dotShape.replace("<shape id=\"2\"", "<shape id=\"2\" parent=\"999\"")
            ), 1080f
        )
        assertTrue(parented.warnings.any { it.contains("movealongpath3") && it.contains("parent") })
        val pDot = parented.snapshot.tracks.flatMap { it.clips }.first { it.name == "Dot" }
        assertEquals(null, pDot.keyframes[AnimProp.POSITION])
    }

    @Test
    fun lumaKeyImportsWithItsKeyframedLowThresholdTail() {
        // Verbatim from am-renderer-preset2.xml: lowThreshold sweeps 1 -> 0 by t = 0.201057.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.lumakey3" locallyApplied="false"><property name="lowThreshold" type="float"><kf t="0.000000" v="1.000000" /><kf t="0.201057" v="0.000000" e="cubicBezier 0.0 0.0 0.58 1.0" /></property><property name="highThreshold" type="float" value="1.000000" /><property name="feather" type="float" value="0.050000" /><property name="invert" type="bool" value="false" /><property name="weighted" type="bool" value="true" /></effect>"""
        ), 1080f)
        assertTrue(r.warnings.none { it.contains("lumakey3 is not supported") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.LUMA_KEY }
        assertEquals(listOf(100f, 100f, 5f, 0f, 1f, 0f, 0f), fx.values.subList(0, 7))
        assertEquals(2f, fx.values[7], 0.001f)   // the low-threshold tail: two keys
        val end = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs).single()
        assertEquals(0f, end.values[0], 0.001f)   // the sweep has opened the band by the end
        assertEquals(64f, ClipFxLibrary.toNative(listOf(fx))!![0], 0.001f)   // native type 64
    }

    @Test
    fun fourColorGradientImportsVerbatim() {
        // Verbatim from am-renderer-preset6.xml: black / white anchors, blend 0.496, alpha 0.95,
        // and NO offset4 (AM's default (0, 0) applies).
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.fourcolorgradient" locallyApplied="false"><property name="color1" type="color" value="#ff000000" /><property name="offset1" type="vec2" value="0.000000,-500.000000" /><property name="color2" type="color" value="#ffffffff" /><property name="offset2" type="vec2" value="500.000000,-500.000000" /><property name="color3" type="color" value="#ff000000" /><property name="offset3" type="vec2" value="500.000000,0.000000" /><property name="color4" type="color" value="#ffffffff" /><property name="blend" type="float" value="0.496000" /><property name="alpha" type="float" value="0.950000" /><property name="blendMode" type="int" value="0" /></effect>"""
        ), 1080f)
        assertTrue(r.warnings.none { it.contains("fourcolorgradient is not supported") })
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects.single { it.type == ClipFxLibrary.FOUR_COLOR_GRADIENT }
        assertEquals(0f, fx.values[0], 0.001f)                 // colour 1: black
        assertEquals(0xFFFFFF.toFloat(), fx.values[1], 0.001f) // colour 2: white
        assertEquals(49.6f, fx.values[4], 0.01f)               // blend x100
        assertEquals(95f, fx.values[5], 0.001f)                // alpha x100
        assertEquals(ClipFxLibrary.packPoint(0f, -500f), fx.values[7], 0.001f)
        assertEquals(ClipFxLibrary.packPoint(500f, 0f), fx.values[9], 0.001f)
        assertEquals(ClipFxLibrary.packPoint(0f, 0f), fx.values[10], 0.001f)   // offset4 defaulted
        val arr = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(16, arr.size)
        assertEquals(65f, arr[0], 0.001f)
        assertEquals(66f, arr[8], 0.001f)   // the EXT group follows
    }

    @Test
    fun turbulentDisplaceImportsWithTwoKeyframedTails() {
        // Verbatim from am-renderer-preset2.xml: direction XY, octaves 1, position (55, 0),
        // evolution 4.5; intensity (0.153 -> 0) and scale (3.32 -> 0.01, keys outside the span)
        // are both keyframed.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.turbulentdisplace" locallyApplied="false"><property name="position" type="vec2" value="55.000000,0.000000" /><property name="intensity" type="float"><kf t="0.000000" v="0.153000" /><kf t="1.000000" v="0.000000" e="cubicBezier 0.0 0.0 0.58 1.0" /></property><property name="evolution" type="float" value="4.500000" /><property name="direction" type="int" value="2" /><property name="scale" type="float"><kf t="-0.036364" v="3.320000" /><kf t="1.872727" v="0.010000" e="cubicBezier 0.0 0.76948255 0.52081007 0.9963063" /></property><property name="stretch" type="float" value="0.000000" /><property name="seed" type="float" value="0.000000" /><property name="screenSpace" type="bool" value="false" /><property name="octaves" type="float" value="1.000000" /><property name="offs" type="float" value="0.000000" /></effect>"""
        ), 1080f)
        assertTrue(r.warnings.none { it.contains("turbulentdisplace is not supported") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.TURBULENT_DISPLACE_AM }
        assertEquals(15.3f, fx.values[0], 0.01f)     // intensity at the layer start
        assertEquals(2f, fx.values[2], 0.001f)       // direction XY
        assertEquals(450f, fx.values[3], 0.001f)     // evolution x100
        assertEquals(1f, fx.values[4], 0.001f)       // octaves
        assertEquals(55f, fx.values[9], 0.001f)      // EXT: position x
        assertEquals(0f, fx.values[10], 0.001f)      // EXT: position y
        assertEquals(2f, fx.values[14], 0.001f)      // intensity tail: two keys
        assertEquals(0.153f, fx.values[16], 0.001f)
        assertEquals(2f, fx.values[27], 0.001f)      // scale tail follows it
        assertEquals(-0.036364f, fx.values[28], 0.0001f)
        assertEquals(3.32f, fx.values[29], 0.001f)
        val end = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs).single()
        assertEquals(0f, end.values[0], 0.001f)      // the displacement has died away by the end
        assertTrue(end.values[1] < fx.values[1])     // and the scale is on its way to 0.01
        val arr = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(67f, arr[0], 0.001f)
        assertEquals(68f, arr[8], 0.001f)
        assertEquals(55f, arr[11], 0.001f)           // position x rides in the EXT group (q2)
    }

    @Test
    fun stretchSegmentAndReplaceColorImportVerbatim() {
        // Verbatim from am-renderer-preset1.xml (stretch 749, everything else 0).
        val c1 = onlyClip(shapeWith(
            """<effect id="com.alightcreative.effects.stretchsegment" locallyApplied="false"><property name="angle" type="float" value="0.000000" /><property name="stretch" type="float" value="749.000000" /><property name="offset" type="float" value="0.000000" /><property name="smooth" type="float" value="0.000000" /></effect>"""
        ))
        val ss = c1.effects.single { it.type == ClipFxLibrary.STRETCH_SEGMENT }
        assertEquals(listOf(0f, 749f, 0f, 0f), ss.values.subList(0, 4))
        assertEquals(70f, ClipFxLibrary.toNative(listOf(ss))!![0], 0.001f)
        // Verbatim from preset-jj-solo-rhapdyy.xml: old #5eb7d9 -> new black, threshold 1.5,
        // feather 2, luminance locked.
        val r2 = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.replacecolor" locallyApplied="true"><property name="oldcolor" type="color" value="#ff5eb7d9" /><property name="newcolor" type="color" value="#ff000000" /><property name="threshold" type="float" value="1.500000" /><property name="feather" type="float" value="2.000000" /><property name="alpha" type="float" value="1.000000" /><property name="lockLuminance" type="bool" value="true" /></effect>"""
        ), 1080f)
        assertTrue(r2.warnings.none { it.contains("replacecolor is not supported") })
        val rc = r2.snapshot.tracks.flatMap { it.clips }[0].effects.single { it.type == ClipFxLibrary.REPLACE_COLOR }
        assertEquals(0x5EB7D9.toFloat(), rc.values[0], 0.001f)
        assertEquals(0f, rc.values[1], 0.001f)
        assertEquals(150f, rc.values[2], 0.001f)
        assertEquals(200f, rc.values[3], 0.001f)
        assertEquals(1f, rc.values[5], 0.001f)   // lock luminance
        assertEquals(74f, ClipFxLibrary.toNative(listOf(rc))!![0], 0.001f)
    }

    @Test
    fun swirlImportsBothVersions() {
        // Verbatim from am-renderer-preset1.xml: swirl4, constant strength 0.05, radius 0.8,
        // no centerPoint (the default centre).
        val c1 = onlyClip(shapeWith(
            """<effect id="com.alightcreative.effects.swirl4" locallyApplied="false"><property name="strength" type="float" value="0.050000" /><property name="radius" type="float" value="0.800000" /></effect>"""
        ))
        val s4 = c1.effects.single { it.type == ClipFxLibrary.SWIRL_AM }
        assertEquals(listOf(5f, 80f, 0f, 0f, 4f), s4.values.subList(0, 5))
        assertEquals(7, s4.values.size)   // no tail: constant strength
        // Verbatim from celz-preset-9x16.xml: swirl3 with strength keyframed 0 -> -0.111 -> 0.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.swirl3" locallyApplied="false"><property name="strength" type="float"><kf t="0.000000" v="0.000000" e="cubicBezier 0.48 0.0 1.0 0.0" /><kf t="0.441379" v="-0.111000" e="cubicBezier 0.48 0.0 1.0 0.0" /><kf t="1.000000" v="0.000000" e="cubicBezier 0.0 1.0 0.52 1.0" /></property><property name="radius" type="float" value="0.510000" /></effect>"""
        ), 1080f)
        assertTrue(r.warnings.none { it.contains("swirl3 is not supported") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val s3 = clip.effects.single { it.type == ClipFxLibrary.SWIRL_AM }
        assertEquals(3f, s3.values[4], 0.001f)   // version 3
        assertEquals(3f, s3.values[7], 0.001f)   // the strength tail: three keys
        // 883 ms in (rel 0.4415, just past the -0.111 key at 0.441379): the last key's
        // (0, 1, 0.52, 1) easing rises steeply, so the value has already come back to -0.1071.
        val mid = AmFxEval.resolve(listOf(s3), clip, clip.startMs + 883L).single()
        assertEquals(-10.71f, mid.values[0], 0.05f)
        assertEquals(69f, ClipFxLibrary.toNative(listOf(s3))!![0], 0.001f)
    }

    @Test
    fun hiddenSwirlStaysSkipped() {
        // skyprest-12.xml's keyframed swirl4 is hidden="true": the hidden rule skips it (with
        // the layer's one aggregate note), it does NOT import as a live swirl.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.swirl4" hidden="true" locallyApplied="false"><property name="strength" type="float"><kf t="0.008894" v="0.000000" /><kf t="0.509172" v="0.054000" /></property><property name="radius" type="float" value="0.800000" /></effect>"""
        ), 1080f)
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        assertTrue(clip.effects.none { it.type == ClipFxLibrary.SWIRL_AM })
        assertTrue(r.warnings.any { it.contains("hidden effects are disabled") && it.contains("swirl4") })
    }

    @Test
    fun halftoneDotsImportsVerbatim() {
        // Verbatim from shared-preset-likenya.xml (locallyApplied="true"; the corpus' nine
        // locallyApplied="false" instances import the same way — neither is hidden).
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.halftonedots" locallyApplied="true"><property name="amount" type="float" value="64.500000" /><property name="strength" type="float" value="0.182000" /><property name="angle" type="float" value="45.000000" /><property name="phase" type="float" value="0.500000" /><property name="color1" type="color" value="#ffff5681" /><property name="color2" type="color" value="#ffffffff" /></effect>"""
        ), 1080f)
        assertTrue(r.warnings.none { it.contains("halftonedots is not supported") })
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects.single { it.type == ClipFxLibrary.HALFTONE_DOTS }
        assertEquals(64.5f, fx.values[0], 0.001f)
        assertEquals(18.2f, fx.values[1], 0.001f)
        assertEquals(45f, fx.values[2], 0.001f)
        assertEquals(50f, fx.values[3], 0.001f)
        assertEquals(0xFF5681.toFloat(), fx.values[4], 0.001f)
        assertEquals(0xFFFFFF.toFloat(), fx.values[5], 0.001f)
        assertEquals(71f, ClipFxLibrary.toNative(listOf(fx))!![0], 0.001f)
    }

    @Test
    fun pixelateImportsWithItsKeyframedSizeTail() {
        // Verbatim from likk-preset-33.xml: size keyframed 24 -> 1, screenSpace on, vignette 1,
        // and a degenerate one-key angle track (0) that must not warn.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.pixelate2" locallyApplied="false"><property name="size" type="float"><kf t="0.009798" v="24.000000" /><kf t="1.000000" v="1.000000" /></property><property name="stretch" type="vec2" value="1.000000,1.000000" /><property name="angle" type="float"><kf t="-0.015973" v="0.000000" /></property><property name="vignette" type="float" value="1.000000" /><property name="screenSpace" type="bool" value="true" /><property name="threshold" type="float" value="0.000000" /><property name="saturation" type="float" value="0.000000" /></effect>"""
        ), 1080f)
        assertTrue(r.warnings.none { it.contains("pixelate2 is not supported") })
        assertTrue(r.warnings.none { it.contains("pixelate2") && it.contains("keyframed angle") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.PIXELATE_AM }
        assertEquals(24f, fx.values[0], 0.001f)     // size at the layer start
        assertEquals(100f, fx.values[4], 0.001f)    // vignette x100
        assertEquals(1f, fx.values[5], 0.001f)      // screen space
        assertEquals(2f, fx.values[14], 0.001f)     // the size tail: two keys
        val end = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs).single()
        assertEquals(1f, end.values[0], 0.001f)     // blocks shrink to 1 px by the end
        val arr = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(72f, arr[0], 0.001f)
        assertEquals(73f, arr[8], 0.001f)
    }

    @Test
    fun spinBakesIntoRotationKeys() {
        // Verbatim from am-renderer-preset1.xml: rpm 100 held to t = 0.167785, then easing
        // linearly to 0 at t = 3.396827 (past the span). On the 2000 ms shape the integral is
        // 2 s x (16.7785 + 72.495) rpm-units = 178.5 -> x6 = ~1071 degrees of added turn.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.spin" locallyApplied="false"><property name="rpm" type="float"><kf t="0.167785" v="100.000000" /><kf t="3.396827" v="0.000000" /></property></effect>"""
        ), 1080f)
        assertTrue(r.warnings.none { it.contains("spin is not supported") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        assertTrue(clip.effects.isEmpty())   // a bake, not an fx
        val keys = clip.keyframes[AnimProp.ROTATION]
        assertTrue(keys != null && keys.size >= 2)
        assertEquals(0f, keys!!.first().a, 0.01f)
        assertEquals(1071.3f, keys.last().a, 6f)
        assertTrue(keys.zipWithNext().all { (a, b) -> b.a >= a.a - 0.01f })   // rpm >= 0 on the span: never turns back
        // A zero spin adds nothing.
        val still = onlyClip(shapeWith(
            """<effect id="com.alightcreative.effects.spin" locallyApplied="false"><property name="rpm" type="float" value="0.000000" /></effect>"""
        ))
        assertTrue(still.keyframes[AnimProp.ROTATION].isNullOrEmpty())
    }

    // ---- Layer-level parity (build 115): .multifoil shapes, <drawing> layers, <camera> layers, layer blending ----

    @Test
    fun multifoilImportsAsPathShape() {
        // Verbatim from proyek-baru-15.xml: a 5-point foil, outer 100 / inner 50, sage fill.
        val r = AlightXmlImporter.parse(scene("""<shape id="2999482906" label="Multifoil 1" startTime="0" endTime="2032" fillType="color" mediaFillMode="fill" parent="2999482908" s=".multifoil">
    <transform>
      <location>
        <kf t="0.336122" v="5.500000,-89.000000,0.000000" />
        <kf t="0.483760" v="-38.500000,-121.000000,0.000000" />
      </location>
    </transform>
    <fillColor value="#ff9d9e60" />
    <property name="pointCount" type="float" value="5.000000" />
    <property name="outerRadius" type="float" value="100.000000" />
    <property name="innerRadius" type="float" value="50.000000" />
    <property name="offsetAngle" type="float" value="0.000000" />
  </shape>""", 720, 900), 720f)
        assertTrue(r.warnings.none { it.contains("multifoil") })
        val c = r.snapshot.tracks.flatMap { it.clips }.single()
        assertEquals("PATH", c.shapePattern)
        assertEquals(0xFF9D9E60L, c.textColor)
        // MOVE + two cubics per point x 5 + CLOSE (AM's smooth-tip / sharp-valley outline).
        assertEquals(12, c.pathSegments.size)
        assertEquals(com.example.model.PATH_OP_MOVE, c.pathSegments.first().op)
        assertEquals(10, c.pathSegments.count { it.op == com.example.model.PATH_OP_CUBIC })
        assertEquals(com.example.model.PATH_OP_CLOSE, c.pathSegments.last().op)
        // The outline's box comes from AM's bezier outline itself: the five tips span 190.2 px in
        // x (none points straight down), and the rounded petal curves bulge to ~192.4 px across;
        // the shape box at 720 wide is 260 px.
        assertEquals(0.740f, c.shapeScaleX, 0.01f)
    }

    @Test
    fun multifoilKeyframedPropertyKeepsFirstValue() {
        val r = AlightXmlImporter.parse(scene("""<shape id="9" label="Foil" startTime="0" endTime="2000" fillType="color" s=".multifoil">
    <transform><location value="540.000000,960.000000,0.000000" /></transform>
    <fillColor value="#ffff0000" />
    <property name="pointCount" type="float"><kf t="0.000000" v="5.000000" /><kf t="1.000000" v="8.000000" /></property>
    <property name="outerRadius" type="float" value="100.000000" />
    <property name="innerRadius" type="float" value="50.000000" />
  </shape>"""), 1080f)
        assertTrue(r.warnings.any { it.contains("pointCount") && it.contains("first value is used") })
        val c = r.snapshot.tracks.flatMap { it.clips }.single()
        assertEquals(12, c.pathSegments.size)   // still the 5-point outline
    }

    // ---- Parameterised shape kinds (build 122): .triangle / .penta / .poly / .star ----
    // No corpus file uses any of these kinds; every expectation below is hand-computed from
    // AM's own shape definitions (hunt-staging/shape-defs/) — the ports they pin.

    @Test
    fun triangleImportsAsPathShape() {
        // The def's default points, serialised as a real export writes them. The outline is the
        // points verbatim in order (p1 first): bbox +-100 on both axes, so the stored outline is
        // the raw points / 100 about the centre.
        val r = AlightXmlImporter.parse(scene("""<shape id="11" label="Triangle 1" startTime="0" endTime="2000" fillType="color" mediaFillMode="stretch" s=".triangle">
    <transform><location value="960.000000,540.000000,0.000000" /></transform>
    <fillColor value="#ff336699" />
    <property name="p1" type="vec2" value="-100.000000,100.000000" />
    <property name="p2" type="vec2" value="0.000000,-100.000000" />
    <property name="p3" type="vec2" value="100.000000,100.000000" />
    <property name="closed" type="bool" value="true" />
  </shape>""", 1920, 1080), 1920f)
        assertTrue(r.warnings.none { it.contains("is not supported yet") })
        val c = r.snapshot.tracks.flatMap { it.clips }.single()
        assertEquals("PATH", c.shapePattern)
        assertEquals(0xFF336699L, c.textColor)
        assertEquals(4, c.pathSegments.size)
        assertEquals(com.example.model.PATH_OP_MOVE, c.pathSegments[0].op)
        assertEquals(-1f, c.pathSegments[0].pts[0], 0.001f)
        assertEquals(1f, c.pathSegments[0].pts[1], 0.001f)      // p1 verbatim: bottom-left in AM's frame
        assertEquals(0f, c.pathSegments[1].pts[0], 0.001f)
        assertEquals(-1f, c.pathSegments[1].pts[1], 0.001f)     // p2: the apex
        assertEquals(1f, c.pathSegments[2].pts[0], 0.001f)
        assertEquals(1f, c.pathSegments[2].pts[1], 0.001f)
        assertEquals(com.example.model.PATH_OP_CLOSE, c.pathSegments[3].op)
        // The half-extent rule through the path tail: rendered width = 2 x half (100) x scale (1)
        // against the 1920-wide shape box (1920 x 130 / 360 = 693.3 px).
        assertEquals(200f / 693.333f, c.shapeScaleX, 0.001f)
        assertEquals(200f / 693.333f, c.shapeScaleY, 0.001f)
    }

    @Test
    fun triangleOpenAndKeyframedPointKeepsFirstValue() {
        val r = AlightXmlImporter.parse(scene("""<shape id="12" label="Tri" startTime="0" endTime="2000" fillType="color" s=".triangle">
    <transform><location value="960.000000,540.000000,0.000000" /></transform>
    <fillColor value="#ffff0000" />
    <property name="p1" type="vec2" value="-100.000000,100.000000" />
    <property name="p2" type="vec2"><kf t="0.000000" v="0.000000,-100.000000" /><kf t="1.000000" v="50.000000,-50.000000" /></property>
    <property name="p3" type="vec2" value="100.000000,100.000000" />
    <property name="closed" type="bool" value="false" />
  </shape>""", 1920, 1080), 1920f)
        assertTrue(r.warnings.any { it.contains("\"p2\"") && it.contains("first value is used") })
        val c = r.snapshot.tracks.flatMap { it.clips }.single()
        // closed = false: MOVE + 2 LINE and no CLOSE; the apex is the first keyframe's point.
        assertEquals(3, c.pathSegments.size)
        assertEquals(com.example.model.PATH_OP_LINE, c.pathSegments[2].op)
        assertEquals(0f, c.pathSegments[1].pts[0], 0.001f)
        assertEquals(-1f, c.pathSegments[1].pts[1], 0.001f)
    }

    @Test
    fun pentaDefaultsImportVerbatim() {
        // No properties at all: the def's five defaults — NOT a regular pentagon (p3 is the
        // centre point (0,0)); they are ported as written.
        val r = AlightXmlImporter.parse(scene("""<shape id="13" label="Penta 1" startTime="0" endTime="2000" fillType="color" s=".penta">
    <transform><location value="540.000000,960.000000,0.000000" /></transform>
    <fillColor value="#ff00ff00" />
  </shape>"""), 1080f)
        assertTrue(r.warnings.none { it.contains("is not supported yet") })
        val c = r.snapshot.tracks.flatMap { it.clips }.single()
        assertEquals("PATH", c.shapePattern)
        assertEquals(6, c.pathSegments.size)
        val want = listOf(-1f to -1f, 0f to -1f, 0f to 0f, 1f to 1f, -1f to 1f)
        for (i in want.indices) {
            assertEquals(want[i].first, c.pathSegments[i].pts[0], 0.001f)
            assertEquals(want[i].second, c.pathSegments[i].pts[1], 0.001f)
        }
        assertEquals(com.example.model.PATH_OP_CLOSE, c.pathSegments[5].op)
    }

    @Test
    fun polyHexagonStartsAtTopAndRunsCounterclockwise() {
        // AM's def: vertex i at angle (60deg x i + 90deg), drawn (r cos a, -r sin a) — vertex 0
        // is the TOP (0,-100) and vertex 1 steps to the upper LEFT (-86.6,-50): counterclockwise
        // in AM's frame. Bbox half is the radius (100) on y, so stored = raw / 100.
        val r = AlightXmlImporter.parse(scene("""<shape id="14" label="Polygon 1" startTime="0" endTime="2000" fillType="color" s=".poly">
    <transform><location value="960.000000,540.000000,0.000000" /></transform>
    <fillColor value="#ff0000ff" />
    <property name="sideCount" type="float" value="6.000000" />
    <property name="radius" type="float" value="100.000000" />
    <property name="offsetAngle" type="float" value="0.000000" />
  </shape>""", 1920, 1080), 1920f)
        assertTrue(r.warnings.none { it.contains("is not supported yet") })
        val c = r.snapshot.tracks.flatMap { it.clips }.single()
        assertEquals("PATH", c.shapePattern)
        assertEquals(7, c.pathSegments.size)
        val want = listOf(
            0f to -1f, -0.86603f to -0.5f, -0.86603f to 0.5f,
            0f to 1f, 0.86603f to 0.5f, 0.86603f to -0.5f
        )
        for (i in want.indices) {
            assertEquals(want[i].first, c.pathSegments[i].pts[0], 0.001f)
            assertEquals(want[i].second, c.pathSegments[i].pts[1], 0.001f)
        }
        assertEquals(com.example.model.PATH_OP_CLOSE, c.pathSegments[6].op)
        assertEquals(200f / 693.333f, c.shapeScaleX, 0.001f)
    }

    @Test
    fun polyOffsetAngleRotatesTheStartVertex() {
        // 4 sides, offset 90deg: the first vertex moves from the top to (-100, 0) — the offset
        // ADDS to the def's angle and the y is negated, exactly as the script computes it.
        val r = AlightXmlImporter.parse(scene("""<shape id="15" label="Poly 4" startTime="0" endTime="2000" fillType="color" s=".poly">
    <transform><location value="960.000000,540.000000,0.000000" /></transform>
    <fillColor value="#ff0000ff" />
    <property name="sideCount" type="float" value="4.000000" />
    <property name="radius" type="float" value="100.000000" />
    <property name="offsetAngle" type="float" value="90.000000" />
  </shape>""", 1920, 1080), 1920f)
        val c = r.snapshot.tracks.flatMap { it.clips }.single()
        assertEquals(5, c.pathSegments.size)
        val want = listOf(-1f to 0f, 0f to 1f, 1f to 0f, 0f to -1f)
        for (i in want.indices) {
            assertEquals(want[i].first, c.pathSegments[i].pts[0], 0.001f)
            assertEquals(want[i].second, c.pathSegments[i].pts[1], 0.001f)
        }
        assertEquals(com.example.model.PATH_OP_CLOSE, c.pathSegments[4].op)
    }

    @Test
    fun starDefaultsMatchAmDefGeometry() {
        // 5 points, outer 100 / inner 50, offset 0 — hand-computed from the def's script:
        // outer vertices at 90/162/234/306/18 degrees, inners half a step later at radius 50.
        // The bbox is x +-95.106, y -100..80.902, so the centre is (0,-9.549) and the uniform
        // half is 95.106; the stored points below are (raw - centre) / 95.106.
        val r = AlightXmlImporter.parse(scene("""<shape id="16" label="Star 1" startTime="0" endTime="2000" fillType="color" s=".star">
    <transform><location value="960.000000,540.000000,0.000000" /></transform>
    <fillColor value="#ffffcc00" />
    <property name="pointCount" type="float" value="5.000000" />
    <property name="outerRadius" type="float" value="100.000000" />
    <property name="innerRadius" type="float" value="50.000000" />
    <property name="offsetAngle" type="float" value="0.000000" />
  </shape>""", 1920, 1080), 1920f)
        assertTrue(r.warnings.none { it.contains("is not supported yet") })
        val c = r.snapshot.tracks.flatMap { it.clips }.single()
        assertEquals("PATH", c.shapePattern)
        assertEquals(0xFFFFCC00L, c.textColor)
        assertEquals(11, c.pathSegments.size)   // MOVE + 9 LINE + CLOSE
        assertEquals(com.example.model.PATH_OP_MOVE, c.pathSegments[0].op)
        assertEquals(0f, c.pathSegments[0].pts[0], 0.001f)
        assertEquals(-0.95106f, c.pathSegments[0].pts[1], 0.001f)   // the top outer vertex
        assertEquals(-0.30902f, c.pathSegments[1].pts[0], 0.001f)   // first inner vertex
        assertEquals(-0.32492f, c.pathSegments[1].pts[1], 0.001f)
        assertEquals(-1f, c.pathSegments[2].pts[0], 0.001f)         // leftmost outer vertex
        assertEquals(-0.22451f, c.pathSegments[2].pts[1], 0.001f)
        assertEquals(0f, c.pathSegments[5].pts[0], 0.001f)          // the bottom inner vertex
        assertEquals(0.62610f, c.pathSegments[5].pts[1], 0.001f)
        assertEquals(0.30902f, c.pathSegments[9].pts[0], 0.001f)    // last inner vertex
        assertEquals(-0.32492f, c.pathSegments[9].pts[1], 0.001f)
        assertEquals(com.example.model.PATH_OP_CLOSE, c.pathSegments[10].op)
        // Rendered width = 2 x half (95.106) against the 693.3 px shape box.
        assertEquals(190.211f / 693.333f, c.shapeScaleX, 0.001f)
    }

    @Test
    fun starKeyframedInnerRadiusKeepsFirstValue() {
        val r = AlightXmlImporter.parse(scene("""<shape id="17" label="Star K" startTime="0" endTime="2000" fillType="color" s=".star">
    <transform><location value="960.000000,540.000000,0.000000" /></transform>
    <fillColor value="#ffffcc00" />
    <property name="pointCount" type="float" value="5.000000" />
    <property name="outerRadius" type="float" value="100.000000" />
    <property name="innerRadius" type="float"><kf t="0.000000" v="50.000000" /><kf t="1.000000" v="80.000000" /></property>
  </shape>""", 1920, 1080), 1920f)
        assertTrue(r.warnings.any { it.contains("\"innerRadius\"") && it.contains("first value is used") })
        val c = r.snapshot.tracks.flatMap { it.clips }.single()
        assertEquals(11, c.pathSegments.size)   // still the radius-50 outline
        assertEquals(-0.30902f, c.pathSegments[1].pts[0], 0.001f)
    }

    @Test
    fun polyZeroRadiusIsSkipped() {
        val r = AlightXmlImporter.parse(scene("""<shape id="18" label="Poly 0" startTime="0" endTime="2000" fillType="color" s=".poly">
    <transform><location value="960.000000,540.000000,0.000000" /></transform>
    <fillColor value="#ff0000ff" />
    <property name="radius" type="float" value="0.000000" />
  </shape>""", 1920, 1080), 1920f)
        assertTrue(r.warnings.any { it.contains("radius is 0") && it.contains("skipped") })
        assertTrue(r.snapshot.tracks.flatMap { it.clips }.isEmpty())
    }

    // ---- .pie wedges (build 123) ----
    // Every expectation is computed from AM's own shape definition (hunt-staging/shape-defs/
    // def_pie.xml) — the wrap rule and the Riskus arc — not from the app's output.

    @Test
    fun pieOutlineDefaultsFollowTheWrapRule() {
        // start 45 / end 90: the wedge is the disc MINUS the 45..90 slice — a 315 deg sweep
        // (four Riskus cubics) starting at the LARGER angle's point (90 deg = the top).
        val segs = PieOutline.segments(45.0, 90.0, 100.0)
        assertEquals(7, segs.size)   // MOVE + 4 CUBIC + LINE + CLOSE
        assertEquals(com.example.model.PATH_OP_MOVE, segs[0].op)
        assertEquals(0f, segs[0].pts[0], 0.001f)
        assertEquals(-100f, segs[0].pts[1], 0.001f)                       // the top point
        assertEquals(0f, segs[2].pts[4], 0.001f)
        assertEquals(100f, segs[2].pts[5], 0.001f)                        // second cubic ends at the bottom
        assertEquals(70.7107f, segs[4].pts[4], 0.001f)
        assertEquals(-70.7107f, segs[4].pts[5], 0.001f)                   // last arc vertex: the 45 deg point
        assertEquals(com.example.model.PATH_OP_LINE, segs[5].op)
        assertEquals(0f, segs[5].pts[0], 0.001f)
        assertEquals(0f, segs[5].pts[1], 0.001f)                          // the radii close at the centre
        assertEquals(com.example.model.PATH_OP_CLOSE, segs[6].op)
        assertEquals(315.0, PieOutline.sweepDegrees(45.0, 90.0), 1e-9)
        // The flattened outline spans the full disc on both axes (Riskus error is ~0.03 px here).
        val flat = flattenPathSegments(segs)
        assertEquals(-100f, flat.minOf { it.first }, 0.6f)
        assertEquals(100f, flat.maxOf { it.first }, 0.6f)
        assertEquals(-100f, flat.minOf { it.second }, 0.6f)
        assertEquals(100f, flat.maxOf { it.second }, 0.6f)
    }

    @Test
    fun pieOutlineWrapRuleEdges() {
        // Angles a full turn apart (the likenya loader's rest state) and equal angles both
        // close the wedge completely: no arc at all.
        assertTrue(PieOutline.segments(810.0, 450.0, 100.0).isEmpty())
        assertTrue(PieOutline.segments(45.0, 45.0, 100.0).isEmpty())
        assertTrue(PieOutline.segments(0.0, 360.0, 100.0).isEmpty())
        assertEquals(0.0, PieOutline.sweepDegrees(810.0, 450.0), 1e-9)
        assertEquals(80.0, PieOutline.sweepDegrees(770.0, 490.0), 1e-9)
        // Past a full turn the sweep grows again from 0: 0 -> 450 draws a 90 deg wedge.
        val mt = PieOutline.segments(0.0, 450.0, 100.0)
        assertEquals(4, mt.size)
        assertEquals(0f, mt[0].pts[0], 0.001f)
        assertEquals(-100f, mt[0].pts[1], 0.001f)
        assertEquals(100f, mt[1].pts[4], 0.001f)
        assertEquals(0f, mt[1].pts[5], 0.001f)
        // Negative angles are ordinary: -30 -> 60 sweeps 270 deg from the 60 deg point.
        val neg = PieOutline.segments(-30.0, 60.0, 100.0)
        assertEquals(6, neg.size)
        assertEquals(50f, neg[0].pts[0], 0.001f)
        assertEquals(-86.60254f, neg[0].pts[1], 0.001f)
    }

    @Test
    fun pieOutlineCounterRotatingAtRadius50() {
        // start 200 / end 100 (the angles run against each other): sweep = 360 - 100 = 260 deg,
        // three cubics, starting at the 200 deg point; the first cubic's end lies ON the arc.
        val segs = PieOutline.segments(200.0, 100.0, 50.0)
        assertEquals(6, segs.size)
        assertEquals(-46.984631f, segs[0].pts[0], 0.001f)
        assertEquals(17.101007f, segs[0].pts[1], 0.001f)
        assertEquals(17.101007f, segs[1].pts[4], 0.001f)
        assertEquals(46.984631f, segs[1].pts[5], 0.001f)
        assertEquals(50f, hypot(segs[1].pts[4], segs[1].pts[5]), 0.001f)
    }

    @Test
    fun pieStaticAnglesImport() {
        // A static wedge (no keyframes anywhere): start 30 / end 120 at radius 80 — sweep 270,
        // outline normalised by the radius, first vertex at the 120 deg point (-0.5, -0.866).
        val r = AlightXmlImporter.parse(scene("""<shape id="21" label="Pie S" startTime="0" endTime="2000" fillType="color" mediaFillMode="stretch" s=".pie">
    <transform><location value="540.000000,960.000000,0.000000" /></transform>
    <fillColor value="#ff123456" />
    <property name="startAngle" type="float" value="30.000000" />
    <property name="endAngle" type="float" value="120.000000" />
    <property name="radius" type="float" value="80.000000" />
  </shape>"""), 1080f)
        assertTrue(r.warnings.none { it.contains(".pie") || it.contains("wedge") })
        val c = r.snapshot.tracks.flatMap { it.clips }.single()
        assertEquals("PATH", c.shapePattern)
        assertTrue(c.hasPie)
        assertEquals(80f, c.pieRadius, 0.001f)
        assertEquals(1, c.pieStartKeys.size)
        assertEquals(30f, c.pieStartKeys[0].a, 0.001f)
        assertEquals(120f, c.pieEndKeys[0].a, 0.001f)
        assertEquals(6, c.pathSegments.size)   // MOVE + 3 CUBIC + LINE + CLOSE
        assertEquals(-0.5f, c.pathSegments[0].pts[0], 0.0001f)
        assertEquals(-0.866025f, c.pathSegments[0].pts[1], 0.0001f)
        // Rendered width = 2 x radius against the 390 px shape box of a 1080-wide scene.
        assertEquals(160f / 390f, c.shapeScaleX, 0.001f)
        // A static pie evaluates to the same outline at every time (the stored outline is built
        // at the real radius and divided by it, the evaluated one at radius 1 — equal to the
        // last float digit, not bit-exact).
        val later = c.at(c.startMs + 1500L)
        assertEquals(c.pathSegments.size, later.pathSegments.size)
        for (i in c.pathSegments.indices) {
            assertEquals(c.pathSegments[i].op, later.pathSegments[i].op)
            for (j in c.pathSegments[i].pts.indices) {
                assertEquals(c.pathSegments[i].pts[j], later.pathSegments[i].pts[j], 0.000001f)
            }
        }
    }

    @Test
    fun pieVerbatimLikenyaLoaderEvaluatesPerFrame() {
        // Verbatim from shared-preset-likenya.xml: "Pie 1", one of the loader's 128 wedges.
        // Both angles are keyframed (810->770->810 against 450->490->450), so the drawn sweep
        // pulses 0 -> 80 -> 0 deg while the layer's own rotation spins it a full turn.
        val r = AlightXmlImporter.parse(scene("""<shape id="713679" label="Pie 1" startTime="0" endTime="849" fillType="color" mediaFillMode="stretch" s=".pie">
    <transform>
      <location value="540.000000,960.000000,0.000000" />
      <rotation>
        <kf t="0.009423" v="0.000000" />
        <kf t="0.990577" v="360.000000" e="cubicBezier 0.0 0.0 0.0 1.0" />
        <kf t="0.539458" v="180.000000" e="cubicBezier 1.0 0.0 1.0 1.0" />
      </rotation>
    </transform>
    <fillColor value="#ffa887a9" />
    <property name="startAngle" type="float">
      <kf t="0.009423" v="810.000000" />
      <kf t="0.539458" v="770.000000" e="cubicBezier 1.0 0.0 1.0 1.0" />
      <kf t="0.990577" v="810.000000" e="cubicBezier 0.0 0.0 0.0 1.0" />
    </property>
    <property name="endAngle" type="float">
      <kf t="0.009423" v="450.000000" />
      <kf t="0.539458" v="490.000000" e="cubicBezier 1.0 0.0 1.0 1.0" />
      <kf t="0.990577" v="450.000000" e="cubicBezier 0.0 0.0 0.0 1.0" />
    </property>
    <property name="radius" type="float" value="100.000000" />
  </shape>"""), 1080f)
        assertTrue(r.warnings.none { it.contains(".pie") || it.contains("wedge") })
        val c = r.snapshot.tracks.flatMap { it.clips }.single()
        assertEquals("PATH", c.shapePattern)
        assertEquals(0xFFA887A9L, c.textColor)
        assertTrue(c.hasPie)
        assertEquals(100f, c.pieRadius, 0.001f)
        // The angle tracks ride as keyframes at the Alight keys' own times (849 ms layer).
        assertEquals(listOf(8L, 458L, 841L), c.pieStartKeys.map { it.timeMs })
        assertEquals(listOf(810f, 770f, 810f), c.pieStartKeys.map { it.a })
        assertEquals(listOf(450f, 490f, 450f), c.pieEndKeys.map { it.a })
        // The stored outline is the most open instant (the middle key, sweep 80 deg).
        assertEquals(4, c.pathSegments.size)
        assertEquals(0.642788f, c.pathSegments[0].pts[0], 0.001f)
        assertEquals(-0.766044f, c.pathSegments[0].pts[1], 0.001f)
        // At the first key the angles are a full turn apart: the wedge is closed, nothing drawn.
        assertTrue(c.at(c.startMs + 8L).pathSegments.isEmpty())
        assertTrue(c.at(c.startMs + 841L).pathSegments.isEmpty())
        // At the middle key the outline is the 80 deg wedge across the top (one Riskus cubic,
        // normalised by the radius), and the layer's own spin has reached 180 deg.
        val mid = c.at(c.startMs + 458L)
        assertEquals(180f, mid.rotationDeg, 0.01f)
        assertEquals(4, mid.pathSegments.size)
        assertEquals(0.642788f, mid.pathSegments[0].pts[0], 0.001f)
        assertEquals(-0.766044f, mid.pathSegments[0].pts[1], 0.001f)
        assertEquals(0.271031f, mid.pathSegments[1].pts[0], 0.001f)
        assertEquals(-1.077985f, mid.pathSegments[1].pts[1], 0.001f)
        assertEquals(-0.642788f, mid.pathSegments[1].pts[4], 0.001f)
        assertEquals(-0.766044f, mid.pathSegments[1].pts[5], 0.001f)
        assertEquals(com.example.model.PATH_OP_CLOSE, mid.pathSegments[3].op)
        // Mid-segment (rel 402, eased by cubicBezier(1,0,1,1) to ~0.5011): the evaluated angles
        // are 789.96 / 470.04, a 40 deg wedge whose first vertex sits at the 789.96 deg point.
        val eased = c.at(c.startMs + 402L)
        assertEquals(4, eased.pathSegments.size)
        assertEquals(0.342750f, eased.pathSegments[0].pts[0], 0.003f)
        assertEquals(-0.939427f, eased.pathSegments[0].pts[1], 0.003f)
    }

    @Test
    fun pieKeyframedRadiusRidesTheScaleKeys() {
        // The radius is keyframed 100 -> 200 while the angles stay static: the outline (which is
        // normalised by the radius) does not change, the composed SCALE keyframes double the size.
        val r = AlightXmlImporter.parse(scene("""<shape id="22" label="Pie R" startTime="0" endTime="2000" fillType="color" s=".pie">
    <transform><location value="540.000000,960.000000,0.000000" /></transform>
    <fillColor value="#ff00ff00" />
    <property name="startAngle" type="float" value="0.000000" />
    <property name="endAngle" type="float" value="90.000000" />
    <property name="radius" type="float">
      <kf t="0.000000" v="100.000000" />
      <kf t="1.000000" v="200.000000" />
    </property>
  </shape>"""), 1080f)
        assertTrue(r.warnings.none { it.contains("first value") || it.contains("wedge") })
        val c = r.snapshot.tracks.flatMap { it.clips }.single()
        assertTrue(c.hasPie)
        assertEquals(100f, c.pieRadius, 0.001f)   // the first value is the normalisation basis
        assertTrue(c.keyframes[AnimProp.SCALE]?.isNotEmpty() == true)
        val t0 = c.at(c.startMs)
        val t1 = c.at(c.startMs + c.durationMs)
        assertEquals(200f / 390f, t0.shapeScaleX, 0.001f)
        assertEquals(400f / 390f, t1.shapeScaleX, 0.001f)
        assertEquals(t0.pathSegments, t1.pathSegments)   // static angles: same normalised outline
        assertEquals(0f, t0.pathSegments[0].pts[0], 0.001f)   // the 90 deg start point: the top
        assertEquals(-1f, t0.pathSegments[0].pts[1], 0.001f)
    }

    @Test
    fun pieAlwaysClosedIsSkipped() {
        // Equal angles at every instant: the wrap rule never opens the wedge (Alight draws
        // nothing either), so the layer is skipped with that said.
        val r = AlightXmlImporter.parse(scene("""<shape id="23" label="Pie 0" startTime="0" endTime="2000" fillType="color" s=".pie">
    <transform><location value="540.000000,960.000000,0.000000" /></transform>
    <fillColor value="#ff0000ff" />
    <property name="startAngle" type="float" value="200.000000" />
    <property name="endAngle" type="float" value="200.000000" />
    <property name="radius" type="float" value="100.000000" />
  </shape>"""), 1080f)
        assertTrue(r.warnings.any { it.contains("wedge closed") && it.contains("skipped") })
        assertTrue(r.snapshot.tracks.flatMap { it.clips }.isEmpty())
    }

    // ---- .callout.roundrect speech-bubble shapes ----
    // Every expectation is hand-computed from AM's own shape definition (hunt-staging/
    // shape-defs/def_calloutrr.xml): the roundrect body (corner handles at r x 0.552228474
    // from their anchors, so the handle coordinate is 100 - 25 x (1 - 0.552228474) = 88.80571)
    // plus the tail's three spliced plain points.

    @Test
    fun calloutOutlineDefaultsPutTheTailOnTheBottom() {
        // The def defaults (also the corpus instance's values): size 100x100, tail (-50,150),
        // cornerRadius 25, tailWidth 40. |tail.y| > |tail.x| and tail.y > 0: BOTTOM side.
        // tailPos = clamp(-50 x 3.5, -75 + 20, 75 - 20) = -55, so the base spans -75..-35 on
        // y=100; its far point lands exactly on the bottom-left corner anchor (-75,100), which
        // the def's list then holds twice in a row — kept verbatim.
        val segs = CalloutOutline.segments(100.0, 100.0, -50.0, 150.0, 25.0, 40.0)
        assertEquals(13, segs.size)   // MOVE + 10 steps + closing corner cubic + CLOSE
        assertEquals(com.example.model.PATH_OP_MOVE, segs[0].op)
        assertEquals(-75f, segs[0].pts[0], 0.001f)
        assertEquals(-100f, segs[0].pts[1], 0.001f)                 // the top edge's left corner end
        assertEquals(com.example.model.PATH_OP_CUBIC, segs[2].op)
        assertEquals(88.80571f, segs[2].pts[0], 0.001f)             // top-right corner arc intact
        assertEquals(100f, segs[2].pts[4], 0.001f)
        assertEquals(-75f, segs[2].pts[5], 0.001f)
        assertEquals(com.example.model.PATH_OP_LINE, segs[5].op)
        assertEquals(-35f, segs[5].pts[0], 0.001f)                  // the tail's near base point
        assertEquals(100f, segs[5].pts[1], 0.001f)
        assertEquals(-50f, segs[6].pts[0], 0.001f)                  // the tip: the RAW tail point
        assertEquals(150f, segs[6].pts[1], 0.001f)
        assertEquals(-75f, segs[7].pts[0], 0.001f)                  // the far base point
        assertEquals(100f, segs[7].pts[1], 0.001f)
        assertEquals(-75f, segs[8].pts[0], 0.001f)                  // the duplicated anchor, verbatim
        assertEquals(100f, segs[8].pts[1], 0.001f)
        assertEquals(com.example.model.PATH_OP_CUBIC, segs[11].op)
        assertEquals(-75f, segs[11].pts[4], 0.001f)                 // the closing corner lands on the start
        assertEquals(-100f, segs[11].pts[5], 0.001f)
        assertEquals(com.example.model.PATH_OP_CLOSE, segs[12].op)
    }

    @Test
    fun calloutOutlineTailOnEachOtherSide() {
        // LEFT (tail.x < 0, |tail.y| <= |tail.x|): tail.y = -20 attracts to -70 and clamps to
        // -55 (the corner arcs bound the base), so the base spans -75..-35 on x=-100 and its
        // far point again coincides with the following anchor.
        val left = CalloutOutline.segments(100.0, 100.0, -150.0, -20.0, 25.0, 40.0)
        assertEquals(13, left.size)
        assertEquals(-100f, left[7].pts[0], 0.001f)
        assertEquals(-35f, left[7].pts[1], 0.001f)
        assertEquals(com.example.model.PATH_OP_LINE, left[8].op)
        assertEquals(-150f, left[8].pts[0], 0.001f)
        assertEquals(-20f, left[8].pts[1], 0.001f)
        assertEquals(-100f, left[9].pts[0], 0.001f)
        assertEquals(-75f, left[9].pts[1], 0.001f)
        // RIGHT (tail.x > 0): tail.y = 20 attracts to 70, clamps to 55: base 35..75 on x=100,
        // spliced BEFORE the right side's lower anchor, so the steps into it are lines.
        val right = CalloutOutline.segments(100.0, 100.0, 150.0, 20.0, 25.0, 40.0)
        assertEquals(13, right.size)
        assertEquals(100f, right[3].pts[0], 0.001f)
        assertEquals(35f, right[3].pts[1], 0.001f)
        assertEquals(150f, right[4].pts[0], 0.001f)
        assertEquals(20f, right[4].pts[1], 0.001f)
        assertEquals(100f, right[5].pts[0], 0.001f)
        assertEquals(75f, right[5].pts[1], 0.001f)
        // TOP (tail.y < 0, |tail.y| > |tail.x|): tail.x = 20 attracts to 70, clamps to 55:
        // base 35..75 on y=-100, spliced right after the first anchor.
        val top = CalloutOutline.segments(100.0, 100.0, 20.0, -150.0, 25.0, 40.0)
        assertEquals(13, top.size)
        assertEquals(35f, top[1].pts[0], 0.001f)
        assertEquals(-100f, top[1].pts[1], 0.001f)
        assertEquals(20f, top[2].pts[0], 0.001f)
        assertEquals(-150f, top[2].pts[1], 0.001f)
        assertEquals(75f, top[3].pts[0], 0.001f)
        assertEquals(-100f, top[3].pts[1], 0.001f)
    }

    @Test
    fun calloutDefaultsImportAsPathShape() {
        // The def defaults serialised as a real export writes them. The outline's box is
        // x +-100, y -100..150 (the tip overhangs), so the normalisation centre is (0,25) and
        // the uniform half is 125: stored = (raw - centre) / 125.
        val r = AlightXmlImporter.parse(scene("""<shape id="31" label="Callout 1" startTime="0" endTime="2000" fillType="color" mediaFillMode="fill" s=".callout.roundrect">
    <transform><location value="960.000000,540.000000,0.000000" /></transform>
    <fillColor value="#ff6bd3cd" />
    <property name="size" type="vec2" value="100.000000,100.000000" />
    <property name="tail" type="vec2" value="-50.000000,150.000000" />
    <property name="cornerRadius" type="float" value="25.000000" />
    <property name="tailWidth" type="float" value="40.000000" />
  </shape>""", 1920, 1080), 1920f)
        assertTrue(r.warnings.none { it.contains("is not supported yet") })
        val c = r.snapshot.tracks.flatMap { it.clips }.single()
        assertEquals("PATH", c.shapePattern)
        assertEquals(0xFF6BD3CDL, c.textColor)
        assertEquals(13, c.pathSegments.size)
        assertEquals(-0.6f, c.pathSegments[0].pts[0], 0.001f)
        assertEquals(-1f, c.pathSegments[0].pts[1], 0.001f)
        assertEquals(-0.28f, c.pathSegments[5].pts[0], 0.001f)      // the tail's near base point
        assertEquals(0.6f, c.pathSegments[5].pts[1], 0.001f)
        assertEquals(-0.4f, c.pathSegments[6].pts[0], 0.001f)       // the tip
        assertEquals(1f, c.pathSegments[6].pts[1], 0.001f)
        assertEquals(com.example.model.PATH_OP_CLOSE, c.pathSegments[12].op)
        // Rendered width = 2 x half (125) against the 693.3 px shape box of a 1920-wide scene.
        assertEquals(250f / 693.333f, c.shapeScaleX, 0.001f)
    }

    @Test
    fun calloutVerbatimProyekInstanceImportsAndLinksToItsParent() {
        // Verbatim from proyek-baru-15.xml: the corpus's only callout — and the one parented
        // layer build 116 could not link, because its reader skipped the layer itself. Its
        // parent is the visible multifoil, so the link lands on the anchor that mirrors it,
        // and the anchor inherits the multifoil's own link to the null.
        val r = AlightXmlImporter.parse(scene("""<shape id="2999482906" label="Multifoil 1" startTime="0" endTime="2032" fillType="color" mediaFillMode="fill" parent="2999482908" s=".multifoil">
    <transform>
      <location>
        <kf t="0.336122" v="5.500000,-89.000000,0.000000" />
        <kf t="0.483760" v="-38.500000,-121.000000,0.000000" />
      </location>
    </transform>
    <fillColor value="#ff9d9e60" />
    <property name="pointCount" type="float" value="5.000000" />
    <property name="outerRadius" type="float" value="100.000000" />
    <property name="innerRadius" type="float" value="50.000000" />
    <property name="offsetAngle" type="float" value="0.000000" />
  </shape>
  <shape id="2999482907" label="Callout 1" startTime="0" endTime="2032" fillType="color" mediaFillMode="fill" parent="2999482906" s=".callout.roundrect">
    <transform>
      <location>
        <kf t="0.270669" v="-5.500000,329.000000,0.000000" />
        <kf t="0.369094" v="26.500000,276.000000,0.000000" />
      </location>
    </transform>
    <fillColor value="#ff6bd3cd" />
    <property name="size" type="vec2" value="100.000000,100.000000" />
    <property name="tail" type="vec2" value="-50.000000,150.000000" />
    <property name="cornerRadius" type="float" value="25.000000" />
    <property name="tailWidth" type="float" value="40.000000" />
  </shape>
  <nullobj id="2999482908" label="Nol 1" startTime="0" endTime="2032" mediaFillMode="fill" type="perspective">
    <transform>
      <location>
        <kf t="0.172244" v="358.500000,450.000000,0.000000" />
        <kf t="0.401575" v="402.000000,430.500000,0.000000" />
        <kf t="0.188484" v="366.987152,450.000000,0.000000" />
      </location>
    </transform>
  </nullobj>""", 720, 900), 720f)
        assertTrue(r.warnings.none { it.contains("is not supported yet") })
        assertTrue(r.warnings.any { it.contains("parents other layers") })
        val clips = r.snapshot.tracks.flatMap { it.clips }
        assertEquals(4, clips.size)   // the null, the multifoil, the callout, the anchor
        val callout = clips.single { it.name == "Callout 1" }
        assertEquals("PATH", callout.shapePattern)
        assertEquals(0xFF6BD3CDL, callout.textColor)
        assertEquals(13, callout.pathSegments.size)
        val anchor = clips.single { it.name == "Multifoil 1 (parent)" }
        val nullClip = clips.single { it.name == "Nol 1" }
        val foil = clips.single { it.name == "Multifoil 1" }
        assertNotNull(callout.parent)
        assertEquals(anchor.id, callout.parent!!.id)
        assertEquals(nullClip.id, foil.parent!!.id)
        assertEquals(nullClip.id, anchor.parent!!.id)
    }

    @Test
    fun calloutKeyframedTailKeepsFirstValue() {
        val r = AlightXmlImporter.parse(scene("""<shape id="32" label="Callout K" startTime="0" endTime="2000" fillType="color" s=".callout.roundrect">
    <transform><location value="960.000000,540.000000,0.000000" /></transform>
    <fillColor value="#ffff0000" />
    <property name="size" type="vec2" value="100.000000,100.000000" />
    <property name="tail" type="vec2"><kf t="0.000000" v="-50.000000,150.000000" /><kf t="1.000000" v="150.000000,-50.000000" /></property>
    <property name="cornerRadius" type="float" value="25.000000" />
    <property name="tailWidth" type="float" value="40.000000" />
  </shape>""", 1920, 1080), 1920f)
        assertTrue(r.warnings.any { it.contains("\"tail\"") && it.contains("first value is used") })
        val c = r.snapshot.tracks.flatMap { it.clips }.single()
        assertEquals(13, c.pathSegments.size)   // still the first value's bottom tail
        assertEquals(-0.4f, c.pathSegments[6].pts[0], 0.001f)
        assertEquals(1f, c.pathSegments[6].pts[1], 0.001f)
    }

    @Test
    fun calloutNonPositiveSizeIsSkipped() {
        val r = AlightXmlImporter.parse(scene("""<shape id="33" label="Callout 0" startTime="0" endTime="2000" fillType="color" s=".callout.roundrect">
    <transform><location value="960.000000,540.000000,0.000000" /></transform>
    <fillColor value="#ffff0000" />
    <property name="size" type="vec2" value="0.000000,100.000000" />
  </shape>""", 1920, 1080), 1920f)
        assertTrue(r.warnings.any { it.contains("size is not positive") && it.contains("skipped") })
        assertTrue(r.snapshot.tracks.flatMap { it.clips }.isEmpty())
    }

    @Test
    fun drawingImportsAsGroupOfStrokePaths() {
        // Verbatim from proyek-baru-15.xml: "Gambar 2", one fill stroke of 144 points.
        val r = AlightXmlImporter.parse(scene("""<drawing id="2999482913" label="Gambar 2" startTime="1433" endTime="1699" fillType="intrinsic" mediaFillMode="fill">
    <transform>
      <location value="360.000000,450.000000,0.000000" />
    </transform>
    <stroke color="#ffffffff" width="90.000000" type="fill" points="-107.00,297.00,1.00;-110.00,302.00,1.00;-112.00,307.00,1.00;-112.74,311.46,1.00;-113.00,313.00,1.00;-115.00,319.00,1.00;-115.74,323.41,1.00;-116.00,325.00,1.00;-118.00,331.00,1.00;-118.00,334.02,1.00;-118.00,335.00,1.00;-118.00,340.00,1.00;-118.00,342.26,1.00;-118.00,343.00,1.00;-118.00,346.00,1.00;-118.00,347.50,1.00;-118.00,348.00,1.00;-117.00,351.00,1.00;-116.25,351.75,1.00;-116.00,352.00,1.00;-114.00,354.00,1.00;-113.24,354.00,1.00;-113.00,354.00,1.00;-111.00,355.00,1.00;-107.99,355.00,1.00;-107.00,355.00,1.00;-104.00,355.00,1.00;-101.72,354.24,1.00;-101.00,354.00,1.00;-97.00,354.00,1.00;-92.87,354.00,1.00;-92.00,354.00,1.00;-88.00,354.00,1.00;-84.64,354.00,1.00;-84.00,354.00,1.00;-80.00,354.00,1.00;-76.87,354.78,1.00;-76.00,355.00,1.00;-73.00,355.00,1.00;-70.73,355.76,1.00;-70.00,356.00,1.00;-68.00,357.00,1.00;-66.49,358.51,1.00;-66.00,359.00,1.00;-64.00,361.00,1.00;-63.00,363.00,1.00;-62.00,364.00,1.00;-61.24,365.52,1.00;-61.00,366.00,1.00;-60.00,368.00,1.00;-59.21,368.79,1.00;-59.00,369.00,1.00;-59.00,371.00,1.00;-58.21,371.79,1.00;-58.00,372.00,1.00;-57.00,373.00,1.00;-55.00,373.00,1.00;-53.00,373.00,1.00;-51.41,371.41,1.00;-51.00,371.00,1.00;-49.00,370.00,1.00;-45.00,367.00,1.00;-41.00,363.00,1.00;-38.00,360.00,1.00;-34.00,356.00,1.00;-30.51,353.38,1.00;-30.00,353.00,1.00;-27.00,351.00,1.00;-24.00,349.00,1.00;-21.00,347.00,1.00;-19.26,347.00,1.00;-19.00,347.00,1.00;-16.00,346.00,1.00;-14.00,347.00,1.00;-11.00,347.00,1.00;-9.00,348.00,1.00;-8.00,349.00,1.00;-6.48,349.76,1.00;-6.00,350.00,1.00;-4.00,351.00,1.00;-3.00,351.00,1.00;-1.00,351.00,1.00;0.00,351.00,1.00;1.00,351.00,1.00;3.27,347.97,1.00;4.00,347.00,1.00;6.00,344.00,1.00;11.00,335.00,1.00;16.00,325.00,1.00;22.00,312.00,1.00;28.00,299.00,1.00;31.69,289.42,1.00;33.00,286.00,1.00;37.00,273.00,1.00;40.00,266.00,1.00;42.00,258.00,1.00;42.00,255.81,1.00;42.00,255.00,1.00;43.00,251.00,1.00;43.00,250.00,1.00;44.00,250.00,1.00;44.00,249.29,1.00;44.00,249.00,1.00;44.00,250.00,1.00;43.12,251.76,1.00;43.00,252.00,1.00;41.00,254.00,1.00;38.00,262.00,1.00;35.00,269.00,1.00;30.07,279.68,1.00;29.00,282.00,1.00;23.00,296.00,1.00;17.67,307.42,1.00;16.00,311.00,1.00;9.00,327.00,1.00;4.55,336.65,1.00;3.00,340.00,1.00;-4.00,354.00,1.00;-8.11,361.41,1.00;-9.00,363.00,1.00;-14.00,373.00,1.00;-18.26,379.82,1.00;-19.00,381.00,1.00;-24.00,388.00,1.00;-27.94,392.73,1.00;-29.00,394.00,1.00;-33.00,399.00,1.00;-35.29,402.81,1.00;-36.00,404.00,1.00;-40.00,409.00,1.00;-43.00,412.00,1.00;-47.00,415.00,1.00;-48.52,416.52,1.00;-49.00,417.00,1.00;-52.00,419.00,1.00;-53.52,419.76,1.00;-54.00,420.00,1.00;-57.00,420.00,1.00;-57.00,420.75,1.00;-57.00,421.00,1.00;-58.00,422.00,1.00;-58.00,423.55,1.00;-58.00,424.00,1.00;-59.00,425.00,1.00" />
  </drawing>""", 720, 900), 720f)
        assertTrue(r.warnings.none { it.contains("<drawing>") })
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val group = clips.single { it.type == LayerType.GROUP }
        assertEquals("Gambar 2", group.name)
        assertEquals(1433L, group.startMs)
        assertEquals(266L, group.durationMs)
        val kids = clips.filter { it.groupId == group.id }
        assertEquals(1, kids.size)
        val kid = kids.single()
        assertEquals(group.id, kid.parent?.id)
        assertEquals("PATH", kid.shapePattern)
        assertEquals(com.example.model.FILL_SOLID, kid.fillMode)   // a "fill" stroke: outline filled
        assertEquals(0xFFFFFFFFL, kid.textColor)
        assertEquals(com.example.model.PATH_OP_CLOSE, kid.pathSegments.last().op)
        assertEquals(group.startMs, kid.startMs)                     // born in root time, like a group's children
        assertEquals(group.durationMs, kid.durationMs)
    }

    @Test
    fun drawingStrokesKeepPaintAndPressureIsReported() {
        val r = AlightXmlImporter.parse(scene("""<drawing id="5" label="Sketch" startTime="0" endTime="2000" fillType="intrinsic">
    <transform><location value="540.000000,960.000000,0.000000" /></transform>
    <stroke color="#ffff0000" width="10.000000" type="pen" points="0.00,0.00,1.00;100.00,0.00,1.00" />
    <stroke color="#ff00ff00" width="20.000000" type="brush" points="0.00,50.00,1.00;0.00,150.00,0.50" />
  </drawing>"""), 1080f)
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val group = clips.single { it.type == LayerType.GROUP }
        val kids = clips.filter { it.groupId == group.id }
        assertEquals(2, kids.size)
        val pen = kids.first { it.strokeColor == 0xFFFF0000L }
        assertEquals(com.example.model.FILL_NONE, pen.fillMode)     // an open stroked polyline
        assertTrue(pen.strokeWidth > 0f)
        assertTrue(r.warnings.any { it.contains("pressure") })       // the 0.50 point varies
        // The pen stroke runs (0,0)->(100,0) in the drawing's local frame and the drawing sits at
        // the scene centre, so in the world the child's box centre lands 50 px right of centre.
        val rig = ParentRig(clips) { c, t -> c.renderAt(t) }
        val adj = rig.adjusted(pen, pen.startMs)!!
        assertEquals(50f, adj.transformX, 0.5f)
        assertEquals(0f, adj.transformY, 0.5f)
    }

    @Test
    fun cameraStaticImportsAsPose() {
        // Verbatim from proyek-baru-273-copy.xml: a parked perspective camera, fov 60 at
        // z = -623.54 on a 720-tall scene — zoom exactly 1, a pure pan down to y = 621.29.
        val r = AlightXmlImporter.parse(scene("""<camera id="2999482900" label="Kamera 1" startTime="0" endTime="27799" mediaFillMode="fill" type="perspective">
    <transform>
      <location value="360.000000,621.286743,-623.538330" />
    </transform>
    <fov value="60.000000" />
    <focus-blur enabled="false">
      <strength value="0.500000" />
      <depth-of-field value="62.353832" />
      <distance value="623.538330" />
    </focus-blur>
    <fog enabled="false">
      <near-z value="623.538330" />
      <far-z value="1247.076660" />
    </fog>
  </camera>""", 720, 720), 720f)
        assertTrue(r.warnings.none { it.contains("Kamera 1") })
        val cam = r.snapshot.tracks.flatMap { it.clips }.single { it.type == LayerType.CAMERA }
        assertEquals(100f, cam.scalePercent, 1f)
        assertEquals(0f, cam.transformX, 0.5f)
        assertEquals(261.29f, cam.transformY, 0.5f)
    }

    @Test
    fun cameraKeyframedMoveImports() {
        // Verbatim from proyek-baru-15.xml: "Kamera 1" glides through three locations at a
        // constant fov 40.56 / z -779.42 on a 900-tall scene — zoom 1.561 throughout.
        val r = AlightXmlImporter.parse(scene("""<camera id="2999482909" label="Kamera 1" startTime="333" endTime="1000" mediaFillMode="fill" type="perspective">
    <transform>
      <location>
        <kf t="-0.025487" v="451.500000,521.500000,-779.422852" />
        <kf t="0.475262" v="428.000000,390.000000,-779.422852" />
        <kf t="0.874063" v="125.500000,543.500000,-779.422852" />
      </location>
    </transform>
    <fov value="40.558975" />
    <focus-blur enabled="false">
      <strength value="0.500000" />
      <depth-of-field value="77.942284" />
      <distance value="779.422852" />
    </focus-blur>
    <fog enabled="false">
      <near-z value="779.422852" />
      <far-z value="1558.845703" />
    </fog>
  </camera>""", 720, 900), 720f)
        val cam = r.snapshot.tracks.flatMap { it.clips }
            .single { it.type == LayerType.CAMERA && it.name == "Kamera 1" }
        val pos = cam.keyframes[AnimProp.POSITION]
        assertTrue(pos != null && pos.size == 3)
        val scl = cam.keyframes[AnimProp.SCALE]
        assertTrue(scl != null && scl.size == 3)
        assertEquals(156.1f, scl!!.first().a, 0.6f)
        assertEquals(156.1f, scl.last().a, 0.6f)
    }

    @Test
    fun orthographicCameraIsSkippedWithWarning() {
        val r = AlightXmlImporter.parse(scene("""<camera id="7" label="Ortho" startTime="0" endTime="2000" type="orthographic">
    <transform><location value="540.000000,960.000000,-1000.000000" /></transform>
    <fov value="60.000000" />
  </camera>"""), 1080f)
        assertTrue(r.snapshot.tracks.flatMap { it.clips }.none { it.type == LayerType.CAMERA })
        assertTrue(r.warnings.any { it.contains("orthographic") })
    }

    private fun blendShape(blending: String) = scene(
        """<shape id="3" label="B" startTime="0" endTime="2000" fillType="color" s=".rect" blending="$blending">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <fillColor value="#ff336699" />
  <property name="size" type="vec2" value="100,100" /></shape>"""
    )

    @Test
    fun layerBlendModesMapToCompositor() {
        val cases = mapOf(
            "multiply" to "Multiply", "screen" to "Screen", "linear-dodge" to "Add",
            "exclusion" to "Exclusion", "overlay" to "Overlay", "darken" to "Darken",
            "subtract" to "Subtract", "soft-overlay" to "Soft Overlay",
            "color-burn" to "Color Burn", "soft-light" to "Soft Light",
            "difference" to "Difference", "luminosity" to "Luminosity", "add" to "Add"
        )
        for ((am, app) in cases) {
            val r = AlightXmlImporter.parse(blendShape(am), 1080f)
            val c = r.snapshot.tracks.flatMap { it.clips }.single()
            assertEquals(app, c.blendMode)
            assertTrue("no fallback warning for $am", r.warnings.none { it.contains("normal layer") })
        }
        // An unknown mode still imports, as normal, with the honest warning.
        val r = AlightXmlImporter.parse(blendShape("vivid-light"), 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }.single()
        assertEquals("Normal", c.blendMode)
        assertTrue(r.warnings.any { it.contains("vivid-light") })
    }

    @Test
    fun blendCatalogueHasNativeShaders() {
        // Subtract and Soft Overlay joined the catalogue with native shader ids (renderer.cpp).
        assertEquals(42, com.example.model.BlendModes.find("Subtract").shaderId)
        assertEquals(33, com.example.model.BlendModes.find("Soft Overlay").shaderId)
        assertEquals(
            com.example.model.BlendKind.COLOR,
            com.example.model.BlendModes.find("Subtract").kind
        )
    }

    @Test
    fun embedSceneBlendingLandsOnGroup() {
        val r = AlightXmlImporter.parse(scene("""<embedScene id="4" label="Grp" startTime="0" endTime="2000" blending="screen" fillType="intrinsic"><transform><location value="540,960,0" /></transform><fillColor value="#ff000000" />
<scene title="" width="1080" height="1920" totalTime="2000">
<shape id="5" label="In" startTime="0" endTime="2000" fillType="color" s=".rect"><transform><location value="540,960,0" /></transform><fillColor value="#ff00ff00" /><property name="size" type="vec2" value="50,50" /></shape>
</scene>
</embedScene>"""), 1080f)
        val group = r.snapshot.tracks.flatMap { it.clips }.single { it.type == LayerType.GROUP }
        assertEquals("Screen", group.blendMode)
    }

    // ---- v164: the parked tier (hunt round 2; defs recovered in defs-batch3) ----

    @Test
    fun shakeV1BakesSimplexPositionKeys() {
        // Verbatim from dj-pambasilet-marz.xml (one of its four identical shake v1 layers):
        // mag pulses 120 -> 0 -> 120, speed 2, angle 45, slack 0.25 — baked with the def's own
        // simplex script, NOT shake2's value noise, and with no slack warning (v1 USES slack).
        val xml = shapeWith(
            """<effect id="com.alightcreative.effects.shake" locallyApplied="false"><property name="mag" type="float"><kf t="0.025751" v="120.000000" /><kf t="0.474249" v="0.000000" e="cubicBezier 0.0 0.7395722 0.15890624 1.0" /><kf t="0.976395" v="120.000000" e="cubicBezier 0.84000003 0.0 1.0 0.26" /></property><property name="speed" type="float" value="2.000000" /><property name="evolution" type="float" value="0.000000" /><property name="seed" type="float" value="0.000000" /><property name="angle" type="float" value="45.000000" /><property name="slack" type="float" value="0.250000" /></effect>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        assertTrue(r.warnings.none { it.contains("shake is not supported") })
        assertTrue(r.warnings.none { it.contains("Effect shake: its slack") })
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        val pos = c.keyframes[AnimProp.POSITION]!!
        assertTrue(pos.size >= 8)
        // |d| <= mag * sqrt(1 + slack^2) ~ 124 at the pulse peaks, and the noise really moves.
        assertTrue(pos.all { abs(it.a) <= 130f && abs(it.b) <= 130f })
        assertTrue(pos.maxOf { Math.hypot(it.a.toDouble(), it.b.toDouble()) } > 5.0)
        val again = AlightXmlImporter.parse(xml, 1080f).snapshot.tracks.flatMap { it.clips }[0]
        assertEquals(pos, again.keyframes[AnimProp.POSITION])   // deterministic
    }

    @Test
    fun brightcontV1MapsExactlyLikeV2() {
        // Verbatim from am-renderer-preset3.xml. brightcont v1's shader is byte-identical to
        // brightcont2's (defs-batch3), so it takes the same Brightness & Contrast mapping.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.brightcont" locallyApplied="false"><property name="contrast" type="float" value="0.100000" /></effect>"""
        ), 1080f)
        assertTrue(r.warnings.none { it.contains("brightcont is not supported") })
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects.single { it.type == ClipFxLibrary.BRIGHT_CONTRAST }
        assertEquals(listOf(0f, 10f), fx.values.subList(0, 2))
    }

    @Test
    fun chromaKeyAmImports() {
        // Verbatim from proyek-baru-273-copy.xml: a black key at threshold 0.1 / feather 0.05.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.chromakey" locallyApplied="true"><property name="keyColor" type="color" value="#ff000000" /><property name="threshold" type="float" value="0.100000" /><property name="feather" type="float" value="0.050000" /><property name="defringe" type="bool" value="false" /><property name="invert" type="bool" value="false" /></effect>"""
        ), 1080f)
        assertTrue(r.warnings.none { it.contains("chromakey is not supported") })
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects.single { it.type == ClipFxLibrary.CHROMA_KEY_AM }
        assertEquals(listOf(0f, 10f, 5f, 0f, 0f, 0f, 0f), fx.values)
        assertEquals(75f, ClipFxLibrary.toNative(listOf(fx))!![0], 0.001f)
    }

    @Test
    fun colorBalanceAmImports() {
        // Verbatim from cwlzy-preset.xml — but WITHOUT its hidden="true": both corpus
        // instances are hidden and stay skipped (next test); this pins the mapping itself.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.colorbalance" locallyApplied="false"><property name="crLow" type="float" value="100.000000" /><property name="mgLow" type="float" value="-100.000000" /><property name="ybLow" type="float" value="100.000000" /><property name="crMid" type="float" value="0.000000" /><property name="mgMid" type="float" value="0.000000" /><property name="ybMid" type="float" value="0.000000" /><property name="crHigh" type="float" value="0.000000" /><property name="mgHigh" type="float" value="0.000000" /><property name="ybHigh" type="float" value="0.000000" /><property name="fixLum" type="bool" value="true" /></effect>"""
        ), 1080f)
        assertTrue(r.warnings.none { it.contains("colorbalance is not supported") })
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects.single { it.type == ClipFxLibrary.COLOR_BALANCE_AM }
        assertEquals(
            listOf(100f, -100f, 100f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f),
            fx.values
        )
        val arr = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(76f, arr[0], 0.001f)    // the main group
        assertEquals(77f, arr[8], 0.001f)    // the EXT group right behind it
        assertEquals(1f, arr[11], 0.001f)    // fixLum rides the EXT group (q2)
    }

    @Test
    fun colorBalanceHiddenInstanceStaysSkipped() {
        // The corpus instances as they actually are: hidden="true" — skipped as hidden (the
        // build-102 rule), NOT reported as unsupported.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.colorbalance" hidden="true" locallyApplied="false"><property name="crLow" type="float" value="100.000000" /><property name="fixLum" type="bool" value="true" /></effect>"""
        ), 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        assertTrue(c.effects.none { it.type == ClipFxLibrary.COLOR_BALANCE_AM })
        assertTrue(r.warnings.any { it.contains("hidden effects are disabled") })
        assertTrue(r.warnings.none { it.contains("colorbalance is not supported") })
    }

    @Test
    fun colorTemperatureImports() {
        // Verbatim from am-renderer-preset4.xml: 5540 K at strength 0.987.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.colortemperature" locallyApplied="false"><property name="temperature" type="float" value="5.540000" /><property name="strength" type="float" value="0.987000" /></effect>"""
        ), 1080f)
        assertTrue(r.warnings.none { it.contains("colortemperature is not supported") })
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects.single { it.type == ClipFxLibrary.COLOR_TEMP_AM }
        assertEquals(5.54f, fx.values[0], 0.001f)
        assertEquals(98.7f, fx.values[1], 0.01f)
        assertEquals(78f, ClipFxLibrary.toNative(listOf(fx))!![0], 0.001f)
    }

    @Test
    fun colorTintImports() {
        // Verbatim from am-renderer-preset3.xml: tint vec3 (hue -55.3, strength 0.0484, unused 0).
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.colortint" locallyApplied="false"><property name="tint" type="vec3" value="-55.304688,0.048402,0.000000" /></effect>"""
        ), 1080f)
        assertTrue(r.warnings.none { it.contains("colortint is not supported") })
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects.single { it.type == ClipFxLibrary.COLOR_TINT_AM }
        assertEquals(-55.304688f, fx.values[0], 0.001f)
        assertEquals(4.8402f, fx.values[1], 0.001f)
        assertEquals(79f, ClipFxLibrary.toNative(listOf(fx))!![0], 0.001f)
    }

    @Test
    fun spinBlurKeyframedAngleRidesTheTail() {
        // Verbatim from celz-preset-9x16.xml: the angle sweeps 0 -> 8 -> 0 degrees.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.spinblur2" locallyApplied="false"><property name="angle" type="float"><kf t="0.531532" v="8.000000" e="cubicBezier 0.65 0.0 1.0 1.0" /><kf t="0.919670" v="0.000000" e="cubicBezier 0.0 0.0 0.34517413 1.0" /><kf t="0.131381" v="0.000000" /></property></effect>"""
        ), 1080f)
        assertTrue(r.warnings.none { it.contains("spinblur2 is not supported") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.SPIN_BLUR_AM }
        assertEquals(0f, fx.values[0], 0.001f)   // the value at the layer's start
        assertEquals(3f, fx.values[7], 0.001f)   // the tail: three keys
        val peak = AmFxEval.resolve(listOf(fx), clip, clip.startMs + (clip.durationMs * 0.531532).toLong()).single()
        assertEquals(8f, peak.values[0], 0.01f)
        assertEquals(80f, ClipFxLibrary.toNative(listOf(fx))!![0], 0.001f)
    }

    @Test
    fun offsetKeyframedRidesTwoTails() {
        // Verbatim from rax-preset-249.xml: a slide-in, offset keyframed -9999 -> 0.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.offset" locallyApplied="true"><property name="offset" type="vec2"><kf t="0.000000" v="-9999.000000,0.000000" /><kf t="0.414656" v="0.000000,0.000000" e="cubicBezier 0.0 0.0 0.0 1.0" /></property><property name="feather" type="float" value="0.000000" /><property name="mask" type="bool" value="false" /></effect>"""
        ), 1080f)
        assertTrue(r.warnings.none { it.contains("offset is not supported") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.OFFSET_AM }
        assertEquals(-9999f, fx.values[0], 0.001f)
        assertEquals(2f, fx.values[7], 0.001f)   // the x tail: two keys (the y tail is a placeholder)
        val done = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs).single()
        assertEquals(0f, done.values[0], 0.001f)
        assertEquals(81f, ClipFxLibrary.toNative(listOf(fx))!![0], 0.001f)
    }

    @Test
    fun radialWipeImportsWithItsProgressTail() {
        // Verbatim from shared-preset-likenya.xml: progress keyframed 360 -> 0 (a wipe-in).
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.radialwipe" locallyApplied="false"><property name="progress" type="float"><kf t="0.001006" v="360.000000" /><kf t="0.482818" v="0.000000" e="cubicBezier 0.42 0.0 0.0 1.0" /></property><property name="offset" type="float" value="0.000000" /><property name="feather" type="float" value="0.000000" /><property name="direction" type="int" value="0" /><property name="repeatCount" type="float" value="1.000000" /></effect>"""
        ), 1080f)
        assertTrue(r.warnings.none { it.contains("radialwipe is not supported") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.RADIAL_WIPE_AM }
        assertEquals(360f, fx.values[0], 0.001f)
        assertEquals(ClipFxLibrary.packPoint(0f, 0f), fx.values[4])
        assertEquals(2f, fx.values[7], 0.001f)   // the progress tail
        val end = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs).single()
        assertEquals(0f, end.values[0], 0.001f)
        assertEquals(82f, ClipFxLibrary.toNative(listOf(fx))!![0], 0.001f)
    }

    @Test
    fun findEdgesAmImports() {
        // Verbatim from am-renderer-preset1.xml: smoothing 1, threshold 1 (the top of its
        // range), inverted.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.findedges" locallyApplied="false"><property name="smoothing" type="float" value="1.000000" /><property name="threshold" type="float" value="1.000000" /><property name="invert" type="bool" value="true" /></effect>"""
        ), 1080f)
        assertTrue(r.warnings.none { it.contains("findedges is not supported") })
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects.single { it.type == ClipFxLibrary.FIND_EDGES_AM }
        assertEquals(listOf(1f, 100f, 1f, 0f, 0f, 0f, 0f), fx.values)
        assertEquals(83f, ClipFxLibrary.toNative(listOf(fx))!![0], 0.001f)
    }

    @Test
    fun hexShiftImportsWithStrengthAndAngleTails() {
        // Verbatim from likk-preset-33.xml: strength 0 -> 0.011 -> 0 and angle ~3508 -> 3259
        // -> 3508, both keyframed (the strength track's last segment carries AM's `random`
        // easing, read as linear like the transform tracks read unknown easings).
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.hexshift" locallyApplied="false"><property name="strength" type="float"><kf t="-0.706714" v="0.000000" /><kf t="0.030035" v="0.011000" /><kf t="1.000000" v="0.000000" e="random 0.5 0.5 0.5 0.5 0.5 0.0" /></property><property name="size" type="float" value="127.000000" /><property name="stretch" type="vec2" value="0.090000,1.000000" /><property name="angle" type="float"><kf t="-0.706714" v="3508.500000" /><kf t="0.030035" v="3259.500000" /><kf t="1.000000" v="3508.500000" e="cubicBezier 0.0 0.0 0.0 1.0" /></property><property name="seed" type="float" value="0.100000" /><property name="screenSpace" type="bool" value="true" /><property name="maskToLayer" type="bool" value="false" /></effect>"""
        ), 1080f)
        assertTrue(r.warnings.none { it.contains("hexshift is not supported") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.HEX_SHIFT_AM }
        assertEquals(0.0106f, fx.values[0], 0.0005f)      // strength at t = 0 (between the first keys)
        assertEquals(127f, fx.values[1], 0.001f)
        assertEquals(9f, fx.values[2], 0.001f)            // stretch x 0.09 as %
        assertEquals(100f, fx.values[3], 0.001f)
        assertEquals(3269.7f, fx.values[4], 1f)           // angle at t = 0
        assertEquals(0.1f, fx.values[5], 0.001f)
        assertEquals(1f, fx.values[6], 0.001f)            // screen space
        assertEquals(0f, fx.values[7], 0.001f)            // EXT: mask to layer off
        assertEquals(3f, fx.values[14], 0.001f)           // the strength tail: three keys
        assertEquals(3f, fx.values[33], 0.001f)           // the angle tail behind it
        val arr = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(84f, arr[0], 0.001f)
        assertEquals(85f, arr[8], 0.001f)                 // the EXT group
    }

    @Test
    fun softGlowImports() {
        // Verbatim from valzy-prst-362.xml: all static, outsideAlpha 0 (no backdrop warning).
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.softglow" locallyApplied="false"><property name="strength" type="float" value="0.500000" /><property name="brightness" type="float" value="1.000000" /><property name="contrast" type="float" value="0.700000" /><property name="highlights" type="float" value="1.000000" /><property name="color" type="color" value="#ffffffff" /><property name="blend" type="float" value="1.000000" /><property name="alpha" type="float" value="1.000000" /><property name="outsideAlpha" type="float" value="0.000000" /></effect>"""
        ), 1080f)
        assertTrue(r.warnings.none { it.contains("softglow is not supported") })
        assertTrue(r.warnings.none { it.contains("softglow: its outside") })
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects.single { it.type == ClipFxLibrary.SOFT_GLOW_AM }
        assertEquals(
            listOf(50f, 100f, 70f, 100f, 0xFFFFFF.toFloat(), 100f, 100f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            fx.values
        )
        val arr = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(86f, arr[0], 0.001f)
        assertEquals(87f, arr[8], 0.001f)
    }

    @Test
    fun fillBehindImportsWithBgOffsetTails() {
        // Verbatim from winkoko-preset-1080sq.xml: bgOffset keyframed in x only; brightness /
        // fgAlpha / fgScale / fgOffset all at their defaults, so nothing is reported.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.fillbehind" locallyApplied="false"><property name="strength" type="float" value="0.080000" /><property name="feather" type="float" value="0.050000" /><property name="saturation" type="float" value="0.000000" /><property name="brightness" type="float" value="0.000000" /><property name="gamma" type="float" value="1.000000" /><property name="alpha" type="float" value="1.000000" /><property name="fgAlpha" type="float" value="1.000000" /><property name="tintColor" type="color" value="#ffff5566" /><property name="tintStrength" type="float" value="0.000000" /><property name="edgeColor" type="color" value="#ff000000" /><property name="edgeStrength" type="float" value="0.075000" /><property name="scanLines" type="float" value="0.298000" /><property name="scanLineCount" type="float" value="742.000000" /><property name="fgScale" type="float" value="1.000000" /><property name="bgOffset" type="vec2"><kf t="0.640714" v="0.000000,0.000000" e="cubicBezier 0.0 0.0 0.0 1.0" /><kf t="0.786699" v="-274.000000,0.000000" e="cubicBezier 0.0 0.0 0.0 1.0" /><kf t="0.927170" v="351.000000,0.000000" e="cubicBezier 0.0 0.0 0.0 1.0" /><kf t="0.997567" v="-277.000000,0.000000" e="cubicBezier 0.0 0.0 0.0 1.0" /></property><property name="bgScale" type="float" value="1.000000" /></effect>"""
        ), 1080f)
        assertTrue(r.warnings.none { it.contains("fillbehind is not supported") })
        assertTrue(r.warnings.none { it.contains("fillbehind: its") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.FILL_BEHIND_AM }
        val expected = listOf(8f, 5f, 100f, 0f, 0xFF5566.toFloat(), 7.5f, 29.8f, 742f, 100f, 0f, 0f, 0f, 100f, 0f)
        assertEquals(expected.size, fx.values.subList(0, 14).size)
        expected.forEachIndexed { i, v -> assertEquals("values[$i]", v, fx.values[i], 0.001f) }
        assertEquals(0f, fx.values[14], 0.001f)   // the strength tail is a placeholder (static)
        assertEquals(4f, fx.values[15], 0.001f)   // the bg offset x tail: four keys
        val mid = AmFxEval.resolve(listOf(fx), clip, clip.startMs + (clip.durationMs * 0.786699).toLong()).single()
        assertEquals(-274f, mid.values[10], 0.01f)
        val arr = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(88f, arr[0], 0.001f)
        assertEquals(89f, arr[8], 0.001f)
    }

    @Test
    fun lightglowImportsVerbatim() {
        // Verbatim from am-renderer-preset1.xml (without its hidden="true"; the corpus pair is
        // hidden anyway, and hidden instances stay hidden-skipped). Was parked at v164 over a
        // supposed host downsample shader; re-reading the def (defs-batch4) shows all four
        // passes share one shader and the buffers are plain downsample="2" targets.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.lightglow" locallyApplied="false"><property name="strength" type="float" value="0.055000" /><property name="threshold" type="float" value="0.000000" /><property name="intensity" type="float" value="3.670000" /><property name="color" type="color" value="#ffff5566" /><property name="blend" type="float" value="0.000000" /><property name="halo" type="float" value="0.000000" /><property name="alpha" type="float" value="0.750000" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("lightglow is not supported") })
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects.single { it.type == ClipFxLibrary.LIGHT_GLOW_AM }
        assertEquals(
            listOf(0.055f, 0f, 3.67f, 0xFF5566.toFloat(), 0f, 0f, 0.75f),
            fx.values
        )
        val arr = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(120f, arr[0], 0.001f)
    }

    @Test
    fun displacemapradImportsVerbatim() {
        // Verbatim from celz-preset-9x16.xml. Was parked at v164: the shader samples the
        // `comp` input and no def said backdrop-only or backdrop+self; the def's srcType
        // settles it — `comp` is srcType "comp" (the composite BELOW the layer; a layer
        // cannot sample its own not-yet-composited output), which is the renderer's
        // backdrop capture, so the family imports since v165.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.displacemaprad" locallyApplied="false"><property name="offsAngle" type="float"><kf t="0.919670" v="0.000000" e="cubicBezier 0.0 0.0 0.0 1.0" /><kf t="0.131381" v="0.000000" /><kf t="0.519520" v="80.000000" e="cubicBezier 1.0 0.0 1.0 1.0" /><kf t="0.506757" v="-80.000000" e="cubicBezier 0.42 0.0 1.0 0.0" /></property><property name="offsRadius" type="float" value="0.000000" /><property name="typex" type="int" value="1" /><property name="typey" type="int" value="2" /><property name="edges" type="int" value="1" /><property name="fromctr" type="bool" value="true" /><property name="invert" type="bool" value="true" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("displacemaprad is not supported") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.DISPLACE_MAP_RAD_AM }
        assertEquals(
            listOf(0f, 0f, 1f, 2f, 1f, 1f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            fx.values.subList(0, 14)
        )
        // The keyframed offsAngle swings -80 -> +80 across t = 0.5068..0.5195; at the end of
        // that segment (the last full millisecond before the +80 key) it is most of the way.
        val mid = AmFxEval.resolve(listOf(fx), clip, clip.startMs + (clip.durationMs * 0.51952).toLong()).single()
        assertTrue(mid.values[0] > 50f)
        val arr = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(116f, arr[0], 0.001f)
        assertEquals(117f, arr[8], 0.001f)
    }
    // ---- v165 tier (hunt round 3): every effect below imports from AM's recovered defs ----

    @Test
    fun wavewarp2ImportsVerbatim() {
        // celz-preset-9x16.xml: phase / m2 keyframed, the rest constant.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.wavewarp2" locallyApplied="false"><property name="phase" type="float"><kf t="0.000000" v="0.000000" /><kf t="1.000000" v="2.000000" /></property><property name="a1d" type="float" value="90.000000" /><property name="m1" type="float" value="20.000000" /><property name="m2" type="float"><kf t="0.000000" v="5.000000" /><kf t="1.000000" v="0.000000" /></property><property name="a2d" type="float" value="90.000000" /><property name="damping" type="float" value="0.000000" /><property name="dampingSpace" type="float" value="0.000000" /><property name="dampingOrigin" type="float" value="0.500000" /><property name="screenSpace" type="bool" value="false" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("wavewarp2") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.WAVE_WARP_AM }
        assertEquals(
            listOf(0f, 90f, 20f, 5f, 90f, 0f, 0f, 0.5f, 0f, 0f, 0f, 0f, 0f, 0f),
            fx.values.subList(0, 14)
        )
        val end = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs).single()
        assertEquals(2f, end.values[0], 0.01f)   // the keyframed phase, at its last key
        assertEquals(0f, end.values[3], 0.01f)   // the keyframed magnitude, at its last key
        val arr = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(90f, arr[0], 0.001f)
        assertEquals(91f, arr[8], 0.001f)
    }

    @Test
    fun randomdisplaceBakesIntoPosition() {
        // am-renderer-preset7.xml: mag / evolution keyframed. AM computes randomdisplace in
        // a script from the layer's location, so the import bakes it into position keys —
        // no fx entry, no warning.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.randomdisplace" locallyApplied="false"><property name="mag" type="float"><kf t="0.000000" v="75.000000" /><kf t="1.000000" v="0.000000" /></property><property name="evolution" type="float"><kf t="0.000000" v="2.500000" /><kf t="1.000000" v="0.000000" /></property><property name="seed" type="float" value="0.000000" /><property name="scatter" type="float" value="0.500000" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("randomdisplace") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        assertTrue(clip.effects.isEmpty())
        val keys = clip.keyframes[AnimProp.POSITION]
        assertTrue(keys != null && keys.size >= 3)
        assertTrue(keys!!.map { it.a }.distinct().size > 1 || keys.map { it.b }.distinct().size > 1)
    }

    @Test
    fun randomdisplaceOnTextBakesToo() {
        // Text layers used to store randomdisplace as an undrawn animator; AM applies it to
        // text like any layer, so the bake runs there too (am-renderer-preset9.xml values).
        val xml = scene(
            """<text id="7" label="T" startTime="0" endTime="2000" size="48" font="googlefonts?name=Montserrat&amp;weight=700" align="center">
  <effect id="com.alightcreative.effects.randomdisplace" locallyApplied="false"><property name="mag" type="float"><kf t="0.000000" v="0.000000" /><kf t="1.000000" v="500.000000" /></property><property name="evolution" type="float"><kf t="0.000000" v="0.000000" /><kf t="1.000000" v="5.000000" /></property><property name="seed" type="float" value="0.000000" /><property name="scatter" type="float" value="0.500000" /></effect>
  <content>Hi</content></text>"""
        )
        val r = AlightXmlImporter.parse(xml, 1080f)
        assertFalse(r.warnings.any { it.contains("randomdisplace") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        assertTrue(clip.textAnimators.none { it.effectId == AlightXmlImporter.RANDOMDISPLACE })
        assertTrue((clip.keyframes[AnimProp.POSITION]?.size ?: 0) >= 3)
    }

    @Test
    fun circularRipple2ImportsVerbatim() {
        // celz-preset-9x16.xml: frequency / strength keyframed to zero.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.circularripple2" locallyApplied="false"><property name="frequency" type="float"><kf t="0.000000" v="3.160000" /><kf t="1.000000" v="0.000000" /></property><property name="strength" type="float"><kf t="0.000000" v="0.048000" /><kf t="1.000000" v="0.000000" /></property><property name="phase" type="float" value="0.200000" /><property name="radius" type="float" value="0.800000" /><property name="feather" type="float" value="0.227000" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("circularripple") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.CIRCULAR_RIPPLE_AM }
        assertEquals(listOf(0.048f, 0.8f, 3.16f, 0.227f, 0.2f, 0f, 0f), fx.values.subList(0, 7))
        val end = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs).single()
        assertEquals(0f, end.values[2], 0.01f)
        assertEquals(0f, end.values[0], 0.01f)
    }

    @Test
    fun stretch2ImportsVerbatim() {
        // am-renderer-preset2.xml: scale keyframed flat, angle keyframed 0 -> 243.5.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.stretch2" locallyApplied="false"><property name="scale" type="float"><kf t="0.000000" v="1.000000" /><kf t="1.000000" v="1.000000" /></property><property name="angle" type="float"><kf t="0.000000" v="0.000000" /><kf t="1.000000" v="243.500000" /></property><property name="contentOnly" type="bool" value="false" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("stretch2") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.STRETCH_AM }
        assertEquals(listOf(1f, 0f, 0f, 2f), fx.values.subList(0, 4))   // [3] = version 2
        val end = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs).single()
        assertEquals(243.5f, end.values[1], 0.01f)
    }

    @Test
    fun squeezeImportsVerbatim() {
        // am-renderer-preset2.xml: strength keyframed 0.2 -> 0 (the def's shader reads
        // nothing else, so no other property is imported).
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.squeeze" locallyApplied="false"><property name="strength" type="float"><kf t="0.000000" v="0.200000" /><kf t="1.000000" v="0.000000" /></property></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("squeeze") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.SQUEEZE_AM }
        assertEquals(0.2f, fx.values[0], 0.001f)
        val end = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs).single()
        assertEquals(0f, end.values[0], 0.01f)
    }

    @Test
    fun fractalWarp4ImportsVerbatim() {
        // am-renderer-preset2.xml: the offset point keyframed (y to -720), parallax constant.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.fractalwarp4" locallyApplied="false"><property name="mag" type="float" value="0.040000" /><property name="octaves" type="float" value="3.000000" /><property name="offs" type="vec2"><kf t="0.000000" v="0.000000,0.000000" /><kf t="1.000000" v="0.000000,-720.000000" /></property><property name="parr" type="vec2" value="0.000000,-1020.000000" /><property name="scale" type="float" value="3.600000" /><property name="intensity" type="float" value="0.500000" /><property name="screenSpace" type="bool" value="false" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("fractalwarp4") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.FRACTAL_WARP_AM }
        assertEquals(
            listOf(0.04f, 3.6f, 0.5f, 3f, 0f, 0f, 0f, -1020f),
            fx.values.subList(0, 8)
        )
        val end = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs).single()
        assertEquals(-720f, end.values[5], 0.01f)   // the keyframed offs y
        val arr = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(95f, arr[0], 0.001f)
        assertEquals(96f, arr[8], 0.001f)
    }

    @Test
    fun stripesImportsVerbatim() {
        // preset-jj-solo-rhapdyy.xml: phase keyframed 0 -> 10, black bands.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.stripes" locallyApplied="false"><property name="amount" type="float" value="10.000000" /><property name="width" type="float" value="0.500000" /><property name="angle" type="float" value="115.000000" /><property name="phase" type="float"><kf t="0.000000" v="0.000000" /><kf t="1.000000" v="10.000000" /></property><property name="color" type="color" value="#ff000000" /><property name="punchout" type="bool" value="false" /><property name="smoothing" type="float" value="0.010000" /><property name="screenSpace" type="bool" value="false" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("stripes") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.STRIPES_AM }
        assertEquals(listOf(10f, 0.5f, 115f, 0f, 0f, 0.01f, 0f), fx.values.subList(0, 7))
        val end = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs).single()
        assertEquals(10f, end.values[3], 0.01f)
    }

    @Test
    fun turbulentDisplace3ImportsVerbatim() {
        // edit-winkoko-preset.xml: direction X, stretch (0.07, 1), single axis on; parr and
        // position are not serialized (their defaults ride the EXT group).
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.turbulentdisplace3" locallyApplied="true"><property name="intensity" type="float" value="0.278000" /><property name="evolution" type="float" value="0.000000" /><property name="direction" type="int" value="0" /><property name="scale" type="float" value="1.000000" /><property name="stretch" type="vec2" value="0.070000,1.000000" /><property name="seed" type="float" value="0.000000" /><property name="singleAxis" type="bool" value="true" /><property name="screenSpace" type="bool" value="false" /><property name="octaves" type="float" value="6.000000" /><property name="offs" type="float" value="0.000000" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("turbulentdisplace3") })
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects.single { it.type == ClipFxLibrary.TURBULENT_DISPLACE3_AM }
        assertEquals(
            listOf(0.278f, 1f, 0f, 0f, 0f, 6f, 0f, 0f, 0f, 0f, 0f, 0.07f, 1f, 2f),
            fx.values.subList(0, 14)
        )
    }

    @Test
    fun roughenEdgesImportsVerbatim() {
        // shake-997-vetri-editz.xml: evolution keyframed, octaves 2.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.roughenedges" locallyApplied="false"><property name="strength" type="float" value="0.473000" /><property name="evolution" type="float"><kf t="0.000000" v="0.000000" /><kf t="1.000000" v="2.754737" /></property><property name="detail" type="float" value="1.000000" /><property name="seed" type="float" value="0.000000" /><property name="octaves" type="float" value="2.000000" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("roughenedges") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.ROUGHEN_EDGES_AM }
        assertEquals(listOf(0.473f, 1f, 0f, 0f, 2f), fx.values.subList(0, 5))
        val end = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs).single()
        assertEquals(2.754737f, end.values[2], 0.01f)
    }

    @Test
    fun streaksImportsVerbatim() {
        // am-renderer-preset2.xml (strength keyframed): all four combine modes at max.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.streaks" locallyApplied="false"><property name="strength" type="float"><kf t="0.000000" v="0.000000" /><kf t="1.000000" v="0.500000" /></property><property name="angle" type="float" value="90.000000" /><property name="alpha" type="float" value="1.000000" /><property name="bias" type="float" value="0.000000" /><property name="rmode" type="int" value="1" /><property name="gmode" type="int" value="1" /><property name="bmode" type="int" value="1" /><property name="amode" type="int" value="1" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("streaks") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.STREAKS_AM }
        assertEquals(
            listOf(0f, 90f, 0f, 1f, 1f, 1f, 1f, 1f),
            fx.values.subList(0, 8)
        )
        val end = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs).single()
        assertEquals(0.5f, end.values[0], 0.01f)
        val arr = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(101f, arr[0], 0.001f)
        assertEquals(102f, arr[8], 0.001f)
    }

    @Test
    fun colorHotImportsVerbatim() {
        // cwlzy-preset.xml: the two hue discs serialize as vec3s (hue, saturation, bias).
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.colorhot" locallyApplied="false"><property name="color" type="vec3" value="338.166748,0.009561,-0.050444" /><property name="tint" type="vec3" value="1.276667,0.011114,0.000000" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("colorhot") })
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects.single { it.type == ClipFxLibrary.COLOR_HOT_AM }
        assertEquals(
            listOf(338.166748f, 0.009561f, -0.050444f, 1.276667f, 0.011114f, 0f),
            fx.values.subList(0, 6)
        )
    }

    @Test
    fun colorizeImportsVerbatim() {
        // am-renderer-preset10.xml: a negative hue, amount = the disc's saturation.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.colorize" locallyApplied="false"><property name="tint" type="vec3" value="-116.053452,0.423856,0.000000" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("colorize") })
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects.single { it.type == ClipFxLibrary.COLORIZE_AM }
        assertEquals(listOf(-116.053452f, 0.423856f), fx.values.subList(0, 2))
    }

    @Test
    fun highlightShadowImportsVerbatim() {
        // shake-effect-preset.xml: highlights at the 2.0 maximum, shadows at 1.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.highlightshadow" locallyApplied="false"><property name="highlights" type="float" value="2.000000" /><property name="shadows" type="float" value="1.000000" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("highlightshadow") })
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects.single { it.type == ClipFxLibrary.HIGHLIGHT_SHADOW_AM }
        assertEquals(listOf(1f, 2f), fx.values.subList(0, 2))
    }

    @Test
    fun spotColor2ImportsVerbatim() {
        // edit-freefire-mental.xml (without its hidden="true"; the corpus instances are
        // hidden and stay hidden-skipped): threshold at its 2.0 maximum, choke 2.22.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.spotcolor2" locallyApplied="false"><property name="choke" type="float" value="2.220000" /><property name="color" type="color" value="#ffff4444" /><property name="feather" type="float" value="0.000000" /><property name="threshold" type="float" value="2.000000" /><property name="saturation" type="float" value="0.000000" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("spotcolor2") })
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects.single { it.type == ClipFxLibrary.SPOT_COLOR_AM }
        assertEquals(listOf(0xFF4444.toFloat(), 2f, 0f, 0f, 2.22f), fx.values.subList(0, 5))
    }

    @Test
    fun simpleStars2ImportsVerbatim() {
        // shake-997-vetri-editz.xml: the parallax z and the star size are keyframed.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.simplestars2" locallyApplied="false"><property name="starParallax" type="vec3"><kf t="0.000000" v="0.000000,0.000000,0.000000" /><kf t="1.000000" v="0.000000,0.000000,101.000000" /></property><property name="scale" type="float" value="1.000000" /><property name="starSize" type="float"><kf t="0.000000" v="0.229000" /><kf t="1.000000" v="0.000000" /></property><property name="starFeather" type="float" value="1.000000" /><property name="color" type="color" value="#ffffffff" /><property name="alpha" type="float" value="1.000000" /><property name="starCount" type="float" value="15.000000" /><property name="brightness" type="float" value="1.000000" /><property name="blendMode" type="int" value="2" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("simplestars2") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.SIMPLE_STARS_AM }
        assertEquals(
            listOf(1f, 0.229f, 1f, 0xFFFFFF.toFloat(), 15f, 1f, 2f, 0f, 0f, 0f, 1f),
            fx.values.subList(0, 11)
        )
        val end = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs).single()
        assertEquals(101f, end.values[9], 0.01f)   // the keyframed parallax z
        assertEquals(0f, end.values[1], 0.01f)    // the keyframed star size
    }

    @Test
    fun ridgesImportsVerbatim() {
        // edit-freefire-mental.xml (v1, on a text layer there): offs / parr keyframed, the
        // stretch a SCALAR 0.2, intensity at its 1.5 maximum.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.ridges" locallyApplied="false"><property name="parr" type="vec2"><kf t="0.000000" v="0.000000,0.000000" /><kf t="1.000000" v="455.118713,345.000000" /></property><property name="offs" type="vec2"><kf t="0.000000" v="0.000000,0.000000" /><kf t="1.000000" v="623.000000,0.000000" /></property><property name="evolution" type="float" value="0.270000" /><property name="scale" type="float" value="1.000000" /><property name="stretch" type="float" value="0.200000" /><property name="intensity" type="float" value="1.500000" /><property name="seed" type="float" value="0.000000" /><property name="color1" type="color" value="#ffffffff" /><property name="color2" type="color" value="#ff1ed91e" /><property name="octaves" type="float" value="9.000000" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("ridges") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.RIDGES_AM }
        assertEquals(
            listOf(1.5f, 1f, 0.27f, 0f, 9f, 0xFFFFFF.toFloat(), 0x1ED91E.toFloat(), 0f, 0f, 0f, 0f, 0.2f, 0.2f, 1f),
            fx.values.subList(0, 14)
        )
        val end = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs).single()
        assertEquals(623f, end.values[7], 0.01f)   // the keyframed offs x
        assertEquals(345f, end.values[10], 0.01f)  // the keyframed parallax y
    }

    @Test
    fun ridges2ImportsVerbatim() {
        // text-toji-pack.xml (v2): evolution keyframed, the stretch a POINT (1, 0.09).
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.ridges2" locallyApplied="false"><property name="color1" type="color" value="#ff292929" /><property name="color2" type="color" value="#ffbebebe" /><property name="evolution" type="float"><kf t="0.000000" v="0.000000" /><kf t="1.000000" v="0.850000" /></property><property name="octaves" type="float" value="1.000000" /><property name="scale" type="float" value="7.130000" /><property name="seed" type="float" value="0.340000" /><property name="stretch" type="vec2" value="1.000000,0.090000" /><property name="intensity" type="float" value="1.000000" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("ridges2") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.RIDGES_AM }
        assertEquals(
            listOf(1f, 7.13f, 0f, 0.34f, 1f, 0x292929.toFloat(), 0xBEBEBE.toFloat(), 0f, 0f, 0f, 0f, 1f, 0.09f, 2f),
            fx.values.subList(0, 14)
        )
        val end = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs).single()
        assertEquals(0.85f, end.values[2], 0.01f)
    }

    @Test
    fun dots2ImportsVerbatim() {
        // shake-997-vetri-editz.xml: phase / density keyframed.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.dots2" locallyApplied="false"><property name="scale" type="float" value="1.400000" /><property name="phase" type="float"><kf t="0.000000" v="0.635000" /><kf t="1.000000" v="0.900000" /></property><property name="radius" type="float" value="0.025000" /><property name="feather" type="float" value="1.000000" /><property name="color" type="color" value="#ffffffff" /><property name="color2" type="color" value="#ffffffff" /><property name="density" type="float"><kf t="0.000000" v="8.000000" /><kf t="1.000000" v="1.000000" /></property><property name="fill" type="float" value="1.000000" /><property name="alpha" type="float" value="1.000000" /><property name="blendMode" type="int" value="0" /><property name="seed" type="float" value="0.000000" /><property name="slack" type="float" value="1.000000" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("dots2") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.DOTS_AM }
        assertEquals(
            listOf(1.4f, 0.635f, 0.025f, 1f, 0xFFFFFF.toFloat(), 0xFFFFFF.toFloat(), 8f, 0f, 0f, 1f, 1f, 0f, 0f, 1f),
            fx.values.subList(0, 14)
        )
        val end = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs).single()
        assertEquals(1f, end.values[6], 0.01f)   // the keyframed density
        assertEquals(0.9f, end.values[1], 0.01f) // the keyframed phase
    }

    @Test
    fun dissolveImportsVerbatim() {
        // shake-rcf.xml (on a text layer there): amount keyframed 0.75 -> 0.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.dissolve" locallyApplied="true"><property name="amount" type="float"><kf t="0.000000" v="0.750000" /><kf t="1.000000" v="0.000000" /></property><property name="seed" type="float" value="0.000000" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("dissolve") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.DISSOLVE_AM }
        assertEquals(0.75f, fx.values[0], 0.001f)
        val end = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs).single()
        assertEquals(0f, end.values[0], 0.01f)
    }

    @Test
    fun bendWarpImportsVerbatim() {
        // text-motion-graphics-preset.xml: origin LEFT, amount keyframed (10 keys there,
        // first / last kept here), offset constant at 0.5.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.bendwarp" locallyApplied="false"><property name="originType" type="int" value="0" /><property name="amount" type="float"><kf t="0.000000" v="0.000000" /><kf t="1.000000" v="30.300001" /></property><property name="offset" type="float" value="0.500000" /><property name="oppositeType" type="int" value="1" /><property name="highLimit" type="float" value="0.750000" /><property name="lowLimit" type="float" value="0.250000" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("bendwarp") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.BEND_WARP_AM }
        assertEquals(listOf(0f, 0.5f, 0f, 1f, 0.75f, 0.25f), fx.values.subList(0, 6))
        val end = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs).single()
        assertEquals(30.300001f, end.values[0], 0.01f)
    }

    @Test
    fun displaceMap3ImportsVerbatim() {
        // beraksi-ryzenzkidss.xml: the offset keyframed (0,0) -> (0,50) under elastic
        // easings there; first / last keys kept here.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.displacemap3" locallyApplied="false"><property name="offs" type="vec2"><kf t="0.000000" v="0.000000,0.000000" /><kf t="1.000000" v="0.000000,50.000000" /></property><property name="typex" type="int" value="1" /><property name="typey" type="int" value="2" /><property name="edges" type="int" value="1" /><property name="fromctr" type="bool" value="false" /><property name="invert" type="bool" value="false" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("displacemap3") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.DISPLACE_MAP_AM }
        assertEquals(listOf(0f, 0f, 1f, 2f, 1f, 0f, 0f), fx.values.subList(0, 7))
        val end = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs).single()
        assertEquals(50f, end.values[1], 0.01f)   // the keyframed offs y
        val arr = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(115f, arr[0], 0.001f)
    }

    @Test
    fun smoothBevelImportsVerbatim() {
        // text-motion-graphics-preset.xml (on a text layer there): the specular angle
        // keyframed 0 -> 360, radius 6, specular intensity 1.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.internal.smoothbevel" locallyApplied="false"><property name="radius" type="float" value="6.000000" /><property name="angle_d" type="float" value="143.000000" /><property name="angle_s" type="float"><kf t="0.000000" v="0.000000" /><kf t="1.000000" v="360.000000" /></property><property name="color_d" type="color" value="#ffffffff" /><property name="color_s" type="color" value="#ffffffff" /><property name="intensity_d" type="float" value="0.500000" /><property name="intensity_s" type="float" value="1.000000" /><property name="elevation_d" type="float" value="0.000000" /><property name="elevation_s" type="float" value="2.200000" /><property name="spec" type="float" value="35.000000" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("smoothbevel") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.SMOOTH_BEVEL_AM }
        assertEquals(
            listOf(6f, 143f, 0f, 0.5f, 1f, 0f, 2.2f, 35f, 0xFFFFFF.toFloat(), 0xFFFFFF.toFloat()),
            fx.values.subList(0, 10)
        )
        val end = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs).single()
        assertEquals(360f, end.values[2], 0.01f)
        val arr = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(118f, arr[0], 0.001f)
        assertEquals(119f, arr[8], 0.001f)
    }

    @Test
    fun innerGlowImportsVerbatim() {
        // shake-technical-jm-legacy.xml — the one visible inner-glow instance in the
        // corpus, missed by every id-list census (its id sits outside effects.*) and
        // found by the v165 full sweep.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.inner-glow" locallyApplied="false"><property name="radius" type="float" value="83.000000" /><property name="alpha" type="float" value="0.180000" /><property name="hardness" type="float" value="0.110000" /><property name="color" type="color" value="#ffffffff" /><property name="blendMode" type="int" value="2" /></effect>"""
        ), 1080f)
        assertFalse(r.warnings.any { it.contains("inner-glow") })
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects.single { it.type == ClipFxLibrary.INNER_GLOW_AM }
        assertEquals(listOf(83f, 0.18f, 0.11f, 0xFFFFFF.toFloat(), 2f), fx.values.subList(0, 5))
        val arr = ClipFxLibrary.toNative(listOf(fx))!!
        assertEquals(121f, arr[0], 0.001f)
    }

    @Test
    fun darkGlowImportsVerbatim() {
        // Verbatim from 7e363d75 (the Graph preset) — one of its three darkglow instances,
        // previously approximated by the app's bright-part Glow (build 120 ports the def).
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.darkglow" locallyApplied="true"><property name="strength" type="float" value="0.250000" /><property name="threshold" type="float" value="0.300000" /><property name="color" type="color" value="#ffff5566" /><property name="blend" type="float" value="0.000000" /><property name="alpha" type="float" value="0.750000" /></effect>"""
        ), 1080f)
        assertTrue(r.warnings.none { it.contains("darkglow") })
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        assertTrue(clip.effects.none { it.type == ClipFxLibrary.GLOW })
        val fx = clip.effects.single { it.type == ClipFxLibrary.DARK_GLOW_AM }
        assertEquals(25f, fx.values[0], 0.001f)
        assertEquals(30f, fx.values[1], 0.001f)
        assertEquals(0xFF5566.toFloat(), fx.values[2], 0.001f)
        assertEquals(0f, fx.values[3], 0.001f)
        assertEquals(75f, fx.values[4], 0.001f)
        assertEquals(122f, ClipFxLibrary.toNative(listOf(fx))!![0], 0.001f)
    }

    @Test
    fun darkGlowKeyframedStrengthRidesATail() {
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.darkglow" locallyApplied="true"><property name="strength" type="float"><kf t="0.000000" v="0.010000" /><kf t="0.500000" v="0.250000" /></property><property name="threshold" type="float" value="0.473000" /><property name="color" type="color" value="#ffff5566" /><property name="blend" type="float" value="0.000000" /><property name="alpha" type="float" value="0.750000" /></effect>"""
        ), 1080f)
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.DARK_GLOW_AM }
        assertEquals(1f, fx.values[0], 0.001f)   // the first keyframe's strength, in %
        assertEquals(2f, fx.values[7], 0.001f)   // the tail: two keys
        val done = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs).single()
        assertEquals(25f, done.values[0], 0.01f)
    }

    @Test
    fun edgeGlowGraphBarVerbatim() {
        // Verbatim from 7e363d75 (a Graph bar): build 128 maps it to the native Edge
        // Glow with every value in AM units; strength 0 keeps the edge buffer
        // unblurred, exactly as AM's chain composites it.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.edgeglow" locallyApplied="true"><property name="smoothing" type="float" value="2.050000" /><property name="threshold" type="float" value="0.590000" /><property name="invert" type="bool" value="false" /><property name="strength" type="float" value="0.000000" /><property name="spread" type="float" value="1.540000" /><property name="fillColor" type="color" value="#ffffffff" /></effect>"""
        ), 1080f)
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        assertTrue(clip.effects.none { it.type == ClipFxLibrary.DARK_GLOW_AM })
        assertTrue(clip.effects.none { it.type == ClipFxLibrary.GLOW })
        val fx = clip.effects.single { it.type == ClipFxLibrary.EDGE_GLOW_AM }
        assertEquals(2.05f, fx.values[0], 0.001f)
        assertEquals(0.59f, fx.values[1], 0.001f)
        assertEquals(0f, fx.values[2], 0.001f)       // invert false
        assertEquals(0f, fx.values[3], 0.001f)       // strength 0
        assertEquals(1.54f, fx.values[4], 0.001f)
        assertEquals(0xFFFFFF.toFloat(), fx.values[5], 0.01f)
        assertEquals(0f, fx.values[6], 0.001f)       // blend: the def's default
        assertEquals(1f, fx.values[7], 0.001f)       // gamma: the def's default
        assertTrue(r.warnings.none { it.contains("edgeglow") })
    }

    @Test
    fun extrudeStaysParked() {
        // Build 127 un-parked it: corpus round 2 found 8 real instances + the def in a
        // second dump, and the instances themselves answer the orient question (the
        // only serialised orient is the identity quaternion). The full pins live in
        // ExtrudeImportTest; this keeps the old parked test's fixture honest.
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.extrude" locallyApplied="false"><property name="depth" type="float" value="0.100000" /></effect>"""
        ), 1080f)
        assertTrue(r.warnings.none { it.contains("extrude is not supported yet") })
        val fx = r.snapshot.tracks.flatMap { it.clips }[0].effects
            .single { it.type == ClipFxLibrary.EXTRUDE_AM }
        assertEquals(0.1f, fx.values[0], 0.001f)
    }

}
