package com.example.ui.components
import androidx.compose.ui.graphics.RectangleShape

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Easing
import com.example.ui.util.bouncyClickable
import kotlin.math.hypot

private val HandleColor = Color(0xFFEA1252)
private val TileBg = Color(0xFF2E3033)
private val TileBgSelected = Color(0xFF3F4144)
private val GridColor = Color(0xFF4A4C50)

/** The graph shows y from [Y_MIN] to [Y_MAX] so overshooting ("back") curves stay visible. */
private const val Y_MIN = -0.3f
private const val Y_MAX = 1.3f

/**
 * Easing curve editor (Alight Motion style) for the segment between two keyframes.
 * Left: the curve with two draggable control points and a vertical line for where the playhead is
 * inside the segment. Right: ready-made curves to tap.
 *
 * [onBegin] is called once when a change starts (one undo step), [onChange] on every change.
 */
@Composable
fun EasingEditor(
    easing: Easing,
    progress: Float,
    onBegin: () -> Unit,
    onChange: (Easing) -> Unit,
    modifier: Modifier = Modifier,
    /** false = only the curve graph (the caller shows its own ready-made curves). */
    showPresets: Boolean = true,
    /** Label of the vertical axis. */
    valueLabel: String = "Transform",
    /**
     * Delete-keyframe affordance (build 199, A5): when the host edits a real keyframe
     * segment, this shows a small "Delete keyframe" action over the graph — it deletes the
     * segment's START key (the key this editor's curve belongs to). Hosts that edit a value
     * without a single owning key leave it null and the editor is exactly as before.
     */
    onDeleteKeyframe: (() -> Unit)? = null
) {
    val curEasing by rememberUpdatedState(easing)
    val begin by rememberUpdatedState(onBegin)
    val change by rememberUpdatedState(onChange)

    Row(
        modifier = modifier.fillMaxSize().testTag("easing_editor"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ---- Curve graph ----
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 4.dp)
                .pointerInput(Unit) {
                    val padL = 26.dp.toPx()
                    val padB = 22.dp.toPx()
                    val padT = 4.dp.toPx()
                    val padR = 8.dp.toPx()
                    fun plot() = androidx.compose.ui.geometry.Rect(
                        padL, padT, size.width - padR, size.height - padB
                    )
                    fun toX(px: Float): Float {
                        val r = plot()
                        return ((px - r.left) / r.width).coerceIn(0f, 1f)
                    }
                    fun toY(py: Float): Float {
                        val r = plot()
                        val v = Y_MIN + (r.bottom - py) / r.height * (Y_MAX - Y_MIN)
                        return v.coerceIn(Y_MIN, Y_MAX)
                    }
                    var active = 0 // 1 = first handle, 2 = second handle
                    detectDragGestures(
                        onDragStart = { pos ->
                            val r = plot()
                            val e = curEasing
                            fun sx(x: Float) = r.left + x * r.width
                            fun sy(y: Float) = r.bottom - (y - Y_MIN) / (Y_MAX - Y_MIN) * r.height
                            val d1 = hypot(pos.x - sx(e.x1), pos.y - sy(e.y1))
                            val d2 = hypot(pos.x - sx(e.x2), pos.y - sy(e.y2))
                            val reach = 44.dp.toPx()
                            active = when {
                                d1 > reach && d2 > reach -> 0
                                d1 <= d2 -> 1
                                else -> 2
                            }
                            if (active != 0) begin()
                        },
                        onDrag = { change0, _ ->
                            if (active == 0) return@detectDragGestures
                            change0.consume()
                            val e = curEasing
                            val nx = toX(change0.position.x)
                            val ny = toY(change0.position.y)
                            change(if (active == 1) e.copy(x1 = nx, y1 = ny) else e.copy(x2 = nx, y2 = ny))
                        },
                        onDragEnd = { active = 0 },
                        onDragCancel = { active = 0 }
                    )
                }
        ) {
            val padL = 26.dp.toPx()
            val padB = 22.dp.toPx()
            val padT = 4.dp.toPx()
            val padR = 8.dp.toPx()
            val left = padL
            val top = padT
            val w = size.width - padL - padR
            val h = size.height - padT - padB
            val bottom = top + h
            fun sx(x: Float) = left + x * w
            fun sy(y: Float) = bottom - (y - Y_MIN) / (Y_MAX - Y_MIN) * h

            // Dashed 4 x 4 grid of the 0..1 box
            val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
            for (i in 0..4) {
                val f = i / 4f
                drawLine(GridColor, Offset(sx(f), sy(0f)), Offset(sx(f), sy(1f)), 2.dp.toPx(), pathEffect = dash)
                drawLine(GridColor, Offset(sx(0f), sy(f)), Offset(sx(1f), sy(f)), 2.dp.toPx(), pathEffect = dash)
            }

            // Where the playhead is inside this segment
            val px = sx(progress.coerceIn(0f, 1f))
            drawLine(Color(0xFFC9CBCF), Offset(px, top), Offset(px, bottom), 2.dp.toPx())

            // Faint lines from the end points to the control points
            val e = easing
            drawLine(Color(0xFF6B6E73), Offset(sx(0f), sy(0f)), Offset(sx(e.x1), sy(e.y1)), 1.5.dp.toPx())
            drawLine(Color(0xFF6B6E73), Offset(sx(1f), sy(1f)), Offset(sx(e.x2), sy(e.y2)), 1.5.dp.toPx())

            // The curve
            drawPath(curvePath(e, 64) { x, y -> Offset(sx(x), sy(y)) }, Color.White,
                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            // Control points
            for ((cx, cy) in listOf(e.x1 to e.y1, e.x2 to e.y2)) {
                val c = Offset(sx(cx), sy(cy))
                drawCircle(Color.White, 13.dp.toPx(), c)
                drawCircle(HandleColor, 10.dp.toPx(), c)
            }

            // Axis names
            drawIntoCanvas { cv ->
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = 0xFF5B5E63.toInt()
                    textSize = 15.dp.toPx()
                    isFakeBoldText = true
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                cv.nativeCanvas.drawText("Time", left + w / 2f, size.height - 3.dp.toPx(), paint)
                cv.nativeCanvas.save()
                cv.nativeCanvas.rotate(-90f, 12.dp.toPx(), top + h / 2f)
                cv.nativeCanvas.drawText(valueLabel, 12.dp.toPx(), top + h / 2f + 5.dp.toPx(), paint)
                cv.nativeCanvas.restore()
            }
        }
        if (onDeleteKeyframe != null) {
            androidx.compose.material3.Text(
                text = "Delete keyframe",
                color = com.example.ui.theme.AmDanger,
                fontSize = 11.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 30.dp, top = 8.dp)
                    .bouncyClickable { onDeleteKeyframe() }
                    .testTag("easing_delete_keyframe")
            )
        }
        }

        // ---- Ready-made curves ----
        if (showPresets) LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.width(150.dp).fillMaxHeight().padding(start = 8.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
        ) {
            items(Easing.PRESETS) { (name, preset) ->
                val selected = preset.sameAs(easing)
                Box(
                    modifier = Modifier
                        .size(66.dp)
                        .clip(RectangleShape)
                        .background(if (selected) TileBgSelected else TileBg)
                        .bouncyClickable(pressedScale = 0.92f, onClick = {
                            begin()
                            change(preset)
                        })
                        .testTag("easing_preset_$name"),
                    contentAlignment = Alignment.Center
                ) {
                    EasingTileCanvas(preset, Modifier.size(42.dp))
                }
            }
        }
    }
}

