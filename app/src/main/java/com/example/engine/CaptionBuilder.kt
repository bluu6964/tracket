package com.example.engine

/**
 * Pure caption logic (no Android classes, unit-tested): Whisper pieces -> captions.
 *
 * What it does, in order:
 *  1. drops Whisper's "stuck" repeats (the same sentence 3+ times in a row),
 *  2. cuts every piece into lines of at most `maxChars` characters (balanced lines, preferring a
 *     break after punctuation, never leaving a lonely short word on its own line),
 *  3. spreads the piece's time over its lines by their length,
 *  4. joins tiny neighbours ("Yes." + "I know.") when they fit into one line and nearly touch,
 *  5. converts the times to the timeline, then makes sure no two captions are on screen at once and
 *     no caption is lost because a neighbour started too early.
 */
object CaptionBuilder {

    /** One caption. Times are relative to the START of the layer it was made from, on the timeline. */
    data class Caption(val relStartMs: Long, val relEndMs: Long, val text: String)

    /** One recognised piece of speech in SOURCE time (ms from the start of the audio that was decoded). */
    class Piece(val startMs: Long, val endMs: Long, val text: String)

    /** A caption in source time (before the conversion to the timeline). */
    private class Line(var startMs: Long, var endMs: Long, var text: String)

    /** Default maximum characters per caption. */
    const val DEFAULT_MAX_CHARS = 30

    /** Shortest time a caption normally stays on screen. */
    const val MIN_CAPTION_MS = 500L

    /** A caption is never made shorter than this, even when the next one starts very soon. */
    private const val HARD_MIN_MS = 200L

    /** Two lines closer than this (source ms) may be joined into one. */
    private const val JOIN_GAP_MS = 250L

    /** A line shorter than this (characters) is a candidate for joining. */
    private const val TINY_CHARS = 9

    /** A caption stays this long after the last word, when the next caption leaves room. */
    private const val HOLD_MS = 150L

    // ---- Text -------------------------------------------------------------------------------

    private val WHITESPACE = Regex("\\s+")
    private const val STRONG_BREAK = ".?!।॥؟…"
    private const val WEAK_BREAK = ",;:،؛—–"

    private fun len(s: String) = s.codePointCount(0, s.length)

    private fun lastChar(word: String): Char {
        var i = word.length - 1
        // look through closing quotes / brackets: `ready."` still ends a sentence
        while (i > 0 && word[i] in "\"'”’)]»") i--
        return word[i]
    }

    /**
     * Cuts [text] at spaces into lines of at most [max] characters. The lines are balanced (not
     * "30 characters, then the rest"), a break after punctuation is preferred, and a single word
     * longer than [max] stays whole.
     */
    fun splitText(text: String, max: Int): List<String> {
        val t = text.trim().replace(WHITESPACE, " ")
        if (t.isEmpty()) return emptyList()
        val limit = max.coerceAtLeast(4)
        if (len(t) <= limit) return listOf(t)

        val words = t.split(' ')
        val n = words.size
        val wl = IntArray(n) { len(words[it]) }
        val best = DoubleArray(n + 1)
        val next = IntArray(n + 1)
        for (i in n - 1 downTo 0) {
            var lineLen = 0
            var bestCost = Double.MAX_VALUE
            var bestJ = i
            for (j in i until n) {
                lineLen += wl[j] + if (j > i) 1 else 0
                if (lineLen > limit && j > i) break
                val last = j == n - 1
                val slack = (limit - lineLen).coerceAtLeast(0)
                var cost = LINE_COST + slack.toDouble() * slack
                if (!last) {
                    val c = lastChar(words[j])
                    if (c in STRONG_BREAK) cost -= STRONG_BONUS else if (c in WEAK_BREAK) cost -= WEAK_BONUS
                }
                // a short word alone on its line looks bad (unless the text really is one word)
                if (j == i && n > 1 && wl[i] * 3 < limit) cost += ORPHAN_PENALTY
                val total = cost + best[j + 1]
                if (total < bestCost) {
                    bestCost = total
                    bestJ = j
                }
            }
            best[i] = bestCost
            next[i] = bestJ + 1
        }
        val lines = ArrayList<String>()
        var i = 0
        while (i < n) {
            val j = next[i]
            lines += words.subList(i, j).joinToString(" ")
            i = j
        }
        return lines
    }

