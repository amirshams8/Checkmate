package com.checkmate.service

import android.content.Context
import android.util.Log
import com.checkmate.core.CheckmatePrefs
import com.checkmate.psyche.db.BehaviorDatabase
import com.checkmate.psyche.db.BehaviorEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * BehaviorLedgerSyncManager — backs up/restores [BehaviorEventEntity] rows (the
 * Room-backed behavior event log — see BehaviorDatabase's own doc on why this
 * replaced the old CheckmatePrefs JSON blob) to the same Cloudflare Worker + KV
 * that [ProfileSyncManager]/[OutcomeLedgerSyncManager] already use, keyed by the
 * same "sync_code".
 *
 * Same reinstall-recovery posture as [OutcomeLedgerSyncManager], not live sync:
 *   - [pushLedger] — whole-table push, fire-and-forget. The table is capped at
 *     200 rows (see BehaviorEventDao.trimToNewest), so unlike day-history data
 *     this never grows unbounded — pushing the whole thing every time is cheap
 *     regardless of how long the student's been using the app.
 *   - [pullLedgerIfLocalEmpty] — restores ONLY if the local table is genuinely
 *     empty (fresh install / BehaviorDatabase's fallbackToDestructiveMigrationOnDowngrade
 *     wipe) — never merges into or overwrites real local history. Call once at
 *     [com.checkmate.CheckmateApp] startup, same call site as
 *     [OutcomeLedgerSyncManager.pullLedgerIfLocalEmpty].
 *
 * Room DAO access is suspend, so — same posture as [OutcomeLedgerSyncManager] —
 * this object owns its own [CoroutineScope] rather than pushing coroutine
 * management onto every call site.
 */
object BehaviorLedgerSyncManager {

    private const val TAG = "BehaviorLedgerSyncManager"
    private const val BEHAVIOR_URL = "https://steep-band-1bd0.amirshamse8.workers.dev/behavior"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
     * Pushes the entire local behavior event log up under the configured sync
     * code. No-op if no sync code is set. Fire-and-forget, same call-site shape
     * as [OutcomeLedgerSyncManager.pushLedger].
     */
    fun pushLedger(context: Context) {
        val code = syncCode() ?: return
        val appContext = context.applicationContext
        scope.launch {
            try {
                val dao = BehaviorDatabase.getInstance(appContext).behaviorEventDao()
                val events = dao.getAll()
                val eventsJson = JSONArray().apply { events.forEach { put(it.toJson()) } }
                val payload = JSONObject().apply {
                    put("code", code)
                    put("updatedAt", System.currentTimeMillis())
                    put("events", eventsJson)
                }
                val body = payload.toString().toRequestBody("application/json".toMediaType())
                val response = client.newCall(Request.Builder().url(BEHAVIOR_URL).post(body).build()).execute()
                Log.d(TAG, "pushLedger: ${response.code} (${events.size} events)")
                response.close()
            } catch (e: Exception) {
                Log.w(TAG, "pushLedger exception: ${e.message}")
            }
        }
    }

    /**
     * Restores the synced behavior log into the local Room table, but ONLY if
     * this device's local table is genuinely empty — never merges into or
     * overwrites real local history. Fire-and-forget like [pushLedger]; pass
     * [onResult] if a caller needs to know whether a restore happened
     * (CheckmateApp's startup call doesn't).
     */
    fun pullLedgerIfLocalEmpty(context: Context, onResult: ((restored: Boolean) -> Unit)? = null) {
        val code = syncCode()
        if (code == null) {
            onResult?.invoke(false)
            return
        }
        val appContext = context.applicationContext
        scope.launch {
            val restored = try {
                val dao = BehaviorDatabase.getInstance(appContext).behaviorEventDao()
                if (dao.count() > 0) {
                    false // real local history already exists — don't clobber it
                } else {
                    val request = Request.Builder().url("$BEHAVIOR_URL?code=$code").get().build()
                    val response = client.newCall(request).execute()
                    val bodyStr = response.body?.string()
                    response.close()
                    if (!response.isSuccessful || bodyStr.isNullOrBlank()) {
                        false
                    } else {
                        val eventsArr = JSONObject(bodyStr).optJSONArray("events")
                        if (eventsArr == null || eventsArr.length() == 0) {
                            false
                        } else {
                            for (i in 0 until eventsArr.length()) {
                                eventsArr.optJSONObject(i)?.toBehaviorEventEntity()?.let { dao.insert(it) }
                            }
                            dao.trimToNewest(200) // keep the same 200-row cap intact after a bulk restore
                            Log.d(TAG, "pullLedgerIfLocalEmpty: restored ${eventsArr.length()} events from sync code")
                            true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "pullLedgerIfLocalEmpty exception: ${e.message}")
                false
            }
            onResult?.invoke(restored)
        }
    }

    // ── Manual (de)serialization ──────────────────────────────────────────
    // BehaviorEventEntity isn't a kotlinx.serialization @Serializable type — Room
    // entities in this codebase generally aren't — so this mirrors
    // OutcomeLedgerSyncManager's manual JSONObject approach. id (the Room
    // autogenerate primary key) is deliberately NOT round-tripped: a restored row
    // gets a fresh local id via @Insert's autoGenerate, same as any other insert.
    private fun BehaviorEventEntity.toJson(): JSONObject = JSONObject().apply {
        put("subject", subject)
        put("topic", topic)
        put("state", state)
        put("timestamp", timestamp)
        put("focusMinutes", focusMinutes)
        put("checksPassed", checksPassed)
        put("checksMissed", checksMissed)
        put("taskType", taskType)
        put("distractionApp", distractionApp ?: JSONObject.NULL)
    }

    private fun JSONObject.toBehaviorEventEntity(): BehaviorEventEntity? = try {
        BehaviorEventEntity(
            subject = getString("subject"),
            topic = getString("topic"),
            state = getString("state"),
            timestamp = getLong("timestamp"),
            focusMinutes = optInt("focusMinutes", 0),
            checksPassed = optInt("checksPassed", 0),
            checksMissed = optInt("checksMissed", 0),
            taskType = optString("taskType", "OTHER"),
            distractionApp = if (isNull("distractionApp")) null else optString("distractionApp", null)
        )
    } catch (e: Exception) {
        Log.w(TAG, "toBehaviorEventEntity parse failure: ${e.message}")
        null
    }
}
