package com.example.ui.components
import androidx.compose.ui.graphics.RectangleShape

import com.example.model.sourceOffsetMs
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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.model.keyframeTimes
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
import com.example.model.isBooleanShapeCandidate
import com.example.model.MultiBarMode
import com.example.model.MultiSelectOps
import com.example.model.TIMELINE_PX_PER_MS
import com.example.model.TimelineClip
import com.example.model.TimelineTrack
import com.example.ui.theme.AmBaseDeep
import com.example.ui.theme.AmCyanBright
import com.example.ui.theme.AmDanger
import com.example.ui.theme.AmHairline
import com.example.ui.theme.AmInk
import com.example.ui.theme.AmMint
import com.example.ui.theme.AmPanel
import com.example.ui.theme.AmTextDim
import com.example.ui.theme.AmTextFaint
import com.example.ui.theme.AmViolet
import com.example.ui.theme.EditorBackground
import com.example.ui.theme.EditorSurface
import com.example.ui.theme.IconActive
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.PlayheadColor
import com.example.ui.theme.SelectedClipBorder
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.amCyanVioletBrush
import com.example.ui.theme.amMintCyanBrush
import com.example.ui.util.bouncyClickable
import com.example.ui.theme.NeonLime
import com.example.ui.theme.NeonLimeDeep
import com.example.ui.theme.GoldClip
import com.example.ui.theme.GoldClipDeep
import com.example.ui.theme.GoldThumbTop
import com.example.ui.theme.GoldThumbBottom
import com.example.ui.theme.PillTop
import com.example.ui.theme.PillBottom
import com.example.ui.theme.TrimHandleTop
import com.example.ui.theme.TrimHandleBottom
import com.example.ui.theme.TimelineBlack
import com.example.ui.theme.LanePillDark
import com.example.ui.theme.LaneEyeTint
import com.example.ui.theme.LaneThumbWhite
import com.example.ui.theme.ShapeBarMid
import com.example.ui.theme.ShapeBarBottom
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
    /** Called once when the finger starts scrubbing the timeline (the editor pauses playback). */
    onScrubStart: () -> Unit = {},
    /** While true, any scrub momentum (fling) is stopped. */
    isPlaying: Boolean = false,
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
    onMultiAction: (MultiAction) -> Unit = {},
    /** A spatial align / distribute tile of the multi-select bar (build 188). */
    onSpatialAction: (com.example.model.SpatialAction) -> Unit = {},
    /** A shape boolean tile of the multi-select bar (build 192). */
    onBooleanAction: (com.example.model.ShapeBooleanOp) -> Unit = {},
    /** Multi-select bar Delete tile (build 199, A11): delete every checked layer at once. */
    onDeleteCheckedLayers: () -> Unit = {},
    /** Multi-select bar All tile (build 199, A4): check every layer of the current scope. */
    onSelectAllLayers: () -> Unit = {},
    /** Audio fade handles (build 199, A9): a fade drag writes the layer's fade pair through this. */
    onSetClipFades: (clipId: String, fadeInMs: Long, fadeOutMs: Long) -> Unit = { _, _, _ -> },
    /** Quick actions (split/delete/…) + master speaker at the bottom-left. Off for the one-row editor strip. */
    showQuickActions: Boolean = true,
    /** Selected-layer bar (top panel): flip / replace. Replace is hidden when null. */
    onFlipHorizontal: () -> Unit = {},
    onFlipVertical: () -> Unit = {},
    onReplace: (() -> Unit)? = null,
    /** Selected-layer bar: Speed button (video / audio layers). Hidden when null. */
    onSpeed: (() -> Unit)? = null,
    /** Selected-layer bar: Reverse button (video layers). Hidden when null. */
    onReverse: (() -> Unit)? = null,
    /** Round flat "+" button at the bottom right. Hidden when null. */
    onAddClick: (() -> Unit)? = null
) {
    val isMulti = multiSelectIds.isNotEmpty()
    val showActionBar = !isMulti && showQuickActions && selectedClipId != null
    val coroutineScope = rememberCoroutineScope()
    var flingJob by remember { mutableStateOf<Job?>(null) }
    val latestScrubStart by rememberUpdatedState(onScrubStart)
    // Play pressed while the timeline is still coasting after a swipe: stop the momentum.
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            flingJob?.cancel()
            flingJob = null
        }
    }
    val latestPxPerMs by rememberUpdatedState(pxPerMs)
    val latestOnPx by rememberUpdatedState(onPxPerMsChange)

    // The gesture block below is created ONCE (key = Unit). Before, it was keyed on
    // currentPlayheadMs, so every drag frame restarted it and the swipe was dropped
    // after a short distance. These always hold the latest values without restarting it.
    val latestPlayheadProvider by rememberUpdatedState(playheadMs)
    val latestTotalMs by rememberUpdatedState(totalDurationMs)
    val latestSeek by rememberUpdatedState(onSeekTo)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(AmBaseDeep)
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
                                    latestScrubStart() // scrubbing pauses playback
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
                    // Standard friction: short, natural coast that stops soon after the finger lifts.
                    if (didScrub && !isPinch) {
                        val velocityPxPerSec = velocityTracker.calculateVelocity().x
                        if (abs(velocityPxPerSec) > FLING_VELOCITY_THRESHOLD_PX) {
                            val startMs = posMs
                            val velocityMsPerSec = -(velocityPxPerSec / pxPerDp) / latestPxPerMs
                            flingJob = coroutineScope.launch {
                                AnimationState(
                                    initialValue = startMs,
                                    initialVelocity = velocityMsPerSec
                                ).animateDecay(
                                    exponentialDecay(
                                        frictionMultiplier = FLING_FRICTION,
                                        absVelocityThreshold = FLING_ABS_VELOCITY_THRESHOLD
                                    )
                                ) {
                                    val total = latestTotalMs.toFloat()
                                    val ms = value.coerceIn(0f, total)
                                    latestSeek(ms.roundToLong())
                                    if (ms <= 0f || ms >= total) cancelAnimation()
                                }
                            }
                        }
                    }
                }
            }
    ) {
        val screenWidth = maxWidth
        val centerLineX = screenWidth / 2 // EXACT 50% HORIZONTAL CENTER OF SCREEN

        val verticalScrollState = rememberScrollState()
        val dpToPx = LocalDensity.current.density

        // ---- Only the lanes near the screen are composed. A long list (100+ auto captions) used to
        // compose every lane, left control and entrance animation in ONE frame, which froze the app
        // right after the speech was done. Lanes outside the window are plain spacers of the same
        // height, so the scroll length and every position stay exactly the same.
        val laneHeights = remember(tracks) {
            tracks.map { t -> t.clips.maxOfOrNull { laneHeightFor(it.type) } ?: 26.dp }
        }
        val laneTopsPx = remember(laneHeights, dpToPx) {
            val tops = FloatArray(laneHeights.size + 1)
            var y = 9f * dpToPx // top padding of the scroll content
            for (i in laneHeights.indices) {
                tops[i] = y
                y += (laneHeights[i].value + 6f) * dpToPx // lane + 6 dp gap
            }
            tops[laneHeights.size] = y
            tops
        }
        val viewportPx = maxHeight.value * dpToPx
        val laneWindow by remember(laneTopsPx, viewportPx) {
            derivedStateOf {
                val n = laneTopsPx.size - 1
                if (n <= LANE_WINDOW_STEP * 2 || !(viewportPx > 0f)) {
                    0..(n - 1).coerceAtLeast(0)
                } else {
                    val scroll = verticalScrollState.value.toFloat()
                    val from = scroll - viewportPx // one screen above ...
                    val to = scroll + 2f * viewportPx // ... and two below (it is mostly scrolled down)
                    var lo = 0
                    while (lo < n - 1 && laneTopsPx[lo + 1] < from) lo++
                    var hi = lo
                    while (hi < n - 1 && laneTopsPx[hi + 1] < to) hi++
                    // snapped to steps of LANE_WINDOW_STEP lanes: the panel recomposes only when it steps
                    val sLo = lo / LANE_WINDOW_STEP * LANE_WINDOW_STEP
                    val sHi = minOf(n - 1, (hi / LANE_WINDOW_STEP + 1) * LANE_WINDOW_STEP - 1)
                    sLo..sHi
                }
            }
        }

        // New lanes pop in with a spring - but not when many arrive at once (auto captions) and not
        // on the first draw: hundreds of simultaneous animations are what made that slow.
        val knownLaneIds = remember { HashSet<String>() }
        val firstDrawDone = remember { BooleanArray(1) }
        val popInLaneIds = remember(tracks) {
            val fresh = tracks.map { it.id }.filter { it !in knownLaneIds }
            if (!firstDrawDone[0] || fresh.size > 3) emptySet() else fresh.toSet()
        }
        SideEffect {
            tracks.forEach { knownLaneIds.add(it.id) }
            firstDrawDone[0] = true
        }

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
        val leftRailWidth = 92.dp
        // The timeline content fills the whole width and runs UNDER the left controls (like Alight
        // Motion): layers are no longer cut off by a black strip, they show between / behind the
        // eye + thumbnail pills. The rail is drawn on top.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(verticalScrollState)
                .padding(top = 9.dp, bottom = if (isMulti) 68.dp else if (showActionBar) SelectedBarHeight + 4.dp else 4.dp)
        ) {
            // Timeline content, shifted so currentPlayheadMs sits under the center line.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState(), enabled = false)
            ) {
                Column(
                    modifier = Modifier
                        .width(contentWidth)
                        // graphicsLayer = draw phase only: no measure/layout per frame while scrolling.
                        .graphicsLayer {
                            translationX = (centerLineX.value - playheadMs() * pxPerMs) * dpToPx - TrimHandleWidth.toPx()
                        },
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tracks.forEachIndexed { laneIndex, track ->
                        androidx.compose.runtime.key(track.id) {
                        if (laneIndex in laneWindow || track.clips.any { it.id == selectedClipId }) TimelineTrackRow(
                            track = track,
                            animateIn = track.id in popInLaneIds,
                            laneIndex = laneIndex,
                            laneCount = tracks.size,
                            selectedClipId = selectedClipId,
                            onClipSelect = onClipSelect,
                            onMoveClipInTime = onMoveClipInTime,
                            onBeginClipTimeDrag = onBeginClipTimeDrag,
                            onReorderLane = onReorderLane,
                            onTrimClipEdge = onTrimClipEdge,
                            onSetClipFades = onSetClipFades,
                            pxPerMs = pxPerMs,
                            visibleFromMs = visibleFromMs,
                            visibleToMs = visibleToMs,
                            waveforms = waveforms,
                            multiSelectIds = multiSelectIds,
                            viewWidth = screenWidth,
                            // Same value the lane column is shifted by: a Chain cancels it so it stays put.
                            columnShiftPx = { (centerLineX.value - playheadMs() * pxPerMs) * dpToPx - TrimHandleWidth.value * dpToPx }
                        ) else Spacer(Modifier.height(laneHeights[laneIndex]))
                        }
                    }
                }
            }

            // Left rail — Alight Motion style: eye + layer preview for each lane (drawn on top).
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .width(leftRailWidth), // flush with the screen's left edge, no side padding
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tracks.forEachIndexed { laneIndex, track ->
                    val clip = track.clips.firstOrNull()
                    val rowH = clip?.let { laneHeightFor(it.type) } ?: 26.dp
                    if (laneIndex !in laneWindow) {
                        Spacer(Modifier.height(rowH))
                        return@forEachIndexed
                    }
                    LayerLaneControl(
                        clip = clip,
                        height = rowH,
                        isMulti = isMulti,
                        isChecked = clip != null && clip.id in multiSelectIds,
                        isSelected = clip != null && clip.id == selectedClipId,
                        onToggleVisibility = { id -> onToggleClipVisibility(id) },
                        onSelect = { id -> onClipSelect(id) },
                        onLongPress = onLongPressLane,
                        onToggleMulti = onToggleMulti
                    )
                }
            }
        }

        // 2. Master speaker (bottom-left, only while no layer is selected: with a layer selected
        //    the full-width action bar below takes this place and carries the speaker itself).
        if (!isMulti && showQuickActions && selectedClipId == null) Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 10.dp, bottom = 8.dp)
                .size(44.dp)
                .bouncyClickable(onClick = onToggleMasterAudio),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isMasterMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeDown,
                contentDescription = "Master Audio",
                tint = LaneEyeTint,
                modifier = Modifier.size(22.dp)
            )
        }

        // 4. VERTICAL LOCKED PLAYHEAD LINE ("খারাভাবে middle এ locked timeline")
        // Reference look: 2.3 dp white line inside a 9 dp translucent band, round ends, starting just
        // under the ruler — flat white in the B/W language, with a small flat white knob at the top.
        Canvas(
            modifier = Modifier
                .width(12.dp)
                .fillMaxHeight()
                .offset(x = centerLineX - 6.dp)
        ) {
            val cx = size.width / 2f
            val top = 3.dp.toPx()
            val bottom = size.height - 2.dp.toPx()
            drawLine(
                Color.White.copy(alpha = 0.10f), Offset(cx, top + 4.5.dp.toPx()), Offset(cx, bottom - 4.5.dp.toPx()),
                strokeWidth = 9.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            drawLine(
                PlayheadColor, Offset(cx, top), Offset(cx, bottom),
                strokeWidth = 2.3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            drawFlatThumb(Offset(cx, top + 4.dp.toPx()), 5.5.dp.toPx())
        }

        // Round flat add button (bottom right). Sits above the selected-layer bar when it is shown.
        if (onAddClick != null && !isMulti && showQuickActions) {
            NeonAddFab(
                onClick = onAddClick,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = if (showActionBar) SelectedBarHeight + 6.dp else 10.dp)
            )
        }

        // 5. Multi-select bar (drawn after the playhead line so the line passes behind it).
        if (isMulti) {
            MultiBarHost(
                tracks = tracks,
                multiSelectIds = multiSelectIds,
                playheadMs = playheadMs,
                onAction = onMultiAction,
                onSpatialAction = onSpatialAction,
                onBooleanAction = onBooleanAction,
                onDeleteChecked = onDeleteCheckedLayers,
                onSelectAll = onSelectAllLayers,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .enterPop(fromBottom = true)
            )
        }

        // 6. Selected-layer action bar: full width, sits at the bottom of the layer panel
        //    (the top one of the two panels). Split / Copy / Flip / Replace / Delete.
        if (showActionBar) {
            val selIsAudio = tracks.any { t -> t.clips.any { it.id == selectedClipId && it.type == LayerType.AUDIO } }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(SelectedBarHeight)
                    .background(EditorBackground)
                    .padding(horizontal = 8.dp)
                    .enterPop(fromBottom = true)
                    .testTag("selected_layer_action_bar"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SelectedBarButton(
                    icon = if (isMasterMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    label = "Master", tint = IconActive, onClick = onToggleMasterAudio,
                    modifier = Modifier.weight(1f), tag = "bar_master"
                )
                SelectedBarButton(Icons.Default.ContentCut, "Split", AmMint, onSplitClip, Modifier.weight(1f), tag = "bar_split")
                SelectedBarButton(Icons.Default.ContentCopy, "Copy", IconActive, onDuplicateClip, Modifier.weight(1f), tag = "bar_copy")
                if (onSpeed != null) {
                    SelectedBarButton(Icons.Default.Speed, "Speed", IconActive, onSpeed, Modifier.weight(1f), tag = "bar_speed")
                }
                if (onReverse != null) {
                    SelectedBarButton(Icons.Default.FastRewind, "Reverse", IconActive, onReverse, Modifier.weight(1f), tag = "bar_reverse")
                }
                if (!selIsAudio) {
                    SelectedBarButton(Icons.Default.Flip, "Flip H", IconActive, onFlipHorizontal, Modifier.weight(1f), tag = "bar_flip_h")
                    SelectedBarButton(Icons.Default.FlipCameraAndroid, "Flip V", IconActive, onFlipVertical, Modifier.weight(1f), tag = "bar_flip_v")
                    if (onReplace != null) {
                        SelectedBarButton(Icons.Default.SwapHoriz, "Replace", IconActive, onReplace, Modifier.weight(1f), tag = "bar_replace")
                    }
                }
                SelectedBarButton(
                    Icons.Default.Delete, "Delete", IconActive, onDeleteClip, Modifier.weight(1f),
                    destructive = true, tag = "bar_delete"
                )
            }
        }
    }
}

