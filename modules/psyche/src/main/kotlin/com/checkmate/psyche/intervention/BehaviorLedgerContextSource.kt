package com.checkmate.psyche.intervention

import com.checkmate.core.TodayContext
import com.checkmate.psyche.AttentionStats
import com.checkmate.psyche.BehaviorLedger

/**
 * Proactive Execution Engine — Step 8. Production [BehaviorContextSource] — thin adapter,
 * adds no new BehaviorLedger/TodayContext capability. [getRecentSkipRatePercent] is the
 * one place this does arithmetic rather than pure delegation, since BehaviorLedger returns
 * a 0f-1f fraction and §7's worked example is expressed as a percentage.
 */
class BehaviorLedgerContextSource : BehaviorContextSource {

    override fun getSkipCountForSubject(subject: String, withinDays: Int): Int =
        BehaviorLedger.getSkipCountForSubject(subject, withinDays)

    override fun getSkipCountByType(subject: String, taskType: String, withinDays: Int): Int =
        BehaviorLedger.getSkipCountByType(subject, taskType, withinDays)

    override fun getRecentSkipRatePercent(): Int =
        (BehaviorLedger.getRecentSkipRate() * 100).toInt()

    override fun getStreakDays(): Int = BehaviorLedger.getStreakDays()

    override fun getTodayCompletedSummary(): String = BehaviorLedger.getTodayCompletedSummary()

    override fun getTodayFreeTextUpdates(): String = TodayContext.getSummaryText()

    override fun getAttentionStats(): AttentionStats = BehaviorLedger.getAttentionStats()
}
