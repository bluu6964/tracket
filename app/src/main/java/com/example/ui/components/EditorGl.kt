package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.engine.UiLabelAtlas
import com.example.engine.UiLabelStyle
import com.example.engine.rememberUiAtlas
import com.example.model.ChromeGeometry
import com.example.model.EditorGeometry
import com.example.model.PanelGeometry
import com.example.model.SheetGeometry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/**
 * Build 252 (UI migration stage 5): the value editors' shared GL
 * building blocks — a panel body, a single text line, a pill strip
 * and the stock Material slider's drawn form — drawn on native GL
 * surfaces per [EditorGeometry], with Compose keeping layout, touch
 * boxes, semantics and state wiring (the stage-2/3/4 pattern).
 */

/**
 * A panel body drawn in GL: a rounded-rect fill (+ optional hairline
 * stroke, + optional drag-handle pill at the top centre) behind
 * [content]. [topOnlyCorners] rounds only the top corners (the fill
 * rect extends below the surface, so the bottom corners fall outside
 * it) — the full-screen editor panels' shape.
 */
@Composable
internal fun GlPanelBody(
    modifier: Modifier = Modifier,
    cornerDp: Float,
    fillArgb: Int,
    borderArgb: Int? = null,
    topOnlyCorners: Boolean = false,
    handleArgb: Int? = null,
    handleTopDp: Float = 0f,
    content: @Composable () -> Unit
) {
    Box(modifier) {
        NativeVectorCanvas(
            modifier = Modifier.fillMaxSize(),
            clearArgb = 0,
            drawKey = listOf(cornerDp, fillArgb, borderArgb, topOnlyCorners, handleArgb, handleTopDp),
            buildCmds = { w, h, dd ->
                val r = cornerDp * dd
                val strokeW = if (borderArgb != null) 1f * dd else 0f
                if (topOnlyCorners) {
                    addRoundRect(
                        w / 2f, (h + r) / 2f, w, h + r, r,
                        fillArgb, strokeW, borderArgb ?: 0
                    )
                } else {
                    addRoundRect(w / 2f, h / 2f, w, h, r, fillArgb, strokeW, borderArgb ?: 0)
                }
                if (handleArgb != null) {
                    addRoundRect(
                        w / 2f, (handleTopDp + 1.5f) * dd,
                        28f * dd, 3f * dd, 1.5f * dd, handleArgb
                    )
                }
            }
        )
        content()
    }
}

/**
 * One line of text drawn in GL inside the host's box: the font box is
 * centred vertically in the box, the run is placed per [align], and
 * the text is ellipsized at the box width when [ellipsize] is set
 * (the Compose Text's maxLines-1 ellipsis rule).
 */
@Composable
internal fun GlTextLine(
    text: String,
    style: UiLabelStyle,
    colorArgb: Int,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Start,
    ellipsize: Boolean = false
) {
    val density = LocalDensity.current.density
    val atlas = rememberUiAtlas()
    val pad = UiLabelAtlas.PAD.toFloat()
    BoxWithConstraints(modifier = modifier) {
        val wPx = maxWidth.value * density
        val hPx = maxHeight.value * density
        fun resolveOnce(): UiLabelAtlas.Entry? {
            val drawn = if (ellipsize) {
                ChromeGeometry.ellipsize(text, wPx) { atlas.measure(it, style) }
            } else text
            return atlas.resolve(drawn, style)
        }
        var entry = resolveOnce()
        if (atlas.overflowed) {
            atlas.reset()
            entry = resolveOnce()
        }
        val e = entry
        // Build 267: the box hugs the drawn run's advance (the
        // Compose Text form — see EditorGeometry.LabelLine). A
        // fillMaxSize canvas here took the whole incoming max width,
        // so as a Row child this line starved every sibling after it
        // (the Text editor's sliders / swatches / pills vanished).
        // Width-pinned hosts (min == max) keep their exact width.
        val boxWPx = EditorGeometry.LabelLine.boxWPx(
            e?.advance ?: wPx, constraints.minWidth.toFloat(), wPx
        )
        Box(
            Modifier
                .width((boxWPx / density).dp)
                .fillMaxHeight()
        ) {
            NativeVectorCanvas(
                modifier = Modifier.fillMaxSize(),
                clearArgb = 0,
                atlas = atlas,
                drawKey = listOf(e, colorArgb, wPx, hPx, atlas.generation),
                buildCmds = { w, h, _ ->
                    if (e != null) {
                        val textH = atlas.textHeightPx(style)
                        val fm = atlas.fontMetrics(style)
                        val top = EditorGeometry.centredTextTopPx(0f, h, textH)
                        val baseline = PanelGeometry.lineBaselinePx(top, textH, fm.ascent, fm.descent)
                        val originX = when (align) {
                            TextAlign.Center -> EditorGeometry.centredOriginPx(w / 2f, e.advance)
                            TextAlign.End -> w - e.advance
                            else -> 0f
                        }
                        val (dstX, dstY) = EditorGeometry.textDst(originX, baseline, e.bearing, e.base, pad)
                        addText(
                            e.index, dstX, dstY,
                            0f, 0f, e.rectW.toFloat(), e.rectH.toFloat(),
                            tintArgb = colorArgb
                        )
                    }
                }
            )
        }
    }
}

