package com.example

import com.example.engine.AlightXmlExporter
import com.example.engine.AlightXmlImporter
import com.example.engine.AmFxEval
import com.example.engine.ParentRig
import com.example.model.AnimProp
import com.example.model.AspectRatio
import com.example.model.ClipFx
import com.example.model.ClipFxLibrary
import com.example.model.Easing
import com.example.model.ExportSettings
import com.example.model.FILL_VIDEO
import com.example.model.Keyframe
import com.example.model.LayerType
import com.example.model.ParentLink
import com.example.model.ProjectSnapshot
import com.example.model.TimelineClip
import com.example.model.TimelineTrack
import com.example.model.renderAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

/**
 * Build 197: the Alight XML exporter's round trip — synthetic projects are exported by
 * [AlightXmlExporter] and re-imported through the REAL [AlightXmlImporter] at the same preview
 * width (1080, against a 1080x1920 scene, so stored units == scene px and positions are
 * centre-relative), and the re-imported structure is compared against the original: layer
 * count / types / order, timing, keyframe times and values within the stated tolerances
 * (floats ride a 6-decimal text form: positions ±1 px, scales ±0.6 %, opacity ±1, keyframe
 * times ±3 ms), shape/text essentials, and the exported-effects subset with its omitted count.
 */
class AlightXmlExportTest {

    private val pw = 1080f

    private fun snap(
        topFirst: List<TimelineClip>,
        canvas: Long = 0xFF102030L,
        fps: Int = 30
    ): ProjectSnapshot = ProjectSnapshot(
        tracks = topFirst.mapIndexed { i, c ->
            TimelineTrack(id = "lane$i", name = "Layer", clips = listOf(c))
        },
        aspectRatio = AspectRatio("1080:1920", 1080f / 1920f, "Imported"),
        canvasColor = canvas,
        exportSettings = ExportSettings(frameRate = fps)
    )

    private fun roundTrip(s: ProjectSnapshot): Pair<AlightXmlExporter.Result, AlightXmlImporter.ImportResult> {
        val out = AlightXmlExporter.export("RT", s, pw)
        assertNotNull("export of a non-empty project", out)
        val back = AlightXmlImporter.parse(out!!.xml, pw)
        return out to back
    }

    private fun clipsOf(r: AlightXmlImporter.ImportResult): List<TimelineClip> =
        r.snapshot.tracks.flatMap { it.clips }

    private fun clip(
        name: String, type: LayerType, start: Long = 0L, dur: Long = 3000L,
        init: TimelineClip.() -> TimelineClip = { this }
    ): TimelineClip = TimelineClip(
        id = "id-$name", name = name, type = type, startMs = start, durationMs = dur,
        color = 0xFF000000L
    ).init()

    // ---- scene + structure ----------------------------------------------------------------------

    @Test
    fun sceneHeaderAndWellFormed() {
        val s = snap(listOf(
            clip("S", LayerType.SHAPE) { copy(shapePattern = "RECT") },
            clip("A", LayerType.AUDIO) {
                copy(uri = "content://media/audio/9", mimeType = "audio/mpeg", sourceDurationMs = 3000)
            }
        ))
        val (out, back) = roundTrip(s)
        assertTrue(out.xml.startsWith("<?xml version='1.0' encoding='UTF-8'?>"))
        assertTrue(out.xml.contains("<scene title=\"RT\" width=\"1080\" height=\"1920\""))
        assertTrue(out.xml.contains("bgcolor=\"#ff102030\""))
        assertTrue(out.xml.contains("totalTime=\"3000\""))
        assertTrue(out.xml.contains("fps=\"30\""))
        assertTrue(out.xml.contains("<media uri=\"content://media/audio/9\""))
        assertTrue(out.xml.contains("type=\"audio/mpeg\""))
        assertEquals(0xFF102030L, back.snapshot.canvasColor)
        // The DOM parses it (the importer's own factory settings) and the root is a scene.
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(out.xml.byteInputStream())
        assertEquals("scene", doc.documentElement.tagName)
    }

    @Test
    fun emptyProjectExportsNothing() {
        assertNull(AlightXmlExporter.export("E", ProjectSnapshot(), pw))
    }

