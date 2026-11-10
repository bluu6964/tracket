package com.example.engine

import android.content.Context
import com.example.model.AnimProp
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.sourceOffsetMs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException

/**
 * Auto Captions: speech of a video / audio layer -> short captions with times.
 *
 * Steps: decode the layer's sound (only the part that is used on the timeline) to 16 kHz mono ->
 * Whisper small (q5_1) in blocks of [BLOCK_SECONDS] -> cut every sentence into pieces of at most
 * [MAX_CHARS] characters -> times converted to the timeline (trim + speed of the layer).
 */
object AutoCaptions {

    /** What the progress bar shows: the label and the % always belong together. */
    data class Status(val label: String, val progress: Float)

    /** Caption length choices of the dialog: name -> maximum characters per caption. */
    val LENGTHS: List<Pair<String, Int>> = listOf(
        "Short (about 20 characters)" to 20,
        "Normal (about 30 characters)" to CaptionBuilder.DEFAULT_MAX_CHARS,
        "Long (about 45 characters)" to 45
    )

    /** Languages offered in the dialog: whisper code -> name. "auto" lets Whisper detect it. */
    val LANGUAGES: List<Pair<String, String>> = listOf(
        "auto" to "Auto-detect",
        "bn" to "বাংলা (Bengali)",
        "en" to "English",
        "hi" to "हिन्दी (Hindi)",
        "ur" to "اردو (Urdu)",
        "ar" to "العربية (Arabic)",
        "es" to "Español (Spanish)",
        "fr" to "Français (French)"
    )

    /** Audio is given to Whisper in pieces of this length (keeps memory low for long videos). */
    private const val BLOCK_SECONDS = 300

    /** A caption longer than this (in characters) is cut into several captions (default). */
    const val MAX_CHARS = CaptionBuilder.DEFAULT_MAX_CHARS

    fun canCaption(clip: TimelineClip): Boolean =
        clip.uri != null && !clip.isImage && (clip.type == LayerType.VIDEO || clip.type == LayerType.AUDIO)

