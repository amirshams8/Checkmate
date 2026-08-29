package com.checkmate.planner.intervention

import android.content.Context
import com.checkmate.learning.engine.LearningDecisionEngine
import com.checkmate.planner.PlanStore
import com.checkmate.planner.model.StudyTask
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
 * from an earlier call. The original version of this fix had no check for that case at
 * all: every call minted a fresh [idGenerator] taskId and created a brand-new [StudyTask]
 * for the top candidate, full stop. Confirmed live: [com.checkmate.service.GapTaskManager]
 * ran twice ~5 minutes apart for the same still-unresolved concept (round 2's retest task,
 * never touched by the student) and got a SECOND new task on the second call, silently
 * orphaning the first — [GapTaskLedger]'s active-task pointer moved to the new one, leaving
 * the old one sitting in [PlanStore] as a duplicate that nothing will ever resolve.
 *
 * BUGFIX (duplicate task via re-ranking, part 2): the first fix above checked the active
 * concept ONLY while iterating the candidate whose own `conceptId` happened to equal
 * [GapTaskLedger.activeConceptId] — which only blocks anything if the active concept is
 * still the one this run's fresh re-rank puts at (or ahead of) the point the walk reaches
 * it. [LearningDecisionEngine.decideFromReport] re-ranks from scratch on every call — a
 * shift in gain estimates (e.g. from P0b evidence just imported for some OTHER concept, or
 * simply floating-point noise in [com.checkmate.learning.analytics.ScoreGainEstimator])
 * can promote a different concept to rank 1 between two calls even though the active
 * concept's own mastery hasn't moved at all. When that happens, the walk reaches the NEW
 * top candidate first, finds nothing wrong with IT specifically (its own conceptId isn't
 * covered, and it isn't the active one, so the old per-candidate check never fired for it),
 * creates a task, and returns — the still-unresolved active concept's task never gets
 * looked at, and its still-PENDING task is silently orphaned exactly like the first bug,
 * just reached through a different path. Confirmed live: with concept 56718eb2 active and
 * its round-2 task still PENDING, a fresh re-rank promoted concept 293376bd to rank 1;
 * [GapTaskLedger.recordServed] logged `isNewConcept=true` for 293376bd, meaning the old
 * per-candidate AlreadyActive check never triggered — it was still there, just never
 * reached, because the walk had already created and returned a task for 293376bd before
 * getting anywhere near 56718eb2's position in the list.
 *
 * Fix: the active-concept check now runs ONCE, up front, independent of ranking — see
 * [RejectionSource.AlreadyActive]. If [GapTaskLedger.activeConceptId] has a task that
 * exists and hasn't reached DONE, the ENTIRE walk is blocked before any candidate is even
 * considered for creation, regardless of whether that concept still appears at rank 1, at
 * some lower rank, or has dropped out of this run's candidate list altogether (a candidate
 * can vanish from the ranked list for reasons unrelated to being finished — e.g. it no
 * longer clears whatever inclusion threshold [LearningDecisionEngine.decideFromReport]
 * applies — and a missing candidate is not evidence the concept is done; only
 * [GapTaskManager.resolveDoneConcept] gets to decide that, via real mastery evidence). This
 * still does NOT change the DONE case: once the active concept's task reaches DONE,
 * [GapTaskManager.resolveDoneConcept] either covers it ([GapTaskLedger.markCovered]) or
 * clears just its P0b session fields for a new round ([GapTaskLedger.resetForNextRound]) —
 * either way the active-task lookup below finds no non-DONE task, the block does not apply,
 * and the walk proceeds normally (a fresh task for a new round, or a fresh task for a newly
 * promoted concept once the old one is genuinely covered).
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
        /** BUGFIX (duplicate task via re-ranking, part 2): [GapTaskLedger.activeConceptId]
         *  has a task ([GapTaskLedger.activeTaskId] in [GapTaskLedger.activeTaskDayKey]'s
         *  plan) that still exists and hasn't reached DONE — the concept is mid-round, not
         *  finished and not abandoned. Checked ONCE up front against the whole walk, not
         *  per iterated candidate (see class doc's "part 2" note on why the original
         *  per-candidate version of this check could be skipped entirely by a re-rank),
         *  so this blocks the walk even when the active concept isn't the candidate this
         *  run's ranking currently prefers — no task is created for anything else, and no
         *  new task is created for the active concept either, until it's genuinely resolved. */
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

    /** Looks up the active concept's task, the same "today's live list first, fall back to
     *  loadDay" pattern [com.checkmate.service.GapTaskManager.findTask] already uses — kept
     *  as a private duplicate rather than a shared helper since planner has no visibility
     *  into `app`'s GapTaskManager (same direction every other cross-module boundary in
     *  this package already respects). Returns null if there is no active concept, no
     *  active task recorded for it, or the task can't be found in either place. */
    private fun activeUnresolvedTask(): StudyTask? {
        val taskId = GapTaskLedger.activeTaskId() ?: return null
        val dayKey = GapTaskLedger.activeTaskDayKey()
        val task = PlanStore.todayTasks.value.find { it.id == taskId }
            ?: dayKey?.let { key -> PlanStore.loadDay(key).find { it.id == taskId } }
        return task?.takeIf { it.state != TaskState.DONE }
    }

    /**
     * Walks [report.candidates][LearningDecisionEngine.DecisionReport.candidates] in
     * ranked order and executes the first one that clears the covered-concept check,
     * mapping, policy validation, and execution — see class doc for why this stops at one
     * instead of creating a task per candidate, and for [GapTaskLedger]'s role in the walk.
     * Before any of that, checks once whether [GapTaskLedger.activeConceptId] still has an
     * unresolved task — see class doc's "part 2" BUGFIX and [RejectionSource.AlreadyActive]
     * for why this has to run independent of ranking order rather than folded into the
     * per-candidate loop below.
     */
    suspend fun executeTopCandidate(
        report: LearningDecisionEngine.DecisionReport,
        now: Long = System.currentTimeMillis()
    ): OrchestrationResult {
        val rejections = mutableListOf<CandidateRejection>()

        // BUGFIX (duplicate task via re-ranking, part 2): resolved ONCE, before any
        // candidate is walked, so a re-rank that promotes a different concept to rank 1
        // can never slip a new task past a still-unresolved active one — see class doc.
        val activeConceptId = GapTaskLedger.activeConceptId()
        if (activeConceptId != null) {
            val blockingTask = activeUnresolvedTask()
            if (blockingTask != null) {
                // Attribute the rejection to whichever candidate this run's ranking would
                // otherwise have picked, so the log still shows what got preempted — even
                // when that candidate is a completely different concept than the one
                // actually blocking. Falls back to the active concept's own candidate (if
                // this run's report still contains it) and, failing that, to the report's
                // own top entry, purely so the CandidateRejection record has something real
                // to point at; none of these branches change WHETHER the walk is blocked.
                val attributed = report.candidates.firstOrNull { it.conceptId == activeConceptId }
                    ?: report.candidates.firstOrNull()
                if (attributed != null) {
                    val attributedRank = report.candidates.indexOf(attributed) + 1
                    val switchNote = if (attributed.conceptId != activeConceptId) {
                        " (this run's re-rank currently prefers concept ${attributed.conceptId} " +
                            "instead — the active concept still wins until it's resolved)"
                    } else ""
                    rejections += CandidateRejection(
                        candidate = attributed,
                        rank = attributedRank,
                        source = RejectionSource.AlreadyActive,
                        detail = "concept $activeConceptId already has an unresolved task " +
                            "(${blockingTask.id}, state=${blockingTask.state}) — not creating " +
                            "a new task while it's pending$switchNote",
                        timestamp = now
                    )
                }
                return OrchestrationResult(OrchestrationOutcome.NoExecutableCandidate, rejections)
            }
        }

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
