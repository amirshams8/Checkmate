package com.checkmate.planner.intervention

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.checkmate.planner.PlanStore

/**
 * Proactive Execution Engine — Step 7 (build), amended in Step 9 (Blueprint Part One, §2,
 * §16) and Step 12 (§22 — see the [OutcomeLedgerWriter] wiring below).
 *
 * "It does not 'constantly run an AI.' It evaluates deterministic signals." Each run walks
 * today's tasks, asks [TriggerEvaluator] whether any fire, and for each one that does —
 * acquires escrow and hands the student a chance to respond before anything is decided for
 * them.
 *
 * Step 9 changes what happens after escrow is acquired. Previously this worker resolved
 * every fired trigger immediately and silently through [InterventionDecisionMaker]'s
 * deterministic fallback (STRICT_REMINDER -> START_TASK), with nothing ever shown to the
 * student — a gap flagged when Step 8 (Context Builder) was built but not yet wired to
 * anything. Now, if [InterventionNotificationBridge.gateway] is wired (from `app`, since
 * planner cannot itself show a notification or reach psyche's ContextBuilder — see that
 * interface's own doc), the worker prompts the student instead and stops there for this
 * task; the transaction stays NEGOTIATING and is resolved later, out of band, by
 * `app`'s InterventionActionReceiver (a tap) or by TTL expiry/reconciliation (silence). If
 * no gateway is wired, or the gateway reports it couldn't show anything (e.g. notification
 * permission denied), this falls back to exactly the old behavior — the deterministic path
 * is never allowed to become unreachable just because the notification layer exists now.
 *
 * Escrow's TTL is widened to [InterventionNotificationBridge.PROMPT_TTL_MILLIS] whenever a
 * gateway is wired, since the 60-second [TaskEscrow.DEFAULT_TTL_MILLIS] default was sized
 * for an active voice negotiation (§6), not for "waits until the student glances at their
 * phone." Without a gateway, escrow still uses the original 60-second default, since nothing
 * is waiting on human attention in that path.
 *
 * Deliberately relies on [TaskEscrow.acquire]'s own atomic check rather than calling
 * [TaskEscrow.isUnderEscrow] first — a separate pre-check would be a second DB read with a
 * race window between it and the acquire call; acquire() already returns AlreadyHeld
 * safely if something else (a concurrent run, or a manually-triggered intervention) beat
 * this one to it.
 */
class InterventionTriggerWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = InterventionDatabase.getInstance(applicationContext)
        val dao = db.interventionTransactionDao()
        val ledgerWriter = OutcomeLedgerWriter(db.outcomeLedgerDao())
        val escrow = TaskEscrow(dao, ledgerWriter)
        val executor = ActionExecutor(dao, escrow, PlanStoreTaskMutator())
        val decisionMaker = InterventionDecisionMaker(escrow, executor)
        val gateway = InterventionNotificationBridge.gateway
        val now = System.currentTimeMillis()

        PlanStore.todayTasks.value.forEach { task ->
            val signal = TriggerEvaluator.evaluate(task, now) ?: return@forEach

            val ttlMillis = if (gateway != null) {
                InterventionNotificationBridge.PROMPT_TTL_MILLIS
            } else {
                TaskEscrow.DEFAULT_TTL_MILLIS
            }
            val acquired = escrow.acquire(task.id, signal.triggerType, now, ttlMillis)
            if (acquired !is EscrowAcquireResult.Acquired) return@forEach

            val dispatched = gateway?.promptStudent(
                context = applicationContext,
                transactionId = acquired.transaction.transactionId,
                task = task,
                lateMinutes = signal.lateMinutes,
                now = now
            )

            if (dispatched != PromptDispatchResult.SHOWN) {
                // No gateway wired, or the gateway couldn't show anything — resolve
                // immediately through the deterministic fallback, same as before this step,
                // rather than leaving the transaction to sit until TTL expiry with nothing
                // ever having been shown to the student.
                decisionMaker.decideAndExecute(
                    transactionId = acquired.transaction.transactionId,
                    task = task,
                    lateMinutes = signal.lateMinutes,
                    now = now
                )
            }
            // dispatched == SHOWN: transaction intentionally left NEGOTIATING here.
        }

        return Result.success()
    }
}
