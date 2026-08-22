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
 * StudyStateSyncManager — "sync everything" pass, third and last piece alongside
 * [BehaviorLedgerSyncManager] and the pre-existing [OutcomeLedgerSyncManager]/
 * [ProfileSyncManager]/[TaskSyncManager]/[StatsSyncManager].
 *
 * Backs up the remaining small "current state" blobs that weren't covered by any
 * existing sync manager, same Cloudflare Worker + KV, same "sync_code":
 *   - mentor_chat_history   (MentorViewModel — capped at 40 messages)
 *   - checklist_template    (DailyChecklist — master item list, not the daily
 *                            done/not-done state, which is day-history — see
 *                            DayHistorySyncManager for that)
 *   - coaching_planner_entries (CoachingPlannerEntry — coaching-test countdown list)
 *
 * Same reinstall-recovery posture as ProfileSyncManager, one flat blob, guarded
 * per-field so a partially-configured device (e.g. one that customized the
 * checklist template but never used Mentor) doesn't get one field silently
 * overwritten by another field's absence.
 *
 * NOT wired into every individual edit call site (MentorViewModel.sendMessage,
 * the Checklist template editor, CoachingPlannerEntry.addEntry) — those live in
 * three different screens/modules and none of this data changes more than a few
 * times a day, so [pushState] instead piggybacks on the same once-daily EOD
 * summary cadence [BehaviorLedgerSyncManager]/[OutcomeLedgerSyncManager] already
 * use (see GuardianNotifier.sendEndOfDaySummary). [pullStateIfEmpty] is called
 * once at [com.checkmate.CheckmateApp] startup, same call site as those two.
 *
 * Synchronous OkHttp calls, matching [ProfileSyncManager]'s style (these are
 * plain CheckmatePrefs string reads, no suspend DAO calls involved) — callers
 * must run this off the main thread, which both wiring points already do.
 */
object StudyStateSyncManager {

    private const val TAG = "StudyStateSyncManager"
    private const val STATE_URL = "https://steep-band-1bd0.amirshamse8.workers.dev/studystate"

    private const val KEY_MENTOR_HISTORY = "mentor_chat_history"
    private const val KEY_CHECKLIST_TEMPLATE = "checklist_template"
    private const val KEY_COACHING_ENTRIES = "coaching_planner_entries"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun syncCode(): String? =
        CheckmatePrefs.getString("sync_code", null)?.trim()?.takeIf { it.isNotBlank() }

    /** True when a sync code is configured — callers can use this to show/hide sync UI state. */
    fun isEnabled(): Boolean = syncCode() != null

    /**
     * Pushes whichever of the three fields have local values (a device that's
     * never opened Mentor simply omits that field rather than pushing blank
     * over a possibly-already-synced value). No-op if no sync code is set.
     * Must be called from a background thread.
     */
    fun pushState() {
        val code = syncCode() ?: return
        try {
            val state = JSONObject()
            CheckmatePrefs.getString(KEY_MENTOR_HISTORY, null)?.let { state.put("mentor_chat_history", it) }
            CheckmatePrefs.getString(KEY_CHECKLIST_TEMPLATE, null)?.let { state.put("checklist_template", it) }
            CheckmatePrefs.getString(KEY_COACHING_ENTRIES, null)?.let { state.put("coaching_planner_entries", it) }

            if (state.length() == 0) return // nothing local to push yet

            val payload = JSONObject().apply {
                put("code", code)
                put("updatedAt", System.currentTimeMillis())
                put("state", state)
            }
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val response = client.newCall(Request.Builder().url(STATE_URL).post(body).build()).execute()
            Log.d(TAG, "pushState: ${response.code}")
            response.close()
        } catch (e: Exception) {
            Log.w(TAG, "pushState exception: ${e.message}")
        }
    }

    /**
     * Restores each of the three fields independently, but ONLY where the local
     * value for that specific field is unset — a device that already has its own
     * checklist template keeps it even if this restores a fresh Mentor history.
     * Must be called from a background thread.
     */
    fun pullStateIfEmpty() {
        val code = syncCode() ?: return
        val needMentor    = CheckmatePrefs.getString(KEY_MENTOR_HISTORY, null) == null
        val needChecklist = CheckmatePrefs.getString(KEY_CHECKLIST_TEMPLATE, null) == null
        val needCoaching  = CheckmatePrefs.getString(KEY_COACHING_ENTRIES, null) == null
        if (!needMentor && !needChecklist && !needCoaching) return // nothing to restore

        try {
            val request = Request.Builder().url("$STATE_URL?code=$code").get().build()
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string()
            response.close()
            if (!response.isSuccessful || bodyStr.isNullOrBlank()) return

            val state = JSONObject(bodyStr).optJSONObject("state") ?: return
            var restoredAny = false

            if (needMentor) {
                state.optString("mentor_chat_history", "").takeIf { it.isNotBlank() }?.let {
                    CheckmatePrefs.putString(KEY_MENTOR_HISTORY, it); restoredAny = true
                }
            }
            if (needChecklist) {
                state.optString("checklist_template", "").takeIf { it.isNotBlank() }?.let {
                    CheckmatePrefs.putString(KEY_CHECKLIST_TEMPLATE, it); restoredAny = true
                }
            }
            if (needCoaching) {
                state.optString("coaching_planner_entries", "").takeIf { it.isNotBlank() }?.let {
                    CheckmatePrefs.putString(KEY_COACHING_ENTRIES, it); restoredAny = true
                }
            }
            if (restoredAny) Log.d(TAG, "pullStateIfEmpty: restored fields from sync code")
        } catch (e: Exception) {
            Log.w(TAG, "pullStateIfEmpty exception: ${e.message}")
        }
    }
}
