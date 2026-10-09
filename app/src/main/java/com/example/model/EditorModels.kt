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

    /** Evaluate a transform property at [timeMs] using keyframes + easing, or fall back to static value. */
    fun evaluateTransform(property: String, timeMs: Long, staticValue: Float): Float {
        val kfs = transformKeyframes[property].orEmpty().sortedBy { it.timeMs }
        if (kfs.isEmpty()) return staticValue
        return interpolateKeyframes(kfs, timeMs)
    }
}

/** Easing / curve type applied between two consecutive keyframes. */
enum class EasingType {
    LINEAR,
    EASE_IN,
    EASE_OUT,
    EASE_IN_OUT,
    EASE_IN_CUBIC,
    EASE_OUT_CUBIC,
    EASE_IN_OUT_CUBIC,
    HOLD
}

/**
 * A single keyframe on a transform property graph.
 * [timeMs] is absolute timeline time.
 * [value] is the property value at that time.
 * [easing] controls interpolation toward the *next* keyframe.
 */
data class TransformKeyframe(
    val timeMs: Long,
    val value: Float,
    val easing: EasingType = EasingType.LINEAR
)

/** Apply easing curve to normalized t ∈ [0, 1]. */
fun applyEasing(t: Float, type: EasingType): Float {
    val x = t.coerceIn(0f, 1f)
    return when (type) {
        EasingType.LINEAR -> x
        EasingType.HOLD -> 0f
        EasingType.EASE_IN -> x * x
        EasingType.EASE_OUT -> 1f - (1f - x) * (1f - x)
        EasingType.EASE_IN_OUT -> if (x < 0.5f) 2f * x * x else 1f - (-2f * x + 2f).let { it * it } / 2f
        EasingType.EASE_IN_CUBIC -> x * x * x
        EasingType.EASE_OUT_CUBIC -> 1f - (1f - x).let { it * it * it }
        EasingType.EASE_IN_OUT_CUBIC ->
            if (x < 0.5f) 4f * x * x * x
            else 1f - (-2f * x + 2f).let { it * it * it } / 2f
    }
}

/** Interpolate a sorted list of keyframes at [timeMs]. */
fun interpolateKeyframes(keyframes: List<TransformKeyframe>, timeMs: Long): Float {
    if (keyframes.isEmpty()) return 0f
    if (timeMs <= keyframes.first().timeMs) return keyframes.first().value
    if (timeMs >= keyframes.last().timeMs) return keyframes.last().value

    for (i in 0 until keyframes.size - 1) {
        val a = keyframes[i]
        val b = keyframes[i + 1]
        if (timeMs in a.timeMs..b.timeMs) {
            val span = (b.timeMs - a.timeMs).toFloat().coerceAtLeast(1f)
            val t = (timeMs - a.timeMs) / span
            val eased = applyEasing(t, a.easing)
            return a.value + (b.value - a.value) * eased
        }
    }
    return keyframes.last().value
}

/** Insert or update a keyframe at [timeMs] for the given property. Returns new map. */
fun upsertKeyframe(
    map: Map<String, List<TransformKeyframe>>,
    property: String,
    timeMs: Long,
    value: Float,
    easing: EasingType = EasingType.LINEAR,
    toleranceMs: Long = 40L
): Map<String, List<TransformKeyframe>> {
    val existing = map[property].orEmpty().toMutableList()
    val idx = existing.indexOfFirst { kotlin.math.abs(it.timeMs - timeMs) <= toleranceMs }
    if (idx >= 0) {
        existing[idx] = existing[idx].copy(value = value, easing = easing)
    } else {
        existing.add(TransformKeyframe(timeMs, value, easing))
    }
    return map + (property to existing.sortedBy { it.timeMs })
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

/** Set the easing of the keyframe at/near [timeMs] (affects segment to next kf). */
fun setKeyframeEasing(
    map: Map<String, List<TransformKeyframe>>,
    property: String,
    timeMs: Long,
    easing: EasingType,
    toleranceMs: Long = 40L
): Map<String, List<TransformKeyframe>> {
    val existing = map[property].orEmpty().map {
        if (kotlin.math.abs(it.timeMs - timeMs) <= toleranceMs) it.copy(easing = easing) else it
    }
    return map + (property to existing)
}

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
