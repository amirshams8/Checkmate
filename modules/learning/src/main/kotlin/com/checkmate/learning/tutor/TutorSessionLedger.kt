package com.checkmate.learning.tutor

import android.util.Log
import com.checkmate.core.CheckmatePrefs
import com.checkmate.learning.engine.LearningDecisionEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Upgrade Blueprint Phase 3 skeleton, item 5 ("persist the tutor state so process
 * death/relaunch doesn't reset it") and item 6 ("connect it to the existing
 * LearningDecisionEngine rather than creating another competing decision engine").
 *
 * CheckmatePrefs-backed, same pattern
 * [com.checkmate.planner.intervention.GapTaskLedger] already establishes for exactly
 * the same reason: single-user app, one tutoring cycle in flight at a time, so there is
 * nowhere else for "which concept is currently mid-teaching-cycle" to live across a
 * process restart. [TutorStateMachine] itself stays a pure, Android-free function of
 * (session, evidence) — this object is the ONLY place in this package that touches
 * CheckmatePrefs, so [TutorStateMachine]'s own unit tests never need
 * `CheckmatePrefs.init()`/an Android Context, same "engine tested pure, ledger wraps
 * it with I/O" split [com.checkmate.planner.intervention.GapTaskLedger] and
 * [com.checkmate.learning.engine.LearningDecisionEngine] already model between
 * themselves.
 *
 * SINGLE ACTIVE SESSION, same justification as
 * [com.checkmate.planner.intervention.GapTaskLedger]'s own single active-concept slot:
 * there is only ever one gap-repair concept in flight in this single-user app, so a
 * second, competing tutor session has nowhere useful to run concurrently anyway — see
 * [start] for what happens when one is already active.
 *
 * NOT unit-tested at the JVM level, matching this codebase's existing convention:
 * no `GapTaskLedgerTest`/`ConceptDifficultyLedgerTest`/`ReplanDayLedgerTest` exists
 * either, since `CheckmatePrefs.ready()` requires `CheckmatePrefs.init(context)`, which
 * plain JVM unit tests don't run (see the same caveat noted against
 * `PolicyValidatorTest`/`TaskEscrowTest` elsewhere in this codebase). [TutorStateMachine]
 * — the part with actual branching logic — is exhaustively unit tested instead
 * (see `TutorStateMachineTest`); this file is a thin, mechanically-reviewable
 * read/write/serialize wrapper around it, same review bar
 * [com.checkmate.planner.intervention.GapTaskLedger] itself is held to.
 */
object TutorSessionLedger {

    private const val TAG = "TutorSessionLedger"
    private val json = Json { ignoreUnknownKeys = true }

    private const val KEY_ACTIVE_SESSION = "tutor_active_session"

    // Same reactivity shape as GapTaskLedger.version — plain SharedPreferences has no
    // Flow of its own, so a monotonic counter is what lets a ViewModel collecting this
    // notice a background-loop write (e.g. GapTaskManager advancing a session outside
    // Compose) the same way GapTaskLedger.version already does for its own fields.
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()
    private fun bumpVersion() { _version.update { it + 1 } }

    /** Current active session, if any — null when no tutor cycle is in flight. */
    fun current(): TutorSession? =
        CheckmatePrefs.getString(KEY_ACTIVE_SESSION, null)?.let {
            runCatching { json.decodeFromString<TutorSession>(it) }
                .onFailure { e -> Log.w(TAG, "failed to decode active tutor session, discarding", e) }
                .getOrNull()
        }

    private fun persist(session: TutorSession) {
        CheckmatePrefs.putString(KEY_ACTIVE_SESSION, json.encodeToString(session))
        bumpVersion()
    }

    /** Result of [start] — distinct from [TutorTransitionResult] since starting a
     *  session isn't itself a [TutorStateMachine] transition (there is no prior
     *  session to transition from). */
    sealed class StartResult {
        data class Started(val session: TutorSession) : StartResult()
        /** A different concept already has an unresolved session in flight — see class
         *  doc's "single active session" note. [existing] is that session, so a caller
         *  can decide whether to surface it instead of silently dropping the request. */
        data class AlreadyActive(val existing: TutorSession) : StartResult()
    }

