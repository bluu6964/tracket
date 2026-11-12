package com.example.engine

import com.example.model.TextAnimator
import com.example.model.TimelineClip
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

/**
 * Evaluates an imported Alight Motion `counter` animator for one frame — Alight's "Count Up/Down"
 * effect — and produces the text the layer actually shows. The effect is text-only by
 * construction (it rewrites the layer's characters); the importer notes a `counter` on a
 * non-text layer as a no-op rather than an unsupported effect. Pure Kotlin (no Android classes) so it can
 * be unit tested. Both render paths substitute the string BEFORE drawing ([NativeTextPainter] hands
 * the counted string to the C++ text engine, [LayerPainter] cuts/draws it), so the engine lays out
 * the counted value itself; no C++ change was needed.
 *
 * Semantics — from Alight's own Help Center / Effect Guide for "Count Up/Down", NOT guessed:
 *  - "Count Up/Down changes any number in a text layer into a manipulatable counter... **Scale** is
 *    multiplied to the original number in the text layer. **Offset** is added to the product."
 *    Displayed = original × scale + offset, for EVERY number in the text; non-numeric text is
 *    unaffected, and a layer with no number in it shows unchanged.
 *  - The XML property names are `offset` (the guide's "Add", default 0) and `scale` ("Multiply",
 *    default 1). Keyframing either over time produces the counting (the guide's count-up recipe:
 *    type the target number, animate Multiply 0 -> 1; countdown to zero: Multiply 1 -> 0).
 *
 * The corpus's two instances:
 * ```
 * 7e363d75…xml (animated graph), on a text layer whose content is "0", 0..7032 ms:
 *   <effect id="com.alightcreative.effects.counter" locallyApplied="true">
 *     <property name="offset" type="float"><kf t="0.002276" v="0" /><kf t="0.286689" v="12" e="cubicBezier …" /></property>
 *     <property name="scale" type="float" value="0.14" /></effect>
 *   -> the original is 0, so the display is the offset track itself: it counts 0 -> 12 in the first
 *      ~2 s (under an "Hours" label) and then holds 12.
 * collected/shake-997-vetri-editz.xml: the same effect on a media-filled SHAPE — no text to count,
 *   so the importer keeps it a warning there and this evaluator never sees it.
 * ```
 *
 * Formatting (Alight does not document it and the corpus's only counted text is "0", so these are the
 * chosen conventions, picked to match what the guide's own examples imply):
 *  - the result is shown with the SAME number of fraction digits as the original token: an integer
 *    original counts in integers ("0" -> "12"), "0.5" doubled shows "1.0";
 *  - rounding at that precision is half away from zero (2.5 -> "3");
 *  - comma grouping is re-applied (Western, every 3 digits) when the original token was grouped —
 *    the guide's own count-up example types the target as "100,000", which only settles correctly if
 *    the grouping survives; an ungrouped original stays ungrouped;
 *  - a leading '-' directly attached to a number is part of it ("-5" × 2 -> "-10"); a dash after a
 *    digit is text ("5-3" × 2 -> "10-6").
 *
 * Easing: animator keyframes carry their Alight easing (the `e` attribute of the keyframe a
 * segment ends at) through [AnimKey] into [TextAnimEval.valueAt], which applies it — the graph
 * counter's cubicBezier-paced count is fast-start here exactly as in Alight (11 of 12 at 1.0 s;
 * it ran linear, showing 6, until the Graph round-6 link). When a layer also
 * carries `textprogress` / `texttransform`, the counter substitutes FIRST and those effects operate
 * on the counted string (no corpus layer combines them; it is the only order in which they see the
 * text that is actually shown).
 */
internal object CounterEval {

    class Result(val offset: Float, val scale: Float)

    /** Null = the clip has no counter animator. */
    fun evaluate(clip: TimelineClip, timeMs: Long): Result? {
        val a = clip.textAnimators.firstOrNull { it.effectId == AlightXmlImporter.COUNTER } ?: return null
        val dur = clip.durationMs.coerceAtLeast(1L)
        val p = ((timeMs - clip.startMs).toFloat() / dur).coerceIn(0f, 1f)
        return evaluate(a, p)
    }

    /** [p]: 0..1 of the layer's length. Constants and keyframes come from [TextAnimEval.valueAt]. */
    fun evaluate(a: TextAnimator, p: Float): Result {
        fun v(name: String): Float? = a.props[name]?.let { TextAnimEval.valueAt(it, p) }?.firstOrNull()
        return Result(offset = v("offset") ?: 0f, scale = v("scale") ?: 1f)
    }

