package com.example.engine

import com.example.model.FILL_NONE
import com.example.model.FILL_SOLID
import com.example.model.FILL_VIDEO
import com.example.model.IMG_FIT
import com.example.model.IMG_STRETCH
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.depthX
import com.example.model.depthY
import com.example.model.shapeDrawScaleX
import com.example.model.shapeDrawScaleY
import kotlin.math.max
import kotlin.math.min

/**
 * Video fills inside shapes (build 104): an Alight Motion shape layer can be filled with a playing
 * video instead of a colour (`fillType="media"` + a `fillVideo` file) — the shape's outline is the
 * window and the video shows through it, following the layer's transform and keyframes. The corpus
 * holds 56 such shapes (37 on built-in kinds, 19 on freehand path quads); before this build the
 * importer read only `fillImage`, so every one of them rendered as an empty outline.
 *
 * This file is the pure, testable core the two render engines share:
 *  - [mediaFillClipOf] decides whether an overlay group (the engines' unit for text / shape layers)
 *    is a single video-filled shape that can be composited live, rather than rasterised flat.
 *  - [silhouetteCopy] / [strokeOnlyCopy] split the shape into the two pictures the composite needs:
 *    a white silhouette (the window the video is cut by) and the outline-only picture (stroke,
 *    shadow, background box), which is drawn over the video exactly like the plain raster was.
 *  - [fillSourceMs] maps a timeline time to the fill file's position: the shape element's own
 *    `inTime` window (stored on the clip's [TimelineClip.trimStartMs]) played linearly at the
 *    clip's speed, like a media layer — no looping, the decoder holds the last frame past the end.
 *  - [pose] places the video frame so it covers the shape's box the way the shape painters place a
 *    picture fill ([com.example.engine.FillShaders.buildImageShader] semantics): IMG_FILL crops to
 *    cover, IMG_FIT letterboxes inside, IMG_STRETCH distorts to the box exactly.
 *
 * Pose / repeat groups and repeat copies fall back to the rasterised poster frame (the painters
 * resolve a video fill to its first frame), which keeps those paths pixel-identical to a still fill.
 */

/** True when [clip] is a shape whose fill is a video file (imported from AM or set by hand). */
fun TimelineClip.isVideoFilledShape(): Boolean =
    type == LayerType.SHAPE && fillMode == FILL_VIDEO && !fillVideoUri.isNullOrBlank()

/**
 * The video-fill routing decision for one overlay group: a group holding exactly one video-filled
 * shape composites that shape's video live; anything else (several layers, an image fill, a plain
 * shape) returns null and the group rasterises as before.
 */
fun mediaFillClipOf(groupClips: List<TimelineClip>): TimelineClip? =
    groupClips.singleOrNull()?.takeIf { it.isVideoFilledShape() }

/**
 * The window the video is cut by: the same shape with a plain white fill and no stroke, so its
 * raster is a pure alpha silhouette (fill colour and stroke live in [strokeOnlyCopy]'s picture).
 */
fun TimelineClip.silhouetteCopy(): TimelineClip =
    copy(fillMode = FILL_SOLID, textColor = 0xFFFFFFFFL, strokeWidth = 0f)

/** The shape with its fill removed: stroke, shadow and background box only — drawn over the video. */
fun TimelineClip.strokeOnlyCopy(): TimelineClip =
    copy(fillMode = FILL_NONE)

/**
 * Where the fill video is at timeline time [timeMs]: its window starts at the clip's
 * [TimelineClip.trimStartMs] and advances at the clip's speed, exactly like a media layer's
 * `sourceMsAt`. Clamped into the file when its duration is known (the decoder would hold the last
 * frame anyway; clamping keeps seeks inside the stream).
 */
fun fillSourceMs(clip: TimelineClip, timeMs: Long, videoDurationMs: Long): Long {
    var s = clip.trimStartMs + ((timeMs - clip.startMs) * clip.speed).toLong()
    if (videoDurationMs > 0L) s = min(s, videoDurationMs - 1L)
    return s.coerceAtLeast(0L)
}

/** Placement of a video frame inside a shape's box, in the native compositor's layer terms. */
data class FillVideoPose(
    /** Offset of the frame centre from the canvas centre, px. */
    val offsetX: Float,
    val offsetY: Float,
    /** Extra scale on top of the compositor's own fit-to-canvas; negative = flipped. */
    val scaleX: Float,
    val scaleY: Float,
    val rotationDeg: Float
)

/**
 * The pose that maps a [contentW] x [contentH] video frame into [clip]'s shape box on a
 * [frameW] x [frameH] canvas, mirroring how the shape painters place a picture fill: the box is
 * the 130 dp shape square scaled by [k] (frame px per preview px) and the layer's draw scales,
 * centred on the layer's position; the mode ([TimelineClip.fillImageMode]) picks cover / fit /
 * stretch. The returned scales are relative to the compositor's drawLayer fit
 * (min(frameW/contentW, frameH/contentH)), so engines can hand them straight to a LayerParams.
 * Null when the frame size is not known yet (the caller falls back to the poster raster).
 */
fun fillVideoPose(
    clip: TimelineClip,
    frameW: Int,
    frameH: Int,
    k: Float,
    density: Float,
    contentW: Int,
    contentH: Int
): FillVideoPose? {
    if (contentW <= 0 || contentH <= 0 || frameW <= 0 || frameH <= 0) return null
    val half = 65f * density * k
    val boxW = 2f * half * clip.shapeDrawScaleX
    val boxH = 2f * half * clip.shapeDrawScaleY
    val fit = min(frameW.toFloat() / contentW, frameH.toFloat() / contentH)
    if (fit <= 0f) return null
    var sx: Float
    var sy: Float
    when (clip.fillImageMode) {
        IMG_STRETCH -> {
            sx = boxW / (contentW * fit)
            sy = boxH / (contentH * fit)
        }
        IMG_FIT -> {
            val s = min(boxW / contentW, boxH / contentH) / fit
            sx = s; sy = s
        }
        else -> { // IMG_FILL: cover the box, centre-cropping the overflow
            val s = max(boxW / contentW, boxH / contentH) / fit
            sx = s; sy = s
        }
    }
    // The layer's own flips fold into the placement, like the painters fold them into the picture.
    if (clip.isFlippedH) sx = -sx
    if (clip.isFlippedV) sy = -sy
    return FillVideoPose(
        offsetX = clip.depthX * k,
        offsetY = clip.depthY * k,
        scaleX = sx,
        scaleY = sy,
        rotationDeg = clip.rotationDeg
    )
}
