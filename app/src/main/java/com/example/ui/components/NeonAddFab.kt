package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AmBaseDeep
import com.example.ui.theme.AmCyan
import com.example.ui.theme.AmCyanBright
import com.example.ui.theme.AmVioletDeep
import com.example.ui.util.bouncyClickable

/**
 * Round "add" button of the design reference: black disc, a big cyan ring open at the upper right,
 * two blue arcs inside, a white plus, and a soft cyan glow around it.
 * [size] is the outer diameter of the cyan ring (61 dp in the reference).
 */
@Composable
fun NeonAddFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 61.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .bouncyClickable(pressedScale = 0.92f, onClick = onClick)
            .testTag("add_layer_fab")
    ) {
        Canvas(Modifier.size(size)) {
            val d = this.size.width
            val u = d / 61f                      // 1 reference dp in px
            val c = Offset(d / 2f, d / 2f)

            // glow (drawn outside the bounds)
            drawCircle(
                brush = Brush.radialGradient(
                    0.50f to AmCyanBright.copy(alpha = 0.20f),
                    0.72f to AmCyanBright.copy(alpha = 0.07f),
                    1.00f to Color.Transparent,
                    center = c, radius = d * 0.95f
                ),
                radius = d * 0.95f, center = c
            )
            // dark disc
            drawCircle(AmBaseDeep, radius = 26.2f * u, center = c)

            // outer cyan ring: 280 degrees, gap at the upper right
            val ringStroke = 4.3f * u
            val ringR = 30.5f * u - ringStroke / 2f
            drawArc(
                color = AmCyanBright,
                startAngle = -25f, sweepAngle = 280f, useCenter = false,
                topLeft = Offset(c.x - ringR, c.y - ringR), size = Size(ringR * 2, ringR * 2),
                style = Stroke(ringStroke, cap = StrokeCap.Round)
            )

            // inner arcs (redesign Stage 2: the blue family becomes cyan->violet)
            val innerR = 18.5f * u
            val innerStroke = 2.4f * u
            val tl = Offset(c.x - innerR, c.y - innerR)
            val sz = Size(innerR * 2, innerR * 2)
            drawArc(
                color = AmVioletDeep,
                startAngle = 183f, sweepAngle = 117f, useCenter = false,
                topLeft = tl, size = sz,
                style = Stroke(innerStroke, cap = StrokeCap.Round)
            )
            drawArc(
                brush = Brush.sweepGradient(
                    0.00f to AmCyanBright,
                    0.25f to AmCyan,
                    0.46f to AmVioletDeep,
                    1.00f to AmVioletDeep,
                    center = c
                ),
                startAngle = 2f, sweepAngle = 162f, useCenter = false,
                topLeft = tl, size = sz,
                style = Stroke(innerStroke, cap = StrokeCap.Round)
            )

            // plus
            val arm = 7.2f * u
            val th = 3.1f * u
            drawLine(Color.White, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), th, StrokeCap.Round)
            drawLine(Color.White, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), th, StrokeCap.Round)
        }
    }
}
