package com.example.engine

import com.example.model.AnimKey
import com.example.model.AnimProp
import com.example.model.AnimValue
import com.example.model.AspectRatio
import com.example.model.CalloutOutline
import com.example.model.ClipFx
import com.example.model.ClipFxLibrary
import com.example.model.ClipMask
import com.example.model.Easing
import com.example.model.FILL_GRADIENT
import com.example.model.FILL_IMAGE
import com.example.model.FILL_VIDEO
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
import com.example.model.PATH_OP_CLOSE
import com.example.model.PATH_OP_CUBIC
import com.example.model.PATH_OP_LINE
import com.example.model.PATH_OP_MOVE
import com.example.model.PATH_OP_QUAD
import com.example.model.PathSeg
import com.example.model.PieOutline
import com.example.model.ProjectSnapshot
import com.example.model.flattenPathSegments
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

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
 *    between their two points (the arrow head is not drawn). A shape with no `s` kind but a `<path>`
 *    child is a freehand vector layer: its `d` (M / L / H / V / C / S / Q / T / Z, absolute or relative)
 *    becomes the clip's stored outline ([com.example.model.TimelineClip.pathSegments]); an arc command
 *    or keyframed path data stays skipped with a warning. The parameterised kinds (`.multifoil`,
 *    `.triangle`, `.penta`, `.poly`, `.star`, `.pie`, `.callout.roundrect`) are line-by-line ports of
 *    AM's own shape definitions into the same stored outline.
 *  - `<embedScene>`: a real Group layer (see GroupOps) holding the layers of the nested `<scene>`; the group's
 *    move / scale / turn / opacity and the pivot (as the children's parent link) come from its own transform,
 *    and the nested times are remapped from the [inTime, outTime] window onto the group's length.
 *  - effects (pass 2): `solidcolor` -> the Solid Color effect (the picture flattened to one colour at
 *    render; keyframed: the colour also stored as FILL_COLOR keyframes for per-frame resolution) and its
 *    alpha multiplied into the opacity; `gradientoverlay2` -> the app's gradient fill (colours, type, and the
 *    def's exact angle / offset / scale geometry, build 120); `textspacing` -> letter spacing / line height of a text layer; `scaleassist`
 *    (axis 3) composed into the SCALE track; `oscillate` / `oscillate3` / `swing` / `swing2` are procedural, so
 *    they are BAKED into the POSITION / ROTATION keyframes at the scene fps, composed over the layer's own
 *    keyframes. `textprogress` is stored as a [TextAnimator] like the other text effects and DRAWN as a
 *    typewriter reveal ([TextProgressEval]: `start` / `end` are the shown window of the text, counted in
 *    grapheme clusters; the C++ text engine hides the clusters outside it, the Canvas fallback cuts the
 *    string). Only its typing `cursor` is not drawn — a value other than 0 (the corpus only has 0) warns.
 *    `drawing.progress` -> the clip's stroke trim: `start` / `end` (percent of the path) become fractions,
 *    keyframed on the STROKE_TRIM track; on a line the trim gates the fill (the line's paint is its path),
 *    on a text layer it draws the glyph outlines trimmed (`strokeTrimText`); on group layers and on
 *    shapes without a stroke it stays a warning.
 *  - `<audio>` layer (pass 3): an audio clip with its timing; `inTime` / `outTime` become the trim and
 *    `mediaFillMode` "stretch" a playback speed. The `src` is a content:// URI of the exporting phone;
 *    the import screen copies such files into the project ([AlightMediaLocalizer]) so they play — when
 *    that copy fails the clip stays silent until the file is picked again (a warning says so). The same
 *    copy applies to the picture fills of media shapes (`fillImage`).
 *  - effects (pass 3): `wipe2` -> a keyframeable rectangular [ClipMask] (the app has no wipe effect of its
 *    own); `gaussianblur` / `edgeglow` / `darkglow` / `exposure` / `tile` / `rgbsep` / `hueshift` /
 *    `satvib` / `brightcont2` / `invert` / `vignette` / `zoomblur2` / `zoomblur3` / `dblur` / `lensblur2` /
 *    `sharpen` / `pinchbulge` / `pinchbulge2` map onto the app's native [ClipFx] library with the closest
 *    parameters; `shake2` is baked into the POSITION keyframes as seeded value noise (the app's camera
 *    shake works in unrelated units); `flip3` -> the layer's 3D rotation tracks; `fade` -> the clip's fade
 *    in / out; `blink` / `blink2` and `pulsate2` are baked into the OPACITY / SCALE keyframes; the
 *    `transform` effect composes onto the layer's own tracks like `scaleassist`; a keyframed `hueshift` on
 *    a solid-filled layer is baked into its colour keyframes; `movealongpath3` is baked into the
 *    POSITION / ROTATION keyframes (see [bakeMoveAlongPath]); `counter` is stored as a [TextAnimator]
 *    like the other text effects and drawn by [CounterEval] (Alight's "Count Up/Down": every number
 *    in the text × its `scale` + its `offset`, both evaluated at the frame's time); the plain `repeat`
 *    is stored as the app's Repeat effect and drawn by [RepeatEval] (its radial / scatter variants
 *    too). Effects with no counterpart in the app (the path following beyond the baked form,
 *    per-corner roundness, …) stay named warnings — nothing is silently dropped.
 *  - everything else (other layer types, other effects) is skipped and listed in [ImportResult.warnings]
 *
 * Rules that were read from the sample files (NOT from the Alight documentation, so check them on a real export):
 *  - `<kf t>` is NORMALIZED: 0..1 of the layer's length (for an `<embedScene>`, of the embed's length)
 *  - the easing `e="cubicBezier x1 y1 x2 y2"` sits on the keyframe a segment ENDS at (the first keyframe of a
 *    track never has one, the last one usually does); the app stores the easing on the keyframe a segment
 *    STARTS at, so every easing moves one keyframe back when a track is converted
 *  - `location` is in scene pixels from the top-left corner; the app stores the move from the canvas centre in PREVIEW
 *    pixels (see PreviewFrameRef), so it is scaled by previewWidthPx / sceneWidth
 *  - Alight text is measured in units of sceneWidth / 640 px: the rendered font height is
 *    `size` * `scale` * sceneWidth / 640 scene pixels. (Build 91 read / 512 off a width
 *    measurement; round 5 (2026-11-11) measured CAP HEIGHTS on Alight's ST Intro export for two
 *    texts in two fonts — Roboto 700 size 18 at scale 0.839844: 32 px at 1920 wide, and the pill
 *    text at scale 0.792888: 30 px — and both give exactly sceneWidth / 640, a factor 3.0 at 1920:
 *    cap height is spacing-independent, so it outranks the width reading.) The app's text is
 *    0.11 * canvas width at 100 %. `wrapWidth` is in scene pixels and becomes the layer's max
 *    text width.
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
    const val LONGSHADOW = "com.alightcreative.effects.longshadow"
    const val LONGSHADOW2 = "com.alightcreative.effects.longshadow2"
    const val RAYS = "com.alightcreative.effects.rays"
    const val COLORTUNE2 = "com.alightcreative.effects.colortune2"
    const val MIRROR = "com.alightcreative.effects.mirror"
    const val GLOWSCAN = "com.alightcreative.effects.glowscan"
    const val LUMAKEY3 = "com.alightcreative.effects.lumakey3"
    const val FOURCOLORGRADIENT = "com.alightcreative.fourcolorgradient"
    const val TURBULENTDISPLACE = "com.alightcreative.effects.turbulentdisplace"
    const val STRETCHSEGMENT = "com.alightcreative.effects.stretchsegment"
    const val SPIN = "com.alightcreative.effects.spin"
    const val REPLACECOLOR = "com.alightcreative.replacecolor"
    const val SWIRL4 = "com.alightcreative.effects.swirl4"
    const val SWIRL3 = "com.alightcreative.effects.swirl3"
    const val HALFTONEDOTS = "com.alightcreative.effects.halftonedots"
    const val PIXELATE2 = "com.alightcreative.effects.pixelate2"
    const val MOTIONBLUR = "com.alightcreative.effects.motionblur"
    const val MOTIONBLUR2 = "com.alightcreative.effects.motionblur2"
    const val MOTIONBLUR3 = "com.alightcreative.effects.motionblur3"
    const val MOTIONBLUR4 = "com.alightcreative.effects.motionblur4"
    // v164: the parked tier (defs-batch3). SHAKE_V1 is the ORIGINAL shake (a JS auto-transform
    // in its def), not shake2 above; BRIGHTCONT_V1's shader is byte-identical to brightcont2's.
    const val SHAKE_V1 = "com.alightcreative.effects.shake"
    const val BRIGHTCONT_V1 = "com.alightcreative.effects.brightcont"
    const val CHROMAKEY = "com.alightcreative.effects.chromakey"
    const val COLORBALANCE = "com.alightcreative.effects.colorbalance"
    const val COLORTEMPERATURE = "com.alightcreative.effects.colortemperature"
    const val COLORTINT = "com.alightcreative.effects.colortint"
    const val SPINBLUR2 = "com.alightcreative.effects.spinblur2"
    const val OFFSETFX = "com.alightcreative.effects.offset"
    const val RADIALWIPE = "com.alightcreative.effects.radialwipe"
    const val FINDEDGES = "com.alightcreative.effects.findedges"
    const val HEXSHIFT = "com.alightcreative.effects.hexshift"
    const val SOFTGLOW = "com.alightcreative.effects.softglow"
    const val FILLBEHIND = "com.alightcreative.effects.fillbehind"
    // v165 tier (hunt round 3; defs in after-motion-dev/hunt-staging/defs-batch4):
    const val WAVEWARP2 = "com.alightcreative.effects.wavewarp2"
    const val CIRCULARRIPPLE2 = "com.alightcreative.effects.circularripple2"
    const val CIRCULARRIPPLE = "com.alightcreative.effects.circularripple"
    const val STRETCH2 = "com.alightcreative.effects.stretch2"
    const val STRETCHV1 = "com.alightcreative.effects.stretch"
    const val SQUEEZE = "com.alightcreative.effects.squeeze"
    const val FRACTALWARP4 = "com.alightcreative.effects.fractalwarp4"
    const val STRIPES = "com.alightcreative.effects.stripes"
    const val TURBULENTDISPLACE3 = "com.alightcreative.effects.turbulentdisplace3"
    const val ROUGHENEDGES = "com.alightcreative.effects.roughenedges"
    const val STREAKS = "com.alightcreative.effects.streaks"
    const val COLORHOT = "com.alightcreative.effects.colorhot"
    const val COLORIZE = "com.alightcreative.effects.colorize"
    const val HIGHLIGHTSHADOW = "com.alightcreative.effects.highlightshadow"
    const val SPOTCOLOR2 = "com.alightcreative.spotcolor2"
    const val DISSOLVE = "com.alightcreative.effects.dissolve"
    const val SIMPLESTARS2 = "com.alightcreative.effects.simplestars2"
    const val RIDGES2 = "com.alightcreative.effects.ridges2"
    const val RIDGES = "com.alightcreative.effects.ridges"
    const val DOTS2 = "com.alightcreative.effects.dots2"
    const val BENDWARP = "com.alightcreative.effects.bendwarp"
    const val DISPLACEMAP3 = "com.alightcreative.effects.displacemap3"
    const val DISPLACEMAP2 = "com.alightcreative.effects.displacemap2"
    const val DISPLACEMAPRAD = "com.alightcreative.effects.displacemaprad"
    const val SMOOTHBEVEL = "com.alightcreative.internal.smoothbevel"
    const val LIGHTGLOW = "com.alightcreative.effects.lightglow"
    const val INNERGLOW = "com.alightcreative.inner-glow"

    /** Effect ids the text reader stores as [TextAnimator] itself (the effect pass must not warn about them). */
    private val TEXT_STORED = setOf(TEXTTRANSFORM, TEXTPROGRESS, COUNTER)

    /** Imported audio layers get this timeline colour. */
    private const val AUDIO_COLOR = 0xFF2E8B6EL

    /** A "hold" easing: the value stays put, then jumps at the segment's end (used by the blink bake). */
    private val HOLD_EASING = Easing(1f, 0f, 1f, 1f)

    private val KNOWN_FAMILIES = setOf("Roboto", "Serif", "Monospace", "Cursive")
    private const val APP_FONT_FRACTION = 0.11f

    /** Alight text units: one unit is sceneWidth / [TEXT_REF_UNITS] px (see the header rules). */
    private const val TEXT_REF_UNITS = 640f

    /** The app's shape box at 100 % (dp, see OverlayPose.shapeBoxPx) and the canvas width it is measured against. */
    private const val APP_SHAPE_BOX_DP = 130f
    private const val CANVAS_DP = 360f

    private const val SHAPE_COLOR = 0xFFFF3377L
    private const val GROUP_COLOR = 0xFF5BB85DL
    /** The app's own Null colour (VideoEditorViewModel's lane colour for LayerType.NULL). */
    private const val NULL_COLOR = 0xFFE8A33DL

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
        /**
         * The app's shape box (130 dp) in scene pixels. Anchored to the ROOT scene, not to the
         * scene the layer sits in: an embedded scene's pixels are the project's pixels (Alight
         * draws a precomp 1:1, its centre at the embed's location — positions already convert
         * with [k], which is root-based), so a size read against a nested scene's own width came
         * back scaled by rootW / sceneW when the renderers drew it against the project canvas.
         * The Graph preset's card lives in a 2005-wide scene inside a 1920 project and rendered
         * 4.2 % small while the 1920-scene bars and lines around it stayed exact — its texts
         * looked pushed out of the card on every side (Graph round 6, G3).
         */
        val shapeBoxPx: Float get() = rootW * APP_SHAPE_BOX_DP / CANVAS_DP

        fun posX(v: List<Float>) = ((v.getOrNull(0) ?: sceneW / 2f) - sceneW / 2f) * k
        fun posY(v: List<Float>) = ((v.getOrNull(1) ?: sceneH / 2f) - sceneH / 2f) * k

        /** Outlines of this scene's shape layers by clip id, lent to `movealongpath3` (see [PathSpec]). */
        val outlineSpecs = HashMap<String, PathSpec>()

        /** `movealongpath3` requests of this scene, in layer order; resolved once every layer is read. */
        val pendingPaths = ArrayList<PendingPath>()
    }

    /** [flat] = every clip of the scene (groups flattened in, document order); [direct] = the clips whose own element is a direct child of the scene (for an embedScene: its Group layer). */
    private class SceneBlock(val flat: List<TimelineClip>, val direct: List<TimelineClip>)

    private fun readSceneContent(sceneEl: Element, ctx: Ctx): SceneBlock {
        val flat = ArrayList<TimelineClip>()
        // One slot per child element (null = the element produced no layer of this scene): a mask layer
        // cuts the layer of the element directly before it, so the pairing must see the gaps too.
        val directSlots = ArrayList<TimelineClip?>()
        val elements = elementChildren(sceneEl)
        for (el in elements) {
            when (el.tagName) {
                "text" -> readText(el, ctx).let { flat += it; directSlots += it }
                "shape" -> {
                    val c = readShape(el, ctx)
                    if (c != null) { flat += c; directSlots += c } else directSlots += null
                }
                "audio" -> readAudio(el, ctx).let { flat += it; directSlots += it }
                "drawing" -> {
                    val g = readDrawing(el, ctx)
                    if (g != null) { flat += g.first; directSlots += g.second } else directSlots += null
                }
                "camera" -> {
                    val c = readCamera(el, ctx)
                    if (c != null) { flat += c; directSlots += c } else directSlots += null
                }
                "nullobj" -> readNull(el, ctx).let { flat += it; directSlots += it }
                "embedScene" -> {
                    val g = readGroup(el, ctx)
                    if (g != null) { flat += g.first; directSlots += g.second } else directSlots += null
                }
                else -> {
                    ctx.warnings += "Layer <" + el.tagName + ">" + labelOf(el) + " is not supported yet: skipped"
                    directSlots += null
                }
            }
        }
        resolveMaskLinks(directSlots, flat, ctx)
        resolveMoveAlongPaths(directSlots, flat, ctx)
        val anchors = resolveParentLinks(elements, directSlots, flat, ctx)
        return SceneBlock(flat, directSlots.filterNotNull() + anchors)
    }

    /**
     * Alight layer masks (`blending="mask"`): a mask layer is never drawn in Alight — it cuts the layer
     * directly below it in the same scene, following its own animation. The imported mask clip stays in
     * the model flagged [TimelineClip.isMask] (hidden, and the renderers never draw it); here it is
     * linked to the layer below ([TimelineClip.maskTargetId]) and the renderers apply the cut. Shape,
     * text, group and video layers can be cut (a group is cut as its composed picture, a video as its
     * placed picture); only shape / text layers can serve as the mask. A mask sitting on another mask
     * (a stack) shares that mask's target: every mask of the stack links to the common layer below the
     * stack, and the renderers cut it by each mask in turn (the intersection of their shapes). A mask
     * with no imported layer below the stack, and a mask over a layer of any other type, stay unlinked
     * and are reported instead of being guessed at.
     */
    private fun resolveMaskLinks(directSlots: ArrayList<TimelineClip?>, flat: ArrayList<TimelineClip>, ctx: Ctx) {
        val w = ctx.warnings
        for (i in directSlots.indices) {
            val m = directSlots[i] ?: continue
            if (!m.isMask) continue
            // The layer this mask cuts: the first non-mask layer below it (a stack of masks over one
            // layer all cut that same layer; a skipped element in between is a gap, not a target).
            var j = i - 1
            while (j >= 0 && directSlots[j]?.isMask == true) j--
            val below = if (j >= 0) directSlots[j] else null
            val linked = when {
                below == null -> {
                    w += "Mask layer \"" + m.name + "\" has no imported layer below it: imported hidden, it cuts nothing"
                    false
                }
                below.type != LayerType.SHAPE && below.type != LayerType.TEXT && below.type != LayerType.GROUP &&
                    below.type != LayerType.VIDEO -> {
                    w += "Mask layer \"" + m.name + "\" masks a " + below.type.displayName.lowercase() +
                        " layer: imported hidden (a mask is applied to shape, text, group and video layers so far)"
                    false
                }
                m.type != LayerType.SHAPE && m.type != LayerType.TEXT -> {
                    w += "Mask layer \"" + m.name + "\" is not a shape or text layer: imported hidden, it cuts nothing"
                    false
                }
                else -> true
            }
            if (linked && below != null) {
                val copy = m.copy(maskTargetId = below.id)
                directSlots[i] = copy
                val fi = flat.indexOfFirst { it.id == m.id }
                if (fi >= 0) flat[fi] = copy
            }
        }
    }

    // ---- audio --------------------------------------------------------------------------------------------------

    /**
     * An `<audio>` element -> an audio clip with its timing. `inTime` / `outTime` (the part of the file the
     * layer plays) become the clip's trim, and `mediaFillMode` "stretch" (the file section is stretched onto
     * the layer's length) becomes a playback speed. The `src` is a content:// URI from the phone that
     * exported the XML; the importer stays pure and keeps the URI — the import screen then copies the file
     * into the project ([AlightMediaLocalizer]) and this warning is dropped. Only when that copy cannot be
     * made does the warning's promise hold: the clip is silent until the file is picked again. A `<volume>`
     * child, when present, sets the clip volume.
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

        // Alight's font height in scene pixels: size x scale, in units of sceneWidth / 640 px —
        // the width of the scene the TEXT sits in (its em is that many of the project's pixels:
        // embedded scenes draw 1:1). The app's font base is the ROOT canvas's, so a text read in
        // a differently-sized nested scene keeps its physical size (the Graph counter, in a
        // 2005-wide scene of a 1920 project, rendered 4.2 % small while its position stayed
        // exact — Graph round 6, G3). Root-scene texts are unchanged: the two widths agree.
        val fontBase = APP_FONT_FRACTION * ctx.rootW
        fun scalePct(v: List<Float>): Float {
            if (v.size >= 2 && abs(v[0] - v[1]) > 1e-3f) w += "Non-uniform scale: only the horizontal scale is used"
            val emPx = size * (v.firstOrNull() ?: 1f) * ctx.sceneW / TEXT_REF_UNITS
            return emPx / fontBase * 100f
        }
        // wrapWidth is in scene pixels: the widest line Alight allows before wrapping. The stored
        // fraction is of the frame the renderers lay out on — the root canvas.
        val wrapFrac = el.getAttribute("wrapWidth").toFloatOrNull()?.takeIf { it > 0f }
            ?.let { (it / ctx.rootW).coerceIn(0.05f, 1f) } ?: 0f

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
            // A hidden effect is disabled in Alight: not stored (applyLayerEffects reports it as skipped).
            if (id in TEXT_STORED && fx.getAttribute("hidden") != "true") {
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
        if (animators.count { it.effectId == TEXTTRANSFORM } > 1) {
            w += "Only the first texttransform of a layer is drawn"
        }

        val built = TimelineClip(
            id = UUID.randomUUID().toString(),
            name = el.getAttribute("label").ifBlank { "Text" },
            type = LayerType.TEXT,
            isVisible = !isMaskLayer(el),
            isMask = isMaskLayer(el),
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
            blendMode = amBlendMode(el, w),
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
            props[n] = AnimValue(type, if (t.keys.isEmpty()) t.constant else emptyList(), t.keys.map { AnimKey(it.t, it.v, it.easing) })
        }
        return TextAnimator(id, props, refSize)
    }

    // ---- shape --------------------------------------------------------------------------------------------------

    private fun readShape(el: Element, ctx: Ctx): TimelineClip? {
        val w = ctx.warnings
        val kind = el.getAttribute("s")
        if (kind == ".line" || kind == ".arrow") return readLineShape(el, ctx, kind)
        // No `s` kind but a <path> child: a freehand vector layer (whole presets are built from them).
        if (kind.isEmpty() && child(el, "path") != null) return readPathShape(el, ctx)
        if (kind == ".multifoil") return readMultifoilShape(el, ctx)
        if (kind == ".triangle" || kind == ".penta") return readPointShape(el, ctx, kind)
        if (kind == ".poly") return readPolyShape(el, ctx)
        if (kind == ".star") return readStarShape(el, ctx)
        if (kind == ".pie") return readPieShape(el, ctx)
        if (kind == ".callout.roundrect") return readCalloutShape(el, ctx)
        val pattern = when (kind) {
            ".rect" -> "RECT"
            ".circle" -> "CIRCLE"
            ".roundrect" -> "ROUNDED_RECT"
            else -> {
                val why = when {
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

        // Fill: the <fillColor> child, or the media of a media fill (a still in `fillImage`, a
        // playing video in `fillVideo` — the uri travels in the XML either way).
        val fillColor = child(el, "fillColor")?.let { parseColor(it.getAttribute("value")) } ?: 0xFFFFFFFFL
        var fillImageUri: String? = null
        var fillVideoUri: String? = null
        var fillImageMode = IMG_FILL
        val fillMode = when (val ft = el.getAttribute("fillType")) {
            "none" -> FILL_NONE
            "media" -> {
                fillImageMode = when (el.getAttribute("mediaFillMode")) {
                    "stretch" -> IMG_STRETCH
                    "fit" -> IMG_FIT
                    else -> IMG_FILL
                }
                fillVideoUri = el.getAttribute("fillVideo").ifEmpty { null }
                if (fillVideoUri != null) {
                    FILL_VIDEO
                } else {
                    fillImageUri = el.getAttribute("fillImage").ifEmpty { null }
                    if (fillImageUri == null) w += "Shape" + labelOf(el) + ": its picture is not in the XML: the fill stays empty"
                    FILL_IMAGE
                }
            }
            "", "color" -> FILL_SOLID
            else -> {
                w += "Shape fill \"" + ft + "\" is not supported yet: a solid fill is used"
                FILL_SOLID
            }
        }
        // A video fill's own window in its file: the element's inTime / outTime, kept like a media
        // layer's trim (AM's outTime 2147476647 is its "to the end of the file" sentinel; the
        // renderers clamp to the real duration). A "stretch" fill mode stretches that window onto
        // the layer's length, as it does for audio.
        val fillInMs = if (fillMode == FILL_VIDEO) el.getAttribute("inTime").toLongOrNull() ?: 0L else 0L
        val fillOutMs = if (fillMode == FILL_VIDEO) el.getAttribute("outTime").toLongOrNull() ?: 0L else 0L
        val fillSpeed = if (fillMode == FILL_VIDEO && el.getAttribute("mediaFillMode") == "stretch" &&
            fillOutMs > fillInMs && (fillOutMs - fillInMs) != durMs
        ) (fillOutMs - fillInMs).toFloat() / durMs else 1f
        val blendMode = amBlendMode(el, w)

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
        // Corner radius (only `.roundrect` carries one): Alight measures it in the shape's own units —
        // the same half-extent units as `size` — and clamps it to half the smaller side, which in half
        // extents is the smaller half extent itself (build 97: the old clamp halved it a second time,
        // and the old basis tied the radius to the shape's WIDTH, so a wide pill's radius collapsed).
        // The app stores 65 x rho in the shape box's dp, rho = the clamped radius as a fraction of
        // that smaller half extent, so the renderers can rebuild the frame-space radius
        // (rho x the smaller rendered half extent) at any keyframed size: rho = 1 is a stadium.
        var shapeCornerRadius = -1f
        var cornerLocal = 0f   // the clamped radius in the shape's own units (a path outline needs it raw)
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
            val smaller = minOf(sizeW, sizeH)
            if (rLocal != null && smaller > 0f) {
                val rEff = minOf(rLocal, smaller).coerceAtLeast(0f)
                cornerLocal = rEff
                shapeCornerRadius = (APP_SHAPE_BOX_DP / 2f * rEff / smaller).coerceIn(0f, APP_SHAPE_BOX_DP / 2f)
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
            else -> strokeSz * CANVAS_DP / ctx.rootW
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
        }

        val posX = ctx.posX(tr.loc?.first() ?: emptyList())
        val posY = ctx.posY(tr.loc?.first() ?: emptyList())
        val built = TimelineClip(
            id = UUID.randomUUID().toString(),
            name = el.getAttribute("label").ifBlank { "Shape" },
            type = LayerType.SHAPE,
            isVisible = !isMaskLayer(el),
            isMask = isMaskLayer(el),
            startMs = startMs.coerceAtLeast(0L),
            durationMs = durMs,
            color = SHAPE_COLOR,
            textColor = fillColor,
            fillMode = fillMode,
            fillImageUri = fillImageUri,
            fillVideoUri = fillVideoUri,
            trimStartMs = fillInMs,
            sourceDurationMs = fillOutMs,
            speed = fillSpeed,
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
            blendMode = blendMode,
            keyframes = keyframes
        )
        // The layer's vector outline, lent to a `movealongpath3` on the layer above it (see PathSpec).
        ctx.outlineSpecs[built.id] = PathSpec(
            kind = pattern, startMs = built.startMs, durMs = built.durationMs,
            loc = tr.loc ?: Track(listOf(ctx.sceneW / 2f, ctx.sceneH / 2f), emptyList()),
            scl = tr.scl, rot = tr.rot, size = sizeT, corner = cornerLocal
        )
        return applyLayerEffects(el, built, ctx, durMs, FxTarget.SHAPE)
    }

    /**
     * A `.multifoil` shape: Alight's foil star — [pointCount] lobes whose tips lie on the outer
     * radius and whose valleys lie on the inner radius. The outline is NOT a plain star polygon:
     * AM's own shape definition (recovered from the public decompiled AM assets,
     * assets/shapes/multifoil.xml) builds each tip as a SMOOTH bezier point whose handles sit
     * perpendicular to the radius, half the inner circumference per point (pw = pi * inner / count)
     * to either side, so the lobes come out as rounded petals and the valleys as sharp corners.
     * This is a line-by-line port of that script's getPath into [PathSeg]s, finished by the shared
     * path-shape tail ([finishPathShape]). The four parameters are plain properties; a keyframed
     * one keeps its first value with a warning, like a shape's corner radius.
     */
    private fun readMultifoilShape(el: Element, ctx: Ctx): TimelineClip? {
        val w = ctx.warnings
        fun prop(name: String, def: Float): Float {
            val t = elementChildren(el).firstOrNull { it.tagName == "property" && it.getAttribute("name") == name }
                ?.let { readTrack(it) } ?: return def
            if (t.keys.size >= 2) w += "Keyframed \"" + name + "\" of a multifoil is not supported yet: the first value is used"
            return t.first().firstOrNull() ?: def
        }
        val count = prop("pointCount", 5f).roundToInt().coerceIn(3, 32)
        val outerR = prop("outerRadius", 100f)
        val innerR = prop("innerRadius", 50f)
        val offsetRad = Math.toRadians(prop("offsetAngle", 0f).toDouble())
        if (outerR < 1e-6f) {
            w += "Layer <shape>" + labelOf(el) + ": its outer radius is 0: skipped"
            return null
        }
        val per = 2.0 * Math.PI / count
        val pw = Math.PI * innerR / count
        // AM's points, in order: a smooth outer tip (in / out handles), then a sharp inner valley,
        // per point; the path closes from the last valley back to the first tip.
        class Tip(val ix: Float, val iy: Float, val x: Float, val y: Float, val ox: Float, val oy: Float)
        val tips = ArrayList<Tip>()
        val valleys = ArrayList<Pair<Float, Float>>()
        for (i in 0 until count) {
            val a = per * i + Math.PI / 2.0 + offsetRad
            val x = (outerR * cos(a)).toFloat()
            val y = (-outerR * sin(a)).toFloat()
            val px = y / outerR
            val py = -x / outerR
            tips += Tip(
                x - (px * pw).toFloat(), y - (py * pw).toFloat(), x, y,
                x + (px * pw).toFloat(), y + (py * pw).toFloat()
            )
            val a2 = a + per / 2.0
            valleys += (innerR * cos(a2)).toFloat() to (-innerR * sin(a2)).toFloat()
        }
        val segs = ArrayList<PathSeg>()
        val t0 = tips[0]
        segs += PathSeg(PATH_OP_MOVE, listOf(t0.x, t0.y))
        for (i in 0 until count) {
            val tip = tips[i]
            val v = valleys[i]
            val next = tips[(i + 1) % count]
            // Tip -> valley (the tip's out handle leads, the valley is a corner), then
            // valley -> next tip (the tip's in handle lands it smoothly).
            segs += PathSeg(PATH_OP_CUBIC, listOf(tip.ox, tip.oy, v.first, v.second, v.first, v.second))
            segs += PathSeg(PATH_OP_CUBIC, listOf(v.first, v.second, next.ix, next.iy, next.x, next.y))
        }
        segs += PathSeg(PATH_OP_CLOSE, emptyList())
        return finishPathShape(el, ctx, segs)
    }

    // ---- parameterised shape kinds (.triangle / .penta / .poly / .star) ----------------------------------------
    //
    // These kinds carry no `size` property: their outlines are built from the shape definition's
    // own parameters, serialised as <property> elements named by the def's param ids (the corpus's
    // .multifoil instance proves the pattern). Each reader below is a line-by-line port of the
    // getPath script in AM's own shape definition (recovered from the public decompiled AM assets,
    // assets/shapes/; copies in hunt-staging/shape-defs/), finished by the shared path-shape tail
    // ([finishPathShape]) — so fills, strokes, masks, media fills and drawing.progress behave as
    // they do for a freehand <path>. A path shape's trim starts at its first stored vertex
    // (build 120), which here is the construction's first point: p1 for the free-point kinds, the
    // top vertex for .poly / .star. A keyframed parameter keeps its first value with a warning,
    // like a multifoil's (the corpus holds no instance of any of these kinds — the constructions
    // rest entirely on AM's defs).

    /** One `<property>` of a parameterised shape kind, as a track (null when the property is absent). */
    private fun shapeParam(el: Element, name: String): Track? =
        elementChildren(el).firstOrNull { it.tagName == "property" && it.getAttribute("name") == name }
            ?.let { readTrack(it) }

    private fun keyframedParamNote(ctx: Ctx, kindName: String, name: String, t: Track) {
        if (t.keys.size >= 2) ctx.warnings += "Keyframed \"" + name + "\" of a " + kindName +
            " is not supported yet: the first value is used"
    }

    private fun shapeParamFloat(el: Element, ctx: Ctx, kindName: String, name: String, def: Float): Float {
        val t = shapeParam(el, name) ?: return def
        keyframedParamNote(ctx, kindName, name, t)
        return t.first().firstOrNull() ?: def
    }

    private fun shapeParamPoint(
        el: Element, ctx: Ctx, kindName: String, name: String, defX: Float, defY: Float
    ): Pair<Float, Float> {
        val t = shapeParam(el, name) ?: return defX to defY
        keyframedParamNote(ctx, kindName, name, t)
        val v = t.first()
        return (v.getOrNull(0) ?: defX) to (v.getOrNull(1) ?: defY)
    }

    private fun shapeParamBool(el: Element, ctx: Ctx, kindName: String, name: String, def: Boolean): Boolean {
        val t = shapeParam(el, name) ?: return def
        keyframedParamNote(ctx, kindName, name, t)
        return (t.first().firstOrNull() ?: if (def) 1f else 0f) > 0.5f
    }

    /**
     * A `.triangle` / `.penta` shape: a free-point polygon — AM's def returns the points p1..pN
     * verbatim, in order (the triangle's outline starts at p1), closed unless its `closed`
     * switch is off. The fallback points are the defs' defaults; the penta's are NOT a regular
     * pentagon (its p3 default is the centre point) — they are ported exactly as written.
     */
    private fun readPointShape(el: Element, ctx: Ctx, kind: String): TimelineClip? {
        val kindName = kind.removePrefix(".")
        val defaults = if (kind == ".triangle") listOf(
            -100f to 100f, 0f to -100f, 100f to 100f
        ) else listOf(
            -100f to -100f, 0f to -100f, 0f to 0f, 100f to 100f, -100f to 100f
        )
        val pts = defaults.mapIndexed { i, d ->
            shapeParamPoint(el, ctx, kindName, "p" + (i + 1), d.first, d.second)
        }
        val closed = shapeParamBool(el, ctx, kindName, "closed", true)
        val segs = ArrayList<PathSeg>()
        segs += PathSeg(PATH_OP_MOVE, listOf(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) segs += PathSeg(PATH_OP_LINE, listOf(pts[i].first, pts[i].second))
        if (closed) segs += PathSeg(PATH_OP_CLOSE, emptyList())
        return finishPathShape(el, ctx, segs)
    }

    /**
     * A `.poly` shape: a regular polygon of `sideCount` sides on `radius`, ported from AM's
     * def — vertex i at angle (2pi/sides * i + pi/2 + offsetAngle), drawn as
     * (r * cos a, -r * sin a), so the first vertex is the TOP one at offset 0 and the outline
     * runs counterclockwise in AM's own frame. Always closed.
     */
    private fun readPolyShape(el: Element, ctx: Ctx): TimelineClip? {
        val w = ctx.warnings
        val sides = shapeParamFloat(el, ctx, "poly", "sideCount", 6f).roundToInt().coerceIn(3, 32)
        val radius = shapeParamFloat(el, ctx, "poly", "radius", 100f)
        val offsetRad = Math.toRadians(shapeParamFloat(el, ctx, "poly", "offsetAngle", 0f).toDouble())
        if (abs(radius) < 1e-6f) {
            w += "Layer <shape>" + labelOf(el) + ": its radius is 0: skipped"
            return null
        }
        val per = 2.0 * Math.PI / sides
        val segs = ArrayList<PathSeg>()
        for (i in 0 until sides) {
            val a = per * i + Math.PI / 2.0 + offsetRad
            segs += PathSeg(
                if (i == 0) PATH_OP_MOVE else PATH_OP_LINE,
                listOf((radius * cos(a)).toFloat(), (-radius * sin(a)).toFloat())
            )
        }
        segs += PathSeg(PATH_OP_CLOSE, emptyList())
        return finishPathShape(el, ctx, segs)
    }

    /**
     * A `.star` shape, ported from AM's def: `pointCount` points alternating outer / inner
     * radius — the outer vertex first, at the top at offset 0, each inner vertex half a step
     * later — so the outline runs counterclockwise in AM's own frame. Always closed.
     */
    private fun readStarShape(el: Element, ctx: Ctx): TimelineClip? {
        val w = ctx.warnings
        val count = shapeParamFloat(el, ctx, "star", "pointCount", 5f).roundToInt().coerceIn(3, 32)
        val outerR = shapeParamFloat(el, ctx, "star", "outerRadius", 100f)
        val innerR = shapeParamFloat(el, ctx, "star", "innerRadius", 50f)
        val offsetRad = Math.toRadians(shapeParamFloat(el, ctx, "star", "offsetAngle", 0f).toDouble())
        if (abs(outerR) < 1e-6f && abs(innerR) < 1e-6f) {
            w += "Layer <shape>" + labelOf(el) + ": its radii are 0: skipped"
            return null
        }
        val per = 2.0 * Math.PI / count
        val halfPer = per / 2.0
        val segs = ArrayList<PathSeg>()
        for (i in 0 until count) {
            var a = per * i + Math.PI / 2.0 + offsetRad
            segs += PathSeg(
                if (i == 0) PATH_OP_MOVE else PATH_OP_LINE,
                listOf((outerR * cos(a)).toFloat(), (-outerR * sin(a)).toFloat())
            )
            a += halfPer
            segs += PathSeg(PATH_OP_LINE, listOf((innerR * cos(a)).toFloat(), (-innerR * sin(a)).toFloat()))
        }
        segs += PathSeg(PATH_OP_CLOSE, emptyList())
        return finishPathShape(el, ctx, segs)
    }

    /**
     * A `.pie` shape: a wedge whose outline Alight's own shape definition builds from its
     * startAngle / endAngle / radius parameters ([PieOutline] is the line-by-line port of that
     * def's script). The wedge is centred on the layer's own origin by construction, so — unlike
     * the bounding-box kinds above — its normalisation divides by the RADIUS and folds no centre
     * offset into the position.
     *
     * All 128 corpus instances (shared-preset-likenya's loader) keyframe BOTH angles, so the
     * outline cannot be baked: the angle tracks are stored on the clip (as ordinary keyframes,
     * with the segment easings shifted the way [trackKeys] shifts them) and the engines rebuild
     * the outline from the evaluated angles per frame (see `pieSegmentsAt`). The stored segments
     * are the outline at the layer's most open sampled instant, for consumers that never
     * evaluate. A keyframed radius rides the composed SCALE keyframes like a shape's keyframed
     * size (the outline itself is radius-free once normalised); the radius's first value is the
     * normalisation basis. A wedge the wrap rule keeps closed at every sampled instant draws
     * nothing in Alight either — it is skipped with that said, like a single-point path.
     */
    private fun readPieShape(el: Element, ctx: Ctx): TimelineClip? {
        val w = ctx.warnings
        val startMs = el.getAttribute("startTime").toLongOrNull() ?: 0L
        val endMs = el.getAttribute("endTime").toLongOrNull() ?: ctx.totalMs
        val durMs = (endMs - startMs).coerceAtLeast(250L)
        val startT = shapeParam(el, "startAngle")
        val endT = shapeParam(el, "endAngle")
        val radiusT = shapeParam(el, "radius")
        val radius = radiusT?.first()?.firstOrNull() ?: 100f
        if (radius == 0f) {
            w += "Layer <shape>" + labelOf(el) + ": its radius is 0: skipped"
            return null
        }
        if (radius < 0f) {
            w += "Layer <shape>" + labelOf(el) + ": its radius is negative: skipped"
            return null
        }
        val startKeys = angleKeys(startT, durMs, 45f)
        val endKeys = angleKeys(endT, durMs, 90f)
        // The stored outline: the most open instant among the tracks' key times and the segment
        // midpoints between them, sampled through the app's own keyframe sampler (easings in).
        val times = (startKeys.map { it.timeMs } + endKeys.map { it.timeMs }).distinct().sorted()
        val samples = ArrayList<Long>()
        samples += times
        for (i in 0 until times.size - 1) samples += (times[i] + times[i + 1]) / 2L
        samples += 0L
        samples += durMs
        var bestT = -1L
        var bestSweep = 0.0
        for (t in samples.distinct()) {
            val sweep = PieOutline.sweepDegrees(
                startKeys.valueAt(t).first.toDouble(), endKeys.valueAt(t).first.toDouble()
            )
            if (sweep > bestSweep) {
                bestSweep = sweep
                bestT = t
            }
        }
        if (bestT < 0L || bestSweep < 0.01) {
            w += "Layer <shape>" + labelOf(el) +
                ": its start and end angles keep the wedge closed at every keyframe: skipped"
            return null
        }
        val raw = PieOutline.segments(
            startKeys.valueAt(bestT).first.toDouble(), endKeys.valueAt(bestT).first.toDouble(),
            radius.toDouble()
        )
        if (raw.isEmpty()) {
            w += "Layer <shape>" + labelOf(el) +
                ": its start and end angles keep the wedge closed at every keyframe: skipped"
            return null
        }
        val norm = PathNorm(
            raw.map { seg -> PathSeg(seg.op, seg.pts.map { v -> v / radius }) },
            radius, 0f, 0f, flattenPathSegments(raw)
        )
        // A keyframed radius composes into the SCALE keyframes as the shape's size track:
        // (radius, radius) pairs at the radius track's own key times.
        val sizeTrack = if (radiusT != null && radiusT.keys.size >= 2) {
            Track(
                listOf(radius, radius),
                radiusT.keys.map { k ->
                    val r = k.v.firstOrNull() ?: radius
                    TKey(k.t, listOf(r, r), k.easing)
                }
            )
        } else null
        return finishPathShapeWithNorm(el, ctx, raw, norm, sizeTrack) { clip ->
            clip.copy(pieStartKeys = startKeys, pieEndKeys = endKeys, pieRadius = radius)
        }
    }

    /**
     * One angle track of a `.pie`, as app keyframes: [trackKeys]' easing shift (a segment takes
     * the easing of the Alight keyframe it ends at), the angle in `a`. A constant or single-key
     * track becomes one keyframe at the layer start.
     */
    private fun angleKeys(t: Track?, durMs: Long, def: Float): List<Keyframe> {
        val ks = trackKeys(t, durMs) { (it.firstOrNull() ?: def) to 0f }
        if (ks.isNotEmpty()) return ks
        return listOf(Keyframe(0L, t?.first()?.firstOrNull() ?: def))
    }

    /**
     * A `.callout.roundrect` shape: a rounded rectangle with a speech-bubble tail on one side —
     * [CalloutOutline] is the line-by-line port of AM's own shape definition (recovered from the
     * public decompiled AM assets, `assets/shapes/`), finished by the shared path-shape tail
     * ([finishPathShape]), so fills, strokes, masks, media fills and drawing.progress behave as
     * they do for the other built-in kinds. Unlike the free-point kinds above this one DOES
     * carry a `size` property — the half extents the def's outline is built from — but the
     * outline encodes it (its box is the rect plus the tail's overhang), so the tail's usual
     * box normalisation applies and no separate size handling is needed. The four parameters
     * are plain properties read with the def's defaults; a keyframed one keeps its first value
     * with a warning, like a multifoil's. The corpus's single instance (proyek-baru-15's
     * "Callout 1") carries exactly the def defaults, all static.
     */
    private fun readCalloutShape(el: Element, ctx: Ctx): TimelineClip? {
        val w = ctx.warnings
        val kindName = "callout.roundrect"
        val (sizeX, sizeY) = shapeParamPoint(el, ctx, kindName, "size", 100f, 100f)
        val (tailX, tailY) = shapeParamPoint(el, ctx, kindName, "tail", -50f, 150f)
        val cornerRadius = shapeParamFloat(el, ctx, kindName, "cornerRadius", 25f)
        val tailWidth = shapeParamFloat(el, ctx, kindName, "tailWidth", 40f)
        if (sizeX <= 0f || sizeY <= 0f) {
            w += "Layer <shape>" + labelOf(el) + ": its size is not positive: skipped"
            return null
        }
        val segs = CalloutOutline.segments(
            sizeX.toDouble(), sizeY.toDouble(), tailX.toDouble(), tailY.toDouble(),
            cornerRadius.toDouble(), tailWidth.toDouble()
        )
        return finishPathShape(el, ctx, segs)
    }

    /** A raw outline normalised for storage (see [normalisePathSegs]): the stored segments, the bounding box's centre and larger half extent, and the flattened outline points. */
    private class PathNorm(
        val segments: List<PathSeg>, val half: Float, val cx: Float, val cy: Float,
        val flat: List<Pair<Float, Float>>
    )

    /**
     * Normalises a raw outline (absolute points in a layer's own frame) for storage as
     * [TimelineClip.pathSegments]: the segments are centred on the outline's bounding box and
     * divided by its larger half extent — a UNIFORM divisor, so a flat outline's stroke is not
     * squashed (see [readPathShape]). Null when the outline has no drawable extent (no points,
     * or a single point).
     */
    private fun normalisePathSegs(raw: List<PathSeg>): PathNorm? {
        val flat = flattenPathSegments(raw)
        if (flat.isEmpty()) return null
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for ((x, y) in flat) {
            minX = minOf(minX, x); minY = minOf(minY, y)
            maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
        }
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        val half = maxOf((maxX - minX) / 2f, (maxY - minY) / 2f)
        if (half < 1e-6f) return null
        val segments = raw.map { seg ->
            PathSeg(seg.op, seg.pts.mapIndexed { i, v -> if (i % 2 == 0) (v - cx) / half else (v - cy) / half })
        }
        return PathNorm(segments, half, cx, cy, flat)
    }

    /**
     * A freehand `<path>` shape (no `s` kind): whole presets (the freefire / shake packs) draw their
     * media quads, colour plates and stroke lines this way — 26 layers of the corpus, all simple
     * M / L / Z polylines. The `d` points live in the layer's own frame — offsets from its anchor,
     * composed by its transform like every Alight layer — so the import mirrors [readShape] with the
     * path's bounding box in the role of `size`: the segments are stored centred on the box and
     * divided by its larger half extent (a UNIFORM divisor — an anisotropic one would squash the
     * stroke of a flat path, a horizontal line's box height being 0 — while [TimelineClip.shapeScaleX]
     * / [TimelineClip.shapeScaleY] carry each axis' true size separately), and the box centre's offset
     * from the anchor is folded into the position, composed through the anchor's own rotation / scale
     * at every keyframe time so a keyframed anchor still carries the path exactly. (An effect baked
     * later turns around the folded anchor instead of Alight's own; in the corpus every turning path
     * layer is centred on its anchor, where the fold is a no-op.)
     */
    private fun readPathShape(el: Element, ctx: Ctx): TimelineClip? {
        val w = ctx.warnings
        val pathEl = child(el, "path")!!
        val d = pathEl.getAttribute("d")
        if (d.isBlank() || elementChildren(pathEl).isNotEmpty()) {
            w += "Layer <shape>" + labelOf(el) + ": keyframed path data is not supported yet: skipped"
            return null
        }
        val raw = parsePathData(d, w, el) ?: return null
        return finishPathShape(el, ctx, raw)
    }

    /**
     * The shared tail of every path-outline shape (a freehand `<path>` shape, a `.multifoil` star,
     * and — with its own paint — a `<drawing>` stroke): the outline [raw] (absolute points in the
     * layer's own frame) is normalised onto its bounding box and built into a PATH clip with the
     * element's fill / stroke / transform, exactly as [readPathShape] documents.
     */
    private fun finishPathShape(el: Element, ctx: Ctx, raw: List<PathSeg>): TimelineClip? {
        val norm = normalisePathSegs(raw)
        if (norm == null) {
            ctx.warnings += "Layer <shape>" + labelOf(el) +
                if (flattenPathSegments(raw).isEmpty()) ": its path has no points: skipped"
                else ": its path is a single point: skipped"
            return null
        }
        return finishPathShapeWithNorm(el, ctx, raw, norm, null) { it }
    }

    /**
     * [finishPathShape] with the outline's normalisation already in hand — a `.pie` wedge's
     * outline is centred on the layer's own origin by construction, so its [norm] divides by the
     * radius instead of the bounding box, and its centre offset is zero. [sizeTrack] overrides
     * the constant (half, half) size track the SCALE keyframes are composed from (a pie with a
     * keyframed radius rides it); [decorate] adjusts the built clip before its effects are read
     * (the pie's angle tracks ride there).
     */
    private fun finishPathShapeWithNorm(
        el: Element, ctx: Ctx, raw: List<PathSeg>, norm: PathNorm,
        sizeTrack: Track?, decorate: (TimelineClip) -> TimelineClip
    ): TimelineClip? {
        val w = ctx.warnings
        val flat = norm.flat
        val cx = norm.cx
        val cy = norm.cy
        val half = norm.half
        val segments = norm.segments

        val startMs = el.getAttribute("startTime").toLongOrNull() ?: 0L
        val endMs = el.getAttribute("endTime").toLongOrNull() ?: ctx.totalMs
        val durMs = (endMs - startMs).coerceAtLeast(250L)
        val tr = readTransform(el, w, pivotUsed = false)

        // Fill and stroke, exactly as a built-in shape reads them (a media fill keeps its picture
        // or its video, as there).
        val fillColor = child(el, "fillColor")?.let { parseColor(it.getAttribute("value")) } ?: 0xFFFFFFFFL
        var fillImageUri: String? = null
        var fillVideoUri: String? = null
        var fillImageMode = IMG_FILL
        val fillMode = when (val ft = el.getAttribute("fillType")) {
            "none" -> FILL_NONE
            "media" -> {
                fillImageMode = when (el.getAttribute("mediaFillMode")) {
                    "stretch" -> IMG_STRETCH
                    "fit" -> IMG_FIT
                    else -> IMG_FILL
                }
                fillVideoUri = el.getAttribute("fillVideo").ifEmpty { null }
                if (fillVideoUri != null) {
                    FILL_VIDEO
                } else {
                    fillImageUri = el.getAttribute("fillImage").ifEmpty { null }
                    if (fillImageUri == null) w += "Shape" + labelOf(el) + ": its picture is not in the XML: the fill stays empty"
                    FILL_IMAGE
                }
            }
            "", "color" -> FILL_SOLID
            else -> {
                w += "Shape fill \"" + ft + "\" is not supported yet: a solid fill is used"
                FILL_SOLID
            }
        }
        // A video fill's window in its file, as in readShape.
        val fillInMs = if (fillMode == FILL_VIDEO) el.getAttribute("inTime").toLongOrNull() ?: 0L else 0L
        val fillOutMs = if (fillMode == FILL_VIDEO) el.getAttribute("outTime").toLongOrNull() ?: 0L else 0L
        val fillSpeed = if (fillMode == FILL_VIDEO && el.getAttribute("mediaFillMode") == "stretch" &&
            fillOutMs > fillInMs && (fillOutMs - fillInMs) != durMs
        ) (fillOutMs - fillInMs).toFloat() / durMs else 1f
        val blendMode = amBlendMode(el, w)

        var strokeColor = 0xFFFFFFFFL
        var strokeSz = 0f
        val strokeEl = child(el, "path-stroke")
        if (strokeEl != null && strokeEl.getAttribute("enabled") != "false") {
            strokeColor = child(strokeEl, "color")?.let { parseColor(it.getAttribute("value")) } ?: 0xFFFFFFFFL
            strokeSz = child(strokeEl, "size")?.getAttribute("value")?.toFloatOrNull() ?: 0f
        }

        val box = ctx.shapeBoxPx
        val sclV = tr.scl?.first() ?: listOf(1f, 1f)
        val sclX = sclV.getOrNull(0) ?: 1f
        val sclY = sclV.getOrNull(1) ?: sclX
        val rendW = 2f * half * sclX
        val rendH = 2f * half * sclY
        // Same stroke rule as the built-in kinds: the stored dp is the stroke's share of the width.
        val strokeWidth = when {
            strokeSz <= 0f -> 0f
            rendW > 0f -> APP_SHAPE_BOX_DP * strokeSz * sclX / rendW
            else -> strokeSz * CANVAS_DP / ctx.rootW
        }

        // The app's anchor is the clip position; the stored outline is centred on the path's box, so
        // the position is the Alight anchor plus the box centre's offset, rotated / scaled with it.
        fun anchorAt(t: Float): Pair<Float, Float> {
            val loc = tr.loc?.valueAt(t)
            val sc = tr.scl?.valueAt(t) ?: listOf(1f, 1f)
            val rot = tr.rot?.valueAt(t)?.firstOrNull() ?: 0f
            val ox = cx * (sc.getOrNull(0) ?: 1f)
            val oy = cy * (sc.getOrNull(1) ?: sc.getOrNull(0) ?: 1f)
            val rad = Math.toRadians(rot.toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            val lx = loc?.getOrNull(0) ?: ctx.sceneW / 2f
            val ly = loc?.getOrNull(1) ?: ctx.sceneH / 2f
            return (lx + ox * c - oy * s) to (ly + ox * s + oy * c)
        }
        val keyframes = LinkedHashMap<AnimProp, List<Keyframe>>()
        val times = ((tr.loc?.keys ?: emptyList()) + (tr.scl?.keys ?: emptyList()) + (tr.rot?.keys ?: emptyList()))
            .map { it.t }.distinct().sorted()
        if (times.size >= 2) {
            val keys = times.mapIndexed { i, t ->
                val next = times.getOrNull(i + 1)
                val e = if (next == null) Easing.LINEAR
                else tr.loc?.keyAt(next)?.easing ?: tr.scl?.keyAt(next)?.easing
                    ?: tr.rot?.keyAt(next)?.easing ?: Easing.LINEAR
                val a = anchorAt(t)
                Keyframe(
                    (t.coerceIn(0f, 1f) * durMs).roundToLong(),
                    (a.first - ctx.sceneW / 2f) * ctx.k, (a.second - ctx.sceneH / 2f) * ctx.k, e
                )
            }.distinctBy { it.timeMs }
            if (keys.size >= 2) keyframes[AnimProp.POSITION] = keys
        }
        fun keys(prop: AnimProp, t: Track?, map: (List<Float>) -> Pair<Float, Float>) {
            val l = trackKeys(t, durMs, map)
            if (l.isNotEmpty()) keyframes[prop] = l
        }
        keys(AnimProp.ROTATION, tr.rot) { (it.firstOrNull() ?: 0f) to 0f }
        keys(AnimProp.OPACITY, tr.opa) { ((it.firstOrNull() ?: 1f) * 100f).coerceIn(0f, 100f) to 0f }
        // The box in the role of `size`: a constant (half, half) track composed with the layer scale,
        // unless the caller hands over its own size track (a pie's keyframed radius).
        val sizeKeys = composedSizeKeys(sizeTrack ?: Track(listOf(half, half), emptyList()), tr.scl, durMs, box)
        if (sizeKeys.isNotEmpty()) keyframes[AnimProp.SCALE] = sizeKeys

        val a0 = anchorAt(0f)
        val posX = (a0.first - ctx.sceneW / 2f) * ctx.k
        val posY = (a0.second - ctx.sceneH / 2f) * ctx.k
        val built = decorate(TimelineClip(
            id = UUID.randomUUID().toString(),
            name = el.getAttribute("label").ifBlank { "Shape" },
            type = LayerType.SHAPE,
            isVisible = !isMaskLayer(el),
            isMask = isMaskLayer(el),
            startMs = startMs.coerceAtLeast(0L),
            durationMs = durMs,
            color = SHAPE_COLOR,
            textColor = fillColor,
            fillMode = fillMode,
            fillImageUri = fillImageUri,
            fillVideoUri = fillVideoUri,
            trimStartMs = fillInMs,
            sourceDurationMs = fillOutMs,
            speed = fillSpeed,
            fillImageMode = fillImageMode,
            shapePattern = "PATH",
            pathSegments = segments,
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
            blendMode = blendMode,
            keyframes = keyframes
        ))
        // The layer's own outline, lent to a `movealongpath3` on the layer above it (see PathSpec):
        // the raw flattened points, still in the layer's own (anchor-relative) frame.
        ctx.outlineSpecs[built.id] = PathSpec(
            kind = "PATH", startMs = built.startMs, durMs = built.durationMs,
            loc = tr.loc ?: Track(listOf(ctx.sceneW / 2f, ctx.sceneH / 2f), emptyList()),
            scl = tr.scl, rot = tr.rot,
            pathPts = flat, pathClosed = raw.any { it.op == PATH_OP_CLOSE }
        )
        return applyLayerEffects(el, built, ctx, durMs, FxTarget.SHAPE)
    }

    /**
     * Parses an SVG-style path `d` (Alight writes it with absolute M / L / Z in the corpus) into
     * absolute [PathSeg]s in the layer's own units. M / L / H / V / C / S / Q / T / Z are supported,
     * absolute or relative (S / T reflect the previous curve's control point, as SVG defines); a
     * command outside that set (an arc, above all) or malformed data warns and returns null, so the
     * layer is skipped rather than drawn wrong.
     */
    private fun parsePathData(d: String, w: MutableList<String>, el: Element): List<PathSeg>? {
        fun fail(why: String): List<PathSeg>? {
            w += "Layer <shape>" + labelOf(el) + ": " + why + ": skipped"
            return null
        }
        // Tokens: command letters and numbers, in order (commas / whitespace are only separators).
        val tokens = ArrayList<Any>()
        val numRe = Regex("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")
        var i = 0
        while (i < d.length) {
            val ch = d[i]
            when {
                ch.isLetter() -> { tokens += ch; i++ }
                ch == ',' || ch.isWhitespace() -> i++
                else -> {
                    val m = numRe.find(d, i)
                    if (m == null || m.range.first != i) return fail("its path data could not be read")
                    tokens += m.value.toFloat()
                    i = m.range.last + 1
                }
            }
        }
        val segs = ArrayList<PathSeg>()
        var idx = 0
        var cmd = ' '
        var curX = 0f
        var curY = 0f
        var subX = 0f
        var subY = 0f
        var cubX = 0f
        var cubY = 0f
        var hasCub = false
        var quaX = 0f
        var quaY = 0f
        var hasQua = false
        fun hasNum(): Boolean = idx < tokens.size && tokens[idx] is Float
        fun num(): Float? = if (hasNum()) tokens[idx++] as Float else null
        while (idx < tokens.size) {
            if (tokens[idx] is Char) {
                cmd = tokens[idx++] as Char
                if (cmd == 'Z' || cmd == 'z') {
                    segs += PathSeg(PATH_OP_CLOSE)
                    curX = subX; curY = subY
                    hasCub = false; hasQua = false
                    cmd = ' '
                    continue
                }
            }
            if (cmd == ' ') return fail("its path data could not be read")
            val rel = cmd.isLowerCase()
            fun px(v: Float) = if (rel) curX + v else v
            fun py(v: Float) = if (rel) curY + v else v
            when (cmd.uppercaseChar()) {
                'M' -> {
                    var first = true
                    while (hasNum()) {
                        val x = num() ?: return fail("its path data is incomplete")
                        val y = num() ?: return fail("its path data is incomplete")
                        curX = px(x); curY = py(y)
                        if (first) {
                            segs += PathSeg(PATH_OP_MOVE, listOf(curX, curY))
                            subX = curX; subY = curY
                            first = false
                        } else {
                            segs += PathSeg(PATH_OP_LINE, listOf(curX, curY))
                        }
                        hasCub = false; hasQua = false
                    }
                    if (first) return fail("its path data is incomplete")
                }
                'L' -> {
                    var any = false
                    while (hasNum()) {
                        val x = num() ?: return fail("its path data is incomplete")
                        val y = num() ?: return fail("its path data is incomplete")
                        curX = px(x); curY = py(y)
                        segs += PathSeg(PATH_OP_LINE, listOf(curX, curY))
                        any = true
                        hasCub = false; hasQua = false
                    }
                    if (!any) return fail("its path data is incomplete")
                }
                'H' -> {
                    var any = false
                    while (hasNum()) {
                        val x = num() ?: return fail("its path data is incomplete")
                        curX = px(x)
                        segs += PathSeg(PATH_OP_LINE, listOf(curX, curY))
                        any = true
                        hasCub = false; hasQua = false
                    }
                    if (!any) return fail("its path data is incomplete")
                }
                'V' -> {
                    var any = false
                    while (hasNum()) {
                        val y = num() ?: return fail("its path data is incomplete")
                        curY = py(y)
                        segs += PathSeg(PATH_OP_LINE, listOf(curX, curY))
                        any = true
                        hasCub = false; hasQua = false
                    }
                    if (!any) return fail("its path data is incomplete")
                }
                'C' -> {
                    var any = false
                    while (hasNum()) {
                        val x1 = num() ?: return fail("its path data is incomplete")
                        val y1 = num() ?: return fail("its path data is incomplete")
                        val x2 = num() ?: return fail("its path data is incomplete")
                        val y2 = num() ?: return fail("its path data is incomplete")
                        val x = num() ?: return fail("its path data is incomplete")
                        val y = num() ?: return fail("its path data is incomplete")
                        val c1x = px(x1); val c1y = py(y1)
                        cubX = px(x2); cubY = py(y2)
                        curX = px(x); curY = py(y)
                        segs += PathSeg(PATH_OP_CUBIC, listOf(c1x, c1y, cubX, cubY, curX, curY))
                        any = true
                        hasCub = true; hasQua = false
                    }
                    if (!any) return fail("its path data is incomplete")
                }
                'S' -> {
                    var any = false
                    while (hasNum()) {
                        val x2 = num() ?: return fail("its path data is incomplete")
                        val y2 = num() ?: return fail("its path data is incomplete")
                        val x = num() ?: return fail("its path data is incomplete")
                        val y = num() ?: return fail("its path data is incomplete")
                        val c1x = if (hasCub) 2f * curX - cubX else curX
                        val c1y = if (hasCub) 2f * curY - cubY else curY
                        cubX = px(x2); cubY = py(y2)
                        curX = px(x); curY = py(y)
                        segs += PathSeg(PATH_OP_CUBIC, listOf(c1x, c1y, cubX, cubY, curX, curY))
                        any = true
                        hasCub = true; hasQua = false
                    }
                    if (!any) return fail("its path data is incomplete")
                }
                'Q' -> {
                    var any = false
                    while (hasNum()) {
                        val x1 = num() ?: return fail("its path data is incomplete")
                        val y1 = num() ?: return fail("its path data is incomplete")
                        val x = num() ?: return fail("its path data is incomplete")
                        val y = num() ?: return fail("its path data is incomplete")
                        quaX = px(x1); quaY = py(y1)
                        curX = px(x); curY = py(y)
                        segs += PathSeg(PATH_OP_QUAD, listOf(quaX, quaY, curX, curY))
                        any = true
                        hasQua = true; hasCub = false
                    }
                    if (!any) return fail("its path data is incomplete")
                }
                'T' -> {
                    var any = false
                    while (hasNum()) {
                        val x = num() ?: return fail("its path data is incomplete")
                        val y = num() ?: return fail("its path data is incomplete")
                        quaX = if (hasQua) 2f * curX - quaX else curX
                        quaY = if (hasQua) 2f * curY - quaY else curY
                        curX = px(x); curY = py(y)
                        segs += PathSeg(PATH_OP_QUAD, listOf(quaX, quaY, curX, curY))
                        any = true
                        hasQua = true; hasCub = false
                    }
                    if (!any) return fail("its path data is incomplete")
                }
                else -> return fail("freehand path command \"" + cmd.uppercaseChar() + "\" is not supported yet")
            }
            // A command letter must follow (or the data ends): a number here would mean the group
            // above stopped early, which the per-command loops already consume fully — but a stray
            // number after Z-less data with cmd reset cannot occur; guard anyway.
            if (hasNum() && cmd == ' ') return fail("its path data could not be read")
        }
        if (segs.isEmpty()) return fail("its path has no points")
        return segs
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
        val blendMode = amBlendMode(el, w)
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
            isVisible = !isMaskLayer(el),
            isMask = isMaskLayer(el),
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
            blendMode = blendMode,
            keyframes = keyframes
        )
        // A line's outline is its segment (see PathSpec); the points are the raw local ones.
        ctx.outlineSpecs[built.id] = PathSpec(
            kind = "LINE", startMs = built.startMs, durMs = built.durationMs,
            loc = tr.loc ?: Track(listOf(ctx.sceneW / 2f, ctx.sceneH / 2f), emptyList()),
            scl = tr.scl, rot = tr.rot, p1 = p1, p2 = p2, pivot = pivV
        )
        return applyLayerEffects(el, built, ctx, durMs, FxTarget.SHAPE)
    }

    /**
     * SCALE keyframes of a shape: the rendered width AND height (2 x size x scale — size is a half extent)
     * against the app's shape box, as the keyframes' `a` / `b`. Both components are animated: until build 93
     * only the width was stored (b = 0) and the height followed the first keyframe's aspect, which drew a
     * keyframed pill (ST Intro's 494x100 roundrect) as a square disk. A `b` of 0 keeps its old meaning
     * (uniform: the height follows the width) for keyframes the editor / presets write.
     */
    private fun composedSizeKeys(sizeT: Track?, scaleT: Track?, durMs: Long, box: Float): List<Keyframe> {
        val times = ((sizeT?.keys ?: emptyList()) + (scaleT?.keys ?: emptyList())).map { it.t }.distinct().sorted()
        if (times.size < 2) return emptyList()
        fun widthAt(t: Float): Float {
            val sz = sizeT?.valueAt(t) ?: listOf(100f, 100f)
            val sc = scaleT?.valueAt(t) ?: listOf(1f, 1f)
            return 2f * (sz.getOrNull(0) ?: 100f) * (sc.getOrNull(0) ?: 1f) / box * 100f
        }
        fun heightAt(t: Float): Float {
            val sz = sizeT?.valueAt(t) ?: listOf(100f, 100f)
            val sc = scaleT?.valueAt(t) ?: listOf(1f, 1f)
            val sy = sc.getOrNull(1) ?: sc.getOrNull(0) ?: 1f
            return 2f * (sz.getOrNull(1) ?: sz.getOrNull(0) ?: 100f) * sy / box * 100f
        }
        return times.mapIndexed { i, t ->
            val next = times.getOrNull(i + 1)
            val e = if (next == null) Easing.LINEAR
            else sizeT?.keyAt(next)?.easing ?: scaleT?.keyAt(next)?.easing ?: Easing.LINEAR
            Keyframe((t.coerceIn(0f, 1f) * durMs).roundToLong(), widthAt(t), heightAt(t), e)
        }.distinctBy { it.timeMs }
    }

    /**
     * The app blend mode an Alight layer's `blending` attribute maps to (the clip's `blendMode` name).
     * Every value the renderers can honour is mapped — the standard W3C family (AM's own recovered
     * blend shaders agree with the W3C formulas for those) plus AM's two own modes, Subtract and
     * Soft Overlay, whose shaders were recovered from the public decompiled AM assets and are now
     * implemented in the native blend pass. `blending="mask"` is not a blend: whether the mask can
     * be applied is only known once the scene's layers are read (it cuts the layer below it) —
     * resolveMaskLinks reports the outcome, so it maps to Normal here without a warning. A value
     * with no counterpart (divide, linear-burn, vivid-light, ...) keeps the old warning verbatim.
     */
    private fun amBlendMode(el: Element, w: MutableList<String>): String {
        val b = el.getAttribute("blending")
        if (b.isEmpty() || b == "mask") return "Normal"
        return when (b) {
            "normal" -> "Normal"
            "darken" -> "Darken"
            "multiply" -> "Multiply"
            "color-burn" -> "Color Burn"
            "lighten" -> "Lighten"
            "screen" -> "Screen"
            "color-dodge" -> "Color Dodge"
            "linear-dodge", "add" -> "Add"
            "overlay" -> "Overlay"
            "soft-light" -> "Soft Light"
            "hard-light" -> "Hard Light"
            "soft-overlay" -> "Soft Overlay"
            "difference" -> "Difference"
            "exclude", "exclusion" -> "Exclusion"
            "subtract" -> "Subtract"
            "hue" -> "Hue"
            "saturation" -> "Saturation"
            "color" -> "Color"
            "luminosity", "luminance" -> "Luminosity"
            else -> {
                w += "Layer blending \"" + b + "\"" + labelOf(el) + " is not supported yet: imported as a normal layer"
                "Normal"
            }
        }
    }

    /** True when the layer is an Alight mask (`blending="mask"`): it must not be drawn itself. */
    private fun isMaskLayer(el: Element): Boolean = el.getAttribute("blending") == "mask"

    // ---- groups (embedScene) ------------------------------------------------------------------------------------

    /**
     * An `<embedScene>` -> the clips of its nested scene (times remapped into the parent's timeline) plus a real
     * Group layer on top of them. The nested layers keep their own positions: they follow the group through a
     * [ParentLink] (see ParentRig), measured around the embed's pivot.
     *
     * Alight's group composition, pinned by Sarowar's build-91 phone comparison of ST Intro (the importer used
     * to store the pivot as an absolute child-scene point — ref = pivot − centre — which threw every child
     * ~half a frame toward the bottom-right; measured error on "Logo Here" was (+365, +205) px at scale 0.38,
     * exactly (scene centre × group scale)): the XML `location` is where the nested scene's CENTRE sits in the
     * parent, and `<pivot>` is the anchor's offset from that centre (the corpus values cluster around 0, e.g.
     * (7.5, −8.5); (−960, −540) is the top-left corner). The transform acts about the anchor, so the group
     * clip's stored pose is the anchor itself (location + pivot, the pivot in the parent's frame) and the
     * link's ref point is the pivot in the children's own (centre-relative) frame: at rest ParentRig then
     * gives world = location + pivot + R·S·(child − pivot), which reproduces both measured ST Intro groups
     * to under a pixel (the pill at (961, 539), the "Logo Here" mask at the frame centre).
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
            // Positions and sizes inside are exact (an embedded scene draws 1:1, centre at the
            // embed's location — see Ctx.shapeBoxPx); what is NOT reproduced is Alight's clipping
            // of the content to the embedded scene's own canvas rect.
            w += "Group" + labelOf(el) + ": its canvas (" + nW.toInt() + "x" + nH.toInt() +
                ") differs from the canvas it sits in: layers inside are not clipped to it"
        }
        val content = readSceneContent(nested, Ctx(nW, nH, ctx.previewW, ctx.rootW, nTotal,
            nested.getAttribute("fps").toFloatOrNull()?.takeIf { it > 0f } ?: ctx.fps, w))

        val startMs = el.getAttribute("startTime").toLongOrNull() ?: 0L
        val endMs = el.getAttribute("endTime").toLongOrNull() ?: ctx.totalMs
        val durMs = (endMs - startMs).coerceAtLeast(100L)
        // An unset outTime is written as a sentinel near Long.MIN_VALUE (ST Intro's "Logo Here":
        // -2147482782, the Graph file's "Week": -2147483215). The window is then the whole nested
        // scene, fitted to the embed's span like any other window — before this, the sentinel
        // silently read as factor 1 and the children ended early, leaving the group's tail empty
        // (the ST Intro logo vanished at 7165 of its 8032 ms group).
        val inT = (el.getAttribute("inTime").toLongOrNull() ?: 0L).coerceAtLeast(0L)
        val outRaw = el.getAttribute("outTime").toLongOrNull()
        // AM's second outTime form is WRAPPED: the window's end measured back from the scene's end
        // and wrapped at Int.MAX_VALUE (out = realOut - totalTime + Int.MAX_VALUE). It is written
        // when a trimmed window ends before the scene does — all 20 corpus instances decode to
        // exactly inTime + the embed's own length (factor 1): rax-preset-249's 19 "Edit Group"
        // slices (inTime == startTime each) and likenya's "Group 2 Copy 4" (window [0, 499] of its
        // 949 ms scene). Read plainly, the value is ~2.1e9 ms, the factor collapses to ~0 and
        // remapClip drops the whole subtree as sub-millisecond clips — those 20 groups imported
        // EMPTY (likenya also lost the 12 group clips nested inside the dropped subtree). A plain
        // outTime can never sit in the wrapped band (it would end a window ~24 days past the
        // scene's end; every plain corpus value is at most ~26 s), so the band is safe to decode.
        // Plain values past the scene's end (the mild "outTime == the embed's length" writes,
        // e.g. Test-1's "Text") stay read as written: they stretch by at most a few percent and
        // lose nothing.
        val outWrapped = if (outRaw != null && nTotal > 0L && outRaw > nTotal &&
            outRaw <= Int.MAX_VALUE.toLong() && outRaw >= Int.MAX_VALUE.toLong() - nTotal
        ) outRaw - Int.MAX_VALUE.toLong() + nTotal else null
        val outT = when {
            outRaw == null || outRaw <= inT -> nTotal
            outWrapped != null && outWrapped > inT -> outWrapped
            else -> outRaw
        }
        val factor = if (outT > inT) (endMs - startMs).toDouble() / (outT - inT).toDouble() else 1.0
        if (abs(factor - 1.0) > 1e-4) w += "Group" + labelOf(el) + ": its timing was stretched to fit its length in the timeline"

        val tr = readTransform(el, w, pivotUsed = true)
        val pivotV = tr.pivot?.first() ?: listOf(0f, 0f)
        if (tr.pivot != null && tr.pivot.keys.size >= 2) {
            w += "Keyframed pivot of a group is not supported yet: the first value is used"
        }

        val groupId = UUID.randomUUID().toString()
        // The pivot in stored (preview) units: the link's ref point in the children's centre-relative
        // frame, and the offset of the group's own stored pose (the anchor) in the parent's frame.
        val pivotX = (pivotV.getOrNull(0) ?: 0f) * ctx.k
        val pivotY = (pivotV.getOrNull(1) ?: 0f) * ctx.k
        val link = ParentLink(groupId, pivotX, pivotY, 100f, 0f)
        val directIds = content.direct.mapTo(HashSet()) { it.id }
        val remapped = content.flat.mapNotNull { c ->
            // A direct layer may already carry an Alight parent link (to a null inside the group):
            // its chain tops out at a direct layer the group link lands on, so only unlinked direct
            // layers take the group link itself; the groupId is set either way.
            val linked = if (c.id in directIds) {
                if (c.parent == null) c.copy(groupId = groupId, parent = link) else c.copy(groupId = groupId)
            } else c
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
        keys(AnimProp.POSITION, tr.loc) { (ctx.posX(it) + pivotX) to (ctx.posY(it) + pivotY) }
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
            transformX = ctx.posX(tr.loc?.first() ?: emptyList()) + pivotX,
            transformY = ctx.posY(tr.loc?.first() ?: emptyList()) + pivotY,
            embedW = nW * ctx.k,
            embedH = nH * ctx.k,
            scalePercent = scalePct(tr.scl?.first() ?: listOf(1f, 1f)),
            rotationDeg = tr.rot?.first()?.firstOrNull() ?: 0f,
            opacity = ((tr.opa?.first()?.firstOrNull() ?: 1f) * 100f).coerceIn(0f, 100f),
            blendMode = amBlendMode(el, w),
            keyframes = keyframes
        )
        val groupFx = applyLayerEffects(el, group, ctx, durMs, FxTarget.GROUP)
        return (remapped + groupFx) to groupFx
    }

    /**
     * A `<drawing>` layer: Alight's freehand vector drawing — a list of `<stroke>` children, each a
     * sampled polyline (`points` = "x, y, pressure" triples in the layer's own frame, offsets from
     * its location) with its own colour / width / type. It imports as a GROUP layer (carrying the
     * drawing's own transform, span, blending and effects) holding one PATH-shape child per stroke,
     * in stroke order: a `pen` / `brush` stroke is an open stroked polyline, a `fill` stroke is its
     * outline filled (closed). A brush's width follows the points' pressure in Alight; the app draws
     * a constant width — every corpus point has pressure 1.00 (where constant IS exact), and a
     * varying pressure is reported rather than faked. The children live in the drawing's local frame
     * (offsets from its anchor), tied to the group by a zero pivot, so the group carries them
     * exactly; each child's stored position is its outline's box centre in that frame (the shared
     * path normalisation, [normalisePathSegs]).
     */
    private fun readDrawing(el: Element, ctx: Ctx): Pair<List<TimelineClip>, TimelineClip>? {
        val w = ctx.warnings
        val startMs = el.getAttribute("startTime").toLongOrNull() ?: 0L
        val endMs = el.getAttribute("endTime").toLongOrNull() ?: ctx.totalMs
        val durMs = (endMs - startMs).coerceAtLeast(100L)
        val label = el.getAttribute("label").ifBlank { "Drawing" }

        var pressureVaries = false
        val children = ArrayList<TimelineClip>()
        val strokeEls = elementChildren(el).filter { it.tagName == "stroke" }
        for ((index, st) in strokeEls.withIndex()) {
            val pts = ArrayList<Pair<Float, Float>>()
            for (p in st.getAttribute("points").split(';')) {
                val f = p.split(',').map { it.trim().toFloatOrNull() }
                val x = f.getOrNull(0)
                val y = f.getOrNull(1)
                if (x == null || y == null) continue
                val pr = f.getOrNull(2)
                if (pr != null && abs(pr - 1f) > 1e-6f) pressureVaries = true
                pts += x to y
            }
            if (pts.size < 2) continue
            val type = st.getAttribute("type")
            if (type != "pen" && type != "brush" && type != "fill") {
                w += "Drawing" + labelOf(el) + ": stroke type \"" + type + "\" is not supported yet: drawn as a pen stroke"
            }
            val filled = type == "fill"
            val segs = ArrayList<PathSeg>()
            segs += PathSeg(PATH_OP_MOVE, listOf(pts[0].first, pts[0].second))
            for (i in 1 until pts.size) segs += PathSeg(PATH_OP_LINE, listOf(pts[i].first, pts[i].second))
            if (filled) segs += PathSeg(PATH_OP_CLOSE, emptyList())
            val norm = normalisePathSegs(segs) ?: continue
            val color = parseColor(st.getAttribute("color")) ?: 0xFFFFFFFFL
            val width = st.getAttribute("width").toFloatOrNull() ?: 0f
            val box = ctx.shapeBoxPx
            val rendW = 2f * norm.half
            // Same stroke rule as the path shapes: the stored dp is the width's share of the
            // outline's rendered width (the app scales a stroke with its layer).
            val strokeWidth = if (!filled && width > 0f && rendW > 0f) APP_SHAPE_BOX_DP * width / rendW else 0f
            children += TimelineClip(
                id = UUID.randomUUID().toString(),
                name = if (strokeEls.size > 1) label + " " + (index + 1) else label,
                type = LayerType.SHAPE,
                startMs = startMs.coerceAtLeast(0L),
                durationMs = durMs,
                color = SHAPE_COLOR,
                textColor = color,
                fillMode = if (filled) FILL_SOLID else FILL_NONE,
                shapePattern = "PATH",
                pathSegments = norm.segments,
                shapeScaleX = rendW / box,
                shapeScaleY = rendW / box,
                strokeWidth = strokeWidth,
                strokeColor = color,
                transformX = norm.cx * ctx.k,
                transformY = norm.cy * ctx.k,
                shapeOffsetX = norm.cx * ctx.k,
                shapeOffsetY = norm.cy * ctx.k,
                scalePercent = rendW / box * 100f,
                opacity = 100f
            )
        }
        if (children.isEmpty()) {
            w += "Layer <drawing>" + labelOf(el) + ": it has no drawable strokes: skipped"
            return null
        }
        if (pressureVaries) {
            w += "Drawing" + labelOf(el) + ": its brush pressure is not reproduced (a constant width is drawn)"
        }

        val tr = readTransform(el, w, pivotUsed = false)
        val groupId = UUID.randomUUID().toString()
        val link = ParentLink(groupId, 0f, 0f, 100f, 0f)
        val linked = children.map { it.copy(groupId = groupId, parent = link) }

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
            name = label,
            type = LayerType.GROUP,
            startMs = startMs.coerceAtLeast(0L),
            durationMs = durMs,
            color = GROUP_COLOR,
            transformX = ctx.posX(tr.loc?.first() ?: emptyList()),
            transformY = ctx.posY(tr.loc?.first() ?: emptyList()),
            scalePercent = scalePct(tr.scl?.first() ?: listOf(1f, 1f)),
            rotationDeg = tr.rot?.first()?.firstOrNull() ?: 0f,
            opacity = ((tr.opa?.first()?.firstOrNull() ?: 1f) * 100f).coerceIn(0f, 100f),
            blendMode = amBlendMode(el, w),
            keyframes = keyframes
        )
        val groupFx = applyLayerEffects(el, group, ctx, durMs, FxTarget.GROUP)
        return (linked + groupFx) to groupFx
    }

    /**
     * A `<camera>` layer: Alight's scene camera. While it is active, the layers below it in the
     * stack are seen through it — which is exactly the app's own Camera layer (see CameraRig: an
     * invisible layer; the layers in the lanes below it are seen through it, the nearest active
     * camera above a layer wins), so the import is a pose conversion, not a new renderer path.
     * AM's camera centres on (location.x, location.y) in scene pixels, sits at distance
     * |location.z| from the scene plane, and has a vertical fov: the visible height at the z=0
     * plane is 2 * |z| * tan(fov/2), so the app zoom is (sceneH/2) / (|z| * tan(fov/2)) — the
     * corpus cameras check out exactly (fov 60 at z = -779.42 on a 900-tall scene is zoom 1, and
     * the parked camera of proyek-baru-273 is a pure pan at zoom 1). The zoom is keyframed from
     * the location / fov / scale tracks at the union of their key times; the camera's own
     * rotation becomes the roll. Only perspective cameras import (all corpus instances); an
     * orthographic camera is skipped, and a focus blur / fog switched on is reported instead of
     * being approximated.
     */
    private fun readCamera(el: Element, ctx: Ctx): TimelineClip? {
        val w = ctx.warnings
        val type = el.getAttribute("type")
        if (type.isNotEmpty() && type != "perspective") {
            w += "Camera" + labelOf(el) + ": type \"" + type + "\" is not supported yet: skipped"
            return null
        }
        if (child(el, "focus-blur")?.getAttribute("enabled") == "true") {
            w += "Camera" + labelOf(el) + ": its focus blur is not reproduced"
        }
        if (child(el, "fog")?.getAttribute("enabled") == "true") {
            w += "Camera" + labelOf(el) + ": its fog is not reproduced"
        }
        if (elementChildren(el).any { it.tagName == "effect" }) {
            w += "Camera" + labelOf(el) + ": effects on a camera layer are not supported yet: skipped"
        }
        val startMs = el.getAttribute("startTime").toLongOrNull() ?: 0L
        val endMs = el.getAttribute("endTime").toLongOrNull() ?: ctx.totalMs
        val durMs = (endMs - startMs).coerceAtLeast(1L)
        val tr = readTransform(el, w, pivotUsed = false)
        val locT = tr.loc
        val fovT = child(el, "fov")?.let { readTrack(it) }
        // AM's default camera distance (fov 60 framing the scene's height) when a location has no z.
        val defZ = -(ctx.sceneH / 2f) / tan(Math.toRadians(30.0)).toFloat()
        fun zoomAt(t: Float): Float {
            val z = locT?.valueAt(t)?.getOrNull(2) ?: defZ
            val fov = fovT?.valueAt(t)?.firstOrNull() ?: 60f
            val scl = tr.scl?.valueAt(t)?.firstOrNull() ?: 1f
            val halfH = abs(z).coerceAtLeast(1f) * tan(Math.toRadians((fov / 2f).toDouble())).toFloat()
            return (ctx.sceneH / 2f) / halfH.coerceAtLeast(1e-3f) * scl * 100f
        }
        val keyframes = LinkedHashMap<AnimProp, List<Keyframe>>()
        val posKeys = trackKeys(locT, durMs) { ctx.posX(it) to ctx.posY(it) }
        if (posKeys.isNotEmpty()) keyframes[AnimProp.POSITION] = posKeys
        val rotKeys = trackKeys(tr.rot, durMs) { (it.firstOrNull() ?: 0f) to 0f }
        if (rotKeys.isNotEmpty()) keyframes[AnimProp.ROTATION] = rotKeys
        val times = ((locT?.keys ?: emptyList()) + (fovT?.keys ?: emptyList()) + (tr.scl?.keys ?: emptyList()))
            .map { it.t }.distinct().sorted()
        if (times.size >= 2) {
            val keys = times.mapIndexed { i, t ->
                val next = times.getOrNull(i + 1)
                val e = if (next == null) Easing.LINEAR
                else locT?.keyAt(next)?.easing ?: fovT?.keyAt(next)?.easing
                    ?: tr.scl?.keyAt(next)?.easing ?: Easing.LINEAR
                Keyframe((t.coerceIn(0f, 1f) * durMs).roundToLong(), zoomAt(t), 0f, e)
            }.distinctBy { it.timeMs }
            if (keys.size >= 2) keyframes[AnimProp.SCALE] = keys
        }
        return TimelineClip(
            id = UUID.randomUUID().toString(),
            name = el.getAttribute("label").ifBlank { "Camera" },
            type = LayerType.CAMERA,
            startMs = startMs.coerceAtLeast(0L),
            durationMs = durMs,
            color = 0xFF8E6BF2,
            transformX = ctx.posX(locT?.first() ?: emptyList()),
            transformY = ctx.posY(locT?.first() ?: emptyList()),
            scalePercent = zoomAt(0f),
            rotationDeg = tr.rot?.first()?.firstOrNull() ?: 0f,
            keyframes = keyframes
        )
    }

    // ---- null objects + layer parenting ---------------------------------------------------------------------------

    /**
     * A `<nullobj>` -> a Null layer: Alight's invisible anchor. It renders nothing; other layers name it in
     * their `parent` attribute and follow its motion (see [resolveParentLinks]). Its own transform is
     * imported like any layer's — location / scale / rotation, constant or keyframed — and its effects run
     * through the GROUP path of [applyLayerEffects], because the transform animators Alight rigs onto nulls
     * (oscillate / swing / pulsate — the classic shake rig: 33 of the corpus's 38 effects on nulls) bake
     * into those tracks there, and the baked motion is exactly what the children must follow. Picture
     * effects on a null land on a clip that is never drawn, like on a group.
     */
    private fun readNull(el: Element, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val startMs = el.getAttribute("startTime").toLongOrNull() ?: 0L
        val endMs = el.getAttribute("endTime").toLongOrNull() ?: ctx.totalMs
        val durMs = (endMs - startMs).coerceAtLeast(1L)
        val tr = readTransform(el, w, pivotUsed = false)
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
        val built = TimelineClip(
            id = UUID.randomUUID().toString(),
            name = el.getAttribute("label").ifBlank { "Null" },
            type = LayerType.NULL,
            startMs = startMs.coerceAtLeast(0L),
            durationMs = durMs,
            color = NULL_COLOR,
            transformX = ctx.posX(tr.loc?.first() ?: emptyList()),
            transformY = ctx.posY(tr.loc?.first() ?: emptyList()),
            scalePercent = scalePct(tr.scl?.first() ?: listOf(1f, 1f)),
            rotationDeg = tr.rot?.first()?.firstOrNull() ?: 0f,
            opacity = ((tr.opa?.first()?.firstOrNull() ?: 1f) * 100f).coerceIn(0f, 100f),
            keyframes = keyframes
        )
        return applyLayerEffects(el, built, ctx, durMs, FxTarget.GROUP)
    }

    /**
     * Alight layer parenting: a layer's `parent` attribute names another layer of the SAME scene whose
     * motion it follows. Corpus census (58 files): 135 parented layers in 14 files — children are shapes
     * (103), groups (27), texts (2) and nulls themselves (3; chains up to 3 links above the child);
     * parents are `<nullobj>` anchors (130) and visible shapes (5). Every parent id resolves inside its
     * own scene.
     *
     * Alight's stored child values are LOCAL to the parent: the child's `location` is the offset from the
     * parent's anchor point, in the parent's own units — pinned numerically by dys-prst-126, whose null
     * sits at the scene centre (540.17, 960.37) while its 37 children all store location (−0.17, −0.37):
     * parent + child = the scene centre the children render at. The world pose is the similarity
     * composition W = P ∘ L at every instant: pos = P.pos + R(P.rot)·S(P.scale)·L.pos, scale =
     * P.scale · L.scale, rot = P.rot + L.rot (every corpus parent scale is uniform, so no shear arises).
     *
     * [ParentRig] composes a parent's DELTA from a saved reference pose; a link whose reference is the
     * scene frame's origin — ref = (−sceneW/2, −sceneH/2) in stored units, refScale 100, refRot 0 — makes
     * that delta composition equal Alight's absolute one at every instant: the children's stored tracks
     * stay exactly what the readers made of the XML (their local values read as if scene-absolute, which
     * the ref subtraction undoes), and only the link is added. Chains compose recursively, because a
     * parented null carries the same style of link to its own parent and ParentRig evaluates the parent's
     * fully-adjusted pose. Inside an `<embedScene>`, the chain tops out at a direct layer of the nested
     * scene, which takes the group link as before (see [readGroup]).
     *
     * A parent that is itself a VISIBLE layer (the 5 corpus shapes) cannot be a ParentRig parent, so an
     * invisible Null anchor mirroring its evaluated transform (plain pose + position / scale / rotation
     * keyframes, after its effects baked) is created and the children link to that; the visible layer
     * renders as before, and the anchor inherits the layer's own parent link / group membership. A parent
     * naming a GROUP is not reproduced (no corpus instance does it) and a parent that was not imported
     * leaves the child unlinked — both are reported, not guessed.
     *
     * Returns the anchor clips created (they join the scene's direct clips so an enclosing group treats
     * them like the layers they mirror).
     */
    private fun resolveParentLinks(
        elements: List<Element>, directSlots: ArrayList<TimelineClip?>, flat: ArrayList<TimelineClip>, ctx: Ctx
    ): List<TimelineClip> {
        val w = ctx.warnings
        val refX = -ctx.sceneW / 2f * ctx.k
        val refY = -ctx.sceneH / 2f * ctx.k
        val byAmId = HashMap<String, TimelineClip>()
        for (i in elements.indices) {
            val c = directSlots.getOrNull(i) ?: continue
            val amId = elements[i].getAttribute("id")
            if (amId.isNotEmpty()) byAmId[amId] = c
        }
        fun replace(old: TimelineClip, new: TimelineClip) {
            for (i in directSlots.indices) if (directSlots[i]?.id == old.id) directSlots[i] = new
            val fi = flat.indexOfFirst { it.id == old.id }
            if (fi >= 0) flat[fi] = new
            val hit = byAmId.filterValues { it.id == old.id }.keys
            for (k in hit) byAmId[k] = new
        }
        fun link(child: TimelineClip, parentClip: TimelineClip) {
            replace(child, child.copy(parent = ParentLink(parentClip.id, refX, refY, 100f, 0f)))
        }
        val wanted = elements.indices.filter { elements[it].getAttribute("parent").isNotEmpty() }
        if (wanted.isEmpty()) return emptyList()
        // Pass 1: parents ParentRig can follow directly (Null layers). Groups are parked with a warning.
        for (i in wanted) {
            val child = directSlots.getOrNull(i) ?: continue   // a skipped layer needs no link
            val parentAmId = elements[i].getAttribute("parent")
            val parentClip = byAmId[parentAmId]
            when {
                parentClip == null ->
                    w += "Layer \"" + child.name + "\": its Alight parent (id " + parentAmId +
                        ") was not imported: the layer keeps its own motion"
                parentClip.id == child.id ->
                    w += "Layer \"" + child.name + "\" is its own parent in Alight: ignored"
                parentClip.type == LayerType.NULL -> link(child, parentClip)
                parentClip.type == LayerType.GROUP ->
                    w += "Layer \"" + child.name + "\" is parented to a group in Alight: parenting to a " +
                        "group is not reproduced yet: the layer keeps its own motion"
                else -> Unit   // a visible layer as parent: pass 2
            }
        }
        // Pass 2: a visible layer as parent — mirror its evaluated transform onto an invisible anchor.
        val anchors = ArrayList<TimelineClip>()
        val anchorByParentId = HashMap<String, TimelineClip>()
        for (i in wanted) {
            val child = directSlots.getOrNull(i) ?: continue
            if (child.parent != null) continue
            val parentClip = byAmId[elements[i].getAttribute("parent")] ?: continue
            if (parentClip.type == LayerType.NULL || parentClip.type == LayerType.GROUP) continue
            if (parentClip.id == child.id) continue
            val anchor = anchorByParentId.getOrPut(parentClip.id) {
                val a = TimelineClip(
                    id = UUID.randomUUID().toString(),
                    name = parentClip.name + " (parent)",
                    type = LayerType.NULL,
                    startMs = parentClip.startMs,
                    durationMs = parentClip.durationMs,
                    color = NULL_COLOR,
                    transformX = parentClip.transformX,
                    transformY = parentClip.transformY,
                    scalePercent = parentClip.scalePercent,
                    rotationDeg = parentClip.rotationDeg,
                    opacity = 100f,
                    keyframes = parentClip.keyframes.filterKeys {
                        it == AnimProp.POSITION || it == AnimProp.SCALE || it == AnimProp.ROTATION
                    },
                    parent = parentClip.parent,
                    groupId = parentClip.groupId
                )
                anchors += a
                flat += a
                w += "Layer \"" + parentClip.name + "\" parents other layers in Alight: an invisible " +
                    "anchor (\"" + a.name + "\") carries its motion for them"
                a
            }
            link(child, anchor)
        }
        return anchors
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

    /**
     * The value at normalized time [t] with Alight's easing applied: the easing of a segment sits on
     * the keyframe it ENDS at (see the class comment), so the blend factor is shaped by the second
     * keyframe's easing. [Track.valueAt] blends linearly; the procedural bakes that sample a track
     * whose easing carries the motion (a `movealongpath3` progress, a path layer's own move) use this.
     */
    private fun Track.valueAtEased(t: Float): List<Float> {
        if (keys.isEmpty()) return constant
        if (t <= keys.first().t) return keys.first().v
        if (t >= keys.last().t) return keys.last().v
        var i = 0
        while (i < keys.size - 2 && keys[i + 1].t <= t) i++
        val a = keys[i]
        val b = keys[i + 1]
        val span = (b.t - a.t).coerceAtLeast(1e-6f)
        var f = ((t - a.t) / span).coerceIn(0f, 1f)
        b.easing?.let { f = it.apply(f) }
        return List(maxOf(a.v.size, b.v.size)) { j ->
            val va = a.v.getOrElse(j) { a.v.lastOrNull() ?: 0f }
            val vb = b.v.getOrElse(j) { b.v.lastOrNull() ?: 0f }
            va + (vb - va) * f
        }
    }

    private fun Track.scalarAtEased(t: Float): Float = valueAtEased(t).firstOrNull() ?: 0f

    /** A packed colour value (see [parseValue]) back to an ARGB Long. */
    private fun unpacked(v: List<Float>): Long = unpackColor(v.getOrNull(0) ?: 0f, v.getOrNull(1) ?: 0f)

    /**
     * Every `<effect>` of a layer the readers did not consume themselves, in document order (Alight applies
     * them in order too: a later fill effect wins over an earlier one). An effect with `hidden="true"` is
     * DISABLED in Alight — it renders nothing there — so it is skipped here like Alight skips it (passes
     * 1–3 imported the corpus's 16 hidden effects as if active); the layer gets ONE warning listing them.
     */
    private fun applyLayerEffects(el: Element, base: TimelineClip, ctx: Ctx, durMs: Long, target: FxTarget): TimelineClip {
        var clip = base
        val hiddenSkipped = ArrayList<String>()
        for (fx in elementChildren(el).filter { it.tagName == "effect" }) {
            val id = fx.getAttribute("id")
            if (fx.getAttribute("hidden") == "true") {
                hiddenSkipped += id.substringAfterLast('.')
                continue
            }
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
                EDGEGLOW -> applyGlow(fx, clip, ctx)
                DARKGLOW -> applyDarkGlowAm(fx, clip, ctx)
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
                MOTIONBLUR, MOTIONBLUR2, MOTIONBLUR3, MOTIONBLUR4 -> applyMotionBlur(fx, clip, ctx)
                LENSBLUR2 -> applyLensBlur(fx, clip, ctx)
                SHARPENFX -> applySharpenFx(fx, clip, ctx)
                PINCHBULGE, PINCHBULGE2 -> applyPinchBulge(fx, clip, ctx)
                PULSATE -> applyPulsate(fx, clip, ctx, durMs)
                TRANSFORMFX -> applyTransformFx(fx, clip, ctx, durMs)
                LIFT -> applyLift(fx, clip, ctx)
                REPEAT -> applyRepeat(fx, clip, ctx)
                REPEAT_RADIAL -> applyRepeatRadial(fx, clip, ctx)
                REPEAT_SCATTER -> applyRepeatScatter(fx, clip, ctx)
                MOVEALONGPATH -> applyMoveAlongPath(fx, el, clip, ctx)
                LONGSHADOW, LONGSHADOW2 -> applyLongShadow(fx, clip, ctx, id)
                RAYS -> applyRays(fx, clip, ctx)
                COLORTUNE2 -> applyColorTune(fx, clip, ctx)
                MIRROR -> applyMirror(fx, clip, ctx)
                GLOWSCAN -> applyGlowScan(fx, clip, ctx)
                LUMAKEY3 -> applyLumaKey(fx, clip, ctx)
                FOURCOLORGRADIENT -> applyFourColorGradient(fx, clip, ctx)
                TURBULENTDISPLACE -> applyTurbulentDisplace(fx, clip, ctx)
                STRETCHSEGMENT -> applyStretchSegment(fx, clip, ctx)
                SPIN -> applySpin(fx, clip, ctx, durMs)
                REPLACECOLOR -> applyReplaceColor(fx, clip, ctx)
                SWIRL4, SWIRL3 -> applySwirl(fx, clip, ctx, id)
                HALFTONEDOTS -> applyHalftoneDots(fx, clip, ctx)
                PIXELATE2 -> applyPixelate(fx, clip, ctx)
                SHAKE_V1 -> applyShakeV1(fx, clip, ctx, durMs)
                BRIGHTCONT_V1 -> applyBrightCont(fx, clip, ctx)
                CHROMAKEY -> applyChromaKeyAm(fx, clip, ctx)
                COLORBALANCE -> applyColorBalanceAm(fx, clip, ctx)
                COLORTEMPERATURE -> applyColorTempAm(fx, clip, ctx)
                COLORTINT -> applyColorTintAm(fx, clip, ctx)
                SPINBLUR2 -> applySpinBlurAm(fx, clip, ctx)
                OFFSETFX -> applyOffsetAm(fx, clip, ctx)
                RADIALWIPE -> applyRadialWipeAm(fx, clip, ctx)
                FINDEDGES -> applyFindEdgesAm(fx, clip, ctx)
                HEXSHIFT -> applyHexShiftAm(fx, clip, ctx)
                SOFTGLOW -> applySoftGlowAm(fx, clip, ctx)
                FILLBEHIND -> applyFillBehindAm(fx, clip, ctx)
                RANDOMDISPLACE -> applyRandomDisplace(fx, clip, ctx, durMs)
                WAVEWARP2 -> applyWaveWarpAm(fx, clip, ctx)
                CIRCULARRIPPLE2, CIRCULARRIPPLE -> applyCircularRippleAm(fx, clip, ctx)
                STRETCH2, STRETCHV1 -> applyStretchAm(fx, clip, ctx, id)
                SQUEEZE -> applySqueezeAm(fx, clip, ctx)
                FRACTALWARP4 -> applyFractalWarpAm(fx, clip, ctx)
                STRIPES -> applyStripesAm(fx, clip, ctx)
                TURBULENTDISPLACE3 -> applyTurbulentDisplace3Am(fx, clip, ctx)
                ROUGHENEDGES -> applyRoughenEdgesAm(fx, clip, ctx)
                STREAKS -> applyStreaksAm(fx, clip, ctx)
                COLORHOT -> applyColorHotAm(fx, clip, ctx)
                COLORIZE -> applyColorizeAm(fx, clip, ctx)
                HIGHLIGHTSHADOW -> applyHighlightShadowAm(fx, clip, ctx)
                SPOTCOLOR2 -> applySpotColorAm(fx, clip, ctx)
                DISSOLVE -> applyDissolveAm(fx, clip, ctx)
                SIMPLESTARS2 -> applySimpleStarsAm(fx, clip, ctx)
                RIDGES2, RIDGES -> applyRidgesAm(fx, clip, ctx, id)
                DOTS2 -> applyDotsAm(fx, clip, ctx)
                BENDWARP -> applyBendWarpAm(fx, clip, ctx)
                DISPLACEMAP3, DISPLACEMAP2 -> applyDisplaceMapAm(fx, clip, ctx)
                DISPLACEMAPRAD -> applyDisplaceMapRadAm(fx, clip, ctx)
                SMOOTHBEVEL -> applySmoothBevelAm(fx, clip, ctx)
                LIGHTGLOW -> applyLightGlowAm(fx, clip, ctx)
                INNERGLOW -> applyInnerGlowAm(fx, clip, ctx)
                COUNTER -> {
                    // A text effect (its number counts the layer's characters): on any other
                    // layer Alight shows nothing either, so this is a note, not a gap.
                    ctx.warnings += "Effect counter only affects text layers: skipped"
                    clip
                }
                else -> {
                    ctx.warnings += "Effect " + id.substringAfterLast('.') + " is not supported yet: skipped"
                    clip
                }
            }
        }
        if (hiddenSkipped.isNotEmpty()) {
            ctx.warnings += "Layer \"" + base.name + "\": hidden effects are disabled in Alight Motion: skipped (" +
                hiddenSkipped.joinToString(", ") + ")"
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
     * layer the trimmed path is the glyphs' outlines: the clip is flagged `strokeTrimText` and the renderers
     * draw those outlines (in the text colour) instead of the fill. On a group layer there is nothing to trim,
     * and a shape without a stroke has no outline to draw on: both stay warnings.
     */
    private fun applyDrawingProgress(fx: Element, clip0: TimelineClip, ctx: Ctx, durMs: Long, target: FxTarget): TimelineClip {
        val w = ctx.warnings
        if (target == FxTarget.GROUP) {
            w += "Effect drawing.progress is only supported on shape and text layers yet: skipped"
            return clip0
        }
        if (target == FxTarget.SHAPE && clip0.strokeWidth <= 0f && !clip0.strokeTrimFill) {
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
            strokeTrimEnd = frac(endT, 0f, 100f),
            strokeTrimText = target == FxTarget.TEXT
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
     * `com.alightcreative.solidcolor`: the layer's PICTURE is flattened to one colour at render
     * time — the Solid Color effect (kFxColor mode 16) replaces the picture's colours and keeps
     * its alpha silhouette, so a media fill keeps its picture's shape (and a picture that is not
     * in the project stays invisible, exactly as in Alight), a stroke-only layer's stroke is
     * tinted, and a group flattens its composed picture (ST Intro's "Shadow" pass). Before build
     * 110 the importer instead replaced the layer's fill with the colour, which painted a media
     * shape's whole rectangle (the lavender square of round 5) and skipped groups outright.
     * A constant colour rides the effect's values; a keyframed one (the file "3" Outline:
     * white -> green) ALSO keeps the app's FILL_COLOR track, which [SolidColorEval] resolves per
     * frame — the effect's values then hold the first colour and the follows-track flag. The
     * effect's `alpha` multiplies the layer opacity.
     */
    private fun applySolidColor(fx: Element, clip0: TimelineClip, ctx: Ctx, durMs: Long, target: FxTarget): TimelineClip {
        val w = ctx.warnings
        var clip = clip0
        val props = fxProps(fx)
        val blend = props["blendMode"]?.scalarAt(0f)?.toInt() ?: 0
        if (blend != 0) w += "Effect solidcolor: blend mode " + blend + " is not supported yet: imported as normal"
        val colorT = props["color"]
        if (colorT != null) {
            val argb = unpacked(colorT.first())
            val r = ((argb shr 16) and 0xFF).toFloat()
            val g = ((argb shr 8) and 0xFF).toFloat()
            val b = (argb and 0xFF).toFloat()
            if (colorT.keys.size >= 2) {
                val keys = trackKeys(colorT, durMs) { (it.getOrNull(0) ?: 0f) to (it.getOrNull(1) ?: 0f) }
                clip = clip.copy(
                    textColor = argb,
                    keyframes = clip.keyframes + (AnimProp.FILL_COLOR to keys)
                )
                clip = addFx(clip, ctx, ClipFxLibrary.SOLID_COLOR, listOf(r, g, b, 100f, 1f), "solidcolor")
            } else {
                clip = addFx(clip, ctx, ClipFxLibrary.SOLID_COLOR, listOf(r, g, b, 100f, 0f), "solidcolor")
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
     * The geometry is the def's own shader maths (recovered build 120 from the public dump,
     * boiniArun2006/am-xml `assets/effects/gradientoverlay2.xml`): in layer space the gradient is
     * centred at C = (0.5 + offset.x / 500, 0.5 + offset.y / 500) (AM's effect point params run 500 to
     * the layer box, y down as stored); a linear gradient's t grows along (sin a, -cos a) — angle 0
     * points UP, 90 right — with t = 0 / 1 at C -/+ that direction x scale/2; a radial gradient's t is
     * twice the centre distance / scale; a sweep's t runs clockwise (screen) from the (-cos a, -sin a)
     * ray. What the app's model cannot say is reported: the effect's own alpha and a non-normal blend
     * mode (in Alight the gradient composites OVER the layer's fill; the app's gradient REPLACES it),
     * keyframed geometry, and the sweep's wrap (AM jumps from colour 2 back to colour 1, the app's
     * sweep blends back).
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
        val angle = props["angle"]?.firstValue(w, "gradientoverlay", "angle") ?: 0f
        val scale = props["scale"]?.firstValue(w, "gradientoverlay", "scale") ?: 1f
        val offT = props["offset"]
        if (offT != null && offT.keys.size >= 2) {
            w += "Effect gradientoverlay: keyframed offset is not supported yet: the first value is used"
        }
        val off = offT?.first() ?: emptyList()
        val cx = 0.5f + (off.getOrNull(0) ?: 0f) / 500f
        val cy = 0.5f + (off.getOrNull(1) ?: 0f) / 500f
        val rad = Math.toRadians(angle.toDouble())
        val half = 0.5f * scale
        clip = when (gradType) {
            GRAD_RADIAL -> clip.copy(gradX1 = cx, gradY1 = cy, gradX2 = cx + half, gradY2 = cy)
            GRAD_SWEEP -> {
                w += "Effect gradientoverlay: its sweep wraps from colour 2 back to colour 1; the app's sweep blends back instead"
                clip.copy(
                    gradX1 = cx, gradY1 = cy,
                    gradX2 = cx - cos(rad).toFloat() * half, gradY2 = cy - sin(rad).toFloat() * half
                )
            }
            else -> {
                val dx = sin(rad).toFloat()
                val dy = -cos(rad).toFloat()
                clip.copy(
                    gradX1 = cx - dx * half, gradY1 = cy - dy * half,
                    gradX2 = cx + dx * half, gradY2 = cy + dy * half
                )
            }
        }
        props["alpha"]?.let { aT ->
            if (aT.keys.size >= 2 || abs(aT.scalarAt(0f) - 1f) > 1e-4f) {
                w += "Effect gradientoverlay: its alpha is not supported yet: the gradient replaces the fill"
            }
        }
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
                val base = baseKeys?.valueAt(t)
                // A shape's SCALE keys carry its height in `b` (see composedSizeKeys): the multiplier
                // scales both, with the static height as the fallback when there are no base keys.
                val baseA = base?.first ?: clip0.scalePercent
                val baseB = base?.second ?: if (clip0.type == LayerType.SHAPE) clip0.shapeScaleY * 100f else 0f
                val f = scaleT.scalarAt(if (durMs > 0L) t.toFloat() / durMs else 0f)
                Keyframe(t, baseA * f, baseB * f, Easing.LINEAR)
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
            clip = clip.copy(keyframes = clip.keyframes + (AnimProp.SCALE to baseKeys.map { it.copy(a = it.a * f, b = it.b * f) }))
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
     * `edgeglow` -> the app's Glow. Edge glow blooms the layer's edges outwards in a colour;
     * the app's glow blooms the bright parts of the layer itself, so the mapping is: threshold 0..1 -> %,
     * spread -> Radius (spread ~1.5 reads as about half the app's radius range), strength 0..1 -> Intensity
     * %. The glow colour (the app's glow has none) and an inverted edge pass are reported. (darkglow used
     * to share this approximation; since build 120 it has its own native port, [applyDarkGlowAm]. The
     * edge glow's own def chain — a findedges pass plus a gaussianblur pass whose parameter wiring is
     * the host's, not the defs' — is what still keeps this one approximate.)
     */
    private fun applyGlow(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val short = "edgeglow"
        val props = fxProps(fx)
        val threshold = props["threshold"]?.firstValue(w, short, "threshold") ?: 0f
        val strength = props["strength"]?.firstValue(w, short, "strength") ?: 0f
        val spread = props["spread"]?.firstValue(w, short, "spread") ?: 0f
        val colorTrack = props["fillColor"]
        val anyNonWhite = colorTrack != null &&
            (listOf(colorTrack.constant) + colorTrack.keys.map { it.v }).any { unpacked(it) != 0xFFFFFFFFL }
        val aspects = ArrayList<String>()
        if (anyNonWhite) aspects += "its colour is not reproduced"
        if ((props["invert"]?.scalarAt(0f) ?: 0f) > 0.5f) aspects += "its invert is not reproduced"
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
     * `darkglow` -> the app's Dark Glow (Alight), from the effect's def (build 120): the layer's
     * dark parts (luma below `threshold`), tinted toward `color` by `blend`, blurred by a kernel
     * that runs off `strength` and the scene diagonal, multiplied back over the layer at `alpha`.
     * Defaults are the def's (strength 0.25, threshold 0.3, colour #FF5566, blend 0.25, alpha
     * 0.75); keyframed strength / threshold ride AmFxEval tails, the rest take first values.
     */
    private fun applyDarkGlowAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val strT = props["strength"]
        val str = (strT?.scalarAtEased(0f) ?: 0.25f) * 100f
        val thrT = props["threshold"]
        val thr = (thrT?.scalarAtEased(0f) ?: 0.3f) * 100f
        val color = props["color"]?.packedColor(w, "darkglow", "color") ?: 0xFF5566.toFloat()
        val blend = (props["blend"]?.firstValue(w, "darkglow", "blend") ?: 0.25f) * 100f
        val alpha = (props["alpha"]?.firstValue(w, "darkglow", "alpha") ?: 0.75f) * 100f
        val values = listOf(
            str.coerceIn(0f, 200f), thr.coerceIn(0f, 100f), color,
            blend.coerceIn(0f, 100f), alpha.coerceIn(0f, 100f), 0f, 0f
        ) + tailsOf(trackTail(strT), trackTail(thrT))
        return addFx(clip0, ctx, ClipFxLibrary.DARK_GLOW_AM, values, "darkglow")
    }

    /**
     * `gaussianblur` -> the app's Gaussian Blur. Alight's `strength` is 0..1; the app's Blurriness is
     * 0..100 and half of it already reads as a strong blur, so strength maps onto 0..50.
     */
    private fun applyGaussianBlur(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val props = fxProps(fx)
        val strengthT = props["strength"] ?: return clip0
        val strength = strengthT.scalarAtEased(0f)
        return addFx(
            clip0, ctx, ClipFxLibrary.BLUR,
            withTails(
                listOf((strength * 50f).coerceIn(0f, 100f)),
                tailsOf(trackTail(strengthT))
            ),
            "gaussianblur"
        )
    }

    /**
     * `longshadow` / `longshadow2` -> the app's Long Shadow: the layer's silhouette extruded along
     * `angle` (degrees, clockwise on screen: 45 = down-right, as in the presets) for `size` of the scene
     * height, tinted `color` at `alpha`, `solidity` between a soft and a hard extrusion, `fade` letting
     * it die out along its length. The app's length is % of the layer picture's height, so the scene-px
     * length is converted against the layer's box (for text the box is the scene: size x 100). The two
     * Alight ids carry the same properties (the corpus stacks both on one layer with different alpha /
     * solidity, as two shadows) and map the same way. A non-default `fadeType` or a `fill` below 1 has
     * no counterpart and is reported.
     */
    private fun applyLongShadow(fx: Element, clip0: TimelineClip, ctx: Ctx, id: String): TimelineClip {
        val w = ctx.warnings
        val short = id.substringAfterLast('.')
        val props = fxProps(fx)
        val sizeT = props["size"]
        val size = sizeT?.scalarAtEased(0f) ?: 0.02f
        val angle = props["angle"]?.firstValue(w, short, "angle") ?: 45f
        val alpha = props["alpha"]?.firstValue(w, short, "alpha") ?: 1f
        val solidity = props["solidity"]?.firstValue(w, short, "solidity") ?: 1f
        val fade = props["fade"]?.firstValue(w, short, "fade") ?: 0f
        val colorT = props["color"]
        if (colorT != null && colorT.keys.size >= 2) {
            w += "Effect " + short + ": keyframed color is not supported yet: the first value is used"
        }
        val packed = if (colorT != null) (unpacked(colorT.valueAt(0f)) and 0xFFFFFFL).toFloat() else 0f
        val aspects = ArrayList<String>()
        if ((props["fadeType"]?.scalarAt(0f)?.toInt() ?: 0) != 0) aspects += "its fade type is not reproduced"
        if ((props["fill"]?.scalarAt(0f) ?: 1f) < 0.999f) aspects += "its fill setting is not reproduced"
        if (aspects.isNotEmpty()) {
            w += "Effect " + short + " is approximated by the app's Long Shadow: " + aspects.joinToString(", ")
        }
        val boxH = layerBoxPx(clip0, ctx).second
        // The slot holds the length as a % of the layer's box height; a keyframed size rides the
        // tail PRE-CONVERTED by the same per-layer factor (constant across the layer), resolved
        // by [AmFxEval] at scale 1.
        fun lenPct(s: Float) = if (boxH > 1f) (s * ctx.sceneH / boxH * 100f).coerceIn(0f, 100f) else (s * 100f).coerceIn(0f, 100f)
        val ang = ((angle % 360f) + 360f) % 360f
        return addFx(
            clip0, ctx, ClipFxLibrary.LONG_SHADOW,
            withTails(
                listOf(
                    lenPct(size), ang,
                    (alpha * 100f).coerceIn(0f, 100f),
                    (solidity * 100f).coerceIn(0f, 100f),
                    (fade * 100f).coerceIn(0f, 100f),
                    packed
                ),
                tailsOf(trackTail(sizeT) { s -> lenPct(s) })
            ),
            short
        )
    }

    /**
     * `rays` -> the app's Light Rays, approximately: Alight blooms the parts of the layer at / above its
     * `threshold` (keyed against `thresholdColor`) in `fillColor` from `centerPoint`; the app's rays
     * smear the bright parts of the layer toward a centre and tint them one colour. The mapping:
     * threshold 0..1 -> %, strength -> Length (the corpus tops out at ~3, so x 33 fills the app's range),
     * intensity -> Intensity (a plain multiplier, x 100, capped at the app's 300), centerPoint (layer px
     * from the layer's centre, y down) -> Centre % of the layer's box, fillColor -> the tint's hue at
     * full amount — a black fill reads as "no tint" (a black tint would erase the rays it colours).
     * Alight's `quality` is a render-cost knob, not a look, and is dropped silently; a non-black
     * threshold colour and a blend mode are reported.
     */
    private fun applyRays(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val strengthT = props["strength"]
        val strength = strengthT?.scalarAtEased(0f) ?: 1f
        val intensity = props["intensity"]?.firstValue(w, "rays", "intensity") ?: 1f
        val threshold = props["threshold"]?.firstValue(w, "rays", "threshold") ?: 0f
        val cpT = props["centerPoint"]
        val cp = cpT?.valueAtEased(0f)
        val (boxW, boxH) = layerBoxPx(clip0, ctx)
        fun cxOf(x: Float) = if (boxW > 1f) (50f + x / boxW * 100f).coerceIn(0f, 100f) else 50f
        fun cyOf(y: Float) = if (boxH > 1f) (50f + y / boxH * 100f).coerceIn(0f, 100f) else 50f
        val cx = cxOf(cp?.getOrNull(0) ?: 0f)
        val cy = cyOf(cp?.getOrNull(1) ?: 0f)
        val fillT = props["fillColor"]
        if (fillT != null && fillT.keys.size >= 2) {
            w += "Effect rays: keyframed fillColor is not supported yet: the first value is used"
        }
        var hue = 0f
        var amount = 0f
        if (fillT != null) {
            val argb = unpacked(fillT.valueAt(0f))
            if ((argb and 0xFFFFFFL) != 0L) {
                hue = hueOfArgb(argb)
                amount = 100f
            }
        }
        val aspects = ArrayList<String>()
        val tcT = props["thresholdColor"]
        if (tcT != null && (listOf(tcT.constant) + tcT.keys.map { it.v }).any { (unpacked(it) and 0xFFFFFFL) != 0L }) {
            aspects += "its threshold colour is not reproduced"
        }
        if ((props["blend"]?.scalarAt(0f) ?: 0f) > 0.5f) aspects += "its blend mode is not reproduced"
        w += "Effect rays is approximated by the app's Light Rays" +
            (if (aspects.isEmpty()) "" else ": " + aspects.joinToString(", "))
        return addFx(
            clip0, ctx, ClipFxLibrary.LIGHT_RAYS,
            withTails(
                listOf(
                    (threshold * 100f).coerceIn(0f, 100f),
                    (strength * 33f).coerceIn(0f, 100f),
                    (intensity * 100f).coerceIn(0f, 300f),
                    cx, cy, hue, amount
                ),
                // Strength and the centre ride tails PRE-CONVERTED into slot units (the x33
                // length scale, the box-relative centre %), resolved by [AmFxEval] at scale 1.
                tailsOf(
                    trackTail(strengthT) { s -> (s * 33f).coerceIn(0f, 100f) },
                    pointTail(cpT, 0) { x -> cxOf(x) },
                    pointTail(cpT, 1) { y -> cyOf(y) }
                )
            ),
            "rays"
        )
    }

    /** The hue (0..360) of an ARGB colour, 0 for a grey. */
    private fun hueOfArgb(argb: Long): Float {
        val r = ((argb shr 16) and 0xFF).toFloat() / 255f
        val g = ((argb shr 8) and 0xFF).toFloat() / 255f
        val b = (argb and 0xFF).toFloat() / 255f
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val d = mx - mn
        if (d < 1e-6f) return 0f
        var h = when (mx) {
            r -> ((g - b) / d) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } * 60f
        if (h < 0f) h += 360f
        return h
    }

    /**
     * `colortune2` -> the app's Color Tune. Each of Alight's four controls (Lift / Gamma / Gain /
     * Offset) is a vec3 of (hue degrees, strength 0..1, luminance shift -1..1) — the only reading the
     * corpus values allow (strengths sit in 0..1, shifts in -1..1, the hues agree). The app runs Lift
     * and Gain (Alight's own guide weights them (1-y)^2 and y^2 by the pixel's luminance); a Gamma or
     * Offset that actually does something is reported, not faked.
     */
    private fun applyColorTune(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        fun disc(name: String): List<Float> {
            val t = props[name] ?: return listOf(0f, 0f, 0f)
            if (t.keys.size >= 2) w += "Effect colortune2: keyframed " + name + " is not supported yet: the first value is used"
            val v = t.valueAt(0f)
            return listOf(
                (((v.getOrNull(0) ?: 0f) % 360f) + 360f) % 360f,
                ((v.getOrNull(1) ?: 0f) * 100f).coerceIn(0f, 100f),
                ((v.getOrNull(2) ?: 0f) * 100f).coerceIn(-100f, 100f)
            )
        }
        val lift = disc("lift")
        val gain = disc("gain")
        val dropped = ArrayList<String>()
        for (name in listOf("gamma", "offset")) {
            val t = props[name] ?: continue
            val v = t.valueAt(0f)
            if (abs(v.getOrNull(1) ?: 0f) > 1e-4f || abs(v.getOrNull(2) ?: 0f) > 1e-4f) {
                dropped += name.replaceFirstChar { it.uppercase() }
            }
        }
        if (dropped.isNotEmpty()) {
            w += "Effect colortune2: its " + dropped.joinToString(" / ") + " controls are not reproduced"
        }
        return addFx(
            clip0, ctx, ClipFxLibrary.COLOR_TUNE,
            listOf(lift[0], lift[1], lift[2], gain[0], gain[1], gain[2]),
            "colortune2"
        )
    }

    /** A scalar track as an [AmFxEval] values tail, or empty when the track is not animated. */
    private fun trackTail(t: Track?): List<Float> = trackTail(t) { it }

    /**
     * A scalar track as an [AmFxEval] values tail with each key's value mapped first — for the
     * parameters whose fx slot holds a CONVERTED unit (Long Shadow's length is a % of the
     * layer's box, Light Rays' length / centre are scaled / box-relative): the tail then holds
     * slot units directly and [AmFxEval] resolves it at scale 1.
     */
    private fun trackTail(t: Track?, map: (Float) -> Float): List<Float> {
        if (t == null || t.keys.size < 2) return emptyList()
        return AmFxEval.encodeTrack(t.keys.map { k -> Triple(k.t, map(k.v.firstOrNull() ?: 0f), k.easing) })
    }

    /** One component of a point track as an [AmFxEval] values tail, mapped like [trackTail]. */
    private fun pointTail(t: Track?, component: Int, map: (Float) -> Float): List<Float> {
        if (t == null || t.keys.size < 2) return emptyList()
        return AmFxEval.encodeTrack(t.keys.map { k -> Triple(k.t, map(k.v.getOrNull(component) ?: 0f), k.easing) })
    }

    /**
     * Joins encoded tails in [AmFxEval]'s declared order: a constant parameter contributes a
     * count-0 placeholder when a sibling IS animated, so the sequence the resolver walks stays
     * aligned; when nothing is animated there is no tail at all.
     */
    private fun tailsOf(vararg encoded: List<Float>): List<Float> {
        if (encoded.all { it.isEmpty() }) return emptyList()
        return encoded.flatMap { if (it.isEmpty()) listOf(0f) else it }
    }

    /**
     * The tails of a multi-track fx (Turbulent Displace's intensity + scale), in [AmFxEval]'s
     * declared order: a constant track contributes a count-0 placeholder when a sibling IS
     * animated, so the sequence the resolver walks stays aligned; when nothing is animated
     * there is no tail at all.
     */
    private fun trackTails(vararg tracks: Track?): List<Float> =
        tailsOf(*tracks.map { trackTail(it) }.toTypedArray())

    /** [values] padded with zeros out to [AmFxEval.TAIL_START] and the tails appended (no-op without tails). */
    private fun withTails(values: List<Float>, tails: List<Float>): List<Float> {
        if (tails.isEmpty()) return values
        return values + List(maxOf(0, AmFxEval.TAIL_START - values.size)) { 0f } + tails
    }

    /** The largest |value| a scalar track takes (its constant or any key) — for aspect checks that must see past t = 0. */
    private fun Track.peakAbs(): Float =
        (listOf(constant) + keys.map { it.v }).maxOfOrNull { abs(it.firstOrNull() ?: 0f) } ?: 0f

    /** The first value of a point track the native fx model cannot animate, with the pass-2 style note. */
    private fun Track.firstPoint(w: MutableList<String>, short: String, prop: String, dx: Float, dy: Float): Pair<Float, Float> {
        if (keys.size >= 2) w += "Effect " + short + ": keyframed " + prop + " is not supported yet: the first value is used"
        val v = first()
        return (v.getOrNull(0) ?: dx) to (v.getOrNull(1) ?: dy)
    }

    /** A colour track's first value as a packed 0xRRGGBB float, with the pass-2 style note when keyframed. */
    private fun Track.packedColor(w: MutableList<String>, short: String, prop: String): Float {
        if (keys.size >= 2) w += "Effect " + short + ": keyframed " + prop + " is not supported yet: the first value is used"
        return (unpacked(first()) and 0xFFFFFFL).toFloat()
    }

    /** A bool property as a 0 / 1 fx value (its constant; bools are not keyframed in the corpus). */
    private fun boolOf(t: Track?, fallback: Boolean): Float =
        if (t == null) (if (fallback) 1f else 0f) else if (t.scalarAt(0f) > 0.5f) 1f else 0f

    /**
     * `mirror` -> the app's Mirror (Alight) — NOT the app's own centre/angle Mirror: Alight flips
     * the WHOLE layer's sampling (type 0 = Horizontal, 1 = Vertical, from AM's own effect
     * definition), blends the flipped picture over the original per blendMode (0 Normal,
     * 1 Multiply, 2 Screen, 3 Over, 4 Under) and mixes by alpha; `offset` is added to the
     * flipped coordinate. Corpus: 51 instances, all type / blendMode / offset at the defaults;
     * alpha is static 1 in 36 and KEYFRAMED (0<->1 flip-flash pulses) in 15 — the keyframed
     * alpha rides the values tail and [AmFxEval] resolves it per frame, as does a keyframed
     * `offset` (none in the corpus; `type` / `blendMode` are discrete and stay first-value).
     */
    private fun applyMirror(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val type = (props["type"]?.firstValue(w, "mirror", "type") ?: 0f).toInt().coerceIn(0, 1)
        val blend = (props["blendMode"]?.firstValue(w, "mirror", "blendMode") ?: 0f).toInt().coerceIn(0, 4)
        val offsetT = props["offset"]
        val offset = (offsetT?.scalarAtEased(0f) ?: 0f) * 100f
        val alphaT = props["alpha"]
        val alpha = (alphaT?.valueAtEased(0f)?.firstOrNull() ?: 1f) * 100f
        val values = listOf(
            type.toFloat(), blend.toFloat(),
            alpha.coerceIn(0f, 100f), offset.coerceIn(-200f, 200f),
            0f, 0f, 0f
        ) + tailsOf(trackTail(alphaT), trackTail(offsetT))
        return addFx(clip0, ctx, ClipFxLibrary.AM_MIRROR, values, "mirror")
    }

    /**
     * `glowscan` -> the app's Glow Scan, Alight's own formula (its effect definition was
     * recovered): a gaussian-blurred copy of the layer (strength feeds the blur — the
     * [applyGaussianBlur] calibration) is scanned by luma; inside the band
     * [threshold*(1+radius)-radius, +radius] the blurred picture gains a pow() glow tinted
     * toward fillColor. Corpus: 8 instances — radius 0.1, fillColor #FF0088FF, blend 0,
     * fill / alpha 1 everywhere; power 2 (4 in yura-preset-9x16), strength 0.05 (0.10 there);
     * threshold is KEYFRAMED in all 8 (the sweep IS the effect), so it rides the values tail
     * and [AmFxEval] resolves it per frame; the secondary scalars (radius / power / strength /
     * fill / alpha) ride behind it the same way when keyframed (none are in the corpus). AM's
     * `blend` has no native slot and is reported when it is not 0.
     */
    private fun applyGlowScan(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val thrT = props["threshold"]
        val threshold = (thrT?.valueAtEased(0f)?.firstOrNull() ?: 0.5f) * 100f
        val radiusT = props["radius"]
        val radius = (radiusT?.scalarAtEased(0f) ?: 0.1f) * 100f
        val powerT = props["power"]
        val power = powerT?.scalarAtEased(0f) ?: 2f
        val strengthT = props["strength"]
        val strength = (strengthT?.scalarAtEased(0f) ?: 0.05f) * 100f
        val colT = props["fillColor"]
        if (colT != null && colT.keys.size >= 2) {
            w += "Effect glowscan: keyframed fillColor is not supported yet: the first value is used"
        }
        val packed = if (colT != null) (unpacked(colT.valueAt(0f)) and 0xFFFFFFL).toFloat() else 0x0088FF.toFloat()
        val blend = props["blend"]?.scalarAt(0f) ?: 0f
        if (abs(blend) > 1e-4f) w += "Effect glowscan: its blend setting is not reproduced"
        val fillT = props["fill"]
        val fill = (fillT?.scalarAtEased(0f) ?: 1f) * 100f
        val alphaT = props["alpha"]
        val alpha = (alphaT?.scalarAtEased(0f) ?: 1f) * 100f
        val values = listOf(
            threshold.coerceIn(0f, 100f),
            radius.coerceIn(0f, 100f),
            power.coerceIn(0f, 4f),
            strength.coerceIn(0f, 200f),
            fill.coerceIn(0f, 100f),
            alpha.coerceIn(0f, 100f),
            packed
        ) + tailsOf(
            trackTail(thrT), trackTail(radiusT), trackTail(powerT),
            trackTail(strengthT), trackTail(fillT), trackTail(alphaT)
        )
        return addFx(clip0, ctx, ClipFxLibrary.GLOW_SCAN, values, "glowscan")
    }

    /**
     * `lumakey3` -> Luma Key (a dedicated engine pass; AM's recovered formula): the layer's alpha
     * is scaled by where its luma falls against the feathered [low, high] band. Corpus: 19
     * instances (3 of them hidden, skipped by the hidden rule); `lowThreshold` is KEYFRAMED in
     * all of them (a 1 -> 0 sweep — the reveal IS the effect), so it rides the values tail and
     * [AmFxEval] resolves values[0] per frame. One corpus instance has no properties at all:
     * AM's defaults (band [0, 1]) make it a no-op there, and the engine's identity check skips
     * the pass here.
     */
    private fun applyLumaKey(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val lowT = props["lowThreshold"]
        val low = (lowT?.valueAtEased(0f)?.firstOrNull() ?: 0f) * 100f
        val high = (props["highThreshold"]?.firstValue(w, "lumakey3", "highThreshold") ?: 1f) * 100f
        val feather = (props["feather"]?.firstValue(w, "lumakey3", "feather") ?: 0.05f) * 100f
        val values = listOf(
            low.coerceIn(0f, 100f), high.coerceIn(0f, 100f), feather.coerceIn(0f, 100f),
            boolOf(props["invert"], false), boolOf(props["weighted"], true), 0f, 0f
        ) + trackTail(lowT)
        return addFx(clip0, ctx, ClipFxLibrary.LUMA_KEY, values, "lumakey3")
    }

    /**
     * `fourcolorgradient` -> Four Color Gradient (a dedicated engine pass; AM's recovered
     * shader): four colours anchored at four points, blended by a smoothstep falloff. Corpus:
     * 15 instances, all constant; `offset4` is never serialized (AM's default (0, 0) applies).
     * The anchor points ride the EXT values, each packed by [ClipFxLibrary.packPoint].
     */
    private fun applyFourColorGradient(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        fun col(name: String, def: Long): Float =
            props[name]?.packedColor(w, "fourcolorgradient", name) ?: (def and 0xFFFFFFL).toFloat()
        fun off(name: String, dx: Float, dy: Float): Float {
            val t = props[name]
            val p = if (t == null) dx to dy else t.firstPoint(w, "fourcolorgradient", name, dx, dy)
            return ClipFxLibrary.packPoint(p.first, p.second)
        }
        val blend = (props["blend"]?.firstValue(w, "fourcolorgradient", "blend") ?: 1f) * 100f
        val alpha = (props["alpha"]?.firstValue(w, "fourcolorgradient", "alpha") ?: 0.75f) * 100f
        val mode = (props["blendMode"]?.firstValue(w, "fourcolorgradient", "blendMode") ?: 0f).toInt().coerceIn(0, 2)
        val values = listOf(
            col("color1", 0xFFFF0000L), col("color2", 0xFF00FF00L),
            col("color3", 0xFF0000FFL), col("color4", 0xFFFF00FFL),
            blend.coerceIn(25f, 200f), alpha.coerceIn(0f, 100f), mode.toFloat(),
            off("offset1", 0f, -500f), off("offset2", 500f, -500f),
            off("offset3", 500f, 0f), off("offset4", 0f, 0f),
            0f, 0f, 0f
        )
        return addFx(clip0, ctx, ClipFxLibrary.FOUR_COLOR_GRADIENT, values, "fourcolorgradient")
    }

    /**
     * `turbulentdisplace` -> Turbulent Displace (Alight) (a dedicated engine pass; AM's
     * recovered shader, Ashima simplex noise and all). Corpus: 39 instances, only ~2 distinct
     * profiles — direction XY, octaves 1, position (55, 0) / (64, 0), evolution 4.5 / 4.62;
     * `intensity` (0.153 -> 0) and `scale` (3.32 -> 0.01, keys outside the span) are KEYFRAMED
     * in all of them, so both ride values tails from index 14 (see [trackTails]).
     */
    private fun applyTurbulentDisplace(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val intT = props["intensity"]
        val scaleT = props["scale"]
        val intensity = (intT?.valueAtEased(0f)?.firstOrNull() ?: 0.25f) * 100f
        val scale = (scaleT?.valueAtEased(0f)?.firstOrNull() ?: 1f) * 100f
        val direction = (props["direction"]?.firstValue(w, "turbulentdisplace", "direction") ?: 3f).toInt().coerceIn(0, 3)
        val evolution = (props["evolution"]?.firstValue(w, "turbulentdisplace", "evolution") ?: 0f) * 100f
        val octaves = (props["octaves"]?.firstValue(w, "turbulentdisplace", "octaves") ?: 6f).toInt().coerceIn(1, 9)
        val offs = (props["offs"]?.firstValue(w, "turbulentdisplace", "offs") ?: 0f) * 100f
        val stretch = (props["stretch"]?.firstValue(w, "turbulentdisplace", "stretch") ?: 0f) * 100f
        val seed = (props["seed"]?.firstValue(w, "turbulentdisplace", "seed") ?: 0f) * 100f
        val pos = props["position"]?.firstPoint(w, "turbulentdisplace", "position", 0f, 0f) ?: (0f to 0f)
        val parr = props["parr"]?.firstPoint(w, "turbulentdisplace", "parr", 0f, 0f) ?: (0f to 0f)
        val values = listOf(
            intensity.coerceIn(0f, 250f), scale.coerceIn(1f, 5000f), direction.toFloat(),
            evolution.coerceIn(-5000f, 5000f), octaves.toFloat(), boolOf(props["screenSpace"], false),
            offs.coerceIn(-100f, 100f),
            stretch.coerceIn(-5000f, 5000f), seed.coerceIn(0f, 500f),
            pos.first, pos.second, parr.first, parr.second, 0f
        ) + trackTails(intT, scaleT)
        return addFx(clip0, ctx, ClipFxLibrary.TURBULENT_DISPLACE_AM, values, "turbulentdisplace")
    }

    /**
     * `stretchsegment` -> Stretch Segment (kFxWarp mode 12; AM's recovered shader). Corpus: 13
     * instances, all constant — angle / offset / smooth 0, stretch 568 / 900 / 749.
     */
    private fun applyStretchSegment(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val angle = props["angle"]?.firstValue(w, "stretchsegment", "angle") ?: 0f
        val stretch = props["stretch"]?.firstValue(w, "stretchsegment", "stretch") ?: 10f
        val offset = props["offset"]?.firstValue(w, "stretchsegment", "offset") ?: 0f
        val smooth = (props["smooth"]?.firstValue(w, "stretchsegment", "smooth") ?: 0f) * 100f
        val values = listOf(
            angle, stretch.coerceIn(0f, 900f), offset.coerceIn(-2000f, 2000f),
            smooth.coerceIn(0f, 100f), 0f, 0f, 0f
        )
        return addFx(clip0, ctx, ClipFxLibrary.STRETCH_SEGMENT, values, "stretchsegment")
    }

    /**
     * `spin` -> a ROTATION bake: Alight's spin is an auto-transform script
     * (`el.transform.angle += p.rpm` per frame, the engine scaling rpm by the elapsed minutes),
     * so the layer's angle grows at rpm revolutions per minute — 6 * rpm degrees per second —
     * accumulated over the EASED rpm track and ADDED over the layer's own rotation keys, the
     * [applySwing] composition. Corpus: 8 instances, rpm keyframed in all (signed, decelerating
     * profiles whose keys sit outside the span; the track clamps / interpolates across it like
     * Alight's own tracks do). Nothing is added when rpm is 0 throughout.
     */
    private fun applySpin(fx: Element, clip0: TimelineClip, ctx: Ctx, durMs: Long): TimelineClip {
        val props = fxProps(fx)
        val rpmT = props["rpm"] ?: return clip0
        if (durMs <= 0L) return clip0
        val still = rpmT.keys.all { abs(it.v.firstOrNull() ?: 0f) < 1e-4f } &&
            abs(rpmT.constant.firstOrNull() ?: 0f) < 1e-4f
        if (still) return clip0
        val baseKeys = clip0.keyframes[AnimProp.ROTATION]
        val extras = (baseKeys?.map { it.timeMs } ?: emptyList()) +
            rpmT.keys.map { (it.t.coerceIn(0f, 1f) * durMs).roundToLong() }
        val times = sampleTimes(durMs, ctx.fps, 0f, extras)
        val basePlain = clip0.propValues(AnimProp.ROTATION)
        var angle = 0.0
        var prevMs = 0L
        val raw = ArrayList<Keyframe>(times.size)
        for (t in times) {
            if (t > prevMs) {
                val midNorm = ((prevMs + t) / 2f) / durMs
                angle += 6.0 * rpmT.scalarAtEased(midNorm) * (t - prevMs) / 1000.0
            }
            prevMs = t
            val base = baseKeys?.valueAt(t) ?: basePlain
            raw += Keyframe(t, base.first + angle.toFloat(), base.second, Easing.LINEAR)
        }
        val keys = simplifyKeys(raw, 0.02f, 0.02f)
        var clip = clip0.copy(keyframes = clip0.keyframes + (AnimProp.ROTATION to keys))
        keys.firstOrNull()?.let { clip = clip.withProp(AnimProp.ROTATION, it.a, it.b) }
        return clip
    }

    /**
     * `replacecolor` -> Replace Color (kFxColor mode 17; AM's recovered shader): a YUV-distance
     * colour swap (chroma x4, luma x0.5). Corpus: 6 instances, all constant; lockLuminance on
     * in 5. The colours are declared alpha="false" in Alight, so packed RGB is the whole story.
     */
    private fun applyReplaceColor(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val old = props["oldcolor"]?.packedColor(w, "replacecolor", "oldcolor") ?: 0xFF0000.toFloat()
        val new = props["newcolor"]?.packedColor(w, "replacecolor", "newcolor") ?: 0x0000FF.toFloat()
        val thr = (props["threshold"]?.firstValue(w, "replacecolor", "threshold") ?: 0.25f) * 100f
        val fea = (props["feather"]?.firstValue(w, "replacecolor", "feather") ?: 0.25f) * 100f
        val alpha = (props["alpha"]?.firstValue(w, "replacecolor", "alpha") ?: 1f) * 100f
        val values = listOf(
            old, new, thr.coerceIn(0f, 400f), fea.coerceIn(0f, 200f), alpha.coerceIn(0f, 100f),
            boolOf(props["lockLuminance"], false), 0f
        )
        return addFx(clip0, ctx, ClipFxLibrary.REPLACE_COLOR, values, "replacecolor")
    }

    /**
     * `swirl4` / `swirl3` -> Swirl (Alight) (kFxWarp mode 13): the two versions share their
     * params and core maths and differ in centre units + aspect rule, so they land as one fx
     * with a version value. Corpus: 9 + 1 instances; radius is constant (0.8 / 0.51), the
     * centre is never serialized (the default: the picture centre); `strength` is constant in
     * the visible swirl4 instances, KEYFRAMED in the swirl3 one (0 -> -0.111 -> 0) — it rides
     * the tail either way. (The one keyframed swirl4 instance is hidden="true" and stays
     * skipped by the hidden rule.)
     */
    private fun applySwirl(fx: Element, clip0: TimelineClip, ctx: Ctx, id: String): TimelineClip {
        val w = ctx.warnings
        val short = id.substringAfterLast('.')
        val props = fxProps(fx)
        val strT = props["strength"]
        val strength = (strT?.valueAtEased(0f)?.firstOrNull() ?: 0.1f) * 100f
        val radius = (props["radius"]?.firstValue(w, short, "radius") ?: 0.3f) * 100f
        val centre = props["centerPoint"]?.firstPoint(w, short, "centerPoint", 0f, 0f) ?: (0f to 0f)
        val values = listOf(
            strength.coerceIn(-50f, 50f), radius.coerceIn(0f, 80f), centre.first, centre.second,
            if (id == SWIRL4) 4f else 3f, 0f, 0f
        ) + trackTail(strT)
        return addFx(clip0, ctx, ClipFxLibrary.SWIRL_AM, values, short)
    }

    /**
     * `halftonedots` -> Halftone Dots (Alight) (kFxWarp mode 14; AM's recovered shader).
     * Corpus: 10 instances, all constant (amount 209.5 / 64.5, angle 45). NOTE: none of the 10
     * is hidden — 9 carry locallyApplied="false", which Alight still renders (and the importer
     * has always imported as active); the hidden rule does not come into it.
     */
    private fun applyHalftoneDots(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val amount = props["amount"]?.firstValue(w, "halftonedots", "amount") ?: 100f
        val strength = (props["strength"]?.firstValue(w, "halftonedots", "strength") ?: 0.5f) * 100f
        val angle = props["angle"]?.firstValue(w, "halftonedots", "angle") ?: 45f
        val phase = (props["phase"]?.firstValue(w, "halftonedots", "phase") ?: 0.5f) * 100f
        val c1 = props["color1"]?.packedColor(w, "halftonedots", "color1") ?: 0x113355.toFloat()
        val c2 = props["color2"]?.packedColor(w, "halftonedots", "color2") ?: 0xFFEEDD.toFloat()
        val values = listOf(
            amount.coerceIn(0f, 500f), strength.coerceIn(0f, 100f), angle,
            phase.coerceIn(-100000f, 100000f), c1, c2, 0f
        )
        return addFx(clip0, ctx, ClipFxLibrary.HALFTONE_DOTS, values, "halftonedots")
    }

    /**
     * `pixelate2` -> Pixelate (Alight) (a dedicated engine pass; AM's recovered shader, cubic
     * vignette included — it is active in all 4 corpus instances at vignette 1). `size` is
     * constant in 3 and KEYFRAMED in 1 (24 -> 1), so it rides the tail from index 14 at scale
     * 1 (the slot already holds AM units). `offset` is never serialized (AM's default (0, 0)).
     */
    private fun applyPixelate(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val sizeT = props["size"]
        val size = sizeT?.valueAtEased(0f)?.firstOrNull() ?: 20f
        val stretch = props["stretch"]?.firstPoint(w, "pixelate2", "stretch", 1f, 1f) ?: (1f to 1f)
        val angle = props["angle"]?.firstValue(w, "pixelate2", "angle") ?: 0f
        val vignette = (props["vignette"]?.firstValue(w, "pixelate2", "vignette") ?: 0f) * 100f
        val threshold = (props["threshold"]?.firstValue(w, "pixelate2", "threshold") ?: 0f) * 100f
        val saturation = (props["saturation"]?.firstValue(w, "pixelate2", "saturation") ?: 0f) * 100f
        val offset = props["offset"]?.firstPoint(w, "pixelate2", "offset", 0f, 0f) ?: (0f to 0f)
        val values = listOf(
            size.coerceIn(1f, 500f),
            (stretch.first * 100f).coerceIn(1f, 10000f), (stretch.second * 100f).coerceIn(1f, 10000f),
            angle, vignette.coerceIn(0f, 100f), boolOf(props["screenSpace"], false),
            threshold.coerceIn(0f, 100f),
            saturation.coerceIn(0f, 100f), offset.first, offset.second, 0f, 0f, 0f, 0f
        ) + trackTail(sizeT)
        return addFx(clip0, ctx, ClipFxLibrary.PIXELATE_AM, values, "pixelate2")
    }

    // ---- v164: Alight's parked tier (hunt round 2; every definition recovered, defs-batch3) ----

    /**
     * `shake` (v1) -> baked into the POSITION keyframes like shake2, but with the ORIGINAL
     * effect's own script (recovered from its def): the displacement is AM's simplex noise of
     * the layer's own location and an evolution that runs on ABSOLUTE scene time
     * (evolution + t*speed - speed), the across-axis component scaled by `slack` (def 0.25),
     * the pair rotated by `angle`. Unlike shake2 there is no freq / zshake. The noise is the
     * Ashima snoise AM's own shaders use ([simplexNoise3]); the seed offsets are the def's.
     */
    private fun applyShakeV1(fx: Element, clip0: TimelineClip, ctx: Ctx, durMs: Long): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val magT = props["mag"] ?: return clip0
        val magMax = (magT.keys.map { abs(it.v.firstOrNull() ?: 0f) } +
            listOf(abs(magT.constant.firstOrNull() ?: 0f), abs(magT.first().firstOrNull() ?: 0f))).maxOrNull() ?: 0f
        if (magMax < 1e-4f) return clip0
        val speed = props["speed"]?.firstValue(w, "shake", "speed") ?: 1f
        val evolution = props["evolution"]?.firstValue(w, "shake", "evolution") ?: 0f
        val seed = (props["seed"]?.firstValue(w, "shake", "seed") ?: 0f).toDouble()
        val angle = props["angle"]?.firstValue(w, "shake", "angle") ?: 45f
        val slack = props["slack"]?.firstValue(w, "shake", "slack") ?: 0.25f
        val rad = Math.toRadians(angle.toDouble())
        val ca = cos(rad).toFloat()
        val sa = sin(rad).toFloat()
        // bakeDelta integrates a layer-local phase; v1's evolution runs on absolute time, so
        // the phase starts at evolution - speed + speed * (the layer's start in seconds).
        val phase0 = 2.0 * Math.PI * (evolution - speed + speed * (clip0.startMs / 1000f))
        val extra = magT.keys.map { (it.t.coerceIn(0f, 1f) * durMs).roundToLong() }
        val baseKeys = clip0.keyframes[AnimProp.POSITION]
        val basePlain = clip0.propValues(AnimProp.POSITION)
        return bakeDelta(clip0, AnimProp.POSITION, durMs, ctx, phase0, { speed }, extra) { phase, norm ->
            val evo = phase / (2.0 * Math.PI)
            val tMs = (norm * durMs).roundToLong()
            val base = baseKeys?.valueAt(tMs) ?: basePlain
            val locX = (base.first / ctx.k + ctx.sceneW / 2f).toDouble()
            val locY = (base.second / ctx.k + ctx.sceneH / 2f).toDouble()
            val mag = magT.scalarAtEased(norm).toDouble()
            val dx = simplexNoise3(locX + seed * 54623.245, locY, evo + seed * 49235.319798) * mag
            val dy = simplexNoise3(locX, locY + seed * 8723.5647, evo + 7468.329 + seed * 19337.940385) * mag * slack
            ((dx * ca - dy * sa) * ctx.k).toFloat() to ((dx * sa + dy * ca) * ctx.k).toFloat()
        }
    }

    /** Ashima simplex noise (the snoise of AM's own shaders), in double precision like AM's JS. */
    private fun simplexNoise3(x: Double, y: Double, z: Double): Double {
        fun mod289(v: Double) = v - floor(v * (1.0 / 289.0)) * 289.0
        fun permute(v: Double) = mod289(((v * 34.0) + 1.0) * v)
        fun taylor(r: Double) = 1.79284291400159 - 0.85373472095314 * r
        val c1 = 1.0 / 6.0
        val c2 = 1.0 / 3.0
        val s = (x + y + z) * c2
        var ix = floor(x + s)
        var iy = floor(y + s)
        var iz = floor(z + s)
        val t = (ix + iy + iz) * c1
        val x0 = doubleArrayOf(x - ix + t, y - iy + t, z - iz + t)
        val g = doubleArrayOf(
            if (x0[0] >= x0[1]) 1.0 else 0.0,
            if (x0[1] >= x0[2]) 1.0 else 0.0,
            if (x0[2] >= x0[0]) 1.0 else 0.0
        )
        val l = doubleArrayOf(1.0 - g[0], 1.0 - g[1], 1.0 - g[2])
        val i1 = doubleArrayOf(min(g[0], l[2]), min(g[1], l[0]), min(g[2], l[1]))
        val i2 = doubleArrayOf(max(g[0], l[2]), max(g[1], l[0]), max(g[2], l[1]))
        val x1 = doubleArrayOf(x0[0] - i1[0] + c1, x0[1] - i1[1] + c1, x0[2] - i1[2] + c1)
        val x2 = doubleArrayOf(x0[0] - i2[0] + c2, x0[1] - i2[1] + c2, x0[2] - i2[2] + c2)
        val x3 = doubleArrayOf(x0[0] - 0.5, x0[1] - 0.5, x0[2] - 0.5)
        ix = mod289(ix); iy = mod289(iy); iz = mod289(iz)
        val p = DoubleArray(4)
        for (k in 0..3) {
            val oz = when (k) { 0 -> 0.0; 1 -> i1[2]; 2 -> i2[2]; else -> 1.0 }
            val oy = when (k) { 0 -> 0.0; 1 -> i1[1]; 2 -> i2[1]; else -> 1.0 }
            val ox = when (k) { 0 -> 0.0; 1 -> i1[0]; 2 -> i2[0]; else -> 1.0 }
            p[k] = permute(permute(permute(iz + oz) + iy + oy) + ix + ox)
        }
        val n_ = 0.142857142857
        val nsx = 2.0 * n_
        val nsy = 0.5 * n_ - 1.0
        val nsz = n_
        val gx = DoubleArray(4)
        val gy = DoubleArray(4)
        for (k in 0..3) {
            val j = p[k] - 49.0 * floor(p[k] * nsz * nsz)
            val xf = floor(j * nsz)
            val yf = floor(j - 7.0 * xf)
            gx[k] = xf * nsx + nsy
            gy[k] = yf * nsx + nsy
        }
        val h = DoubleArray(4) { 1.0 - abs(gx[it]) - abs(gy[it]) }
        val b0 = doubleArrayOf(gx[0], gx[1], gy[0], gy[1])
        val b1 = doubleArrayOf(gx[2], gx[3], gy[2], gy[3])
        val s0 = DoubleArray(4) { floor(b0[it]) * 2.0 + 1.0 }
        val s1 = DoubleArray(4) { floor(b1[it]) * 2.0 + 1.0 }
        val sh = DoubleArray(4) { if (h[it] > 0.0) 0.0 else -1.0 }
        val a0 = doubleArrayOf(
            b0[0] + s0[0] * sh[0], b0[2] + s0[2] * sh[0],
            b0[1] + s0[1] * sh[1], b0[3] + s0[3] * sh[1]
        )
        val a1 = doubleArrayOf(
            b1[0] + s1[0] * sh[2], b1[2] + s1[2] * sh[2],
            b1[1] + s1[1] * sh[3], b1[3] + s1[3] * sh[3]
        )
        fun grad(px: Double, py: Double, pz: Double, v: DoubleArray): Double {
            val n = taylor(px * px + py * py + pz * pz)
            return px * n * v[0] + py * n * v[1] + pz * n * v[2]
        }
        fun corner(v: DoubleArray, px: Double, py: Double, pz: Double): Double {
            val m = max(0.6 - (v[0] * v[0] + v[1] * v[1] + v[2] * v[2]), 0.0)
            val w2 = m * m
            return w2 * w2 * grad(px, py, pz, v)
        }
        return 42.0 * (
            corner(x0, a0[0], a0[1], h[0]) + corner(x1, a0[2], a0[3], h[1]) +
                corner(x2, a1[0], a1[1], h[2]) + corner(x3, a1[2], a1[3], h[3])
            )
    }

    /**
     * `chromakey` -> the app's Chroma Key (Alight) — NOT the app's own Chroma Key: AM's def
     * measures the YUV distance (luma counted a quarter) to the key colour on the
     * unpremultiplied pixel, with a defringe subtraction and a transparent half-texel border.
     * Corpus: 1 instance (black key, threshold 0.1, feather 0.05). Keyframed threshold /
     * feather ride the tail; the key colour is a colour and stays first-value.
     */
    private fun applyChromaKeyAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val key = props["keyColor"]?.packedColor(w, "chromakey", "keyColor") ?: 0x00FF00.toFloat()
        val thrT = props["threshold"]
        val feaT = props["feather"]
        val thr = (thrT?.scalarAtEased(0f) ?: 0.1f) * 100f
        val fea = (feaT?.scalarAtEased(0f) ?: 0.05f) * 100f
        val values = listOf(
            key, thr.coerceIn(0f, 100f), fea.coerceIn(0f, 100f),
            boolOf(props["defringe"], false), boolOf(props["invert"], false), 0f, 0f
        ) + tailsOf(trackTail(thrT), trackTail(feaT))
        return addFx(clip0, ctx, ClipFxLibrary.CHROMA_KEY_AM, values, "chromakey")
    }

    /**
     * `colorbalance` -> the app's Color Balance (Alight): per-band (low / mid / high, by
     * luma) channel gains of (filter + 100) / 100 in YUV space, fixLum restoring the input
     * luma. Corpus: 2 instances, both hidden="true" (skipped at import like Alight skips
     * them) with the extreme defaults (crLow 100, mgLow -100, ybLow 100, fixLum true). Every
     * band value may ride a tail from index 14; fixLum is a flag and stays first-value.
     */
    private fun applyColorBalanceAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val names = listOf("crLow", "mgLow", "ybLow", "crMid", "mgMid", "ybMid", "crHigh", "mgHigh", "ybHigh")
        val tracks = names.map { props[it] }
        val bands = tracks.map { (it?.scalarAtEased(0f) ?: 0f).coerceIn(-100f, 100f) }
        val fixLum = boolOf(props["fixLum"], true)
        val values = listOf(
            bands[0], bands[1], bands[2], bands[3], bands[4], bands[5], bands[6],
            bands[7], bands[8], fixLum, 0f, 0f, 0f, 0f
        ) + tailsOf(*tracks.map { trackTail(it) }.toTypedArray())
        return addFx(clip0, ctx, ClipFxLibrary.COLOR_BALANCE_AM, values, "colorbalance")
    }

    /**
     * `colortemperature` -> the app's Color Temperature (Alight): a Kelvin->RGB gain from
     * AM's own polynomial fit, mixed by strength. Corpus: 2 instances (5.54 K x1000 at 0.987,
     * 7.0 at 1.0), both static; keyframed temperature / strength ride the tail.
     */
    private fun applyColorTempAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val props = fxProps(fx)
        val tempT = props["temperature"]
        val strT = props["strength"]
        val temp = tempT?.scalarAtEased(0f) ?: 6.5f
        val str = (strT?.scalarAtEased(0f) ?: 1f) * 100f
        val values = listOf(
            temp.coerceIn(1f, 40f), str.coerceIn(0f, 100f), 0f, 0f, 0f, 0f, 0f
        ) + tailsOf(trackTail(tempT), trackTail(strT))
        return addFx(clip0, ctx, ClipFxLibrary.COLOR_TEMP_AM, values, "colortemperature")
    }

    /**
     * `colortint` -> the app's Color Tint (Alight): the pixel's chroma mixes toward the tint
     * hue's chroma by strength * alpha (AM's def; the tint's third component is unused there,
     * commented out in the shader). Corpus: 1 instance (hue -55.3, strength 0.048).
     */
    private fun applyColorTintAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val props = fxProps(fx)
        val tintT = props["tint"]
        val v = tintT?.valueAtEased(0f)
        val hue = v?.getOrNull(0) ?: 0f
        val str = (v?.getOrNull(1) ?: 0f) * 100f
        val hueTail = if (tintT != null && tintT.keys.size >= 2)
            AmFxEval.encodeTrack(tintT.keys.map { k -> Triple(k.t, k.v.getOrNull(0) ?: 0f, k.easing) })
        else emptyList()
        val strTail = if (tintT != null && tintT.keys.size >= 2)
            AmFxEval.encodeTrack(tintT.keys.map { k -> Triple(k.t, k.v.getOrNull(1) ?: 0f, k.easing) })
        else emptyList()
        val values = listOf(hue, str.coerceIn(0f, 100f), 0f, 0f, 0f, 0f, 0f) +
            tailsOf(hueTail, strTail)
        return addFx(clip0, ctx, ClipFxLibrary.COLOR_TINT_AM, values, "colortint")
    }

    /**
     * `spinblur2` -> the app's Spin Blur (Alight): taps rotated over [-angle/2, +angle/2],
     * their count from the arc length at each radius. Corpus: 3 instances (angle 1.2 static,
     * 1.0 static, and one KEYFRAMED 0 -> 8 -> 0 — the keyframed angle rides the tail).
     * No instance carries a centerPoint (the layer centre everywhere).
     */
    private fun applySpinBlurAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val angT = props["angle"]
        val ang = angT?.scalarAtEased(0f) ?: 5f
        val centre = props["centerPoint"]?.firstPoint(w, "spinblur2", "centre", 0f, 0f) ?: (0f to 0f)
        val values = listOf(ang, centre.first, centre.second, 0f, 0f, 0f, 0f) +
            tailsOf(trackTail(angT))
        return addFx(clip0, ctx, ClipFxLibrary.SPIN_BLUR_AM, values, "spinblur2")
    }

    /**
     * `offset` -> the app's Offset (Alight): the layer wrap-shifted over itself, the seam
     * cross-faded by feather, the shifted copy masked by the layer's silhouette when mask
     * is on. Corpus: 2 instances — a constant (489, 0) shift and a KEYFRAMED slide-in from
     * -9999 (the offset rides two tails, x then y). (The def's `scale` spinner is commented
     * out in AM's own definition — it does nothing there either.)
     */
    private fun applyOffsetAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val props = fxProps(fx)
        val offT = props["offset"]
        val off = offT?.valueAtEased(0f)
        val ox = off?.getOrNull(0) ?: 0f
        val oy = off?.getOrNull(1) ?: 0f
        val feather = (props["feather"]?.firstValue(ctx.warnings, "offset", "feather") ?: 0f) * 100f
        val values = listOf(
            ox, oy, feather.coerceIn(0f, 100f), boolOf(props["mask"], false), 0f, 0f, 0f
        ) + tailsOf(pointTail(offT, 0) { it }, pointTail(offT, 1) { it })
        return addFx(clip0, ctx, ClipFxLibrary.OFFSET_AM, values, "offset")
    }

    /**
     * `radialwipe` -> the app's Radial Wipe (Alight): an angular matte around the centre.
     * Corpus: 1 instance, progress KEYFRAMED 360 -> 0 (a wipe-in; it rides the tail),
     * direction 0, repeat 1, feather / offset 0, no centerPoint. (The def's start / end
     * spinners are not read by AM's own shader — it hardcodes start = 0.)
     */
    private fun applyRadialWipeAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val progT = props["progress"]
        val prog = progT?.scalarAtEased(0f) ?: 45f
        val feather = props["feather"]?.firstValue(w, "radialwipe", "feather") ?: 0f
        val direction = (props["direction"]?.firstValue(w, "radialwipe", "direction") ?: 0f).toInt().coerceIn(0, 2)
        val repeat = (props["repeatCount"]?.firstValue(w, "radialwipe", "repeatCount") ?: 1f).coerceIn(1f, 36f)
        val centre = props["centerPoint"]?.firstPoint(w, "radialwipe", "centre", 0f, 0f) ?: (0f to 0f)
        val offset = props["offset"]?.firstValue(w, "radialwipe", "offset") ?: 0f
        val values = listOf(
            prog, feather, direction.toFloat(), repeat,
            ClipFxLibrary.packPoint(centre.first, centre.second), offset, 0f
        ) + tailsOf(trackTail(progT))
        return addFx(clip0, ctx, ClipFxLibrary.RADIAL_WIPE_AM, values, "radialwipe")
    }

    /**
     * `findedges` -> the app's Find Edges (Alight): a Scharr gradient of the premultiplied
     * luma. Corpus: 1 instance (smoothing 1, threshold 1 — at the top of its range, so the
     * gradient is clamped away almost everywhere — invert true).
     */
    private fun applyFindEdgesAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val smoothing = props["smoothing"]?.firstValue(w, "findedges", "smoothing") ?: 1f
        val threshold = (props["threshold"]?.firstValue(w, "findedges", "threshold") ?: 0f) * 100f
        val values = listOf(
            smoothing.coerceIn(0f, 10f), threshold.coerceIn(0f, 100f),
            boolOf(props["invert"], false), 0f, 0f, 0f, 0f
        )
        return addFx(clip0, ctx, ClipFxLibrary.FIND_EDGES_AM, values, "findedges")
    }

    /**
     * `hexshift` -> the app's Hex Shift (Alight): each hexagonal cell displaces the sampling
     * by its own fixed random vector (AM's hash, pinned by the def). Corpus: 2 instances,
     * identical — strength KEYFRAMED 0 -> 0.011 -> 0 and angle KEYFRAMED ~3508 -> 3259 ->
     * 3508 (both ride tails from index 14), size 127, stretch (0.09, 1), seed 0.1,
     * screenSpace on, maskToLayer off, no position. The angle track's last segment carries
     * AM's `random` easing, which the importer's easing model reads as linear (the transform
     * tracks do the same); in screen space AM anchors the grid to the screen, here it is
     * anchored to the layer, like Turbulent Displace's.
     */
    private fun applyHexShiftAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val strT = props["strength"]
        val angT = props["angle"]
        val str = strT?.scalarAtEased(0f) ?: 0.1f
        val size = props["size"]?.firstValue(w, "hexshift", "size") ?: 10f
        val stretch = props["stretch"]?.firstPoint(w, "hexshift", "stretch", 1f, 1f) ?: (1f to 1f)
        val ang = angT?.scalarAtEased(0f) ?: 0f
        val seed = props["seed"]?.firstValue(w, "hexshift", "seed") ?: 0f
        val position = props["position"]?.firstPoint(w, "hexshift", "position", 0f, 0f) ?: (0f to 0f)
        val values = listOf(
            str, size.coerceIn(0.5f, 500f),
            (stretch.first * 100f).coerceIn(1f, 1000f), (stretch.second * 100f).coerceIn(1f, 1000f),
            ang, seed, boolOf(props["screenSpace"], false),
            boolOf(props["maskToLayer"], false), ClipFxLibrary.packPoint(position.first, position.second),
            0f, 0f, 0f, 0f, 0f
        ) + tailsOf(trackTail(strT), trackTail(angT))
        return addFx(clip0, ctx, ClipFxLibrary.HEX_SHIFT_AM, values, "hexshift")
    }

    /**
     * `softglow` -> the app's Soft Glow (Alight): the blurred copy, brightened and pushed
     * toward the tint colour by its own luminance, screen-blended over the layer. Corpus:
     * 1 instance, all static (strength 0.5, brightness 1, contrast 0.7, highlights 1, white,
     * blend 1, alpha 1, outsideAlpha 0). AM's backdrop branch (outsideAlpha > 0, it samples
     * the running composite) is not reproduced and is reported when used.
     */
    private fun applySoftGlowAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val strT = props["strength"]
        val str = (strT?.scalarAtEased(0f) ?: 0.1f) * 100f
        val bright = (props["brightness"]?.firstValue(w, "softglow", "brightness") ?: 1f) * 100f
        val contrast = (props["contrast"]?.firstValue(w, "softglow", "contrast") ?: 0.2f) * 100f
        val highlights = (props["highlights"]?.firstValue(w, "softglow", "highlights") ?: 0.5f) * 100f
        val color = props["color"]?.packedColor(w, "softglow", "color") ?: 0xFF5566.toFloat()
        val blend = (props["blend"]?.firstValue(w, "softglow", "blend") ?: 0.25f) * 100f
        val alpha = (props["alpha"]?.firstValue(w, "softglow", "alpha") ?: 1f) * 100f
        val outside = (props["outsideAlpha"]?.firstValue(w, "softglow", "outsideAlpha") ?: 0f) * 100f
        if (outside > 0.5f) {
            w += "Effect softglow: its outside (backdrop) glow is not reproduced"
        }
        val values = listOf(
            str.coerceIn(0f, 200f), bright.coerceIn(0f, 200f), contrast.coerceIn(0f, 100f),
            highlights.coerceIn(0f, 100f), color, blend.coerceIn(0f, 100f), alpha.coerceIn(0f, 100f),
            outside.coerceIn(0f, 100f), 0f, 0f, 0f, 0f, 0f, 0f
        ) + tailsOf(trackTail(strT))
        return addFx(clip0, ctx, ClipFxLibrary.SOFT_GLOW_AM, values, "softglow")
    }

    /**
     * `fillbehind` -> the app's Fill Behind (Alight): the blurred copy, colour-adjusted
     * (saturation / tint / gamma / scanlines), fills behind the feathered foreground with an
     * edge-colour rim. Corpus: 1 instance — strength 0.08, feather 0.05, gamma 1, tint
     * #FF5566 at strength 0, edge black at 0.075, scanLines 0.298 over 742 lines, bgOffset
     * KEYFRAMED (it rides two tails at values 10/11). Four AM params have no native slot —
     * brightness, fgAlpha, fgScale, fgOffset — and are reported when not at their defaults
     * (all four are, in the corpus instance).
     */
    private fun applyFillBehindAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val strT = props["strength"]
        val str = (strT?.scalarAtEased(0f) ?: 0.3f) * 100f
        val feather = (props["feather"]?.firstValue(w, "fillbehind", "feather") ?: 0.05f) * 100f
        val gamma = (props["gamma"]?.firstValue(w, "fillbehind", "gamma") ?: 1f) * 100f
        val tintStr = (props["tintStrength"]?.firstValue(w, "fillbehind", "tintStrength") ?: 0f) * 100f
        val tint = props["tintColor"]?.packedColor(w, "fillbehind", "tintColor") ?: 0f
        val edgeStr = (props["edgeStrength"]?.firstValue(w, "fillbehind", "edgeStrength") ?: 0f) * 100f
        val edge = props["edgeColor"]?.packedColor(w, "fillbehind", "edgeColor") ?: 0f
        val scan = (props["scanLines"]?.firstValue(w, "fillbehind", "scanLines") ?: 0f) * 100f
        val scanCount = props["scanLineCount"]?.firstValue(w, "fillbehind", "scanLineCount") ?: 720f
        val bgScale = (props["bgScale"]?.firstValue(w, "fillbehind", "bgScale") ?: 1f) * 100f
        val alpha = (props["alpha"]?.firstValue(w, "fillbehind", "alpha") ?: 1f) * 100f
        val sat = (props["saturation"]?.firstValue(w, "fillbehind", "saturation") ?: 0f) * 100f
        val bgOffT = props["bgOffset"]
        val bgOff = bgOffT?.valueAtEased(0f)
        val bgOx = bgOff?.getOrNull(0) ?: 0f
        val bgOy = bgOff?.getOrNull(1) ?: 0f
        val brightness = props["brightness"]?.firstValue(w, "fillbehind", "brightness") ?: 0f
        if (abs(brightness) > 1e-4f) w += "Effect fillbehind: its brightness is not reproduced"
        val fgAlpha = props["fgAlpha"]?.firstValue(w, "fillbehind", "fgAlpha") ?: 1f
        if (abs(fgAlpha - 1f) > 1e-4f) w += "Effect fillbehind: its foreground alpha is not reproduced"
        val fgScale = props["fgScale"]?.firstValue(w, "fillbehind", "fgScale") ?: 1f
        if (abs(fgScale - 1f) > 1e-4f) w += "Effect fillbehind: its foreground scale is not reproduced"
        val fgOff = props["fgOffset"]?.first()
        if (fgOff != null && (abs(fgOff.getOrNull(0) ?: 0f) > 1e-4f || abs(fgOff.getOrNull(1) ?: 0f) > 1e-4f)) {
            w += "Effect fillbehind: its foreground offset is not reproduced"
        }
        val values = listOf(
            str.coerceIn(0f, 200f), feather.coerceIn(0f, 100f), gamma.coerceIn(1f, 999f),
            tintStr.coerceIn(0f, 100f), tint, edgeStr.coerceIn(0f, 100f), scan.coerceIn(0f, 100f),
            scanCount.coerceIn(0f, 2160f), bgScale.coerceIn(1f, 10000f), edge,
            bgOx, bgOy, alpha.coerceIn(0f, 100f), sat.coerceIn(-100f, 100f)
        ) + tailsOf(trackTail(strT), pointTail(bgOffT, 0) { it }, pointTail(bgOffT, 1) { it })
        return addFx(clip0, ctx, ClipFxLibrary.FILL_BEHIND_AM, values, "fillbehind")
    }

    /**
     * `randomdisplace` -> a POSITION bake, exactly like `shake`'s: AM computes it in a script
     * (its recovered def), sampling Ashima simplex noise at the layer's own location — it is a
     * layer transform there, not a pixel effect, so there is no native fx type for it. The
     * bake runs for every target (text included: AM applies it to text layers too; it used to
     * be stored as a text animator and never drawn). The def's asymmetric scatter divisors
     * (50 for x, 500 for y) are AM's own, kept verbatim.
     */
    private fun applyRandomDisplace(fx: Element, clip0: TimelineClip, ctx: Ctx, durMs: Long): TimelineClip {
        val props = fxProps(fx)
        val magT = props["mag"] ?: return clip0
        val magMax = (magT.keys.map { abs(it.v.firstOrNull() ?: 0f) } +
            listOf(abs(magT.constant.firstOrNull() ?: 0f), abs(magT.first().firstOrNull() ?: 0f))).maxOrNull() ?: 0f
        if (magMax < 1e-4f) return clip0
        val evoT = props["evolution"]
        val seedT = props["seed"]
        val scatterT = props["scatter"]
        val extra = listOf(magT, evoT, seedT, scatterT).flatMap { t ->
            t?.keys?.map { (it.t.coerceIn(0f, 1f) * durMs).roundToLong() } ?: emptyList()
        }
        val baseKeys = clip0.keyframes[AnimProp.POSITION]
        val basePlain = clip0.propValues(AnimProp.POSITION)
        return bakeDelta(clip0, AnimProp.POSITION, durMs, ctx, 0.0, { 1f }, extra) { _, norm ->
            val tMs = (norm * durMs).roundToLong()
            val base = baseKeys?.valueAt(tMs) ?: basePlain
            val locX = (base.first / ctx.k + ctx.sceneW / 2f).toDouble()
            val locY = (base.second / ctx.k + ctx.sceneH / 2f).toDouble()
            val mag = magT.scalarAtEased(norm).toDouble()
            val evo = (evoT?.scalarAtEased(norm) ?: 0f).toDouble()
            val seed = (seedT?.scalarAtEased(norm) ?: 0f).toDouble()
            val scatter = (scatterT?.scalarAtEased(norm) ?: 0.5f).toDouble()
            val dx = simplexNoise3(locX * scatter / 50.0 + seed * 54623.245, locY * scatter / 500.0,
                evo + seed * 49235.319798) * mag
            val dy = simplexNoise3(locX * scatter / 50.0, locY * scatter / 500.0 + seed * 8723.5647,
                evo + 7468.329 + seed * 19337.940385) * mag
            (dx * ctx.k).toFloat() to (dy * ctx.k).toFloat()
        }
    }

    /**
     * `wavewarp2` -> the app's Wave Warp (Alight), from AM's recovered shader: a sine along
     * one direction displaces along another, with distance and space damping. Keyframed
     * phase / magnitude / angles ride tails (EXT start). AM's screenSpace anchors the wave
     * to the screen; here the pattern stays layer-anchored (the Hex Shift precedent), which
     * is reported when an instance asks for it (no corpus instance does).
     */
    private fun applyWaveWarpAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val phaseT = props["phase"]
        val m2T = props["m2"]
        val a1T = props["a1d"]
        val a2T = props["a2d"]
        val screen = boolOf(props["screenSpace"], false)
        if (screen > 0.5f) w += "Effect wavewarp2: screen space is not reproduced: the wave stays layer-anchored"
        val values = listOf(
            phaseT?.scalarAtEased(0f) ?: 0f,
            a1T?.scalarAtEased(0f) ?: 0f,
            (props["m1"]?.firstValue(w, "wavewarp2", "spacing") ?: 20f).coerceIn(0f, 500f),
            m2T?.scalarAtEased(0f) ?: 4f,
            a2T?.scalarAtEased(0f) ?: 90f,
            (props["damping"]?.firstValue(w, "wavewarp2", "damping") ?: 0f).coerceIn(-1f, 1f),
            (props["dampingSpace"]?.firstValue(w, "wavewarp2", "damping spacing") ?: 0f).coerceIn(-1f, 1f),
            (props["dampingOrigin"]?.firstValue(w, "wavewarp2", "damping origin") ?: 0.5f).coerceIn(0f, 1f),
            screen, 0f, 0f, 0f, 0f, 0f
        ) + tailsOf(trackTail(phaseT), trackTail(m2T), trackTail(a1T), trackTail(a2T))
        return addFx(clip0, ctx, ClipFxLibrary.WAVE_WARP_AM, values, "wavewarp2")
    }

    /**
     * `circularripple2` / `circularripple` -> the app's Circular Ripple (Alight): their
     * recovered shaders are byte-identical, so one mapping serves both. Keyframed frequency /
     * strength / centre ride tails.
     */
    private fun applyCircularRippleAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val props = fxProps(fx)
        val freqT = props["frequency"]
        val strT = props["strength"]
        val cpT = props["centerPoint"]
        val cp = cpT?.valueAtEased(0f)
        val values = listOf(
            (strT?.scalarAtEased(0f) ?: 0.025f).coerceIn(-1f, 1f),
            (props["radius"]?.scalarAtEased(0f) ?: 0.3f).coerceIn(0f, 0.8f),
            (freqT?.scalarAtEased(0f) ?: 20f).coerceIn(0f, 100f),
            (props["feather"]?.scalarAtEased(0f) ?: 0.1f).coerceIn(0f, 1f),
            props["phase"]?.scalarAtEased(0f) ?: 0f,
            cp?.getOrNull(0) ?: 0f,
            cp?.getOrNull(1) ?: 0f
        ) + tailsOf(trackTail(freqT), trackTail(strT), pointTail(cpT, 0) { it }, pointTail(cpT, 1) { it })
        return addFx(clip0, ctx, ClipFxLibrary.CIRCULAR_RIPPLE_AM, values, "circularripple2")
    }

    /**
     * `stretch2` / `stretch` -> the app's Stretch (Alight): one map, two contentOnly
     * composites (v1 puts the layer UNDER the masked sample, v2 keeps the masked sample);
     * [3] carries the version. Keyframed scale / angle ride tails (nearly every instance).
     */
    private fun applyStretchAm(fx: Element, clip0: TimelineClip, ctx: Ctx, id: String): TimelineClip {
        val props = fxProps(fx)
        val scaleT = props["scale"]
        val angT = props["angle"]
        val values = listOf(
            (scaleT?.scalarAtEased(0f) ?: 1f).coerceIn(0.01f, 50f),
            angT?.scalarAtEased(0f) ?: 0f,
            boolOf(props["contentOnly"], false),
            if (id == STRETCH2) 2f else 1f, 0f, 0f, 0f
        ) + tailsOf(trackTail(scaleT), trackTail(angT))
        return addFx(clip0, ctx, ClipFxLibrary.STRETCH_AM, values, "stretch2")
    }

    /**
     * `squeeze` -> the app's Squeeze (Alight): AM's recovered shader reads ONLY the strength
     * (its declared centre point and radius are never sampled — the axis rule the build-101
     * port refused to guess is pinned by the def). A keyframed strength rides the tail.
     */
    private fun applySqueezeAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val props = fxProps(fx)
        val strT = props["strength"]
        val values = listOf(
            (strT?.scalarAtEased(0f) ?: 0f).coerceIn(-2f, 2f), 0f, 0f, 0f, 0f, 0f, 0f
        ) + tailsOf(trackTail(strT))
        return addFx(clip0, ctx, ClipFxLibrary.SQUEEZE_AM, values, "squeeze")
    }

    /**
     * `fractalwarp4` -> the app's Fractal Warp (Alight): two McGuire value-noise fbm fields
     * displace the sampling. The offset / parallax points are keyframed in the corpus, so
     * their components ride separate tails. screenSpace would anchor the field to the
     * screen; the pattern stays layer-anchored here (reported when asked; no corpus
     * instance asks).
     */
    private fun applyFractalWarpAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val magT = props["mag"]
        val scaleT = props["scale"]
        val offsT = props["offs"]
        val parrT = props["parr"]
        val offs = offsT?.valueAtEased(0f)
        val parr = parrT?.valueAtEased(0f)
        if (boolOf(props["screenSpace"], false) > 0.5f) {
            w += "Effect fractalwarp4: screen space is not reproduced: the pattern stays layer-anchored"
        }
        val values = listOf(
            (magT?.scalarAtEased(0f) ?: 0.2f).coerceIn(-5f, 5f),
            (scaleT?.scalarAtEased(0f) ?: 1f).coerceIn(0.1f, 4f),
            (props["intensity"]?.firstValue(w, "fractalwarp4", "intensity") ?: 0.5f).coerceIn(0.25f, 0.75f),
            (props["octaves"]?.firstValue(w, "fractalwarp4", "octaves") ?: 6f).coerceIn(1f, 9f),
            offs?.getOrNull(0) ?: 0f, offs?.getOrNull(1) ?: 0f,
            parr?.getOrNull(0) ?: 0f, parr?.getOrNull(1) ?: 0f,
            0f, 0f, 0f, 0f, 0f, 0f
        ) + tailsOf(
            trackTail(magT), pointTail(offsT, 0) { it }, pointTail(offsT, 1) { it },
            pointTail(parrT, 0) { it }, pointTail(parrT, 1) { it }, trackTail(scaleT)
        )
        return addFx(clip0, ctx, ClipFxLibrary.FRACTAL_WARP_AM, values, "fractalwarp4")
    }

    /**
     * `stripes` -> the app's Stripes (Alight): rotated colour bands over the layer (or
     * punched out of it). Keyframed phase / width ride tails. AM's screenSpace branch
     * anchors the bands to the screen; the layer branch is what runs here (reported when
     * an instance asks for screen space; none does).
     */
    private fun applyStripesAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val phaseT = props["phase"]
        val widthT = props["width"]
        if (boolOf(props["screenSpace"], false) > 0.5f) {
            w += "Effect stripes: screen space is not reproduced: the bands stay layer-anchored"
        }
        val values = listOf(
            (props["amount"]?.firstValue(w, "stripes", "amount") ?: 10f).coerceIn(0.5f, 200f),
            (widthT?.scalarAtEased(0f) ?: 0.5f).coerceIn(0f, 1f),
            props["angle"]?.firstValue(w, "stripes", "angle") ?: 45f,
            phaseT?.scalarAtEased(0f) ?: 0f,
            props["color"]?.packedColor(w, "stripes", "color") ?: 0x2D1EF6.toFloat(),
            (props["smoothing"]?.firstValue(w, "stripes", "smoothing") ?: 0.01f).coerceIn(0f, 0.5f),
            boolOf(props["punchout"], false)
        ) + tailsOf(trackTail(phaseT), trackTail(widthT))
        return addFx(clip0, ctx, ClipFxLibrary.STRIPES_AM, values, "stripes")
    }

    /**
     * `turbulentdisplace3` -> the app's Turbulent Displace 3 (Alight): Ashima turbulence
     * along a direction (X / Y / XY / R / phi-r / phi). Keyframed intensity / scale ride
     * tails. screenSpace only swaps the stretch in the layer-anchored port (the pattern
     * itself stays layer-anchored; reported when asked). A stray `FIXTURB` property in
     * some exports is not in AM's def and is ignored, like any unknown property.
     */
    private fun applyTurbulentDisplace3Am(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val intT = props["intensity"]
        val scaleT = props["scale"]
        val parr = props["parr"]?.valueAtEased(0f)
        val pos = props["position"]?.valueAtEased(0f)
        val stretch = props["stretch"]?.valueAtEased(0f)
        val screen = boolOf(props["screenSpace"], false)
        if (screen > 0.5f) {
            w += "Effect turbulentdisplace3: screen space is not reproduced: the pattern stays layer-anchored"
        }
        val flags = screen + 2f * boolOf(props["singleAxis"], true)
        val values = listOf(
            (intT?.scalarAtEased(0f) ?: 0.25f).coerceIn(0f, 2.5f),
            (scaleT?.scalarAtEased(0f) ?: 1f),
            props["evolution"]?.firstValue(w, "turbulentdisplace3", "evolution") ?: 0f,
            props["seed"]?.firstValue(w, "turbulentdisplace3", "seed") ?: 0f,
            (props["direction"]?.firstValue(w, "turbulentdisplace3", "direction") ?: 4f).coerceIn(0f, 5f),
            (props["octaves"]?.firstValue(w, "turbulentdisplace3", "octaves") ?: 6f).coerceIn(1f, 9f),
            (props["offs"]?.firstValue(w, "turbulentdisplace3", "offset") ?: 0f).coerceIn(-1f, 1f),
            parr?.getOrNull(0) ?: 0f, parr?.getOrNull(1) ?: 0f,
            pos?.getOrNull(0) ?: 0f, pos?.getOrNull(1) ?: 0f,
            stretch?.getOrNull(0) ?: 1f, stretch?.getOrNull(1) ?: 1f,
            flags
        ) + tailsOf(trackTail(intT), trackTail(scaleT))
        return addFx(clip0, ctx, ClipFxLibrary.TURBULENT_DISPLACE3_AM, values, "turbulentdisplace3")
    }

    /**
     * `roughenedges` -> the app's Roughen Edges (Alight): turbulentdisplace3's shader body
     * with the geometry fixed (direction R, detail x 5, offset 0.15), the output eroded by
     * the displaced sample's alpha. The def declares further params its shader never reads
     * (and its properties block never serializes); they are ignored like AM ignores them.
     * A keyframed evolution rides the tail.
     */
    private fun applyRoughenEdgesAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val evoT = props["evolution"]
        val values = listOf(
            (props["strength"]?.firstValue(w, "roughenedges", "strength") ?: 0.2f),
            props["detail"]?.firstValue(w, "roughenedges", "detail") ?: 1f,
            evoT?.scalarAtEased(0f) ?: 0f,
            props["seed"]?.firstValue(w, "roughenedges", "seed") ?: 0f,
            (props["octaves"]?.firstValue(w, "roughenedges", "octaves") ?: 6f).coerceIn(1f, 9f),
            0f, 0f
        ) + tailsOf(trackTail(evoT))
        return addFx(clip0, ctx, ClipFxLibrary.ROUGHEN_EDGES_AM, values, "roughenedges")
    }

    /**
     * `streaks` -> the app's Streaks (Alight): a directional multi-tap smear whose taps
     * combine per channel (min / max / off / average). A keyframed strength rides the tail.
     */
    private fun applyStreaksAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val strT = props["strength"]
        fun mode(name: String): Float =
            (props[name]?.firstValue(w, "streaks", name) ?: 1f).coerceIn(0f, 3f)
        val values = listOf(
            (strT?.scalarAtEased(0f) ?: 0.15f).coerceIn(0f, 1f),
            props["angle"]?.firstValue(w, "streaks", "angle") ?: 0f,
            (props["bias"]?.firstValue(w, "streaks", "bias") ?: 0f).coerceIn(-1f, 1f),
            (props["alpha"]?.firstValue(w, "streaks", "alpha") ?: 1f).coerceIn(0f, 1f),
            mode("rmode"), mode("gmode"), mode("bmode"), mode("amode"),
            0f, 0f, 0f, 0f, 0f, 0f
        ) + tailsOf(trackTail(strT))
        return addFx(clip0, ctx, ClipFxLibrary.STREAKS_AM, values, "streaks")
    }

    /**
     * `colorhot` -> the app's Colour Hot (Alight): the pixel luminance (gamma-shaped by the
     * colour disc's bias) picks between the two hue discs' colours, then the result's
     * luminance is restored and highlights shine toward white. The discs serialize as
     * vec3s (hue, saturation, bias). No corpus instance keyframes them.
     */
    private fun applyColorHotAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val colorT = props["color"]
        val tintT = props["tint"]
        if ((colorT?.keys?.size ?: 0) >= 2 || (tintT?.keys?.size ?: 0) >= 2) {
            w += "Effect colorhot: keyframed colour discs are not supported yet: the first value is used"
        }
        val c = colorT?.valueAtEased(0f)
        val t = tintT?.valueAtEased(0f)
        val values = listOf(
            c?.getOrNull(0) ?: 0f, c?.getOrNull(1) ?: 1f, c?.getOrNull(2) ?: 0f,
            t?.getOrNull(0) ?: 60f, t?.getOrNull(1) ?: 1f, t?.getOrNull(2) ?: 0f,
            0f
        )
        return addFx(clip0, ctx, ClipFxLibrary.COLOR_HOT_AM, values, "colorhot")
    }

    /**
     * `colorize` -> the app's Colorize (Alight): AM's own shader is the Color Tint formula
     * with the hue-disc's saturation as the amount. The tint disc serializes as a vec3.
     */
    private fun applyColorizeAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val tintT = props["tint"]
        if ((tintT?.keys?.size ?: 0) >= 2) {
            w += "Effect colorize: a keyframed tint disc is not supported yet: the first value is used"
        }
        val t = tintT?.valueAtEased(0f)
        val values = listOf(
            t?.getOrNull(0) ?: 0f, (t?.getOrNull(1) ?: 0f).coerceIn(0f, 1f),
            0f, 0f, 0f, 0f, 0f
        )
        return addFx(clip0, ctx, ClipFxLibrary.COLORIZE_AM, values, "colorize")
    }

    /** `highlightshadow` -> the app's Highlights & Shadows (Alight): GPUImage's formula, verbatim in AM's def. */
    private fun applyHighlightShadowAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val values = listOf(
            (props["shadows"]?.firstValue(w, "highlightshadow", "shadows") ?: 1f).coerceIn(0f, 2f),
            (props["highlights"]?.firstValue(w, "highlightshadow", "highlights") ?: 1f).coerceIn(0f, 2f),
            0f, 0f, 0f, 0f, 0f
        )
        return addFx(clip0, ctx, ClipFxLibrary.HIGHLIGHT_SHADOW_AM, values, "highlightshadow")
    }

    /** `spotcolor2` -> the app's Spot Colour (Alight), shader group 0 (group 1 is the eyedropper passthrough). */
    private fun applySpotColorAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val values = listOf(
            props["color"]?.packedColor(w, "spotcolor2", "color") ?: 0xFF0000.toFloat(),
            (props["threshold"]?.firstValue(w, "spotcolor2", "threshold") ?: 0.35f).coerceIn(0f, 2f),
            (props["feather"]?.firstValue(w, "spotcolor2", "feather") ?: 0.25f).coerceIn(0f, 2f),
            (props["saturation"]?.firstValue(w, "spotcolor2", "saturation") ?: 0f).coerceIn(0f, 1f),
            (props["choke"]?.firstValue(w, "spotcolor2", "choke") ?: 5f).coerceIn(0.5f, 20f),
            0f, 0f
        )
        return addFx(clip0, ctx, ClipFxLibrary.SPOT_COLOR_AM, values, "spotcolor2")
    }

    /** `dissolve` -> the app's Dissolve (Alight): a per-pixel hash (on the layer's own pixel grid) keeps pixels at random >= amount. A keyframed amount rides the tail. */
    private fun applyDissolveAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val amountT = props["amount"]
        val values = listOf(
            (amountT?.scalarAtEased(0f) ?: 0.75f).coerceIn(0f, 1f),
            props["seed"]?.firstValue(w, "dissolve", "seed") ?: 0f,
            0f, 0f, 0f, 0f, 0f
        ) + tailsOf(trackTail(amountT))
        return addFx(clip0, ctx, ClipFxLibrary.DISSOLVE_AM, values, "dissolve")
    }

    /**
     * `simplestars2` -> the app's Simple Stars (Alight): procedural star layers (the def's
     * adjusted colour applies the field twice — kept) blended over the layer. The parallax
     * xyz and the star size are keyframed in the corpus and ride tails.
     */
    private fun applySimpleStarsAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val sizeT = props["starSize"]
        val parT = props["starParallax"]
        val par = parT?.valueAtEased(0f)
        val values = listOf(
            props["scale"]?.firstValue(w, "simplestars2", "scale") ?: 1f,
            (sizeT?.scalarAtEased(0f) ?: 0.1f).coerceIn(0f, 5f),
            props["starFeather"]?.firstValue(w, "simplestars2", "star feather") ?: 1f,
            props["color"]?.packedColor(w, "simplestars2", "color") ?: 0xFFFFFF.toFloat(),
            (props["starCount"]?.firstValue(w, "simplestars2", "star count") ?: 25f).coerceIn(1f, 50f),
            props["brightness"]?.firstValue(w, "simplestars2", "brightness") ?: 1f,
            (props["blendMode"]?.firstValue(w, "simplestars2", "blend mode") ?: 2f).coerceIn(0f, 3f),
            par?.getOrNull(0) ?: 0f, par?.getOrNull(1) ?: 0f, par?.getOrNull(2) ?: 0f,
            (props["alpha"]?.firstValue(w, "simplestars2", "alpha") ?: 1f).coerceIn(0f, 1f),
            0f, 0f, 0f
        ) + tailsOf(
            pointTail(parT, 0) { it }, pointTail(parT, 1) { it }, pointTail(parT, 2) { it },
            trackTail(sizeT)
        )
        return addFx(clip0, ctx, ClipFxLibrary.SIMPLE_STARS_AM, values, "simplestars2")
    }

    /**
     * `ridges2` / `ridges` -> the app's Ridges (Alight): a ridged multifractal field mixed
     * between two colours over the layer. The two versions differ ONLY in the stretch fold
     * (v1: scalar stretch, argument multiplied; v2: point stretch, argument divided) — [13]
     * carries the version. Offs / parallax (v1) and evolution (v2) are keyframed in the
     * corpus and ride tails.
     */
    private fun applyRidgesAm(fx: Element, clip0: TimelineClip, ctx: Ctx, id: String): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val version = if (id == RIDGES2) 2f else 1f
        val offsT = props["offs"]
        val parrT = props["parr"]
        val evoT = props["evolution"]
        val offs = offsT?.valueAtEased(0f)
        val parr = parrT?.valueAtEased(0f)
        val stretch = props["stretch"]?.valueAtEased(0f)
        val stretchX = stretch?.getOrNull(0) ?: (if (version == 2f) 1f else 0f)
        val stretchY = stretch?.getOrNull(1) ?: (if (version == 2f) 1f else stretchX)
        val values = listOf(
            (props["intensity"]?.firstValue(w, "ridges", "intensity") ?: 1f).coerceIn(0.1f, 1.5f),
            props["scale"]?.firstValue(w, "ridges", "scale") ?: 1f,
            evoT?.scalarAtEased(0f) ?: 0f,
            props["seed"]?.firstValue(w, "ridges", "seed") ?: 0f,
            (props["octaves"]?.firstValue(w, "ridges", "octaves") ?: 6f).coerceIn(1f, 9f),
            props["color1"]?.packedColor(w, "ridges", "color 1") ?: 0x000000.toFloat(),
            props["color2"]?.packedColor(w, "ridges", "color 2") ?: 0xFFFFFF.toFloat(),
            offs?.getOrNull(0) ?: 0f, offs?.getOrNull(1) ?: 0f,
            parr?.getOrNull(0) ?: 0f, parr?.getOrNull(1) ?: 0f,
            stretchX, stretchY, version
        ) + tailsOf(
            pointTail(offsT, 0) { it }, pointTail(offsT, 1) { it },
            pointTail(parrT, 0) { it }, pointTail(parrT, 1) { it }, trackTail(evoT)
        )
        return addFx(clip0, ctx, ClipFxLibrary.RIDGES_AM, values, "ridges")
    }

    /**
     * `dots2` -> the app's Dots (Alight): a stepped Voronoi dot field (its points come from
     * the McGuire VALUE-noise pair, not a hash) blended over the layer. Keyframed phase /
     * density ride tails.
     */
    private fun applyDotsAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val phaseT = props["phase"]
        val densityT = props["density"]
        val pos = props["position"]?.valueAtEased(0f)
        val values = listOf(
            props["scale"]?.firstValue(w, "dots2", "scale") ?: 1f,
            phaseT?.scalarAtEased(0f) ?: 0f,
            props["radius"]?.firstValue(w, "dots2", "radius") ?: 0.2f,
            props["feather"]?.firstValue(w, "dots2", "feather") ?: 0.1f,
            props["color"]?.packedColor(w, "dots2", "color") ?: 0xFFFFFF.toFloat(),
            props["color2"]?.packedColor(w, "dots2", "color 2") ?: 0x000000.toFloat(),
            (densityT?.scalarAtEased(0f) ?: 12f).coerceIn(1f, 13f),
            pos?.getOrNull(0) ?: 0f, pos?.getOrNull(1) ?: 0f,
            (props["fill"]?.firstValue(w, "dots2", "fill") ?: 1f).coerceIn(0f, 1f),
            (props["alpha"]?.firstValue(w, "dots2", "alpha") ?: 1f).coerceIn(0f, 1f),
            (props["blendMode"]?.firstValue(w, "dots2", "blend mode") ?: 0f).coerceIn(0f, 2f),
            props["seed"]?.firstValue(w, "dots2", "seed") ?: 0f,
            props["slack"]?.firstValue(w, "dots2", "slack") ?: 1f
        ) + tailsOf(trackTail(phaseT), trackTail(densityT))
        return addFx(clip0, ctx, ClipFxLibrary.DOTS_AM, values, "dots2")
    }

    /**
     * `bendwarp` -> the app's Bend Warp (Alight): past an anchor line the layer bends along
     * a circular arc; before it, the opposite type decides freeze / bend. Keyframed amount /
     * offset ride tails.
     */
    private fun applyBendWarpAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val amountT = props["amount"]
        val offsetT = props["offset"]
        val values = listOf(
            (amountT?.scalarAtEased(0f) ?: 0f).coerceIn(-180f, 180f),
            (offsetT?.scalarAtEased(0f) ?: 0f).coerceIn(0f, 1f),
            (props["originType"]?.firstValue(w, "bendwarp", "origin") ?: 3f).coerceIn(0f, 3f),
            (props["oppositeType"]?.firstValue(w, "bendwarp", "opposite") ?: 0f).coerceIn(0f, 2f),
            (props["highLimit"]?.firstValue(w, "bendwarp", "high limit") ?: 0.75f).coerceIn(0f, 1f),
            (props["lowLimit"]?.firstValue(w, "bendwarp", "low limit") ?: 0.25f).coerceIn(0f, 1f),
            0f
        ) + tailsOf(trackTail(amountT), trackTail(offsetT))
        return addFx(clip0, ctx, ClipFxLibrary.BEND_WARP_AM, values, "bendwarp")
    }

    /**
     * `displacemap3` / `displacemap2` -> the app's Displace Map (Alight): their recovered
     * shaders are byte-identical. The def's second input is srcType "comp" — the composite
     * of everything BELOW the layer — which is exactly the backdrop the renderer captures
     * for Copy Background, so the dedicated pass samples it: the layer's own channels pick
     * the offset, the backdrop is what gets drawn. (displacemap v1's shader differs and it
     * stays unmapped.) The offset is keyframed in most instances and rides two tails.
     * `offsLimits` is declared in the def but never read by its shader.
     */
    private fun applyDisplaceMapAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val offsT = props["offs"]
        val offs = offsT?.valueAtEased(0f)
        val values = listOf(
            offs?.getOrNull(0) ?: 0f, offs?.getOrNull(1) ?: 0f,
            (props["typex"]?.firstValue(w, "displacemap3", "x source") ?: 1f).coerceIn(0f, 3f),
            (props["typey"]?.firstValue(w, "displacemap3", "y source") ?: 2f).coerceIn(0f, 3f),
            (props["edges"]?.firstValue(w, "displacemap3", "edges") ?: 1f).coerceIn(0f, 3f),
            boolOf(props["fromctr"], false),
            boolOf(props["invert"], false)
        ) + tailsOf(pointTail(offsT, 0) { it }, pointTail(offsT, 1) { it })
        return addFx(clip0, ctx, ClipFxLibrary.DISPLACE_MAP_AM, values, "displacemap3")
    }

    /** `displacemaprad` -> the app's Displace Map Radial (Alight): the backdrop sample rotates / pushes radially by the layer's channels. A keyframed offset angle rides the tail. */
    private fun applyDisplaceMapRadAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val angleT = props["offsAngle"]
        val cen = props["centerPoint"]?.valueAtEased(0f)
        val values = listOf(
            (angleT?.scalarAtEased(0f) ?: 0f).coerceIn(-3600f, 3600f),
            (props["offsRadius"]?.firstValue(w, "displacemaprad", "offset radius") ?: 0f).coerceIn(-1000f, 1000f),
            (props["typex"]?.firstValue(w, "displacemaprad", "x source") ?: 1f).coerceIn(0f, 3f),
            (props["typey"]?.firstValue(w, "displacemaprad", "y source") ?: 2f).coerceIn(0f, 3f),
            (props["edges"]?.firstValue(w, "displacemaprad", "edges") ?: 1f).coerceIn(0f, 3f),
            boolOf(props["fromctr"], false),
            boolOf(props["invert"], false),
            cen?.getOrNull(0) ?: 0f, cen?.getOrNull(1) ?: 0f,
            0f, 0f, 0f, 0f, 0f
        ) + tailsOf(trackTail(angleT))
        return addFx(clip0, ctx, ClipFxLibrary.DISPLACE_MAP_RAD_AM, values, "displacemaprad")
    }

    /**
     * `smoothbevel` -> the app's Smooth Bevel (Alight): a two-pass distance scan (X into a
     * packed buffer, then Y) shaded by a diffuse + a specular light. A keyframed specular
     * angle rides the tail.
     */
    private fun applySmoothBevelAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val angleST = props["angle_s"]
        val values = listOf(
            (props["radius"]?.firstValue(w, "smoothbevel", "radius") ?: 44f).coerceIn(0f, 200f),
            props["angle_d"]?.firstValue(w, "smoothbevel", "diffuse angle") ?: 45f,
            angleST?.scalarAtEased(0f) ?: 45f,
            (props["intensity_d"]?.firstValue(w, "smoothbevel", "diffuse intensity") ?: 0.5f).coerceIn(0f, 1f),
            (props["intensity_s"]?.firstValue(w, "smoothbevel", "specular intensity") ?: 1f).coerceIn(0f, 1f),
            props["elevation_d"]?.firstValue(w, "smoothbevel", "diffuse elevation") ?: 0f,
            props["elevation_s"]?.firstValue(w, "smoothbevel", "specular elevation") ?: 2.2f,
            (props["spec"]?.firstValue(w, "smoothbevel", "specular coefficient") ?: 35f).coerceIn(1f, 50f),
            props["color_d"]?.packedColor(w, "smoothbevel", "diffuse color") ?: 0xFFFFFF.toFloat(),
            props["color_s"]?.packedColor(w, "smoothbevel", "specular color") ?: 0xFFFFFF.toFloat(),
            0f, 0f, 0f, 0f
        ) + tailsOf(trackTail(angleST))
        return addFx(clip0, ctx, ClipFxLibrary.SMOOTH_BEVEL_AM, values, "smoothbevel")
    }

    /**
     * `lightglow` -> the app's Light Glow (Alight): bright-pick over a threshold, an
     * incremental-Gaussian blur at half resolution, a screen composite with an optional
     * halo, copied back up — all four passes share ONE shader in the def (the build-118
     * park note blamed a host downsample shader; the def's buffers are plain
     * downsample="2" targets, so the effect is implementable after all). Both corpus
     * instances are hidden, so they stay hidden-skipped; this mapping serves future files.
     */
    private fun applyLightGlowAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val values = listOf(
            (props["strength"]?.firstValue(w, "lightglow", "strength") ?: 0.25f).coerceIn(0f, 2f),
            (props["threshold"]?.firstValue(w, "lightglow", "threshold") ?: 0.7f).coerceIn(0f, 1f),
            (props["intensity"]?.firstValue(w, "lightglow", "intensity") ?: 1f).coerceIn(0f, 5f),
            props["color"]?.packedColor(w, "lightglow", "color") ?: 0xFF5566.toFloat(),
            (props["blend"]?.firstValue(w, "lightglow", "blend") ?: 0.25f).coerceIn(0f, 1f),
            (props["halo"]?.firstValue(w, "lightglow", "halo") ?: 0f).coerceIn(0f, 5f),
            (props["alpha"]?.firstValue(w, "lightglow", "alpha") ?: 0.75f).coerceIn(0f, 1f)
        )
        return addFx(clip0, ctx, ClipFxLibrary.LIGHT_GLOW_AM, values, "lightglow")
    }

    /**
     * `inner-glow` -> the app's Inner Glow (Alight): a two-pass distance scan shades the
     * layer inward from its edges with the glow colour (normal / multiply / screen).
     * Missed by every id-list census (it sits outside `com.alightcreative.effects.*`);
     * the v165 full-corpus sweep found its one visible instance, in
     * shake-technical-jm-legacy.xml. The def's colour declares an alpha the shader never
     * reads, so the packed colour drops it like the rest of the tier.
     */
    private fun applyInnerGlowAm(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val values = listOf(
            (props["radius"]?.firstValue(w, "inner-glow", "radius") ?: 50f).coerceIn(0f, 400f),
            (props["alpha"]?.firstValue(w, "inner-glow", "alpha") ?: 0.75f).coerceIn(0f, 1f),
            (props["hardness"]?.firstValue(w, "inner-glow", "hardness") ?: 0.2f).coerceIn(0f, 1f),
            props["color"]?.packedColor(w, "inner-glow", "color") ?: 0xFFFFFF.toFloat(),
            (props["blendMode"]?.firstValue(w, "inner-glow", "blend mode") ?: 2f).coerceIn(0f, 2f),
            0f, 0f
        )
        return addFx(clip0, ctx, ClipFxLibrary.INNER_GLOW_AM, values, "inner-glow")
    }

    /** `exposure` -> the app's Exposure: stops as-is, offset 0..1 -> -100..100, gamma as a percent. */
    private fun applyExposureFx(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val props = fxProps(fx)
        val evT = props["exposure"]
        val offT = props["offset"]
        val gammaT = props["gamma"]
        val ev = evT?.scalarAtEased(0f) ?: 0f
        val gamma = gammaT?.scalarAtEased(0f) ?: 1f
        val offset = offT?.scalarAtEased(0f) ?: 0f
        return addFx(
            clip0, ctx, ClipFxLibrary.EXPOSURE,
            withTails(
                listOf(
                    ev.coerceIn(-5f, 5f),
                    (offset * 100f).coerceIn(-100f, 100f),
                    (gamma * 100f).coerceIn(20f, 300f),
                    0f
                ),
                tailsOf(trackTail(evT), trackTail(offT), trackTail(gammaT))
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
     * (`angle`) and the vertical offset mode have no counterpart and are reported. A keyframed `scale` or
     * `phase` (the corpus animates both heavily — the phase sweeps run to several whole cycles) rides the
     * values tail: [AmFxEval] resolves the one track into both axis slots per frame (the tile shader reads
     * phase through fract / mod, so whole cycles in the tail stay frame-faithful).
     */
    private fun applyTile(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val aspects = ArrayList<String>()
        if ((props["angle"]?.peakAbs() ?: 0f) > 1e-4f) aspects += "its angle is not reproduced"
        val vertT = props["vertoffs"]
        if (vertT != null && (listOf(vertT.constant) + vertT.keys.map { it.v })
                .any { (it.firstOrNull() ?: 0f) > 0.5f }
        ) aspects += "its vertical offset is not reproduced"
        if (aspects.isNotEmpty()) w += "Effect tile is mapped approximately: " + aspects.joinToString(", ")
        val scaleT = props["scale"]
        val scale = scaleT?.scalarAtEased(0f) ?: 1f
        val mirror = if ((props["mirror"]?.scalarAt(0f) ?: 0f) > 0.5f) 1f else 0f
        val phaseT = props["phase"]
        val phase = ((phaseT?.scalarAtEased(0f) ?: 0f) * 100f).coerceIn(-100f, 100f)
        val tiles = scale.coerceIn(1f, 10f)
        return addFx(
            clip0, ctx, ClipFxLibrary.TILE,
            withTails(
                listOf(tiles, tiles, mirror, phase, phase, 0f, 0f),
                tailsOf(trackTail(scaleT), trackTail(phaseT))
            ),
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
        val strengthT = props["strength"]
        val strength = strengthT?.scalarAtEased(0f) ?: 0f
        val angleT = props["angle"]
        val angle = ((angleT?.scalarAtEased(0f) ?: 0f) % 360f + 360f) % 360f
        return addFx(
            clip0, ctx, ClipFxLibrary.RGB_SPLIT,
            withTails(
                listOf((strength * 100f).coerceIn(0f, 100f), angle, 0f),
                tailsOf(trackTail(strengthT), trackTail(angleT))
            ),
            "rgbsep"
        )
    }

    /**
     * `hueshift`: a constant shift becomes the app's Hue / Saturation effect. A keyframed shift on a layer
     * with a plain colour of its own (a solid-filled shape, a text layer) is baked into the FILL_COLOR
     * keyframes instead — an HSV hue rotation of the layer's colour by the shift at every keyframe time.
     * Anywhere else (a group, a gradient or picture fill) the shift rides the Hue / Saturation values tail
     * in RAW degrees (the sweeps run to several full turns: 1190 in the corpus) and [AmFxEval] resolves it
     * per frame — the engine's hue rotation fmods by 360, so unwrapped degrees stay exact.
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
            return addFx(
                clip0, ctx, ClipFxLibrary.HUE_SAT,
                withTails(
                    listOf(hueT.scalarAtEased(0f), 0f, 0f, 0f),
                    tailsOf(trackTail(hueT), emptyList())
                ),
                "hueshift"
            )
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

    /**
     * `satvib` -> Hue / Saturation: saturation 0..1 -> %, vibrance is 1-based in Alight, 0-based here.
     * Alight serialises the vibrance property as `vib` (all 56 corpus instances; a `vibrance` spelling
     * is accepted as a fallback) — before build 117 the importer read only `vibrance`, so every import
     * silently took the default 1. A keyframed saturation rides the values tail (in [AmFxEval]'s
     * Hue / Saturation order it is the second track; the hue track is a placeholder); a keyframed
     * vibrance has no tail (none in the corpus) and keeps the first-value note.
     */
    private fun applySatVib(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val satT = props["saturation"]
        val sat = satT?.scalarAtEased(0f) ?: 0f
        val vib = (props["vib"] ?: props["vibrance"])?.firstValue(w, "satvib", "vib") ?: 1f
        return addFx(
            clip0, ctx, ClipFxLibrary.HUE_SAT,
            withTails(
                listOf(0f, (sat * 100f).coerceIn(-100f, 100f), 0f, ((vib - 1f) * 100f).coerceIn(-100f, 100f)),
                tailsOf(emptyList(), trackTail(satT))
            ),
            "satvib"
        )
    }

    /** `brightcont2` -> Brightness & Contrast, both 0..1 in Alight, -100..100 in the app. */
    private fun applyBrightCont(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val props = fxProps(fx)
        val bT = props["brightness"]
        val cT = props["contrast"]
        val b = bT?.scalarAtEased(0f) ?: 0f
        val c = cT?.scalarAtEased(0f) ?: 0f
        return addFx(
            clip0, ctx, ClipFxLibrary.BRIGHT_CONTRAST,
            withTails(
                listOf((b * 100f).coerceIn(-100f, 100f), (c * 100f).coerceIn(-100f, 100f)),
                tailsOf(trackTail(bT), trackTail(cT))
            ),
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
        if ((props["punchout"]?.peakAbs() ?: 0f) > 0.5f) aspects += "its punch-out"
        if ((props["tint"]?.peakAbs() ?: 0f) > 1e-4f) aspects += "its tint"
        val overlayT = props["overlaycolor"]
        val overlay = if (overlayT != null) {
            (listOf(overlayT.constant) + overlayT.keys.map { it.v }).any { unpacked(it) != 0xFF000000L }
        } else false
        if (overlay) aspects += "its overlay colour"
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
        val short = fx.getAttribute("id").substringAfterLast('.')
        val props = fxProps(fx)
        val strengthT = props["strength"]
        val strength = strengthT?.scalarAtEased(0f) ?: 0f
        return addFx(
            clip0, ctx, ClipFxLibrary.RADIAL_BLUR,
            withTails(
                listOf((strength * 100f).coerceIn(0f, 100f), 100f, 50f, 50f),
                tailsOf(trackTail(strengthT))
            ),
            short
        )
    }

    /** `dblur` -> Directional Blur: its angle and strength; the app's comet trail stays off. */
    private fun applyDirectionalBlur(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val props = fxProps(fx)
        val angleT = props["angle"]
        val angle = ((angleT?.scalarAtEased(0f) ?: 0f) % 360f + 360f) % 360f
        val strengthT = props["strength"]
        val strength = strengthT?.scalarAtEased(0f) ?: 0f
        return addFx(
            clip0, ctx, ClipFxLibrary.DIR_BLUR,
            withTails(
                listOf(angle, (strength * 100f).coerceIn(0f, 100f), 0f),
                tailsOf(trackTail(angleT), trackTail(strengthT))
            ),
            "dblur"
        )
    }

    /**
     * `repeat` -> the app's Repeat effect (see [RepeatEval]): count copies, each shifted by `offset`
     * (scene px -> preview px, like every position), turned by `angle`, scaled by `scale`, faded by
     * `alpha`. The corpus only pins count + offset (every instance has angle 0, scale 1, alpha 1,
     * time 0); a non-zero `time` (a per-copy time shift) has no counterpart in the draw loops and
     * is reported, not guessed. Keyframed parameters take their first value (the pass-2 rule).
     * Every layer kind draws its copies: shape / text / sticker layers expand copy clips in the
     * overlay loops, video / photo and group layers draw their picture once per copy pose
     * (RepeatDraw).
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
     * The repeat family's alternating-copy colour, packed the way [packColor] packs it (two floats
     * plus a flag), or three zeroes when `colorAltCopies` is off or the effect has no fill colour:
     * with it on, the odd copies take the effect's `fillColor` instead of the layer's own colour
     * (the ST Intro scatter dots alternate white / lavender exactly so). Stored after the panel
     * values of the Repeat Radial / Scatter effects (see [RepeatEval]).
     */
    private fun repeatAltColorPacked(props: Map<String, Track>): List<Float> {
        val alt = (props["colorAltCopies"]?.scalarAt(0f) ?: 0f) != 0f
        val colT = props["fillColor"]
        if (!alt || colT == null) return listOf(0f, 0f, 0f)
        val (pa, pb) = packColor(unpacked(colT.first()))
        return listOf(pa, pb, 1f)
    }

    /**
     * The repeat family's copy-window / ordering properties (start / end / phase / easing / overlap /
     * shape / invert / random order): all default in every corpus file, and the app's copy expansion
     * has no counterpart for any of them, so a non-default one is named once, not silently dropped.
     */
    private fun warnRepeatExtras(props: Map<String, Track>, w: MutableList<String>, short: String) {
        fun nonDefault(name: String, default: Float): Boolean {
            val t = props[name] ?: return false
            return (listOf(t.constant) + t.keys.map { it.v }).any { (it.firstOrNull() ?: default) != default }
        }
        if (nonDefault("start", 0f) || nonDefault("end", 1f) || nonDefault("phase", 0f) ||
            nonDefault("easeIn", 0f) || nonDefault("easeOut", 0f) || nonDefault("overlap", 0f) ||
            nonDefault("shape", 0f) ||
            (props["invert"]?.scalarAt(0f) ?: 0f) != 0f ||
            (props["randomOrder"]?.scalarAt(0f) ?: 0f) != 0f
        ) {
            w += "Effect " + short + " is mapped approximately: its start / end window and copy ordering are not reproduced"
        }
    }

    /**
     * `repeat.radial` -> the app's Repeat Radial effect (see [RepeatEval]): [count] copies on a ring
     * of `radius` around the layer (scene px -> preview px, like every position), the first at
     * `startAngle`, sharing `sweep`, each turned by `orientation` and scaled by `baseScale`
     * (x `scale` per step). Keyframed parameters take their first value (the pass-2 rule). Like
     * the plain repeat, it draws on every layer kind (RepeatDraw for picture / group layers).
     */
    private fun applyRepeatRadial(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val rawCount = (props["count"]?.firstValue(w, "repeat.radial", "count") ?: 1f).roundToInt()
        val count = rawCount.coerceIn(1, RepeatEval.MAX_COUNT)
        if (count != rawCount) {
            w += "Effect repeat.radial: count " + rawCount + " is clamped to " + count + ", the most the app draws"
        }
        val radius = props["radius"]?.firstValue(w, "repeat.radial", "radius") ?: 0f
        val startAngle = props["startAngle"]?.firstValue(w, "repeat.radial", "start angle") ?: 0f
        val sweep = props["sweep"]?.firstValue(w, "repeat.radial", "sweep") ?: 360f
        val orientation = props["orientation"]?.firstValue(w, "repeat.radial", "orientation") ?: 0f
        val baseScale = props["baseScale"]?.firstValue(w, "repeat.radial", "base scale") ?: 1f
        val scale = props["scale"]?.firstValue(w, "repeat.radial", "scale") ?: 1f
        val alpha = props["alpha"]?.firstValue(w, "repeat.radial", "alpha") ?: 1f
        val angle = props["angle"]?.firstValue(w, "repeat.radial", "angle") ?: 0f
        warnRepeatExtras(props, w, "repeat.radial")
        return addFx(
            clip0, ctx, ClipFxLibrary.REPEAT_RADIAL,
            listOf(
                count.toFloat(),
                radius * ctx.k,
                startAngle,
                sweep,
                orientation,
                (baseScale * 100f).coerceIn(0f, 200f),
                (alpha * 100f).coerceIn(0f, 100f),
                angle,
                (scale * 100f).coerceIn(0f, 200f)
            ) + repeatAltColorPacked(props),
            "repeat.radial"
        )
    }

    /**
     * `repeat.scatter` -> the app's Repeat Scatter effect (see [RepeatEval]): [count] copies over a
     * disc of `radius` around the layer, shifted by `offset` (scene px -> preview px), placed by the
     * `scatterSeed` field. The `evolution` track is stored as its first and last keyframes (a longer
     * track's middle is approximated) and drifts the field at draw time. `blend` has no counterpart
     * and is reported when non-zero. Like the plain repeat, it draws on every layer kind
     * (RepeatDraw for picture / group layers).
     */
    private fun applyRepeatScatter(fx: Element, clip0: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val rawCount = (props["count"]?.firstValue(w, "repeat.scatter", "count") ?: 1f).roundToInt()
        val count = rawCount.coerceIn(1, RepeatEval.MAX_COUNT)
        if (count != rawCount) {
            w += "Effect repeat.scatter: count " + rawCount + " is clamped to " + count + ", the most the app draws"
        }
        val radius = props["radius"]?.firstValue(w, "repeat.scatter", "radius") ?: 0f
        val offset = props["offset"]?.let {
            if (it.keys.size >= 2) w += "Effect repeat.scatter: keyframed offset is not supported yet: the first value is used"
            it.first()
        } ?: listOf(0f, 0f)
        val seed = props["scatterSeed"]?.firstValue(w, "repeat.scatter", "seed") ?: 0f
        val scale = props["scale"]?.firstValue(w, "repeat.scatter", "scale") ?: 0f
        val alpha = props["alpha"]?.firstValue(w, "repeat.scatter", "alpha") ?: 1f
        val angle = props["angle"]?.firstValue(w, "repeat.scatter", "angle") ?: 0f
        if ((props["blend"]?.scalarAt(0f) ?: 0f) != 0f) {
            w += "Effect repeat.scatter is mapped approximately: its blend is not reproduced"
        }
        warnRepeatExtras(props, w, "repeat.scatter")
        // The evolution track as (t0, v0, t1, v1): first and last keyframes, or the constant twice.
        val evo = props["evolution"]
        val evol = when {
            evo == null -> listOf(0f, 0f, 1f, 0f)
            evo.keys.isEmpty() -> {
                val v = evo.constant.firstOrNull() ?: 0f
                listOf(0f, v, 1f, v)
            }
            else -> {
                if (evo.keys.size > 2) {
                    w += "Effect repeat.scatter: keyframed evolution is approximated by its first and last keyframes"
                }
                val f = evo.keys.first()
                val l = evo.keys.last()
                listOf(f.t, f.v.firstOrNull() ?: 0f, l.t, l.v.firstOrNull() ?: 0f)
            }
        }
        return addFx(
            clip0, ctx, ClipFxLibrary.REPEAT_SCATTER,
            listOf(
                count.toFloat(),
                // The field's radius is stored scaled by the scene's height ratio: round 5's frame
                // measurements put every one of Alight's copies within sceneH/sceneW of the written
                // radius (1242 -> 699 on 1920x1080) of the layer — see RepeatEval.
                radius * ctx.k * ctx.sceneH / ctx.sceneW,
                (offset.getOrNull(0) ?: 0f) * ctx.k,
                (offset.getOrNull(1) ?: 0f) * ctx.k,
                seed,
                (scale * 100f).coerceIn(0f, 100f),
                (alpha * 100f).coerceIn(0f, 100f),
                angle
            ) + evol + repeatAltColorPacked(props),
            "repeat.scatter"
        )
    }

    /**
     * `motionblur` / `motionblur2` / `motionblur3` / `motionblur4` -> the app's Motion Blur (see MotionBlurEval for
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
        val tuneT = props["tune"]
        val tune = tuneT?.scalarAtEased(0f) ?: 1f
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
            withTails(
                listOf(
                    (tune * 100f).coerceIn(0f, 100f),
                    if (usePos) 1f else 0f, if (useScale) 1f else 0f, if (useAngle) 1f else 0f
                ),
                // The tail holds tune RAW (not the slot's 0..100 coercion): [AmFxEval] rewrites
                // slot 0 per frame and MotionBlurEval reads the resolved strength, so a tune
                // sweeping past 1 (4.0 in am-renderer-preset3) keeps its peak.
                tailsOf(trackTail(tuneT))
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
        val strengthT = props["strength"]
        val strength = strengthT?.scalarAtEased(0f) ?: 0f
        val radius = props["radius"]?.scalarAt(0f) ?: 0f
        val radiusPct = if (radius <= 1f) radius * 100f else 100f
        return addFx(
            clip0, ctx, ClipFxLibrary.BULGE,
            withTails(
                listOf((strength * 100f).coerceIn(-100f, 100f), radiusPct.coerceIn(0f, 100f), 50f, 50f),
                tailsOf(trackTail(strengthT))
            ),
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
            val base = baseKeys?.valueAt(t)
            val baseA = base?.first ?: clip0.scalePercent
            val baseB = base?.second ?: if (clip0.type == LayerType.SHAPE) clip0.shapeScaleY * 100f else 0f
            raw += Keyframe(t, baseA * f, baseB * f, Easing.LINEAR)
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

    // ---- move along path ------------------------------------------------------------------------------------------

    /**
     * The vector outline of one shape layer of a scene, in raw Alight values (scene pixels), kept so a
     * `movealongpath3` on the layer above can follow it. [kind] is the shape reader's pattern
     * ("CIRCLE" / "RECT" / "ROUNDED_RECT"), "LINE" (an `.line` / `.arrow`: the outline is its
     * segment, [p1] -> [p2]), or "PATH" (a freehand `<path>` shape: the outline is its flattened
     * raw points, [pathPts]). The transform / size tracks are the layer's own, evaluated with
     * Alight's easing at the follower's time; a line's scale / rotation / pivot enter as first
     * values and a shape's pivot not at all — exactly how the layer's own reader renders it, so the
     * follower stays glued to the outline as the app itself draws that layer.
     */
    private class PathSpec(
        val kind: String,
        val startMs: Long, val durMs: Long,
        val loc: Track?, val scl: Track?, val rot: Track?,
        val size: Track? = null, val corner: Float = 0f,
        val p1: List<Float>? = null, val p2: List<Float>? = null, val pivot: List<Float> = emptyList(),
        /** A freehand path layer ("PATH"): its raw flattened outline points (anchor-relative) and whether it closes. */
        val pathPts: List<Pair<Float, Float>>? = null, val pathClosed: Boolean = false
    )

    /** A recorded `movealongpath3`, resolved by [resolveMoveAlongPaths] once the scene is fully read. */
    private class PendingPath(
        val followerId: String, val parented: Boolean,
        val progress: Track, val angle: Track?, val tangent: Boolean, val inset: Track?
    )

    /** A point of an outline in scene pixels, with its (unit) direction of travel. */
    private class PathPoint(val x: Float, val y: Float, val tanX: Float, val tanY: Float)

    /**
     * A dense walk of an outline: its points joined by straight segments with cumulative lengths, so
     * a fraction of the total length lands on the outline. The chord error of the sampling (720
     * steps around an ellipse, 16 per rounded corner) is far below a pixel at scene sizes.
     */
    private class OutlineWalk(pts: List<Pair<Float, Float>>, private val closed: Boolean) {
        private val segA = ArrayList<Pair<Float, Float>>()
        private val segB = ArrayList<Pair<Float, Float>>()
        private val segStart = ArrayList<Float>()
        val total: Float

        init {
            var acc = 0f
            val n = if (closed) pts.size else pts.size - 1
            for (i in 0 until n) {
                val a = pts[i]
                val b = pts[(i + 1) % pts.size]
                val len = hypot(b.first - a.first, b.second - a.second)
                if (len < 1e-9f) continue
                segA += a
                segB += b
                segStart += acc
                acc += len
            }
            total = acc
        }

        fun pointAt(frac: Float): PathPoint {
            if (segA.isEmpty() || total <= 1e-6f) return PathPoint(0f, 0f, 1f, 0f)
            val f = if (closed) {
                val m = frac % 1f
                if (m < 0f) m + 1f else m
            } else frac.coerceIn(0f, 1f)
            val d = (f * total).coerceAtMost(total)
            var lo = 0
            var hi = segStart.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (segStart[mid] <= d) lo = mid else hi = mid - 1
            }
            val a = segA[lo]
            val b = segB[lo]
            val len = hypot(b.first - a.first, b.second - a.second)
            val u = if (len > 1e-9f) ((d - segStart[lo]) / len).coerceIn(0f, 1f) else 0f
            return PathPoint(
                a.first + (b.first - a.first) * u, a.second + (b.second - a.second) * u,
                (b.first - a.first) / len, (b.second - a.second) / len
            )
        }
    }

    /**
     * The outline points of a shape in its own (unrotated, centre-relative) frame, half extents
     * [a] / [b] and world corner radii [rx] / [ry]. The traversal starts and runs as the stroke
     * trim's outline does (see the C++ shape engine): a circle at the +x axis, a rectangle at the
     * top-left corner along the top edge, always clockwise in screen space (y down) — so a follower
     * synced to a `drawing.progress` on the path layer (the corpus case) rides its drawing tip.
     */
    private fun outlinePoints(kind: String, a: Float, b: Float, rx: Float, ry: Float): List<Pair<Float, Float>> {
        return when (kind) {
            "CIRCLE" -> {
                val steps = 720
                (0 until steps).map { i ->
                    val th = 2.0 * Math.PI * i / steps
                    (a * cos(th).toFloat()) to (b * sin(th).toFloat())
                }
            }
            "RECT" -> listOf(-a to -b, a to -b, a to b, -a to b)
            else -> {   // ROUNDED_RECT
                val crx = minOf(rx, a).coerceAtLeast(0f)
                val cry = minOf(ry, b).coerceAtLeast(0f)
                if (crx < 0.01f || cry < 0.01f) return listOf(-a to -b, a to -b, a to b, -a to b)
                val ex = a - crx
                val ey = b - cry
                val pts = ArrayList<Pair<Float, Float>>()
                pts += (-ex to -b)
                pts += (ex to -b)
                // Corner arcs, centres (+-ex, +-ey), walked angle-proportionally like the trim's.
                fun arc(cx: Float, cy: Float, fromDeg: Double, toDeg: Double, skipLast: Boolean) {
                    val steps = 16
                    for (i in 1..steps) {
                        if (skipLast && i == steps) break
                        val th = Math.toRadians(fromDeg + (toDeg - fromDeg) * i / steps)
                        pts += (cx + crx * cos(th).toFloat()) to (cy + cry * sin(th).toFloat())
                    }
                }
                arc(ex, -ey, -90.0, 0.0, skipLast = false)
                pts += (a to ey)
                arc(ex, ey, 0.0, 90.0, skipLast = false)
                pts += (-ex to b)
                arc(-ex, ey, 90.0, 180.0, skipLast = false)
                pts += (-a to -ey)
                arc(-ex, -ey, 180.0, 270.0, skipLast = true)   // its end is the start point again
                pts
            }
        }
    }

    /** The outline walk of a shape layer at its normalized time [n] (shape kinds only). */
    private fun PathSpec.shapeWalk(n: Float): OutlineWalk {
        if (kind == "PATH") {
            // A freehand outline: the raw points, scaled by the layer's own scale — they are already
            // anchor-relative, like the built-in kinds' outlines.
            val sc = scl?.valueAtEased(n) ?: listOf(1f, 1f)
            val sx = sc.getOrNull(0) ?: 1f
            val sy = sc.getOrNull(1) ?: sx
            val pts = (pathPts ?: emptyList()).map { (it.first * sx) to (it.second * sy) }
            return OutlineWalk(pts, closed = pathClosed)
        }
        val scl = scl?.valueAtEased(n) ?: listOf(1f, 1f)
        val size = size?.valueAtEased(n) ?: listOf(100f, 100f)
        val sx = scl.getOrNull(0) ?: 1f
        val sy = scl.getOrNull(1) ?: sx
        val a = (size.getOrNull(0) ?: 100f) * sx
        val b = (size.getOrNull(1) ?: size.getOrNull(0) ?: 100f) * sy
        return OutlineWalk(outlinePoints(kind, a, b, corner * sx, corner * sy), closed = true)
    }

    /** A line point in scene pixels, placed exactly as [readLineShape] places it. */
    private fun PathSpec.linePoint(p: List<Float>, loc: List<Float>): Pair<Float, Float> {
        val sclV = scl?.first() ?: listOf(1f, 1f)
        val rotV = rot?.first()?.firstOrNull() ?: 0f
        val px = (p[0] - (pivot.getOrNull(0) ?: 0f)) * (sclV.getOrNull(0) ?: 1f)
        val py = (p[1] - (pivot.getOrNull(1) ?: 0f)) * (sclV.getOrNull(1) ?: sclV.getOrNull(0) ?: 1f)
        val rad = Math.toRadians(rotV.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        return ((loc.getOrNull(0) ?: 0f) + px * c - py * s) to
            ((loc.getOrNull(1) ?: 0f) + px * s + py * c)
    }

    /** The outline's total length (scene pixels) at scene time [sceneMs]. */
    private fun PathSpec.lengthAt(sceneMs: Long): Float {
        val n = if (durMs > 0L) ((sceneMs - startMs).toFloat() / durMs).coerceIn(0f, 1f) else 0f
        if (kind == "LINE") {
            val loc = loc?.valueAtEased(n) ?: listOf(0f, 0f)
            val a = linePoint(p1 ?: return 0f, loc)
            val b = linePoint(p2 ?: return 0f, loc)
            return hypot(b.first - a.first, b.second - a.second)
        }
        return shapeWalk(n).total
    }

    /**
     * The outline point at arc-length fraction [frac] at scene time [sceneMs], offset by [inset]
     * along the path's left normal — for the clockwise outlines that normal points at the shape's
     * interior, so a positive inset rides inside the outline (a circle's radius shrinks by it).
     * The path layer's transform is applied around the point, so a moving / turning outline
     * carries the follower with it.
     */
    private fun PathSpec.pointAt(sceneMs: Long, frac: Float, inset: Float): PathPoint {
        val n = if (durMs > 0L) ((sceneMs - startMs).toFloat() / durMs).coerceIn(0f, 1f) else 0f
        if (kind == "LINE") {
            val loc = loc?.valueAtEased(n) ?: listOf(0f, 0f)
            val a = linePoint(p1 ?: listOf(0f, 0f), loc)
            val b = linePoint(p2 ?: listOf(0f, 0f), loc)
            val walk = OutlineWalk(listOf(a, b), closed = false)
            val p = walk.pointAt(frac)
            return PathPoint(p.x - p.tanY * inset, p.y + p.tanX * inset, p.tanX, p.tanY)
        }
        val p = shapeWalk(n).pointAt(frac)
        val ox = p.x - p.tanY * inset
        val oy = p.y + p.tanX * inset
        val loc = loc?.valueAtEased(n) ?: listOf(0f, 0f)
        val rotDeg = rot?.valueAtEased(n)?.firstOrNull() ?: 0f
        if (abs(rotDeg) < 1e-6f) {
            return PathPoint((loc.getOrNull(0) ?: 0f) + ox, (loc.getOrNull(1) ?: 0f) + oy, p.tanX, p.tanY)
        }
        val rad = Math.toRadians(rotDeg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        return PathPoint(
            (loc.getOrNull(0) ?: 0f) + ox * c - oy * s, (loc.getOrNull(1) ?: 0f) + ox * s + oy * c,
            p.tanX * c - p.tanY * s, p.tanX * s + p.tanY * c
        )
    }

    /**
     * `movealongpath3`: Alight's own rule (its Layer Parenting tutorial) — the layer moves along the
     * vector outline of the layer just below it on the timeline; a layer with a parent would follow
     * the parent's outline instead. The path belongs to another layer, so the request is only
     * recorded here; [resolveMoveAlongPaths] bakes it once the whole scene is read.
     */
    private fun applyMoveAlongPath(fx: Element, el: Element, clip: TimelineClip, ctx: Ctx): TimelineClip {
        val w = ctx.warnings
        val props = fxProps(fx)
        val progress = props["progress"]
        if (progress == null) {
            w += "Effect movealongpath3 on \"" + clip.name + "\" has no progress property: skipped"
            return clip
        }
        if (ctx.pendingPaths.any { it.followerId == clip.id }) {
            w += "Effect movealongpath3: layer \"" + clip.name + "\" already follows a path: the second one is skipped"
            return clip
        }
        ctx.pendingPaths += PendingPath(
            followerId = clip.id,
            parented = el.getAttribute("parent").isNotEmpty(),
            progress = progress,
            angle = props["angle"],
            tangent = (props["tangent"]?.scalarAt(0f) ?: 0f) >= 0.5f,
            inset = props["inset"]
        )
        return clip
    }

    /**
     * Pairs every recorded `movealongpath3` with the layer directly below its follower in the same
     * scene (per-element slots, like [resolveMaskLinks]: a skipped element in between is a gap, not
     * a path). The covered form is baked by [bakeMoveAlongPath]; the rest is reported, not guessed:
     * a parented follower (Alight would follow the parent's outline instead of the layer below —
     * the parenting itself IS imported, only the ride is not), a group follower (its stored pose is
     * an anchor, not a plain position), a follower with no imported layer below it, and a layer
     * below without a vector outline (text, group, audio — only shape layers register one).
     */
    private fun resolveMoveAlongPaths(directSlots: ArrayList<TimelineClip?>, flat: ArrayList<TimelineClip>, ctx: Ctx) {
        if (ctx.pendingPaths.isEmpty()) return
        val w = ctx.warnings
        for (p in ctx.pendingPaths) {
            val i = directSlots.indexOfFirst { it?.id == p.followerId }
            val follower = if (i >= 0) directSlots[i] else null
            if (follower == null) continue   // cannot happen: the follower was read in this scene
            val below = if (i >= 1) directSlots[i - 1] else null
            val spec = below?.let { ctx.outlineSpecs[it.id] }
            when {
                p.parented -> w += "Effect movealongpath3 on \"" + follower.name +
                    "\": the layer is parented in Alight, so it would follow its parent's outline instead " +
                    "of the layer below: the ride is skipped (the parenting itself is imported)"
                follower.type == LayerType.GROUP -> w += "Effect movealongpath3 on a group layer (\"" +
                    follower.name + "\") is not supported yet: skipped"
                below == null -> w += "Effect movealongpath3 on \"" + follower.name +
                    "\": there is no imported layer below it to take the path from: skipped"
                spec == null -> w += "Effect movealongpath3 on \"" + follower.name +
                    "\": the layer below it (\"" + below.name + "\") has no vector outline to follow: skipped"
                else -> {
                    val baked = bakeMoveAlongPath(follower, spec, p, ctx)
                    if (baked == null) {
                        w += "Effect movealongpath3 on \"" + follower.name +
                            "\": the outline of the layer below it has no length: skipped"
                    } else {
                        directSlots[i] = baked
                        val fi = flat.indexOfFirst { it.id == follower.id }
                        if (fi >= 0) flat[fi] = baked
                    }
                }
            }
        }
    }

    /**
     * Bakes one `movealongpath3` into the follower's POSITION keyframes (and ROTATION when the
     * effect turns the layer): samples sit on the scene's frames plus every keyframe of the
     * progress / angle / inset tracks and of the path layer's own transform, so the corners stay
     * exact; redundant keys are simplified away. Unlike the procedural bakes, the path REPLACES
     * the layer's own position — Alight puts the layer ON the outline, its authored location is
     * not added (the corpus layer's location sits ~9 px off its ring, an author's by-eye
     * placement; adding it would throw the dot off the ring entirely). `progress` is the
     * arc-length fraction along the outline, eased as Alight eases it; past the ends it wraps on
     * a closed outline and clamps on a line. With `tangent` on, the layer's rotation follows the
     * path's direction (plus the effect's `angle`, which also applies on its own); the composed
     * rotation is unwrapped so a full turn stays a full turn instead of spinning back at the
     * 180-degree seam. A follower that also carries a positional bake (oscillate / shake) loses
     * it to the replacement — no corpus layer combines them.
     */
    private fun bakeMoveAlongPath(
        follower: TimelineClip, spec: PathSpec, p: PendingPath, ctx: Ctx
    ): TimelineClip? {
        val durMs = follower.durationMs
        if (durMs <= 0L) return null
        val startMs = follower.startMs
        if (spec.lengthAt(startMs + durMs / 2) < 1e-3f) return null
        val extras = ArrayList<Long>()
        for (t in listOf(p.progress, p.angle, p.inset)) {
            t?.keys?.forEach { extras += (it.t.coerceIn(0f, 1f) * durMs).roundToLong() }
        }
        for (t in listOf(spec.loc, spec.scl, spec.rot, spec.size)) {
            t?.keys?.forEach { k ->
                val local = spec.startMs + (k.t.coerceIn(0f, 1f) * spec.durMs).roundToLong() - startMs
                if (local in 0..durMs) extras += local
            }
        }
        val baseRot = follower.keyframes[AnimProp.ROTATION]
        baseRot?.forEach { extras += it.timeMs }
        val times = sampleTimes(durMs, ctx.fps, 0f, extras)
        val closed = spec.kind != "LINE"
        fun pointAt(t: Long): PathPoint {
            val norm = t.toFloat() / durMs
            val prog = p.progress.scalarAtEased(norm)
            val frac = if (closed) {
                val m = prog % 1f
                if (m < 0f) m + 1f else m
            } else prog.coerceIn(0f, 1f)
            return spec.pointAt(startMs + t, frac, p.inset?.scalarAtEased(norm) ?: 0f)
        }
        val raw = times.map { t ->
            val pt = pointAt(t)
            Keyframe(t, ctx.posX(listOf(pt.x, pt.y)), ctx.posY(listOf(pt.x, pt.y)), Easing.LINEAR)
        }
        val keys = simplifyKeys(raw, 0.05f, 0.05f)
        if (keys.size < 2) return null
        var clip = follower.copy(keyframes = follower.keyframes + (AnimProp.POSITION to keys))
        keys.firstOrNull()?.let { clip = clip.withProp(AnimProp.POSITION, it.a, it.b) }

        val angleConst = p.angle?.scalarAtEased(0f) ?: 0f
        val angleKeyframed = (p.angle?.keys?.size ?: 0) >= 2
        if (p.tangent || angleKeyframed || abs(angleConst) > 1e-4f) {
            var prev = Double.NaN
            val rawRot = times.map { t ->
                val norm = t.toFloat() / durMs
                var total = (baseRot?.valueAt(t)?.first ?: follower.rotationDeg) +
                    (p.angle?.scalarAtEased(norm) ?: 0f)
                if (p.tangent) {
                    val pt = pointAt(t)
                    total += Math.toDegrees(atan2(pt.tanY.toDouble(), pt.tanX.toDouble())).toFloat()
                }
                if (!prev.isNaN()) {
                    var adj = total.toDouble()
                    while (adj - prev > 180.0) adj -= 360.0
                    while (prev - adj > 180.0) adj += 360.0
                    total = adj.toFloat()
                }
                prev = total.toDouble()
                Keyframe(t, total, 0f, Easing.LINEAR)
            }
            val rotKeys = simplifyKeys(rawRot, 0.02f, 0.02f)
            if (rotKeys.size >= 2) {
                clip = clip.copy(keyframes = clip.keyframes + (AnimProp.ROTATION to rotKeys))
                rotKeys.firstOrNull()?.let { clip = clip.withProp(AnimProp.ROTATION, it.a, 0f) }
            }
        }
        return clip
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
        // No weight in the spec means Alight's regular text weight, 400. Only `imported` fonts omit it
        // (their weight lives in the font file, which the app cannot load, so the substituted family is
        // drawn regular — like Alight itself does when the file is missing on the phone). The old 600
        // default made every such layer render semibold / synthetically bold (ST Intro's "Channel Name").
        val weight = (parts["weight"]?.toIntOrNull() ?: 400).coerceIn(100, 900)
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
