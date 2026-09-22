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
 * those are OS-level and no on-device app can intercept them. Developer
 * Options is the one on-device path that leads there without a factory
 * reset or physical Safe Mode boot, so it's guarded too (see
 * DEV_OPTIONS_KEYWORDS below) even though it never mentions "Checkmate".
 */
object UninstallGuard {

    private const val KEY_PIN_HASH        = "guardian_pin_hash"
    private const val KEY_UNLOCK_UNTIL    = "uninstall_unlock_until"
    private const val KEY_LAST_ALERT      = "uninstall_last_alert_at"
    private const val KEY_LAST_GENERATED  = "guardian_pin_last_generated"
    private const val KEY_FAIL_COUNT      = "guardian_pin_fail_count"
    private const val KEY_LOCKOUT_UNTIL   = "guardian_pin_lockout_until"

    // Mentor v2 (spec 3.5): remote override window, independent of KEY_UNLOCK_UNTIL. A guardian
    // can be PIN-unlocked (isUnlocked() == true) while WorkModeManager.settingsLocked() still
    // reads locked because skipRateExceedsThreshold() is also true — that's the intended
    // escalation behavior. This is the documented emergency bypass for that case: a guardian
    // command relayed through the existing Telegram bot/Cloudflare worker (the same
    // infrastructure StatusReporter already pushes to for "status"/"usage") sets this window,
    // and WorkModeManager.settingsLocked() treats it as a full bypass of BOTH gates.
    // NOTE: this object only stores/checks the window. The inbound half — the worker route that
    // listens for a guardian command (e.g. "unlock") and the on-device poll that calls
    // grantRemoteOverride() — lives at the app layer (see ReminderService.pollRemoteOverride())
    // since :modules:workmode has no network dependency and shouldn't gain one just for this.
    private const val KEY_REMOTE_OVERRIDE_UNTIL = "remote_override_until"
    private const val REMOTE_OVERRIDE_WINDOW_MS = 5 * 60 * 1000L

    // How long a correct PIN entry keeps the watchdog from bouncing the user,
    // so a guardian can actually walk through the uninstall/disable flow.
    private const val UNLOCK_WINDOW_MS = 2 * 60 * 1000L

    // Don't fire a fresh Telegram alert more than once per this window even
    // if the watchdog keeps re-triggering while the user sits on the screen.
    private const val ALERT_THROTTLE_MS = 20 * 1000L

    // Minimum time between PIN (re)generations. Previously 5 minutes (just
    // enough to stop Telegram spam); raised to a ~7-month commitment window
    // (30-day months) so a fresh PIN request itself becomes a rare, guardian-
    // visible event rather than something the student can casually retry.
    // NOTE: this only gates *regeneration* of a new PIN — it does not touch
    // UNLOCK_WINDOW_MS, the brute-force lockout below, or the remote-override
    // path (KEY_REMOTE_OVERRIDE_UNTIL / grantRemoteOverride()), so the
    // guardian's existing Telegram "unlock" command still works at any time
    // even while regeneration itself is on cooldown.
    private const val REGEN_COOLDOWN_MS = 7L * 30 * 24 * 60 * 60 * 1000L

    // Brute-force protection on the unlock field itself.
    private const val MAX_FAILED_ATTEMPTS = 5
    private const val LOCKOUT_MS = 10 * 60 * 1000L

    // ── Repeated-disable-attempt hard lock ──────────────────────────────────
    // AppAutomationService's touch-blocker overlay normally only holds for a fraction of a
    // second (just long enough for GLOBAL_ACTION_HOME to take effect). If someone keeps
    // landing back on a guarded screen (uninstall / device-admin-disable / accessibility-
    // disable) three times in a row, that's a deliberate, repeated attempt rather than one
    // stray tap — this escalates the response from "bounce and briefly block" to a full
    // screen-wide touch freeze, independent of which specific guarded screen triggered it.
    private const val KEY_CONSEC_ATTEMPTS = "guarded_screen_consecutive_attempts"
    private const val KEY_LAST_ATTEMPT_AT = "guarded_screen_last_attempt_at"

