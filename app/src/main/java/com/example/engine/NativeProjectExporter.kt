package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.model.ClipFxLibrary
import com.example.model.ClipMaskLibrary
import com.example.model.AspectRatio
import com.example.model.BlendKind
import com.example.model.BlendModes
import com.example.model.ExportSettings
import com.example.model.GifExport
import com.example.model.GifQuantizer
import com.example.model.GifWriter
import com.example.model.GroupOps
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.TimelineTrack
import com.example.model.exportSpeed
import com.example.model.effectiveScalePercent
import com.example.model.isBehindCamera
import com.example.model.opacityFraction
import com.example.model.renderAt
import com.example.model.sourceOffsetMs
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Project export done by the C++ engine (libam_engine.so):
 *   video / photo layers: NDK decoder pool -> OpenGL ES compositor -> H.264 surface encoder,
 *   sound (audio layers + the sound of video layers): NDK decoder -> C++ mixer (trim, speed with pitch
 *   kept, volume, fades) -> AAC encoder,
 *   both muxed into one MP4 by the NDK muxer.
 *
 * Text / shape / sticker layers: [LayerPainter] (Android Canvas, so Bengali shaping, fonts, gradients
 * and animated stickers look exactly like the preview) draws them into one full-frame bitmap, which
 * the C++ renderer uploads as a texture and composites on top of the video layers every frame
 * (re-drawn only when something changed).
 *
 * Cutout and face blur: mask JPEGs decoded in C++ (jpeg_luma.cpp), applied by GL passes in the renderer
 * (renderer_fx.cpp) before the layer is placed on the canvas.
 *
 * It is the only export path (the old Media3 exporter was removed in v159). If something fails
 * (e.g. a mask the C++ decoder cannot read), [VideoExporter.start] reports the error to the user.
 */
/**
 * A still-image export target (build 189): the same rendered frames as the MP4 path,
 * read back from the renderer instead of going through the H.264 encoder — an
 * animated GIF, or one PNG per frame. Neither carries sound.
 */
enum class RasterFormat { GIF, PNG_SEQUENCE }

class NativeProjectExporter(private val context: Context) {

