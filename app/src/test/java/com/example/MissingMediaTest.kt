package com.example

import com.example.engine.AlightMediaLocalizer
import com.example.engine.AlightXmlImporter
import com.example.engine.MissingMedia
import com.example.model.LayerType
import com.example.model.ProjectSnapshot
import com.example.model.TimelineClip
import com.example.model.TimelineTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Media substitution (build 111, [MissingMedia]): the pure collection / re-pointing half, exercised
 * on a trimmed but verbatim excerpt of the real "ST Intro Template 1" export — the two media shapes
 * whose pictures lived on the exporting phone (`send-message.png` .../53288 in the pill,
 * `images.png` .../1000033464 in the "Logo Here" group) and the audio layer (.../53292). In the
 * harness no content:// file can be read, so the import-time copy fails for all three, exactly as
 * on Sarowar's phone for a collected preset.
 */
class MissingMediaTest {

    private val stIntro = """<?xml version='1.0' encoding='UTF-8' ?>
<scene title="ST Intro Template 1 (13 July) " width="1920" height="1080" bgcolor="#ffd8d8d8" totalTime="8032" fps="30"><shape id="633219" label="send-message.png" startTime="0" endTime="2899" fillType="media" fillImage="content://media/external/images/media/53288" mediaFillMode="stretch" s=".rect"><transform><location value="967.187012,534.500000,0.000000" /></transform><effect id="com.alightcreative.solidcolor" locallyApplied="true"><property name="color" type="color" value="#ffb891ff" /><property name="alpha" type="float" value="1.000000" /><property name="blendMode" type="int" value="0" /></effect><property name="size" type="vec2" value="540.000000,540.000000" /></shape><embedScene id="633205" label="Logo Here" startTime="4133" endTime="8032" fillType="intrinsic" outTime="-2147482782" mediaFillMode="stretch"><transform><location value="960.000000,540.000000,0.000000" /></transform><fillColor value="#ff000000" /><scene title="" width="1920" height="1080" exportWidth="1920" exportHeight="1080" precompose="dynamicResolution" bgcolor="#00000000" totalTime="3032" fps="30" modifiedTime="0" amver="1002592" ffver="106" am="com.alightcreative.motion/5.0.271.1002592" amplatform="android" retime="off" retimeAdaptFPS="false"><shape id="633203" label="images.png" startTime="0" endTime="3032" fillType="media" fillImage="content://media/external/images/media/1000033464" mediaFillMode="stretch" s=".rect"><transform><location value="960.000000,540.000000,0.000000" /></transform><property name="size" type="vec2" value="960.000000,503.500000" /></shape><shape id="633204" label="Mask" startTime="0" endTime="3032" fillType="color" blending="mask" mediaFillMode="stretch" s=".circle"><transform><location value="960.000000,540.000000,0.000000" /></transform><fillColor value="#ffd1c251" /><property name="size" type="vec2" value="100.000000,100.000000" /></shape></scene></embedScene><audio id="633212" label="Minimal Intro SOUND.mp3" startTime="0" endTime="599" src="content://media/external/audio/media/53292" inTime="3500" outTime="4067" mediaFillMode="stretch" /></scene>"""

    private val ownAuthority = "com.example.app.fileprovider"
    private val imgPill = "content://media/external/images/media/53288"
    private val imgLogo = "content://media/external/images/media/1000033464"
    private val audioSrc = "content://media/external/audio/media/53292"

    private fun clips(s: ProjectSnapshot) = s.tracks.flatMap { it.clips }

    @Test
    fun stIntroRefsThatFailToCopyAreCollectedForSubstitution() {
        val parsed = AlightXmlImporter.parse(stIntro, 1920f)
        // The harness cannot read any content:// file: every copy fails, links stay foreign.
        val loc = AlightMediaLocalizer.localize(parsed.snapshot, parsed.warnings, ownAuthority) { null }
        assertEquals(0, loc.copiedFiles)

        val items = MissingMedia.collect(loc.snapshot, ownAuthority)
        assertEquals(3, items.size)
        // One entry per file, labelled by the layer (AM names media layers after their file).
        // (Order follows the snapshot's track layout, not the XML document order.)
        val bySource = items.associateBy { it.sourceUri }
        assertEquals("send-message.png", bySource[imgPill]?.label)
        assertEquals("images.png", bySource[imgLogo]?.label)
        assertEquals("Minimal Intro SOUND.mp3", bySource[audioSrc]?.label)
        assertEquals(MissingMedia.Kind.IMAGE, bySource[imgPill]?.kind)
        assertEquals(MissingMedia.Kind.IMAGE, bySource[imgLogo]?.kind)
        assertEquals(MissingMedia.Kind.AUDIO, bySource[audioSrc]?.kind)
        assertEquals("Picture fill", bySource[imgPill]?.where)
        assertEquals("Picture fill", bySource[imgLogo]?.where)
        assertEquals("Layer file", bySource[audioSrc]?.where)
        assertTrue(items.all { it.layerCount == 1 })
    }

