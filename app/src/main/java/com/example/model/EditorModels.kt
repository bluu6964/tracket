package com.example.model

import kotlinx.serialization.Serializable

const val TIMELINE_PX_PER_MS = 0.045f

const val FILL_NONE = 0
const val FILL_SOLID = 1
const val FILL_GRADIENT = 2
const val FILL_IMAGE = 3
/** A playing video clipped to the layer's outline (build 104); the ref is [TimelineClip.fillVideoUri]. */
const val FILL_VIDEO = 4

/** How a picture fills a layer: cover it (crop), fit inside it, or stretch to it. */
const val IMG_FILL = 0
const val IMG_FIT = 1
const val IMG_STRETCH = 2

const val GRAD_LINEAR = 0
const val GRAD_RADIAL = 1
const val GRAD_SWEEP = 2

/** Ops of one segment of a freehand path ([TimelineClip.pathSegments]). */
const val PATH_OP_MOVE = 0
const val PATH_OP_LINE = 1
const val PATH_OP_QUAD = 2
const val PATH_OP_CUBIC = 3
const val PATH_OP_CLOSE = 4

/**
 * One segment of a freehand path outline. [pts] holds the segment's points as x,y pairs
 * (move / line: 1 pair, quad: 2, cubic: 3, close: none), in the basis [TimelineClip.pathSegments]
 * documents. The points are absolute (a relative command of the source file is absolutized at
 * import), so a renderer can replay the segments in order.
 */
@Serializable
data class PathSeg(
    val op: Int,
    val pts: List<Float> = emptyList()
)

/**
 * The outline of a freehand path as a polyline: every segment's end point, curves flattened at
 * [stepsPerCurve] samples each. Move segments start a new sub-path (their point is included);
 * a close segment repeats its sub-path's first point. Used by the importer's bounds and by tests.
 */
fun flattenPathSegments(segments: List<PathSeg>, stepsPerCurve: Int = 24): List<Pair<Float, Float>> {
    val out = ArrayList<Pair<Float, Float>>()
    var cur = 0f to 0f
    var start = cur
    fun quad(p0: Pair<Float, Float>, p1: Pair<Float, Float>, p2: Pair<Float, Float>, t: Float): Pair<Float, Float> {
        val u = 1f - t
        return (u * u * p0.first + 2f * u * t * p1.first + t * t * p2.first) to
            (u * u * p0.second + 2f * u * t * p1.second + t * t * p2.second)
    }
    fun cubic(p0: Pair<Float, Float>, p1: Pair<Float, Float>, p2: Pair<Float, Float>, p3: Pair<Float, Float>, t: Float): Pair<Float, Float> {
        val u = 1f - t
        return (u * u * u * p0.first + 3f * u * u * t * p1.first + 3f * u * t * t * p2.first + t * t * t * p3.first) to
            (u * u * u * p0.second + 3f * u * u * t * p1.second + 3f * u * t * t * p2.second + t * t * t * p3.second)
    }
    for (seg in segments) {
        val q = seg.pts
        when (seg.op) {
            PATH_OP_MOVE -> if (q.size >= 2) {
                cur = q[0] to q[1]; start = cur; out += cur
            }
            PATH_OP_LINE -> if (q.size >= 2) {
                cur = q[0] to q[1]; out += cur
            }
            PATH_OP_QUAD -> if (q.size >= 4) {
                val c = q[0] to q[1]
                val e = q[2] to q[3]
                for (i in 1..stepsPerCurve) out += quad(cur, c, e, i.toFloat() / stepsPerCurve)
                cur = e
            }
            PATH_OP_CUBIC -> if (q.size >= 6) {
                val c1 = q[0] to q[1]
                val c2 = q[2] to q[3]
                val e = q[4] to q[5]
                for (i in 1..stepsPerCurve) out += cubic(cur, c1, c2, e, i.toFloat() / stepsPerCurve)
                cur = e
            }
            PATH_OP_CLOSE -> {
                out += start
                cur = start
            }
        }
    }
    return out
}

/**
 * Canvas shape. "Original" follows the first video / photo (see [withOriginalAspect]);
 * until there is media it is 16:9.
 */
