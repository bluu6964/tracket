package com.example

import com.example.engine.AlightXmlImporter
import com.example.engine.LayerMaskLinks
import com.example.model.AnimProp
import com.example.model.LayerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Alight Motion layer masks (`blending="mask"`, builds 94–95): a mask layer is never drawn — it cuts the
 * layer directly below it in the same scene. The importer flags the mask clip ([com.example.model.TimelineClip.isMask]),
 * keeps it hidden, and links it to that layer ([com.example.model.TimelineClip.maskTargetId]); the
 * renderers pair them up with [LayerMaskLinks] and cut the layer's picture with the mask's rendering.
 * A GROUP layer below the mask links too: its composed picture is what gets cut.
 */
class LayerMaskImportTest {

    private fun scene(layers: String, w: Int = 1920, h: Int = 1080, totalMs: Long = 2000) =
        """<?xml version='1.0' encoding='UTF-8' ?>
<scene title="Masks" width="$w" height="$h" bgcolor="#ff000000" totalTime="$totalMs" fps="30">
$layers
</scene>"""

    private fun shape(id: String, label: String, extra: String = "") =
        """<shape id="$id" label="$label" startTime="0" endTime="2000" fillType="color" $extra mediaFillMode="stretch" s=".rect"><transform><location value="960.000000,540.000000,0.000000" /><scale value="1.000000,1.000000" /></transform><fillColor value="#ff00ff00" /><property name="size" type="vec2" value="100.000000,100.000000" /></shape>"""

