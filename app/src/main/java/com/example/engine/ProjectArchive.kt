package com.example.engine

import com.example.model.PlanarRemove
import com.example.model.ProjectSnapshot
import kotlinx.serialization.Serializable
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Project FILE (`.ampro`) = a portable copy of a project: one zip that holds
 *   manifest.json   what it is (format name + version, project name)
 *   project.json    the [ProjectSnapshot], every app file path replaced by a token `am-asset:<n>/<name>`
 *   assets/<n>/...  the media, cutout / face masks and remove patches the layers use
 * Like an After Effects / Premiere / Resolve project with its media collected. [import] unpacks it on any phone and puts the
 * real paths back. Plain java.io (Context-free) so it is unit-tested on the JVM; the Android file uri is made by the caller.
 */
object ProjectArchive {
    const val EXTENSION = "ampro"
    const val FORMAT = "after-motion-project"
    const val FORMAT_VERSION = 1
    const val TOKEN = "am-asset:"
    private const val MANIFEST = "manifest.json"
    private const val PROJECT = "project.json"

    private val ALREADY_COMPRESSED = setOf(
        "mp4", "mov", "webm", "mkv", "3gp", "jpg", "jpeg", "png", "webp", "heic", "gif",
        "mp3", "m4a", "aac", "ogg", "wav", "zip"
    )

    @Serializable
    data class Manifest(
        val format: String = "",
        val formatVersion: Int = 0,
        val appVersion: String = "",
        val projectName: String = "",
        val createdAt: Long = 0L,
        val assetCount: Int = 0
    )

    /** [notPacked]: names of layers that use a file the archive could not take (outside the app, or already deleted). */
    class ExportReport(val assetCount: Int, val bytes: Long, val notPacked: List<String>)

    /** [missing]: names of layers whose file was not inside the archive. */
    class ImportResult(val name: String, val snapshot: ProjectSnapshot, val missing: List<String>)

    private fun fileFor(ref: String, filesDir: File): File? =
        ProjectFiles.fileOf(ref, filesDir) ?: if (ref.startsWith("/")) File(ref) else null

