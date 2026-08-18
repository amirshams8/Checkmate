package com.checkmate.planner.intervention

import com.checkmate.planner.model.StudyTask
import com.checkmate.planner.model.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class TriggerEvaluatorTest {

    private fun millisAt(hour: Int, minute: Int): Long {
        val zone = ZoneId.systemDefault()
        return ZonedDateTime.of(LocalDate.now(zone), LocalTime.of(hour, minute), zone)
            .toInstant().toEpochMilli()
    }

    private fun task(
        scheduledStartTime: String? = "19:00",
        state: TaskState = TaskState.PENDING
    ) = StudyTask(
        subject = "Physics",
        topic = "Electrostatics",
        durationMinutes = 90,
        state = state,
        scheduledStartTime = scheduledStartTime
    )

    @Test
    fun `no signal before the scheduled time`() {
        val result = TriggerEvaluator.evaluate(task(), now(18, 55))
        assertNull(result)
    }

    @Test
    fun `no signal within the grace window right after scheduled time`() {
        val result = TriggerEvaluator.evaluate(task(), now(19, 3))
        assertNull(result)
    }

    @Test
    fun `TASK_NOT_STARTED fires once past the threshold`() {
        val result = TriggerEvaluator.evaluate(task(), now(19, 5))
        assertEquals(InterventionTriggerType.TASK_NOT_STARTED, result?.triggerType)
        assertEquals(5, result?.lateMinutes)
    }

    @Test
    fun `LATE_START fires once past the escalation threshold`() {
        val result = TriggerEvaluator.evaluate(task(), now(19, 12))
        assertEquals(InterventionTriggerType.LATE_START, result?.triggerType)
        assertEquals(12, result?.lateMinutes)
    }

    @Test
    fun `no signal for a task that is already ACTIVE`() {
        val result = TriggerEvaluator.evaluate(task(state = TaskState.ACTIVE), now(19, 30))
        assertNull(result)
    }

    @Test
    fun `no signal for a task that is DONE`() {
        val result = TriggerEvaluator.evaluate(task(state = TaskState.DONE), now(19, 30))
        assertNull(result)
    }

    @Test
    fun `no signal for a task with no scheduled start time`() {
        val result = TriggerEvaluator.evaluate(task(scheduledStartTime = null), now(19, 30))
        assertNull(result)
    }

    @Test
    fun `no signal for an unparseable scheduled start time`() {
        val result = TriggerEvaluator.evaluate(task(scheduledStartTime = "not-a-time"), now(19, 30))
        assertNull(result)
    }

    private fun now(hour: Int, minute: Int) = millisAt(hour, minute)
}
