package com.example

import com.example.engine.AlightMediaLocalizer
import com.example.engine.AlightXmlImporter
import com.example.model.LayerType
import com.example.model.ProjectSnapshot
import com.example.model.TimelineClip
import com.example.model.TimelineTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Media copy-at-import ([AlightMediaLocalizer]) and the font-weight rules, exercised on a trimmed but
 * verbatim excerpt of the real "ST Intro Template 1" export: the pill group (a media-fill shape,
 * `fillImage` .../53288, and the "Channel Name" text in an `imported` font), the "Logo Here" group
 * (media shape .../1000033464 under its mask), the end-card text (Roboto weight 700) and the audio
 * layer (content://.../53292).
 */
class AlightMediaLocalizerTest {

    private val stIntro = """<?xml version='1.0' encoding='UTF-8' ?>
<scene title="ST Intro Template 1 (13 July) " width="1920" height="1080" bgcolor="#ffd8d8d8" totalTime="8032" fps="30"><text id="633209" startTime="5166" endTime="8032" fillType="color" mediaFillMode="stretch" size="18.000000" font="googlefonts?name=Roboto&amp;weight=700" wrapWidth="512" align="center"><transform><location value="960.000000,726.500000,0.000000" /><scale value="0.839844,0.839844" /></transform><fillColor value="#ff9257ff" /><effect id="com.alightcreative.effects.textspacing" locallyApplied="true"><property name="letterspacing" type="float" value="0.130000" /><property name="linespacing" type="float" value="1.000000" /></effect><content>Channel Name</content></text><embedScene id="633599" label="Group 1 Copy" startTime="0" endTime="8032" fillType="intrinsic" mediaFillMode="stretch"><transform><location value="959.000000,544.500000,0.000000" /><pivot value="7.500000,-8.500000" /></transform><fillColor value="#ff000000" /><shadow direction="outside" enabled="false"><color value="#ff2f1363" /><opacity value="0.400000" /><size value="63.000000" /><offset value="25.000000,25.000000" /></shadow><scene title="" width="1920" height="1080" exportWidth="1920" exportHeight="1080" precompose="dynamicResolution" bgcolor="#00000000" totalTime="8032" fps="30" modifiedTime="0" amver="1002592" ffver="106" am="com.alightcreative.motion/5.0.271.1002592" amplatform="android" retime="off" retimeAdaptFPS="false"><shape id="633216" label="Circle 2" startTime="1900" endTime="5065" fillType="color" mediaFillMode="stretch" s=".roundrect"><transform><location><kf t="0.352607" v="962.033203,534.500000,0.000000" /><kf t="0.552923" v="1360.459595,534.500000,0.000000" e="cubicBezier 0.44 0.0 0.13 1.0" /><kf t="0.573776" v="1360.459595,534.500000,0.000000" /><kf t="0.774092" v="962.033203,534.500000,0.000000" e="cubicBezier 0.87 0.0 0.56 1.0" /></location><scale><kf t="0.721327" v="0.792888,0.792888" /><kf t="0.826540" v="0.007222,0.007222" e="cubicBezier 0.44 0.0 0.13 1.0" /></scale></transform><fillColor><kf t="0.721327" v="#ffffffff" /><kf t="0.826540" v="#ffffffff" /></fillColor><effect id="com.alightcreative.effects.scaleassist" locallyApplied="true"><property name="axis" type="int" value="3" /><property name="scale" type="float"><kf t="0.573776" v="1.000000" /><kf t="0.721327" v="1.175000" e="cubicBezier 0.87 0.0 0.56 1.0" /><kf t="0.826540" v="1.000000" e="cubicBezier 0.44 0.0 0.13 1.0" /></property><property name="damp" type="float" value="1.000000" /></effect><property name="size" type="vec2"><kf t="-0.595261" v="100.000000,100.000000" /><kf t="-0.384518" v="494.000000,100.000000" e="cubicBezier 0.43666667 0.0 0.12666665 1.0" /><kf t="0.247393" v="494.000000,100.000000" /><kf t="0.552923" v="100.000000,100.000000" e="cubicBezier 0.44 0.0 0.13 1.0" /></property><property name="cornerRadius" type="float" value="100.000000" /></shape><shape id="633218" label="Circle Copy" startTime="0" endTime="8032" fillType="color" mediaFillMode="stretch" s=".roundrect"><transform><location><kf t="0.375498" v="962.033264,534.500000,0.000000" /><kf t="0.541584" v="962.033264,534.500000,0.000000" e="cubicBezier 0.87 0.0 0.56 1.0" /><kf t="0.454432" v="560.038818,534.500000,0.000000" e="cubicBezier 0.36333334 0.0 0.28666666 1.0" /><kf t="0.462649" v="560.038818,534.500000,0.000000" /></location><scale><kf t="0.520792" v="0.792888,0.792888" /><kf t="0.562251" v="1.335000,1.335000" e="cubicBezier 0.44 0.0 0.13 1.0" /></scale></transform><fillColor><kf t="0.541584" v="#ffffffff" /><kf t="0.562251" v="#ff7a32ff" /></fillColor><effect id="com.alightcreative.effects.scaleassist" locallyApplied="true"><property name="axis" type="int" value="3" /><property name="scale" type="float"><kf t="0.462649" v="1.000000" /><kf t="0.520792" v="1.175000" e="cubicBezier 0.87 0.0 0.56 1.0" /><kf t="0.562251" v="1.000000" e="cubicBezier 0.44 0.0 0.13 1.0" /></property><property name="damp" type="float" value="1.000000" /></effect><property name="size" type="vec2"><kf t="0.001992" v="100.000000,100.000000" /><kf t="0.085035" v="494.000000,100.000000" e="cubicBezier 0.43666667 0.0 0.12666665 1.0" /><kf t="0.334039" v="494.000000,100.000000" /><kf t="0.454432" v="100.000000,100.000000" e="cubicBezier 0.44 0.0 0.13 1.0" /></property><property name="cornerRadius" type="float" value="100.000000" /></shape><shape id="633219" label="send-message.png" startTime="0" endTime="2899" fillType="media" fillImage="content://media/external/images/media/53288" mediaFillMode="stretch" s=".rect"><transform><location><kf t="0.000000" v="967.187012,534.500000,0.000000" /><kf t="0.235598" v="1276.017090,534.500000,0.000000" e="cubicBezier 0.44 0.0 0.13 1.0" /></location><scale><kf t="0.741290" v="0.066808,0.066808" /><kf t="0.890997" v="0.066808,0.066808" /><kf t="0.810280" v="0.040379,0.040379" /></scale><opacity><kf t="0.925492" v="1.000000" /><kf t="0.994481" v="0.000000" /></opacity></transform><effect id="com.alightcreative.solidcolor" locallyApplied="true"><property name="color" type="color" value="#ffb891ff" /><property name="alpha" type="float" value="1.000000" /><property name="blendMode" type="int" value="0" /></effect><property name="size" type="vec2" value="540.000000,540.000000" /></shape><text id="633220" startTime="666" endTime="2899" fillType="color" mediaFillMode="stretch" size="18.000000" font="imported?name=MontserratAlternates-Bold.otf" wrapWidth="512" align="left"><transform><location value="870.454651,532.517761,0.000000" /><scale value="0.792888,0.792888" /><opacity><kf t="0.903269" v="1.000000" /><kf t="0.992835" v="0.000000" /></opacity></transform><fillColor value="#ffb891ff" /><effect id="com.alightcreative.effects.textprogress" locallyApplied="true"><property name="start" type="float" value="0.000000" /><property name="end" type="float"><kf t="0.007613" v="0.000000" /><kf t="0.619794" v="1.000000" /></property><property name="cursor" type="int" value="0" /><property name="blink" type="bool" value="false" /></effect><content>Channel Name</content></text></scene></embedScene><embedScene id="633205" label="Logo Here" startTime="4133" endTime="8032" fillType="intrinsic" outTime="-2147482782" mediaFillMode="stretch"><transform><location value="960.000000,540.000000,0.000000" /><scale><kf t="0.012824" v="0.000000,0.000000" /><kf t="0.132342" v="0.380216,0.380216" e="cubicBezier 0.44 0.0 0.13 1.0" /></scale></transform><fillColor value="#ff000000" /><scene title="" width="1920" height="1080" exportWidth="1920" exportHeight="1080" precompose="dynamicResolution" bgcolor="#00000000" totalTime="3032" fps="30" modifiedTime="0" amver="1002592" ffver="106" am="com.alightcreative.motion/5.0.271.1002592" amplatform="android" retime="off" retimeAdaptFPS="false"><shape id="633203" label="images.png" startTime="0" endTime="3032" fillType="media" fillImage="content://media/external/images/media/1000033464" mediaFillMode="stretch" s=".rect"><transform><location value="960.000000,540.000000,0.000000" /></transform><property name="size" type="vec2" value="960.000000,503.500000" /></shape><shape id="633204" label="Mask" startTime="0" endTime="3032" fillType="color" blending="mask" mediaFillMode="stretch" s=".circle"><transform><location value="960.000000,540.000000,0.000000" /><scale value="3.235000,3.235000" /></transform><fillColor value="#ffd1c251" /><property name="size" type="vec2" value="100.000000,100.000000" /></shape></scene></embedScene><audio id="633212" label="Minimal Intro SOUND.mp3" startTime="0" endTime="599" src="content://media/external/audio/media/53292" inTime="3500" outTime="4067" mediaFillMode="stretch" /></scene>"""

    private val ownAuthority = "com.example.app.fileprovider"
    private val imgPill = "content://media/external/images/media/53288"
    private val imgLogo = "content://media/external/images/media/1000033464"
    private val audioSrc = "content://media/external/audio/media/53292"

    private fun clips(s: ProjectSnapshot) = s.tracks.flatMap { it.clips }

    @Test
    fun stIntroMediaIsCopiedAndRewritten() {
        val parsed = AlightXmlImporter.parse(stIntro, 1920f)
        // Every media reference of the template is a foreign content:// uri before the copy step.
        val before = clips(parsed.snapshot)
        assertEquals(setOf(imgPill, imgLogo), before.mapNotNull { it.fillImageUri }.toSet())
        assertEquals(audioSrc, before.single { it.type == LayerType.AUDIO }.uri)

        val calls = ArrayList<String>()
        val r = AlightMediaLocalizer.localize(parsed.snapshot, parsed.warnings, ownAuthority) { src ->
            calls += src
            "content://$ownAuthority/imports/copy_${calls.size}.bin"
        }
        // One resolve per distinct file (the pill picture, the logo picture, the audio), no repeats.
        assertEquals(listOf(imgPill, imgLogo, audioSrc).sorted(), calls.sorted())
        assertEquals(3, r.copiedFiles)
        assertTrue(r.failedNames.isEmpty())

        val after = clips(r.snapshot)
        assertTrue(after.none { it.fillImageUri?.startsWith("content://media/") == true })
        assertTrue(after.none { it.uri?.startsWith("content://media/") == true })
        assertEquals(2, after.count { it.fillImageUri != null })
        // The copied audio no longer carries the (now false) "pick the file again" note.
        assertFalse(r.warnings.any { it.contains("pick the file again") })
        assertFalse(r.warnings.any { it.contains("could not be copied") })
    }

    @Test
    fun failedCopyKeepsOriginalUriAndWarns() {
        val parsed = AlightXmlImporter.parse(stIntro, 1920f)
        val r = AlightMediaLocalizer.localize(parsed.snapshot, parsed.warnings, ownAuthority) { src ->
            if (src == audioSrc) null else "content://$ownAuthority/imports/ok.bin"
        }
        val after = clips(r.snapshot)
        val audio = after.single { it.type == LayerType.AUDIO }
        assertEquals(audioSrc, audio.uri)                       // link kept, import not failed
        assertEquals(listOf("Minimal Intro SOUND.mp3"), r.failedNames)
        assertEquals(2, r.copiedFiles)                          // both pictures still copied
        assertTrue(after.none { it.fillImageUri?.startsWith("content://media/") == true })
        // The failure is named, and the importer's own "pick the file again" note stays (still true).
        assertTrue(r.warnings.any { it.contains("could not be copied") && it.contains("Minimal Intro SOUND.mp3") })
        assertTrue(r.warnings.any { it.contains("pick the file again") })
    }

    @Test
    fun ownedLocalAndMissingUrisAreNotResolved() {
        fun clip(id: String, uri: String?, fill: String?) = TimelineClip(
            id = id, name = id, type = LayerType.SHAPE, startMs = 0, durationMs = 1000, color = 0,
            uri = uri, fillImageUri = fill
        )
        val snap = ProjectSnapshot(
            tracks = listOf(
                TimelineTrack(
                    id = "t", name = "T",
                    clips = listOf(
                        clip("own", "content://$ownAuthority/imports/already.mp3", null),
                        clip("file", "file:///data/local/x.png", null),
                        clip("am", null, "am:F15C58BAAC544334BFB9D0B3ABC9790C526200EF.png"),
                        clip("none", null, null)
                    )
                )
            )
        )
        val r = AlightMediaLocalizer.localize(snap, listOf("w"), ownAuthority) {
            throw AssertionError("resolver must not be called for owned / local / internal uris")
        }
        assertEquals(0, r.copiedFiles)
        assertTrue(r.failedNames.isEmpty())
        assertEquals(listOf("w"), r.warnings)
        assertEquals(snap, r.snapshot)
    }

    @Test
    fun sharedSourceIsResolvedOnceForAllLayers() {
        fun shape(id: String) = TimelineClip(
            id = id, name = id, type = LayerType.SHAPE, startMs = 0, durationMs = 1000, color = 0,
            fillImageUri = imgPill
        )
        val snap = ProjectSnapshot(
            tracks = listOf(TimelineTrack(id = "t", name = "T", clips = listOf(shape("a"), shape("b"))))
        )
        var calls = 0
        val r = AlightMediaLocalizer.localize(snap, emptyList(), ownAuthority) {
            calls++
            "content://$ownAuthority/imports/shared.png"
        }
        assertEquals(1, calls)
        assertEquals(1, r.copiedFiles)
        assertTrue(clips(r.snapshot).all { it.fillImageUri == "content://$ownAuthority/imports/shared.png" })
    }

    @Test
    fun stIntroFontWeightsFollowTheSpec() {
        val parsed = AlightXmlImporter.parse(stIntro, 1920f)
        val texts = clips(parsed.snapshot).filter { it.type == LayerType.TEXT }
        assertEquals(2, texts.size)
        // End card: googlefonts Roboto weight=700 in the XML -> stored bold.
        assertEquals(700, texts.single { it.startMs == 5166L }.fontWeight)
        // Pill: `imported` font (no weight in the spec) -> Alight's regular weight, 400. It used to
        // import as 600 and render faux-bold where Alight (font missing on the phone) shows regular.
        assertEquals(400, texts.single { it.startMs == 666L }.fontWeight)
    }
}
