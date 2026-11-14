package com.example.model

import java.io.OutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Animated GIF89a export, pure Kotlin (build 189 — production-push queue item 2).
 *
 * Three pieces, all free of Android types so the harness can pin them:
 *  - [GifQuantizer]: one frame of RGBA pixels -> a palette of at most 256 colours
 *    (median cut over a 5-bit-per-channel histogram) + one palette index per pixel.
 *    Every histogram bin belongs to exactly one palette box, so mapping a frame to
 *    indices is a table lookup, never a nearest-colour search.
 *  - [GifLzw]: the GIF LZW compressor (clear codes, code-width growth to 12 bits,
 *    a clear + dictionary reset when the 4096-entry table fills).
 *  - [GifWriter]: the streaming GIF89a container over an [OutputStream] — header,
 *    NETSCAPE2.0 loop-forever block, then per frame a Graphic Control Extension
 *    (delay, no transparency) and an Image Descriptor carrying that frame's own
 *    local colour table.
 *
 * The rendered frames are opaque composites (the project canvas colour is behind
 * every layer, exactly like the MP4 export), so no transparency flag is written —
 * there is no alpha to honour, and inventing one would fake a transparent GIF.
 */

/** Median-cut colour quantization for one frame. Deterministic: same input, same palette. */
object GifQuantizer {

    /** [palette] entries are 0xRRGGBB, the size a power of two in 2..256; [indices] row-major, top row first. */
    class Result(val palette: IntArray, val indices: ByteArray)

    private class Box(val bins: IntArray) {
        var population = 0
        var rMin = 31; var rMax = 0; var gMin = 31; var gMax = 0; var bMin = 31; var bMax = 0
        val splittable: Boolean
            get() = bins.size > 1 && (rMax > rMin || gMax > gMin || bMax > bMin)
    }

