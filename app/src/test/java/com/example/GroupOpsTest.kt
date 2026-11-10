package com.example

import com.example.engine.ParentRig
import com.example.model.GroupOps
import com.example.model.LaneLayout
import com.example.model.LayerType
import com.example.model.ParentLink
import com.example.model.TimelineClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real grouping: the Group layer, the layers that follow it, and what it hands down (move, opacity, eye). */
class GroupOpsTest {
    private fun clip(id: String, type: LayerType, start: Long = 0, dur: Long = 1000, x: Float = 0f, opacity: Float = 100f) =
        TimelineClip(id = id, name = id, type = type, startMs = start, durationMs = dur, color = 0, transformX = x, opacity = opacity)

    private fun lanes(vararg c: TimelineClip) = c.map { LaneLayout.newLane(listOf(it)) }

    private fun byId(t: List<com.example.model.TimelineTrack>, id: String) = t.flatMap { it.clips }.first { it.id == id }

    @Test
    fun groupingMakesOneGroupLayerAboveAndLinksTheLayers() {
        val t = lanes(clip("top", LayerType.TEXT, 0, 1000), clip("mid", LayerType.SHAPE, 500, 2000), clip("bottom", LayerType.VIDEO, 0, 500))
        val r = GroupOps.group(t, setOf("top", "mid"))!!
        val g = byId(r.tracks, r.groupId)
        assertEquals(LayerType.GROUP, g.type)
        assertEquals(0L, g.startMs)
        assertEquals(2500L, g.endMs)
        assertEquals(r.groupId, byId(r.tracks, "top").groupId)
        assertEquals(r.groupId, byId(r.tracks, "top").parent?.id)
        assertNull(byId(r.tracks, "bottom").groupId)
        // the group lane sits right above the topmost checked lane; every layer keeps its own lane
        assertEquals(4, r.tracks.size)
        assertEquals(r.groupId, r.tracks[0].clips[0].id)
    }

    @Test
    fun audioCameraAndChainsCannotBeGrouped() {
        val t = lanes(clip("a", LayerType.AUDIO), clip("c", LayerType.CAMERA), clip("ch", LayerType.CHAIN))
        assertNull(GroupOps.group(t, setOf("a", "c", "ch")))
    }

    @Test
    fun aCheckedGroupTakesTheOtherCheckedLayersIn() {
        val first = GroupOps.group(lanes(clip("a", LayerType.TEXT), clip("b", LayerType.TEXT), clip("c", LayerType.SHAPE, 3000, 1000)), setOf("a", "b"))!!
        val second = GroupOps.group(first.tracks, setOf(first.groupId, "c"))!!
        assertEquals(first.groupId, second.groupId)  // no second group was made
        assertEquals(1, second.tracks.flatMap { it.clips }.count { it.type == LayerType.GROUP })
        assertEquals(first.groupId, byId(second.tracks, "c").groupId)
        assertEquals(4000L, byId(second.tracks, first.groupId).endMs)  // the group grew to cover "c"
    }

    @Test
    fun twoGroupsCheckedMakeAGroupInsideAGroup() {
        val g1 = GroupOps.group(lanes(clip("a", LayerType.TEXT), clip("b", LayerType.TEXT)), setOf("a"))!!
        val g2 = GroupOps.group(g1.tracks, setOf("b"))!!
        val outer = GroupOps.group(g2.tracks, setOf(g1.groupId, g2.groupId))!!
        assertEquals(outer.groupId, byId(outer.tracks, g1.groupId).groupId)
        assertEquals(outer.groupId, byId(outer.tracks, g2.groupId).parent?.id)
        assertEquals(4, GroupOps.descendantsOf(outer.tracks, outer.groupId).size)
    }

    @Test
    fun groupFollowsItsLayersWhenTheyMove() {
        val g = GroupOps.group(lanes(clip("a", LayerType.TEXT, 0, 1000), clip("b", LayerType.TEXT, 2000, 1000)), setOf("a", "b"))!!
        val moved = g.tracks.map { lane -> lane.copy(clips = lane.clips.map { if (it.id == "b") it.copy(startMs = 5000) else it }) }
        val fitted = GroupOps.fitGroups(moved)
        assertEquals(6000L, byId(fitted, g.groupId).endMs)
    }

    @Test
    fun aLayerWhoseGroupIsGoneBecomesNormal() {
        val c = clip("a", LayerType.TEXT).copy(groupId = "gone", parent = ParentLink("gone", 0f, 0f, 100f, 0f))
        val fitted = GroupOps.fitGroups(lanes(c))
        assertNull(byId(fitted, "a").groupId)
        assertNull(byId(fitted, "a").parent)
    }

