package com.checkmate.workmode

import com.checkmate.core.CheckmatePrefs
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * UninstallGuard — the shared brain behind Checkmate's uninstall protection.
 *
 * IMPORTANT DESIGN NOTE: the guardian PIN is never typed in by hand and never
 * shown on screen. Whoever holds the phone can tap "Generate PIN", but the
 * PIN itself only ever reaches the guardian's Telegram chat — the device
 * stores only a SHA-256 hash, and the plaintext is never persisted or
 * displayed anywhere in the UI. This closes the obvious loophole where a
 * student sets their own PIN in a plaintext field: generating a new PIN is
 * harmless self-sabotage from the student's side, since they still never
 * learn what it is.
 *
 * Three cooperating pieces close the uninstall loop:
 *  1. Device Admin activation (CheckmateDeviceAdminReceiver) hides the plain
 *     "Uninstall" button on the App Info screen.
 *  2. This object holds the guardian-PIN-gated "temporary unlock" window.
 *  3. AppAutomationService (accessibility service) watches for navigation
 *     into screens that could remove/disable Checkmate and, unless
 *     currently unlocked, bounces to Home and alerts the guardian.
 *
 * None of this can survive Safe Mode, `adb uninstall`, or a factory reset —
 * those are OS-level and no on-device app can intercept them.
 */
object UninstallGuard {

    private const val KEY_PIN_HASH        = "guardian_pin_hash"
    private const val KEY_UNLOCK_UNTIL    = "uninstall_unlock_until"
    private const val KEY_LAST_ALERT      = "uninstall_last_alert_at"
    private const val KEY_LAST_GENERATED  = "guardian_pin_last_generated"
    private const val KEY_FAIL_COUNT      = "guardian_pin_fail_count"
    private const val KEY_LOCKOUT_UNTIL   = "guardian_pin_lockout_until"

    // How long a correct PIN entry keeps the watchdog from bouncing the user,
    // so a guardian can actually walk through the uninstall/disable flow.
    private const val UNLOCK_WINDOW_MS = 2 * 60 * 1000L

    // Don't fire a fresh Telegram alert more than once per this window even
    // if the watchdog keeps re-triggering while the user sits on the screen.
    private const val ALERT_THROTTLE_MS = 20 * 1000L

    // Minimum time between PIN (re)generations — stops a student from
    // spamming "generate" to flood the guardian's Telegram.
    private const val REGEN_COOLDOWN_MS = 5 * 60 * 1000L

    // Brute-force protection on the unlock field itself.
    private const val MAX_FAILED_ATTEMPTS = 5
    private const val LOCKOUT_MS = 10 * 60 * 1000L

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

    // ── Result types ─────────────────────────────────────────────────────────

    sealed class UnlockResult {
        object Success : UnlockResult()
        object WrongPin : UnlockResult()
        object NoPinConfigured : UnlockResult()
        data class LockedOut(val secondsLeft: Long, val justTriggered: Boolean) : UnlockResult()
    }

    // ── PIN generation (device-side hash only — plaintext goes to guardian) ────

    fun hasPinConfigured(): Boolean = !CheckmatePrefs.getString(KEY_PIN_HASH, null).isNullOrBlank()

    fun generateRandomPin(): String {
        val n = SecureRandom().nextInt(1_000_000)
        return n.toString().padStart(6, '0')
    }

    /** Stores only the hash. Also invalidates any active unlock window and clears lockout state. */
    fun storeNewPinHash(pin: String) {
        CheckmatePrefs.putString(KEY_PIN_HASH, hashPin(pin))
        CheckmatePrefs.putLong(KEY_LAST_GENERATED, System.currentTimeMillis())
        CheckmatePrefs.putLong(KEY_UNLOCK_UNTIL, 0L)
        CheckmatePrefs.putInt(KEY_FAIL_COUNT, 0)
        CheckmatePrefs.putLong(KEY_LOCKOUT_UNTIL, 0L)
    }

    fun canRegeneratePin(): Boolean {
        val last = CheckmatePrefs.getLong(KEY_LAST_GENERATED, 0L)
        return System.currentTimeMillis() - last >= REGEN_COOLDOWN_MS
    }

    fun regenCooldownRemainingSeconds(): Long {
        val last = CheckmatePrefs.getLong(KEY_LAST_GENERATED, 0L)
        val remaining = REGEN_COOLDOWN_MS - (System.currentTimeMillis() - last)
        return if (remaining > 0) remaining / 1000 else 0
    }

    private fun hashPin(pin: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(pin.trim().toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ── Unlock flow (guardian enters the PIN they received on Telegram) ────────

    fun isLockedOut(): Boolean =
        System.currentTimeMillis() < CheckmatePrefs.getLong(KEY_LOCKOUT_UNTIL, 0L)

    fun lockoutRemainingSeconds(): Long {
        val remaining = CheckmatePrefs.getLong(KEY_LOCKOUT_UNTIL, 0L) - System.currentTimeMillis()
        return if (remaining > 0) remaining / 1000 else 0
    }

    fun unlockWithPin(pin: String): UnlockResult {
        if (!hasPinConfigured()) return UnlockResult.NoPinConfigured
        if (isLockedOut()) return UnlockResult.LockedOut(lockoutRemainingSeconds(), justTriggered = false)

        val stored = CheckmatePrefs.getString(KEY_PIN_HASH, null)
        return if (hashPin(pin) == stored) {
            CheckmatePrefs.putLong(KEY_UNLOCK_UNTIL, System.currentTimeMillis() + UNLOCK_WINDOW_MS)
            CheckmatePrefs.putInt(KEY_FAIL_COUNT, 0)
            UnlockResult.Success
        } else {
            val fails = CheckmatePrefs.getInt(KEY_FAIL_COUNT, 0) + 1
            if (fails >= MAX_FAILED_ATTEMPTS) {
                CheckmatePrefs.putInt(KEY_FAIL_COUNT, 0)
                CheckmatePrefs.putLong(KEY_LOCKOUT_UNTIL, System.currentTimeMillis() + LOCKOUT_MS)
                UnlockResult.LockedOut(LOCKOUT_MS / 1000, justTriggered = true)
            } else {
                CheckmatePrefs.putInt(KEY_FAIL_COUNT, fails)
                UnlockResult.WrongPin
            }
        }
    }

    fun isUnlocked(): Boolean =
        System.currentTimeMillis() < CheckmatePrefs.getLong(KEY_UNLOCK_UNTIL, 0L)

    fun unlockSecondsRemaining(): Long {
        val remaining = CheckmatePrefs.getLong(KEY_UNLOCK_UNTIL, 0L) - System.currentTimeMillis()
        return if (remaining > 0) remaining / 1000 else 0
    }

    fun lockNow() = CheckmatePrefs.putLong(KEY_UNLOCK_UNTIL, 0L)

    // ── Screen detection (used by AppAutomationService) ─────────────────────────

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
