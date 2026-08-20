package com.checkmate.planner.intervention

/**
 * In-memory stand-in for [OutcomeLedgerDao], same conventions as
 * [FakeInterventionTransactionDao]: [entriesByTransactionId] is exposed/mutable so tests can
 * inspect what got written without a getter round-trip, and so two independent
 * [OutcomeLedgerWriter]/[TaskEscrow] instances can be pointed at the same backing store the
 * same way TaskEscrowTest's reconciliation tests share a `sharedStore`.
 */
class FakeOutcomeLedgerDao(
    val entriesByTransactionId: MutableMap<String, OutcomeLedgerEntry> = mutableMapOf()
) : OutcomeLedgerDao {

    override suspend fun upsert(entry: OutcomeLedgerEntry) {
        entriesByTransactionId[entry.transactionId] = entry
    }

    override suspend fun updateProvenance(transactionId: String, provenance: OutcomeProvenance) {
        val existing = entriesByTransactionId[transactionId] ?: return
        entriesByTransactionId[transactionId] = existing.copy(provenance = provenance)
    }

    override suspend fun getByTransactionId(transactionId: String): OutcomeLedgerEntry? =
        entriesByTransactionId[transactionId]

    override suspend fun getRecent(limit: Int): List<OutcomeLedgerEntry> =
        entriesByTransactionId.values.sortedByDescending { it.resolvedAt }.take(limit)
}
