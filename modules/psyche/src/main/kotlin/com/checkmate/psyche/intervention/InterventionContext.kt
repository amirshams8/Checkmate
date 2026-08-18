package com.checkmate.psyche.intervention

import com.checkmate.planner.model.StudyTask
import com.checkmate.psyche.AttentionStats
import java.time.Instant
import java.time.ZoneId

/**
 * Proactive Execution Engine — Step 8 (Blueprint Part One, §7).
 *
 * "The AI should never receive a vague request like 'Motivate the student.'" This is the
 * structured reality snapshot instead. Fields are scoped to exactly what
 * BehaviorLedger/TodayContext can actually answer today — three of §7's own worked-example
 * fields are deliberately absent rather than faked:
 *
 *   - UPCOMING TEST — no exam-date source has been read this session. Candidate:
 *     ConsultationProfile, referenced by FreeSlotCalculator but not inspected.
 *   - AVAILABLE TIME — needs FreeSlotCalculator's remaining-free-time computation, not
 *     read this session.
 *   - RECENT DISTRACTION — BehaviorLedger.TaskEvent already stores distractionApp per
 *     skip event, but no public BehaviorLedger method exposes "most recent distraction
 *     app." One small additive getter away — not added here without being asked to
 *     change an existing production behavioral-tracking file.
 *
 * [toPromptText] simply omits each of those, rather than filling them with placeholder
 * text — see the "omission is correct" test in ContextBuilderTest.
 */
data class InterventionContext(
    val taskSubject: String,
    val taskTopic: String,
    val taskType: String,
    val scheduledStartTime: String?,
    val nowMillis: Long,
    val lateMinutes: Int,
    val subjectSkipCount7d: Int,
    val subjectSkipCountByType7d: Int,
    val recentSkipRatePercent: Int,
    val streakDays: Int,
    val todayCompletedSummary: String,
    val todayFreeTextUpdates: String,
    val attentionStats: AttentionStats
) {
    /** Prompt-ready text block for the AI Mentor (step 11, not yet built) to consume. */
    fun toPromptText(): String = buildString {
        appendLine("TASK: $taskSubject — $taskTopic ($taskType)")
        scheduledStartTime?.let { appendLine("SCHEDULED: $it") }
        appendLine("CURRENT TIME: ${formatTime(nowMillis)}")
        if (lateMinutes > 0) appendLine("LATE BY: $lateMinutes minutes")
        appendLine("RECENT SKIP RATE: $recentSkipRatePercent%")
        appendLine("$taskSubject SKIPPED (7d): $subjectSkipCount7d")
        if (subjectSkipCountByType7d > 0) {
            appendLine("$taskSubject $taskType SKIPPED (7d): $subjectSkipCountByType7d")
        }
        appendLine("STREAK: ${streakDays}d")
        if (todayCompletedSummary.isNotBlank()) {
            appendLine("TODAY COMPLETED:")
            appendLine(todayCompletedSummary)
        }
        if (todayFreeTextUpdates.isNotBlank()) {
            appendLine("TODAY UPDATES:")
            appendLine(todayFreeTextUpdates)
        }
        val totalChecks = attentionStats.checksPassed + attentionStats.checksMissed
        if (totalChecks > 0) {
            appendLine(
                "ATTENTION: ${attentionStats.checksPassed} passed / ${attentionStats.checksMissed} missed, " +
                    "avg focus ${attentionStats.avgFocusMinutes}m"
            )
        }
    }.trimEnd()

    private fun formatTime(millis: Long): String {
        val time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime()
        return "%02d:%02d".format(time.hour, time.minute)
    }
}

object ContextBuilder {
    /** withinDays window for both skip-count queries below — matches §7's own worked
     *  example ("RECENT SKIP RATE" framed as a short-term/weekly signal). */
    private const val SKIP_LOOKBACK_DAYS = 7

    fun build(
        task: StudyTask,
        lateMinutes: Int,
        now: Long = System.currentTimeMillis(),
        source: BehaviorContextSource = BehaviorLedgerContextSource()
    ): InterventionContext = InterventionContext(
        taskSubject = task.subject,
        taskTopic = task.topic,
        taskType = task.taskType.name,
        scheduledStartTime = task.scheduledStartTime,
        nowMillis = now,
        lateMinutes = lateMinutes,
        subjectSkipCount7d = source.getSkipCountForSubject(task.subject, SKIP_LOOKBACK_DAYS),
        subjectSkipCountByType7d = source.getSkipCountByType(task.subject, task.taskType.name, SKIP_LOOKBACK_DAYS),
        recentSkipRatePercent = source.getRecentSkipRatePercent(),
        streakDays = source.getStreakDays(),
        todayCompletedSummary = source.getTodayCompletedSummary(),
        todayFreeTextUpdates = source.getTodayFreeTextUpdates(),
        attentionStats = source.getAttentionStats()
    )
}
