package com.checkmate.planner.intervention

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proactive Execution Engine — Step 12 (Blueprint Part One, §22, §25 principle 6).
 *
 * Two things are covered separately on purpose: [OutcomeProvenanceDerivation] is pure and
 * synchronous, so it's tested directly with no DAO/coroutine involved at all; everything
 * else here goes through a real [TaskEscrow] wired to both fakes, exercising the three
 * distinct code paths that can write a ledger entry — [TaskEscrow]'s private `resolve()`
 * choke point (commit/abort/resolveAs), [TaskEscrow.acquire]'s inline TTL reclaim, and
 * [TaskEscrow.expireIfPastTtl] — the same three call sites enumerated in that class's own
 * doc. [InterventionDecisionMaker]'s NETWORK_FALLBACK/LLM_INVALID override path is covered
 * in InterventionDecisionMakerTest instead, since it needs the full decideAndExecute
 * pipeline (LLM/PolicyValidator/ActionExecutor) to exercise meaningfully.
 */
class OutcomeLedgerTest {

    private val taskId = "task-1"

    // ── Pure derivation ──────────────────────────────────────────────────

    @Test
    fun `derive maps each terminal state to its matching provenance`() {
        val expected = mapOf(
            InterventionState.COMPLETED to OutcomeProvenance.SUCCESS,
            InterventionState.USER_ABORTED to OutcomeProvenance.USER_ABORTED,
            InterventionState.USER_IGNORED to OutcomeProvenance.USER_IGNORED,
            InterventionState.TTL_EXPIRED to OutcomeProvenance.TTL_EXPIRED,
            InterventionState.EXECUTION_FAILED to OutcomeProvenance.EXECUTION_FAILED,
            InterventionState.POLICY_REJECTED to OutcomeProvenance.POLICY_REJECTED,
            InterventionState.NETWORK_FALLBACK to OutcomeProvenance.NETWORK_FALLBACK,
            InterventionState.LLM_INVALID to OutcomeProvenance.LLM_INVALID
        )
        expected.forEach { (state, provenance) ->
            assertEquals(provenance, OutcomeProvenanceDerivation.derive(state))
        }
    }

    @Test
    fun `derive rejects a non-terminal state`() {
        assertThrows(IllegalArgumentException::class.java) {
            OutcomeProvenanceDerivation.derive(InterventionState.NEGOTIATING)
        }
    }

    // ── TaskEscrow wiring: resolve() choke point (commit/abort/resolveAs) ─

    @Test
    fun `commit writes a SUCCESS ledger entry`() = runTest {
        val dao = FakeInterventionTransactionDao()
        val ledgerDao = FakeOutcomeLedgerDao()
        val escrow = TaskEscrow(dao, OutcomeLedgerWriter(ledgerDao))
        val tx = (escrow.acquire(taskId, InterventionTriggerType.LATE_START, now = 1_000L)
                as EscrowAcquireResult.Acquired).transaction

        escrow.commit(tx.transactionId, outcome = "Session started")

        val entry = ledgerDao.getByTransactionId(tx.transactionId)
        assertEquals(OutcomeProvenance.SUCCESS, entry?.provenance)
        assertEquals(InterventionState.COMPLETED, entry?.terminalState)
        assertEquals(taskId, entry?.taskId)
    }

    @Test
    fun `abort writes a USER_ABORTED ledger entry`() = runTest {
        val dao = FakeInterventionTransactionDao()
        val ledgerDao = FakeOutcomeLedgerDao()
        val escrow = TaskEscrow(dao, OutcomeLedgerWriter(ledgerDao))
        val tx = (escrow.acquire(taskId, InterventionTriggerType.LATE_START, now = 1_000L)
                as EscrowAcquireResult.Acquired).transaction

        escrow.abort(tx.transactionId, reason = "Student dismissed")

        val entry = ledgerDao.getByTransactionId(tx.transactionId)
        assertEquals(OutcomeProvenance.USER_ABORTED, entry?.provenance)
        assertEquals("Student dismissed", entry?.failureReason)
    }

    @Test
    fun `resolveAs writes a matching ledger entry for an arbitrary terminal state`() = runTest {
        val dao = FakeInterventionTransactionDao()
        val ledgerDao = FakeOutcomeLedgerDao()
        val escrow = TaskEscrow(dao, OutcomeLedgerWriter(ledgerDao))
        val tx = (escrow.acquire(taskId, InterventionTriggerType.LATE_START, now = 1_000L)
                as EscrowAcquireResult.Acquired).transaction

        escrow.resolveAs(tx.transactionId, InterventionState.POLICY_REJECTED, failureReason = "duration cap exceeded")

        val entry = ledgerDao.getByTransactionId(tx.transactionId)
        assertEquals(OutcomeProvenance.POLICY_REJECTED, entry?.provenance)
    }