    // A gap longer than this between attempts resets the counter — this counts a *burst* of
    // tries ("three in a row"), not a lifetime total across days/weeks.
    private const val CONSEC_RESET_MS = 10 * 60 * 1000L

    /** Consecutive guarded-screen attempts that trigger the full-screen hard lock. */
    const val HARD_LOCK_THRESHOLD = 3

    /** How long the hard lock holds the entire screen's touch input. */
    const val HARD_LOCK_DURATION_MS = 5 * 60 * 1000L

    /**
     * Text fragments that identify a screen worth blocking, matched case-insensitively.
     *
     * BUGFIX (false trigger on ordinary gestures): "turn off", "app info", "downloaded
     * apps", "installed services", and "installed apps" are common enough as generic
     * system UI copy — a Quick Settings tile's content-description ("Turn off Wi-Fi"),
     * a recents/task-switcher card's per-app "App info" button — that they can co-occur
     * with Checkmate's own name simply because Checkmate is running: its persistent
     * "Work Mode — ON" notification sits in the shade, and its own card sits in
     * recents. Pulling down the notification shade or opening the task switcher are
     * ordinary navigation gestures, not settings screens, so that combination isn't
     * something this watchdog should ever be firing on.
     *
     * Split into two tiers so the fix is precise instead of weakening detection
     * outright:
     *  - [GUARD_KEYWORDS_STRICT]: phrases that only ever appear on a genuine
     *    uninstall/disable confirmation. Safe to match on any watched surface,
     *    including the widened [isLikelySystemSurface] prefixes (SystemUI, OEM
     *    Settings forks) — that widening exists for exactly this tier (the
     *    Battery/App-info "Force stop" bubble loophole).
     *  - [GUARD_KEYWORDS_SETTINGS_ONLY]: phrases generic enough to show up outside
     *    Settings entirely. Only matched when the window is one of the actual
     *    Settings/OEM-Settings packages in [WATCHED_PACKAGES] — never on the
     *    broader [isLikelySystemSurface] prefix match, which is what pulls in the
     *    notification shade / Quick Settings / recents surfaces that caused the
     *    false triggers.
     */
    val GUARD_KEYWORDS_STRICT = listOf(
        "uninstall",
        "force stop",
        "disable device admin app",
        "deactivate this device admin app",
        "deactivate",
        "remove admin"
    )

    val GUARD_KEYWORDS_SETTINGS_ONLY = listOf(
        "turn off",          // accessibility-service disable toggle wording on many OEMs
        "app info",
        // Settings → Accessibility → "Downloaded apps" (Android 13+) / "Installed
        // services" (older AOSP) / "Installed apps" (some OEMs) — the list screen
        // that shows every accessibility service with its On/Off state and is the
        // gateway a student would use to flip Checkmate's accessibility service
        // off without ever hitting a screen that literally says "turn off". Gated
        // the same way as everything else here: only fires when "checkmate" is
        // also present in the visible text (targetsCheckmate), so browsing this
        // list for an unrelated service before Checkmate is installed, or before
        // its row is visible, isn't affected.
        "downloaded apps",
        "installed services",
        "installed apps"
    )

    /** Combined view, kept for anything that wants "every guard keyword" as one list
     *  (e.g. logging/debug tooling) — detection itself uses the two tiers above. */
    val GUARD_KEYWORDS = GUARD_KEYWORDS_STRICT + GUARD_KEYWORDS_SETTINGS_ONLY

    // Package names whose screens we watch. Settings itself, plus common
    // OEM permission-controller / Settings forks (MIUI, ColorOS, One UI).
    val WATCHED_PACKAGES = setOf(
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.miui.securitycenter",
        "com.coloros.securepay",
        "com.oppo.securepay",
        "com.samsung.android.settings",
        // Samsung splits its Accessibility section into its own separate app
        // rather than hosting it inside com.android.settings — the "Installed
        // apps" service list (where Checkmate's accessibility toggle actually
        // lives on One UI) runs under this package, so it needed its own entry
        // or that screen was invisible to WATCHED_PACKAGES entirely.
        "com.samsung.accessibility",
        // CONFIRMED via `adb shell dumpsys window | grep mCurrentFocus` on this
        // device: the OnePlus/ColorOS per-app Battery screen (PowerControlActivity,
        // reached via Settings → Apps → Checkmate → Battery, with the Force stop
        // button on it) runs under this package, not com.android.settings. Already
        // covered by the "com.oplus." prefix in isLikelySystemSurface() below for the
        // STATE_CHANGED path; listed here too so the CONTENT_CHANGED path also
        // applies to it, in case that screen swaps tabs without a full window change.
        "com.oplus.battery"
    )

