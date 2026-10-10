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
    /** Transform keyframes (sorted by time, measured from the layer start). Empty = fixed Transform. */
    val keyframes: List<TransformKeyframe> = emptyList(),
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
    val trimStartMs: Long = 0L
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
