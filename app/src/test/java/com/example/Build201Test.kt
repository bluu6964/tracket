package com.example

import com.example.model.AnimProp
import com.example.model.Keyframe
import com.example.model.LayerType
import com.example.model.MAX_SPEED
import com.example.model.MIN_SPEED
import com.example.model.ProjectSnapshot
import com.example.model.SoloLaw
import com.example.model.SoloState
import com.example.model.SpeedRamp
import com.example.model.TimelineClip
import com.example.model.TimelineTrack
import com.example.model.sourceOffsetMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Build 201 — the audit fix build. The pure pieces pinned here: the speed-ramp
 * table (S-1, [SpeedRamp] — the C++ half is pinned by audio_selftest's ramp
 * section) and the solo session laws (S-5, [SoloLaw] + the snapshot field).
 * The layout items (B-1 play-controls rebuild, B-5 multi-select regroup,
 * B-3/B-8 touch targets) are Compose geometry — proven by the build's 360 dp
 * budget table + proof renders, not by this suite.
 */
class Build201Test {

    private fun clip(
        id: String,
        durationMs: Long = 6000,
        speedKeys: List<Keyframe>? = null,
        visible: Boolean = true,
        muted: Boolean = false
    ) = TimelineClip(
        id = id, name = id, type = LayerType.AUDIO, startMs = 0, durationMs = durationMs,
        color = 0xFF000000, isVisible = visible, isMuted = muted,
        keyframes = if (speedKeys == null) emptyMap() else mapOf(AnimProp.SPEED to speedKeys)
    )

    private var laneSeq = 0
    private fun lane(vararg clips: TimelineClip) =
        TimelineTrack(id = "lane-${laneSeq++}", name = "L", clips = clips.toList())

    // ---------- S-1: SpeedRamp.tableFor ----------

    @Test
    fun speedRamp_noSpeedKeys_returnsNull() {
        assertNull(SpeedRamp.tableFor(clip("a")))
        val other = clip("b").copy(keyframes = mapOf(AnimProp.OPACITY to listOf(Keyframe(0, 1f))))
        assertNull(SpeedRamp.tableFor(other))
    }

    @Test
    fun speedRamp_blockCount_coversTheWholeDuration() {
        val keys = listOf(Keyframe(0, 1.5f), Keyframe(6000, 1.5f))
        // 6000 ms / 10.6667 ms per block = 562.5 -> 563 entries (the native
        // side holds the last entry past the end, so coverage must round UP).
        assertEquals(563, SpeedRamp.tableFor(clip("a", 6000, keys))!!.size)
        assertEquals(94, SpeedRamp.tableFor(clip("a", 1000, keys))!!.size)
        assertEquals(1, SpeedRamp.tableFor(clip("a", 0, keys))!!.size)
    }

    @Test
    fun speedRamp_constantKeys_allEntriesEqual() {
        val keys = listOf(Keyframe(0, 1.5f), Keyframe(6000, 1.5f))
        val t = SpeedRamp.tableFor(clip("a", 6000, keys))!!
        assertTrue(t.all { it == 1.5f })
    }

    @Test
    fun speedRamp_singleKey_isConstant() {
        val t = SpeedRamp.tableFor(clip("a", 3000, listOf(Keyframe(2000, 0.75f))))!!
        assertTrue(t.all { it == 0.75f })
    }

    @Test
    fun speedRamp_linearRamp_midpointsAndIntegralMatchSourceOffset() {
        val c = clip("a", 6000, listOf(Keyframe(0, 0.5f), Keyframe(6000, 2.0f)))
        val t = SpeedRamp.tableFor(c)!!
        assertTrue(
            "entries must not decrease on a rising ramp",
            (0 until t.size - 1).all { t[it + 1] >= t[it] }
        )
        // First entry = the ramp at block 0's midpoint (5.33 ms in).
        assertEquals(0.5f + 1.5f * (5.3333f / 6000f), t[0], 0.01f)
        // The discrete integral tracks the analytic one (sourceOffsetMs) — the
        // native stretcher spends exactly this sum, so this is the "the sound
        // ends when the picture ends" law. Slack covers the final overshoot
        // block (the table rounds the duration UP to whole blocks).
        val spent = SpeedRamp.sourceMs(t)
        val exact = c.sourceOffsetMs(c.durationMs)
        assertTrue("spent $spent vs exact $exact", abs(spent - exact) <= 25.0)
    }