    @Test
    fun nothingIsMissingAfterASuccessfulCopy() {
        val parsed = AlightXmlImporter.parse(stIntro, 1920f)
        var n = 0
        val loc = AlightMediaLocalizer.localize(parsed.snapshot, parsed.warnings, ownAuthority) {
            "content://$ownAuthority/imports/copy_${++n}.bin"
        }
        assertEquals(3, loc.copiedFiles)
        assertTrue(MissingMedia.collect(loc.snapshot, ownAuthority).isEmpty())
    }

    @Test
    fun substitutingTheLogoRepointsItsLayerAndClearsTheItem() {
        val parsed = AlightXmlImporter.parse(stIntro, 1920f)
        val loc = AlightMediaLocalizer.localize(parsed.snapshot, parsed.warnings, ownAuthority) { null }
        val mine = "content://$ownAuthority/imports/my-logo.png"

        val after = MissingMedia.substitute(loc.snapshot, imgLogo, mine)
        val logo = clips(after).single { it.fillImageUri == mine }
        assertEquals("images.png", logo.name)
        // The other two missing files are untouched, and only they remain on the list.
        val remaining = MissingMedia.collect(after, ownAuthority)
        assertEquals(setOf(imgPill, audioSrc), remaining.map { it.sourceUri }.toSet())
    }

    @Test
    fun embeddedAndTokenRefsAreCollectedOwnedAndLocalRefsAreNot() {
        fun clip(id: String, type: LayerType, uri: String? = null, fillI: String? = null, fillV: String? = null) =
            TimelineClip(
                id = id, name = id, type = type, startMs = 0, durationMs = 1000, color = 0,
                uri = uri, fillImageUri = fillI, fillVideoUri = fillV
            )
        val snap = ProjectSnapshot(
            tracks = listOf(
                TimelineTrack(
                    id = "t", name = "T",
                    clips = listOf(
                        clip("vidFill", LayerType.SHAPE, fillV = "am:5A2183027F5DE730E692BBF35E5CB75D328D856A.mp4"),
                        clip("imgFill", LayerType.SHAPE, fillI = "am:F15C58BAAC544334BFB9D0B3ABC9790C526200EF.png"),
                        clip("token", LayerType.VIDEO, uri = "am-asset:0/video.mp4"),
                        clip("own", LayerType.VIDEO, uri = "content://$ownAuthority/imports/already.mp4"),
                        clip("file", LayerType.SHAPE, fillI = "file:///data/local/x.png"),
                        clip("foreignImgLayer", LayerType.VIDEO, uri = imgPill).copy(isImage = true)
                    )
                )
            )
        )
        val items = MissingMedia.collect(snap, ownAuthority)
        assertEquals(listOf("vidFill", "imgFill", "token", "foreignImgLayer"), items.map { it.label })
        assertEquals(
            listOf(MissingMedia.Kind.VIDEO, MissingMedia.Kind.IMAGE, MissingMedia.Kind.VIDEO, MissingMedia.Kind.IMAGE),
            items.map { it.kind }
        )
        assertEquals(listOf("Video fill", "Picture fill", "Layer file", "Layer file"), items.map { it.where })
    }

    @Test
    fun aSharedFileIsOneItemNamingEveryLayer() {
        fun shape(id: String) = TimelineClip(
            id = id, name = id, type = LayerType.SHAPE, startMs = 0, durationMs = 1000, color = 0,
            fillImageUri = imgPill
        )
        val video = TimelineClip(
            id = "v", name = "v", type = LayerType.SHAPE, startMs = 0, durationMs = 1000, color = 0,
            fillVideoUri = imgPill
        )
        val snap = ProjectSnapshot(
            tracks = listOf(TimelineTrack(id = "t", name = "T", clips = listOf(shape("a"), shape("b"), video)))
        )
        val items = MissingMedia.collect(snap, ownAuthority)
        assertEquals(1, items.size)
        assertEquals(listOf("a", "b", "v"), items[0].clipIds)
        assertEquals(3, items[0].layerCount)

        // One substitution re-points all three slots at once.
        val mine = "content://$ownAuthority/imports/shared.png"
        val after = MissingMedia.substitute(snap, imgPill, mine)
        assertTrue(clips(after).all { it.fillImageUri == mine || it.fillVideoUri == mine })
        assertTrue(MissingMedia.collect(after, ownAuthority).isEmpty())
    }

    @Test
    fun substituteOfAnUnknownSourceChangesNothing() {
        val parsed = AlightXmlImporter.parse(stIntro, 1920f)
        val after = MissingMedia.substitute(parsed.snapshot, "content://media/external/images/media/999", "content://x/y.png")
        assertSame(parsed.snapshot, after)
        assertSame(parsed.snapshot, MissingMedia.substitute(parsed.snapshot, imgPill, imgPill))
    }
}
