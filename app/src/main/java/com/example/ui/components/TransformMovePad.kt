package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private val PadBg = Color(0xFF262A3B)
private val ValueBoxBg = Color(0xFF34385A)
private val ValueGreen = Color(0xFF1DE9B6)

/**
 * Horizontal + Vertical in one control: swipe anywhere on the pad to move the layer on both axes
 * at once. X / Y readouts sit at the top, corner brackets frame the pad.
 */
@Composable
fun TransformMovePad(
    x: Float,
    y: Float,
    onMove: (x: Float, y: Float) -> Unit,
    min: Float = -500f,
    max: Float = 500f,
    onMoveFinished: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val curX by rememberUpdatedState(x)
    val curY by rememberUpdatedState(y)
    val latestMove by rememberUpdatedState(onMove)
    val latestFinished by rememberUpdatedState(onMoveFinished)
    val density = LocalDensity.current.density

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(112.dp)
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(PadBg)
            .pointerInput(min, max) {
                detectDragGestures(
                    onDrag = { change, drag ->
                        change.consume()
                        // 1 dp of finger travel = 1 unit.
                        val nx = (curX + drag.x / density).coerceIn(min, max)
                        val ny = (curY + drag.y / density).coerceIn(min, max)
                        latestMove(nx, ny)
                    },
                    onDragEnd = { latestFinished() },
                    onDragCancel = { latestFinished() }
                )
            }
    ) {
        // Corner brackets
        Canvas(modifier = Modifier.fillMaxSize()) {
            val inset = 8.dp.toPx()
            val len = 12.dp.toPx()
            val sw = 1.8.dp.toPx()
            val c = Color(0xFF9096AD)
            fun corner(cx: Float, cy: Float, dx: Float, dy: Float) {
                drawLine(c, Offset(cx, cy), Offset(cx + dx * len, cy), sw, StrokeCap.Round)
                drawLine(c, Offset(cx, cy), Offset(cx, cy + dy * len), sw, StrokeCap.Round)
            }
            corner(inset, inset, 1f, 1f)
            corner(size.width - inset, inset, -1f, 1f)
            corner(inset, size.height - inset, 1f, -1f)
            corner(size.width - inset, size.height - inset, -1f, -1f)
        }

        // X / Y readouts
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(ValueBoxBg)
                .padding(horizontal = 18.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            Readout(value = x, label = "X")
            Readout(value = y, label = "Y")
        }

        Text(
            text = "Swipe here to move layer",
            color = Color(0xFF8D93AA),
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 22.dp)
        )
    }
}

@Composable
private fun Readout(value: Float, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
        Text(
            text = String.format(Locale.US, "%.2f", value),
            color = ValueGreen,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Text(text = label, color = Color(0xFF8D93AA), fontSize = 9.sp)
    }
}
