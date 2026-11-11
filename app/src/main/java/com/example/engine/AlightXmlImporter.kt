package com.example.engine

import com.example.model.AnimKey
import com.example.model.AnimProp
import com.example.model.AnimValue
import com.example.model.AspectRatio
import com.example.model.ClipFx
import com.example.model.ClipFxLibrary
import com.example.model.ClipMask
import com.example.model.Easing
import com.example.model.FILL_GRADIENT
import com.example.model.FILL_IMAGE
import com.example.model.FILL_NONE
import com.example.model.FILL_SOLID
import com.example.model.GRAD_LINEAR
import com.example.model.GRAD_RADIAL
import com.example.model.GRAD_SWEEP
import com.example.model.IMG_FILL
import com.example.model.IMG_FIT
import com.example.model.IMG_STRETCH
import com.example.model.Keyframe
import com.example.model.LaneLayout
import com.example.model.LayerType
import com.example.model.MaskKey
import com.example.model.MaskMode
import com.example.model.MaskShape
import com.example.model.MaskState
import com.example.model.ParentLink
import com.example.model.ProjectSnapshot
import com.example.model.TextAnimator
import com.example.model.TimelineClip
import com.example.model.colorValueAt
import com.example.model.packColor
import com.example.model.propValues
import com.example.model.unpackColor
import com.example.model.valueAt
import com.example.model.withProp
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.InputStream
import java.io.StringReader
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Alight Motion XML (`<scene> ... </scene>`) -> a new After Motion project. Pure Kotlin (no Android classes).
 *
 * What is mapped:
 *  - scene: title -> project name, width / height -> canvas shape, bgcolor -> canvas colour
 *  - `<text>` layer: start / end time, content, font weight, align, colour, size, location / scale / rotation / opacity
 *    (a constant or `<kf>` keyframes), `texttransform` + `randomdisplace` effects stored as [TextAnimator]
 *  - `<shape>` layer (`.rect` / `.circle` / `.roundrect`): fill colour (or no fill, or a picture fill by uri),
 *    `size` x transform scale -> the shape's size, `cornerRadius` -> its corner radius, position / rotation / opacity, a `path-stroke` -> stroke
 *    colour + width, keyframed size / scale -> SCALE keyframes. `.line` / `.arrow` become a thin rectangle
 *    between their two points (the arrow head is not drawn). A freehand `<path>` shape is skipped.
 *  - `<embedScene>`: a real Group layer (see GroupOps) holding the layers of the nested `<scene>`; the group's
 *    move / scale / turn / opacity and the pivot (as the children's parent link) come from its own transform,
 *    and the nested times are remapped from the [inTime, outTime] window onto the group's length.
 *  - effects (pass 2): `solidcolor` -> the clip's colour (keyframed: FILL_COLOR keyframes) and its alpha
 *    multiplied into the opacity; `gradientoverlay2` -> the app's gradient fill (colours, type, an approximate
 *    angle / offset / scale); `textspacing` -> letter spacing / line height of a text layer; `scaleassist`
 *    (axis 3) composed into the SCALE track; `oscillate` / `oscillate3` / `swing` / `swing2` are procedural, so
 *    they are BAKED into the POSITION / ROTATION keyframes at the scene fps, composed over the layer's own
 *    keyframes. `textprogress` is stored as a [TextAnimator] like the other text effects and DRAWN as a
 *    typewriter reveal ([TextProgressEval]: `start` / `end` are the shown window of the text, counted in
 *    grapheme clusters; the C++ text engine hides the clusters outside it, the Canvas fallback cuts the
 *    string). Only its typing `cursor` is not drawn — a value other than 0 (the corpus only has 0) warns.
 *    `drawing.progress` -> the clip's stroke trim: `start` / `end` (percent of the path) become fractions,
 *    keyframed on the STROKE_TRIM track; on a line the trim gates the fill (the line's paint is its path),
 *    on text / group layers and on shapes without a stroke it stays a warning.
 *  - `<audio>` layer (pass 3): an audio clip with its timing; `inTime` / `outTime` become the trim and
 *    `mediaFillMode` "stretch" a playback speed. The `src` is a content:// URI of the exporting phone, so
 *    the clip stays silent until the file is picked again (a warning says so).
 *  - effects (pass 3): `wipe2` -> a keyframeable rectangular [ClipMask] (the app has no wipe effect of its
 *    own); `gaussianblur` / `edgeglow` / `darkglow` / `exposure` / `tile` / `rgbsep` / `hueshift` /
 *    `satvib` / `brightcont2` / `invert` / `vignette` / `zoomblur2` / `zoomblur3` / `dblur` / `lensblur2` /
 *    `sharpen` / `pinchbulge` / `pinchbulge2` map onto the app's native [ClipFx] library with the closest
 *    parameters; `shake2` is baked into the POSITION keyframes as seeded value noise (the app's camera
 *    shake works in unrelated units); `flip3` -> the layer's 3D rotation tracks; `fade` -> the clip's fade
 *    in / out; `blink` / `blink2` and `pulsate2` are baked into the OPACITY / SCALE keyframes; the
 *    `transform` effect composes onto the layer's own tracks like `scaleassist`; a keyframed `hueshift` on
 *    a solid-filled layer is baked into its colour keyframes; `counter` is stored as a [TextAnimator]
 *    like the other text effects and drawn by [CounterEval] (Alight's "Count Up/Down": every number
 *    in the text × its `scale` + its `offset`, both evaluated at the frame's time); the plain `repeat`
 *    is stored as the app's Repeat effect and drawn by [RepeatEval] (its radial / scatter variants
 *    have no counterpart yet). Effects with no counterpart in the app (motion blur is resolved, path
 *    following, …) stay named warnings — nothing is silently dropped.
 *  - everything else (other layer types, other effects) is skipped and listed in [ImportResult.warnings]
 *
 * Rules that were read from the sample files (NOT from the Alight documentation, so check them on a real export):
 *  - `<kf t>` is NORMALIZED: 0..1 of the layer's length (for an `<embedScene>`, of the embed's length)
 *  - the easing `e="cubicBezier x1 y1 x2 y2"` sits on the keyframe a segment ENDS at (the first keyframe of a
 *    track never has one, the last one usually does); the app stores the easing on the keyframe a segment
 *    STARTS at, so every easing moves one keyframe back when a track is converted
 *  - `location` is in scene pixels from the top-left corner; the app stores the move from the canvas centre in PREVIEW
 *    pixels (see PreviewFrameRef), so it is scaled by previewWidthPx / sceneWidth
 *  - Alight text is measured in units of sceneWidth / 512 px: the rendered font height is
 *    `size` * `scale` * sceneWidth / 512 scene pixels (measured on the ST Intro export: size 18 at
 *    scale 0.84 on a 1920 scene renders about 57 px tall, not 15). The app's text is 0.11 * canvas
 *    width at 100 %. `wrapWidth` is in scene pixels and becomes the layer's max text width.
 *  - a shape's `size` is a HALF extent (centre to edge): the rendered size is 2 * `size` * transform
 *    `scale` in scene pixels (a media shape sized 960x540 fills a 1920x1080 scene exactly; the ST
 *    Intro background rect, size 100 at scale 9.915, covers the frame). The app draws a shape in a
 *    130 dp box; the canvas is taken as 360 dp wide (the usual phone canvas: 1080 px at density 3),
 *    so the box is sceneWidth * 130 / 360 scene pixels. A path stroke is stored relative to its
 *    shape (the app scales a stroke with its layer): dp = 130 * stroke * scale / rendered width.
 *  - in the XML, later layers are on top
 */
object AlightXmlImporter {

    const val MAX_BYTES = 5 * 1024 * 1024
    const val TEXTTRANSFORM = "com.alightcreative.effects.texttransform"
    const val RANDOMDISPLACE = "com.alightcreative.effects.randomdisplace"
    const val TEXTPROGRESS = "com.alightcreative.effects.textprogress"
    const val SOLIDCOLOR = "com.alightcreative.solidcolor"
    const val GRADIENTOVERLAY = "com.alightcreative.gradientoverlay2"
    const val DRAWING_PROGRESS = "com.alightcreative.effects.drawing.progress"
    const val SCALEASSIST = "com.alightcreative.effects.scaleassist"
    const val TEXTSPACING = "com.alightcreative.effects.textspacing"
    const val OSCILLATE = "com.alightcreative.effects.oscillate"
    const val OSCILLATE3 = "com.alightcreative.effects.oscillate3"
    const val SWING = "com.alightcreative.effects.swing"
    const val SWING2 = "com.alightcreative.effects.swing2"
    const val COUNTER = "com.alightcreative.effects.counter"
    const val WIPE = "com.alightcreative.effects.wipe2"
    const val EDGEGLOW = "com.alightcreative.effects.edgeglow"
    const val DARKGLOW = "com.alightcreative.effects.darkglow"
    const val GAUSSIANBLUR = "com.alightcreative.effects.gaussianblur"
    const val SHAKE = "com.alightcreative.effects.shake2"
    const val FLIP = "com.alightcreative.effects.flip3"
    const val EXPOSURE = "com.alightcreative.effects.exposure"
    const val FADE = "com.alightcreative.effects.fade"
    const val BLINK = "com.alightcreative.effects.blink"
    const val BLINK2 = "com.alightcreative.effects.blink2"
    const val TILE = "com.alightcreative.effects.tile"
    const val RGBSEP = "com.alightcreative.effects.rgbsep"
    const val HUESHIFT = "com.alightcreative.effects.hueshift"
    const val SATVIB = "com.alightcreative.effects.satvib"
    const val BRIGHTCONT = "com.alightcreative.effects.brightcont2"
    const val INVERTFX = "com.alightcreative.effects.invert"
    const val VIGNETTE = "com.alightcreative.effects.vignette"
    const val ZOOMBLUR2 = "com.alightcreative.effects.zoomblur2"
    const val ZOOMBLUR3 = "com.alightcreative.effects.zoomblur3"
    const val DBLUR = "com.alightcreative.effects.dblur"
    const val LENSBLUR2 = "com.alightcreative.effects.lensblur2"
    const val SHARPENFX = "com.alightcreative.effects.sharpen"
    const val PINCHBULGE = "com.alightcreative.effects.pinchbulge"
    const val PINCHBULGE2 = "com.alightcreative.effects.pinchbulge2"
    const val PULSATE = "com.alightcreative.effects.pulsate2"
    const val TRANSFORMFX = "com.alightcreative.effects.transform"
    const val LIFT = "com.alightcreative.effects.lift"
    const val REPEAT = "com.alightcreative.effects.repeat"
    const val REPEAT_RADIAL = "com.alightcreative.effects.repeat.radial"
    const val REPEAT_SCATTER = "com.alightcreative.effects.repeat.scatter"
    const val MOVEALONGPATH = "com.alightcreative.effect.movealongpath3"
    const val MOTIONBLUR = "com.alightcreative.effects.motionblur"
    const val MOTIONBLUR2 = "com.alightcreative.effects.motionblur2"
    const val MOTIONBLUR4 = "com.alightcreative.effects.motionblur4"

    /** Effect ids the text reader stores as [TextAnimator] itself (the effect pass must not warn about them). */
    private val TEXT_STORED = setOf(TEXTTRANSFORM, RANDOMDISPLACE, TEXTPROGRESS, COUNTER)

    /** Imported audio layers get this timeline colour. */
    private const val AUDIO_COLOR = 0xFF2E8B6EL

    /** A "hold" easing: the value stays put, then jumps at the segment's end (used by the blink bake). */
    private val HOLD_EASING = Easing(1f, 0f, 1f, 1f)

    private val KNOWN_FAMILIES = setOf("Roboto", "Serif", "Monospace", "Cursive")
    private const val APP_FONT_FRACTION = 0.11f

    /** Alight text units: one unit is sceneWidth / [TEXT_REF_UNITS] px (see the header rules). */
    private const val TEXT_REF_UNITS = 512f

    /** The app's shape box at 100 % (dp, see OverlayPose.shapeBoxPx) and the canvas width it is measured against. */
    private const val APP_SHAPE_BOX_DP = 130f
    private const val CANVAS_DP = 360f

    private const val SHAPE_COLOR = 0xFFFF3377L
    private const val GROUP_COLOR = 0xFF5BB85DL

    class ImportResult(val name: String, val snapshot: ProjectSnapshot, val warnings: List<String>)