    @Test
    fun emittedStructureMatchesCorpusVocabulary() {
        val s = snap(listOf(
            clip("T", LayerType.TEXT) { copy(text = "Hi", scalePercent = 150f) },
            clip("S", LayerType.SHAPE) {
                copy(
                    shapePattern = "RECT",
                    effects = listOf(ClipFx(type = ClipFxLibrary.EXPOSURE, values = listOf(0.5f, 0f, 100f, 0f)))
                )
            },
            clip("N", LayerType.NULL) { copy() }
        ))
        val out = AlightXmlExporter.export("RT", s, pw)!!
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(out.xml.byteInputStream())
        val layerTags = setOf("shape", "text", "audio", "nullobj", "embedScene", "camera")
        val sceneAttrs = setOf(
            "title", "width", "height", "exportWidth", "exportHeight", "bgcolor", "totalTime",
            "fps", "precompose", "modifiedTime", "amver", "ffver", "am", "amplatform",
            "retime", "retimeAdaptFPS"
        )
        val root = doc.documentElement
        val attrs = root.attributes
        for (i in 0 until attrs.length) {
            assertTrue("scene attr " + attrs.item(i).nodeName + " is a corpus attr",
                attrs.item(i).nodeName in sceneAttrs)
        }
        val all = doc.getElementsByTagName("*")
        var sawLayer = false
        for (i in 0 until all.length) {
            val el = all.item(i) as org.w3c.dom.Element
            if (el.tagName in layerTags) sawLayer = true
            if (el.tagName == "kf") {
                val t = el.getAttribute("t").toFloat()
                assertTrue("kf t in [0,1]", t >= 0f && t <= 1f)
            }
            if (el.tagName == "effect") {
                assertTrue(el.getAttribute("id").startsWith("com.alightcreative.effects."))
            }
            if (el.tagName == "transform") {
                val kids = el.childNodes
                for (j in 0 until kids.length) {
                    val kid = kids.item(j)
                    if (kid is org.w3c.dom.Element) {
                        assertTrue("transform child " + kid.tagName,
                            kid.tagName in setOf("location", "scale", "rotation", "opacity", "pivot"))
                    }
                }
            }
        }
        assertTrue(sawLayer)
    }

    // ---- shapes -----------------------------------------------------------------------------------

    @Test
    fun shapeRoundTrip() {
        val ease = Easing(0.49f, 0f, 0.22f, 1f)
        val shape = clip("Box", LayerType.SHAPE, start = 200, dur = 3000) {
            copy(
                shapePattern = "ROUNDED_RECT",
                textColor = 0xFF336699L,
                shapeScaleX = 1.5f, shapeScaleY = 1.2f, scalePercent = 150f,
                shapeCornerRadius = 32.5f,
                strokeWidth = 13f, strokeColor = 0xFFFF0000L,
                transformX = 100f, transformY = -50f, rotationDeg = 15f, opacity = 80f,
                keyframes = mapOf(
                    AnimProp.POSITION to listOf(
                        Keyframe(0, 100f, -50f, ease),
                        Keyframe(1500, 200f, 0f)
                    ),
                    AnimProp.OPACITY to listOf(
                        Keyframe(0, 80f, 0f),
                        Keyframe(3000, 40f, 0f)
                    )
                )
            )
        }
        val (_, back) = roundTrip(snap(listOf(shape)))
        val c = clipsOf(back).single()
        assertEquals(LayerType.SHAPE, c.type)
        assertEquals("ROUNDED_RECT", c.shapePattern)
        assertEquals(200L, c.startMs)
        assertEquals(3000L, c.durationMs)
        assertEquals(0xFF336699L, c.textColor)
        assertEquals(1.5f, c.shapeScaleX, 0.01f)
        assertEquals(1.2f, c.shapeScaleY, 0.01f)
        assertEquals(32.5f, c.shapeCornerRadius, 0.6f)
        assertEquals(13f, c.strokeWidth, 0.4f)
        assertEquals(0xFFFF0000L, c.strokeColor)
        assertEquals(15f, c.rotationDeg, 0.1f)
        assertEquals(80f, c.opacity, 1f)
        val pos = c.keyframes[AnimProp.POSITION]!!
        assertEquals(2, pos.size)
        assertEquals(0L, pos[0].timeMs)
        assertEquals(1500L, pos[1].timeMs)
        assertEquals(100f, pos[0].a, 1f)
        assertEquals(-50f, pos[0].b, 1f)
        assertEquals(200f, pos[1].a, 1f)
        assertEquals(0.49f, pos[0].easing.x1, 0.01f)
        assertEquals(0.22f, pos[0].easing.x2, 0.01f)
        val opa = c.keyframes[AnimProp.OPACITY]!!
        assertEquals(40f, opa.last().a, 1f)
    }

