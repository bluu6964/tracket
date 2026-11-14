package com.example.engine

import android.os.Build
import java.io.Closeable

/** JNI entry points for the native H.264 surface encoder / MP4 muxer. */
private object NativeExportNative {
    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeDestroy(session: Long)
    @JvmStatic external fun nativeStart(session: Long, renderer: Long, outputPath: String,
                                        width: Int, height: Int, fps: Int, bitrate: Int): Boolean
    @JvmStatic external fun nativeRenderSolidFrame(session: Long, r: Float, g: Float, b: Float,
                                                    a: Float, ptsNs: Long): Boolean
    @JvmStatic external fun nativeSubmitFrame(session: Long, ptsNs: Long): Boolean
    @JvmStatic external fun nativeFinish(session: Long): Boolean
    @JvmStatic external fun nativeCancel(session: Long)
    @JvmStatic external fun nativeError(session: Long): String
    @JvmStatic external fun nativeAddAudioClip(session: Long, fd: Int, length: Long, startUs: Long, durUs: Long,
                                               trimStartUs: Long, speed: Double, volume: Float,
                                               fadeInUs: Long, fadeOutUs: Long,
                                               duckRelUs: LongArray?, duckGains: FloatArray?,
                                               noiseProfile: FloatArray?, nrStrength: Float,
                                               speedTable: FloatArray?): Boolean
    @JvmStatic external fun nativeStartAudio(session: Long, totalUs: Long, bitrate: Int): Boolean
    @JvmStatic external fun nativePumpAudio(session: Long, upToUs: Long): Boolean
    @JvmStatic external fun nativeProfNs(id: Int): Long
}

/**
 * Experimental native export building block. Call every method on the same [GlThread] that owns
 * [NativeRenderer]. It can submit arbitrary frames composed by the renderer; it is not a replacement
 * for [VideoExporter] until all effects, overlays and audio mixing are ported and regression-tested.
 */
class NativeExportEncoder private constructor(private var session: Long) : Closeable {
    companion object {
        val isSupported: Boolean get() = Build.VERSION.SDK_INT >= 26 && DecoderPool.isAvailable

        /** Returns null if native engine is unavailable or the device is too old for NDK muxing. */
        fun create(): NativeExportEncoder? {
            if (!isSupported) return null
            val p = NativeExportNative.nativeCreate()
            return if (p != 0L) NativeExportEncoder(p) else null
        }
    }

    fun start(renderer: NativeRenderer, outputPath: String, width: Int, height: Int,
              fps: Int = 30, bitrate: Int = 12_000_000): Boolean {
        check(session != 0L) { "Encoder is closed" }
        require(width >= 2 && height >= 2 && fps in 1..120 && bitrate >= 64_000)
        return NativeExportNative.nativeStart(session, renderer.nativeHandle(), outputPath,
            width, height, fps, bitrate)
    }

    /**
     * One sound of the timeline (an audio layer or the sound of a video layer). Call for every clip
     * BEFORE [startAudio]. The native side duplicates [fd], so the caller may close it right away.
     * Times are on the project timeline / in the source file; fades use the clip's own clock.
     * [duckRelUs] / [duckGains] (build 194): the clip's duck curve on its own clock (microseconds
     * from its start + the gain there), same length; null = no ducking.
     * [noiseProfile] / [nrStrength] (build 195): the clip's per-bin noise profile
     * (com.example.model.NoiseReduction.BINS magnitudes) + gate strength; null / 0 = ungated.
     * [speedTable] (build 201, S-1): the clip's per-block SPEED-keyframe table
     * (com.example.model.SpeedRamp); null = constant [speed].
     */
    fun addAudioClip(
        fd: Int, length: Long, startMs: Long, durationMs: Long, trimStartMs: Long,
        speed: Float, volume: Float, fadeInMs: Long, fadeOutMs: Long,
        duckRelUs: LongArray? = null, duckGains: FloatArray? = null,
        noiseProfile: FloatArray? = null, nrStrength: Float = 0f,
        speedTable: FloatArray? = null
    ): Boolean {
        check(session != 0L) { "Encoder is closed" }
        return NativeExportNative.nativeAddAudioClip(
            session, fd, length, startMs * 1000L, durationMs * 1000L, trimStartMs * 1000L,
            speed.toDouble(), volume, fadeInMs * 1000L, fadeOutMs * 1000L, duckRelUs, duckGains,
            noiseProfile, nrStrength, speedTable
        )
    }

    /** Opens the AAC encoder. Call after [start] and after all [addAudioClip] calls, before the first frame. */
    fun startAudio(totalDurationMs: Long, bitrate: Int = 192_000): Boolean {
        check(session != 0L) { "Encoder is closed" }
        return NativeExportNative.nativeStartAudio(session, totalDurationMs * 1000L, bitrate)
    }

    /** Mixes + encodes the sound up to [upToUs] on the timeline. Call while rendering video. */
    fun pumpAudio(upToUs: Long): Boolean {
        check(session != 0L) { "Encoder is closed" }
        return NativeExportNative.nativePumpAudio(session, upToUs)
    }

    /** Wall-clock counters of the native audio path in ns: 0 = audio decode, 1 = audio encode (they only grow). */
    fun profNs(id: Int): Long = NativeExportNative.nativeProfNs(id)

    /** Emits one solid frame. ptsNs must be strictly increasing and in nanoseconds. */
    fun renderSolidFrame(r: Float, g: Float, b: Float, a: Float = 1f, ptsNs: Long): Boolean {
        check(session != 0L) { "Encoder is closed" }
        require(ptsNs >= 0L)
        return NativeExportNative.nativeRenderSolidFrame(session, r, g, b, a, ptsNs)
    }

    /** Submit the current renderer frame after beginFrame/drawLayer. PTS must be increasing. */
    fun submitFrame(ptsNs: Long): Boolean {
        check(session != 0L) { "Encoder is closed" }
        require(ptsNs >= 0L)
        return NativeExportNative.nativeSubmitFrame(session, ptsNs)
    }

    fun finish(): Boolean {
        check(session != 0L) { "Encoder is closed" }
        return NativeExportNative.nativeFinish(session)
    }

    fun errorMessage(): String = if (session == 0L) "Encoder is closed" else NativeExportNative.nativeError(session)
    fun cancel() { if (session != 0L) NativeExportNative.nativeCancel(session) }

    override fun close() {
        if (session != 0L) {
            NativeExportNative.nativeDestroy(session)
            session = 0L
        }
    }
}
