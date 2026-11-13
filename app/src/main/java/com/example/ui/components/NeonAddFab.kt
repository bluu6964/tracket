package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.util.bouncyClickable

/**
 * Round "add" button (B/W language): a flat WHITE disc with a black plus — the same
 * white-emphasis as the Export pill. (Builds ≤164 drew a neon cyan ring + blue arcs +
 * glow here; Sarowar's 2026-11-13 B/W order scrapped that look, the button's size,
 * place and action are unchanged.)
 * [size] is the outer diameter (61 dp in the original design reference).
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

            // flat white disc with a hairline dark edge
            drawCircle(Color.White, radius = 26.2f * u, center = c)
            drawCircle(
                Color.Black.copy(alpha = 0.35f),
                radius = 26.2f * u,
                center = c,
                style = Stroke(1f * u)
            )

            // plus, in black ink on the white disc
            val arm = 8.5f * u
            val th = 3.4f * u
            drawLine(Color.Black, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), th, StrokeCap.Round)
            drawLine(Color.Black, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), th, StrokeCap.Round)
        }
    }
}
