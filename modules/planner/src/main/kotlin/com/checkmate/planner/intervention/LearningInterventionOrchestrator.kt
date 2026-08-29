package com.checkmate.planner.intervention

import android.content.Context
import com.checkmate.learning.engine.LearningDecisionEngine
import com.checkmate.planner.PlanStore
import com.checkmate.planner.model.TaskState
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
 * Daily-cadence wiring ([com.checkmate.service.GapTaskManager] calls [executeTopCandidate]
 * once a day, not just once at import time): without something remembering what was
 * already served, re-running the same-shaped [LearningDecisionEngine.decideFromReport]
 * output tomorrow would just re-pick today's exact candidate forever, since mastery hasn't
 * moved without the P0b evidence loop. [GapTaskLedger] is that memory — a candidate whose
 * concept is already [GapTaskLedger.isCovered] is skipped here (see [RejectionSource.AlreadyCovered])
 * before it's even mapped, and a successful [OrchestrationOutcome.Created] records itself
 * via [GapTaskLedger.recordServed] so the NEXT run's escalation depth and "which concept is
 * this" both stay accurate. A concept only ever becomes covered when its task reaches
 * `TaskState.DONE` (see [GapTaskLedger.markCovered]'s own doc) — never merely because a
 * task once existed for it — so an ignored gap-task keeps being re-served, not abandoned.
 *
 * BUGFIX (duplicate same-round task): "re-served" above does NOT mean "re-created."
 * [GapTaskLedger.isCovered] is only true once a task reaches DONE *and* mastery clears the
 * bar — it says nothing about a concept whose task is still PENDING/ACTIVE/PAUSED/SKIPPED
 * from an earlier call. Before this fix, [executeTopCandidate] had no check for that case at
 * all: every call minted a fresh [idGenerator] taskId and created a brand-new [StudyTask]
 * for the top candidate, full stop. Confirmed live: [com.checkmate.service.GapTaskManager]
 * ran twice ~5 minutes apart for the same still-unresolved concept (round 2's retest task,
 * never touched by the student) and got a SECOND new task on the second call, silently
 * orphaning the first — [GapTaskLedger]'s active-task pointer moved to the new one, leaving
 * the old one sitting in [PlanStore] as a duplicate that nothing will ever resolve. See
 * [RejectionSource.AlreadyActive]: when the candidate's concept is already
 * [GapTaskLedger.activeConceptId] AND that pointer's task still exists and hasn't reached
 * DONE, the walk stops here instead of minting another one — no change to the DONE case
 * ([GapTaskManager.resolveDoneConcept]/[GapTaskLedger.resetForNextRound] still leave the old
 * DONE task's id in place and a *new* task for the new round is exactly what should happen
 * there, and does: this guard only fires when the existing task is NOT DONE).
 *
 * Trigger type: no existing [InterventionTriggerType] names "the learning engine decided
 * this task should exist" — [LearningInterventionCreateTaskIntegrationTest] flagged the
 * same gap and picked [InterventionTriggerType.BACKLOG_RISK] as the closest existing fit
 * rather than widening that enum for this pass; this class keeps that same choice (see
 * [LEARNING_ENGINE_TRIGGER]) so both the test-only pipeline and this production one agree
 * on what gets persisted.
 *
 * This class does not itself decide *when* to run — it consumes an already-built
 * [LearningDecisionEngine.DecisionReport]. [com.checkmate.ui.testresults.TestResultsViewModel]
 * calls it once per fresh test import; [com.checkmate.service.GapTaskManager] calls it once
 * per day thereafter — see that class's own doc for the daily trigger.
 */
class LearningInterventionOrchestrator(
    private val taskEscrow: TaskEscrow,
    private val actionExecutor: ActionExecutor,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val dayKeyProvider: () -> String = { GapTaskLedger.todayKey() }
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
        /** [GapTaskLedger.isCovered] already true for this candidate's concept — its
         *  gap-task previously reached DONE, so the daily walk moves on to the next
         *  ranked candidate instead of re-serving a concept the student already finished. */
        object AlreadyCovered : RejectionSource()
        /** BUGFIX (duplicate same-round task): this candidate's concept is already
         *  [GapTaskLedger.activeConceptId], and that pointer's task ([GapTaskLedger.activeTaskId]
         *  in [GapTaskLedger.activeTaskDayKey]'s plan) still exists and hasn't reached DONE —
         *  the concept is mid-round, not finished and not abandoned, so no new task is
         *  created and the walk stops rather than falling through to a lower-ranked
         *  candidate (that would silently switch which concept is being served). */
        object AlreadyActive : RejectionSource()
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
     * ranked order and executes the first one that clears the covered-concept check,
     * the already-active check, mapping, policy validation, and execution — see class doc
     * for why this stops at one instead of creating a task per candidate, and for
     * [GapTaskLedger]'s role in the walk.
     */
    suspend fun executeTopCandidate(
        report: LearningDecisionEngine.DecisionReport,
        now: Long = System.currentTimeMillis()
    ): OrchestrationResult {
        val rejections = mutableListOf<CandidateRejection>()

        report.candidates.forEachIndexed { index, candidate ->
            val rank = index + 1

            val conceptId = candidate.conceptId
            if (conceptId != null && GapTaskLedger.isCovered(conceptId)) {
                rejections += CandidateRejection(
                    candidate = candidate,
                    rank = rank,
                    source = RejectionSource.AlreadyCovered,
                    detail = "concept $conceptId already reached DONE on a previous gap-task run",
                    timestamp = now
                )
                return@forEachIndexed
            }

            // BUGFIX (duplicate same-round task): concept is already the active one and its
            // task hasn't reached DONE yet — nothing to do. Stop the whole walk here (not
            // return@forEachIndexed) so an unresolved active concept never gets silently
            // swapped for a lower-ranked candidate just because this call happened to run
            // again before the student touched the existing task.
            if (conceptId != null && conceptId == GapTaskLedger.activeConceptId()) {
                val activeTaskId = GapTaskLedger.activeTaskId()
                val activeDayKey = GapTaskLedger.activeTaskDayKey()
                val existingTask = activeTaskId?.let { id ->
                    PlanStore.todayTasks.value.find { it.id == id }
                        ?: activeDayKey?.let { key -> PlanStore.loadDay(key).find { it.id == id } }
                }
                if (existingTask != null && existingTask.state != TaskState.DONE) {
                    return OrchestrationResult(
                        outcome = OrchestrationOutcome.NoExecutableCandidate,
                        rejections = rejections + CandidateRejection(
                            candidate = candidate,
                            rank = rank,
                            source = RejectionSource.AlreadyActive,
                            detail = "concept $conceptId already has an unresolved task " +
                                "(${existingTask.id}, state=${existingTask.state}) — not creating a duplicate",
                            timestamp = now
                        )
                    )
                }
            }

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
                            GapTaskLedger.recordServed(candidate, taskId, dayKeyProvider())
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
