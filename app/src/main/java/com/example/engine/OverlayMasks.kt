package com.example.engine

import com.example.model.ClipMaskLibrary
import com.example.model.LayerType
import com.example.model.MaskShape
import com.example.model.TimelineClip
import com.example.model.depthX
import com.example.model.depthY
import com.example.model.effectiveScalePercent
import com.example.model.shapeDrawScaleX
import com.example.model.shapeDrawScaleY
import kotlin.math.cos
import kotlin.math.sin

/**
 * A layer's OWN masks ([TimelineClip.masks] — the Masks panel, and where the importer puts
 * Alight's `wipe2`) on the overlay paths (text / shape / sticker layers, and imported groups).
 *
 * Photos and videos run their masks through the fx pass on the layer's own texture, where the
 * mask fractions are of that texture — correct by construction. Overlay layers are drawn into a
 * FRAME-sized picture (or a Group's composed picture, also frame-sized), and until the Graph
 * round-6 link nothing ever applied their own masks there: an imported wipe was imported,
 * stored, and silently never drawn — the Graph preset's bars all rendered at full height
 * because their per-bar wipe masks lived on shape layers in exactly this path. A Group layer's
 * own masks DID run, but as fractions of the whole frame even for an imported embed, whose wipe
 * is written against the embed's own box.
 *
 * The fix: run the mask pass on the finished picture with the mask ops REMAPPED from the
 * layer's box into the picture's space. The remap is pure geometry (this file, unit tested):
 * a mask state is fractions of the layer's box; the box sits in the frame at a known centre,
 * size and rotation (the same numbers the painters draw with); the mask pass runs on a target
 * rect of the frame (the whole frame, or the fx region when effects ran first). All sizes are
 * carried through in pixels, so the native side — which converts the fractions back against
 * the target's pixel size — lands on the same coverage the layer's own texture would have had:
 *  - centre: box centre + the layer rotation applied to the state's centre offset in the box;
 *  - w / h: the state's fractions of the box, re-expressed as fractions of the target;
 *  - rot: state rot + layer rot (with a single flip the direction mirrors, see below);
 *  - feather / expand: both convert to pixels against the TARGET height natively, so they are
 *    rescaled by box height / target height to keep the same pixel width;
 *  - corner rides raw (the native side recomputes it against the remapped half extents);
 *  - pen-path points are in mask-box units and are invariant, except under a flip (mirrored);
 *  - a MIRROR band spans its whole picture by definition, so remapping one changes its reach;
 *    it keeps its raw state (no imported wipe uses it, and a user mask on an overlay layer was
 *    authored against the frame-sized picture anyway in spirit — treat as a known limit).
 * Flips: with no flip or both flips the composition above is exact for every shape. With a
 * single flip the rect / ellipse / polygon / star family stays exact (each is symmetric under
 * a mirror of its own frame, with the rotation direction reversed); a LINE half-plane or a pen
 * path under a single flip is mirrored as well as the single-angle form allows.
 */
internal object OverlayMasks {

    /** A layer's box in frame pixels: centre, size, rotation (degrees, clockwise, y down), flips. */
    class Box(
        val cx: Float, val cy: Float, val w: Float, val h: Float,
        val rotDeg: Float, val flipH: Boolean, val flipV: Boolean
    )

    /** The 130 dp shape box in frame px (the painters' `base` is its half). */
    private fun boxBase(frameW: Int, k: Float, density: Float): Float = 65f * density * k

    /** Box of an evaluated SHAPE clip, exactly as [NativeShapePainter] places it. */
    fun shapeBox(clip: TimelineClip, frameW: Int, frameH: Int, k: Float, density: Float): Box {
        val base = boxBase(frameW, k, density)
        return Box(
            cx = frameW / 2f + clip.depthX * k,
            cy = frameH / 2f + clip.depthY * k,
            w = 2f * base * clip.shapeDrawScaleX,
            h = 2f * base * clip.shapeDrawScaleY,
            rotDeg = clip.rotationDeg, flipH = clip.isFlippedH, flipV = clip.isFlippedV
        )
    }

    /** Box of an evaluated STICKER clip: the picture fitted inside the 130 dp box, as drawn. */
    fun stickerBox(clip: TimelineClip, bmpW: Int, bmpH: Int, frameW: Int, frameH: Int, k: Float, density: Float): Box {
        val base = boxBase(frameW, k, density)
        val bw = bmpW.coerceAtLeast(1).toFloat()
        val bh = bmpH.coerceAtLeast(1).toFloat()
        val hw: Float
        val hh: Float
        if (bw >= bh) {
            hw = base
            hh = base * bh / bw
        } else {
            hw = base * bw / bh
            hh = base
        }
        val s = (clip.effectiveScalePercent / 100f).coerceAtLeast(0.01f)
        return Box(
            cx = frameW / 2f + clip.depthX * k,
            cy = frameH / 2f + clip.depthY * k,
            w = 2f * hw * s, h = 2f * hh * s,
            rotDeg = clip.rotationDeg, flipH = clip.isFlippedH, flipV = clip.isFlippedV
        )
    }

