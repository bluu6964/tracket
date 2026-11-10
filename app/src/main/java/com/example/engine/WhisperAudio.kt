package com.example.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteOrder

/**
 * Turns the sound of a video / audio file into what Whisper wants: 16 kHz, mono, 16-bit PCM.
 * Only the wanted part [startMs, endMs) of the source is decoded. The result goes to a temp file
 * (a long video would not fit in memory as floats); [readBlock] reads it back piece by piece.
 */
object WhisperAudio {

    const val SAMPLE_RATE = 16_000

    /**
     * Decodes [startMs, endMs) of the source's first audio track to [out] (raw PCM16, little endian).
     * Returns the number of samples written (at 16 kHz). [onProgress] gets 0..1.
     */
    suspend fun decode(
        context: Context,
        uri: String,
        startMs: Long,
        endMs: Long,
        out: File,
        onProgress: (Float) -> Unit
    ): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) throw IOException("This layer has no sound to caption")
            extractor.selectTrack(track)
            val startUs = startMs.coerceAtLeast(0L) * 1000L
            val endUs = endMs * 1000L
            if (startUs > 0L) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var isFloat = false

            val sink = PcmSink(out)
            var resampler = Resampler(rate, sink)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastReport = 0L

            sink.use {
                while (!outputDone) {
                    currentCoroutineContext().ensureActive()
                    if (!inputDone) {
                        val inIndex = decoder.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buf = decoder.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(buf, 0)
                            val t = extractor.sampleTime
                            if (size < 0 || (endUs > 0L && t >= endUs)) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, size, t, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = decoder.outputFormat
                            val newRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                            isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == 4 // ENCODING_PCM_FLOAT
                            if (newRate != rate) {
                                rate = newRate
                                resampler = Resampler(rate, sink)
                            }
                        }
                        outIndex >= 0 -> {
                            val buf = decoder.getOutputBuffer(outIndex)
                            if (buf != null && info.size > 0) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                buf.order(ByteOrder.nativeOrder())
                                val bytesPerSample = if (isFloat) 4 else 2
                                val frames = info.size / (bytesPerSample * channels)
                                val bufStartUs = info.presentationTimeUs
                                // skip what is before the wanted start, stop at the wanted end
                                val first = if (bufStartUs < startUs)
                                    (((startUs - bufStartUs) * rate) / 1_000_000L).toInt().coerceAtMost(frames) else 0
                                val last = if (endUs > 0L && bufStartUs + frames * 1_000_000L / rate > endUs)
                                    (((endUs - bufStartUs) * rate) / 1_000_000L).toInt().coerceIn(0, frames) else frames
                                if (first > 0) buf.position(buf.position() + first * channels * bytesPerSample)
                                var f = first
                                while (f < last) {
                                    var sum = 0f
                                    for (c in 0 until channels) {
                                        sum += if (isFloat) buf.getFloat() else buf.getShort() / 32768f
                                    }
                                    resampler.push(sum / channels)
                                    f++
                                }
                                val now = System.nanoTime()
                                if (endUs > startUs && now - lastReport > 100_000_000L) {
                                    lastReport = now
                                    onProgress(((bufStartUs - startUs).toFloat() / (endUs - startUs)).coerceIn(0f, 1f))
                                }
                                if (endUs > 0L && bufStartUs >= endUs) outputDone = true
                            }
                            decoder.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
                resampler.finish()
            }
            onProgress(1f)
            return sink.samples
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            extractor.release()
        }
    }

    /**
     * Reads [count] samples starting at sample [from] of a file made by [decode] as floats (-1..1).
     * Returns fewer at the end of the file.
     */
    fun readBlock(file: File, from: Long, count: Int): FloatArray {
        java.io.RandomAccessFile(file, "r").use { raf ->
            val total = raf.length() / 2
            val n = minOf(count.toLong(), (total - from).coerceAtLeast(0L)).toInt()
            val bytes = ByteArray(n * 2)
            raf.seek(from * 2)
            raf.readFully(bytes)
            val out = FloatArray(n)
            for (i in 0 until n) {
                val lo = bytes[2 * i].toInt() and 0xFF
                val hi = bytes[2 * i + 1].toInt()
                out[i] = ((hi shl 8) or lo).toShort() / 32768f
            }
            return out
        }
    }

    /** Writes 16-bit little endian samples to a file. */
    private class PcmSink(file: File) : AutoCloseable {
        private val out = BufferedOutputStream(FileOutputStream(file), 64 * 1024)
        var samples = 0L
            private set

        fun write(x: Float) {
            val v = (x * 32767f).toInt().coerceIn(-32768, 32767)
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            samples++
        }

        override fun close() = out.close()
    }

    /**
     * Mono float samples at [inRate] -> 16 kHz. Down-sampling averages the input samples that fall
     * into one output sample (a cheap low-pass filter); up-sampling (rare, 8 / 11 / 12 kHz sources)
     * interpolates linearly.
     */
    private class Resampler(private val inRate: Int, private val sink: PcmSink) {
        private var phase = 0L
        private var sum = 0f
        private var count = 0
        private var index = 0L
        private var prev = 0f
        private var outCount = 0L
        private val ratio = inRate.toDouble() / SAMPLE_RATE

        fun push(x: Float) {
            if (inRate >= SAMPLE_RATE) {
                sum += x
                count++
                phase += SAMPLE_RATE
                if (phase >= inRate) {
                    phase -= inRate
                    sink.write(sum / count)
                    sum = 0f
                    count = 0
                }
            } else {
                if (index == 0L) prev = x
                var pos = outCount * ratio
                while (pos <= index) {
                    val frac = (pos - (index - 1)).toFloat().coerceIn(0f, 1f)
                    sink.write(prev + (x - prev) * frac)
                    outCount++
                    pos = outCount * ratio
                }
                prev = x
                index++
            }
        }

        fun finish() {
            if (inRate >= SAMPLE_RATE && count > 0) {
                sink.write(sum / count)
                sum = 0f
                count = 0
            }
        }
    }
}
