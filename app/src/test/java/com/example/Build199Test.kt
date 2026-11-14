package com.example

import com.example.model.ClipFx
import com.example.model.AlignRect
import com.example.model.AnimProp
import com.example.model.ExportRange
import com.example.model.HistoryStack
import com.example.model.Keyframe
import com.example.model.LaneLayout
import com.example.model.LayerType
import com.example.model.MultiSelectOps
import com.example.model.SpatialAction
import com.example.model.SpatialAlign
import com.example.model.TimelineClip
import com.example.model.TimelineTrack
import com.example.model.WaveformCache
import com.example.model.deepCopyLayer
import com.example.model.defaultClipColor
import com.example.model.forPastedLayer
import com.example.model.windowTracksForRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build 199 — Tools Audit #2's top lists in one build. The pure pieces pinned here:
 * the undo cap (O1, [HistoryStack]), the normalize identity fast path (O3, [LaneLayout]),
 * the waveform disk-cache format (O4, [WaveformCache]), the whole-layer clipboard (A7),
 * canvas-centre alignment (A8, [SpatialAlign]), the export range (A10, [ExportRange] +
 * windowTracksForRange), multi-select delete (A11, [MultiSelectOps.deleteChecked]) and
 * the layer colour defaults (A2, [defaultClipColor]).
 */
class Build199Test {

    private fun clip(
        id: String,
        type: LayerType = LayerType.VIDEO,
        startMs: Long = 0,
        durationMs: Long = 1000,
        locked: Boolean = false,
        chainId: String? = null,
        groupId: String? = null
    ) = TimelineClip(
        id = id, name = id, type = type, startMs = startMs, durationMs = durationMs,
        color = 0xFF000000, isLocked = locked, chainId = chainId, groupId = groupId
    )

    private var laneSeq = 0

    private fun lane(vararg clips: TimelineClip) =
        TimelineTrack(id = "lane-${laneSeq++}", name = "L", clips = clips.toList())

    // ---------- O1: HistoryStack cap ----------

    @Test
    fun historyStack_capsAtCapacity_droppingTheOldest() {
        val s = HistoryStack<Int>(capacity = 3)
        for (i in 1..5) s.push(i)
        assertEquals(3, s.size)
        assertEquals(5, s.pop())
        assertEquals(4, s.pop())
        assertEquals(3, s.pop())
        assertNull(s.pop())
    }

    @Test
    fun historyStack_underCap_keepsEverything_popIsLifo_clearEmpties() {
        val s = HistoryStack<String>()
        assertEquals(60, s.capacity)
        assertTrue(s.isEmpty())
        s.push("a"); s.push("b")
        assertEquals(2, s.size)
        assertTrue(s.isNotEmpty())
        assertEquals("b", s.pop())
        assertEquals("a", s.pop())
        s.push("x")
        s.clear()
        assertTrue(s.isEmpty())
        assertNull(s.pop())
    }

    // ---------- O3: LaneLayout normalize fast path ----------

    @Test
    fun normalize_alreadyNormalInput_isReturnedAsTheSameInstance() {
        val tracks = listOf(
            lane(clip("a", startMs = 0)),
            lane(clip("b", type = LayerType.AUDIO, startMs = 500))
        )
        assertTrue(LaneLayout.isNormalized(tracks))
        assertSame(tracks, LaneLayout.normalize(tracks))
    }

    @Test
    fun normalize_chainLaneInOrder_isNormalized() {
        val tracks = listOf(
            lane(
                clip("ch", type = LayerType.CHAIN, startMs = 0, durationMs = 3000),
                clip("m1", startMs = 0, chainId = "ch"),
                clip("m2", startMs = 1000, chainId = "ch")
            )
        )
        assertTrue(LaneLayout.isNormalized(tracks))
        assertSame(tracks, LaneLayout.normalize(tracks))
    }

    @Test
    fun isNormalized_rejectsTheShapesNormalizeRepairs() {
        // Empty lane.
        assertFalse(LaneLayout.isNormalized(listOf(lane())))
        // Two clips in one plain lane.
        assertFalse(LaneLayout.isNormalized(listOf(lane(clip("a"), clip("b", startMs = 2000)))))
        // A stray chainId on a plain lane's only clip.
        assertFalse(LaneLayout.isNormalized(listOf(lane(clip("a", chainId = "elsewhere")))))
        // Chain lane with an unrelated extra clip (overflow).
        assertFalse(
            LaneLayout.isNormalized(
                listOf(
                    lane(
                        clip("ch", type = LayerType.CHAIN, durationMs = 3000),
                        clip("m", startMs = 0, chainId = "ch"),
                        clip("x", startMs = 500)
                    )
                )
            )
        )
        // Member sorted before its chain clip.
        assertFalse(
            LaneLayout.isNormalized(
                listOf(
                    lane(
                        clip("m", startMs = 0, chainId = "ch"),
                        clip("ch", type = LayerType.CHAIN, startMs = 100, durationMs = 3000)
                    )
                )
            )
        )
    }

