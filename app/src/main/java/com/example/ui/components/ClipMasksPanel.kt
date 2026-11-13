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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ClipMask
import com.example.model.ClipMaskLibrary
import com.example.model.MaskFrame
import com.example.model.MaskMode
import com.example.model.MaskShape
import com.example.model.MaskState
import com.example.model.TimelineClip
import com.example.model.sourceOffsetMs
import com.example.model.neighbourKey
import com.example.ui.theme.AmCyan
import com.example.ui.theme.AmHairline
import com.example.ui.theme.AmMicroLabel
import com.example.ui.theme.AmTextDim
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.amCyanVioletBrush
import com.example.ui.util.bouncyClickable

/** Width / height of the layer's picture (the mask boxes are square in pixels, so the panel must know it). */
fun maskPictureAspect(clip: TimelineClip): Float = if (clip.sourceAspect > 0.05f) clip.sourceAspect else 16f / 9f

private val addTiles: List<Pair<String, String>> = listOf(
    "rect" to "Rectangle", "ellipse" to "Ellipse", "polygon" to "Polygon", "star" to "Star",
    "heart" to "Heart", "line" to "Line", "mirror" to "Mirror", "pen" to "Pen"
)

/**
 * Masks panel (same place and look as Effects). Top: tiles that add a mask. Then the masks of the layer (tap = select,
 * eye = on / off, copy, X = remove; the order is the order they are combined in). Below: the selected mask's mode,
 * invert, keyframe diamond and sliders. The shape itself is dragged on the canvas ([MaskEditOverlay]).
 * Edits of animated values (position, size, turn, feather, expansion, opacity) write a key at the playhead as soon as
 * the mask has one (diamond).
 */
