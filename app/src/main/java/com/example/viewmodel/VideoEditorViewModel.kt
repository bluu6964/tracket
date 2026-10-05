package com.example.viewmodel

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.example.model.AspectRatio
import com.example.model.ExportSettings
import com.example.model.LayerType
import com.example.model.PreviewResolution
import com.example.model.TimelineClip
import com.example.model.TimelineTrack
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class VideoEditorViewModel : ViewModel() {
    private val _currentPlayheadMs = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val currentPlayheadMs = _currentPlayheadMs.asStateFlow()
    private val _totalDurationMs = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val totalDurationMs = _totalDurationMs.asStateFlow()
    private val _isPlaying = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()
    private val _selectedAspectRatio = kotlinx.coroutines.flow.MutableStateFlow(AspectRatio.RATIO_16_9)
    val selectedAspectRatio = _selectedAspectRatio.asStateFlow()
    private val _selectedResolution = kotlinx.coroutines.flow.MutableStateFlow(PreviewResolution.FULL)
    val selectedResolution = _selectedResolution.asStateFlow()
    private val _selectedClipId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val selectedClipId = _selectedClipId.asStateFlow()
    private val _isMasterMuted = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isMasterMuted = _isMasterMuted.asStateFlow()
    private val _tracks = kotlinx.coroutines.flow.MutableStateFlow<List<TimelineTrack>>(emptyList())
    val tracks = _tracks.asStateFlow()
    private val undoStack = mutableListOf<List<TimelineTrack>>()
    private val redoStack = mutableListOf<List<TimelineTrack>>()
    private val _canUndo = kotlinx.coroutines.flow.MutableStateFlow(false)
    val canUndo = _canUndo.asStateFlow()
    private val _canRedo = kotlinx.coroutines.flow.MutableStateFlow(false)
    val canRedo = _canRedo.asStateFlow()
    private val _exportSettings = kotlinx.coroutines.flow.MutableStateFlow(ExportSettings())
    val exportSettings = _exportSettings.asStateFlow()
    private val _isExporting = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isExporting = _isExporting.asStateFlow()
    private val _exportProgress = kotlinx.coroutines.flow.MutableStateFlow(0f)
    val exportProgress = _exportProgress.asStateFlow()
    private val _lastExportUri = kotlinx.coroutines.flow.MutableStateFlow<android.net.Uri?>(null)
    val lastExportUri = _lastExportUri.asStateFlow()

    private var playbackJob: Job? = null
    private var transformer: Transformer? = null

    init { undoStack.add(emptyList()) }

    fun togglePlayPause() { if (_isPlaying.value) pause() else play() }
    fun play() {
        if (_tracks.value.none { it.type == LayerType.VIDEO && it.clips.any { c -> c.sourceUri != null } }) return
        _isPlaying.value = true
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            val startWall = System.currentTimeMillis()
            val start = _currentPlayheadMs.value
            while (isActive && _isPlaying.value) {
                delay(16)
                val target = start + (System.currentTimeMillis() - startWall)
                if (target >= _totalDurationMs.value) { _currentPlayheadMs.value = _totalDurationMs.value; pause(); break }
                _currentPlayheadMs.value = target
            }
        }
    }
    fun pause() { _isPlaying.value = false; playbackJob?.cancel(); playbackJob = null }
    fun seekTo(timeMs: Long) { _currentPlayheadMs.value = timeMs.coerceIn(0L, _totalDurationMs.value) }

    fun jumpPrevious() {
        val b = _tracks.value.flatMap { it.clips.flatMap { c -> listOf(c.startMs, c.endMs) } }.distinct().sorted()
        seekTo(b.lastOrNull { it < _currentPlayheadMs.value - 300 } ?: 0)
    }
    fun jumpNext() {
        val b = _tracks.value.flatMap { it.clips.flatMap { c -> listOf(c.startMs, c.endMs) } }.distinct().sorted()
        seekTo(b.firstOrNull { it > _currentPlayheadMs.value + 300 } ?: _totalDurationMs.value)
    }
    fun selectClip(id: String?) { _selectedClipId.value = id }
    fun setAspectRatio(v: AspectRatio) { _selectedAspectRatio.value = v }
    fun setResolution(v: PreviewResolution) { _selectedResolution.value = v }
    fun toggleMasterAudio() { _isMasterMuted.value = !_isMasterMuted.value }
    fun toggleTrackMute(index: Int) { mutateTracks { list -> list.toMutableList().also { it[index] = it[index].copy(isMuted = !it[index].isMuted) } } }

    private fun mutateTracks(transform: (List<TimelineTrack>) -> List<TimelineTrack>) {
        undoStack.add(_tracks.value); redoStack.clear(); _tracks.value = transform(_tracks.value)
        _canUndo.value = undoStack.size > 1; _canRedo.value = false
    }
    fun undo() { if (undoStack.size > 1) { redoStack.add(_tracks.value); _tracks.value = undoStack.removeAt(undoStack.lastIndex); _canUndo.value = undoStack.size > 1; _canRedo.value = true } }
    fun redo() { if (redoStack.isNotEmpty()) { undoStack.add(_tracks.value); _tracks.value = redoStack.removeAt(redoStack.lastIndex); _canUndo.value = undoStack.size > 1; _canRedo.value = redoStack.isNotEmpty() } }

    fun addImportedVideo(uri: android.net.Uri, durationMs: Long, name: String) {
        val start = _currentPlayheadMs.value
        val clip = TimelineClip(UUID.randomUUID().toString(), 0, name, LayerType.VIDEO, start, durationMs.coerceAtLeast(1), 0xFF233549, sourceUri = uri.toString())
        val videoIndex = _tracks.value.indexOfFirst { it.type == LayerType.VIDEO && it.trackIndex == 0 }
        val tracks = _tracks.value.toMutableList()
        val track = if (videoIndex >= 0) tracks[videoIndex] else TimelineTrack("video-main", "Main Video", LayerType.VIDEO)
        val updated = track.copy(clips = track.clips + clip)
        if (videoIndex >= 0) tracks[videoIndex] = updated else tracks.add(0, updated)
        undoStack.add(_tracks.value); redoStack.clear(); _tracks.value = tracks
        _selectedClipId.value = clip.id
        _totalDurationMs.value = maxOf(_totalDurationMs.value, clip.endMs)
        _canUndo.value = undoStack.size > 1
    }

    fun splitSelectedClip() {
        val id = _selectedClipId.value ?: return; val at = _currentPlayheadMs.value
        val old = _tracks.value
        val new = old.map { track ->
            val i = track.clips.indexOfFirst { it.id == id }
            if (i < 0) track else {
                val c = track.clips[i]
                if (at <= c.startMs + 1 || at >= c.endMs - 1) track else {
                    val firstDur = at - c.startMs
                    val a = c.copy(durationMs = firstDur)
                    val b = c.copy(id = UUID.randomUUID().toString(), name = "${c.name} (Split)", startMs = at, sourceStartMs = c.sourceStartMs + firstDur, durationMs = c.durationMs - firstDur)
                    track.copy(clips = track.clips.toMutableList().also { it[i] = a; it.add(i + 1, b) })
                }
            }
        }
        undoStack.add(old); redoStack.clear(); _tracks.value = new; _canUndo.value = true
    }
    fun deleteSelectedClip() { val id = _selectedClipId.value ?: return; mutateTracks { ts -> ts.map { it.copy(clips = it.clips.filter { c -> c.id != id }) } }; _selectedClipId.value = null; recomputeDuration() }
    fun duplicateSelectedClip() { val id = _selectedClipId.value ?: return; mutateTracks { ts -> ts.map { t -> t.clips.find { it.id == id }?.let { c -> t.copy(clips = t.clips + c.copy(id=UUID.randomUUID().toString(), name="${c.name} (Copy)", startMs=c.endMs+200)) } ?: t } }; recomputeDuration() }
    fun flipHorizontalSelectedClip() = updateSelected { it.copy(isFlippedH=!it.isFlippedH) }
    fun flipVerticalSelectedClip() = updateSelected { it.copy(isFlippedV=!it.isFlippedV) }
    fun replaceSelectedClipMedia(drawableResId: Int, newName: String? = null) { /* Legacy UI action: real imports use addImportedVideo(). */ }
    fun setClipBlendMode(mode: String) = updateSelected { it.copy(blendMode=mode) }
    fun setClipEffect(effect: String) = updateSelected { it.copy(effectName=effect) }
    fun setClipAnimation(anim: String) = updateSelected { it.copy(animationName=anim) }
    fun setClipColor(color: Long) = updateSelected { it.copy(color=color) }
    private fun updateSelected(f: (TimelineClip)->TimelineClip) { val id=_selectedClipId.value ?: return; mutateTracks { ts -> ts.map { t -> t.copy(clips=t.clips.map { if(it.id==id) f(it) else it }) } } }

    fun addClip(type: LayerType, name: String, durationMs: Long = 5000L, text: String? = null, textColor: Long = 0xFFFFFFFF, shapePattern: String? = null) {
        val trackIndex = when(type) { LayerType.VIDEO->0; LayerType.AUDIO->1; LayerType.SHAPE->2; else->3 }
        val clip=TimelineClip(UUID.randomUUID().toString(), trackIndex, name, type, _currentPlayheadMs.value, durationMs, 0xFF2A4365, text=text, textColor=textColor, shapePattern=shapePattern)
        val old=_tracks.value; val ts=old.toMutableList(); val idx=ts.indexOfFirst { it.trackIndex==trackIndex }
        val track=if(idx>=0) ts[idx] else TimelineTrack("track-$trackIndex","${type.displayName} Track",type)
        val u=track.copy(clips=track.clips+clip); if(idx>=0) ts[idx]=u else ts.add(u)
        undoStack.add(old); redoStack.clear(); _tracks.value=ts; _selectedClipId.value=clip.id; recomputeDuration()
    }

    private fun recomputeDuration() { _totalDurationMs.value = _tracks.value.flatMap { it.clips }.maxOfOrNull { it.endMs } ?: 0L }

    fun startExport(context: Context, onComplete: (android.net.Uri) -> Unit, onError: (String) -> Unit) {
        if (_isExporting.value) return
        val videos = _tracks.value.flatMap { it.clips }.filter { it.type == LayerType.VIDEO && it.sourceUri != null }.sortedBy { it.startMs }
        if (videos.isEmpty()) { onError("Import at least one video first"); return }
        val items = videos.map { clip ->
            val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(clip.sourceStartMs).setEndPositionMs(clip.sourceStartMs + clip.durationMs).build()
            EditedMediaItem.Builder(MediaItem.Builder().setUri(clip.sourceUri!!).setClippingConfiguration(clipping).build()).build()
        }
        val sequence = EditedMediaItemSequence.withAudioAndVideoFrom(items)
        val composition = Composition.Builder(sequence).build()
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "exports").apply { mkdirs() }
        val file = File(dir, "tracket_${System.currentTimeMillis()}.mp4")
        _isExporting.value=true; _exportProgress.value=0f
        val progressHolder=ProgressHolder()
        val t=Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).addListener(object: Transformer.Listener {
            override fun onCompleted(composition: Composition, result: ExportResult) {
                viewModelScope.launch { val uri=publishExport(context,file); _lastExportUri.value=uri; _isExporting.value=false; _exportProgress.value=1f; onComplete(uri) }
            }
            override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                _isExporting.value=false; onError(exception.message ?: "Video export failed")
            }
        }).build()
        transformer=t
        t.start(composition,file.absolutePath)
        viewModelScope.launch {
            while (isActive && _isExporting.value) {
                when (t.getProgress(progressHolder)) { Transformer.PROGRESS_STATE_AVAILABLE -> _exportProgress.value=progressHolder.progress/100f; Transformer.PROGRESS_STATE_NOT_STARTED -> Unit }
                delay(250)
            }
        }
    }

    private fun publishExport(context: Context, file: File): android.net.Uri {
        if (android.os.Build.VERSION.SDK_INT < 29) return android.net.Uri.fromFile(file)
        val values=ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME,file.name); put(MediaStore.Video.Media.MIME_TYPE,"video/mp4"); put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/Tracket"); put(MediaStore.Video.Media.IS_PENDING,1) }
        val resolver=context.contentResolver; val uri=resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values) ?: return android.net.Uri.fromFile(file)
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear(); values.put(MediaStore.Video.Media.IS_PENDING,0); resolver.update(uri,values,null,null); file.delete(); return uri
    }

    override fun onCleared() { transformer?.cancel(); playbackJob?.cancel(); super.onCleared() }
}
