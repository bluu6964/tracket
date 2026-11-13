package com.example.model

import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/**
 * "Save as Template" — user templates (Templates v2): a Group the person built can be saved
 * under a name and re-inserted into any project later, like the built-in templates
 * (Carousel / Pop Title / Logo Reveal), through the same insert path.
 *
 * A saved template is the group's WHOLE subtree as one flat list — [clips] holds the root
 * Group layer first, then every descendant (nested groups included) in lane order, top
 * lane first — captured from the live project by [UserTemplateOps.capture] and turned back
 * into fresh layers by [UserTemplateOps.instantiate]. Everything a clip references by id
 * is normalised at capture and remapped at insert by the same rule (see [UserTemplateOps]):
 * links whose target is inside the group stay (and are remapped consistently), links
 * pointing OUTSIDE the group are dropped — a template must be self-contained. Media files
 * are not part of this class: the store copies them into the template's own folder first
 * and capture rewrites the references through the resulting map (see [UserTemplateFiles]),
 * so a template never depends on the source project's files.
 */
@Serializable
data class SavedTemplate(
    /** Folder name of the template in the store (a UUID). */
    val id: String,
    /** The name the person gave it (the saved group's name is set to the same). */
    val name: String,
    val createdAtMs: Long,
    /** Root Group layer first, then all descendants in lane order (top lane first). */
    val clips: List<TimelineClip> = emptyList()
)

/** Pure capture / instantiate rules for [SavedTemplate] (no Android, no IO). */
object UserTemplateOps {

    /**
     * Every media reference of [clips], in a stable order and without duplicates: a media
     * layer's own file ([TimelineClip.uri]), a shape's image / video fill, a Remove patch
     * file, and the per-frame mask folders of a cutout / face blur. These are the strings
     * the store copies into the template's own storage before capturing.
     */
    fun mediaRefsOf(clips: List<TimelineClip>): List<String> {
        val out = LinkedHashSet<String>()
        for (c in clips) {
            c.uri?.takeIf { it.isNotBlank() }?.let { out += it }
            c.fillImageUri?.takeIf { it.isNotBlank() }?.let { out += it }
            c.fillVideoUri?.takeIf { it.isNotBlank() }?.let { out += it }
            c.planarRemove?.patchPath?.takeIf { it.isNotBlank() }?.let { out += it }
            c.cutoutDir?.takeIf { it.isNotBlank() }?.let { out += it }
            c.faceBlurDir?.takeIf { it.isNotBlank() }?.let { out += it }
        }
        return out.toList()
    }

    /** The root Group layer + every descendant, root first, the rest in lane order. */
    fun subtreeOf(tracks: List<TimelineTrack>, groupId: String): List<TimelineClip> {
        val root = tracks.flatMap { it.clips }
            .firstOrNull { it.id == groupId && it.type == LayerType.GROUP } ?: return emptyList()
        return listOf(root) + GroupOps.descendantsOf(tracks, groupId)
    }

    /**
     * Captures the group [groupId] of [tracks] as a [SavedTemplate], or null when it is not
     * a Group layer. [mediaMap] rewrites media references (old reference -> the template's
     * own copy, built by [UserTemplateFiles.copyMediaInto]); a reference missing from the
     * map is kept as authored.
     *
     * Normalisation (the self-containment rule, applied to every clip including the root):
     *  - the root becomes a top-level layer: its own [TimelineClip.groupId], parent link
     *    and chain are dropped, and its name becomes the template's [name];
     *  - a parent link whose target is inside the group is kept verbatim (it is remapped
     *    at insert); one pointing outside is dropped — except that a group child whose
     *    parent is dropped keeps following its group through the REST link every ordinary
     *    group child carries (the build-143 convention), so it can never fall out of the
     *    group it is stored in;
     *  - [TimelineClip.controllerId], [TimelineClip.maskTargetId] and the followed /
     *    pinned video of a motion track, corner pin or planar remove survive only when
     *    their target is inside the group;
     *  - chains never survive (a Chain is a lane container of the source project).
     */
    fun capture(
        tracks: List<TimelineTrack>,
        groupId: String,
        name: String,
        templateId: String,
        createdAtMs: Long,
        mediaMap: Map<String, String> = emptyMap()
    ): SavedTemplate? {
        val subtree = subtreeOf(tracks, groupId)
        if (subtree.isEmpty()) return null
        val inside = subtree.mapTo(HashSet()) { it.id }
        val clips = subtree.map { c ->
            normalise(c, inside, isRoot = c.id == groupId, rootName = name, mediaMap = mediaMap)
        }
        return SavedTemplate(templateId, name, createdAtMs, clips)
    }

    private fun normalise(
        c: TimelineClip,
        inside: Set<String>,
        isRoot: Boolean,
        rootName: String,
        mediaMap: Map<String, String>
    ): TimelineClip {
        val p = c.parent
        val parent = when {
            isRoot || p == null -> null
            p.id in inside -> p
            c.groupId != null && c.groupId in inside -> GroupOps.linkTo(c.groupId, GroupOps.Pose.REST)
            else -> null
        }
        fun media(ref: String?): String? = ref?.let { mediaMap[it] ?: it }
        val pr = c.planarRemove
        return c.copy(
            name = if (isRoot) rootName else c.name,
            groupId = if (isRoot) null else c.groupId?.takeIf { it in inside },
            parent = parent,
            controllerId = c.controllerId?.takeIf { it in inside },
            maskTargetId = c.maskTargetId?.takeIf { it in inside },
            chainId = null,
            trackFollow = c.trackFollow?.takeIf { it.videoId in inside },
            cornerPin = c.cornerPin?.takeIf { it.videoId in inside },
            planarRemove = pr?.takeIf { it.pin.videoId == c.id || it.pin.videoId in inside }?.let {
                it.copyPreservingPatch(media(it.patchPath) ?: it.patchPath)
            },
            uri = media(c.uri),
            fillImageUri = media(c.fillImageUri),
            fillVideoUri = media(c.fillVideoUri),
            cutoutDir = media(c.cutoutDir),
            faceBlurDir = media(c.faceBlurDir)
        )
    }