    /** True when the first bytes of a file look like an Alight Motion scene (used by the Import button to pick the importer). */
    fun looksLikeAlightXml(head: ByteArray): Boolean {
        val s = String(head, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return s.startsWith("<") && s.contains("<scene")
    }

    fun parse(input: InputStream, previewWidthPx: Float): ImportResult {
        val bytes = input.readNBytesLimited(MAX_BYTES + 1)
        require(bytes.size <= MAX_BYTES) { "The XML file is too big" }
        return parse(String(bytes, Charsets.UTF_8).trimStart('\uFEFF'), previewWidthPx)
    }

    fun parse(xml: String, previewWidthPx: Float): ImportResult {
        val root = try {
            val f = DocumentBuilderFactory.newInstance()
            f.isExpandEntityReferences = false
            runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            f.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
        } catch (e: Exception) {
            throw IllegalArgumentException("Not a valid XML file")
        }
        require(root.tagName == "scene") { "Not an Alight Motion scene (root tag is <" + root.tagName + ">)" }

        val warnings = ArrayList<String>()
        val sceneW = root.getAttribute("width").toFloatOrNull()?.takeIf { it > 0f } ?: 1080f
        val sceneH = root.getAttribute("height").toFloatOrNull()?.takeIf { it > 0f } ?: 1920f
        val name = root.getAttribute("title").trim().ifEmpty { "Alight Motion import" }
        val canvasColor = parseColor(root.getAttribute("bgcolor")) ?: 0xFF000000L
        val totalMs = root.getAttribute("totalTime").toLongOrNull() ?: 0L
        val fps = root.getAttribute("fps").toFloatOrNull()?.takeIf { it > 0f } ?: 30f
        val pw = if (previewWidthPx > 1f) previewWidthPx else sceneW   // unknown preview width: keep scene pixels

        val ctx = Ctx(sceneW, sceneH, pw, sceneW, totalMs, fps, warnings)
        val clips = readSceneContent(root, ctx).flat
        if (clips.isEmpty()) warnings += "No supported layer found in this file"

        val ratio = sceneW / sceneH
        val aspect = AspectRatio.values().firstOrNull { !it.isOriginal && abs(it.ratioFloat - ratio) < 0.01f }
            ?: AspectRatio(sceneW.toInt().toString() + ":" + sceneH.toInt(), ratio, "Imported")

        // Later layers in the XML are on top; placeAll puts the FIRST clip on the top lane.
        val tracks = LaneLayout.placeAll(emptyList(), clips.reversed())
        return ImportResult(
            name,
            ProjectSnapshot(tracks = tracks, aspectRatio = aspect, canvasColor = canvasColor),
            warnings.distinct()
        )
    }

    /**
     * One scene's coordinate frame. [sceneW] / [sceneH] are the scene the layers live in (their positions are
     * measured from its centre); [k] always scales scene pixels of the ROOT scene to preview pixels, because the
     * app has only one canvas. Nested scenes in the samples have the root's size, so this is exact there.
     */
    private class Ctx(
        val sceneW: Float, val sceneH: Float, val previewW: Float, val rootW: Float,
        val totalMs: Long, val fps: Float, val warnings: MutableList<String>
    ) {
        val k: Float get() = previewW / rootW
        /** The app's shape box (130 dp) in this scene's pixels. */
        val shapeBoxPx: Float get() = sceneW * APP_SHAPE_BOX_DP / CANVAS_DP

        fun posX(v: List<Float>) = ((v.getOrNull(0) ?: sceneW / 2f) - sceneW / 2f) * k
        fun posY(v: List<Float>) = ((v.getOrNull(1) ?: sceneH / 2f) - sceneH / 2f) * k
    }

    /** [flat] = every clip of the scene (groups flattened in, document order); [direct] = the clips whose own element is a direct child of the scene (for an embedScene: its Group layer). */
    private class SceneBlock(val flat: List<TimelineClip>, val direct: List<TimelineClip>)

    private fun readSceneContent(sceneEl: Element, ctx: Ctx): SceneBlock {
        val flat = ArrayList<TimelineClip>()
        val direct = ArrayList<TimelineClip>()
        for (el in elementChildren(sceneEl)) {
            when (el.tagName) {
                "text" -> readText(el, ctx).let { flat += it; direct += it }
                "shape" -> readShape(el, ctx)?.let { flat += it; direct += it }
                "audio" -> readAudio(el, ctx).let { flat += it; direct += it }
                "embedScene" -> readGroup(el, ctx)?.let { (block, group) -> flat += block; direct += group }
                else -> ctx.warnings += "Layer <" + el.tagName + ">" + labelOf(el) + " is not supported yet: skipped"
            }
        }
        return SceneBlock(flat, direct)
    }

    // ---- audio --------------------------------------------------------------------------------------------------

    /**
     * An `<audio>` element -> an audio clip with its timing. `inTime` / `outTime` (the part of the file the
     * layer plays) become the clip's trim, and `mediaFillMode` "stretch" (the file section is stretched onto
     * the layer's length) becomes a playback speed. The `src` is a content:// URI from the phone that
     * exported the XML, so it cannot resolve here: the clip is imported with the URI and stays silent until
     * the file is picked again (a warning says so). A `<volume>` child, when present, sets the clip volume.
     */
    private fun readAudio(el: Element, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val startMs = el.getAttribute("startTime").toLongOrNull() ?: 0L
        val endMs = el.getAttribute("endTime").toLongOrNull() ?: ctx.totalMs
        val durMs = (endMs - startMs).coerceAtLeast(1L)
        val src = el.getAttribute("src").ifEmpty { null }
        val inT = el.getAttribute("inTime").toLongOrNull() ?: 0L
        val outT = el.getAttribute("outTime").toLongOrNull() ?: (inT + durMs)
        val span = (outT - inT).coerceAtLeast(0L)
        val speed = if (el.getAttribute("mediaFillMode") == "stretch" && span > 0L && span != durMs) {
            span.toFloat() / durMs
        } else 1f
        val label = el.getAttribute("label")
        w += if (src != null) {
            "Audio" + labelOf(el) + ": its file is on the phone that exported the XML: imported silent, pick the file again to hear it"
        } else {
            "Audio" + labelOf(el) + ": it has no file in the XML: imported silent"
        }
        val mime = when {
            label.endsWith(".mp3", true) -> "audio/mpeg"
            label.endsWith(".wav", true) -> "audio/wav"
            label.endsWith(".m4a", true) -> "audio/mp4"
            label.endsWith(".mp4", true) -> "video/mp4"
            else -> null
        }
        val vol = child(el, "volume")?.getAttribute("value")?.toFloatOrNull()
        return TimelineClip(
            id = UUID.randomUUID().toString(),
            name = label.ifBlank { "Audio" },
            type = LayerType.AUDIO,
            startMs = startMs.coerceAtLeast(0L),
            durationMs = durMs,
            color = AUDIO_COLOR,
            uri = src,
            mimeType = mime,
            trimStartMs = inT,
            sourceDurationMs = outT,
            speed = speed,
            volume = vol?.coerceIn(0f, 1f) ?: 1f
        )
    }

    // ---- text ---------------------------------------------------------------------------------------------------

    private fun readText(el: Element, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val startMs = el.getAttribute("startTime").toLongOrNull() ?: 0L
        val endMs = el.getAttribute("endTime").toLongOrNull() ?: ctx.totalMs
        val durMs = (endMs - startMs).coerceAtLeast(250L)

        val content = elementChildren(el).firstOrNull { it.tagName == "content" }?.textContent
            ?: el.getAttribute("label")
        val (family, weight) = parseFont(el.getAttribute("font"), w)
        val size = el.getAttribute("size").toFloatOrNull() ?: 30f
        val textColor = parseColor(el.getAttribute("fillColor"))
            ?: elementChildren(el).firstOrNull { it.tagName == "fillColor" }?.let { parseColor(it.getAttribute("value")) }
            ?: 0xFFFFFFFFL

        // Transform: constant value or keyframes per property.
        val tr = readTransform(el, w, pivotUsed = false)
        val loc = tr.loc
        val scl = tr.scl
        val rot = tr.rot
        val opa = tr.opa ?: child(el, "opacity")?.let { readTrack(it) }

        // Alight's font height in scene pixels: size x scale, in units of sceneWidth / 512 px.
        val fontBase = APP_FONT_FRACTION * ctx.sceneW
        fun scalePct(v: List<Float>): Float {
            if (v.size >= 2 && abs(v[0] - v[1]) > 1e-3f) w += "Non-uniform scale: only the horizontal scale is used"
            val emPx = size * (v.firstOrNull() ?: 1f) * ctx.sceneW / TEXT_REF_UNITS
            return emPx / fontBase * 100f
        }
        // wrapWidth is in scene pixels: the widest line Alight allows before wrapping.
        val wrapFrac = el.getAttribute("wrapWidth").toFloatOrNull()?.takeIf { it > 0f }
            ?.let { (it / ctx.sceneW).coerceIn(0.05f, 1f) } ?: 0f

        val keyframes = LinkedHashMap<AnimProp, List<Keyframe>>()
        fun keys(prop: AnimProp, t: Track?, map: (List<Float>) -> Pair<Float, Float>) {
            val l = trackKeys(t, durMs, map)
            if (l.isNotEmpty()) keyframes[prop] = l
        }
        keys(AnimProp.POSITION, loc) { ctx.posX(it) to ctx.posY(it) }
        keys(AnimProp.SCALE, scl) { scalePct(it) to 0f }
        keys(AnimProp.ROTATION, rot) { (it.firstOrNull() ?: 0f) to 0f }
        keys(AnimProp.OPACITY, opa) { ((it.firstOrNull() ?: 1f) * 100f).coerceIn(0f, 100f) to 0f }

        // Plain fields = the constant value, or the first keyframe (the fallback of an animated property).
        val locV = loc?.first()
        val sclV = scl?.first()
        val rotV = rot?.first()
        val opaV = opa?.first()

        val animators = ArrayList<TextAnimator>()
        for (fx in elementChildren(el).filter { it.tagName == "effect" }) {
            val id = fx.getAttribute("id")
            if (id in TEXT_STORED) {
                val animator = readAnimator(fx, id, size)
                animators += animator
                if (id == TEXTPROGRESS) {
                    // The reveal itself is drawn (TextProgressEval); only a typing cursor is not. The corpus
                    // only has cursor = 0 (no cursor) — any other value keeps a warning instead of a guess.
                    val cur = animator.props["cursor"]
                    val cursor = (if (cur != null && cur.keys.isNotEmpty()) cur.keys.first().v.firstOrNull()
                        else cur?.value?.firstOrNull()) ?: 0f
                    if (cursor > 0.5f) w += "Effect textprogress cursor is not drawn yet (the reveal itself is drawn)"
                }
            }
        }
        if (animators.any { it.effectId == RANDOMDISPLACE }) {
            w += "Effect randomdisplace is saved with the layer but not drawn yet"
        }
        if (animators.count { it.effectId == TEXTTRANSFORM } > 1) {
            w += "Only the first texttransform of a layer is drawn"
        }

        val built = TimelineClip(
            id = UUID.randomUUID().toString(),
            name = el.getAttribute("label").ifBlank { "Text" },
            type = LayerType.TEXT,
            startMs = startMs.coerceAtLeast(0L),
            durationMs = durMs,
            color = 0xFF3992CE,
            text = content,
            textColor = textColor,
            fontFamily = family,
            fontWeight = weight,
            textAlign = when (el.getAttribute("align")) { "left" -> 0; "right" -> 2; else -> 1 },
            textWrapFrac = wrapFrac,
            scalePercent = scalePct(sclV ?: listOf(1f, 1f)),
            transformX = ctx.posX(locV ?: emptyList()),
            transformY = ctx.posY(locV ?: emptyList()),
            rotationDeg = rotV?.firstOrNull() ?: 0f,
            opacity = ((opaV?.firstOrNull() ?: 1f) * 100f).coerceIn(0f, 100f),
            keyframes = keyframes,
            textAnimators = animators
        )
        return applyLayerEffects(el, built, ctx, durMs, FxTarget.TEXT)
    }

    private fun readAnimator(fx: Element, id: String, refSize: Float): TextAnimator {
        val props = LinkedHashMap<String, AnimValue>()
        for (p in elementChildren(fx).filter { it.tagName == "property" }) {
            val n = p.getAttribute("name")
            if (n.isEmpty()) continue
            val type = p.getAttribute("type").ifEmpty { "float" }
            val t = readTrack(p, type)
            props[n] = AnimValue(type, if (t.keys.isEmpty()) t.constant else emptyList(), t.keys.map { AnimKey(it.t, it.v) })
        }
        return TextAnimator(id, props, refSize)
    }

    // ---- shape --------------------------------------------------------------------------------------------------

    private fun readShape(el: Element, ctx: Ctx): TimelineClip? {
        val w = ctx.warnings
        val kind = el.getAttribute("s")
        if (kind == ".line" || kind == ".arrow") return readLineShape(el, ctx, kind)
        val pattern = when (kind) {
            ".rect" -> "RECT"
            ".circle" -> "CIRCLE"
            ".roundrect" -> "ROUNDED_RECT"
            else -> {
                val why = when {
                    kind.isEmpty() && child(el, "path") != null -> "freehand path shapes are not supported yet"
                    kind.isEmpty() -> "it has no shape kind"
                    else -> "shape kind \"" + kind + "\" is not supported yet"
                }
                w += "Layer <shape>" + labelOf(el) + ": " + why + ": skipped"
                return null
            }
        }

        val startMs = el.getAttribute("startTime").toLongOrNull() ?: 0L
        val endMs = el.getAttribute("endTime").toLongOrNull() ?: ctx.totalMs
        val durMs = (endMs - startMs).coerceAtLeast(250L)
        val tr = readTransform(el, w, pivotUsed = false)

        // Fill: the <fillColor> child, or the picture of a media fill (the uri travels in the XML).
        val fillColor = child(el, "fillColor")?.let { parseColor(it.getAttribute("value")) } ?: 0xFFFFFFFFL
        var fillImageUri: String? = null
        var fillImageMode = IMG_FILL
        val fillMode = when (val ft = el.getAttribute("fillType")) {
            "none" -> FILL_NONE
            "media" -> {
                fillImageUri = el.getAttribute("fillImage").ifEmpty { null }
                fillImageMode = when (el.getAttribute("mediaFillMode")) {
                    "stretch" -> IMG_STRETCH
                    "fit" -> IMG_FIT
                    else -> IMG_FILL
                }
                if (fillImageUri == null) w += "Shape" + labelOf(el) + ": its picture is not in the XML: the fill stays empty"
                FILL_IMAGE
            }
            "", "color" -> FILL_SOLID
            else -> {
                w += "Shape fill \"" + ft + "\" is not supported yet: a solid fill is used"
                FILL_SOLID
            }
        }
        warnBlending(el, w)

        // Stroke: <path-stroke><color/><size/></path-stroke>; a disabled one draws nothing. The size is kept
        // raw here and converted once the rendered width is known (the app scales a stroke with its layer,
        // so the stored dp is relative to the shape: see where rendW is computed below).
        var strokeColor = 0xFFFFFFFFL
        var strokeSz = 0f
        val strokeEl = child(el, "path-stroke")
        if (strokeEl != null && strokeEl.getAttribute("enabled") != "false") {
            strokeColor = child(strokeEl, "color")?.let { parseColor(it.getAttribute("value")) } ?: 0xFFFFFFFFL
            strokeSz = child(strokeEl, "size")?.getAttribute("value")?.toFloatOrNull() ?: 0f
        }

        val sizeT = elementChildren(el).firstOrNull { it.tagName == "property" && it.getAttribute("name") == "size" }
            ?.let { readTrack(it) }
        // Corner radius (only `.roundrect` carries one): Alight measures it in the shape's own units and clamps
        // it to half the smaller side. The app stores dp of its 130 dp shape box, so the stored value keeps the
        // same share of the shape's width: dp = 130 * r / sizeW, after Alight's own clamp.
        var shapeCornerRadius = -1f
        val cornerT = elementChildren(el).firstOrNull { it.tagName == "property" && it.getAttribute("name") == "cornerRadius" }
            ?.let { readTrack(it) }
        if (cornerT != null && pattern == "ROUNDED_RECT") {
            if (cornerT.keys.size >= 2) {
                w += "Keyframed corner radius of a shape is not supported yet: the first value is used"
            }
            val rLocal = cornerT.first().firstOrNull()
            val sizeFirst = sizeT?.first() ?: listOf(100f, 100f)
            val sizeW = sizeFirst.getOrNull(0) ?: 100f
            val sizeH = sizeFirst.getOrNull(1) ?: sizeW
            if (rLocal != null && sizeW > 0f) {
                val rEff = minOf(rLocal, minOf(sizeW, sizeH) / 2f).coerceAtLeast(0f)
                shapeCornerRadius = (APP_SHAPE_BOX_DP * rEff / sizeW).coerceIn(0f, APP_SHAPE_BOX_DP / 2f)
            }
        }

        // Rendered size in scene pixels: Alight's `size` is a HALF extent (centre to edge), so the full
        // extent is 2 x size x transform scale; the app size is measured against its 130 dp box.
        val box = ctx.shapeBoxPx
        val sizeV = sizeT?.first() ?: listOf(100f, 100f)
        val sclV = tr.scl?.first() ?: listOf(1f, 1f)
        val rendW = 2f * (sizeV.getOrNull(0) ?: 100f) * (sclV.getOrNull(0) ?: 1f)
        val rendH = 2f * (sizeV.getOrNull(1) ?: sizeV.getOrNull(0) ?: 100f) * (sclV.getOrNull(1) ?: sclV.getOrNull(0) ?: 1f)
        // The app's stroke width is in canvas dp at layer scale 1 and is scaled by the layer's own scale
        // when drawn, so the stored value keeps the stroke's share of the shape's rendered width:
        // dp = 130 * (stroke x scale) / rendW. A shape that starts at scale 0 keeps the plain conversion.
        val strokeWidth = when {
            strokeSz <= 0f -> 0f
            rendW > 0f -> APP_SHAPE_BOX_DP * strokeSz * (sclV.getOrNull(0) ?: 1f) / rendW
            else -> strokeSz * CANVAS_DP / ctx.sceneW
        }

        val keyframes = LinkedHashMap<AnimProp, List<Keyframe>>()
        fun keys(prop: AnimProp, t: Track?, map: (List<Float>) -> Pair<Float, Float>) {
            val l = trackKeys(t, durMs, map)
            if (l.isNotEmpty()) keyframes[prop] = l
        }
        keys(AnimProp.POSITION, tr.loc) { ctx.posX(it) to ctx.posY(it) }
        keys(AnimProp.ROTATION, tr.rot) { (it.firstOrNull() ?: 0f) to 0f }
        keys(AnimProp.OPACITY, tr.opa) { ((it.firstOrNull() ?: 1f) * 100f).coerceIn(0f, 100f) to 0f }
        val sizeKeys = composedSizeKeys(sizeT, tr.scl, durMs, box)
        if (sizeKeys.isNotEmpty()) {
            keyframes[AnimProp.SCALE] = sizeKeys
            if (sizeT != null && sizeAspectVaries(sizeT, tr.scl)) {
                w += "Keyframed shape size: only the width is animated in the app, the height follows it"
            }
        }

        val posX = ctx.posX(tr.loc?.first() ?: emptyList())
        val posY = ctx.posY(tr.loc?.first() ?: emptyList())
        val built = TimelineClip(
            id = UUID.randomUUID().toString(),
            name = el.getAttribute("label").ifBlank { "Shape" },
            type = LayerType.SHAPE,
            startMs = startMs.coerceAtLeast(0L),
            durationMs = durMs,
            color = SHAPE_COLOR,
            textColor = fillColor,
            fillMode = fillMode,
            fillImageUri = fillImageUri,
            fillImageMode = fillImageMode,
            shapePattern = pattern,
            shapeScaleX = rendW / box,
            shapeScaleY = rendH / box,
            shapeCornerRadius = shapeCornerRadius,
            strokeWidth = strokeWidth,
            strokeColor = strokeColor,
            transformX = posX,
            transformY = posY,
            shapeOffsetX = posX,
            shapeOffsetY = posY,
            scalePercent = rendW / box * 100f,
            rotationDeg = tr.rot?.first()?.firstOrNull() ?: 0f,
            opacity = ((tr.opa?.first()?.firstOrNull() ?: 1f) * 100f).coerceIn(0f, 100f),
            keyframes = keyframes
        )
        return applyLayerEffects(el, built, ctx, durMs, FxTarget.SHAPE)
    }

    /**
     * A `.line` (points `p1` / `p2`, painted by its path-stroke) or an `.arrow` (points `start` / `end`, `lineWidth`
     * thick): the app has no line shape, so the shaft becomes a thin RECT between the two rendered points.
     * Point p is rendered at location + R(rotation) * (scale * (p - pivot)), like every Alight layer.
     */
    private fun readLineShape(el: Element, ctx: Ctx, kind: String): TimelineClip? {
        val w = ctx.warnings
        val isArrow = kind == ".arrow"
        fun propVec(name: String): List<Float>? =
            elementChildren(el).firstOrNull { it.tagName == "property" && it.getAttribute("name") == name }
                ?.let { t -> readTrack(t).also { if (it.keys.size >= 2) w += "Keyframed \"" + name + "\" of a line is not supported yet: the first value is used" }.first().takeIf { v -> v.size >= 2 } }
        val p1 = propVec(if (isArrow) "start" else "p1")
        val p2 = propVec(if (isArrow) "end" else "p2")
        if (p1 == null || p2 == null) {
            w += "Layer <shape>" + labelOf(el) + ": it has no line points: skipped"
            return null
        }
        val startMs = el.getAttribute("startTime").toLongOrNull() ?: 0L
        val endMs = el.getAttribute("endTime").toLongOrNull() ?: ctx.totalMs
        val durMs = (endMs - startMs).coerceAtLeast(250L)
        val tr = readTransform(el, w, pivotUsed = true)

        val locV = tr.loc?.first() ?: listOf(ctx.sceneW / 2f, ctx.sceneH / 2f)
        val sclV = tr.scl?.first() ?: listOf(1f, 1f)
        val rotV = tr.rot?.first()?.firstOrNull() ?: 0f
        val pivV = tr.pivot?.first() ?: listOf(0f, 0f)
        if (tr.scl != null && tr.scl.keys.size >= 2) w += "Keyframed scale of a line is not supported yet: the first value is used"
        if (tr.rot != null && tr.rot.keys.size >= 2) w += "Keyframed rotation of a line is not supported yet: the first value is used"

        fun renderPoint(p: List<Float>, loc: List<Float>): Pair<Float, Float> {
            val px = (p[0] - (pivV.getOrNull(0) ?: 0f)) * (sclV.getOrNull(0) ?: 1f)
            val py = (p[1] - (pivV.getOrNull(1) ?: 0f)) * (sclV.getOrNull(1) ?: sclV.getOrNull(0) ?: 1f)
            val rad = Math.toRadians(rotV.toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            return ((loc.getOrNull(0) ?: ctx.sceneW / 2f) + px * c - py * s) to
                ((loc.getOrNull(1) ?: ctx.sceneH / 2f) + px * s + py * c)
        }
        fun centre(loc: List<Float>): Pair<Float, Float> {
            val a = renderPoint(p1, loc)
            val b = renderPoint(p2, loc)
            return (a.first + b.first) / 2f to (a.second + b.second) / 2f
        }
        val a = renderPoint(p1, locV)
        val b = renderPoint(p2, locV)
        val length = hypot(b.first - a.first, b.second - a.second)
        val angle = Math.toDegrees(atan2(b.second - a.second, b.first - a.first).toDouble()).toFloat()

        // The line's own paint: an arrow is a filled shape, a line is its stroke.
        val strokeEl = child(el, "path-stroke")
        val thickProp = elementChildren(el).firstOrNull { it.tagName == "property" && it.getAttribute("name") == "lineWidth" }
            ?.getAttribute("value")?.toFloatOrNull()
        val thickLocal = if (isArrow) (thickProp ?: 0f)
        else child(strokeEl ?: el, "size")?.getAttribute("value")?.toFloatOrNull() ?: thickProp ?: 0f
        val thickness = thickLocal * (sclV.getOrNull(0) ?: 1f)
        val lineColor = if (isArrow) {
            child(el, "fillColor")?.let { parseColor(it.getAttribute("value")) } ?: 0xFFFFFFFFL
        } else {
            strokeEl?.let { child(it, "color") }?.let { parseColor(it.getAttribute("value")) }
                ?: child(el, "fillColor")?.let { parseColor(it.getAttribute("value")) } ?: 0xFFFFFFFFL
        }
        warnBlending(el, w)
        w += if (isArrow) "Arrow" + labelOf(el) + " is imported as a plain line: its head is not drawn yet"
        else "Line" + labelOf(el) + " is imported as a thin rectangle"

        val box = ctx.shapeBoxPx
        val keyframes = LinkedHashMap<AnimProp, List<Keyframe>>()
        val posKeys = trackKeys(tr.loc, durMs) { v ->
            val c = centre(v)
            ((c.first - ctx.sceneW / 2f) * ctx.k) to ((c.second - ctx.sceneH / 2f) * ctx.k)
        }
        if (posKeys.isNotEmpty()) keyframes[AnimProp.POSITION] = posKeys
        val opaKeys = trackKeys(tr.opa, durMs) { ((it.firstOrNull() ?: 1f) * 100f).coerceIn(0f, 100f) to 0f }
        if (opaKeys.isNotEmpty()) keyframes[AnimProp.OPACITY] = opaKeys

        val c0 = centre(locV)
        val posX = (c0.first - ctx.sceneW / 2f) * ctx.k
        val posY = (c0.second - ctx.sceneH / 2f) * ctx.k
        val built = TimelineClip(
            id = UUID.randomUUID().toString(),
            name = el.getAttribute("label").ifBlank { if (isArrow) "Arrow" else "Line" },
            type = LayerType.SHAPE,
            startMs = startMs.coerceAtLeast(0L),
            durationMs = durMs,
            color = SHAPE_COLOR,
            textColor = lineColor,
            fillMode = FILL_SOLID,
            // The line's paint is its path: a drawing.progress trim gates this fill, not a stroke.
            strokeTrimFill = true,
            shapePattern = "RECT",
            shapeScaleX = length / box,
            shapeScaleY = thickness / box,
            transformX = posX,
            transformY = posY,
            shapeOffsetX = posX,
            shapeOffsetY = posY,
            scalePercent = length / box * 100f,
            rotationDeg = angle,
            opacity = ((tr.opa?.first()?.firstOrNull() ?: 1f) * 100f).coerceIn(0f, 100f),
            keyframes = keyframes
        )
        return applyLayerEffects(el, built, ctx, durMs, FxTarget.SHAPE)
    }

    /** SCALE keyframes of a shape: the rendered width (2 x size x scale, X axis — size is a half extent) against the app's shape box. */
    private fun composedSizeKeys(sizeT: Track?, scaleT: Track?, durMs: Long, box: Float): List<Keyframe> {
        val times = ((sizeT?.keys ?: emptyList()) + (scaleT?.keys ?: emptyList())).map { it.t }.distinct().sorted()
        if (times.size < 2) return emptyList()
        fun widthAt(t: Float): Float {
            val sz = sizeT?.valueAt(t) ?: listOf(100f, 100f)
            val sc = scaleT?.valueAt(t) ?: listOf(1f, 1f)
            return 2f * (sz.getOrNull(0) ?: 100f) * (sc.getOrNull(0) ?: 1f) / box * 100f
        }
        return times.mapIndexed { i, t ->
            val next = times.getOrNull(i + 1)
            val e = if (next == null) Easing.LINEAR
            else sizeT?.keyAt(next)?.easing ?: scaleT?.keyAt(next)?.easing ?: Easing.LINEAR
            Keyframe((t.coerceIn(0f, 1f) * durMs).roundToLong(), widthAt(t), 0f, e)
        }.distinctBy { it.timeMs }
    }

    /** True when the keyframed size changes its height/width ratio (the app animates the width only). */
    private fun sizeAspectVaries(sizeT: Track, scaleT: Track?): Boolean {
        val times = (sizeT.keys.map { it.t } + (scaleT?.keys?.map { it.t } ?: emptyList())).distinct()
        var lo = Float.MAX_VALUE
        var hi = 0f
        for (t in times) {
            val sz = sizeT.valueAt(t)
            val wdt = sz.getOrNull(0) ?: 0f
            val hgt = sz.getOrNull(1) ?: 0f
            if (wdt <= 0f) continue
            val r = hgt / wdt
            if (r < lo) lo = r
            if (r > hi) hi = r
        }
        return hi > 0f && lo < Float.MAX_VALUE && hi / lo.coerceAtLeast(1e-6f) > 1.05f
    }

    private fun warnBlending(el: Element, w: MutableList<String>) {
        val b = el.getAttribute("blending")
        if (b.isNotEmpty()) w += "Layer blending \"" + b + "\"" + labelOf(el) + " is not supported yet: imported as a normal layer"
    }

    // ---- groups (embedScene) ------------------------------------------------------------------------------------

    /**
     * An `<embedScene>` -> the clips of its nested scene (times remapped into the parent's timeline) plus a real
     * Group layer on top of them. The nested layers keep their own positions: they follow the group through a
     * [ParentLink] whose ref pose is the REST pose, so the group's whole transform reaches them (see ParentRig),
     * measured around the embed's pivot: the link saves the pivot in the children's own coordinate frame.
     */
    private fun readGroup(el: Element, ctx: Ctx): Pair<List<TimelineClip>, TimelineClip>? {
        val w = ctx.warnings
        val nested = child(el, "scene")
        if (nested == null) {
            w += "Group" + labelOf(el) + " has no content in the XML: skipped"
            return null
        }
        val nW = nested.getAttribute("width").toFloatOrNull()?.takeIf { it > 0f } ?: ctx.sceneW
        val nH = nested.getAttribute("height").toFloatOrNull()?.takeIf { it > 0f } ?: ctx.sceneH
        val nTotal = nested.getAttribute("totalTime").toLongOrNull() ?: 0L
        if (abs(nW - ctx.sceneW) > 1f || abs(nH - ctx.sceneH) > 1f) {
            w += "Group" + labelOf(el) + ": its canvas (" + nW.toInt() + "x" + nH.toInt() +
                ") differs from the project canvas: positions inside are approximate"
        }
        val content = readSceneContent(nested, Ctx(nW, nH, ctx.previewW, ctx.rootW, nTotal,
            nested.getAttribute("fps").toFloatOrNull()?.takeIf { it > 0f } ?: ctx.fps, w))

        val startMs = el.getAttribute("startTime").toLongOrNull() ?: 0L
        val endMs = el.getAttribute("endTime").toLongOrNull() ?: ctx.totalMs
        val durMs = (endMs - startMs).coerceAtLeast(100L)
        val inT = el.getAttribute("inTime").toLongOrNull() ?: 0L
        val outT = el.getAttribute("outTime").toLongOrNull() ?: nTotal
        val factor = if (outT > inT) (endMs - startMs).toDouble() / (outT - inT).toDouble() else 1.0
        if (abs(factor - 1.0) > 1e-4) w += "Group" + labelOf(el) + ": its timing was stretched to fit its length in the timeline"

        val tr = readTransform(el, w, pivotUsed = true)
        val pivotV = tr.pivot?.first() ?: listOf(0f, 0f)
        if (tr.pivot != null && tr.pivot.keys.size >= 2) {
            w += "Keyframed pivot of a group is not supported yet: the first value is used"
        }

        val groupId = UUID.randomUUID().toString()
        val link = ParentLink(
            groupId,
            ((pivotV.getOrNull(0) ?: 0f) - nW / 2f) * ctx.k,
            ((pivotV.getOrNull(1) ?: 0f) - nH / 2f) * ctx.k,
            100f, 0f
        )
        val directIds = content.direct.mapTo(HashSet()) { it.id }
        val remapped = content.flat.mapNotNull { c ->
            val linked = if (c.id in directIds) c.copy(groupId = groupId, parent = link) else c
            remapClip(linked, startMs, endMs, inT, factor)
        }

        fun scalePct(v: List<Float>): Float {
            if (v.size >= 2 && abs(v[0] - v[1]) > 1e-3f) w += "Non-uniform scale: only the horizontal scale is used"
            return (v.firstOrNull() ?: 1f) * 100f
        }
        val keyframes = LinkedHashMap<AnimProp, List<Keyframe>>()
        fun keys(prop: AnimProp, t: Track?, map: (List<Float>) -> Pair<Float, Float>) {
            val l = trackKeys(t, durMs, map)
            if (l.isNotEmpty()) keyframes[prop] = l
        }
        keys(AnimProp.POSITION, tr.loc) { ctx.posX(it) to ctx.posY(it) }
        keys(AnimProp.SCALE, tr.scl) { scalePct(it) to 0f }
        keys(AnimProp.ROTATION, tr.rot) { (it.firstOrNull() ?: 0f) to 0f }
        keys(AnimProp.OPACITY, tr.opa) { ((it.firstOrNull() ?: 1f) * 100f).coerceIn(0f, 100f) to 0f }

        val group = TimelineClip(
            id = groupId,
            name = el.getAttribute("label").ifBlank { "Group" },
            type = LayerType.GROUP,
            startMs = startMs.coerceAtLeast(0L),
            durationMs = durMs,
            color = GROUP_COLOR,
            transformX = ctx.posX(tr.loc?.first() ?: emptyList()),
            transformY = ctx.posY(tr.loc?.first() ?: emptyList()),
            scalePercent = scalePct(tr.scl?.first() ?: listOf(1f, 1f)),
            rotationDeg = tr.rot?.first()?.firstOrNull() ?: 0f,
            opacity = ((tr.opa?.first()?.firstOrNull() ?: 1f) * 100f).coerceIn(0f, 100f),
            keyframes = keyframes
        )
        val groupFx = applyLayerEffects(el, group, ctx, durMs, FxTarget.GROUP)
        return (remapped + groupFx) to groupFx
    }

    /** A clip of a nested scene, in the parent timeline: [inT] .. [outT] of the nested time is stretched onto [startMs] .. [endMs], and the clip is cut at the group's ends. */
    private fun remapClip(c: TimelineClip, startMs: Long, endMs: Long, inT: Long, factor: Double): TimelineClip? {
        fun parentTime(t: Long): Long = startMs + ((t - inT) * factor).roundToLong()
        val ns = parentTime(c.startMs).coerceIn(startMs, endMs)
        val ne = parentTime(c.endMs).coerceIn(startMs, endMs)
        if (ne - ns < 1L) return null
        val nd = ne - ns
        val nk = if (c.keyframes.isEmpty()) c.keyframes else c.keyframes.mapValues { (_, l) ->
            l.map { kf ->
                val nt = parentTime(c.startMs + kf.timeMs)
                kf.copy(timeMs = (nt - ns).coerceIn(0L, nd))
            }.distinctBy { it.timeMs }
        }
        val nm = if (c.masks.isEmpty()) c.masks else c.masks.map { m ->
            if (m.keys.isEmpty()) m else m.copy(keys = m.keys.map { k ->
                val nt = parentTime(c.startMs + k.timeMs)
                k.copy(timeMs = (nt - ns).coerceIn(0L, nd))
            }.distinctBy { it.timeMs })
        }
        return c.copy(
            startMs = ns, durationMs = nd, keyframes = nk, masks = nm,
            fadeInMs = (c.fadeInMs * factor).roundToLong().coerceIn(0L, nd),
            fadeOutMs = (c.fadeOutMs * factor).roundToLong().coerceIn(0L, nd)
        )
    }

    // ---- effects (pass 2) ---------------------------------------------------------------------------------------

    /** What a layer's effects can act on: a group has no fill of its own, a text layer has spacing. */
    private enum class FxTarget { TEXT, SHAPE, GROUP }

    /** The properties of one `<effect>` by name, each as a [Track] (its own `type` attribute drives the parsing). */
    private fun fxProps(fx: Element): Map<String, Track> {
        val out = LinkedHashMap<String, Track>()
        for (p in elementChildren(fx).filter { it.tagName == "property" }) {
            val n = p.getAttribute("name")
            if (n.isNotEmpty()) out[n] = readTrack(p)
        }
        return out
    }

    private fun Track.scalarAt(t: Float): Float = valueAt(t).firstOrNull() ?: 0f

    /** A packed colour value (see [parseValue]) back to an ARGB Long. */
    private fun unpacked(v: List<Float>): Long = unpackColor(v.getOrNull(0) ?: 0f, v.getOrNull(1) ?: 0f)

    /**
     * Every `<effect>` of a layer the readers did not consume themselves, in document order (Alight applies
     * them in order too: a later fill effect wins over an earlier one).
     */
    private fun applyLayerEffects(el: Element, base: TimelineClip, ctx: Ctx, durMs: Long, target: FxTarget): TimelineClip {
        var clip = base
        for (fx in elementChildren(el).filter { it.tagName == "effect" }) {
            val id = fx.getAttribute("id")
            if (target == FxTarget.TEXT && id in TEXT_STORED) continue   // the text reader stored it already
            clip = when (id) {
                SOLIDCOLOR -> applySolidColor(fx, clip, ctx, durMs, target)
                GRADIENTOVERLAY -> applyGradientOverlay(fx, clip, ctx, durMs, target)
                DRAWING_PROGRESS -> applyDrawingProgress(fx, clip, ctx, durMs, target)
                SCALEASSIST -> applyScaleAssist(fx, clip, ctx, durMs)
                TEXTSPACING -> applyTextSpacing(fx, clip, ctx, target)
                OSCILLATE, OSCILLATE3 -> applyOscillate(fx, clip, ctx, durMs, id)
                SWING, SWING2 -> applySwing(fx, clip, ctx, durMs, id)
                WIPE -> applyWipe(fx, clip, ctx, durMs)
                EDGEGLOW -> applyGlow(fx, clip, ctx, dark = false)
                DARKGLOW -> applyGlow(fx, clip, ctx, dark = true)
                GAUSSIANBLUR -> applyGaussianBlur(fx, clip, ctx)
                SHAKE -> applyShake(fx, clip, ctx, durMs)
                FLIP -> applyFlip(fx, clip, ctx, durMs)
                EXPOSURE -> applyExposureFx(fx, clip, ctx)
                FADE -> applyFade(fx, clip, ctx, durMs)
                BLINK, BLINK2 -> applyBlink(fx, clip, ctx, durMs)
                TILE -> applyTile(fx, clip, ctx)
                RGBSEP -> applyRgbSep(fx, clip, ctx)
                HUESHIFT -> applyHueShift(fx, clip, ctx, durMs, target)
                SATVIB -> applySatVib(fx, clip, ctx)
                BRIGHTCONT -> applyBrightCont(fx, clip, ctx)
                INVERTFX -> applyInvert(fx, clip, ctx)
                VIGNETTE -> applyVignette(fx, clip, ctx)
                ZOOMBLUR2, ZOOMBLUR3 -> applyZoomBlur(fx, clip, ctx)
                DBLUR -> applyDirectionalBlur(fx, clip, ctx)
                MOTIONBLUR, MOTIONBLUR2, MOTIONBLUR4 -> applyMotionBlur(fx, clip, ctx)
                LENSBLUR2 -> applyLensBlur(fx, clip, ctx)
                SHARPENFX -> applySharpenFx(fx, clip, ctx)
                PINCHBULGE, PINCHBULGE2 -> applyPinchBulge(fx, clip, ctx)
                PULSATE -> applyPulsate(fx, clip, ctx, durMs)
                TRANSFORMFX -> applyTransformFx(fx, clip, ctx, durMs)
                LIFT -> applyLift(fx, clip, ctx)
                REPEAT -> applyRepeat(fx, clip, ctx)
                REPEAT_RADIAL, REPEAT_SCATTER -> {
                    // A circle of copies / a scattered field: a different arrangement from the plain
                    // repeat, with no counterpart yet — named, not silently dropped.
                    ctx.warnings += "Effect " + id.substringAfter("effects.") +
                        " is not supported yet: the app cannot repeat a layer in a circle / scattered: skipped"
                    clip
                }
                MOVEALONGPATH -> {
                    ctx.warnings += "Effect movealongpath3 is not supported yet: the app cannot follow a path: skipped"
                    clip
                }
                else -> {
                    ctx.warnings += "Effect " + id.substringAfterLast('.') + " is not supported yet: skipped"
                    clip
                }
            }
        }
        return clip
    }

    /**
     * `com.alightcreative.effects.drawing.progress`: the draw-on of a layer's path. Alight stores `start` and
     * `end` in PERCENT of the path length, each constant or keyframed; the app stores fractions
     * ([com.example.model.TimelineClip.strokeTrimStart] / `strokeTrimEnd`) and animates them on the STROKE_TRIM
     * track, whose keyframes sit at the union of the two tracks' key times (each track is sampled there; the
     * easing of a segment comes from the Alight keyframe it ends at, the `start` track first — the same rule as
     * the composed size keyframes). What is trimmed is the layer's stroke; an imported line has no stroke (its
     * paint is its path), so its trim gates the fill instead (the line reader sets `strokeTrimFill`). On a text
     * or group layer there is nothing to trim, and a shape without a stroke has no outline to draw on: both
     * stay warnings.
     */
    private fun applyDrawingProgress(fx: Element, clip0: TimelineClip, ctx: Ctx, durMs: Long, target: FxTarget): TimelineClip {
        val w = ctx.warnings
        if (target != FxTarget.SHAPE) {
            w += "Effect drawing.progress is only supported on shape layers yet: skipped"
            return clip0
        }
        if (clip0.strokeWidth <= 0f && !clip0.strokeTrimFill) {
            w += "Effect drawing.progress on a shape without a stroke: there is no outline to trim: skipped"
            return clip0
        }
        val props = fxProps(fx)
        val startT = props["start"]
        val endT = props["end"]
        if (startT == null && endT == null) {
            w += "Effect drawing.progress has no start or end property: skipped"
            return clip0
        }
        // Percent (0..100, the fallback is in percent too) -> the app's fraction (0..1).
        fun frac(t: Track?, at: Float, fallbackPct: Float): Float =
            ((t?.valueAt(at)?.firstOrNull() ?: fallbackPct) / 100f).coerceIn(0f, 1f)
        var clip = clip0.copy(
            strokeTrimStart = frac(startT, 0f, 0f),
            strokeTrimEnd = frac(endT, 0f, 100f)
        )
        val times = ((startT?.keys ?: emptyList()) + (endT?.keys ?: emptyList())).map { it.t }.distinct().sorted()
        if (times.size >= 2) {
            val keys = times.mapIndexed { i, t ->
                val next = times.getOrNull(i + 1)
                val e = if (next == null) Easing.LINEAR
                else startT?.keyAt(next)?.easing ?: endT?.keyAt(next)?.easing ?: Easing.LINEAR
                Keyframe((t.coerceIn(0f, 1f) * durMs).roundToLong(), frac(startT, t, 0f), frac(endT, t, 100f), e)
            }.distinctBy { it.timeMs }
            if (keys.size >= 2) clip = clip.copy(keyframes = clip.keyframes + (AnimProp.STROKE_TRIM to keys))
        }
        return clip
    }

    /**
     * `com.alightcreative.solidcolor`: the layer's paint becomes one colour. Constant -> the clip colour,
     * keyframed -> FILL_COLOR keyframes (the app's colour track). The effect's `alpha` multiplies the layer
     * opacity. On a stroke-only shape the app cannot animate the stroke colour, so the colour is saved as the
     * fill colour and the stroke keeps its own (in Alight the whole layer, stroke included, is tinted).
     */
    private fun applySolidColor(fx: Element, clip0: TimelineClip, ctx: Ctx, durMs: Long, target: FxTarget): TimelineClip {
        val w = ctx.warnings
        if (target == FxTarget.GROUP) {
            w += "Effect solidcolor on a group is not supported yet: skipped"
            return clip0
        }
        var clip = clip0
        val props = fxProps(fx)
        val blend = props["blendMode"]?.scalarAt(0f)?.toInt() ?: 0
        if (blend != 0) w += "Effect solidcolor: blend mode " + blend + " is not supported yet: imported as normal"
        val colorT = props["color"]
        if (colorT != null) {
            val strokeOnly = target == FxTarget.SHAPE && clip.fillMode == FILL_NONE && clip.strokeWidth > 0f
            if (strokeOnly && colorT.keys.size >= 2) {
                w += "Effect solidcolor on a stroke-only layer: its colour animation is saved as the fill colour, the stroke keeps its own colour"
            } else if (strokeOnly) {
                w += "Effect solidcolor on a stroke-only layer: the colour is saved as the fill colour, the stroke keeps its own colour"
            }
            if (target == FxTarget.SHAPE && clip.fillMode == FILL_IMAGE) {
                w += "Effect solidcolor replaces the layer's picture fill"
                clip = clip.copy(fillMode = FILL_SOLID)
            }
            if (colorT.keys.size >= 2) {
                val keys = trackKeys(colorT, durMs) { (it.getOrNull(0) ?: 0f) to (it.getOrNull(1) ?: 0f) }
                clip = clip.copy(
                    textColor = unpacked(colorT.first()),
                    fillMode = if (clip.fillMode == FILL_GRADIENT) FILL_SOLID else clip.fillMode,
                    keyframes = clip.keyframes - AnimProp.GRAD_COLOR_1 - AnimProp.GRAD_COLOR_2 + (AnimProp.FILL_COLOR to keys)
                )
            } else {
                clip = clip.copy(
                    textColor = unpacked(colorT.first()),
                    fillMode = if (clip.fillMode == FILL_GRADIENT) FILL_SOLID else clip.fillMode,
                    keyframes = clip.keyframes - AnimProp.FILL_COLOR - AnimProp.GRAD_COLOR_1 - AnimProp.GRAD_COLOR_2
                )
            }
        }
        props["alpha"]?.let { clip = composeAlpha(clip, it, durMs) }
        return clip
    }

    /** Multiplies a clip's opacity by an Alight alpha track (0..1, constant or keyframed). */
    private fun composeAlpha(clip0: TimelineClip, alphaT: Track, durMs: Long): TimelineClip {
        val baseKeys = clip0.keyframes[AnimProp.OPACITY]
        if (alphaT.keys.size >= 2) {
            val times = ((baseKeys?.map { it.timeMs } ?: emptyList()) +
                alphaT.keys.map { (it.t.coerceIn(0f, 1f) * durMs).roundToLong() } + listOf(0L, durMs))
                .distinct().sorted()
            val keys = times.map { t ->
                val base = baseKeys?.valueAt(t)?.first ?: clip0.opacity
                val a = alphaT.scalarAt(if (durMs > 0L) t.toFloat() / durMs else 0f)
                Keyframe(t, (base * a).coerceIn(0f, 100f), 0f, Easing.LINEAR)
            }.distinctBy { it.timeMs }
            return clip0.copy(
                opacity = keys.firstOrNull()?.a ?: clip0.opacity,
                keyframes = clip0.keyframes + (AnimProp.OPACITY to keys)
            )
        }
        val a = alphaT.scalarAt(0f)
        if (abs(a - 1f) <= 1e-4f) return clip0
        var clip = clip0.copy(opacity = (clip0.opacity * a).coerceIn(0f, 100f))
        if (baseKeys != null) {
            clip = clip.copy(keyframes = clip.keyframes + (AnimProp.OPACITY to
                baseKeys.map { it.copy(a = (it.a * a).coerceIn(0f, 100f)) }))
        }
        return clip
    }

    /**
     * `com.alightcreative.gradientoverlay2` -> the app's gradient fill: its two colours (constant or
     * GRAD_COLOR_1 / GRAD_COLOR_2 keyframes) and its type (0 linear, 1 radial, 2 sweep, the app's order).
     * Alight places the gradient in layer space by angle / scale / offset (scene pixels); the app wants two
     * points inside the layer box, so those three are converted approximately. The effect's own alpha (it
     * blends the gradient over the layer's fill in Alight; the app's gradient REPLACES the fill) and a
     * non-normal blend mode are not mapped.
     */
    private fun applyGradientOverlay(fx: Element, clip0: TimelineClip, ctx: Ctx, durMs: Long, target: FxTarget): TimelineClip {
        val w = ctx.warnings
        if (target == FxTarget.GROUP) {
            w += "Effect gradientoverlay on a group is not supported yet: skipped"
            return clip0
        }
        var clip = clip0
        val props = fxProps(fx)
        val blend = props["blendMode"]?.scalarAt(0f)?.toInt() ?: 0
        if (blend != 0) w += "Effect gradientoverlay: blend mode " + blend + " is not supported yet: imported as normal"
        val gt = props["gradType"]?.scalarAt(0f)?.toInt() ?: 0
        val gradType = when (gt) {
            0 -> GRAD_LINEAR
            1 -> GRAD_RADIAL
            2 -> GRAD_SWEEP
            else -> {
                w += "Effect gradientoverlay: gradient type " + gt + " is not supported yet: a linear gradient is used"
                GRAD_LINEAR
            }
        }
        clip = clip.copy(fillMode = FILL_GRADIENT, gradType = gradType, keyframes = clip.keyframes - AnimProp.FILL_COLOR)
        for ((propName, animProp, first) in listOf(
            Triple("color1", AnimProp.GRAD_COLOR_1, true),
            Triple("color2", AnimProp.GRAD_COLOR_2, false)
        )) {
            val t = props[propName] ?: continue
            if (t.keys.size >= 2) {
                val keys = trackKeys(t, durMs) { (it.getOrNull(0) ?: 0f) to (it.getOrNull(1) ?: 0f) }
                val c = unpacked(t.first())
                clip = if (first) clip.copy(gradColor1 = c, keyframes = clip.keyframes + (animProp to keys))
                else clip.copy(gradColor2 = c, keyframes = clip.keyframes + (animProp to keys))
            } else {
                val c = unpacked(t.first())
                clip = if (first) clip.copy(gradColor1 = c) else clip.copy(gradColor2 = c)
            }
        }
        val angle = props["angle"]?.scalarAt(0f) ?: 0f
        val scale = props["scale"]?.scalarAt(0f) ?: 1f
        val off = props["offset"]?.first() ?: emptyList()
        val (boxW, boxH) = layerBoxPx(clip, ctx)
        val rad = Math.toRadians(angle.toDouble())
        val dx = cos(rad).toFloat()
        val dy = sin(rad).toFloat()
        val cx = 0.5f + (off.getOrNull(0) ?: 0f) / boxW
        val cy = 0.5f + (off.getOrNull(1) ?: 0f) / boxH
        val half = 0.5f * scale
        clip = if (gradType == GRAD_LINEAR) {
            clip.copy(gradX1 = cx - dx * half, gradY1 = cy - dy * half, gradX2 = cx + dx * half, gradY2 = cy + dy * half)
        } else {
            clip.copy(gradX1 = cx, gradY1 = cy, gradX2 = cx + dx * half, gradY2 = cy + dy * half)
        }
        props["alpha"]?.let { aT ->
            if (aT.keys.size >= 2 || abs(aT.scalarAt(0f) - 1f) > 1e-4f) {
                w += "Effect gradientoverlay: its alpha is not supported yet: the gradient replaces the fill"
            }
        }
        w += "Effect gradientoverlay: angle, offset and scale are mapped approximately"
        return clip
    }

    /** The layer's box in scene pixels: a shape's rendered size, otherwise (text, group content) the scene itself. */
    private fun layerBoxPx(clip: TimelineClip, ctx: Ctx): Pair<Float, Float> {
        if (clip.type != LayerType.SHAPE) return ctx.sceneW to ctx.sceneH
        val w = clip.shapeScaleX * ctx.shapeBoxPx
        val h = clip.shapeScaleY * ctx.shapeBoxPx
        return (if (w > 1f) w else ctx.sceneW) to (if (h > 1f) h else ctx.sceneH)
    }

    /**
     * `com.alightcreative.effects.textspacing` on a text layer. Alight's letter spacing is a fraction of the
     * font size (0.13 = mild, 2.11 = very wide display type), the app stores percent of the font size; the
     * line spacing is a multiplier of the natural line height, the app stores percent.
     */
    private fun applyTextSpacing(fx: Element, clip0: TimelineClip, ctx: Ctx, target: FxTarget): TimelineClip {
        if (target != FxTarget.TEXT) {
            ctx.warnings += "Effect textspacing is not supported yet: skipped"
            return clip0
        }
        val props = fxProps(fx)
        var clip = clip0
        props["letterspacing"]?.let { t ->
            if (t.keys.size >= 2) ctx.warnings += "Effect textspacing: keyframed letter spacing is not supported yet: the first value is used"
            clip = clip.copy(letterSpacing = t.scalarAt(0f) * 100f)
        }
        props["linespacing"]?.let { t ->
            if (t.keys.size >= 2) ctx.warnings += "Effect textspacing: keyframed line spacing is not supported yet: the first value is used"
            val v = t.scalarAt(0f)
            if (v > 0f) clip = clip.copy(lineHeightPercent = v * 100f)
        }
        return clip
    }

    /**
     * `com.alightcreative.effects.scaleassist`: a keyframed scale multiplier on top of the layer's own scale
     * (in the samples: a pop, 1 -> 1.175 -> 1, with its own bezier easings). `axis` 3 = both axes; the app
     * animates one scale track, so the multiplier is composed into the SCALE keyframes (its keyframe times
     * are kept, so the pop's corners stay exact; the easing between them becomes linear). `damp` only shapes
     * Alight's own smoothing of the curve and is not reproduced.
     */
    private fun applyScaleAssist(fx: Element, clip0: TimelineClip, ctx: Ctx, durMs: Long): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val axis = props["axis"]?.scalarAt(0f)?.toInt() ?: 3
        if (axis != 3) {
            w += "Effect scaleassist: axis " + axis + " is not supported yet: skipped"
            return clip0
        }
        val scaleT = props["scale"] ?: return clip0
        return composeScaleMultiplier(clip0, scaleT, durMs)
    }

    /**
     * Multiplies a clip's SCALE track by an Alight scale track (constant or keyframed), composed over the
     * layer's own keyframed scale exactly like [applyScaleAssist] does; the `transform` effect and
     * `pulsate2` reuse it for their own scale modulation.
     */
    private fun composeScaleMultiplier(clip0: TimelineClip, scaleT: Track, durMs: Long): TimelineClip {
        val baseKeys = clip0.keyframes[AnimProp.SCALE]
        if (scaleT.keys.size >= 2) {
            val times = ((baseKeys?.map { it.timeMs } ?: emptyList()) +
                scaleT.keys.map { (it.t.coerceIn(0f, 1f) * durMs).roundToLong() } + listOf(0L, durMs))
                .distinct().sorted()
            val keys = times.map { t ->
                val base = baseKeys?.valueAt(t)?.first ?: clip0.scalePercent
                val f = scaleT.scalarAt(if (durMs > 0L) t.toFloat() / durMs else 0f)
                Keyframe(t, base * f, 0f, Easing.LINEAR)
            }.distinctBy { it.timeMs }
            val firstA = keys.firstOrNull()?.a ?: clip0.scalePercent
            var clip = clip0.copy(scalePercent = firstA, keyframes = clip0.keyframes + (AnimProp.SCALE to keys))
            if (clip0.type == LayerType.SHAPE) {
                clip = clip.copy(
                    shapeScaleX = firstA / 100f,
                    shapeScaleY = clip0.shapeScaleY * scaleT.scalarAt(0f)
                )
            }
            return clip
        }
        val f = scaleT.scalarAt(0f)
        if (abs(f - 1f) <= 1e-4f) return clip0
        var clip = clip0.copy(scalePercent = clip0.scalePercent * f)
        if (clip0.type == LayerType.SHAPE) {
            clip = clip.copy(shapeScaleX = clip0.shapeScaleX * f, shapeScaleY = clip0.shapeScaleY * f)
        }
        if (baseKeys != null) {
            clip = clip.copy(keyframes = clip.keyframes + (AnimProp.SCALE to baseKeys.map { it.copy(a = it.a * f) }))
        }
        return clip
    }

    /**
     * `oscillate` / `oscillate3`: the layer is moved along the `angle` direction (degrees, scene pixels) by
     * mag(t) * sin(phase(t)), phase(t) = 2 * PI * (the integral of `freq` in Hz) + `phase` (in cycles).
     * `type` 0 is the sine (the only waveform in the samples); oscillate3's `direction` is 0 in every sample.
     * The app has no procedural oscillation (its Camera Shake is random noise, not this), so the motion is
     * baked into the POSITION keyframes, added over the layer's own keyframed move.
     */
    private fun applyOscillate(fx: Element, clip0: TimelineClip, ctx: Ctx, durMs: Long, id: String): TimelineClip {
        val w = ctx.warnings
        val name = id.substringAfterLast('.')
        val props = fxProps(fx)
        val typeV = props["type"]?.scalarAt(0f)?.toInt() ?: 0
        if (typeV != 0) w += "Effect " + name + ": waveform type " + typeV + " is not supported yet: a sine wave is used"
        if (id == OSCILLATE3) {
            val dir = props["direction"]?.scalarAt(0f)?.toInt() ?: 0
            if (dir != 0) w += "Effect oscillate3: direction " + dir + " is not supported yet: oscillation along the angle is used"
        }
        val magT = props["mag"] ?: return clip0
        val magMax = (magT.keys.map { abs(it.v.firstOrNull() ?: 0f) } +
            listOf(abs(magT.constant.firstOrNull() ?: 0f), abs(magT.first().firstOrNull() ?: 0f))).maxOrNull() ?: 0f
        if (magMax < 1e-4f) return clip0
        val angleT = props["angle"]
        val freqT = props["freq"]
        val phase0 = (props["phase"]?.scalarAt(0f) ?: 0f).toDouble() * 2.0 * Math.PI
        val extra = (magT.keys + (angleT?.keys ?: emptyList()) + (freqT?.keys ?: emptyList()))
            .map { (it.t.coerceIn(0f, 1f) * durMs).roundToLong() }
        return bakeDelta(clip0, AnimProp.POSITION, durMs, ctx, phase0, { n -> freqT?.scalarAt(n) ?: 0f }, extra) { phase, norm ->
            val mag = magT.scalarAt(norm)
            val ang = angleT?.scalarAt(norm) ?: 0f
            val s = sin(phase).toFloat() * mag
            val rad = Math.toRadians(ang.toDouble())
            (s * cos(rad).toFloat() * ctx.k) to (s * sin(rad).toFloat() * ctx.k)
        }
    }

    /**
     * `swing` / `swing2`: the layer's rotation swings between the angles `a1` and `a2` (degrees):
     * rot(t) = mid(t) + amp(t) * sin(phase(t)), mid = (a1 + a2) / 2, amp = (a2 - a1) / 2, the phase like in
     * [applyOscillate]. Baked into the ROTATION keyframes, added over the layer's own keyframed turn.
     */
    private fun applySwing(fx: Element, clip0: TimelineClip, ctx: Ctx, durMs: Long, id: String): TimelineClip {
        val w = ctx.warnings
        val name = id.substringAfterLast('.')
        val props = fxProps(fx)
        val typeV = props["type"]?.scalarAt(0f)?.toInt() ?: 0
        if (typeV != 0) w += "Effect " + name + ": waveform type " + typeV + " is not supported yet: a sine wave is used"
        val a1T = props["a1"] ?: return clip0
        val a2T = props["a2"] ?: return clip0
        val freqT = props["freq"]
        val phase0 = (props["phase"]?.scalarAt(0f) ?: 0f).toDouble() * 2.0 * Math.PI
        val norms = ((a1T.keys + a2T.keys).map { it.t } + listOf(0f, 0.25f, 0.5f, 0.75f, 1f)).distinct()
        var ampMax = 0f
        var midSum = 0f
        for (n in norms) {
            ampMax = maxOf(ampMax, abs(a2T.scalarAt(n) - a1T.scalarAt(n)) / 2f)
            midSum += (a1T.scalarAt(n) + a2T.scalarAt(n)) / 2f
        }
        if (ampMax < 1e-3f) {
            // No swing left, only a steady extra turn (or nothing at all).
            val mid = midSum / norms.size.coerceAtLeast(1)
            if (abs(mid) < 1e-3f) return clip0
            var clip = clip0.withProp(AnimProp.ROTATION, clip0.rotationDeg + mid, 0f)
            val bk = clip0.keyframes[AnimProp.ROTATION]
            if (bk != null) {
                clip = clip.copy(keyframes = clip.keyframes + (AnimProp.ROTATION to bk.map { it.copy(a = it.a + mid) }))
            }
            return clip
        }
        val extra = (a1T.keys + a2T.keys + (freqT?.keys ?: emptyList()))
            .map { (it.t.coerceIn(0f, 1f) * durMs).roundToLong() }
        return bakeDelta(clip0, AnimProp.ROTATION, durMs, ctx, phase0, { n -> freqT?.scalarAt(n) ?: 0f }, extra) { phase, norm ->
            val a1 = a1T.scalarAt(norm)
            val a2 = a2T.scalarAt(norm)
            ((a1 + a2) / 2f + (a2 - a1) / 2f * sin(phase).toFloat()) to 0f
        }
    }

    /**
     * Samples a procedural delta and adds it onto a clip's own track of [prop]: the value at every sample is
     * the layer's own value there plus the delta. Samples sit on the scene's frames and at least 16 per
     * oscillation period, plus on every keyframe of the layer and of the effect's own properties, so the
     * corners stay exact; keys the curve makes redundant are dropped again. The phase is accumulated
     * (phase += 2 * PI * freq * dt), so a keyframed `freq` still gives the right phase.
     */
    private fun bakeDelta(
        clip0: TimelineClip, prop: AnimProp, durMs: Long, ctx: Ctx,
        phase0: Double, freqAt: (Float) -> Float, extraTimes: Collection<Long>,
        delta: (phaseRad: Double, normT: Float) -> Pair<Float, Float>
    ): TimelineClip {
        if (durMs <= 0L) return clip0
        var maxFreq = 0f
        for (i in 0..16) maxFreq = maxOf(maxFreq, abs(freqAt(i / 16f)))
        val baseKeys = clip0.keyframes[prop]
        val extras = (baseKeys?.map { it.timeMs } ?: emptyList()) + extraTimes
        val times = sampleTimes(durMs, ctx.fps, maxFreq, extras)
        val basePlain = clip0.propValues(prop)
        var phase = phase0
        var prevMs = 0L
        val raw = ArrayList<Keyframe>(times.size)
        for (t in times) {
            if (t > prevMs) {
                val midNorm = ((prevMs + t) / 2f) / durMs
                phase += 2.0 * Math.PI * freqAt(midNorm) * (t - prevMs) / 1000.0
            }
            prevMs = t
            val norm = t.toFloat() / durMs
            val (dx, dy) = delta(phase, norm)
            val base = baseKeys?.valueAt(t) ?: basePlain
            raw += Keyframe(t, base.first + dx, base.second + dy, Easing.LINEAR)
        }
        val (epsA, epsB) = if (prop == AnimProp.ROTATION) 0.02f to 0.02f else 0.05f to 0.05f
        val keys = simplifyKeys(raw, epsA, epsB)
        var clip = clip0.copy(keyframes = clip0.keyframes + (prop to keys))
        keys.firstOrNull()?.let { clip = clip.withProp(prop, it.a, it.b) }
        return clip
    }

    /** Sample grid of a bake: 0, the layer end, frame steps (finer for fast oscillations), plus [extra]. */
    private fun sampleTimes(durMs: Long, fps: Float, maxFreq: Float, extra: Collection<Long>): List<Long> {
        val frameMs = if (fps > 0f) 1000f / fps else 33.333f
        var step = minOf(frameMs, if (maxFreq > 0.01f) 1000f / maxFreq / 16f else frameMs)
        if (step < 4f) step = 4f
        var guard = 0
        while (true) {
            val set = sortedSetOf(0L, durMs)
            var t = step.toDouble()
            while (t < durMs) {
                set += t.roundToLong()
                t += step
            }
            set += extra
            val times = set.filter { it in 0..durMs }
            if (times.size <= 1600 || guard++ >= 5) return times
            step *= 2f
        }
    }

    /** Drops keys that sit (nearly) on the straight line between their kept neighbours. */
    private fun simplifyKeys(keys: List<Keyframe>, epsA: Float, epsB: Float): List<Keyframe> {
        if (keys.size <= 2) return keys
        val out = ArrayList<Keyframe>(keys.size)
        out += keys.first()
        var anchor = keys.first()
        for (i in 1 until keys.size - 1) {
            val next = keys[i + 1]
            val span = (next.timeMs - anchor.timeMs).toFloat()
            val f = if (span > 0f) (keys[i].timeMs - anchor.timeMs) / span else 0f
            val ea = anchor.a + (next.a - anchor.a) * f
            val eb = anchor.b + (next.b - anchor.b) * f
            if (abs(keys[i].a - ea) > epsA || abs(keys[i].b - eb) > epsB) {
                out += keys[i]
                anchor = keys[i]
            }
        }
        out += keys.last()
        return out.distinctBy { it.timeMs }
    }

    // ---- effects (pass 3) ---------------------------------------------------------------------------------------

    /** Adds a native [ClipFx] to a clip, honouring the app's per-layer effect cap (the rest would never run). */
    private fun addFx(clip0: TimelineClip, ctx: Ctx, type: String, values: List<Float>, short: String): TimelineClip {
        if (ClipFxLibrary.def(type) == null) return clip0
        if (clip0.effects.size >= ClipFxLibrary.MAX_EFFECTS) {
            ctx.warnings += "Effect " + short + " is not added: a layer runs at most " +
                ClipFxLibrary.MAX_EFFECTS + " effects in the app"
            return clip0
        }
        return clip0.copy(effects = clip0.effects + ClipFx(type = type, values = values))
    }

    /** The first value of a track the native fx model cannot animate, with the pass-2 style note. */
    private fun Track.firstValue(w: MutableList<String>, short: String, prop: String): Float {
        if (keys.size >= 2) w += "Effect " + short + ": keyframed " + prop + " is not supported yet: the first value is used"
        return scalarAt(0f)
    }

    /** The easing of the Alight keyframe at [ms] (a segment takes the easing of the keyframe it ends at). */
    private fun Track.easingAtMs(ms: Long, durMs: Long): Easing? =
        keys.firstOrNull { (it.t.coerceIn(0f, 1f) * durMs).roundToLong() == ms }?.easing

    /**
     * `wipe2` -> a rectangular [ClipMask]: the layer is visible where the projection of its box onto the
     * wipe direction lies between `start` and `end` (both 0..1 of the box's projection span; `angle` 90
     * wipes downward, as in the graph template's bars). The app has no wipe effect of its own, but a mask
     * rectangle rotated to the wipe angle, centred on the band and long enough across it, is the same
     * reveal — and mask keys carry the animation, with the easings of the AM keyframes. A wipe that stays
     * fully open adds no mask at all. Alight's `feather` is in scene pixels along the wipe; the mask's
     * feather is its own 0..100 soft edge, so it is converted against the layer's extent and reported.
     */
    private fun applyWipe(fx: Element, clip0: TimelineClip, ctx: Ctx, durMs: Long): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val startT = props["start"]
        val endT = props["end"]
        if (startT == null && endT == null) return clip0
        val angle = props["angle"]?.firstValue(w, "wipe", "angle") ?: 0f
        val feather = props["feather"]?.firstValue(w, "wipe", "feather") ?: 0f
        fun sAt(n: Float) = startT?.scalarAt(n) ?: 0f
        fun eAt(n: Float) = endT?.scalarAt(n) ?: 1f
        val norms = (((startT?.keys ?: emptyList()) + (endT?.keys ?: emptyList())).map { it.t } + listOf(0f, 1f)).distinct()
        if (norms.all { sAt(it) <= 0f && eAt(it) >= 1f }) return clip0   // open the whole time: no mask needed

        val rad = Math.toRadians(angle.toDouble())
        val c = cos(rad).toFloat()
        val sn = sin(rad).toFloat()
        val len = abs(c) + abs(sn)                                   // projection span of the unit box
        val projMin = minOf(0f, c) + minOf(0f, sn)                   // projection of the box's near corner
        val projC = (c + sn) / 2f                                    // projection of the box centre
        val (boxW, boxH) = layerBoxPx(clip0, ctx)
        val extent = boxW * abs(c) + boxH * abs(sn)
        val featherPct = if (extent > 1f) (feather / extent * 100f).coerceIn(0f, 100f) else 0f
        if (feather > 0f) w += "Effect wipe: its feather is mapped approximately"
        fun state(s: Float, e: Float): MaskState {
            val d = projMin + (s + e) / 2f * len - projC
            return MaskState(
                cx = 0.5f + c * d, cy = 0.5f + sn * d,
                w = ((e - s) * len).coerceAtLeast(0f), h = len + 1.5f,
                rot = angle, feather = featherPct, opacity = 100f
            )
        }
        val keyframed = (startT?.keys?.size ?: 0) >= 2 || (endT?.keys?.size ?: 0) >= 2
        val mask = if (!keyframed) {
            ClipMask(shape = MaskShape.RECT, mode = MaskMode.ADD, state = state(sAt(0f), eAt(0f)))
        } else {
            val times = norms.map { (it.coerceIn(0f, 1f) * durMs).roundToLong() }.distinct().sorted()
            val keys = times.mapIndexed { i, t ->
                val n = if (durMs > 0L) t.toFloat() / durMs else 0f
                val next = times.getOrNull(i + 1)
                val e = if (next == null) Easing.LINEAR
                else startT?.easingAtMs(next, durMs) ?: endT?.easingAtMs(next, durMs) ?: Easing.LINEAR
                MaskKey(t, state(sAt(n), eAt(n)), e)
            }
            ClipMask(shape = MaskShape.RECT, mode = MaskMode.ADD, state = keys.firstOrNull()?.state ?: state(0f, 1f), keys = keys)
        }
        return clip0.copy(masks = clip0.masks + mask)
    }

