package com.checkmate.planner.intervention

/**
 * RetentionClearPolicy — pure decision for auto-clearing a RETENTION CHECK task once its
 * submitted Testmate result has been imported, with no manual report import or manual "Done" tap.
 *
 * Rule (Sir's decision): the task is cleared (DONE) only when the student actually answered at
 * least one question AND the concept's mastery is at or above the mastery threshold (0.83).
 * Below the threshold — or when mastery cannot be read — the task stays open, so a retention
 * check that did not demonstrate recall is not silently closed. The reason is reported in the
 * RETENTION_RESOLVED telemetry. (Earlier behaviour cleared on any answered question and only
 * logged the mastery; that is replaced by this rule.)
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

    /** The StudyTask is marked DONE only for [Outcome.MASTERED]; every other outcome leaves it open. */
    fun shouldMarkDone(outcome: Outcome): Boolean = outcome == Outcome.MASTERED

    /** Short machine-readable reason a task was left open, or null when it is cleared. */
    fun notClearedReason(outcome: Outcome, mastery: Double?): String? = when (outcome) {
        Outcome.MASTERED -> null
        Outcome.NOT_SUBMITTED -> "nothing_answered"
        Outcome.BELOW_THRESHOLD -> if (mastery == null) "mastery_unknown" else "below_threshold"
    }
}
