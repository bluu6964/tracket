package com.example.engine

import com.example.model.ClipFx
import com.example.model.ClipFxLibrary
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Alight Motion `motionblur` / `motionblur2` / `motionblur4` — the honest approximation the roadmap
 * asks for: a directional blur along the layer's position velocity plus a radial blur for its
 * scale / rotation velocity, resolved per frame. Pure Kotlin (no Android classes) so it can be
 * unit tested; the C++ side needs no change (the resolved ops are the existing FX_DIR_BLUR and
 * FX_RADIAL_BLUR, and the unresolved type 55 is skipped by fxParseOps: it sits above FX_MAX).
 *
 * What Alight stores (all three versions share the same four properties; 33 motionblur4 + 18
 * motionblur + 7 motionblur2 instances in the corpus):
 * ```
 * <effect id="com.alightcreative.effects.motionblur4" locallyApplied="true">
 *   <property name="tune" type="float" value="0.200000" />
 *   <property name="usePos" type="bool" value="true" />
 *   <property name="useScale" type="bool" value="true" />
 *   <property name="useAngle" type="bool" value="true" />
 * </effect>
 * ```
 * `tune` is the shutter strength (0..1 in the corpus: 0.2, 1.0); `usePos` / `useScale` / `useAngle`
 * say which kinds of motion smear. The importer stores the three flags as values[1..3] of the
 * Motion Blur effect (values[0] is the Strength); a Motion Blur made in the app's own Effects
 * panel has only values[0], so the flags default to on. [resolve] turns one Motion Blur into up
 * to two ops: a DIR_BLUR along the position velocity and a RADIAL_BLUR (spin / zoom) around the
 * layer centre for the scale / rotation velocity.
 *
 * Position maths — the layer moves (vx, vy) preview-px per frame (central difference of the
 * evaluated pose over one frame); the smear on screen should span that distance. The
 * directional-blur shader draws `lenPx = length / 100 * 160 * (H / 1080)` texture-px, so a smear
 * of `speedFrac` of the canvas height per frame needs `length = speedFrac * 1080 / 160 * 100 =
 * speedFrac * 675` (the texture height cancels: the same formula holds for canvas-sized overlay
 * pictures and for media textures). The strength multiplies it (`tune`).
 *
 * Angle: the shader's angle is counter-clockwise on screen (its direction is (cos a, -sin a) with
 * screen y down), so the canvas velocity (vx, vy, y down) is `atan2(-vy, vx)`; the picture is
 * turned clockwise by `rotationDeg` before it is drawn, which subtracts from a counter-clockwise
 * angle, so the picture-space angle is that plus `rotationDeg`. Verified against gl_math.h
 * (rotateZ(+deg) turns clockwise on screen) and the blur shader's own comment.
 *
 * Radial maths — the radial-blur shader (kFxBlur2 mode 1) turns / scales every tap around the
 * centre: `ang = s * amt * 0.5 * (1 - zoom)`, `sc = 1 + s * amt * 0.30 * zoom` for s in [-1, 1],
 * so the half-spans are `amt * 0.5 * (1 - zoom)` radians of spin and `amt * 0.30 * zoom` of
 * relative scale. The app's blur convention (same as the directional one) is half-span = one
 * frame of motion, so with dR = the layer's rotation change this frame (radians) and dS = its
 * relative scale change this frame, `amt * (1 - zoom) = 2 * dR` and `amt * 0.6 * zoom = 2 * dS`:
 * `amt = 2 * dR + 2 * dS / 0.6`, `zoom = (2 * dS / 0.6) / amt` (0 = pure spin, 1 = pure zoom).
 * The centre is the layer centre (50, 50): scale and rotation both pivot there.
 *
 * Known approximations (the phone comparison will tune them):
 *  - the directional length ignores the pose scale: a layer drawn at 50 % smears half as far on
 *    screen as it should (the length is exact at 100 % scale, off by the scale factor otherwise);
 *  - the pose scale is a single uniform percent: a layer whose width and height animate on
 *    different tracks (the importer's "only the width is animated" case) blurs by its uniform
 *    scale track, not by each axis;
 *  - parent motion is ignored: the pose is sampled from the clip's own keyframes, so a layer that
 *    only moves because its parent group / null moves gets no blur (the effect ON the moving group
 *    itself, the common corpus case, is correct);
 *  - the preview assumes the 30 fps default frame (33 ms); the export uses the real setting.
 */
internal object MotionBlurEval {

    /** The pose of a layer at a time: preview-px from the canvas centre, rotation clockwise-positive, uniform scale in percent. */
    data class Pose(val x: Float, val y: Float, val rotationDeg: Float, val scalePercent: Float = 100f)

    /** Length (DIR_BLUR p1, 0..100) for a layer moving [speedFracPerFrame] of the canvas height per frame. */
    fun lengthFor(speedFracPerFrame: Float, tune: Float): Float =
        (speedFracPerFrame * 675f * tune.coerceIn(0f, 1f)).coerceIn(0f, 100f)

    /**
     * (angleDeg, length01) of the directional blur for the layer's motion at [timeMs], or null when
     * the layer is still (or barely moving) at that frame. [poseAt] samples the evaluated pose;
     * [frameMs] is one project frame; [canvasHeightPx] is the canvas height in the same preview-px
     * as the pose. [tune] is 0..1.
     */
    fun blurAt(
        poseAt: (Long) -> Pose?,
        timeMs: Long,
        frameMs: Long,
        canvasHeightPx: Float,
        tune: Float
    ): Pair<Float, Float>? {
        if (frameMs <= 0L || canvasHeightPx <= 0f) return null
        val h = frameMs / 2
        val p0 = poseAt(timeMs - h) ?: return null
        val p1 = poseAt(timeMs + h) ?: return null
        val vx = p1.x - p0.x
        val vy = p1.y - p0.y
        val speedPx = hypot(vx, vy)
        if (speedPx < 0.5f) return null   // still (or sub-pixel): nothing to smear
        val length = lengthFor(speedPx / canvasHeightPx, tune)
        if (length < 0.5f) return null
        // Shader angle is counter-clockwise on screen; the picture is turned clockwise by rotationDeg.
        val angle = ((Math.toDegrees(atan2(-vy.toDouble(), vx.toDouble())).toFloat() + p1.rotationDeg) % 360f + 360f) % 360f
        return angle to length
    }

    /**
     * (amount 0..100, spin/zoom 0..100) of the radial blur for the layer's scale / rotation motion
     * at [timeMs], or null when it is not scaling or turning (or barely) at that frame. [poseAt]
     * samples the evaluated pose; [frameMs] is one project frame; [tune] is 0..1. A disabled kind
     * contributes nothing: with [useAngle] off a pure spin returns null, with [useScale] off a
     * pure zoom returns null. The centre is the layer centre (50, 50) — the caller fills it in.
     */
    fun radialAt(
        poseAt: (Long) -> Pose?,
        timeMs: Long,
        frameMs: Long,
        tune: Float,
        useScale: Boolean = true,
        useAngle: Boolean = true
    ): Pair<Float, Float>? {
        if (frameMs <= 0L || (!useScale && !useAngle)) return null
        val h = frameMs / 2
        val p0 = poseAt(timeMs - h) ?: return null
        val p1 = poseAt(timeMs + h) ?: return null
        // Shortest signed rotation change this frame, in radians (179 -> -179 is +2 degrees, not -358).
        val dDeg = ((p1.rotationDeg - p0.rotationDeg) % 360f + 540f) % 360f - 180f
        val spinPart = if (useAngle) 2f * kotlin.math.abs(Math.toRadians(dDeg.toDouble()).toFloat()) else 0f
        // Relative scale change this frame, against the mid-frame scale (symmetric in / out).
        val sMid = (p0.scalePercent + p1.scalePercent) / 2f
        if (sMid <= 0f) return null
        val zoomPart = if (useScale) 2f * kotlin.math.abs((p1.scalePercent - p0.scalePercent) / sMid) / 0.6f else 0f
        val amt = spinPart + zoomPart
        if (amt < 0.005f) return null   // still (or sub-degree / sub-percent): nothing to smear
        val amount = (amt * tune.coerceIn(0f, 1f) * 100f).coerceIn(0f, 100f)
        if (amount < 0.5f) return null
        return amount to (if (amt > 0f) zoomPart / amt * 100f else 0f)
    }

    /**
     * The effects as the renderer should run them at [timeMs]: every enabled Motion Blur becomes
     * a directional blur along the layer's position velocity (trail 0 = smear both ways, like a
     * shutter centred on the frame) and / or a radial blur around the layer centre for its scale /
     * rotation velocity, following the flags stored in values[1..3] (usePos / useScale / useAngle;
     * a Motion Blur made in the app's own Effects panel has only values[0], so all three default
     * to on). A Motion Blur on a still layer is dropped for that frame. Anything else passes
     * through unchanged, and a list with no Motion Blur is returned as-is (no sampling at all).
     * Feed the result to [ClipFxLibrary.toNative].
     */
    fun resolve(
        effects: List<ClipFx>,
        poseAt: (Long) -> Pose?,
        timeMs: Long,
        frameMs: Long,
        canvasHeightPx: Float
    ): List<ClipFx> {
        if (effects.none { it.enabled && it.type == ClipFxLibrary.MOTION_BLUR }) return effects
        return effects.flatMap { fx ->
            if (!fx.enabled || fx.type != ClipFxLibrary.MOTION_BLUR) return@flatMap listOf(fx)
            val tune = (fx.values.getOrNull(0) ?: 100f) / 100f
            val usePos = (fx.values.getOrNull(1) ?: 1f) >= 0.5f
            val useScale = (fx.values.getOrNull(2) ?: 1f) >= 0.5f
            val useAngle = (fx.values.getOrNull(3) ?: 1f) >= 0.5f
            val out = ArrayList<ClipFx>(2)
            if (usePos) {
                blurAt(poseAt, timeMs, frameMs, canvasHeightPx, tune)?.let { b ->
                    out += fx.copy(type = ClipFxLibrary.DIR_BLUR, values = listOf(b.first, b.second, 0f))
                }
            }
            if (useScale || useAngle) {
                radialAt(poseAt, timeMs, frameMs, tune, useScale, useAngle)?.let { r ->
                    out += fx.copy(type = ClipFxLibrary.RADIAL_BLUR, values = listOf(r.first, r.second, 50f, 50f))
                }
            }
            out
        }
    }

    /**
     * The canvas height in preview-px for a surface of [surfaceW] x [surfaceH] whose preview width
     * is [previewWidthPx] (0 before the first preview: the surface size itself is the preview size).
     */
    fun canvasHeightPx(previewWidthPx: Float, surfaceW: Int, surfaceH: Int): Float =
        if (previewWidthPx > 0f && surfaceW > 0) previewWidthPx * surfaceH / surfaceW else surfaceH.toFloat()
}
