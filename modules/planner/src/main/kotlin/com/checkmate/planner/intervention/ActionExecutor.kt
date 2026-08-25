package com.checkmate.planner.intervention

import com.checkmate.planner.model.TaskState

/**
 * Proactive Execution Engine — Step 5 (Blueprint Part One, §13).
 *
 * "The Executor is the only component allowed to actually mutate application state."
 * ActionExecutor takes a [PermittedAction] — something PolicyValidator already approved —
 * and applies it through [TaskMutator], then resolves the owning [InterventionTransaction]
 * via [TaskEscrow] so escrow is always released exactly once execution is done, success or
 * failure. LLM, voice, and the Trigger Engine are not wired to this yet — nothing calls
 * ActionExecutor from production code in this step.
 *
 * Idempotency (§13: "the application should not create two sessions or mutate the plan
 * twice") has two layers here:
 *  1. Primary guard — [execute] first checks whether [transactionId]'s transaction is
 *     already terminal. A duplicate delivery of an already-resolved transaction is a
 *     cheap no-op ([ExecutionOutcome.TransactionAlreadyResolved]) before any mutation is
 *     attempted at all.
 *  2. Defensive guard — each `applyX` function re-reads the *live* task via [TaskMutator]
 *     and compares against what the action would produce, rather than trusting that the
 *     [PolicyState] snapshot PolicyValidator validated against is still current. This is
 *     also what makes START_TASK correct for a task that's since become PAUSED vs. still
 *     PENDING: PolicyValidator permits START_TASK for either, but only ActionExecutor,
 *     looking at the live task right before mutating, knows whether that means
 *     [TaskMutator.startTask] or [TaskMutator.resumeTask].
 *
 * Upgrade Blueprint Phase 2.4/2.5 (P0a): [PermittedAction.CreateTask] follows the same
 * defensive-guard shape as everything else — [applyCreateTask] re-reads [TaskMutator]
 * for the pre-generated [PermittedAction.CreateTask.taskId] right before creating, so a
 * duplicate transaction delivery (the exact scenario the class doc above is about) finds
 * the task already there and returns [ExecutionOutcome.NoOpAlreadyApplied] instead of a
 * second StudyTask.
 */