private val SelectedBarHeight = 52.dp

@Composable
private fun SelectedBarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    tag: String = ""
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = 4.dp)
            .bouncyClickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        // Icon only (no name text); the label stays as the accessibility description.
        // A destructive action (Delete) reads red — the flag used to be accepted but never applied.
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (destructive) AmDanger else tint,
            modifier = Modifier.size(22.dp)
        )
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
    onSpatialAction: (com.example.model.SpatialAction) -> Unit = {},
    onBooleanAction: (com.example.model.ShapeBooleanOp) -> Unit = {},
    onDeleteChecked: () -> Unit = {},
    onSelectAll: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val mode by remember(tracks, multiSelectIds) {
        derivedStateOf {
            val checked = tracks.flatMap { it.clips }.filter { it.id in multiSelectIds }
            MultiSelectOps.barMode(checked, playheadMs())
        }
    }
    // The boolean row (build 192) exists only for exactly two eligible shapes:
    // stored-fields eligibility (a visible, non-mask shape with a filled region),
    // active at the playhead, and not pose-placed (the frame-space evaluation in
    // the engine refuses those — the row must not promise what it cannot do).
    val booleanReady by remember(tracks, multiSelectIds) {
        derivedStateOf {
            if (multiSelectIds.size != 2) false
            else {
                val t = playheadMs()
                val checked = tracks.flatMap { it.clips }.filter { it.id in multiSelectIds }
                checked.size == 2 && checked.all {
                    it.isBooleanShapeCandidate && it.isActiveAt(t) &&
                        !com.example.engine.overlayNeedsPose(it)
                }
            }
        }
    }
    MultiSelectBar(
        mode = mode,
        onAction = onAction,
        selectedCount = multiSelectIds.size,
        onSpatialAction = onSpatialAction,
        showBooleanRow = booleanReady,
        onBooleanAction = onBooleanAction,
        onDeleteChecked = onDeleteChecked,
        onSelectAll = onSelectAll,
        modifier = modifier
    )
}

