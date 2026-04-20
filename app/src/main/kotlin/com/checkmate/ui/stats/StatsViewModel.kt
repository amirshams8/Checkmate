package com.checkmate.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.checkmate.planner.PlanStore
import com.checkmate.psyche.BehaviorLedger
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class StatsState(
    val streakDays:           Int                      = 0,
    val todayCompletion:      Int                      = 0,
    val weekCompletion:       Int                      = 0,
    val weeklyData:           List<Pair<String, Int>>  = emptyList(),
    val subjectStats:         List<Pair<String, Int>>  = emptyList(),
    val attentionChecksPassed: Int                     = 0,
    val attentionChecksMissed: Int                     = 0,
    val avgFocusMinutes:      Int                      = 0
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
}
