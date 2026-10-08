package com.example.model

import java.util.UUID

/**
 * Timeline lane rules (pure functions, no Android dependencies).
 *
 * - The timeline is a list of lanes. `tracks[0]` is the TOP lane (drawn above the others),
 *   the last item is the BOTTOM lane.
 * - There is no limit on the number of lanes and no lane is special: media is a layer like any other.
 * - Layers may sit side by side in one lane, but two layers that overlap in time are never in the
 *   same lane: the later one is moved to another lane (a new lane is created when needed).
 * - Lanes only exist while they hold at least one clip.
 * - Clips inside a lane are always sorted by start time.
 */
object LaneLayout {

    fun newLane(clips: List<TimelineClip> = emptyList()): TimelineTrack = TimelineTrack(
        id = "lane_${UUID.randomUUID()}",
        name = "Layer",
        clips = clips
    )

    /**
     * Puts [clip] in the lowest existing lane that is free for its whole time range.
     * If every lane is busy there, a new lane is created above all others.
     */
    fun place(tracks: List<TimelineTrack>, clip: TimelineClip): List<TimelineTrack> {
        for (i in tracks.indices.reversed()) {
            val lane = tracks[i]
            if (lane.clips.none { it.overlaps(clip) }) {
                val result = tracks.toMutableList()
                result[i] = lane.copy(clips = (lane.clips + clip).sortedBy { it.startMs })
                return result
            }
        }
        return listOf(newLane(listOf(clip))) + tracks
    }

    /**
     * Repairs the layout after clips changed: overlapping clips are moved out of their lane
     * (the one that starts first keeps the lane), empty lanes are removed and every lane is sorted.
     */
    fun normalize(tracks: List<TimelineTrack>): List<TimelineTrack> {
        val overflow = mutableListOf<TimelineClip>()
        val kept = tracks.map { lane ->
            val accepted = mutableListOf<TimelineClip>()
            for (clip in lane.clips.sortedBy { it.startMs }) {
                if (accepted.none { it.overlaps(clip) }) accepted += clip else overflow += clip
            }
            lane.copy(clips = accepted)
        }.filter { it.clips.isNotEmpty() }

        var result = kept
        for (clip in overflow) result = place(result, clip)
        return result
    }
}
