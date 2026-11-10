package com.example.engine

import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/** Raw JNI functions of libam_engine.so (renderer part). Use [NativeRenderer] instead. */
internal object RendererNative {
    @JvmStatic external fun nativeCreate(): Long
    /** Corner pin helper: picture points (u, v pairs) of a video drawn with [params] -> canvas pixels (x, y pairs). No GL. */
    @JvmStatic external fun nativeQuadToCanvas(params: FloatArray, canvasW: Int, canvasH: Int, uv: FloatArray, out: FloatArray): Boolean
    @JvmStatic external fun nativeDestroy(r: Long)
    @JvmStatic external fun nativeCreateTexture(r: Long, oes: Boolean): Int
    @JvmStatic external fun nativeDeleteTexture(r: Long, tex: Int)
    @JvmStatic external fun nativeSetOutput(r: Long, surface: Surface?): Boolean
    @JvmStatic external fun nativeSetOffscreen(r: Long, w: Int, h: Int): Boolean
    @JvmStatic external fun nativeOutputSize(r: Long, out: IntArray)
    @JvmStatic external fun nativeBeginFrame(r: Long, red: Float, green: Float, blue: Float, alpha: Float)
    @JvmStatic external fun nativeDrawLayer(r: Long, tex: Int, oes: Boolean, params: FloatArray)
    @JvmStatic external fun nativeDrawLayerBlended(
        r: Long, tex: Int, oes: Boolean, params: FloatArray, mode: Int, opacity: Float, canvas: FloatArray
    )
    @JvmStatic external fun nativeLayerLooksBlack(r: Long, tex: Int, oes: Boolean, params: FloatArray): Boolean
    @JvmStatic external fun nativeBlendTexture(r: Long, tex: Int, mode: Int, opacity: Float, canvas: FloatArray)
    @JvmStatic external fun nativeReadPixels(r: Long, x: Int, y: Int, w: Int, h: Int, out: ByteArray): Boolean
    @JvmStatic external fun nativeUploadRgba(r: Long, tex: Int, w: Int, h: Int, data: ByteArray): Boolean
    @JvmStatic external fun nativeEndFrame(r: Long, ptsNs: Long): Boolean
    @JvmStatic external fun nativeBeginGroup(r: Long): Boolean
    @JvmStatic external fun nativeEndGroup(r: Long): Int
    @JvmStatic external fun nativeLoadMask(r: Long, slot: Int, path: String): Int
    @JvmStatic external fun nativeLoadImage(r: Long, fd: Int, maxSide: Int, out: IntArray): Int
    @JvmStatic external fun nativeApplyFx(
        r: Long, tex: Int, oes: Boolean, params: FloatArray,
        faceMask: Int, faceStrength: Float, cutMask: Int, flatten: Boolean, canvas: FloatArray,
        fxOps: FloatArray?, grainFrame: Int, maskOps: FloatArray?, bgAligned: Boolean
    ): Int
}

/** Texture matrix of a texture that is already upright (what [NativeRenderer.applyFx] returns). */
val IDENTITY_TEX_MATRIX = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

/** One thread that owns the OpenGL context. Create the renderer and use it ONLY through [call] / [post]. */
class GlThread(name: String = "am-gl") : AutoCloseable {
    private val thread = HandlerThread(name).also { it.start() }
    val handler = Handler(thread.looper)

    fun post(block: () -> Unit) {
        handler.post(block)
    }

    /** Runs [block] on the GL thread and waits for the result (rethrows its exception). */
    fun <T> call(block: () -> T): T {
        if (Thread.currentThread() === thread) return block()
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<T>>()
        handler.post {
            result.set(runCatching(block))
            latch.countDown()
        }
        latch.await()
        return result.get().getOrThrow()
    }

    override fun close() {
        thread.quitSafely()
    }
}

/** Parameters of one layer, see native LayerDraw. Canvas units are output pixels. */
class LayerParams {
    var offsetX = 0f
    var offsetY = 0f
    var scaleX = 1f
    var scaleY = 1f
    var rotationDeg = 0f
    var opacity = 1f

    /** Visible part of the upright picture (fractions 0..1, origin top-left); the cropped part is fitted into the canvas. */
    var cropX = 0f
    var cropY = 0f
    var cropW = 1f
    var cropH = 1f

    /**
     * 3D turn around the pivot with perspective (v121). Active when [perspD] > 0 and [rotX] / [rotY] are not both 0:
     * then [offsetX] / [offsetY] are ignored and [baseX] / [baseY] (position after depth, no pivot compensation) are used.
     * Pivot and base are canvas pixels from the canvas centre; [perspD] = camera distance in canvas pixels.
     */
    var rotX = 0f
    var rotY = 0f
    var pivotX = 0f
    var pivotY = 0f
    var baseX = 0f
    var baseY = 0f
    var perspD = 0f

    /**
     * Corner pin (v142, Mocha-style planar track): when not null the picture is drawn on this quad - 8 floats TL, TR, BR, BL,
     * canvas pixels from the TOP-LEFT of the canvas - with perspective; offset / scale / rotation / 3D are ignored.
     */
    var pin: FloatArray? = null

