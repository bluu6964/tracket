package com.example.engine

import android.content.Context
import android.net.Uri
import android.view.Surface

/** Raw JNI functions of libam_engine.so (see app/src/main/cpp/engine). Use [DecoderPool] instead. */
internal object DecoderPoolNative {
    @JvmStatic external fun nativeCreate(maxHw: Int, maxSw: Int): Long
    @JvmStatic external fun nativeDestroy(pool: Long)
    @JvmStatic external fun nativeOpen(
        pool: Long, fd: Int, offset: Long, length: Long, surface: Surface?, info: LongArray,
        preferSoftware: Boolean
    ): Int
    @JvmStatic external fun nativeClose(pool: Long, id: Int)
    @JvmStatic external fun nativeSeek(pool: Long, id: Int, targetUs: Long, out: LongArray): Int
    @JvmStatic external fun nativeNext(pool: Long, id: Int, out: LongArray): Int
    @JvmStatic external fun nativeSetMaxHw(pool: Long, n: Int)
    @JvmStatic external fun nativeStats(pool: Long, out: IntArray)
}

/**
 * Pool of video decoders for many layers: at most [maxHardware] hardware decoders are alive at the
 * same time (least recently used one is released and re-created on demand), with automatic software
 * fallback. All calls block - use a render / worker thread, never the UI thread.
 *
 * Frame hand-shake with a SurfaceTexture: after [seek] / [next] returned [OK] the frame was queued
 * to the surface; wait for onFrameAvailable, call updateTexImage(), only then ask for the next one.
 * [VideoLayerSource] does exactly this.
 */
class DecoderPool(maxHardware: Int = 3, maxSoftware: Int = 2) : AutoCloseable {

    companion object {
        const val OK = 0          // a NEW frame was queued to the surface: wait for onFrameAvailable
        const val SAME = 1        // that frame is already on the surface: nothing to wait for
        const val END_OF_STREAM = -1
        const val BUSY = -2   // all decoder slots in use by other clips: retry in a few ms
        const val ERROR = -3

        /** False when libam_engine.so is not part of the build / cannot be loaded. */
        val isAvailable: Boolean by lazy {
            try {
                System.loadLibrary("am_engine")
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    class Clip internal constructor(
        internal val id: Int,
        val width: Int,
        val height: Int,
        /** Degrees the renderer has to rotate the decoded picture. */
        val rotation: Int,
        val durationUs: Long,
        val fps: Float
    ) {
        /** Timestamp of the frame the last successful [seek] / [next] put on the surface. */
        @Volatile var ptsUs: Long = 0
            internal set
        internal val out = LongArray(1)
    }

    data class Stats(
        val activeHw: Int, val activeSw: Int, val maxHw: Int, val maxSw: Int,
        val opened: Int, val evictions: Int, val fallbacks: Int, val errors: Int
    )

    private var ptr: Long = if (isAvailable) DecoderPoolNative.nativeCreate(maxHardware, maxSoftware) else 0L

    /** Opens a video; its frames go to [surface] (null = decode only). Null result = unreadable. */
    fun open(context: Context, uri: Uri, surface: Surface?, preferSoftware: Boolean = false): Clip? {
        if (ptr == 0L) return null
        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (_: Exception) {
            null
        } ?: return null
        pfd.use {
            val info = LongArray(5)
            val id = DecoderPoolNative.nativeOpen(ptr, it.fd, 0L, it.statSize, surface, info, preferSoftware)
            if (id <= 0) return null
            return Clip(id, info[0].toInt(), info[1].toInt(), info[2].toInt(), info[3], info[4] / 1000f)
        }
    }

    /** Shows the frame nearest to [targetUs] (exact seek). Returns OK / SAME / END_OF_STREAM / BUSY / ERROR. */
    fun seek(clip: Clip, targetUs: Long): Int {
        val st = DecoderPoolNative.nativeSeek(ptr, clip.id, targetUs, clip.out)
        if (st >= 0) clip.ptsUs = clip.out[0]
        return st
    }

    /** Shows the next frame in order (export / playback). */
    fun next(clip: Clip): Int {
        val st = DecoderPoolNative.nativeNext(ptr, clip.id, clip.out)
        if (st >= 0) clip.ptsUs = clip.out[0]
        return st
    }

    fun close(clip: Clip) {
        if (ptr != 0L) DecoderPoolNative.nativeClose(ptr, clip.id)
    }

    fun setMaxHardware(n: Int) {
        if (ptr != 0L) DecoderPoolNative.nativeSetMaxHw(ptr, n)
    }

    fun stats(): Stats {
        val v = IntArray(8)
        if (ptr != 0L) DecoderPoolNative.nativeStats(ptr, v)
        return Stats(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7])
    }

    override fun close() {
        val p = ptr
        ptr = 0L
        if (p != 0L) DecoderPoolNative.nativeDestroy(p)
    }
}
