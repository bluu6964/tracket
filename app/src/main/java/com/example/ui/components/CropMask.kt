package com.example.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.model.TimelineClip

/** The part of a layer that stays visible after Crop, as fractions (0..1) of its own box. */
class CropFractions(val l: Float, val t: Float, val r: Float, val b: Float)

fun TimelineClip.cropFractions(): CropFractions? {
    val cW = (cropWidthPercent / 100f).coerceIn(0.05f, 1f)
    val cH = (cropHeightPercent / 100f).coerceIn(0.05f, 1f)
    if (cW >= 0.999f && cH >= 0.999f) return null
    val l = (1f - cW) * (cropHorizontalPercent / 100f)
    val t = (1f - cH) * (cropVerticalPercent / 100f)
    return CropFractions(l, t, l + cW, t + cH)
}

private class CropRectShape(val f: CropFractions) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(f.l * size.width, f.t * size.height, f.r * size.width, f.b * size.height))
}

/** Shape and text layers: Crop hides everything outside the crop box (the layer is not resized). */
fun Modifier.cropMask(clip: TimelineClip): Modifier {
    val f = clip.cropFractions() ?: return this
    val shape = CropRectShape(f)
    return this.graphicsLayer {
        this.clip = true
        this.shape = shape
    }
}
