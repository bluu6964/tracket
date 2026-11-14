package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.EditablePath
import com.example.model.PATH_OP_CLOSE
import com.example.model.PATH_OP_CUBIC
import com.example.model.PATH_OP_LINE
import com.example.model.PATH_OP_MOVE
import com.example.model.PathAnchor
import com.example.model.PathEdit
import com.example.model.PathSubpath
import com.example.model.TimelineClip
import com.example.model.depthX
import com.example.model.depthY
import com.example.model.flattenPathSegments
import com.example.model.shapeDrawScaleX
import com.example.model.shapeDrawScaleY
import com.example.ui.theme.AmInk
import com.example.ui.theme.AmTextDim
import com.example.ui.theme.AmTextFaint
import com.example.ui.theme.TextPrimary
import com.example.ui.util.bouncyClickable
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The point-based Pen tool (build 177 — Sarowar's correction: real vector drawing is
 * done WITH POINTS, like other apps; the build-166 Draw is freehand and stays as it is).
 *
 * This file holds the pen placement overlay + panel and the node-editing overlay +
 * panel. All geometry lives in the pure model ([PathEdit] / [com.example.model.PenPath]);
 * the overlays only translate touches: the pen works in frame fractions (the freehand
 * Draw's space), the node editor in the layer's stored segment units, mapped through
 * the layer's pose exactly as the shape painters draw it ([PathFrameMap]).
 *
 * Flat B/W like every editing surface: anchors are small squares, handles small
 * circles on grey lines, guides and outlines white — no colour, no glow.
 */

// ---------------------------------------------------------------------- pen placement

/**
 * Captures the pen interaction on the canvas: a tap places an anchor ([onTap] on the
 * down stroke), dragging after the tap pulls bezier handles out of it ([onTap] then
 * [onPull] per move, [onRelease] with whether a pull happened), and a tap on the FIRST
 * anchor (with at least 3 placed) asks to close the path ([onCloseTap]). A live
 * preview line follows the finger to the next point.
 */
@Composable
fun PenToolOverlay(
    anchors: List<PathAnchor>,
    closed: Boolean,
    strokeWidthDp: Float,
    onTap: (Float, Float) -> Unit,
    onPull: (Float, Float) -> Unit,
    onRelease: (Boolean) -> Unit,
    onCloseTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density
    val anchorsNow by rememberUpdatedState(anchors)
    val closedNow by rememberUpdatedState(closed)
    val tapNow by rememberUpdatedState(onTap)
    val pullNow by rememberUpdatedState(onPull)
    val releaseNow by rememberUpdatedState(onRelease)
    val closeTapNow by rememberUpdatedState(onCloseTap)
    var cursor by remember { mutableStateOf<Offset?>(null) }
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("pen_tool_overlay")
            .pointerInput(Unit) {
                val closeRadiusPx = 26.dp.toPx()
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent()
                        val first = down.changes.firstOrNull { it.pressed } ?: continue
                        val startPos = first.position
                        first.consume()
                        cursor = startPos
                        val placed = anchorsNow
                        val isClosed = closedNow
                        val fx = (startPos.x / size.width).coerceIn(0f, 1f)
                        val fy = (startPos.y / size.height).coerceIn(0f, 1f)
                        val closeHit = !isClosed && placed.size >= 3 && size.width > 0 &&
                            hypot(
                                startPos.x - placed[0].x * size.width,
                                startPos.y - placed[0].y * size.height
                            ) <= closeRadiusPx
                        if (closeHit) {
                            closeTapNow()
                            // Swallow the rest of this touch: closing places no anchor.
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.first()
                                cursor = ch.position
                                ch.consume()
                                if (!ch.pressed) break
                            }
                            cursor = null
                            continue
                        }
                        if (!isClosed) tapNow(fx, fy)
                        var pulled = false
                        var done = false
                        while (!done) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.first()
                            cursor = ch.position
                            if (ch.pressed) {
                                if (!isClosed) {
                                    if ((ch.position - startPos).getDistance() > viewConfiguration.touchSlop) pulled = true
                                    if (pulled) {
                                        pullNow(
                                            (ch.position.x / size.width).coerceIn(0f, 1f),
                                            (ch.position.y / size.height).coerceIn(0f, 1f)
                                        )
                                    }
                                }
                                ch.consume()
                            } else {
                                ch.consume()
                                done = true
                            }
                        }
                        cursor = null
                        if (!isClosed) releaseNow(pulled)
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height
        fun pt(x: Float, y: Float) = Offset(x * w, y * h)
        if (anchors.isNotEmpty()) {
            val segs = PathEdit.toSegments(EditablePath(listOf(PathSubpath(anchors, closed))))
            val path = Path()
            for (seg in segs) {
                val q = seg.pts
                when (seg.op) {
                    PATH_OP_MOVE -> if (q.size >= 2) path.moveTo(q[0] * w, q[1] * h)
                    PATH_OP_LINE -> if (q.size >= 2) path.lineTo(q[0] * w, q[1] * h)
                    PATH_OP_CUBIC -> if (q.size >= 6) path.cubicTo(
                        q[0] * w, q[1] * h, q[2] * w, q[3] * h, q[4] * w, q[5] * h
                    )
                    PATH_OP_CLOSE -> path.close()
                }
            }
            if (closed && anchors.size >= 3) drawPath(path, Color.White.copy(alpha = 0.25f))
            drawPath(
                path, Color.White,
                style = Stroke(
                    width = (strokeWidthDp * density).coerceAtLeast(1f),
                    cap = StrokeCap.Round, join = StrokeJoin.Round
                )
            )
            // Live preview: a thin line from the last anchor to the finger.
            val cur = cursor
            if (!closed && cur != null) {
                val last = anchors.last()
                drawLine(
                    Color.White.copy(alpha = 0.55f),
                    pt(last.x, last.y), cur,
                    strokeWidth = 1.5f * density
                )
            }
            // The last anchor's handles while they are being pulled out.
            val lastA = anchors.last()
            if (lastA.hasIn || lastA.hasOut) {
                val c = pt(lastA.x, lastA.y)
                val inP = if (lastA.hasIn) pt(lastA.inX!!, lastA.inY!!) else c
                val outP = if (lastA.hasOut) pt(lastA.outX!!, lastA.outY!!) else c
                drawLine(Color(0xFF9A9A9A), inP, outP, strokeWidth = 1f * density)
                if (lastA.hasIn) drawCircle(Color.White, radius = 4f * density, center = inP)
                if (lastA.hasOut) drawCircle(Color.White, radius = 4f * density, center = outP)
            }
            // Anchors: small squares; the first one gets a ring when it can close the path.
            val half = 4f * density
            anchors.forEachIndexed { i, a ->
                val c = pt(a.x, a.y)
                drawRect(
                    Color.White,
                    topLeft = Offset(c.x - half, c.y - half),
                    size = androidx.compose.ui.geometry.Size(half * 2f, half * 2f)
                )
                if (i == 0 && !closed && anchors.size >= 3) {
                    drawCircle(
                        Color.White, radius = 10f * density, center = c,
                        style = Stroke(width = 1.5f * density)
                    )
                }
            }
        }
    }
}

