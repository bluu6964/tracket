package com.example.model

import kotlin.math.abs

/**
 * Controller fan-out (build 146 — Sarowar's Controller semantics v2, 2026-11-13).
 *
 * A Controller ([LayerType.CONTROLLER]) is a SIMULTANEOUS MULTI-LAYER EDIT handle — it is not a
 * parent and it drives nothing. Layers are merely CONNECTED to it, and whatever is done to the
 * Controller in the editor is done the same way to each connected layer, IN THAT LAYER'S OWN
 * PLACE. Sarowar's decisive example: rotating the Controller rotates each connected layer
 * around its OWN pivot, in place — connected layers must NOT orbit the Controller (the
 * build-145 ParentRig inheritance did exactly that orbit and was removed).
 *
 * The fan-out is EDITOR-TIME, not render-time: when an edit changes the Controller, the
 * editor rewrites each connected layer's own stored values (through this object), so the
 * project file afterwards holds ordinary layers with ordinary values and ParentRig / the
 * renderers never hear about the connection. The Controller keeps its own property values
 * (its tools show and edit them like any layer's); the fan-out copies the CHANGE across:
 *
 *  - POSITION — DELTA fan-out: moving the Controller by (dx, dy) moves every connected layer
 *    by (dx, dy) from its own position.
 *  - ROTATION — DELTA fan-out about each layer's own pivot: rotating the Controller by dθ
 *    adds dθ to every connected layer's own rotation. Positions are never touched by it:
 *    no orbit, ever.
 *  - SCALE — RELATIVE fan-out about each layer's own pivot: Controller scale x1.2 multiplies
 *    every connected layer's scale by 1.2.
 *  - OPACITY — VALUE fan-out: the Controller's opacity (evaluated at the playhead) becomes
 *    every connected layer's opacity.
 *  - BLEND MODE — VALUE fan-out: the Controller's mode becomes every connected layer's mode.
 *  - EFFECTS — VALUE fan-out: the Controller's effect stack replaces every connected layer's
 *    own stack (applied to each layer individually — per-layer application is Sarowar's
 *    settled decision, 2026-11-13; not once on the connected set as a composite).
 *  - COLOUR — VALUE fan-out (build 155 — the Color tool joined the Controller's scope):
 *    the Controller's FILL STATE, as it looks at the playhead, becomes every connected
 *    SHAPE / TEXT layer's fill state — fill mode, the solid colour, the gradient stops
 *    and geometry, the image fill. Those are the kinds the app's own colour model
 *    colours (the Color tool's gate); layers with no colour concept (video, audio,
 *    helpers) are simply unaffected. The colours themselves land by the editor's
 *    keyframe rule, like opacity: a connected layer carrying FILL_COLOR / GRAD_COLOR
 *    keyframes gets the Controller's playhead colour as a keyframe at the playhead.
 *
 * Keyframing follows the editor's own rule (the Transform rule): when the connected layer
 * carries keyframes on the fanned property, the fanned value lands as a keyframe at the
 * playhead; otherwise the layer's stored field takes it. Deltas are measured between the
 * Controller's evaluated values ([TimelineClip.at]) at the playhead before / after the edit,
 * so canvas drags, panel edits and keyframe writes at the playhead all fan out the same way.
 * Not fanned (they stay per-layer, like masks / crop / volume / timing): the 3D
 * properties (POS_Z / ROT_X / ROT_Y), the text-style colours (stroke / shadow /
 * background box — those belong to Text edit, not to the Color tool), timing, and
 * the layer's own keyframe structure away from the playhead. Keyframe toggles / easing / In-Out animations made on a Controller
 * are mirrored onto connected layers by the editor as the same operation (see
 * VideoEditorViewModel), not by the value diff here.
 *
 * A layer is CONNECTED to a controller when [TimelineClip.controllerId] names it, or —
 * legacy, from build 145 — when its [TimelineClip.parent] link points at it. The fan-out is
 * ONE level: only the layers connected to the Controller being edited are rewritten (a
 * connected Controller's own values change like any layer's, and its own connected layers
 * are reached when IT is edited).
 */
object ControllerFanOut {

    private const val EPS = 0.0005f

    /** The layers of [clips] connected to the controller [controllerId] (never the controller itself). */
    fun connected(clips: List<TimelineClip>, controllerId: String): List<TimelineClip> =
        clips.filter { it.id != controllerId && (it.controllerId == controllerId || it.parent?.id == controllerId) }

    /** True when [clip] is connected to the controller [controllerId] (see [connected]). */
    fun isConnected(clip: TimelineClip, controllerId: String): Boolean =
        clip.id != controllerId && (clip.controllerId == controllerId || clip.parent?.id == controllerId)

