package com.example.engine

import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Speech to text with whisper.cpp (model: small, 5-bit quantised, see [WhisperModelDownloader]).
 *
 * One model is kept in memory while captions are being made and freed right after ([Session.close]),
 * because it takes about 500 MB of RAM.
 */
object WhisperEngine {

    private const val TAG = "WhisperEngine"

    @Volatile
    private var libraryLoaded = false

    /** Why Auto Captions cannot run on this phone, or null when it can. */
    fun unsupportedReason(): String? {
        if (Build.SUPPORTED_64_BIT_ABIS.none { it == "arm64-v8a" }) {
            return "Auto Captions needs a 64-bit ARM phone"
        }
        val features = cpuFeatures()
        if (features == null || !features.contains("asimddp") || !features.contains("asimdhp")) {
            return "This phone's processor is too old for Auto Captions"
        }
        return null
    }

    /** The CPU feature flags from /proc/cpuinfo ("Features" line), or null when unreadable. */
    private fun cpuFeatures(): Set<String>? = try {
        File("/proc/cpuinfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("Features") }
                ?.substringAfter(':')?.trim()?.split(' ')?.toSet()
        }
    } catch (e: Exception) {
        null
    }

    @Synchronized
    private fun loadLibrary() {
        if (libraryLoaded) return
        try {
            System.loadLibrary("whisper_jni")
        } catch (e: UnsatisfiedLinkError) {
            throw IOException("Speech engine is missing from this build (whisper.cpp not compiled in)")
        }
        libraryLoaded = true
        Log.i(TAG, "whisper: " + WhisperNative.nativeSystemInfo())
    }

    /** Number of CPU threads for decoding: half of the cores (the big ones), 2..4. */
    fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    /** Loads the model (about 1-3 s) and returns a session. Call [Session.close] when done. */
    fun open(modelFile: File): Session {
        unsupportedReason()?.let { throw IOException(it) }
        loadLibrary()
        val ctx = WhisperNative.nativeInit(modelFile.absolutePath)
        if (ctx == 0L) throw IOException("Could not load the speech model (delete it and download again)")
        return Session(ctx)
    }

    class Session internal constructor(private var ctx: Long) : AutoCloseable {

        /** Asks the running [transcribe] to stop as soon as possible (safe to call from any thread). */
        fun cancel() = WhisperNative.nativeSetCancel(true)

        /**
         * Recognises [samples] (16 kHz mono floats, -1..1; any length, whisper works in 30 s windows).
         * [segmentCallback] gets (start ms, end ms, text) relative to the start of [samples].
         * Returns false when it was cancelled or failed.
         */
        fun transcribe(
            samples: FloatArray,
            language: String,
            threads: Int = defaultThreads(),
            progressCallback: (Int) -> Unit,
            segmentCallback: (Long, Long, String) -> Unit
        ): Boolean {
            check(ctx != 0L) { "session is closed" }
            val listener = object : WhisperListener {
                override fun onSegment(startMs: Long, endMs: Long, utf8: ByteArray) {
                    segmentCallback(startMs, endMs, String(utf8, Charsets.UTF_8))
                }

                override fun onProgress(percent: Int) = progressCallback(percent)
            }
            return WhisperNative.nativeTranscribe(ctx, samples, language, threads, listener) == 0
        }

        fun resetCancel() = WhisperNative.nativeSetCancel(false)

        override fun close() {
            val c = ctx
            ctx = 0L
            if (c != 0L) WhisperNative.nativeFree(c)
        }
    }
}
