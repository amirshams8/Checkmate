package com.checkmate.planner

import com.checkmate.core.CheckmatePrefs
import com.checkmate.planner.model.StudyTask
import com.checkmate.planner.model.TaskState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.Calendar

// Blueprint 2.6: aggregated pause/focus numbers for the Stats screen.
// actualFocusMinutesToday sums StudyTask.actualMinutes (real focus time, pauses
// excluded — see StudyTask.actualMinutes) across today's completed sessions.
// avgPausesPerSession / pauseRatePercent are computed over the trailing 7 days
// of *attempted* sessions (DONE or SKIPPED — a task that was never started has
// no pause behavior to measure), so a fatigue/distraction trend is visible
// even on a day with zero completions.
data class PauseStats(
    val actualFocusMinutesToday: Int   = 0,
    val avgPausesPerSession:     Float = 0f,
    val pauseRatePercent:        Int   = 0
)

object PlanStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val _todayTasks = MutableStateFlow<List<StudyTask>>(emptyList())
    val todayTasks: StateFlow<List<StudyTask>> = _todayTasks.asStateFlow()

    init { reload() }

    private fun reload() {
        val saved = CheckmatePrefs.getString("plan_${todayKey()}", null)
        _todayTasks.value = saved?.let {
            try { json.decodeFromString(it) } catch (_: Exception) { emptyList() }
        } ?: emptyList()
    }

    fun saveTodayTasks(tasks: List<StudyTask>) = persist(tasks)

    /**
     * Appends a single manually-created task to today's plan WITHOUT touching any
     * existing tasks (generated or custom). Unlike saveTodayTasks(), which replaces
     * the whole list, this is additive — safe to call any time, any number of times.
     * The new task goes through the exact same StudyTask model as AdaptivePlanner
     * output, so it picks up Start/Pause/Done/Skip, AttentionCycleService, and
     * GuardianNotifier WhatsApp + Telegram reporting automatically — no separate
     * code path needed anywhere downstream.
     */
    fun addCustomTask(task: StudyTask) = persist(_todayTasks.value + task)

    /**
     * Upgrade Blueprint Phase 2.4/2.5 (P0a) — appends a single LearningDecisionEngine-
     * originated task to today's plan. Same additive semantics as [addCustomTask]
     * (existing tasks, generated or custom, are untouched); kept as its own entry point
     * rather than reusing addCustomTask() so a StudyTask's provenance is never ambiguous:
     * addCustomTask() is what HomeViewModel calls for a student-typed task (isCustom=true),
     * this is what [com.checkmate.planner.intervention.TaskMutator.createTask] calls for a
     * task whose shape and content came from a validated [com.checkmate.planner.intervention.CreateTaskRequest]
     * (isCustom stays false — learningIntent/conceptId/targetedConceptIds carry the
     * provenance instead, see StudyTask's doc on those fields).
     *
     * Caller is expected to have already run this through TaskEscrow + PolicyValidator —
     * PlanStore itself has no opinion on validity, same as addCustomTask/saveTodayTasks.
     */
    fun createTask(task: StudyTask) = persist(_todayTasks.value + task)

    /** Removes a single task by id (used to let a student delete a custom task they added by mistake). */
    fun removeTask(taskId: String) = persist(_todayTasks.value.filterNot { it.id == taskId })

    /** Edits the planned duration of a single task — used for custom-task duration editing. */
    fun updateTaskDuration(taskId: String, durationMinutes: Int) = updateTask(taskId) {
        it.copy(durationMinutes = durationMinutes)
    }

    /**
     * Manually assigns/edits a task's scheduledStartTime — backs the "Schedule" action on
     * an Unscheduled TaskCard (see HomeViewModel.scheduleTask / HomeScreen's
     * ScheduleTaskDialog). Works for both custom and AI-generated tasks: a generated task
     * can land in "Unscheduled" too when AdaptivePlanner.assignScheduledTimes() couldn't
     * fit it into today's free time, and this is how the student fixes that from HomeScreen
     * without deleting and re-adding it.
     */
    fun updateTaskSchedule(taskId: String, scheduledStartTime: String) = updateTask(taskId) {
        it.copy(scheduledStartTime = scheduledStartTime)
    }

    /**
     * Manually sets both scheduledStartTime AND durationMinutes for a task in a single
     * write — backs the Timeline/List clock-icon's start+end time editor (see
     * HomeViewModel.rescheduleTask / HomeScreen's ScheduleTaskDialog). Unlike calling
     * updateTaskSchedule() and updateTaskDuration() separately, this goes through one
     * persist() call so the two fields can never observably desync mid-update (e.g. a
     * StateFlow collector seeing the new start time paired with the old duration for one
     * frame).
     */
    fun updateTaskScheduleAndDuration(taskId: String, scheduledStartTime: String, durationMinutes: Int) = updateTask(taskId) {
        it.copy(scheduledStartTime = scheduledStartTime, durationMinutes = durationMinutes)
    }

    fun setTaskActive(taskId: String) = updateTask(taskId) { it.copy(state = TaskState.ACTIVE) }

    fun markTask(taskId: String, state: TaskState) = updateTask(taskId) {
        it.copy(state = state, completedAt = if (state == TaskState.DONE) System.currentTimeMillis() else null)
    }

    /**
     * BUGFIX (P0b AlreadyActive-guard bypass via silent no-op revert): [markTask] only ever
     * mutates [_todayTasks] (via [updateTask] -> [persist], both hardcoded to [todayKey]).
     * Every READ path that needs a task from an arbitrary day already falls back to
     * [loadDay] ([findTask] in GapTaskManager, [LearningInterventionOrchestrator]'s
     * activeUnresolvedTask) — but there was no equivalent WRITE fallback. Confirmed live:
     * GapTaskManager.resolveDoneConcept's "evidence not imported yet, revert taskId to
     * PENDING" call used plain `markTask(taskId, PENDING)`. When the active task's
     * dayKey ([GapTaskLedger.activeTaskDayKey]) wasn't today's key, `updateTask`'s
     * `.map { if (it.id == taskId) ... }` against `_todayTasks.value` matched nothing,
     * `persist()` re-saved today's (unrelated) list, and the task's real DONE state in
     * `plan_<dayKey>` was never touched. The task then stayed reported as DONE — so
     * LearningInterventionOrchestrator.executeTopCandidate's AlreadyActive guard (which
     * treats a DONE task as "nothing blocking") let a fresh task get created for the same
     * still-unresolved concept, while GapTaskLedger's session/round fields were untouched
     * (this branch never calls resetForNextRound), so the new task pointed straight back at
     * the OLD, already-submitted Testmate session — reproducing exactly the "retest button
     * reopens the old result page" symptom. This targets the correct day's persisted list
     * directly instead of assuming "today," and only touches [_todayTasks] when [dayKey] IS
     * today's — same "today's live list first" contract every read path already follows.
     * Returns true if a matching task was actually found and updated, so a caller that needs
     * to know whether the revert actually landed (rather than silently doing nothing) can log
     * or branch on it instead of assuming success the way the old markTask call did.
     */
    fun markTaskInDay(dayKey: String, taskId: String, state: TaskState): Boolean {
        if (dayKey == todayKey()) {
            val found = _todayTasks.value.any { it.id == taskId }
            if (found) markTask(taskId, state)
            return found
        }
        val existing = loadDay(dayKey)
        val found = existing.any { it.id == taskId }
        if (!found) return false
        val updated = existing.map {
            if (it.id == taskId) {
                it.copy(state = state, completedAt = if (state == TaskState.DONE) System.currentTimeMillis() else null)
            } else it
        }
        CheckmatePrefs.putString("plan_$dayKey", json.encodeToString(updated))
        CheckmatePrefs.putLong("tasks_updated_at_$dayKey", System.currentTimeMillis())
        return true
    }

    /**
     * Blueprint 2.6: records the session's real focus time — as measured by
     * AttentionCycleManager's tick loop, which freezes while PAUSED (see
     * HomeViewModel.confirmCompletion/markSkip) — onto the task itself. Sets both
     * focusMinutes (read by BehaviorLedger.record -> feeds Stats "Avg Focus") and
     * actualMinutes (read by getPauseStats() -> Stats "actual focus time (not wall
     * clock)"). Previously neither field was ever written, so both stats silently
     * read 0 forever; this is the fix, called right before markTask() on completion/skip.
     */
    fun setFocusMinutes(taskId: String, minutes: Int) = updateTask(taskId) {
        it.copy(focusMinutes = minutes, actualMinutes = minutes)
    }

    fun pauseTask(taskId: String, pausedAt: Long) = updateTask(taskId) {
        it.copy(state = TaskState.PAUSED, pausedAt = pausedAt, pauseCount = it.pauseCount + 1)
    }

    fun resumeTask(taskId: String, resumedAt: Long) = updateTask(taskId) { task ->
        val elapsed = if (task.pausedAt != null && task.pausedAt > 0L) resumedAt - task.pausedAt else 0L
        task.copy(state = TaskState.ACTIVE, pausedAt = null, totalPausedMs = task.totalPausedMs + elapsed)
    }

    // ── Accountability Core: Intention Declaration + Session Check-In (Blueprint 10.1) ──

    /** Stores the student's free-text answer to "What will you study?" before a session starts. */
    fun setIntention(taskId: String, intentionText: String) = updateTask(taskId) {
        it.copy(intentionText = intentionText)
    }

    /** Stores the student's self-report ("YES" | "PARTIAL" | "NO") to "Did you finish it?" after a session ends. */
    fun setCompletionStatus(taskId: String, status: String) = updateTask(taskId) {
        it.copy(completedStatus = status)
    }

    private fun updateTask(taskId: String, block: (StudyTask) -> StudyTask) {
        persist(_todayTasks.value.map { if (it.id == taskId) block(it) else it })
    }

    /**
     * Single write path for today's task list — every mutating function above funnels
     * through this instead of duplicating the "encode + persist + update StateFlow"
     * triplet. Also stamps tasks_updated_at_<dayKey> alongside plan_<dayKey> so
     * TaskSyncManager (app layer) can tell whether the local copy or a synced remote
     * copy is newer, without PlanStore itself knowing anything about sync/network —
     * this module stays local-storage-only, same as before.
     */
    private fun persist(tasks: List<StudyTask>) {
        val key = todayKey()
        CheckmatePrefs.putString("plan_$key", json.encodeToString(tasks))
        CheckmatePrefs.putLong("tasks_updated_at_$key", System.currentTimeMillis())
        _todayTasks.value = tasks
    }

    /** Epoch ms today's plan was last locally modified — used by TaskSyncManager to decide push vs. pull. */
    fun getLastUpdatedAt(): Long = CheckmatePrefs.getLong("tasks_updated_at_${todayKey()}", 0L)

    /** Public day key (e.g. "2026_224") — exposed so TaskSyncManager's payloads use the exact same
     *  day boundary PlanStore itself uses, instead of re-deriving it and risking drift. */
    fun currentDayKey(): String = todayKey()

    suspend fun getTodayTasksSnapshot(): List<StudyTask> = _todayTasks.value

    // Synchronous version for BroadcastReceiver context (no coroutine scope available)
    fun getTodayTasksSnapshot_Sync(): List<StudyTask> = _todayTasks.value

    fun getTodayCompletionPercent(): Int {
        val t = _todayTasks.value
        return if (t.isEmpty()) 0 else t.count { it.state == TaskState.DONE } * 100 / t.size
    }

    // ── End-of-day cleanup (9 PM) ────────────────────────────────────────────

    private const val EOD_CLEAR_HOUR = 21 // 9 PM — DONE/SKIPPED tasks clear, everything else keeps reminding
    private const val KEY_LAST_EOD_CLEANUP_DAY = "plan_last_eod_cleanup_day"

    /**
     * Bugfix ("old tasks not clearing at end of day"): [_todayTasks] was only ever populated
     * at process start ([init]/[reload]) or by a local write ([persist]) — nothing re-derived
     * it when the day moved on, so a DONE/SKIPPED task just sat in the list, and in Compose,
     * forever, until the app process happened to restart. This is the fix: called from
     * ReminderService's existing 15-min loop so it actually runs while the app is alive,
     * not just at cold start.
     *
     * No-ops before [EOD_CLEAR_HOUR] (9 PM) local time, and no-ops again once it's already
     * run for [todayKey]'s date — [KEY_LAST_EOD_CLEANUP_DAY] is claimed BEFORE the list is
     * touched specifically so a crash mid-cleanup can't leave the guard unset and cause the
     * next 15-min tick to re-run the carry-forward below and duplicate tasks onto tomorrow's
     * plan ("misswiring" the reminders on the ones that are still pending).
     *
     * DONE/SKIPPED tasks are dropped — nothing left to remind about. Everything still
     * unresolved (PENDING/ACTIVE/PAUSED) is left untouched in [_todayTasks] — it keeps
     * showing on Today and keeps triggering ReminderService.checkPendingTasks()'s nudge
     * exactly as before — and is ALSO copied onto tomorrow's persisted plan
     * (plan_<tomorrowKey>), deduped by task id, so it isn't simply abandoned the moment the
     * real calendar day rolls over and PlanStore starts reading a different SharedPrefs key.
     */
    fun cleanupCompletedIfDue() {
        val cal = Calendar.getInstance()
        if (cal.get(Calendar.HOUR_OF_DAY) < EOD_CLEAR_HOUR) return

        val key = todayKey()
        if (CheckmatePrefs.getString(KEY_LAST_EOD_CLEANUP_DAY, "") == key) return
        CheckmatePrefs.putString(KEY_LAST_EOD_CLEANUP_DAY, key)

        val current = _todayTasks.value
        val unresolved = current.filter { it.state != TaskState.DONE && it.state != TaskState.SKIPPED }
        if (unresolved.size != current.size) persist(unresolved)

        if (unresolved.isNotEmpty()) {
            val tomorrowCal = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
            val tomorrowKey = keyForDay(tomorrowCal)
            val existingTomorrow = loadDay(tomorrowKey)
            val existingIds = existingTomorrow.map { it.id }.toSet()
            val carried = unresolved.filterNot { it.id in existingIds }
            if (carried.isNotEmpty()) {
                CheckmatePrefs.putString("plan_$tomorrowKey", json.encodeToString(existingTomorrow + carried))
            }
        }
    }

    fun getStreakDays(): Int {
        var streak = 0
        val cal = Calendar.getInstance()
        for (i in 0..30) {
            val saved = CheckmatePrefs.getString("plan_${keyForDay(cal)}", null) ?: break
            val tasks = try { json.decodeFromString<List<StudyTask>>(saved) } catch (_: Exception) { break }
            if (tasks.isEmpty() || tasks.none { it.state == TaskState.DONE }) break
            streak++
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return streak
    }

    // ── Consistency tracking (Stats consistency calendar + "no plan == no study") ──────────

    /**
     * Per-day study status used by the Stats consistency calendar and by
     * [getConsecutiveMissedDays]. A day with no saved plan at all, or a plan saved with zero
     * tasks in it, is MISSED — "no tasks in plan" is treated as "no study happened", not as a
     * neutral/unknown state, which is what silently reading everything as 0% used to do.
     */
    enum class DayStudyStatus { COMPLETE, PARTIAL, MISSED, FUTURE }

    /** Status for a single calendar day. Days after today are FUTURE (not yet knowable). */
    fun getDayStatus(cal: Calendar): DayStudyStatus {
        val today = Calendar.getInstance()
        val isFuture = cal.get(Calendar.YEAR) > today.get(Calendar.YEAR) ||
            (cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                cal.get(Calendar.DAY_OF_YEAR) > today.get(Calendar.DAY_OF_YEAR))
        if (isFuture) return DayStudyStatus.FUTURE

        val tasks = loadDay(keyForDay(cal))
        if (tasks.isEmpty()) return DayStudyStatus.MISSED // no tasks in plan == no study
        val done = tasks.count { it.state == TaskState.DONE }
        return when {
            done == tasks.size -> DayStudyStatus.COMPLETE
            done > 0            -> DayStudyStatus.PARTIAL
            else                 -> DayStudyStatus.MISSED
        }
    }

    /**
     * Day-of-month -> status for every day in [year]/[month] (month is 0-based, matching
     * java.util.Calendar). Backs the Stats screen's consistency calendar (green/yellow/red
     * dots per day, like a GitHub-style contribution grid).
     */
    fun getMonthConsistency(year: Int, month: Int): Map<Int, DayStudyStatus> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, month)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val result = LinkedHashMap<Int, DayStudyStatus>()
        for (d in 1..daysInMonth) {
            cal.set(Calendar.DAY_OF_MONTH, d)
            result[d] = getDayStatus(cal)
        }
        return result
    }

    /**
     * Number of consecutive days, counting back from yesterday (today isn't over yet, so it's
     * excluded), with MISSED status — no plan generated OR nothing completed. This is what
     * lets the app actually notice "the student hasn't studied in N days" instead of that
     * information only existing implicitly as a bunch of empty SharedPrefs keys nobody reads.
     * Used by ProactiveMentor.consistencyCheckIfNeeded() to nudge the student and alert the
     * guardian once this crosses a threshold.
     */
    fun getConsecutiveMissedDays(): Int {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -1)
        var missed = 0
        for (i in 0 until 60) {
            if (getDayStatus(cal) != DayStudyStatus.MISSED) break
            missed++
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return missed
    }

    fun getWeekCompletionPercent(): Int {
        val cal = Calendar.getInstance(); var total = 0; var done = 0
        repeat(7) {
            val tasks = loadDay(keyForDay(cal))
            total += tasks.size; done += tasks.count { it.state == TaskState.DONE }
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return if (total == 0) 0 else done * 100 / total
    }

    fun getWeeklyData(): List<Pair<String, Int>> {
        val cal = Calendar.getInstance()
        val days = listOf("Mon","Tue","Wed","Thu","Fri","Sat","Sun")
        val result = mutableListOf<Pair<String, Int>>()
        repeat(7) { i ->
            val tasks = loadDay(keyForDay(cal))
            val pct = if (tasks.isEmpty()) 0 else tasks.count { it.state == TaskState.DONE } * 100 / tasks.size
            result.add(0, Pair(days[6 - i], pct))
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return result
    }

    fun getSubjectStats(): List<Pair<String, Int>> =
        _todayTasks.value.groupBy { it.subject }.map { (subj, list) ->
            Pair(subj, if (list.isEmpty()) 0 else list.count { it.state == TaskState.DONE } * 100 / list.size)
        }

    /** Blueprint 2.6 — see PauseStats doc above. */
    fun getPauseStats(): PauseStats {
        val actualFocusToday = _todayTasks.value
            .filter { it.state == TaskState.DONE }
            .sumOf { it.actualMinutes }

        val cal = Calendar.getInstance()
        val weekSessions = mutableListOf<StudyTask>()
        repeat(7) {
            weekSessions += loadDay(keyForDay(cal)).filter { it.state == TaskState.DONE || it.state == TaskState.SKIPPED }
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }

        if (weekSessions.isEmpty()) return PauseStats(actualFocusMinutesToday = actualFocusToday)

        val avgPauses  = weekSessions.sumOf { it.pauseCount }.toFloat() / weekSessions.size
        val pauseRate  = weekSessions.count { it.pauseCount > 0 } * 100 / weekSessions.size

        return PauseStats(
            actualFocusMinutesToday = actualFocusToday,
            avgPausesPerSession     = avgPauses,
            pauseRatePercent        = pauseRate
        )
    }

    /**
     * Gap-task escalation pass: was `private`, now exposed. [GapTaskManager] (app layer)
     * needs to read a SPECIFIC past day's plan to check whether an autonomously-created
     * gap-repair task ever reached DONE — [todayTasks] only ever holds the current day's
     * list, so there was no way to answer "what happened to the task from 3 days ago"
     * without this. Behavior is unchanged for every existing caller in this file.
     */
    fun loadDay(key: String): List<StudyTask> {
        val s = CheckmatePrefs.getString("plan_$key", null) ?: return emptyList()
        return try { json.decodeFromString(s) } catch (_: Exception) { emptyList() }
    }

    private fun todayKey() = keyForDay(Calendar.getInstance())

    /**
     * Gap-task escalation pass: was `private`, now exposed alongside [loadDay] — same
     * caller, same reason: [GapTaskManager] needs to compute the exact day-key for "N days
     * ago" itself so it can pair it with [loadDay].
     */
    fun keyForDay(cal: Calendar) =
        "${cal.get(Calendar.YEAR)}_${cal.get(Calendar.DAY_OF_YEAR)}"
}