@Serializable
data class AspectRatio(val label: String, val ratioFloat: Float, val iconDescription: String) {
    val isOriginal: Boolean get() = label == "Original"

    /** For "Original": the same ratio with the real shape of the source media. */
    fun withOriginalAspect(sourceAspect: Float): AspectRatio =
        if (isOriginal && sourceAspect > 0.05f) copy(ratioFloat = sourceAspect) else this

    companion object {
        val ORIGINAL = AspectRatio("Original", 16f / 9f, "Shape of the first video or photo")
        val RATIO_16_9 = AspectRatio("16:9", 16f / 9f, "YouTube Landscape")
        val RATIO_9_16 = AspectRatio("9:16", 9f / 16f, "TikTok / Reels / Shorts")
        val RATIO_1_1 = AspectRatio("1:1", 1f, "Instagram Square")
        val RATIO_4_5 = AspectRatio("4:5", 4f / 5f, "Instagram Portrait")
        val RATIO_21_9 = AspectRatio("21:9", 21f / 9f, "Cinematic Ultrawide")
        val RATIO_4_3 = AspectRatio("4:3", 4f / 3f, "Standard Format")

        fun values(): List<AspectRatio> =
            listOf(ORIGINAL, RATIO_9_16, RATIO_16_9, RATIO_1_1, RATIO_4_5, RATIO_21_9, RATIO_4_3)
    }
}

@Serializable
enum class LayerType(val displayName: String) {
    VIDEO("Video"),
    AUDIO("Audio"),
    SHAPE("Shape"),
    OVERLAY("Overlay"),
    STICKER("Sticker"),
    TEXT("Text"),
    /** Null object: an invisible layer (never drawn / exported) that other layers can have as parent, see [ParentLink]. */
    NULL("Null"),
    /** 3D camera: an invisible layer; the layers in the lanes below it are seen through it, see CameraRig. */
    CAMERA("Camera"),
    /** Chain: a lane that holds several layers side by side (children have chainId = its id), see LaneLayout.placeInChain. Never drawn. */
    CHAIN("Chain"),
    /**
     * Group (real grouping, Alight Motion style): a layer without a picture that has its own move / scale / turn / opacity.
     * The layers inside (groupId = its id, each linked to it by [TimelineClip.parent]) follow it, see ParentRig and [GroupOps].
     */
    GROUP("Group")
}

@Serializable
enum class PreviewResolution(val label: String, val detail: String) {
    FULL("1080p", "High Quality Preview"),
    HALF("720p", "Smooth Performance"),
    QUARTER("480p", "Fastest Editing")
}

