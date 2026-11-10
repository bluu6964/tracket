package com.example.engine

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * One video layer: decoder-pool clip -> SurfaceTexture -> GL external texture. Create and use it on
 * the GL thread (the SurfaceTexture is bound to the renderer's GL context).
 *
 * `seek` / `next` do the whole hand-shake (decode, wait for the frame, updateTexImage), so after they
 * return [DecoderPool.OK] / [DecoderPool.SAME] the texture holds the requested frame.
 */
class VideoLayerSource private constructor(
    private val renderer: NativeRenderer,
    private val pool: DecoderPool,
    val clip: DecoderPool.Clip,
    val textureId: Int,
    private val surfaceTexture: SurfaceTexture,
    private val surface: Surface
) : AutoCloseable {

    /** Latest SurfaceTexture transform matrix, pass it to [NativeRenderer.drawLayer]. */
    val texMatrix = FloatArray(16)

    /** Picture size after the video's rotation metadata (what the layer really looks like). */
    val contentWidth: Int get() = if (clip.rotation % 180 == 0) clip.width else clip.height
    val contentHeight: Int get() = if (clip.rotation % 180 == 0) clip.height else clip.width

    /**
     * Rotation the renderer still has to apply to the picture (degrees clockwise). The file's rotation metadata
     * ([DecoderPool.Clip.rotation]) is handed to the codec with the track format, and on many devices the codec then
     * puts the rotation into the SurfaceTexture transform matrix itself - applying it a second time turns the picture
     * 90 degrees too far (seen on a phone: portrait video sideways in the C++ preview). So the matrix is looked at after
     * every new frame: when it already contains the turn, nothing more is applied (0); otherwise the metadata value.
     */
    var drawRotation: Int = clip.rotation
        private set

    private var rotationLogged = false

    private val frameReady = Semaphore(0)

    companion object {
        /**
         * The rotation (metadata value [rotation]) the renderer must add on top of SurfaceTexture matrix [m]. A plain
         * decoder matrix only flips v: x' = +-u, y' = -+v (m0 > 0, m5 < 0). A matrix that already holds the turn has
         * the axes swapped (90 / 270: the off-diagonal numbers m1 / m4 dominate) or x mirrored with y straight (180).
         */
        internal fun remainingRotation(rotation: Int, m: FloatArray): Int {
            if (rotation == 0) return 0
            val turned = when (rotation) {
                90, 270 -> Math.abs(m[1]) > Math.abs(m[0]) && Math.abs(m[4]) > Math.abs(m[5])
                180 -> m[0] < -0.5f && m[5] > 0.5f
                else -> false
            }
            return if (turned) 0 else rotation
        }

        private val callbackThread: Handler by lazy {
            val t = HandlerThread("am-frame-callback")
            t.start()
            Handler(t.looper)
        }

        /** GL thread only. Null if the file cannot be decoded. */
        fun open(
            context: Context, uri: Uri, renderer: NativeRenderer, pool: DecoderPool, preferSoftware: Boolean = false
        ): VideoLayerSource? {
            val tex = renderer.createExternalTexture()
            val st = SurfaceTexture(tex)
            val surface = Surface(st)
            val clip = pool.open(context, uri, surface, preferSoftware)
            if (clip == null) {
                surface.release()
                st.release()
                renderer.deleteTexture(tex)
                return null
            }
            val src = VideoLayerSource(renderer, pool, clip, tex, st, surface)
            st.setOnFrameAvailableListener({ src.frameReady.release() }, callbackThread)
            return src
        }
    }

    fun seek(timeUs: Long): Int {
        frameReady.drainPermits()
        val st = pool.seek(clip, timeUs)
        return finish(st)
    }

    fun next(): Int {
        frameReady.drainPermits()
        return finish(pool.next(clip))
    }

    private fun finish(st: Int): Int {
        if (st != DecoderPool.OK) return st
        if (!frameReady.tryAcquire(1, TimeUnit.SECONDS)) return DecoderPool.ERROR
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(texMatrix)
        drawRotation = remainingRotation(clip.rotation, texMatrix)
        if (!rotationLogged) {
            rotationLogged = true
            android.util.Log.i("VideoLayerSource", "rotation metadata ${clip.rotation}, matrix already turned: " +
                "${drawRotation != clip.rotation}, renderer applies $drawRotation (m0=${texMatrix[0]} m1=${texMatrix[1]} m4=${texMatrix[4]} m5=${texMatrix[5]})")
        }
        return DecoderPool.OK
    }

    override fun close() {
        pool.close(clip)
        surfaceTexture.setOnFrameAvailableListener(null)
        surface.release()
        surfaceTexture.release()
        renderer.deleteTexture(textureId)
    }
}
