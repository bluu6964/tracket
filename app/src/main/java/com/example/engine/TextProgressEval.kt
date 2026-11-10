package com.example.engine

import com.example.model.TextAnimator
import com.example.model.TimelineClip
import kotlin.math.max
import kotlin.math.min

/**
 * Evaluates an imported Alight Motion `textprogress` animator for one frame: the typewriter reveal.
 * Pure Kotlin (no Android classes) so it can be unit tested. [TextNative] hands the result to the C++ text
 * engine, which draws only the clusters inside the window; the Canvas fallback ([LayerPainter]) cuts the
 * string with [visibleText] instead — same window, same counting.
 *
 * The property set is the one seen in the corpus (ST Intro template, `1c30cfdb…xml`, on the text
 * "Channel Name", appearing twice — the scene and its copy):
 * ```
 * <effect id="com.alightcreative.effects.textprogress" locallyApplied="true">
 *   <property name="start" type="float" value="0.000000" />
 *   <property name="end" type="float"><kf t="0.007613" v="0.000000" /><kf t="0.619794" v="1.000000" /></property>
 *   <property name="cursor" type="int" value="0" />
 *   <property name="blink" type="bool" value="false" />
 * </effect>
 * ```
 * Semantics (read from that file + the effect's name, NOT from Alight documentation):
 *  - `start`, `end` — floats 0..1: the window of the text that is SHOWN, as fractions of the whole text
 *    counted in grapheme clusters. `end` keyframed 0 -> 1 types the text in; a `start` above 0 hides the
 *    leading characters again. A cluster is shown only when its whole slice of the text lies inside the
 *    window (characters pop in one by one, they do not fade), so the number shown is floor-like:
 *    window [0, 0.6] over 5 clusters shows exactly 3.
 *  - `cursor` — int, a typing cursor drawn at the reveal edge. Only the corpus value 0 (= no cursor, the
 *    plain reading) is rendered; any other value keeps an importer warning ("cursor is not drawn yet"),
 *    because what the other values look like cannot be read from the corpus. [Result.blink] only matters
 *    for a cursor, so it is evaluated and reported but not drawn yet either.
 *
 * The cutting unit is the grapheme cluster on both paths: the C++ engine cuts by HarfBuzz cluster (a shaped
 * Bengali conjunct is one cluster, so it can never be split), this file cuts with the small UAX #29 subset
 * in [clustersOf] for the Canvas fallback. `java.text.BreakIterator` is deliberately NOT used: its rules
 * vary by platform and JDK version (the JDK 17 one splits "আমি" into আ + ম + ি, detaching the vowel sign),
 * while the segmenter here is deterministic and keeps Indic conjuncts and vowel signs whole.
 */
internal object TextProgressEval {

    class Result(
        val start: Float,
        val end: Float,
        val cursor: Int,
        val blink: Boolean
    )

    /** Null = the clip has no textprogress animator. */
    fun evaluate(clip: TimelineClip, timeMs: Long): Result? {
        val a = clip.textAnimators.firstOrNull { it.effectId == AlightXmlImporter.TEXTPROGRESS } ?: return null
        val dur = clip.durationMs.coerceAtLeast(1L)
        val p = ((timeMs - clip.startMs).toFloat() / dur).coerceIn(0f, 1f)
        return evaluate(a, p)
    }

    /** [p]: 0..1 of the layer's length. Constants and keyframes come from [TextAnimEval.valueAt]. */
    fun evaluate(a: TextAnimator, p: Float): Result {
        fun v(name: String): List<Float>? = a.props[name]?.let { TextAnimEval.valueAt(it, p) }
        val start = (v("start")?.firstOrNull() ?: 0f).coerceIn(0f, 1f)
        val end = (v("end")?.firstOrNull() ?: 1f).coerceIn(0f, 1f)
        val cursor = (v("cursor")?.firstOrNull() ?: 0f).toInt()
        val blink = (v("blink")?.firstOrNull() ?: 0f) > 0.5f
        return Result(start, end, cursor, blink)
    }

    /**
     * Is cluster [index] of [count] shown? Its slice [index/count, (index+1)/count] must lie fully inside
     * the window (the window's own ends are order-free, like the texttransform `arrived` rule in the C++
     * engine). The C++ side uses this very formula, so both paths show the same characters.
     */
    fun isVisible(index: Int, count: Int, start: Float, end: Float): Boolean {
        if (count <= 0 || index < 0 || index >= count) return false
        val lo = min(start, end).coerceIn(0f, 1f)
        val hi = max(start, end).coerceIn(0f, 1f)
        val a = max(index.toFloat() / count, lo)
        val b = min((index + 1).toFloat() / count, hi)
        return (b - a) * count > 0.999f
    }

