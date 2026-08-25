package com.checkmate.planner.intervention

import com.checkmate.planner.model.StudyTask
import com.checkmate.planner.model.TaskState
import com.checkmate.planner.model.TaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId

/**
 * Proactive Execution Engine — Step 2 (Blueprint Part One, §11 / §26 build sequence).
 * The policy test matrix. Pure JVM unit tests — PolicyValidator has no Android
 * dependency, so none of this needs Robolectric or an instrumented test.
 */
class PolicyValidatorTest {

    private fun pendingTask(durationMinutes: Int = 90, state: TaskState = TaskState.PENDING) = StudyTask(
        subject = "Physics",
        topic = "Electrostatics",
        durationMinutes = durationMinutes,
        state = state
    )

    private fun stateAt(hour: Int, minute: Int, task: StudyTask?): PolicyState {
        val zone = ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val millis = java.time.ZonedDateTime.of(today, LocalTime.of(hour, minute), zone)
            .toInstant().toEpochMilli()
        return PolicyState(task = task, nowMillis = millis)
    }

    // ── Blueprint §11 worked examples ───────────────────────────────────────

    @Test
    fun `REDUCE_DURATION with valid smaller duration is permitted`() {
        val task = pendingTask(durationMinutes = 90)
        val intent = LlmIntent(
            speech = "Reducing to 35 minutes.",
            intentType = InterventionIntentType.REDUCE_DURATION,
            targetTaskId = task.id,
            parameters = mapOf("newDurationMinutes" to "35")
        )
        val result = PolicyValidator.validate(intent, PolicyState(task = task))
        assertTrue(result is PolicyResult.Permitted)
        val action = (result as PolicyResult.Permitted).action as PermittedAction.ReduceDuration
        assertEquals(35, action.newDurationMinutes)
    }

    @Test
    fun `negative duration is rejected`() {
        val task = pendingTask()
        val intent = LlmIntent(
            speech = "x",
            intentType = InterventionIntentType.REDUCE_DURATION,
            targetTaskId = task.id,
            parameters = mapOf("newDurationMinutes" to "-10")
        )
        val result = PolicyValidator.validate(intent, PolicyState(task = task))
        assertRejectedWith(result, RejectionReason.NEGATIVE_OR_ZERO_DURATION)
    }

    @Test
    fun `unknown task id is rejected`() {
        val intent = LlmIntent(
            speech = "x",
            intentType = InterventionIntentType.REDUCE_DURATION,
            targetTaskId = "does-not-exist",
            parameters = mapOf("newDurationMinutes" to "35")
        )
        val result = PolicyValidator.validate(intent, PolicyState(task = null))
        assertRejectedWith(result, RejectionReason.UNKNOWN_TASK_ID)
    }

    @Test
    fun `UNLOCK_DEVICE is not representable and is rejected as unrecognized`() {
        val intentType = LlmIntent.parseIntentType("UNLOCK_DEVICE")
        assertEquals(null, intentType)
        val intent = LlmIntent(speech = "x", intentType = intentType)
        val result = PolicyValidator.validate(intent, PolicyState(task = null))
        assertRejectedWith(result, RejectionReason.UNRECOGNIZED_INTENT)
    }

    @Test
    fun `DISABLE_GUARDIAN is not representable and is rejected as unrecognized`() {
        val intentType = LlmIntent.parseIntentType("DISABLE_GUARDIAN")
        assertEquals(null, intentType)
        val intent = LlmIntent(speech = "x", intentType = intentType)
        val result = PolicyValidator.validate(intent, PolicyState(task = null))
        assertRejectedWith(result, RejectionReason.UNRECOGNIZED_INTENT)
    }

    @Test
    fun `REMOVE_WORKMODE is not representable and is rejected as unrecognized`() {
        val intentType = LlmIntent.parseIntentType("REMOVE_WORKMODE")
        assertEquals(null, intentType)
        val intent = LlmIntent(speech = "x", intentType = intentType)
        val result = PolicyValidator.validate(intent, PolicyState(task = null))
        assertRejectedWith(result, RejectionReason.UNRECOGNIZED_INTENT)
    }

    @Test
    fun `15 minute break is permitted`() {
        // Amended in step 5: TAKE_SHORT_BREAK now requires a task (PlanStore.pauseTask
        // needs one to actually pause), so this needs PolicyState.task set.
        val task = pendingTask()
        val intent = LlmIntent(
            speech = "Take 15.",
            intentType = InterventionIntentType.TAKE_SHORT_BREAK,
            targetTaskId = task.id,
            parameters = mapOf("minutes" to "15")
        )
        val result = PolicyValidator.validate(intent, PolicyState(task = task))
        assertTrue(result is PolicyResult.Permitted)
        val action = (result as PolicyResult.Permitted).action as PermittedAction.ShortBreak
        assertEquals(task.id, action.taskId)
    }

