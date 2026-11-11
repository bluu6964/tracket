package com.example

import com.example.engine.AlightMediaLocalizer
import com.example.engine.AlightXmlImporter
import com.example.engine.fillSourceMs
import com.example.engine.fillVideoPose
import com.example.engine.isVideoFilledShape
import com.example.engine.mediaFillClipOf
import com.example.engine.silhouetteCopy
import com.example.engine.strokeOnlyCopy
import com.example.model.FILL_IMAGE
import com.example.model.FILL_NONE
import com.example.model.FILL_SOLID
import com.example.model.FILL_VIDEO
import com.example.model.IMG_FILL
import com.example.model.IMG_FIT
import com.example.model.IMG_STRETCH
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.depthX
import com.example.model.depthY
import com.example.model.shapeDrawScaleX
import com.example.model.shapeDrawScaleY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

/**
 * Video fills inside shapes (build 104): an Alight shape filled with a playing video
 * (`fillType="media"` + `fillVideo`) used to import as an IMAGE fill with no picture — the fill
 * rendered empty everywhere. These tests pin, on verbatim excerpts of the real corpus files:
 * the importer's fill kind + media ref + the fill's own inTime/outTime window, the localizer's
 * copy of the fill file, the routing decision the render engines make (live composite vs poster
 * raster), the silhouette / outline split, the fill's source-time mapping, and the geometry that
 * maps the video frame into the shape's box (cover / fit / stretch, flips, rotation, offset).
 */
class FillVideoFillTest {

    private fun scene(layers: String, w: Int = 1080, h: Int = 1920, total: Long = 2000) =
        """<?xml version='1.0' encoding='UTF-8' ?>
<scene title="T" width="$w" height="$h" bgcolor="#ff102030" totalTime="$total" fps="30">
$layers
</scene>"""

