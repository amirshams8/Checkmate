package com.checkmate.planner.intervention

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/**
 * Proactive Execution Engine — Step 12 (Blueprint Part One, §22 "The Feedback Loop",
 * §24 "Final Architecture", §25 principle 6: "Measure intervention effectiveness. Don't
 * merely log behavior — log cause -> intervention -> response -> outcome.").
 *
 * "Every intervention is transactional" (§25 principle 3) already gave us
 * [InterventionTransaction] as the single source of truth for *how* a transaction got
 * resolved (its terminal [InterventionState]). The Outcome Ledger is a second, append-mostly
 * record of *why* — one row per terminal transaction resolution, written at the same choke
 * point [InterventionTransaction] itself resolves through ([TaskEscrow]'s private
 * `resolve()`), so this table can never drift out of sync with "the FSM actually decided
 * this transaction is done." [BehaviorLedger]/PsycheEngine (Part Two) are the eventual
 * downstream readers of this table — nothing reads it yet, same "built ahead of its caller"
 * posture this package has used for earlier steps (e.g. Step 3's InterventionTransaction
 * before Step 4's TaskEscrow existed to write to it).
 *
 * [OutcomeLedgerEntry.transactionId] is the primary key, not a generated one — this is
 * deliberately a 1:1 companion row per [InterventionTransaction], not an independent log
 * table, so `upsert` (REPLACE) is also how [TaskEscrow] corrects an initially-derived
 * provenance after the fact (see [OutcomeProvenance]'s own doc on NETWORK_FALLBACK/
 * LLM_INVALID).
 */
@Entity(tableName = "outcome_ledger_entries")
@TypeConverters(InterventionConverters::class, OutcomeLedgerConverters::class)
data class OutcomeLedgerEntry(
    @PrimaryKey
    val transactionId: String,
    val taskId: String,
    val triggerType: InterventionTriggerType,
    val terminalState: InterventionState,
    val provenance: OutcomeProvenance,
    val attemptCount: Int,
    val resolvedAt: Long,
    val outcome: String? = null,
    val failureReason: String? = null
)

/**
 * Why a transaction ended up where it did — a layer above [InterventionState] itself.
 * Six of these (everything but NETWORK_FALLBACK/LLM_INVALID) are 1:1 derivable from the
 * terminal [InterventionState] alone (see [OutcomeProvenanceDerivation]). The remaining two
 * exist because [InterventionDecisionMaker.decideAndExecute]'s fallback path resolves
 * *identically* to a genuine LLM-negotiated success at [TaskEscrow]'s level — both commit
 * as COMPLETED — so provenance for those two is corrected via a follow-up
 * [TaskEscrow.overrideProvenance] call once decideAndExecute knows `usedFallback` was true,
 * rather than ever being read off `currentState` (see that enum's own doc: NETWORK_FALLBACK
 * and LLM_INVALID are declared states that no production code actually transitions a
 * transaction into).
 */
enum class OutcomeProvenance {
    /** Terminal state was COMPLETED and nothing overrode it — a genuine, decisive
     *  resolution (LLM-negotiated or otherwise), not a fallback. */
    SUCCESS,
    USER_ABORTED,
    /** Declared for completeness alongside [InterventionState.USER_IGNORED] — not
     *  currently reachable, since nothing in production distinguishes "ignored" from
     *  "timed out" today; both resolve via TTL_EXPIRED. See that state's own doc. */
    USER_IGNORED,
    TTL_EXPIRED,
    EXECUTION_FAILED,
    POLICY_REJECTED,
    /** COMPLETED, but no LLM was ever attempted, or [InterventionFallback.attemptLlm]
     *  produced nothing within budget (timeout/blank/gateway failure) — the deterministic
     *  STRICT_REMINDER fallback ran instead. */
    NETWORK_FALLBACK,
    /** COMPLETED, but the LLM *did* respond and [InterventionDecisionMaker] fell back
     *  anyway because [LlmIntentParser] couldn't make sense of it. */
    LLM_INVALID
}