    fun export(
        snapshot: ProjectSnapshot,
        name: String,
        appVersion: String,
        filesDir: File,
        out: OutputStream,
        onProgress: (Float) -> Unit = {}
    ): ExportReport {
        val root = filesDir.canonicalFile
        val assets = LinkedHashMap<String, Pair<String, File>>() // canonical path -> (token path, file)
        val notPacked = LinkedHashSet<String>()

        fun pack(ref: String?, label: String): String? {
            if (ref == null || ref.startsWith(TOKEN)) return ref
            val f = fileFor(ref, root)?.let { runCatching { it.canonicalFile }.getOrNull() }
            if (f == null || !f.exists() || !f.path.startsWith(root.path + File.separator)) {
                notPacked.add(label)
                return ref
            }
            val known = assets[f.path]
            if (known != null) return TOKEN + known.first
            val tokenPath = "${assets.size}/${f.name}"
            assets[f.path] = tokenPath to f
            return TOKEN + tokenPath
        }

        val tracks = snapshot.tracks.map { t ->
            t.copy(clips = t.clips.map { c ->
                val label = c.name
                c.copy(
                    uri = pack(c.uri, label),
                    fillImageUri = pack(c.fillImageUri, label),
                    fillVideoUri = pack(c.fillVideoUri, label),
                    cutoutDir = pack(c.cutoutDir, label),
                    faceBlurDir = pack(c.faceBlurDir, label),
                    planarRemove = c.planarRemove?.let { pr ->
                        val p = pack(pr.patchPath, label) ?: pr.patchPath
                        if (p == pr.patchPath) pr else PlanarRemove(pr.pin, pr.gains, p, pr.usedFrames)
                    }
                )
            })
        }

        fun filesOf(f: File): List<Pair<File, String>> =
            if (f.isDirectory) f.walkTopDown().filter { it.isFile }.map { it to f.name + "/" + it.relativeTo(f).path.replace('\\', '/') }.toList()
            else listOf(f to f.name)

        // (file, entry name) for everything that goes into assets/
        val entries = assets.values.flatMap { (tokenPath, f) ->
            val n = tokenPath.substringBefore('/')
            filesOf(f).map { (file, rel) -> file to "assets/$n/$rel" }
        }
        val total = entries.sumOf { it.first.length() }.coerceAtLeast(1L)
        var done = 0L
        var written = 0L

        val manifest = Manifest(FORMAT, FORMAT_VERSION, appVersion, name, System.currentTimeMillis(), entries.size)
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            fun putText(entry: String, text: String) {
                zip.setLevel(Deflater.DEFAULT_COMPRESSION)
                zip.putNextEntry(ZipEntry(entry))
                val b = text.toByteArray(Charsets.UTF_8)
                zip.write(b)
                zip.closeEntry()
                written += b.size
            }
            putText(MANIFEST, ProjectStore.json.encodeToString(Manifest.serializer(), manifest))
            putText(PROJECT, ProjectStore.encode(snapshot.copy(tracks = tracks)))
            val buf = ByteArray(64 * 1024)
            for ((file, entry) in entries) {
                val ext = file.extension.lowercase()
                zip.setLevel(if (ext in ALREADY_COMPRESSED) Deflater.NO_COMPRESSION else Deflater.DEFAULT_COMPRESSION)
                zip.putNextEntry(ZipEntry(entry))
                file.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        zip.write(buf, 0, n)
                        done += n
                        written += n
                        onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
                zip.closeEntry()
            }
        }
        onProgress(1f)
        return ExportReport(entries.size, written, notPacked.toList())
    }

    /**
     * Unpacks a project file into `<filesDir>/imports/ap_<id>/` and returns the project with real paths.
     * [uriForFile] turns a media file into the uri the layers use (the FileProvider uri on a phone).
     * @throws IllegalArgumentException when the file is not a project file, or is from a newer version of the app.
     */
    fun import(input: InputStream, filesDir: File, uriForFile: (File) -> String): ImportResult {
        val dest = File(filesDir, "imports/ap_" + UUID.randomUUID().toString().take(8)).apply { mkdirs() }
        val destRoot = dest.canonicalFile
        var manifest: Manifest? = null
        var projectText: String? = null
        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val n = e.name
                    if (e.isDirectory) continue
                    when {
                        n == MANIFEST ->
                            manifest = runCatching {
                                ProjectStore.json.decodeFromString(Manifest.serializer(), String(zip.readBytes(), Charsets.UTF_8))
                            }.getOrNull()
                        n == PROJECT -> projectText = String(zip.readBytes(), Charsets.UTF_8)
                        n.startsWith("assets/") -> {
                            val target = File(destRoot, n.removePrefix("assets/")).canonicalFile
                            if (!target.path.startsWith(destRoot.path + File.separator)) continue // zip-slip: ignored
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
            }
            val m = manifest
            val text = projectText
            require(m != null && m.format == FORMAT && text != null) { "This is not an After Motion project file" }
            require(m.formatVersion <= FORMAT_VERSION) { "This project was made by a newer version of the app" }

            val missing = LinkedHashSet<String>()
            fun resolve(ref: String?, label: String, asUri: Boolean): String? {
                if (ref == null || !ref.startsWith(TOKEN)) return ref
                val f = runCatching { File(destRoot, ref.removePrefix(TOKEN)).canonicalFile }.getOrNull()
                if (f == null || !f.path.startsWith(destRoot.path + File.separator) || !f.exists()) {
                    missing.add(label)
                    return ref
                }
                return if (asUri) uriForFile(f) else f.absolutePath
            }

            val snap = ProjectStore.decode(text)
            val tracks = snap.tracks.map { t ->
                t.copy(clips = t.clips.map { c ->
                    val label = c.name
                    c.copy(
                        uri = resolve(c.uri, label, true),
                        fillImageUri = resolve(c.fillImageUri, label, true),
                        fillVideoUri = resolve(c.fillVideoUri, label, true),
                        cutoutDir = resolve(c.cutoutDir, label, false),
                        faceBlurDir = resolve(c.faceBlurDir, label, false),
                        planarRemove = c.planarRemove?.let { pr ->
                            val p = resolve(pr.patchPath, label, false) ?: pr.patchPath
                            if (p == pr.patchPath) pr else PlanarRemove(pr.pin, pr.gains, p, pr.usedFrames)
                        }
                    )
                })
            }
            return ImportResult(m.projectName.ifBlank { "Imported project" }, snap.copy(tracks = tracks), missing.toList())
        } catch (e: Throwable) {
            dest.deleteRecursively()
            throw e
        }
    }
}
