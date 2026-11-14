package com.example.ui.components
import androidx.compose.ui.graphics.RectangleShape

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.MultiAction
import com.example.model.MultiBarMode
import com.example.model.ShapeBooleanOp
import com.example.model.SpatialAction
import com.example.ui.theme.AmDanger
import com.example.ui.theme.amGlassBrush
import com.example.ui.util.bouncyClickable

private val IconColor = Color.White

/**
 * Bar shown at the bottom of the timeline while several layers are selected.
 * First row: left group depends on the playhead ([mode]); right group aligns the checked
 * layers IN TIME. Second row (build 188): canvas SPATIAL align / distribute of the checked
 * layers (see [com.example.model.SpatialAlign]) — bare tiles like the rest of the bar;
 * the two distribute tiles dim out below 3 checked layers (they need three to space).
 * Third row (build 192): shape BOOLEAN ops (see [com.example.model.ShapeBoolean]) —
 * composed ONLY when [showBooleanRow] (exactly two eligible shape layers checked);
 * Subtract reads first-checked minus second-checked.
 */
@Composable
fun MultiSelectBar(
    mode: MultiBarMode,
    onAction: (MultiAction) -> Unit,
    modifier: Modifier = Modifier,
    /** How many layers are checked (the distribute tiles need >= 3). */
    selectedCount: Int = 0,
    /** A spatial align / distribute tile of the second row. */
    onSpatialAction: (SpatialAction) -> Unit = {},
    /** Show the boolean row (exactly two eligible shape layers are checked). */
    showBooleanRow: Boolean = false,
    /** A boolean tile of the third row. */
    onBooleanAction: (ShapeBooleanOp) -> Unit = {},
    /** Delete tile (build 199, A11): deletes ALL checked layers at once (one undo step; locked layers are skipped). */
    onDeleteChecked: () -> Unit = {},
    /** Select-all tile (build 199, A4): checks every layer of the current scope. */
    onSelectAll: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .glassSurface(RectangleShape, fill = amGlassBrush())
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                when (mode) {
                    MultiBarMode.SPLIT -> {
                        BarIcon(MultiAction.DELETE_LEFT, onAction) { drawSplitIcon(dashLeft = true, dashRight = false) }
                        BarIcon(MultiAction.SPLIT, onAction) { drawSplitIcon(dashLeft = false, dashRight = false) }
                        BarIcon(MultiAction.DELETE_RIGHT, onAction) { drawSplitIcon(dashLeft = false, dashRight = true) }
                    }
                    MultiBarMode.START -> {
                        BarIcon(MultiAction.EXTEND_START, onAction) { drawExtendIcon(atStart = true) }
                        BarIcon(MultiAction.MOVE_START, onAction) { drawMoveIcon(atStart = true) }
                    }
                    MultiBarMode.END -> {
                        BarIcon(MultiAction.EXTEND_END, onAction) { drawExtendIcon(atStart = false) }
                        BarIcon(MultiAction.MOVE_END, onAction) { drawMoveIcon(atStart = false) }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MiniTile(label = "Delete", labelColor = AmDanger, onClick = onDeleteChecked) { drawTrashGlyph() }
                MiniTile(label = "All", labelColor = Color.White.copy(alpha = 0.75f), onClick = onSelectAll) { drawSelectAllGlyph() }
                AlignTile(MultiAction.ALIGN_START, onAction) { drawBars(BarsKind.LEFT) }
                AlignTile(MultiAction.SEQUENCE, onAction) { drawBars(BarsKind.STAIR) }
                AlignTile(MultiAction.ALIGN_END, onAction) { drawBars(BarsKind.RIGHT) }
                AlignTile(MultiAction.CHAIN, onAction) { drawChainIcon() }
                AlignTile(MultiAction.GROUP, onAction) { drawGroupIcon() }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                SpatialTile(SpatialAction.ALIGN_LEFT, onSpatialAction) { drawAlignGlyph(AlignGlyph.LEFT) }
                SpatialTile(SpatialAction.ALIGN_CENTER_H, onSpatialAction) { drawAlignGlyph(AlignGlyph.CENTER_H) }
                SpatialTile(SpatialAction.ALIGN_RIGHT, onSpatialAction) { drawAlignGlyph(AlignGlyph.RIGHT) }
                SpatialTile(SpatialAction.ALIGN_TOP, onSpatialAction) { drawAlignGlyph(AlignGlyph.TOP) }
                SpatialTile(SpatialAction.ALIGN_CENTER_V, onSpatialAction) { drawAlignGlyph(AlignGlyph.CENTER_V) }
                SpatialTile(SpatialAction.ALIGN_BOTTOM, onSpatialAction) { drawAlignGlyph(AlignGlyph.BOTTOM) }
                SpatialTile(SpatialAction.CANVAS_CENTER_H, onSpatialAction) { drawCanvasCentreGlyph(horizontal = true) }
                SpatialTile(SpatialAction.CANVAS_CENTER_V, onSpatialAction) { drawCanvasCentreGlyph(horizontal = false) }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                SpatialTile(SpatialAction.DISTRIBUTE_H, onSpatialAction, enabled = selectedCount >= 3) { drawDistributeGlyph(horizontal = true) }
                SpatialTile(SpatialAction.DISTRIBUTE_V, onSpatialAction, enabled = selectedCount >= 3) { drawDistributeGlyph(horizontal = false) }
            }
        }
        if (showBooleanRow) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                BooleanTile(ShapeBooleanOp.UNION, onBooleanAction)
                BooleanTile(ShapeBooleanOp.SUBTRACT, onBooleanAction)
                BooleanTile(ShapeBooleanOp.INTERSECT, onBooleanAction)
                BooleanTile(ShapeBooleanOp.XOR, onBooleanAction)
            }
        }
    }
}

