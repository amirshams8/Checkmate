package com.checkmate.service

import android.content.Context
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.ConsultationProfile
import com.checkmate.core.DailyCheckIn
import com.checkmate.core.DebugTrail
import com.checkmate.planner.PlanStore
import com.checkmate.planner.model.StudyTask
import com.checkmate.planner.model.TaskState
import com.checkmate.planner.model.TaskType
import com.checkmate.testmate.TestmateApi
import com.checkmate.testmate.TestmateDailyTargetOutcome
import com.checkmate.testmate.TestmateQbankPracticeOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Daily Target of qs as per FT schedule should be scheduled on a Checkmate task —
 * continue practice qbank via API for all 4 subjects" (chat history). The P0c
 * bridge between Testmate's server-computed, FT-schedule-boosted daily coverage
 * target ([TestmateApi.fetchDailyTarget], lib/daily-target-engine.ts on the
 * Testmate side) and an actual Q-bank practice SESSION the student can tap
 * straight into from Checkmate — one per NEET subject that has a target today.
 *
 * Deliberately a separate object from [GapTaskManager]: that one drives the P0b
 * gap-REPAIR loop (POST /api/tests/targeted, WRONG_SKIPPED pool, keyed by
 * intervention_id off GapTaskLedger's active concept). This one drives Q-bank
 * COVERAGE practice ([TestmateApi.startQbankPractice], NEW pool, no
 * intervention_id at all — see that route's own boundary note) off the daily
 * per-subject breakdown instead. The two pipelines never touch the same session,
 * same separation the Testmate side already enforces server-side.
 *
 * Once per calendar day (mirrors [GapTaskManager.generateIfNeeded]'s gate and its
 * "only mark today done once something was actually decided, not on every empty/
 * failed attempt" bugfix — an unconfigured exam target or a transient network
 * error retries hourly via [RETRY_INTERVAL_MS] instead of locking out the rest of
 * the day):
 *
 *  1. [TestmateApi.fetchDailyTarget] — GET /api/qbank/daily-target.
 *  2. For each of the 4 NEET subjects (Physics/Chemistry/Botany/Zoology) with
 *     `subject_breakdown[].coverage_target > 0` and no session already recorded
 *     today, pick that subject's #1 chapter off `coverage_gaps` — already sorted
 *     most-urgent-first server-side (soonest FT study deadline, then most
 *     remaining questions), and filtered to chapters with `remaining_questions > 0`
 *     (see lib/daily-target-engine.ts's own doc) — but `remaining_questions` there
 *     is a TARGET count, not proof real question rows exist. See the BUGFIX in
 *     step 2's own code below (`unseededChapters`) for why a coverage_gaps entry is
 *     NOT, on its own, guaranteed to have real questions behind it.
 *  3. [TestmateApi.startQbankPractice] — POST /api/qbank/practice with
 *     `pool = NEW`, `question_count = ` that subject's coverage target.
 *     Idempotent per chapter/pool on the Testmate side (a still-live session for
 *     the same chapter is handed back instead of forking a duplicate), so a retry
 *     of this same call — e.g. from [StatsScreen] re-entering the screen — is safe.
 *  4. Persists `{subject, chapter, sessionId, testId, questionCount, taskId}` per
 *     subject so [todaysSessions] can drive a "Continue <Subject> Q-bank practice"
 *     entry — see StatsScreen's "Today's Q-bank Targets" card, which opens the
 *     session via the existing `test_web/{sessionId}` route (same one P0b targeted
 *     tests use).
 *  5. FIX (Tasks tab wiring): also creates a real [StudyTask] via
 *     [PlanStore.createTask] for the session — mirrors [GapTaskManager]/
 *     [RetentionCheckManager]'s own direct-StudyTask pattern (a plain PlanStore
 *     write, no LearningInterventionOrchestrator negotiation needed since there's
 *     no repair/escalation logic attached to a Q-bank coverage session). Until now
 *     this object only ever wrote to [PREF_SESSIONS_JSON] and was read solely by
 *     [StatsScreen]'s "Today's Q-bank Targets" card — [todaysSessions] was never
 *     wired into [PlanStore], so the daily Q-bank target never showed up on the
 *     Home tasks tab at all. Not a regression, a missing wire.
 *
 * DAILY CHECK-IN FLOOD: the chapter the student picks per subject in the Daily Check-In (Step 1) overrides
 * the server's "#1 gap" choice for that subject. On check-in submit ([applyCheckInTopicsAsync]) — and
 * retried from [generateIfNeeded] — each selected (subject, chapter) starts a Q-bank session via
 * [TestmateApi.startQbankPractice] with `checkinChapter`, [CHECKIN_FLOOD_QUESTIONS] questions, drawn from
 * every bank question tagged for that check-in chapter (Testmate questions.checkin_chapter; tagged by
 * scripts/tag-checkin-chapters.ts). Those sessions are [SOURCE_CHECKIN]; the auto flow below skips any
 * subject a live check-in session already covers (Biology covers Botany + Zoology) and retires an
 * untouched auto session for it. Re-doing the check-in with another chapter retires the old pending one.
 *
 * A subject with a target but nothing in `coverage_gaps` for it (syllabus_chapters
 * not yet seeded server-side, or that subject's bank is fully drained) is skipped
 * for the day rather than retried every cycle — there's no chapter to send.
 */
object QbankDailyTaskManager {

    private const val TAG = "QbankDailyTaskManager"

    // Same once-a-day-gate-only-marked-on-real-outcome pattern as
    // GapTaskManager's own PREF_LAST_ATTEMPT_MS bugfix note — see that object's
    // class doc for why an empty/failed run must not lock out the rest of the day.
    private const val PREF_LAST_GENERATED_DAY = "qbank_daily_last_generated_day"
    private const val PREF_LAST_ATTEMPT_MS = "qbank_daily_last_attempt_ms"
    private const val RETRY_INTERVAL_MS = 60L * 60_000L

    private const val PREF_SESSIONS_JSON = "qbank_daily_sessions_json"
    private const val PREF_SESSIONS_DAY = "qbank_daily_sessions_day"

    // Persisted the same way GapTaskManager.PREF_TESTMATE_LAST_ERROR is — visible in
    // Settings → Test Platform without needing adb logcat. Cleared on the next
    // successful attempt.
    const val PREF_LAST_ERROR = "qbank_daily_last_error"
    const val PREF_LAST_ERROR_AT = "qbank_daily_last_error_at"

    private val SUBJECTS = listOf("Physics", "Chemistry", "Botany", "Zoology")

    const val SOURCE_AUTO = "auto"
    const val SOURCE_CHECKIN = "checkin"

    /** How many questions a check-in chapter floods into Q-bank (the server default is 20; "flood" = double+). */
    private const val CHECKIN_FLOOD_QUESTIONS = 40

    // Which (subject|chapter|checkInCompletedAt) check-in sessions were already created today. Keyed by the
    // check-in's own completion time so deleting a flooded task sticks (the 15-min loop must not resurrect
    // it) while re-submitting the check-in is a fresh request.
    private const val PREF_CHECKIN_APPLIED_JSON = "qbank_checkin_applied_json"
    private const val PREF_CHECKIN_APPLIED_DAY = "qbank_checkin_applied_day"
    private const val CHECKIN_RETRY_MS = 30L * 60_000L

    private val checkInMutex = Mutex()
    private val checkInLastAttempt = mutableMapOf<String, Long>()
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Roughly NEET MCQ pacing (~1 min/question) — same reasoning
    // RetentionCheckManager.RETENTION_QUESTION_COUNT's own duration note applies
    // (a fixed, honest estimate rather than a real timer): this only sizes the
    // StudyTask card on Home, it does not gate or clock the Testmate session
    // itself. Floored so a tiny coverage_target still reads as a real task.
    private const val MINUTES_PER_QUESTION = 1
    private const val MIN_TASK_MINUTES = 10

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private fun todayKey(): String = dayFormat.format(Date())

    // Same reactive-change-signal shape as GapTaskLedger.version/
    // RetentionTaskLedger.version — this object's writes happen from
    // ReminderService's background loop (and from StatsScreen re-entry), with no
    // observable of their own otherwise; HomeViewModel needs to notice a session
    // (and its StudyTask) becoming available the same way it already does for
    // those two ledgers.
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()
    private fun bumpVersion() { _version.update { it + 1 } }

    data class QbankDailySession(
        val subject: String,
        val chapter: String,
        val sessionId: String,
        val testId: String,
        val questionCount: Int,
        val taskId: String? = null, // null only for sessions persisted before this fix; see StatsScreen's own fallback
        val source: String = "auto", // SOURCE_AUTO (daily-target gap) or SOURCE_CHECKIN (picked in Daily Check-In)
        val topic: String? = null // check-in syllabus topic the session is narrowed to; null = whole chapter
    )

    /**
     * Today's already-created sessions, one per subject that got one — for
     * StatsScreen to render "Continue <Subject>" entries. Empty before the first
     * successful [generateIfNeeded] run of the day, or once a new day rolls over
     * (the stored list is day-stamped via [PREF_SESSIONS_DAY], same guard style as
     * TestmateWebScreen's PREF_HISTORY).
     */
    fun todaysSessions(): List<QbankDailySession> {
        if (CheckmatePrefs.getString(PREF_SESSIONS_DAY, null) != todayKey()) return emptyList()
        val raw = CheckmatePrefs.getString(PREF_SESSIONS_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                QbankDailySession(
                    subject = o.optString("subject"),
                    chapter = o.optString("chapter"),
                    sessionId = o.optString("sessionId"),
                    testId = o.optString("testId"),
                    questionCount = o.optInt("questionCount"),
                    taskId = o.optString("taskId", "").takeIf { it.isNotBlank() },
                    source = o.optString("source", "auto"),
                    topic = o.optString("topic", "").takeIf { it.isNotBlank() }
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveSessions(sessions: List<QbankDailySession>) {
        val arr = JSONArray()
        sessions.forEach { s ->
            arr.put(JSONObject().apply {
                put("subject", s.subject)
                put("chapter", s.chapter)
                put("sessionId", s.sessionId)
                put("testId", s.testId)
                put("questionCount", s.questionCount)
                put("taskId", s.taskId ?: "")
                put("source", s.source)
                put("topic", s.topic ?: "")
            })
        }
        CheckmatePrefs.putString(PREF_SESSIONS_JSON, arr.toString())
        CheckmatePrefs.putString(PREF_SESSIONS_DAY, todayKey())
        bumpVersion()
    }

    /**
     * BUGFIX (delete-then-regenerate stuck no-op): deleting a "Q-bank: <chapter>"
     * StudyTask via the Home X button used to leave that subject's entry in
     * [PREF_SESSIONS_JSON] untouched — [generateIfNeededInternal]'s
     * `existing.containsKey(subject)` check then kept treating the subject as
     * "already has a session today" for the rest of the day, so even a
     * force-refresh right after deleting couldn't create a new one for it.
     * Confirmed live: after deleting the Physics task and forcing a refresh, no
     * new session was created for Physics. Mirrors
     * [GapTaskLedger.releaseIfActiveTask]'s own shape — called from
     * [HomeViewModel.removeTask] alongside it, whenever the removed task matches a
     * stored session's taskId, so deleting really does make the subject eligible
     * again the same day instead of only from tomorrow.
     */
    fun releaseIfSessionTask(taskId: String) {
        val sessions = todaysSessions()
        val match = sessions.firstOrNull { it.taskId == taskId } ?: return
        DebugTrail.d(TAG, "releaseIfSessionTask: releasing subject=${match.subject} " +
            "chapter=${match.chapter} task=$taskId")
        saveSessions(sessions.filterNot { it.taskId == taskId })
    }

    /**
     * Entry point — called from [ReminderService]'s 15-min loop, and also directly
     * from [com.checkmate.ui.stats.StatsScreen] on screen entry so a session
     * created just now shows up without waiting for the next service cycle. Both
     * call sites are safe to call redundantly: the day-key gate and per-subject
     * "already have one today" check below make a repeat call a cheap no-op once
     * today's sessions exist.
     */
    /**
     * Entry point — called from [ReminderService]'s 15-min loop, and also directly
     * from [com.checkmate.ui.stats.StatsScreen] on screen entry so a session
     * created just now shows up without waiting for the next service cycle. Both
     * call sites are safe to call redundantly: the day-key gate and per-subject
     * "already have one today" check below make a repeat call a cheap no-op once
     * today's sessions exist.
     */
    suspend fun generateIfNeeded(context: Context) {
        // Check-in picks first, so the auto flow below sees them and skips the subjects they cover.
        applyCheckInTopicsInternal(force = false)
        generateIfNeededInternal(context, force = false)
    }

    /**
     * Fire-and-forget: called when the Daily Check-In is submitted. Runs on its own scope (not the
     * screen's), so leaving the check-in screen right away cannot cancel it.
     */
    fun applyCheckInTopicsAsync() {
        bgScope.launch {
            try {
                applyCheckInTopicsInternal(force = true)
            } catch (e: Exception) {
                DebugTrail.e(TAG, "applyCheckInTopics failed: ${e.message}")
            }
        }
    }

    /**
     * Manual override for Settings → Test Platform's "Force refresh Q-bank targets"
     * button — same shape as [GapTaskManager.forceGenerateNow]: bypasses the
     * once-a-day gate AND [RETRY_INTERVAL_MS]'s hourly throttle so a student (or a
     * debugging session) doesn't have to wait for tomorrow's day-rollover, or for an
     * hour to pass, to see a fresh [DebugTrail] run of this exact pipeline. Runs
     * through the SAME [generateIfNeededInternal] body — including the per-subject
     * `existing.containsKey` skip and the unseeded-chapters guard further down — so
     * forcing never re-creates a session for a subject that already has one today; it
     * only re-attempts subjects that were skipped or failed.
     */
    // BUGFIX (forced run dies silently): Settings launches this from a composable
    // rememberCoroutineScope, which is cancelled the moment the student leaves the
    // screen. Confirmed live (16:57:32): "ENTER force=true" was logged and then NOTHING
    // — no fetch error, no per-subject line — while the student had already moved on to
    // Home. Same signature at 12:32:50/12:32:57. NonCancellable lets a manual force run
    // finish (it is bounded by the Testmate client's own timeouts).
    suspend fun forceGenerateNow(context: Context) =
        withContext(NonCancellable) {
            applyCheckInTopicsInternal(force = true)
            generateIfNeededInternal(context, force = true)
        }

    private suspend fun generateIfNeededInternal(context: Context, force: Boolean) {
        val today = todayKey()
        // BUGFIX (day gate hides orphaned sessions): once anything was generated today,
        // PREF_LAST_GENERATED_DAY made every automatic 15-min cycle return in 0ms (see
        // trail: "QbankDailyTaskManager.generateIfNeeded ok in 0ms" all afternoon), so the
        // orphaned-session release below could only ever run from the manual force. A
        // session whose task is gone from today's plan must reopen the gate (the hourly
        // RETRY_INTERVAL_MS throttle below still applies).
        val liveIds = PlanStore.todayTasks.value.map { it.id }.toSet()
        val hasOrphanedSession = todaysSessions().any { it.taskId != null && it.taskId !in liveIds }
        if (!force && !hasOrphanedSession &&
            CheckmatePrefs.getString(PREF_LAST_GENERATED_DAY, null) == today) return

        val lastAttempt = CheckmatePrefs.getLong(PREF_LAST_ATTEMPT_MS, 0L)
        if (!force && System.currentTimeMillis() - lastAttempt < RETRY_INTERVAL_MS) return
        CheckmatePrefs.putLong(PREF_LAST_ATTEMPT_MS, System.currentTimeMillis())

        DebugTrail.d(TAG, "generateIfNeeded: ENTER day=$today force=$force")

        when (val outcome = TestmateApi.fetchDailyTarget()) {
            is TestmateDailyTargetOutcome.Error -> {
                DebugTrail.e(TAG, "generateIfNeeded: fetchDailyTarget failed: ${outcome.message}")
                recordError(outcome.message)
            }
            is TestmateDailyTargetOutcome.Success -> {
                val target = outcome.target
                DebugTrail.d(TAG, "generateIfNeeded: target fetched configured=${target.configured} " +
                    "gaps=${target.coverageGaps?.size ?: 0} sessionsToday=${todaysSessions().size}")
                if (!target.configured) {
                    // Not an error — the student just hasn't set exam_date/syllabus_deadline
                    // on Testmate yet (POST /api/qbank/exam-target). Nothing to schedule
                    // until they do; retry hourly rather than daily since this is cheap and
                    // the student may configure it mid-session.
                    DebugTrail.d(TAG, "generateIfNeeded: exam target not configured on Testmate yet — skip")
                    return
                }

                // coverage_gaps is already sorted most-urgent-first server-side (soonest
                // FT study deadline, then most remaining) — taking the first match per
                // subject is exactly "that subject's #1 gap", no re-sorting needed here.
                //
                // BUGFIX (unseeded chapters getting scheduled): coverage_gaps' own
                // remainingQuestions is a TARGET count (how many the syllabus/FT schedule
                // says should be asked), not a signal that real question rows actually
                // exist for that chapter — this object's own earlier doc comment ("already
                // filtered ... to chapters with remaining_questions > 0") was true of that
                // target count but was wrongly read as "therefore has content." Confirmed
                // live: Structure of Atom, and Redox/Electrochemistry the day before, each
                // got a session + StudyTask created despite having zero seeded qbank rows —
                // coverage_gaps.first() was trusted with no cross-check against content
                // availability at all. The SAME daily-target payload already carries that
                // signal on a different field — TestmateUpcomingFt.chaptersWithoutQuestions
                // (chapters_without_questions), scoped per upcoming FT — it was just never
                // read here. Union it across every upcoming test and walk each subject's
                // (already urgency-sorted) gap list past any chapter in that set instead of
                // blindly taking .first() — the same "known-empty, skip silently" pattern
                // QBankSelector.selectTodayQuestions already established for the equivalent
                // local-DB case (empty qbankPoolByChapter result), just applied here against
                // the server's own signal instead of a local query.
                val unseededChapters: Set<String> = target.upcomingTests
                    .flatMap { it.chaptersWithoutQuestions }
                    .map { it.trim().lowercase() }
                    .toSet()

                val topGapChapterBySubject: Map<String, String> = (target.coverageGaps ?: emptyList())
                    .groupBy { it.subject }
                    .mapNotNull { (subject, gaps) ->
                        // BUGFIX (practice 422 "No unattempted questions left"): also skip a
                        // chapter with nothing drawable — coverage_gaps counts imported test
                        // papers (tag_source = 'manual'/'untagged') as coverage, but Q-bank
                        // practice only draws 'coaching_module' rows, and
                        // chapters_without_questions above only flags chapters with ZERO rows of
                        // any source. Confirmed live: Zoology's #1 gap "Body Fluids and
                        // Circulation" passed the unseeded check, then Testmate refused to build
                        // the test. The server's own doc on remaining_coaching_module_questions
                        // says callers should pick the first gap with this > 0. null (older
                        // server) = unknown, so it isn't filtered.
                        gaps.firstOrNull {
                            it.chapter.trim().lowercase() !in unseededChapters &&
                                (it.remainingCoachingModuleQuestions ?: 1) > 0
                        }?.let { subject to it.chapter }
                    }
                    .toMap()

                // BUGFIX (orphaned session blocks regeneration): a stored session whose
                // StudyTask is no longer in today's plan is stale. Confirmed live: "Generate
                // Today's Plan" -> savePlan() -> PlanStore.saveTodayTasks() replaces the whole
                // task list without going through HomeViewModel.removeTask(), so
                // releaseIfSessionTask never ran and the Physics session (task 6afe4225...)
                // kept the subject looking "already done today" after every task was gone.
                // Reconcile against the live plan here so ANY removal path self-heals.
                // Legacy sessions with taskId == null are left alone.
                val liveTaskIds = PlanStore.todayTasks.value.map { it.id }.toSet()
                val allToday = todaysSessions()
                val liveSessions = allToday.filter { it.taskId == null || it.taskId in liveTaskIds }
                if (liveSessions.size != allToday.size) {
                    allToday.filterNot { it in liveSessions }.forEach {
                        DebugTrail.d(TAG, "generateIfNeeded: releasing orphaned session subject=${it.subject} " +
                            "chapter=${it.chapter} task=${it.taskId} (task no longer in today's plan)")
                    }
                    saveSessions(liveSessions)
                }

                // Check-in sessions are managed by applyCheckInTopicsInternal; the auto map only holds auto ones
                // (a started auto session and a check-in session for one subject can coexist).
                val checkInSessions = liveSessions.filter { it.source == SOURCE_CHECKIN }
                val coveredByCheckIn: Set<String> = checkInSessions.flatMap { autoSubjectsCoveredBy(it.subject) }.toSet()
                val existing = liveSessions.filter { it.source != SOURCE_CHECKIN }.associateBy { it.subject }.toMutableMap()
                var anyCreated = false
                var lastFailure: String? = null

                for (subject in SUBJECTS) {
                    if (existing.containsKey(subject)) {
                        // Was silent — made "why no Physics task?" unanswerable from the trail.
                        DebugTrail.d(TAG, "generateIfNeeded: $subject already has a live session today — skipping")
                        continue // already have today's session
                    }

                    if (subject in coveredByCheckIn) {
                        DebugTrail.d(TAG, "generateIfNeeded: $subject is covered by today's Daily Check-In chapter — skipping auto pick")
                        continue
                    }

                    val subjectCoverage = target.subjectBreakdown.find { it.subject == subject }
                    if (subjectCoverage == null || subjectCoverage.coverageTarget <= 0) continue

                    val chapter = topGapChapterBySubject[subject]
                    if (chapter == null) {
                        // Covers two cases now: no coverage_gaps entry for this subject at
                        // all, OR every entry this subject has is in unseededChapters (see
                        // BUGFIX above) — both mean "nothing schedulable today," logged the
                        // same way since either legitimately skips the subject rather than
                        // retrying every cycle.
                        DebugTrail.d(TAG, "generateIfNeeded: no seeded coverage_gaps chapter for $subject yet — skipping")
                        continue
                    }

                    when (val practiceOutcome = TestmateApi.startQbankPractice(
                        chapter = chapter,
                        questionCount = subjectCoverage.coverageTarget
                    )) {
                        is TestmateQbankPracticeOutcome.Success -> {
                            val r = practiceOutcome.result

                            // FIX (Tasks tab): create the actual StudyTask now, same
                            // direct-PlanStore.createTask() pattern GapTaskManager/
                            // RetentionCheckManager use for their own Testmate-backed
                            // sessions — no orchestrator/escrow negotiation needed here,
                            // this isn't a repair candidate competing for the single
                            // gap-repair slot, it's a plain coverage-practice task.
                            val task = StudyTask(
                                subject = subject,
                                topic = "Q-bank: $chapter",
                                durationMinutes = (r.questionCount * MINUTES_PER_QUESTION)
                                    .coerceAtLeast(MIN_TASK_MINUTES),
                                taskType = TaskType.PRACTICE
                            )
                            PlanStore.createTask(task)

                            existing[subject] = QbankDailySession(
                                subject = subject,
                                chapter = chapter,
                                sessionId = r.sessionId,
                                testId = r.testId,
                                questionCount = r.questionCount,
                                taskId = task.id
                            )
                            anyCreated = true
                            DebugTrail.d(
                                TAG,
                                "generateIfNeeded: $subject -> chapter=$chapter session=${r.sessionId} " +
                                    "q=${r.questionCount} reused=${r.reused} task=${task.id}"
                            )
                        }
                        is TestmateQbankPracticeOutcome.Error -> {
                            lastFailure = "$subject: ${practiceOutcome.message}"
                            DebugTrail.e(
                                TAG,
                                "generateIfNeeded: startQbankPractice failed for $subject/$chapter: ${practiceOutcome.message}"
                            )
                        }
                    }
                }

                if (anyCreated) {
                    saveSessions(checkInSessions + existing.values.toList())
                }
                if (lastFailure != null) {
                    recordError(lastFailure)
                } else {
                    clearError()
                }
                // Mark today's gate done once every subject either got a session or was
                // legitimately skipped (no target, or no coverage_gaps chapter yet) — a
                // real Testmate error with NOTHING created leaves the gate open so the
                // hourly retry (above) tries again instead of waiting until tomorrow.
                if (anyCreated || lastFailure == null) {
                    CheckmatePrefs.putString(PREF_LAST_GENERATED_DAY, today)
                }
            }
        }
    }

    // ── Daily Check-In flood ────────────────────────────────────────────────

    /** Q-bank subjects a check-in subject stands in for (Biology = Botany + Zoology). */
    private fun autoSubjectsCoveredBy(checkInSubject: String): Set<String> =
        if (checkInSubject == "Biology") setOf("Botany", "Zoology") else setOf(checkInSubject)

    private fun loadApplied(): MutableSet<String> {
        if (CheckmatePrefs.getString(PREF_CHECKIN_APPLIED_DAY, null) != todayKey()) return mutableSetOf()
        val raw = CheckmatePrefs.getString(PREF_CHECKIN_APPLIED_JSON, null) ?: return mutableSetOf()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }.toMutableSet()
        } catch (_: Exception) {
            mutableSetOf()
        }
    }

    private fun saveApplied(keys: Set<String>) {
        CheckmatePrefs.putString(PREF_CHECKIN_APPLIED_JSON, JSONArray(keys.toList()).toString())
        CheckmatePrefs.putString(PREF_CHECKIN_APPLIED_DAY, todayKey())
    }

    /**
     * For every (subject, chapter) the student picked in today's completed Daily Check-In, make sure a
     * [SOURCE_CHECKIN] Q-bank session + StudyTask exists. NEET only (the check-in chapter vocabulary and
     * Testmate's tag map are NEET's). Cheap no-op once everything is applied; failures retry at most every
     * [CHECKIN_RETRY_MS] unless [force] (check-in submit / Settings force-refresh).
     */
    private suspend fun applyCheckInTopicsInternal(force: Boolean) = checkInMutex.withLock {
        if (ConsultationProfile.load().examTarget != "NEET") return@withLock
        val checkIn = DailyCheckIn.loadToday() ?: return@withLock
        if (checkIn.completedAt <= 0L) return@withLock
        val wanted: Map<String, String> = checkIn.todayTopics.filterValues { it.isNotBlank() }
        // subject -> picked syllabus topics (deduped, sorted). One combined session covers all of a subject's picks;
        // its stored `topic` is the same " | "-joined key the server uses as the session's topic label.
        val wantedTopicLists: Map<String, List<String>> = checkIn.todayTopicSets
            .mapValues { (_, v) -> v.filter { it.isNotBlank() }.distinct().sorted() }
            .filterValues { it.isNotEmpty() }
        val wantedTopics: Map<String, String> = wantedTopicLists.mapValues { (_, v) -> v.joinToString(" | ") }

        val liveTasks = PlanStore.todayTasks.value.associateBy { it.id }
        var sessions = todaysSessions().filter { it.taskId == null || it.taskId in liveTasks }
        var changed = false

        // 1. Retire check-in sessions that no longer match the check-in (chapter changed or deselected),
        //    but only while their task is untouched — never yank something the student started.
        sessions.filter { it.source == SOURCE_CHECKIN && (wanted[it.subject] != it.chapter || wantedTopics[it.subject] != it.topic) }.forEach { stale ->
            val task = stale.taskId?.let { liveTasks[it] }
            if (task == null || task.state == TaskState.PENDING) {
                task?.let { PlanStore.removeTask(it.id) }
                sessions = sessions - stale
                changed = true
                DebugTrail.d(TAG, "applyCheckIn: retired ${stale.subject}/${stale.chapter} (check-in changed)")
            }
        }

        // 2. Flood each selected chapter.
        val applied = loadApplied()
        for ((subject, chapter) in wanted) {
            val topic = wantedTopics[subject]
            val topicList = wantedTopicLists[subject].orEmpty()
            val key = "$subject|$chapter|${topic ?: ""}|${checkIn.completedAt}"
            if (key in applied) continue
            if (sessions.any { it.source == SOURCE_CHECKIN && it.subject == subject && it.chapter == chapter && it.topic == topic }) {
                applied.add(key)
                continue
            }
            val now = System.currentTimeMillis()
            if (!force && now - (checkInLastAttempt[key] ?: 0L) < CHECKIN_RETRY_MS) continue
            checkInLastAttempt[key] = now

            when (val outcome = TestmateApi.startQbankPractice(
                chapter = chapter,
                topics = topicList.takeIf { it.isNotEmpty() },
                questionCount = CHECKIN_FLOOD_QUESTIONS,
                checkinChapter = chapter
            )) {
                is TestmateQbankPracticeOutcome.Success -> {
                    val r = outcome.result
                    // The check-in pick replaces the auto pick: drop untouched auto sessions for the subjects it covers.
                    val covered = autoSubjectsCoveredBy(subject)
                    sessions.filter { it.source == SOURCE_AUTO && it.subject in covered }.forEach { auto ->
                        val autoTask = auto.taskId?.let { liveTasks[it] }
                        if (autoTask == null || autoTask.state == TaskState.PENDING) {
                            autoTask?.let { PlanStore.removeTask(it.id) }
                            sessions = sessions - auto
                        }
                    }
                    val task = StudyTask(
                        subject = subject,
                        topic = "Q-bank: $chapter" + (topicList.takeIf { it.isNotEmpty() }?.let { " — ${it.joinToString(", ")}" } ?: ""),
                        durationMinutes = (r.questionCount * MINUTES_PER_QUESTION).coerceAtLeast(MIN_TASK_MINUTES),
                        taskType = TaskType.PRACTICE
                    )
                    PlanStore.createTask(task)
                    sessions = sessions + QbankDailySession(
                        subject = subject,
                        chapter = chapter,
                        sessionId = r.sessionId,
                        testId = r.testId,
                        questionCount = r.questionCount,
                        taskId = task.id,
                        source = SOURCE_CHECKIN,
                        topic = topic
                    )
                    applied.add(key)
                    changed = true
                    DebugTrail.d(TAG, "applyCheckIn: $subject -> $chapter session=${r.sessionId} q=${r.questionCount} reused=${r.reused} task=${task.id}")
                }
                is TestmateQbankPracticeOutcome.Error -> {
                    recordError("Check-in $chapter: ${outcome.message}")
                    DebugTrail.e(TAG, "applyCheckIn: startQbankPractice failed for $subject/$chapter: ${outcome.message}")
                }
            }
        }
        saveApplied(applied)
        if (changed) saveSessions(sessions)
    }

    private fun recordError(message: String) {
        CheckmatePrefs.putString(PREF_LAST_ERROR, message)
        CheckmatePrefs.putLong(PREF_LAST_ERROR_AT, System.currentTimeMillis())
    }

    private fun clearError() {
        CheckmatePrefs.remove(PREF_LAST_ERROR)
        CheckmatePrefs.remove(PREF_LAST_ERROR_AT)
    }
}
