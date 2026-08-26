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
 * Two sync paths share this one worker blob:
 *  - [pushState]/[pullStateIfEmpty]: reinstall-recovery only, once-daily EOD
 *    cadence, restore-only-if-locally-unset (see GuardianNotifier.sendEndOfDaySummary
 *    and CheckmateApp startup).
 *  - [pushMentorMessage]/[pullMentorHistory]: fast per-message mentor path, called
 *    directly from MentorViewModel on every send/receive. Both are read-modify-write
 *    against the server blob (GET current state, touch only KEY_MENTOR_HISTORY, PUT
 *    the whole thing back) since the worker's /studystate route is a flat overwrite
 *    with no server-side merge — skipping the GET would silently wipe out whatever
 *    checklist_template / coaching_planner_entries the other device last synced.
 *
 * Synchronous OkHttp calls, matching [ProfileSyncManager]'s style (these are
 * plain CheckmatePrefs string reads, no suspend DAO calls involved) — callers
 * must run this off the main thread, which all wiring points already do.
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

    /**
     * Pushes a single updated mentor chat history string immediately — not
     * gated behind [pushState]'s once-daily cadence. Read-modify-write: fetches
     * the current server blob first and merges only [KEY_MENTOR_HISTORY] into
     * it, so a device that only ever talks to Mentor can't clobber another
     * device's already-synced checklist/coaching fields. No-op if no sync code
     * is set. Must be called from a background thread.
     */
    fun pushMentorMessage(chatHistoryJson: String) {
        val code = syncCode() ?: return
        try {
            val state = fetchServerState(code) ?: JSONObject()
            state.put(KEY_MENTOR_HISTORY, chatHistoryJson)

            val payload = JSONObject().apply {
                put("code", code)
                put("updatedAt", System.currentTimeMillis())
                put("state", state)
            }
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val response = client.newCall(Request.Builder().url(STATE_URL).post(body).build()).execute()
            Log.d(TAG, "pushMentorMessage: ${response.code}")
            response.close()
        } catch (e: Exception) {
            Log.w(TAG, "pushMentorMessage exception: ${e.message}")
        }
    }

    /**
     * Fetches the latest mentor chat history from the server unconditionally —
     * unlike [pullStateIfEmpty], which only restores when the local field is
     * null, this always checks so MentorViewModel can pick up a message sent
     * from the other device. Returns null on any failure or empty history.
     * Must be called from a background thread.
     */
    fun pullMentorHistory(): String? {
        val code = syncCode() ?: return null
        return try {
            fetchServerState(code)?.optString(KEY_MENTOR_HISTORY, "")?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "pullMentorHistory exception: ${e.message}")
            null
        }
    }

    /** Shared GET + parse helper for [pushMentorMessage] / [pullMentorHistory]. */
    private fun fetchServerState(code: String): JSONObject? {
        val request = Request.Builder().url("$STATE_URL?code=$code").get().build()
        val response = client.newCall(request).execute()
        val bodyStr = response.body?.string()
        response.close()
        if (!response.isSuccessful || bodyStr.isNullOrBlank()) return null
        return JSONObject(bodyStr).optJSONObject("state")
    }
}
