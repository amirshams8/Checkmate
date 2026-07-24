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

    fun saveTodayTasks(tasks: List<StudyTask>) {
        CheckmatePrefs.putString("plan_${todayKey()}", json.encodeToString(tasks))
        _todayTasks.value = tasks
    }

    /**
     * Appends a single manually-created task to today's plan WITHOUT touching any
     * existing tasks (generated or custom). Unlike saveTodayTasks(), which replaces
     * the whole list, this is additive — safe to call any time, any number of times.
     * The new task goes through the exact same StudyTask model as AdaptivePlanner
     * output, so it picks up Start/Pause/Done/Skip, AttentionCycleService, and
     * GuardianNotifier WhatsApp + Telegram reporting automatically — no separate
     * code path needed anywhere downstream.
     */
    fun addCustomTask(task: StudyTask) {
        val updated = _todayTasks.value + task
        _todayTasks.value = updated
        CheckmatePrefs.putString("plan_${todayKey()}", json.encodeToString(updated))
    }

    /** Removes a single task by id (used to let a student delete a custom task they added by mistake). */
    fun removeTask(taskId: String) {
        val updated = _todayTasks.value.filterNot { it.id == taskId }
        _todayTasks.value = updated
        CheckmatePrefs.putString("plan_${todayKey()}", json.encodeToString(updated))
    }

    /** Edits the planned duration of a single task — used for custom-task duration editing. */
    fun updateTaskDuration(taskId: String, durationMinutes: Int) = updateTask(taskId) {
        it.copy(durationMinutes = durationMinutes)
    }

    fun setTaskActive(taskId: String) = updateTask(taskId) { it.copy(state = TaskState.ACTIVE) }

    fun markTask(taskId: String, state: TaskState) = updateTask(taskId) {
        it.copy(state = state, completedAt = if (state == TaskState.DONE) System.currentTimeMillis() else null)
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
        val updated = _todayTasks.value.map { if (it.id == taskId) block(it) else it }
        _todayTasks.value = updated
        CheckmatePrefs.putString("plan_${todayKey()}", json.encodeToString(updated))
    }

    suspend fun getTodayTasksSnapshot(): List<StudyTask> = _todayTasks.value

    // Synchronous version for BroadcastReceiver context (no coroutine scope available)
    fun getTodayTasksSnapshot_Sync(): List<StudyTask> = _todayTasks.value

    fun getTodayCompletionPercent(): Int {
        val t = _todayTasks.value
        return if (t.isEmpty()) 0 else t.count { it.state == TaskState.DONE } * 100 / t.size
    }

    fun getStreakDays(): Int {
        var streak = 0
        val cal = Calendar.getInstance()
        for (i in 0..30) {
            val saved = CheckmatePrefs.getString("plan_${keyForDay(cal)}", null) ?: break
            val tasks = try { json.decodeFromString<List<StudyTask>>(saved) } catch (_: Exception) { break }
            if (tasks.count { it.state == TaskState.DONE } == 0 && tasks.isNotEmpty()) break
            streak++
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return streak
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

    private fun loadDay(key: String): List<StudyTask> {
        val s = CheckmatePrefs.getString("plan_$key", null) ?: return emptyList()
        return try { json.decodeFromString(s) } catch (_: Exception) { emptyList() }
    }

    private fun todayKey() = keyForDay(Calendar.getInstance())
    private fun keyForDay(cal: Calendar) =
        "${cal.get(Calendar.YEAR)}_${cal.get(Calendar.DAY_OF_YEAR)}"
}
