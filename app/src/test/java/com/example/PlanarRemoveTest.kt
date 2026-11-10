package com.example

import com.example.engine.RemoveBuilder
import com.example.model.CornerPin
import com.example.model.PlanarRemove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanarRemoveTest {
    private fun remove(n: Int, gains: FloatArray = FloatArray(n) { 1f }): PlanarRemove {
        val times = LongArray(n) { 1000L + it * 33L }
        val quad = FloatArray(n * 8) { 0.1f + (it % 8) * 0.05f }
        return PlanarRemove(CornerPin("v", times, quad, 16f / 9f), gains, "/none", true)
    }

    @Test
    fun coversOnlyTheTrackedPart() {
        val r = remove(10)
        assertFalse(r.coversAt(500L))
        assertTrue(r.coversAt(1000L))
        assertTrue(r.coversAt(1000L + 9 * 33L))
        assertFalse(r.coversAt(1000L + 9 * 33L + 500L))
    }

    @Test
    fun gainFollowsTheFrame() {
        val r = remove(3, floatArrayOf(1f, 1.2f, 0.9f))
        assertEquals(1f, r.gainAt(1000L), 1e-6f)
        assertEquals(1.2f, r.gainAt(1033L), 1e-6f)
        assertEquals(0.9f, r.gainAt(1066L), 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun gainsMustMatchThePin() {
        remove(3, FloatArray(2))
    }

    @Test
    fun gainsAreRelativeSmoothedAndClamped() {
        val flat = RemoveBuilder.gainsFrom(List(6) { 100f }, 6)
        flat.forEach { assertEquals(1f, it, 1e-5f) }
        val g = RemoveBuilder.gainsFrom(listOf(100f, 100f, 100f, 400f, 100f, 100f), 6)
        g.forEach { assertTrue(it in 0.5f..2f) }
        // a single spike is spread out, not copied
        assertTrue(g[3] < 1.7f)
        // unknown light (0) or a size mismatch means "no change"
        RemoveBuilder.gainsFrom(listOf(0f, 100f), 2).forEach { assertEquals(1f, it, 1e-6f) }
        RemoveBuilder.gainsFrom(listOf(100f), 3).forEach { assertEquals(1f, it, 1e-6f) }
    }
}
