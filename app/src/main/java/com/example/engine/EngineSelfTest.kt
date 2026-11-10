package com.example.engine

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log

/**
 * On-device check of the native engine (decoder pool -> SurfaceTexture -> OpenGL -> pixels).
 * Call from a background thread, e.g. a debug button:
 *
 *     Thread { Log.i("AmSelfTest", EngineSelfTest.run(context, videoUri)) }.start()
 *
 * Test 1: one clip drawn at 3 times. Test 2: the same file opened 6 times (more layers than hardware
 * decoders) and all 6 drawn into one frame - this is the situation that crashed the Media3 export.
 */
object EngineSelfTest {

    private class Stats(val nonBlack: Float, val meanLuma: Float, val center: String)

    private fun analyse(buf: ByteArray, w: Int, h: Int): Stats {
        var nb = 0
        var luma = 0L
        for (i in 0 until w * h) {
            val r = buf[i * 4].toInt() and 0xFF
            val g = buf[i * 4 + 1].toInt() and 0xFF
            val b = buf[i * 4 + 2].toInt() and 0xFF
            if (r > 8 || g > 8 || b > 8) nb++
            luma += (r * 299 + g * 587 + b * 114) / 1000
        }
        val c = ((h / 2) * w + w / 2) * 4
        val center = "rgb(${buf[c].toInt() and 0xFF},${buf[c + 1].toInt() and 0xFF},${buf[c + 2].toInt() and 0xFF})"
        return Stats(nb.toFloat() / (w * h), luma.toFloat() / (w * h), center)
    }

    fun run(context: Context, uri: Uri): String {
        val out = StringBuilder()
        fun log(s: String) {
            out.appendLine(s)
            Log.i("AmSelfTest", s)
        }
        if (!DecoderPool.isAvailable) return "FAIL: libam_engine.so could not be loaded (is the native build part of the app?)"
        val gl = GlThread("am-selftest")
        val pool = DecoderPool(maxHardware = 3, maxSoftware = 2)
        try {
            gl.call {
                val r = NativeRenderer.create()
                if (r == null) {
                    log("FAIL: no OpenGL ES 3 / EGL renderer")
                    return@call
                }
                try {
                    val w = 360
                    val h = 640
                    if (!r.setOffscreen(w, h)) {
                        log("FAIL: offscreen output not available")
                        return@call
                    }
                    val buf = ByteArray(w * h * 4)

                    // ---- Test 1: one clip, three times
                    val one = VideoLayerSource.open(context, uri, r, pool)
                    if (one == null) {
                        log("FAIL: video could not be opened")
                        return@call
                    }
                    val c = one.clip
                    log("clip ${c.width}x${c.height} rot=${c.rotation} fps=${c.fps} dur=${c.durationUs / 1000} ms")
                    val dur = if (c.durationUs > 0) c.durationUs else 3_000_000L
                    for (t in longArrayOf(0L, dur / 4, dur / 2)) {
                        val t0 = SystemClock.elapsedRealtime()
                        val st = one.seek(t)
                        r.beginFrame()
                        r.drawLayer(one.textureId, true, one.texMatrix, one.contentWidth, one.contentHeight, c.rotation)
                        r.readPixels(0, 0, w, h, buf)
                        r.endFrame()
                        val s = analyse(buf, w, h)
                        log("T1 seek ${t / 1000} ms -> status=$st pts=${c.ptsUs / 1000} ms, ${SystemClock.elapsedRealtime() - t0} ms, " +
                            "non-black=${"%.0f".format(s.nonBlack * 100)}% luma=${"%.0f".format(s.meanLuma)} center=${s.center}")
                    }
                    one.close()

                    // ---- Test 2: 6 layers of the same file on 3 hardware decoders
                    val n = 6
                    val layers = (0 until n).mapNotNull { VideoLayerSource.open(context, uri, r, pool) }
                    if (layers.size < n) log("WARN: only ${layers.size}/$n layers opened")
                    var failures = 0
                    val t0 = SystemClock.elapsedRealtime()
                    for (round in 0 until 3) {
                        r.beginFrame(0.1f, 0.1f, 0.1f, 1f)
                        layers.forEachIndexed { i, l ->
                            var st: Int
                            var tries = 0
                            do {
                                st = l.seek((dur / 8) * (i + round))
                                if (st == DecoderPool.BUSY) SystemClock.sleep(5)
                            } while (st == DecoderPool.BUSY && ++tries < 100)
                            if (st < 0 && st != DecoderPool.END_OF_STREAM) failures++
                            val p = LayerParams().apply {
                                scaleX = 0.45f; scaleY = 0.45f
                                offsetX = if (i % 2 == 0) -w * 0.25f else w * 0.25f
                                offsetY = (i / 2 - 1) * h * 0.3f
                            }
                            r.drawLayer(l.textureId, true, l.texMatrix, l.contentWidth, l.contentHeight, l.drawRotation, p)
                        }
                        r.readPixels(0, 0, w, h, buf)
                        r.endFrame()
                        val s = analyse(buf, w, h)
                        log("T2 round $round: non-black=${"%.0f".format(s.nonBlack * 100)}% luma=${"%.0f".format(s.meanLuma)}")
                    }
                    val ps = pool.stats()
                    log("T2 ${SystemClock.elapsedRealtime() - t0} ms, failures=$failures | decoders hw ${ps.activeHw}/${ps.maxHw}" +
                        " sw ${ps.activeSw}/${ps.maxSw}, evictions=${ps.evictions}, fallbacks=${ps.fallbacks}, errors=${ps.errors}")
                    layers.forEach { it.close() }
                    log(if (failures == 0) "RESULT: OK" else "RESULT: FAILED ($failures seeks failed)")
                } finally {
                    r.close()
                }
            }
        } catch (e: Throwable) {
            log("FAIL: ${e::class.java.simpleName}: ${e.message}")
        } finally {
            pool.close()
            gl.close()
        }
        return out.toString()
    }
}