/**
 * A block of text drawn in GL inside the host's box: paragraphs split
 * on '\n', each wrapped greedily at the box width (Android's rule,
 * [PanelGeometry.wrapGreedy]), every line centred horizontally, and
 * the whole block centred vertically — the editors' hint / empty
 * state texts' form.
 */
@Composable
internal fun GlTextBlock(
    text: String,
    style: UiLabelStyle,
    colorArgb: Int,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Center,
    /** The Compose lineHeight, when the host Text set one (sp). */
    linePitchSp: Float? = null
) {
    val density = LocalDensity.current.density
    val atlas = rememberUiAtlas()
    val pad = UiLabelAtlas.PAD.toFloat()
    BoxWithConstraints(modifier = modifier) {
        val wPx = maxWidth.value * density
        fun resolveBlock(): List<UiLabelAtlas.Entry?> {
            val lines = text.split('\n').flatMap { para ->
                PanelGeometry.wrapGreedy(para, wPx, Int.MAX_VALUE) { atlas.measure(it, style) }
            }
            return lines.map { atlas.resolve(it, style) }
        }
        var lineEntries = resolveBlock()
        if (atlas.overflowed) {
            atlas.reset()
            lineEntries = resolveBlock()
        }
        val entries = lineEntries
        val textH = atlas.textHeightPx(style)
        val pitch = if (linePitchSp != null) {
            linePitchSp * density * LocalDensity.current.fontScale
        } else {
            textH.toFloat()
        }
        val blockH = pitch * entries.size
        // A wrap-height host (an old Text's place) sizes the block to
        // its lines; a bounded host centres the block inside itself.
        val bounded = maxHeight != androidx.compose.ui.unit.Dp.Infinity
        val boxH = if (bounded) maxHeight else (blockH / density).dp
        Box(
            Modifier
                .width(maxWidth)
                .height(boxH)
        ) {
            NativeVectorCanvas(
                modifier = Modifier.fillMaxSize(),
                clearArgb = 0,
                atlas = atlas,
                drawKey = listOf(entries, colorArgb, wPx, blockH, bounded, atlas.generation),
                buildCmds = { w, h, _ ->
                    val fm = atlas.fontMetrics(style)
                    var lineTop = (h - blockH) / 2f
                    for (e in entries) {
                        if (e != null) {
                            val baseline = PanelGeometry.lineBaselinePx(lineTop, pitch, fm.ascent, fm.descent)
                            val originX = when (align) {
                                TextAlign.Center -> EditorGeometry.centredOriginPx(w / 2f, e.advance)
                                TextAlign.End, TextAlign.Right -> w - e.advance
                                else -> 0f
                            }
                            val (dstX, dstY) = EditorGeometry.textDst(originX, baseline, e.bearing, e.base, pad)
                            addText(
                                e.index, dstX, dstY,
                                0f, 0f, e.rectW.toFloat(), e.rectH.toFloat(),
                                tintArgb = colorArgb
                            )
                        }
                        lineTop += pitch
                    }
                }
            )
        }
    }
}

/** One item of a [GlPillStrip]. */
internal class GlPillItem(
    val label: String,
    val selected: Boolean,
    val tag: String? = null,
    val description: String? = null,
    val onClick: () -> Unit
)

/**
 * A strip of hug-content pills drawn on ONE GL surface: each pill
 * sizes to its label (advance + padding, an optional minimum height),
 * sits at its own height centred in the strip, and keeps a Compose
 * tap box with bouncyClickable's press contract (ChromePressBox, the
 * press scale shared into the GL draw). [scrollable] wraps the strip
 * in a horizontal scroll row (the LazyRow hosts' replacement — the
 * item sets are small and fixed).
 */
