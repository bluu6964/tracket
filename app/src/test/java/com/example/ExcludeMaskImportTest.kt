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
 * Exclude Mask combining (build 138, ROADMAP item 16): an Alight `blending="exclude"` layer is the
 * Blending menu's second mask kind. Since build 135 it imported hidden like a Mask layer but stayed
 * unlinked (the renderers' links intersected; exclusion subtracts). Now it links to the same target
 * under the same rules as a Mask layer, with [com.example.model.TimelineClip.isExcludeMask] carrying
 * the kind, and the renderers subtract its cut in stack order — a stack's combined cut is
 * (intersection of its Mask layers) minus (union of its Exclude Mask layers), and an exclude with no
 * Mask in its stack subtracts from the uncut target. The corpus census behind these pins (105 files):
 * 30 exclude layers — 24 in exclude-only stacks, 6 in mixed Mask+Exclude stacks (wym-prst-268 x5,
 * proyek-baru-1 x1); 24 are shape layers (they link) and 6 are embedScene groups (they could not
 * serve as masks in the renderers until build 141 composited group-mask coverage — they link now;
 * GroupMaskImportTest pins the group cases). The combination algebra itself is pinned on the CPU
 * reference in mask_plan_selftest.
 */
class ExcludeMaskImportTest {

    private fun scene(layers: String, totalMs: Long = 2000) =
        """<?xml version='1.0' encoding='UTF-8' ?>
<scene title="Excl" width="1920" height="1080" bgcolor="#ff000000" totalTime="$totalMs" fps="30">
$layers
</scene>"""

    private fun shape(id: String, label: String, extra: String = "", start: Long = 0, end: Long = 2000) =
        """<shape id="$id" label="$label" startTime="$start" endTime="$end" fillType="color" $extra mediaFillMode="stretch" s=".rect"><transform><location value="960.000000,540.000000,0.000000" /><scale value="1.000000,1.000000" /></transform><fillColor value="#ff00ff00" /><property name="size" type="vec2" value="100.000000,100.000000" /></shape>"""

    private fun exclShape(id: String, label: String, start: Long = 0, end: Long = 2000) =
        shape(id, label, """blending="exclude"""", start, end)

    private fun group(id: String, label: String, inner: String) =
        """<embedScene id="$id" label="$label" startTime="0" endTime="2000" fillType="intrinsic" mediaFillMode="stretch"><transform><location value="960.000000,540.000000,0.000000" /></transform><scene title="" width="1920" height="1080" totalTime="2000" fps="30">$inner</scene></embedScene>"""

    @Test
    fun excludeAloneLinksToTheLayerBelow() {
        // The commonest corpus form (24 of 30): an exclude with no Mask in its stack subtracts
        // from the uncut target — the target minus the exclude's shape.
        val r = AlightXmlImporter.parse(scene(shape("1", "Photo") + exclShape("2", "Hole")), 1920f)
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val photo = clips.first { it.name == "Photo" }
        val hole = clips.first { it.name == "Hole" }
        assertTrue(hole.isMask)
        assertTrue(hole.isExcludeMask)
        assertFalse(hole.isVisible)
        assertEquals(photo.id, hole.maskTargetId)
        val byTarget = LayerMaskLinks.masksByTarget(clips.asReversed())
        assertEquals(listOf("Hole"), byTarget[photo.id]?.map { it.name })
        assertEquals(listOf(true), byTarget[photo.id]?.map { it.isExcludeMask })
        assertTrue(r.warnings.none { it.contains("Exclude mask layer") })
    }

    @Test
    fun mixedMaskExcludeStackLinksBothInSceneOrder() {
        // The wym-prst-268 pattern (x5 of its 6 groups): [image, Mask circle, Exclude circle] —
        // both masks link to the image; the pairing hands the renderers the intersect cut first
        // and the subtract cut second, so the image survives only in the mask minus the exclude.
        val r = AlightXmlImporter.parse(
            scene(shape("1", "Photo") + shape("2", "Disc", """blending="mask"""") + exclShape("3", "Bite")), 1920f
        )
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val photo = clips.first { it.name == "Photo" }
        assertEquals(photo.id, clips.first { it.name == "Disc" }.maskTargetId)
        assertEquals(photo.id, clips.first { it.name == "Bite" }.maskTargetId)
        val byTarget = LayerMaskLinks.masksByTarget(clips.asReversed())
        assertEquals(listOf("Disc", "Bite"), byTarget[photo.id]?.map { it.name })
        assertEquals(listOf(false, true), byTarget[photo.id]?.map { it.isExcludeMask })
        assertTrue(r.warnings.none { it.contains("Exclude mask layer") || it.contains("Mask layer") })
    }

    @Test
    fun mixedStackActivityWindowsFollowEachMasksOwnSpan() {
        // proyek-baru-1, verbatim spans: image 0..2032, Mask 0..932, Exclude 900..2032. The cuts
        // the renderers apply at a time t are the stack's ACTIVE masks (their scopeMasksFor
        // filter), so the file has three regimes: intersect-only, intersect-minus-exclude, and
        // exclude-alone (the target minus the shape, NOT a cut to nothing) — the model-level
        // basis of the combination, pinned here.
        val r = AlightXmlImporter.parse(
            scene(
                shape("1", "IMG-20261002-WA0040.jpg", start = 0, end = 2032) +
                    shape("2", "Persegi panjang 1", """blending="mask"""", start = 0, end = 932) +
                    exclShape("3", "Persegi panjang 1", start = 900, end = 2032),
                totalMs = 2032
            ), 1920f
        )
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val img = clips.first { it.name == "IMG-20261002-WA0040.jpg" }
        val stack = LayerMaskLinks.masksByTarget(clips.asReversed())[img.id]!!
        assertEquals(2, stack.size)
        fun activeAt(t: Long) = stack.filter { it.isActiveAt(t) }.map { it.isExcludeMask }
        assertEquals(listOf(false), activeAt(500))          // mask only
        assertEquals(listOf(false, true), activeAt(910))    // mask minus exclude
        assertEquals(listOf(true), activeAt(1500))          // exclude alone
        assertTrue(r.warnings.none { it.contains("Exclude mask layer") })
    }

    @Test
    fun excludeOverAGroupLinksToTheGroup() {
        // omniframe-tiki-tiki-pf's "Group and Mask 4": a shape Exclude Mask over an embedScene —
        // the group's composed picture is what gets subtracted from.
        val r = AlightXmlImporter.parse(
            scene(group("9", "Group 2", shape("8", "Inner")) + exclShape("2", "Circle 1")), 1920f
        )
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val grp = clips.first { it.type == LayerType.GROUP }
        val excl = clips.first { it.name == "Circle 1" }
        assertTrue(excl.isExcludeMask)
        assertEquals(grp.id, excl.maskTargetId)
        val byTarget = LayerMaskLinks.masksByTarget(clips.filter { it.groupId == grp.groupId })
        assertEquals(excl.id, byTarget[grp.id]?.single()?.id)
        assertTrue(r.warnings.none { it.contains("Exclude mask layer") })
    }

    @Test
    fun excludeWithNoLayerBelowStaysUnlinked() {
        val r = AlightXmlImporter.parse(scene(exclShape("1", "Hole") + shape("2", "Box")), 1920f)
        val hole = r.snapshot.tracks.flatMap { it.clips }.first { it.name == "Hole" }
        assertTrue(hole.isMask)
        assertNull(hole.maskTargetId)
        assertTrue(r.warnings.any { it.contains("Exclude mask layer") && it.contains("no imported layer below") })
    }

    @Test
    fun excludeOverAnUnsupportedTargetStaysUnlinked() {
        val audio = """<audio id="1" label="Song" startTime="0" endTime="2000" src="content://media/external/audio/media/7" />"""
        val r = AlightXmlImporter.parse(scene(audio + exclShape("2", "Hole")), 1920f)
        val hole = r.snapshot.tracks.flatMap { it.clips }.first { it.name == "Hole" }
        assertTrue(hole.isMask)
        assertNull(hole.maskTargetId)
        assertTrue(r.warnings.any { it.contains("Exclude mask layer") && it.contains("masks a audio layer") })
    }

    @Test
    fun test1RealExcludeLinksToCircle3Copy() {
        // Test-1's Shape embed, verbatim from 1ab16e65: [Circle 3 Copy, Rounded Rectangle 1 Copy
        // (exclude)]. The pill's exclusion now links to the circle it sits on. (Round 9 established
        // the pill never overlaps the circle's travel, so the observable effect in Test-1 is nil —
        // this pins the LINK, not a pixel change.)
        val xml = """<?xml version='1.0' encoding='UTF-8' ?><scene title="3" width="1920" height="1080" bgcolor="#ff000000" totalTime="5833" fps="30">
<embedScene id="634614" label="Shape" startTime="3200" endTime="5365" fillType="intrinsic" outTime="2165" mediaFillMode="stretch"><transform><location value="960.000000,540.000000,0.000000" /><pivot value="1.500000,26.620605" /></transform><fillColor value="#ff000000" /><scene title="" width="1920" height="1080" exportWidth="1920" exportHeight="1080" precompose="dynamicResolution" bgcolor="#00000000" totalTime="2165" fps="30" modifiedTime="0" amver="1002592" ffver="106" am="com.alightcreative.motion/5.0.275" amplatform="android" retime="off" retimeAdaptFPS="false"><shape id="634483" label="Circle 3 Copy" startTime="0" endTime="1532" fillType="color" mediaFillMode="stretch" s=".circle"><transform><location><kf t="0.011097" v="960.000000,401.000000,0.000000" /><kf t="0.228460" v="960.000000,192.821716,0.000000" e="cubicBezier 0.18333337 0.0 0.25999996 1.0" /><kf t="0.598564" v="960.000000,751.463745,0.000000" e="cubicBezier 0.74 0.0 1.0 0.62" /></location><scale value="0.365000,0.365000" /></transform><fillColor value="#ff14fd6a" /><property name="size" type="vec2" value="100.000000,100.000000" /></shape><shape id="634489" label="Rounded Rectangle 1 Copy" startTime="0" endTime="2165" fillType="color" blending="exclude" mediaFillMode="stretch" s=".roundrect"><transform><location value="961.500000,831.279663,0.000000" /><scale value="1.070881,1.070881" /></transform><fillColor value="#ff1ed91e" /><path-stroke direction="centered" enabled="false" end-size="1.500000"><color value="#ffffffff" /><size value="13.000000" /></path-stroke><property name="size" type="vec2" value="522.000000,136.000000" /><property name="cornerRadius" type="float" value="373.000000" /></shape></scene></embedScene>
</scene>"""
        val r = AlightXmlImporter.parse(xml, 1920f)
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val circle = clips.first { it.name == "Circle 3 Copy" }
        val pill = clips.first { it.name == "Rounded Rectangle 1 Copy" }
        assertTrue(pill.isMask)
        assertTrue(pill.isExcludeMask)
        assertFalse(pill.isVisible)
        assertEquals(circle.id, pill.maskTargetId)
        assertEquals(circle.groupId, pill.groupId)
        assertTrue(r.warnings.none { it.contains("Exclude mask layer") })
    }
}
