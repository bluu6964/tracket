package com.example.engine

import com.example.model.ClipFx
import com.example.model.ClipFxLibrary
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Alight Motion `motionblur` / `motionblur2` / `motionblur4` — the honest approximation the roadmap
 * asks for: a directional blur along the layer's own velocity, resolved per frame. Pure Kotlin (no
 * Android classes) so it can be unit tested; the C++ side needs no change (the resolved op is the
 * existing FX_DIR_BLUR, and the unresolved type 55 is skipped by fxParseOps: it sits above FX_MAX).
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
 * say which kinds of motion smear. This cycle does POSITION only: the importer maps the effect
 * when `usePos` is on and warns that scale / rotation blur is not reproduced yet (those need a
 * radial blur along the scale / spin velocity — the next slice). A layer whose `usePos` is off is
 * skipped at import, because nothing it blurs is implemented yet.
 *
 * Maths — the layer moves (vx, vy) preview-px per frame (central difference of the evaluated pose
 * over one frame); the smear on screen should span that distance. The directional-blur shader
 * draws `lenPx = length / 100 * 160 * (H / 1080)` texture-px, so a smear of `speedFrac` of the
 * canvas height per frame needs `length = speedFrac * 1080 / 160 * 100 = speedFrac * 675` (the
 * texture height cancels: the same formula holds for canvas-sized overlay pictures and for media
 * textures). The strength multiplies it (`tune`).
 *
 * Angle: the shader's angle is counter-clockwise on screen (its direction is (cos a, -sin a) with
 * screen y down), so the canvas velocity (vx, vy, y down) is `atan2(-vy, vx)`; the picture is
 * turned clockwise by `rotationDeg` before it is drawn, which subtracts from a counter-clockwise
 * angle, so the picture-space angle is that plus `rotationDeg`. Verified against gl_math.h
 * (rotateZ(+deg) turns clockwise on screen) and the blur shader's own comment.
 *
 * Known approximations (the phone comparison will tune them):
 *  - the pose scale is ignored: a layer drawn at 50 % smears half as far on screen as it should
 *    (the length is exact at 100 % scale, off by the scale factor otherwise);
 *  - parent motion is ignored: the pose is sampled from the clip's own keyframes, so a layer that
 *    only moves because its parent group / null moves gets no blur (the effect ON the moving group
 *    itself, the common corpus case, is correct);
 *  - the preview assumes the 30 fps default frame (33 ms); the export uses the real setting.
 */
internal object MotionBlurEval {

    /** The pose of a layer at a time: preview-px from the canvas centre, rotation clockwise-positive. */
    data class Pose(val x: Float, val y: Float, val rotationDeg: Float)

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
     * The effects as the renderer should run them at [timeMs]: every enabled Motion Blur becomes a
     * directional blur along the layer's motion (trail 0 = smear both ways, like a shutter centred
     * on the frame); a Motion Blur on a still layer is dropped for that frame. Anything else passes
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
        return effects.mapNotNull { fx ->
            if (!fx.enabled || fx.type != ClipFxLibrary.MOTION_BLUR) return@mapNotNull fx
            val b = blurAt(poseAt, timeMs, frameMs, canvasHeightPx, (fx.values.getOrNull(0) ?: 100f) / 100f)
                ?: return@mapNotNull null
            fx.copy(type = ClipFxLibrary.DIR_BLUR, values = listOf(b.first, b.second, 0f))
        }
    }

    /**
     * The canvas height in preview-px for a surface of [surfaceW] x [surfaceH] whose preview width
     * is [previewWidthPx] (0 before the first preview: the surface size itself is the preview size).
     */
    fun canvasHeightPx(previewWidthPx: Float, surfaceW: Int, surfaceH: Int): Float =
        if (previewWidthPx > 0f && surfaceW > 0) previewWidthPx * surfaceH / surfaceW else surfaceH.toFloat()
}
