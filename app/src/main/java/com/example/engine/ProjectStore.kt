package com.example.engine

import android.content.Context
import com.example.model.ProjectMeta
import com.example.model.ProjectSnapshot
import com.example.model.TimelineTrack
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Projects on disk: `<root>/<id>/project.json` (the [ProjectSnapshot]), `<root>/<id>/meta.json` ([ProjectMeta]) and
 * `<root>/last.txt` (id of the project that was open last). A write goes to a .tmp file first and the old file is kept as
 * `.bak`, so a crash in the middle of a save never destroys the project; [load] falls back to the .bak file.
 *
 * Plain java.io (no Context needed apart from the convenience constructor), so it is unit-tested on the JVM.
 * The media files themselves are NOT copied: a project points to the app's own copies (filesDir/imports, cutouts, ...).
 */
class ProjectStore(private val root: File) {

    constructor(context: Context) : this(File(context.filesDir, "projects"))

    companion object {
        const val PROJECT_FILE = "project.json"
        const val META_FILE = "meta.json"
        private const val LAST_FILE = "last.txt"

        val json = Json {
            ignoreUnknownKeys = true      // a file written by a newer version still opens
            encodeDefaults = true         // a default that changes later does not change old projects
            coerceInputValues = true      // null / unknown enum value -> the default
            allowSpecialFloatingPointValues = true
        }

        fun encode(snapshot: ProjectSnapshot): String = json.encodeToString(ProjectSnapshot.serializer(), snapshot)
        fun decode(text: String): ProjectSnapshot = json.decodeFromString(ProjectSnapshot.serializer(), text)
    }

    init { root.mkdirs() }

    private fun dir(id: String) = File(root, id)

    /** Id of the project that was open when the app was closed, or null. */
    var lastId: String?
        get() = runCatching { File(root, LAST_FILE).readText().trim() }.getOrNull()
            ?.takeIf { it.isNotEmpty() && File(dir(it), PROJECT_FILE).exists() }
        set(value) {
            runCatching {
                if (value == null) File(root, LAST_FILE).delete() else writeAtomic(File(root, LAST_FILE), value)
            }
        }

    /** All projects, newest first. A folder whose meta.json is broken is rebuilt from its project.json. */
    fun list(): List<ProjectMeta> {
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { d ->
            readMeta(d.name) ?: run {
                val snap = load(d.name) ?: return@run null
                val t = File(d, PROJECT_FILE).lastModified()
                metaOf(d.name, "Project", t, t, snap)
            }
        }.sortedByDescending { it.updatedAt }
    }

    /** A new empty project (saved at once, so it shows in the list). */
    fun create(name: String = "Project", snapshot: ProjectSnapshot = ProjectSnapshot()): ProjectMeta {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dir(id).mkdirs()
        val meta = metaOf(id, uniqueName(name), now, now, snapshot)
        writeAtomic(File(dir(id), PROJECT_FILE), encode(snapshot))
        writeMeta(meta)
        return meta
    }

    /** Writes the project. Keeps its name and creation time; updates the time, duration and layer count. */
    fun save(id: String, snapshot: ProjectSnapshot): ProjectMeta {
        dir(id).mkdirs()
        val old = readMeta(id)
        val now = System.currentTimeMillis()
        writeAtomic(File(dir(id), PROJECT_FILE), encode(snapshot))
        val meta = metaOf(id, old?.name ?: "Project", old?.createdAt ?: now, now, snapshot)
        writeMeta(meta)
        return meta
    }

    /** The saved project, or null when it is missing or both the file and its backup are unreadable. */
    fun load(id: String): ProjectSnapshot? {
        val f = File(dir(id), PROJECT_FILE)
        return runCatching { decode(f.readText()) }.getOrNull()
            ?: runCatching { decode(File(dir(id), "$PROJECT_FILE.bak").readText()) }.getOrNull()
    }

    fun rename(id: String, name: String) {
        val m = readMeta(id) ?: return
        val clean = name.trim().ifEmpty { return }
        writeMeta(m.copy(name = clean))
    }

    fun delete(id: String) {
        if (id.isEmpty() || id.contains('/') || id.contains("..")) return
        dir(id).deleteRecursively()
        if (File(root, LAST_FILE).let { it.exists() && runCatching { it.readText().trim() }.getOrNull() == id }) {
            File(root, LAST_FILE).delete()
        }
    }

    /** The project to open at start: the last one, else the newest one, else a new empty one. */
    fun openLastOrCreate(): Pair<String, ProjectSnapshot> {
        lastId?.let { id -> load(id)?.let { return id to it } }
        for (m in list()) load(m.id)?.let { return m.id to it }
        val m = create("Project")
        return m.id to ProjectSnapshot()
    }

    private fun uniqueName(base: String): String {
        val names = list().map { it.name }.toSet()
        if (base !in names) return base
        var n = 2
        while ("$base $n" in names) n++
        return "$base $n"
    }

    private fun metaOf(id: String, name: String, created: Long, updated: Long, s: ProjectSnapshot) = ProjectMeta(
        id = id, name = name, createdAt = created, updatedAt = updated,
        durationMs = s.tracks.flatMap { it.clips }.maxOfOrNull { it.endMs } ?: 0L,
        layerCount = s.tracks.sumOf { it.clips.size }
    )

    private fun readMeta(id: String): ProjectMeta? =
        runCatching { json.decodeFromString(ProjectMeta.serializer(), File(dir(id), META_FILE).readText()) }.getOrNull()

    private fun writeMeta(m: ProjectMeta) {
        dir(m.id).mkdirs()
        writeAtomic(File(dir(m.id), META_FILE), json.encodeToString(ProjectMeta.serializer(), m))
    }

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

/** Finds out which media of a project are gone (the app's own copies can be deleted by "clear storage"). */
object ProjectFiles {
    /**
     * The app file a media uri points to, or null when it is not one of the app's own files (then nobody can tell, and it
     * counts as present). FileProvider uris look like `content://<package>.fileprovider/imports/<name>`.
     */
    fun fileOf(uri: String, filesDir: File): File? {
        if (uri.startsWith("file://")) return File(uri.removePrefix("file://"))
        if (!uri.startsWith("content://")) return null
        val afterScheme = uri.removePrefix("content://")
        val slash = afterScheme.indexOf('/')
        if (slash < 0) return null
        val path = afterScheme.substring(slash + 1).substringBefore('?')
        if (path.contains("..")) return null
        return if (path.startsWith("imports/")) File(filesDir, path) else null
    }

    /** Names of the layers whose media file does not exist any more. */
    fun missingMedia(tracks: List<TimelineTrack>, filesDir: File): List<String> =
        tracks.flatMap { it.clips }.mapNotNull { c ->
            val u = c.uri ?: return@mapNotNull null
            if (u.startsWith(ProjectArchive.TOKEN)) return@mapNotNull c.name  // a project file whose media was not inside
            val f = fileOf(u, filesDir) ?: return@mapNotNull null
            if (f.exists()) null else c.name
        }
}
