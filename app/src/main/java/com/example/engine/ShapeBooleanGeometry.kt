package com.example.engine

import com.example.model.BoolPt
import com.example.model.TimelineClip
import com.example.model.depthX
import com.example.model.depthY
import com.example.model.isBehindCamera
import com.example.model.shapeBooleanRings
import com.example.model.shapeDrawScaleX
import com.example.model.shapeDrawScaleY
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shape booleans (build 192): resolves a shape layer to its filled region as rings in
 * FRAME pixels for one frame — the input [com.example.model.ShapeBoolean.combine]
 * works on, so two shapes combine in the frame space they are both drawn in.
 *
 * The layer is evaluated through the caller's [ParentRig] (keyframes / tracking /
 * parent chain all apply, exactly like the preview) and its local outline
 * ([TimelineClip.shapeBooleanRings] — stored path segments for a PATH layer, the
 * parametric outline for a built-in kind, the crop window already cut in) is replayed
 * with the exact transform [LayerPainter.drawShape] draws the shape itself with
 * (centre + depth, rotation, per-axis shapeDrawScale, flips; base = 65 x density x
 * k) — the same replay [TextPathGeometry] performs for text-on-path. What is
 * combined is therefore the outline AS DRAWN at the playhead: move, rotate, scale or
 * animate either shape and the boolean follows what the user sees.
 *
 * Guards (the flat replay cannot model these placements, so the shape is refused —
 * the boolean never combines geometry from somewhere wrong): the layer is
 * pose-placed (3D turn / own pivot, [overlayNeedsPose]) or behind the camera (Z).
 */
internal object ShapeBooleanGeometry {

    /**
     * [clips] = every clip of the project, [rig] = the frame's parent rig built over
     * the same clips (the preview engine's construction), [k] = frame px per preview
     * px and [density] the shape box's density — the same numbers the shape painters
     * use. In the editor both are the preview's own (k = 1, frame = preview frame).
     * Returns the rings in frame px, or null when the layer has no combinable region
     * at [timeMs].
     */
    fun frameRings(
        clips: List<TimelineClip>,
        rig: ParentRig,
        clip: TimelineClip,
        timeMs: Long,
        frameW: Float,
        frameH: Float,
        k: Float,
        density: Float
    ): List<List<BoolPt>>? {
        val raw = clips.firstOrNull { it.id == clip.id } ?: clip
        if (overlayNeedsPose(raw)) return null
        val adj = rig.adjusted(raw, timeMs)
        if (adj.isBehindCamera) return null
        val local = adj.shapeBooleanRings() ?: return null

        val base = 65f * density * k
        val cx = frameW / 2f + adj.depthX * k
        val cy = frameH / 2f + adj.depthY * k
        val sx = adj.shapeDrawScaleX * if (adj.isFlippedH) -1f else 1f
        val sy = adj.shapeDrawScaleY * if (adj.isFlippedV) -1f else 1f
        val rad = Math.toRadians(adj.rotationDeg.toDouble())
        val co = cos(rad).toFloat()
        val si = sin(rad).toFloat()
        return local.map { ring ->
            ring.map { p ->
                val lx = p.x.toFloat() * base * sx
                val ly = p.y.toFloat() * base * sy
                BoolPt(
                    (cx + co * lx - si * ly).toDouble(),
                    (cy + si * lx + co * ly).toDouble()
                )
            }
        }
    }
}
