package com.example.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Decodes an audio file once and keeps the loudest sample of every [BUCKET_MS] ms
 * (0..1, normalised) so the timeline can draw a waveform. Runs on a background thread.
 */
object AudioWaveform {

    const val BUCKET_MS = 50

    suspend fun compute(context: Context, uri: String, durationMs: Long): FloatArray? =
        withContext(Dispatchers.Default) {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(context, Uri.parse(uri), null)
                var trackIndex = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        trackIndex = i
                        format = f
                        break
                    }
                }
                if (trackIndex < 0 || format == null) return@withContext null
                extractor.selectTrack(trackIndex)

                val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext null
                val decoder = MediaCodec.createDecoderByType(mime)
                codec = decoder
                decoder.configure(format, null, null, 0)
                decoder.start()

                val peaks = FloatArray((durationMs / BUCKET_MS + 2).toInt().coerceAtLeast(2))
                var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var framesDone = 0L

                while (!outputDone) {
                    ensureActive()
                    if (!inputDone) {
                        val inIndex = decoder.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buf = decoder.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIndex >= 0 -> {
                            val out = decoder.getOutputBuffer(outIndex)
                            if (out != null && info.size > 0) {
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                val samples = out.order(ByteOrder.nativeOrder()).asShortBuffer()
                                val ch = channels.coerceAtLeast(1)
                                val frames = samples.remaining() / ch
                                for (f in 0 until frames) {
                                    var peak = 0
                                    for (c in 0 until ch) {
                                        val v = abs(samples.get(f * ch + c).toInt())
                                        if (v > peak) peak = v
                                    }
                                    val ms = (framesDone + f) * 1000L / sampleRate
                                    val b = (ms / BUCKET_MS).toInt().coerceAtMost(peaks.size - 1)
                                    val level = peak / 32768f
                                    if (level > peaks[b]) peaks[b] = level
                                }
                                framesDone += frames
                            }
                            decoder.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val nf = decoder.outputFormat
                            sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                }

                // Normalise so quiet songs still show a readable wave.
                var max = 0f
                for (p in peaks) if (p > max) max = p
                if (max > 0.01f) for (i in peaks.indices) peaks[i] = (peaks[i] / max).coerceIn(0f, 1f)
                peaks
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { extractor.release() }
            }
        }
}
