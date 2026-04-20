package com.checkmate.psyche

import com.checkmate.core.CheckmatePrefs
import com.checkmate.planner.model.StudyTask
import com.checkmate.planner.model.TaskState
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.Calendar

@Serializable
data class TaskEvent(
    val subject:      String,
    val topic:        String,
    val state:        String,   // DONE / SKIPPED
    val timestamp:    Long,
    val focusMinutes: Int = 0,
    val checksPassed: Int = 0,
    val checksMissed: Int = 0
)

data class AttentionStats(
    val checksPassed:    Int,
    val checksMissed:    Int,
    val avgFocusMinutes: Int
)

object BehaviorLedger {

    private val json = Json { ignoreUnknownKeys = true }
    private const val KEY_EVENTS = "behavior_events"
    private const val MAX_EVENTS = 200

    fun record(task: StudyTask, state: TaskState, checksPassed: Int = 0, checksMissed: Int = 0) {
        val events = getEvents().toMutableList()
        events.add(TaskEvent(
            subject      = task.subject,
            topic        = task.topic,
            state        = state.name,
            timestamp    = System.currentTimeMillis(),
            focusMinutes = task.focusMinutes,
            checksPassed = checksPassed,
            checksMissed = checksMissed
        ))
        val trimmed = events.takeLast(MAX_EVENTS)
        CheckmatePrefs.putString(KEY_EVENTS, json.encodeToString(trimmed))
    }

    fun getSkipCountForSubject(subject: String, withinDays: Int = 7): Int {
        val cutoff = System.currentTimeMillis() - withinDays * 24 * 60 * 60 * 1000L
        return getEvents().count { it.subject == subject && it.state == "SKIPPED" && it.timestamp > cutoff }
    }

    fun getTotalSkipCount(withinDays: Int = 7): Int {
        val cutoff = System.currentTimeMillis() - withinDays * 24 * 60 * 60 * 1000L
        return getEvents().count { it.state == "SKIPPED" && it.timestamp > cutoff }
    }

    fun getRecentSkipRate(): Float {
        val recent = getEvents().takeLast(20)
        if (recent.isEmpty()) return 0f
        return recent.count { it.state == "SKIPPED" }.toFloat() / recent.size
    }

    fun getStreakDays(): Int {
        val events = getEvents().sortedByDescending { it.timestamp }
        var streak = 0
        val cal    = Calendar.getInstance()
        for (i in 0..30) {
            val dayStart = cal.clone() as Calendar
            dayStart.set(Calendar.HOUR_OF_DAY, 0)
            dayStart.set(Calendar.MINUTE, 0)
            dayStart.set(Calendar.SECOND, 0)
            val dayEnd = cal.clone() as Calendar
            dayEnd.set(Calendar.HOUR_OF_DAY, 23)
            dayEnd.set(Calendar.MINUTE, 59)

            val dayEvents = events.filter { it.timestamp in dayStart.timeInMillis..dayEnd.timeInMillis }
            if (dayEvents.isEmpty() && i > 0) break
            if (dayEvents.any { it.state == "DONE" }) streak++
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return streak
    }

    fun getSummaryForPlanner(): String {
        val skipRate = getRecentSkipRate()
        val streak   = getStreakDays()
        return "Streak: ${streak}d, Recent skip rate: ${(skipRate * 100).toInt()}%, Total skips (7d): ${getTotalSkipCount()}"
    }

    fun getAttentionStats(): AttentionStats {
        val events = getEvents().filter { it.checksPassed + it.checksMissed > 0 }
        val passed = events.sumOf { it.checksPassed }
        val missed = events.sumOf { it.checksMissed }
        val avgFocus = if (events.isEmpty()) 0 else events.sumOf { it.focusMinutes } / events.size
        return AttentionStats(passed, missed, avgFocus)
    }

    private fun getEvents(): List<TaskEvent> {
        val saved = CheckmatePrefs.getString(KEY_EVENTS, null) ?: return emptyList()
        return try { json.decodeFromString(saved) } catch (_: Exception) { emptyList() }
    }
}
