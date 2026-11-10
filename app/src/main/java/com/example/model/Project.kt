package com.example.model

import kotlinx.serialization.Serializable

/**
 * Everything of a project that is saved to disk (project.json) and brought back when the app opens again.
 * New fields must have a default value: old files then still load (missing keys use the default).
 * Undo / redo history and the selection are NOT saved.
 */
@Serializable
data class ProjectSnapshot(
    val version: Int = CURRENT_VERSION,
    val tracks: List<TimelineTrack> = emptyList(),
    val aspectRatio: AspectRatio = AspectRatio.RATIO_16_9,
    val canvasColor: Long = 0xFF000000L,
    val exportSettings: ExportSettings = ExportSettings(),
    val previewResolution: PreviewResolution = PreviewResolution.FULL,
    val isMasterMuted: Boolean = false,
    val playheadMs: Long = 0L,
    /** Beat marks on the timeline (project time, ms, sorted). */
    val beatMarksMs: List<Long> = emptyList()
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/** One line of the project list (meta.json next to project.json). */
@Serializable
data class ProjectMeta(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val durationMs: Long = 0L,
    val layerCount: Int = 0
)
