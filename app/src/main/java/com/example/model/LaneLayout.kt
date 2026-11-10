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
     * Puts every clip of [clips] in its own lane on top, in ONE step: the first clip ends up on the
     * top lane, the last one right above the old lanes. (Calling [place] in a loop copies the whole
     * lane list once per clip: 200 captions = 20,000 copies.)
     */
    fun placeAll(tracks: List<TimelineTrack>, clips: List<TimelineClip>): List<TimelineTrack> {
        if (clips.isEmpty()) return tracks
        val out = ArrayList<TimelineTrack>(clips.size + tracks.size)
        for (c in clips) out += newLane(listOf(c))
        out.addAll(tracks)
        return out
    }

    /**
     * Ensures every lane has at most one clip. Extra clips each get their own lane on top.
     * Empty lanes are removed.
     * Exception: a lane with a Chain layer keeps the chain and every layer whose chainId is the chain's id (they sit side
     * by side in that one lane); a layer with a stale chainId becomes a normal layer.
     */
    fun normalize(tracks: List<TimelineTrack>): List<TimelineTrack> {
        val singles = mutableListOf<TimelineTrack>()
        val overflow = mutableListOf<TimelineClip>()

        for (lane in tracks) {
            val sorted = lane.clips.sortedBy { it.startMs }
            if (sorted.isEmpty()) continue
            val chain = sorted.firstOrNull { it.type == LayerType.CHAIN }
            if (chain != null) {
                val members = sorted.filter { it.id == chain.id || it.chainId == chain.id }
                val rest = sorted.filter { it.id != chain.id && it.chainId != chain.id }
                singles += lane.copy(clips = listOf(chain) + members.filter { it.id != chain.id })
                overflow += rest.map { if (it.chainId != null) it.copy(chainId = null) else it }
                continue
            }
            // Keep the first clip in this lane; any extras become their own lanes.
            val first = sorted.first()
            singles += lane.copy(clips = listOf(if (first.chainId != null) first.copy(chainId = null) else first))
            if (sorted.size > 1) overflow += sorted.drop(1).map { if (it.chainId != null) it.copy(chainId = null) else it }
        }

        var result: List<TimelineTrack> = singles
        for (clip in overflow) {
            result = place(result, clip)
        }
        return result
    }

    /** The layers inside the Chain [chainId] (not the chain itself). */
    fun childrenOf(tracks: List<TimelineTrack>, chainId: String): List<TimelineClip> =
        tracks.flatMap { it.clips }.filter { it.chainId == chainId }

    /**
     * Puts [clip] into the lane of the Chain [chainId], side by side with the layers already in it. The clip starts where it
     * wants to ([clip].startMs); when a layer is already there it goes right BEHIND it (after its end), still in this lane.
     * The chain then grows to cover its layers ([fitChains]). Without that chain: a normal new lane on top.
     */
    fun placeInChain(tracks: List<TimelineTrack>, chainId: String, clip: TimelineClip): List<TimelineTrack> {
        val li = tracks.indexOfFirst { lane -> lane.clips.any { it.id == chainId && it.type == LayerType.CHAIN } }
        if (li < 0) return place(tracks, clip.copy(chainId = null))
        val lane = tracks[li]
        val siblings = lane.clips.filter { it.chainId == chainId }.sortedBy { it.startMs }
        var start = clip.startMs.coerceAtLeast(0L)
        var moved = true
        var guard = 0
        while (moved && guard++ < 500) {
            moved = false
            for (s in siblings) {
                if (start < s.endMs && s.startMs < start + clip.durationMs) {
                    start = s.endMs
                    moved = true
                }
            }
        }
        val placed = clip.copy(startMs = start, chainId = chainId)
        val out = tracks.toMutableList()
        out[li] = lane.copy(clips = lane.clips + placed)
        return fitChains(out)
    }

    /** Every Chain layer covers exactly its layers (first start .. last end). A chain without layers keeps its own length. */
    fun fitChains(tracks: List<TimelineTrack>): List<TimelineTrack> {
        var changed = false
        val out = tracks.map { lane ->
            val chain = lane.clips.firstOrNull { it.type == LayerType.CHAIN } ?: return@map lane
            val kids = lane.clips.filter { it.chainId == chain.id }
            if (kids.isEmpty()) return@map lane
            val start = kids.minOf { it.startMs }
            val end = kids.maxOf { it.endMs }
            if (chain.startMs == start && chain.endMs == end) return@map lane
            changed = true
            lane.copy(clips = lane.clips.map { if (it.id == chain.id) it.copy(startMs = start, durationMs = end - start) else it })
        }
        return if (changed) out else tracks
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
