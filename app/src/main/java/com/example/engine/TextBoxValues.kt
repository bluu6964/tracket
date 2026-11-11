package com.example.engine

/**
 * The width of a text layer's layout box, in the text engine's layout units.
 *
 * Both text painters (the C++ engine via [NativeTextPainter] and the Canvas fallback in LayerPainter)
 * lay text out at the UNSCALED font size (fontPx = 11 % of the frame width) and apply the layer's
 * scale when the glyphs are drawn, so every layout length — this box included — lives in that unscaled
 * space. An authored wrap width ([com.example.model.TimelineClip.textWrapFrac], set by the Alight
 * importer from AM's `wrapWidth`) is a share of the frame AS SEEN ON SCREEN, so it must be converted
 * into layout units by dividing by the layer's scale. Until build 93 the painters used the fraction
 * raw, which shrank the box by the scale factor: ST Intro's end-card "Channel Name" (wrap 512 of
 * 1920, scale 0.268) got a ~110 px box on screen and wrapped into a two-letter column.
 *
 * The default box (no authored width) is a layout-space design value of the app and keeps its
 * historical meaning — it is NOT divided — so app-made text renders exactly as before.
 */
internal fun textLayoutBoxWidth(frameW: Float, wrapFrac: Float, scale: Float, padH: Float): Float {
    val authored = wrapFrac > 0f
    val frac = if (authored) wrapFrac else 0.9f
    val s = if (authored) scale.coerceAtLeast(0.01f) else 1f
    return (frameW * frac / s - 2f * padH).coerceAtLeast(1f)
}
