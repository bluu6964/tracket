package com.example.model

import java.util.UUID

/**
 * One timeline row is exactly one layer. This mirrors a layer stack editor: a row can never
 * contain two clips, even when their time ranges do not overlap.
 */
object LaneLayout {

    fun newLane(clip: TimelineClip): TimelineTrack = TimelineTrack(
        id = "lane_${UUID.randomUUID()}",
        name = clip.name,
        clips = listOf(clip)
    )

    /** Always creates a dedicated row for the layer. [insertTop] controls stacking order. */
    fun place(
        tracks: List<TimelineTrack>,
        clip: TimelineClip,
        insertTop: Boolean = true
    ): List<TimelineTrack> {
        val lane = newLane(clip)
        return if (insertTop) listOf(lane) + tracks else tracks + lane
    }

    /**
     * Repairs old/legacy timelines so every row contains one clip. Existing row order is kept
     * and multiple clips from one row become consecutive rows.
     */
    fun normalize(tracks: List<TimelineTrack>): List<TimelineTrack> {
        return tracks.flatMap { track ->
            track.clips.sortedBy { it.startMs }.map { clip ->
                track.copy(
                    id = "lane_${UUID.randomUUID()}",
                    name = clip.name,
                    clips = listOf(clip)
                )
            }
        }
    }
}
