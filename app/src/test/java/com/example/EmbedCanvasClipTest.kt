package com.example

import com.example.engine.AlightXmlImporter
import com.example.engine.OverlayMasks
import com.example.model.ClipMaskLibrary
import com.example.model.LayerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Embed-canvas clipping (ROADMAP Queue 12a): an imported `<embedScene>` is a precomposition —
 * Alight rasterises it on its OWN canvas, so content outside that rect never reaches the
 * parent scene. The native renderers cut the group's composed picture to the embed's box
 * (OverlayMasks.embedCanvasClipOps -> one INTERSECT rect mask op, run as a mask-only fx pass
 * right after the group pass, in the preview and the exporter alike). These pins hold the
 * geometry of that op and the importer's side of the rule (the group carries its canvas; the
 * import note states the clipping precisely). The corpus census behind the change: 211 embeds,
 * 52 whose content crosses the canvas, 7 visibly inside the root frame (Subscriber's tall
 * "Shape 1" in a 1080x607 embed among them — the geometry pinned below).
 */
class EmbedCanvasClipTest {

    // JNI mask op layout (ClipMaskLibrary.STRIDE floats): 0 shape, 1 mode, 3/4 centre,
    // 5/6 size (fractions of the target), 7 rotation, 8 feather.
    private fun ops(
        anchorX: Float = 0f, anchorY: Float = 0f, refX: Float = 0f, refY: Float = 0f,
        scale: Float = 100f, rot: Float = 0f, embedW: Float = 1080f, embedH: Float = 1920f,
        frameW: Int = 1080, frameH: Int = 1920
    ): FloatArray? = OverlayMasks.embedCanvasClipOps(
        anchorX, anchorY, refX, refY, scale, rot, false, false, embedW, embedH, 1f, frameW, frameH
    )

    @Test
    fun identityEmbedClipsToTheWholeFrame() {
        val o = ops()!!
        assertEquals(ClipMaskLibrary.STRIDE, o.size)
        assertEquals(0f, o[0], 0f)          // RECT
        assertEquals(2f, o[1], 0f)          // INTERSECT: folds from a base of 1, pure cut
        assertEquals(0.5f, o[3], 0.001f)
        assertEquals(0.5f, o[4], 0.001f)
        assertEquals(1f, o[5], 0.001f)
        assertEquals(1f, o[6], 0.001f)
        assertEquals(0f, o[7], 0.001f)
        assertEquals(0f, o[8], 0.001f)      // hard edge, no feather
    }

    @Test
    fun scaledEmbedClipsToItsPlacedBox() {
        // A 1080x1080 embed at 50 % in a 1080x1920 frame: a 540x540 box in the middle.
        val o = ops(embedW = 1080f, embedH = 1080f, scale = 50f)!!
        assertEquals(0.5f, o[3], 0.001f)
        assertEquals(0.5f, o[4], 0.001f)
        assertEquals(0.5f, o[5], 0.001f)
        assertEquals(540f / 1920f, o[6], 0.001f)
    }

    @Test
    fun offsetAnchorMovesTheClipBox() {
        val o = ops(anchorX = 270f, anchorY = -480f)!!
        assertEquals(0.75f, o[3], 0.001f)   // (540 + 270) / 1080
        assertEquals(0.25f, o[4], 0.001f)   // (960 - 480) / 1920
    }

    @Test
    fun pivotShiftsTheBoxBack() {
        // Anchor = location + pivot; the scene centre sits at anchor - R*S*ref.
        val o1 = ops(anchorX = 100f, refX = 100f)!!
        assertEquals(0.5f, o1[3], 0.001f)
        val o2 = ops(anchorX = 100f, refX = 100f, scale = 200f)!!
        assertEquals(440f / 1080f, o2[3], 0.001f)   // 540 + 100 - 2*100
    }

