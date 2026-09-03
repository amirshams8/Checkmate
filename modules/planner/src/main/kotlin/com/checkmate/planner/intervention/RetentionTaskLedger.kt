package com.checkmate.planner.intervention

import android.util.Log
import com.checkmate.core.CheckmatePrefs
import com.checkmate.learning.engine.LearningDecisionEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * next-session-retention-loop.txt — closes the RETENTION CHECK execution/evidence gap:
 * ```
 * RetentionEngine: REVIEW -> SCHEDULE_RETENTION_TEST -> RETENTION CHECK task (already worked)
 *     -> [THIS] launch a Testmate retention session for this exact task
 *     -> student answers questions -> Testmate result
 *     -> [existing] TargetedTestEvidenceImporter -> QuestionAttempt + LearningEvent
 *     -> MasteryEngine.recomputeAll -> RetentionEngine sees fresh lastSeen/mastery
 *        on its own next decideFromReport pass (no new decision-push code needed here —
 *        see class doc's "no new logic" note below)
 * ```
 *
 * ROOT CAUSE this replaces: SCHEDULE_RETENTION_TEST is intentionally excluded from
 * [LearningInterventionOrchestrator]'s `GAP_LEDGER_TRACKED_INTENTS` — only REPAIR_CONCEPT/
 * START_DIAGNOSTIC/ASSIGN_TARGETED_SET participate in [GapTaskLedger]'s single-active-concept
 * tracking. That means a RETENTION CHECK task was, until now, a plain StudyTask: marking it
 * DONE only updated focus/behavior stats, never mastery or lastSeen for that concept, because
 * nothing ever requested a Testmate session or polled for its result the way
 * [com.checkmate.service.GapTaskManager] already does for the three gap-repair intents.
 *
 * WHY A SEPARATE LEDGER, NOT [GapTaskLedger] ITSELF: that ledger's own class doc is explicit
 * that it tracks exactly ONE active concept at a time — deliberately, since gap-repair is a
 * single-active-concept streak ("one gap-repair task a day, cycling through every gap... until
 * each one is actually done"). Retention checks have no such constraint: several concepts can
 * legitimately clear the HIGH-mastery/HIGH-risk REVIEW bar in the same [RetentionEngine.decideAll]
 * pass, and each gets its own RETENTION CHECK task independent of whatever gap-repair concept is
 * currently active. Joining GAP_LEDGER_TRACKED_INTENTS outright would mean a retention check for
 * concept A silently overwriting the active-concept pointer for a gap-repair round already in
 * progress on concept B (or vice versa) — exactly the kind of cross-purpose collision
 * [GapTaskLedger]'s own bugfix history is full of examples of. This object instead tracks one
 * entry PER RETENTION TASK, keyed by taskId, with no single "active" slot at all.
 *
 * WHY NO ROUND-RETRY LOGIC (unlike [GapTaskLedger]'s P0b round counter): a gap-repair task that
 * comes back still below [com.checkmate.learning.engine.MasteryEngine.MASTERY_THRESHOLD] needs
 * another round of the SAME repair, requested explicitly (`resetForNextRound`). A retention
 * check has no equivalent "try again immediately" step — once its evidence is imported, mastery
 * and `lastSeen` are already up to date, and [RetentionEngine.decide] will naturally re-classify
 * the concept (REVIEW again, MOVE_ON, or even TEACH) the next time
 * [com.checkmate.service.GapTaskManager.generateIfNeeded]'s daily pass re-runs
 * `LearningDecisionEngine.decideFromReport` — which can, on its own, produce a brand-new
 * SCHEDULE_RETENTION_TEST candidate (a fresh taskId, a fresh entry here) if the concept is still
 * at risk. No extra "next decision" push belongs in this file — see the CONSTRAINT in
 * next-session-retention-loop.txt: RetentionEngine's 14-day decay and REVIEW/TEACH/MOVE_ON
 * thresholds are untouched by this change.
 *
 * CheckmatePrefs-backed, same pattern as [GapTaskLedger] and [com.checkmate.planner.PlanStore].
 * Single-user app — no per-student keying needed.
 */
object RetentionTaskLedger {

    private const val TAG = "RetentionTaskLedger"

    private val json = Json { ignoreUnknownKeys = true }

    private const val KEY_SESSIONS = "retention_task_sessions"

    // Unresolved entries are always kept in full (there should only ever be a handful
    // outstanding at once — one per concept currently at REVIEW risk). Resolved entries
    // are capped so a long-lived install doesn't grow this pref forever — same reasoning
    // as GapTaskLedger.WarningLogEntry's own MAX_WARNING_LOG_ENTRIES cap.
    private const val MAX_RESOLVED_KEPT = 30

    @Serializable
    data class RetentionSession(
        val taskId: String,
        val dayKey: String,
        val conceptId: String?,
        val subject: String?,
        val chapter: String?,
        val topic: String?,
        val testmateTestId: String? = null,
        val testmateSessionId: String? = null,
        val evidenceImported: Boolean = false,
        val createdAt: Long = System.currentTimeMillis()
    )

    // Same reactive-change-signal shape as GapTaskLedger.version — CheckmatePrefs writes
    // from ReminderService's background loop (RetentionCheckManager) have no observable of
    // their own otherwise, and HomeViewModel needs to notice a session becoming available
    // to take, same as it already does for GapTaskLedger's P0b fields.
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()
    private fun bumpVersion() { _version.update { it + 1 } }

    private fun all(): List<RetentionSession> =
        CheckmatePrefs.getString(KEY_SESSIONS, null)?.let {
            try {
                json.decodeFromString(it)
            } catch (e: Exception) {
                Log.e(TAG, "corrupt retention session list, resetting: ${e.message}", e)
                emptyList()
            }
        } ?: emptyList()

    private fun save(sessions: List<RetentionSession>) {
        val unresolved = sessions.filter { !it.evidenceImported }
        val resolved = sessions.filter { it.evidenceImported }
            .sortedByDescending { it.createdAt }
            .take(MAX_RESOLVED_KEPT)
        CheckmatePrefs.putString(KEY_SESSIONS, json.encodeToString(unresolved + resolved))
    }

    /**
     * Called by [LearningInterventionOrchestrator.executeTopCandidate] right after a
     * SCHEDULE_RETENTION_TEST candidate is turned into a real StudyTask (see that class's
     * `Route.tracksRetentionLedger`). Idempotent on [taskId] so a retried orchestrator call
     * for the same task never duplicates the entry.
     */
    fun record(candidate: LearningDecisionEngine.CandidateIntervention, taskId: String, dayKey: String) {
        val existing = all()
        if (existing.any { it.taskId == taskId }) return
        val entry = RetentionSession(
            taskId = taskId,
            dayKey = dayKey,
            conceptId = candidate.conceptId,
            subject = candidate.subject,
            chapter = candidate.chapter,
            // Same "null" -> real-null defense GapTaskLedger.sanitizeTopicOrChapter applies —
            // candidate.topic can carry the literal string "null" from a still-poisoned
            // Question row (see that function's own doc for the full chain).
            topic = candidate.topic?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        )
        save(existing + entry)
        Log.d(TAG, "record: taskId=$taskId concept=${candidate.conceptId} chapter=${candidate.chapter}")
        bumpVersion()
    }

    /**
     * True while [conceptId] already has an outstanding (evidence not yet imported)
     * retention task — [LearningInterventionOrchestrator] checks this per-candidate so a
     * fresh daily re-rank can't pile up a second retention check for the same concept while
     * the first one's Testmate session is still unsubmitted.
     */
    fun hasUnresolvedForConcept(conceptId: String): Boolean =
        all().any { it.conceptId == conceptId && !it.evidenceImported }

    /** Entries with no Testmate session created yet — [com.checkmate.service.RetentionCheckManager]
     *  drives these through [com.checkmate.testmate.TestmateApi.createTargetedTest]. */
    fun pendingSessionCreation(): List<RetentionSession> =
        all().filter { it.testmateSessionId.isNullOrBlank() && !it.evidenceImported }

    /** Entries with a session on file whose result hasn't been imported yet —
     *  [com.checkmate.service.RetentionCheckManager] polls these every cycle, same cadence
     *  as [com.checkmate.service.GapTaskManager.evidencePollIfNeeded]. */
    fun pendingEvidence(): List<RetentionSession> =
        all().filter { !it.testmateSessionId.isNullOrBlank() && !it.evidenceImported }

    /** For UI wiring (HomeScreen's "take test" button) — the sessionId to open for a
     *  specific StudyTask, or null if this task has no retention session at all (not a
     *  retention task, or its session hasn't been created yet). */
    fun sessionIdForTask(taskId: String): String? =
        all().firstOrNull { it.taskId == taskId }?.testmateSessionId?.takeIf { it.isNotBlank() }

    /** Called once by [com.checkmate.service.RetentionCheckManager] right after Testmate
     *  confirms a targeted test was created (or already existed — the endpoint is
     *  idempotent by intervention_id) for this task. */
    fun recordTestmateSession(taskId: String, testId: String, sessionId: String) {
        val updated = all().map {
            if (it.taskId == taskId) it.copy(testmateTestId = testId, testmateSessionId = sessionId) else it
        }
        save(updated)
        bumpVersion()
    }

    /** Called once [com.checkmate.service.TargetedTestEvidenceImporter] has actually written
     *  QuestionAttempt/LearningEvent rows for this task's session — stops
     *  [com.checkmate.service.RetentionCheckManager] from re-polling/re-importing an
     *  already-scored session every 15 minutes, and (via [save]'s resolved-cap) lets this
     *  entry eventually age out instead of tracking it forever. */
    fun markEvidenceImported(taskId: String) {
        val updated = all().map { if (it.taskId == taskId) it.copy(evidenceImported = true) else it }
        save(updated)
        bumpVersion()
    }

    fun entry(taskId: String): RetentionSession? = all().firstOrNull { it.taskId == taskId }
}