    /**
     * `edgeglow` / `darkglow` -> the app's Glow. Edge glow blooms the layer's edges outwards in a colour;
     * the app's glow blooms the bright parts of the layer itself, so the mapping is: threshold 0..1 -> %,
     * spread -> Radius (spread ~1.5 reads as about half the app's radius range), strength 0..1 -> Intensity
     * %. The glow colour (the app's glow has none) and, for darkglow, the fact that Alight blooms the DARK
     * parts, are reported. Dark glow's `alpha` has no counterpart and is dropped with the colour note.
     */
    private fun applyGlow(fx: Element, clip0: TimelineClip, ctx: Ctx, dark: Boolean): TimelineClip {
        val w = ctx.warnings
        val short = if (dark) "darkglow" else "edgeglow"
        val props = fxProps(fx)
        val threshold = props["threshold"]?.firstValue(w, short, "threshold") ?: 0f
        val strength = props["strength"]?.firstValue(w, short, "strength") ?: 0f
        val spread = props["spread"]?.firstValue(w, short, "spread") ?: 0f
        val colorProp = if (dark) "color" else "fillColor"
        val colorTrack = props[colorProp]
        val anyNonWhite = colorTrack != null &&
            (listOf(colorTrack.constant) + colorTrack.keys.map { it.v }).any { unpacked(it) != 0xFFFFFFFFL }
        val aspects = ArrayList<String>()
        if (dark) aspects += "it blooms dark parts, the app's Glow blooms bright ones"
        if (anyNonWhite) aspects += "its colour is not reproduced"
        if (!dark && (props["invert"]?.scalarAt(0f) ?: 0f) > 0.5f) aspects += "its invert is not reproduced"
        if (dark && abs((props["alpha"]?.scalarAt(0f) ?: 1f) - 1f) > 1e-4f) aspects += "its alpha is not reproduced"
        if (aspects.isNotEmpty()) {
            w += "Effect " + short + " is approximated by the app's Glow: " + aspects.joinToString(", ")
        }
        return addFx(
            clip0, ctx, ClipFxLibrary.GLOW,
            listOf(
                (threshold * 100f).coerceIn(0f, 100f),
                (spread * 33f).coerceIn(0f, 100f),
                (strength * 100f).coerceIn(0f, 300f)
            ),
            short
        )
    }