@Composable
private fun LayerLaneControl(
    clip: TimelineClip?,
    height: Dp,
    isMulti: Boolean,
    isChecked: Boolean,
    isSelected: Boolean = false,
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
    val pillShape = RectangleShape
    Box(
        modifier = Modifier
            .padding(start = 5.dp)
            .height(height)
            .fillMaxWidth()
            .clip(pillShape)
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
    ) {
        // Flat pill under the lane content (B/W language); the edge is drawn OVER it —
        // selected lane: a solid white edge; otherwise a soft white hairline.
        Box(
            Modifier
                .matchParentSize()
                .background(AmPanel, pillShape)
        ) {}
        Box(
            Modifier
                .matchParentSize()
                .border(
                    if (isSelected) 1.dp else 0.75.dp,
                    if (isSelected) Color.White else Color.White.copy(alpha = 0.22f),
                    pillShape
                )
        ) {}
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 8.5.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isMulti) {
                // Multi-select: the eye turns into a check circle (empty when not checked).
                Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    if (isChecked) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .background(amMintCyanBrush(), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = AmInk,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else {
                        Box(
                            Modifier
                                .size(20.dp)
                                .border(1.5.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                        )
                    }
                }
            } else Box(
                // 30 dp hit box around the 24 dp eye glyph: hide/show is a frequent tap and the
                // lane pill is only 34-40 dp tall, so the target grows as far as the pill allows.
                modifier = Modifier
                    .size(30.dp)
                    .bouncyClickable(pressedScale = 1f, onClick = { onToggleVisibility(clip.id) }),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (clip.isVisible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    contentDescription = if (clip.isVisible) "Hide layer" else "Show layer",
                    tint = if (clip.isVisible) LaneEyeTint else LaneEyeTint.copy(alpha = 0.35f),
                    modifier = Modifier.size(24.dp)
                )
            }
            // thin divider between eye and thumbnail
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight(0.55f)
                    .background(Color.White.copy(alpha = 0.20f))
            )
            LayerThumb(clip = clip, thumbColor = thumbColor)
        }
    }
}

