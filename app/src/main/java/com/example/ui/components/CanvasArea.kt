package com.example.ui.components

import com.example.model.sourceOffsetMs
import com.example.engine.StickerLibrary
import com.example.model.TimelineClip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput as mediaPointerInput
import androidx.compose.ui.layout.onSizeChanged as mediaOnSizeChanged
import androidx.compose.runtime.mutableFloatStateOf as mediaMutableFloatStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState as mediaRememberUpdatedState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AspectRatio
import com.example.model.CanvasSnap
import com.example.model.CropVisibleBox
import com.example.model.cropVisibleBox
import com.example.model.LayerType
import com.example.model.SnapRect
import com.example.model.at
import com.example.model.renderAt
import com.example.model.TimelineTrack
import com.example.model.hasBlending
import com.example.model.opacityFraction
import com.example.model.effectiveScalePercent
import com.example.model.depthFactor
import com.example.model.depthX
import com.example.model.depthY
import com.example.model.isBehindCamera
import com.example.ui.util.layerBlend
import com.example.ui.theme.CanvasBackground
import com.example.ui.theme.LocalCanvasColor
import com.example.ui.theme.EditorBackground
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen

@Composable
fun CanvasArea(
    /** Fixed height of the canvas strip. Null = take the full height the parent offers (the fullscreen player). */
    height: Dp? = null,
    aspectRatio: AspectRatio,
    playheadMs: () -> Long,
    isPlaying: Boolean,
    tracks: List<TimelineTrack>,
    /**
     * Every layer of the project, for the C++ picture. [tracks] is only the level being edited (Edit Group): what can be
     * touched and selected on the canvas. A group is drawn as ONE picture from [nativeTracks].
     */
    nativeTracks: List<TimelineTrack> = tracks,
    selectedClipId: String? = null,
    onTogglePlayPause: () -> Unit,
    onUpdateClipTransform: (clipId: String, offsetX: Float, offsetY: Float) -> Unit = { _, _, _ -> },
    onClipSelect: (clipId: String) -> Unit = {},
    onTextDoubleTap: (clipId: String) -> Unit = {},
    /**
     * Build 225: the stored form a canvas move WOULD write (the ViewModel's bake) —
     * the drag preview draws the dragged layer from it while a drag runs; the real
     * commit at drag end still goes through [onUpdateClipTransform]. Default = no
     * bake (the fullscreen player, where drags are inert as before).
     */
    onDragBake: (clip: TimelineClip, offsetX: Float, offsetY: Float) -> TimelineClip = { c, _, _ -> c },
    /**
     * Build 225: the fixed full-canvas drag layer for text / shape layers is live
     * only where the caller wires real drag callbacks (the editor). The fullscreen
     * player leaves it off — its build-224 handlers wrote to no-op callbacks, so
     * layers there never moved, selected or opened; with the layer off they don't.
     */
    canvasDragEnabled: Boolean = false,
    /** Layer whose Color editor is open: its two gradient points are shown on the canvas. */
    gradientEditId: String? = null,
    onGradientPoints: (id: String, x1: Float, y1: Float, x2: Float, y2: Float) -> Unit = { _, _, _, _, _ -> },
    /** Bounding box handles: called once when a drag starts, then with the new scale / rotation. */
    onSelectionBegin: () -> Unit = {},
    onSelectionTransform: (id: String, scalePercent: Float, rotationDeg: Float) -> Unit = { _, _, _ -> },
    /** Real size of the canvas frame on screen (width in px + screen density): the export scales shapes / text / moves by it. */
    onPreviewFrame: (widthPx: Float, density: Float) -> Unit = { _, _ -> },
    /** Drawn on top of the canvas frame (same size as the frame), e.g. the motion-tracking circle. */
    frameOverlay: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null,
    /** The C++ engine that draws the video / photo layers (and text / shapes / stickers). */
    nativePreview: com.example.engine.NativePreviewEngine? = null,
    /** True while an on-canvas editor (Masks) is open: the transform box of the selected layer is hidden. */
    selectionHidden: Boolean = false,
    /** Build 216: the workspace row's grid toggle — white 24% thirds lines over the stage. View-only. */
    showGrid: Boolean = false,
    /** Build 199 (A3): the editor's snap switch — off, canvas drags write the raw position and no snap guides show. */
    snapEnabled: Boolean = true,
    /**
     * Measured text-layer boxes, shared with the caller (build 184): the Transform panel's
     * snapper reads the same measurements this area's own snap uses. Null = this area keeps
     * a private local map (the fullscreen player's behaviour, unchanged).
     */
    sharedTextSizes: MutableMap<String, androidx.compose.ui.geometry.Size>? = null,
    /**
     * Snap guide lines owned by the caller (build 184: the Transform panel's PositionPad
     * snap), in canvas pixels like this area's own guides; drawn with the same hairlines.
     */
    externalSnapGuideX: Float? = null,
    externalSnapGuideY: Float? = null,
    /**
     * Onion-skin ghosts (build 190): the settings plus the project frame rate and
     * duration the engine's ghost plan steps by. Handed to the preview engine with
     * every update; the ghosts themselves are drawn by the engine UNDER the real
     * frame, so every overlay in this area (selection, handles, guides) stays
     * above them exactly as today.
     */
    onionSkin: com.example.model.OnionSkinSettings = com.example.model.OnionSkinSettings(),
    onionFps: Int = 30,
    onionDurationMs: Long = 0L,
    modifier: Modifier = Modifier
) {
    val canvasColor = LocalCanvasColor.current
    val canvasArgb = canvasColor.toArgb()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (height != null) Modifier.height(height) else Modifier.fillMaxHeight())
            .background(com.example.ui.theme.AmBaseDeep) // build 216: the stage surround stays BLACK
            .testTag("canvas_preview_area"),
        contentAlignment = Alignment.Center
    ) {
        // Aspect-ratio bounding box for the player
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 0.dp), // build 217: the reference's preview gutter
            contentAlignment = Alignment.Center
        ) {
            val maxW = maxWidth
            val maxH = maxHeight
            val targetRatio = aspectRatio.ratioFloat

            // Build 261: the fit is THE law (model/FrameFit, host-pinned) —
            // the largest rect of the project's aspect inside this area,
            // centred by the BoxWithConstraints alignment below. The C++
            // preview treats the frame's surface as the canvas, so this
            // rect's aspect IS the picture's aspect: letterbox, never stretch.
            val frameFit = remember(maxW, maxH, targetRatio) {
                com.example.model.FrameFit.fit(maxW.value, maxH.value, targetRatio)
            }
            val frameW = frameFit.w.dp
            val frameH = frameFit.h.dp

            val frameDensity = androidx.compose.ui.platform.LocalDensity.current
            LaunchedEffect(frameW, frameH) {
                onPreviewFrame(with(frameDensity) { frameW.toPx() }, frameDensity.density)
            }
            // Build 225: the drag preview state. While a text / shape drag runs,
            // the dragged layer is DISPLAYED from the bake of the proposed position
            // (the stored form the end commit will write — see bakeIfLive and the
            // engine tracks inside the frame); the project state itself is written
            // once, at drag end, so a drag no longer rewrites the whole project
            // (keyframe commit + Controller fan-out + a full recomposition of
            // everything that reads the tracks) on every pointer event.
            val dragBakeNow by mediaRememberUpdatedState(onDragBake)
            var liveDrag by remember { mutableStateOf<LiveDragPos?>(null) }
            val bakeIfLive: (TimelineClip) -> TimelineClip = { clip ->
                val ld = liveDrag
                if (ld != null && clip.id == ld.clipId) dragBakeNow(clip, ld.x, ld.y) else clip
            }
            Box(
                modifier = Modifier
                    .size(width = frameW, height = frameH)
                    .clip(RoundedCornerShape(16.dp)) // build 276: the fresh design's canvas card (was 6 dp, build 217)
                    .background(canvasColor)
                    .border(1.dp, Color(0xFF2B3644), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                val anyActiveClip by remember(tracks) {
                    derivedStateOf {
                        val t = playheadMs()
                        tracks.any { tr -> tr.clips.any { it.isActiveAt(t) } }
                    }
                }
                // Media layers (videos and pictures) that are on the canvas now, bottom lane first.
                val activeMediaClips by remember(tracks, isPlaying) {
                    derivedStateOf {
                        val t = playheadMs()
                        tracks
                            .asReversed()
                            .flatMap { it.clips }
                            .filter {
                                it.type == LayerType.VIDEO && it.isVisible && it.uri != null && it.isActiveAt(t)
                            }
                            .map { com.example.engine.NativeRenderAt.renderAt(it, t, t) }
                            // Depth: a layer at / behind the camera is gone; the larger the Z the nearer (drawn later).
                            .filter { !it.isBehindCamera }
                            .sortedBy { it.transformZ }
                    }
                }
                // Build 225: while a drag runs, the engine draws the dragged layer
                // from its baked form (the stored form the end commit will write);
                // every other layer is handed over exactly as stored.
                val ldNow = liveDrag
                val engineTracks = if (ldNow == null) nativeTracks else nativeTracks.map { tr ->
                    if (tr.clips.none { it.id == ldNow.clipId }) tr
                    else tr.copy(clips = tr.clips.map { c -> if (c.id == ldNow.clipId) dragBakeNow(c, ldNow.x, ldNow.y) else c })
                }
                if (nativePreview != null) {
                    NativePreviewSurface(
                        engine = nativePreview,
                        tracks = engineTracks,
                        playheadMs = playheadMs,
                        isPlaying = isPlaying,
                        canvasArgb = canvasArgb,
                        modifier = Modifier.fillMaxSize(),
                        onionSkin = onionSkin,
                        onionFps = onionFps,
                        onionDurationMs = onionDurationMs
                    )
                }                // ---- Canvas snapping (build 176, Sarowar's tool 3 from the audit) ----
                // Every drag path below (media, shape, sticker, text) reports the layer's
                // proposed STORED position to snappedMove instead of onUpdateClipTransform:
                // the move is snapped in canvas pixels (the pure rule is model/CanvasSnap)
                // and the SNAPPED position is what gets written. Snapping the drag of a
                // Controller moves the Controller's own handle the same way — the fan-out
                // in the ViewModel then works from the snapped position. Multi-layer drag
                // does not exist on this canvas (drags are one layer), so there is nothing
                // else to snap by. Guide lines are flat white hairlines (the B/W language).
                val fwPx = with(frameDensity) { frameW.toPx() }
                val fhPx = with(frameDensity) { frameH.toPx() }
                // Build 184: the caller may share its measured text boxes (sharedTextSizes)
                // so the Transform panel's snapper measures text layers exactly like the
                // canvas drag below; without one this area keeps its own local map.
                val localTextSizes = remember { androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.ui.geometry.Size>() }
                val textSizes = sharedTextSizes ?: localTextSizes
                var snapGuideX by remember { mutableStateOf<Float?>(null) }
                var snapGuideY by remember { mutableStateOf<Float?>(null) }
                val snapTracksNow by mediaRememberUpdatedState(tracks)
                val snapSizesNow by mediaRememberUpdatedState(textSizes)
                val snapEnabledNow by mediaRememberUpdatedState(snapEnabled)
                val clearSnapGuides: () -> Unit = { snapGuideX = null; snapGuideY = null }
                // Build 225: the snap decision, split from the write. snappedMove keeps
                // its build-224 shape (decide + write in one call) for the media and
                // sticker paths; the text / shape drag layer decides per move event
                // through computeSnapped and writes ONCE at drag end. [othersClips]
                // lets that layer hand in the snap targets as they stand mid-drag
                // (see overlayLiveMove); null = measure them from the project as-is.
                val computeSnapped: (String, Float, Float, List<TimelineClip>?) -> Pair<Float, Float> = { id, sx, sy, othersClips ->
                    var outX = sx
                    var outY = sy
                    var gx: Float? = null
                    var gy: Float? = null
                    if (snapEnabledNow) {
                    val t = playheadMs()
                    val clipsNow = snapTracksNow.asReversed().flatMap { it.clips }
                        .filter { it.isVisible && !it.isMask && it.isActiveAt(t) && !it.isBehindCamera }
                        .map { com.example.engine.NativeRenderAt.renderAt(it, t, t) }
                    val dragged = clipsNow.firstOrNull { it.id == id }
                    if (dragged != null) {
                        val moved = dragged.copy(transformX = sx, transformY = sy, shapeOffsetX = sx, shapeOffsetY = sy)
                        val rect = snapRectOf(moved, fwPx, fhPx, frameDensity.density, snapSizesNow, forDragged = true)
                        if (rect != null) {
                            val others = (othersClips ?: clipsNow.filter { it.id != id })
                                .mapNotNull { snapRectOf(it, fwPx, fhPx, frameDensity.density, snapSizesNow, forDragged = false) }
                            val res = CanvasSnap.snap(rect, fwPx, fhPx, others, 8f * frameDensity.density)
                            // Canvas px -> stored units: the stored position is drawn times the depth factor.
                            val df = dragged.depthFactor.coerceAtLeast(0.001f)
                            outX = sx + res.dx / df
                            outY = sy + res.dy / df
                            gx = res.guideX
                            gy = res.guideY
                        }
                    }
                    }
                    snapGuideX = gx
                    snapGuideY = gy
                    outX to outY
                }
                val snappedMove: (String, Float, Float) -> Unit = { id, sx, sy ->
                    val (outX, outY) = computeSnapped(id, sx, sy, null)
                    onUpdateClipTransform(id, outX, outY)
                }
                val overlayLiveMove: (String, Float, Float) -> Pair<Float, Float> = { id, sx, sy ->
                    // A Controller drag in build 224 fanned its connected layers out
                    // per event, so the snap targets stood at their fanned positions
                    // (as of the previous event). Reproduce that stand for the snap
                    // decision: the targets fanned by the drag's previous position.
                    var others: List<TimelineClip>? = null
                    val prev = liveDrag
                    if (prev != null && prev.clipId == id && snapEnabledNow) {
                        val t = playheadMs()
                        val clipsNow = snapTracksNow.asReversed().flatMap { it.clips }
                            .filter { it.isVisible && !it.isMask && it.isActiveAt(t) && !it.isBehindCamera }
                            .map { com.example.engine.NativeRenderAt.renderAt(it, t, t) }
                        val dragged = clipsNow.firstOrNull { it.id == id }
                        if (dragged != null && dragged.type == LayerType.CONTROLLER) {
                            val prevAfter = dragged.copy(transformX = prev.x, transformY = prev.y, shapeOffsetX = prev.x, shapeOffsetY = prev.y)
                            others = clipsNow.filter { it.id != id }
                                .map { com.example.model.ControllerFanOut.fanned(it, dragged, prevAfter, t) }
                        }
                    }
                    val snapped = computeSnapped(id, sx, sy, others)
                    liveDrag = LiveDragPos(id, snapped.first, snapped.second)
                    snapped
                }
                val overlayCommit: (String, Float, Float) -> Unit = { id, x, y ->
                    liveDrag = null
                    onUpdateClipTransform(id, x, y)
                    clearSnapGuides()
                }
                if (activeMediaClips.isNotEmpty()) {
                    // Tap a picture / video to select it, drag to move it (like text and shapes).
                    MediaGestureLayer(
                        clips = activeMediaClips,
                        selectedId = selectedClipId,
                        onSelect = onClipSelect,
                        onBegin = onSelectionBegin,
                        onMove = snappedMove,
                        onDragEnd = clearSnapGuides,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (activeMediaClips.isEmpty() && !anyActiveClip) {
                    // Clean Alight Motion style canvas with subtle center indicator.
                    // A truly empty project (no layers at all — a first run, or everything deleted)
                    // also gets a one-line hint; a mere gap between layers of a real project does not.
                    val projectEmpty = nativeTracks.all { it.clips.isEmpty() }
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            NativeVectorCanvas(
                                modifier = Modifier.size(24.dp),
                                drawKey = 0,
                                buildCmds = { w, h, d ->
                                    val dotColor = 0x40FFFFFF
                                    addCircle(w * 0.5f, h * 0.5f, 2f * d, dotColor)
                                    addLine(w * 0.5f, 0f, w * 0.5f, h, 1f * d, dotColor)
                                    addLine(0f, h * 0.5f, w, h * 0.5f, 1f * d, dotColor)
                                },
                            )
                            if (projectEmpty) {
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    "No layers yet",
                                    color = TextSecondary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Tap Add below to add media, text or a shape",
                                    color = TextSecondary.copy(alpha = 0.75f),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }

                // Render dynamic text or sticker overlays if active at current playhead
                // Lane order = stacking order: the bottom lane is drawn first, the top lane last.
                val activeOverlayClips by remember(tracks, isPlaying) {
                    derivedStateOf {
                        val t = playheadMs()
                        tracks
                            .asReversed()
                            .flatMap { it.clips }
                            .filter { it.isVisible && !it.isMask && (it.type == LayerType.SHAPE || it.type == LayerType.NULL || it.type == LayerType.GROUP || it.type == LayerType.CONTROLLER || it.type == LayerType.STICKER || it.text != null) && it.isActiveAt(t) }
                            .map { com.example.engine.NativeRenderAt.renderAt(bakeIfLive(it), t, t) }
                            .filter { !it.isBehindCamera }
                            .sortedBy { it.transformZ }
                    }
                }

                activeOverlayClips.forEach { clip ->
                // Every layer sits in a full-canvas box that applies its opacity / blend mode.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBlend(clip.blendMode, clip.opacityFraction),
                    contentAlignment = Alignment.Center
                ) {
                  if (clip.type == LayerType.STICKER) {
                    StickerLayerOnCanvas(
                        clip = clip,
                        timeMs = playheadMs(),
                        isSelected = clip.id == selectedClipId,
                        onMove = snappedMove,
                        onDragEnd = clearSnapGuides,
                        onTap = onClipSelect,
                        hideContent = com.example.engine.nativeShapeEligible(clip),
                        modifier = Modifier.align(Alignment.Center)
                    )
                  } else if (clip.type != LayerType.SHAPE && clip.type != LayerType.NULL && clip.type != LayerType.GROUP && clip.type != LayerType.CONTROLLER) {
                    TextLayerOnCanvas(
                        clip = clip,
                        frameWidth = frameW,
                        isSelected = clip.id == selectedClipId,
                        hideContent = com.example.engine.nativeTextEligible(clip),
                        modifier = Modifier.align(Alignment.Center),
                        onLayoutSize = { id, w, h -> textSizes[id] = androidx.compose.ui.geometry.Size(w, h) },
                        overlay = {
                            if (clip.id == gradientEditId && clip.fillMode == com.example.model.FILL_GRADIENT) {
                                GradientHandleLayer(
                                    clip = clip,
                                    onPoints = onGradientPoints,
                                    modifier = Modifier.matchParentSize()
                                )
                            }
                        }
                    )
                  } else {
                    val effectiveTransX = clip.depthX
                    val effectiveTransY = clip.depthY
                    val effectiveScale = clip.effectiveScalePercent / 100f
                    val baseSize = 130.dp

                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .graphicsLayer {
                                translationX = effectiveTransX
                                translationY = effectiveTransY
                                scaleX = effectiveScale.coerceAtLeast(0.01f) * (if (clip.isFlippedH) -1f else 1f)
                                scaleY = effectiveScale.coerceAtLeast(0.01f) * (if (clip.isFlippedV) -1f else 1f)
                                rotationZ = clip.rotationDeg
                                rotationX = clip.rotation3dX
                                rotationY = clip.rotation3dY
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(clip.pivotX, clip.pivotY)
                                cameraDistance = 12f * density
                            }
                            .size(baseSize)
                    ) {
                        // Build 225: this box only DRAWS now (the marker below and the
                        // gradient handles). The drag that used to live on an inner
                        // touch box here — hugging the crop-visible window (build 186)
                        // — is measured by the fixed full-canvas CanvasDragLayer,
                        // whose hit test hugs the same crop-visible box in canvas
                        // space: a drag starting on the cropped-away part still
                        // falls through to whatever is beneath, as if that part
                        // were not there.
                        // The shape picture is drawn by the C++ engine (NativePreviewEngine).
                        // Helper layers have no picture: a GROUP shows this editing marker (dashed box + cross),
                        // never exported. A Null / Controller shows NOTHING while unselected (build 155 —
                        // Sarowar's display correction, CanvasMarkRule); selected, the white bounding box
                        // below (SelectionFrameOverlay) is all that appears, like for any other layer.
                        if (com.example.model.CanvasMarkRule.markFor(clip.type, clip.id == selectedClipId) == com.example.model.CanvasMark.MARKER) {
                            val markArgb = when (clip.type) {
                                LayerType.GROUP -> 0xFF5BB85D.toInt()
                                LayerType.CONTROLLER -> 0xFF00B8A9.toInt()
                                else -> 0xFFE8A33D.toInt()
                            }
                            NativeVectorCanvas(
                                modifier = Modifier.matchParentSize(),
                                drawKey = clip.type,
                                buildCmds = { w, h, d ->
                                    val sw = 1.5f * d
                                    val xs = floatArrayOf(0f, w, w, 0f)
                                    val ys = floatArrayOf(0f, 0f, h, h)
                                    addPolyline(xs, ys, 4, closed = true, width = sw, argb = markArgb, dashOn = 14f, dashOff = 10f)
                                    val cx = w * 0.5f
                                    val cy = h * 0.5f
                                    val arm = 14f * d
                                    addLine(cx - arm, cy, cx + arm, cy, sw * 1.4f, markArgb)
                                    addLine(cx, cy - arm, cx, cy + arm, sw * 1.4f, markArgb)
                                    addCircle(cx, cy, 3f * d, markArgb)
                                },
                            )
                        }

                        if (clip.id == gradientEditId && clip.fillMode == com.example.model.FILL_GRADIENT) {
                            GradientHandleLayer(clip = clip, onPoints = onGradientPoints)
                        }

                    }
                }
                }
                }

                // Build 225: the fixed full-canvas gesture layer for the text /
                // shape-family layers drawn above (below the selection frame, so
                // its handles keep priority through consumption — see
                // CanvasDragLayer). Off where the caller wires no real drag
                // callbacks (the fullscreen player).
                if (canvasDragEnabled && activeOverlayClips.isNotEmpty()) {
                    CanvasDragLayer(
                        clips = activeOverlayClips,
                        textSizes = textSizes,
                        onSelect = onClipSelect,
                        onDoubleTap = onTextDoubleTap,
                        onMove = overlayLiveMove,
                        onCommit = overlayCommit,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Bounding box of the selected shape / text layer (Alight Motion style).
                val selOverlay = activeOverlayClips.find { it.id == selectedClipId }
                if (selOverlay != null && selOverlay.id != gradientEditId) {
                    val dens = androidx.compose.ui.platform.LocalDensity.current.density
                    val isShape = selOverlay.type == LayerType.SHAPE || selOverlay.type == LayerType.STICKER || selOverlay.type == LayerType.NULL || selOverlay.type == LayerType.GROUP || selOverlay.type == LayerType.CONTROLLER
                    val size = if (isShape) androidx.compose.ui.geometry.Size(130f * dens, 130f * dens)
                    else textSizes[selOverlay.id]
                    // Crop clips a shape / text layer's own box in place (the layer is NOT resized
                    // or re-centred), so the frame hugs the visible cropped part, not the pre-crop
                    // box. Stickers are drawn uncropped, and Null / Group / Controller have no
                    // cropped picture — their box stays the whole box, as before.
                    val vis = size?.let {
                        if (selOverlay.type == LayerType.SHAPE || selOverlay.text != null) {
                            selOverlay.cropVisibleBox(it.width, it.height)
                        } else {
                            CropVisibleBox(it.width, it.height, 0f, 0f)
                        }
                    }
                    if (size != null && vis != null && size.width > 0f && size.height > 0f) {
                        SelectionFrameOverlay(
                            clip = selOverlay,
                            layerWidthPx = size.width,
                            layerHeightPx = size.height,
                            minScale = 0.01f,
                            onBegin = onSelectionBegin,
                            onTransform = onSelectionTransform,
                            modifier = Modifier.fillMaxSize(),
                            visW = vis.width,
                            visH = vis.height,
                            visOffX = vis.offX,
                            visOffY = vis.offY
                        )
                    }
                }

                // 3D camera selected: viewfinder corners on the canvas (the camera itself has no picture, see CameraRig).
                if (tracks.any { it.clips.any { c -> c.id == selectedClipId && c.type == LayerType.CAMERA } }) {
                    NativeVectorCanvas(
                        modifier = Modifier.fillMaxSize(),
                        drawKey = 0,
                        buildCmds = { w, h, d ->
                            val col = 0xFF8E6BF2.toInt()
                            val sw = 2.5f * d
                            val m = 10f * d
                            val a = 26f * d
                            fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                                addLine(x, y, x + dx * a, y, sw, col)
                                addLine(x, y, x, y + dy * a, sw, col)
                            }
                            corner(m, m, 1f, 1f); corner(w - m, m, -1f, 1f); corner(m, h - m, 1f, -1f); corner(w - m, h - m, -1f, -1f)
                            addCircle(w * 0.5f, h * 0.5f, 3f * d, col)
                        },
                    )
                }

                // Main Track Framing Box & Center Crosshair Target
                val selectedClipType = tracks.flatMap { it.clips }.firstOrNull { it.id == selectedClipId }?.type
                val isMainTrackSelected = selectedClipType == LayerType.VIDEO
                if (isMainTrackSelected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        NativeVectorCanvas(
                            modifier = Modifier.size(26.dp),
                            drawKey = 0,
                            buildCmds = { w, h, d ->
                                val greenNeon = 0xFF00E676.toInt()
                                val sw = 2f * d
                                val cX = w * 0.5f
                                val cY = h * 0.5f
                                val arm = 7f * d
                                addCircle(cX, cY, 2f * d, greenNeon)
                                addLine(cX, 0f, cX, arm, sw, greenNeon)
                                addLine(cX, h - arm, cX, h, sw, greenNeon)
                                addLine(0f, cY, arm, cY, sw, greenNeon)
                                addLine(w - arm, cY, w, cY, sw, greenNeon)
                            },
                        )
                    }
                }

                // Snap guide lines (build 176): flat white hairlines, shown only while a
                // drag is snapped (the B/W language — no colour, no glow). Build 184: the
                // Transform panel's PositionPad snap draws its guides through the same lines
                // (externalSnapGuideX/Y, owned by the screen).
                if (snapGuideX != null || snapGuideY != null || externalSnapGuideX != null || externalSnapGuideY != null) {
                    NativeVectorCanvas(
                        modifier = Modifier.fillMaxSize(),
                        drawKey = listOf(snapGuideX, snapGuideY, externalSnapGuideX, externalSnapGuideY),
                        buildCmds = { w, h, d ->
                            val col = 0xD9FFFFFF.toInt()
                            val sw = 1f * d
                            snapGuideX?.let { x -> addLine(x, 0f, x, h, sw, col) }
                            snapGuideY?.let { y -> addLine(0f, y, w, y, sw, col) }
                            externalSnapGuideX?.let { x -> addLine(x, 0f, x, h, sw, col) }
                            externalSnapGuideY?.let { y -> addLine(0f, y, w, y, sw, col) }
                        },
                    )
                }

            }

            // Build 216: the thirds grid (workspace toggle) — white 24%
            // 1 px lines over the stage, under every selection overlay.
            if (showGrid) {
                NativeVectorCanvas(
                    modifier = Modifier.size(width = frameW, height = frameH),
                    drawKey = 0,
                    buildCmds = { w, h, d ->
                        val col = 0x3DFFFFFF
                        val lw = 1f * d
                        val tx = w / 3f
                        val ty = h / 3f
                        addLine(tx, 0f, tx, h, lw, col)
                        addLine(tx * 2f, 0f, tx * 2f, h, lw, col)
                        addLine(0f, ty, w, ty, lw, col)
                        addLine(0f, ty * 2f, w, ty * 2f, lw, col)
                    },
                )
            }

            // Bounding box + corner / rotate handles of the selected picture / video layer.
            // Drawn outside the clipped canvas, so handles at the canvas edge can still be grabbed.
            val selMedia by remember(tracks, selectedClipId, isPlaying) {
                derivedStateOf {
                    val t = playheadMs()
                    tracks.flatMap { it.clips }.firstOrNull {
                        it.id == selectedClipId && it.type == LayerType.VIDEO &&
                            it.isVisible && it.uri != null && it.isActiveAt(t)
                    }?.let { com.example.engine.NativeRenderAt.renderAt(bakeIfLive(it), t, t) }
                }
            }
            val mediaSel = selMedia
            if (mediaSel != null && mediaSel.id != gradientEditId && !selectionHidden) {
                val d = androidx.compose.ui.platform.LocalDensity.current
                val fwPx = with(d) { frameW.toPx() }
                val fhPx = with(d) { frameH.toPx() }
                val box = mediaVisibleSize(mediaSel, fwPx, fhPx)
                SelectionFrameOverlay(
                    // Flip mirrors the picture inside its box, so the box itself stays the same.
                    clip = mediaSel.copy(isFlippedH = false, isFlippedV = false),
                    layerWidthPx = box.width,
                    layerHeightPx = box.height,
                    minScale = 0.01f,
                    onBegin = onSelectionBegin,
                    onTransform = onSelectionTransform,
                    modifier = Modifier.fillMaxSize(),
                    pivotRefW = fwPx,
                    pivotRefH = fhPx
                )
            }
            if (frameOverlay != null) {
                Box(modifier = Modifier.size(width = frameW, height = frameH)) { this.frameOverlay() }
            }
        }
    }
}

