package com.checkmate.service

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.ConsultationProfile
import com.checkmate.learning.engine.MasteryEngine
import com.checkmate.learning.model.StudentModel
import com.checkmate.learning.student.StudentModelBuilder
import com.checkmate.planner.PlanStore
import com.checkmate.planner.intervention.InterventionDatabase
import com.checkmate.testmate.TestmateApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Checkmate MCP gateway — read-projection push (FINAL_PLAN "Option C — hybrid").
 *
 * Checkmate stays the source of truth for the student model, the intervention history and
 * today's plan. This pushes curated SNAPSHOTS of those three things to Testmate's
 * `PUT /api/gateway/projection`, using the SAME base URL + device token the rest of
 * Checkmate already uses for Testmate (Settings -> Test Platform). The MCP gateway on the
 * Testmate side only ever reads those stored snapshots — it never reaches into this phone,
 * and keeps answering (with a freshness stamp) while the phone is offline.
 *
 * What is deliberately NOT pushed: the raw behavior event ledger, screen/app-usage data,
 * profile/consultation text, API keys, anything outside the three snapshots below. The
 * server re-normalizes every field it accepts, so this file is the first filter, not the
 * only one.
 *
 * Fire-and-forget background sync: a periodic WorkManager job (network required) plus one
 * immediate run at app start. Each snapshot is pushed independently — one failing (e.g. an
 * empty student model on a fresh install) never blocks the others.
 */
object GatewaySync {

    private const val TAG = "GatewaySync"
    private const val MAX_INTERVENTIONS = 300

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    enum class PushResult { OK, SKIPPED_NOT_CONFIGURED, REJECTED, RETRYABLE_FAILURE }

    /** Pushes all three snapshots. Returns the worst outcome so the Worker can decide to retry. */
    suspend fun pushAll(context: Context): PushResult = withContext(Dispatchers.IO) {
        val base = baseUrl()
        val token = token()
        if (base == null || token == null) return@withContext PushResult.SKIPPED_NOT_CONFIGURED

        val results = listOf(
            "student_model" to { buildStudentModel(context) },
            "interventions" to { buildInterventions(context) },
            "today_plan" to { buildTodayPlan() }
        ).map { (kind, build) ->
            try {
                push(base, token, kind, build())
            } catch (e: Exception) {
                Log.w(TAG, "push $kind failed to build/send: ${e.message}")
                PushResult.RETRYABLE_FAILURE
            }
        }
        when {
            PushResult.REJECTED in results -> PushResult.REJECTED
            PushResult.RETRYABLE_FAILURE in results -> PushResult.RETRYABLE_FAILURE
            else -> PushResult.OK
        }
    }

    // ── config (same prefs + allow-list as TestmateApi; never accepts an arbitrary host) ──
    private fun baseUrl(): String? {
        val saved = CheckmatePrefs.getString(TestmateApi.PREF_BASE_URL, null)?.trim()?.trimEnd('/')
            ?.takeIf { it.isNotBlank() } ?: return null
        if (!TestmateApi.isAllowedBaseUrl(saved)) {
            Log.w(TAG, "Stored Testmate base URL failed the allow-list check — not syncing")
            return null
        }
        return saved
    }

    private fun token(): String? =
        CheckmatePrefs.getString(TestmateApi.PREF_TOKEN, null)?.trim()?.takeIf { it.isNotBlank() }

    private fun push(base: String, token: String, kind: String, payload: JSONObject): PushResult {
        payload.put("generated_ms", System.currentTimeMillis())
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$base/api/gateway/projection?kind=$kind")
            .addHeader("Authorization", "Bearer $token")
            .put(body)
            .build()
        client.newCall(request).execute().use { resp ->
            Log.d(TAG, "push $kind: HTTP ${resp.code}")
            return when {
                resp.isSuccessful -> PushResult.OK
                // 400/401/403/413: retrying the identical payload/credentials can't succeed.
                resp.code in 400..499 && resp.code != 408 && resp.code != 429 -> PushResult.REJECTED
                else -> PushResult.RETRYABLE_FAILURE
            }
        }
    }

    // org.json throws on NaN/Infinity — skip non-finite numbers instead of failing the whole push.
    private fun JSONObject.putNum(key: String, v: Double?): JSONObject {
        if (v != null && v.isFinite()) put(key, v)
        return this
    }

    private fun JSONObject.putStr(key: String, v: String?): JSONObject {
        if (!v.isNullOrBlank() && v != "null") put(key, v)
        return this
    }

