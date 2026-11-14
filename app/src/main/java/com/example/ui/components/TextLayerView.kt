package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.model.FILL_GRADIENT
import com.example.model.FILL_NONE
import com.example.model.FILL_SOLID
import com.example.model.TimelineClip
import com.example.model.cropVisibleRect
import com.example.model.effectiveScalePercent
import com.example.model.depthX
import com.example.model.depthY
import com.example.ui.text.rememberFontFamily
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** What an empty text layer shows (the layer is visible on the canvas before anything is typed). */
const val TEXT_PLACEHOLDER = "Text"

/** Font size is 11% of the canvas width, so the text looks the same in every canvas size. */
private const val FONT_SIZE_FRACTION = 0.11f

/**
 * Draws one text layer: fill, outline (stroke), shadow and background box, exactly as set in
 * Text edit. [frameWidth] is the width of the canvas the text sits on; every size (font,
 * outline, shadow, padding) is relative to it.
 */
@Composable
fun TextLayerContent(
    clip: TimelineClip,
    frameWidth: Dp,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val family = rememberFontFamily(clip.fontFamily)
    val width = if (frameWidth.value > 0f) frameWidth else 300.dp
    val fontPx = with(density) { width.toPx() } * FONT_SIZE_FRACTION
    val label = (clip.text ?: "").ifEmpty { TEXT_PLACEHOLDER }

    val decoration = when {
        clip.isUnderline && clip.isStrikethrough ->
            TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
        clip.isUnderline -> TextDecoration.Underline
        clip.isStrikethrough -> TextDecoration.LineThrough
        else -> TextDecoration.None
    }
    val fillColor = if (clip.fillMode == FILL_NONE) Color.Transparent else Color(clip.textColor)
    val fillBrush: Brush? = when (clip.fillMode) {
        FILL_GRADIENT -> clip.gradientBrush()
        com.example.model.FILL_IMAGE -> clip.imageBrush()
        else -> null
    }

    val shadow: Shadow? = if (clip.shadowOpacity > 0f) {
        val base = Color(clip.shadowColor)
        val rad = Math.toRadians(clip.shadowAngle.toDouble())
        val dist = fontPx * clip.shadowDistance / 100f * 0.4f
        Shadow(
            color = base.copy(alpha = (base.alpha * clip.shadowOpacity / 100f).coerceIn(0f, 1f)),
            offset = Offset((cos(rad) * dist).toFloat(), (sin(rad) * dist).toFloat()),
            blurRadius = fontPx * clip.shadowBlur / 100f * 0.5f
        )
    } else null

    val base = TextStyle(
        fontFamily = family,
        fontSize = with(density) { fontPx.toSp() },
        fontWeight = FontWeight(clip.fontWeight.coerceIn(100, 900)),
        fontStyle = if (clip.isItalic) FontStyle.Italic else FontStyle.Normal,
        textDecoration = decoration,
        textAlign = when (clip.textAlign) {
            0 -> TextAlign.Start
            2 -> TextAlign.End
            else -> TextAlign.Center
        },
        letterSpacing = with(density) { (fontPx * clip.letterSpacing / 100f).toSp() },
        lineHeight = with(density) { (fontPx * clip.lineHeightPercent / 100f).toSp() }
    )

    val hasStroke = clip.textStrokeThickness > 0f
    val strokePx = fontPx * clip.textStrokeThickness / 100f * 0.4f

    // Padding: a small margin always, bigger when a background box is shown.
    val bgOn = clip.bgOpacity > 0f
    val padH = with(density) {
        max(fontPx * 0.25f, if (bgOn) fontPx * clip.bgPadding / 100f * 0.9f else 0f).toDp()
    }
    val padV = with(density) {
        max(fontPx * 0.12f, if (bgOn) fontPx * clip.bgPadding / 100f * 0.5f else 0f).toDp()
    }
    val bgBase = Color(clip.bgColor)
    val bgModifier = if (bgOn) {
        Modifier.background(
            bgBase.copy(alpha = (bgBase.alpha * clip.bgOpacity / 100f).coerceIn(0f, 1f)),
            RoundedCornerShape(percent = (clip.bgCorner / 2f).toInt().coerceIn(0, 50))
        )
    } else Modifier

    Box(
        modifier = modifier
            // An authored wrap width (textWrapFrac, from an Alight import) is an on-screen share of
            // the frame; this box is laid out unscaled and scaled by the caller, so it is divided by
            // the layer's scale — the same conversion as the render painters (see textLayoutBoxWidth).
            // The padding below sits INSIDE this max width in Compose, while the render engines add
            // it outside the line width, so an authored width gets the padding added back here —
            // without it the editor box was 0.5 em narrower than the render box (build 135).
            .widthIn(
                max = if (clip.textWrapFrac > 0f) {
                    width * (clip.textWrapFrac / (clip.effectiveScalePercent / 100f).coerceAtLeast(0.01f)) + padH * 2
                } else width * 0.9f
            )
            .then(bgModifier)
            .padding(horizontal = padH, vertical = padV)
    ) {
        if (hasStroke) {
            // Outline sits underneath; the fill drawn on top hides its inner half.
            Text(
                text = label,
                style = base.copy(
                    color = Color(clip.textStrokeColor),
                    drawStyle = Stroke(width = strokePx, join = StrokeJoin.Round),
                    shadow = shadow
                )
            )
            Text(text = label, style = base.withFill(fillBrush, fillColor))
        } else {
            Text(text = label, style = base.withFill(fillBrush, fillColor).copy(shadow = shadow))
        }
    }
}

