package com.checkmate.planner.intervention

/**
 * P0a continuation. In-memory [PlanReplanner] fake for [ActionExecutorTest]/
 * [LearningInterventionOrchestratorTest] — same "interface exists so this can be exercised
 * without Android/CheckmatePrefs" reasoning as [FakeTaskMutator] for [TaskMutator]. There is
 * no plan-state field to inspect the way [FakeTaskMutator.currentState] gives every other
 * fake here — REPLAN_DAY's real work is "regenerate and replace via AdaptivePlanner +
 * PlanStore," which this fake deliberately doesn't attempt to simulate — so [callCount] is
 * the only thing a test can assert: that [replanToday] ran, and how many times.
 */
class FakePlanReplanner(
    private val throwOnReplan: Throwable? = null
) : PlanReplanner {

    var callCount: Int = 0
        private set

    override suspend fun replanToday() {
        callCount++
        throwOnReplan?.let { throw it }
    }
}
