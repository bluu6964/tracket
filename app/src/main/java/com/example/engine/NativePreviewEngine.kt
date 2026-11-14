package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.net.Uri
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import com.example.model.ClipFx
import com.example.model.ClipFxLibrary
import com.example.model.GroupOps
import com.example.model.OnionSkin
import com.example.model.OnionSkinSettings
import com.example.model.ClipMaskLibrary
import com.example.model.BlendKind
import com.example.model.BlendModes
import com.example.model.effectiveScalePercent
import com.example.model.isBehindCamera
import com.example.model.opacityFraction
import com.example.model.sourceOffsetMs
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.TimelineTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Editor preview of the video / photo layers drawn by the C++ GL renderer (the same one the export uses).
 * One GL thread owns the renderer and the decoder pool; the UI only hands over the state (tracks, playhead
 * provider) and asks for frames. The editor playhead is the clock: every frame shows the picture under it.
 *
 *  - paused / scrubbing: exact seek to the playhead (the pool decodes forward without re-seeking when it can)
 *  - playing: next() frames in order, a re-seek only when far off
 * Sound is not played here: [NativeAudioPreview] (C++ mixer + AAudio) plays it; there are no Media3 players in the preview any more.
 * The canvas and a full-screen editor (Crop, Edit shape) can each have a surface: see [surfaceChanged] and [setOverride].
 *
 * Onion skin (build 190): while paused / scrubbing with the toggle on, [drawFrame] first draws ghost
 * pictures of the scene at the neighbouring frame times of [OnionSkin.plan] — each rendered by the
 * very same scene pass into a group picture, colorized to its side's tint (the shipped COLORIZE_AM
 * op: previous = warm red, next = green) and composited at its falloff alpha UNDER the real frame,
 * which is then drawn over them at full opacity as always. Preview only: the exporter owns a
 * separate renderer and never sees this state, and while playing (or in a full-screen editor
 * override) no ghost pass runs at all.
 */
class NativePreviewEngine(context: Context) {

    private class Source(val uri: String, val video: VideoLayerSource?, val image: Image?) {
        var shown = false
        var probes = 0
        var swRetried = false
        fun close(r: NativeRenderer) {
            video?.close()
            image?.let { r.deleteTexture(it.tex) }
        }
    }

    private class Image(val tex: Int, val w: Int, val h: Int)
    private class AnimEntry(val clip: TimelineClip, val anim: NativeLayerAnim)

    private val appCtx = context.applicationContext
    private val gl = GlThread("am-preview-gl")

    // Input from the UI thread.
    @Volatile private var tracks: List<TimelineTrack> = emptyList()
    /** A full-screen editor (Crop, Edit shape) shows its own layers through this engine: they replace [tracks] while set. */
    @Volatile private var editorTracks: List<TimelineTrack>? = null
    private val liveTracks: List<TimelineTrack> get() = editorTracks ?: tracks
    @Volatile private var playheadOf: () -> Long = { 0L }
    @Volatile private var canvasArgb: Int = 0xFF000000.toInt()
    /** Onion-skin state (build 190), handed over with [update]: settings, the project frame rate the ghost steps use, and the project duration they clamp to. */
    @Volatile private var onionSkin: OnionSkinSettings = OnionSkinSettings()
    @Volatile private var onionFps: Int = 30
    @Volatile private var onionDurationMs: Long = 0L
    @Volatile private var playing = false
    @Volatile private var released = false
    private val framePending = AtomicBoolean(false)

    // GL thread only.
    private var renderer: NativeRenderer? = null
    private var pool: DecoderPool? = null
    private var outputOn = false
    private val surfaces = LinkedHashMap<Any, Surface>()  // GL thread: every live surface, the one drawn on last
    private var outW = 0
    private var outH = 0
    private var loopScheduled = false
    private var lastStatsMs = 0L
    private var choreographer: Choreographer? = null
    private val sources = HashMap<String, Source>()
    /** Decoders of shapes' video fills (build 104), keyed by the shape's id; pruned like [sources]. */
    private val fillSources = HashMap<String, Source>()
    private val anims = HashMap<String, AnimEntry>()
    /** Clean patches of the Remove tool (GL textures, loaded on first use). */
    private val removePatches = RemovePatchCache()

    // Text / shape / sticker layers: drawn by the C++ engines into frame-sized bitmaps, shown as textures on top of the pictures.
    // Layers that follow each other share one picture; a layer with a blend mode gets its own (same grouping as the export).
    private class OverlayGroup(val ids: List<String>, val blendId: Int, val pose: Boolean = false, val fx: Boolean = false, val masked: Boolean = false, val mediaFill: Boolean = false, val ownMask: Boolean = false) {
        // fx = a layer with effects (Effects panel): always its own picture, the C++ effects run on it before it is drawn.
        // masked = the layer is cut by a mask layer (Alight style): always its own picture, cut before upload.
        // ownMask = the layer carries its OWN masks (Masks panel; an imported wipe2 lands there): always its own
        // picture, cut by the remapped mask pass after the effects (see OverlayMasks). textBoxW / textBoxH = the
        // drawn box of a text layer (frame px), captured when its picture is redrawn, for that remap.
        // mediaFill = one shape filled with a playing video (build 104): its own picture holds only the
        // shape's outline; the video is composited under it, cut by the silhouette in silBmp / silTex.
        // pose = a layer turned in 3D / with its own pivot (OverlayPose.kt): drawn upright into its own picture, the
        // renderer places it. boxW / boxH = the layer's box at scale 1 (its pivot is a fraction of it).
        var boxW = 0f
        var boxH = 0f
        var bmp: Bitmap? = null
        var tex = 0
        var uploaded = false
        var sig: List<Any?>? = null
        var silBmp: Bitmap? = null
        var silTex = 0
        var silUploaded = false
        // fx layers that are not placed by the renderer: the box of the layer inside the picture (the effects run on it).
        var region: FxRegion? = null
        var textBoxW = 0f
        var textBoxH = 0f
    }
    // Overlay pictures (text / shape / sticker) of each level: "" = the top level, else the id of a Group layer (every group
    // is drawn as its own picture, see drawGroupLayer, and keeps its own overlay pictures).
    private val overlayCaches = HashMap<String, List<OverlayGroup>>()
    private val textPainter by lazy {
        NativeTextPainter(
            { name -> com.example.ui.text.FontRepository.fileOrNull(appCtx, name)?.absolutePath },
            { uri -> FillImages.peek(uri) }  // never blocks: starts the load and returns null until it is ready
        ).also { painter ->
            // Text-on-path (build 191): the resolver reads the scene of the frame being
            // drawn (set in drawTextLayers, which owns the rig + camera of that frame).
            painter.pathPoints = { tc, t ->
                val rig = pathSceneRig
                if (rig == null) null
                else TextPathGeometry.framePoints(
                    pathSceneClips, rig, tc, t, outW, outH,
                    PreviewFrameRef.widthPx.let { if (it > 0f) outW / it else 1f },
                    appCtx.resources.displayMetrics.density
                ) { c -> pathSceneCam?.affects(c) == true }
            }
        }
    }
    /** Text-on-path scene of the frame being drawn (build 191): clips + rig + camera, set per drawTextLayers call. */
    private var pathSceneClips: List<TimelineClip> = emptyList()
    private var pathSceneRig: ParentRig? = null
    private var pathSceneCam: CameraRig? = null
    private val shapePainter by lazy { NativeShapePainter { uri -> FillImages.peek(uri) } }  // never blocks, like the text painter
    private var fillRetryPosted = false
    private val frameCallback = Choreographer.FrameCallback {
        loopScheduled = false
        if (!released && outputOn && playing) {
            drawFrame()
            scheduleLoop()
        }
    }

    /** Hand over the state. Cheap, call it whenever the composition has new tracks / colour. */
    fun update(
        tracks: List<TimelineTrack>, playheadOf: () -> Long, canvasArgb: Int,
        onionSkin: OnionSkinSettings = OnionSkinSettings(), onionFps: Int = 30, onionDurationMs: Long = 0L
    ) {
        this.tracks = tracks
        this.playheadOf = playheadOf
        this.canvasArgb = canvasArgb
        this.onionSkin = onionSkin
        this.onionFps = onionFps
        this.onionDurationMs = onionDurationMs
    }

    /** Draw these tracks instead of the project's (a full-screen editor), or the project again with null. */
    fun setOverride(t: List<TimelineTrack>?) {
        if (editorTracks === t) return
        editorTracks = t
    }

    fun setPlaying(on: Boolean) {
        if (playing == on) return
        playing = on
        if (!released) gl.post { if (playing) scheduleLoop() else requestFrame() }
    }

    /** Draw the frame under the playhead soon (several requests in a row become one frame). */
    fun requestFrame() {
        if (released) return
        if (framePending.compareAndSet(false, true)) {
            gl.post {
                framePending.set(false)
                drawFrame()
            }
        }
    }

    /**
     * A surface to draw on. The canvas and a full-screen editor (Crop, Edit shape) can each have one at the same time:
     * the one that appeared last is drawn on, and when it goes away the one before it takes over again.
     */
    fun surfaceChanged(surface: Surface, owner: Any = MAIN_OWNER) {
        if (released) return
        gl.post {
            val r = ensureRenderer() ?: return@post
            surfaces.remove(owner)
            surfaces[owner] = surface  // last = most recent = the one drawn on
            if (!useSurface(r, surface)) {
                Log.w(TAG, "native preview: could not use the surface")
                surfaces.remove(owner)
                outputOn = false
                return@post
            }
            drawFrame()
            if (playing) scheduleLoop()
        }
    }

    /** The canvas' SurfaceView (set by the editor's canvas surface, not by full-screen editors): [captureFrame] copies from it. */
    @Volatile
    var captureView: java.lang.ref.WeakReference<android.view.SurfaceView>? = null

