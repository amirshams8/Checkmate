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
 * Originally scoped to the three intents fully specified end-to-end for the first P0a
 * pass — REPAIR_CONCEPT, START_DIAGNOSTIC, ASSIGN_TARGETED_SET. The P0a continuation adds
 * two more: SCHEDULE_RETENTION_TEST and START_MOCK. Both are still genuine study SESSIONS
 * with a positive [LearningDecisionEngine.CandidateIntervention.durationMinutes] — the
 * same CreateTaskRequest shape as the original three, just a different
 * [com.checkmate.planner.model.TaskType] (see [taskTypeFor]). REPLAN_DAY,
 * REDUCE_DIFFICULTY, and INCREASE_DIFFICULTY are deliberately NOT added here — none of the
 * three is a study session a StudyTask can represent (REPLAN_DAY regenerates the whole
 * day's plan; a difficulty adjustment is a standing preference, not something the student
 * "does" for N minutes) — [toCreateTaskRequest] still returns null for those, and
 * [LearningInterventionOrchestrator] routes them through [PolicyValidator.validateReplanDay]/
 * [PolicyValidator.validateAdjustDifficulty] instead, entirely bypassing this mapper.
 *
 * [toCreateTaskRequest] returning null — deliberately, not silently coerced into one of
 * the supported task shapes — lets a caller iterating a DecisionReport's candidates filter
 * out-of-scope ones explicitly (e.g. surface them elsewhere, log them, or just skip)
 * instead of this mapper inventing a plausible-looking CreateTaskRequest for an intent
 * nobody has scoped a StudyTask shape for.
 *
 * This mapper does not itself talk to TaskEscrow or PolicyValidator — same separation of
 * concerns as everything else in this package (PolicyValidator doesn't call ActionExecutor,
 * ActionExecutor doesn't call PolicyValidator). The caller orchestrating this flow is
 * [LearningInterventionOrchestrator] — see its own doc for the full production wiring.
 */
object LearningInterventionMapper {

    private val SUPPORTED_INTENTS = setOf(
        LearningDecisionEngine.LearningInterventionIntent.REPAIR_CONCEPT,
        LearningDecisionEngine.LearningInterventionIntent.START_DIAGNOSTIC,
        LearningDecisionEngine.LearningInterventionIntent.ASSIGN_TARGETED_SET,
        LearningDecisionEngine.LearningInterventionIntent.SCHEDULE_RETENTION_TEST,
        LearningDecisionEngine.LearningInterventionIntent.START_MOCK
    )

    /**
     * P0a continuation: [LearningDecisionEngine.macroCandidates] always builds
     * START_MOCK candidates with `subject = null`, `chapter = null`, `topic = null` — a
     * mock covers the whole syllabus, not one subject (see that function's own doc). A
     * StudyTask still needs a non-blank subject/topic to exist at all
     * ([PolicyValidator.validateCreateTask]'s blank check), so these two constants stand in
     * for "the whole syllabus" as a label — they are NOT a guess at real syllabus content,
     * just the display text for a whole-student session.
     */
    private const val MOCK_SUBJECT = "Full Syllabus"
    private const val MOCK_TOPIC = "Full-Length Mock Test"

    /**
     * Returns null for any candidate outside [SUPPORTED_INTENTS], or one whose subject is
     * unknown (both [LearningDecisionEngine.CandidateIntervention.subject] and
     * `.chapter`/`.topic`/`.conceptId` are nullable — see that class's doc — and a task
     * with a blank subject/topic would just fail [PolicyValidator.validateCreateTask]
     * anyway, so declining to guess here is equivalent but skips a doomed round trip).
     * START_MOCK is the one exception to the subject-required rule — see [MOCK_SUBJECT]'s
     * own doc for why it's handled as its own branch before the general subject lookup.
     */
    fun toCreateTaskRequest(candidate: LearningDecisionEngine.CandidateIntervention): CreateTaskRequest? {
        val intent = candidate.intent
        if (intent !in SUPPORTED_INTENTS) return null

        if (intent == LearningDecisionEngine.LearningInterventionIntent.START_MOCK) {
            return CreateTaskRequest(
                subject = MOCK_SUBJECT,
                topic = MOCK_TOPIC,
                durationMinutes = candidate.durationMinutes,
                taskType = taskTypeFor(intent),
                rationale = candidate.rationale,
                learningIntent = intent.name,
                conceptId = null,
                targetedConceptIds = emptyList()
            )
        }

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
            // P0a continuation: a retention check is a short recall probe on already-taught
            // material, not new teaching and not open-ended practice — REVISION is the
            // closest existing fit (see classifyIntent's own doc: "it doesn't need
            // re-teaching, it needs a recall check").
            LearningDecisionEngine.LearningInterventionIntent.SCHEDULE_RETENTION_TEST -> TaskType.REVISION
            // A full-length mock is the student answering questions under exam conditions —
            // PRACTICE fits, same reasoning as START_DIAGNOSTIC/ASSIGN_TARGETED_SET above.
            // There is no dedicated MOCK TaskType — adding one would widen an enum other
            // code may match on elsewhere, not something to do incidentally in this pass.
            LearningDecisionEngine.LearningInterventionIntent.START_MOCK -> TaskType.PRACTICE
            else -> TaskType.OTHER // unreachable for every intent in SUPPORTED_INTENTS
        }
}
