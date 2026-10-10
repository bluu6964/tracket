package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.example.R
import com.example.model.LayerType
import com.example.model.MultiAction
import com.example.model.MultiBarMode
import com.example.model.MultiSelectOps
import com.example.model.TIMELINE_PX_PER_MS
import com.example.model.TimelineClip
import com.example.model.TimelineTrack
import com.example.ui.theme.EditorBackground
import com.example.ui.theme.EditorSurface
import com.example.ui.theme.IconActive
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.PlayheadColor
import com.example.ui.theme.SelectedClipBorder
import com.example.ui.theme.TextPrimary
import com.example.ui.util.bouncyClickable
import com.example.ui.util.enterPop
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun TimelineLayerPanel(
    height: Dp,
    tracks: List<TimelineTrack>,
    playheadMs: () -> Long,
    totalDurationMs: Long,
    selectedClipId: String?,
    isMasterMuted: Boolean,
    onSeekTo: (Long) -> Unit,
    onSeekFinished: (Long) -> Unit = {},
    onClipSelect: (String?) -> Unit,
    onSplitClip: () -> Unit,
    onDeleteClip: () -> Unit,
    onDuplicateClip: () -> Unit,
    onToggleMasterAudio: () -> Unit,
    /** Horizontal drag on a layer: move it earlier/later on the timeline (ms delta). */
    onMoveClipInTime: (clipId: String, deltaMs: Long) -> Unit = { _, _ -> },
    /** Called once when a layer time-drag starts (for undo). */
    onBeginClipTimeDrag: () -> Unit = {},
    /** Vertical reorder: move lane fromIndex → toIndex (0 = top). */
    onReorderLane: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
    /** Trim handles of the selected layer: edge (true = left) and ms delta. */
    onTrimClipEdge: (clipId: String, isLeft: Boolean, deltaMs: Long) -> Unit = { _, _, _ -> },
    /** Eye control: toggle layer visibility on canvas/export. */
    onToggleClipVisibility: (clipId: String) -> Unit = {},
    /** Timeline scale in px-per-ms (supports pinch zoom). */
    pxPerMs: Float = TIMELINE_PX_PER_MS,
    onPxPerMsChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
    waveforms: Map<String, FloatArray> = emptyMap(),
    /** Layers checked in multi-select mode (empty = mode is off). */
    multiSelectIds: Set<String> = emptySet(),
    /** Long press on a layer's left control starts multi-select with that layer. */
    onLongPressLane: (clipId: String) -> Unit = {},
    /** Tap on a layer's left control while multi-select is on: check / uncheck it. */
    onToggleMulti: (clipId: String) -> Unit = {},
    /** A button of the multi-select bar. */
    onMultiAction: (MultiAction) -> Unit = {}
) {
    val isMulti = multiSelectIds.isNotEmpty()
    val coroutineScope = rememberCoroutineScope()
    var flingJob by remember { mutableStateOf<Job?>(null) }
    val latestPxPerMs by rememberUpdatedState(pxPerMs)
    val latestOnPx by rememberUpdatedState(onPxPerMsChange)

    // The gesture block below is created ONCE (key = Unit). Before, it was keyed on
    // currentPlayheadMs, so every drag frame restarted it and the swipe was dropped
    // after a short distance. These always hold the latest values without restarting it.
    val latestPlayheadProvider by rememberUpdatedState(playheadMs)
    val latestTotalMs by rememberUpdatedState(totalDurationMs)
    val latestSeek by rememberUpdatedState(onSeekTo)
    val latestSeekFinished by rememberUpdatedState(onSeekFinished)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(EditorBackground)
            .testTag("layer_panel_timeline")
            // One gesture loop: 1-finger horizontal = scrub/fling, 2-finger = pinch zoom.
            // detectTransformGestures was eating single-finger swipes, so this is unified.
            .pointerInput(Unit) {
                val velocityTracker = VelocityTracker()
                val pxPerDp = density
                val touchSlop = viewConfiguration.touchSlop

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    flingJob?.cancel()
                    velocityTracker.resetTracking()
                    velocityTracker.addPosition(down.uptimeMillis, down.position)
                    var posMs = latestPlayheadProvider().toFloat()
                    var pastTouchSlop = false
                    var isPinch = false
                    var lastSpan = 0f
                    var didScrub = false
                    // Slop is measured from the finger-down point (not per event), otherwise a
                    // slow swipe never reached the slop and the timeline felt dead/laggy.
                    val downPos = down.position
                    var lastX = down.position.x

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }

                        if (pressed.isEmpty()) break

                        if (pressed.size >= 2) {
                            // ---- Pinch zoom ----
                            isPinch = true
                            val a = pressed[0].position
                            val b = pressed[1].position
                            val span = (a - b).getDistance()
                            if (lastSpan > 1f && span > 1f) {
                                val zoomChange = span / lastSpan
                                if (abs(zoomChange - 1f) > 0.001f) {
                                    val next = (latestPxPerMs * zoomChange).coerceIn(
                                        TIMELINE_PX_PER_MS * 0.25f,
                                        TIMELINE_PX_PER_MS * 8f
                                    )
                                    latestOnPx(next)
                                }
                            }
                            lastSpan = span
                            pressed.forEach { it.consume() }
                        } else if (!isPinch) {
                            // ---- Single-finger horizontal scrub ----
                            val change = pressed[0]
                            // A child (vertical scroll, layer drag) already took this gesture.
                            if (change.isConsumed) break
                            val totalDx = change.position.x - downPos.x
                            val totalDy = change.position.y - downPos.y
                            if (!pastTouchSlop) {
                                if (abs(totalDx) > touchSlop || abs(totalDy) > touchSlop) {
                                    // Mostly vertical: leave it to verticalScroll.
                                    if (abs(totalDy) > abs(totalDx) * 1.2f) break
                                    pastTouchSlop = true
                                    lastX = downPos.x // include the distance travelled to reach the slop
                                }
                            }
                            if (pastTouchSlop) {
                                change.consume()
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                val dx = change.position.x - lastX
                                lastX = change.position.x
                                val deltaMs = -(dx / pxPerDp) / latestPxPerMs
                                posMs = (posMs + deltaMs).coerceIn(0f, latestTotalMs.toFloat())
                                latestSeek(posMs.roundToLong())
                                didScrub = true
                            }
                        }
                    }

                    // Fling after a horizontal scrub (not after pinch / vertical scroll).
                    if (didScrub && !isPinch) {
                        val velocityPxPerSec = velocityTracker.calculateVelocity().x
                        if (abs(velocityPxPerSec) > 60f) {
                            val startMs = posMs
                            val velocityMsPerSec = -(velocityPxPerSec / pxPerDp) / latestPxPerMs * FLING_VELOCITY_BOOST
                            flingJob = coroutineScope.launch {
                                var last = startMs
                                AnimationState(
                                    initialValue = startMs,
                                    initialVelocity = velocityMsPerSec
                                ).animateDecay(
                                    exponentialDecay(
                                        frictionMultiplier = FLING_FRICTION,
                                        absVelocityThreshold = 8f
                                    )
                                ) {
                                    val total = latestTotalMs.toFloat()
                                    val ms = value.coerceIn(0f, total)
                                    last = ms
                                    latestSeek(ms.roundToLong())
                                    if (ms <= 0f || ms >= total) cancelAnimation()
                                }
                                latestSeekFinished(last.roundToLong())
                            }
                        } else {
                            latestSeekFinished(posMs.roundToLong())
                        }
                    }
                }
            }
    ) {
        val screenWidth = maxWidth
        val centerLineX = screenWidth / 2 // EXACT 50% HORIZONTAL CENTER OF SCREEN

        val verticalScrollState = rememberScrollState()
        val dpToPx = LocalDensity.current.density

        // Real timeline length: the longest clip (any track) + room for the + button and the
        // right half of the screen. Clips used to be capped at the screen width because their
        // parent had a bounded width; the horizontalScroll below (scrolling disabled) only
        // hands its child unbounded width so a long clip keeps its true size.
        val contentEndMs = maxOf(
            totalDurationMs,
            tracks.maxOfOrNull { t -> t.clips.maxOfOrNull { it.endMs } ?: 0L } ?: 0L
        )
        val contentWidth = (contentEndMs * pxPerMs).dp + screenWidth + 64.dp + TrimHandleWidth

        // Time window that is actually on screen (+ margin) so only visible thumbnails load.
        // The window is snapped to 2 s steps: while swiping, the clips only recompose when the
        // window steps (every ~2 s), not on every frame. The margin (~2.2 s) is larger than the step.
        // Look-ahead of one full screen on each side and a 3 s step: thumbnails are composed and
        // requested long before they scroll into view, so swiping never waits for them (this was
        // the periodic stutter).
        val marginMs = (screenWidth.value / pxPerMs).toLong()
        val windowStepMs = 3_000L
        // derivedStateOf: the panel recomposes only when the 2 s window steps, never per frame.
        val visibleFromMs by remember(pxPerMs, screenWidth) {
            derivedStateOf {
                val rawFromMs = playheadMs() - (centerLineX.value / pxPerMs).toLong() - marginMs
                Math.floorDiv(rawFromMs, windowStepMs) * windowStepMs
            }
        }
        val visibleToMs by remember(pxPerMs, screenWidth) {
            derivedStateOf {
                val rawToMs = playheadMs() +
                    ((screenWidth - centerLineX).value / pxPerMs).toLong() + marginMs
                (Math.floorDiv(rawToMs, windowStepMs) + 1L) * windowStepMs
            }
        }

        // 1. Left layer controls (eye + thumb) + timeline tracks, shared vertical scroll.
        val leftRailWidth = 76.dp
        Row(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(verticalScrollState)
                .padding(top = 4.dp, bottom = if (isMulti) 68.dp else 4.dp)
        ) {
            // Left rail — Alight Motion style: eye + layer preview for each lane.
            Column(
                modifier = Modifier
                    .width(leftRailWidth), // flush with the screen's left edge, no side padding
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tracks.forEach { track ->
                    val clip = track.clips.firstOrNull()
                    val rowH = clip?.let { laneHeightFor(it.type) } ?: 32.dp
                    LayerLaneControl(
                        clip = clip,
                        height = rowH,
                        isMulti = isMulti,
                        isChecked = clip != null && clip.id in multiSelectIds,
                        onToggleVisibility = { id -> onToggleClipVisibility(id) },
                        onSelect = { id -> onClipSelect(id) },
                        onLongPress = onLongPressLane,
                        onToggleMulti = onToggleMulti
                    )
                }
            }

            // Timeline content, shifted so currentPlayheadMs sits under the center line.
            // Center line is still screen-relative; offset accounts for left rail.
            val railPx = with(LocalDensity.current) { leftRailWidth.toPx() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState(), enabled = false)
            ) {
                Column(
                    modifier = Modifier
                        .width(contentWidth)
                        // graphicsLayer = draw phase only: no measure/layout per frame while scrolling.
                        .graphicsLayer {
                            translationX = (centerLineX.value - playheadMs() * pxPerMs) * dpToPx - railPx - TrimHandleWidth.toPx()
                        },
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tracks.forEachIndexed { laneIndex, track ->
                        androidx.compose.runtime.key(track.id) {
                        TimelineTrackRow(
                            track = track,
                            laneIndex = laneIndex,
                            laneCount = tracks.size,
                            selectedClipId = selectedClipId,
                            onClipSelect = onClipSelect,
                            onMoveClipInTime = onMoveClipInTime,
                            onBeginClipTimeDrag = onBeginClipTimeDrag,
                            onReorderLane = onReorderLane,
                            onTrimClipEdge = onTrimClipEdge,
                            pxPerMs = pxPerMs,
                            visibleFromMs = visibleFromMs,
                            visibleToMs = visibleToMs,
                            waveforms = waveforms,
                            multiSelectIds = multiSelectIds
                        )
                        }
                    }
                }
            }
        }

        // 2. Quick actions + master speaker (bottom-left, clear of the layer rail)
        if (!isMulti) Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(EditorBackground.copy(alpha = 0.9f), CircleShape)
                    .bouncyClickable(onClick = onToggleMasterAudio),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isMasterMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Master Audio",
                    tint = IconActive,
                    modifier = Modifier.size(20.dp)
                )
            }
            if (selectedClipId != null) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(EditorSurface.copy(alpha = 0.9f), CircleShape)
                            .bouncyClickable(onClick = onSplitClip),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SmartButton,
                            contentDescription = "Split Clip",
                            tint = NeonCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(EditorSurface.copy(alpha = 0.9f), CircleShape)
                            .bouncyClickable(onClick = onDuplicateClip),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Duplicate Clip",
                            tint = IconActive,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(Color(0xFF3B1818).copy(alpha = 0.9f), CircleShape)
                            .bouncyClickable(onClick = onDeleteClip),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Clip",
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            } // selected tools Column
            } // if selectedClipId
        } // bottom-left Column

        // 4. VERTICAL LOCKED PLAYHEAD LINE ("খারাভাবে middle এ locked timeline")
        Canvas(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .offset(x = centerLineX - 1.dp)
        ) {
            drawLine(
                color = PlayheadColor,
                start = Offset(1.dp.toPx(), 0f),
                end = Offset(1.dp.toPx(), size.height),
                strokeWidth = 2.dp.toPx()
            )
        }

        // 5. Multi-select bar (drawn after the playhead line so the line passes behind it).
        if (isMulti) {
            MultiBarHost(
                tracks = tracks,
                multiSelectIds = multiSelectIds,
                playheadMs = playheadMs,
                onAction = onMultiAction,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .enterPop(fromBottom = true)
            )
        }
    }
}


