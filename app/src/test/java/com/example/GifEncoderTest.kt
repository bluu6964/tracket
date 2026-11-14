package com.example

import com.example.model.GifExport
import com.example.model.GifLzw
import com.example.model.GifQuantizer
import com.example.model.GifWriter
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * The GIF89a export core (model/GifEncoder.kt, build 189): median-cut quantization
 * (palette never over 256 colours, exact averages for flat frames, bottom-up GL
 * readback equals the top-down picture), the LZW compressor (round-tripped through
 * an independent decoder written from the GIF spec, including the code-width
 * growth rule and the 4096-entry table reset), and the writer's container bytes
 * (header, NETSCAPE loop-forever, per-frame delays, local palettes, trailer)
 * parsed back by an independent parser below.
 */
class GifEncoderTest {

    // ---------------------------------------------------------------- quantizer

    private fun rgbaFrame(w: Int, h: Int, px: (Int, Int) -> Triple<Int, Int, Int>): ByteArray {
        val buf = ByteArray(w * h * 4)
        var i = 0
        for (y in 0 until h) for (x in 0 until w) {
            val (r, g, b) = px(x, y)
            buf[i] = r.toByte(); buf[i + 1] = g.toByte(); buf[i + 2] = b.toByte(); buf[i + 3] = 255.toByte()
            i += 4
        }
        return buf
    }

    @Test
    fun quantizeNeverExceeds256Colors() {
        val rnd = Random(1234)
        val buf = ByteArray(64 * 64 * 4)
        rnd.nextBytes(buf)
        val q = GifQuantizer.quantize(buf, 64, 64)
        assertTrue("palette ${q.palette.size}", q.palette.size <= 256)
        assertTrue("palette is a power of two", q.palette.size and (q.palette.size - 1) == 0)
        assertEquals(64 * 64, q.indices.size)
        for (idx in q.indices) assertTrue((idx.toInt() and 0xFF) < q.palette.size)
    }

    @Test
    fun quantizeSingleColorFrameIsExact() {
        val buf = rgbaFrame(8, 8) { _, _ -> Triple(200, 30, 90) }
        val q = GifQuantizer.quantize(buf, 8, 8)
        assertEquals(0xC81E5A, q.palette[0])
        assertTrue(q.indices.all { it == 0.toByte() })
    }

    @Test
    fun quantizeTwoFlatColorsAreExact() {
        val buf = rgbaFrame(16, 4) { x, _ -> if (x < 8) Triple(255, 0, 0) else Triple(0, 0, 255) }
        val q = GifQuantizer.quantize(buf, 16, 4)
        val red = q.palette.indexOf(0xFF0000)
        val blue = q.palette.indexOf(0x0000FF)
        assertTrue("red in palette", red >= 0)
        assertTrue("blue in palette", blue >= 0)
        for (y in 0 until 4) for (x in 0 until 16) {
            val idx = q.indices[y * 16 + x].toInt() and 0xFF
            assertEquals(if (x < 8) red else blue, idx)
        }
    }

    @Test
    fun quantizeBottomUpMatchesTopDown() {
        val w = 5; val h = 3
        val top = rgbaFrame(w, h) { x, y -> Triple(x * 40 + y * 7, y * 60 + x * 3, (x + y) * 25) }
        val flipped = ByteArray(top.size)
        val rowBytes = w * 4
        for (y in 0 until h) {
            System.arraycopy(top, (h - 1 - y) * rowBytes, flipped, y * rowBytes, rowBytes)
        }
        val a = GifQuantizer.quantize(top, w, h)
        val b = GifQuantizer.quantize(flipped, w, h, bottomUp = true)
        assertIntsEq(a.palette, b.palette)
        assertBytesEq(a.indices, b.indices)
    }

    // --------------------------------------------------------------------- LZW

    @Test
    fun lzwRoundTripPatterned() {
        val data = ByteArray(5000) { i -> ((i / 7) % 4).toByte() }
        assertBytesEq(data, lzwDecode(GifLzw.compress(data, 2), 2))
    }

    @Test
    fun lzwRoundTripSinglePixelAndEmpty() {
        assertBytesEq(byteArrayOf(5), lzwDecode(GifLzw.compress(byteArrayOf(5), 8), 8))
        assertBytesEq(ByteArray(0), lzwDecode(GifLzw.compress(ByteArray(0), 8), 8))
    }

    @Test
    fun lzwRoundTripLongRun() {
        val data = ByteArray(50_000) { 3 }
        assertBytesEq(data, lzwDecode(GifLzw.compress(data, 2), 2))
    }