/** The pen panel: width, undo point, cancel / done — same place and language as the Draw panel. */
@Composable
fun PenToolPanel(
    anchorCount: Int,
    closed: Boolean,
    widthDp: Float,
    onWidth: (Float) -> Unit,
    onUndoPoint: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            "Tap to place a point — drag after a tap to bend the line. Tap the first point again to close the shape.",
            color = AmTextDim, fontSize = 12.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Width", color = TextPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp))
            Slider(
                value = widthDp,
                onValueChange = onWidth,
                valueRange = 1f..30f,
                modifier = Modifier.weight(1f).testTag("pen_width_slider")
            )
            Spacer(Modifier.width(10.dp))
            Text("${widthDp.roundToInt()}", color = AmTextDim, fontSize = 12.sp)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PenPanelButton("Undo point", enabled = anchorCount > 0, onClick = onUndoPoint)
            Spacer(Modifier.weight(1f))
            PenPanelButton("Cancel", enabled = true, onClick = onCancel)
            Spacer(Modifier.width(10.dp))
            GradientPillButton(onClick = onDone, tag = "pen_done_button") {
                Text("Done", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                closed -> "Closed shape — it will be filled"
                anchorCount == 0 -> "No points yet"
                else -> "$anchorCount points"
            },
            color = AmTextFaint, fontSize = 11.sp
        )
    }
}

