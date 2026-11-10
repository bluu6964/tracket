package com.example.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.nio.FloatBuffer

/**
 * Robust Video Matting (MobileNetV3, ONNX) through ONNX Runtime: real alpha matting, soft hair
 * and edges, plus cleaned foreground colours.
 *
 * The model is recurrent (it carries 4 state tensors from frame to frame). For one picture the
 * same frame is fed several times so the state "warms up" and the result gets sharper.
 *
 * Model file: app/src/main/assets/rvm/rvm_mobilenetv3_fp32.onnx (see the README next to it).
 */
object RvmEngine {

    const val MODEL_ASSET = "rvm/rvm_mobilenetv3_fp32.onnx"

    private const val TAG = "RvmEngine"

    /**
     * XNNPACK self-test is OFF: a native crash inside ORT cannot be caught, and it is not proven on
     * every phone. Turn on only after trying it on a device you can debug.
     */
    private const val ENABLE_XNNPACK_TUNE = false

    /** Threads of the model. The phone also decodes and saves masks at the same time, so not more than 4. */
    private val THREADS = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    /** Video needs only the alpha and the 4 memory tensors: asking for these alone skips copying `fgr`. */
    private val VIDEO_OUTPUTS = setOf("pha", "r1o", "r2o", "r3o", "r4o")

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    /** Execution provider in use: "xnnpack" or "cpu" (chosen by a quick self-test on this phone). */
    @Volatile
    var provider: String = "cpu"
        private set

