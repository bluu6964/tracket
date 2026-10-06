package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.model.AspectRatio
import com.example.model.ExportSettings
import com.example.model.ImportedMedia
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

    private val _totalDurationMs = MutableStateFlow(10_000L)
    val totalDurationMs: StateFlow<Long> = _totalDurationMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _selectedAspectRatio = MutableStateFlow(AspectRatio.RATIO_16_9)
    val selectedAspectRatio: StateFlow<AspectRatio> = _selectedAspectRatio.asStateFlow()

    private val _selectedResolution = MutableStateFlow(PreviewResolution.FULL)
    val selectedResolution: StateFlow<PreviewResolution> = _selectedResolution.asStateFlow()

    private val _selectedClipId = MutableStateFlow<String?>(null)
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
        // With real media, ExoPlayer is the clock (the screen feeds updatePlayhead()).
        if (hasMainMedia()) { playbackJob = null; return }
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
            recomputeTotal()
            _canUndo.value = undoStack.size > 1
            _canRedo.value = true
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            val next = redoStack.removeAt(redoStack.lastIndex)
            undoStack.add(next)
            _tracks.value = next
            recomputeTotal()
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
                        durationMs = duration2,
                        trimStartMs = clip.trimStartMs + duration1
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
        _tracks.value = normalizeMainTrack(newTracks)
        _selectedClipId.value = null
        recomputeTotal()
    }

    fun duplicateSelectedClip() {
        val clipId = _selectedClipId.value ?: return
        pushHistory()
        val newTracks = _tracks.value.map { track ->
            val index = track.clips.indexOfFirst { it.id == clipId }
            if (index == -1) return@map track
            val clip = track.clips[index]
            val duplicated = clip.copy(
                id = UUID.randomUUID().toString(),
                name = "${clip.name} (Copy)",
                startMs = clip.endMs + 200
            )
            val list = track.clips.toMutableList()
            if (track.id == MAIN_TRACK_ID) list.add(index + 1, duplicated) else list.add(duplicated)
            track.copy(clips = list)
        }
        _tracks.value = normalizeMainTrack(newTracks)
        recomputeTotal()
    }

    fun flipHorizontalSelectedClip() {
        val clipId = _selectedClipId.value ?: return
        pushHistory()
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id == clipId) clip.copy(isFlippedH = !clip.isFlippedH) else clip
            })
        }
    }

    fun flipVerticalSelectedClip() {
        val clipId = _selectedClipId.value ?: return
        pushHistory()
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id == clipId) clip.copy(isFlippedV = !clip.isFlippedV) else clip
            })
        }
    }

    fun replaceSelectedClipMedia(drawableResId: Int, newName: String? = null) {
        val clipId = _selectedClipId.value ?: return
        pushHistory()
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id == clipId) {
                    clip.copy(
                        drawableResId = drawableResId,
                        name = newName ?: clip.name
                    )
                } else clip
            })
        }
    }

    fun setClipBlendMode(mode: String) {
        val clipId = _selectedClipId.value ?: return
        pushHistory()
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id == clipId) clip.copy(blendMode = mode) else clip
            })
        }
    }

    fun setClipEffect(effect: String) {
        val clipId = _selectedClipId.value ?: return
        pushHistory()
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id == clipId) clip.copy(effectName = effect) else clip
            })
        }
    }

    fun setClipAnimation(anim: String) {
        val clipId = _selectedClipId.value ?: return
        pushHistory()
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id == clipId) clip.copy(animationName = anim) else clip
            })
        }
    }

    fun setClipColor(color: Long) {
        val clipId = _selectedClipId.value ?: return
        pushHistory()
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id == clipId) clip.copy(color = color) else clip
            })
        }
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

    // ---- Real media -------------------------------------------------------

    fun hasMainMedia(): Boolean =
        _tracks.value.firstOrNull()?.clips?.any { it.uri != null } == true

    /** Appends picked videos/photos to the end of the main track. */
    fun importMedia(items: List<ImportedMedia>) {
        if (items.isEmpty()) return
        pushHistory()
        val tracks = _tracks.value.toMutableList()
        val main = tracks[0]
        var cursor = main.clips.maxOfOrNull { it.endMs } ?: 0L
        val added = items.map { m ->
            val clip = TimelineClip(
                id = UUID.randomUUID().toString(),
                trackIndex = 0,
                name = m.name,
                type = LayerType.VIDEO,
                startMs = cursor,
                durationMs = m.durationMs,
                color = 0xFF2A4365,
                uri = m.uri,
                mimeType = m.mimeType,
                isImage = m.isImage,
                sourceDurationMs = m.durationMs
            )
            cursor += m.durationMs
            clip
        }
        tracks[0] = main.copy(clips = main.clips + added)
        _tracks.value = tracks
        _selectedClipId.value = added.last().id
        recomputeTotal()
    }

    /** Called by the screen while ExoPlayer plays; keeps the timeline playhead in sync. */
    fun updatePlayhead(ms: Long) {
        _currentPlayheadMs.value = ms.coerceIn(0L, _totalDurationMs.value)
    }

    fun onPlaybackEnded() {
        _isPlaying.value = false
        playbackJob?.cancel()
        playbackJob = null
    }

    fun setExporting(exporting: Boolean) {
        _isExporting.value = exporting
        if (exporting) _exportProgress.value = 0f
    }

    fun setExportProgress(progress: Float) {
        _exportProgress.value = progress.coerceIn(0f, 1f)
    }

    fun finishExport() {
        _exportProgress.value = 1f
        _isExporting.value = false
    }

    private fun recomputeTotal() {
        val maxEnd = _tracks.value.flatMap { it.clips }.maxOfOrNull { it.endMs }
        _totalDurationMs.value = if (maxEnd == null) 10_000L else maxOf(1_000L, maxEnd)
        _currentPlayheadMs.value = _currentPlayheadMs.value.coerceAtMost(_totalDurationMs.value)
    }

    /** The main track plays gapless, back to back, in list order. */
    private fun normalizeMainTrack(tracks: List<TimelineTrack>): List<TimelineTrack> {
        if (tracks.isEmpty()) return tracks
        var cursor = 0L
        val packed = tracks[0].clips.map { c ->
            val moved = c.copy(startMs = cursor)
            cursor += c.durationMs
            moved
        }
        return listOf(tracks[0].copy(clips = packed)) + tracks.drop(1)
    }

    companion object {
        const val MAIN_TRACK_ID = "track_video_main"

        private fun createInitialTracks(): List<TimelineTrack> = listOf(
            TimelineTrack(id = MAIN_TRACK_ID, name = "Main Video", type = LayerType.VIDEO),
            TimelineTrack(id = "track_audio", name = "Audio", type = LayerType.AUDIO),
            TimelineTrack(id = "track_shape", name = "Shapes", type = LayerType.SHAPE),
            TimelineTrack(id = "track_overlay", name = "Overlay & Text", type = LayerType.OVERLAY),
            TimelineTrack(id = "track_broll", name = "B-Roll", type = LayerType.VIDEO)
        )
    }
}