@Composable
fun ClipMasksPanel(
    clip: TimelineClip,
    playheadMs: Long,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    /** Pen drawing on the canvas: number of points placed so far, or -1 when not drawing. */
    penPoints: Int,
    onPenStart: () -> Unit,
    onPenUndo: () -> Unit,
    onPenDone: () -> Unit,
    onPenCancel: () -> Unit,
    /** Pen path edit: the control point selected on the canvas (-1 = none) and a request to delete it. */
    selectedPoint: Int,
    onDeletePoint: () -> Unit,
    /** Jump the playhead to a timeline time (previous / next key). */
    onSeekTo: (Long) -> Unit,
    onChange: (List<ClipMask>) -> Unit,
    /** "Track object" of the selected mask: the editor asks where the object is and follows it (id of the mask). */
    onTrackMask: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val masks = clip.masks
    val aspect = maskPictureAspect(clip)
    val rel = playheadMs - clip.startMs
    val selected = masks.firstOrNull { it.id == selectedId }

    fun replace(m: ClipMask) = onChange(masks.map { if (it.id == m.id) m else it })

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("clip_masks_panel")
    ) {
        if (penPoints >= 0) {
            Text(
                if (penPoints < 3) "Tap the picture to place points ($penPoints / ${ClipMaskLibrary.MAX_POINTS}, at least 3)"
                else "$penPoints points. Keep tapping, or press Done.",
                color = TextPrimary, fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Undo point", false, enabled = penPoints > 0, tag = "mask_pen_undo", onClick = onPenUndo)
                Chip("Done", true, enabled = penPoints >= 3, tag = "mask_pen_done", onClick = onPenDone)
                Chip("Cancel", false, tag = "mask_pen_cancel", onClick = onPenCancel)
            }
            Spacer(Modifier.height(16.dp))
            return@Column
        }

        Text("Add a mask", color = AmTextDim, style = AmMicroLabel)
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(addTiles) { (id, label) ->
                ShapeTile(id, label, onClick = {
                    if (id == "pen") {
                        if (masks.size < ClipMaskLibrary.MAX_MASKS) onPenStart()
                    } else if (masks.size < ClipMaskLibrary.MAX_MASKS) {
                        val m = when (id) {
                            "rect" -> ClipMaskLibrary.create(MaskShape.RECT, aspect)
                            "ellipse" -> ClipMaskLibrary.create(MaskShape.ELLIPSE, aspect)
                            "polygon" -> ClipMaskLibrary.create(MaskShape.POLYGON, aspect)
                            "star" -> ClipMaskLibrary.create(MaskShape.STAR, aspect)
                            "heart" -> ClipMaskLibrary.heart(aspect)
                            "line" -> ClipMaskLibrary.create(MaskShape.LINE, aspect)
                            else -> ClipMaskLibrary.create(MaskShape.MIRROR, aspect)
                        }
                        onSelect(m.id)
                        onChange(masks + m)
                    }
                })
            }
        }
        Spacer(Modifier.height(14.dp))

        Text("Masks (combined top to bottom)", color = AmTextDim, style = AmMicroLabel)
        Spacer(Modifier.height(6.dp))
        if (masks.isEmpty()) {
            Text("Nothing yet. Tap a shape above, then drag it on the picture.", color = TextSecondary, fontSize = 13.sp)
        }
        masks.forEachIndexed { idx, m ->
            val on = m.id == selectedId
            Box(
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .fillMaxWidth()
                    .bouncyClickable(pressedScale = 0.99f, onClick = { onSelect(if (on) null else m.id) })
                    .testTag("mask_row_$idx"),
                contentAlignment = Alignment.Center
            ) {
                KitGlassPill(Modifier.matchParentSize())
                if (on) Box(
                    Modifier
                        .matchParentSize()
                        .border(1.5.dp, amCyanVioletBrush(), RoundedCornerShape(12.dp))
                ) {}
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                Text(
                    "${idx + 1}. ${MaskShape.names[m.shape] ?: "Mask"}" + (if (m.isAnimated) "  ◆" else ""),
                    color = if (!m.enabled) TextSecondary else if (on) AmCyan else TextPrimary,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    (MaskMode.names.firstOrNull { it.first == m.mode }?.second ?: "") + if (m.inverted) " (inv)" else "",
                    color = TextSecondary, fontSize = 11.sp
                )
                IconBtn(if (m.enabled) Icons.Default.Visibility else Icons.Default.VisibilityOff, "Turn off / on", "mask_toggle_$idx") {
                    replace(m.copy(enabled = !m.enabled))
                }
                IconBtn(Icons.Default.ContentCopy, "Duplicate", "mask_copy_$idx") {
                    if (masks.size < ClipMaskLibrary.MAX_MASKS) {
                        val c = m.copy(id = java.util.UUID.randomUUID().toString(),
                            state = m.state.copy(cx = (m.state.cx + 0.04f).coerceAtMost(1f)),
                            keys = m.keys.map { k -> k.copy(state = k.state.copy(cx = (k.state.cx + 0.04f).coerceAtMost(1f))) })
                        onSelect(c.id)
                        onChange(masks.toMutableList().also { it.add(idx + 1, c) })
                    }
                }
                IconBtn(Icons.Default.Delete, "Remove", "mask_remove_$idx") {
                    if (selectedId == m.id) onSelect(null)
                    onChange(masks.filter { it.id != m.id })
                }
                }
            }
        }

        if (selected != null) {
            // a tracked mask is shown (and edited) where the object is: the sliders show the drawn values
            val src = clip.trimStartMs + clip.sourceOffsetMs(rel.coerceAtLeast(0L))
            val s = selected.shownStateAt(rel, src)
            val keyIdx = selected.keyIndexAt(rel)
            fun edit(ns: MaskState) = replace(selected.withShownStateAt(rel, src, ns))
            Spacer(Modifier.height(6.dp))
            Text("Combine", color = AmTextDim, style = AmMicroLabel)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(MaskMode.names) { (mode, label) ->
                    Chip(label, selected.mode == mode, tag = "mask_mode_$mode") { replace(selected.copy(mode = mode)) }
                }
                item { Chip("Invert", selected.inverted, tag = "mask_invert") { replace(selected.copy(inverted = !selected.inverted)) } }
            }
            Spacer(Modifier.height(10.dp))

            // keyframes of this mask
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Animate", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(78.dp))
                IconBtn(Icons.Default.SkipPrevious, "Previous key", "mask_key_prev") {
                    val i = selected.neighbourKey(rel, false)
                    if (i >= 0) onSeekTo(clip.startMs + selected.keys[i].timeMs)
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .bouncyClickable(onClick = { replace(selected.toggleKeyAt(rel)) })
                        .testTag("mask_key_toggle"),
                    contentAlignment = Alignment.Center
                ) { Diamond(filled = keyIdx >= 0, active = selected.isAnimated) }
                IconBtn(Icons.Default.SkipNext, "Next key", "mask_key_next") {
                    val i = selected.neighbourKey(rel, true)
                    if (i >= 0) onSeekTo(clip.startMs + selected.keys[i].timeMs)
                }
                Text(
                    if (selected.isAnimated) "${selected.keys.size} keys" else "off",
                    color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp)
                )
            }
            Spacer(Modifier.height(4.dp))

            // motion track: the mask follows an object of the video (place the mask on the object first)
            val tr = selected.track
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Track", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(78.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Chip(if (tr == null) "Track object" else "Track again", tr != null, tag = "mask_track") { onTrackMask(selected.id) }
                    }
                    if (tr != null) {
                        item { Chip("Rotate", tr.followRotation, tag = "mask_track_rot") { replace(selected.copy(track = tr.withFollow(!tr.followRotation, tr.followScale))) } }
                        item { Chip("Scale", tr.followScale, tag = "mask_track_scale") { replace(selected.copy(track = tr.withFollow(tr.followRotation, !tr.followScale))) } }
                        item { Chip("Clear", false, tag = "mask_track_clear") { replace(selected.copy(track = null)) } }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))

            MaskSlider("Feather", s.feather, 0f, 100f, "", "feather") { edit(s.copy(feather = it)) }
            MaskSlider("Expansion", s.expand, -100f, 100f, "", "expand") { edit(s.copy(expand = it)) }
            MaskSlider("Opacity", s.opacity, 0f, 100f, "%", "opacity") { edit(s.copy(opacity = it)) }
            if (selected.shape != MaskShape.LINE) {
                MaskSlider("Rotation", s.rot, -180f, 180f, "°", "rot") { edit(s.copy(rot = it)) }
            } else {
                MaskSlider("Angle", s.rot, -180f, 180f, "°", "rot") { edit(s.copy(rot = it)) }
            }
            if (selected.shape != MaskShape.LINE) {
                if (selected.shape != MaskShape.MIRROR) {
                    MaskSlider("Width", s.w * 100f, 1f, 200f, "%", "w") { edit(s.copy(w = it / 100f)) }
                }
                MaskSlider("Height", s.h * 100f, 1f, 200f, "%", "h") { edit(s.copy(h = it / 100f)) }
            }
            MaskSlider("Position X", s.cx * 100f, -50f, 150f, "%", "x") { edit(s.copy(cx = it / 100f)) }
            MaskSlider("Position Y", s.cy * 100f, -50f, 150f, "%", "y") { edit(s.copy(cy = it / 100f)) }
            when (selected.shape) {
                MaskShape.RECT -> MaskSlider("Corners", selected.corner, 0f, 100f, "%", "corner") { replace(selected.copy(corner = it)) }
                MaskShape.POLYGON -> MaskSlider("Sides", selected.sides.toFloat(), 3f, 12f, "", "sides") { replace(selected.copy(sides = it.toInt().coerceIn(3, 12))) }
                MaskShape.STAR -> {
                    MaskSlider("Points", selected.sides.toFloat(), 3f, 12f, "", "sides") { replace(selected.copy(sides = it.toInt().coerceIn(3, 12))) }
                    MaskSlider("Inner size", selected.inner, 5f, 100f, "%", "inner") { replace(selected.copy(inner = it)) }
                }
                MaskShape.PATH -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Chip("Smooth curve", selected.smooth, tag = "mask_smooth") { replace(selected.copy(smooth = !selected.smooth)) }
                        Chip(
                            "Delete point", false,
                            enabled = selectedPoint in selected.points.indices && selected.points.size > ClipMaskLibrary.MIN_POINTS,
                            tag = "mask_delete_point", onClick = onDeletePoint
                        )
                    }
                    Text(
                        "Drag the dots on the picture. Tap an edge to add a point, tap a dot to select it.",
                        color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun MaskSlider(label: String, value: Float, min: Float, max: Float, unit: String, tag: String, onValue: (Float) -> Unit) {
    // The same ruler slider as Transform / Effects.
    RulerDialSlider(
        label = label,
        labelWidth = 105.dp,
        value = value.coerceIn(min, max),
        onValueChange = onValue,
        min = min,
        max = max,
        unit = unit,
        isPercentage = false,
        modifier = Modifier.testTag("mask_slider_$tag")
    )
}

@Composable
private fun Chip(label: String, on: Boolean, enabled: Boolean = true, tag: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .bouncyClickable(onClick = { if (enabled) onClick() })
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        KitGlassPill(Modifier.matchParentSize())
        Box(
            Modifier
                .matchParentSize()
                .then(
                    if (on) Modifier.border(1.dp, amCyanVioletBrush(), RoundedCornerShape(16.dp))
                    else Modifier.border(1.dp, AmHairline, RoundedCornerShape(16.dp))
                )
        ) {}
        Text(
            label,
            color = if (!enabled) TextSecondary else TextPrimary,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun IconBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, tag: String, onClick: () -> Unit) {
    Icon(
        icon, contentDescription = desc, tint = TextPrimary,
        modifier = Modifier
            .size(36.dp)
            .bouncyClickable(onClick = onClick)
            .padding(8.dp)
            .testTag(tag)
    )
}

@Composable
private fun Diamond(filled: Boolean, active: Boolean) {
    Canvas(Modifier.size(20.dp)) {
        val c = Color(if (active) 0xFFFFC107 else 0xFF9E9E9E)
        rotate(45f) {
            val s = size.width * 0.62f
            val tl = Offset((size.width - s) / 2f, (size.height - s) / 2f)
            if (filled) drawRect(c, tl, Size(s, s)) else drawRect(c, tl, Size(s, s), style = Stroke(2.2f))
        }
    }
}

@Composable
private fun ShapeTile(id: String, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(72.dp)
            .bouncyClickable(onClick = onClick)
            .testTag("mask_tile_$id"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(56.dp),
            contentAlignment = Alignment.Center
        ) {
            KitGlassTile(Modifier.matchParentSize())
            Canvas(Modifier.size(30.dp)) { drawGlyph(id) }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = TextPrimary, fontSize = 11.sp, maxLines = 1)
    }
}

/** Small picture of a shape for the add tiles, drawn with the same outline code the canvas editor uses. */
private fun DrawScope.drawGlyph(id: String) {
    val w = size.width
    val stroke = Stroke(2.4f, join = StrokeJoin.Round)
    val white = Color.White
    val shape = when (id) {
        "rect" -> MaskShape.RECT
        "ellipse" -> MaskShape.ELLIPSE
        "polygon" -> MaskShape.POLYGON
        "star" -> MaskShape.STAR
        "line" -> MaskShape.LINE
        "mirror" -> MaskShape.MIRROR
        else -> MaskShape.PATH
    }
    when (id) {
        "line" -> {
            drawLine(white, Offset(0f, w * 0.5f), Offset(w, w * 0.5f), 2.6f)
            drawLine(white.copy(alpha = 0.4f), Offset(w * 0.5f, w * 0.5f), Offset(w * 0.5f, w * 0.15f), 2.6f)
            return
        }
        "mirror" -> {
            drawLine(white, Offset(0f, w * 0.3f), Offset(w, w * 0.3f), 2.6f)
            drawLine(white, Offset(0f, w * 0.7f), Offset(w, w * 0.7f), 2.6f)
            return
        }
        "pen" -> {
            val p = Path().apply {
                moveTo(w * 0.15f, w * 0.8f); lineTo(w * 0.3f, w * 0.2f); lineTo(w * 0.7f, w * 0.35f); lineTo(w * 0.85f, w * 0.85f); close()
            }
            drawPath(p, white, style = stroke)
            return
        }
    }
    // unit-square glyph mask drawn through MaskFrame (so the tile always matches the real outline)
    val m: ClipMask = when (id) {
        "heart" -> ClipMaskLibrary.heart(1f)
        else -> ClipMaskLibrary.create(shape, 1f)
    }
    val s = MaskState(cx = 0.5f, cy = 0.5f, w = 0.9f, h = 0.9f)
    val pts = MaskFrame(m, s, 1f).outline()
    if (pts.size < 2) return
    val path = Path().apply {
        moveTo(pts[0].x * w, pts[0].y * w)
        for (i in 1 until pts.size) lineTo(pts[i].x * w, pts[i].y * w)
        close()
    }
    drawPath(path, white, style = stroke)
}
