package com.checkmate.planner.intervention

import com.checkmate.planner.model.StudyTask
import com.checkmate.planner.model.TaskState
import com.checkmate.planner.model.TaskType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proactive Execution Engine — Step 5 (Blueprint Part One, §13). LLM/voice/the Trigger
 * Engine are not involved anywhere here — ActionExecutor is exercised directly against
 * PermittedActions, as scoped.
 */
class ActionExecutorTest {

    private fun task(
        state: TaskState = TaskState.PENDING,
        durationMinutes: Int = 90,
        scheduledStartTime: String? = null,
        pausedAt: Long? = null,
        totalPausedMs: Long = 0L
    ) = StudyTask(
        subject = "Physics",
        topic = "Electrostatics",
        durationMinutes = durationMinutes,
        state = state,
        scheduledStartTime = scheduledStartTime,
        pausedAt = pausedAt,
        totalPausedMs = totalPausedMs
    )

    private class Fixture(
        task: StudyTask,
        val planReplanner: FakePlanReplanner? = null,
        val difficultyMutator: FakeDifficultyMutator? = null
    ) {
        val dao = FakeInterventionTransactionDao()
        val escrow = TaskEscrow(dao)
        val mutator = FakeTaskMutator(listOf(task))
        val executor = ActionExecutor(dao, escrow, mutator, planReplanner, difficultyMutator)
        val taskId = task.id

        suspend fun acquire(now: Long = 1_000L): InterventionTransaction =
            (escrow.acquire(taskId, InterventionTriggerType.LATE_START, now = now)
                    as EscrowAcquireResult.Acquired).transaction

        /** P0a — CREATE_TASK escrows on a caller-generated id that doesn't identify an
         *  existing task yet (see [PermittedAction.CreateTask]'s doc), so tests that
         *  exercise it acquire on a fresh id instead of [taskId]. Reused (P0a continuation)
         *  by REPLAN_DAY/AdjustDifficulty tests, which escrow on a synthetic, non-StudyTask
         *  key the same way — see [PermittedAction.ReplanDay]/[PermittedAction.AdjustDifficulty]'s
         *  own docs. */
        suspend fun acquireFor(id: String, now: Long = 1_000L): InterventionTransaction =
            (escrow.acquire(id, InterventionTriggerType.LATE_START, now = now)
                    as EscrowAcquireResult.Acquired).transaction
    }

    // ── START_TASK ───────────────────────────────────────────────────────

    @Test
    fun `start task on PENDING applies and completes the transaction`() = runTest {
        val f = Fixture(task(state = TaskState.PENDING))
        val tx = f.acquire()

        val outcome = f.executor.execute(tx.transactionId, PermittedAction.StartTask(f.taskId))

        assertTrue(outcome is ExecutionOutcome.Applied)
        assertEquals(TaskState.ACTIVE, f.mutator.currentState(f.taskId)?.state)
        assertEquals(InterventionState.COMPLETED, f.dao.getById(tx.transactionId)?.currentState)
    }

    @Test
    fun `start task on PAUSED resumes instead of restarting, preserving pause accounting`() = runTest {
        val f = Fixture(task(state = TaskState.PAUSED, pausedAt = 1_000L, totalPausedMs = 2_000L))
        val tx = f.acquire(now = 5_000L)

        val outcome = f.executor.execute(tx.transactionId, PermittedAction.StartTask(f.taskId), now = 5_000L)

        assertTrue(outcome is ExecutionOutcome.Applied)
        val result = f.mutator.currentState(f.taskId)
        assertEquals(TaskState.ACTIVE, result?.state)
        assertEquals(null, result?.pausedAt)
        // 2_000 (already accumulated) + 4_000 (5_000 - 1_000 elapsed since pausedAt)
        assertEquals(6_000L, result?.totalPausedMs)
    }

    @Test
    fun `start task on already ACTIVE is a no-op but still completes the transaction`() = runTest {
        val f = Fixture(task(state = TaskState.ACTIVE))
        val tx = f.acquire()

        val outcome = f.executor.execute(tx.transactionId, PermittedAction.StartTask(f.taskId))

        assertTrue(outcome is ExecutionOutcome.NoOpAlreadyApplied)
        assertEquals(InterventionState.COMPLETED, f.dao.getById(tx.transactionId)?.currentState)
    }

