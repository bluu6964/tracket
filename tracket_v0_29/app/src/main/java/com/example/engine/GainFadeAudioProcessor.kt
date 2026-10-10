package com.example.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Export-side volume + fade in/out for one audio layer (16-bit PCM). Must run AFTER any speed
 * change so that its clock is the timeline clock (same as the preview). Other sample formats
 * pass through untouched.
 */
@UnstableApi
class GainFadeAudioProcessor(
    private val gain: Float,
    private val fadeInMs: Long,
    private val fadeOutMs: Long,
    private val durationMs: Long
) : BaseAudioProcessor() {

    private var framesDone = 0L
    private var sampleRate = 44_100
    private var channels = 2

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET   // inactive: audio passes through
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val frameSize = 2 * channels
        val frames = size / frameSize
        val out = replaceOutputBuffer(frames * frameSize)
        for (f in 0 until frames) {
            val ms = (framesDone + f) * 1000L / sampleRate
            val g = gain * fade(ms)
            for (c in 0 until channels) {
                val s = inputBuffer.getShort().toInt()
                out.putShort((s * g).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        framesDone += frames
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    private fun fade(ms: Long): Float {
        var g = 1f
        if (fadeInMs > 0L && ms < fadeInMs) g = minOf(g, ms / fadeInMs.toFloat())
        val left = durationMs - ms
        if (fadeOutMs > 0L && left < fadeOutMs) g = minOf(g, left.coerceAtLeast(0L) / fadeOutMs.toFloat())
        return g.coerceIn(0f, 1f)
    }
}