    private fun PlanarRemove.copyPreservingPatch(newPath: String): PlanarRemove =
        if (newPath == patchPath) this else PlanarRemove(pin, gains, newPath, usedFrames)

    /**
     * The layers an insert drops into the project: every clip of [template] copied under a
     * FRESH id, every internal reference remapped to the matching fresh id, and the whole
     * composition shifted so the root group starts at [insertAtMs] (children keep their
     * offsets from it; keyframes are relative to each layer's own start and need no shift).
     * The result is in the stored lane order — the group first — ready for
     * [LaneLayout.placeAll]. Inserting never mutates [template], and inserting twice gives
     * two independent groups (the same independence [GroupOps.duplicate] gives a copy).
     */
    fun instantiate(template: SavedTemplate, insertAtMs: Long): List<TimelineClip> {
        val root = template.clips.firstOrNull() ?: return emptyList()
        val idMap = template.clips.associate { it.id to UUID.randomUUID().toString() }
        val shift = insertAtMs - root.startMs
        return template.clips.map { c ->
            c.copy(
                id = idMap.getValue(c.id),
                startMs = c.startMs + shift,
                groupId = c.groupId?.let { idMap[it] },
                parent = c.parent?.let { p -> idMap[p.id]?.let { p.copy(id = it) } },
                controllerId = c.controllerId?.let { idMap[it] },
                maskTargetId = c.maskTargetId?.let { idMap[it] },
                chainId = null,
                trackFollow = c.trackFollow?.let { tf ->
                    idMap[tf.videoId]?.let { TrackFollow(it, tf.timesMs, tf.dx, tf.dy, tf.rotDeg, tf.scale) }
                },
                cornerPin = c.cornerPin?.let { cp ->
                    idMap[cp.videoId]?.let { CornerPin(it, cp.timesMs, cp.quad, cp.aspect) }
                },
                planarRemove = c.planarRemove?.let { pr ->
                    idMap[pr.pin.videoId]?.let {
                        PlanarRemove(CornerPin(it, pr.pin.timesMs, pr.pin.quad, pr.pin.aspect), pr.gains, pr.patchPath, pr.usedFrames)
                    }
                }
            )
        }
    }
}

/**
 * The file side of user templates (plain java.io, like [com.example.engine.ProjectStore]):
 * resolving a clip's media reference to the app file behind it and copying those files into
 * a template's own folder, so the saved template keeps working when the source project's
 * media is deleted afterwards.
 */
object UserTemplateFiles {

    /**
     * The app file a media reference points to, or null when it cannot be resolved (an
     * external provider's uri the app never copied). `file://` references resolve directly;
     * `content://` references resolve through the app FileProvider's shape — the path after
     * the authority is a path under [filesDir] (`imports/...`, `templates/...`, ...);
     * anything that is already a plain path resolves as itself. `..` never resolves.
     */
    fun resolveRefFile(ref: String, filesDir: File): File? {
        if (ref.isBlank() || ref.contains("..")) return null
        if (ref.startsWith("file://")) return File(ref.removePrefix("file://"))
        if (ref.startsWith("content://")) {
            val afterScheme = ref.removePrefix("content://")
            val slash = afterScheme.indexOf('/')
            if (slash < 0) return null
            val path = afterScheme.substring(slash + 1).substringBefore('?')
            if (path.isBlank() || path.contains("..")) return null
            return File(filesDir, path)
        }
        if (ref.startsWith("/")) return File(ref)
        return null
    }

    /**
     * Copies every resolvable reference of [refs] into [destDir] and returns the rewrite
     * map (old reference -> new reference). A file is copied flat; a folder (a cutout /
     * face-blur mask folder) is copied whole. New references keep the form of the old ones:
     * media files become `file://` uris of the copies (the form the renderers and
     * [com.example.engine.ProjectFiles] resolve everywhere), folders become the copies'
     * plain paths (the form the mask engines store). A reference that cannot be resolved
     * or copied gets no entry — capture then keeps it as authored, like a project whose
     * media is missing.
     */
    fun copyMediaInto(refs: List<String>, filesDir: File, destDir: File): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var n = 0
        for (ref in refs) {
            if (ref in out) continue
            val src = resolveRefFile(ref, filesDir) ?: continue
            if (!src.exists()) continue
            runCatching {
                if (src.isDirectory) {
                    val dest = File(destDir, "d${n++}_${src.name}")
                    src.copyRecursively(dest, overwrite = false)
                    out[ref] = dest.absolutePath
                } else {
                    destDir.mkdirs()
                    val ext = src.name.substringAfterLast('.', missingDelimiterValue = "")
                        .takeIf { it.length in 1..5 && it.all { c -> c.isLetterOrDigit() } }
                    val dest = File(destDir, "m${n++}_${src.nameWithoutExtension}" + (ext?.let { ".$it" } ?: ""))
                    src.copyTo(dest, overwrite = false)
                    if (dest.length() == src.length()) out[ref] = "file://" + dest.absolutePath
                    else dest.delete()
                }
            }
        }
        return out
    }
}
