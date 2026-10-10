package com.example

import com.example.model.AspectRatio
import com.example.model.ImportedMedia
import com.example.model.LayerType
import com.example.model.PreviewResolution
import com.example.model.TimelineClip
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
    fun testLayersThatDoNotOverlapShareALane() {
        viewModel.addClip(LayerType.SHAPE, "A", 3000L)
        viewModel.seekTo(3000L)          // exactly where A ends: touching is not overlapping
        viewModel.addClip(LayerType.SHAPE, "B", 3000L)

        assertEquals(1, viewModel.tracks.value.size)
        assertEquals(listOf("A", "B"), viewModel.tracks.value[0].clips.map { it.name })
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
    fun testImportAppendsToMainTrackAndUpdatesDuration() {
        importVideo("a", 12_000L)
        importVideo("b", 8_000L)
        val clips = mainClips()
        assertEquals(2, clips.size)
        assertEquals(0L, clips[0].startMs)
        assertEquals(12_000L, clips[1].startMs)
        assertEquals(20_000L, viewModel.totalDurationMs.value)
        assertTrue(viewModel.hasMainMedia())
    }

    @Test
    fun testFirstImportStartsAtZeroEvenWithOtherLayers() {
        viewModel.addClip(LayerType.TEXT, "Text", 3000L, text = "hi")
        importVideo("a", 5_000L)
        assertEquals(0L, mainClips()[0].startMs)
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
    fun testDeleteRipplesMainTrack() {
        importVideo("a", 5_000L)
        importVideo("b", 5_000L)
        importVideo("c", 5_000L)
        viewModel.selectClip(mainClips()[1].id)
        viewModel.deleteSelectedClip()
        val clips = mainClips()
        assertEquals(2, clips.size)
        assertEquals(5_000L, clips[1].startMs)
        assertEquals(10_000L, viewModel.totalDurationMs.value)
    }

    @Test
    fun testUndoRestoresDeletedClip() {
        importVideo("a", 5_000L)
        importVideo("b", 5_000L)
        viewModel.selectClip(mainClips()[0].id)
        viewModel.deleteSelectedClip()
        assertEquals(5_000L, viewModel.totalDurationMs.value)

        viewModel.undo()
        assertEquals(2, mainClips().size)
        assertEquals(5_000L, mainClips()[1].startMs)
        assertEquals(10_000L, viewModel.totalDurationMs.value)
    }

    @Test
    fun testDuplicateInsertsAfterClipAndShiftsFollowing() {
        importVideo("a", 5_000L)
        importVideo("b", 5_000L)
        viewModel.selectClip(mainClips()[0].id)
        viewModel.duplicateSelectedClip()
        val clips = mainClips()
        assertEquals(3, clips.size)
        assertEquals(0L, clips[0].startMs)
        assertEquals(5_000L, clips[1].startMs)
        assertEquals(10_000L, clips[2].startMs)
        assertEquals(15_000L, viewModel.totalDurationMs.value)
    }
}
