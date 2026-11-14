package com.example.model

/**
 * The export range (build 199, audit #2 item A10): in/out points on the timeline ruler.
 * When a range is set, exports render only that window of the project; unset, exports render
 * the whole project exactly as before. The points are editor state saved with the project
 * (additive snapshot fields, the same family as the markers of build 188); [of] is the single
 * place that decides whether two stored points form a usable range, so an old file, a hand
 * edit, or a project that shrank under its points can never produce a broken export.
 */
data class ExportRange(val inMs: Long, val outMs: Long) {

    val lengthMs: Long get() = outMs - inMs

    companion object {
        /** Shorter than this is a mis-tap, not a range. */
        const val MIN_LENGTH_MS = 100L

        /**
         * The range for two stored points against a project of [totalMs], or null when there is
         * no usable range: a point missing, no project length, or the points (after clamping
         * into the project) closer together than [MIN_LENGTH_MS].
         */
        fun of(inMs: Long?, outMs: Long?, totalMs: Long): ExportRange? {
            if (inMs == null || outMs == null || totalMs <= 0L) return null
            val i = inMs.coerceIn(0L, totalMs)
            val o = outMs.coerceIn(0L, totalMs)
            return if (o - i >= MIN_LENGTH_MS) ExportRange(i, o) else null
        }
    }
}

/**
 * The tracks of [range] alone, re-based so the range starts at 0 (pure; build 199, A10).
 *
 * Clips outside the window drop; clips crossing an edge are trimmed with the SPLIT law
 * (TimelineOps' second half): the source offset advances by the cut amount, keyframes shift
 * with the cut, and a fade (or in/out animation marker) that belonged to the cut-away edge is
 * cleared — a window that starts mid-fade must not fade in from a frame the export never had.
 * Clip ids and every link (group, chain, parent, controller) are untouched, so chains, groups
 * and rigs inside the window behave exactly as they do in the full project.
 */
fun windowTracksForRange(tracks: List<TimelineTrack>, range: ExportRange): List<TimelineTrack> {
    val out = tracks.map { lane ->
        lane.copy(clips = lane.clips.mapNotNull { c ->
            if (c.endMs <= range.inMs || c.startMs >= range.outMs) return@mapNotNull null
            val headCut = (range.inMs - c.startMs).coerceAtLeast(0L)
            val tailCut = (c.endMs - range.outMs).coerceAtLeast(0L)
            if (headCut == 0L && tailCut == 0L) {
                // Fully inside: only the re-base shift.
                return@mapNotNull c.copy(startMs = c.startMs - range.inMs)
            }
            var nc = c.copy(
                startMs = c.startMs + headCut - range.inMs,
                durationMs = c.durationMs - headCut - tailCut
            )
            if (headCut > 0L) {
                nc = nc.copy(
                    trimStartMs = nc.trimStartMs + nc.sourceOffsetMs(headCut),
                    fadeInMs = 0L
                ).withShiftedKeyframes(-headCut).withoutClipAnim(AnimSlot.IN)
            }
            if (tailCut > 0L) {
                nc = nc.copy(fadeOutMs = 0L).withoutClipAnim(AnimSlot.OUT)
            }
            nc
        })
    }
    return LaneLayout.normalize(out)
}
