package com.example.engine

import android.content.Context
import android.net.Uri
import com.example.model.AnimProp
import com.example.model.TimelineClip
import com.example.model.sourceOffsetMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Raw JNI functions of libam_engine.so (cpp/engine/interp_jni.cpp, frame_interp.h). Use [SlowMoBuilder]. */
internal object InterpNative {
    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeDestroy(p: Long)
    @JvmStatic external fun nativeEstimate(p: Long, a: ByteArray, b: ByteArray, w: Int, h: Int): Boolean
    @JvmStatic external fun nativeRender(p: Long, a: ByteArray, b: ByteArray, w: Int, h: Int, f: Float, out: ByteArray): Boolean
}

/**
 * Smooth slow motion: where a layer is slowed down, the picture between two real frames is made new (motion between the
 * two frames is measured and the new picture is built from both, moved along that motion) instead of showing the same
 * frame again. The layer is pre-rendered, speed included (a constant speed or a speed ramp), into a new video file that
 * then plays at speed 1; the result has no sound.
 *
 * Runs on the dedicated GL thread like the native exporter: decoder -> GL -> pixels -> interpolation (C++) -> GL -> encoder.
 */
object SlowMoBuilder {
    class Result(val file: File, val durationMs: Long, val frames: Int)

    /** The user pressed Stop. */
    class Stopped : RuntimeException("Stopped")

    private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    /** True when the layer is slowed down somewhere (a constant speed below 1 or a speed ramp). */
    fun hasSlowPart(clip: TimelineClip): Boolean =
        clip.speed < 0.999f || !clip.keyframes[AnimProp.SPEED].isNullOrEmpty()

    suspend fun render(
        context: Context,
        clip: TimelineClip,
        onProgress: (Float) -> Unit = {},
        shouldStop: () -> Boolean = { false }
    ): Result = withContext(Dispatchers.Default) {
        val uri = clip.uri ?: error("This layer has no video.")
        check(DecoderPool.isAvailable) { "The native engine is not available on this phone." }
        val outDir = File(context.filesDir, "slowmo").apply { mkdirs() }
        val outFile = File(outDir, "slowmo_${clip.id}_${System.currentTimeMillis()}.mp4")
        val flowPtr = InterpNative.nativeCreate()
        try {
            GlThread("am-slowmo").use { gl ->
                gl.call { run(context, clip, Uri.parse(uri), outFile, flowPtr, onProgress, shouldStop) }
            }
        } catch (t: Throwable) {
            outFile.delete()
            throw t
        } finally {
            InterpNative.nativeDestroy(flowPtr)
        }
    }