/**
 * Reads the playhead itself, so while scrubbing only this small bar recomposes (not the whole
 * timeline) when the option group changes (split / start / end).
 */
@Composable
private fun MultiBarHost(
    tracks: List<TimelineTrack>,
    multiSelectIds: Set<String>,
    playheadMs: () -> Long,
    onAction: (MultiAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val mode by remember(tracks, multiSelectIds) {
        derivedStateOf {
            val checked = tracks.flatMap { it.clips }.filter { it.id in multiSelectIds }
            MultiSelectOps.barMode(checked, playheadMs())
        }
    }
    MultiSelectBar(mode = mode, onAction = onAction, modifier = modifier)
}

@Composable
private fun LayerLaneControl(
    clip: TimelineClip?,
    height: Dp,
    isMulti: Boolean,
    isChecked: Boolean,
    onToggleVisibility: (String) -> Unit,
    onSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onToggleMulti: (String) -> Unit
) {
    if (clip == null) {
        Spacer(modifier = Modifier.height(height))
        return
    }
    val raw = (clip.textColor.takeIf { it != 0L } ?: clip.color)
    val thumbColor = Color(
        red = ((raw shr 16) and 0xFF).toInt() / 255f,
        green = ((raw shr 8) and 0xFF).toInt() / 255f,
        blue = (raw and 0xFF).toInt() / 255f,
        alpha = 1f
    )
    // Alight Motion style: a pill glued to the left edge of the screen, rounded only on the
    // right end, big outlined eye + a square layer thumbnail.
    val haptic = LocalHapticFeedback.current
    val latestSelect by rememberUpdatedState(onSelect)
    val latestLong by rememberUpdatedState(onLongPress)
    val latestToggle by rememberUpdatedState(onToggleMulti)
    val latestMulti by rememberUpdatedState(isMulti)
    Row(
        modifier = Modifier
            .height(height)
            .fillMaxWidth()
            // No background: just eye/check + thumbnail.
            // Tap = select (or check/uncheck in multi-select); long press = start multi-select.
            .pointerInput(clip.id) {
                detectTapGestures(
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (latestMulti) latestToggle(clip.id) else latestLong(clip.id)
                    },
                    onTap = {
                        if (latestMulti) latestToggle(clip.id) else latestSelect(clip.id)
                    }
                )
            }
            .padding(start = 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (isMulti) {
            // Multi-select: the eye turns into a check circle (empty when not checked).
            Box(
                modifier = Modifier.size(26.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isChecked) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(Color(0xFF00FFA8), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = Color(0xFF053B2E),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        } else Icon(
            imageVector = if (clip.isVisible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
            contentDescription = if (clip.isVisible) "Hide layer" else "Show layer",
            tint = if (clip.isVisible) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.35f),
            modifier = Modifier
                .size(24.dp)
                .bouncyClickable(pressedScale = 1f, onClick = { onToggleVisibility(clip.id) })
        )
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(2.dp))
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(2.dp))
                .background(
                    when (clip.type) {
                        LayerType.VIDEO -> Color(0xFF000000)
                        LayerType.AUDIO -> Color(0xFF1B4332)
                        LayerType.SHAPE -> thumbColor
                        LayerType.TEXT -> Color(0xFF5C3D10)
                        else -> thumbColor
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            when (clip.type) {
                LayerType.SHAPE -> {
                    // Mini shape cue
                    Box(
                        Modifier
                            .size(12.dp)
                            .clip(
                                when ((clip.shapePattern ?: "CIRCLE").uppercase()) {
                                    "RECT", "RECTANGLE", "SQUARE" -> RoundedCornerShape(2.dp)
                                    "ROUNDED", "ROUNDED_RECT" -> RoundedCornerShape(4.dp)
                                    else -> CircleShape
                                }
                            )
                            .background(thumbColor)
                    )
                }
                LayerType.TEXT -> {
                    Text("T", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                LayerType.AUDIO -> {
                    Icon(Icons.Default.Mic, null, tint = Color.White, modifier = Modifier.size(12.dp))
                }
                else -> {
                    // video / other: solid chip already shows
                }
            }
        }
    }
}

@Composable
fun TimelineTrackRow(
    track: TimelineTrack,
    laneIndex: Int = 0,
    laneCount: Int = 1,
    selectedClipId: String?,
    onClipSelect: (String?) -> Unit,
    onMoveClipInTime: (clipId: String, deltaMs: Long) -> Unit = { _, _ -> },
    onBeginClipTimeDrag: () -> Unit = {},
    onReorderLane: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
    onTrimClipEdge: (clipId: String, isLeft: Boolean, deltaMs: Long) -> Unit = { _, _, _ -> },
    pxPerMs: Float = TIMELINE_PX_PER_MS,
    visibleFromMs: Long = Long.MIN_VALUE / 4,
    visibleToMs: Long = Long.MAX_VALUE / 4,
    waveforms: Map<String, FloatArray> = emptyMap(),
    multiSelectIds: Set<String> = emptySet()
) {
    // Alight Motion: one layer per line → fixed row height by layer type.
    val trackHeight = track.clips.maxOfOrNull { laneHeightFor(it.type) } ?: 32.dp
    val density = LocalDensity.current
    val pxPerDp = density.density
    // How many vertical pixels ≈ one lane step for reorder.
    val laneStepPx = with(density) { (trackHeight + 6.dp).toPx() }

    // Room on the left so the left trim handle of a layer at 0:00 is still inside the lane.
    val handlePx = with(density) { TrimHandleWidth.toPx() }
    var dragOffsetX by remember { mutableStateOf(0f) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    // Lane slides smoothly to its new row after a reorder / when a lane above is added or removed.
    val slideY = remember { Animatable(0f) }
    var prevLaneIndex by remember { mutableStateOf(laneIndex) }
    LaunchedEffect(laneIndex) {
        if (prevLaneIndex != laneIndex) {
            slideY.snapTo((prevLaneIndex - laneIndex) * laneStepPx)
            prevLaneIndex = laneIndex
            slideY.animateTo(0f, spring(dampingRatio = 0.82f, stiffness = 700f))
        }
    }
    // Lift animation while a layer is held / dragged.
    val lift by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 900f),
        label = "laneLift"
    )
    // New lane pops in.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 600f)) }

    Box(
        modifier = Modifier
            .height(trackHeight)
            .fillMaxWidth()
            .graphicsLayer {
                translationY = (if (isDragging) dragOffsetY else slideY.value)
                val s = 1f + 0.03f * lift
                scaleY = s * (0.85f + 0.15f * appear.value)
                alpha = appear.value * (1f - 0.08f * lift)
                shadowElevation = 12f * lift
            }
        ,
        contentAlignment = Alignment.CenterStart
    ) {
        // Exactly one clip per lane in Alight Motion mode.
        track.clips.forEach { clip ->
            val clipWidth = (clip.durationMs * pxPerMs).dp.coerceAtLeast(36.dp)
            val isSelected = clip.id == selectedClipId

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (clip.startMs * pxPerMs * pxPerDp + handlePx + if (isDragging) dragOffsetX else 0f).roundToInt(),
                            y = 0
                        )
                    }
                    .pointerInput(clip.id, laneIndex, laneCount, pxPerMs) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                isDragging = true
                                dragOffsetX = 0f
                                dragOffsetY = 0f
                                onClipSelect(clip.id)
                                onBeginClipTimeDrag()
                            },
                            onDragEnd = {
                                // Commit vertical reorder if moved enough lanes.
                                val steps = (dragOffsetY / laneStepPx).roundToInt()
                                if (steps != 0) {
                                    val to = (laneIndex + steps).coerceIn(0, (laneCount - 1).coerceAtLeast(0))
                                    if (to != laneIndex) onReorderLane(laneIndex, to)
                                }
                                // Horizontal time already applied live via onMoveClipInTime.
                                isDragging = false
                                dragOffsetX = 0f
                                dragOffsetY = 0f
                            },
                            onDragCancel = {
                                isDragging = false
                                dragOffsetX = 0f
                                dragOffsetY = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetX += dragAmount.x
                                dragOffsetY += dragAmount.y
                                // Dominant axis: horizontal → time, vertical → reorder preview only.
                                if (abs(dragAmount.x) >= abs(dragAmount.y)) {
                                    val deltaMs = (dragAmount.x / pxPerDp / pxPerMs).roundToLong()
                                    if (deltaMs != 0L) {
                                        onMoveClipInTime(clip.id, deltaMs)
                                        // Keep visual offset in sync after model updates startMs.
                                        dragOffsetX -= deltaMs * pxPerMs * pxPerDp
                                    }
                                }
                            }
                        )
                    }
            ) {
                TimelineClipItem(
                    clip = clip,
                    width = clipWidth,
                    isSelected = isSelected || isDragging,
                    isMultiSelected = clip.id in multiSelectIds,
                    onClick = { onClipSelect(clip.id) },
                    visibleFromMs = visibleFromMs,
                    visibleToMs = visibleToMs,
                    waveform = clip.uri?.let { waveforms[it] }
                )
            }

            // Two arrow handles flank a selected layer: drag to shorten / lengthen it.
            if (isSelected && !isDragging && !clip.isLocked) {
                val barPx = clipWidth.value * pxPerDp
                TrimHandle(
                    isLeft = true,
                    height = trackHeight,
                    modifier = Modifier.offset {
                        IntOffset((clip.startMs * pxPerMs * pxPerDp).roundToInt(), 0)
                    },
                    pxPerMs = pxPerMs,
                    pxPerDp = pxPerDp,
                    onBegin = onBeginClipTimeDrag,
                    onTrim = { d -> onTrimClipEdge(clip.id, true, d) }
                )
                TrimHandle(
                    isLeft = false,
                    height = trackHeight,
                    modifier = Modifier.offset {
                        IntOffset((clip.startMs * pxPerMs * pxPerDp + handlePx + barPx).roundToInt(), 0)
                    },
                    pxPerMs = pxPerMs,
                    pxPerDp = pxPerDp,
                    onBegin = onBeginClipTimeDrag,
                    onTrim = { d -> onTrimClipEdge(clip.id, false, d) }
                )
            }
        }
    }
}

