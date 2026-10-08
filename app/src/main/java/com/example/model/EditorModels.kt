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
    val text: String? = null,
    val textColor: Long = 0xFFFFFFFF,
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
    val speed: Float = 1.0f,
    val isMuted: Boolean = false,
    val isFlippedH: Boolean = false,
    val isFlippedV: Boolean = false,
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
 * One horizontal line (lane) of the timeline. Lanes are not tied to a layer type: any layer
 * (media, shape, text, audio, ...) can live in any lane. Clips inside a lane never overlap in time.
 */
data class TimelineTrack(
    val id: String,
    val name: String,
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