@Composable
internal fun GlPillStrip(
    items: List<GlPillItem>,
    labelStyle: (Boolean) -> UiLabelStyle,
    textArgb: (Boolean) -> Int,
    fillArgb: (Boolean) -> Int,
    borderArgb: (Boolean) -> Int?,
    padHDp: Float,
    padVDp: Float,
    gapDp: Float,
    modifier: Modifier = Modifier,
    minHDp: Float = 0f,
    pressScale: Float = 0.94f,
    scrollable: Boolean = false,
    /** Horizontal content padding inside the strip (the LazyRow hosts'
     *  contentPadding): items start this far in, and it counts into
     *  the strip's content width. */
    contentPadHDp: Float = 0f
) {
    val density = LocalDensity.current.density
    val atlas = rememberUiAtlas()
    val pad = UiLabelAtlas.PAD.toFloat()

    fun resolveAll(): List<UiLabelAtlas.Entry?> =
        items.map { atlas.resolve(it.label, labelStyle(it.selected)) }
    var entries = resolveAll()
    if (atlas.overflowed) {
        atlas.reset()
        entries = resolveAll()
    }

    val scales = remember(items.map { it.label }) { items.map { Animatable(1f) } }
    val scaleValues = scales.map { it.value }

    val widths = EditorGeometry.PillStrip.itemWidthsPx(entries.map { it?.advance ?: 0f }, padHDp, density)
    val lefts = EditorGeometry.PillStrip.itemLeftsPx(widths, gapDp, density, startPx = contentPadHDp * density)
    val heights = items.mapIndexed { i, item ->
        val textH = atlas.textHeightPx(labelStyle(item.selected))
        EditorGeometry.PillStrip.itemHPx(textH, padVDp, minHDp, density)
    }
    val stripH = heights.maxOrNull() ?: 0f
    val contentW = EditorGeometry.PillStrip.contentWPx(widths, gapDp, density) +
        2f * contentPadHDp * density

    val surface: @Composable () -> Unit = {
        Box(
            Modifier
                .width((contentW / density).dp)
                .height((stripH / density).dp)
        ) {
            NativeVectorCanvas(
                modifier = Modifier.fillMaxSize(),
                clearArgb = 0,
                atlas = atlas,
                drawKey = listOf(entries, scaleValues, widths, heights, atlas.generation),
                buildCmds = { _, _, dd ->
                    items.forEachIndexed { i, item ->
                        val pillH = heights[i]
                        val pillTop = (stripH - pillH) / 2f
                        val cx = lefts[i] + widths[i] / 2f
                        val cy = pillTop + pillH / 2f
                        val border = borderArgb(item.selected)
                        setScaleAbout(cx, cy, scales[i].value)
                        addRoundRect(
                            cx, cy, widths[i], pillH, pillH / 2f,
                            fillArgb(item.selected),
                            if (border != null) 1f * dd else 0f, border ?: 0
                        )
                        val e = entries[i]
                        if (e != null) {
                            val style = labelStyle(item.selected)
                            val textH = atlas.textHeightPx(style)
                            val fm = atlas.fontMetrics(style)
                            val top = EditorGeometry.centredTextTopPx(pillTop, pillH, textH)
                            val baseline = PanelGeometry.lineBaselinePx(top, textH, fm.ascent, fm.descent)
                            val originX = EditorGeometry.centredOriginPx(cx, e.advance)
                            val (dstX, dstY) = EditorGeometry.textDst(originX, baseline, e.bearing, e.base, pad)
                            addText(
                                e.index, dstX, dstY,
                                0f, 0f, e.rectW.toFloat(), e.rectH.toFloat(),
                                tintArgb = textArgb(item.selected)
                            )
                        }
                        clearTransform()
                    }
                }
            )
            items.forEachIndexed { i, item ->
                val pillTop = (stripH - heights[i]) / 2f
                ChromePressBox(
                    scales[i], pressScale, item.onClick,
                    Modifier
                        .offset(x = (lefts[i] / density).dp, y = (pillTop / density).dp)
                        .width((widths[i] / density).dp)
                        .height((heights[i] / density).dp),
                    tag = item.tag,
                    description = item.description ?: item.label
                )
            }
        }
    }

    if (scrollable) {
        Row(modifier = modifier.horizontalScroll(rememberScrollState())) { surface() }
    } else {
        Box(modifier = modifier) { surface() }
    }
}

/**
 * The stock Material 3 slider (material3 1.3.0) re-drawn in GL, with
 * the library's own gesture law (see [EditorGeometry.M3Slider]):
 * press anchors an offset, a tap applies it on lift (value from the
 * DOWN position), a drag trails the finger by the touch slop, and
 * onValueChangeFinished fires on drag stop / tap / drag cancel. The
 * progress semantics the library exposes are kept on the host box.
 */