    /** Brightness factor of the colour (v143, Remove patch: follows the light of the video). 1 = as is; plain 2D pictures only. */
    var gain = 1f
}

/**
 * OpenGL ES 3 renderer for the preview AND the export (same drawing code, different output surface).
 * Everything must run on one thread: create it on your [GlThread] and call it from there only.
 */
class NativeRenderer private constructor(private var ptr: Long) : AutoCloseable {

    companion object {
        /** Null when the native library is missing or no GLES3 context could be created. */
        fun create(): NativeRenderer? {
            if (!DecoderPool.isAvailable) return null
            val p = RendererNative.nativeCreate()
            return if (p != 0L) NativeRenderer(p) else null
        }
    }

    internal fun nativeHandle(): Long = ptr

    private val params = FloatArray(46)

    /** Texture the decoder writes into (via SurfaceTexture(texId)): GL_TEXTURE_EXTERNAL_OES. */
    fun createExternalTexture(): Int = RendererNative.nativeCreateTexture(ptr, true)
    fun createTexture2d(): Int = RendererNative.nativeCreateTexture(ptr, false)

    /** A photo decoded in C++ and uploaded as a texture (see [loadPhoto]). */
    class Photo(val textureId: Int, val width: Int, val height: Int)

    /**
     * Decodes the picture behind [fd] in C++ (NDK AImageDecoder, Android 11+; EXIF orientation applied, long side
     * limited to [maxSide]) straight into a GL texture. Null = not possible here (older Android, unsupported
     * file): use a Bitmap + GLUtils instead. [fd] stays owned by the caller. GL thread only.
     */
    fun loadPhoto(fd: Int, maxSide: Int): Photo? {
        val size = IntArray(2)
        val tex = RendererNative.nativeLoadImage(ptr, fd, maxSide, size)
        return if (tex != 0) Photo(tex, size[0], size[1]) else null
    }
    fun deleteTexture(tex: Int) = RendererNative.nativeDeleteTexture(ptr, tex)

    /** Draw into [surface]: a preview SurfaceView / TextureView, or a MediaCodec input surface. */
    fun setOutput(surface: Surface?): Boolean = RendererNative.nativeSetOutput(ptr, surface)

    /** Draw into an invisible buffer (tests, thumbnails). */
    fun setOffscreen(width: Int, height: Int): Boolean = RendererNative.nativeSetOffscreen(ptr, width, height)

    fun outputSize(): Pair<Int, Int> {
        val v = IntArray(2)
        RendererNative.nativeOutputSize(ptr, v)
        return v[0] to v[1]
    }

    fun beginFrame(r: Float = 0f, g: Float = 0f, b: Float = 0f, a: Float = 1f) =
        RendererNative.nativeBeginFrame(ptr, r, g, b, a)

    /**
     * @param texMatrix SurfaceTexture.getTransformMatrix() result
     * @param contentW / contentH picture size AFTER rotation (for a 90/270 video: height x width)
     * @param contentRotation clip.rotation (degrees clockwise)
     */
    fun drawLayer(
        tex: Int, external: Boolean, texMatrix: FloatArray,
        contentW: Int, contentH: Int, contentRotation: Int, layer: LayerParams = LayerParams()
    ) {
        fillParams(texMatrix, contentW, contentH, contentRotation, layer)
        RendererNative.nativeDrawLayer(ptr, tex, external, params)
    }

    /**
     * Like [drawLayer], but combined with what is already on the frame by a blend mode.
     * @param mode BlendSpec.shaderId (0 = Normal, 1..4 mattes, 10.. colour blends)
     * @param layer layer.opacity is the blend opacity
     * @param canvasRgb canvas colour r, g, b (0..1), used by the matte modes
     */
    fun drawLayerBlended(
        tex: Int, external: Boolean, texMatrix: FloatArray,
        contentW: Int, contentH: Int, contentRotation: Int, layer: LayerParams,
        mode: Int, canvasRgb: FloatArray
    ) {
        fillParams(texMatrix, contentW, contentH, contentRotation, layer)
        RendererNative.nativeDrawLayerBlended(ptr, tex, external, params, mode, layer.opacity, canvasRgb)
    }

    /** True when the layer, drawn alone, covers pixels that are all pure black (a decoder without a picture). */
    fun layerLooksBlack(
        tex: Int, external: Boolean, texMatrix: FloatArray,
        contentW: Int, contentH: Int, contentRotation: Int, layer: LayerParams
    ): Boolean {
        fillParams(texMatrix, contentW, contentH, contentRotation, layer)
        return RendererNative.nativeLayerLooksBlack(ptr, tex, external, params)
    }

    /** Blend a canvas-sized premultiplied 2D picture (a Bitmap uploaded as texture, first row = top). */
    fun blendTexture(tex: Int, mode: Int, opacity: Float, canvasRgb: FloatArray) =
        RendererNative.nativeBlendTexture(ptr, tex, mode, opacity, canvasRgb)

