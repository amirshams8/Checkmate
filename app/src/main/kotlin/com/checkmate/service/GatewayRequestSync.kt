package com.checkmate.service

import android.content.Context
import android.util.Log
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.ConsultationProfile
import com.checkmate.learning.engine.LearningDecisionEngine
import com.checkmate.learning.model.LearningIds
import com.checkmate.learning.student.StudentModelBuilder
import com.checkmate.planner.intervention.LearningInterventionOrchestrator
import com.checkmate.planner.intervention.PolicyValidator
import com.checkmate.testmate.TestmateApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Checkmate MCP gateway — request queue, phone side (FINAL_PLAN Phase 3, "controlled writes").
 *
 * An external AI holding a `checkmate:plan` token can only QUEUE a request on Testmate
 * (`gateway_requests`). This pulls those requests (`GET /api/gateway/requests`, same base URL
 * + device token as [GatewaySync]), runs each through the SAME path Checkmate's own learning
 * engine uses, and reports the verdict back (`POST /api/gateway/requests/{id}`):
 *
 * ```
 * request -> CandidateIntervention -> LearningInterventionOrchestrator.executeTopCandidate
 *         -> LearningInterventionMapper -> PolicyValidator -> TaskEscrow -> ActionExecutor
 * ```
 *
 * "AI recommends, code decides": nothing here writes a task directly. Every existing guard
 * still applies (one unresolved gap task at a time, covered concepts, escrow, policy), and a
 * refusal is reported back with the real reason so the AI can see why.
 *
 * SUPPORTED: `request_repair`, and `create_task` with task_type repair or qbank_practice.
 * NOT SUPPORTED (reported as rejected, with a message): `dismiss_task` (no policy action exists
 * to remove a task from outside the app), `create_task` for retention/revision (those must go
 * through RetentionTaskLedger's session loop), and any due_date other than today (a
 * CreateTaskRequest only plans today).
 *
 * Runs from [GatewaySyncWorker] after the snapshot push (every ~30 min and at app start), so a
 * request is picked up within one sync interval, not instantly.
 */
object GatewayRequestSync {

    private const val TAG = "GatewayRequestSync"
    private const val KEY_HANDLED = "gateway_request_results"
    private const val MAX_HANDLED = 20
    private const val MAX_MESSAGE = 280

    /** Judgment calls, not syllabus facts: a repair with no question target gets 30 minutes; a
     *  task with a question target gets 1 minute per question (NEET pace), clamped to
     *  [PolicyValidator.MIN_DURATION_MINUTES]..[MAX_TASK_MINUTES]. */
    const val DEFAULT_REPAIR_MINUTES = 30
    const val MINUTES_PER_QUESTION = 1
    const val MAX_TASK_MINUTES = 90

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private data class Pending(val id: String, val kind: String, val payload: JSONObject, val reason: String)

    data class Decision(
        val accepted: Boolean,
        val message: String,
        val policyDecision: String,
        val taskId: String? = null
    )