    // LOOPHOLE FIX (Force Stop reachable via a Battery/App-info card that doesn't run
    // under "com.android.settings" — e.g. a floating "app info" bubble hosted by
    // SystemUI, or a OnePlus/ColorOS-specific Settings fork): rather than guess this
    // device's exact package name for that surface (a wrong guess would silently
    // exempt nothing), match by prefix against the OEM/system packages that are
    // plausible hosts for such a screen. AppAutomationService only uses this to widen
    // which windows get the (already name+keyword gated) guarded-screen text scan on
    // window-open events — it does NOT change what counts as guarded. checkGuardedScreen()
    // still requires "checkmate" + a GUARD_KEYWORDS_STRICT match (or a
    // GUARD_KEYWORDS_SETTINGS_ONLY match gated to a real WATCHED_PACKAGES surface)
    // before it acts, so widening which packages get scanned can't introduce a false
    // positive on an unrelated system screen.
    private val SYSTEM_PACKAGE_PREFIXES = listOf(
        "com.android.systemui",
        "com.android.settings",
        "com.oneplus.",
        "com.oplus.",
        "com.coloros.",
        "com.samsung.",
        "com.miui.",
        "com.xiaomi."
    )

    /** True if [pkg] looks like an OS/OEM system surface (Settings/SystemUI fork) worth
     *  scanning for a guarded screen, even though it isn't one of the specific
     *  [WATCHED_PACKAGES] entries above. */
    fun isLikelySystemSurface(pkg: String): Boolean =
        SYSTEM_PACKAGE_PREFIXES.any { pkg == it || pkg.startsWith(it) }

    // Android's own "Restricted settings" verification/CAPTCHA dialog, shown
    // on both the activate AND deactivate device-admin paths. It never
    // mentions "Checkmate" by name (it's the OS's generic copy for ANY
    // device-admin app), so it can't ride the targetsCheckmate gate that
    // GUARD_KEYWORDS uses below — matched name-agnostically instead.
    val DEVICE_ADMIN_PROMPT_KEYWORDS = listOf(
        "activate device admin apps",
        "deactivate device admin app"
    )

    /** True if this window is the OS-level activate/deactivate device-admin prompt (with or without the app named). */
    fun isDeviceAdminPrompt(visibleText: String): Boolean {
        val lower = visibleText.lowercase()
        return DEVICE_ADMIN_PROMPT_KEYWORDS.any { lower.contains(it) }
    }

    // Developer Options rows that expose an ADB path. Enabling USB or Wireless
    // debugging lets a student later run `adb uninstall`/`adb shell pm disable`
    // from a computer, which bypasses the accessibility-service watchdog and
    // the device-admin uninstall block entirely — neither of those OS-level
    // paths ever surfaces "Checkmate" on screen, so like the device-admin
    // prompt above this is matched name-agnostically rather than gated on
    // targetsCheckmate. Matching on these labels means the whole Developer
    // Options screen is guarded, not just the moment a toggle is flipped —
    // same "block the screen outright" precedent as "app info" in GUARD_KEYWORDS.
    val DEV_OPTIONS_KEYWORDS = listOf(
        "usb debugging",
        "wireless debugging",
        "disable adb authorisation timeout",
        "disable adb authorization timeout"
    )

    /** True if this window is a Developer Options screen exposing USB/Wireless debugging. */
    fun isDeveloperOptionsScreen(visibleText: String): Boolean {
        val lower = visibleText.lowercase()
        return DEV_OPTIONS_KEYWORDS.any { lower.contains(it) }
    }

    /**
     * Injected by CheckmateApp.onCreate so :workmode (and :automation, which
     * calls this object) never directly references GuardianNotifier.
     */
    var listener: UninstallAlertListener? = null

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

