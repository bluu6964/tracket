package com.example.model

import kotlinx.serialization.Serializable

/**
 * Build 201 (audit S-5): the solo session as DATA, not ViewModel memory.
 *
 * Until 200 the remembered pre-solo flags lived only in the ViewModel
 * (a Map<String, Pair<Boolean, Boolean>>): undo/redo and project loads wiped
 * the memory while the solo marker and the rewritten flags survived, so after
 * any history travel the solo could never be un-soloed back — the restore set
 * was simply gone. Now the session is a value: [ProjectSnapshot.solo] persists
 * it with the project (additive field — old files load with no session, older
 * apps skip the key), and the undo history carries it beside the tracks, so
 * tracks and session always travel together and can never desync.
 */
@Serializable
data class SoloRestoreEntry(
    val clipId: String,
    val isVisible: Boolean,
    val isMuted: Boolean
)

/** An active solo: which layer is soloed + the flags every layer had before. */
@Serializable
data class SoloState(
    val clipId: String,
    val restore: List<SoloRestoreEntry>
)

/** The pure flag math of solo, exactly the ViewModel's pre-201 semantics. */
object SoloLaw {

    /** Remember every layer's current flags for [clipId]'s solo session. */
    fun capture(tracks: List<TimelineTrack>, clipId: String): SoloState =
        SoloState(
            clipId,
            tracks.flatMap { it.clips }.map { SoloRestoreEntry(it.id, it.isVisible, it.isMuted) }
        )

    /**
     * The solo arrangement: the soloed layer is made visible (its mute is NOT
     * touched — a muted layer stays muted, as before); every other layer is
     * hidden and muted.
     */
    fun soloed(tracks: List<TimelineTrack>, clipId: String): List<TimelineTrack> =
        tracks.map { lane ->
            lane.copy(clips = lane.clips.map { c ->
                when {
                    c.id == clipId -> if (c.isVisible) c else c.copy(isVisible = true)
                    else -> c.copy(isVisible = false, isMuted = true)
                }
            })
        }

    /**
     * Back to the remembered flags. Layers with no entry (added after the
     * capture) keep their current flags; entries for layers that are gone are
     * simply unused.
     */
    fun restored(tracks: List<TimelineTrack>, state: SoloState): List<TimelineTrack> {
        val saved = state.restore.associateBy { it.clipId }
        return tracks.map { lane ->
            lane.copy(clips = lane.clips.map { c ->
                val s = saved[c.id] ?: return@map c
                c.copy(isVisible = s.isVisible, isMuted = s.isMuted)
            })
        }
    }

    /** A session is only meaningful while its layer exists. */
    fun isValidFor(state: SoloState?, tracks: List<TimelineTrack>): Boolean =
        state != null && tracks.any { lane -> lane.clips.any { it.id == state.clipId } }
}