/** Rounded-square layer thumbnail: gold "T" for text, white tile for shapes, etc. */
@Composable
private fun LayerThumb(clip: TimelineClip, thumbColor: Color) {
    val shape = RoundedCornerShape(7.dp)
    val isText = clip.type == LayerType.TEXT
    val fill: Brush = when (clip.type) {
        LayerType.TEXT -> Brush.verticalGradient(listOf(GoldThumbTop, GoldThumbBottom))
        LayerType.SHAPE -> {
            val c = lerpColor(thumbColor, LaneThumbWhite, 0.90f)
            Brush.verticalGradient(listOf(lerpColor(c, Color.White, 0.04f), lerpColor(c, Color.Black, 0.02f)))
        }
        LayerType.VIDEO -> Brush.verticalGradient(listOf(Color(0xFF1A1A1A), Color.Black))
        LayerType.AUDIO -> Brush.verticalGradient(listOf(Color(0xFF2A6A50), Color(0xFF1B4332)))
        else -> Brush.verticalGradient(listOf(lerpColor(thumbColor, Color.White, 0.2f), thumbColor))
    }
    Box(
        modifier = Modifier
            .size(25.dp)
            .clip(shape)
            .background(fill)
            .then(
                if (clip.type == LayerType.SHAPE) Modifier else Modifier.border(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(
                            (if (isText) GoldClip else Color.White).copy(alpha = 0.75f),
                            (if (isText) GoldClipDeep else Color.White).copy(alpha = 0.15f)
                        )
                    ),
                    shape
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        when (clip.type) {
            LayerType.TEXT -> Text("T", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            LayerType.AUDIO -> Icon(Icons.Default.Mic, null, tint = Color.White, modifier = Modifier.size(14.dp))
            else -> {}
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
    /** Audio fade handles (build 199, A9): a fade drag writes the layer's fade pair through this. */
    onSetClipFades: (clipId: String, fadeInMs: Long, fadeOutMs: Long) -> Unit = { _, _, _ -> },
    pxPerMs: Float = TIMELINE_PX_PER_MS,
    visibleFromMs: Long = Long.MIN_VALUE / 4,
    visibleToMs: Long = Long.MAX_VALUE / 4,
    waveforms: Map<String, FloatArray> = emptyMap(),
    multiSelectIds: Set<String> = emptySet(),
    /** false = the lane is simply there (no pop-in spring), used for bulk adds and the first draw. */
    animateIn: Boolean = true,
    /** Width of the screen: a Chain is exactly this wide, edge to edge. */
    viewWidth: Dp = 360.dp,
    /** How far the lane column is shifted by the timeline scroll (px); a Chain moves back by it, so it never seems to scroll. */
    columnShiftPx: () -> Float = { 0f }
) {
    // Alight Motion: one layer per line → fixed row height by layer type.
    val trackHeight = track.clips.maxOfOrNull { laneHeightFor(it.type) } ?: 26.dp
    // Build 187: the capsule bar's height — the row minus the inset, centred in the row.
    val barHeight = trackHeight - LaneBarInset
    val density = LocalDensity.current
    val pxPerDp = density.density
    // How many vertical pixels ≈ one lane step for reorder.
    val laneStepPx = with(density) { (trackHeight + 6.dp).toPx() }

    // Room on the left so the left trim handle of a layer at 0:00 is still inside the lane.
    val handlePx = with(density) { TrimHandleWidth.toPx() }
    var dragOffsetX by remember { mutableStateOf(0f) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    // A lane can hold several layers (inside a Chain): only the one that is dragged follows the finger.
    var dragClipId by remember { mutableStateOf<String?>(null) }

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
    val appear = remember { Animatable(if (animateIn) 0f else 1f) }
    LaunchedEffect(Unit) { if (animateIn) appear.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 600f)) }

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
        // Build 187: the lane's dark TRACK groove, running the lane's full timeline width
        // behind the clips (Sarowar's reference bars sit on a black track). A Chain lane
        // keeps its own band instead — no groove under it.
        if (track.clips.none { it.type == LayerType.CHAIN }) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .height(barHeight)
                    .background(LaneTrackColor, RoundedCornerShape(percent = 50))
            )
        }
        // One clip per lane, except a Chain lane: the chain bar first (behind), its layers on top of it.
        val laneClips = if (track.clips.size > 1) track.clips.sortedBy { if (it.type == LayerType.CHAIN) 0 else 1 } else track.clips
        laneClips.forEach { clip ->
            val clipWidth = if (clip.type == LayerType.CHAIN) viewWidth else (clip.durationMs * pxPerMs).dp.coerceAtLeast(36.dp)
            val isSelected = clip.id == selectedClipId
            val dragging = isDragging && dragClipId == clip.id

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = if (clip.type == LayerType.CHAIN) 0
                            else (clip.startMs * pxPerMs * pxPerDp + handlePx + if (dragging) dragOffsetX else 0f).roundToInt(),
                            // Build 187: the capsule bar is centred in its (taller) row; a Chain keeps the full row.
                            y = if (clip.type == LayerType.CHAIN) 0
                            else (LaneBarInset.value / 2f * pxPerDp).roundToInt()
                        )
                    }
                    // The lane column scrolls with the timeline; a Chain undoes that, so it stays fixed on the screen.
                    .then(
                        if (clip.type == LayerType.CHAIN) Modifier.graphicsLayer { translationX = -columnShiftPx() }
                        else Modifier
                    )
                    // Build 187: the touch box is the BAR (barHeight), not the whole row —
                    // the drag/trim gestures themselves are unchanged. A Chain keeps the row.
                    .then(
                        if (clip.type == LayerType.CHAIN) Modifier
                        else Modifier.height(barHeight)
                    )
                    .pointerInput(clip.id, laneIndex, laneCount, pxPerMs) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                isDragging = true
                                dragClipId = clip.id
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
                                // A Chain has no start / end to move: it only changes lane.
                                if (clip.type != LayerType.CHAIN && abs(dragAmount.x) >= abs(dragAmount.y)) {
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
                    isSelected = isSelected || dragging,
                    isMultiSelected = clip.id in multiSelectIds,
                    onClick = { onClipSelect(clip.id) },
                    visibleFromMs = visibleFromMs,
                    visibleToMs = visibleToMs,
                    waveform = clip.uri?.let { waveforms[it] },
                    pxPerMs = pxPerMs,
                    // Build 187: the badge number = the lane's order in the displayed stack.
                    layerNumber = laneIndex + 1
                )
            }

            // Two arrow handles flank a selected layer: drag to shorten / lengthen it.
            if (isSelected && !isDragging && !clip.isLocked && clip.type != LayerType.CHAIN) {
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

            // Build 199 (A9): draggable fade knobs on a selected AUDIO layer. The waveform
            // already draws the fades (AudioClipBody shapes it by fadeAt); these knobs at the
            // bar's top corners are where the drawn fade ends. A drag writes through the same
            // setClipFades funnel as the Fade sheet's sliders (live value during the drag,
            // coerced there); onBeginClipTimeDrag opens the drag's single undo step.
            if (isSelected && !isDragging && !clip.isLocked && clip.type == LayerType.AUDIO) {
                val barLeftPx = clip.startMs * pxPerMs * pxPerDp + handlePx
                val barPx = clipWidth.value * pxPerDp
                val knobHalfPx = 16f * pxPerDp   // half the 32 dp hit box (build 201: was 24 dp box / 12)
                val knobTopPx = (LaneBarInset.value / 2f) * pxPerDp - knobHalfPx
                FadeHandle(
                    isLeft = true,
                    modifier = Modifier.offset {
                        IntOffset(
                            (barLeftPx + clip.fadeInMs * pxPerMs * pxPerDp - knobHalfPx).roundToInt(),
                            knobTopPx.roundToInt()
                        )
                    },
                    pxPerMs = pxPerMs,
                    pxPerDp = pxPerDp,
                    onBegin = onBeginClipTimeDrag,
                    onFadeDelta = { d -> onSetClipFades(clip.id, clip.fadeInMs + d, clip.fadeOutMs) }
                )
                FadeHandle(
                    isLeft = false,
                    modifier = Modifier.offset {
                        IntOffset(
                            (barLeftPx + barPx - clip.fadeOutMs * pxPerMs * pxPerDp - knobHalfPx).roundToInt(),
                            knobTopPx.roundToInt()
                        )
                    },
                    pxPerMs = pxPerMs,
                    pxPerDp = pxPerDp,
                    onBegin = onBeginClipTimeDrag,
                    onFadeDelta = { d -> onSetClipFades(clip.id, clip.fadeInMs, clip.fadeOutMs - d) }
                )
            }
        }
    }
}