// ---------------------------------------------------------------------- node editing

/**
 * Maps between a PATH layer's stored segment units and canvas pixels, with the same
 * composition the shape painters use: the outline is replayed at base = 65 dp around
 * the layer centre (frame centre + depth position), scaled per axis by the shape draw
 * scales (flips included) and rotated by the layer's 2D rotation about its centre.
 * (A layer's pivot / 3D tilt does not move a shape's painted outline — the painters
 * rotate about the centre — so it does not enter this map either.)
 */
private class PathFrameMap(clip: TimelineClip, frameW: Float, frameH: Float, density: Float) {
    private val base = 65f * density
    private val cx = frameW / 2f + clip.depthX
    private val cy = frameH / 2f + clip.depthY
    private val sx = clip.shapeDrawScaleX * (if (clip.isFlippedH) -1f else 1f)
    private val sy = clip.shapeDrawScaleY * (if (clip.isFlippedV) -1f else 1f)
    private val rad = Math.toRadians(clip.rotationDeg.toDouble())
    private val cs = cos(rad).toFloat()
    private val sn = sin(rad).toFloat()

    fun toCanvas(x: Float, y: Float): Offset {
        val lx = x * base * sx
        val ly = y * base * sy
        return Offset(cx + lx * cs - ly * sn, cy + lx * sn + ly * cs)
    }

    fun toStored(p: Offset): Pair<Float, Float> {
        val dx = p.x - cx
        val dy = p.y - cy
        val x = dx * cs + dy * sn
        val y = -dx * sn + dy * cs
        return (x / (base * sx)) to (y / (base * sy))
    }
}

/** What a touch in the node editor grabbed. kind: 0 = anchor, 1 = handle, 2 = segment. */
private data class NodeHit(val kind: Int, val sub: Int, val idx: Int, val out: Boolean, val t: Float)

private fun nodeHitTest(
    map: PathFrameMap,
    path: EditablePath,
    selSub: Int, selIdx: Int,
    pos: Offset,
    anchorR: Float, handleR: Float, segR: Float
): NodeHit? {
    // 1) The selected anchor's handles (only it shows handles).
    if (selSub in path.subpaths.indices) {
        val sp = path.subpaths[selSub]
        if (selIdx in sp.anchors.indices) {
            val a = sp.anchors[selIdx]
            if (a.hasOut) {
                val hp = map.toCanvas(a.outX!!, a.outY!!)
                if ((pos - hp).getDistance() <= handleR) return NodeHit(1, selSub, selIdx, true, 0f)
            }
            if (a.hasIn) {
                val hp = map.toCanvas(a.inX!!, a.inY!!)
                if ((pos - hp).getDistance() <= handleR) return NodeHit(1, selSub, selIdx, false, 0f)
            }
        }
    }
    // 2) Any anchor (nearest first).
    var bestAnchor: NodeHit? = null
    var bestDist = anchorR
    path.subpaths.forEachIndexed { s, sp ->
        sp.anchors.forEachIndexed { i, a ->
            val d = (pos - map.toCanvas(a.x, a.y)).getDistance()
            if (d <= bestDist) {
                bestDist = d
                bestAnchor = NodeHit(0, s, i, false, 0f)
            }
        }
    }
    if (bestAnchor != null) return bestAnchor
    // 3) A segment: walk each segment's flattened samples (stored units) in canvas
    // pixels; the closest projection gives both the hit and the split fraction.
    var best: NodeHit? = null
    var bestSegDist = segR
    path.subpaths.forEachIndexed { s, sp ->
        val n = sp.anchors.size
        if (n < 2) return@forEachIndexed
        val segCount = if (sp.closed) n else n - 1
        for (segIdx in 0 until segCount) {
            val a = sp.anchors[segIdx]
            val b = sp.anchors[(segIdx + 1) % n]
            val startSeg = com.example.model.PathSeg(PATH_OP_MOVE, listOf(a.x, a.y))
            val drawSeg = PathEdit.toSegments(
                EditablePath(listOf(PathSubpath(listOf(a, b))))
            ).getOrNull(1) ?: continue
            val samples = flattenPathSegments(listOf(startSeg, drawSeg), 32)
                .map { map.toCanvas(it.first, it.second) }
            if (samples.size < 2) continue
            for (i in 0 until samples.size - 1) {
                val p0 = samples[i]
                val p1 = samples[i + 1]
                val dx = p1.x - p0.x
                val dy = p1.y - p0.y
                val len2 = dx * dx + dy * dy
                val tt = if (len2 < 1e-9f) 0f
                else (((pos.x - p0.x) * dx + (pos.y - p0.y) * dy) / len2).coerceIn(0f, 1f)
                val ex = p0.x + dx * tt - pos.x
                val ey = p0.y + dy * tt - pos.y
                val d = hypot(ex, ey)
                if (d <= bestSegDist) {
                    bestSegDist = d
                    best = NodeHit(2, s, segIdx, false, (i + tt) / (samples.size - 1))
                }
            }
        }
    }
    return best
}

