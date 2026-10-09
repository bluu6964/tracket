package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.EasingType
import com.example.model.TransformKeyframe
import com.example.model.applyEasing
import com.example.ui.util.bouncyClickable
import kotlin.math.abs

private val GraphBg = Color(0xFF1A1D24)
private val GridColor = Color(0xFF2A2F3A)
private val CurveColor = Color.White
private val KeyframeColor = Color(0xFFE91E63)
private val KeyframeStroke = Color.White
private val PlayheadLine = Color(0xFFB0B8C8)
private val PresetBg = Color(0xFF252A35)
private val PresetSelected = Color(0xFF3A4150)

/**
 * Graph editor matching CapCut/Alight-style transform curve UI:
 * - X = Time, Y = Transform property value
 * - White curve with real easing sampling between keyframes
 * - Red keyframe dots (tap add/remove, drag move)
 * - Vertical playhead line
 * - Right-side easing preset buttons
 */
@Composable
fun TransformGraphEditor(
    keyframes: List<TransformKeyframe>,
    propertyLabel: String,
    valueMin: Float,
    valueMax: Float,
    timeStartMs: Long,
    timeEndMs: Long,
    playheadMs: Long,
    selectedEasing: EasingType,
    onAddKeyframe: (timeMs: Long, value: Float) -> Unit,
    onMoveKeyframe: (oldTimeMs: Long, newTimeMs: Long, newValue: Float) -> Unit,
    onRemoveKeyframe: (timeMs: Long) -> Unit,
    onSelectEasing: (EasingType) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val sorted = remember(keyframes) { keyframes.sortedBy { it.timeMs } }
    val timeSpan = (timeEndMs - timeStartMs).coerceAtLeast(1L).toFloat()
    val valueSpan = (valueMax - valueMin).coerceAtLeast(0.001f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(GraphBg)
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(28.dp)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = propertyLabel,
                    color = Color(0xFF8E99AF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF2C3240), RoundedCornerShape(8.dp))
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(sorted, timeStartMs, timeEndMs, valueMin, valueMax) {
                            detectTapGestures(
                                onTap = { offset ->
                                    val w = size.width.toFloat()
                                    val h = size.height.toFloat()
                                    val t = timeStartMs + ((offset.x / w) * timeSpan).toLong()
                                    val v = valueMax - (offset.y / h) * valueSpan
                                    val near = sorted.find {
                                        abs(it.timeMs - t) < timeSpan * 0.04f &&
                                            abs(it.value - v) < valueSpan * 0.08f
                                    }
                                    if (near != null) {
                                        onRemoveKeyframe(near.timeMs)
                                    } else {
                                        onAddKeyframe(
                                            t.coerceIn(timeStartMs, timeEndMs),
                                            v.coerceIn(valueMin, valueMax)
                                        )
                                    }
                                },
                                onDoubleTap = { offset ->
                                    val w = size.width.toFloat()
                                    val t = timeStartMs + ((offset.x / w) * timeSpan).toLong()
                                    onSeek(t.coerceIn(timeStartMs, timeEndMs))
                                }
                            )
                        }
                        .pointerInput(sorted, timeStartMs, timeEndMs, valueMin, valueMax) {
                            var draggedKfTime: Long? = null
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val w = size.width.toFloat()
                                    val h = size.height.toFloat()
                                    val t = timeStartMs + ((offset.x / w) * timeSpan).toLong()
                                    val v = valueMax - (offset.y / h) * valueSpan
                                    draggedKfTime = sorted.minByOrNull {
                                        val dx = (it.timeMs - t).toFloat() / timeSpan
                                        val dy = (it.value - v) / valueSpan
                                        dx * dx + dy * dy
                                    }?.takeIf {
                                        val dx = (it.timeMs - t).toFloat() / timeSpan
                                        val dy = (it.value - v) / valueSpan
                                        dx * dx + dy * dy < 0.01f
                                    }?.timeMs
                                },
                                onDrag = { change, _ ->
                                    val kfT = draggedKfTime ?: return@detectDragGestures
                                    change.consume()
                                    val w = size.width.toFloat()
                                    val h = size.height.toFloat()
                                    val newT = timeStartMs + ((change.position.x / w) * timeSpan).toLong()
                                    val newV = valueMax - (change.position.y / h) * valueSpan
                                    val clampedT = newT.coerceIn(timeStartMs, timeEndMs)
                                    onMoveKeyframe(kfT, clampedT, newV.coerceIn(valueMin, valueMax))
                                    draggedKfTime = clampedT
                                },
                                onDragEnd = { draggedKfTime = null }
                            )
                        }
                ) {
                    val w = size.width
                    val h = size.height

                    val gridX = 4
                    val gridY = 4
                    for (i in 0..gridX) {
                        val x = w * i / gridX
                        drawLine(
                            color = GridColor,
                            start = Offset(x, 0f),
                            end = Offset(x, h),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                floatArrayOf(6f, 6f)
                            )
                        )
                    }
                    for (i in 0..gridY) {
                        val y = h * i / gridY
                        drawLine(
                            color = GridColor,
                            start = Offset(0f, y),
                            end = Offset(w, y),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                floatArrayOf(6f, 6f)
                            )
                        )
                    }

                    fun timeToX(t: Long) = ((t - timeStartMs) / timeSpan) * w
                    fun valueToY(v: Float) = ((valueMax - v) / valueSpan) * h

                    if (sorted.size >= 2) {
                        val path = Path()
                        val samplesPerSegment = 32
                        var first = true
                        for (i in 0 until sorted.size - 1) {
                            val a = sorted[i]
                            val b = sorted[i + 1]
                            for (s in 0..samplesPerSegment) {
                                val tNorm = s / samplesPerSegment.toFloat()
                                val eased = applyEasing(tNorm, a.easing)
                                val time = a.timeMs + ((b.timeMs - a.timeMs) * tNorm).toLong()
                                val value = a.value + (b.value - a.value) * eased
                                val px = timeToX(time)
                                val py = valueToY(value)
                                if (first) {
                                    path.moveTo(px, py)
                                    first = false
                                } else {
                                    path.lineTo(px, py)
                                }
                            }
                        }
                        drawPath(
                            path = path,
                            color = CurveColor,
                            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                        )
                    } else if (sorted.size == 1) {
                        val y = valueToY(sorted[0].value)
                        drawLine(
                            color = CurveColor,
                            start = Offset(0f, y),
                            end = Offset(w, y),
                            strokeWidth = 2.dp.toPx()
                        )
                    }

                    val phX = timeToX(playheadMs.coerceIn(timeStartMs, timeEndMs))
                    drawLine(
                        color = PlayheadLine,
                        start = Offset(phX, 0f),
                        end = Offset(phX, h),
                        strokeWidth = 1.5.dp.toPx()
                    )

                    sorted.forEach { kf ->
                        val cx = timeToX(kf.timeMs)
                        val cy = valueToY(kf.value)
                        drawCircle(color = KeyframeStroke, radius = 8.dp.toPx(), center = Offset(cx, cy))
                        drawCircle(color = KeyframeColor, radius = 6.dp.toPx(), center = Offset(cx, cy))
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(
                modifier = Modifier
                    .width(48.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val presets = listOf(
                    EasingType.EASE_IN_CUBIC to PresetPath.EaseInUp,
                    EasingType.LINEAR to PresetPath.Linear,
                    EasingType.EASE_OUT_CUBIC to PresetPath.EaseOut,
                    EasingType.EASE_IN_OUT_CUBIC to PresetPath.EaseInOutS,
                    EasingType.EASE_IN to PresetPath.EaseIn,
                    EasingType.EASE_OUT to PresetPath.EaseOutFlat,
                    EasingType.EASE_IN_OUT to PresetPath.EaseInOut,
                    EasingType.HOLD to PresetPath.Hold
                )
                presets.forEach { (type, pathType) ->
                    val selected = selectedEasing == type
                    Box(
                        modifier = Modifier
                            .size(44.dp, 28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selected) PresetSelected else PresetBg)
                            .border(
                                1.dp,
                                if (selected) Color(0xFF5A6578) else Color(0xFF2C3240),
                                RoundedCornerShape(6.dp)
                            )
                            .bouncyClickable { onSelectEasing(type) },
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.size(28.dp, 16.dp)) {
                            drawEasingIcon(pathType, KeyframeColor, CurveColor)
                        }
                    }
                }
            }
        }

        Text(
            text = "Time",
            color = Color(0xFF8E99AF),
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 4.dp)
        )
    }
}

