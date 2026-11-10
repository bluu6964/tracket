package com.example.engine

import com.example.model.LayerType
import com.example.model.TimelineClip
import kotlin.math.cos
import kotlin.math.sin

/**
 * Parenting by a Null object (v150). A layer with [TimelineClip.parent] follows the MOVE of its Null since the moment
 * the link was made: the Null's position, scale and rotation now, compared with the pose saved in the link
 * ([com.example.model.ParentLink]). So a layer does not jump when it is linked, and when the Null moves / turns /
 * scales (keyframes work), the layer moves / turns / scales with it around the Null's position (2D).
 * A Null can have a Null as its own parent (chain, cycles are cut by [MAX_DEPTH]).
 *
 * A real Group layer ([LayerType.GROUP], see GroupOps) is a parent in exactly the same way (its move / scale / turn reaches
 * the layers inside), and a hidden group hides the layers inside ([hiddenByGroup]). Its opacity, blend mode, effects and
 * masks are NOT handed down: the renderer draws the layers inside into their own picture and treats that picture as
 * one layer (see NativePreviewEngine / NativeProjectExporter, "group pass"). [inheritedGroupOpacity] is only for
 * Ungroup, which has to give the layers the opacity the group had.
 *
 * Preview and export both use this one class; they only differ in how a clip is evaluated at a time ([render]):
 * the C++ animation for the preview, the Kotlin renderAt for the export (same numbers).
 */
internal class ParentRig(clips: List<TimelineClip>, private val render: (TimelineClip, Long) -> TimelineClip) {
    private val byId = clips.associateBy { it.id }
    val hasLinks: Boolean = clips.any { it.parent != null }

    /** [c] at [t] with its animation, and then moved by its Null (text / shape / sticker / Null layers). */
    fun adjusted(c: TimelineClip, t: Long, depth: Int = 0): TimelineClip {
        val r = render(c, t)
        val n = nullOf(c, t, depth) ?: return r
        return compose(r, n, 1f)
    }

    /**
     * Video / photo layers: [fp] = the animation numbers of the layer (offset px [0..1], scale [2..3], rotation [4]);
     * changed in place. [k] = output pixels per clip unit (offsets are px there).
     */
    fun applyToFp(c: TimelineClip, t: Long, fp: FloatArray, k: Float) {
        val n = nullOf(c, t, 0) ?: return
        val link = c.parent ?: return
        val ds = scaleRatio(link.refScale, n.scalePercent)
        val dr = n.rotationDeg - link.refRot
        val (x, y) = move(fp[0], fp[1], link.refX * k, link.refY * k, n.transformX * k, n.transformY * k, ds, dr)
        fp[0] = x
        fp[1] = y
        // Layers turned in 3D are placed by their base (the centre without the pivot compensation): it moves the same way.
        val (bx, by) = move(fp[14], fp[15], link.refX * k, link.refY * k, n.transformX * k, n.transformY * k, ds, dr)
        fp[14] = bx
        fp[15] = by
        fp[2] *= ds
        fp[3] *= ds
        fp[4] += dr
        // Group: its eye reaches the layers inside (its opacity does not: the group is drawn as one picture).
        if (hiddenByGroup(c)) fp[5] = 0f
    }

    /** The pose (position / scale / rotation, with its own parents) a link to [nullId] would save at [t]. */
    fun poseOf(nullId: String, t: Long): TimelineClip? {
        val n = byId[nullId]?.takeIf { isParentKind(it) } ?: return null
        return adjusted(n, t, 1)
    }

    /** True when making [parentId] the parent of [childId] would make a loop. */
    fun wouldLoop(childId: String, parentId: String): Boolean {
        var cur: String? = parentId
        var n = 0
        while (cur != null && n++ <= MAX_DEPTH + 2) {
            if (cur == childId) return true
            cur = byId[cur]?.parent?.id
        }
        return false
    }

    private fun nullOf(c: TimelineClip, t: Long, depth: Int): TimelineClip? {
        val link = c.parent ?: return null
        if (depth > MAX_DEPTH) return null
        val p = byId[link.id]?.takeIf { isParentKind(it) } ?: return null
        return adjusted(p, t, depth + 1)
    }

    private fun isParentKind(c: TimelineClip): Boolean = c.type == LayerType.NULL || c.type == LayerType.GROUP

    /** True when a Group above [c] (its parent, or the parent of that parent, ...) is switched off with its eye. */
    fun hiddenByGroup(c: TimelineClip): Boolean {
        var cur: TimelineClip? = c
        var n = 0
        while (cur != null && n++ <= MAX_DEPTH) {
            val p = cur.parent?.let { byId[it.id] } ?: return false
            if (p.type == LayerType.GROUP && !p.isVisible) return true
            cur = p
        }
        return false
    }

    /**
     * Opacity (0..1) of the groups above [c] at [t], multiplied (1 when [c] is in no group). Only Ungroup uses it: a group
     * is drawn as ONE picture, so while it exists its opacity is applied to that picture and not to the layers inside.
     */
    fun inheritedGroupOpacity(c: TimelineClip, t: Long): Float {
        var f = 1f
        var cur: TimelineClip? = c
        var n = 0
        while (cur != null && n++ <= MAX_DEPTH) {
            val p = cur.parent?.let { byId[it.id] } ?: break
            if (p.type == LayerType.GROUP) f *= (render(p, t).opacity / 100f).coerceIn(0f, 1f)
            cur = p
        }
        return f
    }

    private fun compose(r: TimelineClip, n: TimelineClip, k: Float): TimelineClip {
        val link = r.parent ?: return r
        val ds = scaleRatio(link.refScale, n.scalePercent)
        val dr = n.rotationDeg - link.refRot
        val (x, y) = move(r.transformX, r.transformY, link.refX * k, link.refY * k, n.transformX * k, n.transformY * k, ds, dr)
        return r.copy(
            transformX = x, transformY = y,
            scalePercent = r.scalePercent * ds,
            rotationDeg = r.rotationDeg + dr,
            opacity = if (hiddenByGroup(r)) 0f else r.opacity
        )
    }

    private fun scaleRatio(refScale: Float, nowScale: Float): Float = nowScale / refScale.coerceAtLeast(0.01f)

    /** Point (x, y) turned by [dr] degrees (clockwise on screen, y down) and scaled by [ds] around the Null's old position, then put at the Null's new position. */
    private fun move(x: Float, y: Float, refX: Float, refY: Float, nx: Float, ny: Float, ds: Float, dr: Float): Pair<Float, Float> {
        val vx = x - refX
        val vy = y - refY
        val rad = Math.toRadians(dr.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        return Pair(nx + (vx * c - vy * s) * ds, ny + (vx * s + vy * c) * ds)
    }

    companion object {
        const val MAX_DEPTH = 8
    }
}
