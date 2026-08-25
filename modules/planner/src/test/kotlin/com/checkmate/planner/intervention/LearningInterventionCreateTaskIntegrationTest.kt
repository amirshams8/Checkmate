package com.checkmate.planner.intervention

import com.checkmate.learning.engine.LearningDecisionEngine
import com.checkmate.planner.model.TaskState
import com.checkmate.planner.model.TaskType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Upgrade Blueprint Phase 2.4/2.5 (P0a) — integration test for the full pipeline this
 * pass wires up:
 * ```
 * LearningDecisionEngine -> CandidateIntervention -> LearningInterventionMapper
 *     -> CreateTaskRequest -> PolicyValidator -> TaskEscrow/transaction
 *     -> TaskMutator.createTask() -> PlanStore -> StudyTask
 * ```
 * Uses [FakeTaskMutator] in place of [PlanStoreTaskMutator]/PlanStore (same reasoning as
 * every other plain-JVM test in this package: no Android SharedPreferences dependency).
 * Not a real end-to-end run against actual FT/test evidence and StudentModel/DecisionEngine
 * output — that's the separate step the blueprint calls out as coming after this plumbing
 * is in place, not part of it.
 */
class LearningInterventionCreateTaskIntegrationTest {

    private fun candidate(
        intent: LearningDecisionEngine.LearningInterventionIntent,
        conceptId: String? = "phy_rotational_inertia",
        subject: String? = "Physics",
        chapter: String? = "Rotational Motion",
        durationMinutes: Int = 25
    ) = LearningDecisionEngine.CandidateIntervention(
        intent = intent,
        conceptId = conceptId,
        subject = subject,
        chapter = chapter,
        topic = null,
        durationMinutes = durationMinutes,
        expectedGain = 1.5,
        priorityScore = 1.5,
        rationale = "Weak prerequisite for an upcoming target concept"
    )

    private class Fixture {
        val dao = FakeInterventionTransactionDao()
        val escrow = TaskEscrow(dao)
        val mutator = FakeTaskMutator()
        val executor = ActionExecutor(dao, escrow, mutator)
    }

    /**
     * Runs a [LearningDecisionEngine.CandidateIntervention] through every P0a stage and
     * returns the [ExecutionOutcome] — mirrors the orchestration
     * [LearningInterventionMapper]'s own doc describes as not yet existing as a single
     * production call site.
     */
    private suspend fun runPipeline(
        f: Fixture,
        candidateIntervention: LearningDecisionEngine.CandidateIntervention
    ): ExecutionOutcome {
        val request = LearningInterventionMapper.toCreateTaskRequest(candidateIntervention)
            ?: error("candidate was not mappable — test setup bug")

        val newTaskId = UUID.randomUUID().toString()
        // No existing InterventionTriggerType is a semantic match for "learning-engine
        // decided a task should exist" — none of the eleven values name that. BACKLOG_RISK
        // is the closest available fit; picking the right value (or adding one) is a
        // question for whoever wires this into a real trigger path, not this test.
        val acquireResult = f.escrow.acquire(newTaskId, InterventionTriggerType.BACKLOG_RISK)
        val transaction = (acquireResult as EscrowAcquireResult.Acquired).transaction

        val policyResult = PolicyValidator.validateCreateTask(newTaskId, request)
        val permitted = policyResult as PolicyResult.Permitted

        return f.executor.execute(transaction.transactionId, permitted.action)
    }

    @Test
    fun `REPAIR_CONCEPT candidate produces a real StudyTask via the full pipeline`() = runTest {
        val f = Fixture()
        val outcome = runPipeline(
            f,
            candidate(LearningDecisionEngine.LearningInterventionIntent.REPAIR_CONCEPT)
        )

        assertTrue(outcome is ExecutionOutcome.Applied)
        val created = (outcome as ExecutionOutcome.Applied).action as PermittedAction.CreateTask
        val task = f.mutator.currentState(created.taskId)

        assertEquals("Physics", task?.subject)
        assertEquals("Rotational Motion", task?.topic)
        assertEquals(25, task?.durationMinutes)
        assertEquals(TaskType.LECTURE, task?.taskType)
        assertEquals(TaskState.PENDING, task?.state)
        assertEquals("REPAIR_CONCEPT", task?.learningIntent)
        assertEquals("phy_rotational_inertia", task?.conceptId)
        assertEquals(InterventionState.COMPLETED, f.dao.getById(transactionIdOf(f, created.taskId))?.currentState)
    }

    @Test
    fun `START_DIAGNOSTIC candidate produces a real StudyTask via the full pipeline`() = runTest {
        val f = Fixture()
        val outcome = runPipeline(
            f,
            candidate(LearningDecisionEngine.LearningInterventionIntent.START_DIAGNOSTIC)
        )

        assertTrue(outcome is ExecutionOutcome.Applied)
        val created = (outcome as ExecutionOutcome.Applied).action as PermittedAction.CreateTask
        assertEquals(TaskType.PRACTICE, f.mutator.currentState(created.taskId)?.taskType)
    }

    @Test
    fun `ASSIGN_TARGETED_SET candidate produces a real StudyTask via the full pipeline`() = runTest {
        val f = Fixture()
        val outcome = runPipeline(
            f,
            candidate(LearningDecisionEngine.LearningInterventionIntent.ASSIGN_TARGETED_SET, conceptId = null)
        )

        assertTrue(outcome is ExecutionOutcome.Applied)
        val created = (outcome as ExecutionOutcome.Applied).action as PermittedAction.CreateTask
        val task = f.mutator.currentState(created.taskId)
        assertEquals(TaskType.PRACTICE, task?.taskType)
        assertEquals("ASSIGN_TARGETED_SET", task?.learningIntent)
    }

    @Test
    fun `an out-of-scope candidate never reaches PolicyValidator or the executor`() {
        val request = LearningInterventionMapper.toCreateTaskRequest(
            candidate(LearningDecisionEngine.LearningInterventionIntent.REPLAN_DAY)
        )
        assertEquals(null, request) // mapper declines; nothing downstream is exercised
    }

    /** Small helper — the transaction id isn't returned by [runPipeline] directly, so this
     *  re-derives it via [TaskEscrow]'s own "latest transaction for a task" lookup. */
    private suspend fun transactionIdOf(f: Fixture, taskId: String): String =
        f.dao.getLatestForTask(taskId)?.transactionId ?: error("no transaction found for $taskId")
}