/**
 * The node-editing overlay for a selected PATH layer: draws the outline with its
 * anchors (small squares; the selected one filled), the selected anchor's bezier
 * handles (small circles on grey lines), and turns touches into edits — drag an
 * anchor to move it, drag a handle to bend the curve, tap a segment to add a point
 * there, tap empty canvas to deselect. Positions go through [PathFrameMap], so the
 * points sit exactly on the painted outline.
 */
@Composable
fun NodeEditOverlay(
    clip: TimelineClip,
    path: EditablePath,
    selectedSub: Int,
    selectedIdx: Int,
    onSelect: (Int, Int) -> Unit,
    onDeselect: () -> Unit,
    onGestureStart: () -> Unit,
    onMoveAnchor: (Int, Int, Float, Float) -> Unit,
    onMoveHandle: (Int, Int, Boolean, Float, Float) -> Unit,
    onAddPoint: (Int, Int, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density
    val clipNow by rememberUpdatedState(clip)
    val pathNow by rememberUpdatedState(path)
    val selSubNow by rememberUpdatedState(selectedSub)
    val selIdxNow by rememberUpdatedState(selectedIdx)
    val selectNow by rememberUpdatedState(onSelect)
    val deselectNow by rememberUpdatedState(onDeselect)
    val gestureStartNow by rememberUpdatedState(onGestureStart)
    val moveAnchorNow by rememberUpdatedState(onMoveAnchor)
    val moveHandleNow by rememberUpdatedState(onMoveHandle)
    val addPointNow by rememberUpdatedState(onAddPoint)
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("node_edit_overlay")
            .pointerInput(clip.id) {
                val anchorR = 16.dp.toPx()
                val handleR = 16.dp.toPx()
                val segR = 12.dp.toPx()
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent()
                        val first = down.changes.firstOrNull { it.pressed } ?: continue
                        val startPos = first.position
                        first.consume()
                        val map = PathFrameMap(
                            clipNow, size.width.toFloat(), size.height.toFloat(), density
                        )
                        val hit = nodeHitTest(
                            map, pathNow, selSubNow, selIdxNow, startPos, anchorR, handleR, segR
                        )
                        if (hit == null) {
                            deselectNow()
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.first()
                                ch.consume()
                                if (!ch.pressed) break
                            }
                        } else when (hit.kind) {
                            1 -> {
                                gestureStartNow()
                                var done = false
                                while (!done) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.first()
                                    if (ch.pressed) {
                                        val st = map.toStored(ch.position)
                                        moveHandleNow(hit.sub, hit.idx, hit.out, st.first, st.second)
                                        ch.consume()
                                    } else {
                                        ch.consume()
                                        done = true
                                    }
                                }
                            }
                            0 -> {
                                selectNow(hit.sub, hit.idx)
                                gestureStartNow()
                                var done = false
                                while (!done) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.first()
                                    if (ch.pressed) {
                                        val st = map.toStored(ch.position)
                                        moveAnchorNow(hit.sub, hit.idx, st.first, st.second)
                                        ch.consume()
                                    } else {
                                        ch.consume()
                                        done = true
                                    }
                                }
                            }
                            2 -> {
                                addPointNow(hit.sub, hit.idx, hit.t)
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.first()
                                    ch.consume()
                                    if (!ch.pressed) break
                                }
                            }
                        }
                    }
                }
            }
    ) {
        val map = PathFrameMap(clip, size.width, size.height, density)
        // The outline, replayed from the anchors through the one segment rule.
        val segs = PathEdit.toSegments(path)
        val outline = Path()
        for (seg in segs) {
            val q = seg.pts
            when (seg.op) {
                PATH_OP_MOVE -> if (q.size >= 2) map.toCanvas(q[0], q[1]).let { outline.moveTo(it.x, it.y) }
                PATH_OP_LINE -> if (q.size >= 2) map.toCanvas(q[0], q[1]).let { outline.lineTo(it.x, it.y) }
                PATH_OP_CUBIC -> if (q.size >= 6) {
                    val c1 = map.toCanvas(q[0], q[1])
                    val c2 = map.toCanvas(q[2], q[3])
                    val e = map.toCanvas(q[4], q[5])
                    outline.cubicTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y)
                }
                PATH_OP_CLOSE -> outline.close()
            }
        }
        if (path.subpaths.any { it.closed }) drawPath(outline, Color.White.copy(alpha = 0.10f))
        drawPath(outline, Color.White, style = Stroke(width = 1.5f * density, join = StrokeJoin.Round))
        // The selected anchor's handles: grey lines, small white circles.
        if (selectedSub in path.subpaths.indices) {
            val sp = path.subpaths[selectedSub]
            if (selectedIdx in sp.anchors.indices) {
                val a = sp.anchors[selectedIdx]
                val c = map.toCanvas(a.x, a.y)
                if (a.hasIn) {
                    val hp = map.toCanvas(a.inX!!, a.inY!!)
                    drawLine(Color(0xFF9A9A9A), c, hp, strokeWidth = 1f * density)
                    drawCircle(Color.White, radius = 4f * density, center = hp)
                    drawCircle(
                        Color.Black.copy(alpha = 0.6f), radius = 4f * density, center = hp,
                        style = Stroke(width = 1f * density)
                    )
                }
                if (a.hasOut) {
                    val hp = map.toCanvas(a.outX!!, a.outY!!)
                    drawLine(Color(0xFF9A9A9A), c, hp, strokeWidth = 1f * density)
                    drawCircle(Color.White, radius = 4f * density, center = hp)
                    drawCircle(
                        Color.Black.copy(alpha = 0.6f), radius = 4f * density, center = hp,
                        style = Stroke(width = 1f * density)
                    )
                }
            }
        }
        // Anchors: small squares — the selected one filled white, the rest dark with a white edge.
        val half = 4f * density
        path.subpaths.forEachIndexed { s, sp ->
            sp.anchors.forEachIndexed { i, a ->
                val c = map.toCanvas(a.x, a.y)
                val topLeft = Offset(c.x - half, c.y - half)
                val sz = androidx.compose.ui.geometry.Size(half * 2f, half * 2f)
                if (s == selectedSub && i == selectedIdx) {
                    drawRect(Color.White, topLeft = topLeft, size = sz)
                } else {
                    drawRect(Color(0xFF1C1C1C), topLeft = topLeft, size = sz)
                    drawRect(Color.White, topLeft = topLeft, size = sz, style = Stroke(width = 1.3f * density))
                }
            }
        }
    }
}

/** The node-editing panel: the delete-point action and Done — same language as the other panels. */
@Composable
fun NodeEditPanel(
    canDelete: Boolean,
    onDeletePoint: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            "Drag a point to move it. Drag a round handle to bend the curve. Tap a line to add a point.",
            color = AmTextDim, fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PenPanelButton("Delete point", enabled = canDelete, onClick = onDeletePoint)
            Spacer(Modifier.weight(1f))
            GradientPillButton(onClick = onDone, tag = "node_edit_done_button") {
                Text("Done", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Small flat text button of the pen / node panels (the Draw panel's button, same language). */
@Composable
private fun PenPanelButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .bouncyClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (enabled) TextPrimary else AmTextFaint,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold
        )
    }
}
