package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.AudioWaveform
import com.example.model.TIMELINE_PX_PER_MS
import com.example.model.TimelineClip
import java.util.Locale

/**
 * Body of an audio layer on the timeline: waveform of the part of the song this layer plays,
 * a duration badge and the file name. Only the part that is on screen is drawn.
 */
@Composable
fun AudioClipBody(
    clip: TimelineClip,
    width: Dp,
    waveform: FloatArray?,
    visibleFromMs: Long,
    visibleToMs: Long
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF4B4BC8))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val barStep = 3.dp.toPx()
            val barWidth = 1.5.dp.toPx()
            val pxPerMs = TIMELINE_PX_PER_MS.dp.toPx()
            val fromPx = ((visibleFromMs - clip.startMs) * pxPerMs).coerceIn(0f, size.width)
            val toPx = ((visibleToMs - clip.startMs) * pxPerMs).coerceIn(0f, size.width)
            if (toPx <= fromPx) return@Canvas

            val midY = size.height * 0.62f
            val maxHalf = size.height * 0.30f
            val msPerBar = barStep / pxPerMs * clip.speed
            val hasWave = waveform != null && waveform.isNotEmpty()

            var x = (fromPx / barStep).toInt() * barStep
            while (x < toPx) {
                val srcMs = clip.trimStartMs + (x / pxPerMs) * clip.speed
                val level = if (hasWave) {
                    val b0 = (srcMs / AudioWaveform.BUCKET_MS).toInt().coerceIn(0, waveform!!.size - 1)
                    val b1 = ((srcMs + msPerBar) / AudioWaveform.BUCKET_MS).toInt()
                        .coerceIn(b0, waveform.size - 1)
                    var peak = 0f
                    for (b in b0..b1) if (waveform[b] > peak) peak = waveform[b]
                    peak
                } else {
                    0.12f   // flat placeholder until the waveform is decoded
                }
                val half = (maxHalf * level).coerceAtLeast(1.dp.toPx())

                // Volume and fades are drawn into the wave, so the user sees what they set.
                val rel = (x / pxPerMs).toLong()
                val shown = clip.volume.coerceIn(0f, 1f) * fadeAt(clip, rel)
                val h = (half * shown).coerceAtLeast(1.dp.toPx())
                drawLine(
                    color = Color.White.copy(alpha = 0.9f),
                    start = Offset(x, midY - h),
                    end = Offset(x, midY + h),
                    strokeWidth = barWidth
                )
                x += barStep
            }
        }

        Text(
            text = String.format(Locale.US, "%.1fs", clip.durationMs / 1000f),
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 4.dp, top = 2.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color(0xFF34349A))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )
        Text(
            text = clip.name,
            color = Color.White,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 6.dp, bottom = 1.dp)
        )
    }
}

private fun fadeAt(clip: TimelineClip, relMs: Long): Float {
    var g = 1f
    if (clip.fadeInMs > 0L && relMs < clip.fadeInMs) {
        g = minOf(g, relMs.coerceAtLeast(0L) / clip.fadeInMs.toFloat())
    }
    val left = clip.durationMs - relMs
    if (clip.fadeOutMs > 0L && left < clip.fadeOutMs) {
        g = minOf(g, left.coerceAtLeast(0L) / clip.fadeOutMs.toFloat())
    }
    return g.coerceIn(0f, 1f)
}
