package com.example

import com.example.model.ClipMaskLibrary
import com.example.model.MaskFrame
import com.example.model.MaskHandle
import com.example.model.MaskLayerMap
import com.example.model.MaskShape
import com.example.model.MaskTrack
import com.example.model.TrackResult
import com.example.model.TrackSample
import com.example.model.Pt
import com.example.model.hitHandle
import com.example.model.neighbourKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipMaskTest {
    private val asp = 16f / 9f

    @Test fun keysAnimateAndEnd() {
        var m = ClipMaskLibrary.create(MaskShape.RECT, asp).toggleKeyAt(0L)
        m = m.withStateAt(1000L, m.stateAt(0).copy(cx = 0.9f))
        assertEquals(2, m.keys.size)
        assertEquals(0.5f, m.stateAt(-5L).cx, 1e-3f)
        assertEquals(0.9f, m.stateAt(5000L).cx, 1e-3f)
        assertTrue(m.stateAt(500L).cx in 0.6f..0.8f)
        assertEquals(1, m.neighbourKey(0, true))
        val end = m.toggleKeyAt(1000L).toggleKeyAt(0L)
        assertTrue(!end.isAnimated)
    }

    @Test fun nativeArray() {
        val heart = ClipMaskLibrary.toNative(listOf(ClipMaskLibrary.heart(asp)), 0L)!!
        assertEquals(ClipMaskLibrary.STRIDE, heart.size)
        assertEquals(16f, heart[14], 0f)
        assertNull(ClipMaskLibrary.toNative(listOf(ClipMaskLibrary.create(MaskShape.PATH, asp)), 0L))
    }

    /** Object moves right 0.2 of the picture and turns 90 degrees / doubles in size over 1 s (a sample per 100 ms). */
    private fun track(rot: Boolean, scale: Boolean): MaskTrack = MaskTrack.from(
        TrackResult(
            "v",
            (0..10).map { TrackSample(1000L + it * 100L, 0.4f + 0.02f * it, 0.5f, 9f * it, 1f + 0.1f * it) },
            1f
        ),
        rot, scale
    )!!

    @Test fun trackedMaskFollowsTheObject() {
        val m = ClipMaskLibrary.create(MaskShape.ELLIPSE, 1f).let { it.copy(state = it.state.copy(cx = 0.4f, cy = 0.5f, w = 0.2f, h = 0.2f)) }
            .copy(track = track(false, false))
        // first frame and anything before it: where the mask was put
        assertEquals(0.4f, m.shownStateAt(0L, 1000L).cx, 1e-4f)
        assertEquals(0.4f, m.shownStateAt(0L, 0L).cx, 1e-4f)
        // last frame: moved with the object, size and turn untouched
        val e = m.shownStateAt(0L, 2000L)
        assertEquals(0.6f, e.cx, 1e-4f)
        assertEquals(0.5f, e.cy, 1e-4f)
        assertEquals(0.2f, e.w, 1e-4f)
        assertEquals(0f, e.rot, 1e-4f)
        // held with its video frame until the next one (2 ms tolerance, like the layer track)
        assertEquals(0.44f, m.shownStateAt(0L, 1297L).cx, 1e-4f)
        assertEquals(0.46f, m.shownStateAt(0L, 1298L).cx, 1e-4f)
        // never changes the base state
        assertEquals(0.4f, m.stateAt(0L).cx, 1e-4f)
    }

    @Test fun trackedMaskTurnsAndZoomsAboutTheStartPoint() {
        // mask 0.1 to the right of the tracked point; the object turns 90 deg clockwise and doubles
        val m = ClipMaskLibrary.create(MaskShape.RECT, 1f).let { it.copy(state = it.state.copy(cx = 0.5f, cy = 0.5f, w = 0.2f, h = 0.1f)) }
            .copy(track = track(true, true))
        val e = m.shownStateAt(0L, 2000L)
        // tracked point is now (0.6, 0.5); the offset (0.1, 0) turns to (0, 0.1) and doubles to (0, 0.2)
        assertEquals(0.6f, e.cx, 1e-4f)
        assertEquals(0.7f, e.cy, 1e-4f)
        assertEquals(0.4f, e.w, 1e-4f)
        assertEquals(0.2f, e.h, 1e-4f)
        assertEquals(90f, e.rot, 1e-3f)
        // the Rotate / Scale switches can be turned off afterwards
        val off = m.copy(track = m.track!!.withFollow(false, false)).shownStateAt(0L, 2000L)
        assertEquals(0.7f, off.cx, 1e-4f)
        assertEquals(0.5f, off.cy, 1e-4f)
        assertEquals(0f, off.rot, 1e-4f)
    }

    @Test fun editingATrackedMaskIsRelativeToTheObject() {
        val m = ClipMaskLibrary.create(MaskShape.RECT, 1.5f).let { it.copy(state = it.state.copy(cx = 0.5f, cy = 0.5f, w = 0.2f, h = 0.1f)) }
            .copy(track = track(true, true))
        val shown = m.shownStateAt(0L, 1600L)
        // dragging the mask on screen by (+0.05, -0.03) ...
        val moved = m.withShownStateAt(0L, 1600L, shown.copy(cx = shown.cx + 0.05f, cy = shown.cy - 0.03f))
        val again = moved.shownStateAt(0L, 1600L)
        assertEquals(shown.cx + 0.05f, again.cx, 1e-4f)
        assertEquals(shown.cy - 0.03f, again.cy, 1e-4f)
        assertEquals(shown.w, again.w, 1e-4f)
        assertEquals(shown.rot, again.rot, 1e-3f)
    }

    @Test fun trackedMaskGoesToTheEngineAtItsDrawnPlace() {
        val m = ClipMaskLibrary.create(MaskShape.ELLIPSE, 1f).let { it.copy(state = it.state.copy(cx = 0.4f, cy = 0.5f)) }
            .copy(track = track(false, false))
        val arr = ClipMaskLibrary.toNative(listOf(m), 0L, 2000L)!!
        assertEquals(0.6f, arr[3], 1e-4f)
        // without a source time the old behaviour (no track effect before the first frame)
        assertEquals(0.4f, ClipMaskLibrary.toNative(listOf(m), 0L)!![3], 1e-4f)
    }

    @Test fun lineMaskIsNotZoomedByTheTrack() {
        val m = ClipMaskLibrary.create(MaskShape.LINE, 1f).copy(track = track(true, true))
        assertEquals(m.state.w, m.shownStateAt(0L, 2000L).w, 1e-5f)
    }

    @Test fun dragsKeepOppositeCorner() {
        val r = ClipMaskLibrary.create(MaskShape.RECT, asp).let { it.copy(state = it.state.copy(rot = 30f)) }
        val f = MaskFrame(r, r.state, asp)
        val c2 = f.corner(2)
        val ns = f.dragCorner(0, Pt(f.corner(0).x - 0.1f * f.cosR, f.corner(0).y - 0.1f * f.sinR))
        val g = MaskFrame(r, ns, asp)
        assertEquals(c2.x, g.corner(2).x, 1e-3f)
        assertEquals(c2.y, g.corner(2).y, 1e-3f)
        assertEquals(MaskHandle.CORNER + 1, f.hitHandle(f.corner(1), 0.02f))
        assertEquals(90f, f.rotateTo(Pt(f.cx + 1f, f.cy)).rot, 1e-3f)
    }

    @Test fun penAndLayerMap() {
        val pen = ClipMaskLibrary.fromPenPoints(listOf(0.2f to 0.2f, 0.8f to 0.2f, 0.5f to 0.9f), false)!!
        val f = MaskFrame(pen, pen.state, asp)
        assertNotNull(f.insertPointNear(Pt(0.5f * asp, 0.2f), 0.03f))
        assertNull(f.insertPointNear(Pt(0.5f * asp, 0f), 0.03f))
        val lm = MaskLayerMap(0.25f, 0f, 0.5f, 1f, 400f, 300f, false, false)
        val q = lm.pictureToBox(0.5f, 0.5f)
        assertEquals(0f, q.x, 1e-3f)
        assertEquals(0.5f, lm.boxToPicture(q.x, q.y).x, 1e-3f)
    }
}
