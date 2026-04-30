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

    fun setTaskActive(taskId: String) = updateTask(taskId) { it.copy(state = TaskState.ACTIVE) }

    fun markTask(taskId: String, state: TaskState) = updateTask(taskId) {
        it.copy(state = state, completedAt = if (state == TaskState.DONE) System.currentTimeMillis() else null)
    }

    fun pauseTask(taskId: String, pausedAt: Long) = updateTask(taskId) {
        it.copy(state = TaskState.PAUSED, pausedAt = pausedAt)
    }

    fun resumeTask(taskId: String, resumedAt: Long) = updateTask(taskId) { task ->
        val elapsed = if (task.pausedAt != null && task.pausedAt > 0L) resumedAt - task.pausedAt else 0L
        task.copy(state = TaskState.ACTIVE, pausedAt = null, totalPausedMs = task.totalPausedMs + elapsed)
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

    private fun loadDay(key: String): List<StudyTask> {
        val s = CheckmatePrefs.getString("plan_$key", null) ?: return emptyList()
        return try { json.decodeFromString(s) } catch (_: Exception) { emptyList() }
    }

    private fun todayKey() = keyForDay(Calendar.getInstance())
    private fun keyForDay(cal: Calendar) =
        "${cal.get(Calendar.YEAR)}_${cal.get(Calendar.DAY_OF_YEAR)}"
}
