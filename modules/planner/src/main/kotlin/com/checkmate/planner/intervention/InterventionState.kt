package com.checkmate.planner.intervention

/**
 * Proactive Execution Engine — Step 3 (Blueprint Part One, §3-4).
 *
 * The Intervention FSM's states. The "happy path" chain is IDLE -> ... -> COMPLETED;
 * the remaining constants are the failure/exit paths (§3) — an intervention can land on
 * any of these directly from an earlier state rather than always passing through
 * COMPLETED. [TERMINAL_STATES] is what "resolved" means for the core invariant: no
 * intervention may remain indefinitely in an intermediate state.
 */
enum class InterventionState {
    IDLE,
    TRIGGERED,
    PROMPTED,
    WAITING_FOR_RESPONSE,
    NEGOTIATING,
    INTENT_RECEIVED,
    POLICY_VALIDATED,
    ACTION_EXECUTED,
    OUTCOME_RECORDED,
    COMPLETED,

    // ── Failure / exit paths (Blueprint §3) ──
    USER_IGNORED,
    USER_ABORTED,
    TTL_EXPIRED,
    NETWORK_FALLBACK,
    LLM_INVALID,
    POLICY_REJECTED,
    EXECUTION_FAILED;

    companion object {
        /** A transaction sitting in any of these has resolved — reconciliation (§4) only
         *  needs to act on transactions NOT in this set. */
        val TERMINAL_STATES: Set<InterventionState> = setOf(
            COMPLETED, USER_IGNORED, USER_ABORTED, TTL_EXPIRED,
            NETWORK_FALLBACK, LLM_INVALID, POLICY_REJECTED, EXECUTION_FAILED
        )
    }

    val isTerminal: Boolean get() = this in TERMINAL_STATES
}

/**
 * Proactive Trigger Engine's signal types (Blueprint §2). Persisted on the transaction
 * so the Outcome Ledger (a later step) can later ask "which trigger types actually lead
 * to completed interventions." The Trigger Engine itself (§2, build step 7) isn't built
 * yet — this enum exists now only so InterventionTransaction has a real, closed type for
 * triggerType rather than a free-text column.
 */
enum class InterventionTriggerType {
    SCHEDULED_START_APPROACHING,
    TASK_NOT_STARTED,
    LATE_START,
    REPEATED_SKIPS,
    DISTRACTION_DETECTED,
    LONG_INACTIVITY,
    AVAILABLE_TIME_CHANGED,
    SESSION_ENDED,
    UPCOMING_TEST,
    BACKLOG_RISK,
    REPEATED_INTERVENTION_FAILURE
}
