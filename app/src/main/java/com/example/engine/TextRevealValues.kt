package com.example.engine

import com.example.model.AnimValue
import com.example.model.TextAnimator

/**
 * Value rules for the text editor's Reveal sliders ([com.example.ui.components.TextEditSheet],
 * Style tab) — the editor side of the Alight Motion `textprogress` animator (build 82 renders it).
 * Pure Kotlin, so the standalone harness can test it.
 *
 * DECISION (cycle 2026-11-11, roadmap item 1): the sliders edit a STATIC window only. An imported
 * keyframed reveal — a typewriter animation whose `start`/`end` change over the layer — is preserved
 * by default: while either property has keyframes the sliders stay hidden and the sheet says so.
 * Only an explicit "Use static window" tap replaces the keyframes with constants, frozen at the end
 * state (p = 1, i.e. what the text looks like when the typing finishes), so overwriting an imported
 * animation is always a deliberate gesture, never a slider accident. A constant animator (or none)
 * shows the sliders; dragging one creates/updates the constant animator, and the renderers draw the
 * window live in the preview at any playhead time, because a constant window is time-independent.
 */
object TextRevealValues {

    /** True when the animator's `start` or `end` is keyframed — an animated reveal, not a static window. */
    fun isKeyframed(animator: TextAnimator?): Boolean {
        if (animator == null) return false
        return animator.props["start"]?.keys?.isNotEmpty() == true ||
            animator.props["end"]?.keys?.isNotEmpty() == true
    }

    /**
     * The reveal window of [animator] at [p] (0..1 of the layer): constants, or keyframes evaluated
     * through [TextProgressEval] (the same evaluation the renderers use, so the sliders show exactly
     * what the preview draws). Absent animator = the full window (0, 1).
     */
    fun windowAt(animator: TextAnimator?, p: Float): Pair<Float, Float> {
        if (animator == null) return 0f to 1f
        val r = TextProgressEval.evaluate(animator, p)
        return coerceWindow(r.start, r.end)
    }

    /**
     * A window as 0..1 fractions: both clamped to 0..1 and ordered, so a start dragged past the end
     * pulls the end along instead of producing an inverted window. An empty window (start == end)
     * hides the whole text, exactly like the renderers treat it.
     */
    fun coerceWindow(start: Float, end: Float): Pair<Float, Float> {
        val s = start.coerceIn(0f, 1f)
        val e = end.coerceIn(s, 1f)
        return s to e
    }

    /**
     * The animators with the `textprogress` one replaced by a constant start/end window (or a new
     * constant animator appended when the layer has none). Every other animator is untouched and the
     * order is kept; the replaced animator keeps its other props (cursor, blink) and its refSize, so
     * only the window itself changes. A full window (0, 1) draws the whole text, so creating the
     * animator is visually a no-op until the user narrows it.
     */
    fun withStaticWindow(animators: List<TextAnimator>, start: Float, end: Float): List<TextAnimator> {
        val (s, e) = coerceWindow(start, end)
        val idx = animators.indexOfFirst { it.effectId == AlightXmlImporter.TEXTPROGRESS }
        if (idx < 0) {
            return animators + TextAnimator(
                effectId = AlightXmlImporter.TEXTPROGRESS,
                props = mapOf(
                    "start" to AnimValue(type = "float", value = listOf(s)),
                    "end" to AnimValue(type = "float", value = listOf(e))
                )
            )
        }
        val old = animators[idx]
        val props = old.props.toMutableMap()
        props["start"] = AnimValue(type = "float", value = listOf(s))
        props["end"] = AnimValue(type = "float", value = listOf(e))
        return animators.toMutableList().also { it[idx] = old.copy(props = props) }
    }
}