@Serializable
data class TimelineClip(
    val id: String,
    val name: String,
    val type: LayerType,
    val startMs: Long,
    val durationMs: Long,
    val color: Long,
    val drawableResId: Int? = null,
    val text: String? = null,
    val textColor: Long = 0xFFFFFFFF,
    /** Fill of shape and text layers: 0 = none, 1 = solid (textColor), 2 = gradient, 3 = image. */
    val fillMode: Int = FILL_SOLID,
    /** Gradient look: 0 = linear, 1 = radial, 2 = sweep. Two stops: gradColor1 -> gradColor2. */
    val gradType: Int = GRAD_LINEAR,
    val gradColor1: Long = 0xFF1FD91F,
    val gradColor2: Long = 0xFFFFFFFF,
    /**
     * The two gradient points (0..1 inside the layer). Linear: start -> end. Radial: centre = point 1,
     * radius = distance to point 2. Sweep: centre = point 1, start direction = towards point 2.
     */
    val gradX1: Float = 0.05f,
    val gradY1: Float = 0.5f,
    val gradX2: Float = 0.95f,
    val gradY2: Float = 0.5f,
    /** Image fill (fillMode == FILL_IMAGE): picture uri and IMG_FILL / IMG_FIT / IMG_STRETCH. */
    val fillImageUri: String? = null,
    val fillImageMode: Int = IMG_FILL,
    /**
     * Video fill (fillMode == FILL_VIDEO, build 104): the video that plays inside the shape's outline,
     * mapped by the same [fillImageMode]. Its source window rides on the clip's own media fields:
     * [trimStartMs] = the file position at the layer's start, [sourceDurationMs] = the window's end
     * (AM's `inTime` / `outTime` on the shape element), played linearly like a media layer.
     */
    val fillVideoUri: String? = null,
    // ---- Text layer styling (only used by LayerType.TEXT) ----
    val fontFamily: String = "Roboto",
    val fontWeight: Int = 600,
    val isItalic: Boolean = false,
    val isUnderline: Boolean = false,
    val isStrikethrough: Boolean = false,
    /** 0 = left, 1 = center, 2 = right */
    val textAlign: Int = 1,
    /** Letter spacing, -10..40 (percent of the font size). */
    val letterSpacing: Float = 0f,
    /** Line height, 80..200 (percent). */
    val lineHeightPercent: Float = 120f,
    /**
     * Widest line of text as a fraction of the frame width; 0 = the app default (0.9). An imported
     * Alight layer keeps its `wrapWidth` (in scene pixels) here so its lines break where Alight's did.
     */
    val textWrapFrac: Float = 0f,
    /** Text outline thickness, 0..100 (0 = no outline). */
    val textStrokeThickness: Float = 0f,
    val textStrokeColor: Long = 0xFF000000,
    /** Shadow: opacity 0 turns it off. All values 0..100 except angle (0..360). */
    val shadowOpacity: Float = 60f,
    val shadowBlur: Float = 30f,
    val shadowDistance: Float = 10f,
    val shadowAngle: Float = 45f,
    val shadowColor: Long = 0xFF000000,
    /** Background box behind the text: opacity 0 turns it off. */
    val bgOpacity: Float = 0f,
    val bgColor: Long = 0xFF000000,
    val bgCorner: Float = 20f,
    val bgPadding: Float = 30f,
    val shapePattern: String? = null,
    val shapeOffsetX: Float = 0f,
    val shapeOffsetY: Float = 0f,
    val shapeScaleX: Float = 1.0f,
    val shapeScaleY: Float = 1.0f,
    /**
     * Corner radius of a rounded shape: 65 x rho in the 130 dp shape box's dp, where rho is the radius
     * as a fraction of the shape's SMALLER half extent (rho = 1 is a stadium — see
     * [shapeCornerRadii]); -1 = not set, the renderers then use their built-in corner (about 16 dp).
     * Only read for rounded patterns. The shape editor's slider writes this same unit (0..65 dp).
     */
    val shapeCornerRadius: Float = -1f,
    val strokeWidth: Float = 0f,
    val strokeColor: Long = 0xFFFFFFFF,
    /**
     * Stroke trim (draw-on, Alight Motion's `drawing.progress`): the visible part of the shape's outline,
     * as fractions of its length (0..1). The defaults show the whole outline. Animatable (STROKE_TRIM:
     * a = start, b = end).
     */
    val strokeTrimStart: Float = 0f,
    val strokeTrimEnd: Float = 1f,
    /**
     * True when the layer's paint IS its path (an imported line, baked to a thin rectangle): the trim then
     * gates the fill along the rectangle's length instead of gating a stroke.
     */
    val strokeTrimFill: Boolean = false,
    /**
     * True when the trim came from a `drawing.progress` on a TEXT layer: the renderers then draw the
     * glyphs' OUTLINES (in the text colour) trimmed by [strokeTrimStart] / [strokeTrimEnd] over the
     * concatenated outline length, instead of the filled text — Alight Motion's draw-on handwriting look.
     * The outline width is the layer's own text stroke width when it has one, else a default share of
     * the font size (see NativeTextPainter).
     */
    val strokeTrimText: Boolean = false,
    /**
     * Freehand outline of a `shapePattern == "PATH"` layer (an imported Alight Motion `<path>` shape —
     * a preset layer with no `s` kind): the segments in the shape box's own units. The path's bounding
     * box was centred on the layer anchor (any offset of the box from the layer's own origin is folded
     * into the clip's position) and divided by the box's larger half extent, so coordinates span
     * [-1, 1] on that axis; the box scale ([shapeScaleX] / [shapeScaleY], built from that same half
     * extent and the layer's transform scale) carries the true size on both axes, so the renderers can
     * replay the segments scaled by their usual box. Empty for every built-in kind.
     */
    val pathSegments: List<PathSeg> = emptyList(),
    // Transform parameters (2D, Pivot, 3D, Warp)
    val transformX: Float = 0f,
    val transformY: Float = 0f,
    /** Depth position (3D tab): toward the camera = bigger, away = smaller. */
    val transformZ: Float = 0f,
    val scalePercent: Float = 100f,
    val rotationDeg: Float = 0f,
    val pivotX: Float = 0.5f,
    val pivotY: Float = 0.5f,
    val rotation3dX: Float = 0f,
    val rotation3dY: Float = 0f,
    val warpDistort: Float = 0f,
    // Crop parameters
    val cropWidthPercent: Float = 100f,
    val cropHeightPercent: Float = 100f,
    val cropHorizontalPercent: Float = 50f,
    val cropVerticalPercent: Float = 50f,
    val cropPreset: String = "Free",
    /** Width / height of the source media as displayed (0 = unknown). Crop is relative to the media itself. */
    val sourceAspect: Float = 0f,
    val volume: Float = 1.0f,
    val speed: Float = 1.0f,
    val isMuted: Boolean = false,
    /** Audio layers: fade in / fade out length on the timeline, in ms (0 = no fade). */
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    val isLocked: Boolean = false,
    /** When false the layer is hidden on the canvas and skipped on export (eye control). */
    val isVisible: Boolean = true,
    val isFlippedH: Boolean = false,
    val isFlippedV: Boolean = false,
    /** Blending mode name, see [BlendModes] ("Normal" = plain). */
    val blendMode: String = "Normal",
    /** Layer opacity, 0..100 (percent). */
    val opacity: Float = 100f,
    val effectName: String? = null,
    val animationName: String? = null,
    /** In / Out animation applied from the Animations panel (the keyframes are the truth, this is what the panel shows). */
    val animIn: ClipAnim? = null,
    val animOut: ClipAnim? = null,
    // Real media (null for text/shape/demo layers)
    val uri: String? = null,
    val mimeType: String? = null,
    val isImage: Boolean = false,
    val sourceDurationMs: Long = 0L,
    val trimStartMs: Long = 0L,
    /** Transform keyframes per property (times relative to the layer start), see [AnimProp]. */
    val keyframes: Map<AnimProp, List<Keyframe>> = emptyMap(),
    /** Motion tracking: this layer follows an object of a video, frame by frame (see [TrackFollow]). */
    val trackFollow: TrackFollow? = null,
    /** Corner pin (planar track, Mocha-style): this picture is drawn on a tracked flat surface of a video (see [CornerPin]). */
    val cornerPin: CornerPin? = null,
    /** Remove (planar track + clean patch, Mocha-style): a flaw on a flat surface of this video is covered by a patch (see [PlanarRemove]). */
    val planarRemove: PlanarRemove? = null,
    /** Cutout (person only): folder of per-frame masks made by RVM, null = no cutout. See CutoutVideo. */
    val cutoutDir: String? = null,
    val cutoutFps: Float = 0f,
    /** Source time (ms) of mask frame 0, and how many mask frames there are. */
    val cutoutStartMs: Long = 0L,
    val cutoutFrames: Int = 0,
    /** Face AI blur: folder of per-frame masks (white = blur here) made with MediaPipe Face Landmarker. See FaceBlurVideo. */
    val faceBlurDir: String? = null,
    val faceBlurFps: Float = 0f,
    /** Source time (ms) of mask frame 0, and how many mask frames there are. */
    val faceBlurStartMs: Long = 0L,
    val faceBlurFrames: Int = 0,
    /** Blur strength 0..1 and the region name (FACE / EYES / NOSE / LIPS) the masks were made for. */
    val faceBlurStrength: Float = 0.5f,
    val faceBlurRegion: String = "",
    /** Effects of the Effects panel (blur, glow, RGB split, ...), run by the C++ engine in list order. See [ClipFxLibrary]. */
    val effects: List<ClipFx> = emptyList(),
    /** Masks of the Masks panel (shapes with feather / invert / combine mode, animatable), run by the C++ engine after the effects. See [ClipMaskLibrary]. */
    val masks: List<ClipMask> = emptyList(),
    /** Parent (a Null object): this layer follows the move / turn / scale of that Null, see ParentRig. */
    val parent: ParentLink? = null,
    /** Id of the Chain layer this layer sits in (same lane), null = a normal layer. */
    val chainId: String? = null,
    /** Id of the real Group layer this layer is inside (it follows that group, see [GroupOps]), null = not in a group. */
    val groupId: String? = null,
    /**
     * Group layers imported from an Alight `<embedScene>`: the embedded scene's own canvas size,
     * in stored (preview) units like every other size (0 = not an imported embed, or an older
     * project). The group path needs it to place the group's OWN masks: an imported `wipe2` on the
     * embed is written against the embed's box (this rect, as the group's transform places it),
     * not against the frame the composed picture happens to fill. A user-made group keeps 0 and
     * its masks stay in picture units, as authored.
     */
    val embedW: Float = 0f,
    /** See [embedW]. */
    val embedH: Float = 0f,
    /**
     * Group layers only: true after the person trimmed the group's edge. The group then keeps its own length (the layers
     * inside are cut at its ends) instead of always covering its layers, see [GroupOps.fitGroups].
     */
    val groupFreeLength: Boolean = false,
    /**
     * Alight Motion layer mask (`blending="mask"`): this layer is never drawn itself — it cuts the layer it
     * sits on (see [maskTargetId]). Only the importer sets this. The renderers skip mask layers outright, so
     * a mask can never be un-hidden into a normal (wrongly painted) layer.
     */
    val isMask: Boolean = false,
    /** On a mask layer ([isMask]): the id of the layer it cuts — the layer directly below it in its Alight scene (for a stack of masks, the common layer below the stack; every mask of the stack carries that same id). Null = the mask cuts nothing. */
    val maskTargetId: String? = null,
    /** Text animators imported from an Alight Motion XML (stored; drawn by the C++ text engine in a later step), see [TextAnimator]. */
    val textAnimators: List<TextAnimator> = emptyList()
) {
    val endMs: Long
        get() = startMs + durationMs

    fun isActiveAt(timeMs: Long): Boolean {
        return timeMs in startMs until endMs
    }

    /** True when the two clips share any part of the timeline (touching ends do not count). */
    fun overlaps(other: TimelineClip): Boolean {
        return startMs < other.endMs && other.startMs < endMs
    }
}

