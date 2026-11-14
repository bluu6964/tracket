package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.model.TimelineClip
import com.example.model.effectiveScalePercent
import com.example.model.depthX
import com.example.model.depthY
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Where a layer sits on the canvas: maps points of the layer's own box to canvas pixels.
 */
internal class FrameGeo(
    val clip: TimelineClip,
    val w: Float,
    val h: Float,
    val frameW: Float,
    val frameH: Float,
    minScale: Float,
    /** Size the layer's pivot is measured against (media layers pivot on the whole canvas); 0 = the box itself. */
    pivotRefW: Float = 0f,
    pivotRefH: Float = 0f,
    /**
     * Visible part of the box after Crop (shape / text layers — the painters clip the box in
     * place, see [com.example.model.cropVisibleBox]): the corners / edges / hit test hug this
     * sub-rectangle, not the whole box. Defaults = the whole box. The pivot and [center] keep
     * measuring against the full box — crop does not move the layer's pivot.
     */
    val visW: Float = w,
    val visH: Float = h,
    /** Centre of the visible part relative to the centre of the box, in box units. */
    val visOffX: Float = 0f,
    val visOffY: Float = 0f
) {
    private val sx = (clip.effectiveScalePercent / 100f).coerceAtLeast(minScale) * (if (clip.isFlippedH) -1f else 1f)
    private val sy = (clip.effectiveScalePercent / 100f).coerceAtLeast(minScale) * (if (clip.isFlippedV) -1f else 1f)
    private val rad = Math.toRadians(clip.rotationDeg.toDouble())
    private val cs = cos(rad).toFloat()
    private val sn = sin(rad).toFloat()
    private val pvx = (clip.pivotX - 0.5f) * (if (pivotRefW > 0f) pivotRefW else w)
    private val pvy = (clip.pivotY - 0.5f) * (if (pivotRefH > 0f) pivotRefH else h)

    /** [q] is relative to the centre of the layer's box. */
    fun map(qx: Float, qy: Float): Offset {
        val x = (qx - pvx) * sx
        val y = (qy - pvy) * sy
        return Offset(
            frameW / 2f + pvx + x * cs - y * sn + clip.depthX,
            frameH / 2f + pvy + x * sn + y * cs + clip.depthY
        )
    }

    val center get() = map(0f, 0f)

    /** Inverse of [map]: canvas pixels -> point relative to the centre of the layer's box. */
    fun unmap(p: Offset): Offset {
        val dx = p.x - frameW / 2f - pvx - clip.depthX
        val dy = p.y - frameH / 2f - pvy - clip.depthY
        val x = dx * cs + dy * sn
        val y = -dx * sn + dy * cs
        return Offset(x / sx + pvx, y / sy + pvy)
    }

    /** True when the point [p] (canvas pixels) lies inside the layer's (rotated, scaled) box. */
    fun contains(p: Offset): Boolean {
        var pos = false
        var neg = false
        for (i in 0..3) {
            val a = corner(i)
            val b = corner((i + 1) % 4)
            val cr = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
            if (cr > 0f) pos = true else if (cr < 0f) neg = true
        }
        return !(pos && neg)
    }

    fun corner(i: Int): Offset = when (i) {
        0 -> map(visOffX - visW / 2f, visOffY - visH / 2f) // top-left
        1 -> map(visOffX + visW / 2f, visOffY - visH / 2f)  // top-right
        2 -> map(visOffX + visW / 2f, visOffY + visH / 2f)   // bottom-right
        else -> map(visOffX - visW / 2f, visOffY + visH / 2f) // bottom-left
    }

    /** Mid-edge handles: 0=top, 1=right, 2=bottom, 3=left. */
    fun edge(i: Int): Offset = when (i) {
        0 -> map(visOffX, visOffY - visH / 2f)   // top mid
        1 -> map(visOffX + visW / 2f, visOffY)    // right mid
        2 -> map(visOffX, visOffY + visH / 2f)    // bottom mid
        else -> map(visOffX - visW / 2f, visOffY) // left mid
    }
}

/**
 * CapCut-style selection bounding box:
 * - Thin white outline with subtle dark edge
 * - 8 small white handles (4 corners + 4 edge mids) with thin black border
 * - Small center pivot dot
 * - No rotation handle
 */