    private fun fillParams(texMatrix: FloatArray, contentW: Int, contentH: Int, contentRotation: Int, layer: LayerParams) {
        System.arraycopy(texMatrix, 0, params, 0, 16)
        params[16] = contentW.toFloat()
        params[17] = contentH.toFloat()
        params[18] = contentRotation.toFloat()
        params[19] = layer.offsetX
        params[20] = layer.offsetY
        params[21] = layer.scaleX
        params[22] = layer.scaleY
        params[23] = layer.rotationDeg
        params[24] = layer.opacity
        params[25] = layer.cropX
        params[26] = layer.cropY
        params[27] = layer.cropW
        params[28] = layer.cropH
        params[29] = layer.rotX
        params[30] = layer.rotY
        params[31] = layer.pivotX
        params[32] = layer.pivotY
        params[33] = layer.baseX
        params[34] = layer.baseY
        params[35] = layer.perspD
        val pin = layer.pin
        if (pin != null && pin.size >= 8) {
            params[36] = 1f
            System.arraycopy(pin, 0, params, 37, 8)
        } else {
            params[36] = 0f
        }
        params[45] = layer.gain
    }

    /**
     * Mask picture (small grey JPEG of the cutout / face blur tool) as a GL texture, decoded in C++.
     * [slot] = any id per mask sequence (its texture is re-used while [path] stays the same).
     * Returns 0 when the file cannot be decoded.
     */
    fun loadMask(slot: Int, path: String): Int = RendererNative.nativeLoadMask(ptr, slot, path)

    /**
     * Face blur and / or cutout on the picture of a layer, done on the GPU. Returns a 2D texture with the
     * processed, upright picture (draw it with [IDENTITY_TEX_MATRIX] and rotation 0, same content size),
     * valid until the next call; 0 when it failed. A mask id of 0 = that effect is off.
     * [flatten] = lowest layer: the person is drawn straight onto the canvas colour [canvasRgb].
     * [fxOps] = the layer's effects (see [ClipFxLibrary.toNative]), run after face blur / cutout; [grainFrame] = film grain
     * pattern number ([ClipFxLibrary.grainFrame]) so the preview and the export grain are the same.
     * [maskOps] = the layer's masks at this time ([ClipMaskLibrary.toNative]), run last, after the effects.
     */
    fun applyFx(
        tex: Int, external: Boolean, texMatrix: FloatArray,
        contentW: Int, contentH: Int, contentRotation: Int,
        faceMask: Int, faceStrength: Float, cutMask: Int, flatten: Boolean, canvasRgb: FloatArray,
        fxOps: FloatArray? = null, grainFrame: Int = 0, maskOps: FloatArray? = null,
        crop: FloatArray? = null,
        /** The picture sits exactly where it is on the canvas (text / shape / sticker pictures): Copy Background may read what is behind it. */
        backdrop: Boolean = false
    ): Int {
        // [crop] = [x, y, w, h] fractions of the picture (origin top-left): the effects run on that part only and the
        // result has that part's pixel size (text / shape layers: the layer's box, see OverlayFx). null = the whole picture.
        val lp = LayerParams()
        if (crop != null && crop.size >= 4) {
            lp.cropX = crop[0]; lp.cropY = crop[1]; lp.cropW = crop[2]; lp.cropH = crop[3]
        }
        fillParams(texMatrix, contentW, contentH, contentRotation, lp)
        return RendererNative.nativeApplyFx(
            ptr, tex, external, params, faceMask, faceStrength, cutMask, flatten, canvasRgb, fxOps, grainFrame, maskOps, backdrop
        )
    }

    /** RGBA, rows bottom to top. Call before [endFrame]. */
    fun readPixels(x: Int, y: Int, w: Int, h: Int, out: ByteArray): Boolean =
        RendererNative.nativeReadPixels(ptr, x, y, w, h, out)

    /** [ptsNs] >= 0 stamps the frame (encoder input surface); -1 for the preview. */
    /** Fills the plain 2D texture [tex] (see [createTexture2d]) with w * h RGBA pixels, rows bottom-to-top (as [readPixels] gives them). */
    fun uploadRgba(tex: Int, w: Int, h: Int, data: ByteArray): Boolean = RendererNative.nativeUploadRgba(ptr, tex, w, h, data)

    fun endFrame(ptsNs: Long = -1L): Boolean = RendererNative.nativeEndFrame(ptr, ptsNs)

    /**
     * Group pass: a Group layer is drawn as ONE picture. After [beginGroup] (true) everything drawn goes into a transparent,
     * canvas-sized picture instead of the frame; [endGroup] puts the frame back and returns that picture as a 2D texture
     * (draw it with [IDENTITY_TEX_MATRIX], the canvas size as content size, rotation 0 and a default [LayerParams], or give it to
     * [applyFx] first for the group's effects and masks). The texture is valid until the next [beginGroup] at the same depth.
     * False = not possible (too deep / out of memory): draw the layers of the group straight into the frame.
     */
    fun beginGroup(): Boolean = RendererNative.nativeBeginGroup(ptr)
    fun endGroup(): Int = RendererNative.nativeEndGroup(ptr)

    override fun close() {
        val p = ptr
        ptr = 0L
        if (p != 0L) RendererNative.nativeDestroy(p)
    }
}