/**
 * Link of a layer to its parent Null object. The ref values are the Null's pose (position, scale %, rotation) at the
 * moment of linking: the layer follows the Null's change from that pose, so it does not jump when linked.
 */
@Serializable
data class ParentLink(
    val id: String,
    val refX: Float,
    val refY: Float,
    val refScale: Float,
    val refRot: Float
)

/**
 * One horizontal line (lane) of the timeline (Alight Motion style).
 * Lanes are not tied to a layer type: any layer (media, shape, text, audio, ...) can live in any lane.
 * Each lane holds at most one clip (one layer per line).
 */
@Serializable
data class TimelineTrack(
    val id: String,
    val name: String,
    val isMuted: Boolean = false,
    val isLocked: Boolean = false,
    val clips: List<TimelineClip> = emptyList()
)

@Serializable
data class ExportSettings(
    val resolution: String = "1080p FHD",
    val frameRate: Int = 30,
    val quality: String = "High (Recommended)",
    /** Custom video bitrate in kbps (500..50000); 0 = automatic from [quality]. */
    val customBitrateKbps: Int = 0,
    val format: String = "MP4 (H.264)",
    /** Canvas / background colour (ARGB). Used behind the layers of the export. */
    val canvasColor: Long = 0xFF000000
)

/** Result of reading a picked file: what the importer hands to the ViewModel. */
data class ImportedMedia(
    val uri: String,
    val name: String,
    val mimeType: String?,
    val durationMs: Long,
    val isImage: Boolean,
    val aspect: Float = 0f
)

