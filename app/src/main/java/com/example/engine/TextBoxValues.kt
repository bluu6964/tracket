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
 * An authored width is the widest LINE Alight allows, and both engines add the padding OUTSIDE
 * that line width (their drawn box is longest-line + 2 x padH), so the padding is NOT subtracted
 * from an authored width here. Until build 135 it was, which shrank every authored box by
 * 2 x padH = 0.5 em of the layout font: the Graph counter (wrapWidth 239, size 48 x scale 1.268
 * in a 2005-wide scene — an allowance of 239 / 190.6 = 1.2536 em) got a 0.7536 em layout box and
 * wrapped its two-digit values onto two lines on device (round 8). With the padding left out, the
 * box in em is exactly Alight's allowance (wrapWidth / em), at any layer or group scale.
 *
 * The default box (no authored width) is a layout-space design value of the app and keeps its
 * historical meaning — a share of the frame with the padding inside it, not divided by the
 * scale — so app-made text renders exactly as before.
 */
internal fun textLayoutBoxWidth(frameW: Float, wrapFrac: Float, scale: Float, padH: Float): Float {
    val authored = wrapFrac > 0f
    val frac = if (authored) wrapFrac else 0.9f
    val s = if (authored) scale.coerceAtLeast(0.01f) else 1f
    val box = frameW * frac / s
    return (if (authored) box else box - 2f * padH).coerceAtLeast(1f)
}
