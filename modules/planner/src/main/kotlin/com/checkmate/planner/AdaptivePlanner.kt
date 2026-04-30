package com.checkmate.planner

import android.content.Context
import android.util.Log
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.ConsultationProfile
import com.checkmate.core.ConsultationProfile.Companion.toPromptContext
import com.checkmate.core.CoachingPlannerEntry
import com.checkmate.core.DailyCheckIn
import com.checkmate.core.PYQWeightage
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

        val llmPlan = tryLlmPlan(config, daysLeft, behaviorSummary, studyWindowHours, profile, checkIn, coachingContext, pyqContext)
        if (llmPlan.isNotEmpty()) return llmPlan

        return ruleBasedPlan(config, daysLeft, behaviorSummary, studyWindowHours)
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

    private suspend fun tryLlmPlan(
        config: PlannerState,
        daysLeft: Int,
        behaviorSummary: String,
        studyWindowHours: Float,
        profile: com.checkmate.core.ConsultationProfile,
        checkIn: DailyCheckIn?,
        coachingContext: String,
        pyqContext: String
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
