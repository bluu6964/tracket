package com.example.model

const val TIMELINE_PX_PER_MS = 0.045f

enum class AspectRatio(val label: String, val ratioFloat: Float, val iconDescription: String) {
    RATIO_16_9("16:9", 16f / 9f, "YouTube Landscape"),
    RATIO_9_16("9:16", 9f / 16f, "TikTok / Reels / Shorts"),
    RATIO_1_1("1:1", 1f, "Instagram Square"),
    RATIO_4_5("4:5", 4f / 5f, "Instagram Portrait"),
    RATIO_21_9("21:9", 21f / 9f, "Cinematic Ultrawide"),
    RATIO_4_3("4:3", 4f / 3f, "Standard Format")
}

enum class LayerType(val displayName: String) {
    VIDEO("Video"),
    AUDIO("Audio"),
    SHAPE("Shape"),
    OVERLAY("Overlay"),
    STICKER("Sticker"),
    TEXT("Text")
}

enum class PreviewResolution(val label: String, val detail: String) {
    FULL("1080p", "High Quality Preview"),
    HALF("720p", "Smooth Performance"),
    QUARTER("480p", "Fastest Editing")
}

data class TimelineClip(
    val id: String,
    val name: String,
    val type: LayerType,
    val startMs: Long,
    val durationMs: Long,
    val color: Long,
    val drawableResId: Int? = null,
    val text: String? = null,
    val textColor: Long = 0xFFFFFFFF,
    // ---- Text layer styling (only used by LayerType.TEXT) ----
    val fontFamily: String = "Roboto",
    val fontWeight: Int = 600,
    val isItalic: Boolean = false,
    val isUnderline: Boolean = false,
    val isStrikethrough: Boolean = false,
    /** 0 = left, 1 = center, 2 = right */
    val textAlign: Int = 1,
    /** Letter spacing, -10..40 (percent of the font size). */
    val letterSpacing: Float = 0f,
    /** Line height, 80..200 (percent). */
    val lineHeightPercent: Float = 120f,
    /** Text outline thickness, 0..100 (0 = no outline). */
    val textStrokeThickness: Float = 0f,
    val textStrokeColor: Long = 0xFF000000,
    /** Shadow: opacity 0 turns it off. All values 0..100 except angle (0..360). */
    val shadowOpacity: Float = 60f,
    val shadowBlur: Float = 30f,
    val shadowDistance: Float = 10f,
    val shadowAngle: Float = 45f,
    val shadowColor: Long = 0xFF000000,
    /** Background box behind the text: opacity 0 turns it off. */
    val bgOpacity: Float = 0f,
    val bgColor: Long = 0xFF000000,
    val bgCorner: Float = 20f,
    val bgPadding: Float = 30f,
    val shapePattern: String? = null,
    val shapeOffsetX: Float = 0f,
    val shapeOffsetY: Float = 0f,
    val shapeScaleX: Float = 1.0f,
    val shapeScaleY: Float = 1.0f,
    val strokeWidth: Float = 0f,
    val strokeColor: Long = 0xFFFFFFFF,
    // Transform parameters (2D, Pivot, 3D, Warp)
    val transformX: Float = 0f,
    val transformY: Float = 0f,
    val scalePercent: Float = 100f,
    val rotationDeg: Float = 0f,
    val pivotX: Float = 0.5f,
    val pivotY: Float = 0.5f,
    val rotation3dX: Float = 0f,
    val rotation3dY: Float = 0f,
    val warpDistort: Float = 0f,
    // Crop parameters
    val cropWidthPercent: Float = 100f,
    val cropHeightPercent: Float = 100f,
    val cropHorizontalPercent: Float = 50f,
    val cropVerticalPercent: Float = 50f,
    val cropPreset: String = "Free",
    val volume: Float = 1.0f,
    val speed: Float = 1.0f,
    val isMuted: Boolean = false,
    /** Audio layers: fade in / fade out length on the timeline, in ms (0 = no fade). */
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    val isLocked: Boolean = false,
    /** When false the layer is hidden on the canvas and skipped on export (eye control). */
    val isVisible: Boolean = true,
    val isFlippedH: Boolean = false,
    val isFlippedV: Boolean = false,
    val blendMode: String = "Normal",
    val effectName: String? = null,
    val animationName: String? = null,
    // Real media (null for text/shape/demo layers)
    val uri: String? = null,
    val mimeType: String? = null,
    val isImage: Boolean = false,
    val sourceDurationMs: Long = 0L,
    val trimStartMs: Long = 0L,
    /**
     * Transform keyframes keyed by property name
     * (e.g. "transformX", "transformY", "scalePercent", "rotationDeg", …).
     * Empty list = no animation; the static field value is used.
     * Keyframe times are RELATIVE to [startMs] so moving a layer keeps its animation.
     */
    val transformKeyframes: Map<String, List<TransformKeyframe>> = emptyMap()
) {
    val endMs: Long
        get() = startMs + durationMs

    fun isActiveAt(timeMs: Long): Boolean {
        return timeMs in startMs until endMs
    }

    /** True when the two clips share any part of the timeline (touching ends do not count). */
    fun overlaps(other: TimelineClip): Boolean {
        return startMs < other.endMs && other.startMs < endMs
    }

    /**
     * Evaluate a transform property at the absolute timeline time [timeMs] using keyframes + curves,
     * or fall back to the static value. Keyframe times are stored relative to [startMs], so moving
     * the layer on the timeline keeps its animation attached.
     */
    fun evaluateTransform(property: String, timeMs: Long, staticValue: Float): Float {
        val kfs = transformKeyframes[property]
        if (kfs.isNullOrEmpty()) return staticValue
        return interpolateKeyframes(kfs, timeMs - startMs)
    }

    /** Keyframes of [property] with absolute timeline times (what the UI draws and edits). */
    fun keyframesAbs(property: String): List<TransformKeyframe> =
        transformKeyframes[property].orEmpty().map { it.copy(timeMs = it.timeMs + startMs) }

    /** True when any transform property has a keyframe within [toleranceMs] of the absolute time. */
    fun hasKeyframeNear(absTimeMs: Long, toleranceMs: Long = 40L, properties: Collection<String>? = null): Boolean {
        val rel = absTimeMs - startMs
        return transformKeyframes.any { (prop, list) ->
            (properties == null || prop in properties) && list.any { kotlin.math.abs(it.timeMs - rel) <= toleranceMs }
        }
    }

    /** Moves every keyframe by [deltaMs] (relative time) — used when the layer start moves but the content must stay put. */
    fun withKeyframesShifted(deltaMs: Long): TimelineClip {
        if (deltaMs == 0L || transformKeyframes.isEmpty()) return this
        return copy(transformKeyframes = shiftKeyframes(transformKeyframes, deltaMs))
    }
}

