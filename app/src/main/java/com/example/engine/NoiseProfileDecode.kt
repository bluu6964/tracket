package com.example.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.floor

/**
 * Noise-profile decode (build 195): decodes exactly the source window a layer
 * plays — [startMs, startMs + spanMs), the caller passes the layer's trim start
 * and its played source span — into MONO PCM floats at exactly
 * [TARGET_RATE] Hz (the shared mixer's rate), delivered in chunks to a sink as
 * they are decoded: the window is never held whole (a 10-minute span is
 * 28.8 M samples).
 *
 * The window discipline is build 193's [AudioBeatDecode]: the extractor seeks
 * to the window start and every decoded buffer is trimmed by its presentation
 * time, so sample 0 of the stream is the window's first sample — the frames
 * the profile is estimated from are the very samples the mixer's gate will
 * later process. Stereo is mixed down by averaging (the profile is a level
 * estimate; steady noise is near-identical in both channels) and any source
 * rate is converted to [TARGET_RATE] by streaming linear interpolation.
 */
object NoiseProfileDecode {

    /** The user pressed Stop / the caller went away. */
    class Stopped : RuntimeException("Stopped")

    /** The mixer's rate: the profile's bins are stated against it. */
    const val TARGET_RATE = 48000

    suspend fun decodeWindow(
        context: Context,
        uri: String,
        startMs: Long,
        spanMs: Long,
        shouldStop: () -> Boolean = { false },
        onChunk: (FloatArray) -> Unit
    ): Unit = withContext(Dispatchers.Default) {
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
            if (track < 0 || format == null) throw IOException("This layer has no sound.")
            extractor.selectTrack(track)
            val startUs = startMs.coerceAtLeast(0L) * 1000L
            val endUs = startUs + spanMs.coerceAtLeast(1L) * 1000L
            if (startUs > 0L) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var isFloat = false
            val sink = ResamplingSink(rate, onChunk)
            val bufInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (shouldStop()) throw Stopped()
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = decoder.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        val t = extractor.sampleTime
                        if (size < 0 || t >= endUs) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, t, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(bufInfo, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = decoder.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == 4 // ENCODING_PCM_FLOAT
                        sink.rateChanged(rate)
                    }
                    outIndex >= 0 -> {
                        val buf = decoder.getOutputBuffer(outIndex)
                        if (buf != null && bufInfo.size > 0) {
                            buf.position(bufInfo.offset)
                            buf.limit(bufInfo.offset + bufInfo.size)
                            buf.order(java.nio.ByteOrder.nativeOrder())
                            val bytesPerSample = if (isFloat) 4 else 2
                            val total = bufInfo.size / (bytesPerSample * channels)
                            val bufStartUs = bufInfo.presentationTimeUs
                            val first = if (bufStartUs < startUs)
                                (((startUs - bufStartUs) * rate) / 1_000_000L).toInt().coerceAtMost(total) else 0
                            val last = if (bufStartUs + total * 1_000_000L / rate > endUs)
                                (((endUs - bufStartUs) * rate) / 1_000_000L).toInt().coerceIn(0, total) else total
                            if (last > first) {
                                val frames = last - first
                                val mono = FloatArray(frames)
                                if (isFloat) {
                                    val fb = buf.asFloatBuffer()
                                    for (f in 0 until frames) {
                                        var sum = 0f
                                        val base = (first + f) * channels
                                        for (c in 0 until channels) sum += fb.get(base + c)
                                        mono[f] = sum / channels
                                    }
                                } else {
                                    val sb = buf.asShortBuffer()
                                    for (f in 0 until frames) {
                                        var sum = 0f
                                        val base = (first + f) * channels
                                        for (c in 0 until channels) sum += sb.get(base + c) / 32768f
                                        mono[f] = sum / channels
                                    }
                                }
                                sink.push(mono)
                            }
                            if (bufStartUs >= endUs) outputDone = true
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            sink.finish()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Converts the decoded mono stream to [TARGET_RATE] by linear interpolation
     * as it arrives (either direction — the ratio is just srcRate / TARGET_RATE),
     * handing the consumer a chunk whenever enough output has accumulated.
     */
    private class ResamplingSink(initialRate: Int, private val onChunk: (FloatArray) -> Unit) {
        private var rate = initialRate
        private var pending = FloatArray(0)
        private var pendingBase = 0L   // global input index of pending[0]
        private var totalIn = 0L
        private var nextOut = 0L
        private var out = FloatArray(1 shl 14)
        private var outSize = 0

        fun rateChanged(newRate: Int) {
            if (totalIn == 0L) rate = newRate
        }

        fun push(mono: FloatArray) {
            if (rate == TARGET_RATE) {
                deliver(mono)
                totalIn += mono.size
                nextOut += mono.size
                return
            }
            val merged = FloatArray(pending.size + mono.size)
            System.arraycopy(pending, 0, merged, 0, pending.size)
            System.arraycopy(mono, 0, merged, pending.size, mono.size)
            pending = merged
            totalIn += mono.size
            emit(final = false)
        }

        fun finish() {
            if (rate != TARGET_RATE) emit(final = true)
            if (outSize > 0) {
                onChunk(out.copyOf(outSize))
                outSize = 0
            }
        }

        private fun emit(final: Boolean) {
            val ratio = rate.toDouble() / TARGET_RATE
            while (true) {
                val pos = nextOut * ratio
                val i0 = floor(pos).toLong()
                if (i0 - pendingBase >= pending.size) break
                if (!final && i0 + 1 - pendingBase >= pending.size) break
                val a = pending[(i0 - pendingBase).toInt()]
                val b = if (i0 + 1 - pendingBase < pending.size) pending[(i0 + 1 - pendingBase).toInt()] else a
                addOut(a + (b - a) * (pos - i0).toFloat())
                nextOut++
            }
            val keepFrom = floor(nextOut * ratio).toLong() - pendingBase
            if (keepFrom > 0) {
                val drop = minOf(keepFrom, pending.size.toLong()).toInt()
                pending = pending.copyOfRange(drop, pending.size)
                pendingBase += drop
            }
            if (outSize >= 16384) {
                onChunk(out.copyOf(outSize))
                outSize = 0
            }
        }

        private fun deliver(mono: FloatArray) {
            var i = 0
            while (i < mono.size) {
                val n = minOf(16384 - outSize, mono.size - i)
                if (outSize + n > out.size) out = out.copyOf(maxOf(out.size * 2, outSize + n))
                System.arraycopy(mono, i, out, outSize, n)
                outSize += n
                i += n
                if (outSize >= 16384) {
                    onChunk(out.copyOf(outSize))
                    outSize = 0
                }
            }
        }

        private fun addOut(v: Float) {
            if (outSize == out.size) out = out.copyOf(out.size * 2)
            out[outSize++] = v
        }
    }
}