@Composable
internal fun GlSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    min: Float,
    max: Float,
    onValueChangeFinished: (() -> Unit)?,
    thumbArgb: Int,
    activeArgb: Int,
    inactiveArgb: Int,
    modifier: Modifier = Modifier
) {
    val g = EditorGeometry.M3Slider
    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onValueChange)
    val latestOnFinished by rememberUpdatedState(onValueChangeFinished)
    var pressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .height(EditorGeometry.M3Slider.HEIGHT_DP.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, min..max, 0)
                setProgress { target ->
                    val c = target.coerceIn(min, max)
                    if (c != latestValue) {
                        latestOnChange(c)
                        latestOnFinished?.invoke()
                        true
                    } else {
                        false
                    }
                }
            }
            .pointerInput(min, max) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    var dragging = false
                    var driftCancelled = false
                    var crossedSign = 0f
                    var lastEmitted = latestValue
                    var finished = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) {
                            // Taken by someone else (a parent scroll):
                            // the library ends the gesture as a cancel.
                            if (dragging) finished = true
                            break
                        }
                        if (!change.pressed) {
                            change.consume()
                            if (dragging) {
                                finished = true
                            } else if (!driftCancelled) {
                                // A tap: the value comes from the DOWN
                                // position (the press anchor), then the
                                // finished callback fires.
                                val v = g.valueOfOffset(
                                    size.width.toFloat(), down.position.x,
                                    min, max, true, density
                                )
                                if (v != lastEmitted) {
                                    lastEmitted = v
                                    latestOnChange(v)
                                }
                                finished = true
                            }
                            break
                        }
                        val totalMove = change.position.x - down.position.x
                        if (!dragging) {
                            if (abs(totalMove) >= slop) {
                                dragging = true
                                crossedSign = sign(totalMove)
                            } else if (
                                (change.position - down.position).getDistance() > slop
                            ) {
                                // Mostly-vertical drift kills the tap
                                // (the drag may still start later).
                                driftCancelled = true
                            }
                        }
                        if (dragging) {
                            change.consume()
                            val rawOffset = change.position.x - crossedSign * slop
                            val v = g.valueOfOffset(
                                size.width.toFloat(), rawOffset,
                                min, max, true, density
                            )
                            if (v != lastEmitted) {
                                lastEmitted = v
                                latestOnChange(v)
                            }
                        }
                    }
                    pressed = false
                    if (finished) latestOnFinished?.invoke()
                }
            }
    ) {
        NativeVectorCanvas(
            modifier = Modifier.fillMaxSize(),
            clearArgb = 0,
            drawKey = listOf(value, pressed, thumbArgb, activeArgb, inactiveArgb),
            buildCmds = { w, h, dd ->
                val f = g.fractionOf(latestValue, min, max)
                val centreY = h / 2f
                val trackLeft = g.trackLeftPx(pressed, dd)
                val trackW = g.trackWPx(w, pressed, dd)
                val valueEnd = g.valueEndPx(w, f, pressed, dd)
                val endGap = g.endGapPx(pressed, dd)
                val trackH = g.TRACK_H_DP * dd
                val outer = g.TRACK_OUTER_CORNER_DP * dd
                val inner = g.TRACK_INNER_CORNER_DP * dd
                // Inactive segment + stop indicator (drawn first, the
                // active segment never overlaps it).
                if (g.inactiveDrawn(w, f, pressed, dd)) {
                    val segW = trackW - valueEnd - endGap
                    val pts = EditorGeometry.roundedRectOutline(
                        trackLeft + valueEnd + endGap + segW / 2f, centreY,
                        segW, trackH, inner, outer, outer, inner
                    )
                    addFilledPolygon(
                        FloatArray(pts.size / 2) { pts[it * 2] },
                        FloatArray(pts.size / 2) { pts[it * 2 + 1] },
                        pts.size / 2, inactiveArgb
                    )
                    addCircle(
                        g.stopCentreXPx(w, pressed, dd), centreY,
                        g.STOP_DP * dd / 2f, activeArgb
                    )
                }
                // Active segment.
                if (g.activeDrawn(w, f, pressed, dd)) {
                    val segW = valueEnd - endGap
                    val pts = EditorGeometry.roundedRectOutline(
                        trackLeft + segW / 2f, centreY,
                        segW, trackH, outer, inner, inner, outer
                    )
                    addFilledPolygon(
                        FloatArray(pts.size / 2) { pts[it * 2] },
                        FloatArray(pts.size / 2) { pts[it * 2 + 1] },
                        pts.size / 2, activeArgb
                    )
                }
                // The handle: a capsule, half width while pressed.
                val thumbW = g.thumbWPx(pressed, dd)
                val thumbLeft = g.thumbLeftPx(w, f, pressed, dd).toFloat()
                addRoundRect(
                    thumbLeft + thumbW / 2f, centreY,
                    thumbW, g.THUMB_H_DP * dd, thumbW / 2f, thumbArgb
                )
            }
        )
    }
}

/**
 * Build 252 (stage 5): one capsule pill chip drawn in GL — the
 * masks panel's Chip pattern (build 207): selected = the filled
 * AmPillSelected surface + bold label, unselected = AmPanelHigh +
 * semibold dim label, disabled = grey label with the press still
 * animating but the click gated. Hug width from the label advance.
 */
