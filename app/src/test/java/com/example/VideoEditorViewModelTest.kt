package com.example

import com.example.model.AspectRatio
import com.example.model.ImportedMedia
import com.example.model.LayerType
import com.example.model.PreviewResolution
import com.example.viewmodel.VideoEditorViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VideoEditorViewModelTest {

    private lateinit var viewModel: VideoEditorViewModel

    @Before
    fun setUp() {
        viewModel = VideoEditorViewModel()
    }

    @Test
    fun testInitialState() {
        assertEquals(0L, viewModel.currentPlayheadMs.value)
        assertEquals(10_000L, viewModel.totalDurationMs.value)
        assertFalse(viewModel.isPlaying.value)
        assertEquals(AspectRatio.RATIO_16_9, viewModel.selectedAspectRatio.value)
        assertEquals(PreviewResolution.FULL, viewModel.selectedResolution.value)
        assertEquals(5, viewModel.tracks.value.size)
    }

    @Test
    fun testPlayPauseToggle() {
        viewModel.togglePlayPause()
        assertTrue(viewModel.isPlaying.value)
        viewModel.togglePlayPause()
        assertFalse(viewModel.isPlaying.value)
    }

    @Test
    fun testSeekClamping() {
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
        val initialCount = viewModel.tracks.value[0].clips.size
        viewModel.addClip(LayerType.VIDEO, "Test Video", 4000L)
        assertEquals(initialCount + 1, viewModel.tracks.value[0].clips.size)
        assertTrue(viewModel.canUndo.value)

        // Undo
        viewModel.undo()
        assertEquals(initialCount, viewModel.tracks.value[0].clips.size)
        assertTrue(viewModel.canRedo.value)

        // Redo
        viewModel.redo()
        assertEquals(initialCount + 1, viewModel.tracks.value[0].clips.size)
    }

    private fun importVideo(name: String, ms: Long) = viewModel.importMedia(
        listOf(ImportedMedia("content://test/$name", name, "video/mp4", ms, isImage = false))
    )

    @Test
    fun testImportAppendsToMainTrackAndUpdatesDuration() {
        importVideo("a", 12_000L)
        importVideo("b", 8_000L)
        val clips = viewModel.tracks.value[0].clips
        assertEquals(2, clips.size)
        assertEquals(0L, clips[0].startMs)
        assertEquals(12_000L, clips[1].startMs)
        assertEquals(20_000L, viewModel.totalDurationMs.value)
        assertTrue(viewModel.hasMainMedia())
    }

    @Test
    fun testSplitSelectedClipKeepsTrimOffset() {
        importVideo("a", 20_000L)
        viewModel.seekTo(8_000L)
        viewModel.splitSelectedClip()
        val clips = viewModel.tracks.value[0].clips
        assertEquals(2, clips.size)
        assertEquals(8_000L, clips[0].durationMs)
        assertEquals(8_000L, clips[1].trimStartMs)
        assertEquals(12_000L, clips[1].durationMs)
    }

    @Test
    fun testDeleteRipplesMainTrack() {
        importVideo("a", 5_000L)
        importVideo("b", 5_000L)
        importVideo("c", 5_000L)
        viewModel.selectClip(viewModel.tracks.value[0].clips[1].id)
        viewModel.deleteSelectedClip()
        val clips = viewModel.tracks.value[0].clips
        assertEquals(2, clips.size)
        assertEquals(5_000L, clips[1].startMs)
        assertEquals(10_000L, viewModel.totalDurationMs.value)
    }
}
