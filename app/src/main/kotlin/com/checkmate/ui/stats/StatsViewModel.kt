package com.checkmate.ui.stats

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.checkmate.core.AppUsageTracker
import com.checkmate.planner.PlanStore
import com.checkmate.psyche.BehaviorLedger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StatsState(
    val streakDays:           Int                      = 0,
    val todayCompletion:      Int                      = 0,
    val weekCompletion:       Int                      = 0,
    val weeklyData:           List<Pair<String, Int>>  = emptyList(),
    val subjectStats:         List<Pair<String, Int>>  = emptyList(),
    val attentionChecksPassed: Int                     = 0,
    val attentionChecksMissed: Int                     = 0,
    val avgFocusMinutes:      Int                      = 0,
    // App usage (Digital Wellbeing-style)
    val hasUsageAccess:       Boolean                  = true,
    val appUsageToday:        List<Pair<String, Int>>  = emptyList(), // label -> minutes
    val totalScreenMinutesToday: Int                   = 0,
    val screenTimeHistory:    List<Pair<String, Int>>  = emptyList()  // day -> minutes
)

class StatsViewModel : ViewModel() {
    private val _state = MutableStateFlow(StatsState())
    val state: StateFlow<StatsState> = _state.asStateFlow()

    init { loadStats() }

    private fun loadStats() {
        viewModelScope.launch {
            val streak       = PlanStore.getStreakDays()
            val todayPct     = PlanStore.getTodayCompletionPercent()
            val weekPct      = PlanStore.getWeekCompletionPercent()
            val weekly       = PlanStore.getWeeklyData()
            val subjectData  = PlanStore.getSubjectStats()
            val ledger       = BehaviorLedger.getAttentionStats()
            _state.update { it.copy(
                streakDays            = streak,
                todayCompletion       = todayPct,
                weekCompletion        = weekPct,
                weeklyData            = weekly,
                subjectStats          = subjectData,
                attentionChecksPassed = ledger.checksPassed,
                attentionChecksMissed = ledger.checksMissed,
                avgFocusMinutes       = ledger.avgFocusMinutes
            )}
        }
    }

    /**
     * Loads on-device app usage history (today's breakdown + 7-day screen
     * time). Called from StatsScreen with the composable's Context since
     * UsageStatsManager needs it. Safe to call repeatedly (e.g. on resume).
     */
    fun loadAppUsage(context: Context) {
        viewModelScope.launch {
            val granted = AppUsageTracker.hasUsageAccess(context)
            if (!granted) {
                _state.update { it.copy(hasUsageAccess = false) }
                return@launch
            }
            val (today, history) = withContext(Dispatchers.IO) {
                val today = AppUsageTracker.getTodayUsage(context, limit = 6)
                    .map { it.label to (it.foregroundMillis / 60_000L).toInt() }
                val history = AppUsageTracker.getScreenTimeHistory(context, days = 7)
                    .map { it.dayLabel to (it.totalMillis / 60_000L).toInt() }
                today to history
            }
            val totalMinutes = AppUsageTracker.getTodayTotalMillis(context).let { (it / 60_000L).toInt() }
            _state.update { it.copy(
                hasUsageAccess          = true,
                appUsageToday           = today,
                totalScreenMinutesToday = totalMinutes,
                screenTimeHistory       = history
            )}
        }
    }
}
