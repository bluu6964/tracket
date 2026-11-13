package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.R

/**
 * The redesign ASSET KIT (build 162) — Sarowar rejected Stage 1's flat Compose
 * gradients as generic ("কেমন ai ai দেখাচ্ছে"), so the rich surfaces are now
 * program-rendered PNGs (see after-motion-dev/ui-redesign/kit/gen_kit.py, every
 * asset documented in ROADMAP) drawn UNDER code-drawn text / icons / interaction:
 *
 *  - Glow assets (ring / pill / thumb) are rendered on pure black and drawn with
 *    [BlendMode.Plus], so the black ground vanishes over the #0A0A12 base and
 *    only the volumetric bloom composites — the falloff flat brushes can't do.
 *  - Glass bases (disc / tile / pill) are RGBA and drawn normally; their baked
 *    dome light, rim and soft shadow give the depth; content goes on top.
 *  - [panelSheenStrip] lays the sheet-top rim light over a code-drawn sheet fill.
 *
 * Assets live in res/drawable-nodpi (density-independent, drawn at exact dp size).
 */

@Composable
fun KitGlowRing(modifier: Modifier = Modifier, violet: Boolean = false, alpha: Float = 1f) {
    val bmp = imageResource(if (violet) R.drawable.am_glow_ring_violet else R.drawable.am_glow_ring_mint)
    Canvas(modifier) {
        drawImage(bmp, dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            alpha = alpha, blendMode = BlendMode.Plus)
    }
}

@Composable
fun KitGlowPill(modifier: Modifier = Modifier, alpha: Float = 1f) {
    val bmp = imageResource(R.drawable.am_glow_pill_mint)
    Canvas(modifier) {
        drawImage(bmp, dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            alpha = alpha, blendMode = BlendMode.Plus)
    }
}

@Composable
fun KitGlassDisc(modifier: Modifier = Modifier) {
    val bmp = imageResource(R.drawable.am_glass_disc)
    Canvas(modifier) {
        drawImage(bmp, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
    }
}

@Composable
fun KitGlassTile(modifier: Modifier = Modifier) {
    val bmp = imageResource(R.drawable.am_glass_tile)
    Canvas(modifier) {
        drawImage(bmp, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
    }
}

@Composable
fun KitGlassPill(modifier: Modifier = Modifier) {
    val bmp = imageResource(R.drawable.am_glass_pill)
    Canvas(modifier) {
        drawImage(bmp, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
    }
}

/** The glowing slider thumb bitmap, for Canvas-based controls (dial slider, playhead). */
@Composable
fun kitThumbBitmap(): ImageBitmap = imageResource(R.drawable.am_glow_thumb)

/** Draws the kit thumb additively, centred on [center], [radiusPx] across the halo. */
fun DrawScope.drawKitThumb(bmp: ImageBitmap, center: Offset, radiusPx: Float) {
    val d = (radiusPx * 2).toInt()
    drawImage(
        bmp,
        dstOffset = IntOffset((center.x - radiusPx).toInt(), (center.y - radiusPx).toInt()),
        dstSize = IntSize(d, d),
        blendMode = BlendMode.Plus
    )
}

/**
 * The sheet-top light: am_panel_sheen stretched across the top of a code-drawn
 * sheet (rim band + centre dome). Place AFTER the sheet's background modifier;
 * it draws over the fill, under the content.
 */
fun Modifier.panelSheenStrip(): Modifier = composed {
    val bmp = imageResource(R.drawable.am_panel_sheen)
    drawWithContent {
        val h = minOf(30.dp.toPx(), size.height)
        drawImage(bmp, dstSize = IntSize(size.width.toInt(), h.toInt()))
        drawContent()
    }
}
