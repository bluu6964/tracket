package com.example.engine

import android.content.Context
import java.io.File

/**
 * Breadcrumbs of the last cutout run. A native crash (inside ONNX Runtime) cannot be caught or
 * logged by the app, so each step is appended to a small file that survives the crash; on the next
 * start [incomplete] tells where the last run stopped.
 */
internal object CutoutTrace {
    private fun file(c: Context) = File(c.filesDir, "cutout_trace.txt")

    fun begin(c: Context) {
        try { file(c).writeText("start\n") } catch (_: Throwable) {}
    }

    fun mark(c: Context, step: String) {
        try { file(c).appendText(step + "\n") } catch (_: Throwable) {}
    }

    /** Steps of the last run when it did not finish (crash or kill), else null. Reported only once. */
    fun incomplete(c: Context): String? {
        val f = file(c)
        if (!f.exists()) return null
        var text = ""
        try {
            text = f.readText()
        } catch (_: Throwable) {
        }
        try {
            f.delete()
        } catch (_: Throwable) {
        }
        val lines = text.split("\n").filter { it.isNotBlank() }
        val last = if (lines.isEmpty()) "" else lines[lines.size - 1]
        if (lines.isEmpty() || last == "done" || last.startsWith("error")) return null
        return lines.joinToString(" > ")
    }
}