/** Build 225: an in-flight canvas drag's latest applied (snapped) stored position. */
private data class LiveDragPos(val clipId: String, val x: Float, val y: Float)

/** Size (px) of the visible part of a media layer: the picture fitted into the canvas, after crop. */
internal fun mediaVisibleSize(mc: com.example.model.TimelineClip, fw: Float, fh: Float): androidx.compose.ui.geometry.Size {
    val cW = (mc.cropWidthPercent / 100f).coerceIn(0.05f, 1f)
    val cH = (mc.cropHeightPercent / 100f).coerceIn(0.05f, 1f)
    val cropActive = cW < 0.999f || cH < 0.999f
    val a = if (mc.sourceAspect > 0f) mc.sourceAspect else fw / fh
    val contentW = if (a > fw / fh) fw else fh * a
    val contentH = if (a > fw / fh) fw / a else fh
    if (!cropActive) return androidx.compose.ui.geometry.Size(contentW, contentH)
    val s = minOf(fw / (cW * contentW), fh / (cH * contentH))
    return androidx.compose.ui.geometry.Size(cW * contentW * s, cH * contentH * s)
}

/**
 * Where a layer sits on the canvas for snapping (build 176): its axis-aligned bounds
 * (the four rotated corners) and centre, in canvas pixels — the same [FrameGeo] geometry
 * the selection frame uses. Media layers measure their visible (fitted, cropped) picture
 * with the pivot on the whole canvas; shape layers their 130 dp box FOLLOWING the crop
 * window (the painters clip the box in place, so a cropped shape offers no phantom snap
 * lines); sticker / group layers their 130 dp box; text its measured box, also following
 * its crop window. A dragged Null / Controller / Camera (no picture) snaps by its centre
 * only; as a snap TARGET those types (and anything without a measurable picture) return
 * null and are skipped.
 *
 * Internal (not private) since build 184: the Transform panel's PositionPad snapper in
 * VideoEditorScreen measures layers through this exact function, so a panel move snaps
 * against the same rects (cropped bounds included) as a canvas drag.
 */