    /**
     * `gaussianblur` -> the app's Gaussian Blur. Alight's `strength` is 0..1; the app's Blurriness is
     * 0..100 and half of it already reads as a strong blur, so strength maps onto 0..50.
     */
    private fun applyGaussianBlur(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val props = fxProps(fx)
        val strength = props["strength"]?.firstValue(ctx.warnings, "gaussianblur", "strength") ?: return clip0
        return addFx(clip0, ctx, ClipFxLibrary.BLUR, listOf((strength * 50f).coerceIn(0f, 100f)), "gaussianblur")
    }

    /** `exposure` -> the app's Exposure: stops as-is, offset 0..1 -> -100..100, gamma as a percent. */
    private fun applyExposureFx(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val ev = props["exposure"]?.firstValue(w, "exposure", "exposure") ?: 0f
        val gamma = props["gamma"]?.firstValue(w, "exposure", "gamma") ?: 1f
        val offset = props["offset"]?.firstValue(w, "exposure", "offset") ?: 0f
        return addFx(
            clip0, ctx, ClipFxLibrary.EXPOSURE,
            listOf(
                ev.coerceIn(-5f, 5f),
                (offset * 100f).coerceIn(-100f, 100f),
                (gamma * 100f).coerceIn(20f, 300f),
                0f
            ),
            "exposure"
        )
    }

