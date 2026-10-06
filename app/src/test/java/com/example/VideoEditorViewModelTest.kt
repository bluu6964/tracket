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

    private fun clipsOf(type: LayerType): List<TimelineClip> =
        viewModel.tracks.value.first { it.type == type }.clips

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
        assertEquals(5, viewModel.tracks.value.size)
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
    fun testAddClipGoesToMatchingTrack() {
        viewModel.addClip(LayerType.SHAPE, "Shape", 3000L)
        viewModel.addClip(LayerType.TEXT, "Text", 3000L, text = "hi")
        viewModel.addClip(LayerType.AUDIO, "Audio", 3000L)
        viewModel.addClip(LayerType.STICKER, "Sticker", 3000L)

        assertEquals(1, clipsOf(LayerType.SHAPE).size)
        assertEquals(1, clipsOf(LayerType.TEXT).size)
        assertEquals(1, clipsOf(LayerType.AUDIO).size)
        assertEquals(1, clipsOf(LayerType.OVERLAY).size)
        assertEquals(0, mainClips().size)
        assertFalse(viewModel.hasMainMedia())
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
