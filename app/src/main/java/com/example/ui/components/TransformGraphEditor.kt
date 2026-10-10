package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.BezierHandles
import com.example.model.EasingType
import com.example.model.TransformKeyframe
import com.example.model.applyKeyframeCurve
import com.example.model.easingHandles
import com.example.model.interpolateKeyframes
import com.example.ui.util.bouncyClickable
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private val GraphBg = Color(0xFF1A1D24)
private val GridColor = Color(0xFF2A2F3A)
private val CurveColor = Color.White
private val CurveDim = Color(0xFF8E99AF)
private val KeyframeColor = Color(0xFFE91E63)
private val KeyframeSelected = Color(0xFFFFC107)
private val HandleColor = Color(0xFF4DD0E1)
private val PlayheadLine = Color(0xFFB0B8C8)
private val PresetBg = Color(0xFF252A35)
private val PresetSelected = Color(0xFF3A4150)
private val LabelColor = Color(0xFF8E99AF)

/** Preset curves offered under the graph, in display order. */
private val PresetOrder = listOf(
    EasingType.LINEAR,
    EasingType.EASE_IN,
    EasingType.EASE_OUT,
    EasingType.EASE_IN_OUT,
    EasingType.EASE_IN_CUBIC,
    EasingType.EASE_OUT_CUBIC,
    EasingType.EASE_IN_OUT_CUBIC,
    EasingType.HOLD
)

private const val TIME_PAD_DP = 16f
private const val HIT_RADIUS_DP = 24f

/** Everything the gesture handlers need, refreshed on every recomposition (gestures never restart mid-drag). */
private class GraphSnapshot(
    val sorted: List<TransformKeyframe>,
    val t0: Long,
    val t1: Long,
    val v0: Float,
    val v1: Float,
    val activeSeg: Int
)

/** Maps time/value to canvas pixels and back. */
private class GraphGeom(
    val w: Float,
    val h: Float,
    val padX: Float,
    val t0: Long,
    val t1: Long,
    val v0: Float,
    val v1: Float
) {
    private val tSpan get() = (t1 - t0).coerceAtLeast(1L).toFloat()
    private val vSpan get() = (v1 - v0).coerceAtLeast(1e-4f)
    private val usableW get() = (w - 2f * padX).coerceAtLeast(1f)

    fun x(t: Long) = padX + (t - t0) / tSpan * usableW
    fun y(v: Float) = (v1 - v) / vSpan * h
    fun t(x: Float): Long = t0 + (((x - padX) / usableW) * tSpan).toLong()
    fun v(y: Float): Float = v1 - (y / h) * vSpan
}

private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = ax - bx
    val dy = ay - by
    return sqrt(dx * dx + dy * dy)
}

/** Positions (time, value) of the two bezier handles of the segment that starts at [a] and ends at [b]. */
private fun handlePoints(a: TransformKeyframe, b: TransformKeyframe): Pair<Pair<Float, Float>, Pair<Float, Float>> {
    val c = a.curve()
    val dt = (b.timeMs - a.timeMs).toFloat()
    val dv = b.value - a.value
    return Pair(
        Pair(a.timeMs + c.x1 * dt, a.value + c.y1 * dv),
        Pair(a.timeMs + c.x2 * dt, a.value + c.y2 * dv)
    )
}

private fun fmtValue(v: Float, span: Float): String =
    if (span >= 20f) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.2f", v)

private fun fmtTime(ms: Long): String = String.format(Locale.US, "%.2fs", ms / 1000f)

/**
 * Graph editor for transform animation (Alight Motion / After Effects style):
 * - X = time over the layer, Y = property value (auto-fitted to the keyframes)
 * - Tap a keyframe to select it, drag it to move it (time + value); tap empty space to move the playhead;
 *   long-press empty space to add a keyframe
 * - The selected segment shows two cyan bezier handles: drag them to shape the curve freely
 * - Preset curves under the graph apply to the selected segment only
 *
 * [keyframes] use ABSOLUTE timeline times.
 */