    @Test
    fun pathShapeRoundTrip() {
        val segs = listOf(
            com.example.model.PathSeg(com.example.model.PATH_OP_MOVE, listOf(-1f, -1f)),
            com.example.model.PathSeg(com.example.model.PATH_OP_LINE, listOf(1f, -1f)),
            com.example.model.PathSeg(com.example.model.PATH_OP_LINE, listOf(1f, 1f)),
            com.example.model.PathSeg(com.example.model.PATH_OP_LINE, listOf(-1f, 1f)),
            com.example.model.PathSeg(com.example.model.PATH_OP_CLOSE, emptyList())
        )
        val shape = clip("Quad", LayerType.SHAPE) {
            copy(shapePattern = "PATH", pathSegments = segs, shapeScaleX = 2f, shapeScaleY = 2f,
                scalePercent = 200f, textColor = 0xFF00FF00L)
        }
        val (_, back) = roundTrip(snap(listOf(shape)))
        val c = clipsOf(back).single()
        assertEquals("PATH", c.shapePattern)
        assertEquals(2f, c.shapeScaleX, 0.02f)
        assertEquals(5, c.pathSegments.size)
        assertEquals(-1f, c.pathSegments[0].pts[0], 0.01f)
        assertEquals(1f, c.pathSegments[2].pts[0], 0.01f)
    }

    // ---- text -------------------------------------------------------------------------------------

    @Test
    fun textRoundTrip() {
        val text = clip("Title", LayerType.TEXT, start = 100, dur = 2000) {
            copy(
                text = "A & B <Test>", fontFamily = "Roboto", fontWeight = 700,
                scalePercent = 200f, textColor = 0xFFFFFF00L, textAlign = 0,
                letterSpacing = 10f, transformX = -80f, transformY = 120f
            )
        }
        val (_, back) = roundTrip(snap(listOf(text)))
        val c = clipsOf(back).single()
        assertEquals(LayerType.TEXT, c.type)
        assertEquals("A & B <Test>", c.text)
        assertEquals(700, c.fontWeight)
        assertEquals(200f, c.scalePercent, 2f)
        assertEquals(0xFFFFFF00L, c.textColor)
        assertEquals(0, c.textAlign)
        assertEquals(10f, c.letterSpacing, 0.6f)
        assertEquals(-80f, c.transformX, 1f)
        assertEquals(120f, c.transformY, 1f)
        assertEquals(100L, c.startMs)
    }

    // ---- audio ------------------------------------------------------------------------------------

    @Test
    fun audioRoundTrip() {
        val audio = clip("Song", LayerType.AUDIO, start = 0, dur = 4000) {
            copy(
                uri = "content://media/audio/42", mimeType = "audio/mpeg",
                trimStartMs = 500, sourceDurationMs = 10500, speed = 2f, volume = 0.5f
            )
        }
        val (_, back) = roundTrip(snap(listOf(audio)))
        val c = clipsOf(back).single()
        assertEquals(LayerType.AUDIO, c.type)
        assertEquals("content://media/audio/42", c.uri)
        assertEquals(500L, c.trimStartMs)
        assertEquals(2f, c.speed, 0.01f)
        assertEquals(0.5f, c.volume, 0.01f)
        assertEquals(4000L, c.durationMs)
    }

    // ---- parenting ----------------------------------------------------------------------------------

    @Test
    fun importedFormParentRoundTrip() {
        val nullClip = clip("Anchor", LayerType.NULL) {
            copy(transformX = 100f, transformY = 50f, scalePercent = 200f)
        }
        val kid = clip("Kid", LayerType.SHAPE) {
            copy(
                shapePattern = "RECT", transformX = 10f, transformY = 20f,
                parent = ParentLink(nullClip.id, -540f, -960f, 100f, 0f)
            )
        }
        val (_, back) = roundTrip(snap(listOf(kid, nullClip)))
        val clips = clipsOf(back)
        val kid2 = clips.first { it.name == "Kid" }
        val null2 = clips.first { it.name == "Anchor" }
        assertNotNull(kid2.parent)
        assertEquals(null2.id, kid2.parent!!.id)
        assertEquals(10f, kid2.transformX, 1f)
        assertEquals(20f, kid2.transformY, 1f)
        assertEquals(100f, null2.transformX, 1f)
        assertEquals(200f, null2.scalePercent, 1f)
    }