@Composable
fun SelectionFrameOverlay(
    clip: TimelineClip,
    layerWidthPx: Float,
    layerHeightPx: Float,
    minScale: Float,
    onBegin: () -> Unit,
    onTransform: (id: String, scalePercent: Float, rotationDeg: Float) -> Unit,
    modifier: Modifier = Modifier,
    pivotRefW: Float = 0f,
    pivotRefH: Float = 0f,
    /**
     * Visible part of the layer's box after Crop (shape / text layers): size + centre offset
     * in box px, from [com.example.model.cropVisibleBox]. Defaults = the whole box (media
     * callers pass the already-cropped visible size as the box itself).
     */
    visW: Float = layerWidthPx,
    visH: Float = layerHeightPx,
    visOffX: Float = 0f,
    visOffY: Float = 0f
) {
    val density = LocalDensity.current.density
    var frameW by remember { mutableFloatStateOf(1f) }
    var frameH by remember { mutableFloatStateOf(1f) }
    val geo = FrameGeo(clip, layerWidthPx, layerHeightPx, frameW, frameH, minScale, pivotRefW, pivotRefH, visW, visH, visOffX, visOffY)
    val geoNow by rememberUpdatedState(geo)
    val begin by rememberUpdatedState(onBegin)
    val send by rememberUpdatedState(onTransform)

    // Visual handle radius (drawn) — smaller than before
    val handleR = 5.5f * density
    val handleStroke = 1.4f * density
    // Touch target stays larger for usability
    val touch = 40.dp

    /** kind 0..3 = corners, kind 4..7 = edge mids */
    fun handlePos(kind: Int, g: FrameGeo): Offset =
        if (kind < 4) g.corner(kind) else g.edge(kind - 4)

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged {
                frameW = it.width.toFloat()
                frameH = it.height.toFloat()
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Bounding box outline
            val p = Path().apply {
                val c0 = geo.corner(0)
                moveTo(c0.x, c0.y)
                for (i in 1..3) geo.corner(i).let { lineTo(it.x, it.y) }
                close()
            }
            drawPath(
                p,
                Color.Black.copy(alpha = 0.30f),
                style = Stroke(2.8f * density, join = StrokeJoin.Round)
            )
            drawPath(
                p,
                Color.White,
                style = Stroke(1.3f * density, join = StrokeJoin.Round)
            )

            // Center pivot dot (like CapCut)
            val c = geo.center
            drawCircle(Color.Black.copy(alpha = 0.35f), radius = 2.2f * density, center = c)
            drawCircle(Color.White.copy(alpha = 0.85f), radius = 1.4f * density, center = c)

            // 8 handles: white fill + thin black ring
            for (i in 0..7) {
                val hp = handlePos(i, geo)
                drawCircle(Color.White, radius = handleR, center = hp)
                drawCircle(
                    Color.Black.copy(alpha = 0.75f),
                    radius = handleR,
                    center = hp,
                    style = Stroke(handleStroke)
                )
            }
        }

        // Touch targets for all 8 handles (uniform scale from center)
        for (kind in 0..7) {
            Box(
                modifier = Modifier
                    .offset {
                        val p = handlePos(kind, geo)
                        val r = touch.toPx() / 2f
                        IntOffset((p.x - r).roundToInt(), (p.y - r).roundToInt())
                    }
                    .size(touch)
                    .testTag(if (kind < 4) "selection_corner_$kind" else "selection_edge_${kind - 4}")
                    .pointerInput(kind) {
                        var startScale = 100f
                        var startDist = 1f
                        var center = Offset.Zero
                        detectDragGestures(
                            onDragStart = {
                                begin()
                                val g = geoNow
                                center = g.center
                                val p0 = handlePos(kind, g)
                                startScale = g.clip.scalePercent
                                startDist = hypot(p0.x - center.x, p0.y - center.y).coerceAtLeast(1f)
                            }
                        ) { change, _ ->
                            change.consume()
                            val g = geoNow
                            val half = size.width / 2f
                            val hp = handlePos(kind, g)
                            val pos = Offset(
                                hp.x - half + change.position.x,
                                hp.y - half + change.position.y
                            )
                            val d = hypot(pos.x - center.x, pos.y - center.y)
                            val s = (startScale * d / startDist).coerceIn(minScale * 100f, 100000f)
                            // Keep existing rotation; only scale changes
                            send(g.clip.id, s, g.clip.rotationDeg)
                        }
                    }
            )
        }
    }
}