    @Test
    fun `4 hour break is rejected`() {
        val task = pendingTask()
        val intent = LlmIntent(
            speech = "Take 240.",
            intentType = InterventionIntentType.TAKE_SHORT_BREAK,
            targetTaskId = task.id,
            parameters = mapOf("minutes" to "240")
        )
        val result = PolicyValidator.validate(intent, PolicyState(task = task))
        assertRejectedWith(result, RejectionReason.BREAK_TOO_LONG)
    }

    @Test
    fun `short break on a task with no task in PolicyState is rejected`() {
        val intent = LlmIntent(
            speech = "Take 15.",
            intentType = InterventionIntentType.TAKE_SHORT_BREAK,
            parameters = mapOf("minutes" to "15")
        )
        val result = PolicyValidator.validate(intent, PolicyState(task = null))
        assertRejectedWith(result, RejectionReason.UNKNOWN_TASK_ID)
    }

    // ── Additional coverage beyond the blueprint's worked examples ─────────

    @Test
    fun `reduce duration below minimum is rejected`() {
        val task = pendingTask(durationMinutes = 90)
        val intent = LlmIntent(
            speech = "x",
            intentType = InterventionIntentType.REDUCE_DURATION,
            targetTaskId = task.id,
            parameters = mapOf("newDurationMinutes" to "2")
        )
        val result = PolicyValidator.validate(intent, PolicyState(task = task))
        assertRejectedWith(result, RejectionReason.DURATION_TOO_SHORT)
    }

    @Test
    fun `reduce duration that is not actually smaller is rejected`() {
        val task = pendingTask(durationMinutes = 90)
        val intent = LlmIntent(
            speech = "x",
            intentType = InterventionIntentType.REDUCE_DURATION,
            targetTaskId = task.id,
            parameters = mapOf("newDurationMinutes" to "120")
        )
        val result = PolicyValidator.validate(intent, PolicyState(task = task))
        assertRejectedWith(result, RejectionReason.DURATION_NOT_A_REDUCTION)
    }

    @Test
    fun `reduce duration on a DONE task is rejected`() {
        val task = pendingTask(durationMinutes = 90, state = TaskState.DONE)
        val intent = LlmIntent(
            speech = "x",
            intentType = InterventionIntentType.REDUCE_DURATION,
            targetTaskId = task.id,
            parameters = mapOf("newDurationMinutes" to "35")
        )
        val result = PolicyValidator.validate(intent, PolicyState(task = task))
        assertRejectedWith(result, RejectionReason.TASK_NOT_ACTIVE_STATE)
    }

    @Test
    fun `reduce duration with malformed number is rejected`() {
        val task = pendingTask()
        val intent = LlmIntent(
            speech = "x",
            intentType = InterventionIntentType.REDUCE_DURATION,
            targetTaskId = task.id,
            parameters = mapOf("newDurationMinutes" to "soon")
        )
        val result = PolicyValidator.validate(intent, PolicyState(task = task))
        assertRejectedWith(result, RejectionReason.MALFORMED_INTENT)
    }

    @Test
    fun `start task on a PENDING task is permitted`() {
        val task = pendingTask(state = TaskState.PENDING)
        val intent = LlmIntent(speech = "x", intentType = InterventionIntentType.START_TASK, targetTaskId = task.id)
        val result = PolicyValidator.validate(intent, PolicyState(task = task))
        assertTrue(result is PolicyResult.Permitted)
    }

    @Test
    fun `start task on an already ACTIVE task is rejected`() {
        val task = pendingTask(state = TaskState.ACTIVE)
        val intent = LlmIntent(speech = "x", intentType = InterventionIntentType.START_TASK, targetTaskId = task.id)
        val result = PolicyValidator.validate(intent, PolicyState(task = task))
        assertRejectedWith(result, RejectionReason.TASK_NOT_ACTIVE_STATE)
    }

    @Test
    fun `reschedule to a valid future time is permitted`() {
        val task = pendingTask()
        val intent = LlmIntent(
            speech = "x",
            intentType = InterventionIntentType.RESCHEDULE_TASK,
            targetTaskId = task.id,
            parameters = mapOf("newScheduledStartTime" to "20:00")
        )
        val result = PolicyValidator.validate(intent, stateAt(19, 0, task))
        assertTrue(result is PolicyResult.Permitted)
    }

    @Test
    fun `reschedule to a time in the past is rejected`() {
        val task = pendingTask()
        val intent = LlmIntent(
            speech = "x",
            intentType = InterventionIntentType.RESCHEDULE_TASK,
            targetTaskId = task.id,
            parameters = mapOf("newScheduledStartTime" to "18:00")
        )
        val result = PolicyValidator.validate(intent, stateAt(19, 0, task))
        assertRejectedWith(result, RejectionReason.INVALID_RESCHEDULE_TIME)
    }

    @Test
    fun `reschedule with malformed time string is rejected`() {
        val task = pendingTask()
        val intent = LlmIntent(
            speech = "x",
            intentType = InterventionIntentType.RESCHEDULE_TASK,
            targetTaskId = task.id,
            parameters = mapOf("newScheduledStartTime" to "8pm")
        )
        val result = PolicyValidator.validate(intent, stateAt(19, 0, task))
        assertRejectedWith(result, RejectionReason.INVALID_RESCHEDULE_TIME)
    }

