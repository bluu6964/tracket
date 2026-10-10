package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowRight
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.AspectRatio
import com.example.ui.theme.CanvasBackground
import com.example.ui.theme.IconActive
import com.example.ui.theme.RulerSubTickColor
import com.example.ui.theme.RulerTickColor
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.util.bouncyClickable
import java.util.UUID

enum class AppliedEffectsScreenMode {
    LIST,         // Shows Add effect button & applied list / empty text (Images 1 & 3)
    LIBRARY,      // Shows categories & grid of effects (Image 2)
    PARAMETERS    // Shows specific effect parameter sliders (Image 4)
}

data class EffectPreset(
    val id: String,
    val name: String,
    val category: String,
    val isPro: Boolean = false,
    val defaultIntensity: Float = 20f,
    val secondaryName: String = "Rotation",
    val defaultSecondary: Float = 45f,
    val secondaryUnit: String = "°",
    val tertiaryName: String = "Edge Hardness",
    val defaultTertiary: Float = 0f,
    val tertiaryUnit: String = "%"
)

class ActiveAppliedEffect(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    initialEnabled: Boolean = true,
    initialIntensity: Float = 20f,
    val secondaryName: String = "Rotation",
    initialSecondary: Float = 45f,
    val secondaryUnit: String = "°",
    val tertiaryName: String = "Edge Hardness",
    initialTertiary: Float = 0f,
    val tertiaryUnit: String = "%"
) {
    var isEnabled by mutableStateOf(initialEnabled)
    var intensity by mutableFloatStateOf(initialIntensity)
    var secondaryValue by mutableFloatStateOf(initialSecondary)
    var tertiaryValue by mutableFloatStateOf(initialTertiary)
}