    /** Same cooldown, in whole days — the 5-minute-scale seconds value above reads as a
     *  meaningless huge number now that the cooldown is measured in months. */
    fun regenCooldownRemainingDays(): Long {
        val remainingSeconds = regenCooldownRemainingSeconds()
        // Round up so "less than a day left" still shows as 1, not 0.
        return (remainingSeconds + 86_399) / 86_400
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
            // A correct guardian PIN means this is a legitimate disable/uninstall flow, not
            // tampering — clear the consecutive-attempt counter so it doesn't carry over into
            // an unrelated future burst.
            resetConsecutiveAttempts()
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

    /** Opens the remote-override window. Call only after verifying a guardian-issued command
     *  (see the NOTE above KEY_REMOTE_OVERRIDE_UNTIL) — this has no PIN check of its own. */
    fun grantRemoteOverride() {
        CheckmatePrefs.putLong(KEY_REMOTE_OVERRIDE_UNTIL, System.currentTimeMillis() + REMOTE_OVERRIDE_WINDOW_MS)
    }

    fun hasRemoteOverride(): Boolean =
        System.currentTimeMillis() < CheckmatePrefs.getLong(KEY_REMOTE_OVERRIDE_UNTIL, 0L)

    // ── Screen detection (used by AppAutomationService) ─────────────────────────

    /**
     * True if this window's visible text suggests an uninstall/disable screen for
     * Checkmate.
     *
     * [isKnownSettingsSurface] must be true only when the event's package is one of
     * the actual Settings/OEM-Settings apps in [WATCHED_PACKAGES] — NOT just a
     * broader [isLikelySystemSurface] prefix match. It gates
     * [GUARD_KEYWORDS_SETTINGS_ONLY], whose phrasing ("app info", "turn off", ...)
     * is common enough elsewhere (Quick Settings tiles, recents cards) to
     * false-trigger on ordinary gestures if checked everywhere
     * [GUARD_KEYWORDS_STRICT] is. See the doc on [GUARD_KEYWORDS_STRICT] /
     * [GUARD_KEYWORDS_SETTINGS_ONLY] for the false-positive this fixes.
     */
    fun looksLikeGuardedScreen(
        visibleText: String,
        targetsCheckmate: Boolean,
        isKnownSettingsSurface: Boolean
    ): Boolean {
        if (!targetsCheckmate) return false
        val lower = visibleText.lowercase()
        if (GUARD_KEYWORDS_STRICT.any { lower.contains(it) }) return true
        return isKnownSettingsSurface && GUARD_KEYWORDS_SETTINGS_ONLY.any { lower.contains(it) }
    }

    /**
     * Called by AppAutomationService every time a guarded screen is detected and bounced.
     * Tracks consecutive attempts, resetting the count if the gap since the last one exceeds
     * [CONSEC_RESET_MS] (so this measures a burst, not a lifetime total). Returns true the
     * moment the [HARD_LOCK_THRESHOLD]-th consecutive attempt lands — the caller's signal to
     * escalate from the normal brief touch-block to the full-screen [HARD_LOCK_DURATION_MS] lock.
     */
    fun recordGuardedAttempt(): Boolean {
        val now = System.currentTimeMillis()
        val last = CheckmatePrefs.getLong(KEY_LAST_ATTEMPT_AT, 0L)
        val previousCount = CheckmatePrefs.getInt(KEY_CONSEC_ATTEMPTS, 0)
        val count = if (now - last > CONSEC_RESET_MS) 1 else previousCount + 1
        CheckmatePrefs.putInt(KEY_CONSEC_ATTEMPTS, count)
        CheckmatePrefs.putLong(KEY_LAST_ATTEMPT_AT, now)
        return count >= HARD_LOCK_THRESHOLD
    }

    /** Clears the consecutive-attempt counter. Called after a hard lock fires (so the next
     *  lock requires a fresh burst) and on a successful guardian PIN unlock. */
    fun resetConsecutiveAttempts() {
        CheckmatePrefs.putInt(KEY_CONSEC_ATTEMPTS, 0)
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
