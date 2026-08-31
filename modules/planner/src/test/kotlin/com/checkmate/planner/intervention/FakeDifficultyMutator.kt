package com.checkmate.planner.intervention

/**
 * P0a continuation. In-memory [DifficultyMutator] fake for [ActionExecutorTest]/
 * [LearningInterventionOrchestratorTest] — mirrors [LedgerDifficultyMutator]'s "one active
 * direction per concept, overwritten not appended" semantics without touching CheckmatePrefs,
 * same reasoning as [FakeTaskMutator] for [TaskMutator]. [adjustCallCount] lets a test assert
 * the no-op guard in [ActionExecutor.applyAdjustDifficulty] actually short-circuited instead
 * of writing an identical value again.
 */
class FakeDifficultyMutator(
    seed: Map<String, DifficultyDirection> = emptyMap()
) : DifficultyMutator {

    private val directions: MutableMap<String, DifficultyDirection> = seed.toMutableMap()

    var adjustCallCount: Int = 0
        private set

    override fun current(conceptId: String): DifficultyDirection? = directions[conceptId]

    override fun adjust(conceptId: String, direction: DifficultyDirection, now: Long) {
        adjustCallCount++
        directions[conceptId] = direction
    }
}