    /**
     * Box of an evaluated TEXT clip: [drawnW] / [drawnH] = the layout box the text engine
     * reported for this draw (its scale-1 box times the layer's effective scale), in frame px.
     */
    fun textBox(clip: TimelineClip, drawnW: Float, drawnH: Float, frameW: Int, frameH: Int, k: Float): Box =
        Box(
            cx = frameW / 2f + clip.depthX * k,
            cy = frameH / 2f + clip.depthY * k,
            w = drawnW, h = drawnH,
            rotDeg = clip.rotationDeg, flipH = clip.isFlippedH, flipV = clip.isFlippedV
        )

    /**
     * Box of an imported GROUP (embed) clip: its embedded scene's rect as the group's composed
     * transform places it. [anchorX] / [anchorY] = the adjusted group clip's stored position
     * (the anchor = embed location + pivot, in stored units), [refX] / [refY] = the pivot in the
     * same units (the children's parent-link reference), [embedW] / [embedH] = the scene size in
     * stored units ([TimelineClip.embedW]). The scene's centre lands on
     * anchor − R(rot)·S(scale)·ref (ParentRig's composition of the centre point), and the rect
     * turns / scales with the group. [k] = frame px per stored unit.
     */
    fun groupBox(
        anchorX: Float, anchorY: Float, refX: Float, refY: Float,
        scalePct: Float, rotDeg: Float, flipH: Boolean, flipV: Boolean,
        embedW: Float, embedH: Float, k: Float, frameW: Int, frameH: Int
    ): Box {
        val s = scalePct / 100f
        val rad = Math.toRadians(rotDeg.toDouble())
        val c = cos(rad).toFloat()
        val sn = sin(rad).toFloat()
        val rx = refX * k * s
        val ry = refY * k * s
        return Box(
            cx = frameW / 2f + anchorX * k - (rx * c - ry * sn),
            cy = frameH / 2f + anchorY * k - (rx * sn + ry * c),
            w = embedW * k * s, h = embedH * k * s,
            rotDeg = rotDeg, flipH = flipH, flipV = flipV
        )
    }

    /**
     * [masks] (the flat array of [ClipMaskLibrary.toNative], fractions of the layer's box)
     * re-expressed as fractions of the target rect ([tx], [ty], [tw], [th] in frame px — the
     * whole frame, or the fx region the mask pass runs on). Returns a new array.
     */
    fun remap(masks: FloatArray, box: Box, tx: Float, ty: Float, tw: Float, th: Float): FloatArray {
        if (tw <= 0f || th <= 0f || box.w <= 0f || box.h <= 0f) return masks
        val out = masks.copyOf()
        val stride = ClipMaskLibrary.STRIDE
        val rad = Math.toRadians(box.rotDeg.toDouble())
        val c = cos(rad).toFloat()
        val sn = sin(rad).toFloat()
        val fx = if (box.flipH) -1f else 1f
        val fy = if (box.flipV) -1f else 1f
        val singleFlip = box.flipH xor box.flipV
        var o = 0
        while (o + stride <= out.size) {
            val shape = out[o].toInt()
            if (shape != MaskShape.MIRROR) {
                val cx = out[o + 3]
                val cy = out[o + 4]
                val w = out[o + 5]
                val h = out[o + 6]
                val rot = out[o + 7]
                // Centre: the state's offset from the box centre (box px), flipped, turned by
                // the layer's rotation, and moved to the box centre in the frame.
                val lx = (cx - 0.5f) * box.w * fx
                val ly = (cy - 0.5f) * box.h * fy
                val px = box.cx + lx * c - ly * sn
                val py = box.cy + lx * sn + ly * c
                out[o + 3] = (px - tx) / tw
                out[o + 4] = (py - ty) / th
                out[o + 5] = w * box.w / tw
                out[o + 6] = h * box.h / th
                out[o + 7] = if (singleFlip) box.rotDeg - rot else box.rotDeg + rot
                // Feather / expand are pixel widths natively derived from the target height.
                out[o + 8] = out[o + 8] * box.h / th
                out[o + 9] = out[o + 9] * box.h / th
                if (shape == MaskShape.PATH) {
                    // Pen points are in mask-box units (-0.5..0.5): they follow the box's own
                    // axes, so only a flip touches them.
                    val n = out[o + 14].toInt()
                    for (i in 0 until n) {
                        if (box.flipH) out[o + 16 + 2 * i] = -out[o + 16 + 2 * i]
                        if (box.flipV) out[o + 17 + 2 * i] = -out[o + 17 + 2 * i]
                    }
                }
            }
            o += stride
        }
        return out
    }

    /** True when [clip] carries an enabled own mask the native pass can run. */
    fun hasOwnMasks(clip: TimelineClip): Boolean = clip.masks.any { it.enabled }

    /** Picks the box of an evaluated overlay [clip]; [textDrawn] = a text layer's drawn box (w, h) in frame px. */
    fun boxOf(
        clip: TimelineClip, textDrawn: Pair<Float, Float>?, stickerSize: Pair<Int, Int>?,
        frameW: Int, frameH: Int, k: Float, density: Float
    ): Box? = when (clip.type) {
        LayerType.SHAPE -> shapeBox(clip, frameW, frameH, k, density)
        LayerType.TEXT -> textDrawn?.let { textBox(clip, it.first, it.second, frameW, frameH, k) }
        LayerType.STICKER -> stickerSize?.let { stickerBox(clip, it.first, it.second, frameW, frameH, k, density) }
        else -> null
    }
}