internal fun snapRectOf(
    clip: TimelineClip,
    fw: Float,
    fh: Float,
    density: Float,
    textSizes: Map<String, androidx.compose.ui.geometry.Size>,
    forDragged: Boolean
): SnapRect? {
    val w: Float
    val h: Float
    val prW: Float
    val prH: Float
    // The visible (cropped) part of the layer's box, where crop clips the box in place.
    // Null = the whole box is the visible part (media already measures its cropped size).
    var vis: CropVisibleBox? = null
    when {
        clip.type == LayerType.VIDEO && clip.uri != null -> {
            val s = mediaVisibleSize(clip, fw, fh)
            w = s.width; h = s.height; prW = fw; prH = fh
        }
        clip.type == LayerType.SHAPE -> {
            val s = 130f * density
            w = s; h = s; prW = 0f; prH = 0f
            vis = clip.cropVisibleBox(w, h)
        }
        clip.type == LayerType.STICKER || clip.type == LayerType.GROUP -> {
            val s = 130f * density
            w = s; h = s; prW = 0f; prH = 0f
        }
        clip.text != null -> {
            val s = textSizes[clip.id]
            w = s?.width ?: 0f; h = s?.height ?: 0f; prW = 0f; prH = 0f
            vis = clip.cropVisibleBox(w, h)
        }
        forDragged -> {
            w = 0f; h = 0f; prW = 0f; prH = 0f
        }
        else -> return null
    }
    val geo = if (vis != null) {
        FrameGeo(clip, w, h, fw, fh, 0.01f, prW, prH, vis.width, vis.height, vis.offX, vis.offY)
    } else {
        FrameGeo(clip, w, h, fw, fh, 0.01f, prW, prH)
    }
    var l = Float.MAX_VALUE
    var tp = Float.MAX_VALUE
    var r = -Float.MAX_VALUE
    var bt = -Float.MAX_VALUE
    for (i in 0..3) {
        val c = geo.corner(i)
        if (c.x < l) l = c.x
        if (c.x > r) r = c.x
        if (c.y < tp) tp = c.y
        if (c.y > bt) bt = c.y
    }
    val ctr = geo.center
    return SnapRect(ctr.x, ctr.y, l, tp, r, bt)
}