    @Test
    fun lzwRoundTripRandomForcesTableReset() {
        val rnd = Random(99)
        val data = ByteArray(200_000) { rnd.nextInt(256).toByte() }
        assertBytesEq(data, lzwDecode(GifLzw.compress(data, 8), 8))
    }

    // ------------------------------------------------------------------ writer

    @Test
    fun writerHeaderLoopDelaysAndTrailer() {
        val palette = intArrayOf(0x000000, 0xFF0000, 0x00FF00, 0x0000FF)
        val frame = ByteArray(4 * 3) { (it % 4).toByte() }
        val bos = ByteArrayOutputStream()
        val writer = GifWriter(bos, 4, 3)
        writer.addFrame(frame, palette, 7)
        writer.addFrame(frame, palette, 20)
        writer.finish()
        val bytes = bos.toByteArray()
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        assertEquals(0x3B, bytes.last().toInt() and 0xFF)
        val parsed = parseGif(bytes)
        assertEquals(4, parsed.width)
        assertEquals(3, parsed.height)
        assertEquals(0, parsed.loopCount)
        assertEquals(2, parsed.frames.size)
        assertEquals(7, parsed.frames[0].delayCs)
        assertEquals(20, parsed.frames[1].delayCs)
    }

    @Test
    fun writerFramesDecodeToTheInputIndices() {
        val palette1 = intArrayOf(0x102030, 0xFF0000, 0x00FF00, 0x0000FF)
        val frame1 = ByteArray(6 * 5) { ((it * 7) % 4).toByte() }
        val palette2 = intArrayOf(0x000000, 0xFFFFFF)
        val frame2 = ByteArray(6 * 5) { (it % 2).toByte() }
        val bos = ByteArrayOutputStream()
        val writer = GifWriter(bos, 6, 5)
        writer.addFrame(frame1, palette1, 10)
        writer.addFrame(frame2, palette2, 33)
        writer.finish()
        val parsed = parseGif(bos.toByteArray())
        assertEquals(2, parsed.frames.size)
        assertIntsEq(palette1, parsed.frames[0].palette)
        assertIntsEq(palette2, parsed.frames[1].palette)
        assertBytesEq(frame1, parsed.frames[0].indices)
        assertBytesEq(frame2, parsed.frames[1].indices)
    }

    @Test
    fun writerCarriesQuantizedFrames() {
        val buf = rgbaFrame(32, 32) { x, y -> Triple(x * 8, y * 8, (x + y) * 4) }
        val q = GifQuantizer.quantize(buf, 32, 32)
        val bos = ByteArrayOutputStream()
        val writer = GifWriter(bos, 32, 32)
        repeat(3) { writer.addFrame(q.indices, q.palette, GifExport.delayCs(15)) }
        writer.finish()
        val parsed = parseGif(bos.toByteArray())
        assertEquals(3, parsed.frames.size)
        for (f in parsed.frames) {
            assertBytesEq(q.indices, f.indices)
            assertEquals(7, f.delayCs)
        }
    }

    // ----------------------------------------------------------------- helpers

    @Test
    fun delayCsMapping() {
        assertEquals(20, GifExport.delayCs(5))
        assertEquals(10, GifExport.delayCs(10))
        assertEquals(7, GifExport.delayCs(15))
        assertEquals(4, GifExport.delayCs(24))
        assertEquals(3, GifExport.delayCs(30))
        assertEquals(2, GifExport.delayCs(100)) // clamped: viewers force tiny delays up anyway
    }

    @Test
    fun gifSizeCapsTheLongestSide() {
        assertEquals(404 to 720, GifExport.gifSize(1080, 1920))
        assertEquals(720 to 404, GifExport.gifSize(1920, 1080))
        assertEquals(720 to 480, GifExport.gifSize(720, 480))
        assertEquals(300 to 200, GifExport.gifSize(300, 200))
    }

    @Test
    fun rgbaBottomUpToArgbFlipsAndConverts() {
        // Buffer rows are bottom-first: bottom row = red, green; top row = blue, white.
        val buf = byteArrayOf(
            255.toByte(), 0, 0, 255.toByte(), 0, 255.toByte(), 0, 255.toByte(),
            0, 0, 255.toByte(), 255.toByte(), 255.toByte(), 255.toByte(), 255.toByte(), 255.toByte()
        )
        val argb = GifExport.rgbaBottomUpToArgb(buf, 2, 2)
        assertIntsEq(
            intArrayOf(0xFF0000FF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt()),
            argb
        )
    }

