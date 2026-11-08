package com.checkmate.workmode

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LockdownSweepRequestTest {

    @Before
    fun clear() = LockdownSweepRequest.reset()

    @After
    fun clearAfter() = LockdownSweepRequest.reset()

    @Test
    fun noRequestMeansNoSweep() {
        assertFalse(LockdownSweepRequest.consume(1_000L))
    }

    @Test
    fun requestIsConsumedExactlyOnce() {
        LockdownSweepRequest.request(1_000L)
        assertTrue(LockdownSweepRequest.consume(1_500L))
        assertFalse(LockdownSweepRequest.consume(1_600L))
    }

    @Test
    fun repeatedRequestsStillGiveOneSweep() {
        LockdownSweepRequest.request(1_000L)
        LockdownSweepRequest.request(2_000L)
        assertTrue(LockdownSweepRequest.consume(2_100L))
        assertFalse(LockdownSweepRequest.consume(2_200L))
    }

    @Test
    fun staleRequestIsDroppedNotHonoured() {
        LockdownSweepRequest.request(1_000L)
        assertFalse(LockdownSweepRequest.consume(1_000L + LockdownSweepRequest.TTL_MS + 1))
        // The stale request was cleared by the failed consume.
        assertFalse(LockdownSweepRequest.consume(1_000L + LockdownSweepRequest.TTL_MS + 2))
    }

    @Test
    fun requestExactlyAtTtlIsStillHonoured() {
        LockdownSweepRequest.request(5_000L)
        assertTrue(LockdownSweepRequest.consume(5_000L + LockdownSweepRequest.TTL_MS))
    }

    @Test
    fun clockGoingBackwardsDoesNotSweep() {
        LockdownSweepRequest.request(10_000L)
        assertFalse(LockdownSweepRequest.consume(9_000L))
    }
}
