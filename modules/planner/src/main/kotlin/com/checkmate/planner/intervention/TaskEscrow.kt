package com.checkmate.planner.intervention

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Proactive Execution Engine — Step 4 (Blueprint Part One, §5-6).
 *
 * Task Escrow. While a task has a live (non-terminal) [InterventionTransaction], nothing
 * else may start it, edit it, or create a second concurrent negotiation for it — "The FSM
 * owns the task until it reaches COMMIT or ABORT" (§5). This deliberately does NOT touch
 * [com.checkmate.planner.model.TaskState] or [com.checkmate.planner.PlanStore] — there is
 * no NEGOTIATING case added to TaskState, because that enum is matched exhaustively across
 * the UI layer (HomeScreen/HomeViewModel/etc.) and adding a case there is a much wider,
 * riskier change than this step calls for. Escrow ownership is entirely a property of the
 * transaction table from step 3: a task is under escrow iff `dao.getLatestForTask(taskId)`
 * is non-null and non-terminal. Callers that need to gate task-mutation UI on escrow can
 * check that via [isUnderEscrow] without StudyTask ever knowing escrow exists.
 *
 * Atomicity: this is a single-process Android app, not a multi-process one, so the actual
 * correctness boundary is in-process mutual exclusion, not a Room `@Transaction`/SQLite
 * isolation level. Every entry point below funnels through [withTaskLock], which holds a
 * per-taskId [Mutex] for the duration of its read-then-write. Two concurrent acquire()
 * calls for the same task always serialize through that Mutex, so exactly one observes
 * "no existing escrow" and wins; the other sees the transaction the winner just wrote and
 * gets [EscrowAcquireResult.AlreadyHeld]. The lock map itself is never persisted — that's
 * fine, because after a real process death there's no second in-process caller left to
 * race against; the surviving [InterventionTransaction] row is instead picked up by
 * [reconcileUnfinished] (or reclaimed inline by the next [acquire] call for that task).
 */
class TaskEscrow(private val dao: InterventionTransactionDao) {

    private val taskLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Attempts to acquire escrow for [taskId]. Succeeds and creates a new NEGOTIATING
     * transaction unless one already exists and is both non-terminal AND still within its
     * TTL. A non-terminal transaction found past its TTL is treated as abandoned — reclaimed
     * (marked TTL_EXPIRED) in the same locked section, and acquisition then proceeds. This
     * is what makes expiry deterministic even if no reconciliation job has run yet: the very
     * next acquire() attempt for that task will not be blocked by a stale escrow forever.
     */
    suspend fun acquire(
        taskId: String,
        triggerType: InterventionTriggerType,
        now: Long = System.currentTimeMillis(),
        ttlMillis: Long = DEFAULT_TTL_MILLIS
    ): EscrowAcquireResult = withTaskLock(taskId) {
        val existing = dao.getLatestForTask(taskId)
        if (existing != null && !existing.currentState.isTerminal) {
            if (now < existing.expiresAt) {
                return@withTaskLock EscrowAcquireResult.AlreadyHeld(existing)
            }
            // Past TTL and nobody released it — reclaim before proceeding.
            dao.update(
                existing.copy(
                    currentState = InterventionState.TTL_EXPIRED,
                    failureReason = "TTL expired before release; reclaimed by acquire()"
                )
            )
        }

        val transaction = InterventionTransaction(
            taskId = taskId,
            currentState = InterventionState.NEGOTIATING,
            triggerType = triggerType,
            createdAt = now,
            expiresAt = now + ttlMillis
        )
        dao.upsert(transaction)
        EscrowAcquireResult.Acquired(transaction)
    }

    /** COMMIT (§5): the intervention concluded with an action taken. Idempotent — calling
     *  this again (or calling [abort] afterward) on the same transaction is a no-op. */
    suspend fun commit(transactionId: String, outcome: String? = null): EscrowReleaseResult =
        resolve(transactionId, InterventionState.COMPLETED, outcome = outcome)

    /** ABORT (§5): the intervention concluded without acting on the task (e.g. the student
     *  dismissed it, or a later step's negotiation failed cleanly). Idempotent, same as
     *  [commit]. */
    suspend fun abort(transactionId: String, reason: String? = null): EscrowReleaseResult =
        resolve(transactionId, InterventionState.USER_ABORTED, failureReason = reason)

