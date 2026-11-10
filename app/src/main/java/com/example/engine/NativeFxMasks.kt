package com.example.engine

import com.example.model.TimelineClip
import java.io.File

/**
 * Cutout / face blur mask pictures of one video frame as GL textures (decoded in C++). Shared by the C++ export
 * ([NativeProjectExporter], strict = a mask that cannot be decoded stops the export) and the C++ preview
 * ([NativePreviewEngine], not strict = that frame simply shows no effect), so both pick the same file.
 */
internal object NativeFxMasks {

    /** Mask frame index for source time [srcMs] (same rounding as CutoutMasks / FaceBlurMasks). */
    fun maskIndex(srcMs: Long, startMs: Long, fps: Float, frames: Int): Int =
        (((srcMs - startMs) * fps / 1000f) + 0.5f).toInt().coerceIn(0, frames - 1)

    /** Texture of the saved cutout mask of this frame, 0 = clip has no cutout (or no mask for this frame). */
    fun cutout(renderer: NativeRenderer, c: TimelineClip, layerIndex: Int, srcMs: Long, strict: Boolean): Int {
        val dir = c.cutoutDir ?: return 0
        if (c.isImage || c.cutoutFrames <= 0 || c.cutoutFps <= 0f) return 0
        val f = File(dir, CutoutMasks.fileName(maskIndex(srcMs, c.cutoutStartMs, c.cutoutFps, c.cutoutFrames)))
        if (!f.exists()) return 0 // same as Media3: a missing mask = no effect for that frame
        val tex = renderer.loadMask(layerIndex * 2, f.absolutePath)
        if (tex == 0 && strict) error("Native decoder could not read cutout mask ${f.name}.")
        return tex
    }

    /** Same for the face blur mask. */
    fun face(renderer: NativeRenderer, c: TimelineClip, layerIndex: Int, srcMs: Long, strict: Boolean): Int {
        val dir = c.faceBlurDir ?: return 0
        if (c.isImage || c.faceBlurFrames <= 0 || c.faceBlurFps <= 0f) return 0
        val f = File(dir, FaceBlurMasks.fileName(maskIndex(srcMs, c.faceBlurStartMs, c.faceBlurFps, c.faceBlurFrames)))
        if (!f.exists()) return 0
        val tex = renderer.loadMask(layerIndex * 2 + 1, f.absolutePath)
        if (tex == 0 && strict) error("Native decoder could not read face blur mask ${f.name}.")
        return tex
    }
}