    @Test
    fun ungroupBringsTheLayersBackAndRemovesTheGroup() {
        val g = GroupOps.group(lanes(clip("a", LayerType.TEXT), clip("b", LayerType.SHAPE)), setOf("a", "b"))!!
        val out = GroupOps.ungroup(g.tracks, g.groupId)
        assertEquals(2, out.flatMap { it.clips }.size)
        assertTrue(out.flatMap { it.clips }.all { it.groupId == null && it.parent == null })
    }

    @Test
    fun ungroupBakesThePoseWhenAskedTo() {
        val g = GroupOps.group(lanes(clip("a", LayerType.TEXT, x = 10f)), setOf("a"))!!
        val out = GroupOps.ungroup(g.tracks, g.groupId, bake = { it.copy(transformX = it.transformX + 50f) })
        assertEquals(60f, byId(out, "a").transformX, 0.001f)
    }

    @Test
    fun duplicateCopiesTheGroupWithItsLayersAndRelinksThem() {
        val g = GroupOps.group(lanes(clip("a", LayerType.TEXT), clip("b", LayerType.SHAPE)), setOf("a", "b"))!!
        val d = GroupOps.duplicate(g.tracks, g.groupId)!!
        assertTrue(d.groupId != g.groupId)
        val kids = GroupOps.childrenOf(d.tracks, d.groupId)
        assertEquals(2, kids.size)
        assertTrue(kids.all { it.parent?.id == d.groupId })
        // the original is untouched
        assertEquals(2, GroupOps.childrenOf(d.tracks, g.groupId).size)
        assertEquals(6, d.tracks.flatMap { it.clips }.size)
    }

    // ---- what the group hands down (ParentRig) ----

    private fun rigOf(list: List<TimelineClip>) = ParentRig(list) { cl, _ -> cl }

    @Test
    fun groupMovesItsLayersLikeANull() {
        val link = ParentLink("g", 0f, 0f, 100f, 0f)
        val group = clip("g", LayerType.GROUP, x = 30f)
        val kid = clip("k", LayerType.TEXT, x = 5f).copy(groupId = "g", parent = link)
        val r = rigOf(listOf(group, kid)).adjusted(kid, 0)
        assertEquals(35f, r.transformX, 0.001f)
    }

    @Test
    fun groupOpacityIsNotHandedDownBecauseTheGroupIsDrawnAsOnePicture() {
        val inner = clip("in", LayerType.GROUP, opacity = 50f).copy(parent = ParentLink("out", 0f, 0f, 100f, 0f))
        val outer = clip("out", LayerType.GROUP, opacity = 50f)
        val kid = clip("k", LayerType.TEXT, opacity = 80f).copy(parent = ParentLink("in", 0f, 0f, 100f, 0f))
        val rig = rigOf(listOf(outer, inner, kid))
        assertEquals(80f, rig.adjusted(kid, 0).opacity, 0.01f)   // the layer keeps its own opacity
        assertEquals(50f, rig.adjusted(inner, 0).opacity, 0.01f) // and so does a group inside a group
        val fp = FloatArray(17).also { it[2] = 1f; it[3] = 1f; it[5] = 1f }
        val video = clip("v", LayerType.VIDEO).copy(parent = ParentLink("out", 0f, 0f, 100f, 0f))
        rigOf(listOf(outer, video)).applyToFp(video, 0, fp, 1f)
        assertEquals(1f, fp[5], 0.001f)
        // only Ungroup asks for it: the layers get the opacity the groups had (0.5 * 0.5)
        assertEquals(0.25f, rig.inheritedGroupOpacity(kid, 0), 0.001f)
    }

    // ---- one level at a time (Edit Group) ----

    @Test
    fun theTopLevelShowsTheGroupAsOneLayerAndHidesWhatIsInsideIt() {
        val g = GroupOps.group(lanes(clip("a", LayerType.TEXT), clip("b", LayerType.SHAPE), clip("c", LayerType.VIDEO)), setOf("a", "b"))!!
        val top = GroupOps.scopeTracks(g.tracks, null).flatMap { it.clips }.map { it.id }
        assertEquals(setOf(g.groupId, "c"), top.toSet())
        assertEquals(2, top.size)
    }

    @Test
    fun insideAGroupOnlyItsLayersAreShown() {
        val g = GroupOps.group(lanes(clip("a", LayerType.TEXT), clip("b", LayerType.SHAPE), clip("c", LayerType.VIDEO)), setOf("a", "b"))!!
        val inside = GroupOps.scopeTracks(g.tracks, g.groupId).flatMap { it.clips }.map { it.id }
        assertEquals(setOf("a", "b"), inside.toSet())
        // a name that is not a group (any more) means the top level
        assertEquals(GroupOps.scopeTracks(g.tracks, null), GroupOps.scopeTracks(g.tracks, "nope"))
        assertNull(GroupOps.validScope(g.tracks, "a"))   // a normal layer is not a level
        assertEquals(g.groupId, GroupOps.validScope(g.tracks, g.groupId))
    }

