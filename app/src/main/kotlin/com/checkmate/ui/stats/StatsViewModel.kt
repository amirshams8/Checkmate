package com.checkmate.ui.stats

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.checkmate.core.AppUsageTracker
import com.checkmate.planner.PlanStore
import com.checkmate.planner.intervention.InterventionDatabase
import com.checkmate.planner.intervention.InterventionStats
import com.checkmate.planner.intervention.OutcomeLedgerStats
import com.checkmate.psyche.BehaviorLedger
import com.checkmate.service.DayHistorySyncManager
import com.checkmate.workmode.WorkModeSchedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
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
    val screenTimeHistory:    List<Pair<String, Int>>  = emptyList(),  // day -> minutes
    // ── Consistency calendar (green/yellow/red dots per day) ──────────────
    val consistencyMonthLabel: String                             = "",
    val consistencyMonth:      Map<Int, PlanStore.DayStudyStatus> = emptyMap(),
    // "No tasks in plan == no study": consecutive days (ending yesterday) with nothing
    // planned or nothing completed. 0 means no gap right now.
    val consecutiveMissedDays: Int                                = 0,
    // Step 14 (Blueprint §26 "Baseline intervention statistics"): read model over the
    // Step 12 Outcome Ledger. Defaults to InterventionStats.EMPTY (totalResolved == 0)
    // until loadInterventionStats() below actually runs — the screen uses that as the
    // signal to hide the card entirely rather than show an all-zero one, same posture as
    // the consecutiveMissedDays warning card already does for its own condition.
    val interventionStats: InterventionStats = InterventionStats.EMPTY
)

class StatsViewModel : ViewModel() {
    private val _state = MutableStateFlow(StatsState())
    val state: StateFlow<StatsState> = _state.asStateFlow()

    init { loadStats() }

    private fun loadStats() {
        viewModelScope.launch {
            // "Sync everything" pass: merge in any day-history (plan/checklist/
            // check-in) this device is missing BEFORE reading PlanStore below, so
            // a day synced from another device shows up in this same load's
            // streak/weekly/monthly numbers instead of only after the next
            // reload. Per-day merge, never overwrites a day already present
            // locally — see DayHistorySyncManager.pullMissingDays' own doc.
            withContext(Dispatchers.IO) { DayHistorySyncManager.pullMissingDays() }

            val streak       = PlanStore.getStreakDays()
            val todayPct     = PlanStore.getTodayCompletionPercent()
            val weekPct      = PlanStore.getWeekCompletionPercent()
            val weekly       = PlanStore.getWeeklyData()
            val subjectData  = PlanStore.getSubjectStats()
            val ledger       = BehaviorLedger.getAttentionStats()
            val pauseStats   = PlanStore.getPauseStats()

            // Consistency calendar: current month's per-day status, plus how many days in a
            // row nothing's been studied — see PlanStore.DayStudyStatus doc for why an empty
            // plan counts the same as a missed day here.
            val cal = Calendar.getInstance()
            val monthMap = PlanStore.getMonthConsistency(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH))
            val monthLabel = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
            val missedStreak = PlanStore.getConsecutiveMissedDays()

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
                pauseRatePercent        = pauseStats.pauseRatePercent,
                consistencyMonthLabel   = monthLabel,
                consistencyMonth        = monthMap,
                consecutiveMissedDays   = missedStreak
            )}

            // Backup the Consistency tab snapshot to the sync code (fire-and-forget,
            // no-op with no sync code) and, if this device has no study history yet
            // (fresh install), restore the synced snapshot instead of showing all
            // zeros. Never overrides real local numbers — see
            // StatsSyncManager.looksEmpty()/pullStats() docs. Screen-usage-time
            // fields are excluded from sync entirely and untouched here.
            withContext(Dispatchers.IO) {
                StatsSyncManager.pushStats(_state.value)
                if (StatsSyncManager.looksEmpty(_state.value)) {
                    StatsSyncManager.pullStats(_state.value)?.let { restored ->
                        _state.update { restored }
                    }
                }
                // Opportunistic backup, same trigger as StatsSyncManager's own push
                // just above — a Stats-tab open doubles as a day-history backup
                // point in addition to the nightly EOD push (GuardianNotifier.
                // sendEndOfDaySummary).
                DayHistorySyncManager.pushHistory()
            }
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
     * (00:00-05:00 every day, +01:00-15:10 on Sun/Wed, +19:00-now every day)
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

        // Sun/Wed extra window: 01:00-15:10 — only the part beyond END_HOUR is new
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

    /**
     * Step 14 (Blueprint §26 "Baseline intervention statistics"). Separate load function,
     * same pattern as [loadAppUsage] just above — needs a Context (for
     * [InterventionDatabase.getInstance]) that only the Composable has, so it's called from
     * its own LaunchedEffect in StatsScreen rather than folded into [loadStats]'s init-time
     * call. Safe to call repeatedly (e.g. on resume), same as loadAppUsage.
     *
     * This is purely a read model — see [OutcomeLedgerStats]'s own doc for why "Learning
     * comes after measurement" (§25 principle 7) means nothing here feeds back into
     * trigger sensitivity, policy, or strategy selection. That's Step 15, not this.
     */
    fun loadInterventionStats(context: Context) {
        viewModelScope.launch {
            val stats = withContext(Dispatchers.IO) {
                val dao = InterventionDatabase.getInstance(context).outcomeLedgerDao()
                OutcomeLedgerStats.compute(dao)
            }
            _state.update { it.copy(interventionStats = stats) }
        }
    }
}