    @Test
    fun userFormParentRoundTripKeepsWorldPose() {
        val nullClip = clip("Anchor", LayerType.NULL) {
            copy(transformX = 100f, transformY = 50f, scalePercent = 200f)
        }
        val kid = clip("Kid", LayerType.SHAPE) {
            copy(
                shapePattern = "RECT", transformX = 10f, transformY = 20f,
                scalePercent = 50f, rotationDeg = 30f,
                parent = ParentLink(nullClip.id, 100f, 50f, 200f, 0f)
            )
        }
        val before = listOf(kid, nullClip)
        val worldBefore = ParentRig(before) { c, t -> c.renderAt(t) }.adjusted(kid, 0)
        val (_, back) = roundTrip(snap(listOf(kid, nullClip)))
        val clips = clipsOf(back)
        val kid2 = clips.first { it.name == "Kid" }
        assertNotNull(kid2.parent)
        val worldAfter = ParentRig(clips) { c, t -> c.renderAt(t) }.adjusted(kid2, 0)
        assertEquals(worldBefore.transformX, worldAfter.transformX, 1f)
        assertEquals(worldBefore.transformY, worldAfter.transformY, 1f)
        assertEquals(worldBefore.scalePercent, worldAfter.scalePercent, 1f)
        assertEquals(worldBefore.rotationDeg, worldAfter.rotationDeg, 0.5f)
        // And the exported local values are the similarity-corrected ones.
        assertEquals(-585f, kid2.transformX, 1f)
        assertEquals(-975f, kid2.transformY, 1f)
        assertEquals(25f, kid2.scalePercent, 1f)
    }

    // ---- groups -------------------------------------------------------------------------------------

    @Test
    fun groupRoundTrip() {
        val groupId = "id-Group"
        val group = clip("Group", LayerType.GROUP, start = 1000, dur = 4000) {
            copy(id = groupId, transformX = 0f, transformY = 0f)
        }
        val kidShape = clip("Inner", LayerType.SHAPE, start = 1500, dur = 2000) {
            copy(
                shapePattern = "RECT", textColor = 0xFF123456L, groupId = groupId,
                transformX = 30f, transformY = -40f,
                parent = ParentLink(groupId, 0f, 0f, 100f, 0f)
            )
        }
        val kidText = clip("InnerText", LayerType.TEXT, start = 1000, dur = 4000) {
            copy(text = "Inside", groupId = groupId, scalePercent = 100f,
                parent = ParentLink(groupId, 0f, 0f, 100f, 0f))
        }
        val (_, back) = roundTrip(snap(listOf(kidText, kidShape, group)))
        val clips = clipsOf(back)
        val g2 = clips.first { it.type == LayerType.GROUP }
        assertEquals(1000L, g2.startMs)
        assertEquals(4000L, g2.durationMs)
        val kids = clips.filter { it.groupId == g2.id }
        assertEquals(2, kids.size)
        val s2 = kids.first { it.name == "Inner" }
        assertEquals(1500L, s2.startMs)
        assertEquals(0xFF123456L, s2.textColor)
        assertEquals(30f, s2.transformX, 1f)
        assertEquals(-40f, s2.transformY, 1f)
        val t2 = kids.first { it.name == "InnerText" }
        assertEquals("Inside", t2.text)
    }

    // ---- media layers ---------------------------------------------------------------------------------

    @Test
    fun mediaLayerRoundTrip() {
        val video = clip("Clip", LayerType.VIDEO, start = 0, dur = 5000) {
            copy(
                uri = "content://media/video/7", mimeType = "video/mp4", isImage = false,
                sourceAspect = 16f / 9f, trimStartMs = 1000, sourceDurationMs = 11000,
                scalePercent = 100f
            )
        }
        val (_, back) = roundTrip(snap(listOf(video)))
        val c = clipsOf(back).single()
        assertEquals(LayerType.SHAPE, c.type)
        assertEquals(FILL_VIDEO, c.fillMode)
        assertEquals("content://media/video/7", c.fillVideoUri)
        assertEquals(1000L, c.trimStartMs)
        // Content box = the frame-wide fit of a 16:9 picture: 1080 px wide against the
        // 390 px shape box (1080 * 130 / 360).
        assertEquals(1080f / 390f, c.shapeScaleX, 0.03f)
        assertEquals(5000L, c.durationMs)
    }

    // ---- masks + blend ----------------------------------------------------------------------------------

