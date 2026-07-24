package com.checkmate.planner

import android.content.Context
import android.util.Log
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.ConsultationProfile
import com.checkmate.core.ConsultationProfile.Companion.toPromptContext
import com.checkmate.core.CoachingPlannerEntry
import com.checkmate.core.DailyCheckIn
import com.checkmate.core.DailyChecklist
import com.checkmate.core.PYQWeightage
import com.checkmate.core.TodayContext
import com.checkmate.core.llm.LlmGateway
import com.checkmate.planner.model.StudyTask
import com.checkmate.planner.model.SubjectConfig
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

object AdaptivePlanner {

    private const val TAG = "AdaptivePlanner"

    suspend fun generateDailyPlan(context: Context, config: PlannerState): List<StudyTask> {
        val daysLeft          = daysUntilExam(config.examDate)
        val behaviorSummary   = getBehaviorSummary()
        val studyWindowHours  = calculateStudyWindowHours(config.studyStartTime, config.studyEndTime)
        val profile           = ConsultationProfile.load()
        val checkIn           = DailyCheckIn.loadToday()
        val coachingContext   = CoachingPlannerEntry.upcomingContext(7)
        val pyqContext         = buildPyqContext(config.examType, checkIn)
        // FEATURE: Checklist → Planner — same-day lecture/DPP/notes completion
        // feeds tomorrow's plan so an incomplete checklist skews the next plan
        // toward catching up on fundamentals rather than piling on new PYQ topics.
        val checklistContext  = buildChecklistContext()
        // Mentor v2 (spec 3.7): read TodayContext directly (not only via the cached
        // behavior_summary string) so a mid-day regenerate always reflects the latest
        // same-day free-text updates even if refreshBehaviorSummaryCache() hasn't run
        // since the last one (e.g. the very first plan of the day).
        val todayContext      = TodayContext.getSummaryText()

        // Blueprint 4.1: today's free windows (study window minus school/coaching
        // blocked slots). Computed once, applied to whichever plan comes out below.
        val freeSlots = FreeSlotCalculator.computeFreeSlots(
            profile.blockedSlots, config.studyStartTime, config.studyEndTime
        )

        val llmPlan = tryLlmPlan(config, daysLeft, behaviorSummary, studyWindowHours, profile, checkIn, coachingContext, pyqContext, checklistContext, todayContext)
        if (llmPlan.isNotEmpty()) return assignScheduledTimes(llmPlan, freeSlots)

        return assignScheduledTimes(ruleBasedPlan(config, daysLeft, behaviorSummary, studyWindowHours), freeSlots)
    }

    /**
     * Blueprint 4.2: packs tasks sequentially into today's free slots in the
     * order they were generated — which is already priority order (highest
     * subject weightage / PYQ-driven reason first, from both the LLM and
     * rule-based paths) — so the hardest/highest-priority work naturally lands
     * in the earliest slots rather than needing a second re-sort here.
     *
     * A task that doesn't fit in the slot it's currently pointed at moves the
     * cursor to the next free slot; a task that doesn't fit anywhere (total
     * plan exceeds available free time) is returned with scheduledStartTime
     * left null — HomeScreen's timeline view groups those under "Unscheduled"
     * instead of silently dropping them.
     */
    private fun assignScheduledTimes(
        tasks: List<StudyTask>,
        freeSlots: List<FreeSlotCalculator.FreeSlot>
    ): List<StudyTask> {
        if (tasks.isEmpty() || freeSlots.isEmpty()) return tasks

        var slotIndex = 0
        var cursor = freeSlots[0].startMinute

        return tasks.map { task ->
            while (slotIndex < freeSlots.size && cursor + task.durationMinutes > freeSlots[slotIndex].endMinute) {
                slotIndex++
                cursor = if (slotIndex < freeSlots.size) freeSlots[slotIndex].startMinute else -1
            }
            if (slotIndex >= freeSlots.size || cursor < 0) {
                task
            } else {
                val scheduled = task.copy(scheduledStartTime = FreeSlotCalculator.formatMinutes(cursor))
                cursor += task.durationMinutes
                scheduled
            }
        }
    }

    private fun getBehaviorSummary(): String =
        CheckmatePrefs.getString("behavior_summary", "No behavior data yet") ?: "No behavior data yet"

