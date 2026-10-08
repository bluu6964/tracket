package com.example

import com.example.model.AspectRatio
import com.example.model.ImportedMedia
import com.example.model.LayerType
import com.example.model.PreviewResolution
import com.example.model.TimelineClip
import com.example.model.fadeGainAt
import com.example.viewmodel.VideoEditorViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VideoEditorViewModelTest {

    private lateinit var viewModel: VideoEditorViewModel

    @Before
    fun setUp() {
        viewModel = VideoEditorViewModel()
    }

    private fun allClips(): List<TimelineClip> = viewModel.tracks.value.flatMap { it.clips }

    private fun clipsOf(type: LayerType): List<TimelineClip> =
        allClips().filter { it.type == type }.sortedBy { it.startMs }

    /** Index of the lane that holds the clip (0 = top lane). */
    private fun laneOf(clip: TimelineClip): Int =
        viewModel.tracks.value.indexOfFirst { lane -> lane.clips.any { it.id == clip.id } }

    private fun mainClips() = clipsOf(LayerType.VIDEO)

    private fun importVideo(name: String, ms: Long) = viewModel.importMedia(
        listOf(ImportedMedia("content://test/$name", name, "video/mp4", ms, isImage = false))
    )

    @Test
    fun testInitialState() {
        assertEquals(0L, viewModel.currentPlayheadMs.value)
        assertEquals(0L, viewModel.totalDurationMs.value)
        assertFalse(viewModel.isPlaying.value)
        assertFalse(viewModel.hasMainMedia())
        assertEquals(AspectRatio.RATIO_16_9, viewModel.selectedAspectRatio.value)
        assertEquals(PreviewResolution.FULL, viewModel.selectedResolution.value)
        assertEquals(0, viewModel.tracks.value.size)
    }

    @Test
    fun testPlayDoesNothingWithoutMedia() {
        viewModel.togglePlayPause()
        assertFalse(viewModel.isPlaying.value)
    }

    @Test
    fun testPlayPauseToggleWithMedia() {
        importVideo("a", 10_000L)
        viewModel.togglePlayPause()
        assertTrue(viewModel.isPlaying.value)
        viewModel.togglePlayPause()
        assertFalse(viewModel.isPlaying.value)
    }

    @Test
    fun testSeekClamping() {
        importVideo("a", 10_000L)
        viewModel.seekTo(10_000L)
        assertEquals(10_000L, viewModel.currentPlayheadMs.value)

        // Beyond total duration
        viewModel.seekTo(100_000L)
        assertEquals(10_000L, viewModel.currentPlayheadMs.value)

        // Negative
        viewModel.seekTo(-500L)
        assertEquals(0L, viewModel.currentPlayheadMs.value)
    }

    @Test
    fun testAspectRatioChange() {
        viewModel.setAspectRatio(AspectRatio.RATIO_9_16)
        assertEquals(AspectRatio.RATIO_9_16, viewModel.selectedAspectRatio.value)
    }

    @Test
    fun testAddClipAndUndoRedo() {
        val initialCount = mainClips().size
        viewModel.addClip(LayerType.VIDEO, "Test Video", 4000L)
        assertEquals(initialCount + 1, mainClips().size)
        assertTrue(viewModel.canUndo.value)

        viewModel.undo()
        assertEquals(initialCount, mainClips().size)
        assertTrue(viewModel.canRedo.value)

        viewModel.redo()
        assertEquals(initialCount + 1, mainClips().size)
    }

    @Test
    fun testAddClipKeepsItsType() {
        viewModel.addClip(LayerType.SHAPE, "Shape", 3000L)
        viewModel.addClip(LayerType.TEXT, "Text", 3000L, text = "hi")
        viewModel.addClip(LayerType.AUDIO, "Audio", 3000L)
        viewModel.addClip(LayerType.STICKER, "Sticker", 3000L)

        assertEquals(1, clipsOf(LayerType.SHAPE).size)
        assertEquals(1, clipsOf(LayerType.TEXT).size)
        assertEquals(1, clipsOf(LayerType.AUDIO).size)
        assertEquals(1, clipsOf(LayerType.STICKER).size)
        assertEquals(0, mainClips().size)
        assertFalse(viewModel.hasMainMedia())
    }

    @Test
    fun testOverlappingLayersGetTheirOwnLanes() {
        viewModel.addClip(LayerType.SHAPE, "A", 4000L)
        viewModel.addClip(LayerType.SHAPE, "B", 4000L)
        viewModel.addClip(LayerType.TEXT, "C", 4000L, text = "hi")

        val lanes = viewModel.tracks.value
        assertEquals(3, lanes.size)
        assertTrue(lanes.all { it.clips.size == 1 })
        // Newest layer is on the top lane.
        assertEquals("C", lanes[0].clips[0].name)
        assertEquals("A", lanes[2].clips[0].name)
    }

    @Test
    fun testEachLayerGetsItsOwnLane_AlightMotionStyle() {
        viewModel.addClip(LayerType.SHAPE, "A", 3000L)
        viewModel.seekTo(3000L)          // even non-overlapping layers stay on separate lines
        viewModel.addClip(LayerType.SHAPE, "B", 3000L)

        assertEquals(2, viewModel.tracks.value.size)
        assertTrue(viewModel.tracks.value.all { it.clips.size == 1 })
        // Newest on top
        assertEquals("B", viewModel.tracks.value[0].clips[0].name)
        assertEquals("A", viewModel.tracks.value[1].clips[0].name)
    }

    @Test
    fun testNoLimitOnLanes() {
        repeat(25) { viewModel.addClip(LayerType.SHAPE, "S$it", 2000L) }
        assertEquals(25, viewModel.tracks.value.size)
        assertTrue(viewModel.tracks.value.all { it.clips.size == 1 })
    }

    @Test
    fun testMediaIsNotSpecialInLanes() {
        viewModel.addClip(LayerType.SHAPE, "Shape", 4000L)
        importVideo("a", 5_000L)

        // The media clip overlaps the shape, so it gets its own lane instead of a fixed "media row".
        assertEquals(2, viewModel.tracks.value.size)
        assertEquals(0, laneOf(mainClips()[0]))
        assertEquals(1, laneOf(clipsOf(LayerType.SHAPE)[0]))
    }

    @Test
    fun testNoLaneEverHasOverlappingClips() {
        importVideo("a", 6_000L)
        viewModel.addClip(LayerType.SHAPE, "A", 3000L)
        viewModel.seekTo(2_000L)
        viewModel.addClip(LayerType.TEXT, "B", 3000L, text = "hi")
        viewModel.seekTo(5_000L)
        viewModel.addClip(LayerType.SHAPE, "C", 3000L)
        viewModel.duplicateSelectedClip()

        viewModel.tracks.value.forEach { lane ->
            for (i in lane.clips.indices) for (j in i + 1 until lane.clips.size) {
                assertFalse(lane.clips[i].overlaps(lane.clips[j]))
            }
        }
    }

    @Test
    fun testEmptyLaneDisappearsWhenItsLastLayerIsDeleted() {
        viewModel.addClip(LayerType.SHAPE, "A", 3000L)
        viewModel.addClip(LayerType.SHAPE, "B", 3000L)
        assertEquals(2, viewModel.tracks.value.size)

        viewModel.deleteSelectedClip()   // B is selected after adding
        assertEquals(1, viewModel.tracks.value.size)
        assertEquals("A", viewModel.tracks.value[0].clips[0].name)
    }

    @Test
    fun testImportMediaAsNormalLayersAtPlayhead() {
        importVideo("a", 12_000L)
        viewModel.seekTo(3_000L)
        importVideo("b", 8_000L)
        val clips = mainClips()
        assertEquals(2, clips.size)
        // Both placed at the playhead of their import (no sequential main-track append).
        assertEquals(0L, clips.minOf { it.startMs })
        assertEquals(3_000L, clips.maxOf { it.startMs })
        assertEquals(12_000L, viewModel.totalDurationMs.value) // a is longer from 0
        assertTrue(viewModel.hasMainMedia())
        // Each media layer has its own lane.
        assertEquals(2, viewModel.tracks.value.count { t -> t.clips.any { it.type == LayerType.VIDEO } })
    }

    @Test
    fun testMediaImportUsesPlayheadLikeOtherLayers() {
        viewModel.addClip(LayerType.TEXT, "Text", 3000L, text = "hi")
        viewModel.seekTo(1_500L)
        importVideo("a", 5_000L)
        assertEquals(1_500L, mainClips()[0].startMs)
    }

    @Test
    fun testSplitSelectedClipKeepsTrimOffset() {
        importVideo("a", 20_000L)
        viewModel.seekTo(8_000L)
        viewModel.splitSelectedClip()
        val clips = mainClips()
        assertEquals(2, clips.size)
        assertEquals(8_000L, clips[0].durationMs)
        assertEquals(8_000L, clips[1].startMs)
        assertEquals(8_000L, clips[1].trimStartMs)
        assertEquals(12_000L, clips[1].durationMs)
        assertEquals(20_000L, viewModel.totalDurationMs.value)
    }

    @Test
    fun testDeleteMediaDoesNotRippleOtherLayers() {
        importVideo("a", 5_000L)
        viewModel.seekTo(5_000L)
        importVideo("b", 5_000L)
        viewModel.seekTo(10_000L)
        importVideo("c", 5_000L)
        val mid = mainClips().first { it.startMs == 5_000L }
        viewModel.selectClip(mid.id)
        viewModel.deleteSelectedClip()
        val clips = mainClips()
        assertEquals(2, clips.size)
        // Remaining media keep their own start times (no ripple).
        assertEquals(setOf(0L, 10_000L), clips.map { it.startMs }.toSet())
        assertEquals(15_000L, viewModel.totalDurationMs.value)
    }

    @Test
    fun testUndoRestoresDeletedClip() {
        importVideo("a", 5_000L)
        viewModel.seekTo(5_000L)
        importVideo("b", 5_000L)
        viewModel.selectClip(mainClips().first { it.startMs == 0L }.id)
        viewModel.deleteSelectedClip()
        assertEquals(10_000L, viewModel.totalDurationMs.value)

        viewModel.undo()
        assertEquals(2, mainClips().size)
        assertEquals(10_000L, viewModel.totalDurationMs.value)
    }

    @Test
    fun testDuplicateMediaLikeOtherLayers() {
        importVideo("a", 5_000L)
        viewModel.selectClip(mainClips()[0].id)
        viewModel.duplicateSelectedClip()
        val clips = mainClips()
        assertEquals(2, clips.size)
        // Copy starts just after the original; no shifting of other media.
        assertEquals(0L, clips.minOf { it.startMs })
        assertEquals(5_200L, clips.maxOf { it.startMs })
        assertEquals(10_200L, viewModel.totalDurationMs.value)
    }

    @Test
    fun testAddTextLayerCreatesSelectedEmptyTextLayer() {
        val id = viewModel.addTextLayer()
        val clip = clipsOf(LayerType.TEXT).single()
        assertEquals(id, clip.id)
        assertEquals(id, viewModel.selectedClipId.value)
        assertEquals("", clip.text)
        assertEquals(4_000L, clip.durationMs)
    }

    @Test
    fun testUpdateClipChangesTextStyleLive() {
        val id = viewModel.addTextLayer()
        viewModel.updateClip(id) {
            it.copy(text = "Hello", fontFamily = "Lobster", fontWeight = 700, textStrokeThickness = 40f)
        }
        val clip = clipsOf(LayerType.TEXT).single()
        assertEquals("Hello", clip.text)
        assertEquals("Lobster", clip.fontFamily)
        assertEquals(700, clip.fontWeight)
        assertEquals(40f, clip.textStrokeThickness, 0.001f)
    }

    @Test
    fun testTextEditSessionUndoesAsOneStep() {
        val id = viewModel.addTextLayer()
        viewModel.beginTextEdit()
        viewModel.updateClip(id) { it.copy(text = "a") }
        viewModel.updateClip(id) { it.copy(text = "ab") }
        viewModel.updateClip(id) { it.copy(fontWeight = 900) }
        viewModel.undo()
        val clip = clipsOf(LayerType.TEXT).single()
        assertEquals("", clip.text)
        assertEquals(600, clip.fontWeight)
    }

    // ---- Audio layers (v0.1.12) ----

    private fun importAudio(name: String, ms: Long) = viewModel.importAudio(
        listOf(ImportedMedia("content://test/$name", name, "audio/mpeg", ms, isImage = false))
    )

    @Test
    fun testImportAudioIsKeptInsideVideoLength() {
        importVideo("v", 10_000L)
        viewModel.seekTo(4_000L)
        importAudio("song", 60_000L)
        val audio = clipsOf(LayerType.AUDIO).single()
        assertEquals(4_000L, audio.startMs)
        assertEquals(6_000L, audio.durationMs)
        assertEquals(60_000L, audio.sourceDurationMs)
        assertEquals(audio.id, viewModel.selectedClipId.value)
    }

    @Test
    fun testAudioOverlappingVideoGoesToItsOwnLane() {
        importVideo("v", 10_000L)
        viewModel.seekTo(0L)
        importAudio("song", 5_000L)
        val video = mainClips().single()
        val audio = clipsOf(LayerType.AUDIO).single()
        assertTrue(laneOf(video) != laneOf(audio))
    }

    @Test
    fun testAudioSpeedChangesTimelineLength() {
        importAudio("song", 10_000L)
        val id = clipsOf(LayerType.AUDIO).single().id
        viewModel.setAudioSpeed(id, 2f)
        val audio = clipsOf(LayerType.AUDIO).single()
        assertEquals(2f, audio.speed, 0.0001f)
        assertEquals(5_000L, audio.durationMs)
    }

    @Test
    fun testSplitAudioKeepsSourcePositionWithSpeed() {
        importAudio("song", 20_000L)
        val id = clipsOf(LayerType.AUDIO).single().id
        viewModel.setAudioSpeed(id, 2f)            // now 10 s long on the timeline
        viewModel.seekTo(4_000L)
        viewModel.splitSelectedClip()
        val parts = clipsOf(LayerType.AUDIO)
        assertEquals(2, parts.size)
        assertEquals(4_000L, parts[0].durationMs)
        assertEquals(8_000L, parts[1].trimStartMs)  // 4 s on the timeline = 8 s of the song
    }

    @Test
    fun testFadeGain() {
        val clip = TimelineClip(
            id = "a", name = "a", type = LayerType.AUDIO, startMs = 0L, durationMs = 10_000L,
            color = 0L, fadeInMs = 2_000L, fadeOutMs = 2_000L
        )
        assertEquals(0f, clip.fadeGainAt(0L), 0.0001f)
        assertEquals(0.5f, clip.fadeGainAt(1_000L), 0.0001f)
        assertEquals(1f, clip.fadeGainAt(5_000L), 0.0001f)
        assertEquals(0.5f, clip.fadeGainAt(9_000L), 0.0001f)
    }
}
