package com.example.ui.components

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.example.R
import com.example.model.LayerType
import com.example.model.TIMELINE_PX_PER_MS
import com.example.model.TimelineClip
import com.example.model.TimelineTrack
import com.example.ui.theme.EditorBackground
import com.example.ui.theme.EditorSurface
import com.example.ui.theme.IconActive
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.PlayheadColor
import com.example.ui.theme.SelectedClipBorder
import com.example.ui.theme.TextPrimary
import com.example.ui.util.bouncyClickable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun TimelineLayerPanel(
    height: Dp,
    tracks: List<TimelineTrack>,
    currentPlayheadMs: Long,
    totalDurationMs: Long,
    selectedClipId: String?,
    isMasterMuted: Boolean,
    onSeekTo: (Long) -> Unit,
    onClipSelect: (String?) -> Unit,
    onSplitClip: () -> Unit,
    onDeleteClip: () -> Unit,
    onDuplicateClip: () -> Unit,
    onToggleMasterAudio: () -> Unit,
    /** Horizontal drag on a layer: move it earlier/later on the timeline (ms delta). */
    onMoveClipInTime: (clipId: String, deltaMs: Long) -> Unit = { _, _ -> },
    /** Called once when a layer time-drag starts (for undo). */
    onBeginClipTimeDrag: () -> Unit = {},
    /** Vertical reorder: move lane fromIndex → toIndex (0 = top). */
    onReorderLane: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
    /** Eye control: toggle layer visibility on canvas/export. */
    onToggleClipVisibility: (clipId: String) -> Unit = {},
    /** Timeline scale in px-per-ms (supports pinch zoom). */
    pxPerMs: Float = TIMELINE_PX_PER_MS,
    onPxPerMsChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
    waveforms: Map<String, FloatArray> = emptyMap()
) {
    val coroutineScope = rememberCoroutineScope()
    var flingJob by remember { mutableStateOf<Job?>(null) }
    val latestPxPerMs by rememberUpdatedState(pxPerMs)
    val latestOnPx by rememberUpdatedState(onPxPerMsChange)

    // The gesture block below is created ONCE (key = Unit). Before, it was keyed on
    // currentPlayheadMs, so every drag frame restarted it and the swipe was dropped
    // after a short distance. These always hold the latest values without restarting it.
    val latestPlayheadMs by rememberUpdatedState(currentPlayheadMs)
    val latestTotalMs by rememberUpdatedState(totalDurationMs)
    val latestSeek by rememberUpdatedState(onSeekTo)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(EditorBackground)
            .testTag("layer_panel_timeline")
            // One gesture loop: 1-finger horizontal = scrub/fling, 2-finger = pinch zoom.
            // detectTransformGestures was eating single-finger swipes, so this is unified.
            .pointerInput(Unit) {
                val velocityTracker = VelocityTracker()
                val pxPerDp = density
                val touchSlop = viewConfiguration.touchSlop

                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    flingJob?.cancel()
                    velocityTracker.resetTracking()
                    var posMs = latestPlayheadMs.toFloat()
                    var pastTouchSlop = false
                    var isPinch = false
                    var lastSpan = 0f
                    var didScrub = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }

                        if (pressed.isEmpty()) break

                        if (pressed.size >= 2) {
                            // ---- Pinch zoom ----
                            isPinch = true
                            val a = pressed[0].position
                            val b = pressed[1].position
                            val span = (a - b).getDistance()
                            if (lastSpan > 1f && span > 1f) {
                                val zoomChange = span / lastSpan
                                if (abs(zoomChange - 1f) > 0.001f) {
                                    val next = (latestPxPerMs * zoomChange).coerceIn(
                                        TIMELINE_PX_PER_MS * 0.25f,
                                        TIMELINE_PX_PER_MS * 8f
                                    )
                                    latestOnPx(next)
                                }
                            }
                            lastSpan = span
                            pressed.forEach { it.consume() }
                        } else if (!isPinch) {
                            // ---- Single-finger horizontal scrub ----
                            val change = pressed[0]
                            val dx = change.position.x - change.previousPosition.x
                            val dy = change.position.y - change.previousPosition.y
                            if (!pastTouchSlop) {
                                val totalDx = change.position.x - change.previousPosition.x
                                // accumulate via position vs down would be better; use slop on movement
                                if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                                    pastTouchSlop = true
                                    // If mostly vertical, don't steal — let verticalScroll handle it
                                    if (abs(dy) > abs(dx) * 1.2f) {
                                        break
                                    }
                                }
                            }
                            if (pastTouchSlop) {
                                change.consume()
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                val deltaMs = -(dx / pxPerDp) / latestPxPerMs
                                posMs = (posMs + deltaMs).coerceIn(0f, latestTotalMs.toFloat())
                                latestSeek(posMs.roundToLong())
                                didScrub = true
                            }
                        }
                    }

                    // Fling after a horizontal scrub (not after pinch / vertical scroll).
                    if (didScrub && !isPinch) {
                        val velocityPxPerSec = velocityTracker.calculateVelocity().x
                        if (abs(velocityPxPerSec) > 120f) {
                            val startMs = posMs
                            val velocityMsPerSec = -(velocityPxPerSec / pxPerDp) / latestPxPerMs
                            flingJob = coroutineScope.launch {
                                AnimationState(
                                    initialValue = startMs,
                                    initialVelocity = velocityMsPerSec
                                ).animateDecay(
                                    exponentialDecay(
                                        frictionMultiplier = FLING_FRICTION,
                                        absVelocityThreshold = 25f
                                    )
                                ) {
                                    val total = latestTotalMs.toFloat()
                                    val ms = value.coerceIn(0f, total)
                                    latestSeek(ms.roundToLong())
                                    if (ms <= 0f || ms >= total) cancelAnimation()
                                }
                            }
                        }
                    }
                }
            }
    ) {
        val screenWidth = maxWidth
        val centerLineX = screenWidth / 2 // EXACT 50% HORIZONTAL CENTER OF SCREEN

        val verticalScrollState = rememberScrollState()
        val dpToPx = LocalDensity.current.density

        // Real timeline length: the longest clip (any track) + room for the + button and the
        // right half of the screen. Clips used to be capped at the screen width because their
        // parent had a bounded width; the horizontalScroll below (scrolling disabled) only
        // hands its child unbounded width so a long clip keeps its true size.
        val contentEndMs = maxOf(
            totalDurationMs,
            tracks.maxOfOrNull { t -> t.clips.maxOfOrNull { it.endMs } ?: 0L } ?: 0L
        )
        val contentWidth = (contentEndMs * pxPerMs).dp + screenWidth + 64.dp

        // Time window that is actually on screen (+ margin) so only visible thumbnails load.
        // The window is snapped to 2 s steps: while swiping, the clips only recompose when the
        // window steps (every ~2 s), not on every frame. The margin (~2.2 s) is larger than the step.
        val marginMs = (100f / pxPerMs).toLong()
        val windowStepMs = 2_000L
        val rawFromMs = currentPlayheadMs - (centerLineX.value / pxPerMs).toLong() - marginMs
        val rawToMs = currentPlayheadMs +
            ((screenWidth - centerLineX).value / pxPerMs).toLong() + marginMs
        val visibleFromMs = Math.floorDiv(rawFromMs, windowStepMs) * windowStepMs
        val visibleToMs = (Math.floorDiv(rawToMs, windowStepMs) + 1L) * windowStepMs

        // 1. Left layer controls (eye + thumb) + timeline tracks, shared vertical scroll.
        val leftRailWidth = 56.dp
        Row(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(verticalScrollState)
                .padding(vertical = 4.dp)
        ) {
            // Left rail — Alight Motion style: eye + layer preview for each lane.
            Column(
                modifier = Modifier
                    .width(leftRailWidth)
                    .padding(start = 6.dp, end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tracks.forEach { track ->
                    val clip = track.clips.firstOrNull()
                    val rowH = clip?.let { laneHeightFor(it.type) } ?: 40.dp
                    LayerLaneControl(
                        clip = clip,
                        height = rowH,
                        onToggleVisibility = { id -> onToggleClipVisibility(id) },
                        onSelect = { id -> onClipSelect(id) }
                    )
                }
            }

            // Timeline content, shifted so currentPlayheadMs sits under the center line.
            // Center line is still screen-relative; offset accounts for left rail.
            val railPx = with(LocalDensity.current) { leftRailWidth.toPx() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState(), enabled = false)
            ) {
                Column(
                    modifier = Modifier
                        .width(contentWidth)
                        .offset {
                            IntOffset(
                                x = ((centerLineX.value - latestPlayheadMs * pxPerMs) * dpToPx - railPx).roundToInt(),
                                y = 0
                            )
                        },
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tracks.forEachIndexed { laneIndex, track ->
                        TimelineTrackRow(
                            track = track,
                            laneIndex = laneIndex,
                            laneCount = tracks.size,
                            selectedClipId = selectedClipId,
                            onClipSelect = onClipSelect,
                            onMoveClipInTime = onMoveClipInTime,
                            onBeginClipTimeDrag = onBeginClipTimeDrag,
                            onReorderLane = onReorderLane,
                            pxPerMs = pxPerMs,
                            visibleFromMs = visibleFromMs,
                            visibleToMs = visibleToMs,
                            waveforms = waveforms
                        )
                    }
                }
            }
        }

        // 2. Quick actions + master speaker (bottom-left, clear of the layer rail)
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(EditorBackground.copy(alpha = 0.9f), CircleShape)
                    .bouncyClickable(onClick = onToggleMasterAudio),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isMasterMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Master Audio",
                    tint = IconActive,
                    modifier = Modifier.size(20.dp)
                )
            }
            if (selectedClipId != null) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(EditorSurface.copy(alpha = 0.9f), CircleShape)
                            .bouncyClickable(onClick = onSplitClip),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SmartButton,
                            contentDescription = "Split Clip",
                            tint = NeonCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(EditorSurface.copy(alpha = 0.9f), CircleShape)
                            .bouncyClickable(onClick = onDuplicateClip),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Duplicate Clip",
                            tint = IconActive,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(Color(0xFF3B1818).copy(alpha = 0.9f), CircleShape)
                            .bouncyClickable(onClick = onDeleteClip),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Clip",
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            } // selected tools Column
            } // if selectedClipId
        } // bottom-left Column

        // 4. VERTICAL LOCKED PLAYHEAD LINE ("খারাভাবে middle এ locked timeline")
        Canvas(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .offset(x = centerLineX - 1.dp)
        ) {
            drawLine(
                color = PlayheadColor,
                start = Offset(1.dp.toPx(), 0f),
                end = Offset(1.dp.toPx(), size.height),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
}


@Composable
private fun LayerLaneControl(
    clip: TimelineClip?,
    height: Dp,
    onToggleVisibility: (String) -> Unit,
    onSelect: (String) -> Unit
) {
    if (clip == null) {
        Spacer(modifier = Modifier.height(height))
        return
    }
    val raw = (clip.textColor.takeIf { it != 0L } ?: clip.color)
    val thumbColor = Color(
        red = ((raw shr 16) and 0xFF).toInt() / 255f,
        green = ((raw shr 8) and 0xFF).toInt() / 255f,
        blue = (raw and 0xFF).toInt() / 255f,
        alpha = 1f
    )
    Row(
        modifier = Modifier
            .height(height)
            .fillMaxWidth()
            .clip(RoundedCornerShape(height / 2))
            .background(Color(0xFF1A1E28))
            .bouncyClickable(pressedScale = 0.96f, onClick = { onSelect(clip.id) })
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = if (clip.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
            contentDescription = if (clip.isVisible) "Hide layer" else "Show layer",
            tint = if (clip.isVisible) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.35f),
            modifier = Modifier
                .size(18.dp)
                .bouncyClickable(onClick = { onToggleVisibility(clip.id) })
        )
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    when (clip.type) {
                        LayerType.VIDEO -> Color(0xFF2A4365)
                        LayerType.AUDIO -> Color(0xFF1B4332)
                        LayerType.SHAPE -> thumbColor
                        LayerType.TEXT -> Color(0xFF5C3D10)
                        else -> thumbColor
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            when (clip.type) {
                LayerType.SHAPE -> {
                    // Mini shape cue
                    Box(
                        Modifier
                            .size(12.dp)
                            .clip(
                                when ((clip.shapePattern ?: "CIRCLE").uppercase()) {
                                    "RECT", "RECTANGLE", "SQUARE" -> RoundedCornerShape(2.dp)
                                    "ROUNDED", "ROUNDED_RECT" -> RoundedCornerShape(4.dp)
                                    else -> CircleShape
                                }
                            )
                            .background(thumbColor)
                    )
                }
                LayerType.TEXT -> {
                    Text("T", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                LayerType.AUDIO -> {
                    Icon(Icons.Default.Mic, null, tint = Color.White, modifier = Modifier.size(12.dp))
                }
                else -> {
                    // video / other: solid chip already shows
                }
            }
        }
    }
}

@Composable
fun TimelineTrackRow(
    track: TimelineTrack,
    laneIndex: Int = 0,
    laneCount: Int = 1,
    selectedClipId: String?,
    onClipSelect: (String?) -> Unit,
    onMoveClipInTime: (clipId: String, deltaMs: Long) -> Unit = { _, _ -> },
    onBeginClipTimeDrag: () -> Unit = {},
    onReorderLane: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
    pxPerMs: Float = TIMELINE_PX_PER_MS,
    visibleFromMs: Long = Long.MIN_VALUE / 4,
    visibleToMs: Long = Long.MAX_VALUE / 4,
    waveforms: Map<String, FloatArray> = emptyMap()
) {
    // Alight Motion: one layer per line → fixed row height by layer type.
    val trackHeight = track.clips.maxOfOrNull { laneHeightFor(it.type) } ?: 40.dp
    val density = LocalDensity.current
    val pxPerDp = density.density
    // How many vertical pixels ≈ one lane step for reorder.
    val laneStepPx = with(density) { (trackHeight + 6.dp).toPx() }

    var dragOffsetX by remember { mutableStateOf(0f) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .height(trackHeight)
            .fillMaxWidth()
            .graphicsLayer {
                // Live feedback while reordering vertically.
                translationY = if (isDragging) dragOffsetY else 0f
                alpha = if (isDragging) 0.92f else 1f
                shadowElevation = if (isDragging) 8f else 0f
            }
            .background(Color(0xFF13161F).copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.CenterStart
    ) {
        // Exactly one clip per lane in Alight Motion mode.
        track.clips.forEach { clip ->
            val clipOffset = (clip.startMs * pxPerMs).dp
            val clipWidth = (clip.durationMs * pxPerMs).dp.coerceAtLeast(36.dp)
            val isSelected = clip.id == selectedClipId

            Box(
                modifier = Modifier
                    .offset(x = clipOffset + if (isDragging) (dragOffsetX / pxPerDp).dp else 0.dp)
                    .pointerInput(clip.id, laneIndex, laneCount) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                isDragging = true
                                dragOffsetX = 0f
                                dragOffsetY = 0f
                                onClipSelect(clip.id)
                                onBeginClipTimeDrag()
                            },
                            onDragEnd = {
                                // Commit vertical reorder if moved enough lanes.
                                val steps = (dragOffsetY / laneStepPx).roundToInt()
                                if (steps != 0) {
                                    val to = (laneIndex + steps).coerceIn(0, (laneCount - 1).coerceAtLeast(0))
                                    if (to != laneIndex) onReorderLane(laneIndex, to)
                                }
                                // Horizontal time already applied live via onMoveClipInTime.
                                isDragging = false
                                dragOffsetX = 0f
                                dragOffsetY = 0f
                            },
                            onDragCancel = {
                                isDragging = false
                                dragOffsetX = 0f
                                dragOffsetY = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetX += dragAmount.x
                                dragOffsetY += dragAmount.y
                                // Dominant axis: horizontal → time, vertical → reorder preview only.
                                if (abs(dragAmount.x) >= abs(dragAmount.y)) {
                                    val deltaMs = (dragAmount.x / pxPerDp / pxPerMs).roundToLong()
                                    if (deltaMs != 0L) {
                                        onMoveClipInTime(clip.id, deltaMs)
                                        // Keep visual offset in sync after model updates startMs.
                                        dragOffsetX -= deltaMs * pxPerMs * pxPerDp
                                    }
                                }
                            }
                        )
                    }
            ) {
                TimelineClipItem(
                    clip = clip,
                    width = clipWidth,
                    isSelected = isSelected || isDragging,
                    onClick = { onClipSelect(clip.id) },
                    visibleFromMs = visibleFromMs,
                    visibleToMs = visibleToMs,
                    waveform = clip.uri?.let { waveforms[it] }
                )
            }
        }
    }
}