    private fun run(
        context: Context, clip: TimelineClip, uri: Uri, outFile: File, flowPtr: Long,
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
        var tex2d = 0
        try {
            val source = VideoLayerSource.open(context, uri, renderer, pool) ?: error("Could not open the video.")
            toClose = source
            val srcFps = if (source.clip.fps > 1f) source.clip.fps else 30f
            val frameUs = (1_000_000f / srcFps).toLong().coerceAtLeast(1L)
            // Output size: the picture's own shape, long side at most 1080 (the pictures go through the CPU), even numbers.
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
            tex2d = renderer.createTexture2d()

            val bytes = W * H * 4
            var bufA = ByteArray(bytes)
            var bufB = ByteArray(bytes)
            val bufO = ByteArray(bytes)
            var haveA = false
            var tA = 0L
            var tB = 0L
            var flowReady = false

            fun capture(dst: ByteArray): Boolean {
                renderer.beginFrame(0f, 0f, 0f, 1f)
                renderer.drawLayer(
                    source.textureId, true, source.texMatrix, source.contentWidth, source.contentHeight,
                    source.drawRotation, LayerParams()
                )
                return renderer.readPixels(0, 0, W, H, dst)
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

            /** Makes [tA, tB) the pair of real frames around [srcUs]. */
            fun ensurePair(srcUs: Long): Boolean {
                if (haveA && srcUs >= tA && srcUs < tB) return true
                var st = seekPatient(srcUs)
                if (st < 0 && st != DecoderPool.END_OF_STREAM) return false
                var p = source.clip.ptsUs
                if (p > srcUs && srcUs - frameUs >= 0L) {   // the nearest frame was the later one: take the one before
                    st = seekPatient(srcUs - frameUs)
                    if (st < 0 && st != DecoderPool.END_OF_STREAM) return false
                    p = source.clip.ptsUs
                }
                if (haveA && abs(p - tB) < frameUs / 3) {   // moving on by one frame: the old B is the new A
                    val t = bufA; bufA = bufB; bufB = t
                } else {
                    if (!capture(bufA)) return false
                }
                tA = p
                haveA = true
                flowReady = false
                val nx = source.next()
                if (nx == DecoderPool.OK || nx == DecoderPool.SAME) {
                    val pb = source.clip.ptsUs
                    if (pb > tA && capture(bufB)) {
                        tB = pb
                        return true
                    }
                }
                // end of the video (or no next frame): the last frame holds
                System.arraycopy(bufA, 0, bufB, 0, bytes)
                tB = tA + frameUs
                return true
            }

            val relEndMs = clip.durationMs.coerceAtLeast(1L)
            val total = ((relEndMs * outFps + 999L) / 1000L).coerceAtLeast(1L).toInt()
            val slowMs = clip.keyframes[AnimProp.SPEED].isNullOrEmpty()
            fun srcUsAt(j: Int): Long {
                val relMs = j * 1000.0 / outFps
                val srcMs = if (slowMs) clip.trimStartMs + relMs * clip.speed
                else (clip.trimStartMs + clip.sourceOffsetMs(relMs.toLong())).toDouble()
                return (srcMs * 1000.0).toLong()
            }

            var done = 0
            for (j in 0 until total) {
                if (shouldStop()) throw Stopped()
                val srcUs = srcUsAt(j)
                val stepUs = abs(srcUsAt(min(j + 1, total - 1)) - srcUs)
                if (!ensurePair(srcUs)) error("Could not decode the video at ${srcUs / 1000} ms.")
                var f = ((srcUs - tA).toFloat() / (tB - tA).coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                if (stepUs >= frameUs * 0.95f) f = if (f < 0.5f) 0f else 1f   // not slowed here: a real frame, nothing to invent
                if (f > 0.001f && f < 0.999f) {
                    if (!flowReady) flowReady = InterpNative.nativeEstimate(flowPtr, bufA, bufB, W, H)
                    if (!flowReady || !InterpNative.nativeRender(flowPtr, bufA, bufB, W, H, f, bufO)) {
                        System.arraycopy(if (f < 0.5f) bufA else bufB, 0, bufO, 0, bytes)   // no motion found: the nearer real frame
                    }
                } else {
                    System.arraycopy(if (f < 0.5f) bufA else bufB, 0, bufO, 0, bytes)
                }
                require(renderer.uploadRgba(tex2d, W, H, bufO)) { "Could not upload the new frame." }
                renderer.beginFrame(0f, 0f, 0f, 1f)
                renderer.drawLayer(tex2d, false, IDENTITY, W, H, 0, LayerParams())
                require(encoder.submitFrame(j.toLong() * 1_000_000_000L / outFps)) {
                    encoder.errorMessage().ifBlank { "The video encoder failed on frame $j." }
                }
                done++
                if (done % 3 == 0 || done == total) onProgress(done.toFloat() / total)
            }
            require(encoder.finish()) { encoder.errorMessage().ifBlank { "Could not finish the new video." } }
            return Result(outFile, relEndMs, done)
        } catch (t: Throwable) {
            runCatching { encoder.cancel() }
            outFile.delete()
            throw t
        } finally {
            if (tex2d != 0) runCatching { renderer.deleteTexture(tex2d) }
            runCatching { toClose?.close() }
            runCatching { encoder.close() }
            runCatching { pool.close() }
            runCatching { renderer.close() }
        }
    }
}
