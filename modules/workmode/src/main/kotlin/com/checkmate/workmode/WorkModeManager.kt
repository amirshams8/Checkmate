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
     * Cycle toggles) must be read-only in the UI. The only way past this is
     * a guardian PIN unlock — the same UninstallGuard mechanism that gates
     * uninstall protection, so there's still exactly one PIN, and the
     * student never sees it either way.
     */
    fun settingsLocked(): Boolean = isEnforcing() && !UninstallGuard.isUnlocked()

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