    /**
     * [layer] rewritten with the fan-out of the Controller edit that took the Controller from
     * [before] to [after] (its stored states around the edit), the playhead at [timeMs].
     * Returns [layer] itself when nothing fanned out to it.
     */
    fun fanned(layer: TimelineClip, before: TimelineClip, after: TimelineClip, timeMs: Long): TimelineClip {
        if (layer.id == before.id) return layer
        val b = before.at(timeMs)
        val a = after.at(timeMs)
        val rel = (timeMs - layer.startMs).coerceIn(0L, layer.durationMs)
        val resolved = layer.at(timeMs)
        var out = layer

        // POSITION — delta: the layer moves by what the Controller moved, from its own place.
        val dx = a.transformX - b.transformX
        val dy = a.transformY - b.transformY
        if (abs(dx) > EPS || abs(dy) > EPS) {
            out = out.atPlayhead(AnimProp.POSITION, rel, resolved.transformX + dx, resolved.transformY + dy)
        }
        // ROTATION — delta about the layer's OWN pivot: only its rotation value changes;
        // its position is untouched, so the layer turns in place and never orbits.
        val dr = a.rotationDeg - b.rotationDeg
        if (abs(dr) > EPS) {
            out = out.atPlayhead(AnimProp.ROTATION, rel, resolved.rotationDeg + dr, 0f)
        }
        // SCALE — relative about the layer's own pivot. The keyframe b (a separate height
        // scale, importer form) is preserved from the layer's own track.
        if (abs(b.scalePercent) > EPS) {
            val ratio = a.scalePercent / b.scalePercent
            if (abs(ratio - 1f) > EPS) {
                val keys = layer.keyframes[AnimProp.SCALE].orEmpty()
                val lb = if (keys.isEmpty()) 0f else keys.valueAt(rel).second
                out = out.atPlayhead(AnimProp.SCALE, rel, resolved.scalePercent * ratio, lb)
            }
        }
        // OPACITY — value: the Controller's opacity becomes the layer's.
        if (abs(a.opacity - b.opacity) > EPS) {
            out = out.atPlayhead(AnimProp.OPACITY, rel, a.opacity, 0f)
        }
        // BLEND MODE / EFFECTS — values (neither is keyframed): set on the layer outright.
        if (after.blendMode != before.blendMode) out = out.copy(blendMode = after.blendMode)
        if (after.effects != before.effects) out = out.copy(effects = after.effects)
        // COLOUR — value, per layer kind (build 155): when any part of the Controller's
        // fill state changed, a connected SHAPE / TEXT layer takes the whole state (mode,
        // gradient geometry, image / video fill), because the Color tool presents the fill
        // as one state — picking a colour on the Controller must visibly recolour a card
        // whose own fill was an image. The colours land at their playhead values by the
        // editor's keyframe rule. Other kinds have no colour concept: unaffected.
        if (layer.type == LayerType.SHAPE || layer.type == LayerType.TEXT) {
            val colourChanged = after.fillMode != before.fillMode ||
                after.gradType != before.gradType ||
                after.gradX1 != before.gradX1 || after.gradY1 != before.gradY1 ||
                after.gradX2 != before.gradX2 || after.gradY2 != before.gradY2 ||
                after.fillImageUri != before.fillImageUri ||
                after.fillImageMode != before.fillImageMode ||
                after.fillVideoUri != before.fillVideoUri ||
                a.textColor != b.textColor ||
                a.gradColor1 != b.gradColor1 || a.gradColor2 != b.gradColor2
            if (colourChanged) {
                out = out.copy(
                    fillMode = after.fillMode, gradType = after.gradType,
                    gradX1 = after.gradX1, gradY1 = after.gradY1,
                    gradX2 = after.gradX2, gradY2 = after.gradY2,
                    fillImageUri = after.fillImageUri, fillImageMode = after.fillImageMode,
                    fillVideoUri = after.fillVideoUri
                )
                if (a.textColor != b.textColor) {
                    val (pa, pb) = packColor(a.textColor)
                    out = out.atPlayhead(AnimProp.FILL_COLOR, rel, pa, pb)
                }
                if (a.gradColor1 != b.gradColor1) {
                    val (pa, pb) = packColor(a.gradColor1)
                    out = out.atPlayhead(AnimProp.GRAD_COLOR_1, rel, pa, pb)
                }
                if (a.gradColor2 != b.gradColor2) {
                    val (pa, pb) = packColor(a.gradColor2)
                    out = out.atPlayhead(AnimProp.GRAD_COLOR_2, rel, pa, pb)
                }
            }
        }
        return out
    }

    /**
     * The editor's keyframe rule for one fanned property: a layer that carries keyframes on
     * [p] gets the value as a keyframe at [rel] (its stored fallback is untouched); a layer
     * without them takes the value into its stored field.
     */
    private fun TimelineClip.atPlayhead(p: AnimProp, rel: Long, a: Float, b: Float): TimelineClip =
        if (keyframes[p].isNullOrEmpty()) withProp(p, a, b) else withKeyframe(p, rel, a, b)
}
