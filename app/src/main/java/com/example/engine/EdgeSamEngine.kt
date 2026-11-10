package com.example.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * EdgeSAM (ONNX) through ONNX Runtime: "click / brush on an object, get its mask". Used by the Roto Brush.
 *
 * Two model files are needed (see README_ROTO.md): the image ENCODER (picture -> embeddings, slow, once per
 * frame) and the prompt DECODER (embeddings + points / box -> mask, fast, runs again on every brush stroke).
 *
 * Files are looked for in the app's files folder (models/edgesam_encoder.onnx, models/edgesam_decoder.onnx,
 * put there by [importModel]) and then in assets (edgesam/edgesam_encoder.onnx, edgesam/edgesam_decoder.onnx).
 *
 * The input / output names of the decoder differ a little between ONNX exports, so they are found at run time
 * (by name part) and optional inputs (mask_input, has_mask_input, orig_im_size) are filled only when present.
 */
object EdgeSamEngine {

    const val ENCODER_FILE = "edgesam_encoder.onnx"
    const val DECODER_FILE = "edgesam_decoder.onnx"
    private const val ASSET_DIR = "edgesam"

    /** Side of the (square, padded) picture the encoder works on. */
    const val SIDE = 1024
    /** Side of the low-resolution mask logits of the decoder. */
    private const val LOW = 256

    // SAM pixel normalisation (the encoder export does not do it itself).
    private val MEAN = floatArrayOf(123.675f, 116.28f, 103.53f)
    private val STD = floatArrayOf(58.395f, 57.12f, 57.375f)

    private val THREADS = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    private fun modelFile(context: Context, name: String) = File(File(context.filesDir, "models"), name)

    private fun hasAsset(context: Context, name: String): Boolean = try {
        context.assets.open("$ASSET_DIR/$name").close()
        true
    } catch (e: Exception) {
        false
    }

    fun hasEncoder(context: Context) = modelFile(context, ENCODER_FILE).length() > 1_000_000L || hasAsset(context, ENCODER_FILE)
    fun hasDecoder(context: Context) = modelFile(context, DECODER_FILE).length() > 100_000L || hasAsset(context, DECODER_FILE)
    fun isAvailable(context: Context) = hasEncoder(context) && hasDecoder(context)

    /** Copies a model file chosen with the system file picker into the app. [encoder] = which of the two it is. */
    fun importModel(context: Context, uri: Uri, encoder: Boolean) {
        val dest = modelFile(context, if (encoder) ENCODER_FILE else DECODER_FILE)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open the file")
            input.use { ins -> FileOutputStream(tmp).use { out -> ins.copyTo(out, 64 * 1024) } }
            if (tmp.length() < 100_000L) throw IOException("The file is too small to be an EdgeSAM model")
            dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("Could not save the model")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        release() // next use loads the new file
    }

    // Official EdgeSAM (3x) ONNX files (Hugging Face Space of the authors). If a link ever stops working the
    // Roto screen still lets the user choose the two files by hand.
    const val ENCODER_URL = "https://huggingface.co/spaces/chongzhou/EdgeSAM/resolve/main/weights/edge_sam_3x_encoder.onnx"
    const val DECODER_URL = "https://huggingface.co/spaces/chongzhou/EdgeSAM/resolve/main/weights/edge_sam_3x_decoder.onnx"

    /** Downloads the missing model files (about 38 MB in total). [onProgress] = 0..1 over both files. */
    suspend fun download(context: Context, onProgress: (Float) -> Unit) {
        val parts = listOf(true, false)
        for ((i, enc) in parts.withIndex()) {
            val have = if (enc) hasEncoder(context) else hasDecoder(context)
            if (have) { onProgress((i + 1) / 2f); continue }
            downloadOne(context, enc) { p -> onProgress((i + p) / 2f) }
        }
        release()
    }