/**
 * Invisible touch layer above the media layers: finds which picture / video is under the finger
 * (the selected one first, else the top one), selects it and moves it while dragging.
 * Text and shape layers sit above this layer and keep their own gestures.
 */
@Composable
private fun MediaGestureLayer(
    clips: List<com.example.model.TimelineClip>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onBegin: () -> Unit,
    onMove: (id: String, x: Float, y: Float) -> Unit,
    /** Called when a gesture on a layer ends (build 176: clears the snap guides). */
    onDragEnd: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var fw by remember { mediaMutableFloatStateOf(1f) }
    var fh by remember { mediaMutableFloatStateOf(1f) }
    val clipsNow by mediaRememberUpdatedState(clips)
    val selNow by mediaRememberUpdatedState(selectedId)
    val selectNow by mediaRememberUpdatedState(onSelect)
    val beginNow by mediaRememberUpdatedState(onBegin)
    val moveNow by mediaRememberUpdatedState(onMove)
    val endNow by mediaRememberUpdatedState(onDragEnd)

    Box(
        modifier = modifier
            .mediaOnSizeChanged { fw = it.width.toFloat(); fh = it.height.toFloat() }
            .mediaPointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    fun geo(c: com.example.model.TimelineClip): FrameGeo {
                        val b = mediaVisibleSize(c, fw, fh)
                        return FrameGeo(
                            c.copy(isFlippedH = false, isFlippedV = false),
                            b.width, b.height, fw, fh, 0.05f, fw, fh
                        )
                    }
                    val list = clipsNow
                    val sel = list.find { it.id == selNow }
                    val hit = if (sel != null && geo(sel).contains(down.position)) sel
                    else list.lastOrNull { geo(it).contains(down.position) }
                    if (hit == null) return@awaitEachGesture

                    if (hit.id != selNow) selectNow(hit.id)
                    if (hit.isLocked) return@awaitEachGesture

                    var tx = hit.transformX
                    var ty = hit.transformY
                    // The picture is drawn at (stored move x depth factor): finger px -> stored move.
                    val depthF = hit.depthFactor
                    var total = androidx.compose.ui.geometry.Offset.Zero
                    var dragging = false
                    val slop = viewConfiguration.touchSlop
                    while (true) {
                        val event = awaitPointerEvent()
                        val c = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed || c.isConsumed) break
                        val d = c.positionChange()
                        if (!dragging) {
                            total += d
                            if (total.getDistance() > slop) {
                                dragging = true
                                beginNow() // one undo step for the whole drag
                                tx += total.x / depthF
                                ty += total.y / depthF
                                moveNow(hit.id, tx, ty)
                                c.consume()
                            }
                        } else {
                            tx += d.x / depthF
                            ty += d.y / depthF
                            moveNow(hit.id, tx, ty)
                            c.consume()
                        }
                    }
                    endNow()
                }
            }
    )
}

