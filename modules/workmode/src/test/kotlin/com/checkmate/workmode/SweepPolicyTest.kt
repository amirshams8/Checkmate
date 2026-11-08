package com.checkmate.workmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepPolicyTest {

    private val blocked = setOf("com.game.a")
    private val watchlist = setOf("com.game.a", "com.instagram.android")

    @Test
    fun lockdownOutsideEnforcementStillSweeps() {
        // The bug: lockdown active, not enforcing (e.g. the 15:10-19:00 gap) -> no sweep.
        assertTrue(SweepPolicy.sweepActive(enforcing = false, inLockdown = true))
    }

    @Test
    fun lockdownOutsideEnforcementTargetsWatchlistApp() {
        val targets = SweepPolicy.sweepTargets(
            enforcing = false, inLockdown = true, blocked = blocked, watchlist = watchlist
        )
        assertTrue("com.instagram.android" in targets)
    }

    @Test
    fun enforcementWithoutLockdownTargetsOnlyBlockedList() {
        val targets = SweepPolicy.sweepTargets(
            enforcing = true, inLockdown = false, blocked = blocked, watchlist = watchlist
        )
        assertEquals(blocked, targets)
        assertFalse("com.instagram.android" in targets)
    }

    @Test
    fun neitherEnforcingNorLockdownSweepsNothing() {
        assertFalse(SweepPolicy.sweepActive(enforcing = false, inLockdown = false))
        assertTrue(
            SweepPolicy.sweepTargets(
                enforcing = false, inLockdown = false, blocked = blocked, watchlist = watchlist
            ).isEmpty()
        )
    }

    @Test
    fun bothActiveTargetsUnion() {
        val targets = SweepPolicy.sweepTargets(
            enforcing = true, inLockdown = true, blocked = blocked, watchlist = watchlist
        )
        assertEquals(blocked + watchlist, targets)
    }
}