@Composable
internal fun GlChip(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    padHDp: Float = 10f,
    padVDp: Float = 4f,
    pressScale: Float = 0.94f,
    styleOn: UiLabelStyle = UiLabelStyle.TEXT_11_BOLD,
    styleOff: UiLabelStyle = UiLabelStyle.TEXT_11_SEMI,
    fillOnArgb: Int = 0x2418D8E0,
    fillOffArgb: Int = 0xFF151B23.toInt(),
    textOnArgb: Int = 0xFFF4F4F5.toInt(),
    textOffArgb: Int = 0xFFA1A1AA.toInt(),
    textDisabledArgb: Int = 0xFFA1A1AA.toInt(),
    /** Build 253 (stage 6): a minimum chip height (the export chips' 30 dp). */
    minHDp: Float = 0f
) {
    val density = LocalDensity.current.density
    val atlas = rememberUiAtlas()
    val pad = UiLabelAtlas.PAD.toFloat()
    val style = if (selected) styleOn else styleOff
    fun resolveChip() = atlas.resolve(label, style)
    var entry = resolveChip()
    if (atlas.overflowed) {
        atlas.reset()
        entry = resolveChip()
    }
    val e = entry
    val scale = remember { Animatable(1f) }
    val textArgb = when {
        !enabled -> textDisabledArgb
        selected -> textOnArgb
        else -> textOffArgb
    }
    val fillArgb = if (selected) fillOnArgb else fillOffArgb
    val wDp = if (e != null) (e.advance / density + 2f * padHDp).dp else 0.dp
    val hDp = max(atlas.textHeightPx(style) / density + 2f * padVDp, minHDp).dp
    Box(
        modifier
            .width(wDp)
            .height(hDp)
    ) {
        NativeVectorCanvas(
            modifier = Modifier.fillMaxSize(),
            clearArgb = 0,
            atlas = atlas,
            drawKey = listOf(e, scale.value, fillArgb, textArgb, atlas.generation),
            buildCmds = { w, h, _ ->
                // The Compose chip's background sat OUTSIDE the press
                // graphicsLayer — only the label scales.
                addRoundRect(w / 2f, h / 2f, w, h, h / 2f, fillArgb)
                if (e != null) {
                    val textH = atlas.textHeightPx(style)
                    val fm = atlas.fontMetrics(style)
                    val top = EditorGeometry.centredTextTopPx(0f, h, textH)
                    val baseline = PanelGeometry.lineBaselinePx(top, textH, fm.ascent, fm.descent)
                    val originX = EditorGeometry.centredOriginPx(w / 2f, e.advance)
                    val (dstX, dstY) = EditorGeometry.textDst(originX, baseline, e.bearing, e.base, pad)
                    setScaleAbout(w / 2f, h / 2f, scale.value)
                    addText(
                        e.index, dstX, dstY,
                        0f, 0f, e.rectW.toFloat(), e.rectH.toFloat(),
                        tintArgb = textArgb
                    )
                    clearTransform()
                }
            }
        )
        ChromePressBox(
            scale, pressScale, { if (enabled) onClick() },
            Modifier.fillMaxSize(),
            tag = tag,
            description = label
        )
    }
}

/**
 * Build 252 (stage 5): the pen / draw panels' bare text button in
 * GL (11.5 sp semibold, h8/v6 padding; disabled = faint, no click).
 * The press scales the text — all the Compose box held.
 */
@Composable
internal fun GlTextButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density
    val atlas = rememberUiAtlas()
    val pad = UiLabelAtlas.PAD.toFloat()
    val style = UiLabelStyle.ED_TEXT_SEMI
    fun resolveBtn() = atlas.resolve(label, style)
    var entry = resolveBtn()
    if (atlas.overflowed) {
        atlas.reset()
        entry = resolveBtn()
    }
    val e = entry
    val scale = remember { Animatable(1f) }
    val wDp = ((e?.advance ?: 0f) / density + 16f).dp
    val hDp = (atlas.textHeightPx(style) / density + 12f).dp
    Box(
        modifier
            .width(wDp)
            .height(hDp)
    ) {
        NativeVectorCanvas(
            modifier = Modifier.fillMaxSize(),
            clearArgb = 0,
            atlas = atlas,
            drawKey = listOf(e, scale.value, enabled, atlas.generation),
            buildCmds = { w, h, _ ->
                if (e != null) {
                    val textH = atlas.textHeightPx(style)
                    val fm = atlas.fontMetrics(style)
                    val top = EditorGeometry.centredTextTopPx(0f, h, textH)
                    val baseline = PanelGeometry.lineBaselinePx(top, textH, fm.ascent, fm.descent)
                    val (dstX, dstY) = EditorGeometry.textDst(8f * density, baseline, e.bearing, e.base, pad)
                    setScaleAbout(w / 2f, h / 2f, scale.value)
                    addText(
                        e.index, dstX, dstY,
                        0f, 0f, e.rectW.toFloat(), e.rectH.toFloat(),
                        tintArgb = if (enabled) 0xFFF4F4F5.toInt() else 0xFF71717A.toInt()
                    )
                    clearTransform()
                }
            }
        )
        ChromePressBox(
            scale, 0.94f, onClick,
            Modifier.fillMaxSize(),
            enabled = enabled,
            description = label
        )
    }
}