@Composable
fun TransformGraphEditor(
    keyframes: List<TransformKeyframe>,
    propertyLabel: String,
    valueMin: Float,
    valueMax: Float,
    currentValue: Float,
    timeStartMs: Long,
    timeEndMs: Long,
    playheadMs: Long,
    onAddKeyframe: (timeMs: Long, value: Float) -> Unit,
    onMoveKeyframe: (oldTimeMs: Long, newTimeMs: Long, newValue: Float) -> Unit,
    onRemoveKeyframe: (timeMs: Long) -> Unit,
    onSetPreset: (timeMs: Long, easing: EasingType) -> Unit,
    onSetCurve: (timeMs: Long, handles: BezierHandles) -> Unit,
    onApplyPresetToAll: (EasingType) -> Unit,
    onEditStart: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    graphHeight: androidx.compose.ui.unit.Dp = 170.dp
) {
    val sorted = remember(keyframes) { keyframes.sortedBy { it.timeMs } }

    var selectedIdx by remember(propertyLabel) { mutableIntStateOf(-1) }
    var pendingSelectTime by remember(propertyLabel) { mutableStateOf<Long?>(null) }
    var frozenRange by remember(propertyLabel) { mutableStateOf<Pair<Float, Float>?>(null) }

    // Keep the selection valid when keyframes are added / removed.
    LaunchedEffect(sorted) {
        val pending = pendingSelectTime
        if (pending != null) {
            val i = sorted.indexOfFirst { abs(it.timeMs - pending) <= 60L }
            if (i >= 0) {
                selectedIdx = i
                pendingSelectTime = null
            }
        }
        if (selectedIdx >= sorted.size) selectedIdx = sorted.lastIndex
    }

    // The segment being edited: starts at the selected keyframe (or ends there if it is the last one);
    // with nothing selected it is the segment under the playhead.
    val activeSeg: Int = run {
        if (sorted.size < 2) -1
        else if (selectedIdx in sorted.indices) {
            if (selectedIdx < sorted.lastIndex) selectedIdx else selectedIdx - 1
        } else {
            val before = sorted.indexOfLast { it.timeMs <= playheadMs + 40L }
            before.coerceIn(0, sorted.size - 2)
        }
    }

    // Auto-fit value range (frozen while a drag is in progress so the picture does not jump).
    val autoRange: Pair<Float, Float> = run {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        sorted.forEach { lo = min(lo, it.value); hi = max(hi, it.value) }
        if (sorted.isEmpty()) {
            lo = currentValue
            hi = currentValue
        }
        if (activeSeg >= 0) {
            // headroom so overshoot curves stay visible
            val a = sorted[activeSeg]
            val dv = sorted[activeSeg + 1].value - a.value
            for (f in floatArrayOf(-0.35f, 1.35f)) {
                val v = a.value + dv * f
                lo = min(lo, v)
                hi = max(hi, v)
            }
        }
        val full = (valueMax - valueMin).coerceAtLeast(0.001f)
        val minSpan = full * 0.06f
        if (hi - lo < minSpan) {
            val c = (hi + lo) / 2f
            lo = c - minSpan / 2f
            hi = c + minSpan / 2f
        }
        val pad = (hi - lo) * 0.12f
        Pair(lo - pad, hi + pad)
    }
    val range = frozenRange ?: autoRange
    val tStart = timeStartMs
    val tEnd = max(timeEndMs, timeStartMs + 1L)

    val snap by rememberUpdatedState(GraphSnapshot(sorted, tStart, tEnd, range.first, range.second, activeSeg))
    val latestAdd by rememberUpdatedState(onAddKeyframe)
    val latestMove by rememberUpdatedState(onMoveKeyframe)
    val latestCurve by rememberUpdatedState(onSetCurve)
    val latestEditStart by rememberUpdatedState(onEditStart)
    val latestSeek by rememberUpdatedState(onSeek)
    val latestMin by rememberUpdatedState(valueMin)
    val latestMax by rememberUpdatedState(valueMax)

    val nearPlayhead = sorted.indexOfFirst { abs(it.timeMs - playheadMs) <= 40L }
    val active = if (activeSeg >= 0) sorted[activeSeg] else null
    val activeCurve = active?.curve()
    val selectedKf = sorted.getOrNull(selectedIdx)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(GraphBg)
            .padding(8.dp)
            .testTag("transform_graph")
    ) {
        // ---- action row ---------------------------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            GraphButton("◀", enabled = sorted.any { it.timeMs < playheadMs - 40L }) {
                sorted.lastOrNull { it.timeMs < playheadMs - 40L }?.let {
                    onSeek(it.timeMs)
                    selectedIdx = sorted.indexOf(it)
                }
            }
            GraphButton(
                text = if (nearPlayhead >= 0) "◆ Remove" else "◆ Add",
                highlighted = nearPlayhead >= 0
            ) {
                if (nearPlayhead >= 0) {
                    onRemoveKeyframe(sorted[nearPlayhead].timeMs)
                } else {
                    val t = playheadMs.coerceIn(tStart, tEnd)
                    pendingSelectTime = t
                    val v = if (sorted.isEmpty()) currentValue else interpolateKeyframes(sorted, t)
                    onAddKeyframe(t, v.coerceIn(valueMin, valueMax))
                }
            }
            GraphButton("▶", enabled = sorted.any { it.timeMs > playheadMs + 40L }) {
                sorted.firstOrNull { it.timeMs > playheadMs + 40L }?.let {
                    onSeek(it.timeMs)
                    selectedIdx = sorted.indexOf(it)
                }
            }
            GraphButton("Delete", enabled = selectedKf != null) {
                selectedKf?.let { onRemoveKeyframe(it.timeMs) }
            }
            Box(modifier = Modifier.weight(1f))
            Text(
                text = when {
                    selectedKf != null ->
                        "${fmtTime(selectedKf.timeMs - tStart)}  ${fmtValue(selectedKf.value, range.second - range.first)}"
                    sorted.isEmpty() -> "No keyframes"
                    else -> propertyLabel
                },
                color = LabelColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // ---- graph ----------------------------------------------------------------------------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(graphHeight)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF2C3240), RoundedCornerShape(8.dp))
                .clipToBounds()
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    // Taps: select keyframe / move playhead / long-press adds a keyframe.
                    .pointerInput(Unit) {
                        val radius = HIT_RADIUS_DP.dp.toPx()
                        val pad = TIME_PAD_DP.dp.toPx()
                        detectTapGestures(
                            onTap = { off ->
                                val s = snap
                                val g = GraphGeom(size.width.toFloat(), size.height.toFloat(), pad, s.t0, s.t1, s.v0, s.v1)
                                val hit = s.sorted.indices.minByOrNull {
                                    dist(off.x, off.y, g.x(s.sorted[it].timeMs), g.y(s.sorted[it].value))
                                }?.takeIf {
                                    dist(off.x, off.y, g.x(s.sorted[it].timeMs), g.y(s.sorted[it].value)) <= radius
                                }
                                if (hit != null) {
                                    selectedIdx = hit
                                    latestSeek(s.sorted[hit].timeMs)
                                } else {
                                    selectedIdx = -1
                                    latestSeek(g.t(off.x).coerceIn(s.t0, s.t1))
                                }
                            },
                            onLongPress = { off ->
                                val s = snap
                                val g = GraphGeom(size.width.toFloat(), size.height.toFloat(), pad, s.t0, s.t1, s.v0, s.v1)
                                val t = g.t(off.x).coerceIn(s.t0, s.t1)
                                val v = g.v(off.y).coerceIn(latestMin, latestMax)
                                pendingSelectTime = t
                                latestAdd(t, v)
                            }
                        )
                    }
                    // Drags: bezier handle / keyframe / playhead scrub. Keyed on Unit so a drag is never restarted.
                    .pointerInput(Unit) {
                        val radius = HIT_RADIUS_DP.dp.toPx()
                        val pad = TIME_PAD_DP.dp.toPx()
                        // 0 = none, 1 = keyframe, 2 = handle 1, 3 = handle 2, 4 = playhead scrub
                        var mode = 0
                        var idx = -1
                        detectDragGestures(
                            onDragStart = { off ->
                                val s = snap
                                val g = GraphGeom(size.width.toFloat(), size.height.toFloat(), pad, s.t0, s.t1, s.v0, s.v1)
                                mode = 0
                                idx = -1
                                var best = Float.MAX_VALUE
                                if (s.activeSeg >= 0) {
                                    val a = s.sorted[s.activeSeg]
                                    val b = s.sorted[s.activeSeg + 1]
                                    if (a.easing != EasingType.HOLD) {
                                        val (h1, h2) = handlePoints(a, b)
                                        val d1 = dist(off.x, off.y, g.x(h1.first.toLong()), g.y(h1.second))
                                        val d2 = dist(off.x, off.y, g.x(h2.first.toLong()), g.y(h2.second))
                                        if (d1 <= radius && d1 < best) { best = d1; mode = 2 }
                                        if (d2 <= radius && d2 < best) { best = d2; mode = 3 }
                                    }
                                }
                                s.sorted.forEachIndexed { i, k ->
                                    val d = dist(off.x, off.y, g.x(k.timeMs), g.y(k.value))
                                    if (d <= radius && d <= best) { best = d; mode = 1; idx = i }
                                }
                                if (mode == 0) {
                                    mode = 4
                                    latestSeek(g.t(off.x).coerceIn(s.t0, s.t1))
                                } else {
                                    latestEditStart()
                                    frozenRange = Pair(s.v0, s.v1)
                                    if (mode == 1) selectedIdx = idx
                                    if (mode == 2 || mode == 3) selectedIdx = s.activeSeg
                                }
                            },
                            onDrag = { change, _ ->
                                val s = snap
                                val g = GraphGeom(size.width.toFloat(), size.height.toFloat(), pad, s.t0, s.t1, s.v0, s.v1)
                                when (mode) {
                                    4 -> {
                                        change.consume()
                                        latestSeek(g.t(change.position.x).coerceIn(s.t0, s.t1))
                                    }
                                    1 -> {
                                        change.consume()
                                        val k = s.sorted.getOrNull(idx) ?: return@detectDragGestures
                                        val nt = g.t(change.position.x).coerceIn(s.t0, s.t1)
                                        val nv = g.v(change.position.y).coerceIn(latestMin, latestMax)
                                        latestMove(k.timeMs, nt, nv)
                                    }
                                    2, 3 -> {
                                        change.consume()
                                        val seg = s.activeSeg
                                        if (seg < 0) return@detectDragGestures
                                        val a = s.sorted[seg]
                                        val b = s.sorted[seg + 1]
                                        val dt = (b.timeMs - a.timeMs).toFloat().coerceAtLeast(1f)
                                        val dv = b.value - a.value
                                        val cur = a.curve()
                                        val fx = ((g.t(change.position.x) - a.timeMs) / dt).coerceIn(0f, 1f)
                                        val rawV = g.v(change.position.y)
                                        // Flat segment (equal values): vertical handle position is meaningless.
                                        val fy = if (abs(dv) < 1e-4f) null else (rawV - a.value) / dv
                                        val next = if (mode == 2) {
                                            BezierHandles(fx, fy ?: cur.y1, cur.x2, cur.y2)
                                        } else {
                                            BezierHandles(cur.x1, cur.y1, fx, fy ?: cur.y2)
                                        }
                                        latestCurve(a.timeMs, next)
                                    }
                                }
                            },
                            onDragEnd = { mode = 0; idx = -1; frozenRange = null },
                            onDragCancel = { mode = 0; idx = -1; frozenRange = null }
                        )
                    }
            ) {
                val g = GraphGeom(size.width, size.height, TIME_PAD_DP.dp.toPx(), tStart, tEnd, range.first, range.second)
                val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))

                // grid
                for (i in 0..4) {
                    val x = size.width * i / 4f
                    drawLine(GridColor, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx(), pathEffect = dash)
                    val y = size.height * i / 4f
                    drawLine(GridColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = dash)
                }

                // curve
                if (sorted.isNotEmpty()) {
                    val path = Path()
                    val first = sorted.first()
                    path.moveTo(0f, g.y(first.value))
                    path.lineTo(g.x(first.timeMs), g.y(first.value))
                    for (i in 0 until sorted.size - 1) {
                        val a = sorted[i]
                        val b = sorted[i + 1]
                        if (a.easing == EasingType.HOLD) {
                            path.lineTo(g.x(b.timeMs), g.y(a.value))
                            path.lineTo(g.x(b.timeMs), g.y(b.value))
                        } else {
                            val steps = 40
                            for (s in 1..steps) {
                                val tn = s / steps.toFloat()
                                val eased = applyKeyframeCurve(tn, a)
                                val tm = a.timeMs + ((b.timeMs - a.timeMs) * tn).toLong()
                                path.lineTo(g.x(tm), g.y(a.value + (b.value - a.value) * eased))
                            }
                        }
                    }
                    val last = sorted.last()
                    path.lineTo(size.width, g.y(last.value))
                    drawPath(path, CurveDim, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

                    // the segment being edited, brighter
                    if (activeSeg >= 0) {
                        val a = sorted[activeSeg]
                        val b = sorted[activeSeg + 1]
                        val seg = Path()
                        seg.moveTo(g.x(a.timeMs), g.y(a.value))
                        if (a.easing == EasingType.HOLD) {
                            seg.lineTo(g.x(b.timeMs), g.y(a.value))
                            seg.lineTo(g.x(b.timeMs), g.y(b.value))
                        } else {
                            val steps = 48
                            for (s in 1..steps) {
                                val tn = s / steps.toFloat()
                                val eased = applyKeyframeCurve(tn, a)
                                val tm = a.timeMs + ((b.timeMs - a.timeMs) * tn).toLong()
                                seg.lineTo(g.x(tm), g.y(a.value + (b.value - a.value) * eased))
                            }
                        }
                        drawPath(seg, CurveColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                    }
                } else {
                    val y = g.y(currentValue)
                    drawLine(CurveDim, Offset(0f, y), Offset(size.width, y), 2.dp.toPx(), pathEffect = dash)
                }

                // playhead + value dot
                val phT = playheadMs.coerceIn(tStart, tEnd)
                val phX = g.x(phT)
                drawLine(PlayheadLine, Offset(phX, 0f), Offset(phX, size.height), 1.5.dp.toPx())
                val phV = if (sorted.isEmpty()) currentValue else interpolateKeyframes(sorted, phT)
                drawCircle(Color.White, 3.5.dp.toPx(), Offset(phX, g.y(phV)))

                // bezier handles of the active segment
                if (activeSeg >= 0 && sorted[activeSeg].easing != EasingType.HOLD) {
                    val a = sorted[activeSeg]
                    val b = sorted[activeSeg + 1]
                    val (h1, h2) = handlePoints(a, b)
                    val p1 = Offset(g.x(h1.first.toLong()), g.y(h1.second))
                    val p2 = Offset(g.x(h2.first.toLong()), g.y(h2.second))
                    val pa = Offset(g.x(a.timeMs), g.y(a.value))
                    val pb = Offset(g.x(b.timeMs), g.y(b.value))
                    drawLine(HandleColor, pa, p1, 1.5.dp.toPx())
                    drawLine(HandleColor, pb, p2, 1.5.dp.toPx())
                    drawCircle(Color.White, 8.dp.toPx(), p1)
                    drawCircle(HandleColor, 6.dp.toPx(), p1)
                    drawCircle(Color.White, 8.dp.toPx(), p2)
                    drawCircle(HandleColor, 6.dp.toPx(), p2)
                }

                // keyframes
                sorted.forEachIndexed { i, kf ->
                    val c = Offset(g.x(kf.timeMs), g.y(kf.value))
                    val sel = i == selectedIdx
                    drawDiamond(c, if (sel) 10.dp.toPx() else 8.dp.toPx(), Color.White)
                    drawDiamond(c, if (sel) 7.5.dp.toPx() else 6.dp.toPx(), if (sel) KeyframeSelected else KeyframeColor)
                }
            }

            // axis labels (do not intercept touches)
            Text(
                text = fmtValue(range.second, range.second - range.first),
                color = LabelColor, fontSize = 9.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 2.dp)
            )
            Text(
                text = fmtValue(range.first, range.second - range.first),
                color = LabelColor, fontSize = 9.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 2.dp)
            )
            Text(
                text = fmtTime(tEnd - tStart),
                color = LabelColor, fontSize = 9.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 2.dp)
            )
            if (sorted.size < 2) {
                Text(
                    text = if (sorted.isEmpty()) "Long-press to add a keyframe" else "Add a second keyframe to shape the curve",
                    color = LabelColor, fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        // ---- presets --------------------------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val enabled = active != null
            PresetOrder.forEach { type ->
                val selected = active?.easing == type
                PresetChip(
                    handles = easingHandles(type),
                    hold = type == EasingType.HOLD,
                    selected = selected,
                    enabled = enabled
                ) {
                    active?.let { onSetPreset(it.timeMs, type) }
                }
            }
            // Custom curve (shown selected once the handles were dragged)
            PresetChip(
                handles = if (active?.easing == EasingType.CUSTOM && activeCurve != null) activeCurve else BezierHandles(0.25f, 0.9f, 0.75f, 0.1f),
                hold = false,
                selected = active?.easing == EasingType.CUSTOM,
                enabled = false,
                onClick = {}
            )
            GraphButton(
                text = "All",
                enabled = active != null && active.easing != EasingType.CUSTOM
            ) {
                active?.let { onApplyPresetToAll(it.easing) }
            }
        }
        if (active != null && activeCurve != null && active.easing != EasingType.HOLD) {
            Text(
                text = String.format(
                    Locale.US, "cubic-bezier(%.2f, %.2f, %.2f, %.2f)",
                    activeCurve.x1, activeCurve.y1, activeCurve.x2, activeCurve.y2
                ),
                color = LabelColor,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

private fun DrawScope.drawDiamond(c: Offset, r: Float, color: Color) {
    val p = Path().apply {
        moveTo(c.x, c.y - r)
        lineTo(c.x + r, c.y)
        lineTo(c.x, c.y + r)
        lineTo(c.x - r, c.y)
        close()
    }
    drawPath(p, color)
}

@Composable
private fun GraphButton(
    text: String,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.35f)
            .clip(RoundedCornerShape(6.dp))
            .background(if (highlighted) KeyframeColor else PresetBg)
            .border(1.dp, Color(0xFF2C3240), RoundedCornerShape(6.dp))
            .bouncyClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PresetChip(
    handles: BezierHandles,
    hold: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .alpha(if (enabled || selected) 1f else 0.45f)
            .size(44.dp, 30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) PresetSelected else PresetBg)
            .border(
                1.dp,
                if (selected) Color(0xFF5A6578) else Color(0xFF2C3240),
                RoundedCornerShape(6.dp)
            )
            .bouncyClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(28.dp, 16.dp)) {
            val w = size.width
            val h = size.height
            val path = Path()
            if (hold) {
                path.moveTo(0f, h * 0.85f)
                path.lineTo(w * 0.55f, h * 0.85f)
                path.lineTo(w * 0.55f, h * 0.15f)
                path.lineTo(w, h * 0.15f)
            } else {
                path.moveTo(0f, h)
                path.cubicTo(
                    w * handles.x1, h - h * handles.y1,
                    w * handles.x2, h - h * handles.y2,
                    w, 0f
                )
            }
            drawPath(path, Color.White, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round))
            drawCircle(KeyframeColor, 2.5.dp.toPx(), Offset(0f, if (hold) h * 0.85f else h))
            drawCircle(KeyframeColor, 2.5.dp.toPx(), Offset(w, if (hold) h * 0.15f else 0f))
        }
    }
}

/** Which transform property is currently being graphed. */
enum class GraphProperty(
    val key: String,
    val label: String,
    val min: Float,
    val max: Float,
    val defaultValue: Float
) {
    HORIZONTAL("transformX", "X", -500f, 500f, 0f),
    VERTICAL("transformY", "Y", -500f, 500f, 0f),
    SCALE("scalePercent", "Scale", 10f, 400f, 100f),
    ROTATION("rotationDeg", "Rotate", -180f, 180f, 0f),
    PIVOT_X("pivotX", "Pivot X", 0f, 1f, 0.5f),
    PIVOT_Y("pivotY", "Pivot Y", 0f, 1f, 0.5f),
    ROT_3D_X("rotation3dX", "3D X", -180f, 180f, 0f),
    ROT_3D_Y("rotation3dY", "3D Y", -180f, 180f, 0f),
    WARP("warpDistort", "Warp", -100f, 100f, 0f)
}