private val TrimHandleWidth = 34.dp

/** The composed lane window moves in steps of this many lanes. */
private const val LANE_WINDOW_STEP = 8

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
                if (isLeft) RoundedCornerShape(topStart = 9.dp, bottomStart = 9.dp, topEnd = 2.dp, bottomEnd = 2.dp)
                else RoundedCornerShape(topEnd = 9.dp, bottomEnd = 9.dp, topStart = 2.dp, bottomStart = 2.dp)
            )
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
        FlatTile(Modifier.matchParentSize())
        Icon(
            imageVector = if (isLeft) Icons.Default.ChevronLeft else Icons.Default.ChevronRight,
            contentDescription = if (isLeft) "Trim start" else "Trim end",
            tint = Color.White,
            modifier = Modifier.size(26.dp)
        )
    }
}

/**
 * A fade knob (build 199, A9): a small white disc with a dark triangle pointing along the
 * fade's growth direction, sitting on the audio bar's top corner where the drawn fade ends.
 * Drag = the TrimHandle pattern (whole-ms steps through [onFadeDelta], one undo step opened
 * by [onBegin]); the 32 dp box is the touch target (build 201, audit B-8: was 24 dp),
 * the disc itself is 16 dp.
 */
@Composable
private fun FadeHandle(
    isLeft: Boolean,
    modifier: Modifier,
    pxPerMs: Float,
    pxPerDp: Float,
    onBegin: () -> Unit,
    onFadeDelta: (Long) -> Unit
) {
    val latestOnFade by rememberUpdatedState(onFadeDelta)
    Box(
        modifier = modifier
            .size(32.dp)
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
                            latestOnFade(whole)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(16.dp)) {
            drawCircle(Color.White)
            val cx = size.width / 2f
            val cy = size.height / 2f
            val tri = Path().apply {
                if (isLeft) {
                    moveTo(cx - size.width * 0.14f, cy - size.height * 0.2f)
                    lineTo(cx + size.width * 0.18f, cy)
                    lineTo(cx - size.width * 0.14f, cy + size.height * 0.2f)
                } else {
                    moveTo(cx + size.width * 0.14f, cy - size.height * 0.2f)
                    lineTo(cx - size.width * 0.18f, cy)
                    lineTo(cx + size.width * 0.14f, cy + size.height * 0.2f)
                }
                close()
            }
            drawPath(tri, Color(0xFF111111))
        }
    }
}

// Build 187 (Sarowar's layer redesign): row heights cut by roughly a quarter so more
// lanes fit the panel (the capsule bar inside is LaneBarInset shorter still). Old -> new:
// VIDEO/AUDIO 40 -> 30, SHAPE/CHAIN/OVERLAY/TEXT 38 -> 28, GROUP 36 -> 27, else 34 -> 26.
// A third was NOT taken: the left rail's 25 dp thumbnail and the eye hit box set the floor
// (the slimmest row, 26 dp, still holds both), and trim handles keep the full row height.
internal fun laneHeightFor(type: LayerType): Dp = when (type) {
    LayerType.VIDEO -> 30.dp
    LayerType.AUDIO -> 30.dp
    LayerType.SHAPE -> 28.dp
    LayerType.CHAIN -> 28.dp
    LayerType.OVERLAY, LayerType.TEXT -> 28.dp
    LayerType.GROUP -> 27.dp
    else -> 26.dp
}