    @Test
    fun withoutGroupsEveryLevelIsTheWholeList() {
        val t = lanes(clip("a", LayerType.TEXT), clip("b", LayerType.SHAPE))
        assertTrue(GroupOps.scopeTracks(t, null) === t)
    }

    @Test
    fun aGroupInsideAGroupIsOneLayerOfTheOuterLevel() {
        val g1 = GroupOps.group(lanes(clip("a", LayerType.TEXT), clip("b", LayerType.TEXT)), setOf("a"))!!
        val g2 = GroupOps.group(g1.tracks, setOf("b"))!!
        val outer = GroupOps.group(g2.tracks, setOf(g1.groupId, g2.groupId))!!
        assertEquals(outer.groupId, byId(outer.tracks, g1.groupId).groupId)
        assertEquals(setOf(outer.groupId), GroupOps.scopeTracks(outer.tracks, null).flatMap { it.clips }.map { it.id }.toSet())
        assertEquals(setOf(g1.groupId, g2.groupId), GroupOps.scopeTracks(outer.tracks, outer.groupId).flatMap { it.clips }.map { it.id }.toSet())
        assertEquals(setOf("a"), GroupOps.scopeTracks(outer.tracks, g1.groupId).flatMap { it.clips }.map { it.id }.toSet())
    }

    @Test
    fun groupingInsideAGroupMakesTheNewGroupPartOfThatGroup() {
        val g = GroupOps.group(lanes(clip("a", LayerType.TEXT), clip("b", LayerType.SHAPE), clip("c", LayerType.SHAPE)), setOf("a", "b", "c"))!!
        val inner = GroupOps.group(g.tracks, setOf("a", "b"))!!
        val newGroup = byId(inner.tracks, inner.groupId)
        assertEquals(g.groupId, newGroup.groupId)
        assertEquals(g.groupId, newGroup.parent?.id)
        assertEquals(inner.groupId, byId(inner.tracks, "a").groupId)
        assertEquals(g.groupId, byId(inner.tracks, "c").groupId)
    }

    // ---- trimming the group ----

    @Test
    fun aTrimmedGroupKeepsItsOwnLengthButAnUntrimmedOneFollowsItsLayers() {
        val g = GroupOps.group(lanes(clip("a", LayerType.TEXT, 0, 1000), clip("b", LayerType.TEXT, 2000, 1000)), setOf("a", "b"))!!
        val trimmed = g.tracks.map { lane ->
            lane.copy(clips = lane.clips.map { if (it.id == g.groupId) it.copy(durationMs = 1500, groupFreeLength = true) else it })
        }
        assertEquals(1500L, byId(GroupOps.fitGroups(trimmed), g.groupId).durationMs)
        // layers moved further out: a trimmed group still does not grow, an untrimmed one does
        val moved = trimmed.map { lane -> lane.copy(clips = lane.clips.map { if (it.id == "b") it.copy(startMs = 5000) else it }) }
        assertEquals(1500L, byId(GroupOps.fitGroups(moved), g.groupId).durationMs)
        val movedFree = g.tracks.map { lane -> lane.copy(clips = lane.clips.map { if (it.id == "b") it.copy(startMs = 5000) else it }) }
        assertEquals(6000L, byId(GroupOps.fitGroups(movedFree), g.groupId).endMs)
    }

    @Test
    fun aHiddenGroupHidesTheLayersInsideIt() {
        val group = clip("g", LayerType.GROUP).copy(isVisible = false)
        val kid = clip("k", LayerType.TEXT).copy(parent = ParentLink("g", 0f, 0f, 100f, 0f))
        val rig = rigOf(listOf(group, kid))
        assertTrue(rig.hiddenByGroup(kid))
        assertEquals(0f, rig.adjusted(kid, 0).opacity, 0.001f)
        val fp = FloatArray(17).also { it[5] = 1f; it[2] = 1f; it[3] = 1f }
        rig.applyToFp(kid, 0, fp, 1f)
        assertEquals(0f, fp[5], 0.001f)
    }

    @Test
    fun aNullParentDoesNotChangeOpacity() {
        val n = clip("n", LayerType.NULL, opacity = 10f)
        val kid = clip("k", LayerType.TEXT, opacity = 80f).copy(parent = ParentLink("n", 0f, 0f, 100f, 0f))
        assertEquals(80f, rigOf(listOf(n, kid)).adjusted(kid, 0).opacity, 0.001f)
        assertNotNull(rigOf(listOf(n, kid)).poseOf("n", 0))
    }
}
