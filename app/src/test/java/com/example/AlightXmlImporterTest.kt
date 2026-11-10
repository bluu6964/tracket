package com.example

import com.example.engine.AlightXmlImporter
import com.example.model.AnimProp
import com.example.model.LayerType
import com.example.model.packColor
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
        // 30 * 2 = 60 scene px; the app's text is 0.11 * 1080 = 118.8 px at 100 %
        assertEquals(60f / (0.11f * 1080f) * 100f, c.scalePercent, 0.01f)
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
        // size 100x100 x scale (10.7, 5.76) = 1070x576 scene px, against the 130 dp box
        assertEquals(1070f / box3, rect.shapeScaleX, 0.001f)
        assertEquals(576f / box3, rect.shapeScaleY, 0.001f)
        assertEquals(1070f / box3 * 100f, rect.scalePercent, 0.01f)
        assertEquals(0f, rect.transformX, 0.001f)   // centred in the scene
        assertEquals(0f, rect.transformY, 0.001f)
        assertEquals(5832L, rect.durationMs)
        assertEquals(null, rect.groupId)
    }

    @Test
    fun strokeOnlyCircleKeepsItsStrokeAndGroupLink() {
        val clips = threeClips()
        val group2 = clips.first { it.name == "Group 2" }
        val c = clips.first { it.name == "Circle 3" }
        assertEquals("CIRCLE", c.shapePattern)
        assertEquals(com.example.model.FILL_NONE, c.fillMode)
        assertEquals(0xFFFFFFFFL, c.strokeColor)
        assertEquals(29.5f * 360f / 1920f, c.strokeWidth, 0.001f)   // scene px -> dp of a 360 dp canvas
        assertEquals(1666L, c.startMs)
        assertEquals(1533L, c.durationMs)
        assertEquals(group2.id, c.groupId)
        val link = c.parent!!
        assertEquals(group2.id, link.id)
        // The link's ref point is the group's pivot (0, 26.62) in the children's own frame: rest scale / rotation.
        assertEquals(-960f, link.refX, 0.001f)
        assertEquals(26.620605f - 540f, link.refY, 0.001f)
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
        assertEquals(-12.5f, group2.transformY, 0.001f)
        // A group inside it is itself a child of it.
        val group1 = clips.first { it.name == "Group 1" }
        assertEquals(group2.id, group1.groupId)
        assertEquals(group2.id, group1.parent!!.id)
        assertEquals(109.3878f, group1.scalePercent, 0.001f)
        // The group's own keyframed move / turn, with the easing of the segment it ends at moved one key back.
        val pos = group1.keyframes[AnimProp.POSITION]!!
        assertEquals(listOf(1016L, 1516L), pos.map { it.timeMs })
        assertEquals(-0.5f, pos[0].a, 0.001f)
        assertEquals(434.0308f, pos[1].a, 0.001f)
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
        assertEquals(10f * 360f / 1920f, outline.strokeWidth, 0.001f)
        // Fallback size = the first keyframe (522x136) x the transform scale 1.122605.
        assertEquals(522f * 1.122605f / box3, outline.shapeScaleX, 0.001f)
        assertEquals(136f * 1.122605f / box3, outline.shapeScaleY, 0.001f)
        val keys = outline.keyframes[AnimProp.SCALE]!!
        assertEquals(listOf(1016L, 1516L, 3183L, 3850L), keys.map { it.timeMs })
        assertEquals(522f * 1.122605f / box3 * 100f, keys[0].a, 0.01f)
        assertEquals(136f * 1.122605f / box3 * 100f, keys[1].a, 0.01f)
        assertEquals(136f * 1.122605f / box3 * 100f, keys[2].a, 0.01f)
        assertEquals(372f * 1.122605f / box3 * 100f, keys[3].a, 0.01f)
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
        assertEquals(100f * 0.245f / box3 * 100f, keys[1].a, 0.01f)
        assertEquals(100f * 0.365f / box3 * 100f, keys[2].a, 0.01f)
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
        assertTrue(r.warnings.any { it.contains("\"mask\"") })
        assertTrue(r.warnings.any { it.contains("plain line") })
    }

    @Test
    fun roundRectCornerRadiusIsImportedAsBoxDp() {
        val r = threeResult()
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val radii = clips.filter { it.shapePattern == "ROUNDED_RECT" }.map { it.shapeCornerRadius }
        assertTrue(radii.isNotEmpty())
        assertTrue(radii.all { it >= 0f })
        // Real file "3": size 100 x 100 with cornerRadius 32 -> 130 * 32 / 100 dp of the shape box.
        assertTrue(radii.any { abs(it - 130f * 32f / 100f) < 0.01f })
        // Size 522 x 136 with cornerRadius 373: Alight clamps to half the smaller side (68) -> 130 * 68 / 522.
        assertTrue(radii.any { abs(it - 130f * 68f / 522f) < 0.01f })
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
        // First value 20 (smaller side 100, so no clamp) -> 130 * 20 / 200.
        assertEquals(130f * 20f / 200f, c.shapeCornerRadius, 0.01f)
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
        assertEquals(200f / (1080f * 130f / 360f), c.shapeScaleX, 0.001f)
        assertEquals(50f / (1080f * 130f / 360f), c.shapeScaleY, 0.001f)
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
        assertTrue(r.warnings.any { it.contains("textprogress is saved") })
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
        val base = 60f / (0.11f * 1080f) * 100f
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
        assertEquals(200f / box, c.shapeScaleX, 0.001f)
        assertEquals(100f / box, c.shapeScaleY, 0.001f)
        assertEquals(200f / box * 100f, c.scalePercent, 0.01f)
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
        val base = 30f / (0.11f * 1080f) * 100f
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
        assertEquals(200f / box * 100f, c.scalePercent, 0.01f)   // the shape's own scale x 2
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
        assertTrue(r.warnings.any { it.contains("counter is saved with the layer") })
    }

    @Test
    fun counterOnAShapeStaysAWarning() {
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.counter" locallyApplied="true"><property name="offset" type="float" value="0" /></effect>"""
        ), 1080f)
        assertTrue(r.warnings.any { it.contains("counter is not supported yet") })
    }

    @Test
    fun repeatStaysAWarningWithReason() {
        val r = AlightXmlImporter.parse(shapeWith(
            """<effect id="com.alightcreative.effects.repeat.radial" locallyApplied="true"><property name="count" type="int" value="6" /></effect>"""
        ), 1080f)
        assertTrue(r.warnings.any { it.contains("cannot repeat a layer") })
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
