package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.model.AspectRatio
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.ui.theme.CanvasBackground
import com.example.ui.theme.EditorBackground
import com.example.ui.theme.IconActive
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.RulerSubTickColor
import com.example.ui.theme.RulerTickColor
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.util.bouncyClickable

enum class TransformTab(val title: String) {
    TWO_D("2D"),
    PIVOT("Pivot"),
    THREE_D("3D"),
    WARP("Warp")
}

@Composable
fun TransformView(
    clip: TimelineClip?,
    aspectRatio: AspectRatio = AspectRatio.RATIO_16_9,
    currentPlayheadMs: Long,
    totalDurationMs: Long,
    isPlaying: Boolean,
    player: Player? = null,
    hasMainMedia: Boolean = false,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onTogglePlay: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    onDismiss: () -> Unit,
    panelOnly: Boolean = false,
    onApplyTransform: (
        horizontal: Float,
        vertical: Float,
        scale: Float,
        rotation: Float,
        pivotX: Float,
        pivotY: Float,
        rot3dX: Float,
        rot3dY: Float,
        warp: Float
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    // Current active mode tab: 2D, Pivot, 3D, Warp
    var activeTab by remember { mutableStateOf(TransformTab.TWO_D) }

    // 2D Transform state
    var horizontal by remember(clip?.id) { mutableFloatStateOf(clip?.transformX ?: 0.0f) }
    var vertical by remember(clip?.id) { mutableFloatStateOf(clip?.transformY ?: 0.0f) }
    var scale by remember(clip?.id) { mutableFloatStateOf(clip?.scalePercent ?: 100.0f) }
    var rotation by remember(clip?.id) { mutableFloatStateOf(clip?.rotationDeg ?: 0.0f) }

    // Pivot state
    var pivotX by remember(clip?.id) { mutableFloatStateOf(clip?.pivotX ?: 0.5f) }
    var pivotY by remember(clip?.id) { mutableFloatStateOf(clip?.pivotY ?: 0.5f) }

    // 3D Transform state
    var rot3dX by remember(clip?.id) { mutableFloatStateOf(clip?.rotation3dX ?: 0.0f) }
    var rot3dY by remember(clip?.id) { mutableFloatStateOf(clip?.rotation3dY ?: 0.0f) }

    // Warp state
    var warpDistort by remember(clip?.id) { mutableFloatStateOf(clip?.warpDistort ?: 0.0f) }

    LaunchedEffect(clip?.id) {
        if (clip != null) {
            horizontal = clip.transformX
            vertical = clip.transformY
            scale = clip.scalePercent
            rotation = clip.rotationDeg
            pivotX = clip.pivotX
            pivotY = clip.pivotY
            rot3dX = clip.rotation3dX
            rot3dY = clip.rotation3dY
            warpDistort = clip.warpDistort
        }
    }

    fun emitTransform() {
        onApplyTransform(
            horizontal,
            vertical,
            scale,
            rotation,
            pivotX,
            pivotY,
            rot3dX,
            rot3dY,
            warpDistort
        )
    }

    fun resetAll() {
        horizontal = 0.0f
        vertical = 0.0f
        scale = 100.0f
        rotation = 0.0f
        pivotX = 0.5f
        pivotY = 0.5f
        rot3dX = 0.0f
        rot3dY = 0.0f
        warpDistort = 0.0f
        emitTransform()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(if (panelOnly) EditorBackground else Color(0xFF0F1116))
            .testTag("transform_screen")
    ) {
        if (panelOnly) {
            // Playback controls, timescale and the selected layer's own strip, then the panel at the bottom.
            TransformPlaybackRow(
                clip = clip,
                totalDurationMs = totalDurationMs,
                isPlaying = isPlaying,
                canUndo = canUndo,
                canRedo = canRedo,
                onTogglePlay = onTogglePlay,
                onSeekTo = onSeekTo,
                onUndo = onUndo,
                onRedo = onRedo
            )
            TimescaleSection(
                height = 28.dp,
                playheadMs = { currentPlayheadMs },
                totalDurationMs = totalDurationMs
            )
            TransformLayerStrip(
                clip = clip,
                currentPlayheadMs = currentPlayheadMs,
                totalDurationMs = totalDurationMs,
                onSeekTo = onSeekTo,
                onReset = { resetAll() }
            )
            Spacer(modifier = Modifier.weight(1f))
        }

        if (!panelOnly) {
        // 1. Header: Left down arrow button, Center "Transform" title, Right "Done" button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .bouncyClickable(onClick = {
                        emitTransform()
                        onDismiss()
                    })
                    .testTag("transform_back_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Collapse Transform",
                    tint = IconActive,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "Transform",
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(NeonCyan)
                    .bouncyClickable(onClick = {
                        emitTransform()
                        onDismiss()
                    })
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Done",
                    color = EditorBackground,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 2. Video Preview Canvas with Transformed frame and Magenta Reticle Crosshair
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 4.dp),
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

            val isMainVideo = (clip == null || clip.type == LayerType.VIDEO)

            Box(
                modifier = Modifier
                    .size(width = frameW, height = frameH)
                    .clip(RoundedCornerShape(8.dp))
                    .background(CanvasBackground)
                    .shadow(elevation = 6.dp, shape = RoundedCornerShape(8.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                    .pointerInput(clip?.id) {
                        var startDragH = horizontal
                        var startDragV = vertical
                        detectDragGestures(
                            onDragStart = {
                                startDragH = horizontal
                                startDragV = vertical
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                startDragH += dragAmount.x
                                startDragV += dragAmount.y
                                horizontal = startDragH
                                vertical = startDragV
                                emitTransform()
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isMainVideo) {
                    // Transforming the main video track
                    if (player != null && hasMainMedia) {
                        AndroidView(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationX = horizontal
                                    translationY = vertical
                                    scaleX = (scale / 100f).coerceAtLeast(0.05f)
                                    scaleY = (scale / 100f).coerceAtLeast(0.05f)
                                    rotationZ = rotation
                                    rotationX = rot3dX
                                    rotationY = rot3dY
                                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(pivotX, pivotY)
                                    cameraDistance = 12f * density
                                },
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    useController = false
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    this.player = player
                                }
                            },
                            update = { it.player = player }
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationX = horizontal
                                    translationY = vertical
                                    scaleX = (scale / 100f).coerceAtLeast(0.05f)
                                    scaleY = (scale / 100f).coerceAtLeast(0.05f)
                                    rotationZ = rotation
                                    rotationX = rot3dX
                                    rotationY = rot3dY
                                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(pivotX, pivotY)
                                    cameraDistance = 12f * density
                                }
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                drawRect(color = CanvasBackground)
                                val w = size.width
                                val h = size.height
                                drawLine(
                                    color = Color.White.copy(alpha = 0.08f),
                                    start = Offset(w / 2f, 0f),
                                    end = Offset(w / 2f, h),
                                    strokeWidth = 1.dp.toPx()
                                )
                                drawLine(
                                    color = Color.White.copy(alpha = 0.08f),
                                    start = Offset(0f, h / 2f),
                                    end = Offset(w, h / 2f),
                                    strokeWidth = 1.dp.toPx()
                                )
                            }
                        }
                    }

                    // Main track anchor reticle
                    PivotTargetCrosshair(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(
                                x = (horizontal + (pivotX - 0.5f) * frameW.value).dp,
                                y = (vertical + (pivotY - 0.5f) * frameH.value).dp
                            )
                    )
                } else {
                    // Transforming an overlay (e.g. Shape, Text, Sticker)
                    // 1. Base background media/canvas
                    if (player != null && hasMainMedia) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    useController = false
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    this.player = player
                                }
                            },
                            update = { it.player = player }
                        )
                    } else {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawRect(color = CanvasBackground)
                            val w = size.width
                            val h = size.height
                            drawLine(
                                color = Color.White.copy(alpha = 0.08f),
                                start = Offset(w / 2f, 0f),
                                end = Offset(w / 2f, h),
                                strokeWidth = 1.dp.toPx()
                            )
                            drawLine(
                                color = Color.White.copy(alpha = 0.08f),
                                start = Offset(0f, h / 2f),
                                end = Offset(w, h / 2f),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                    }

                    // 2. Transformed overlay layer
                    if (clip.type == LayerType.SHAPE) {
                        val rawColor = if (clip.textColor != 0L) clip.textColor else clip.color
                        val a = ((rawColor shr 24) and 0xFF).toInt()
                        val r = ((rawColor shr 16) and 0xFF).toInt()
                        val g = ((rawColor shr 8) and 0xFF).toInt()
                        val b = (rawColor and 0xFF).toInt()
                        val shapeColor = if (a == 0 && r == 0 && g == 0 && b == 0) {
                            Color(0xFFFF3377)
                        } else {
                            Color(r, g, b, if (a == 0) 255 else a)
                        }

                        val strokeRaw = clip.strokeColor
                        val strA = ((strokeRaw shr 24) and 0xFF).toInt()
                        val strR = ((strokeRaw shr 16) and 0xFF).toInt()
                        val strG = ((strokeRaw shr 8) and 0xFF).toInt()
                        val strB = (strokeRaw and 0xFF).toInt()
                        val strokeCol = Color(strR, strG, strB, if (strA == 0) 255 else strA)

                        val pattern = clip.shapePattern ?: "CIRCLE"
                        val baseSize = 130.dp

                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .graphicsLayer {
                                    translationX = horizontal
                                    translationY = vertical
                                    scaleX = (scale / 100f).coerceAtLeast(0.05f)
                                    scaleY = (scale / 100f).coerceAtLeast(0.05f)
                                    rotationZ = rotation
                                    rotationX = rot3dX
                                    rotationY = rot3dY
                                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(pivotX, pivotY)
                                    cameraDistance = 12f * density
                                }
                                .size(baseSize)
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                when (pattern) {
                                    "CIRCLE" -> {
                                        drawCircle(color = shapeColor)
                                        if (clip.strokeWidth > 0f) {
                                            drawCircle(color = strokeCol, style = Stroke(clip.strokeWidth.dp.toPx()))
                                        }
                                    }
                                    "SQUARE" -> {
                                        drawRect(color = shapeColor)
                                        if (clip.strokeWidth > 0f) {
                                            drawRect(color = strokeCol, style = Stroke(clip.strokeWidth.dp.toPx()))
                                        }
                                    }
                                    "ROUNDED_SQUARE", "ROUNDED_RECT" -> {
                                        drawRoundRect(
                                            color = shapeColor,
                                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx(), 16.dp.toPx())
                                        )
                                        if (clip.strokeWidth > 0f) {
                                            drawRoundRect(
                                                color = strokeCol,
                                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                                                style = Stroke(clip.strokeWidth.dp.toPx())
                                            )
                                        }
                                    }
                                    "WIDE_ROUNDED_RECT" -> {
                                        drawRoundRect(
                                            color = shapeColor,
                                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx(), 10.dp.toPx())
                                        )
                                        if (clip.strokeWidth > 0f) {
                                            drawRoundRect(
                                                color = strokeCol,
                                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx(), 10.dp.toPx()),
                                                style = Stroke(clip.strokeWidth.dp.toPx())
                                            )
                                        }
                                    }
                                    "OVAL" -> {
                                        drawOval(color = shapeColor)
                                        if (clip.strokeWidth > 0f) {
                                            drawOval(color = strokeCol, style = Stroke(clip.strokeWidth.dp.toPx()))
                                        }
                                    }
                                    "PENTAGON", "STAR" -> {
                                        val path = Path().apply {
                                            val rad = size.minDimension / 2f
                                            val cX = size.width / 2f
                                            val cY = size.height / 2f
                                            val points = 5
                                            for (i in 0 until points) {
                                                val angle = Math.toRadians((i * 360.0 / points) - 90.0)
                                                val px = (cX + rad * Math.cos(angle)).toFloat()
                                                val py = (cY + rad * Math.sin(angle)).toFloat()
                                                if (i == 0) moveTo(px, py) else lineTo(px, py)
                                            }
                                            close()
                                        }
                                        drawPath(path, color = shapeColor)
                                        if (clip.strokeWidth > 0f) {
                                            drawPath(path, color = strokeCol, style = Stroke(clip.strokeWidth.dp.toPx()))
                                        }
                                    }
                                    else -> drawCircle(color = shapeColor)
                                }
                            }

                            // Pink selection outline on the transformed shape
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .border(1.5.dp, Color(0xFFFF3377), RoundedCornerShape(2.dp))
                            )
                        }

                        // Pink Reticle Anchor Crosshair Target on the shape
                        PivotTargetCrosshair(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .offset(
                                    x = (horizontal + (pivotX - 0.5f) * baseSize.value).dp,
                                    y = (vertical + (pivotY - 0.5f) * baseSize.value).dp
                                )
                        )
                    } else if (clip.text != null || clip.type == LayerType.TEXT) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .graphicsLayer {
                                    translationX = horizontal
                                    translationY = vertical
                                    scaleX = (scale / 100f).coerceAtLeast(0.05f)
                                    scaleY = (scale / 100f).coerceAtLeast(0.05f)
                                    rotationZ = rotation
                                    rotationX = rot3dX
                                    rotationY = rot3dY
                                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(pivotX, pivotY)
                                    cameraDistance = 12f * density
                                }
                                .border(1.dp, Color.White)
                        ) {
                            TextLayerContent(clip = clip, frameWidth = frameW)
                        }

                        PivotTargetCrosshair(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .offset(
                                    x = (horizontal + (pivotX - 0.5f) * 100f).dp,
                                    y = (vertical + (pivotY - 0.5f) * 50f).dp
                                )
                        )
                    }
                }
            }
        }

        // 3. Playback Controls Row: Skip Prev, Play/Pause, Skip Next, Undo, Redo
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.width(36.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .bouncyClickable(onClick = { onSeekTo(0L) }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint = IconActive,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .bouncyClickable(onClick = onTogglePlay),
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
                    modifier = Modifier
                        .size(36.dp)
                        .bouncyClickable(onClick = { onSeekTo(totalDurationMs) }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = IconActive,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .bouncyClickable(enabled = canUndo, onClick = onUndo),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "Undo",
                        tint = if (canUndo) IconActive else Color(0xFF4B5262),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .bouncyClickable(enabled = canRedo, onClick = onRedo),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Redo,
                        contentDescription = "Redo",
                        tint = if (canRedo) IconActive else Color(0xFF4B5262),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // 4. Timescale: 00:03 47 | 00:19 49 with ruler ticks
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.weight(0.36f))
            Text(
                text = formatTimeCode(currentPlayheadMs),
                color = TextPrimary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(text = "  |  ", color = TextSecondary, fontSize = 13.sp)
            Text(
                text = formatTimeCode(totalDurationMs),
                color = TextSecondary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(8.dp))

            Canvas(
                modifier = Modifier
                    .weight(0.48f)
                    .height(14.dp)
            ) {
                val tickSpacing = 8.dp.toPx()
                val count = (size.width / tickSpacing).toInt()
                for (i in 0..count) {
                    val isMajor = (i % 5 == 0)
                    drawLine(
                        color = if (isMajor) RulerTickColor else RulerSubTickColor,
                        start = Offset(i * tickSpacing, 0f),
                        end = Offset(i * tickSpacing, if (isMajor) size.height else size.height * 0.5f),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }
        }

        // 5. Mini Layer Strip: [Reset button on left] [Timeline strip with keyframe diamond and easing icon]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp)
                .height(34.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Reset Button on left (matching screenshot)
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1D212B))
                    .border(1.dp, Color(0xFF333A4A), RoundedCornerShape(6.dp))
                    .bouncyClickable(onClick = {
                        horizontal = 0.0f
                        vertical = 0.0f
                        scale = 100.0f
                        rotation = 0.0f
                        pivotX = 0.5f
                        pivotY = 0.5f
                        rot3dX = 0.0f
                        rot3dY = 0.0f
                        warpDistort = 0.0f
                        emitTransform()
                    }),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reset Transform",
                    tint = IconActive,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Thumbnail strip container
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(30.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1E222D))
                    .border(1.dp, Color(0xFF2C3240), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                // Keyframe diamond indicator & Easing curve icon on right
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer { rotationZ = 45f }
                            .background(Color(0xFF3B4150), RoundedCornerShape(2.dp))
                            .bouncyClickable(onClick = {}),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Keyframe",
                            tint = Color.White,
                            modifier = Modifier
                                .size(13.dp)
                                .graphicsLayer { rotationZ = -45f }
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    // Easing S-Curve Icon (matching screenshot)
                    Canvas(modifier = Modifier.size(18.dp)) {
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(0f, size.height)
                            cubicTo(
                                size.width * 0.4f, size.height,
                                size.width * 0.6f, 0f,
                                size.width, 0f
                            )
                        }
                        drawPath(
                            path = path,
                            color = Color(0xFF8E99AF),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                        )
                    }
                }
            }
        }

        }

        // 6. Bottom Transform Panel (matching screenshot)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(Color(0xFF14171E))
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            // Drag handle pill at top
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(36.dp)
                    .height(4.dp)
                    .background(Color(0xFF3F4654), RoundedCornerShape(2.dp))
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Dial Sliders depending on Active Mode
            AnimatedContent(
                targetState = activeTab,
                transitionSpec = {
                    fadeIn(androidx.compose.animation.core.tween(120, delayMillis = 30)) togetherWith
                        fadeOut(androidx.compose.animation.core.tween(70))
                },
                label = "transformTabTransition"
            ) { currentTab ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    when (currentTab) {
                        TransformTab.TWO_D -> {
                            // Horizontal + Vertical in one swipe pad
                            TransformMovePad(
                                x = horizontal,
                                y = vertical,
                                onMove = { nx, ny ->
                                    horizontal = nx
                                    vertical = ny
                                    emitTransform()
                                }
                            )

                            // Scale (100.0%)
                            RulerDialSlider(
                                label = "Scale",
                                value = scale,
                                onValueChange = {
                                    scale = it
                                    emitTransform()
                                },
                                min = 10f,
                                max = 400f,
                                unit = "",
                                isPercentage = true
                            )

                            // Rotation (0.0°)
                            RulerDialSlider(
                                label = "Rotation",
                                value = rotation,
                                onValueChange = {
                                    rotation = it
                                    emitTransform()
                                },
                                min = -180f,
                                max = 180f,
                                unit = "°",
                                isPercentage = false
                            )
                        }

                        TransformTab.PIVOT -> {
                            RulerDialSlider(
                                label = "Pivot X",
                                value = (pivotX - 0.5f) * 100f,
                                onValueChange = {
                                    pivotX = (it / 100f) + 0.5f
                                    emitTransform()
                                },
                                min = -100f,
                                max = 100f,
                                unit = "%",
                                isPercentage = false
                            )

                            RulerDialSlider(
                                label = "Pivot Y",
                                value = (pivotY - 0.5f) * 100f,
                                onValueChange = {
                                    pivotY = (it / 100f) + 0.5f
                                    emitTransform()
                                },
                                min = -100f,
                                max = 100f,
                                unit = "%",
                                isPercentage = false
                            )

                            // Preset anchors
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf("Top-Left" to (0f to 0f), "Center" to (0.5f to 0.5f), "Bottom-Right" to (1f to 1f)).forEach { (name, point) ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF232732))
                                            .bouncyClickable {
                                                pivotX = point.first
                                                pivotY = point.second
                                                emitTransform()
                                            }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(name, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }

                        TransformTab.THREE_D -> {
                            RulerDialSlider(
                                label = "Rotation X",
                                value = rot3dX,
                                onValueChange = {
                                    rot3dX = it
                                    emitTransform()
                                },
                                min = -90f,
                                max = 90f,
                                unit = "°",
                                isPercentage = false
                            )

                            RulerDialSlider(
                                label = "Rotation Y",
                                value = rot3dY,
                                onValueChange = {
                                    rot3dY = it
                                    emitTransform()
                                },
                                min = -90f,
                                max = 90f,
                                unit = "°",
                                isPercentage = false
                            )
                        }

                        TransformTab.WARP -> {
                            RulerDialSlider(
                                label = "Warp Distort",
                                value = warpDistort,
                                onValueChange = {
                                    warpDistort = it
                                    emitTransform()
                                },
                                min = -100f,
                                max = 100f,
                                unit = "%",
                                isPercentage = false
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Bottom Mode Switcher Tabs: [2D] [Pivot] [3D] [Warp] (matching screenshot)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TransformTab.values().forEach { tab ->
                    val isSelected = tab == activeTab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Color(0xFFF3F4F6) else Color(0xFF232732))
                            .bouncyClickable(
                                pressedScale = 0.94f,
                                onClick = { activeTab = tab }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tab.title,
                            color = if (isSelected) Color(0xFF111317) else Color(0xFFE5E7EB),
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

/**
 * 4-lobed Pink Crosshair Pivot Anchor Target exactly matching screenshot.
 */
@Composable
fun PivotTargetCrosshair(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(36.dp)) {
        val pinkColor = Color(0xFFFF2D78)
        val cX = size.width / 2f
        val cY = size.height / 2f
        val centerRadius = 3.5.dp.toPx()
        val armLength = 7.dp.toPx()
        val armWidth = 3.dp.toPx()
        val gap = 4.dp.toPx()

        // Center circle
        drawCircle(color = pinkColor, radius = centerRadius)
        drawCircle(color = Color.White, radius = 1.2.dp.toPx())

        // Top arm
        drawRoundRect(
            color = pinkColor,
            topLeft = Offset(cX - armWidth / 2f, cY - gap - armLength),
            size = Size(armWidth, armLength),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(armWidth / 2f, armWidth / 2f)
        )
        // Bottom arm
        drawRoundRect(
            color = pinkColor,
            topLeft = Offset(cX - armWidth / 2f, cY + gap),
            size = Size(armWidth, armLength),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(armWidth / 2f, armWidth / 2f)
        )
        // Left arm
        drawRoundRect(
            color = pinkColor,
            topLeft = Offset(cX - gap - armLength, cY - armWidth / 2f),
            size = Size(armLength, armWidth),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(armWidth / 2f, armWidth / 2f)
        )
        // Right arm
        drawRoundRect(
            color = pinkColor,
            topLeft = Offset(cX + gap, cY - armWidth / 2f),
            size = Size(armLength, armWidth),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(armWidth / 2f, armWidth / 2f)
        )
    }
}
