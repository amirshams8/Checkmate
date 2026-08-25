package com.checkmate.planner.intervention

import com.checkmate.learning.engine.LearningDecisionEngine
import com.checkmate.planner.model.TaskType

/**
 * Upgrade Blueprint Phase 2.4/2.5 (P0a) — implements the previously-missing arrow in the
 * pipeline diagram:
 * ```
 * LearningDecisionEngine -> CandidateIntervention -> LearningInterventionMapper
 *     -> CreateTaskRequest -> PolicyValidator -> TaskEscrow/transaction
 *     -> TaskMutator.createTask() -> PlanStore -> StudyTask
 * ```
 *
 * Deliberately scoped to the three intents already fully specified end-to-end for this
 * pass — REPAIR_CONCEPT, START_DIAGNOSTIC, ASSIGN_TARGETED_SET (matching
 * [PolicyValidator.SUPPORTED_LEARNING_INTENTS]). The other five
 * (SCHEDULE_RETENTION_TEST, START_MOCK, REPLAN_DAY, REDUCE_DIFFICULTY,
 * INCREASE_DIFFICULTY) make [toCreateTaskRequest] return null — deliberately, not
 * silently coerced into one of the three supported task shapes — so a caller iterating a
 * DecisionReport's candidates can filter them out explicitly (e.g. surface them
 * elsewhere, log them, or just skip) instead of this mapper inventing a plausible-looking
 * CreateTaskRequest for an intent nobody has scoped a StudyTask shape for yet.
 *
 * This mapper does not itself talk to TaskEscrow or PolicyValidator — same separation of
 * concerns as everything else in this package (PolicyValidator doesn't call ActionExecutor,
 * ActionExecutor doesn't call PolicyValidator). The caller orchestrating P0a's full flow is
 * expected to: generate a taskId, call [TaskEscrow.acquire] with it, call
 * [PolicyValidator.validateCreateTask] with the request this mapper produced, then
 * [ActionExecutor.execute] the resulting [PermittedAction.CreateTask] — none of that
 * orchestration exists yet as a single call site; wiring it is a later step (see the
 * blueprint's own note that a real end-to-end run through actual FT/test evidence comes
 * after P0a's plumbing, not as part of it).
 */
object LearningInterventionMapper {

    private val SUPPORTED_INTENTS = setOf(
        LearningDecisionEngine.LearningInterventionIntent.REPAIR_CONCEPT,
        LearningDecisionEngine.LearningInterventionIntent.START_DIAGNOSTIC,
        LearningDecisionEngine.LearningInterventionIntent.ASSIGN_TARGETED_SET
    )

    /**
     * Returns null for any candidate outside [SUPPORTED_INTENTS], or one whose subject is
     * unknown (both [LearningDecisionEngine.CandidateIntervention.subject] and
     * `.chapter`/`.topic`/`.conceptId` are nullable — see that class's doc — and a task
     * with a blank subject/topic would just fail [PolicyValidator.validateCreateTask]
     * anyway, so declining to guess here is equivalent but skips a doomed round trip).
     */
    fun toCreateTaskRequest(candidate: LearningDecisionEngine.CandidateIntervention): CreateTaskRequest? {
        val intent = candidate.intent
        if (intent !in SUPPORTED_INTENTS) return null

        val subject = candidate.subject ?: return null
        val topic = candidate.chapter ?: candidate.topic ?: candidate.conceptId ?: return null

        val targetedConceptIds = when (intent) {
            LearningDecisionEngine.LearningInterventionIntent.ASSIGN_TARGETED_SET ->
                // ASSIGN_TARGETED_SET candidates are collapsed chapter-level groups (see
                // LearningDecisionEngine.collapseIntoTargetedSets) — conceptId is null for
                // these by construction, so there is no per-concept id list to carry yet.
                // Left empty rather than guessed; wiring the underlying concept ids through
                // the collapse step is a separate change, not something this mapper should
                // invent from a single collapsed candidate.
                emptyList()
            else -> candidate.conceptId?.let { listOf(it) } ?: emptyList()
        }

        return CreateTaskRequest(
            subject = subject,
            topic = topic,
            durationMinutes = candidate.durationMinutes,
            taskType = taskTypeFor(intent),
            rationale = candidate.rationale,
            learningIntent = intent.name,
            conceptId = candidate.conceptId,
            targetedConceptIds = targetedConceptIds
        )
    }

    private fun taskTypeFor(intent: LearningDecisionEngine.LearningInterventionIntent): TaskType =
        when (intent) {
            // A repair session teaches/re-teaches the weak prerequisite — closest existing
            // TaskType is LECTURE (see StudyTask.taskType's doc: AdaptivePlanner maps its
            // own LEARN sessionType the same way).
            LearningDecisionEngine.LearningInterventionIntent.REPAIR_CONCEPT -> TaskType.LECTURE
            // Both diagnostic and targeted-set sessions are the student answering
            // questions, not being taught new material — PRACTICE fits both.
            LearningDecisionEngine.LearningInterventionIntent.START_DIAGNOSTIC -> TaskType.PRACTICE
            LearningDecisionEngine.LearningInterventionIntent.ASSIGN_TARGETED_SET -> TaskType.PRACTICE
            else -> TaskType.OTHER // unreachable for the three SUPPORTED_INTENTS this is ever called with
        }
}
