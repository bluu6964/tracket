package com.example.engine

import com.example.model.AnimKey
import com.example.model.AnimProp
import com.example.model.AnimValue
import com.example.model.AspectRatio
import com.example.model.Easing
import com.example.model.Keyframe
import com.example.model.LaneLayout
import com.example.model.LayerType
import com.example.model.ProjectSnapshot
import com.example.model.TextAnimator
import com.example.model.TimelineClip
import com.example.model.packColor
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.InputStream
import java.io.StringReader
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Alight Motion XML (`<scene> ... </scene>`) -> a new After Motion project. Pure Kotlin (no Android classes).
 *
 * Step 1 of the import (what is mapped):
 *  - scene: title -> project name, width / height -> canvas shape, bgcolor -> canvas colour
 *  - `<text>` layer: start / end time, content, font weight, align, colour, size, location / scale / rotation / opacity
 *    (a constant or `<kf>` keyframes), `texttransform` + `randomdisplace` effects stored as [TextAnimator]
 *  - everything else (other layer types, other effects) is skipped and listed in [ImportResult.warnings]
 *
 * Rules that were read from the sample files (NOT from the Alight documentation, so check them on a real export):
 *  - `<kf t>` of an effect property is NORMALIZED: 0..1 of the layer's length
 *  - `location` is in scene pixels from the top-left corner; the app stores the move from the canvas centre in PREVIEW
 *    pixels (see PreviewFrameRef), so it is scaled by previewWidthPx / sceneWidth
 *  - Alight text `size` * `scale` is the font height in scene pixels; the app's text is 0.11 * canvas width at 100 %
 *  - in the XML, later layers are on top
 */
object AlightXmlImporter {

    const val MAX_BYTES = 5 * 1024 * 1024
    const val TEXTTRANSFORM = "com.alightcreative.effects.texttransform"
    const val RANDOMDISPLACE = "com.alightcreative.effects.randomdisplace"

    private val KNOWN_FAMILIES = setOf("Roboto", "Serif", "Monospace", "Cursive")
    private const val APP_FONT_FRACTION = 0.11f

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
        val pw = if (previewWidthPx > 1f) previewWidthPx else sceneW   // unknown preview width: keep scene pixels

        val clips = ArrayList<TimelineClip>()
        val ctx = Ctx(sceneW, sceneH, pw, totalMs, warnings)
        for (el in elementChildren(root)) {
            when (el.tagName) {
                "text" -> clips += readText(el, ctx)
                else -> warnings += "Layer <" + el.tagName + ">" + labelOf(el) + " is not supported yet: skipped"
            }
        }
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

    private class Ctx(val sceneW: Float, val sceneH: Float, val previewW: Float, val totalMs: Long, val warnings: MutableList<String>) {
        val k: Float get() = previewW / sceneW
    }

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
        val tr = elementChildren(el).firstOrNull { it.tagName == "transform" }
        val loc = tr?.let { child(it, "location") }?.let { readTrack(it) }
        val scl = tr?.let { child(it, "scale") }?.let { readTrack(it) }
        val rot = tr?.let { child(it, "rotation") }?.let { readTrack(it) }
        val opa = (tr?.let { child(it, "opacity") } ?: child(el, "opacity"))?.let { readTrack(it) }
        tr?.let { t ->
            val known = setOf("location", "scale", "rotation", "opacity")
            elementChildren(t).map { it.tagName }.filter { it !in known }.distinct()
                .forEach { w += "Transform <" + it + "> is not supported yet: ignored" }
        }

        val fontBase = APP_FONT_FRACTION * ctx.sceneW
        fun scalePct(v: List<Float>): Float {
            if (v.size >= 2 && abs(v[0] - v[1]) > 1e-3f) w += "Non-uniform scale: only the horizontal scale is used"
            return size * (v.firstOrNull() ?: 1f) / fontBase * 100f
        }
        fun posX(v: List<Float>) = ((v.getOrNull(0) ?: ctx.sceneW / 2f) - ctx.sceneW / 2f) * ctx.k
        fun posY(v: List<Float>) = ((v.getOrNull(1) ?: ctx.sceneH / 2f) - ctx.sceneH / 2f) * ctx.k

        val keyframes = LinkedHashMap<AnimProp, List<Keyframe>>()
        fun keys(prop: AnimProp, t: Track?, map: (List<Float>) -> Pair<Float, Float>) {
            if (t == null || t.keys.size < 2) return
            keyframes[prop] = t.keys.map { k ->
                val (a, b) = map(k.v)
                Keyframe((k.t.coerceIn(0f, 1f) * durMs).roundToLong(), a, b, Easing.LINEAR)
            }.distinctBy { it.timeMs }
        }
        keys(AnimProp.POSITION, loc) { posX(it) to posY(it) }
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
            if (id == TEXTTRANSFORM || id == RANDOMDISPLACE) animators += readAnimator(fx, id, size)
            else w += "Effect " + id.substringAfterLast('.') + " is not supported yet: skipped"
        }
        if (animators.any { it.effectId == RANDOMDISPLACE }) {
            w += "Effect randomdisplace is saved with the layer but not drawn yet"
        }
        if (animators.count { it.effectId == TEXTTRANSFORM } > 1) {
            w += "Only the first texttransform of a layer is drawn"
        }

        return TimelineClip(
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
            transformX = posX(locV ?: emptyList()),
            transformY = posY(locV ?: emptyList()),
            rotationDeg = rotV?.firstOrNull() ?: 0f,
            opacity = ((opaV?.firstOrNull() ?: 1f) * 100f).coerceIn(0f, 100f),
            keyframes = keyframes,
            textAnimators = animators
        )
    }

    private fun readAnimator(fx: Element, id: String, refSize: Float): TextAnimator {
        val props = LinkedHashMap<String, AnimValue>()
        for (p in elementChildren(fx).filter { it.tagName == "property" }) {
            val n = p.getAttribute("name")
            if (n.isEmpty()) continue
            val type = p.getAttribute("type").ifEmpty { "float" }
            val t = readTrack(p, type)
            props[n] = AnimValue(type, if (t.keys.isEmpty()) t.constant else emptyList(), t.keys)
        }
        return TextAnimator(id, props, refSize)
    }

    // ---- values -------------------------------------------------------------------------------------------------

    /** A value that is either constant (`value="a,b"`) or animated (`<kf t v>` children). */
    private class Track(val constant: List<Float>, val keys: List<AnimKey>) {
        fun first(): List<Float> = if (keys.isNotEmpty()) keys.first().v else constant
    }

    private fun readTrack(el: Element, type: String = ""): Track {
        val ty = type.ifEmpty { el.getAttribute("type") }
        val keys = elementChildren(el).filter { it.tagName == "kf" }.mapNotNull { kf ->
            val t = kf.getAttribute("t").toFloatOrNull() ?: return@mapNotNull null
            val v = parseValue(kf.getAttribute("v"), ty) ?: return@mapNotNull null
            AnimKey(t, v)
        }.sortedBy { it.t }
        val constant = parseValue(el.getAttribute("value"), ty) ?: emptyList()
        return Track(constant, keys)
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