    @Test
    fun `start task on a DONE task fails and resolves EXECUTION_FAILED`() = runTest {
        val f = Fixture(task(state = TaskState.DONE))
        val tx = f.acquire()

        val outcome = f.executor.execute(tx.transactionId, PermittedAction.StartTask(f.taskId))

        assertTrue(outcome is ExecutionOutcome.Failed)
        assertEquals(InterventionState.EXECUTION_FAILED, f.dao.getById(tx.transactionId)?.currentState)
    }

    // ── REDUCE_DURATION ──────────────────────────────────────────────────

    @Test
    fun `reduce duration applies and completes the transaction`() = runTest {
        val f = Fixture(task(durationMinutes = 90))
        val tx = f.acquire()

        val outcome = f.executor.execute(tx.transactionId, PermittedAction.ReduceDuration(f.taskId, 35))

        assertTrue(outcome is ExecutionOutcome.Applied)
        assertEquals(35, f.mutator.currentState(f.taskId)?.durationMinutes)
        assertEquals(InterventionState.COMPLETED, f.dao.getById(tx.transactionId)?.currentState)
    }

    @Test
    fun `reduce duration to the same value already in effect is a no-op`() = runTest {
        val f = Fixture(task(durationMinutes = 35))
        val tx = f.acquire()

        val outcome = f.executor.execute(tx.transactionId, PermittedAction.ReduceDuration(f.taskId, 35))

        assertTrue(outcome is ExecutionOutcome.NoOpAlreadyApplied)
    }

    // ── RESCHEDULE_TASK ──────────────────────────────────────────────────

    @Test
    fun `reschedule applies and completes the transaction`() = runTest {
        val f = Fixture(task(scheduledStartTime = "19:00"))
        val tx = f.acquire()

        val outcome = f.executor.execute(tx.transactionId, PermittedAction.RescheduleTask(f.taskId, "20:00"))

        assertTrue(outcome is ExecutionOutcome.Applied)
        assertEquals("20:00", f.mutator.currentState(f.taskId)?.scheduledStartTime)
    }

    @Test
    fun `reschedule to the same time already in effect is a no-op`() = runTest {
        val f = Fixture(task(scheduledStartTime = "20:00"))
        val tx = f.acquire()

        val outcome = f.executor.execute(tx.transactionId, PermittedAction.RescheduleTask(f.taskId, "20:00"))

        assertTrue(outcome is ExecutionOutcome.NoOpAlreadyApplied)
    }

    // ── TAKE_SHORT_BREAK ─────────────────────────────────────────────────

    @Test
    fun `short break on PENDING pauses the task and completes the transaction`() = runTest {
        val f = Fixture(task(state = TaskState.PENDING))
        val tx = f.acquire(now = 3_000L)

        val outcome = f.executor.execute(tx.transactionId, PermittedAction.ShortBreak(f.taskId, 15), now = 3_000L)

        assertTrue(outcome is ExecutionOutcome.Applied)
        val result = f.mutator.currentState(f.taskId)
        assertEquals(TaskState.PAUSED, result?.state)
        assertEquals(3_000L, result?.pausedAt)
    }

    @Test
    fun `short break on an already PAUSED task is a no-op`() = runTest {
        val f = Fixture(task(state = TaskState.PAUSED, pausedAt = 1_000L))
        val tx = f.acquire()

        val outcome = f.executor.execute(tx.transactionId, PermittedAction.ShortBreak(f.taskId, 15))

        assertTrue(outcome is ExecutionOutcome.NoOpAlreadyApplied)
    }

    @Test
    fun `short break on a DONE task fails and resolves EXECUTION_FAILED`() = runTest {
        val f = Fixture(task(state = TaskState.DONE))
        val tx = f.acquire()

        val outcome = f.executor.execute(tx.transactionId, PermittedAction.ShortBreak(f.taskId, 15))

        assertTrue(outcome is ExecutionOutcome.Failed)
        assertEquals(InterventionState.EXECUTION_FAILED, f.dao.getById(tx.transactionId)?.currentState)
    }

    // ── Non-mutating actions ─────────────────────────────────────────────

    @Test
    fun `KEEP_PLAN NO_ACTION and REQUEST_CLARIFICATION do not touch the task but do complete`() = runTest {
        listOf(PermittedAction.KeepPlan, PermittedAction.NoAction, PermittedAction.RequestClarification)
            .forEach { action ->
                val f = Fixture(task(durationMinutes = 90, scheduledStartTime = "19:00"))
                val tx = f.acquire()
                val before = f.mutator.currentState(f.taskId)

                val outcome = f.executor.execute(tx.transactionId, action)

                assertTrue("$action should be NotApplicable", outcome is ExecutionOutcome.NotApplicable)
                assertEquals(before, f.mutator.currentState(f.taskId))
                assertEquals(InterventionState.COMPLETED, f.dao.getById(tx.transactionId)?.currentState)
            }
    }

