package com.checkmate.core

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug log that survives logcat filtering. Some OEM builds (ColorOS/OPPO) drop third-party
 * Log.d/i output from `adb logcat`, which made the gap-task pipeline undebuggable. Every line
 * is still sent to Log.* AND appended to a capped ring buffer in [CheckmatePrefs] under
 * [KEY], readable without logcat (from Termux):
 *
 *   adb shell "run-as com.checkmate cat shared_prefs/checkmate_prefs.xml" \
 *     | sed 's/&#10;/\n/g' | sed -n '/debug_trail/,/<\/string>/p'
 *
 * Never throws (safe in plain-JVM unit tests, where Log is stubbed and CheckmatePrefs is
 * uninitialised). Temporary diagnostic aid — every line rewrites the prefs file, so drop the
 * mirror once the pipeline is healthy.
 */
object DebugTrail {
    const val KEY = "debug_trail"
    private const val MAX_CHARS = 60_000
    private val lock = Any()

    fun d(tag: String, msg: String) = emit('D', tag, msg, null)
    fun w(tag: String, msg: String, tr: Throwable? = null) = emit('W', tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null) = emit('E', tag, msg, tr)

    fun read(): String = CheckmatePrefs.getString(KEY, "") ?: ""
    fun clear() { CheckmatePrefs.remove(KEY) }

    private fun emit(level: Char, tag: String, msg: String, tr: Throwable?) {
        try {
            when (level) {
                'E' -> Log.e(tag, msg, tr)
                'W' -> Log.w(tag, msg, tr)
                else -> Log.d(tag, msg)
            }
        } catch (_: Throwable) {}
        try {
            val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            val trailer = if (tr != null) {
                " | ${tr.javaClass.simpleName}: ${tr.message} @ ${tr.stackTrace.firstOrNull()}"
            } else ""
            val line = "$ts $level/$tag ${msg.replace('\n', ' ')}$trailer\n"
            synchronized(lock) {
                val cur = CheckmatePrefs.getString(KEY, "") ?: ""
                var next = cur + line
                if (next.length > MAX_CHARS) {
                    next = next.substring(next.length - MAX_CHARS)
                    next = next.substringAfter('\n', next)
                }
                CheckmatePrefs.putString(KEY, next)
            }
        } catch (_: Throwable) {}
    }
}
