package com.example.ui.components

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val LAYER_CONTROL_WIDTH = 124.dp
private val LAYER_ROW_HEIGHT = 50.dp
private val LAYER_ROW_GAP = 4.dp
private const val MIN_CLIP_DURATION_MS = 200L

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
    onBeginTimelineGesture: () -> Unit,
    onEndTimelineGesture: () -> Unit,
    onMoveClip: (String, Long) -> Unit,
    onResizeClip: (String, Boolean, Long) -> Unit,
    onReorderTrack: (Int, Int) -> Unit,
    onToggleVisibility: (Int) -> Unit,
    onToggleLock: (Int) -> Unit,
    onSplitClip: () -> Unit,
    onDeleteClip: () -> Unit,
    onDuplicateClip: () -> Unit,
    onToggleMasterAudio: () -> Unit,
    onAddMediaClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var flingJob by remember { mutableStateOf<Job?>(null) }
    val latestPlayheadMs by rememberUpdatedState(currentPlayheadMs)
    val latestTotalMs by rememberUpdatedState(totalDurationMs)
    val latestSeek by rememberUpdatedState(onSeekTo)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(EditorBackground)
            .testTag("layer_panel_timeline")
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    flingJob?.cancel()
                }
            }
            .pointerInput(Unit) {
                val velocityTracker = VelocityTracker()
                val pxPerDp = density
                var posMs = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        flingJob?.cancel()
                        velocityTracker.resetTracking()
                        posMs = latestPlayheadMs.toFloat()
                    },
                    onHorizontalDrag = { change, dragAmountPx ->
                        change.consume()
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        val deltaMs = -(dragAmountPx / pxPerDp) / TIMELINE_PX_PER_MS
                        posMs = (posMs + deltaMs).coerceIn(0f, latestTotalMs.toFloat())
                        latestSeek(posMs.roundToLong())
                    },
                    onDragEnd = {
                        val velocityPxPerSec = velocityTracker.calculateVelocity().x
                        if (abs(velocityPxPerSec) > 200f) {
                            val startMs = posMs
                            val velocityMsPerSec = -(velocityPxPerSec / pxPerDp) / TIMELINE_PX_PER_MS
                            flingJob = coroutineScope.launch {
                                AnimationState(
                                    initialValue = startMs,
                                    initialVelocity = velocityMsPerSec
                                ).animateDecay(
                                    exponentialDecay(frictionMultiplier = FLING_FRICTION, absVelocityThreshold = 40f)
                                ) {
                                    val total = latestTotalMs.toFloat()
                                    val ms = value.coerceIn(0f, total)
                                    latestSeek(ms.roundToLong())
                                    if (ms <= 0f || ms >= total) cancelAnimation()
                                }
                            }
                        }
                    },
                    onDragCancel = { flingJob?.cancel() }
                )
            }
    ) {
        val screenWidth = maxWidth
        val centerLineX = screenWidth / 2
        val verticalScrollState = rememberScrollState()
        val dpToPx = LocalDensity.current.density

        val contentEndMs = maxOf(
            totalDurationMs,
            tracks.maxOfOrNull { it.clips.firstOrNull()?.endMs ?: 0L } ?: 0L
        )
        val contentWidth = (contentEndMs * TIMELINE_PX_PER_MS).dp + screenWidth + 96.dp
        val marginMs = (160f / TIMELINE_PX_PER_MS).toLong()
        val visibleFromMs = currentPlayheadMs - (centerLineX.value / TIMELINE_PX_PER_MS).toLong() - marginMs
        val visibleToMs = currentPlayheadMs +
            ((screenWidth - centerLineX).value / TIMELINE_PX_PER_MS).toLong() + marginMs

        Box(modifier = Modifier.fillMaxSize()) {
            // Timeline rows. Each row is one and only one layer.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(verticalScrollState)
                    .horizontalScroll(rememberScrollState(), enabled = false)
                    .padding(vertical = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .width(contentWidth)
                        .offset {
                            IntOffset(
                                x = ((centerLineX.value - currentPlayheadMs * TIMELINE_PX_PER_MS) * dpToPx).roundToInt(),
                                y = 0
                            )
                        },
                    verticalArrangement = Arrangement.spacedBy(LAYER_ROW_GAP)
                ) {
                    tracks.forEachIndexed { index, track ->
                        TimelineTrackRow(
                            track = track,
                            trackIndex = index,
                            trackCount = tracks.size,
                            selectedClipId = selectedClipId,
                            onClipSelect = onClipSelect,
                            onBeginTimelineGesture = onBeginTimelineGesture,
                            onEndTimelineGesture = onEndTimelineGesture,
                            onMoveClip = onMoveClip,
                            onResizeClip = onResizeClip,
                            onReorderTrack = onReorderTrack,
                            visibleFromMs = visibleFromMs,
                            visibleToMs = visibleToMs
                        )
                    }
                    AddMediaRow(
                        endMs = tracks.maxOfOrNull { it.clips.firstOrNull()?.endMs ?: 0L } ?: 0L,
                        onAddMediaClick = onAddMediaClick
                    )
                }
            }

            // Fixed layer controls, like the marked controls in the reference: eye + layer icon +
            // name + lock/drag handle. It shares the exact same vertical scroll state as the timeline.
            LayerControlColumn(
                tracks = tracks,
                selectedClipId = selectedClipId,
                scrollState = verticalScrollState,
                onClipSelect = onClipSelect,
                onToggleVisibility = onToggleVisibility,
                onToggleLock = onToggleLock,
                onBeginTimelineGesture = onBeginTimelineGesture,
                onEndTimelineGesture = onEndTimelineGesture,
                onReorderTrack = onReorderTrack
            )

            // Center-locked playhead.
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

            Box(
                modifier = Modifier
                    .offset(x = (centerLineX - 54.dp).coerceAtLeast(132.dp), y = 8.dp)
                    .size(44.dp)
                    .background(EditorBackground.copy(alpha = 0.75f), CircleShape)
                    .bouncyClickable(onClick = onToggleMasterAudio),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isMasterMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Master Audio",
                    tint = IconActive,
                    modifier = Modifier.size(26.dp)
                )
            }

            if (selectedClipId != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 132.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QuickTimelineButton(Icons.Default.SmartButton, NeonCyan, onSplitClip)
                    QuickTimelineButton(Icons.Default.ContentCopy, IconActive, onDuplicateClip)
                    QuickTimelineButton(Icons.Default.Delete, Color(0xFFFF5252), onDeleteClip, destructive = true)
                }
            }
        }
    }
}