private val TrimHandleWidth = 34.dp

/** Grey arrow tab on one side of a selected layer (Alight Motion style). Drag = trim. */
@Composable
private fun TrimHandle(
    isLeft: Boolean,
    height: Dp,
    modifier: Modifier,
    pxPerMs: Float,
    pxPerDp: Float,
    onBegin: () -> Unit,
    onTrim: (Long) -> Unit
) {
    val latestOnTrim by rememberUpdatedState(onTrim)
    Box(
        modifier = modifier
            .width(TrimHandleWidth)
            .height(height)
            .clip(
                if (isLeft) RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 1.dp, bottomEnd = 1.dp)
                else RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp, topStart = 1.dp, bottomStart = 1.dp)
            )
            .background(Color(0xFF454A58))
            .pointerInput(pxPerMs, pxPerDp) {
                var acc = 0f
                detectHorizontalDragGestures(
                    onDragStart = { acc = 0f; onBegin() },
                    onDragEnd = { acc = 0f },
                    onDragCancel = { acc = 0f },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        acc += dx / pxPerDp / pxPerMs
                        val whole = acc.toLong()
                        if (whole != 0L) {
                            acc -= whole
                            latestOnTrim(whole)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isLeft) Icons.Default.ChevronLeft else Icons.Default.ChevronRight,
            contentDescription = if (isLeft) "Trim start" else "Trim end",
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(24.dp)
        )
    }
}

