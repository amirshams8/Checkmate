package com.checkmate.planner.intervention

import com.checkmate.planner.model.StudyTask

/**
 * Proactive Execution Engine — Step 5 (Blueprint Part One, §13).
 *
 * Seam between [ActionExecutor] and PlanStore's real mutation surface. Exists purely so
 * ActionExecutorTest can run as a plain JVM unit test against a fake, instead of needing
 * PlanStore's actual CheckmatePrefs-backed (Android SharedPreferences) storage — same
 * reasoning as InterventionTransactionDao/FakeInterventionTransactionDao in step 3.
 * [PlanStoreTaskMutator] is the real production implementation, wired to the exact
 * functions PlanStore already exposes — this interface deliberately adds no new
 * capability PlanStore doesn't already have.
 */
interface TaskMutator {
    fun findTask(taskId: String): StudyTask?

    /** For a PENDING task. See [ActionExecutor] for why PAUSED uses [resumeTask] instead. */
    fun startTask(taskId: String)

    /** For a PAUSED task — preserves totalPausedMs accounting, unlike [startTask]. */
    fun resumeTask(taskId: String, resumedAt: Long)

    fun pauseTask(taskId: String, pausedAt: Long)

    fun reduceDuration(taskId: String, newDurationMinutes: Int)

    fun rescheduleTask(taskId: String, newScheduledStartTime: String)

    /**
     * Upgrade Blueprint Phase 2.4/2.5 (P0a). Unlike every function above, [taskId] does
     * not yet identify an existing task — it's the caller-generated id [request] should be
     * created under (see [PermittedAction.CreateTask]'s doc for why the id has to be
     * generated before this is called, not by this function). Returns the created
     * [StudyTask] so callers (currently just [ActionExecutor]) don't need a second
     * [findTask] round-trip to get back what they just created.
     */
    fun createTask(taskId: String, request: CreateTaskRequest): StudyTask
}
