package com.checkmate.service

import android.content.Context
import android.util.Log
import com.checkmate.core.CheckmatePrefs
import com.checkmate.planner.PlanStore
import com.checkmate.planner.model.TaskState
import com.checkmate.ui.mentor.MentorViewModel
import java.util.Calendar

/**
 * ProactiveMentor — Mentor v2 (spec 3.2).
 *
 * Reuses the trigger points that already exist elsewhere in the app (HomeViewModel.markSkip(),
 * DistractionGuard's threshold callback, ReminderService's existing 15-min loop) rather than
 * inventing new AlarmManager plumbing — each of those already fires at the right moment, they
 * just never told Mentor. This object is the thin layer that does: append to the persisted
 * chat history (MentorViewModel.appendProactiveMessage) + surface a notification, for each of
 * the three trigger types called out in the spec.
 */
object ProactiveMentor {

    private const val TAG = "ProactiveMentor"

    /** Call from HomeViewModel.markSkip() with the same reaction text already being spoken via
     *  TTS — this just also logs it into Mentor chat instead of it being a one-off spoken line. */
    fun onSkip(context: Context, reactionText: String) {
        MentorViewModel.appendProactiveMessage(reactionText)
        MentorNotifier.notify(context, reactionText)
        Log.d(TAG, "onSkip logged to Mentor chat")
    }

    /** Call from CheckmateApp's DistractionGuard.listener alongside the existing
     *  GuardianNotifier.notifyDistractionAlert() call. */
    fun onDistractionThreshold(context: Context, kind: String, target: String) {
        val what = if (kind == "site") "the site $target" else target
        val msg = "You tried opening $what while a task was active. That's the ${DistractionGuardThreshold} attempt — stay on task."
        MentorViewModel.appendProactiveMessage(msg)
        MentorNotifier.notify(context, msg)
        Log.d(TAG, "onDistractionThreshold logged to Mentor chat: $kind/$target")
    }

    private const val DistractionGuardThreshold = 3 // mirrors DistractionGuard.ALERT_THRESHOLD; kept
    // as a local literal rather than a cross-module import since :app already depends on
    // :modules:workmode for other things, but pulling in the whole DistractionGuard object here
    // just for one constant isn't worth the coupling for a single number used in copy text.

    // ── Idle check-in ───────────────────────────────────────────────────────
    private const val KEY_LAST_IDLE_NUDGE_DAY = "mentor_last_idle_nudge_day"
    private const val KEY_IDLE_CHECK_HOUR     = "mentor_idle_check_hour"
    private const val DEFAULT_IDLE_CHECK_HOUR = 11 // 11 AM local — configurable via prefs

    /**
     * Call from ReminderService's existing 15-min loop. If it's past the configured check-in
     * hour, nothing is DONE or ACTIVE yet today, and this hasn't already fired today, appends
     * a check-in message to Mentor chat. Same day-key guard pattern as DailyChecklist/PlanStore
     * so this only ever fires once per day regardless of how often the loop runs.
     */
    fun idleCheckIfNeeded(context: Context) {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val checkHour = CheckmatePrefs.getInt(KEY_IDLE_CHECK_HOUR, DEFAULT_IDLE_CHECK_HOUR)
        if (hour < checkHour) return

        val todayKey = "${cal.get(Calendar.YEAR)}_${cal.get(Calendar.DAY_OF_YEAR)}"
        if (CheckmatePrefs.getString(KEY_LAST_IDLE_NUDGE_DAY, "") == todayKey) return

        val tasks = PlanStore.getTodayTasksSnapshot_Sync()
        val hasProgress = tasks.any { it.state == TaskState.DONE || it.state == TaskState.ACTIVE }
        if (hasProgress) return

        val msg = if (tasks.isEmpty())
            "It's past $checkHour:00 and no plan has been generated today. Open Checkmate and generate one."
        else
            "It's past $checkHour:00 and nothing's been started today. ${tasks.size} task(s) waiting."

        MentorViewModel.appendProactiveMessage(msg)
        MentorNotifier.notify(context, msg)
        CheckmatePrefs.putString(KEY_LAST_IDLE_NUDGE_DAY, todayKey)
        Log.d(TAG, "idleCheckIfNeeded fired")
    }
}