/**
 * The given media layers (bottom first) drawn by the C++ engine on a surface of a full-screen editor (Crop, Edit shape):
 * same renderer as the canvas and the export, so blend modes, crop, 3D, cutout and face blur are the same there.
 */
@Composable
internal fun MediaLayersStack(
    layers: List<com.example.model.TimelineClip>,
    nativePreview: com.example.engine.NativePreviewEngine,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val editorTracks = remember(layers) {
        listOf(com.example.model.TimelineTrack(id = "editor", name = "editor", clips = layers))
    }
    NativeEditorSurface(
        engine = nativePreview,
        tracks = editorTracks,
        isPlaying = isPlaying,
        modifier = modifier.fillMaxSize()
    )
}

/**
 * Sticker layer on the preview canvas: the same 130 dp box and the same move / scale / rotate
 * placement as a shape layer. Animated stickers show the frame for the playhead time, so the
 * preview matches the export frame for frame.
 */
@Composable
private fun StickerLayerOnCanvas(
    clip: TimelineClip,
    timeMs: Long,
    isSelected: Boolean,
    onMove: (clipId: String, offsetX: Float, offsetY: Float) -> Unit,
    /** Called when the drag ends (build 176: clears the snap guides). */
    onDragEnd: () -> Unit = {},
    onTap: (clipId: String) -> Unit,
    modifier: Modifier = Modifier,
    /** The C++ preview draws this sticker: keep the (invisible) box for selection and touch only. */
    hideContent: Boolean = false
) {
    val density = LocalDensity.current.density
    val latest by androidx.compose.runtime.rememberUpdatedState(clip)
    val frame = remember(clip.uri, timeMs - clip.startMs) {
        StickerLibrary.frameFor(clip.uri, clip.sourceOffsetMs(timeMs - clip.startMs))?.asImageBitmap()
    }
    val effectiveScale = clip.effectiveScalePercent / 100f

    Box(
        modifier = modifier
            .graphicsLayer {
                translationX = clip.depthX
                translationY = clip.depthY
                scaleX = effectiveScale.coerceAtLeast(0.01f) * (if (clip.isFlippedH) -1f else 1f)
                scaleY = effectiveScale.coerceAtLeast(0.01f) * (if (clip.isFlippedV) -1f else 1f)
                rotationZ = clip.rotationDeg
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(clip.pivotX, clip.pivotY)
                cameraDistance = 12f * density
            }
            .size(130.dp)
            .pointerInput(clip.id) {
                detectTapGestures(onTap = { onTap(clip.id) })
            }
            .pointerInput(clip.id) {
                var currentDragX = 0f
                var currentDragY = 0f
                detectDragGestures(
                    onDragStart = {
                        currentDragX = latest.transformX
                        currentDragY = latest.transformY
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val l = latest
                        val rad = Math.toRadians(l.rotationDeg.toDouble())
                        val c = Math.cos(rad).toFloat()
                        val sn = Math.sin(rad).toFloat()
                        val sc = (l.scalePercent / 100f).coerceAtLeast(0.01f) // stored moves are before the depth factor
                        val sx = sc * (if (l.isFlippedH) -1f else 1f)
                        val sy = sc * (if (l.isFlippedV) -1f else 1f)
                        currentDragX += dragAmount.x * sx * c - dragAmount.y * sy * sn
                        currentDragY += dragAmount.x * sx * sn + dragAmount.y * sy * c
                        onMove(l.id, currentDragX, currentDragY)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        frame?.let {
            Image(
                bitmap = it,
                contentDescription = clip.name,
                modifier = Modifier.fillMaxSize().then(if (hideContent) Modifier.alpha(0f) else Modifier),
                contentScale = ContentScale.Fit
            )
        }
    }
}
