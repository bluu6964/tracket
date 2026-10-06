package com.example.ui.components

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.AspectRatio
import com.example.model.LayerType
import com.example.model.TimelineTrack
import com.example.ui.theme.EditorBackground
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen

@OptIn(UnstableApi::class)
@Composable
fun CanvasArea(
    height: Dp,
    aspectRatio: AspectRatio,
    currentPlayheadMs: Long,
    isPlaying: Boolean,
    tracks: List<TimelineTrack>,
    selectedClipId: String? = null,
    onTogglePlayPause: () -> Unit,
    player: Player? = null,
    hasMainMedia: Boolean = false,
    onUpdateClipTransform: (clipId: String, offsetX: Float, offsetY: Float) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(EditorBackground)
            .testTag("canvas_preview_area"),
        contentAlignment = Alignment.Center
    ) {
        // Aspect-ratio bounding box for the player
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 6.dp),
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
                    .background(Color(0xFF090A0D))
                    .shadow(elevation = 6.dp, shape = RoundedCornerShape(8.dp))
                contentAlignment = Alignment.Center
            ) {
                val activeVideoClip = tracks
                    .flatMap { it.clips }
                    .find { it.type == LayerType.VIDEO && it.isActiveAt(currentPlayheadMs) }
                val clipScale = (activeVideoClip?.scalePercent ?: 100f) / 100f
                val clipRotZ = activeVideoClip?.rotationDeg ?: 0f
                val clipRotX = activeVideoClip?.rotation3dX ?: 0f
                val clipRotY = activeVideoClip?.rotation3dY ?: 0f
                val clipTransX = activeVideoClip?.transformX ?: 0f
                val clipTransY = activeVideoClip?.transformY ?: 0f

                if (player != null && hasMainMedia && activeVideoClip?.uri != null) {
                    // Real preview: ExoPlayer renders the active video layer with its transformations
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = clipScale
                                scaleY = clipScale
                                translationX = clipTransX
                                translationY = clipTransY
                                rotationZ = clipRotZ
                                rotationX = clipRotX
                                rotationY = clipRotY
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                                    activeVideoClip.pivotX,
                                    activeVideoClip.pivotY
                                )
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
                } else if (tracks.flatMap { it.clips }.none { it.isActiveAt(currentPlayheadMs) }) {
                    // Clean Alight Motion style canvas with subtle center indicator
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.size(24.dp)) {
                            val dotColor = Color.White.copy(alpha = 0.25f)
                            drawCircle(color = dotColor, radius = 2.dp.toPx())
                            drawLine(color = dotColor, start = Offset(size.width / 2f, 0f), end = Offset(size.width / 2f, size.height), strokeWidth = 1.dp.toPx())
                            drawLine(color = dotColor, start = Offset(0f, size.height / 2f), end = Offset(size.width, size.height / 2f), strokeWidth = 1.dp.toPx())
                        }
                    }
                }

                // Render dynamic text or sticker overlays if active at current playhead
                val activeTextClips = tracks
                    .flatMap { it.clips }
                    .filter { it.text != null && it.isActiveAt(currentPlayheadMs) }

                activeTextClips.forEach { clip ->
                    val effectiveTransX = clip.transformX
                    val effectiveTransY = clip.transformY
                    val effectiveScale = clip.scalePercent / 100f
                    val isSelected = clip.id == selectedClipId

                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .graphicsLayer {
                                translationX = effectiveTransX
                                translationY = effectiveTransY
                                scaleX = effectiveScale.coerceAtLeast(0.1f)
                                scaleY = effectiveScale.coerceAtLeast(0.1f)
                                rotationZ = clip.rotationDeg
                                rotationX = clip.rotation3dX
                                rotationY = clip.rotation3dY
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(clip.pivotX, clip.pivotY)
                                cameraDistance = 12f * density
                            }
                            .pointerInput(clip.id) {
                                var currentDragX = effectiveTransX
                                var currentDragY = effectiveTransY
                                detectDragGestures(
                                    onDragStart = {
                                        currentDragX = effectiveTransX
                                        currentDragY = effectiveTransY
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        currentDragX += dragAmount.x
                                        currentDragY += dragAmount.y
                                        onUpdateClipTransform(
                                            clip.id,
                                            currentDragX,
                                            currentDragY
                                        )
                                    }
                                )
                            }
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                            .then(
                                if (isSelected) Modifier.border(1.5.dp, Color(0xFF00E676), RoundedCornerShape(4.dp))
                                else Modifier
                            )
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = clip.text ?: "",
                            color = Color(clip.textColor),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Render dynamic shape overlays if active at current playhead
                val activeShapeClips = tracks
                    .flatMap { it.clips }
                    .filter { it.type == LayerType.SHAPE && it.isActiveAt(currentPlayheadMs) }

                activeShapeClips.forEach { clip ->
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
                    val isSelected = clip.id == selectedClipId

                    val effectiveTransX = clip.transformX
                    val effectiveTransY = clip.transformY
                    val effectiveScale = clip.scalePercent / 100f
                    val baseSize = 130.dp

                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .graphicsLayer {
                                translationX = effectiveTransX
                                translationY = effectiveTransY
                                scaleX = effectiveScale.coerceAtLeast(0.05f)
                                scaleY = effectiveScale.coerceAtLeast(0.05f)
                                rotationZ = clip.rotationDeg
                                rotationX = clip.rotation3dX
                                rotationY = clip.rotation3dY
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(clip.pivotX, clip.pivotY)
                                cameraDistance = 12f * density
                            }
                            .size(baseSize)
                            .pointerInput(clip.id) {
                                var currentDragX = effectiveTransX
                                var currentDragY = effectiveTransY
                                detectDragGestures(
                                    onDragStart = {
                                        currentDragX = effectiveTransX
                                        currentDragY = effectiveTransY
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        currentDragX += dragAmount.x
                                        currentDragY += dragAmount.y
                                        onUpdateClipTransform(
                                            clip.id,
                                            currentDragX,
                                            currentDragY
                                        )
                                    }
                                )
                            }
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
                                        val cX = size.width / 2f
                                        val cY = size.height / 2f
                                        val outer = size.minDimension / 2f
                                        val isStar = pattern == "STAR"
                                        val pointCount = if (isStar) 10 else 5
                                        val inner = outer * 0.45f
                                        for (i in 0 until pointCount) {
                                            val angle = Math.toRadians((i * 360.0 / pointCount) - 90.0)
                                            val radius = if (isStar && i % 2 == 1) inner else outer
                                            val px = (cX + radius * Math.cos(angle)).toFloat()
                                            val py = (cY + radius * Math.sin(angle)).toFloat()
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

                        if (isSelected) {
                            // Single Alight Motion-style transform frame: one outline + eight corner/edge handles.
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val accent = Color(0xFFFF3B81)
                                val stroke = 1.5.dp.toPx()
                                val handle = 7.dp.toPx()
                                val inset = handle / 2f
                                drawRect(
                                    color = accent,
                                    topLeft = Offset(inset, inset),
                                    size = Size(size.width - handle, size.height - handle),
                                    style = Stroke(width = stroke)
                                )

                                val points = listOf(
                                    Offset(inset, inset),
                                    Offset(size.width / 2f, inset),
                                    Offset(size.width - inset, inset),
                                    Offset(size.width - inset, size.height / 2f),
                                    Offset(size.width - inset, size.height - inset),
                                    Offset(size.width / 2f, size.height - inset),
                                    Offset(inset, size.height - inset),
                                    Offset(inset, size.height / 2f)
                                )
                                points.forEach { point ->
                                    drawRect(
                                        color = accent,
                                        topLeft = Offset(point.x - handle / 2f, point.y - handle / 2f),
                                        size = Size(handle, handle)
                                    )
                                }
                            }
                        }
                    }
                }

                // Main Track Framing Box & Center Crosshair Target
                val selectedClipType = tracks.flatMap { it.clips }.firstOrNull { it.id == selectedClipId }?.type
                val isMainTrackSelected = selectedClipType == LayerType.VIDEO
                if (isMainTrackSelected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 20.dp)
                            .border(1.5.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(2.dp)),
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
        }
    }
}
