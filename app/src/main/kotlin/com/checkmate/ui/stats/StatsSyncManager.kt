package com.checkmate.ui.stats

import android.util.Log
import com.checkmate.core.CheckmatePrefs
import com.checkmate.planner.PlanStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * StatsSyncManager — backs up/restores the Consistency tab (streak, today/week
 * completion, weekly + subject breakdown, consistency calendar, attention +
 * pause stats, focus score) to the same Cloudflare Worker + KV as
 * ProfileSyncManager/TaskSyncManager, keyed by the same "sync_code".
 *
 * Deliberately EXCLUDES anything screen-usage-time related (appUsageToday,
 * totalScreenMinutesToday, screenTimeHistory, hasUsageAccess) — that stays
 * on-device only, per explicit request. focusScore IS included since it's a
 * single displayed 0-100 number on the tab, not raw usage time.
 *
 * This is a SNAPSHOT sync, not a replication of the underlying day-by-day
 * plan history that PlanStore actually computes these numbers from. That
 * means: it's enough to see the same Consistency tab on a second device or
 * after a restore, but it does NOT reconstruct history for streak/calendar
 * math to keep computing correctly from raw data going forward — that would
 * mean syncing every "plan_<day>" key, a bigger job. Push happens every time
 * the Stats tab loads (cheap, fire-and-forget). Pull only overrides local
 * state when local looks genuinely empty (fresh install, no plan history yet
 * on this device) — it will never clobber real local numbers.
 */
object StatsSyncManager {

    private const val TAG = "StatsSyncManager"
    private const val STATS_URL = "https://steep-band-1bd0.amirshamse8.workers.dev/stats"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun syncCode(): String? =
        CheckmatePrefs.getString("sync_code", null)?.trim()?.takeIf { it.isNotBlank() }

    /** True when local state looks like there's simply no study history on this device yet. */
    fun looksEmpty(state: StatsState): Boolean =
        state.streakDays == 0 &&
        state.todayCompletion == 0 &&
        state.weekCompletion == 0 &&
        state.weeklyData.all { it.second == 0 } &&
        state.subjectStats.isEmpty()

    /**
     * Pushes the current Consistency tab snapshot. No-op if no sync code set.
     * Must be called from a background thread.
     */
    fun pushStats(state: StatsState) {
        val code = syncCode() ?: return
        try {
            val weekly = JSONArray().apply {
                state.weeklyData.forEach { (label, pct) -> put(JSONObject().apply { put("label", label); put("pct", pct) }) }
            }
            val subjects = JSONArray().apply {
                state.subjectStats.forEach { (name, pct) -> put(JSONObject().apply { put("name", name); put("pct", pct) }) }
            }
            val monthMap = JSONObject().apply {
                state.consistencyMonth.forEach { (day, status) -> put(day.toString(), status.name) }
            }
            val stats = JSONObject().apply {
                put("streakDays", state.streakDays)
                put("todayCompletion", state.todayCompletion)
                put("weekCompletion", state.weekCompletion)
                put("weeklyData", weekly)
                put("subjectStats", subjects)
                put("attentionChecksPassed", state.attentionChecksPassed)
                put("attentionChecksMissed", state.attentionChecksMissed)
                put("avgFocusMinutes", state.avgFocusMinutes)
                put("actualFocusMinutesToday", state.actualFocusMinutesToday)
                put("avgPausesPerSession", state.avgPausesPerSession)
                put("pauseRatePercent", state.pauseRatePercent)
                put("focusScore", state.focusScore)
                put("consistencyMonthLabel", state.consistencyMonthLabel)
                put("consistencyMonth", monthMap)
                put("consecutiveMissedDays", state.consecutiveMissedDays)
            }
            val payload = JSONObject().apply {
                put("code", code)
                put("updatedAt", System.currentTimeMillis())
                put("stats", stats)
            }
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val response = client.newCall(Request.Builder().url(STATS_URL).post(body).build()).execute()
            Log.d(TAG, "pushStats: ${response.code}")
            response.close()
        } catch (e: Exception) {
            Log.w(TAG, "pushStats exception: ${e.message}")
        }
    }

    /**
     * Fetches the synced snapshot and, if present, returns a StatsState copy
     * with the synced fields applied (screen-usage fields untouched — caller's
     * existing values for those pass straight through). Returns null if no
     * sync code, no data pushed yet, or the fetch fails. Must be called from a
     * background thread; caller is responsible for only invoking this when
     * looksEmpty(currentState) is true, so real local numbers are never
     * clobbered.
     */
    fun pullStats(current: StatsState): StatsState? {
        val code = syncCode() ?: return null
        return try {
            val request = Request.Builder().url("$STATS_URL?code=$code").get().build()
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string()
            response.close()
            if (!response.isSuccessful || bodyStr.isNullOrBlank()) return null

            val obj = JSONObject(bodyStr)
            val stats = obj.optJSONObject("stats") ?: return null

            val weekly = mutableListOf<Pair<String, Int>>()
            stats.optJSONArray("weeklyData")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    weekly.add(o.getString("label") to o.getInt("pct"))
                }
            }
            val subjects = mutableListOf<Pair<String, Int>>()
            stats.optJSONArray("subjectStats")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    subjects.add(o.getString("name") to o.getInt("pct"))
                }
            }
            val monthMap = mutableMapOf<Int, PlanStore.DayStudyStatus>()
            stats.optJSONObject("consistencyMonth")?.let { obj2 ->
                obj2.keys().forEach { key ->
                    val day = key.toIntOrNull() ?: return@forEach
                    val status = try { PlanStore.DayStudyStatus.valueOf(obj2.getString(key)) } catch (_: Exception) { null }
                    if (status != null) monthMap[day] = status
                }
            }
            if (weekly.isEmpty() && subjects.isEmpty() && monthMap.isEmpty() && stats.optInt("streakDays", 0) == 0) {
                return null // nothing meaningful pushed yet
            }

            current.copy(
                streakDays              = stats.optInt("streakDays", 0),
                todayCompletion         = stats.optInt("todayCompletion", 0),
                weekCompletion          = stats.optInt("weekCompletion", 0),
                weeklyData              = weekly,
                subjectStats            = subjects,
                attentionChecksPassed   = stats.optInt("attentionChecksPassed", 0),
                attentionChecksMissed   = stats.optInt("attentionChecksMissed", 0),
                avgFocusMinutes         = stats.optInt("avgFocusMinutes", 0),
                actualFocusMinutesToday = stats.optInt("actualFocusMinutesToday", 0),
                avgPausesPerSession     = stats.optDouble("avgPausesPerSession", 0.0).toFloat(),
                pauseRatePercent        = stats.optInt("pauseRatePercent", 0),
                focusScore              = stats.optInt("focusScore", current.focusScore),
                consistencyMonthLabel   = stats.optString("consistencyMonthLabel", current.consistencyMonthLabel),
                consistencyMonth        = monthMap,
                consecutiveMissedDays   = stats.optInt("consecutiveMissedDays", 0)
            )
        } catch (e: Exception) {
            Log.w(TAG, "pullStats exception: ${e.message}")
            null
        }
    }
}
