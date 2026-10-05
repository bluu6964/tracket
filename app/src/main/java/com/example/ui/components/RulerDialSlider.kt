package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun RulerDialSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    min: Float = 0f,
    max: Float = 200f,
    isPercentage: Boolean = true,
    modifier: Modifier = Modifier
) {
    val displayValue = if (isPercentage) {
        String.format(Locale.US, "%.0f%%", value)
    } else {
        String.format(Locale.US, "%.1f%%", value)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Label on left (e.g., "Width", "Height", "Feather")
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(62.dp)
        )

        // 2. Interactive Ruler Tick Slider with Red Center Needle (matching screenshot)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(value, min, max) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        // Sensitivity: 1dp drag adjusts value smoothly
                        val delta = (dragAmount.x / 2.5f)
                        val newValue = (value + delta).coerceIn(min, max)
                        onValueChange(newValue)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val centerX = w / 2f
                val tickSpacing = 10.dp.toPx()
                val tickCount = (w / tickSpacing).toInt() + 4

                // Offset ticks based on current value
                val scrollOffset = ((value - 100f) * 1.5f) % tickSpacing

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

        // 3. Current Value on right (e.g., "100%", "0.0%")
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
