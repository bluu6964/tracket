package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.util.bouncyClickable

/**
 * "Add" button (B/W language). It was a flat white disc with a black plus until
 * Sarowar's boxless order 2026-11-14 (build 183) — now BARE like every other
 * button: just the plus glyph, drawn white with a dark under-stroke so it stays
 * readable over bright timeline clips. The touch box ([size], 61 dp in the
 * original design reference) is unchanged.
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

            // BARE plus (build 183): a dark under-stroke pass, then the white glyph.
            val arm = 10.5f * u
            val th = 3.6f * u
            drawLine(Color.Black.copy(alpha = 0.55f), Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), th + 2.4f * u, StrokeCap.Round)
            drawLine(Color.Black.copy(alpha = 0.55f), Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), th + 2.4f * u, StrokeCap.Round)
            drawLine(Color.White, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), th, StrokeCap.Round)
            drawLine(Color.White, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), th, StrokeCap.Round)
        }
    }
}