    /**
     * Makes the captions of [clip]. Runs on the calling coroutine (use Dispatchers.Default / IO);
     * cancelling the coroutine stops Whisper within a moment. [onStatus] gets the stage label and
     * 0..1 over all steps together (see [CaptionProgress] for the slices). Captions are cut to
     * [maxChars] characters.
     */
    suspend fun generate(
        context: Context,
        clip: TimelineClip,
        language: String,
        maxChars: Int = MAX_CHARS,
        onStatus: (Status) -> Unit
    ): List<CaptionBuilder.Caption> {
        val uri = clip.uri ?: throw IOException("This layer has no media")
        val model = WhisperModelDownloader.modelFile(context)
        if (!WhisperModelDownloader.isDownloaded(context)) throw IOException("Speech model is not downloaded")

        // The part of the source that is really used on the timeline.
        val srcStart = clip.trimStartMs
        var srcEnd = srcStart + clip.sourceOffsetMs(clip.durationMs)
        if (clip.sourceDurationMs > 0L) srcEnd = minOf(srcEnd, clip.sourceDurationMs)
        if (srcEnd <= srcStart) throw IOException("Nothing to caption in this layer")

        val progress = CaptionProgress { label, p -> onStatus(Status(label, p)) }
        val dir = File(context.cacheDir, "whisper").apply { mkdirs() }
        val pcm = File(dir, "audio_${System.currentTimeMillis()}.pcm")
        var session: WhisperEngine.Session? = null
        try {
            progress.report(CaptionProgress.Stage.READ, 0f, "Reading the sound")
            val samples = WhisperAudio.decode(context, uri, srcStart, srcEnd, pcm) { p ->
                progress.report(CaptionProgress.Stage.READ, p, "Reading the sound")
            }
            if (samples < WhisperAudio.SAMPLE_RATE / 2) throw IOException("No speech found")

            currentCoroutineContext().ensureActive()
            progress.report(CaptionProgress.Stage.LOAD, 0f, "Loading the speech model")
            val whisper = WhisperEngine.open(model)
            session = whisper
            whisper.resetCancel()
            progress.report(CaptionProgress.Stage.LOAD, 1f, "Loading the speech model")

            val totalMs = samples * 1000L / WhisperAudio.SAMPLE_RATE
            val clock = SpeechClock(totalMs)
            // One place that turns the clock into the bar: the label shows the real audio time.
            val publish = {
                val at = clock.estimateMs()
                progress.report(
                    CaptionProgress.Stage.WRITE,
                    if (totalMs > 0L) at.toFloat() / totalMs else 1f,
                    "Writing captions " + CaptionBuilder.clock(at) + " / " + CaptionBuilder.clock(totalMs)
                )
            }
            publish()

            val blockSamples = BLOCK_SECONDS * WhisperAudio.SAMPLE_RATE
            val blocks = ((samples + blockSamples - 1) / blockSamples).toInt()
            val pieces = ArrayList<CaptionBuilder.Piece>()

            for (b in 0 until blocks) {
                currentCoroutineContext().ensureActive()
                val from = b.toLong() * blockSamples
                val block = WhisperAudio.readBlock(pcm, from, blockSamples)
                if (block.isEmpty()) break
                val blockStartMs = from * 1000L / WhisperAudio.SAMPLE_RATE
                val blockMs = block.size * 1000L / WhisperAudio.SAMPLE_RATE
                val ok = runCancellable(whisper, onTick = publish) {
                    whisper.transcribe(
                        samples = block,
                        language = language,
                        progressCallback = { pct ->
                            clock.reached(blockStartMs + blockMs * pct.coerceIn(0, 100) / 100L)
                            publish()
                        },
                        segmentCallback = { t0, t1, text ->
                            val clean = cleanText(text)
                            if (clean.isNotEmpty()) {
                                pieces += CaptionBuilder.Piece(blockStartMs + t0, blockStartMs + t1, clean)
                            }
                            clock.reached(blockStartMs + t1.coerceIn(0L, blockMs))
                            publish()
                        }
                    )
                }
                currentCoroutineContext().ensureActive()
                if (!ok) throw IOException("Speech recognition failed")
                clock.reached(blockStartMs + blockMs)
                publish()
            }

            progress.report(CaptionProgress.Stage.BUILD, 0f, "Preparing captions")
            val result = CaptionBuilder.build(
                pieces = pieces,
                maxChars = maxChars,
                layerDurationMs = clip.durationMs,
                toRel = { relFromSource(clip, it) }
            )
            progress.report(CaptionProgress.Stage.BUILD, 1f, "Preparing captions")
            return result
        } finally {
            session?.close()
            pcm.delete()
        }
    }

    /**
     * Runs [block] (Whisper, blocking) and makes sure a cancelled coroutine stops it: a helper thread
     * polls the coroutine and sets the cancel flag of the native side (whisper.cpp then stops within
     * a moment and [block] returns false). The same thread calls [onTick] about 5 times a second so
     * the bar keeps moving between Whisper's own reports.
     */
    private suspend fun <T> runCancellable(
        session: WhisperEngine.Session,
        onTick: () -> Unit,
        block: () -> T
    ): T {
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        val watcher = Thread {
            try {
                while (job != null && job.isActive) {
                    Thread.sleep(200)
                    if (job.isActive) onTick()
                }
                session.cancel()
            } catch (_: InterruptedException) {
            }
        }
        watcher.isDaemon = true
        watcher.start()
        try {
            return block()
        } finally {
            watcher.interrupt()
        }
    }

    /** Whisper puts "[BLANK_AUDIO]", "(music)", "♪" etc. in the text when there is no speech: those are dropped. */
    private fun cleanText(raw: String): String {
        val t = raw.replace('\n', ' ').trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return ""
        if (Regex("^[\\[(*♪].*[\\])*♪]$").matches(t)) return ""
        if (t.all { !it.isLetterOrDigit() }) return ""
        return t
    }

    /** Layer time (ms after the layer's start) at which the source has played [srcRelMs] ms (inverse of sourceOffsetMs). */
    private fun relFromSource(clip: TimelineClip, srcRelMs: Long): Long {
        if (srcRelMs <= 0L) return 0L
        if (clip.keyframes[AnimProp.SPEED].isNullOrEmpty()) {
            return (srcRelMs / clip.speed.coerceAtLeast(0.01f)).toLong()
        }
        var lo = 0L
        var hi = clip.durationMs
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (clip.sourceOffsetMs(mid) < srcRelMs) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
