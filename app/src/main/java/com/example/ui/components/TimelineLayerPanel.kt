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
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Link
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.model.keyframeTimes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.example.ui.theme.AmBase
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
import com.example.ui.util.amChromeSpec
import com.example.ui.util.bouncyClickable
import com.example.ui.theme.NeonLime
import com.example.ui.theme.NeonLimeDeep
import com.example.ui.theme.NeonBarBlue
import com.example.ui.theme.NeonBarCyan
import com.example.ui.theme.NeonBarGreen
import com.example.ui.theme.NeonBarIndigo
import com.example.ui.theme.NeonBarOrange
import com.example.ui.theme.NeonBarPink
import com.example.ui.theme.NeonBarViolet
import com.example.ui.theme.GoldClip
import com.example.ui.theme.GoldClipDeep
import com.example.ui.theme.GoldThumbTop
import com.example.ui.theme.GoldThumbBottom
import com.example.ui.theme.PillTop
import com.example.ui.theme.PillBottom
import com.example.ui.theme.TrimHandleTop
import com.example.ui.theme.TrimHandleBottom
import com.example.ui.theme.TimelineBlack
import com.example.ui.theme.ClipFilmBody
import com.example.ui.theme.LanePill
import com.example.ui.theme.LanePillDark
import com.example.ui.theme.NeonBarGold
import com.example.ui.theme.ChainBridge
import com.example.ui.theme.ChainEmptyInk
import com.example.ui.theme.ChainEmptyInkSelected
import com.example.ui.theme.ChainSectionBg
import com.example.ui.theme.ChainSectionBgSelected
import com.example.ui.theme.ChainSectionBorder
import com.example.ui.theme.ClipCameraIndigo
import com.example.ui.theme.ClipSpaceViolet
import com.example.ui.theme.ClipDrawingRose
import com.example.ui.theme.ClipGroupTeal
import com.example.ui.theme.ClipStickerAmber
import com.example.ui.theme.ClipVectorCyan
import com.example.ui.theme.RefEyeBg
import com.example.ui.theme.RefEyeHidden
import com.example.ui.theme.LaneEyeTint
import com.example.ui.theme.LaneThumbWhite
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
    /** Build 218: delete one layer by id (the chain section's x buttons). */
    onDeleteClipById: (String) -> Unit = {},
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
    onAddClick: (() -> Unit)? = null,
    /**
     * Build 211 (NO STICKY TOOL LINES): true while the unified tools
     * panel is open. The selected-layer bar and the multi-select bar are
     * then NOT rendered and their bottom reserves vanish — their actions
     * are published to the tools grid's one scrolling list instead (see
     * [barActionsPanel]), as its head tiles.
     */
    toolsPanelOpen: Boolean = false,
    /** The bar-actions bridge to the tools grid (build 211); null = nowhere to publish. */
    barActionsPanel: BarActionsState? = null,
    /** Build 217: the reference's comfort lane density (rows 44 dp instead of 32 dp). */
    comfortDensity: Boolean = false
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
            .background(AmBase)
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
        val laneHeights = remember(tracks, comfortDensity) {
            tracks.map { t -> t.clips.maxOfOrNull { laneHeightFor(it.type, comfortDensity) } ?: 20.dp }
        }
        val laneTopsPx = remember(laneHeights, dpToPx) {
            val tops = FloatArray(laneHeights.size + 1)
            var y = 3f * dpToPx // top padding of the scroll content
            for (i in laneHeights.indices) {
                tops[i] = y
                y += (laneHeights[i].value + 3f) * dpToPx
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
        val leftRailWidth = 34.dp
        // The timeline content fills the whole width and runs UNDER the left controls (like Alight
        // Motion): layers are no longer cut off by a black strip, they show between / behind the
        // eye + thumbnail pills. The rail is drawn on top.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(verticalScrollState)
                .padding(top = 3.dp, bottom = if (!toolsPanelOpen && showActionBar) SelectedBarHeight + 3.dp else 3.dp)
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
                    verticalArrangement = Arrangement.spacedBy(3.dp)
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
                            onDeleteClipById = onDeleteClipById,
                            pxPerMs = pxPerMs,
                            visibleFromMs = visibleFromMs,
                            visibleToMs = visibleToMs,
                            waveforms = waveforms,
                            multiSelectIds = multiSelectIds,
                            comfort = comfortDensity,
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
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                tracks.forEachIndexed { laneIndex, track ->
                    // Build 218: a chain lane's rail cell belongs to the Chain
                    // clip (the section's eye + link glyph), wherever it sits
                    // in the lane's clip order; other lanes keep the head clip.
                    val chainClip = track.clips.firstOrNull { it.type == LayerType.CHAIN }
                    val clip = chainClip ?: track.clips.firstOrNull()
                    val rowH = laneHeights[laneIndex]
                    if (laneIndex !in laneWindow) {
                        Spacer(Modifier.height(rowH))
                        return@forEachIndexed
                    }
                    if (chainClip != null && !isMulti) {
                        ChainLaneControl(
                            chain = chainClip,
                            height = rowH,
                            isSelected = chainClip.id == selectedClipId,
                            onToggleVisibility = { id -> onToggleClipVisibility(id) },
                            onSelect = { id -> onClipSelect(id) },
                            onLongPress = onLongPressLane
                        )
                    } else {
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
        }

        // 2. (Build 217: the master speaker that floated at the panel's
        //    bottom-left is gone — the reference's tracks area has no
        //    such button. Master mute lives in the timeline options
        //    sheet now, and in the selected-layer bar's Master tile.)

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
            val top = 0f
            val bottom = size.height - 2.dp.toPx()
            // Build 217 (reference): the 2 dp WHITE playhead line runs
            // through the tracks from the panel's top (the ruler above
            // starts it below its timecode), over a soft dark shadow
            // (the reference's 0 0 6 px rgba(0,0,0,0.75) glow).
            drawLine(
                Color.Black.copy(alpha = 0.35f), Offset(cx, top), Offset(cx, bottom),
                strokeWidth = 7.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            drawLine(
                PlayheadColor, Offset(cx, top), Offset(cx, bottom),
                strokeWidth = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }

        // Round flat add button (bottom right). Sits above the selected-layer bar when it is shown.
        if (onAddClick != null && !isMulti && showQuickActions) {
            NeonAddFab(
                onClick = onAddClick,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = if (showActionBar && !toolsPanelOpen) SelectedBarHeight + 5.dp else 8.dp)
            )
        }

        // Build 211 (NO STICKY TOOL LINES): while the tools panel is
        // open, the selected-layer bar's actions are published to the
        // tools grid as its HEAD tiles — same order, icons, callbacks
        // and tags as the bar in section 6; Delete keeps its red.
        // Build 224: the block lives in TimelineBarActionsPublisher so the
        // native timeline's host can publish the very same tiles.
        TimelineBarActionsPublisher(
            barActionsPanel = barActionsPanel,
            showActionBar = showActionBar,
            toolsPanelOpen = toolsPanelOpen,
            tracks = tracks,
            selectedClipId = selectedClipId,
            isMasterMuted = isMasterMuted,
            onToggleMasterAudio = onToggleMasterAudio,
            onSplitClip = onSplitClip,
            onDuplicateClip = onDuplicateClip,
            onSpeed = onSpeed,
            onReverse = onReverse,
            onFlipHorizontal = onFlipHorizontal,
            onFlipVertical = onFlipVertical,
            onReplace = onReplace,
            onDeleteClip = onDeleteClip
        )

        // 5. Multi-select bar: build 260 moved it OUT of the timeline —
        //    the screen composes MultiBarHost in the FOOTER slot, where
        //    it automatically replaces the add-layer dock while
        //    multi-select is active (Sarowar's order). The host still
        //    publishes the bar's actions to the tools grid while the
        //    tools panel is open (build 211), from the footer.

        // 6. Selected-layer action bar: full width, sits at the bottom of the layer panel
        //    (the top one of the two panels). Split / Copy / Flip / Replace / Delete.
        //    Build 211 (NO STICKY TOOL LINES): while the tools panel is
        //    open this bar is NOT rendered — its actions are published
        //    to the grid as head tiles (see PublishBarActions below).
        if (showActionBar && !toolsPanelOpen) {
            TimelineSelectedBar(
                tracks = tracks,
                selectedClipId = selectedClipId,
                isMasterMuted = isMasterMuted,
                onToggleMasterAudio = onToggleMasterAudio,
                onSplitClip = onSplitClip,
                onDuplicateClip = onDuplicateClip,
                onSpeed = onSpeed,
                onReverse = onReverse,
                onFlipHorizontal = onFlipHorizontal,
                onFlipVertical = onFlipVertical,
                onReplace = onReplace,
                onDeleteClip = onDeleteClip,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/**
 * Build 224: the panel's bar-actions publisher, extracted unchanged so the
 * native timeline host publishes the identical head tiles while the tools
 * panel is open (build 211's NO STICKY TOOL LINES law holds in both modes).
 */
@Composable
internal fun TimelineBarActionsPublisher(
    barActionsPanel: BarActionsState?,
    showActionBar: Boolean,
    toolsPanelOpen: Boolean,
    tracks: List<TimelineTrack>,
    selectedClipId: String?,
    isMasterMuted: Boolean,
    onToggleMasterAudio: () -> Unit,
    onSplitClip: () -> Unit,
    onDuplicateClip: () -> Unit,
    onSpeed: (() -> Unit)?,
    onReverse: (() -> Unit)?,
    onFlipHorizontal: () -> Unit,
    onFlipVertical: () -> Unit,
    onReplace: (() -> Unit)?,
    onDeleteClip: () -> Unit
) {
    PublishBarActions(
        barActionsPanel,
        if (showActionBar && toolsPanelOpen) {
            val selIsAudioPub = tracks.any { t -> t.clips.any { it.id == selectedClipId && it.type == LayerType.AUDIO } }
            val tiles = buildList {
                fun tile(
                    label: String, tag: String,
                    icon: androidx.compose.ui.graphics.vector.ImageVector,
                    destructive: Boolean = false,
                    onClick: () -> Unit
                ) {
                    add(
                        BarActionTile(
                            label, tag, destructive = destructive, onClick = onClick,
                            glyph = BarGlyph.Icon(icon, if (destructive) AmDanger else IconActive)
                        )
                    )
                }
                tile(
                    "Master", "bar_master",
                    if (isMasterMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    onClick = onToggleMasterAudio
                )
                // Build 260: Split's glyph is Sarowar's `][` bracket
                // design (BracketGlyphs), a drawn glyph like the
                // multi-select bar's — no longer the scissors icon.
                add(
                    BarActionTile(
                        "Split", "bar_split", onClick = onSplitClip,
                        glyph = BarGlyph.Drawn(24f, 20f, IconActive) {
                            drawSplitGlyph(androidx.compose.ui.graphics.Color.White)
                        }
                    )
                )
                tile("Copy", "bar_copy", Icons.Default.ContentCopy, onClick = onDuplicateClip)
                onSpeed?.let { tile("Speed", "bar_speed", Icons.Default.Speed, onClick = it) }
                onReverse?.let { tile("Reverse", "bar_reverse", Icons.Default.FastRewind, onClick = it) }
                if (!selIsAudioPub) {
                    tile("Flip H", "bar_flip_h", Icons.Default.Flip, onClick = onFlipHorizontal)
                    tile("Flip V", "bar_flip_v", Icons.Default.FlipCameraAndroid, onClick = onFlipVertical)
                    onReplace?.let { tile("Replace", "bar_replace", Icons.Default.SwapHoriz, onClick = it) }
                }
                tile("Delete", "bar_delete", Icons.Default.Delete, destructive = true, onClick = onDeleteClip)
            }
            BarActionsSection(
                key = "selected:$selectedClipId:$isMasterMuted:$selIsAudioPub:${tiles.size}",
                tiles = tiles
            )
        } else null
    )
}

/**
 * Build 224: the selected-layer action bar (the panel's section 6), extracted
 * unchanged so the native timeline host renders the identical bar when the
 * tools panel is manually closed over a selection.
 */
@Composable
internal fun TimelineSelectedBar(
    tracks: List<TimelineTrack>,
    selectedClipId: String?,
    isMasterMuted: Boolean,
    onToggleMasterAudio: () -> Unit,
    onSplitClip: () -> Unit,
    onDuplicateClip: () -> Unit,
    onSpeed: (() -> Unit)?,
    onReverse: (() -> Unit)?,
    onFlipHorizontal: () -> Unit,
    onFlipVertical: () -> Unit,
    onReplace: (() -> Unit)?,
    onDeleteClip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selIsAudio = tracks.any { t -> t.clips.any { it.id == selectedClipId && it.type == LayerType.AUDIO } }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(SelectedBarHeight)
            .background(EditorBackground)
            .padding(horizontal = 6.dp)
            .enterPop(fromBottom = true)
            .testTag("selected_layer_action_bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SelectedBarButton(
            icon = if (isMasterMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            label = "Master", tint = IconActive, onClick = onToggleMasterAudio,
            modifier = Modifier.weight(1f), tag = "bar_master"
        )
        SelectedBarDrawnButton("Split", onSplitClip, Modifier.weight(1f), tag = "bar_split") {
            drawSplitGlyph(AmMint)
        }
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

/**
 * Build 224: the lanes column's bottom padding, exactly as the Compose
 * panel computes it (its vertical-scroll content padding) — the native
 * surface needs the same content height for its scroll range.
 *
 * Build 260: the multi-select bar no longer overlays the lanes (it
 * lives in the footer slot), so the multi reservation is gone — only
 * the selected-layer bar, still an in-panel overlay, keeps its
 * reservation. [isMulti] stays a parameter for the call sites.
 */
internal fun timelineBottomPadDp(toolsPanelOpen: Boolean, isMulti: Boolean, showActionBar: Boolean): Float =
    if (!toolsPanelOpen && showActionBar) SelectedBarHeight.value + 3f
    else 3f

private val SelectedBarHeight = 40.dp

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
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * Build 260: [SelectedBarButton]'s drawn-glyph twin — same box, same
 * 18 dp icon slot, same bouncyClickable contract, but the glyph is a
 * DrawScope draw (Sarowar's bracket family in BracketGlyphs.kt has no
 * Material icon). The label is the accessibility description, as with
 * the icon variant; the draw paints in its own colour.
 */
@Composable
private fun SelectedBarDrawnButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "",
    draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = 4.dp)
            .bouncyClickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .size(18.dp)
                .semantics { contentDescription = label }
        ) { draw() }
    }
}


/**
 * The boolean row's eligibility rule (build 192), extracted in build
 * 260 so the screen's footer layout math can budget the multi-select
 * bar's height with the SAME rule the bar itself uses: exactly two
 * checked layers, each a stored-fields boolean candidate (a visible,
 * non-mask shape with a filled region), active at the playhead, and
 * not pose-placed (the frame-space evaluation in the engine refuses
 * those — the row must not promise what it cannot do).
 */
internal fun multiBooleanReady(
    tracks: List<TimelineTrack>,
    multiSelectIds: Set<String>,
    playheadMs: Long
): Boolean {
    if (multiSelectIds.size != 2) return false
    val checked = tracks.flatMap { it.clips }.filter { it.id in multiSelectIds }
    return checked.size == 2 && checked.all {
        it.isBooleanShapeCandidate && it.isActiveAt(playheadMs) &&
            !com.example.engine.overlayNeedsPose(it)
    }
}

/**
 * Reads the playhead itself, so while scrubbing only this small bar recomposes (not the whole
 * timeline) when the option group changes (split / start / end).
 *
 * Build 260: the host is composed by the SCREEN in the footer slot —
 * entering multi-select swaps the add-layer dock for this bar
 * automatically (Sarowar's order); the timeline panel no longer
 * overlays it over the lanes.
 */
@Composable
internal fun MultiBarHost(
    tracks: List<TimelineTrack>,
    multiSelectIds: Set<String>,
    playheadMs: () -> Long,
    onAction: (MultiAction) -> Unit,
    onSpatialAction: (com.example.model.SpatialAction) -> Unit = {},
    onBooleanAction: (com.example.model.ShapeBooleanOp) -> Unit = {},
    onDeleteChecked: () -> Unit = {},
    onSelectAll: () -> Unit = {},
    /** Build 260: the bar's Done tile — leaves multi-select. */
    onExitMulti: () -> Unit = {},
    /** Build 211: while the tools panel is open, publish tiles instead of rendering the bar. */
    toolsPanelOpen: Boolean = false,
    /** The bar-actions bridge to the tools grid (build 211); null = nowhere to publish. */
    barActionsPanel: BarActionsState? = null,
    modifier: Modifier = Modifier
) {
    val mode by remember(tracks, multiSelectIds) {
        derivedStateOf {
            val checked = tracks.flatMap { it.clips }.filter { it.id in multiSelectIds }
            MultiSelectOps.barMode(checked, playheadMs())
        }
    }
    val booleanReady by remember(tracks, multiSelectIds) {
        derivedStateOf { multiBooleanReady(tracks, multiSelectIds, playheadMs()) }
    }
    // Build 211 (NO STICKY TOOL LINES): while the tools panel is open
    // the bar renders nowhere — its actions are the grid's head tiles,
    // built from the same mode / boolean state computed above.
    PublishBarActions(
        barActionsPanel,
        if (toolsPanelOpen) BarActionsSection(
            key = "multi:$mode:$booleanReady:${multiSelectIds.size}",
            tiles = multiBarActionTiles(
                mode = mode,
                selectedCount = multiSelectIds.size,
                showBooleanRow = booleanReady,
                onAction = onAction,
                onSpatialAction = onSpatialAction,
                onBooleanAction = onBooleanAction,
                onDeleteChecked = onDeleteChecked,
                onSelectAll = onSelectAll
            )
        ) else null
    )
    if (!toolsPanelOpen) {
        // Build 235: the bar is compact (count + All / Delete / Done
        // + the boolean row) — mode / time-align / spatial / Chain /
        // Group live in the tools grid tiles published above.
        MultiSelectBar(
            selectedCount = multiSelectIds.size,
            showBooleanRow = booleanReady,
            onBooleanAction = onBooleanAction,
            onDeleteChecked = onDeleteChecked,
            onSelectAll = onSelectAll,
            onExit = onExitMulti,
            modifier = modifier
        )
    }
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
    // Build 217 (Sarowar's HTML reference): the lane control is the
    // reference's EYE-BUTTON — 42 dp wide, the full row height, fill
    // #1E1E24, rounded only on the right (r9), the eye glyph #A1A1AA
    // (a hidden layer's eye is #52525B). The build-216 eye + thumbnail
    // pill is gone: the reference has no thumbnail. The gestures are
    // unchanged: tap = select (or check in multi-select), long press =
    // multi-select, and the eye itself toggles visibility.
    val haptic = LocalHapticFeedback.current
    val latestSelect by rememberUpdatedState(onSelect)
    val latestLong by rememberUpdatedState(onLongPress)
    val latestToggle by rememberUpdatedState(onToggleMulti)
    val latestMulti by rememberUpdatedState(isMulti)
    val eyeShape = RoundedCornerShape(topEnd = 7.dp, bottomEnd = 7.dp)
    Box(
        modifier = Modifier
            .width(34.dp)
            .height(height)
            .clip(eyeShape)
            .background(com.example.ui.theme.RefEyeBg, eyeShape)
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
            .testTag("lane_control_${clip.id}"),
        contentAlignment = Alignment.Center
    ) {
        if (isMulti) {
            // Multi-select: the eye turns into a check circle (empty when not checked).
            if (isChecked) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .background(com.example.ui.theme.RefLime, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = com.example.ui.theme.RefLimeInk,
                        modifier = Modifier.size(13.dp)
                    )
                }
            } else {
                Box(
                    Modifier
                        .size(16.dp)
                        .border(1.5.dp, LaneEyeTint.copy(alpha = 0.4f), CircleShape)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .bouncyClickable(pressedScale = 1f, onClick = { onToggleVisibility(clip.id) }),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (clip.isVisible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    contentDescription = if (clip.isVisible) "Hide layer" else "Show layer",
                    tint = if (clip.isVisible) LaneEyeTint else com.example.ui.theme.RefEyeHidden,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

/** Rounded-square layer thumbnail: gold "T" for text, white tile for shapes, etc. */
@Composable
private fun LayerThumb(clip: TimelineClip, thumbColor: Color) {
    val shape = RoundedCornerShape(5.dp)
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
            .size(18.dp)
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
            LayerType.TEXT -> Text("T", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
            LayerType.AUDIO -> Icon(Icons.Default.Mic, null, tint = Color.White, modifier = Modifier.size(11.dp))
            else -> {}
        }
    }
}

/**
 * Build 218 (Sarowar's HTML reference v3): the chain lane's left cell — the
 * reference's `.chain-eye-cell`: full section height, #1E1E24 fill, a 1 dp
 * right border and the section's top/bottom hairlines, holding the eye
 * (the chain's visibility toggle — the eye-btn's job for this lane) over a
 * small chain-link glyph. Tapping the cell selects the Chain; a long press
 * opens multi-select, exactly like a normal lane cell.
 */
@Composable
private fun ChainLaneControl(
    chain: TimelineClip,
    height: Dp,
    isSelected: Boolean,
    onToggleVisibility: (String) -> Unit,
    onSelect: (String) -> Unit,
    onLongPress: (String) -> Unit
) {
    val eyeTint = when {
        !chain.isVisible -> RefEyeHidden
        isSelected -> Color.White
        else -> LaneEyeTint
    }
    Box(
        modifier = Modifier
            .width(34.dp)
            .height(height)
            .background(RefEyeBg)
            .drawBehind {
                val hair = if (isSelected) 1.5.dp.toPx() else 1.dp.toPx()
                val hairColor = if (isSelected) Color.White else ChainSectionBorder
                drawRect(hairColor, Offset(size.width - 1.dp.toPx(), 0f), Size(1.dp.toPx(), size.height))
                drawRect(hairColor, Offset(0f, 0f), Size(size.width, hair))
                drawRect(hairColor, Offset(0f, size.height - hair), Size(size.width, hair))
            }
            .pointerInput(chain.id) {
                detectTapGestures(
                    onTap = { onSelect(chain.id) },
                    onLongPress = { onLongPress(chain.id) }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(17.dp)
                    .pointerInput(chain.id) {
                        detectTapGestures(onTap = { onToggleVisibility(chain.id) })
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (!chain.isVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (!chain.isVisible) "Show chain" else "Hide chain",
                    tint = eyeTint,
                    modifier = Modifier.size(14.dp)
                )
            }
            Icon(
                imageVector = Icons.Default.Link,
                contentDescription = null,
                tint = eyeTint,
                modifier = Modifier.size(9.dp)
            )
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
    /** Build 218: delete one layer by id (the chain section's x buttons: the
     *  section x deletes the Chain, a child x deletes just that child). */
    onDeleteClipById: (String) -> Unit = {},
    pxPerMs: Float = TIMELINE_PX_PER_MS,
    visibleFromMs: Long = Long.MIN_VALUE / 4,
    visibleToMs: Long = Long.MAX_VALUE / 4,
    waveforms: Map<String, FloatArray> = emptyMap(),
    multiSelectIds: Set<String> = emptySet(),
    /** false = the lane is simply there (no pop-in spring), used for bulk adds and the first draw. */
    animateIn: Boolean = true,
    /** Width of the screen: a Chain is exactly this wide, edge to edge. */
    viewWidth: Dp = 360.dp,
    /** Build 217: comfort lane density (44 dp rows, r10 bars) vs compact (32 dp, r9). */
    comfort: Boolean = false,
    /** How far the lane column is shifted by the timeline scroll (px); a Chain moves back by it, so it never seems to scroll. */
    columnShiftPx: () -> Float = { 0f }
) {
    // Alight Motion: one layer per line → fixed row height by layer type.
    val trackHeight = track.clips.maxOfOrNull { laneHeightFor(it.type, comfort) } ?: 20.dp
    // Build 187: the capsule bar's height — the row minus the inset, centred in the row.
    val barHeight = trackHeight - LaneBarInset
    val density = LocalDensity.current
    val pxPerDp = density.density
    // How many vertical pixels ≈ one lane step for reorder.
    val laneStepPx = with(density) { (trackHeight + 3.dp).toPx() }

    // Room on the left so the left trim handle of a layer at 0:00 is still inside the lane.
    val handlePx = with(density) { TrimHandleWidth.toPx() }
    // Build 218: a Chain lane renders as the reference's full-width SECTION —
    // the Chain clip's own bar is replaced by the section drawn below.
    val chainClip = track.clips.firstOrNull { it.type == LayerType.CHAIN }
    val chainChildren = if (chainClip != null) track.clips.filter { it.chainId == chainClip.id } else emptyList()
    val chainSelected = chainClip != null && chainClip.id == selectedClipId
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
            slideY.animateTo(0f, amChromeSpec())
        }
    }
    // Lift animation while a layer is held / dragged.
    val lift by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0f,
        animationSpec = amChromeSpec(),
        label = "laneLift"
    )
    // New lane pops in.
    val appear = remember { Animatable(if (animateIn) 0f else 1f) }
    LaunchedEffect(Unit) { if (animateIn) appear.animateTo(1f, amChromeSpec()) }

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
        // Build 218 (Sarowar's HTML reference v3): the CHAIN SECTION — a
        // full-width band pinned to the screen (it cancels the lane column's
        // shift, exactly like the old chain band), #18181D with 1 dp
        // hairlines; selected (the Chain itself is the selection) the
        // hairlines turn 1.5 dp white on #1D1D24; a hidden chain dims the
        // section to 35%. The bridges between adjacent children and the
        // empty-chain label are drawn here; the children themselves are
        // rendered by the normal clip loop below, restyled by TimelineClipItem.
        if (chainClip != null) {
            Box(
                Modifier
                    .offset { IntOffset(0, 0) }
                    .graphicsLayer { translationX = -columnShiftPx() }
                    .width(viewWidth)
                    .height(trackHeight)
                    .alpha(if (!chainClip.isVisible) 0.35f else 1f)
                    .background(if (chainSelected) ChainSectionBgSelected else ChainSectionBg)
                    .drawBehind {
                        val hair = if (chainSelected) 1.5.dp.toPx() else 1.dp.toPx()
                        val hairColor = if (chainSelected) Color.White else ChainSectionBorder
                        drawRect(hairColor, Offset(0f, 0f), Size(size.width, hair))
                        drawRect(hairColor, Offset(0f, size.height - hair), Size(size.width, hair))
                        // Bridges: a 2 dp #3A3A46 line spanning the gap
                        // between each pair of adjacent children, mid-height.
                        if (chainChildren.size > 1) {
                            val sorted = chainChildren.sortedBy { it.startMs }
                            val midY = size.height / 2f
                            val bh = 2.dp.toPx()
                            for (i in 0 until sorted.size - 1) {
                                val a = sorted[i]
                                val b = sorted[i + 1]
                                val x1 = columnShiftPx() + (a.endMs * pxPerMs * pxPerDp) + handlePx
                                val x2 = columnShiftPx() + (b.startMs * pxPerMs * pxPerDp) + handlePx
                                if (x2 > x1) drawRect(ChainBridge, Offset(x1, midY - bh / 2f), Size(x2 - x1, bh))
                            }
                        }
                    }
            ) {
                if (chainChildren.isEmpty()) {
                    Row(
                        Modifier
                            .align(Alignment.CenterStart)
                            .offset { IntOffset((columnShiftPx() + 42.dp.toPx()).roundToInt(), 0) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.Link, null,
                            tint = if (chainSelected) ChainEmptyInkSelected else ChainEmptyInk,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            "Chain",
                            color = if (chainSelected) ChainEmptyInkSelected else ChainEmptyInk,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
        // One clip per lane, except a Chain lane: the chain bar first (behind), its layers on top of it.
        val laneClips = if (track.clips.size > 1) track.clips.sortedBy { if (it.type == LayerType.CHAIN) 0 else 1 } else track.clips
        laneClips.forEach { clip ->
            val clipWidth = if (clip.type == LayerType.CHAIN) viewWidth else (clip.durationMs * pxPerMs).dp.coerceAtLeast(30.dp)
            val isSelected = clip.id == selectedClipId
            val dragging = isDragging && dragClipId == clip.id

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = if (clip.type == LayerType.CHAIN) 0
                            else (clip.startMs * pxPerMs * pxPerDp + handlePx + if (dragging) dragOffsetX else 0f).roundToInt(),
                            y = if (clip.type == LayerType.CHAIN) 0
                            else if (chainClip != null && clip.chainId == chainClip.id) (3.dp.value * pxPerDp).roundToInt()
                            else (LaneBarInset.value / 2f * pxPerDp).roundToInt()
                        )
                    }
                    // The lane column scrolls with the timeline; a Chain undoes that, so it stays fixed on the screen.
                    .then(
                        if (clip.type == LayerType.CHAIN) Modifier.graphicsLayer { translationX = -columnShiftPx() }
                        else Modifier
                    )
                    .then(
                        if (clip.type == LayerType.CHAIN) Modifier
                        else if (chainClip != null && clip.chainId == chainClip.id) Modifier.height(trackHeight - 6.dp)
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
                    comfort = comfort,
                    visibleFromMs = visibleFromMs,
                    visibleToMs = visibleToMs,
                    waveform = clip.uri?.let { waveforms[it] },
                    pxPerMs = pxPerMs,
                    // Build 187: the badge number = the lane's order in the displayed stack.
                    layerNumber = laneIndex + 1,
                    // Build 218: chain children render as the section's flat clips.
                    inChain = chainClip != null && clip.chainId == chainClip.id,
                    chainSectionSelected = chainSelected,
                    onDeleteSelf = { onDeleteClipById(clip.id) }
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
                val knobHalfPx = 12f * pxPerDp
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

        // Build 218 (reference): the SELECTED section's delete x — pinned to
        // the screen's right edge of the section (8 dp in), vertically
        // centred: a 20 dp box on rgba(0,0,0,0.45), r6 (32 dp touch box).
        // It deletes the Chain — with its children, the app's chain-delete
        // semantics (deleteClipById).
        if (chainClip != null && chainSelected) {
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (columnShiftPx() + (viewWidth.value - 22f) * pxPerDp).roundToInt(),
                            ((trackHeight.value - 24f) / 2f * pxPerDp).roundToInt()
                        )
                    }
                    .size(24.dp)
                    .bouncyClickable(pressedScale = 0.9f, onClick = { onDeleteClipById(chainClip.id) })
                    .testTag("chain_section_delete_x"),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(5.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.size(8.dp)) {
                        val w = 1.5.dp.toPx()
                        val m = w / 2f
                        drawLine(Color.White, Offset(m, m), Offset(size.width - m, size.height - m), w, StrokeCap.Round)
                        drawLine(Color.White, Offset(size.width - m, m), Offset(m, size.height - m), w, StrokeCap.Round)
                    }
                }
            }
        }
    }
}

private val TrimHandleWidth = 26.dp

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
                if (isLeft) RoundedCornerShape(topStart = 7.dp, bottomStart = 7.dp, topEnd = 2.dp, bottomEnd = 2.dp)
                else RoundedCornerShape(topEnd = 7.dp, bottomEnd = 7.dp, topStart = 2.dp, bottomStart = 2.dp)
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
        Box(
            modifier = Modifier
                .width(7.dp)
                .fillMaxHeight()
                .padding(vertical = 2.dp)
                .background(Color.White, RoundedCornerShape(4.dp))
                .border(1.dp, Color(0x0F000000), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(1.6.dp)
                    .fillMaxHeight(0.52f)
                    .background(AmInk, RoundedCornerShape(1.dp))
            )
        }
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
            .size(24.dp)
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
        Canvas(Modifier.size(13.dp)) {
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
// Build 214: rail thumb 22 dp + eye hit box 24 dp, so rows are cut a further 2 dp (slimmest 24 dp).
internal fun laneHeightFor(type: LayerType, comfort: Boolean = false): Dp = when (type) {
    LayerType.CHAIN -> if (comfort) 42.dp else 34.dp
    else -> if (comfort) 34.dp else 24.dp
}

/** Build 187: the capsule bar is this much shorter than its lane row (centred in it). */
private val LaneBarInset = 0.dp
/** Build 187: the lane's dark track groove. The panel is pure black (AmBaseDeep), so the
 *  reference's black track becomes a near-black groove — the same track-vs-page contrast. */
private val LaneTrackColor = Color(0xFF0A0E13) // build 217: no visible groove on the dark timeline (build 276: the new base)
/** Build 187: the Controller's settled teal marker colour (same as CanvasArea's). */
private val ControllerTeal = Color(0xFF00B8A9)
/** Build 215: the audio bar's own colour (AudioClipBody's fill) — the reference's neon green. */
private val AudioBarColor = NeonBarGreen

/**
 * Build 218 (Sarowar's HTML reference v3): the flat bar colour of a layer —
 * the reference's colour table, shared by the normal bars and the chain
 * section's children. PATH shapes split by creation tool (see
 * [TimelineClip.isPenPath] / [TimelineClip.isDrawPath]): a freehand layer
 * takes the drawing rose; a Pen layer — and any other PATH (an imported
 * Alight path, a boolean result) — takes the vector cyan.
 */
internal fun barColorFor(clip: TimelineClip): Color = when {
    clip.type == LayerType.VIDEO -> NeonBarBlue
    clip.type == LayerType.AUDIO -> NeonBarGreen
    clip.type == LayerType.TEXT -> NeonBarPink
    clip.isDrawPath -> ClipDrawingRose
    clip.type == LayerType.SHAPE && clip.shapePattern == "PATH" -> ClipVectorCyan
    clip.type == LayerType.SHAPE -> NeonBarGold
    clip.type == LayerType.STICKER -> ClipStickerAmber
    clip.type == LayerType.OVERLAY -> NeonBarViolet
    clip.type == LayerType.GROUP -> ClipGroupTeal
    clip.type == LayerType.CAMERA -> ClipCameraIndigo
    clip.type == LayerType.SPACE3D -> ClipSpaceViolet
    clip.type == LayerType.NULL -> com.example.ui.theme.RefNullPink
    else -> NeonBarGold
}

/** Build 218: the [BarTypeIcon] kind of a layer (chain children and the
 *  recoloured bars draw their type glyph from it). */
internal fun barGlyphFor(clip: TimelineClip): String = when {
    clip.type == LayerType.TEXT -> "text"
    clip.isDrawPath -> "drawing"
    clip.type == LayerType.SHAPE && clip.shapePattern == "PATH" -> "vector"
    clip.type == LayerType.SHAPE -> "shape"
    clip.type == LayerType.STICKER -> "sticker"
    clip.type == LayerType.CAMERA -> "camera"
    clip.type == LayerType.SPACE3D -> "space"
    clip.type == LayerType.GROUP -> "group"
    clip.type == LayerType.NULL -> "null"
    clip.type == LayerType.VIDEO -> "video"
    clip.type == LayerType.AUDIO -> "music"
    clip.type == LayerType.CHAIN -> "chain"
    else -> "fx"
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
    waveform: FloatArray? = null,
    pxPerMs: Float = TIMELINE_PX_PER_MS,
    /** Build 187: the layer's number in the displayed stack, drawn in the bar's end badge.
     *  Null = no badge (the Transform strip's lone row is not part of the stack display). */
    layerNumber: Int? = null,
    /** Build 217: comfort density — bars are r10 instead of r9. */
    comfort: Boolean = false,
    /** Build 218: this clip is a child inside a Chain section — a flat r8
     *  clip (type colour + glyph + 12.5 sp name, no filmstrip / waveform /
     *  drag handle); its delete x also shows while the SECTION is selected
     *  and deletes just this clip ([onDeleteSelf]). */
    inChain: Boolean = false,
    /** Build 218: the Chain section this child sits in is the selection. */
    chainSectionSelected: Boolean = false,
    /** Build 218: delete just this clip (the chain child's x). */
    onDeleteSelf: () -> Unit = {}
) {
    val selectScale by animateFloatAsState(
        targetValue = 1f, // no grow/pop on touch or select: layers stay perfectly still while scrolling
        animationSpec = amChromeSpec(),
        label = "clipSelectScale"
    )

    // A Chain is a faint grey band fixed on the screen, edge to edge: no start, no end, no corners, no grip.
    val isChain = clip.type == LayerType.CHAIN
    // Build 187 (Sarowar's reference design): the clip is a slim bar on the lane's
    // dark track. The outer box is the bar's layout box — deliberately UNCLIPPED; the
    // bar itself (clipped, clickable) is the inner box. A Chain keeps its rectangle band.
    // Build 217 (Sarowar's HTML reference): the bar is a rounded rect of
    // r9 (r10 in the comfort density) — the reference's exact corners.
    // Build 218: a chain child is r8, the reference's chain-clip corner.
    val barCorner = if (inChain) 6.dp else if (comfort) 8.dp else 7.dp
    val barShape = if (isChain) RectangleShape else RoundedCornerShape(barCorner)
    Box(
        modifier = Modifier.width(width)
            .fillMaxHeight()
            .drawBehind {
                // Build 217 (reference): the selected bar's outer ring —
                // white at 25%, 1 dp, sitting 2.25 dp outside the bar
                // (the bar's own 1.5 dp white border is drawn inside it).
                if ((isSelected || isMultiSelected) && !isChain && clip.type != LayerType.CONTROLLER) {
                    val inset = 2.25.dp.toPx()
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.25f),
                        topLeft = Offset(-inset, -inset),
                        size = Size(size.width + inset * 2, size.height + inset * 2),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barCorner.toPx() + inset),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            }
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
                    // Build 217 (reference): selection = a 1.5 dp WHITE
                    // border, an outer ring of white at 25% (drawn behind,
                    // outside the bar) and the soft drop shadow.
                    Modifier
                        .shadow(
                            6.dp, barShape, clip = false,
                            ambientColor = Color.Black.copy(alpha = 0.45f),
                            spotColor = Color.Black.copy(alpha = 0.45f)
                        )
                        .border(1.5.dp, Color.White, barShape)
                } else {
                    Modifier
                }
            )
    ) {
        // Build 215: a video / image bar's neon body — solid fill + crosshatch UNDER
        // its frame strip (the reference draw order: fill, stripes, content on top).
        // Blue for video, cyan for an image; the frames draw over it as before.
        if (clip.type == LayerType.VIDEO && !inChain) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (clip.isImage) NeonBarCyan else ClipFilmBody)
            )
        }
        if (inChain) {
            // Build 218: a chain child is a FLAT clip inside the section —
            // the type colour, its glyph and its name at 12.5 sp (no
            // filmstrip, no waveform, no name chip).
            CapsuleBar(
                barColorFor(clip),
                if (clip.type == LayerType.TEXT) clip.text?.takeIf { it.isNotBlank() } ?: clip.name else clip.name,
                isSelected,
                fontSize = 10.5.sp,
                endPad = 20.dp,
                icon = { BarTypeIcon(barGlyphFor(clip)) }
            )
        } else when (clip.type) {
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
                    // colour with the build-215 crosshatch like a real audio bar.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(AudioBarColor)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .background(Color.White.copy(alpha = 0.25f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Mic",
                                    tint = Color.White,
                                    modifier = Modifier.size(11.dp)
                                )
                            }
                            repeat(15) {
                                Box(
                                    modifier = Modifier
                                        .width(12.dp)
                                        .height(3.dp)
                                        .background(Color.White.copy(alpha = 0.75f), RoundedCornerShape(2.dp))
                                )
                            }
                        }
                    }
                }
            }

            LayerType.SHAPE -> CapsuleBar(barColorFor(clip), clip.name, isSelected, icon = { BarTypeIcon(barGlyphFor(clip)) }) // build 218: shape = #3EB1F7; a PATH splits by tool — Pen #06B6D4, freehand #FB7185
            // Build 218: the Chain's own bar is GONE — TimelineTrackRow draws
            // the full-width SECTION (fill, hairlines, bridges) behind the
            // children instead; this item stays transparent and only carries
            // the tap / long-press lane gestures.
            LayerType.CHAIN -> Box(Modifier.fillMaxSize())
            LayerType.TEXT -> CapsuleBar(
                NeonBarPink,
                clip.text?.takeIf { it.isNotBlank() } ?: clip.name,
                isSelected,
                icon = { BarTypeIcon("text") }
            )
            LayerType.OVERLAY -> CapsuleBar(NeonBarViolet, clip.name, isSelected, icon = { BarTypeIcon("fx") }) // build 217: overlay/fx = #F54BBF (the reference)
            // Build 218: the reference gives Sticker its own colour — amber #F59E0B.
            LayerType.STICKER -> CapsuleBar(ClipStickerAmber, clip.name, isSelected, icon = { BarTypeIcon("sticker") })
            // Real Group: build 218 — the reference's teal #14B8A6 (its layers
            // below keep a start edge in the same colour).
            LayerType.GROUP -> CapsuleBar(ClipGroupTeal, clip.name, isSelected, icon = { BarTypeIcon("group") })
            // Controller (build 187): Sarowar ordered a DIFFERENT design from every other
            // layer — a HOLLOW teal outline in its settled marker colour (0xFF00B8A9, the
            // CanvasArea marker), transparent fill, teal label and badge. Instantly
            // distinct from every filled bar; its fan-out semantics are untouched.
            // Build 218: the reference has no Controller colour — the hollow
            // treatment stays.
            LayerType.CONTROLLER -> ControllerBar(clip.name, isSelected || isMultiSelected)
            // Build 217: the Null bar is the reference's PINK capsule
            // (#F9326A) with the null glyph.
            // Build 218: Camera takes the reference's flat indigo #6366F1
            // capsule (it was the quiet hollow bar before).
            LayerType.NULL -> CapsuleBar(com.example.ui.theme.RefNullPink, clip.name, isSelected, icon = { BarTypeIcon("null") })
            LayerType.CAMERA -> CapsuleBar(ClipCameraIndigo, clip.name, isSelected, icon = { BarTypeIcon("camera") })
            // Build 262: the 3D Space bar — deep violet capsule with the cube glyph.
            LayerType.SPACE3D -> CapsuleBar(ClipSpaceViolet, clip.name, isSelected, icon = { BarTypeIcon("space") })

            // No other LayerType exists today; the catch-all takes the reference's
            // indigo — the adjustment / effect-layer colour of the mapping.
            else -> CapsuleBar(NeonBarIndigo, clip.name, isSelected)
        }
        // Build 215: the build-187 selected-VIDEO gradient wash is gone — selection
        // is the white edge alone (Sarowar's build-213 marking), on every bar alike.
        // Video / photo / audio lanes show a picture strip or waveform, so the layer name goes on a small chip.
        if (!inChain && (clip.type == LayerType.VIDEO || clip.type == LayerType.AUDIO) && clip.name.isNotBlank()) {
            Text(
                text = clip.name,
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 3.dp, top = 2.dp, end = 30.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
        // A layer inside a real Group: a thin edge at its start, in the Group's
        // colour (build 218: teal, following the Group bar's new colour).
        if (!inChain && clip.groupId != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(3.dp)
                    .background(ClipGroupTeal)
            )
        }

        // Video with sound: waveform along the bottom of the frames (no file name / path text).
        if (!inChain && clip.type == LayerType.VIDEO && clip.uri != null && !clip.isImage) {
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
        if (!inChain && isSelected && clip.keyframes.isNotEmpty()) {
            val times = remember(clip.keyframes) { clip.keyframeTimes() }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val half = 6.dp.toPx()
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
                    drawPath(d, AmTextDim, style = Stroke(width = 3.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
                    drawPath(d, Color.White)
                }
            }
        }
        // Build 217 (reference): the bar's right end carries the drag
        // handle (three white lines, 18 x 1.6 dp, 55% opacity) on every
        // bar. Build 223 (Sarowar's order): the selected bar's in-bar
        // delete x is REMOVED — deletion lives in the selected header's
        // trash and the grid/More actions. The reorder drag itself is
        // unchanged (long-press drag anywhere on the bar).
        if (!isChain) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (inChain && (isSelected || chainSectionSelected)) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .bouncyClickable(pressedScale = 0.9f, onClick = onDeleteSelf)
                            .testTag("bar_delete_x"),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .background(
                                    Color.Black.copy(alpha = 0.35f),
                                    RoundedCornerShape(4.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(Modifier.size(8.dp)) {
                                val w = 1.5.dp.toPx()
                                val m = w / 2f
                                drawLine(Color.White, Offset(m, m), Offset(size.width - m, size.height - m), w, StrokeCap.Round)
                                drawLine(Color.White, Offset(size.width - m, m), Offset(m, size.height - m), w, StrokeCap.Round)
                            }
                        }
                    }
                }
                if (!inChain) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.padding(horizontal = 3.dp)
                    ) {
                        repeat(3) {
                            Box(
                                Modifier
                                    .width(14.dp)
                                    .height(1.3.dp)
                                    .background(Color.White.copy(alpha = 0.55f), RoundedCornerShape(1.dp))
                            )
                        }
                    }
                }
            }
        }
      } // end capsule bar
      if (!isChain && !inChain && clip.effects.isNotEmpty()) {
          Box(
              modifier = Modifier
                  .align(Alignment.TopStart)
                  .offset(x = 1.5.dp, y = (-1.5).dp)
                  .size(14.dp)
                  .background(com.example.ui.theme.RefFxBadge, CircleShape)
                  .border(1.2.dp, AmBase, CircleShape)
                  .testTag("bar_fx_badge"),
              contentAlignment = Alignment.Center
          ) {
              Text(
                  "fx", color = Color.White, fontSize = 8.sp,
                  fontWeight = FontWeight.Bold,
                  fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                  maxLines = 1
              )
          }
      }
        // Build 187: the white circle BADGE at the bar's right end, dark bold number =
        // the layer's number in the displayed stack. Part of the bar rendering, NOT a
        // button: it carries no pointer input, so taps/drags pass through to the bar.
        // Normally it STRADDLES the bar/track boundary like the references; it clamps
        // fully inside the bar when the clip is SELECTED (the right trim handle sits
        // exactly at the bar's end and must stay clear) or too short to straddle safely
        // (width < 56 dp — badge + label + trim clearance no longer fit the straddle).
        // Build 214: the bar-end number badge is gone — the selected lane is marked by its white edge.
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
 * Build 215 — a clip's bar body in Sarowar's neon-bars reference: a SOLID vivid
 * clip-type colour with the shared white crosshatch texture over it, and the layer
 * name in dark bold on top. (Build 187's flat capsule; its selected-state gradient
 * into a lighter shade is superseded — a selected bar keeps the solid colour and
 * is marked by the white edge alone, Sarowar's build-213 marking.) The [selected]
 * parameter is kept for the call sites; the fill no longer depends on it.
 */
@Composable
private fun CapsuleBar(
    color: Color,
    label: String? = null,
    selected: Boolean = false,
    /** Build 218: chain children label at 12.5 sp (the reference). */
    fontSize: androidx.compose.ui.unit.TextUnit = 11.sp,
    /** Build 218: chain children end padding (no drag handle to clear). */
    endPad: Dp = 30.dp,
    icon: (@Composable () -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.SolidColor(color)),
        contentAlignment = Alignment.CenterStart
    ) {
        if (!label.isNullOrBlank()) {
            Row(
                modifier = Modifier.padding(start = 8.dp, end = endPad),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    icon()
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    text = label,
                    color = Color.White,
                    fontSize = fontSize,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** The reference's small white type glyphs for the capsule bars (13 dp). */
@Composable
private fun BarTypeIcon(kind: String) {
    val c = Color.White
    Canvas(Modifier.size(13.dp)) {
        val u = size.width / 24f
        when (kind) {
            "text" -> {
                val w = 2.3f * u
                drawLine(c, Offset(5f * u, 5f * u), Offset(19f * u, 5f * u), w, StrokeCap.Round)
                drawLine(c, Offset(12f * u, 5f * u), Offset(12f * u, 19f * u), w, StrokeCap.Round)
                drawLine(c, Offset(9f * u, 19f * u), Offset(15f * u, 19f * u), w, StrokeCap.Round)
            }
            "shape" -> {
                drawCircle(c.copy(alpha = 0.65f), radius = 5f * u, center = Offset(9.5f * u, 9.5f * u))
                drawRoundRect(
                    c, topLeft = Offset(9.5f * u, 9.5f * u), size = Size(10.5f * u, 10.5f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f * u)
                )
            }
            "fx" -> {
                fun star(cx: Float, cy: Float, r: Float, col: Color) {
                    val path = Path()
                    val pts = listOf(
                        0f to -1f, 0.22f to -0.22f, 1f to 0f, 0.22f to 0.22f,
                        0f to 1f, -0.22f to 0.22f, -1f to 0f, -0.22f to -0.22f
                    )
                    pts.forEachIndexed { i, pt ->
                        val x = cx + pt.first * r; val y = cy + pt.second * r
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close(); drawPath(path, col)
                }
                star(8.5f * u, 8.5f * u, 5.5f * u, c.copy(alpha = 0.55f))
                star(15.5f * u, 15.5f * u, 7f * u, c)
            }
            "group" -> {
                drawRoundRect(
                    c.copy(alpha = 0.55f), topLeft = Offset(4f * u, 4f * u),
                    size = Size(13f * u, 13f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * u),
                    style = Stroke(1.8f * u)
                )
                drawRoundRect(
                    c, topLeft = Offset(8f * u, 8f * u), size = Size(12f * u, 12f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * u),
                    style = Stroke(1.8f * u)
                )
            }
            "null" -> {
                drawRoundRect(
                    c, topLeft = Offset(7f * u, 7f * u), size = Size(10f * u, 10f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f * u)
                )
                listOf(4f to 4f, 16.5f to 4f, 4f to 16.5f, 16.5f to 16.5f).forEach { pt ->
                    drawRoundRect(
                        com.example.ui.theme.RefNullPink, topLeft = Offset(pt.first * u, pt.second * u),
                        size = Size(3.5f * u, 3.5f * u),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f * u),
                        style = Stroke(1.3f * u)
                    )
                }
            }
            // Build 218 kinds (the reference's per-type glyphs, drawn in the
            // same 24-unit hand style): sticker = smiley, camera = video
            // camera, vector = node polyline, drawing = freehand scribble,
            // video = film frame, music = note, chain = link.
            "sticker" -> {
                drawCircle(c, radius = 7.5f * u, center = Offset(12f * u, 12f * u), style = Stroke(1.8f * u))
                drawCircle(c, radius = 1.1f * u, center = Offset(9.3f * u, 10f * u))
                drawCircle(c, radius = 1.1f * u, center = Offset(14.7f * u, 10f * u))
                val smile = Path().apply {
                    moveTo(8.8f * u, 13.8f * u)
                    quadraticBezierTo(12f * u, 16.6f * u, 15.2f * u, 13.8f * u)
                }
                drawPath(smile, c, style = Stroke(1.8f * u, cap = StrokeCap.Round))
            }
            "space" -> {
                // Build 262: a small isometric cube (hexagon + the three centre edges).
                val cube = Path().apply {
                    moveTo(12f * u, 3.5f * u); lineTo(20f * u, 7.5f * u); lineTo(20f * u, 16f * u)
                    lineTo(12f * u, 20f * u); lineTo(4f * u, 16f * u); lineTo(4f * u, 7.5f * u); close()
                }
                drawPath(cube, c, style = Stroke(1.7f * u))
                drawLine(c, Offset(4f * u, 7.5f * u), Offset(12f * u, 11.5f * u), 1.7f * u)
                drawLine(c, Offset(20f * u, 7.5f * u), Offset(12f * u, 11.5f * u), 1.7f * u)
                drawLine(c, Offset(12f * u, 11.5f * u), Offset(12f * u, 20f * u), 1.7f * u)
            }
            "camera" -> {
                drawRoundRect(
                    c, topLeft = Offset(3.5f * u, 8f * u), size = Size(11.5f * u, 8.5f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * u)
                )
                val tri = Path().apply {
                    moveTo(15f * u, 11f * u); lineTo(20.5f * u, 8.5f * u)
                    lineTo(20.5f * u, 16f * u); lineTo(15f * u, 13.5f * u); close()
                }
                drawPath(tri, c)
            }
            "vector" -> {
                val pts = listOf(5f to 18f, 9.5f to 7f, 14.5f to 14f, 19f to 5.5f)
                for (i in 0 until pts.size - 1) {
                    drawLine(
                        c, Offset(pts[i].first * u, pts[i].second * u),
                        Offset(pts[i + 1].first * u, pts[i + 1].second * u),
                        1.7f * u, StrokeCap.Round
                    )
                }
                pts.forEach { pt -> drawCircle(c, radius = 1.9f * u, center = Offset(pt.first * u, pt.second * u)) }
            }
            "drawing" -> {
                val scribble = Path().apply {
                    moveTo(4f * u, 15f * u)
                    cubicTo(7f * u, 6f * u, 10f * u, 19f * u, 13f * u, 10f * u)
                    cubicTo(15f * u, 5f * u, 17.5f * u, 12f * u, 20f * u, 9f * u)
                }
                drawPath(scribble, c, style = Stroke(1.9f * u, cap = StrokeCap.Round))
            }
            "video" -> {
                drawRoundRect(
                    c, topLeft = Offset(4f * u, 5.5f * u), size = Size(16f * u, 13f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f * u),
                    style = Stroke(1.8f * u)
                )
                drawLine(c, Offset(9f * u, 5.5f * u), Offset(9f * u, 18.5f * u), 1.4f * u)
                drawLine(c, Offset(15f * u, 5.5f * u), Offset(15f * u, 18.5f * u), 1.4f * u)
            }
            "music" -> {
                drawCircle(c, radius = 2.6f * u, center = Offset(8.5f * u, 17f * u))
                drawCircle(c, radius = 2.6f * u, center = Offset(17.5f * u, 15f * u))
                drawLine(c, Offset(11.1f * u, 17f * u), Offset(11.1f * u, 6.5f * u), 1.8f * u, StrokeCap.Round)
                drawLine(c, Offset(20.1f * u, 15f * u), Offset(20.1f * u, 4.5f * u), 1.8f * u, StrokeCap.Round)
                drawLine(c, Offset(11.1f * u, 6.5f * u), Offset(20.1f * u, 4.5f * u), 2.2f * u, StrokeCap.Round)
            }
            "chain" -> {
                drawRoundRect(
                    c, topLeft = Offset(3.5f * u, 9f * u), size = Size(10f * u, 6.5f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.25f * u),
                    style = Stroke(1.9f * u)
                )
                drawRoundRect(
                    c, topLeft = Offset(10.5f * u, 9f * u), size = Size(10f * u, 6.5f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.25f * u),
                    style = Stroke(1.9f * u)
                )
            }
            else -> drawCircle(c, radius = 4f * u, center = Offset(12f * u, 12f * u))
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
    val shape = RoundedCornerShape(7.dp)
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
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 9.dp, end = 26.dp)
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
                Color.White.copy(alpha = if (selected) 0.55f else 0.25f),
                RoundedCornerShape(7.dp)
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                color = AmTextDim,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 9.dp, end = 26.dp)
            )
        }
    }
}

/**
 * Build 215 — the neon bars' shared texture (Sarowar's neon-bars reference): thin
 * WHITE stripes at 28% opacity crossing BOTH ways (45° and −45°) — a crosshatch.
 * At phone scale the reference works out to a ~0.7 dp line on an ~8.6 dp period
 * (the period is the perpendicular measure, as in the CSS repeating gradient:
 * stepping the 45° lines along x by period × √2 yields that perpendicular period).
 * Drawn behind the bar's content (labels, the audio waveform), and clipped to the
 * bar's rounded shape by the bar's own clip. Replaces build 187's darker
 * single-lean audio stripes.
 */
internal fun DrawScope.drawNeonCrosshatch() {
    val ink = Color.White.copy(alpha = 0.28f)
    val w = 0.7.dp.toPx()
    val step = 8.6.dp.toPx() * 1.41421356f
    var x = -size.height
    while (x < size.width + size.height) {
        drawLine(ink, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = w)
        x += step
    }
    var x2 = -size.height
    while (x2 < size.width + size.height) {
        drawLine(ink, Offset(x2, 0f), Offset(x2 + size.height, size.height), strokeWidth = w)
        x2 += step
    }
}

private fun Modifier.neonCrosshatch(): Modifier = drawBehind { drawNeonCrosshatch() }

private fun lerpColor(a: Color, b: Color, t: Float): Color =
    androidx.compose.ui.graphics.lerp(a, b, t)

/** Standard Compose/Android fling friction (1f = default). Higher = shorter glide. */
private const val FLING_FRICTION = 1f
/** Min horizontal velocity (px/s) to start a coasting fling (Android's min fling is ~50dp/s). */
private const val FLING_VELOCITY_THRESHOLD_PX = 100f
/** Stop the decay once velocity falls below this (ms/s). */
private const val FLING_ABS_VELOCITY_THRESHOLD = 20f

private val ThumbTileWidth = 34.dp
private val ThumbTileHeight = 34.dp

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

    Box(modifier = Modifier.fillMaxSize().background(if (clip.isImage) NeonBarCyan else ClipFilmBody)) {
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
        height = laneHeightFor(clip.type) + 6.dp,
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