/** Small picture of a curve (dashed box + curve + its two control points). */
@Composable
private fun EasingTileCanvas(easing: Easing, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
        val pad = 3.dp.toPx()
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        fun sx(x: Float) = pad + x * w
        fun sy(y: Float) = pad + (1f - y) * h
        drawRect(
            GridColor, topLeft = Offset(pad, pad), size = androidx.compose.ui.geometry.Size(w, h),
            style = Stroke(1.5.dp.toPx(), pathEffect = dash)
        )
        drawPath(curvePath(easing, 40) { x, y -> Offset(sx(x), sy(y)) }, Color.White,
            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(HandleColor, 2.6.dp.toPx(), Offset(sx(easing.x1), sy(easing.y1)))
        drawCircle(HandleColor, 2.6.dp.toPx(), Offset(sx(easing.x2), sy(easing.y2)))
    }
}

/** The easing curve as a path; [map] turns a (time, progress) point into a pixel. */
private fun curvePath(e: Easing, steps: Int, map: (Float, Float) -> Offset): Path {
    val path = Path()
    for (i in 0..steps) {
        val (x, y) = e.pointAt(i / steps.toFloat())
        val o = map(x, y)
        if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
    }
    return path
}

/** The little curve icon of the header: draws the given easing (or an S-curve when there is none). */
fun DrawScope.drawEasingIcon(easing: Easing?, color: Color, strokeWidth: Float) {
    val e = easing ?: Easing(0.4f, 0f, 0.6f, 1f)
    val pad = strokeWidth
    val w = size.width - 2 * pad
    val h = size.height - 2 * pad
    val path = curvePath(e, 24) { x, y ->
        Offset(pad + x * w, pad + (1f - y.coerceIn(-0.2f, 1.2f).let { (it + 0.2f) / 1.4f * 1f }) * h)
    }
    drawPath(path, color, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
