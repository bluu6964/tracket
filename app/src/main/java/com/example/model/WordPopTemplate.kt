package com.example.model

/**
 * The "Word Pop" template (Templates programme, text-animation template #2): a short
 * sentence whose words arrive ONE BY ONE — each word pops in (scale up on the Back-out
 * curve, fading and rising a little), the sentence holds, and all words fade out together.
 * Where the Pop Title animates ONE text layer as a whole, this template gives every word
 * its OWN ordinary TEXT layer: the other classic title style, and the one that lets the
 * user retype, move, retime or recolour each word separately.
 *
 * Like the other templates, inserting it drops ONE Group layer into the current timeline
 * at the playhead (see [buildGroup]) and the user customises it in place. Like the Pop
 * Title there is no fixed internal scene and no rig: the composition is authored directly
 * against the live preview size, sizes as fractions of the preview width (the text
 * engine's font is 11% of the frame width), the words centred on the canvas centre, and
 * the group itself at the REST pose — moving / scaling / rotating the one group layer
 * moves the whole sentence. Per Sarowar's principle ("যেটার কাজ যেখানে সেটা সেখানে
 * রাখবে") nothing else is kept: no Controller, no Null — nothing here has their job.
 *
 * THE LAYOUT LAW (why the words sit where they sit): a text layer's glyphs are drawn
 * centred on the layer's own position — both painters lay the line out centred in a box
 * that hugs the line and centres the box on the layer position (see LayerPainter /
 * NativeTextPainter) — so a sentence of word layers is placed by its word CENTRES, and
 * the centres come from the words' advances, the same measured quantities the text engine
 * itself sums when it lays out a line. The pure-Kotlin builder has no font to measure
 * with, so the sample words' advances were measured ONCE, with the app's own shaping
 * stack (HarfBuzz over Noto Sans Bengali Bold — the family Android falls back to for
 * Bengali, at the layer's weight 700) and are carried in [WordPopSpec.WORD_ADV_EM]:
 * আপনার 3.596 em, নতুন 2.000 em, লেখা 2.228 em, the space 0.260 em; the check that the
 * law rests on: the shaped sentence "আপনার নতুন লেখা" advances exactly 8.344 em =
 * 3.596 + 0.260 + 2.000 + 0.260 + 2.228, so the parts sum to the whole. Word i's centre
 * is (-total/2 + the advances and spaces before it + its own half advance) x the layout
 * font (11% of the preview width): the sentence spans exactly [-total/2, +total/2], the
 * gap between neighbouring word edges is exactly one space advance, and because the law
 * distributes from the centre, a device font a few percent off scales the sentence about
 * the centre instead of pushing it off-centre. Retyping a word keeps its authored centre
 * (the animation never depends on the words) — the user nudges a much longer word the
 * same way they would in any editor.
 *
 * The performance (3 s, all ordinary keyframes with the app's own easing presets):
 *  - word i starts at i x 180 ms and pops for 350 ms: SCALE 60% -> 100% on the "Back
 *    out" preset (overshoots to ~101% and settles — the pop), OPACITY 0 -> 100
 *    (Cubic out) and a POSITION rise from [WordPopSpec.RISE_FRAC] of the width below its
 *    place to its place (Cubic out). Before its turn a word waits invisible at its
 *    start pose (the evaluator holds its first key, the Logo Reveal's convention);
 *  - every word has landed by 710 ms; the sentence then holds at rest;
 *  - exit, 2600..3000 ms: all words fade 100 -> 0 together (Cubic in), ending at exactly
 *    opacity 0 — one shared fade, no movement.
 */
object WordPopSpec {
    const val DURATION_MS = 3000L
    /** Each word's pop (scale + fade + rise) runs inside this window. */
    const val POP_MS = 350L
    /** Word i starts this many ms after word i-1. */
    const val STAGGER_MS = 180L
    /** The shared fade-out runs from here to [DURATION_MS] — 400 ms. */
    const val EXIT_START_MS = 2600L

    /** The sample sentence, one word per layer, in reading order. */
    val WORDS = listOf("আপনার", "নতুন", "লেখা")

    /**
     * Each sample word's advance in ems of the layout font, measured with HarfBuzz over
     * Noto Sans Bengali Bold (upem 1000), the app's shaping stack and Android's Bengali
     * fallback family — see the layout law in the file header. Same order as [WORDS].
     */
    val WORD_ADV_EM = listOf(3.596f, 2.000f, 2.228f)
    /** The space advance in ems, measured the same way: the inter-word gap. */
    const val SPACE_EM = 0.260f
    /** The text engine's layout font, as a fraction of the frame width. */
    const val FONT_FRAC = 0.11f

    /** Rest scale of every word layer (the app's plain text size). */
    const val WORD_SCALE = 100f
    /** Each pop starts at this scale and settles to [WORD_SCALE]. */
    const val ENTER_SCALE = 60f
    /** Entrance rise: how far below its place a word starts, as a fraction of width. */
    const val RISE_FRAC = 0.02f

    /** When word [index]'s pop starts, in ms from the template's start. */
    fun startMs(index: Int): Long = index * STAGGER_MS

    /** Word [index]'s advance on a canvas [previewWidthPx] wide, in stored px. */
    fun advancePx(index: Int, previewWidthPx: Float): Float =
        WORD_ADV_EM[index] * FONT_FRAC * previewWidthPx

