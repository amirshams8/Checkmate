package com.checkmate.planner.intervention

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class OutcomeLedgerStatsTest {

    private fun entry(
        id: String,
        provenance: OutcomeProvenance,
        triggerType: InterventionTriggerType = InterventionTriggerType.LATE_START,
        resolvedAt: Long = 1_000L
    ) = OutcomeLedgerEntry(
        transactionId = id,
        taskId = "task-$id",
        triggerType = triggerType,
        terminalState = InterventionState.COMPLETED, // not consulted by stats; provenance is
        provenance = provenance,
        attemptCount = 0,
        resolvedAt = resolvedAt
    )

    @Test
    fun `empty ledger returns InterventionStats EMPTY`() = runTest {
        val dao = FakeOutcomeLedgerDao()
        assertEquals(InterventionStats.EMPTY, OutcomeLedgerStats.compute(dao))
    }

    @Test
    fun `completionRate counts SUCCESS NETWORK_FALLBACK and LLM_INVALID as completed`() = runTest {
        val dao = FakeOutcomeLedgerDao(mutableMapOf(
            "a" to entry("a", OutcomeProvenance.SUCCESS),
            "b" to entry("b", OutcomeProvenance.NETWORK_FALLBACK),
            "c" to entry("c", OutcomeProvenance.LLM_INVALID),
            "d" to entry("d", OutcomeProvenance.USER_ABORTED),
            "e" to entry("e", OutcomeProvenance.TTL_EXPIRED)
        ))

        val stats = OutcomeLedgerStats.compute(dao)

        assertEquals(5, stats.totalResolved)
        assertEquals(3, stats.completedCount)
        assertEquals(3.0 / 5.0, stats.completionRate, 0.0001)
    }

    @Test
    fun `fallbackRate is a fraction of completions, not of all resolutions`() = runTest {
        val dao = FakeOutcomeLedgerDao(mutableMapOf(
            "a" to entry("a", OutcomeProvenance.SUCCESS),
            "b" to entry("b", OutcomeProvenance.NETWORK_FALLBACK),
            "c" to entry("c", OutcomeProvenance.USER_ABORTED),
            "d" to entry("d", OutcomeProvenance.USER_ABORTED)
        ))

        val stats = OutcomeLedgerStats.compute(dao)

        // completedCount = 2 (SUCCESS + NETWORK_FALLBACK), fallbackCount = 1
        assertEquals(2, stats.completedCount)
        assertEquals(1, stats.fallbackCount)
        assertEquals(0.5, stats.fallbackRate, 0.0001)
        // Not 1/4 — fallbackRate is scoped to completions, not all 4 resolutions.
    }

    @Test
    fun `fallbackRate is zero when there are zero completions, not a division error`() = runTest {
        val dao = FakeOutcomeLedgerDao(mutableMapOf(
            "a" to entry("a", OutcomeProvenance.USER_ABORTED),
            "b" to entry("b", OutcomeProvenance.TTL_EXPIRED)
        ))

        val stats = OutcomeLedgerStats.compute(dao)

        assertEquals(0, stats.completedCount)
        assertEquals(0.0, stats.fallbackRate, 0.0001)
    }

    @Test
    fun `byProvenance and byTriggerType tally correctly`() = runTest {
        val dao = FakeOutcomeLedgerDao(mutableMapOf(
            "a" to entry("a", OutcomeProvenance.SUCCESS, InterventionTriggerType.LATE_START),
            "b" to entry("b", OutcomeProvenance.SUCCESS, InterventionTriggerType.REPEATED_SKIPS),
            "c" to entry("c", OutcomeProvenance.TTL_EXPIRED, InterventionTriggerType.LATE_START)
        ))

        val stats = OutcomeLedgerStats.compute(dao)

        assertEquals(2, stats.byProvenance[OutcomeProvenance.SUCCESS])
        assertEquals(1, stats.byProvenance[OutcomeProvenance.TTL_EXPIRED])
        assertEquals(2, stats.byTriggerType[InterventionTriggerType.LATE_START])
        assertEquals(1, stats.byTriggerType[InterventionTriggerType.REPEATED_SKIPS])
    }

    @Test
    fun `since filters out entries resolved before the cutoff`() = runTest {
        val dao = FakeOutcomeLedgerDao(mutableMapOf(
            "old" to entry("old", OutcomeProvenance.SUCCESS, resolvedAt = 1_000L),
            "new" to entry("new", OutcomeProvenance.SUCCESS, resolvedAt = 5_000L)
        ))

        val stats = OutcomeLedgerStats.compute(dao, since = 3_000L)

        assertEquals(1, stats.totalResolved)
        assertEquals(1, stats.byProvenance[OutcomeProvenance.SUCCESS])
    }
}
