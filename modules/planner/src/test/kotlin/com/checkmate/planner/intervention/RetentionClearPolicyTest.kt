package com.checkmate.planner.intervention

import com.checkmate.planner.intervention.RetentionClearPolicy.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionClearPolicyTest {

    private val threshold = 0.83

    @Test
    fun nothingAnsweredDoesNotClear() {
        val o = RetentionClearPolicy.decide(answered = 0, mastery = 0.95, threshold = threshold)
        assertEquals(Outcome.NOT_SUBMITTED, o)
        assertFalse(RetentionClearPolicy.shouldMarkDone(o))
        assertEquals("nothing_answered", RetentionClearPolicy.notClearedReason(o, 0.95))
    }

    @Test
    fun answeredAtThresholdIsMasteredAndClears() {
        val o = RetentionClearPolicy.decide(answered = 8, mastery = 0.83, threshold = threshold)
        assertEquals(Outcome.MASTERED, o)
        assertTrue(RetentionClearPolicy.shouldMarkDone(o))
        assertNull(RetentionClearPolicy.notClearedReason(o, 0.83))
    }

    @Test
    fun answeredAboveThresholdIsMasteredAndClears() {
        val o = RetentionClearPolicy.decide(10, 0.9, threshold)
        assertEquals(Outcome.MASTERED, o)
        assertTrue(RetentionClearPolicy.shouldMarkDone(o))
    }

    @Test
    fun answeredJustBelowThresholdDoesNotClear() {
        val o = RetentionClearPolicy.decide(answered = 8, mastery = 0.82, threshold = threshold)
        assertEquals(Outcome.BELOW_THRESHOLD, o)
        assertFalse(RetentionClearPolicy.shouldMarkDone(o))
        assertEquals("below_threshold", RetentionClearPolicy.notClearedReason(o, 0.82))
    }

    @Test
    fun answeredBelowThresholdDoesNotClear() {
        val o = RetentionClearPolicy.decide(answered = 8, mastery = 0.6, threshold = threshold)
        assertEquals(Outcome.BELOW_THRESHOLD, o)
        assertFalse(RetentionClearPolicy.shouldMarkDone(o))
    }

    @Test
    fun answeredWithUnknownMasteryDoesNotClear() {
        val o = RetentionClearPolicy.decide(answered = 3, mastery = null, threshold = threshold)
        assertEquals(Outcome.BELOW_THRESHOLD, o)
        assertFalse(RetentionClearPolicy.shouldMarkDone(o))
        assertEquals("mastery_unknown", RetentionClearPolicy.notClearedReason(o, null))
    }
}