    private fun buildPyqContext(exam: String, checkIn: DailyCheckIn?): String {
        if (checkIn == null) return ""
        return checkIn.todayTopics.entries.mapNotNull { (subject, topic) ->
            val weight = PYQWeightage.findTopicWeightage(exam, topic)
            if (weight > 0f) "$subject/$topic: PYQ weight ${String.format("%.1f", weight)}%"
            else null
        }.joinToString("\n")
    }

    /**
     * Reads today's checklist completion (lecture/notes/DPP/etc.) so the LLM
     * planner can react to same-day execution gaps, not just multi-day behavior
     * trends. Returns "" when the checklist was never touched today — an
     * untouched checklist means "not used," not "0% complete," and treating it
     * as a crisis would produce false catch-up plans. Mirrors the same
     * empty-guard pattern used in buildPyqContext().
     */
    private fun buildChecklistContext(): String {
        val summary = DailyChecklist.getTodaySummaryText()
        if (summary.isBlank()) return ""

        val items = DailyChecklist.getTodayItems()
        if (items.isEmpty()) return ""

        val touched = items.any { it.isDone }
        // Checklist exists but nothing has been checked off yet today (e.g. it's
        // early morning) — not a signal of falling behind, so skip it.
        if (!touched) return ""

        val doneCount = items.count { it.isDone }
        val totalCount = items.size
        val incomplete = items.filter { !it.isDone }.map { it.label }

        return buildString {
            appendLine("Today's checklist: $doneCount/$totalCount complete")
            if (incomplete.isNotEmpty()) {
                appendLine("Not done yet: ${incomplete.joinToString()}")
            }
        }.trim()
    }

