package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AmTextDim
import com.example.ui.theme.AmTextFaint
import com.example.ui.theme.TextPrimary
import com.example.ui.util.bouncyClickable
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The freehand drawing tool (build 166, item 1): the canvas overlay that captures one
 * freehand stroke. Points are reported in frame fractions (0..1) — the input of
 * [com.example.model.VectorDraw.build], which turns the finished stroke into a PATH
 * shape layer. A new touch starts a new stroke (the previous one is replaced); the
 * stroke stays drawn while the user decides (Smooth / Close / Width in the panel).
 */
@Composable
fun DrawVectorOverlay(
    points: List<Pair<Float, Float>>,
    strokeWidthDp: Float,
    closed: Boolean,
    onStroke: (List<Pair<Float, Float>>) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density
    val strokeNow by rememberUpdatedState(onStroke)
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("draw_vector_overlay")
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent()
                        val first = down.changes.firstOrNull { it.pressed } ?: continue
                        var pts = listOf(
                            (first.position.x / size.width).coerceIn(0f, 1f) to
                                (first.position.y / size.height).coerceIn(0f, 1f)
                        )
                        strokeNow(pts)
                        first.consume()
                        var drawing = true
                        while (drawing) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.first()
                            if (ch.pressed) {
                                val fx = (ch.position.x / size.width).coerceIn(0f, 1f)
                                val fy = (ch.position.y / size.height).coerceIn(0f, 1f)
                                val last = pts.last()
                                // Keep the stored stroke light: a new point every ~3 px of travel.
                                if (hypot((fx - last.first) * size.width, (fy - last.second) * size.height) >= 3f) {
                                    pts = pts + (fx to fy)
                                    strokeNow(pts)
                                }
                                ch.consume()
                            } else {
                                drawing = false
                            }
                        }
                    }
                }
            }
    ) {
        if (points.isNotEmpty()) {
            val path = Path()
            points.forEachIndexed { i, (fx, fy) ->
                val o = Offset(fx * size.width, fy * size.height)
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            if (closed && points.size >= 3) path.close()
            if (closed && points.size >= 3) {
                drawPath(path, Color.White.copy(alpha = 0.25f))
            }
            drawPath(
                path, Color.White,
                style = Stroke(
                    width = (strokeWidthDp * density).coerceAtLeast(1f),
                    cap = StrokeCap.Round, join = StrokeJoin.Round
                )
            )
        }
    }
}

/**
 * The draw panel (same place as the Masks / Effects panels): Smooth and Closed-shape
 * switches, the stroke width, and Clear / Cancel / Done. Flat B/W like every panel.
 */
@Composable
fun DrawVectorPanel(
    smooth: Boolean,
    onSmooth: (Boolean) -> Unit,
    closed: Boolean,
    onClosed: (Boolean) -> Unit,
    widthDp: Float,
    onWidth: (Float) -> Unit,
    hasStroke: Boolean,
    onClear: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            "Draw on the picture — one stroke becomes a path layer you can move, colour and effect like any shape.",
            color = AmTextDim, fontSize = 12.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip("Smooth", smooth, onClick = { onSmooth(!smooth) }, tag = "draw_smooth_chip")
            GlassChip("Closed shape", closed, onClick = { onClosed(!closed) }, tag = "draw_closed_chip")
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Width", color = TextPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp))
            Slider(
                value = widthDp,
                onValueChange = onWidth,
                valueRange = 1f..30f,
                modifier = Modifier.weight(1f).testTag("draw_width_slider")
            )
            Spacer(Modifier.width(10.dp))
            Text("${widthDp.roundToInt()}", color = AmTextDim, fontSize = 12.sp)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box2Button("Clear", enabled = hasStroke, onClick = onClear)
            Spacer(Modifier.weight(1f))
            Box2Button("Cancel", enabled = true, onClick = onCancel)
            Spacer(Modifier.width(10.dp))
            GradientPillButton(onClick = onDone, tag = "draw_done_button") {
                Text(
                    "Done", color = TextPrimary,
                    fontSize = 14.sp, fontWeight = FontWeight.Bold
                )
            }
        }
        if (!hasStroke) {
            Spacer(Modifier.height(4.dp))
            Text("Nothing drawn yet", color = AmTextFaint, fontSize = 11.sp)
        }
    }
}

/** Small flat text button of the draw panel. */
@Composable
private fun Box2Button(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .bouncyClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (enabled) TextPrimary else AmTextFaint,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold
        )
    }
}
