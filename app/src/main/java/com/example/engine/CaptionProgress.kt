package com.example.engine

/**
 * Progress of Auto Captions as ONE bar: every stage owns a slice of 0..100 %, the value never goes
 * backwards, and the label always belongs to the value shown with it (both are sent together).
 * Thread-safe: Whisper's callbacks and the ticker thread report from different threads.
 *
 * The model download is NOT part of this bar: it is a separate step with its own 0..100 %.
 */
class CaptionProgress(private val sink: (label: String, progress: Float) -> Unit) {

    enum class Stage(val from: Float, val to: Float) {
        /** Decoding the layer's sound to 16 kHz mono. */
        READ(0.00f, 0.06f),

        /** Loading the speech model into memory (no real progress, a short step). */
        LOAD(0.06f, 0.10f),

        /** Whisper recognising the speech: the long part. */
        WRITE(0.10f, 0.97f),

        /** Cutting sentences into captions and fitting them to the timeline. */
        BUILD(0.97f, 0.99f),

        /** Adding the text layers (done by the screen). */
        PLACE(0.99f, 1.00f)
    }

    private var last = 0f
    private var lastLabel = ""

    /** [local] is 0..1 inside [stage]. Reports are ignored when they would move the bar back. */
    @Synchronized
    fun report(stage: Stage, local: Float, label: String) {
        val v = stage.from + (stage.to - stage.from) * local.coerceIn(0f, 1f)
        if (v < last) return
        if (v == last && label == lastLabel) return
        last = v
        lastLabel = label
        sink(label, v)
    }
}

/**
 * Where Whisper really is in the audio, plus a small honest estimate in between.
 *
 * Whisper reports only when a 30 s window is finished, so a plain bar would sit still and then
 * jump. [estimateMs] adds the time since the last real report multiplied by the speed measured
 * so far, but never more than 90 % of the next window, and never goes backwards. The real
 * position always wins as soon as Whisper reports it, so the bar ends exactly at the truth.
 */
class SpeechClock(
    private val totalMs: Long,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private var pos = 0L
    private var at = nowMs()
    private var speed = PRIOR_SPEED
    private var measured = false
    private var shown = 0L

    /** Whisper finished everything up to [positionMs] (audio ms). */
    @Synchronized
    fun reached(positionMs: Long) {
        val p = positionMs.coerceIn(0L, totalMs)
        if (p <= pos) return
        val t = nowMs()
        val dt = t - at
        if (dt >= 50L) {
            val sample = (p - pos).toDouble() / dt
            speed = if (measured) 0.5 * speed + 0.5 * sample else sample
            measured = true
        }
        pos = p
        at = t
        if (shown < p) shown = p
    }

    /** Position to show now (audio ms): real position + a capped creep, never decreasing, never past the end. */
    @Synchronized
    fun estimateMs(): Long {
        val dt = (nowMs() - at).coerceAtLeast(0L)
        val room = minOf(WINDOW_MS, totalMs - pos).coerceAtLeast(0L) * 0.9
        val est = pos + minOf(dt * speed, room).toLong()
        if (est > shown) shown = est
        return minOf(shown, totalMs)
    }

    /** The real position Whisper reported last. */
    @Synchronized
    fun realMs(): Long = pos

    private companion object {
        /** Whisper works in windows of 30 s. */
        const val WINDOW_MS = 30_000L

        /** Audio ms per wall ms before anything is measured. */
        const val PRIOR_SPEED = 1.5
    }
}