    private suspend fun tryLlmPlan(
        config: PlannerState,
        daysLeft: Int,
        behaviorSummary: String,
        studyWindowHours: Float,
        profile: com.checkmate.core.ConsultationProfile,
        checkIn: DailyCheckIn?,
        coachingContext: String,
        pyqContext: String,
        checklistContext: String,
        todayContext: String = ""
    ): List<StudyTask> {
        val systemPrompt = """
You are an adaptive study planner for competitive exam students.
Generate a focused daily study plan. Respond ONLY with a valid JSON array, no markdown, no explanation.
Format: [{"subject":"Biology","topic":"Cell Division","subtopic":"Mitosis stages","durationMinutes":45,"sessionType":"LEARN","priority":"HIGH","reason":"PYQ weight 9%, marked weak"}]
Rules:
- Max 5 tasks
- Total time must fit in ${studyWindowHours.toInt()} hours (minus breaks)
- Weight tasks by subject priority and PYQ weightage
- If exam < 30 days: revision-heavy
- If exam < 7 days: full revision only
- Keep durations in multiples of 30
- sessionType must be one of: LEARN, REVISE, PRACTICE, TEST_PREP
- reason must be specific: mention PYQ %, coaching test dates, weak topic flags
- If TODAY'S CHECKLIST STATUS shows incomplete fundamentals (lecture notes, DPP, NCERT reading)
  from earlier today, prioritize catching those up over introducing new topics — an unfinished
  checklist means the student is behind on today's baseline, not ready for additional load
- If TODAY'S LOGGED UPDATES mentions something already covered today (e.g. "back from coaching,
  did Physics 2hrs"), do not re-assign that same subject/topic — build on it or move to the next
  weak area instead
""".trimIndent()

        val prompt = buildString {
            appendLine("Exam: ${config.examType} | Days until exam: $daysLeft")
            appendLine("Subjects (name:weight): ${config.subjects.joinToString { "${it.name}:${it.weightage}" }}")
            appendLine("Study window: ${config.studyStartTime}–${config.studyEndTime} (${studyWindowHours}h)")
            appendLine()
            appendLine("STUDENT PROFILE:")
            appendLine(profile.toPromptContext())
            appendLine()
            if (checkIn != null) {
                appendLine("TODAY'S TOPICS SELECTED:")
                checkIn.todayTopics.forEach { (subj, topic) -> appendLine("  $subj → $topic") }
                appendLine()
            }
            if (coachingContext.isNotBlank()) {
                appendLine("UPCOMING COACHING SCHEDULE:")
                appendLine(coachingContext)
                appendLine()
            }
            if (pyqContext.isNotBlank()) {
                appendLine("PYQ WEIGHTAGE FOR TODAY'S TOPICS:")
                appendLine(pyqContext)
                appendLine()
            }
            if (checklistContext.isNotBlank()) {
                appendLine("TODAY'S CHECKLIST STATUS:")
                appendLine(checklistContext)
                appendLine()
            }
            if (todayContext.isNotBlank()) {
                appendLine("TODAY'S LOGGED UPDATES (from Mentor chat / quick-log):")
                appendLine(todayContext)
                appendLine()
            }
            appendLine("BEHAVIOR DATA: $behaviorSummary")
            appendLine()
            appendLine("Generate today's plan.")
        }

        return try {
            val raw = LlmGateway.complete(prompt, systemPrompt)
            if (raw.isBlank()) return emptyList()
            val json  = raw.trim().removePrefix("```json").removeSuffix("```").trim()
            val arr   = JSONArray(json)
            val tasks = mutableListOf<StudyTask>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val subtopic = if (obj.has("subtopic")) " — ${obj.getString("subtopic")}" else ""
                tasks.add(StudyTask(
                    subject         = obj.getString("subject"),
                    topic           = obj.getString("topic") + subtopic,
                    durationMinutes = obj.getInt("durationMinutes")
                ))
            }
            Log.d(TAG, "LLM generated ${tasks.size} tasks")
            tasks
        } catch (e: Exception) {
            Log.w(TAG, "LLM plan failed: ${e.message}")
            emptyList()
        }
    }

    private fun ruleBasedPlan(
        config: PlannerState,
        daysLeft: Int,
        behaviorSummary: String,
        studyWindowHours: Float
    ): List<StudyTask> {
        val tasks        = mutableListOf<StudyTask>()
        val sorted       = config.subjects.sortedByDescending { it.weightage }
        val maxTasks     = if (daysLeft < 7) 5 else if (daysLeft < 30) 4 else 3
        val baseDuration = if (daysLeft < 30) 30 else 45

        val skipRateStr = CheckmatePrefs.getString("recent_skip_rate", "0") ?: "0"
        val skipRate    = skipRateStr.toFloatOrNull() ?: 0f
        val duration    = when {
            skipRate > 0.5f -> (baseDuration - 15).coerceAtLeast(20)
            skipRate < 0.1f -> (baseDuration + 15).coerceAtMost(60)
            else            -> baseDuration
        }

        // Use PYQ weightage to pick top topics per subject
        val dayOfYear = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        sorted.take(maxTasks).forEachIndexed { idx: Int, subj: SubjectConfig ->
            val topTopics = PYQWeightage.getTopTopics(config.examType, subj.name, 6)
            val topic = if (topTopics.isNotEmpty()) {
                val t = topTopics[(dayOfYear + idx) % topTopics.size]
                if (daysLeft < 30) "Revision: ${t.first}" else t.first
            } else {
                if (daysLeft < 30) "Revision: ${subj.name} Chapter ${(dayOfYear + idx) % 10 + 1}"
                else "${subj.name} Chapter ${(dayOfYear + idx) % 10 + 1}"
            }
            tasks.add(StudyTask(
                subject         = subj.name,
                topic           = topic,
                durationMinutes = if (idx == 0) duration + 15 else duration,
                priority        = sorted.size - idx
            ))
        }
        return tasks
    }

    private fun daysUntilExam(dateStr: String): Int {
        return try {
            val sdf  = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            val exam = sdf.parse(dateStr) ?: return 365
            val diff = exam.time - System.currentTimeMillis()
            (diff / (1000 * 60 * 60 * 24)).toInt().coerceAtLeast(0)
        } catch (_: Exception) { 365 }
    }

    private fun calculateStudyWindowHours(start: String, end: String): Float {
        return try {
            val (sh, sm) = start.split(":").map { it.toInt() }
            val (eh, em) = end.split(":").map { it.toInt() }
            val startMin = sh * 60 + sm
            val endMin   = eh * 60 + em
            ((endMin - startMin) / 60f).coerceAtLeast(1f)
        } catch (_: Exception) { 8f }
    }
}
