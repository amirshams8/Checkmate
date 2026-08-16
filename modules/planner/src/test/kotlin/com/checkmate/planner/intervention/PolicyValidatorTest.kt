package com.checkmate.planner.intervention

import com.checkmate.planner.model.StudyTask
import com.checkmate.planner.model.TaskState
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
        val intent = LlmIntent(
            speech = "Take 15.",
            intentType = InterventionIntentType.TAKE_SHORT_BREAK,
            parameters = mapOf("minutes" to "15")
        )
        val result = PolicyValidator.validate(intent, PolicyState(task = null))
        assertTrue(result is PolicyResult.Permitted)
    }

    @Test
    fun `4 hour break is rejected`() {
        val intent = LlmIntent(
            speech = "Take 240.",
            intentType = InterventionIntentType.TAKE_SHORT_BREAK,
            parameters = mapOf("minutes" to "240")
        )
        val result = PolicyValidator.validate(intent, PolicyState(task = null))
        assertRejectedWith(result, RejectionReason.BREAK_TOO_LONG)
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

    private fun assertRejectedWith(result: PolicyResult, reason: RejectionReason) {
        assertTrue("expected Rejected but was $result", result is PolicyResult.Rejected)
        assertEquals(reason, (result as PolicyResult.Rejected).reason)
    }
}
