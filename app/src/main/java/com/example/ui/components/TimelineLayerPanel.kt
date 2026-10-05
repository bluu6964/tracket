package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(EditorBackground)
            .testTag("layer_panel_timeline")
            .pointerInput(totalDurationMs, currentPlayheadMs) {
                val velocityTracker = VelocityTracker()

                detectDragGestures(
                    onDragStart = {
                        // Immediately stop any active fling when user touches
                        flingJob?.cancel()
                        velocityTracker.resetTracking()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        velocityTracker.addPosition(change.uptimeMillis, change.position)

                        // Higher sensitivity so timeline glides generously like other video editing apps
                        val dragSensitivity = 1.35f
                        val deltaMs = (-dragAmount.x * dragSensitivity / TIMELINE_PX_PER_MS).toLong()
                        val newTime = (currentPlayheadMs + deltaMs).coerceIn(0L, totalDurationMs)
                        onSeekTo(newTime)
                    },
                    onDragEnd = {
                        val velocityX = velocityTracker.calculateVelocity().x

                        // Inertial fling momentum: swiping glides several seconds forward or backward smoothly
                        if (kotlin.math.abs(velocityX) > 200f) {
                            flingJob = coroutineScope.launch {
                                var currentV = -velocityX * 1.3f
                                var currentTime = currentPlayheadMs.toFloat()
                                val decayRate = 0.93f // Smooth physical friction

                                while (isActive && kotlin.math.abs(currentV) > 50f) {
                                    delay(16L) // 60fps update
                                    val stepDeltaMs = (currentV * 0.016f / TIMELINE_PX_PER_MS)
                                    currentTime = (currentTime + stepDeltaMs).coerceIn(0f, totalDurationMs.toFloat())
                                    onSeekTo(currentTime.toLong())

                                    if (currentTime <= 0f || currentTime >= totalDurationMs.toFloat()) {
                                        break
                                    }
                                    currentV *= decayRate
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

        // 1. Horizontal Scroll Content: All timeline tracks translated strictly proportional to currentPlayheadMs
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(verticalScrollState)
                .padding(vertical = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(x = centerLineX - (currentPlayheadMs * TIMELINE_PX_PER_MS).dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tracks.forEach { track ->
                    TimelineTrackRow(
                        track = track,
                        selectedClipId = selectedClipId,
                        onClipSelect = onClipSelect,
                        onAddMediaClick = onAddMediaClick
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
    onAddMediaClick: () -> Unit
) {
    val trackHeight = when (track.type) {
        LayerType.VIDEO -> 48.dp
        LayerType.AUDIO -> 32.dp
        LayerType.SHAPE -> 38.dp
        LayerType.OVERLAY -> 44.dp
        else -> 36.dp
    }

    Row(
        modifier = Modifier.height(trackHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        track.clips.forEach { clip ->
            val clipWidth = (clip.durationMs * TIMELINE_PX_PER_MS).dp.coerceAtLeast(40.dp)
            val isSelected = clip.id == selectedClipId

            TimelineClipItem(
                clip = clip,
                width = clipWidth,
                isSelected = isSelected,
                onClick = { onClipSelect(clip.id) }
            )
            Spacer(modifier = Modifier.width(2.dp))
        }

        // Plus (+) button at end of main video track with spring feedback
        if (track.type == LayerType.VIDEO && track.name == "Main Video") {
            Box(
                modifier = Modifier
                    .size(trackHeight)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF2B2F3A))
                    .bouncyClickable(onClick = onAddMediaClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Media",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
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
    onClick: () -> Unit
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
                Row(modifier = Modifier.fillMaxSize()) {
                    val frameCount = ((width.value / 38).toInt()).coerceAtLeast(1)
                    for (i in 0 until frameCount) {
                        AsyncImage(
                            model = clip.sourceUri,
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
                    AsyncImage(
                        model = clip.sourceUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(4.dp))
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
