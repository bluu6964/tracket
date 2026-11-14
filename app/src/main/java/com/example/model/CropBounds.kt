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

/**
 * The visible part of a layer's box as a sub-rectangle in box-local coordinates
 * (origin at the box's top-left corner, the box's own units) — the same window
 * [cropVisibleBox] bounds, re-expressed for the Compose touch boxes (build 186):
 * the shape drag box in CanvasArea and TextLayerOnCanvas's layout box clip their
 * hit-testing to this rect, so a touch on the cropped-away part of the stored
 * box does not grab the layer and falls through to whatever is beneath.
 * [contains] is the canonical statement of that rule.
 */
data class CropVisibleRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    /** Half-open, like every layout hit test: the left / top edges are in, the right / bottom out. */
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x < right && y >= top && y < bottom
}

/**
 * The visible part of this layer's [boxW] x [boxH] box after crop, as a
 * box-local rect (see [CropVisibleRect]), or null exactly when [cropVisibleBox]
 * has nothing to bound. No crop returns the whole box at (0, 0).
 */
fun TimelineClip.cropVisibleRect(boxW: Float, boxH: Float): CropVisibleRect? {
    val v = cropVisibleBox(boxW, boxH) ?: return null
    // cropVisibleBox already clamps the window inside the box; this second clamp only
    // absorbs float dust in the centre-offset arithmetic, so the rect is exactly
    // contained in the box (never a hair outside it) at the anchors.
    return CropVisibleRect(
        left = ((boxW - v.width) / 2f + v.offX).coerceIn(0f, boxW - v.width),
        top = ((boxH - v.height) / 2f + v.offY).coerceIn(0f, boxH - v.height),
        width = v.width,
        height = v.height
    )
}
