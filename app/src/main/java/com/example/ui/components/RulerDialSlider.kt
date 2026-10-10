package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.TextPrimary
import java.util.Locale

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

        // 2. Interactive Ruler Tick Slider with Red Center Needle
        // Alight Motion style: 1 tick = 1 unit, major tick every 5 units
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    // Fixed px-per-unit so drag distance maps 1:1 to tick marks
                    val pxPerUnit = 8.dp.toPx()
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        // Drag left → value up (ruler slides under the needle)
                        val delta = -dragAmount.x / pxPerUnit
                        val newValue = (currentValue + delta).coerceIn(currentMin, currentMax)
                        currentOnValueChange(newValue)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val centerX = w / 2f
                // 1 unit of value = 1 tick spacing (same as drag mapping)
                val pxPerUnit = 8.dp.toPx()
                val halfUnits = (w / 2f / pxPerUnit).toInt() + 2
                val startUnit = kotlin.math.floor(value - halfUnits).toInt()
                val endUnit = kotlin.math.ceil(value + halfUnits).toInt()

                for (u in startUnit..endUnit) {
                    if (u.toFloat() < currentMin - 0.5f || u.toFloat() > currentMax + 0.5f) continue
                    val x = centerX + (u - value) * pxPerUnit
                    if (x < -2f || x > w + 2f) continue

                    val isMajorTick = (u % 5 == 0)
                    val tickHeight = if (isMajorTick) h * 0.75f else h * 0.42f
                    val yStart = (h - tickHeight) / 2f
                    val yEnd = yStart + tickHeight

                    drawLine(
                        color = Color(0xFF4B5262),
                        start = Offset(x, yStart),
                        end = Offset(x, yEnd),
                        strokeWidth = if (isMajorTick) 1.8.dp.toPx() else 1.4.dp.toPx()
                    )
                }

                // Center Red Indicator Line (needle)
                val redNeedleHeight = h * 0.82f
                val needleStart = (h - redNeedleHeight) / 2f
                drawLine(
                    color = Color(0xFFFF3B5C),
                    start = Offset(centerX, needleStart),
                    end = Offset(x = centerX, y = needleStart + redNeedleHeight),
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
