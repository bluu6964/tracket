package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AnimProp
import com.example.model.Easing
import com.example.model.at
import com.example.model.indexOnTime
import com.example.model.segmentIndexAt
import com.example.model.FILL_GRADIENT
import com.example.model.FILL_IMAGE
import com.example.model.IMG_FILL
import com.example.model.IMG_FIT
import com.example.model.IMG_STRETCH
import com.example.model.FILL_NONE
import com.example.model.FILL_SOLID
import com.example.model.GRAD_LINEAR
import com.example.model.GRAD_RADIAL
import com.example.model.GRAD_SWEEP
import com.example.model.TimelineClip
import com.example.ui.theme.AmBase
import com.example.ui.theme.AmHairline
import com.example.ui.theme.AmMint
import com.example.ui.theme.AmPanel
import com.example.ui.theme.AmPanelHigh
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.amGlassBrush
import com.example.ui.util.bouncyClickable
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// B/W language: the sheet palette points at the shared monochrome design tokens
// (flat panel, raised chip tone, recessed base, white accent). Same geometry and layout.
private val CePanelBg = AmPanel
private val CeTabBg = AmPanelHigh
private val CeInnerBg = AmBase
private val CeAccent = AmMint

private val CeSwatches = listOf(
    0xFFFF4444, 0xFFF5B342, 0xFFFAE764, 0xFF1FD91F, 0xFF18E2E2, 0xFF3F4DF2, 0xFFD73FDA,
    0xFFFFFFFF, 0xFFBDBDBD, 0xFF8A8A8A, 0xFF474747, 0xFF000000, 0xFF5BB5D9, 0xFF96C99B
)

/**
 * "Color" editor of a shape or text layer (Alight Motion style): four tabs on top
 * (no fill / solid colour / gradient / image fill) and the matching editor below.
 * Every change is applied live to the layer, so the canvas above shows it straight away.
 */
