package com.example.engine

import com.example.model.DEPTH_D
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.TimelineTrack
import com.example.model.depthFactorOf

/**
 * 3D camera (v151): a Camera layer looks at every layer in the lanes BELOW it (layers above it, like captions, stay as
 * they are). The camera's own Transform is the camera: position X / Y = pan, Z = dolly (forward), scale = zoom, rotation =
 * roll, 3D rotation X / Y = orbit around the point it looks at. All of it can be keyframed. The maths is C++
 * (cpp/engine/camera.cpp, host-tested): a layer under a camera is drawn on the 4 projected corners of its picture, with
 * the renderer's corner pin path. A camera can have a Null parent too ([ParentRig]).
 * Preview and export use this one class. Stacking: under a camera every layer (pictures AND text / shape / sticker) is
 * ordered by its distance from the camera ([stackKey]): the farthest is drawn first, so a camera that dollies, orbits or
 * rolls puts the layers in the right front-to-back order, and a layer the camera passed is not drawn.
 */
internal class CameraRig(tracks: List<TimelineTrack>, private val parents: ParentRig) {
    private class Cam(val lane: Int, val clip: TimelineClip)

    private val laneOf = HashMap<String, Int>()
    private val cams = ArrayList<Cam>()

    init {
        tracks.forEachIndexed { i, tr ->
            for (c in tr.clips) {
                laneOf[c.id] = i
                if (c.type == LayerType.CAMERA && c.isVisible) cams.add(Cam(i, c))
            }
        }
    }

    val hasCamera: Boolean get() = cams.isNotEmpty()

    /** True when the layer is under a camera lane (at any time): text / shape layers then get their own upright picture. */
    fun affects(c: TimelineClip): Boolean {
        val l = laneOf[c.id] ?: return false
        return cams.any { it.lane < l }
    }

    /** [panX, panY, dolly, zoom, rotX, rotY, roll] of the camera that looks at [c] at [t] (positions in output px), or null. */
    private fun poseFor(c: TimelineClip, t: Long, k: Float): FloatArray? {
        val l = laneOf[c.id] ?: return null
        val cam = cams.filter { it.lane < l && it.clip.isActiveAt(t) }.maxByOrNull { it.lane } ?: return null
        val r = parents.adjusted(cam.clip, t)
        return floatArrayOf(
            r.transformX * k, r.transformY * k, r.transformZ * k, (r.scalePercent / 100f).coerceAtLeast(0.001f),
            r.rotation3dX, r.rotation3dY, r.rotationDeg
        )
    }

    /**
     * Stacking key of a layer for the draw order (bigger = nearer = drawn later; the same scale as a plain Z in preview px, so
     * layers a camera does not look at can be compared with it). [c] = the layer as it is at [t] (keyframes and Null parent
     * applied). Null = no camera looks at it: the caller uses its own Z.
     */
    @Synchronized
    fun stackKey(c: TimelineClip, t: Long): Float? {
        if (cams.isEmpty()) return null
        val cam = poseFor(c, t, 1f) ?: return null
        java.util.Arrays.fill(inA, 0f)
        inA[6] = c.transformX; inA[7] = c.transformY
        inA[8] = c.transformZ
        inA[11] = c.rotationDeg; inA[12] = c.rotation3dX; inA[13] = c.rotation3dY
        inA[16] = DEPTH_D
        inA[17] = 1f
        inA[18] = cam[0]; inA[19] = cam[1]; inA[20] = cam[2]; inA[21] = cam[3]
        inA[22] = cam[4]; inA[23] = cam[5]; inA[24] = cam[6]
        val d = AnimNative.nativeCameraDepth(inA)
        return if (d.isNaN()) BEHIND_KEY else -d
    }

    /** True when a camera looks at [c] at [t]. */
    fun activeFor(c: TimelineClip, t: Long): Boolean = hasCamera && poseFor(c, t, 1f) != null

    /** Result of a camera lookup: [quad] = corners (TL, TR, BR, BL, canvas px from the top-left), null = behind the camera (not drawn). */
    class Seen(val quad: FloatArray?)

    private val inA = FloatArray(kCamIn)
    private val outA = FloatArray(8)

