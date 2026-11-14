package com.example.model

/**
 * A bounded LIFO stack — the undo/redo history store (build 199, audit #2 item O1).
 *
 * The editor's history holds a full copy of the track list per step, and before build 199 the
 * stacks grew without bound: a long session could pin hundreds of full states. This stack caps
 * itself at [capacity] entries — pushing past the cap drops the OLDEST entry (the step that
 * would be hardest to miss), so undo still reaches back [capacity] steps no matter how long the
 * session runs. Pure and dependency-free so the harness can pin the cap behaviour.
 */
class HistoryStack<T>(val capacity: Int = DEFAULT_CAPACITY) {

    private val items = ArrayList<T>()

    val size: Int get() = items.size

    fun isEmpty(): Boolean = items.isEmpty()

    fun isNotEmpty(): Boolean = items.isNotEmpty()

    /** Pushes one step, evicting the oldest steps while the stack is over capacity. */
    fun push(item: T) {
        items.add(item)
        while (items.size > capacity) items.removeAt(0)
    }

    /** The newest step, removed; null when empty. */
    fun pop(): T? = if (items.isEmpty()) null else items.removeAt(items.lastIndex)

    fun clear() {
        items.clear()
    }

    companion object {
        /** ~60 editor steps — deep enough for a working session, bounded in memory. */
        const val DEFAULT_CAPACITY = 60
    }
}
