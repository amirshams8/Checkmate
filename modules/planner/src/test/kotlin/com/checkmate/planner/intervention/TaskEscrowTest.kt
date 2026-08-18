package com.checkmate.planner.intervention

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proactive Execution Engine — Step 4 (Blueprint Part One, §5-6). Covers atomic
 * acquisition under concurrency, commit/abort idempotency, deterministic TTL expiry, and
 * process-death reconciliation. LLM/voice/ActionExecutor are not involved anywhere here —
 * this is exercising TaskEscrow in isolation, as scoped.
 */
class TaskEscrowTest {

    private val taskId = "task-1"

    // ── Acquisition ──────────────────────────────────────────────────────

    @Test
    fun `acquire succeeds when no existing escrow`() = runTest {
        val escrow = TaskEscrow(FakeInterventionTransactionDao())
        val result = escrow.acquire(taskId, InterventionTriggerType.LATE_START, now = 1_000L)
        assertTrue(result is EscrowAcquireResult.Acquired)
        val tx = (result as EscrowAcquireResult.Acquired).transaction
        assertEquals(InterventionState.NEGOTIATING, tx.currentState)
        assertEquals(taskId, tx.taskId)
    }

    @Test
    fun `acquire is denied while a non-expired escrow is already held`() = runTest {
        val escrow = TaskEscrow(FakeInterventionTransactionDao())
        escrow.acquire(taskId, InterventionTriggerType.LATE_START, now = 1_000L, ttlMillis = 60_000L)

        val second = escrow.acquire(taskId, InterventionTriggerType.DISTRACTION_DETECTED, now = 1_500L)
        assertTrue(second is EscrowAcquireResult.AlreadyHeld)
    }

    @Test
    fun `acquire reclaims an escrow past its TTL and succeeds`() = runTest {
        val dao = FakeInterventionTransactionDao()
        val escrow = TaskEscrow(dao)
        val first = escrow.acquire(taskId, InterventionTriggerType.LATE_START, now = 1_000L, ttlMillis = 500L)
        val firstId = (first as EscrowAcquireResult.Acquired).transaction.transactionId

        // now = 2_000 is well past expiresAt = 1_500 for the first transaction.
        val second = escrow.acquire(taskId, InterventionTriggerType.REPEATED_SKIPS, now = 2_000L)
        assertTrue(second is EscrowAcquireResult.Acquired)

        val reclaimed = dao.getById(firstId)
        assertEquals(InterventionState.TTL_EXPIRED, reclaimed?.currentState)
    }

    @Test
    fun `concurrent acquisition for the same task yields exactly one winner`() = runTest {
        val escrow = TaskEscrow(FakeInterventionTransactionDao())
        val attempts = 10

        val results = (1..attempts).map { i ->
            async { escrow.acquire(taskId, InterventionTriggerType.LATE_START, now = 1_000L + i) }
        }.awaitAll()

        val acquired = results.filterIsInstance<EscrowAcquireResult.Acquired>()
        val denied = results.filterIsInstance<EscrowAcquireResult.AlreadyHeld>()
        assertEquals(1, acquired.size)
        assertEquals(attempts - 1, denied.size)
    }

    @Test
    fun `concurrent acquisition for different tasks does not contend`() = runTest {
        val escrow = TaskEscrow(FakeInterventionTransactionDao())
        val taskIds = (1..5).map { "task-$it" }

        val results = taskIds.map { id ->
            async { escrow.acquire(id, InterventionTriggerType.LATE_START, now = 1_000L) }
        }.awaitAll()

        assertTrue(results.all { it is EscrowAcquireResult.Acquired })
    }

    // ── Commit / abort ───────────────────────────────────────────────────

    @Test
    fun `commit resolves the transaction to COMPLETED`() = runTest {
        val dao = FakeInterventionTransactionDao()
        val escrow = TaskEscrow(dao)
        val tx = (escrow.acquire(taskId, InterventionTriggerType.LATE_START, now = 1_000L)
                as EscrowAcquireResult.Acquired).transaction

        val result = escrow.commit(tx.transactionId, outcome = "Session started, 35 min")
        assertEquals(EscrowReleaseResult.Released, result)
        assertEquals(InterventionState.COMPLETED, dao.getById(tx.transactionId)?.currentState)
    }