    // Verbatim from collected/edit-freefire-mental.xml: a full-frame freehand quad filled with a
    // video (no mediaFillMode, AM's sentinel outTime), carrying a hidden effect plus visible
    // exposure / tile effects.
    private val freefireQuad = """<shape id="291413" label="Kasih Nama Woi 2_720p.mp4" startTime="0" endTime="6499" fillType="media" fillVideo="content://media/external/video/media/813" outTime="2147476647">
    <transform>
      <location value="540.000000,960.000000" />
    </transform>
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

    // Verbatim from collected/zooms-toji-pack.xml: a built-in .rect filled with a video,
    // mediaFillMode="fill", turned 90 degrees.
    private val zoomsRect = """<shape id="10232853" label="New Project 41 [AAB82B6].mp4" startTime="0" endTime="1049" fillType="media" fillVideo="content://media/external/video/media/20504" outTime="1049" mediaFillMode="fill" s=".rect">
            <transform>
              <location value="967.296875,770.786133,0.000000" />
              <rotation value="90.000000" />
            </transform>
            <property name="size" type="vec2" value="540.000000,432.000000" />
          </shape>"""

    // Verbatim from collected/edit-winkoko-preset.xml: a built-in .rect filled with a video,
    // blended "screen", with the fill's own window inTime=1768 .. outTime=2634 (exactly the
    // layer's 866 ms length).
    private val winkokoRect = """<shape id="2999474083" label="ri.films.mp4" startTime="11933" endTime="12799" fillType="media" blending="screen" fillVideo="content://media/external/video/media/94859" inTime="1768" outTime="2634" mediaFillMode="fill" s=".rect">
    <transform>
      <location value="540.000000,540.000000,0.000000" />
    </transform>
    <gain value="0.000000" />
    <property name="size" type="vec2" value="540.000000,960.000000" />
  </shape>"""

    // Verbatim from the ST Intro template: a still picture fill (must stay FILL_IMAGE).
    private val stIntroImage = """<shape id="633203" label="images.png" startTime="0" endTime="3032" fillType="media" fillImage="content://media/external/images/media/1000033464" mediaFillMode="stretch" s=".rect"><transform><location value="960.000000,540.000000,0.000000" /></transform><property name="size" type="vec2" value="960.000000,503.500000" /></shape>"""

    private fun onlyClip(xml: String, w: Int = 1080, h: Int = 1920, total: Long = 2000): TimelineClip {
        val r = AlightXmlImporter.parse(scene(xml, w, h, total), 1080f)
        return r.snapshot.tracks.flatMap { it.clips }.single { it.type == LayerType.SHAPE }
    }

    @Test
    fun freefirePathQuadImportsAsVideoFill() {
        val r = AlightXmlImporter.parse(scene(freefireQuad, total = 6500), 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }.single { it.type == LayerType.SHAPE }
        assertEquals("PATH", c.shapePattern)
        assertEquals(FILL_VIDEO, c.fillMode)
        assertEquals("content://media/external/video/media/813", c.fillVideoUri)
        assertNull(c.fillImageUri)
        // No mediaFillMode on the element: the fill is the default cover mode.
        assertEquals(IMG_FILL, c.fillImageMode)
        // The video IS in the XML now: the old "its picture is not in the XML" warning is gone.
        assertFalse(r.warnings.any { it.contains("its picture is not in the XML") })
        assertTrue(c.isVideoFilledShape())
    }

    @Test
    fun zoomsRectImportsAsVideoFill() {
        val c = onlyClip(zoomsRect, w = 1920, h = 1080)
        assertEquals(FILL_VIDEO, c.fillMode)
        assertEquals("content://media/external/video/media/20504", c.fillVideoUri)
        assertEquals(IMG_FILL, c.fillImageMode)   // mediaFillMode="fill"
        assertEquals(90f, c.rotationDeg, 0.001f)
        assertTrue(c.isVideoFilledShape())
    }

    @Test
    fun winkokoRectKeepsItsFillWindow() {
        val c = onlyClip(winkokoRect, w = 1080, h = 1080, total = 12800)
        assertEquals(FILL_VIDEO, c.fillMode)
        assertEquals("content://media/external/video/media/94859", c.fillVideoUri)
        // The fill's own window in its file rides on the clip's media trim fields.
        assertEquals(1768L, c.trimStartMs)
        assertEquals(2634L, c.sourceDurationMs)
        assertEquals(1f, c.speed, 0.0001f)
    }

    @Test
    fun stillPictureFillStaysAnImageFill() {
        val c = onlyClip(stIntroImage, w = 1920, h = 1080, total = 3032)
        assertEquals(FILL_IMAGE, c.fillMode)
        assertEquals("content://media/external/images/media/1000033464", c.fillImageUri)
        assertEquals(IMG_STRETCH, c.fillImageMode)
        assertNull(c.fillVideoUri)
        assertFalse(c.isVideoFilledShape())
    }

    @Test
    fun localizerCopiesTheFillVideo() {
        val parsed = AlightXmlImporter.parse(scene(winkokoRect, w = 1080, h = 1080, total = 12800), 1080f)
        val own = "com.example.app.fileprovider"
        val r = AlightMediaLocalizer.localize(parsed.snapshot, parsed.warnings, own) { src ->
            if (src == "content://media/external/video/media/94859") "content://$own/imports/fill.mp4" else null
        }
        val c = r.snapshot.tracks.flatMap { it.clips }.single { it.type == LayerType.SHAPE }
        assertEquals("content://$own/imports/fill.mp4", c.fillVideoUri)
        assertEquals(1, r.copiedFiles)
        assertTrue(r.failedNames.isEmpty())
    }

    @Test
    fun localizerFailureKeepsTheFillUriAndNamesTheLayer() {
        val parsed = AlightXmlImporter.parse(scene(winkokoRect, w = 1080, h = 1080, total = 12800), 1080f)
        val r = AlightMediaLocalizer.localize(parsed.snapshot, parsed.warnings, "com.example.app.fileprovider") { null }
        val c = r.snapshot.tracks.flatMap { it.clips }.single { it.type == LayerType.SHAPE }
        assertEquals("content://media/external/video/media/94859", c.fillVideoUri)
        assertEquals(listOf("ri.films.mp4"), r.failedNames)
    }

    // ---- routing + the silhouette / outline split ------------------------------------------------

    private fun shape(fillMode: Int, uri: String? = "content://v/1") = TimelineClip(
        id = "s1", name = "s", type = LayerType.SHAPE, startMs = 0L, durationMs = 1000L, color = 0L,
        fillMode = fillMode, fillVideoUri = if (fillMode == FILL_VIDEO) uri else null,
        fillImageUri = if (fillMode == FILL_IMAGE) uri else null,
        shapePattern = "RECT", strokeWidth = 3f, textColor = 0xFF112233L
    )

    @Test
    fun routingSendsOnlySingleVideoFilledShapesToTheLivePath() {
        val v = shape(FILL_VIDEO)
        assertEquals(v, mediaFillClipOf(listOf(v)))
        assertNull(mediaFillClipOf(listOf(shape(FILL_IMAGE))))
        assertNull(mediaFillClipOf(listOf(v, shape(FILL_SOLID))))
        assertNull(mediaFillClipOf(listOf(shape(FILL_VIDEO, uri = null))))
        assertNull(mediaFillClipOf(emptyList()))
    }

    @Test
    fun silhouetteAndOutlineCopiesSplitTheShape() {
        val v = shape(FILL_VIDEO)
        val sil = v.silhouetteCopy()
        assertEquals(FILL_SOLID, sil.fillMode)
        assertEquals(0xFFFFFFFFL, sil.textColor)
        assertEquals(0f, sil.strokeWidth, 0f)
        assertEquals(v.fillVideoUri, sil.fillVideoUri)
        assertEquals(v.shapePattern, sil.shapePattern)
        val out = v.strokeOnlyCopy()
        assertEquals(FILL_NONE, out.fillMode)
        assertEquals(3f, out.strokeWidth, 0f)          // the outline keeps its stroke
        assertEquals(v.textColor, out.textColor)
    }

    // ---- source time ------------------------------------------------------------------------------

    @Test
    fun fillSourceTimeFollowsTheWindowAndClampsToTheFile() {
        val c = shape(FILL_VIDEO).copy(startMs = 1000L, trimStartMs = 1768L)
        assertEquals(1768L, fillSourceMs(c, 1000L, 0L))            // layer start = window start
        assertEquals(2634L, fillSourceMs(c, 1866L, 0L))            // advances 1:1 with the timeline
        assertEquals(4999L, fillSourceMs(c, 100000L, 5000L))       // clamped into the file
        assertEquals(0L, fillSourceMs(c.copy(trimStartMs = 0L), 0L, 5000L))
        val stretched = c.copy(speed = 2f)
        assertEquals(2768L, fillSourceMs(stretched, 1500L, 0L))    // a stretch fill runs at its speed
    }

    // ---- placement geometry -------------------------------------------------------------------------

    /** A shape whose box is 260 x 130 px on a 1920 x 1080 frame (density 1, k 1, scale 200% x 100%). */
    private fun wideShape(mode: Int) = TimelineClip(
        id = "g", name = "g", type = LayerType.SHAPE, startMs = 0L, durationMs = 1000L, color = 0L,
        fillMode = FILL_VIDEO, fillVideoUri = "content://v/1", fillImageMode = mode,
        shapePattern = "RECT", scalePercent = 200f, shapeScaleX = 1f, shapeScaleY = 0.5f
    )

    private fun drawnSize(clip: TimelineClip, frameW: Int, frameH: Int, cw: Int, ch: Int): Pair<Float, Float> {
        val pose = fillVideoPose(clip, frameW, frameH, 1f, 1f, cw, ch)!!
        val fit = min(frameW.toFloat() / cw, frameH.toFloat() / ch)
        return (cw * fit * pose.scaleX) to (ch * fit * pose.scaleY)
    }

    @Test
    fun coverFillCropsTheOverflow() {
        val c = wideShape(IMG_FILL)
        // The shape's own box, from the model's draw scales (the painters' box).
        assertEquals(260f, 2f * 65f * c.shapeDrawScaleX, 0.01f)
        assertEquals(130f, 2f * 65f * c.shapeDrawScaleY, 0.01f)
        val (dw, dh) = drawnSize(c, 1920, 1080, 640, 360)
        assertEquals(260f, dw, 0.01f)          // the box width is covered exactly
        assertTrue(dh > 130f)                  // the height overflows and is centre-cropped
        assertEquals(146.25f, dh, 0.01f)
    }

    @Test
    fun fitFillLetterboxesInsideTheBox() {
        val (dw, dh) = drawnSize(wideShape(IMG_FIT), 1920, 1080, 640, 360)
        assertEquals(130f, dh, 0.01f)          // the box height is met exactly
        assertTrue(dw < 260f)                  // the width leaves bars at the sides
        assertEquals(231.11f, dw, 0.01f)
    }

    @Test
    fun stretchFillDistortsToTheBoxExactly() {
        val (dw, dh) = drawnSize(wideShape(IMG_STRETCH), 1920, 1080, 640, 360)
        assertEquals(260f, dw, 0.01f)
        assertEquals(130f, dh, 0.01f)
    }

    @Test
    fun poseCarriesOffsetRotationAndFlips() {
        val c = wideShape(IMG_FILL).copy(transformX = 40f, transformY = -20f, rotationDeg = 30f, isFlippedH = true)
        val pose = fillVideoPose(c, 1920, 1080, 2f, 1f, 640, 360)!!
        assertEquals(c.depthX * 2f, pose.offsetX, 0.01f)
        assertEquals(c.depthY * 2f, pose.offsetY, 0.01f)
        assertEquals(30f, pose.rotationDeg, 0.001f)
        assertTrue(pose.scaleX < 0f)           // the flip folds into the placement sign
        assertTrue(pose.scaleY > 0f)
        // No frame size yet = no pose (the caller falls back to the poster).
        assertNull(fillVideoPose(c, 1920, 1080, 1f, 1f, 0, 360))
    }
}
