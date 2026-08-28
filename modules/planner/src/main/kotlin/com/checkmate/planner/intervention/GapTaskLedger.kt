package com.checkmate.planner.intervention

import com.checkmate.core.CheckmatePrefs
import com.checkmate.learning.engine.LearningDecisionEngine
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Calendar

/**
 * "One gap-repair task a day, cycling through every gap the test surfaced, until each one
 * is actually done" needs state that neither [LearningInterventionOrchestrator] nor
 * [com.checkmate.planner.PlanStore] carry on their own:
 *
 * - [PlanStore.todayTasks] only ever holds the CURRENT day's list — there's nowhere to ask
 *   "did the concept I served three days ago ever get finished."
 * - [LearningInterventionOrchestrator.executeTopCandidate] re-ranks from scratch every run.
 *   Without something outside it remembering what's already been served, re-running the
 *   SAME [com.checkmate.learning.engine.LearningDecisionEngine.decideFromReport] output
 *   tomorrow (mastery hasn't moved without the P0b evidence loop) would just re-pick
 *   today's exact candidate forever — the backlog would never advance.
 *
 * This object is that memory, CheckmatePrefs-backed (same pattern as [PlanStore] itself),
 * covering four things:
 * 1. **Covered concepts** — a concept is "covered" only once its gap-task reaches
 *    `TaskState.DONE`, never merely once a task was created for it (see [markCovered] doc
 *    for why creation alone isn't the right signal). [LearningInterventionOrchestrator]
 *    skips covered concepts when walking the ranked candidate list.
 * 2. **The active concept's streak** — how many consecutive days the current gap concept
 *    has been re-served without reaching DONE, which is exactly the escalation-depth input
 *    the gap-task warning needs (day 1 silent, day 2-3 names the consequence, day 4+ full
 *    persuasion — see [com.checkmate.service.GapTaskManager]).
 * 3. **Once-a-day guards** for generation and escalation, same "day-key written after
 *    firing" pattern [com.checkmate.service.ProactiveMentor]'s own checks already use.
 * 4. **P0b: the active concept's linked Testmate targeted-test session** — which
 *    `test_sessions.id`/`tests.id` Testmate created for the CURRENT active concept (see
 *    [recordTestmateSession]), and whether that session's result has already been turned
 *    into real [com.checkmate.learning.model.QuestionAttempt]/[com.checkmate.learning.model.LearningEvent]
 *    evidence (see [markActiveEvidenceImported]) — so [com.checkmate.service.GapTaskManager]
 *    creates the targeted test at most once per concept and imports its result at most once.
 *    Deliberately tied to the SAME "active concept" slot as #2 above, not a separate
 *    map, since there is only ever one active gap concept at a time in this single-user app.
 *
 * Single-user app (see this module's other CheckmatePrefs-backed singletons) — no
 * per-student keying needed.
 */
object GapTaskLedger {

    private val json = Json { ignoreUnknownKeys = true }

    private const val KEY_COVERED_CONCEPT_IDS       = "gap_task_covered_concept_ids"
    private const val KEY_ACTIVE_CONCEPT_ID         = "gap_task_active_concept_id"
    private const val KEY_ACTIVE_TASK_ID            = "gap_task_active_task_id"
    private const val KEY_ACTIVE_TASK_DAY_KEY       = "gap_task_active_task_day_key"
    private const val KEY_ACTIVE_DAYS_SERVED        = "gap_task_active_days_served"
    private const val KEY_ACTIVE_SUBJECT            = "gap_task_active_subject"
    private const val KEY_ACTIVE_CHAPTER            = "gap_task_active_chapter"
    private const val KEY_ACTIVE_TOPIC              = "gap_task_active_topic"
    private const val KEY_ACTIVE_DURATION_MIN       = "gap_task_active_duration_min"
    private const val KEY_ACTIVE_RATIONALE          = "gap_task_active_rationale"
    private const val KEY_ACTIVE_EXPECTED_GAIN      = "gap_task_active_expected_gain"
    private const val KEY_ACTIVE_TESTMATE_TEST_ID   = "gap_task_active_testmate_test_id"
    private const val KEY_ACTIVE_TESTMATE_SESSION_ID = "gap_task_active_testmate_session_id"
    private const val KEY_ACTIVE_EVIDENCE_IMPORTED  = "gap_task_active_evidence_imported"
    private const val KEY_LAST_GENERATED_DAY        = "gap_task_last_generated_day"
    private const val KEY_LAST_ESCALATED_DAY        = "gap_task_last_escalated_day"
    private const val KEY_WARNING_LOG               = "gap_task_warning_log"
    private const val MAX_WARNING_LOG_ENTRIES       = 50