    /**
     * Copies the picture that is on the canvas now (exactly what the preview shows: pictures, text, shapes, stickers,
     * canvas colour, no selection frame) into a bitmap of the canvas frame's size. Call on the main thread;
     * [onDone] runs on the main thread with null when the picture could not be read.
     */
    fun captureFrame(onDone: (Bitmap?) -> Unit) {
        val v = captureView?.get()
        if (released || v == null || v.width <= 0 || v.height <= 0 || !v.holder.surface.isValid) {
            onDone(null)
            return
        }
        val bmp = try {
            Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            Log.w(TAG, "capture: no memory for the bitmap", t)
            onDone(null)
            return
        }
        try {
            android.view.PixelCopy.request(
                v, bmp,
                { result ->
                    if (result == android.view.PixelCopy.SUCCESS) onDone(bmp)
                    else { bmp.recycle(); onDone(null) }
                },
                android.os.Handler(android.os.Looper.getMainLooper())
            )
        } catch (t: Throwable) {
            Log.w(TAG, "capture failed", t)
            bmp.recycle()
            onDone(null)
        }
    }

    /** Blocks until the GL thread let go of the surface (it must not be used after this returns). */
    fun surfaceDestroyed(owner: Any = MAIN_OWNER) {
        if (released) return
        gl.call {
            val wasActive = surfaces.keys.lastOrNull() === owner
            surfaces.remove(owner)
            if (!wasActive) return@call Unit
            val r = renderer
            val next = surfaces.values.lastOrNull()
            if (r != null && next != null && useSurface(r, next)) {
                drawFrame()
                if (playing) scheduleLoop()
            } else {
                outputOn = false
                r?.setOutput(null)
                closeAllSources()
            }
            Unit
        }
    }

    /** GL thread: make [surface] the output and remember its size. */
    private fun useSurface(r: NativeRenderer, surface: Surface): Boolean {
        if (!r.setOutput(surface)) {
            outputOn = false
            return false
        }
        val (w, h) = r.outputSize()
        outW = w
        outH = h
        outputOn = true
        return true
    }

    fun release() {
        if (released) return
        released = true
        try {
            gl.call {
                outputOn = false
                closeAllSources()
                renderer?.setOutput(null)
                renderer?.close()
                renderer = null
                pool?.close()
                pool = null
                Unit
            }
        } catch (t: Throwable) {
            Log.w(TAG, "release", t)
        }
        gl.close()
    }

    // ------------------------------------------------------------------------------------------------

    private fun ensureRenderer(): NativeRenderer? {
        renderer?.let { return it }
        val r = NativeRenderer.create() ?: return null
        renderer = r
        pool = DecoderPool(MAX_HW_DECODERS, 2)
        return r
    }

    private fun scheduleLoop() {
        if (loopScheduled || !playing || !outputOn || released) return
        loopScheduled = true
        val c = choreographer ?: Choreographer.getInstance().also { choreographer = it }
        c.postFrameCallback(frameCallback)
    }

    private fun closeAllSources() {
        val r = renderer
        if (r != null) sources.values.forEach { it.close(r) }
        sources.clear()
        if (r != null) fillSources.values.forEach { it.close(r) }
        fillSources.clear()
        anims.values.forEach { it.anim.close() }
        anims.clear()
        removePatches.release(r)
        closeGroups(r)
    }

    private fun closeGroups(r: NativeRenderer?) {
        for (list in overlayCaches.values) closeGroupList(r, list)
        overlayCaches.clear()
    }

    private fun closeGroupList(r: NativeRenderer?, list: List<OverlayGroup>) {
        for (g in list) {
            if (g.tex != 0) r?.deleteTexture(g.tex)
            if (g.silTex != 0) r?.deleteTexture(g.silTex)
            g.bmp?.recycle()
            g.silBmp?.recycle()
        }
    }

    /** Frees the overlay pictures of groups that are gone. */
    private fun pruneOverlayCaches(r: NativeRenderer, groupIds: Set<String>) {
        if (overlayCaches.isEmpty()) return
        val dead = overlayCaches.keys.filter { it.isNotEmpty() && it !in groupIds }
        for (k in dead) overlayCaches.remove(k)?.let { closeGroupList(r, it) }
    }

    /**
     * 3D camera: puts a picture layer on the corners its camera sees it at (the corner pin path). False = the layer is
     * behind the camera and is not drawn. Nothing changes for a layer without a camera, or one that is corner pinned.
     */
    private fun placeByCamera(
        cam: CameraRig, c: TimelineClip, t: Long, anim: NativeLayerAnim, fp: FloatArray, p: LayerParams,
        contentW: Int, contentH: Int, previewW: Float
    ): Boolean {
        if (!cam.hasCamera || p.pin != null) return true
        val k = if (previewW > 0f) outW / previewW else 1f
        val seen = cam.seeMedia(c, t, fp, anim.clipAt(t, t)[2], contentW, contentH, k, outW, outH) ?: return true
        p.pin = seen.quad ?: return false
        return true
    }

    /** Parents (Null objects) of the layers, as they are now. Cheap: nothing is done when no layer has a parent. */
    private fun parentRig(): ParentRig =
        ParentRig(liveTracks.flatMap { it.clips }) { c, t -> NativeRenderAt.renderAt(c, t) }

    /** Position of every layer in the stacking order: bottom lane = 0, later = in front. */
    private fun laneIndex(): Map<String, Int> {
        val m = HashMap<String, Int>()
        for ((i, c) in liveTracks.asReversed().flatMap { it.clips }.withIndex()) m[c.id] = i
        return m
    }

    private fun animFor(c: TimelineClip): NativeLayerAnim? {
        val e = anims[c.id]
        if (e != null && e.clip === c) return e.anim
        e?.anim?.close()
        val a = NativeLayerAnim.create(c)
        if (a == null) anims.remove(c.id) else anims[c.id] = AnimEntry(c, a)
        return a
    }

    private fun sourceFor(r: NativeRenderer, c: TimelineClip): Source? {
        val uriStr = c.uri ?: return null
        val old = sources[c.id]
        if (old != null && old.uri == uriStr) return old
        old?.close(r)
        sources.remove(c.id)
        val uri = Uri.parse(uriStr)
        val s = if (c.isImage) {
            val loaded: Image? = try {
                appCtx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    r.loadPhoto(pfd.fd, MAX_PHOTO_SIDE)?.let { Image(it.textureId, it.width, it.height) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "photo decode failed", t)
                null
            }
            if (loaded == null) return null
            Source(uriStr, null, loaded)
        } else {
            val v = VideoLayerSource.open(appCtx, uri, r, pool ?: return null) ?: return null
            Source(uriStr, v, null)
        }
        sources[c.id] = s
        return s
    }

    /** The decoder of a shape's video fill (build 104), opened lazily like a picture layer's source. */
    private fun fillSourceFor(r: NativeRenderer, c: TimelineClip): Source? {
        val uriStr = c.fillVideoUri ?: return null
        val old = fillSources[c.id]
        if (old != null && old.uri == uriStr) return old
        old?.close(r)
        fillSources.remove(c.id)
        val v = VideoLayerSource.open(appCtx, Uri.parse(uriStr), r, pool ?: return null) ?: return null
        val s = Source(uriStr, v, null)
        fillSources[c.id] = s
        return s
    }

    /**
     * Puts the fill frame that belongs to project time [t] on the fill's texture: the shape's own
     * window in the file (fillSourceMs), sought / stepped exactly like a picture layer in [advance],
     * minus the black-frame probe (a fill that misbehaves falls back to its poster instead).
     */
    private fun advanceFill(s: Source, shape: TimelineClip, t: Long) {
        val v = s.video ?: return
        val targetUs = fillSourceMs(shape, t, v.clip.durationUs / 1000L) * 1000L
        val frameUs = if (v.clip.fps > 1f) (1_000_000f / v.clip.fps).toLong() else 33_333L
        var st: Int
        if (!s.shown || !playing) {
            st = seekTo(v, targetUs, patient = !s.shown)
        } else {
            val diff = targetUs - v.clip.ptsUs
            if (diff < -2 * frameUs || diff > MAX_CATCHUP_US) {
                st = seekTo(v, targetUs, patient = false)
            } else {
                st = DecoderPool.SAME
                var n = 0
                while (targetUs - v.clip.ptsUs >= frameUs && n < 3) {
                    st = v.next()
                    n++
                    if (st != DecoderPool.OK) break
                }
            }
        }
        if (st == DecoderPool.OK || st == DecoderPool.SAME) s.shown = true
        // END_OF_STREAM / BUSY: the last picture stays on the texture.
    }

    /**
     * Effects as the renderer runs them at [timeMs]: Motion Blur is resolved from the layer's own
     * motion over one 33 ms frame (the 30 fps default: the preview has no project fps), a
     * keyframed Solid Color takes its colour from the layer's colour track, and the Alight
     * Mirror / Glow Scan take their keyframed alpha / threshold from their values tails
     * ([AmFxEval]). [sample] is
     * the clip the motion is sampled from — the original, not the rendered copy, so a rig adjustment
     * can never hide the keyframes.
     */
    private fun fxOpsOf(c: TimelineClip, timeMs: Long, sample: TimelineClip = c): FloatArray? =
        ClipFxLibrary.toNative(
            SolidColorEval.resolve(
                MotionBlurEval.resolve(AmFxEval.resolve(c.effects, c, timeMs), { ms ->
                    NativeRenderAt.renderAt(sample, ms).let { MotionBlurEval.Pose(it.transformX, it.transformY, it.rotationDeg, it.scalePercent) }
                }, timeMs, 33L, MotionBlurEval.canvasHeightPx(PreviewFrameRef.widthPx, outW, outH),
                    sample.pivotX * 100f, sample.pivotY * 100f),
                c, timeMs
            )
        )

