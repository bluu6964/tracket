package com.example.ui.components

import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import kotlin.math.roundToInt
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.model.AnimProp
import com.example.model.DEPTH_HIDE_Z
import com.example.model.depthFactorOf
import com.example.model.AspectRatio
import com.example.model.Easing
import com.example.model.indexOnTime
import com.example.model.segmentIndexAt
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.ui.theme.AmBase
import com.example.ui.theme.AmHairline
import com.example.ui.theme.AmHairlineStrong
import com.example.ui.theme.AmInk
import com.example.ui.theme.AmMint
import com.example.ui.theme.AmPanel
import com.example.ui.theme.AmPanelHigh
import com.example.ui.theme.AmTextFaint
import com.example.ui.theme.AmTickMajor
import com.example.ui.theme.AmTickMinor
import com.example.ui.theme.CanvasBackground
import com.example.ui.theme.EditorBackground
import com.example.ui.theme.IconActive
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.RulerSubTickColor
import com.example.ui.theme.RulerTickColor
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.amGlassBrush
import com.example.ui.theme.amMintCyanBrush
import com.example.ui.util.bouncyClickable

private enum class Transform2DPart { POSITION, SCALE, ROTATION }
private enum class Transform3DPart { POSITION_Z, ROTATION_X, ROTATION_Y }

/** Practically unlimited: the rulers only need *some* end, so these are far beyond anything usable. */
private const val TRANSFORM_BIG = 100000f
private const val Z_RANGE_MIN = 5000f
private const val Z_RANGE_MAX = 1000f
private const val TRANSFORM_SCALE_MIN = 1f
private const val TRANSFORM_SCALE_MAX = 100000f

private class RailItem(val icon: ImageVector, val badge: String?, val description: String)

private val rail2d = listOf(
    RailItem(Icons.Default.OpenWith, null, "Position"),
    RailItem(Icons.Default.OpenInFull, null, "Scale"),
    RailItem(Icons.Default.RotateRight, null, "Rotation")
)
private val rail3d = listOf(
    RailItem(Icons.Default.OpenWith, "Z", "Position Z"),
    RailItem(Icons.Default.RotateRight, "X", "Rotation X"),
    RailItem(Icons.Default.RotateRight, "Y", "Rotation Y")
)

enum class TransformTab(val title: String) {
    TWO_D("2D"),
    THREE_D("3D")
}

