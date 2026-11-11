package com.example.engine

import com.example.model.LayerType
import com.example.model.ProjectSnapshot
import com.example.model.TimelineClip

/**
 * Media substitution for imported projects (build 111).
 *
 * Alight Motion presets are traded as XML files, and their media references point at the ORIGINAL
 * author's phone: `content://media/external/...` uris of that device's media store, or `am:<hash>`
 * names of files embedded in the author's Alight library. Neither exists on this phone, so
 * [AlightMediaLocalizer] leaves those layers pointing at files that will never load — they render
 * empty (transparent since build 110) and stay useless. Alight itself expects the user to drop their
 * own picture into a "Logo Here" placeholder; this object is the pure half of the same offer here:
 *
 *  - [collect] lists every still-unresolvable media reference of a project, one entry per distinct
 *    file, labelled by the first layer that uses it, with the kind of file the picker must offer.
 *  - [substitute] re-points every layer that used one such file at a replacement the user picked
 *    (the Android half copies the pick into the project's own storage first, through the same
 *    [MediaImporter.copyToImports] path as the import-time copy, so the replacement is packed by
 *    [ProjectArchive] exactly like any localised media).
 *
 * A reference counts as unresolvable when it is an `am:` embedded name, an `am-asset:` token whose
 * file was not inside a project file ([ProjectArchive.TOKEN]), or a foreign `content://` uri — after
 * the import-time copy those are precisely the references that failed to localise. A reference that
 * was never attempted (an owned copy, a plain file path) is not listed.
 */
object MissingMedia {

    /** What the picker must offer for a missing file. */
    enum class Kind(val pickerLabel: String) {
        IMAGE("picture"), VIDEO("video"), AUDIO("sound")
    }

    /**
     * One missing file. [label] = the name of the first layer that uses it (AM names media layers
     * after their file, e.g. "images.png"); [where] = which slot of the layer points at it;
     * [clipIds] = every layer that uses the file (a preset's pill picture is often shared by groups).
     */
    data class Item(
        val sourceUri: String,
        val label: String,
        val kind: Kind,
        val where: String,
        val clipIds: List<String>
    ) {
        val layerCount: Int get() = clipIds.size
    }

    /** True when [ref] names a file this phone cannot produce: an embedded / token name, or a foreign uri. */
    fun isUnresolvable(ref: String?, ownAuthority: String): Boolean {
        if (ref == null) return false
        if (ref.startsWith("am:") || ref.startsWith("am-asset:")) return true
        return AlightMediaLocalizer.needsCopy(ref, ownAuthority)
    }

    /** Every missing file of [snapshot], in first-use order, one entry per distinct file. */
    fun collect(snapshot: ProjectSnapshot, ownAuthority: String): List<Item> {
        val bySource = LinkedHashMap<String, Item>()
        fun note(ref: String?, clip: TimelineClip, kind: Kind, where: String) {
            if (!isUnresolvable(ref, ownAuthority)) return
            val src = ref!!
            val old = bySource[src]
            bySource[src] = if (old == null) {
                Item(src, clip.name, kind, where, listOf(clip.id))
            } else if (clip.id in old.clipIds) {
                old
            } else {
                old.copy(clipIds = old.clipIds + clip.id)
            }
        }
        for (c in snapshot.tracks.flatMap { it.clips }) {
            note(c.uri, c, kindOfLayer(c), "Layer file")
            note(c.fillImageUri, c, Kind.IMAGE, "Picture fill")
            note(c.fillVideoUri, c, Kind.VIDEO, "Video fill")
        }
        return bySource.values.toList()
    }

    /** The kind of file a media layer itself is (its own [TimelineClip.uri]). */
    private fun kindOfLayer(c: TimelineClip): Kind = when {
        c.type == LayerType.AUDIO -> Kind.AUDIO
        c.isImage -> Kind.IMAGE
        c.type == LayerType.VIDEO -> Kind.VIDEO
        c.mimeType?.startsWith("audio/") == true -> Kind.AUDIO
        c.mimeType?.startsWith("image/") == true -> Kind.IMAGE
        c.mimeType?.startsWith("video/") == true -> Kind.VIDEO
        else -> Kind.VIDEO
    }

    /**
     * [snapshot] with every layer that pointed at [sourceUri] now pointing at [newUri] instead
     * (all three media slots). A source nothing uses returns the snapshot unchanged.
     */
    fun substitute(snapshot: ProjectSnapshot, sourceUri: String, newUri: String): ProjectSnapshot {
        if (sourceUri == newUri) return snapshot
        var touched = false
        val tracks = snapshot.tracks.map { track ->
            track.copy(clips = track.clips.map { c ->
                var out = c
                if (c.uri == sourceUri) { out = out.copy(uri = newUri); touched = true }
                if (c.fillImageUri == sourceUri) { out = out.copy(fillImageUri = newUri); touched = true }
                if (c.fillVideoUri == sourceUri) { out = out.copy(fillVideoUri = newUri); touched = true }
                out
            })
        }
        return if (touched) snapshot.copy(tracks = tracks) else snapshot
    }
}
