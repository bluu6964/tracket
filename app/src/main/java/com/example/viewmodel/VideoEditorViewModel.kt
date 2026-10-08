package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.model.AspectRatio
import com.example.model.ExportSettings
import com.example.model.ImportedMedia
import com.example.model.LaneLayout
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

    private val _totalDurationMs = MutableStateFlow(0L)
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

    private val _tracks = MutableStateFlow<List<TimelineTrack>>(emptyList())
    val tracks: StateFlow<List<TimelineTrack>> = _tracks.asStateFlow()

    init {
        recomputeTotal()
    }

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

    fun togglePlayPause() {
        if (_isPlaying.value) {
            pause()
        } else {
            play()
        }
    }

    fun play() {
        val total = _totalDurationMs.value
        if (total <= 0L) {
            _isPlaying.value = false
            return
        }
        _isPlaying.value = true
        playbackJob?.cancel()
        playbackJob = null
        // With real media, ExoPlayer is the only clock (the screen feeds the playhead back
        // through updatePlayhead). The wall-clock timer below is only a fallback for
        // projects that have no media yet.
        if (hasMainMedia()) return
        playbackJob = viewModelScope.launch {
            val startWallTime = System.currentTimeMillis()
            val startPlayhead = _currentPlayheadMs.value

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
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = false
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            redoStack.add(_tracks.value)
            val previous = undoStack.removeAt(undoStack.lastIndex)
            _tracks.value = previous
            recomputeTotal()
            _canUndo.value = undoStack.isNotEmpty()
            _canRedo.value = true
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            undoStack.add(_tracks.value)
            val next = redoStack.removeAt(redoStack.lastIndex)
            _tracks.value = next
            recomputeTotal()
            _canUndo.value = undoStack.isNotEmpty()
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
                    track.copy(clips = updatedClips.sortedBy { it.startMs })
                } else {
                    track
                }
            } else {
                track
            }
        }
        _tracks.value = newTracks
        recomputeTotal()
    }

    fun deleteSelectedClip() {
        val clipId = _selectedClipId.value ?: return
        val removed = _tracks.value.flatMap { it.clips }.firstOrNull { it.id == clipId } ?: return
        pushHistory()
        val withoutClip = _tracks.value.map { track ->
            track.copy(clips = track.clips.filter { it.id != clipId })
        }
        val closedGap = if (removed.type == LayerType.VIDEO) {
            // Media plays back to back: close the hole left by the removed media clip
            // (media clips can live in any lane, so every lane is checked).
            withoutClip.map { track ->
                track.copy(clips = track.clips.map {
                    if (it.type == LayerType.VIDEO && it.startMs >= removed.endMs) {
                        it.copy(startMs = it.startMs - removed.durationMs)
                    } else it
                })
            }
        } else {
            withoutClip
        }
        _tracks.value = LaneLayout.normalize(closedGap)
        _selectedClipId.value = null
        recomputeTotal()
    }

    fun duplicateSelectedClip() {
        val clipId = _selectedClipId.value ?: return
        val clip = _tracks.value.flatMap { it.clips }.firstOrNull { it.id == clipId } ?: return
        pushHistory()
        val isMedia = clip.type == LayerType.VIDEO
        val duplicated = clip.copy(
            id = UUID.randomUUID().toString(),
            name = "${clip.name} (Copy)",
            startMs = if (isMedia) clip.endMs else clip.endMs + 200
        )
        val base = if (isMedia) {
            // Media is inserted right after the original; later media moves along.
            _tracks.value.map { track ->
                track.copy(clips = track.clips.map {
                    if (it.type == LayerType.VIDEO && it.startMs >= clip.endMs) {
                        it.copy(startMs = it.startMs + clip.durationMs)
                    } else it
                })
            }
        } else {
            _tracks.value
        }
        // The copy goes to the first lane that is free at its time, otherwise to a new lane.
        _tracks.value = LaneLayout.place(LaneLayout.normalize(base), duplicated)
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

    /**
     * Adds a real shape layer at the current playhead. This is the single entry point
     * used by the Shape picker so timeline insertion, selection, undo/redo and duration
     * calculation stay consistent.
     */
    fun addShape(
        name: String,
        pattern: String,
        durationMs: Long = 4000L,
        fillColor: Long = 0xFFFFFFFF,
        strokeWidth: Float = 0f,
        strokeColor: Long = 0xFFFFFFFF,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        scaleX: Float = 1f,
        scaleY: Float = 1f
    ) {
        val normalizedPattern = pattern.uppercase()
        val safeDuration = durationMs.coerceIn(250L, 60_000L)
        addClip(
            type = LayerType.SHAPE,
            name = name,
            durationMs = safeDuration,
            textColor = fillColor,
            shapePattern = normalizedPattern,
            shapeOffsetX = offsetX,
            shapeOffsetY = offsetY,
            shapeScaleX = scaleX.coerceIn(0.1f, 10f),
            shapeScaleY = scaleY.coerceIn(0.1f, 10f),
            strokeWidth = strokeWidth.coerceIn(0f, 40f),
            strokeColor = strokeColor
        )
    }

    /**
     * Adds an empty text layer at the playhead and selects it. Returns the new layer's id so the
     * screen can open Text edit on it. The layer exists (and can be undone) right away.
     */
    fun addTextLayer(durationMs: Long = 4000L): String {
        addClip(
            type = LayerType.TEXT,
            name = "Text",
            durationMs = durationMs.coerceIn(250L, 60_000L),
            text = ""
        )
        return _selectedClipId.value.orEmpty()
    }

    /** Remembers the current state for undo before a Text edit session on an existing layer. */
    fun beginTextEdit() = pushHistory()

    /**
     * Live edit of one layer (used by Text edit). It does not push undo steps itself:
     * one step is pushed when the editor opens, so a whole editing session undoes at once.
     */
    fun updateClip(clipId: String, change: (TimelineClip) -> TimelineClip) {
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { if (it.id == clipId) change(it) else it })
        }
    }

    fun addClip(
        type: LayerType,
        name: String,
        durationMs: Long = 5000L,
        text: String? = null,
        textColor: Long = 0xFFFFFFFF,
        shapePattern: String? = null,
        shapeOffsetX: Float = 0f,
        shapeOffsetY: Float = 0f,
        shapeScaleX: Float = 1.0f,
        shapeScaleY: Float = 1.0f,
        strokeWidth: Float = 0f,
        strokeColor: Long = 0xFFFFFFFF
    ) {
        pushHistory()
        val playhead = _currentPlayheadMs.value

        val color = when (type) {
            LayerType.VIDEO -> 0xFF2A4365
            LayerType.AUDIO -> 0xFF1B4332
            LayerType.SHAPE -> 0xFFFF3377
            LayerType.OVERLAY -> 0xFF4A154B
            LayerType.TEXT -> 0xFF5C3D10
            LayerType.STICKER -> 0xFF4A2511
        }

        val newClip = TimelineClip(
            id = UUID.randomUUID().toString(),
            name = name,
            type = type,
            startMs = playhead,
            durationMs = durationMs,
            color = color,
            text = text,
            textColor = textColor,
            shapePattern = shapePattern,
            shapeOffsetX = shapeOffsetX,
            shapeOffsetY = shapeOffsetY,
            shapeScaleX = shapeScaleX,
            shapeScaleY = shapeScaleY,
            strokeWidth = strokeWidth,
            strokeColor = strokeColor,
            transformX = shapeOffsetX,
            transformY = shapeOffsetY,
            scalePercent = (shapeScaleX * 100f).coerceAtLeast(10f)
        )

        // Every layer type is placed the same way: first free lane, otherwise a new lane.
        _tracks.value = LaneLayout.place(_tracks.value, newClip)
        _selectedClipId.value = newClip.id
        recomputeTotal()
    }

    fun updateClipTransform(
        clipId: String,
        offsetX: Float,
        offsetY: Float,
        scaleX: Float = 1f,
        scaleY: Float = 1f
    ) {
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id == clipId) {
                    clip.copy(
                        shapeOffsetX = offsetX,
                        shapeOffsetY = offsetY,
                        shapeScaleX = scaleX,
                        shapeScaleY = scaleY,
                        transformX = offsetX,
                        transformY = offsetY,
                        scalePercent = if (scaleX != 1f) (scaleX * 100f).coerceAtLeast(10f) else clip.scalePercent
                    )
                } else clip
            })
        }
    }

    fun updateClipFullTransform(
        clipId: String,
        transformX: Float,
        transformY: Float,
        scalePercent: Float,
        rotationDeg: Float,
        pivotX: Float = 0.5f,
        pivotY: Float = 0.5f,
        rot3dX: Float = 0f,
        rot3dY: Float = 0f,
        warp: Float = 0f
    ) {
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id == clipId) {
                    clip.copy(
                        transformX = transformX,
                        transformY = transformY,
                        scalePercent = scalePercent,
                        rotationDeg = rotationDeg,
                        pivotX = pivotX,
                        pivotY = pivotY,
                        rotation3dX = rot3dX,
                        rotation3dY = rot3dY,
                        warpDistort = warp,
                        shapeOffsetX = transformX,
                        shapeOffsetY = transformY,
                        shapeScaleX = scalePercent / 100f,
                        shapeScaleY = scalePercent / 100f
                    )
                } else clip
            })
        }
    }

    fun updateClipCrop(
        clipId: String,
        cropWidthPercent: Float,
        cropHeightPercent: Float,
        cropHorizontalPercent: Float = 50f,
        cropVerticalPercent: Float = 50f,
        cropPreset: String = "Free"
    ) {
        pushHistory()
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id == clipId) {
                    clip.copy(
                        cropWidthPercent = cropWidthPercent,
                        cropHeightPercent = cropHeightPercent,
                        cropHorizontalPercent = cropHorizontalPercent,
                        cropVerticalPercent = cropVerticalPercent,
                        cropPreset = cropPreset
                    )
                } else clip
            })
        }
    }

    fun resetClipTransform(clipId: String) {
        pushHistory()
        updateClipFullTransform(
            clipId = clipId,
            transformX = 0f,
            transformY = 0f,
            scalePercent = 100f,
            rotationDeg = 0f,
            pivotX = 0.5f,
            pivotY = 0.5f,
            rot3dX = 0f,
            rot3dY = 0f,
            warp = 0f
        )
    }

    // ---- Real media -------------------------------------------------------

    fun hasMainMedia(): Boolean =
        _tracks.value.any { track ->
            track.clips.any { it.type == LayerType.VIDEO && it.uri != null }
        }

    /** Appends picked videos/photos after the last media clip, in whichever lane has room. */
    fun importMedia(items: List<ImportedMedia>) {
        if (items.isEmpty()) return
        pushHistory()
        var cursor = _tracks.value.flatMap { it.clips }
            .filter { it.type == LayerType.VIDEO }
            .maxOfOrNull { it.endMs } ?: 0L
        val added = items.map { m ->
            val clip = TimelineClip(
                id = UUID.randomUUID().toString(),
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
        var updated = _tracks.value
        added.forEach { updated = LaneLayout.place(updated, it) }
        _tracks.value = updated
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
        _totalDurationMs.value = maxEnd ?: 0L
        _currentPlayheadMs.value = _currentPlayheadMs.value.coerceAtMost(_totalDurationMs.value)
    }
}
