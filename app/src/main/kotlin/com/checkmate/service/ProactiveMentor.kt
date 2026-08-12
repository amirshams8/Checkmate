package com.checkmate.service

import android.content.Context
import android.util.Log
import com.checkmate.core.CheckmatePrefs
import com.checkmate.planner.PlanStore
import com.checkmate.planner.model.TaskState
import com.checkmate.ui.mentor.MentorViewModel
import com.checkmate.workmode.WorkModeSchedule
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
        val msg = when (kind) {
            "scroll" -> "You've been scrolling $target for a while — take a break before it turns into an hour."
            "site"   -> "You tried opening the site $target while a task was active. That's the ${DistractionGuardThreshold} attempt — stay on task."
            else     -> "You tried opening $target while a task was active. That's the ${DistractionGuardThreshold} attempt — stay on task."
        }
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

    // ── Consistency check ("no tasks in plan == no study") ─────────────────

    private const val KEY_LAST_INACTIVITY_ALERT_DAY = "mentor_last_inactivity_alert_day"
    private const val MISSED_DAYS_ALERT_THRESHOLD = 2

    /**
     * Call once daily (from ReminderService's existing 15-min loop, alongside
     * idleCheckIfNeeded). Fires when PlanStore shows [MISSED_DAYS_ALERT_THRESHOLD]+
     * consecutive days with no completed tasks — including days where no plan was ever
     * generated, which PlanStore.getConsecutiveMissedDays() now counts as a missed day
     * instead of silently ignoring (see PlanStore.DayStudyStatus). Previously an "empty
     * plan" day and a "genuinely caught up, nothing left to do" day looked identical to
     * every stat in the app; this is what makes the difference visible and actionable.
     * Guarded to fire at most once per calendar day, same day-key pattern as
     * idleCheckIfNeeded.
     */
    fun consistencyCheckIfNeeded(context: Context) {
        val cal = Calendar.getInstance()
        val todayKey = "${cal.get(Calendar.YEAR)}_${cal.get(Calendar.DAY_OF_YEAR)}"
        if (CheckmatePrefs.getString(KEY_LAST_INACTIVITY_ALERT_DAY, "") == todayKey) return

        val missedDays = PlanStore.getConsecutiveMissedDays()
        if (missedDays < MISSED_DAYS_ALERT_THRESHOLD) return

        val msg = "No study recorded for $missedDays days in a row — no plan or nothing " +
            "completed. Your guardian has been notified."
        MentorViewModel.appendProactiveMessage(msg)
        MentorNotifier.notify(context, msg)
        GuardianNotifier.notifyStudyInactivity(context, missedDays)
        CheckmatePrefs.putString(KEY_LAST_INACTIVITY_ALERT_DAY, todayKey)
        Log.d(TAG, "consistencyCheckIfNeeded fired: missedDays=$missedDays")
    }

    // ── Weekly holiday prompt ───────────────────────────────────────────────

    private const val KEY_LAST_HOLIDAY_PROMPT_WEEK = "mentor_last_holiday_prompt_week"

    /**
     * Call once daily (from ReminderService's existing 15-min loop). Only actually fires on
     * Wednesdays, and at most once per calendar week (guarded by an ISO-ish "$year_$weekOfYear"
     * key, same pattern as the day-key guards elsewhere in this object) — asks whether any
     * holiday should be marked for the coming days. Marking a holiday is still entirely a
     * guardian action gated behind the PIN (see HolidaySchedule / SettingsScreen's
     * HolidaySettings) — this only surfaces the question regularly instead of relying on
     * someone remembering to check Settings unprompted. Also pings the guardian directly via
     * GuardianNotifier, since they're the one who actually has to act on it.
     */
    fun holidayPromptIfNeeded(context: Context) {
        val cal = Calendar.getInstance()
        if (cal.get(Calendar.DAY_OF_WEEK) != Calendar.WEDNESDAY) return

        val weekKey = "${cal.get(Calendar.YEAR)}_${cal.get(Calendar.WEEK_OF_YEAR)}"
        if (CheckmatePrefs.getString(KEY_LAST_HOLIDAY_PROMPT_WEEK, "") == weekKey) return

        val msg = "Any holidays coming up? Ask your guardian to mark them in " +
            "Settings \u2192 Work Mode \u2192 Holidays. On a marked holiday the usual " +
            "${WorkModeSchedule.LABEL} window is replaced by a single reduced lock, " +
            "1:00 AM \u2013 5:30 PM only, for that day."
        MentorViewModel.appendProactiveMessage(msg)
        MentorNotifier.notify(context, msg)
        GuardianNotifier.notifyHolidayPrompt(context)
        CheckmatePrefs.putString(KEY_LAST_HOLIDAY_PROMPT_WEEK, weekKey)
        Log.d(TAG, "holidayPromptIfNeeded fired")
    }
}