/**
 * A text layer placed on the canvas: shows the transform (move / scale / rotate / 3D), can be
 * dragged, tapped (select) and double-tapped (open Text edit).
 */
@Composable
fun TextLayerOnCanvas(
    clip: TimelineClip,
    frameWidth: Dp,
    isSelected: Boolean,
    onMove: (clipId: String, offsetX: Float, offsetY: Float) -> Unit,
    /** Called when the drag ends (build 176: clears the snap guides). */
    onDragEnd: () -> Unit = {},
    onTap: (clipId: String) -> Unit,
    onDoubleTap: (clipId: String) -> Unit,
    modifier: Modifier = Modifier,
    /** Reports the unscaled size of the text box (for the bounding box on the canvas). */
    onLayoutSize: (id: String, widthPx: Float, heightPx: Float) -> Unit = { _, _, _ -> },
    /** Drawn on top of the text inside the same transformed box (gradient handles). */
    overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
    /** The C++ preview draws this layer: keep the (invisible) box for size, selection and touch only. */
    hideContent: Boolean = false
) {
    val x by rememberUpdatedState(clip.transformX)
    val y by rememberUpdatedState(clip.transformY)
    // Drag moves are stored before the depth factor, so the drag uses the plain scale.
    val scale by rememberUpdatedState((clip.scalePercent / 100f).coerceAtLeast(0.01f))
    val rotation by rememberUpdatedState(clip.rotationDeg)
    val flipX by rememberUpdatedState(if (clip.isFlippedH) -1f else 1f)
    val flipY by rememberUpdatedState(if (clip.isFlippedV) -1f else 1f)
    val move by rememberUpdatedState(onMove)
    val tap by rememberUpdatedState(onTap)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    // Build 186: the layer's own measured size (the same numbers onLayoutSize reports),
    // so the touch box below can be clipped to the visible (cropped) part of the box.
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val densityNow = LocalDensity.current

    Box(
        modifier = modifier
            .onSizeChanged {
                boxSize = it
                onLayoutSize(clip.id, it.width.toFloat(), it.height.toFloat())
            }
            .graphicsLayer {
                translationX = clip.depthX
                translationY = clip.depthY
                scaleX = (clip.effectiveScalePercent / 100f).coerceAtLeast(0.01f) * (if (clip.isFlippedH) -1f else 1f)
                scaleY = (clip.effectiveScalePercent / 100f).coerceAtLeast(0.01f) * (if (clip.isFlippedV) -1f else 1f)
                rotationZ = clip.rotationDeg
                rotationX = clip.rotation3dX
                rotationY = clip.rotation3dY
                transformOrigin = TransformOrigin(clip.pivotX, clip.pivotY)
                cameraDistance = 12f * density
            }
    ) {
        // Build 186: taps and drags live on an inner box covering only the VISIBLE part of
        // the layer. Crop clips a text layer's own box in place (the cropMask below), so a
        // touch on the cropped-away part must not select or drag the layer — it never
        // reaches this box and falls through to whatever is beneath (the same
        // cropVisibleRect law the selection frame follows, build 182). With no crop the
        // rect is the whole box and nothing changes. The handlers themselves are the ones
        // that used to sit on the outer box, moved unchanged: drag deltas are
        // translation-invariant, and the tap handlers never read the touch offset. Until
        // the first measure lands there is no touch box at all (one layout pass).
        val visRect = if (boxSize.width > 0 && boxSize.height > 0) {
            clip.cropVisibleRect(boxSize.width.toFloat(), boxSize.height.toFloat())
        } else {
            null
        }
        if (visRect != null) {
            Box(
                modifier = Modifier
                    .offset(
                        x = with(densityNow) { visRect.left.toDp() },
                        y = with(densityNow) { visRect.top.toDp() }
                    )
                    .size(
                        with(densityNow) { visRect.width.toDp() },
                        with(densityNow) { visRect.height.toDp() }
                    )
                    .pointerInput(clip.id) {
                        detectTapGestures(
                            onTap = { tap(clip.id) },
                            onDoubleTap = { doubleTap(clip.id) }
                        )
                    }
                    .pointerInput(clip.id) {
                        var dragX = 0f
                        var dragY = 0f
                        detectDragGestures(
                            onDragStart = {
                                dragX = x
                                dragY = y
                                tap(clip.id)
                            },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                            onDrag = { change, amount ->
                                change.consume()
                                // The drag arrives in the layer's own (scaled + rotated) space:
                                // turn it back into canvas pixels.
                                val rad = Math.toRadians(rotation.toDouble())
                                val c = cos(rad).toFloat()
                                val s = sin(rad).toFloat()
                                val ax = amount.x * flipX
                                val ay = amount.y * flipY
                                dragX += (ax * c - ay * s) * scale
                                dragY += (ax * s + ay * c) * scale
                                move(clip.id, dragX, dragY)
                            }
                        )
                    }
            )
        }
        TextLayerContent(
            clip = clip, frameWidth = frameWidth,
            modifier = Modifier.cropMask(clip).then(if (hideContent) Modifier.alpha(0f) else Modifier)
        )
        overlay()
    }
}

