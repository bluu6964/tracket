package com.example.engine

import com.example.model.TextOnPath
import com.example.model.TimelineClip
import com.example.model.depthX
import com.example.model.depthY
import com.example.model.flattenPathSegments
import com.example.model.isBehindCamera
import com.example.model.isPathBearing
import com.example.model.shapeDrawScaleX
import com.example.model.shapeDrawScaleY
import kotlin.math.cos
import kotlin.math.sin

/**
 * Text-on-path (build 191): resolves a text layer's attachment ([TimelineClip.textPathLayerId])
 * to the path's polyline in FRAME pixels for one frame — the points [NativeTextPainter] hands
 * to the C++ engine and the Canvas fallback draws along.
 *
 * The path layer is evaluated through the caller's [ParentRig] (so its keyframes, tracking
 * and parent chain all apply — the text rides the path AS EVALUATED at [timeMs]) and its
 * stored segments are replayed with the exact transform [LayerPainter.drawShape] draws the
 * shape itself with (centre + depth, rotation, per-axis scale, flips; base = 65 x density
 * x k), so the text sits on the outline the user sees. The path is evaluated even outside
 * the path layer's own time window — its geometry persists — and the path layer being
 * hidden does NOT detach the text (only deleting the layer, or emptying its outline, does:
 * every unresolvable case returns null and the text lays out in lines as usual).
 *
 * Guards (the flat replay cannot model these placements, so the attachment rests while
 * they apply — the text draws in its normal layout, never somewhere wrong): the text
 * layer or the path layer is pose-placed (3D turn / own pivot, [overlayNeedsPose]), the
 * text layer is placed by a 3D camera ([cameraAffects]), or the path layer sits behind
 * the camera (Z).
 */
internal object TextPathGeometry {

    /**
     * [clips] = every clip of the project (the attachment + the guards read the ORIGINAL
     * layers from it), [rig] = the frame's parent rig built over the same clips,
     * [k] = frame px per preview px and [density] the shape box's density — the same two
     * numbers the shape painters use. Returns x,y pairs (frame px), or null.
     */
    fun framePoints(
        clips: List<TimelineClip>,
        rig: ParentRig,
        textClip: TimelineClip,
        timeMs: Long,
        frameW: Int,
        frameH: Int,
        k: Float,
        density: Float,
        cameraAffects: (TimelineClip) -> Boolean = { false }
    ): FloatArray? {
        val pathId = textClip.textPathLayerId ?: return null
        val pathRaw = clips.firstOrNull { it.id == pathId } ?: return null
        if (!pathRaw.isPathBearing) return null
        // Pose / camera guards read the layers as authored (the clip handed in may be an
        // upright pose copy whose own fields were reset — its id still finds the original).
        val textRaw = clips.firstOrNull { it.id == textClip.id } ?: textClip
        if (overlayNeedsPose(textRaw) || overlayNeedsPose(pathRaw)) return null
        if (cameraAffects(textRaw)) return null

        val adj = rig.adjusted(pathRaw, timeMs)
        if (adj.isBehindCamera || adj.pathSegments.isEmpty()) return null
        val flat = flattenPathSegments(adj.pathSegments)
        if (flat.size < 2) return null
        // A path with no length (all points one place) cannot carry text: rest the
        // attachment (the text draws straight) rather than piling the glyphs on a point.
        if (TextOnPath.arcTable(flat).total <= 0f) return null

        val base = 65f * density * k
        val cx = frameW / 2f + adj.depthX * k
        val cy = frameH / 2f + adj.depthY * k
        val sx = adj.shapeDrawScaleX * if (adj.isFlippedH) -1f else 1f
        val sy = adj.shapeDrawScaleY * if (adj.isFlippedV) -1f else 1f
        val rad = Math.toRadians(adj.rotationDeg.toDouble())
        val co = cos(rad).toFloat()
        val si = sin(rad).toFloat()
        val out = FloatArray(flat.size * 2)
        for ((i, p) in flat.withIndex()) {
            val lx = p.first * base * sx
            val ly = p.second * base * sy
            out[i * 2] = cx + co * lx - si * ly
            out[i * 2 + 1] = cy + si * lx + co * ly
        }
        return out
    }
}
