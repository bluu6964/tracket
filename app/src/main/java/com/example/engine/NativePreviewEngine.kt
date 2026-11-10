package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.net.Uri
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import com.example.model.ClipFxLibrary
import com.example.model.GroupOps
import com.example.model.ClipMaskLibrary
import com.example.model.BlendKind
import com.example.model.BlendModes
import com.example.model.isBehindCamera
import com.example.model.opacityFraction
import com.example.model.sourceOffsetMs
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.TimelineTrack
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Editor preview of the video / photo layers drawn by the C++ GL renderer (the same one the export uses).
 * One GL thread owns the renderer and the decoder pool; the UI only hands over the state (tracks, playhead
 * provider) and asks for frames. The editor playhead is the clock: every frame shows the picture under it.
 *
 *  - paused / scrubbing: exact seek to the playhead (the pool decodes forward without re-seeking when it can)
 *  - playing: next() frames in order, a re-seek only when far off
 * Sound is not played here: [NativeAudioPreview] (C++ mixer + AAudio) plays it; there are no Media3 players in the preview any more.
 * The canvas and a full-screen editor (Crop, Edit shape) can each have a surface: see [surfaceChanged] and [setOverride].
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
    private val anims = HashMap<String, AnimEntry>()
    /** Clean patches of the Remove tool (GL textures, loaded on first use). */
    private val removePatches = RemovePatchCache()

    // Text / shape / sticker layers: drawn by the C++ engines into frame-sized bitmaps, shown as textures on top of the pictures.
    // Layers that follow each other share one picture; a layer with a blend mode gets its own (same grouping as the export).
    private class OverlayGroup(val ids: List<String>, val blendId: Int, val pose: Boolean = false, val fx: Boolean = false) {
        // fx = a layer with effects (Effects panel): always its own picture, the C++ effects run on it before it is drawn.
        // pose = a layer turned in 3D / with its own pivot (OverlayPose.kt): drawn upright into its own picture, the
        // renderer places it. boxW / boxH = the layer's box at scale 1 (its pivot is a fraction of it).
        var boxW = 0f
        var boxH = 0f
        var bmp: Bitmap? = null
        var tex = 0
        var uploaded = false
        var sig: List<Any?>? = null
        // fx layers that are not placed by the renderer: the box of the layer inside the picture (the effects run on it).
        var region: FxRegion? = null
    }
    // Overlay pictures (text / shape / sticker) of each level: "" = the top level, else the id of a Group layer (every group
    // is drawn as its own picture, see drawGroupLayer, and keeps its own overlay pictures).
    private val overlayCaches = HashMap<String, List<OverlayGroup>>()
    private val textPainter by lazy {
        NativeTextPainter(
            { name -> com.example.ui.text.FontRepository.fileOrNull(appCtx, name)?.absolutePath },
            { uri -> FillImages.peek(uri) }  // never blocks: starts the load and returns null until it is ready
        )
    }
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
    fun update(tracks: List<TimelineTrack>, playheadOf: () -> Long, canvasArgb: Int) {
        this.tracks = tracks
        this.playheadOf = playheadOf
        this.canvasArgb = canvasArgb
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
            g.bmp?.recycle()
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

        val bg = canvasArgb
        val canvasRgb = floatArrayOf(
            ((bg shr 16) and 0xFF) / 255f, ((bg shr 8) and 0xFF) / 255f, (bg and 0xFF) / 255f
        )
        r.beginFrame(canvasRgb[0], canvasRgb[1], canvasRgb[2], 1f)
        val previewW = PreviewFrameRef.widthPx
        val rig = parentRig()
        val cam = CameraRig(liveTracks, rig)
        val firstMedia = media.firstOrNull()
        val groupIds = GroupOps.groupIdsOf(liveTracks)
        // Depth of a layer or group: bigger Z = nearer = drawn later.
        // Under a 3D camera it is the distance from the camera instead (CameraRig.stackKey), also for shapes / text / stickers.
        fun zOf(c: TimelineClip): Float =
            if (c.type == LayerType.GROUP) (if (rig.hasLinks) rig.adjusted(c, t) else NativeRenderAt.renderAt(c, t)).transformZ
            else (if (cam.hasCamera) cam.stackKey(if (rig.hasLinks) rig.adjusted(c, t) else NativeRenderAt.renderAt(c, t), t) else null)
                ?: animFor(c)?.let { a -> a.clipAt(t, t)[2] } ?: 0f
        // drawScope (below) draws one level; a group needs it for its own level, so it is reached through this variable.
        var drawScopeRef: (String?, Int) -> Unit = { _, _ -> }
        // One picture layer (video / photo); returns early when the layer is not drawn.
        fun drawMediaLayer(c: TimelineClip, depth: Int) {
            val anim = animFor(c) ?: return
            val fp = anim.eval(t, outW, outH, previewW)
            if (rig.hasLinks) rig.applyToFp(c, t, fp, if (previewW > 0f) outW / previewW else 1f)  // Null parent
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
                if (baseClip == null || baseAnim == null || !baseClip.isActiveAt(t)) return
                val bfp = baseAnim.eval(t, outW, outH, previewW)
                val bp = LayerParams().apply {
                    offsetX = bfp[0]; offsetY = bfp[1]
                    scaleX = bfp[2]; scaleY = bfp[3]
                    rotationDeg = bfp[4]
                    cropX = bfp[6]; cropY = bfp[7]; cropW = bfp[8]; cropH = bfp[9]
                    rotX = bfp[10]; rotY = bfp[11]; pivotX = bfp[12]; pivotY = bfp[13]
                    baseX = bfp[14]; baseY = bfp[15]; perspD = bfp[16]
                }
                p.pin = CornerPinRuntime.quadOnCanvas(pinSpec, t, bp, outW, outH) ?: return
            }
            // Blend mode (same rule as the export): a matte on the lowest layer has nothing to cut, it behaves like Normal.
            val found = BlendModes.find(c.blendMode)
            val blendId = if (c === firstMedia && found.kind == BlendKind.MATTE) 0 else found.shaderId
            val src = sources[c.id] ?: return
            val img = src.image
            if (img != null) {
                if (!placeByCamera(cam, c, t, anim, fp, p, img.w, img.h, previewW)) return
                // Effects of the Effects panel on a photo (before crop / transform, like on a video).
                val imgOps = ClipFxLibrary.toNative(c.effects)
                val imgMasks = ClipMaskLibrary.toNative(c.masks, t - c.startMs, CutoutMasks.sourceMs(c, t))
                if (imgOps != null || imgMasks != null) {
                    val fxTex = r.applyFx(
                        img.tex, false, FLIP_V, img.w, img.h, 0,
                        0, 0f, 0, false, canvasRgb, imgOps, ClipFxLibrary.grainFrame(t), imgMasks
                    )
                    if (fxTex != 0) {
                        if (blendId == 0) r.drawLayer(fxTex, false, IDENTITY_TEX_MATRIX, img.w, img.h, 0, p)
                        else r.drawLayerBlended(fxTex, false, IDENTITY_TEX_MATRIX, img.w, img.h, 0, p, blendId, canvasRgb)
                        return
                    }
                    Log.w(TAG, "native effects / masks failed on photo '${c.name}', drawn without them")
                }
                if (blendId == 0) r.drawLayer(img.tex, false, FLIP_V, img.w, img.h, 0, p)
                else r.drawLayerBlended(img.tex, false, FLIP_V, img.w, img.h, 0, p, blendId, canvasRgb)
                return
            }
            val v = src.video ?: return
            advance(r, c, src, v, anim, t, p)
            val now = sources[c.id] ?: return  // advance() may have swapped in a software decoder
            val cur = now.video ?: return
            if (!now.shown) return
            if (!placeByCamera(cam, c, t, anim, fp, p, cur.contentWidth, cur.contentHeight, previewW)) return
            // Face blur / cutout on the decoded picture first (before crop / transform), like the export.
            val slot = media.indexOfFirst { it.id == c.id }
            val srcMs = anim.sourceMs(t)
            val faceMask = NativeFxMasks.face(r, c, slot, srcMs, strict = false)
            val cutMask = NativeFxMasks.cutout(r, c, slot, srcMs, strict = false)
            val fxOps = ClipFxLibrary.toNative(c.effects)
            val maskOps = ClipMaskLibrary.toNative(c.masks, t - c.startMs, CutoutMasks.sourceMs(c, t))
            if (faceMask != 0 || cutMask != 0 || fxOps != null || maskOps != null) {
                val fxTex = r.applyFx(
                    cur.textureId, true, cur.texMatrix, cur.contentWidth, cur.contentHeight, cur.drawRotation,
                    faceMask, c.faceBlurStrength, cutMask, flatten = c === firstMedia && depth == 0, canvasRgb = canvasRgb,
                    fxOps = fxOps, grainFrame = ClipFxLibrary.grainFrame(t), maskOps = maskOps
                )
                if (fxTex != 0) {
                    if (blendId == 0) {
                        r.drawLayer(fxTex, false, IDENTITY_TEX_MATRIX, cur.contentWidth, cur.contentHeight, 0, p)
                    } else {
                        r.drawLayerBlended(fxTex, false, IDENTITY_TEX_MATRIX, cur.contentWidth, cur.contentHeight, 0, p, blendId, canvasRgb)
                    }
                    RemoveRuntime.draw(r, removePatches, c, p, t, outW, outH, blendId, canvasRgb)
                    return
                }
                Log.w(TAG, "native face blur / cutout / effects / masks failed on '${c.name}', drawn without the effect")
            }
            if (blendId == 0) {
                r.drawLayer(cur.textureId, true, cur.texMatrix, cur.contentWidth, cur.contentHeight, cur.drawRotation, p)
            } else {
                r.drawLayerBlended(cur.textureId, true, cur.texMatrix, cur.contentWidth, cur.contentHeight, cur.drawRotation, p, blendId, canvasRgb)
            }
            // Remove: the clean patch on the moving surface, right after the layer's own picture.
            RemoveRuntime.draw(r, removePatches, c, p, t, outW, outH, blendId, canvasRgb)
        }
        // A Group layer is ONE layer: the layers inside are drawn into their own picture (the renderer's group pass), and that
        // picture gets the group's opacity, blend mode, effects and masks like a photo would. The group's move / scale / turn
        // already reached the layers inside through their parent link (ParentRig), so the picture is placed at 0 / 0 / 1.
        fun drawGroupLayer(c: TimelineClip, depth: Int) {
            val g = if (rig.hasLinks) rig.adjusted(c, t) else NativeRenderAt.renderAt(c, t)
            val opacity = g.opacityFraction.coerceIn(0f, 1f)
            if (opacity <= 0f) return
            if (!r.beginGroup()) {  // too deep / no memory: the layers go straight onto the frame (no group blend / effects)
                drawScopeRef(c.id, depth + 1)
                return
            }
            drawScopeRef(c.id, depth + 1)
            val tex = r.endGroup()
            if (tex == 0) return
            val blendId = BlendModes.find(c.blendMode).shaderId
            val fxOps = ClipFxLibrary.toNative(c.effects)
            val maskOps = ClipMaskLibrary.toNative(c.masks, t - c.startMs, CutoutMasks.sourceMs(c, t))
            var drawTex = tex
            if (fxOps != null || maskOps != null) {
                val fxTex = r.applyFx(
                    tex, false, IDENTITY_TEX_MATRIX, outW, outH, 0,
                    0, 0f, 0, false, canvasRgb, fxOps, ClipFxLibrary.grainFrame(t), maskOps, null, true
                )
                if (fxTex != 0) drawTex = fxTex
                else Log.w(TAG, "native effects / masks failed on group '${c.name}', drawn without them")
            }
            val lp = LayerParams().apply { this.opacity = opacity }
            if (blendId == 0) r.drawLayer(drawTex, false, IDENTITY_TEX_MATRIX, outW, outH, 0, lp)
            else r.drawLayerBlended(drawTex, false, IDENTITY_TEX_MATRIX, outW, outH, 0, lp, blendId, canvasRgb)
        }
        // One level: the top level, or the layers inside a group. One stacking order for pictures, groups and shapes / text /
        // stickers: bigger Z in front; equal Z = lane order (a layer added later, in a higher lane, is in front). A picture
        // or group is drawn before an overlay group when it is behind it.
        val lane = laneIndex()
        fun drawScope(scopeId: String?, depth: Int) {
            val clips = GroupOps.scopeTracks(liveTracks, scopeId).asReversed().flatMap { it.clips }
            val active = clips.filter {
                it.isVisible && it.isActiveAt(t) &&
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
            drawTextLayers(r, t, canvasRgb, scopeId, drawMediaUpTo)
            drawMediaUpTo(Float.POSITIVE_INFINITY, Int.MAX_VALUE)  // pictures in front of every shape / text / sticker
        }
        drawScopeRef = ::drawScope
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
            .filter { it.isVisible && (nativeTextEligible(it) || nativeShapeEligible(it)) }
        if (overlays.isEmpty()) {
            if (groups.isNotEmpty()) {
                closeGroupList(r, groups)
                overlayCaches.remove(cacheKey)
            }
            return
        }
        // A layer with effects always gets its own picture, so the effects touch only that layer (text / shape / sticker).
        val rig = parentRig()
        val cam = CameraRig(liveTracks, rig)  // 3D camera: layers under a camera lane are placed by it (own upright picture)
        // Stacking key of a text / shape / sticker layer: its distance from the camera when one looks at it, else its own Z.
        fun overlayKey(c: TimelineClip): Float = cam.stackKey(c, t) ?: c.transformZ
        class Slot(val clips: MutableList<TimelineClip>, val blendId: Int, val pose: Boolean, val fx: Boolean)
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
            val spec = BlendModes.find(c.blendMode)
            val hasFx = ClipFxLibrary.toNative(c.effects) != null
            if (overlayNeedsPose(c) || cam.affects(c)) {
                layout.add(Slot(mutableListOf(c), spec.shaderId, true, hasFx))  // 3D / own pivot: always its own picture
            } else if (hasFx) {
                layout.add(Slot(mutableListOf(c), spec.shaderId, false, true))
            } else if (spec.isNormal) {
                val last = layout.lastOrNull()
                if (last != null && last.blendId == 0 && !last.pose && !last.fx && !mediaBetween(last.clips.last(), c)) last.clips.add(c)
                else layout.add(Slot(mutableListOf(c), 0, false, false))
            } else {
                layout.add(Slot(mutableListOf(c), spec.shaderId, false, false))
            }
        }
        val same = groups.size == layout.size && layout.indices.all { i ->
            groups[i].blendId == layout[i].blendId && groups[i].pose == layout[i].pose && groups[i].fx == layout[i].fx &&
                groups[i].ids == layout[i].clips.map { it.id }
        }
        if (!same) {
            closeGroupList(r, groups)
            groups = layout.map { OverlayGroup(it.clips.map { c -> c.id }, it.blendId, it.pose, it.fx) }
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
            val pics = texts.filter { it.fillMode == com.example.model.FILL_IMAGE }.map { c -> c.fillImageUri?.let { FillImages.peek(it) } }
            if (pics.any { it == null }) picturesMissing = true
            // A sticker's animation frame depends on the time, so it is part of the key (same as the export).
            val stickerFrames = texts.map { c ->
                if (c.type == LayerType.STICKER) c.sourceOffsetMs(t - c.startMs) else -1L
            }
            // A layer placed by the renderer (pose) is drawn upright: only what changes the picture is in the key, so a
            // layer that just turns or moves is not redrawn.
            val drawClips = if (g.pose) texts.map { if (cam.activeFor(it, t)) uprightForCamera(it) else uprightForPose(it) } else texts
            // An imported text animator changes the picture with the time: the time is part of the key for those layers only.
            val animTimes = drawClips.map { if (it.textAnimators.isNotEmpty()) t else -1L }
            val sig = listOf(drawClips, fonts, PreviewFrameRef.widthPx, w, h, pics.map { it != null }, stickerFrames, animTimes)
            if (sig != g.sig) {
                bmp.eraseColor(0)
                // A blended layer's opacity is applied by the blend shader, not in the picture.
                val applyOpacity = g.blendId == 0
                for (c in drawClips) {
                    if (c.type == LayerType.SHAPE) {
                        if (!shapePainter.drawShape(bmp, c, w, h, k, dens, applyOpacity, cropFractionsOfClip(c))) {
                            Log.w(TAG, "native shape: layer '${c.name}' was not drawn")
                        }
                    } else if (c.type == LayerType.STICKER) {
                        val st = StickerLibrary.frameFor(c.uri, c.sourceOffsetMs(t - c.startMs))
                        if (st != null && !shapePainter.drawSticker(bmp, st, c, w, h, k, dens, applyOpacity)) {
                            Log.w(TAG, "native sticker: layer '${c.name}' was not drawn")
                        }
                    } else if (!textPainter.draw(bmp, c, w, h, k, applyOpacity, cropFractionsOfClip(c), t)) {
                        Log.w(TAG, "native text: layer '${c.name}' was not drawn")
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
            if (g.fx) {
                val ops = ClipFxLibrary.toNative(texts[0].effects)
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
    }
}