    @Test
    fun `REQUEST_GUARDIAN is recognized and completes without mutating the task`() = runTest {
        val f = Fixture(task())
        val tx = f.acquire()
        val before = f.mutator.currentState(f.taskId)

        val outcome = f.executor.execute(tx.transactionId, PermittedAction.RequestGuardian)

        assertTrue(outcome is ExecutionOutcome.RequiresGuardianEscalation)
        assertEquals(before, f.mutator.currentState(f.taskId))
        assertEquals(InterventionState.COMPLETED, f.dao.getById(tx.transactionId)?.currentState)
    }

    // ── Duplicate delivery / transaction lookup (Blueprint §13) ─────────

    @Test
    fun `duplicate delivery of an already-resolved transaction does not mutate the task twice`() = runTest {
        val f = Fixture(task(durationMinutes = 90))
        val tx = f.acquire()

        val first = f.executor.execute(tx.transactionId, PermittedAction.ReduceDuration(f.taskId, 35))
        assertTrue(first is ExecutionOutcome.Applied)
        assertEquals(35, f.mutator.currentState(f.taskId)?.durationMinutes)

        // Same transactionId delivered again — e.g. a retried WorkManager job.
        val second = f.executor.execute(tx.transactionId, PermittedAction.ReduceDuration(f.taskId, 35))
        assertEquals(ExecutionOutcome.TransactionAlreadyResolved, second)
        // Still 35 — not reduced again, and no error either.
        assertEquals(35, f.mutator.currentState(f.taskId)?.durationMinutes)
    }

    @Test
    fun `executing an unknown transaction id returns TransactionNotFound`() = runTest {
        val f = Fixture(task())
        val outcome = f.executor.execute("does-not-exist", PermittedAction.StartTask(f.taskId))
        assertEquals(ExecutionOutcome.TransactionNotFound, outcome)
    }

    @Test
    fun `task deleted between validation and execution fails cleanly`() = runTest {
        val f = Fixture(task())
        val tx = f.acquire()

        val outcome = f.executor.execute(tx.transactionId, PermittedAction.StartTask("some-other-task-id"))

        assertTrue(outcome is ExecutionOutcome.Failed)
        assertEquals(InterventionState.EXECUTION_FAILED, f.dao.getById(tx.transactionId)?.currentState)
    }

    // ── CREATE_TASK (Upgrade Blueprint Phase 2.4/2.5, P0a) ───────────────

    private fun createTaskRequest(
        subject: String = "Physics",
        topic: String = "Rotational Motion",
        durationMinutes: Int = 25,
        learningIntent: String? = "REPAIR_CONCEPT",
        conceptId: String? = "phy_rotational_inertia"
    ) = CreateTaskRequest(
        subject = subject,
        topic = topic,
        durationMinutes = durationMinutes,
        taskType = TaskType.LECTURE,
        rationale = "Weak prerequisite for an upcoming target concept",
        learningIntent = learningIntent,
        conceptId = conceptId
    )

    @Test
    fun `create task on a fresh id applies, creates the StudyTask, and completes the transaction`() = runTest {
        val f = Fixture(task()) // seed task is irrelevant here — CreateTask uses a fresh id
        val newTaskId = "learning-engine-task-1"
        val tx = f.acquireFor(newTaskId)

        val outcome = f.executor.execute(tx.transactionId, PermittedAction.CreateTask(newTaskId, createTaskRequest()))

        assertTrue(outcome is ExecutionOutcome.Applied)
        val created = f.mutator.currentState(newTaskId)
        assertEquals("Physics", created?.subject)
        assertEquals("Rotational Motion", created?.topic)
        assertEquals(25, created?.durationMinutes)
        assertEquals(TaskType.LECTURE, created?.taskType)
        assertEquals("REPAIR_CONCEPT", created?.learningIntent)
        assertEquals("phy_rotational_inertia", created?.conceptId)
        assertEquals(TaskState.PENDING, created?.state) // StudyTask's own default
        assertEquals(InterventionState.COMPLETED, f.dao.getById(tx.transactionId)?.currentState)
    }

