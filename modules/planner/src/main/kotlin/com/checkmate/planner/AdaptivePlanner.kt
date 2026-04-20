package com.checkmate.planner

import android.content.Context
import android.util.Log
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.llm.LlmGateway
import com.checkmate.planner.model.StudyTask
import com.checkmate.planner.model.SubjectConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

object AdaptivePlanner {

    private const val TAG = "AdaptivePlanner"

    suspend fun generateDailyPlan(context: Context, config: PlannerState): List<StudyTask> {
        val daysLeft         = daysUntilExam(config.examDate)
        val behaviorSummary  = getBehaviorSummary()
        val studyWindowHours = calculateStudyWindowHours(config.studyStartTime, config.studyEndTime)

        val llmPlan = tryLlmPlan(config, daysLeft, behaviorSummary, studyWindowHours)
        if (llmPlan.isNotEmpty()) return llmPlan

        return ruleBasedPlan(config, daysLeft, behaviorSummary, studyWindowHours)
    }

    /**
     * Pull behavior summary from prefs directly to avoid depending on modules:psyche.
     * PsycheEngine/BehaviorLedger write their summary into CheckmatePrefs under "behavior_summary".
     */
    private fun getBehaviorSummary(): String =
        CheckmatePrefs.getString("behavior_summary", "No behavior data yet") ?: "No behavior data yet"

    private suspend fun tryLlmPlan(
        config: PlannerState,
        daysLeft: Int,
        behaviorSummary: String,
        studyWindowHours: Float
    ): List<StudyTask> {
        val systemPrompt = """
You are an adaptive study planner for competitive exam students.
Generate a focused daily study plan. Respond ONLY with a valid JSON array, no markdown, no explanation.
Format: [{"subject":"Biology","topic":"Cell Division","durationMinutes":45},...]
Rules:
- Max 5 tasks
- Total time must fit in ${studyWindowHours.toInt()} hours (minus breaks)
- Weight tasks by subject priority
- If exam < 30 days: revision-heavy
- If exam < 7 days: full revision only
- Keep durations in multiples of 30
""".trimIndent()

        val prompt = """
Exam: ${config.examType}
Days until exam: $daysLeft
Subjects (name:weight): ${config.subjects.joinToString { "${it.name}:${it.weightage}" }}
Study window: ${config.studyStartTime}–${config.studyEndTime} (${studyWindowHours}h)
Behavior: $behaviorSummary
Generate today's plan.
""".trimIndent()

        return try {
            val raw = LlmGateway.complete(prompt, systemPrompt)
            if (raw.isBlank()) return emptyList()
            val json  = raw.trim().removePrefix("```json").removeSuffix("```").trim()
            val arr   = JSONArray(json)
            val tasks = mutableListOf<StudyTask>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                tasks.add(StudyTask(
                    subject         = obj.getString("subject"),
                    topic           = obj.getString("topic"),
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

        // Adapt duration from stored skip rate
        val skipRateStr = CheckmatePrefs.getString("recent_skip_rate", "0") ?: "0"
        val skipRate    = skipRateStr.toFloatOrNull() ?: 0f
        val duration    = when {
            skipRate > 0.5f -> (baseDuration - 15).coerceAtLeast(20)
            skipRate < 0.1f -> (baseDuration + 15).coerceAtMost(60)
            else            -> baseDuration
        }

        val topicMap = mapOf(
            "Biology"   to listOf("Cell Biology", "Genetics", "Human Physiology", "Plant Physiology", "Ecology", "Evolution"),
            "Chemistry" to listOf("Physical Chemistry", "Organic Chemistry", "Inorganic Chemistry", "Equilibrium", "Thermodynamics"),
            "Physics"   to listOf("Mechanics", "Thermodynamics", "Optics", "Electromagnetism", "Modern Physics"),
            "Math"      to listOf("Algebra", "Calculus", "Trigonometry", "Probability", "Geometry"),
            "Maths"     to listOf("Algebra", "Calculus", "Trigonometry", "Probability", "Geometry")
        )

        val dayOfYear = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        sorted.take(maxTasks).forEachIndexed { idx: Int, subj: SubjectConfig ->
            val topics = topicMap[subj.name] ?: listOf("Chapter ${(dayOfYear + idx) % 10 + 1}")
            val topic  = if (daysLeft < 30) "Revision: ${topics[(dayOfYear + idx) % topics.size]}"
                         else topics[(dayOfYear + idx) % topics.size]
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