/**
 * Derives [OutcomeProvenance] from an already-terminal [InterventionState]. Pure and
 * synchronous on purpose (see [OutcomeLedgerTest]) — this is the "six of eight categories
 * are fully derivable from terminal state, attempt count, and failure reason text" mapping;
 * `attemptCount`/`failureReason` aren't consulted by the mapping itself today (the terminal
 * state alone is unambiguous for all eight), but stay as parameters/columns because a future
 * refinement (e.g. splitting SUCCESS by attemptCount) shouldn't need a new call site.
 */
object OutcomeProvenanceDerivation {
    fun derive(terminalState: InterventionState, @Suppress("UNUSED_PARAMETER") failureReason: String? = null): OutcomeProvenance {
        require(terminalState.isTerminal) {
            "OutcomeProvenanceDerivation.derive requires a terminal state, got $terminalState"
        }
        return when (terminalState) {
            InterventionState.COMPLETED -> OutcomeProvenance.SUCCESS
            InterventionState.USER_ABORTED -> OutcomeProvenance.USER_ABORTED
            InterventionState.USER_IGNORED -> OutcomeProvenance.USER_IGNORED
            InterventionState.TTL_EXPIRED -> OutcomeProvenance.TTL_EXPIRED
            InterventionState.EXECUTION_FAILED -> OutcomeProvenance.EXECUTION_FAILED
            InterventionState.POLICY_REJECTED -> OutcomeProvenance.POLICY_REJECTED
            InterventionState.NETWORK_FALLBACK -> OutcomeProvenance.NETWORK_FALLBACK
            InterventionState.LLM_INVALID -> OutcomeProvenance.LLM_INVALID
            else -> error("unreachable — $terminalState is terminal but has no provenance mapping")
        }
    }
}

@Dao
interface OutcomeLedgerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: OutcomeLedgerEntry)

    /** Used by [TaskEscrow.overrideProvenance] to correct NETWORK_FALLBACK/LLM_INVALID
     *  after the baseline SUCCESS row has already been written — see [OutcomeProvenance]'s
     *  own doc. A no-op if [transactionId] has no ledger row yet (e.g. ledgerWriter was
     *  null when the transaction resolved). */
    @Query("UPDATE outcome_ledger_entries SET provenance = :provenance WHERE transactionId = :transactionId")
    suspend fun updateProvenance(transactionId: String, provenance: OutcomeProvenance)

    @Query("SELECT * FROM outcome_ledger_entries WHERE transactionId = :transactionId")
    suspend fun getByTransactionId(transactionId: String): OutcomeLedgerEntry?

    @Query("SELECT * FROM outcome_ledger_entries ORDER BY resolvedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<OutcomeLedgerEntry>
}

object OutcomeLedgerConverters {
    @TypeConverter
    fun fromProvenance(provenance: OutcomeProvenance): String = provenance.name

    @TypeConverter
    fun toProvenance(value: String): OutcomeProvenance = OutcomeProvenance.valueOf(value)
}

/**
 * Thin wrapper [TaskEscrow] holds an optional reference to — keeps TaskEscrow's own code
 * talking in terms of "record this transaction" / "override this provenance" rather than
 * reaching into DAO/derivation details itself. Optional and defaulted to null everywhere
 * it's threaded through (see [TaskEscrow]'s constructor doc) so this is purely additive:
 * every existing single-arg `TaskEscrow(dao)` call site — 15+ of them, 22 in
 * TaskEscrowTest.kt alone — keeps compiling and behaving exactly as before, just without a
 * ledger.
 */
class OutcomeLedgerWriter(private val dao: OutcomeLedgerDao) {

    suspend fun record(transaction: InterventionTransaction, now: Long = System.currentTimeMillis()) {
        val provenance = OutcomeProvenanceDerivation.derive(transaction.currentState, transaction.failureReason)
        dao.upsert(
            OutcomeLedgerEntry(
                transactionId = transaction.transactionId,
                taskId = transaction.taskId,
                triggerType = transaction.triggerType,
                terminalState = transaction.currentState,
                provenance = provenance,
                attemptCount = transaction.attemptCount,
                resolvedAt = now,
                outcome = transaction.outcome,
                failureReason = transaction.failureReason
            )
        )
    }

    suspend fun overrideProvenance(transactionId: String, provenance: OutcomeProvenance) {
        dao.updateProvenance(transactionId, provenance)
    }
}
