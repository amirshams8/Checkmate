package com.checkmate.workmode

import com.checkmate.workmode.KillGapPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class KillGapPolicyTest {

    private val now = 1_000_000L
    private val old = now - 10 * 60_000L

    private fun eval(
        last: Long = old,
        clean: Boolean = false,
        rebooted: Boolean = false,
        pin: Boolean = false,
        exit: Boolean = false,
        enabled: Boolean = false,
        window: Boolean = true
    ) = KillGapPolicy.evaluate(last, now, clean, rebooted, pin, exit, enabled, window)

    @Test
    fun routeB_longGap_serviceDropped_inWindow_isKill() {
        assertEquals(Verdict.KILL_SUSPECTED, eval())
    }

    @Test
    fun routeA_userRequestedExit_isKillEvenIfServiceWasRestoredAndGapShort() {
        // Watchdog re-added the service within seconds: enabled again, gap tiny.
        assertEquals(Verdict.KILL_SUSPECTED, eval(last = now - 5_000L, exit = true, enabled = true))
    }

    @Test
    fun noHeartbeatEverIsIgnored() {
        assertEquals(Verdict.NO_HEARTBEAT, eval(last = 0L, exit = true))
    }

    @Test
    fun cleanShutdownIsNotAKill() {
        assertEquals(Verdict.CLEAN_SHUTDOWN, eval(clean = true, exit = true))
    }

    @Test
    fun rebootIsLeftToBootGapGuard() {
        assertEquals(Verdict.REBOOTED, eval(rebooted = true, exit = true))
    }

    @Test
    fun guardianPinUnlockSuppresses() {
        assertEquals(Verdict.PIN_UNLOCKED, eval(pin = true, exit = true))
    }

    @Test
    fun shortGapWithoutExitSignalIsIgnored() {
        assertEquals(Verdict.GAP_TOO_SHORT, eval(last = now - 30_000L))
    }

    @Test
    fun gapExactlyAtThresholdCounts() {
        assertEquals(Verdict.KILL_SUSPECTED, eval(last = now - KillGapPolicy.GAP_THRESHOLD_MS))
    }

    @Test
    fun longGapButServiceStillEnabledIsBenignOsKill() {
        assertEquals(Verdict.STILL_ENABLED, eval(enabled = true))
    }

    @Test
    fun noEnforcementWindowMeansNoReaction() {
        assertEquals(Verdict.NO_ENFORCEMENT_WINDOW, eval(window = false))
        assertEquals(Verdict.NO_ENFORCEMENT_WINDOW, eval(exit = true, window = false))
    }

    @Test
    fun clockGoingBackwardsDoesNotCountAsGap() {
        assertEquals(Verdict.GAP_TOO_SHORT, eval(last = now + 60_000L))
    }
}
