package com.example.engine

import com.example.model.AnimProp
import com.example.model.DEPTH_D
import com.example.model.TimelineClip
import com.example.model.depthX
import com.example.model.depthY
import com.example.model.effectiveScalePercent

/**
 * A text / shape / sticker layer that is turned in 3D, or pivots around a point other than the centre of its box
 * (v126). Such a layer is not drawn into the shared overlay picture: it gets its own picture, drawn UPRIGHT (scale and
 * flips baked in, no rotation, no move), and the C++ renderer places that picture with the same 3D matrix it uses for
 * video / photo layers. The numbers come from cpp/engine/overlay_pose.cpp (tested on a PC).
 * Keyframed 3D angles count too: the layer then stays in this path for its whole length, so its group never changes.
 */
internal fun overlayNeedsPose(c: TimelineClip): Boolean =
    c.rotation3dX != 0f || c.rotation3dY != 0f || c.pivotX != 0.5f || c.pivotY != 0.5f ||
        !c.keyframes[AnimProp.ROT_X].isNullOrEmpty() || !c.keyframes[AnimProp.ROT_Y].isNullOrEmpty()

/**
 * The layer as the upright picture shows it: everything the renderer applies (move, turn, 3D, pivot) is reset. Draw
 * this clip into the picture, and use it as the cache key of the picture, so a layer that only turns is not redrawn.
 */
internal fun uprightForPose(c: TimelineClip): TimelineClip =
    c.copy(transformX = 0f, transformY = 0f, rotationDeg = 0f, rotation3dX = 0f, rotation3dY = 0f, pivotX = 0.5f, pivotY = 0.5f)

private val poseIn = FloatArray(13)
private val poseOut = FloatArray(11)

/**
 * LayerParams for the upright picture of [c] (the clip as it is at this time: keyframes applied).
 * [boxW] / [boxH] = the layer's own box at scale 1 in output pixels, [k] = output pixels per preview pixel.
 * The opacity is left at 1: it is baked into the picture, or given by the caller to the blend shader.
 */
@Synchronized
internal fun overlayPoseParams(c: TimelineClip, boxW: Float, boxH: Float, k: Float): LayerParams {
    poseIn[0] = boxW
    poseIn[1] = boxH
    poseIn[2] = (c.effectiveScalePercent / 100f).coerceAtLeast(0.01f)
    poseIn[3] = if (c.isFlippedH) 1f else 0f
    poseIn[4] = if (c.isFlippedV) 1f else 0f
    poseIn[5] = c.rotationDeg
    poseIn[6] = c.rotation3dX
    poseIn[7] = c.rotation3dY
    poseIn[8] = c.pivotX
    poseIn[9] = c.pivotY
    poseIn[10] = c.depthX * k
    poseIn[11] = c.depthY * k
    poseIn[12] = DEPTH_D * k  // the same camera as the Z depth and the video layers (layer_anim.cpp)
    AnimNative.nativeOverlayPose(poseIn, poseOut)
    val p = LayerParams()
    p.offsetX = poseOut[0]
    p.offsetY = poseOut[1]
    p.rotationDeg = poseOut[2]
    p.rotX = poseOut[3]
    p.rotY = poseOut[4]
    p.pivotX = poseOut[5]
    p.pivotY = poseOut[6]
    p.baseX = poseOut[7]
    p.baseY = poseOut[8]
    p.perspD = poseOut[9]
    return p
}

/** Box of a shape / sticker layer at scale 1: the editor draws a 130 dp square ([density] = screen density). */
internal fun shapeBoxPx(density: Float, k: Float): Float = 130f * density * k
