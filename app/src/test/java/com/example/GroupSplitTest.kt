package com.example

import com.example.model.GroupOps
import com.example.model.LaneLayout
import com.example.model.LayerType
import com.example.model.TimelineClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Group split: two groups, the layers inside are cut at the playhead or move to the second group. */
class GroupSplitTest {
    private fun clip(id: String, type: LayerType, start: Long, dur: Long) =
        TimelineClip(id = id, name = id, type = type, startMs = start, durationMs = dur, color = 0)

    @Test
    fun splitCutsSpanningLayersAndMovesLaterOnes() {
        val lanes = listOf(
            clip("a", LayerType.TEXT, 0, 4000),
            clip("b", LayerType.SHAPE, 3000, 3000),
            clip("c", LayerType.TEXT, 0, 1000)
        ).map { LaneLayout.newLane(listOf(it)) }
        val grouped = GroupOps.group(lanes, setOf("a", "b", "c"))!!
        val s = GroupOps.splitGroup(LaneLayout.normalize(grouped.tracks), grouped.groupId, 2000L)
        assertNotNull(s)
        val all = s!!.tracks.flatMap { it.clips }

        val g1 = all.first { it.id == grouped.groupId }
        val g2 = all.first { it.id == s.groupId }
        assertEquals(LayerType.GROUP, g2.type)
        assertEquals(2000L, g1.durationMs)
        assertEquals(2000L, g2.startMs)
        assertEquals(4000L, g2.durationMs)

        // "a" spans the playhead: first half stays in the first group, second half is a new layer in the second group
        assertEquals(2000L, all.first { it.id == "a" }.durationMs)
        val inSecond = all.filter { it.groupId == s.groupId }
        assertEquals(2, inSecond.size)               // second half of "a" and the whole "b"
        assertTrue(inSecond.any { it.id == "b" && it.startMs == 3000L })
        assertTrue(inSecond.any { it.id != "b" && it.startMs == 2000L && it.durationMs == 2000L })
        assertTrue(inSecond.all { it.parent?.id == s.groupId })

        // "c" ended before the playhead: untouched, still in the first group
        assertEquals(grouped.groupId, all.first { it.id == "c" }.groupId)
        assertEquals(1000L, all.first { it.id == "c" }.durationMs)
    }

    @Test
    fun splitNearAnEdgeDoesNothing() {
        val lanes = listOf(clip("a", LayerType.TEXT, 0, 4000)).map { LaneLayout.newLane(listOf(it)) }
        val grouped = GroupOps.group(lanes, setOf("a"))!!
        assertNull(GroupOps.splitGroup(grouped.tracks, grouped.groupId, 200L))
        assertNull(GroupOps.splitGroup(grouped.tracks, grouped.groupId, 3900L))
    }
}