    /** The whole sentence's advance on a canvas [previewWidthPx] wide, in stored px. */
    fun totalWidthPx(previewWidthPx: Float): Float {
        val totalEm = WORD_ADV_EM.sum() + (WORDS.size - 1) * SPACE_EM
        return totalEm * FONT_FRAC * previewWidthPx
    }

    /**
     * Word [index]'s centre on a canvas [previewWidthPx] wide, in stored px from the
     * canvas centre: -total/2 + everything before the word + its own half advance.
     */
    fun centreXPx(index: Int, previewWidthPx: Float): Float {
        val totalEm = WORD_ADV_EM.sum() + (WORDS.size - 1) * SPACE_EM
        var beforeEm = index * SPACE_EM
        for (j in 0 until index) beforeEm += WORD_ADV_EM[j]
        val centreEm = -totalEm / 2f + beforeEm + WORD_ADV_EM[index] / 2f
        return centreEm * FONT_FRAC * previewWidthPx
    }

    // The app's own easing presets (Easing.PRESETS), by name.
    val EASE_BACK_OUT = Easing(0.175f, 0.885f, 0.32f, 1.2f)     // "Back out"
    val EASE_CUBIC_OUT = Easing(0.215f, 0.61f, 0.355f, 1f)      // "Cubic out"
    val EASE_CUBIC_IN = Easing(0.55f, 0.055f, 0.675f, 0.19f)    // "Cubic in"
}

object WordPopTemplate {

    /** What [buildGroup] hands back: the Group layer, its word layers, and the lanes. */
    data class Insert(
        val group: TimelineClip,
        /** The sentence: one ordinary TEXT layer per word, in reading order. */
        val wordLayers: List<TimelineClip>,
        /** The layers inside the group in lane order (top lane first): word 1, 2, 3. */
        val children: List<TimelineClip>,
        /** Lanes to place on top of the project, top lane first: the group, then [children]. */
        val lanesTopFirst: List<TimelineClip>
    )

    /**
     * The template as ONE Group layer + its three word layers, ready to insert into the
     * project that is open. [previewWidthPx] / [previewHeightPx] are the editor preview
     * frame's size (the stored-units basis, like the importer's); the group starts at
     * [insertAtMs] (the playhead) and spans [WordPopSpec.DURATION_MS]. Every call builds
     * fresh clips under [idBase] — inserting twice gives two independent sentences.
     *
     * The group stays at the REST pose and the words link to it with the REST reference
     * pose (the Carousel's convention): the group's own transform is then a pure user
     * control on top of the authored design, and ParentRig composes it onto every word.
     */
    fun buildGroup(
        previewWidthPx: Float,
        previewHeightPx: Float,
        insertAtMs: Long,
        idBase: String = "wordpop"
    ): Insert {
        val pw = if (previewWidthPx > 1f) previewWidthPx else 1080f
        // Sizes ride on the width (the text engine's font does too); the design is
        // centre-anchored, so any aspect keeps it in frame (pinned for 16:9 and 9:16).
        // [previewHeightPx] is accepted for symmetry with the other templates' insert API.
        val rise = WordPopSpec.RISE_FRAC * pw

        val groupId = "$idBase-group"
        val link = GroupOps.linkTo(groupId, GroupOps.Pose.REST)
        val group = TimelineClip(
            id = groupId,
            name = "Word Pop",
            type = LayerType.GROUP,
            startMs = insertAtMs,
            durationMs = WordPopSpec.DURATION_MS,
            color = 0xFFEF6C00L
        )

        val words = WordPopSpec.WORDS.mapIndexed { i, word ->
            val start = WordPopSpec.startMs(i)
            val landed = start + WordPopSpec.POP_MS
            val x = WordPopSpec.centreXPx(i, pw)
            TimelineClip(
                id = "$idBase-word${i + 1}",
                name = "Word ${i + 1}",
                type = LayerType.TEXT,
                startMs = insertAtMs,
                durationMs = WordPopSpec.DURATION_MS,
                color = 0xFF3992CEL,
                text = word,
                textColor = 0xFFFFFFFFL,
                fontWeight = 700,
                transformX = x,
                keyframes = mapOf(
                    AnimProp.POSITION to listOf(
                        Keyframe(start, x, rise, WordPopSpec.EASE_CUBIC_OUT),
                        Keyframe(landed, x, 0f)
                    ),
                    AnimProp.SCALE to listOf(
                        Keyframe(start, WordPopSpec.ENTER_SCALE, 0f, WordPopSpec.EASE_BACK_OUT),
                        Keyframe(landed, WordPopSpec.WORD_SCALE, 0f)
                    ),
                    AnimProp.OPACITY to listOf(
                        Keyframe(start, 0f, 0f, WordPopSpec.EASE_CUBIC_OUT),
                        Keyframe(landed, 100f, 0f),
                        Keyframe(WordPopSpec.EXIT_START_MS, 100f, 0f, WordPopSpec.EASE_CUBIC_IN),
                        Keyframe(WordPopSpec.DURATION_MS, 0f, 0f)
                    )
                ),
                groupId = groupId,
                parent = link
            )
        }

        // Lane order (top lane first): the words in reading order (they never overlap,
        // so the order is presentational — word 1 keeps the top lane like the Pop
        // Title's text keeps its lane above the bar).
        val lanesTopFirst = listOf(group) + words
        return Insert(group, words, words, lanesTopFirst)
    }
}
