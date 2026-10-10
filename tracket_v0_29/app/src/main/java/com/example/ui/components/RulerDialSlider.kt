package com.example.ui.components

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.awaitPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.TextPrimary
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun RulerDialSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    min: Float = 0f,
    max: Float = 200f,
    unit: String = "%",
    isPercentage: Boolean = true,
    labelWidth: Dp = 105.dp,
    modifier: Modifier = Modifier
) {
    val currentValue by rememberUpdatedState(value)
    val currentMin by rememberUpdatedState(min)
    val currentMax by rememberUpdatedState(max)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val scope = rememberCoroutineScope()
    val flingJob = remember { arrayOfNulls<Job>(1) }

    val displayValue = if (unit.isNotEmpty()) {
        String.format(Locale.US, "%.1f%s", value, unit)
    } else if (isPercentage) {
        String.format(Locale.US, "%.1f%%", value)
    } else {
        String.format(Locale.US, "%.1f", value)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Label on left (e.g., "Width", "Height", "Intensity", "Rotation", "Edge Hardness")
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(labelWidth)
        )

        // 2. Interactive Ruler Tick Slider with Red Center Needle (matching screenshot)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    val velocityTracker = VelocityTracker()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        flingJob[0]?.cancel()
                        velocityTracker.resetTracking()
                        velocityTracker.addPosition(down.uptimeMillis, down.position)
                        var lastX = down.position.x
                        var pos = currentValue
                        val sensitivity = (currentMax - currentMin) / 250f
                        val sens = sensitivity.coerceAtLeast(0.4f)

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            val change = pressed[0]
                            change.consume()
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            val dx = change.position.x - lastX
                            lastX = change.position.x
                            pos = (pos + dx * sens).coerceIn(currentMin, currentMax)
                            currentOnValueChange(pos)
                        }

                        val vx = velocityTracker.calculateVelocity().x
                        if (abs(vx) > 80f) {
                            val start = pos
                            val velocity = vx * sens
                            flingJob[0] = scope.launch {
                                AnimationState(
                                    initialValue = start,
                                    initialVelocity = velocity
                                ).animateDecay(
                                    exponentialDecay(
                                        frictionMultiplier = 0.16f,
                                        absVelocityThreshold = 0.5f
                                    )
                                ) {
                                    val v = value.coerceIn(currentMin, currentMax)
                                    currentOnValueChange(v)
                                    if (v <= currentMin || v >= currentMax) cancelAnimation()
                                }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val centerX = w / 2f
                val tickSpacing = 10.dp.toPx()
                val tickCount = (w / tickSpacing).toInt() + 6

                // Offset ticks continuously based on current value so the ruler slides
                val scrollOffset = ((value - currentMin) * 2.5f) % tickSpacing

                for (i in -tickCount / 2..tickCount / 2) {
                    val x = centerX + i * tickSpacing - scrollOffset
                    if (x < 0 || x > w) continue

                    val isMajorTick = (i % 5 == 0)
                    val tickHeight = if (isMajorTick) h * 0.75f else h * 0.42f
                    val yStart = (h - tickHeight) / 2f
                    val yEnd = yStart + tickHeight

                    drawLine(
                        color = Color(0xFF4B5262),
                        start = Offset(x, yStart),
                        end = Offset(x, yEnd),
                        strokeWidth = 1.5.dp.toPx()
                    )
                }

                // Center Red Indicator Line (needle) matching screenshot
                val redNeedleHeight = h * 0.82f
                val needleStart = (h - redNeedleHeight) / 2f
                drawLine(
                    color = Color(0xFFFF3B5C),
                    start = Offset(centerX, needleStart),
                    end = Offset(centerX, needleStart + redNeedleHeight),
                    strokeWidth = 2.5.dp.toPx()
                )
            }
        }

        // 3. Current Value on right (e.g., "20%", "45°", "0%")
        Text(
            text = displayValue,
            color = TextPrimary,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.width(60.dp)
        )
    }
}
