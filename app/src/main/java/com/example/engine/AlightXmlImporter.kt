package com.example.engine

import com.example.model.AnimKey
import com.example.model.AnimProp
import com.example.model.AnimValue
import com.example.model.AspectRatio
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
import com.example.model.ParentLink
import com.example.model.ProjectSnapshot
import com.example.model.TextAnimator
import com.example.model.TimelineClip
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
import kotlin.math.hypot
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
 *    `size` x transform scale -> the shape's size, position / rotation / opacity, a `path-stroke` -> stroke
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
 *    keyframes. `textprogress` is stored as a [TextAnimator] like the other text effects (not drawn yet).
 *    `drawing.progress` has no stroke-trim in the app model, so it stays a warning.
 *  - everything else (other layer types, other effects) is skipped and listed in [ImportResult.warnings]
 *
 * Rules that were read from the sample files (NOT from the Alight documentation, so check them on a real export):
 *  - `<kf t>` is NORMALIZED: 0..1 of the layer's length (for an `<embedScene>`, of the embed's length)
 *  - the easing `e="cubicBezier x1 y1 x2 y2"` sits on the keyframe a segment ENDS at (the first keyframe of a
 *    track never has one, the last one usually does); the app stores the easing on the keyframe a segment
 *    STARTS at, so every easing moves one keyframe back when a track is converted
 *  - `location` is in scene pixels from the top-left corner; the app stores the move from the canvas centre in PREVIEW
 *    pixels (see PreviewFrameRef), so it is scaled by previewWidthPx / sceneWidth
 *  - Alight text `size` * `scale` is the font height in scene pixels; the app's text is 0.11 * canvas width at 100 %
 *  - a shape's rendered size is `size` * transform `scale` in scene pixels. The app draws a shape in a 130 dp box;
 *    the canvas is taken as 360 dp wide (the usual phone canvas: 1080 px at density 3), so the box is
 *    sceneWidth * 130 / 360 scene pixels, and a stroke width in scene pixels becomes size * 360 / sceneWidth dp
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

    /** Effect ids the text reader stores as [TextAnimator] itself (the effect pass must not warn about them). */
    private val TEXT_STORED = setOf(TEXTTRANSFORM, RANDOMDISPLACE, TEXTPROGRESS)

    private val KNOWN_FAMILIES = setOf("Roboto", "Serif", "Monospace", "Cursive")
    private const val APP_FONT_FRACTION = 0.11f

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
                "embedScene" -> readGroup(el, ctx)?.let { (block, group) -> flat += block; direct += group }
                else -> ctx.warnings += "Layer <" + el.tagName + ">" + labelOf(el) + " is not supported yet: skipped"
            }
        }
        return SceneBlock(flat, direct)
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

        val fontBase = APP_FONT_FRACTION * ctx.sceneW
        fun scalePct(v: List<Float>): Float {
            if (v.size >= 2 && abs(v[0] - v[1]) > 1e-3f) w += "Non-uniform scale: only the horizontal scale is used"
            return size * (v.firstOrNull() ?: 1f) / fontBase * 100f
        }

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
                animators += readAnimator(fx, id, size)
                if (id == TEXTPROGRESS) w += "Effect textprogress is saved with the layer but not drawn yet"
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

        // Stroke: <path-stroke><color/><size/></path-stroke>; a disabled one draws nothing.
        var strokeColor = 0xFFFFFFFFL
        var strokeWidth = 0f
        val strokeEl = child(el, "path-stroke")
        if (strokeEl != null && strokeEl.getAttribute("enabled") != "false") {
            strokeColor = child(strokeEl, "color")?.let { parseColor(it.getAttribute("value")) } ?: 0xFFFFFFFFL
            val sz = child(strokeEl, "size")?.getAttribute("value")?.toFloatOrNull() ?: 0f
            strokeWidth = sz * CANVAS_DP / ctx.sceneW
        }

        val sizeT = elementChildren(el).firstOrNull { it.tagName == "property" && it.getAttribute("name") == "size" }
            ?.let { readTrack(it) }
        val corner = elementChildren(el).firstOrNull { it.tagName == "property" && it.getAttribute("name") == "cornerRadius" }
            ?.getAttribute("value")?.toFloatOrNull()
        if (corner != null && pattern == "ROUNDED_RECT") {
            w += "Shape corner radius is not supported yet: the app's own rounded corner is used"
        }

        // Rendered size in scene pixels = size x transform scale; the app size is measured against its 130 dp box.
        val box = ctx.shapeBoxPx
        val sizeV = sizeT?.first() ?: listOf(100f, 100f)
        val sclV = tr.scl?.first() ?: listOf(1f, 1f)
        val rendW = (sizeV.getOrNull(0) ?: 100f) * (sclV.getOrNull(0) ?: 1f)
        val rendH = (sizeV.getOrNull(1) ?: sizeV.getOrNull(0) ?: 100f) * (sclV.getOrNull(1) ?: sclV.getOrNull(0) ?: 1f)

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

    /** SCALE keyframes of a shape: the rendered width (size x scale, X axis) against the app's shape box. */
    private fun composedSizeKeys(sizeT: Track?, scaleT: Track?, durMs: Long, box: Float): List<Keyframe> {
        val times = ((sizeT?.keys ?: emptyList()) + (scaleT?.keys ?: emptyList())).map { it.t }.distinct().sorted()
        if (times.size < 2) return emptyList()
        fun widthAt(t: Float): Float {
            val sz = sizeT?.valueAt(t) ?: listOf(100f, 100f)
            val sc = scaleT?.valueAt(t) ?: listOf(1f, 1f)
            return (sz.getOrNull(0) ?: 100f) * (sc.getOrNull(0) ?: 1f) / box * 100f
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
        return c.copy(startMs = ns, durationMs = nd, keyframes = nk)
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
                DRAWING_PROGRESS -> {
                    // The app model has no stroke trim / draw-on progress, so there is nothing to map to yet.
                    ctx.warnings += "Effect drawing.progress is not supported yet: skipped"
                    clip
                }
                SCALEASSIST -> applyScaleAssist(fx, clip, ctx, durMs)
                TEXTSPACING -> applyTextSpacing(fx, clip, ctx, target)
                OSCILLATE, OSCILLATE3 -> applyOscillate(fx, clip, ctx, durMs, id)
                SWING, SWING2 -> applySwing(fx, clip, ctx, durMs, id)
                else -> {
                    ctx.warnings += "Effect " + id.substringAfterLast('.') + " is not supported yet: skipped"
                    clip
                }
            }
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
