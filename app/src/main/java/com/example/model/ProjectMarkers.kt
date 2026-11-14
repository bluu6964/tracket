package com.example.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A NAMED timeline marker (build 188): a labelled point in project time, shown as a gold
 * flag on the timescale ruler. Unlike the anonymous beat marks ([ProjectSnapshot.beatMarksMs]),
 * a marker carries a name the user gave it ("Intro", "Drop", "Outro"...) and tapping it on
 * the ruler jumps the playhead to it.
 */
@Serializable
data class TimelineMarker(
    val id: String,
    /** Project time of the marker, ms (>= 0). */
    val timeMs: Long,
    val name: String
)

/**
 * Pure marker-list rules (no Android dependencies): the list is always kept sorted by time;
 * names are trimmed and never blank (a blank name falls back to "Marker" on add and is
 * refused on rename, so a marker can never lose its label by accident).
 */
object ProjectMarkers {

    const val FALLBACK_NAME = "Marker"

    /** Sorted by time, negative times dropped. The shape every stored list is kept in. */
    fun normalize(markers: List<TimelineMarker>): List<TimelineMarker> =
        markers.filter { it.timeMs >= 0L }.sortedBy { it.timeMs }

    /** Adds a marker at [timeMs] (clamped to >= 0) and returns the new sorted list. */
    fun add(
        markers: List<TimelineMarker>,
        timeMs: Long,
        name: String,
        id: String = UUID.randomUUID().toString()
    ): List<TimelineMarker> {
        val clean = name.trim().ifEmpty { FALLBACK_NAME }
        return normalize(markers + TimelineMarker(id, timeMs.coerceAtLeast(0L), clean))
    }

    /** Renames one marker; a blank name or an unknown id leaves the list unchanged. */
    fun rename(markers: List<TimelineMarker>, id: String, name: String): List<TimelineMarker> {
        val clean = name.trim()
        if (clean.isEmpty()) return markers
        return markers.map { if (it.id == id) it.copy(name = clean) else it }
    }

    /** Removes one marker (unknown id = unchanged list). */
    fun remove(markers: List<TimelineMarker>, id: String): List<TimelineMarker> =
        markers.filter { it.id != id }

    /**
     * The marker closest to [timeMs], or null when none is within [toleranceMs] of it.
     * Drives both the ruler tap (jump to the tapped marker) and the marker button
     * (a marker already at the playhead is edited instead of duplicated).
     */
    fun nearest(markers: List<TimelineMarker>, timeMs: Long, toleranceMs: Long): TimelineMarker? {
        val best = markers.minByOrNull { kotlin.math.abs(it.timeMs - timeMs) } ?: return null
        return if (kotlin.math.abs(best.timeMs - timeMs) <= toleranceMs) best else null
    }
}
