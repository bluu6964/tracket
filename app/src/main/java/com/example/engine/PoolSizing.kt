package com.example.engine

import com.example.model.TimelineClip

/** How many decoders the pool must be allowed to hold: one per video file that is on screen at the same moment. */
internal object PoolSizing {
    fun maxOverlap(clips: List<TimelineClip>): Int {
        val v = clips.filter { !it.isImage && it.uri != null }
        if (v.isEmpty()) return 1
        var best = 1
        for (c in v) {
            var n = 0
            for (o in v) if (o.startMs <= c.startMs && c.startMs < o.endMs) n++
            if (n > best) best = n
        }
        return best
    }

    fun hw(clips: List<TimelineClip>): Int = maxOverlap(clips).coerceIn(2, 8)
}
