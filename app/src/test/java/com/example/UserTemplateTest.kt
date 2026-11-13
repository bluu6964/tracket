package com.example

import com.example.model.AnimProp
import com.example.model.ClipFxLibrary
import com.example.model.GroupOps
import com.example.model.Keyframe
import com.example.model.LayerType
import com.example.model.ParentLink
import com.example.model.SavedTemplate
import com.example.model.TimelineClip
import com.example.model.TimelineTrack
import com.example.model.TrackFollow
import com.example.model.UserTemplateFiles
import com.example.model.UserTemplateOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * "Save as Template" — user templates (Templates v2): the model rules of
 * [UserTemplateOps] / [UserTemplateFiles] that the store and the editor build on.
 *
 * The pins: capture takes the group's WHOLE subtree (root first, descendants in lane
 * order) and normalises it into a self-contained template — internal parent / controller
 * / mask / track links survive, links pointing OUTSIDE the group are dropped (a group
 * child whose parent is dropped keeps following its group through the REST link, the
 * build-143 convention); media references are rewritten to the template's own copies, so
 * the template still resolves after the source project's imports are deleted; and
 * instantiate produces fresh, independent groups (fresh ids, internal links remapped to
 * the counterparts, the composition shifted onto the playhead) without ever mutating
 * the stored template.
 */
class UserTemplateTest {

    private fun lane(clip: TimelineClip) = TimelineTrack(id = "lane-${clip.id}", name = "Layer", clips = listOf(clip))

    private fun clip(
        id: String, type: LayerType, startMs: Long = 1000L, durationMs: Long = 4000L,
        groupId: String? = null, parent: ParentLink? = null, name: String = id
    ) = TimelineClip(
        id = id, name = name, type = type, startMs = startMs, durationMs = durationMs,
        color = 0xFF112233L, groupId = groupId, parent = parent
    )

    private val glow = ClipFxLibrary.create(ClipFxLibrary.GLOW)!!

    /**
     * One project: an external Null, a group "g1" (itself nested in an outer group) with a
     * Null rig, a Controller, a keyframed + effect-carrying card parented to the rig and
     * connected to the Controller, a mask cutting the card, a stray child parented to the
     * EXTERNAL Null, a tracked layer following an in-group video, one following an outside
     * video, a nested group with a text — plus a solo top-level layer outside everything.
     */
    private fun fixture(): List<TimelineTrack> {
        val gLink = GroupOps.linkTo("g1", GroupOps.Pose.REST)
        val root = clip("g1", LayerType.GROUP, name = "My Group").copy(
            groupId = "outer-g",
            parent = ParentLink("outer-g", 3f, 4f, 90f, 15f)
        )
        val rig = clip("g1-null", LayerType.NULL, groupId = "g1", parent = gLink).copy(
            keyframes = mapOf(AnimProp.ROTATION to listOf(Keyframe(0L, 0f), Keyframe(4000L, 360f)))
        )
        val ctrl = clip("g1-ctrl", LayerType.CONTROLLER, groupId = "g1", parent = gLink)
        val card = clip("g1-card", LayerType.SHAPE, groupId = "g1", parent = ParentLink("g1-null", 10f, 20f, 100f, 0f, lockRotate = true)).copy(
            controllerId = "g1-ctrl",
            effects = listOf(glow),
            fillMode = com.example.model.FILL_IMAGE,
            fillImageUri = "file:///data/imports/pic.png",
            keyframes = mapOf(AnimProp.POSITION to listOf(Keyframe(0L, 1f, 2f), Keyframe(1000L, 3f, 4f)))
        )
        val mask = clip("g1-mask", LayerType.SHAPE, groupId = "g1", parent = gLink).copy(
            isMask = true, maskTargetId = "g1-card"
        )
        val stray = clip("g1-stray", LayerType.SHAPE, groupId = "g1", parent = ParentLink("ext-null", 0f, 0f, 100f, 0f)).copy(
            controllerId = "missing-ctrl", maskTargetId = "solo"
        )
        val video = clip("g1-video", LayerType.VIDEO, groupId = "g1", parent = gLink).copy(
            uri = "file:///data/imports/clip.mp4", mimeType = "video/mp4", sourceDurationMs = 4000L
        )
        val follow = clip("g1-follow", LayerType.SHAPE, groupId = "g1", parent = gLink).copy(
            trackFollow = TrackFollow("g1-video", longArrayOf(0L, 100L), floatArrayOf(0f, 5f), floatArrayOf(0f, 6f))
        )
        val followExt = clip("g1-follow-ext", LayerType.SHAPE, groupId = "g1", parent = gLink).copy(
            trackFollow = TrackFollow("ext-video", longArrayOf(0L), floatArrayOf(0f), floatArrayOf(0f))
        )
        val sub = clip("g1-sub", LayerType.GROUP, groupId = "g1", parent = gLink, name = "Sub")
        val subText = clip("g1-sub-text", LayerType.TEXT, startMs = 1500L, durationMs = 2000L,
            groupId = "g1-sub", parent = GroupOps.linkTo("g1-sub", GroupOps.Pose.REST)).copy(
            text = "আপনার লেখা",
            keyframes = mapOf(AnimProp.OPACITY to listOf(Keyframe(0L, 0f), Keyframe(500L, 100f)))
        )
        val extNull = clip("ext-null", LayerType.NULL)
        val solo = clip("solo", LayerType.TEXT)
        return listOf(
            lane(extNull), lane(root), lane(rig), lane(ctrl), lane(card), lane(mask),
            lane(stray), lane(video), lane(follow), lane(followExt), lane(sub), lane(subText),
            lane(solo)
        )
    }