/** Build 187: the capsule bar is this much shorter than its lane row (centred in it). */
private val LaneBarInset = 4.dp
/** Build 187: the lane's dark track groove. The panel is pure black (AmBaseDeep), so the
 *  reference's black track becomes a near-black groove — the same track-vs-page contrast. */
private val LaneTrackColor = Color(0xFF161616)
/** Build 187: the Controller's settled teal marker colour (same as CanvasArea's). */
private val ControllerTeal = Color(0xFF00B8A9)
/** Build 187: the audio bar's own colour (AudioClipBody's fill). */
private val AudioBarColor = Color(0xFF4B4BC8)

@Composable
fun TimelineClipItem(
    clip: TimelineClip,
    width: Dp,
    isSelected: Boolean,
    onClick: () -> Unit,
    isMultiSelected: Boolean = false,
    visibleFromMs: Long = Long.MIN_VALUE / 4,
    visibleToMs: Long = Long.MAX_VALUE / 4,
    waveform: FloatArray? = null,
    pxPerMs: Float = TIMELINE_PX_PER_MS,
    /** Build 187: the layer's number in the displayed stack, drawn in the bar's end badge.
     *  Null = no badge (the Transform strip's lone row is not part of the stack display). */
    layerNumber: Int? = null
) {
    val selectScale by animateFloatAsState(
        targetValue = 1f, // no grow/pop on touch or select: layers stay perfectly still while scrolling
        animationSpec = spring(
            dampingRatio = 0.8f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "clipSelectScale"
    )

    // A Chain is a faint grey band fixed on the screen, edge to edge: no start, no end, no corners, no grip.
    val isChain = clip.type == LayerType.CHAIN
    // Build 187 (Sarowar's reference design): the clip is a slim CAPSULE bar on the lane's
    // dark track. The outer box is the bar's layout box — deliberately UNCLIPPED, so the
    // number badge can straddle the bar's right end like the references; the capsule
    // itself (clipped, clickable) is the inner box. A Chain keeps its rectangle band.
    val barShape = if (isChain) RectangleShape else RoundedCornerShape(percent = 50)
    Box(
        modifier = Modifier.width(width)
            .fillMaxHeight()
            .graphicsLayer {
                scaleX = selectScale
                scaleY = selectScale
                alpha = if (clip.isVisible) 1f else 0.35f
            }
    ) {
      Box(
        modifier = Modifier.fillMaxSize()
            .clip(barShape)
            .bouncyClickable(pressedScale = 1f, onClick = onClick)
            // Selected (single or multi-select): a white edge (amCyanVioletBrush has
            // resolved to solid white since the B/W pass — the build-183 bright-vs-dim
            // law). The Controller draws its own teal selected state, so it is exempt.
            .then(
                if ((isSelected || isMultiSelected) && !isChain && clip.type != LayerType.CONTROLLER) {
                    Modifier.border(1.5.dp, amCyanVioletBrush(), barShape)
                } else {
                    Modifier
                }
            )
    ) {
        when (clip.type) {
            LayerType.VIDEO -> {
                if (clip.uri != null) {
                    // Real frames taken from the clip itself, spread over its full width.
                    VideoFrameStrip(
                        clip = clip,
                        widthDp = width,
                        visibleFromMs = visibleFromMs,
                        visibleToMs = visibleToMs,
                        pxPerMs = pxPerMs
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
                        visibleToMs = visibleToMs,
                        isSelected = isSelected
                    )
                } else {
                    // Old demo audio layer (no file): the placeholder bars, on the audio
                    // colour with the build-187 diagonal stripes like a real audio bar.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(AudioBarColor)
                            .audioStripes()
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
                                    .background(AmMint.copy(alpha = 0.25f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Mic",
                                    tint = AmMint,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            repeat(15) {
                                Box(
                                    modifier = Modifier
                                        .width(16.dp)
                                        .height(4.dp)
                                        .background(AmTextFaint, RoundedCornerShape(2.dp))
                                )
                            }
                        }
                    }
                }
            }

            LayerType.SHAPE -> CapsuleBar(ShapeBarMid, clip.name, isSelected)
            // Chain: a see-through grey band with dashed edges (a little clearer while it is selected or checked).
            // It looks like a rail the layers sit on, not like a layer.
            LayerType.CHAIN -> {
                val on = isSelected || isMultiSelected
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFB4B4B4).copy(alpha = if (on) 0.24f else 0.11f))
                        .drawBehind {
                            val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f
                            )
                            val line = Color(0xFFD5DAE0).copy(alpha = if (on) 0.6f else 0.3f)
                            val w = 1.dp.toPx()
                            drawLine(line, androidx.compose.ui.geometry.Offset(0f, w / 2), androidx.compose.ui.geometry.Offset(size.width, w / 2), w, pathEffect = dash)
                            drawLine(line, androidx.compose.ui.geometry.Offset(0f, size.height - w / 2), androidx.compose.ui.geometry.Offset(size.width, size.height - w / 2), w, pathEffect = dash)
                        }
                )
            }
            LayerType.TEXT -> CapsuleBar(
                GoldClip,
                clip.text?.takeIf { it.isNotBlank() } ?: clip.name,
                isSelected
            )
            LayerType.OVERLAY, LayerType.STICKER -> CapsuleBar(Color(0xFFF5A44B), clip.name, isSelected)
            // Real Group: its settled green identity, carried inside the capsule language
            // (its layers below keep their green start edge, and the rail thumb stays green).
            LayerType.GROUP -> CapsuleBar(Color(0xFF3F8F43), clip.name, isSelected)
            // Controller (build 187): Sarowar ordered a DIFFERENT design from every other
            // layer — a HOLLOW teal outline in its settled marker colour (0xFF00B8A9, the
            // CanvasArea marker), transparent fill, teal label and badge. Instantly
            // distinct from every filled bar; its fan-out semantics are untouched.
            LayerType.CONTROLLER -> ControllerBar(clip.name, isSelected || isMultiSelected)
            // Null / Camera: invisible utility layers — a quiet dark hollow bar (grey
            // outline, dim label), deliberately NOT the Controller's teal design.
            LayerType.NULL, LayerType.CAMERA -> QuietBar(clip.name, isSelected)

            else -> CapsuleBar(Color(0xFFF5A44B), clip.name, isSelected)
        }
        // Build 187: a selected VIDEO bar gets the gradient treatment as a lightening
        // wash over its frames (a video bar's "colour" is its frames): brightest at the
        // left, running out along the bar — the gradient reference mapped onto media.
        if (isSelected && clip.type == LayerType.VIDEO) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        0.0f to Color.White.copy(alpha = 0.30f),
                        0.65f to Color.Transparent,
                        1.0f to Color.Transparent
                    )
                )
            )
        }
        // Video / photo / audio lanes show a picture strip or waveform, so the layer name goes on a small chip.
        if ((clip.type == LayerType.VIDEO || clip.type == LayerType.AUDIO) && clip.name.isNotBlank()) {
            Text(
                text = clip.name,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 4.dp, top = 3.dp, end = 40.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
        // A layer inside a real Group: a thin green edge at its start.
        if (clip.groupId != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(3.dp)
                    .background(Color(0xFF5BB85D))
            )
        }

        // Video with sound: waveform along the bottom of the frames (no file name / path text).
        if (clip.type == LayerType.VIDEO && clip.uri != null && !clip.isImage) {
            VideoWaveformStrip(
                clip = clip,
                waveform = waveform,
                visibleFromMs = visibleFromMs,
                visibleToMs = visibleToMs,
                pxPerMs = pxPerMs,
                modifier = Modifier.align(Alignment.BottomStart)
            )
        }
        // Keyframe diamonds of the selected layer (one per keyframe time, any property).
        if (isSelected && clip.keyframes.isNotEmpty()) {
            val times = remember(clip.keyframes) { clip.keyframeTimes() }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val half = 8.dp.toPx()
                for (t in times) {
                    if (t < 0L || t > clip.durationMs) continue
                    val cx = t * pxPerMs * density
                    val cy = size.height / 2f
                    val d = Path().apply {
                        moveTo(cx, cy - half)
                        lineTo(cx + half * 0.8f, cy)
                        lineTo(cx, cy + half)
                        lineTo(cx - half * 0.8f, cy)
                        close()
                    }
                    drawPath(d, AmTextDim, style = Stroke(width = 4.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
                    drawPath(d, Color.White)
                }
            }
        }
        // Build 187: the old three-line Grip at the bar's right end is gone — the number
        // badge (below) now marks the bar's end, exactly as in the references. The drag
        // itself is unchanged (long-press drag anywhere on the bar).
      } // end capsule bar
        // Build 187: the white circle BADGE at the bar's right end, dark bold number =
        // the layer's number in the displayed stack. Part of the bar rendering, NOT a
        // button: it carries no pointer input, so taps/drags pass through to the bar.
        // Normally it STRADDLES the bar/track boundary like the references; it clamps
        // fully inside the bar when the clip is SELECTED (the right trim handle sits
        // exactly at the bar's end and must stay clear) or too short to straddle safely
        // (width < 56 dp — badge + label + trim clearance no longer fit the straddle).
        if (!isChain && layerNumber != null) {
            LayerBadge(
                clip = clip,
                number = layerNumber,
                inside = isSelected || width < 56.dp,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

/**
 * Build 187 — the bar-end badge: a white circle (92% of the bar height, like the
 * references) with a thin ring in the clip's own colour and the layer's number in dark
 * bold. The Controller's badge is teal-tinted, the Null/Camera badge is dark grey —
 * each matches its bar's design family. [inside] = clamped fully inside the bar;
 * otherwise the badge straddles the bar's right end (its centre on the boundary).
 */
@Composable
private fun LayerBadge(
    clip: TimelineClip,
    number: Int,
    inside: Boolean,
    modifier: Modifier = Modifier
) {
    val (fill, ring, ink) = badgeStyleFor(clip.type)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight(0.92f)
            .aspectRatio(1f)
    ) {
        // Captured here: inside the badge's own Box the BoxWithConstraints receiver is
        // shadowed, so maxHeight would not resolve there (build 187's one compile error).
        val badgeH = maxHeight
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset(x = if (inside) (-3).dp else badgeH * 0.46f)
                .background(fill, CircleShape)
                .border(1.5.dp, ring, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number.toString(),
                color = ink,
                fontSize = (badgeH.value * if (number >= 100) 0.30f else 0.42f).sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
        }
    }
}

/** Badge colours per design family: (fill, ring, number ink). */
private fun badgeStyleFor(type: LayerType): Triple<Color, Color, Color> = when (type) {
    LayerType.CONTROLLER -> Triple(Color(0xFFCCF2EE), ControllerTeal, Color(0xFF00574F))
    LayerType.NULL, LayerType.CAMERA ->
        Triple(Color(0xFF262626), Color.White.copy(alpha = 0.25f), Color(0xFFBDBDBD))
    LayerType.VIDEO -> Triple(Color.White, Color(0xFF27384C), Color(0xFF141414))
    LayerType.AUDIO -> Triple(Color.White, AudioBarColor, Color(0xFF141414))
    LayerType.TEXT -> Triple(Color.White, GoldClipDeep, Color(0xFF141414))
    LayerType.SHAPE -> Triple(Color.White, ShapeBarBottom, Color(0xFF141414))
    LayerType.GROUP -> Triple(Color.White, Color(0xFF3F8F43), Color(0xFF141414))
    else -> Triple(Color.White, Color(0xFFF5A44B), Color(0xFF141414))
}


/**
 * Build 187 — a clip's capsule body in Sarowar's reference design: a FLAT clip-type
 * colour (the vertical gradient, glossy sheen and rim of the old bar are gone) and the
 * layer name in dark bold. A SELECTED clip runs its own colour into its lighter shade
 * along the bar's length (the gradient reference) — that gradient is the clip's
 * content-colour treatment per his reference, not chrome, so the build-183 boxless /
 * B-W chrome law is untouched everywhere else.
 */
@Composable
private fun CapsuleBar(
    color: Color,
    label: String? = null,
    selected: Boolean = false
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (selected) Brush.horizontalGradient(
                    listOf(color, lerpColor(color, Color.White, 0.45f))
                ) else androidx.compose.ui.graphics.SolidColor(color)
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                color = Color(0xFF111111),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, end = 34.dp)
            )
        }
    }
}