    /** The visible cluster indices as one range (the window is an interval, so the shown set is contiguous). */
    fun visibleRange(count: Int, start: Float, end: Float): IntRange {
        if (count <= 0) return IntRange.EMPTY
        var first = -1
        var last = -1
        for (i in 0 until count) {
            if (isVisible(i, count, start, end)) {
                if (first < 0) first = i
                last = i
            }
        }
        return if (first < 0) IntRange.EMPTY else first..last
    }

    /** Indic viramas / linkers (GB9c): a consonant after one of these belongs to the same cluster. */
    private val linkers = setOf(0x09CD, 0x094D, 0x0A4D, 0x0ACD, 0x0B4D, 0x0BCD, 0x0C4D, 0x0CCD, 0x0D4D, 0x17D2)

    private fun isExtend(cp: Int): Boolean =
        cp == 0x200C || // ZWNJ is GCB Extend
            cp in 0x1F3FB..0x1F3FF || // emoji skin tones are GCB Extend (Character calls them symbols)
            Character.getType(cp) == Character.NON_SPACING_MARK.toInt() ||
            Character.getType(cp) == Character.ENCLOSING_MARK.toInt()

    private fun isSpacingMark(cp: Int): Boolean =
        Character.getType(cp) == Character.COMBINING_SPACING_MARK.toInt()

    /**
     * Grapheme clusters of [text]: a small, deterministic UAX #29 subset — break everywhere EXCEPT
     * before a mark (Extend / spacing mark / ZWNJ), around a ZWJ, inside a CRLF, between the two halves
     * of a regional-indicator pair, and before a consonant that follows an Indic linker inside the same
     * cluster (GB9c, so ক + ্ + ত stays one cluster). A newline is a cluster of its own. This matches
     * the HarfBuzz clusters the C++ engine cuts by for Latin, Bengali and emoji text; exotic scripts may
     * segment slightly differently there (the native path stays exact).
     */
    fun clustersOf(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var clusterStart = 0
        var j = 0
        val cps = ArrayList<Int>()
        val offs = ArrayList<Int>()
        while (j < text.length) {
            val cp = text.codePointAt(j)
            cps += cp
            offs += j
            j += Character.charCount(cp)
        }
        offs += text.length
        fun breakBefore(idx: Int): Boolean {
            val cp = cps[idx]
            val p = cps[idx - 1]
            if (p == '\r'.code && cp == '\n'.code) return false
            if (p == '\n'.code || p == '\r'.code || cp == '\n'.code || cp == '\r'.code) return true
            if (isExtend(cp) || isSpacingMark(cp) || cp == 0x200D) return false
            if (p == 0x200D) return false
            if (cp in 0x1F1E6..0x1F1FF && p in 0x1F1E6..0x1F1FF) {
                // no break inside a flag: count the RIs of the current cluster
                var n = 0
                var k = idx - 1
                while (k >= 0 && cps[k] in 0x1F1E6..0x1F1FF) { n++; k-- }
                return n % 2 == 0
            }
            if (p in linkers) {
                // GB9c: scan back over marks / linkers; a letter before them keeps the cluster whole
                var k = idx - 2
                while (k >= 0 && (isExtend(cps[k]) || isSpacingMark(cps[k]) || cps[k] in linkers)) k--
                if (k >= 0 && Character.isLetter(cps[k])) return false
            }
            return true
        }
        var i = 1
        while (i < cps.size) {
            if (breakBefore(i)) {
                out += text.substring(offs[clusterStart], offs[i])
                clusterStart = i
            }
            i++
        }
        out += text.substring(offs[clusterStart], offs[cps.size])
        return out
    }

    /**
     * The substring of [text] inside the [start, end] window — what the Canvas fallback draws. The native
     * engine keeps the full layout and hides the clusters instead, so centred text does not re-centre while
     * it types in there; the fallback re-lays the cut string out (its layout already differs from the
     * engine's: no chosen font, no texttransform).
     */
    fun visibleText(text: String, start: Float, end: Float): String {
        val clusters = clustersOf(text)
        if (clusters.isEmpty()) return ""
        val r = visibleRange(clusters.size, start, end)
        if (r.isEmpty()) return ""
        val sb = StringBuilder()
        for (i in r) sb.append(clusters[i])
        return sb.toString()
    }
}
