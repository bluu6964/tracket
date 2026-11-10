package com.example

import com.example.engine.ParentRig
import com.example.model.LayerType
import com.example.model.ParentLink
import com.example.model.TimelineClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentRigTest {
    private fun clip(id: String, type: LayerType, x: Float = 0f, y: Float = 0f, scale: Float = 100f, rot: Float = 0f, parent: ParentLink? = null) =
        TimelineClip(
            id = id, name = id, type = type, startMs = 0, durationMs = 1000, color = 0,
            transformX = x, transformY = y, scalePercent = scale, rotationDeg = rot, parent = parent
        )

    private val link = ParentLink("n", 0f, 0f, 100f, 0f)

    @Test
    fun childStaysWhereItIsWhileNullDoesNotMove() {
        val n = clip("n", LayerType.NULL)
        val c = clip("c", LayerType.TEXT, x = 100f, y = 20f, parent = link)
        val r = ParentRig(listOf(n, c)) { cl, _ -> cl }.adjusted(c, 0)
        assertEquals(100f, r.transformX, 0.001f)
        assertEquals(20f, r.transformY, 0.001f)
        assertEquals(100f, r.scalePercent, 0.001f)
    }

    @Test
    fun childFollowsMoveTurnAndScale() {
        val n = clip("n", LayerType.NULL, x = 10f, y = 5f, scale = 200f, rot = 90f)
        val c = clip("c", LayerType.TEXT, x = 100f, y = 0f, rot = 10f, parent = link)
        val r = ParentRig(listOf(n, c)) { cl, _ -> cl }.adjusted(c, 0)
        // (100, 0) turned 90 degrees clockwise (y down) = (0, 100), twice as big = (0, 200), then moved by (10, 5)
        assertEquals(10f, r.transformX, 0.01f)
        assertEquals(205f, r.transformY, 0.01f)
        assertEquals(200f, r.scalePercent, 0.001f)
        assertEquals(100f, r.rotationDeg, 0.001f)
    }

    @Test
    fun videoNumbersFollowToo() {
        val n = clip("n", LayerType.NULL, x = 10f, rot = 180f)
        val c = clip("c", LayerType.VIDEO, parent = link)
        val fp = FloatArray(17).also { it[0] = 40f; it[2] = 1f; it[3] = 1f }
        ParentRig(listOf(n, c)) { cl, _ -> cl }.applyToFp(c, 0, fp, 2f)
        assertEquals(-40f + 20f, fp[0], 0.01f)
        assertEquals(0f, fp[1], 0.01f)
        assertEquals(180f, fp[4], 0.001f)
    }

    @Test
    fun loopsAreRefused() {
        val a = clip("a", LayerType.NULL, parent = ParentLink("b", 0f, 0f, 100f, 0f))
        val b = clip("b", LayerType.NULL)
        val rig = ParentRig(listOf(a, b)) { cl, _ -> cl }
        assertTrue(rig.wouldLoop("b", "a"))
        assertTrue(!rig.wouldLoop("a", "b"))
    }
}
