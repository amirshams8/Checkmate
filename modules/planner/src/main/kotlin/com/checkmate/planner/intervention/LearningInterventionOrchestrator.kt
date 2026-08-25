package com.checkmate.planner.intervention

import android.content.Context
import com.checkmate.learning.engine.LearningDecisionEngine
import java.util.UUID

/**
 * Upgrade Blueprint Phase 2.4/2.5 (P0a) — the single production call site that was still
 * missing: [LearningInterventionMapper]'s own doc names this exact orchestration as "not
 * yet existing as a single call site," and [LearningInterventionCreateTaskIntegrationTest]
 * only exercises the chain a test-local `runPipeline` helper wires by hand. This is that
 * helper, promoted to a real production class:
 * ```
 * LearningDecisionEngine.DecisionReport -> LearningInterventionOrchestrator
 *     -> (per candidate, highest priorityScore first) LearningInterventionMapper
 *     -> CreateTaskRequest -> PolicyValidator -> TaskEscrow -> ActionExecutor
 *     -> TaskMutator.createTask() -> PlanStore -> StudyTask
 * ```
 *
 * Deliberately does NOT create one task per candidate. [DecisionReport.candidates] is
 * already ranked by [LearningDecisionEngine.CandidateIntervention.priorityScore] — turning
 * every candidate into a task would just as capacity-blind as never having ranked them at
 * all, defeating the entire point of [com.checkmate.learning.analytics.ScoreGainEstimator]
 * and today's finite study time. [executeTopCandidate] instead walks the ranking in order
 * and stops at the first candidate that clears every stage — mapping, policy, and
 * execution. An earlier, higher-ranked candidate that fails any stage is recorded (never
 * silently dropped, see [CandidateRejection]) and the walk falls through to the next one,
 * exactly the "try candidate #2 if policy allows" behavior a ranked list implies.
 *
 * Trigger type: no existing [InterventionTriggerType] names "the learning engine decided
 * this task should exist" — [LearningInterventionCreateTaskIntegrationTest] flagged the
 * same gap and picked [InterventionTriggerType.BACKLOG_RISK] as the closest existing fit
 * rather than widening that enum for this pass; this class keeps that same choice (see
 * [LEARNING_ENGINE_TRIGGER]) so both the test-only pipeline and this production one agree
 * on what gets persisted.
 *
 * This class does not itself decide *when* to run — it consumes an already-built
 * [LearningDecisionEngine.DecisionReport]. Wiring a real trigger (a schedule, a button, a
 * WorkManager job) to call [executeTopCandidate] is a separate, later step, same as every
 * other "plumbing exists, nothing calls it on a schedule yet" seam in this package.
 */