    @Test
    fun `abort resolves the transaction to USER_ABORTED`() = runTest {
        val dao = FakeInterventionTransactionDao()
        val escrow = TaskEscrow(dao)
        val tx = (escrow.acquire(taskId, InterventionTriggerType.LATE_START, now = 1_000L)
                as EscrowAcquireResult.Acquired).transaction

        val result = escrow.abort(tx.transactionId, reason = "Student dismissed the prompt")
        assertEquals(EscrowReleaseResult.Released, result)
        assertEquals(InterventionState.USER_ABORTED, dao.getById(tx.transactionId)?.currentState)
    }

    @Test
    fun `commit is idempotent when called twice`() = runTest {
        val dao = FakeInterventionTransactionDao()
        val escrow = TaskEscrow(dao)
        val tx = (escrow.acquire(taskId, InterventionTriggerType.LATE_START, now = 1_000L)
                as EscrowAcquireResult.Acquired).transaction

        escrow.commit(tx.transactionId, outcome = "first")
        val secondCall = escrow.commit(tx.transactionId, outcome = "second")

        assertEquals(EscrowReleaseResult.AlreadyResolved, secondCall)
        // The original resolution is untouched by the redundant second call.
        assertEquals("first", dao.getById(tx.transactionId)?.outcome)
    }

    @Test
    fun `abort after commit is idempotent and does not override the resolution`() = runTest {
        val dao = FakeInterventionTransactionDao()
        val escrow = TaskEscrow(dao)
        val tx = (escrow.acquire(taskId, InterventionTriggerType.LATE_START, now = 1_000L)
                as EscrowAcquireResult.Acquired).transaction

        escrow.commit(tx.transactionId, outcome = "Session started")
        val abortAfterCommit = escrow.abort(tx.transactionId, reason = "too late")

        assertEquals(EscrowReleaseResult.AlreadyResolved, abortAfterCommit)
        assertEquals(InterventionState.COMPLETED, dao.getById(tx.transactionId)?.currentState)
    }

    @Test
    fun `commit on an unknown transaction returns NotFound`() = runTest {
        val escrow = TaskEscrow(FakeInterventionTransactionDao())
        assertEquals(EscrowReleaseResult.NotFound, escrow.commit("does-not-exist"))
    }

    @Test
    fun `escrow releases after commit, allowing a new acquisition for the same task`() = runTest {
        val dao = FakeInterventionTransactionDao()
        val escrow = TaskEscrow(dao)
        val tx = (escrow.acquire(taskId, InterventionTriggerType.LATE_START, now = 1_000L)
                as EscrowAcquireResult.Acquired).transaction
        escrow.commit(tx.transactionId)

        assertFalse(escrow.isUnderEscrow(taskId))
        val second = escrow.acquire(taskId, InterventionTriggerType.LATE_START, now = 2_000L)
        assertTrue(second is EscrowAcquireResult.Acquired)
    }

    // ── Deterministic expiry ────────────────────────────────────────────

    @Test
    fun `expireIfPastTtl is a no-op before the TTL elapses`() = runTest {
        val dao = FakeInterventionTransactionDao()
        val escrow = TaskEscrow(dao)
        val tx = (escrow.acquire(taskId, InterventionTriggerType.LATE_START, now = 1_000L, ttlMillis = 60_000L)
                as EscrowAcquireResult.Acquired).transaction

        val result = escrow.expireIfPastTtl(tx.transactionId, now = 1_500L)
        assertEquals(EscrowExpiryResult.NotYetExpired, result)
        assertEquals(InterventionState.NEGOTIATING, dao.getById(tx.transactionId)?.currentState)
    }

