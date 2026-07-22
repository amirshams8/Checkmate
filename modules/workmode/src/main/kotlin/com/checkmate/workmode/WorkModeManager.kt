package com.checkmate.workmode

import android.content.Context
import android.content.Intent
import android.os.Build
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.CheckmateState
import com.checkmate.core.StudyMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object WorkModeManager {

    private const val KEY_ACTIVE_SOURCE = "work_mode_active_source"
    private const val SOURCE_MANUAL   = "manual"
    private const val SOURCE_SCHEDULE = "schedule"

    // Mentor v2 (spec 3.4): post-skip escalation lockdown window.
    private const val KEY_LOCKDOWN_UNTIL     = "post_skip_lockdown_until"
    private const val DEFAULT_LOCKDOWN_MIN   = 45
    private const val KEY_LOCKDOWN_MINUTES   = "post_skip_lockdown_minutes"
    // Fixed default watchlist for the escalation window — deliberately broader than the
    // permanent blocklist since it's temporary. Guardian can extend it via
    // "escalation_watchlist" (comma-separated package names), same storage pattern as
    // getBlockedApps()/getBlockedDomains(). Package names, not labels — matched directly
    // against AccessibilityEvent.packageName in AppAutomationService.
    private val DEFAULT_ESCALATION_WATCHLIST = setOf(
        "com.instagram.android",
        "com.google.android.youtube",
        "com.zhiliaoapp.musically",   // TikTok
        "com.snapchat.android",
        "com.facebook.katana",
        "com.twitter.android",
        "com.reddit.frontpage"
    )

    // Mentor v2 (spec 3.5): skip-rate threshold that escalates the guardian-PIN lock.
    private const val KEY_ESCALATION_THRESHOLD = "escalation_skip_threshold"
    private const val DEFAULT_ESCALATION_THRESHOLD = 0.4f

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    /**
     * Call once from CheckmateApp.onCreate and BootReceiver, after
     * CheckmatePrefs.init()/CheckmateState.init(). Syncs the in-memory flag
     * with whatever was persisted before this process started — previously
     * _isActive always reset to false on a fresh process even if the
     * student had an active session, which silently turned off app
     * blocking until the app UI was reopened. Also immediately reconciles
     * against the hardcoded schedule.
     */
    fun init(context: Context) {
        _isActive.value = CheckmateState.currentMode == StudyMode.STUDY
        evaluateSchedule(context)
    }

    fun activate(context: Context, source: String = SOURCE_MANUAL) {
        CheckmateState.setMode(context, StudyMode.STUDY)
        CheckmatePrefs.putString(KEY_ACTIVE_SOURCE, source)
        _isActive.value = true
        startService(context)
    }

    /**
     * Turns Work Mode off. Returns true if it actually turned off.
     *
     * Returns false — and leaves blocking ON — if the hardcoded daily
     * schedule (WorkModeSchedule) is currently in force and no guardian PIN
     * unlock is active. This is what closes the "mark the task done/skip it,
     * then browse freely until the next one" loophole during the
     * 19:00-02:00 window: block mode isn't tied to any single task while
     * the schedule says it should be on.
     */
    fun deactivate(context: Context): Boolean {
        DistractionGuard.reset()
        if (WorkModeSchedule.isWithinScheduledWindow() && !UninstallGuard.isUnlocked()) {
            // Whatever session requested this deactivate (e.g. a manual task
            // finishing) is over, but the hardcoded schedule is still in force.
            // Hand the active-source tag back to "schedule" so evaluateSchedule()
            // can auto-release Work Mode once the window itself ends — otherwise
            // this stays tagged as the task's source (e.g. "manual") forever, the
            // natural-end auto-release check never matches, and blocking silently
            // continues past the hardcoded window until manually toggled off.
            CheckmatePrefs.putString(KEY_ACTIVE_SOURCE, SOURCE_SCHEDULE)
            return false
        }
        CheckmateState.setMode(context, StudyMode.NORMAL)
        CheckmatePrefs.putString(KEY_ACTIVE_SOURCE, "")
        _isActive.value = false
        context.stopService(Intent(context, WorkModeService::class.java))
        return true
    }

    fun toggle(context: Context) {
        if (_isActive.value) deactivate(context) else activate(context)
    }

    /**
     * True whenever app/website blocking must be enforced right now —
     * either a manual/task session is active, or the hardcoded 19:00-02:00
     * window is live. AppAutomationService keys blocking decisions off
     * this instead of the raw [isActive] flag, so the hardcoded window
     * can't be bypassed just by never starting a task, or by the
     * accessibility-service process restarting and losing in-memory state.
     */
    fun isEnforcing(): Boolean = _isActive.value || WorkModeSchedule.isWithinScheduledWindow()

    /**
     * True when Work Mode settings (Blocked Apps, Blocked Websites, Focus
     * Cycle toggles, Guardian Telegram Chat ID) must be read-only in the UI.
     *
     * Locked permanently as soon as a guardian PIN exists — NOT just while
     * a session/the hardcoded window is enforcing. Before any PIN has been
     * generated (first launch / initial setup) these stay open so the
     * guardian can configure them once. After that, the only way past this
     * gate is a guardian PIN unlock — the same UninstallGuard mechanism
     * that gates uninstall protection, so there's still exactly one PIN,
     * and the student never sees it either way. Tying this to enforcement
     * state alone used to leave a window (outside 19:00-02:00, no active
     * session) where these were freely editable; that loophole is now closed.
     */
    // Mentor v2 (spec 3.5): second, independent gate on top of the existing PIN mechanism —
    // even a correct guardian PIN unlock doesn't reopen settings while the recent skip rate
    // is over threshold. This is deliberate escalation, not a bug: a guardian who's just
    // unlocked settings during a bad week should still see them locked back down once the
    // unlock window (UNLOCK_WINDOW_MS in UninstallGuard) lapses, same as before — the new
    // clause only matters while skipRateExceedsThreshold() is true, which requires an actual
    // sustained skip pattern (see DEFAULT_ESCALATION_THRESHOLD), not a one-off.
    // Recommended mitigation for the "guardian genuinely needs in during an escalation"
    // case: UninstallGuard.grantRemoteOverride() (Telegram-command driven, see that file) —
    // it bypasses this gate entirely rather than fighting it.
    fun settingsLocked(): Boolean =
        UninstallGuard.hasPinConfigured() &&
        !UninstallGuard.hasRemoteOverride() &&
        (!UninstallGuard.isUnlocked() || skipRateExceedsThreshold())

    /**
     * Mentor v2 (spec 3.5): reads the skip rate PsycheEngine.refreshBehaviorSummaryCache()
     * writes into "recent_skip_rate" (a plain CheckmatePrefs bridge, same pattern as the
     * "behavior_summary" cache — :modules:workmode has no dependency on :modules:psyche, so
     * this avoids adding one). Threshold is configurable via "escalation_skip_threshold";
     * defaults to DEFAULT_ESCALATION_THRESHOLD.
     */
    fun skipRateExceedsThreshold(): Boolean {
        val rate = CheckmatePrefs.getString("recent_skip_rate", "0")?.toFloatOrNull() ?: 0f
        val threshold = CheckmatePrefs.getString(KEY_ESCALATION_THRESHOLD, null)?.toFloatOrNull()
            ?: DEFAULT_ESCALATION_THRESHOLD
        return rate > threshold
    }

    // Mentor v2 (spec 3.4): post-skip escalation lockdown.

    /** Opens a timed lockdown window starting now. Call from HomeViewModel.markSkip(). */
    fun startPostSkipLockdown(context: Context) {
        val minutes = CheckmatePrefs.getInt(KEY_LOCKDOWN_MINUTES, DEFAULT_LOCKDOWN_MIN)
            .let { if (it <= 0) DEFAULT_LOCKDOWN_MIN else it }
        val until = System.currentTimeMillis() + minutes * 60 * 1000L
        CheckmatePrefs.putLong(KEY_LOCKDOWN_UNTIL, until)
    }

    /** True while a post-skip lockdown window is active. AppAutomationService checks this
     *  alongside the normal blocklist to decide whether to skip DistractionGuard's 3-attempt
     *  grace period for apps on [getEscalationWatchlist]. */
    fun isInPostSkipLockdown(): Boolean =
        System.currentTimeMillis() < CheckmatePrefs.getLong(KEY_LOCKDOWN_UNTIL, 0L)

    /** Package names blocked on first foreground during a post-skip lockdown window — the
     *  permanent blocklist plus a fixed distraction-prone watchlist (extendable via prefs). */
    fun getEscalationWatchlist(): Set<String> {
        val extra = CheckmatePrefs.getString("escalation_watchlist", "") ?: ""
        val extraSet = extra.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        return getBlockedApps() + DEFAULT_ESCALATION_WATCHLIST + extraSet
    }

    /**
     * Reconciles [isActive] with the hardcoded schedule. Call on app start,
     * boot, from the twice-daily schedule alarms (WorkModeScheduleReceiver),
     * and periodically from the accessibility service.
     *
     * Only ever force-starts or force-stops sessions that IT started
     * (tracked via [KEY_ACTIVE_SOURCE] = "schedule") — a manual task session
     * that happens to be running is never auto-cancelled here, and a manual
     * session that outlasts the window keeps running under student control
     * exactly as before.
     */
    fun evaluateSchedule(context: Context) {
        val inWindow = WorkModeSchedule.isWithinScheduledWindow()
        val currentSource = CheckmatePrefs.getString(KEY_ACTIVE_SOURCE, "")

        if (inWindow && !_isActive.value) {
            activate(context, source = SOURCE_SCHEDULE)
        } else if (!inWindow && _isActive.value && currentSource == SOURCE_SCHEDULE) {
            // Window ended naturally and nothing manual took over — release
            // it without requiring a PIN, since this isn't a student action.
            deactivate(context)
        }
    }

    /** Returns package names of apps to block. */
    fun getBlockedApps(): Set<String> {
        val saved = CheckmatePrefs.getString("blocked_apps", "") ?: ""
        return saved.split(",").filter { it.isNotBlank() }.toSet()
    }

    /**
     * Returns hostnames of websites to block (e.g. "youtube.com", "instagram.com").
     * Stored as comma-separated values under "blocked_domains".
     */
    fun getBlockedDomains(): Set<String> {
        val saved = CheckmatePrefs.getString("blocked_domains", "") ?: ""
        return saved.split(",").filter { it.isNotBlank() }.toSet()
    }

    private fun startService(context: Context) {
        val intent = Intent(context, WorkModeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.startForegroundService(intent)
        else
            context.startService(intent)
    }
}