    @Test
    fun `duplicate delivery of a CreateTask transaction does not create a second StudyTask`() = runTest {
        val f = Fixture(task())
        val newTaskId = "learning-engine-task-2"
        val tx = f.acquireFor(newTaskId)
        val request = createTaskRequest()

        val first = f.executor.execute(tx.transactionId, PermittedAction.CreateTask(newTaskId, request))
        assertTrue(first is ExecutionOutcome.Applied)

        // Same transactionId delivered again — e.g. a retried WorkManager job. The primary
        // guard (transaction already terminal) catches this before applyCreateTask runs.
        val second = f.executor.execute(tx.transactionId, PermittedAction.CreateTask(newTaskId, request))
        assertEquals(ExecutionOutcome.TransactionAlreadyResolved, second)
    }

    @Test
    fun `create task where the id already exists (idempotency guard) is a no-op, not a second task`() = runTest {
        val f = Fixture(task())
        val newTaskId = "learning-engine-task-3"
        // Simulate the id having already been created by a prior, separately-resolved
        // execution (not the primary already-terminal guard above) — applyCreateTask's own
        // defensive re-check (mirrors every other applyX in this class) is what catches this.
        f.mutator.createTask(newTaskId, createTaskRequest())
        val tx = f.acquireFor("distinct-fresh-id-for-escrow")

        val outcome = f.executor.execute(tx.transactionId, PermittedAction.CreateTask(newTaskId, createTaskRequest()))

        assertTrue(outcome is ExecutionOutcome.NoOpAlreadyApplied)
    }

    @Test
    fun `create task with no scheduledStartTime lands unscheduled, same as any other task`() = runTest {
        val f = Fixture(task())
        val newTaskId = "learning-engine-task-4"
        val tx = f.acquireFor(newTaskId)

        f.executor.execute(tx.transactionId, PermittedAction.CreateTask(newTaskId, createTaskRequest()))

        assertNull(f.mutator.currentState(newTaskId)?.scheduledStartTime)
    }

    // ── REPLAN_DAY (Upgrade Blueprint Phase 2.4/2.5, P0a continuation) ────

    @Test
    fun `replan day applies, calls PlanReplanner once, and completes the transaction`() = runTest {
        val replanner = FakePlanReplanner()
        val f = Fixture(task(), planReplanner = replanner)
        val key = "replan:2026_243"
        val tx = f.acquireFor(key)

        val outcome = f.executor.execute(tx.transactionId, PermittedAction.ReplanDay(key))

        assertTrue(outcome is ExecutionOutcome.Applied)
        assertEquals(1, replanner.callCount)
        assertEquals(InterventionState.COMPLETED, f.dao.getById(tx.transactionId)?.currentState)
    }

    @Test
    fun `replan day with no PlanReplanner wired fails and resolves EXECUTION_FAILED`() = runTest {
        val f = Fixture(task()) // planReplanner defaults to null
        val key = "replan:2026_243"
        val tx = f.acquireFor(key)

        val outcome = f.executor.execute(tx.transactionId, PermittedAction.ReplanDay(key))

        assertTrue(outcome is ExecutionOutcome.Failed)
        assertEquals(InterventionState.EXECUTION_FAILED, f.dao.getById(tx.transactionId)?.currentState)
    }

    @Test
    fun `replan day where PlanReplanner throws is caught and resolves EXECUTION_FAILED, not a propagated exception`() = runTest {
        val replanner = FakePlanReplanner(throwOnReplan = RuntimeException("AdaptivePlanner blew up"))
        val f = Fixture(task(), planReplanner = replanner)
        val key = "replan:2026_243"
        val tx = f.acquireFor(key)

        val outcome = f.executor.execute(tx.transactionId, PermittedAction.ReplanDay(key))

        assertTrue(outcome is ExecutionOutcome.Failed)
        assertEquals(1, replanner.callCount)
        assertEquals(InterventionState.EXECUTION_FAILED, f.dao.getById(tx.transactionId)?.currentState)
    }

    @Test
    fun `duplicate delivery of a completed ReplanDay transaction does not replan a second time`() = runTest {
        val replanner = FakePlanReplanner()
        val f = Fixture(task(), planReplanner = replanner)
        val key = "replan:2026_243"
        val tx = f.acquireFor(key)

        val first = f.executor.execute(tx.transactionId, PermittedAction.ReplanDay(key))
        assertTrue(first is ExecutionOutcome.Applied)
        assertEquals(1, replanner.callCount)

        // Same transactionId delivered again — the primary already-terminal guard catches
        // this before applyReplanDay ever runs a second time. Unlike CreateTask/ReduceDuration,
        // there is no live-state re-check possible for ReplanDay (see its own applyX doc), so
        // this primary guard is the ONLY thing preventing a duplicate delivery from wiping the
        // day's plan twice.
        val second = f.executor.execute(tx.transactionId, PermittedAction.ReplanDay(key))
        assertEquals(ExecutionOutcome.TransactionAlreadyResolved, second)
        assertEquals(1, replanner.callCount)
    }