/**
 * Build 252 (stage 5): the white "Done" pill (GradientPillButton's
 * shape) in GL — white capsule + hairline, ink label; the fill sat
 * outside the press layer, the label scales.
 */
@Composable
internal fun GlDonePill(
    label: String,
    tag: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density
    val atlas = rememberUiAtlas()
    val pad = UiLabelAtlas.PAD.toFloat()
    val style = UiLabelStyle.SHEET_ITEM_BOLD
    fun resolvePill() = atlas.resolve(label, style)
    var entry = resolvePill()
    if (atlas.overflowed) {
        atlas.reset()
        entry = resolvePill()
    }
    val e = entry
    val scale = remember { Animatable(1f) }
    val wDp = ((e?.advance ?: 0f) / density + 24f).dp
    val hDp = (atlas.textHeightPx(style) / density).dp
    Box(
        modifier
            .width(wDp)
            .height(hDp)
    ) {
        NativeVectorCanvas(
            modifier = Modifier.fillMaxSize(),
            clearArgb = 0,
            atlas = atlas,
            drawKey = listOf(e, scale.value, atlas.generation),
            buildCmds = { w, h, dd ->
                addRoundRect(w / 2f, h / 2f, w, h, h / 2f, 0xFFFFFFFF.toInt())
                addRoundRect(
                    w / 2f, h / 2f, w - 1f * dd, h - 1f * dd, h / 2f - 0.5f * dd,
                    0, 1f * dd, 0xFF232C38.toInt()
                )
                if (e != null) {
                    val textH = atlas.textHeightPx(style)
                    val fm = atlas.fontMetrics(style)
                    val top = EditorGeometry.centredTextTopPx(0f, h, textH)
                    val baseline = PanelGeometry.lineBaselinePx(top, textH, fm.ascent, fm.descent)
                    val originX = EditorGeometry.centredOriginPx(w / 2f, e.advance)
                    val (dstX, dstY) = EditorGeometry.textDst(originX, baseline, e.bearing, e.base, pad)
                    setScaleAbout(w / 2f, h / 2f, scale.value)
                    addText(
                        e.index, dstX, dstY,
                        0f, 0f, e.rectW.toFloat(), e.rectH.toFloat(),
                        tintArgb = 0xFF111114.toInt()
                    )
                    clearTransform()
                }
            }
        )
        ChromePressBox(
            scale, 0.94f, onClick,
            Modifier.fillMaxSize(),
            tag = tag,
            description = label
        )
    }
}

/**
 * Build 253 (UI migration stage 6): one GL text line sized to its
 * style's font box — the wrap-height Compose Text's form, for leaf
 * texts inside Compose layout skeletons (sheet titles, row labels,
 * readouts). [GlTextLine] does the drawing; this wrapper only fixes
 * the height the font box needs.
 */
@Composable
internal fun GlLabelLine(
    text: String,
    style: UiLabelStyle,
    colorArgb: Int,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Start,
    ellipsize: Boolean = false
) {
    val density = LocalDensity.current.density
    val atlas = rememberUiAtlas()
    val hDp = (atlas.textHeightPx(style) / density).dp
    GlTextLine(text, style, colorArgb, modifier.height(hDp), align, ellipsize)
}

/**
 * Build 253 (stage 6): rasterize a Material icon painter into this
 * atlas (the stage-3/4 pattern: the vector's alpha carries the
 * shape, the blit tint supplies the colour).
 */
internal fun UiLabelAtlas.iconEntry(
    key: String,
    painter: VectorPainter,
    sizePx: Int,
    density: Float
): UiLabelAtlas.Entry? =
    resolveIcon(key, sizePx, sizePx) { cv, x, y ->
        val b = rasterizePainterBitmap(painter, sizePx, density)
        cv.drawBitmap(b, x, y, null)
        b.recycle()
    }

/**
 * Build 253 (stage 6): the violet primary pill (PillComponents'
 * VioletPillButton) in GL — the lime capsule centred in the caller's
 * box, hug width per [SheetGeometry.VioletPill]; the fill sat
 * outside the press layer in Compose, so only the label scales.
 */
