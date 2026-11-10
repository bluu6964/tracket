package com.example.ui.util

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Soft neon glow drawn *behind* the composable (it spreads outside the bounds).
 * Stacked translucent rounded rects → cheap falloff, works on every API level.
 */
fun Modifier.neonGlow(
    color: Color,
    radius: Dp = 12.dp,
    corner: Dp = 8.dp,
    alpha: Float = 0.45f
): Modifier = drawBehind {
    val r = radius.toPx()
    val c = corner.toPx()
    val steps = 9
    for (i in steps downTo 1) {
        val grow = r * i / steps
        drawRoundRect(
            color = color.copy(alpha = alpha / steps),
            topLeft = Offset(-grow, -grow),
            size = Size(size.width + 2 * grow, size.height + 2 * grow),
            cornerRadius = CornerRadius(c + grow)
        )
    }
}

/** White playhead line with a faint halo. [x] is the line centre in px. */
fun DrawScope.drawGlowLine(x: Float, color: Color = Color(0xFFFFFFFF)) {
    drawLine(color.copy(alpha = 0.16f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 7.dp.toPx())
    drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
}
