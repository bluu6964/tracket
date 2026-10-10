package com.example.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.ui.components.AddShapeSheet
import com.example.ui.components.AspectRatioSheet
import com.example.ui.components.BottomToolbar
import com.example.ui.components.CanvasArea
import com.example.ui.components.ClipEditToolsPanel
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

    // Splitting only works when the playhead sits inside the selected clip (more than
    // 500 ms from both edges). Report the real result instead of always claiming success
    // (see ANALYSIS.md, P0-4).
    val splitSelected: () -> Unit = {
        snackbarHostState.currentSnackbarData?.dismiss()
        scope.launch {
            snackbarHostState.showSnackbar(
                if (viewModel.splitSelectedClip()) "Clip split at playhead"
                else "Move the playhead inside the clip to split"
            )
        }
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
        tracks.flatMap { it.clips }
            .filter { it.type == LayerType.VIDEO && it.uri != null }.sortedBy { it.startMs }
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
    var showAddShapeSheet by remember { mutableStateOf(false) }
    var showTransformMode by remember { mutableStateOf(false) }
    // Transient transform preview is kept out of the project StateFlow so slider movement only
    // invalidates the canvas, not the whole editor/timeline.
    val transformPreviewState = remember { mutableStateOf<TimelineClip?>(null) }

    // System back / gesture back closes Transform first instead of leaving the editor.
    BackHandler(enabled = showTransformMode) { showTransformMode = false }
    LaunchedEffect(tracks, showTransformMode, selectedClipId) {
        val preview = transformPreviewState.value ?: return@LaunchedEffect
        if (preview.id != selectedClipId) {
            transformPreviewState.value = null
            return@LaunchedEffect
        }
        val saved = tracks.asSequence().flatMap { it.clips.asSequence() }
            .firstOrNull { it.id == preview.id }
        if (saved != null &&
            saved.transformX == preview.transformX &&
            saved.transformY == preview.transformY &&
            saved.scalePercent == preview.scalePercent &&
            saved.rotationDeg == preview.rotationDeg &&
            saved.pivotX == preview.pivotX &&
            saved.pivotY == preview.pivotY &&
            saved.rotation3dX == preview.rotation3dX &&
            saved.rotation3dY == preview.rotation3dY &&
            saved.warpDistort == preview.warpDistort
        ) {
            // Keep the preview override until the persisted clip catches up, avoiding a
            // one-frame snap back between gesture end and the StateFlow emission.
            transformPreviewState.value = null
        }
    }

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
                        // Nothing is persisted yet: say so instead of promising an autosave
                        // that does not exist (see ANALYSIS.md, P0-3).
                        scope.launch {
                            snackbarHostState.showSnackbar("Project is not saved yet — use Export to keep your video")
                        }
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
                    transformPreviewState = transformPreviewState,
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
                    onSplitClip = splitSelected,
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
                            onSplit = splitSelected,
                            onEffects = comingSoon,
                            onAnimations = comingSoon,
                            onCopy = {
                                viewModel.duplicateSelectedClip()
                                scope.launch { snackbarHostState.showSnackbar("Clip duplicated") }
                            },
                            onReplace = comingSoon,
                            onColor = comingSoon,
                            onTransform = {
                                // Open Transform on the selected layer: if the playhead is outside it,
                                // jump to the layer's start so the layer is visible straight away.
                                val target = tracks.flatMap { it.clips }.find { it.id == selectedClipId }
                                if (target != null && !target.isActiveAt(currentPlayheadMs)) {
                                    viewModel.seekTo(target.startMs)
                                }
                                showTransformMode = true
                            },
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
                            onShapeClick = { showAddShapeSheet = true },
                            onTransformClick = comingSoon,
                            onOverlayClick = comingSoon,
                            onStickersClick = comingSoon
                        )
                    }
                }
            }

            // Transform controls intentionally overlay only the lower timeline/tools area.
            // Keep the canvas viewport and all visible layers completely unchanged.
            if (showTransformMode) {
                val selectedClip = tracks.flatMap { it.clips }.find { it.id == selectedClipId }
                    ?: tracks.flatMap { it.clips }.firstOrNull()

                // Top bar: replaces the editor header while Transform is open (back chevron + title).
                com.example.ui.components.TransformHeaderBar(
                    height = headerH,
                    onBack = { showTransformMode = false },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(21f)
                )

                // Bottom area: playback row, timescale, the layer's strip and the transform panel.
                // The canvas in the middle stays untouched.
                val transformAreaH = (playControlsH + timescaleH + layerPanelH + toolbarH)
                    .coerceAtLeast(380.dp)
                    .coerceAtMost(totalHeight * 0.62f)

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(transformAreaH)
                        .zIndex(20f)
                        .background(EditorBackground)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
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
                        panelOnly = true,
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
                        onPreviewTransform = { h, v, sc, rot, px, py, r3x, r3y, warp ->
                            selectedClip?.let { clip ->
                                transformPreviewState.value = clip.copy(
                                    transformX = h,
                                    transformY = v,
                                    scalePercent = sc,
                                    rotationDeg = rot,
                                    shapeOffsetX = h,
                                    shapeOffsetY = v,
                                    shapeScaleX = sc / 100f,
                                    shapeScaleY = sc / 100f,
                                    pivotX = px,
                                    pivotY = py,
                                    rotation3dX = r3x,
                                    rotation3dY = r3y,
                                    warpDistort = warp
                                )
                            }
                        }
                    )
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
            resolution = exportSettings.resolution,
            aspectRatio = selectedAspectRatio,
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



    if (showAddShapeSheet) {
        AddShapeSheet(
            onAddShape = { name, pattern ->
                val clipDuration = if (totalDurationMs > 0L && currentPlayheadMs < totalDurationMs) {
                    minOf(4000L, (totalDurationMs - currentPlayheadMs).coerceAtLeast(1000L))
                } else {
                    4000L
                }
                viewModel.addShape(
                    name = name,
                    pattern = pattern,
                    durationMs = clipDuration
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
}
