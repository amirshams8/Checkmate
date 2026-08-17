package com.checkmate.planner.intervention

/**
 * In-memory stand-in for [InterventionTransactionDao] used only by [TaskEscrowTest]. The
 * project has no Robolectric/instrumented-test setup, so real Room can't run as a plain
 * JVM unit test; TaskEscrow only depends on the DAO interface (not on Room or Context)
 * specifically so this substitution is possible. [transactionsById] is exposed and mutable
 * from outside so tests can construct two independent [TaskEscrow] instances that share
 * the same backing store — that's what simulates "the process died and restarted, but the
 * underlying (Room) database survived."
 */
class FakeInterventionTransactionDao(
    val transactionsById: MutableMap<String, InterventionTransaction> = mutableMapOf()
) : InterventionTransactionDao {

    override suspend fun upsert(transaction: InterventionTransaction) {
        transactionsById[transaction.transactionId] = transaction
    }

    override suspend fun update(transaction: InterventionTransaction) {
        transactionsById[transaction.transactionId] = transaction
    }

    override suspend fun getById(transactionId: String): InterventionTransaction? =
        transactionsById[transactionId]

    override suspend fun getLatestForTask(taskId: String): InterventionTransaction? =
        transactionsById.values
            .filter { it.taskId == taskId }
            .maxByOrNull { it.createdAt }

    override suspend fun getUnfinished(terminalStates: List<InterventionState>): List<InterventionTransaction> =
        transactionsById.values.filter { it.currentState !in terminalStates }

    override suspend fun getRecent(limit: Int): List<InterventionTransaction> =
        transactionsById.values.sortedByDescending { it.createdAt }.take(limit)
}
