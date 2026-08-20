package com.checkmate.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.checkmate.planner.PlanStore
import com.checkmate.planner.intervention.ActionExecutor
import com.checkmate.planner.intervention.DecisionOutcome
import com.checkmate.planner.intervention.EscrowExtendResult
import com.checkmate.planner.intervention.InterventionDatabase
import com.checkmate.planner.intervention.InterventionDecisionMaker
import com.checkmate.planner.intervention.OutcomeLedgerWriter
import com.checkmate.planner.intervention.PlanStoreTaskMutator
import com.checkmate.planner.intervention.TaskEscrow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Proactive Execution Engine — Step 9 (Blueprint Part One, §16), amended in Step 12 (§22)
 * to wire the Outcome Ledger through both handlers' [TaskEscrow] instances.
 *
 * Handles the "Start" and "Snooze 5 min" notification actions built by [InterventionNotifier].
 * "Talk to Checkmate" is not handled here — it's a plain [android.app.PendingIntent.getActivity]
 * straight to MainActivity (see [InterventionNotifier]), since it has nothing to resolve on
 * its own yet; Step 10 (STT/TTS) is what will eventually turn that tap into a real
 * negotiation.
 *
 * BroadcastReceiver.onReceive is synchronous and Room/TaskEscrow are suspend APIs, so this
 * follows the standard goAsync() + coroutine pattern (a PendingResult is held open until the
 * coroutine finishes) rather than InterventionReconciliation's fire-and-forget module-scope
 * launch — a receiver's process can be killed as soon as onReceive returns unless goAsync()
 * says otherwise, which fire-and-forget alone doesn't guarantee.
 */
class InterventionActionReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        val transactionId = intent.getStringExtra(InterventionNotifier.EXTRA_TRANSACTION_ID) ?: return
        val taskId = intent.getStringExtra(InterventionNotifier.EXTRA_TASK_ID) ?: return
        val lateMinutes = intent.getIntExtra(InterventionNotifier.EXTRA_LATE_MINUTES, 0)
        val action = intent.action
        val appContext = context.applicationContext

        InterventionNotifier.cancel(appContext, transactionId)

        val pendingResult = goAsync()
        scope.launch {
            try {
                when (action) {
                    InterventionNotifier.ACTION_START -> handleStart(appContext, transactionId, taskId, lateMinutes)
                    InterventionNotifier.ACTION_SNOOZE -> handleSnooze(appContext, transactionId, taskId, lateMinutes)
                    else -> Log.w(TAG, "Unrecognized intervention action: $action")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed handling $action for $transactionId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Student tapped "Start". Resolves through the same deterministic STRICT_REMINDER ->
     * START_TASK -> PolicyValidator -> ActionExecutor path the no-gateway fallback already
     * uses (Blueprint §14/§25 principle 2 — the LLM has no execution path yet regardless of
     * how the student got here) rather than mutating PlanStore directly, so there is exactly
     * one place ("what happens when we decide to start this task") for both the seen and
     * unseen paths to share.
     */
    private suspend fun handleStart(context: Context, transactionId: String, taskId: String, lateMinutes: Int) {
        val task = PlanStore.todayTasks.value.find { it.id == taskId }
        if (task == null) {
            Log.w(TAG, "Start tapped for $transactionId but task $taskId no longer exists")
            return
        }
        val db = InterventionDatabase.getInstance(context)
        val dao = db.interventionTransactionDao()
        val ledgerWriter = OutcomeLedgerWriter(db.outcomeLedgerDao())
        val escrow = TaskEscrow(dao, ledgerWriter)
        val executor = ActionExecutor(dao, escrow, PlanStoreTaskMutator())
        val decisionMaker = InterventionDecisionMaker(escrow, executor)
        val decision = decisionMaker.decideAndExecute(
            transactionId = transactionId,
            task = task,
            lateMinutes = lateMinutes
        )
        // Currently unreachable in practice — decideAndExecute is never given an llmPrompt
        // here, so its deterministic fallback always resolves to START_TASK, never
        // RequestGuardian/ShortBreak. Checked anyway so this handler doesn't quietly need
        // updating the day an llmPrompt does get threaded through the "Start" button too —
        // see InterventionSideEffects' own doc.
        if (decision is DecisionOutcome.Executed) {
            InterventionSideEffects.handle(context, task, transactionId, decision.executionOutcome)
        }
        Log.d(TAG, "Start handled for $transactionId")
    }

    /**
     * Student tapped "Snooze 5 min". Extends the live escrow (see [TaskEscrow.extend]'s doc
     * for why this doesn't touch currentState) and schedules a one-shot re-notification —
     * does NOT re-run [com.checkmate.planner.intervention.TriggerEvaluator] or re-acquire
     * escrow, since the transaction this belongs to is still live and already owns the task.
     */
    private suspend fun handleSnooze(context: Context, transactionId: String, taskId: String, lateMinutes: Int) {
        val db = InterventionDatabase.getInstance(context)
        val dao = db.interventionTransactionDao()
        val ledgerWriter = OutcomeLedgerWriter(db.outcomeLedgerDao())
        val escrow = TaskEscrow(dao, ledgerWriter)
        val result = escrow.extend(transactionId, InterventionNotifier.SNOOZE_MILLIS)
        if (result !is EscrowExtendResult.Extended) {
            Log.w(TAG, "Snooze for $transactionId did not extend (state: $result) — not rescheduling")
            return
        }
        InterventionSnoozeAlarmReceiver.scheduleRenotify(context, transactionId, taskId, lateMinutes)
        Log.d(TAG, "Snoozed $transactionId until ${result.newExpiresAt}")
    }

    companion object {
        private const val TAG = "InterventionActionRx"
    }
}
