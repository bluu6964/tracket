package com.example.engine

/** Receives the results of one [WhisperNative.nativeTranscribe] run (called on the thread that runs it). */
interface WhisperListener {
    /** One recognised piece of speech. Times are in ms from the start of the audio that was passed in. */
    fun onSegment(startMs: Long, endMs: Long, utf8: ByteArray)

    /** Progress of the current block, 0..100. */
    fun onProgress(percent: Int)
}

/** Raw JNI functions of libwhisper_jni.so (see app/src/main/cpp). Use [WhisperEngine] instead. */
internal object WhisperNative {
    @JvmStatic external fun nativeInit(path: String): Long
    @JvmStatic external fun nativeFree(ctx: Long)
    @JvmStatic external fun nativeSetCancel(cancel: Boolean)
    @JvmStatic external fun nativeSystemInfo(): String
    @JvmStatic external fun nativeTranscribe(
        ctx: Long,
        samples: FloatArray,
        language: String,
        threads: Int,
        listener: WhisperListener
    ): Int
}