    private fun captured(): SavedTemplate =
        UserTemplateOps.capture(fixture(), "g1", "My Template", "tpl-1", 4242L)!!

    @Test
    fun captureTakesTheWholeSubtreeRootFirst() {
        val tpl = captured()
        assertEquals("tpl-1", tpl.id)
        assertEquals("My Template", tpl.name)
        assertEquals(4242L, tpl.createdAtMs)
        assertEquals(
            listOf("g1", "g1-null", "g1-ctrl", "g1-card", "g1-mask", "g1-stray", "g1-video",
                "g1-follow", "g1-follow-ext", "g1-sub", "g1-sub-text"),
            tpl.clips.map { it.id }
        )
        // Nothing from outside the group is captured.
        assertFalse(tpl.clips.any { it.id == "ext-null" || it.id == "solo" })
    }

    @Test
    fun captureNormalisesTheRoot() {
        val root = captured().clips.first()
        assertEquals("My Template", root.name)          // the group takes the template's name
        assertNull(root.groupId)                        // it was nested: now a top-level root
        assertNull(root.parent)
        assertNull(root.chainId)
    }

    @Test
    fun captureKeepsInternalLinksAndContent() {
        val byId = captured().clips.associateBy { it.id }
        val card = byId.getValue("g1-card")
        assertEquals("g1-null", card.parent?.id)        // parented to the in-group rig
        assertTrue(card.parent!!.lockRotate)
        assertEquals("g1-ctrl", card.controllerId)      // connected to the in-group Controller
        assertEquals(listOf(glow), card.effects)        // effects ride along
        assertEquals(2, card.keyframes.getValue(AnimProp.POSITION).size)
        assertEquals("g1-card", byId.getValue("g1-mask").maskTargetId)
        assertEquals("g1-video", byId.getValue("g1-follow").trackFollow?.videoId)
        assertEquals("g1-sub", byId.getValue("g1-sub-text").groupId)
        assertEquals("g1-sub", byId.getValue("g1-sub-text").parent?.id)
        assertEquals("আপনার লেখা", byId.getValue("g1-sub-text").text)
        assertEquals(2, byId.getValue("g1-null").keyframes.getValue(AnimProp.ROTATION).size)
    }

