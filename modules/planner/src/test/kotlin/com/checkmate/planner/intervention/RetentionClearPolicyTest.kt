package com.checkmate.planner.intervention

import com.checkmate.planner.intervention.RetentionClearPolicy.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionClearPolicyTest {

    private val threshold = 0.83

    @Test
    fun nothingAnsweredDoesNotClear() {
        val o = RetentionClearPolicy.decide(answered = 0, mastery = 0.95, threshold = threshold)
        assertEquals(Outcome.NOT_SUBMITTED, o)
        assertFalse(RetentionClearPolicy.shouldMarkDone(o))
    }

    @Test
    fun answeredAtThresholdIsMasteredAndClears() {
        val o = RetentionClearPolicy.decide(answered = 8, mastery = 0.83, threshold = threshold)
        assertEquals(Outcome.MASTERED, o)
        assertTrue(RetentionClearPolicy.shouldMarkDone(o))
    }

    @Test
    fun answeredAboveThresholdIsMastered() {
        assertEquals(Outcome.MASTERED, RetentionClearPolicy.decide(10, 0.9, threshold))
    }

    @Test
    fun answeredBelowThresholdStillClearsTheTask() {
        val o = RetentionClearPolicy.decide(answered = 8, mastery = 0.6, threshold = threshold)
        assertEquals(Outcome.BELOW_THRESHOLD, o)
        assertTrue(RetentionClearPolicy.shouldMarkDone(o))
    }

    @Test
    fun answeredWithUnknownMasteryClearsAsBelowThreshold() {
        val o = RetentionClearPolicy.decide(answered = 3, mastery = null, threshold = threshold)
        assertEquals(Outcome.BELOW_THRESHOLD, o)
        assertTrue(RetentionClearPolicy.shouldMarkDone(o))
    }
}
