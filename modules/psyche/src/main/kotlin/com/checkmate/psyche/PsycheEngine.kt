package com.checkmate.psyche

import android.util.Log
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.CoachingPlannerEntry
import com.checkmate.core.llm.LlmGateway
import com.checkmate.planner.model.StudyTask
import com.checkmate.planner.model.TaskState
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

// FEATURE: Weekly Report Delta — snapshots each week's numbers so the next
// report can show trend direction (e.g. "Consistency dropped from Good to
// Moderate") instead of an isolated snapshot. First week has no baseline
// and falls back to the original non-delta format.
@Serializable
data class WeeklySnapshot(
    val skipTotal:    Int    = 0,
    val streak:       Int    = 0,
    val checksPassed: Int    = 0,
    val checksMissed: Int    = 0,
    val consistency:  String = "",
    val timestamp:    Long   = 0L
)

object PsycheEngine {

    private const val TAG = "PsycheEngine"
    private const val KEY_LAST_SNAPSHOT = "psyche_last_week_snapshot"
    private val json = Json { ignoreUnknownKeys = true }

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

    // BUGFIX: previously these two took no checksPassed/checksMissed args and
    // always recorded 0/0 into BehaviorLedger, so attention-check counts never
    // reached the dashboard or the weekly guardian report even though
    // AttentionCycleManager was tracking them correctly during the session.
    // Callers (HomeViewModel.markDone/markSkip) now read
    // AttentionCycleManager.currentState() BEFORE stopping the service and
    // pass the real counts through here.
    fun onTaskCompleted(task: StudyTask, checksPassed: Int = 0, checksMissed: Int = 0) {
        BehaviorLedger.record(task, TaskState.DONE, checksPassed, checksMissed)
    }

    fun onTaskSkipped(task: StudyTask, checksPassed: Int = 0, checksMissed: Int = 0) {
        BehaviorLedger.record(task, TaskState.SKIPPED, checksPassed, checksMissed)
    }

    // FEATURE: Coaching-Test Countdown — a skip 2 days before a coaching test
    // in that subject reads with real urgency instead of generic streak
    // language. Deliberately conservative: only fires within COACHING_TEST_URGENT_DAYS,
    // only for type=="test" (not lectures), and any lookup failure (bad date,
    // no entry, subject mismatch) falls straight through to the existing
    // skip-count logic unchanged — a stale or empty coaching calendar can
    // only under-trigger this, never fabricate false urgency.
    private const val COACHING_TEST_URGENT_DAYS = 3

    fun getSkipReaction(task: StudyTask): String {
        val skipCount = BehaviorLedger.getSkipCountForSubject(task.subject, withinDays = 7)

        val upcomingTest = try {
            CoachingPlannerEntry.nearestUpcomingTest(task.subject, withinDays = COACHING_TEST_URGENT_DAYS)
        } catch (_: Exception) { null }

        val urgencyClause = upcomingTest?.let { test ->
            when (test.daysAway) {
                0    -> " Your ${test.subject} test is today."
                1    -> " Your ${test.subject} test is tomorrow."
                else -> " Your ${test.subject} test is in ${test.daysAway} days."
            }
        } ?: ""

        return when {
            skipCount >= 3 -> "Third ${task.subject} skip this week. Guardian will be notified.$urgencyClause"
            skipCount == 2 -> "Second skip on ${task.subject}. Tomorrow's load is reduced but this delay costs you.$urgencyClause"
            else           -> "You skipped ${task.subject}. Stay consistent.$urgencyClause"
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

        // ── Load last week's snapshot (if any) before we overwrite it ──────────
        val previous = loadLastSnapshot()
        val deltaBlock = buildDeltaBlock(previous, consistency, attnStats, skipTotal)

        val ruleReport = """
*Checkmate Weekly Report*

Streak: ${streak} days
Tasks missed this week: $skipTotal
Consistency: $consistency
Attention checks passed: ${attnStats.checksPassed}
Attention checks missed: ${attnStats.checksMissed}
Avg focus per session: ${attnStats.avgFocusMinutes} min
${deltaBlock}
${if (skipRate > 0.4f) "⚠ Needs better discipline. Evening sessions are being skipped frequently." else "Performance is on track."}
""".trimIndent()

        val report = try {
            val llm = LlmGateway.complete(
                "Generate a short parent WhatsApp report. Data: $ruleReport",
                "You write brief, factual parent reports about student study performance. 5-6 lines max. No fluff. " +
                "If a WEEK-OVER-WEEK CHANGE line is present in the data, include that trend — it matters more to the parent than the raw snapshot."
            )
            if (llm.isBlank()) ruleReport else llm
        } catch (_: Exception) { ruleReport }

        // ── Persist this week's numbers as next week's baseline ────────────────
        saveSnapshot(WeeklySnapshot(
            skipTotal    = skipTotal,
            streak       = streak,
            checksPassed = attnStats.checksPassed,
            checksMissed = attnStats.checksMissed,
            consistency  = consistency,
            timestamp    = System.currentTimeMillis()
        ))

        return report
    }

    // ── Delta helpers ────────────────────────────────────────────────────────

    private fun buildDeltaBlock(
        previous: WeeklySnapshot?,
        consistency: String,
        attnStats: AttentionStats,
        skipTotal: Int
    ): String {
        if (previous == null) return "" // first week — nothing to diff against yet

        val consistencyLine = if (previous.consistency.isNotBlank() && previous.consistency != consistency) {
            "Consistency ${trendWord(previous.consistency, consistency)} from ${previous.consistency} to $consistency."
        } else null

        val missedDelta = attnStats.checksMissed - previous.checksMissed
        val missedLine = when {
            missedDelta > 0 -> "Focus checks missed rose from ${previous.checksMissed} to ${attnStats.checksMissed}."
            missedDelta < 0 -> "Focus checks missed fell from ${previous.checksMissed} to ${attnStats.checksMissed}."
            else            -> null
        }

        val skipDelta = skipTotal - previous.skipTotal
        val skipLine = when {
            skipDelta > 0 -> "Tasks missed rose from ${previous.skipTotal} to $skipTotal this week."
            skipDelta < 0 -> "Tasks missed dropped from ${previous.skipTotal} to $skipTotal this week."
            else          -> null
        }

        val lines = listOfNotNull(consistencyLine, missedLine, skipLine)
        if (lines.isEmpty()) return ""

        return "\nWEEK-OVER-WEEK CHANGE:\n" + lines.joinToString("\n") { "• $it" } + "\n"
    }

    /** Simple ordinal comparison so we say "improved" vs "dropped" correctly. */
    private fun trendWord(prev: String, current: String): String {
        val order = listOf("Poor", "Moderate", "Good", "Excellent")
        val prevIdx = order.indexOf(prev)
        val currIdx = order.indexOf(current)
        if (prevIdx < 0 || currIdx < 0) return "changed"
        return if (currIdx > prevIdx) "improved" else "dropped"
    }

    private fun loadLastSnapshot(): WeeklySnapshot? {
        val raw = CheckmatePrefs.getString(KEY_LAST_SNAPSHOT, null) ?: return null
        return try { json.decodeFromString(raw) } catch (_: Exception) { null }
    }

    private fun saveSnapshot(snapshot: WeeklySnapshot) {
        CheckmatePrefs.putString(KEY_LAST_SNAPSHOT, json.encodeToString(snapshot))
    }
}