    @Test
    fun `KEEP_PLAN NO_ACTION REQUEST_CLARIFICATION and REQUEST_GUARDIAN are always permitted`() {
        listOf(
            InterventionIntentType.KEEP_PLAN,
            InterventionIntentType.NO_ACTION,
            InterventionIntentType.REQUEST_CLARIFICATION,
            InterventionIntentType.REQUEST_GUARDIAN
        ).forEach { type ->
            val intent = LlmIntent(speech = "x", intentType = type)
            val result = PolicyValidator.validate(intent, PolicyState(task = null))
            assertTrue("$type should be permitted", result is PolicyResult.Permitted)
        }
    }

    // ── validateCreateTask (Upgrade Blueprint Phase 2.4/2.5, P0a) ──────────

    private fun createTaskRequest(
        subject: String = "Physics",
        topic: String = "Rotational Motion",
        durationMinutes: Int = 25,
        learningIntent: String? = "REPAIR_CONCEPT",
        scheduledStartTime: String? = null
    ) = CreateTaskRequest(
        subject = subject,
        topic = topic,
        durationMinutes = durationMinutes,
        taskType = TaskType.LECTURE,
        scheduledStartTime = scheduledStartTime,
        learningIntent = learningIntent,
        conceptId = "phy_rotational_inertia"
    )

    @Test
    fun `REPAIR_CONCEPT create request is permitted`() {
        val result = PolicyValidator.validateCreateTask("new-task-1", createTaskRequest(learningIntent = "REPAIR_CONCEPT"))
        assertTrue(result is PolicyResult.Permitted)
        val action = (result as PolicyResult.Permitted).action as PermittedAction.CreateTask
        assertEquals("new-task-1", action.taskId)
    }

    @Test
    fun `START_DIAGNOSTIC create request is permitted`() {
        val result = PolicyValidator.validateCreateTask("new-task-2", createTaskRequest(learningIntent = "START_DIAGNOSTIC"))
        assertTrue(result is PolicyResult.Permitted)
    }

    @Test
    fun `ASSIGN_TARGETED_SET create request is permitted`() {
        val result = PolicyValidator.validateCreateTask("new-task-3", createTaskRequest(learningIntent = "ASSIGN_TARGETED_SET"))
        assertTrue(result is PolicyResult.Permitted)
    }

    @Test
    fun `create request with no learningIntent (non-learning-engine caller) is permitted`() {
        val result = PolicyValidator.validateCreateTask("new-task-4", createTaskRequest(learningIntent = null))
        assertTrue(result is PolicyResult.Permitted)
    }

    @Test
    fun `create request naming an out-of-scope learning intent is rejected`() {
        val result = PolicyValidator.validateCreateTask(
            "new-task-5",
            createTaskRequest(learningIntent = "REPLAN_DAY")
        )
        assertRejectedWith(result, RejectionReason.UNSUPPORTED_LEARNING_INTENT)
    }

    @Test
    fun `create request with blank subject is rejected`() {
        val result = PolicyValidator.validateCreateTask("new-task-6", createTaskRequest(subject = ""))
        assertRejectedWith(result, RejectionReason.MALFORMED_INTENT)
    }

    @Test
    fun `create request with blank topic is rejected`() {
        val result = PolicyValidator.validateCreateTask("new-task-7", createTaskRequest(topic = "  "))
        assertRejectedWith(result, RejectionReason.MALFORMED_INTENT)
    }

    @Test
    fun `create request with zero duration is rejected`() {
        val result = PolicyValidator.validateCreateTask("new-task-8", createTaskRequest(durationMinutes = 0))
        assertRejectedWith(result, RejectionReason.NEGATIVE_OR_ZERO_DURATION)
    }

    @Test
    fun `create request below minimum duration is rejected`() {
        val result = PolicyValidator.validateCreateTask("new-task-9", createTaskRequest(durationMinutes = 3))
        assertRejectedWith(result, RejectionReason.DURATION_TOO_SHORT)
    }

    @Test
    fun `create request with a valid scheduledStartTime is permitted`() {
        val result = PolicyValidator.validateCreateTask(
            "new-task-10",
            createTaskRequest(scheduledStartTime = "20:00")
        )
        assertTrue(result is PolicyResult.Permitted)
    }

    @Test
    fun `create request with a malformed scheduledStartTime is rejected`() {
        val result = PolicyValidator.validateCreateTask(
            "new-task-11",
            createTaskRequest(scheduledStartTime = "8pm")
        )
        assertRejectedWith(result, RejectionReason.INVALID_RESCHEDULE_TIME)
    }

    private fun assertRejectedWith(result: PolicyResult, reason: RejectionReason) {
        assertTrue("expected Rejected but was $result", result is PolicyResult.Rejected)
        assertEquals(reason, (result as PolicyResult.Rejected).reason)
    }
}
