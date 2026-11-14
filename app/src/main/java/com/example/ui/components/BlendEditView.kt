package com.example.ui.components
import androidx.compose.ui.graphics.RectangleShape

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AnimProp
import com.example.model.BlendKind
import com.example.model.BlendModes
import com.example.model.BlendSpec
import com.example.model.Easing
import com.example.model.TimelineClip
import com.example.model.indexOnTime
import com.example.model.segmentIndexAt
import com.example.ui.theme.AmInk
import com.example.ui.theme.AmPanel
import com.example.ui.theme.AmPanelHigh
import com.example.ui.theme.AmTextDim
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.amCyanVioletBrush
import com.example.ui.theme.amGlassBrush
import com.example.ui.util.bouncyClickable
import com.example.ui.util.composeBlendMode
import com.example.ui.util.matteFilter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// Redesign Stage 2: flat greys now point at the shared design tokens (panel / raised chip tone).
private val BePanelBg = AmPanel
private val BeButtonBg = AmPanelHigh

/**
 * "Blending" editor of a layer (Alight Motion style): reset button, opacity slider with its
 * value, a row of blend-mode thumbnails of the chosen group, and the group tabs
 * (Matte / Darken / Brighten / Contrast / Difference / Color) at the bottom.
 * Every change is applied live to the layer, so the canvas above shows it straight away.
 */