/** Easing / curve type applied between two consecutive keyframes. CUSTOM uses the keyframe's own bezier handles. */
enum class EasingType {
    LINEAR,
    EASE_IN,
    EASE_OUT,
    EASE_IN_OUT,
    EASE_IN_CUBIC,
    EASE_OUT_CUBIC,
    EASE_IN_OUT_CUBIC,
    HOLD,
    CUSTOM
}

/**
 * Cubic-bezier easing handles of one segment, CSS style: the curve runs from (0,0) to (1,1) and
 * (x1,y1) / (x2,y2) are the two control points. x must stay in 0..1 (time never runs backwards);
 * y may leave 0..1 to overshoot (bounce / anticipation).
 */
data class BezierHandles(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    fun sanitized() = BezierHandles(
        x1.coerceIn(0f, 1f), y1.coerceIn(BEZIER_Y_MIN, BEZIER_Y_MAX),
        x2.coerceIn(0f, 1f), y2.coerceIn(BEZIER_Y_MIN, BEZIER_Y_MAX)
    )

    companion object {
        const val BEZIER_Y_MIN = -1f
        const val BEZIER_Y_MAX = 2f
    }
}

/** The bezier handles behind each preset, so the editor can show (and start editing from) the real curve. */
fun easingHandles(type: EasingType): BezierHandles = when (type) {
    EasingType.LINEAR, EasingType.HOLD, EasingType.CUSTOM -> BezierHandles(0.33f, 0.33f, 0.67f, 0.67f)
    EasingType.EASE_IN -> BezierHandles(0.11f, 0f, 0.5f, 0f)
    EasingType.EASE_OUT -> BezierHandles(0.5f, 1f, 0.89f, 1f)
    EasingType.EASE_IN_OUT -> BezierHandles(0.45f, 0f, 0.55f, 1f)
    EasingType.EASE_IN_CUBIC -> BezierHandles(0.32f, 0f, 0.67f, 0f)
    EasingType.EASE_OUT_CUBIC -> BezierHandles(0.33f, 1f, 0.68f, 1f)
    EasingType.EASE_IN_OUT_CUBIC -> BezierHandles(0.65f, 0f, 0.35f, 1f)
}

/**
 * A single keyframe on a transform property graph.
 * [timeMs] is relative to the layer's start (so the animation travels with the layer).
 * [value] is the property value at that time.
 * [easing] / [handles] control interpolation toward the *next* keyframe.
 */
data class TransformKeyframe(
    val timeMs: Long,
    val value: Float,
    val easing: EasingType = EasingType.LINEAR,
    /** Only used when [easing] is CUSTOM. */
    val handles: BezierHandles? = null
) {
    /** The bezier handles that actually shape the segment after this keyframe. */
    fun curve(): BezierHandles =
        if (easing == EasingType.CUSTOM) (handles ?: easingHandles(EasingType.LINEAR)) else easingHandles(easing)
}