    /**
     * Starts a fresh [TutorSession] for [conceptId] in [TutorState.DIAGNOSE], persists
     * it, and returns it — unless a DIFFERENT concept's session is already active and
     * unresolved (not [TutorState.isTerminal]), in which case nothing is written and
     * [StartResult.AlreadyActive] is returned. Starting again for the SAME conceptId
     * while one is already active returns the existing session unchanged (idempotent —
     * a caller re-invoking this shouldn't blow away in-progress history), matching
     * [StartResult.AlreadyActive]'s own shape so both cases are handled the same way.
     * A session that already reached [TutorState.isTerminal] is treated as free — a
     * fresh [start] for the same or a different concept simply overwrites it, the same
     * "DONE clears the slot" discipline
     * [com.checkmate.planner.intervention.GapTaskLedger.markCovered] applies to its own
     * active-concept pointer.
     */
    fun start(conceptId: String, now: Long): StartResult {
        val existing = current()
        if (existing != null && !existing.state.isTerminal) {
            return StartResult.AlreadyActive(existing)
        }
        val fresh = TutorStateMachine.start(conceptId, now)
        persist(fresh)
        return StartResult.Started(fresh)
    }

    /**
     * Runs [evidence] through [TutorStateMachine.transition] against the current active
     * session and persists the result if it advanced. Returns null if there is no
     * active session at all — a caller shouldn't be feeding evidence to a tutor cycle
     * that was never started (or already ended and cleared).
     */
    fun apply(evidence: TutorEvidence, now: Long): TutorTransitionResult? {
        val session = current() ?: return null
        val result = TutorStateMachine.transition(session, evidence, now)
        if (result is TutorTransitionResult.Advanced) {
            persist(result.session)
        }
        return result
    }

    /**
     * Explicitly frees the active-session slot — call once a terminal session
     * ([TutorState.MOVE_ON]/[TutorState.ESCALATED]) has been fully handled by the
     * caller (e.g. [TutorState.MOVE_ON] recorded as covered somewhere analogous to
     * [com.checkmate.planner.intervention.GapTaskLedger.markCovered] — that wiring is
     * the next milestone, not this one). Deliberately NOT automatic on reaching a
     * terminal state: a caller may still need to read the finished session (its
     * [TutorSession.history], its [TutorState.ESCALATED] reason) before it disappears.
     */
    fun clear() {
        CheckmatePrefs.remove(KEY_ACTIVE_SESSION)
        bumpVersion()
    }

    // ── LearningDecisionEngine connector (item 6) ───────────────────────────

    /**
     * Intents this state machine actually governs a teaching cycle for — a single,
     * already-identified concept that needs DIAGNOSE->EXPLAIN->PRACTICE->VERIFY. The
     * other five [LearningDecisionEngine.LearningInterventionIntent] values don't fit
     * this shape and are deliberately excluded, same "not every intent is this state
     * machine's concern" boundary
     * `com.checkmate.planner.intervention.LearningInterventionOrchestrator.GAP_LEDGER_TRACKED_INTENTS`
     * already draws for a different subset of the same taxonomy:
     * - [LearningDecisionEngine.LearningInterventionIntent.ASSIGN_TARGETED_SET] names a
     *   whole chapter across several concepts, not one — no single `conceptId` to run a
     *   cycle for.
     * - [LearningDecisionEngine.LearningInterventionIntent.SCHEDULE_RETENTION_TEST] is
     *   a recall check on an already-mastered concept, not a teaching cycle.
     * - [LearningDecisionEngine.LearningInterventionIntent.START_MOCK]/[REPLAN_DAY] are
     *   whole-student, not concept-scoped at all.
     * - [LearningDecisionEngine.LearningInterventionIntent.REDUCE_DIFFICULTY]/
     *   [INCREASE_DIFFICULTY] mutate a difficulty preference, not a teaching cycle.
     */
    private val TUTORABLE_INTENTS = setOf(
        LearningDecisionEngine.LearningInterventionIntent.REPAIR_CONCEPT,
        LearningDecisionEngine.LearningInterventionIntent.START_DIAGNOSTIC
    )

    /**
     * The one place this package reads a
     * [LearningDecisionEngine.CandidateIntervention] — [LearningDecisionEngine] itself
     * is untouched by this file and stays the sole authority on WHICH concept is worth
     * working on next; this only decides whether the chosen candidate is a shape this
     * state machine can run a cycle for, and if so, starts one. Returns null (no
     * session started, no error) for a candidate outside [TUTORABLE_INTENTS] or with no
     * `conceptId` — a caller falls back to whatever non-tutor execution path already
     * exists for that candidate (unchanged; see
     * [com.checkmate.planner.intervention.LearningInterventionOrchestrator], not
     * touched by this pass).
     */
    fun startFromCandidate(
        candidate: LearningDecisionEngine.CandidateIntervention,
        now: Long
    ): StartResult? {
        val conceptId = candidate.conceptId ?: return null
        if (candidate.intent !in TUTORABLE_INTENTS) return null
        return start(conceptId, now)
    }
}