    /**
     * Quantizes [rgba] (4 bytes per pixel, R,G,B,A — alpha ignored, frames are opaque).
     * [bottomUp] = the buffer's first row is the picture's BOTTOM row (GL readback order);
     * the returned indices are always top row first.
     */
    fun quantize(rgba: ByteArray, width: Int, height: Int, maxColors: Int = 256, bottomUp: Boolean = false): Result {
        val count = width * height
        require(width >= 1 && height >= 1) { "empty frame" }
        require(rgba.size >= count * 4) { "pixel buffer too small" }
        val limit = maxColors.coerceIn(2, 256)

        // Histogram over 5-bit channels, with the true 8-bit sums per bin for the averages.
        val hist = IntArray(32768)
        val rSum = IntArray(32768)
        val gSum = IntArray(32768)
        val bSum = IntArray(32768)
        var i = 0
        for (p in 0 until count) {
            val r = rgba[i].toInt() and 0xFF
            val g = rgba[i + 1].toInt() and 0xFF
            val b = rgba[i + 2].toInt() and 0xFF
            val bin = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            hist[bin]++
            rSum[bin] += r; gSum[bin] += g; bSum[bin] += b
            i += 4
        }

        fun makeBox(bins: IntArray): Box {
            val box = Box(bins)
            for (bin in bins) {
                box.population += hist[bin]
                val r = (bin shr 10) and 31; val g = (bin shr 5) and 31; val b = bin and 31
                if (r < box.rMin) box.rMin = r; if (r > box.rMax) box.rMax = r
                if (g < box.gMin) box.gMin = g; if (g > box.gMax) box.gMax = g
                if (b < box.bMin) box.bMin = b; if (b > box.bMax) box.bMax = b
            }
            return box
        }

        val used = IntArray(hist.count { it > 0 })
        var u = 0
        for (bin in hist.indices) if (hist[bin] > 0) used[u++] = bin
        val boxes = mutableListOf(makeBox(used))
        while (boxes.size < limit) {
            // Split the most populous splittable box, along its widest channel, at the weighted median.
            var target: Box? = null
            for (box in boxes) {
                if (box.splittable && (target == null || box.population > target.population)) target = box
            }
            val box = target ?: break
            val channel = when {
                box.rMax - box.rMin >= box.gMax - box.gMin && box.rMax - box.rMin >= box.bMax - box.bMin -> 0
                box.gMax - box.gMin >= box.bMax - box.bMin -> 1
                else -> 2
            }
            val shift = when (channel) { 0 -> 10; 1 -> 5; else -> 0 }
            val sorted = box.bins.sortedWith(compareBy({ (it shr shift) and 31 }, { it })).toIntArray()
            var acc = 0
            var cut = sorted.size - 2
            for (k in sorted.indices) {
                acc += hist[sorted[k]]
                if (acc >= box.population / 2) { cut = minOf(k, sorted.size - 2); break }
            }
            boxes.remove(box)
            boxes.add(makeBox(sorted.copyOfRange(0, cut + 1)))
            boxes.add(makeBox(sorted.copyOfRange(cut + 1, sorted.size)))
        }

        // Palette colour of a box = count-weighted average of its pixels' true colours.
        var tableSize = 2
        while (tableSize < boxes.size) tableSize = tableSize shl 1
        val palette = IntArray(tableSize)
        val binIndex = IntArray(32768) { -1 }
        for ((bi, box) in boxes.withIndex()) {
            var r = 0L; var g = 0L; var b = 0L
            for (bin in box.bins) {
                r += rSum[bin]; g += gSum[bin]; b += bSum[bin]
                binIndex[bin] = bi
            }
            val n = box.population.coerceAtLeast(1)
            palette[bi] = (((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt())
        }

        val indices = ByteArray(count)
        for (y in 0 until height) {
            val srcRow = (if (bottomUp) height - 1 - y else y) * width * 4
            val dstRow = y * width
            var j = srcRow
            for (x in 0 until width) {
                val bin = (((rgba[j].toInt() and 0xFF) shr 3) shl 10) or
                    (((rgba[j + 1].toInt() and 0xFF) shr 3) shl 5) or
                    ((rgba[j + 2].toInt() and 0xFF) shr 3)
                indices[dstRow + x] = binIndex[bin].toByte()
                j += 4
            }
        }
        return Result(palette, indices)
    }
}

/** The GIF LZW compressor: [indices] (unsigned bytes) -> the compressed data stream (no sub-block chunking). */
object GifLzw {

    fun compress(indices: ByteArray, minCodeSize: Int): ByteArray {
        val min = minCodeSize.coerceIn(2, 8)
        val clear = 1 shl min
        val eoi = clear + 1
        val out = java.io.ByteArrayOutputStream(indices.size / 2 + 16)
        var bitBuf = 0
        var bitCnt = 0
        fun emit(code: Int, width: Int) {
            bitBuf = bitBuf or (code shl bitCnt)
            bitCnt += width
            while (bitCnt >= 8) {
                out.write(bitBuf and 0xFF)
                bitBuf = bitBuf ushr 8
                bitCnt -= 8
            }
        }

        val dict = HashMap<Int, Int>()
        var nextCode = eoi + 1
        var width = min + 1
        emit(clear, width)
        if (indices.isEmpty()) {
            emit(eoi, width)
            if (bitCnt > 0) out.write(bitBuf and 0xFF)
            return out.toByteArray()
        }
        var prefix = indices[0].toInt() and 0xFF
        for (i in 1 until indices.size) {
            val k = indices[i].toInt() and 0xFF
            val key = (prefix shl 8) or k
            val hit = dict[key]
            if (hit != null) {
                prefix = hit
            } else {
                emit(prefix, width)
                if (nextCode < 4096) {
                    dict[key] = nextCode++
                    // The decoder (giflib rule) widens one entry behind the encoder;
                    // widening when the next code reaches 2^width keeps the two in step.
                    if (nextCode == (1 shl width) && width < 12) width++
                } else {
                    emit(clear, width)
                    dict.clear()
                    nextCode = eoi + 1
                    width = min + 1
                }
                prefix = k
            }
        }
        emit(prefix, width)
        emit(eoi, width)
        if (bitCnt > 0) out.write(bitBuf and 0xFF)
        return out.toByteArray()
    }
}

/**
 * Streaming GIF89a writer. The header (with the loop-forever block) is written on
 * construction; [addFrame] appends one frame with its own local colour table;
 * [finish] writes the trailer and flushes (the caller still owns closing the stream).
 */
class GifWriter(private val out: OutputStream, val width: Int, val height: Int) {

    init {
        require(width in 1..65535 && height in 1..65535) { "bad GIF size ${width}x$height" }
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(width)
        writeShort(height)
        out.write(0x70) // no global colour table; 8-bit colour resolution
        out.write(0)    // background colour index (unused, no GCT)
        out.write(0)    // pixel aspect ratio
        // NETSCAPE2.0 looping extension, loop count 0 = forever.
        out.write(0x21); out.write(0xFF); out.write(0x0B)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(0x03); out.write(0x01); writeShort(0); out.write(0)
    }

    /**
     * One frame: [indices] row-major top-down (width × height entries), [palette]
     * 0xRRGGBB entries with a power-of-two size in 2..256 (as [GifQuantizer] returns),
     * [delayCs] the frame delay in hundredths of a second.
     */
    fun addFrame(indices: ByteArray, palette: IntArray, delayCs: Int) {
        require(indices.size == width * height) { "frame has ${indices.size} pixels, expected ${width * height}" }
        require(palette.size in 2..256 && (palette.size and (palette.size - 1)) == 0) {
            "palette size ${palette.size} is not a power of two in 2..256"
        }
        // Graphic Control Extension: disposal 1 (do not dispose), no transparency.
        out.write(0x21); out.write(0xF9); out.write(0x04)
        out.write(0x04)
        writeShort(delayCs.coerceIn(0, 65535))
        out.write(0); out.write(0)
        // Image Descriptor with a local colour table.
        out.write(0x2C)
        writeShort(0); writeShort(0); writeShort(width); writeShort(height)
        var log2 = 0
        while ((1 shl (log2 + 1)) < palette.size) log2++
        out.write(0x80 or log2) // LCT present, size = 2^(log2+1)
        for (rgb in palette) {
            out.write((rgb shr 16) and 0xFF)
            out.write((rgb shr 8) and 0xFF)
            out.write(rgb and 0xFF)
        }
        val minCodeSize = max(2, log2 + 1)
        out.write(minCodeSize)
        val data = GifLzw.compress(indices, minCodeSize)
        var off = 0
        while (off < data.size) {
            val n = minOf(255, data.size - off)
            out.write(n)
            out.write(data, off, n)
            off += n
        }
        out.write(0) // block terminator
    }

    fun finish() {
        out.write(0x3B)
        out.flush()
    }

    private fun writeShort(v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }
}

/** Sizes, delays and pixel conversion for the raster exports (GIF + PNG sequence), shared by the exporter and its options UI. */
object GifExport {

    /** GIF frame rate default (the export dialog's GIF rate starts here). */
    const val DEFAULT_FPS = 15
    const val MIN_FPS = 5
    const val MAX_FPS = 30

    /** Longest side of a GIF frame: past this, a phone GIF is mostly download time. */
    const val MAX_SIDE = 720

    /**
     * Frame cap so a long project cannot explode into a multi-hundred-MB GIF
     * (at the 15 fps default this is 100 seconds of project). The exporter refuses
     * past it with a message instead of producing the file.
     */
    const val MAX_FRAMES = 1500

    /** Frame delay in hundredths of a second for [fps], at least 2 (viewers force 0–1 up to ~100 ms). */
    fun delayCs(fps: Int): Int =
        (100.0 / fps.coerceIn(1, 100)).roundToInt().coerceAtLeast(2)

    /** [srcW]×[srcH] scaled to fit [MAX_SIDE] on the longest side, both sides even and ≥ 2; unchanged when already small. */
    fun gifSize(srcW: Int, srcH: Int): Pair<Int, Int> {
        val m = max(srcW, srcH)
        if (m <= MAX_SIDE) return srcW to srcH
        val k = MAX_SIDE.toFloat() / m
        fun even(x: Float) = ((x.roundToInt() / 2) * 2).coerceAtLeast(2)
        return even(srcW * k) to even(srcH * k)
    }

    /** GL readback (RGBA, bottom row first) -> ARGB ints, top row first, alpha forced opaque (the frame is a finished composite). */
    fun rgbaBottomUpToArgb(rgba: ByteArray, width: Int, height: Int): IntArray {
        val out = IntArray(width * height)
        for (y in 0 until height) {
            var j = (height - 1 - y) * width * 4
            val dst = y * width
            for (x in 0 until width) {
                val r = rgba[j].toInt() and 0xFF
                val g = rgba[j + 1].toInt() and 0xFF
                val b = rgba[j + 2].toInt() and 0xFF
                out[dst + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                j += 4
            }
        }
        return out
    }
}