    // ── Covered concepts ────────────────────────────────────────────────────

    fun isCovered(conceptId: String): Boolean =
        coveredIds().contains(conceptId)

    /**
     * Marks [conceptId] covered — call this ONLY once the gap-task's own `TaskState`
     * reaches DONE, never at task-creation time. Creation-time marking would mean an
     * ignored task silently "counts" as covered and the orchestrator moves on to the next
     * gap, permanently abandoning the one the student never actually did — the opposite of
     * "till every gap is covered." Clears the active-concept pointer if [conceptId] was it,
     * so the next [recordServed] call for a different concept starts a fresh streak instead
     * of inheriting this one's day count.
     */
    fun markCovered(conceptId: String) {
        val updated = coveredIds() + conceptId
        CheckmatePrefs.putString(KEY_COVERED_CONCEPT_IDS, updated.joinToString(","))
        recordWarningOutcome(conceptId)
        if (CheckmatePrefs.getString(KEY_ACTIVE_CONCEPT_ID, null) == conceptId) {
            clearActive()
        }
    }

    private fun coveredIds(): Set<String> =
        CheckmatePrefs.getString(KEY_COVERED_CONCEPT_IDS, null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()

    // ── Active concept + streak ─────────────────────────────────────────────

    /**
     * Called by [LearningInterventionOrchestrator.executeTopCandidate] right after a
     * candidate is successfully turned into a task. Bumps the streak if [candidate] is the
     * SAME concept already active (re-served because it wasn't finished); starts a fresh
     * streak at 1 for a new concept. No-ops for a candidate with no [conceptId][LearningDecisionEngine.CandidateIntervention.conceptId]
     * (e.g. day-level intents) — there's nothing to track streak-wise for those.
     *
     * P0b: only resets the Testmate session/evidence-imported fields (see class doc #4)
     * when [candidate] is a genuinely NEW concept — re-serving the SAME concept for another
     * day must leave an already-created session alone, since the whole point of re-serving
     * it is that the student hasn't taken that session yet.
     */
    fun recordServed(candidate: LearningDecisionEngine.CandidateIntervention, taskId: String, dayKey: String) {
        val conceptId = candidate.conceptId ?: return
        val previousConceptId = CheckmatePrefs.getString(KEY_ACTIVE_CONCEPT_ID, null)
        val previousDays = CheckmatePrefs.getInt(KEY_ACTIVE_DAYS_SERVED, 0)
        val isNewConcept = previousConceptId != conceptId
        val daysServed = if (isNewConcept) 1 else previousDays + 1

        CheckmatePrefs.putString(KEY_ACTIVE_CONCEPT_ID, conceptId)
        CheckmatePrefs.putString(KEY_ACTIVE_TASK_ID, taskId)
        CheckmatePrefs.putString(KEY_ACTIVE_TASK_DAY_KEY, dayKey)
        CheckmatePrefs.putInt(KEY_ACTIVE_DAYS_SERVED, daysServed)
        CheckmatePrefs.putString(KEY_ACTIVE_SUBJECT, candidate.subject ?: "")
        CheckmatePrefs.putString(KEY_ACTIVE_CHAPTER, candidate.chapter ?: "")
        CheckmatePrefs.putString(KEY_ACTIVE_TOPIC, candidate.topic ?: candidate.chapter ?: "")
        CheckmatePrefs.putInt(KEY_ACTIVE_DURATION_MIN, candidate.durationMinutes)
        CheckmatePrefs.putString(KEY_ACTIVE_RATIONALE, candidate.rationale)
        CheckmatePrefs.putString(KEY_ACTIVE_EXPECTED_GAIN, candidate.expectedGain.toString())

        if (isNewConcept) {
            CheckmatePrefs.putString(KEY_ACTIVE_TESTMATE_TEST_ID, "")
            CheckmatePrefs.putString(KEY_ACTIVE_TESTMATE_SESSION_ID, "")
            CheckmatePrefs.putBoolean(KEY_ACTIVE_EVIDENCE_IMPORTED, false)
        }
    }

    // BUGFIX: this was the one accessor in this object missing the blank-filter every
    // sibling below it has (activeSubject/activeChapter/activeTopic/etc.). clearActive()
    // writes "" (not null) to KEY_ACTIVE_CONCEPT_ID same as every other key — without the
    // filter, a cleared ledger reported a truthy-but-empty concept id instead of null,
    // which made createTargetedTestIfNeeded() (see GapTaskManager) treat "nothing active"
    // as "an active concept with a missing chapter" and log a misleading warning every
    // cycle instead of correctly no-op'ing.
    fun activeConceptId(): String? = CheckmatePrefs.getString(KEY_ACTIVE_CONCEPT_ID, null)?.takeIf { it.isNotBlank() }
    fun activeTaskId(): String? = CheckmatePrefs.getString(KEY_ACTIVE_TASK_ID, null)
    fun activeTaskDayKey(): String? = CheckmatePrefs.getString(KEY_ACTIVE_TASK_DAY_KEY, null)
    fun activeDaysServed(): Int = CheckmatePrefs.getInt(KEY_ACTIVE_DAYS_SERVED, 0)
    fun activeSubject(): String? = CheckmatePrefs.getString(KEY_ACTIVE_SUBJECT, null)?.takeIf { it.isNotBlank() }
    fun activeChapter(): String? = CheckmatePrefs.getString(KEY_ACTIVE_CHAPTER, null)?.takeIf { it.isNotBlank() }
    fun activeTopic(): String? = CheckmatePrefs.getString(KEY_ACTIVE_TOPIC, null)?.takeIf { it.isNotBlank() }
    fun activeDurationMinutes(): Int = CheckmatePrefs.getInt(KEY_ACTIVE_DURATION_MIN, 0)
    fun activeRationale(): String? = CheckmatePrefs.getString(KEY_ACTIVE_RATIONALE, null)?.takeIf { it.isNotBlank() }
    fun activeExpectedGain(): Double =
        CheckmatePrefs.getString(KEY_ACTIVE_EXPECTED_GAIN, null)?.toDoubleOrNull() ?: 0.0

    // ── P0b: active concept's Testmate targeted-test link ───────────────────

    fun activeTestmateTestId(): String? =
        CheckmatePrefs.getString(KEY_ACTIVE_TESTMATE_TEST_ID, null)?.takeIf { it.isNotBlank() }

    fun activeTestmateSessionId(): String? =
        CheckmatePrefs.getString(KEY_ACTIVE_TESTMATE_SESSION_ID, null)?.takeIf { it.isNotBlank() }

    fun isActiveEvidenceImported(): Boolean =
        CheckmatePrefs.getBoolean(KEY_ACTIVE_EVIDENCE_IMPORTED, false)

    /** Called once by [com.checkmate.service.GapTaskManager] right after Testmate confirms
     *  a targeted test was created (or already existed — see the endpoint's own idempotency
     *  by intervention_id) for the currently active concept. */
    fun recordTestmateSession(testId: String, sessionId: String) {
        CheckmatePrefs.putString(KEY_ACTIVE_TESTMATE_TEST_ID, testId)
        CheckmatePrefs.putString(KEY_ACTIVE_TESTMATE_SESSION_ID, sessionId)
    }

    /** Called once [com.checkmate.service.TargetedTestEvidenceImporter] has actually written
     *  QuestionAttempt/LearningEvent rows for the active concept's session — stops
     *  [com.checkmate.service.GapTaskManager] from re-polling/re-importing an already-scored
     *  session every 15 minutes for the rest of the day. */
    fun markActiveEvidenceImported() {
        CheckmatePrefs.putBoolean(KEY_ACTIVE_EVIDENCE_IMPORTED, true)
    }

    private fun clearActive() {
        CheckmatePrefs.putString(KEY_ACTIVE_CONCEPT_ID, "")
        CheckmatePrefs.putString(KEY_ACTIVE_TASK_ID, "")
        CheckmatePrefs.putString(KEY_ACTIVE_TASK_DAY_KEY, "")
        CheckmatePrefs.putInt(KEY_ACTIVE_DAYS_SERVED, 0)
        CheckmatePrefs.putString(KEY_ACTIVE_SUBJECT, "")
        CheckmatePrefs.putString(KEY_ACTIVE_CHAPTER, "")
        CheckmatePrefs.putString(KEY_ACTIVE_TOPIC, "")
        CheckmatePrefs.putInt(KEY_ACTIVE_DURATION_MIN, 0)
        CheckmatePrefs.putString(KEY_ACTIVE_RATIONALE, "")
        CheckmatePrefs.putString(KEY_ACTIVE_EXPECTED_GAIN, "")
        CheckmatePrefs.putString(KEY_ACTIVE_TESTMATE_TEST_ID, "")
        CheckmatePrefs.putString(KEY_ACTIVE_TESTMATE_SESSION_ID, "")
        CheckmatePrefs.putBoolean(KEY_ACTIVE_EVIDENCE_IMPORTED, false)
    }

    // ── Once-a-day guards ────────────────────────────────────────────────────

    fun hasGeneratedToday(todayKey: String): Boolean =
        CheckmatePrefs.getString(KEY_LAST_GENERATED_DAY, null) == todayKey

    fun markGeneratedToday(todayKey: String) =
        CheckmatePrefs.putString(KEY_LAST_GENERATED_DAY, todayKey)

    fun hasEscalatedToday(todayKey: String): Boolean =
        CheckmatePrefs.getString(KEY_LAST_ESCALATED_DAY, null) == todayKey

    fun markEscalatedToday(todayKey: String) =
        CheckmatePrefs.putString(KEY_LAST_ESCALATED_DAY, todayKey)

    // ── Warning outcome log (data collection only — no style-selection logic yet) ──────

    @Serializable
    data class WarningLogEntry(
        val conceptId: String,
        val sentDayKey: String,
        val daysServedAtEscalation: Int,
        val tier: Int,
        var completedAfterward: Boolean = false
    )

    /**
     * Appends one record every time [com.checkmate.service.GapTaskManager] actually sends
     * an escalation message. Deliberately just data collection for now — once there's
     * enough history to mean anything, which [tier] (escalation depth) correlates with a
     * concept actually reaching DONE afterward becomes a real, evidence-backed question
     * instead of a guess. Capped at [MAX_WARNING_LOG_ENTRIES], oldest dropped first.
     */
    fun logWarningSent(conceptId: String, dayKey: String, daysServedAtEscalation: Int, tier: Int) {
        val updated = (warningLog() + WarningLogEntry(conceptId, dayKey, daysServedAtEscalation, tier))
            .takeLast(MAX_WARNING_LOG_ENTRIES)
        CheckmatePrefs.putString(KEY_WARNING_LOG, json.encodeToString(updated))
    }

    /** Called from [markCovered] — flips the most recent unresolved warning for
     *  [conceptId], if any, so the log can later answer "did warning X actually work." */
    private fun recordWarningOutcome(conceptId: String) {
        val log = warningLog()
        val lastUnresolvedIndex = log.indexOfLast { it.conceptId == conceptId && !it.completedAfterward }
        if (lastUnresolvedIndex == -1) return
        val updated = log.toMutableList()
        updated[lastUnresolvedIndex] = updated[lastUnresolvedIndex].copy(completedAfterward = true)
        CheckmatePrefs.putString(KEY_WARNING_LOG, json.encodeToString(updated))
    }

    fun warningLog(): List<WarningLogEntry> =
        CheckmatePrefs.getString(KEY_WARNING_LOG, null)?.let {
            try { json.decodeFromString(it) } catch (_: Exception) { emptyList() }
        } ?: emptyList()

    // ── Shared day-key helper — same "YYYY_dayOfYear" format PlanStore uses ────────────

    fun todayKey(): String {
        val cal = Calendar.getInstance()
        return "${cal.get(Calendar.YEAR)}_${cal.get(Calendar.DAY_OF_YEAR)}"
    }
}
