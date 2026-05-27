package com.checkmate.workmode

import android.content.Context
import android.net.Uri
import android.util.Log
import com.checkmate.core.CheckmatePrefs
import com.checkmate.service.GuardianNotifier
import com.checkmate.service.ScreenshotCapture

/**
 * DistractionGuard — tracks how many times the student attempts to open each
 * blocked app or visit each blocked domain during a study session.
 *
 * On the 3rd attempt for any single app or domain:
 *   1. Captures a screenshot via ScreenshotCapture (no Activity needed — uses
 *      the overlay window already owned by FloatingAttentionService).
 *   2. Sends a WhatsApp alert to the guardian with the screenshot attached.
 *
 * Counters live in memory only and are wiped when WorkModeManager.deactivate()
 * is called, so each new task starts from zero.
 *
 * Thread-safety: all mutations are @Synchronized. Reads are opportunistic —
 * a slight race on the counter is acceptable (off-by-one on the alert threshold
 * is harmless; we'd rather alert once too many than miss an alert).
 */
object DistractionGuard {

    private const val TAG       = "DistractionGuard"
    const val ALERT_THRESHOLD   = 3          // alert on 3rd attempt

    // packageName → attempt count
    private val appAttempts     = mutableMapOf<String, Int>()
    // hostname (e.g. "youtube.com") → attempt count
    private val domainAttempts  = mutableMapOf<String, Int>()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called by AppAutomationService every time a blocked app is foregrounded.
     * Returns the new attempt count so the caller can decide what action to take.
     */
    @Synchronized
    fun recordAppAttempt(context: Context, packageName: String): Int {
        val count = (appAttempts[packageName] ?: 0) + 1
        appAttempts[packageName] = count
        Log.d(TAG, "App attempt: $packageName → $count")
        if (count == ALERT_THRESHOLD) {
            triggerAlert(context, "app", packageName)
        }
        return count
    }

    /**
     * Called by AppAutomationService every time the URL bar shows a blocked domain.
     * Returns the new attempt count.
     */
    @Synchronized
    fun recordDomainAttempt(context: Context, hostname: String): Int {
        val count = (domainAttempts[hostname] ?: 0) + 1
        domainAttempts[hostname] = count
        Log.d(TAG, "Domain attempt: $hostname → $count")
        if (count == ALERT_THRESHOLD) {
            triggerAlert(context, "site", hostname)
        }
        return count
    }

    /** Wipe all counters — call from WorkModeManager.deactivate(). */
    @Synchronized
    fun reset() {
        appAttempts.clear()
        domainAttempts.clear()
        Log.d(TAG, "Counters reset")
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun triggerAlert(context: Context, kind: String, target: String) {
        Log.w(TAG, "ALERT: $kind '$target' attempted $ALERT_THRESHOLD times")
        // Capture overlay screenshot (no Activity required)
        val uri: Uri? = ScreenshotCapture.captureOverlay(context)
        GuardianNotifier.notifyDistractionAlert(context, kind, target, uri)
    }
}
