package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.KeyframeEasing
import com.example.ui.util.bouncyClickable
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private val EditorBg = Color(0xFF2A2F3D)
private val GridLine = Color(0xFF3A4154)
private val CurveGreen = Color(0xFF1DE9B6)
private val HandleWhite = Color(0xFFF5F6FA)
private val PresetBg = Color(0xFF1E2330)
private val PresetSelected = Color(0xFF151920)

/**
 * Alight Motion–style cubic-bezier easing editor.
 * Drag the two white handles to shape the curve between two keyframes.
 */
@Composable
fun BezierEasingEditor(
    initial: KeyframeEasing,
    onApply: (KeyframeEasing) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var x1 by remember { mutableFloatStateOf(initial.x1.coerceIn(0f, 1f)) }
    var y1 by remember { mutableFloatStateOf(initial.y1.coerceIn(-0.5f, 1.5f)) }
    var x2 by remember { mutableFloatStateOf(initial.x2.coerceIn(0f, 1f)) }
    var y2 by remember { mutableFloatStateOf(initial.y2.coerceIn(-0.5f, 1.5f)) }
    var isHold by remember { mutableStateOf(initial.isHold) }

    val current = remember(x1, y1, x2, y2, isHold) {
        if (isHold) KeyframeEasing.HOLD
        else KeyframeEasing(x1, y1, x2, y2, label = "Custom")
    }

    fun applyPreset(p: KeyframeEasing) {
        isHold = p.isHold
        x1 = p.x1
        y1 = p.y1
        x2 = p.x2
        y2 = p.y2
        onApply(p)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(EditorBg)
    ) {
        // Top bar: back
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .bouncyClickable {
                        onApply(current)
                        onDismiss()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color(0xFFE5E7EB),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, bottom = 8.dp)
        ) {
            // Main curve canvas
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF232836))
                    .padding(16.dp)
            ) {
                BezierCurvePad(
                    x1 = x1, y1 = y1, x2 = x2, y2 = y2, isHold = isHold,
                    onChange = { nx1, ny1, nx2, ny2 ->
                        isHold = false
                        x1 = nx1
                        y1 = ny1
                        x2 = nx2
                        y2 = ny2
                        onApply(KeyframeEasing(nx1, ny1, nx2, ny2, label = "Custom"))
                    }
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Right preset column
            Column(
                modifier = Modifier
                    .width(56.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                KeyframeEasing.EDITOR_PRESETS.forEach { preset ->
                    val selected = current.sameCurve(preset)
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) PresetSelected else PresetBg)
                            .border(
                                width = if (selected) 1.5.dp else 0.dp,
                                color = if (selected) CurveGreen else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .bouncyClickable { applyPreset(preset) },
                        contentAlignment = Alignment.Center
                    ) {
                        MiniCurveIcon(easing = preset, selected = selected)
                    }
                }
            }
        }

        // Bottom title strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "CUBIC BEZIER EASING",
                color = Color(0xFF9AA3B5),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp
            )
        }
    }
}

