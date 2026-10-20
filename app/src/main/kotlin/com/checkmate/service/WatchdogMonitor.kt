package com.checkmate.service

import android.content.Context
import com.checkmate.core.CheckmatePrefs
import com.checkmate.workmode.AccessibilityRepair

/**
 * WatchdogMonitor — periodic check that Checkmate's accessibility watchdog is enabled.
 * Tries a self-repair first (needs WRITE_SECURE_SETTINGS, see [AccessibilityRepair]); if the
 * watchdog is still off afterwards, tells the guardian, at most once per [ALERT_EVERY_MS].
 */
object WatchdogMonitor {

    private const val KEY_LAST_ALERT = "watchdog_off_last_alert_at"
    private const val ALERT_EVERY_MS = 6 * 60 * 60 * 1000L

    fun check(context: Context) {
        if (AccessibilityRepair.ensureEnabled(context)) return
        val now = System.currentTimeMillis()
        if (now - CheckmatePrefs.getLong(KEY_LAST_ALERT, 0L) < ALERT_EVERY_MS) return
        CheckmatePrefs.putLong(KEY_LAST_ALERT, now)
        GuardianNotifier.notifyWatchdogStillOff(context, AccessibilityRepair.canRepair(context))
    }
}
