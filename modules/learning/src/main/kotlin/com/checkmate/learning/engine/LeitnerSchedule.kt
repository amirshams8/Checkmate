package com.checkmate.learning.engine

/**
 * Concept-level Leitner retention scheduler (pure logic — state lives in the planner's
 * RetentionTaskLedger). Exam-oriented box intervals (2 / 4 / 7 / 14 days) sized for a
 * ~month-out test, not a universal long-term SRS curve; the table is configurable.
 *
 * Leitner decides WHETHER a concept is due; the decay equation
 * ([RetentionEngine.retentionScore]) only ranks the due concepts.
 *
 * Evidence rules ([evaluate]): SKIPPED neither promotes nor demotes; fewer than
 * [MIN_ANSWERED] answered questions is inconclusive (box unchanged, short retry delay);
 * otherwise >= [PASS_THRESHOLD] correct promotes one box (capped at [MAX_BOX]) and anything
 * lower drops the concept back to box 1.
 */
object LeitnerSchedule {

    val INTERVAL_DAYS: List<Int> = listOf(2, 4, 7, 14)
    const val MIN_BOX = 1
    const val MAX_BOX = 4
    const val PASS_THRESHOLD = 0.8
    const val MIN_ANSWERED = 2

    /** Inconclusive check (too few answered): box untouched, but nextDueAt is pushed out this
     *  many days so a skipped check doesn't re-spawn every cycle. */
    const val INCONCLUSIVE_RETRY_DAYS = 1

    private const val MS_PER_DAY = 86_400_000L

    data class Outcome(val box: Int, val nextDueAt: Long, val decided: Boolean)

    fun intervalMs(box: Int): Long =
        INTERVAL_DAYS[box.coerceIn(MIN_BOX, MAX_BOX) - 1] * MS_PER_DAY

    /** Due time for a concept with no persisted state yet: lastSeen + the box-1 interval. */
    fun initialDueAt(lastSeen: Long): Long = lastSeen + intervalMs(MIN_BOX)

    /**
     * [answered] must exclude skipped/unanswered questions; [correct] is the answered-and-correct
     * subset.
     */
    fun evaluate(currentBox: Int, answered: Int, correct: Int, now: Long): Outcome {
        val box = currentBox.coerceIn(MIN_BOX, MAX_BOX)
        if (answered < MIN_ANSWERED) {
            return Outcome(box, now + INCONCLUSIVE_RETRY_DAYS * MS_PER_DAY, decided = false)
        }
        val rate = correct.toDouble() / answered
        val newBox = if (rate >= PASS_THRESHOLD) minOf(box + 1, MAX_BOX) else MIN_BOX
        return Outcome(newBox, now + intervalMs(newBox), decided = true)
    }
}
