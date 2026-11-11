package com.example.engine

import com.example.model.LayerType
import com.example.model.ProjectSnapshot

/**
 * Post-import step for Alight Motion XML imports: media layers point at content:// files on the phone
 * that exported the XML (a shape's picture fill, an audio layer's file). Those links usually do not
 * resolve on this phone — and never survive the source being deleted — so importing copies each file
 * into the app's own media storage and rewrites the layer to the copy.
 *
 * This object is the pure mapping half (no Android, unit-tested): it walks the parsed [ProjectSnapshot],
 * asks [resolve] once per distinct foreign content:// uri, and rewrites every layer that used it. The
 * Android half (bytes through a ContentResolver, into the same `imports` pool as [MediaImporter.read])
 * is [MediaImporter.copyToImports], wired in by the import screen. A file that cannot be copied keeps
 * its original uri — the import never fails over media — and its layers are named in the returned
 * warnings instead.
 */
object AlightMediaLocalizer {

    /** [copiedFiles] = distinct files copied; [failedNames] = names of the layers whose file stayed linked. */
    data class Result(
        val snapshot: ProjectSnapshot,
        val warnings: List<String>,
        val copiedFiles: Int,
        val failedNames: List<String>
    )

    /** A uri this step should try to copy: a content:// uri that is not already one of the app's own files. */
    fun needsCopy(uri: String?, ownAuthority: String): Boolean {
        if (uri == null || !uri.startsWith("content://")) return false
        val authority = uri.removePrefix("content://").substringBefore('/')
        return authority.isNotEmpty() && authority != ownAuthority
    }

    /**
     * Rewrites every foreign content:// media uri of [snapshot] through [resolve] (source uri -> the
     * project's own uri for the copy, null when the copy could not be made). [warnings] are the importer's
     * notes: notes that a copied file made stale are dropped, one note per failed file is appended.
     */
    fun localize(
        snapshot: ProjectSnapshot,
        warnings: List<String>,
        ownAuthority: String,
        resolve: (String) -> String?
    ): Result {
        // Distinct foreign uris, in first-use order; each is resolved exactly once however many
        // layers use it (ST Intro's pill picture is referenced by two groups).
        val sources = LinkedHashSet<String>()
        for (c in snapshot.tracks.flatMap { it.clips }) {
            if (needsCopy(c.uri, ownAuthority)) sources += c.uri!!
            if (needsCopy(c.fillImageUri, ownAuthority)) sources += c.fillImageUri!!
        }
        if (sources.isEmpty()) return Result(snapshot, warnings, 0, emptyList())

        val owned = HashMap<String, String>()   // source uri -> the project copy's uri
        val failed = HashSet<String>()
        for (src in sources) {
            val dst = runCatching { resolve(src) }.getOrNull()
            if (dst != null && dst != src) owned[src] = dst else failed += src
        }

        val failedNames = ArrayList<String>()
        val copiedAudioNames = ArrayList<String>()
        val tracks = snapshot.tracks.map { track ->
            track.copy(clips = track.clips.map { c ->
                var out = c
                val u = c.uri
                if (u != null && u in owned) {
                    out = out.copy(uri = owned[u])
                    if (c.type == LayerType.AUDIO) copiedAudioNames += c.name
                }
                if (u != null && u in failed && c.name !in failedNames) failedNames += c.name
                val f = c.fillImageUri
                if (f != null && f in owned) out = out.copy(fillImageUri = owned[f])
                if (f != null && f in failed && c.name !in failedNames) failedNames += c.name
                out
            })
        }

        // An audio layer whose file WAS copied no longer needs the importer's "pick the file again"
        // note (it names the layer in quotes) — drop it. Every failed file gets a fresh note.
        var outWarnings = warnings
        if (copiedAudioNames.isNotEmpty()) {
            outWarnings = outWarnings.filterNot { msg ->
                msg.contains("pick the file again") && copiedAudioNames.any { n -> msg.contains("\"" + n + "\"") }
            }
        }
        if (failedNames.isNotEmpty()) {
            outWarnings = outWarnings + failedNames.map {
                "Media \"" + it + "\": its file could not be copied into the project, so it stays linked to the exporting phone"
            }
        }
        return Result(snapshot.copy(tracks = tracks), outWarnings, owned.size, failedNames)
    }
}
