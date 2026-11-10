package com.example

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Crash report for phones without a PC: when the app crashes, the error text is saved; the next time the app starts it is
 * shown in a plain Android dialog (no Compose, so it works even when Compose is what crashed) with a Copy button.
 * Also reports the last native crash / start-up failure that the system recorded (text only, no stack).
 */
object CrashReport {
    private const val FILE = "last_crash.txt"
    private const val PREFS = "crash_report"
    private const val KEY_SEEN = "exit_seen_ts"

    /** Call first thing in onCreate. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(app.filesDir, FILE).writeText("Thread: ${thread.name}\n" + Log.getStackTraceString(error))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The text to show now, or null when the last run ended normally. */
    fun pending(context: Context): String? {
        val parts = ArrayList<String>()
        val f = File(context.filesDir, FILE)
        if (f.exists()) {
            runCatching { f.readText().take(8000) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { parts += it }
        }
        nativeExit(context)?.let { parts += it }
        return if (parts.isEmpty()) null else parts.joinToString("\n\n")
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }

    private fun nativeExit(context: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getLong(KEY_SEEN, 0L)
            val last = am.getHistoricalProcessExitReasons(context.packageName, 0, 5).firstOrNull {
                it.timestamp > seen &&
                    (it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || it.reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE)
            }
            if (last == null) null
            else {
                prefs.edit().putLong(KEY_SEEN, last.timestamp).apply()
                "System exit record: reason=${last.reason} (5 = native crash, 7 = start-up failure) status=${last.status} ${last.description ?: ""}"
            }
        }.getOrNull()
    }
}
