package com.checkmate.planner.intervention

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.checkmate.planner.PlanStore

/**
 * Proactive Execution Engine — Step 7 (Blueprint Part One, §2).
 *
 * "It does not 'constantly run an AI.' It evaluates deterministic signals." Each run walks
 * today's tasks, asks [TriggerEvaluator] whether any fire, and for each one that does —
 * acquires escrow and hands it to [InterventionDecisionMaker]. AI Mentor / Context Builder
 * (steps 8/11) aren't wired yet, so every intervention this worker drives goes through the
 * deterministic fallback path only, same as every other step built so far — this worker
 * doesn't change that, it just supplies the "when" that was missing before it existed.
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
        val dao = InterventionDatabase.getInstance(applicationContext).interventionTransactionDao()
        val escrow = TaskEscrow(dao)
        val executor = ActionExecutor(dao, escrow, PlanStoreTaskMutator())
        val decisionMaker = InterventionDecisionMaker(escrow, executor)
        val now = System.currentTimeMillis()

        PlanStore.todayTasks.value.forEach { task ->
            val signal = TriggerEvaluator.evaluate(task, now) ?: return@forEach

            val acquired = escrow.acquire(task.id, signal.triggerType, now)
            if (acquired !is EscrowAcquireResult.Acquired) return@forEach

            decisionMaker.decideAndExecute(
                transactionId = acquired.transaction.transactionId,
                task = task,
                lateMinutes = signal.lateMinutes,
                now = now
            )
        }

        return Result.success()
    }
}
