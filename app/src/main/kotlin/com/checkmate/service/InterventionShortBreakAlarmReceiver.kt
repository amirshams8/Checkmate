package com.checkmate.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.checkmate.planner.PlanStore
import com.checkmate.planner.model.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fixes a gap flagged in [com.checkmate.planner.intervention.ActionExecutor.applyShortBreak]'s
 * own comment: a resolved `TAKE_SHORT_BREAK` intent paused the task via
 * [PlanStore.pauseTask], but the negotiated break length (`PermittedAction.ShortBreak.minutes`)
 * was discarded right after validation — nothing ever scheduled waking the student back up,
 * so a "15 minute break" just meant the task sat PAUSED indefinitely until the student
 * remembered to resume it manually themselves.
 *
 * Mirrors [InterventionSnoozeAlarmReceiver] deliberately closely: same one-shot
 * setExactAndAllowWhileIdle pattern (a short break should still wake the device from Doze —
 * the common case is the student locking their phone for the whole break, not an edge case),
 * same "recheck live state right before acting" idiom. The one real difference is what
 * "still relevant" means: snooze re-shows a prompt regardless of what else changed, but this
 * only resumes the task if it's STILL PAUSED when the alarm fires — if the student already
 * resumed manually, moved on to a different task, or the task finished/was skipped in the
 * meantime, firing resumeTask() here would be actively wrong, not just redundant.
 *
 * Known limitation: [com.checkmate.planner.PlanStore.pauseTask] has no concept of *why* a
 * task was paused, so this can't tell "paused via this negotiated short break" apart from
 * "student independently hit pause for an unrelated reason." Treating "still PAUSED
 * `minutes` later" as "the break is over, resume" is the best signal actually available —
 * consistent with how the rest of the intervention pipeline already treats live task state
 * as ground truth (see ActionExecutor's own re-read-before-mutating comment) rather than
 * threading a separate "pause reason" through PlanStore for this one case.
 */
class InterventionShortBreakAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val appContext = context.applicationContext

        val pendingResult = goAsync()
        scope.launch {
            try {
                resumeIfStillPaused(taskId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed resuming $taskId after short break", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun resumeIfStillPaused(taskId: String) {
        val task = PlanStore.todayTasks.value.find { it.id == taskId }
        if (task == null || task.state != TaskState.PAUSED) {
            Log.d(TAG, "Task $taskId no longer PAUSED (or gone) — not auto-resuming")
            return
        }
        PlanStore.resumeTask(taskId, System.currentTimeMillis())
        Log.d(TAG, "Auto-resumed $taskId after short break")
    }

    companion object {
        private const val TAG = "ShortBreakAlarmRx"
        private const val REQUEST_CODE_BASE = 91_000
        const val EXTRA_TASK_ID = "task_id"

        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        /**
         * Same [PendingIntent.FLAG_UPDATE_CURRENT] idiom
         * [InterventionSnoozeAlarmReceiver.scheduleRenotify] uses — calling this again for a
         * task that already has a pending wake-up (e.g. the student negotiates "actually,
         * make it 20 minutes" mid-break) replaces the existing alarm rather than stacking a
         * second one, so only the most recently negotiated break length ever takes effect.
         */
        fun scheduleResume(context: Context, taskId: String, minutes: Int) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, InterventionShortBreakAlarmReceiver::class.java).apply {
                putExtra(EXTRA_TASK_ID, taskId)
            }
            val pi = PendingIntent.getBroadcast(
                context, requestCode(taskId), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val delayMillis = minutes.coerceAtLeast(1) * 60_000L
            am.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + delayMillis,
                pi
            )
            Log.d(TAG, "Short-break resume alarm set for $taskId in ${minutes}min")
        }

        /** Cancels a pending resume — for the (currently unused, but cheap to provide) case
         *  of a caller wanting to cancel a scheduled wake-up outright rather than reschedule
         *  it, e.g. if the task is abandoned entirely before the break ends. */
        fun cancel(context: Context, taskId: String) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, InterventionShortBreakAlarmReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context, requestCode(taskId), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
        }

        private fun requestCode(taskId: String): Int =
            REQUEST_CODE_BASE + (taskId.hashCode() % 10_000)
    }
}
