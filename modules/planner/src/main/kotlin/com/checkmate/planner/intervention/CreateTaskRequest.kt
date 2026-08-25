package com.checkmate.planner.intervention

import com.checkmate.planner.model.TaskType

/**
 * Upgrade Blueprint Phase 2.4/2.5 (P0a) — the missing request shape in:
 * ```
 * LearningDecisionEngine -> CandidateIntervention -> LearningInterventionMapper
 *     -> CreateTaskRequest -> PolicyValidator -> TaskEscrow/transaction
 *     -> TaskMutator.createTask() -> PlanStore -> StudyTask
 * ```
 *
 * Untrusted the same way [LlmIntent] is treated as untrusted — [LearningInterventionMapper]
 * is deterministic, not an LLM, but [PolicyValidator.validateCreateTask] still gates every
 * field before anything reaches [TaskMutator.createTask]. "AI recommends, code decides"
 * still applies here: LearningDecisionEngine recommends, PolicyValidator decides.
 *
 * Deliberately a plain data class, not a [PermittedAction] case directly — the same
 * request needs to survive both a validation failure (rejected, nothing created) and a
 * successful validation (wrapped in [PermittedAction.CreateTask]), so it exists as its own
 * type rather than being constructed only after validation passes.
 */
data class CreateTaskRequest(
    val subject:            String,
    val topic:               String,
    val durationMinutes:     Int,
    val taskType:            TaskType = TaskType.OTHER,
    val priority:            Int      = 1,
    val scheduledStartTime:  String?  = null,   // optional "HH:mm" 24h — most learning-engine
                                                 // tasks are unscheduled and land in HomeScreen's
                                                 // "Unscheduled" section, same as any other task
                                                 // AdaptivePlanner couldn't fit into a free slot.
    val rationale:           String   = "",
    // P0a scope (per LearningDecisionEngine's own scoping note): only REPAIR_CONCEPT,
    // START_DIAGNOSTIC, ASSIGN_TARGETED_SET are wired end-to-end. Stored as the enum's
    // `.name` — see StudyTask.learningIntent's doc for why this stays a String here rather
    // than importing LearningInterventionIntent's type into this request shape.
    val learningIntent:      String?  = null,
    val conceptId:           String?  = null,
    val targetedConceptIds:  List<String> = emptyList()
)
