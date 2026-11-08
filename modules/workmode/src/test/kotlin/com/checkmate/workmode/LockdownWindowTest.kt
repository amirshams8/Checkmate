package com.checkmate.workmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockdownWindowTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun untilIsNowPlusMinutes() {
        assertEquals(t0 + 45 * 60_000L, LockdownWindow.untilMs(t0, 45))
    }

    @Test
    fun activeInsideWindow() {
        val until = LockdownWindow.untilMs(t0, 45)
        assertTrue(LockdownWindow.isActive(t0, until))
        assertTrue(LockdownWindow.isActive(t0 + 44 * 60_000L, until))
    }

    @Test
    fun inactiveAtAndAfterEnd() {
        val until = LockdownWindow.untilMs(t0, 45)
        assertFalse(LockdownWindow.isActive(until, until))
        assertFalse(LockdownWindow.isActive(until + 1, until))
    }

    @Test
    fun neverSetWindowIsInactive() {
        assertFalse(LockdownWindow.isActive(t0, 0L))
    }

    @Test
    fun windowFollowsTheClockPassedIn() {
        // The tamper case: a window stamped on the trusted clock stays active when the
        // trusted clock has not advanced, regardless of what the raw device clock says.
        val until = LockdownWindow.untilMs(t0, 45)
        val rawClockMovedForward60Min = t0 + 60 * 60_000L
        assertFalse(LockdownWindow.isActive(rawClockMovedForward60Min, until)) // raw clock: ends early
        assertTrue(LockdownWindow.isActive(t0 + 10 * 60_000L, until))          // trusted clock: still on
    }
}
