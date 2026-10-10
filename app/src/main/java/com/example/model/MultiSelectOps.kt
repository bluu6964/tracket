package com.example.model

import java.util.UUID

/** Which option group the multi-select bar shows (it depends on where the playhead is). */
enum class MultiBarMode {
    /** Playhead is inside at least one selected layer: delete-left / split / delete-right. */
    SPLIT,
    /** Playhead is before the layers: extend start to playhead / move start to playhead. */
    START,
    /** Playhead is after all layers: extend end to playhead / move end to playhead. */
    END
}

/** Every button of the multi-select bar. */
enum class MultiAction {
    DELETE_LEFT,
    SPLIT,
    DELETE_RIGHT,
    EXTEND_START,
    MOVE_START,
    EXTEND_END,
    MOVE_END,
    ALIGN_START,
    SEQUENCE,
    ALIGN_END
}

/**
 * Multi-select operations (pure functions, no Android dependencies).
 * Each function returns the new lane list, or null when nothing changed
 * (so the ViewModel does not push an empty undo step).
 */
object MultiSelectOps {

    private const val MIN_LEN = 100L

    fun barMode(selected: List<TimelineClip>, playheadMs: Long): MultiBarMode {
        if (selected.isEmpty()) return MultiBarMode.SPLIT
        if (selected.any { playheadMs > it.startMs && playheadMs < it.endMs }) return MultiBarMode.SPLIT
        if (selected.all { it.endMs <= playheadMs }) return MultiBarMode.END
        return MultiBarMode.START
    }

    fun apply(
        tracks: List<TimelineTrack>,
        ids: Set<String>,
        action: MultiAction,
        playheadMs: Long
    ): List<TimelineTrack>? {
        val selected = tracks.flatMap { it.clips }.filter { it.id in ids }
        if (selected.isEmpty()) return null
        val p = playheadMs

        return when (action) {
            MultiAction.SPLIT -> split(tracks, ids, p)

            MultiAction.DELETE_LEFT -> mapSelected(tracks, ids) { c ->
                if (p > c.startMs && c.endMs - p >= MIN_LEN) {
                    val d = p - c.startMs
                    c.withKeyframesShiftedForStart(d).copy(
                        startMs = p,
                        durationMs = c.durationMs - d,
                        trimStartMs = if (hasSource(c)) c.trimStartMs + (d * c.speed).toLong() else c.trimStartMs,
                        fadeInMs = 0L
                    )
                } else c
            }

            MultiAction.DELETE_RIGHT -> mapSelected(tracks, ids) { c ->
                if (p - c.startMs >= MIN_LEN && p < c.endMs) {
                    c.copy(durationMs = p - c.startMs, fadeOutMs = 0L)
                } else c
            }

            MultiAction.EXTEND_START -> mapSelected(tracks, ids) { c ->
                if (c.startMs > p) {
                    var d = c.startMs - p
                    if (hasSource(c)) {
                        // Media can only grow backwards as far as its in-point allows.
                        val spd = c.speed.coerceAtLeast(0.01f)
                        d = minOf(d, (c.trimStartMs / spd).toLong())
                    }
                    if (d <= 0L) c else c.withKeyframesShiftedForStart(-d).copy(
                        startMs = c.startMs - d,
                        durationMs = c.durationMs + d,
                        trimStartMs = if (hasSource(c)) (c.trimStartMs - (d * c.speed).toLong()).coerceAtLeast(0L) else c.trimStartMs
                    )
                } else c
            }

            MultiAction.MOVE_START -> mapSelected(tracks, ids) { c ->
                if (c.startMs > p) c.copy(startMs = p) else c
            }

            MultiAction.EXTEND_END -> mapSelected(tracks, ids) { c ->
                if (c.endMs < p) {
                    var d = p - c.endMs
                    if (hasSource(c) && c.sourceDurationMs > 0L) {
                        val spd = c.speed.coerceAtLeast(0.01f)
                        val maxLen = ((c.sourceDurationMs - c.trimStartMs) / spd).toLong()
                        d = minOf(d, (maxLen - c.durationMs).coerceAtLeast(0L))
                    }
                    if (d <= 0L) c else c.copy(durationMs = c.durationMs + d)
                } else c
            }

            MultiAction.MOVE_END -> mapSelected(tracks, ids) { c ->
                if (c.endMs < p) c.copy(startMs = (p - c.durationMs).coerceAtLeast(0L)) else c
            }

            MultiAction.ALIGN_START -> {
                val target = selected.minOf { it.startMs }
                mapSelected(tracks, ids) { c -> c.copy(startMs = target) }
            }

            MultiAction.ALIGN_END -> {
                val target = selected.maxOf { it.endMs }
                mapSelected(tracks, ids) { c ->
                    c.copy(startMs = (target - c.durationMs).coerceAtLeast(0L))
                }
            }

            MultiAction.SEQUENCE -> {
                // One after another, in the order they currently start (top lane first on ties).
                val laneOf = HashMap<String, Int>()
                tracks.forEachIndexed { i, lane -> lane.clips.forEach { laneOf[it.id] = i } }
                val ordered = selected.sortedWith(compareBy({ it.startMs }, { laneOf[it.id] ?: 0 }))
                var cursor = ordered.first().startMs
                val newStart = HashMap<String, Long>()
                for (c in ordered) {
                    newStart[c.id] = cursor
                    cursor += c.durationMs
                }
                mapSelected(tracks, ids) { c -> c.copy(startMs = newStart[c.id] ?: c.startMs) }
            }
        }
    }

    private fun hasSource(c: TimelineClip) = c.uri != null && !c.isImage

    private fun mapSelected(
        tracks: List<TimelineTrack>,
        ids: Set<String>,
        edit: (TimelineClip) -> TimelineClip
    ): List<TimelineTrack>? {
        var changed = false
        val out = tracks.map { lane ->
            lane.copy(clips = lane.clips.map { c ->
                if (c.id in ids) {
                    val n = edit(c)
                    if (n != c) changed = true
                    n
                } else c
            })
        }
        return if (changed) out else null
    }

    /** Splits every selected layer that the playhead is inside. The right halves get their own new lanes. */
    private fun split(tracks: List<TimelineTrack>, ids: Set<String>, p: Long): List<TimelineTrack>? {
        val rightHalves = mutableListOf<TimelineClip>()
        val mapped = tracks.map { lane ->
            lane.copy(clips = lane.clips.map { c ->
                if (c.id in ids && p - c.startMs >= MIN_LEN && c.endMs - p >= MIN_LEN) {
                    val d1 = p - c.startMs
                    val (leftKeys, rightKeys) = c.splitKeyframes(p)
                    rightHalves += c.copy(
                        id = UUID.randomUUID().toString(),
                        name = "${c.name} (Split)",
                        startMs = p,
                        durationMs = c.endMs - p,
                        trimStartMs = c.trimStartMs + (d1 * c.speed).toLong(),
                        fadeInMs = 0L,
                        keyframes = rightKeys
                    )
                    c.copy(durationMs = d1, fadeOutMs = 0L, keyframes = leftKeys)
                } else c
            })
        }
        if (rightHalves.isEmpty()) return null
        var result = LaneLayout.normalize(mapped)
        for (part in rightHalves) result = LaneLayout.place(result, part)
        return result
    }
}