    @Test
    fun normalize_outputAlwaysSatisfiesIsNormalized_andIsIdempotent() {
        val messy = listOf(
            lane(clip("b", startMs = 2000), clip("a", startMs = 0)),
            lane(),
            lane(clip("c", startMs = 0), clip("d", startMs = 500))
        )
        val once = LaneLayout.normalize(messy)
        assertTrue(LaneLayout.isNormalized(once))
        assertSame(once, LaneLayout.normalize(once))
        // The overflow clips were not lost: all four clips survive, one per lane.
        assertEquals(setOf("a", "b", "c", "d"), once.flatMap { it.clips }.map { it.id }.toSet())
        assertEquals(4, once.size)
    }

    // ---------- O4: WaveformCache ----------

    @Test
    fun waveformCache_roundTrips_andKeyIsStableAndSensitive() {
        val peaks = floatArrayOf(0f, 0.5f, 1f, 0.25f)
        val blob = WaveformCache.encode(peaks, durationMs = 4000, sizeBytes = 12345)
        val back = WaveformCache.decode(blob, durationMs = 4000, sizeBytes = 12345)
        assertTrue(peaks.contentEquals(back!!))

        val k = WaveformCache.keyFor("content://media/1", 4000, 12345)
        assertEquals(k, WaveformCache.keyFor("content://media/1", 4000, 12345))
        assertTrue(k != WaveformCache.keyFor("content://media/2", 4000, 12345))
        assertTrue(k != WaveformCache.keyFor("content://media/1", 4001, 12345))
        assertTrue(k != WaveformCache.keyFor("content://media/1", 4000, 999))
    }

    @Test
    fun waveformCache_anyCorruptionOrMismatchReadsAsMiss() {
        val blob = WaveformCache.encode(floatArrayOf(0.5f, 0.6f), durationMs = 100, sizeBytes = 7)
        // Wrong identity.
        assertNull(WaveformCache.decode(blob, durationMs = 101, sizeBytes = 7))
        assertNull(WaveformCache.decode(blob, durationMs = 100, sizeBytes = 8))
        // Truncated / extended / garbage.
        assertNull(WaveformCache.decode(blob.copyOf(blob.size - 4), 100, 7))
        assertNull(WaveformCache.decode(blob + byteArrayOf(0), 100, 7))
        assertNull(WaveformCache.decode(ByteArray(64) { 0x55 }, 100, 7))
        assertNull(WaveformCache.decode(ByteArray(0), 100, 7))
        // Tampered magic.
        val bad = blob.copyOf()
        bad[0] = 0
        assertNull(WaveformCache.decode(bad, 100, 7))
    }

    // ---------- A7: whole-layer clipboard ----------

    private fun richClip(): TimelineClip = clip("src", durationMs = 5000).copy(
        keyframes = mapOf(AnimProp.POSITION to listOf(Keyframe(100, 1f, 2f), Keyframe(900, 3f, 4f))),
        effects = listOf(ClipFx(id = "fx1", type = "gaussian_blur", values = listOf(1f, 2f)))
    )

    @Test
    fun layerClipboard_deepCopyIsIndependent_andKeepsEffectIds() {
        val src = richClip()
        val copy = src.deepCopyLayer()
        assertEquals(src.keyframes.keys, copy.keyframes.keys)
        assertEquals(src.keyframes.getValue(AnimProp.POSITION).map { it.timeMs }, copy.keyframes.getValue(AnimProp.POSITION).map { it.timeMs })
        assertTrue(src.keyframes.getValue(AnimProp.POSITION) !== copy.keyframes.getValue(AnimProp.POSITION))
        assertTrue(src.effects[0] !== copy.effects[0])
        // The clipboard law: the stored copy KEEPS effect ids; a paste re-ids.
        assertEquals("fx1", copy.effects[0].id)
    }

    @Test
    fun layerClipboard_pasteGetsFreshLayerAndEffectIds_everyTime() {
        val stored = richClip().deepCopyLayer()
        val p1 = stored.forPastedLayer("new-1", newStartMs = 7000)
        val p2 = stored.forPastedLayer("new-2", newStartMs = 7000)
        assertEquals("new-1", p1.id)
        assertEquals(7000L, p1.startMs)
        assertEquals(5000L, p1.durationMs)
        assertTrue("fx1" != p1.effects[0].id)
        assertTrue(p1.effects[0].id != p2.effects[0].id)
        // The stored copy is untouched by pasting: still the source's effect id.
        assertEquals("fx1", stored.effects[0].id)
    }

