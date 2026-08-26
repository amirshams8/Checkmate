package com.checkmate.service

import android.util.Log
import com.checkmate.core.CheckmatePrefs
import com.checkmate.ui.mentor.MentorMessage
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
 * MentorSyncManager — syncs the Mentor chat log (MentorViewModel's PREFS_KEY_HISTORY)
 * between two devices sharing the same "sync_code" set in Settings -> Task Sync, same
 * Cloudflare Worker + KV that [TaskSyncManager]/[ProfileSyncManager]/[OutcomeLedgerSyncManager]
 * already POST to.
 *
 * REQUIRES a new `/mentor` route on the Worker (KV-backed, same shape as the existing
 * `/tasks` and `/outcomes` routes) — that source isn't part of this Android repo, same
 * caveat as TaskSyncManager's doc comment. Add a `/mentor` handler that stores whatever
 * JSON body is POSTed under the `code` field, and returns it verbatim on GET `?code=`.
 *
 * Sync model: merge, not last-write-wins whole-list-replace like [TaskSyncManager] — a
 * chat log is additive (each device may append messages while the other is offline), so
 * overwriting one device's copy with the other's would silently drop real messages. Every
 * pull unions local + remote messages, de-duped by (role, content, ts) and sorted by ts,
 * then both sides converge: the merged result is what gets kept locally AND pushed back up,
 * same non-destructive spirit as [OutcomeLedgerSyncManager] but for a log both devices write
 * to, not just one that gets backed up.
 */
object MentorSyncManager {

    private const val TAG = "MentorSyncManager"
    private const val MENTOR_URL = "https://steep-band-1bd0.amirshamse8.workers.dev/mentor"
    private const val MAX_MESSAGES = 40 // matches MentorViewModel.MAX_PERSISTED_MESSAGES

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
     * Pushes the given (already-trimmed) message list up under the configured sync code.
     * No-op if no sync code is set. Must be called from a background thread; failures are
     * logged and swallowed, same posture as every other sync manager here — a flaky
     * connection should never block sending or reading a Mentor message.
     */
    fun pushHistory(messages: List<MentorMessage>) {
        val code = syncCode() ?: return
        try {
            val messagesJson = json.encodeToString(messages)
            val payload = JSONObject().apply {
                put("code", code)
                put("updatedAt", System.currentTimeMillis())
                put("messages", JSONArray(messagesJson))
            }
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val response = client.newCall(Request.Builder().url(MENTOR_URL).post(body).build()).execute()
            Log.d(TAG, "pushHistory: ${response.code} (${messages.size} messages)")
            response.close()
        } catch (e: Exception) {
            Log.w(TAG, "pushHistory exception: ${e.message}")
        }
    }

    /**
     * Fetches the other device's synced messages and returns [local] merged with them —
     * union de-duped by (role, content, ts), sorted by ts, capped to [MAX_MESSAGES] — or
     * null if no sync code is set or nothing could be fetched (caller keeps [local]
     * untouched in that case). Must be called from a background thread.
     */
    fun pullAndMerge(local: List<MentorMessage>): List<MentorMessage>? {
        val code = syncCode() ?: return null
        return try {
            val request = Request.Builder().url("$MENTOR_URL?code=$code").get().build()
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string()
            response.close()
            if (!response.isSuccessful || bodyStr.isNullOrBlank()) return null

            val messagesArr = JSONObject(bodyStr).optJSONArray("messages") ?: return null
            val remote = json.decodeFromString<List<MentorMessage>>(messagesArr.toString())
            if (remote.isEmpty()) return null

            (local + remote)
                .distinctBy { "${it.role}|${it.content}|${it.ts}" }
                .sortedBy { it.ts }
                .takeLast(MAX_MESSAGES)
        } catch (e: Exception) {
            Log.w(TAG, "pullAndMerge exception: ${e.message}")
            null
        }
    }
}
