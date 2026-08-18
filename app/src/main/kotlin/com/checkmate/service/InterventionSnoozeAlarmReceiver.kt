package com.checkmate.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.checkmate.planner.PlanStore
import com.checkmate.planner.intervention.InterventionDatabase
import com.checkmate.planner.model.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Proactive Execution Engine — Step 9 (Blueprint Part One, §16 "Snooze 5 min").
 *
 * Fired once, [InterventionNotifier.SNOOZE_MILLIS] after a student taps "Snooze" — re-shows
 * the exact same prompt rather than treating the snoozed moment as a fresh trigger.
 * Deliberately does NOT go through [com.checkmate.planner.intervention.TriggerEvaluator] or
 * [com.checkmate.planner.intervention.TaskEscrow.acquire] again: the transaction this
 * re-notification belongs to already exists — extended, not re-created, by
 * [InterventionActionReceiver.handleSnooze] — so re-acquiring here would either collide with
 * that live escrow (AlreadyHeld) or, worse, risk a second transaction for the same task.
 *
 * Re-checks both the transaction's live state and the task's live state before re-notifying,
 * so a task the student already started/completed some other way in the intervening 5
 * minutes (e.g. opened the app directly and hit Start on HomeScreen) doesn't get re-prompted
 * for no reason — same "recheck live state right before acting" posture ActionExecutor
 * already uses for idempotency.
 */
class InterventionSnoozeAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val transactionId = intent.getStringExtra(InterventionNotifier.EXTRA_TRANSACTION_ID) ?: return
        val taskId = intent.getStringExtra(InterventionNotifier.EXTRA_TASK_ID) ?: return
        val lateMinutes = intent.getIntExtra(InterventionNotifier.EXTRA_LATE_MINUTES, 0)
        val appContext = context.applicationContext

        val pendingResult = goAsync()
        scope.launch {
            try {
                renotify(appContext, transactionId, taskId, lateMinutes)
            } catch (e: Exception) {
                Log.e(TAG, "Failed re-notifying $transactionId after snooze", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun renotify(context: Context, transactionId: String, taskId: String, lateMinutes: Int) {
        val dao = InterventionDatabase.getInstance(context).interventionTransactionDao()
        val transaction = dao.getById(transactionId)
        if (transaction == null || transaction.currentState.isTerminal) {
            Log.d(TAG, "Snoozed transaction $transactionId already resolved — not re-notifying")
            return
        }
        val task = PlanStore.todayTasks.value.find { it.id == taskId }
        if (task == null || task.state != TaskState.PENDING) {
            Log.d(TAG, "Task $taskId no longer PENDING (or gone) — not re-notifying for $transactionId")
            return
        }
        InterventionNotifier.show(context, transactionId, task, lateMinutes, System.currentTimeMillis())
    }

    companion object {
        private const val TAG = "InterventionSnoozeRx"
        private const val REQUEST_CODE_BASE = 90_000

        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        /**
         * Uses setExactAndAllowWhileIdle rather than GuardianNotifier's setRepeating pattern
         * — this is a single one-shot 5-minutes-from-now alarm per snooze, not a recurring
         * daily/weekly schedule, and it needs to still fire even if the device enters Doze in
         * the meantime (a student who snoozes and then locks their phone for the full 5
         * minutes is the common case, not an edge case). SCHEDULE_EXACT_ALARM is already
         * declared in the manifest for this app's other exact-timing needs.
         */
        fun scheduleRenotify(context: Context, transactionId: String, taskId: String, lateMinutes: Int) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, InterventionSnoozeAlarmReceiver::class.java).apply {
                putExtra(InterventionNotifier.EXTRA_TRANSACTION_ID, transactionId)
                putExtra(InterventionNotifier.EXTRA_TASK_ID, taskId)
                putExtra(InterventionNotifier.EXTRA_LATE_MINUTES, lateMinutes)
            }
            val pi = PendingIntent.getBroadcast(
                context, requestCode(transactionId), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + InterventionNotifier.SNOOZE_MILLIS,
                pi
            )
            Log.d(TAG, "Re-notify alarm set for $transactionId in ${InterventionNotifier.SNOOZE_MILLIS}ms")
        }

        private fun requestCode(transactionId: String): Int =
            REQUEST_CODE_BASE + (transactionId.hashCode() % 10_000)
    }
}
