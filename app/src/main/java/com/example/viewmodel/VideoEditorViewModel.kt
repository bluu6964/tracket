package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.model.AspectRatio
import com.example.model.ExportSettings
import com.example.model.LayerType
import com.example.model.PreviewResolution
import com.example.model.TimelineClip
import com.example.model.TimelineTrack
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class VideoEditorViewModel : ViewModel() {

    private val _currentPlayheadMs = MutableStateFlow(0L)
    val currentPlayheadMs: StateFlow<Long> = _currentPlayheadMs.asStateFlow()

    private val _totalDurationMs = MutableStateFlow(37_000L) // 00:37:00 from screenshot
    val totalDurationMs: StateFlow<Long> = _totalDurationMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _selectedAspectRatio = MutableStateFlow(AspectRatio.RATIO_16_9)
    val selectedAspectRatio: StateFlow<AspectRatio> = _selectedAspectRatio.asStateFlow()

    private val _selectedResolution = MutableStateFlow(PreviewResolution.FULL)
    val selectedResolution: StateFlow<PreviewResolution> = _selectedResolution.asStateFlow()

    private val _selectedClipId = MutableStateFlow<String?>("clip_video_yellow")
    val selectedClipId: StateFlow<String?> = _selectedClipId.asStateFlow()

    private val _isMasterMuted = MutableStateFlow(false)
    val isMasterMuted: StateFlow<Boolean> = _isMasterMuted.asStateFlow()

    private val _tracks = MutableStateFlow<List<TimelineTrack>>(createInitialTracks())
    val tracks: StateFlow<List<TimelineTrack>> = _tracks.asStateFlow()

    // Undo / Redo history stacks
    private val undoStack = mutableListOf<List<TimelineTrack>>()
    private val redoStack = mutableListOf<List<TimelineTrack>>()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private val _exportSettings = MutableStateFlow(ExportSettings())
    val exportSettings: StateFlow<ExportSettings> = _exportSettings.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _exportProgress = MutableStateFlow(0f)
    val exportProgress: StateFlow<Float> = _exportProgress.asStateFlow()

    private var playbackJob: Job? = null

    init {
        // Record initial state
        undoStack.add(_tracks.value)
    }

    fun togglePlayPause() {
        if (_isPlaying.value) {
            pause()
        } else {
            play()
        }
    }

    fun play() {
        _isPlaying.value = true
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            val startWallTime = System.currentTimeMillis()
            val startPlayhead = _currentPlayheadMs.value
            val total = _totalDurationMs.value

            while (isActive && _isPlaying.value) {
                delay(16L) // ~60 fps smooth synchronization
                val elapsed = System.currentTimeMillis() - startWallTime
                val target = startPlayhead + elapsed
                if (target >= total) {
                    _currentPlayheadMs.value = 0L
                    play() // Loop from start
                    break
                } else {
                    _currentPlayheadMs.value = target
                }
            }
        }
    }

    fun pause() {
        _isPlaying.value = false
        playbackJob?.cancel()
        playbackJob = null
    }

    fun seekTo(timeMs: Long) {
        val clamped = timeMs.coerceIn(0L, _totalDurationMs.value)
        _currentPlayheadMs.value = clamped
    }

    fun jumpPrevious() {
        val current = _currentPlayheadMs.value
        // Find nearest clip boundary before current
        val allBoundaries = _tracks.value.flatMap { track ->
            track.clips.flatMap { listOf(it.startMs, it.endMs) }
        }.distinct().sorted()

        val prev = allBoundaries.lastOrNull { it < current - 300L } ?: 0L
        seekTo(prev)
    }

    fun jumpNext() {
        val current = _currentPlayheadMs.value
        val allBoundaries = _tracks.value.flatMap { track ->
            track.clips.flatMap { listOf(it.startMs, it.endMs) }
        }.distinct().sorted()

        val next = allBoundaries.firstOrNull { it > current + 300L } ?: _totalDurationMs.value
        seekTo(next)
    }

    fun selectClip(clipId: String?) {
        _selectedClipId.value = clipId
    }

    fun setAspectRatio(ratio: AspectRatio) {
        _selectedAspectRatio.value = ratio
    }

    fun setResolution(resolution: PreviewResolution) {
        _selectedResolution.value = resolution
    }

    fun toggleMasterAudio() {
        _isMasterMuted.value = !_isMasterMuted.value
    }

    fun toggleTrackMute(trackIndex: Int) {
        pushHistory()
        val currentList = _tracks.value.toMutableList()
        if (trackIndex in currentList.indices) {
            val track = currentList[trackIndex]
            currentList[trackIndex] = track.copy(isMuted = !track.isMuted)
            _tracks.value = currentList
        }
    }

    private fun pushHistory() {
        undoStack.add(_tracks.value)
        redoStack.clear()
        _canUndo.value = undoStack.size > 1
        _canRedo.value = false
    }

    fun undo() {
        if (undoStack.size > 1) {
            val current = undoStack.removeAt(undoStack.lastIndex)
            redoStack.add(current)
            _tracks.value = undoStack.last()
            _canUndo.value = undoStack.size > 1
            _canRedo.value = true
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            val next = redoStack.removeAt(redoStack.lastIndex)
            undoStack.add(next)
            _tracks.value = next
            _canUndo.value = undoStack.size > 1
            _canRedo.value = redoStack.isNotEmpty()
        }
    }

    fun splitSelectedClip() {
        val clipId = _selectedClipId.value ?: return
        val playhead = _currentPlayheadMs.value

        pushHistory()
        val newTracks = _tracks.value.map { track ->
            val clipIndex = track.clips.indexOfFirst { it.id == clipId }
            if (clipIndex != -1) {
                val clip = track.clips[clipIndex]
                if (playhead > clip.startMs + 500 && playhead < clip.endMs - 500) {
                    val duration1 = playhead - clip.startMs
                    val duration2 = clip.endMs - playhead
                    val part1 = clip.copy(durationMs = duration1)
                    val part2 = clip.copy(
                        id = UUID.randomUUID().toString(),
                        name = "${clip.name} (Split)",
                        startMs = playhead,
                        durationMs = duration2
                    )
                    val updatedClips = track.clips.toMutableList()
                    updatedClips[clipIndex] = part1
                    updatedClips.add(clipIndex + 1, part2)
                    track.copy(clips = updatedClips)
                } else {
                    track
                }
            } else {
                track
            }
        }
        _tracks.value = newTracks
    }

    fun deleteSelectedClip() {
        val clipId = _selectedClipId.value ?: return
        pushHistory()
        val newTracks = _tracks.value.map { track ->
            track.copy(clips = track.clips.filter { it.id != clipId })
        }
        _tracks.value = newTracks
        _selectedClipId.value = null
    }

    fun duplicateSelectedClip() {
        val clipId = _selectedClipId.value ?: return
        pushHistory()
        val newTracks = _tracks.value.map { track ->
            val clip = track.clips.find { it.id == clipId }
            if (clip != null) {
                val duplicated = clip.copy(
                    id = UUID.randomUUID().toString(),
                    name = "${clip.name} (Copy)",
                    startMs = clip.endMs + 200,
                    durationMs = clip.durationMs
                )
                track.copy(clips = track.clips + duplicated)
            } else {
                track
            }
        }
        _tracks.value = newTracks
    }

    fun addClip(
        type: LayerType,
        name: String,
        durationMs: Long = 5000L,
        text: String? = null,
        textColor: Long = 0xFFFFFFFF,
        shapePattern: String? = null
    ) {
        pushHistory()
        val playhead = _currentPlayheadMs.value
        val trackIndex = when (type) {
            LayerType.VIDEO -> 0
            LayerType.AUDIO -> 1
            LayerType.SHAPE -> 2
            LayerType.OVERLAY -> 3
            LayerType.STICKER, LayerType.TEXT -> 3
        }

        val color = when (type) {
            LayerType.VIDEO -> 0xFF2A4365
            LayerType.AUDIO -> 0xFF1B4332
            LayerType.SHAPE -> 0xFF00384D
            LayerType.OVERLAY -> 0xFF4A154B
            LayerType.TEXT -> 0xFF5C3D10
            LayerType.STICKER -> 0xFF4A2511
        }

        val newClip = TimelineClip(
            id = UUID.randomUUID().toString(),
            trackIndex = trackIndex,
            name = name,
            type = type,
            startMs = playhead,
            durationMs = durationMs,
            color = color,
            text = text,
            textColor = textColor,
            shapePattern = shapePattern
        )

        val updatedTracks = _tracks.value.toMutableList()
        if (trackIndex in updatedTracks.indices) {
            val track = updatedTracks[trackIndex]
            updatedTracks[trackIndex] = track.copy(clips = track.clips + newClip)
            _tracks.value = updatedTracks
            _selectedClipId.value = newClip.id
        }

        // Adjust total duration if new clip exceeds current duration
        if (newClip.endMs > _totalDurationMs.value) {
            _totalDurationMs.value = newClip.endMs + 1000L
        }
    }

    fun startExport(onComplete: () -> Unit) {
        if (_isExporting.value) return
        _isExporting.value = true
        _exportProgress.value = 0f

        viewModelScope.launch {
            for (i in 1..100) {
                delay(30)
                _exportProgress.value = i / 100f
            }
            delay(400)
            _isExporting.value = false
            onComplete()
        }
    }

    companion object {
        private fun createInitialTracks(): List<TimelineTrack> {
            return listOf(
                // Track 0: Main Video track with filmstrip clips matching screenshot
                TimelineTrack(
                    id = "track_video_main",
                    name = "Main Video",
                    type = LayerType.VIDEO,
                    clips = listOf(
                        TimelineClip(
                            id = "clip_video_family",
                            trackIndex = 0,
                            name = "The Family's Secret",
                            type = LayerType.VIDEO,
                            startMs = 0L,
                            durationMs = 12_000L,
                            color = 0xFF233549
                        ),
                        TimelineClip(
                            id = "clip_video_yellow",
                            trackIndex = 0,
                            name = "Vlog Talk",
                            type = LayerType.VIDEO,
                            startMs = 12_000L,
                            durationMs = 15_000L,
                            color = 0xFF2B4764
                        ),
                        TimelineClip(
                            id = "clip_video_doctor",
                            trackIndex = 0,
                            name = "Notes & Study",
                            type = LayerType.VIDEO,
                            startMs = 27_000L,
                            durationMs = 10_000L,
                            color = 0xFF233549
                        )
                    )
                ),
                // Track 1: Audio / Waveform Track with green mic icon and dash markers
                TimelineTrack(
                    id = "track_audio_voiceover",
                    name = "Voiceover & Audio",
                    type = LayerType.AUDIO,
                    clips = listOf(
                        TimelineClip(
                            id = "clip_audio_mic",
                            trackIndex = 1,
                            name = "Voice Record 01",
                            type = LayerType.AUDIO,
                            startMs = 0L,
                            durationMs = 37_000L,
                            color = 0xFF143026
                        )
                    )
                ),
                // Track 2: Shape / Cyan Glowing Dots Layer matching screenshot
                TimelineTrack(
                    id = "track_shape_dots",
                    name = "Cyan Dots Effect",
                    type = LayerType.SHAPE,
                    clips = listOf(
                        TimelineClip(
                            id = "clip_shape_cyan_grid",
                            trackIndex = 2,
                            name = "Glowing Particles",
                            type = LayerType.SHAPE,
                            startMs = 0L,
                            durationMs = 37_000L,
                            color = 0xFF00384D,
                            shapePattern = "CYAN_DOTS"
                        )
                    )
                ),
                // Track 3: Overlay / Image Track matching portrait cards in screenshot
                TimelineTrack(
                    id = "track_overlay_media",
                    name = "Photo Overlay",
                    type = LayerType.OVERLAY,
                    clips = listOf(
                        TimelineClip(
                            id = "clip_overlay_card",
                            trackIndex = 3,
                            name = "Overlay Still",
                            type = LayerType.OVERLAY,
                            startMs = 0L,
                            durationMs = 14_000L,
                            color = 0xFF3D1E38
                        )
                    )
                ),
                // Track 4: Nature / B-Roll Video Track matching bottom tree canopy in screenshot
                TimelineTrack(
                    id = "track_broll_nature",
                    name = "B-Roll Trees",
                    type = LayerType.VIDEO,
                    clips = listOf(
                        TimelineClip(
                            id = "clip_broll_canopy",
                            trackIndex = 4,
                            name = "Sunny Canopy",
                            type = LayerType.VIDEO,
                            startMs = 0L,
                            durationMs = 37_000L,
                            color = 0xFF1D382B
                        )
                    )
                )
            )
        }
    }
}
