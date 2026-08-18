package com.checkmate.planner.intervention

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Proactive Execution Engine — Step 7 (Blueprint Part One, §2).
 *
 * "WorkManager -> durable background evaluation." Unlike this codebase's existing
 * AlarmManager-based schedules (GuardianNotifier's reports, WorkModeScheduleReceiver),
 * this does NOT need re-arming in BootReceiver: WorkManager persists enqueued periodic
 * work in its own database and reschedules itself after reboot automatically. Call
 * [schedulePeriodicEvaluation] once from CheckmateApp.onCreate() with
 * ExistingPeriodicWorkPolicy.KEEP so repeated app starts don't reset the schedule.
 */
object InterventionTriggerScheduler {

    private const val UNIQUE_WORK_NAME = "checkmate_intervention_trigger_evaluation"

    /** WorkManager enforces a 15-minute floor on periodic work — this is that floor. */
    const val EVALUATION_INTERVAL_MINUTES = 15L

    fun schedulePeriodicEvaluation(context: Context) {
        val request = PeriodicWorkRequestBuilder<InterventionTriggerWorker>(
            EVALUATION_INTERVAL_MINUTES, TimeUnit.MINUTES
        )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