    private fun drawFrame() {
        val r = renderer ?: return
        if (!outputOn || released) return
        val t = playheadOf()
        // Every picture layer of the project, groups included (decoders, slots of the masks, corner pins look at all of them).
        val media = liveTracks.asReversed().flatMap { it.clips }
            .filter { it.type == LayerType.VIDEO && it.isVisible && it.uri != null }

        // Layers near the playhead keep a decoder (opened early so the first frame is ready), the rest are freed.
        val wanted = HashSet<String>()
        for (c in media) {
            if (t >= c.startMs - PRELOAD_MS && t < c.endMs + KEEP_MS) {
                wanted += c.id
                sourceFor(r, c)
            }
        }
        removePatches.retain(r, media.filter { it.planarRemove != null }.map { it.id }.toSet())
        val gone = sources.keys.filter { it !in wanted }
        for (id in gone) {
            sources.remove(id)?.close(r)
            anims.remove(id)?.anim?.close()
        }
        // Video fills of shapes (build 104): their decoders live and die by the same window.
        val fillWanted = HashSet<String>()
        for (c in liveTracks.flatMap { it.clips }) {
            if (c.isVideoFilledShape() && c.isVisible && t >= c.startMs - PRELOAD_MS && t < c.endMs + KEEP_MS) {
                fillWanted += c.id
                fillSourceFor(r, c)
            }
        }
        val fillGone = fillSources.keys.filter { it !in fillWanted }
        for (id in fillGone) fillSources.remove(id)?.close(r)

        val bg = canvasArgb
        val canvasRgb = floatArrayOf(
            ((bg shr 16) and 0xFF) / 255f, ((bg shr 8) and 0xFF) / 255f, (bg and 0xFF) / 255f
        )
        r.beginFrame(canvasRgb[0], canvasRgb[1], canvasRgb[2], 1f)
        // The time the scene passes below draw at: the playhead [t] for the real frame, a
        // neighbouring frame time for an onion-skin ghost pass (build 190). The decoder-pool
        // management above always works on the real playhead; a ghost pass seeks the decoders
        // to its own time and the real pass after it seeks them back.
        var sceneT = t
        val previewW = PreviewFrameRef.widthPx
        val rig = parentRig()
        val cam = CameraRig(liveTracks, rig)
        val firstMedia = media.firstOrNull()
        val groupIds = GroupOps.groupIdsOf(liveTracks)
        // Depth of a layer or group: bigger Z = nearer = drawn later.
        // Under a 3D camera it is the distance from the camera instead (CameraRig.stackKey), also for shapes / text / stickers.
        fun zOf(c: TimelineClip): Float =
            if (c.type == LayerType.GROUP) (if (rig.hasLinks) rig.adjusted(c, sceneT) else NativeRenderAt.renderAt(c, sceneT)).transformZ
            else (if (cam.hasCamera) cam.stackKey(if (rig.hasLinks) rig.adjusted(c, sceneT) else NativeRenderAt.renderAt(c, sceneT), sceneT) else null)
                ?: animFor(c)?.let { a -> a.clipAt(sceneT, sceneT)[2] } ?: 0f
        // drawScope (below) draws one level; a group needs it for its own level, so it is reached through this variable.
        var drawScopeRef: (String?, Int) -> Unit = { _, _ -> }
        // The mask layers cutting a layer of one level (a Group or a picture layer, Alight style),
        // evaluated at [sceneT] like every layer — empty when no mask sits on it, or none of its masks is
        // there at [sceneT] (outside a mask layer's own span there is no cut from it). A stack of masks
        // over the layer comes back as several entries: the layer is cut by each in turn.
        fun scopeMasksFor(c: TimelineClip, sceneT: Long): List<TimelineClip> {
            val scopeClips = GroupOps.scopeTracks(liveTracks, c.groupId).asReversed().flatMap { it.clips }
            val mcs = LayerMaskLinks.masksByTarget(scopeClips)[c.id] ?: return emptyList()
            return mcs.filter { it.isActiveAt(sceneT) }
                .map { if (rig.hasLinks) rig.adjusted(it, sceneT) else NativeRenderAt.renderAt(it, sceneT) }
        }
        // One picture layer (video / photo); returns early when the layer is not drawn.
        fun drawMediaLayer(c: TimelineClip, depth: Int) {
            val anim = animFor(c) ?: return
            val fp = anim.eval(sceneT, outW, outH, previewW)
            if (rig.hasLinks) rig.applyToFp(c, sceneT, fp, if (previewW > 0f) outW / previewW else 1f)  // Null parent
            if (fp[5] <= 0f) return
            val p = LayerParams().apply {
                offsetX = fp[0]; offsetY = fp[1]
                scaleX = fp[2]; scaleY = fp[3]
                rotationDeg = fp[4]
                opacity = fp[5].coerceIn(0f, 1f)
                cropX = fp[6]; cropY = fp[7]; cropW = fp[8]; cropH = fp[9]
                rotX = fp[10]; rotY = fp[11]; pivotX = fp[12]; pivotY = fp[13]
                baseX = fp[14]; baseY = fp[15]; perspD = fp[16]
            }
            // Corner pin (planar track): the picture goes on the tracked surface of another video, with perspective.
            val pinSpec = c.cornerPin
            if (pinSpec != null) {
                val baseClip = media.firstOrNull { it.id == pinSpec.videoId }
                val baseAnim = baseClip?.let { animFor(it) }
                if (baseClip == null || baseAnim == null || !baseClip.isActiveAt(sceneT)) return
                val bfp = baseAnim.eval(sceneT, outW, outH, previewW)
                val bp = LayerParams().apply {
                    offsetX = bfp[0]; offsetY = bfp[1]
                    scaleX = bfp[2]; scaleY = bfp[3]
                    rotationDeg = bfp[4]
                    cropX = bfp[6]; cropY = bfp[7]; cropW = bfp[8]; cropH = bfp[9]
                    rotX = bfp[10]; rotY = bfp[11]; pivotX = bfp[12]; pivotY = bfp[13]
                    baseX = bfp[14]; baseY = bfp[15]; perspD = bfp[16]
                }
                p.pin = CornerPinRuntime.quadOnCanvas(pinSpec, sceneT, bp, outW, outH) ?: return
            }
            // Blend mode (same rule as the export): a matte on the lowest layer has nothing to cut, it behaves like Normal.
            // The layer's own values are read directly: a Controller connection hands nothing down
            // at render time (build 146 — its fan-out already rewrote the layer's stored values).
            val eff = c
            val found = BlendModes.find(eff.blendMode)
            val blendId = if (c === firstMedia && found.kind == BlendKind.MATTE) 0 else found.shaderId
            // A Repeat-family effect on the picture (an imported Alight repeat, or one added in the
            // Effects panel): the same texture drawn once per copy, each copy with its own pose
            // (RepeatDraw) — one decoder, many draws. repeatDrawParams reads the placement as it
            // stands when this runs, so the camera / corner-pin placement below is already in it.
            // A Repeat-family effect on the picture (an imported Alight repeat, or one added in the
            // Effects panel): the same texture drawn once per copy, each copy with its own pose
            // (RepeatDraw) — one decoder, many draws. repeatDrawParams reads the placement as it
            // stands when this runs, so the camera / corner-pin placement below is already in it.
            // [effBlendId] is [blendId], except while the layer is captured into its own picture for
            // a layer mask (below): inside the capture the draws are plain, the blend is applied
            // when the cut picture is composited onto the frame.
            var effBlendId = blendId
            fun drawTex(tex: Int, oes: Boolean, mat: FloatArray, cw: Int, ch: Int, rot: Int) {
                for (pp in repeatDrawParams(p, c, sceneT, if (previewW > 0f) outW / previewW else 1f)) {
                    if (effBlendId == 0) r.drawLayer(tex, oes, mat, cw, ch, rot, pp)
                    else r.drawLayerBlended(tex, oes, mat, cw, ch, rot, pp, effBlendId, canvasRgb)
                }
            }
            // The layer's own draws (photo and video branches), against whatever target is current.
            // True = something was drawn. Kept as one function so a masked layer can run it inside
            // its own picture (the renderer's group pass) and be cut before compositing.
            fun drawContent(): Boolean {
                val src = sources[c.id] ?: return false
                val img = src.image
                if (img != null) {
                    if (!placeByCamera(cam, c, sceneT, anim, fp, p, img.w, img.h, previewW)) return false
                    // Effects of the Effects panel on a photo (before crop / transform, like on a video).
                    val imgOps = fxOpsOf(c, sceneT)
                    val imgMasks = ClipMaskLibrary.toNative(c.masks, sceneT - c.startMs, CutoutMasks.sourceMs(c, sceneT))
                    if (imgOps != null || imgMasks != null) {
                        val fxTex = r.applyFx(
                            img.tex, false, FLIP_V, img.w, img.h, 0,
                            0, 0f, 0, false, canvasRgb, imgOps, ClipFxLibrary.grainFrame(sceneT), imgMasks
                        )
                        if (fxTex != 0) {
                            drawTex(fxTex, false, IDENTITY_TEX_MATRIX, img.w, img.h, 0)
                            return true
                        }
                        Log.w(TAG, "native effects / masks failed on photo '${c.name}', drawn without them")
                    }
                    drawTex(img.tex, false, FLIP_V, img.w, img.h, 0)
                    return true
                }
                val v = src.video ?: return false
                advance(r, c, src, v, anim, sceneT, p)
                val now = sources[c.id] ?: return false  // advance() may have swapped in a software decoder
                val cur = now.video ?: return false
                if (!now.shown) return false
                if (!placeByCamera(cam, c, sceneT, anim, fp, p, cur.contentWidth, cur.contentHeight, previewW)) return false
                // Face blur / cutout on the decoded picture first (before crop / transform), like the export.
                val slot = media.indexOfFirst { it.id == c.id }
                val srcMs = anim.sourceMs(sceneT)
                val faceMask = NativeFxMasks.face(r, c, slot, srcMs, strict = false)
                val cutMask = NativeFxMasks.cutout(r, c, slot, srcMs, strict = false)
                val fxOps = fxOpsOf(c, sceneT)
                val maskOps = ClipMaskLibrary.toNative(c.masks, sceneT - c.startMs, CutoutMasks.sourceMs(c, sceneT))
                if (faceMask != 0 || cutMask != 0 || fxOps != null || maskOps != null) {
                    val fxTex = r.applyFx(
                        cur.textureId, true, cur.texMatrix, cur.contentWidth, cur.contentHeight, cur.drawRotation,
                        // flatten (the lowest layer's cutout drawn straight onto the canvas
                        // colour) belongs to the real frame only: in a ghost picture the
                        // flattened rect would turn opaque and tint as a solid block.
                        faceMask, c.faceBlurStrength, cutMask, flatten = sceneT == t && c === firstMedia && depth == 0, canvasRgb = canvasRgb,
                        fxOps = fxOps, grainFrame = ClipFxLibrary.grainFrame(sceneT), maskOps = maskOps
                    )
                    if (fxTex != 0) {
                        drawTex(fxTex, false, IDENTITY_TEX_MATRIX, cur.contentWidth, cur.contentHeight, 0)
                        RemoveRuntime.draw(r, removePatches, c, p, sceneT, outW, outH, effBlendId, canvasRgb)
                        return true
                    }
                    Log.w(TAG, "native face blur / cutout / effects / masks failed on '${c.name}', drawn without the effect")
                }
                drawTex(cur.textureId, true, cur.texMatrix, cur.contentWidth, cur.contentHeight, cur.drawRotation)
                // Remove: the clean patch on the moving surface, right after the layer's own picture.
                RemoveRuntime.draw(r, removePatches, c, p, sceneT, outW, outH, effBlendId, canvasRgb)
                return true
            }
            // A mask layer sitting on this picture (Alight style): the layer is captured into its own
            // frame-sized picture (placement, camera, corner pin and repeat copies all inside it),
            // cut by each mask in turn — the same group-pass machinery that cuts a Group layer's
            // composed picture — and only then composited, with its blend mode, onto the frame. The
            // cut is in frame space, so it holds for placed / turned / pinned pictures too. With no
            // group pass available the layer is drawn uncut, like a group in the same fallback.
            val masks = scopeMasksFor(c, sceneT)
            if (masks.isEmpty()) {
                drawContent()
                return
            }
            if (!r.beginGroup()) {
                Log.w(TAG, "layer mask on '${c.name}' could not be applied (no group pass), drawn uncut")
                drawContent()
                return
            }
            effBlendId = 0
            val drew = drawContent()
            effBlendId = blendId
            var tex = r.endGroup()
            if (!drew || tex == 0) return
            val kk = if (previewW > 0f) outW / previewW else 1f
            val dens = appCtx.resources.displayMetrics.density
            for (m in masks) {
                val cut = cutGroupPicture(r, tex, m, outW, outH, kk, dens, sceneT)
                if (cut != 0) tex = cut
                else Log.w(TAG, "layer mask '${m.name}' was not applied to '${c.name}', drawn uncut by it")
            }
            val lp = LayerParams()
            if (blendId == 0) r.drawLayer(tex, false, IDENTITY_TEX_MATRIX, outW, outH, 0, lp)
            else r.drawLayerBlended(tex, false, IDENTITY_TEX_MATRIX, outW, outH, 0, lp, blendId, canvasRgb)
        }
        // A Group layer is ONE layer: the layers inside are drawn into their own picture (the renderer's group pass), and that
        // picture gets the group's opacity, blend mode, effects and masks like a photo would. The group's move / scale / turn
        // already reached the layers inside through their parent link (ParentRig), so the picture is placed at 0 / 0 / 1.
        fun drawGroupLayer(c: TimelineClip, depth: Int) {
            val g = if (rig.hasLinks) rig.adjusted(c, sceneT) else NativeRenderAt.renderAt(c, sceneT)
            val opacity = g.opacityFraction.coerceIn(0f, 1f)
            if (opacity <= 0f) return
            if (!r.beginGroup()) {  // too deep / no memory: the layers go straight onto the frame (no group blend / effects)
                drawScopeRef(c.id, depth + 1)
                return
            }
            drawScopeRef(c.id, depth + 1)
            val tex = r.endGroup()
            if (tex == 0) return
            val blendId = BlendModes.find(g.blendMode).shaderId
            val fxOps = fxOpsOf(g, sceneT, c)
            // The children's parent link to this group (its ref = the embed's pivot): the embed's
            // own box in the frame is read off it, for the canvas clip below and the mask remap.
            val embedLink = if (c.embedW > 0f) {
                liveTracks.flatMap { it.clips }.firstOrNull { it.parent?.id == c.id }?.parent
            } else null
            var drawTex = tex
            // An imported embed's canvas clip (Queue 12a): Alight rasterises an embedded scene on
            // its own canvas — content outside that rect never reaches the parent scene. The
            // composed picture is cut to the embed's box FIRST, before the group's own effects
            // (parent-scene effects in Alight, fed by the clipped picture) and before any mask
            // layer's cut. A user-made group (embedW == 0) has no canvas and is never clipped.
            // In the beginGroup fallback above there is no group picture, so — like a mask on
            // such a group — the clip cannot apply there.
            if (embedLink != null) {
                val clipOps = OverlayMasks.embedCanvasClipOps(
                    g.transformX, g.transformY, embedLink.refX, embedLink.refY,
                    g.scalePercent, g.rotationDeg, g.isFlippedH, g.isFlippedV,
                    c.embedW, c.embedH, if (previewW > 0f) outW / previewW else 1f, outW, outH
                )
                if (clipOps != null) {
                    val clipped = r.applyFx(
                        drawTex, false, IDENTITY_TEX_MATRIX, outW, outH, 0,
                        0, 0f, 0, false, canvasRgb, null, ClipFxLibrary.grainFrame(sceneT), clipOps, null, true
                    )
                    if (clipped != 0) drawTex = clipped
                    else Log.w(TAG, "embed canvas clip failed on group '${c.name}', drawn unclipped")
                }
            }
            val rawMaskOps = ClipMaskLibrary.toNative(c.masks, sceneT - c.startMs, CutoutMasks.sourceMs(c, sceneT))
            // An imported group's own masks (a wipe on an embedScene — Graph round 6 G1) are
            // fractions of the EMBED's box, not of the frame the composed picture fills: remap
            // them onto the frame (see OverlayMasks). A user-authored group's masks were written
            // against the picture itself (embedW == 0) and keep their meaning.
            val maskOps = if (rawMaskOps != null && c.embedW > 0f) {
                if (embedLink != null) {
                    val box = OverlayMasks.groupBox(
                        g.transformX, g.transformY, embedLink.refX, embedLink.refY, g.scalePercent, g.rotationDeg,
                        g.isFlippedH, g.isFlippedV, c.embedW, c.embedH,
                        if (previewW > 0f) outW / previewW else 1f, outW, outH
                    )
                    OverlayMasks.remap(rawMaskOps, box, 0f, 0f, outW.toFloat(), outH.toFloat())
                } else rawMaskOps
            } else rawMaskOps
            // A mask layer sitting on this group (Alight style) cuts the composed picture, before the
            // group's own effects — the same order as a masked shape / text layer's picture. Each mask
            // is evaluated like every layer, so the cut follows its own animation; outside a mask
            // layer's own span there is no cut from it. (In the beginGroup fallback above there is no group
            // picture, so a mask on such a group cannot cut — the layers are already on the frame.)
            val maskAdjs = scopeMasksFor(c, sceneT)
            if (maskAdjs.isNotEmpty()) {
                val pw = PreviewFrameRef.widthPx
                val kk = if (pw > 0f) outW / pw else 1f
                val dens = appCtx.resources.displayMetrics.density
                for (maskAdj in maskAdjs) {
                    val cutTex = cutGroupPicture(r, drawTex, maskAdj, outW, outH, kk, dens, sceneT)
                    if (cutTex != 0) drawTex = cutTex
                    else Log.w(TAG, "layer mask '${maskAdj.name}' was not applied to group '${c.name}', drawn uncut by it")
                }
            }
            if (fxOps != null || maskOps != null) {
                val fxTex = r.applyFx(
                    drawTex, false, IDENTITY_TEX_MATRIX, outW, outH, 0,
                    0, 0f, 0, false, canvasRgb, fxOps, ClipFxLibrary.grainFrame(sceneT), maskOps, null, true
                )
                if (fxTex != 0) drawTex = fxTex
                else Log.w(TAG, "native effects / masks failed on group '${c.name}', drawn without them")
            }
            // A Repeat-family effect on the group itself: the composed picture (mask and effects
            // already applied above, in the single draw's order) is drawn once per copy, each copy
            // posed about the group's own anchor (RepeatDraw); without one, the single draw. (In
            // the beginGroup fallback above there is no group picture to pose, so a repeat on such
            // a group leaves its layers drawn once — the same limit as a mask on such a group.)
            val copies = groupRepeatDrawParams(g, sceneT, if (previewW > 0f) outW / previewW else 1f, opacity)
            if (copies == null) {
                val lp = LayerParams().apply { this.opacity = opacity }
                if (blendId == 0) r.drawLayer(drawTex, false, IDENTITY_TEX_MATRIX, outW, outH, 0, lp)
                else r.drawLayerBlended(drawTex, false, IDENTITY_TEX_MATRIX, outW, outH, 0, lp, blendId, canvasRgb)
            } else {
                for (lp in copies) {
                    if (blendId == 0) r.drawLayer(drawTex, false, IDENTITY_TEX_MATRIX, outW, outH, 0, lp)
                    else r.drawLayerBlended(drawTex, false, IDENTITY_TEX_MATRIX, outW, outH, 0, lp, blendId, canvasRgb)
                }
            }
        }
        // One level: the top level, or the layers inside a group. One stacking order for pictures, groups and shapes / text /
        // stickers: bigger Z in front; equal Z = lane order (a layer added later, in a higher lane, is in front). A picture
        // or group is drawn before an overlay group when it is behind it.
        val lane = laneIndex()
        fun drawScope(scopeId: String?, depth: Int) {
            val clips = GroupOps.scopeTracks(liveTracks, scopeId).asReversed().flatMap { it.clips }
            val active = clips.filter {
                it.isVisible && !it.isMask && it.isActiveAt(sceneT) &&
                    ((it.type == LayerType.VIDEO && it.uri != null) || it.type == LayerType.GROUP)
            }
            // Depth: nearer (bigger Z) layers later; equal Z keeps the lane order.
            val ordered = if (active.size > 1) active.sortedBy { zOf(it) } else active
            val orderedZ = ordered.map { c -> c to zOf(c) }
            var mediaPos = 0
            val drawMediaUpTo: (Float, Int) -> Unit = { zLimit, laneLimit ->
                while (mediaPos < orderedZ.size) {
                    val (mc, mz) = orderedZ[mediaPos]
                    val behind = mz < zLimit || (mz == zLimit && (lane[mc.id] ?: 0) < laneLimit)
                    if (!behind) break
                    mediaPos++
                    if (mc.type == LayerType.GROUP) drawGroupLayer(mc, depth) else drawMediaLayer(mc, depth)
                }
            }
            drawTextLayers(r, sceneT, canvasRgb, scopeId, drawMediaUpTo)
            drawMediaUpTo(Float.POSITIVE_INFINITY, Int.MAX_VALUE)  // pictures in front of every shape / text / sticker
        }
        drawScopeRef = ::drawScope
        // ONION SKIN (build 190): the ghost passes, drawn UNDER the real frame. The plan
        // is pure (model/OnionSkin.plan); each ghost is the SAME scene pass at the ghost's
        // own time — so keyframed animation is evaluated exactly as the renderer always
        // evaluates it — captured into a group picture (transparent where the scene draws
        // nothing), colorized to its side's tint with the shipped Colorize op and drawn
        // at its falloff alpha. The real pass below then covers the ghosts at full
        // opacity wherever the real scene is opaque: a ghost shows exactly where the
        // scene has moved away from, or has not yet arrived at. The tints are a
        // content-visualisation aid for the scene's own frames (like the markers' gold),
        // not chrome. When the plan is empty — toggle off, playing, a full-screen editor
        // override — not one extra call runs: zero behaviour or cost change. Cost when
        // on: one extra scene render per ghost (decoder seeks included), capped at
        // 3 + 3 by the plan; the pictures are re-rendered per drawFrame, like the frame
        // itself, so scrubbing and edits always show the current scene.
        val onionPlan = if (!playing && editorTracks == null && onionSkin.enabled)
            OnionSkin.plan(t, onionFps, onionDurationMs, onionSkin.countPerSide, onionSkin.opacity)
        else emptyList()
        fun onionTintOps(hueDeg: Float): FloatArray? = ClipFxLibrary.toNative(
            listOf(ClipFx(type = ClipFxLibrary.COLORIZE_AM, values = listOf(hueDeg, 1f)))
        )
        for (ghost in onionPlan) {
            sceneT = ghost.timeMs
            if (!r.beginGroup()) {
                Log.w(TAG, "onion skin: no group pass for the ${ghost.timeMs} ms ghost, skipped")
                continue
            }
            drawScope(null, 0)
            val ghostTex = r.endGroup()
            if (ghostTex == 0) continue
            val tintOps = onionTintOps(ghost.hueDeg)
            val tinted = if (tintOps != null) r.applyFx(
                ghostTex, false, IDENTITY_TEX_MATRIX, outW, outH, 0,
                0, 0f, 0, false, canvasRgb, tintOps, 0, null
            ) else 0
            // A failed tint keeps the ghost (untinted, same alpha) instead of dropping it.
            val lp = LayerParams().apply { opacity = ghost.alpha }
            r.drawLayer(if (tinted != 0) tinted else ghostTex, false, IDENTITY_TEX_MATRIX, outW, outH, 0, lp)
        }
        sceneT = t
        drawScope(null, 0)
        pruneOverlayCaches(r, groupIds)
        r.endFrame()
        logPoolStats()
    }