@Composable
fun ColorEditSheet(
    /** The stored layer (with its keyframes); the sheet shows it as it looks at [currentPlayheadMs]. */
    rawClip: TimelineClip,
    onUpdate: ((TimelineClip) -> TimelineClip) -> Unit,
    onEyedropper: ((Long) -> Unit) -> Unit,
    /** Opens the device picture picker; the caller stores the chosen picture on the layer. */
    onPickImage: () -> Unit,
    modifier: Modifier = Modifier,
    fullHeight: Dp? = null,
    /** Back button of the top card (same as the Transform panel); null = no back button. */
    onBack: (() -> Unit)? = null,
    currentPlayheadMs: Long = 0L,
    /** Diamond: add / remove a keyframe of these colour properties at the playhead. */
    onToggleKeyframes: (List<AnimProp>) -> Unit = {},
    /** Easing editor: a change is about to start (one undo step) / the curve of the segment starting at the keyframe time changed. */
    onEasingBegin: () -> Unit = {},
    onEasingChange: (AnimProp, Long, Easing) -> Unit = { _, _, _ -> }
) {
    // The layer as it looks at the playhead (colour keyframes applied).
    val clip = rawClip.at(currentPlayheadMs)
    // -1 = gradient overview, 0 / 1 = editing the colour of that gradient stop.
    var editingStop by remember(clip.id) { mutableIntStateOf(-1) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (fullHeight != null) Modifier.height(fullHeight) else Modifier)
            .glassSurface(
                RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                fill = amGlassBrush()
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .testTag("color_edit_sheet")
    ) {
        // ---- Top card: back + live value + keyframe diamond + easing (same as the Transform panel) ----
        val headerText = when (clip.fillMode) {
            FILL_NONE -> "No fill"
            FILL_GRADIENT -> when (editingStop) {
                0 -> hexLabel(clip.gradColor1)
                1 -> hexLabel(clip.gradColor2)
                else -> "Gradient"
            }
            FILL_IMAGE -> "Image"
            else -> hexLabel(clip.textColor)
        }
        // Colour properties that are open right now (none = nothing to animate).
        val curProps: List<AnimProp> = when (clip.fillMode) {
            FILL_SOLID -> listOf(AnimProp.FILL_COLOR)
            FILL_GRADIENT -> when (editingStop) {
                0 -> listOf(AnimProp.GRAD_COLOR_1)
                1 -> listOf(AnimProp.GRAD_COLOR_2)
                else -> listOf(AnimProp.GRAD_COLOR_1, AnimProp.GRAD_COLOR_2)
            }
            else -> emptyList()
        }
        val relMs = currentPlayheadMs - rawClip.startMs
        val insideClip = currentPlayheadMs >= rawClip.startMs && currentPlayheadMs <= rawClip.endMs
        val onKeyframe = curProps.any { rawClip.keyframes[it].orEmpty().indexOnTime(relMs) >= 0 }
        val hasKeyframes = curProps.any { rawClip.keyframes[it].orEmpty().isNotEmpty() }
        // Easing works between two keyframes; with two properties (gradient overview) the first one that has a segment is shown.
        val easingProp = curProps.firstOrNull { rawClip.keyframes[it].orEmpty().segmentIndexAt(relMs) >= 0 }
        val kfList = easingProp?.let { rawClip.keyframes[it].orEmpty() }.orEmpty()
        val segIdx = if (easingProp != null) kfList.segmentIndexAt(relMs) else -1
        val easingEnabled = segIdx >= 0
        var showEasing by remember { mutableStateOf(false) }
        LaunchedEffect(easingEnabled, editingStop, clip.fillMode) { if (!easingEnabled) showEasing = false }
        LaunchedEffect(editingStop, clip.fillMode) { showEasing = false }
        val segStart = if (segIdx >= 0) kfList[segIdx] else null
        val segEnd = if (segIdx >= 0) kfList[segIdx + 1] else null

        TransformValueHeader(
            valueText = headerText,
            onBack = onBack,
            keyframeEnabled = insideClip && curProps.isNotEmpty(),
            hasKeyframes = hasKeyframes,
            onKeyframeNow = onKeyframe,
            onKeyframe = { onToggleKeyframes(curProps) },
            easingEnabled = easingEnabled,
            easingOpen = showEasing && easingEnabled,
            easing = segStart?.easing,
            onEasing = { if (easingEnabled) showEasing = !showEasing }
        )

        Spacer(Modifier.height(4.dp))

        if (showEasing && segStart != null && segEnd != null) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                EasingEditor(
                    easing = segStart.easing,
                    progress = ((relMs - segStart.timeMs).toFloat() /
                        (segEnd.timeMs - segStart.timeMs).coerceAtLeast(1L)).coerceIn(0f, 1f),
                    onBegin = onEasingBegin,
                    onChange = { e -> curProps.forEach { onEasingChange(it, segStart.timeMs, e) } }
                )
            }
        } else
        when (clip.fillMode) {
            FILL_NONE -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No fill — the layer shows no colour.\nPick Solid or Gradient to color it again.",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }

            FILL_GRADIENT -> {
                if (editingStop < 0) {
                    GradientOverview(
                        clip = clip,
                        onUpdate = onUpdate,
                        onEditStop = { editingStop = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                } else {
                    val stop = editingStop
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(CeTabBg)
                            .bouncyClickable(pressedScale = 0.92f, onClick = { editingStop = -1 })
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("color_gradient_back"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack, "Back to gradient",
                            tint = TextPrimary, modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (stop == 0) "Gradient · start color" else "Gradient · end color",
                            color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    ColorPickerPanel(
                        color = if (stop == 0) clip.gradColor1 else clip.gradColor2,
                        onColor = { c ->
                            onUpdate {
                                if (stop == 0) it.copy(gradColor1 = c) else it.copy(gradColor2 = c)
                            }
                        },
                        onEyedropper = onEyedropper,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }

            FILL_IMAGE -> ImageFillPanel(
                clip = clip,
                onUpdate = onUpdate,
                onPickImage = onPickImage,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            else -> ColorPickerPanel(
                color = clip.textColor,
                onColor = { c -> onUpdate { it.copy(textColor = c, fillMode = FILL_SOLID) } },
                onEyedropper = onEyedropper,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }

        Spacer(Modifier.height(8.dp))

        // ---- Bottom card: none / solid / gradient / image (smaller) ------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(CeTabBg)
        ) {
            FillTab(Icons.Default.Block, "No fill", clip.fillMode == FILL_NONE, "color_tab_none") {
                onUpdate { it.copy(fillMode = FILL_NONE) }
            }
            FillTab(Icons.Default.FormatColorFill, "Solid color", clip.fillMode == FILL_SOLID, "color_tab_solid") {
                onUpdate { it.copy(fillMode = FILL_SOLID) }
            }
            FillTab(Icons.Default.Gradient, "Gradient", clip.fillMode == FILL_GRADIENT, "color_tab_gradient") {
                editingStop = -1
                onUpdate { it.copy(fillMode = FILL_GRADIENT) }
            }
            FillTab(Icons.Default.Image, "Image fill", clip.fillMode == FILL_IMAGE, "color_tab_image") {
                if (clip.fillImageUri == null) onPickImage()
                else onUpdate { it.copy(fillMode = FILL_IMAGE) }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.FillTab(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(if (selected) CeInnerBg else Color.Transparent)
            .bouncyClickable(pressedScale = 0.94f, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon, description,
            tint = if (selected) CeAccent else Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

// =====================================================================================
// Gradient
// =====================================================================================

@Composable
private fun GradientOverview(
    clip: TimelineClip,
    onUpdate: ((TimelineClip) -> TimelineClip) -> Unit,
    onEditStop: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val c1 = Color(clip.gradColor1)
    val c2 = Color(clip.gradColor2)

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Gradient type: linear / radial / sweep
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(CeTabBg)
        ) {
            listOf(GRAD_LINEAR to "Linear", GRAD_RADIAL to "Radial", GRAD_SWEEP to "Sweep").forEach { (type, name) ->
                val selected = clip.gradType == type
                Box(
                    modifier = Modifier
                        .width(72.dp)
                        .height(52.dp)
                        .background(if (selected) CeInnerBg else Color.Transparent)
                        .bouncyClickable(pressedScale = 0.92f) { onUpdate { it.copy(gradType = type) } }
                        .testTag("gradient_type_$name"),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(
                        modifier = Modifier
                            .size(34.dp)
                            .border(
                                2.dp,
                                if (selected) CeAccent else Color.White,
                                RoundedCornerShape(7.dp)
                            )
                            .padding(2.dp)
                    ) {
                        drawRoundRect(
                            brush = LayerFillBrush { w, h ->
                                com.example.engine.buildGradientShader(
                                    clip.copy(
                                        gradType = type,
                                        gradX1 = if (type == GRAD_LINEAR) 0f else 0.5f, gradY1 = 0.5f,
                                        gradX2 = 1f, gradY2 = 0.5f
                                    ), w, h
                                )
                            },
                            cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(26.dp))

        // Gradient bar with its two colour stops (tap a stop to change its colour)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(3.dp))
            ) {
                drawChecker(Size(size.width, size.height))
                drawRect(Brush.horizontalGradient(listOf(c1, c2)))
            }
            StopHandle(c1, "color_stop_start", Modifier.align(Alignment.CenterStart)) { onEditStop(0) }
            StopHandle(c2, "color_stop_end", Modifier.align(Alignment.CenterEnd)) { onEditStop(1) }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "Tap a color box to change it.\nDrag the two points on the canvas to move the gradient.",
            color = TextSecondary, fontSize = 12.sp, textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(CeTabBg)
                .bouncyClickable(pressedScale = 0.94f) {
                    onUpdate { it.copy(gradColor1 = it.gradColor2, gradColor2 = it.gradColor1) }
                }
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("gradient_swap"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.SwapHoriz, "Swap colors", tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Swap colors", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun StopHandle(color: Color, tag: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .size(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .padding(3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF8A8A8A))
            .bouncyClickable(pressedScale = 0.9f, onClick = onClick)
            .testTag(tag)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(color)
        )
    }
}

// =====================================================================================
// Solid colour picker: wheel view / swatch view / eyedropper
// =====================================================================================

@Composable
private fun ColorPickerPanel(
    color: Long,
    onColor: (Long) -> Unit,
    onEyedropper: ((Long) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    var view by remember { mutableIntStateOf(0) } // 0 = wheel, 1 = swatches
    val onColorNow by rememberUpdatedState(onColor)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(CeInnerBg)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            if (view == 0) {
                WheelPicker(color = color, onColor = { onColorNow(it) }, modifier = Modifier.fillMaxSize())
            } else {
                SwatchGrid(color = color, onColor = { onColorNow(it) }, modifier = Modifier.fillMaxSize())
            }
        }
        Column(
            modifier = Modifier
                .width(58.dp)
                .fillMaxHeight()
        ) {
            RailButton(Icons.Default.Colorize, "Pick from canvas", false, "color_eyedropper") {
                onEyedropper { picked -> onColorNow(picked) }
            }
            RailWheelButton(view == 0) { view = 0 }
            RailButton(Icons.Default.Apps, "Swatches", view == 1, "color_view_swatches") { view = 1 }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.RailButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) AmMint.copy(alpha = 0.16f) else CeTabBg)
            .bouncyClickable(pressedScale = 0.92f, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon, description,
            tint = if (selected) CeAccent else Color.White,
            modifier = Modifier.size(26.dp)
        )
    }
}

@Composable
private fun SwatchGrid(color: Long, onColor: (Long) -> Unit, modifier: Modifier = Modifier) {
    val rgb = color and 0xFFFFFFL
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(8.dp)
    ) {
        // Header: current colour with its hex code and opacity
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(10.dp))
        ) {
            Canvas(Modifier.fillMaxSize()) { drawChecker(size) }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(color)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    hexLabel(color),
                    color = contrastOn(Color(color)),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        CeSwatches.chunked(7).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                row.forEach { c ->
                    val selected = (c and 0xFFFFFFL) == rgb
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Color(c))
                            .border(
                                if (selected) 2.5.dp else 1.dp,
                                if (selected) Color.White else Color.White.copy(alpha = 0.12f),
                                RoundedCornerShape(9.dp)
                            )
                            .bouncyClickable(pressedScale = 0.85f) { onColor(c) }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun WheelPicker(color: Long, onColor: (Long) -> Unit, modifier: Modifier = Modifier) {
    val init = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(color.toInt(), it) }
    }
    var hue by remember { mutableFloatStateOf(init[0]) }
    var sat by remember { mutableFloatStateOf(init[1]) }
    var value by remember { mutableFloatStateOf(init[2]) }
    var alpha by remember { mutableFloatStateOf(((color shr 24) and 0xFF).toInt() / 255f) }
    var lastEmitted by remember { mutableStateOf(Long.MIN_VALUE) }
    val onColorNow by rememberUpdatedState(onColor)

    // The colour was changed from outside (swatch, eyedropper): follow it.
    LaunchedEffect(color) {
        if (color != lastEmitted) {
            val a = FloatArray(3)
            android.graphics.Color.colorToHSV(color.toInt(), a)
            hue = a[0]; sat = a[1]; value = a[2]
            alpha = ((color shr 24) and 0xFF).toInt() / 255f
        }
    }

    fun emit() {
        val argb = android.graphics.Color.HSVToColor(
            (alpha * 255f).roundToInt().coerceIn(0, 255),
            floatArrayOf(hue.coerceIn(0f, 359.99f), sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
        )
        val c = argb.toLong() and 0xFFFFFFFFL
        lastEmitted = c
        onColorNow(c)
    }

    val current = Color(
        android.graphics.Color.HSVToColor(
            (alpha * 255f).roundToInt().coerceIn(0, 255),
            floatArrayOf(hue.coerceIn(0f, 359.99f), sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
        )
    )
    val hueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue.coerceIn(0f, 359.99f), 1f, 1f)))

    BoxWithConstraints(modifier = modifier.padding(10.dp)) {
        // Two squares side by side (wheel + saturation/value) with the opacity bar underneath.
        val barH = 28.dp
        val gap = 12.dp
        val s = minOf((maxWidth - gap) / 2f, maxHeight - barH - gap).coerceAtLeast(80.dp)

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                // ---- Hue ring with the hex code in the middle ----
                Box(modifier = Modifier.size(s), contentAlignment = Alignment.Center) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("color_hue_ring")
                            .pointerInput(Unit) {
                                detectTapGestures { o ->
                                    val ang = atan2(o.y - size.height / 2f, o.x - size.width / 2f)
                                    hue = ((Math.toDegrees(ang.toDouble()) + 360.0) % 360.0).toFloat()
                                    emit()
                                }
                            }
                            .pointerInput(Unit) {
                                detectDragGestures { change, _ ->
                                    change.consume()
                                    val o = change.position
                                    val ang = atan2(o.y - size.height / 2f, o.x - size.width / 2f)
                                    hue = ((Math.toDegrees(ang.toDouble()) + 360.0) % 360.0).toFloat()
                                    emit()
                                }
                            }
                    ) {
                        val ringW = 18.dp.toPx()
                        val r = size.minDimension / 2f - ringW / 2f
                        drawCircle(
                            brush = Brush.sweepGradient(
                                (0..6).map {
                                    Color(android.graphics.Color.HSVToColor(floatArrayOf((it * 60f).coerceAtMost(359.9f), 1f, 1f)))
                                }
                            ),
                            radius = r,
                            style = Stroke(ringW)
                        )
                        val rad = Math.toRadians(hue.toDouble())
                        val p = Offset(
                            size.width / 2f + r * cos(rad).toFloat(),
                            size.height / 2f + r * sin(rad).toFloat()
                        )
                        drawCircle(hueColor, radius = ringW / 2f + 2.dp.toPx(), center = p)
                        drawCircle(Color.White, radius = ringW / 2f + 2.dp.toPx(), center = p, style = Stroke(2.5.dp.toPx()))
                    }
                    Box(
                        modifier = Modifier
                            .size(s - 44.dp)
                            .clip(CircleShape)
                    ) {
                        Canvas(Modifier.fillMaxSize()) { drawChecker(size) }
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(current),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                hexLabel(current.toLongArgb(), twoLines = true),
                                color = contrastOn(current),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // ---- Saturation (x) / value (y) square ----
                Canvas(
                    modifier = Modifier
                        .size(s)
                        .clip(RoundedCornerShape(10.dp))
                        .testTag("color_sv_square")
                        .pointerInput(Unit) {
                            detectTapGestures { o ->
                                sat = (o.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (o.y / size.height).coerceIn(0f, 1f)
                                emit()
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                sat = (change.position.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                                emit()
                            }
                        }
                ) {
                    drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val p = Offset(sat * size.width, (1f - value) * size.height)
                    drawCircle(Color.White, radius = 9.dp.toPx(), center = p, style = Stroke(2.dp.toPx()))
                    drawCircle(Color.Black.copy(alpha = 0.4f), radius = 11.dp.toPx(), center = p, style = Stroke(1.dp.toPx()))
                }
            }

            Spacer(Modifier.height(gap))

            // ---- Opacity bar ----
            Canvas(
                modifier = Modifier
                    .width(s * 2 + gap)
                    .height(barH)
                    .clip(RoundedCornerShape(barH / 2))
                    .testTag("color_alpha_bar")
                    .pointerInput(Unit) {
                        detectTapGestures { o ->
                            alpha = (o.x / size.width).coerceIn(0f, 1f)
                            emit()
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            alpha = (change.position.x / size.width).coerceIn(0f, 1f)
                            emit()
                        }
                    }
            ) {
                drawChecker(size)
                val solid = current.copy(alpha = 1f)
                drawRect(Brush.horizontalGradient(listOf(solid.copy(alpha = 0f), solid)))
                val r = size.height / 2f
                val cx = (alpha * size.width).coerceIn(r, size.width - r)
                drawCircle(Color.White, radius = r - 1.dp.toPx(), center = Offset(cx, r), style = Stroke(3.dp.toPx()))
            }
        }
    }
}

// =====================================================================================
// Helpers
// =====================================================================================

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawChecker(area: Size) {
    val cell = 7.dp.toPx()
    var yi = 0
    var y = 0f
    while (y < area.height) {
        var xi = 0
        var x = 0f
        while (x < area.width) {
            drawRect(
                if ((xi + yi) % 2 == 0) Color(0xFF8A8A8A) else Color(0xFFCFCFCF),
                topLeft = Offset(x, y),
                size = Size(cell, cell)
            )
            x += cell; xi++
        }
        y += cell; yi++
    }
}

private fun Color.toLongArgb(): Long = this.toArgb().toLong() and 0xFFFFFFFFL

private fun hexLabel(argb: Long, twoLines: Boolean = false): String {
    val rgb = (argb and 0xFFFFFFL).toInt()
    val pct = (((argb shr 24) and 0xFF).toInt() / 255f * 100f).roundToInt()
    val sep = if (twoLines) "\n" else " "
    return String.format(Locale.US, "#%06X%s(%d%%)", rgb, sep, pct)
}

private fun contrastOn(c: Color): Color {
    // Over a see-through colour the checkerboard shows, so decide mostly on the colour itself.
    val lum = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
    return if (lum > 0.62f && c.alpha > 0.5f) Color.Black else Color.White
}

/** "Color wheel" button: a small hue ring (so it no longer looks like the swatches palette). */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.RailWheelButton(selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) AmMint.copy(alpha = 0.16f) else CeTabBg)
            .bouncyClickable(pressedScale = 0.92f, onClick = onClick)
            .testTag("color_view_wheel"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(28.dp)) {
            val w = 6.dp.toPx()
            drawCircle(
                brush = Brush.sweepGradient(
                    (0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf((it * 60f).coerceAtMost(359.9f), 1f, 1f))) }
                ),
                radius = size.minDimension / 2f - w / 2f,
                style = Stroke(w)
            )
            if (selected) drawCircle(CeAccent, radius = 3.dp.toPx())
        }
    }
}

/** Image fill: pick a picture from the device and choose Fill / Fit / Stretch. */
@Composable
private fun ImageFillPanel(
    clip: TimelineClip,
    onUpdate: ((TimelineClip) -> TimelineClip) -> Unit,
    onPickImage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bmp = clip.fillImageUri?.let { com.example.engine.FillImages.peek(it) }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CeInnerBg)
                .border(1.dp, AmHairline, RoundedCornerShape(12.dp))
                .bouncyClickable(pressedScale = 0.95f, onClick = onPickImage)
                .testTag("fill_image_preview"),
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Fill picture",
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Default.AddPhotoAlternate, "Choose picture", tint = Color.White, modifier = Modifier.size(40.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(CeTabBg)
                .bouncyClickable(pressedScale = 0.94f, onClick = onPickImage)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("fill_image_choose"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.AddPhotoAlternate, "Choose picture", tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (clip.fillImageUri == null) "Choose picture" else "Change picture",
                color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(CeTabBg)
        ) {
            listOf(
                Triple(IMG_FILL, "Fill", Icons.Default.Fullscreen),
                Triple(IMG_FIT, "Fit", Icons.Default.FitScreen),
                Triple(IMG_STRETCH, "Stretch", Icons.Default.OpenInFull)
            ).forEach { (mode, name, icon) ->
                val selected = clip.fillImageMode == mode
                Column(
                    modifier = Modifier
                        .width(86.dp)
                        .height(60.dp)
                        .background(if (selected) CeInnerBg else Color.Transparent)
                        .bouncyClickable(pressedScale = 0.92f) { onUpdate { it.copy(fillImageMode = mode) } }
                        .testTag("fill_image_$name"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(icon, name, tint = if (selected) CeAccent else Color.White, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(name, color = if (selected) CeAccent else TextPrimary, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}
