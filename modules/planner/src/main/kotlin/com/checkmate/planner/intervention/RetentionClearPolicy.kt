package com.checkmate.planner.intervention

/**
 * RetentionClearPolicy — pure decision for auto-clearing a RETENTION CHECK task once its
 * Testmate evidence has been imported, with no manual report import or manual "Done" tap.
 *
 * Before this, [com.checkmate.service.RetentionCheckManager.evidencePollIfNeeded] imported
 * the evidence and updated the Leitner box but never marked the retention StudyTask DONE, so
 * a submitted retention test could stay PENDING (and later look overdue). Repair tasks
 * already auto-clear via GapTaskManager.autoResolveAfterEvidence.
 *
 * Rule: the task is cleared (DONE) as soon as the student actually answered at least one
 * question — the check happened. Whether the concept is back above the mastery threshold is
 * reported (telemetry) but does not keep the task open: when mastery is still below the
 * threshold, RetentionEngine/LearningDecisionEngine re-queue the concept on their own next
 * pass, same as before.
 *
 * Android-free so it runs as a plain JVM unit test.
 */
object RetentionClearPolicy {

    enum class Outcome {
        /** Nothing answered (all skipped / empty) — leave the task as it is. */
        NOT_SUBMITTED,
        /** Answered, and mastery is at or above the threshold. */
        MASTERED,
        /** Answered, but mastery is unknown or still below the threshold. */
        BELOW_THRESHOLD
    }

    fun decide(answered: Int, mastery: Double?, threshold: Double): Outcome = when {
        answered <= 0 -> Outcome.NOT_SUBMITTED
        mastery != null && mastery >= threshold -> Outcome.MASTERED
        else -> Outcome.BELOW_THRESHOLD
    }

    /** The StudyTask is marked DONE for every outcome except [Outcome.NOT_SUBMITTED]. */
    fun shouldMarkDone(outcome: Outcome): Boolean = outcome != Outcome.NOT_SUBMITTED
}
