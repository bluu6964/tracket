package com.example

import com.example.model.CornerPin
import com.example.model.CornerPinApply
import com.example.model.LayerType
import com.example.model.PlanarResult
import com.example.model.PlanarSample
import com.example.model.TimelineClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CornerPinTest {

    private fun pin(): CornerPin {
        val times = longArrayOf(1000L, 1040L, 1080L)
        val q = FloatArray(24) { it.toFloat() / 100f }
        return CornerPin("v", times, q, 16f / 9f)
    }

    @Test
    fun indexAtHoldsTheFrameUntilTheNextOne() {
        val p = pin()
        assertEquals(0, p.indexAt(500L))      // before the first frame: the first entry
        assertEquals(0, p.indexAt(1000L))
        assertEquals(0, p.indexAt(1039L))
        assertEquals(1, p.indexAt(1040L))
        assertEquals(1, p.indexAt(1078L))
        assertEquals(2, p.indexAt(1079L))     // 2 ms rounding tolerance
        assertEquals(2, p.indexAt(99999L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun quadSizeMustMatchTheTimes() {
        CornerPin("v", longArrayOf(0L, 1L), FloatArray(8), 1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun planarSampleNeedsEightNumbers() {
        PlanarSample(0L, FloatArray(6))
    }

    @Test
    fun emptyResultGivesNoPin() {
        val clip = TimelineClip(id = "v", name = "v", type = LayerType.VIDEO, startMs = 0L, durationMs = 5000L, color = 0L)
        assertNull(CornerPinApply.from(clip, PlanarResult("v", emptyList(), 1.78f)))
    }

    @Test
    fun resultBecomesPinWithProjectTimes() {
        val clip = TimelineClip(id = "v", name = "v", type = LayerType.VIDEO, startMs = 2000L, durationMs = 5000L, color = 0L)
        val s = listOf(
            PlanarSample(0L, floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)),
            PlanarSample(40L, floatArrayOf(0.11f, 0.1f, 0.91f, 0.1f, 0.91f, 0.9f, 0.11f, 0.9f))
        )
        val pin = CornerPinApply.from(clip, PlanarResult("v", s, 1.5f))
        assertNotNull(pin)
        assertEquals(2, pin!!.size)
        assertEquals("v", pin.videoId)
        assertEquals(1.5f, pin.aspect, 0f)
        assertEquals(2000L, pin.timesMs[0])      // first frame sits at the layer start
        assertEquals(2040L, pin.timesMs[1])      // speed 1: 40 ms of video = 40 ms of project
        assertEquals(0.91f, pin.quad[8 + 2], 1e-6f)
    }
}
