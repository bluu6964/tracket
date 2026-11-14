package com.example.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The on-disk waveform peak cache format (build 199, audit #2 item O4).
 *
 * Decoding an audio file (or a video's soundtrack) to peaks costs a full pass over the media;
 * before build 199 the peaks lived only in the session, so every launch re-decoded every file.
 * The view-model now stores the peaks beside the app cache, keyed by [keyFor] — the media uri
 * plus its duration and byte size, the identity the app can actually verify. This object is the
 * pure half: key + encode/decode. Every decode is validated (magic, version, the exact duration
 * and size it was written for, a sane bucket count, an exact byte length), so a stale or corrupt
 * blob reads as "no cache" and the caller falls back to a fresh decode — a bad cache can never
 * crash or mislead the editor, at worst it costs one re-decode.
 */
object WaveformCache {

    private const val MAGIC = 0x4D435750 // "MCWP"
    private const val VERSION = 1
    private const val HEADER_BYTES = 28

    /** Sanity bound: 200k buckets at the engine's 50 ms bucket = ~2.8 hours of media. */
    private const val MAX_PEAKS = 200_000

    /**
     * The stable cache key for one media item. Same uri + duration + size always maps to the
     * same key; any of the three changing maps to a different one (the old blob is simply never
     * read again). A replaced file that keeps uri, duration AND size would still hit the old
     * peaks — the same identity limit the session cache had with the uri alone, strictly tighter.
     */
    fun keyFor(uri: String, durationMs: Long, sizeBytes: Long): String {
        var h = -3750763034362895579L // FNV-1a 64 offset basis
        val s = uri + '|' + durationMs + '|' + sizeBytes
        for (ch in s) {
            h = h xor ch.code.toLong()
            h *= 1099511628211L // FNV prime
        }
        return "wf_" + (h and Long.MAX_VALUE).toString(16)
    }

    /** Serializes [peaks] with the identity they were decoded for. */
    fun encode(peaks: FloatArray, durationMs: Long, sizeBytes: Long): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_BYTES + peaks.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MAGIC)
        buf.putInt(VERSION)
        buf.putLong(durationMs)
        buf.putLong(sizeBytes)
        buf.putInt(peaks.size)
        for (p in peaks) buf.putFloat(p)
        return buf.array()
    }

    /**
     * The peaks stored in [bytes], or null when the blob is not exactly a cache entry for
     * [durationMs]/[sizeBytes] — wrong magic or version, a different media identity, a nonsense
     * bucket count, or a truncated/extended payload all read as a cache miss.
     */
    fun decode(bytes: ByteArray, durationMs: Long, sizeBytes: Long): FloatArray? {
        if (bytes.size < HEADER_BYTES) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.getInt() != MAGIC) return null
        if (buf.getInt() != VERSION) return null
        if (buf.getLong() != durationMs) return null
        if (buf.getLong() != sizeBytes) return null
        val n = buf.getInt()
        if (n <= 0 || n > MAX_PEAKS) return null
        if (bytes.size != HEADER_BYTES + n * 4) return null
        return FloatArray(n) { buf.getFloat() }
    }
}
