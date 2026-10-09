package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.model.MultiAction
import com.example.model.MultiBarMode
import com.example.ui.util.bouncyClickable

private val BarColor = Color(0xFF363A55)
private val TileColor = Color(0xFF50536B)
private val IconColor = Color.White

/**
 * Bar shown at the bottom of the timeline while several layers are selected.
 * Left group depends on the playhead ([mode]); right group aligns the checked layers.
 */
@Composable
fun MultiSelectBar(
    mode: MultiBarMode,
    onAction: (MultiAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .height(54.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(BarColor)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
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
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AlignTile(MultiAction.ALIGN_START, onAction) { drawBars(BarsKind.LEFT) }
            AlignTile(MultiAction.SEQUENCE, onAction) { drawBars(BarsKind.STAIR) }
            AlignTile(MultiAction.ALIGN_END, onAction) { drawBars(BarsKind.RIGHT) }
        }
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
            .size(width = 48.dp, height = 44.dp)
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
            .size(width = 38.dp, height = 36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(TileColor)
            .bouncyClickable { onAction(action) },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(width = 24.dp, height = 20.dp)) { draw() }
    }
}

// ---------------------------------------------------------------------------------------------
// Icons (drawn, so they match the reference screenshots without extra assets)
// ---------------------------------------------------------------------------------------------

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