private fun laneHeightFor(type: LayerType): Dp = when (type) {
    LayerType.VIDEO -> 36.dp
    LayerType.AUDIO -> 36.dp
    LayerType.SHAPE -> 32.dp
    LayerType.OVERLAY, LayerType.TEXT -> 34.dp
    else -> 30.dp
}

@Composable
fun TimelineClipItem(
    clip: TimelineClip,
    width: Dp,
    isSelected: Boolean,
    onClick: () -> Unit,
    isMultiSelected: Boolean = false,
    visibleFromMs: Long = Long.MIN_VALUE / 4,
    visibleToMs: Long = Long.MAX_VALUE / 4,
    waveform: FloatArray? = null
) {
    val selectScale by animateFloatAsState(
        targetValue = 1f, // no grow/pop on touch or select: layers stay perfectly still while scrolling
        animationSpec = spring(
            dampingRatio = 0.8f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "clipSelectScale"
    )

    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .graphicsLayer {
                scaleX = selectScale
                scaleY = selectScale
                alpha = if (clip.isVisible) 1f else 0.35f
            }
            .clip(RoundedCornerShape(2.dp))
            .bouncyClickable(pressedScale = 1f, onClick = onClick)
            // Selected (single or multi-select): inner shadow on all four edges.
            .drawWithContent {
                drawContent()
                if (isSelected || isMultiSelected) {
                    val e = 11.dp.toPx().coerceAtMost(size.height / 2f)
                    val dark = Color.Black.copy(alpha = 0.55f)
                    val none = Color.Transparent
                    drawRect(Brush.verticalGradient(listOf(dark, none), 0f, e), size = androidx.compose.ui.geometry.Size(size.width, e))
                    drawRect(
                        Brush.verticalGradient(listOf(none, dark), size.height - e, size.height),
                        topLeft = Offset(0f, size.height - e),
                        size = androidx.compose.ui.geometry.Size(size.width, e)
                    )
                    val ex = e.coerceAtMost(size.width / 2f)
                    drawRect(Brush.horizontalGradient(listOf(dark, none), 0f, ex), size = androidx.compose.ui.geometry.Size(ex, size.height))
                    drawRect(
                        Brush.horizontalGradient(listOf(none, dark), size.width - ex, size.width),
                        topLeft = Offset(size.width - ex, 0f),
                        size = androidx.compose.ui.geometry.Size(ex, size.height)
                    )
                }
            }
    ) {
        when (clip.type) {
            LayerType.VIDEO -> {
                if (clip.uri != null) {
                    // Real frames taken from the clip itself, spread over its full width.
                    VideoFrameStrip(
                        clip = clip,
                        widthDp = width,
                        visibleFromMs = visibleFromMs,
                        visibleToMs = visibleToMs
                    )
                } else {
                    // Demo clip without media: keep the old placeholder tiles.
                    Row(modifier = Modifier.fillMaxSize()) {
                        val tileRes = when {
                            clip.name.contains("Yellow") || clip.name.contains("Vlog") -> R.drawable.sample_portrait_1791121452621
                            clip.name.contains("Canopy") || clip.name.contains("Trees") -> R.drawable.sample_nature_1791121466670
                            else -> R.drawable.sample_portrait_1791121452621
                        }
                        val frameCount = ((width.value / 38).toInt()).coerceAtLeast(1)
                        for (i in 0 until frameCount) {
                            Image(
                                painter = painterResource(id = tileRes),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .border(0.5.dp, Color.Black.copy(alpha = 0.3f))
                            )
                        }
                    }
                }
            }

            LayerType.AUDIO -> {
                if (clip.uri != null) {
                    // Real audio layer: waveform of the part of the song this layer plays.
                    AudioClipBody(
                        clip = clip,
                        width = width,
                        waveform = waveform,
                        visibleFromMs = visibleFromMs,
                        visibleToMs = visibleToMs
                    )
                } else {
                    // Old demo audio layer (no file): keep the placeholder bars.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF1E232A))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .background(NeonGreen.copy(alpha = 0.25f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Mic",
                                    tint = NeonGreen,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            repeat(15) {
                                Box(
                                    modifier = Modifier
                                        .width(16.dp)
                                        .height(4.dp)
                                        .background(Color(0xFF4B5563), RoundedCornerShape(2.dp))
                                )
                            }
                        }
                    }
                }
            }

            LayerType.SHAPE -> StripedBar(Color(0xFF4FBFC9), clip.name)
            LayerType.TEXT -> StripedBar(
                Color(0xFFF7CB60),
                clip.text?.takeIf { it.isNotBlank() } ?: clip.name
            )
            LayerType.OVERLAY, LayerType.STICKER -> StripedBar(Color(0xFFF5A44B), clip.name)

            else -> StripedBar(Color(0xFFF5A44B), clip.name)
        }

        // Video: file name chip so layers are easy to tell apart.
        if (clip.type == LayerType.VIDEO) {
            Text(
                text = clip.name,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 6.dp, bottom = 4.dp, end = 26.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(2.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
        // Grip (three lines) at the right end, like a drag handle.
        Grip(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp))
    }
}