    @Test
    fun captureDropsLinksPointingOutside() {
        val byId = captured().clips.associateBy { it.id }
        val stray = byId.getValue("g1-stray")
        // The stray was parented to the external Null: the link is dropped and the group
        // child falls back to the REST group link, so it stays inside its group.
        assertEquals("g1", stray.parent?.id)
        assertTrue(stray.parent!!.refX == GroupOps.Pose.REST.x && stray.parent!!.refScale == GroupOps.Pose.REST.scalePercent)
        assertNull(stray.controllerId)                  // the Controller was outside / missing
        assertNull(stray.maskTargetId)                  // the mask target was outside
        assertNull(byId.getValue("g1-follow-ext").trackFollow)   // followed an outside video
    }

    @Test
    fun captureOfANonGroupIsNull() {
        assertNull(UserTemplateOps.capture(fixture(), "solo", "X", "t", 0L))
        assertNull(UserTemplateOps.capture(fixture(), "missing", "X", "t", 0L))
    }

    @Test
    fun instantiateRemapsEverythingAndShiftsToThePlayhead() {
        val tpl = captured()
        val lanes = UserTemplateOps.instantiate(tpl, insertAtMs = 9000L)
        assertEquals(tpl.clips.size, lanes.size)
        val newIds = lanes.mapTo(HashSet()) { it.id }
        // Fresh ids: none of the stored ones, all unique.
        assertTrue(newIds.none { it in tpl.clips.map { c -> c.id }.toSet() })
        assertEquals(lanes.size, newIds.size)
        val root = lanes.first()
        assertEquals(9000L, root.startMs)               // the group lands on the playhead
        assertEquals("My Template", root.name)
        assertNull(root.groupId)
        assertNull(root.parent)
        // Children keep their offsets from the group.
        val orig = tpl.clips.associateBy { it.name }
        for (c in lanes) {
            val o = orig.getValue(c.name)
            assertEquals(o.startMs - tpl.clips.first().startMs, c.startMs - root.startMs)
            assertEquals(o.durationMs, c.durationMs)
        }
        // Every internal reference points at the inserted counterpart (by name).
        val byName = lanes.associateBy { it.name }
        fun counterpartOf(ref: String?, from: SavedTemplate): String? =
            ref?.let { r -> from.clips.first { it.id == r }.name }
        val card = byName.getValue("g1-card")
        assertEquals(byName.getValue("g1-null").id, card.parent?.id)
        assertEquals(byName.getValue("g1-ctrl").id, card.controllerId)
        assertEquals(listOf(glow), card.effects)
        assertEquals(orig.getValue("g1-card").keyframes, card.keyframes)
        assertEquals(card.id, byName.getValue("g1-mask").maskTargetId)
        assertEquals(byName.getValue("g1-video").id, byName.getValue("g1-follow").trackFollow?.videoId)
        assertNull(byName.getValue("g1-follow-ext").trackFollow)
        assertEquals(byName.getValue("Sub").id, byName.getValue("g1-sub-text").groupId)
        assertEquals(byName.getValue("Sub").id, byName.getValue("g1-sub-text").parent?.id)
        // The stray's fallback REST link now targets the inserted group.
        assertEquals(root.id, byName.getValue("g1-stray").parent?.id)
        // And nothing references an id that is not part of the insert.
        for (c in lanes) {
            assertTrue(c.groupId == null || c.groupId in newIds)
            assertTrue(c.parent == null || c.parent!!.id in newIds)
            assertTrue(c.controllerId == null || c.controllerId in newIds)
            assertTrue(c.maskTargetId == null || c.maskTargetId in newIds)
        }
        assertEquals("g1-null", counterpartOf(tpl.clips.first { it.name == "g1-card" }.parent?.id, tpl))
    }

    @Test
    fun twoInsertsAreIndependentAndTheTemplateIsUntouched() {
        val tpl = captured()
        val a = UserTemplateOps.instantiate(tpl, 0L)
        val b = UserTemplateOps.instantiate(tpl, 0L)
        val aIds = a.mapTo(HashSet()) { it.id }
        val bIds = b.mapTo(HashSet()) { it.id }
        assertTrue(aIds.intersect(bIds).isEmpty())
        // "Editing" an inserted group (the editor's copy-style edits) cannot reach the
        // stored template or the other insert.
        val editedA = a.map { if (it.type == LayerType.GROUP && it.groupId == null) it.copy(name = "Changed", transformX = 99f) else it }
        assertEquals("Changed", editedA.first().name)
        assertEquals("My Template", tpl.clips.first().name)
        assertEquals("My Template", b.first().name)
        assertTrue(tpl.clips.first().transformX == 0f)
    }

