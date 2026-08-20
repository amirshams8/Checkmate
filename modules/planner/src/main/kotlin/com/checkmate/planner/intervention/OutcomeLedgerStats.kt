package com.checkmate.planner.intervention

/**
 * Proactive Execution Engine — Step 14 (Blueprint §26 "Baseline intervention statistics"),
 * built on top of Step 12's Outcome Ledger.
 *
 * "Learning comes after measurement" (§25 principle 7) — this is the measurement half only.
 * Nothing here feeds back into trigger sensitivity, policy, or strategy selection; that's
 * Step 15 (Adaptive strategy learning), deliberately sequenced after this. [InterventionStats]
 * is a plain read model, computed fresh on demand from [OutcomeLedgerDao.getAll] rather than
 * maintained incrementally — at this app's expected volume (a handful of intervention
 * resolutions per student per day) a full scan is cheap enough that there's no reason to take
 * on the complexity of a running aggregate that could drift out of sync with the ledger.
 *
 * Two derived rates matter more than the rest and get named fields of their own rather than
 * being left as something a caller has to reconstruct from [byProvenance] each time:
 *  - [completionRate]: of everything that resolved, how much ended in the student actually
 *    starting/acting on the task (terminalState == COMPLETED, i.e. provenance SUCCESS,
 *    NETWORK_FALLBACK, or LLM_INVALID) as opposed to USER_ABORTED / TTL_EXPIRED /
 *    POLICY_REJECTED / EXECUTION_FAILED.
 *  - [fallbackRate]: *of those completions*, how many were NETWORK_FALLBACK/LLM_INVALID
 *    rather than a genuine LLM-negotiated SUCCESS. This one is a more informative signal
 *    than it might look at first — see [OutcomeProvenance]'s own doc on how it's derived —
 *    because [InterventionDecisionMaker.decideAndExecute]'s three production callers
 *    (the notification's own Start button, the negotiation screen's Start button, and the
 *    TTL-expiry/worker fallback path) never pass an `llmPrompt`, so *every* resolution
 *    through those three always corrects to NETWORK_FALLBACK. A SUCCESS-provenance
 *    completion, today, can only mean one thing: the student actually talked to the AI
 *    Mentor and it resolved decisively. A high [fallbackRate] doesn't mean the LLM is
 *    failing — it's the more literal "how often is the student just tapping Start instead
 *    of negotiating."
 */
data class InterventionStats(
    val totalResolved: Int,
    val completedCount: Int,
    val completionRate: Double,
    val fallbackCount: Int,
    val fallbackRate: Double,
    val byProvenance: Map<OutcomeProvenance, Int>,
    val byTriggerType: Map<InterventionTriggerType, Int>
) {
    companion object {
        val EMPTY = InterventionStats(
            totalResolved = 0,
            completedCount = 0,
            completionRate = 0.0,
            fallbackCount = 0,
            fallbackRate = 0.0,
            byProvenance = emptyMap(),
            byTriggerType = emptyMap()
        )
    }
}

object OutcomeLedgerStats {

    private val FALLBACK_PROVENANCES = setOf(OutcomeProvenance.NETWORK_FALLBACK, OutcomeProvenance.LLM_INVALID)
    private val COMPLETED_PROVENANCES = FALLBACK_PROVENANCES + OutcomeProvenance.SUCCESS

    /**
     * [since] restricts to entries with `resolvedAt >= since` (epoch millis) — e.g. "just
     * today" or "this week" for a guardian-facing summary. Null (the default) computes over
     * the entire ledger.
     */
    suspend fun compute(dao: OutcomeLedgerDao, since: Long? = null): InterventionStats {
        val all = dao.getAll()
        val entries = if (since != null) all.filter { it.resolvedAt >= since } else all
        if (entries.isEmpty()) return InterventionStats.EMPTY

        val byProvenance = entries.groupingBy { it.provenance }.eachCount()
        val byTriggerType = entries.groupingBy { it.triggerType }.eachCount()

        val completedCount = byProvenance.filterKeys { it in COMPLETED_PROVENANCES }.values.sum()
        val fallbackCount = byProvenance.filterKeys { it in FALLBACK_PROVENANCES }.values.sum()

        return InterventionStats(
            totalResolved = entries.size,
            completedCount = completedCount,
            completionRate = completedCount.toDouble() / entries.size,
            fallbackCount = fallbackCount,
            fallbackRate = if (completedCount > 0) fallbackCount.toDouble() / completedCount else 0.0,
            byProvenance = byProvenance,
            byTriggerType = byTriggerType
        )
    }
}
