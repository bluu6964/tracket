package com.example.engine

import com.example.model.BlendModes
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
 * A Controller layer ([LayerType.CONTROLLER], build 145 — Sarowar's design) is a parent in the same way, but it hands
 * MORE down to the layers connected to it (the layers whose link points at it):
 *  - TRANSFORM: exactly the Null behaviour above.
 *  - OPACITY: the controller's opacity (evaluated at t) MULTIPLIES the connected layer's own.
 *  - BLENDING: a connected layer that still carries the default blend mode ("Normal") inherits the controller's
 *    blend mode; a layer with any blend mode of its own keeps its own (its explicit choice wins). Consequence of
 *    this precedence: a connected layer cannot force plain Normal against a controller blend — it must be
 *    disconnected, or the controller's mode reset.
 *  - EFFECTS: the controller's effect stack runs on EACH connected layer individually, AFTER that layer's own
 *    stack (evaluation order: own stack in order, then the controller's stack in order) — per-layer application,
 *    decided by Sarowar 2026-11-13 (not once on the connected set as a composite).
 * The inheritance is applied in [adjusted] / [compose] (so every consumer of the rig sees the effective values) and,
 * for the media path that places layers from C++ numbers, in [applyToFp] (opacity) + [withControllerInheritance]
 * (blend / effects read by the caller). Only the DIRECT parent hands down: a layer behind a Null / Group between it
 * and a Controller gets the transform chain but no opacity / blend / effects from that Controller. A Controller that
 * is itself connected to another Controller first inherits from its own (its evaluated values are already
 * effective), so chains of controllers cascade. What stays per-layer only: masks, colour / fill, crop, volume,
 * visibility (the eye) and the layer's own keyframes.
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
        // Parent Helper locks: the position follows in full (the layer stays attached
        // to the parent), only the layer's OWN scale / rotation stop inheriting.
        val dsOwn = if (link.lockScale) 1f else ds
        val drOwn = if (link.lockRotate) 0f else dr
        val (x, y) = move(fp[0], fp[1], link.refX * k, link.refY * k, n.transformX * k, n.transformY * k, ds, dr)
        fp[0] = x
        fp[1] = y
        // Layers turned in 3D are placed by their base (the centre without the pivot compensation): it moves the same way.
        val (bx, by) = move(fp[14], fp[15], link.refX * k, link.refY * k, n.transformX * k, n.transformY * k, ds, dr)
        fp[14] = bx
        fp[15] = by
        fp[2] *= dsOwn
        fp[3] *= dsOwn
        fp[4] += drOwn
        // Controller parent: its opacity multiplies the layer's (fp[5] is the layer's own
        // opacity fraction from the C++ evaluation; n is the evaluated controller, whose
        // opacity is already effective if it is itself connected to another controller).
        if (n.type == LayerType.CONTROLLER) fp[5] *= (n.opacity / 100f).coerceIn(0f, 1f)
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

    private fun isParentKind(c: TimelineClip): Boolean =
        c.type == LayerType.NULL || c.type == LayerType.GROUP || c.type == LayerType.CONTROLLER

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
        // Parent Helper locks (see applyToFp): position follows in full; the layer's
        // own scale / rotation stop inheriting under lockScale / lockRotate.
        val dsOwn = if (link.lockScale) 1f else ds
        val drOwn = if (link.lockRotate) 0f else dr
        val (x, y) = move(r.transformX, r.transformY, link.refX * k, link.refY * k, n.transformX * k, n.transformY * k, ds, dr)
        var out = r.copy(
            transformX = x, transformY = y,
            scalePercent = r.scalePercent * dsOwn,
            rotationDeg = r.rotationDeg + drOwn,
            opacity = if (hiddenByGroup(r)) 0f else r.opacity
        )
        // Controller parent: opacity / blend / effects cascade (see the class doc). [n] is the
        // controller fully evaluated (its own inheritance already applied), so chains cascade.
        if (n.type == LayerType.CONTROLLER) out = applyControllerValues(out, n)
        if (hiddenByGroup(r)) out = out.copy(opacity = 0f)
        return out
    }

    /**
     * The Controller cascade onto an evaluated layer [r] from its evaluated Controller [ctrl]:
     * opacity multiplies, the blend mode is inherited while [r] still carries the default
     * "Normal" (its own explicit mode wins), and the effect stacks concatenate — [r]'s own
     * stack first, then the controller's (per-layer application, build 145).
     */
    private fun applyControllerValues(r: TimelineClip, ctrl: TimelineClip): TimelineClip = r.copy(
        opacity = r.opacity * (ctrl.opacity / 100f).coerceIn(0f, 1f),
        blendMode = if (r.blendMode == BlendModes.NORMAL) ctrl.blendMode else r.blendMode,
        effects = if (ctrl.effects.isEmpty()) r.effects else r.effects + ctrl.effects
    )

    /**
     * [c] evaluated at [t] with the Controller cascade applied to its VALUES (opacity / blend
     * mode / effects) but WITHOUT the transform composition — for callers that place the layer
     * themselves (the media path: C++ numbers + [applyToFp]) and only read the values the
     * controller hands down. A layer whose direct parent is no Controller comes back as its
     * plain evaluation.
     */
    fun withControllerInheritance(c: TimelineClip, t: Long): TimelineClip {
        val r = render(c, t)
        val link = c.parent ?: return r
        val p = byId[link.id]?.takeIf { it.type == LayerType.CONTROLLER } ?: return r
        return applyControllerValues(r, adjusted(p, t, 1))
    }

    /**
     * The Controller cascade with the controller's STORED values (no time evaluation): enough
     * for decisions that never vary with time — which blend shader / whether any effects a
     * layer effectively carries (the renderers' overlay grouping). Opacity is NOT applied
     * here (it is time-varying and handled by the evaluated paths).
     */
    fun withStaticControllerInheritance(c: TimelineClip): TimelineClip {
        val link = c.parent ?: return c
        val p = byId[link.id]?.takeIf { it.type == LayerType.CONTROLLER } ?: return c
        return c.copy(
            blendMode = if (c.blendMode == BlendModes.NORMAL) p.blendMode else c.blendMode,
            effects = if (p.effects.isEmpty()) c.effects else c.effects + p.effects
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
