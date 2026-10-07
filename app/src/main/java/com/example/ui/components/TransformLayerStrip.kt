package com.example.ui.components

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.LayerType
import com.example.model.TIMELINE_PX_PER_MS
import com.example.model.TimelineClip
import com.example.ui.theme.EditorBackground
import com.example.ui.theme.IconActive
import com.example.ui.theme.PlayheadColor
import com.example.ui.theme.TextPrimary
import com.example.ui.util.bouncyClickable
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Top bar shown while Transform is open (replaces the normal editor header):
 * a collapse chevron on the left (= back) and the "Transform" title in the middle.
 */
@Composable
fun TransformHeaderBar(
    height: Dp,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(EditorBackground)
            // swallow touches so nothing underneath (the real header) can be hit
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp)
                .size(44.dp)
                .bouncyClickable(onClick = onBack)
                .testTag("transform_back_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Back",
                tint = IconActive,
                modifier = Modifier.size(30.dp)
            )
        }
        Text(
            text = "Transform",
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/**
 * Playback row for the selected layer: prev / play / next on the centre, undo / redo on the right.
 * Prev / next jump to the start / end of the layer that is being transformed.
 */
@Composable
fun TransformPlaybackRow(
    clip: TimelineClip?,
    totalDurationMs: Long,
    isPlaying: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onTogglePlay: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val startMs = clip?.startMs ?: 0L
    val lastMs = ((clip?.endMs ?: totalDurationMs) - 1L).coerceAtLeast(startMs)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier.width(72.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(36.dp).bouncyClickable(onClick = { onSeekTo(startMs) }),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.SkipPrevious, "Layer start", tint = IconActive, modifier = Modifier.size(24.dp))
            }
            Box(
                modifier = Modifier.size(40.dp).bouncyClickable(onClick = onTogglePlay),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = IconActive,
                    modifier = Modifier.size(30.dp)
                )
            }
            Box(
                modifier = Modifier.size(36.dp).bouncyClickable(onClick = { onSeekTo(lastMs) }),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.SkipNext, "Layer end", tint = IconActive, modifier = Modifier.size(24.dp))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(32.dp).bouncyClickable(enabled = canUndo, onClick = onUndo),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo, "Undo",
                    tint = if (canUndo) IconActive else Color(0xFF4B5262),
                    modifier = Modifier.size(20.dp)
                )
            }
            Box(
                modifier = Modifier.size(32.dp).bouncyClickable(enabled = canRedo, onClick = onRedo),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Redo, "Redo",
                    tint = if (canRedo) IconActive else Color(0xFF4B5262),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private val StripLeftInset = 56.dp
private const val STRIP_FLING_FRICTION = 0.55f

/**
 * The selected layer on its own, drawn at the real timeline scale and scrolled under a fixed centre
 * playhead (exactly like the main timeline). Dragging it scrubs the project time.
 * Reset button on the left, keyframe + easing icons on the right.
 */
@Composable
fun TransformLayerStrip(
    clip: TimelineClip?,
    currentPlayheadMs: Long,
    totalDurationMs: Long,
    onSeekTo: (Long) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var flingJob by remember { mutableStateOf<Job?>(null) }
    val latestPlayheadMs by rememberUpdatedState(currentPlayheadMs)
    val latestTotalMs by rememberUpdatedState(totalDurationMs)
    val latestSeek by rememberUpdatedState(onSeekTo)
    val density = LocalDensity.current.density

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag("transform_layer_strip")
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    flingJob?.cancel()
                }
            }
            .pointerInput(Unit) {
                val velocityTracker = VelocityTracker()
                var posMs = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        flingJob?.cancel()
                        velocityTracker.resetTracking()
                        posMs = latestPlayheadMs.toFloat()
                    },
                    onHorizontalDrag = { change, dragPx ->
                        change.consume()
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        val deltaMs = -(dragPx / density) / TIMELINE_PX_PER_MS
                        posMs = (posMs + deltaMs).coerceIn(0f, latestTotalMs.toFloat())
                        latestSeek(posMs.roundToLong())
                    },
                    onDragEnd = {
                        val vx = velocityTracker.calculateVelocity().x
                        if (abs(vx) > 200f) {
                            val startMs = posMs
                            val velMsPerSec = -(vx / density) / TIMELINE_PX_PER_MS
                            flingJob = scope.launch {
                                AnimationState(initialValue = startMs, initialVelocity = velMsPerSec)
                                    .animateDecay(
                                        exponentialDecay(frictionMultiplier = STRIP_FLING_FRICTION, absVelocityThreshold = 40f)
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
        val stripWidth = maxWidth
        val centerX = stripWidth / 2
        // centre of the playhead measured inside the clip viewport (which starts after the reset button)
        val centerInViewport = centerX - StripLeftInset

        // 1. Clip viewport
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = StripLeftInset)
                .clipToBounds()
        ) {
            if (clip != null) {
                val clipWidthDp = (clip.durationMs * TIMELINE_PX_PER_MS).dp.coerceAtLeast(36.dp)
                val xDp = centerInViewport.value + (clip.startMs - currentPlayheadMs) * TIMELINE_PX_PER_MS
                val wPx = (clipWidthDp.value * density).roundToInt().coerceIn(1, 200_000)
                val xPx = (xDp * density).roundToInt()

                val marginMs = (100f / TIMELINE_PX_PER_MS).toLong()
                val visibleFromMs = currentPlayheadMs - (centerInViewport.value / TIMELINE_PX_PER_MS).toLong() - marginMs
                val visibleToMs = currentPlayheadMs +
                    ((stripWidth - centerX).value / TIMELINE_PX_PER_MS).toLong() + marginMs

                Box(
                    modifier = Modifier
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(Constraints.fixed(wPx, constraints.maxHeight))
                            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(xPx, 0) }
                        }
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(6.dp))
                ) {
                    if (clip.type == LayerType.SHAPE) {
                        ShapeStripContent(clip)
                    } else {
                        TimelineClipItem(
                            clip = clip,
                            width = clipWidthDp,
                            isSelected = false,
                            isLocked = false,
                            onClick = {},
                            onBeginTimelineGesture = {},
                            onEndTimelineGesture = {},
                            onMove = {},
                            onResize = { _, _ -> },
                            onVerticalDrag = {},
                            onVerticalDragEnd = {},
                            visibleFromMs = visibleFromMs,
                            visibleToMs = visibleToMs
                        )
                    }
                }
            }
        }

        // 2. Reset button (left)
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp)
                .size(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF1D212B))
                .border(1.dp, Color(0xFF333A4A), RoundedCornerShape(6.dp))
                .bouncyClickable(onClick = onReset),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Refresh, "Reset Transform", tint = IconActive, modifier = Modifier.size(18.dp))
        }

        // 3. Keyframe + easing icons (right)
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer { rotationZ = 45f }
                    .border(1.5.dp, Color.White, RoundedCornerShape(4.dp))
                    .bouncyClickable(onClick = {}),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Keyframe",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = -45f }
                )
            }
            Spacer(modifier = Modifier.width(18.dp))
            Canvas(modifier = Modifier.size(20.dp)) {
                val path = Path().apply {
                    moveTo(0f, size.height)
                    cubicTo(size.width * 0.4f, size.height, size.width * 0.6f, 0f, size.width, 0f)
                }
                drawPath(path, color = Color(0xFF8E99AF), style = Stroke(width = 2.dp.toPx()))
            }
        }

        // 4. Fixed centre playhead, in line with the timescale divider above
        Canvas(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .offset(x = centerX - 1.dp)
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

/** Checkerboard + the layer's own shape tiled along its length (shapes have no video frames). */
@Composable
private fun ShapeStripContent(clip: TimelineClip) {
    val shapeColor = stripShapeColor(clip)
    val pattern = clip.shapePattern ?: "CIRCLE"
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cell = 6.dp.toPx()
        drawRect(Color(0xFFF2F2F2))
        val cols = ceil(size.width / cell).toInt()
        val rows = ceil(size.height / cell).toInt()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if ((r + c) % 2 == 0) {
                    drawRect(
                        color = Color(0xFFD6D6D6),
                        topLeft = Offset(c * cell, r * cell),
                        size = Size(cell, cell)
                    )
                }
            }
        }

        val tile = size.height * 0.86f
        val pitch = size.height * 1.4f
        var x = (size.height - tile) / 2f + 2.dp.toPx()
        val y = (size.height - tile) / 2f
        while (x < size.width) {
            drawStripShape(pattern, shapeColor, Offset(x, y), tile)
            x += pitch
        }
    }
}