    @Test
    fun speedRamp_clampsToTheNativeRange() {
        val hot = SpeedRamp.tableFor(clip("a", 1000, listOf(Keyframe(0, 10f))))!!
        assertTrue(hot.all { it == MAX_SPEED })
        val slow = SpeedRamp.tableFor(clip("a", 1000, listOf(Keyframe(0, 0.01f))))!!
        assertTrue(slow.all { it == MIN_SPEED })
    }

    // ---------- S-5: SoloLaw + the snapshot field ----------

    private fun threeTracks(): List<TimelineTrack> = listOf(
        lane(clip("a", visible = true, muted = false), clip("b", visible = false, muted = true)),
        lane(clip("c", visible = true, muted = true))
    )

    @Test
    fun solo_capture_recordsEveryLayer() {
        val s = SoloLaw.capture(threeTracks(), "a")
        assertEquals("a", s.clipId)
        assertEquals(3, s.restore.size)
        assertEquals(false, s.restore.first { it.clipId == "b" }.isVisible)
        assertEquals(true, s.restore.first { it.clipId == "b" }.isMuted)
        assertEquals(true, s.restore.first { it.clipId == "c" }.isMuted)
    }

    @Test
    fun solo_soloed_forcesSoloVisible_hidesAndMutesOthers_keepsSoloMute() {
        // Solo a HIDDEN + MUTED layer: it becomes visible, its mute is NOT cleared.
        val tracks = listOf(lane(clip("a", visible = true), clip("b", visible = false, muted = true)))
        val out = SoloLaw.soloed(tracks, "b").flatMap { it.clips }
        val b = out.first { it.id == "b" }
        assertTrue(b.isVisible)
        assertTrue("the soloed layer's own mute is untouched", b.isMuted)
        val a = out.first { it.id == "a" }
        assertFalse(a.isVisible)
        assertTrue(a.isMuted)
    }

    @Test
    fun solo_restored_roundTrips() {
        val tracks = threeTracks()
        val s = SoloLaw.capture(tracks, "c")
        val back = SoloLaw.restored(SoloLaw.soloed(tracks, "c"), s)
        assertEquals(
            tracks.flatMap { it.clips }.map { it.id to (it.isVisible to it.isMuted) },
            back.flatMap { it.clips }.map { it.id to (it.isVisible to it.isMuted) }
        )
    }

    @Test
    fun solo_restored_keepsLayersAddedAfterCapture() {
        val tracks = threeTracks()
        val s = SoloLaw.capture(tracks, "a")
        val grown = SoloLaw.soloed(tracks, "a") + lane(clip("new", visible = true, muted = false))
        val newClip = SoloLaw.restored(grown, s).flatMap { it.clips }.first { it.id == "new" }
        assertTrue(newClip.isVisible)
        assertFalse(newClip.isMuted)
    }

    @Test
    fun solo_isValidFor_needsTheLayer() {
        val tracks = threeTracks()
        assertTrue(SoloLaw.isValidFor(SoloState("a", emptyList()), tracks))
        assertFalse(SoloLaw.isValidFor(SoloState("gone", emptyList()), tracks))
        assertFalse(SoloLaw.isValidFor(null, tracks))
    }

    @Test
    fun solo_snapshotField_defaultsNull_andCarriesTheSession() {
        assertNull(ProjectSnapshot().solo)
        val s = SoloState("a", emptyList())
        assertEquals(s, ProjectSnapshot(solo = s).solo)
    }
}
