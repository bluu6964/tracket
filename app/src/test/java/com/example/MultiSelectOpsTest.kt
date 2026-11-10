package com.example

import com.example.model.LayerType
import com.example.model.MultiAction
import com.example.model.MultiBarMode
import com.example.model.MultiSelectOps
import com.example.model.TimelineClip
import com.example.model.TimelineTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultiSelectOpsTest {

    private fun clip(id: String, start: Long, dur: Long) = TimelineClip(
        id = id, name = id, type = LayerType.SHAPE, startMs = start, durationMs = dur, color = 0xFF000000
    )

    private fun lanes(vararg c: TimelineClip) = c.map { TimelineTrack(id = "lane_${it.id}", name = "L", clips = listOf(it)) }

    private fun find(t: List<TimelineTrack>, id: String) = t.flatMap { it.clips }.first { it.id == id }

    @Test
    fun barMode_followsPlayhead() {
        val sel = listOf(clip("a", 1000, 2000), clip("b", 5000, 1000))
        assertEquals(MultiBarMode.START, MultiSelectOps.barMode(sel, 0))
        assertEquals(MultiBarMode.SPLIT, MultiSelectOps.barMode(sel, 1500))
        assertEquals(MultiBarMode.END, MultiSelectOps.barMode(sel, 9000))
    }

    @Test
    fun split_cutsOnlyLayersUnderPlayhead() {
        val t = lanes(clip("a", 0, 4000), clip("b", 5000, 1000))
        val out = MultiSelectOps.apply(t, setOf("a", "b"), MultiAction.SPLIT, 1000)!!
        assertEquals(3, out.size)
        assertEquals(1000L, find(out, "a").durationMs)
        assertEquals(5000L, find(out, "b").startMs)
    }

    @Test
    fun deleteLeftAndRight() {
        val t = lanes(clip("a", 0, 4000))
        val left = MultiSelectOps.apply(t, setOf("a"), MultiAction.DELETE_LEFT, 1000)!!
        assertEquals(1000L, find(left, "a").startMs)
        assertEquals(3000L, find(left, "a").durationMs)
        val right = MultiSelectOps.apply(t, setOf("a"), MultiAction.DELETE_RIGHT, 1000)!!
        assertEquals(1000L, find(right, "a").durationMs)
    }

    @Test
    fun startAndEndOptions() {
        val t = lanes(clip("a", 2000, 1000))
        assertEquals(0L, find(MultiSelectOps.apply(t, setOf("a"), MultiAction.MOVE_START, 0)!!, "a").startMs)
        val ext = find(MultiSelectOps.apply(t, setOf("a"), MultiAction.EXTEND_START, 500)!!, "a")
        assertEquals(500L, ext.startMs)
        assertEquals(2500L, ext.durationMs)
        assertEquals(7000L, find(MultiSelectOps.apply(t, setOf("a"), MultiAction.MOVE_END, 8000)!!, "a").startMs)
        assertEquals(6000L, find(MultiSelectOps.apply(t, setOf("a"), MultiAction.EXTEND_END, 8000)!!, "a").durationMs)
    }

    @Test
    fun align_and_sequence() {
        val t = lanes(clip("a", 1000, 2000), clip("b", 4000, 1000))
        val ids = setOf("a", "b")
        val s = MultiSelectOps.apply(t, ids, MultiAction.ALIGN_START, 0)!!
        assertEquals(1000L, find(s, "b").startMs)
        val e = MultiSelectOps.apply(t, ids, MultiAction.ALIGN_END, 0)!!
        assertEquals(3000L, find(e, "a").startMs)
        val q = MultiSelectOps.apply(t, ids, MultiAction.SEQUENCE, 0)!!
        assertEquals(3000L, find(q, "b").startMs)
    }

    @Test
    fun noChange_returnsNull() {
        val t = lanes(clip("a", 0, 1000))
        assertNull(MultiSelectOps.apply(t, setOf("a"), MultiAction.ALIGN_START, 0))
    }
}