    /**
     * `shake2`: Alight's noise shake — the layer is displaced by mag(t) (scene pixels) of smooth value
     * noise running at `freq` Hz, starting at `evolution` cycles, decorrelated by `seed`, mostly along
     * `angle`. The app's Camera Shake works in unrelated units (its Amount is not pixels and its noise is
     * not seeded), so — like oscillate — the shake is BAKED into the POSITION keyframes, with a
     * deterministic seeded value noise standing in for Alight's own noise (the motion matches in size, rate
     * and direction, not frame for frame). `slack` and `zshake` (rotation / zoom shake) are not reproduced.
     */
    private fun applyShake(fx: Element, clip0: TimelineClip, ctx: Ctx, durMs: Long): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val magT = props["mag"] ?: return clip0
        val slack = props["slack"]?.scalarAt(0f) ?: 0f
        val zshake = props["zshake"]?.scalarAt(0f) ?: 0f
        if (abs(slack) > 1e-4f || abs(zshake) > 1e-4f) {
            w += "Effect shake: its slack / zoom shake is not reproduced"
        }
        val magMax = (magT.keys.map { abs(it.v.firstOrNull() ?: 0f) } +
            listOf(abs(magT.constant.firstOrNull() ?: 0f), abs(magT.first().firstOrNull() ?: 0f))).maxOrNull() ?: 0f
        if (magMax < 1e-4f) return clip0
        val freqT = props["freq"]
        val seed = (props["seed"]?.scalarAt(0f) ?: 0f).toInt()
        val angle = props["angle"]?.scalarAt(0f) ?: 0f
        val phase0 = (props["evolution"]?.scalarAt(0f) ?: 0f).toDouble() * 2.0 * Math.PI
        val extra = (magT.keys + (freqT?.keys ?: emptyList())).map { (it.t.coerceIn(0f, 1f) * durMs).roundToLong() }
        val rad = Math.toRadians(angle.toDouble())
        val ca = cos(rad).toFloat()
        val sa = sin(rad).toFloat()
        return bakeDelta(clip0, AnimProp.POSITION, durMs, ctx, phase0, { n -> freqT?.scalarAt(n) ?: 0f }, extra) { phase, norm ->
            val cycles = phase / (2.0 * Math.PI)
            val mag = magT.scalarAt(norm)
            val nx = valueNoise(seed * 2 + 1, cycles)
            val ny = valueNoise(seed * 2 + 2, cycles * 0.913 + 7.31)
            val along = nx * mag
            val across = ny * mag * 0.6f
            ((along * ca - across * sa) * ctx.k) to ((along * sa + across * ca) * ctx.k)
        }
    }

    /** Deterministic smooth value noise in -1..1, one lattice cell per cycle. */
    private fun valueNoise(seed: Int, x: Double): Float {
        val i = floor(x).toLong()
        val f = x - i
        val u = f * f * (3.0 - 2.0 * f)
        val a = hash01(seed, i)
        val b = hash01(seed, i + 1)
        return ((a + (b - a) * u) * 2.0 - 1.0).toFloat()
    }

    private fun hash01(seed: Int, i: Long): Double {
        var h = i * 374761393L + seed.toLong() * 668265263L
        h = (h xor (h ushr 13)) * 1274126177L
        h = h xor (h ushr 16)
        return (h and 0x7fffffffL).toDouble() / 2147483648.0
    }

    /**
     * `flip3` -> the layer's 3D rotation: the layer turns by `angle` around an axis that lies in the screen
     * plane, `axis` degrees away from the vertical (axis 0 = a turntable flip around the vertical axis =
     * the app's ROT_Y; axis 90 = a flip around the horizontal axis = ROT_X). A keyframed angle composes
     * onto both tracks with its easings. The effect's fake-3D extras — lighting, a solid back face, crop
     * and camera distance — have no counterpart and are reported when they are not at their defaults.
     */
    private fun applyFlip(fx: Element, clip0: TimelineClip, ctx: Ctx, durMs: Long): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        if ((props["enableLight"]?.scalarAt(0f) ?: 0f) > 0.5f) {
            w += "Effect flip: its lighting is not reproduced"
        }
        if ((props["solid"]?.scalarAt(0f) ?: 0f) > 0.5f) {
            w += "Effect flip: its solid back face is not reproduced"
        }
        val crop = props["crop"]?.scalarAt(0f) ?: 1f
        if (abs(crop - 1f) > 1e-4f) w += "Effect flip: its crop is not reproduced"
        val zdist = props["zdist"]?.scalarAt(0f) ?: 0f
        if (abs(zdist) > 1e-4f) w += "Effect flip: its camera distance is not reproduced"
        val angleT = props["angle"] ?: return clip0
        val axis = props["axis"]?.scalarAt(0f) ?: 0f
        val rad = Math.toRadians(axis.toDouble())
        val wx = sin(rad).toFloat()
        val wy = cos(rad).toFloat()
        if (angleT.keys.size < 2) {
            val th = angleT.scalarAt(0f)
            if (abs(th) < 1e-4f) return clip0
            if (clip0.keyframes[AnimProp.ROT_X] == null && clip0.keyframes[AnimProp.ROT_Y] == null) {
                return clip0.copy(rotation3dX = clip0.rotation3dX + th * wx, rotation3dY = clip0.rotation3dY + th * wy)
            }
        }
        val baseX = clip0.keyframes[AnimProp.ROT_X]
        val baseY = clip0.keyframes[AnimProp.ROT_Y]
        val times = ((angleT.keys.map { (it.t.coerceIn(0f, 1f) * durMs).roundToLong() }) +
            (baseX?.map { it.timeMs } ?: emptyList()) + (baseY?.map { it.timeMs } ?: emptyList()) +
            listOf(0L, durMs)).distinct().sorted()
        fun keysFor(base: List<Keyframe>?, plain: Float, weight: Float): List<Keyframe> =
            times.mapIndexed { i, t ->
                val n = if (durMs > 0L) t.toFloat() / durMs else 0f
                val b = base?.valueAt(t)?.first ?: plain
                val next = times.getOrNull(i + 1)
                val e = if (next == null) Easing.LINEAR else angleT.easingAtMs(next, durMs) ?: Easing.LINEAR
                Keyframe(t, b + angleT.scalarAt(n) * weight, 0f, e)
            }.distinctBy { it.timeMs }
        val kx = keysFor(baseX, clip0.rotation3dX, wx)
        val ky = keysFor(baseY, clip0.rotation3dY, wy)
        return clip0.copy(
            rotation3dX = kx.firstOrNull()?.a ?: clip0.rotation3dX,
            rotation3dY = ky.firstOrNull()?.a ?: clip0.rotation3dY,
            keyframes = clip0.keyframes + (AnimProp.ROT_X to kx) + (AnimProp.ROT_Y to ky)
        )
    }

    /** `fade`: its in / out times (seconds) become the clip's fade in / out, scaled down if they overlap. */
    private fun applyFade(fx: Element, clip0: TimelineClip, ctx: Ctx, durMs: Long): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        var fadeIn = (props["inTime"]?.firstValue(w, "fade", "in time") ?: 0f) * 1000f
        var fadeOut = (props["outTime"]?.firstValue(w, "fade", "out time") ?: 0f) * 1000f
        fadeIn = fadeIn.coerceIn(0f, durMs.toFloat())
        fadeOut = fadeOut.coerceIn(0f, durMs.toFloat())
        if (fadeIn + fadeOut > durMs && fadeIn + fadeOut > 0f) {
            val s = durMs / (fadeIn + fadeOut)
            fadeIn *= s
            fadeOut *= s
        }
        return clip0.copy(
            fadeInMs = maxOf(clip0.fadeInMs, fadeIn.roundToLong()),
            fadeOutMs = maxOf(clip0.fadeOutMs, fadeOut.roundToLong())
        )
    }

    /**
     * `blink` / `blink2`: the layer blinks on and off `freq` times a second, starting ON. There is no blink
     * effect in the app, so the square wave is baked into the OPACITY keyframes over the layer's own
     * opacity: keys only at the toggle times, each segment given a hold easing so the value stays put and
     * jumps at the toggle instead of ramping. A blink faster than ~1400 keys is reported instead.
     */
    private fun applyBlink(fx: Element, clip0: TimelineClip, ctx: Ctx, durMs: Long): TimelineClip {
        val w = ctx.warnings
        val short = fx.getAttribute("id").substringAfterLast('.')
        val props = fxProps(fx)
        val freq = props["freq"]?.firstValue(w, short, "frequency") ?: return clip0
        if (freq <= 0f || durMs <= 0L) return clip0
        val half = 500.0 / freq
        if (half >= durMs) return clip0   // never toggles inside the layer: it just stays on
        val baseKeys = clip0.keyframes[AnimProp.OPACITY]
        if (baseKeys == null) {
            val times = ArrayList<Long>()
            var t = 0.0
            while (t < durMs) {
                times += t.roundToLong()
                t += half
            }
            times += durMs
            if (times.size > 1400) {
                w += "Effect " + short + ": it is too fast to bake into opacity keyframes: skipped"
                return clip0
            }
            val keys = times.mapIndexed { i, ms ->
                val on = i % 2 == 0
                Keyframe(ms, if (on) clip0.opacity else 0f, 0f, if (i < times.size - 1) HOLD_EASING else Easing.LINEAR)
            }.distinctBy { it.timeMs }
            return clip0.copy(keyframes = clip0.keyframes + (AnimProp.OPACITY to keys))
        }
        val times = sortedSetOf(0L, durMs)
        var t = half
        while (t < durMs) {
            times += t.roundToLong()
            t += half
        }
        times += baseKeys.map { it.timeMs }
        if (times.size > 1400) {
            w += "Effect " + short + ": it is too fast to bake into opacity keyframes: skipped"
            return clip0
        }
        val keys = times.map { ms ->
            val base = baseKeys.valueAt(ms).first
            val on = floor(ms / half).toLong() % 2L == 0L
            Keyframe(ms, if (on) base else 0f, 0f, Easing.LINEAR)
        }
        return clip0.copy(
            opacity = keys.firstOrNull()?.a ?: clip0.opacity,
            keyframes = clip0.keyframes + (AnimProp.OPACITY to keys)
        )
    }

    /**
     * `tile` -> the app's Motion Tile: Alight's `scale` (how many tiles fit the frame) becomes the tile
     * count on both axes, `mirror` the mirrored edges, `phase` the phase on both axes. A rotated tile grid
     * (`angle`) and the vertical offset mode have no counterpart and are reported.
     */
    private fun applyTile(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val aspects = ArrayList<String>()
        val angle = props["angle"]?.scalarAt(0f) ?: 0f
        if (abs(angle) > 1e-4f) aspects += "its angle is not reproduced"
        if ((props["vertoffs"]?.scalarAt(0f) ?: 0f) > 0.5f) aspects += "its vertical offset is not reproduced"
        if (aspects.isNotEmpty()) w += "Effect tile is mapped approximately: " + aspects.joinToString(", ")
        val scale = props["scale"]?.firstValue(w, "tile", "scale") ?: 1f
        val mirror = if ((props["mirror"]?.scalarAt(0f) ?: 0f) > 0.5f) 1f else 0f
        val phase = ((props["phase"]?.scalarAt(0f) ?: 0f) * 100f).coerceIn(-100f, 100f)
        val tiles = scale.coerceIn(1f, 10f)
        return addFx(
            clip0, ctx, ClipFxLibrary.TILE,
            listOf(tiles, tiles, mirror, phase, phase, 0f, 0f),
            "tile"
        )
    }

    /**
     * `rgbsep` -> the app's RGB Split: `strength` 0..1 -> Amount, `angle` the drift direction. Alight
     * separates all three channels by mode; the app drifts red and blue apart, so the mapping is reported
     * as approximate.
     */
    private fun applyRgbSep(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        w += "Effect rgbsep is mapped to RGB Split approximately: its channel mode is not reproduced"
        val strength = props["strength"]?.firstValue(w, "rgbsep", "strength") ?: 0f
        val angle = ((props["angle"]?.scalarAt(0f) ?: 0f) % 360f + 360f) % 360f
        return addFx(
            clip0, ctx, ClipFxLibrary.RGB_SPLIT,
            listOf((strength * 100f).coerceIn(0f, 100f), angle, 0f),
            "rgbsep"
        )
    }

    /**
     * `hueshift`: a constant shift becomes the app's Hue / Saturation effect. A keyframed shift on a layer
     * with a plain colour of its own (a solid-filled shape, a text layer) is baked into the FILL_COLOR
     * keyframes instead — an HSV hue rotation of the layer's colour by the shift at every keyframe time —
     * because the fx model cannot animate. Anywhere else (a group, a gradient or picture fill) the first
     * value is used with a note.
     */
    private fun applyHueShift(fx: Element, clip0: TimelineClip, ctx: Ctx, durMs: Long, target: FxTarget): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val hueT = props["hue"] ?: return clip0
        if (hueT.keys.size < 2) {
            val wrapped = wrapHue(hueT.scalarAt(0f))
            if (abs(wrapped) < 0.01f) return clip0
            return addFx(clip0, ctx, ClipFxLibrary.HUE_SAT, listOf(wrapped, 0f, 0f, 0f), "hueshift")
        }
        val bakeable = target == FxTarget.TEXT || (target == FxTarget.SHAPE && clip0.fillMode == FILL_SOLID)
        if (!bakeable) {
            val wrapped = wrapHue(hueT.scalarAt(0f))
            w += "Effect hueshift: keyframed hue is not supported yet: the first value is used"
            if (abs(wrapped) < 0.01f) return clip0
            return addFx(clip0, ctx, ClipFxLibrary.HUE_SAT, listOf(wrapped, 0f, 0f, 0f), "hueshift")
        }
        val baseKeys = clip0.keyframes[AnimProp.FILL_COLOR]
        val times = ((hueT.keys.map { (it.t.coerceIn(0f, 1f) * durMs).roundToLong() }) +
            (baseKeys?.map { it.timeMs } ?: emptyList()) + listOf(0L, durMs)).distinct().sorted()
        val keys = times.mapIndexed { i, t ->
            val n = if (durMs > 0L) t.toFloat() / durMs else 0f
            val basePacked = baseKeys?.colorValueAt(t) ?: packColor(clip0.textColor)
            val rotated = hueRotate(unpacked(listOf(basePacked.first, basePacked.second)), hueT.scalarAt(n))
            val pk = packColor(rotated)
            val next = times.getOrNull(i + 1)
            val e = if (next == null) Easing.LINEAR else hueT.easingAtMs(next, durMs) ?: Easing.LINEAR
            Keyframe(t, pk.first, pk.second, e)
        }.distinctBy { it.timeMs }
        val first = keys.firstOrNull()
        w += "Effect hueshift is baked into the layer's colour keyframes"
        return clip0.copy(
            textColor = if (first != null) unpacked(listOf(first.a, first.b)) else clip0.textColor,
            keyframes = clip0.keyframes + (AnimProp.FILL_COLOR to keys)
        )
    }

    /** A hue angle wrapped into the app's -180..180 range. */
    private fun wrapHue(deg: Float): Float {
        var h = deg % 360f
        if (h > 180f) h -= 360f
        if (h < -180f) h += 360f
        return h
    }

    /** Rotates the hue of an ARGB colour by [deg] degrees (HSV), keeping alpha, saturation and value. */
    private fun hueRotate(argb: Long, deg: Float): Long {
        val a = (argb shr 24) and 0xFF
        val r = ((argb shr 16) and 0xFF).toFloat() / 255f
        val g = ((argb shr 8) and 0xFF).toFloat() / 255f
        val b = (argb and 0xFF).toFloat() / 255f
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val d = mx - mn
        if (d < 1e-6f || mx < 1e-6f) return argb
        var h = when (mx) {
            r -> ((g - b) / d) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } * 60f
        if (h < 0f) h += 360f
        h = (h + deg) % 360f
        if (h < 0f) h += 360f
        val s = d / mx
        val v = mx
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (rr, gg, bb) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(value: Float): Long = ((value + m) * 255f + 0.5f).toInt().coerceIn(0, 255).toLong()
        return (a shl 24) or (ch(rr) shl 16) or (ch(gg) shl 8) or ch(bb)
    }

    /** `satvib` -> Hue / Saturation: saturation 0..1 -> %, vibrance is 1-based in Alight, 0-based here. */
    private fun applySatVib(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val sat = props["saturation"]?.firstValue(w, "satvib", "saturation") ?: 0f
        val vib = props["vibrance"]?.firstValue(w, "satvib", "vibrance") ?: 1f
        return addFx(
            clip0, ctx, ClipFxLibrary.HUE_SAT,
            listOf(0f, (sat * 100f).coerceIn(-100f, 100f), 0f, ((vib - 1f) * 100f).coerceIn(-100f, 100f)),
            "satvib"
        )
    }

    /** `brightcont2` -> Brightness & Contrast, both 0..1 in Alight, -100..100 in the app. */
    private fun applyBrightCont(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val b = props["brightness"]?.firstValue(w, "brightcont", "brightness") ?: 0f
        val c = props["contrast"]?.firstValue(w, "brightcont", "contrast") ?: 0f
        return addFx(
            clip0, ctx, ClipFxLibrary.BRIGHT_CONTRAST,
            listOf((b * 100f).coerceIn(-100f, 100f), (c * 100f).coerceIn(-100f, 100f)),
            "brightcont"
        )
    }

    /**
     * `invert` -> the app's Invert when every channel is inverted (the only case in the samples, and the
     * app's own mode). Inverting single channels has no counterpart and is reported; inverting nothing is
     * a no-op.
     */
    private fun applyInvert(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        fun flag(name: String): Boolean {
            val t = props[name] ?: return true   // an absent channel flag means the default: inverted
            return t.scalarAt(0f) > 0.5f
        }
        val r = flag("red")
        val g = flag("green")
        val b = flag("blue")
        if (r && g && b) return addFx(clip0, ctx, ClipFxLibrary.INVERT, listOf(100f, 0f), "invert")
        if (!r && !g && !b) return clip0
        w += "Effect invert: inverting single channels is not supported yet: skipped"
        return clip0
    }

    /**
     * `vignette` -> the app's Vignette: strength -> Amount, scale -> Size, feather -> Softness (all %).
     * The punch-out mode, a colour tint and a non-black overlay colour are not reproduced.
     */
    private fun applyVignette(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val aspects = ArrayList<String>()
        if ((props["punchout"]?.scalarAt(0f) ?: 0f) > 0.5f) aspects += "its punch-out"
        if (abs(props["tint"]?.scalarAt(0f) ?: 0f) > 1e-4f) aspects += "its tint"
        val overlay = props["overlaycolor"]?.let { unpacked(it.first()) } ?: 0xFF000000L
        if (overlay != 0xFF000000L) aspects += "its overlay colour"
        if (aspects.isNotEmpty()) {
            w += "Effect vignette is mapped approximately: " + aspects.joinToString(", ") +
                if (aspects.size == 1) " is not reproduced" else " are not reproduced"
        }
        val strength = props["strength"]?.firstValue(w, "vignette", "strength") ?: 0f
        val scale = props["scale"]?.firstValue(w, "vignette", "scale") ?: 1f
        val feather = props["feather"]?.firstValue(w, "vignette", "feather") ?: 0f
        return addFx(
            clip0, ctx, ClipFxLibrary.VIGNETTE,
            listOf(
                (strength * 100f).coerceIn(0f, 100f),
                (scale * 100f).coerceIn(0f, 100f),
                (feather * 100f).coerceIn(0f, 100f)
            ),
            "vignette"
        )
    }

    /** `zoomblur2` / `zoomblur3` -> Radial Blur in its zoom mode, around the centre (Alight zooms from the layer centre). */
    private fun applyZoomBlur(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val short = fx.getAttribute("id").substringAfterLast('.')
        val props = fxProps(fx)
        val strength = props["strength"]?.firstValue(w, short, "strength") ?: 0f
        return addFx(
            clip0, ctx, ClipFxLibrary.RADIAL_BLUR,
            listOf((strength * 100f).coerceIn(0f, 100f), 100f, 50f, 50f),
            short
        )
    }

    /** `dblur` -> Directional Blur: its angle and strength; the app's comet trail stays off. */
    private fun applyDirectionalBlur(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val angle = ((props["angle"]?.scalarAt(0f) ?: 0f) % 360f + 360f) % 360f
        val strength = props["strength"]?.firstValue(w, "dblur", "strength") ?: 0f
        return addFx(
            clip0, ctx, ClipFxLibrary.DIR_BLUR,
            listOf(angle, (strength * 100f).coerceIn(0f, 100f), 0f),
            "dblur"
        )
    }

    /**
     * `repeat` -> the app's Repeat effect (see [RepeatEval]): count copies, each shifted by `offset`
     * (scene px -> preview px, like every position), turned by `angle`, scaled by `scale`, faded by
     * `alpha`. The corpus only pins count + offset (every instance has angle 0, scale 1, alpha 1,
     * time 0); a non-zero `time` (a per-copy time shift) has no counterpart in the draw loops and
     * is reported, not guessed. Keyframed parameters take their first value (the pass-2 rule).
     * A repeat on a video / photo / group layer is stored but not drawn repeated yet: the overlay
     * loops expand shape / text / sticker layers only, so that case is named too.
     */
    private fun applyRepeat(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val count = (props["count"]?.firstValue(w, "repeat", "count") ?: 1f).roundToInt()
            .coerceIn(1, RepeatEval.MAX_COUNT).toFloat()
        val offset = props["offset"]?.let {
            if (it.keys.size >= 2) w += "Effect repeat: keyframed offset is not supported yet: the first value is used"
            it.first()
        } ?: listOf(0f, 0f)
        val angle = props["angle"]?.firstValue(w, "repeat", "angle") ?: 0f
        val scale = props["scale"]?.firstValue(w, "repeat", "scale") ?: 1f
        val alpha = props["alpha"]?.firstValue(w, "repeat", "alpha") ?: 1f
        if ((props["time"]?.scalarAt(0f) ?: 0f) != 0f) {
            w += "Effect repeat is mapped approximately: its time offset is not reproduced"
        }
        if (clip0.type == LayerType.VIDEO || clip0.type == LayerType.GROUP) {
            w += "Effect repeat on a video / group layer is stored but not drawn repeated yet"
        }
        return addFx(
            clip0, ctx, ClipFxLibrary.REPEAT,
            listOf(
                count,
                (offset.getOrNull(0) ?: 0f) * ctx.k,
                (offset.getOrNull(1) ?: 0f) * ctx.k,
                angle,
                (scale * 100f).coerceIn(0f, 200f),
                (alpha * 100f).coerceIn(0f, 100f)
            ),
            "repeat"
        )
    }

    /**
     * `motionblur` / `motionblur2` / `motionblur4` -> the app's Motion Blur (see MotionBlurEval for
     * the per-frame maths: a directional blur along the layer's position velocity plus a radial
     * blur around the layer centre for its scale / rotation velocity, `tune` as the strength).
     * The three `use*` flags are stored as values[1..3] so the resolver only blurs the kinds
     * Alight blurs; a missing flag is read as off, except `usePos` (an effect with no flags listed
     * is just "motion blur"). An effect with all three flags off is skipped.
     */
    private fun applyMotionBlur(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val short = fx.getAttribute("id").substringAfterLast('.')
        val props = fxProps(fx)
        val tune = props["tune"]?.firstValue(w, short, "tune") ?: 1f
        val usePos = (props["usePos"]?.scalarAt(0f) ?: 1f) >= 0.5f
        val useScale = (props["useScale"]?.scalarAt(0f) ?: 0f) >= 0.5f
        val useAngle = (props["useAngle"]?.scalarAt(0f) ?: 0f) >= 0.5f
        if (!usePos && !useScale && !useAngle) {
            w += "Effect " + short + " is not supported yet: all of usePos / useScale / useAngle are off: skipped"
            return clip0
        }
        val kinds = listOfNotNull(
            if (usePos) "a directional blur along the layer's position motion" else null,
            if (useScale || useAngle) "a radial blur for its " +
                listOfNotNull(if (useScale) "scale" else null, if (useAngle) "rotation" else null)
                    .joinToString(" and ") + " motion" else null
        )
        w += "Effect " + short + " is mapped approximately as " + kinds.joinToString(" and ")
        return addFx(
            clip0, ctx, ClipFxLibrary.MOTION_BLUR,
            listOf(
                (tune * 100f).coerceIn(0f, 100f),
                if (usePos) 1f else 0f, if (useScale) 1f else 0f, if (useAngle) 1f else 0f
            ),
            short
        )
    }

    /**
     * `lensblur2` -> the app's Lens Blur: radius 0..1 -> 0..100. Alight's `strength` (how strongly the
     * highlights bloom) has no single counterpart; the app's own highlight defaults are used and the
     * difference is reported.
     */
    private fun applyLensBlur(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        w += "Effect lensblur2 is mapped approximately: its strength is not reproduced"
        val radius = props["radius"]?.firstValue(w, "lensblur2", "radius") ?: 0f
        return addFx(
            clip0, ctx, ClipFxLibrary.LENS_BLUR,
            listOf((radius * 100f).coerceIn(0f, 100f), 40f, 70f),
            "lensblur2"
        )
    }

    /** `sharpen` -> the app's Sharpen: strength -> Amount, radius (Alight's is ~0..10) -> the app's 0..100. */
    private fun applySharpenFx(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        w += "Effect sharpen is mapped approximately"
        val strength = props["strength"]?.firstValue(w, "sharpen", "strength") ?: 0f
        val radius = props["radius"]?.scalarAt(0f) ?: 0f
        return addFx(
            clip0, ctx, ClipFxLibrary.SHARPEN,
            listOf((strength * 100f).coerceIn(0f, 300f), (radius * 10f).coerceIn(0f, 100f), 0f, 40f),
            "sharpen"
        )
    }

    /**
     * `pinchbulge` / `pinchbulge2` -> the app's Bulge: a positive strength bulges in both apps, the radius
     * (0..1 of the frame in the samples) becomes a percent, around the centre. Alight's falloff curve
     * differs from the app's, so the mapping is reported as approximate.
     */
    private fun applyPinchBulge(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val short = fx.getAttribute("id").substringAfterLast('.')
        val props = fxProps(fx)
        w += "Effect " + short + " is mapped approximately"
        val strength = props["strength"]?.firstValue(w, short, "strength") ?: 0f
        val radius = props["radius"]?.scalarAt(0f) ?: 0f
        val radiusPct = if (radius <= 1f) radius * 100f else 100f
        return addFx(
            clip0, ctx, ClipFxLibrary.BULGE,
            listOf((strength * 100f).coerceIn(-100f, 100f), radiusPct.coerceIn(0f, 100f), 50f, 50f),
            short
        )
    }

    /**
     * `pulsate2`: the layer's scale pulses between `minsize` and `maxsize` (multipliers of its own scale)
     * at `freq` Hz — mid + amp * sin(phase), composed multiplicatively into the SCALE keyframes exactly
     * like scaleassist. `type` 0 is the sine (the only waveform in the samples). A steady pulsate (min ==
     * max) is just a constant multiplier.
     */
    private fun applyPulsate(fx: Element, clip0: TimelineClip, ctx: Ctx, durMs: Long): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val typeV = props["type"]?.scalarAt(0f)?.toInt() ?: 0
        if (typeV != 0) w += "Effect pulsate: waveform type " + typeV + " is not supported yet: a sine wave is used"
        val maxT = props["maxsize"] ?: return clip0
        val minT = props["minsize"]
        fun minAt(n: Float) = minT?.scalarAt(n) ?: 1f
        var ampMax = 0f
        for (i in 0..16) {
            val n = i / 16f
            ampMax = maxOf(ampMax, abs(maxT.scalarAt(n) - minAt(n)) / 2f)
        }
        if (ampMax < 1e-4f) {
            val mid = (maxT.scalarAt(0f) + minAt(0f)) / 2f
            if (abs(mid - 1f) <= 1e-4f) return clip0
            return composeScaleMultiplier(clip0, Track(listOf(mid), emptyList()), durMs)
        }
        if (durMs <= 0L) return clip0
        val freqT = props["freq"]
        var maxFreq = 0f
        for (i in 0..16) maxFreq = maxOf(maxFreq, abs(freqT?.scalarAt(i / 16f) ?: 0f))
        val baseKeys = clip0.keyframes[AnimProp.SCALE]
        val extras = (maxT.keys + (minT?.keys ?: emptyList()) + (freqT?.keys ?: emptyList()))
            .map { (it.t.coerceIn(0f, 1f) * durMs).roundToLong() } + (baseKeys?.map { it.timeMs } ?: emptyList())
        val times = sampleTimes(durMs, ctx.fps, maxFreq, extras)
        var phase = (props["phase"]?.scalarAt(0f) ?: 0f).toDouble() * 2.0 * Math.PI
        var prevMs = 0L
        var factor0 = 1f
        val raw = ArrayList<Keyframe>(times.size)
        for (t in times) {
            if (t > prevMs) {
                val midNorm = ((prevMs + t) / 2f) / durMs
                phase += 2.0 * Math.PI * (freqT?.scalarAt(midNorm) ?: 0f) * (t - prevMs) / 1000.0
            }
            prevMs = t
            val n = t.toFloat() / durMs
            val mid = (maxT.scalarAt(n) + minAt(n)) / 2f
            val amp = (maxT.scalarAt(n) - minAt(n)) / 2f
            val f = mid + amp * sin(phase).toFloat()
            if (t == 0L) factor0 = f
            val base = baseKeys?.valueAt(t)?.first ?: clip0.scalePercent
            raw += Keyframe(t, base * f, 0f, Easing.LINEAR)
        }
        val keys = simplifyKeys(raw, 0.05f, 0.05f)
        val firstA = keys.firstOrNull()?.a ?: clip0.scalePercent
        var clip = clip0.copy(scalePercent = firstA, keyframes = clip0.keyframes + (AnimProp.SCALE to keys))
        if (clip0.type == LayerType.SHAPE) {
            clip = clip.copy(shapeScaleX = firstA / 100f, shapeScaleY = clip0.shapeScaleY * factor0)
        }
        return clip
    }

    /**
     * The `transform` effect: an extra transform stacked on the layer's own — its `scale` composes
     * multiplicatively like scaleassist, its `angle` adds onto the rotation, its `alpha` multiplies the
     * opacity. Its picture-fill modes (`fill`, `maskToLayer`) have no counterpart and are reported.
     */
    private fun applyTransformFx(fx: Element, clip0: TimelineClip, ctx: Ctx, durMs: Long): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        var clip = clip0
        props["scale"]?.let { clip = composeScaleMultiplier(clip, it, durMs) }
        props["angle"]?.let { angleT ->
            clip = if (angleT.keys.size >= 2) {
                val baseKeys = clip.keyframes[AnimProp.ROTATION]
                val times = ((angleT.keys.map { (it.t.coerceIn(0f, 1f) * durMs).roundToLong() }) +
                    (baseKeys?.map { it.timeMs } ?: emptyList()) + listOf(0L, durMs)).distinct().sorted()
                val keys = times.mapIndexed { i, t ->
                    val n = if (durMs > 0L) t.toFloat() / durMs else 0f
                    val base = baseKeys?.valueAt(t)?.first ?: clip.rotationDeg
                    val next = times.getOrNull(i + 1)
                    val e = if (next == null) Easing.LINEAR else angleT.easingAtMs(next, durMs) ?: Easing.LINEAR
                    Keyframe(t, base + angleT.scalarAt(n), 0f, e)
                }.distinctBy { it.timeMs }
                clip.copy(
                    rotationDeg = keys.firstOrNull()?.a ?: clip.rotationDeg,
                    keyframes = clip.keyframes + (AnimProp.ROTATION to keys)
                )
            } else {
                val a = angleT.scalarAt(0f)
                if (abs(a) <= 1e-4f) clip else {
                    var c2 = clip.copy(rotationDeg = clip.rotationDeg + a)
                    val bk = clip.keyframes[AnimProp.ROTATION]
                    if (bk != null) {
                        c2 = c2.copy(keyframes = c2.keyframes + (AnimProp.ROTATION to bk.map { it.copy(a = it.a + a) }))
                    }
                    c2
                }
            }
        }
        props["alpha"]?.let { clip = composeAlpha(clip, it, durMs) }
        val fill = props["fill"]?.scalarAt(0f)?.toInt() ?: 0
        if (fill != 0) w += "Effect transform: its fill mode is not reproduced"
        if ((props["maskToLayer"]?.scalarAt(0f) ?: 0f) > 0.5f) {
            w += "Effect transform: mask-to-layer is not reproduced"
        }
        return clip
    }

    /**
     * `lift`: in every exported sample the effect carries nothing but `fill` = 0 (no lift amount at all),
     * so there is nothing to map; an export that actually lifts (a fill or any other property) is reported.
     */
    private fun applyLift(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val props = fxProps(fx)
        val fill = props["fill"]?.scalarAt(0f) ?: 0f
        if (abs(fill) > 1e-4f || props.keys.any { it != "fill" }) {
            ctx.warnings += "Effect lift is not supported yet: skipped"
        }
        return clip0
    }

    // ---- transform + values -------------------------------------------------------------------------------------

    private class TransformTracks(val loc: Track?, val scl: Track?, val rot: Track?, val opa: Track?, val pivot: Track?)

    /** The `<transform>` of a layer / embedScene. [pivotUsed] = the caller maps the pivot itself; otherwise a present pivot is reported like the old importer did. */
    private fun readTransform(el: Element, w: MutableList<String>, pivotUsed: Boolean): TransformTracks {
        val tr = elementChildren(el).firstOrNull { it.tagName == "transform" }
            ?: return TransformTracks(null, null, null, null, null)
        val pivot = child(tr, "pivot")?.let { readTrack(it) }
        if (pivot != null && !pivotUsed) w += "Transform <pivot> is not supported yet: ignored"
        val known = setOf("location", "scale", "rotation", "opacity", "pivot")
        elementChildren(tr).map { it.tagName }.filter { it !in known }.distinct()
            .forEach { w += "Transform <" + it + "> is not supported yet: ignored" }
        return TransformTracks(
            child(tr, "location")?.let { readTrack(it) },
            child(tr, "scale")?.let { readTrack(it) },
            child(tr, "rotation")?.let { readTrack(it) },
            child(tr, "opacity")?.let { readTrack(it) },
            pivot
        )
    }

    /**
     * A value that is either constant (`value="a,b"`) or animated (`<kf t v e>` children).
     * The easing of a keyframe belongs to the segment ENDING at it (see the class comment).
     */
    private class TKey(val t: Float, val v: List<Float>, val easing: Easing?)

    private class Track(val constant: List<Float>, val keys: List<TKey>) {
        fun first(): List<Float> = if (keys.isNotEmpty()) keys.first().v else constant
        fun keyAt(t: Float): TKey? = keys.firstOrNull { abs(it.t - t) < 1e-6f }

        /** The value at normalized time [t]: the constant, or blended linearly between the keyframes around it. */
        fun valueAt(t: Float): List<Float> {
            if (keys.isEmpty()) return constant
            if (t <= keys.first().t) return keys.first().v
            if (t >= keys.last().t) return keys.last().v
            var i = 0
            while (i < keys.size - 2 && keys[i + 1].t <= t) i++
            val a = keys[i]
            val b = keys[i + 1]
            val span = (b.t - a.t).coerceAtLeast(1e-6f)
            val f = ((t - a.t) / span).coerceIn(0f, 1f)
            return List(maxOf(a.v.size, b.v.size)) { j ->
                val va = a.v.getOrElse(j) { a.v.lastOrNull() ?: 0f }
                val vb = b.v.getOrElse(j) { b.v.lastOrNull() ?: 0f }
                va + (vb - va) * f
            }
        }
    }

    private fun readTrack(el: Element, type: String = ""): Track {
        val ty = type.ifEmpty { el.getAttribute("type") }
        val keys = elementChildren(el).filter { it.tagName == "kf" }.mapNotNull { kf ->
            val t = kf.getAttribute("t").toFloatOrNull() ?: return@mapNotNull null
            val v = parseValue(kf.getAttribute("v"), ty) ?: return@mapNotNull null
            TKey(t, v, parseEasing(kf.getAttribute("e")))
        }.sortedBy { it.t }
        val constant = parseValue(el.getAttribute("value"), ty) ?: emptyList()
        return Track(constant, keys)
    }

    /** "cubicBezier x1 y1 x2 y2" -> the app's easing; anything else (or nothing) -> null. */
    internal fun parseEasing(s: String): Easing? {
        val parts = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size != 5 || !parts[0].startsWith("cubicBezier")) return null
        val n = parts.drop(1).map { it.toFloatOrNull() ?: return null }
        return Easing(n[0], n[1], n[2], n[3])
    }

    /**
     * A track -> the app's keyframes of one property. The easing of a segment comes from the Alight keyframe the
     * segment ends at, so each keyframe takes the easing of the NEXT Alight keyframe.
     */
    private fun trackKeys(t: Track?, durMs: Long, map: (List<Float>) -> Pair<Float, Float>): List<Keyframe> {
        if (t == null || t.keys.size < 2) return emptyList()
        return t.keys.mapIndexed { i, k ->
            val (a, b) = map(k.v)
            val e = t.keys.getOrNull(i + 1)?.easing ?: Easing.LINEAR
            Keyframe((k.t.coerceIn(0f, 1f) * durMs).roundToLong(), a, b, e)
        }.distinctBy { it.timeMs }
    }

    private fun parseValue(s: String, type: String): List<Float>? {
        val v = s.trim()
        if (v.isEmpty()) return null
        return when (type) {
            "bool" -> listOf(if (v == "true" || v == "1") 1f else 0f)
            "color" -> parseColor(v)?.let { c -> packColor(c).let { listOf(it.first, it.second) } }
            else -> {
                val nums = v.split(',').map { it.trim().toFloatOrNull() ?: return null }
                nums
            }
        }
    }

    /** "#aarrggbb" or "#rrggbb" -> ARGB Long. */
    internal fun parseColor(s: String): Long? {
        val h = s.trim().removePrefix("#")
        if (h.isEmpty() || h.any { !it.isLetterOrDigit() }) return null
        val n = h.toLongOrNull(16) ?: return null
        return when (h.length) { 8 -> n; 6 -> 0xFF000000L or n; else -> null }
    }

    /** `googlefonts?name=Roboto&weight=900` -> (family the app has, weight). */
    internal fun parseFont(font: String, warnings: MutableList<String>): Pair<String, Int> {
        val q = font.substringAfter('?', "")
        val parts = q.split('&').mapNotNull { p ->
            val i = p.indexOf('=')
            if (i <= 0) null else p.substring(0, i) to p.substring(i + 1).replace('+', ' ')
        }.toMap()
        val name = parts["name"].orEmpty()
        val weight = (parts["weight"]?.toIntOrNull() ?: 600).coerceIn(100, 900)
        val family = if (name in KNOWN_FAMILIES) name else {
            if (name.isNotEmpty()) warnings += "Font \"" + name + "\" is not in the app: Roboto is used"
            "Roboto"
        }
        return family to weight
    }

    // ---- DOM helpers --------------------------------------------------------------------------------------------

    private fun elementChildren(e: Element): List<Element> {
        val out = ArrayList<Element>()
        var n: Node? = e.firstChild
        while (n != null) { if (n is Element) out += n; n = n.nextSibling }
        return out
    }

    private fun child(e: Element, tag: String): Element? = elementChildren(e).firstOrNull { it.tagName == tag }

    private fun labelOf(e: Element): String = e.getAttribute("label").let { if (it.isEmpty()) "" else " \"" + it + "\"" }

    private fun InputStream.readNBytesLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}
