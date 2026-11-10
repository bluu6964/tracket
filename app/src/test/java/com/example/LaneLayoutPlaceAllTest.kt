package com.example

import com.example.model.LaneLayout
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.TimelineTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LaneLayoutPlaceAllTest {

    private fun clip(id: String) = TimelineClip(
        id = id, name = id, type = LayerType.TEXT, startMs = 0, durationMs = 1000, color = 0xFF000000
    )

    @Test
    fun everyClipGetsItsOwnLane_firstOnTop_oldLanesKeptBelow() {
        val old = listOf(TimelineTrack(id = "old", name = "L", clips = listOf(clip("o"))))
        val out = LaneLayout.placeAll(old, listOf(clip("a"), clip("b"), clip("c")))
        assertEquals(listOf("a", "b", "c", "o"), out.map { it.clips.single().id })
        assertEquals(4, out.map { it.id }.toSet().size)
    }

    @Test
    fun emptyListReturnsTheSameTracks() {
        val old = emptyList<TimelineTrack>()
        assertSame(old, LaneLayout.placeAll(old, emptyList()))
    }
}