@Composable
internal fun GlVioletPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null
) {
    val density = LocalDensity.current.density
    val atlas = rememberUiAtlas()
    val pad = UiLabelAtlas.PAD.toFloat()
    val style = UiLabelStyle.CHIP_BIG
    fun resolvePill() = atlas.resolve(label, style)
    var entry = resolvePill()
    if (atlas.overflowed) {
        atlas.reset()
        entry = resolvePill()
    }
    val e = entry
    val scale = remember { Animatable(1f) }
    val textH = atlas.textHeightPx(style)
    val wDp = if (e != null) (SheetGeometry.VioletPill.widthPx(e.advance, density) / density).dp else 0.dp
    val hDp = (SheetGeometry.VioletPill.heightPx(textH, density) / density).dp
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .width(wDp)
                .height(hDp)
        ) {
            NativeVectorCanvas(
                modifier = Modifier.fillMaxSize(),
                clearArgb = 0,
                atlas = atlas,
                drawKey = listOf(e, scale.value, atlas.generation),
                buildCmds = { w, h, _ ->
                    addRoundRect(w / 2f, h / 2f, w, h, h / 2f, 0xFF18D8E0.toInt())
                    if (e != null) {
                        val fm = atlas.fontMetrics(style)
                        val top = EditorGeometry.centredTextTopPx(0f, h, textH)
                        val baseline = PanelGeometry.lineBaselinePx(top, textH, fm.ascent, fm.descent)
                        val originX = EditorGeometry.centredOriginPx(w / 2f, e.advance)
                        val (dstX, dstY) = EditorGeometry.textDst(originX, baseline, e.bearing, e.base, pad)
                        setScaleAbout(w / 2f, h / 2f, scale.value)
                        addText(
                            e.index, dstX, dstY,
                            0f, 0f, e.rectW.toFloat(), e.rectH.toFloat(),
                            tintArgb = 0xFF111114.toInt()
                        )
                        clearTransform()
                    }
                }
            )
            ChromePressBox(
                scale, 0.94f, onClick,
                Modifier.fillMaxSize(),
                tag = tag,
                description = label
            )
        }
    }
}

/**
 * Build 253 (stage 6): the white primary pill (GlassComponents'
 * GradientPillButton) in GL — white capsule + hairline, ink label,
 * hug size per [SheetGeometry.WhitePill], centred in the caller's
 * box; the fill sat outside the press layer, only the label scales.
 */
@Composable
internal fun GlWhitePill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null
) {
    val density = LocalDensity.current.density
    val atlas = rememberUiAtlas()
    val pad = UiLabelAtlas.PAD.toFloat()
    val style = UiLabelStyle.PILL_BTN
    fun resolvePill() = atlas.resolve(label, style)
    var entry = resolvePill()
    if (atlas.overflowed) {
        atlas.reset()
        entry = resolvePill()
    }
    val e = entry
    val scale = remember { Animatable(1f) }
    val textH = atlas.textHeightPx(style)
    val wDp = if (e != null) (SheetGeometry.WhitePill.widthPx(e.advance, density) / density).dp else 0.dp
    val hDp = (SheetGeometry.WhitePill.heightPx(textH, density) / density).dp
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .width(wDp)
                .height(hDp)
        ) {
            NativeVectorCanvas(
                modifier = Modifier.fillMaxSize(),
                clearArgb = 0,
                atlas = atlas,
                drawKey = listOf(e, scale.value, atlas.generation),
                buildCmds = { w, h, dd ->
                    addRoundRect(w / 2f, h / 2f, w, h, h / 2f, 0xFFFFFFFF.toInt())
                    addRoundRect(
                        w / 2f, h / 2f, w - 1f * dd, h - 1f * dd, h / 2f - 0.5f * dd,
                        0, 1f * dd, 0xFF232C38.toInt()
                    )
                    if (e != null) {
                        val fm = atlas.fontMetrics(style)
                        val top = EditorGeometry.centredTextTopPx(0f, h, textH)
                        val baseline = PanelGeometry.lineBaselinePx(top, textH, fm.ascent, fm.descent)
                        val originX = EditorGeometry.centredOriginPx(w / 2f, e.advance)
                        val (dstX, dstY) = EditorGeometry.textDst(originX, baseline, e.bearing, e.base, pad)
                        setScaleAbout(w / 2f, h / 2f, scale.value)
                        addText(
                            e.index, dstX, dstY,
                            0f, 0f, e.rectW.toFloat(), e.rectH.toFloat(),
                            tintArgb = 0xFF111114.toInt()
                        )
                        clearTransform()
                    }
                }
            )
            ChromePressBox(
                scale, 0.94f, onClick,
                Modifier.fillMaxSize(),
                tag = tag,
                description = label
            )
        }
    }
}

/**
 * Build 253 (stage 6): the Material 3 switch in GL (the audio
 * sheets' form; geometry per [SheetGeometry.Switch]). Colours are
 * the build-252 defaults under this theme with the sheets' own
 * override: on = white track + near-white thumb; off = panel track
 * + hairline-strong border + dim thumb. A tap toggles (the library's
 * drag-to-toggle and its pressed 28 dp thumb are gesture states,
 * not replicated).
 */
