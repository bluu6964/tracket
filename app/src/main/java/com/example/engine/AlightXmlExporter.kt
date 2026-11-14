package com.example.engine

import com.example.model.AnimProp
import com.example.model.ClipFx
import com.example.model.ClipFxLibrary
import com.example.model.Easing
import com.example.model.FILL_GRADIENT
import com.example.model.FILL_IMAGE
import com.example.model.FILL_NONE
import com.example.model.FILL_SOLID
import com.example.model.FILL_VIDEO
import com.example.model.GRAD_LINEAR
import com.example.model.GRAD_RADIAL
import com.example.model.IMG_FIT
import com.example.model.IMG_STRETCH
import com.example.model.Keyframe
import com.example.model.LayerType
import com.example.model.PATH_OP_CLOSE
import com.example.model.PATH_OP_CUBIC
import com.example.model.PATH_OP_LINE
import com.example.model.PATH_OP_MOVE
import com.example.model.PATH_OP_QUAD
import com.example.model.ProjectSnapshot
import com.example.model.TimelineClip
import com.example.model.valueAt
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

/**
 * Build 197: project export to an Alight Motion XML scene — the inverse of [AlightXmlImporter].
 *
 * The exporter emits what REAL Alight files look like (the forms were validated against the
 * 129-file corpus the importer was built against): a `<scene>` with a `<media>` table and one
 * element per layer in bottom-to-top document order (later elements draw on top), transforms as
 * `<location>` / `<scale>` / `<rotation>` / `<opacity>` tracks whose `<kf>` times are normalised
 * to the layer's span and whose easing sits on the keyframe a segment ENDS at, media carried by
 * `<shape fillType="media">` layers referenced by uri (never embedded), groups as `<embedScene>`
 * with a nested `<scene>`, parenting as a `parent="…"` attribute naming the parent's element id.
 *
 * COORDINATE LAW (the importer's, inverted exactly): the app's stored units are preview pixels
 * around the canvas centre — the exporter takes the SAME [previewWidthPx] the importer is given
 * on the way back in, so an export → import round trip at one preview width is the identity for
 * everything this writer covers (the harness round-trip test pins it). Scene pixels = stored / k
 * shifted by half the scene size, with k = previewWidthPx / sceneWidth.
 *
 * PARENTING LAW: the app's [com.example.model.ParentLink] is a DELTA link (ParentRig composes
 * the parent's motion since a saved reference pose); Alight's is ABSOLUTE (the child's stored
 * values are local to the parent's anchor). The two agree exactly after a constant similarity
 * correction computed from the link's own reference: the exported child's positions map by
 * stored' = ref' + R(−refRot)·(stored − ref)·(100 / refScale), its scale multiplies by
 * 100 / refScale and its rotation loses refRot, where ref' is the reference the importer itself
 * will recreate — the scene origin (−sceneW/2·k, −sceneH/2·k, 100, 0) for a plain parent, or
 * (0, 0, 100, 0) for a group's children (the exporter writes no pivot, so the importer
 * recreates a zero pivot). Parent Helper locks (lockScale / lockRotate) skip their own
 * track's correction and are written as the `parenthelper` effect, exactly as the importer
 * reads them back.
 *
 * EFFECTS: only the effects whose importer mapping is unambiguous are written back (exposure,
 * brightcont2, rgbsep, hueshift + satvib split from hue_saturation, vignette, invert, tile,
 * gaussianblur from the AM blur port, textspacing from a text layer's spacing fields, the fade
 * effect from the clip's fade fields, parenthelper from link locks, and the stored text
 * animators verbatim). Keyframed parameters ride the SAME tails the importer encoded
 * ([AmFxEval] layout: at index 7, per track a count then [t, v, easing×4] per key, the easing
 * on the ending key — the XML convention too, so tails convert key-for-key). Everything else is
 * OMITTED and counted in [Report] — never silently faked into plausible-looking wrong XML.
 */
object AlightXmlExporter {

    /** What an export could not write, counted — surfaced in the UI message and CYCLE-LOG. */
    data class Report(
        val layersWritten: Int,
        /** App effect type -> how many instances were not written. */
        val omittedEffects: Map<String, Int>,
        /** Whole layers that were not written, with the reason. */
        val omittedLayers: List<String>,
        /** Partial losses (a feature of a written layer that has no Alight counterpart here). */
        val notes: List<String>
    ) {
        val omittedEffectCount: Int get() = omittedEffects.values.sum()
    }

    data class Result(val xml: String, val report: Report)

    /**
     * The XML for [snapshot] (project title [name]), or null when the project has no layers at
     * all (the caller reports an empty project instead of writing a bad file). [previewWidthPx]
     * must be the editor preview width — the same anchor the importer uses.
     */
    fun export(name: String, snapshot: ProjectSnapshot, previewWidthPx: Float): Result? {
        val all = snapshot.tracks.flatMap { it.clips }
        if (all.isEmpty()) return null
        return Session(name, snapshot, previewWidthPx, all).run()
    }

    // ---- shared formatting -------------------------------------------------------------------

    /** The corpus float form: six decimals, trailing zeros trimmed ("540", "0.169692"). */
    internal fun fmt(v: Float): String {
        if (v.isNaN() || v.isInfinite()) return "0"
        var s = String.format(Locale.US, "%.6f", v)
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        return if (s == "-0" || s.isEmpty()) "0" else s
    }

