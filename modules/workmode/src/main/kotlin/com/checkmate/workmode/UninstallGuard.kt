package com.checkmate.workmode

import com.checkmate.core.CheckmatePrefs

/**
 * UninstallGuard — the shared brain behind Checkmate's uninstall protection.
 *
 * Three cooperating pieces close the loop:
 *  1. Device Admin activation (CheckmateDeviceAdminReceiver) hides the plain
 *     "Uninstall" button on the App Info screen.
 *  2. This object holds the guardian-PIN-gated "temporary unlock" window —
 *     the only sanctioned way past the watchdog below.
 *  3. AppAutomationService (accessibility service) watches for navigation
 *     into the screens that could remove/disable Checkmate and, unless
 *     currently unlocked, bounces the user back to Home and alerts the
 *     guardian via GuardianNotifier.notifyUninstallAttempt().
 *
 * None of this can survive Safe Mode, `adb uninstall`, or a factory reset —
 * those are OS-level and no on-device app can intercept them. What this
 * closes is the everyday loophole: a single tap on "Uninstall" or "Turn off"
 * from a normal running session.
 */
object UninstallGuard {

    private const val KEY_PIN         = "guardian_pin"
    private const val KEY_UNLOCK_UNTIL = "uninstall_unlock_until"
    private const val KEY_LAST_ALERT   = "uninstall_last_alert_at"

    // How long a correct PIN entry keeps the watchdog from bouncing the user,
    // so a guardian can actually walk through the uninstall/disable flow.
    private const val UNLOCK_WINDOW_MS = 2 * 60 * 1000L

    // Don't fire a fresh Telegram alert more than once per this window even
    // if the watchdog keeps re-triggering while the user sits on the screen.
    private const val ALERT_THROTTLE_MS = 20 * 1000L

    /** Text fragments that identify a screen worth blocking, matched case-insensitively. */
    val GUARD_KEYWORDS = listOf(
        "uninstall",
        "force stop",
        "disable device admin app",
        "deactivate this device admin app",
        "deactivate",
        "remove admin",
        "turn off",          // accessibility-service disable toggle wording on many OEMs
        "app info"
    )

    // Package names whose screens we watch. Settings itself, plus common
    // OEM permission-controller / Settings forks (MIUI, ColorOS, One UI).
    val WATCHED_PACKAGES = setOf(
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.miui.securitycenter",
        "com.coloros.securepay",
        "com.oppo.securepay",
        "com.samsung.android.settings"
    )

    fun hasPinConfigured(): Boolean = !CheckmatePrefs.getString(KEY_PIN, null).isNullOrBlank()

    fun setPin(pin: String) = CheckmatePrefs.putString(KEY_PIN, pin.trim())

    /**
     * Guardian enters the PIN inside the app (Settings → Security) before
     * going to uninstall/disable Checkmate. Correct PIN opens a short window
     * during which the watchdog stands down.
     */
    fun unlockWithPin(pin: String): Boolean {
        val stored = CheckmatePrefs.getString(KEY_PIN, null)
        if (stored.isNullOrBlank() || pin.trim() != stored) return false
        CheckmatePrefs.putLong(KEY_UNLOCK_UNTIL, System.currentTimeMillis() + UNLOCK_WINDOW_MS)
        return true
    }

    fun isUnlocked(): Boolean =
        System.currentTimeMillis() < CheckmatePrefs.getLong(KEY_UNLOCK_UNTIL, 0L)

    fun lockNow() = CheckmatePrefs.putLong(KEY_UNLOCK_UNTIL, 0L)

    /** True if this window's visible text suggests an uninstall/disable screen for Checkmate. */
    fun looksLikeGuardedScreen(visibleText: String, targetsCheckmate: Boolean): Boolean {
        if (!targetsCheckmate) return false
        val lower = visibleText.lowercase()
        return GUARD_KEYWORDS.any { lower.contains(it) }
    }

    /** Throttles guardian alerts so a stuck screen doesn't spam Telegram. */
    fun shouldAlert(): Boolean {
        val last = CheckmatePrefs.getLong(KEY_LAST_ALERT, 0L)
        val now = System.currentTimeMillis()
        if (now - last < ALERT_THROTTLE_MS) return false
        CheckmatePrefs.putLong(KEY_LAST_ALERT, now)
        return true
    }
}
