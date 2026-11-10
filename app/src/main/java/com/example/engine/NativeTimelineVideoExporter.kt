package com.example.engine

import android.content.Context
import android.net.Uri
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.TimelineTrack
import com.example.model.sourceOffsetMs
import java.io.File

/**
 * Experimental native timeline renderer for video-only projects. Frames are decoded by the NDK
 * decoder pool, composited by GLES, and encoded/muxed by the native MediaCodec path.
 *
 * This intentionally rejects projects containing audio, still images, text/shapes/stickers, or
 * advanced effects rather than silently producing an output that looks unlike VideoExporter.
 * Use VideoExporter (-> NativeProjectExporter) for the real export.
 *
 * Run from a worker thread. All decoder/GL calls are marshalled through the dedicated GlThread.
 */
class NativeTimelineVideoExporter(private val context: Context) {
    data class Result(val outputFile: File, val durationMs: Long, val frameCount: Int)

    fun exportVideoOnly(
        tracks: List<TimelineTrack>,
        outputFile: File,
        width: Int,
        height: Int,
        fps: Int = 30,
        bitrate: Int = 12_000_000,
        backgroundArgb: Int = android.graphics.Color.BLACK,
        onProgress: (Float) -> Unit = {}
    ): Result {
        require(width >= 2 && height >= 2 && fps in 1..60)
        val all = tracks.flatMap { it.clips }.filter { it.isVisible }
        require(all.isNotEmpty()) { "Nothing to export: add a visible video layer." }
        require(all.all { it.type == LayerType.VIDEO && !it.isImage && it.uri != null }) {
            "Native export currently supports video-only projects. Use the standard exporter for audio, images, text, shapes, stickers, or effects."
        }
        val clips = tracks.asReversed().flatMap { it.clips }
            .filter { it.isVisible && it.type == LayerType.VIDEO && it.uri != null }
        val durationMs = clips.maxOfOrNull { it.endMs }?.coerceAtLeast(1L) ?: 1L
        val totalFrames = ((durationMs * fps + 999L) / 1000L).coerceAtLeast(1L).toInt()
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        return GlThread("am-native-export").use { gl ->
            gl.call {
                val renderer = NativeRenderer.create()
                    ?: error("Native GLES renderer is unavailable on this device.")
                val pool = DecoderPool(maxHardware = PoolSizing.hw(clips), maxSoftware = 3)
                val encoder = NativeExportEncoder.create()
                if (encoder == null) {
                    pool.close()
                    renderer.close()
                    error("Native export requires Android 8.0 (API 26) or newer and the native engine.")
                }
                val sources = linkedMapOf<String, VideoLayerSource>()
                try {
                    require(encoder.start(renderer, outputFile.absolutePath, width, height, fps, bitrate)) {
                        encoder.errorMessage().ifBlank { "Could not start native H.264 export." }
                    }
                    clips.distinctBy { it.id }.forEach { clip ->
                        val src = VideoLayerSource.open(context, Uri.parse(clip.uri), renderer, pool)
                            ?: error("Could not open video layer '${clip.name}'.")
                        sources[clip.id] = src
                    }

                    val bgA = ((backgroundArgb ushr 24) and 0xff) / 255f
                    val bgR = ((backgroundArgb ushr 16) and 0xff) / 255f
                    val bgG = ((backgroundArgb ushr 8) and 0xff) / 255f
                    val bgB = (backgroundArgb and 0xff) / 255f
                    var completed = 0
                    for (frame in 0 until totalFrames) {
                        val timeMs = frame * 1000L / fps
                        renderer.beginFrame(bgR, bgG, bgB, bgA)
                        for (clip in clips) {
                            if (timeMs < clip.startMs || timeMs >= clip.endMs) continue
                            val source = sources[clip.id] ?: continue
                            val relMs = timeMs - clip.startMs
                            val sourceMs = clip.trimStartMs + clip.sourceOffsetMs(relMs)
                            val status = source.seek(sourceMs * 1000L)
                            if (status == DecoderPool.BUSY) error("Native decoder pool is busy while exporting '${clip.name}'.")
                            if (status == DecoderPool.ERROR) error("Could not decode '${clip.name}' at ${sourceMs} ms.")
                            if (status < 0) continue

                            val p = LayerParams().apply {
                                offsetX = clip.transformX
                                offsetY = clip.transformY
                                val scale = (clip.scalePercent / 100f).coerceIn(0.01f, 20f)
                                scaleX = scale * if (clip.isFlippedH) -1f else 1f
                                scaleY = scale * if (clip.isFlippedV) -1f else 1f
                                rotationDeg = clip.rotationDeg
                                opacity = (clip.opacity / 100f).coerceIn(0f, 1f)
                            }
                            source.texMatrix.let { matrix ->
                                renderer.drawLayer(
                                    source.textureId, true, matrix,
                                    source.contentWidth, source.contentHeight, source.drawRotation, p
                                )
                            }
                        }
                        val ptsNs = frame.toLong() * 1_000_000_000L / fps
                        require(encoder.submitFrame(ptsNs)) {
                            encoder.errorMessage().ifBlank { "Native encoder failed on frame $frame." }
                        }
                        completed++
                        if (completed % maxOf(1, fps / 2) == 0 || completed == totalFrames) {
                            onProgress(completed.toFloat() / totalFrames)
                        }
                    }
                    require(encoder.finish()) {
                        encoder.errorMessage().ifBlank { "Could not finalize native MP4." }
                    }
                    Result(outputFile, durationMs, completed)
                } catch (t: Throwable) {
                    encoder.cancel()
                    outputFile.delete()
                    throw t
                } finally {
                    sources.values.forEach { runCatching { it.close() } }
                    encoder.close()
                    pool.close()
                    renderer.close()
                }
            }
        }
    }
}