    // ---------- A8: canvas-centre alignment ----------

    private fun r(id: String, l: Float, t: Float, w: Float, h: Float) = AlignRect(id, l, t, l + w, t + h)

    @Test
    fun canvasCentre_singleLayerCentresOnTheFrame() {
        val one = listOf(r("a", 100f, 50f, 200f, 100f)) // centre (200, 100); frame 1080x1920
        val dh = SpatialAlign.canvasDeltas(one, 1080f, 1920f, SpatialAction.CANVAS_CENTER_H)
        assertEquals(340f, dh.getValue("a").first, 0.001f)
        assertEquals(0f, dh.getValue("a").second, 0.001f)
        val dv = SpatialAlign.canvasDeltas(one, 1080f, 1920f, SpatialAction.CANVAS_CENTER_V)
        assertEquals(0f, dv.getValue("a").first, 0.001f)
        assertEquals(860f, dv.getValue("a").second, 0.001f)
    }

    @Test
    fun canvasCentre_guards_alreadyCentred_noCanvasAction_degenerateFrame_nanRect() {
        val centred = listOf(r("a", 440f, 910f, 200f, 100f)) // centre exactly (540, 960)
        assertTrue(SpatialAlign.canvasDeltas(centred, 1080f, 1920f, SpatialAction.CANVAS_CENTER_H).isEmpty())
        assertTrue(SpatialAlign.canvasDeltas(centred, 1080f, 1920f, SpatialAction.CANVAS_CENTER_V).isEmpty())
        // A selection-relative action is not canvasDeltas' business, and deltas() refuses the canvas ones.
        val one = listOf(r("a", 0f, 0f, 10f, 10f))
        assertTrue(SpatialAlign.canvasDeltas(one, 1080f, 1920f, SpatialAction.ALIGN_LEFT).isEmpty())
        assertTrue(SpatialAlign.deltas(one, SpatialAction.CANVAS_CENTER_H).isEmpty())
        assertTrue(SpatialAlign.canvasDeltas(one, 0f, 1920f, SpatialAction.CANVAS_CENTER_H).isEmpty())
        val nan = listOf(AlignRect("n", Float.NaN, 0f, 10f, 10f))
        assertTrue(SpatialAlign.canvasDeltas(nan, 1080f, 1920f, SpatialAction.CANVAS_CENTER_H).isEmpty())
    }

    // ---------- A10: export range ----------

    @Test
    fun exportRange_of_clampsAndRejectsUnusablePoints() {
        assertNull(ExportRange.of(null, 5000, 10000))
        assertNull(ExportRange.of(1000, null, 10000))
        assertNull(ExportRange.of(1000, 5000, 0))
        assertNull(ExportRange.of(5000, 1000, 10000)) // reversed
        assertNull(ExportRange.of(1000, 1099, 10000)) // shorter than the 100 ms minimum
        assertEquals(ExportRange(0, 10000), ExportRange.of(-500, 99999, 10000)) // clamped to the project
        assertEquals(4000L, ExportRange.of(1000, 5000, 10000)!!.lengthMs)
    }

    @Test
    fun windowTracks_fullyInsideClipOnlyShifts_outsideDrops() {
        val tracks = listOf(
            lane(clip("in", startMs = 2000, durationMs = 1000)),
            lane(clip("out", startMs = 8000, durationMs = 1000))
        )
        val w = windowTracksForRange(tracks, ExportRange(1000, 5000))
        val clips = w.flatMap { it.clips }
        assertEquals(listOf("in"), clips.map { it.id })
        assertEquals(1000L, clips[0].startMs)
        assertEquals(1000L, clips[0].durationMs)
    }

    @Test
    fun windowTracks_headStraddleFollowsTheSplitLaw() {
        val c = clip("a", startMs = 0, durationMs = 4000).copy(
            trimStartMs = 500,
            fadeInMs = 300,
            fadeOutMs = 200,
            keyframes = mapOf(AnimProp.OPACITY to listOf(Keyframe(1500, 0.5f), Keyframe(3000, 1f)))
        )
        val w = windowTracksForRange(listOf(lane(c)), ExportRange(1000, 3500))
        val out = w.flatMap { it.clips }.single()
        assertEquals(0L, out.startMs)
        assertEquals(2500L, out.durationMs)
        // Source advanced by the cut (speed 1: trimStart += headCut), fade-in of the cut edge gone.
        assertEquals(1500L, out.trimStartMs)
        assertEquals(0L, out.fadeInMs)
        // Tail straddle: fade-out of the cut edge gone too.
        assertEquals(0L, out.fadeOutMs)
        // Keyframes shifted with the head cut; the key before the window is gone with its span.
        assertEquals(listOf(500L, 2000L), out.keyframes.getValue(AnimProp.OPACITY).map { it.timeMs })
    }

