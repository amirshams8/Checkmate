package com.checkmate.service

import android.content.Context
import android.util.Log
import com.checkmate.core.CheckmatePrefs
import com.checkmate.planner.intervention.InterventionDatabase
import com.checkmate.planner.intervention.InterventionState
import com.checkmate.planner.intervention.InterventionTriggerType
import com.checkmate.planner.intervention.OutcomeLedgerEntry
import com.checkmate.planner.intervention.OutcomeProvenance
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
 * OutcomeLedgerSyncManager — backs up/restores the Outcome Ledger (Step 12's
 * [OutcomeLedgerEntry] rows, one per resolved intervention transaction) to the same
 * Cloudflare Worker + KV that [ProfileSyncManager]/[TaskSyncManager] already use, keyed by
 * the same "sync_code" set in Settings -> Task Sync.
 *
 * This is a reinstall-recovery backup, matching [ProfileSyncManager]'s model — NOT live
 * multi-device sync like [TaskSyncManager]. The ledger is a local behavioral history, not
 * something two devices edit concurrently, so there's no "which write is newer" question
 * to resolve, only "did this device already have history, or is it starting fresh":
 *   - [pushLedger] — whole-table push, fire-and-forget, called once daily (piggybacking on
 *     [GuardianNotifier.sendEndOfDaySummary] — the ledger only grows by a handful of rows a
 *     day, so a nightly snapshot is enough; this isn't a live sync target). At the scale
 *     [OutcomeLedgerEntry]'s own doc describes (a few thousand rows/year at most), pushing
 *     the whole table each time is simpler and cheaper to reason about than a delta sync.
 *   - [pullLedgerIfLocalEmpty] — restores ONLY if this device's local ledger table is
 *     genuinely empty (fresh install/reinstall) — never merges into or overwrites a device
 *     that already has its own history, same non-destructive posture as
 *     [ProfileSyncManager.pullProfileIfLocalEmpty]. Called once at [com.checkmate.CheckmateApp]
 *     startup.
 *
 * Room DAO access is suspend, unlike [ProfileSyncManager]'s synchronous-only CheckmatePrefs
 * reads/writes, so — same posture as [GuardianNotifier]'s own module-level `scope` — this
 * object owns its own [CoroutineScope] rather than pushing coroutine management onto every
 * call site. Both public functions are plain, fire-and-forget calls; callers don't wrap
 * them in Thread{} or launch{} themselves.
 */
object OutcomeLedgerSyncManager {

    private const val TAG = "OutcomeLedgerSyncManager"
    private const val OUTCOMES_URL = "https://steep-band-1bd0.amirshamse8.workers.dev/outcomes"

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
     * Pushes the entire local ledger up under the configured sync code. No-op if no sync
     * code is set. Fire-and-forget — launches on this object's own IO-scoped coroutine and
     * returns immediately, same call-site shape as [ProfileSyncManager.pushProfile] even
     * though the underlying DAO call is suspend.
     */
    fun pushLedger(context: Context) {
        val code = syncCode() ?: return
        val appContext = context.applicationContext
        scope.launch {
            try {
                val dao = InterventionDatabase.getInstance(appContext).outcomeLedgerDao()
                val entries = dao.getAll()
                val entriesJson = JSONArray().apply { entries.forEach { put(it.toJson()) } }
                val payload = JSONObject().apply {
                    put("code", code)
                    put("updatedAt", System.currentTimeMillis())
                    put("entries", entriesJson)
                }
                val body = payload.toString().toRequestBody("application/json".toMediaType())
                val response = client.newCall(Request.Builder().url(OUTCOMES_URL).post(body).build()).execute()
                Log.d(TAG, "pushLedger: ${response.code} (${entries.size} entries)")
                response.close()
            } catch (e: Exception) {
                Log.w(TAG, "pushLedger exception: ${e.message}")
            }
        }
    }

    /**
     * Restores the synced ledger into the local Room table, but ONLY if this device's local
     * table is genuinely empty — never merges into or overwrites real local history.
     * Fire-and-forget like [pushLedger]; pass [onResult] if a caller needs to know whether a
     * restore happened (CheckmateApp's startup call doesn't).
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
                val dao = InterventionDatabase.getInstance(appContext).outcomeLedgerDao()
                if (dao.getAll().isNotEmpty()) {
                    false // real local history already exists — don't clobber it
                } else {
                    val request = Request.Builder().url("$OUTCOMES_URL?code=$code").get().build()
                    val response = client.newCall(request).execute()
                    val bodyStr = response.body?.string()
                    response.close()
                    if (!response.isSuccessful || bodyStr.isNullOrBlank()) {
                        false
                    } else {
                        val entriesArr = JSONObject(bodyStr).optJSONArray("entries")
                        if (entriesArr == null || entriesArr.length() == 0) {
                            false
                        } else {
                            for (i in 0 until entriesArr.length()) {
                                entriesArr.optJSONObject(i)?.toOutcomeLedgerEntry()?.let { dao.upsert(it) }
                            }
                            Log.d(TAG, "pullLedgerIfLocalEmpty: restored ${entriesArr.length()} entries from sync code")
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
    // OutcomeLedgerEntry isn't a kotlinx.serialization @Serializable type — Room entities in
    // this codebase generally aren't (TaskSyncManager's StudyTask is the exception) — so
    // this mirrors ProfileSyncManager's manual JSONObject approach rather than pulling
    // serialization annotations into a Step-12 file that's otherwise settled.

    private fun OutcomeLedgerEntry.toJson(): JSONObject = JSONObject().apply {
        put("transactionId", transactionId)
        put("taskId", taskId)
        put("triggerType", triggerType.name)
        put("terminalState", terminalState.name)
        put("provenance", provenance.name)
        put("attemptCount", attemptCount)
        put("resolvedAt", resolvedAt)
        put("outcome", outcome ?: JSONObject.NULL)
        put("failureReason", failureReason ?: JSONObject.NULL)
    }

    private fun JSONObject.toOutcomeLedgerEntry(): OutcomeLedgerEntry? = try {
        OutcomeLedgerEntry(
            transactionId = getString("transactionId"),
            taskId = getString("taskId"),
            triggerType = InterventionTriggerType.valueOf(getString("triggerType")),
            terminalState = InterventionState.valueOf(getString("terminalState")),
            provenance = OutcomeProvenance.valueOf(getString("provenance")),
            attemptCount = optInt("attemptCount", 0),
            resolvedAt = getLong("resolvedAt"),
            outcome = if (isNull("outcome")) null else optString("outcome", null),
            failureReason = if (isNull("failureReason")) null else optString("failureReason", null)
        )
    } catch (e: Exception) {
        // Unknown enum constant (older/newer app version) or missing required field —
        // skip just this row rather than failing the whole restore.
        Log.w(TAG, "Skipping unparseable ledger entry: ${e.message}")
        null
    }
}