    @Test
    fun maskAndBlendRoundTrip() {
        val target = clip("Target", LayerType.SHAPE) {
            copy(shapePattern = "RECT", blendMode = "Screen")
        }
        val mask = clip("Mask", LayerType.SHAPE) {
            copy(shapePattern = "CIRCLE", isMask = true, isVisible = false, maskTargetId = target.id)
        }
        // Stack: the mask sits directly above its target.
        val (_, back) = roundTrip(snap(listOf(mask, target)))
        val clips = clipsOf(back)
        val t2 = clips.first { it.name == "Target" }
        val m2 = clips.first { it.name == "Mask" }
        assertEquals("Screen", t2.blendMode)
        assertTrue(m2.isMask)
        assertEquals(t2.id, m2.maskTargetId)
    }

    // ---- effects ----------------------------------------------------------------------------------------

    @Test
    fun effectsRoundTripAndOmissionCount() {
        val exposureTail = AmFxEval.encodeTrack(
            listOf(
                Triple(0f, -0.5f, null),
                Triple(1f, 1f, Easing(0.42f, 0f, 0.58f, 1f))
            )
        )
        val exposureKeyframed = ClipFx(
            type = ClipFxLibrary.EXPOSURE,
            values = listOf(-0.5f, 0f, 100f, 0f, 0f, 0f, 0f) + exposureTail + listOf(0f, 0f)
        )
        val shape = clip("Fx", LayerType.SHAPE) {
            copy(
                shapePattern = "RECT",
                effects = listOf(
                    ClipFx(type = ClipFxLibrary.EXPOSURE, values = listOf(0.5f, 20f, 150f, 0f)),
                    exposureKeyframed,
                    ClipFx(type = ClipFxLibrary.BRIGHT_CONTRAST, values = listOf(10f, -20f)),
                    ClipFx(type = ClipFxLibrary.RGB_SPLIT, values = listOf(50f, 90f, 0f)),
                    ClipFx(type = ClipFxLibrary.VIGNETTE, values = listOf(60f, 80f, 40f)),
                    ClipFx(type = ClipFxLibrary.INVERT, values = listOf(100f, 0f)),
                    ClipFx(type = ClipFxLibrary.TILE, values = listOf(3f, 3f, 1f, 25f, 25f, 0f, 0f)),
                    ClipFx(type = ClipFxLibrary.GAUSSIAN_BLUR_AM, values = listOf(1.5f)),
                    ClipFx(type = ClipFxLibrary.HUE_SAT, values = listOf(30f, -40f, 0f, 10f)),
                    ClipFx(type = ClipFxLibrary.GLOW, values = listOf(50f))
                )
            )
        }
        val (out, back) = roundTrip(snap(listOf(shape)))
        assertEquals(1, out.report.omittedEffectCount)
        assertEquals(1, out.report.omittedEffects[ClipFxLibrary.GLOW])
        val c = clipsOf(back).single()
        fun fx(type: String) = c.effects.filter { it.type == type }
        val exp = fx(ClipFxLibrary.EXPOSURE)
        assertEquals(2, exp.size)
        assertEquals(0.5f, exp[0].values[0], 0.01f)
        assertEquals(20f, exp[0].values[1], 0.5f)
        assertEquals(150f, exp[0].values[2], 1f)
        // The keyframed exposure resolves through its tail: -0.5 at 0, the symmetric
        // bezier's midpoint 0.25 at half the layer, 1 at the end.
        val mid = AmFxEval.trackValueAt(exp[1].values, 0.5f, AmFxEval.TAIL_START)
        assertNotNull(mid)
        assertEquals(0.25f, mid!!, 0.05f)
        assertEquals(-0.5f, AmFxEval.trackValueAt(exp[1].values, 0f, AmFxEval.TAIL_START)!!, 0.01f)
        val bc = fx(ClipFxLibrary.BRIGHT_CONTRAST).single()
        assertEquals(10f, bc.values[0], 0.5f)
        assertEquals(-20f, bc.values[1], 0.5f)
        val rgb = fx(ClipFxLibrary.RGB_SPLIT).single()
        assertEquals(50f, rgb.values[0], 0.5f)
        assertEquals(90f, rgb.values[1], 0.5f)
        val vig = fx(ClipFxLibrary.VIGNETTE).single()
        assertEquals(60f, vig.values[0], 0.5f)
        assertEquals(80f, vig.values[1], 0.5f)
        assertEquals(40f, vig.values[2], 0.5f)
        assertEquals(1, fx(ClipFxLibrary.INVERT).size)
        val tile = fx(ClipFxLibrary.TILE).single()
        assertEquals(3f, tile.values[0], 0.05f)
        assertEquals(1f, tile.values[2], 0.01f)
        assertEquals(25f, tile.values[3], 0.5f)
        val blur = fx(ClipFxLibrary.GAUSSIAN_BLUR_AM).single()
        assertEquals(1.5f, blur.values[0], 0.02f)
        // Hue/saturation split into hueshift + satvib and recombines on the way back in.
        val hs = fx(ClipFxLibrary.HUE_SAT)
        assertEquals(2, hs.size)
        assertEquals(30f, hs.sumOf { it.values[0].toDouble() }.toFloat(), 1f)
        assertEquals(-40f, hs.sumOf { it.values[1].toDouble() }.toFloat(), 1f)
        assertEquals(10f, hs.sumOf { it.values[3].toDouble() }.toFloat(), 1f)
        assertTrue(fx(ClipFxLibrary.GLOW).isEmpty())
        assertEquals(10, c.effects.size)
    }