    /**
     * XML 1.0 character legality (build 198): the C0 control characters other than
     * tab / LF / CR — and the two non-characters U+FFFE / U+FFFF — are forbidden
     * ANYWHERE in an XML file, escaping cannot represent them, and a strict parser
     * (Alight's, or this app's own importer) rejects the whole document over one
     * stray byte in a layer name or a text body. They are replaced with a space
     * before escaping; everything else passes through byte-identical.
     */
    internal fun xmlChars(s: String): String {
        var dirty = false
        for (c in s) {
            if ((c.code < 0x20 && c != '\t' && c != '\n' && c != '\r') ||
                c.code == 0xFFFE || c.code == 0xFFFF
            ) { dirty = true; break }
        }
        if (!dirty) return s
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                if ((c.code < 0x20 && c != '\t' && c != '\n' && c != '\r') ||
                    c.code == 0xFFFE || c.code == 0xFFFF
                ) ' ' else c
            )
        }
        return sb.toString()
    }

    internal fun escAttr(s: String): String = xmlChars(s).replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")

    internal fun escText(s: String): String =
        xmlChars(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    internal fun colorHex(argb: Long): String =
        "#" + (argb and 0xFFFFFFFFL).toString(16).padStart(8, '0')

    /** One XML keyframe of a track: normalised time, the value components, the segment easing. */
    private data class XmlKey(val t: Float, val v: List<Float>, val e: Easing?)

    /** A decoded [AmFxEval] tail track: (t, value, easing) keys in Alight units. */
    private data class TailKey(val t: Float, val v: Float, val e: Easing?)

    private class Session(
        private val name: String,
        private val snapshot: ProjectSnapshot,
        previewWidthPx: Float,
        private val allClips: List<TimelineClip>
    ) {
        // Scene geometry: the aspect label of an imported project is the literal "W:H"; a named
        // preset's label is the ratio itself, parsed the same way when it fits the float ratio,
        // otherwise the frame is derived with the long side at 1920 (Alight's own exports are
        // full-size frames, and positions/sizes are all relative to these numbers via k).
        private val sceneW: Int
        private val sceneH: Int
        private val k: Float
        private val previewW: Float
        private val previewH: Float
        private val fps: Int = snapshot.exportSettings.frameRate.coerceIn(1, 240)
        private val durationMs: Long = allClips.maxOfOrNull { it.endMs } ?: 0L

        private val byId: Map<String, TimelineClip> = allClips.associateBy { it.id }
        private val amIds = HashMap<String, String>()
        private var nextAmId = 1_000_000

        private data class MediaInfo(val filename: String, val mime: String?, val durationMs: Long)
        private val media = LinkedHashMap<String, MediaInfo>()

        private var layersWritten = 0
        private val omittedFx = sortedMapOf<String, Int>()
        private val omittedLayers = ArrayList<String>()
        private val noteCounts = LinkedHashMap<String, Int>()

        init {
            val ratio = snapshot.aspectRatio.ratioFloat.takeIf { it > 0f } ?: (16f / 9f)
            val parts = snapshot.aspectRatio.label.split(":")
            val lw = parts.getOrNull(0)?.trim()?.toIntOrNull()
            val lh = parts.getOrNull(1)?.trim()?.toIntOrNull()
            if (lw != null && lh != null && lw >= 16 && lh >= 16 &&
                abs(lw.toFloat() / lh.toFloat() - ratio) < 0.02f * ratio
            ) {
                sceneW = lw; sceneH = lh
            } else if (ratio >= 1f) {
                sceneW = 1920; sceneH = (1920f / ratio).roundToLong().toInt().coerceAtLeast(16)
            } else {
                sceneH = 1920; sceneW = (1920f * ratio).roundToLong().toInt().coerceAtLeast(16)
            }
            previewW = if (previewWidthPx > 1f) previewWidthPx else sceneW.toFloat()
            k = previewW / sceneW
            previewH = previewW * sceneH / sceneW
        }

        private fun amId(clip: TimelineClip): String =
            amIds.getOrPut(clip.id) { (nextAmId++).toString() }

        private fun omitFx(type: String) {
            omittedFx[type] = (omittedFx[type] ?: 0) + 1
        }

        private fun note(text: String) {
            noteCounts[text] = (noteCounts[text] ?: 0) + 1
        }

        fun run(): Result {
            collectMedia()
            val sb = StringBuilder()
            sb.append("<?xml version='1.0' encoding='UTF-8'?>\n")
            sb.append("<!-- Exported from MaxCut as an Alight Motion project -->\n")
            sb.append("<scene title=\"").append(escAttr(name)).append("\"")
            sb.append(" width=\"").append(sceneW).append("\" height=\"").append(sceneH).append("\"")
            sb.append(" exportWidth=\"").append(sceneW).append("\" exportHeight=\"").append(sceneH).append("\"")
            sb.append(" bgcolor=\"").append(colorHex(snapshot.canvasColor)).append("\"")
            sb.append(" totalTime=\"").append(durationMs).append("\"")
            sb.append(" fps=\"").append(fps).append("\">\n")
            for ((uri, info) in media) {
                sb.append("  <media uri=\"").append(escAttr(uri)).append("\"")
                sb.append(" filename=\"").append(escAttr(info.filename)).append("\"")
                sb.append(" title=\"").append(escAttr(info.filename)).append("\"")
                if (info.mime != null) sb.append(" type=\"").append(escAttr(info.mime)).append("\"")
                if (info.durationMs > 0L) sb.append(" duration=\"").append(info.durationMs * 1000L).append("\"")
                sb.append(" />\n")
            }
            // Stack order: tracks[0] is the TOP lane; the file lists the bottom layer first.
            val rootStack = snapshot.tracks.flatMap { it.clips }
                .filter { it.groupId == null || byId[it.groupId]?.type != LayerType.GROUP }
            emitScene(sb, rootStack, Frame(sceneW.toFloat(), sceneH.toFloat()), indent = 1,
                timeShiftMs = 0L, windowMs = durationMs, parentGroup = null)
            sb.append("</scene>\n")
            val notes = noteCounts.map { (t, n) -> if (n > 1) "$t (x$n)" else t }
            return Result(
                sb.toString(),
                Report(layersWritten, omittedFx.toMap(), omittedLayers.toList(), notes)
            )
        }

        /** A scene's own frame: its pixel size (positions convert against it, scale k is global). */
        private data class Frame(val w: Float, val h: Float)

        // ---- media table ----------------------------------------------------------------------

        private fun guessMime(uri: String, fallback: String?): String? {
            if (!fallback.isNullOrBlank()) return fallback
            val ext = uri.substringBefore('?').substringAfterLast('.', "").lowercase()
            return when (ext) {
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "m4a" -> "audio/mp4"
                "mp4", "m4v" -> "video/mp4"
                "mov" -> "video/quicktime"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> null
            }
        }

        private fun registerMedia(uri: String?, mime: String?, durationMs: Long) {
            if (uri.isNullOrBlank()) return
            val filename = uri.substringBefore('?').substringAfterLast('/').ifBlank { "media" }
            val prev = media[uri]
            if (prev == null) {
                media[uri] = MediaInfo(filename, guessMime(uri, mime), durationMs)
            } else if (prev.durationMs <= 0L && durationMs > 0L) {
                media[uri] = prev.copy(durationMs = durationMs)
            }
        }

        private fun collectMedia() {
            for (c in allClips) {
                when (c.type) {
                    LayerType.AUDIO -> registerMedia(c.uri, c.mimeType, c.sourceDurationMs)
                    LayerType.VIDEO, LayerType.OVERLAY, LayerType.STICKER ->
                        registerMedia(c.uri, c.mimeType, c.sourceDurationMs)
                    LayerType.SHAPE -> {
                        registerMedia(c.fillImageUri, if (c.isImage) c.mimeType else "image/*", 0L)
                        registerMedia(c.fillVideoUri, "video/*", c.sourceDurationMs)
                    }
                    else -> Unit
                }
            }
        }

        // ---- scene / layer emission -------------------------------------------------------------

        /**
         * Emits [stackTopFirst] (the scene's clips in stack order, top first) as elements in
         * file order (bottom first). [timeShiftMs] is subtracted from every start (a group's
         * children live in the group's own time); [windowMs] clips spans to the scene's length.
         */
        private fun emitScene(
            sb: StringBuilder, stackTopFirst: List<TimelineClip>, frame: Frame,
            indent: Int, timeShiftMs: Long, windowMs: Long, parentGroup: TimelineClip?
        ) {
            val inScene = HashSet(stackTopFirst.map { it.id })
            val ordered = stackTopFirst.asReversed()
            // Mask adjacency, checked on the emitted order: an Alight mask cuts the layer
            // directly BELOW it in the file, which is the element emitted just before it.
            val emittedIds = ArrayList<String>()
            for (clip in ordered) {
                val before = sb.length
                emitLayer(sb, clip, frame, indent, timeShiftMs, windowMs, parentGroup, inScene)
                if (sb.length > before) emittedIds += clip.id
            }
            for (clip in ordered) {
                if (!clip.isMask) continue
                val target = clip.maskTargetId ?: continue
                val mi = emittedIds.indexOf(clip.id)
                if (mi <= 0) continue
                if (emittedIds[mi - 1] != target) {
                    note("A mask layer is not directly above the layer it masks: Alight will apply it to the layer below it in the file")
                }
            }
        }

        private fun emitLayer(
            sb: StringBuilder, clip: TimelineClip, frame: Frame, indent: Int,
            timeShiftMs: Long, windowMs: Long, parentGroup: TimelineClip?, inScene: Set<String>
        ) {
            when (clip.type) {
                LayerType.CONTROLLER -> {
                    omittedLayers += "Controller layer \"" + clip.name + "\" (Alight Motion has no controller layer)"
                    return
                }
                LayerType.CHAIN -> {
                    omittedLayers += "Chain layer \"" + clip.name + "\" (its layers are written as normal layers)"
                    return
                }
                else -> Unit
            }
            val writtenBefore = layersWritten
            val start = (clip.startMs - timeShiftMs).coerceAtLeast(0L)
            // Manual clamp, NOT coerceIn(start, windowMs): a group child whose start
            // lies past the group's end (group trimmed shorter, child moved out) makes
            // windowMs < start, and coerceIn on an empty range THROWS — the child must
            // land in the end <= start omission below instead (build 198).
            val rawEnd = clip.endMs - timeShiftMs
            val end = when {
                windowMs <= start -> start
                rawEnd < start -> start
                rawEnd > windowMs -> windowMs
                else -> rawEnd
            }
            if (end <= start && parentGroup != null) {
                omittedLayers += "Layer \"" + clip.name + "\" lies outside its group's span"
                return
            }
            val dur = (end - start).coerceAtLeast(1L)
            val adj = parentAdjust(clip, frame, parentGroup, inScene)
            val pad = "  ".repeat(indent)
            when (clip.type) {
                LayerType.GROUP -> emitGroup(sb, clip, frame, indent, start, dur, adj)
                LayerType.NULL -> {
                    sb.append(pad).append("<nullobj")
                    commonAttrs(sb, clip, start, end, adj)
                    sb.append(">\n")
                    emitTransform(sb, clip, frame, indent + 1, adj, dur, ScaleLaw.PLAIN)
                    emitEffects(sb, clip, indent + 1, adj)
                    sb.append(pad).append("</nullobj>\n")
                    layersWritten++
                }
                LayerType.CAMERA -> emitCamera(sb, clip, frame, indent, start, end, dur, adj)
                LayerType.AUDIO -> emitAudio(sb, clip, indent, start, end)
                LayerType.TEXT -> emitText(sb, clip, frame, indent, start, end, dur, adj)
                LayerType.SHAPE -> emitShape(sb, clip, frame, indent, start, end, dur, adj)
                LayerType.VIDEO, LayerType.OVERLAY, LayerType.STICKER ->
                    emitMediaLayer(sb, clip, frame, indent, start, end, dur, adj)
                else -> {
                    omittedLayers += "Layer \"" + clip.name + "\" of type " + clip.type +
                        " (no Alight Motion counterpart)"
                }
            }
            if (layersWritten > writtenBefore) featureNotes(clip)
        }

        /** id / label / span / blending / parent attributes shared by every layer element. */
        private fun commonAttrs(
            sb: StringBuilder, clip: TimelineClip, start: Long, end: Long, adj: ParentAdjust?
        ) {
            sb.append(" id=\"").append(amId(clip)).append("\"")
            if (clip.name.isNotBlank()) sb.append(" label=\"").append(escAttr(clip.name)).append("\"")
            sb.append(" startTime=\"").append(start).append("\" endTime=\"").append(end).append("\"")
            val blending = blendingOf(clip)
            if (blending != null) sb.append(" blending=\"").append(blending).append("\"")
            if (adj?.parentAmId != null) sb.append(" parent=\"").append(adj.parentAmId).append("\"")
        }

        private fun blendingOf(clip: TimelineClip): String? {
            if (clip.isExcludeMask) return "exclude"
            if (clip.isMask) return "mask"
            return when (clip.blendMode) {
                "Normal" -> null
                "Darken" -> "darken"
                "Multiply" -> "multiply"
                "Color Burn" -> "color-burn"
                "Linear Burn" -> "linear-burn"
                "Darker Color" -> "darker-color"
                "Lighten" -> "lighten"
                "Lighter Color" -> "lighter-color"
                "Screen" -> "screen"
                "Color Dodge" -> "color-dodge"
                "Add" -> "linear-dodge"
                "Overlay" -> "overlay"
                "Soft Light" -> "soft-light"
                "Hard Light" -> "hard-light"
                "Soft Overlay" -> "soft-overlay"
                "Difference" -> "difference"
                "Diff" -> "diff"
                "Exclusion" -> "exclusion"
                "Subtract" -> "subtract"
                "Hue" -> "hue"
                "Saturation" -> "saturation"
                "Color" -> "color"
                "Luminosity" -> "luminosity"
                else -> {
                    note("Blend mode \"" + clip.blendMode + "\" has no Alight Motion name: written as normal")
                    null
                }
            }
        }

        // ---- parenting --------------------------------------------------------------------------

        /**
         * The constant correction turning the app's delta link into Alight's absolute one.
         * ParentRig composes world = P.pos + R(P.rot − refRot)·(P.scale / refScale)·(stored − ref);
         * the re-import composes world' = P.pos + R(P.rot)·(P.scale / 100)·(stored' − ref'), with
         * ref' the reference the importer itself recreates. Equality for every parent pose gives
         * stored' = ref' + R(−refRot)·(stored − ref)·(100 / refScale) for positions, stored scale
         * x (100 / refScale) and stored rotation − refRot — the position correction is a
         * similarity about the reference point, not a plain shift (a user link made while the
         * parent sat at scale 200 halves the child's local offsets). Parent Helper locks skip
         * only their own track's correction; the position follows in full, as in ParentRig.
         */
        private data class ParentAdjust(
            val parentAmId: String?,
            val refXp: Float, val refYp: Float,
            val refX: Float, val refY: Float,
            val posMul: Float, val refRot: Float,
            val scaleMul: Float, val rotSub: Float,
            val lockScale: Boolean, val lockRotate: Boolean
        ) {
            fun pos(x: Float, y: Float): Pair<Float, Float> {
                val vx = (x - refX) * posMul
                val vy = (y - refY) * posMul
                if (refRot == 0f) return (refXp + vx) to (refYp + vy)
                val rad = Math.toRadians(-refRot.toDouble())
                val c = cos(rad).toFloat()
                val s = sin(rad).toFloat()
                return (refXp + vx * c - vy * s) to (refYp + vx * s + vy * c)
            }
        }

        private fun parentAdjust(
            clip: TimelineClip, frame: Frame, parentGroup: TimelineClip?, inScene: Set<String>
        ): ParentAdjust? {
            val link = clip.parent
            // A group child whose link names the group itself: structural — the importer
            // recreates the link with a zero pivot (no pivot is ever written), so the
            // reference it composes against is (0, 0, 100, 0) in the child's stored units.
            if (parentGroup != null && (link == null || link.id == parentGroup.id)) {
                if (link == null) return null
                val mul = 100f / link.refScale.coerceAtLeast(0.01f)
                return ParentAdjust(
                    parentAmId = null,
                    refXp = 0f, refYp = 0f,
                    refX = link.refX, refY = link.refY,
                    posMul = mul, refRot = link.refRot,
                    scaleMul = if (link.lockScale) 1f else mul,
                    rotSub = if (link.lockRotate) 0f else link.refRot,
                    lockScale = link.lockScale, lockRotate = link.lockRotate
                )
            }
            if (link == null) return null
            val target = byId[link.id]
            if (target == null || target.id !in inScene ||
                (target.type != LayerType.NULL && target.type != LayerType.GROUP)
            ) {
                note("A layer's parent link could not be written (the parent is not an exported null or group in the same scene): the layer keeps its own motion")
                return null
            }
            // The importer recreates a plain parent link against the scene origin.
            val mul = 100f / link.refScale.coerceAtLeast(0.01f)
            return ParentAdjust(
                parentAmId = amId(target),
                refXp = -frame.w / 2f * k, refYp = -frame.h / 2f * k,
                refX = link.refX, refY = link.refY,
                posMul = mul, refRot = link.refRot,
                scaleMul = if (link.lockScale) 1f else mul,
                rotSub = if (link.lockRotate) 0f else link.refRot,
                lockScale = link.lockScale, lockRotate = link.lockRotate
            )
        }

        // ---- transforms ---------------------------------------------------------------------------

        private enum class ScaleLaw { PLAIN, SHAPE, TEXT, MEDIA }

        /**
         * The `<transform>` element: location / scale / rotation / opacity tracks. App keyframe
         * easings sit on the key a segment STARTS at; Alight's sit on the key it ENDS at — the
         * emitted key i (i >= 1) therefore carries the app key (i-1)'s easing, exactly the shift
         * the importer's trackKeys performs in reverse.
         */
        private fun emitTransform(
            sb: StringBuilder, clip: TimelineClip, frame: Frame, indent: Int,
            adj: ParentAdjust?, dur: Long, law: ScaleLaw,
            mediaContent: Pair<Float, Float>? = null
        ) {
            val pad = "  ".repeat(indent)
            val inner = StringBuilder()
            // Location: stored (centre-relative preview px) -> scene px from the top-left,
            // through the parent correction when the layer is parented.
            val loc = trackOut(clip.keyframes[AnimProp.POSITION], clip.transformX, clip.transformY, dur) { a, b ->
                val (px, py) = adj?.pos(a, b) ?: (a to b)
                listOf(px / k + frame.w / 2f, py / k + frame.h / 2f, clip.transformZ / k)
            }
            appendTrack(inner, pad, "location", loc)
            // Scale: the law per layer kind (see the class doc + the shape/text/media writers).
            val scaleMul = adj?.scaleMul ?: 1f
            val scl = when (law) {
                ScaleLaw.PLAIN -> trackOut(clip.keyframes[AnimProp.SCALE], clip.scalePercent, 0f, dur) { a, b ->
                    val sx = a * scaleMul / 100f
                    val sy = (if (b > 0f) b else a) * scaleMul / 100f
                    listOf(if (clip.isFlippedH) -sx else sx, if (clip.isFlippedV) -sy else sy)
                }
                ScaleLaw.SHAPE -> {
                    val box = sceneW * 130f / 360f
                    trackOut(clip.keyframes[AnimProp.SCALE], clip.scalePercent, clip.shapeScaleY * 100f, dur) { a, b ->
                        val sx = a / 100f * box / 200f * scaleMul
                        val sy = (if (b > 0f) b else a) / 100f * box / 200f * scaleMul
                        listOf(sx, sy)
                    }
                }
                ScaleLaw.TEXT -> {
                    val s0 = textScaleBase(clip)
                    trackOut(clip.keyframes[AnimProp.SCALE], clip.scalePercent, 0f, dur) { a, _ ->
                        val s = if (s0 > 0f) a / s0 * scaleMul else a / 100f * scaleMul
                        listOf(s, s)
                    }
                }
                ScaleLaw.MEDIA -> {
                    val (cw, ch) = mediaContent ?: (previewW to previewH)
                    trackOut(clip.keyframes[AnimProp.SCALE], clip.scalePercent, 0f, dur) { a, b ->
                        val sx = cw / k * (a * scaleMul / 100f) / 200f
                        val sy = ch / k * ((if (b > 0f) b else a) * scaleMul / 100f) / 200f
                        listOf(if (clip.isFlippedH) -sx else sx, if (clip.isFlippedV) -sy else sy)
                    }
                }
            }
            appendTrack(inner, pad, "scale", scl)
            val rotSub = adj?.rotSub ?: 0f
            val rot = trackOut(clip.keyframes[AnimProp.ROTATION], clip.rotationDeg, 0f, dur) { a, _ ->
                listOf(a - rotSub)
            }
            if (rot.constant == null || rot.constant[0] != 0f || rot.keys != null) {
                appendTrack(inner, pad, "rotation", rot)
            }
            val opa = trackOut(clip.keyframes[AnimProp.OPACITY], clip.opacity, 0f, dur) { a, _ ->
                listOf((a / 100f).coerceIn(0f, 1f))
            }
            if (opa.constant == null || opa.constant[0] != 1f || opa.keys != null) {
                appendTrack(inner, pad, "opacity", opa)
            }
            if (inner.isNotEmpty()) {
                sb.append(pad).append("<transform>\n").append(inner).append(pad).append("</transform>\n")
            }
        }

        /** A built track: either one constant value or the keyframe list. */
        private data class TrackOut(val constant: List<Float>?, val keys: List<XmlKey>?)

        private fun trackOut(
            keys: List<Keyframe>?, constA: Float, constB: Float, dur: Long,
            map: (Float, Float) -> List<Float>
        ): TrackOut {
            val ks = keys?.sortedBy { it.timeMs }?.distinctBy { it.timeMs } ?: emptyList()
            if (ks.size >= 2) {
                val out = ks.mapIndexed { i, key ->
                    XmlKey(
                        t = if (dur > 0L) (key.timeMs.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                        v = map(key.a, key.b),
                        e = if (i == 0) null else ks[i - 1].easing.takeUnless { it.isLinear }
                    )
                }
                return TrackOut(null, out)
            }
            val single = ks.firstOrNull()
            return TrackOut(map(single?.a ?: constA, single?.b ?: constB), null)
        }

        private fun appendTrack(sb: StringBuilder, pad: String, tag: String, track: TrackOut) {
            if (track.keys != null) {
                sb.append(pad).append("<").append(tag).append(">\n")
                for (key in track.keys) {
                    sb.append(pad).append("  <kf t=\"").append(fmt(key.t)).append("\" v=\"")
                        .append(key.v.joinToString(",") { fmt(it) }).append("\"")
                    if (key.e != null) {
                        sb.append(" e=\"cubicBezier ").append(fmt(key.e.x1)).append(' ')
                            .append(fmt(key.e.y1)).append(' ').append(fmt(key.e.x2)).append(' ')
                            .append(fmt(key.e.y2)).append("\"")
                    }
                    sb.append(" />\n")
                }
                sb.append(pad).append("</").append(tag).append(">\n")
            } else if (track.constant != null) {
                sb.append(pad).append("<").append(tag).append(" value=\"")
                    .append(track.constant.joinToString(",") { fmt(it) }).append("\" />\n")
            }
        }

        // ---- groups -------------------------------------------------------------------------------

        private fun emitGroup(
            sb: StringBuilder, clip: TimelineClip, frame: Frame, indent: Int,
            start: Long, dur: Long, adj: ParentAdjust?
        ) {
            val pad = "  ".repeat(indent)
            val nW = if (clip.embedW > 0f) clip.embedW / k else frame.w
            val nH = if (clip.embedH > 0f) clip.embedH / k else frame.h
            sb.append(pad).append("<embedScene")
            commonAttrs(sb, clip, start, start + dur, adj)
            sb.append(" fillType=\"intrinsic\" outTime=\"").append(dur)
            sb.append("\" mediaFillMode=\"stretch\">\n")
            emitTransform(sb, clip, frame, indent + 1, adj, dur, ScaleLaw.PLAIN)
            emitEffects(sb, clip, indent + 1, adj)
            sb.append(pad).append("  <scene width=\"").append(fmt(nW)).append("\" height=\"")
                .append(fmt(nH)).append("\" totalTime=\"").append(dur)
                .append("\" fps=\"").append(fps).append("\">\n")
            val children = snapshot.tracks.flatMap { it.clips }.filter { it.groupId == clip.id }
            emitScene(sb, children, Frame(nW, nH), indent + 2,
                timeShiftMs = clip.startMs, windowMs = dur, parentGroup = clip)
            sb.append(pad).append("  </scene>\n")
            sb.append(pad).append("</embedScene>\n")
            layersWritten++
        }

        // ---- camera -------------------------------------------------------------------------------

        private fun emitCamera(
            sb: StringBuilder, clip: TimelineClip, frame: Frame, indent: Int,
            start: Long, end: Long, dur: Long, adj: ParentAdjust?
        ) {
            val pad = "  ".repeat(indent)
            sb.append(pad).append("<camera")
            commonAttrs(sb, clip, start, end, adj)
            sb.append(" type=\"perspective\">\n")
            val inner = StringBuilder()
            val ipad = "  ".repeat(indent + 1)
            // The importer rebuilds a camera's zoom from location z + fov 60: emit fov's
            // default geometry — z from the SCALE (zoom) track, x/y from POSITION, sampled
            // at the union of both tracks' key times (readCamera samples the same union).
            val posKeys = clip.keyframes[AnimProp.POSITION]?.sortedBy { it.timeMs } ?: emptyList()
            val sclKeys = clip.keyframes[AnimProp.SCALE]?.sortedBy { it.timeMs } ?: emptyList()
            val defZ = -(frame.h / 2f) / tan(Math.toRadians(30.0)).toFloat()
            fun zAt(relMs: Long): Float {
                val zoom = if (sclKeys.isNotEmpty()) sclKeys.valueAt(relMs).first
                else clip.scalePercent
                return if (zoom > 0.01f) defZ * 100f / zoom else defZ
            }
            fun xyAt(relMs: Long): Pair<Float, Float> {
                val v = if (posKeys.isNotEmpty()) posKeys.valueAt(relMs)
                else clip.transformX to clip.transformY
                val (px, py) = adj?.pos(v.first, v.second) ?: v
                return (px / k + frame.w / 2f) to (py / k + frame.h / 2f)
            }
            val times = (posKeys.map { it.timeMs } + sclKeys.map { it.timeMs })
                .distinct().sorted()
            if (times.size >= 2) {
                inner.append(ipad).append("<location>\n")
                times.forEachIndexed { i, t ->
                    val (x, y) = xyAt(t)
                    inner.append(ipad).append("  <kf t=\"")
                        .append(fmt(if (dur > 0L) t.toFloat() / dur else 0f))
                        .append("\" v=\"").append(fmt(x)).append(',').append(fmt(y)).append(',')
                        .append(fmt(zAt(t) / k)).append("\"")
                    if (i > 0) {
                        val prev = times[i - 1]
                        val e = (if (posKeys.any { it.timeMs == prev }) posKeys.first { it.timeMs == prev }.easing
                        else sclKeys.firstOrNull { it.timeMs == prev }?.easing) ?: Easing.LINEAR
                        if (!e.isLinear) {
                            inner.append(" e=\"cubicBezier ").append(fmt(e.x1)).append(' ')
                                .append(fmt(e.y1)).append(' ').append(fmt(e.x2)).append(' ')
                                .append(fmt(e.y2)).append("\"")
                        }
                    }
                    inner.append(" />\n")
                }
                inner.append(ipad).append("</location>\n")
            } else {
                val (x, y) = xyAt(0L)
                inner.append(ipad).append("<location value=\"").append(fmt(x)).append(',')
                    .append(fmt(y)).append(',').append(fmt(zAt(0L) / k)).append("\" />\n")
            }
            val rot = trackOut(clip.keyframes[AnimProp.ROTATION], clip.rotationDeg, 0f, dur) { a, _ ->
                listOf(a - (adj?.rotSub ?: 0f))
            }
            if (rot.constant == null || rot.constant[0] != 0f || rot.keys != null) {
                appendTrack(inner, ipad, "rotation", rot)
            }
            if (inner.isNotEmpty()) {
                sb.append(ipad).append("<transform>\n").append(inner).append(ipad).append("</transform>\n")
            }
            sb.append(pad).append("</camera>\n")
            layersWritten++
        }

        // ---- audio ----------------------------------------------------------------------------------

        private fun emitAudio(sb: StringBuilder, clip: TimelineClip, indent: Int, start: Long, end: Long) {
            val pad = "  ".repeat(indent)
            val uri = clip.uri
            if (uri.isNullOrBlank()) {
                omittedLayers += "Audio layer \"" + clip.name + "\" has no file to reference"
                return
            }
            sb.append(pad).append("<audio")
            sb.append(" id=\"").append(amId(clip)).append("\"")
            if (clip.name.isNotBlank()) sb.append(" label=\"").append(escAttr(clip.name)).append("\"")
            sb.append(" startTime=\"").append(start).append("\" endTime=\"").append(end).append("\"")
            sb.append(" src=\"").append(escAttr(uri)).append("\"")
            if (clip.trimStartMs > 0L) sb.append(" inTime=\"").append(clip.trimStartMs).append("\"")
            // The window: a stretched span encodes the speed (the importer's own rule);
            // at speed 1 the file's own end is kept so the source length survives.
            if (clip.speed != 1f) {
                sb.append(" outTime=\"")
                    .append(clip.trimStartMs + (clip.durationMs * clip.speed).roundToLong()).append("\"")
                sb.append(" mediaFillMode=\"stretch\"")
            } else {
                val outT = maxOf(clip.sourceDurationMs, clip.trimStartMs + clip.durationMs)
                sb.append(" outTime=\"").append(outT).append("\" mediaFillMode=\"fill\"")
            }
            if (clip.volume != 1f || clip.keyframes[AnimProp.SPEED]?.isNotEmpty() == true) {
                sb.append(">\n")
                if (clip.volume != 1f) {
                    val v = clip.volume.coerceIn(0f, 1f)
                    if (v != clip.volume) note("An audio layer's volume above 1 is written as 1 (Alight's volume range)")
                    sb.append(pad).append("  <volume value=\"").append(fmt(v)).append("\" />\n")
                }
                sb.append(pad).append("</audio>\n")
            } else {
                sb.append(" />\n")
            }
            layersWritten++
        }

        // ---- text -----------------------------------------------------------------------------------

        /** The scale % a text layer sits at when its size attribute is derived (its t=0 scale). */
        private fun textScaleBase(clip: TimelineClip): Float {
            val ks = clip.keyframes[AnimProp.SCALE]?.sortedBy { it.timeMs }
            return ks?.firstOrNull()?.a ?: clip.scalePercent
        }

        private fun emitText(
            sb: StringBuilder, clip: TimelineClip, frame: Frame, indent: Int,
            start: Long, end: Long, dur: Long, adj: ParentAdjust?
        ) {
            val pad = "  ".repeat(indent)
            // Inverse of readText's scalePct: scalePct = size * scl * sceneW / 640 /
            // (0.11 * rootW) * 100 — the size is chosen so the layer's own scale starts at 1.
            val s0 = textScaleBase(clip)
            val size = if (s0 > 0f) s0 * 0.704f * sceneW / frame.w else 30f
            val font = when (clip.fontFamily) {
                "Roboto", "Serif", "Monospace", "Cursive" ->
                    "googlefonts?name=" + clip.fontFamily + "&weight=" + clip.fontWeight.coerceIn(100, 900)
                else -> "imported?name=" + clip.fontFamily
            }
            sb.append(pad).append("<text")
            commonAttrs(sb, clip, start, end, adj)
            sb.append(" fillType=\"color\" mediaFillMode=\"stretch\"")
            sb.append(" size=\"").append(fmt(size)).append("\"")
            sb.append(" font=\"").append(escAttr(font)).append("\"")
            if (clip.textWrapFrac > 0f) {
                sb.append(" wrapWidth=\"").append(fmt(clip.textWrapFrac * sceneW)).append("\"")
            }
            sb.append(" align=\"").append(
                when (clip.textAlign) { 0 -> "left"; 2 -> "right"; else -> "center" }
            ).append("\"")
            sb.append(" fillColor=\"").append(colorHex(clip.textColor)).append("\">\n")
            emitTransform(sb, clip, frame, indent + 1, adj, dur, ScaleLaw.TEXT)
            sb.append(pad).append("  <content>").append(escText(clip.text ?: "")).append("</content>\n")
            emitEffects(sb, clip, indent + 1, adj)
            emitAnimators(sb, clip, indent + 1)
            sb.append(pad).append("</text>\n")
            layersWritten++
        }

        private fun emitAnimators(sb: StringBuilder, clip: TimelineClip, indent: Int) {
            val pad = "  ".repeat(indent)
            for (anim in clip.textAnimators) {
                sb.append(pad).append("<effect id=\"").append(escAttr(anim.effectId))
                    .append("\" locallyApplied=\"false\">\n")
                for ((propName, av) in anim.props) {
                    sb.append(pad).append("  <property name=\"").append(escAttr(propName))
                        .append("\" type=\"").append(escAttr(av.type)).append("\"")
                    if (av.keys.size >= 2) {
                        sb.append(">\n")
                        for (key in av.keys) {
                            sb.append(pad).append("    <kf t=\"").append(fmt(key.t)).append("\" v=\"")
                                .append(key.v.joinToString(",") { fmt(it) }).append("\"")
                            val e = key.easing
                            if (e != null && !e.isLinear) {
                                sb.append(" e=\"cubicBezier ").append(fmt(e.x1)).append(' ')
                                    .append(fmt(e.y1)).append(' ').append(fmt(e.x2)).append(' ')
                                    .append(fmt(e.y2)).append("\"")
                            }
                            sb.append(" />\n")
                        }
                        sb.append(pad).append("  </property>\n")
                    } else {
                        val v = av.keys.firstOrNull()?.v ?: av.value
                        sb.append(" value=\"").append(v.joinToString(",") { fmt(it) }).append("\" />\n")
                    }
                }
                sb.append(pad).append("</effect>\n")
            }
        }

        // ---- shapes ---------------------------------------------------------------------------------

        private fun emitShape(
            sb: StringBuilder, clip: TimelineClip, frame: Frame, indent: Int,
            start: Long, end: Long, dur: Long, adj: ParentAdjust?
        ) {
            val pad = "  ".repeat(indent)
            val pattern = clip.shapePattern ?: "RECT"
            val isPath = pattern == "PATH"
            if (!isPath && pattern != "RECT" && pattern != "CIRCLE" && pattern != "ROUNDED_RECT") {
                if (clip.pathSegments.isEmpty()) {
                    omittedLayers += "Shape layer \"" + clip.name + "\" of kind " + pattern +
                        " (no Alight Motion counterpart)"
                    return
                }
                note("A parametric shape is written as its current outline (a static path)")
            }
            sb.append(pad).append("<shape")
            commonAttrs(sb, clip, start, end, adj)
            // Fill.
            when (clip.fillMode) {
                FILL_NONE -> sb.append(" fillType=\"none\"")
                FILL_IMAGE -> {
                    sb.append(" fillType=\"media\"")
                    if (!clip.fillImageUri.isNullOrBlank()) {
                        sb.append(" fillImage=\"").append(escAttr(clip.fillImageUri)).append("\"")
                    }
                    sb.append(" mediaFillMode=\"").append(
                        when (clip.fillImageMode) {
                            IMG_FIT -> "fit"; IMG_STRETCH -> "stretch"; else -> "fill"
                        }
                    ).append("\"")
                }
                FILL_VIDEO -> {
                    sb.append(" fillType=\"media\"")
                    if (!clip.fillVideoUri.isNullOrBlank()) {
                        sb.append(" fillVideo=\"").append(escAttr(clip.fillVideoUri)).append("\"")
                    }
                    if (clip.trimStartMs > 0L) sb.append(" inTime=\"").append(clip.trimStartMs).append("\"")
                    if (clip.speed != 1f) {
                        sb.append(" outTime=\"")
                            .append(clip.trimStartMs + (clip.durationMs * clip.speed).roundToLong())
                            .append("\" mediaFillMode=\"stretch\"")
                    } else {
                        val outT = maxOf(clip.sourceDurationMs, clip.trimStartMs + clip.durationMs)
                        sb.append(" outTime=\"").append(outT).append("\" mediaFillMode=\"fill\"")
                    }
                }
                FILL_GRADIENT -> {
                    sb.append(" fillType=\"color\"")
                    note("A gradient fill is written as its first colour (the gradient itself has no layer-level form the importer reads)")
                }
                else -> sb.append(" fillType=\"color\"")
            }
            if (!isPath && pattern != "RECT" && pattern != "CIRCLE" && pattern != "ROUNDED_RECT") {
                // parametric kind written as a path: no s attribute
            } else if (!isPath) {
                sb.append(" s=\"").append(
                    when (pattern) {
                        "CIRCLE" -> ".circle"
                        "ROUNDED_RECT" -> ".roundrect"
                        else -> ".rect"
                    }
                ).append("\"")
            }
            sb.append(">\n")
            emitTransform(sb, clip, frame, indent + 1, adj, dur, ScaleLaw.SHAPE)
            if (clip.fillMode == FILL_SOLID || clip.fillMode == FILL_GRADIENT) {
                val c = if (clip.fillMode == FILL_GRADIENT) clip.gradColor1 else clip.textColor
                sb.append(pad).append("  <fillColor value=\"").append(colorHex(c)).append("\" />\n")
            }
            if (pattern == "RECT" || pattern == "CIRCLE" || pattern == "ROUNDED_RECT") {
                sb.append(pad).append("  <property name=\"size\" type=\"vec2\" value=\"100,100\" />\n")
                if (pattern == "ROUNDED_RECT") emitCornerRadius(sb, clip, indent + 1, dur)
            }
            if (clip.strokeWidth > 0f) {
                sb.append(pad).append("  <path-stroke")
                if (clip.strokeCapStartSize > 0f) {
                    sb.append(" start=\"circle-filled\" start-size=\"").append(fmt(clip.strokeCapStartSize)).append("\"")
                }
                if (clip.strokeCapEndSize > 0f) {
                    sb.append(" end=\"circle-filled\" end-size=\"").append(fmt(clip.strokeCapEndSize)).append("\"")
                }
                sb.append(">\n")
                sb.append(pad).append("    <color value=\"").append(colorHex(clip.strokeColor)).append("\" />\n")
                sb.append(pad).append("    <size value=\"").append(fmt(clip.strokeWidth * 200f / 130f)).append("\" />\n")
                sb.append(pad).append("  </path-stroke>\n")
            }
            if (isPath || (pattern != "RECT" && pattern != "CIRCLE" && pattern != "ROUNDED_RECT")) {
                if (clip.pathSegments.isNotEmpty()) {
                    sb.append(pad).append("  <path d=\"").append(pathData(clip)).append("\" />\n")
                }
            }
            emitEffects(sb, clip, indent + 1, adj)
            sb.append(pad).append("</shape>\n")
            layersWritten++
        }

        private fun emitCornerRadius(sb: StringBuilder, clip: TimelineClip, indent: Int, dur: Long) {
            val pad = "  ".repeat(indent)
            val keys = clip.keyframes[AnimProp.SHAPE_CORNER_RADIUS]?.sortedBy { it.timeMs }
                ?.distinctBy { it.timeMs } ?: emptyList()
            if (keys.size >= 2) {
                sb.append(pad).append("<property name=\"cornerRadius\" type=\"float\">\n")
                keys.forEachIndexed { i, key ->
                    sb.append(pad).append("  <kf t=\"")
                        .append(fmt(if (dur > 0L) (key.timeMs.toFloat() / dur).coerceIn(0f, 1f) else 0f))
                        .append("\" v=\"").append(fmt(key.a / 65f * 100f)).append("\"")
                    if (i > 0) {
                        val e = keys[i - 1].easing
                        if (!e.isLinear) {
                            sb.append(" e=\"cubicBezier ").append(fmt(e.x1)).append(' ')
                                .append(fmt(e.y1)).append(' ').append(fmt(e.x2)).append(' ')
                                .append(fmt(e.y2)).append("\"")
                        }
                    }
                    sb.append(" />\n")
                }
                sb.append(pad).append("</property>\n")
            } else if (clip.shapeCornerRadius >= 0f) {
                sb.append(pad).append("<property name=\"cornerRadius\" type=\"float\" value=\"")
                    .append(fmt(clip.shapeCornerRadius / 65f * 100f)).append("\" />\n")
            }
        }

        /** The stored outline (bbox-centred, larger half extent 1) back in Alight's ±100 space. */
        private fun pathData(clip: TimelineClip): String {
            val sb = StringBuilder()
            for (seg in clip.pathSegments) {
                val p = seg.pts.map { it * 100f }
                when (seg.op) {
                    PATH_OP_MOVE -> sb.append("M ").append(fmt(p.getOrElse(0) { 0f })).append(' ')
                        .append(fmt(p.getOrElse(1) { 0f }))
                    PATH_OP_LINE -> sb.append("L ").append(fmt(p.getOrElse(0) { 0f })).append(' ')
                        .append(fmt(p.getOrElse(1) { 0f }))
                    PATH_OP_QUAD -> sb.append("Q ")
                        .append(fmt(p.getOrElse(0) { 0f })).append(' ').append(fmt(p.getOrElse(1) { 0f })).append(' ')
                        .append(fmt(p.getOrElse(2) { 0f })).append(' ').append(fmt(p.getOrElse(3) { 0f }))
                    PATH_OP_CUBIC -> sb.append("C ")
                        .append(fmt(p.getOrElse(0) { 0f })).append(' ').append(fmt(p.getOrElse(1) { 0f })).append(' ')
                        .append(fmt(p.getOrElse(2) { 0f })).append(' ').append(fmt(p.getOrElse(3) { 0f })).append(' ')
                        .append(fmt(p.getOrElse(4) { 0f })).append(' ').append(fmt(p.getOrElse(5) { 0f }))
                    PATH_OP_CLOSE -> sb.append("Z")
                }
            }
            return sb.toString()
        }

        // ---- media layers (video / image / sticker -> a media shape) --------------------------------

        private fun emitMediaLayer(
            sb: StringBuilder, clip: TimelineClip, frame: Frame, indent: Int,
            start: Long, end: Long, dur: Long, adj: ParentAdjust?
        ) {
            val pad = "  ".repeat(indent)
            val uri = clip.uri
            if (uri.isNullOrBlank()) {
                omittedLayers += "Media layer \"" + clip.name + "\" has no file to reference"
                return
            }
            val isVideo = !clip.isImage &&
                (clip.mimeType?.startsWith("video") == true || clip.type == LayerType.VIDEO)
            // The layer's content box: its picture fitted into the frame by aspect (the
            // painters' own mediaVisibleSize law), in stored units.
            val a = if (clip.sourceAspect > 0f) clip.sourceAspect else previewW / previewH
            val cw = if (a > previewW / previewH) previewW else previewH * a
            val ch = if (a > previewW / previewH) previewW / a else previewH
            sb.append(pad).append("<shape")
            commonAttrs(sb, clip, start, end, adj)
            sb.append(" fillType=\"media\"")
            if (isVideo) {
                sb.append(" fillVideo=\"").append(escAttr(uri)).append("\"")
                if (clip.trimStartMs > 0L) sb.append(" inTime=\"").append(clip.trimStartMs).append("\"")
                if (clip.speed != 1f) {
                    sb.append(" outTime=\"")
                        .append(clip.trimStartMs + (clip.durationMs * clip.speed).roundToLong())
                        .append("\" mediaFillMode=\"stretch\"")
                } else {
                    val outT = maxOf(clip.sourceDurationMs, clip.trimStartMs + clip.durationMs)
                    sb.append(" outTime=\"").append(outT).append("\" mediaFillMode=\"fill\"")
                }
            } else {
                sb.append(" fillImage=\"").append(escAttr(uri)).append("\" mediaFillMode=\"fill\"")
            }
            sb.append(" s=\".rect\">\n")
            emitTransform(sb, clip, frame, indent + 1, adj, dur, ScaleLaw.MEDIA, cw to ch)
            sb.append(pad).append("  <property name=\"size\" type=\"vec2\" value=\"100,100\" />\n")
            emitEffects(sb, clip, indent + 1, adj)
            sb.append(pad).append("</shape>\n")
            layersWritten++
        }

        // ---- effects --------------------------------------------------------------------------------

        /**
         * The effect elements of one layer: the unambiguous subset of [clip.effects] (in the
         * layer's own order), then the fade effect from the clip's fade fields, the
         * parenthelper from its link locks, and a text layer's textspacing. Everything else is
         * counted in the report. (Never called for camera or audio layers: the importer reads
         * no effects there — a camera with an effect element is even skipped whole.)
         */
        private fun emitEffects(sb: StringBuilder, clip: TimelineClip, indent: Int, adj: ParentAdjust?) {
            val pad = "  ".repeat(indent)
            for (fx in clip.effects) {
                if (!fx.enabled) {
                    omitFx(fx.type)
                    note("Disabled effects are not written (Alight's hidden flag reads back as dropped in the importer)")
                    continue
                }
                when (fx.type) {
                    ClipFxLibrary.EXPOSURE -> emitExposure(sb, fx, pad)
                    ClipFxLibrary.BRIGHT_CONTRAST -> emitBrightContrast(sb, fx, pad)
                    ClipFxLibrary.RGB_SPLIT -> emitRgbSplit(sb, fx, pad)
                    ClipFxLibrary.VIGNETTE -> emitVignette(sb, fx, pad)
                    ClipFxLibrary.INVERT -> {
                        if ((fx.values.getOrNull(0) ?: 0f) >= 50f) emitInvert(sb, pad)
                        else omitFx(fx.type)
                    }
                    ClipFxLibrary.TILE -> emitTile(sb, fx, pad)
                    ClipFxLibrary.GAUSSIAN_BLUR_AM -> emitGaussianBlur(sb, fx, pad)
                    ClipFxLibrary.HUE_SAT -> emitHueSat(sb, fx, pad)
                    else -> omitFx(fx.type)
                }
            }
            if (clip.fadeInMs > 0L || clip.fadeOutMs > 0L) {
                sb.append(pad).append("<effect id=\"com.alightcreative.effects.fade\" locallyApplied=\"false\">\n")
                if (clip.fadeInMs > 0L) {
                    sb.append(pad).append("  <property name=\"inTime\" type=\"float\" value=\"")
                        .append(fmt(clip.fadeInMs / 1000f)).append("\" />\n")
                }
                if (clip.fadeOutMs > 0L) {
                    sb.append(pad).append("  <property name=\"outTime\" type=\"float\" value=\"")
                        .append(fmt(clip.fadeOutMs / 1000f)).append("\" />\n")
                }
                sb.append(pad).append("</effect>\n")
            }
            if (adj != null && (adj.lockScale || adj.lockRotate)) {
                sb.append(pad).append("<effect id=\"com.alightcreative.effects.parenthelper\" locallyApplied=\"false\">\n")
                if (adj.lockScale) {
                    sb.append(pad).append("  <property name=\"scaleMode\" type=\"int\" value=\"1\" />\n")
                }
                if (adj.lockRotate) {
                    sb.append(pad).append("  <property name=\"rotateMode\" type=\"int\" value=\"1\" />\n")
                }
                sb.append(pad).append("</effect>\n")
            }
            if (clip.type == LayerType.TEXT &&
                (clip.letterSpacing != 0f || clip.lineHeightPercent != 120f)
            ) {
                sb.append(pad).append("<effect id=\"com.alightcreative.effects.textspacing\" locallyApplied=\"false\">\n")
                if (clip.letterSpacing != 0f) {
                    sb.append(pad).append("  <property name=\"letterspacing\" type=\"float\" value=\"")
                        .append(fmt(clip.letterSpacing / 100f)).append("\" />\n")
                }
                if (clip.lineHeightPercent != 120f) {
                    sb.append(pad).append("  <property name=\"linespacing\" type=\"float\" value=\"")
                        .append(fmt(clip.lineHeightPercent / 100f)).append("\" />\n")
                }
                sb.append(pad).append("</effect>\n")
            }
        }

        private fun fxHead(sb: StringBuilder, pad: String, alightId: String) {
            sb.append(pad).append("<effect id=\"").append(alightId).append("\" locallyApplied=\"false\">\n")
        }

        /** One float property: a constant value, or the decoded tail as keyframes (raw units). */
        private fun fxProp(
            sb: StringBuilder, pad: String, name: String,
            constVal: Float, tail: List<TailKey>?, constMap: (Float) -> Float = { it }
        ) {
            if (tail != null) {
                sb.append(pad).append("  <property name=\"").append(name).append("\" type=\"float\">\n")
                for (key in tail) {
                    sb.append(pad).append("    <kf t=\"").append(fmt(key.t)).append("\" v=\"")
                        .append(fmt(key.v)).append("\"")
                    val e = key.e
                    if (e != null && !e.isLinear) {
                        sb.append(" e=\"cubicBezier ").append(fmt(e.x1)).append(' ')
                            .append(fmt(e.y1)).append(' ').append(fmt(e.x2)).append(' ')
                            .append(fmt(e.y2)).append("\"")
                    }
                    sb.append(" />\n")
                }
                sb.append(pad).append("  </property>\n")
            } else {
                sb.append(pad).append("  <property name=\"").append(name)
                    .append("\" type=\"float\" value=\"").append(fmt(constMap(constVal))).append("\" />\n")
            }
        }

        private fun fxPropBool(sb: StringBuilder, pad: String, name: String, v: Boolean) {
            sb.append(pad).append("  <property name=\"").append(name)
                .append("\" type=\"bool\" value=\"").append(if (v) "true" else "false").append("\" />\n")
        }

        /**
         * Decodes up to [count] tail tracks from [values] in the importer's append order:
         * at index [AmFxEval.TAIL_START], each track is a count then [t, v, easing x4] per key;
         * a count of 0 is the one-float placeholder a constant parameter leaves when a sibling
         * is animated. Anything malformed yields nulls (the constants still export).
         */
        private fun decodeTails(values: List<Float>, count: Int): List<List<TailKey>?> {
            val out = ArrayList<List<TailKey>?>(count)
            var pos = AmFxEval.TAIL_START
            for (i in 0 until count) {
                if (pos >= values.size) {
                    out += null
                    continue
                }
                val n = values[pos].toInt()
                when {
                    n == 0 -> {
                        out += null
                        pos += 1
                    }
                    // The length check is LONG math (build 198): a corrupted count
                    // (n ~ 4e8) overflows n * 6 in Int, wraps negative, passes an Int
                    // check and the key loop then reads / allocates past the list.
                    n >= 2 && pos + 1L + n.toLong() * 6L <= values.size.toLong() -> {
                        val keys = ArrayList<TailKey>(n)
                        for (j in 0 until n) {
                            val b = pos + 1 + j * 6
                            keys += TailKey(
                                values[b], values[b + 1],
                                Easing(values[b + 2], values[b + 3], values[b + 4], values[b + 5])
                            )
                        }
                        out += keys
                        pos += 1 + n * 6
                    }
                    else -> {
                        out += null
                        pos = values.size   // misaligned: trust nothing after this point
                    }
                }
            }
            return out
        }

        private fun emitExposure(sb: StringBuilder, fx: ClipFx, pad: String) {
            val tails = decodeTails(fx.values, 3)
            fxHead(sb, pad, "com.alightcreative.effects.exposure")
            fxProp(sb, pad, "exposure", fx.values.getOrElse(0) { 0f }, tails[0])
            fxProp(sb, pad, "offset", fx.values.getOrElse(1) { 0f }, tails[1]) { it / 100f }
            fxProp(sb, pad, "gamma", fx.values.getOrElse(2) { 100f }, tails[2]) { it / 100f }
            sb.append(pad).append("</effect>\n")
        }

        private fun emitBrightContrast(sb: StringBuilder, fx: ClipFx, pad: String) {
            val tails = decodeTails(fx.values, 2)
            fxHead(sb, pad, "com.alightcreative.effects.brightcont2")
            fxProp(sb, pad, "brightness", fx.values.getOrElse(0) { 0f }, tails[0]) { it / 100f }
            fxProp(sb, pad, "contrast", fx.values.getOrElse(1) { 0f }, tails[1]) { it / 100f }
            sb.append(pad).append("</effect>\n")
        }

        private fun emitRgbSplit(sb: StringBuilder, fx: ClipFx, pad: String) {
            val tails = decodeTails(fx.values, 2)
            fxHead(sb, pad, "com.alightcreative.effects.rgbsep")
            fxProp(sb, pad, "strength", fx.values.getOrElse(0) { 0f }, tails[0]) { it / 100f }
            fxProp(sb, pad, "angle", fx.values.getOrElse(1) { 0f }, tails[1])
            sb.append(pad).append("</effect>\n")
        }

        private fun emitVignette(sb: StringBuilder, fx: ClipFx, pad: String) {
            fxHead(sb, pad, "com.alightcreative.effects.vignette")
            fxProp(sb, pad, "strength", fx.values.getOrElse(0) { 0f }, null) { it / 100f }
            fxProp(sb, pad, "scale", fx.values.getOrElse(1) { 100f }, null) { it / 100f }
            fxProp(sb, pad, "feather", fx.values.getOrElse(2) { 0f }, null) { it / 100f }
            sb.append(pad).append("</effect>\n")
        }

        private fun emitInvert(sb: StringBuilder, pad: String) {
            fxHead(sb, pad, "com.alightcreative.effects.invert")
            fxPropBool(sb, pad, "red", true)
            fxPropBool(sb, pad, "green", true)
            fxPropBool(sb, pad, "blue", true)
            sb.append(pad).append("</effect>\n")
        }

        private fun emitTile(sb: StringBuilder, fx: ClipFx, pad: String) {
            val tails = decodeTails(fx.values, 2)
            val v0 = fx.values.getOrElse(0) { 1f }
            val v1 = fx.values.getOrElse(1) { v0 }
            if (abs(v1 - v0) > 0.01f) {
                note("A tile effect with different horizontal and vertical counts is written with the horizontal count")
            }
            fxHead(sb, pad, "com.alightcreative.effects.tile")
            fxProp(sb, pad, "scale", v0, tails[0])
            fxPropBool(sb, pad, "mirror", (fx.values.getOrElse(2) { 0f }) > 0.5f)
            fxProp(sb, pad, "phase", fx.values.getOrElse(3) { 0f }, tails[1]) { it / 100f }
            sb.append(pad).append("</effect>\n")
        }

        private fun emitGaussianBlur(sb: StringBuilder, fx: ClipFx, pad: String) {
            val tails = decodeTails(fx.values, 1)
            fxHead(sb, pad, "com.alightcreative.effects.gaussianblur")
            fxProp(sb, pad, "strength", fx.values.getOrElse(0) { 0f }, tails[0])
            sb.append(pad).append("</effect>\n")
        }

        private fun wrapHue(deg: Float): Float = ((deg % 360f) + 540f) % 360f - 180f

        private fun emitHueSat(sb: StringBuilder, fx: ClipFx, pad: String) {
            val h = fx.values.getOrElse(0) { 0f }
            val s = fx.values.getOrElse(1) { 0f }
            val x = fx.values.getOrElse(2) { 0f }
            val vib = fx.values.getOrElse(3) { 0f }
            if (abs(x) > 0.01f) {
                // The third hue_saturation slot (a lightness shift) exists in neither
                // hueshift nor satvib: the whole effect is omitted rather than half-written.
                omitFx(fx.type)
                return
            }
            val tails = decodeTails(fx.values, 2)
            val hueTail = tails[0]
            val satTail = tails[1]
            var wrote = false
            if (abs(h) > 0.01f || hueTail != null) {
                fxHead(sb, pad, "com.alightcreative.effects.hueshift")
                fxProp(sb, pad, "hue", h, hueTail) { wrapHue(it) }
                sb.append(pad).append("</effect>\n")
                wrote = true
            }
            if (abs(s) > 0.01f || satTail != null || abs(vib) > 0.01f) {
                fxHead(sb, pad, "com.alightcreative.effects.satvib")
                fxProp(sb, pad, "saturation", s, satTail) { it / 100f }
                fxProp(sb, pad, "vib", vib, null) { it / 100f + 1f }
                sb.append(pad).append("</effect>\n")
                wrote = true
            }
            if (!wrote) note("A hue/saturation effect at its neutral values writes nothing")
        }

        // ---- per-layer feature losses ---------------------------------------------------------------

        /** Counts the features of a written layer that this export deliberately does not carry. */
        private fun featureNotes(clip: TimelineClip) {
            val keys = clip.keyframes
            if (clip.cropWidthPercent < 99.9f || clip.cropHeightPercent < 99.9f ||
                keys[AnimProp.CROP_SIZE]?.isNotEmpty() == true || keys[AnimProp.CROP_POS]?.isNotEmpty() == true
            ) note("Crop is not written (the importer reads no layer-level crop)")
            if (keys[AnimProp.POS_Z]?.isNotEmpty() == true || keys[AnimProp.ROT_X]?.isNotEmpty() == true ||
                keys[AnimProp.ROT_Y]?.isNotEmpty() == true || clip.rotation3dX != 0f || clip.rotation3dY != 0f
            ) note("3D rotation and depth are not written")
            if (clip.warpDistort != 0f) note("Warp is not written")
            if (keys[AnimProp.SPEED]?.isNotEmpty() == true) {
                note("Speed keyframes are not written (a constant speed is carried by the media window)")
            }
            if (keys[AnimProp.FILL_COLOR]?.isNotEmpty() == true ||
                keys[AnimProp.GRAD_COLOR_1]?.isNotEmpty() == true ||
                keys[AnimProp.GRAD_COLOR_2]?.isNotEmpty() == true ||
                keys[AnimProp.GRAD_GEOMETRY]?.isNotEmpty() == true
            ) note("Fill colour and gradient keyframes are not written (the constant colour is)")
            if (clip.type == LayerType.SHAPE &&
                (clip.strokeTrimStart > 0f || clip.strokeTrimEnd < 1f ||
                    keys[AnimProp.STROKE_TRIM]?.isNotEmpty() == true)
            ) note("Stroke trim (draw-on) is not written")
            if ((clip.isFlippedH || clip.isFlippedV) &&
                (clip.type == LayerType.SHAPE || clip.type == LayerType.TEXT)
            ) note("A shape or text layer's flip is not written")
            if (clip.type == LayerType.TEXT) {
                if (clip.isItalic || clip.isUnderline || clip.isStrikethrough) {
                    note("Text italic, underline and strikethrough are not written")
                }
                if (clip.textStrokeThickness > 0f) note("A text outline is not written")
                if (clip.shadowOpacity > 0f &&
                    (clip.shadowOpacity != 60f || clip.shadowBlur != 30f || clip.shadowDistance != 10f ||
                        clip.shadowAngle != 45f || clip.shadowColor != 0xFF000000L)
                ) note("A text shadow is not written")
                if (clip.bgOpacity > 0f) note("A text background box is not written")
                if (clip.textPathLayerId != null) {
                    note("Text on a path is written flat (the path attachment is not written)")
                }
            }
            if (clip.type == LayerType.AUDIO) {
                if (clip.parent != null) note("An audio layer's parent link is not written")
                if (clip.duckEnabled) note("Audio ducking is not written")
                if (clip.nrEnabled) note("Noise reduction is not written")
                if (clip.isMuted) note("An audio layer's mute is not written")
                if (clip.fadeInMs > 0L || clip.fadeOutMs > 0L) {
                    note("An audio layer's fade is not written (the importer reads no audio effects)")
                }
            }
            if (clip.masks.isNotEmpty()) {
                note("A layer's own masks are not written (mask layers are written)")
            }
            if (clip.controllerId != null) note("A controller link is not written")
            if (clip.chainId != null) {
                note("Chain membership is not written (the layer is written as a normal layer)")
            }
            if (clip.type != LayerType.CAMERA && (clip.pivotX != 0.5f || clip.pivotY != 0.5f)) {
                note("A non-centre anchor point is not written (position and rotation values are kept)")
            }
            if (!clip.isVisible && !clip.isMask) {
                note("A hidden layer is written visible (layer visibility has no form the importer reads)")
            }
        }
    }
}
