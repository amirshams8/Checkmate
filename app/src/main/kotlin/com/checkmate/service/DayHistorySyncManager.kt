package com.checkmate.service

import android.util.Log
import com.checkmate.core.CheckmatePrefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * DayHistorySyncManager — the bigger of the two "sync everything" pieces
 * ([StudyStateSyncManager] is the smaller one). Backs up the full day-by-day
 * history that every existing sync manager deliberately left out:
 *   - "plan_<dayKey>"          (PlanStore — one full task list per day)
 *   - "checklist_<dayKey>"     (DailyChecklist — daily done/not-done state;
 *                               NOT "checklist_template", which is bounded
 *                               "current state" and lives in StudyStateSyncManager)
 *   - "daily_checkin_<dayKey>" (DailyCheckIn — one check-in record per day)
 *
 * StatsSyncManager's own doc already flagged this exact gap: "it does NOT
 * reconstruct history for streak/calendar math ... that would mean syncing
 * every plan_<day> key, a bigger job." This is that bigger job.
 *
 * DIFFERENT MERGE MODEL from every other sync manager here, and deliberately
 * so: PlanStore/DailyChecklist/DailyCheckIn keep no index of which day keys
 * exist (see CheckmatePrefs.allKeysWithPrefix's own doc) and day-history is
 * naturally sparse rather than all-or-nothing, so:
 *   - [pushHistory] pushes every locally known day (discovered via
 *     CheckmatePrefs.allKeysWithPrefix, not a Room table) as one JSON object
 *     keyed by day, whole-history-replace on the KV side.
 *   - [pullMissingDays] merges IN only the days this device doesn't already
 *     have locally, per key — never a single "local table is empty" check
 *     like [OutcomeLedgerSyncManager]/[BehaviorLedgerSyncManager] use, since a
 *     device could legitimately have some days locally and be missing others
 *     (e.g. it just joined an existing sync_code partway through the
 *     student's history).
 *
 * At realistic personal-use scale (a year or two of daily entries, each a few
 * KB) the whole history is still comfortably inside one KV value and one
 * Worker request body, so a full push every time — same choice
 * [OutcomeLedgerSyncManager]/[BehaviorLedgerSyncManager] made for their own
 * bounded tables — stays simpler than a per-day delta/diff scheme.
 *
 * Synchronous OkHttp calls, same style as [ProfileSyncManager]/
 * [StudyStateSyncManager] — callers MUST run this off the main thread (see
 * GuardianNotifier.sendEndOfDaySummary's Thread{} wrap).
 */
object DayHistorySyncManager {

    private const val TAG = "DayHistorySyncManager"
    private const val HISTORY_URL = "https://steep-band-1bd0.amirshamse8.workers.dev/dayhistory"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun syncCode(): String? =
        CheckmatePrefs.getString("sync_code", null)?.trim()?.takeIf { it.isNotBlank() }

    /** True when a sync code is configured — callers can use this to show/hide sync UI state. */
    fun isEnabled(): Boolean = syncCode() != null

    /**
     * Pushes every locally known day's plan/checklist/check-in data. No-op if
     * no sync code is set, or if there's genuinely nothing local yet. Must be
     * called from a background thread.
     */
    fun pushHistory() {
        val code = syncCode() ?: return
        try {
            val plans = JSONObject()
            CheckmatePrefs.allKeysWithPrefix("plan_").forEach { key ->
                CheckmatePrefs.getString(key, null)?.let { plans.put(key.removePrefix("plan_"), it) }
            }
            val checklists = JSONObject()
            CheckmatePrefs.allKeysWithPrefix("checklist_")
                .filterNot { it == "checklist_template" }
                .forEach { key ->
                    CheckmatePrefs.getString(key, null)?.let { checklists.put(key.removePrefix("checklist_"), it) }
                }
            val checkins = JSONObject()
            CheckmatePrefs.allKeysWithPrefix("daily_checkin_").forEach { key ->
                CheckmatePrefs.getString(key, null)?.let { checkins.put(key.removePrefix("daily_checkin_"), it) }
            }

            if (plans.length() == 0 && checklists.length() == 0 && checkins.length() == 0) return

            val payload = JSONObject().apply {
                put("code", code)
                put("updatedAt", System.currentTimeMillis())
                put("plans", plans)
                put("checklists", checklists)
                put("checkins", checkins)
            }
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val response = client.newCall(Request.Builder().url(HISTORY_URL).post(body).build()).execute()
            Log.d(
                TAG,
                "pushHistory: ${response.code} (${plans.length()} plan days, " +
                    "${checklists.length()} checklist days, ${checkins.length()} check-ins)"
            )
            response.close()
        } catch (e: Exception) {
            Log.w(TAG, "pushHistory exception: ${e.message}")
        }
    }

    /**
     * Fills in ONLY the days this device doesn't already have locally — per-day
     * merge, never overwrites an existing plan_<day>/checklist_<day>/
     * daily_checkin_<day> entry. Safe to call every time (e.g. alongside
     * [StatsViewModel]'s existing load-time sync calls) since it's a strict
     * no-clobber merge, not a "restore once" operation. Must be called from a
     * background thread.
     */
    fun pullMissingDays() {
        val code = syncCode() ?: return
        try {
            val request = Request.Builder().url("$HISTORY_URL?code=$code").get().build()
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string()
            response.close()
            if (!response.isSuccessful || bodyStr.isNullOrBlank()) return

            val obj = JSONObject(bodyStr)
            var restored = 0

            obj.optJSONObject("plans")?.let { plans ->
                plans.keys().asSequence().forEach { dayKey ->
                    val prefKey = "plan_$dayKey"
                    if (CheckmatePrefs.getString(prefKey, null) == null) {
                        CheckmatePrefs.putString(prefKey, plans.getString(dayKey)); restored++
                    }
                }
            }
            obj.optJSONObject("checklists")?.let { checklists ->
                checklists.keys().asSequence().forEach { dayKey ->
                    val prefKey = "checklist_$dayKey"
                    if (CheckmatePrefs.getString(prefKey, null) == null) {
                        CheckmatePrefs.putString(prefKey, checklists.getString(dayKey)); restored++
                    }
                }
            }
            obj.optJSONObject("checkins")?.let { checkins ->
                checkins.keys().asSequence().forEach { dayKey ->
                    val prefKey = "daily_checkin_$dayKey"
                    if (CheckmatePrefs.getString(prefKey, null) == null) {
                        CheckmatePrefs.putString(prefKey, checkins.getString(dayKey)); restored++
                    }
                }
            }
            if (restored > 0) Log.d(TAG, "pullMissingDays: restored $restored day-history entries from sync code")
        } catch (e: Exception) {
            Log.w(TAG, "pullMissingDays exception: ${e.message}")
        }
    }
}
