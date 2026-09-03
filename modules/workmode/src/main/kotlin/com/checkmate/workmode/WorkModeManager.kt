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

    // BUGFIX (silent delay never enforced): idempotency ledger for overdue-PENDING
    // enforcement, plus the dynamically-detected top-time-consuming app for the current
    // overdue window. See hasAppliedOverdueEnforcement/markOverdueEnforcementApplied/
    // retainOverdueEnforcementOnly/setOverdueTopApp for the actual logic; these two keys
    // are just the storage, same CheckmatePrefs idiom as KEY_LOCKDOWN_UNTIL above.
    private const val KEY_OVERDUE_ESCALATED_TASK_IDS = "overdue_escalated_task_ids"
    private const val KEY_OVERDUE_TOP_APP            = "overdue_top_app_pkg"

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
        TrustedTime.refreshIfNeeded(context)
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

    /** Opens a timed lockdown window starting now. Call from HomeViewModel.markSkip() —
     *  and, as of the overdue-enforcement fix, from `app`'s OverdueEnforcementCoordinator
     *  for a badly-overdue still-PENDING task. Deliberately the same function for both:
     *  it's the identical consequence (temporary lockdown + escalation watchlist), just
     *  reached by two different triggers — an explicit Skip tap, or silence past
     *  threshold. Neither caller may pair this with a TaskState change other than what
     *  that caller already legitimately does on its own terms (markSkip marks SKIPPED
     *  for its own reasons; the overdue path must NOT mark anything). */
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

    /**
     * Package names blocked on first foreground during a post-skip/overdue lockdown
     * window — the permanent blocklist, plus a fixed distraction-prone watchlist
     * (extendable via prefs), plus (BUGFIX, silent delay never enforced) whichever single
     * app [setOverdueTopApp] most recently recorded as the top time-consumer during an
     * overdue-PENDING window. That last piece is what lets this catch a student's actual
     * procrastination target — a game, a browser tab, anything not on the fixed list —
     * rather than only ever blocking the same static seven apps.
     */
    fun getEscalationWatchlist(): Set<String> {
        val extra = CheckmatePrefs.getString("escalation_watchlist", "") ?: ""
        val extraSet = extra.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val overdueTop = CheckmatePrefs.getString(KEY_OVERDUE_TOP_APP, "")
            ?.takeIf { it.isNotBlank() }
        return getBlockedApps() + DEFAULT_ESCALATION_WATCHLIST + extraSet + setOfNotNull(overdueTop)
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

    /**
     * BUGFIX (notification-Start / cross-device-sync never activated WorkMode):
     * exposes the currently-recorded [KEY_ACTIVE_SOURCE] tag so a caller can tell
     * *why* Work Mode is on before deciding whether it's safe to turn off — e.g.
     * [com.checkmate.service.WorkModeTaskReconciler] must not deactivate a session
     * that a guardian toggled on manually or that the hardcoded schedule opened,
     * only one it opened itself (tagged [SOURCE_TASK]). Was private before this
     * fix; nothing outside this object needed to read it.
     */
    fun activeSource(): String = CheckmatePrefs.getString(KEY_ACTIVE_SOURCE, "") ?: ""

    /**
     * BUGFIX (silent delay never enforced): true once overdue enforcement has already
     * been applied for this specific task id. [InterventionTriggerWorker] calls
     * [OverdueEnforcementGateway.applyOverdueEnforcement] every ~15-minute cycle the task
     * stays PENDING past threshold — this is what stops that from re-running
     * startPostSkipLockdown()/re-detecting the top app on every single cycle
     * (09:35 apply, 09:50 no-op, 10:05 no-op, ...) instead of exactly once per overdue
     * episode. Keyed on StudyTask.id (a fresh UUID per generated task — see StudyTask's
     * own doc — never reused across days), not a global flag, so one overdue task doesn't
     * suppress detection for a different one.
     */
    fun hasAppliedOverdueEnforcement(taskId: String): Boolean =
        overdueEscalatedIds().contains(taskId)

    /** Records that overdue enforcement has now been applied for [taskId] — call exactly
     *  once, from the same code path that calls [startPostSkipLockdown] for this reason. */
    fun markOverdueEnforcementApplied(taskId: String) {
        persistOverdueEscalatedIds(overdueEscalatedIds() + taskId)
    }

    /**
     * Drops every escalated-task id NOT in [stillPendingTaskIds]. Call from
     * [com.checkmate.service.WorkModeTaskReconciler] on every PlanStore.todayTasks
     * emission — that's the one place that already observes every task leaving PENDING,
     * regardless of why (started, done, skipped, reverted back to PENDING by
     * GapTaskManager's resolveDoneConcept path, or dropped off today's list entirely by
     * day rollover). A task that's since been resolved has nothing left to guard against
     * re-escalating, and if it ever legitimately becomes PENDING again later (the
     * GapTaskManager revert case), it deserves a fresh overdue evaluation rather than
     * being permanently suppressed by a stale flag from its last time around.
     *
     * Also clears the detected top-app watchlist entry once nothing is left escalated, so
     * a stale detection from an already-resolved delay doesn't linger in
     * [getEscalationWatchlist] indefinitely.
     */
    fun retainOverdueEnforcementOnly(stillPendingTaskIds: Set<String>) {
        val current = overdueEscalatedIds()
        val retained = current.intersect(stillPendingTaskIds)
        if (retained != current) persistOverdueEscalatedIds(retained)
        if (retained.isEmpty() && current.isNotEmpty()) setOverdueTopApp(null)
    }

    /** Records which app [com.checkmate.core.AppUsageTracker.getTopAppInRange] found was
     *  eating the most time during the current overdue-PENDING window — folded into
     *  [getEscalationWatchlist] alongside the fixed watchlist. Pass null to clear it. */
    fun setOverdueTopApp(pkg: String?) {
        CheckmatePrefs.putString(KEY_OVERDUE_TOP_APP, pkg ?: "")
    }

    private fun overdueEscalatedIds(): Set<String> {
        val saved = CheckmatePrefs.getString(KEY_OVERDUE_ESCALATED_TASK_IDS, "") ?: ""
        return saved.split(",").filter { it.isNotBlank() }.toSet()
    }

    private fun persistOverdueEscalatedIds(ids: Set<String>) {
        CheckmatePrefs.putString(KEY_OVERDUE_ESCALATED_TASK_IDS, ids.joinToString(","))
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

    /**
     * BUGFIX (notification-Start / cross-device-sync never activated WorkMode):
     * public (was folded into the private SOURCE_* consts before this fix) so
     * `app`'s [WorkModeTaskReconciler] — a different Gradle module — can tag its
     * own activate() calls and compare against [activeSource]. Kept as a plain
     * string constant to match every other source tag's storage as raw
     * CheckmatePrefs strings.
     */
    const val SOURCE_TASK = "task"
}