@Composable
internal fun GlSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String,
    modifier: Modifier = Modifier
) {
    val g = SheetGeometry.Switch
    val scale = remember { Animatable(1f) }
    Box(
        modifier
            .width(g.TRACK_W_DP.dp)
            .height(g.TRACK_H_DP.dp)
    ) {
        NativeVectorCanvas(
            modifier = Modifier.fillMaxSize(),
            clearArgb = 0,
            drawKey = checked,
            buildCmds = { w, h, dd ->
                if (checked) {
                    addRoundRect(w / 2f, h / 2f, w, h, h / 2f, 0xFFFFFFFF.toInt())
                    addCircle(
                        g.thumbCentreXPx(true, dd), h / 2f,
                        g.thumbRadiusPx(true, dd), 0xFFF4F4F5.toInt()
                    )
                } else {
                    addRoundRect(w / 2f, h / 2f, w, h, h / 2f, 0xFF151B23.toInt())
                    addRoundRect(
                        w / 2f, h / 2f,
                        w - g.BORDER_W_DP * dd, h - g.BORDER_W_DP * dd,
                        h / 2f - (g.BORDER_W_DP / 2f) * dd,
                        0, g.BORDER_W_DP * dd, 0xFF2E3947.toInt()
                    )
                    addCircle(
                        g.thumbCentreXPx(false, dd), h / 2f,
                        g.thumbRadiusPx(false, dd), 0xFFA1A1AA.toInt()
                    )
                }
            }
        )
        ChromePressBox(
            scale, 1f, { onCheckedChange(!checked) },
            Modifier.fillMaxSize(),
            description = description
        )
    }
}

/**
 * Build 252 (stage 5): the pen / draw panels' Width row in GL —
 * semibold label, the shared GlSlider (default M3 colours, as the
 * material3 Slider showed under this theme), and the value readout.
 */
@Composable
internal fun GlWidthRow(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    sliderTag: String,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density
    val atlas = rememberUiAtlas()
    val valueText = "${kotlin.math.round(value).toInt()}"
    fun resolveW(): Pair<UiLabelAtlas.Entry?, UiLabelAtlas.Entry?> =
        atlas.resolve("Width", UiLabelStyle.ED_TEXT_SEMI) to atlas.resolve(valueText, UiLabelStyle.TEXT_11)
    var wEntries = resolveW()
    if (atlas.overflowed) {
        atlas.reset()
        wEntries = resolveW()
    }
    val (labelEntry, valueEntry) = wEntries
    Row(
        modifier = modifier,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(((labelEntry?.advance ?: 0f) / density).dp)
                .height(37.5.dp)
        ) {
            NativeVectorCanvas(
                modifier = Modifier.fillMaxSize(),
                clearArgb = 0,
                atlas = atlas,
                drawKey = listOf(labelEntry, atlas.generation),
                buildCmds = { w, h, _ ->
                    if (labelEntry != null) {
                        val style = UiLabelStyle.ED_TEXT_SEMI
                        val textH = atlas.textHeightPx(style)
                        val fm = atlas.fontMetrics(style)
                        val top = EditorGeometry.centredTextTopPx(0f, h, textH)
                        val baseline = PanelGeometry.lineBaselinePx(top, textH, fm.ascent, fm.descent)
                        val (dstX, dstY) = EditorGeometry.textDst(0f, baseline, labelEntry.bearing, labelEntry.base, UiLabelAtlas.PAD.toFloat())
                        addText(
                            labelEntry.index, dstX, dstY,
                            0f, 0f, labelEntry.rectW.toFloat(), labelEntry.rectH.toFloat(),
                            tintArgb = 0xFFF4F4F5.toInt()
                        )
                    }
                }
            )
        }
        Spacer(Modifier.width(8.dp))
        GlSlider(
            value = value,
            onValueChange = onValueChange,
            min = valueRange.start,
            max = valueRange.endInclusive,
            onValueChangeFinished = null,
            thumbArgb = 0xFFF4F4F5.toInt(),
            activeArgb = 0xFFF4F4F5.toInt(),
            inactiveArgb = 0xFF2A3542.toInt(),
            modifier = Modifier
                .weight(1f)
                .testTag(sliderTag)
        )
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .width(((valueEntry?.advance ?: 0f) / density).dp)
                .height(37.5.dp)
        ) {
            NativeVectorCanvas(
                modifier = Modifier.fillMaxSize(),
                clearArgb = 0,
                atlas = atlas,
                drawKey = listOf(valueEntry, atlas.generation),
                buildCmds = { w, h, _ ->
                    if (valueEntry != null) {
                        val style = UiLabelStyle.TEXT_11
                        val textH = atlas.textHeightPx(style)
                        val fm = atlas.fontMetrics(style)
                        val top = EditorGeometry.centredTextTopPx(0f, h, textH)
                        val baseline = PanelGeometry.lineBaselinePx(top, textH, fm.ascent, fm.descent)
                        val (dstX, dstY) = EditorGeometry.textDst(0f, baseline, valueEntry.bearing, valueEntry.base, UiLabelAtlas.PAD.toFloat())
                        addText(
                            valueEntry.index, dstX, dstY,
                            0f, 0f, valueEntry.rectW.toFloat(), valueEntry.rectH.toFloat(),
                            tintArgb = 0xFFA1A1AA.toInt()
                        )
                    }
                }
            )
        }
    }
}