//===== WORKERFILE: reports-studystate-dayhistory-routes.js (add to the steep-band-1bd0 Worker project via Wrangler — NOT part of this Android repo) =====
// Three new routes for the same Worker your existing /profile, /tasks, /stats,
// /outcomes routes already live in. Same KV binding those use (referred to
// below as KV — swap in your actual binding name from wrangler.toml).
// Each route: POST writes, GET reads, keyed by "code" (the same sync_code
// value the app already sends to every other route).
//
// Drop these three blocks into your existing router alongside the other
// routes' handlers, using whatever routing style the rest of the Worker
// already uses (raw `if (url.pathname === "/x")` switch, or a router lib) —
// shown here as plain async functions you can wire in either way.

// ── /reports — LearningReportSyncManager ───────────────────────────────────
// Append-only list, deduped by reportId (a content hash the app computes) —
// NOT a last-write-wins single blob like /profile.
async function handleReports(request, KV) {
  const url = new URL(request.url);

  if (request.method === "POST") {
    const { code, reportId, pushedAt, reportText } = await request.json();
    if (!code || !reportId || !reportText) {
      return new Response("Missing code/reportId/reportText", { status: 400 });
    }
    const key = `reports:${code}`;
    const existing = JSON.parse((await KV.get(key)) || "[]");
    if (!existing.some((r) => r.reportId === reportId)) {
      existing.push({ reportId, pushedAt, reportText });
      await KV.put(key, JSON.stringify(existing));
    }
    return new Response("OK", { status: 200 });
  }

  if (request.method === "GET") {
    const code = url.searchParams.get("code");
    if (!code) return new Response("Missing code", { status: 400 });
    const reports = JSON.parse((await KV.get(`reports:${code}`)) || "[]");
    return new Response(JSON.stringify({ reports }), {
      headers: { "Content-Type": "application/json" },
    });
  }

  return new Response("Method not allowed", { status: 405 });
}

// ── /studystate — StudyStateSyncManager ─────────────────────────────────────
// Single blob, same shape as /profile: { code, updatedAt, state: { mentor_chat_history?, checklist_template?, coaching_planner_entries? } }
async function handleStudyState(request, KV) {
  const url = new URL(request.url);

  if (request.method === "POST") {
    const { code, state } = await request.json();
    if (!code || !state) return new Response("Missing code/state", { status: 400 });
    await KV.put(`studystate:${code}`, JSON.stringify(state));
    return new Response("OK", { status: 200 });
  }

  if (request.method === "GET") {
    const code = url.searchParams.get("code");
    if (!code) return new Response("Missing code", { status: 400 });
    const state = JSON.parse((await KV.get(`studystate:${code}`)) || "{}");
    return new Response(JSON.stringify({ state }), {
      headers: { "Content-Type": "application/json" },
    });
  }

  return new Response("Method not allowed", { status: 405 });
}

// ── /dayhistory — DayHistorySyncManager ─────────────────────────────────────
// Single blob, whole-history-replace on push (the app itself only ever sends
// its full locally-known set): { code, updatedAt, plans: {dayKey: json}, checklists: {dayKey: json}, checkins: {dayKey: json} }
async function handleDayHistory(request, KV) {
  const url = new URL(request.url);

  if (request.method === "POST") {
    const { code, plans, checklists, checkins } = await request.json();
    if (!code) return new Response("Missing code", { status: 400 });
    await KV.put(
      `dayhistory:${code}`,
      JSON.stringify({ plans: plans || {}, checklists: checklists || {}, checkins: checkins || {} })
    );
    return new Response("OK", { status: 200 });
  }

  if (request.method === "GET") {
    const code = url.searchParams.get("code");
    if (!code) return new Response("Missing code", { status: 400 });
    const raw = await KV.get(`dayhistory:${code}`);
    const data = JSON.parse(raw || '{"plans":{},"checklists":{},"checkins":{}}');
    return new Response(JSON.stringify(data), {
      headers: { "Content-Type": "application/json" },
    });
  }

  return new Response("Method not allowed", { status: 405 });
}

// Wire into your existing fetch handler's routing, e.g.:
//   if (url.pathname === "/reports")    return handleReports(request, env.YOUR_KV_BINDING);
//   if (url.pathname === "/studystate") return handleStudyState(request, env.YOUR_KV_BINDING);
//   if (url.pathname === "/dayhistory") return handleDayHistory(request, env.YOUR_KV_BINDING);
