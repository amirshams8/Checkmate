package com.checkmate.psyche.intervention

import com.checkmate.psyche.AttentionStats

class FakeBehaviorContextSource(
    private val skipCountBySubject: Map<String, Int> = emptyMap(),
    private val skipCountByType: Map<Pair<String, String>, Int> = emptyMap(),
    private val recentSkipRatePercent: Int = 0,
    private val streakDays: Int = 0,
    private val todayCompletedSummary: String = "",
    private val todayFreeTextUpdates: String = "",
    private val attentionStats: AttentionStats = AttentionStats(checksPassed = 0, checksMissed = 0, avgFocusMinutes = 0)
) : BehaviorContextSource {

    override fun getSkipCountForSubject(subject: String, withinDays: Int): Int =
        skipCountBySubject[subject] ?: 0

    override fun getSkipCountByType(subject: String, taskType: String, withinDays: Int): Int =
        skipCountByType[subject to taskType] ?: 0

    override fun getRecentSkipRatePercent(): Int = recentSkipRatePercent

    override fun getStreakDays(): Int = streakDays

    override fun getTodayCompletedSummary(): String = todayCompletedSummary

    override fun getTodayFreeTextUpdates(): String = todayFreeTextUpdates

    override fun getAttentionStats(): AttentionStats = attentionStats
}
