package com.example.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.UnstableApi
import com.example.engine.EditorPlayer
import com.example.engine.MediaImporter
import com.example.engine.VideoExporter
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.LayerType
import com.example.ui.components.AddAudioSheet
import com.example.ui.components.AddShapeSheet
import com.example.ui.components.AddTextSheet
import com.example.ui.components.AppliedEffectsView
import com.example.ui.components.AspectRatioSheet
import com.example.ui.components.BottomToolbar
import com.example.ui.components.CanvasArea
import com.example.ui.components.ClipEditToolsPanel
import com.example.ui.components.ClipEffectsSheet
import com.example.ui.components.ClipAnimationsSheet
import com.example.ui.components.ClipReplaceSheet
import com.example.ui.components.ClipBlendSheet
import com.example.ui.components.ClipCropSheet
import com.example.ui.components.EditorHeader
import com.example.ui.components.ExportModalDialog
import com.example.ui.components.FullscreenPlayerModal
import com.example.ui.components.PlayControlsSection
import com.example.ui.components.ResolutionSheet
import com.example.ui.components.TimelineLayerPanel
import com.example.ui.components.TimescaleSection
import com.example.ui.theme.EditorBackground
import com.example.viewmodel.VideoEditorViewModel
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
@Composable
fun VideoEditorScreen(
    viewModel: VideoEditorViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val currentPlayheadMs by viewModel.currentPlayheadMs.collectAsState()
    val totalDurationMs by viewModel.totalDurationMs.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val selectedAspectRatio by viewModel.selectedAspectRatio.collectAsState()
    val selectedResolution by viewModel.selectedResolution.collectAsState()
    val selectedClipId by viewModel.selectedClipId.collectAsState()
    val isMasterMuted by viewModel.isMasterMuted.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val isExporting by viewModel.isExporting.collectAsState()
    val exportProgress by viewModel.exportProgress.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val exportSettings by viewModel.exportSettings.collectAsState()

    // v0.1 only supports main-track editing; everything else shows this message.
    val comingSoon: () -> Unit = {
        snackbarHostState.currentSnackbarData?.dismiss()
        scope.launch { snackbarHostState.showSnackbar("Coming in a later version") }
    }

    // ---- Real engine: preview player, exporter, media picker ----
    val editorPlayer = remember { EditorPlayer(context) }
    val exporter = remember { VideoExporter(context) }
    DisposableEffect(Unit) {
        editorPlayer.onEnded = { viewModel.onPlaybackEnded() }
        onDispose {
            exporter.cancel()
            editorPlayer.release()
        }
    }

    // Real media clips across all tracks in timeline order
    val mediaClips = remember(tracks) {
        tracks.filter { it.type == LayerType.VIDEO }
            .flatMap { it.clips }.filter { it.uri != null }.sortedBy { it.startMs }
    }
    LaunchedEffect(mediaClips) {
        editorPlayer.setClips(mediaClips, viewModel.currentPlayheadMs.value)
    }
    LaunchedEffect(isMasterMuted) { editorPlayer.setMuted(isMasterMuted) }

    // Scrubbing / seeking while paused moves the player to the playhead.
    LaunchedEffect(currentPlayheadMs, isPlaying) {
        if (!isPlaying) editorPlayer.seekTo(currentPlayheadMs)
    }
    // Play / pause: ExoPlayer is the clock and feeds the playhead back to the ViewModel when media exists.
    LaunchedEffect(isPlaying, mediaClips) {
        if (mediaClips.isEmpty()) return@LaunchedEffect
        if (isPlaying) {
            val total = editorPlayer.totalMs()
            if (viewModel.currentPlayheadMs.value >= total - 50L) viewModel.seekTo(0L)
            editorPlayer.seekTo(viewModel.currentPlayheadMs.value, force = true)
            editorPlayer.player.play()
            while (isActive) {
                viewModel.updatePlayhead(editorPlayer.timelinePositionMs())
                delay(33L)
            }
        } else {
            editorPlayer.player.pause()
        }
    }

    val mediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val items = MediaImporter.read(context, uris)
                if (items.isEmpty()) {
                    snackbarHostState.showSnackbar("Could not read the selected files")
                } else {
                    viewModel.importMedia(items)
                    snackbarHostState.showSnackbar("Imported ${items.size} item(s)")
                }
            }
        }
    }

    // Dialog & Sheet States
    var showAspectRatioSheet by remember { mutableStateOf(false) }
    var showResolutionSheet by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showFullscreenPlayer by remember { mutableStateOf(false) }
    var showAddAudioSheet by remember { mutableStateOf(false) }
    var showAddTextSheet by remember { mutableStateOf(false) }
    var showAddShapeSheet by remember { mutableStateOf(false) }
    var showEditShapeMode by remember { mutableStateOf(false) }
    var showClipEffectsSheet by remember { mutableStateOf(false) }
    var showClipAnimationsSheet by remember { mutableStateOf(false) }
    var showClipReplaceSheet by remember { mutableStateOf(false) }
    var showClipBlendSheet by remember { mutableStateOf(false) }
    var showClipCropSheet by remember { mutableStateOf(false) }
    var showAppliedEffectsMode by remember { mutableStateOf(false) }
    var showTransformMode by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(EditorBackground)
            .statusBarsPadding()
            .navigationBarsPadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = EditorBackground
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
        ) {
            val totalHeight = maxHeight

            // Proportions matching user prompt exactly:
            // Header: 1/18 of screen
            // Canvas: 9/18 of screen
            // Play, full screen section: 1/18 of screen
            // Timescale: 1/30 of screen
            // Layer panel: 5/18 of screen
            // Bottom toolbar: remaining screen height (~2/18)
            val headerH = (totalHeight * (1f / 18f)).coerceAtLeast(44.dp)
            val canvasH = (totalHeight * (9f / 18f)).coerceAtLeast(260.dp)
            val playControlsH = (totalHeight * (1f / 18f)).coerceAtLeast(44.dp)
            val timescaleH = (totalHeight * (1f / 30f)).coerceAtLeast(24.dp)
            val layerPanelH = (totalHeight * (5f / 18f)).coerceAtLeast(160.dp)
            val toolbarH = (totalHeight - (headerH + canvasH + playControlsH + timescaleH + layerPanelH)).coerceAtLeast(54.dp)

            Column(modifier = Modifier.fillMaxSize()) {
                // 1. Header (1/18 of screen)
                EditorHeader(
                    height = headerH,
                    aspectRatio = selectedAspectRatio,
                    onBackClick = {
                        scope.launch { snackbarHostState.showSnackbar("Project autosaved") }
                    },
                    onAspectRatioClick = { showAspectRatioSheet = true },
                    onExportClick = { showExportDialog = true }
                )

                // 2. Canvas Area (9/18 of screen)
                CanvasArea(
                    height = canvasH,
                    aspectRatio = selectedAspectRatio,
                    currentPlayheadMs = currentPlayheadMs,
                    isPlaying = isPlaying,
                    tracks = tracks,
                    selectedClipId = selectedClipId,
                    onTogglePlayPause = { viewModel.togglePlayPause() },
                    player = editorPlayer.player,
                    hasMainMedia = mediaClips.isNotEmpty(),
                    onUpdateClipTransform = { id, x, y -> viewModel.updateClipTransform(id, x, y) }
                )

                // 3. Play & Full Screen Section (1/18 of screen)
                PlayControlsSection(
                    height = playControlsH,
                    isPlaying = isPlaying,
                    canUndo = canUndo,
                    canRedo = canRedo,
                    onFullScreenClick = { showFullscreenPlayer = true },
                    onResolutionClick = { showResolutionSheet = true },
                    onPreviousClick = { viewModel.jumpPrevious() },
                    onPlayPauseClick = { viewModel.togglePlayPause() },
                    onNextClick = { viewModel.jumpNext() },
                    onUndoClick = { viewModel.undo() },
                    onRedoClick = { viewModel.redo() }
                )

                // 4. Timescale Section (1/30 of screen)
                TimescaleSection(
                    height = timescaleH,
                    currentPlayheadMs = currentPlayheadMs,
                    totalDurationMs = totalDurationMs
                )

                // 5. Layer Panel (5/18 of screen)
                TimelineLayerPanel(
                    height = layerPanelH,
                    tracks = tracks,
                    currentPlayheadMs = currentPlayheadMs,
                    totalDurationMs = totalDurationMs,
                    selectedClipId = selectedClipId,
                    isMasterMuted = isMasterMuted,
                    onSeekTo = { viewModel.seekTo(it) },
                    onClipSelect = { viewModel.selectClip(it) },
                    onSplitClip = {
                        viewModel.splitSelectedClip()
                        scope.launch { snackbarHostState.showSnackbar("Clip split at playhead") }
                    },
                    onDeleteClip = {
                        viewModel.deleteSelectedClip()
                        scope.launch { snackbarHostState.showSnackbar("Clip removed") }
                    },
                    onDuplicateClip = {
                        viewModel.duplicateSelectedClip()
                        scope.launch { snackbarHostState.showSnackbar("Clip duplicated") }
                    },
                    onToggleMasterAudio = {
                        viewModel.toggleMasterAudio()
                        scope.launch {
                            snackbarHostState.showSnackbar(if (!isMasterMuted) "Master Audio Muted" else "Master Audio Unmuted")
                        }
                    },
                    onAddMediaClick = {
                        mediaPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    }
                )

                // 6. Footer: Clip Edit Tools Panel if clip is selected, otherwise Main Bottom Toolbar
                AnimatedContent(
                    targetState = selectedClipId,
                    transitionSpec = {
                        (fadeIn(animationSpec = androidx.compose.animation.core.tween(220)) +
                                scaleIn(initialScale = 0.96f, animationSpec = androidx.compose.animation.core.tween(260))) togetherWith
                        (fadeOut(animationSpec = androidx.compose.animation.core.tween(160)) +
                                scaleOut(targetScale = 0.96f, animationSpec = androidx.compose.animation.core.tween(180)))
                    },
                    label = "toolbarTransition"
                ) { targetClipId ->
                    if (targetClipId != null) {
                        ClipEditToolsPanel(
                            height = toolbarH,
                            onDeselect = { viewModel.selectClip(null) },
                            onSplit = {
                                viewModel.splitSelectedClip()
                                scope.launch { snackbarHostState.showSnackbar("Clip split at playhead") }
                            },
                            onEffects = comingSoon,
                            onAnimations = comingSoon,
                            onCopy = {
                                viewModel.duplicateSelectedClip()
                                scope.launch { snackbarHostState.showSnackbar("Clip duplicated") }
                            },
                            onReplace = comingSoon,
                            onColor = comingSoon,
                            onTransform = { showTransformMode = true },
                            onCrop = comingSoon,
                            onDelete = {
                                viewModel.deleteSelectedClip()
                                scope.launch { snackbarHostState.showSnackbar("Clip deleted") }
                            },
                            onBlend = comingSoon,
                            onFlipHorizontal = {
                                viewModel.flipHorizontalSelectedClip()
                                scope.launch { snackbarHostState.showSnackbar("Flipped horizontally") }
                            },
                            onFlipVertical = {
                                viewModel.flipVerticalSelectedClip()
                                scope.launch { snackbarHostState.showSnackbar("Flipped vertically") }
                            }
                        )
                    } else {
                        BottomToolbar(
                            height = toolbarH,
                            onAudioClick = comingSoon,
                            onTextClick = comingSoon,
                            onShapeClick = { showEditShapeMode = true },
                            onTransformClick = comingSoon,
                            onOverlayClick = comingSoon,
                            onStickersClick = comingSoon
                        )
                    }
                }
            }
        }
    }

    // Modal Sheets & Dialogs
    if (showAspectRatioSheet) {
        AspectRatioSheet(
            currentRatio = selectedAspectRatio,
            onRatioSelected = { viewModel.setAspectRatio(it) },
            onDismiss = { showAspectRatioSheet = false }
        )
    }

    if (showResolutionSheet) {
        ResolutionSheet(
            currentResolution = selectedResolution,
            onResolutionSelected = { viewModel.setResolution(it) },
            onDismiss = { showResolutionSheet = false }
        )
    }

    if (showExportDialog) {
        ExportModalDialog(
            isExporting = isExporting,
            progress = exportProgress,
            onStartExport = {
                viewModel.pause()
                viewModel.setExporting(true)
                exporter.start(
                    tracks = tracks,
                    aspectRatio = selectedAspectRatio,
                    settings = exportSettings,
                    onProgress = { viewModel.setExportProgress(it) },
                    onDone = {
                        viewModel.finishExport()
                        scope.launch { snackbarHostState.showSnackbar("Saved to Movies/Tracket") }
                    },
                    onError = { msg ->
                        viewModel.setExporting(false)
                        showExportDialog = false
                        scope.launch { snackbarHostState.showSnackbar("Export failed: $msg") }
                    }
                )
            },
            onDismiss = { showExportDialog = false }
        )
    }

    if (showAddAudioSheet) {
        AddAudioSheet(
            onAddAudio = { audioName ->
                viewModel.addClip(
                    type = LayerType.AUDIO,
                    name = audioName,
                    durationMs = 15_000L
                )
                scope.launch { snackbarHostState.showSnackbar("Added audio: $audioName") }
            },
            onDismiss = { showAddAudioSheet = false }
        )
    }

    if (showAddTextSheet) {
        AddTextSheet(
            onAddText = { text, color ->
                viewModel.addClip(
                    type = LayerType.TEXT,
                    name = "Text: $text",
                    durationMs = 7000L,
                    text = text,
                    textColor = color
                )
                scope.launch { snackbarHostState.showSnackbar("Added text layer") }
            },
            onDismiss = { showAddTextSheet = false }
        )
    }

    if (showAddShapeSheet) {
        AddShapeSheet(
            onAddShape = { name, pattern ->
                val clipDuration = if (totalDurationMs > 0L && currentPlayheadMs < totalDurationMs) {
                    minOf(4000L, (totalDurationMs - currentPlayheadMs).coerceAtLeast(1000L))
                } else {
                    4000L
                }
                viewModel.addClip(
                    type = LayerType.SHAPE,
                    name = name,
                    durationMs = clipDuration,
                    shapePattern = pattern
                )
                scope.launch { snackbarHostState.showSnackbar("Added shape: $name") }
            },
            onDismiss = { showAddShapeSheet = false }
        )
    }

    if (showFullscreenPlayer) {
        FullscreenPlayerModal(
            aspectRatio = selectedAspectRatio,
            currentPlayheadMs = currentPlayheadMs,
            totalDurationMs = totalDurationMs,
            isPlaying = isPlaying,
            onTogglePlay = { viewModel.togglePlayPause() },
            onDismiss = { showFullscreenPlayer = false }
        )
    }

    if (showEditShapeMode) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showEditShapeMode = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            com.example.ui.components.EditShapeView(
                aspectRatio = selectedAspectRatio,
                currentPlayheadMs = currentPlayheadMs,
                totalDurationMs = totalDurationMs,
                isPlaying = isPlaying,
                player = editorPlayer.player,
                hasMainMedia = mediaClips.isNotEmpty(),
                onTogglePlay = { viewModel.togglePlayPause() },
                onSeekTo = { viewModel.seekTo(it) },
                onDismiss = { showEditShapeMode = false },
                onSaveShape = { shapeType, w, h, color, feather, offX, offY, strokeW, strokeCol ->
                    val clipDuration = if (totalDurationMs > 0L && currentPlayheadMs < totalDurationMs) {
                        minOf(4000L, (totalDurationMs - currentPlayheadMs).coerceAtLeast(1000L))
                    } else {
                        4000L
                    }
                    val a = (color.alpha * 255).toInt().coerceIn(0, 255)
                    val r = (color.red * 255).toInt().coerceIn(0, 255)
                    val g = (color.green * 255).toInt().coerceIn(0, 255)
                    val b = (color.blue * 255).toInt().coerceIn(0, 255)
                    val argb = ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
                    val colorLong = argb.toLong() and 0xFFFFFFFFL

                    val strA = (strokeCol.alpha * 255).toInt().coerceIn(0, 255)
                    val strR = (strokeCol.red * 255).toInt().coerceIn(0, 255)
                    val strG = (strokeCol.green * 255).toInt().coerceIn(0, 255)
                    val strB = (strokeCol.blue * 255).toInt().coerceIn(0, 255)
                    val strokeArgb = ((strA and 0xFF) shl 24) or ((strR and 0xFF) shl 16) or ((strG and 0xFF) shl 8) or (strB and 0xFF)
                    val strokeColorLong = strokeArgb.toLong() and 0xFFFFFFFFL

                    viewModel.addClip(
                        type = LayerType.SHAPE,
                        name = "${shapeType.label} Shape",
                        durationMs = clipDuration,
                        shapePattern = shapeType.name,
                        textColor = colorLong,
                        shapeOffsetX = offX,
                        shapeOffsetY = offY,
                        shapeScaleX = w / 100f,
                        shapeScaleY = h / 100f,
                        strokeWidth = strokeW,
                        strokeColor = strokeColorLong
                    )
                    scope.launch { snackbarHostState.showSnackbar("Added ${shapeType.label} to canvas") }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    if (showClipEffectsSheet) {
        val selectedClip = tracks.flatMap { it.clips }.find { it.id == selectedClipId }
        ClipEffectsSheet(
            currentEffect = selectedClip?.effectName,
            onEffectSelect = { effect ->
                viewModel.setClipEffect(effect)
                scope.launch { snackbarHostState.showSnackbar("Applied $effect effect") }
            },
            onDismiss = { showClipEffectsSheet = false }
        )
    }

    if (showClipAnimationsSheet) {
        val selectedClip = tracks.flatMap { it.clips }.find { it.id == selectedClipId }
        ClipAnimationsSheet(
            currentAnim = selectedClip?.animationName,
            onAnimSelect = { anim ->
                viewModel.setClipAnimation(anim)
                scope.launch { snackbarHostState.showSnackbar("Applied $anim animation") }
            },
            onDismiss = { showClipAnimationsSheet = false }
        )
    }

    if (showClipReplaceSheet) {
        ClipReplaceSheet(
            onMediaSelected = { res, name ->
                viewModel.replaceSelectedClipMedia(res, name)
                scope.launch { snackbarHostState.showSnackbar("Replaced with $name") }
            },
            onDismiss = { showClipReplaceSheet = false }
        )
    }

    if (showClipBlendSheet) {
        val selectedClip = tracks.flatMap { it.clips }.find { it.id == selectedClipId }
        ClipBlendSheet(
            currentBlend = selectedClip?.blendMode ?: "Normal",
            onBlendSelected = { mode ->
                viewModel.setClipBlendMode(mode)
                scope.launch { snackbarHostState.showSnackbar("Blend mode: $mode") }
            },
            onDismiss = { showClipBlendSheet = false }
        )
    }

    if (showClipCropSheet) {
        val selectedClip = tracks.flatMap { it.clips }.find { it.id == selectedClipId }
            ?: tracks.flatMap { it.clips }.firstOrNull()

        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showClipCropSheet = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            com.example.ui.components.CropView(
                clip = selectedClip,
                aspectRatio = selectedAspectRatio,
                currentPlayheadMs = currentPlayheadMs,
                totalDurationMs = totalDurationMs,
                isPlaying = isPlaying,
                player = editorPlayer.player,
                hasMainMedia = mediaClips.isNotEmpty(),
                canUndo = canUndo,
                canRedo = canRedo,
                onTogglePlay = { viewModel.togglePlayPause() },
                onSeekTo = { viewModel.seekTo(it) },
                onUndo = { viewModel.undo() },
                onRedo = { viewModel.redo() },
                onDismiss = { showClipCropSheet = false },
                onApplyCrop = { w, h, horiz, vert, preset ->
                    val targetId = selectedClip?.id ?: selectedClipId
                    if (targetId != null) {
                        viewModel.updateClipCrop(
                            clipId = targetId,
                            cropWidthPercent = w,
                            cropHeightPercent = h,
                            cropHorizontalPercent = horiz,
                            cropVerticalPercent = vert,
                            cropPreset = preset
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    if (showAppliedEffectsMode) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showAppliedEffectsMode = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            AppliedEffectsView(
                aspectRatio = selectedAspectRatio,
                onDismiss = { showAppliedEffectsMode = false },
                onEffectApplied = { effectName ->
                    viewModel.setClipEffect(effectName)
                    scope.launch { snackbarHostState.showSnackbar("Applied $effectName") }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    if (showTransformMode) {
        val selectedClip = tracks.flatMap { it.clips }.find { it.id == selectedClipId }
            ?: tracks.flatMap { it.clips }.firstOrNull()

        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showTransformMode = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            com.example.ui.components.TransformView(
                clip = selectedClip,
                aspectRatio = selectedAspectRatio,
                currentPlayheadMs = currentPlayheadMs,
                totalDurationMs = totalDurationMs,
                isPlaying = isPlaying,
                player = editorPlayer.player,
                hasMainMedia = mediaClips.isNotEmpty(),
                canUndo = canUndo,
                canRedo = canRedo,
                onTogglePlay = { viewModel.togglePlayPause() },
                onSeekTo = { viewModel.seekTo(it) },
                onUndo = { viewModel.undo() },
                onRedo = { viewModel.redo() },
                onDismiss = { showTransformMode = false },
                onApplyTransform = { h, v, sc, rot, px, py, r3x, r3y, warp ->
                    val targetId = selectedClip?.id ?: selectedClipId
                    if (targetId != null) {
                        viewModel.updateClipFullTransform(
                            clipId = targetId,
                            transformX = h,
                            transformY = v,
                            scalePercent = sc,
                            rotationDeg = rot,
                            pivotX = px,
                            pivotY = py,
                            rot3dX = r3x,
                            rot3dY = r3y,
                            warp = warp
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