private enum class PresetPath {
    EaseInUp, Linear, EaseOut, EaseInOutS, EaseIn, EaseOutFlat, EaseInOut, Hold
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEasingIcon(
    type: PresetPath,
    accent: Color,
    line: Color
) {
    val w = size.width
    val h = size.height
    val path = Path()
    when (type) {
        PresetPath.EaseInUp -> {
            path.moveTo(0f, h)
            path.cubicTo(w * 0.4f, h, w * 0.6f, 0f, w, 0f)
        }
        PresetPath.Linear -> {
            path.moveTo(0f, h)
            path.lineTo(w, 0f)
        }
        PresetPath.EaseOut -> {
            path.moveTo(0f, h * 0.7f)
            path.cubicTo(w * 0.3f, h * 0.1f, w * 0.5f, 0f, w, 0f)
        }
        PresetPath.EaseInOutS -> {
            path.moveTo(0f, h)
            path.cubicTo(w * 0.4f, h, w * 0.6f, 0f, w, 0f)
        }
        PresetPath.EaseIn -> {
            path.moveTo(0f, h)
            path.cubicTo(w * 0.5f, h, w * 0.7f, h * 0.3f, w, 0f)
        }
        PresetPath.EaseOutFlat -> {
            path.moveTo(0f, h)
            path.cubicTo(w * 0.2f, h * 0.2f, w * 0.5f, 0f, w, 0f)
        }
        PresetPath.EaseInOut -> {
            path.moveTo(0f, h)
            path.cubicTo(w * 0.35f, h, w * 0.35f, 0f, w * 0.5f, 0f)
            path.cubicTo(w * 0.65f, 0f, w * 0.65f, h, w, h * 0.15f)
        }
        PresetPath.Hold -> {
            path.moveTo(0f, h * 0.7f)
            path.lineTo(w * 0.7f, h * 0.7f)
            path.lineTo(w * 0.7f, h * 0.2f)
            path.lineTo(w, h * 0.2f)
        }
    }
    drawPath(path, line, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round))
    drawCircle(accent, 2.5.dp.toPx(), Offset(0f, when (type) {
        PresetPath.Hold -> h * 0.7f
        else -> h
    }))
    drawCircle(accent, 2.5.dp.toPx(), Offset(w, when (type) {
        PresetPath.Hold -> h * 0.2f
        PresetPath.EaseInOut -> h * 0.15f
        else -> 0f
    }))
}

/** Which transform property is currently being graphed. */
enum class GraphProperty(
    val key: String,
    val label: String,
    val min: Float,
    val max: Float,
    val defaultValue: Float
) {
    HORIZONTAL("transformX", "Transform", -500f, 500f, 0f),
    VERTICAL("transformY", "Vertical", -500f, 500f, 0f),
    SCALE("scalePercent", "Scale", 10f, 400f, 100f),
    ROTATION("rotationDeg", "Rotation", -180f, 180f, 0f),
    PIVOT_X("pivotX", "Pivot X", 0f, 1f, 0.5f),
    PIVOT_Y("pivotY", "Pivot Y", 0f, 1f, 0.5f),
    ROT_3D_X("rotation3dX", "3D X", -180f, 180f, 0f),
    ROT_3D_Y("rotation3dY", "3D Y", -180f, 180f, 0f),
    WARP("warpDistort", "Warp", -100f, 100f, 0f)
}
