package com.checkmate.planner.intervention

/**
 * P0a continuation (REDUCE_DIFFICULTY/INCREASE_DIFFICULTY) — Upgrade Blueprint
 * Phase 2.4/2.5.
 *
 * Seam between [ActionExecutor] and the per-concept difficulty-tier preference store
 * ([ConceptDifficultyLedger]) — same "interface for fakeable unit testing" reasoning as
 * [TaskMutator]/[PlanReplanner]. [LedgerDifficultyMutator] is the real production
 * implementation.
 *
 * HONEST GAP: as of this pass, nothing downstream actually READS a recorded
 * [DifficultyDirection] back to change which questions get selected for a concept —
 * there is no per-concept difficulty tier anywhere in `:modules:learning`'s question
 * selection today (see Thought_process notes: "Question.difficulty is just a string
 * field," "no existing per-concept difficulty preference table"). This interface and
 * [ConceptDifficultyLedger] give REDUCE_DIFFICULTY/INCREASE_DIFFICULTY a real, persisted,
 * idempotent effect (a recorded standing preference per concept) consistent with how
 * every other [PermittedAction] in this package works, ahead of the question-selection
 * logic that will eventually consume it — the same "build the seam before its eventual
 * caller exists" pattern [GapTaskLedger]'s own P0b fields were built under. Wiring an
 * actual question-difficulty filter to read [current] is a separate, not-yet-scoped pass.
 */
interface DifficultyMutator {
    /** The currently recorded preference for [conceptId], or null if none has ever been
     *  recorded — used by [ActionExecutor.applyAdjustDifficulty]'s defensive no-op guard,
     *  same shape as [TaskMutator.findTask] feeding every other applyX's re-check. */
    fun current(conceptId: String): DifficultyDirection?

    /** Records [direction] as the standing preference for [conceptId], overwriting
     *  whatever was there before (a concept has exactly one active direction at a time —
     *  there is no history of prior adjustments to preserve here). */
    fun adjust(conceptId: String, direction: DifficultyDirection, now: Long)
}
