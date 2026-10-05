package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.model.AspectRatio
import com.example.model.LayerType
import com.example.model.TimelineTrack

@Composable
fun CanvasArea(
    height: Dp,
    aspectRatio: AspectRatio,
    currentPlayheadMs: Long,
    isPlaying: Boolean,
    tracks: List<TimelineTrack>,
    selectedClipId: String? = null,
    masterMuted: Boolean = false,
    onTogglePlayPause: () -> Unit,
    onDuration: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val active = tracks.flatMap { it.clips }
        .filter { it.type == LayerType.VIDEO && it.sourceUri != null && it.isActiveAt(currentPlayheadMs) }
        .maxByOrNull { it.startMs }
    val sourcePosition = active?.let { it.sourceStartMs + (currentPlayheadMs - it.startMs) } ?: 0L

    Box(
        modifier = modifier.fillMaxSize().height(height).background(Color(0xFF090A0D)),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            val ratio = aspectRatio.ratioFloat
            val frame = if (maxWidth.value / maxHeight.value > ratio) maxHeight * ratio to maxHeight else maxWidth to (maxWidth / ratio)
            Box(
                modifier = Modifier.size(frame.first, frame.second).clip(RoundedCornerShape(8.dp)).background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                MediaPreview(
                    sourceUri = active?.sourceUri,
                    positionMs = sourcePosition,
                    isPlaying = isPlaying,
                    muted = masterMuted || tracks.filter { it.type == LayerType.AUDIO }.all { it.isMuted },
                    modifier = Modifier.fillMaxSize(),
                    onDuration = onDuration
                )
                if (active == null && tracks.flatMap { it.clips }.none { it.sourceUri != null }) {
                    Text("No media imported", color = Color.White)
                }
            }
        }
    }
}
