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
}
