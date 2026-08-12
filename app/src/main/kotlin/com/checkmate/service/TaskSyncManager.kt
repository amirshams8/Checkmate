package com.checkmate.service

import android.util.Log
import com.checkmate.core.CheckmatePrefs
import com.checkmate.planner.PlanStore
import com.checkmate.planner.model.StudyTask
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * TaskSyncManager — syncs ONLY today's task list (PlanStore.todayTasks) between two
 * devices that share the same sync code. Nothing else (settings, stats, streaks,
 * profile, checklist) is touched — every other screen keeps reading/writing its own
 * device-local CheckmatePrefs exactly as before.
 *
 * Reuses the same Cloudflare Worker StatusReporter/TelegramAlertBot already POST to
 * (steep-band-1bd0.amirshamse8.workers.dev) with a new /tasks route backed by KV,
 * same shape as the existing /status and /usage routes. See the /tasks worker.js
 * snippet shipped alongside this file — it needs to be added to the worker project
 * directly (that source isn't part of this Android repo).
 *
 * Sync model: last-write-wins, whole-list replace, keyed by "sync_code" (set
 * identically on both devices in Settings → TASK SYNC — deliberately separate from
 * the guardian's Telegram chat id, so this works even on a setup with no guardian
 * configured). Every push carries updatedAt (epoch ms) and dayKey (PlanStore's own
 * "yyyy_dayOfYear" key, via PlanStore.currentDayKey()) so a pull can never splice a
 * stale day's tasks onto today, and a push never clobbers a newer edit made on the
 * other device in the last few seconds.
 */
object TaskSyncManager {

    private const val TAG = "TaskSyncManager"
    private const val TASKS_URL = "https://steep-band-1bd0.amirshamse8.workers.dev/tasks"

    private val json = Json { ignoreUnknownKeys = true }
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
     * Pushes today's task list up to the worker under the configured sync code.
     * No-op if no sync code is set (sync is opt-in — an unconfigured device never
     * phones home). Must be called from a background thread; failures are logged
     * and swallowed so a flaky connection never blocks a local task action.
     */
    fun pushTasks(tasks: List<StudyTask>) {
        val code = syncCode() ?: return
        try {
            val tasksJson = json.encodeToString(tasks)
            val payload = JSONObject().apply {
                put("code", code)
                put("dayKey", PlanStore.currentDayKey())
                put("updatedAt", PlanStore.getLastUpdatedAt())
                put("tasks", JSONArray(tasksJson))
            }
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val response = client.newCall(Request.Builder().url(TASKS_URL).post(body).build()).execute()
            Log.d(TAG, "pushTasks: ${response.code}")
            response.close()
        } catch (e: Exception) {
            Log.w(TAG, "pushTasks exception: ${e.message}")
        }
    }

    /**
     * Fetches the other device's synced tasks and returns them ONLY if they're for
     * today and strictly newer than localUpdatedAt — otherwise returns null and the
     * caller leaves today's plan untouched. Must be called from a background thread.
     */
    fun pullTasksIfNewer(localUpdatedAt: Long): List<StudyTask>? {
        val code = syncCode() ?: return null
        return try {
            val request = Request.Builder().url("$TASKS_URL?code=$code").get().build()
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string()
            response.close()
            if (!response.isSuccessful || bodyStr.isNullOrBlank()) return null

            val obj = JSONObject(bodyStr)
            val remoteDayKey    = obj.optString("dayKey", "")
            val remoteUpdatedAt = obj.optLong("updatedAt", 0L)
            // Different day (or worker has nothing yet) — never splice it onto today's plan.
            if (remoteDayKey != PlanStore.currentDayKey()) return null
            // Local copy is already the same age or newer — nothing to pull.
            if (remoteUpdatedAt <= localUpdatedAt) return null

            val tasksArr = obj.optJSONArray("tasks") ?: return null
            json.decodeFromString<List<StudyTask>>(tasksArr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "pullTasksIfNewer exception: ${e.message}")
            null
        }
    }
}
