package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.withTransform
import com.example.model.LayerType
import com.example.model.at
import com.example.model.withClipAnim
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ANIM_MIN_MS
import com.example.model.AnimDir
import com.example.model.AnimPreset
import com.example.model.AnimPresets
import com.example.model.AnimSlot
import com.example.model.ClipAnim
import com.example.model.Easing
import com.example.model.TimelineClip
import com.example.model.maxAnimMs
import com.example.ui.theme.AmCyan
import com.example.ui.theme.AmHairline
import com.example.ui.theme.EditorSurface
import com.example.ui.theme.EditorSurfaceVariant
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.amCyanVioletBrush
import com.example.ui.theme.amGlassBrush
import com.example.ui.util.bouncyClickable
import kotlin.math.roundToInt

private const val MAX_SLIDER_MS = 4000L

private fun iconFor(presetId: String): ImageVector = when (presetId) {
    "fade" -> Icons.Default.Opacity
    "slide" -> Icons.Default.SwapHoriz
    "slide_fade" -> Icons.Default.Gradient
    "bounce" -> Icons.Default.SwapVert
    "zoom" -> Icons.Default.ZoomIn
    "zoom_fade" -> Icons.Default.ZoomOutMap
    "zoom_out" -> Icons.Default.ZoomOut
    "pop" -> Icons.Default.Star
    "spin" -> Icons.Default.RotateRight
    "spin_zoom" -> Icons.Default.Autorenew
    "wipe" -> Icons.Default.Crop
    else -> Icons.Default.Block
}

private enum class AnimSection(val label: String) { OPTIONS("Options"), CURVE("Curve") }

