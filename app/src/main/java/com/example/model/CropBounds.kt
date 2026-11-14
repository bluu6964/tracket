package com.example.model

/**
 * The visible part of a layer's own box after Crop, for the canvas bounds family
 * (the selection frame and the snap rects — `FrameGeo` in the UI layer).
 *
 * Shape and text layers are NOT resized or re-centred by crop: the painters clip
 * the layer's own box to the crop window in place (LayerPainter's crop clip,
 * CropMask on the preview), so the visible part is a sub-rectangle of the box —
 * [width] x [height], centred at ([offX], [offY]) relative to the box centre, in
 * the caller's box units. Media layers do NOT use this: their cropped picture is
 * re-fitted and centred by the renderer, and their frame already follows it
 * (mediaVisibleSize in CanvasArea).
 */
data class CropVisibleBox(
    val width: Float,
    val height: Float,
    /** Centre of the visible part relative to the centre of the layer's box. */
    val offX: Float,
    val offY: Float
)

/**
 * The visible part of this layer's [boxW] x [boxH] box after crop, or null when
 * there is nothing to bound (a degenerate box). No crop — or a crop window
 * covering the whole box — returns the full box with zero offset.
 *
 * The crop fractions follow the painters' law exactly: the window size is the
 * stored percent coerced to the renderers' 5%..100% range, and its position is
 * the stored anchor percent of the leftover space, clamped inside the box the
 * way the native renderer clamps it. So even a stored "fully cropped away"
 * value bounds the same 5% sliver the renderers actually draw — never the
 * phantom pre-crop box, never a zero-size one.
 */
fun TimelineClip.cropVisibleBox(boxW: Float, boxH: Float): CropVisibleBox? {
    if (boxW <= 0f || boxH <= 0f) return null
    val cW = (cropWidthPercent / 100f).coerceIn(0.05f, 1f)
    val cH = (cropHeightPercent / 100f).coerceIn(0.05f, 1f)
    if (cW >= 0.999f && cH >= 0.999f) return CropVisibleBox(boxW, boxH, 0f, 0f)
    val l = ((1f - cW) * (cropHorizontalPercent / 100f)).coerceIn(0f, 1f - cW)
    val t = ((1f - cH) * (cropVerticalPercent / 100f)).coerceIn(0f, 1f - cH)
    val w = cW * boxW
    val h = cH * boxH
    if (w <= 0f || h <= 0f) return null
    return CropVisibleBox(w, h, (l + cW / 2f - 0.5f) * boxW, (t + cH / 2f - 0.5f) * boxH)
}
