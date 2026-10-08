package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.pxPerMs
import com.example.ui.theme.EditorBackground
import com.example.ui.theme.PlayheadColor
import com.example.ui.theme.RulerSubTickColor
import com.example.ui.theme.RulerTickColor
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun TimescaleSection(
    height: Dp,
    currentPlayheadMs: Long,
    totalDurationMs: Long,
    pxPerMs: Float = TIMELINE_PX_PER_MS,
    modifier: Modifier = Modifier
) {
    val currentTimeStr = formatTimeCode(currentPlayheadMs)
    val totalTimeStr = formatTimeCode(totalDurationMs)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(EditorBackground)
            .testTag("timescale_section")
    ) {
        val screenWidth = maxWidth
        val centerLineX = screenWidth / 2 // Exact 50% horizontal center

        // 1. Current playhead position (00:00 00) - to the left of the exact center
        Box(
            modifier = Modifier
                .width(centerLineX - 8.dp)
                .fillMaxHeight()
                .align(Alignment.CenterStart),
            contentAlignment = Alignment.CenterEnd
        ) {
            Text(
                text = currentTimeStr,
                color = TextPrimary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End
            )
        }

        // 2. Center Divider line (|) - collinear with the locked vertical playhead below
        Canvas(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .offset(x = centerLineX - 1.dp)
        ) {
            drawLine(
                color = PlayheadColor,
                start = Offset(1.dp.toPx(), 0f),
                end = Offset(1.dp.toPx(), size.height),
                strokeWidth = 2.dp.toPx()
            )
        }

        // 3. Right side: Total duration (00:37 00)
        val durationLabelWidth = 72.dp
        Box(
            modifier = Modifier
                .offset(x = centerLineX + 8.dp)
                .width(durationLabelWidth)
                .fillMaxHeight(),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = totalTimeStr,
                color = TextSecondary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
        }

        // 4. Timescale ruler tick marks starting after total duration and extending to the right
        // EXACT mathematical 1:1 proportionality with TimelineLayerPanel via pxPerMs
        val rulerStartX = centerLineX + 8.dp + durationLabelWidth + 4.dp
        val rulerWidth = (screenWidth - rulerStartX).coerceAtLeast(0.dp)

        Box(
            modifier = Modifier
                .offset(x = rulerStartX)
                .width(rulerWidth)
                .fillMaxHeight(),
            contentAlignment = Alignment.CenterStart
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val h = size.height

                // Calculate time window visible in ruler canvas
                // tickX_dp = centerLineX + (tickTimeMs - currentPlayheadMs) * pxPerMs
                // In canvas local coordinates: localX_dp = tickX_dp - rulerStartX
                val pxPerMsDp = pxPerMs.dp.toPx()

                // Calculate min and max timeMs visible in this section
                val minVisibleMs = currentPlayheadMs + ((rulerStartX - centerLineX).toPx() / pxPerMsDp).toLong()
                val maxVisibleMs = currentPlayheadMs + ((screenWidth - centerLineX).toPx() / pxPerMsDp).toLong()

                // Minor ticks every 200ms, Major ticks every 1000ms
                val startTickMs = (minVisibleMs / 200L) * 200L
                val endTickMs = ((maxVisibleMs / 200L) + 1) * 200L

                for (t in startTickMs..endTickMs step 200L) {
                    val tickGlobalX = centerLineX.toPx() + (t - currentPlayheadMs) * pxPerMsDp
                    val tickLocalX = tickGlobalX - rulerStartX.toPx()

                    if (tickLocalX in 0f..size.width) {
                        val isMajorTick = (t % 1000L == 0L)
                        val tickHeight = if (isMajorTick) h * 0.72f else h * 0.36f
                        val yStart = (h - tickHeight) / 2f
                        val yEnd = yStart + tickHeight

                        drawLine(
                            color = if (isMajorTick) RulerTickColor else RulerSubTickColor,
                            start = Offset(tickLocalX, yStart),
                            end = Offset(tickLocalX, yEnd),
                            strokeWidth = if (isMajorTick) 1.5.dp.toPx() else 1.dp.toPx()
                        )
                    }
                }
            }
        }
    }
}

fun formatTimeCode(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hundredths = (ms % 1000) / 10
    return String.format(Locale.US, "%02d:%02d %02d", minutes, seconds, hundredths)
}
