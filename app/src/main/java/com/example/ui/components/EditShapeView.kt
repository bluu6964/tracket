package com.example.ui.components
import androidx.compose.ui.graphics.RectangleShape

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
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import com.example.model.AspectRatio
import com.example.model.ShapeEditValues
import com.example.model.TimelineClip
import com.example.model.at
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
    /** Media layers on the canvas now (bottom first) and the player of each video layer. */
    mediaLayers: List<TimelineClip> = emptyList(),
    /** C++ engine of the canvas: it draws the media layers of this editor on its own surface. */
    nativePreview: com.example.engine.NativePreviewEngine,
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
        strokeColor: Color,
        /** Corner radius in dp inside the 130 dp shape box (only used by the rounded rect shapes). */
        cornerRadiusDp: Float,
        /** Stroke trim window as fractions of the outline length (Alight `drawing.progress`). */
        trimStart: Float,
        trimEnd: Float
    ) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Editing an existing shape (build 173 — null = adding a new one): the sheet is prefilled
     * from it, the shape-type picker stays hidden (the type is fixed), and the header button
     * saves instead of adding. The fill colour is prefilled from the layer as it looks at
     * the playhead; every other field from the stored layer, so saving a keyframed layer
     * without touching a value writes no keyframe (see commitKeyframed).
     */
    initialClip: TimelineClip? = null,
    /**
     * The shape's corner radius is keyframed (build 173): the slider stays hidden and the
     * sheet says so, with a chip that replaces the animation by a static radius frozen at
     * the playhead — the build-86 text-reveal decision, same class.
     */
    keyframedCornerRadius: Boolean = false,
    /** Replaces the keyframed radius by a static one frozen at the playhead. */
    onUseStaticRadius: (() -> Unit)? = null,
    /**
     * The shape's stroke trim is keyframed (build 175): same class as the corner radius —
     * the Trim start/end sliders stay hidden and the sheet says so, with a chip that
     * replaces the animation by a static window frozen at the playhead.
     */
    keyframedTrim: Boolean = false,
    /** Replaces the keyframed trim by a static window frozen at the playhead. */
    onUseStaticTrim: (() -> Unit)? = null,
    /** Right button of the header: "Add" when adding, "Save" when editing. */
    saveLabel: String = "Add"
) {
    // Shape Parameters State — prefilled from the edited layer, or the add defaults.
    // The fill colour comes from the layer as it looks at the playhead (what the canvas
    // shows); the corner radius from the stored layer, so a keyframed radius that the
    // hidden slider cannot touch also cannot leak into a save.
    val editing = initialClip != null
    val atPlayhead = initialClip?.at(currentPlayheadMs)
    var selectedShapeType by remember(initialClip?.id) {
        mutableStateOf(initialClip?.let { shapeTypeOf(it.shapePattern) } ?: ShapeType.CIRCLE)
    }
    var activeTab by remember { mutableStateOf(ShapeEditTab.SHAPE) }

    var widthPercent by remember(initialClip?.id) {
        mutableFloatStateOf(initialClip?.let { it.shapeScaleX * 100f } ?: 100f)
    }
    var heightPercent by remember(initialClip?.id) {
        mutableFloatStateOf(initialClip?.let { it.shapeScaleY * 100f } ?: 100f)
    }
    var featherPercent by remember { mutableFloatStateOf(0.0f) }

    var fillColor by remember(initialClip?.id) {
        mutableStateOf(atPlayhead?.let { Color(it.textColor) } ?: Color.White)
    }
    var fillOpacity by remember(initialClip?.id) {
        mutableFloatStateOf(atPlayhead?.let { ((it.textColor shr 24) and 0xFF) / 255f * 100f } ?: 100f)
    }

    var strokeWidth by remember(initialClip?.id) {
        mutableFloatStateOf(initialClip?.strokeWidth ?: 0f)
    }
    var strokeColor by remember(initialClip?.id) {
        mutableStateOf(initialClip?.let { Color(it.strokeColor) } ?: Color.White)
    }

    // New fields from the Alight import (builds 80–81): corner radius for the rounded rect shapes
    // (the preview's built-in corners: 12 dp wide rect, 20 dp rounded square) and the stroke trim
    // window (draw-on). The trim sliders only show while a stroke exists.
    var cornerRadiusDp by remember(initialClip?.id) {
        mutableFloatStateOf(
            initialClip?.let { if (it.shapeCornerRadius >= 0f) it.shapeCornerRadius else 12f } ?: 12f
        )
    }
    var trimStartPercent by remember(initialClip?.id) {
        mutableFloatStateOf(initialClip?.let { it.strokeTrimStart * 100f } ?: 0f)
    }
    var trimEndPercent by remember(initialClip?.id) {
        mutableFloatStateOf(initialClip?.let { it.strokeTrimEnd * 100f } ?: 100f)
    }

    var shadowBlur by remember { mutableFloatStateOf(0f) }
    var glowIntensity by remember { mutableFloatStateOf(0f) }

    // Canvas Interactive Shape Transformation
    var shapeOffsetX by remember(initialClip?.id) {
        mutableFloatStateOf(initialClip?.shapeOffsetX ?: 0f)
    }
    var shapeOffsetY by remember(initialClip?.id) {
        mutableFloatStateOf(initialClip?.shapeOffsetY ?: 0f)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F0F))
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
                    .clip(RectangleShape)
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
                            strokeColor,
                            cornerRadiusDp,
                            trimStartPercent / 100f,
                            trimEndPercent / 100f
                        )
                        onDismiss()
                    })
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = saveLabel,
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

            // Centered video container strictly locked to project aspect ratio
            Box(
                modifier = Modifier
                    .size(width = frameW, height = frameH)
                    .clip(androidx.compose.ui.graphics.RectangleShape)
                    .background(com.example.ui.theme.LocalCanvasColor.current),
                contentAlignment = Alignment.Center
            ) {
                if (mediaLayers.isNotEmpty()) {
                    MediaLayersStack(
                        layers = mediaLayers,
                        nativePreview = nativePreview,
                        isPlaying = isPlaying
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
                val pinkHandleColor = Color(0xFFABE41D)

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
                        // The outline the stroke follows; the fill is drawn per shape below, the stroke
                        // once afterwards so the trim window (PathMeasure segment) applies to every shape.
                        var outlinePath: Path? = null

                        when (selectedShapeType) {
                            ShapeType.CIRCLE -> {
                                drawCircle(color = colorWithAlpha)
                                outlinePath = Path().apply {
                                    addOval(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                                }
                            }
                            ShapeType.SQUARE, ShapeType.WIDE_ROUNDED_RECT -> {
                                // Only the rounded rect shapes read the corner-radius slider (the renderers
                                // ignore it for SQUARE); a plain square keeps square corners here too.
                                val cornerRadius = if (selectedShapeType == ShapeType.WIDE_ROUNDED_RECT) {
                                    cornerRadiusDp.dp.toPx()
                                } else 0f
                                drawRoundRect(
                                    color = colorWithAlpha,
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius)
                                )
                                outlinePath = Path().apply {
                                    addRoundRect(
                                        androidx.compose.ui.geometry.RoundRect(
                                            androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height),
                                            androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius)
                                        )
                                    )
                                }
                            }
                            ShapeType.ROUNDED_SQUARE -> {
                                val cornerRadius = cornerRadiusDp.dp.toPx()
                                drawRoundRect(
                                    color = colorWithAlpha,
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius)
                                )
                                outlinePath = Path().apply {
                                    addRoundRect(
                                        androidx.compose.ui.geometry.RoundRect(
                                            androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height),
                                            androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius)
                                        )
                                    )
                                }
                            }
                            ShapeType.OVAL -> {
                                drawOval(color = colorWithAlpha)
                                outlinePath = Path().apply {
                                    addOval(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
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
                                outlinePath = path
                            }
                        }

                        // Trimmed stroke: the visible window of the outline, same PathMeasure segment
                        // the timeline renderers draw (LayerPainter.drawStrokePath). An empty window
                        // (start == end) draws no stroke at all.
                        val outline = outlinePath
                        if (strokeWidth > 0f && outline != null) {
                            val trimStart = (trimStartPercent / 100f).coerceIn(0f, 1f)
                            val trimEnd = (trimEndPercent / 100f).coerceIn(0f, 1f)
                            if (trimEnd > trimStart) {
                                val strokeStyle = Stroke(width = strokeWidth.dp.toPx())
                                if (trimStart <= 0f && trimEnd >= 1f) {
                                    drawPath(outline, color = strokeColor, style = strokeStyle)
                                } else {
                                    val measure = PathMeasure().apply { setPath(outline, false) }
                                    val len = measure.length
                                    if (len > 0f) {
                                        val segment = Path()
                                        if (measure.getSegment(len * trimStart, len * trimEnd, segment, true)) {
                                            drawPath(segment, color = strokeColor, style = strokeStyle)
                                        }
                                    }
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
                        .clip(RectangleShape)
                        .background(Color(0xFF2C2C2C))
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
                .clip(RectangleShape)
                .background(Color(0xFF141414))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Drag handle pill at top
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(36.dp)
                    .height(4.dp)
                    .background(Color(0xFF3F3F3F), RectangleShape)
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
                            .clip(RectangleShape)
                            .background(if (isSelected) Color(0xFFF3F4F6) else Color(0xFF232323))
                            .bouncyClickable(
                                pressedScale = 0.92f,
                                onClick = { activeTab = tab }
                            )
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tab.title,
                            color = if (isSelected) Color(0xFF111111) else Color(0xFFE5E7EB),
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
                    // Shape selection row: Label "Shape" + Row of shape cards (matching screenshot).
                    // Editing an existing shape the type is fixed, so the picker stays hidden.
                    if (!editing) {
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
                                        .clip(RectangleShape)
                                        .background(if (isSelected) Color(0xFF2C2C2C) else Color(0xFF1D1D1D))
                                        .border(
                                            width = if (isSelected) 2.dp else 1.dp,
                                            color = if (isSelected) Color.White else Color(0xFF3B3B3B),
                                            shape = RectangleShape
                                        )
                                        .bouncyClickable(onClick = {
                                            selectedShapeType = shape
                                            // Each rounded shape starts at its built-in corner, as drawn before.
                                            cornerRadiusDp = when (shape) {
                                                ShapeType.WIDE_ROUNDED_RECT -> 12f
                                                ShapeType.ROUNDED_SQUARE -> 20f
                                                else -> cornerRadiusDp
                                            }
                                        }),
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

                    // Corner radius (Alight import, build 80): only the rounded rect shapes read it —
                    // the renderers ignore it for SQUARE, CIRCLE and the rest, so it stays hidden there.
                    if (selectedShapeType == ShapeType.WIDE_ROUNDED_RECT || selectedShapeType == ShapeType.ROUNDED_SQUARE) {
                        Spacer(modifier = Modifier.height(6.dp))
                        if (keyframedCornerRadius) {
                            // Animated corner radius (build 173): the slider would silently mislead —
                            // the value changes over time — so it stays hidden, like the build-86
                            // text-reveal sliders, until an explicit tap freezes it at the playhead.
                            Text(
                                "Animated corner radius — the radius is keyframed in Alight Motion, so it changes over time.",
                                color = TextSecondary, fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            ShapeEditChip(label = "Use static radius", onClick = {
                                cornerRadiusDp = initialClip?.at(currentPlayheadMs)?.shapeCornerRadius
                                    ?: cornerRadiusDp
                                onUseStaticRadius?.invoke()
                            })
                        } else {
                            RulerDialSlider(
                                label = "Corner radius",
                                value = cornerRadiusDp,
                                onValueChange = { cornerRadiusDp = it },
                                min = 0f,
                                max = 65f,
                                unit = "dp",
                                isPercentage = false,
                                step = 1f
                            )
                        }
                    }
                }

                ShapeEditTab.FILL -> {
                    // Color Palette
                    Text("Fill Color", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(Color.White, Color(0xFFABE41D), Color(0xFFABE41D), Color(0xFFFFEB3B), Color(0xFF00E676), Color(0xFF9C27B0), Color(0xFFFF5722), Color(0xFF1E2129)).forEach { col ->
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
                        listOf(Color.White, Color(0xFFABE41D), Color(0xFFABE41D), Color(0xFFFFEB3B), Color.Black).forEach { col ->
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
                    // Stroke trim (Alight `drawing.progress`, build 81): the visible window of the
                    // outline — 0–100 % draws the whole stroke, a narrower window draws it on.
                    // The sliders clamp against each other so start can never pass end.
                    if (strokeWidth > 0f) {
                        Spacer(modifier = Modifier.height(10.dp))
                        if (keyframedTrim) {
                            // Animated trim (build 175): the sliders would silently mislead — the
                            // window changes over time — so they stay hidden, like the build-173
                            // corner-radius slider, until an explicit tap freezes it at the playhead.
                            Text(
                                "Animated stroke trim — the trim is keyframed in Alight Motion, so it changes over time.",
                                color = TextSecondary, fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            ShapeEditChip(label = "Use static trim", onClick = {
                                val (s, e) = initialClip?.let {
                                    ShapeEditValues.staticTrimAt(it, currentPlayheadMs)
                                } ?: (trimStartPercent / 100f to trimEndPercent / 100f)
                                trimStartPercent = s * 100f
                                trimEndPercent = e * 100f
                                onUseStaticTrim?.invoke()
                            })
                        } else {
                            RulerDialSlider(
                                label = "Trim start",
                                value = trimStartPercent,
                                onValueChange = { trimStartPercent = it.coerceIn(0f, trimEndPercent) },
                                min = 0f,
                                max = 100f,
                                isPercentage = true
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            RulerDialSlider(
                                label = "Trim end",
                                value = trimEndPercent,
                                onValueChange = { trimEndPercent = it.coerceIn(trimStartPercent, 100f) },
                                min = 0f,
                                max = 100f,
                                isPercentage = true
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
                        strokeColor,
                        cornerRadiusDp,
                        trimStartPercent / 100f,
                        trimEndPercent / 100f
                    )
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RectangleShape,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = NeonCyan)
            ) {
                Text(
                    text = if (editing) "Save Shape" else "Add Shape to Timeline",
                    color = EditorBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

/**
 * The editor's shape type for a stored [TimelineClip.shapePattern] (build 173, edit
 * prefill): the importer's normalised names ("RECT" / "CIRCLE" / "ROUNDED_RECT") and the
 * app's own enum names. Anything else (PATH shapes, groups of paths) falls back to
 * SQUARE — the picker is hidden while editing, so the type only decides which sliders
 * the sheet shows, and the saved pattern is never rewritten.
 */
private fun shapeTypeOf(pattern: String?): ShapeType = when (pattern?.uppercase()) {
    "CIRCLE" -> ShapeType.CIRCLE
    "RECT", "SQUARE" -> ShapeType.SQUARE
    "ROUNDED_RECT", "ROUNDED_SQUARE" -> ShapeType.ROUNDED_SQUARE
    "WIDE_ROUNDED_RECT" -> ShapeType.WIDE_ROUNDED_RECT
    "OVAL" -> ShapeType.OVAL
    "PENTAGON" -> ShapeType.PENTAGON
    "STAR" -> ShapeType.STAR
    else -> ShapeType.SQUARE
}

/** The build-86 text-reveal sheet chip, local to the shape editor (same look). */
@Composable
private fun ShapeEditChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(48.dp)
            .clip(RectangleShape)
            .background(ChipBg)
            .bouncyClickable(pressedScale = 0.9f, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}