private fun laneHeightFor(type: LayerType): Dp = when (type) {
    LayerType.VIDEO -> 44.dp
    LayerType.AUDIO -> 44.dp
    LayerType.SHAPE -> 40.dp
    LayerType.OVERLAY, LayerType.TEXT -> 42.dp
    else -> 36.dp
}

@Composable
fun TimelineClipItem(
    clip: TimelineClip,
    width: Dp,
    isSelected: Boolean,
    onClick: () -> Unit,
    visibleFromMs: Long = Long.MIN_VALUE / 4,
    visibleToMs: Long = Long.MAX_VALUE / 4,
    waveform: FloatArray? = null
) {
    val selectScale by animateFloatAsState(
        targetValue = if (isSelected) 1.03f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "clipSelectScale"
    )

    val borderModifier = if (isSelected) {
        Modifier.border(2.dp, SelectedClipBorder, RoundedCornerShape(6.dp))
    } else {
        Modifier.border(1.dp, Color(0xFF2C3240), RoundedCornerShape(6.dp))
    }

    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .graphicsLayer {
                scaleX = selectScale
                scaleY = selectScale
                alpha = if (clip.isVisible) 1f else 0.35f
            }
            .clip(RoundedCornerShape(6.dp))
            .then(borderModifier)
            .bouncyClickable(pressedScale = 0.95f, onClick = onClick)
    ) {
        when (clip.type) {
            LayerType.VIDEO -> {
                if (clip.uri != null) {
                    // Real frames taken from the clip itself, spread over its full width.
                    VideoFrameStrip(
                        clip = clip,
                        widthDp = width,
                        visibleFromMs = visibleFromMs,
                        visibleToMs = visibleToMs
                    )
                } else {
                    // Demo clip without media: keep the old placeholder tiles.
                    Row(modifier = Modifier.fillMaxSize()) {
                        val tileRes = when {
                            clip.name.contains("Yellow") || clip.name.contains("Vlog") -> R.drawable.sample_portrait_1791121452621
                            clip.name.contains("Canopy") || clip.name.contains("Trees") -> R.drawable.sample_nature_1791121466670
                            else -> R.drawable.sample_portrait_1791121452621
                        }
                        val frameCount = ((width.value / 38).toInt()).coerceAtLeast(1)
                        for (i in 0 until frameCount) {
                            Image(
                                painter = painterResource(id = tileRes),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .border(0.5.dp, Color.Black.copy(alpha = 0.3f))
                            )
                        }
                    }
                }
            }

            LayerType.AUDIO -> {
                if (clip.uri != null) {
                    // Real audio layer: waveform of the part of the song this layer plays.
                    AudioClipBody(
                        clip = clip,
                        width = width,
                        waveform = waveform,
                        visibleFromMs = visibleFromMs,
                        visibleToMs = visibleToMs
                    )
                } else {
                    // Old demo audio layer (no file): keep the placeholder bars.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF1E232A))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .background(NeonGreen.copy(alpha = 0.25f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Mic",
                                    tint = NeonGreen,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            repeat(15) {
                                Box(
                                    modifier = Modifier
                                        .width(16.dp)
                                        .height(4.dp)
                                        .background(Color(0xFF4B5563), RoundedCornerShape(2.dp))
                                )
                            }
                        }
                    }
                }
            }

            LayerType.SHAPE -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F2537))
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val dotSpacing = 8.dp.toPx()
                        val cols = (size.width / dotSpacing).toInt()
                        val rows = (size.height / dotSpacing).toInt()
                        for (r in 1 until rows) {
                            for (c in 1 until cols) {
                                drawCircle(
                                    color = NeonCyan,
                                    radius = 1.6.dp.toPx(),
                                    center = Offset(c * dotSpacing, r * dotSpacing)
                                )
                            }
                        }
                    }
                }
            }

            LayerType.OVERLAY -> {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF2A1C2C))
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.sample_cyan_dots_1791121437975),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Image(
                        painter = painterResource(id = R.drawable.sample_portrait_1791121452621),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                }
            }

            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(clip.color)),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = clip.name,
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }

        Text(
            text = clip.name,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )

        // Selected clip duration badge & keyframe diamond (matching screenshot)
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .height(28.dp)
                    .clip(RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 4.dp, bottomEnd = 4.dp))
                    .background(Color.White)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = String.format(java.util.Locale.US, "%.1fs", clip.durationMs / 1000f),
                    color = Color.Black,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp)
                    .size(20.dp)
                    .graphicsLayer { rotationZ = 45f }
                    .background(Color(0xFF374151).copy(alpha = 0.85f), RoundedCornerShape(2.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(12.dp)
                        .graphicsLayer { rotationZ = -45f }
                )
            }
        }
    }
}