/**
 * A boolean tile of the third row: a drawn Venn glyph over a small name (the ops are
 * not self-evident as bare icons on first sight; the name is part of the tile, in the
 * bar's bare B/W language — no box, no fill).
 */
@Composable
private fun BooleanTile(
    op: ShapeBooleanOp,
    onAction: (ShapeBooleanOp) -> Unit
) {
    Column(
        modifier = Modifier
            .width(58.dp)
            .bouncyClickable { onAction(op) }
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(modifier = Modifier.size(width = 28.dp, height = 20.dp)) { drawBooleanGlyph(op) }
        Text(
            op.displayName,
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 8.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

/**
 * A labelled mini-tile of the first row (build 199): Delete (A11) and Select-all (A4) —
 * actions whose glyph alone would be ambiguous get their name under the glyph, in the
 * bar's bare language. Delete carries the destructive red of the selected-layer bar.
 */
@Composable
private fun MiniTile(
    label: String,
    labelColor: Color,
    onClick: () -> Unit,
    draw: DrawScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .width(38.dp)
            .bouncyClickable { onClick() }
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(modifier = Modifier.size(width = 24.dp, height = 20.dp)) { draw() }
        Text(
            label,
            color = labelColor,
            fontSize = 8.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun BarIcon(
    action: MultiAction,
    onAction: (MultiAction) -> Unit,
    draw: DrawScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 44.dp)
            .bouncyClickable { onAction(action) },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(width = 40.dp, height = 30.dp)) { draw() }
    }
}

@Composable
private fun AlignTile(
    action: MultiAction,
    onAction: (MultiAction) -> Unit,
    draw: DrawScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .size(width = 34.dp, height = 36.dp)
            .bouncyClickable { onAction(action) },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(width = 24.dp, height = 20.dp)) { draw() }
    }
}