/**
 * Animations panel. It is a normal editor panel (same place and look as Transform / Crop / Color:
 * the timescale and the selected layer stay visible above it), NOT a big bottom sheet.
 *
 * In / Out tabs, a row of animations (tapping one applies it at once and plays it), then two sections:
 *  - Options: direction and length.
 *  - Curve: a small shape that plays the animation with the chosen curve (so you SEE how it moves),
 *    the draggable curve graph (a line shows where the shape is in time) and ready-made curves.
 *
 * [onApply] gets the preset (null = remove the animation of that slot). [onPreview] plays the
 * animation of that slot on the real layer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipAnimationsPanel(
    clip: TimelineClip?,
    aspectRatio: Float = 16f / 9f,
    onApply: (slot: AnimSlot, preset: AnimPreset?, dir: AnimDir, durationMs: Long, easing: Easing?) -> Unit,
    onPreview: (slot: AnimSlot, durationMs: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (clip == null) return

    var slot by remember { mutableStateOf(AnimSlot.IN) }
    var section by remember { mutableStateOf(AnimSection.OPTIONS) }
    val applied: ClipAnim? = if (slot == AnimSlot.IN) clip.animIn else clip.animOut
    val appliedPreset = AnimPresets.byId(applied?.preset)

    // Local controls, restarted from what the layer has whenever the tab changes.
    var dir by remember(slot) { mutableStateOf(applied?.dir ?: AnimDir.LEFT) }
    var durMs by remember(slot) { mutableFloatStateOf((applied?.durationMs ?: appliedPreset?.defaultMs ?: 500L).toFloat()) }
    var easing by remember(slot) {
        mutableStateOf(
            applied?.easing?.takeIf { e -> appliedPreset == null || !e.sameAs(appliedPreset.defaultEasing(slot)) }
        )
    }

    val maxMs = clip.maxAnimMs(slot)
    val sliderMax = maxMs.coerceAtMost(MAX_SLIDER_MS).coerceAtLeast(ANIM_MIN_MS + 1L).toFloat()
    val canAnimate = maxMs >= ANIM_MIN_MS

    fun applyAnim(p: AnimPreset?, d: AnimDir, ms: Long, e: Easing?) = onApply(slot, p, d, ms, e)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("clip_animations_sheet")
    ) {
        // In / Out tabs + section switch on one line (keeps the panel low)
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (s in AnimSlot.values()) {
                val on = s == slot
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .bouncyClickable(onClick = { slot = s })
                        .testTag("anim_tab_${s.name.lowercase()}"),
                    contentAlignment = Alignment.Center
                ) {
                    FlatPill(Modifier.matchParentSize())
                    Box(
                        Modifier
                            .matchParentSize()
                            .then(
                                if (on) Modifier.border(1.dp, amCyanVioletBrush(), RoundedCornerShape(16.dp))
                                else Modifier.border(1.dp, AmHairline, RoundedCornerShape(16.dp))
                            )
                    ) {}
                    Text(
                        s.label,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (appliedPreset != null && canAnimate) {
                for (sec in AnimSection.values()) {
                    val on = sec == section
                    Box(
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .then(
                                if (on) Modifier.background(AmCyan.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
                                else Modifier
                            )
                            .border(1.dp, if (on) AmCyan else Color.Transparent, RoundedCornerShape(16.dp))
                            .bouncyClickable(onClick = { section = sec })
                            .testTag("anim_section_${sec.name.lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!on) FlatPill(Modifier.matchParentSize())
                        Text(
                            sec.label, color = if (on) AmCyan else TextPrimary, fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // Animations: tap = applied
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                AnimTile(
                    label = "None",
                    icon = Icons.Default.Block,
                    selected = applied == null,
                    tag = "anim_none"
                ) { applyAnim(null, dir, durMs.toLong(), null) }
            }
            items(AnimPresets.ALL) { p ->
                AnimTile(
                    label = p.label,
                    icon = iconFor(p.id),
                    selected = applied?.preset == p.id,
                    tag = "anim_${p.id}"
                ) {
                    // A new animation starts from its own defaults (length, curve); the direction is kept
                    // when the animation has it.
                    val d = if (p.dirs.contains(dir)) dir else p.defaultDir()
                    dir = d
                    durMs = p.defaultMs.toFloat()
                    easing = null
                    applyAnim(p, d, p.defaultMs, null)
                    onPreview(slot, p.defaultMs.coerceAtMost(maxMs.coerceAtLeast(ANIM_MIN_MS)))
                }
            }
        }

        if (appliedPreset != null && canAnimate) {
            Spacer(Modifier.height(8.dp))
            if (section == AnimSection.OPTIONS) {
                // Direction
                if (appliedPreset.dirs.isNotEmpty()) {
                    Text(
                        if (appliedPreset.id.startsWith("spin")) "Turn" else if (slot == AnimSlot.IN) "From" else "To",
                        color = TextSecondary, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (d in appliedPreset.dirs) {
                            val on = d == dir
                            val spin = appliedPreset.id.startsWith("spin")
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .then(
                                        if (on) Modifier.background(AmCyan.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
                                        else Modifier
                                    )
                                    .border(1.5.dp, if (on) AmCyan else Color.Transparent, RoundedCornerShape(10.dp))
                                    .bouncyClickable(onClick = {
                                        dir = d
                                        applyAnim(appliedPreset, d, durMs.toLong(), easing)
                                        onPreview(slot, durMs.toLong())
                                    })
                                    .testTag("anim_dir_${d.name.lowercase()}"),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!on) FlatPill(Modifier.matchParentSize())
                                Text(
                                    if (spin) (if (d == AnimDir.RIGHT) "↻ Clockwise" else "↺ Counter")
                                    else "${d.arrow} ${d.label}",
                                    color = TextPrimary, fontSize = 13.sp,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Length
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Length", color = TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Text("%.2f s".format(durMs / 1000f), color = TextPrimary, fontSize = 13.sp)
                }
                Slider(
                    value = durMs.coerceIn(ANIM_MIN_MS.toFloat(), sliderMax),
                    onValueChange = {
                        durMs = it
                        applyAnim(appliedPreset, dir, it.roundToInt().toLong(), easing)
                    },
                    onValueChangeFinished = { onPreview(slot, durMs.toLong()) },
                    valueRange = ANIM_MIN_MS.toFloat()..sliderMax,
                    colors = SliderDefaults.colors(
                        thumbColor = AmCyan,
                        activeTrackColor = AmCyan,
                        inactiveTrackColor = EditorSurfaceVariant
                    ),
                    modifier = Modifier.testTag("anim_length_slider")
                )
            } else {
                // ---- Curve: shape preview + curve graph + ready-made curves ----
                val shownEasing = easing ?: appliedPreset.defaultEasing(slot)
                // 0..1 position inside the animation; the preview shape and the graph line share it.
                var t by remember { mutableFloatStateOf(0f) }
                LaunchedEffect(slot, appliedPreset.id, dir, durMs) {
                    val animNs = (durMs.coerceIn(400f, 2500f) * 1_000_000f).toLong()
                    val holdNs = 500_000_000L
                    var t0 = withFrameNanos { it }
                    while (true) {
                        val now = withFrameNanos { it }
                        val el = now - t0
                        if (el >= animNs + holdNs) t0 = now
                        t = (el.toFloat() / animNs).coerceIn(0f, 1f)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().height(118.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AnimShapePreview(
                        preset = appliedPreset,
                        slot = slot,
                        dir = dir,
                        easing = shownEasing,
                        t = t,
                        aspectRatio = aspectRatio,
                        modifier = Modifier.fillMaxHeight().padding(vertical = 4.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        EasingEditor(
                            easing = shownEasing,
                            progress = t,
                            onBegin = {},
                            onChange = { e ->
                                easing = e
                                applyAnim(appliedPreset, dir, durMs.toLong(), e)
                            },
                            showPresets = false,
                            valueLabel = "Move"
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        CurveChip(label = "Auto", easing = null, selected = easing == null) {
                            easing = null
                            applyAnim(appliedPreset, dir, durMs.toLong(), null)
                            onPreview(slot, durMs.toLong())
                        }
                    }
                    items(Easing.PRESETS) { (name, e) ->
                        CurveChip(label = name, easing = e, selected = easing?.sameAs(e) == true) {
                            easing = e
                            applyAnim(appliedPreset, dir, durMs.toLong(), e)
                            onPreview(slot, durMs.toLong())
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "The animation is made of normal keyframes: edit them in Transform, Crop or Blend.",
                color = TextSecondary, fontSize = 11.sp
            )
        } else if (!canAnimate) {
            Spacer(Modifier.height(14.dp))
            Text("This layer is too short for another animation.", color = TextSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * A small canvas with a shape on which the animation is played, using the animation's own keyframe
 * code (so it looks exactly as the real layer will move) and the chosen easing curve.
 * [t] 0..1 = position inside the animation. In: arrives. Out: leaves.
 */