    @Test
    fun windowTracks_chainLaneSurvivesWindowing() {
        val tracks = listOf(
            lane(
                clip("ch", type = LayerType.CHAIN, startMs = 0, durationMs = 6000),
                clip("m1", startMs = 0, durationMs = 2000, chainId = "ch"),
                clip("m2", startMs = 2000, durationMs = 2000, chainId = "ch"),
                clip("m3", startMs = 4000, durationMs = 2000, chainId = "ch")
            )
        )
        val w = windowTracksForRange(tracks, ExportRange(1000, 5000))
        assertTrue(LaneLayout.isNormalized(w))
        val ids = w.flatMap { it.clips }.map { it.id }
        assertTrue("ch" in ids && "m1" in ids && "m2" in ids && "m3" in ids)
    }

    // ---------- A11: multi-select delete ----------

    @Test
    fun deleteChecked_removesTheCheckedSet_andNormalizes() {
        val tracks = listOf(
            lane(clip("a")),
            lane(clip("b", startMs = 2000)),
            lane(clip("c", type = LayerType.AUDIO, startMs = 0))
        )
        val res = MultiSelectOps.deleteChecked(tracks, setOf("a", "c"))!!
        assertEquals(2, res.deletedCount)
        assertEquals(0, res.skippedLockedCount)
        assertEquals(listOf("b"), res.tracks.flatMap { it.clips }.map { it.id })
        assertTrue(LaneLayout.isNormalized(res.tracks))
    }

    @Test
    fun deleteChecked_lockedLayersAreSparedAndCounted() {
        val tracks = listOf(
            lane(clip("a")),
            lane(clip("b", startMs = 2000, locked = true)),
            lane(clip("c", type = LayerType.AUDIO, startMs = 0))
        )
        val res = MultiSelectOps.deleteChecked(tracks, setOf("a", "b", "c"))!!
        assertEquals(2, res.deletedCount)
        assertEquals(1, res.skippedLockedCount)
        assertEquals(listOf("b"), res.tracks.flatMap { it.clips }.map { it.id })

        // Everything checked is locked: nothing deleted, no tracks change, skips still reported.
        val allLocked = MultiSelectOps.deleteChecked(tracks, setOf("b"))!!
        assertEquals(0, allLocked.deletedCount)
        assertEquals(1, allLocked.skippedLockedCount)
        assertSame(tracks, allLocked.tracks)
    }

    @Test
    fun deleteChecked_cascadesChainMembersAndGroupDescendants_likeSingleDelete() {
        val tracks = listOf(
            lane(
                clip("ch", type = LayerType.CHAIN, durationMs = 3000),
                clip("m1", startMs = 0, chainId = "ch"),
                clip("m2", startMs = 1000, chainId = "ch")
            ),
            lane(clip("g", type = LayerType.GROUP, startMs = 0)),
            lane(clip("kid", startMs = 0, groupId = "g")),
            lane(clip("free", startMs = 0))
        )
        val res = MultiSelectOps.deleteChecked(tracks, setOf("ch", "g"))!!
        assertEquals(5, res.deletedCount) // chain + 2 members + group + its child
        assertEquals(listOf("free"), res.tracks.flatMap { it.clips }.map { it.id })
    }

    @Test
    fun deleteChecked_lockedCascadeMemberIsSpared_noCheckedLayersIsNull() {
        val tracks = listOf(
            lane(
                clip("ch", type = LayerType.CHAIN, durationMs = 3000),
                clip("m1", startMs = 0, chainId = "ch", locked = true)
            )
        )
        val res = MultiSelectOps.deleteChecked(tracks, setOf("ch"))!!
        assertEquals(1, res.deletedCount) // the chain itself
        assertEquals(1, res.skippedLockedCount) // its locked member stays
        assertEquals(listOf("m1"), res.tracks.flatMap { it.clips }.map { it.id })

        assertNull(MultiSelectOps.deleteChecked(tracks, emptySet()))
        assertNull(MultiSelectOps.deleteChecked(tracks, setOf("nope")))
    }

    // ---------- A2: layer colour defaults ----------

    @Test
    fun defaultClipColor_matchesTheCreationColours() {
        assertEquals(0xFF2A4365, defaultClipColor(LayerType.VIDEO))
        assertEquals(0xFF1B4332, defaultClipColor(LayerType.AUDIO))
        assertEquals(0xFFFF3377, defaultClipColor(LayerType.SHAPE))
        assertEquals(0xFF00B8A9, defaultClipColor(LayerType.CONTROLLER))
        assertEquals(0xFF5BB85D, defaultClipColor(LayerType.GROUP))
    }
}
