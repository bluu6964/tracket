package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.TIMELINE_PX_PER_MS
import com.example.ui.theme.AmBaseDeep
import com.example.ui.theme.AmCyanBright
import com.example.ui.theme.AmOrange
import com.example.ui.theme.AmTextFaint
import com.example.ui.theme.IconActive
import com.example.ui.theme.TextPrimary
import com.example.ui.util.bouncyClickable
import java.util.Locale

// Geometry measured from the reference (dp):  [4] [pill 48] [5] [ bars ... ] [5] [pill] [4]
private val PillMargin = 4.dp
private val LeftPillWidth = 48.dp
private val PillGap = 5.dp
private val PillHeight = 19.dp
private val BarWidth = 4.dp
private val BarHeight = 16.5.dp
private val MinBarPitch = 8.dp
private val BeatMarkColor = AmCyanBright
private val TickSteps = longArrayOf(200L, 500L, 1000L, 2000L, 5000L, 10_000L, 30_000L, 60_000L)

/**
 * Segmented ruler: rounded bars that scroll with the playhead (one bar per time step).
 * Bars before the playhead are orange, after it grey; the bar under the playhead is a mix and
 * both ends fade out. The current time sits in a pill on the left, the project length on the right.
 */
@Composable
fun TimescaleSection(
    height: Dp,
    playheadMs: () -> Long,
    totalDurationMs: Long,
    pxPerMs: Float = TIMELINE_PX_PER_MS,
    modifier: Modifier = Modifier,
    /** When set, a back chevron sits at the left end of the ruler. */
    onBack: (() -> Unit)? = null,
    /** Beat marks (project time, ms): drawn as cyan marks on the ruler. */
    beatMarksMs: List<Long> = emptyList()
) {
    val backInset = if (onBack != null) 44.dp else 0.dp
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(AmBaseDeep)
            .testTag("timescale_section")
    ) {
        val rightPillWidth = when {
            totalDurationMs >= 3_600_000L -> 64.dp
            totalDurationMs >= 600_000L -> 48.dp
            else -> 42.dp
        }
        // The playhead pill widens the same way once the playhead itself passes an hour.
        val leftPillWidth = if (playheadMs() >= 3_600_000L) 64.dp else LeftPillWidth

        if (onBack != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(44.dp)
                    .fillMaxHeight()
                    .bouncyClickable(onClick = onBack)
                    .testTag("panel_back_button"),
                contentAlignment = Alignment.Center
            ) {
                // BARE chevron (boxless order, build 183); the 44 dp hit area is unchanged.
                Box(
                    modifier = Modifier.size(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Back",
                        tint = IconActive,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // ── Scrolling bars ─────────────────────────────────────────────
        Canvas(modifier = Modifier.fillMaxSize()) {
            val currentMs = playheadMs()
            val w = size.width
            val centerPx = w / 2f
            val barW = BarWidth.toPx()
            val barH = BarHeight.toPx()
            val top = (size.height - barH) / 2f
            val areaStart = (backInset + PillMargin + leftPillWidth + PillGap).toPx()
            val areaEnd = w - (PillMargin + rightPillWidth + PillGap).toPx()
            val pxPerMsPx = pxPerMs.dp.toPx()

            // Step grows when the timeline is zoomed out so the bars never get closer than ~8 dp.
            var step = TickSteps.last()
            for (s in TickSteps) {
                if (s * pxPerMsPx >= MinBarPitch.toPx()) { step = s; break }
            }
            val pitch = step * pxPerMsPx
            val lastMs = if (totalDurationMs > 0L) totalDurationMs else Long.MAX_VALUE / 4

            val halfSpanMs = (centerPx / pxPerMsPx).toLong() + step
            val firstTick = (((currentMs - halfSpanMs).coerceAtLeast(0L)) / step) * step
            val lastTick = currentMs + halfSpanMs
            val corner = CornerRadius(barW / 2f)

            var t = firstTick
            while (t <= lastTick && t <= lastMs) {
                val x = centerPx + (t - currentMs) * pxPerMsPx          // bar centre
                val left = x - barW / 2f
                if (left >= areaStart && left + barW <= areaEnd) {
                    // 1 = elapsed (orange), 0 = upcoming (grey); the bar under the playhead mixes.
                    val elapsed = ((centerPx - x) / pitch + 0.1f).coerceIn(0f, 1f)
                    val leftFade = 0.15f + 0.85f * ((x - areaStart) / (pitch * 2.5f)).coerceIn(0f, 1f)
                    val rightFade = 0.72f + 0.28f * ((areaEnd - x) / (pitch * 6f)).coerceIn(0f, 1f)
                    val alpha = minOf(leftFade, rightFade)
                    drawRoundRect(
                        color = lerp(AmTextFaint, AmOrange, elapsed).copy(alpha = alpha),
                        topLeft = Offset(left, top),
                        size = Size(barW, barH),
                        cornerRadius = corner
                    )
                }
                t += step
            }

            // Beat marks: cyan, taller than the bars, so they stand out on the ruler.
            if (beatMarksMs.isNotEmpty()) {
                val markW = 3.dp.toPx()
                val markH = size.height * 0.82f
                val markTop = (size.height - markH) / 2f
                for (m in beatMarksMs) {
                    val x = centerPx + (m - currentMs) * pxPerMsPx
                    val left = x - markW / 2f
                    if (left >= areaStart && left + markW <= areaEnd) {
                        drawRoundRect(
                            color = BeatMarkColor,
                            topLeft = Offset(left, markTop),
                            size = Size(markW, markH),
                            cornerRadius = CornerRadius(markW / 2f)
                        )
                    }
                }
            }
        }

        // ── Current time (left) and project length (right) ─────────────
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = backInset + PillMargin)
                .width(leftPillWidth)
                .height(PillHeight)
                .testTag("playhead_time_pill"),
            contentAlignment = Alignment.Center
        ) {
            FlatPill(Modifier.matchParentSize())
            PillText { formatClock(playheadMs()) }
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = PillMargin)
                .width(rightPillWidth)
                .height(PillHeight)
                .testTag("total_time_pill"),
            contentAlignment = Alignment.Center
        ) {
            FlatPill(Modifier.matchParentSize())
            PillText { formatClock(totalDurationMs) }
        }
    }
}

/** Only this tiny text recomposes while the playhead moves. */
@Composable
private fun PillText(text: () -> String) {
    Text(
        text = text(),
        color = TextPrimary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        maxLines = 1
    )
}

/** "MM:SS" - the clock shown in the ruler pills; from one hour on it becomes "H:MM:SS" (it showed "75:00" before). */
fun formatClock(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L)) / 1000
    return if (totalSeconds >= 3600L) {
        String.format(
            Locale.US, "%d:%02d:%02d",
            totalSeconds / 3600, (totalSeconds / 60) % 60, totalSeconds % 60
        )
    } else {
        String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}

fun formatTimeCode(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hundredths = (ms % 1000) / 10
    // CapCut-style: 00:01:06  (MM:SS:centiseconds)
    return String.format(Locale.US, "%02d:%02d:%02d", minutes, seconds, hundredths)
}