    @Test
    fun `expireIfPastTtl expires a transaction once the TTL has elapsed`() = runTest {
        val dao = FakeInterventionTransactionDao()
        val escrow = TaskEscrow(dao)
        val tx = (escrow.acquire(taskId, InterventionTriggerType.LATE_START, now = 1_000L, ttlMillis = 500L)
                as EscrowAcquireResult.Acquired).transaction

        val result = escrow.expireIfPastTtl(tx.transactionId, now = 2_000L)
        assertEquals(EscrowExpiryResult.Expired, result)
        assertEquals(InterventionState.TTL_EXPIRED, dao.getById(tx.transactionId)?.currentState)
    }

    @Test
    fun `expireIfPastTtl on an already-resolved transaction is idempotent`() = runTest {
        val dao = FakeInterventionTransactionDao()
        val escrow = TaskEscrow(dao)
        val tx = (escrow.acquire(taskId, InterventionTriggerType.LATE_START, now = 1_000L, ttlMillis = 500L)
                as EscrowAcquireResult.Acquired).transaction
        escrow.commit(tx.transactionId)

        val result = escrow.expireIfPastTtl(tx.transactionId, now = 2_000L)
        assertEquals(EscrowExpiryResult.AlreadyResolved, result)
        assertEquals(InterventionState.COMPLETED, dao.getById(tx.transactionId)?.currentState)
    }

    @Test
    fun `expireIfPastTtl on an unknown transaction returns NotFound`() = runTest {
        val escrow = TaskEscrow(FakeInterventionTransactionDao())
        assertEquals(EscrowExpiryResult.NotFound, escrow.expireIfPastTtl("does-not-exist", now = 2_000L))
    }

    // ── Process death / reconciliation ──────────────────────────────────

    @Test
    fun `reconcileUnfinished expires past-TTL transactions and leaves live ones alone`() = runTest {
        val dao = FakeInterventionTransactionDao()
        val escrow = TaskEscrow(dao)
        val stale = (escrow.acquire("task-stale", InterventionTriggerType.LATE_START, now = 1_000L, ttlMillis = 500L)
                as EscrowAcquireResult.Acquired).transaction
        val live = (escrow.acquire("task-live", InterventionTriggerType.LATE_START, now = 1_000L, ttlMillis = 60_000L)
                as EscrowAcquireResult.Acquired).transaction

        val result = escrow.reconcileUnfinished(now = 2_000L)

        assertEquals(1, result.expiredTransactions.size)
        assertEquals(stale.transactionId, result.expiredTransactions.first().transactionId)
        assertEquals(1, result.stillLiveTransactions.size)
        assertEquals(live.transactionId, result.stillLiveTransactions.first().transactionId)
        assertEquals(InterventionState.TTL_EXPIRED, dao.getById(stale.transactionId)?.currentState)
        assertEquals(InterventionState.NEGOTIATING, dao.getById(live.transactionId)?.currentState)
    }

    @Test
    fun `a fresh TaskEscrow instance over the same backing store reconciles a surviving transaction`() = runTest {
        // Simulates process death: instance A acquires escrow but never gets to commit()
        // or abort() before the process is killed. The backing store (a stand-in for the
        // Room database, which survives process death) is shared with instance B, which
        // has a brand-new (empty) in-process Mutex map — exactly what happens on restart.
        val sharedStore = mutableMapOf<String, InterventionTransaction>()
        val dao = FakeInterventionTransactionDao(sharedStore)
        val instanceA = TaskEscrow(dao)
        val tx = (instanceA.acquire(taskId, InterventionTriggerType.LATE_START, now = 1_000L, ttlMillis = 500L)
                as EscrowAcquireResult.Acquired).transaction
        // instanceA "dies" here — never calls commit()/abort().

        val instanceB = TaskEscrow(FakeInterventionTransactionDao(sharedStore))
        assertTrue(instanceB.isUnderEscrow(taskId))

        val reconciliation = instanceB.reconcileUnfinished(now = 2_000L)
        assertEquals(1, reconciliation.expiredTransactions.size)
        assertEquals(tx.transactionId, reconciliation.expiredTransactions.first().transactionId)

        assertFalse(instanceB.isUnderEscrow(taskId))
        val reAcquired = instanceB.acquire(taskId, InterventionTriggerType.LATE_START, now = 2_100L)
        assertTrue(reAcquired is EscrowAcquireResult.Acquired)
    }

