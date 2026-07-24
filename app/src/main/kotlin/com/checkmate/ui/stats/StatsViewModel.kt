package com.checkmate.ui.stats

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.checkmate.core.AppUsageTracker
import com.checkmate.planner.PlanStore
import com.checkmate.psyche.BehaviorLedger
import com.checkmate.workmode.WorkModeSchedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.roundToInt

data class StatsState(
    val streakDays:           Int                      = 0,
    val todayCompletion:      Int                      = 0,
    val weekCompletion:       Int                      = 0,
    val weeklyData:           List<Pair<String, Int>>  = emptyList(),
    val subjectStats:         List<Pair<String, Int>>  = emptyList(),
    val attentionChecksPassed: Int                     = 0,
    val attentionChecksMissed: Int                     = 0,
    val avgFocusMinutes:      Int                      = 0,
    // Blueprint 2.6 — actual focus time, pause count/rate
    val actualFocusMinutesToday: Int                   = 0,
    val avgPausesPerSession:  Float                    = 0f,
    val pauseRatePercent:     Int                      = 0,
    // Blueprint 10.3 — single 0-100 focus score (Work Mode adherence + completion)
    val focusScore:           Int                      = 0,
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
            val pauseStats   = PlanStore.getPauseStats()
            _state.update { it.copy(
                streakDays              = streak,
                todayCompletion         = todayPct,
                weekCompletion          = weekPct,
                weeklyData               = weekly,
                subjectStats            = subjectData,
                attentionChecksPassed   = ledger.checksPassed,
                attentionChecksMissed   = ledger.checksMissed,
                avgFocusMinutes         = ledger.avgFocusMinutes,
                actualFocusMinutesToday = pauseStats.actualFocusMinutesToday,
                avgPausesPerSession     = pauseStats.avgPausesPerSession,
                pauseRatePercent        = pauseStats.pauseRatePercent
            )}
        }
    }

    /**
     * Loads on-device app usage history (today's breakdown + 7-day screen
     * time), plus the Blueprint 10.3 focus score, which needs the same
     * UsageStatsManager access. Called from StatsScreen with the composable's
     * Context. Safe to call repeatedly (e.g. on resume).
     */
    fun loadAppUsage(context: Context) {
        viewModelScope.launch {
            val granted = AppUsageTracker.hasUsageAccess(context)
            if (!granted) {
                // No usage access -> Work Mode adherence can't be measured; fall back to
                // completion rate alone so the score still means something instead of 0.
                _state.update { it.copy(
                    hasUsageAccess = false,
                    focusScore     = it.todayCompletion
                ) }
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
            val adherencePercent = withContext(Dispatchers.IO) { computeWorkModeAdherencePercent(context) }
            val score = ((_state.value.todayCompletion + adherencePercent) / 2f).roundToInt().coerceIn(0, 100)
            _state.update { it.copy(
                hasUsageAccess          = true,
                appUsageToday           = today,
                totalScreenMinutesToday = totalMinutes,
                screenTimeHistory       = history,
                focusScore              = score
            )}
        }
    }

    /**
     * Blueprint 10.3: "Work Mode adherence" half of the focus score. Measures
     * how much of today's already-elapsed WorkModeSchedule locked window(s)
     * (00:00-05:00 every day, +01:00-17:30 on Sun/Wed, +19:00-now every day)
     * had non-Checkmate app usage recorded — i.e. usage during hours the
     * student was supposed to be blocked out. 0 usage during locked time ->
     * 100% adherence; usage filling the whole locked window so far -> 0%.
     *
     * Lives in :app (not AppUsageTracker in :core) because it needs both
     * AppUsageTracker (:core) and WorkModeSchedule (:workmode) — :core can't
     * depend on :workmode (workmode already depends on core), so this can only
     * be composed at a layer that depends on both, which :app does.
     */
    private fun computeWorkModeAdherencePercent(context: Context): Int {
        val now = Calendar.getInstance()
        val dayStart = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val nowMillis = now.timeInMillis

        fun rangeMillis(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int): Pair<Long, Long>? {
            val start = (dayStart.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, startHour); set(Calendar.MINUTE, startMinute)
            }.timeInMillis
            val end = (dayStart.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, endHour); set(Calendar.MINUTE, endMinute)
            }.timeInMillis
            val clippedEnd = end.coerceAtMost(nowMillis)
            return if (clippedEnd > start) start to clippedEnd else null
        }

        val ranges = mutableListOf<Pair<Long, Long>>()

        // Tail of last night's usual 19:00-05:00 window, always present every day.
        rangeMillis(0, 0, WorkModeSchedule.END_HOUR, 0)?.let { ranges.add(it) }

        // Sun/Wed extra window: 01:00-17:30 — only the part beyond END_HOUR is new
        // (00:00-END_HOUR is already covered by the range above).
        if (now.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY || now.get(Calendar.DAY_OF_WEEK) == Calendar.WEDNESDAY) {
            rangeMillis(
                WorkModeSchedule.END_HOUR, 0,
                WorkModeSchedule.SPECIAL_END_HOUR, WorkModeSchedule.SPECIAL_END_MINUTE
            )?.let { ranges.add(it) }
        }

        // Start of tonight's usual window: 19:00 onward, clipped to now.
        rangeMillis(WorkModeSchedule.START_HOUR, 0, 24, 0)?.let { ranges.add(it) }

        val lockedElapsedMillis = ranges.sumOf { it.second - it.first }
        if (lockedElapsedMillis <= 0L) return 100 // no locked window has elapsed yet today

        val usedDuringLockedMillis = ranges.sumOf { (s, e) -> AppUsageTracker.getUsageMillisInRange(context, s, e) }
        val adherence = 1f - (usedDuringLockedMillis.toFloat() / lockedElapsedMillis.toFloat())
        return (adherence.coerceIn(0f, 1f) * 100).roundToInt()
    }
}