    @Test
    fun mediaRefsAreCollectedInOrder() {
        val refs = UserTemplateOps.mediaRefsOf(UserTemplateOps.subtreeOf(fixture(), "g1"))
        assertEquals(listOf("file:///data/imports/pic.png", "file:///data/imports/clip.mp4"), refs)
    }

    @Test
    fun mediaCopiesSurviveTheSourceProjectsImports() {
        val base = Files.createTempDirectory("utpl_media").toFile()
        try {
            val filesDir = File(base, "files").apply { mkdirs() }
            val imports = File(filesDir, "imports").apply { mkdirs() }
            File(imports, "pic.png").writeBytes(byteArrayOf(1, 2, 3, 4))
            File(imports, "clip.mp4").writeBytes(byteArrayOf(5, 6, 7))
            val cutoutSrc = File(filesDir, "cutouts/abc").apply { mkdirs() }
            File(cutoutSrc, "frame1.png").writeBytes(byteArrayOf(8, 9))

            val gLink = GroupOps.linkTo("mg", GroupOps.Pose.REST)
            val group = clip("mg", LayerType.GROUP, name = "Media Group")
            val image = clip("mg-img", LayerType.SHAPE, groupId = "mg", parent = gLink).copy(
                fillMode = com.example.model.FILL_IMAGE,
                fillImageUri = "content://com.example.fileprovider/imports/pic.png"
            )
            val video = clip("mg-vid", LayerType.VIDEO, groupId = "mg", parent = gLink).copy(
                uri = "file://" + File(imports, "clip.mp4").absolutePath
            )
            val cut = clip("mg-cut", LayerType.VIDEO, groupId = "mg", parent = gLink).copy(
                uri = "file://" + File(imports, "clip.mp4").absolutePath,
                cutoutDir = cutoutSrc.absolutePath, cutoutFrames = 1
            )
            val tracks = listOf(lane(group), lane(image), lane(video), lane(cut))

            // What the store does: copy first, then capture through the rewrite map.
            val tplMedia = File(base, "templates/t1/media")
            val subtree = UserTemplateOps.subtreeOf(tracks, "mg")
            val map = UserTemplateFiles.copyMediaInto(UserTemplateOps.mediaRefsOf(subtree), filesDir, tplMedia)
            val saved = UserTemplateOps.capture(tracks, "mg", "Media Group", "t1", 1L, map)!!
            val byId = saved.clips.associateBy { it.id }

            val imgRef = byId.getValue("mg-img").fillImageUri!!
            assertTrue(imgRef.startsWith("file://"))
            assertTrue(imgRef.contains("templates"))
            assertNotNull(UserTemplateFiles.resolveRefFile(imgRef, filesDir))
            assertTrue(UserTemplateFiles.resolveRefFile(imgRef, filesDir)!!.exists())
            val cutRef = byId.getValue("mg-cut").cutoutDir!!
            assertTrue(File(cutRef, "frame1.png").exists())

            // The source project's files go away: the template's copies still resolve.
            imports.deleteRecursively()
            cutoutSrc.deleteRecursively()
            assertTrue(UserTemplateFiles.resolveRefFile(imgRef, filesDir)!!.exists())
            assertTrue(UserTemplateFiles.resolveRefFile(byId.getValue("mg-vid").uri!!, filesDir)!!.exists())
            assertTrue(File(cutRef, "frame1.png").exists())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun unresolvableMediaKeepsTheAuthoredReference() {
        val base = Files.createTempDirectory("utpl_missing").toFile()
        try {
            val map = UserTemplateFiles.copyMediaInto(
                listOf("content://com.other.provider/external/123", "file:///nope/gone.png"),
                base, File(base, "media")
            )
            assertTrue(map.isEmpty())
        } finally {
            base.deleteRecursively()
        }
    }
}
