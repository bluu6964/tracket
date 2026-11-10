package com.example.engine

import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.TimelineTrack

/** JNI of cpp/engine/export_plan.cpp: frame count, video bitrate and the choice of clips that carry sound. */
internal object ExportPlanNative {
    @JvmStatic external fun nativeTotalFrames(durationMs: Long, fps: Int): Int
    @JvmStatic external fun nativeBitrate(w: Int, h: Int, fps: Int, customKbps: Int, quality: String): Int
    @JvmStatic external fun nativeAudioPick(
        flags: IntArray, nums: LongArray, vols: FloatArray, videoEndMs: Long,
        outIdx: IntArray, outVol: FloatArray, outFadeIn: LongArray, outFadeOut: LongArray
    ): Int

    // MUST match enum AudioClipFlags in export_plan.h.
    private const val F_VISIBLE = 1
    private const val F_HAS_URI = 2
    private const val F_CLIP_MUTED = 4
    private const val F_TRACK_MUTED = 8
    private const val F_IS_IMAGE = 16
    private const val F_TYPE_AUDIO = 1 shl 5
    private const val F_TYPE_VIDEO = 2 shl 5

    fun totalFrames(durationMs: Long, fps: Int): Int = nativeTotalFrames(durationMs, fps)

    fun bitrate(w: Int, h: Int, fps: Int, customKbps: Int, quality: String): Int =
        nativeBitrate(w, h, fps, customKbps, quality)

    /** A clip chosen for the mix, with the volume and fades to use. */
    class AudioChoice(val clip: TimelineClip, val volume: Float, val fadeInMs: Long, val fadeOutMs: Long)

    /** Clips of all lanes (lane order, then clip order) that go into the mix. */
    fun pickAudio(tracks: List<TimelineTrack>, videoEndMs: Long): List<AudioChoice> {
        val clips = ArrayList<TimelineClip>()
        val flags = ArrayList<Int>()
        for (track in tracks) for (c in track.clips) {
            clips.add(c)
            var f = 0
            if (c.isVisible) f = f or F_VISIBLE
            if (c.uri != null) f = f or F_HAS_URI
            if (c.isMuted) f = f or F_CLIP_MUTED
            if (track.isMuted) f = f or F_TRACK_MUTED
            if (c.isImage) f = f or F_IS_IMAGE
            f = f or when (c.type) {
                LayerType.AUDIO -> F_TYPE_AUDIO
                LayerType.VIDEO -> F_TYPE_VIDEO
                else -> 0
            }
            flags.add(f)
        }
        val n = clips.size
        if (n == 0) return emptyList()
        val nums = LongArray(n * 4)
        val vols = FloatArray(n)
        for (i in 0 until n) {
            val c = clips[i]
            nums[i * 4] = c.startMs
            nums[i * 4 + 1] = c.durationMs
            nums[i * 4 + 2] = c.fadeInMs
            nums[i * 4 + 3] = c.fadeOutMs
            vols[i] = c.volume
        }
        val outIdx = IntArray(n)
        val outVol = FloatArray(n)
        val outIn = LongArray(n)
        val outOut = LongArray(n)
        val m = nativeAudioPick(flags.toIntArray(), nums, vols, videoEndMs, outIdx, outVol, outIn, outOut)
        return List(m) { AudioChoice(clips[outIdx[it]], outVol[it], outIn[it], outOut[it]) }
    }
}