    // The harness's JUnit stub has no assertArrayEquals; these two cover the array types used here.
    private fun assertBytesEq(expected: ByteArray, actual: ByteArray) {
        assertTrue("byte arrays differ (sizes ${expected.size} vs ${actual.size})", expected.contentEquals(actual))
    }

    private fun assertIntsEq(expected: IntArray, actual: IntArray) {
        assertTrue("int arrays differ (sizes ${expected.size} vs ${actual.size})", expected.contentEquals(actual))
    }

    // ------------------------------------------- independent GIF parser + decoder

    private class ParsedFrame(val delayCs: Int, val palette: IntArray, val indices: ByteArray)
    private class ParsedGif(val width: Int, val height: Int, val loopCount: Int?, val frames: List<ParsedFrame>)

    private fun parseGif(bytes: ByteArray): ParsedGif {
        var pos = 0
        fun u8(): Int {
            if (pos >= bytes.size) fail("unexpected end of GIF")
            return bytes[pos++].toInt() and 0xFF
        }
        fun u16(): Int = u8() or (u8() shl 8)
        fun subBlocks(): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val n = u8()
                if (n == 0) break
                repeat(n) { out.write(u8()) }
            }
            return out.toByteArray()
        }
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        pos = 6
        val w = u16(); val h = u16()
        val packed = u8(); u8(); u8()
        if (packed and 0x80 != 0) {
            val gctSize = 1 shl ((packed and 0x07) + 1)
            pos += gctSize * 3
        }
        var loop: Int? = null
        val frames = mutableListOf<ParsedFrame>()
        var delay = -1
        while (true) {
            when (val tag = u8()) {
                0x3B -> return ParsedGif(w, h, loop, frames)
                0x21 -> when (u8()) {
                    0xF9 -> {
                        assertEquals(4, u8())
                        u8() // packed (disposal / transparency)
                        delay = u16()
                        u8()
                        assertEquals(0, u8())
                    }
                    0xFF -> {
                        val appLen = u8()
                        val app = String(bytes, pos, appLen, Charsets.US_ASCII); pos += appLen
                        val data = subBlocks()
                        if (app == "NETSCAPE2.0" && data.size >= 3 && data[0].toInt() == 1) {
                            loop = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
                        }
                    }
                    else -> subBlocks()
                }
                0x2C -> {
                    u16(); u16(); u16(); u16()
                    val p = u8()
                    var palette = IntArray(0)
                    if (p and 0x80 != 0) {
                        val size = 1 shl ((p and 0x07) + 1)
                        palette = IntArray(size) {
                            val r = u8(); val g = u8(); val b = u8()
                            (r shl 16) or (g shl 8) or b
                        }
                    }
                    val minCode = u8()
                    val indices = lzwDecode(subBlocks(), minCode)
                    assertEquals(w * h, indices.size)
                    frames.add(ParsedFrame(delay, palette, indices))
                    delay = -1
                }
                else -> fail("unknown GIF block 0x${tag.toString(16)}")
            }
        }
    }

    /** LZW decoder written from the GIF spec (dictionary lags the encoder by one entry; KwKwK handled). */
    private fun lzwDecode(data: ByteArray, minCodeSize: Int): ByteArray {
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        val dict = ArrayList<ByteArray>()
        fun reset() {
            dict.clear()
            for (i in 0 until clear) dict.add(byteArrayOf(i.toByte()))
            dict.add(ByteArray(0)) // clear code slot
            dict.add(ByteArray(0)) // EOI slot
        }
        reset()
        var width = minCodeSize + 1
        var bitPos = 0
        fun readCode(): Int {
            if (bitPos + width > data.size * 8) return -1
            var code = 0
            for (i in 0 until width) {
                val bit = (data[(bitPos + i) / 8].toInt() ushr ((bitPos + i) % 8)) and 1
                code = code or (bit shl i)
            }
            bitPos += width
            return code
        }
        val out = ByteArrayOutputStream()
        var prev: ByteArray? = null
        while (true) {
            val code = readCode()
            if (code < 0 || code == eoi) break
            if (code == clear) {
                reset(); width = minCodeSize + 1; prev = null; continue
            }
            val p = prev
            val entry = when {
                code < dict.size -> dict[code]
                code == dict.size && p != null -> p + p[0]
                else -> throw AssertionError("bad LZW code $code, dictionary size ${dict.size}")
            }
            out.write(entry)
            if (p != null) {
                dict.add(p + entry[0])
                val addedCode = dict.size - 1
                if (addedCode == (1 shl width) - 2 && width < 12) width++
            }
            prev = entry
        }
        return out.toByteArray()
    }
}
