package com.checkmate.planner.intervention

/**
 * P0a continuation (REDUCE_DIFFICULTY/INCREASE_DIFFICULTY) — Upgrade Blueprint
 * Phase 2.4/2.5. Production [DifficultyMutator] — thin adapter over
 * [ConceptDifficultyLedger], same "interface delegates straight to the real
 * CheckmatePrefs-backed store, no extra logic" shape [PlanStoreTaskMutator] already
 * establishes for [TaskMutator]. Wired into [LearningInterventionOrchestrator.from]'s
 * factory alongside [AdaptivePlanReplanner].
 */
class LedgerDifficultyMutator : DifficultyMutator {

    override fun current(conceptId: String): DifficultyDirection? =
        ConceptDifficultyLedger.current(conceptId)

    override fun adjust(conceptId: String, direction: DifficultyDirection, now: Long) {
        ConceptDifficultyLedger.set(conceptId, direction, now)
    }
}
