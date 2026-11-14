package com.example.ui.components
import androidx.compose.ui.graphics.RectangleShape

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.model.AnimProp
import com.example.model.AspectRatio
import com.example.model.Easing
import com.example.model.indexOnTime
import com.example.model.segmentIndexAt
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.ui.theme.CanvasBackground
import com.example.ui.theme.EditorBackground
import com.example.ui.theme.IconActive
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.RulerSubTickColor
import com.example.ui.theme.RulerTickColor
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.util.bouncyClickable
import kotlin.math.roundToInt

val CROP_PRESETS = listOf("Free", "Original", "Project", "9:16", "16:9", "1:1", "4:5", "21:9", "4:3")

/** Same screen proportions as the main editor, so Crop looks like the other editor panels. */
class CropChrome(
    val headerH: Dp,
    val canvasH: Dp,
    val playControlsH: Dp,
    val timescaleH: Dp,
    val layerRow: @Composable () -> Unit,
    val onFullScreen: () -> Unit,
    val onResolution: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit
)

@Composable
fun CropView(
    clip: TimelineClip?,
    aspectRatio: AspectRatio = AspectRatio.RATIO_16_9,
    currentPlayheadMs: Long,
    totalDurationMs: Long,
    isPlaying: Boolean,
    /** Player of each video layer (the edited layer has its own one). */
    /** C++ engine of the canvas: it draws the media layers of this editor on its own surface. */
    nativePreview: com.example.engine.NativePreviewEngine,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onTogglePlay: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    onDismiss: () -> Unit,
    onApplyCrop: (
        cropWidth: Float,
        cropHeight: Float,
        cropHorizontal: Float,
        cropVertical: Float,
        preset: String
    ) -> Unit,
    modifier: Modifier = Modifier,
    chrome: CropChrome? = null,
    /** Diamond: add / remove a crop keyframe (size + position) at the playhead. */
    onToggleKeyframes: (List<AnimProp>) -> Unit = {},
    /** Easing editor: a change is about to start (one undo step) / the curve of the segment starting at the keyframe time changed. */
    onEasingBegin: () -> Unit = {},
    onEasingChange: (AnimProp, Long, Easing) -> Unit = { _, _, _ -> }
) {
    var activePreset by remember(clip?.id) { mutableStateOf(clip?.cropPreset ?: "Free") }

    var widthPercent by remember(clip?.id) { mutableFloatStateOf(clip?.cropWidthPercent ?: 100.0f) }
    var heightPercent by remember(clip?.id) { mutableFloatStateOf(clip?.cropHeightPercent ?: 100.0f) }
    var horizontalPercent by remember(clip?.id) { mutableFloatStateOf(clip?.cropHorizontalPercent ?: 50.0f) }
    var verticalPercent by remember(clip?.id) { mutableFloatStateOf(clip?.cropVerticalPercent ?: 50.0f) }

    // Keep local state in sync with the clip (covers clip switch and undo/redo).
    LaunchedEffect(
        clip?.id, clip?.cropWidthPercent, clip?.cropHeightPercent,
        clip?.cropHorizontalPercent, clip?.cropVerticalPercent, clip?.cropPreset
    ) {
        if (clip != null) {
            widthPercent = clip.cropWidthPercent
            heightPercent = clip.cropHeightPercent
            horizontalPercent = clip.cropHorizontalPercent
            verticalPercent = clip.cropVerticalPercent
            activePreset = clip.cropPreset
        }
    }

    // Shape and text layers are cropped on their own box (not on the whole canvas).
    val isShapeLayer = clip?.type == LayerType.SHAPE
    val isTextLayer = clip?.type == LayerType.TEXT
    var textBoxPx by remember(clip?.id) { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val srcAspect = when {
        isShapeLayer -> 1f
        isTextLayer && textBoxPx.width > 0 && textBoxPx.height > 0 -> textBoxPx.width.toFloat() / textBoxPx.height
        isTextLayer -> 3f
        else -> clip?.sourceAspect?.takeIf { it > 0f } ?: aspectRatio.ratioFloat
    }
    val minSize = 0.1f

    // ---- Keyframe / easing state of the crop (box size + box position) ----
    val cropProps = remember { listOf(AnimProp.CROP_SIZE, AnimProp.CROP_POS) }
    val kfMap = clip?.keyframes.orEmpty()
    val relMs = currentPlayheadMs - (clip?.startMs ?: 0L)
    val insideClip = clip != null && currentPlayheadMs >= clip.startMs && currentPlayheadMs <= clip.endMs
    val onKeyframeNow = cropProps.any { kfMap[it].orEmpty().indexOnTime(relMs) >= 0 }
    val hasKeyframes = cropProps.any { kfMap[it].orEmpty().isNotEmpty() }
    val easingProp = cropProps.firstOrNull { kfMap[it].orEmpty().segmentIndexAt(relMs) >= 0 }
    val kfList = easingProp?.let { kfMap[it].orEmpty() }.orEmpty()
    val segIdx = if (easingProp != null) kfList.segmentIndexAt(relMs) else -1
    val easingEnabled = segIdx >= 0
    var showEasing by remember { mutableStateOf(false) }
    LaunchedEffect(easingEnabled) { if (!easingEnabled) showEasing = false }
    val segStart = if (segIdx >= 0) kfList[segIdx] else null
    val segEnd = if (segIdx >= 0) kfList[segIdx + 1] else null

    fun emitCrop() {
        onApplyCrop(widthPercent, heightPercent, horizontalPercent, verticalPercent, activePreset)
    }

    fun rl() = (1f - widthPercent / 100f) * horizontalPercent / 100f
    fun rt() = (1f - heightPercent / 100f) * verticalPercent / 100f
    fun rr() = rl() + widthPercent / 100f
    fun rb() = rt() + heightPercent / 100f

    fun setRect(l: Float, t: Float, r: Float, b: Float) {
        val w = (r - l).coerceIn(minSize, 1f)
        val h = (b - t).coerceIn(minSize, 1f)
        val nl = l.coerceIn(0f, 1f - w)
        val nt = t.coerceIn(0f, 1f - h)
        widthPercent = w * 100f
        heightPercent = h * 100f
        horizontalPercent = if (1f - w > 0.0001f) (nl / (1f - w) * 100f).coerceIn(0f, 100f) else 50f
        verticalPercent = if (1f - h > 0.0001f) (nt / (1f - h) * 100f).coerceIn(0f, 100f) else 50f
    }

    /** Fractional (of the source) width/height ratio the box must keep, or null for Free. */
    fun lockK(preset: String): Float? {
        val pixelRatio = when (preset) {
            "Free" -> return null
            "Original" -> srcAspect
            "Project" -> aspectRatio.ratioFloat
            else -> {
                val parts = preset.split(":")
                val x = parts.getOrNull(0)?.toFloatOrNull()
                val y = parts.getOrNull(1)?.toFloatOrNull()
                if (x != null && y != null && y > 0f) x / y else return null
            }
        }
        return pixelRatio / srcAspect
    }

    fun applyPresetRatio(preset: String) {
        activePreset = preset
        val k = lockK(preset)
        if (k != null) {
            // Largest centered box with that ratio.
            val w: Float
            val h: Float
            if (k >= 1f) { w = 1f; h = 1f / k } else { h = 1f; w = k }
            setRect((1f - w) / 2f, (1f - h) / 2f, (1f + w) / 2f, (1f + h) / 2f)
        }
        emitCrop()
    }

    /** Resize around the current center (used by the Width / Height sliders). */
    fun resizeAroundCenter(newW: Float?, newH: Float?) {
        val k = lockK(activePreset)
        var w = newW ?: (rr() - rl())
        var h = newH ?: (rb() - rt())
        if (k != null) {
            if (newW != null) h = w / k else w = h * k
            if (h > 1f) { h = 1f; w = h * k }
            if (w > 1f) { w = 1f; h = w / k }
        }
        w = w.coerceIn(minSize, 1f)
        h = h.coerceIn(minSize, 1f)
        val cx = (rl() + rr()) / 2f
        val cy = (rt() + rb()) / 2f
        val nl = (cx - w / 2f).coerceIn(0f, 1f - w)
        val nt = (cy - h / 2f).coerceIn(0f, 1f - h)
        setRect(nl, nt, nl + w, nt + h)
        emitCrop()
    }

    // dx/dy are fractions of the source size. mode: 0=move, else bit flags 1=left 2=right 4=top 8=bottom
    fun dragCrop(mode: Int, dx: Float, dy: Float) {
        var l = rl(); var t = rt(); var r = rr(); var b = rb()
        if (mode == 0) {
            val w = r - l; val h = b - t
            l = (l + dx).coerceIn(0f, 1f - w); r = l + w
            t = (t + dy).coerceIn(0f, 1f - h); b = t + h
            setRect(l, t, r, b)
            emitCrop()
            return
        }
        val mL = mode and 1 != 0
        val mR = mode and 2 != 0
        val mT = mode and 4 != 0
        val mB = mode and 8 != 0
        val k = lockK(activePreset)
        if (k == null) {
            if (mL) l = (l + dx).coerceIn(0f, r - minSize)
            if (mR) r = (r + dx).coerceIn(l + minSize, 1f)
            if (mT) t = (t + dy).coerceIn(0f, b - minSize)
            if (mB) b = (b + dy).coerceIn(t + minSize, 1f)
            setRect(l, t, r, b)
        } else {
            val isCorner = (mL || mR) && (mT || mB)
            if (isCorner) {
                val ax = if (mL) r else l
                val ay = if (mT) b else t
                val mx = (if (mL) l + dx else r + dx).coerceIn(0f, 1f)
                val my = (if (mT) t + dy else b + dy).coerceIn(0f, 1f)
                var w = kotlin.math.abs(mx - ax)
                val h0 = kotlin.math.abs(my - ay)
                w = maxOf(w, h0 * k)
                val maxW = minOf(if (mL) ax else 1f - ax, (if (mT) ay else 1f - ay) * k)
                w = w.coerceAtMost(maxW).coerceAtLeast(minOf(minSize, maxW))
                val h = w / k
                l = if (mL) ax - w else ax; r = l + w
                t = if (mT) ay - h else ay; b = t + h
            } else if (mL || mR) {
                val cy = (t + b) / 2f
                val rawW = if (mL) r - (l + dx).coerceIn(0f, r - minSize) else (r + dx).coerceIn(l + minSize, 1f) - l
                val maxH = 2f * minOf(cy, 1f - cy)
                var h = (rawW / k).coerceAtMost(maxH).coerceAtLeast(minOf(minSize, maxH))
                val w = h * k
                if (mL) l = r - w else r = l + w
                t = cy - h / 2f; b = t + h
            } else {
                val cx = (l + r) / 2f
                val rawH = if (mT) b - (t + dy).coerceIn(0f, b - minSize) else (b + dy).coerceIn(t + minSize, 1f) - t
                val maxW = 2f * minOf(cx, 1f - cx)
                var w = (rawH * k).coerceAtMost(maxW).coerceAtLeast(minOf(minSize, maxW))
                val h = w / k
                if (mT) t = b - h else b = t + h
                l = cx - w / 2f; r = l + w
            }
            setRect(l, t, r, b)
        }
        emitCrop()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F0F))
            .then(
                // Swipe down on the tools area (below the crop canvas) = back.
                if (chrome != null) Modifier.swipeDownToDismiss(
                    ignoreAbove = chrome.headerH + chrome.canvasH
                ) {
                    emitCrop()
                    onDismiss()
                } else Modifier
            )
            .testTag("crop_screen")
    ) {
        if (chrome != null) {
            TransformHeaderBar(
                height = chrome.headerH,
                title = "Crop",
                onReset = {
                    activePreset = "Free"
                    setRect(0f, 0f, 1f, 1f)
                    emitCrop()
                }
            )
        } else {
        // 1. Top Header: Down Arrow, "Crop", Done
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .bouncyClickable(onClick = {
                        emitCrop()
                        onDismiss()
                    })
                    .testTag("crop_back_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Collapse Crop",
                    tint = IconActive,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "Crop",
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .clip(RectangleShape)
                    .background(NeonCyan)
                    .bouncyClickable(onClick = {
                        emitCrop()
                        onDismiss()
                    })
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Done",
                    color = EditorBackground,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        }

        // 2. Interactive crop area: the box sits on the media itself (not the canvas frame).
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (chrome != null) Modifier.height(chrome.canvasH) else Modifier.weight(1f))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            val maxW = maxWidth
            val maxH = maxHeight
            val targetRatio = aspectRatio.ratioFloat

            val (frameW, frameH) = remember(maxW, maxH, targetRatio) {
                if (maxW.value / maxH.value > targetRatio) {
                    (maxH * targetRatio) to maxH
                } else {
                    maxW to (maxW / targetRatio)
                }
            }
            val frameRatio = frameW.value / frameH.value
            val density = androidx.compose.ui.platform.LocalDensity.current
            val contentW = when {
                isShapeLayer -> 140.dp
                isTextLayer && textBoxPx.width > 0 -> with(density) { textBoxPx.width.toDp() }
                isTextLayer -> 200.dp
                else -> if (srcAspect > frameRatio) frameW else frameH * srcAspect
            }
            val contentH = when {
                isShapeLayer -> 140.dp
                isTextLayer && textBoxPx.height > 0 -> with(density) { textBoxPx.height.toDp() }
                isTextLayer -> 66.dp
                else -> if (srcAspect > frameRatio) frameW / srcAspect else frameH
            }
            val padDp = 24.dp

            Box(
                modifier = Modifier.size(width = frameW, height = frameH),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RectangleShape)
                        .background(com.example.ui.theme.LocalCanvasColor.current)
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RectangleShape),
                    contentAlignment = Alignment.Center
                ) {
                // Background Layer (Video / Shape / Text Media)
                if (clip != null && clip.type == LayerType.VIDEO && clip.uri != null && !isShapeLayer && !isTextLayer) {
                    // The edited layer alone, uncropped and untransformed (the crop box works on it).
                    MediaLayersStack(
                        layers = listOf(
                            clip.copy(
                                blendMode = "Normal", opacity = 100f,
                                scalePercent = 100f, transformX = 0f, transformY = 0f, transformZ = 0f,
                                rotationDeg = 0f, rotation3dX = 0f, rotation3dY = 0f,
                                pivotX = 0.5f, pivotY = 0.5f,
                                cropWidthPercent = 100f, cropHeightPercent = 100f,
                                cropHorizontalPercent = 50f, cropVerticalPercent = 50f,
                                isFlippedH = false, isFlippedV = false,
                                // The C++ engine would animate / track it again; the crop box works on the plain layer.
                                keyframes = emptyMap(), trackFollow = null
                            )
                        ),
                        nativePreview = nativePreview,
                        isPlaying = isPlaying
                    )
                } else if (isTextLayer && clip != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .onSizeChanged { textBoxPx = it }
                    ) {
                        TextLayerContent(clip = clip.copy(cropWidthPercent = 100f, cropHeightPercent = 100f), frameWidth = frameW)
                    }
                } else if (clip?.type == LayerType.SHAPE) {
                    val rawColor = if (clip.textColor != 0L) clip.textColor else clip.color
                    val a = ((rawColor shr 24) and 0xFF).toInt()
                    val r = ((rawColor shr 16) and 0xFF).toInt()
                    val g = ((rawColor shr 8) and 0xFF).toInt()
                    val b = (rawColor and 0xFF).toInt()
                    val shapeColor = if (a == 0 && r == 0 && g == 0 && b == 0) Color(0xFFABE41D) else Color(r, g, b, if (a == 0) 255 else a)
                    val pattern = clip.shapePattern ?: "CIRCLE"
                    val fillBrush = clip.fillBrush()

                    Box(
                        modifier = Modifier
                            .size(140.dp)
                            .align(Alignment.Center)
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            when (pattern) {
                                "CIRCLE" -> drawCircle(brush = fillBrush)
                                "SQUARE" -> drawRect(brush = fillBrush)
                                "ROUNDED_SQUARE", "ROUNDED_RECT" -> drawRoundRect(brush = fillBrush, cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx(), 16.dp.toPx()))
                                else -> drawCircle(brush = fillBrush)
                            }
                        }
                    }
                } else {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRect(color = Color(0xFF121212))
                        val w = size.width
                        val h = size.height
                        drawLine(color = Color.White.copy(alpha = 0.08f), start = Offset(w / 2f, 0f), end = Offset(w / 2f, h), strokeWidth = 1.dp.toPx())
                        drawLine(color = Color.White.copy(alpha = 0.08f), start = Offset(0f, h / 2f), end = Offset(w, h / 2f), strokeWidth = 1.dp.toPx())
                    }
                }

                }

                // Gesture + drawing layer (bigger than the media so edge handles stay touchable).
                // requiredSize (not size): when the media fills the frame in one direction, plain size()
                // would be clamped to the frame, the padding would eat into the media area and the
                // crop box could never reach the outer ~24dp of the media.
                Box(
                    modifier = Modifier
                        .requiredSize(width = contentW + padDp * 2, height = contentH + padDp * 2)
                        .testTag("crop_box")
                        .pointerInput(clip?.id) {
                            val pad = 24.dp.toPx()
                            val slop = 30.dp.toPx()
                            var mode = -1
                            detectDragGestures(
                                onDragStart = { pos ->
                                    val cw = size.width - 2 * pad
                                    val ch = size.height - 2 * pad
                                    val l = pad + rl() * cw
                                    val r = pad + rr() * cw
                                    val t = pad + rt() * ch
                                    val b = pad + rb() * ch
                                    fun near(v: Float, target: Float) = kotlin.math.abs(v - target) <= slop
                                    val nL = near(pos.x, l); val nR = near(pos.x, r)
                                    val nT = near(pos.y, t); val nB = near(pos.y, b)
                                    val inX = pos.x in (l - slop)..(r + slop)
                                    val inY = pos.y in (t - slop)..(b + slop)
                                    mode = when {
                                        nL && nT -> 1 or 4
                                        nR && nT -> 2 or 4
                                        nL && nB -> 1 or 8
                                        nR && nB -> 2 or 8
                                        nL && inY -> 1
                                        nR && inY -> 2
                                        nT && inX -> 4
                                        nB && inX -> 8
                                        pos.x in l..r && pos.y in t..b -> 0
                                        else -> -1
                                    }
                                },
                                onDragEnd = { mode = -1 },
                                onDragCancel = { mode = -1 },
                                onDrag = { change, drag ->
                                    if (mode >= 0) {
                                        change.consume()
                                        val cw = size.width - 2 * pad
                                        val ch = size.height - 2 * pad
                                        dragCrop(mode, drag.x / cw, drag.y / ch)
                                    }
                                }
                            )
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val pad = padDp.toPx()
                        val cw = size.width - 2 * pad
                        val ch = size.height - 2 * pad
                        val l = pad + rl() * cw
                        val r = pad + rr() * cw
                        val t = pad + rt() * ch
                        val b = pad + rb() * ch

                        // Dim everything on the media outside the crop box.
                        clipRect(pad, pad, pad + cw, pad + ch) {
                            clipRect(l, t, r, b, clipOp = androidx.compose.ui.graphics.ClipOp.Difference) {
                                drawRect(color = Color.Black.copy(alpha = 0.55f))
                            }
                        }

                        // Rule-of-thirds guides
                        val guide = Color.White.copy(alpha = 0.28f)
                        for (i in 1..2) {
                            val x = l + (r - l) * i / 3f
                            val y = t + (b - t) * i / 3f
                            drawLine(guide, Offset(x, t), Offset(x, b), strokeWidth = 1.dp.toPx())
                            drawLine(guide, Offset(l, y), Offset(r, y), strokeWidth = 1.dp.toPx())
                        }

                        // Dashed border
                        drawRect(
                            color = Color.White.copy(alpha = 0.95f),
                            topLeft = Offset(l, t),
                            size = androidx.compose.ui.geometry.Size(r - l, b - t),
                            style = Stroke(
                                width = 1.5.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 9f), 0f)
                            )
                        )

                        // Handles: 4 corners + 4 edge midpoints
                        val mx = (l + r) / 2f
                        val my = (t + b) / 2f
                        val pts = listOf(
                            Offset(l, t) to 7.dp, Offset(r, t) to 7.dp, Offset(l, b) to 7.dp, Offset(r, b) to 7.dp,
                            Offset(mx, t) to 5.dp, Offset(mx, b) to 5.dp, Offset(l, my) to 5.dp, Offset(r, my) to 5.dp
                        )
                        for ((o, rad) in pts) {
                            drawCircle(Color(0xFFABE41D), radius = rad.toPx(), center = o)
                            drawCircle(Color.White, radius = rad.toPx(), center = o, style = Stroke(width = 1.5.dp.toPx()))
                        }
                    }
                }
            }
        }

        if (chrome != null) {
            PlayControlsSection(
                height = chrome.playControlsH,
                isPlaying = isPlaying,
                canUndo = canUndo,
                canRedo = canRedo,
                onFullScreenClick = chrome.onFullScreen,
                onResolutionClick = chrome.onResolution,
                onPreviousClick = chrome.onPrevious,
                onPlayPauseClick = onTogglePlay,
                onNextClick = chrome.onNext,
                onUndoClick = onUndo,
                onRedoClick = onRedo,
                // Build 201 (audit S-3): the crop surface wires none of the
                // editor-only controls — they are OMITTED here, not rendered
                // inert (until 200 they showed, and the snap magnet even
                // painted white/"on" while connected to nothing).
                showEditorControls = false
            )
            TimescaleSection(
                height = chrome.timescaleH,
                playheadMs = { currentPlayheadMs },
                totalDurationMs = totalDurationMs,
                onBack = {
                    emitCrop()
                    onDismiss()
                }
            )
            chrome.layerRow()
        } else {
        // 3. Playback Controls Row: Skip Prev, Play/Pause, Skip Next, Undo, Redo
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.width(36.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .bouncyClickable(onClick = { onSeekTo(0L) }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint = IconActive,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .bouncyClickable(onClick = onTogglePlay),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = IconActive,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .bouncyClickable(onClick = { onSeekTo(totalDurationMs) }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = IconActive,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .bouncyClickable(enabled = canUndo, onClick = onUndo),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "Undo",
                        tint = if (canUndo) IconActive else Color(0xFF4A4A4A),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .bouncyClickable(enabled = canRedo, onClick = onRedo),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Redo,
                        contentDescription = "Redo",
                        tint = if (canRedo) IconActive else Color(0xFF4A4A4A),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // 4. Timescale: 00:01 70 | 00:19 49 with ruler ticks
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.weight(0.36f))
            Text(
                text = formatTimeCode(currentPlayheadMs),
                color = TextPrimary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(text = "  |  ", color = TextSecondary, fontSize = 13.sp)
            Text(
                text = formatTimeCode(totalDurationMs),
                color = TextSecondary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(8.dp))

            Canvas(
                modifier = Modifier
                    .weight(0.48f)
                    .height(14.dp)
            ) {
                val tickSpacing = 8.dp.toPx()
                val count = (size.width / tickSpacing).toInt()
                for (i in 0..count) {
                    val isMajor = (i % 5 == 0)
                    drawLine(
                        color = if (isMajor) RulerTickColor else RulerSubTickColor,
                        start = Offset(i * tickSpacing, 0f),
                        end = Offset(i * tickSpacing, if (isMajor) size.height else size.height * 0.5f),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }
        }

        // 5. Mini Layer Strip: Reset Button on Left, Timeline Strip on Right
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp)
                .height(34.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Reset / Rotate Icon Button
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RectangleShape)
                    .background(Color(0xFF1D1D1D))
                    .border(1.dp, Color(0xFF333333), RectangleShape)
                    .bouncyClickable(onClick = {
                        activePreset = "Free"
                        setRect(0f, 0f, 1f, 1f)
                        emitCrop()
                    }),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reset Crop",
                    tint = IconActive,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Timeline Strip
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(30.dp)
                    .clip(RectangleShape)
                    .background(Color(0xFF1E1E1E))
                    .border(1.dp, Color(0xFF2C2C2C), RectangleShape)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer { rotationZ = 45f }
                            .background(Color(0xFF3B3B3B), RectangleShape)
                            .bouncyClickable(onClick = {}),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Keyframe",
                            tint = Color.White,
                            modifier = Modifier
                                .size(13.dp)
                                .graphicsLayer { rotationZ = -45f }
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Canvas(modifier = Modifier.size(18.dp)) {
                        val path = Path().apply {
                            moveTo(0f, size.height)
                            cubicTo(size.width * 0.4f, size.height, size.width * 0.6f, 0f, size.width, 0f)
                        }
                        drawPath(path = path, color = Color(0xFF8E8E8E), style = Stroke(width = 2.dp.toPx()))
                    }
                }
            }
        }

        }

        // 6. Bottom Control Panel: Aspect Ratio Pills & Ruler Dial Sliders
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (chrome != null) Modifier.weight(1f)
                        .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    else Modifier
                )
                .clip(RectangleShape)
                .background(Color(0xFF141414))
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            // Drag handle pill
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(36.dp)
                    .height(4.dp)
                    .background(Color(0xFF3F3F3F), RectangleShape)
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Top card: back + live value + keyframe / easing (same as the Transform panel)
            if (chrome != null) {
                TransformValueHeader(
                    valueText = "${widthPercent.roundToInt()}% × ${heightPercent.roundToInt()}%",
                    onBack = {
                        emitCrop()
                        onDismiss()
                    },
                    keyframeEnabled = insideClip,
                    hasKeyframes = hasKeyframes,
                    onKeyframeNow = onKeyframeNow,
                    onKeyframe = { onToggleKeyframes(cropProps) },
                    easingEnabled = easingEnabled,
                    easingOpen = showEasing && easingEnabled,
                    easing = segStart?.easing,
                    onEasing = { if (easingEnabled) showEasing = !showEasing }
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (showEasing && segStart != null && segEnd != null) {
                Box(modifier = Modifier.fillMaxWidth().height(180.dp)) {
                    EasingEditor(
                        easing = segStart.easing,
                        progress = ((relMs - segStart.timeMs).toFloat() /
                            (segEnd.timeMs - segStart.timeMs).coerceAtLeast(1L)).coerceIn(0f, 1f),
                        onBegin = onEasingBegin,
                        onChange = { e -> cropProps.forEach { onEasingChange(it, segStart.timeMs, e) } }
                    )
                }
            } else {
            // Aspect Ratio Preset Pills Row (Free, Original, Project, 9:16, 16:9, 1:1, etc.)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(CROP_PRESETS) { preset ->
                    val isSelected = (preset == activePreset)
                    Box(
                        modifier = Modifier
                            .clip(RectangleShape)
                            .background(if (isSelected) Color.White else Color(0xFF262626))
                            .border(1.dp, if (isSelected) Color.White else Color(0xFF3D3D3D), RectangleShape)
                            .bouncyClickable(onClick = { applyPresetRatio(preset) })
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = preset,
                            color = if (isSelected) Color(0xFF0F0F0F) else TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 4 Fine-Tune Ruler Dial Sliders (Width, Height, Horizontal, Vertical)
            RulerDialSlider(
                label = "Width",
                value = widthPercent,
                onValueChange = {
                    resizeAroundCenter(it / 100f, null)
                },
                min = 10f,
                max = 100f,
                unit = "%",
                isPercentage = true
            )

            RulerDialSlider(
                label = "Height",
                value = heightPercent,
                onValueChange = {
                    resizeAroundCenter(null, it / 100f)
                },
                min = 10f,
                max = 100f,
                unit = "%",
                isPercentage = true
            )

            RulerDialSlider(
                label = "Horizontal",
                value = horizontalPercent,
                onValueChange = {
                    horizontalPercent = it
                    emitCrop()
                },
                min = 0f,
                max = 100f,
                unit = "%",
                isPercentage = true
            )

            RulerDialSlider(
                label = "Vertical",
                value = verticalPercent,
                onValueChange = {
                    verticalPercent = it
                    emitCrop()
                },
                min = 0f,
                max = 100f,
                unit = "%",
                isPercentage = true
            )
            }
        }
    }
}

@Composable
private fun CropCircleHandle(modifier: Modifier = Modifier, sizeDp: Dp = 16.dp) {
    Box(
        modifier = modifier
            .size(sizeDp)
            .clip(CircleShape)
            .background(Color(0xFFABE41D))
            .border(1.5.dp, Color.White, CircleShape)
    )
}
