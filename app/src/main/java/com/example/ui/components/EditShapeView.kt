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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.common.Player
import com.example.model.AspectRatio
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.CanvasBackground
import com.example.ui.theme.EditorBackground
import com.example.ui.theme.EditorSurface
import com.example.ui.theme.EditorSurfaceVariant
import com.example.ui.theme.IconActive
import com.example.ui.theme.IconDisabled
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.PlayheadColor
import com.example.ui.theme.RulerSubTickColor
import com.example.ui.theme.RulerTickColor
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.util.bouncyClickable

enum class ShapeType(val label: String) {
    WIDE_ROUNDED_RECT("Wide Rect"),
    SQUARE("Square"),
    ROUNDED_SQUARE("Rounded Square"),
    CIRCLE("Circle"),
    OVAL("Oval"),
    PENTAGON("Pentagon"),
    STAR("Star")
}

enum class ShapeEditTab(val title: String) {
    SHAPE("Shape"),
    FILL("Fill"),
    STROKE("Stroke"),
    SHADOW("Shadow"),
    GLOW("Glow")
}

@Composable
fun EditShapeView(
    aspectRatio: AspectRatio = AspectRatio.RATIO_16_9,
    currentPlayheadMs: Long = 0L,
    totalDurationMs: Long = 5000L,
    isPlaying: Boolean = false,
    player: Player? = null,
    hasMainMedia: Boolean = false,
    onTogglePlay: () -> Unit = {},
    onSeekTo: (Long) -> Unit = {},
    onDismiss: () -> Unit,
    onSaveShape: (
        shapeType: ShapeType,
        widthPercent: Float,
        heightPercent: Float,
        fillColor: Color,
        featherPercent: Float,
        offsetX: Float,
        offsetY: Float,
        strokeWidth: Float,
        strokeColor: Color
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    // Shape Parameters State
    var selectedShapeType by remember { mutableStateOf(ShapeType.CIRCLE) }
    var activeTab by remember { mutableStateOf(ShapeEditTab.SHAPE) }

    var widthPercent by remember { mutableFloatStateOf(100f) }
    var heightPercent by remember { mutableFloatStateOf(100f) }
    var featherPercent by remember { mutableFloatStateOf(0.0f) }

    var fillColor by remember { mutableStateOf(Color.White) }
    var fillOpacity by remember { mutableFloatStateOf(100f) }

    var strokeWidth by remember { mutableFloatStateOf(0f) }
    var strokeColor by remember { mutableStateOf(Color.White) }

    var shadowBlur by remember { mutableFloatStateOf(0f) }
    var glowIntensity by remember { mutableFloatStateOf(0f) }

    // Canvas Interactive Shape Transformation
    var shapeOffsetX by remember { mutableFloatStateOf(0f) }
    var shapeOffsetY by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F1116))
            .testTag("edit_shape_screen")
    ) {
        // 1. Header: Left close button, Center "Edit shape" title, Right "Add" button
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
                    .bouncyClickable(onClick = onDismiss)
                    .testTag("edit_shape_back_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cancel",
                    tint = IconActive,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "Edit shape",
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
                        onSaveShape(
                            selectedShapeType,
                            widthPercent,
                            heightPercent,
                            fillColor,
                            featherPercent,
                            shapeOffsetX,
                            shapeOffsetY,
                            strokeWidth,
                            strokeColor
                        )
                        onDismiss()
                    })
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Add",
                    color = EditorBackground,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 2. Video Preview Canvas with Shape & Coral Pink Bounding Box (Canvas ratio strictly locked)
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

            // Centered video container strictly locked to project aspect ratio
            Box(
                modifier = Modifier
                    .size(width = frameW, height = frameH)
                    .clip(RoundedCornerShape(8.dp))
                    .background(CanvasBackground)
                    .shadow(elevation = 6.dp, shape = RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
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
                    // Studio canvas with subtle alignment grid
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

                // Interactive Shape with Coral Bounding Box
                val baseShapeSize = 135.dp
                val shapeW = baseShapeSize * (widthPercent / 100f)
                val shapeH = baseShapeSize * (heightPercent / 100f)
                val pinkHandleColor = Color(0xFFFF3377)

                Box(
                    modifier = Modifier
                        .offset(x = shapeOffsetX.dp, y = shapeOffsetY.dp)
                        .size(width = shapeW, height = shapeH)
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                shapeOffsetX += dragAmount.x / 2.5f
                                shapeOffsetY += dragAmount.y / 2.5f
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Rendered Shape
                    val blurModifier = if (featherPercent > 0f) {
                        Modifier.blur((featherPercent / 8f).dp)
                    } else Modifier

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(blurModifier)
                    ) {
                        val colorWithAlpha = fillColor.copy(alpha = fillOpacity / 100f)

                        when (selectedShapeType) {
                            ShapeType.CIRCLE -> {
                                drawCircle(color = colorWithAlpha)
                                if (strokeWidth > 0f) {
                                    drawCircle(
                                        color = strokeColor,
                                        style = Stroke(width = strokeWidth.dp.toPx())
                                    )
                                }
                            }
                            ShapeType.SQUARE, ShapeType.WIDE_ROUNDED_RECT -> {
                                val cornerRadius = if (selectedShapeType == ShapeType.WIDE_ROUNDED_RECT) 12.dp.toPx() else 0f
                                drawRoundRect(
                                    color = colorWithAlpha,
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius)
                                )
                                if (strokeWidth > 0f) {
                                    drawRoundRect(
                                        color = strokeColor,
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius),
                                        style = Stroke(width = strokeWidth.dp.toPx())
                                    )
                                }
                            }
                            ShapeType.ROUNDED_SQUARE -> {
                                drawRoundRect(
                                    color = colorWithAlpha,
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx(), 20.dp.toPx())
                                )
                                if (strokeWidth > 0f) {
                                    drawRoundRect(
                                        color = strokeColor,
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx(), 20.dp.toPx()),
                                        style = Stroke(width = strokeWidth.dp.toPx())
                                    )
                                }
                            }
                            ShapeType.OVAL -> {
                                drawOval(color = colorWithAlpha)
                                if (strokeWidth > 0f) {
                                    drawOval(
                                        color = strokeColor,
                                        style = Stroke(width = strokeWidth.dp.toPx())
                                    )
                                }
                            }
                            ShapeType.PENTAGON, ShapeType.STAR -> {
                                val path = Path().apply {
                                    val r = size.minDimension / 2f
                                    val cX = size.width / 2f
                                    val cY = size.height / 2f
                                    val points = 5
                                    for (i in 0 until points) {
                                        val angle = Math.toRadians((i * 360.0 / points) - 90.0)
                                        val px = (cX + r * Math.cos(angle)).toFloat()
                                        val py = (cY + r * Math.sin(angle)).toFloat()
                                        if (i == 0) moveTo(px, py) else lineTo(px, py)
                                    }
                                    close()
                                }
                                drawPath(path, color = colorWithAlpha)
                                if (strokeWidth > 0f) {
                                    drawPath(path, color = strokeColor, style = Stroke(width = strokeWidth.dp.toPx()))
                                }
                            }
                        }
                    }

                    // Coral/Pink Bounding Box & 8 Drag Handles (matching screenshot)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(1.5.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(2.dp))
                    )

                    // 8 Pink Handles: Top-Left, Top, Top-Right, Right, Bottom-Right, Bottom, Bottom-Left, Left
                    val handlePositions = listOf(
                        Alignment.TopStart,
                        Alignment.TopCenter,
                        Alignment.TopEnd,
                        Alignment.CenterEnd,
                        Alignment.BottomEnd,
                        Alignment.BottomCenter,
                        Alignment.BottomStart,
                        Alignment.CenterStart
                    )

                    handlePositions.forEach { alignment ->
                        Box(
                            modifier = Modifier
                                .align(alignment)
                                .size(10.dp)
                                .background(pinkHandleColor, CircleShape)
                                .border(1.dp, Color.White, CircleShape)
                        )
                    }

                    // Bottom-left Grid Anchor Button (matching screenshot)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .offset(x = (-16).dp, y = 16.dp)
                            .size(24.dp)
                            .background(Color.White, CircleShape)
                            .padding(3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val dotRadius = 1.2.dp.toPx()
                            val spacing = 4.dp.toPx()
                            for (r in 0..2) {
                                for (c in 0..2) {
                                    drawCircle(
                                        color = pinkHandleColor,
                                        radius = dotRadius,
                                        center = Offset(c * spacing + 2.dp.toPx(), r * spacing + 2.dp.toPx())
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Playback Controls & Timescale & Mini Layer Strip (matching screenshot)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            // Controls: Skip Prev, Play/Pause, Skip Next, Undo, Redo
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width(40.dp))

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
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Prev", tint = IconActive, modifier = Modifier.size(24.dp))
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
                        Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = IconActive, modifier = Modifier.size(24.dp))
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", tint = IconActive, modifier = Modifier.size(20.dp))
                    }
                    Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo", tint = IconActive, modifier = Modifier.size(20.dp))
                    }
                }
            }

            // Timescale from actual project playhead and duration
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.weight(0.38f))
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
                // Ruler ticks
                Canvas(
                    modifier = Modifier
                        .weight(0.45f)
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

            // Real-time Mini Layer Strip
            val progressFraction = if (totalDurationMs > 0L) (currentPlayheadMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f) else 0f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .height(30.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(progressFraction.coerceIn(0.2f, 1f))
                        .height(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF2C3240))
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(fillColor.copy(alpha = fillOpacity / 100f), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = selectedShapeType.label,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Vertical center playhead line
                Canvas(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .align(Alignment.Center)
                ) {
                    drawLine(
                        color = Color.White,
                        start = Offset(0f, 0f),
                        end = Offset(0f, size.height),
                        strokeWidth = 2.dp.toPx()
                    )
                }
            }
        }

        // 4. Bottom Shape Editor Panel (matching screenshot)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(Color(0xFF14171E))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Drag handle pill at top
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(36.dp)
                    .height(4.dp)
                    .background(Color(0xFF3F4654), RoundedCornerShape(2.dp))
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Tab Bar: [Shape] [Fill] [Stroke] [Shadow] [Glow]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ShapeEditTab.values().forEach { tab ->
                    val isSelected = tab == activeTab
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Color(0xFFF3F4F6) else Color(0xFF232732))
                            .bouncyClickable(
                                pressedScale = 0.92f,
                                onClick = { activeTab = tab }
                            )
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tab.title,
                            color = if (isSelected) Color(0xFF111317) else Color(0xFFE5E7EB),
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Tab Contents
            when (activeTab) {
                ShapeEditTab.SHAPE -> {
                    // Shape selection row: Label "Shape" + Row of shape cards (matching screenshot)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Shape",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(62.dp)
                        )

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            items(ShapeType.values()) { shape ->
                                val isSelected = shape == selectedShapeType
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) Color(0xFF2C3240) else Color(0xFF1D212B))
                                        .border(
                                            width = if (isSelected) 2.dp else 1.dp,
                                            color = if (isSelected) Color.White else Color(0xFF3B4150),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .bouncyClickable(onClick = { selectedShapeType = shape }),
                                    contentAlignment = Alignment.Center
                                ) {
                                    // Shape mini icon representation
                                    Canvas(modifier = Modifier.size(18.dp)) {
                                        val c = Color.White
                                        when (shape) {
                                            ShapeType.CIRCLE -> drawCircle(color = c)
                                            ShapeType.SQUARE -> drawRect(color = c)
                                            ShapeType.WIDE_ROUNDED_RECT -> drawRoundRect(color = c, cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f))
                                            ShapeType.ROUNDED_SQUARE -> drawRoundRect(color = c, cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f))
                                            ShapeType.OVAL -> drawOval(color = c)
                                            ShapeType.PENTAGON, ShapeType.STAR -> {
                                                drawCircle(color = c)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Width Dial Slider (matching screenshot: Width, tick ruler, red needle, 100%)
                    RulerDialSlider(
                        label = "Width",
                        value = widthPercent,
                        onValueChange = { widthPercent = it },
                        min = 10f,
                        max = 250f,
                        isPercentage = true
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Height Dial Slider (matching screenshot: Height, tick ruler, red needle, 100%)
                    RulerDialSlider(
                        label = "Height",
                        value = heightPercent,
                        onValueChange = { heightPercent = it },
                        min = 10f,
                        max = 250f,
                        isPercentage = true
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Feather Dial Slider (matching screenshot: Feather, tick ruler, red needle, 0.0%)
                    RulerDialSlider(
                        label = "Feather",
                        value = featherPercent,
                        onValueChange = { featherPercent = it },
                        min = 0f,
                        max = 100f,
                        isPercentage = false
                    )
                }

                ShapeEditTab.FILL -> {
                    // Color Palette
                    Text("Fill Color", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(Color.White, Color(0xFF00F0FF), Color(0xFFFF3377), Color(0xFFFFEB3B), Color(0xFF00E676), Color(0xFF9C27B0), Color(0xFFFF5722), Color(0xFF1E2129)).forEach { col ->
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(col)
                                    .border(2.dp, if (fillColor == col) Color.White else Color.Transparent, CircleShape)
                                    .bouncyClickable(onClick = { fillColor = col })
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    RulerDialSlider(
                        label = "Opacity",
                        value = fillOpacity,
                        onValueChange = { fillOpacity = it },
                        min = 0f,
                        max = 100f,
                        isPercentage = true
                    )
                }

                ShapeEditTab.STROKE -> {
                    RulerDialSlider(
                        label = "Stroke",
                        value = strokeWidth,
                        onValueChange = { strokeWidth = it },
                        min = 0f,
                        max = 30f,
                        isPercentage = false
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(Color.White, Color(0xFFFF3377), Color(0xFF00F0FF), Color(0xFFFFEB3B), Color.Black).forEach { col ->
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(col)
                                    .border(2.dp, if (strokeColor == col) Color.White else Color.Transparent, CircleShape)
                                    .bouncyClickable(onClick = { strokeColor = col })
                            )
                        }
                    }
                }

                ShapeEditTab.SHADOW -> {
                    RulerDialSlider(
                        label = "Shadow",
                        value = shadowBlur,
                        onValueChange = { shadowBlur = it },
                        min = 0f,
                        max = 50f,
                        isPercentage = false
                    )
                }

                ShapeEditTab.GLOW -> {
                    RulerDialSlider(
                        label = "Glow",
                        value = glowIntensity,
                        onValueChange = { glowIntensity = it },
                        min = 0f,
                        max = 100f,
                        isPercentage = true
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            androidx.compose.material3.Button(
                onClick = {
                    onSaveShape(
                        selectedShapeType,
                        widthPercent,
                        heightPercent,
                        fillColor,
                        featherPercent,
                        shapeOffsetX,
                        shapeOffsetY,
                        strokeWidth,
                        strokeColor
                    )
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(10.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = NeonCyan)
            ) {
                Text(
                    text = "Add Shape to Timeline",
                    color = EditorBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}