    @Test
    fun `a second commit does not duplicate or corrupt the ledger entry`() = runTest {
        val dao = FakeInterventionTransactionDao()
        val ledgerDao = FakeOutcomeLedgerDao()
        val escrow = TaskEscrow(dao, OutcomeLedgerWriter(ledgerDao))
        val tx = (escrow.acquire(taskId, InterventionTriggerType.LATE_START, now = 1_000L)
                as EscrowAcquireResult.Acquired).transaction

        escrow.commit(tx.transactionId, outcome = "first")
        escrow.commit(tx.transactionId, outcome = "second") // AlreadyResolved, no-op

        assertEquals(1, ledgerDao.entriesByTransactionId.size)
        assertEquals(OutcomeProvenance.SUCCESS, ledgerDao.getByTransactionId(tx.transactionId)?.provenance)
    }

    @Test
    fun `a null ledgerWriter never writes anything and does not affect resolution`() = runTest {
        val dao = FakeInterventionTransactionDao()
        val escrow = TaskEscrow(dao) // no ledgerWriter — backward-compat default
        val tx = (escrow.acquire(taskId, InterventionTriggerType.LATE_START, now = 1_000L)
                as EscrowAcquireResult.Acquired).transaction

        val result = escrow.commit(tx.transactionId, outcome = "Session started")

        assertEquals(EscrowReleaseResult.Released, result)
        assertEquals(InterventionState.COMPLETED, dao.getById(tx.transactionId)?.currentState)
    }

    // ── TaskEscrow wiring: TTL_EXPIRED's two direct-update sites ──────────

    @Test
    fun `acquire's inline TTL reclaim writes a TTL_EXPIRED ledger entry`() = runTest {
        val dao = FakeInterventionTransactionDao()
        val ledgerDao = FakeOutcomeLedgerDao()
        val escrow = TaskEscrow(dao, OutcomeLedgerWriter(ledgerDao))
        val first = (escrow.acquire(taskId, InterventionTriggerType.LATE_START, now = 1_000L, ttlMillis = 500L)
                as EscrowAcquireResult.Acquired).transaction

        // now = 2_000 is well past expiresAt = 1_500 for the first transaction — this
        // acquire() call reclaims it inline before creating a new one.
        escrow.acquire(taskId, InterventionTriggerType.REPEATED_SKIPS, now = 2_000L)

        val entry = ledgerDao.getByTransactionId(first.transactionId)
        assertEquals(OutcomeProvenance.TTL_EXPIRED, entry?.provenance)
        assertEquals(InterventionState.TTL_EXPIRED, entry?.terminalState)
    }

    @Test
    fun `expireIfPastTtl writes a TTL_EXPIRED ledger entry`() = runTest {
        val dao = FakeInterventionTransactionDao()
        val ledgerDao = FakeOutcomeLedgerDao()
        val escrow = TaskEscrow(dao, OutcomeLedgerWriter(ledgerDao))
        val tx = (escrow.acquire(taskId, InterventionTriggerType.LATE_START, now = 1_000L, ttlMillis = 500L)
                as EscrowAcquireResult.Acquired).transaction

        escrow.expireIfPastTtl(tx.transactionId, now = 2_000L)

        val entry = ledgerDao.getByTransactionId(tx.transactionId)
        assertEquals(OutcomeProvenance.TTL_EXPIRED, entry?.provenance)
    }

    // ── overrideProvenance ──────────────────────────────────────────────

    @Test
    fun `overrideProvenance corrects an existing ledger entry's provenance`() = runTest {
        val dao = FakeInterventionTransactionDao()
        val ledgerDao = FakeOutcomeLedgerDao()
        val escrow = TaskEscrow(dao, OutcomeLedgerWriter(ledgerDao))
        val tx = (escrow.acquire(taskId, InterventionTriggerType.LATE_START, now = 1_000L)
                as EscrowAcquireResult.Acquired).transaction
        escrow.commit(tx.transactionId, outcome = "Session started")

        escrow.overrideProvenance(tx.transactionId, OutcomeProvenance.NETWORK_FALLBACK)

        assertEquals(OutcomeProvenance.NETWORK_FALLBACK, ledgerDao.getByTransactionId(tx.transactionId)?.provenance)
    }

    @Test
    fun `overrideProvenance on a transaction with no ledger row is a safe no-op`() = runTest {
        val ledgerDao = FakeOutcomeLedgerDao()
        val writer = OutcomeLedgerWriter(ledgerDao)

        writer.overrideProvenance("does-not-exist", OutcomeProvenance.LLM_INVALID)

        assertNull(ledgerDao.getByTransactionId("does-not-exist"))
    }

    @Test
    fun `overrideProvenance with a null ledgerWriter is a safe no-op`() = runTest {
        val dao = FakeInterventionTransactionDao()
        val escrow = TaskEscrow(dao) // no ledgerWriter
        val tx = (escrow.acquire(taskId, InterventionTriggerType.LATE_START, now = 1_000L)
                as EscrowAcquireResult.Acquired).transaction
        escrow.commit(tx.transactionId)

        // Should not throw.
        escrow.overrideProvenance(tx.transactionId, OutcomeProvenance.NETWORK_FALLBACK)
        assertTrue(true)
    }
}
