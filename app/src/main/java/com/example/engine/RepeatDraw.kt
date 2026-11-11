package com.example.engine

import com.example.model.TimelineClip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Repeat family's draw-side pose composition for the layers that place ONE finished picture
 * with renderer parameters — a video / photo texture and a Group layer's composed picture — in
 * the preview and the export (the overlay layers expand copy clips instead, see [RepeatEval]).
 * One shared mechanism for both engines and both layer kinds: the layer's picture is drawn once
 * per copy of [RepeatEval.copyDeltas], each time with the copy's own [LayerParams] — the same
 * texture every time (a repeated video layer still has ONE decoder), the same group picture,
 * only the placement changes.
 *
 * A media copy's placement is the base placement recomposed around the layer's anchor, with the
 * same pivot folding the layer anim itself uses (cpp/engine/layer_anim.cpp): the anchor term
 * R * S * pivot is recomputed with the copy's turn / scale, so a copy scales and turns about the
 * layer's own anchor exactly like the layer does, then steps by the copy's scene-space offset.
 * (For a centred pivot the anchor term is zero and this is just offset + delta.) The 3D fields
 * ride along unchanged except the base position, which translates rigidly — a turned-in-3D
 * layer's copies stay in its plane, posed about its pivot by the renderer's own 3D model.
 */

/** [s] * R([deg]) * ([x], [y]), the layer anim's pivot folding (screen space, y down). */
private fun turned(s: Float, deg: Float, x: Float, y: Float): Pair<Float, Float> {
    val rad = deg * PI.toFloat() / 180f
    val c = cos(rad)
    val sn = sin(rad)
    return Pair(s * (c * x - sn * y), s * (sn * x + c * y))
}

/**
 * The placements a video / photo layer is drawn with at [timeMs]: the base placement alone when
 * the layer has no repeat-family effect, one placement per copy when it has. [base] is the
 * layer's evaluated placement (its pin, when corner-pinned / camera-placed, already set): a
 * pinned layer keeps its single quad — the pin IS its pose, and copies would stack onto it.
 * [k] = output px per preview px (the copy offsets are stored in preview px).
 */
internal fun repeatDrawParams(base: LayerParams, clip: TimelineClip, timeMs: Long, k: Float): List<LayerParams> {
    if (base.pin != null) return listOf(base)
    val deltas = RepeatEval.copyDeltas(clip, timeMs) ?: return listOf(base)
    val s0 = abs(base.scaleX)
    val (r0x, r0y) = turned(s0, base.rotationDeg, base.pivotX, base.pivotY)
    return deltas.map { d ->
        val (r1x, r1y) = turned(s0 * d.scaleMul, base.rotationDeg + d.rotDeg, base.pivotX, base.pivotY)
        LayerParams().also { p ->
            p.offsetX = base.offsetX + (r0x - r1x) + d.dx * k
            p.offsetY = base.offsetY + (r0y - r1y) + d.dy * k
            p.scaleX = base.scaleX * d.scaleMul
            p.scaleY = base.scaleY * d.scaleMul
            p.rotationDeg = base.rotationDeg + d.rotDeg
            p.opacity = (base.opacity * d.opacityMul).coerceIn(0f, 1f)
            p.cropX = base.cropX
            p.cropY = base.cropY
            p.cropW = base.cropW
            p.cropH = base.cropH
            p.rotX = base.rotX
            p.rotY = base.rotY
            p.pivotX = base.pivotX
            p.pivotY = base.pivotY
            p.baseX = base.baseX + d.dx * k
            p.baseY = base.baseY + d.dy * k
            p.perspD = base.perspD
            p.gain = base.gain
        }
    }
}

/**
 * The placements a Group layer's composed picture is drawn with at [timeMs]: null when the group
 * has no repeat-family effect (the caller draws its single placement), one placement per copy
 * when it has. [g] is the group layer as evaluated at [timeMs] (its anchor is its stored pose:
 * transformX / transformY in preview px); [baseOpacity] is the group's evaluated opacity as a
 * fraction. The picture is frame-sized, so a copy is the whole picture transformed about the
 * group's anchor ([RepeatEval.groupCopyOffset]) — after the group's own mask and effects have
 * run on it, exactly like the single draw it replaces, so the copies respect the group's
 * effects / mask ordering by construction.
 */
internal fun groupRepeatDrawParams(g: TimelineClip, timeMs: Long, k: Float, baseOpacity: Float): List<LayerParams>? {
    val deltas = RepeatEval.copyDeltas(g, timeMs) ?: return null
    val ax = g.transformX * k
    val ay = g.transformY * k
    return deltas.map { d ->
        val (ox, oy) = RepeatEval.groupCopyOffset(ax, ay, d, k)
        LayerParams().also { p ->
            p.offsetX = ox
            p.offsetY = oy
            p.rotationDeg = d.rotDeg
            p.scaleX = d.scaleMul
            p.scaleY = d.scaleMul
            p.opacity = (baseOpacity * d.opacityMul).coerceIn(0f, 1f)
        }
    }
}
