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
    val subject:         String,
    val topic:           String,
    val state:           String,   // DONE / SKIPPED
    val timestamp:       Long,
    val focusMinutes:    Int = 0,
    val checksPassed:    Int = 0,
    val checksMissed:    Int = 0,
    // Mentor v2 (spec 2.2):
    val taskType:        String  = "OTHER", // mirrors StudyTask.TaskType.name — lets skip patterns be
                                             // queried per task-type (e.g. "PRACTICE skips in Physics")
                                             // rather than only aggregate skip rate.
    val distractionApp:  String? = null     // label of the app foregrounded right after this skip, if any.
                                             // Populated by the caller (HomeViewModel.markSkip) so guardian
                                             // alerts can say what caused the skip, not just that it happened.
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

    // distractionApp is optional and only meaningful for SKIPPED events — callers recording a
    // DONE task can omit it. taskType defaults to task.taskType.name so existing call sites
    // (PsycheEngine.onTaskCompleted/onTaskSkipped) don't need to change at all.
    fun record(
        task: StudyTask,
        state: TaskState,
        checksPassed: Int = 0,
        checksMissed: Int = 0,
        distractionApp: String? = null
    ) {
        val events = getEvents().toMutableList()
        events.add(TaskEvent(
            subject         = task.subject,
            topic           = task.topic,
            state           = state.name,
            timestamp       = System.currentTimeMillis(),
            focusMinutes    = task.focusMinutes,
            checksPassed    = checksPassed,
            checksMissed    = checksMissed,
            taskType        = task.taskType.name,
            distractionApp  = distractionApp
        ))
        val trimmed = events.takeLast(MAX_EVENTS)
        CheckmatePrefs.putString(KEY_EVENTS, json.encodeToString(trimmed))
    }

    fun getSkipCountForSubject(subject: String, withinDays: Int = 7): Int {
        val cutoff = System.currentTimeMillis() - withinDays * 24 * 60 * 60 * 1000L
        return getEvents().count { it.subject == subject && it.state == "SKIPPED" && it.timestamp > cutoff }
    }

    /**
     * Mentor v2 (spec 2.2): skip count scoped to a specific task type within a subject —
     * e.g. getSkipCountByType("Physics", "PRACTICE", 7) answers "how many times has the
     * student skipped Physics *practice* tasks specifically this week," which the aggregate
     * getSkipCountForSubject() can't distinguish from skipped lectures/revision.
     */
    fun getSkipCountByType(subject: String, taskType: String, withinDays: Int = 7): Int {
        val cutoff = System.currentTimeMillis() - withinDays * 24 * 60 * 60 * 1000L
        return getEvents().count {
            it.subject == subject && it.taskType == taskType && it.state == "SKIPPED" && it.timestamp > cutoff
        }
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

    /**
     * Mentor v2 (spec 3.7): same-day DONE tasks, most recent first — used by AdaptivePlanner
     * to know what's already been covered today (e.g. subject/topic pairs finished before the
     * student got back from coaching), separate from the 7-day aggregate stats above.
     */
    fun getTodayCompletedSummary(): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = cal.timeInMillis
        val todayDone = getEvents()
            .filter { it.state == "DONE" && it.timestamp >= startOfDay }
            .sortedByDescending { it.timestamp }
        if (todayDone.isEmpty()) return ""
        return todayDone.joinToString("\n") { "  ${it.subject}: ${it.topic} (${it.taskType})" }
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
