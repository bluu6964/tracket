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
        assertTrue(r.warnings.any { it.contains("progress") })
        assertTrue(r.warnings.any { it.contains("solidcolor") })
        assertTrue(r.warnings.any { it.contains("movealongpath3") })
        assertTrue(r.warnings.any { it.contains("corner radius") })
        assertTrue(r.warnings.any { it.contains("\"mask\"") })
        assertTrue(r.warnings.any { it.contains("plain line") })
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
}
