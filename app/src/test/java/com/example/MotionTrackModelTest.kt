package com.example

import com.example.model.LayerType
import com.example.model.MotionTrackApply
import com.example.model.TrackFollow
import com.example.model.renderAt
import com.example.model.trackOffsetAt
import com.example.model.TimelineClip
import com.example.model.TrackResult
import com.example.model.TrackSample
import com.example.model.VideoPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTrackModelTest {

    private fun clip(
        id: String,
        type: LayerType = LayerType.VIDEO,
        start: Long = 0L,
        dur: Long = 10_000L
    ) = TimelineClip(id = id, name = id, type = type, startMs = start, durationMs = dur, color = 0xFF000000)

    // ---- placement: picture <-> canvas ----------------------------------------------------

    @Test
    fun pictureCentreIsTheFrameCentre_andPositionMovesIt() {
        val c = clip("v").copy(transformX = 30f, transformY = -10f)
        val p = VideoPlacement(c, 16f / 9f, 200f, 400f).toCanvas(0.5f, 0.5f)
        assertEquals(130f, p.x, 0.01f)
        assertEquals(190f, p.y, 0.01f)
    }

    @Test
    fun scaleGrowsAboutThePivot() {
        val c = clip("v").copy(scalePercent = 200f)
        // aspect 16:9 in a 200 x 400 frame: the picture is 200 wide, so u = 1 is the right frame edge
        val p = VideoPlacement(c, 16f / 9f, 200f, 400f).toCanvas(1f, 0.5f)
        assertEquals(300f, p.x, 0.01f)
        assertEquals(200f, p.y, 0.01f)
    }

    @Test
    fun roundTripWithRotationFlipAndCrop() {
        val c = clip("v").copy(
            scalePercent = 140f, rotationDeg = 33f, transformX = 25f, transformY = -40f,
            isFlippedH = true, cropWidthPercent = 60f, cropHeightPercent = 80f,
            cropHorizontalPercent = 30f, cropVerticalPercent = 70f
        )
        val pl = VideoPlacement(c, 16f / 9f, 360f, 640f)
        for ((u, v) in listOf(0.2f to 0.3f, 0.5f to 0.5f, 0.9f to 0.7f)) {
            val canvas = pl.toCanvas(u, v)
            val back = pl.fromCanvas(canvas.x, canvas.y)
            assertEquals(u, back.x, 1e-3f)
            assertEquals(v, back.y, 1e-3f)
        }
    }

    // ---- time ----------------------------------------------------------------------------

    @Test
    fun timelineTimeFollowsTrimAndSpeed() {
        val c = clip("v", start = 1000L).copy(trimStartMs = 500L, speed = 2f)
        assertEquals(1500L, MotionTrackApply.timelineMsAt(c, 1500L))
        assertEquals(1000L, MotionTrackApply.timelineMsAt(c, 400L))
    }

    // ---- frame-by-frame follow (no keyframes) ----------------------------------------------

    /** Object moving right: u 0.3 -> 0.7 over 4 s, one sample every 100 ms. */
    private fun movingRight(): TrackResult {
        val s = (0..40).map { TrackSample(it * 100L, 0.3f + 0.4f * it / 40f, 0.5f) }
        return TrackResult("v", s, 1f)
    }

    private fun text(start: Long = 0L, dur: Long = 10_000L) =
        clip("t", LayerType.TEXT, start = start, dur = dur).copy(text = "hi", transformX = 50f, transformY = -20f)

    @Test
    fun everyTrackedFrameGetsItsOwnEntry_noThinningNoSmoothing() {
        val f = MotionTrackApply.follow(clip("v"), movingRight(), 400f, 400f)
        assertNotNull(f)
        f!!
        assertEquals(41, f.size)
        // starts at zero offset, ends where the object ended (0.4 of the 400 px frame)
        assertEquals(0f, f.dx[0], 0.001f)
        assertEquals(0.4f * 400f, f.dx[40], 0.01f)
        // exact straight line: each entry is exactly where the tracker put the object
        for (i in 0..40) assertEquals(i * 4f, f.dx[i], 0.01f)
        assertEquals(0L, f.timesMs[0])
        assertEquals(4000L, f.timesMs[40])
    }

    /** Object that turns 90 degrees and doubles in size while moving right (one sample per 100 ms). */
    private fun turningAndGrowing(): TrackResult {
        val s = (0..10).map { TrackSample(it * 100L, 0.3f + 0.04f * it, 0.5f, 9f * it, 1f + 0.1f * it) }
        return TrackResult("v", s, 1f)
    }

    @Test
    fun turnAndZoomAreOnlyFollowedWhenAsked() {
        val plain = MotionTrackApply.follow(clip("v"), turningAndGrowing(), 400f, 400f)!!
        assertNull(plain.rotDeg)
        assertNull(plain.scale)
        val full = MotionTrackApply.follow(clip("v"), turningAndGrowing(), 400f, 400f, followRotation = true, followScale = true)!!
        assertEquals(11, full.size)
        assertEquals(0f, full.rotDeg!![0], 0.001f)
        assertEquals(90f, full.rotDeg!![10], 0.001f)
        assertEquals(2f, full.scale!![10], 0.001f)
        // the position part is the same either way
        assertEquals(plain.dx[10], full.dx[10], 0.001f)
    }

    @Test
    fun followerTurnsAndZoomsOnTopOfItsOwnValues() {
        val f = MotionTrackApply.follow(clip("v"), turningAndGrowing(), 400f, 400f, followRotation = true, followScale = true)!!
        val t = text().copy(trackFollow = f, rotationDeg = 10f, scalePercent = 50f)
        val r = t.renderAt(1000L)
        assertEquals(100f, r.rotationDeg, 0.001f) // 10 own + 90 of the object
        assertEquals(100f, r.scalePercent, 0.001f) // 50 own x 2
        // a follower that only moves keeps its own turn and size
        val moveOnly = text().copy(trackFollow = MotionTrackApply.follow(clip("v"), turningAndGrowing(), 400f, 400f), rotationDeg = 10f)
        assertEquals(10f, moveOnly.renderAt(1000L).rotationDeg, 0.001f)
        assertEquals(100f, moveOnly.renderAt(1000L).scalePercent, 0.001f)
    }

    @Test
    fun mirroredVideoMirrorsTheTurn() {
        val f = MotionTrackApply.follow(clip("v").copy(isFlippedH = true), turningAndGrowing(), 400f, 400f, followRotation = true)!!
        assertEquals(-90f, f.rotDeg!![10], 0.001f)
    }

    @Test
    fun noKeyframesAreWritten() {
        val t = text()
        val f = MotionTrackApply.follow(clip("v"), movingRight(), 400f, 400f)!!
        val tracked = t.copy(trackFollow = f)
        assertTrue(tracked.keyframes.isEmpty())
        assertEquals(50f, tracked.transformX, 0f) // own position stays the base
    }

    @Test
    fun layerIsHeldWithItsVideoFrame() {
        val f = MotionTrackApply.follow(clip("v"), movingRight(), 400f, 400f)!!
        val t = text().copy(trackFollow = f)
        // exactly on a frame
        assertEquals(50f + 4f * 10, t.renderAt(1000L).transformX, 0.01f)
        // between two frames the previous frame is still on screen, so the layer stays too
        assertEquals(50f + 4f * 10, t.renderAt(1090L).transformX, 0.01f)
        assertEquals(50f + 4f * 11, t.renderAt(1100L).transformX, 0.01f)
        // y never changes for a horizontal move
        assertEquals(-20f, t.renderAt(2500L).transformY, 0.001f)
    }

    @Test
    fun wholeMillisecondRoundingOfFrameTimesDoesNotDropAFrame() {
        // 30 fps: true frame times 0, 33.33, 66.67, 100 ms are stored as 0, 33, 66, 100
        val s = listOf(0L, 33L, 66L, 100L).mapIndexed { i, ms -> TrackSample(ms, 0.1f * (i + 1), 0.5f) }
        val f = MotionTrackApply.follow(clip("v"), TrackResult("v", s, 1f), 100f, 100f)!!
        assertEquals(1, f.indexAt(33L))
        assertEquals(1, f.indexAt(32L)) // 1 ms early (rounding) still shows frame 1
        assertEquals(2, f.indexAt(66L))
        assertEquals(2, f.indexAt(97L)) // frame 3 starts at 100 ms
        assertEquals(3, f.indexAt(100L))
    }

    @Test
    fun beforeTheFirstFrameNoOffset_afterTheLastFrameTheLastOffsetIsHeld() {
        val f = MotionTrackApply.follow(clip("v"), movingRight(), 400f, 400f)!!
        val late = text().copy(trackFollow = f)
        assertEquals(0f to 0f, late.trackOffsetAt(-500L))
        assertEquals(160f, late.trackOffsetAt(9000L).first, 0.01f)
    }

    @Test
    fun timesAreAbsoluteSoALayerThatStartsLaterStaysInStep() {
        val f = MotionTrackApply.follow(clip("v"), movingRight(), 400f, 400f)!!
        val t = text(start = 2000L, dur = 5000L).copy(trackFollow = f)
        // at project time 2000 the object has moved 0.2 * 400 px
        assertEquals(50f + 80f, t.renderAt(2000L).transformX, 0.01f)
        assertEquals(50f + 160f, t.renderAt(4000L).transformX, 0.01f)
    }

    @Test
    fun movingTheLayerByHandKeepsTheTrack() {
        val f = MotionTrackApply.follow(clip("v"), movingRight(), 400f, 400f)!!
        val t = text().copy(trackFollow = f, transformX = 300f)
        assertEquals(300f + 40f, t.renderAt(1000L).transformX, 0.01f)
    }

    @Test
    fun trackedFramesFollowTheVideosTrimAndSpeed() {
        // video starts at 1000 ms on the timeline, skips 500 ms of the source, plays 2x
        val video = clip("v", start = 1000L).copy(trimStartMs = 500L, speed = 2f)
        val s = listOf(500L, 700L, 900L).mapIndexed { i, ms -> TrackSample(ms, 0.2f * (i + 1), 0.5f) }
        val f = MotionTrackApply.follow(video, TrackResult("v", s, 1f), 100f, 100f)!!
        assertEquals(listOf(1000L, 1100L, 1200L), f.timesMs.toList())
    }

    @Test
    fun emptyTrackGivesNothing() {
        assertNull(MotionTrackApply.follow(clip("v"), TrackResult("v", emptyList(), 1f), 100f, 100f))
        assertEquals(-1, TrackFollow("v", LongArray(0), FloatArray(0), FloatArray(0)).indexAt(5L))
    }

    // ---- camera track: the tracked point stays in the middle of the canvas ------------------

    @Test
    fun cameraTrack_keepsThePointInTheCentre_evenWithScaleRotationAndPosition() {
        val v = clip("v").copy(transformX = 12f, transformY = -7f, scalePercent = 150f, rotationDeg = 20f)
        val s = listOf(
            TrackSample(0L, 0.30f, 0.40f),
            TrackSample(100L, 0.35f, 0.42f),
            TrackSample(200L, 0.47f, 0.55f),
            TrackSample(300L, 0.60f, 0.35f)
        )
        val res = TrackResult("v", s, 16f / 9f)
        val f = MotionTrackApply.cameraFollow(v, res, 200f, 400f)!!
        assertEquals(4, f.size)
        val tracked = v.copy(trackFollow = f)
        for (sample in s) {
            val shown = tracked.renderAt(sample.srcMs)
            val p = VideoPlacement(shown, 16f / 9f, 200f, 400f).toCanvas(sample.u, sample.v)
            assertEquals(100f, p.x, 0.05f)
            assertEquals(200f, p.y, 0.05f)
        }
    }

    @Test
    fun cameraTrack_emptyResultGivesNull() {
        assertNull(MotionTrackApply.cameraFollow(clip("v"), TrackResult("v", emptyList(), 1f), 100f, 100f))
    }
}