@Composable
fun TransformView(
    clip: TimelineClip?,
    aspectRatio: AspectRatio = AspectRatio.RATIO_16_9,
    currentPlayheadMs: Long,
    totalDurationMs: Long,
    isPlaying: Boolean,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onTogglePlay: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    onDismiss: () -> Unit,
    panelOnly: Boolean = false,
    /** false = no 2D / 3D switcher and no 3D properties (a Null object only has 2D move / scale / rotation). */
    show3d: Boolean = true,
    timescaleHeight: androidx.compose.ui.unit.Dp = 28.dp,
    layerRow: (@Composable () -> Unit)? = null,
    resetTick: Int = 0,
    /** Diamond button: add / remove a keyframe of this property at the playhead. */
    onToggleKeyframe: (AnimProp) -> Unit = {},
    /** Easing editor: a change is about to start (one undo step) / the curve of the segment that starts at the keyframe time changed. */
    onEasingBegin: () -> Unit = {},
    onEasingChange: (AnimProp, Long, Easing) -> Unit = { _, _, _ -> },
    onApplyTransform: (
        horizontal: Float,
        vertical: Float,
        scale: Float,
        rotation: Float,
        pivotX: Float,
        pivotY: Float,
        rot3dX: Float,
        rot3dY: Float,
        warp: Float,
        posZ: Float
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    // Current active mode tab: 2D, 3D
    var activeTab by remember { mutableStateOf(TransformTab.TWO_D) }
    if (!show3d && activeTab != TransformTab.TWO_D) activeTab = TransformTab.TWO_D
    var part2d by remember { mutableStateOf(Transform2DPart.POSITION) }
    var part3d by remember { mutableStateOf(Transform3DPart.POSITION_Z) }

    // 2D Transform state
    var horizontal by remember(clip?.id) { mutableFloatStateOf(clip?.transformX ?: 0.0f) }
    var vertical by remember(clip?.id) { mutableFloatStateOf(clip?.transformY ?: 0.0f) }
    var scale by remember(clip?.id) { mutableFloatStateOf(clip?.scalePercent ?: 100.0f) }
    var rotation by remember(clip?.id) { mutableFloatStateOf(clip?.rotationDeg ?: 0.0f) }

    // Pivot state
    var pivotX by remember(clip?.id) { mutableFloatStateOf(clip?.pivotX ?: 0.5f) }
    var pivotY by remember(clip?.id) { mutableFloatStateOf(clip?.pivotY ?: 0.5f) }

    // 3D Transform state
    var rot3dX by remember(clip?.id) { mutableFloatStateOf(clip?.rotation3dX ?: 0.0f) }
    var rot3dY by remember(clip?.id) { mutableFloatStateOf(clip?.rotation3dY ?: 0.0f) }

    // Depth (Z) position
    var posZ by remember(clip?.id) { mutableFloatStateOf(clip?.transformZ ?: 0.0f) }

    // Warp state
    var warpDistort by remember(clip?.id) { mutableFloatStateOf(clip?.warpDistort ?: 0.0f) }

    // With keyframes the clip given here is the animated one at the playhead: follow it while the
    // playhead moves or a keyframe changes, so the rulers / pad always show the real current value.
    // Every change of the layer (also one made on the canvas by dragging / scaling / rotating it)
    // re-syncs the panel, not only keyframe changes.
    val animSyncKey: Any? = if (clip != null) {
        listOf(
            currentPlayheadMs, clip.keyframes,
            clip.transformX, clip.transformY, clip.scalePercent, clip.rotationDeg,
            clip.pivotX, clip.pivotY, clip.rotation3dX, clip.rotation3dY,
            clip.transformZ, clip.warpDistort
        )
    } else null
    LaunchedEffect(clip?.id, animSyncKey) {
        if (clip != null) {
            horizontal = clip.transformX
            vertical = clip.transformY
            scale = clip.scalePercent
            rotation = clip.rotationDeg
            pivotX = clip.pivotX
            pivotY = clip.pivotY
            rot3dX = clip.rotation3dX
            rot3dY = clip.rotation3dY
            warpDistort = clip.warpDistort
            posZ = clip.transformZ
        }
    }

    fun emitTransform() {
        onApplyTransform(
            horizontal,
            vertical,
            scale,
            rotation,
            pivotX,
            pivotY,
            rot3dX,
            rot3dY,
            warpDistort,
            posZ
        )
    }

    fun resetAll() {
        horizontal = 0.0f
        vertical = 0.0f
        scale = 100.0f
        rotation = 0.0f
        pivotX = 0.5f
        pivotY = 0.5f
        rot3dX = 0.0f
        rot3dY = 0.0f
        warpDistort = 0.0f
        posZ = 0.0f
        emitTransform()
    }

    androidx.compose.runtime.LaunchedEffect(resetTick) { if (resetTick > 0) resetAll() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(if (panelOnly) EditorBackground else AmBase)
            .testTag("transform_screen")
    ) {
        if (panelOnly) {
            // Play / undo-redo stays universal (main PlayControlsSection). Only timescale + layer strip here.
            TimescaleSection(
                height = timescaleHeight,
                playheadMs = { currentPlayheadMs },
                totalDurationMs = totalDurationMs
            )
            if (layerRow != null) layerRow() else TransformLayerStrip(
                clip = clip,
                currentPlayheadMs = currentPlayheadMs,
                totalDurationMs = totalDurationMs,
                onSeekTo = onSeekTo,
                onReset = { resetAll() }
            )
        }


        // 6. Bottom Transform Panel (matching screenshot)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // In the editor the panel takes all the space under the layer strip.
                .then(if (panelOnly) Modifier.weight(1f) else Modifier)
                .glassSurface(
                    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    fill = amGlassBrush()
                )
                .panelSheenStrip()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            // Drag handle pill at top
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(36.dp)
                    .height(4.dp)
                    .background(AmHairlineStrong, RoundedCornerShape(2.dp))
            )

            Spacer(modifier = Modifier.height(4.dp))

            val headerText = when (activeTab) {
                TransformTab.TWO_D -> when (part2d) {
                    Transform2DPart.POSITION -> "X ${horizontal.roundToInt()}   Y ${vertical.roundToInt()}"
                    Transform2DPart.SCALE -> "${scale.roundToInt()}%"
                    Transform2DPart.ROTATION -> "${rotation.roundToInt()}°"
                }
                TransformTab.THREE_D -> when (part3d) {
                    Transform3DPart.POSITION_Z -> "Z ${posZ.roundToInt()}"
                    Transform3DPart.ROTATION_X -> "X ${rot3dX.roundToInt()}°"
                    Transform3DPart.ROTATION_Y -> "Y ${rot3dY.roundToInt()}°"
                }
            }
            // ---- Keyframe / easing state of the property that is open right now ----
            val curProp = when (activeTab) {
                TransformTab.TWO_D -> when (part2d) {
                    Transform2DPart.POSITION -> AnimProp.POSITION
                    Transform2DPart.SCALE -> AnimProp.SCALE
                    Transform2DPart.ROTATION -> AnimProp.ROTATION
                }
                TransformTab.THREE_D -> when (part3d) {
                    Transform3DPart.POSITION_Z -> AnimProp.POS_Z
                    Transform3DPart.ROTATION_X -> AnimProp.ROT_X
                    Transform3DPart.ROTATION_Y -> AnimProp.ROT_Y
                }
            }
            val relMs = currentPlayheadMs - (clip?.startMs ?: 0L)
            val kfList = clip?.keyframes?.get(curProp).orEmpty()
            val insideClip = clip != null && currentPlayheadMs >= clip.startMs && currentPlayheadMs <= clip.endMs
            val onKeyframe = kfList.indexOnTime(relMs) >= 0
            val segIdx = kfList.segmentIndexAt(relMs)
            // Easing only works between two keyframes of this property.
            val easingEnabled = segIdx >= 0
            var showEasing by remember { mutableStateOf(false) }
            LaunchedEffect(easingEnabled, curProp) { if (!easingEnabled) showEasing = false }
            LaunchedEffect(curProp) { showEasing = false }
            val segStart = if (segIdx >= 0) kfList[segIdx] else null
            val segEnd = if (segIdx >= 0) kfList[segIdx + 1] else null

            TransformValueHeader(
                valueText = headerText,
                onBack = if (panelOnly) onDismiss else null,
                keyframeEnabled = insideClip,
                hasKeyframes = kfList.isNotEmpty(),
                onKeyframeNow = onKeyframe,
                onKeyframe = { clip?.let { onToggleKeyframe(curProp) } },
                easingEnabled = easingEnabled,
                easingOpen = showEasing && easingEnabled,
                easing = segStart?.easing,
                onEasing = { if (easingEnabled) showEasing = !showEasing }
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Left icons = sections. Only the chosen section is shown, and it fills the free space.
            if (showEasing && segStart != null && segEnd != null) {
                Box(
                    modifier = if (panelOnly) Modifier.weight(1f).fillMaxWidth()
                    else Modifier.fillMaxWidth().height(110.dp)
                ) {
                    EasingEditor(
                        easing = segStart.easing,
                        progress = ((relMs - segStart.timeMs).toFloat() /
                            (segEnd.timeMs - segStart.timeMs).coerceAtLeast(1L)).coerceIn(0f, 1f),
                        onBegin = onEasingBegin,
                        onChange = { e -> onEasingChange(curProp, segStart.timeMs, e) }
                    )
                }
            } else AnimatedContent(
                targetState = activeTab,
                modifier = if (panelOnly) Modifier.weight(1f).fillMaxWidth() else Modifier,
                transitionSpec = {
                    fadeIn(androidx.compose.animation.core.tween(120, delayMillis = 30)) togetherWith
                        fadeOut(androidx.compose.animation.core.tween(70))
                },
                label = "transformTabTransition"
            ) { currentTab ->
                val areaMod = if (panelOnly) Modifier.fillMaxSize()
                else Modifier.fillMaxWidth().height(110.dp)
                when (currentTab) {
                    TransformTab.TWO_D -> Row(modifier = areaMod, verticalAlignment = Alignment.CenterVertically) {
                        IconRail(
                            items = rail2d,
                            selectedIndex = part2d.ordinal,
                            onSelect = { part2d = Transform2DPart.values()[it] }
                        )
                        Box(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            when (part2d) {
                                Transform2DPart.POSITION -> PositionPad(
                                    x = horizontal,
                                    y = vertical,
                                    onChange = { nx, ny ->
                                        horizontal = nx
                                        vertical = ny
                                        emitTransform()
                                    }
                                )
                                Transform2DPart.SCALE -> RulerDialSlider(
                                    label = "",
                                    labelWidth = 0.dp,
                                    showValue = false,
                                    value = scale,
                                    onValueChange = {
                                        scale = it
                                        emitTransform()
                                    },
                                    min = TRANSFORM_SCALE_MIN,
                                    max = TRANSFORM_SCALE_MAX,
                                    unit = "",
                                    isPercentage = true
                                )
                                Transform2DPart.ROTATION -> RulerDialSlider(
                                    label = "",
                                    labelWidth = 0.dp,
                                    showValue = false,
                                    value = rotation,
                                    onValueChange = {
                                        rotation = it
                                        emitTransform()
                                    },
                                    min = -TRANSFORM_BIG,
                                    max = TRANSFORM_BIG,
                                    unit = "°",
                                    isPercentage = false
                                )
                            }
                        }
                    }

                    TransformTab.THREE_D -> Row(modifier = areaMod, verticalAlignment = Alignment.CenterVertically) {
                        IconRail(
                            items = rail3d,
                            selectedIndex = part3d.ordinal,
                            onSelect = { part3d = Transform3DPart.values()[it] }
                        )
                        Box(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            when (part3d) {
                                Transform3DPart.POSITION_Z -> RulerDialSlider(
                                    label = "",
                                    labelWidth = 0.dp,
                                    showValue = false,
                                    value = posZ,
                                    onValueChange = {
                                        posZ = it
                                        emitTransform()
                                    },
                                    // Depth: 0 = canvas plane, + comes toward the camera (gone from DEPTH_HIDE_Z),
                                    // - goes away (smaller). More than this changes nothing visible.
                                    min = -Z_RANGE_MIN,
                                    max = Z_RANGE_MAX,
                                    unit = "",
                                    isPercentage = false
                                )
                                Transform3DPart.ROTATION_X -> RulerDialSlider(
                                    label = "",
                                    labelWidth = 0.dp,
                                    showValue = false,
                                    value = rot3dX,
                                    onValueChange = {
                                        rot3dX = it
                                        emitTransform()
                                    },
                                    min = -TRANSFORM_BIG,
                                    max = TRANSFORM_BIG,
                                    unit = "°",
                                    isPercentage = false
                                )
                                Transform3DPart.ROTATION_Y -> RulerDialSlider(
                                    label = "",
                                    labelWidth = 0.dp,
                                    showValue = false,
                                    value = rot3dY,
                                    onValueChange = {
                                        rot3dY = it
                                        emitTransform()
                                    },
                                    min = -TRANSFORM_BIG,
                                    max = TRANSFORM_BIG,
                                    unit = "°",
                                    isPercentage = false
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Bottom mode switcher tabs: [2D] [3D]
            if (show3d) Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TransformTab.values().forEach { tab ->
                    val isSelected = tab == activeTab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .then(
                                if (isSelected) Modifier.background(amMintCyanBrush(), RoundedCornerShape(8.dp))
                                else Modifier
                            )
                            .bouncyClickable(
                                pressedScale = 0.94f,
                                onClick = { activeTab = tab }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!isSelected) KitGlassTile(Modifier.matchParentSize())
                        Text(
                            text = tab.title,
                            color = if (isSelected) AmInk else TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

/**
 * 4-lobed Pink Crosshair Pivot Anchor Target exactly matching screenshot.
 */
@Composable
fun PivotTargetCrosshair(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(36.dp)) {
        val pinkColor = AmMint
        val cX = size.width / 2f
        val cY = size.height / 2f
        val centerRadius = 3.5.dp.toPx()
        val armLength = 7.dp.toPx()
        val armWidth = 3.dp.toPx()
        val gap = 4.dp.toPx()

        // Center circle
        drawCircle(color = pinkColor, radius = centerRadius)
        drawCircle(color = Color.White, radius = 1.2.dp.toPx())

        // Top arm
        drawRoundRect(
            color = pinkColor,
            topLeft = Offset(cX - armWidth / 2f, cY - gap - armLength),
            size = Size(armWidth, armLength),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(armWidth / 2f, armWidth / 2f)
        )
        // Bottom arm
        drawRoundRect(
            color = pinkColor,
            topLeft = Offset(cX - armWidth / 2f, cY + gap),
            size = Size(armWidth, armLength),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(armWidth / 2f, armWidth / 2f)
        )
        // Left arm
        drawRoundRect(
            color = pinkColor,
            topLeft = Offset(cX - gap - armLength, cY - armWidth / 2f),
            size = Size(armLength, armWidth),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(armWidth / 2f, armWidth / 2f)
        )
        // Right arm
        drawRoundRect(
            color = pinkColor,
            topLeft = Offset(cX + gap, cY - armWidth / 2f),
            size = Size(armLength, armWidth),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(armWidth / 2f, armWidth / 2f)
        )
    }
}

/**
 * 2D position pad (Alight Motion style): put a finger on the pad and move it, the layer's X and Y
 * change together by the same distance the finger moves (1 px = 1 unit). Shows the live X / Y.
 */
@Composable
private fun PositionPad(
    x: Float,
    y: Float,
    onChange: (x: Float, y: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val curX by rememberUpdatedState(x)
    val curY by rememberUpdatedState(y)
    val change by rememberUpdatedState(onChange)
    val range = 100000f   // practically unlimited
    val visualRange = 500f // the dot shows a +-500 window, then stays at the edge
    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(10.dp))
                .background(AmPanel)
                .border(1.dp, AmHairline, RoundedCornerShape(10.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var px = curX
                        var py = curY
                        var last = down.position
                        var lastX = curX
                        var lastY = curY
                        while (true) {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            c.consume()
                            val d = c.position - last
                            last = c.position
                            px = (px + d.x).coerceIn(-range, range)
                            py = (py + d.y).coerceIn(-range, range)
                            val nx = px.roundToInt().toFloat()
                            val ny = py.roundToInt().toFloat()
                            if (nx != lastX || ny != lastY) {
                                lastX = nx
                                lastY = ny
                                change(nx, ny)
                            }
                        }
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val grid = 24.dp.toPx()
                var gx = w / 2f % grid
                while (gx < w) {
                    drawLine(AmTickMinor, Offset(gx, 0f), Offset(gx, h), strokeWidth = 1.dp.toPx())
                    gx += grid
                }
                var gy = h / 2f % grid
                while (gy < h) {
                    drawLine(AmTickMinor, Offset(0f, gy), Offset(w, gy), strokeWidth = 1.dp.toPx())
                    gy += grid
                }
                // Center axes
                drawLine(AmTickMajor, Offset(w / 2f, 0f), Offset(w / 2f, h), strokeWidth = 1.5.dp.toPx())
                drawLine(AmTickMajor, Offset(0f, h / 2f), Offset(w, h / 2f), strokeWidth = 1.5.dp.toPx())
                // Current position
                val r = 9.dp.toPx()
                val dx = (w / 2f + (x / visualRange) * (w / 2f)).coerceIn(r, w - r)
                val dy = (h / 2f + (y / visualRange) * (h / 2f)).coerceIn(r, h - r)
                drawCircle(AmMint.copy(alpha = 0.25f), radius = r * 1.9f, center = Offset(dx, dy))
                drawCircle(AmMint, radius = r, center = Offset(dx, dy))
            }
        }
    }
}

/** Left rail of icons: tap one to show only that section. [RailItem.badge] is a small axis letter. */
@Composable
private fun IconRail(
    items: List<RailItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.width(44.dp).fillMaxHeight(),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items.forEachIndexed { i, item ->
            val selected = i == selectedIndex
            val tint = if (selected) AmMint else TextPrimary
            Box(
                modifier = Modifier
                    .size(width = 40.dp, height = 32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .bouncyClickable(onClick = { onSelect(i) }),
                contentAlignment = Alignment.Center
            ) {
                if (selected) KitGlassTile(Modifier.matchParentSize())
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.description,
                    tint = tint,
                    modifier = Modifier.size(22.dp)
                )
                if (item.badge != null) {
                    Text(
                        text = item.badge,
                        color = tint,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 3.dp, bottom = 1.dp)
                    )
                }
            }
        }
    }
}

/**
 * Top of the panel (Alight Motion style): back on the left, the live value in the middle,
 * keyframe diamond + easing curve on the right.
 *
 * Diamond: filled = the playhead sits on a keyframe (tap removes it), outlined = this property is
 * animated but there is no keyframe here, grey = not animated yet (tap adds a keyframe).
 * Easing: only usable between two keyframes; it shows the curve of that segment.
 */
@Composable
internal fun TransformValueHeader(
    valueText: String,
    onBack: (() -> Unit)?,
    keyframeEnabled: Boolean,
    hasKeyframes: Boolean,
    onKeyframeNow: Boolean,
    onKeyframe: () -> Unit,
    easingEnabled: Boolean,
    easingOpen: Boolean,
    easing: Easing?,
    onEasing: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = AmMint
    Row(
        modifier = modifier.fillMaxWidth().height(40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .bouncyClickable(onClick = onBack)
                    .testTag("panel_back_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Back",
                    tint = IconActive,
                    modifier = Modifier.size(26.dp)
                )
            }
        } else {
            Spacer(modifier = Modifier.width(40.dp))
        }

        Text(
            text = valueText,
            color = TextPrimary,
            fontSize = 15.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.weight(1f)
        )

        // Keyframe diamond
        Box(
            modifier = Modifier
                .size(40.dp)
                .graphicsLayer { alpha = if (keyframeEnabled) 1f else 0.35f }
                .bouncyClickable(onClick = { if (keyframeEnabled) onKeyframe() })
                .testTag("transform_keyframe_button"),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer { rotationZ = 45f }
                    .background(if (onKeyframeNow) accent else AmPanelHigh, RoundedCornerShape(3.dp))
                    .then(
                        if (hasKeyframes && !onKeyframeNow) Modifier.border(1.5.dp, accent, RoundedCornerShape(3.dp))
                        else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (!onKeyframeNow) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Keyframe",
                        tint = Color.White,
                        modifier = Modifier
                            .size(14.dp)
                            .graphicsLayer { rotationZ = -45f }
                    )
                }
            }
        }

        // Easing curve (between two keyframes only)
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (easingOpen) AmPanelHigh else Color.Transparent)
                .bouncyClickable(onClick = { if (easingEnabled) onEasing() })
                .testTag("transform_easing_button"),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(20.dp)) {
                drawEasingIcon(
                    easing = if (easingEnabled) easing else null,
                    color = when {
                        !easingEnabled -> AmTextFaint
                        easingOpen -> accent
                        else -> TextPrimary
                    },
                    strokeWidth = 2.dp.toPx()
                )
            }
        }
    }
}