@Composable
private fun Grip(modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        repeat(3) {
            Box(
                Modifier
                    .width(14.dp)
                    .height(2.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(1.dp))
            )
        }
    }
}


/**
 * Layer body: soft top-to-bottom gradient, light top edge, faint vertical stripes and a label.
 * Drawn once into a display list; scrolling only moves it.
 */
@Composable
private fun StripedBar(color: Color, label: String? = null) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        lerpColor(color, Color.White, 0.18f),
                        color,
                        lerpColor(color, Color.Black, 0.12f)
                    )
                )
            )
            .drawBehind {
                val stripe = 12.dp.toPx()
                var x = stripe
                var odd = false
                while (x < size.width + stripe) {
                    if (odd) {
                        drawRect(
                            color = Color.White.copy(alpha = 0.06f),
                            topLeft = Offset(x - stripe, 0f),
                            size = androidx.compose.ui.geometry.Size(stripe, size.height)
                        )
                    }
                    odd = !odd
                    x += stripe
                }
                // light top edge
                drawRect(
                    color = Color.White.copy(alpha = 0.35f),
                    size = androidx.compose.ui.geometry.Size(size.width, 1.5.dp.toPx())
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                color = Color(0xFF14121F),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, end = 32.dp)
            )
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color =
    androidx.compose.ui.graphics.lerp(a, b, t)

