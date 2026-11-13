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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AmTickMajor
import com.example.ui.theme.AmTickMinor
import com.example.ui.theme.TextPrimary
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Distance between two ruler ticks. One tick = one [step] of value. */
private val RULER_TICK_SPACING = 10.dp

/** Standard fling feel (Compose default is 1f; a bit higher = a bit shorter glide for precise sliders). */
private const val RULER_FLING_FRICTION = 1.2f

/** Slower releases than this (px/s) just stop, so a careful adjustment never drifts afterwards. */
private const val RULER_MIN_FLING_VELOCITY = 350f

/**
 * The ruler itself: drag to change the value, **one tick = one step** (default 1 unit), and a quick
 * swipe keeps gliding for a natural distance. The ruler follows the finger. Values are always
 * whole steps.
 */
@Composable
fun RulerDial(
    value: Float,
    onValueChange: (Float) -> Unit,
    min: Float,
    max: Float,
    modifier: Modifier = Modifier,
    step: Float = 1f
) {
    val currentValue by rememberUpdatedState(value)
    val currentMin by rememberUpdatedState(min)
    val currentMax by rememberUpdatedState(max)
    val currentStep by rememberUpdatedState(step)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val scope = rememberCoroutineScope()
    val flingJob = remember { arrayOfNulls<Job>(1) }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                val velocityTracker = VelocityTracker()
                val tickPx = RULER_TICK_SPACING.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    flingJob[0]?.cancel() // touching the ruler stops any glide
                    velocityTracker.resetTracking()
                    velocityTracker.addPosition(down.uptimeMillis, down.position)
                    var lastX = down.position.x
                    var pos = currentValue.coerceIn(currentMin, currentMax)
                    var lastEmitted = pos

                    fun emit(p: Float) {
                        val st = currentStep
                        val q = (((p - currentMin) / st).roundToInt() * st + currentMin)
                            .coerceIn(currentMin, currentMax)
                        if (q != lastEmitted) {
                            lastEmitted = q
                            currentOnValueChange(q)
                        }
                    }

                    // The ruler only takes the touch once the finger moves SIDEWAYS. A mostly vertical move is left alone, so a
                    // panel with several rulers (Effects) can still be scrolled by touching a ruler.
                    var horizontal = false
                    val slop = viewConfiguration.touchSlop
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        val change = pressed[0]
                        if (!horizontal) {
                            val totalX = change.position.x - down.position.x
                            val totalY = change.position.y - down.position.y
                            if (abs(totalX) <= slop && abs(totalY) <= slop) continue  // not decided yet
                            if (abs(totalY) > abs(totalX)) return@awaitEachGesture   // vertical: not ours
                            horizontal = true
                        }
                        change.consume()
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        val dx = change.position.x - lastX
                        lastX = change.position.x
                        pos = (pos + dx / tickPx * currentStep).coerceIn(currentMin, currentMax)
                        emit(pos)
                    }

                    val vx = velocityTracker.calculateVelocity().x
                    if (abs(vx) > RULER_MIN_FLING_VELOCITY) {
                        val start = pos
                        val velocity = vx / tickPx * currentStep // value units per second
                        flingJob[0] = scope.launch {
                            AnimationState(initialValue = start, initialVelocity = velocity)
                                .animateDecay(
                                    exponentialDecay(
                                        frictionMultiplier = RULER_FLING_FRICTION,
                                        absVelocityThreshold = currentStep * 2f
                                    )
                                ) {
                                    // `this.value` = the animated value (not the slider's `value`).
                                    val v = this.value.coerceIn(currentMin, currentMax)
                                    emit(v)
                                    if (v <= currentMin || v >= currentMax) cancelAnimation()
                                }
                        }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val centerX = w / 2f
            val spacing = RULER_TICK_SPACING.toPx()
            val st = step
            val maxIndex = ((max - min) / st).roundToInt()
            // Position of the current value on the ruler, in ticks from `min`.
            val idx = (value.coerceIn(min, max) - min) / st
            val half = (w / 2f / spacing).toInt() + 2
            val base = floor(idx).toInt()

            for (k in (base - half)..(base + half)) {
                if (k < 0 || k > maxIndex) continue // the ruler ends at min / max
                // Tick k stays attached to its value; the ruler slides with the finger.
                val x = centerX + (idx - k) * spacing
                if (x < 0f || x > w) continue

                val tickValue = min + k * st
                val isMajorTick = (tickValue / st).roundToInt() % 5 == 0
                val tickHeight = if (isMajorTick) h * 0.75f else h * 0.42f
                val yStart = (h - tickHeight) / 2f
                drawLine(
                    color = if (isMajorTick) AmTickMajor else AmTickMinor,
                    start = Offset(x, yStart),
                    end = Offset(x, yStart + tickHeight),
                    strokeWidth = 1.5.dp.toPx()
                )
            }

            // Center indicator (needle): a flat white line (B/W language; geometry
            // and drag law unchanged), with a flat white thumb disc at its centre.
            val needleHeight = h * 0.82f
            val needleStart = (h - needleHeight) / 2f
            drawLine(
                color = Color.White.copy(alpha = 0.20f),
                start = Offset(centerX, needleStart),
                end = Offset(centerX, needleStart + needleHeight),
                strokeWidth = 7.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color.White,
                start = Offset(centerX, needleStart),
                end = Offset(centerX, needleStart + needleHeight),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawFlatThumb(Offset(centerX, h / 2f), 7.5.dp.toPx())
        }
    }
}

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
    step: Float = 1f,
    /** When set, this icon is shown on the left instead of the [label] text. */
    icon: ImageVector? = null,
    /** false = the caller shows the value somewhere else (e.g. a header above the slider). */
    showValue: Boolean = true,
    modifier: Modifier = Modifier
) {
    // The readout must be able to show the value EXACTLY: the decimals follow the step
    // (0.01 / 0.05 / 0.25-stepped values exist — a flat "%.1f" showed 0.25 as "0.3").
    val fmt = when {
        step >= 1f -> "%.0f"
        abs(step * 10f - (step * 10f).roundToInt()) < 0.001f -> "%.1f"
        else -> "%.2f"
    }
    val displayValue = if (unit.isNotEmpty()) {
        String.format(Locale.US, fmt + "%s", value, unit)
    } else if (isPercentage) {
        String.format(Locale.US, fmt + "%%", value)
    } else {
        String.format(Locale.US, fmt, value)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Label on left (e.g., "Width", "Height", "Intensity", "Rotation", "Edge Hardness")
        if (icon != null) {
            Box(modifier = Modifier.width(labelWidth), contentAlignment = Alignment.CenterStart) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = TextPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
        } else {
            Text(
                text = label,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(labelWidth)
            )
        }

        // 2. Ruler with the center needle
        RulerDial(
            value = value,
            onValueChange = onValueChange,
            min = min,
            max = max,
            step = step,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )

        // 3. Current value on the right (e.g., "20%", "45°", "0%") — in a small flat
        // grey pill (the readout format is build 160's step-exact logic, untouched).
        if (showValue) Box(
            modifier = Modifier
                .width(68.dp)
                .height(30.dp),
            contentAlignment = Alignment.Center
        ) {
            FlatPill(Modifier.matchParentSize())
            Text(
                text = displayValue,
                color = TextPrimary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}