    private fun cast(cam: FloatArray): Seen {
        inA[17] = 1f
        inA[18] = cam[0]; inA[19] = cam[1]; inA[20] = cam[2]; inA[21] = cam[3]
        inA[22] = cam[4]; inA[23] = cam[5]; inA[24] = cam[6]
        return Seen(if (AnimNative.nativeCameraQuad(inA, outA)) outA.copyOf() else null)
    }

    /**
     * A video / photo layer. [fp] = its animation numbers ([NativeLayerAnim.eval], Null parent already applied), [z] = its Z
     * (preview px), [contentW] / [contentH] = the whole picture. Null = no camera looks at it now (draw it as usual).
     */
    @Synchronized
    fun seeMedia(
        c: TimelineClip, t: Long, fp: FloatArray, z: Float, contentW: Int, contentH: Int, k: Float, canvasW: Int, canvasH: Int
    ): Seen? {
        if (cams.isEmpty()) return null
        val cam = poseFor(c, t, k) ?: return null
        val f = depthFactorOf(z)  // fp is after the depth factor; the camera does the depth itself
        java.util.Arrays.fill(inA, 0f)
        inA[0] = canvasW.toFloat(); inA[1] = canvasH.toFloat()
        inA[2] = contentW.toFloat(); inA[3] = contentH.toFloat()
        inA[4] = fp[8]; inA[5] = fp[9]
        inA[6] = fp[14] / f; inA[7] = fp[15] / f
        inA[8] = z * k
        inA[9] = fp[2] / f; inA[10] = fp[3] / f
        inA[11] = fp[4]; inA[12] = fp[10]; inA[13] = fp[11]
        inA[14] = fp[12]; inA[15] = fp[13]
        inA[16] = DEPTH_D * k
        return cast(cam)
    }

    /**
     * A text / shape / sticker layer, drawn upright into a canvas-sized picture by [uprightForCamera] ([c] = the layer as it
     * is at [t], Null parent applied). [boxW] / [boxH] = its box at scale 1 (output px). Null = no camera looks at it now.
     */
    @Synchronized
    fun seeOverlay(
        c: TimelineClip, t: Long, boxW: Float, boxH: Float, k: Float, canvasW: Int, canvasH: Int
    ): Seen? {
        if (cams.isEmpty()) return null
        val cam = poseFor(c, t, k) ?: return null
        // The picture has the scale and flips baked in (about the box centre): the pivot is where the pivot point of the
        // box lands in it, and the base is the move plus what the baked scale shifted (same as overlay_pose.cpp).
        val s = (c.scalePercent / 100f).coerceAtLeast(0.01f)
        val fx = if (c.isFlippedH) -1f else 1f
        val fy = if (c.isFlippedV) -1f else 1f
        val px = (c.pivotX - 0.5f) * boxW
        val py = (c.pivotY - 0.5f) * boxH
        val dx = px * (1f - s * fx)
        val dy = py * (1f - s * fy)
        java.util.Arrays.fill(inA, 0f)
        inA[0] = canvasW.toFloat(); inA[1] = canvasH.toFloat()
        inA[2] = canvasW.toFloat(); inA[3] = canvasH.toFloat()
        inA[4] = 1f; inA[5] = 1f
        inA[6] = c.transformX * k + dx; inA[7] = c.transformY * k + dy
        inA[8] = c.transformZ * k
        inA[9] = 1f; inA[10] = 1f
        inA[11] = c.rotationDeg; inA[12] = c.rotation3dX; inA[13] = c.rotation3dY
        inA[14] = px - dx; inA[15] = py - dy
        inA[16] = DEPTH_D * k
        return cast(cam)
    }

    companion object {
        /** Stacking key of a layer that is behind the camera (it is not drawn, so only has to sort first). */
        const val BEHIND_KEY = -1.0e9f
        private const val kCamIn = 25
    }
}

/**
 * The layer as its upright picture shows it under a camera: move, turn, 3D, pivot and the depth are all left to the camera
 * (scale and flips stay in the picture). Draw this clip, and use it as the cache key of the picture.
 */
internal fun uprightForCamera(c: TimelineClip): TimelineClip =
    c.copy(
        transformX = 0f, transformY = 0f, transformZ = 0f, rotationDeg = 0f,
        rotation3dX = 0f, rotation3dY = 0f, pivotX = 0.5f, pivotY = 0.5f
    )
