package com.example.ui.util

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import com.example.model.BlendKind
import com.example.model.BlendModes
import com.example.model.BlendSpec

private const val LUMA_R = 0.2126f
private const val LUMA_G = 0.7152f
private const val LUMA_B = 0.0722f

/** Compose blend mode of a colour blend (matte modes use [matteFilter] instead).
 *  "Subtract" and "Soft Overlay" (Alight Motion modes) have no Compose equivalent and fall
 *  back to SrcOver here: the editor canvas shows them unblended, while the native preview
 *  and the export (cpp/engine/renderer.cpp blendFn) apply them exactly. */
fun composeBlendMode(spec: BlendSpec): BlendMode = when (spec.name) {
    "Darken" -> BlendMode.Darken
    "Multiply" -> BlendMode.Multiply
    "Color Burn" -> BlendMode.ColorBurn
    "Lighten" -> BlendMode.Lighten
    "Screen" -> BlendMode.Screen
    "Color Dodge" -> BlendMode.ColorDodge
    "Add" -> BlendMode.Plus
    "Overlay" -> BlendMode.Overlay
    "Soft Light" -> BlendMode.Softlight
    "Hard Light" -> BlendMode.Hardlight
    "Difference" -> BlendMode.Difference
    "Exclusion" -> BlendMode.Exclusion
    "Hue" -> BlendMode.Hue
    "Saturation" -> BlendMode.Saturation
    "Color" -> BlendMode.Color
    "Luminosity" -> BlendMode.Luminosity
    else -> BlendMode.SrcOver
}

/**
 * Matte modes cut the layers beneath with "destination in": what is kept is
 * `1 - amount + amount * matte`, where matte is the layer's alpha / luma (or the inverse).
 * The factor is written into the alpha channel by this colour filter.
 * (Colour-matrix offsets are in the 0..255 range.)
 */
fun matteFilter(spec: BlendSpec, amount: Float): ColorFilter {
    val a = amount.coerceIn(0f, 1f)
    val m = FloatArray(20)
    // R, G, B rows stay identity (they do not matter for "destination in").
    m[0] = 1f; m[6] = 1f; m[12] = 1f
    when (spec.name) {
        "Alpha Matte" -> { m[18] = a; m[19] = 255f * (1f - a) }
        "Mask Invert" -> { m[18] = -a; m[19] = 255f }
        "Luma" -> {
            m[15] = a * LUMA_R; m[16] = a * LUMA_G; m[17] = a * LUMA_B
            m[19] = 255f * (1f - a)
        }
        else -> { // Luma Invert
            m[15] = -a * LUMA_R; m[16] = -a * LUMA_G; m[17] = -a * LUMA_B
            m[19] = 255f
        }
    }
    return ColorFilter.colorMatrix(ColorMatrix(m))
}

/**
 * Preview of a layer's opacity + blending mode. Put it on a box that covers the whole canvas,
 * and make sure the canvas is drawn into an offscreen layer (see CanvasArea) so the blend sees
 * everything that lies beneath.
 *
 * [opacity] is 0..1.
 */
fun Modifier.layerBlend(blendMode: String, opacity: Float): Modifier {
    val spec = BlendModes.find(blendMode)
    val a = opacity.coerceIn(0f, 1f)
    if (spec.isNormal) {
        return if (a >= 0.999f) this
        else this.graphicsLayer {
            alpha = a
            compositingStrategy = CompositingStrategy.Offscreen
        }
    }
    return this.drawWithContent {
        drawIntoCanvas { canvas ->
            val paint = Paint()
            if (spec.kind == BlendKind.MATTE) {
                paint.blendMode = BlendMode.DstIn
                paint.colorFilter = matteFilter(spec, a)
            } else {
                paint.blendMode = composeBlendMode(spec)
                paint.alpha = a
            }
            canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
            drawContent()
            canvas.restore()
        }
    }
}
