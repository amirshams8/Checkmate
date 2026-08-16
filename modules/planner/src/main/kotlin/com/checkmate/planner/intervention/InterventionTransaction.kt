package com.checkmate.planner.intervention

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import java.util.UUID

/**
 * Proactive Execution Engine — Step 3 (Blueprint Part One, §4).
 *
 * "We rejected using SharedPreferences as the primary source of truth for the FSM."
 * This is that dedicated Room entity. If the process dies mid-negotiation, this row is
 * what survives — a Worker/receiver restart (a later build step) reads back any
 * non-terminal transaction and reconciles it, instead of the negotiation silently
 * vanishing.
 *
 * [llmIntentJson] and [approvedActionJson] are kept as plain nullable text — an audit
 * trail of what the LLM proposed and what PolicyValidator actually permitted, not a
 * round-trippable object. LlmIntent/PermittedAction aren't wired for polymorphic
 * kotlinx.serialization yet (PermittedAction is a sealed class with per-case fields),
 * and reconciliation as described in §4 only needs currentState/expiresAt/taskId to
 * resume the FSM — it doesn't need to replay the exact killed LLM response. Wiring these
 * two columns for full deserialization can happen if/when something actually needs to
 * read them back as typed objects.
 */
@Entity(tableName = "intervention_transactions")
@TypeConverters(InterventionConverters::class)
data class InterventionTransaction(
    @PrimaryKey
    val transactionId: String = UUID.randomUUID().toString(),
    val taskId: String,
    val currentState: InterventionState,
    val triggerType: InterventionTriggerType,
    val createdAt: Long,
    val expiresAt: Long,
    @ColumnInfo(defaultValue = "0")
    val attemptCount: Int = 0,
    val llmIntentJson: String? = null,
    val approvedActionJson: String? = null,
    val studentResponse: String? = null,
    val outcome: String? = null,
    val failureReason: String? = null
)

object InterventionConverters {
    @TypeConverter
    fun fromState(state: InterventionState): String = state.name

    @TypeConverter
    fun toState(value: String): InterventionState = InterventionState.valueOf(value)

    @TypeConverter
    fun fromTriggerType(type: InterventionTriggerType): String = type.name

    @TypeConverter
    fun toTriggerType(value: String): InterventionTriggerType = InterventionTriggerType.valueOf(value)
}

@Dao
interface InterventionTransactionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(transaction: InterventionTransaction)

    @Update
    suspend fun update(transaction: InterventionTransaction)

    @Query("SELECT * FROM intervention_transactions WHERE transactionId = :transactionId")
    suspend fun getById(transactionId: String): InterventionTransaction?

    /** Most recent transaction for a task, regardless of state — used to check whether a
     *  task is currently under Task Escrow (a later build step). */
    @Query("SELECT * FROM intervention_transactions WHERE taskId = :taskId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestForTask(taskId: String): InterventionTransaction?

    /** Reconciliation query (§4): every transaction not yet resolved. Pass
     *  [InterventionState.TERMINAL_STATES] so this file doesn't need to duplicate that
     *  set. Intended to run once from BootReceiver / a WorkManager reconciliation job. */
    @Query("SELECT * FROM intervention_transactions WHERE currentState NOT IN (:terminalStates)")
    suspend fun getUnfinished(terminalStates: List<InterventionState>): List<InterventionTransaction>

    @Query("SELECT * FROM intervention_transactions ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<InterventionTransaction>
}
