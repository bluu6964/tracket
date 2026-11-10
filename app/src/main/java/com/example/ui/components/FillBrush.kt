package com.example.ui.components

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import com.example.engine.FillImages
import com.example.engine.buildGradientShader
import com.example.engine.buildImageShader
import com.example.model.FILL_GRADIENT
import com.example.model.FILL_IMAGE
import com.example.model.FILL_NONE
import com.example.model.TimelineClip

/** A brush whose shader is built for the exact size it is drawn at (a shape or a block of text). */
class LayerFillBrush(private val make: (Float, Float) -> Shader) : ShaderBrush() {
    override fun createShader(size: Size): Shader =
        make(size.width.coerceAtLeast(1f), size.height.coerceAtLeast(1f))
}

/** The solid colour of a shape layer (same fallbacks the shape drawing always used). */
fun TimelineClip.solidFillColor(): Color {
    val raw = if (textColor != 0L) textColor else color
    val a = ((raw shr 24) and 0xFF).toInt()
    val r = ((raw shr 16) and 0xFF).toInt()
    val g = ((raw shr 8) and 0xFF).toInt()
    val b = (raw and 0xFF).toInt()
    return if (a == 0 && r == 0 && g == 0 && b == 0) Color(0xFFABE41D)
    else Color(r, g, b, if (a == 0) 255 else a)
}

fun TimelineClip.gradientBrush(): Brush {
    val c = this
    return LayerFillBrush { w, h -> buildGradientShader(c, w, h) }
}

/** Picture fill; nothing is drawn until the picture has finished loading. */
fun TimelineClip.imageBrush(): Brush {
    val uri = fillImageUri ?: return SolidColor(Color.Transparent)
    val bmp = FillImages.peek(uri) ?: return SolidColor(Color.Transparent)
    val mode = fillImageMode
    return LayerFillBrush { w, h -> buildImageShader(bmp, uri, mode, w.toInt(), h.toInt()) }
}

/** What the layer is filled with right now: nothing, one colour, the gradient or a picture. */
fun TimelineClip.fillBrush(): Brush = when (fillMode) {
    FILL_NONE -> SolidColor(Color.Transparent)
    FILL_GRADIENT -> gradientBrush()
    FILL_IMAGE -> imageBrush()
    else -> SolidColor(solidFillColor())
}