    /** Pulls, decides and reports every pending request. Returns how many were reported. */
    suspend fun processPending(context: Context): Int = withContext(Dispatchers.IO) {
        val base = baseUrl() ?: return@withContext 0
        val token = token() ?: return@withContext 0
        val pending = pull(base, token) ?: return@withContext 0
        if (pending.isEmpty()) return@withContext 0

        var reported = 0
        for (req in pending) {
            // A request already decided on a previous run whose report never reached the server
            // is re-reported as-is. It must NOT be run again: that could create a second task.
            val decision = handled(req.id) ?: try {
                decide(context, req).also { remember(req.id, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "request ${req.id} (${req.kind}) failed: ${e.message}", e)
                Decision(false, "Checkmate hit an internal error handling this request.", "INTERNAL_ERROR")
                    .also { remember(req.id, it) }
            }
            if (report(base, token, req.id, decision)) reported += 1
        }
        reported
    }

    // ── decisions ───────────────────────────────────────────────────────────────
    private suspend fun decide(context: Context, req: Pending): Decision = when (req.kind) {
        "request_repair" -> runCandidate(
            context = context,
            chapter = req.payload.optStr("chapter"),
            subject = req.payload.optStr("subject"),
            topic = req.payload.optStr("topic"),
            concept = req.payload.optStr("concept"),
            minutes = DEFAULT_REPAIR_MINUTES,
            chapterLevelOnly = false,
            reason = req.reason
        )
        "create_task" -> decideCreateTask(context, req)
        "dismiss_task" -> Decision(
            false,
            "Checkmate does not allow tasks to be dismissed from outside the app.",
            "UNSUPPORTED_KIND"
        )
        else -> Decision(false, "Unknown request kind.", "UNSUPPORTED_KIND")
    }

    private suspend fun decideCreateTask(context: Context, req: Pending): Decision {
        val p = req.payload
        val due = p.optStr("due_date")
        if (due != null && due != LocalDate.now().toString()) {
            return Decision(false, "Checkmate only plans today; a task for $due can't be scheduled.", "UNSUPPORTED_DUE_DATE")
        }
        val minutes = if (p.has("target_questions") && !p.isNull("target_questions")) {
            (p.optInt("target_questions", 10) * MINUTES_PER_QUESTION)
                .coerceIn(PolicyValidator.MIN_DURATION_MINUTES, MAX_TASK_MINUTES)
        } else {
            DEFAULT_REPAIR_MINUTES
        }
        return when (val type = p.optStr("task_type")) {
            "repair" -> runCandidate(context, p.optStr("chapter"), p.optStr("subject"), null, null, minutes, false, req.reason)
            "qbank_practice" -> runCandidate(context, p.optStr("chapter"), p.optStr("subject"), null, null, minutes, true, req.reason)
            else -> Decision(
                false,
                "Checkmate can't create '$type' tasks from outside the app; retention and revision checks are scheduled by its own engine.",
                "UNSUPPORTED_TASK_TYPE"
            )
        }
    }

    /**
     * Builds one [LearningDecisionEngine.CandidateIntervention] and hands it to the orchestrator
     * as a single-candidate report. With a matching concept it is REPAIR_CONCEPT, which enters
     * GapTaskLedger and so gets the normal Testmate session/evidence loop; otherwise it is the
     * chapter-level ASSIGN_TARGETED_SET (no concept id, so no ledger tracking, same as the
     * engine's own chapter-level candidates).
     */
    private suspend fun runCandidate(
        context: Context,
        chapter: String?,
        subject: String?,
        topic: String?,
        concept: String?,
        minutes: Int,
        chapterLevelOnly: Boolean,
        reason: String
    ): Decision {
        if (chapter.isNullOrBlank()) return Decision(false, "The request has no chapter.", "MALFORMED")

        val model = StudentModelBuilder.build(context)
        val inChapter = model.concepts.values.filter { sameChapter(it.chapter, chapter) }

        val resolvedSubject = subject?.takeIf { it.isNotBlank() }
            ?: inChapter.firstNotNullOfOrNull { it.subject?.takeIf { s -> s.isNotBlank() } }
            ?: return Decision(
                false,
                "Checkmate can't tell which subject '$chapter' belongs to.",
                "SUBJECT_UNKNOWN"
            )

        // Weakest concept in the chapter unless the request named one (matched by topic/concept name).
        val wanted = concept ?: topic
        val matched = if (chapterLevelOnly) null else {
            wanted?.let { w -> inChapter.firstOrNull { norm(it.topic) == norm(w) || norm(it.conceptId) == norm(w) } }
                ?: inChapter.minByOrNull { it.mastery }
        }

        val candidate = LearningDecisionEngine.CandidateIntervention(
            intent = if (matched != null) {
                LearningDecisionEngine.LearningInterventionIntent.REPAIR_CONCEPT
            } else {
                LearningDecisionEngine.LearningInterventionIntent.ASSIGN_TARGETED_SET
            },
            conceptId = matched?.conceptId,
            subject = resolvedSubject,
            chapter = matched?.chapter ?: chapter,
            topic = matched?.topic ?: topic,
            durationMinutes = minutes,
            expectedGain = 0.0,
            priorityScore = 0.0,
            rationale = "Requested by an AI assistant: $reason"
        )

        val now = System.currentTimeMillis()
        val report = LearningDecisionEngine.DecisionReport(
            studentId = LearningIds.LOCAL_STUDENT_ID,
            examType = ConsultationProfile.load().examTarget,
            generatedAt = now,
            candidates = listOf(candidate)
        )
        val result = LearningInterventionOrchestrator.from(context)
            .executeTopCandidate(report, now, excludeReplanDay = true)
        return toDecision(result)
    }

    private fun toDecision(result: LearningInterventionOrchestrator.OrchestrationResult): Decision =
        when (val outcome = result.outcome) {
            is LearningInterventionOrchestrator.OrchestrationOutcome.Created ->
                Decision(true, "Task created on the phone.", "ALLOW", outcome.taskId)
            is LearningInterventionOrchestrator.OrchestrationOutcome.NoExecutableCandidate -> {
                val first = result.rejections.firstOrNull()
                Decision(false, first?.detail ?: "No task was created.", sourceName(first?.source))
            }
        }

    private fun sourceName(source: LearningInterventionOrchestrator.RejectionSource?): String = when (source) {
        null -> "NONE"
        is LearningInterventionOrchestrator.RejectionSource.NotMappable -> "NOT_MAPPABLE"
        is LearningInterventionOrchestrator.RejectionSource.PolicyRejected -> "POLICY_${source.reason.name}"
        is LearningInterventionOrchestrator.RejectionSource.TaskIdCollision -> "TASK_ID_COLLISION"
        is LearningInterventionOrchestrator.RejectionSource.AlreadyCovered -> "ALREADY_COVERED"
        is LearningInterventionOrchestrator.RejectionSource.AlreadyActive -> "ALREADY_ACTIVE"
        is LearningInterventionOrchestrator.RejectionSource.AlreadyReplannedToday -> "ALREADY_REPLANNED_TODAY"
        is LearningInterventionOrchestrator.RejectionSource.NoOpAlreadyApplied -> "NO_OP_ALREADY_APPLIED"
    }

    // ── name matching (mirrors lib/ft-schedule.ts normalizeChapter on the server) ──
    private fun norm(s: String?): String =
        s.orEmpty().lowercase().replace("&", "and").replace(Regex("[^a-z0-9]+"), "")

    /** Equal after normalizing, or one is a leading part of the other (>= 8 chars so short
     *  names only ever match exactly). The server sends Testmate's canonical chapter name;
     *  the phone's concept rows carry whatever the report used. */
    private fun sameChapter(a: String?, b: String?): Boolean {
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val (short, long) = if (x.length <= y.length) x to y else y to x
        return short.length >= 8 && long.startsWith(short)
    }

    // ── HTTP ────────────────────────────────────────────────────────────────────
    private fun pull(base: String, token: String): List<Pending>? {
        val request = Request.Builder()
            .url("$base/api/gateway/requests")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "pull: HTTP ${resp.code}")
                    return null
                }
                val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("requests") ?: return emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("request_id", "")
                    if (id.isBlank()) return@mapNotNull null
                    Pending(
                        id = id,
                        kind = o.optString("kind", ""),
                        payload = o.optJSONObject("payload") ?: JSONObject(),
                        reason = o.optString("reason", "")
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "pull failed: ${e.message}")
            null
        }
    }

    private fun report(base: String, token: String, id: String, d: Decision): Boolean {
        val body = JSONObject().apply {
            put("status", if (d.accepted) "accepted" else "rejected")
            put(
                "result",
                JSONObject().apply {
                    put("message", d.message.take(MAX_MESSAGE))
                    put("policy_decision", d.policyDecision)
                    d.taskId?.let { put("task_id", it) }
                }
            )
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$base/api/gateway/requests/$id")
            .addHeader("Authorization", "Bearer $token")
            .post(body)
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                Log.d(TAG, "report $id: HTTP ${resp.code}")
                resp.isSuccessful
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "report $id failed: ${e.message}")
            false
        }
    }

    // ── idempotency memory (last few decisions, so a lost report is re-sent, never re-run) ──
    private fun handled(id: String): Decision? {
        val arr = loadHandled()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) {
                return Decision(
                    accepted = o.optBoolean("accepted"),
                    message = o.optString("message"),
                    policyDecision = o.optString("policy"),
                    taskId = o.optString("task_id").takeIf { it.isNotBlank() }
                )
            }
        }
        return null
    }

    private fun remember(id: String, d: Decision) {
        val old = loadHandled()
        val kept = JSONArray()
        val start = maxOf(0, old.length() - (MAX_HANDLED - 1))
        for (i in start until old.length()) kept.put(old.get(i))
        kept.put(
            JSONObject()
                .put("id", id)
                .put("accepted", d.accepted)
                .put("message", d.message)
                .put("policy", d.policyDecision)
                .put("task_id", d.taskId ?: "")
        )
        CheckmatePrefs.putString(KEY_HANDLED, kept.toString())
    }

    private fun loadHandled(): JSONArray = try {
        JSONArray(CheckmatePrefs.getString(KEY_HANDLED, null) ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }

    // ── config (same prefs + allow-list as GatewaySync / TestmateApi) ──
    private fun baseUrl(): String? {
        val saved = CheckmatePrefs.getString(TestmateApi.PREF_BASE_URL, null)?.trim()?.trimEnd('/')
            ?.takeIf { it.isNotBlank() } ?: return null
        if (!TestmateApi.isAllowedBaseUrl(saved)) {
            Log.w(TAG, "Stored Testmate base URL failed the allow-list check — not syncing requests")
            return null
        }
        return saved
    }

    private fun token(): String? =
        CheckmatePrefs.getString(TestmateApi.PREF_TOKEN, null)?.trim()?.takeIf { it.isNotBlank() }

    private fun JSONObject.optStr(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).trim().takeIf { it.isNotBlank() && it != "null" } else null
}