    /** True when the model is downloaded (see [ModelDownloader]) or shipped in assets. */
    fun isAvailable(context: Context): Boolean {
        if (ModelDownloader.isDownloaded(context)) return true
        return try {
            context.assets.open(MODEL_ASSET).close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun createSession(e: OrtEnvironment, bytes: ByteArray, xnnpack: Boolean): OrtSession {
        val o = OrtSession.SessionOptions()
        try {
            if (xnnpack) {
                // XNNPACK has its own thread pool; ORT's own pool stays at 1.
                o.setIntraOpNumThreads(1)
                o.addXnnpack(mapOf("intra_op_num_threads" to THREADS.toString()))
            } else {
                o.setIntraOpNumThreads(THREADS)
            }
            o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            return e.createSession(bytes, o)
        } finally {
            o.close()
        }
    }

    /** Median time (ms) of one model step on a dummy 384x216 picture, or null when this provider fails. */
    private fun benchMs(e: OrtEnvironment, bytes: ByteArray, xnnpack: Boolean): Double? = try {
        val s = createSession(e, bytes, xnnpack)
        try {
            val w = 384
            val h = 216
            val chw = FloatArray(3 * w * h) { (it % 251) / 251f }
            Stream(s).use { st ->
                repeat(2) { st.step(chw, w, h, 384) }
                val t = DoubleArray(5)
                for (i in t.indices) {
                    val t0 = System.nanoTime()
                    st.step(chw, w, h, 384)
                    t[i] = (System.nanoTime() - t0) / 1e6
                }
                t.sort()
                t[2]
            }
        } finally {
            s.close()
        }
    } catch (t: Throwable) {
        Log.w(TAG, "bench xnnpack=$xnnpack failed: $t")
        null
    }

    /** Picks the faster of plain CPU and XNNPACK once (about a second) and remembers it. */
    private fun tune(e: OrtEnvironment, bytes: ByteArray): String {
        val cpu = benchMs(e, bytes, false)
        val xnn = benchMs(e, bytes, true)
        val pick = if (xnn != null && (cpu == null || xnn < cpu * 0.9)) "xnnpack" else "cpu"
        Log.i(TAG, "tune: cpu=$cpu ms, xnnpack=$xnn ms -> $pick")
        return pick
    }

    @Synchronized
    internal fun session(context: Context): OrtSession {
        session?.let { return it }
        val e = OrtEnvironment.getEnvironment()
        val file = ModelDownloader.modelFile(context)
        val fromFile = ModelDownloader.isDownloaded(context)
        val bytes = if (fromFile) file.readBytes() else context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val prefs = context.getSharedPreferences("rvm_tune", Context.MODE_PRIVATE)
        var ep: String? = "cpu"
        if (ENABLE_XNNPACK_TUNE) {
            ep = prefs.getString("ep", null)
            if (ep == null) {
                CutoutTrace.mark(context, "tune")
                ep = try { tune(e, bytes) } catch (t: Throwable) { "cpu" }
                prefs.edit().putString("ep", ep).apply()
            }
        }
        CutoutTrace.mark(context, "create session ($ep)")
        val s = try {
            try {
                createSession(e, bytes, ep == "xnnpack")
            } catch (t: Throwable) {
                if (ep != "xnnpack") throw t
                prefs.edit().putString("ep", "cpu").apply()
                ep = "cpu"
                createSession(e, bytes, false)
            }
        } catch (t: Exception) {
            // A damaged download must not stay: delete it so the next try downloads it again.
            if (fromFile) file.delete()
            throw t
        }
        provider = ep ?: "cpu"
        env = e
        session = s
        CutoutTrace.mark(context, "session ok")
        return s
    }

    /**
     * A running video: feed consecutive frames of the SAME size with [step]; the recurrent state is
     * kept between calls, which is what makes the alpha steady from frame to frame.
     */
    class Stream internal constructor(private val s: OrtSession) : AutoCloseable {
        constructor(context: Context) : this(RvmEngine.session(context))

        private val e = OrtEnvironment.getEnvironment()
        private var states: List<OnnxTensor> = (0 until 4).map {
            OnnxTensor.createTensor(e, FloatBuffer.wrap(floatArrayOf(0f)), longArrayOf(1, 1, 1, 1))
        }
        private var initial = true
        private var prev: OrtSession.Result? = null

        /** Alpha (0..1, row-major) of the person in a [Bitmap]. */
        fun step(src: Bitmap): FloatArray {
            val w = src.width
            val h = src.height
            val wh = w * h
            val px = IntArray(wh)
            src.getPixels(px, 0, w, 0, 0, w, h)
            val chw = FloatArray(3 * wh)
            for (i in 0 until wh) {
                val c = px[i]
                chw[i] = ((c shr 16) and 0xFF) / 255f
                chw[wh + i] = ((c shr 8) and 0xFF) / 255f
                chw[2 * wh + i] = (c and 0xFF) / 255f
            }
            return step(chw, w, h, 512)
        }

        /**
         * Alpha (0..1, row-major, w*h) of the person in a planar RGB picture ([chw]: all R, then
         * all G, then all B, each 0..1). The model itself works on a copy of about [modelSide]
         * px on the long side: smaller = faster.
         */
        fun step(chw: FloatArray, w: Int, h: Int, modelSide: Int): FloatArray {
            val wh = w * h
            val ratio = (modelSide.toFloat() / maxOf(w, h)).coerceIn(0.2f, 1f)
            val srcT = OnnxTensor.createTensor(e, FloatBuffer.wrap(chw), longArrayOf(1, 3, h.toLong(), w.toLong()))
            val ratioT = OnnxTensor.createTensor(e, FloatBuffer.wrap(floatArrayOf(ratio)), longArrayOf(1))
            try {
                val res = s.run(
                    mapOf(
                        "src" to srcT,
                        "r1i" to states[0], "r2i" to states[1], "r3i" to states[2], "r4i" to states[3],
                        "downsample_ratio" to ratioT
                    ),
                    VIDEO_OUTPUTS
                )
                val phaT = res.get("pha").get() as OnnxTensor
                val pha = FloatArray(wh).also { phaT.floatBuffer.get(it) }
                val next = listOf("r1o", "r2o", "r3o", "r4o").map { res.get(it).get() as OnnxTensor }
                if (initial) {
                    states.forEach { it.close() }
                    initial = false
                }
                prev?.close()
                prev = res
                states = next
                return pha
            } finally {
                srcT.close()
                ratioT.close()
            }
        }

        override fun close() {
            prev?.close()
            prev = null
            if (initial) states.forEach { it.close() }
            states = emptyList()
        }
    }

    /** The person of [src] with a soft alpha, on a transparent background. Blocking: call off the main thread. */
    @Synchronized
    fun matte(context: Context, src: Bitmap, warmupPasses: Int = 4): Bitmap {
        val s = session(context)
        val e = env!!
        val w = src.width
        val h = src.height
        val wh = w * h

        // RGB 0..1, planar (NCHW).
        val px = IntArray(wh)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val chw = FloatArray(3 * wh)
        for (i in 0 until wh) {
            val c = px[i]
            chw[i] = ((c shr 16) and 0xFF) / 255f
            chw[wh + i] = ((c shr 8) and 0xFF) / 255f
            chw[2 * wh + i] = (c and 0xFF) / 255f
        }

        // The model works on a reduced copy: about 512 px on the long side.
        val ratio = (512f / maxOf(w, h)).coerceIn(0.2f, 1f)

        val srcT = OnnxTensor.createTensor(e, FloatBuffer.wrap(chw), longArrayOf(1, 3, h.toLong(), w.toLong()))
        val ratioT = OnnxTensor.createTensor(e, FloatBuffer.wrap(floatArrayOf(ratio)), longArrayOf(1))
        val zeros = (0 until 4).map {
            OnnxTensor.createTensor(e, FloatBuffer.wrap(floatArrayOf(0f)), longArrayOf(1, 1, 1, 1))
        }
        var states: List<OnnxTensor> = zeros
        var prev: OrtSession.Result? = null
        var pha = FloatArray(0)
        var fgr = FloatArray(0)
        try {
            repeat(warmupPasses.coerceAtLeast(1)) {
                val inputs = mapOf(
                    "src" to srcT,
                    "r1i" to states[0], "r2i" to states[1], "r3i" to states[2], "r4i" to states[3],
                    "downsample_ratio" to ratioT
                )
                val res = s.run(inputs)
                val phaT = res.get("pha").get() as OnnxTensor
                val fgrT = res.get("fgr").get() as OnnxTensor
                pha = FloatArray(wh).also { phaT.floatBuffer.get(it) }
                fgr = FloatArray(3 * wh).also { fgrT.floatBuffer.get(it) }
                states = listOf("r1o", "r2o", "r3o", "r4o").map { res.get(it).get() as OnnxTensor }
                prev?.close()
                prev = res
            }
        } finally {
            prev?.close()
            zeros.forEach { it.close() }
            srcT.close()
            ratioT.close()
        }

        val out = IntArray(wh)
        for (i in 0 until wh) {
            val a = pha[i].coerceIn(0f, 1f)
            val alpha = if (a < 0.02f) 0 else (a * 255f + 0.5f).toInt()
            val r = (fgr[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val g = (fgr[wh + i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val b = (fgr[2 * wh + i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            out[i] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
    }
}