@Composable
private fun QuickTimelineButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
    destructive: Boolean = false
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .background(
                if (destructive) Color(0xFF3B1818).copy(alpha = 0.9f) else EditorSurface.copy(alpha = 0.9f),
                CircleShape
            )
            .bouncyClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun LayerControlColumn(
    tracks: List<TimelineTrack>,
    selectedClipId: String?,
    scrollState: androidx.compose.foundation.ScrollState,
    onClipSelect: (String) -> Unit,
    onToggleVisibility: (Int) -> Unit,
    onToggleLock: (Int) -> Unit,
    onBeginTimelineGesture: () -> Unit,
    onEndTimelineGesture: () -> Unit,
    onReorderTrack: (Int, Int) -> Unit
) {
    Box(
        modifier = Modifier
            .width(LAYER_CONTROL_WIDTH)
            .fillMaxHeight()
            .background(EditorBackground.copy(alpha = 0.96f))
            .verticalScroll(scrollState)
            .padding(vertical = 4.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(LAYER_ROW_GAP)) {
            tracks.forEachIndexed { index, track ->
                val clip = track.clips.firstOrNull() ?: return@forEachIndexed
                var dragY by remember(track.id) { mutableFloatStateOf(0f) }
                val density = LocalDensity.current
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(LAYER_ROW_HEIGHT)
                        .graphicsLayer { translationY = dragY }
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (clip.id == selectedClipId) Color(0xFF242B38) else Color(0xFF151A24))
                        .border(1.dp, Color(0xFF262D3A), RoundedCornerShape(7.dp))
                        .pointerInput(track.id, index) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragY = 0f
                                    onClipSelect(clip.id)
                                    onBeginTimelineGesture()
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragY += amount.y
                                },
                                onDragEnd = {
                                    val rowStepPx = with(density) { (LAYER_ROW_HEIGHT + LAYER_ROW_GAP).toPx() }
                                    val target = (index + (dragY / rowStepPx).roundToInt()).coerceIn(0, tracks.lastIndex)
                                    dragY = 0f
                                    onReorderTrack(index, target)
                                    onEndTimelineGesture()
                                },
                                onDragCancel = {
                                    dragY = 0f
                                    onEndTimelineGesture()
                                }
                            )
                        }
                        .bouncyClickable { onClipSelect(clip.id) },
                    contentAlignment = Alignment.CenterStart
                ) {
                    Box(
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(30.dp)
                            .bouncyClickable { onToggleVisibility(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (track.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (track.isVisible) "Hide layer" else "Show layer",
                            tint = if (track.isVisible) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    LayerThumb(
                        clip = clip,
                        modifier = Modifier
                            .padding(start = 38.dp)
                            .size(30.dp)
                    )

                    Text(
                        text = clip.name,
                        color = Color.White.copy(alpha = 0.82f),
                        fontSize = 9.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(start = 72.dp)
                            .width(30.dp)
                    )

                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 28.dp)
                            .size(22.dp)
                            .bouncyClickable { onToggleLock(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (track.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = if (track.isLocked) "Unlock layer" else "Lock layer",
                            tint = if (track.isLocked) Color(0xFFFFB74D) else Color.White.copy(alpha = 0.28f),
                            modifier = Modifier.size(13.dp)
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.DragIndicator,
                        contentDescription = "Drag layer",
                        tint = Color.White.copy(alpha = 0.25f),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 4.dp)
                            .size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun LayerThumb(clip: TimelineClip, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val loader = remember(context) { ThumbLoader.get(context) }
    val background = Color(clip.color)
    when {
        clip.type == LayerType.VIDEO && clip.uri != null -> {
            val request = ImageRequest.Builder(context)
                .data(clip.uri)
                .size(60, 60)
                .memoryCacheKey("layer-thumb-${clip.uri}")
                .apply { if (!clip.isImage) videoFrameMillis(0) }
                .build()
            AsyncImage(
                model = request,
                imageLoader = loader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier.clip(RoundedCornerShape(5.dp))
            )
        }
        else -> {
            Box(
                modifier = modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(background),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (clip.type) {
                        LayerType.TEXT -> "T"
                        LayerType.SHAPE -> "◆"
                        LayerType.AUDIO -> "♫"
                        LayerType.OVERLAY -> "◉"
                        LayerType.STICKER -> "★"
                        LayerType.VIDEO -> "▶"
                    },
                    color = Color.White.copy(alpha = 0.9f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun AddMediaRow(endMs: Long, onAddMediaClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(40.dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = (endMs * TIMELINE_PX_PER_MS).dp + 8.dp)
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF2A3042))
                .bouncyClickable(pressedScale = 0.88f, hoverScale = 1.08f, onClick = onAddMediaClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add media", tint = IconActive, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun TimelineTrackRow(
    track: TimelineTrack,
    trackIndex: Int,
    trackCount: Int,
    selectedClipId: String?,
    onClipSelect: (String) -> Unit,
    onBeginTimelineGesture: () -> Unit,
    onEndTimelineGesture: () -> Unit,
    onMoveClip: (String, Long) -> Unit,
    onResizeClip: (String, Boolean, Long) -> Unit,
    onReorderTrack: (Int, Int) -> Unit,
    visibleFromMs: Long,
    visibleToMs: Long
) {
    val clip = track.clips.firstOrNull() ?: return
    val density = LocalDensity.current
    val isSelected = clip.id == selectedClipId
    var dragY by remember(clip.id) { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .height(LAYER_ROW_HEIGHT)
            .fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = (clip.startMs * TIMELINE_PX_PER_MS).dp)
                .graphicsLayer {
                    translationY = dragY
                    alpha = if (track.isVisible) 1f else 0.45f
                }
        ) {
            TimelineClipItem(
                clip = clip,
                width = (clip.durationMs * TIMELINE_PX_PER_MS).dp.coerceAtLeast(44.dp),
                isSelected = isSelected,
                isLocked = track.isLocked,
                onClick = { onClipSelect(clip.id) },
                onBeginTimelineGesture = onBeginTimelineGesture,
                onEndTimelineGesture = onEndTimelineGesture,
                onMove = { deltaMs ->
                    onMoveClip(clip.id, clip.startMs + deltaMs)
                },
                onResize = { left, edgeMs ->
                    onResizeClip(clip.id, left, edgeMs)
                },
                onVerticalDrag = { deltaY -> dragY += deltaY },
                onVerticalDragEnd = {
                    val rowStepPx = with(density) { (LAYER_ROW_HEIGHT + LAYER_ROW_GAP).toPx() }
                    val target = (trackIndex + (dragY / rowStepPx).roundToInt()).coerceIn(0, trackCount - 1)
                    dragY = 0f
                    onReorderTrack(trackIndex, target)
                },
                visibleFromMs = visibleFromMs,
                visibleToMs = visibleToMs
            )
        }
    }
}

@Composable
private fun TimelineClipItem(
    clip: TimelineClip,
    width: Dp,
    isSelected: Boolean,
    isLocked: Boolean,
    onClick: () -> Unit,
    onBeginTimelineGesture: () -> Unit,
    onEndTimelineGesture: () -> Unit,
    onMove: (Long) -> Unit,
    onResize: (Boolean, Long) -> Unit,
    onVerticalDrag: (Float) -> Unit,
    onVerticalDragEnd: () -> Unit,
    visibleFromMs: Long,
    visibleToMs: Long
) {
    val density = LocalDensity.current
    val selectScale by animateFloatAsState(
        targetValue = if (isSelected) 1.02f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "clipSelectScale"
    )
    val borderModifier = if (isSelected) {
        Modifier.border(2.dp, SelectedClipBorder, RoundedCornerShape(6.dp))
    } else {
        Modifier.border(1.dp, Color(0xFF2C3240), RoundedCornerShape(6.dp))
    }
    var totalDragY by remember(clip.id) { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .width(width)
            .height(LAYER_ROW_HEIGHT - 6.dp)
            .graphicsLayer { scaleX = selectScale; scaleY = selectScale }
            .clip(RoundedCornerShape(6.dp))
            .then(borderModifier)
            .pointerInput(clip.id, isLocked, width) {
                detectDragGestures(
                    onDragStart = {
                        if (!isLocked) {
                            totalDragY = 0f
                            onBeginTimelineGesture()
                            onClick()
                        }
                    },
                    onDrag = { change, amount ->
                        if (isLocked) return@detectDragGestures
                        change.consume()
                        val deltaMs = (amount.x / density.density / TIMELINE_PX_PER_MS).roundToLong()
                        if (deltaMs != 0L) onMove(deltaMs)
                        totalDragY += amount.y
                        onVerticalDrag(amount.y)
                    },
                    onDragEnd = {
                        if (!isLocked) {
                            onVerticalDragEnd()
                            onEndTimelineGesture()
                        }
                        totalDragY = 0f
                    },
                    onDragCancel = {
                        if (!isLocked) onEndTimelineGesture()
                        totalDragY = 0f
                    }
                )
            }
            .bouncyClickable(enabled = !isLocked, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        when (clip.type) {
            LayerType.VIDEO -> {
                if (clip.uri != null) {
                    VideoFrameStrip(
                        clip = clip,
                        widthDp = width,
                        visibleFromMs = visibleFromMs,
                        visibleToMs = visibleToMs
                    )
                } else {
                    Row(Modifier.fillMaxSize()) {
                        val tileRes = when {
                            clip.name.contains("Yellow") || clip.name.contains("Vlog") -> R.drawable.sample_portrait_1791121452621
                            clip.name.contains("Canopy") || clip.name.contains("Trees") -> R.drawable.sample_nature_1791121466670
                            else -> R.drawable.sample_portrait_1791121452621
                        }
                        repeat(((width.value / 38).toInt()).coerceAtLeast(1)) {
                            Image(
                                painter = painterResource(id = tileRes),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }
                }
            }
            LayerType.AUDIO -> {
                Row(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF1E232A)).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(14.dp))
                    repeat(14) { Box(Modifier.width(13.dp).height(4.dp).background(Color(0xFF4B5563), RoundedCornerShape(2.dp))) }
                }
            }
            LayerType.SHAPE -> {
                Box(Modifier.fillMaxSize().background(Color(0xFF0F2537))) {
                    Canvas(Modifier.fillMaxSize()) {
                        val dotSpacing = 8.dp.toPx()
                        val cols = (size.width / dotSpacing).toInt()
                        val rows = (size.height / dotSpacing).toInt()
                        for (r in 1 until rows) for (c in 1 until cols) {
                            drawCircle(NeonCyan, 1.6.dp.toPx(), Offset(c * dotSpacing, r * dotSpacing))
                        }
                    }
                }
            }
            LayerType.OVERLAY -> {
                Box(Modifier.fillMaxSize().background(Color(0xFF2A1C2C)), contentAlignment = Alignment.CenterStart) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 6.dp)) {
                        Image(painter = painterResource(R.drawable.sample_cyan_dots_1791121437975), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(34.dp))
                        Image(painter = painterResource(R.drawable.sample_portrait_1791121452621), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(34.dp))
                    }
                }
            }
            else -> {
                Box(Modifier.fillMaxSize().background(Color(clip.color)), contentAlignment = Alignment.CenterStart) {
                    Text(clip.name, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }

        Text(
            text = clip.name,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 4.dp, vertical = 1.dp)
        )

        if (isSelected) {
            // Duration badge.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .height(26.dp)
                    .clip(RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                    .background(Color.White)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(String.format(java.util.Locale.US, "%.1fs", clip.durationMs / 1000f), color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }

            // Left/right trim handles: drag both sides to shorten/extend.
            ResizeHandle(
                isLeft = true,
                modifier = Modifier.align(Alignment.CenterStart),
                onBegin = onBeginTimelineGesture,
                onDragDelta = { deltaPx ->
                    val edge = clip.startMs + (deltaPx / density.density / TIMELINE_PX_PER_MS).roundToLong()
                    onResize(true, edge)
                },
                onEnd = onEndTimelineGesture
            )
            ResizeHandle(
                isLeft = false,
                modifier = Modifier.align(Alignment.CenterEnd),
                onBegin = onBeginTimelineGesture,
                onDragDelta = { deltaPx ->
                    val edge = clip.endMs + (deltaPx / density.density / TIMELINE_PX_PER_MS).roundToLong()
                    onResize(false, edge)
                },
                onEnd = onEndTimelineGesture
            )
        }
    }
}

@Composable
private fun ResizeHandle(
    isLeft: Boolean,
    modifier: Modifier,
    onBegin: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onEnd: () -> Unit
) {
    Box(
        modifier = modifier
            .width(11.dp)
            .fillMaxHeight()
            .background(Color.White.copy(alpha = 0.12f))
            .pointerInput(isLeft) {
                detectHorizontalDragGestures(
                    onDragStart = { onBegin() },
                    onHorizontalDrag = { change, delta -> change.consume(); onDragDelta(delta) },
                    onDragEnd = onEnd,
                    onDragCancel = onEnd
                )
            }
    ) {
        Box(
            modifier = Modifier
                .align(if (isLeft) Alignment.CenterStart else Alignment.CenterEnd)
                .width(2.dp)
                .fillMaxHeight(0.55f)
                .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(2.dp))
        )
    }
}

/** Higher = shorter glide after a swipe. 0.55 glides ~2x further than the Android default. */
private const val FLING_FRICTION = 0.55f

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