@Composable
private fun MiniCurveIcon(easing: KeyframeEasing, selected: Boolean) {
    Canvas(modifier = Modifier.size(28.dp)) {
        val path = Path()
        if (easing.isHold) {
            path.moveTo(0f, size.height)
            path.lineTo(size.width * 0.7f, size.height)
            path.lineTo(size.width * 0.7f, 0f)
            path.lineTo(size.width, 0f)
        } else {
            path.moveTo(0f, size.height)
            path.cubicTo(
                size.width * easing.x1, size.height * (1f - easing.y1),
                size.width * easing.x2, size.height * (1f - easing.y2),
                size.width, 0f
            )
        }
        drawPath(
            path,
            color = if (selected) CurveGreen else Color(0xFFB0B6C4),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

@Composable
private fun BezierCurvePad(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    isHold: Boolean,
    onChange: (Float, Float, Float, Float) -> Unit
) {
    val density = LocalDensity.current
    // Which handle is being dragged: 1 or 2
    var activeHandle by remember { mutableStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(x1, y1, x2, y2, isHold) {
                    val pad = 12.dp.toPx()
                    fun toNorm(pos: Offset): Pair<Float, Float> {
                        val w = size.width - pad * 2
                        val h = size.height - pad * 2
                        val nx = ((pos.x - pad) / w).coerceIn(0f, 1f)
                        // y: bottom = 0, top = 1; allow slight overshoot outside pad
                        val ny = (1f - (pos.y - pad) / h).coerceIn(-0.5f, 1.5f)
                        return nx to ny
                    }
                    fun handlePos(nx: Float, ny: Float): Offset {
                        val w = size.width - pad * 2
                        val h = size.height - pad * 2
                        return Offset(pad + nx * w, pad + (1f - ny) * h)
                    }
                    detectDragGestures(
                        onDragStart = { offset ->
                            val p1 = handlePos(x1, y1)
                            val p2 = handlePos(x2, y2)
                            val d1 = hypot(offset.x - p1.x, offset.y - p1.y)
                            val d2 = hypot(offset.x - p2.x, offset.y - p2.y)
                            activeHandle = when {
                                d1 <= d2 && d1 < 48.dp.toPx() -> 1
                                d2 < 48.dp.toPx() -> 2
                                else -> if (d1 <= d2) 1 else 2
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val (nx, ny) = toNorm(change.position)
                            when (activeHandle) {
                                1 -> onChange(nx, ny, x2, y2)
                                2 -> onChange(x1, y1, nx, ny)
                            }
                        },
                        onDragEnd = { activeHandle = 0 }
                    )
                }
        ) {
            val pad = 12.dp.toPx()
            val left = pad
            val top = pad
            val right = size.width - pad
            val bottom = size.height - pad
            val w = right - left
            val h = bottom - top

            fun pt(nx: Float, ny: Float) = Offset(left + nx * w, top + (1f - ny) * h)

            // Grid
            val gridDiv = 4
            for (i in 0..gridDiv) {
                val gx = left + w * i / gridDiv
                val gy = top + h * i / gridDiv
                drawLine(GridLine, Offset(gx, top), Offset(gx, bottom), strokeWidth = 1.dp.toPx())
                drawLine(GridLine, Offset(left, gy), Offset(right, gy), strokeWidth = 1.dp.toPx())
            }
            // Border
            drawRect(
                color = GridLine.copy(alpha = 0.8f),
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size(w, h),
                style = Stroke(width = 1.5.dp.toPx())
            )

            val p0 = pt(0f, 0f)
            val p3 = pt(1f, 1f)
            val p1 = pt(x1, y1)
            val p2 = pt(x2, y2)

            if (isHold) {
                // Step: stay low until end, then jump
                val mid = Offset(p3.x, p0.y)
                drawLine(Color.White.copy(alpha = 0.85f), p0, mid, strokeWidth = 2.5.dp.toPx())
                drawLine(CurveGreen, mid, Offset(p3.x, p3.y), strokeWidth = 2.5.dp.toPx())
            } else {
                // Control arms (white)
                drawLine(Color.White.copy(alpha = 0.55f), p0, p1, strokeWidth = 2.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.55f), p3, p2, strokeWidth = 2.dp.toPx())

                // Cubic curve (green segment through middle for emphasis like screenshot)
                val path = Path().apply {
                    moveTo(p0.x, p0.y)
                    cubicTo(p1.x, p1.y, p2.x, p2.y, p3.x, p3.y)
                }
                drawPath(path, Color.White.copy(alpha = 0.9f), style = Stroke(width = 2.5.dp.toPx()))
                // Green highlight along curve (sample mid section)
                val greenPath = Path()
                var started = false
                for (i in 0..40) {
                    val t = i / 40f
                    val u = 1f - t
                    val bx = u * u * u * p0.x + 3 * u * u * t * p1.x + 3 * u * t * t * p2.x + t * t * t * p3.x
                    val by = u * u * u * p0.y + 3 * u * u * t * p1.y + 3 * u * t * t * p2.y + t * t * t * p3.y
                    if (t in 0.15f..0.85f) {
                        if (!started) {
                            greenPath.moveTo(bx, by)
                            started = true
                        } else greenPath.lineTo(bx, by)
                    }
                }
                if (started) {
                    drawPath(greenPath, CurveGreen, style = Stroke(width = 3.dp.toPx()))
                }
            }

            // Endpoints (green)
            drawCircle(CurveGreen, radius = 6.dp.toPx(), center = p0)
            drawCircle(CurveGreen, radius = 6.dp.toPx(), center = p3)

            // Handles (white)
            if (!isHold) {
                drawCircle(HandleWhite, radius = 14.dp.toPx(), center = p1)
                drawCircle(HandleWhite, radius = 14.dp.toPx(), center = p2)
            }
        }
    }
}