private fun bezierAxis(s: Float, a: Float, b: Float): Float {
    val u = 1f - s
    return 3f * u * u * s * a + 3f * u * s * s * b + s * s * s
}

private fun bezierAxisSlope(s: Float, a: Float, b: Float): Float {
    val u = 1f - s
    return 3f * u * u * a + 6f * u * s * (b - a) + 3f * s * s * (1f - b)
}

/** y of the cubic bezier (0,0)-(x1,y1)-(x2,y2)-(1,1) at time fraction [x] ∈ [0,1]. */
fun cubicBezierEase(x: Float, h: BezierHandles): Float {
    val t = x.coerceIn(0f, 1f)
    if (t == 0f || t == 1f) return t
    if (h.x1 == h.y1 && h.x2 == h.y2) return t // straight line
    // Newton–Raphson, with bisection as a fallback when the slope is flat.
    var s = t
    for (i in 0 until 8) {
        val err = bezierAxis(s, h.x1, h.x2) - t
        if (kotlin.math.abs(err) < 1e-5f) return bezierAxis(s, h.y1, h.y2)
        val slope = bezierAxisSlope(s, h.x1, h.x2)
        if (kotlin.math.abs(slope) < 1e-4f) break
        s = (s - err / slope).coerceIn(0f, 1f)
    }
    var lo = 0f
    var hi = 1f
    s = t
    for (i in 0 until 24) {
        val err = bezierAxis(s, h.x1, h.x2) - t
        if (kotlin.math.abs(err) < 1e-5f) break
        if (err > 0f) hi = s else lo = s
        s = (lo + hi) / 2f
    }
    return bezierAxis(s, h.y1, h.y2)
}

/** Apply a preset easing curve to normalized t ∈ [0, 1]. */
fun applyEasing(t: Float, type: EasingType): Float {
    val x = t.coerceIn(0f, 1f)
    return when (type) {
        EasingType.HOLD -> 0f
        EasingType.LINEAR -> x
        else -> cubicBezierEase(x, easingHandles(type))
    }
}

/** Eased progress (0..1, may overshoot with custom handles) of the segment that starts at [from]. */
fun applyKeyframeCurve(t: Float, from: TransformKeyframe): Float {
    val x = t.coerceIn(0f, 1f)
    return when (from.easing) {
        EasingType.HOLD -> 0f
        EasingType.LINEAR -> x
        else -> cubicBezierEase(x, from.curve())
    }
}

/** Interpolate a sorted list of keyframes at [timeMs] (same time base as the keyframes). */
fun interpolateKeyframes(keyframes: List<TransformKeyframe>, timeMs: Long): Float {
    if (keyframes.isEmpty()) return 0f
    val sorted = if (keyframes.size < 2 || keyframes.zipWithNext().all { it.first.timeMs <= it.second.timeMs }) keyframes
    else keyframes.sortedBy { it.timeMs }
    if (timeMs <= sorted.first().timeMs) return sorted.first().value
    if (timeMs >= sorted.last().timeMs) return sorted.last().value

    for (i in 0 until sorted.size - 1) {
        val a = sorted[i]
        val b = sorted[i + 1]
        if (timeMs in a.timeMs..b.timeMs) {
            val span = (b.timeMs - a.timeMs).toFloat().coerceAtLeast(1f)
            val t = (timeMs - a.timeMs) / span
            val eased = applyKeyframeCurve(t, a)
            return a.value + (b.value - a.value) * eased
        }
    }
    return sorted.last().value
}

/**
 * Insert or update a keyframe at [timeMs] for the given property. Returns new map.
 * Updating an existing keyframe keeps its curve unless [easing] / [handles] are given.
 */
fun upsertKeyframe(
    map: Map<String, List<TransformKeyframe>>,
    property: String,
    timeMs: Long,
    value: Float,
    easing: EasingType? = null,
    handles: BezierHandles? = null,
    toleranceMs: Long = 40L
): Map<String, List<TransformKeyframe>> {
    val existing = map[property].orEmpty().toMutableList()
    val idx = existing.indexOfFirst { kotlin.math.abs(it.timeMs - timeMs) <= toleranceMs }
    if (idx >= 0) {
        val old = existing[idx]
        existing[idx] = old.copy(
            value = value,
            easing = easing ?: old.easing,
            handles = if (easing != null || handles != null) handles else old.handles
        )
    } else {
        existing.add(TransformKeyframe(timeMs, value, easing ?: EasingType.LINEAR, handles))
    }
    return map + (property to existing.sortedBy { it.timeMs })
}

/**
 * Move one keyframe in time and value in a single step, keeping its curve. The new time is clamped
 * between its neighbours so keyframes can never swap places or merge by accident.
 */