    companion object {
        private const val TAG = "NativeProjectExporter"

        /**
         * Null when the native exporter can export this project 1:1. Otherwise the reason it cannot,
         * and the export is refused with that message.
         */
        fun unsupportedReason(tracks: List<TimelineTrack>): String? {
            if (Build.VERSION.SDK_INT < 26) return "needs Android 8.0+"
            if (!NativeExportEncoder.isSupported) return "native engine is not available"
            val clips = tracks.flatMap { it.clips }.filter { it.isVisible }
            if (clips.isEmpty()) return "nothing to export"

            // Several media layers are exported natively too (v0.1.53). If the native decoders fail on
            // a device, the export stops with an error message (there is no Media3 fallback any more).

            for (c in clips) {
                when (c.type) {
                    LayerType.TEXT, LayerType.SHAPE, LayerType.STICKER -> {}
                    // Cutout and face blur run in the C++ engine too (v0.1.100): masks are decoded in C++
                    // and applied by GL passes, so no layer kind is refused.
                    LayerType.VIDEO -> {}
                    else -> {}
                }
            }
            return null
        }

        /** "1080p FHD" + 9:16 -> 1080x1920; short side = the number, both sides even (same as VideoExporter). */
        fun outputSize(resolution: String, ratio: AspectRatio): Pair<Int, Int> {
            val short = Regex("\\d{3,4}").find(resolution)?.value?.toInt()?.coerceIn(180, 1080) ?: 1080
            val r = ratio.ratioFloat
            val (w, h) = if (r >= 1f) (short * r) to short.toFloat() else short.toFloat() to (short / r)
            fun even(x: Float) = (x.roundToInt() / 2) * 2
            return even(w) to even(h)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)
    private var worker: Thread? = null

    /**
     * Callbacks run on the main thread. [save] moves the finished file to the gallery.
     * [rasterFormat] != null exports stills instead of an MP4 (build 189): for
     * [RasterFormat.GIF] [save] receives the .gif file; for [RasterFormat.PNG_SEQUENCE]
     * it receives a DIRECTORY holding frame_0001.png, frame_0002.png, ...
     */
    fun start(
        tracks: List<TimelineTrack>,
        aspectRatio: AspectRatio,
        settings: ExportSettings,
        save: (File) -> Uri,
        onProgress: (Float) -> Unit,
        onDone: (Uri) -> Unit,
        onError: (String) -> Unit,
        rasterFormat: RasterFormat? = null
    ) {
        cancelled.set(false)
        val t = Thread({
            val stamp = System.currentTimeMillis()
            val out = when (rasterFormat) {
                RasterFormat.GIF -> File(context.cacheDir, "tracket_native_$stamp.gif")
                RasterFormat.PNG_SEQUENCE -> File(context.cacheDir, "tracket_frames_$stamp")
                null -> File(context.cacheDir, "tracket_native_$stamp.mp4")
            }
            fun deleteOut() {
                if (out.isDirectory) out.deleteRecursively() else out.delete()
            }
            try {
                runExport(tracks, aspectRatio, settings, out, rasterFormat) { p ->
                    if (!cancelled.get()) main.post { onProgress(p) }
                }
                if (cancelled.get()) { deleteOut(); return@Thread }
                val uri = save(out)
                if (!cancelled.get()) main.post { onDone(uri) }
            } catch (_: CancellationException) {
                deleteOut()
            } catch (t: Throwable) {
                deleteOut()
                Log.w(TAG, "native export failed", t)
                if (!cancelled.get()) main.post { onError(t.message ?: t.javaClass.simpleName) }
            }
        }, "am-native-export-worker")
        worker = t
        t.start()
    }

    fun cancel() {
        cancelled.set(true)
    }

    // ------------------------------------------------------------------------------------------

    private class ImageLayer(val textureId: Int, val width: Int, val height: Int)

    /** Text / shape / sticker layers drawn into one picture; [blendId] != 0 = one layer with a blend mode. */
    private class OverlayGroup(val clips: List<TimelineClip>, val blendId: Int, val pose: Boolean = false, val fx: Boolean = false, val mask: List<TimelineClip> = emptyList(), val mediaFill: Boolean = false, val ownMask: Boolean = false) {
        // fx = a layer with effects (Effects panel): its own picture, the C++ effects run on it before it is drawn.
        // mask = the mask layers cutting this layer (Alight style; several when masks are stacked): the layer has a picture of its own, cut before upload.
        // ownMask = the layer carries its OWN masks (Masks panel; an imported wipe2 lands there): its own picture,
        // cut by the remapped mask pass after the effects (see OverlayMasks). textBoxW / textBoxH = the drawn box of
        // a text layer (frame px), captured when its picture is redrawn, for that remap.
        // mediaFill = one shape filled with a playing video (build 104): its own picture holds only the
        // shape's outline; the video is composited under it, cut by the silhouette in silBmp / silTex.
        // pose = a layer turned in 3D / with its own pivot (OverlayPose.kt): drawn upright into its own picture and
        // placed by the renderer. boxW / boxH = its box at scale 1 (the pivot is a fraction of it).
        var boxW = 0f
        var boxH = 0f
        var bmp: Bitmap? = null
        var canvas: android.graphics.Canvas? = null
        var tex = 0
        var uploaded = false
        var lastSignature: List<Any>? = null
        var silBmp: Bitmap? = null
        var silTex = 0
        var silUploaded = false
        // fx layers that are not placed by the renderer: the box of the layer inside the picture (the effects run on it).
        var region: FxRegion? = null
        var textBoxW = 0f
        var textBoxH = 0f
    }

    private fun runExport(
        tracks: List<TimelineTrack>,
        aspectRatio: AspectRatio,
        settings: ExportSettings,
        outputFile: File,
        rasterFormat: RasterFormat?,
        onProgress: (Float) -> Unit
    ) {
        if (!DecoderPool.isAvailable) error("Native engine library is not available.")
        val all = tracks.flatMap { it.clips }
        // Bottom lane first, so later layers are drawn on top (same order as VideoExporter).
        val visuals = tracks.asReversed().flatMap { it.clips }
            .filter { it.type == LayerType.VIDEO && it.uri != null && it.isVisible }
        val durationMs = (if (visuals.isNotEmpty()) visuals.maxOf { it.endMs } else all.maxOf { it.endMs })
            .coerceAtLeast(100L)
        val isGif = rasterFormat == RasterFormat.GIF
        val isPng = rasterFormat == RasterFormat.PNG_SEQUENCE
        val raster = rasterFormat != null
        // GIF frames are capped to a phone-sane size (GifExport.MAX_SIDE); the PNG
        // sequence keeps the full chosen resolution, like the MP4.
        val (fullW, fullH) = outputSize(settings.resolution, aspectRatio)
        val (w, h) = if (isGif) GifExport.gifSize(fullW, fullH) else fullW to fullH
        val fps = if (isGif) settings.gifFrameRate.coerceIn(GifExport.MIN_FPS, GifExport.MAX_FPS)
            else settings.frameRate.coerceIn(24, 60)
        // Frame count, bitrate and the sound clips are decided in C++ (export_plan.cpp, tested on a PC).
        val bitrate = ExportPlanNative.bitrate(w, h, fps, settings.customBitrateKbps, settings.quality)
        val totalFrames = ExportPlanNative.totalFrames(durationMs, fps)
        if (isGif && totalFrames > GifExport.MAX_FRAMES) {
            error("This project is too long for a GIF ($totalFrames frames at $fps fps; the limit is " +
                "${GifExport.MAX_FRAMES}). Shorten the project, lower the GIF frame rate, or export an MP4.")
        }
        val canvas = settings.canvasColor.toInt()
        val bgA = ((canvas ushr 24) and 0xff) / 255f
        val bgR = ((canvas ushr 16) and 0xff) / 255f
        val bgG = ((canvas ushr 8) and 0xff) / 255f
        val bgB = (canvas and 0xff) / 255f
        outputFile.parentFile?.mkdirs()
        if (!isPng && outputFile.exists()) outputFile.delete()

        GlThread("am-native-export").use { gl ->
            gl.call {
                val renderer = NativeRenderer.create() ?: error("Native GLES renderer is unavailable on this device.")
                val pool = DecoderPool(maxHardware = PoolSizing.hw(visuals), maxSoftware = 3)
                // Raster exports render offscreen and read the pixels back; the MP4
                // path hands the renderer to the H.264 surface encoder instead.
                val encoder = if (raster) null else NativeExportEncoder.create()
                if (!raster && encoder == null) {
                    pool.close(); renderer.close()
                    error("Native export requires Android 8.0 (API 26) or newer and the native engine.")
                }
                if (raster) {
                    require(renderer.setOffscreen(w, h)) { "Could not create the image render target." }
                }
                // The GIF writer itself opens at the encoder.start point below (inside
                // the try), so a failure there takes the same cleanup path as the MP4's.
                var gifOut: java.io.BufferedOutputStream? = null
                var gifWriter: GifWriter? = null
                if (isPng) outputFile.mkdirs()
                val rgbaBuf = if (raster) ByteArray(w * h * 4) else null
                val videos = HashMap<String, VideoLayerSource>()
                // Video fills of shapes (build 104), keyed by the shape's id; opened with the picture
                // sources below, closed with them. A fill that cannot be opened is NOT an error (unlike
                // a video layer): the shape falls back to the fill's poster frame.
                val fillVideos = HashMap<String, VideoLayerSource>()
                // Keyframes / tracking / pose of every picture layer are evaluated in C++ (layer_anim.cpp).
                val anims = HashMap<String, NativeLayerAnim>()
                val previewW = PreviewFrameRef.widthPx
                // Null objects: layers that have a parent follow it (same maths as the preview, ParentRig.kt).
                val rig = ParentRig(all) { c, t -> c.renderAt(t) }
                // 3D camera: layers in the lanes below a Camera layer are seen through it (CameraRig.kt).
                val cam = CameraRig(tracks, rig)
                val camK = if (previewW > 0f) w / previewW else 1f
                // Effects as the renderer runs them this frame: Motion Blur is resolved from the
                // layer's own motion over one export frame, a keyframed Solid Color takes its
                // colour from the layer's colour track, and the Alight Mirror / Glow Scan take
                // their keyframed alpha / threshold from their values tails (AmFxEval). [sample]
                // is the clip the motion is
                // sampled from — the original, not the rendered copy, so a rig adjustment can never
                // hide the keyframes.
                val frameMs = 1000L / fps
                val canvasH = MotionBlurEval.canvasHeightPx(previewW, w, h)
                fun fxOpsOf(c: TimelineClip, timeMs: Long, sample: TimelineClip = c): FloatArray? =
                    ClipFxLibrary.toNative(
                        SolidColorEval.resolve(
                            MotionBlurEval.resolve(AmFxEval.resolve(c.effects, c, timeMs), { ms ->
                                NativeRenderAt.renderAt(sample, ms).let { MotionBlurEval.Pose(it.transformX, it.transformY, it.rotationDeg, it.scalePercent) }
                            }, timeMs, frameMs, canvasH, sample.pivotX * 100f, sample.pivotY * 100f),
                            c, timeMs
                        )
                    )
                // Puts a picture layer on the corners its camera sees it at (corner pin path); false = behind the camera.
                fun placeByCamera(clip: TimelineClip, t: Long, anim: NativeLayerAnim, fp: FloatArray, p: LayerParams, cw: Int, ch: Int): Boolean {
                    if (!cam.hasCamera || p.pin != null) return true
                    val seen = cam.seeMedia(clip, t, fp, anim.clipAt(t, t)[2], cw, ch, camK, w, h) ?: return true
                    p.pin = seen.quad ?: return false
                    return true
                }
                val images = HashMap<String, ImageLayer>()
                // Clean patches of the Remove tool (GL textures).
                val removePatches = RemovePatchCache()
                // One stacking order for pictures and overlays: bigger Z in front, equal Z = lane order (later = in front).
                val laneIdx = HashMap<String, Int>().also { m ->
                    for ((i, c) in tracks.asReversed().flatMap { it.clips }.withIndex()) m[c.id] = i
                }
                // Index of a picture layer in [visuals] (the slot of its face / cutout masks): unique over the whole project.
                val visualIndex = HashMap<String, Int>().also { m -> for ((i, c) in visuals.withIndex()) m[c.id] = i }
                // A Group layer is ONE layer: its layers are drawn into their own picture (renderer group pass) that then gets
                // the group's opacity / blend / effects / masks. So every level (the top level, and each group) has its own
                // text / shape / sticker pictures: "" = the top level, else the id of the Group layer.
                val groupIds = GroupOps.groupIdsOf(tracks)
                // Text / shape / sticker layers of one level, bottom lane first (same order as VideoExporter). Normal
                // layers that follow each other share one picture; a layer with a blend mode gets its own,
                // so the stacking order is kept.
                fun buildOverlayGroups(scopeId: String?): List<OverlayGroup> {
                    val scopeClips = GroupOps.scopeTracks(tracks, scopeId).asReversed().flatMap { it.clips }
                    val overlays = scopeClips.filter {
                        it.isVisible && !it.isMask && (it.type == LayerType.SHAPE || it.type == LayerType.TEXT || it.type == LayerType.STICKER)
                    }
                    // Mask layers of this level (never drawn themselves): target id -> the masks that cut it.
                    val maskByTarget = LayerMaskLinks.masksByTarget(scopeClips)
                    // Lanes of the pictures and groups of this level: overlays on both sides of one cannot share one picture.
                    val mediaLanes = scopeClips
                        .filter { (it.type == LayerType.VIDEO && it.uri != null && it.isVisible) || (it.type == LayerType.GROUP && it.isVisible) }
                        .map { laneIdx[it.id] ?: 0 }
                    fun mediaBetween(a: TimelineClip, b: TimelineClip): Boolean {
                        val lo = laneIdx[a.id] ?: 0
                        val hi = laneIdx[b.id] ?: 0
                        return mediaLanes.any { it in (lo + 1) until hi }
                    }
                    val level = mutableListOf<OverlayGroup>()
                    var cur = mutableListOf<TimelineClip>()
                    for (c in overlays) {
                        if (cur.isNotEmpty() && mediaBetween(cur.last(), c)) { level += OverlayGroup(cur, 0); cur = mutableListOf() }
                        val effC = c
                        val spec = BlendModes.find(effC.blendMode)
                        val hasFx = ClipFxLibrary.hasEffects(effC.effects)
                        val hasOwnMasks = OverlayMasks.hasOwnMasks(c)
                        if (mediaFillClipOf(listOf(c)) != null && !overlayNeedsPose(c) && !cam.affects(c)) {
                            // A shape filled with a playing video (build 104): always a picture of its
                            // own — the outline raster the video is composited under. Pose / camera
                            // layers keep the poster path below.
                            if (cur.isNotEmpty()) { level += OverlayGroup(cur, 0); cur = mutableListOf() }
                            level += OverlayGroup(listOf(c), spec.shaderId, fx = hasFx, mask = maskByTarget[c.id].orEmpty(), mediaFill = true, ownMask = hasOwnMasks)
                        } else if (overlayNeedsPose(c) || cam.affects(c)) {
                            if (cur.isNotEmpty()) { level += OverlayGroup(cur, 0); cur = mutableListOf() }
                            level += OverlayGroup(listOf(c), spec.shaderId, pose = true, fx = hasFx, ownMask = hasOwnMasks)
                        } else if (maskByTarget.containsKey(c.id)) {
                            // Cut by a mask layer: a picture of its own, or the cut would also take the
                            // layers it shares the picture with.
                            if (cur.isNotEmpty()) { level += OverlayGroup(cur, 0); cur = mutableListOf() }
                            level += OverlayGroup(listOf(c), spec.shaderId, fx = hasFx, mask = maskByTarget[c.id].orEmpty(), ownMask = hasOwnMasks)
                        } else if (hasOwnMasks) {
                            // Cut by its own masks (an imported wipe2): also a picture of its own.
                            if (cur.isNotEmpty()) { level += OverlayGroup(cur, 0); cur = mutableListOf() }
                            level += OverlayGroup(listOf(c), spec.shaderId, fx = hasFx, ownMask = true)
                        } else if (hasFx) {
                            if (cur.isNotEmpty()) { level += OverlayGroup(cur, 0); cur = mutableListOf() }
                            level += OverlayGroup(listOf(c), spec.shaderId, fx = true)
                        } else if (spec.isNormal) {
                            cur.add(c)
                        } else {
                            if (cur.isNotEmpty()) { level += OverlayGroup(cur, 0); cur = mutableListOf() }
                            level += OverlayGroup(listOf(c), spec.shaderId)
                        }
                    }
                    if (cur.isNotEmpty()) level += OverlayGroup(cur, 0)
                    return level
                }
                val overlayGroups = HashMap<String, List<OverlayGroup>>().also { m ->
                    m[""] = buildOverlayGroups(null)
                    for (id in groupIds) m[id] = buildOverlayGroups(id)
                }
                // A mask layer over a Group or Video layer needs the painter too (it draws the mask
                // that cuts the group's composed picture / the video's placed picture), even when the
                // project has no overlay layers at all.
                val maskTargetIds = all.filter { it.isMask }.mapNotNull { it.maskTargetId }.toSet()
                val painter = if (overlayGroups.values.any { it.isNotEmpty() } ||
                    all.any { (it.type == LayerType.GROUP || it.type == LayerType.VIDEO) && it.id in maskTargetIds }
                ) LayerPainter(w, h) else null
                // Text layers: C++ engine (chosen font, HarfBuzz shaping) when this build has it; Canvas otherwise.
                if (painter != null && TextNative.isAvailable) {
                    painter.nativeText = NativeTextPainter(fontFile = { name ->
                        com.example.ui.text.FontRepository.fileOrNull(context, name)?.absolutePath
                    })
                }
                // Text-on-path (build 191): one resolver for both text routes (the C++
                // painter and the Canvas fallback in LayerPainter), reading this export's
                // scene + rig + camera — the same numbers the shape painters draw with.
                if (painter != null) {
                    val expK = if (previewW > 0f) w / previewW else 1f
                    val pathResolver: (TimelineClip, Long) -> FloatArray? = { tc, t ->
                        TextPathGeometry.framePoints(
                            all, rig, tc, t, w, h, expK, PreviewFrameRef.density
                        ) { c -> cam.affects(c) }
                    }
                    painter.pathPoints = pathResolver
                    painter.nativeText?.pathPoints = pathResolver
                }
                // Shape / sticker layers: C++ shape engine (no FreeType / HarfBuzz needed), Canvas as fallback.
                if (painter != null && ShapeNative.isAvailable) painter.nativeShape = NativeShapePainter()
                val clearPaint = android.graphics.Paint().apply {
                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
                }
                // Alpha multiply of a layer mask (DST_IN): a masked layer's picture keeps only where its
                // mask layer is. The scratch picture the mask is drawn into is shared by all masked layers.
                val maskDstInPaint = android.graphics.Paint().apply {
                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
                }
                // Erase by an Exclude Mask layer (DST_OUT): the picture is removed where the mask is.
                val maskDstOutPaint = android.graphics.Paint().apply {
                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
                }
                var maskScratchBmp: Bitmap? = null
                // Texture the mask layer is uploaded into before it cuts a group picture (see drawGroupLayer).
                var groupMaskTex = 0
                var groupMaskTexUploaded = false
                val canvasRgb = floatArrayOf(bgR, bgG, bgB)
                var lastFrame = -1
                try {
                    // ---- open every picture source ----
                    for (c in visuals.distinctBy { it.id }) {
                        anims[c.id] = NativeLayerAnim.create(c)
                            ?: error("Native animation engine is unavailable for layer '${c.name}'.")
                        val uri = Uri.parse(c.uri)
                        val nativePhoto = if (c.isImage && Build.VERSION.SDK_INT >= 30) loadPhotoNative(renderer, uri) else null
                        if (nativePhoto != null) {
                            images[c.id] = nativePhoto
                        } else if (c.isImage) {
                            val bmp = loadBitmap(uri)?.let { b ->
                                if (b.config == Bitmap.Config.ARGB_8888) b
                                else (b.copy(Bitmap.Config.ARGB_8888, false) ?: b).also { if (it !== b) b.recycle() }
                            } ?: error("Could not read photo '${c.name}'.")
                            val tex = renderer.createTexture2d()
                            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
                            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
                            images[c.id] = ImageLayer(tex, bmp.width, bmp.height)
                            bmp.recycle()
                        } else {
                            videos[c.id] = VideoLayerSource.open(context, uri, renderer, pool)
                                ?: error("Could not open video layer '${c.name}'.")
                        }
                    }

                    // ---- open the shapes' video fills (build 104) ----
                    for (c in all.filter { it.isVideoFilledShape() && it.isVisible }.distinctBy { it.id }) {
                        val src = runCatching {
                            VideoLayerSource.open(context, Uri.parse(c.fillVideoUri!!), renderer, pool)
                        }.getOrNull()
                        if (src != null) fillVideos[c.id] = src
                        else Log.w(TAG, "video fill of '${c.name}' could not be opened; its poster frame is used")
                    }

                    // Raster exports have no encoder and no sound: the renderer is
                    // already set offscreen and each frame is read back in the loop.
                    val hasAudio: Boolean
                    if (encoder != null) {
                        require(encoder.start(renderer, outputFile.absolutePath, w, h, fps, bitrate)) {
                            encoder.errorMessage().ifBlank { "Could not start native H.264 export." }
                        }
                        val ha = addAudio(encoder, tracks, durationMs)
                        if (ha) {
                            require(encoder.startAudio(durationMs)) {
                                encoder.errorMessage().ifBlank { "Could not start native AAC encoder." }
                            }
                        }
                        hasAudio = ha
                    } else {
                        hasAudio = false
                        if (isGif) {
                            gifOut = java.io.BufferedOutputStream(java.io.FileOutputStream(outputFile))
                            gifWriter = GifWriter(gifOut, w, h)
                        }
                    }

                    // ---- frames ----
                    val shown = HashSet<String>()
                    // Shapes whose video fill has shown at least one frame (build 104).
                    val fillShown = HashSet<String>()
                    // A hardware decoder that outputs only black (seen with several video layers on some chips):
                    // the first frames of every layer are checked; a black one is re-opened with a software decoder.
                    val probes = HashMap<String, Int>()
                    val swRetried = HashSet<String>()
                    // Where the time goes (ns): [0] decode + wait for the picture, [1] text/shape/sticker,
                    // [2] draw is queued, encoder submit + video drain, [3] audio. Shown after the export.
                    val prof = LongArray(4)
                    val tStart = System.nanoTime()
                    val audioDec0 = if (encoder != null) encoder.profNs(0) else 0L
                    val audioEnc0 = if (encoder != null) encoder.profNs(1) else 0L
                    // The mask layers cutting a layer of one level (a Group or a picture layer, Alight
                    // style), evaluated at [timeMs] like every layer — empty when no mask sits on it,
                    // or none of its masks is there then (outside a mask layer's own span there is no
                    // cut from it). A stack of masks over the layer comes back as several entries: the
                    // layer is cut by each in turn.
                    fun scopeMasksFor(clip: TimelineClip, timeMs: Long): List<TimelineClip> {
                        val scopeClips = GroupOps.scopeTracks(tracks, clip.groupId).asReversed().flatMap { it.clips }
                        val mcs = LayerMaskLinks.masksByTarget(scopeClips)[clip.id] ?: return emptyList()
                        return mcs.filter { it.isActiveAt(timeMs) }
                            .map { if (rig.hasLinks) rig.adjusted(it, timeMs) else it.renderAt(timeMs) }
                    }
                    // Coverage of one mask layer (already evaluated at [timeMs]) drawn into [out]:
                    // a shape / text mask goes through the same painter as every overlay; a GROUP
                    // mask (build 141) composites its scope — the group's picture is its content
                    // in Alight — in stacking order, each child cut by its own masks inside the
                    // scope first (the preview's maskCoverageInto is the same construction).
                    // Children that paint nothing in a scene (audio / null / camera / chain lanes)
                    // are skipped; a media child (video / image / sticker) has no painter coverage,
                    // so the whole mask fails: false = the target is left uncut by this mask.
                    val groupMaskTemps = ArrayList<Bitmap?>()
                    fun groupMaskTemp(slot: Int): Bitmap {
                        while (groupMaskTemps.size <= slot) groupMaskTemps.add(null)
                        var bmp = groupMaskTemps[slot]
                        if (bmp == null || bmp.width != w || bmp.height != h || bmp.isRecycled) {
                            bmp?.recycle()
                            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            groupMaskTemps[slot] = bmp
                        }
                        return bmp
                    }
                    fun maskCoverageInto(out: Bitmap, maskAdj: TimelineClip, timeMs: Long, depth: Int): Boolean {
                        if (depth > 4) return false
                        if (maskAdj.type != LayerType.GROUP) {
                            val p = painter ?: return false
                            p.nativeTarget = out
                            p.draw(android.graphics.Canvas(out), maskAdj, applyOpacity = true, timeMs = timeMs)
                            return true
                        }
                        val scopeClips = GroupOps.scopeTracks(tracks, maskAdj.id).asReversed().flatMap { it.clips }
                        val scopeMasks = LayerMaskLinks.masksByTarget(scopeClips)
                        val comp = groupMaskTemp(depth * 3 + 1)
                        comp.eraseColor(0)
                        var any = false
                        for (c in scopeClips) {
                            if (!c.isVisible || c.isMask || !c.isActiveAt(timeMs)) continue
                            if (c.type == LayerType.AUDIO || c.type == LayerType.NULL ||
                                c.type == LayerType.CAMERA || c.type == LayerType.CHAIN ||
                                c.type == LayerType.CONTROLLER
                            ) continue
                            val adj = if (rig.hasLinks) rig.adjusted(c, timeMs) else c.renderAt(timeMs)
                            if (adj.type == LayerType.VIDEO || adj.type == LayerType.OVERLAY ||
                                adj.type == LayerType.STICKER
                            ) return false
                            val tmp = groupMaskTemp(depth * 3)
                            tmp.eraseColor(0)
                            if (!maskCoverageInto(tmp, adj, timeMs, depth + 1)) return false
                            for (m in scopeMasks[c.id].orEmpty()) {
                                if (!m.isActiveAt(timeMs)) continue
                                val mAdj = if (rig.hasLinks) rig.adjusted(m, timeMs) else m.renderAt(timeMs)
                                val ms = groupMaskTemp(depth * 3 + 2)
                                ms.eraseColor(0)
                                if (!maskCoverageInto(ms, mAdj, timeMs, depth + 1)) return false
                                android.graphics.Canvas(tmp).drawBitmap(
                                    ms, 0f, 0f, if (m.isExcludeMask) maskDstOutPaint else maskDstInPaint
                                )
                            }
                            android.graphics.Canvas(comp).drawBitmap(tmp, 0f, 0f, null)
                            any = true
                        }
                        if (!any) return false
                        val alpha = maskAdj.opacityFraction
                        if (alpha >= 0.999f) {
                            android.graphics.Canvas(out).drawBitmap(comp, 0f, 0f, null)
                        } else {
                            val paint = android.graphics.Paint().apply {
                                this.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)
                            }
                            android.graphics.Canvas(out).drawBitmap(comp, 0f, 0f, paint)
                        }
                        return true
                    }
                    // Draws one mask layer (already evaluated at [timeMs]) into the shared scratch
                    // picture and returns it — or null when its coverage could not be drawn (a
                    // group mask with a media child, build 141): callers leave the target uncut.
                    fun drawMaskIntoScratch(maskAdj: TimelineClip, timeMs: Long): Bitmap? {
                        var scratch = maskScratchBmp
                        if (scratch == null || scratch.width != w || scratch.height != h || scratch.isRecycled) {
                            scratch?.recycle()
                            scratch = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            maskScratchBmp = scratch
                        }
                        scratch.eraseColor(0)
                        return if (maskCoverageInto(scratch, maskAdj, timeMs, 0)) scratch else null
                    }
                    // Cuts a frame-sized renderer texture (a Group layer's composed picture, or a
                    // picture layer captured into its own picture for a mask) by one mask layer: the
                    // mask is drawn into the scratch picture, uploaded, and the renderer multiplies
                    // the texture by its alpha (DST_IN) — or by one-minus-alpha for an Exclude Mask
                    // layer (DST_OUT). Returns the cut texture — or [tex] itself, uncut, when the
                    // mask's coverage could not be drawn.
                    fun cutPictureByMask(tex: Int, maskAdj: TimelineClip, timeMs: Long, ownerName: String): Int {
                        val scratch = drawMaskIntoScratch(maskAdj, timeMs) ?: return tex
                        if (groupMaskTex == 0) groupMaskTex = renderer.createTexture2d()
                        if (groupMaskTex == 0) error("Could not create the mask texture for '$ownerName'.")
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, groupMaskTex)
                        if (!groupMaskTexUploaded) {
                            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, scratch, 0)
                            groupMaskTexUploaded = true
                        } else {
                            GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, scratch)
                        }
                        val cutTex = renderer.applyTexMask(tex, false, IDENTITY_TEX_MATRIX, w, h, 0, groupMaskTex, maskAdj.isExcludeMask)
                        if (cutTex == 0) error("Native layer mask '${maskAdj.name}' failed on '$ownerName'.")
                        return cutTex
                    }
                    // One picture layer of the frame (video / photo): returns early when the layer is not drawn.
                    fun drawMediaLayer(layerIdx: Int, clip: TimelineClip, timeMs: Long, depth: Int) {
                            if (timeMs < clip.startMs || timeMs >= clip.endMs) return
                            val anim = anims[clip.id] ?: return
                            val fp = anim.eval(timeMs, w, h, previewW)
                            if (rig.hasLinks) rig.applyToFp(clip, timeMs, fp, if (previewW > 0f) w / previewW else 1f)  // Null parent
                            val opacity = fp[5].coerceIn(0f, 1f)
                            if (opacity <= 0f) return
                            val p = layerParamsFrom(fp)
                            // Corner pin (planar track): the picture goes on the tracked surface of another video.
                            val pinSpec = clip.cornerPin
                            if (pinSpec != null) {
                                val baseClip = visuals.firstOrNull { it.id == pinSpec.videoId }
                                val baseAnim = baseClip?.let { anims[it.id] }
                                if (baseClip == null || baseAnim == null || timeMs < baseClip.startMs || timeMs >= baseClip.endMs) return
                                val bp = layerParamsFrom(baseAnim.eval(timeMs, w, h, previewW))
                                p.pin = CornerPinRuntime.quadOnCanvas(pinSpec, timeMs, bp, w, h) ?: return
                            }
                            // A matte has nothing beneath the base layer to cut: it behaves like Normal.
                            // The layer's own blend mode is read directly: a Controller connection
                            // hands nothing down at render time (build 146).
                            val eff = clip
                            val found = BlendModes.find(eff.blendMode)
                            val blendId = if (clip === visuals.first() && found.kind == BlendKind.MATTE) 0 else found.shaderId
                            // A Repeat-family effect on the picture (an imported Alight repeat, or one
                            // added in the Effects panel): the same texture drawn once per copy, each
                            // copy with its own pose (RepeatDraw) — one decoder, many draws, exactly
                            // like the preview. repeatDrawParams reads the placement as it stands
                            // when this runs, so the camera / corner-pin placement below is in it.
                            // [effBlendId] is [blendId], except while the layer is captured into its
                            // own picture for a layer mask (below): inside the capture the draws are
                            // plain, the blend is applied when the cut picture is composited.
                            var effBlendId = blendId
                            fun drawTex(tex: Int, oes: Boolean, mat: FloatArray, cw: Int, ch: Int, rot: Int) {
                                for (pp in repeatDrawParams(p, clip, timeMs, if (previewW > 0f) w / previewW else 1f)) {
                                    if (effBlendId == 0) renderer.drawLayer(tex, oes, mat, cw, ch, rot, pp)
                                    else renderer.drawLayerBlended(tex, oes, mat, cw, ch, rot, pp, effBlendId, canvasRgb)
                                }
                            }
                            // The layer's own draws (photo and video branches), against whatever
                            // target is current. True = something was drawn. Kept as one function so
                            // a masked layer can run it inside its own picture (the renderer's group
                            // pass) and be cut before compositing.
                            fun drawContent(): Boolean {
                            val img = images[clip.id]
                            if (img != null) {
                                if (!placeByCamera(clip, timeMs, anim, fp, p, img.width, img.height)) return false
                                val imgOps = fxOpsOf(clip, timeMs)
                                val imgMasks = ClipMaskLibrary.toNative(clip.masks, timeMs - clip.startMs, CutoutMasks.sourceMs(clip, timeMs))
                                if (imgOps != null || imgMasks != null) {
                                    val fxTex = renderer.applyFx(
                                        img.textureId, false, FLIP_V, img.width, img.height, 0,
                                        0, 0f, 0, false, canvasRgb, imgOps, ClipFxLibrary.grainFrame(timeMs), imgMasks
                                    )
                                    if (fxTex == 0) error("Native effects / masks failed on '${clip.name}'.")
                                    drawTex(fxTex, false, IDENTITY_TEX_MATRIX, img.width, img.height, 0)
                                    return true
                                }
                                drawTex(img.textureId, false, FLIP_V, img.width, img.height, 0)
                                return true
                            }
                            var src = videos[clip.id] ?: return false
                            val sourceMs = anim.sourceMs(timeMs)
                            val us = sourceMs * 1000L
                            val tD0 = System.nanoTime()
                            var st = src.seek(us)
                            var tries = 0
                            while (st == DecoderPool.BUSY && tries++ < 300) { Thread.sleep(10); st = src.seek(us) }
                            if (st == DecoderPool.BUSY) error("Native decoder pool is busy while exporting '${clip.name}'.")
                            if (st == DecoderPool.ERROR) error("Could not decode '${clip.name}' at $sourceMs ms.")
                            prof[0] += System.nanoTime() - tD0
                            if (st == DecoderPool.OK || st == DecoderPool.SAME) shown.add(clip.id)
                            // END_OF_STREAM: the last frame stays on the surface, keep showing it.
                            if (clip.id !in shown) return false
                            if (!placeByCamera(clip, timeMs, anim, fp, p, src.contentWidth, src.contentHeight)) return false
                            val probed = probes[clip.id] ?: 0
                            if (probed < 3 && clip.id !in swRetried) {
                                probes[clip.id] = probed + 1
                                if (renderer.layerLooksBlack(
                                        src.textureId, true, src.texMatrix,
                                        src.contentWidth, src.contentHeight, src.drawRotation, p
                                    )
                                ) {
                                    swRetried.add(clip.id)
                                    val fresh = VideoLayerSource.open(
                                        context, Uri.parse(clip.uri), renderer, pool, preferSoftware = true
                                    )
                                    if (fresh != null) {
                                        var st2 = fresh.seek(us)
                                        var tries2 = 0
                                        while (st2 == DecoderPool.BUSY && tries2++ < 300) { Thread.sleep(10); st2 = fresh.seek(us) }
                                        if (st2 == DecoderPool.OK || st2 == DecoderPool.SAME) {
                                            src.close()
                                            videos[clip.id] = fresh
                                            src = fresh
                                        } else {
                                            fresh.close()
                                        }
                                    }
                                }
                            }
                            // Face blur / cutout: first on the decoded picture (before crop / transform),
                            // same order as the preview (face blur, then cutout).
                            val faceMask = NativeFxMasks.face(renderer, clip, layerIdx, sourceMs, strict = true)
                            val cutMask = NativeFxMasks.cutout(renderer, clip, layerIdx, sourceMs, strict = true)
                            val fxOps = fxOpsOf(clip, timeMs)
                            val maskOps = ClipMaskLibrary.toNative(clip.masks, timeMs - clip.startMs, CutoutMasks.sourceMs(clip, timeMs))
                            if (faceMask != 0 || cutMask != 0 || fxOps != null || maskOps != null) {
                                val fxTex = renderer.applyFx(
                                    src.textureId, true, src.texMatrix,
                                    src.contentWidth, src.contentHeight, src.drawRotation,
                                    faceMask, clip.faceBlurStrength, cutMask,
                                    flatten = clip === visuals.first() && depth == 0, canvasRgb = canvasRgb,
                                    fxOps = fxOps, grainFrame = ClipFxLibrary.grainFrame(timeMs), maskOps = maskOps
                                )
                                if (fxTex == 0) error("Native face blur / cutout / effects / masks failed on '${clip.name}'.")
                                drawTex(fxTex, false, IDENTITY_TEX_MATRIX, src.contentWidth, src.contentHeight, 0)
                                RemoveRuntime.draw(renderer, removePatches, clip, p, timeMs, w, h, effBlendId, canvasRgb)
                                return true
                            }
                            drawTex(src.textureId, true, src.texMatrix, src.contentWidth, src.contentHeight, src.drawRotation)
                            // Remove: the clean patch on the moving surface, right after the layer's own picture.
                            RemoveRuntime.draw(renderer, removePatches, clip, p, timeMs, w, h, effBlendId, canvasRgb)
                            return true
                            }
                            // A mask layer sitting on this picture (Alight style): the layer is
                            // captured into its own frame-sized picture (placement, camera, corner
                            // pin and repeat copies all inside it), cut by each mask in turn — the
                            // same group-pass machinery that cuts a Group layer's composed picture —
                            // and only then composited, with its blend mode, onto the frame. The cut
                            // is in frame space, so it holds for placed / turned / pinned pictures
                            // too. With no group pass available the layer is drawn uncut, like a
                            // group in the same fallback.
                            val masks = scopeMasksFor(clip, timeMs)
                            if (masks.isEmpty()) {
                                drawContent()
                                return
                            }
                            if (!renderer.beginGroup()) {
                                drawContent()
                                return
                            }
                            effBlendId = 0
                            val drew = drawContent()
                            effBlendId = blendId
                            var tex = renderer.endGroup()
                            if (!drew || tex == 0) return
                            for (m in masks) tex = cutPictureByMask(tex, m, timeMs, clip.name)
                            val lp = LayerParams()
                            if (blendId == 0) renderer.drawLayer(tex, false, IDENTITY_TEX_MATRIX, w, h, 0, lp)
                            else renderer.drawLayerBlended(tex, false, IDENTITY_TEX_MATRIX, w, h, 0, lp, blendId, canvasRgb)
                    }
                    // A Group layer is ONE layer: its layers are drawn into their own picture (the renderer's group pass), and that
                    // picture gets the group's opacity, blend mode, effects and masks like a photo would. The group's move / scale /
                    // turn already reached the layers inside through their parent link (ParentRig), so it is placed at 0 / 0 / 1.
                    var drawScopeRef: (String?, Int, Long) -> Unit = { _, _, _ -> }
                    fun drawGroupLayer(clip: TimelineClip, timeMs: Long, depth: Int) {
                        if (timeMs < clip.startMs || timeMs >= clip.endMs) return
                        val g = if (rig.hasLinks) rig.adjusted(clip, timeMs) else clip.renderAt(timeMs)
                        val opacity = g.opacityFraction.coerceIn(0f, 1f)
                        if (opacity <= 0f) return
                        if (!renderer.beginGroup()) {  // too deep / no memory: the layers go straight onto the frame
                            drawScopeRef(clip.id, depth + 1, timeMs)
                            return
                        }
                        drawScopeRef(clip.id, depth + 1, timeMs)
                        val tex = renderer.endGroup()
                        if (tex == 0) return
                        // [g] is the group fully evaluated through the rig: its own blend / effects.
                        val blendId = BlendModes.find(g.blendMode).shaderId
                        val fxOps = fxOpsOf(g, timeMs, clip)
                        // The children's parent link to this group (its ref = the embed's pivot):
                        // the embed's own box in the frame is read off it, for the canvas clip
                        // below and the mask remap.
                        val embedLink = if (clip.embedW > 0f) {
                            all.firstOrNull { it.parent?.id == clip.id }?.parent
                        } else null
                        var drawTex = tex
                        // An imported embed's canvas clip (Queue 12a): Alight rasterises an
                        // embedded scene on its own canvas — content outside that rect never
                        // reaches the parent scene. The composed picture is cut to the embed's
                        // box FIRST, before the group's own effects (parent-scene effects in
                        // Alight, fed by the clipped picture) and before any mask layer's cut —
                        // exactly like the preview. A user-made group (embedW == 0) has no canvas
                        // and is never clipped. (In the beginGroup fallback above there is no
                        // group picture, so — like a mask on such a group — the clip cannot
                        // apply there.)
                        if (embedLink != null) {
                            val clipOps = OverlayMasks.embedCanvasClipOps(
                                g.transformX, g.transformY, embedLink.refX, embedLink.refY,
                                g.scalePercent, g.rotationDeg, g.isFlippedH, g.isFlippedV,
                                clip.embedW, clip.embedH, if (previewW > 0f) w / previewW else 1f, w, h
                            )
                            if (clipOps != null) {
                                val clipped = renderer.applyFx(
                                    drawTex, false, IDENTITY_TEX_MATRIX, w, h, 0,
                                    0, 0f, 0, false, canvasRgb, null, ClipFxLibrary.grainFrame(timeMs), clipOps, null, true
                                )
                                if (clipped == 0) error("Embed canvas clip failed on group '${clip.name}'.")
                                drawTex = clipped
                            }
                        }
                        val rawMaskOps = ClipMaskLibrary.toNative(clip.masks, timeMs - clip.startMs, CutoutMasks.sourceMs(clip, timeMs))
                        // An imported group's own masks (a wipe on an embedScene — Graph round 6 G1)
                        // are fractions of the EMBED's box, not of the frame the composed picture
                        // fills: remap them onto the frame (see OverlayMasks). A user-authored
                        // group's masks were written against the picture itself (embedW == 0).
                        val maskOps = if (rawMaskOps != null && clip.embedW > 0f) {
                            if (embedLink != null) {
                                val box = OverlayMasks.groupBox(
                                    g.transformX, g.transformY, embedLink.refX, embedLink.refY, g.scalePercent, g.rotationDeg,
                                    g.isFlippedH, g.isFlippedV, clip.embedW, clip.embedH,
                                    if (previewW > 0f) w / previewW else 1f, w, h
                                )
                                OverlayMasks.remap(rawMaskOps, box, 0f, 0f, w.toFloat(), h.toFloat())
                            } else rawMaskOps
                        } else rawMaskOps
                        // A mask layer sitting on this group (Alight style) cuts the composed picture,
                        // before the group's own effects — the same order as a masked shape / text
                        // layer's picture. Each mask is drawn with the same painter as every overlay
                        // (into the shared scratch picture), uploaded, and the renderer multiplies the
                        // group picture by its alpha (DST_IN; an Exclude Mask layer by one-minus-alpha,
                        // DST_OUT); a stack of masks cuts in turn. Outside a
                        // mask layer's own span there is no cut from it. (In the beginGroup fallback
                        // above there is no group picture, so a mask on such a group cannot cut.)
                        for (maskAdj in scopeMasksFor(clip, timeMs)) {
                            drawTex = cutPictureByMask(drawTex, maskAdj, timeMs, clip.name)
                        }
                        if (fxOps != null || maskOps != null) {
                            val fxTex = renderer.applyFx(
                                drawTex, false, IDENTITY_TEX_MATRIX, w, h, 0,
                                0, 0f, 0, false, canvasRgb, fxOps, ClipFxLibrary.grainFrame(timeMs), maskOps, null, true
                            )
                            if (fxTex == 0) error("Native effects / masks failed on group '${clip.name}'.")
                            drawTex = fxTex
                        }
                        // A Repeat-family effect on the group itself: the composed picture (mask and
                        // effects already applied above, in the single draw's order) is drawn once per
                        // copy, each copy posed about the group's own anchor (RepeatDraw); without
                        // one, the single draw — exactly like the preview. (In the beginGroup
                        // fallback above there is no group picture to pose, so a repeat on such a
                        // group leaves its layers drawn once — the same limit as a mask there.)
                        val copies = groupRepeatDrawParams(g, timeMs, if (previewW > 0f) w / previewW else 1f, opacity)
                        if (copies == null) {
                            val lp = LayerParams().apply { this.opacity = opacity }
                            if (blendId == 0) renderer.drawLayer(drawTex, false, IDENTITY_TEX_MATRIX, w, h, 0, lp)
                            else renderer.drawLayerBlended(drawTex, false, IDENTITY_TEX_MATRIX, w, h, 0, lp, blendId, canvasRgb)
                        } else {
                            for (lp in copies) {
                                if (blendId == 0) renderer.drawLayer(drawTex, false, IDENTITY_TEX_MATRIX, w, h, 0, lp)
                                else renderer.drawLayerBlended(drawTex, false, IDENTITY_TEX_MATRIX, w, h, 0, lp, blendId, canvasRgb)
                            }
                        }
                    }
                    // A video-filled shape's live composite (build 104): the fill video is placed into
                    // the shape's box (FillVideoPlacement), cut by the shape's silhouette texture, run
                    // through the layer's effects when it has any, and drawn — the shape's own outline
                    // picture goes over it afterwards. False = could not composite (the caller keeps
                    // the poster raster). Renderer results (group pass, mask cut, effects) are drawn
                    // before the next such call, as everywhere in this engine.
                    fun drawFillVideoComposite(
                        src: VideoLayerSource, evalClip: TimelineClip, silTex: Int, silBmp: Bitmap,
                        fx: Boolean, ops: FloatArray?, blendId: Int, timeMs: Long
                    ): Boolean {
                        val pose = fillVideoPose(
                            evalClip, w, h, if (previewW > 0f) w / previewW else 1f,
                            PreviewFrameRef.density, src.contentWidth, src.contentHeight
                        ) ?: return false
                        if (!renderer.beginGroup()) return false
                        val lp = LayerParams().apply {
                            offsetX = pose.offsetX; offsetY = pose.offsetY
                            scaleX = pose.scaleX; scaleY = pose.scaleY
                            rotationDeg = pose.rotationDeg
                        }
                        renderer.drawLayer(src.textureId, true, src.texMatrix, src.contentWidth, src.contentHeight, src.drawRotation, lp)
                        val placed = renderer.endGroup()
                        if (placed == 0) return false
                        val cut = renderer.applyTexMask(placed, false, IDENTITY_TEX_MATRIX, w, h, 0, silTex)
                        if (cut == 0) return false
                        var tex = cut
                        var contW = w
                        var contH = h
                        var placeLp: LayerParams? = null
                        if (fx && ops != null) {
                            val reg = OverlayFx.regionOf(silBmp)
                            val fxTex = renderer.applyFx(
                                tex, false, IDENTITY_TEX_MATRIX, w, h, 0,
                                0, 0f, 0, false, canvasRgb, ops, ClipFxLibrary.grainFrame(timeMs), null,
                                reg?.crop(w, h), true
                            )
                            if (fxTex != 0) {
                                tex = fxTex
                                if (reg != null) {
                                    contW = reg.w
                                    contH = reg.h
                                    placeLp = reg.place(w, h)
                                }
                            }
                        }
                        val outLp = (placeLp ?: LayerParams()).apply { opacity = evalClip.opacityFraction }
                        if (blendId == 0) renderer.drawLayer(tex, false, IDENTITY_TEX_MATRIX, contW, contH, 0, outLp)
                        else renderer.drawLayerBlended(tex, false, IDENTITY_TEX_MATRIX, contW, contH, 0, outLp, blendId, canvasRgb)
                        return true
                    }
                    // One level of the frame: the top level, or the layers inside a Group.
                    fun drawScope(scopeId: String?, depth: Int, timeMs: Long) {
                        val levelGroups = overlayGroups[scopeId ?: ""] ?: emptyList()
                        val items = GroupOps.scopeTracks(tracks, scopeId).asReversed().flatMap { it.clips }
                            .filter { (it.type == LayerType.VIDEO && it.uri != null && it.isVisible) || (it.type == LayerType.GROUP && it.isVisible) }
                        // Under a 3D camera the key is the distance from the camera (CameraRig.stackKey), also for text / shapes.
                        fun zAt(c: TimelineClip): Float =
                            if (timeMs < c.startMs || timeMs >= c.endMs) 0f
                            else if (c.type == LayerType.GROUP && rig.hasLinks) rig.adjusted(c, timeMs).transformZ
                            else {
                                val r0 = if (rig.hasLinks) rig.adjusted(c, timeMs) else c.renderAt(timeMs)
                                (if (c.type == LayerType.VIDEO) cam.stackKey(r0, timeMs) else null) ?: r0.transformZ
                            }
                        fun overlayKey(c: TimelineClip): Float = cam.stackKey(c, timeMs) ?: c.transformZ
                        // Depth: nearer (bigger Z) layers are drawn later = in front. Z can be keyframed, so the
                        // order is decided per frame (stable: equal Z keeps the lane order).
                        val sorted = if (items.size > 1) items.sortedBy { zAt(it) } else items
                        val frameMedia = sorted.map { c -> Triple(visualIndex[c.id] ?: -1, c, zAt(c)) }
                        var mediaPos = 0
                        fun drawMediaUpTo(zLimit: Float, laneLimit: Int) {
                            while (mediaPos < frameMedia.size) {
                                val (li, mc, mz) = frameMedia[mediaPos]
                                val behind = mz < zLimit || (mz == zLimit && (laneIdx[mc.id] ?: 0) < laneLimit)
                                if (!behind) break
                                mediaPos++
                                if (mc.type == LayerType.GROUP) drawGroupLayer(mc, timeMs, depth) else drawMediaLayer(li, mc, timeMs, depth)
                            }
                        }
                        if (painter != null) {
                            for (g in levelGroups) {
                                val active = g.clips.filter { it.isActiveAt(timeMs) }
                                if (active.isEmpty()) continue
                                // Depth: nearer layers on top, layers past the camera are not drawn.
                                val rendered = active.map { if (rig.hasLinks) rig.adjusted(it, timeMs) else it.renderAt(timeMs) }
                                    .filter { !it.isBehindCamera }
                                    .sortedBy { overlayKey(it) }
                                if (rendered.isEmpty()) continue
                                drawMediaUpTo(overlayKey(rendered.first()), laneIdx[rendered.first().id] ?: 0)  // pictures behind this group go first
                                // Repeat copies of a layer inside a group / under a Null: the
                                // ancestors' scale / turn act on the copy offsets (Alight places
                                // the copies in the layer's own scene) — see RepeatEval, Graph G4.
                                val outerById = HashMap<String, Pair<Float, Float>>()
                                if (rig.hasLinks) {
                                    for (orig in g.clips) {
                                        if (RepeatEval.anyRepeatOf(orig) == null) continue
                                        val adj = rendered.firstOrNull { it.id == orig.id } ?: continue
                                        outerById[orig.id] = RepeatEval.outerOf(orig.renderAt(timeMs), adj)
                                    }
                                }
                                fun copiesOfAdj(c: TimelineClip): List<TimelineClip> {
                                    val o = outerById[c.id]
                                    return RepeatEval.copiesOf(c, timeMs = timeMs, outerScale = o?.first ?: 1f, outerRotDeg = o?.second ?: 0f)
                                }
                                // A video-filled shape (build 104): the fill is sought to this frame —
                                // a fill NEVER fails the export; any trouble leaves the poster raster.
                                // The live composite runs for a single repeat copy; several copies
                                // rasterise the poster like any repeated layer.
                                val fillClip = if (g.mediaFill) mediaFillClipOf(g.clips) else null
                                val fillCopies = if (fillClip != null) copiesOfAdj(rendered[0]) else null
                                var fillSrc: VideoLayerSource? = null
                                var useVideo = false
                                if (fillClip != null && fillCopies != null && fillCopies.size == 1) {
                                    val src = fillVideos[fillClip.id]
                                    if (src != null) {
                                        val targetMs = fillSourceMs(fillClip, timeMs, src.clip.durationUs / 1000L)
                                        val st = runCatching {
                                            var s = src.seek(targetMs * 1000L)
                                            var tries = 0
                                            while (s == DecoderPool.BUSY && tries++ < 30) { Thread.sleep(10); s = src.seek(targetMs * 1000L) }
                                            s
                                        }.getOrNull()
                                        if (st == DecoderPool.OK || st == DecoderPool.SAME) fillShown.add(fillClip.id)
                                        if (fillClip.id in fillShown) {
                                            fillSrc = src
                                            useVideo = fillVideoPose(
                                                fillCopies[0], w, h, if (previewW > 0f) w / previewW else 1f,
                                                PreviewFrameRef.density, src.contentWidth, src.contentHeight
                                            ) != null
                                        }
                                    }
                                }
                                // A layer placed by the renderer (pose) is drawn upright: only what changes the picture is in
                                // the key, so a layer that just turns or moves is not redrawn. A layer with the Repeat effect is
                                // drawn once per copy (RepeatEval.copiesOf): in its own upright picture the copies are placed by
                                // undoing the layer's own turn, so the picture's turn puts each copy back on the scene-space grid.
                                val drawn = if (fillClip != null) {
                                    // Live: only the outline is rasterised (the video is composited
                                    // under it). Poster: the whole shape, poster fill included.
                                    if (useVideo) listOf(fillCopies!![0].strokeOnlyCopy()) else fillCopies!!
                                } else if (g.pose) rendered.flatMap { c ->
                                    val upright = if (cam.activeFor(c, timeMs)) uprightForCamera(c) else uprightForPose(c)
                                    RepeatEval.copiesOf(upright, undoRotationDeg = c.rotationDeg, timeMs = timeMs)
                                } else rendered.flatMap { copiesOfAdj(it) }
                                // The mask layers cutting this picture (a masked layer always has a picture of
                                // its own): each is evaluated like every layer and part of the key, so the
                                // cuts follow the masks' own animation. Outside a mask's span there is no
                                // cut from it.
                                val maskAdjs = if (g.mask.isNotEmpty() && !g.pose) {
                                    g.mask.filter { it.isActiveAt(timeMs) }
                                        .map { if (rig.hasLinks) rig.adjusted(it, timeMs) else it.renderAt(timeMs) }
                                } else emptyList()
                                // A sticker's animation frame depends on the time, so it is part of the key.
                                // The fill's raster mode is part of it too: live (outline only) and
                                // poster (whole shape) are different pictures.
                                val signature = drawn.map { r ->
                                    if (r.type == LayerType.STICKER) r to r.sourceOffsetMs(timeMs - r.startMs) else r
                                } + listOf<Any>(maskAdjs, useVideo)
                                if (g.bmp == null) {
                                    g.bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                    g.canvas = android.graphics.Canvas(g.bmp!!)
                                    g.tex = renderer.createTexture2d()
                                }
                                if (signature != g.lastSignature) {
                                    val cv = g.canvas!!
                                    cv.drawPaint(clearPaint) // transparent every redraw
                                    // A blended layer's opacity is applied by the blend shader, not in the picture.
                                    painter.nativeTarget = g.bmp
                                    for (r in drawn) painter.draw(cv, r, applyOpacity = g.blendId == 0, timeMs = timeMs)
                                    // A text layer with its own masks: remember the box it was just
                                    // drawn in (the layout box at scale 1 times the layer's effective
                                    // scale), for the own-mask pass below (see OverlayMasks).
                                    if (g.ownMask && rendered.isNotEmpty() && rendered[0].type == LayerType.TEXT && drawn.isNotEmpty()) {
                                        val s = (rendered[0].effectiveScalePercent / 100f).coerceAtLeast(0.01f)
                                        val (tbw, tbh) = painter.boxOf(drawn[0])
                                        g.textBoxW = tbw * s
                                        g.textBoxH = tbh * s
                                    }
                                    // The layer masks cut the finished picture: each mask layer is drawn
                                    // into the scratch picture and multiplied over this one (alpha only,
                                    // DST_IN), in turn — the picture keeps only where every mask is; an
                                    // Exclude Mask layer erases instead (DST_OUT, see LayerMaskLinks).
                                    for (maskAdj in maskAdjs) {
                                        val scratch = drawMaskIntoScratch(maskAdj, timeMs) ?: continue
                                        painter.nativeTarget = g.bmp
                                        cv.drawBitmap(scratch, 0f, 0f, if (maskAdj.isExcludeMask) maskDstOutPaint else maskDstInPaint)
                                    }
                                    // The video fill's window (build 104): the shape's silhouette,
                                    // rasterised with the same painter (white fill, no stroke, no
                                    // opacity — the composite applies it), cut by the same mask layers
                                    // when there are any, and uploaded as the texture the renderer
                                    // multiplies the placed video by.
                                    if (fillClip != null && useVideo) {
                                        if (g.silBmp == null) {
                                            g.silBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                            g.silTex = renderer.createTexture2d()
                                        }
                                        val sil = g.silBmp!!
                                        sil.eraseColor(0)
                                        painter.nativeTarget = sil
                                        painter.draw(android.graphics.Canvas(sil), fillCopies!![0].silhouetteCopy(), applyOpacity = false, timeMs = timeMs)
                                        for (maskAdj in maskAdjs) {
                                            val scratch = drawMaskIntoScratch(maskAdj, timeMs) ?: continue
                                            android.graphics.Canvas(sil).drawBitmap(scratch, 0f, 0f, if (maskAdj.isExcludeMask) maskDstOutPaint else maskDstInPaint)
                                        }
                                        painter.nativeTarget = g.bmp
                                        if (g.silTex != 0) {
                                            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, g.silTex)
                                            if (!g.silUploaded) {
                                                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, sil, 0)
                                                g.silUploaded = true
                                            } else {
                                                GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, sil)
                                            }
                                        }
                                    }
                                    if (g.pose) painter.boxOf(drawn[0]).let { g.boxW = it.first; g.boxH = it.second }
                                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, g.tex)
                                    if (!g.uploaded) {
                                        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, g.bmp!!, 0)
                                        g.uploaded = true
                                    } else {
                                        GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, g.bmp!!)
                                    }
                                    // Effects run on the layer's own box, not on the whole canvas-sized picture (see OverlayFx).
                                    g.region = if (g.fx && !g.pose) OverlayFx.regionOf(g.bmp!!) else null
                                    g.lastSignature = signature
                                }
                                if (!g.uploaded) continue
                                // Effects of the Effects panel on this layer's own picture; the result is an upright texture.
                                var drawTex = g.tex
                                var drawMat = FLIP_V
                                var contW = w          // size of the picture that is drawn (the layer's box when the effects ran on it)
                                var contH = h
                                var boxLp: LayerParams? = null
                                // Video fill (build 104): the playing video, cut by the shape's
                                // silhouette (and run through the layer's effects when it has any),
                                // goes down first; the group's own picture — the shape's outline, or
                                // the poster when the video could not be shown — is drawn over it by
                                // the branches below, without the effects a second time.
                                val fillDrawn = if (fillClip != null && useVideo && fillSrc != null && g.silTex != 0 && g.silBmp != null) {
                                    val ops = if (g.fx) fxOpsOf(rendered[0], timeMs, g.clips.firstOrNull { it.id == rendered[0].id } ?: rendered[0]) else null
                                    drawFillVideoComposite(fillSrc!!, fillCopies!![0], g.silTex, g.silBmp!!, g.fx, ops, g.blendId, timeMs)
                                } else false
                                if (g.fx && !fillDrawn) {
                                    val ops = fxOpsOf(rendered[0], timeMs, g.clips.firstOrNull { it.id == rendered[0].id } ?: rendered[0])
                                    if (ops != null) {
                                        val reg = g.region
                                        val fxTex = renderer.applyFx(
                                            g.tex, false, FLIP_V, w, h, 0,
                                            0, 0f, 0, false, canvasRgb, ops, ClipFxLibrary.grainFrame(timeMs), null,
                                            reg?.crop(w, h), !g.pose
                                        )
                                        if (fxTex == 0) error("Native effects failed on '${rendered[0].name}'.")
                                        drawTex = fxTex
                                        drawMat = IDENTITY_TEX_MATRIX
                                        if (reg != null) {
                                            contW = reg.w
                                            contH = reg.h
                                            boxLp = reg.place(w, h)
                                        }
                                    }
                                }
                                // The layer's OWN masks (Masks panel; an imported wipe2 lands here —
                                // Graph round 6 G1): photos run theirs inside the fx pass on their own
                                // texture, but an overlay's picture is frame-sized, so the masks are
                                // remapped from the layer's box into the picture's space (OverlayMasks)
                                // and run as their own pass, on the effects' result when effects ran.
                                // Pose pictures are cut too (build 166, mirroring the preview): the
                                // mask is applied to the upright picture with the UPRIGHT clip's box,
                                // and the projection carries the cut along. The live video fill is
                                // already on the frame and is not cut (no imported wipe combines with
                                // a video fill).
                                if (g.ownMask && !fillDrawn) {
                                    val orig = g.clips.singleOrNull()
                                    val eval = rendered.firstOrNull()
                                    if (orig != null && eval != null) {
                                        val raw = ClipMaskLibrary.toNative(orig.masks, timeMs - orig.startMs, CutoutMasks.sourceMs(orig, timeMs))
                                        if (raw != null) {
                                            val stickerBm = if (eval.type == LayerType.STICKER) StickerLibrary.frameFor(eval.uri, eval.sourceOffsetMs(timeMs - eval.startMs)) else null
                                            val boxClip = if (g.pose) {
                                                if (cam.activeFor(eval, timeMs)) uprightForCamera(eval) else uprightForPose(eval)
                                            } else eval
                                            val box = OverlayMasks.boxOf(
                                                boxClip,
                                                textDrawn = if (g.textBoxW > 0f) g.textBoxW to g.textBoxH else null,
                                                stickerSize = stickerBm?.let { it.width to it.height },
                                                w, h, if (previewW > 0f) w / previewW else 1f, PreviewFrameRef.density
                                            )
                                            if (box != null) {
                                                val reg = if (drawTex != g.tex) g.region else null
                                                val tx = reg?.x?.toFloat() ?: 0f
                                                val ty = reg?.y?.toFloat() ?: 0f
                                                val tw = reg?.w?.toFloat() ?: w.toFloat()
                                                val th = reg?.h?.toFloat() ?: h.toFloat()
                                                val ops = OverlayMasks.remap(raw, box, tx, ty, tw, th)
                                                val maskTex = renderer.applyFx(drawTex, false, drawMat, contW, contH, 0, 0, 0f, 0, false, canvasRgb, null, 0, ops, null, false)
                                                if (maskTex == 0) error("Own masks failed on '${eval.name}'.")
                                                drawTex = maskTex
                                                drawMat = IDENTITY_TEX_MATRIX
                                            }
                                        }
                                    }
                                }
                                // 3D camera: the upright picture goes on the corners the camera sees it at.
                                val seen = if (g.pose) cam.seeOverlay(rendered[0], timeMs, g.boxW, g.boxH, camK, w, h) else null
                                if (seen != null && seen.quad == null) continue  // behind the camera
                                if (g.pose) {
                                    // The upright picture, placed by the renderer like a video layer (pivot, rotation, 3D turn).
                                    val lp = if (seen != null) LayerParams().apply { pin = seen.quad }
                                    else overlayPoseParams(rendered[0], g.boxW, g.boxH, if (previewW > 0f) w / previewW else 1f)
                                    if (g.blendId == 0) {
                                        renderer.drawLayer(drawTex, false, drawMat, w, h, 0, lp)  // opacity is in the picture
                                    } else {
                                        lp.opacity = rendered[0].opacityFraction
                                        renderer.drawLayerBlended(drawTex, false, drawMat, w, h, 0, lp, g.blendId, canvasRgb)
                                    }
                                } else if (g.blendId == 0) {
                                    renderer.drawLayer(drawTex, false, drawMat, contW, contH, 0, boxLp ?: LayerParams())
                                } else if (drawTex == g.tex) {
                                    renderer.blendTexture(g.tex, g.blendId, rendered[0].opacityFraction, canvasRgb)
                                } else {
                                    val lp = (boxLp ?: LayerParams()).apply { opacity = rendered[0].opacityFraction }
                                    renderer.drawLayerBlended(drawTex, false, drawMat, contW, contH, 0, lp, g.blendId, canvasRgb)
                                }
                            }
                        }
                        drawMediaUpTo(Float.POSITIVE_INFINITY, Int.MAX_VALUE)  // the pictures in front of every shape / text / sticker
                    }
                    drawScopeRef = ::drawScope
                    for (frame in 0 until totalFrames) {
                        if (cancelled.get()) throw CancellationException()
                        lastFrame = frame
                        val timeMs = frame * 1000L / fps
                        renderer.beginFrame(bgR, bgG, bgB, bgA)
                        val tO0 = System.nanoTime()
                        drawScope(null, 0, timeMs)
                        prof[1] += System.nanoTime() - tO0
                        val ptsNs = frame.toLong() * 1_000_000_000L / fps
                        val tS0 = System.nanoTime()
                        if (encoder != null) {
                            require(encoder.submitFrame(ptsNs)) {
                                encoder.errorMessage().ifBlank { "Native encoder failed on frame $frame." }
                            }
                            prof[2] += System.nanoTime() - tS0
                            if (hasAudio) {
                                // Keep the sound a little ahead of the picture so the muxer can interleave.
                                val tA0 = System.nanoTime()
                                require(encoder.pumpAudio(ptsNs / 1000L + 250_000L)) {
                                    encoder.errorMessage().ifBlank { "Native audio export failed." }
                                }
                                prof[3] += System.nanoTime() - tA0
                            }
                        } else {
                            // Raster frame: the same composed picture, read back instead
                            // of encoded — quantized into the GIF, or written as a PNG.
                            val buf = rgbaBuf!!
                            require(renderer.readPixels(0, 0, w, h, buf)) {
                                "Could not read rendered frame $frame."
                            }
                            renderer.endFrame()
                            if (isGif) {
                                val q = GifQuantizer.quantize(buf, w, h, bottomUp = true)
                                gifWriter!!.addFrame(q.indices, q.palette, GifExport.delayCs(fps))
                            } else {
                                writePngFrame(outputFile, frame, GifExport.rgbaBottomUpToArgb(buf, w, h), w, h)
                            }
                            prof[2] += System.nanoTime() - tS0
                        }
                        if (frame % max(1, fps / 2) == 0 || frame == totalFrames - 1) {
                            onProgress(((frame + 1).toFloat() / totalFrames).coerceAtMost(0.99f))
                        }
                    }
                    val tF0 = System.nanoTime()
                    if (encoder != null) {
                        require(encoder.finish()) {
                            encoder.errorMessage().ifBlank { "Could not finalize native MP4." }
                        }
                    } else if (isGif) {
                        gifWriter!!.finish()
                        gifOut!!.close()
                    }
                    val tFin = System.nanoTime() - tF0
                    run {
                        fun sec(ns: Long) = "%.1f".format(ns / 1e9)
                        val ps = runCatching { pool.stats() }.getOrNull()
                        val full = "Native export ${sec(System.nanoTime() - tStart)}s, $totalFrames frames ($w x $h @ $fps): " +
                            "decode ${sec(prof[0])}s, overlays ${sec(prof[1])}s, encode ${sec(prof[2])}s, " +
                            "audio ${sec(prof[3])}s, finish ${sec(tFin)}s" +
                            (if (ps != null) ", decoder evict=${ps.evictions} fallback=${ps.fallbacks} err=${ps.errors}" else "")
                        Log.i(TAG, full)
                        // A toast shows only ~2 lines: two short ones, one after the other.
                        val line1 = "Total ${sec(System.nanoTime() - tStart)}s | decode ${sec(prof[0])} | overlay ${sec(prof[1])}"
                        val line2 = "encode ${sec(prof[2])} | audio ${sec(prof[3])} | finish ${sec(tFin)}" +
                            (if (ps != null) " | ev ${ps.evictions} fb ${ps.fallbacks} err ${ps.errors}" else "")
                        val aDec = if (encoder != null) encoder.profNs(0) - audioDec0 else 0L
                        val aEnc = if (encoder != null) encoder.profNs(1) - audioEnc0 else 0L
                        val line3 = "audio: decode ${sec(aDec)} | encode ${sec(aEnc)} | rest ${sec((prof[3] - aDec - aEnc).coerceAtLeast(0L))}"
                        Log.i(TAG, line3)
                        if (encoder != null) {
                            main.post {
                                runCatching {
                                    android.widget.Toast.makeText(context, line1, android.widget.Toast.LENGTH_LONG).show()
                                    android.widget.Toast.makeText(context, line2, android.widget.Toast.LENGTH_LONG).show()
                                    android.widget.Toast.makeText(context, line3, android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                    onProgress(1f)
                } catch (t: Throwable) {
                    encoder?.cancel()
                    if (outputFile.isDirectory) outputFile.deleteRecursively() else outputFile.delete()
                    if (t is CancellationException) throw t
                    // Details for the error message (shown in the export error).
                    val st = runCatching { pool.stats() }.getOrNull()
                    val info = buildString {
                        append(" [frame ").append(lastFrame).append('/').append(totalFrames)
                        append(", ").append(t.javaClass.simpleName)
                        if (st != null) {
                            append(", decoders hw=").append(st.activeHw).append('/').append(st.maxHw)
                            append(" sw=").append(st.activeSw).append('/').append(st.maxSw)
                            append(" evict=").append(st.evictions).append(" fallback=").append(st.fallbacks)
                            append(" err=").append(st.errors)
                        }
                        append(']')
                    }
                    throw IllegalStateException((t.message ?: t.javaClass.simpleName) + info, t)
                } finally {
                    videos.values.forEach { runCatching { it.close() } }
                    fillVideos.values.forEach { runCatching { it.close() } }
                    anims.values.forEach { runCatching { it.close() } }
                    images.values.forEach { runCatching { renderer.deleteTexture(it.textureId) } }
                    runCatching { removePatches.release(renderer) }
                    overlayGroups.values.forEach { level ->
                        level.forEach { g ->
                            if (g.tex != 0) runCatching { renderer.deleteTexture(g.tex) }
                            if (g.silTex != 0) runCatching { renderer.deleteTexture(g.silTex) }
                            g.bmp?.recycle()
                            g.silBmp?.recycle()
                        }
                    }
                    maskScratchBmp?.recycle()
                    runCatching { gifOut?.close() }
                    encoder?.close()
                    pool.close()
                    renderer.close()
                }
            }
        }
    }

    /** One frame of a PNG-sequence export: frame_0001.png, frame_0002.png, ... inside [dir]. */
    private fun writePngFrame(dir: File, index: Int, argb: IntArray, w: Int, h: Int) {
        val bmp = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        try {
            val name = "frame_%04d.png".format(index + 1)
            File(dir, name).outputStream().use { o ->
                check(bmp.compress(Bitmap.CompressFormat.PNG, 100, o)) {
                    "Could not write PNG frame ${index + 1}."
                }
            }
        } finally {
            bmp.recycle()
        }
    }

    /** Video sound (unless muted) + audio layers (volume, fades); the choice is made in C++ ([ExportPlanNative.pickAudio]). Returns true if any clip was added. */
    private fun addAudio(encoder: NativeExportEncoder, tracks: List<TimelineTrack>, videoEndMs: Long): Boolean {
        var added = false
        for (a in ExportPlanNative.pickAudio(tracks, videoEndMs)) {
            val c = a.clip
            val uri = Uri.parse(c.uri ?: continue)
            val pfd = try { context.contentResolver.openFileDescriptor(uri, "r") } catch (_: Exception) { null } ?: continue
            pfd.use {
                if (encoder.addAudioClip(it.fd, it.statSize, c.startMs, c.durationMs, c.trimStartMs,
                        c.exportSpeed, a.volume, a.fadeInMs, a.fadeOutMs)) added = true
            }
        }
        return added
    }

    /** LayerParams from the C++ result of [NativeLayerAnim.eval] (opacity is applied by the caller's check). */
    private fun layerParamsFrom(fp: FloatArray): LayerParams = LayerParams().apply {
        offsetX = fp[0]
        offsetY = fp[1]
        scaleX = fp[2]
        scaleY = fp[3]
        rotationDeg = fp[4]
        opacity = fp[5].coerceIn(0f, 1f)
        cropX = fp[6]
        cropY = fp[7]
        cropW = fp[8]
        cropH = fp[9]
        // 3D turn around the pivot with perspective (the renderer uses these instead of the offset when it is turned)
        rotX = fp[10]
        rotY = fp[11]
        pivotX = fp[12]
        pivotY = fp[13]
        baseX = fp[14]
        baseY = fp[15]
        perspD = fp[16]
    }

    /** Photo decoded in C++ (AImageDecoder, Android 11+) straight into a texture; null = use [loadBitmap]. */
    private fun loadPhotoNative(renderer: NativeRenderer, uri: Uri, maxSide: Int = 2560): ImageLayer? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            renderer.loadPhoto(pfd.fd, maxSide)?.let { ImageLayer(it.textureId, it.width, it.height) }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "native photo decode failed, using the Kotlin decoder", t)
        null
    }

    /** Photo -> upright ARGB bitmap, longest side at most 2560 px (EXIF rotation applied). */
    private fun loadBitmap(uri: Uri, maxSide: Int = 2560): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { dec, info, _ ->
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val side = max(info.size.width, info.size.height)
                if (side > maxSide) {
                    val f = maxSide.toFloat() / side
                    dec.setTargetSize((info.size.width * f).roundToInt().coerceAtLeast(1),
                        (info.size.height * f).roundToInt().coerceAtLeast(1))
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            val orientation = context.contentResolver.openInputStream(uri)?.use {
                android.media.ExifInterface(it).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
            } ?: android.media.ExifInterface.ORIENTATION_NORMAL
            val m = Matrix()
            when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            }
            if (raw != null && !m.isIdentity) {
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
            } else raw
        }
    } catch (t: Throwable) {
        Log.w(TAG, "photo decode failed", t)
        null
    }

    /** Texture matrix for a normal 2D texture whose first row is the TOP of the picture. */
    private val FLIP_V = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, -1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 1f, 0f, 1f
    )
}