    private suspend fun downloadOne(context: Context, encoder: Boolean, onProgress: (Float) -> Unit) {
        val dest = modelFile(context, if (encoder) ENCODER_FILE else DECODER_FILE)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(if (encoder) ENCODER_URL else DECODER_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("Download failed (HTTP $code). Use \"Choose file\" instead.")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done / total.toFloat()).coerceIn(0f, 1f))
                    }
                }
            }
            if (tmp.length() < 100_000L || (total > 0 && tmp.length() != total)) throw IOException("Downloaded file is incomplete, try again")
            dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("Could not save the model")
        } finally {
            conn?.disconnect()
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * How the encoder wants its picture: 0 = SAM mean/std, 1 = 0..1 (normalisation is inside the model),
     * 2 = 0..255. Exports differ and the model does not say, so the Roto screen has a button that switches it.
     */
    fun inputMode(context: Context): Int =
        context.getSharedPreferences("edgesam", Context.MODE_PRIVATE).getInt("input_mode", 0)

    fun setInputMode(context: Context, mode: Int) {
        context.getSharedPreferences("edgesam", Context.MODE_PRIVATE).edit().putInt("input_mode", mode).apply()
    }

    private fun readModel(context: Context, name: String): ByteArray {
        val f = modelFile(context, name)
        return if (f.length() > 100_000L) f.readBytes()
        else context.assets.open("$ASSET_DIR/$name").use { it.readBytes() }
    }

    private var env: OrtEnvironment? = null
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null

    @Synchronized
    private fun sessions(context: Context): Pair<OrtSession, OrtSession> {
        val e = env ?: OrtEnvironment.getEnvironment().also { env = it }
        val enc = encoder ?: create(e, readModel(context, ENCODER_FILE)).also { encoder = it }
        val dec = decoder ?: create(e, readModel(context, DECODER_FILE)).also { decoder = it }
        return Pair(enc, dec)
    }

    private fun create(e: OrtEnvironment, bytes: ByteArray): OrtSession {
        val o = OrtSession.SessionOptions()
        try {
            o.setIntraOpNumThreads(THREADS)
            o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            return e.createSession(bytes, o)
        } finally {
            o.close()
        }
    }

    @Synchronized
    fun release() {
        try { encoder?.close() } catch (_: Throwable) {}
        try { decoder?.close() } catch (_: Throwable) {}
        encoder = null
        decoder = null
    }

    // ---- data -------------------------------------------------------------------------

    /** Result of the encoder for one picture: keep it while the user brushes on that picture. */
    class Embedding internal constructor(
        internal val data: FloatArray,
        internal val shape: LongArray,
        /** Original picture size. */
        val width: Int,
        val height: Int,
        /** Picture -> 1024 square scale (longest side = 1024). */
        internal val scale: Float
    )

    /** A brush point in ORIGINAL picture pixels. [positive] = "this is the object", false = "this is not". */
    class Pt(val x: Float, val y: Float, val positive: Boolean)

    /** Box prompt in original picture pixels. */
    class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    /**
     * One mask: [alpha] (0..1, soft edge) at [w] x [h] (the size asked for), plus the raw low-res logits
     * ([logits], 256x256) that can be fed back as a hint for the next frame / next stroke.
     */
    class Mask(val w: Int, val h: Int, val alpha: FloatArray, val logits: FloatArray, val score: Float) {
        /** Share of the picture covered by the object (0..1). */
        fun coverage(): Float {
            var n = 0
            for (v in alpha) if (v > 0.5f) n++
            return n / alpha.size.toFloat().coerceAtLeast(1f)
        }
    }

    // ---- encoder ----------------------------------------------------------------------

    /** Runs the encoder on [bmp] (any size; scaled so that its longest side is 1024, padded with zeros). */
    fun encode(context: Context, bmp: Bitmap): Embedding {
        val (enc, _) = sessions(context)
        val e = env!!
        val w = bmp.width
        val h = bmp.height
        val scale = SIDE / max(w, h).toFloat()
        val nw = (w * scale + 0.5f).toInt().coerceIn(1, SIDE)
        val nh = (h * scale + 0.5f).toInt().coerceIn(1, SIDE)
        val scaled = if (nw == w && nh == h) bmp else Bitmap.createScaledBitmap(bmp, nw, nh, true)
        val px = IntArray(nw * nh)
        scaled.getPixels(px, 0, nw, 0, 0, nw, nh)
        if (scaled !== bmp) scaled.recycle()

        // CHW float, normalised; the padded area stays 0 (= the mean colour after normalisation).
        val mode = inputMode(context)
        val plane = SIDE * SIDE
        val chw = FloatArray(3 * plane)
        for (y in 0 until nh) {
            val row = y * SIDE
            for (x in 0 until nw) {
                val c = px[y * nw + x]
                val r = ((c shr 16) and 0xFF).toFloat()
                val g = ((c shr 8) and 0xFF).toFloat()
                val b = (c and 0xFF).toFloat()
                when (mode) {
                    1 -> { chw[row + x] = r / 255f; chw[plane + row + x] = g / 255f; chw[2 * plane + row + x] = b / 255f }
                    2 -> { chw[row + x] = r; chw[plane + row + x] = g; chw[2 * plane + row + x] = b }
                    else -> {
                        chw[row + x] = (r - MEAN[0]) / STD[0]
                        chw[plane + row + x] = (g - MEAN[1]) / STD[1]
                        chw[2 * plane + row + x] = (b - MEAN[2]) / STD[2]
                    }
                }
            }
        }

        val name = enc.inputNames.first()
        val result: Embedding = OnnxTensor.createTensor(e, FloatBuffer.wrap(chw), longArrayOf(1, 3, SIDE.toLong(), SIDE.toLong())).use { input ->
            enc.run(mapOf(name to input)).use { res ->
                val out = res.get(0) as OnnxTensor
                val shape = out.info.shape
                val fb = out.floatBuffer
                val data = FloatArray(fb.remaining())
                fb.get(data)
                Embedding(data, shape, w, h, scale)
            }
        }
        return result
    }

    // ---- decoder ----------------------------------------------------------------------

    /**
     * Mask for the brush [points] (and optional [box]) on the picture of [emb]. [hint] = low-res logits of an
     * earlier mask of (nearly) the same object (previous stroke / previous frame): makes the answer steadier.
     * [prefer] = alpha of a mask to stay close to (the best of the model's candidates is the one that overlaps
     * it most); null = take the candidate with the best score.
     * The result is sampled at [outW] x [outH] (a picture of the same shape as the encoded one).
     */
    fun decode(
        context: Context,
        emb: Embedding,
        points: List<Pt>,
        box: Box?,
        hint: FloatArray?,
        prefer: FloatArray?,
        outW: Int,
        outH: Int
    ): Mask {
        val (_, dec) = sessions(context)
        val e = env!!

        // SAM prompt convention: a box is two points with labels 2 (top-left) and 3 (bottom-right);
        // padding point (label -1) is added when there is no box.
        val n = points.size + (if (box != null) 2 else 1)
        val coords = FloatArray(n * 2)
        val labels = FloatArray(n)
        var i = 0
        for (p in points) {
            coords[i * 2] = p.x * emb.scale
            coords[i * 2 + 1] = p.y * emb.scale
            labels[i] = if (p.positive) 1f else 0f
            i++
        }
        if (box != null) {
            coords[i * 2] = box.x0 * emb.scale; coords[i * 2 + 1] = box.y0 * emb.scale; labels[i] = 2f; i++
            coords[i * 2] = box.x1 * emb.scale; coords[i * 2 + 1] = box.y1 * emb.scale; labels[i] = 3f; i++
        } else {
            coords[i * 2] = 0f; coords[i * 2 + 1] = 0f; labels[i] = -1f; i++
        }

        val opened = ArrayList<OnnxTensor>()
        try {
            val inputs = HashMap<String, OnnxTensor>()
            for (name in dec.inputNames) {
                val l = name.lowercase()
                val t: OnnxTensor? = when {
                    l.contains("embed") -> OnnxTensor.createTensor(e, FloatBuffer.wrap(emb.data), emb.shape)
                    l.contains("coord") -> OnnxTensor.createTensor(e, FloatBuffer.wrap(coords), longArrayOf(1, n.toLong(), 2))
                    l.contains("label") -> OnnxTensor.createTensor(e, FloatBuffer.wrap(labels), longArrayOf(1, n.toLong()))
                    l.contains("has_mask") -> OnnxTensor.createTensor(
                        e, FloatBuffer.wrap(floatArrayOf(if (hint != null) 1f else 0f)), longArrayOf(1)
                    )
                    l.contains("mask_in") -> OnnxTensor.createTensor(
                        e, FloatBuffer.wrap(hint ?: FloatArray(LOW * LOW)), longArrayOf(1, 1, LOW.toLong(), LOW.toLong())
                    )
                    l.contains("orig") || l.contains("im_size") ->
                        OnnxTensor.createTensor(e, FloatBuffer.wrap(floatArrayOf(emb.height.toFloat(), emb.width.toFloat())), longArrayOf(2))
                    else -> null
                }
                if (t != null) {
                    opened.add(t)
                    inputs[name] = t
                }
            }
            val result: Mask = dec.run(inputs).use { res ->
                var masksT: OnnxTensor? = null
                var scoresT: OnnxTensor? = null
                for (k in 0 until res.size()) {
                    val v = res.get(k) as? OnnxTensor ?: continue
                    val nm = dec.outputNames.elementAtOrNull(k)?.lowercase() ?: ""
                    if (nm.contains("score") || nm.contains("iou")) scoresT = v
                    else if (nm.contains("mask") || masksT == null) masksT = v
                }
                val mt = masksT ?: throw IllegalStateException("Decoder gave no mask")
                val shape = mt.info.shape // [1, M, H, W] (or [1, H, W])
                val mh = shape[shape.size - 2].toInt()
                val mw = shape[shape.size - 1].toInt()
                val count = if (shape.size >= 4) shape[1].toInt() else 1
                val fb = mt.floatBuffer
                val all = FloatArray(fb.remaining())
                fb.get(all)
                val scores = FloatArray(count) { 0f }
                scoresT?.let {
                    val sb = it.floatBuffer
                    var q = 0
                    while (sb.hasRemaining() && q < count) scores[q++] = sb.get()
                }

                // Sample every candidate at the output size, then choose.
                val plane = mh * mw
                var best = 0
                var bestVal = -1f
                val candidates = ArrayList<FloatArray>(count)
                for (c in 0 until count) {
                    val a = sample(all, c * plane, mw, mh, emb, outW, outH)
                    candidates.add(a)
                    val v = if (prefer != null && prefer.size == a.size) overlap(a, prefer) else scores[c]
                    if (v > bestVal) { bestVal = v; best = c }
                }
                val lowLogits = lowRes(all, best * plane, mw, mh)
                Mask(outW, outH, candidates[best], lowLogits, scores[best])
            }
            return result
        } finally {
            for (t in opened) try { t.close() } catch (_: Throwable) {}
        }
    }

    /** Intersection over union of two alpha pictures of the same size. */
    private fun overlap(a: FloatArray, b: FloatArray): Float {
        var inter = 0
        var uni = 0
        for (k in a.indices) {
            val x = a[k] > 0.5f
            val y = b[k] > 0.5f
            if (x && y) inter++
            if (x || y) uni++
        }
        return if (uni == 0) 0f else inter / uni.toFloat()
    }

    private fun sigmoid(v: Float): Float = 1f / (1f + exp(-v.toDouble()).toFloat())

    /**
     * Logits of size mw x mh -> alpha at outW x outH. The logits cover the padded 1024 square (low-res: 256),
     * so only the top-left [scaled picture] part belongs to the picture. A decoder that already answers at the
     * picture size (orig_im_size export) is mapped straight.
     */
    private fun sample(src: FloatArray, off: Int, mw: Int, mh: Int, emb: Embedding, outW: Int, outH: Int): FloatArray {
        val out = FloatArray(outW * outH)
        val full = mw == emb.width && mh == emb.height
        // Pixel of the original picture -> position in the logits.
        val kx = if (full) 1f else (mw * emb.scale / SIDE)
        val ky = if (full) 1f else (mh * emb.scale / SIDE)
        for (y in 0 until outH) {
            val py = (y + 0.5f) * emb.height / outH
            val fy = (py * ky - 0.5f).coerceIn(0f, mh - 1f)
            val y0 = floor(fy).toInt()
            val y1 = min(y0 + 1, mh - 1)
            val ty = fy - y0
            for (x in 0 until outW) {
                val px = (x + 0.5f) * emb.width / outW
                val fx = (px * kx - 0.5f).coerceIn(0f, mw - 1f)
                val x0 = floor(fx).toInt()
                val x1 = min(x0 + 1, mw - 1)
                val tx = fx - x0
                val a = src[off + y0 * mw + x0]
                val b = src[off + y0 * mw + x1]
                val c = src[off + y1 * mw + x0]
                val d = src[off + y1 * mw + x1]
                val v = (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty
                // The logit 0 is the edge; a slope of 1.5 gives a soft but not blurry border.
                out[y * outW + x] = sigmoid(v * 1.5f)
            }
        }
        return out
    }

    /** The candidate as 256x256 logits (for use as the next hint). */
    private fun lowRes(src: FloatArray, off: Int, mw: Int, mh: Int): FloatArray {
        if (mw == LOW && mh == LOW) return src.copyOfRange(off, off + LOW * LOW)
        val out = FloatArray(LOW * LOW)
        for (y in 0 until LOW) {
            val sy = min(mh - 1, y * mh / LOW)
            for (x in 0 until LOW) out[y * LOW + x] = src[off + sy * mw + min(mw - 1, x * mw / LOW)]
        }
        return out
    }

    // ---- helpers for prompts built from a mask ------------------------------------------

    /** Bounding box of the pixels above 0.5 of [alpha] (w x h), grown by [grow] (0.08 = 8 %), in picture pixels. */
    fun boxOf(alpha: FloatArray, w: Int, h: Int, picW: Int, picH: Int, grow: Float): Box? {
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) if (alpha[y * w + x] > 0.5f) {
            if (x < x0) x0 = x
            if (x > x1) x1 = x
            if (y < y0) y0 = y
            if (y > y1) y1 = y
        }
        if (x1 < 0) return null
        val sx = picW / w.toFloat()
        val sy = picH / h.toFloat()
        val gw = (x1 - x0 + 1) * grow
        val gh = (y1 - y0 + 1) * grow
        return Box(
            ((x0 - gw) * sx).coerceAtLeast(0f), ((y0 - gh) * sy).coerceAtLeast(0f),
            ((x1 + 1 + gw) * sx).coerceAtMost(picW.toFloat()), ((y1 + 1 + gh) * sy).coerceAtMost(picH.toFloat())
        )
    }

    /**
     * Up to [count] "sure object" points inside [alpha] (w x h): the middles of the biggest solid areas, found
     * on a coarse grid (a cell counts only when all of it is object, so points are never near the edge).
     */
    fun insidePoints(alpha: FloatArray, w: Int, h: Int, picW: Int, picH: Int, count: Int): List<Pt> {
        val cell = max(4, min(w, h) / 12)
        val cols = w / cell
        val rows = h / cell
        if (cols < 1 || rows < 1) return emptyList()
        class Cand(val cx: Int, val cy: Int, val depth: Int)
        val fill = Array(rows) { BooleanArray(cols) }
        for (r in 0 until rows) for (c in 0 until cols) {
            var all = true
            loop@ for (y in r * cell until (r + 1) * cell) for (x in c * cell until (c + 1) * cell) {
                if (alpha[y * w + x] <= 0.5f) { all = false; break@loop }
            }
            fill[r][c] = all
        }
        // "Depth" = how many steps of the 4 neighbours are still object around the cell (small, cheap).
        val cands = ArrayList<Cand>()
        for (r in 0 until rows) for (c in 0 until cols) if (fill[r][c]) {
            var d = 1
            while (d < 6) {
                var ok = true
                for (k in -d..d) {
                    if (!at(fill, r + k, c - d) || !at(fill, r + k, c + d) || !at(fill, r - d, c + k) || !at(fill, r + d, c + k)) { ok = false; break }
                }
                if (!ok) break
                d++
            }
            cands.add(Cand(c, r, d))
        }
        cands.sortByDescending { it.depth }
        val sx = picW / w.toFloat()
        val sy = picH / h.toFloat()
        val out = ArrayList<Pt>()
        val minDist = cell * 3
        for (cd in cands) {
            val px = cd.cx * cell + cell / 2
            val py = cd.cy * cell + cell / 2
            if (out.none { Math.hypot(((it.x / sx) - px).toDouble(), ((it.y / sy) - py).toDouble()) < minDist }) {
                out.add(Pt(px * sx, py * sy, true))
                if (out.size >= count) break
            }
        }
        return out
    }

    private fun at(f: Array<BooleanArray>, r: Int, c: Int): Boolean =
        r >= 0 && c >= 0 && r < f.size && c < f[0].size && f[r][c]
}
