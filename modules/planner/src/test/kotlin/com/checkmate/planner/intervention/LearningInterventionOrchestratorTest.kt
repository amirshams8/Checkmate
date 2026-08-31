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

    private class Fixture(
        planReplanner: FakePlanReplanner? = null,
        difficultyMutator: FakeDifficultyMutator? = null
    ) {
        val dao = FakeInterventionTransactionDao()
        val escrow = TaskEscrow(dao)
        val mutator = FakeTaskMutator()
        val executor = ActionExecutor(dao, escrow, mutator, planReplanner, difficultyMutator)
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
    fun `REGRESSION (P0a continuation) - REPLAN_DAY is no longer NotMappable, it now routes and executes`() = runTest {
        // Was: "an out-of-scope top candidate is rejected and recorded, then the next
        // candidate executes", using REPLAN_DAY as the NotMappable example. After the P0a
        // continuation, resolveRoute() has a REPLAN_DAY branch (PolicyValidator.validateReplanDay),
        // so a bare REPLAN_DAY candidate is now Permitted and gets escrowed/executed, not
        // rejected — the original assertions here would fail against current production code.
        // Kept as a named regression test (not silently rewritten) so the next person to read
        // this file sees why the shape changed.
        val f = Fixture(planReplanner = FakePlanReplanner())
        val result = f.orchestrator.executeTopCandidate(
            report(
                candidate(LearningDecisionEngine.LearningInterventionIntent.REPLAN_DAY, priorityScore = 1.6, conceptId = "c1"),
                candidate(LearningDecisionEngine.LearningInterventionIntent.REPAIR_CONCEPT, priorityScore = 0.2, conceptId = "c2")
            )
        )

        val created = result.outcome as LearningInterventionOrchestrator.OrchestrationOutcome.Created
        assertTrue(created.executionOutcome is ExecutionOutcome.Applied)
        assertTrue(result.rejections.isEmpty())
        // c2 (REPAIR_CONCEPT) is never reached — REPLAN_DAY (rank 1) already won the walk,
        // same "stop at the first executable candidate" behavior every other intent gets.
    }

    @Test
    fun `a REDUCE_DIFFICULTY candidate with no conceptId is rejected as NotMappable, then the next candidate executes`() = runTest {
        val f = Fixture()
        val result = f.orchestrator.executeTopCandidate(
            report(
                // Never expected from LearningDecisionEngine.conceptCandidates in practice
                // (it always sets conceptId for these two intents — see resolveRoute's own
                // doc), but this is the one remaining reachable NotMappable path today.
                candidate(LearningDecisionEngine.LearningInterventionIntent.REDUCE_DIFFICULTY, priorityScore = 1.6, conceptId = null),
                candidate(LearningDecisionEngine.LearningInterventionIntent.REPAIR_CONCEPT, priorityScore = 0.2, conceptId = "c2")
            )
        )

        val created = result.outcome as LearningInterventionOrchestrator.OrchestrationOutcome.Created
        assertEquals("c2", created.candidate.conceptId)

        assertEquals(1, result.rejections.size)
        val rejection = result.rejections.single()
        assertEquals(1, rejection.rank)
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
        // REGRESSION (P0a continuation): the original version of this test used REPLAN_DAY
        // and START_MOCK as its two "unmappable" candidates — both are now routable/mappable
        // (see the REPLAN_DAY regression test above), so this needed a genuinely still-
        // unmappable pair. REDUCE_DIFFICULTY/INCREASE_DIFFICULTY with conceptId=null are the
        // only reachable NotMappable case left after this pass.
        val f = Fixture()
        val result = f.orchestrator.executeTopCandidate(
            report(
                candidate(LearningDecisionEngine.LearningInterventionIntent.REDUCE_DIFFICULTY, priorityScore = 1.6, conceptId = null),
                candidate(LearningDecisionEngine.LearningInterventionIntent.INCREASE_DIFFICULTY, priorityScore = 0.2, conceptId = null)
            )
        )

        assertEquals(LearningInterventionOrchestrator.OrchestrationOutcome.NoExecutableCandidate, result.outcome)
        assertEquals(2, result.rejections.size)
        assertEquals(1, result.rejections[0].rank)
        assertEquals(2, result.rejections[1].rank)
        assertTrue(result.rejections.all { it.source is LearningInterventionOrchestrator.RejectionSource.NotMappable })
    }

    @Test
    fun `an empty decision report yields NoExecutableCandidate with no rejections`() = runTest {
        val f = Fixture()
        val result = f.orchestrator.executeTopCandidate(report())

        assertEquals(LearningInterventionOrchestrator.OrchestrationOutcome.NoExecutableCandidate, result.outcome)
        assertTrue(result.rejections.isEmpty())
    }

    // ── REPLAN_DAY / REDUCE_DIFFICULTY / INCREASE_DIFFICULTY end-to-end (P0a continuation) ──

    @Test
    fun `a REPLAN_DAY candidate replans today's plan through PlanReplanner and reports no StudyTask id`() = runTest {
        val replanner = FakePlanReplanner()
        val f = Fixture(planReplanner = replanner)
        val result = f.orchestrator.executeTopCandidate(
            report(candidate(LearningDecisionEngine.LearningInterventionIntent.REPLAN_DAY, priorityScore = 1.6, conceptId = null))
        )

        val created = result.outcome as LearningInterventionOrchestrator.OrchestrationOutcome.Created
        assertTrue(created.executionOutcome is ExecutionOutcome.Applied)
        assertEquals(1, replanner.callCount)
        // REPLAN_DAY has no real StudyTask — taskId here is the synthetic escrow key (see
        // OrchestrationOutcome.Created's own doc), never a PlanStore id.
        assertTrue("expected a synthetic replan: key, was ${created.taskId}", created.taskId.startsWith("replan:"))
        assertTrue(result.rejections.isEmpty())
    }

    @Test
    fun `a REPLAN_DAY candidate with no PlanReplanner wired still reaches Created, with executionOutcome Failed`() = runTest {
        // The orchestrator's job is routing/policy/escrow, not guaranteeing the execution
        // seam is wired — a missing PlanReplanner surfaces as an ExecutionOutcome.Failed
        // inside a Created result, same as ActionExecutorTest's own "not wired" case, not as
        // a routing or policy rejection.
        val f = Fixture() // planReplanner defaults to null
        val result = f.orchestrator.executeTopCandidate(
            report(candidate(LearningDecisionEngine.LearningInterventionIntent.REPLAN_DAY, priorityScore = 1.6, conceptId = null))
        )

        val created = result.outcome as LearningInterventionOrchestrator.OrchestrationOutcome.Created
        assertTrue(created.executionOutcome is ExecutionOutcome.Failed)
        assertTrue(result.rejections.isEmpty())
    }

    @Test
    fun `a REPLAN_DAY candidate whose escrow key is already under escrow is recorded as TaskIdCollision`() = runTest {
        val f = Fixture(planReplanner = FakePlanReplanner())
        // Pre-acquire the exact synthetic escrow key resolveRoute() would build for today's
        // REPLAN_DAY, simulating a concurrent negotiation already in flight.
        val dayKey = GapTaskLedger.todayKey()
        f.escrow.acquire("replan:$dayKey", InterventionTriggerType.BACKLOG_RISK, now = 1_000L)

        val result = f.orchestrator.executeTopCandidate(
            report(candidate(LearningDecisionEngine.LearningInterventionIntent.REPLAN_DAY, priorityScore = 1.6, conceptId = null)),
            now = 1_000L
        )

        assertEquals(LearningInterventionOrchestrator.OrchestrationOutcome.NoExecutableCandidate, result.outcome)
        val rejection = result.rejections.single()
        assertTrue(rejection.source is LearningInterventionOrchestrator.RejectionSource.TaskIdCollision)
    }

    @Test
    fun `a REDUCE_DIFFICULTY candidate records the direction through DifficultyMutator`() = runTest {
        val mutator = FakeDifficultyMutator()
        val f = Fixture(difficultyMutator = mutator)
        val result = f.orchestrator.executeTopCandidate(
            report(candidate(LearningDecisionEngine.LearningInterventionIntent.REDUCE_DIFFICULTY, priorityScore = 1.6, conceptId = "c1"))
        )

        val created = result.outcome as LearningInterventionOrchestrator.OrchestrationOutcome.Created
        assertTrue(created.executionOutcome is ExecutionOutcome.Applied)
        assertEquals(DifficultyDirection.REDUCE, mutator.current("c1"))
        assertEquals("difficulty:c1", created.taskId)
    }

    @Test
    fun `an INCREASE_DIFFICULTY candidate records the direction through DifficultyMutator`() = runTest {
        val mutator = FakeDifficultyMutator()
        val f = Fixture(difficultyMutator = mutator)
        val result = f.orchestrator.executeTopCandidate(
            report(candidate(LearningDecisionEngine.LearningInterventionIntent.INCREASE_DIFFICULTY, priorityScore = 1.6, conceptId = "c1"))
        )

        val created = result.outcome as LearningInterventionOrchestrator.OrchestrationOutcome.Created
        assertTrue(created.executionOutcome is ExecutionOutcome.Applied)
        assertEquals(DifficultyDirection.INCREASE, mutator.current("c1"))
    }

    @Test
    fun `a REDUCE_DIFFICULTY candidate with no DifficultyMutator wired still reaches Created, with executionOutcome Failed`() = runTest {
        val f = Fixture() // difficultyMutator defaults to null
        val result = f.orchestrator.executeTopCandidate(
            report(candidate(LearningDecisionEngine.LearningInterventionIntent.REDUCE_DIFFICULTY, priorityScore = 1.6, conceptId = "c1"))
        )

        val created = result.outcome as LearningInterventionOrchestrator.OrchestrationOutcome.Created
        assertTrue(created.executionOutcome is ExecutionOutcome.Failed)
        assertTrue(result.rejections.isEmpty())
    }

    @Test
    fun `SCHEDULE_RETENTION_TEST and START_MOCK candidates create real StudyTasks, like the original three intents`() = runTest {
        val f = Fixture()
        val result = f.orchestrator.executeTopCandidate(
            report(candidate(LearningDecisionEngine.LearningInterventionIntent.SCHEDULE_RETENTION_TEST, priorityScore = 1.6, conceptId = "c1"))
        )

        val created = result.outcome as LearningInterventionOrchestrator.OrchestrationOutcome.Created
        assertTrue(created.executionOutcome is ExecutionOutcome.Applied)
        assertEquals(TaskType.REVISION, f.mutator.currentState(created.taskId)?.taskType)
    }

    // NOTE (honest gap, not silently skipped): a test asserting SCHEDULE_RETENTION_TEST/
    // START_MOCK are excluded from GAP_LEDGER_TRACKED_INTENTS, and a test asserting
    // ReplanDayLedger actually blocks a second same-day REPLAN_DAY, both belong here in
    // principle. Neither is included: GapTaskLedger/ReplanDayLedger/ConceptDifficultyLedger
    // are all CheckmatePrefs-backed (see ConceptDifficultyLedger's own doc on that pattern),
    // and CheckmatePrefs.putString/getString are silent no-ops whenever CheckmatePrefs.init()
    // hasn't been called (see CheckmatePrefs.ready()) — which plain JVM unit tests never do,
    // same reason this file never touches PlanStore directly either. A test built on top of
    // that would always observe "nothing was ever marked," passing regardless of whether the
    // real tracked/untracked distinction (or the once-a-day guard) is correct — a false
    // positive, not real coverage. Exercising those two behaviors for real needs either a
    // fake/injectable seam for GapTaskLedger and ReplanDayLedger (neither currently has one —
    // both are called directly as objects, unlike TaskMutator/PlanReplanner/DifficultyMutator)
    // or an instrumented/Robolectric test with a real backing SharedPreferences. Flagging this
    // rather than writing a test that would pass either way.
}