    @Test
    fun `a fresh TaskEscrow instance over the same backing store leaves a still-live transaction untouched`() = runTest {
        val sharedStore = mutableMapOf<String, InterventionTransaction>()
        val dao = FakeInterventionTransactionDao(sharedStore)
        val instanceA = TaskEscrow(dao)
        val tx = (instanceA.acquire(taskId, InterventionTriggerType.LATE_START, now = 1_000L, ttlMillis = 60_000L)
                as EscrowAcquireResult.Acquired).transaction
        // instanceA "dies" here, well within the TTL window.

        val instanceB = TaskEscrow(FakeInterventionTransactionDao(sharedStore))
        val reconciliation = instanceB.reconcileUnfinished(now = 1_500L)

        assertTrue(reconciliation.expiredTransactions.isEmpty())
        assertEquals(1, reconciliation.stillLiveTransactions.size)
        assertEquals(tx.transactionId, reconciliation.stillLiveTransactions.first().transactionId)
        assertTrue(instanceB.isUnderEscrow(taskId))
    }

    // ── Snooze extension (Step 9, §16 "Snooze 5 min") ───────────────────

    @Test
    fun `extend pushes expiresAt forward without changing state`() = runTest {
        val dao = FakeInterventionTransactionDao()
        val escrow = TaskEscrow(dao)
        val tx = (escrow.acquire(taskId, InterventionTriggerType.LATE_START, now = 1_000L, ttlMillis = 300_000L)
                as EscrowAcquireResult.Acquired).transaction

        val result = escrow.extend(tx.transactionId, additionalMillis = 300_000L, now = 1_500L)

        assertTrue(result is EscrowExtendResult.Extended)
        val stored = dao.getById(tx.transactionId)
        assertEquals(InterventionState.NEGOTIATING, stored?.currentState)
        assertEquals((result as EscrowExtendResult.Extended).newExpiresAt, stored?.expiresAt)
        assertTrue(stored!!.expiresAt > tx.expiresAt)
    }

    @Test
    fun `extend on an already-resolved transaction returns AlreadyResolved and does not revive it`() = runTest {
        val dao = FakeInterventionTransactionDao()
        val escrow = TaskEscrow(dao)
        val tx = (escrow.acquire(taskId, InterventionTriggerType.LATE_START, now = 1_000L)
                as EscrowAcquireResult.Acquired).transaction
        escrow.commit(tx.transactionId, outcome = "Session started")

        val result = escrow.extend(tx.transactionId, additionalMillis = 300_000L, now = 1_500L)

        assertEquals(EscrowExtendResult.AlreadyResolved, result)
        assertEquals(InterventionState.COMPLETED, dao.getById(tx.transactionId)?.currentState)
    }

    @Test
    fun `extend on an unknown transaction returns NotFound`() = runTest {
        val escrow = TaskEscrow(FakeInterventionTransactionDao())
        assertEquals(EscrowExtendResult.NotFound, escrow.extend("does-not-exist", additionalMillis = 300_000L, now = 1_500L))
    }

    @Test
    fun `extend from a snoozed deadline still in the future extends from the existing deadline, not from now`() = runTest {
        val dao = FakeInterventionTransactionDao()
        val escrow = TaskEscrow(dao)
        val tx = (escrow.acquire(taskId, InterventionTriggerType.LATE_START, now = 1_000L, ttlMillis = 300_000L)
                as EscrowAcquireResult.Acquired).transaction
        // expiresAt = 301_000. Extending at now=1_500 (well before expiry) should add on top
        // of the existing deadline, not restart a fresh 300_000ms window from now=1_500.
        val result = escrow.extend(tx.transactionId, additionalMillis = 300_000L, now = 1_500L)

        assertTrue(result is EscrowExtendResult.Extended)
        assertEquals(tx.expiresAt + 300_000L, (result as EscrowExtendResult.Extended).newExpiresAt)
    }
}
