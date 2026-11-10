package com.example

import com.example.model.LaneLayout
import com.example.model.LayerType
import com.example.model.TimelineClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainLaneTest {
    private fun clip(id: String, type: LayerType, start: Long, dur: Long, chainId: String? = null) =
        TimelineClip(id = id, name = id, type = type, startMs = start, durationMs = dur, color = 0, chainId = chainId)

    private fun chainLane() = LaneLayout.place(emptyList(), clip("g", LayerType.CHAIN, 0, 4000))

    @Test
    fun layersSitSideBySideInTheChainLane() {
        var t = chainLane()
        t = LaneLayout.placeInChain(t, "g", clip("a", LayerType.TEXT, 1000, 2000))
        t = LaneLayout.placeInChain(t, "g", clip("b", LayerType.SHAPE, 5000, 1000))
        assertEquals(1, t.size)
        assertEquals(3, t[0].clips.size)
        // the chain grew to cover both layers
        val g = t[0].clips.first { it.id == "g" }
        assertEquals(1000L, g.startMs)
        assertEquals(6000L, g.endMs)
    }

    @Test
    fun aNewLayerGoesBehindALayerThatIsAlreadyThere() {
        var t = chainLane()
        t = LaneLayout.placeInChain(t, "g", clip("a", LayerType.TEXT, 1000, 2000))
        t = LaneLayout.placeInChain(t, "g", clip("b", LayerType.TEXT, 1500, 1000))
        val b = t[0].clips.first { it.id == "b" }
        assertEquals(3000L, b.startMs)
        assertEquals("g", b.chainId)
        t = LaneLayout.placeInChain(t, "g", clip("c", LayerType.TEXT, 500, 1000))  // 500..1500 hits a -> after a and b
        val c = t[0].clips.first { it.id == "c" }
        assertEquals(4000L, c.startMs)
    }

    @Test
    fun normalizeKeepsTheChainAndItsLayersTogether() {
        var t = chainLane()
        t = LaneLayout.placeInChain(t, "g", clip("a", LayerType.TEXT, 0, 1000))
        t = LaneLayout.placeInChain(t, "g", clip("b", LayerType.TEXT, 2000, 1000))
        val n = LaneLayout.normalize(t)
        assertEquals(1, n.size)
        assertEquals(3, n[0].clips.size)
    }

    @Test
    fun aLayerWithoutItsChainBecomesNormal() {
        val lane = LaneLayout.newLane(listOf(clip("a", LayerType.TEXT, 0, 1000, chainId = "gone")))
        val n = LaneLayout.normalize(listOf(lane))
        assertEquals(null, n[0].clips[0].chainId)
        assertTrue(LaneLayout.normalize(listOf(lane)).size == 1)
    }
}
