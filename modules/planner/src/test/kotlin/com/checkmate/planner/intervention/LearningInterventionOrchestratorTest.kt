package com.checkmate.planner.intervention

import com.checkmate.learning.engine.LearningDecisionEngine
import com.checkmate.planner.model.TaskType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Upgrade Blueprint Phase 2.4/2.5 (P0a follow-up) — covers [LearningInterventionOrchestrator],
 * the production call site [LearningInterventionCreateTaskIntegrationTest]'s own `runPipeline`
 * doc names as not yet existing. Same fixture shape (fakes, not PlanStore) as that test.
 */
class LearningInterventionOrchestratorTest {

    private fun candidate(
        intent: LearningDecisionEngine.LearningInterventionIntent,
        priorityScore: Double,
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
        expectedGain = priorityScore,
        priorityScore = priorityScore,
        rationale = "test candidate"
    )

    private fun report(vararg candidates: LearningDecisionEngine.CandidateIntervention) =
        LearningDecisionEngine.DecisionReport(
            studentId = "student-1",
            examType = "NEET",
            generatedAt = 1_000L,
            candidates = candidates.toList()
        )

    private class Fixture {
        val dao = FakeInterventionTransactionDao()
        val escrow = TaskEscrow(dao)
        val mutator = FakeTaskMutator()
        val executor = ActionExecutor(dao, escrow, mutator)
        var nextId = 0
        val orchestrator = LearningInterventionOrchestrator(
            taskEscrow = escrow,
            actionExecutor = executor,
            idGenerator = { "generated-task-${nextId++}" }
        )
    }

    @Test
    fun `single executable candidate produces a real StudyTask`() = runTest {
        val f = Fixture()
        val result = f.orchestrator.executeTopCandidate(
            report(candidate(LearningDecisionEngine.LearningInterventionIntent.REPAIR_CONCEPT, priorityScore = 1.6))
        )

        assertTrue(result.outcome is LearningInterventionOrchestrator.OrchestrationOutcome.Created)
        val created = result.outcome as LearningInterventionOrchestrator.OrchestrationOutcome.Created
        assertEquals(1, created.rank)
        assertTrue(created.executionOutcome is ExecutionOutcome.Applied)
        assertEquals(TaskType.LECTURE, f.mutator.currentState(created.taskId)?.taskType)
        assertTrue(result.rejections.isEmpty())
    }

    @Test
    fun `stops at the first executable candidate instead of creating one task per candidate`() = runTest {
        val f = Fixture()
        val result = f.orchestrator.executeTopCandidate(
            report(
                candidate(LearningDecisionEngine.LearningInterventionIntent.REPAIR_CONCEPT, priorityScore = 1.6, conceptId = "c1"),
                candidate(LearningDecisionEngine.LearningInterventionIntent.START_DIAGNOSTIC, priorityScore = 0.2, conceptId = "c2"),
                candidate(LearningDecisionEngine.LearningInterventionIntent.REPAIR_CONCEPT, priorityScore = 0.2, conceptId = "c3")
            )
        )

        val created = result.outcome as LearningInterventionOrchestrator.OrchestrationOutcome.Created
        assertEquals("c1", created.candidate.conceptId)
        // Only the winning candidate's taskId/idGenerator call/transaction ever exists —
        // candidates #2 and #3 are never mapped, validated, or given a taskId at all.
        assertEquals(1, f.dao.transactionsById.size)
        assertEquals(1, f.nextId)
        assertTrue(result.rejections.isEmpty())
    }

    @Test
    fun `an out-of-scope top candidate is rejected and recorded, then the next candidate executes`() = runTest {
        val f = Fixture()
        val result = f.orchestrator.executeTopCandidate(
            report(
                // REPLAN_DAY is outside LearningInterventionMapper's SUPPORTED_INTENTS.
                candidate(LearningDecisionEngine.LearningInterventionIntent.REPLAN_DAY, priorityScore = 1.6, conceptId = "c1"),
                candidate(LearningDecisionEngine.LearningInterventionIntent.REPAIR_CONCEPT, priorityScore = 0.2, conceptId = "c2")
            )
        )

        val created = result.outcome as LearningInterventionOrchestrator.OrchestrationOutcome.Created
        assertEquals("c2", created.candidate.conceptId)

        assertEquals(1, result.rejections.size)
        val rejection = result.rejections.single()
        assertEquals(1, rejection.rank)
        assertEquals("c1", rejection.candidate.conceptId)
        assertTrue(rejection.source is LearningInterventionOrchestrator.RejectionSource.NotMappable)
    }

    @Test
    fun `a policy-rejected top candidate is recorded, then the next candidate executes`() = runTest {
        val f = Fixture()
        val result = f.orchestrator.executeTopCandidate(
            report(
                // durationMinutes below PolicyValidator.MIN_DURATION_MINUTES.
                candidate(LearningDecisionEngine.LearningInterventionIntent.REPAIR_CONCEPT, priorityScore = 1.6, conceptId = "c1", durationMinutes = 1),
                candidate(LearningDecisionEngine.LearningInterventionIntent.REPAIR_CONCEPT, priorityScore = 0.2, conceptId = "c2")
            )
        )

        val created = result.outcome as LearningInterventionOrchestrator.OrchestrationOutcome.Created
        assertEquals("c2", created.candidate.conceptId)

        assertEquals(1, result.rejections.size)
        val rejection = result.rejections.single()
        assertEquals("c1", rejection.candidate.conceptId)
        val source = rejection.source as LearningInterventionOrchestrator.RejectionSource.PolicyRejected
        assertEquals(RejectionReason.DURATION_TOO_SHORT, source.reason)
    }

    @Test
    fun `every candidate rejected yields NoExecutableCandidate with all rejections recorded`() = runTest {
        val f = Fixture()
        val result = f.orchestrator.executeTopCandidate(
            report(
                candidate(LearningDecisionEngine.LearningInterventionIntent.REPLAN_DAY, priorityScore = 1.6, conceptId = "c1"),
                candidate(LearningDecisionEngine.LearningInterventionIntent.START_MOCK, priorityScore = 0.2, conceptId = "c2")
            )
        )

        assertEquals(LearningInterventionOrchestrator.OrchestrationOutcome.NoExecutableCandidate, result.outcome)
        assertEquals(2, result.rejections.size)
        assertEquals(1, result.rejections[0].rank)
        assertEquals(2, result.rejections[1].rank)
    }

    @Test
    fun `an empty decision report yields NoExecutableCandidate with no rejections`() = runTest {
        val f = Fixture()
        val result = f.orchestrator.executeTopCandidate(report())

        assertEquals(LearningInterventionOrchestrator.OrchestrationOutcome.NoExecutableCandidate, result.outcome)
        assertTrue(result.rejections.isEmpty())
    }
}
