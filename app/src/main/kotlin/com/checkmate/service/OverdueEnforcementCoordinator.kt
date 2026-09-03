package com.checkmate.service

import android.content.Context
import com.checkmate.core.AppUsageTracker
import com.checkmate.planner.intervention.OverdueEnforcementGateway
import com.checkmate.planner.model.StudyTask
import com.checkmate.workmode.WorkModeManager
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * BUGFIX (silent delay never enforced): `app`'s implementation of
 * [OverdueEnforcementGateway], wired from CheckmateApp.onCreate. Turns
 * [InterventionTriggerWorker]'s "this PENDING task is N minutes overdue" signal into the
 * same real consequence HomeViewModel.markSkip() already applies for an explicit Skip —
 * WorkMode lockdown + escalation watchlist — without ever touching the task's TaskState.
 * The task stays PENDING and stays startable; see [OverdueEnforcementGateway]'s own doc
 * for why that distinction matters.
 *
 * Two things this adds beyond a plain "call startPostSkipLockdown" forward:
 *
 * 1. Idempotency. [InterventionTriggerWorker] calls [applyOverdueEnforcement]
 *    unconditionally every ~15-minute cycle a task stays overdue — this checks
 *    [WorkModeManager.hasAppliedOverdueEnforcement] first and no-ops if the task's
 *    already been escalated, so a task that's been overdue for two hours gets exactly one
 *    lockdown window opened for it, not a fresh one every cycle.
 *
 * 2. Detecting the actual distraction. Rather than relying solely on the fixed
 *    DEFAULT_ESCALATION_WATCHLIST, this asks [AppUsageTracker.getTopAppInRange] what the
 *    student has actually spent the most time in since the task's scheduled start time,
 *    and folds that single package into [WorkModeManager.getEscalationWatchlist] via
 *    [WorkModeManager.setOverdueTopApp] — catching whatever the real time sink was, watchlist
 *    app or not, not just the seven apps that list happens to name.
 */
object OverdueEnforcementCoordinator : OverdueEnforcementGateway {

    override fun applyOverdueEnforcement(context: Context, task: StudyTask, lateMinutes: Int) {
        // Idempotency guard — see class doc point 1. Must be checked before anything else
        // below runs, since re-detecting the top app and re-opening the lockdown window on
        // every worker cycle is exactly the "09:35 lockdown, 09:50 lockdown again, 10:05
        // lockdown again" behavior this exists to prevent.
        if (WorkModeManager.hasAppliedOverdueEnforcement(task.id)) return

        val topAppPackage = detectTopAppSinceScheduledStart(context, task)
        WorkModeManager.setOverdueTopApp(topAppPackage)

        // Reused, not duplicated: the identical consequence HomeViewModel.markSkip() opens
        // for an explicit Skip tap. Deliberately NOT calling markSkip() itself and
        // deliberately NOT calling PlanStore.markTask anywhere in this file — this task is
        // still PENDING and stays PENDING; only the enforcement layer changes.
        WorkModeManager.startPostSkipLockdown(context)

        WorkModeManager.markOverdueEnforcementApplied(task.id)
    }

    /**
     * Resolves task.scheduledStartTime ("HH:mm") against today's date to get the actual
     * start of the window to inspect, then asks AppUsageTracker for whichever app ate the
     * most foreground time between that moment and now. Returns null (safe no-op fold in
     * getEscalationWatchlist) if the time string doesn't parse, Usage Access isn't
     * granted, or nothing cleared AppUsageTracker's usage floor — [applyOverdueEnforcement]
     * still opens the lockdown window and the fixed watchlist either way.
     */
    private fun detectTopAppSinceScheduledStart(context: Context, task: StudyTask): String? {
        val scheduled = task.scheduledStartTime?.let {
            try { LocalTime.parse(it) } catch (e: Exception) { null }
        } ?: return null

        val startMillis = scheduled.atDate(LocalDate.now())
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val nowMillis = System.currentTimeMillis()

        return AppUsageTracker.getTopAppInRange(context, startMillis, nowMillis)?.packageName
    }
}
