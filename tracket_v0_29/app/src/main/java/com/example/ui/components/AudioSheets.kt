package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.EditorSurface
import com.example.ui.theme.EditorSurfaceVariant
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.util.bouncyClickable
import java.util.Locale
import kotlin.math.roundToInt

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = NeonCyan,
    activeTrackColor = NeonCyan
)

/** Volume of one audio layer, 0..100 %. Changes are applied live. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioVolumeSheet(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = EditorSurface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Volume", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("${(volume * 100f).roundToInt()}%", color = NeonCyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Slider(
                value = volume,
                onValueChange = onVolumeChange,
                valueRange = 0f..1f,
                colors = sliderColors()
            )
        }
    }
}

/** Fade in / fade out length of one audio layer. Changes are applied live. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioFadeSheet(
    fadeInMs: Long,
    fadeOutMs: Long,
    maxFadeMs: Long,
    onChange: (fadeInMs: Long, fadeOutMs: Long) -> Unit,
    onDismiss: () -> Unit
) {
    val maxFade = maxFadeMs.coerceIn(500L, 10_000L).toFloat()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = EditorSurface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("Fade", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Fade in", color = TextSecondary, fontSize = 14.sp)
                Text(String.format(Locale.US, "%.1fs", fadeInMs / 1000f), color = NeonCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = fadeInMs.toFloat().coerceIn(0f, maxFade),
                onValueChange = { onChange(it.toLong(), fadeOutMs) },
                valueRange = 0f..maxFade,
                colors = sliderColors()
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Fade out", color = TextSecondary, fontSize = 14.sp)
                Text(String.format(Locale.US, "%.1fs", fadeOutMs / 1000f), color = NeonCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = fadeOutMs.toFloat().coerceIn(0f, maxFade),
                onValueChange = { onChange(fadeInMs, it.toLong()) },
                valueRange = 0f..maxFade,
                colors = sliderColors()
            )
        }
    }
}

/** Speed of one audio layer (0.25x..4x, pitch stays the same). Applied when the finger lifts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSpeedSheet(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var shown by remember { mutableFloatStateOf(speed) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = EditorSurface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Speed", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(String.format(Locale.US, "%.2fx", shown), color = NeonCyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Slider(
                value = shown,
                onValueChange = { shown = it },
                onValueChangeFinished = { onSpeedChange(shown) },
                valueRange = 0.25f..4f,
                colors = sliderColors()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(0.5f, 1f, 1.5f, 2f).forEach { preset ->
                    Text(
                        text = String.format(Locale.US, "%.1fx", preset),
                        color = TextPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(EditorSurfaceVariant)
                            .bouncyClickable {
                                shown = preset
                                onSpeedChange(preset)
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}
