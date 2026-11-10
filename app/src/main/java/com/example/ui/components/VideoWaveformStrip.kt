package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.engine.AudioWaveform
import com.example.model.TimelineClip

/**
 * Waveform of a video layer's own sound, drawn over the bottom of its frame strip.
 * Draws nothing when the video has no audio (the waveform came back empty) or is muted.
 * Only the part that is on screen is drawn.
 */
@Composable
fun VideoWaveformStrip(
    clip: TimelineClip,
    waveform: FloatArray?,
    visibleFromMs: Long,
    visibleToMs: Long,
    pxPerMs: Float,
    modifier: Modifier = Modifier
) {
    if (waveform == null || waveform.isEmpty() || clip.isMuted) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.5f)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val barStep = 3.dp.toPx()
            val barWidth = 1.5.dp.toPx()
            val pxPerMsPx = pxPerMs.dp.toPx()
            val fromPx = ((visibleFromMs - clip.startMs) * pxPerMsPx).coerceIn(0f, size.width)
            val toPx = ((visibleToMs - clip.startMs) * pxPerMsPx).coerceIn(0f, size.width)
            if (toPx <= fromPx) return@Canvas

            val baseY = size.height - 2.dp.toPx()
            val maxH = size.height - 4.dp.toPx()
            val msPerBar = barStep / pxPerMsPx * clip.speed

            var x = (fromPx / barStep).toInt() * barStep
            while (x < toPx) {
                val srcMs = clip.trimStartMs + (x / pxPerMsPx) * clip.speed
                val b0 = (srcMs / AudioWaveform.BUCKET_MS).toInt().coerceIn(0, waveform.size - 1)
                val b1 = ((srcMs + msPerBar) / AudioWaveform.BUCKET_MS).toInt().coerceIn(b0, waveform.size - 1)
                var peak = 0f
                for (b in b0..b1) if (waveform[b] > peak) peak = waveform[b]
                val h = (maxH * peak * clip.volume.coerceIn(0f, 1f)).coerceAtLeast(1.dp.toPx())
                drawLine(
                    color = Color.White.copy(alpha = 0.9f),
                    start = Offset(x, baseY),
                    end = Offset(x, baseY - h),
                    strokeWidth = barWidth
                )
                x += barStep
            }
        }
    }
}
