package com.checkmate.planner.intervention

/**
 * P0a continuation (REPLAN_DAY) — Upgrade Blueprint Phase 2.4/2.5.
 *
 * Seam between [ActionExecutor] and the actual whole-day plan regeneration surface
 * ([com.checkmate.planner.AdaptivePlanner.generateDailyPlan] +
 * [com.checkmate.planner.PlanStore.saveTodayTasks]) — same "interface so ActionExecutor
 * can be unit-tested against a fake instead of needing Android Context/CheckmatePrefs"
 * reasoning as [TaskMutator]'s own doc. [AdaptivePlanReplanner] is the real production
 * implementation.
 *
 * Deliberately a single no-argument suspend function: REPLAN_DAY (see
 * [LearningDecisionEngine.LearningInterventionIntent.REPLAN_DAY]'s own doc) always means
 * "regenerate TODAY's plan from the student's current saved configuration" — there is no
 * per-call parameterization PermittedAction.ReplanDay carries beyond the escrow key
 * (which ActionExecutor never passes down here; see its own applyReplanDay doc), so this
 * doesn't need one either.
 */
interface PlanReplanner {
    /**
     * Regenerates and REPLACES today's entire task list — not additive, unlike
     * [com.checkmate.planner.PlanStore.createTask]/`addCustomTask`. Implementations are
     * expected to let any thrown exception propagate; [ActionExecutor.applyReplanDay] is
     * the one place that catches it and turns it into [ExecutionOutcome.Failed].
     */
    suspend fun replanToday()
}