    // ── student_model ─────────────────────────────────────────────────────────
    private suspend fun buildStudentModel(context: Context): JSONObject {
        val model: StudentModel = StudentModelBuilder.build(context)
        val root = JSONObject()
        root.putStr("exam", runCatching { ConsultationProfile.load().examTarget }.getOrNull())
        root.put("model_version", model.modelVersion)
        root.put(
            "overall",
            JSONObject().apply {
                put("concepts_tracked", model.overall.conceptsTracked)
                put("concepts_mastered", model.overall.conceptsMastered)
                put("concepts_weak", model.overall.conceptsWeak)
                putNum("average_mastery", model.overall.averageMastery)
                put("total_attempts", model.overall.totalAttempts)
                put("unresolved_error_count", model.overall.unresolvedErrorCount)
            }
        )

        val concepts = JSONArray()
        for (c in model.concepts.values) {
            val issues = JSONArray()
            for (p in c.prerequisiteIssues) {
                issues.put(JSONObject().putStr("subject", p.subject).putStr("chapter", p.chapter).putStr("topic", p.topic))
            }
            concepts.put(
                JSONObject().apply {
                    putStr("concept_id", c.conceptId)
                    putStr("subject", c.subject)
                    putStr("chapter", c.chapter)
                    putStr("topic", c.topic)
                    putNum("mastery", c.mastery)
                    putNum("forgetting_risk", c.forgettingRisk)
                    put("retention_decision", c.retentionDecision.name)
                    put("attempt_count", c.attemptCount)
                    putNum("recent_accuracy", c.recentAccuracy)
                    putNum("lifetime_accuracy", c.lifetimeAccuracy)
                    put("error_count", c.errorCount)
                    c.lastSeen?.let { put("last_seen_ms", it) }
                    put("weak", c.mastery < MasteryEngine.MASTERY_THRESHOLD)
                    put("prerequisite_issues", issues)
                }
            )
        }
        root.put("concepts", concepts)

        val errors = JSONArray()
        for (e in model.unresolvedErrors) {
            val concept = model.concepts[e.conceptId]
            errors.put(
                JSONObject().apply {
                    putStr("concept_id", e.conceptId)
                    putStr("subject", concept?.subject)
                    putStr("chapter", concept?.chapter)
                    putStr("topic", concept?.topic)
                    putStr("error_type", e.errorType)
                    put("occurrences", e.occurrences)
                    put("first_seen_ms", e.firstSeen)
                    put("last_seen_ms", e.lastSeen)
                }
            )
        }
        root.put("unresolved_errors", errors)
        return root
    }

    // ── interventions (Outcome Ledger) ────────────────────────────────────────
    private suspend fun buildInterventions(context: Context): JSONObject {
        val all = InterventionDatabase.getInstance(context).outcomeLedgerDao().getAll()
        val entries = JSONArray()
        all.sortedByDescending { it.resolvedAt }.take(MAX_INTERVENTIONS).forEach { e ->
            entries.put(
                JSONObject().apply {
                    putStr("transaction_id", e.transactionId)
                    putStr("task_id", e.taskId)
                    put("trigger_type", e.triggerType.name)
                    put("terminal_state", e.terminalState.name)
                    put("provenance", e.provenance.name)
                    put("attempt_count", e.attemptCount)
                    put("resolved_ms", e.resolvedAt)
                    putStr("outcome", e.outcome)
                    putStr("failure_reason", e.failureReason)
                }
            )
        }
        return JSONObject().put("entries", entries)
    }

    // ── today_plan ────────────────────────────────────────────────────────────
    private fun buildTodayPlan(): JSONObject {
        val tasks = JSONArray()
        PlanStore.getTodayTasksSnapshot_Sync().forEach { t ->
            tasks.put(
                JSONObject().apply {
                    putStr("id", t.id)
                    putStr("subject", t.subject)
                    putStr("topic", t.topic)
                    put("duration_minutes", t.durationMinutes)
                    put("state", t.state.name)
                    put("task_type", t.taskType.name)
                    putStr("scheduled_start_time", t.scheduledStartTime)
                    putStr("completed_status", t.completedStatus)
                    putStr("learning_intent", t.learningIntent)
                    putStr("concept_id", t.conceptId)
                    putStr("rationale", t.rationale)
                    put("focus_minutes", t.focusMinutes)
                }
            )
        }
        return JSONObject().apply {
            putStr("day_key", PlanStore.currentDayKey())
            put("completion_percent", PlanStore.getTodayCompletionPercent())
            put("tasks", tasks)
        }
    }
}

/** WorkManager entry point. Network-constrained; only retries failures that could succeed on retry. */
class GatewaySyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        when (GatewaySync.pushAll(applicationContext)) {
            GatewaySync.PushResult.RETRYABLE_FAILURE -> if (runAttemptCount < 3) Result.retry() else Result.success()
            // Not configured / rejected: nothing to gain from retrying; the next periodic run re-checks.
            else -> Result.success()
        }
}

object GatewaySyncScheduler {

    private const val PERIODIC_WORK = "checkmate_gateway_sync"
    private const val STARTUP_WORK = "checkmate_gateway_sync_startup"

    /** WorkManager's periodic floor is 15 min; 30 keeps the snapshot fresh without wasting battery. */
    private const val INTERVAL_MINUTES = 30L

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val wm = WorkManager.getInstance(context)

        wm.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<GatewaySyncWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
        )
        // One immediate run per app start so a fresh install / new token has data right away.
        wm.enqueueUniqueWork(
            STARTUP_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<GatewaySyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
        )
    }

    fun cancel(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(PERIODIC_WORK)
        wm.cancelUniqueWork(STARTUP_WORK)
    }
}
