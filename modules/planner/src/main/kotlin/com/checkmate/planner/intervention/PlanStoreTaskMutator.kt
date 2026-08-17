package com.checkmate.planner.intervention

import com.checkmate.planner.PlanStore
import com.checkmate.planner.model.StudyTask

/**
 * Proactive Execution Engine — Step 5. Production [TaskMutator] — thin adapter over
 * PlanStore's existing mutation functions. Adds no new PlanStore functions: START_TASK on
 * a PENDING task maps to [PlanStore.setTaskActive], START_TASK on a PAUSED task maps to
 * [PlanStore.resumeTask] instead, so totalPausedMs accounting stays correct — see
 * [ActionExecutor] for why that distinction has to be made at execution time using live
 * task state, not baked into the action at validation time.
 *
 * Not wired into app startup / DI anywhere yet — nothing constructs [ActionExecutor] with
 * this in production code yet either. That wiring (and the Trigger Engine that would
 * actually call ActionExecutor) is a later step.
 */
class PlanStoreTaskMutator : TaskMutator {

    override fun findTask(taskId: String): StudyTask? =
        PlanStore.todayTasks.value.find { it.id == taskId }

    override fun startTask(taskId: String) {
        PlanStore.setTaskActive(taskId)
    }

    override fun resumeTask(taskId: String, resumedAt: Long) {
        PlanStore.resumeTask(taskId, resumedAt)
    }

    override fun pauseTask(taskId: String, pausedAt: Long) {
        PlanStore.pauseTask(taskId, pausedAt)
    }

    override fun reduceDuration(taskId: String, newDurationMinutes: Int) {
        PlanStore.updateTaskDuration(taskId, newDurationMinutes)
    }

    override fun rescheduleTask(taskId: String, newScheduledStartTime: String) {
        PlanStore.updateTaskSchedule(taskId, newScheduledStartTime)
    }
}
