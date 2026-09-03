package com.checkmate.planner.intervention

import android.content.Context
import com.checkmate.planner.model.StudyTask

/**
 * BUGFIX (silent delay never enforced): a PENDING task past
 * [TriggerEvaluator.OVERDUE_ENFORCEMENT_THRESHOLD_MINUTES] gets a real consequence —
 * WorkMode lockdown + escalation watchlist, the same mechanism HomeViewModel.markSkip()
 * already uses for an explicit Skip tap — instead of just another notification.
 *
 * Deliberately does NOT change the task's TaskState. PENDING + 47 min late is still
 * legitimately recoverable — the student can still press Start — so this must never
 * become SKIPPED just because nobody looked at their phone. Implementations must not
 * call PlanStore.markTask/markSkip or anything else that mutates task state; this is
 * enforcement only, layered on top of a task that's still, and stays, PENDING.
 *
 * Same settable-gateway seam as [InterventionNotificationGateway]/
 * [InterventionGuardianGateway] — planner has no dependency on :modules:workmode (nor
 * should it; the two modules are deliberately kept independent), so this interface is
 * owned here and implemented by `app`'s OverdueEnforcementCoordinator, wired from
 * CheckmateApp.onCreate alongside the other two gateways.
 *
 * [InterventionTriggerWorker] calls this unconditionally every worker cycle the task
 * remains PENDING past threshold — it does not track "have I already done this for this
 * task." Idempotency (applying the consequence once per overdue episode, not once per
 * ~15-minute worker cycle forever) is the implementation's responsibility, via a durable
 * per-task guard — see WorkModeManager.hasAppliedOverdueEnforcement's own doc for where
 * that guard lives and how it gets cleared once the task leaves PENDING.
 */
interface OverdueEnforcementGateway {
    /**
     * [task] is still PENDING at call time — implementations must not mutate its
     * TaskState. [lateMinutes] is [TriggerEvaluator]'s own computed value, already past
     * [TriggerEvaluator.OVERDUE_ENFORCEMENT_THRESHOLD_MINUTES]; implementations should
     * not recompute lateness themselves — this is the single detection source.
     */
    fun applyOverdueEnforcement(context: Context, task: StudyTask, lateMinutes: Int)
}

object OverdueEnforcementBridge {
    @Volatile
    var gateway: OverdueEnforcementGateway? = null
}
