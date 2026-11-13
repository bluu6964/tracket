package com.example.engine

/**
 * Small-defect cleanup for a Roto Brush mask (pure, harness-pinned): fills tiny holes that are fully
 * enclosed by the object and drops tiny isolated specks outside it. Both are the classic EdgeSAM noise
 * (a bright patch inside the object the model leaves out, a few stray pixels it adds outside) and both
 * read on screen as "the AI is bad".
 *
 * The caps scale with the picture size and stay far below any real object part or real hole (a ring, a
 * mug handle), and the biggest object component is never removed, so a genuinely small object survives.
 * Only pixels whose binary state (alpha above / below 0.5) flips are rewritten — to solid 1 or 0; every
 * other pixel keeps its value, so the soft mask edge is untouched.
 *
 * Returns the input array itself when nothing changes; otherwise a cleaned copy (the input is never
 * mutated — propagation chains the raw mask it was given).
 */
object MaskCleanup {

    fun clean(alpha: FloatArray, w: Int, h: Int): FloatArray {
        if (w <= 0 || h <= 0 || alpha.size < w * h) return alpha
        val n = w * h
        val inside = BooleanArray(n) { alpha[it] > 0.5f }
        var anyInside = false
        for (b in inside) if (b) { anyInside = true; break }
        if (!anyInside) return alpha

        val maxSpeck = maxOf(4, (n / 20_000f).toInt())   // ~29 px at 1024x576, ~7 px at 512x288
        val maxHole = maxOf(16, (n / 2_000f).toInt())    // ~294 px at 1024x576, ~73 px at 512x288

        var out: FloatArray? = null
        fun writable(): FloatArray = out ?: alpha.copyOf().also { out = it }

        // Object components: drop the tiny ones (never the biggest).
        val obj = label(inside, true, w, h)
        val biggest = obj.sizes.indices.maxByOrNull { obj.sizes[it] } ?: -1
        val drop = BooleanArray(obj.sizes.size) { it != biggest && obj.sizes[it] <= maxSpeck }
        if (drop.any { it }) {
            val o = writable()
            for (p in 0 until n) {
                val id = obj.labels[p]
                if (id > 0 && drop[id - 1]) o[p] = 0f
            }
        }
        // Background components that touch no picture border are holes: fill the tiny ones.
        val bg = label(inside, false, w, h)
        val fill = BooleanArray(bg.sizes.size) { !bg.touchesBorder[it] && bg.sizes[it] <= maxHole }
        if (fill.any { it }) {
            val o = writable()
            for (p in 0 until n) {
                val id = bg.labels[p]
                if (id > 0 && fill[id - 1]) o[p] = 1f
            }
        }
        return out ?: alpha
    }

    private class Components(val labels: IntArray, val sizes: List<Int>, val touchesBorder: List<Boolean>)

    /** 4-connected components of the pixels equal to [want]; labels are 1-based (0 = not in a component). */
    private fun label(mask: BooleanArray, want: Boolean, w: Int, h: Int): Components {
        val n = w * h
        val labels = IntArray(n)
        val sizes = ArrayList<Int>()
        val border = ArrayList<Boolean>()
        val stack = IntArray(n)
        for (start in 0 until n) {
            if (mask[start] != want || labels[start] != 0) continue
            val id = sizes.size + 1
            var sp = 0
            stack[sp++] = start
            labels[start] = id
            var size = 0
            var touches = false
            while (sp > 0) {
                val p = stack[--sp]
                size++
                val x = p % w
                val y = p / w
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) touches = true
                if (x > 0) { val q = p - 1; if (mask[q] == want && labels[q] == 0) { labels[q] = id; stack[sp++] = q } }
                if (x < w - 1) { val q = p + 1; if (mask[q] == want && labels[q] == 0) { labels[q] = id; stack[sp++] = q } }
                if (y > 0) { val q = p - w; if (mask[q] == want && labels[q] == 0) { labels[q] = id; stack[sp++] = q } }
                if (y < h - 1) { val q = p + w; if (mask[q] == want && labels[q] == 0) { labels[q] = id; stack[sp++] = q } }
            }
            sizes.add(size)
            border.add(touches)
        }
        return Components(labels, sizes, border)
    }
}