    private fun maskShape(id: String, label: String) = shape(id, label, """blending="mask"""")

    private fun group(id: String, label: String, inner: String) =
        """<embedScene id="$id" label="$label" startTime="0" endTime="2000" fillType="intrinsic" mediaFillMode="stretch"><transform><location value="960.000000,540.000000,0.000000" /></transform><scene title="" width="1920" height="1080" totalTime="2000" fps="30">$inner</scene></embedScene>"""

    private fun text(id: String, label: String) =
        """<text id="$id" label="$label" startTime="0" endTime="2000" fillType="color" size="30.000000" font="googlefonts?name=Roboto&amp;weight=400"><transform><location value="960.000000,540.000000,0.000000" /></transform><fillColor value="#ffffffff" /><content>Hi</content></text>"""

    // The "Logo Here" group of the real ST Intro template (verbatim layers): a media rect with a
    // circular mask directly on top of it.
    private val logoHere = scene(
        """<embedScene id="633205" label="Logo Here" startTime="4133" endTime="8032" fillType="intrinsic" mediaFillMode="stretch"><transform><location value="960.000000,540.000000,0.000000" /><scale><kf t="0.012824" v="0.000000,0.000000" /><kf t="0.132342" v="0.380216,0.380216" e="cubicBezier 0.44 0.0 0.13 1.0" /></scale></transform><fillColor value="#ff000000" /><scene title="" width="1920" height="1080" totalTime="3032" fps="30"><shape id="633203" label="images.png" startTime="0" endTime="3032" fillType="media" fillImage="content://media/external/images/media/1000033464" mediaFillMode="stretch" s=".rect"><transform><location value="960.000000,540.000000,0.000000" /></transform><property name="size" type="vec2" value="960.000000,503.500000" /></shape><shape id="633204" label="Mask" startTime="0" endTime="3032" fillType="color" blending="mask" mediaFillMode="stretch" s=".circle"><transform><location value="960.000000,540.000000,0.000000" /><scale value="3.235000,3.235000" /></transform><fillColor value="#ffd1c251" /><property name="size" type="vec2" value="100.000000,100.000000" /></shape></scene></embedScene>""",
        totalMs = 8032
    )

    @Test
    fun stIntroMaskIsFlaggedAndLinkedToTheImageBelowIt() {
        val r = AlightXmlImporter.parse(logoHere, 1920f)
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val mask = clips.first { it.name == "Mask" }
        val image = clips.first { it.name == "images.png" }
        assertTrue(mask.isMask)
        assertFalse(mask.isVisible)
        assertEquals(image.id, mask.maskTargetId)
        // The two live in the same group, and the renderer pairing resolves the link inside that scope.
        assertEquals(image.groupId, mask.groupId)
        val byTarget = LayerMaskLinks.masksByTarget(clips.filter { it.groupId == image.groupId })
        assertEquals(mask.id, byTarget[image.id]?.id)
        assertTrue(LayerMaskLinks.isMasked(image, byTarget))
        // An applied mask leaves no warning behind.
        assertTrue(r.warnings.none { it.contains("Mask layer") })
    }

    @Test
    fun maskOverATextLayerIsLinked() {
        val r = AlightXmlImporter.parse(scene(text("1", "Caption") + maskShape("2", "Cut")), 1920f)
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val mask = clips.first { it.name == "Cut" }
        assertTrue(mask.isMask)
        assertEquals(clips.first { it.type == LayerType.TEXT }.id, mask.maskTargetId)
        assertTrue(r.warnings.none { it.contains("Mask layer") })
    }

    @Test
    fun maskWithNoLayerBelowCutsNothing() {
        val r = AlightXmlImporter.parse(scene(maskShape("1", "Cut") + shape("2", "Box")), 1920f)
        val mask = r.snapshot.tracks.flatMap { it.clips }.first { it.name == "Cut" }
        assertTrue(mask.isMask)
        assertFalse(mask.isVisible)
        assertNull(mask.maskTargetId)
        assertTrue(r.warnings.any { it.contains("no imported layer below") })
    }

    @Test
    fun maskOverASkippedLayerCutsNothing() {
        // The layer below the mask has an unsupported shape kind, so it is not imported at all: the
        // mask must not jump over the gap and cut the next layer down.
        val skipped = """<shape id="1" label="Blob" startTime="0" endTime="2000" fillType="color" s=".blob"><transform><location value="960.000000,540.000000,0.000000" /></transform><fillColor value="#ff00ff00" /></shape>"""
        val r = AlightXmlImporter.parse(scene(shape("0", "Under") + skipped + maskShape("2", "Cut")), 1920f)
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val mask = clips.first { it.name == "Cut" }
        assertNull(mask.maskTargetId)
        assertTrue(r.warnings.any { it.contains("no imported layer below") })
    }

    @Test
    fun maskOverAGroupIsLinked() {
        val r = AlightXmlImporter.parse(scene(group("9", "G", shape("8", "Inner")) + maskShape("2", "Cut")), 1920f)
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val mask = clips.first { it.name == "Cut" }
        val grp = clips.first { it.type == LayerType.GROUP }
        assertTrue(mask.isMask)
        assertFalse(mask.isVisible)
        assertEquals(grp.id, mask.maskTargetId)
        // The renderer pairing resolves the link in the group's own scope (the top level here): the
        // group's composed picture is what gets cut.
        val byTarget = LayerMaskLinks.masksByTarget(clips.filter { it.groupId == grp.groupId })
        assertEquals(mask.id, byTarget[grp.id]?.id)
        assertTrue(LayerMaskLinks.isMasked(grp, byTarget))
        // An applied mask leaves no warning behind.
        assertTrue(r.warnings.none { it.contains("Mask layer") })
    }

    @Test
    fun maskOverAGroupInsideAnotherGroupIsLinked() {
        // The zooms pattern: inside one group, a second group with a mask layer directly on it — the
        // mask and its target group are siblings of that inner scene.
        val r = AlightXmlImporter.parse(
            scene(group("7", "Outer G", group("9", "Inner G", shape("8", "Inner")) + maskShape("2", "Cut"))), 1920f
        )
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val mask = clips.first { it.name == "Cut" }
        val innerG = clips.first { it.type == LayerType.GROUP && it.name == "Inner G" }
        val outerG = clips.first { it.type == LayerType.GROUP && it.name == "Outer G" }
        assertEquals(innerG.id, mask.maskTargetId)
        // The pairing resolves inside the outer group's scope (where both siblings live).
        assertEquals(outerG.id, mask.groupId)
        assertEquals(innerG.groupId, mask.groupId)
        val byTarget = LayerMaskLinks.masksByTarget(clips.filter { it.groupId == outerG.id })
        assertEquals(mask.id, byTarget[innerG.id]?.id)
        assertTrue(r.warnings.none { it.contains("Mask layer") })
    }

    @Test
    fun groupTargetMaskCutsTheComposedPictureToTheMaskShape() {
        // The group paints the whole frame (a 1920x1080 rect child); the mask is a circle that grows
        // to 600 px across at the end of its span. Cut by it, the group's picture keeps only the
        // circle: pi * 300^2 of the 1920 * 1080 frame (~13.6%) — the renderers apply exactly this
        // geometry (the mask layer evaluated per frame, multiplied over the group's picture).
        val box = 1920f * 130f / 360f   // the 130 dp shape box in scene px, as the painters use it
        val fullFrame = """<shape id="8" label="Full" startTime="0" endTime="2000" fillType="color" s=".rect"><transform><location value="960.000000,540.000000,0.000000" /></transform><fillColor value="#ff00ff00" /><property name="size" type="vec2" value="960.000000,540.000000" /></shape>"""
        val circleMask = """<shape id="2" label="Cut" startTime="0" endTime="2000" fillType="color" blending="mask" s=".circle"><transform><location value="960.000000,540.000000,0.000000" /><scale><kf t="0" v="1.000000,1.000000" /><kf t="1" v="3.000000,3.000000" /></scale></transform><fillColor value="#ffffffff" /><property name="size" type="vec2" value="100.000000,100.000000" /></shape>"""
        val r = AlightXmlImporter.parse(scene(group("9", "G", fullFrame) + circleMask), 1920f)
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val mask = clips.first { it.name == "Cut" }
        val grp = clips.first { it.type == LayerType.GROUP }
        assertEquals(grp.id, mask.maskTargetId)
        // The group's child really covers the frame, so what survives the cut is the mask's doing.
        val child = clips.first { it.name == "Full" }
        assertEquals(1920f, child.shapeScaleX * box, 20f)
        // The mask at the end of its span (its last SCALE key, percent of the box — the same maths
        // as the "3" / ST Intro circle assertions): 2 * 100 * 3 = 600 scene px across.
        val peak = mask.keyframes[AnimProp.SCALE]!!.maxOf { it.a }
        val diameter = peak / 100f * box
        assertEquals(600f, diameter, 15f)
        assertEquals(0f, mask.transformX, 0.01f)   // centred: the cutout sits in the middle of the frame
        assertEquals(0f, mask.transformY, 0.01f)
        val kept = (Math.PI * (diameter / 2) * (diameter / 2) / (1920.0 * 1080.0)).toFloat()
        assertEquals(0.136f, kept, 0.01f)
        assertTrue(kept < 0.5f)   // a real cut: most of the group's picture is removed
    }

    @Test
    fun stackedMasksLinkOnlyTheLowerOne() {
        val r = AlightXmlImporter.parse(
            scene(shape("1", "Box") + maskShape("2", "Cut A") + maskShape("3", "Cut B")), 1920f
        )
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val box = clips.first { it.name == "Box" }
        assertEquals(box.id, clips.first { it.name == "Cut A" }.maskTargetId)
        assertNull(clips.first { it.name == "Cut B" }.maskTargetId)
        assertTrue(r.warnings.any { it.contains("sits on another mask") })
    }

    @Test
    fun pairingIgnoresUnlinkedAndNonMaskClips() {
        val r = AlightXmlImporter.parse(scene(shape("1", "Box") + maskShape("2", "Cut")), 1920f)
        val clips = r.snapshot.tracks.flatMap { it.clips }
        // A mask with its link removed, and a normal layer, both stay out of the pairing.
        val unlinked = clips.map { if (it.isMask) it.copy(maskTargetId = null) else it }
        assertTrue(LayerMaskLinks.masksByTarget(unlinked).isEmpty())
        val notMask = clips.map { it.copy(isMask = false) }
        assertTrue(LayerMaskLinks.masksByTarget(notMask).isEmpty())
    }
}
