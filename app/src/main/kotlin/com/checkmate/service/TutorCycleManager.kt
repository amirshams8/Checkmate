package com.checkmate.service

import android.content.Context
import android.util.Log
import com.checkmate.learning.student.StudentModelBuilder
import com.checkmate.learning.tutor.TutorDiagnostics
import com.checkmate.learning.tutor.TutorEvidence
import com.checkmate.learning.tutor.TutorSession
import com.checkmate.learning.tutor.TutorSessionLedger
import com.checkmate.learning.tutor.TutorState
import com.checkmate.learning.tutor.TutorTransitionResult
import com.checkmate.planner.intervention.GapTaskLedger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Upgrade Blueprint Phase 3, P3.2 ("make the FSM drive real learning activity") —
 * the execution bridge between the pure [com.checkmate.learning.tutor.TutorStateMachine]
 * skeleton and the real intervention pipeline. This is a fourth manager in the same family
 * as [GapTaskManager]/[RetentionCheckManager]: one object, called from
 * [ReminderService]'s existing 15-min loop, driving one specific piece of state.
 *
 * DELIBERATELY DOES NOT REQUEST ITS OWN TESTMATE TEST. [GapTaskManager]'s own P0b loop
 * already requests/polls/imports a targeted test for whatever concept
 * [GapTaskLedger.activeConceptId] currently is — and that concept is, by construction, the
 * exact same one a tutor session was started for (see [GapTaskManager]'s own
 * `generateIfNeededLocked`, which calls [TutorSessionLedger.startFromCandidate] with the
 * same [com.checkmate.learning.engine.LearningDecisionEngine.CandidateIntervention]
 * [GapTaskLedger.recordServed] already tracked). Firing a SECOND, independent targeted-test
 * request here would mean two competing Testmate sessions for one concept — this instead
 * reads [GapTaskLedger]'s own already-imported evidence
 * ([GapTaskLedger.activeLastImportAttemptCount]/[GapTaskLedger.activeLastImportCorrectCount])
 * and, once VERIFY resolves without mastery, asks for a fresh round the SAME way
 * `resolveDoneConcept` already does ([GapTaskLedger.resetForNextRound]) — "extend the
 * existing intervention pipeline, don't replace it," applied to the tutor bridge itself.
 *
 * SHARES [GapTaskLedger]'s MUTEX. Every function here reads or writes [GapTaskLedger]'s own
 * fields, so [driveActiveSession] runs inside [GapTaskLedger.withLock] — same discipline
 * [GapTaskManager] itself already applies to every one of its own entry points, for exactly
 * the race class already fixed once in this codebase (see [GapTaskLedger.withLock]'s own
 * doc). [TutorSessionLedger] has no mutex of its own; every mutation of it happens to be
 * serialized through this shared lock today ONLY because every call site that touches it
 * (this file, and [GapTaskManager]'s `startFromCandidate` call) is reached via
 * [GapTaskLedger.withLock] — a future caller that mutates [TutorSessionLedger] from outside
 * that lock would reopen the exact bug class this comment is warning about.
 *
 * NO LLM CALL ANYWHERE IN THIS FILE — DIAGNOSE uses [TutorDiagnostics] (deterministic), and
 * EXPLAIN auto-advances immediately by design (see [driveExplain]'s own doc for why that's
 * a real, defensible choice for this pass and not a stub being papered over).
 */
object TutorCycleManager {

    private const val TAG = "TutorCycleManager"

    /** Safety cap on how many state-to-state auto-advances (DIAGNOSE, EXPLAIN — the two
     *  states that need no external evidence) this drives in a single tick, so a logic bug
     *  that made [TutorStateMachine] loop between two states could never hang this call
     *  forever. Five is comfortably above the three real states (DIAGNOSE/EXPLAIN/one
     *  PRACTICE-or-VERIFY check) a normal tick ever needs to walk through. */
    private const val MAX_AUTO_STEPS_PER_TICK = 5

    /** Call from [ReminderService]'s existing 15-min loop, after
     *  [GapTaskManager.evidencePollIfNeeded] (so a just-imported round's evidence is
     *  available to consume in the very same tick). No-op if no tutor session is active. */
    suspend fun driveActiveSession(context: Context) = GapTaskLedger.withLock {
        driveActiveSessionLocked(context)
    }

    private suspend fun driveActiveSessionLocked(context: Context) {
        var steps = 0
        while (steps++ < MAX_AUTO_STEPS_PER_TICK) {
            val session = TutorSessionLedger.current() ?: return
            val advanced = when (session.state) {
                TutorState.DIAGNOSE -> driveDiagnose(context, session)
                TutorState.EXPLAIN -> driveExplain(session)
                TutorState.PRACTICE -> drivePractice(session)
                TutorState.VERIFY -> driveVerify(context, session)
                TutorState.MASTERED -> { driveMastered(session); return }
                TutorState.MOVE_ON, TutorState.ESCALATED -> {
                    // Defensive only — driveMastered/ESCALATED's own producer already clear
                    // the slot immediately, so a session should never actually be READ back
                    // in one of these two states on a later tick. Clearing here anyway costs
                    // nothing and closes the gap if that assumption is ever wrong.
                    Log.w(TAG, "found lingering terminal session (${session.state}) for concept=${session.conceptId} — clearing")
                    TutorSessionLedger.clear()
                    return
                }
            }
            if (!advanced) return
        }
        Log.w(TAG, "hit MAX_AUTO_STEPS_PER_TICK — check for a TutorStateMachine loop")
    }

    // ── DIAGNOSE ─────────────────────────────────────────────────────────────

    private suspend fun driveDiagnose(context: Context, session: TutorSession): Boolean {
        val studentModel = withContext(Dispatchers.IO) { StudentModelBuilder.build(context) }
        val snapshot = studentModel.concepts[session.conceptId]
        val finding = TutorDiagnostics.diagnose(snapshot)
        val result = TutorSessionLedger.apply(TutorEvidence.Diagnostic(finding), now())
        return result is TutorTransitionResult.Advanced
    }

    // ── EXPLAIN ──────────────────────────────────────────────────────────────

    /**
     * Auto-advances immediately — no LLM teaching layer exists yet (deliberate; see
     * [TutorCycleManager]'s own class doc and [TutorState]'s scope note). The explanation
     * already shown to the student for this concept is the real gap-repair
     * [com.checkmate.planner.model.StudyTask]'s own `rationale` text
     * ([com.checkmate.learning.engine.LearningDecisionEngine]'s `rationaleFor` — genuine
     * written explanatory content already surfaced in the UI, not a placeholder), created
     * the moment [GapTaskManager] served this concept. Once a real LLM explanation layer
     * exists (P3.1-adjacent work), this is the one place it plugs in — replacing this
     * immediate auto-advance with "wait until the student has actually viewed/requested a
     * richer explanation" — without [TutorStateMachine]'s own EXPLAIN transition changing
     * at all.
     */
    private fun driveExplain(session: TutorSession): Boolean {
        val result = TutorSessionLedger.apply(TutorEvidence.ExplanationDelivered, now())
        return result is TutorTransitionResult.Advanced
    }

    // ── PRACTICE ─────────────────────────────────────────────────────────────

    /**
     * Consumes [GapTaskLedger]'s own already-imported round evidence — see class doc for
     * why this never requests its own Testmate test. Returns false (nothing to do yet)
     * until [GapTaskLedger.isActiveEvidenceImported] goes true, which happens on some later
     * tick once [GapTaskManager.evidencePollIfNeeded] actually imports a submitted result.
     */
    private fun drivePractice(session: TutorSession): Boolean {
        val conceptId = session.conceptId
        if (GapTaskLedger.activeConceptId() != conceptId) {
            // GapTaskLedger has moved on (covered/reassigned) while this tutor session was
            // still open — there is no P0b evidence stream left for it to ride on. Free the
            // slot rather than leave an orphaned session sitting in PRACTICE forever.
            Log.w(TAG, "concept=$conceptId is no longer GapTaskLedger's active concept — clearing stale tutor session")
            TutorSessionLedger.clear()
            return false
        }
        if (!GapTaskLedger.isActiveEvidenceImported()) return false // waiting on the student

        val attemptCount = GapTaskLedger.activeLastImportAttemptCount()
        val correctCount = GapTaskLedger.activeLastImportCorrectCount()
        val result = TutorSessionLedger.apply(TutorEvidence.PracticeAttempts(attemptCount, correctCount), now())
        if (result !is TutorTransitionResult.Advanced) {
            // Shouldn't happen at TARGETED_TEST_QUESTION_COUNT=15 >> MIN_PRACTICE_ATTEMPTS=3,
            // but stay defensive rather than get stuck re-reading the same rejected evidence
            // forever — ask for a fresh round exactly like a genuine VERIFY-fail would.
            Log.w(TAG, "concept=$conceptId practice evidence rejected ($result) — requesting a fresh round")
            GapTaskLedger.resetForNextRound()
            return false
        }
        return true
    }

    // ── VERIFY ───────────────────────────────────────────────────────────────

    /**
     * Split out from [drivePractice] rather than inlined at its tail, so a session that
     * somehow persists mid-VERIFY across a process death/crash (advanced PRACTICE->VERIFY
     * but didn't reach a further state before the process died) is still recoverable on the
     * next tick — this re-derives everything it needs (a fresh [com.checkmate.learning.model.ConceptSnapshot],
     * fresh dominant error) from durable state rather than anything carried over in memory
     * from [drivePractice]'s own call.
     */
    private suspend fun driveVerify(context: Context, session: TutorSession): Boolean {
        val conceptId = session.conceptId
        if (GapTaskLedger.activeConceptId() != conceptId) {
            Log.w(TAG, "concept=$conceptId is no longer GapTaskLedger's active concept — clearing stale tutor session")
            TutorSessionLedger.clear()
            return false
        }

        val studentModel = withContext(Dispatchers.IO) { StudentModelBuilder.build(context) }
        val snapshot = studentModel.concepts[conceptId]
        if (snapshot == null) {
            // Mastery recompute (inside TargetedTestEvidenceImporter.import) should always
            // leave a snapshot behind once evidence exists — this would mean something
            // upstream is broken. Don't fabricate one; ask for a fresh round instead of
            // guessing at VERIFY with no real evidence.
            Log.w(TAG, "concept=$conceptId has no ConceptSnapshot to verify against — requesting a fresh round")
            GapTaskLedger.resetForNextRound()
            return false
        }

        val dominantError = studentModel.unresolvedErrors
            .filter { it.conceptId == conceptId }
            .maxByOrNull { it.occurrences }
            ?.errorType

        val result = TutorSessionLedger.apply(TutorEvidence.Verification(snapshot, dominantError), now())
        val advancedTo = (result as? TutorTransitionResult.Advanced)?.session?.state
        if (advancedTo != TutorState.MASTERED) {
            // Looped back to EXPLAIN/PRACTICE, or escalated — either way this round's
            // Testmate evidence is now spent. Same mechanism GapTaskManager's own
            // resolveDoneConcept already uses for "student finished the task, still below
            // threshold" — a fresh round gets requested the next time
            // GapTaskManager.createTargetedTestIfNeeded runs.
            GapTaskLedger.resetForNextRound()
        }
        return result is TutorTransitionResult.Advanced
    }

    // ── MASTERED ─────────────────────────────────────────────────────────────

    private fun driveMastered(session: TutorSession) {
        TutorSessionLedger.apply(TutorEvidence.CloseOut, now())
        TutorSessionLedger.clear()
        Log.d(TAG, "tutor session for concept=${session.conceptId} closed out (MASTERED -> MOVE_ON)")
        // Deliberately does NOT call GapTaskLedger.markCovered() — that call is reserved for
        // GapTaskManager.resolveDoneConcept's own independently-verified "task reached DONE
        // AND mastery recheck cleared threshold" path (see markCovered's own doc). The tutor
        // reaching MASTERED is a real, evidence-backed confirmation, but letting two
        // different code paths both decide "this concept is covered" risks exactly the kind
        // of double-write / inconsistent-state bug class this codebase has already spent a
        // long stretch fixing once for this same ledger. GapTaskManager's own flow will reach
        // the same conclusion independently once the student marks the task DONE.
    }

    private fun now(): Long = System.currentTimeMillis()
}
