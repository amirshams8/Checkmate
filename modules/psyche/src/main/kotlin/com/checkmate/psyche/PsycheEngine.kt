package com.checkmate.psyche

import android.util.Log
import com.checkmate.core.llm.LlmGateway
import com.checkmate.planner.model.StudyTask
import com.checkmate.planner.model.TaskState

object PsycheEngine {

    private const val TAG = "PsycheEngine"

    private val SYSTEM_PROMPT = """
You are a strict but adaptive study coach. Your job is to enforce behavior, not motivate with fluff.
Rules:
- Keep messages SHORT (1-2 sentences max)
- Be direct and consequence-based
- Never say "you can do it" or generic praise
- React to actual behavior patterns
- If skip: state the consequence
- If done well: acknowledge briefly and raise the bar
""".trimIndent()

    suspend fun getDailyMorningMessage(): String {
        val skipRate = BehaviorLedger.getRecentSkipRate()
        val streak   = BehaviorLedger.getStreakDays()

        // Rule-based first (fast, works offline)
        val ruleMsg = when {
            streak >= 7  -> "7-day streak. Maintain this — it's the only thing that matters now."
            streak >= 3  -> "Good consistency this week. Keep it."
            skipRate > 0.5f -> "You've been skipping more than completing. That needs to stop today."
            skipRate > 0.3f -> "Inconsistent last few days. Today has to be clean."
            else         -> "New day. Complete your tasks — nothing else counts."
        }

        return try {
            val llmMsg = LlmGateway.complete(
                "Behavior data: ${BehaviorLedger.getSummaryForPlanner()}. Write one short morning message.",
                SYSTEM_PROMPT
            )
            if (llmMsg.isBlank()) ruleMsg else llmMsg
        } catch (_: Exception) { ruleMsg }
    }

    fun onTaskCompleted(task: StudyTask) {
        BehaviorLedger.record(task, TaskState.DONE)
    }

    fun onTaskSkipped(task: StudyTask) {
        BehaviorLedger.record(task, TaskState.SKIPPED)
    }

    fun getSkipReaction(task: StudyTask): String {
        val skipCount = BehaviorLedger.getSkipCountForSubject(task.subject, withinDays = 7)
        return when {
            skipCount >= 3 -> "Third ${task.subject} skip this week. Guardian will be notified."
            skipCount == 2 -> "Second skip on ${task.subject}. Tomorrow's load is reduced but this delay costs you."
            else           -> "You skipped ${task.subject}. Stay consistent."
        }
    }

    suspend fun getGuardianWeeklyReport(): String {
        val skipTotal = BehaviorLedger.getTotalSkipCount(7)
        val streak    = BehaviorLedger.getStreakDays()
        val attnStats = BehaviorLedger.getAttentionStats()
        val skipRate  = BehaviorLedger.getRecentSkipRate()

        val consistency = when {
            skipRate < 0.1f -> "Excellent"
            skipRate < 0.3f -> "Good"
            skipRate < 0.5f -> "Moderate"
            else            -> "Poor"
        }

        val ruleReport = """
*Checkmate Weekly Report*

Streak: ${streak} days
Tasks missed this week: $skipTotal
Consistency: $consistency
Attention checks passed: ${attnStats.checksPassed}
Attention checks missed: ${attnStats.checksMissed}
Avg focus per session: ${attnStats.avgFocusMinutes} min

${if (skipRate > 0.4f) "⚠ Needs better discipline. Evening sessions are being skipped frequently." else "Performance is on track."}
""".trimIndent()

        return try {
            val llm = LlmGateway.complete(
                "Generate a short parent WhatsApp report. Data: $ruleReport",
                "You write brief, factual parent reports about student study performance. 5-6 lines max. No fluff."
            )
            if (llm.isBlank()) ruleReport else llm
        } catch (_: Exception) { ruleReport }
    }
}
