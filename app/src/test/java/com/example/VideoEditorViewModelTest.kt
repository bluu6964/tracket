package com.example

import com.example.model.AspectRatio
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
        assertEquals(37_000L, viewModel.totalDurationMs.value)
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
        assertEquals(37_000L, viewModel.currentPlayheadMs.value)

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

    @Test
    fun testSplitSelectedClip() {
        viewModel.selectClip("clip_video_yellow") // start 12000, duration 15000 -> end 27000
        viewModel.seekTo(18_000L)
        val initialCount = viewModel.tracks.value[0].clips.size
        viewModel.splitSelectedClip()
        assertEquals(initialCount + 1, viewModel.tracks.value[0].clips.size)
    }
}