    /**
     * Explicit TTL check for one transaction, independent of [acquire]'s inline reclaim —
     * for a reconciliation job (a later step) that wants to sweep expired escrows without
     * also trying to acquire a new one. Deterministic and idempotent: calling it again after
     * it has already expired (or already resolved by commit/abort) a transaction is a safe
     * no-op.
     */
    suspend fun expireIfPastTtl(
        transactionId: String,
        now: Long = System.currentTimeMillis()
    ): EscrowExpiryResult {
        val current = dao.getById(transactionId) ?: return EscrowExpiryResult.NotFound
        return withTaskLock(current.taskId) {
            val fresh = dao.getById(transactionId) ?: return@withTaskLock EscrowExpiryResult.NotFound
            when {
                fresh.currentState.isTerminal -> EscrowExpiryResult.AlreadyResolved
                now < fresh.expiresAt -> EscrowExpiryResult.NotYetExpired
                else -> {
                    dao.update(
                        fresh.copy(
                            currentState = InterventionState.TTL_EXPIRED,
                            failureReason = "TTL expired; reclaimed by expireIfPastTtl()"
                        )
                    )
                    EscrowExpiryResult.Expired
                }
            }
        }
    }

    /**
     * Reconciliation (§4): "Process dies -> Room remains -> Worker/receiver restarts ->
     * unfinished transaction discovered -> FSM reconciles it." Intended to run once after
     * process restart (from BootReceiver, or a WorkManager job — wiring that trigger point
     * is a later step; this only provides the sweep itself). Every unfinished transaction
     * past its TTL is expired; anything unfinished but still within TTL is left alone and
     * returned so the caller can decide what to do with a live, still-valid negotiation
     * that survived the restart.
     */
    suspend fun reconcileUnfinished(now: Long = System.currentTimeMillis()): ReconciliationResult {
        val unfinished = dao.getUnfinished(InterventionState.TERMINAL_STATES.toList())
        val expired = mutableListOf<InterventionTransaction>()
        val stillLive = mutableListOf<InterventionTransaction>()
        for (transaction in unfinished) {
            when (expireIfPastTtl(transaction.transactionId, now)) {
                is EscrowExpiryResult.Expired -> expired += transaction.copy(currentState = InterventionState.TTL_EXPIRED)
                is EscrowExpiryResult.NotYetExpired -> stillLive += transaction
                // Resolved by a concurrent caller between the query above and this check,
                // or the row vanished — either way it's no longer this sweep's concern.
                is EscrowExpiryResult.AlreadyResolved, is EscrowExpiryResult.NotFound -> Unit
            }
        }
        return ReconciliationResult(expiredTransactions = expired, stillLiveTransactions = stillLive)
    }

    /** True iff [taskId] currently has a non-terminal transaction — i.e. is under escrow
     *  and should not be independently mutated/started elsewhere. */
    suspend fun isUnderEscrow(taskId: String): Boolean {
        val existing = dao.getLatestForTask(taskId) ?: return false
        return !existing.currentState.isTerminal
    }

    private suspend fun resolve(
        transactionId: String,
        state: InterventionState,
        outcome: String? = null,
        failureReason: String? = null
    ): EscrowReleaseResult {
        val current = dao.getById(transactionId) ?: return EscrowReleaseResult.NotFound
        return withTaskLock(current.taskId) {
            val fresh = dao.getById(transactionId) ?: return@withTaskLock EscrowReleaseResult.NotFound
            if (fresh.currentState.isTerminal) return@withTaskLock EscrowReleaseResult.AlreadyResolved
            dao.update(fresh.copy(currentState = state, outcome = outcome, failureReason = failureReason))
            EscrowReleaseResult.Released
        }
    }

    private suspend fun <T> withTaskLock(taskId: String, block: suspend () -> T): T {
        val mutex = taskLocks.getOrPut(taskId) { Mutex() }
        return mutex.withLock { block() }
    }

    companion object {
        /** Blueprint §6 example TTL. */
        const val DEFAULT_TTL_MILLIS = 60_000L
    }
}

sealed class EscrowAcquireResult {
    data class Acquired(val transaction: InterventionTransaction) : EscrowAcquireResult()
    data class AlreadyHeld(val existing: InterventionTransaction) : EscrowAcquireResult()
}

sealed class EscrowReleaseResult {
    object Released : EscrowReleaseResult()
    /** Idempotency case: already COMPLETED/ABORTED/EXPIRED/etc. — commit()/abort() called
     *  twice, or called after TTL already resolved it first. Not an error. */
    object AlreadyResolved : EscrowReleaseResult()
    object NotFound : EscrowReleaseResult()
}

sealed class EscrowExpiryResult {
    object Expired : EscrowExpiryResult()
    object AlreadyResolved : EscrowExpiryResult()
    object NotYetExpired : EscrowExpiryResult()
    object NotFound : EscrowExpiryResult()
}

data class ReconciliationResult(
    val expiredTransactions: List<InterventionTransaction>,
    val stillLiveTransactions: List<InterventionTransaction>
)
