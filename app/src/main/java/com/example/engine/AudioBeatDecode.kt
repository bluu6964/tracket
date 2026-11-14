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
 * Beat-detection decode (build 193): decodes exactly the source window an
 * audio layer plays — [startMs, startMs + spanMs), the caller passes the
 * layer's trim start and its played source span — into MONO PCM floats at
 * most [ANALYSIS_RATE] Hz, ready for [com.example.model.BeatDetect].
 *
 * The window discipline is AudioReverse's: the extractor seeks to the window
 * start and every decoded buffer is trimmed by its presentation time, so
 * sample 0 of the result is the window's first sample (a beat found at
 * window time t is heard at source time startMs + t — no seek slop). Stereo
 * is mixed down by averaging (what an onset sounds like in the room), and a
 * higher source rate is reduced by linear interpolation while decoding, so
 * the analysis array never holds the full-rate signal.
 */
object AudioBeatDecode {

    /** Decoded mono PCM + the rate it is at (the source rate when already <= [ANALYSIS_RATE]). */
    class Pcm(val samples: FloatArray, val sampleRate: Int)

    /** The user pressed Stop. */
    class Stopped : RuntimeException("Stopped")

    /** Onset analysis rate: flux needs the drum band, not the full 44.1 kHz. */
    const val ANALYSIS_RATE = 16000

    /**
     * The longest played span one detection run will decode (10 minutes of
     * music is 9.6 M analysis samples; beyond this the honest answer is a
     * message, not an out-of-memory).
     */
    const val MAX_SPAN_MS = 600_000L

    suspend fun decodeWindow(
        context: Context,
        uri: String,
        startMs: Long,
        spanMs: Long,
        onProgress: (Float) -> Unit = {},
        shouldStop: () -> Boolean = { false }
    ): Pcm = withContext(Dispatchers.Default) {
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
            val sink = MonoSink(rate)
            val bufInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastReport = 0L

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
                            val now = System.nanoTime()
                            if (now - lastReport > 100_000_000L) {
                                lastReport = now
                                onProgress(
                                    ((bufStartUs - startUs).toFloat() / (endUs - startUs)).coerceIn(0f, 1f)
                                )
                            }
                            if (bufStartUs >= endUs) outputDone = true
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            val pcm = sink.finish()
            if (pcm.samples.isEmpty()) throw IOException("No sound could be read from this layer.")
            pcm
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Collects mono samples, reducing them to [ANALYSIS_RATE] by linear
     * interpolation as they arrive when the source rate is higher (the
     * full-rate signal is never held whole). At or under the analysis rate
     * the samples pass through untouched and keep their own rate.
     */
    private class MonoSink(initialRate: Int) {
        private var rate = initialRate
        private var bag = FloatBag()
        // Resampler state (only used when rate > ANALYSIS_RATE).
        private var pending = FloatArray(0)
        private var pendingBase = 0L   // global input index of pending[0]
        private var totalIn = 0L
        private var nextOut = 0L

        fun rateChanged(newRate: Int) {
            if (totalIn == 0L && bag.size == 0) rate = newRate
        }

        fun push(mono: FloatArray) {
            if (rate <= ANALYSIS_RATE) {
                bag.add(mono)
                return
            }
            val merged = FloatArray(pending.size + mono.size)
            System.arraycopy(pending, 0, merged, 0, pending.size)
            System.arraycopy(mono, 0, merged, pending.size, mono.size)
            pending = merged
            totalIn += mono.size
            emit(final = false)
        }

        fun finish(): Pcm {
            if (rate <= ANALYSIS_RATE) return Pcm(bag.toArray(), rate.coerceAtLeast(1))
            emit(final = true)
            return Pcm(bag.toArray(), ANALYSIS_RATE)
        }

        private fun emit(final: Boolean) {
            val ratio = rate.toDouble() / ANALYSIS_RATE
            while (true) {
                val pos = nextOut * ratio
                val i0 = floor(pos).toLong()
                // Need input i0 (and i0 + 1 unless finishing on the very last sample).
                if (i0 - pendingBase >= pending.size) break
                if (!final && i0 + 1 - pendingBase >= pending.size) break
                val a = pending[(i0 - pendingBase).toInt()]
                val b = if (i0 + 1 - pendingBase < pending.size) pending[(i0 + 1 - pendingBase).toInt()] else a
                bag.add(a + (b - a) * (pos - i0).toFloat())
                nextOut++
            }
            // Drop the inputs no future output can touch (keep the bracketing one).
            val keepFrom = floor(nextOut * ratio).toLong() - pendingBase
            if (keepFrom > 0) {
                val drop = minOf(keepFrom, pending.size.toLong()).toInt()
                pending = pending.copyOfRange(drop, pending.size)
                pendingBase += drop
            }
        }
    }

    /** A growable FloatArray. */
    private class FloatBag {
        private var arr = FloatArray(1 shl 15)
        var size = 0
            private set

        fun add(v: Float) {
            if (size == arr.size) arr = arr.copyOf(arr.size * 2)
            arr[size++] = v
        }

        fun add(values: FloatArray) {
            while (size + values.size > arr.size) arr = arr.copyOf(arr.size * 2)
            System.arraycopy(values, 0, arr, size, values.size)
            size += values.size
        }

        fun toArray(): FloatArray = arr.copyOf(size)
    }
}