@Composable
fun AppliedEffectsView(
    aspectRatio: AspectRatio = AspectRatio.RATIO_16_9,
    onDismiss: () -> Unit,
    onEffectApplied: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Current screen mode: LIST (Applied Effects), LIBRARY (Add effect picker), PARAMETERS (sliders)
    var screenMode by remember { mutableStateOf(AppliedEffectsScreenMode.LIST) }

    // Applied effects list
    val appliedEffects = remember { mutableStateListOf<ActiveAppliedEffect>() }
    var selectedEffectForEditing by remember { mutableStateOf<ActiveAppliedEffect?>(null) }

    // Library category selection
    var selectedCategory by remember { mutableStateOf("Blur") }
    val categories = listOf("Blur", "Background", "Mask & Edges")

    // Playback state
    var isPlaying by remember { mutableStateOf(false) }
    var currentTimeMs by remember { mutableLongStateOf(10L) } // 00:00 01
    val totalTimeMs = 37000L // 00:37 00 matching screenshots

    val blurPresets = listOf(
        EffectPreset("gaussian_blur", "Gaussian Blur", "Blur", isPro = false, defaultIntensity = 25f, secondaryName = "Radius", defaultSecondary = 15f, secondaryUnit = "px"),
        EffectPreset("mask_blur", "Mask Blur", "Blur", isPro = true, defaultIntensity = 40f, secondaryName = "Feather", defaultSecondary = 20f, secondaryUnit = "%"),
        EffectPreset("directional_blur", "Directional Blur", "Blur", isPro = false, defaultIntensity = 20f, secondaryName = "Rotation", defaultSecondary = 45f, secondaryUnit = "°", tertiaryName = "Edge Hardness", defaultTertiary = 0f, tertiaryUnit = "%"),
        EffectPreset("tilt_shift", "Tilt-Shift", "Blur", isPro = true, defaultIntensity = 30f, secondaryName = "Position", defaultSecondary = 50f, secondaryUnit = "%"),
        EffectPreset("fast_box_blur", "Fast Box Blur", "Blur", isPro = false, defaultIntensity = 15f, secondaryName = "Iterations", defaultSecondary = 3f, secondaryUnit = ""),
        EffectPreset("iris_blur", "Iris Blur", "Blur", isPro = true, defaultIntensity = 35f, secondaryName = "Radius", defaultSecondary = 60f, secondaryUnit = "%"),
        EffectPreset("edge_aware_blur", "Edge-Aware Blur", "Blur", isPro = false, defaultIntensity = 25f, secondaryName = "Threshold", defaultSecondary = 40f, secondaryUnit = "%"),
        EffectPreset("radial_spin", "Radial Spin", "Blur", isPro = true, defaultIntensity = 30f, secondaryName = "Angle", defaultSecondary = 60f, secondaryUnit = "°")
    )

    val backgroundPresets = listOf(
        EffectPreset("bg_glow", "Ambient Glow", "Background", isPro = false, defaultIntensity = 30f, secondaryName = "Spread", defaultSecondary = 40f, secondaryUnit = "%"),
        EffectPreset("dark_vignette", "Dark Vignette", "Background", isPro = false, defaultIntensity = 45f, secondaryName = "Roundness", defaultSecondary = 50f, secondaryUnit = "%"),
        EffectPreset("neon_halo", "Neon Halo", "Background", isPro = true, defaultIntensity = 50f, secondaryName = "Color Tint", defaultSecondary = 180f, secondaryUnit = "°"),
        EffectPreset("bokeh_dust", "Bokeh Dust", "Background", isPro = true, defaultIntensity = 20f, secondaryName = "Density", defaultSecondary = 30f, secondaryUnit = "%")
    )

    val maskPresets = listOf(
        EffectPreset("edge_glow", "Edge Glow", "Mask & Edges", isPro = false, defaultIntensity = 35f, secondaryName = "Width", defaultSecondary = 10f, secondaryUnit = "px"),
        EffectPreset("cutout_fringe", "Cutout Fringe", "Mask & Edges", isPro = true, defaultIntensity = 25f, secondaryName = "Chroma Key", defaultSecondary = 15f, secondaryUnit = "%"),
        EffectPreset("alpha_feather", "Alpha Feather", "Mask & Edges", isPro = false, defaultIntensity = 20f, secondaryName = "Feather", defaultSecondary = 12f, secondaryUnit = "px"),
        EffectPreset("outline_stroke", "Outline Stroke", "Mask & Edges", isPro = true, defaultIntensity = 30f, secondaryName = "Thickness", defaultSecondary = 4f, secondaryUnit = "px")
    )

    val currentPresets = when (selectedCategory) {
        "Blur" -> blurPresets
        "Background" -> backgroundPresets
        else -> maskPresets
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F1116))
            .testTag("applied_effects_screen")
    ) {
        // 1. Header Bar (matching screenshot: Left down arrow button, Center Title)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .bouncyClickable(onClick = {
                        when (screenMode) {
                            AppliedEffectsScreenMode.PARAMETERS -> {
                                // Go back to applied effects list
                                screenMode = AppliedEffectsScreenMode.LIST
                            }
                            AppliedEffectsScreenMode.LIBRARY -> {
                                // Go back to applied effects list
                                screenMode = AppliedEffectsScreenMode.LIST
                            }
                            AppliedEffectsScreenMode.LIST -> {
                                // Exit to main editor
                                onDismiss()
                            }
                        }
                    })
                    .testTag("effects_back_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Back",
                    tint = IconActive,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = if (screenMode == AppliedEffectsScreenMode.PARAMETERS && selectedEffectForEditing != null) {
                    selectedEffectForEditing!!.name
                } else {
                    "Applied Effects"
                },
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.size(44.dp))
        }

        // 2. Canvas Area (STRICTLY LOCKED TO PROJECT ASPECT RATIO)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            val maxW = maxWidth
            val maxH = maxHeight
            val targetRatio = aspectRatio.ratioFloat

            val (frameW, frameH) = remember(maxW, maxH, targetRatio) {
                if (maxW.value / maxH.value > targetRatio) {
                    (maxH * targetRatio) to maxH
                } else {
                    maxW to (maxW / targetRatio)
                }
            }

            // Locked Aspect-Ratio Video Container
            Box(
                modifier = Modifier
                    .size(width = frameW, height = frameH)
                    .clip(RoundedCornerShape(8.dp))
                    .background(CanvasBackground)
                    .shadow(elevation = 6.dp, shape = RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                // Background Video Clip with real-time blur when effect is active
                val activeBlur = appliedEffects.firstOrNull { it.isEnabled }
                val blurAmount = if (activeBlur != null) {
                    (activeBlur.intensity / 6f).dp
                } else if (screenMode == AppliedEffectsScreenMode.PARAMETERS && selectedEffectForEditing != null) {
                    (selectedEffectForEditing!!.intensity / 6f).dp
                } else 0.dp

                Image(
                    painter = painterResource(id = R.drawable.sample_portrait_1791121452621),
                    contentDescription = "Background frame",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (blurAmount > 0.dp) Modifier.blur(blurAmount) else Modifier)
                )

                // Darkening vignette overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f))
                )

                // Central glowing particles matching screenshot
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cX = size.width / 2f
                    val cY = size.height / 2f
                    val dotsCount = 7
                    val radius = size.minDimension * 0.32f
                    for (i in 0 until dotsCount) {
                        val angle = Math.toRadians((i * 360.0 / dotsCount) - 90.0)
                        val x = (cX + radius * Math.cos(angle)).toFloat()
                        val y = (cY + radius * Math.sin(angle)).toFloat()
                        drawCircle(
                            color = Color(0xFF00E5FF).copy(alpha = 0.85f),
                            radius = 9.dp.toPx(),
                            center = Offset(x, y)
                        )
                    }
                }
            }
        }

        // 3. Playback Controls & Timescale & Mini Layer Strip (matching screenshot)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            // Controls: Skip Prev, Play/Pause, Skip Next, Undo, Redo
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width(40.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .bouncyClickable(onClick = { currentTimeMs = 0L }),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Prev", tint = IconActive, modifier = Modifier.size(24.dp))
                    }

                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .bouncyClickable(onClick = { isPlaying = !isPlaying }),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = IconActive,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .bouncyClickable(onClick = { currentTimeMs = totalTimeMs }),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = IconActive, modifier = Modifier.size(24.dp))
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", tint = IconActive, modifier = Modifier.size(20.dp))
                    }
                    Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo", tint = IconActive, modifier = Modifier.size(20.dp))
                    }
                }
            }

            // Timescale: 00:00 01 | 00:37 00 (matching screenshot)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.weight(0.38f))
                Text(
                    text = "00:00 01",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(text = "  |  ", color = TextSecondary, fontSize = 13.sp)
                Text(
                    text = "00:37 00",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.width(8.dp))
                // Ruler ticks
                Canvas(
                    modifier = Modifier
                        .weight(0.45f)
                        .height(14.dp)
                ) {
                    val tickSpacing = 8.dp.toPx()
                    val count = (size.width / tickSpacing).toInt()
                    for (i in 0..count) {
                        val isMajor = (i % 5 == 0)
                        drawLine(
                            color = if (isMajor) RulerTickColor else RulerSubTickColor,
                            start = Offset(i * tickSpacing, 0f),
                            end = Offset(i * tickSpacing, if (isMajor) size.height else size.height * 0.5f),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                }
            }

            // Mini Layer Strip with video thumbnail and keyframe diamond button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .height(30.dp)
            ) {
                Row(
                    modifier = Modifier
                        .offset(x = 120.dp)
                        .height(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF2C3240))
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.sample_portrait_1791121452621),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(80.dp)
                            .height(24.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    // Diamond/Keyframe + button (matching screenshot)
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer { rotationZ = 45f }
                            .background(Color(0xFF4B5568), RoundedCornerShape(2.dp))
                            .bouncyClickable(onClick = {}),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Keyframe",
                            tint = Color.White,
                            modifier = Modifier
                                .size(12.dp)
                                .graphicsLayer { rotationZ = -45f }
                        )
                    }
                }

                // Vertical center playhead line
                Canvas(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .align(Alignment.Center)
                ) {
                    drawLine(
                        color = Color.White,
                        start = Offset(0f, 0f),
                        end = Offset(0f, size.height),
                        strokeWidth = 2.dp.toPx()
                    )
                }
            }
        }

        // 4. Bottom Effects Section (Transitions based on screenMode)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(Color(0xFF14171E))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Top drag handle pill
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(36.dp)
                        .height(4.dp)
                        .background(Color(0xFF3F4654), RoundedCornerShape(2.dp))
                )

                Spacer(modifier = Modifier.height(12.dp))

                AnimatedContent(
                    targetState = screenMode,
                    transitionSpec = {
                    fadeIn(androidx.compose.animation.core.tween(120, delayMillis = 30)) togetherWith
                        fadeOut(androidx.compose.animation.core.tween(70))
                },
                    label = "effectsPanelTransition"
                ) { mode ->
                    when (mode) {
                        // Image 1 (empty) & Image 3 (applied list)
                        AppliedEffectsScreenMode.LIST -> {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                // Big prominent [ Add effect ] button (matching screenshots)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF262A35))
                                        .bouncyClickable(
                                            pressedScale = 0.96f,
                                            onClick = { screenMode = AppliedEffectsScreenMode.LIBRARY }
                                        )
                                        .testTag("add_effect_button"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Add effect",
                                        color = TextPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                if (appliedEffects.isEmpty()) {
                                    // Empty state message (Image 1 matching screenshot)
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 24.dp, horizontal = 20.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "See applied effects here, reorder them by dragging, and control their parameters",
                                            color = Color(0xFF8B92A2),
                                            fontSize = 13.sp,
                                            lineHeight = 19.sp,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                } else {
                                    // List of Applied Effects (Image 3 matching screenshot)
                                    LazyColumn(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(appliedEffects) { effect ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(48.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(Color(0xFF212530))
                                                    .padding(horizontal = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // Left dropdown arrow ▶ to open parameters
                                                Box(
                                                    modifier = Modifier
                                                        .size(32.dp)
                                                        .bouncyClickable(onClick = {
                                                            selectedEffectForEditing = effect
                                                            screenMode = AppliedEffectsScreenMode.PARAMETERS
                                                        })
                                                        .testTag("effect_dropdown_button_${effect.name}"),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.ArrowRight,
                                                        contentDescription = "Expand effect",
                                                        tint = IconActive,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                }

                                                Spacer(modifier = Modifier.width(6.dp))

                                                // Effect Name
                                                Text(
                                                    text = effect.name,
                                                    color = TextPrimary,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .bouncyClickable(onClick = {
                                                            selectedEffectForEditing = effect
                                                            screenMode = AppliedEffectsScreenMode.PARAMETERS
                                                        })
                                                )

                                                // Visibility Eye Toggle
                                                Box(
                                                    modifier = Modifier
                                                        .size(32.dp)
                                                        .bouncyClickable(onClick = {
                                                            effect.isEnabled = !effect.isEnabled
                                                        }),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = if (effect.isEnabled) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                                        contentDescription = "Toggle visibility",
                                                        tint = if (effect.isEnabled) IconActive else Color(0xFF6B7280),
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }

                                                Spacer(modifier = Modifier.width(8.dp))

                                                // Drag reorder handle (6 dots ⠿)
                                                Box(
                                                    modifier = Modifier.size(32.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Canvas(modifier = Modifier.size(14.dp)) {
                                                        val dotR = 1.3.dp.toPx()
                                                        val spacingX = 5.dp.toPx()
                                                        val spacingY = 4.5.dp.toPx()
                                                        for (row in 0..2) {
                                                            for (col in 0..1) {
                                                                drawCircle(
                                                                    color = Color(0xFF9CA3AF),
                                                                    radius = dotR,
                                                                    center = Offset(col * spacingX + 2.dp.toPx(), row * spacingY + 2.dp.toPx())
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Image 2: Effect Library / Picker
                        AppliedEffectsScreenMode.LIBRARY -> {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                // Category Pill Tabs: [Blur] [Background] [Mask & Edges]
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    categories.forEach { cat ->
                                        val isSelected = cat == selectedCategory
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (isSelected) Color(0xFFF3F4F6) else Color(0xFF232732))
                                                .bouncyClickable(onClick = { selectedCategory = cat })
                                                .padding(horizontal = 14.dp, vertical = 7.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = cat,
                                                color = if (isSelected) Color(0xFF111317) else Color(0xFFE5E7EB),
                                                fontSize = 13.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                // Grid of Effect Cards (matching Image 2)
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(4),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(currentPresets) { preset ->
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .bouncyClickable(onClick = {
                                                    val newEffect = ActiveAppliedEffect(
                                                        name = preset.name,
                                                        initialIntensity = preset.defaultIntensity,
                                                        secondaryName = preset.secondaryName,
                                                        initialSecondary = preset.defaultSecondary,
                                                        secondaryUnit = preset.secondaryUnit,
                                                        tertiaryName = preset.tertiaryName,
                                                        initialTertiary = preset.defaultTertiary,
                                                        tertiaryUnit = preset.tertiaryUnit
                                                    )
                                                    appliedEffects.add(newEffect)
                                                    selectedEffectForEditing = newEffect
                                                    onEffectApplied(preset.name)
                                                    // Go directly to applied effects list or parameter control
                                                    screenMode = AppliedEffectsScreenMode.LIST
                                                })
                                                .testTag("effect_preset_${preset.id}"),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            // Effect Card with checkerboard & 3D Rocket/Blur Graphic + Star Badge
                                            Box(
                                                modifier = Modifier
                                                    .size(62.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(Color(0xFF262C38))
                                                    .border(1.dp, Color(0xFF3B4150), RoundedCornerShape(10.dp)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                // Stylized Rocket / Graphic Canvas
                                                Canvas(modifier = Modifier.size(38.dp)) {
                                                    // Draw rocket body
                                                    val cX = size.width / 2f
                                                    val cY = size.height / 2f
                                                    drawCircle(
                                                        color = Color(0xFFFF5252),
                                                        radius = 12.dp.toPx(),
                                                        center = Offset(cX, cY)
                                                    )
                                                    drawCircle(
                                                        color = Color(0xFF00E5FF),
                                                        radius = 6.dp.toPx(),
                                                        center = Offset(cX, cY)
                                                    )
                                                }

                                                // Top-right Star badge if pro
                                                if (preset.isPro) {
                                                    Box(
                                                        modifier = Modifier
                                                            .align(Alignment.TopEnd)
                                                            .padding(3.dp)
                                                            .size(14.dp)
                                                            .background(Color(0xFF00B0FF), CircleShape),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Star,
                                                            contentDescription = null,
                                                            tint = Color.White,
                                                            modifier = Modifier.size(10.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(4.dp))

                                            Text(
                                                text = preset.name,
                                                color = TextPrimary,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium,
                                                textAlign = TextAlign.Center,
                                                maxLines = 2,
                                                lineHeight = 12.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Image 4: Parameter Control Screen (Sliders for Intensity, Rotation, Edge Hardness)
                        AppliedEffectsScreenMode.PARAMETERS -> {
                            val effect = selectedEffectForEditing
                            if (effect != null) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    // 1. Intensity Slider
                                    RulerDialSlider(
                                        label = "Intensity",
                                        value = effect.intensity,
                                        onValueChange = { effect.intensity = it },
                                        min = 0f,
                                        max = 100f,
                                        unit = "%",
                                        labelWidth = 105.dp
                                    )

                                    Spacer(modifier = Modifier.height(6.dp))

                                    // 2. Secondary Slider (e.g. "Rotation" with 45°)
                                    RulerDialSlider(
                                        label = effect.secondaryName,
                                        value = effect.secondaryValue,
                                        onValueChange = { effect.secondaryValue = it },
                                        min = 0f,
                                        max = if (effect.secondaryUnit == "°") 360f else 100f,
                                        unit = effect.secondaryUnit,
                                        labelWidth = 105.dp
                                    )

                                    Spacer(modifier = Modifier.height(6.dp))

                                    // 3. Tertiary Slider (e.g. "Edge Hardness" with 0%)
                                    RulerDialSlider(
                                        label = effect.tertiaryName,
                                        value = effect.tertiaryValue,
                                        onValueChange = { effect.tertiaryValue = it },
                                        min = 0f,
                                        max = 100f,
                                        unit = effect.tertiaryUnit,
                                        labelWidth = 105.dp
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }
}
