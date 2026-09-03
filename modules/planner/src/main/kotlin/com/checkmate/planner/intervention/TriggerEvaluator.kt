package com.checkmate.planner.intervention

import com.checkmate.planner.model.StudyTask
import com.checkmate.planner.model.TaskState
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Proactive Execution Engine — Step 7 (Blueprint Part One, §2).
 *
 * The event detector. Deliberately scoped to the two signals derivable from a
 * [StudyTask] alone — TASK_NOT_STARTED and LATE_START — since those are the only two of
 * §2's eleven listed signals this codebase currently has grounded data for. The other
 * nine (distraction detected, repeated skips, backlog risk, upcoming test, etc.) need
 * BehaviorLedger/TodayContext, which hasn't been read this session; adding them here on
 * a guess would be exactly the kind of unread-source assumption to avoid. [evaluate] is
 * a pure function, no Android/DB/network dependency, same testability posture as
 * PolicyValidator.
 */
object TriggerEvaluator {

    /** Below this, "N minutes late" isn't worth interrupting the student for yet. */
    const val NOT_STARTED_THRESHOLD_MINUTES = 5

    /** Past this, TASK_NOT_STARTED escalates to LATE_START. */
    const val LATE_ESCALATION_THRESHOLD_MINUTES = 10

    /**
     * BUGFIX (silent delay never enforced): past this, a still-PENDING task stops being
     * "just another notification" and gets a real consequence — WorkMode lockdown +
     * escalation watchlist, via [OverdueEnforcementGateway]. Deliberately just a threshold
     * compared against [TriggerSignal.lateMinutes], the same value [evaluate] already
     * computes — this is not a second lateness calculation, and nothing here changes what
     * [evaluate] returns or how it's computed. See InterventionTriggerWorker's own doc for
     * why 30 is a threshold to compare against, not a real-time deadline: the periodic
     * worker that reads this has a 15-minute WorkManager floor and no guaranteed exact
     * cadence, so this can legitimately fire anywhere from ~30 to ~44 minutes late
     * depending on when the worker last happened to run — that's expected, not a bug.
     */
    const val OVERDUE_ENFORCEMENT_THRESHOLD_MINUTES = 30

    data class TriggerSignal(val triggerType: InterventionTriggerType, val lateMinutes: Int)

    /**
     * Returns a fired signal, or null if nothing should trigger right now. Only PENDING
     * tasks with a parseable "HH:mm" [StudyTask.scheduledStartTime] are considered — a
     * task that's ACTIVE/PAUSED/DONE/SKIPPED, or has no schedule at all, has nothing for
     * this signal to detect.
     */
    fun evaluate(task: StudyTask, nowMillis: Long = System.currentTimeMillis()): TriggerSignal? {
        if (task.state != TaskState.PENDING) return null
        val scheduled = task.scheduledStartTime?.let(::parseTimeOrNull) ?: return null

        val nowTime = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalTime()
        val lateMinutes = Duration.between(scheduled, nowTime).toMinutes().toInt()
        if (lateMinutes < NOT_STARTED_THRESHOLD_MINUTES) return null

        val triggerType = if (lateMinutes >= LATE_ESCALATION_THRESHOLD_MINUTES) {
            InterventionTriggerType.LATE_START
        } else {
            InterventionTriggerType.TASK_NOT_STARTED
        }
        return TriggerSignal(triggerType, lateMinutes)
    }

    private fun parseTimeOrNull(raw: String): LocalTime? =
        try {
            LocalTime.parse(raw)
        } catch (e: Exception) {
            null
        }
}