/** One look in the Style tab. Only the fields listed here change when it is applied. */
data class TextPreset(
    val fill: Long,
    val strokeThickness: Float = 0f,
    val strokeColor: Long = 0xFF000000,
    val shadowOpacity: Float = 0f,
    val shadowBlur: Float = 0f,
    val shadowDistance: Float = 0f,
    val shadowColor: Long = 0xFF000000
) {
    fun applyTo(c: TimelineClip) = c.copy(
        textColor = fill,
        fillMode = FILL_SOLID,
        textStrokeThickness = strokeThickness,
        textStrokeColor = strokeColor,
        shadowOpacity = shadowOpacity,
        shadowBlur = shadowBlur,
        shadowDistance = shadowDistance,
        shadowColor = shadowColor
    )
}

val TextPresets: List<TextPreset> = listOf(
    TextPreset(0xFFFFFFFF, strokeThickness = 35f),
    TextPreset(0xFFFFFFFF, strokeThickness = 70f),
    TextPreset(0xFFFFFFFF, shadowOpacity = 100f, shadowBlur = 0f, shadowDistance = 25f),
    TextPreset(0xFFFFFFFF, shadowOpacity = 60f, shadowBlur = 30f, shadowDistance = 10f),
    TextPreset(0xFFFFFFFF, shadowOpacity = 100f, shadowBlur = 70f, shadowDistance = 0f, shadowColor = 0xFFABE41D),
    TextPreset(0xFF00E600, strokeThickness = 35f),
    TextPreset(0xFFFFEB3B, strokeThickness = 35f),
    TextPreset(0xFFFF4D6D, strokeThickness = 35f, strokeColor = 0xFFFFFFFF),
    TextPreset(0xFF000000, strokeThickness = 35f, strokeColor = 0xFFFFFFFF),
    TextPreset(0xFFFF9F0A, shadowOpacity = 80f, shadowBlur = 40f, shadowDistance = 0f, shadowColor = 0xFFFF9F0A)
)

/** Text fill: a gradient brush when there is one, otherwise a plain colour. */
private fun TextStyle.withFill(brush: Brush?, color: Color): TextStyle =
    if (brush != null) copy(brush = brush) else copy(color = color)
