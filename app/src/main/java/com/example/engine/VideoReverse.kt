package com.example.engine

import android.content.Context
import android.net.Uri
import com.example.model.AnimProp
import com.example.model.TimelineClip
import com.example.model.sourceOffsetMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Video Reverse: the layer (speed included) is pre-rendered backwards into a new video file that then plays at speed 1
 * (same idea as [SlowMoBuilder]; the result has no sound). Runs on the dedicated GL thread like the native exporter:
 * decoder -> GL -> encoder.
 */
object VideoReverse {
    class Result(val file: File, val durationMs: Long, val frames: Int)

    /** The user pressed Stop. */
    class Stopped : RuntimeException("Stopped")

    suspend fun render(
        context: Context,
        clip: TimelineClip,
        onProgress: (Float) -> Unit = {},
        shouldStop: () -> Boolean = { false }
    ): Result = withContext(Dispatchers.Default) {
        val uri = clip.uri ?: error("This layer has no video.")
        check(DecoderPool.isAvailable) { "The native engine is not available on this phone." }
        val outDir = File(context.filesDir, "reverse").apply { mkdirs() }
        val outFile = File(outDir, "rev_${clip.id}_${System.currentTimeMillis()}.mp4")
        try {
            GlThread("am-reverse").use { gl ->
                gl.call { run(context, clip, Uri.parse(uri), outFile, onProgress, shouldStop) }
            }
        } catch (t: Throwable) {
            outFile.delete()
            throw t
        }
    }

    private fun run(
        context: Context, clip: TimelineClip, uri: Uri, outFile: File,
        onProgress: (Float) -> Unit, shouldStop: () -> Boolean
    ): Result {
        val renderer = NativeRenderer.create() ?: error("Native GLES renderer is unavailable on this device.")
        val pool = DecoderPool(maxHardware = 2, maxSoftware = 2)
        val encoder = NativeExportEncoder.create()
        if (encoder == null) {
            pool.close()
            renderer.close()
            error("Native export needs Android 8.0 (API 26) or newer and the native engine.")
        }
        var toClose: VideoLayerSource? = null
        try {
            val source = VideoLayerSource.open(context, uri, renderer, pool) ?: error("Could not open the video.")
            toClose = source
            val srcFps = if (source.clip.fps > 1f) source.clip.fps else 30f
            val frameUs = (1_000_000f / srcFps).toLong().coerceAtLeast(1L)
            // Output size: the picture's own shape, long side at most 1080, even numbers.
            val cw = source.contentWidth.coerceAtLeast(2)
            val ch = source.contentHeight.coerceAtLeast(2)
            val k = min(1f, 1080f / max(cw, ch))
            val W = max(2, ((cw * k).roundToInt() / 2) * 2)
            val H = max(2, ((ch * k).roundToInt() / 2) * 2)
            val outFps = if (srcFps >= 50f) 60 else 30
            val bitrate = (W.toLong() * H * outFps / 6).coerceIn(2_000_000L, 24_000_000L).toInt()
            outFile.parentFile?.mkdirs()
            if (outFile.exists()) outFile.delete()
            require(encoder.start(renderer, outFile.absolutePath, W, H, outFps, bitrate)) {
                encoder.errorMessage().ifBlank { "Could not start the video encoder." }
            }

            fun seekPatient(us: Long): Int {
                var st = source.seek(max(0L, us))
                var tries = 0
                while (st == DecoderPool.BUSY && tries++ < 100) {
                    Thread.sleep(10)
                    st = source.seek(max(0L, us))
                }
                return st
            }

            val durationMs = clip.durationMs.coerceAtLeast(1L)
            val total = ((durationMs * outFps + 999L) / 1000L).coerceAtLeast(1L).toInt()
            val noRamp = clip.keyframes[AnimProp.SPEED].isNullOrEmpty()
            val stepMs = 1000.0 / outFps

            /** Source time (us) of the picture that the REVERSED layer shows at output frame [j]. */
            fun srcUsAt(j: Int): Long {
                // Frame j of the new file is the picture at (length - j) of the old layer; half a frame in, so it stays inside.
                val relMs = (durationMs - (j + 0.5) * stepMs).coerceAtLeast(0.0)
                val srcMs = if (noRamp) clip.trimStartMs + relMs * clip.speed
                else (clip.trimStartMs + clip.sourceOffsetMs(relMs.toLong())).toDouble()
                return (srcMs * 1000.0).toLong().coerceAtLeast(0L)
            }

            var curPts = Long.MIN_VALUE   // source time of the picture that is on the texture now
            var done = 0
            for (j in 0 until total) {
                if (shouldStop()) throw Stopped()
                val srcUs = srcUsAt(j)
                // The same real picture is still the right one: no new seek (slowed layers repeat frames).
                if (!(curPts != Long.MIN_VALUE && srcUs >= curPts && srcUs < curPts + frameUs)) {
                    var st = seekPatient(srcUs)
                    if (st < 0 && st != DecoderPool.END_OF_STREAM) error("Could not decode the video at ${srcUs / 1000} ms.")
                    var p = source.clip.ptsUs
                    if (p > srcUs && srcUs - frameUs >= 0L) {   // the nearest frame was the later one: take the one before
                        st = seekPatient(srcUs - frameUs)
                        if (st < 0 && st != DecoderPool.END_OF_STREAM) error("Could not decode the video at ${srcUs / 1000} ms.")
                        p = source.clip.ptsUs
                    }
                    curPts = p
                }
                renderer.beginFrame(0f, 0f, 0f, 1f)
                renderer.drawLayer(
                    source.textureId, true, source.texMatrix, source.contentWidth, source.contentHeight,
                    source.drawRotation, LayerParams()
                )
                require(encoder.submitFrame(j.toLong() * 1_000_000_000L / outFps)) {
                    encoder.errorMessage().ifBlank { "The video encoder failed on frame $j." }
                }
                done++
                if (done % 3 == 0 || done == total) onProgress(done.toFloat() / total)
            }
            require(encoder.finish()) { encoder.errorMessage().ifBlank { "Could not finish the new video." } }
            return Result(outFile, durationMs, done)
        } catch (t: Throwable) {
            runCatching { encoder.cancel() }
            outFile.delete()
            throw t
        } finally {
            runCatching { toClose?.close() }
            runCatching { encoder.close() }
            runCatching { pool.close() }
            runCatching { renderer.close() }
        }
    }
}
