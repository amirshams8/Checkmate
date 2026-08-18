package com.checkmate.psyche.intervention

import com.checkmate.psyche.AttentionStats

/**
 * Proactive Execution Engine — Step 8 (Blueprint Part One, §7).
 *
 * Seam between [ContextBuilder] and BehaviorLedger/TodayContext's real
 * CheckmatePrefs-backed storage — same reasoning as every other Fake/production-adapter
 * pair built for the intervention engine so far (TaskMutator, InterventionTransactionDao).
 * [BehaviorLedgerContextSource] is the real implementation.
 */
interface BehaviorContextSource {
    fun getSkipCountForSubject(subject: String, withinDays: Int): Int
    fun getSkipCountByType(subject: String, taskType: String, withinDays: Int): Int
    fun getRecentSkipRatePercent(): Int
    fun getStreakDays(): Int
    fun getTodayCompletedSummary(): String
    fun getTodayFreeTextUpdates(): String
    fun getAttentionStats(): AttentionStats
}