/** Lower = longer glide after a swipe. Alight-Motion-like smooth coast. */
private const val FLING_FRICTION = 0.12f
/** Multiplier on release velocity so a normal flick travels farther. */
private const val FLING_VELOCITY_BOOST = 1.35f

private val ThumbTileWidth = 44.dp
private val ThumbTileHeight = 44.dp

/** One shared loader that can decode video frames (coil-video). */
private object ThumbLoader {
    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader =
        instance ?: synchronized(this) {
            instance ?: ImageLoader.Builder(context.applicationContext)
                .components { add(VideoFrameDecoder.Factory()) }
                .build()
                .also { instance = it }
        }
}

/**
 * Filmstrip for a clip: one thumbnail per [ThumbTileWidth] of clip width, each showing the
 * frame at that point of the clip (respecting trim and speed). Only tiles inside the visible
 * time window are composed, so very long clips stay cheap.
 */
@Composable
private fun VideoFrameStrip(
    clip: TimelineClip,
    widthDp: Dp,
    visibleFromMs: Long,
    visibleToMs: Long
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val loader = remember(context) { ThumbLoader.get(context) }

    val tileW = ThumbTileWidth.value
    val tileCount = ceil(widthDp.value / tileW).toInt().coerceAtLeast(1)

    val relFromMs = visibleFromMs - clip.startMs
    val relToMs = visibleToMs - clip.startMs
    if (relToMs < 0L || relFromMs > clip.durationMs) return

    val first = (relFromMs.coerceAtLeast(0L) * TIMELINE_PX_PER_MS / tileW).toInt().coerceIn(0, tileCount - 1)
    val last = (relToMs * TIMELINE_PX_PER_MS / tileW).toInt().coerceIn(0, tileCount - 1)

    val tilePxW = with(density) { ThumbTileWidth.roundToPx() }
    val tilePxH = with(density) { ThumbTileHeight.roundToPx() }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1B2030))) {
        for (i in first..last) {
            val key: String
            var frameMs = 0L
            if (clip.isImage) {
                key = "${clip.uri}@img"
            } else {
                val timelineOffsetMs = (i * tileW + tileW / 2f) / TIMELINE_PX_PER_MS
                var srcMs = clip.trimStartMs + (timelineOffsetMs * clip.speed).toLong()
                if (clip.sourceDurationMs > 0L) {
                    srcMs = srcMs.coerceIn(0L, (clip.sourceDurationMs - 1L).coerceAtLeast(0L))
                }
                frameMs = (srcMs / 200L) * 200L // snap so nearby tiles/redraws share the cache
                key = "${clip.uri}@$frameMs"
            }
            val request = remember(clip.uri, key, tilePxW, tilePxH) {
                ImageRequest.Builder(context)
                    .data(clip.uri)
                    .size(tilePxW, tilePxH)
                    .memoryCacheKey(key)
                    .crossfade(false)
                    .apply { if (!clip.isImage) videoFrameMillis(frameMs) }
                    .build()
            }

            AsyncImage(
                model = request,
                contentDescription = null,
                imageLoader = loader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .offset(x = ThumbTileWidth * i)
                    .width(ThumbTileWidth)
                    .fillMaxHeight()
            )
        }
    }
}
