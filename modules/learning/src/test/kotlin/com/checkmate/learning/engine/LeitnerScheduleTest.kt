package com.checkmate.learning.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeitnerScheduleTest {

    private val day = 86_400_000L

    @Test
    fun `initial due date is lastSeen plus the box-1 interval`() {
        assertEquals(1_000L + 2 * day, LeitnerSchedule.initialDueAt(1_000L))
    }

    @Test
    fun `pass with enough answers promotes one box and uses that box's interval`() {
        val out = LeitnerSchedule.evaluate(currentBox = 1, answered = 5, correct = 4, now = 0L)
        assertTrue(out.decided)
        assertEquals(2, out.box)
        assertEquals(4 * day, out.nextDueAt)
    }

    @Test
    fun `fail drops back to box 1`() {
        val out = LeitnerSchedule.evaluate(currentBox = 3, answered = 5, correct = 3, now = 0L)
        assertTrue(out.decided)
        assertEquals(1, out.box)
        assertEquals(2 * day, out.nextDueAt)
    }

    @Test
    fun `promotion is capped at the top box`() {
        val out = LeitnerSchedule.evaluate(currentBox = 4, answered = 4, correct = 4, now = 0L)
        assertEquals(4, out.box)
        assertEquals(14 * day, out.nextDueAt)
    }

    @Test
    fun `zero or one answered is inconclusive and leaves the box unchanged`() {
        val none = LeitnerSchedule.evaluate(currentBox = 3, answered = 0, correct = 0, now = 0L)
        val one = LeitnerSchedule.evaluate(currentBox = 3, answered = 1, correct = 1, now = 0L)
        assertFalse(none.decided)
        assertFalse(one.decided)
        assertEquals(3, none.box)
        assertEquals(3, one.box)
        assertEquals(1 * day, one.nextDueAt)
    }

    @Test
    fun `exactly 80 percent promotes`() {
        val out = LeitnerSchedule.evaluate(currentBox = 1, answered = 5, correct = 4, now = 0L)
        assertEquals(2, out.box)
    }
}