    private const val LINE_COST = 25.0
    private const val STRONG_BONUS = 200.0
    private const val WEAK_BONUS = 110.0
    private const val ORPHAN_PENALTY = 150.0

    private fun normKey(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    // ---- Pieces -> captions -----------------------------------------------------------------

    /**
     * [pieces] are in source time, [toRel] converts source ms (relative to the start of the part of
     * the source that is used) to ms after the start of the layer on the timeline (trim + speed),
     * [layerDurationMs] is the layer's length on the timeline.
     */
    fun build(
        pieces: List<Piece>,
        maxChars: Int,
        layerDurationMs: Long,
        toRel: (Long) -> Long
    ): List<Caption> {
        // 1. Whisper sometimes loops on one sentence: keep it twice, drop the third repeat and later.
        val kept = ArrayList<Piece>()
        var lastKey = ""
        var run = 0
        for (p in pieces.sortedBy { it.startMs }) {
            val key = normKey(p.text)
            if (key.isEmpty()) continue
            run = if (key == lastKey) run + 1 else 1
            lastKey = key
            if (run <= 2) kept += p
        }

        // 2 + 3. lines, with the piece's time shared by line length
        val lines = ArrayList<Line>()
        for (p in kept) {
            val chunks = splitText(p.text, maxChars)
            if (chunks.isEmpty()) continue
            val lens = chunks.map { len(it).coerceAtLeast(1) }
            val total = lens.sum()
            val span = (p.endMs - p.startMs).coerceAtLeast(MIN_CAPTION_MS)
            var used = 0
            var cursor = p.startMs
            for ((k, chunk) in chunks.withIndex()) {
                used += lens[k]
                val end = if (k == chunks.lastIndex) p.startMs + span else p.startMs + span * used / total
                lines += Line(cursor, end, chunk)
                cursor = end
            }
        }

        // 4. join tiny neighbours that nearly touch and fit together
        val joined = ArrayList<Line>()
        for (l in lines) {
            val prev = joined.lastOrNull()
            if (prev != null &&
                l.startMs - prev.endMs <= JOIN_GAP_MS &&
                (len(prev.text) < TINY_CHARS || len(l.text) < TINY_CHARS) &&
                len(prev.text) + 1 + len(l.text) <= maxChars
            ) {
                prev.text = prev.text + " " + l.text
                prev.endMs = maxOf(prev.endMs, l.endMs)
            } else {
                joined += Line(l.startMs, l.endMs, l.text)
            }
        }

        // 5. to the timeline
        data class Rel(val start: Long, val end: Long, val text: String)
        val rel = ArrayList<Rel>()
        for (l in joined) {
            val s = toRel(l.startMs).coerceAtLeast(0L)
            if (s >= layerDurationMs) continue
            val e = toRel(l.endMs).coerceAtLeast(s)
            rel += Rel(s, e, l.text)
        }

        // Sweep: no overlap, nobody shorter than MIN_CAPTION_MS when there is room, nobody dropped
        // only because the next one starts early (it is kept at least HARD_MIN_MS, the next one is
        // pushed back a little instead).
        val out = ArrayList<Caption>()
        var prevEnd = 0L
        for ((i, c) in rel.withIndex()) {
            val start = maxOf(c.start, prevEnd)
            if (start >= layerDurationMs) break
            var end = maxOf(c.end + HOLD_MS, start + MIN_CAPTION_MS)
            val nextStart = rel.getOrNull(i + 1)?.start
            if (nextStart != null && end > nextStart) end = maxOf(nextStart, start + HARD_MIN_MS)
            end = minOf(end, layerDurationMs)
            if (end <= start) continue
            out += Caption(start, end, c.text)
            prevEnd = end
        }
        return out
    }

    /** "1:05" style time for progress labels. */
    fun clock(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        val m = s / 60
        val r = s % 60
        return if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, r) else "%d:%02d".format(m, r)
    }
}