    // ── REDUCE_DIFFICULTY / INCREASE_DIFFICULTY (Upgrade Blueprint Phase 2.4/2.5, P0a continuation) ─

    @Test
    fun `adjust difficulty applies and records the direction`() = runTest {
        val mutator = FakeDifficultyMutator()
        val f = Fixture(task(), difficultyMutator = mutator)
        val key = "difficulty:phy_rotational_inertia"
        val tx = f.acquireFor(key)

        val outcome = f.executor.execute(
            tx.transactionId,
            PermittedAction.AdjustDifficulty("phy_rotational_inertia", DifficultyDirection.REDUCE, key)
        )

        assertTrue(outcome is ExecutionOutcome.Applied)
        assertEquals(DifficultyDirection.REDUCE, mutator.current("phy_rotational_inertia"))
        assertEquals(1, mutator.adjustCallCount)
        assertEquals(InterventionState.COMPLETED, f.dao.getById(tx.transactionId)?.currentState)
    }

    @Test
    fun `adjust difficulty to the direction already recorded is a no-op and does not rewrite it`() = runTest {
        val mutator = FakeDifficultyMutator(seed = mapOf("phy_rotational_inertia" to DifficultyDirection.INCREASE))
        val f = Fixture(task(), difficultyMutator = mutator)
        val key = "difficulty:phy_rotational_inertia"
        val tx = f.acquireFor(key)

        val outcome = f.executor.execute(
            tx.transactionId,
            PermittedAction.AdjustDifficulty("phy_rotational_inertia", DifficultyDirection.INCREASE, key)
        )

        assertTrue(outcome is ExecutionOutcome.NoOpAlreadyApplied)
        assertEquals(0, mutator.adjustCallCount)
        assertEquals(InterventionState.COMPLETED, f.dao.getById(tx.transactionId)?.currentState)
    }

    @Test
    fun `adjust difficulty from REDUCE to INCREASE overwrites the recorded direction`() = runTest {
        val mutator = FakeDifficultyMutator(seed = mapOf("phy_rotational_inertia" to DifficultyDirection.REDUCE))
        val f = Fixture(task(), difficultyMutator = mutator)
        val key = "difficulty:phy_rotational_inertia"
        val tx = f.acquireFor(key)

        val outcome = f.executor.execute(
            tx.transactionId,
            PermittedAction.AdjustDifficulty("phy_rotational_inertia", DifficultyDirection.INCREASE, key)
        )

        assertTrue(outcome is ExecutionOutcome.Applied)
        assertEquals(DifficultyDirection.INCREASE, mutator.current("phy_rotational_inertia"))
        assertEquals(1, mutator.adjustCallCount)
    }

    @Test
    fun `adjust difficulty with no DifficultyMutator wired fails and resolves EXECUTION_FAILED`() = runTest {
        val f = Fixture(task()) // difficultyMutator defaults to null
        val key = "difficulty:phy_rotational_inertia"
        val tx = f.acquireFor(key)

        val outcome = f.executor.execute(
            tx.transactionId,
            PermittedAction.AdjustDifficulty("phy_rotational_inertia", DifficultyDirection.REDUCE, key)
        )

        assertTrue(outcome is ExecutionOutcome.Failed)
        assertEquals(InterventionState.EXECUTION_FAILED, f.dao.getById(tx.transactionId)?.currentState)
    }

    @Test
    fun `duplicate delivery of a completed AdjustDifficulty transaction does not adjust a second time`() = runTest {
        val mutator = FakeDifficultyMutator()
        val f = Fixture(task(), difficultyMutator = mutator)
        val key = "difficulty:phy_rotational_inertia"
        val tx = f.acquireFor(key)
        val action = PermittedAction.AdjustDifficulty("phy_rotational_inertia", DifficultyDirection.REDUCE, key)

        val first = f.executor.execute(tx.transactionId, action)
        assertTrue(first is ExecutionOutcome.Applied)

        val second = f.executor.execute(tx.transactionId, action)
        assertEquals(ExecutionOutcome.TransactionAlreadyResolved, second)
        assertEquals(1, mutator.adjustCallCount)
    }
}
