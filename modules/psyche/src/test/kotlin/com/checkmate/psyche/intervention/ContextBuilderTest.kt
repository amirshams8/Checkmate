package com.checkmate.psyche.intervention

import com.checkmate.planner.model.StudyTask
import com.checkmate.planner.model.TaskType
import com.checkmate.psyche.AttentionStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBuilderTest {

    private fun task(
        scheduledStartTime: String? = "19:00",
        taskType: TaskType = TaskType.PRACTICE
    ) = StudyTask(
        subject = "Physics",
        topic = "Electrostatics",
        durationMinutes = 90,
        scheduledStartTime = scheduledStartTime,
        taskType = taskType
    )

    @Test
    fun `build populates every field from the task and the source`() {
        val source = FakeBehaviorContextSource(
            skipCountBySubject = mapOf("Physics" to 3),
            skipCountByType = mapOf(("Physics" to "PRACTICE") to 2),
            recentSkipRatePercent = 42,
            streakDays = 5,
            todayCompletedSummary = "  Chemistry: Stoichiometry (LECTURE)",
            todayFreeTextUpdates = "- Back from coaching",
            attentionStats = AttentionStats(checksPassed = 8, checksMissed = 2, avgFocusMinutes = 22)
        )

        val context = ContextBuilder.build(task(), lateMinutes = 8, now = 1_000L, source = source)

        assertEquals("Physics", context.taskSubject)
        assertEquals("Electrostatics", context.taskTopic)
        assertEquals("PRACTICE", context.taskType)
        assertEquals("19:00", context.scheduledStartTime)
        assertEquals(8, context.lateMinutes)
        assertEquals(3, context.subjectSkipCount7d)
        assertEquals(2, context.subjectSkipCountByType7d)
        assertEquals(42, context.recentSkipRatePercent)
        assertEquals(5, context.streakDays)
        assertEquals("  Chemistry: Stoichiometry (LECTURE)", context.todayCompletedSummary)
        assertEquals("- Back from coaching", context.todayFreeTextUpdates)
        assertEquals(8, context.attentionStats.checksPassed)
    }

    @Test
    fun `toPromptText includes core fields`() {
        val context = ContextBuilder.build(
            task(), lateMinutes = 8, now = 1_000L,
            source = FakeBehaviorContextSource(
                skipCountBySubject = mapOf("Physics" to 3),
                recentSkipRatePercent = 42,
                streakDays = 5
            )
        )
        val text = context.toPromptText()

        assertTrue(text.contains("TASK: Physics — Electrostatics (PRACTICE)"))
        assertTrue(text.contains("SCHEDULED: 19:00"))
        assertTrue(text.contains("LATE BY: 8 minutes"))
        assertTrue(text.contains("RECENT SKIP RATE: 42%"))
        assertTrue(text.contains("Physics SKIPPED (7d): 3"))
        assertTrue(text.contains("STREAK: 5d"))
    }

    @Test
    fun `toPromptText omits SCHEDULED when the task has no scheduled start time`() {
        val context = ContextBuilder.build(
            task(scheduledStartTime = null), lateMinutes = 0, now = 1_000L,
            source = FakeBehaviorContextSource()
        )
        assertFalse(context.toPromptText().contains("SCHEDULED:"))
    }

    @Test
    fun `toPromptText omits LATE BY when the task is not late`() {
        val context = ContextBuilder.build(task(), lateMinutes = 0, now = 1_000L, source = FakeBehaviorContextSource())
        assertFalse(context.toPromptText().contains("LATE BY"))
    }

    @Test
    fun `toPromptText omits TODAY COMPLETED and TODAY UPDATES when both are blank`() {
        val context = ContextBuilder.build(
            task(), lateMinutes = 0, now = 1_000L,
            source = FakeBehaviorContextSource(todayCompletedSummary = "", todayFreeTextUpdates = "")
        )
        val text = context.toPromptText()
        assertFalse(text.contains("TODAY COMPLETED"))
        assertFalse(text.contains("TODAY UPDATES"))
    }

    @Test
    fun `toPromptText includes ATTENTION only when there is at least one check recorded`() {
        val withChecks = ContextBuilder.build(
            task(), lateMinutes = 0, now = 1_000L,
            source = FakeBehaviorContextSource(attentionStats = AttentionStats(3, 1, 20))
        )
        val withoutChecks = ContextBuilder.build(
            task(), lateMinutes = 0, now = 1_000L,
            source = FakeBehaviorContextSource(attentionStats = AttentionStats(0, 0, 0))
        )
        assertTrue(withChecks.toPromptText().contains("ATTENTION:"))
        assertFalse(withoutChecks.toPromptText().contains("ATTENTION:"))
    }

    @Test
    fun `toPromptText never fabricates the three unavailable fields`() {
        val context = ContextBuilder.build(task(), lateMinutes = 8, now = 1_000L, source = FakeBehaviorContextSource())
        val text = context.toPromptText()
        assertFalse(text.contains("UPCOMING TEST"))
        assertFalse(text.contains("AVAILABLE TIME"))
        assertFalse(text.contains("RECENT DISTRACTION"))
    }
}