/** Lower = longer glide after a swipe. Alight-Motion-like smooth coast. */
private const val FLING_FRICTION = 0.35f

private val ThumbTileWidth = 44.dp
private val ThumbTileHeight = 44.dp

/** One shared loader that can decode video frames (coil-video). */
private object ThumbLoader {
    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader =
        instance ?: synchronized(this) {
            instance ?: ImageLoader.Builder(context.applicationContext)
                .components { add(VideoFrameDecoder.Factory()) }
                .build()
                .also { instance = it }
        }
}

/**
 * Filmstrip for a clip: one thumbnail per [ThumbTileWidth] of clip width, each showing the
 * frame at that point of the clip (respecting trim and speed). Only tiles inside the visible
 * time window are composed, so very long clips stay cheap.
 */
@Composable
private fun VideoFrameStrip(
    clip: TimelineClip,
    widthDp: Dp,
    visibleFromMs: Long,
    visibleToMs: Long
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val loader = remember(context) { ThumbLoader.get(context) }

    val tileW = ThumbTileWidth.value
    val tileCount = ceil(widthDp.value / tileW).toInt().coerceAtLeast(1)

    val relFromMs = visibleFromMs - clip.startMs
    val relToMs = visibleToMs - clip.startMs
    if (relToMs < 0L || relFromMs > clip.durationMs) return

    val first = (relFromMs.coerceAtLeast(0L) * TIMELINE_PX_PER_MS / tileW).toInt().coerceIn(0, tileCount - 1)
    val last = (relToMs * TIMELINE_PX_PER_MS / tileW).toInt().coerceIn(0, tileCount - 1)

    val tilePxW = with(density) { ThumbTileWidth.roundToPx() }
    val tilePxH = with(density) { ThumbTileHeight.roundToPx() }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1B2030))) {
        for (i in first..last) {
            val key: String
            var frameMs = 0L
            if (clip.isImage) {
                key = "${clip.uri}@img"
            } else {
                val timelineOffsetMs = (i * tileW + tileW / 2f) / TIMELINE_PX_PER_MS
                var srcMs = clip.trimStartMs + (timelineOffsetMs * clip.speed).toLong()
                if (clip.sourceDurationMs > 0L) {
                    srcMs = srcMs.coerceIn(0L, (clip.sourceDurationMs - 1L).coerceAtLeast(0L))
                }
                frameMs = (srcMs / 200L) * 200L // snap so nearby tiles/redraws share the cache
                key = "${clip.uri}@$frameMs"
            }
            val request = ImageRequest.Builder(context)
                .data(clip.uri)
                .size(tilePxW, tilePxH)
                .memoryCacheKey(key)
                .apply { if (!clip.isImage) videoFrameMillis(frameMs) }
                .build()

            AsyncImage(
                model = request,
                contentDescription = null,
                imageLoader = loader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .offset(x = ThumbTileWidth * i)
                    .width(ThumbTileWidth)
                    .fillMaxHeight()
            )
        }
    }
}