/**
 * Build 187 — the Controller's DIFFERENT bar (Sarowar's explicit order): a HOLLOW
 * outline in its settled teal marker family — transparent fill, teal border, teal
 * label — so it can never be mistaken for a filled content bar. Selected: the border
 * thickens and a faint teal wash fills the hollow. Rendering only; the settled
 * fan-out semantics (build 155) are untouched.
 */
@Composable
private fun ControllerBar(label: String?, selected: Boolean) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (selected) Modifier.background(ControllerTeal.copy(alpha = 0.14f), shape) else Modifier)
            .border(if (selected) 2.dp else 1.5.dp, ControllerTeal, shape),
        contentAlignment = Alignment.CenterStart
    ) {
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                color = ControllerTeal,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, end = 34.dp)
            )
        }
    }
}

/**
 * Build 187 — the Null / Camera bar: visually quiet (dark hollow, grey outline, dim
 * label) — these layers are invisible utility, so the bar stays near-silent, and it is
 * deliberately NOT the Controller's teal design.
 */
@Composable
private fun QuietBar(label: String?, selected: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .border(
                1.dp,
                Color.White.copy(alpha = if (selected) 0.65f else 0.28f),
                RoundedCornerShape(percent = 50)
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                color = AmTextDim,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, end = 34.dp)
            )
        }
    }
}

