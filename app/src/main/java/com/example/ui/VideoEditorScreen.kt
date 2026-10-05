package com.example.ui

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                viewModel.selectClip(null)
                // Keep access to the selected file after the picker closes.
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) { }
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1L
                viewModel.addImportedVideo(uri, duration, uri.lastPathSegment ?: "Video")
            } finally { retriever.release() }
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
                    masterMuted = isMasterMuted,
                    onTogglePlayPause = { viewModel.togglePlayPause() }
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
                    onAddMediaClick = { videoPicker.launch(arrayOf("video/*")) }
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
                        onOverlayClick = { videoPicker.launch(arrayOf("video/*", "image/*")) },
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
                viewModel.startExport(
                    context = context,
                    onComplete = { uri -> scope.launch { snackbarHostState.showSnackbar("Export complete: $uri") } },
                    onError = { message -> scope.launch { snackbarHostState.showSnackbar(message) } }
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
            onImport = { showClipReplaceSheet = false; videoPicker.launch(arrayOf("video/*")) },
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
