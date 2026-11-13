package com.example

import com.example.engine.AlightXmlImporter
import com.example.engine.LayerMaskLinks
import com.example.model.LayerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Group-as-MASK (build 141, the item-16 tail): a whole embedScene can be a mask layer in Alight —
 * `blending="mask"` or `blending="exclude"` on the group itself. Through build 140 a mask group
 * imported as a plain VISIBLE group (drawn — plainly wrong) and an exclude group stayed unlinked
 * (the mask painters drew shape / text layers only). Now both kinds flag hidden + mask like leaf
 * masks and link to the layer below, and the engines composite the group's scope into the mask
 * coverage scratch (NativePreviewEngine.maskCoverageInto / the exporter's maskCoverageInto).
 *
 * Corpus census behind these pins (105 files, ElementTree over every scene): 11 `blending="mask"`
 * groups (omniframe-evergreen x5, omniframe-onyankopon x4, omniframe-hardtrekk-hero x1,
 * omniframe-tiki-tiki-pf x1) and 3 unique exclude groups (shared-preset-likenya x2 over GROUP
 * targets, montagem-guerreirro x1). Every mask group's children are shape / text layers (plus
 * evergreen's one nested mask group), and their targets are shape, text, group and media-shape
 * layers — the scenes below replicate each pattern. What stays device-unverified: the coverage
 * composite itself on a phone (children's internal blend modes composite as alpha-over; every
 * corpus mask-group child is Normal).
 */
class GroupMaskImportTest {

    private fun scene(layers: String, totalMs: Long = 2000) =
        """<?xml version='1.0' encoding='UTF-8' ?>
<scene title="GM" width="1920" height="1080" bgcolor="#ff000000" totalTime="$totalMs" fps="30">
$layers
</scene>"""

    private fun shape(id: String, label: String, extra: String = "") =
        """<shape id="$id" label="$label" startTime="0" endTime="2000" fillType="color" $extra mediaFillMode="stretch" s=".rect"><transform><location value="960.000000,540.000000,0.000000" /><scale value="1.000000,1.000000" /></transform><fillColor value="#ff00ff00" /><property name="size" type="vec2" value="100.000000,100.000000" /></shape>"""

    private fun text(id: String, label: String) =
        """<text id="$id" label="$label" startTime="0" endTime="2000" fillType="color" size="30.000000" font="googlefonts?name=Roboto&amp;weight=400"><transform><location value="960.000000,540.000000,0.000000" /></transform><fillColor value="#ffffffff" /><content>Hi</content></text>"""

    private fun group(id: String, label: String, inner: String, extra: String = "") =
        """<embedScene id="$id" label="$label" startTime="0" endTime="2000" fillType="intrinsic" $extra mediaFillMode="stretch"><transform><location value="960.000000,540.000000,0.000000" /></transform><scene title="" width="1920" height="1080" totalTime="2000" fps="30">$inner</scene></embedScene>"""

    private fun maskGroup(id: String, label: String, inner: String) =
        group(id, label, inner, """blending="mask"""")

    private fun exclGroup(id: String, label: String, inner: String) =
        group(id, label, inner, """blending="exclude"""")

    @Test
    fun maskGroupOverShapeLinksAndHides() {
        // The evergreen pattern (x3 at root): a "Text" group of shapes masking the photo rect
        // below it. Before build 141 this group was imported VISIBLE and drawn.
        val r = AlightXmlImporter.parse(
            scene(shape("1", "Photo") + maskGroup("9", "Text", shape("2", "Glyph A") + shape("3", "Glyph B"))),
            1920f
        )
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val photo = clips.first { it.name == "Photo" }
        val g = clips.first { it.name == "Text" }
        assertEquals(LayerType.GROUP, g.type)
        assertTrue(g.isMask)
        assertFalse(g.isExcludeMask)
        assertFalse(g.isVisible)
        assertEquals(photo.id, g.maskTargetId)
        assertTrue(r.warnings.none { it.contains("Mask layer") })
        // The renderer pairing hands the group mask to the photo's cut list.
        val byTarget = LayerMaskLinks.masksByTarget(clips.asReversed())
        assertEquals(listOf("Text"), byTarget[photo.id]?.map { it.name })
        assertEquals(listOf(false), byTarget[photo.id]?.map { it.isExcludeMask })
        // Its children stay inside its scope, so the coverage composite finds them.
        val kids = clips.filter { it.groupId == g.id }
        assertEquals(listOf("Glyph A", "Glyph B"), kids.map { it.name }.sorted())
    }

    @Test
    fun maskGroupOverTextLinks() {
        // The onyankopon pattern: a stroke-animation group masking a TEXT layer.
        val r = AlightXmlImporter.parse(
            scene(text("1", "Title") + maskGroup("9", "Stroke Animation", shape("2", "Stroke"))),
            1920f
        )
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val title = clips.first { it.name == "Title" }
        val g = clips.first { it.name == "Stroke Animation" }
        assertTrue(g.isMask)
        assertFalse(g.isVisible)
        assertEquals(title.id, g.maskTargetId)
        assertTrue(r.warnings.none { it.contains("Mask layer") })
    }

    @Test
    fun maskGroupOverGroupLinks() {
        // The hardtrekk-hero pattern: a mask group over another (ordinary) group.
        val inner = group("8", "Content", shape("3", "Inner"))
        val r = AlightXmlImporter.parse(
            scene(inner + maskGroup("9", "Group 1", text("4", "A") + text("5", "B"))),
            1920f
        )
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val content = clips.first { it.name == "Content" }
        val g = clips.first { it.name == "Group 1" }
        assertTrue(g.isMask)
        assertEquals(content.id, g.maskTargetId)
        assertTrue(r.warnings.none { it.contains("Mask layer") })
    }

    @Test
    fun excludeGroupOverGroupLinksAsExclude() {
        // The likenya pattern (x2): an exclude group of pie shapes subtracting from a GROUP.
        val pies = (1..3).joinToString("") { shape("2$it", "Pie $it", "") }
        val r = AlightXmlImporter.parse(
            scene(group("8", "Group 2", shape("3", "Inner")) + exclGroup("9", "Group 1 Copy 3", pies)),
            1920f
        )
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val target = clips.first { it.name == "Group 2" }
        val g = clips.first { it.name == "Group 1 Copy 3" }
        assertTrue(g.isMask)
        assertTrue(g.isExcludeMask)
        assertFalse(g.isVisible)
        assertEquals(target.id, g.maskTargetId)
        val byTarget = LayerMaskLinks.masksByTarget(clips.asReversed())
        assertEquals(listOf(true), byTarget[target.id]?.map { it.isExcludeMask })
        assertTrue(r.warnings.none { it.contains("Exclude mask layer") })
    }

    @Test
    fun nestedMaskGroupLinksInsideItsParentScope() {
        // The full evergreen construct: a mask group ("Text") over a photo, holding a text and
        // a NESTED mask group ("Sketchy Stroke Animation Copy") over that text.
        val outer = maskGroup(
            "9", "Text",
            text("4", "10187688") + maskGroup("10", "Sketchy Stroke Animation Copy", shape("5", "S1") + shape("6", "S2"))
        )
        val r = AlightXmlImporter.parse(scene(shape("1", "Photo") + outer), 1920f)
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val photo = clips.first { it.name == "Photo" }
        val outerG = clips.first { it.name == "Text" }
        val innerText = clips.first { it.name == "10187688" }
        val nested = clips.first { it.name == "Sketchy Stroke Animation Copy" }
        assertEquals(photo.id, outerG.maskTargetId)
        assertEquals(innerText.id, nested.maskTargetId)
        assertEquals(outerG.id, nested.groupId)
        assertEquals(outerG.id, innerText.groupId)
        assertTrue(nested.isMask)
        assertFalse(nested.isVisible)
        assertTrue(r.warnings.none { it.contains("Mask layer") })
    }

    @Test
    fun maskGroupInAStackWithALeafMaskLinksBoth() {
        // Stack semantics are unchanged by the mask's own type: [target, leaf mask, group mask]
        // links BOTH masks to the target, in scene order.
        val r = AlightXmlImporter.parse(
            scene(
                shape("1", "Photo") +
                    shape("2", "Disc", """blending="mask"""") +
                    maskGroup("9", "Frame", shape("3", "Bar"))
            ),
            1920f
        )
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val photo = clips.first { it.name == "Photo" }
        assertEquals(photo.id, clips.first { it.name == "Disc" }.maskTargetId)
        assertEquals(photo.id, clips.first { it.name == "Frame" }.maskTargetId)
        val byTarget = LayerMaskLinks.masksByTarget(clips.asReversed())
        assertEquals(listOf("Disc", "Frame"), byTarget[photo.id]?.map { it.name })
    }

    @Test
    fun maskGroupWithNothingBelowStaysUnlinkedWithAWarning() {
        val r = AlightXmlImporter.parse(
            scene(maskGroup("9", "Text", shape("2", "Glyph")) + shape("1", "Photo")),
            1920f
        )
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val g = clips.first { it.name == "Text" }
        assertTrue(g.isMask)
        assertFalse(g.isVisible)
        assertNull(g.maskTargetId)
        assertTrue(r.warnings.any { it.contains("Mask layer") && it.contains("no imported layer below it") })
    }

    @Test
    fun leafMaskBehaviourUnchanged() {
        // A media (video-content) layer still cannot serve as a mask; a shape mask still links.
        val r = AlightXmlImporter.parse(
            scene(shape("1", "Photo") + shape("2", "Disc", """blending="mask"""")),
            1920f
        )
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val photo = clips.first { it.name == "Photo" }
        val disc = clips.first { it.name == "Disc" }
        assertEquals(photo.id, disc.maskTargetId)
        assertTrue(r.warnings.none { it.contains("Mask layer") })
    }
}
