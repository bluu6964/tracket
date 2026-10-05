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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import com.example.ui.util.bouncyClickable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.AspectRatio
import com.example.model.TimelineTrack
import com.example.ui.theme.EditorBackground
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen

@Composable
fun CanvasArea(
    height: Dp,
    aspectRatio: AspectRatio,
    currentPlayheadMs: Long,
    isPlaying: Boolean,
    tracks: List<TimelineTrack>,
    selectedClipId: String? = null,
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Zoom/pan state for canvas preview
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Floating particles animation when playing
    val infiniteTransition = rememberInfiniteTransition(label = "particles")
    val dotPulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val dotDrift by infiniteTransition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "drift"
    )

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
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.8f, 3.0f)
                            offsetX = (offsetX + pan.x).coerceIn(-200f, 200f)
                            offsetY = (offsetY + pan.y).coerceIn(-200f, 200f)
                        }
                    }
                    .bouncyClickable(pressedScale = 0.97f, onClick = onTogglePlayPause),
                contentAlignment = Alignment.Center
            ) {
                // Main Composed Video Canvas Frame (Split layout matching screenshot)
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                        }
                ) {
                    // Left strip: Portrait clip (person / white shirt / lanyard)
                    Box(
                        modifier = Modifier
                            .weight(0.24f)
                            .fillMaxHeight()
                            .background(Color(0xFF1E242B))
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.sample_portrait_1791121452621),
                            contentDescription = "Left Video Frame",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Center strip: Dark navy with glowing green & cyan dots constellation
                    Box(
                        modifier = Modifier
                            .weight(0.52f)
                            .fillMaxHeight()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0xFF161A29),
                                        Color(0xFF1A1F33),
                                        Color(0xFF131724)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        // Cyan dots effect canvas
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            val baseRadius = 8.dp.toPx() * (if (isPlaying) dotPulse else 1f)
                            val drift = if (isPlaying) dotDrift else 0f

                            // Predefined glowing dots constellation matching user's image
                            val points = listOf(
                                Offset(w * 0.48f + drift, h * 0.20f),
                                Offset(w * 0.25f, h * 0.22f - drift),
                                Offset(w * 0.72f, h * 0.22f + drift),
                                Offset(w * 0.44f - drift, h * 0.32f),
                                Offset(w * 0.23f + drift, h * 0.46f),
                                Offset(w * 0.76f - drift, h * 0.42f),
                                Offset(w * 0.42f + drift, h * 0.50f),
                                Offset(w * 0.63f, h * 0.47f + drift),
                                Offset(w * 0.32f - drift, h * 0.60f),
                                Offset(w * 0.77f + drift, h * 0.58f),
                                Offset(w * 0.54f, h * 0.62f - drift)
                            )

                            points.forEach { pt ->
                                // Outer blur/glow
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            NeonCyan.copy(alpha = 0.85f),
                                            NeonGreen.copy(alpha = 0.4f),
                                            Color.Transparent
                                        ),
                                        center = pt,
                                        radius = baseRadius * 2.2f
                                    ),
                                    radius = baseRadius * 2.2f,
                                    center = pt
                                )
                                // Bright solid core
                                drawCircle(
                                    color = NeonGreen,
                                    radius = baseRadius,
                                    center = pt
                                )
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.9f),
                                    radius = baseRadius * 0.4f,
                                    center = pt
                                )
                            }
                        }
                    }

                    // Right strip: Sunlit green tree canopy
                    Box(
                        modifier = Modifier
                            .weight(0.24f)
                            .fillMaxHeight()
                            .background(Color(0xFF283B29))
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.sample_nature_1791121466670),
                            contentDescription = "Right Nature Frame",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Render dynamic text or sticker overlays if active at current playhead
                val activeTextClips = tracks
                    .flatMap { it.clips }
                    .filter { it.text != null && it.isActiveAt(currentPlayheadMs) }

                activeTextClips.forEach { clip ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = clip.text ?: "",
                            color = Color(clip.textColor),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Selected Layer Framing Box & Center Crosshair Target (matching screenshot)
                if (selectedClipId != null) {
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

                // Subtle play indicator when paused
                if (!isPlaying) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(24.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Tap to Play",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
            }
        }
    }
}