    @Test
    fun fadeRoundTrip() {
        val shape = clip("Fader", LayerType.SHAPE) {
            copy(shapePattern = "RECT", fadeInMs = 500, fadeOutMs = 250)
        }
        val (_, back) = roundTrip(snap(listOf(shape)))
        val c = clipsOf(back).single()
        assertEquals(500L, c.fadeInMs)
        assertEquals(250L, c.fadeOutMs)
    }

    @Test
    fun controllerIsCountedNotWritten() {
        val ctl = clip("Ctl", LayerType.CONTROLLER) { copy() }
        val shape = clip("S", LayerType.SHAPE) {
            copy(shapePattern = "RECT", controllerId = ctl.id)
        }
        val (out, back) = roundTrip(snap(listOf(shape, ctl)))
        assertEquals(1, out.report.omittedLayers.size)
        assertTrue(out.report.omittedLayers[0].contains("Controller"))
        assertEquals(1, clipsOf(back).size)
    }

    // ---- build 198 hardening ----------------------------------------------------------------------

    @Test
    fun groupChildOutsideTheGroupSpanIsOmittedNotACrash() {
        val groupId = "id-Group"
        val group = clip("Group", LayerType.GROUP, start = 1000, dur = 2000) {
            copy(id = groupId, transformX = 0f, transformY = 0f)
        }
        // The child starts only after the group has already ended (the group was
        // trimmed shorter, the child moved out). The exporter must list it as
        // omitted — build 197's span clamp threw IllegalArgumentException on the
        // empty coerce range this shape of project produces (build 198 fix).
        val late = clip("Late", LayerType.SHAPE, start = 5000, dur = 1000) {
            copy(shapePattern = "RECT", groupId = groupId)
        }
        val out = AlightXmlExporter.export("RT", snap(listOf(late, group)), pw)
        assertNotNull(out)
        assertTrue(out!!.report.omittedLayers.any { it.contains("outside its group's span") })
        assertEquals(1, out.report.layersWritten)
    }

    @Test
    fun controlCharactersNeverReachTheFile() {
        // XML 1.0 forbids most C0 control characters: a layer name or text body
        // carrying one (a stray paste) must not turn the export into a file no
        // strict parser — Alight's, or this app's own importer — can reopen.
        val text = clip("T\u0002x", LayerType.TEXT) {
            copy(text = "A\u0001B\u000BC")
        }
        val out = AlightXmlExporter.export("RT", snap(listOf(text)), pw)
        assertNotNull(out)
        val xml = out!!.xml
        assertTrue(
            "no raw control chars in the file",
            xml.none { it.code < 0x20 && it != '\t' && it != '\n' && it != '\r' }
        )
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(xml.byteInputStream(Charsets.UTF_8))
    }

    @Test
    fun malformedEffectTailCountCannotCrashTheExport() {
        // A corrupted values list whose first tail-track count is absurd: the
        // count x 6 bounds check must be overflow-safe, or the decode reads
        // past the list and the export dies (build 198 fix). The effect must
        // still export from its constants.
        val bogus = ClipFx(
            type = ClipFxLibrary.EXPOSURE,
            values = listOf(0.5f, 0f, 100f, 0f, 0f, 0f, 0f, 4.0e8f, 1f, 2f)
        )
        val shape = clip("Fx", LayerType.SHAPE) {
            copy(shapePattern = "RECT", effects = listOf(bogus))
        }
        val out = AlightXmlExporter.export("RT", snap(listOf(shape)), pw)
        assertNotNull(out)
        assertTrue(out!!.xml.contains("com.alightcreative.effects.exposure"))
    }
}
