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
    val trackIndex: Int,
    val name: String,
    val type: LayerType,
    val startMs: Long,
    val durationMs: Long,
    val color: Long,
    val drawableResId: Int? = null,
    val text: String? = null,
    val textColor: Long = 0xFFFFFFFF,
    val shapePattern: String? = null,
    val volume: Float = 1.0f,
    val speed: Float = 1.0f,
    val isMuted: Boolean = false,
    val isLocked: Boolean = false,
    val isFlippedH: Boolean = false,
    val isFlippedV: Boolean = false,
    val blendMode: String = "Normal",
    val effectName: String? = null,
    val animationName: String? = null,
    val mediaUri: String? = null
) {
    val endMs: Long
        get() = startMs + durationMs

    fun isActiveAt(timeMs: Long): Boolean {
        return timeMs in startMs until endMs
    }
}

data class TimelineTrack(
    val id: String,
    val name: String,
    val type: LayerType,
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