class LearningInterventionOrchestrator(
    private val taskEscrow: TaskEscrow,
    private val actionExecutor: ActionExecutor,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {

    /** Why a candidate never became a task — kept as a distinct type from
     *  [RejectionReason] because a candidate can fail before policy is ever consulted
     *  (see [NotMappable]), and collapsing that into a fake [RejectionReason] would
     *  misattribute a mapper decision to PolicyValidator. */
    sealed class RejectionSource {
        /** [LearningInterventionMapper.toCreateTaskRequest] returned null — the intent is
         *  outside P0a's mapped scope, or the candidate is missing subject/topic. */
        object NotMappable : RejectionSource()
        /** [PolicyValidator.validateCreateTask] rejected the mapped request. */
        data class PolicyRejected(val reason: RejectionReason) : RejectionSource()
        /** The freshly generated [taskId][UUID] collided with a task already under
         *  escrow — expected to be effectively unreachable given UUID generation, kept
         *  as a named case rather than a thrown exception so a candidate this happens to
         *  is recorded like any other rejection instead of aborting the whole walk. */
        object TaskIdCollision : RejectionSource()
    }

    /** One candidate that was ranked but did not end up as a task — see class doc's
     *  "failures are first-class" requirement. `rank` is 1-based position in
     *  [LearningDecisionEngine.DecisionReport.candidates], preserved here because the
     *  candidate itself carries no id of its own. */
    data class CandidateRejection(
        val candidate: LearningDecisionEngine.CandidateIntervention,
        val rank: Int,
        val source: RejectionSource,
        val detail: String,
        val timestamp: Long
    )

    sealed class OrchestrationOutcome {
        /** The candidate that made it all the way through, plus what
         *  [ActionExecutor.execute] returned for it — callers that care whether the
         *  StudyTask really landed (vs. e.g. [ExecutionOutcome.NoOpAlreadyApplied]) read
         *  that from here rather than assuming success. */
        data class Created(
            val candidate: LearningDecisionEngine.CandidateIntervention,
            val rank: Int,
            val taskId: String,
            val executionOutcome: ExecutionOutcome
        ) : OrchestrationOutcome()
        /** Every candidate in the report was rejected (or the report had none to begin
         *  with) — [OrchestrationResult.rejections] carries the full detail. */
        object NoExecutableCandidate : OrchestrationOutcome()
    }

    data class OrchestrationResult(
        val outcome: OrchestrationOutcome,
        val rejections: List<CandidateRejection>
    )

    /**
     * Walks [report.candidates][LearningDecisionEngine.DecisionReport.candidates] in
     * ranked order and executes the first one that clears mapping, policy validation, and
     * execution — see class doc for why this stops at one instead of creating a task per
     * candidate.
     */
    suspend fun executeTopCandidate(
        report: LearningDecisionEngine.DecisionReport,
        now: Long = System.currentTimeMillis()
    ): OrchestrationResult {
        val rejections = mutableListOf<CandidateRejection>()

        report.candidates.forEachIndexed { index, candidate ->
            val rank = index + 1
            val request = LearningInterventionMapper.toCreateTaskRequest(candidate)
            if (request == null) {
                rejections += CandidateRejection(
                    candidate = candidate,
                    rank = rank,
                    source = RejectionSource.NotMappable,
                    detail = "intent ${candidate.intent} is not mappable to a CreateTaskRequest " +
                        "(outside P0a scope, or missing subject/topic)",
                    timestamp = now
                )
                return@forEachIndexed
            }

            val taskId = idGenerator()

            when (val policyResult = PolicyValidator.validateCreateTask(taskId, request)) {
                is PolicyResult.Rejected -> {
                    rejections += CandidateRejection(
                        candidate = candidate,
                        rank = rank,
                        source = RejectionSource.PolicyRejected(policyResult.reason),
                        detail = policyResult.detail,
                        timestamp = now
                    )
                }

                is PolicyResult.Permitted -> {
                    when (val acquireResult = taskEscrow.acquire(taskId, LEARNING_ENGINE_TRIGGER, now)) {
                        is EscrowAcquireResult.AlreadyHeld -> {
                            rejections += CandidateRejection(
                                candidate = candidate,
                                rank = rank,
                                source = RejectionSource.TaskIdCollision,
                                detail = "generated taskId $taskId already under escrow",
                                timestamp = now
                            )
                        }

                        is EscrowAcquireResult.Acquired -> {
                            val action = policyResult.action as PermittedAction.CreateTask
                            val executionOutcome = actionExecutor.execute(
                                acquireResult.transaction.transactionId,
                                action,
                                now
                            )
                            return OrchestrationResult(
                                outcome = OrchestrationOutcome.Created(candidate, rank, taskId, executionOutcome),
                                rejections = rejections
                            )
                        }
                    }
                }
            }
        }

        return OrchestrationResult(OrchestrationOutcome.NoExecutableCandidate, rejections)
    }

    companion object {
        /** See class doc's "Trigger type" note. */
        val LEARNING_ENGINE_TRIGGER = InterventionTriggerType.BACKLOG_RISK

        /**
         * Production wiring — same [InterventionDatabase]/[OutcomeLedgerWriter]/
         * [PlanStoreTaskMutator] construction [InterventionReconciliation.runAtStartup]
         * already uses, so this and the reconciliation sweep never disagree about which
         * database or ledger a transaction was written through.
         */
        fun from(context: Context): LearningInterventionOrchestrator {
            val db = InterventionDatabase.getInstance(context)
            val dao = db.interventionTransactionDao()
            val ledgerWriter = OutcomeLedgerWriter(db.outcomeLedgerDao())
            val escrow = TaskEscrow(dao, ledgerWriter)
            val executor = ActionExecutor(dao, escrow, PlanStoreTaskMutator())
            return LearningInterventionOrchestrator(escrow, executor)
        }
    }
}
