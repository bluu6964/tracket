package com.example.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.ui.components.TextEditSheet
import com.example.ui.components.swipeDownToDismiss
import com.example.ui.util.enterPop
import com.example.ui.components.pickColorFromWindow
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
import android.content.Intent
import android.graphics.Bitmap
import com.example.engine.MediaImporter
import com.example.engine.VideoExporter
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.LayerType
import com.example.model.at
import com.example.model.hasBlending
import com.example.model.TIMELINE_PX_PER_MS
import com.example.ui.components.AudioClipToolsPanel
import com.example.ui.components.AudioFadeSheet
import com.example.ui.components.AudioSpeedSheet
import com.example.ui.components.AudioVolumeSheet
import com.example.ui.components.AddShapeSheet
import com.example.ui.components.StickerPickerSheet
import com.example.ui.components.StickerSpeedSheet
import com.example.ui.components.AddTextSheet
import com.example.ui.components.AppliedEffectsView
import com.example.ui.components.AspectRatioSheet
import com.example.ui.components.BottomToolbar
import com.example.ui.components.CanvasArea
import com.example.ui.components.ClipEditToolsPanel
import com.example.ui.components.ClipEffectsSheet
import com.example.ui.components.ClipAnimationsPanel
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
import com.example.ui.components.TrackLayerPickDialog
import com.example.ui.components.PlanarRunOverlay
import com.example.ui.components.PlanarSetupOverlay
import com.example.ui.components.RemoveMaskOverlay
import com.example.ui.components.TrackRunOverlay
import com.example.ui.components.TrackSetupOverlay
import com.example.ui.components.TrackTarget
import kotlinx.coroutines.launch
import com.example.model.sourceOffsetMs
import com.example.model.ImportedMedia
import com.example.engine.CutoutEngine
import androidx.core.net.toUri
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/** The video layer being prepared for tracking and the picture's width / height. */
private data class TrackSetupState(
    val clipId: String,
    val aspect: Float,
    val targetId: String,
    val maskId: String? = null,
    /** Planar track + corner pin (the 4 corners of a flat surface) instead of the circle. */
    val planar: Boolean = false,
    /** Remove (clean patch): the planar setup is followed by painting over the flaw. */
    val remove: Boolean = false,
    /** Stabilize: the planar setup is followed by tracking, the layer is then pinned on itself. */
    val stabilize: Boolean = false,
    /** Camera track: the video itself moves so the tracked part stays in the middle of the screen. */
    val camera: Boolean = false
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun VideoEditorScreen(
    viewModel: VideoEditorViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    // The playhead changes every frame. It is held as a State and only READ in lambdas / draw /
    // layout phases (or inside modes that are open), so the whole editor no longer recomposes per frame.
    val playheadState = viewModel.currentPlayheadMs.collectAsState()
    val playheadProvider: () -> Long = remember { { playheadState.value } }
    val totalDurationMs by viewModel.totalDurationMs.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val rawAspectRatio by viewModel.selectedAspectRatio.collectAsState()
    val canvasColor by viewModel.canvasColor.collectAsState()
    val selectedResolution by viewModel.selectedResolution.collectAsState()
    val selectedClipId by viewModel.selectedClipId.collectAsState()
    val multiSelectIds by viewModel.multiSelectIds.collectAsState()
    val isMasterMuted by viewModel.isMasterMuted.collectAsState()
    val beatMarks by viewModel.beatMarks.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    // Edit Group: inside a group only its layers are shown (timeline + canvas); outside, a group is one single layer.
    val editingGroupId by viewModel.editingGroupId.collectAsState()
    val shownTracks = remember(tracks, editingGroupId) { com.example.model.GroupOps.scopeTracks(tracks, editingGroupId) }
    // "Original" = the shape of the first video / photo.
    val originalAspect = remember(tracks) {
        tracks.flatMap { it.clips }
            .filter { it.type == LayerType.VIDEO && it.uri != null && it.sourceAspect > 0f }
            .minByOrNull { it.startMs }?.sourceAspect ?: 0f
    }
    val selectedAspectRatio = remember(rawAspectRatio, originalAspect) {
        rawAspectRatio.withOriginalAspect(originalAspect)
    }
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val isExporting by viewModel.isExporting.collectAsState()
    val exportProgress by viewModel.exportProgress.collectAsState()
    val waveforms by viewModel.waveforms.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Full-video cutout (RVM): progress 0..1 while it runs, null when idle.
    var cutoutProgress by remember { androidx.compose.runtime.mutableStateOf<Float?>(null) }
    var cutoutJob by remember { androidx.compose.runtime.mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var cutoutLabel by remember { androidx.compose.runtime.mutableStateOf("Cutting out the person…") }
    // Roto Brush (EdgeSAM): the video layer being brushed, null = closed.
    var rotoClipId by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    // Video layer waiting for the user to agree to download the cutout model.
    var askModelDownload by remember { androidx.compose.runtime.mutableStateOf<com.example.model.TimelineClip?>(null) }

    // Runs the video cutout; downloads the model first when it is not on the phone yet.
    val runVideoCutout: (com.example.model.TimelineClip, Boolean) -> Unit = { target, needDownload ->
        cutoutProgress = 0f
        cutoutLabel = if (needDownload) "Downloading cutout model…" else "Cutting out the person…"
        cutoutJob = scope.launch {
            try {
                if (needDownload) {
                    withContext(Dispatchers.IO) {
                        com.example.engine.ModelDownloader.download(context) { p -> cutoutProgress = p }
                    }
                    cutoutLabel = "Cutting out the person…"
                    cutoutProgress = 0f
                }
                val r = withContext(Dispatchers.Default) {
                    com.example.engine.CutoutVideo.generate(context, target) { p -> cutoutProgress = p }
                }
                viewModel.beginAdjust()
                viewModel.updateClip(target.id) {
                    it.copy(
                        cutoutDir = r.dir, cutoutFps = r.fps,
                        cutoutStartMs = r.startMs, cutoutFrames = r.frames
                    )
                }
                cutoutProgress = null
                snackbarHostState.showSnackbar("Cutout done" + (if (r.summary.isNotEmpty()) " · " + r.summary else ""))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                cutoutProgress = null
                snackbarHostState.showSnackbar("Cutout failed: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                cutoutProgress = null
                cutoutJob = null
            }
        }
    }
    // Roto Brush (EdgeSAM): brush an object, it is followed through the whole clip and becomes the layer's cutout.
    rotoClipId?.let { rid ->
        val rc = tracks.flatMap { it.clips }.find { it.id == rid }
        if (rc == null) {
            rotoClipId = null
        } else {
            com.example.ui.components.RotoBrushScreen(
                clip = rc,
                startSrcMs = rc.trimStartMs + rc.sourceOffsetMs((playheadState.value - rc.startMs).coerceAtLeast(0L)),
                onClose = { rotoClipId = null },
                onApply = { r ->
                    viewModel.beginAdjust()
                    viewModel.updateClip(rid) {
                        it.copy(
                            cutoutDir = r.dir, cutoutFps = r.fps,
                            cutoutStartMs = r.startMs, cutoutFrames = r.frames
                        )
                    }
                    rotoClipId = null
                    scope.launch { snackbarHostState.showSnackbar("Roto done" + (if (r.summary.isNotEmpty()) " · " + r.summary else "")) }
                }
            )
        }
    }
    askModelDownload?.let { pending ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askModelDownload = null },
            title = { Text("Download cutout model?") },
            text = { Text("Cutout needs a one-time download of about 14 MB. It is kept on your phone and works offline after that.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    askModelDownload = null
                    runVideoCutout(pending, true)
                }) { Text("Download") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { askModelDownload = null }) { Text("Not now") }
            }
        )
    }
    val exportSettings by viewModel.exportSettings.collectAsState()
    // Size of the canvas frame in the preview: the export draws shapes / text at the same relative size.
    var previewFrameWidthPx by remember { mutableFloatStateOf(0f) }
    var previewDensity by remember { mutableFloatStateOf(1f) }

    // ---- Face AI (MediaPipe Face Landmarker): blur that follows the face / eyes / nose / lips ----
    var faceProgress by remember { androidx.compose.runtime.mutableStateOf<Float?>(null) }
    var faceLabel by remember { androidx.compose.runtime.mutableStateOf("Finding the face…") }
    var faceJob by remember { androidx.compose.runtime.mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var askFaceAi by remember { androidx.compose.runtime.mutableStateOf<com.example.model.TimelineClip?>(null) }
    var faceRegion by remember {
        androidx.compose.runtime.mutableStateOf(
            com.example.engine.FaceRegion.fromName(
                context.getSharedPreferences("face_ai", android.content.Context.MODE_PRIVATE).getString("region", null)
            )
        )
    }
    var faceStrength by remember {
        androidx.compose.runtime.mutableStateOf(
            context.getSharedPreferences("face_ai", android.content.Context.MODE_PRIVATE).getFloat("strength", 0.5f)
        )
    }

    val requestFaceAi: (String?) -> Unit = req@{ id ->
        val clip = tracks.flatMap { it.clips }.find { it.id == id } ?: return@req
        snackbarHostState.currentSnackbarData?.dismiss()
        if (clip.type != LayerType.VIDEO || clip.isImage || clip.uri == null) {
            scope.launch { snackbarHostState.showSnackbar("Face AI works on video layers") }
        } else if (faceProgress != null) {
            scope.launch { snackbarHostState.showSnackbar("Face AI is already running") }
        } else {
            // Start with what this layer already has, so the dialog shows the current choice.
            if (clip.faceBlurDir != null) {
                faceRegion = com.example.engine.FaceRegion.fromName(clip.faceBlurRegion)
                faceStrength = clip.faceBlurStrength
            }
            askFaceAi = clip
        }
    }

    // Downloads the face model when it is missing, finds the face in every frame, saves the masks.
    val runFaceBlur: (com.example.model.TimelineClip, com.example.engine.FaceRegion, Float) -> Unit = { target, region, strength ->
        context.getSharedPreferences("face_ai", android.content.Context.MODE_PRIVATE)
            .edit().putString("region", region.name).putFloat("strength", strength).apply()
        faceProgress = 0f
        faceJob = scope.launch {
            try {
                if (!com.example.engine.FaceModelDownloader.isDownloaded(context)) {
                    faceLabel = "Downloading face model…"
                    withContext(Dispatchers.IO) {
                        com.example.engine.FaceModelDownloader.download(context) { p -> faceProgress = p }
                    }
                    faceProgress = 0f
                }
                faceLabel = "Finding the face…"
                val r = withContext(Dispatchers.Default) {
                    com.example.engine.FaceBlurVideo.generate(context, target, region) { p -> faceProgress = p }
                }
                viewModel.beginAdjust()
                viewModel.updateClip(target.id) {
                    it.copy(
                        faceBlurDir = r.dir, faceBlurFps = r.fps,
                        faceBlurStartMs = r.startMs, faceBlurFrames = r.frames,
                        faceBlurStrength = strength, faceBlurRegion = region.name
                    )
                }
                faceProgress = null
                val pct = (100f * r.framesWithFace / r.frames.coerceAtLeast(1)).toInt().coerceIn(0, 100)
                snackbarHostState.showSnackbar(
                    region.label + " blur done · face in $pct% of the frames · " + r.summary
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                faceProgress = null
                scope.launch { snackbarHostState.showSnackbar("Face AI cancelled") }
                throw e
            } catch (e: Throwable) {
                faceProgress = null
                snackbarHostState.showSnackbar("Face AI failed: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                faceProgress = null
                faceJob = null
            }
        }
    }
    askFaceAi?.let { pending ->
        val modelReady = com.example.engine.FaceModelDownloader.isDownloaded(context)
        val hasBlur = pending.faceBlurDir != null
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askFaceAi = null },
            title = { Text("Face AI · Blur") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        if (modelReady) "The blur follows the face in every frame. Works offline."
                        else "Face AI needs a one-time download of the face model (" +
                            com.example.engine.FaceModelDownloader.APPROX_SIZE_TEXT +
                            "). It is kept on your phone and works offline after that.",
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("What to blur", fontSize = 12.sp, color = Color(0xFF9AA4B5))
                    com.example.engine.FaceRegion.values().forEach { r ->
                        androidx.compose.foundation.layout.Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { faceRegion = r }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.RadioButton(selected = faceRegion == r, onClick = { faceRegion = r })
                            Text(r.label, fontSize = 14.sp)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Blur strength", fontSize = 12.sp, color = Color(0xFF9AA4B5))
                    listOf("Low" to 0.15f, "Medium" to 0.5f, "High" to 0.9f).forEach { (name, v) ->
                        androidx.compose.foundation.layout.Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { faceStrength = v }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = kotlin.math.abs(faceStrength - v) < 0.2f &&
                                    listOf(0.15f, 0.5f, 0.9f).minByOrNull { kotlin.math.abs(it - faceStrength) } == v,
                                onClick = { faceStrength = v }
                            )
                            Text(name, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    askFaceAi = null
                    runFaceBlur(pending, faceRegion, faceStrength)
                }) { Text(if (modelReady) "Process" else "Download & process") }
            },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    if (hasBlur) {
                        androidx.compose.material3.TextButton(onClick = {
                            askFaceAi = null
                            viewModel.beginAdjust()
                            viewModel.updateClip(pending.id) {
                                it.copy(faceBlurDir = null, faceBlurFps = 0f, faceBlurStartMs = 0L, faceBlurFrames = 0, faceBlurRegion = "")
                            }
                            scope.launch { snackbarHostState.showSnackbar("Face blur removed") }
                        }) { Text("Remove") }
                    }
                    androidx.compose.material3.TextButton(onClick = { askFaceAi = null }) { Text("Cancel") }
                }
            }
        )
    }

    // ---- Auto Captions: speech -> text layers (whisper.cpp, model "small" q5_1, downloaded on first use) ----
    // ONE state for the bar (label + %), so a label never shows next to the % of another stage. null = not running.
    var captionStatus by remember { mutableStateOf<com.example.engine.AutoCaptions.Status?>(null) }
    var captionJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // Layer waiting for the user to pick the language (and agree to the model download).
    var askCaptions by remember { mutableStateOf<com.example.model.TimelineClip?>(null) }
    var captionLang by remember {
        mutableStateOf(
            context.getSharedPreferences("captions", android.content.Context.MODE_PRIVATE)
                .getString("lang", "auto") ?: "auto"
        )
    }
    // Maximum characters per caption (Short / Normal / Long in the dialog), remembered.
    var captionMaxChars by remember {
        mutableStateOf(
            context.getSharedPreferences("captions", android.content.Context.MODE_PRIVATE)
                .getInt("maxChars", com.example.engine.AutoCaptions.MAX_CHARS)
        )
    }

    // Tool "Captions" on a video / audio layer: checks the phone, then asks language + download.
    val requestCaptions: (String?) -> Unit = req@{ id ->
        val clip = tracks.flatMap { it.clips }.find { it.id == id } ?: return@req
        snackbarHostState.currentSnackbarData?.dismiss()
        val unsupported = com.example.engine.WhisperEngine.unsupportedReason()
        if (!com.example.engine.AutoCaptions.canCaption(clip)) {
            scope.launch { snackbarHostState.showSnackbar("Captions work on video and audio layers") }
        } else if (unsupported != null) {
            scope.launch { snackbarHostState.showSnackbar(unsupported) }
        } else if (captionStatus != null) {
            scope.launch { snackbarHostState.showSnackbar("Captions are already being made") }
        } else {
            askCaptions = clip
        }
    }

    // Downloads the speech model when it is missing, then makes the captions and adds them as text layers.
    // Every step has its own label and its own 0..100 % (the download is separate from the captions).
    val runCaptions: (com.example.model.TimelineClip, String, Int) -> Unit = { target, lang, maxChars ->
        context.getSharedPreferences("captions", android.content.Context.MODE_PRIVATE)
            .edit().putString("lang", lang).putInt("maxChars", maxChars).apply()
        captionStatus = com.example.engine.AutoCaptions.Status("Starting", 0f)
        captionJob = scope.launch {
            try {
                if (!com.example.engine.WhisperModelDownloader.isDownloaded(context)) {
                    captionStatus = com.example.engine.AutoCaptions.Status("Downloading speech model", 0f)
                    var mb = ""
                    withContext(Dispatchers.IO) {
                        com.example.engine.WhisperModelDownloader.download(
                            context,
                            onBytes = { done, total ->
                                mb = " (" + done / 1_000_000L + " / " + total / 1_000_000L + " MB)"
                            }
                        ) { p ->
                            captionStatus = com.example.engine.AutoCaptions.Status("Downloading speech model$mb", p)
                        }
                    }
                }
                val made = withContext(Dispatchers.Default) {
                    com.example.engine.AutoCaptions.generate(
                        context, target, lang, maxChars,
                        onStatus = { captionStatus = it }
                    )
                }
                // the layer may have been moved while the captions were made: use where it is now
                val parentStart = tracks.flatMap { it.clips }.find { it.id == target.id }?.startMs ?: target.startMs
                val fw = previewFrameWidthPx
                val fh = if (fw > 0f) fw / selectedAspectRatio.ratioFloat else 0f
                // Show the last stage before the (short) adding step, and let one frame draw it.
                captionStatus = com.example.engine.AutoCaptions.Status(
                    "Adding " + made.size + " captions to the timeline",
                    com.example.engine.CaptionProgress.Stage.PLACE.from
                )
                androidx.compose.runtime.withFrameNanos { }
                val added = viewModel.addCaptionLayers(parentStart, made, fh * 0.30f)
                captionStatus = null
                snackbarHostState.showSnackbar(if (added > 0) "$added captions added" else "No speech found")
            } catch (e: kotlinx.coroutines.CancellationException) {
                captionStatus = null
                scope.launch { snackbarHostState.showSnackbar("Captions cancelled") }
                throw e
            } catch (e: Throwable) {
                captionStatus = null
                snackbarHostState.showSnackbar("Captions failed: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                captionStatus = null
                captionJob = null
            }
        }
    }
    askCaptions?.let { pending ->
        val modelReady = com.example.engine.WhisperModelDownloader.isDownloaded(context)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askCaptions = null },
            title = { Text("Auto Captions") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        if (modelReady) "The speech of this layer becomes text layers on the timeline. Works offline."
                        else "Captions need a one-time download of the speech model (" +
                            com.example.engine.WhisperModelDownloader.APPROX_SIZE_TEXT +
                            "). Wi-Fi is recommended. It is kept on your phone and works offline after that.",
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("Language of the speech", fontSize = 12.sp, color = Color(0xFF9AA4B5))
                    com.example.engine.AutoCaptions.LANGUAGES.forEach { (code, name) ->
                        androidx.compose.foundation.layout.Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { captionLang = code }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = captionLang == code,
                                onClick = { captionLang = code }
                            )
                            Text(name, fontSize = 14.sp)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Length of one caption", fontSize = 12.sp, color = Color(0xFF9AA4B5))
                    com.example.engine.AutoCaptions.LENGTHS.forEach { (name, chars) ->
                        androidx.compose.foundation.layout.Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { captionMaxChars = chars }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = captionMaxChars == chars,
                                onClick = { captionMaxChars = chars }
                            )
                            Text(name, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    askCaptions = null
                    runCaptions(pending, captionLang, captionMaxChars)
                }) { Text(if (modelReady) "Start" else "Download & start") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { askCaptions = null }) { Text("Not now") }
            }
        )
    }

    // ---- Motion tracking: pick a layer, put a circle on the object, watch the layer follow live ----
    var trackPickFor by remember { mutableStateOf<String?>(null) } // video waiting for "which layer follows?"
    var trackSetup by remember { mutableStateOf<TrackSetupState?>(null) }
    var trackFollowRot by remember { mutableStateOf(false) } // the follower also turns with the object
    var trackFollowScale by remember { mutableStateOf(false) } // ... and zooms with it
    var trackCircle by remember { mutableStateOf(com.example.ui.components.TrackCircle()) }
    var trackRunning by remember { mutableStateOf(false) }
    var trackProgress by remember { mutableStateOf<Float?>(null) }
    var trackLive by remember { mutableStateOf<com.example.ui.components.TrackLive?>(null) }
    var trackJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val trackStop = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    // Planar track (Mocha-style corner pin): the 4 corners of the surface, and what the tracker sees while it runs
    var planarQuad by remember { mutableStateOf(com.example.ui.components.PlanarQuad()) }
    var planarRunning by remember { mutableStateOf(false) }
    var planarLive by remember { mutableStateOf<com.example.ui.components.PlanarLive?>(null) }
    // Remove (clean patch over a flaw): the menu, the step (0 = corners, 1 = paint), what was painted, what the bar says
    var removeMenuFor by remember { mutableStateOf<String?>(null) }
    var removeStage by remember { mutableStateOf(0) }
    var removePaint by remember { mutableStateOf(com.example.ui.components.RemovePaint()) }
    var removeUseFrames by remember { mutableStateOf(true) }
    var removeLabel by remember { mutableStateOf<String?>(null) }
    var stabilizeMenuFor by remember { mutableStateOf<String?>(null) }
    var stabilizeStrength by remember { mutableStateOf(0.55f) }

    // Tool "Track" (video layer selected): first ask which layer should follow the object.
    val requestTrack: (String?) -> Unit = req@{ id ->
        if (trackRunning) {
            scope.launch { snackbarHostState.showSnackbar("A track is already running") }
            return@req
        }
        trackPickFor = id
    }

    // A follower layer was picked: show the circle over the canvas (the track starts at the playhead).
    val openTrackSetup: (String, String, String?, Boolean) -> Unit = openSetup@{ clipId, targetId, maskId, camera ->
        val video = tracks.flatMap { it.clips }.find { it.id == clipId } ?: return@openSetup
        val uri = video.uri ?: return@openSetup
        if (!video.isActiveAt(playheadState.value)) viewModel.seekTo(video.startMs)
        viewModel.pause()
        scope.launch {
            val a = withContext(Dispatchers.IO) { com.example.engine.MotionTrack.pictureAspect(context, uri) }
            trackCircle = com.example.ui.components.TrackCircle()
            trackSetup = TrackSetupState(video.id, if (a > 0f) a else video.sourceAspect, targetId, maskId, camera = camera)
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                if (camera) "Put the circle on the part that must stay in the center, then press Track"
                else if (maskId != null) "Put the circle on the object the mask sits on, then press Track"
                else "Put the circle on a part of the object, then press Track"
            )
        }
    }

    // "Track" button of the circle: follows the object from the playhead to the end of the layer.
    // The circle, the followed corners and the follower layer move live; Stop keeps what is done.
    val startTracking: () -> Unit = start@{
        val setup = trackSetup ?: return@start
        val all = tracks.flatMap { it.clips }
        val video = all.find { it.id == setup.clipId } ?: return@start
        val target = all.find { it.id == setup.targetId }
        if (target == null) {
            trackSetup = null
            scope.launch { snackbarHostState.showSnackbar("The layer that should follow is gone") }
            return@start
        }
        val fw = previewFrameWidthPx
        if (fw <= 0f) return@start
        val fh = fw / selectedAspectRatio.ratioFloat
        val t = playheadState.value
        if (!video.isActiveAt(t)) {
            scope.launch { snackbarHostState.showSnackbar("Move the playhead onto the video layer") }
            return@start
        }
        val placement = com.example.model.VideoPlacement(video.at(t), setup.aspect, fw, fh)
        val c = trackCircle
        val p = placement.fromCanvas(c.cx * fw, c.cy * fh)
        if (p.x < 0f || p.x > 1f || p.y < 0f || p.y > 1f) {
            scope.launch { snackbarHostState.showSnackbar("Put the circle on the video picture") }
            return@start
        }
        val rNorm = (c.r * fw) / placement.pixelsPerPictureWidth
        val followRot = trackFollowRot
        val followScale = trackFollowScale
        val maskId = setup.maskId
        val isCamera = setup.camera
        val oldMasks = video.masks
        // camera track: the video moves the other way (point stays in the middle); otherwise a layer follows the object
        fun followOf(res: com.example.model.TrackResult): com.example.model.TrackFollow? =
            if (isCamera) com.example.model.MotionTrackApply.cameraFollow(video, res, fw, fh)
            else com.example.model.MotionTrackApply.follow(video, res, fw, fh, followRot, followScale)
        // a mask of the video itself follows the object (Mocha-style); a layer follows it with a position track
        fun withMaskTrack(res: com.example.model.TrackResult): List<com.example.model.ClipMask> {
            val mt = com.example.model.MaskTrack.from(res, followRot, followScale)
            return oldMasks.map { if (it.id == maskId) it.copy(track = mt) else it }
        }
        val startSrc = com.example.engine.CutoutMasks.sourceMs(video, t)
        viewModel.pause()
        viewModel.beginAdjust() // one undo step for the whole track
        trackSetup = null
        trackStop.set(false)
        trackLive = null
        trackProgress = 0f
        trackRunning = true
        trackJob = scope.launch {
            var lastHeavy = 0L
            var done = false
            try {
                val r = com.example.engine.MotionTrack.run(
                    context, video, startSrc, p.x, p.y, rNorm,
                    onProgress = { pr -> trackProgress = pr },
                    onLive = { lf ->
                        // circle + corners on the canvas, in the video's own (possibly moving) place
                        val tl = com.example.model.MotionTrackApply.timelineMsAt(video, lf.srcMs)
                        val pl = com.example.model.VideoPlacement(video.at(tl), lf.aspect, fw, fh)
                        val cp = pl.toCanvas(lf.u, lf.v)
                        // camera track: the video is moved so the point sits in the middle, the circle goes with it
                        val shX = if (isCamera) fw / 2f - cp.x else 0f
                        val shY = if (isCamera) fh / 2f - cp.y else 0f
                        val pts = FloatArray(lf.pts.size)
                        var i = 0
                        while (i + 1 < pts.size) {
                            val q = pl.toCanvas(lf.pts[i], lf.pts[i + 1])
                            pts[i] = (q.x + shX) / fw
                            pts[i + 1] = (q.y + shY) / fh
                            i += 2
                        }
                        trackLive = com.example.ui.components.TrackLive(
                            (cp.x + shX) / fw, (cp.y + shY) / fh, lf.rNorm * pl.pixelsPerPictureWidth / fw, pts
                        )
                        // the follower layer and the playhead move along (a few times a second)
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastHeavy >= 120L) {
                            lastHeavy = now
                            val part = com.example.model.TrackResult(video.id, lf.samples.toList(), lf.aspect)
                            val fol = if (maskId != null) null else followOf(part)
                            val liveMasks = if (maskId != null) withMaskTrack(part) else null
                            scope.launch {
                                if (liveMasks != null) viewModel.setClipMasks(video.id, liveMasks)
                                if (fol != null) {
                                    viewModel.setTrackFollow(target.id, fol, undoStep = false)
                                    // keep the layer on screen as long as the object is followed (the video keeps its length)
                                    val need = fol.timesMs.last() - target.startMs
                                    if (!isCamera && need > target.durationMs) viewModel.setOverlayDurationMs(target.id, need)
                                }
                                viewModel.seekTo(tl)
                            }
                        }
                    },
                    shouldStop = { trackStop.get() }
                )
                val fol = if (maskId != null) null else followOf(r)
                if (if (maskId != null) r.samples.isEmpty() else fol == null) {
                    scope.launch { snackbarHostState.showSnackbar("Nothing could be tracked") }
                } else {
                    if (maskId != null) {
                        viewModel.setClipMasks(video.id, withMaskTrack(r))
                    } else if (fol != null) {
                        viewModel.setTrackFollow(target.id, fol, undoStep = false)
                        val need = fol.timesMs.last() - target.startMs
                        if (!isCamera && need > target.durationMs) viewModel.setOverlayDurationMs(target.id, need)
                    }
                    done = true
                    val lost = r.lostAtSrcMs
                    val msg = if (lost != null) {
                        "Lost the object after " +
                            String.format(java.util.Locale.US, "%.1f", (lost - startSrc) / 1000f) +
                            " s. The " + (if (maskId != null) "mask" else "layer") + " follows up to there."
                    } else if (trackStop.get()) "Stopped. The " + (if (maskId != null) "mask" else "layer") + " follows the part tracked so far."
                    else {
                        val lastSrc = r.samples.last().srcMs
                        val endSrc = video.trimStartMs + video.sourceOffsetMs(video.durationMs)
                        val secs = String.format(java.util.Locale.US, "%.1f", (lastSrc - startSrc) / 1000f)
                        val total = String.format(java.util.Locale.US, "%.1f", (endSrc - startSrc) / 1000f)
                        if (endSrc - lastSrc > 700L) "Video reading stopped early: tracked $secs s of $total s."
                        else "Done. Tracked $secs s of $total s."
                    }
                    scope.launch { snackbarHostState.showSnackbar(msg) }
                }
                viewModel.seekTo(t)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                scope.launch { snackbarHostState.showSnackbar("Tracking failed: " + (e.message ?: e.javaClass.simpleName)) }
            } finally {
                if (!done) {
                    if (maskId != null) {
                        viewModel.setClipMasks(video.id, oldMasks)
                    } else {
                        viewModel.setTrackFollow(target.id, target.trackFollow, undoStep = false)
                        if (!isCamera) viewModel.setOverlayDurationMs(target.id, target.durationMs)
                    }
                }
                trackRunning = false
                trackProgress = null
                trackLive = null
                trackJob = null
            }
        }
    }

    // "Pin" was picked: show the 4 corners over the canvas (the planar track starts at the playhead).
    val openPlanarSetup: (String, String) -> Unit = openPlanar@{ clipId, targetId ->
        val video = tracks.flatMap { it.clips }.find { it.id == clipId } ?: return@openPlanar
        val uri = video.uri ?: return@openPlanar
        if (!com.example.engine.PlanarTrack.isAvailable) {
            scope.launch { snackbarHostState.showSnackbar("The native engine is not available on this phone") }
            return@openPlanar
        }
        if (!video.isActiveAt(playheadState.value)) viewModel.seekTo(video.startMs)
        viewModel.pause()
        scope.launch {
            val a = withContext(Dispatchers.IO) { com.example.engine.MotionTrack.pictureAspect(context, uri) }
            planarQuad = com.example.ui.components.PlanarQuad()
            trackSetup = TrackSetupState(video.id, if (a > 0f) a else video.sourceAspect, targetId, null, planar = true)
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar("Drag the 4 corners onto a flat surface (screen, poster, wall), then press Track")
        }
    }

    // "Track" button of the 4 corners: follows the surface (perspective included) from the playhead to the end of the layer.
    // The corners, the followed points and the pinned layer move live; Stop keeps what is done.
    val startPlanarTracking: () -> Unit = startPlanar@{
        val setup = trackSetup ?: return@startPlanar
        val all = tracks.flatMap { it.clips }
        val video = all.find { it.id == setup.clipId } ?: return@startPlanar
        val target = all.find { it.id == setup.targetId }
        if (target == null) {
            trackSetup = null
            scope.launch { snackbarHostState.showSnackbar("The layer to pin is gone") }
            return@startPlanar
        }
        val fw = previewFrameWidthPx
        if (fw <= 0f) return@startPlanar
        val fh = fw / selectedAspectRatio.ratioFloat
        val t = playheadState.value
        if (!video.isActiveAt(t)) {
            scope.launch { snackbarHostState.showSnackbar("Move the playhead onto the video layer") }
            return@startPlanar
        }
        // the 4 corners: canvas frame -> the video's own picture (0..1)
        val placement = com.example.model.VideoPlacement(video.at(t), setup.aspect, fw, fh)
        val quadUv = FloatArray(8)
        for (i in 0 until 4) {
            val o = planarQuad.c[i]
            val p = placement.fromCanvas(o.x * fw, o.y * fh)
            if (p.x < 0f || p.x > 1f || p.y < 0f || p.y > 1f) {
                scope.launch { snackbarHostState.showSnackbar("Put all 4 corners on the video picture") }
                return@startPlanar
            }
            quadUv[2 * i] = p.x
            quadUv[2 * i + 1] = p.y
        }
        val startSrc = com.example.engine.CutoutMasks.sourceMs(video, t)
        viewModel.pause()
        viewModel.beginAdjust() // one undo step for the whole track
        trackSetup = null
        trackStop.set(false)
        planarLive = null
        trackProgress = 0f
        trackRunning = true
        planarRunning = true
        trackJob = scope.launch {
            var lastHeavy = 0L
            var done = false
            try {
                val r = com.example.engine.PlanarTrack.run(
                    context, video, startSrc, quadUv,
                    onProgress = { pr -> trackProgress = pr },
                    onLive = { lf ->
                        // corners + followed points on the canvas, in the video's own (possibly moving) place
                        val tl = com.example.model.MotionTrackApply.timelineMsAt(video, lf.srcMs)
                        val pl = com.example.model.VideoPlacement(video.at(tl), lf.aspect, fw, fh)
                        val qc = FloatArray(8)
                        for (i in 0 until 4) {
                            val q = pl.toCanvas(lf.quad[2 * i], lf.quad[2 * i + 1])
                            qc[2 * i] = q.x / fw
                            qc[2 * i + 1] = q.y / fh
                        }
                        val pts = FloatArray(lf.pts.size)
                        var i = 0
                        while (i + 1 < pts.size) {
                            val q = pl.toCanvas(lf.pts[i], lf.pts[i + 1])
                            pts[i] = q.x / fw
                            pts[i + 1] = q.y / fh
                            i += 2
                        }
                        planarLive = com.example.ui.components.PlanarLive(qc, pts)
                        // the pinned layer and the playhead move along (a few times a second)
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastHeavy >= 120L) {
                            lastHeavy = now
                            val part = com.example.model.PlanarResult(video.id, lf.samples.toList(), lf.aspect)
                            val pin = com.example.model.CornerPinApply.from(video, part)
                            scope.launch {
                                if (pin != null) {
                                    viewModel.setCornerPin(target.id, pin, undoStep = false)
                                    val need = pin.timesMs.last() - target.startMs
                                    if (need > target.durationMs) viewModel.setOverlayDurationMs(target.id, need)
                                }
                                viewModel.seekTo(tl)
                            }
                        }
                    },
                    shouldStop = { trackStop.get() }
                )
                val pin = com.example.model.CornerPinApply.from(video, r)
                if (pin == null) {
                    scope.launch { snackbarHostState.showSnackbar("Nothing could be tracked") }
                } else {
                    viewModel.setCornerPin(target.id, pin, undoStep = false)
                    val need = pin.timesMs.last() - target.startMs
                    if (need > target.durationMs) viewModel.setOverlayDurationMs(target.id, need)
                    done = true
                    val lost = r.lostAtSrcMs
                    val msg = if (lost != null) {
                        "Lost the surface after " + String.format(java.util.Locale.US, "%.1f", (lost - startSrc) / 1000f) +
                            " s. The layer is pinned up to there."
                    } else if (trackStop.get()) "Stopped. The layer is pinned for the part tracked so far."
                    else {
                        val lastSrc = r.samples.last().srcMs
                        val secs = String.format(java.util.Locale.US, "%.1f", (lastSrc - startSrc) / 1000f)
                        "Done. Pinned for $secs s."
                    }
                    scope.launch { snackbarHostState.showSnackbar(msg) }
                }
                viewModel.seekTo(t)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                scope.launch { snackbarHostState.showSnackbar("Tracking failed: " + (e.message ?: e.javaClass.simpleName)) }
            } finally {
                if (!done) {
                    viewModel.setCornerPin(target.id, target.cornerPin, undoStep = false)
                    viewModel.setOverlayDurationMs(target.id, target.durationMs)
                }
                trackRunning = false
                planarRunning = false
                trackProgress = null
                planarLive = null
                trackJob = null
            }
        }
    }


    // Tool "Remove" (video layer selected): menu first (start / redo / take away).
    val requestRemove: (String?) -> Unit = req@{ id ->
        if (id == null) return@req
        if (trackRunning) {
            scope.launch { snackbarHostState.showSnackbar("A track is already running") }
            return@req
        }
        removeMenuFor = id
    }

    // "Start" of the Remove menu: the 4 corners over the canvas first, then the painting (the track starts at the playhead).
    val openRemoveSetup: (String) -> Unit = openRm@{ clipId ->
        val video = tracks.flatMap { it.clips }.find { it.id == clipId } ?: return@openRm
        val uri = video.uri ?: return@openRm
        if (!com.example.engine.RemoveBuilder.isAvailable) {
            scope.launch { snackbarHostState.showSnackbar("The native engine is not available on this phone") }
            return@openRm
        }
        if (!video.isActiveAt(playheadState.value)) viewModel.seekTo(video.startMs)
        viewModel.pause()
        scope.launch {
            val a = withContext(Dispatchers.IO) { com.example.engine.MotionTrack.pictureAspect(context, uri) }
            planarQuad = com.example.ui.components.PlanarQuad()
            removePaint = com.example.ui.components.RemovePaint()
            removeStage = 0
            trackSetup = TrackSetupState(video.id, if (a > 0f) a else video.sourceAspect, video.id, null, planar = true, remove = true)
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar("Drag the 4 corners onto the flat surface around the flaw, then press Next")
        }
    }

    // "Remove" button of the painting step: follows the surface, makes the clean patch, puts it on the layer.
    val startRemove: () -> Unit = startRm@{
        val setup = trackSetup ?: return@startRm
        val video = tracks.flatMap { it.clips }.find { it.id == setup.clipId } ?: return@startRm
        val fw = previewFrameWidthPx
        if (fw <= 0f) return@startRm
        val fh = fw / selectedAspectRatio.ratioFloat
        val t = playheadState.value
        if (!video.isActiveAt(t)) {
            scope.launch { snackbarHostState.showSnackbar("Move the playhead onto the video layer") }
            return@startRm
        }
        val paint = removePaint
        if (paint.isEmpty) {
            scope.launch { snackbarHostState.showSnackbar("Paint over the thing you want to remove") }
            return@startRm
        }
        val placement = com.example.model.VideoPlacement(video.at(t), setup.aspect, fw, fh)
        val quadUv = FloatArray(8)
        for (i in 0 until 4) {
            val o = planarQuad.c[i]
            val p = placement.fromCanvas(o.x * fw, o.y * fh)
            if (p.x < 0f || p.x > 1f || p.y < 0f || p.y > 1f) {
                scope.launch { snackbarHostState.showSnackbar("Put all 4 corners on the video picture") }
                return@startRm
            }
            quadUv[2 * i] = p.x
            quadUv[2 * i + 1] = p.y
        }
        // The painted area on the video's own picture (a small grey picture, 255 = remove).
        val asp = if (setup.aspect > 0.01f) setup.aspect else 16f / 9f
        val side = com.example.engine.RemoveBuilder.MASK_SIDE
        val mw = if (asp >= 1f) side else maxOf(8, Math.round(side * asp))
        val mh = if (asp >= 1f) maxOf(8, Math.round(side / asp)) else side
        val bmp = android.graphics.Bitmap.createBitmap(mw, mh, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        val c0 = placement.fromCanvas(fw / 2f, fh / 2f)
        val c1 = placement.fromCanvas(fw / 2f + paint.brush * fw, fh / 2f)
        val rad = Math.hypot(((c1.x - c0.x) * mw).toDouble(), ((c1.y - c0.y) * mh).toDouble()).toFloat().coerceAtLeast(2f)
        val fillPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            isAntiAlias = true
            style = android.graphics.Paint.Style.FILL
        }
        val strokePaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            strokeWidth = 2f * rad
        }
        for (s in paint.strokes) {
            val pts = s.map { placement.fromCanvas(it.x * fw, it.y * fh) }
            if (pts.isEmpty()) continue
            if (pts.size == 1) {
                cv.drawCircle(pts[0].x * mw, pts[0].y * mh, rad, fillPaint)
            } else {
                val path = android.graphics.Path()
                path.moveTo(pts[0].x * mw, pts[0].y * mh)
                for (i in 1 until pts.size) path.lineTo(pts[i].x * mw, pts[i].y * mh)
                cv.drawPath(path, strokePaint)
            }
        }
        val px = IntArray(mw * mh)
        bmp.getPixels(px, 0, mw, 0, 0, mw, mh)
        bmp.recycle()
        val mask = ByteArray(mw * mh) { ((px[it] ushr 24) and 0xFF).toByte() }

        val startSrc = com.example.engine.CutoutMasks.sourceMs(video, t)
        val useFrames = removeUseFrames
        viewModel.pause()
        viewModel.beginAdjust() // one undo step for the whole job
        trackSetup = null
        trackStop.set(false)
        planarLive = null
        trackProgress = 0f
        trackRunning = true
        planarRunning = true
        removeLabel = "Tracking the surface…"
        trackJob = scope.launch {
            var lastSeek = 0L
            try {
                val rm = com.example.engine.RemoveBuilder.run(
                    context, video, startSrc, quadUv, mask, mw, mh, useFrames,
                    onProgress = { pr, label ->
                        trackProgress = pr
                        removeLabel = label
                    },
                    onLive = { lf ->
                        val tl = com.example.model.MotionTrackApply.timelineMsAt(video, lf.srcMs)
                        val pl = com.example.model.VideoPlacement(video.at(tl), lf.aspect, fw, fh)
                        val qc = FloatArray(8)
                        for (i in 0 until 4) {
                            val q = pl.toCanvas(lf.quad[2 * i], lf.quad[2 * i + 1])
                            qc[2 * i] = q.x / fw
                            qc[2 * i + 1] = q.y / fh
                        }
                        val pts = FloatArray(lf.pts.size)
                        var i = 0
                        while (i + 1 < pts.size) {
                            val q = pl.toCanvas(lf.pts[i], lf.pts[i + 1])
                            pts[i] = q.x / fw
                            pts[i + 1] = q.y / fh
                            i += 2
                        }
                        planarLive = com.example.ui.components.PlanarLive(qc, pts)
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastSeek >= 120L) {
                            lastSeek = now
                            scope.launch { viewModel.seekTo(tl) }
                        }
                    },
                    shouldStop = { trackStop.get() }
                )
                viewModel.setPlanarRemove(video.id, rm, undoStep = false)
                val secs = String.format(java.util.Locale.US, "%.1f", (rm.lastMs - rm.firstMs) / 1000f)
                val msg = if (trackStop.get()) "Stopped. The patch covers the $secs s tracked so far."
                else "Done. The patch covers $secs s."
                scope.launch { snackbarHostState.showSnackbar(msg) }
                viewModel.seekTo(t)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                scope.launch { snackbarHostState.showSnackbar("Remove failed: " + (e.message ?: e.javaClass.simpleName)) }
            } finally {
                trackRunning = false
                planarRunning = false
                trackProgress = null
                planarLive = null
                trackJob = null
                removeLabel = null
            }
        }
    }

    removeMenuFor?.let { id ->
        val hasPatch = tracks.flatMap { it.clips }.firstOrNull { it.id == id }?.planarRemove != null
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { removeMenuFor = null },
            title = { Text("Remove") },
            text = {
                Text(
                    if (hasPatch) "A clean patch covers a flaw on this layer. Make it again, or take it away."
                    else "Covers a mark, wire, text or dust on a flat surface (wall, table, poster, screen) with a clean patch that follows the camera. Not for people, moving things or bent surfaces."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    removeMenuFor = null
                    openRemoveSetup(id)
                }) { Text(if (hasPatch) "Redo" else "Start") }
            },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    if (hasPatch) {
                        androidx.compose.material3.TextButton(onClick = {
                            removeMenuFor = null
                            viewModel.setPlanarRemove(id, null)
                            scope.launch { snackbarHostState.showSnackbar("Remove taken away") }
                        }) { Text("Take away") }
                    }
                    androidx.compose.material3.TextButton(onClick = { removeMenuFor = null }) { Text("Close") }
                }
            }
        )
    }


    // Tool "Stabilize" (video layer selected): menu, then the 4 corners of the thing that should stay still, then track.
    val requestStabilize: (String?) -> Unit = req@{ id ->
        if (id == null) return@req
        if (trackRunning) {
            scope.launch { snackbarHostState.showSnackbar("A track is already running") }
            return@req
        }
        stabilizeMenuFor = id
    }

    val openStabilizeSetup: (String) -> Unit = openSt@{ clipId ->
        val video = tracks.flatMap { it.clips }.find { it.id == clipId } ?: return@openSt
        val uri = video.uri ?: return@openSt
        if (!com.example.engine.PlanarTrack.isAvailable) {
            scope.launch { snackbarHostState.showSnackbar("The native engine is not available on this phone") }
            return@openSt
        }
        if (!video.isActiveAt(playheadState.value)) viewModel.seekTo(video.startMs)
        viewModel.pause()
        scope.launch {
            val a = withContext(Dispatchers.IO) { com.example.engine.MotionTrack.pictureAspect(context, uri) }
            planarQuad = com.example.ui.components.PlanarQuad()
            trackSetup = TrackSetupState(video.id, if (a > 0f) a else video.sourceAspect, video.id, null, planar = true, stabilize = true)
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar("Drag the 4 corners onto a flat part that should stay still, then press Stabilize")
        }
    }

    /** Tracks and builds the stabilize. [strength] null = Lock surface (the marked 4 corners), else Auto (whole picture, smoothed). */
    val runStabilize: (com.example.model.TimelineClip, FloatArray, Float?, Long) -> Unit = runSt@{ video, quadUv, strength, t ->
        val fw = previewFrameWidthPx
        if (fw <= 0f) return@runSt
        val fh = fw / selectedAspectRatio.ratioFloat
        val startSrc = com.example.engine.CutoutMasks.sourceMs(video, t)
        viewModel.pause()
        viewModel.beginAdjust()
        trackSetup = null
        trackStop.set(false)
        planarLive = null
        trackProgress = 0f
        trackRunning = true
        planarRunning = true
        removeLabel = if (strength == null) "Tracking the surface…" else "Following the camera…"
        trackJob = scope.launch {
            var lastSeek = 0L
            try {
                val pin = com.example.engine.StabilizeBuilder.run(
                    context, video, startSrc, quadUv,
                    onProgress = { pr -> trackProgress = pr },
                    onLive = { lf ->
                        val tl = com.example.model.MotionTrackApply.timelineMsAt(video, lf.srcMs)
                        val pl = com.example.model.VideoPlacement(video.at(tl), lf.aspect, fw, fh)
                        val qc = FloatArray(8)
                        for (i in 0 until 4) {
                            val q = pl.toCanvas(lf.quad[2 * i], lf.quad[2 * i + 1])
                            qc[2 * i] = q.x / fw
                            qc[2 * i + 1] = q.y / fh
                        }
                        val pts = FloatArray(lf.pts.size)
                        var i = 0
                        while (i + 1 < pts.size) {
                            val q = pl.toCanvas(lf.pts[i], lf.pts[i + 1])
                            pts[i] = q.x / fw
                            pts[i + 1] = q.y / fh
                            i += 2
                        }
                        planarLive = com.example.ui.components.PlanarLive(qc, pts)
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastSeek >= 120L) {
                            lastSeek = now
                            scope.launch { viewModel.seekTo(tl) }
                        }
                    },
                    shouldStop = { trackStop.get() },
                    smoothStrength = strength
                )
                viewModel.setCornerPin(video.id, pin, undoStep = false)
                val secs = String.format(java.util.Locale.US, "%.1f", (pin.timesMs.last() - pin.timesMs.first()) / 1000f)
                scope.launch { snackbarHostState.showSnackbar((if (strength == null) "Done. Stabilized $secs s (the picture is zoomed a little to hide the edges)." else "Done. Shake removed from $secs s (the picture is zoomed a little to hide the edges).")) }
                viewModel.seekTo(t)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                scope.launch { snackbarHostState.showSnackbar("Stabilize failed: " + (e.message ?: e.javaClass.simpleName)) }
            } finally {
                trackRunning = false
                planarRunning = false
                trackProgress = null
                planarLive = null
                trackJob = null
                removeLabel = null
            }
        }
    }

    val startStabilize: () -> Unit = startSt@{
        val setup = trackSetup ?: return@startSt
        val video = tracks.flatMap { it.clips }.find { it.id == setup.clipId } ?: return@startSt
        val fw = previewFrameWidthPx
        if (fw <= 0f) return@startSt
        val fh = fw / selectedAspectRatio.ratioFloat
        val t = playheadState.value
        if (!video.isActiveAt(t)) {
            scope.launch { snackbarHostState.showSnackbar("Move the playhead onto the video layer") }
            return@startSt
        }
        val placement = com.example.model.VideoPlacement(video.at(t), setup.aspect, fw, fh)
        val quadUv = FloatArray(8)
        for (i in 0 until 4) {
            val o = planarQuad.c[i]
            val p = placement.fromCanvas(o.x * fw, o.y * fh)
            if (p.x < 0f || p.x > 1f || p.y < 0f || p.y > 1f) {
                scope.launch { snackbarHostState.showSnackbar("Put all 4 corners on the video picture") }
                return@startSt
            }
            quadUv[2 * i] = p.x
            quadUv[2 * i + 1] = p.y
        }
        runStabilize(video, quadUv, null, t)
    }

    /** Auto stabilize: no points. The whole picture is followed from the start of the layer and the shake is smoothed away. */
    val startAutoStabilize: (String) -> Unit = startAuto@{ clipId ->
        val video = tracks.flatMap { it.clips }.find { it.id == clipId } ?: return@startAuto
        if (video.uri == null) return@startAuto
        if (!com.example.engine.PlanarTrack.isAvailable) {
            scope.launch { snackbarHostState.showSnackbar("The native engine is not available on this phone") }
            return@startAuto
        }
        runStabilize(video, com.example.engine.StabilizeBuilder.FULL_FRAME_QUAD, stabilizeStrength, video.startMs)
    }

    /** Smooth slow-mo: pre-renders the slowed layer with new frames between the real ones, then swaps the layer to the new file. */
    val startSmoothSlowMo: (String) -> Unit = startSm@{ clipId ->
        val video = tracks.flatMap { it.clips }.find { it.id == clipId } ?: return@startSm
        if (video.type != LayerType.VIDEO || video.isImage || video.uri == null) return@startSm
        if (trackRunning) return@startSm
        if (!com.example.engine.SlowMoBuilder.hasSlowPart(video)) {
            scope.launch { snackbarHostState.showSnackbar("Slow the layer down first (speed below 1x)") }
            return@startSm
        }
        if (!com.example.engine.DecoderPool.isAvailable) {
            scope.launch { snackbarHostState.showSnackbar("The native engine is not available on this phone") }
            return@startSm
        }
        viewModel.pause()
        viewModel.beginAdjust()
        trackStop.set(false)
        trackProgress = 0f
        trackRunning = true
        removeLabel = "Making smooth slow-mo…"
        trackJob = scope.launch {
            try {
                val res = com.example.engine.SlowMoBuilder.render(
                    context, video,
                    onProgress = { trackProgress = it },
                    shouldStop = { trackStop.get() }
                )
                viewModel.applyBakedSlowMo(video.id, android.net.Uri.fromFile(res.file).toString(), res.durationMs)
                snackbarHostState.showSnackbar("Done. Smooth slow-mo made (${res.frames} frames).")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: com.example.engine.SlowMoBuilder.Stopped) {
                snackbarHostState.showSnackbar("Stopped")
            } catch (e: Throwable) {
                snackbarHostState.showSnackbar("Smooth slow-mo failed: ${e.message ?: "unknown error"}")
            } finally {
                trackRunning = false
                trackProgress = null
                trackJob = null
                removeLabel = null
            }
        }
    }

    /** Video Reverse: pre-renders the layer backwards, then swaps the layer to the new file (no sound, like smooth slow-mo). */
    val startVideoReverse: (String) -> Unit = startRv@{ clipId ->
        val video = tracks.flatMap { it.clips }.find { it.id == clipId } ?: return@startRv
        if (video.type != LayerType.VIDEO || video.isImage || video.uri == null) return@startRv
        if (trackRunning) return@startRv
        if (video.cutoutDir != null || video.faceBlurDir != null || video.cornerPin != null || video.planarRemove != null) {
            scope.launch { snackbarHostState.showSnackbar("Reverse the video first, then use Cutout / Face AI / Remove") }
            return@startRv
        }
        if (!com.example.engine.DecoderPool.isAvailable) {
            scope.launch { snackbarHostState.showSnackbar("The native engine is not available on this phone") }
            return@startRv
        }
        viewModel.pause()
        viewModel.beginAdjust()
        trackStop.set(false)
        trackProgress = 0f
        trackRunning = true
        removeLabel = "Reversing the video…"
        trackJob = scope.launch {
            try {
                val res = com.example.engine.VideoReverse.render(
                    context, video,
                    onProgress = { trackProgress = it },
                    shouldStop = { trackStop.get() }
                )
                viewModel.applyBakedSlowMo(video.id, android.net.Uri.fromFile(res.file).toString(), res.durationMs)
                snackbarHostState.showSnackbar("Done. The video is reversed (the new copy has no sound).")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: com.example.engine.VideoReverse.Stopped) {
                snackbarHostState.showSnackbar("Stopped")
            } catch (e: Throwable) {
                snackbarHostState.showSnackbar("Reverse failed: ${e.message ?: "unknown error"}")
            } finally {
                trackRunning = false
                trackProgress = null
                trackJob = null
                removeLabel = null
            }
        }
    }

    /** Audio Reverse: decodes the part the layer plays, turns it around, saves a new WAV and swaps the layer to it. */
    val startAudioReverse: (String) -> Unit = startAr@{ clipId ->
        val audio = tracks.flatMap { it.clips }.find { it.id == clipId } ?: return@startAr
        if (audio.type != LayerType.AUDIO || audio.uri == null) return@startAr
        if (trackRunning) return@startAr
        if (com.example.engine.AudioReverse.hasSpeedRamp(audio)) {
            scope.launch { snackbarHostState.showSnackbar("Remove the speed ramp (keyframes) first, then reverse") }
            return@startAr
        }
        viewModel.pause()
        viewModel.beginAdjust()
        trackStop.set(false)
        trackProgress = 0f
        trackRunning = true
        removeLabel = "Reversing the sound…"
        trackJob = scope.launch {
            try {
                val res = com.example.engine.AudioReverse.render(
                    context, audio,
                    onProgress = { trackProgress = it },
                    shouldStop = { trackStop.get() }
                )
                val newUri = android.net.Uri.fromFile(res.file).toString()
                viewModel.applyBakedAudioReverse(audio.id, newUri, res.durationMs)
                viewModel.requestWaveform(context, newUri, res.durationMs)
                snackbarHostState.showSnackbar("Done. The sound is reversed.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: com.example.engine.AudioReverse.Stopped) {
                snackbarHostState.showSnackbar("Stopped")
            } catch (e: Throwable) {
                snackbarHostState.showSnackbar("Reverse failed: ${e.message ?: "unknown error"}")
            } finally {
                trackRunning = false
                trackProgress = null
                trackJob = null
                removeLabel = null
            }
        }
    }

    var renameTargetId by remember { mutableStateOf<String?>(null) }
    var transitionTargetId by remember { mutableStateOf<String?>(null) }

    /** Freeze frame: the picture of the video layer at the playhead becomes a 2 s photo layer; the video goes on after it. */
    val startFreezeFrame: (String) -> Unit = startFz@{ clipId ->
        val video = tracks.flatMap { it.clips }.find { it.id == clipId } ?: return@startFz
        val srcUri = video.uri
        if (video.type != LayerType.VIDEO || video.isImage || srcUri == null) return@startFz
        val t = playheadState.value
        if (!video.isActiveAt(t)) {
            scope.launch { snackbarHostState.showSnackbar("Move the playhead onto the video first") }
            return@startFz
        }
        val srcUs = (video.trimStartMs + video.sourceOffsetMs(t - video.startMs)) * 1000L
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val r = android.media.MediaMetadataRetriever()
                    try {
                        r.setDataSource(context, android.net.Uri.parse(srcUri))
                        val bmp = r.getFrameAtTime(srcUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
                            ?: r.getFrameAtTime(srcUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?: error("Could not read the picture")
                        com.example.engine.CutoutEngine.savePng(context, bmp)
                    } finally {
                        runCatching { r.release() }
                    }
                }
            }
            val stillUri = saved.getOrNull()
            snackbarHostState.currentSnackbarData?.dismiss()
            if (stillUri == null) {
                snackbarHostState.showSnackbar("Freeze failed: " + (saved.exceptionOrNull()?.message ?: "could not read the picture"))
            } else if (!viewModel.insertFreezeFrame(video.id, stillUri, 2000L)) {
                snackbarHostState.showSnackbar("Could not freeze here")
            } else {
                snackbarHostState.showSnackbar("Freeze frame added (2 s). Change its length like any layer.")
            }
        }
    }

    // ---- Face swap (light): the face of a photo onto the face in a video layer. Asks for consent first. -----------------------
    var askFaceSwap by remember { mutableStateOf<com.example.model.TimelineClip?>(null) }
    var swapConsent by remember { mutableStateOf(false) }
    var swapPhoto by remember { mutableStateOf<android.net.Uri?>(null) }
    val swapPhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) swapPhoto = uri
    }
    val requestFaceSwap: (String?) -> Unit = reqSw@{ id ->
        val clip = tracks.flatMap { it.clips }.find { it.id == id } ?: return@reqSw
        snackbarHostState.currentSnackbarData?.dismiss()
        if (clip.type != LayerType.VIDEO || clip.isImage || clip.uri == null) {
            scope.launch { snackbarHostState.showSnackbar("Face swap works on video layers") }
        } else if (faceProgress != null) {
            scope.launch { snackbarHostState.showSnackbar("Face AI is already running") }
        } else if (!com.example.engine.DecoderPool.isAvailable) {
            scope.launch { snackbarHostState.showSnackbar("The native engine is not available on this phone") }
        } else {
            swapConsent = false
            swapPhoto = null
            askFaceSwap = clip
        }
    }
    val runFaceSwap: (com.example.model.TimelineClip, android.net.Uri) -> Unit = { target, photo ->
        viewModel.pause()
        faceProgress = 0f
        faceLabel = "Swapping the face…"
        faceJob = scope.launch {
            try {
                if (!com.example.engine.FaceModelDownloader.isDownloaded(context)) {
                    faceLabel = "Downloading face model…"
                    withContext(Dispatchers.IO) {
                        com.example.engine.FaceModelDownloader.download(context) { p -> faceProgress = p }
                    }
                    faceProgress = 0f
                    faceLabel = "Swapping the face…"
                }
                val res = com.example.engine.FaceSwapBuilder.render(context, target, photo) { p -> faceProgress = p }
                viewModel.beginAdjust()
                viewModel.applyBakedSlowMo(target.id, android.net.Uri.fromFile(res.file).toString(), res.durationMs)
                faceProgress = null
                val pct = (100f * res.swapped / res.frames.coerceAtLeast(1)).toInt().coerceIn(0, 100)
                snackbarHostState.showSnackbar("Face swap done · face found in $pct% of the frames · file marked AI-edited")
            } catch (e: kotlinx.coroutines.CancellationException) {
                faceProgress = null
                scope.launch { snackbarHostState.showSnackbar("Face swap cancelled") }
                throw e
            } catch (e: Throwable) {
                faceProgress = null
                snackbarHostState.showSnackbar("Face swap failed: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                faceProgress = null
                faceJob = null
            }
        }
    }
    renameTargetId?.let { id ->
        val named = tracks.flatMap { it.clips }.find { it.id == id }
        if (named != null) {
            com.example.ui.components.RenameLayerDialog(
                initial = named.name,
                onConfirm = { newName ->
                    viewModel.renameClip(id, newName)
                    renameTargetId = null
                },
                onDismiss = { renameTargetId = null }
            )
        }
    }
    transitionTargetId?.let { id ->
        val trCanvasW = if (previewFrameWidthPx > 1f) previewFrameWidthPx else 1000f
        val trCanvasH = trCanvasW / selectedAspectRatio.ratioFloat.coerceAtLeast(0.1f)
        com.example.ui.components.TransitionDialog(
            onApply = { presetId, dir, ms ->
                transitionTargetId = null
                val ok = viewModel.applyClipTransition(id, presetId, dir, ms, trCanvasW, trCanvasH)
                scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(if (ok) "Transition added" else "This layer cannot take a transition")
                }
            },
            onRemove = {
                transitionTargetId = null
                viewModel.beginAdjust()
                viewModel.setClipAnim(com.example.model.AnimSlot.IN, null, com.example.model.AnimDir.LEFT, 0L, null, trCanvasW, trCanvasH)
            },
            onDismiss = { transitionTargetId = null }
        )
    }
    askFaceSwap?.let { pending ->
        val modelReady = com.example.engine.FaceModelDownloader.isDownloaded(context)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askFaceSwap = null },
            title = { Text("Face swap") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Puts the face from a photo onto the face in this video layer (the biggest face at the start). " +
                            "Light version: best with a front-facing photo and a face that does not turn far away. " +
                            "The layer is re-made as a new video without sound, marked AI-edited. Undo brings the old one back." +
                            if (modelReady) "" else " Needs a one-time download of the face model (" +
                                com.example.engine.FaceModelDownloader.APPROX_SIZE_TEXT + ").",
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.TextButton(onClick = {
                        swapPhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text(if (swapPhoto == null) "Choose the photo" else "Photo chosen · change") }
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.layout.Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { swapConsent = !swapConsent },
                        verticalAlignment = Alignment.Top
                    ) {
                        androidx.compose.material3.Checkbox(checked = swapConsent, onCheckedChange = { swapConsent = it })
                        Text(
                            "The face in the photo and the face in the video are mine, or those people agreed. " +
                                "I will not use this to fool, harass or harm anyone.",
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = swapConsent && swapPhoto != null,
                    onClick = {
                        val photo = swapPhoto ?: return@TextButton
                        askFaceSwap = null
                        runFaceSwap(pending, photo)
                    }
                ) { Text("Make") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { askFaceSwap = null }) { Text("Cancel") }
            }
        )
    }

    stabilizeMenuFor?.let { id ->
        val clip = tracks.flatMap { it.clips }.firstOrNull { it.id == id }
        val has = clip != null && com.example.engine.StabilizeBuilder.isStabilize(clip)
        val followerPin = clip?.cornerPin != null && !has
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { stabilizeMenuFor = null },
            title = { Text("Stabilize") },
            text = {
                androidx.compose.foundation.layout.Column {
                Text(
                    if (followerPin) "This layer is pinned on another video. Take that pin away first."
                    else if (has) "This layer is stabilized. Make it again, or take it away."
                    else "Auto: removes the shake of the whole video, no points to mark. The slider sets how steady it gets (right end = tripod). Lock surface: holds a flat part (wall, table, screen) exactly still; you mark its 4 corners."
                )
                    if (!followerPin) {
                        Text(
                            "Steadiness: " + (if (stabilizeStrength >= 0.995f) "tripod" else String.format(java.util.Locale.US, "%.1f s", com.example.engine.StabilizeBuilder.sigmaSeconds(stabilizeStrength))),
                            modifier = androidx.compose.ui.Modifier.padding(top = 12.dp)
                        )
                        androidx.compose.material3.Slider(value = stabilizeStrength, onValueChange = { stabilizeStrength = it })
                    }
                }
            },
            confirmButton = {
                if (!followerPin) {
                    androidx.compose.foundation.layout.Row {
                        androidx.compose.material3.TextButton(onClick = {
                            stabilizeMenuFor = null
                            openStabilizeSetup(id)
                        }) { Text("Lock") }
                        androidx.compose.material3.TextButton(onClick = {
                            stabilizeMenuFor = null
                            startAutoStabilize(id)
                        }) { Text("Auto") }
                    }
                }
            },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    if (has) {
                        androidx.compose.material3.TextButton(onClick = {
                            stabilizeMenuFor = null
                            viewModel.setCornerPin(id, null)
                            scope.launch { snackbarHostState.showSnackbar("Stabilize taken away") }
                        }) { Text("Remove") }
                    }
                    androidx.compose.material3.TextButton(onClick = { stabilizeMenuFor = null }) { Text("Close") }
                }
            }
        )
    }

    trackPickFor?.let { videoId ->
        val targets = tracks.flatMap { it.clips }
            .filter { it.id != videoId && it.type != LayerType.AUDIO }
            .map {
                TrackTarget(
                    it.id, it.type.displayName + ": " + (it.text?.take(24)?.ifBlank { null } ?: it.name),
                    canPin = it.type == LayerType.VIDEO && it.uri != null,
                    pinned = it.cornerPin != null
                )
            }
        TrackLayerPickDialog(
            targets = targets,
            onPick = { id ->
                trackPickFor = null
                openTrackSetup(videoId, id, null, false)
            },
            onDismiss = { trackPickFor = null },
            onPickCamera = {
                trackPickFor = null
                openTrackSetup(videoId, videoId, null, true)
            },
            onRemoveCamera = if (tracks.flatMap { it.clips }.find { it.id == videoId }?.trackFollow != null) {
                {
                    trackPickFor = null
                    viewModel.setTrackFollow(videoId, null)
                    scope.launch { snackbarHostState.showSnackbar("Camera track removed") }
                }
            } else null,
            onPickPin = { id ->
                trackPickFor = null
                openPlanarSetup(videoId, id)
            },
            onRemovePin = { id ->
                trackPickFor = null
                viewModel.setCornerPin(id, null)
                scope.launch { snackbarHostState.showSnackbar("Corner pin removed") }
            }
        )
    }

    // v0.1 only supports main-track editing; everything else shows this message.
    val comingSoon: () -> Unit = {
        snackbarHostState.currentSnackbarData?.dismiss()
        scope.launch { snackbarHostState.showSnackbar("Coming in a later version") }
    }

    // ---- Real engine: preview player, scrub frame cache, exporter, media picker ----
    val exporter = remember { VideoExporter(context) }
    // The preview: the C++ engine draws the pictures (GL) and plays the sound (AAudio). No Media3 in the preview any more.
    val nativePreview = remember { com.example.engine.NativePreviewEngine(context) }
    val nativeAudio = remember { com.example.engine.NativeAudioPreview(context) }
    DisposableEffect(Unit) {
        onDispose {
            exporter.cancel()
            nativeAudio.release()
            nativePreview.release()
        }
    }

    // ---- Projects: the last project is restored at start, every change is autosaved, the X button opens the list ----
    val projectStore = remember { com.example.engine.ProjectStore(context) }
    val projectId by viewModel.projectId.collectAsState()
    var showProjects by remember { mutableStateOf(false) }
    var projectList by remember { mutableStateOf(emptyList<com.example.model.ProjectMeta>()) }

    fun reportMissingMedia(snap: com.example.model.ProjectSnapshot) {
        val missing = com.example.engine.ProjectFiles.missingMedia(snap.tracks, context.filesDir)
        if (missing.isNotEmpty()) {
            val more = if (missing.size > 3) " +" + (missing.size - 3) else ""
            android.widget.Toast.makeText(
                context, "Media missing: " + missing.take(3).joinToString() + more, android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    fun saveProjectNow() {
        val id = viewModel.projectId.value ?: return
        runCatching { projectStore.save(id, viewModel.snapshot()) }
    }

    // Restore once (a rotation keeps the ViewModel, so its project id is already set then).
    LaunchedEffect(Unit) {
        if (viewModel.projectId.value == null) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { projectStore.openLastOrCreate() }.getOrNull()
            }
            if (loaded != null) {
                viewModel.loadProject(loaded.second, loaded.first)
                withContext(Dispatchers.IO) { projectStore.lastId = loaded.first }
                reportMissingMedia(loaded.second)
            }
        }
    }

    // Autosave one second after the last change. It only starts after a project is open, so a failed restore
    // can never overwrite a saved project with an empty one.
    LaunchedEffect(projectId) {
        if (projectId == null) return@LaunchedEffect
        merge(
            viewModel.tracks.map { 0 },
            viewModel.selectedAspectRatio.map { 0 },
            viewModel.canvasColor.map { 0 },
            viewModel.exportSettings.map { 0 },
            viewModel.selectedResolution.map { 0 },
            viewModel.isMasterMuted.map { 0 },
            viewModel.beatMarks.map { 0 }
        ).debounce(1000L).collect {
            val id = viewModel.projectId.value ?: return@collect
            val snap = viewModel.snapshot()
            withContext(Dispatchers.IO) { runCatching { projectStore.save(id, snap) } }
        }
    }

    // Project file (.ampro): export one project with its media, import it on this or another phone.
    var projectBusy by remember { mutableStateOf<String?>(null) }
    var projectBusyProgress by remember { mutableStateOf(-1f) }
    var pendingExportId by remember { mutableStateOf<String?>(null) }

    val exportProjectLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { target: android.net.Uri? ->
        val id = pendingExportId
        pendingExportId = null
        if (target != null && id != null) {
            scope.launch {
                projectBusy = "Exporting project..."
                projectBusyProgress = 0f
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        if (id == viewModel.projectId.value) projectStore.save(id, viewModel.snapshot())
                        val snap = projectStore.load(id) ?: error("The project cannot be read")
                        val name = projectStore.list().firstOrNull { it.id == id }?.name ?: "Project"
                        val out = context.contentResolver.openOutputStream(target) ?: error("Cannot write there")
                        out.use { o ->
                            com.example.engine.ProjectArchive.export(
                                snap, name, com.example.BuildConfig.VERSION_NAME, context.filesDir, o
                            ) { p -> projectBusyProgress = p }
                        }
                    }
                }
                projectBusy = null
                val report = result.getOrNull()
                val msg = if (report != null) {
                    "Project saved (" + report.assetCount + " files)" +
                        if (report.notPacked.isNotEmpty()) ". Not included: " + report.notPacked.take(3).joinToString() else ""
                } else {
                    "Export failed: " + (result.exceptionOrNull()?.message ?: "unknown error")
                }
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    val importProjectLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { source: android.net.Uri? ->
        if (source != null) {
            scope.launch {
                projectBusy = "Importing project..."
                projectBusyProgress = -1f
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val input = context.contentResolver.openInputStream(source) ?: error("Cannot read that file")
                        val buffered = java.io.BufferedInputStream(input)
                        // One Import button for both: an Alight Motion XML scene, or an .ampro project file.
                        buffered.use { b ->
                            b.mark(4096)
                            val head = ByteArray(4096)
                            val n = b.read(head).coerceAtLeast(0)
                            b.reset()
                            if (com.example.engine.AlightXmlImporter.looksLikeAlightXml(head.copyOf(n))) {
                                val x = com.example.engine.AlightXmlImporter.parse(
                                    b, com.example.engine.PreviewFrameRef.widthPx
                                )
                                val meta = projectStore.create(x.name, x.snapshot)
                                Triple(
                                    meta, x.snapshot,
                                    if (x.warnings.isNotEmpty()) ". Notes: " + x.warnings.take(3).joinToString("; ") else ""
                                )
                            } else {
                                val r = com.example.engine.ProjectArchive.import(b, context.filesDir) { f ->
                                    androidx.core.content.FileProvider.getUriForFile(
                                        context, context.packageName + ".fileprovider", f
                                    ).toString()
                                }
                                val meta = projectStore.create(r.name, r.snapshot)
                                Triple(
                                    meta, r.snapshot,
                                    if (r.missing.isNotEmpty()) ". Media missing: " + r.missing.take(3).joinToString() else ""
                                )
                            }
                        }
                    }
                }
                projectBusy = null
                val ok = result.getOrNull()
                if (ok != null) {
                    saveProjectNow()
                    viewModel.loadProject(ok.second, ok.first.id)
                    withContext(Dispatchers.IO) { projectStore.lastId = ok.first.id }
                    showProjects = false
                    android.widget.Toast.makeText(context, "Project imported" + ok.third, android.widget.Toast.LENGTH_LONG).show()
                } else {
                    android.widget.Toast.makeText(
                        context, "Import failed: " + (result.exceptionOrNull()?.message ?: "unknown error"),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    LaunchedEffect(showProjects) {
        if (showProjects) {
            saveProjectNow()
            projectList = withContext(Dispatchers.IO) { runCatching { projectStore.list() }.getOrDefault(emptyList()) }
        }
    }

    // Leaving the app stops playback (video and audio layers) and saves the project.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                viewModel.pause()
                saveProjectNow()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Audio layers follow the playhead. While playing they are synced 10x/s (not per frame);
    // while scrubbing the sync is debounced so swiping stays fluid.
    LaunchedEffect(tracks, isPlaying, isMasterMuted) {
        if (isPlaying) {
            while (isActive) {
                nativeAudio.sync(tracks, viewModel.currentPlayheadMs.value, true, isMasterMuted)
                delay(100L)
            }
        } else {
            viewModel.currentPlayheadMs.collectLatest { ms ->
                delay(100L)
                nativeAudio.sync(tracks, ms, false, isMasterMuted)
            }
        }
    }
    // C++ preview: a new picture whenever the playhead or the layers change (requests are coalesced).
    LaunchedEffect(tracks) {
        viewModel.currentPlayheadMs.collect { nativePreview.requestFrame() }
    }
    // Decode the waveform of every audio file and of every video's own sound once, in the
    // background (a video without an audio track just gets an empty waveform = nothing drawn).
    LaunchedEffect(tracks) {
        tracks.flatMap { it.clips }
            .filter { it.uri != null && (it.type == LayerType.AUDIO || (it.type == LayerType.VIDEO && !it.isImage)) }
            .forEach {
                val dur = maxOf(it.sourceDurationMs, it.trimStartMs + (it.durationMs * it.speed).toLong())
                viewModel.requestWaveform(context.applicationContext, it.uri!!, dur)
            }
    }

    remember { com.example.engine.FillImages.init(context) }

    // Image fill: the picture picked in the Color editor is stored on the layer being edited.
    var editingColorIdForPicker by remember { mutableStateOf<String?>(null) }
    val fillImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val id = editingColorIdForPicker
        if (uri != null && id != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.updateClip(id) {
                it.copy(fillMode = com.example.model.FILL_IMAGE, fillImageUri = uri.toString())
            }
        }
    }

    // Replace: pick a new picture/video for the selected layer.
    val replacePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val items = MediaImporter.read(context, listOf(uri))
                if (items.isEmpty()) {
                    snackbarHostState.showSnackbar("Could not read the selected file")
                } else {
                    viewModel.replaceSelectedClipWithMedia(items.first())
                }
            }
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
                }
            }
        }
    }

    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { u ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        u, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            scope.launch {
                val items = MediaImporter.read(context, uris)
                if (items.isEmpty()) {
                    snackbarHostState.showSnackbar("Could not read the selected audio")
                } else {
                    viewModel.importAudio(items)
                }
            }
        }
    }

    // Dialog & Sheet States
    var showAspectRatioSheet by remember { mutableStateOf(false) }
    var showAddLayerSheet by remember { mutableStateOf(false) }
    // Parent picker (Null object): the layer it is open for, null = closed.
    var parentSheetClipId by remember { mutableStateOf<String?>(null) }
    var showResolutionSheet by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportDone by remember { mutableStateOf(false) }
    var exportedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var timelinePxPerMs by remember { mutableFloatStateOf(TIMELINE_PX_PER_MS) }
    var showFullscreenPlayer by remember { mutableStateOf(false) }
    var showAudioVolumeSheet by remember { mutableStateOf(false) }
    var showAudioFadeSheet by remember { mutableStateOf(false) }
    var showAudioSpeedSheet by remember { mutableStateOf(false) }
    var showAddTextSheet by remember { mutableStateOf(false) }
    var showAddShapeSheet by remember { mutableStateOf(false) }
    var showStickerSheet by remember { mutableStateOf(false) }
    var showStickerSpeedSheet by remember { mutableStateOf(false) }
    var showEditShapeMode by remember { mutableStateOf(false) }
    var showClipEffectsSheet by remember { mutableStateOf(false) }
    // Masks panel: open flag, selected mask, selected pen point, and the points of a pen path being drawn (null = not drawing).
    var showClipMasksSheet by remember { mutableStateOf(false) }
    var maskSelectedId by remember { mutableStateOf<String?>(null) }
    var maskSelPoint by remember { mutableStateOf(-1) }
    var maskPenPoints by remember { mutableStateOf<List<Pair<Float, Float>>?>(null) }
    var showClipAnimationsSheet by remember { mutableStateOf(false) }
    var animPreviewJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var showClipReplaceSheet by remember { mutableStateOf(false) }
    var showClipBlendSheet by remember { mutableStateOf(false) }
    var showClipCropSheet by remember { mutableStateOf(false) }
    var showAppliedEffectsMode by remember { mutableStateOf(false) }
    var showTransformMode by remember { mutableStateOf(false) }

    // Play / pause: the view model's wall clock drives the playhead (see VideoEditorViewModel.play).
    // Full-screen editors (Shape / Crop) show the media layers themselves.
    val editorMediaLayers = remember(tracks, playheadState.value) {
        val t = playheadState.value
        tracks.asReversed().flatMap { it.clips }
            .filter { it.type == LayerType.VIDEO && it.isVisible && it.uri != null && it.isActiveAt(t) }
    }
    // Text edit: id of the text layer that is being edited (null = closed).
    var editingTextId by remember { mutableStateOf<String?>(null) }
    // Color (fill) editor: id of the shape / text layer whose fill is being edited (null = closed).
    var editingColorId by remember { mutableStateOf<String?>(null) }
    // Blending editor (opacity + blend mode): id of the layer being edited (null = closed).
    var editingBlendId by remember { mutableStateOf<String?>(null) }
    // Eyedropper: while set, a tap on the canvas picks a color and passes it to this callback.
    var eyedropperCallback by remember { mutableStateOf<((Long) -> Unit)?>(null) }
    // Chroma Key eyedropper: what to do when the pick is abandoned (puts the effect back the way it was).
    var eyedropperCancel by remember { mutableStateOf<(() -> Unit)?>(null) }

    // System back / gesture back closes Transform first instead of leaving the editor.
    BackHandler(enabled = showTransformMode) { showTransformMode = false }
    // Back leaves multi-select first.
    BackHandler(enabled = multiSelectIds.isNotEmpty() && !showTransformMode && editingTextId == null && editingColorId == null && editingBlendId == null) {
        viewModel.exitMultiSelect()
    }
    BackHandler(enabled = editingBlendId != null) { editingBlendId = null }
    BackHandler(enabled = editingTextId != null) {
        if (eyedropperCallback != null) eyedropperCallback = null else editingTextId = null
    }
    BackHandler(enabled = editingColorId != null) {
        if (eyedropperCallback != null) eyedropperCallback = null else editingColorId = null
    }

    // Buddy (the app's companion, ui/buddy) reacts to what happens here.
    run {
        val clipCount = tracks.sumOf { it.clips.size }
        var prevCount by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
        LaunchedEffect(clipCount) {
            if (prevCount >= 0) {
                if (clipCount > prevCount) com.example.ui.buddy.BuddyBrain.react(com.example.ui.buddy.BuddyEvent.CLIP_ADDED)
                else if (clipCount < prevCount) com.example.ui.buddy.BuddyBrain.react(com.example.ui.buddy.BuddyEvent.CLIP_REMOVED)
            }
            prevCount = clipCount
        }
        LaunchedEffect(selectedClipId) {
            if (selectedClipId != null) com.example.ui.buddy.BuddyBrain.react(com.example.ui.buddy.BuddyEvent.LAYER_SELECTED)
        }
        LaunchedEffect(isPlaying) { com.example.ui.buddy.BuddyBrain.setPlaying(isPlaying) }
        val busyNow = isExporting || cutoutProgress != null || captionStatus != null || faceProgress != null || trackProgress != null
        LaunchedEffect(busyNow) {
            com.example.ui.buddy.BuddyBrain.setBusy(busyNow, if (isExporting) "Rendering your video…" else "Working on it…")
        }
        LaunchedEffect(exportDone) {
            if (exportDone) com.example.ui.buddy.BuddyBrain.react(com.example.ui.buddy.BuddyEvent.EXPORT_DONE)
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        com.example.ui.theme.LocalCanvasColor provides androidx.compose.ui.graphics.Color(canvasColor)
    ) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(EditorBackground)
            .statusBarsPadding()
            .navigationBarsPadding(),
        snackbarHost = {
            androidx.compose.foundation.layout.Column {
                cutoutProgress?.let { p ->
                    com.example.ui.components.CutoutProgressBar(p, cutoutLabel, onCancel = { cutoutJob?.cancel() })
                }
                captionStatus?.let { st ->
                    com.example.ui.components.CutoutProgressBar(st.progress, st.label, onCancel = { captionJob?.cancel() })
                }
                faceProgress?.let { p ->
                    com.example.ui.components.CutoutProgressBar(p, faceLabel, onCancel = { faceJob?.cancel() })
                }
                trackProgress?.let { p ->
                    com.example.ui.components.CutoutProgressBar(
                        p, removeLabel ?: if (planarRunning) "Tracking the surface…" else "Tracking the object…", onCancel = { trackStop.set(true) }, actionLabel = "Stop"
                    )
                }
                SnackbarHost(snackbarHostState)
            }
        },
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

            // Alight-style editor panels (Transform / Color / Blending): the play-controls card stays
            // exactly where it is. The panel starts right under it, at the timescale, so the selected
            // layer's strip takes the first layer row and the other layers are hidden behind the panel.
            val panelTop = headerH + canvasH + playControlsH
            val panelH = (totalHeight - panelTop).coerceAtLeast(200.dp)

            // The selected layer, drawn by the real timeline panel (identical look) as the first row.
            val layerRow: @Composable (String) -> Unit = { id ->
                com.example.ui.components.SelectedLayerRow(
                    clipId = id,
                    tracks = tracks,
                    playheadMs = playheadProvider,
                    totalDurationMs = totalDurationMs,
                    onSeekTo = { viewModel.seekTo(it) },
                    onScrubStart = { viewModel.pause() },
                    isPlaying = isPlaying,
                    onMoveClipInTime = { cid, delta -> viewModel.moveClipInTime(cid, delta) },
                    onBeginClipTimeDrag = { viewModel.beginAdjust() },
                    onTrimClipEdge = { cid, left, d -> viewModel.trimClipEdge(cid, left, d) },
                    onToggleClipVisibility = { cid -> viewModel.toggleClipVisibility(cid) },
                    pxPerMs = timelinePxPerMs,
                    onPxPerMsChange = { timelinePxPerMs = it },
                    waveforms = waveforms
                )
            }
            var transformResetTick by remember { mutableStateOf(0) }

            Column(modifier = Modifier.fillMaxSize()) {
                // 1. Header (1/18 of screen). While a layer is selected it shows that layer (name, type, time) instead.
                val headerClip = selectedClipId?.let { sid -> tracks.flatMap { it.clips }.find { it.id == sid } }
                if (headerClip != null) {
                    fun fmt(ms: Long): String { val t = ms.coerceAtLeast(0L); return "%d:%02d.%d".format(t / 60000, (t / 1000) % 60, (t % 1000) / 100) }
                    val parentName = headerClip.parent?.let { l -> tracks.flatMap { it.clips }.find { it.id == l.id }?.name }
                    val info = buildString {
                        append(headerClip.type.displayName)
                        append(" · ").append(fmt(headerClip.startMs)).append("–").append(fmt(headerClip.endMs))
                        append(" (").append("%.1f".format(headerClip.durationMs / 1000f)).append("s)")
                        if (parentName != null) append(" · Parent: ").append(parentName)
                        if (!headerClip.isVisible) append(" · Hidden")
                    }
                    com.example.ui.components.LayerHeader(
                        height = headerH,
                        name = headerClip.name,
                        info = info,
                        colorArgb = headerClip.color,
                        onBack = { viewModel.selectClip(null) },
                        onRename = { renameTargetId = headerClip.id },
                        visible = headerClip.isVisible,
                        onToggleVisible = { viewModel.toggleClipVisibility(headerClip.id) }
                    )
                } else EditorHeader(
                    height = headerH,
                    aspectRatio = selectedAspectRatio,
                    onBackClick = { showProjects = true },
                    onAspectRatioClick = { showAspectRatioSheet = true },
                    onSaveFrameClick = {
                        // current canvas frame -> PNG in Pictures/After Motion
                        nativePreview.captureFrame { bmp ->
                            if (bmp == null) {
                                scope.launch { snackbarHostState.showSnackbar("Could not read the frame") }
                            } else {
                                scope.launch {
                                    val uri = withContext(Dispatchers.IO) {
                                        try { com.example.engine.FrameSaver.savePng(context, bmp) } finally { bmp.recycle() }
                                    }
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    snackbarHostState.showSnackbar(
                                        if (uri != null) "Frame saved (Pictures/After Motion)" else "Could not save the frame"
                                    )
                                }
                            }
                        }
                    },
                    resolutionLabel = exportSettings.resolution.substringBefore(' ').uppercase(),
                    onResolutionClick = { showAspectRatioSheet = true },
                    onExportClick = {
                        // Fresh state every time: a previous export must not leak into this one.
                        exportDone = false
                        exportedUri = null
                        viewModel.setExporting(false)
                        viewModel.setExportProgress(0f)
                        showExportDialog = true
                    }
                )

                // 2. Canvas Area (9/18 of screen)
                val trackOverlay: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? =
                    if (trackSetup != null) {
                        {
                            if (trackSetup?.remove == true) {
                                if (removeStage == 0) {
                                    PlanarSetupOverlay(
                                        quad = planarQuad,
                                        onQuad = { planarQuad = it },
                                        onStart = { removeStage = 1 },
                                        onCancel = { trackSetup = null },
                                        startLabel = "Next",
                                        hint = "Drag the 4 corners onto the flat surface around the flaw"
                                    )
                                } else {
                                    RemoveMaskOverlay(
                                        paint = removePaint,
                                        onPaint = { removePaint = it },
                                        quad = planarQuad,
                                        useFrames = removeUseFrames,
                                        onUseFrames = { removeUseFrames = it },
                                        onBack = { removeStage = 0 },
                                        onStart = startRemove,
                                        onCancel = { trackSetup = null }
                                    )
                                }
                            } else if (trackSetup?.stabilize == true) {
                                PlanarSetupOverlay(
                                    quad = planarQuad,
                                    onQuad = { planarQuad = it },
                                    onStart = startStabilize,
                                    onCancel = { trackSetup = null },
                                    startLabel = "Stabilize",
                                    hint = "Drag the 4 corners onto a flat part that should stay still"
                                )
                            } else if (trackSetup?.planar == true) {
                                PlanarSetupOverlay(
                                    quad = planarQuad,
                                    onQuad = { planarQuad = it },
                                    onStart = startPlanarTracking,
                                    onCancel = { trackSetup = null }
                                )
                            } else {
                                TrackSetupOverlay(
                                    circle = trackCircle,
                                    onCircle = { trackCircle = it },
                                    onStart = startTracking,
                                    onCancel = { trackSetup = null },
                                    followRotation = trackFollowRot,
                                    onFollowRotation = { trackFollowRot = it },
                                    followScale = trackFollowScale,
                                    onFollowScale = { trackFollowScale = it }
                                )
                            }
                        }
                    } else if (trackRunning) {
                        {
                            if (planarRunning) PlanarRunOverlay(planarLive) else TrackRunOverlay(trackLive)
                        }
                    } else null
                val maskEditClip = if (showClipMasksSheet) tracks.flatMap { it.clips }.firstOrNull { it.id == selectedClipId } else null
                val canvasOverlay: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? =
                    if (maskEditClip != null) {
                        {
                            val t = playheadState.value
                            com.example.ui.components.MaskEditOverlay(
                                clip = com.example.engine.NativeRenderAt.renderAt(maskEditClip, t, t),
                                playheadMs = t,
                                selectedId = maskSelectedId,
                                selectedPoint = maskSelPoint,
                                onSelectPoint = { maskSelPoint = it },
                                penPoints = maskPenPoints,
                                onPenAdd = { x, y ->
                                    val cur = maskPenPoints
                                    if (cur != null && cur.size < com.example.model.ClipMaskLibrary.MAX_POINTS) {
                                        maskPenPoints = cur + (x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f))
                                    }
                                },
                                onMaskChange = { nm ->
                                    val list = tracks.flatMap { it.clips }.firstOrNull { it.id == maskEditClip.id }?.masks.orEmpty()
                                    viewModel.setClipMasks(maskEditClip.id, list.map { if (it.id == nm.id) nm else it })
                                }
                            )
                        }
                    } else trackOverlay
                CanvasArea(
                    nativePreview = nativePreview,
                    selectionHidden = showClipMasksSheet,
                    height = canvasH,
                    aspectRatio = selectedAspectRatio,
                    playheadMs = playheadProvider,
                    isPlaying = isPlaying,
                    tracks = shownTracks,
                    nativeTracks = tracks,
                    selectedClipId = selectedClipId,
                    onTogglePlayPause = { viewModel.togglePlayPause() },
                    onUpdateClipTransform = { id, x, y -> viewModel.updateClipTransform(id, x, y) },
                    onClipSelect = { viewModel.selectClip(it) },
                    onTextDoubleTap = { id ->
                        viewModel.selectClip(id)
                        viewModel.beginTextEdit()
                        editingTextId = id
                    },
                    onPreviewFrame = { w, d -> previewFrameWidthPx = w; previewDensity = d },
                    frameOverlay = canvasOverlay,
                    onSelectionBegin = { viewModel.beginAdjust() },
                    onSelectionTransform = { id, sc, rot ->
                        viewModel.setScaleRotation(id, sc, rot)
                    },
                    gradientEditId = editingColorId,
                    onGradientPoints = { id, x1, y1, x2, y2 ->
                        viewModel.updateClip(id) {
                            it.copy(gradX1 = x1, gradY1 = y1, gradX2 = x2, gradY2 = y2)
                        }
                    }
                )

                // 3. Play & Full Screen Section (1/18 of screen) — always visible (universal)
                PlayControlsSection(
                    height = playControlsH,
                    isPlaying = isPlaying,
                    canUndo = canUndo,
                    canRedo = canRedo,
                    onFullScreenClick = { showFullscreenPlayer = true },
                    onResolutionClick = { showResolutionSheet = true },
                    resolutionLabel = when (selectedResolution) {
                        com.example.model.PreviewResolution.FULL -> "Full"
                        com.example.model.PreviewResolution.HALF -> "1/2"
                        com.example.model.PreviewResolution.QUARTER -> "1/4"
                    },
                    onGridClick = { showAspectRatioSheet = true },
                    onBeatMarkClick = {
                        val added = viewModel.toggleBeatMark(playheadState.value)
                        snackbarHostState.currentSnackbarData?.dismiss()
                        scope.launch { snackbarHostState.showSnackbar(if (added) "Beat mark added" else "Beat mark removed") }
                    },
                    onPreviousClick = { viewModel.jumpPrevious() },
                    onPlayPauseClick = { viewModel.togglePlayPause() },
                    onNextClick = { viewModel.jumpNext() },
                    onUndoClick = { viewModel.undo(); com.example.ui.buddy.BuddyBrain.react(com.example.ui.buddy.BuddyEvent.UNDO) },
                    onRedoClick = { viewModel.redo(); com.example.ui.buddy.BuddyBrain.react(com.example.ui.buddy.BuddyEvent.REDO) }
                )

                // 4. Timescale Section (1/30 of screen)
                TimescaleSection(
                    height = timescaleH,
                    playheadMs = playheadProvider,
                    totalDurationMs = totalDurationMs,
                    pxPerMs = timelinePxPerMs,
                    beatMarksMs = beatMarks
                )

                // 5. Layer Panel (5/18 of screen). Inside a group (Edit Group) a green bar on top leads back out.
                Box(Modifier.fillMaxWidth().height(layerPanelH)) {
                TimelineLayerPanel(
                    height = layerPanelH,
                    tracks = shownTracks,
                    playheadMs = playheadProvider,
                    totalDurationMs = totalDurationMs,
                    selectedClipId = selectedClipId,
                    isMasterMuted = isMasterMuted,
                    onSeekTo = { viewModel.seekTo(it) },
                    onScrubStart = { viewModel.pause() },
                    isPlaying = isPlaying,
                    // While several layers are checked, taps/drags on layer bodies must not
                    // switch to single selection (that would leave multi-select).
                    onClipSelect = { id -> if (multiSelectIds.isEmpty()) viewModel.selectClip(id) },
                    multiSelectIds = multiSelectIds,
                    onLongPressLane = { id -> viewModel.enterMultiSelect(id) },
                    onToggleMulti = { id -> viewModel.toggleMultiSelect(id) },
                    onMultiAction = { viewModel.applyMultiAction(it) },
                    onSplitClip = {
                        com.example.ui.buddy.BuddyBrain.react(com.example.ui.buddy.BuddyEvent.SPLIT)
                        viewModel.splitSelectedClip()
                    },
                    onDeleteClip = {
                        viewModel.deleteSelectedClip()
                    },
                    onDuplicateClip = {
                        viewModel.duplicateSelectedClip()
                    },
                    onToggleMasterAudio = {
                        viewModel.toggleMasterAudio()
                    },
                    onFlipHorizontal = { viewModel.flipHorizontalSelectedClip() },
                    onFlipVertical = { viewModel.flipVerticalSelectedClip() },
                    onReplace = {
                        val target = tracks.flatMap { it.clips }.find { it.id == selectedClipId }
                        if (target != null && target.type == LayerType.VIDEO) {
                            replacePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        } else {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            scope.launch {
                                snackbarHostState.showSnackbar("Replace works on video and photo layers")
                            }
                        }
                    },
                    onSpeed = run {
                        val sel = tracks.flatMap { it.clips }.find { it.id == selectedClipId }
                        if (sel != null && (sel.type == LayerType.AUDIO ||
                                (sel.type == LayerType.VIDEO && !sel.isImage && sel.uri != null))
                        ) {
                            {
                                viewModel.beginAdjust()
                                showAudioSpeedSheet = true
                            }
                        } else if (sel != null && sel.type == LayerType.STICKER) {
                            {
                                viewModel.beginAdjust()
                                showStickerSpeedSheet = true
                            }
                        } else null
                    },
                    onReverse = run {
                        val sel = tracks.flatMap { it.clips }.find { it.id == selectedClipId }
                        if (sel != null && sel.type == LayerType.VIDEO && !sel.isImage && sel.uri != null) {
                            { startVideoReverse(sel.id) }
                        } else null
                    },
                    waveforms = waveforms,
                    onMoveClipInTime = { id, delta -> viewModel.moveClipInTime(id, delta) },
                    onBeginClipTimeDrag = { viewModel.beginAdjust() },
                    onReorderLane = { from, to -> viewModel.reorderShownLane(from, to) },
                    onTrimClipEdge = { id, left, d -> viewModel.trimClipEdge(id, left, d) },
                    onToggleClipVisibility = { id -> viewModel.toggleClipVisibility(id) },
                    pxPerMs = timelinePxPerMs,
                    onPxPerMsChange = { timelinePxPerMs = it },
                    onAddClick = { showAddLayerSheet = true }
                )
                val editingGroup = editingGroupId?.let { gid -> tracks.flatMap { it.clips }.firstOrNull { it.id == gid } }
                if (editingGroup != null) {
                    com.example.ui.components.GroupEditBar(
                        name = editingGroup.name,
                        onExit = { viewModel.exitGroup() },
                        modifier = Modifier.align(Alignment.TopEnd)
                    )
                }
                }

                // 6. Footer: Clip Edit Tools Panel if clip is selected, otherwise Main Bottom Toolbar
                AnimatedContent(
                    targetState = selectedClipId,
                    // Fast, springy swap (Alight Motion style): new bar slides up a little and fades in,
                    // the old one fades out quickly. No slow tween, no scale pop.
                    transitionSpec = {
                        (fadeIn(androidx.compose.animation.core.tween(110, delayMillis = 40)) +
                            androidx.compose.animation.slideInVertically(
                                animationSpec = androidx.compose.animation.core.spring(
                                    dampingRatio = 0.8f,
                                    stiffness = 900f
                                ),
                                initialOffsetY = { it / 3 }
                            )) togetherWith
                            fadeOut(androidx.compose.animation.core.tween(80))
                    },
                    label = "toolbarTransition"
                ) { targetClipId ->
                    if (targetClipId != null) {
                        val targetIsAudio = tracks.any { t ->
                            t.clips.any { it.id == targetClipId && it.type == LayerType.AUDIO }
                        }
                        val targetIsChain = tracks.any { t ->
                            t.clips.any { it.id == targetClipId && it.type == LayerType.CHAIN }
                        }
                        if (targetIsChain) com.example.ui.components.ChainAddPanel(
                            // A Chain has no picture of its own: only ways to put layers into it (they go inside while it is selected).
                            height = toolbarH,
                            onBack = { viewModel.selectClip(null) },
                            onMedia = {
                                mediaPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                )
                            },
                            onText = {
                                val remaining = totalDurationMs - playheadState.value
                                val id = viewModel.addTextLayer(
                                    if (totalDurationMs > 0L && remaining >= 1000L) minOf(4000L, remaining) else 4000L
                                )
                                if (id.isNotEmpty()) editingTextId = id
                            },
                            onShape = { showAddShapeSheet = true },
                            onAudio = { audioPicker.launch(arrayOf("audio/*")) },
                            onStickers = { showStickerSheet = true },
                            onNull = { viewModel.addNullLayer() },
                            onCamera = { viewModel.addCameraLayer() },
                            onDelete = { viewModel.deleteSelectedClip() }
                        ) else if (targetIsAudio) AudioClipToolsPanel(
                            height = toolbarH,
                            onDeselect = { viewModel.selectClip(null) },
                            onVolume = {
                                viewModel.beginAdjust()
                                showAudioVolumeSheet = true
                            },
                            onFade = {
                                viewModel.beginAdjust()
                                showAudioFadeSheet = true
                            },
                            onSpeed = {
                                viewModel.beginAdjust()
                                showAudioSpeedSheet = true
                            },
                            onReverse = { startAudioReverse(targetClipId) },
                            onCaptions = { requestCaptions(targetClipId) },
                            onRename = { renameTargetId = targetClipId }
                        ) else ClipEditToolsPanel(
                            height = toolbarH,
                            // Null / 3D Camera: no picture, so no Effects / Animations / Color / Crop / Blend; only what moves them.
                            onlyTools = if (tracks.any { t -> t.clips.any { it.id == targetClipId && (it.type == LayerType.NULL || it.type == LayerType.CAMERA) } })
                                setOf("transform", "parent", "rename") else null,
                            onDeselect = { viewModel.selectClip(null) },
                            onEffects = {
                                // Effects are drawn by the C++ engine on every picture layer: video, photo, text, shape, sticker.
                                val target = tracks.flatMap { it.clips }.find { it.id == selectedClipId }
                                if (target != null && target.type != LayerType.AUDIO && target.type != LayerType.NULL && target.type != LayerType.CAMERA && target.type != LayerType.CHAIN && target.type != LayerType.GROUP) {
                                    if (!target.isActiveAt(playheadState.value)) {
                                        viewModel.seekTo(target.startMs)
                                    }
                                    // One undo step for everything tried in the panel.
                                    viewModel.beginAdjust()
                                    showClipEffectsSheet = true
                                } else {
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Effects do not work on audio, Null, Camera, Chain and Group layers")
                                    }
                                }
                            },
                            onMasks = {
                                // Masks (shapes with feather / invert / combine modes) are drawn by the C++ engine on video / photo layers.
                                val target = tracks.flatMap { it.clips }.find { it.id == selectedClipId }
                                if (target != null && target.type == LayerType.VIDEO) {
                                    if (!target.isActiveAt(playheadState.value)) {
                                        viewModel.seekTo(target.startMs)
                                    }
                                    viewModel.pause()
                                    viewModel.beginAdjust()
                                    maskSelectedId = target.masks.firstOrNull()?.id
                                    maskSelPoint = -1
                                    maskPenPoints = null
                                    showClipMasksSheet = true
                                } else {
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Masks work on video and photo layers")
                                    }
                                }
                            },
                            onAnimations = {
                                // Animations of the selected layer: jump to its start if the playhead is outside it.
                                val target = tracks.flatMap { it.clips }.find { it.id == selectedClipId }
                                if (target != null && !target.isActiveAt(playheadState.value)) {
                                    viewModel.seekTo(target.startMs)
                                }
                                // One undo step for everything tried in the panel.
                                viewModel.beginAdjust()
                                showClipAnimationsSheet = true
                            },
                            onColor = {
                                // Color = the fill of a shape or text layer (none / solid / gradient).
                                val target = tracks.flatMap { it.clips }.find { it.id == selectedClipId }
                                if (target != null && (target.type == LayerType.SHAPE || target.type == LayerType.TEXT)) {
                                    if (!target.isActiveAt(playheadState.value)) {
                                        viewModel.seekTo(target.startMs)
                                    }
                                    viewModel.beginAdjust()
                                    editingColorId = target.id
                                } else {
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Color works on shape and text layers")
                                    }
                                }
                            },
                            onTransform = {
                                // Open Transform on the selected layer: if the playhead is outside it,
                                // jump to the layer's start so the layer is visible straight away.
                                val target = tracks.flatMap { it.clips }.find { it.id == selectedClipId }
                                if (target != null && !target.isActiveAt(playheadState.value)) {
                                    viewModel.seekTo(target.startMs)
                                }
                                showTransformMode = true
                            },
                            onCrop = {
                                // Open Crop on the selected layer; jump to its start if the playhead is outside it.
                                val target = tracks.flatMap { it.clips }.find { it.id == selectedClipId }
                                if (target != null && !target.isActiveAt(playheadState.value)) {
                                    viewModel.seekTo(target.startMs)
                                }
                                viewModel.beginAdjust()
                                showClipCropSheet = true
                            },
                            onBlend = {
                                // Blending works on every visual layer: open it on the selected one and
                                // jump to the layer's start if the playhead is outside it.
                                val target = tracks.flatMap { it.clips }.find { it.id == selectedClipId }
                                if (target != null) {
                                    if (!target.isActiveAt(playheadState.value)) {
                                        viewModel.seekTo(target.startMs)
                                    }
                                    viewModel.beginAdjust()
                                    editingBlendId = target.id
                                }
                            },
                            onCutout = {
                                // Cutout (person only, RVM): a photo is replaced by its cutout picture;
                                // a video gets a mask for EVERY frame (preview + export). Tap again to remove.
                                val target = tracks.flatMap { it.clips }.find { it.id == selectedClipId }
                                val srcUri = target?.uri
                                snackbarHostState.currentSnackbarData?.dismiss()
                                if (target == null || target.type != LayerType.VIDEO || srcUri == null) {
                                    scope.launch { snackbarHostState.showSnackbar("Cutout works on video and photo layers") }
                                } else if (cutoutProgress != null) {
                                    scope.launch { snackbarHostState.showSnackbar("A cutout is already running") }
                                } else if (!target.isImage && target.cutoutDir != null) {
                                    viewModel.beginAdjust()
                                    viewModel.updateClip(target.id) {
                                        it.copy(cutoutDir = null, cutoutFps = 0f, cutoutStartMs = 0L, cutoutFrames = 0)
                                    }
                                    scope.launch { snackbarHostState.showSnackbar("Cutout removed") }
                                } else if (target.isImage) {
                                    scope.launch { snackbarHostState.showSnackbar("Cutting out the person…") }
                                    scope.launch {
                                        val result = withContext(Dispatchers.Default) {
                                            runCatching {
                                                val src = CutoutEngine.loadPhoto(context, srcUri.toUri())
                                                if (src == null) null
                                                else {
                                                    val (out, engine) = CutoutEngine.cutoutAuto(context, src)
                                                    Pair(Triple(CutoutEngine.savePng(context, out), out.width, out.height), engine)
                                                }
                                            }
                                        }
                                        snackbarHostState.currentSnackbarData?.dismiss()
                                        val done = result.getOrNull()
                                        if (done == null) {
                                            scope.launch { snackbarHostState.showSnackbar("Cutout failed: " + (result.exceptionOrNull()?.message ?: "could not read the picture")) }
                                        } else {
                                            val (saved, w, h) = done.first
                                            viewModel.beginAdjust()
                                            viewModel.updateClip(target.id) {
                                                it.copy(uri = saved, mimeType = "image/png", name = it.name + " cutout", sourceAspect = w / h.toFloat())
                                            }
                                            scope.launch { snackbarHostState.showSnackbar("Cutout done - " + done.second) }
                                        }
                                    }
                                } else if (!com.example.engine.RvmEngine.isAvailable(context)) {
                                    askModelDownload = target
                                } else {
                                    runVideoCutout(target, false)
                                }
                            },
                            onRoto = if (tracks.flatMap { it.clips }.any {
                                    it.id == targetClipId && it.type == LayerType.VIDEO && !it.isImage && it.uri != null
                                }
                            ) {
                                {
                                    val t = tracks.flatMap { it.clips }.find { it.id == targetClipId }
                                    if (t != null && t.cutoutDir != null) {
                                        // Same slot as the person cutout: tap again to remove the roto result.
                                        viewModel.beginAdjust()
                                        viewModel.updateClip(t.id) {
                                            it.copy(cutoutDir = null, cutoutFps = 0f, cutoutStartMs = 0L, cutoutFrames = 0)
                                        }
                                        scope.launch { snackbarHostState.showSnackbar("Cutout removed") }
                                    } else if (t != null) {
                                        rotoClipId = t.id
                                    }
                                }
                            } else null,
                            onVolume = if (tracks.flatMap { it.clips }.any {
                                    it.id == targetClipId && it.type == LayerType.VIDEO && !it.isImage && it.uri != null
                                }
                            ) {
                                {
                                    viewModel.beginAdjust()
                                    showAudioVolumeSheet = true
                                }
                            } else null,
                            onTrack = if (tracks.flatMap { it.clips }.any {
                                    it.id == targetClipId && it.type == LayerType.VIDEO && !it.isImage && it.uri != null
                                }
                            ) {
                                { requestTrack(targetClipId) }
                            } else null,
                            onRemove = if (tracks.flatMap { it.clips }.any {
                                    it.id == targetClipId && it.type == LayerType.VIDEO && !it.isImage && it.uri != null
                                }
                            ) {
                                { requestRemove(targetClipId) }
                            } else null,
                            onStabilize = if (tracks.flatMap { it.clips }.any {
                                    it.id == targetClipId && it.type == LayerType.VIDEO && !it.isImage && it.uri != null
                                }
                            ) {
                                { requestStabilize(targetClipId) }
                            } else null,
                            onCaptions = if (tracks.flatMap { it.clips }.any {
                                    it.id == targetClipId && it.type == LayerType.VIDEO && !it.isImage && it.uri != null
                                }
                            ) {
                                { requestCaptions(targetClipId) }
                            } else null,
                            onFaceAi = if (tracks.flatMap { it.clips }.any {
                                    it.id == targetClipId && it.type == LayerType.VIDEO && !it.isImage && it.uri != null
                                }
                            ) {
                                { requestFaceAi(targetClipId) }
                            } else null,
                            onFaceSwap = if (tracks.flatMap { it.clips }.any {
                                    it.id == targetClipId && it.type == LayerType.VIDEO && !it.isImage && it.uri != null
                                }
                            ) {
                                { requestFaceSwap(targetClipId) }
                            } else null,
                            onEditText = if (tracks.flatMap { it.clips }
                                    .any { it.id == targetClipId && it.type == LayerType.TEXT }
                            ) {
                                {
                                    viewModel.beginTextEdit()
                                    editingTextId = targetClipId
                                }
                            } else null,
                            onParent = if (tracks.flatMap { it.clips }.any { it.id == targetClipId && it.type == LayerType.GROUP }) null
                            else { { parentSheetClipId = targetClipId } },
                            onUngroup = if (tracks.flatMap { it.clips }.any { it.id == targetClipId && it.type == LayerType.GROUP }) {
                                {
                                    viewModel.ungroupSelected()
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    scope.launch { snackbarHostState.showSnackbar("Group ungrouped") }
                                }
                            } else null,
                            onEditGroup = if (tracks.flatMap { it.clips }.any { it.id == targetClipId && it.type == LayerType.GROUP }) {
                                { viewModel.enterGroup(targetClipId) }
                            } else null,
                            onRename = { renameTargetId = targetClipId },
                            onFreeze = if (tracks.flatMap { it.clips }.any {
                                    it.id == targetClipId && it.type == LayerType.VIDEO && !it.isImage && it.uri != null
                                }
                            ) {
                                { startFreezeFrame(targetClipId) }
                            } else null,
                            onTransition = if (tracks.flatMap { it.clips }.any {
                                    it.id == targetClipId && it.type != LayerType.GROUP && it.type != LayerType.NULL &&
                                        it.type != LayerType.CAMERA && it.type != LayerType.CHAIN && it.type != LayerType.AUDIO
                                }
                            ) {
                                { transitionTargetId = targetClipId }
                            } else null
                        )
                    } else {
                        BottomToolbar(
                            height = toolbarH,
                            onNullClick = { viewModel.addNullLayer() },
                            onChainClick = { viewModel.addChainLayer() },
                            onCameraClick = { viewModel.addCameraLayer() }
                        )
                    }
                }
            }

            // ---- Text edit -------------------------------------------------------------------
            // Same idea as Transform: the real canvas in the middle stays visible (it shows every
            // change live), the header becomes "Text edit" and the lower area becomes the editor.
            val editingText = editingTextId?.let { id -> tracks.flatMap { it.clips }.find { it.id == id } }
            LaunchedEffect(editingText == null, editingTextId) {
                if (editingText == null && editingTextId != null) editingTextId = null
            }
            if (editingText != null) {
                val closeText = {
                    eyedropperCallback = null
                    editingTextId = null
                }
                com.example.ui.components.TransformHeaderBar(
                    height = headerH,
                    title = "Text edit",
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(31f)
                        .enterPop()
                )

                // Same layout as Color/Blending. While the keyboard is open only the text box +
                // chips remain, raised above the keyboard.
                val textImeVisible = WindowInsets.isImeVisible
                val textRowH = com.example.ui.components.laneHeightFor(editingText.type) + 8.dp
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .then(if (textImeVisible) Modifier else Modifier.height(panelH))
                        .zIndex(30f)
                        .enterPop(fromBottom = true)
                        .background(EditorBackground)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        .swipeDownToDismiss(onDismiss = closeText)
                ) {
                    if (!textImeVisible) {
                        com.example.ui.components.TimescaleSection(
                            height = timescaleH,
                            playheadMs = { playheadState.value },
                            totalDurationMs = totalDurationMs,
                            onBack = closeText
                        )
                        layerRow(editingText.id)
                    }
                    TextEditSheet(
                        clip = editingText,
                        onUpdate = { change -> viewModel.updateClip(editingText.id, change) },
                        onEyedropper = { cb -> eyedropperCallback = cb },
                        modifier = Modifier.fillMaxWidth(),
                        fullHeight = (panelH - timescaleH - textRowH).coerceAtLeast(120.dp)
                    )
                }
                // The layer can disappear while editing (undo / delete): close the editor then.

            }

            // ---- Color (fill) editor -----------------------------------------------------
            // Same idea as Text edit: the canvas stays visible and shows every change live.
            val editingColor = editingColorId?.let { id -> tracks.flatMap { it.clips }.find { it.id == id } }
            LaunchedEffect(editingColor == null, editingColorId) {
                if (editingColor == null && editingColorId != null) editingColorId = null
            }
            if (editingColor != null) {
                val closeColor = {
                    eyedropperCallback = null
                    editingColorId = null
                }
                com.example.ui.components.TransformHeaderBar(
                    height = headerH,
                    title = "Color",
                    onReset = {

                        // Reset = back to a plain white fill with the default gradient.
                        val d = com.example.model.TimelineClip(id = "", name = "", type = LayerType.SHAPE, startMs = 0, durationMs = 0, color = 0)
                        viewModel.updateClipNoKeyframes(editingColor.id) {
                            it.copy(
                                keyframes = it.keyframes.filterKeys { p -> !p.isColor },
                                fillMode = com.example.model.FILL_SOLID, textColor = 0xFFFFFFFF,
                                gradType = d.gradType, gradColor1 = d.gradColor1, gradColor2 = d.gradColor2,
                                gradX1 = d.gradX1, gradY1 = d.gradY1, gradX2 = d.gradX2, gradY2 = d.gradY2,
                                fillImageUri = null, fillImageMode = d.fillImageMode
                            )
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(31f)
                        .enterPop()
                )

                // Bottom area: timescale + layer strip + Color editor.
                // Play controls stay visible above (universal).
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = panelTop)
                        .fillMaxWidth()
                        .height(panelH)
                        .zIndex(30f)
                        .enterPop(fromBottom = true)
                        .background(EditorBackground)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        .swipeDownToDismiss(onDismiss = closeColor)
                ) {
                    // No TransformPlaybackRow — keep clean look (layer strip + editor only).
                    com.example.ui.components.TimescaleSection(
                        height = timescaleH,
                        playheadMs = { playheadState.value },
                        totalDurationMs = totalDurationMs,
                        onBack = closeColor
                    )
                    layerRow(editingColor.id)
                    com.example.ui.components.ColorEditSheet(
                        rawClip = editingColor,
                        currentPlayheadMs = playheadState.value,
                        onToggleKeyframes = { props -> viewModel.toggleKeyframes(editingColor.id, props) },
                        onEasingBegin = { viewModel.beginAdjust() },
                        onEasingChange = { prop, kfTime, easing ->
                            viewModel.setKeyframeEasing(editingColor.id, prop, kfTime, easing)
                        },
                        onUpdate = { change -> viewModel.updateClipColor(editingColor.id, change) },
                        onEyedropper = { cb -> eyedropperCallback = cb },
                        onPickImage = {
                            editingColorIdForPicker = editingColor.id
                            fillImagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onBack = closeColor,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ---- Blending editor ---------------------------------------------------------
            // Same layout as Color: the canvas stays visible and shows opacity / blend live.
            val editingBlend = editingBlendId?.let { id -> tracks.flatMap { it.clips }.find { it.id == id } }
            LaunchedEffect(editingBlend == null, editingBlendId) {
                if (editingBlend == null && editingBlendId != null) editingBlendId = null
            }
            if (editingBlend != null) {
                com.example.ui.components.TransformHeaderBar(
                    height = headerH,
                    title = "Blending",
                    onReset = {
                        viewModel.updateClip(editingBlend.id) {
                            it.copy(opacity = 100f, blendMode = com.example.model.BlendModes.NORMAL)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(31f)
                        .enterPop()
                )

                // Play controls stay visible above (universal). Only layer strip + blend editor below.
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = panelTop)
                        .fillMaxWidth()
                        .height(panelH)
                        .zIndex(30f)
                        .enterPop(fromBottom = true)
                        .background(EditorBackground)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        .swipeDownToDismiss(onDismiss = { editingBlendId = null })
                ) {
                    com.example.ui.components.TimescaleSection(
                        height = timescaleH,
                        playheadMs = { playheadState.value },
                        totalDurationMs = totalDurationMs,
                        onBack = { editingBlendId = null }
                    )
                    layerRow(editingBlend.id)
                    com.example.ui.components.BlendEditSheet(
                        clip = editingBlend.at(playheadState.value),
                        rawClip = editingBlend,
                        currentPlayheadMs = playheadState.value,
                        onUpdate = { change -> viewModel.updateClipLive(editingBlend.id, change) },
                        onToggleKeyframes = { props -> viewModel.toggleKeyframes(editingBlend.id, props) },
                        onEasingBegin = { viewModel.beginAdjust() },
                        onEasingChange = { prop, kfTime, easing ->
                            viewModel.setKeyframeEasing(editingBlend.id, prop, kfTime, easing)
                        },
                        onReset = {
                            viewModel.updateClipNoKeyframes(editingBlend.id) {
                                it.copy(
                                    keyframes = it.keyframes.filterKeys { p -> p != com.example.model.AnimProp.OPACITY },
                                    opacity = 100f,
                                    blendMode = com.example.model.BlendModes.NORMAL
                                )
                            }
                        },
                        onBack = { editingBlendId = null },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Eyedropper (Text edit and Color): a tap on the canvas reads the pixel under the finger.
            if (editingText != null || editingColor != null || showClipEffectsSheet) {
                val picker = eyedropperCallback
                if (picker != null && showClipEffectsSheet && editingText == null && editingColor == null) {
                    // Chroma Key: a round ring to drag over the picture; the colour under it is read live.
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = headerH)
                            .fillMaxWidth()
                            .height(canvasH)
                            .zIndex(32f)
                    ) {
                        com.example.ui.components.ChromaPickRing(
                            onConfirm = { c ->
                                picker(c)
                                eyedropperCallback = null
                            },
                            onCancel = {
                                eyedropperCancel?.invoke()
                                eyedropperCancel = null
                                eyedropperCallback = null
                            }
                        )
                    }
                } else if (picker != null) {
                    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = headerH)
                            .fillMaxWidth()
                            .height(canvasH)
                            .zIndex(32f)
                            .onGloballyPositioned { origin = it.positionInWindow() }
                            .pointerInput(picker) {
                                detectTapGestures { tap ->
                                    pickColorFromWindow(
                                        context,
                                        (origin.x + tap.x).toInt(),
                                        (origin.y + tap.y).toInt()
                                    ) { color ->
                                        if (color != null) picker(color)
                                        else {
                                            eyedropperCancel?.invoke()
                                            eyedropperCancel = null
                                            scope.launch {
                                                snackbarHostState.showSnackbar("Could not read that color")
                                            }
                                        }
                                        eyedropperCallback = null
                                    }
                                }
                            }
                    ) {
                        Text(
                            "Tap the canvas to pick a color",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 10.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Black.copy(alpha = 0.65f))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
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
                    onReset = { transformResetTick++ },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(21f)
                        .enterPop()
                )

                // Cover play controls so only clean layer-strip + transform panel is visible
                // Play controls stay visible above (universal). Only layer strip + transform panel below.
                // The canvas in the middle stays untouched.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = panelTop)
                        .fillMaxWidth()
                        .height(panelH)
                        .zIndex(20f)
                        .enterPop(fromBottom = true)
                        .background(EditorBackground)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        .swipeDownToDismiss(onDismiss = { showTransformMode = false })
                ) {
                    com.example.ui.components.TransformView(
                        // The layer as it looks at the playhead (keyframes applied).
                        clip = selectedClip?.at(playheadState.value),
                        aspectRatio = selectedAspectRatio,
                        currentPlayheadMs = playheadState.value,
                        totalDurationMs = totalDurationMs,
                        isPlaying = isPlaying,
                        canUndo = canUndo,
                        canRedo = canRedo,
                        onTogglePlay = { viewModel.togglePlayPause() },
                        onSeekTo = { viewModel.seekTo(it) },
                        onUndo = { viewModel.undo(); com.example.ui.buddy.BuddyBrain.react(com.example.ui.buddy.BuddyEvent.UNDO) },
                        onRedo = { viewModel.redo(); com.example.ui.buddy.BuddyBrain.react(com.example.ui.buddy.BuddyEvent.REDO) },
                        onDismiss = { showTransformMode = false },
                        panelOnly = true,
                        // A Null only hands its 2D move / scale / turn to its children (ParentRig): no 3D tab for it.
                        show3d = selectedClip?.type != LayerType.NULL,
                        timescaleHeight = timescaleH,
                        resetTick = transformResetTick,
                        onToggleKeyframe = { prop ->
                            selectedClip?.id?.let { viewModel.toggleKeyframe(it, prop) }
                        },
                        onEasingBegin = { viewModel.beginAdjust() },
                        onEasingChange = { prop, kfTime, easing ->
                            selectedClip?.id?.let { viewModel.setKeyframeEasing(it, prop, kfTime, easing) }
                        },
                        layerRow = selectedClip?.id?.let { id -> @Composable { layerRow(id) } },
                        onApplyTransform = { h, v, sc, rot, px, py, r3x, r3y, warp, z ->
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
                                    warp = warp,
                                    transformZ = z
                                )
                            }
                        }
                    )
                }
            }
            // ---- Audio panels (Volume / Fade / Speed): same layout as the other editors ----------
            val audioClip = tracks.flatMap { it.clips }
                .firstOrNull {
                    it.id == selectedClipId && (it.type == LayerType.AUDIO ||
                        (it.type == LayerType.VIDEO && !it.isImage && it.uri != null))
                }
            val audioPanelTitle = when {
                showAudioVolumeSheet -> "Volume"
                showAudioFadeSheet -> "Fade"
                showAudioSpeedSheet -> "Speed"
                else -> null
            }
            val closeAudioPanel = {
                showAudioVolumeSheet = false
                showAudioFadeSheet = false
                showAudioSpeedSheet = false
            }
            LaunchedEffect(audioClip == null, audioPanelTitle) {
                if (audioClip == null && audioPanelTitle != null) closeAudioPanel()
            }
            BackHandler(enabled = audioPanelTitle != null) { closeAudioPanel() }
            if (audioClip != null && audioPanelTitle != null) {
                com.example.ui.components.TransformHeaderBar(
                    height = headerH,
                    title = audioPanelTitle,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(31f)
                        .enterPop()
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = panelTop)
                        .fillMaxWidth()
                        .height(panelH)
                        .zIndex(30f)
                        .enterPop(fromBottom = true)
                        .background(EditorBackground)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        .swipeDownToDismiss(onDismiss = closeAudioPanel)
                ) {
                    com.example.ui.components.TimescaleSection(
                        height = timescaleH,
                        playheadMs = { playheadState.value },
                        totalDurationMs = totalDurationMs,
                        onBack = closeAudioPanel
                    )
                    layerRow(audioClip.id)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (showAudioVolumeSheet) {
                            AudioVolumeSheet(
                                volume = audioClip.volume,
                                onVolumeChange = { viewModel.setClipVolume(audioClip.id, it) },
                                onDismiss = closeAudioPanel,
                                muted = audioClip.isMuted,
                                onMutedChange = { viewModel.setClipMuted(audioClip.id, it) }
                            )
                        } else if (showAudioFadeSheet) {
                            AudioFadeSheet(
                                fadeInMs = audioClip.fadeInMs,
                                fadeOutMs = audioClip.fadeOutMs,
                                maxFadeMs = audioClip.durationMs / 2,
                                onChange = { fin, fout -> viewModel.setClipFades(audioClip.id, fin, fout) },
                                onDismiss = closeAudioPanel
                            )
                        } else if (showAudioSpeedSheet) {
                            AudioSpeedSheet(
                                speed = audioClip.at(playheadState.value).speed,
                                onSpeedChange = { viewModel.setClipSpeed(audioClip.id, it) },
                                onDismiss = closeAudioPanel,
                                rawClip = audioClip,
                                playheadMs = playheadState.value,
                                onLiveSpeed = { v ->
                                    viewModel.updateClipLive(audioClip.id) { it.copy(speed = v) }
                                },
                                onToggleKeyframes = { props -> viewModel.toggleKeyframes(audioClip.id, props) },
                                onEasingBegin = { viewModel.beginAdjust() },
                                onEasingChange = { prop, kfTime, easing ->
                                    viewModel.setKeyframeEasing(audioClip.id, prop, kfTime, easing)
                                },
                                onSmooth = if (audioClip.type == LayerType.VIDEO && !audioClip.isImage && audioClip.uri != null) {
                                    {
                                        closeAudioPanel()
                                        startSmoothSlowMo(audioClip.id)
                                    }
                                } else null
                            )
                        }
                    }
                }
            }

            // ---- Animations panel: same place and layout as Transform / Volume / Fade (timescale + layer on top) ----
            val animClip = tracks.flatMap { it.clips }.firstOrNull { it.id == selectedClipId }
            val closeAnimPanel = {
                animPreviewJob?.cancel()
                viewModel.pause()
                showClipAnimationsSheet = false
            }
            LaunchedEffect(animClip == null, showClipAnimationsSheet) {
                if (animClip == null && showClipAnimationsSheet) closeAnimPanel()
            }
            BackHandler(enabled = showClipAnimationsSheet) { closeAnimPanel() }
            if (showClipAnimationsSheet && animClip != null) {
                // Canvas size in preview pixels = the unit of the Position values (slides need it).
                val animCanvasW = if (previewFrameWidthPx > 1f) previewFrameWidthPx else 1000f
                val animCanvasH = animCanvasW / selectedAspectRatio.ratioFloat.coerceAtLeast(0.1f)
                com.example.ui.components.TransformHeaderBar(
                    height = headerH,
                    title = "Animations",
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(31f)
                        .enterPop()
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = panelTop)
                        .fillMaxWidth()
                        .height(panelH)
                        .zIndex(30f)
                        .enterPop(fromBottom = true)
                        .background(EditorBackground)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        .swipeDownToDismiss(onDismiss = closeAnimPanel)
                ) {
                    com.example.ui.components.TimescaleSection(
                        height = timescaleH,
                        playheadMs = { playheadState.value },
                        totalDurationMs = totalDurationMs,
                        onBack = closeAnimPanel
                    )
                    layerRow(animClip.id)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        ClipAnimationsPanel(
                            clip = animClip,
                            aspectRatio = selectedAspectRatio.ratioFloat,
                            onApply = { slot, preset, dir, ms, easing ->
                                viewModel.setClipAnim(slot, preset, dir, ms, easing, animCanvasW, animCanvasH)
                            },
                            onPreview = { slot, ms ->
                                // Plays the animation: In from the layer's start, Out from just before its end.
                                val c = tracks.flatMap { it.clips }.find { it.id == selectedClipId }
                                if (c != null) {
                                    animPreviewJob?.cancel()
                                    animPreviewJob = scope.launch {
                                        val from = if (slot == com.example.model.AnimSlot.IN) c.startMs
                                        else (c.endMs - ms - 300L).coerceAtLeast(c.startMs)
                                        val until = if (slot == com.example.model.AnimSlot.IN) (c.startMs + ms + 400L).coerceAtMost(c.endMs - 1L)
                                        else c.endMs - 1L
                                        viewModel.pause()
                                        viewModel.seekTo(from)
                                        viewModel.play()
                                        kotlinx.coroutines.delay((until - from).coerceAtLeast(50L))
                                        viewModel.pause()
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // ---- Effects panel: same place and layout as Animations (timescale + layer on top, canvas shows the result live) ----
            val fxClip = tracks.flatMap { it.clips }.firstOrNull { it.id == selectedClipId }
            val closeFxPanel = {
                // Closing the panel while the eyedropper is on abandons the pick (the key comes back on).
                eyedropperCancel?.invoke()
                eyedropperCancel = null
                eyedropperCallback = null
                showClipEffectsSheet = false
            }
            LaunchedEffect(fxClip == null, showClipEffectsSheet) {
                if (fxClip == null && showClipEffectsSheet) closeFxPanel()
            }
            BackHandler(enabled = showClipEffectsSheet) {
                if (eyedropperCallback != null) {
                    eyedropperCancel?.invoke()
                    eyedropperCancel = null
                    eyedropperCallback = null
                } else closeFxPanel()
            }
            if (showClipEffectsSheet && fxClip != null) {
                com.example.ui.components.TransformHeaderBar(
                    height = headerH,
                    title = "Effects",
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(31f)
                        .enterPop()
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = panelTop)
                        .fillMaxWidth()
                        .height(panelH)
                        .zIndex(30f)
                        .enterPop(fromBottom = true)
                        .background(EditorBackground)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        .swipeDownToDismiss(onDismiss = closeFxPanel)
                ) {
                    com.example.ui.components.TimescaleSection(
                        height = timescaleH,
                        playheadMs = { playheadState.value },
                        totalDurationMs = totalDurationMs,
                        onBack = closeFxPanel
                    )
                    layerRow(fxClip.id)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        com.example.ui.components.ClipEffectsPanel(
                            clip = fxClip,
                            onChange = { list -> viewModel.setClipEffects(fxClip.id, list) },
                            onPickColor = { onPicked, onCancel ->
                                eyedropperCancel = onCancel
                                eyedropperCallback = { c ->
                                    eyedropperCancel = null
                                    onPicked(c)
                                }
                            }
                        )
                    }
                }
            }

            // ---- Masks panel: same place and layout as Effects; the shape is edited on the canvas above ----
            val maskClip = tracks.flatMap { it.clips }.firstOrNull { it.id == selectedClipId }
            val closeMaskPanel = {
                showClipMasksSheet = false
                maskPenPoints = null
                maskSelPoint = -1
            }
            LaunchedEffect(maskClip == null, showClipMasksSheet) {
                if (maskClip == null && showClipMasksSheet) closeMaskPanel()
            }
            BackHandler(enabled = showClipMasksSheet) {
                if (maskPenPoints != null) {
                    maskPenPoints = null
                } else {
                    closeMaskPanel()
                }
            }
            if (showClipMasksSheet && maskClip != null) {
                com.example.ui.components.TransformHeaderBar(
                    height = headerH,
                    title = "Masks",
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(31f)
                        .enterPop()
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = panelTop)
                        .fillMaxWidth()
                        .height(panelH)
                        .zIndex(30f)
                        .enterPop(fromBottom = true)
                        .background(EditorBackground)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        .swipeDownToDismiss(onDismiss = closeMaskPanel)
                ) {
                    com.example.ui.components.TimescaleSection(
                        height = timescaleH,
                        playheadMs = { playheadState.value },
                        totalDurationMs = totalDurationMs,
                        onBack = closeMaskPanel
                    )
                    layerRow(maskClip.id)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        com.example.ui.components.ClipMasksPanel(
                            clip = maskClip,
                            playheadMs = playheadState.value,
                            selectedId = maskSelectedId,
                            onSelect = { maskSelectedId = it; maskSelPoint = -1 },
                            penPoints = maskPenPoints?.size ?: -1,
                            onPenStart = { maskPenPoints = emptyList() },
                            onPenUndo = { maskPenPoints = maskPenPoints?.dropLast(1) },
                            onPenDone = {
                                val pts = maskPenPoints
                                val nm = if (pts != null) com.example.model.ClipMaskLibrary.fromPenPoints(pts, smooth = false) else null
                                if (nm != null) {
                                    maskSelectedId = nm.id
                                    maskSelPoint = -1
                                    viewModel.setClipMasks(maskClip.id, maskClip.masks + nm)
                                }
                                maskPenPoints = null
                            },
                            onPenCancel = { maskPenPoints = null },
                            selectedPoint = maskSelPoint,
                            onDeletePoint = {
                                val m = maskClip.masks.firstOrNull { it.id == maskSelectedId }
                                if (m != null && maskSelPoint in m.points.indices && m.points.size > com.example.model.ClipMaskLibrary.MIN_POINTS) {
                                    val nm = m.copy(points = m.points.filterIndexed { i, _ -> i != maskSelPoint })
                                    viewModel.setClipMasks(maskClip.id, maskClip.masks.map { if (it.id == nm.id) nm else it })
                                    maskSelPoint = -1
                                }
                            },
                            onSeekTo = { viewModel.seekTo(it) },
                            onChange = { list -> viewModel.setClipMasks(maskClip.id, list) },
                            onTrackMask = { mid ->
                                if (trackRunning) {
                                    scope.launch { snackbarHostState.showSnackbar("A track is already running") }
                                } else if (maskClip.uri == null || maskClip.type != LayerType.VIDEO) {
                                    scope.launch { snackbarHostState.showSnackbar("Tracking works on video layers") }
                                } else {
                                    closeMaskPanel()
                                    openTrackSetup(maskClip.id, maskClip.id, mid, false)
                                }
                            }
                        )
                    }
                }
            }

        if (showClipCropSheet) {
            val selectedClip = tracks.flatMap { it.clips }.find { it.id == selectedClipId }
                ?: tracks.flatMap { it.clips }.firstOrNull()

            androidx.compose.ui.window.Dialog(
                onDismissRequest = { showClipCropSheet = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                com.example.ui.components.CropView(
                    // The layer as it looks at the playhead (keyframes applied).
                    clip = selectedClip?.at(playheadState.value),
                    onToggleKeyframes = { props ->
                        selectedClip?.id?.let { viewModel.toggleKeyframes(it, props) }
                    },
                    onEasingBegin = { viewModel.beginAdjust() },
                    onEasingChange = { prop, kfTime, easing ->
                        selectedClip?.id?.let { viewModel.setKeyframeEasing(it, prop, kfTime, easing) }
                    },
                    aspectRatio = selectedAspectRatio,
                    currentPlayheadMs = playheadState.value,
                    totalDurationMs = totalDurationMs,
                    isPlaying = isPlaying,
                    nativePreview = nativePreview,
                    canUndo = canUndo,
                    canRedo = canRedo,
                    onTogglePlay = { viewModel.togglePlayPause() },
                    onSeekTo = { viewModel.seekTo(it) },
                    onUndo = { viewModel.undo(); com.example.ui.buddy.BuddyBrain.react(com.example.ui.buddy.BuddyEvent.UNDO) },
                    onRedo = { viewModel.redo(); com.example.ui.buddy.BuddyBrain.react(com.example.ui.buddy.BuddyEvent.REDO) },
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
                    modifier = Modifier
                        .fillMaxSize()
                        .background(EditorBackground)
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .enterPop(),
                    chrome = com.example.ui.components.CropChrome(
                        headerH = headerH,
                        canvasH = canvasH,
                        playControlsH = playControlsH,
                        timescaleH = timescaleH,
                        layerRow = { selectedClip?.id?.let { layerRow(it) } },
                        onFullScreen = { showFullscreenPlayer = true },
                        onResolution = { showResolutionSheet = true },
                        onPrevious = { viewModel.jumpPrevious() },
                        onNext = { viewModel.jumpNext() }
                    )
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
            originalAspect = originalAspect,
            onRatioSelected = { viewModel.setAspectRatio(it) },
            canvasColor = canvasColor,
            onCanvasColor = { viewModel.setCanvasColor(it) },
            resolution = exportSettings.resolution,
            onResolution = { viewModel.setExportResolution(it) },
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

    if (showProjects) {
        com.example.ui.components.ProjectsDialog(
            projects = projectList,
            currentId = projectId,
            onDismiss = { showProjects = false },
            onNew = {
                scope.launch {
                    saveProjectNow()
                    val m = withContext(Dispatchers.IO) { projectStore.create("Project") }
                    viewModel.loadProject(com.example.model.ProjectSnapshot(), m.id)
                    withContext(Dispatchers.IO) { projectStore.lastId = m.id }
                    showProjects = false
                }
            },
            onOpen = { id ->
                scope.launch {
                    if (id != viewModel.projectId.value) {
                        saveProjectNow()
                        val snap = withContext(Dispatchers.IO) { projectStore.load(id) }
                        if (snap == null) {
                            android.widget.Toast.makeText(context, "Cannot open this project", android.widget.Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        viewModel.loadProject(snap, id)
                        withContext(Dispatchers.IO) { projectStore.lastId = id }
                        reportMissingMedia(snap)
                    }
                    showProjects = false
                }
            },
            onRename = { id, name ->
                scope.launch {
                    projectList = withContext(Dispatchers.IO) {
                        projectStore.rename(id, name)
                        projectStore.list()
                    }
                }
            },
            onExport = { p ->
                pendingExportId = p.id
                val safe = p.name.replace(Regex("[^\\p{L}\\p{N} _.-]"), "_").trim().ifEmpty { "Project" }
                exportProjectLauncher.launch(safe + "." + com.example.engine.ProjectArchive.EXTENSION)
            },
            onImport = { importProjectLauncher.launch(arrayOf("*/*")) },
            onDelete = { id ->
                scope.launch {
                    if (id == viewModel.projectId.value) {
                        // Switch to a new project first, so the autosave can never bring the deleted one back.
                        val m = withContext(Dispatchers.IO) { projectStore.create("Project") }
                        viewModel.loadProject(com.example.model.ProjectSnapshot(), m.id)
                        withContext(Dispatchers.IO) { projectStore.lastId = m.id }
                    }
                    projectList = withContext(Dispatchers.IO) {
                        projectStore.delete(id)
                        projectStore.list()
                    }
                }
            }
        )
    }

    projectBusy?.let { msg ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            containerColor = androidx.compose.ui.graphics.Color(0xFF10161B),
            title = { androidx.compose.material3.Text(msg, color = androidx.compose.ui.graphics.Color.White) },
            text = {
                if (projectBusyProgress >= 0f) {
                    androidx.compose.material3.LinearProgressIndicator(progress = { projectBusyProgress })
                } else {
                    androidx.compose.material3.LinearProgressIndicator()
                }
            },
            confirmButton = {}
        )
    }

    if (showExportDialog) {
        ExportModalDialog(
            isExporting = isExporting,
            isCompleted = exportDone,
            progress = exportProgress,
            resolutionLabel = exportSettings.resolution,
            frameRate = exportSettings.frameRate,
            onResolutionChange = { viewModel.setExportResolution(it) },
            onFrameRateChange = { viewModel.setExportFrameRate(it) },
            quality = exportSettings.quality,
            onQualityChange = { viewModel.setExportQuality(it) },
            bitrateKbps = exportSettings.customBitrateKbps,
            onBitrateChange = { viewModel.setExportBitrate(it) },
            onStartExport = {
                exportDone = false
                exportedUri = null
                viewModel.pause()
                viewModel.setExporting(true)
                exporter.start(
                    tracks = tracks,
                    aspectRatio = selectedAspectRatio,
                    settings = exportSettings.copy(canvasColor = canvasColor),
                    previewFrameWidthPx = previewFrameWidthPx,
                    previewDensity = previewDensity,
                    onProgress = { viewModel.setExportProgress(it) },
                    onDone = { uri ->
                        viewModel.finishExport()
                        exportedUri = uri
                        exportDone = true
                        scope.launch { snackbarHostState.showSnackbar("Saved to Movies/After Motion") }
                    },
                    onError = { msg ->
                        viewModel.setExporting(false)
                        viewModel.setExportProgress(0f)
                        showExportDialog = false
                        com.example.ui.buddy.BuddyBrain.react(com.example.ui.buddy.BuddyEvent.EXPORT_FAIL)
                        scope.launch { snackbarHostState.showSnackbar("Export failed: $msg") }
                    }
                )
            },
            onCancel = {
                exporter.cancel()
                viewModel.setExporting(false)
                viewModel.setExportProgress(0f)
                exportDone = false
                showExportDialog = false
            },
            onDismiss = {
                exportDone = false
                exportedUri = null
                viewModel.setExportProgress(0f)
                showExportDialog = false
            },
            onShare = {
                exportedUri?.let { u ->
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, u)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Share video").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
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
            },
            onDismiss = { showAddTextSheet = false }
        )
    }

    if (showStickerSpeedSheet) {
        val stickerClip = tracks.flatMap { it.clips }.find { it.id == selectedClipId }
        if (stickerClip != null && stickerClip.type == LayerType.STICKER) {
            StickerSpeedSheet(
                clip = stickerClip,
                playheadMs = playheadState.value,
                onSpeedChange = { viewModel.setClipSpeed(stickerClip.id, it) },
                onLiveSpeed = { v -> viewModel.updateClipLive(stickerClip.id) { it.copy(speed = v) } },
                onToggleKeyframes = { props -> viewModel.toggleKeyframes(stickerClip.id, props) },
                onEasingBegin = { viewModel.beginAdjust() },
                onEasingChange = { prop, kfTime, easing ->
                    viewModel.setKeyframeEasing(stickerClip.id, prop, kfTime, easing)
                },
                onDismiss = { showStickerSpeedSheet = false }
            )
        }
    }

    if (showAddLayerSheet) {
        com.example.ui.components.AddLayerSheet(
            onMedia = {
                mediaPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                )
            },
            onText = {
                // New text layer at the playhead, opened straight in Text edit.
                val remaining = totalDurationMs - playheadState.value
                val id = viewModel.addTextLayer(
                    if (totalDurationMs > 0L && remaining >= 1000L) minOf(4000L, remaining) else 4000L
                )
                if (id.isNotEmpty()) editingTextId = id
            },
            onShape = { showAddShapeSheet = true },
            onAudio = { audioPicker.launch(arrayOf("audio/*")) },
            onSticker = { showStickerSheet = true },
            onDismiss = { showAddLayerSheet = false }
        )
    }

    // Parent picker: choose the Null object a layer follows.
    parentSheetClipId?.let { childId ->
        val all = tracks.flatMap { it.clips }
        val child = all.firstOrNull { it.id == childId }
        if (child == null) {
            parentSheetClipId = null
        } else {
            // A Null that has this layer as (grand)parent would make a loop: not offered.
            fun loops(nullId: String): Boolean {
                var cur: String? = nullId
                var n = 0
                while (cur != null && n++ < 12) {
                    if (cur == childId) return true
                    cur = all.firstOrNull { it.id == cur }?.parent?.id
                }
                return false
            }
            com.example.ui.components.ParentSheet(
                layerName = child.text?.takeIf { it.isNotBlank() } ?: child.name,
                nulls = all.filter { it.type == LayerType.NULL && it.id != childId && !loops(it.id) }.map { it.id to it.name },
                currentId = child.parent?.id,
                onPick = { pid ->
                    if (!viewModel.setClipParent(childId, pid)) {
                        scope.launch { snackbarHostState.showSnackbar("This Null cannot be the parent") }
                    }
                    parentSheetClipId = null
                },
                onDismiss = { parentSheetClipId = null }
            )
        }
    }

    if (showStickerSheet) {
        StickerPickerSheet(
            onPick = { item ->
                // Sticker at the playhead, up to 4 s (or what is left of the timeline).
                val remaining = totalDurationMs - playheadState.value
                val dur = if (totalDurationMs > 0L && remaining >= 1000L) minOf(4000L, remaining) else 4000L
                viewModel.addSticker(item.assetPath, item.name, dur)
            },
            onDismiss = { showStickerSheet = false }
        )
    }

    if (showAddShapeSheet) {
        AddShapeSheet(
            onAddShape = { name, pattern ->
                val clipDuration = if (totalDurationMs > 0L && playheadState.value < totalDurationMs) {
                    minOf(4000L, (totalDurationMs - playheadState.value).coerceAtLeast(1000L))
                } else {
                    4000L
                }
                viewModel.addShape(
                    name = name,
                    pattern = pattern,
                    durationMs = clipDuration
                )
            },
            onDismiss = { showAddShapeSheet = false }
        )
    }

    if (showFullscreenPlayer) {
        FullscreenPlayerModal(
            aspectRatio = selectedAspectRatio,
            currentPlayheadMs = playheadState.value,
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
                currentPlayheadMs = playheadState.value,
                totalDurationMs = totalDurationMs,
                isPlaying = isPlaying,
                mediaLayers = editorMediaLayers,
                nativePreview = nativePreview,
                onTogglePlay = { viewModel.togglePlayPause() },
                onSeekTo = { viewModel.seekTo(it) },
                onDismiss = { showEditShapeMode = false },
                onSaveShape = { shapeType, w, h, color, feather, offX, offY, strokeW, strokeCol ->
                    val clipDuration = if (totalDurationMs > 0L && playheadState.value < totalDurationMs) {
                        minOf(4000L, (totalDurationMs - playheadState.value).coerceAtLeast(1000L))
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
                },
                modifier = Modifier.fillMaxSize().enterPop()
            )
        }
    }

    if (showClipReplaceSheet) {
        ClipReplaceSheet(
            onMediaSelected = { res, name ->
                viewModel.replaceSelectedClipMedia(res, name)
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
            },
            onDismiss = { showClipBlendSheet = false }
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
                },
                modifier = Modifier.fillMaxSize().enterPop()
            )
        }
    }


}
