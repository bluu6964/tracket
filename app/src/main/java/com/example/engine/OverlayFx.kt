package com.example.engine

import android.graphics.Bitmap
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * The part of a canvas-sized overlay picture (text / shape / sticker) that the Effects panel's effects run on, in canvas
 * pixels (origin top-left). A text or shape layer is small inside a canvas-sized picture, so effects that work from the
 * picture's centre or edges (Vignette, Mirror, Kaleidoscope, Polar, Mosaic, Lens Distortion, ...) did almost nothing visible.
 * Running them on the layer's own box (plus room for Blur / Glow to spread) makes them behave like on a video layer.
 */
internal class FxRegion(val x: Int, val y: Int, val w: Int, val h: Int) {
    /** [x, y, w, h] as fractions of the canvas: the `crop` of [NativeRenderer.applyFx]. */
    fun crop(canvasW: Int, canvasH: Int): FloatArray = floatArrayOf(
        x / canvasW.toFloat(), y / canvasH.toFloat(), w / canvasW.toFloat(), h / canvasH.toFloat()
    )

    /**
     * Draw parameters that put the effects' result (a picture of w * h pixels, drawn with content size w * h) back at the
     * place it was cut from, 1 canvas pixel = 1 picture pixel (the renderer fits a picture into the canvas first, so the
     * scale undoes that fit).
     */
    fun place(canvasW: Int, canvasH: Int): LayerParams {
        val fit = min(canvasW.toFloat() / w, canvasH.toFloat() / h)
        val lp = LayerParams()
        lp.scaleX = 1f / fit
        lp.scaleY = 1f / fit
        lp.offsetX = x + w / 2f - canvasW / 2f
        lp.offsetY = y + h / 2f - canvasH / 2f
        return lp
    }
}

internal object OverlayFx {
    private const val STEP = 3      // every 3rd pixel is looked at: the box only has to be found, not traced
    private const val QUANT = 64    // the region size is a multiple of this, so a moving layer keeps the same GPU targets

    private var buf: ByteBuffer? = null

    /**
     * The padded box of the non-transparent pixels of [bmp], or null when the picture is empty (the caller then runs the
     * effects on the whole picture). Cheap: one memory copy and a coarse scan, done only when the picture was redrawn.
     */
    @Synchronized
    fun regionOf(bmp: Bitmap): FxRegion? {
        if (bmp.isRecycled || bmp.config != Bitmap.Config.ARGB_8888) return null
        val cw = bmp.width
        val ch = bmp.height
        if (cw < 2 * QUANT || ch < 2 * QUANT) return null
        val need = bmp.byteCount
        var b = buf
        if (b == null || b.capacity() < need) {
            b = ByteBuffer.allocateDirect(need)
            buf = b
        }
        b!!.clear()
        bmp.copyPixelsToBuffer(b)
        val rowBytes = bmp.rowBytes
        var minX = cw
        var minY = ch
        var maxX = -1
        var maxY = -1
        var y = 0
        while (y < ch) {
            val row = y * rowBytes
            var x = 0
            while (x < cw) {
                // ARGB_8888 is stored as R, G, B, A bytes: the alpha is the 4th byte of a pixel.
                if ((b.get(row + x * 4 + 3).toInt() and 0xFF) > 2) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                x += STEP
            }
            y += STEP
        }
        if (maxX < 0) return null
        val bw = maxX - minX + STEP
        val bh = maxY - minY + STEP
        // Room for blur / glow / shake to spread: half the layer's size, at least 48 px, at most 480 px.
        val pad = (max(bw, bh) * 0.5f).toInt().coerceIn(48, 480) + STEP
        val (rx, rw) = span(minX - pad, maxX + pad + 1, cw)
        val (ry, rh) = span(minY - pad, maxY + pad + 1, ch)
        return FxRegion(rx, ry, rw, rh)
    }

    /** [lo, hi) rounded up to a multiple of [QUANT], centred on the same middle, kept inside 0 until [total]. */
    private fun span(lo: Int, hi: Int, total: Int): Pair<Int, Int> {
        var size = hi - lo
        size = ((size + QUANT - 1) / QUANT) * QUANT
        size = max(QUANT, min(size, total))
        val start = ((lo + hi) / 2 - size / 2).coerceIn(0, total - size)
        return start to size
    }
}