    @Test
    fun rotatedEmbedTurnsTheClipRect() {
        val o = ops(embedW = 1080f, embedH = 1080f, rot = 90f)!!
        assertEquals(90f, o[7], 0.001f)
        assertEquals(1f, o[5], 0.001f)              // the box's own width, turned by the op
        assertEquals(1080f / 1920f, o[6], 0.001f)
    }

    @Test
    fun userGroupHasNoCanvasClip() {
        assertNull(ops(embedW = 0f))
        assertNull(ops(embedH = 0f))
    }

    // ---- the importer side, on the Subscriber geometry (1080x607 embed in a 1080x1080 root) --

    private val overflowXml = """
<scene title="Subscriber" width="1080" height="1080" bgcolor="#ff000000" totalTime="4000" fps="30"><embedScene id="1" label="Group 1" startTime="0" endTime="4000" fillType="intrinsic" mediaFillMode="stretch"><transform><location value="540.000000,540.000000,0.000000" /></transform><fillColor value="#ff000000" /><scene title="" width="1080" height="607" totalTime="4000" fps="30"><shape id="2" label="Shape 1" startTime="0" endTime="4000" fillType="color" mediaFillMode="stretch" s=".rect"><transform><location value="540.000000,303.000000,0.000000" /></transform><fillColor value="#ffffffff" /><property name="size" type="vec2" value="200.000000,800.000000" /></shape></scene></embedScene></scene>
""".trimIndent()

    @Test
    fun overflowingEmbedImportsWithItsCanvasAndThePreciseNote() {
        val r = AlightXmlImporter.parse(overflowXml, 1080f)
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val group = clips.single { it.type == LayerType.GROUP }
        assertEquals(1080f, group.embedW, 0.5f)
        assertEquals(607f, group.embedH, 0.5f)
        val child = clips.single { it.name == "Shape 1" }
        assertEquals(group.id, child.groupId)
        assertTrue(r.warnings.any { it.contains("layers inside are clipped to it") })
        assertFalse(r.warnings.any { it.contains("not clipped") })
    }

    @Test
    fun clipOpsForTheImportedOverflowingEmbedAreItsCanvasRect() {
        val r = AlightXmlImporter.parse(overflowXml, 1080f)
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val group = clips.single { it.type == LayerType.GROUP }
        val link = clips.first { it.parent?.id == group.id }.parent!!
        val o = OverlayMasks.embedCanvasClipOps(
            group.transformX, group.transformY, link.refX, link.refY,
            group.scalePercent, group.rotationDeg, group.isFlippedH, group.isFlippedV,
            group.embedW, group.embedH, 1f, 1080, 1080
        )!!
        assertEquals(0.5f, o[3], 0.001f)
        assertEquals(0.5f, o[4], 0.001f)
        assertEquals(1f, o[5], 0.001f)
        assertEquals(607f / 1080f, o[6], 0.001f)   // the 607-tall canvas, exactly
    }

    @Test
    fun boundaryContentExactlyAtTheCanvasEdgeStillImports() {
        // A shape whose size is the canvas itself (540 x 303.5 half extents = 1080 x 607):
        // nothing to cut, but the embed still carries its canvas and the note still reads true.
        val xml = overflowXml.replace("200.000000,800.000000", "540.000000,303.500000")
        val r = AlightXmlImporter.parse(xml, 1080f)
        val group = r.snapshot.tracks.flatMap { it.clips }.single { it.type == LayerType.GROUP }
        assertEquals(607f, group.embedH, 0.5f)
        assertNotNull(
            OverlayMasks.embedCanvasClipOps(
                group.transformX, group.transformY, 0f, 0f, group.scalePercent, 0f,
                false, false, group.embedW, group.embedH, 1f, 1080, 1080
            )
        )
    }

    @Test
    fun sameSizeEmbedHasNoCanvasNote() {
        val xml = overflowXml.replace("width=\"1080\" height=\"607\"", "width=\"1080\" height=\"1080\"")
        val r = AlightXmlImporter.parse(xml, 1080f)
        assertFalse(r.warnings.any { it.contains("canvas") })
    }
}
