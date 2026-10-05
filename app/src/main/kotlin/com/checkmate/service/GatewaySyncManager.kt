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
import com.checkmate.learning.repository.LearningDatabase
import com.checkmate.learning.student.StudentModelBuilder
import com.checkmate.planner.PlanStore
import com.checkmate.planner.intervention.InterventionDatabase
import com.checkmate.planner.model.TaskState
import com.checkmate.psyche.BehaviorLedger
import com.checkmate.psyche.db.BehaviorDatabase
import com.checkmate.testmate.TestmateApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
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
 * Six snapshots: student_model, interventions, today_plan, plus knowledge_graph (the seeded
 * prerequisite edges, re-sent at most once a day unless they change), behavior (AGGREGATE
 * numbers only) and task_history (the last two weeks of saved daily plans).
 *
 * What is deliberately NOT pushed: the raw behavior event ledger (only counts derived from
 * it), which app distracted the student, screen/app-usage data, profile/consultation text,
 * API keys, anything outside the snapshots below. The server re-normalizes every field it
 * accepts, so this file is the first filter, not the only one.
 *
 * Fire-and-forget background sync: a periodic WorkManager job (network required) plus one
 * immediate run at app start. Each snapshot is pushed independently — one failing (e.g. an
 * empty student model on a fresh install) never blocks the others.
 */
object GatewaySync {

    private const val TAG = "GatewaySync"
    private const val MAX_INTERVENTIONS = 300
    private const val MAX_GRAPH_EDGES = 4000
    private const val MAX_GRAPH_NODES = 1500
    private const val HISTORY_DAYS = 14
    private const val MAX_TASKS_PER_DAY = 80
    // The prerequisite graph is seeded once and rarely changes: skip re-sending an identical
    // payload for this long (any change goes out on the next sync regardless).
    private const val GRAPH_RESEND_MS = 24L * 60 * 60 * 1000
    private const val PREF_GRAPH_HASH = "gateway_kg_hash"
    private const val PREF_GRAPH_PUSHED_MS = "gateway_kg_pushed_ms"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    enum class PushResult { OK, SKIPPED_NOT_CONFIGURED, REJECTED, RETRYABLE_FAILURE }

