package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.model.TimelineClip
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
    val fillColor = Color(clip.textColor)

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
            .widthIn(max = width * 0.9f)
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
            Text(text = label, style = base.copy(color = fillColor))
        } else {
            Text(text = label, style = base.copy(color = fillColor, shadow = shadow))
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
    onTap: (clipId: String) -> Unit,
    onDoubleTap: (clipId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val x by rememberUpdatedState(clip.transformX)
    val y by rememberUpdatedState(clip.transformY)
    val scale by rememberUpdatedState((clip.scalePercent / 100f).coerceAtLeast(0.1f))
    val rotation by rememberUpdatedState(clip.rotationDeg)
    val move by rememberUpdatedState(onMove)
    val tap by rememberUpdatedState(onTap)
    val doubleTap by rememberUpdatedState(onDoubleTap)

    Box(
        modifier = modifier
            .graphicsLayer {
                translationX = clip.transformX
                translationY = clip.transformY
                scaleX = (clip.scalePercent / 100f).coerceAtLeast(0.1f)
                scaleY = (clip.scalePercent / 100f).coerceAtLeast(0.1f)
                rotationZ = clip.rotationDeg
                rotationX = clip.rotation3dX
                rotationY = clip.rotation3dY
                transformOrigin = TransformOrigin(clip.pivotX, clip.pivotY)
                cameraDistance = 12f * density
            }
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
                    onDrag = { change, amount ->
                        change.consume()
                        // The drag arrives in the layer's own (scaled + rotated) space:
                        // turn it back into canvas pixels.
                        val rad = Math.toRadians(rotation.toDouble())
                        val c = cos(rad).toFloat()
                        val s = sin(rad).toFloat()
                        dragX += (amount.x * c - amount.y * s) * scale
                        dragY += (amount.x * s + amount.y * c) * scale
                        move(clip.id, dragX, dragY)
                    }
                )
            }
            .then(
                if (isSelected) Modifier.border(1.dp, Color.White, RoundedCornerShape(1.dp))
                else Modifier
            )
    ) {
        TextLayerContent(clip = clip, frameWidth = frameWidth)
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
    TextPreset(0xFFFFFFFF, shadowOpacity = 100f, shadowBlur = 70f, shadowDistance = 0f, shadowColor = 0xFF00E5FF),
    TextPreset(0xFF00E600, strokeThickness = 35f),
    TextPreset(0xFFFFEB3B, strokeThickness = 35f),
    TextPreset(0xFFFF4D6D, strokeThickness = 35f, strokeColor = 0xFFFFFFFF),
    TextPreset(0xFF000000, strokeThickness = 35f, strokeColor = 0xFFFFFFFF),
    TextPreset(0xFFFF9F0A, shadowOpacity = 80f, shadowBlur = 40f, shadowDistance = 0f, shadowColor = 0xFFFF9F0A)
)