/** A spatial align / distribute tile of the second row (same bare tile as [AlignTile]). */
@Composable
private fun SpatialTile(
    action: SpatialAction,
    onAction: (SpatialAction) -> Unit,
    enabled: Boolean = true,
    draw: DrawScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .size(width = 34.dp, height = 34.dp)
            .bouncyClickable(enabled = enabled) { onAction(action) },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(width = 24.dp, height = 20.dp)) { draw() }
    }
}

// ---------------------------------------------------------------------------------------------
// Icons (drawn, so they match the reference screenshots without extra assets)
// ---------------------------------------------------------------------------------------------

/** Chain: a dashed container with two layers side by side inside. */
private fun DrawScope.drawChainIcon() {
    val sw = 1.6.dp.toPx()
    drawRoundRect(
        color = IconColor,
        topLeft = Offset(sw, sw),
        size = Size(size.width - 2 * sw, size.height - 2 * sw),
        cornerRadius = CornerRadius(3.dp.toPx()),
        style = Stroke(width = sw, pathEffect = dashed())
    )
    val h = size.height * 0.34f
    val top = (size.height - h) / 2f
    drawRoundRect(IconColor, Offset(size.width * 0.16f, top), Size(size.width * 0.28f, h), CornerRadius(1.5.dp.toPx()))
    drawRoundRect(IconColor, Offset(size.width * 0.54f, top), Size(size.width * 0.30f, h), CornerRadius(1.5.dp.toPx()))
}

/** Group: a solid frame with two layers inside (they move together). */
private fun DrawScope.drawGroupIcon() {
    val sw = 1.8.dp.toPx()
    drawRoundRect(
        color = IconColor,
        topLeft = Offset(sw, sw),
        size = Size(size.width - 2 * sw, size.height - 2 * sw),
        cornerRadius = CornerRadius(3.dp.toPx()),
        style = Stroke(width = sw)
    )
    drawRoundRect(IconColor, Offset(size.width * 0.20f, size.height * 0.22f), Size(size.width * 0.36f, size.height * 0.30f), CornerRadius(1.5.dp.toPx()))
    drawRoundRect(IconColor, Offset(size.width * 0.40f, size.height * 0.50f), Size(size.width * 0.38f, size.height * 0.28f), CornerRadius(1.5.dp.toPx()))
}

private fun DrawScope.dashed() = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))

private fun DrawScope.outline(x0: Float, x1: Float, dash: Boolean) {
    val sw = 1.8.dp.toPx()
    val top = size.height * 0.2f
    val h = size.height * 0.6f
    drawRoundRect(
        color = IconColor,
        topLeft = Offset(x0, top),
        size = Size((x1 - x0).coerceAtLeast(1f), h),
        cornerRadius = CornerRadius(3.dp.toPx()),
        style = Stroke(width = sw, pathEffect = if (dash) dashed() else null)
    )
}

private fun DrawScope.verticalLine(x: Float) {
    drawLine(
        color = IconColor,
        start = Offset(x, 1.dp.toPx()),
        end = Offset(x, size.height - 1.dp.toPx()),
        strokeWidth = 2.dp.toPx(),
        cap = StrokeCap.Round
    )
}

/** delete-left / split / delete-right: playhead line with a box on each side. */
private fun DrawScope.drawSplitIcon(dashLeft: Boolean, dashRight: Boolean) {
    val cx = size.width / 2f
    val pad = 1.5.dp.toPx()
    val gap = 4.dp.toPx()
    outline(pad, cx - gap, dashLeft)
    outline(cx + gap, size.width - pad, dashRight)
    verticalLine(cx)
}

/** Extend start/end to the playhead: dashed (new) part + solid (old) part next to the line. */
private fun DrawScope.drawExtendIcon(atStart: Boolean) {
    val pad = 1.5.dp.toPx()
    val gap = 4.dp.toPx()
    val w = size.width
    if (atStart) {
        verticalLine(pad)
        outline(pad + gap, w * 0.52f, dash = true)
        outline(w * 0.52f, w - pad, dash = false)
    } else {
        verticalLine(w - pad)
        outline(pad, w * 0.48f, dash = false)
        outline(w * 0.48f, w - pad - gap, dash = true)
    }
}

