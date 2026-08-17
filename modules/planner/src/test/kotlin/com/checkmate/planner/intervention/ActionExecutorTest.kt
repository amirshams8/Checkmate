package com.checkmate.planner.intervention

import com.checkmate.planner.model.StudyTask
import com.checkmate.planner.model.TaskState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    private class Fixture(task: StudyTask) {
        val dao = FakeInterventionTransactionDao()
        val escrow = TaskEscrow(dao)
        val mutator = FakeTaskMutator(listOf(task))
        val executor = ActionExecutor(dao, escrow, mutator)
        val taskId = task.id

        suspend fun acquire(now: Long = 1_000L): InterventionTransaction =
            (escrow.acquire(taskId, InterventionTriggerType.LATE_START, now = now)
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
}
