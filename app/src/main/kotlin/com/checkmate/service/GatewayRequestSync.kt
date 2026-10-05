package com.checkmate.service

import android.content.Context
import android.util.Log
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.ConsultationProfile
import com.checkmate.learning.engine.LearningDecisionEngine
import com.checkmate.learning.model.LearningIds
import com.checkmate.learning.student.StudentModelBuilder
import com.checkmate.planner.FreeSlotCalculator
import com.checkmate.planner.PlanStore
import com.checkmate.planner.intervention.LearningInterventionOrchestrator
import com.checkmate.planner.intervention.PolicyValidator
import com.checkmate.planner.model.StudyTask
import com.checkmate.planner.model.TaskState
import com.checkmate.planner.model.TaskType
import com.checkmate.testmate.TestmateApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Calendar
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
 * SUPPORTED: `request_repair`; `create_task` with task_type repair or qbank_practice (through
 * the orchestrator) or `study` (a plain study block, see [decideCreateStudyTask]); and
 * `schedule_task` (move a PENDING task in today's plan to a start time, see [decideScheduleTask]).
 * `study` and `schedule_task` do not go through the learning-intervention orchestrator, so they
 * carry their own guards, all enforced HERE on the phone no matter what the server sent: today
 * only, 10..120 minutes, inside the student's study window, never over a blocked slot or
 * another unfinished task, at most [MAX_AI_TASKS_PER_DAY] AI-created tasks a day, and no
 * duplicate of a task that is still open.
 *
 * FORCE: a `study` create or a `schedule_task` may carry `force: true` (the AI only sends it
 * when the student explicitly asked for that exact time). Force skips ONLY the study-window and
 * blocked-slot checks. It never skips: a time already in the past, running past midnight, or an
 * overlap with another unfinished task (two tasks in one slot breaks the plan).
 *
 * Slot rejections are split into distinct policy decisions so the AI can tell whether `force`
 * would help: OVERLAP (force cannot fix), BLOCKED_SLOT and OUTSIDE_WINDOW (force fixes),
 * TIME_IN_PAST, PAST_MIDNIGHT and INVALID_TIME (force cannot fix).
 * NOT SUPPORTED (reported as rejected, with a message): `dismiss_task` (no policy action exists
 * to remove a task from outside the app), `create_task` for retention/revision (those must go
 * through RetentionTaskLedger's session loop), and any due_date other than today.
 *
 * Runs from [GatewaySyncWorker] after the snapshot push (every ~30 min and at app start) AND from
 * [GatewaySyncScheduler.startFastPolling] every few seconds while the app process is alive, so a
 * request is normally picked up within seconds. Both paths share [processLock], so a request is
 * never decided twice by two overlapping runs.
 */
object GatewayRequestSync {

    private const val TAG = "GatewayRequestSync"
    private const val KEY_HANDLED = "gateway_request_results"
    private const val MAX_HANDLED = 20
    private const val MAX_MESSAGE = 280
    private const val MINUTES_PER_DAY = 24 * 60

    /** One processing pass at a time: the fast poller and the WorkManager worker must not race. */
    private val processLock = Mutex()

    /** Judgment calls, not syllabus facts: a repair with no question target gets 30 minutes; a
     *  task with a question target gets 1 minute per question (NEET pace), clamped to
     *  [PolicyValidator.MIN_DURATION_MINUTES]..[MAX_TASK_MINUTES]. */
    const val DEFAULT_REPAIR_MINUTES = 30
    const val MINUTES_PER_QUESTION = 1
    const val MAX_TASK_MINUTES = 90

    /** Plain study blocks and rescheduling (not question-target tasks). */
    const val MIN_STUDY_MINUTES = 10
    const val MAX_STUDY_MINUTES = 120
    const val DEFAULT_STUDY_MINUTES = 45
    const val MAX_AI_TASKS_PER_DAY = 15
    /** Stored in StudyTask.rationale so AI-created tasks can be counted (and told apart) later. */
    private const val AI_TASK_MARKER = "Requested by an AI assistant"
    private val HHMM = Regex("""^([01]\d|2[0-3]):[0-5]\d$""")

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
        processLock.withLock { processPendingLocked(context) }
    }

    private suspend fun processPendingLocked(context: Context): Int {
        val base = baseUrl() ?: return 0
        val token = token() ?: return 0
        val pending = pull(base, token) ?: return 0
        if (pending.isEmpty()) return 0

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
        return reported
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
        "schedule_task" -> decideScheduleTask(req)
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
        if (p.optStr("task_type") == "study") return decideCreateStudyTask(req)
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

    // ── plain study tasks and rescheduling (phone-side guards, see class doc) ───────

    private fun decideCreateStudyTask(req: Pending): Decision {
        val p = req.payload
        val subject = p.optStr("subject") ?: return Decision(false, "The request has no subject.", "MALFORMED")
        val topic = p.optStr("topic") ?: return Decision(false, "The request has no topic.", "MALFORMED")
        val minutes = p.optInt("duration_minutes", DEFAULT_STUDY_MINUTES)
        if (minutes !in MIN_STUDY_MINUTES..MAX_STUDY_MINUTES) {
            return Decision(false, "A study task must be $MIN_STUDY_MINUTES-$MAX_STUDY_MINUTES minutes (got $minutes).", "DURATION_OUT_OF_RANGE")
        }
        val taskType = when (p.optStr("study_type")) {
            "LECTURE" -> TaskType.LECTURE
            "REVISION" -> TaskType.REVISION
            "READING" -> TaskType.READING
            else -> TaskType.PRACTICE
        }

        val today = PlanStore.getTodayTasksSnapshot_Sync()
        if (today.count { it.rationale.startsWith(AI_TASK_MARKER) } >= MAX_AI_TASKS_PER_DAY) {
            return Decision(false, "Already $MAX_AI_TASKS_PER_DAY AI-requested tasks today; Checkmate won't add more until tomorrow.", "DAILY_AI_TASK_LIMIT")
        }
        val open = today.any {
            it.state != TaskState.DONE && it.state != TaskState.SKIPPED &&
                norm(it.subject) == norm(subject) && norm(it.topic) == norm(topic)
        }
        if (open) return Decision(false, "Today's plan already has an open task for $subject: $topic.", "ALREADY_PLANNED")

        val force = p.optBoolean("force", false)
        val requested = p.optStr("scheduled_start_time")
        var forcedNote = ""
        val start: String? = if (requested != null) {
            slotProblem(requested, minutes, ignoreTaskId = null, force = force)?.let { return Decision(false, it.message, it.code) }
            if (force) forcedNote = describeForce(requested, minutes, ignoreTaskId = null)
            requested
        } else {
            firstFreeStartFromNow(minutes, ignoreTaskId = null)
        }

        val task = StudyTask(
            subject = subject,
            topic = topic,
            durationMinutes = minutes,
            isCustom = true, // student-visible like a typed task, so a replan keeps it and its duration stays editable
            taskType = taskType,
            scheduledStartTime = start,
            rationale = "$AI_TASK_MARKER: ${req.reason}".take(300)
        )
        PlanStore.addCustomTask(task)
        val where = when {
            requested != null -> "at $requested"
            start != null -> "at $start (first free slot)"
            else -> "unscheduled (no free ${minutes}-minute slot left today)"
        }
        return Decision(true, "Added $subject: $topic ($minutes min) to today's plan $where.$forcedNote", "ALLOW", task.id)
    }

    private fun decideScheduleTask(req: Pending): Decision {
        val p = req.payload
        val id = p.optStr("task_id") ?: return Decision(false, "The request has no task_id.", "MALFORMED")
        val start = p.optStr("scheduled_start_time") ?: return Decision(false, "The request has no start time.", "MALFORMED")
        if (!HHMM.matches(start)) return Decision(false, "'$start' is not a valid HH:mm time.", "INVALID_TIME")

        val task = PlanStore.getTodayTasksSnapshot_Sync().firstOrNull { it.id == id }
            ?: return Decision(false, "No task with that id in today's plan (it may belong to another day or have been removed).", "UNKNOWN_TASK_ID")
        if (task.state != TaskState.PENDING) {
            return Decision(false, "That task is ${task.state}; only pending tasks can be moved.", "TASK_NOT_PENDING")
        }

        val requestedMinutes = if (p.has("duration_minutes") && !p.isNull("duration_minutes")) p.optInt("duration_minutes", 0) else null
        if (requestedMinutes != null) {
            if (requestedMinutes !in MIN_STUDY_MINUTES..MAX_STUDY_MINUTES) {
                return Decision(false, "Duration must be $MIN_STUDY_MINUTES-$MAX_STUDY_MINUTES minutes (got $requestedMinutes).", "DURATION_OUT_OF_RANGE")
            }
            if (task.learningIntent != null && requestedMinutes != task.durationMinutes) {
                return Decision(false, "Checkmate sized this repair task itself; ask only for a new time, not a new length.", "DURATION_LOCKED")
            }
        }
        val minutes = requestedMinutes ?: task.durationMinutes

        if (start == task.scheduledStartTime && minutes == task.durationMinutes) {
            return Decision(false, "That task is already scheduled for $start.", "NO_OP_ALREADY_APPLIED")
        }
        val force = p.optBoolean("force", false)
        slotProblem(start, minutes, ignoreTaskId = task.id, force = force)?.let { return Decision(false, it.message, it.code) }
        val forcedNote = if (force) describeForce(start, minutes, ignoreTaskId = task.id) else ""

        PlanStore.updateTaskScheduleAndDuration(task.id, start, minutes)
        return Decision(true, "Moved ${task.subject}: ${task.topic} to $start for $minutes min.$forcedNote", "ALLOW", task.id)
    }

    private fun nowMinute(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    private data class SlotProblem(val code: String, val message: String)

    /** The student's study window today in minutes-from-midnight (same prefs and defaults as the planner). */
    private fun studyWindow(): Pair<Int, Int> {
        val startStr = CheckmatePrefs.getString("study_start", "06:00") ?: "06:00"
        val endStr = CheckmatePrefs.getString("study_end", "22:00") ?: "22:00"
        val start = FreeSlotCalculator.parseTimeOrNull(startStr) ?: (6 * 60)
        val end = FreeSlotCalculator.parseTimeOrNull(endStr) ?: (22 * 60)
        return start to end
    }

    /** Unfinished tasks that already have a time, as (start, end) minutes. */
    private fun occupiedRanges(ignoreTaskId: String?): List<Pair<Int, Int>> =
        PlanStore.getTodayTasksSnapshot_Sync().mapNotNull { t ->
            if (t.id == ignoreTaskId || t.state == TaskState.DONE || t.state == TaskState.SKIPPED) return@mapNotNull null
            val s = t.scheduledStartTime?.let { FreeSlotCalculator.parseTimeOrNull(it) } ?: return@mapNotNull null
            s to (s + t.durationMinutes)
        }

    /** Free time today: study window minus blocked slots minus every OTHER unfinished task that already has a time. */
    private fun freeSlotsToday(ignoreTaskId: String?): List<FreeSlotCalculator.FreeSlot> {
        // Same prefs and defaults HomeViewModel.findNextFreeSlot reads, so both agree on "today's window".
        val studyStart = CheckmatePrefs.getString("study_start", "06:00") ?: "06:00"
        val studyEnd = CheckmatePrefs.getString("study_end", "22:00") ?: "22:00"
        val free = FreeSlotCalculator.computeFreeSlots(ConsultationProfile.load().blockedSlots, studyStart, studyEnd)
        return FreeSlotCalculator.subtractOccupied(free, occupiedRanges(ignoreTaskId))
    }

    /** Free slots with the part that already passed cut off, for "first slot from now" searches. */
    private fun freeSlotsFromNow(ignoreTaskId: String?): List<FreeSlotCalculator.FreeSlot> {
        val now = nowMinute()
        return freeSlotsToday(ignoreTaskId).mapNotNull {
            val s = maxOf(it.startMinute, now)
            if (it.endMinute > s) FreeSlotCalculator.FreeSlot(s, it.endMinute) else null
        }
    }

    private fun firstFreeStartFromNow(minutes: Int, ignoreTaskId: String?): String? =
        FreeSlotCalculator.firstFitStart(minutes, freeSlotsFromNow(ignoreTaskId))?.let { FreeSlotCalculator.formatMinutes(it) }

    /**
     * Null when [startHHmm] for [minutes] is allowed today; otherwise a coded problem with a message the
     * AI can act on. With [force] the study window and blocked slots are ignored; the past, midnight and
     * other unfinished tasks are still enforced.
     */
    private fun slotProblem(startHHmm: String, minutes: Int, ignoreTaskId: String?, force: Boolean): SlotProblem? {
        if (!HHMM.matches(startHHmm)) return SlotProblem("INVALID_TIME", "'$startHHmm' is not a valid HH:mm time.")
        val start = FreeSlotCalculator.parseTimeOrNull(startHHmm)
            ?: return SlotProblem("INVALID_TIME", "'$startHHmm' is not a valid HH:mm time.")
        val now = nowMinute()
        if (start <= now) {
            return SlotProblem(
                "TIME_IN_PAST",
                "$startHHmm is not ahead of the current time (${FreeSlotCalculator.formatMinutes(now)}); choose a later time today."
            )
        }
        val end = start + minutes
        if (end > MINUTES_PER_DAY) {
            return SlotProblem("PAST_MIDNIGHT", "$startHHmm for $minutes min would run past midnight; Checkmate only plans today.")
        }
        val range = "$startHHmm-${FreeSlotCalculator.formatMinutes(end)}"
        val suggestion = firstFreeStartFromNow(minutes, ignoreTaskId)
        val suggestionText = if (suggestion != null) " First free $minutes-minute slot today starts at $suggestion."
        else " There is no free $minutes-minute slot left today."

        // Overlap with another unfinished task: never overridable.
        if (occupiedRanges(ignoreTaskId).any { start < it.second && end > it.first }) {
            return SlotProblem("OVERLAP", "$range overlaps another unfinished task in today's plan; force cannot override that.$suggestionText")
        }
        if (force) return null

        val blocked = blockedOverlap(start, end)
        if (blocked != null) {
            return SlotProblem("BLOCKED_SLOT", "$range overlaps the blocked slot '$blocked'. Resend with force=true to override it.$suggestionText")
        }
        val (ws, we) = studyWindow()
        if (start < ws || end > we) {
            return SlotProblem(
                "OUTSIDE_WINDOW",
                "$range is outside the student's study window (${FreeSlotCalculator.formatMinutes(ws)}-${FreeSlotCalculator.formatMinutes(we)}). Resend with force=true to override it.$suggestionText"
            )
        }
        return null
    }

    /** Label of the first blocked slot overlapping [start, end), or null. Unparseable slots are ignored. */
    private fun blockedOverlap(start: Int, end: Int): String? {
        val hit = ConsultationProfile.load().blockedSlots.firstOrNull { slot ->
            val s = FreeSlotCalculator.parseTimeOrNull(slot.startTime) ?: return@firstOrNull false
            val e = FreeSlotCalculator.parseTimeOrNull(slot.endTime) ?: return@firstOrNull false
            e > s && start < e && end > s
        } ?: return null
        return hit.label.ifBlank { "blocked time" }
    }

    /** Suffix for an accepted forced request that really did bypass the window or a blocked slot. */
    private fun describeForce(startHHmm: String, minutes: Int, ignoreTaskId: String?): String {
        val code = slotProblem(startHHmm, minutes, ignoreTaskId, force = false)?.code
        return if (code == "OUTSIDE_WINDOW" || code == "BLOCKED_SLOT") " (forced: study window / blocked slot overridden)" else ""
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
