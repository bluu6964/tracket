package com.example.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.UnstableApi
import com.example.engine.EditorPlayer
import com.example.engine.MediaImporter
import com.example.engine.ProjectStore
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
    val appContext = context.applicationContext
    val exportSettings by viewModel.exportSettings.collectAsState()
    val projectStore = remember(appContext) { ProjectStore(appContext) }
    var projectLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(projectStore, viewModel) {
        try {
            projectStore.load()?.let(viewModel::restoreProject)
        } finally {
            projectLoaded = true
        }
    }
    LaunchedEffect(
        projectLoaded,
        tracks,
        currentPlayheadMs,
        selectedAspectRatio,
        selectedResolution,
        isMasterMuted
    ) {
        if (!projectLoaded) return@LaunchedEffect
        // Debounce frequent playhead updates; persist the final position after scrubbing/playback.
        delay(750L)
        runCatching { projectStore.save(viewModel.createProjectSnapshot()) }
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

    // Main track = playlist of real clips, in timeline order.
    val mainClips = remember(tracks) {
        tracks.firstOrNull()?.clips.orEmpty().filter { it.uri != null }.sortedBy { it.startMs }
    }
    LaunchedEffect(mainClips) {
        editorPlayer.setClips(mainClips, viewModel.currentPlayheadMs.value)
    }
    LaunchedEffect(isMasterMuted) { editorPlayer.setMuted(isMasterMuted) }

    // Scrubbing / seeking while paused moves the player to the playhead.
    LaunchedEffect(currentPlayheadMs, isPlaying) {
        if (!isPlaying) editorPlayer.seekTo(currentPlayheadMs)
    }
    // Play / pause: ExoPlayer is the clock and feeds the playhead back to the ViewModel.
    LaunchedEffect(isPlaying, mainClips) {
        if (mainClips.isEmpty()) return@LaunchedEffect
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
            // Photo Picker grants can be persisted so a saved project can reopen its media later.
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
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

    if (!projectLoaded) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(EditorBackground)
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
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
                        scope.launch {
                            val saveResult = runCatching {
                                projectStore.save(viewModel.createProjectSnapshot())
                            }
                            snackbarHostState.showSnackbar(
                                if (saveResult.isSuccess) "Project saved on this device"
                                else "Could not save project"
                            )
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
                    player = if (showFullscreenPlayer) null else editorPlayer.player,
                    hasMainMedia = mainClips.isNotEmpty() && !showFullscreenPlayer
                )

                // 3. Play & Full Screen Section (1/18 of screen)
                PlayControlsSection(
                    height = playControlsH,
                    isPlaying = isPlaying,
                    canUndo = canUndo,
                    canRedo = canRedo,
                    onFullScreenClick = { showFullscreenPlayer = true },
                    onResolutionClick = { showResolutionSheet = true },
                    onPreviousClick = {
                        if (viewModel.isPlaying.value) viewModel.pause()
                        viewModel.jumpPrevious()
                    },
                    onPlayPauseClick = { viewModel.togglePlayPause() },
                    onNextClick = {
                        if (viewModel.isPlaying.value) viewModel.pause()
                        viewModel.jumpNext()
                    },
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
                    onSeekTo = {
                        if (viewModel.isPlaying.value) viewModel.pause()
                        viewModel.seekTo(it)
                    },
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
                if (selectedClipId != null) {
                    ClipEditToolsPanel(
                        height = toolbarH,
                        onDeselect = { viewModel.selectClip(null) },
                        onSplit = {
                            viewModel.splitSelectedClip()
                            scope.launch { snackbarHostState.showSnackbar("Clip split at playhead") }
                        },
                        onEffects = { showAppliedEffectsMode = true },
                        onAnimations = { showClipAnimationsSheet = true },
                        onCopy = {
                            viewModel.duplicateSelectedClip()
                            scope.launch { snackbarHostState.showSnackbar("Clip duplicated") }
                        },
                        onReplace = { showClipReplaceSheet = true },
                        onColor = { showEditShapeMode = true },
                        onTransform = {
                            scope.launch { snackbarHostState.showSnackbar("Transform active - drag/pinch clip on canvas") }
                        },
                        onCrop = { showClipCropSheet = true },
                        onDelete = {
                            viewModel.deleteSelectedClip()
                            scope.launch { snackbarHostState.showSnackbar("Clip deleted") }
                        },
                        onBlend = { showClipBlendSheet = true },
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
                        onAudioClick = { showAddAudioSheet = true },
                        onTextClick = { showAddTextSheet = true },
                        onShapeClick = { showEditShapeMode = true },
                        onOverlayClick = {
                            viewModel.addClip(
                                type = LayerType.OVERLAY,
                                name = "Media Overlay",
                                durationMs = 6000L
                            )
                            scope.launch { snackbarHostState.showSnackbar("Added overlay layer") }
                        },
                        onStickersClick = {
                            viewModel.addClip(
                                type = LayerType.STICKER,
                                name = "✨ Sparkles Sticker",
                                durationMs = 5000L,
                                text = "✨ NEW VLOG ✨"
                            )
                            scope.launch { snackbarHostState.showSnackbar("Added sticker layer") }
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
            onStartExport = {
                viewModel.pause()
                viewModel.setExporting(true)
                exporter.start(
                    tracks = tracks,
                    aspectRatio = selectedAspectRatio,
                    settings = exportSettings,
                    masterMuted = isMasterMuted,
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
                viewModel.addClip(
                    type = LayerType.SHAPE,
                    name = name,
                    durationMs = 10_000L,
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
            tracks = tracks,
            player = editorPlayer.player,
            hasMainMedia = mainClips.isNotEmpty(),
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
                onDismiss = { showEditShapeMode = false },
                onSaveShape = { shapeType, w, h, color, feather ->
                    viewModel.addClip(
                        type = LayerType.SHAPE,
                        name = "${shapeType.label} Shape",
                        durationMs = 9140L,
                        shapePattern = shapeType.name
                    )
                    scope.launch { snackbarHostState.showSnackbar("Added ${shapeType.label} layer") }
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
        ClipCropSheet(
            onCropRatioSelected = { ratio ->
                scope.launch { snackbarHostState.showSnackbar("Crop ratio set to $ratio") }
            },
            onDismiss = { showClipCropSheet = false }
        )
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
}
