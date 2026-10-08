package com.example.model

import java.util.UUID

/**
 * Timeline lane rules — Alight Motion style (pure functions, no Android dependencies).
 *
 * - The timeline is a list of lanes. `tracks[0]` is the TOP lane (drawn above the others),
 *   the last item is the BOTTOM lane.
 * - **One layer per line**: each lane holds at most one clip. Layers never sit side by side.
 * - A new layer always gets its own new lane (created on top).
 * - Lanes only exist while they hold a clip.
 * - Vertical reorder changes stacking order (top = drawn on top in the canvas / export).
 */
object LaneLayout {

    fun newLane(clips: List<TimelineClip> = emptyList()): TimelineTrack = TimelineTrack(
        id = "lane_${UUID.randomUUID()}",
        name = "Layer",
        clips = clips
    )

    /**
     * Puts [clip] in its own exclusive lane on top (Alight Motion: 1 layer = 1 line).
     */
    fun place(tracks: List<TimelineTrack>, clip: TimelineClip): List<TimelineTrack> {
        return listOf(newLane(listOf(clip))) + tracks
    }

    /**
     * Ensures every lane has at most one clip. Extra clips each get their own lane on top.
     * Empty lanes are removed.
     */
    fun normalize(tracks: List<TimelineTrack>): List<TimelineTrack> {
        val singles = mutableListOf<TimelineTrack>()
        val overflow = mutableListOf<TimelineClip>()

        for (lane in tracks) {
            val sorted = lane.clips.sortedBy { it.startMs }
            if (sorted.isEmpty()) continue
            // Keep the first clip in this lane; any extras become their own lanes.
            singles += lane.copy(clips = listOf(sorted.first()))
            if (sorted.size > 1) overflow += sorted.drop(1)
        }

        var result = singles
        for (clip in overflow) {
            result = place(result, clip)
        }
        return result
    }

    /** Moves the lane at [fromIndex] to [toIndex] (stacking order). Indices are clamped. */
    fun reorder(tracks: List<TimelineTrack>, fromIndex: Int, toIndex: Int): List<TimelineTrack> {
        if (tracks.isEmpty()) return tracks
        val from = fromIndex.coerceIn(0, tracks.lastIndex)
        val to = toIndex.coerceIn(0, tracks.lastIndex)
        if (from == to) return tracks
        val list = tracks.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        return list
    }

    /**
     * Moves [clipId] earlier/later on the timeline by [deltaMs].
     * Start is clamped to ≥ 0. Does not change duration.
     */
    fun shiftClipTime(
        tracks: List<TimelineTrack>,
        clipId: String,
        deltaMs: Long
    ): List<TimelineTrack> {
        return tracks.map { lane ->
            lane.copy(
                clips = lane.clips.map { c ->
                    if (c.id != clipId) c
                    else c.copy(startMs = (c.startMs + deltaMs).coerceAtLeast(0L))
                }
            )
        }
    }

    /** Sets absolute start time of [clipId] (ms ≥ 0). */
    fun setClipStart(
        tracks: List<TimelineTrack>,
        clipId: String,
        startMs: Long
    ): List<TimelineTrack> {
        return tracks.map { lane ->
            lane.copy(
                clips = lane.clips.map { c ->
                    if (c.id != clipId) c
                    else c.copy(startMs = startMs.coerceAtLeast(0L))
                }
            )
        }
    }
}