/** Move start/end to the playhead: solid box and an arrow toward the line. */
private fun DrawScope.drawMoveIcon(atStart: Boolean) {
    val pad = 1.5.dp.toPx()
    val w = size.width
    val cy = size.height / 2f
    val sw = 1.8.dp.toPx()
    val head = 5.dp.toPx()
    if (atStart) {
        verticalLine(pad)
        outline(w * 0.42f, w - pad, dash = false)
        val x1 = pad + 1.dp.toPx()
        val x2 = w * 0.42f - 2.dp.toPx()
        drawLine(IconColor, Offset(x1, cy), Offset(x2, cy), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(IconColor, Offset(x1, cy), Offset(x1 + head, cy - head), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(IconColor, Offset(x1, cy), Offset(x1 + head, cy + head), strokeWidth = sw, cap = StrokeCap.Round)
    } else {
        verticalLine(w - pad)
        outline(pad, w * 0.58f, dash = false)
        val x1 = w * 0.58f + 2.dp.toPx()
        val x2 = w - pad - 1.dp.toPx()
        drawLine(IconColor, Offset(x1, cy), Offset(x2, cy), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(IconColor, Offset(x2, cy), Offset(x2 - head, cy - head), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(IconColor, Offset(x2, cy), Offset(x2 - head, cy + head), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

private enum class AlignGlyph { LEFT, CENTER_H, RIGHT, TOP, CENTER_V, BOTTOM }

/** Trash can in the destructive red (build 199, A11): lid, handle and slatted body. */
private fun DrawScope.drawTrashGlyph() {
    val w = size.width
    val h = size.height
    val sw = 1.7.dp.toPx()
    val c = AmDanger
    // Lid + handle.
    drawLine(c, Offset(w * 0.14f, h * 0.2f), Offset(w * 0.86f, h * 0.2f), sw, StrokeCap.Round)
    drawLine(c, Offset(w * 0.4f, h * 0.2f), Offset(w * 0.4f, h * 0.1f), sw, StrokeCap.Round)
    drawLine(c, Offset(w * 0.6f, h * 0.2f), Offset(w * 0.6f, h * 0.1f), sw, StrokeCap.Round)
    drawLine(c, Offset(w * 0.4f, h * 0.1f), Offset(w * 0.6f, h * 0.1f), sw, StrokeCap.Round)
    // Body: slightly tapered can.
    val body = Path().apply {
        moveTo(w * 0.24f, h * 0.32f)
        lineTo(w * 0.3f, h * 0.9f)
        lineTo(w * 0.7f, h * 0.9f)
        lineTo(w * 0.76f, h * 0.32f)
    }
    drawPath(body, c, style = Stroke(width = sw, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    drawLine(c, Offset(w * 0.44f, h * 0.45f), Offset(w * 0.46f, h * 0.78f), sw, StrokeCap.Round)
    drawLine(c, Offset(w * 0.56f, h * 0.45f), Offset(w * 0.54f, h * 0.78f), sw, StrokeCap.Round)
}

/** Dashed selection square with a check (build 199, A4): select all layers. */
private fun DrawScope.drawSelectAllGlyph() {
    val w = size.width
    val h = size.height
    val sw = 1.6.dp.toPx()
    drawRoundRect(
        color = IconColor,
        topLeft = Offset(sw, sw),
        size = Size(w - 2 * sw, h - 2 * sw),
        cornerRadius = CornerRadius(2.dp.toPx()),
        style = Stroke(width = sw, pathEffect = dashed())
    )
    val tick = Path().apply {
        moveTo(w * 0.3f, h * 0.52f)
        lineTo(w * 0.45f, h * 0.68f)
        lineTo(w * 0.72f, h * 0.34f)
    }
    drawPath(tick, IconColor, style = Stroke(width = sw, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

/**
 * Canvas-centre glyphs (build 199, A8): a FRAME outline (not a selection guide) with one
 * bar centred in it — vertical bar for centre-H, horizontal bar for centre-V.
 */
private fun DrawScope.drawCanvasCentreGlyph(horizontal: Boolean) {
    val w = size.width
    val h = size.height
    val sw = 1.6.dp.toPx()
    drawRoundRect(
        color = IconColor,
        topLeft = Offset(sw, sw),
        size = Size(w - 2 * sw, h - 2 * sw),
        cornerRadius = CornerRadius(2.dp.toPx()),
        style = Stroke(width = sw)
    )
    val cr = CornerRadius(1.5.dp.toPx())
    if (horizontal) {
        drawRoundRect(IconColor, Offset(w / 2f - w * 0.07f, h * 0.24f), Size(w * 0.14f, h * 0.52f), cr)
    } else {
        drawRoundRect(IconColor, Offset(w * 0.24f, h / 2f - h * 0.1f), Size(w * 0.52f, h * 0.2f), cr)
    }
}

/**
 * Spatial align glyphs: a thin guide line (the edge / centre everything moves to) with
 * three bars of different lengths against it — horizontal bars for the X actions,
 * vertical bars for the Y actions.
 */
private fun DrawScope.drawAlignGlyph(kind: AlignGlyph) {
    val w = size.width
    val h = size.height
    val sw = 1.6.dp.toPx()
    val cr = CornerRadius(1.5.dp.toPx())
    val lens = floatArrayOf(0.92f, 0.58f, 0.36f)
    when (kind) {
        AlignGlyph.LEFT, AlignGlyph.CENTER_H, AlignGlyph.RIGHT -> {
            val gx = when (kind) {
                AlignGlyph.LEFT -> 1.dp.toPx()
                AlignGlyph.RIGHT -> w - 1.dp.toPx()
                else -> w / 2f
            }
            drawLine(IconColor, Offset(gx, 0f), Offset(gx, h), strokeWidth = sw, cap = StrokeCap.Round)
            val thick = h * 0.2f
            val ys = floatArrayOf(0f, h * 0.4f, h * 0.8f)
            for (i in 0..2) {
                val len = w * lens[i]
                val x = when (kind) {
                    AlignGlyph.LEFT -> gx + 2.dp.toPx()
                    AlignGlyph.RIGHT -> gx - 2.dp.toPx() - len
                    else -> gx - len / 2f
                }
                drawRoundRect(IconColor, Offset(x, ys[i]), Size(len, thick), cr)
            }
        }
        else -> {
            val gy = when (kind) {
                AlignGlyph.TOP -> 1.dp.toPx()
                AlignGlyph.BOTTOM -> h - 1.dp.toPx()
                else -> h / 2f
            }
            drawLine(IconColor, Offset(0f, gy), Offset(w, gy), strokeWidth = sw, cap = StrokeCap.Round)
            val thick = w * 0.2f
            val xs = floatArrayOf(0f, w * 0.4f, w * 0.8f)
            for (i in 0..2) {
                val len = h * lens[i]
                val y = when (kind) {
                    AlignGlyph.TOP -> gy + 2.dp.toPx()
                    AlignGlyph.BOTTOM -> gy - 2.dp.toPx() - len
                    else -> gy - len / 2f
                }
                drawRoundRect(IconColor, Offset(xs[i], y), Size(thick, len), cr)
            }
        }
    }
}

/** Distribute glyphs: three equal blocks spaced evenly between two end ticks. */
private fun DrawScope.drawDistributeGlyph(horizontal: Boolean) {
    val w = size.width
    val h = size.height
    val sw = 1.6.dp.toPx()
    val cr = CornerRadius(1.5.dp.toPx())
    if (horizontal) {
        drawLine(IconColor, Offset(0f, 0f), Offset(0f, h), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(IconColor, Offset(w, 0f), Offset(w, h), strokeWidth = sw, cap = StrokeCap.Round)
        val bw = w * 0.18f
        val bh = h * 0.52f
        val top = (h - bh) / 2f
        for (cx in floatArrayOf(0.25f, 0.5f, 0.75f)) {
            drawRoundRect(IconColor, Offset(w * cx - bw / 2f, top), Size(bw, bh), cr)
        }
    } else {
        drawLine(IconColor, Offset(0f, 0f), Offset(w, 0f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(IconColor, Offset(0f, h), Offset(w, h), strokeWidth = sw, cap = StrokeCap.Round)
        val bw = w * 0.52f
        val bh = h * 0.18f
        val left = (w - bw) / 2f
        for (cy in floatArrayOf(0.25f, 0.5f, 0.75f)) {
            drawRoundRect(IconColor, Offset(left, h * cy - bh / 2f), Size(bw, bh), cr)
        }
    }
}

/**
 * Boolean glyphs: two overlapping circles read as the op's Venn diagram. The glyph
 * paths use the Compose path ops (an icon convenience only — the feature's own core
 * is [com.example.model.ShapeBoolean]). Subtract cuts the RIGHT circle out of the
 * left (first-checked minus second-checked) and leaves the cut circle as a dim
 * outline so the direction stays readable.
 */
private fun DrawScope.drawBooleanGlyph(op: ShapeBooleanOp) {
    val w = size.width
    val h = size.height
    val r = minOf(w, h) * 0.34f
    fun circle(cx: Float, cy: Float): Path = Path().apply {
        addOval(Rect(Offset(cx - r, cy - r), Size(2f * r, 2f * r)))
    }
    val p1 = circle(w * 0.36f, h * 0.5f)
    val p2 = circle(w * 0.64f, h * 0.5f)
    val sw = 1.4.dp.toPx()
    when (op) {
        ShapeBooleanOp.UNION -> {
            drawPath(p1, IconColor)
            drawPath(p2, IconColor)
        }
        ShapeBooleanOp.SUBTRACT -> {
            val cut = Path().apply { op(p1, p2, PathOperation.Difference) }
            drawPath(cut, IconColor)
            drawPath(p2, IconColor.copy(alpha = 0.45f), style = Stroke(width = sw))
        }
        ShapeBooleanOp.INTERSECT -> {
            drawPath(p1, IconColor.copy(alpha = 0.45f), style = Stroke(width = sw))
            drawPath(p2, IconColor.copy(alpha = 0.45f), style = Stroke(width = sw))
            val lens = Path().apply { op(p1, p2, PathOperation.Intersect) }
            drawPath(lens, IconColor)
        }
        ShapeBooleanOp.XOR -> {
            val x = Path().apply { op(p1, p2, PathOperation.Xor) }
            drawPath(x, IconColor)
        }
    }
}

private enum class BarsKind { LEFT, STAIR, RIGHT }

/** Three little bars: left-aligned, staircase, right-aligned. */
private fun DrawScope.drawBars(kind: BarsKind) {
    val w = size.width
    val h = size.height
    val barH = h * 0.22f
    val ys = floatArrayOf(0f, h * 0.39f, h * 0.78f)
    val lens = floatArrayOf(1f, 0.62f, 0.38f)
    for (i in 0..2) {
        val len = w * lens[i]
        val x = when (kind) {
            BarsKind.LEFT -> 0f
            BarsKind.RIGHT -> w - len
            BarsKind.STAIR -> when (i) { 0 -> w * 0.45f; 1 -> w * 0.2f; else -> 0f }
        }
        val drawLen = if (kind == BarsKind.STAIR) w * floatArrayOf(0.55f, 0.6f, 0.4f)[i] else len
        drawRoundRect(
            color = IconColor,
            topLeft = Offset(x, ys[i]),
            size = Size(drawLen, barH),
            cornerRadius = CornerRadius(1.5.dp.toPx())
        )
    }
}
