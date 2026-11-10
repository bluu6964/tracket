package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.model.TimelineClip
import kotlin.math.roundToInt

/**
 * The two gradient points on the canvas (Alight Motion style): a line with a coloured dot at the
 * start and a white dot at the end. Drag a dot to move that end of the gradient. It fills the box
 * of the layer, so it follows the layer's move / scale / rotation.
 */
@Composable
fun GradientHandleLayer(
    clip: TimelineClip,
    onPoints: (id: String, x1: Float, y1: Float, x2: Float, y2: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var wPx by remember { mutableFloatStateOf(1f) }
    var hPx by remember { mutableFloatStateOf(1f) }
    val cur by rememberUpdatedState(clip)
    val send by rememberUpdatedState(onPoints)
    val w by rememberUpdatedState(wPx)
    val h by rememberUpdatedState(hPx)
    val touch = 44.dp
    val dot = 20.dp

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { wPx = it.width.toFloat().coerceAtLeast(1f); hPx = it.height.toFloat().coerceAtLeast(1f) }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val a = Offset(clip.gradX1 * size.width, clip.gradY1 * size.height)
            val b = Offset(clip.gradX2 * size.width, clip.gradY2 * size.height)
            drawLine(Color.Black, a, b, strokeWidth = 4.dp.toPx())
            drawLine(Color.White, a, b, strokeWidth = 2.dp.toPx())
        }
        listOf(0, 1).forEach { idx ->
            val px = if (idx == 0) clip.gradX1 else clip.gradX2
            val py = if (idx == 0) clip.gradY1 else clip.gradY2
            Box(
                modifier = Modifier
                    .offset {
                        val r = touch.toPx() / 2f
                        IntOffset((px * wPx - r).roundToInt(), (py * hPx - r).roundToInt())
                    }
                    .size(touch)
                    .testTag(if (idx == 0) "gradient_point_start" else "gradient_point_end")
                    .pointerInput(idx) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val c = cur
                            val dx = drag.x / w
                            val dy = drag.y / h
                            if (idx == 0) {
                                send(c.id, (c.gradX1 + dx).coerceIn(0f, 1f), (c.gradY1 + dy).coerceIn(0f, 1f), c.gradX2, c.gradY2)
                            } else {
                                send(c.id, c.gradX1, c.gradY1, (c.gradX2 + dx).coerceIn(0f, 1f), (c.gradY2 + dy).coerceIn(0f, 1f))
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(dot + 4.dp)
                        .background(Color.Black, CircleShape)
                )
                Box(
                    Modifier
                        .size(dot)
                        .background(if (idx == 0) Color(clip.gradColor1) else Color.White, CircleShape)
                        .border(2.dp, Color.White, CircleShape)
                )
            }
        }
    }
}