@Composable
fun BlendEditSheet(
    /** The layer as it looks at the playhead (keyframes applied). */
    clip: TimelineClip,
    onUpdate: ((TimelineClip) -> TimelineClip) -> Unit,
    modifier: Modifier = Modifier,
    /** Back button of the top card (same as the Transform panel); null = no back button. */
    onBack: (() -> Unit)? = null,
    /** The stored layer (with its keyframes), used for the diamond and the easing curve. */
    rawClip: TimelineClip = clip,
    currentPlayheadMs: Long = 0L,
    /** Diamond: add / remove an opacity keyframe at the playhead. */
    onToggleKeyframes: (List<AnimProp>) -> Unit = {},
    onEasingBegin: () -> Unit = {},
    onEasingChange: (AnimProp, Long, Easing) -> Unit = { _, _, _ -> },
    /** Reset also drops the opacity keyframes; default = plain update. */
    onReset: (() -> Unit)? = null
) {
    val opacityProps = remember { listOf(AnimProp.OPACITY) }
    val relMs = currentPlayheadMs - rawClip.startMs
    val insideClip = currentPlayheadMs >= rawClip.startMs && currentPlayheadMs <= rawClip.endMs
    val opKeys = rawClip.keyframes[AnimProp.OPACITY].orEmpty()
    val onKeyframeNow = opKeys.indexOnTime(relMs) >= 0
    val hasKeyframes = opKeys.isNotEmpty()
    val segIdx = opKeys.segmentIndexAt(relMs)
    val easingEnabled = segIdx >= 0
    var showEasing by remember { mutableStateOf(false) }
    LaunchedEffect(easingEnabled) { if (!easingEnabled) showEasing = false }
    val segStart = if (segIdx >= 0) opKeys[segIdx] else null
    val segEnd = if (segIdx >= 0) opKeys[segIdx + 1] else null

    var category by remember(clip.id) {
        mutableStateOf(BlendModes.find(clip.blendMode).category)
    }
    val current = BlendModes.find(clip.blendMode)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassSurface(
                RectangleShape,
                fill = amGlassBrush()
            )
            // swallow touches so nothing underneath can be hit
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .padding(top = 12.dp, bottom = 4.dp)
            .testTag("blend_edit_sheet")
    ) {
        // ---- Top card: back + live value + keyframe / easing (same as the Transform panel) ----
        Box(Modifier.padding(horizontal = 12.dp)) {
            TransformValueHeader(
                valueText = "${clip.opacity.roundToInt()}% · ${current.name}",
                onBack = onBack,
                keyframeEnabled = insideClip,
                hasKeyframes = hasKeyframes,
                onKeyframeNow = onKeyframeNow,
                onKeyframe = { onToggleKeyframes(opacityProps) },
                easingEnabled = easingEnabled,
                easingOpen = showEasing && easingEnabled,
                easing = segStart?.easing,
                onEasing = { if (easingEnabled) showEasing = !showEasing }
            )
        }
        Spacer(Modifier.height(4.dp))

        Column(
            modifier = Modifier
                .weight(1f, fill = true)
                .verticalScroll(rememberScrollState())
        ) {
        // ---- Reset ---------------------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Box(
                modifier = Modifier
                    .size(width = 56.dp, height = 46.dp)
                    .bouncyClickable(pressedScale = 0.92f) {
                        (onReset ?: { onUpdate { it.copy(opacity = 100f, blendMode = BlendModes.NORMAL) } })()
                        category = BlendModes.find(BlendModes.NORMAL).category
                    }
                    .testTag("blend_reset"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Refresh, "Reset",
                    tint = TextSecondary, modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---- Opacity -------------------------------------------------------------------
        if (showEasing && segStart != null && segEnd != null) {
            // Easing curve of the segment the playhead is on (replaces the slider while open).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .padding(horizontal = 12.dp)
            ) {
                EasingEditor(
                    easing = segStart.easing,
                    progress = ((relMs - segStart.timeMs).toFloat() /
                        (segEnd.timeMs - segStart.timeMs).coerceAtLeast(1L)).coerceIn(0f, 1f),
                    onBegin = onEasingBegin,
                    onChange = { e -> onEasingChange(AnimProp.OPACITY, segStart.timeMs, e) }
                )
            }
        } else
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("opacity", color = AmTextDim, fontSize = 15.sp)
            Spacer(Modifier.width(10.dp))
            OpacitySlider(
                value = clip.opacity,
                onValueChange = { v -> onUpdate { it.copy(opacity = v) } },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("blend_opacity_slider")
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(width = 76.dp, height = 50.dp),
                contentAlignment = Alignment.Center
            ) {
                FlatPill(Modifier.matchParentSize())
                Text(
                    "${clip.opacity.roundToInt()}",
                    color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("blend_opacity_value")
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- Blend mode thumbnails of the chosen group ----------------------------------
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("blend_modes_row"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(BlendModes.inCategory(category), key = { it.name }) { spec ->
                val selected = spec.name == current.name
                Column(
                    modifier = Modifier
                        .width(78.dp)
                        .bouncyClickable(pressedScale = 0.94f) {
                            onUpdate { it.copy(blendMode = spec.name) }
                        }
                        .testTag("blend_mode_${spec.name}"),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    BlendThumb(
                        spec = spec,
                        selected = selected,
                        modifier = Modifier.size(78.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        spec.name,
                        color = if (selected) TextPrimary else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }

        }

        // ---- Group tabs ---------------------------------------------------------------
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(44.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(BlendModes.categories, key = { it }) { name ->
                val on = name == category
                Box(
                    modifier = Modifier
                        .height(44.dp)
                        .bouncyClickable(pressedScale = 0.94f) { category = name }
                        .testTag("blend_tab_$name"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        name,
                        color = if (on) TextPrimary else TextSecondary,
                        fontSize = 17.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 14.dp)
                    )
                }
            }
        }
    }
}

// =====================================================================================
// Opacity slider (thin lime line, round thumb)
// =====================================================================================

@Composable
private fun OpacitySlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestChange by rememberUpdatedState(onValueChange)
    Canvas(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val thumbR = 11.dp.toPx()
                fun toValue(x: Float): Float {
                    val span = (size.width - 2f * thumbR).coerceAtLeast(1f)
                    return (((x - thumbR) / span).coerceIn(0f, 1f) * 100f).roundToInt().toFloat()
                }
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                latestChange(toValue(down.position.x))
                drag(down.id) { change ->
                    latestChange(toValue(change.position.x))
                    change.consume()
                }
            }
        }
    ) {
        val thumbR = 11.dp.toPx()
        val y = size.height / 2f
        val x = thumbR + (size.width - 2f * thumbR) * (value / 100f).coerceIn(0f, 1f)
        // B/W language: a flat white track line and a flat white thumb disc.
        // Geometry (thumb radius, span math) is untouched.
        drawLine(
            color = Color.White,
            start = Offset(thumbR, y), end = Offset(size.width - thumbR, y),
            strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round
        )
        drawCircle(color = Color.White, radius = thumbR, center = Offset(x, y))
        drawCircle(color = AmInk, radius = thumbR * 0.35f, center = Offset(x, y))
    }
}

// =====================================================================================
// Blend-mode thumbnail: a striped picture with a flower blended on top
// =====================================================================================

@Composable
private fun BlendThumb(spec: BlendSpec, selected: Boolean, modifier: Modifier = Modifier) {
    val shape = RectangleShape
    Box(
        modifier = modifier
            .clip(shape)
            .background(BeButtonBg)
            .then(if (selected) Modifier.border(2.dp, amCyanVioletBrush(), shape) else Modifier)
            .padding(if (selected) 2.dp else 0.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        ) {
            drawBackdrop()
            drawIntoCanvas { canvas ->
                val paint = Paint()
                if (spec.kind == BlendKind.MATTE) {
                    paint.blendMode = BlendMode.DstIn
                    paint.colorFilter = matteFilter(spec, 1f)
                } else {
                    paint.blendMode = composeBlendMode(spec)
                }
                canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
                drawFlower()
                canvas.restore()
            }
        }
    }
}

private fun DrawScope.drawBackdrop() {
    drawRect(Color(0xFF2FB9E8))
    val step = size.height / 11f
    var y = 0f
    var i = 0
    while (y < size.height) {
        if (i % 2 == 0) {
            drawRect(Color(0xFF63D8FF), Offset(0f, y), Size(size.width, step))
        }
        y += step
        i++
    }
}

private fun DrawScope.drawFlower() {
    val c = center
    val r = size.minDimension * 0.42f
    val petal = Color(0xFFEF4B2B)
    for (k in 0 until 8) {
        val a = k * PI / 4.0
        drawCircle(
            color = petal, radius = r * 0.30f,
            center = Offset(c.x + (cos(a) * r * 0.64f).toFloat(), c.y + (sin(a) * r * 0.64f).toFloat())
        )
    }
    drawCircle(color = petal, radius = r * 0.66f, center = c)
    drawCircle(color = Color(0xFFFFC98B), radius = r * 0.40f, center = c)
    drawCircle(color = Color(0xFF3A2A25), radius = r * 0.045f, center = Offset(c.x - r * 0.13f, c.y - r * 0.05f))
    drawCircle(color = Color(0xFF3A2A25), radius = r * 0.045f, center = Offset(c.x + r * 0.13f, c.y - r * 0.05f))
    drawCircle(color = Color(0xFFE5685A), radius = r * 0.07f, center = Offset(c.x, c.y + r * 0.08f))
}