    /** Pushes every snapshot. Returns the worst outcome so the Worker can decide to retry. */
    suspend fun pushAll(context: Context): PushResult = withContext(Dispatchers.IO) {
        val base = baseUrl()
        val token = token()
        if (base == null || token == null) return@withContext PushResult.SKIPPED_NOT_CONFIGURED

        // `map` is inline, so the suspend builders are legal here (a lambda stored in a
        // listOf(...) is NOT a suspend lambda — that was the compile error).
        val kinds = listOf("student_model", "interventions", "today_plan", "knowledge_graph", "behavior", "task_history")
        val results = kinds.map { kind ->
            try {
                val payload = when (kind) {
                    "student_model" -> buildStudentModel(context)
                    "interventions" -> buildInterventions(context)
                    "today_plan" -> buildTodayPlan()
                    "knowledge_graph" -> buildKnowledgeGraph(context)
                    "behavior" -> buildBehavior(context)
                    else -> buildTaskHistory()
                }
                if (kind == "knowledge_graph") pushKnowledgeGraph(base, token, payload) else push(base, token, kind, payload)
            } catch (e: CancellationException) {
                throw e
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

    /** Pushes only today's plan (+ constraints): one small PUT, cheap enough to repeat every couple of minutes. */
    suspend fun pushTodayPlanOnly(): PushResult = withContext(Dispatchers.IO) {
        val base = baseUrl()
        val token = token()
        if (base == null || token == null) return@withContext PushResult.SKIPPED_NOT_CONFIGURED
        try {
            push(base, token, "today_plan", buildTodayPlan())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "today_plan quick push failed: ${e.message}")
            PushResult.RETRYABLE_FAILURE
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
            // Planning constraints for the AI (study window, blocked slots, free gaps, quota, rollover).
            // Never lets a constraints failure block the plan snapshot itself.
            try {
                put("constraints", GatewayRequestSync.constraintsSnapshot())
            } catch (e: Exception) {
                Log.w(TAG, "constraints snapshot failed: ${e.message}")
            }
        }
    }

    // ── knowledge_graph (prerequisite edges) ──────────────────────────────────
    private suspend fun buildKnowledgeGraph(context: Context): JSONObject {
        val db = LearningDatabase.getInstance(context)
        val edges = db.conceptDependencyDao().getAll().take(MAX_GRAPH_EDGES)
        val ids = edges.flatMap { listOf(it.conceptId, it.prerequisiteConceptId) }.distinct().take(MAX_GRAPH_NODES)
        // chunked: SQLite caps the number of bound variables in one IN (...) query.
        val concepts = ids.chunked(400).flatMap { db.conceptDao().getByIds(it) }
        val known = concepts.map { it.id }.toSet()

        val nodes = JSONArray()
        for (c in concepts) {
            nodes.put(
                JSONObject().apply {
                    putStr("concept_id", c.id)
                    putStr("subject", c.subject)
                    putStr("chapter", c.chapter)
                    putStr("topic", c.topic)
                }
            )
        }
        val edgeArr = JSONArray()
        for (e in edges) {
            if (e.conceptId in known && e.prerequisiteConceptId in known) {
                edgeArr.put(JSONObject().put("concept_id", e.conceptId).put("prerequisite_id", e.prerequisiteConceptId))
            }
        }
        return JSONObject().put("nodes", nodes).put("edges", edgeArr)
    }

    /** Skips an unchanged graph for [GRAPH_RESEND_MS]; records the hash only after a successful push. */
    private fun pushKnowledgeGraph(base: String, token: String, payload: JSONObject): PushResult {
        val hash = payload.toString().hashCode().toString()
        val lastHash = CheckmatePrefs.getString(PREF_GRAPH_HASH, null)
        val lastPushed = CheckmatePrefs.getLong(PREF_GRAPH_PUSHED_MS, 0L)
        if (hash == lastHash && System.currentTimeMillis() - lastPushed < GRAPH_RESEND_MS) return PushResult.OK
        val result = push(base, token, "knowledge_graph", payload)
        if (result == PushResult.OK) {
            CheckmatePrefs.putString(PREF_GRAPH_HASH, hash)
            CheckmatePrefs.putLong(PREF_GRAPH_PUSHED_MS, System.currentTimeMillis())
        }
        return result
    }

    // ── behavior (aggregates only: counts and rates, never individual events) ──
    private suspend fun buildBehavior(context: Context): JSONObject {
        val snap = BehaviorLedger.getSnapshot()
        val events = BehaviorDatabase.getInstance(context).behaviorEventDao().getAll()
        val now = System.currentTimeMillis()
        fun count(state: String, days: Int) = events.count { it.state == state && it.timestamp > now - days * DAY_MS }

        val patterns = JSONArray()
        for (p in snap.subjectPatterns) {
            patterns.put(JSONObject().putStr("subject", p.subject).putStr("task_type", p.taskType).put("occurrences", p.occurrences))
        }
        return JSONObject().apply {
            put("streak_days", snap.streakDays)
            put("consecutive_missed_days", PlanStore.getConsecutiveMissedDays())
            put("week_completion_percent", PlanStore.getWeekCompletionPercent())
            put("recent_skip_rate_percent", snap.recentSkipRatePercent)
            put("total_skips_7d", snap.totalSkips7d)
            put("today_completed", snap.todayCompleted.size)
            put(
                "attention",
                JSONObject()
                    .put("checks_passed", snap.attentionChecksPassed)
                    .put("checks_missed", snap.attentionChecksMissed)
                    .put("avg_focus_minutes", snap.avgFocusMinutes)
            )
            put("skip_patterns", patterns)
            put(
                "ledger",
                JSONObject()
                    .put("events", events.size)
                    .put("done_7d", count("DONE", 7))
                    .put("skipped_7d", count("SKIPPED", 7))
                    .put("done_30d", count("DONE", 30))
                    .put("skipped_30d", count("SKIPPED", 30))
                    .put("focus_minutes_7d", events.filter { it.state == "DONE" && it.timestamp > now - 7 * DAY_MS }.sumOf { it.focusMinutes })
            )
        }
    }

    // ── task_history (saved daily plans, newest first, today included) ────────
    private fun buildTaskHistory(): JSONObject {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        val days = JSONArray()
        repeat(HISTORY_DAYS) {
            val tasks = PlanStore.loadDay(PlanStore.keyForDay(cal))
            val rows = JSONArray()
            tasks.take(MAX_TASKS_PER_DAY).forEach { t ->
                rows.put(
                    JSONObject().apply {
                        putStr("id", t.id)
                        putStr("subject", t.subject)
                        putStr("topic", t.topic)
                        put("task_type", t.taskType.name)
                        put("state", t.state.name)
                        put("duration_minutes", t.durationMinutes)
                        put("focus_minutes", t.focusMinutes)
                        putStr("learning_intent", t.learningIntent)
                        putStr("completed_status", t.completedStatus)
                    }
                )
            }
            days.put(
                JSONObject().apply {
                    put("date", fmt.format(cal.time))
                    put("planned", tasks.size)
                    put("done", tasks.count { it.state == TaskState.DONE })
                    put("skipped", tasks.count { it.state == TaskState.SKIPPED })
                    put("unfinished", tasks.count { it.state != TaskState.DONE && it.state != TaskState.SKIPPED })
                    put("focus_minutes", tasks.sumOf { it.focusMinutes })
                    put("tasks", rows)
                }
            )
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return JSONObject().put("days", days)
    }
}

/** WorkManager entry point. Network-constrained; only retries failures that could succeed on retry. */
class GatewaySyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val pushed = GatewaySync.pushAll(applicationContext)

        // After the snapshot push, so the server's view is fresh when the AI reads an outcome.
        // Independent of the push result and never fails the job: a request-queue problem must not
        // block snapshots, and anything unanswered is simply picked up on the next run.
        try {
            GatewayRequestSync.processPending(applicationContext)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("GatewaySyncWorker", "request queue pass failed: ${e.message}")
        }

        return when (pushed) {
            GatewaySync.PushResult.RETRYABLE_FAILURE -> if (runAttemptCount < 3) Result.retry() else Result.success()
            // Not configured / rejected: nothing to gain from retrying; the next periodic run re-checks.
            else -> Result.success()
        }
    }
}

object GatewaySyncScheduler {