class ActionExecutor(
    private val transactionDao: InterventionTransactionDao,
    private val taskEscrow: TaskEscrow,
    private val taskMutator: TaskMutator
) {

    suspend fun execute(
        transactionId: String,
        action: PermittedAction,
        now: Long = System.currentTimeMillis()
    ): ExecutionOutcome {
        val transaction = transactionDao.getById(transactionId)
            ?: return ExecutionOutcome.TransactionNotFound

        if (transaction.currentState.isTerminal) {
            return ExecutionOutcome.TransactionAlreadyResolved
        }

        val outcome = applyAction(action, now)

        when (outcome) {
            is ExecutionOutcome.Applied,
            is ExecutionOutcome.NoOpAlreadyApplied,
            is ExecutionOutcome.NotApplicable ->
                taskEscrow.commit(transactionId, outcome = describe(outcome))

            is ExecutionOutcome.RequiresGuardianEscalation ->
                // Recognized outcome, but this step does not call GuardianNotifier —
                // that integration wasn't inspected/requested for this increment. The
                // transaction still resolves so it doesn't sit open indefinitely.
                taskEscrow.commit(transactionId, outcome = "Escalated to guardian (notifier not yet wired)")

            is ExecutionOutcome.Failed ->
                taskEscrow.resolveAs(transactionId, InterventionState.EXECUTION_FAILED, failureReason = outcome.reason)

            ExecutionOutcome.TransactionAlreadyResolved, ExecutionOutcome.TransactionNotFound ->
                Unit // unreachable here — both are returned above, before applyAction runs.
        }

        return outcome
    }

    private fun applyAction(action: PermittedAction, now: Long): ExecutionOutcome = when (action) {
        is PermittedAction.StartTask -> applyStartTask(action, now)
        is PermittedAction.ReduceDuration -> applyReduceDuration(action)
        is PermittedAction.RescheduleTask -> applyRescheduleTask(action)
        is PermittedAction.ShortBreak -> applyShortBreak(action, now)
        is PermittedAction.CreateTask -> applyCreateTask(action)
        PermittedAction.KeepPlan, PermittedAction.NoAction, PermittedAction.RequestClarification ->
            ExecutionOutcome.NotApplicable(action)
        PermittedAction.RequestGuardian -> ExecutionOutcome.RequiresGuardianEscalation
    }

    private fun applyStartTask(action: PermittedAction.StartTask, now: Long): ExecutionOutcome {
        val task = taskMutator.findTask(action.taskId)
            ?: return ExecutionOutcome.Failed(action, "task ${action.taskId} no longer exists")
        return when (task.state) {
            TaskState.ACTIVE -> ExecutionOutcome.NoOpAlreadyApplied(action)
            TaskState.PAUSED -> {
                taskMutator.resumeTask(action.taskId, now)
                ExecutionOutcome.Applied(action)
            }
            TaskState.PENDING -> {
                taskMutator.startTask(action.taskId)
                ExecutionOutcome.Applied(action)
            }
            TaskState.DONE, TaskState.SKIPPED ->
                ExecutionOutcome.Failed(action, "task ${action.taskId} is ${task.state}, state changed since validation")
        }
    }

    private fun applyReduceDuration(action: PermittedAction.ReduceDuration): ExecutionOutcome {
        val task = taskMutator.findTask(action.taskId)
            ?: return ExecutionOutcome.Failed(action, "task ${action.taskId} no longer exists")
        if (task.state == TaskState.DONE || task.state == TaskState.SKIPPED) {
            return ExecutionOutcome.Failed(action, "task ${action.taskId} is ${task.state}, state changed since validation")
        }
        if (task.durationMinutes == action.newDurationMinutes) {
            return ExecutionOutcome.NoOpAlreadyApplied(action)
        }
        taskMutator.reduceDuration(action.taskId, action.newDurationMinutes)
        return ExecutionOutcome.Applied(action)
    }

    private fun applyRescheduleTask(action: PermittedAction.RescheduleTask): ExecutionOutcome {
        val task = taskMutator.findTask(action.taskId)
            ?: return ExecutionOutcome.Failed(action, "task ${action.taskId} no longer exists")
        if (task.state == TaskState.DONE || task.state == TaskState.SKIPPED) {
            return ExecutionOutcome.Failed(action, "task ${action.taskId} is ${task.state}, state changed since validation")
        }
        if (task.scheduledStartTime == action.newScheduledStartTime) {
            return ExecutionOutcome.NoOpAlreadyApplied(action)
        }
        taskMutator.rescheduleTask(action.taskId, action.newScheduledStartTime)
        return ExecutionOutcome.Applied(action)
    }

    private fun applyShortBreak(action: PermittedAction.ShortBreak, now: Long): ExecutionOutcome {
        val task = taskMutator.findTask(action.taskId)
            ?: return ExecutionOutcome.Failed(action, "task ${action.taskId} no longer exists")
        if (task.state == TaskState.DONE || task.state == TaskState.SKIPPED) {
            return ExecutionOutcome.Failed(action, "task ${action.taskId} is ${task.state}, state changed since validation")
        }
        if (task.state == TaskState.PAUSED) {
            return ExecutionOutcome.NoOpAlreadyApplied(action)
        }
        // Note: [action.minutes] is not persisted anywhere — PlanStore.pauseTask only
        // records pausedAt/pauseCount, not an intended break length. Actually waking the
        // student back up after `minutes` (an alarm, a notification) is scheduling
        // behavior that belongs to the Trigger Engine (a later step), not the Executor.
        taskMutator.pauseTask(action.taskId, now)
        return ExecutionOutcome.Applied(action)
    }

    /**
     * Upgrade Blueprint Phase 2.4/2.5 (P0a). Unlike every other applyX above, there is no
     * pre-existing task to re-validate state on — [PermittedAction.CreateTask.taskId] is a
     * caller-generated id that only becomes a real StudyTask once this succeeds. The live
     * re-check here is purely the idempotency guard described in the class doc: if a task
     * with this id already exists (a duplicate transaction delivery replaying the same
     * CreateTask action), nothing is created a second time.
     */
    private fun applyCreateTask(action: PermittedAction.CreateTask): ExecutionOutcome {
        val existing = taskMutator.findTask(action.taskId)
        if (existing != null) {
            return ExecutionOutcome.NoOpAlreadyApplied(action)
        }
        taskMutator.createTask(action.taskId, action.request)
        return ExecutionOutcome.Applied(action)
    }

    private fun describe(outcome: ExecutionOutcome): String = when (outcome) {
        is ExecutionOutcome.Applied -> "Applied ${outcome.action::class.simpleName}"
        is ExecutionOutcome.NoOpAlreadyApplied -> "No-op — ${outcome.action::class.simpleName} already in effect"
        is ExecutionOutcome.NotApplicable -> "${outcome.action::class.simpleName} — no task mutation required"
        else -> outcome.toString()
    }
}

sealed class ExecutionOutcome {
    data class Applied(val action: PermittedAction) : ExecutionOutcome()
    data class NoOpAlreadyApplied(val action: PermittedAction) : ExecutionOutcome()
    /** KeepPlan / NoAction / RequestClarification — by design, nothing to mutate. */
    data class NotApplicable(val action: PermittedAction) : ExecutionOutcome()
    /** RequestGuardian — recognized, but not wired to GuardianNotifier in this step. */
    object RequiresGuardianEscalation : ExecutionOutcome()
    /** The live task no longer supports this action — state changed (or the task was
     *  deleted) between PolicyValidator's snapshot and execution. Resolves the
     *  transaction as EXECUTION_FAILED, not USER_ABORTED. */
    data class Failed(val action: PermittedAction, val reason: String) : ExecutionOutcome()
    /** Duplicate delivery of a transaction that already resolved — see class doc. */
    object TransactionAlreadyResolved : ExecutionOutcome()
    object TransactionNotFound : ExecutionOutcome()
}
