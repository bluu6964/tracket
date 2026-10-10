package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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

val CROP_PRESETS = listOf("Free", "Original", "Project", "9:16", "16:9", "1:1", "4:5", "21:9", "4:3")

@Composable
fun CropView(
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
    onApplyCrop: (
        cropWidth: Float,
        cropHeight: Float,
        cropHorizontal: Float,
        cropVertical: Float,
        preset: String
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    var activePreset by remember(clip?.id) { mutableStateOf(clip?.cropPreset ?: "Free") }

    var widthPercent by remember(clip?.id) { mutableFloatStateOf(clip?.cropWidthPercent ?: 100.0f) }
    var heightPercent by remember(clip?.id) { mutableFloatStateOf(clip?.cropHeightPercent ?: 100.0f) }
    var horizontalPercent by remember(clip?.id) { mutableFloatStateOf(clip?.cropHorizontalPercent ?: 50.0f) }
    var verticalPercent by remember(clip?.id) { mutableFloatStateOf(clip?.cropVerticalPercent ?: 50.0f) }

    LaunchedEffect(clip?.id) {
        if (clip != null) {
            widthPercent = clip.cropWidthPercent
            heightPercent = clip.cropHeightPercent
            horizontalPercent = clip.cropHorizontalPercent
            verticalPercent = clip.cropVerticalPercent
            activePreset = clip.cropPreset
        }
    }

    fun emitCrop() {
        onApplyCrop(
            widthPercent,
            heightPercent,
            horizontalPercent,
            verticalPercent,
            activePreset
        )
    }

    fun applyPresetRatio(preset: String) {
        activePreset = preset
        when (preset) {
            "Original", "Project", "Free" -> {
                widthPercent = 100f
                heightPercent = 100f
                horizontalPercent = 50f
                verticalPercent = 50f
            }
            "16:9" -> {
                widthPercent = 100f
                heightPercent = 56.25f
                horizontalPercent = 50f
                verticalPercent = 50f
            }
            "9:16" -> {
                widthPercent = 56.25f
                heightPercent = 100f
                horizontalPercent = 50f
                verticalPercent = 50f
            }
            "1:1" -> {
                widthPercent = 80f
                heightPercent = 80f
                horizontalPercent = 50f
                verticalPercent = 50f
            }
            "4:5" -> {
                widthPercent = 80f
                heightPercent = 100f
                horizontalPercent = 50f
                verticalPercent = 50f
            }
            "21:9" -> {
                widthPercent = 100f
                heightPercent = 42.8f
                horizontalPercent = 50f
                verticalPercent = 50f
            }
            "4:3" -> {
                widthPercent = 100f
                heightPercent = 75f
                horizontalPercent = 50f
                verticalPercent = 50f
            }
        }
        emitCrop()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F1116))
            .testTag("crop_screen")
    ) {
        // 1. Top Header: Down Arrow, "Crop", Done
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
                        emitCrop()
                        onDismiss()
                    })
                    .testTag("crop_back_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Collapse Crop",
                    tint = IconActive,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "Crop",
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
                        emitCrop()
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

        // 2. Interactive Crop Canvas Box with Pink Handles
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 6.dp),
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

            Box(
                modifier = Modifier
                    .size(width = frameW, height = frameH)
                    .clip(RoundedCornerShape(8.dp))
                    .background(CanvasBackground)
                    .shadow(elevation = 6.dp, shape = RoundedCornerShape(8.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                // Background Layer (Video / Shape / Text Media)
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
                } else if (clip?.type == LayerType.SHAPE) {
                    val rawColor = if (clip.textColor != 0L) clip.textColor else clip.color
                    val a = ((rawColor shr 24) and 0xFF).toInt()
                    val r = ((rawColor shr 16) and 0xFF).toInt()
                    val g = ((rawColor shr 8) and 0xFF).toInt()
                    val b = (rawColor and 0xFF).toInt()
                    val shapeColor = if (a == 0 && r == 0 && g == 0 && b == 0) Color(0xFFFF3377) else Color(r, g, b, if (a == 0) 255 else a)
                    val pattern = clip.shapePattern ?: "CIRCLE"

                    Box(
                        modifier = Modifier
                            .size(140.dp)
                            .align(Alignment.Center)
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            when (pattern) {
                                "CIRCLE" -> drawCircle(color = shapeColor)
                                "SQUARE" -> drawRect(color = shapeColor)
                                "ROUNDED_SQUARE", "ROUNDED_RECT" -> drawRoundRect(color = shapeColor, cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx(), 16.dp.toPx()))
                                else -> drawCircle(color = shapeColor)
                            }
                        }
                    }
                } else {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRect(color = Color(0xFF141722))
                        val w = size.width
                        val h = size.height
                        drawLine(color = Color.White.copy(alpha = 0.08f), start = Offset(w / 2f, 0f), end = Offset(w / 2f, h), strokeWidth = 1.dp.toPx())
                        drawLine(color = Color.White.copy(alpha = 0.08f), start = Offset(0f, h / 2f), end = Offset(w, h / 2f), strokeWidth = 1.dp.toPx())
                    }
                }

                // Animated Crop Box Bounds relative to canvas frame for smooth transitions
                val animWPercent by animateFloatAsState(targetValue = widthPercent, animationSpec = spring(), label = "cropW")
                val animHPercent by animateFloatAsState(targetValue = heightPercent, animationSpec = spring(), label = "cropH")
                val animHorizPercent by animateFloatAsState(targetValue = horizontalPercent, animationSpec = spring(), label = "cropX")
                val animVertPercent by animateFloatAsState(targetValue = verticalPercent, animationSpec = spring(), label = "cropY")

                val cropBoxW = frameW * (animWPercent / 100f)
                val cropBoxH = frameH * (animHPercent / 100f)
                val maxOffX = frameW - cropBoxW
                val maxOffY = frameH - cropBoxH
                val cropOffX = maxOffX * (animHorizPercent / 100f)
                val cropOffY = maxOffY * (animVertPercent / 100f)

                // Dark Masking overlay outside crop box
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val boxLeft = (cropOffX.value * density)
                    val boxTop = (cropOffY.value * density)
                    val boxRight = boxLeft + (cropBoxW.value * density)
                    val boxBottom = boxTop + (cropBoxH.value * density)

                    // Draw dark translucent overlay outside the cropped rectangle
                    drawRect(color = Color.Black.copy(alpha = 0.45f))
                    // Clear the inner crop rect area
                    clipRect(boxLeft, boxTop, boxRight, boxBottom, clipOp = androidx.compose.ui.graphics.ClipOp.Difference) {
                        drawRect(color = Color.Black.copy(alpha = 0.45f))
                    }
                }

                // Dashed Pink / White Crop Bounding Box with Drag Handles
                Box(
                    modifier = Modifier
                        .offset(x = cropOffX, y = cropOffY)
                        .size(width = cropBoxW, height = cropBoxH)
                        .pointerInput(Unit) {
                            var startH = horizontalPercent
                            var startV = verticalPercent
                            detectDragGestures(
                                onDragStart = {
                                    startH = horizontalPercent
                                    startV = verticalPercent
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    if (maxOffX.value > 0f) {
                                        startH += (dragAmount.x / (maxOffX.value * density)) * 100f
                                        horizontalPercent = startH.coerceIn(0f, 100f)
                                    }
                                    if (maxOffY.value > 0f) {
                                        startV += (dragAmount.y / (maxOffY.value * density)) * 100f
                                        verticalPercent = startV.coerceIn(0f, 100f)
                                    }
                                    emitCrop()
                                }
                            )
                        }
                ) {
                    // White / Pink Dashed Rect Border
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stroke = Stroke(
                            width = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                        )
                        drawRect(color = Color.White.copy(alpha = 0.95f), style = stroke)
                    }

                    // 4 Corner Pink Handles (Top-Left, Top-Right, Bottom-Left, Bottom-Right)
                    CropCircleHandle(modifier = Modifier.align(Alignment.TopStart).offset(x = (-8).dp, y = (-8).dp))
                    CropCircleHandle(modifier = Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp))
                    CropCircleHandle(modifier = Modifier.align(Alignment.BottomStart).offset(x = (-8).dp, y = 8.dp))
                    CropCircleHandle(modifier = Modifier.align(Alignment.BottomEnd).offset(x = 8.dp, y = 8.dp))

                    // 4 Edge Midpoint Handles
                    CropCircleHandle(modifier = Modifier.align(Alignment.TopCenter).offset(y = (-8).dp), sizeDp = 14.dp)
                    CropCircleHandle(modifier = Modifier.align(Alignment.BottomCenter).offset(y = 8.dp), sizeDp = 14.dp)
                    CropCircleHandle(modifier = Modifier.align(Alignment.CenterStart).offset(x = (-8).dp), sizeDp = 14.dp)
                    CropCircleHandle(modifier = Modifier.align(Alignment.CenterEnd).offset(x = 8.dp), sizeDp = 14.dp)
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

        // 4. Timescale: 00:01 70 | 00:19 49 with ruler ticks
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

        // 5. Mini Layer Strip: Reset Button on Left, Timeline Strip on Right
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp)
                .height(34.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Reset / Rotate Icon Button
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1D212B))
                    .border(1.dp, Color(0xFF333A4A), RoundedCornerShape(6.dp))
                    .bouncyClickable(onClick = {
                        widthPercent = 100f
                        heightPercent = 100f
                        horizontalPercent = 50f
                        verticalPercent = 50f
                        activePreset = "Free"
                        emitCrop()
                    }),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reset Crop",
                    tint = IconActive,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Timeline Strip
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

                    Canvas(modifier = Modifier.size(18.dp)) {
                        val path = Path().apply {
                            moveTo(0f, size.height)
                            cubicTo(size.width * 0.4f, size.height, size.width * 0.6f, 0f, size.width, 0f)
                        }
                        drawPath(path = path, color = Color(0xFF8E99AF), style = Stroke(width = 2.dp.toPx()))
                    }
                }
            }
        }

        // 6. Bottom Control Panel: Aspect Ratio Pills & Ruler Dial Sliders
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(Color(0xFF14171E))
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            // Drag handle pill
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(36.dp)
                    .height(4.dp)
                    .background(Color(0xFF3F4654), RoundedCornerShape(2.dp))
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Aspect Ratio Preset Pills Row (Free, Original, Project, 9:16, 16:9, 1:1, etc.)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(CROP_PRESETS) { preset ->
                    val isSelected = (preset == activePreset)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Color.White else Color(0xFF232834))
                            .border(1.dp, if (isSelected) Color.White else Color(0xFF363E4E), RoundedCornerShape(8.dp))
                            .bouncyClickable(onClick = { applyPresetRatio(preset) })
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = preset,
                            color = if (isSelected) Color(0xFF0F1116) else TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 4 Fine-Tune Ruler Dial Sliders (Width, Height, Horizontal, Vertical)
            RulerDialSlider(
                label = "Width",
                value = widthPercent,
                onValueChange = {
                    widthPercent = it
                    emitCrop()
                },
                min = 10f,
                max = 100f,
                unit = "%",
                isPercentage = true
            )

            RulerDialSlider(
                label = "Height",
                value = heightPercent,
                onValueChange = {
                    heightPercent = it
                    emitCrop()
                },
                min = 10f,
                max = 100f,
                unit = "%",
                isPercentage = true
            )

            RulerDialSlider(
                label = "Horizontal",
                value = horizontalPercent,
                onValueChange = {
                    horizontalPercent = it
                    emitCrop()
                },
                min = 0f,
                max = 100f,
                unit = "%",
                isPercentage = true
            )

            RulerDialSlider(
                label = "Vertical",
                value = verticalPercent,
                onValueChange = {
                    verticalPercent = it
                    emitCrop()
                },
                min = 0f,
                max = 100f,
                unit = "%",
                isPercentage = true
            )
        }
    }
}

@Composable
private fun CropCircleHandle(modifier: Modifier = Modifier, sizeDp: Dp = 16.dp) {
    Box(
        modifier = modifier
            .size(sizeDp)
            .clip(CircleShape)
            .background(Color(0xFFFF3377))
            .border(1.5.dp, Color.White, CircleShape)
    )
}