/**
 * Fade multiplier (0..1) of an audio layer at [relMs] ms after the layer's start on the timeline.
 * Volume is applied separately (clip.volume). Used by the preview and mirrored by the exporter.
 */
fun TimelineClip.fadeGainAt(relMs: Long): Float {
    var g = 1f
    if (fadeInMs > 0L && relMs < fadeInMs) {
        g = minOf(g, relMs.coerceAtLeast(0L) / fadeInMs.toFloat())
    }
    val left = durationMs - relMs
    if (fadeOutMs > 0L && left < fadeOutMs) {
        g = minOf(g, left.coerceAtLeast(0L) / fadeOutMs.toFloat())
    }
    return g.coerceIn(0f, 1f)
}

/** Camera distance of the Z (depth) position: perspective factor = D / (D - z). */
const val DEPTH_D = 1000f

/** From this Z the layer is at / behind the camera (it flew past the screen): not drawn any more. */
const val DEPTH_HIDE_Z = 980f

/**
 * Perspective factor of a layer at depth [z]: 1 at z = 0, bigger when the layer comes toward the
 * camera (z > 0), smaller when it goes away (z < 0). It scales the layer AND its position, so a layer
 * that is off-centre moves away from the centre while it comes closer, and flies off the screen.
 */
fun depthFactorOf(z: Float): Float =
    (DEPTH_D / (DEPTH_D - z.coerceAtMost(DEPTH_HIDE_Z))).coerceAtLeast(0.001f)