    private const val PERIODIC_WORK = "checkmate_gateway_sync"
    private const val STARTUP_WORK = "checkmate_gateway_sync_startup"
    private const val NOW_WORK = "checkmate_gateway_sync_now"
    private const val TAG = "GatewaySyncScheduler"

    /**
     * How often the in-process poller asks the gateway for new AI requests. One small GET per tick
     * (it returns nothing when the queue is empty), so a request is picked up within ~this long.
     * WorkManager can't go below 15 min, hence the separate loop.
     */
    private const val FAST_POLL_SECONDS = 15L

    /** Doubles after a failed/offline pass up to this cap, so a dead network doesn't burn battery. */
    private const val FAST_POLL_MAX_BACKOFF_SECONDS = 120L

    /** How often the poller refreshes today's plan + constraints on the server, so reads are never ~30 min stale. */
    private const val PLAN_PUSH_SECONDS = 120L

    private val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var pollJob: Job? = null

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
        startFastPolling(context)
    }

    /**
     * Near-instant pickup of AI requests while the app process is alive (the study guard's foreground
     * service keeps it alive). Idempotent. After any pass that actually answered a request it also
     * queues a snapshot push, so the AI's next read of today's plan shows the change.
     */
    fun startFastPolling(context: Context) {
        if (pollJob?.isActive == true) return
        val app = context.applicationContext
        pollJob = pollScope.launch {
            var wait = FAST_POLL_SECONDS
            var lastPlanPush = 0L
            while (isActive) {
                try {
                    val answered = GatewayRequestSync.processPending(app)
                    if (answered > 0) syncNow(app)
                    if (System.currentTimeMillis() - lastPlanPush >= PLAN_PUSH_SECONDS * 1000L) {
                        GatewaySync.pushTodayPlanOnly()
                        lastPlanPush = System.currentTimeMillis()
                    }
                    wait = FAST_POLL_SECONDS
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "fast poll failed: ${e.message}")
                    wait = (wait * 2).coerceAtMost(FAST_POLL_MAX_BACKOFF_SECONDS)
                }
                delay(wait * 1000L)
            }
        }
    }

    fun stopFastPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /** One immediate snapshot push + request pass (a "Sync now" button can call this too). */
    fun syncNow(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<GatewaySyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
        )
    }

    fun cancel(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(PERIODIC_WORK)
        wm.cancelUniqueWork(STARTUP_WORK)
        wm.cancelUniqueWork(NOW_WORK)
        stopFastPolling()
    }
}