private fun DrawScope.drawStripShape(pattern: String, color: Color, topLeft: Offset, side: Float) {
    when (pattern) {
        "SQUARE" -> drawRect(color, topLeft, Size(side, side))
        "ROUNDED_SQUARE", "ROUNDED_RECT", "WIDE_ROUNDED_RECT" ->
            drawRoundRect(color, topLeft, Size(side, side), CornerRadius(side * 0.22f, side * 0.22f))
        "OVAL" -> drawOval(color, topLeft, Size(side, side * 0.72f))
        "PENTAGON", "STAR" -> {
            val isStar = pattern == "STAR"
            val count = if (isStar) 10 else 5
            val outer = side / 2f
            val inner = outer * 0.45f
            val cx = topLeft.x + side / 2f
            val cy = topLeft.y + side / 2f
            val path = Path().apply {
                for (i in 0 until count) {
                    val angle = Math.toRadians(i * 360.0 / count - 90.0)
                    val rad = if (isStar && i % 2 == 1) inner else outer
                    val px = (cx + rad * cos(angle)).toFloat()
                    val py = (cy + rad * sin(angle)).toFloat()
                    if (i == 0) moveTo(px, py) else lineTo(px, py)
                }
                close()
            }
            drawPath(path, color)
        }
        else -> drawCircle(color, radius = side / 2f, center = Offset(topLeft.x + side / 2f, topLeft.y + side / 2f))
    }
}

private fun stripShapeColor(clip: TimelineClip): Color {
    val raw = if (clip.textColor != 0L) clip.textColor else clip.color
    val a = ((raw shr 24) and 0xFF).toInt()
    val r = ((raw shr 16) and 0xFF).toInt()
    val g = ((raw shr 8) and 0xFF).toInt()
    val b = (raw and 0xFF).toInt()
    return if (a == 0 && r == 0 && g == 0 && b == 0) Color(0xFFFF3377) else Color(r, g, b, if (a == 0) 255 else a)
}