fun moveKeyframe(
    map: Map<String, List<TransformKeyframe>>,
    property: String,
    oldTimeMs: Long,
    newTimeMs: Long,
    newValue: Float,
    minGapMs: Long = 50L,
    toleranceMs: Long = 40L
): Map<String, List<TransformKeyframe>> {
    val list = map[property].orEmpty().sortedBy { it.timeMs }
    val idx = list.indexOfFirst { kotlin.math.abs(it.timeMs - oldTimeMs) <= toleranceMs }
    if (idx < 0) return map
    val lo = if (idx > 0) list[idx - 1].timeMs + minGapMs else Long.MIN_VALUE
    val hi = if (idx < list.lastIndex) list[idx + 1].timeMs - minGapMs else Long.MAX_VALUE
    val t = if (lo > hi) list[idx].timeMs else newTimeMs.coerceIn(lo, hi)
    val out = list.toMutableList()
    out[idx] = list[idx].copy(timeMs = t, value = newValue)
    return map + (property to out)
}

/** Remove keyframe nearest to [timeMs] (within tolerance). */
fun removeKeyframe(
    map: Map<String, List<TransformKeyframe>>,
    property: String,
    timeMs: Long,
    toleranceMs: Long = 40L
): Map<String, List<TransformKeyframe>> {
    val existing = map[property].orEmpty().filter {
        kotlin.math.abs(it.timeMs - timeMs) > toleranceMs
    }
    return if (existing.isEmpty()) map - property else map + (property to existing)
}

/** Set a preset easing on the keyframe at/near [timeMs] (affects the segment to the next kf). */
fun setKeyframeEasing(
    map: Map<String, List<TransformKeyframe>>,
    property: String,
    timeMs: Long,
    easing: EasingType,
    toleranceMs: Long = 40L
): Map<String, List<TransformKeyframe>> {
    val existing = map[property].orEmpty().map {
        if (kotlin.math.abs(it.timeMs - timeMs) <= toleranceMs) it.copy(easing = easing, handles = null) else it
    }
    return map + (property to existing)
}

/** Set custom bezier handles on the keyframe at/near [timeMs] (the segment to the next kf becomes CUSTOM). */
fun setKeyframeCurve(
    map: Map<String, List<TransformKeyframe>>,
    property: String,
    timeMs: Long,
    handles: BezierHandles,
    toleranceMs: Long = 40L
): Map<String, List<TransformKeyframe>> {
    val clean = handles.sanitized()
    val existing = map[property].orEmpty().map {
        if (kotlin.math.abs(it.timeMs - timeMs) <= toleranceMs) it.copy(easing = EasingType.CUSTOM, handles = clean) else it
    }
    return map + (property to existing)
}

/** Shift all keyframe times by [deltaMs] (keeps them ≥ 0 relative to the layer start is NOT enforced: content may be trimmed away). */
fun shiftKeyframes(
    map: Map<String, List<TransformKeyframe>>,
    deltaMs: Long
): Map<String, List<TransformKeyframe>> =
    if (deltaMs == 0L) map else map.mapValues { (_, list) -> list.map { it.copy(timeMs = it.timeMs + deltaMs) } }

/**
 * One horizontal line (lane) of the timeline (Alight Motion style).
 * Lanes are not tied to a layer type: any layer (media, shape, text, audio, ...) can live in any lane.
 * Each lane holds at most one clip (one layer per line).
 */
data class TimelineTrack(
    val id: String,
    val name: String,
    val isMuted: Boolean = false,
    val isLocked: Boolean = false,
    val clips: List<TimelineClip> = emptyList()
)

data class ExportSettings(
    val resolution: String = "1080p FHD",
    val frameRate: Int = 30,
    val quality: String = "High (Recommended)",
    val format: String = "MP4 (H.264)"
)

/** Result of reading a picked file: what the importer hands to the ViewModel. */
data class ImportedMedia(
    val uri: String,
    val name: String,
    val mimeType: String?,
    val durationMs: Long,
    val isImage: Boolean
)

/**
 * Fade multiplier (0..1) of an audio layer at [relMs] ms after the layer's start on the timeline.
 * Volume is applied separately (clip.volume). Used by the preview and mirrored by the exporter.
 */
fun TimelineClip.fadeGainAt(relMs: Long): Float {
    var g = 1f
    if (fadeInMs > 0L && relMs < fadeInMs) {
        g = minOf(g, relMs.coerceAtLeast(0L) / fadeInMs.toFloat())
    }
    val left = durationMs - relMs
    if (fadeOutMs > 0L && left < fadeOutMs) {
        g = minOf(g, left.coerceAtLeast(0L) / fadeOutMs.toFloat())
    }
    return g.coerceIn(0f, 1f)
}
