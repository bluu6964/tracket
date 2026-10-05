package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.EditorBackground
import com.example.ui.theme.IconActive
import com.example.ui.theme.IconDisabled
import com.example.ui.util.bouncyClickable

@Composable
fun PlayControlsSection(
    height: Dp,
    isPlaying: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onFullScreenClick: () -> Unit,
    onResolutionClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onUndoClick: () -> Unit,
    onRedoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Spring pop animation when play state toggles
    val playPauseScale by animateFloatAsState(
        targetValue = if (isPlaying) 1.08f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "playPauseSpring"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(EditorBackground)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left side: Fullscreen + Preview Resolution
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .bouncyClickable(onClick = onFullScreenClick)
                    .testTag("fullscreen_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CropFree,
                    contentDescription = "Full Screen",
                    tint = IconActive,
                    modifier = Modifier.size(24.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .bouncyClickable(onClick = onResolutionClick)
                    .testTag("preview_resolution_button"),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.size(26.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = "Preview Resolution",
                        tint = IconActive,
                        modifier = Modifier.size(22.dp)
                    )
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = IconActive,
                        modifier = Modifier
                            .size(11.dp)
                            .offset(x = 6.dp, y = 5.dp)
                    )
                }
            }
        }

        // Center: Skip Previous, Play/Pause, Skip Next with Spring
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .bouncyClickable(onClick = onPreviousClick)
                    .testTag("prev_frame_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Jump Previous",
                    tint = IconActive,
                    modifier = Modifier.size(26.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .graphicsLayer {
                        scaleX = playPauseScale
                        scaleY = playPauseScale
                    }
                    .bouncyClickable(
                        pressedScale = 0.85f,
                        hoverScale = 1.12f,
                        onClick = onPlayPauseClick
                    )
                    .testTag("play_pause_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = IconActive,
                    modifier = Modifier.size(34.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .bouncyClickable(onClick = onNextClick)
                    .testTag("next_frame_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Jump Next",
                    tint = IconActive,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        // Right side: Undo & Redo with Spring
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .bouncyClickable(
                        enabled = canUndo,
                        onClick = onUndoClick
                    )
                    .testTag("undo_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = "Undo",
                    tint = if (canUndo) IconActive else IconDisabled,
                    modifier = Modifier.size(22.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .bouncyClickable(
                        enabled = canRedo,
                        onClick = onRedoClick
                    )
                    .testTag("redo_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Redo,
                    contentDescription = "Redo",
                    tint = if (canRedo) IconActive else IconDisabled,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