val TimelineClip.depthFactor: Float get() = depthFactorOf(transformZ)

/** True when the layer is at / behind the camera (Z >= [DEPTH_HIDE_Z]): invisible. */
val TimelineClip.isBehindCamera: Boolean get() = transformZ >= DEPTH_HIDE_Z

/** Scale % after the Z (depth) position: a layer moved toward the camera looks bigger, away looks smaller. */
val TimelineClip.effectiveScalePercent: Float
    get() = scalePercent * depthFactor

/**
 * Per-axis scale the shape painters draw a SHAPE layer with (1 = the 130 dp shape box). X is the layer's
 * effective scale (after depth) — the value both painters used for both axes before the shape's own aspect
 * was honoured. Y carries that aspect on top: [shapeScaleY] relative to [shapeScaleX]. The drawn size is
 * therefore the box times ([shapeDrawScaleX], [shapeDrawScaleY]) whenever the writers' invariant
 * scalePercent = shapeScaleX x 100 holds (the importer, the shape tools, tracking), a parent group's scale
 * still flows in through [scalePercent] (ParentRig composes it), and a shape without an aspect of its own
 * (shapeScaleY == shapeScaleX — every uniform scale, keyframed or not) draws exactly as it did before.
 */
val TimelineClip.shapeDrawScaleX: Float
    get() = (effectiveScalePercent / 100f).coerceAtLeast(0.01f)

val TimelineClip.shapeDrawScaleY: Float
    get() = (shapeDrawScaleX * (if (shapeScaleX > 1e-4f) shapeScaleY / shapeScaleX else 1f)).coerceAtLeast(0.01f)

/**
 * Corner radii of a rounded shape in the painters' local units (one per axis; [base] is the shape
 * box's half in the same units), or null when no radius is stored ([shapeCornerRadius] < 0).
 *
 * The stored dp is 65 x rho, rho = the corner radius as a fraction of the shape's SMALLER half
 * extent, so the frame-space radius is rho x min(halfW, halfH) at any (keyframed) size — a true
 * circular arc, and rho = 1 a stadium however wide the shape is animated. Each local axis gets
 * that frame radius divided by its own draw scale, which is what makes the arc circular on the
 * frame instead of an ellipse stretched by the shape's aspect. For a uniform shape both axes
 * return the stored dp in local units, exactly the reading the painters used before per-axis radii.
 */
fun TimelineClip.shapeCornerRadii(base: Float): Pair<Float, Float>? {
    if (shapeCornerRadius < 0f) return null
    val rho = (shapeCornerRadius / 65f).coerceIn(0f, 1f)
    val sx = shapeDrawScaleX
    val sy = shapeDrawScaleY
    val m = minOf(sx, sy)
    return (base * rho * m / sx).coerceIn(0f, base) to (base * rho * m / sy).coerceIn(0f, base)
}

/** Position (canvas px) after perspective: the stored position times [depthFactor]. */
val TimelineClip.depthX: Float get() = transformX * depthFactor
val TimelineClip.depthY: Float get() = transformY * depthFactor

/** Highest per-layer volume: 200 %. */
const val MAX_CLIP_VOLUME = 2f