@Composable
private fun AnimShapePreview(
    preset: AnimPreset,
    slot: AnimSlot,
    dir: AnimDir,
    easing: Easing,
    t: Float,
    aspectRatio: Float,
    modifier: Modifier = Modifier
) {
    val ratio = aspectRatio.coerceIn(0.4f, 2.4f)
    // A 4 s dummy layer with the animation applied. The canvas is the unit of Position (px).
    val previewW = 320f
    val previewH = previewW / ratio
    val layerMs = 4000L
    val animMs = 1000L
    val dummy = remember(preset.id, slot, dir, easing, ratio) {
        TimelineClip(
            id = "anim_preview", name = "p", type = LayerType.SHAPE,
            startMs = 0L, durationMs = layerMs, color = 0xFF00E5FF
        ).withClipAnim(slot, preset, dir, animMs, easing, previewW, previewH)
    }
    val ms = if (slot == AnimSlot.IN) (t * animMs).toLong() else (layerMs - animMs + (t * animMs).toLong())
    val c = dummy.at(ms.coerceIn(0L, layerMs))

    Canvas(
        modifier
            .aspectRatio(ratio, matchHeightConstraintsFirst = true)
            .glassSurface(RoundedCornerShape(8.dp), fill = amGlassBrush())
    ) {
        val k = size.width / previewW // preview px -> screen px
        val base = size.height * 0.5f
        val sc = (c.scalePercent / 100f).coerceAtLeast(0f)
        val side = base * sc
        val cx = size.width / 2f + c.transformX * k
        val cy = size.height / 2f + c.transformY * k
        val alpha = (c.opacity / 100f).coerceIn(0f, 1f)
        val left = cx - side / 2f
        val top = cy - side / 2f
        withTransform({ rotate(c.rotationDeg, Offset(cx, cy)) }) {
            // Crop box (wipe): the part of the shape that is visible.
            val cw = (c.cropWidthPercent / 100f).coerceIn(0f, 1f)
            val ch = (c.cropHeightPercent / 100f).coerceIn(0f, 1f)
            val cl = left + (side - side * cw) * (c.cropHorizontalPercent / 100f)
            val ct = top + (side - side * ch) * (c.cropVerticalPercent / 100f)
            clipRect(cl, ct, cl + side * cw, ct + side * ch) {
                drawRoundRect(
                    NeonCyan.copy(alpha = alpha),
                    topLeft = Offset(left, top),
                    size = Size(side, side),
                    cornerRadius = CornerRadius(side * 0.18f)
                )
                // A marker so turning / direction can be seen.
                drawCircle(
                    Color.Black.copy(alpha = 0.55f * alpha),
                    radius = side * 0.12f,
                    center = Offset(left + side * 0.28f, top + side * 0.28f)
                )
            }
        }
    }
}

@Composable
private fun AnimTile(label: String, icon: ImageVector, selected: Boolean, tag: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(72.dp)
            .bouncyClickable(onClick = onClick)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(12.dp))
                .then(
                    if (selected) Modifier.background(AmCyan.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                    else Modifier
                )
                .border(if (selected) 2.dp else 1.dp, if (selected) AmCyan else Color.Transparent, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (!selected) FlatTile(Modifier.matchParentSize())
            Icon(icon, contentDescription = label, tint = if (selected) AmCyan else Color.White, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(5.dp))
        Text(label, color = TextPrimary, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun CurveChip(label: String, easing: Easing?, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(62.dp)
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (selected) Modifier.background(AmCyan.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
                else Modifier
            )
            .border(1.5.dp, if (selected) AmCyan else Color.Transparent, RoundedCornerShape(10.dp))
            .bouncyClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (!selected) FlatTile(Modifier.matchParentSize())
        Column(
            modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        if (easing == null) {
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                Text("A", color = if (selected) AmCyan else Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            val tint = if (selected) AmCyan else Color.White
            Canvas(Modifier.size(34.dp)) {
                val pad = 4.dp.toPx()
                val w = size.width - 2 * pad
                val h = size.height - 2 * pad
                val yMin = -0.3f
                val yMax = 1.3f
                val path = Path()
                for (i in 0..24) {
                    val (x, y) = easing.pointAt(i / 24f)
                    val p = Offset(pad + x * w, pad + h - (y - yMin) / (yMax - yMin) * h)
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, tint, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Text(label, color = TextPrimary, fontSize = 9.sp, maxLines = 1)
        }
    }
}
