package com.example.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.example.model.AnimProp
import com.example.model.TimelineClip
import com.example.model.sourceOffsetMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Audio Reverse: the part of the source that the layer plays is decoded, turned around (last sample first) and saved as a
 * new WAV file. The layer then plays that file from its start, so preview and export need nothing new.
 * The speed stays as it is (a constant speed is kept; a speed ramp is not supported here).
 */
object AudioReverse {
    class Result(val file: File, val durationMs: Long)

    /** True when the layer has a speed ramp (keyframes on speed): reversing it is not supported. */
    fun hasSpeedRamp(clip: TimelineClip): Boolean = !clip.keyframes[AnimProp.SPEED].isNullOrEmpty()

    suspend fun render(
        context: Context,
        clip: TimelineClip,
        onProgress: (Float) -> Unit = {},
        shouldStop: () -> Boolean = { false }
    ): Result = withContext(Dispatchers.Default) {
        val uri = clip.uri ?: throw IOException("This layer has no sound.")
        val outDir = File(context.filesDir, "reverse").apply { mkdirs() }
        val tmp = File(outDir, "rev_${clip.id}_${System.currentTimeMillis()}.pcm")
        val outFile = File(outDir, "rev_${clip.id}_${System.currentTimeMillis()}.wav")
        try {
            val startMs = clip.trimStartMs.coerceAtLeast(0L)
            val endMs = startMs + clip.sourceOffsetMs(clip.durationMs).coerceAtLeast(1L)
            val info = decodeToPcm(context, uri, startMs, endMs, tmp, onProgress, shouldStop)
            if (info.frames <= 0L) throw IOException("No sound could be read from this layer.")
            writeReversedWav(tmp, outFile, info, shouldStop)
            Result(outFile, info.frames * 1000L / info.rate)
        } catch (t: Throwable) {
            outFile.delete()
            throw t
        } finally {
            tmp.delete()
        }
    }

    private class PcmInfo(val rate: Int, val channels: Int, val frames: Long)

    /** Decodes [startMs, endMs) of the first audio track to [out]: raw PCM16 little endian, 1 or 2 channels, source rate. */
    private suspend fun decodeToPcm(
        context: Context, uri: String, startMs: Long, endMs: Long, out: File,
        onProgress: (Float) -> Unit, shouldStop: () -> Boolean
    ): PcmInfo {
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
            val startUs = startMs * 1000L
            val endUs = endMs * 1000L
            if (startUs > 0L) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var inCh = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var isFloat = false
            // The output keeps 1 channel (mono) or 2 (stereo; more than 2: the first two).
            val outCh = if (inCh >= 2) 2 else 1
            var frames = 0L
            val bufInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastReport = 0L

            BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { sink ->
                var chunk = ByteArray(4096)
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
                            inCh = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                            isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == 4 // ENCODING_PCM_FLOAT
                        }
                        outIndex >= 0 -> {
                            val buf = decoder.getOutputBuffer(outIndex)
                            if (buf != null && bufInfo.size > 0) {
                                buf.position(bufInfo.offset)
                                buf.limit(bufInfo.offset + bufInfo.size)
                                buf.order(ByteOrder.nativeOrder())
                                val bytesPerSample = if (isFloat) 4 else 2
                                val total = bufInfo.size / (bytesPerSample * inCh)
                                val bufStartUs = bufInfo.presentationTimeUs
                                val first = if (bufStartUs < startUs)
                                    (((startUs - bufStartUs) * rate) / 1_000_000L).toInt().coerceAtMost(total) else 0
                                val last = if (bufStartUs + total * 1_000_000L / rate > endUs)
                                    (((endUs - bufStartUs) * rate) / 1_000_000L).toInt().coerceIn(0, total) else total
                                if (first > 0) buf.position(buf.position() + first * inCh * bytesPerSample)
                                val need = (last - first).coerceAtLeast(0) * outCh * 2
                                if (chunk.size < need) chunk = ByteArray(need)
                                var w = 0
                                var f = first
                                while (f < last) {
                                    var lastS = 0
                                    for (c in 0 until inCh) {
                                        val s: Int = if (isFloat) {
                                            (buf.getFloat() * 32767f).toInt().coerceIn(-32768, 32767)
                                        } else {
                                            buf.getShort().toInt()
                                        }
                                        lastS = s
                                        if (c < outCh) {
                                            chunk[w++] = (s and 0xFF).toByte()
                                            chunk[w++] = ((s shr 8) and 0xFF).toByte()
                                        }
                                    }
                                    if (inCh < outCh) {   // the decoder gave mono for a stereo file: copy to both sides
                                        chunk[w++] = (lastS and 0xFF).toByte()
                                        chunk[w++] = ((lastS shr 8) and 0xFF).toByte()
                                    }
                                    f++
                                }
                                if (w > 0) sink.write(chunk, 0, w)
                                frames += (last - first).coerceAtLeast(0)
                                val now = System.nanoTime()
                                if (endUs > startUs && now - lastReport > 100_000_000L) {
                                    lastReport = now
                                    // Decoding is the first half of the work, writing the file the second.
                                    onProgress((((bufStartUs - startUs).toFloat() / (endUs - startUs)).coerceIn(0f, 1f)) * 0.5f)
                                }
                                if (bufStartUs >= endUs) outputDone = true
                            }
                            decoder.releaseOutputBuffer(outIndex, false)
                            if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            }
            return PcmInfo(rate, outCh, frames)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Writes [pcm] backwards (last frame first) as a 16-bit WAV file. */
    private fun writeReversedWav(pcm: File, out: File, info: PcmInfo, shouldStop: () -> Boolean) {
        val frameBytes = info.channels * 2
        val dataBytes = info.frames * frameBytes
        if (dataBytes > 0xFFFFFFFFL - 44L) throw IOException("The sound is too long to reverse.")
        BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { sink ->
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray(Charsets.US_ASCII))
            h.putInt((36L + dataBytes).toInt())
            h.put("WAVE".toByteArray(Charsets.US_ASCII))
            h.put("fmt ".toByteArray(Charsets.US_ASCII))
            h.putInt(16)
            h.putShort(1)                                   // PCM
            h.putShort(info.channels.toShort())
            h.putInt(info.rate)
            h.putInt(info.rate * frameBytes)                // byte rate
            h.putShort(frameBytes.toShort())                // block align
            h.putShort(16)                                  // bits per sample
            h.put("data".toByteArray(Charsets.US_ASCII))
            h.putInt(dataBytes.toInt())
            sink.write(h.array())

            val chunkFrames = 8192
            val buf = ByteArray(chunkFrames * frameBytes)
            val rev = ByteArray(chunkFrames * frameBytes)
            RandomAccessFile(pcm, "r").use { raf ->
                var remaining = info.frames
                while (remaining > 0L) {
                    if (shouldStop()) throw Stopped()
                    val n = minOf(remaining, chunkFrames.toLong()).toInt()
                    val startFrame = remaining - n
                    raf.seek(startFrame * frameBytes)
                    raf.readFully(buf, 0, n * frameBytes)
                    for (i in 0 until n) {
                        System.arraycopy(buf, (n - 1 - i) * frameBytes, rev, i * frameBytes, frameBytes)
                    }
                    sink.write(rev, 0, n * frameBytes)
                    remaining -= n
                }
            }
        }
    }

    /** The user pressed Stop. */
    class Stopped : RuntimeException("Stopped")
}
