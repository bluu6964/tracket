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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
    onAddMediaClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var flingJob by remember { mutableStateOf<Job?>(null) }

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
            // Touching the panel stops a running fling (without consuming the touch).
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    flingJob?.cancel()
                }
            }
            .pointerInput(Unit) {
                val velocityTracker = VelocityTracker()
                val pxPerDp = density          // PointerInputScope is a Density
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
                        // px -> dp -> ms, so the timeline follows the finger 1:1.
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
                                    exponentialDecay(
                                        frictionMultiplier = FLING_FRICTION,
                                        absVelocityThreshold = 40f
                                    )
                                ) {
                                    val total = latestTotalMs.toFloat()
                                    val ms = value.coerceIn(0f, total)
                                    latestSeek(ms.roundToLong())
                                    if (ms <= 0f || ms >= total) cancelAnimation()
                                }
                            }
                        }
                    },
                    onDragCancel = {
                        flingJob?.cancel()
                    }
                )
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
        val contentWidth = (contentEndMs * TIMELINE_PX_PER_MS).dp + screenWidth + 64.dp

        // Time window that is actually on screen (+ margin) so only visible thumbnails load.
        val marginMs = (100f / TIMELINE_PX_PER_MS).toLong()
        val visibleFromMs = currentPlayheadMs - (centerLineX.value / TIMELINE_PX_PER_MS).toLong() - marginMs
        val visibleToMs = currentPlayheadMs +
            ((screenWidth - centerLineX).value / TIMELINE_PX_PER_MS).toLong() + marginMs

        // 1. Timeline content, shifted so currentPlayheadMs sits under the center line.
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
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tracks.forEach { track ->
                    TimelineTrackRow(
                        track = track,
                        selectedClipId = selectedClipId,
                        onClipSelect = onClipSelect,
                        onAddMediaClick = onAddMediaClick,
                        visibleFromMs = visibleFromMs,
                        visibleToMs = visibleToMs
                    )
                }
            }
        }

        // 2. Left side Speaker icon with spring click animation
        Box(
            modifier = Modifier
                .offset(x = (centerLineX - 54.dp).coerceAtLeast(8.dp), y = 8.dp)
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

        // 3. Quick action buttons on the left with spring click animation
        if (selectedClipId != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = 8.dp),
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
            }
        }

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
fun TimelineTrackRow(
    track: TimelineTrack,
    selectedClipId: String?,
    onClipSelect: (String?) -> Unit,
    onAddMediaClick: () -> Unit,
    visibleFromMs: Long = Long.MIN_VALUE / 4,
    visibleToMs: Long = Long.MAX_VALUE / 4
) {
    val trackHeight = when (track.type) {
        LayerType.VIDEO -> 44.dp
        LayerType.AUDIO -> 32.dp
        LayerType.SHAPE -> 40.dp
        LayerType.OVERLAY, LayerType.TEXT -> 42.dp
        else -> 36.dp
    }

    Box(
        modifier = Modifier
            .height(trackHeight)
            .fillMaxWidth()
            .background(Color(0xFF13161F).copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.CenterStart
    ) {
        track.clips.forEach { clip ->
            val clipOffset = (clip.startMs * TIMELINE_PX_PER_MS).dp
            val clipWidth = (clip.durationMs * TIMELINE_PX_PER_MS).dp.coerceAtLeast(36.dp)
            val isSelected = clip.id == selectedClipId

            Box(modifier = Modifier.offset(x = clipOffset)) {
                TimelineClipItem(
                    clip = clip,
                    width = clipWidth,
                    isSelected = isSelected,
                    onClick = { onClipSelect(clip.id) },
                    visibleFromMs = visibleFromMs,
                    visibleToMs = visibleToMs
                )
            }
        }

        // Import button: sits right after the last clip on the main video track
        // (or at the playhead when the track is empty).
        if (track.type == LayerType.VIDEO) {
            val endMs = track.clips.maxOfOrNull { it.endMs } ?: 0L
            Box(
                modifier = Modifier
                    .offset(x = (endMs * TIMELINE_PX_PER_MS).dp + 8.dp)
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2A3042))
                    .bouncyClickable(
                        pressedScale = 0.88f,
                        hoverScale = 1.08f,
                        onClick = onAddMediaClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add media",
                    tint = IconActive,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
fun TimelineClipItem(
    clip: TimelineClip,
    width: Dp,
    isSelected: Boolean,
    onClick: () -> Unit,
    visibleFromMs: Long = Long.MIN_VALUE / 4,
    visibleToMs: Long = Long.MAX_VALUE / 4
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
