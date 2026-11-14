package com.example.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.engine.AudioWaveform
import com.example.model.sourceOffsetMs
import com.example.model.MIN_SPEED
import com.example.model.MAX_CLIP_VOLUME
import com.example.model.MAX_SPEED
import com.example.model.AspectRatio
import com.example.model.CarouselParams
import com.example.model.CarouselTemplate
import com.example.model.VectorDraw
import com.example.model.PathSeg
import com.example.model.DonutChartTemplate
import com.example.model.GenieTemplate
import com.example.model.LogoRevealTemplate
import com.example.model.LowerThirdTemplate
import com.example.model.PieCounterTemplate
import com.example.model.PopTitleTemplate
import com.example.model.SocialGrowthTemplate
import com.example.model.WordPopTemplate
import com.example.model.SavedTemplate
import com.example.model.UserTemplateOps
import com.example.model.ControllerFanOut
import com.example.model.ExportSettings
import com.example.model.ImportedMedia
import com.example.model.GroupOps
import com.example.model.LaneLayout
import com.example.model.LayerType
import com.example.model.MultiAction
import com.example.model.MultiSelectOps
import com.example.model.PreviewResolution
import com.example.model.AnimProp
import com.example.model.Easing
import com.example.model.ShapeEditValues
import com.example.model.TimelineClip
import com.example.model.at
import com.example.model.copyEffects
import com.example.model.deepCopyKeyframes
import com.example.model.withFreshEffectIds
import com.example.model.withPastedKeyframes
import com.example.model.trackOffsetAt
import com.example.model.indexOnTime
import com.example.model.propValues
import com.example.model.withEasing
import com.example.model.withKeyframe
import com.example.model.AnimDir
import com.example.model.AnimPreset
import com.example.model.AnimSlot
import com.example.model.AnimPresets
import com.example.model.ANIM_MIN_MS
import com.example.model.maxAnimMs
import com.example.model.withClipAnim
import com.example.model.withEndMoved
import com.example.model.withTrimmedStartKeyframes
import com.example.model.withoutClipAnim
import com.example.model.withProp
import com.example.model.withScaledKeyframes
import com.example.model.withShiftedKeyframes
import com.example.model.withoutKeyframe
import com.example.model.TimelineTrack
import com.example.model.ProjectSnapshot
import com.example.model.ProjectMarkers
import com.example.model.TimelineMarker
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

    private val _multiSelectIds = MutableStateFlow<Set<String>>(emptySet())
    /** Layers checked in multi-select mode (empty = mode is off). */
    val multiSelectIds: StateFlow<Set<String>> = _multiSelectIds.asStateFlow()

    private val _isMasterMuted = MutableStateFlow(false)
    val isMasterMuted: StateFlow<Boolean> = _isMasterMuted.asStateFlow()

    private val _tracks = MutableStateFlow<List<TimelineTrack>>(emptyList())
    val tracks: StateFlow<List<TimelineTrack>> = _tracks.asStateFlow()

    // Must come BEFORE the init block below: recomputeTotal() reads it. Declared later it was still null -> NullPointerException at start.
    private val _editingGroupId = MutableStateFlow<String?>(null)

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
        val hasMedia = hasMainMedia()
        // Finished project: start again from the beginning.
        if (hasMedia && _currentPlayheadMs.value >= total - 50L) _currentPlayheadMs.value = 0L
        _isPlaying.value = true
        playbackJob?.cancel()
        playbackJob = null
        // The wall clock is the only clock: every layer (several videos can overlap) has its own
        // player that just follows the playhead.
        playbackJob = viewModelScope.launch {
            val startWallTime = System.currentTimeMillis()
            val startPlayhead = _currentPlayheadMs.value

            while (isActive && _isPlaying.value) {
                delay(16L) // ~60 fps smooth synchronization
                val elapsed = System.currentTimeMillis() - startWallTime
                val target = startPlayhead + elapsed
                if (target >= total) {
                    if (hasMedia) {
                        _currentPlayheadMs.value = total
                        _isPlaying.value = false
                    } else {
                        _currentPlayheadMs.value = 0L
                        play() // Loop from start
                    }
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
        setSelected(clipId)
    }

    /** Every selection change goes through here: choosing a normal layer leaves multi-select. */
    private fun setSelected(id: String?) {
        _selectedClipId.value = id
        if (id != null && _multiSelectIds.value.isNotEmpty()) _multiSelectIds.value = emptySet()
        // A layer that lives in another group than the one being edited: go to that group (or the top level).
        if (id != null) {
            val c = _tracks.value.flatMap { it.clips }.firstOrNull { it.id == id }
            if (c != null) {
                val scope = GroupOps.validScope(_tracks.value, c.groupId)
                if (_editingGroupId.value != scope) _editingGroupId.value = scope
            }
        }
    }

    // ---- Edit Group (Alight Motion style: go inside a group and edit its layers one by one) ----------

    // (the private flow itself is declared above the init block: recomputeTotal() reads it while the ViewModel is built)

    /**
     * The Group the person is inside (Edit Group), or null = the top level. Inside a group the timeline and the canvas show
     * only the layers of that group (GroupOps.scopeTracks); a group is one single layer anywhere else.
     */
    val editingGroupId: StateFlow<String?> = _editingGroupId.asStateFlow()

    /** Goes inside the Group [groupId]. False when it is not a Group layer. */
    fun enterGroup(groupId: String): Boolean {
        val g = _tracks.value.flatMap { it.clips }.firstOrNull { it.id == groupId && it.type == LayerType.GROUP } ?: return false
        _multiSelectIds.value = emptySet()
        _selectedClipId.value = null
        _editingGroupId.value = g.id
        return true
    }

    /** Leaves the group being edited (one level up) and selects it, so it can be moved / faded as one layer. */
    fun exitGroup() {
        val gid = _editingGroupId.value ?: return
        val g = _tracks.value.flatMap { it.clips }.firstOrNull { it.id == gid }
        _editingGroupId.value = null
        _multiSelectIds.value = emptySet()
        if (g != null) setSelected(g.id) else _selectedClipId.value = null
    }

    /**
     * Vertical drag in the timeline. [fromIndex] / [toIndex] are positions in the lanes that are SHOWN (the level being
     * edited, see [editingGroupId]); they are turned into positions in the whole list here.
     */
    fun reorderShownLane(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val all = _tracks.value
        val shown = GroupOps.scopeTracks(all, _editingGroupId.value)
        if (shown.isEmpty()) return
        val from = GroupOps.realLaneIndex(all, shown[fromIndex.coerceIn(0, shown.lastIndex)].id)
        val to = GroupOps.realLaneIndex(all, shown[toIndex.coerceIn(0, shown.lastIndex)].id)
        if (from < 0 || to < 0 || from == to) return
        pushHistory()
        _tracks.value = LaneLayout.reorder(all, from, to)
    }

    // ---- Multi-select (long-press a layer's left control) -------------------

    fun enterMultiSelect(clipId: String) {
        _selectedClipId.value = null
        _multiSelectIds.value = setOf(clipId)
    }

    fun toggleMultiSelect(clipId: String) {
        val cur = _multiSelectIds.value
        _multiSelectIds.value = if (clipId in cur) cur - clipId else cur + clipId
    }

    fun exitMultiSelect() {
        _multiSelectIds.value = emptySet()
    }

    /** Runs one button of the multi-select bar on all checked layers (one undo step). */
    fun applyMultiAction(action: MultiAction) {
        val ids = _multiSelectIds.value
        if (ids.isEmpty()) return
        if (action == MultiAction.GROUP) {
            // Real group: the layers follow a new Group layer (or go into the one group that is checked too).
            val all = _tracks.value.flatMap { it.clips }
            val rig = com.example.engine.ParentRig(all) { c, t -> com.example.engine.NativeRenderAt.renderAt(c, t) }
            val made = GroupOps.group(_tracks.value, ids) { gid ->
                rig.poseOf(gid, _currentPlayheadMs.value)?.let { GroupOps.Pose(it.transformX, it.transformY, it.scalePercent, it.rotationDeg) }
            } ?: return
            pushHistory()
            _tracks.value = made.tracks
            recomputeTotal()
            exitMultiSelect()
            setSelected(made.groupId)
            return
        }
        val next = MultiSelectOps.apply(_tracks.value, ids, action, _currentPlayheadMs.value) ?: return
        pushHistory()
        _tracks.value = next
        recomputeTotal()
        if (action == MultiAction.CHAIN) {
            // Done: leave multi-select and select the new chain, so layers added next go inside it.
            val gid = next.flatMap { it.clips }.firstOrNull { it.id in ids && it.chainId != null }?.chainId
            exitMultiSelect()
            if (gid != null) setSelected(gid)
        }
    }

    private val _canvasColor = MutableStateFlow(0xFF000000L)
    val canvasColor: StateFlow<Long> = _canvasColor.asStateFlow()

    // ---- Beat marks: small marks on the timeline ruler (for cutting / animating to the music) ----
    private val _beatMarks = MutableStateFlow<List<Long>>(emptyList())
    /** Project times (ms) of the beat marks, sorted. */
    val beatMarks: StateFlow<List<Long>> = _beatMarks.asStateFlow()

    /**
     * Plus button next to play: puts a beat mark at [ms]. A mark already within [BEAT_MARK_TOLERANCE_MS] of [ms] is
     * taken away instead (the same button removes it again). Returns true when a mark was added.
     */
    fun toggleBeatMark(ms: Long): Boolean {
        val at = ms.coerceAtLeast(0L)
        val list = _beatMarks.value
        val near = list.minByOrNull { kotlin.math.abs(it - at) }
        return if (near != null && kotlin.math.abs(near - at) <= BEAT_MARK_TOLERANCE_MS) {
            _beatMarks.value = list - near
            false
        } else {
            _beatMarks.value = (list + at).sorted()
            true
        }
    }

    // ---- Named timeline markers (build 188): labelled flags on the ruler, saved with the project ----
    private val _markers = MutableStateFlow<List<TimelineMarker>>(emptyList())
    /** Named markers, sorted by time. Like the beat marks they live OUTSIDE the track undo history. */
    val markers: StateFlow<List<TimelineMarker>> = _markers.asStateFlow()

    fun addMarker(timeMs: Long, name: String) {
        _markers.value = ProjectMarkers.add(_markers.value, timeMs, name)
    }

    fun renameMarker(id: String, name: String) {
        _markers.value = ProjectMarkers.rename(_markers.value, id, name)
    }

    fun deleteMarker(id: String) {
        _markers.value = ProjectMarkers.remove(_markers.value, id)
    }

    /** The marker at [timeMs] (within [MARKER_TOLERANCE_MS]), if any — the marker button then edits it instead of adding a second one on the same spot. */
    fun markerNear(timeMs: Long): TimelineMarker? =
        ProjectMarkers.nearest(_markers.value, timeMs, MARKER_TOLERANCE_MS)

    fun setCanvasColor(argb: Long) {
        _canvasColor.value = argb or 0xFF000000L // the canvas is always opaque
    }

    /** Export resolution label, e.g. "1080p FHD" (the number is the short side in pixels). */
    fun setExportResolution(label: String) {
        _exportSettings.value = _exportSettings.value.copy(resolution = label)
    }

    /** Custom bitrate in kbps (500..50000); 0 = automatic. */
    fun setExportBitrate(kbps: Int) {
        _exportSettings.value = _exportSettings.value.copy(
            customBitrateKbps = if (kbps <= 0) 0 else kbps.coerceIn(500, 50_000)
        )
    }

    fun setExportQuality(quality: String) {
        _exportSettings.value = _exportSettings.value.copy(quality = quality)
    }

    /** Export frame rate, limited to 24..60 fps. */
    fun setExportFrameRate(fps: Int) {
        _exportSettings.value = _exportSettings.value.copy(frameRate = fps.coerceIn(24, 60))
    }

    /** Export format: one of [com.example.model.ExportFormats] (MP4 / GIF / PNG sequence). */
    fun setExportFormat(format: String) {
        _exportSettings.value = _exportSettings.value.copy(format = format)
    }

    /** GIF export frame rate, limited to [com.example.model.GifExport.MIN_FPS]..[com.example.model.GifExport.MAX_FPS]. */
    fun setGifFrameRate(fps: Int) {
        _exportSettings.value = _exportSettings.value.copy(
            gifFrameRate = fps.coerceIn(com.example.model.GifExport.MIN_FPS, com.example.model.GifExport.MAX_FPS)
        )
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

    /** Eye control: hide/show a layer on the canvas and in export. */
    fun toggleClipVisibility(clipId: String) {
        pushHistory()
        val target = _tracks.value.flatMap { it.clips }.firstOrNull { it.id == clipId }
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { c ->
                if (c.id == clipId) c.copy(isVisible = !c.isVisible)
                else if (target?.type == LayerType.CHAIN && c.chainId == clipId) c.copy(isVisible = !target.isVisible)  // the layers follow their chain
                else c
            })
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

        val clip = _tracks.value.flatMap { it.clips }.firstOrNull { it.id == clipId } ?: return
        if (clip.type == LayerType.CHAIN) return  // a chain is only a container: split the layers in it
        if (clip.type == LayerType.GROUP) {
            // Group split: two groups, the layers inside are cut at the playhead (see GroupOps.splitGroup).
            val res = GroupOps.splitGroup(LaneLayout.normalize(_tracks.value), clip.id, playhead) ?: return
            pushHistory()
            _tracks.value = res.tracks
            setSelected(res.groupId)
            recomputeTotal()
            return
        }
        // Need room on both sides so each half is usable.
        if (playhead <= clip.startMs + 500 || playhead >= clip.endMs - 500) return

        pushHistory()
        val duration1 = playhead - clip.startMs
        val duration2 = clip.endMs - playhead
        // The In animation stays on the first half, the Out animation goes with the second half.
        val part1 = clip.copy(durationMs = duration1, fadeOutMs = 0L).withoutClipAnim(AnimSlot.OUT)
        val part2 = clip.copy(
            id = UUID.randomUUID().toString(),
            name = "${clip.name} (Split)",
            startMs = playhead,
            durationMs = duration2,
            // Source time moves `speed` times faster than timeline time.
            trimStartMs = clip.trimStartMs + clip.sourceOffsetMs(duration1),
            fadeInMs = 0L
        ).withShiftedKeyframes(-duration1).withoutClipAnim(AnimSlot.IN)

        // Alight Motion style: 1 layer = 1 line.
        // Keep part1 in the original lane; put part2 on its own new lane.
        val withPart1 = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { if (it.id == clipId) part1 else it })
        }
        _tracks.value = placeNewLayer(LaneLayout.normalize(withPart1), part2, clip.chainId)
        setSelected(part2.id)
        recomputeTotal()
    }

    fun deleteSelectedClip() {
        val clipId = _selectedClipId.value ?: return
        if (_tracks.value.flatMap { it.clips }.none { it.id == clipId }) return
        pushHistory()
        // Media is a normal layer (like text/shape): no special gap-closing.
        // Deleting a Chain deletes the layers inside it too.
        val inGroup = GroupOps.descendantsOf(_tracks.value, clipId).map { it.id }.toSet()
        val gone = _tracks.value.flatMap { it.clips }.filter { it.id == clipId || it.chainId == clipId || it.id in inGroup }.map { it.id }.toSet()
        val withoutClip = _tracks.value.map { track ->
            track.copy(clips = track.clips.filter { it.id !in gone })
        }
        _tracks.value = LaneLayout.normalize(withoutClip)
        setSelected(null)
        recomputeTotal()
    }

    fun duplicateSelectedClip() {
        val clipId = _selectedClipId.value ?: return
        val clip = _tracks.value.flatMap { it.clips }.firstOrNull { it.id == clipId } ?: return
        pushHistory()
        if (clip.type == LayerType.GROUP) {
            val copy = GroupOps.duplicate(LaneLayout.normalize(_tracks.value), clip.id)
            if (copy != null) {
                _tracks.value = copy.tracks
                setSelected(copy.groupId)
                recomputeTotal()
            }
            return
        }
        if (clip.type == LayerType.CHAIN) {
            // A copy of the chain with copies of its layers, in a new lane on top.
            val newChainId = UUID.randomUUID().toString()
            val kids = LaneLayout.childrenOf(_tracks.value, clip.id).map {
                it.copy(id = UUID.randomUUID().toString(), chainId = newChainId)
            }
            val g = clip.copy(id = newChainId, name = "${clip.name} (Copy)")
            _tracks.value = LaneLayout.normalize(_tracks.value).let { LaneLayout.newLane(listOf(g) + kids).let { lane -> listOf(lane) + it } }
            setSelected(newChainId)
            recomputeTotal()
            return
        }
        // Same rule for every layer type (media is not special).
        val duplicated = clip.copy(
            id = UUID.randomUUID().toString(),
            name = "${clip.name} (Copy)",
            startMs = clip.endMs + 200
        )
        _tracks.value = placeNewLayer(LaneLayout.normalize(_tracks.value), duplicated, clip.chainId)
        setSelected(duplicated.id)
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

    /**
     * Replace: swaps the picture/video of the selected video or photo layer for a new file.
     * Position, lane, transform, flip and effects stay; the layer never gets longer than the
     * new video, and the crop is reset because the new file has another shape.
     */
    fun replaceSelectedClipWithMedia(m: ImportedMedia) {
        val clipId = _selectedClipId.value ?: return
        pushHistory()
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id == clipId && clip.type == LayerType.VIDEO) {
                    val newDuration = if (m.isImage) clip.durationMs
                    else minOf(clip.durationMs, m.durationMs).coerceAtLeast(250L)
                    clip.copy(
                        name = m.name,
                        uri = m.uri,
                        mimeType = m.mimeType,
                        isImage = m.isImage,
                        sourceDurationMs = m.durationMs,
                        sourceAspect = m.aspect,
                        trimStartMs = 0L,
                        durationMs = newDuration,
                        drawableResId = null,
                        cropWidthPercent = 100f,
                        cropHeightPercent = 100f,
                        cropHorizontalPercent = 50f,
                        cropVerticalPercent = 50f,
                        cropPreset = "Free"
                    )
                } else clip
            })
        }
        recomputeTotal()
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
        val before = clipNow(clipId)
        pushHistory()
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id == clipId) clip.copy(blendMode = mode) else clip
            })
        }
        if (before != null) applyControllerFanOut(before)
    }

    /**
     * Replaces the effects of a layer (Effects panel). No undo step is pushed here: the panel pushes one when it opens
     * ([beginAdjust]), so a whole session of trying effects undoes at once. Effects are not keyframed.
     */
    fun setClipEffects(clipId: String, effects: List<com.example.model.ClipFx>) {
        val before = clipNow(clipId)
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id == clipId) clip.copy(effects = effects) else clip
            })
        }
        if (before != null) applyControllerFanOut(before)
    }

    /**
     * Replaces the masks of a layer (Masks panel). Like [setClipEffects]: no undo step here, the panel pushes one when it
     * opens ([beginAdjust]).
     */
    fun setClipMasks(clipId: String, masks: List<com.example.model.ClipMask>) {
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id == clipId) clip.copy(masks = masks) else clip
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

    // ---- In-app clipboard: copy / paste keyframes + effects (build 176) ----
    //
    // Sarowar's tools 1+2 from the missing-tools audit. The payload rules live in
    // model/ClipClipboard.kt (pure, harness-tested); the clipboard itself is just two
    // nullable fields here, with StateFlows so the tools panel can show Paste only
    // while the clipboard holds something.

    private var keyframesClipboard: Map<AnimProp, List<com.example.model.Keyframe>>? = null
    private var effectsClipboard: List<com.example.model.ClipFx>? = null

    private val _hasKeyframesClipboard = MutableStateFlow(false)
    val hasKeyframesClipboard: StateFlow<Boolean> = _hasKeyframesClipboard.asStateFlow()
    private val _hasEffectsClipboard = MutableStateFlow(false)
    val hasEffectsClipboard: StateFlow<Boolean> = _hasEffectsClipboard.asStateFlow()

    /** Copies every keyframe track of the selected layer. Returns false (and empties the clipboard) when the layer has no keyframes. */
    fun copySelectedKeyframes(): Boolean {
        val clip = _selectedClipId.value?.let { clipNow(it) } ?: return false
        keyframesClipboard = if (clip.keyframes.values.all { it.isEmpty() }) null
        else clip.keyframes.deepCopyKeyframes()
        _hasKeyframesClipboard.value = keyframesClipboard != null
        return keyframesClipboard != null
    }

    /**
     * Pastes the copied keyframes onto the selected layer: the copied set's earliest key
     * lands on the playhead (Alight's rule), each pasted prop replaces the target's keys
     * inside its pasted span only. One undo step. Applies to the selected layer alone —
     * a Controller's connected layers are NOT mirrored (a paste is a per-layer clipboard
     * op, not one of the settled fan-out edits).
     */
    fun pasteKeyframes(): Boolean {
        val copied = keyframesClipboard ?: return false
        val clipId = _selectedClipId.value ?: return false
        val clip = clipNow(clipId) ?: return false
        val anchor = (_currentPlayheadMs.value - clip.startMs).coerceIn(0L, clip.durationMs)
        pushHistory()
        updateClipRaw(clipId) { it.withPastedKeyframes(copied, anchor) }
        return true
    }

    /** Copies the selected layer's whole effect stack (deep copy, keyframed param tails included). Returns false (and empties the clipboard) when the stack is empty. */
    fun copySelectedEffects(): Boolean {
        val clip = _selectedClipId.value?.let { clipNow(it) } ?: return false
        effectsClipboard = if (clip.effects.isEmpty()) null else clip.effects.copyEffects()
        _hasEffectsClipboard.value = effectsClipboard != null
        return effectsClipboard != null
    }

    /**
     * Pastes the copied effect stack onto the selected layer, REPLACING its stack (fresh
     * effect ids). One undo step. Same type gate as the Effects panel; on a Controller the
     * paste rides [setClipEffects], so it fans out to the connected layers like any
     * Effects-panel edit of a Controller.
     */
    fun pasteEffects(): Boolean {
        val copied = effectsClipboard ?: return false
        val clipId = _selectedClipId.value ?: return false
        val target = clipNow(clipId) ?: return false
        if (target.type == LayerType.AUDIO || target.type == LayerType.NULL || target.type == LayerType.CAMERA ||
            target.type == LayerType.CHAIN || target.type == LayerType.GROUP
        ) return false
        pushHistory()
        setClipEffects(clipId, copied.withFreshEffectIds())
        return true
    }

    /**
     * One-tap In / Out animation. Writes real keyframes + easing into the layer (see AnimPresets.kt), so
     * the animation can be customised afterwards like any other keyframes. [preset] null = remove the
     * animation of [slot]. No undo step is pushed here: the Animations panel pushes one when it opens
     * ([beginAdjust]) so a whole session of trying animations undoes at once.
     */
    fun setClipAnim(
        slot: AnimSlot,
        preset: AnimPreset?,
        dir: AnimDir,
        durationMs: Long,
        easing: Easing?,
        canvasW: Float,
        canvasH: Float
    ) {
        val clipId = _selectedClipId.value ?: return
        val target = clipNow(clipId)
        updateClipRaw(clipId) { c ->
            if (preset == null) c.withoutClipAnim(slot)
            else c.withClipAnim(slot, preset, dir, durationMs, easing, canvasW, canvasH)
        }
        // Controller (build 146): the same In / Out animation is applied to every connected
        // layer — each one animates in its own place, like the Controller itself does.
        if (target != null && target.type == LayerType.CONTROLLER) {
            val connected = ControllerFanOut.connected(_tracks.value.flatMap { it.clips }, clipId)
            for (c in connected) {
                updateClipRaw(c.id) { lc ->
                    if (preset == null) lc.withoutClipAnim(slot)
                    else lc.withClipAnim(slot, preset, dir, durationMs, easing, canvasW, canvasH)
                }
            }
        }
    }

    /** Layer rename (Clip tools > Rename). An empty name is ignored. */
    fun renameClip(clipId: String, name: String) {
        val n = name.trim().take(60)
        if (n.isEmpty()) return
        if (_tracks.value.flatMap { it.clips }.none { it.id == clipId }) return
        pushHistory()
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { if (it.id == clipId) it.copy(name = n) else it })
        }
    }

    /**
     * Freeze frame: the video layer holds the picture it shows at the playhead for [holdMs]. The layer is cut at the playhead;
     * a photo layer ([stillUri], the picture at that moment, same place / size / effects) fills the hold, and the rest of the
     * video starts after it. Other layers do not move. Returns false when the playhead is not inside the layer.
     */
    fun insertFreezeFrame(clipId: String, stillUri: String, holdMs: Long): Boolean {
        val clip = _tracks.value.flatMap { it.clips }.firstOrNull { it.id == clipId } ?: return false
        if (clip.type != LayerType.VIDEO || clip.isImage) return false
        val playhead = _currentPlayheadMs.value
        if (!clip.isActiveAt(playhead)) return false
        val hold = holdMs.coerceIn(250L, 30_000L)
        val d1 = playhead - clip.startMs
        val d2 = clip.endMs - playhead
        val part1 = if (d1 > 100L) clip.copy(durationMs = d1, fadeOutMs = 0L).withoutClipAnim(AnimSlot.OUT) else null
        val part2 = if (d2 > 100L) clip.copy(
            id = UUID.randomUUID().toString(),
            name = "${clip.name} (After freeze)",
            startMs = playhead + hold,
            durationMs = d2,
            trimStartMs = clip.trimStartMs + clip.sourceOffsetMs(d1),
            fadeInMs = 0L
        ).withShiftedKeyframes(-d1).withoutClipAnim(AnimSlot.IN) else null
        if (part1 == null && part2 == null) return false

        pushHistory()
        // The still looks exactly like the layer at this moment (animated position / size / opacity are baked in).
        val baked = com.example.engine.NativeRenderAt.renderAt(clip, playhead)
        val still = baked.copy(
            id = UUID.randomUUID().toString(),
            name = "Freeze frame",
            isImage = true,
            uri = stillUri,
            mimeType = "image/png",
            startMs = playhead,
            durationMs = hold,
            sourceDurationMs = hold,
            trimStartMs = 0L,
            speed = 1f,
            keyframes = emptyMap(),
            animIn = null,
            animOut = null,
            fadeInMs = 0L,
            fadeOutMs = 0L,
            volume = 0f,
            cutoutDir = null, cutoutFps = 0f, cutoutStartMs = 0L, cutoutFrames = 0,
            faceBlurDir = null, faceBlurFps = 0f, faceBlurStartMs = 0L, faceBlurFrames = 0,
            trackFollow = null, cornerPin = null, planarRemove = null
        )
        var tracks = _tracks.value.map { track ->
            track.copy(clips = track.clips.mapNotNull { c ->
                if (c.id != clipId) c else part1   // no first part (freeze at the very start): the layer is replaced by the later part
            })
        }
        tracks = LaneLayout.normalize(tracks)
        if (part2 != null) tracks = placeNewLayer(tracks, part2, clip.chainId)
        tracks = placeNewLayer(tracks, still, clip.chainId)
        _tracks.value = tracks
        setSelected(still.id)
        recomputeTotal()
        return true
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
     * Adds a layer drawn with the vector drawing tool (build 166): [drawn] is the pure
     * geometry [VectorDraw] built from the stroke (normalised segments + placement), so
     * the layer is an ordinary PATH shape — the same storage an imported Alight `<path>`
     * shape gets — placed and sized exactly where the user drew it. [fillMode] /
     * [fillColor] paint the inside of a closed drawing; an open line is stroke-only.
     */
    fun addDrawnPath(
        drawn: VectorDraw.DrawnPath,
        durationMs: Long = 4000L,
        fillMode: Int,
        fillColor: Long,
        strokeWidth: Float,
        strokeColor: Long = 0xFFFFFFFF,
        /** Layer name: "Drawing" for the freehand tool, "Pen" for the point-based pen (build 177). */
        name: String = "Drawing"
    ) {
        pushHistory()
        val newClip = TimelineClip(
            id = UUID.randomUUID().toString(),
            name = name,
            type = LayerType.SHAPE,
            startMs = _currentPlayheadMs.value,
            durationMs = durationMs.coerceIn(250L, 60_000L),
            color = 0xFFFF3377,
            textColor = fillColor,
            fillMode = fillMode,
            shapePattern = "PATH",
            pathSegments = drawn.segments,
            shapeOffsetX = drawn.offsetX,
            shapeOffsetY = drawn.offsetY,
            shapeScaleX = drawn.shapeScale,
            shapeScaleY = drawn.shapeScale,
            strokeWidth = strokeWidth.coerceIn(0f, 40f),
            strokeColor = strokeColor,
            transformX = drawn.offsetX,
            transformY = drawn.offsetY,
            scalePercent = (drawn.shapeScale * 100f).coerceAtLeast(1f)
        )
        _tracks.value = placeNewLayer(_tracks.value, newClip)
        setSelected(newClip.id)
        recomputeTotal()
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
     * The Chain a new layer goes into: the selected layer if it is a Chain, or the Chain of the selected layer (so you can
     * keep adding). Null = the new layer gets its own lane as usual.
     */
    private fun chainTargetId(): String? {
        val sel = _selectedClipId.value ?: return null
        val c = _tracks.value.flatMap { it.clips }.firstOrNull { it.id == sel } ?: return null
        return if (c.type == LayerType.CHAIN) c.id else c.chainId
    }

    /** A new layer: inside the selected Chain (side by side, behind a layer that is already there), or on its own lane. */
    private fun placeNewLayer(tracks: List<TimelineTrack>, clip: TimelineClip, chainId: String? = chainTargetId()): List<TimelineTrack> {
        // Edit Group: a new layer goes INTO the group being edited (it follows the group like the layers already there).
        val scope = GroupOps.validScope(tracks, _editingGroupId.value)
        var c = clip
        if (scope != null && clip.groupId == null) {
            if (GroupOps.canGroup(clip)) {
                val rig = com.example.engine.ParentRig(tracks.flatMap { it.clips }) { x, t -> com.example.engine.NativeRenderAt.renderAt(x, t) }
                val pose = rig.poseOf(scope, _currentPlayheadMs.value)?.let { GroupOps.Pose(it.transformX, it.transformY, it.scalePercent, it.rotationDeg) } ?: GroupOps.Pose.REST
                c = clip.copy(groupId = scope, parent = GroupOps.linkTo(scope, pose))
            } else {
                _editingGroupId.value = null  // audio / camera / chain cannot be inside a group: go out so the new layer is seen
            }
        }
        return if (chainId != null && c.type != LayerType.CHAIN) LaneLayout.placeInChain(tracks, chainId, c)
        else LaneLayout.place(tracks, c)
    }

    /** Adds a Chain layer at the playhead and selects it. While it (or a layer in it) is selected, added layers go inside it. */
    fun addChainLayer(durationMs: Long = 4000L) {
        addClip(type = LayerType.CHAIN, name = "Chain ${_tracks.value.flatMap { it.clips }.count { it.type == LayerType.CHAIN } + 1}", durationMs = durationMs.coerceIn(250L, 60_000L))
    }

    /** Adds a Null object (invisible helper layer, parent for other layers) at the playhead and selects it. */
    fun addNullLayer(durationMs: Long = 5000L) {
        val n = _tracks.value.flatMap { it.clips }.count { it.type == LayerType.NULL } + 1
        addClip(type = LayerType.NULL, name = "Null $n", durationMs = durationMs.coerceIn(250L, 60_000L))
    }

    /**
     * Adds a Controller layer (build 145; semantics v2 build 146) at the playhead and selects
     * it. An invisible EDIT handle: layers connected to it (Parent picker) are edited together
     * through it — every edit of the Controller fans out onto each connected layer in its own
     * place (see [ControllerFanOut]). It is not a parent and drives nothing.
     */
    fun addControllerLayer(durationMs: Long = 5000L) {
        addClip(type = LayerType.CONTROLLER, name = "Controller", durationMs = durationMs.coerceIn(250L, 60_000L))
    }

    /** Adds a 3D camera layer at the playhead and selects it. Layers in the lanes below it are seen through it. */
    fun addCameraLayer(durationMs: Long = 5000L) {
        addClip(type = LayerType.CAMERA, name = "Camera", durationMs = durationMs.coerceIn(250L, 60_000L))
    }

    /**
     * Links [clipId] to the layer [parentId] (null = no link). The Parent picker offers two
     * different relations under one list, and each pick changes only its own relation:
     *  - a Null or a Group becomes the layer's PARENT: the link saves the parent's pose at the
     *    playhead, so the layer stays where it is and then follows the parent's move / turn /
     *    scale (ParentRig). A layer that gets another parent than its group leaves the group.
     *  - a Controller becomes the layer's CONNECTION (build 146): it is stored in
     *    [TimelineClip.controllerId] and changes nothing about the layer's parent or rendering —
     *    it only means edits of that Controller fan out onto the layer ([ControllerFanOut]). A
     *    legacy build-145 parent link at a Controller is cleared (it was this same connection).
     * Picking null clears BOTH relations. Returns false when it cannot be done (unknown layer,
     * self-link, not a parent kind, or a parent loop).
     */
    fun setClipParent(clipId: String, parentId: String?): Boolean {
        val all = _tracks.value.flatMap { it.clips }
        if (all.none { it.id == clipId }) return false
        if (parentId == null) {
            pushHistory()
            _tracks.value = _tracks.value.map { track ->
                track.copy(clips = track.clips.map {
                    if (it.id == clipId) it.copy(parent = null, controllerId = null) else it
                })
            }
            return true
        }
        if (parentId == clipId) return false
        val target = all.firstOrNull { it.id == parentId } ?: return false
        if (target.type == LayerType.CONTROLLER) {
            pushHistory()
            _tracks.value = _tracks.value.map { track ->
                track.copy(clips = track.clips.map {
                    if (it.id == clipId) it.copy(
                        controllerId = parentId,
                        parent = if (it.parent?.id == parentId) null else it.parent
                    ) else it
                })
            }
            return true
        }
        var link: com.example.model.ParentLink? = null
        run {
            val rig = com.example.engine.ParentRig(all) { c, t -> com.example.engine.NativeRenderAt.renderAt(c, t) }
            if (rig.wouldLoop(clipId, parentId)) return false
            val pose = rig.poseOf(parentId, _currentPlayheadMs.value) ?: return false
            link = com.example.model.ParentLink(parentId, pose.transformX, pose.transformY, pose.scalePercent, pose.rotationDeg)
        }
        pushHistory()
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map {
                // A layer that gets another parent than its group leaves the group. Its
                // Controller connection (controllerId) is a separate relation and survives;
                // a legacy controller link sitting in the parent field is replaced with the
                // rest of the link, as the picker showed only one current link.
                if (it.id == clipId) it.copy(parent = link, groupId = if (it.groupId != null && link?.id != it.groupId) null else it.groupId) else it
            })
        }
        return true
    }

    /**
     * Ungroup: the layers of the selected Group go out of it and the Group layer is removed. A layer that has no keyframes
     * (in a group without keyframes) keeps the pose it shows now, so nothing jumps; otherwise it goes back to its own pose.
     * Returns true when something was ungrouped.
     */
    fun ungroupSelected(): Boolean {
        val gid = _selectedClipId.value ?: return false
        val all = _tracks.value.flatMap { it.clips }
        val group = all.firstOrNull { it.id == gid && it.type == LayerType.GROUP } ?: return false
        val t = _currentPlayheadMs.value
        val rig = com.example.engine.ParentRig(all) { c, tt -> com.example.engine.NativeRenderAt.renderAt(c, tt) }
        // Static means: nothing on the way from the layer up to the top is keyframed or tracked, so one pose is the whole truth.
        fun isStatic(c: TimelineClip): Boolean {
            var cur: TimelineClip? = c
            var n = 0
            while (cur != null && n++ < 10) {
                if (cur.keyframes.values.any { it.isNotEmpty() } || cur.trackFollow != null) return false
                cur = cur.parent?.let { link -> all.firstOrNull { it.id == link.id } }
            }
            return true
        }
        pushHistory()
        _tracks.value = GroupOps.ungroup(
            _tracks.value, gid,
            bake = { c ->
                if (!isStatic(c)) c
                else {
                    val a = rig.adjusted(c, t)
                    c.copy(
                        transformX = a.transformX, transformY = a.transformY,
                        shapeOffsetX = a.transformX, shapeOffsetY = a.transformY,  // the shape offset mirrors the position
                        scalePercent = a.scalePercent, rotationDeg = a.rotationDeg,
                        opacity = a.opacity * rig.inheritedGroupOpacity(c, t)  // the group's own opacity goes to the layers when it is gone
                    )
                }
            },
            poseOf = { id -> rig.poseOf(id, t)?.let { GroupOps.Pose(it.transformX, it.transformY, it.scalePercent, it.rotationDeg) } }
        )
        setSelected(null)
        recomputeTotal()
        return group.id.isNotEmpty()
    }

    /**
     * Adds a sticker layer at the playhead. [assetPath] is the sticker's path inside the assets
     * (see StickerLibrary): a PNG file or an animated frame folder.
     */
    fun addSticker(assetPath: String, name: String, durationMs: Long = 4000L) {
        addClip(
            type = LayerType.STICKER,
            name = name,
            durationMs = durationMs.coerceIn(250L, 60_000L),
            uri = com.example.engine.StickerLibrary.URI_PREFIX + assetPath,
            shapeScaleX = 1f,
            shapeScaleY = 1f
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

    /**
     * Adds one text layer per caption (Auto Captions), all in ONE undo step and ONE update of the
     * timeline. [parentStartMs] is the timeline start of the layer the captions were made from; each
     * caption's times are relative to it. [offsetYPx] moves the captions down from the centre of the
     * canvas (the lower third looks right). Every layer gets its own lane (one layer per line, like
     * every other layer); the first caption is the top lane. Returns how many were added.
     */
    fun addCaptionLayers(
        parentStartMs: Long,
        captions: List<com.example.engine.CaptionBuilder.Caption>,
        offsetYPx: Float
    ): Int {
        if (captions.isEmpty()) return 0
        pushHistory()
        val clips = captions.map { c ->
            TimelineClip(
                id = UUID.randomUUID().toString(),
                name = "Caption",
                type = LayerType.TEXT,
                startMs = (parentStartMs + c.relStartMs).coerceAtLeast(0L),
                durationMs = (c.relEndMs - c.relStartMs).coerceAtLeast(250L),
                color = 0xFF3992CE,
                text = c.text,
                textColor = 0xFFFFFFFF,
                fontWeight = 700,
                textStrokeThickness = 14f,
                textStrokeColor = 0xFF000000,
                scalePercent = 70f,
                transformY = offsetYPx
            )
        }
        _editingGroupId.value = null  // captions go to the top level: leave the group so they can be seen
        _tracks.value = LaneLayout.placeAll(_tracks.value, clips)
        setSelected(null)
        recomputeTotal()
        return clips.size
    }

    /**
     * Templates (build 143; rig builds 144 + 146): inserts the Carousel template into the
     * CURRENT project as ONE Group layer at the playhead — the whole composition (the
     * "Carousel Null" rig layer carrying the motion, the "Carousel Controller" edit handle,
     * background, 8 shadow twins, 8 photo cards) as ordinary children inside it, fitted to
     * the canvas by the group's own transform (see [CarouselTemplate.buildGroup]). One undo
     * step; the group ends up selected so the user sees what landed. [previewWidthPx] /
     * [previewHeightPx] are the editor preview frame's size (the stored-units basis).
     * [params] are the dialog's chosen settings (build 154: card count / turn duration /
     * direction; the defaults are the reference's 8 cards, 5 s, clockwise).
     * Returns the group's id.
     */
    fun insertCarouselTemplate(
        backgroundUri: String,
        cardUris: List<String>,
        previewWidthPx: Float,
        previewHeightPx: Float,
        params: CarouselParams = CarouselParams()
    ): String? {
        if (backgroundUri.isBlank() || cardUris.size != params.cardCount) return null
        val insert = CarouselTemplate.buildGroup(
            previewWidthPx, previewHeightPx, backgroundUri, cardUris,
            insertAtMs = _currentPlayheadMs.value,
            idBase = "carousel-" + UUID.randomUUID().toString().take(8),
            params = params
        )
        pushHistory()
        _editingGroupId.value = null  // the group lands at the top level: leave any open group so it can be seen
        _tracks.value = LaneLayout.placeAll(_tracks.value, insert.lanesTopFirst)
        setSelected(insert.group.id)
        recomputeTotal()
        return insert.group.id
    }

    /**
     * Templates (text-animation template #1): inserts the "Pop Title" template into the
     * CURRENT project as ONE Group layer at the playhead — one ordinary TEXT layer carrying
     * the whole title animation on its own keyframes plus its accent bar, authored against
     * the live preview frame (see [PopTitleTemplate.buildGroup]). One undo step; the group
     * ends up selected. No generated pictures are needed, so this runs on the caller's
     * thread. Returns the group's id.
     */
    fun insertPopTitleTemplate(
        previewWidthPx: Float,
        previewHeightPx: Float
    ): String {
        val insert = PopTitleTemplate.buildGroup(
            previewWidthPx, previewHeightPx,
            insertAtMs = _currentPlayheadMs.value,
            idBase = "poptitle-" + UUID.randomUUID().toString().take(8)
        )
        pushHistory()
        _editingGroupId.value = null  // the group lands at the top level, like the Carousel's
        _tracks.value = LaneLayout.placeAll(_tracks.value, insert.lanesTopFirst)
        setSelected(insert.group.id)
        recomputeTotal()
        return insert.group.id
    }

    /**
     * User templates ("Save as Template", Templates v2): inserts a template the person
     * saved earlier ([com.example.engine.UserTemplateStore]) into the CURRENT project as
     * ONE Group layer at the playhead — the same insert conventions as the built-in
     * templates: the saved subtree is instantiated under fresh ids (internal links
     * remapped, see [UserTemplateOps.instantiate]), one undo step, the group lands at the
     * top level and ends up selected. The stored template is never mutated, so it can be
     * inserted again (here or in another project) any number of times. Returns the new
     * group's id, or null for an empty template.
     */
    fun insertUserTemplate(template: SavedTemplate): String? {
        val lanes = UserTemplateOps.instantiate(template, _currentPlayheadMs.value)
        if (lanes.isEmpty()) return null
        pushHistory()
        _editingGroupId.value = null  // the group lands at the top level, like the built-ins'
        _tracks.value = LaneLayout.placeAll(_tracks.value, lanes)
        setSelected(lanes.first().id)
        recomputeTotal()
        return lanes.first().id
    }

    /**
     * Templates (logo-animation template #1): inserts the "Logo Reveal" template into the
     * CURRENT project as ONE Group layer at the playhead — a logo slot (an image-filled
     * shape fed by the generated placeholder, replaced like a Carousel card) that pops in,
     * with a plain brand-name TEXT layer rising in under it, then a static hold (see
     * [LogoRevealTemplate.buildGroup]). One undo step; the group ends up selected.
     * [previewWidthPx] / [previewHeightPx] are the editor preview frame's size (the
     * stored-units basis). Returns the group's id, or null when [logoUri] is blank.
     */
    fun insertLogoRevealTemplate(
        logoUri: String,
        previewWidthPx: Float,
        previewHeightPx: Float
    ): String? {
        if (logoUri.isBlank()) return null
        val insert = LogoRevealTemplate.buildGroup(
            previewWidthPx, previewHeightPx, logoUri,
            insertAtMs = _currentPlayheadMs.value,
            idBase = "logoreveal-" + UUID.randomUUID().toString().take(8)
        )
        pushHistory()
        _editingGroupId.value = null  // the group lands at the top level, like the Carousel's
        _tracks.value = LaneLayout.placeAll(_tracks.value, insert.lanesTopFirst)
        setSelected(insert.group.id)
        recomputeTotal()
        return insert.group.id
    }

    /**
     * Templates (text-animation template #2): inserts the "Word Pop" template into the
     * CURRENT project as ONE Group layer at the playhead — one ordinary TEXT layer per
     * word of the sample sentence, each popping in in turn on its own keyframes, authored
     * against the live preview frame (see [WordPopTemplate.buildGroup]). One undo step;
     * the group ends up selected. No generated pictures are needed, so this runs on the
     * caller's thread. Returns the group's id.
     */
    fun insertWordPopTemplate(
        previewWidthPx: Float,
        previewHeightPx: Float
    ): String {
        val insert = WordPopTemplate.buildGroup(
            previewWidthPx, previewHeightPx,
            insertAtMs = _currentPlayheadMs.value,
            idBase = "wordpop-" + UUID.randomUUID().toString().take(8)
        )
        pushHistory()
        _editingGroupId.value = null  // the group lands at the top level, like the Pop Title's
        _tracks.value = LaneLayout.placeAll(_tracks.value, insert.lanesTopFirst)
        setSelected(insert.group.id)
        recomputeTotal()
        return insert.group.id
    }

    /**
     * Templates: inserts the "Lower Third" template into the CURRENT project as ONE
     * Group layer at the playhead — an amber accent bar growing at the bottom left, a
     * plain name TEXT layer staging in beside it and a plain subtitle TEXT layer at a
     * static 58% staging in after it, then a shared slide/fade exit (see
     * [LowerThirdTemplate.buildGroup] — the design banked in the 2026-11-13 STOP note).
     * One undo step; the group ends up selected. No generated pictures are needed, so
     * this runs on the caller's thread. Returns the group's id.
     */
    fun insertLowerThirdTemplate(
        previewWidthPx: Float,
        previewHeightPx: Float
    ): String {
        val insert = LowerThirdTemplate.buildGroup(
            previewWidthPx, previewHeightPx,
            insertAtMs = _currentPlayheadMs.value,
            idBase = "lowerthird-" + UUID.randomUUID().toString().take(8)
        )
        pushHistory()
        _editingGroupId.value = null  // the group lands at the top level, like the Pop Title's
        _tracks.value = LaneLayout.placeAll(_tracks.value, insert.lanesTopFirst)
        setSelected(insert.group.id)
        recomputeTotal()
        return insert.group.id
    }

    /**
     * Templates (reference commission): inserts the "Genie" template into the CURRENT
     * project as ONE Group layer at the playhead — the pill that stretches into the
     * tilted avatar bar, the scrolling avatar row, and the four profile cards cycling
     * one at a time (see [GenieTemplate.buildGroup]). One undo step; the group ends up
     * selected. Returns the group's id, or null when the generated pictures are
     * missing ([avatarUris] must hold 6, [photoUris] 4).
     */
    fun insertGenieTemplate(
        avatarUris: List<String>,
        photoUris: List<String>,
        previewWidthPx: Float,
        previewHeightPx: Float
    ): String? {
        if (avatarUris.size != 6 || photoUris.size != 4) return null
        val insert = GenieTemplate.buildGroup(
            previewWidthPx, previewHeightPx, avatarUris, photoUris,
            insertAtMs = _currentPlayheadMs.value,
            idBase = "genie-" + UUID.randomUUID().toString().take(8)
        )
        pushHistory()
        _editingGroupId.value = null  // the group lands at the top level, like the Carousel's
        _tracks.value = LaneLayout.placeAll(_tracks.value, insert.lanesTopFirst)
        setSelected(insert.group.id)
        recomputeTotal()
        return insert.group.id
    }

    /**
     * Templates (reference commission batch 2): inserts the "Pie Counter" template —
     * a pie that sweeps clockwise from the top while its % counts 0 -> 100 (see
     * [PieCounterTemplate.buildGroup]). No generated pictures are needed. One undo
     * step; the group ends up selected. Returns the group's id.
     */
    fun insertPieCounterTemplate(
        previewWidthPx: Float,
        previewHeightPx: Float
    ): String {
        val insert = PieCounterTemplate.buildGroup(
            previewWidthPx, previewHeightPx,
            insertAtMs = _currentPlayheadMs.value,
            idBase = "pie-" + UUID.randomUUID().toString().take(8)
        )
        pushHistory()
        _editingGroupId.value = null
        _tracks.value = LaneLayout.placeAll(_tracks.value, insert.lanesTopFirst)
        setSelected(insert.group.id)
        recomputeTotal()
        return insert.group.id
    }

    /**
     * Templates (reference commission batch 2): inserts the "Donut Chart" template —
     * five segments building one by one around the ring, each with its % label and
     * its legend line (see [DonutChartTemplate.buildGroup]). No generated pictures
     * are needed. One undo step; the group ends up selected. Returns the group's id.
     */
    fun insertDonutChartTemplate(
        previewWidthPx: Float,
        previewHeightPx: Float
    ): String {
        val insert = DonutChartTemplate.buildGroup(
            previewWidthPx, previewHeightPx,
            insertAtMs = _currentPlayheadMs.value,
            idBase = "donut-" + UUID.randomUUID().toString().take(8)
        )
        pushHistory()
        _editingGroupId.value = null
        _tracks.value = LaneLayout.placeAll(_tracks.value, insert.lanesTopFirst)
        setSelected(insert.group.id)
        recomputeTotal()
        return insert.group.id
    }

    /**
     * Templates (reference commission batch 2): inserts the "Social Growth" template —
     * the upload pill, the progress ring, the CREATE / POST... / ELEVATE titles and the
     * header counters above the tile grid (see [SocialGrowthTemplate.buildGroup]).
     * One undo step; the group ends up selected. Returns the group's id, or null when
     * [avatarUri] is blank.
     */
    fun insertSocialGrowthTemplate(
        avatarUri: String,
        previewWidthPx: Float,
        previewHeightPx: Float
    ): String? {
        if (avatarUri.isBlank()) return null
        val insert = SocialGrowthTemplate.buildGroup(
            previewWidthPx, previewHeightPx, avatarUri,
            insertAtMs = _currentPlayheadMs.value,
            idBase = "growth-" + UUID.randomUUID().toString().take(8)
        )
        pushHistory()
        _editingGroupId.value = null
        _tracks.value = LaneLayout.placeAll(_tracks.value, insert.lanesTopFirst)
        setSelected(insert.group.id)
        recomputeTotal()
        return insert.group.id
    }

    /** Remembers the current state for undo before a Text edit session on an existing layer. */
    fun beginTextEdit() = pushHistory()

    /**
     * Live edit of one layer (used by Text edit). It does not push undo steps itself:
     * one step is pushed when the editor opens, so a whole editing session undoes at once.
     */
    fun updateClip(clipId: String, change: (TimelineClip) -> TimelineClip) {
        val before = clipNow(clipId)
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map {
                if (it.id == clipId) commitKeyframed(it, change(it), vsResolved = false) else it
            })
        }
        if (before != null) applyControllerFanOut(before)
    }

    /**
     * Live edit from the Color panel. [change] is applied to the layer AS IT LOOKS at the playhead,
     * so a colour that has keyframes is compared with its animated value: an edit becomes a keyframe
     * at the playhead (the Transform rule), the stored colour stays as the fallback.
     */
    fun updateClipLive(clipId: String, change: (TimelineClip) -> TimelineClip) = updateClipColor(clipId, change)

    fun updateClipColor(clipId: String, change: (TimelineClip) -> TimelineClip) {
        val before = clipNow(clipId)
        val t = _currentPlayheadMs.value
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map {
                if (it.id == clipId) commitKeyframed(it, change(it.at(t)), vsResolved = true) else it
            })
        }
        if (before != null) applyControllerFanOut(before)
    }

    /** Edit without any keyframe handling (Color reset also drops the colour keyframes). */
    fun updateClipNoKeyframes(clipId: String, change: (TimelineClip) -> TimelineClip) {
        val before = clipNow(clipId)
        updateClipRaw(clipId, change)
        // The Color / Blend resets ride this path: on a Controller they fan out like any
        // other edit of those panels (build 155).
        if (before != null) applyControllerFanOut(before)
    }

    /**
     * Replaces a PATH layer's outline (build 177, node editing): the segments are the
     * layer's geometry, not a keyframable property, so the edit rides the no-keyframes
     * path. Undo is the caller's: one [beginAdjust] per gesture / discrete edit.
     */
    fun setClipPathSegments(clipId: String, segments: List<PathSeg>) {
        updateClipNoKeyframes(clipId) { it.copy(pathSegments = segments) }
    }

    /**
     * Diamond of a panel that edits several properties at once (gradient colours): if the playhead
     * sits on a keyframe of any of them those keyframes are removed, otherwise every property gets
     * one at the playhead.
     */
    fun toggleKeyframes(clipId: String, props: List<AnimProp>) {
        if (props.isEmpty()) return
        val clip = _tracks.value.flatMap { it.clips }.firstOrNull { it.id == clipId } ?: return
        val t = _currentPlayheadMs.value
        if (t < clip.startMs || t > clip.endMs) return
        val rel = t - clip.startMs
        val on = props.filter { clip.keyframes[it].orEmpty().indexOnTime(rel) >= 0 }
        pushHistory()
        if (on.isNotEmpty()) {
            updateClipRaw(clipId) { c ->
                var out = c
                for (p in on) {
                    val idx = out.keyframes[p].orEmpty().indexOnTime(rel)
                    if (idx >= 0) out = out.withoutKeyframe(p, idx)
                }
                out
            }
            for (p in on) mirrorKeyframeToggle(clip, p, added = false)
        } else {
            val resolved = clip.at(t)
            updateClipRaw(clipId) { c ->
                var out = c
                for (p in props) {
                    val (a, b) = resolved.propValues(p)
                    out = out.withKeyframe(p, rel, a, b)
                }
                out
            }
            for (p in props) mirrorKeyframeToggle(clip, p, added = true)
        }
    }

    /** Same as [updateClip] but without the keyframe handling (keyframe edits themselves). */
    private fun updateClipRaw(clipId: String, change: (TimelineClip) -> TimelineClip) {
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { if (it.id == clipId) change(it) else it })
        }
    }

    /**
     * Keyframe rule for every transform edit. A property WITHOUT keyframes is edited as before.
     * A property WITH keyframes keeps its plain field untouched; an edit becomes a keyframe at the
     * playhead (the one it sits on is changed, otherwise a new one is added) - Alight Motion style.
     *
     * [vsResolved]: the caller sends the complete state it showed (the Transform panel), so a value
     * counts as edited when it differs from what the layer looks like at the playhead. Otherwise it
     * counts as edited when it differs from the stored field.
     */
    private fun commitKeyframed(old: TimelineClip, new: TimelineClip, vsResolved: Boolean): TimelineClip {
        if (old.keyframes.isEmpty()) return new
        val t = _currentPlayheadMs.value
        val rel = (t - old.startMs).coerceIn(0L, old.durationMs)
        val resolved = old.at(t)
        var out = new
        for (p in old.keyframes.keys) {
            val (na, nb) = new.propValues(p)
            val (ra, rb) = (if (vsResolved) resolved else old).propValues(p)
            val (oa, ob) = old.propValues(p)
            val changed = kotlin.math.abs(na - ra) > 0.0005f || kotlin.math.abs(nb - rb) > 0.0005f
            out = out.withProp(p, oa, ob)
            if (changed) out = out.withKeyframe(p, rel, na, nb)
        }
        return out
    }

    // ---- Controller fan-out (build 146) ------------------------------------
    //
    // A Controller is a simultaneous multi-layer edit handle: when an edit changes a
    // Controller, the same change is fanned out onto every layer connected to it, in each
    // layer's own place (see ControllerFanOut for the delta / value rules). The edit
    // functions below capture the Controller's stored state BEFORE the edit and call
    // [applyControllerFanOut] after it; the undo step is the caller's own (the fan-out
    // adds none). Keyframe toggles / easing / In-Out animations are mirrored as the same
    // operation instead ([mirrorKeyframeToggle] / the setClipAnim / setKeyframeEasing
    // mirrors), because they edit the track structure rather than a playhead value.

    /** The stored state of a layer right now (null when it is not on the timeline). */
    private fun clipNow(clipId: String): TimelineClip? =
        _tracks.value.flatMap { it.clips }.firstOrNull { it.id == clipId }

    /**
     * Fans the edit that took the Controller [before] to its current stored state out onto
     * every connected layer. No-op for other layer kinds and for unchanged Controllers.
     */
    private fun applyControllerFanOut(before: TimelineClip) {
        if (before.type != LayerType.CONTROLLER) return
        val all = _tracks.value.flatMap { it.clips }
        val after = all.firstOrNull { it.id == before.id } ?: return
        if (after == before) return
        val connected = ControllerFanOut.connected(all, before.id).map { it.id }.toSet()
        if (connected.isEmpty()) return
        val t = _currentPlayheadMs.value
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { c ->
                if (c.id in connected) ControllerFanOut.fanned(c, before, after, t) else c
            })
        }
    }

    /**
     * Mirrors a keyframe diamond toggle made on the Controller [controller] onto its
     * connected layers: [added] true = each connected layer gets a keyframe of [prop] at
     * the same absolute time holding its OWN value there; false = the keyframe it has at
     * that time on [prop] is removed.
     */
    private fun mirrorKeyframeToggle(controller: TimelineClip, prop: AnimProp, added: Boolean) {
        if (controller.type != LayerType.CONTROLLER) return
        val t = _currentPlayheadMs.value
        val all = _tracks.value.flatMap { it.clips }
        for (c in ControllerFanOut.connected(all, controller.id)) {
            if (t < c.startMs || t > c.endMs) continue
            val rel = t - c.startMs
            updateClipRaw(c.id) { lc ->
                if (added) {
                    val (a, b) = lc.at(t).propValues(prop)
                    lc.withKeyframe(prop, rel, a, b)
                } else {
                    val i = lc.keyframes[prop].orEmpty().indexOnTime(rel)
                    if (i >= 0) lc.withoutKeyframe(prop, i) else lc
                }
            }
        }
    }

    /**
     * Motion tracking: makes the layer follow a tracked object, frame by frame (no keyframes are
     * written). [follow] = null removes the tracking. [undoStep] = false for the live updates while a
     * track runs (the caller pushed one undo step when the track began).
     */
    fun setTrackFollow(clipId: String, follow: com.example.model.TrackFollow?, undoStep: Boolean = true) {
        if (undoStep) pushHistory()
        updateClipRaw(clipId) { it.copy(trackFollow = follow) }
    }

    /** Corner pin: draws the layer on a planar-tracked surface of a video. [pin] = null removes it. */
    fun setCornerPin(clipId: String, pin: com.example.model.CornerPin?, undoStep: Boolean = true) {
        if (undoStep) pushHistory()
        updateClipRaw(clipId) { it.copy(cornerPin = pin) }
    }

    /** Remove: covers a flaw on a planar-tracked surface of the video with a clean patch. [remove] = null takes it away. */
    fun setPlanarRemove(clipId: String, remove: com.example.model.PlanarRemove?, undoStep: Boolean = true) {
        if (undoStep) pushHistory()
        updateClipRaw(clipId) { it.copy(planarRemove = remove) }
    }

    /**
     * Diamond button: adds a keyframe of [prop] at the playhead (with the value the layer has there),
     * or removes the one the playhead sits on. Only works while the playhead is inside the layer.
     */
    fun toggleKeyframe(clipId: String, prop: AnimProp) {
        val clip = _tracks.value.flatMap { it.clips }.firstOrNull { it.id == clipId } ?: return
        val t = _currentPlayheadMs.value
        if (t < clip.startMs || t > clip.endMs) return
        val rel = t - clip.startMs
        val idx = clip.keyframes[prop].orEmpty().indexOnTime(rel)
        pushHistory()
        if (idx >= 0) {
            updateClipRaw(clipId) { it.withoutKeyframe(prop, idx) }
            mirrorKeyframeToggle(clip, prop, added = false)
        } else {
            val (a, b) = clip.at(t).propValues(prop)
            updateClipRaw(clipId) { it.withKeyframe(prop, rel, a, b) }
            mirrorKeyframeToggle(clip, prop, added = true)
        }
    }

    /**
     * Sets the easing of the segment that starts at the keyframe [keyframeTimeMs] (relative time).
     * Live while a handle is dragged; the caller invokes [beginAdjust] once when the drag starts.
     */
    fun setKeyframeEasing(clipId: String, prop: AnimProp, keyframeTimeMs: Long, easing: Easing) {
        val target = clipNow(clipId)
        updateClipRaw(clipId) { c ->
            val idx = c.keyframes[prop].orEmpty().indexOfFirst { it.timeMs == keyframeTimeMs }
            if (idx < 0) c else c.withEasing(prop, idx, easing)
        }
        // Controller (build 146): the same segment easing lands on each connected layer's
        // keyframe at the same absolute time (the same operation, mirrored).
        if (target != null && target.type == LayerType.CONTROLLER) {
            val absTime = target.startMs + keyframeTimeMs
            val all = _tracks.value.flatMap { it.clips }
            for (c in ControllerFanOut.connected(all, target.id)) {
                updateClipRaw(c.id) { lc ->
                    val idx = lc.keyframes[prop].orEmpty().indexOfFirst { it.timeMs == absTime - lc.startMs }
                    if (idx < 0) lc else lc.withEasing(prop, idx, easing)
                }
            }
        }
    }

    /** Scale + rotation from the selection frame handles (values are absolute, as the user sees them). */
    fun setScaleRotation(clipId: String, scalePercent: Float, rotationDeg: Float) {
        val before = clipNow(clipId)
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map {
                if (it.id == clipId) {
                    commitKeyframed(it, it.copy(scalePercent = scalePercent, rotationDeg = rotationDeg), vsResolved = true)
                } else it
            })
        }
        if (before != null) applyControllerFanOut(before)
    }

    /**
     * Moves a layer earlier/later on the timeline (horizontal drag).
     * [deltaMs] is added to startMs; start is clamped to ≥ 0.
     * Call [beginAdjust] once before a drag session so undo is a single step.
     */
    fun moveClipInTime(clipId: String, deltaMs: Long) {
        if (deltaMs == 0L) return
        val clip = _tracks.value.flatMap { it.clips }.firstOrNull { it.id == clipId }
        if (clip != null && clip.type == LayerType.CHAIN) {
            // The whole chain moves: its layers keep their places inside it.
            val d = deltaMs.coerceAtLeast(-clip.startMs)
            _tracks.value = _tracks.value.map { track ->
                track.copy(clips = track.clips.map { c ->
                    if (c.id == clipId || c.chainId == clipId) c.copy(startMs = (c.startMs + d).coerceAtLeast(0L)) else c
                })
            }
            recomputeTotal()
            return
        }
        if (clip != null && clip.type == LayerType.GROUP) {
            // The whole group moves: the layers inside it (also the ones in groups inside it) keep their places.
            val d = deltaMs.coerceAtLeast(-clip.startMs)
            val moving = GroupOps.descendantsOf(_tracks.value, clipId).map { it.id }.toSet() + clipId
            val lowest = _tracks.value.flatMap { it.clips }.filter { it.id in moving }.minOf { it.startMs }
            val dd = d.coerceAtLeast(-lowest)
            _tracks.value = _tracks.value.map { track ->
                track.copy(clips = track.clips.map { c ->
                    if (c.id in moving) c.copy(startMs = (c.startMs + dd).coerceAtLeast(0L)) else c
                })
            }
            recomputeTotal()
            return
        }
        if (clip?.chainId != null) {
            // A layer inside a chain slides along the line but never over its neighbours.
            val sib = LaneLayout.childrenOf(_tracks.value, clip.chainId).filter { it.id != clipId }
            val prevEnd = sib.filter { it.startMs < clip.startMs }.maxOfOrNull { it.endMs } ?: 0L
            val nextStart = sib.filter { it.startMs > clip.startMs }.minOfOrNull { it.startMs } ?: Long.MAX_VALUE / 4
            val newStart = (clip.startMs + deltaMs).coerceIn(prevEnd, (nextStart - clip.durationMs).coerceAtLeast(prevEnd))
            _tracks.value = LaneLayout.setClipStart(_tracks.value, clipId, newStart)
            recomputeTotal()
            return
        }
        _tracks.value = LaneLayout.shiftClipTime(_tracks.value, clipId, deltaMs)
        recomputeTotal()
    }

    /**
     * Trims a layer from one edge (the two arrow handles of a selected layer).
     * Left edge: start moves, and for media the in-point (trimStartMs) moves with it.
     * Right edge: only the length changes. Media can't be stretched past its source length.
     * Call [beginAdjust] once before a drag session so undo is a single step.
     */
    fun trimClipEdge(clipId: String, isLeft: Boolean, deltaMs: Long) {
        if (deltaMs == 0L) return
        val minLen = 100L
        val all = _tracks.value.flatMap { it.clips }
        val me = all.firstOrNull { it.id == clipId }
        if (me?.type == LayerType.CHAIN) return  // a chain always covers its layers: trim the layers instead
        // A group can be trimmed: from then on it keeps its own length and the layers inside are cut at its ends.
        // Neighbours inside the same chain: the layer can not grow over them.
        val sib = if (me?.chainId != null) all.filter { it.chainId == me.chainId && it.id != clipId } else emptyList()
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { c0 ->
                if (c0.id != clipId) return@map c0
                val c = if (c0.type == LayerType.GROUP) c0.copy(groupFreeLength = true) else c0
                val hasSource = c.uri != null && !c.isImage
                val spd = c.speed.coerceAtLeast(0.01f)
                if (isLeft) {
                    var d = deltaMs.coerceAtLeast(-c.startMs)
                    sib.filter { it.endMs <= c.startMs }.maxOfOrNull { it.endMs }?.let { d = d.coerceAtLeast(it - c.startMs) }
                    if (hasSource) d = d.coerceAtLeast(-(c.trimStartMs / spd).toLong())
                    d = d.coerceAtMost(c.durationMs - minLen)
                    c.copy(
                        startMs = c.startMs + d,
                        durationMs = c.durationMs - d,
                        trimStartMs = if (hasSource) (c.trimStartMs + d * spd).toLong().coerceAtLeast(0L) else c.trimStartMs
                    ).withTrimmedStartKeyframes(d)
                } else {
                    var d = deltaMs.coerceAtLeast(-(c.durationMs - minLen))
                    sib.filter { it.startMs >= c.endMs }.minOfOrNull { it.startMs }?.let { d = d.coerceAtMost(it - c.endMs) }
                    if (hasSource && c.sourceDurationMs > 0L) {
                        val maxLen = ((c.sourceDurationMs - c.trimStartMs) / spd).toLong()
                        d = d.coerceAtMost((maxLen - c.durationMs).coerceAtLeast(0L))
                    }
                    c.copy(durationMs = c.durationMs + d).withEndMoved(d)
                }
            })
        }
        recomputeTotal()
    }

    /**
     * Sets the length of a layer that has no media of its own (text, shape, sticker). Used by motion
     * tracking: a new layer is only 4 s long, but it has to stay on screen as long as the object is
     * followed. One layer per lane, so a longer layer can never run into another one.
     */
    fun setOverlayDurationMs(clipId: String, durationMs: Long) {
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { c ->
                if (c.id == clipId && (c.uri == null || c.isImage)) {
                    val newDur = durationMs.coerceAtLeast(100L)
                    c.copy(durationMs = newDur).withEndMoved(newDur - c.durationMs)
                } else c
            })
        }
        recomputeTotal()
    }

    /** Sets absolute start time of a layer on the timeline. */
    fun setClipStartMs(clipId: String, startMs: Long) {
        val clip = _tracks.value.flatMap { it.clips }.firstOrNull { it.id == clipId }
        if (clip != null && (clip.type == LayerType.CHAIN || clip.type == LayerType.GROUP || clip.chainId != null)) {
            moveClipInTime(clipId, startMs - clip.startMs)  // chain rules (neighbours, whole chain)
            return
        }
        _tracks.value = LaneLayout.setClipStart(_tracks.value, clipId, startMs)
        recomputeTotal()
    }

    /**
     * Reorders lanes (vertical drag). [fromIndex]/[toIndex] are positions in [tracks]
     * (0 = top layer, drawn above everything).
     */
    fun reorderLane(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        pushHistory()
        _tracks.value = LaneLayout.reorder(_tracks.value, fromIndex, toIndex)
    }

    /**
     * Moves the lane that holds [clipId] by [deltaLanes] steps
     * (negative = toward top / higher in the stack, positive = toward bottom).
     */
    fun moveLaneByClip(clipId: String, deltaLanes: Int) {
        if (deltaLanes == 0) return
        val tracks = _tracks.value
        val from = tracks.indexOfFirst { lane -> lane.clips.any { it.id == clipId } }
        if (from < 0) return
        val to = (from + deltaLanes).coerceIn(0, tracks.lastIndex)
        if (from == to) return
        pushHistory()
        _tracks.value = LaneLayout.reorder(tracks, from, to)
    }

    fun addClip(
        type: LayerType,
        name: String,
        durationMs: Long = 5000L,
        text: String? = null,
        textColor: Long = 0xFFFFFFFF,
        shapePattern: String? = null,
        uri: String? = null,
        shapeOffsetX: Float = 0f,
        shapeOffsetY: Float = 0f,
        shapeScaleX: Float = 1.0f,
        shapeScaleY: Float = 1.0f,
        strokeWidth: Float = 0f,
        strokeColor: Long = 0xFFFFFFFF,
        /** Corner radius in dp (-1 = not set, the renderers use their built-in corner). */
        shapeCornerRadius: Float = -1f,
        /** Stroke trim window as 0..1 fractions of the outline length (Alight `drawing.progress`). */
        strokeTrimStart: Float = 0f,
        strokeTrimEnd: Float = 1f
    ) {
        pushHistory()
        val playhead = _currentPlayheadMs.value

        // The shape editor's sliders clamp already; this is the safety net for direct callers.
        val (trimStart, trimEnd) = ShapeEditValues.coerceTrim(strokeTrimStart, strokeTrimEnd)
        val cornerRadius = if (shapeCornerRadius >= 0f) {
            ShapeEditValues.coerceCornerRadiusDp(shapeCornerRadius)
        } else -1f

        val color = when (type) {
            LayerType.VIDEO -> 0xFF2A4365
            LayerType.AUDIO -> 0xFF1B4332
            LayerType.SHAPE -> 0xFFFF3377
            LayerType.OVERLAY -> 0xFF4A154B
            LayerType.TEXT -> 0xFF3992CE
            LayerType.STICKER -> 0xFF4A2511
            LayerType.NULL -> 0xFFE8A33D
            LayerType.CAMERA -> 0xFF8E6BF2
            LayerType.CHAIN -> 0xFF9AA0A8
            LayerType.GROUP -> 0xFF5BB85D
            LayerType.CONTROLLER -> 0xFF00B8A9
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
            shapeCornerRadius = cornerRadius,
            strokeTrimStart = trimStart,
            strokeTrimEnd = trimEnd,
            uri = uri,
            transformX = shapeOffsetX,
            transformY = shapeOffsetY,
            scalePercent = (shapeScaleX * 100f).coerceAtLeast(1f)
        )

        // Alight Motion style: every layer gets its own exclusive lane (1 layer = 1 line).
        _tracks.value = placeNewLayer(_tracks.value, newClip)
        setSelected(newClip.id)
        recomputeTotal()
    }

    fun updateClipTransform(
        clipId: String,
        offsetX: Float,
        offsetY: Float,
        scaleX: Float = 1f,
        scaleY: Float = 1f
    ) {
        val before = clipNow(clipId)
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id == clipId) {
                    // The canvas draws a tracked layer at (own position + tracking offset); take the
                    // tracking offset out again so only the user's own move is stored.
                    val (fx, fy) = clip.trackOffsetAt(_currentPlayheadMs.value)
                    val ox = offsetX - fx
                    val oy = offsetY - fy
                    // Built on the layer as it looks at the playhead, so a moved layer with position
                    // keyframes gets a keyframe at the playhead instead of silently changing the stored value.
                    commitKeyframed(clip, clip.at(_currentPlayheadMs.value).copy(
                        shapeOffsetX = ox,
                        shapeOffsetY = oy,
                        shapeScaleX = scaleX,
                        shapeScaleY = scaleY,
                        transformX = ox,
                        transformY = oy,
                        scalePercent = if (scaleX != 1f) (scaleX * 100f).coerceAtLeast(1f) else clip.scalePercent
                    ), vsResolved = true)
                } else clip
            })
        }
        if (before != null) applyControllerFanOut(before)
    }

    /**
     * Moves several layers by per-layer deltas (STORED units) as ONE undo step — the write
     * path of canvas align / distribute (build 188). Each layer goes through [moveLayerBy],
     * the same core as a canvas drag: the move is built on the layer as it looks at the
     * playhead, so a position-keyframed layer gets a keyframe at the playhead instead of a
     * silently changed stored value, and moving a Controller fans the delta out onto its
     * connected layers exactly like a manual Controller move (the settled build-155 rule —
     * a connected layer that is itself in the selection is moved by its own delta as well;
     * if its Controller is also moved, the fan-out applies on top, as with two manual moves).
     */
    fun moveClipsBy(deltas: Map<String, Pair<Float, Float>>) {
        val real = deltas.filter { (id, d) -> (d.first != 0f || d.second != 0f) && clipNow(id) != null }
        if (real.isEmpty()) return
        pushHistory()
        for ((id, d) in real) moveLayerBy(id, d.first, d.second)
    }

    /**
     * One layer moved by a stored-unit delta; the caller owns the undo step. A delta needs
     * no tracking-offset correction (the offset is the same before and after the move, so it
     * cancels — [updateClipTransform] subtracts it only because the canvas hands it an
     * absolute drawn position). Scale / rotation / pivot are copied from the resolved layer
     * untouched: an align never reshapes a layer.
     */
    private fun moveLayerBy(clipId: String, dx: Float, dy: Float) {
        val before = clipNow(clipId) ?: return
        val t = _currentPlayheadMs.value
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id == clipId) {
                    val resolved = clip.at(t)
                    commitKeyframed(clip, resolved.copy(
                        transformX = resolved.transformX + dx,
                        transformY = resolved.transformY + dy,
                        shapeOffsetX = resolved.transformX + dx,
                        shapeOffsetY = resolved.transformY + dy
                    ), vsResolved = true)
                } else clip
            })
        }
        applyControllerFanOut(before)
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
        warp: Float = 0f,
        transformZ: Float = 0f
    ) {
        val before = clipNow(clipId)
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id == clipId) {
                    // The Transform panel sends everything it shows (= the animated values at the playhead).
                    commitKeyframed(clip, clip.copy(
                        transformX = transformX,
                        transformY = transformY,
                        scalePercent = scalePercent,
                        rotationDeg = rotationDeg,
                        pivotX = pivotX,
                        pivotY = pivotY,
                        rotation3dX = rot3dX,
                        rotation3dY = rot3dY,
                        warpDistort = warp,
                        transformZ = transformZ,
                        shapeOffsetX = transformX,
                        shapeOffsetY = transformY,
                        shapeScaleX = scalePercent / 100f,
                        shapeScaleY = scalePercent / 100f
                    ), vsResolved = true)
                } else clip
            })
        }
        if (before != null) applyControllerFanOut(before)
    }

    fun updateClipCrop(
        clipId: String,
        cropWidthPercent: Float,
        cropHeightPercent: Float,
        cropHorizontalPercent: Float = 50f,
        cropVerticalPercent: Float = 50f,
        cropPreset: String = "Free"
    ) {
        // Live update while the Crop screen is open; the caller invokes beginAdjust() once on open.
        // Built on the layer as it looks at the playhead, so a cropped property that has keyframes
        // turns the edit into a keyframe (the Transform rule) instead of changing the stored value.
        val t = _currentPlayheadMs.value
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id == clipId) {
                    commitKeyframed(clip, clip.at(t).copy(
                        cropWidthPercent = cropWidthPercent,
                        cropHeightPercent = cropHeightPercent,
                        cropHorizontalPercent = cropHorizontalPercent,
                        cropVerticalPercent = cropVerticalPercent,
                        cropPreset = cropPreset
                    ), vsResolved = true)
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


    // ---- Audio layers -----------------------------------------------------

    private val _waveforms = MutableStateFlow<Map<String, FloatArray>>(emptyMap())
    /** Waveform peaks per audio file uri (empty array = could not be decoded). */
    val waveforms: StateFlow<Map<String, FloatArray>> = _waveforms.asStateFlow()
    private val waveformRequested = mutableSetOf<String>()

    /** Decodes the waveform of an audio file once, in the background. */
    fun requestWaveform(context: Context, uri: String, durationMs: Long) {
        if (!waveformRequested.add(uri)) return
        viewModelScope.launch {
            val peaks = AudioWaveform.compute(context, uri, durationMs) ?: FloatArray(0)
            _waveforms.value = _waveforms.value + (uri to peaks)
        }
    }

    /** End of the last picture/video clip: audio is kept inside the video length. */
    private fun videoEndMs(): Long =
        _tracks.value.flatMap { it.clips }
            .filter { it.type == LayerType.VIDEO && it.uri != null }
            .maxOfOrNull { it.endMs } ?: 0L

    /**
     * Adds picked audio files as audio layers, one after the other, starting at the playhead.
     * When there is video, a layer never runs past the end of the video.
     */
    fun importAudio(items: List<ImportedMedia>) {
        if (items.isEmpty()) return
        pushHistory()
        val videoEnd = videoEndMs()
        var cursor = _currentPlayheadMs.value
        if (videoEnd > 0L && cursor > videoEnd - 1000L) cursor = 0L
        var updated = _tracks.value
        var lastId: String? = null
        val into = chainTargetId()
        for (m in items) {
            var duration = m.durationMs
            if (videoEnd > 0L) duration = minOf(duration, videoEnd - cursor)
            if (duration < 250L) continue
            val clip = TimelineClip(
                id = UUID.randomUUID().toString(),
                name = m.name,
                type = LayerType.AUDIO,
                startMs = cursor,
                durationMs = duration,
                color = 0xFF1B4332,
                uri = m.uri,
                mimeType = m.mimeType,
                sourceDurationMs = m.durationMs
            )
            updated = placeNewLayer(updated, clip, into)
            lastId = clip.id
            cursor += duration
        }
        if (lastId == null) {
            undoStack.removeAt(undoStack.lastIndex)
            _canUndo.value = undoStack.isNotEmpty()
            return
        }
        _tracks.value = updated
        setSelected(lastId)
        recomputeTotal()
    }

    /** One undo step for a whole slider session (Volume / Fade / Speed sheets). */
    fun beginAdjust() = pushHistory()

    fun setClipVolume(clipId: String, volume: Float) =
        updateClip(clipId) { it.copy(volume = volume.coerceIn(0f, MAX_CLIP_VOLUME)) }

    /** Per-layer mute (video and audio layers): the layer's sound is left out of preview and export. */
    fun setClipMuted(clipId: String, muted: Boolean) =
        updateClip(clipId) { it.copy(isMuted = muted) }

    fun setClipFades(clipId: String, fadeInMs: Long, fadeOutMs: Long) =
        updateClip(clipId) {
            val maxFade = it.durationMs
            it.copy(
                fadeInMs = fadeInMs.coerceIn(0L, maxFade),
                fadeOutMs = fadeOutMs.coerceIn(0L, maxFade)
            )
        }

    fun setAudioSpeed(clipId: String, speed: Float) = setClipSpeed(clipId, speed)

    /**
     * Smooth slow-mo is ready: the layer's picture (speed already inside it, new frames made between the real ones) is the
     * new file [uri]. The layer keeps its place and length, plays at speed 1 and has no speed ramp any more. The caller has
     * called beginAdjust() (the undo step).
     */
    fun applyBakedSlowMo(clipId: String, uri: String, durationMs: Long) {
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id == clipId && clip.type == LayerType.VIDEO) {
                    clip.copy(
                        uri = uri,
                        mimeType = "video/mp4",
                        sourceDurationMs = durationMs,
                        trimStartMs = 0L,
                        speed = 1f,
                        keyframes = clip.keyframes - AnimProp.SPEED
                    )
                } else clip
            })
        }
        recomputeTotal()
    }

    /**
     * Audio Reverse is ready: the layer now plays the reversed copy [uri] (a WAV file) from its start. Place, length and speed
     * stay as they were. The caller has called beginAdjust() (the undo step).
     */
    fun applyBakedAudioReverse(clipId: String, uri: String, durationMs: Long) {
        _tracks.value = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id == clipId && clip.type == LayerType.AUDIO) {
                    clip.copy(
                        uri = uri,
                        mimeType = "audio/wav",
                        sourceDurationMs = durationMs,
                        trimStartMs = 0L
                    )
                } else clip
            })
        }
        recomputeTotal()
    }

    /** Changes the speed of an audio or video layer; the layer gets shorter/longer on the timeline. */
    fun setClipSpeed(clipId: String, speed: Float) {
        val s = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        val mapped = _tracks.value.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id == clipId && clip.type == LayerType.STICKER) {
                    // Stickers: speed = how fast the animation plays; the layer keeps its length.
                    clip.copy(speed = s)
                } else if (clip.id == clipId) {
                    val sourceSpan = clip.sourceOffsetMs(clip.durationMs).toDouble()
                    val newDuration = (sourceSpan / s).toLong().coerceAtLeast(250L)
                    clip.copy(
                        speed = s,
                        durationMs = newDuration,
                        fadeInMs = clip.fadeInMs.coerceAtMost(newDuration),
                        fadeOutMs = clip.fadeOutMs.coerceAtMost(newDuration)
                    ).withScaledKeyframes(newDuration.toFloat() / clip.durationMs.coerceAtLeast(1L))
                } else clip
            })
        }
        _tracks.value = LaneLayout.normalize(mapped)
        recomputeTotal()
    }

    // ---- Real media -------------------------------------------------------

    fun hasMainMedia(): Boolean =
        _tracks.value.any { track ->
            track.clips.any { it.type == LayerType.VIDEO && it.uri != null }
        }

    /**
     * Imports videos/photos as normal layers (like text/shape).
     * Each file becomes its own lane at the playhead — no special "main track" behaviour.
     */
    fun importMedia(items: List<ImportedMedia>) {
        if (items.isEmpty()) return
        pushHistory()
        val playhead = _currentPlayheadMs.value
        var updated = _tracks.value
        var lastId: String? = null
        val into = chainTargetId()
        for (m in items) {
            val clip = TimelineClip(
                id = UUID.randomUUID().toString(),
                name = m.name,
                type = LayerType.VIDEO,
                startMs = playhead,
                durationMs = m.durationMs,
                color = 0xFF2A4365,
                uri = m.uri,
                mimeType = m.mimeType,
                isImage = m.isImage,
                sourceDurationMs = m.durationMs,
                sourceAspect = m.aspect
            )
            updated = placeNewLayer(updated, clip, into)
            lastId = clip.id
        }
        _tracks.value = updated
        setSelected(lastId)
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

    // ---- Project save / load (see ProjectStore) ----
    private val _projectId = MutableStateFlow<String?>(null)
    /** Id of the open project (null until the screen has opened / created one). Survives rotation, so it is not reloaded. */
    val projectId: StateFlow<String?> = _projectId.asStateFlow()

    /** What is saved to disk: layers, canvas, export settings, playhead. */
    fun snapshot(): ProjectSnapshot = ProjectSnapshot(
        tracks = _tracks.value,
        aspectRatio = _selectedAspectRatio.value,
        canvasColor = _canvasColor.value,
        exportSettings = _exportSettings.value,
        previewResolution = _selectedResolution.value,
        isMasterMuted = _isMasterMuted.value,
        playheadMs = _currentPlayheadMs.value,
        beatMarksMs = _beatMarks.value,
        markers = _markers.value
    )

    /** Replaces everything with a saved project. Undo history and selection start empty. */
    fun loadProject(s: ProjectSnapshot, id: String) {
        pause()
        undoStack.clear()
        redoStack.clear()
        _canUndo.value = false
        _canRedo.value = false
        _selectedClipId.value = null
        _multiSelectIds.value = emptySet()
        _selectedAspectRatio.value = s.aspectRatio
        _canvasColor.value = s.canvasColor
        _exportSettings.value = s.exportSettings
        _selectedResolution.value = s.previewResolution
        _isMasterMuted.value = s.isMasterMuted
        _beatMarks.value = s.beatMarksMs.filter { it >= 0L }.distinct().sorted()
        _markers.value = ProjectMarkers.normalize(s.markers)
        _tracks.value = s.tracks
        _currentPlayheadMs.value = 0L
        recomputeTotal()
        _currentPlayheadMs.value = s.playheadMs.coerceIn(0L, _totalDurationMs.value)
        _projectId.value = id
    }

    private fun recomputeTotal() {
        val fitted = GroupOps.fitGroups(LaneLayout.fitChains(_tracks.value))
        if (fitted !== _tracks.value) _tracks.value = fitted  // chains and groups always cover their layers
        // The group being edited is gone (deleted / ungrouped / undone): back to the top level.
        _editingGroupId.value?.let { if (GroupOps.validScope(_tracks.value, it) == null) _editingGroupId.value = null }
        val maxEnd = _tracks.value.flatMap { it.clips }.maxOfOrNull { it.endMs }
        _totalDurationMs.value = maxEnd ?: 0L
        _currentPlayheadMs.value = _currentPlayheadMs.value.coerceAtMost(_totalDurationMs.value)
        val multi = _multiSelectIds.value
        if (multi.isNotEmpty()) {
            val alive = _tracks.value.flatMap { it.clips }.map { it.id }.toSet()
            val kept = multi.filter { it in alive }.toSet()
            if (kept != multi) _multiSelectIds.value = kept
        }
    }
}

/** A beat mark closer than this to the playhead counts as "the same spot" (the plus button then removes it). */
const val BEAT_MARK_TOLERANCE_MS = 100L

/** A named marker closer than this to the playhead counts as "at the playhead" (the marker button edits it instead of adding another). */
const val MARKER_TOLERANCE_MS = 150L
