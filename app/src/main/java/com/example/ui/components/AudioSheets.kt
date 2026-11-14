package com.example.ui.components
import androidx.compose.ui.graphics.RectangleShape

import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import com.example.model.segmentIndexAt
import com.example.model.indexOnTime
import com.example.model.TimelineClip
import com.example.model.Easing
import com.example.model.AnimProp
import com.example.model.MIN_SPEED
import com.example.model.MAX_SPEED
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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

/** Volume of one layer (audio or video), 0..200 %, plus a mute switch. Changes are applied live. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioVolumeSheet(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onDismiss: () -> Unit,
    muted: Boolean = false,
    onMutedChange: ((Boolean) -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
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
                Text(
                    if (muted) "Muted" else "${(volume * 100f).roundToInt()}%",
                    color = NeonCyan, fontSize = 16.sp, fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Slider(
                value = volume.coerceIn(0f, 2f),
                onValueChange = {
                    onVolumeChange(it)
                    // Moving the slider means "I want to hear it": leave mute.
                    if (muted) onMutedChange?.invoke(false)
                },
                valueRange = 0f..2f,
                colors = sliderColors()
            )
            if (onMutedChange != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Mute this layer", color = TextSecondary, fontSize = 14.sp)
                    Switch(
                        checked = muted,
                        onCheckedChange = onMutedChange,
                        colors = SwitchDefaults.colors(checkedTrackColor = NeonCyan)
                    )
                }
            }
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
    Column(modifier = Modifier.fillMaxWidth()) {
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

/**
 * Speed of one layer (0.1x..4x, pitch stays the same). Without speed keyframes a change sets the
 * base speed (the layer gets shorter / longer on the timeline). With speed keyframes a change is
 * stored as a keyframe at the playhead, and the diamond / easing work like the other panels.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSpeedSheet(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    onDismiss: () -> Unit,
    rawClip: TimelineClip? = null,
    playheadMs: Long = 0L,
    onLiveSpeed: ((Float) -> Unit)? = null,
    onToggleKeyframes: (List<AnimProp>) -> Unit = {},
    onEasingBegin: () -> Unit = {},
    onEasingChange: (AnimProp, Long, Easing) -> Unit = { _, _, _ -> },
    /** Video layers: pre-render the slow part with new frames made between the real ones (smooth slow-mo). */
    onSmooth: (() -> Unit)? = null
) {
    val props = remember { listOf(AnimProp.SPEED) }
    val relMs = if (rawClip != null) playheadMs - rawClip.startMs else 0L
    val keys = rawClip?.keyframes?.get(AnimProp.SPEED).orEmpty()
    val keyed = keys.isNotEmpty()
    val insideClip = rawClip != null && playheadMs >= rawClip.startMs && playheadMs <= rawClip.endMs
    val onKeyframeNow = keys.indexOnTime(relMs) >= 0
    val segIdx = keys.segmentIndexAt(relMs)
    val easingEnabled = segIdx >= 0
    val segStart = if (segIdx >= 0) keys[segIdx] else null
    val segEnd = if (segIdx >= 0) keys[segIdx + 1] else null
    var showEasing by remember { mutableStateOf(false) }
    LaunchedEffect(easingEnabled) { if (!easingEnabled) showEasing = false }

    var shown by remember { mutableFloatStateOf(speed) }
    LaunchedEffect(speed) { shown = speed }

    // With keyframes every change is written at the playhead right away; without them the base
    // speed changes (on finger lift for the slider, at once for the presets).
    fun applySpeed(v: Float) {
        shown = v
        if (keyed && onLiveSpeed != null) onLiveSpeed(v) else onSpeedChange(v)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        TransformValueHeader(
            valueText = String.format(Locale.US, "%.2fx", shown),
            onBack = null,
            keyframeEnabled = insideClip,
            hasKeyframes = keyed,
            onKeyframeNow = onKeyframeNow,
            onKeyframe = { onToggleKeyframes(props) },
            easingEnabled = easingEnabled,
            easingOpen = showEasing && easingEnabled,
            easing = segStart?.easing,
            onEasing = { if (easingEnabled) showEasing = !showEasing }
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))
            if (showEasing && segStart != null && segEnd != null) {
                Box(modifier = Modifier.fillMaxWidth().height(180.dp)) {
                    EasingEditor(
                        easing = segStart.easing,
                        progress = ((relMs - segStart.timeMs).toFloat() /
                            (segEnd.timeMs - segStart.timeMs).coerceAtLeast(1L)).coerceIn(0f, 1f),
                        onBegin = onEasingBegin,
                        onChange = { e -> onEasingChange(AnimProp.SPEED, segStart.timeMs, e) }
                    )
                }
            } else {
                Slider(
                    value = shown,
                    onValueChange = { v -> if (keyed) applySpeed(v) else shown = v },
                    onValueChangeFinished = { if (!keyed) onSpeedChange(shown) },
                    valueRange = MIN_SPEED..MAX_SPEED,
                    colors = sliderColors()
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(0.1f, 0.5f, 1f, 2f, 4f).forEach { preset ->
                    Text(
                        text = String.format(Locale.US, "%.1fx", preset),
                        color = TextPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RectangleShape)
                            .background(EditorSurfaceVariant)
                            .bouncyClickable { applySpeed(preset) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
            if (onSmooth != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Smooth slow-mo (make new frames)",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RectangleShape)
                        .background(EditorSurfaceVariant)
                        .bouncyClickable { onSmooth() }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                )
                Text(
                    text = "Slow the layer first (below 1x). The slow part is re-made as a new video; sound is not kept.",
                    color = TextPrimary.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

/**
 * Ducking of one audio layer (build 194): while the chosen trigger layer is loud, this
 * layer's volume is lowered by up to the amount and let back up in the gaps. The settings
 * live on this layer; the gain itself is computed live from the trigger's sound (see
 * [com.example.model.Ducking]) — nothing is baked. Changes are applied live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioDuckingSheet(
    clip: TimelineClip,
    triggers: List<TimelineClip>,
    onChange: (enabled: Boolean, triggerId: String?, amountDb: Float, smoothness: Float) -> Unit,
    onDismiss: () -> Unit
) {
    val enabled = clip.duckEnabled
    val triggerId = clip.duckTriggerId
    val amountDb = clip.duckAmountDb
    val smoothness = clip.duckSmoothness
    Column(modifier = Modifier.fillMaxWidth()) {
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
                Text("Ducking", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Switch(
                    checked = enabled,
                    onCheckedChange = { onChange(it, triggerId, amountDb, smoothness) },
                    colors = SwitchDefaults.colors(checkedTrackColor = NeonCyan)
                )
            }
            Text(
                "Lower this layer's volume automatically while another layer is loud, " +
                    "and let it back up in the gaps.",
                color = TextSecondary, fontSize = 13.sp
            )
            if (triggers.isEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "No other layer with sound yet. Add a voice or music layer first, " +
                        "then pick it here as the trigger.",
                    color = TextSecondary, fontSize = 14.sp
                )
            } else if (enabled) {
                Spacer(modifier = Modifier.height(14.dp))
                Text("Duck under", color = TextSecondary, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(4.dp))
                triggers.forEach { trigger ->
                    val selected = trigger.id == triggerId
                    Text(
                        text = trigger.name,
                        color = if (selected) TextPrimary else TextSecondary,
                        fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .bouncyClickable { onChange(true, trigger.id, amountDb, smoothness) }
                            .padding(vertical = 8.dp)
                    )
                }
                if (triggerId != null && triggers.none { it.id == triggerId }) {
                    Text(
                        "The trigger layer was removed — pick another layer above.",
                        color = TextSecondary, fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Amount", color = TextSecondary, fontSize = 14.sp)
                    Text(
                        "−${amountDb.roundToInt()} dB",
                        color = NeonCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = amountDb.coerceIn(0f, com.example.model.Ducking.MAX_AMOUNT_DB),
                    onValueChange = { onChange(true, triggerId, it, smoothness) },
                    valueRange = 0f..com.example.model.Ducking.MAX_AMOUNT_DB,
                    colors = sliderColors()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Smoothness", color = TextSecondary, fontSize = 14.sp)
                    Text(
                        "${(smoothness * 100f).roundToInt()}%",
                        color = NeonCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = smoothness.coerceIn(0f, 1f),
                    onValueChange = { onChange(true, triggerId, amountDb, it) },
                    valueRange = 0f..1f,
                    colors = sliderColors()
                )
            }
        }
    }
}

/**
 * Noise reduction of one audio layer (build 195): the layer's steady background
 * noise (hiss, hum, fan noise) is gated down against a noise profile estimated
 * from the layer's own sound (see [com.example.model.NoiseReduction]). The two
 * settings live on this layer; the profile itself is computed live by the sound
 * paths — nothing is baked. Changes are applied live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioNoiseSheet(
    clip: TimelineClip,
    onChange: (enabled: Boolean, strength: Float) -> Unit,
    onDismiss: () -> Unit
) {
    val enabled = clip.nrEnabled
    val strength = clip.nrStrength
    Column(modifier = Modifier.fillMaxWidth()) {
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
                Text("Noise reduction", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Switch(
                    checked = enabled,
                    onCheckedChange = { onChange(it, strength) },
                    colors = SwitchDefaults.colors(checkedTrackColor = NeonCyan)
                )
            }
            Text(
                "Remove the steady background noise of this layer — hiss, hum, fan " +
                    "noise — while keeping the voice or music on top of it.",
                color = TextSecondary, fontSize = 13.sp
            )
            if (enabled) {
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Strength", color = TextSecondary, fontSize = 14.sp)
                    Text(
                        "${(strength * 100f).roundToInt()}%",
                        color = NeonCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = strength.coerceIn(0f, 1f),
                    onValueChange = { onChange(true, it) },
                    valueRange = 0f..1f,
                    colors = sliderColors()
                )
                Text(
                    "The layer's sound is analysed the first time it plays and after " +
                        "its trim or speed changes — until the analysis lands, the layer " +
                        "plays unprocessed. Exports are always cleaned.",
                    color = TextSecondary, fontSize = 13.sp
                )
            }
        }
    }
}