/**
 * Build 187 — diagonal stripes over an audio bar, per the striped reference: darker
 * stripes of the bar's own colour leaning like //// (top end shifted right by half the
 * bar height, the reference's ~63°), drawn BEHIND the content, so the white waveform
 * stays on top and legible — the audio reading (indigo + white wave) is unmistakable.
 */
private fun Modifier.audioStripes(base: Color = AudioBarColor): Modifier = drawBehind {
    val stripe = lerpColor(base, Color.Black, 0.20f)
    val w = 3.dp.toPx()
    val period = 7.dp.toPx()
    val lean = size.height * 0.5f
    var x = -size.height
    while (x < size.width + size.height) {
        drawLine(stripe, Offset(x, size.height), Offset(x + lean, 0f), strokeWidth = w)
        x += period
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color =
    androidx.compose.ui.graphics.lerp(a, b, t)

/** Standard Compose/Android fling friction (1f = default). Higher = shorter glide. */
private const val FLING_FRICTION = 1f
/** Min horizontal velocity (px/s) to start a coasting fling (Android's min fling is ~50dp/s). */
private const val FLING_VELOCITY_THRESHOLD_PX = 100f
/** Stop the decay once velocity falls below this (ms/s). */
private const val FLING_ABS_VELOCITY_THRESHOLD = 20f

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
 * Uses the live [pxPerMs] so zoomed timelines pick the correct frames (not the default scale).
 */
@Composable
private fun VideoFrameStrip(
    clip: TimelineClip,
    widthDp: Dp,
    visibleFromMs: Long,
    visibleToMs: Long,
    pxPerMs: Float = TIMELINE_PX_PER_MS
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val loader = remember(context) { ThumbLoader.get(context) }

    val tileW = ThumbTileWidth.value
    val tileCount = ceil(widthDp.value / tileW).toInt().coerceAtLeast(1)
    val scale = pxPerMs.coerceAtLeast(0.0001f)

    val relFromMs = visibleFromMs - clip.startMs
    val relToMs = visibleToMs - clip.startMs
    if (relToMs < 0L || relFromMs > clip.durationMs) return

    val first = (relFromMs.coerceAtLeast(0L) * scale / tileW).toInt().coerceIn(0, tileCount - 1)
    val last = (relToMs * scale / tileW).toInt().coerceIn(0, tileCount - 1)

    val tilePxW = with(density) { ThumbTileWidth.roundToPx() }
    val tilePxH = with(density) { ThumbTileHeight.roundToPx() }

    Box(modifier = Modifier.fillMaxSize().background(AmPanel)) {
        for (i in first..last) {
            val key: String
            var frameMs = 0L
            if (clip.isImage) {
                key = "${clip.uri}@img"
            } else {
                val timelineOffsetMs = (i * tileW + tileW / 2f) / scale
                var srcMs = clip.trimStartMs + clip.sourceOffsetMs(timelineOffsetMs.toLong())
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


/**
 * Editor panels (Transform / Color / Blending): the selected layer is drawn by the REAL timeline
 * panel (same eye, thumbnail, clip bar, trim handles, name, grip), alone in the first row.
 */
@Composable
fun SelectedLayerRow(
    clipId: String,
    tracks: List<TimelineTrack>,
    playheadMs: () -> Long,
    totalDurationMs: Long,
    onSeekTo: (Long) -> Unit,
    onMoveClipInTime: (clipId: String, deltaMs: Long) -> Unit,
    onBeginClipTimeDrag: () -> Unit,
    onTrimClipEdge: (clipId: String, isLeft: Boolean, deltaMs: Long) -> Unit,
    onToggleClipVisibility: (clipId: String) -> Unit,
    pxPerMs: Float,
    onPxPerMsChange: (Float) -> Unit,
    waveforms: Map<String, FloatArray> = emptyMap(),
    /** Called when a scrub starts (the editor pauses playback, like on the main timeline). */
    onScrubStart: () -> Unit = {},
    isPlaying: Boolean = false,
    modifier: Modifier = Modifier
) {
    val track = tracks.firstOrNull { t -> t.clips.any { it.id == clipId } } ?: return
    val clip = track.clips.first { it.id == clipId }
    TimelineLayerPanel(
        height = laneHeightFor(clip.type) + 8.dp,
        tracks = listOf(track.copy(clips = listOf(clip))),
        playheadMs = playheadMs,
        totalDurationMs = totalDurationMs,
        selectedClipId = clipId,
        isMasterMuted = false,
        onSeekTo = onSeekTo,
        onClipSelect = {},
        onSplitClip = {},
        onDeleteClip = {},
        onDuplicateClip = {},
        onToggleMasterAudio = {},
        onMoveClipInTime = onMoveClipInTime,
        onBeginClipTimeDrag = onBeginClipTimeDrag,
        onTrimClipEdge = onTrimClipEdge,
        onToggleClipVisibility = onToggleClipVisibility,
        pxPerMs = pxPerMs,
        onPxPerMsChange = onPxPerMsChange,
        waveforms = waveforms,
        showQuickActions = false,
        onScrubStart = onScrubStart,
        isPlaying = isPlaying,
        modifier = modifier
    )
}
