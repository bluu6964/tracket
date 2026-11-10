package com.example.engine

import com.example.model.AnimProp
import com.example.model.TimelineClip

/** Raw JNI functions of libam_engine.so (layer animation part, cpp/engine/anim_jni.cpp). Use [NativeLayerAnim]. */
internal object AnimNative {
    @JvmStatic external fun nativeCreate(
        meta: LongArray, stat: FloatArray, flags: Int, counts: IntArray, times: LongArray, vals: FloatArray,
        trackTimes: LongArray?, trackDx: FloatArray?, trackDy: FloatArray?,
        trackRot: FloatArray?, trackScale: FloatArray?
    ): Long

    @JvmStatic external fun nativeDestroy(p: Long)
    @JvmStatic external fun nativeExportSpeed(p: Long): Float
    @JvmStatic external fun nativeSourceMs(p: Long, timeMs: Long): Long
    @JvmStatic external fun nativeClipAt(p: Long, timeMs: Long, trackMs: Long, out: FloatArray)
    @JvmStatic external fun nativeEval(p: Long, timeMs: Long, outW: Int, outH: Int, previewW: Float, out: FloatArray)

    /** Pose of an upright text / shape picture that is turned in 3D or pivots off-centre; see [overlayPoseParams]. */
    @JvmStatic external fun nativeOverlayPose(input: FloatArray, out: FloatArray)

    /** 3D camera: corners of a layer on the canvas (cpp/engine/camera.h). false = behind the camera. */
    @JvmStatic external fun nativeCameraQuad(input: FloatArray, out: FloatArray): Boolean
    /** Distance of the layer's centre from the camera (bigger = farther); NaN = behind the camera. Same input as [nativeCameraQuad]. */
    @JvmStatic external fun nativeCameraDepth(input: FloatArray): Float
}

/**
 * Keyframes + motion tracking + pose of ONE video / photo layer, evaluated in C++ (cpp/engine/layer_anim.cpp).
 * Same numbers as [com.example.model.at] / [com.example.model.renderAt] / [layerPose] (those stay for the preview).
 * The frame result [eval]:
 *   [0] offsetX  [1] offsetY  [2] scaleX  [3] scaleY  [4] rotationDeg  [5] opacity 0..1
 *   [6] cropX  [7] cropY  [8] cropW  [9] cropH
 *   3D (v121): [10] rotX  [11] rotY (degrees)  [12] pivotX  [13] pivotY (px from the canvas centre)
 *   [14] baseX  [15] baseY (position after depth, px)  [16] perspD (camera distance, px)
 */
internal class NativeLayerAnim private constructor(private var ptr: Long) : AutoCloseable {

    private val out = FloatArray(17)

    /** Constant speed the export uses (average when the layer has speed keyframes). */
    val exportSpeed: Float get() = AnimNative.nativeExportSpeed(ptr)

    /** Source time (ms) shown at project time [timeMs]. */
    fun sourceMs(timeMs: Long): Long = AnimNative.nativeSourceMs(ptr, timeMs)

    /** The returned array is reused by the next call. */
    fun eval(timeMs: Long, outW: Int, outH: Int, previewWidthPx: Float): FloatArray {
        AnimNative.nativeEval(ptr, timeMs, outW, outH, previewWidthPx, out)
        return out
    }

    private val clipOut = FloatArray(21)

    /**
     * Preview: the clip fields at [timeMs] (keyframes + tracking of the picture at [trackMs]), same as Kotlin
     * renderAt. [0] posX [1] posY [2] posZ [3] scale% [4] rotationDeg [5] cropW% [6] cropH% [7] cropX% [8] cropY%
     * [9] opacity% [10] speed [11] rotX [12] rotY [13..14] fill a,b [15..16] grad1 a,b [17..18] grad2 a,b
     * [19] strokeTrimStart [20] strokeTrimEnd. The returned array is reused by the next call.
     */
    fun clipAt(timeMs: Long, trackMs: Long): FloatArray {
        AnimNative.nativeClipAt(ptr, timeMs, trackMs, clipOut)
        return clipOut
    }

    override fun close() {
        if (ptr != 0L) {
            AnimNative.nativeDestroy(ptr)
            ptr = 0L
        }
    }

    companion object {
        // MUST be the same order as enum Prop in layer_anim.h.
        private val ORDER = listOf(
            AnimProp.POSITION, AnimProp.SCALE, AnimProp.ROTATION, AnimProp.POS_Z,
            AnimProp.CROP_SIZE, AnimProp.CROP_POS, AnimProp.OPACITY, AnimProp.SPEED,
            AnimProp.ROT_X, AnimProp.ROT_Y, AnimProp.FILL_COLOR, AnimProp.GRAD_COLOR_1, AnimProp.GRAD_COLOR_2,
            AnimProp.STROKE_TRIM
        )

        /** Null when the native library is not available or the clip could not be handed over. */
        fun create(c: TimelineClip): NativeLayerAnim? {
            if (!DecoderPool.isAvailable) return null
            val counts = IntArray(ORDER.size)
            val times = ArrayList<Long>()
            val vals = ArrayList<Float>()
            for ((i, p) in ORDER.withIndex()) {
                val list = c.keyframes[p].orEmpty()
                counts[i] = list.size
                for (k in list) {
                    times.add(k.timeMs)
                    vals.add(k.a); vals.add(k.b)
                    vals.add(k.easing.x1); vals.add(k.easing.y1); vals.add(k.easing.x2); vals.add(k.easing.y2)
                }
            }
            val meta = longArrayOf(c.startMs, c.durationMs, c.trimStartMs)
            val stat = floatArrayOf(
                c.transformX, c.transformY, c.transformZ, c.scalePercent, c.rotationDeg, c.pivotX, c.pivotY,
                c.cropWidthPercent, c.cropHeightPercent, c.cropHorizontalPercent, c.cropVerticalPercent,
                c.opacity, c.speed, c.rotation3dX, c.rotation3dY, c.strokeTrimStart, c.strokeTrimEnd
            )
            val flags = (if (c.isFlippedH) 1 else 0) or (if (c.isFlippedV) 2 else 0)
            val f = c.trackFollow
            val p = AnimNative.nativeCreate(
                meta, stat, flags, counts, times.toLongArray(), vals.toFloatArray(),
                f?.timesMs, f?.dx, f?.dy, f?.rotDeg, f?.scale
            )
            return if (p != 0L) NativeLayerAnim(p) else null
        }
    }
}