    /**
     * The text the layer shows at [timeMs] with the counter applied, or null when the clip has no
     * counter animator (the caller then draws the authored [text]).
     */
    fun displayText(text: String, clip: TimelineClip, timeMs: Long): String? {
        val r = evaluate(clip, timeMs) ?: return null
        return substitute(text, r.offset.toDouble(), r.scale.toDouble())
    }

    /** Every number in [text] replaced by number × [scale] + [offset]; everything else kept as is. */
    fun substitute(text: String, offset: Double, scale: Double): String {
        if (text.none { it.isDigit() }) return text
        val sb = StringBuilder(text.length + 8)
        var i = 0
        while (i < text.length) {
            val tok = scanNumber(text, i)
            if (tok == null) {
                sb.append(text[i])
                i++
            } else {
                sb.append(format(tok.value * scale + offset, tok.decimals, tok.grouped))
                i = tok.end
            }
        }
        return sb.toString()
    }

    private class Token(val end: Int, val value: Double, val decimals: Int, val grouped: Boolean)

    /**
     * The number token starting exactly at [from], or null. A token is: an optional '-' (only when it
     * is not itself the tail of a number and a digit or ".digit" follows), digits with ',' grouping
     * (a comma counts only when a digit follows it), and an optional fraction ('.' + digits — the dot
     * counts only when a digit follows it). A leading '.' (".5") also starts a token when it does not
     * follow a digit or another dot.
     */
    private fun scanNumber(text: String, from: Int): Token? {
        val n = text.length
        var i = from
        var neg = false
        val c = text[i]
        if (c == '-') {
            val prev = if (from > 0) text[from - 1] else ' '
            if (prev.isDigit() || prev == '.' || prev == ',' || prev == '-') return null
            val ok = from + 1 < n &&
                (text[from + 1].isDigit() || (text[from + 1] == '.' && from + 2 < n && text[from + 2].isDigit()))
            if (!ok) return null
            neg = true
            i++
        } else if (c == '.') {
            val prev = if (from > 0) text[from - 1] else ' '
            if (prev.isDigit() || prev == '.') return null
            if (from + 1 >= n || !text[from + 1].isDigit()) return null
        } else if (!c.isDigit()) {
            return null
        }
        val intDigits = StringBuilder()
        var grouped = false
        while (i < n) {
            val ch = text[i]
            if (ch.isDigit()) {
                intDigits.append(ch)
                i++
            } else if (ch == ',' && intDigits.isNotEmpty() && i + 1 < n && text[i + 1].isDigit()) {
                grouped = true
                i++
            } else break
        }
        var fracDigits = ""
        if (i < n && text[i] == '.' && i + 1 < n && text[i + 1].isDigit()) {
            val start = i + 1
            var j = start
            while (j < n && text[j].isDigit()) j++
            fracDigits = text.substring(start, j)
            i = j
        }
        if (intDigits.isEmpty() && fracDigits.isEmpty()) return null
        val magnitude = (intDigits.toString() + if (fracDigits.isNotEmpty()) "." + fracDigits else "").toDouble()
        return Token(
            end = i,
            value = if (neg) -magnitude else magnitude,
            decimals = fracDigits.length,
            grouped = grouped
        )
    }

    /**
     * [v] with exactly [decimals] fraction digits, rounded half away from zero, the integer part
     * comma-grouped when [grouped]. Digit-string arithmetic (via units of 10^-[decimals]) keeps the
     * result deterministic; absurd magnitudes (far beyond the effect's own ranges — offset ±100000,
     * scale ±2 — acting on a readable text number) fall back to plain fixed-point.
     */
    private fun format(v: Double, decimals: Int, grouped: Boolean): String {
        val pow10 = 10.0.pow(decimals)
        val scaled = v * pow10
        if (!scaled.isFinite() || abs(scaled) > 9.0e15) {
            return String.format(java.util.Locale.US, "%.${decimals}f", v)
        }
        val units = if (scaled >= 0) floor(scaled + 0.5).toLong() else ceil(scaled - 0.5).toLong()
        val neg = units < 0
        val a = abs(units)
        val powL = pow10.toLong()
        var intStr = (a / powL).toString()
        if (grouped) intStr = groupThousands(intStr)
        val sb = StringBuilder()
        if (neg) sb.append('-')
        sb.append(intStr)
        if (decimals > 0) sb.append('.').append((a % powL).toString().padStart(decimals, '0'))
        return sb.toString()
    }

    private fun groupThousands(s: String): String {
        if (s.length <= 3) return s
        val sb = StringBuilder()
        var i = s.length % 3
        if (i > 0) sb.append(s, 0, i)
        while (i < s.length) {
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(s, i, i + 3)
            i += 3
        }
        return sb.toString()
    }
}
