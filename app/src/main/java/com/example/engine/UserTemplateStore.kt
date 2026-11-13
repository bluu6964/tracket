package com.example.engine

import android.content.Context
import com.example.model.SavedTemplate
import com.example.model.TimelineTrack
import com.example.model.UserTemplateFiles
import com.example.model.UserTemplateOps
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * User templates on disk ("Save as Template", Templates v2): `<root>/<id>/template.json`
 * (the [SavedTemplate]) and `<root>/<id>/media/` — the template's OWN copies of every
 * media file its layers reference, copied at save time by [UserTemplateFiles], so the
 * template survives the source project (and its imports) being deleted. The root is
 * `filesDir/templates`, next to `filesDir/projects`: templates are the person's own
 * library, shared by every project.
 *
 * Saving is a capture, not a project write: [UserTemplateOps.capture] normalises the
 * group's subtree (internal links kept, links outside the group dropped) after the media
 * references have been rewritten to the copies. Writes are atomic the [ProjectStore] way
 * (tmp file, old file kept as .bak, [load] falls back to it).
 */
class UserTemplateStore(private val root: File, private val filesDir: File) {

    constructor(context: Context) : this(File(context.filesDir, "templates"), context.filesDir)

    companion object {
        const val TEMPLATE_FILE = "template.json"
        private val json: Json = ProjectStore.json
    }

    init { root.mkdirs() }

    private fun dir(id: String) = File(root, id)

    private fun validId(id: String): Boolean = id.isNotEmpty() && !id.contains('/') && !id.contains("..")

    /** All saved templates, newest first. A folder whose file is unreadable is skipped. */
    fun list(): List<SavedTemplate> {
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { load(it.name) }.sortedByDescending { it.createdAtMs }
    }

    /** The saved template, or null when missing or unreadable (the .bak fallback included). */
    fun load(id: String): SavedTemplate? {
        if (!validId(id)) return null
        val f = File(dir(id), TEMPLATE_FILE)
        return runCatching { decode(f.readText()) }.getOrNull()
            ?: runCatching { decode(File(dir(id), "$TEMPLATE_FILE.bak").readText()) }.getOrNull()
    }

    /**
     * Saves the group [groupId] of [tracks] as a template named [name]: copies the media
     * of the whole subtree into the template's own `media/` folder, captures the subtree
     * with the references rewritten to those copies, and writes it. Returns the saved
     * template, or null when [groupId] is not a Group layer of [tracks]. Blocking IO —
     * call off the main thread.
     */
    fun save(name: String, tracks: List<TimelineTrack>, groupId: String): SavedTemplate? {
        val subtree = UserTemplateOps.subtreeOf(tracks, groupId)
        if (subtree.isEmpty()) return null
        val id = UUID.randomUUID().toString()
        dir(id).mkdirs()
        val mediaDir = File(dir(id), "media").apply { mkdirs() }
        val mediaMap = UserTemplateFiles.copyMediaInto(
            UserTemplateOps.mediaRefsOf(subtree), filesDir, mediaDir
        )
        val clean = name.trim().ifEmpty { subtree.first().name }
        val saved = UserTemplateOps.capture(tracks, groupId, clean, id, System.currentTimeMillis(), mediaMap)
            ?: return null
        if (mediaDir.exists() && (mediaDir.list()?.isEmpty() != false)) mediaDir.delete()
        writeAtomic(File(dir(id), TEMPLATE_FILE), encode(saved))
        return saved
    }

    /** Removes the template and its media copies. */
    fun delete(id: String) {
        if (!validId(id)) return
        dir(id).deleteRecursively()
    }

    private fun encode(t: SavedTemplate): String = json.encodeToString(SavedTemplate.serializer(), t)

    private fun decode(text: String): SavedTemplate = json.decodeFromString(SavedTemplate.serializer(), text)

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (target.exists()) {
            val bak = File(target.parentFile, target.name + ".bak")
            bak.delete()
            target.renameTo(bak)
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
