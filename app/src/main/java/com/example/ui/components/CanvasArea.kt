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
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
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
import com.example.model.LayerType
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
    modifier: Modifier = Modifier
) {
    val canvasColor = LocalCanvasColor.current
    val canvasArgb = canvasColor.toArgb()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (height != null) Modifier.height(height) else Modifier.fillMaxHeight())
            .background(EditorBackground)
            .testTag("canvas_preview_area"),
        contentAlignment = Alignment.Center
    ) {
        // Aspect-ratio bounding box for the player
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 0.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            val maxW = maxWidth
            val maxH = maxHeight
            val targetRatio = aspectRatio.ratioFloat

            val (frameW, frameH) = remember(maxW, maxH, targetRatio) {
                if (maxW.value / maxH.value > targetRatio) {
                    (maxH * targetRatio) to maxH
                } else {
                    maxW to (maxW / targetRatio)
                }
            }

            val frameDensity = androidx.compose.ui.platform.LocalDensity.current
            LaunchedEffect(frameW, frameH) {
                onPreviewFrame(with(frameDensity) { frameW.toPx() }, frameDensity.density)
            }
            Box(
                modifier = Modifier
                    .size(width = frameW, height = frameH)
                    .clip(RectangleShape)
                    .background(canvasColor),
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
                if (nativePreview != null) {
                    NativePreviewSurface(
                        engine = nativePreview,
                        tracks = nativeTracks,
                        playheadMs = playheadMs,
                        isPlaying = isPlaying,
                        canvasArgb = canvasArgb,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (activeMediaClips.isNotEmpty()) {
                    // Tap a picture / video to select it, drag to move it (like text and shapes).
                    MediaGestureLayer(
                        clips = activeMediaClips,
                        selectedId = selectedClipId,
                        onSelect = onClipSelect,
                        onBegin = onSelectionBegin,
                        onMove = onUpdateClipTransform,
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
                            Canvas(modifier = Modifier.size(24.dp)) {
                                val dotColor = Color.White.copy(alpha = 0.25f)
                                drawCircle(color = dotColor, radius = 2.dp.toPx())
                                drawLine(color = dotColor, start = Offset(size.width / 2f, 0f), end = Offset(size.width / 2f, size.height), strokeWidth = 1.dp.toPx())
                                drawLine(color = dotColor, start = Offset(0f, size.height / 2f), end = Offset(size.width, size.height / 2f), strokeWidth = 1.dp.toPx())
                            }
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
                            .map { com.example.engine.NativeRenderAt.renderAt(it, t, t) }
                            .filter { !it.isBehindCamera }
                            .sortedBy { it.transformZ }
                    }
                }

                val textSizes = remember { androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.ui.geometry.Size>() }
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
                        onMove = onUpdateClipTransform,
                        onTap = onClipSelect,
                        hideContent = com.example.engine.nativeShapeEligible(clip),
                        modifier = Modifier.align(Alignment.Center)
                    )
                  } else if (clip.type != LayerType.SHAPE && clip.type != LayerType.NULL && clip.type != LayerType.GROUP && clip.type != LayerType.CONTROLLER) {
                    TextLayerOnCanvas(
                        clip = clip,
                        frameWidth = frameW,
                        isSelected = clip.id == selectedClipId,
                        onMove = onUpdateClipTransform,
                        onTap = onClipSelect,
                        onDoubleTap = onTextDoubleTap,
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
                    val latestShape by androidx.compose.runtime.rememberUpdatedState(clip)

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
                            .pointerInput(clip.id) {
                                var currentDragX = 0f
                                var currentDragY = 0f
                                detectDragGestures(
                                    onDragStart = {
                                        currentDragX = latestShape.transformX
                                        currentDragY = latestShape.transformY
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        // The finger moves in the layer's own space (scaled, rotated and
                                        // maybe flipped): turn it back into canvas pixels.
                                        val l = latestShape
                                        val rad = Math.toRadians(l.rotationDeg.toDouble())
                                        val c = Math.cos(rad).toFloat()
                                        val sn = Math.sin(rad).toFloat()
                                        val sc = (l.scalePercent / 100f).coerceAtLeast(0.01f) // stored moves are before the depth factor
                                        val sx = sc * (if (l.isFlippedH) -1f else 1f)
                                        val sy = sc * (if (l.isFlippedV) -1f else 1f)
                                        currentDragX += dragAmount.x * sx * c - dragAmount.y * sy * sn
                                        currentDragY += dragAmount.x * sx * sn + dragAmount.y * sy * c
                                        onUpdateClipTransform(l.id, currentDragX, currentDragY)
                                    }
                                )
                            }
                    ) {
                        // The shape picture is drawn by the C++ engine (NativePreviewEngine); this box only takes the touch.
                        // Helper layers have no picture: a GROUP shows this editing marker (dashed box + cross),
                        // never exported. A Null / Controller shows NOTHING while unselected (build 155 —
                        // Sarowar's display correction, CanvasMarkRule); selected, the white bounding box
                        // below (SelectionFrameOverlay) is all that appears, like for any other layer.
                        if (com.example.model.CanvasMarkRule.markFor(clip.type, clip.id == selectedClipId) == com.example.model.CanvasMark.MARKER) {
                            Canvas(modifier = Modifier.matchParentSize()) {
                                val c = when (clip.type) {
                                    LayerType.GROUP -> Color(0xFF5BB85D)
                                    LayerType.CONTROLLER -> Color(0xFF00B8A9)
                                    else -> Color(0xFFE8A33D)
                                }
                                val sw = 1.5.dp.toPx()
                                drawRect(
                                    color = c, style = androidx.compose.ui.graphics.drawscope.Stroke(
                                        width = sw,
                                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                                    )
                                )
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val arm = 14.dp.toPx()
                                drawLine(c, Offset(cx - arm, cy), Offset(cx + arm, cy), strokeWidth = sw * 1.4f)
                                drawLine(c, Offset(cx, cy - arm), Offset(cx, cy + arm), strokeWidth = sw * 1.4f)
                                drawCircle(c, radius = 3.dp.toPx(), center = Offset(cx, cy))
                            }
                        }

                        if (clip.id == gradientEditId && clip.fillMode == com.example.model.FILL_GRADIENT) {
                            GradientHandleLayer(clip = clip, onPoints = onGradientPoints)
                        }

                    }
                }
                }
                }

                // Bounding box of the selected shape / text layer (Alight Motion style).
                val selOverlay = activeOverlayClips.find { it.id == selectedClipId }
                if (selOverlay != null && selOverlay.id != gradientEditId) {
                    val dens = androidx.compose.ui.platform.LocalDensity.current.density
                    val isShape = selOverlay.type == LayerType.SHAPE || selOverlay.type == LayerType.STICKER || selOverlay.type == LayerType.NULL || selOverlay.type == LayerType.GROUP || selOverlay.type == LayerType.CONTROLLER
                    val size = if (isShape) androidx.compose.ui.geometry.Size(130f * dens, 130f * dens)
                    else textSizes[selOverlay.id]
                    if (size != null && size.width > 0f && size.height > 0f) {
                        SelectionFrameOverlay(
                            clip = selOverlay,
                            layerWidthPx = size.width,
                            layerHeightPx = size.height,
                            minScale = 0.01f,
                            onBegin = onSelectionBegin,
                            onTransform = onSelectionTransform,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // 3D camera selected: viewfinder corners on the canvas (the camera itself has no picture, see CameraRig).
                if (tracks.any { it.clips.any { c -> c.id == selectedClipId && c.type == LayerType.CAMERA } }) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val col = Color(0xFF8E6BF2)
                        val sw = 2.5.dp.toPx()
                        val m = 10.dp.toPx()
                        val a = 26.dp.toPx()
                        val w = size.width
                        val h = size.height
                        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                            drawLine(col, Offset(x, y), Offset(x + dx * a, y), strokeWidth = sw)
                            drawLine(col, Offset(x, y), Offset(x, y + dy * a), strokeWidth = sw)
                        }
                        corner(m, m, 1f, 1f); corner(w - m, m, -1f, 1f); corner(m, h - m, 1f, -1f); corner(w - m, h - m, -1f, -1f)
                        drawCircle(col, radius = 3.dp.toPx(), center = Offset(w / 2f, h / 2f))
                    }
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
                        Canvas(modifier = Modifier.size(26.dp)) {
                            val greenNeon = Color(0xFF00E676)
                            val strokeW = 2.dp.toPx()
                            val cX = size.width / 2f
                            val cY = size.height / 2f

                            // Center dot
                            drawCircle(color = greenNeon, radius = 2.dp.toPx())

                            // 4 Crosshair lines
                            drawLine(color = greenNeon, start = Offset(cX, 0f), end = Offset(cX, 7.dp.toPx()), strokeWidth = strokeW)
                            drawLine(color = greenNeon, start = Offset(cX, size.height - 7.dp.toPx()), end = Offset(cX, size.height), strokeWidth = strokeW)
                            drawLine(color = greenNeon, start = Offset(0f, cY), end = Offset(7.dp.toPx(), cY), strokeWidth = strokeW)
                            drawLine(color = greenNeon, start = Offset(size.width - 7.dp.toPx(), cY), end = Offset(size.width, cY), strokeWidth = strokeW)
                        }
                    }
                }

            }

            // Bounding box + corner / rotate handles of the selected picture / video layer.
            // Drawn outside the clipped canvas, so handles at the canvas edge can still be grabbed.
            val selMedia by remember(tracks, selectedClipId, isPlaying) {
                derivedStateOf {
                    val t = playheadMs()
                    tracks.flatMap { it.clips }.firstOrNull {
                        it.id == selectedClipId && it.type == LayerType.VIDEO &&
                            it.isVisible && it.uri != null && it.isActiveAt(t)
                    }?.let { com.example.engine.NativeRenderAt.renderAt(it, t, t) }
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
    modifier: Modifier = Modifier
) {
    var fw by remember { mediaMutableFloatStateOf(1f) }
    var fh by remember { mediaMutableFloatStateOf(1f) }
    val clipsNow by mediaRememberUpdatedState(clips)
    val selNow by mediaRememberUpdatedState(selectedId)
    val selectNow by mediaRememberUpdatedState(onSelect)
    val beginNow by mediaRememberUpdatedState(onBegin)
    val moveNow by mediaRememberUpdatedState(onMove)

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