    /**
     * Text, shape and sticker layers (C++ engines, the same code as the export), placed among the pictures like every layer: bigger Z in front, equal Z by lane order (later = in front). Layers that follow
     * each other share one frame-sized picture, a layer with a blend mode gets its own (blended by the renderer). Layers
     * the painters cannot draw stay in Compose (they are not [nativeTextEligible] / [nativeShapeEligible]).
     */
    private fun drawTextLayers(r: NativeRenderer, t: Long, canvasRgb: FloatArray, scopeId: String?, drawMediaUpTo: (Float, Int) -> Unit) {
        val w = outW
        val h = outH
        if (w <= 0 || h <= 0 || !(TextNative.isAvailable || ShapeNative.isAvailable)) return
        // Lane order: the bottom lane first (inside a picture: nearer = bigger Z later, like the Compose preview).
        // Only the layers of this level (the top level, or one group): a group is drawn as its own picture.
        val scopeClips = GroupOps.scopeTracks(liveTracks, scopeId).asReversed().flatMap { it.clips }
        val cacheKey = scopeId ?: ""
        var groups: List<OverlayGroup> = overlayCaches[cacheKey] ?: emptyList()
        val overlays = scopeClips
            .filter { it.isVisible && !it.isMask && (nativeTextEligible(it) || nativeShapeEligible(it)) }
        if (overlays.isEmpty()) {
            if (groups.isNotEmpty()) {
                closeGroupList(r, groups)
                overlayCaches.remove(cacheKey)
            }
            return
        }
        // Mask layers of this level (never drawn themselves): target id -> the mask that cuts it.
        val maskByTarget = LayerMaskLinks.masksByTarget(scopeClips)
        // A layer with effects always gets its own picture, so the effects touch only that layer (text / shape / sticker).
        val rig = parentRig()
        val cam = CameraRig(liveTracks, rig)  // 3D camera: layers under a camera lane are placed by it (own upright picture)
        // Text-on-path (build 191): the text painter's resolver reads this frame's scene.
        pathSceneClips = liveTracks.flatMap { it.clips }
        pathSceneRig = rig
        pathSceneCam = cam
        // Stacking key of a text / shape / sticker layer: its distance from the camera when one looks at it, else its own Z.
        fun overlayKey(c: TimelineClip): Float = cam.stackKey(c, t) ?: c.transformZ
        class Slot(val clips: MutableList<TimelineClip>, val blendId: Int, val pose: Boolean, val fx: Boolean, val masked: Boolean = false, val mediaFill: Boolean = false, val ownMask: Boolean = false)
        val layout = ArrayList<Slot>()
        val lane = laneIndex()
        // Lane positions of the picture layers: overlays on both sides of one cannot share a picture (it would be between them).
        val mediaLanes = scopeClips
            .filter { (it.type == LayerType.VIDEO && it.isVisible && it.uri != null) || (it.type == LayerType.GROUP && it.isVisible) }
            .map { lane[it.id] ?: 0 }
        fun mediaBetween(a: TimelineClip, b: TimelineClip): Boolean {
            val lo = lane[a.id] ?: 0
            val hi = lane[b.id] ?: 0
            return mediaLanes.any { it in (lo + 1) until hi }
        }
        for (c in overlays) {
            // The grouping reads the layer's OWN blend / effects: a Controller connection hands
            // nothing down at render time (build 146), so the stored values are the whole truth.
            val effC = c
            val spec = BlendModes.find(effC.blendMode)
            val hasFx = ClipFxLibrary.hasEffects(effC.effects)
            val hasOwnMasks = OverlayMasks.hasOwnMasks(c)
            if (mediaFillClipOf(listOf(c)) != null && !overlayNeedsPose(c) && !cam.affects(c)) {
                // A shape filled with a playing video (build 104): always a picture of its own (the
                // outline the video is composited under). Pose / camera layers keep the poster path.
                layout.add(Slot(mutableListOf(c), spec.shaderId, false, hasFx, masked = maskByTarget.containsKey(c.id), mediaFill = true, ownMask = hasOwnMasks))
            } else if (overlayNeedsPose(c) || cam.affects(c)) {
                layout.add(Slot(mutableListOf(c), spec.shaderId, true, hasFx, ownMask = hasOwnMasks))  // 3D / own pivot: always its own picture
            } else if (maskByTarget.containsKey(c.id)) {
                // Cut by a mask layer: the layer needs a picture of its own, or the cut would also take
                // the layers it shares the picture with.
                layout.add(Slot(mutableListOf(c), spec.shaderId, false, hasFx, masked = true, ownMask = hasOwnMasks))
            } else if (hasOwnMasks) {
                // Cut by its own masks (an imported wipe2): also a picture of its own.
                layout.add(Slot(mutableListOf(c), spec.shaderId, false, hasFx, ownMask = true))
            } else if (hasFx) {
                layout.add(Slot(mutableListOf(c), spec.shaderId, false, true))
            } else if (spec.isNormal) {
                val last = layout.lastOrNull()
                if (last != null && last.blendId == 0 && !last.pose && !last.fx && !last.masked && !last.ownMask && !mediaBetween(last.clips.last(), c)) last.clips.add(c)
                else layout.add(Slot(mutableListOf(c), 0, false, false))
            } else {
                layout.add(Slot(mutableListOf(c), spec.shaderId, false, false))
            }
        }
        val same = groups.size == layout.size && layout.indices.all { i ->
            groups[i].blendId == layout[i].blendId && groups[i].pose == layout[i].pose && groups[i].fx == layout[i].fx &&
                groups[i].masked == layout[i].masked && groups[i].mediaFill == layout[i].mediaFill && groups[i].ownMask == layout[i].ownMask &&
                groups[i].ids == layout[i].clips.map { it.id }
        }
        if (!same) {
            closeGroupList(r, groups)
            groups = layout.map { OverlayGroup(it.clips.map { c -> c.id }, it.blendId, it.pose, it.fx, it.masked, it.mediaFill, it.ownMask) }
            overlayCaches[cacheKey] = groups
        }
        val pw = PreviewFrameRef.widthPx
        val k = if (pw > 0f) w / pw else 1f
        // PreviewFrameRef.density is only set when an export starts (1.0 before), so the preview reads the real
        // screen density itself: shapes / stickers are 130 dp boxes.
        val dens = appCtx.resources.displayMetrics.density
        var picturesMissing = false
        for ((i, g) in groups.withIndex()) {
            val texts = layout[i].clips.filter { it.isActiveAt(t) }
                .map { if (rig.hasLinks) rig.adjusted(it, t) else NativeRenderAt.renderAt(it, t) }
                .filter { !it.isBehindCamera }
                .sortedBy { overlayKey(it) }
            if (texts.isEmpty()) continue
            drawMediaUpTo(overlayKey(texts.first()), lane[texts.first().id] ?: 0)  // pictures behind this group go first
            var bmp = g.bmp
            if (bmp == null || bmp.width != w || bmp.height != h) {
                if (g.tex != 0) r.deleteTexture(g.tex)
                bmp?.recycle()
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                g.bmp = bmp
                g.tex = r.createTexture2d()
                g.uploaded = false
                g.sig = null
            }
            val fonts = texts.map {
                if (it.type == LayerType.SHAPE || it.type == LayerType.STICKER) null
                else com.example.ui.text.FontRepository.fileOrNull(appCtx, it.fontFamily)?.absolutePath
            }
            // A picture fill that is still loading draws nothing yet: look again shortly, and redraw when it arrived.
            // A video fill's poster counts too (it is what the shape shows until its decoder shows a frame).
            val pics = texts.filter { it.fillMode == com.example.model.FILL_IMAGE || it.fillMode == com.example.model.FILL_VIDEO }
                .map { c -> (if (c.fillMode == com.example.model.FILL_VIDEO) c.fillVideoUri else c.fillImageUri)?.let { u -> FillImages.peek(u) } }
            if (pics.any { it == null }) picturesMissing = true
            // A sticker's animation frame depends on the time, so it is part of the key (same as the export).
            val stickerFrames = texts.map { c ->
                if (c.type == LayerType.STICKER) c.sourceOffsetMs(t - c.startMs) else -1L
            }
            // Repeat copies of a layer inside a group / under a Null: Alight places the copies in
            // the layer's own scene, so the ancestors' scale / turn act on the copy offsets too.
            // texts[] are fully composed poses; outerOf recovers the ancestor similarity from the
            // layer's own evaluation (RepeatEval's class doc, Graph round 6 G4).
            val outerById = HashMap<String, Pair<Float, Float>>()
            if (rig.hasLinks) {
                for (orig in layout[i].clips) {
                    if (RepeatEval.anyRepeatOf(orig) == null) continue
                    val adj = texts.firstOrNull { it.id == orig.id } ?: continue
                    outerById[orig.id] = RepeatEval.outerOf(NativeRenderAt.renderAt(orig, t), adj)
                }
            }
            fun copiesOfAdj(c: TimelineClip): List<TimelineClip> {
                val o = outerById[c.id]
                return RepeatEval.copiesOf(c, timeMs = t, outerScale = o?.first ?: 1f, outerRotDeg = o?.second ?: 0f)
            }
            // A video-filled shape (build 104): advance its decoder to this frame. The live composite
            // runs for a single repeat copy; several copies rasterise the poster like any repeat.
            val fillOrig = if (g.mediaFill) layout[i].clips.singleOrNull() else null
            val fillCopies = if (fillOrig != null) copiesOfAdj(texts[0]) else null
            var fillSrcV: VideoLayerSource? = null
            var useVideo = false
            if (fillOrig != null && fillCopies != null && fillCopies.size == 1) {
                val s = fillSources[fillOrig.id]
                val v = s?.video
                if (s != null && v != null) {
                    advanceFill(s, fillOrig, t)
                    if (s.shown && fillVideoPose(fillCopies[0], w, h, k, dens, v.contentWidth, v.contentHeight) != null) {
                        fillSrcV = v
                        useVideo = true
                    }
                }
            }
            // A layer placed by the renderer (pose) is drawn upright: only what changes the picture is in the key, so a
            // layer that just turns or moves is not redrawn. A layer with the Repeat effect is drawn once per copy
            // (RepeatEval.copiesOf): in its own upright picture the copies are placed by undoing the layer's own
            // turn, so the picture's turn puts each copy back on the scene-space grid (see RepeatEval).
            val drawClips = if (fillOrig != null) {
                // Live: only the outline is rasterised (the video is composited under it).
                // Poster: the whole shape, poster fill included.
                if (useVideo) listOf(fillCopies!![0].strokeOnlyCopy()) else fillCopies!!
            } else if (g.pose) texts.flatMap { c ->
                val upright = if (cam.activeFor(c, t)) uprightForCamera(c) else uprightForPose(c)
                RepeatEval.copiesOf(upright, undoRotationDeg = c.rotationDeg, timeMs = t)
            } else texts.flatMap { copiesOfAdj(it) }
            // An imported text animator changes the picture with the time: the time is part of the key for those layers only.
            val animTimes = drawClips.map { if (it.textAnimators.isNotEmpty()) t else -1L }
            // The mask layers cutting this picture (a masked layer always has a picture of its own):
            // each is evaluated like every layer and is part of the key, so the cuts follow the masks'
            // own animation. Outside a mask layer's own span there is no cut from it.
            val maskAdjs = if (g.masked && !g.pose) {
                val mcs = layout[i].clips.firstOrNull()?.id?.let { maskByTarget[it] }.orEmpty()
                mcs.filter { it.isActiveAt(t) }
                    .map { if (rig.hasLinks) rig.adjusted(it, t) else NativeRenderAt.renderAt(it, t) }
            } else emptyList()
            // The fill's raster mode is part of the key too: live (outline only) and poster (whole
            // shape) are different pictures, and the silhouette follows the same key.
            val sig = listOf(drawClips, fonts, PreviewFrameRef.widthPx, w, h, pics.map { it != null }, stickerFrames, animTimes, maskAdjs, useVideo)
            if (sig != g.sig) {
                bmp.eraseColor(0)
                // A blended layer's opacity is applied by the blend shader, not in the picture.
                val applyOpacity = g.blendId == 0
                var textDrew = false
                for (c in drawClips) {
                    if (g.mediaFill && useVideo) {
                        // The outline of a live video fill goes through the Canvas-capable painter
                        // (build 104): the C++ shape painter declines freehand path shapes, whose
                        // outlines only LayerPainter can replay. Vector-only here (no bitmap loads).
                        val fp = fillPainterFor(w, h, dens)
                        fp.nativeTarget = bmp
                        fp.draw(android.graphics.Canvas(bmp), c, applyOpacity, t)
                    } else if (c.type == LayerType.SHAPE) {
                        if (!shapePainter.drawShape(bmp, c, w, h, k, dens, applyOpacity, cropFractionsOfClip(c))) {
                            // Path shapes (the importer's `.pie` / `.arc` / converted-path
                            // layers) are declined by the C++ shape painter: only the
                            // Canvas painter can replay their stored outline. Fall back
                            // to it — the same route the live video fill takes above, and
                            // the same picture the export draws.
                            if (c.shapePattern == "PATH") {
                                val fp = fillPainterFor(w, h, dens)
                                fp.nativeTarget = bmp
                                fp.draw(android.graphics.Canvas(bmp), c, applyOpacity, t)
                            } else {
                                Log.w(TAG, "native shape: layer '${c.name}' was not drawn")
                            }
                        }
                    } else if (c.type == LayerType.STICKER) {
                        val st = StickerLibrary.frameFor(c.uri, c.sourceOffsetMs(t - c.startMs))
                        if (st != null && !shapePainter.drawSticker(bmp, st, c, w, h, k, dens, applyOpacity)) {
                            Log.w(TAG, "native sticker: layer '${c.name}' was not drawn")
                        }
                    } else if (!textPainter.draw(bmp, c, w, h, k, applyOpacity, cropFractionsOfClip(c), t)) {
                        Log.w(TAG, "native text: layer '${c.name}' was not drawn")
                    } else textDrew = true
                }
                // A text layer with its own masks: remember the box it was just drawn in (the text
                // engine lays out at scale 1; the layer's effective scale sizes the drawn box), for
                // the own-mask pass below (see OverlayMasks).
                if (textDrew && g.ownMask && texts.isNotEmpty()) {
                    val s = (texts[0].effectiveScalePercent / 100f).coerceAtLeast(0.01f)
                    g.textBoxW = textPainter.lastBoxW * s
                    g.textBoxH = textPainter.lastBoxH * s
                }
                // The layer masks cut the finished picture (before any effects, which run on the cut
                // picture like on any other): each mask layer's own rendering, alpha only, in turn —
                // a Mask layer keeps the picture only where it is, an Exclude Mask layer removes it
                // where it is (see LayerMaskLinks).
                for (maskAdj in maskAdjs) {
                    if (!applyLayerMask(bmp, maskAdj, w, h, k, dens, t)) {
                        Log.w(TAG, "layer mask '${maskAdj.name}' was not drawn, its layer is shown uncut by it")
                    }
                }
                // The video fill's window (build 104): the shape's silhouette (white fill, no stroke,
                // no opacity — the composite applies it), cut by the same mask layers when there are
                // any, uploaded as the texture the renderer multiplies the placed video by.
                if (g.mediaFill && useVideo) {
                    var sil = g.silBmp
                    if (sil == null || sil.width != w || sil.height != h || sil.isRecycled) {
                        if (g.silTex != 0) r.deleteTexture(g.silTex)
                        sil?.recycle()
                        sil = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        g.silBmp = sil
                        g.silTex = r.createTexture2d()
                        g.silUploaded = false
                    }
                    sil.eraseColor(0)
                    val fp = fillPainterFor(w, h, dens)
                    fp.nativeTarget = sil
                    fp.draw(android.graphics.Canvas(sil), fillCopies!![0].silhouetteCopy(), applyOpacity = false, timeMs = t)
                    for (maskAdj in maskAdjs) {
                        drawMaskScratch(maskAdj, w, h, k, dens, t)?.let { scratch ->
                            android.graphics.Canvas(sil).drawBitmap(scratch, 0f, 0f, if (maskAdj.isExcludeMask) MASK_DST_OUT_PAINT else MASK_DST_IN_PAINT)
                        }
                    }
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
                if (g.pose) {
                    val c0 = drawClips[0]
                    if (c0.type == LayerType.SHAPE || c0.type == LayerType.STICKER) {
                        g.boxW = shapeBoxPx(dens, k)
                        g.boxH = g.boxW
                    } else {
                        g.boxW = textPainter.lastBoxW
                        g.boxH = textPainter.lastBoxH
                    }
                }
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, g.tex)
                if (!g.uploaded) {
                    GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
                    g.uploaded = true
                } else {
                    GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, bmp)
                }
                // Effects run on the layer's own box, not on the whole canvas-sized picture (see OverlayFx).
                g.region = if (g.fx && !g.pose) OverlayFx.regionOf(bmp) else null
                g.sig = sig
            }
            if (!g.uploaded) continue
            // Effects of the Effects panel on this layer's own picture (blur, glow, ...): the result is an upright texture.
            var drawTex = g.tex
            var drawMat = FLIP_V
            var contW = w          // size of the picture that is drawn (the layer's box when the effects ran on it)
            var contH = h
            var boxLp: LayerParams? = null
            // Video fill (build 104): the playing video, cut by the shape's silhouette (and run
            // through the layer's effects when it has any), goes down first; the group's own
            // picture — the shape's outline, or the poster when the video could not be shown — is
            // drawn over it by the branches below, without the effects a second time.
            val fillDrawn = if (g.mediaFill && useVideo && fillSrcV != null && g.silTex != 0 && g.silBmp != null) {
                val ops = if (g.fx) fxOpsOf(texts[0], t, layout[i].clips.firstOrNull { it.id == texts[0].id } ?: texts[0]) else null
                drawFillVideoComposite(r, fillSrcV!!, fillCopies!![0], g.silTex, g.silBmp!!, g.fx, ops, g.blendId, t, canvasRgb, w, h, k, dens)
            } else false
            if (g.fx && !fillDrawn) {
                val ops = fxOpsOf(texts[0], t, layout[i].clips.firstOrNull { it.id == texts[0].id } ?: texts[0])
                if (ops != null) {
                    val reg = g.region
                    val fxTex = r.applyFx(
                        g.tex, false, FLIP_V, w, h, 0,
                        0, 0f, 0, false, canvasRgb, ops, ClipFxLibrary.grainFrame(t), null,
                        reg?.crop(w, h), !g.pose
                    )
                    if (fxTex != 0) {
                        drawTex = fxTex
                        drawMat = IDENTITY_TEX_MATRIX
                        if (reg != null) {
                            contW = reg.w
                            contH = reg.h
                            boxLp = reg.place(w, h)
                        }
                    } else {
                        Log.w(TAG, "native effects failed on '${texts[0].name}', drawn without them")
                    }
                }
            }
            // The layer's OWN masks (Masks panel; an imported wipe2 lands here — Graph round 6 G1):
            // photos run theirs inside the fx pass on their own texture, but an overlay's picture
            // is frame-sized, so the masks are remapped from the layer's box into the picture's
            // space (OverlayMasks) and run as their own pass — on the effects' result when effects
            // ran, exactly like the native side orders them (masks last). Pose pictures are cut
            // too (build 166): the mask belongs to the layer's picture, so it is applied to the
            // upright picture — with the box of the UPRIGHT clip (centred, unturned), the same
            // clip the painters drew — and the projection then carries the cut along. The live
            // video fill is already on the frame and is not cut (no imported wipe combines
            // with a video fill).
            if (g.ownMask && !fillDrawn) {
                val orig = layout[i].clips.singleOrNull()
                val eval = texts.firstOrNull()
                if (orig != null && eval != null) {
                    val raw = ClipMaskLibrary.toNative(orig.masks, t - orig.startMs, CutoutMasks.sourceMs(orig, t))
                    if (raw != null) {
                        val stickerBm = if (eval.type == LayerType.STICKER) StickerLibrary.frameFor(eval.uri, eval.sourceOffsetMs(t - eval.startMs)) else null
                        val boxClip = if (g.pose) {
                            if (cam.activeFor(eval, t)) uprightForCamera(eval) else uprightForPose(eval)
                        } else eval
                        val box = OverlayMasks.boxOf(
                            boxClip,
                            textDrawn = if (g.textBoxW > 0f) g.textBoxW to g.textBoxH else null,
                            stickerSize = stickerBm?.let { it.width to it.height },
                            w, h, k, dens
                        )
                        if (box != null) {
                            val reg = if (drawTex != g.tex) g.region else null
                            val tx = reg?.x?.toFloat() ?: 0f
                            val ty = reg?.y?.toFloat() ?: 0f
                            val tw = reg?.w?.toFloat() ?: w.toFloat()
                            val th = reg?.h?.toFloat() ?: h.toFloat()
                            val ops = OverlayMasks.remap(raw, box, tx, ty, tw, th)
                            val maskTex = r.applyFx(drawTex, false, drawMat, contW, contH, 0, 0, 0f, 0, false, canvasRgb, null, 0, ops, null, false)
                            if (maskTex != 0) {
                                drawTex = maskTex
                                drawMat = IDENTITY_TEX_MATRIX
                            } else {
                                Log.w(TAG, "own masks failed on '${eval.name}', drawn uncut")
                            }
                        }
                    }
                }
            }
            // 3D camera: the upright picture goes on the corners the camera sees it at (corner pin path).
            val seen = if (g.pose) cam.seeOverlay(texts[0], t, g.boxW, g.boxH, k, w, h) else null
            if (seen != null && seen.quad == null) continue  // behind the camera
            if (g.pose) {
                // The upright picture, placed by the renderer: pivot, rotation and 3D turn like a video layer.
                val lp = if (seen != null) LayerParams().apply { pin = seen.quad } else overlayPoseParams(texts[0], g.boxW, g.boxH, k)
                if (g.blendId == 0) {
                    r.drawLayer(drawTex, false, drawMat, w, h, 0, lp)  // opacity is in the picture
                } else {
                    lp.opacity = texts[0].opacityFraction
                    r.drawLayerBlended(drawTex, false, drawMat, w, h, 0, lp, g.blendId, canvasRgb)
                }
            } else if (g.blendId == 0) {
                r.drawLayer(drawTex, false, drawMat, contW, contH, 0, boxLp ?: LayerParams())
            } else if (drawTex == g.tex) {
                r.blendTexture(g.tex, g.blendId, texts[0].opacityFraction, canvasRgb)
            } else {
                val lp = (boxLp ?: LayerParams()).apply { opacity = texts[0].opacityFraction }
                r.drawLayerBlended(drawTex, false, drawMat, contW, contH, 0, lp, g.blendId, canvasRgb)
            }
        }
        if (picturesMissing && !fillRetryPosted) {
            fillRetryPosted = true
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ fillRetryPosted = false; requestFrame() }, 300L)
        }
    }

    /** Scratch picture a mask layer is drawn into before it cuts a masked layer's picture (see [drawMaskScratch]). */
    private var maskScratch: Bitmap? = null

    // The Canvas-capable painter for video-fill outlines and silhouettes (build 104): LayerPainter
    // replays freehand path shapes the C++ shape painter declines, with the preview's real density
    // (its PreviewFrameRef density is stale here) and the C++ painter first for built-in kinds.
    private var fillPainter: LayerPainter? = null
    private var fillPainterKey: Triple<Int, Int, Float>? = null

    private fun fillPainterFor(w: Int, h: Int, dens: Float): LayerPainter {
        val key = Triple(w, h, dens)
        val cur = fillPainter
        if (cur != null && fillPainterKey == key) return cur
        val p = LayerPainter(w, h, dens)
        p.nativeShape = shapePainter
        fillPainter = p
        fillPainterKey = key
        return p
    }

    /**
     * A video-filled shape's live composite (build 104), the preview twin of the exporter's: the fill
     * video is placed into the shape's box (FillVideoPlacement), cut by the shape's silhouette
     * texture, run through the layer's effects when it has any, and drawn — the shape's own outline
     * picture goes over it afterwards. False = could not composite (the caller keeps the poster).
     */
    private fun drawFillVideoComposite(
        r: NativeRenderer, src: VideoLayerSource, evalClip: TimelineClip, silTex: Int, silBmp: Bitmap,
        fx: Boolean, ops: FloatArray?, blendId: Int, t: Long, canvasRgb: FloatArray,
        w: Int, h: Int, k: Float, dens: Float
    ): Boolean {
        val pose = fillVideoPose(evalClip, w, h, k, dens, src.contentWidth, src.contentHeight) ?: return false
        if (!r.beginGroup()) return false
        val lp = LayerParams().apply {
            offsetX = pose.offsetX; offsetY = pose.offsetY
            scaleX = pose.scaleX; scaleY = pose.scaleY
            rotationDeg = pose.rotationDeg
        }
        r.drawLayer(src.textureId, true, src.texMatrix, src.contentWidth, src.contentHeight, src.drawRotation, lp)
        val placed = r.endGroup()
        if (placed == 0) return false
        val cut = r.applyTexMask(placed, false, IDENTITY_TEX_MATRIX, w, h, 0, silTex)
        if (cut == 0) return false
        var tex = cut
        var contW = w
        var contH = h
        var placeLp: LayerParams? = null
        if (fx && ops != null) {
            val reg = OverlayFx.regionOf(silBmp)
            val fxTex = r.applyFx(
                tex, false, IDENTITY_TEX_MATRIX, w, h, 0,
                0, 0f, 0, false, canvasRgb, ops, ClipFxLibrary.grainFrame(t), null,
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
        if (blendId == 0) r.drawLayer(tex, false, IDENTITY_TEX_MATRIX, contW, contH, 0, outLp)
        else r.drawLayerBlended(tex, false, IDENTITY_TEX_MATRIX, contW, contH, 0, outLp, blendId, canvasRgb)
        return true
    }

    /**
     * Draws the mask layer [mask] (already evaluated at [t]) into the shared scratch picture and
     * returns it — the same painters that draw every layer, so the rendering is what the mask layer
     * would look like if it were drawn. Null = the mask could not be drawn (callers leave the masked
     * picture uncut).
     */
    private fun drawMaskScratch(mask: TimelineClip, w: Int, h: Int, k: Float, dens: Float, t: Long): Bitmap? {
        var scratch = maskScratch
        if (scratch == null || scratch.width != w || scratch.height != h || scratch.isRecycled) {
            scratch?.recycle()
            scratch = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            maskScratch = scratch
        }
        scratch.eraseColor(0)
        return if (maskCoverageInto(scratch, mask, w, h, k, dens, t, 0)) scratch else null
    }

    /**
     * Draws the mask layer [mask] (evaluated at [t]) into [out] as coverage. Shape / text masks use
     * the painters, exactly as before group masks existed. A GROUP mask (build 141) composites its
     * scope: the group's picture IS its content in Alight, so the coverage is the scope's layers
     * composited in stacking order, each drawn by the same painters with its fully-parented pose
     * (the [parentRig] chain runs through the mask group itself, the way the scene draws them) and
     * cut by its own masks inside the scope first — the evergreen presets nest a whole mask group
     * inside another mask group, which the recursion handles the same way. Children that paint
     * nothing in a scene (audio / null / camera / chain lanes) are skipped; a child whose picture
     * the painters cannot produce (a media layer) fails the whole mask — the caller leaves the
     * target uncut and logs it — rather than cutting by a partial coverage. A group child's own
     * blend mode is composited as plain alpha-over (every corpus mask-group child is Normal).
     * The group's own opacity scales the finished coverage, like a leaf mask's painter opacity.
     */
    private fun maskCoverageInto(
        out: Bitmap, mask: TimelineClip, w: Int, h: Int, k: Float, dens: Float, t: Long, depth: Int
    ): Boolean {
        if (depth > 4) return false
        return when (mask.type) {
            LayerType.SHAPE -> shapePainter.drawShape(out, mask, w, h, k, dens, true, cropFractionsOfClip(mask))
            LayerType.TEXT -> textPainter.draw(out, mask, w, h, k, true, cropFractionsOfClip(mask), t)
            LayerType.GROUP -> {
                val rig = parentRig()
                val scopeClips = GroupOps.scopeTracks(liveTracks, mask.id).asReversed().flatMap { it.clips }
                val scopeMasks = LayerMaskLinks.masksByTarget(scopeClips)
                val comp = groupMaskTemp(depth * 3 + 1, w, h)
                comp.eraseColor(0)
                var any = false
                for (c in scopeClips) {
                    if (!c.isVisible || c.isMask || !c.isActiveAt(t)) continue
                    if (c.type == LayerType.AUDIO || c.type == LayerType.NULL ||
                        c.type == LayerType.CAMERA || c.type == LayerType.CHAIN ||
                        c.type == LayerType.CONTROLLER
                    ) continue
                    val adj = if (rig.hasLinks) rig.adjusted(c, t) else NativeRenderAt.renderAt(c, t)
                    val tmp = groupMaskTemp(depth * 3, w, h)
                    tmp.eraseColor(0)
                    val drew = when {
                        adj.type == LayerType.SHAPE &&
                            shapePainter.drawShape(tmp, adj, w, h, k, dens, true, cropFractionsOfClip(adj)) -> true
                        adj.type == LayerType.SHAPE -> {
                            // The C++ shape painter declines freehand path shapes; the Canvas-capable
                            // painter replays them (the video-fill outline precedent, build 104).
                            val fp = fillPainterFor(w, h, dens)
                            fp.nativeTarget = tmp
                            fp.draw(android.graphics.Canvas(tmp), adj, true, t)
                            true
                        }
                        else -> maskCoverageInto(tmp, adj, w, h, k, dens, t, depth + 1)
                    }
                    if (!drew) {
                        Log.w(TAG, "group mask '${mask.name}': child '${adj.name}' could not be drawn, the mask is not applied")
                        return false
                    }
                    for (m in scopeMasks[c.id].orEmpty()) {
                        if (!m.isActiveAt(t)) continue
                        val mAdj = if (rig.hasLinks) rig.adjusted(m, t) else NativeRenderAt.renderAt(m, t)
                        val ms = groupMaskTemp(depth * 3 + 2, w, h)
                        ms.eraseColor(0)
                        if (!maskCoverageInto(ms, mAdj, w, h, k, dens, t, depth + 1)) {
                            Log.w(TAG, "group mask '${mask.name}': inner mask '${mAdj.name}' could not be drawn, the mask is not applied")
                            return false
                        }
                        android.graphics.Canvas(tmp).drawBitmap(
                            ms, 0f, 0f, if (m.isExcludeMask) MASK_DST_OUT_PAINT else MASK_DST_IN_PAINT
                        )
                    }
                    android.graphics.Canvas(comp).drawBitmap(tmp, 0f, 0f, null)
                    any = true
                }
                if (!any) return false
                val alpha = mask.opacityFraction
                if (alpha >= 0.999f) {
                    android.graphics.Canvas(out).drawBitmap(comp, 0f, 0f, null)
                } else {
                    val paint = android.graphics.Paint().apply {
                        this.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)
                    }
                    android.graphics.Canvas(out).drawBitmap(comp, 0f, 0f, paint)
                }
                true
            }
            else -> false
        }
    }

    /** Depth-indexed scratch bitmaps for group-mask coverage compositing (see [maskCoverageInto]). */
    private val groupMaskPool = ArrayList<Bitmap?>()

    private fun groupMaskTemp(slot: Int, w: Int, h: Int): Bitmap {
        while (groupMaskPool.size <= slot) groupMaskPool.add(null)
        var bmp = groupMaskPool[slot]
        if (bmp == null || bmp.width != w || bmp.height != h || bmp.isRecycled) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            groupMaskPool[slot] = bmp
        }
        return bmp
    }

    /**
     * Cuts [bmp] (a masked layer's own picture) by the mask layer's alpha: the mask layer is drawn into a
     * scratch picture with the same painters and multiplied over the picture (DST_IN: the picture keeps
     * only where the mask is). An Exclude Mask layer ([TimelineClip.isExcludeMask]) subtracts instead
     * (DST_OUT: the picture is removed where the mask is). [mask] is the mask layer evaluated at the
     * frame's time, so the cut follows its animation. False = the mask could not be drawn (the picture
     * is left uncut).
     */
    private fun applyLayerMask(
        bmp: Bitmap, mask: TimelineClip, w: Int, h: Int, k: Float, dens: Float, t: Long
    ): Boolean {
        val scratch = drawMaskScratch(mask, w, h, k, dens, t) ?: return false
        android.graphics.Canvas(bmp).drawBitmap(scratch, 0f, 0f, if (mask.isExcludeMask) MASK_DST_OUT_PAINT else MASK_DST_IN_PAINT)
        return true
    }

    /** Texture the mask layer is uploaded into before it cuts a group picture (see [cutGroupPicture]). */
    private var groupMaskTex = 0
    private var groupMaskTexW = 0
    private var groupMaskTexH = 0

    /**
     * Cuts [tex] (a Group layer's composed picture, a renderer texture) by the mask layer's alpha:
     * the mask is drawn exactly as in [applyLayerMask], uploaded, and the renderer multiplies the
     * picture by it (DST_IN, see [NativeRenderer.applyTexMask]) — or by one-minus-alpha for an
     * Exclude Mask layer (DST_OUT). Returns the cut picture's texture,
     * or 0 when the cut could not be made (the caller draws the picture uncut).
     */
    private fun cutGroupPicture(
        r: NativeRenderer, tex: Int, mask: TimelineClip, w: Int, h: Int, k: Float, dens: Float, t: Long
    ): Int {
        val scratch = drawMaskScratch(mask, w, h, k, dens, t) ?: return 0
        if (groupMaskTex == 0 || groupMaskTexW != w || groupMaskTexH != h) {
            if (groupMaskTex != 0) r.deleteTexture(groupMaskTex)
            groupMaskTex = r.createTexture2d()
            if (groupMaskTex == 0) return 0
            groupMaskTexW = w
            groupMaskTexH = h
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, groupMaskTex)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, scratch, 0)
        } else {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, groupMaskTex)
            GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, scratch)
        }
        return r.applyTexMask(tex, false, IDENTITY_TEX_MATRIX, w, h, 0, groupMaskTex, mask.isExcludeMask)
    }

    private fun logPoolStats() {
        if (!playing) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastStatsMs < STATS_EVERY_MS) return
        lastStatsMs = now
        val st = pool?.stats() ?: return
        Log.i(TAG, "pool hw=${st.activeHw}/${st.maxHw} sw=${st.activeSw}/${st.maxSw} opened=${st.opened} evictions=${st.evictions} fallbacks=${st.fallbacks} errors=${st.errors}")
    }

    /** Puts the picture that belongs to project time [t] on the layer's texture. */
    private fun advance(
        r: NativeRenderer, c: TimelineClip, s: Source, v: VideoLayerSource,
        anim: NativeLayerAnim, t: Long, p: LayerParams
    ) {
        val targetUs = anim.sourceMs(t) * 1000L
        val frameUs = if (v.clip.fps > 1f) (1_000_000f / v.clip.fps).toLong() else 33_333L
        var st: Int
        if (!s.shown || !playing) {
            st = seekTo(v, targetUs, patient = !s.shown)
        } else {
            val diff = targetUs - v.clip.ptsUs
            if (diff < -2 * frameUs || diff > MAX_CATCHUP_US) {
                st = seekTo(v, targetUs, patient = false)
            } else {
                st = DecoderPool.SAME
                var n = 0
                while (targetUs - v.clip.ptsUs >= frameUs && n < 3) {
                    st = v.next()
                    n++
                    if (st != DecoderPool.OK) break
                }
            }
        }
        if (st == DecoderPool.OK || st == DecoderPool.SAME) s.shown = true
        // END_OF_STREAM / BUSY: the last picture stays on the texture.

        // A hardware decoder that outputs only black (seen with several layers on some chips): the first
        // frames are checked, a black layer is re-opened with a software decoder (same as the export).
        if (s.shown && s.probes < 3 && !s.swRetried) {
            s.probes++
            if (r.layerLooksBlack(v.textureId, true, v.texMatrix, v.contentWidth, v.contentHeight, v.drawRotation, p)) {
                s.swRetried = true
                val fresh = VideoLayerSource.open(appCtx, Uri.parse(s.uri), r, pool ?: return, preferSoftware = true)
                if (fresh != null) {
                    val st2 = seekTo(fresh, targetUs, patient = true)
                    if (st2 == DecoderPool.OK || st2 == DecoderPool.SAME) {
                        v.close()
                        val ns = Source(s.uri, fresh, null).also { it.shown = true; it.probes = 3; it.swRetried = true }
                        sources[c.id] = ns
                    } else {
                        fresh.close()
                    }
                }
            }
        }
    }

    private fun seekTo(v: VideoLayerSource, targetUs: Long, patient: Boolean): Int {
        var st = v.seek(targetUs)
        if (patient) {
            var tries = 0
            while (st == DecoderPool.BUSY && tries++ < 40) {
                Thread.sleep(10)
                st = v.seek(targetUs)
            }
        }
        return st
    }

    private companion object {
        val MAIN_OWNER = Any()
        const val TAG = "NativePreview"
        const val PRELOAD_MS = 1_500L
        const val KEEP_MS = 500L
        const val MAX_PHOTO_SIDE = 2048
        const val MAX_CATCHUP_US = 750_000L
        const val MAX_HW_DECODERS = 8   // preview: one hardware decoder per visible layer, no evict/reopen
        const val STATS_EVERY_MS = 2_000L

        /** Texture matrix of a plain 2D texture whose first row is the TOP of the picture. */
        val FLIP_V = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f
        )

        /** Alpha multiply of a layer mask (DST_IN): the picture keeps only where the mask layer is. */
        val MASK_DST_IN_PAINT = android.graphics.Paint().apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        }

        /** Erase by an Exclude Mask layer (DST_OUT): the picture is removed where the mask layer is. */
        val MASK_DST_OUT_PAINT = android.graphics.Paint().apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
        }
    }
}
