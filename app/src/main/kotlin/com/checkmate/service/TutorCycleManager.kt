package com.checkmate.service

import android.content.Context
import android.util.Log
import com.checkmate.learning.student.StudentModelBuilder
import com.checkmate.learning.tutor.DiagnosticFinding
import com.checkmate.learning.tutor.DirectLlmGateway
import com.checkmate.learning.tutor.TutorDiagnosticContextBuilder
import com.checkmate.learning.tutor.TutorDiagnosticFindingLedger
import com.checkmate.learning.tutor.TutorDiagnosticValidator
import com.checkmate.learning.tutor.TutorDiagnostics
import com.checkmate.learning.tutor.TutorEvidence
import com.checkmate.learning.tutor.TutorExplanationContext
import com.checkmate.learning.tutor.TutorSession
import com.checkmate.learning.tutor.TutorSessionLedger
import com.checkmate.learning.tutor.TutorState
import com.checkmate.learning.tutor.TutorTransitionResult
import com.checkmate.planner.intervention.GapTaskLedger
import com.checkmate.ui.mentor.MentorViewModel
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
 * P3.2 UPDATE — DIAGNOSE/EXPLAIN now DO reach an LLM (see [driveDiagnose]/[driveExplain]),
 * but the FSM TRANSITION itself never does — [DiagnosticFinding] (the coarse KNOWN/UNKNOWN/
 * MISUNDERSTOOD/FORGOTTEN evidence [TutorEvidence.Diagnostic] carries into
 * [com.checkmate.learning.tutor.TutorStateMachine]) still comes exclusively from
 * [TutorDiagnostics]'s deterministic heuristic, unchanged. What the LLM path adds is a
 * richer [com.checkmate.learning.tutor.TutorDiagnosticFinding] (sub-concept hypothesis,
 * structured explanation content) layered ON TOP, best-effort, never blocking or altering
 * the deterministic transition below it — matching
 * [com.checkmate.learning.tutor.TutorStateMachine]'s own "DETERMINISTIC BY DESIGN" doc: "An
 * LLM may PRODUCE the [TutorEvidence] this file consumes... but never decides the
 * transition itself."
 */
object TutorCycleManager {

    private const val TAG = "TutorCycleManager"

    /** Safety cap on how many state-to-state auto-advances (DIAGNOSE, EXPLAIN — the two
     *  states that need no external evidence) this drives in a single tick, so a logic bug
     *  that made [TutorStateMachine][com.checkmate.learning.tutor.TutorStateMachine] loop
     *  between two states could never hang this call forever. Five is comfortably above the
     *  three real states (DIAGNOSE/EXPLAIN/one PRACTICE-or-VERIFY check) a normal tick ever
     *  needs to walk through. */
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
                TutorState.EXPLAIN -> driveExplain(context, session)
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
                    TutorDiagnosticFindingLedger.clear()
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

        // P3.2: best-effort enrichment only — see class doc's P3.2 UPDATE note. `finding`
        // above (deterministic) is what actually decides this transition, below,
        // regardless of whether enrichment runs, succeeds, or is skipped entirely. Only
        // attempted when there's real evidence to reason over (snapshot != null) and
        // teaching is actually needed (not KNOWN) — an LLM asked to hypothesize about a
        // concept the student already knows, or one with zero evidence at all, has nothing
        // useful to add.
        if (snapshot != null && finding != DiagnosticFinding.KNOWN) {
            enrichDiagnosisIfPossible(context, session)
        }

        val result = TutorSessionLedger.apply(TutorEvidence.Diagnostic(finding), now())
        return result is TutorTransitionResult.Advanced
    }

    /**
     * Attempts an LLM-enriched diagnosis and stores it via [TutorDiagnosticFindingLedger]
     * for [driveExplain] to pick up on a later tick. Never throws, never blocks
     * [driveDiagnose]'s own deterministic transition — [TutorDiagnosticContextBuilder.build]
     * returning null, an LLM timeout/blank response, or a
     * [TutorDiagnosticValidator.ValidationResult.Rejected] all fall through to
     * [TutorDiagnosticValidator.deterministicFallback] rather than leaving nothing stored,
     * same "no silent failure, always land on a usable value" discipline the offline-first
     * LLM paths elsewhere in this codebase (e.g.
     * [com.checkmate.planner.intervention.InterventionFallback]) already follow.
     */
    private suspend fun enrichDiagnosisIfPossible(context: Context, session: TutorSession) {
        val diagCtx = TutorDiagnosticContextBuilder.build(
            context = context,
            conceptId = session.conceptId,
            tutorState = session.state,
            cycleCount = session.cycleCount
        ) ?: return

        val raw = runCatching { DirectLlmGateway.diagnose(diagCtx) }.getOrNull()
        val enriched = if (raw != null) {
            when (val result = TutorDiagnosticValidator.validate(raw, diagCtx, now())) {
                is TutorDiagnosticValidator.ValidationResult.Valid -> result.finding
                is TutorDiagnosticValidator.ValidationResult.Rejected -> {
                    Log.w(TAG, "concept=${session.conceptId} LLM diagnosis rejected (${result.reason}) — using deterministic fallback")
                    TutorDiagnosticValidator.deterministicFallback(diagCtx, now())
                }
            }
        } else {
            TutorDiagnosticValidator.deterministicFallback(diagCtx, now())
        }
        TutorDiagnosticFindingLedger.store(enriched)
    }

    // ── EXPLAIN ──────────────────────────────────────────────────────────────

    /**
     * P3.2: reads back the [com.checkmate.learning.tutor.TutorDiagnosticFinding]
     * [driveDiagnose] enriched (if any), requests a structured explanation via
     * [DirectLlmGateway.explain], and posts it to the student through the same channel
     * [ProactiveMentor] already uses ([MentorViewModel.appendProactiveMessage] +
     * [MentorNotifier.notify]) — not a new delivery channel.
     *
     * Still auto-advances EXPLAIN -> PRACTICE regardless of whether delivery succeeded —
     * [TutorEvidence.ExplanationDelivered]'s own doc is explicit that "which type of
     * explanation was chosen is a teaching-layer concern this state machine deliberately
     * has no opinion on — it only needs to know teaching happened before practice can
     * start." A richer LLM explanation and the original gap-repair
     * [com.checkmate.planner.model.StudyTask]'s own rationale text (already shown in the
     * UI regardless) are equally "an explanation was delivered" as far as the FSM is
     * concerned — this only changes WHAT the student additionally sees in Mentor chat, not
     * whether the transition is allowed.
     */
    private suspend fun driveExplain(context: Context, session: TutorSession): Boolean {
        deliverExplanationIfPossible(context, session)
        val result = TutorSessionLedger.apply(TutorEvidence.ExplanationDelivered, now())
        return result is TutorTransitionResult.Advanced
    }

    /**
     * No-op (falls through to the pre-existing StudyTask-rationale-only behavior) when
     * there's no enriched finding for THIS concept — including a finding left over from a
     * different, already-superseded session, guarded by the conceptId check below, since
     * [TutorDiagnosticFindingLedger] is a single unkeyed slot (see that object's own doc).
     */
    private suspend fun deliverExplanationIfPossible(context: Context, session: TutorSession) {
        val finding = TutorDiagnosticFindingLedger.current() ?: return
        if (finding.conceptId != session.conceptId) return

        val diagCtx = TutorDiagnosticContextBuilder.build(
            context = context,
            conceptId = session.conceptId,
            tutorState = session.state,
            cycleCount = session.cycleCount
        ) ?: return

        val explanation = runCatching {
            DirectLlmGateway.explain(TutorExplanationContext(diagCtx, finding))
        }.getOrNull() ?: return

        val message = buildString {
            append(explanation.explanation)
            explanation.workedExample?.let { append("\n\nExample: $it") }
            explanation.commonTrap?.let { append("\n\nWatch out: $it") }
            explanation.checkQuestion?.let { append("\n\n$it") }
        }
        MentorViewModel.appendProactiveMessage(message)
        MentorNotifier.notify(context, explanation.explanation)
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
            TutorDiagnosticFindingLedger.clear()
            return false
        }
        if (!GapTaskLedger.isActiveEvidenceImported()) return false // waiting on the student

        val attemptCount = GapTaskLedger.activeLastImportAttemptCount()
        val correctCount = GapTaskLedger.activeLastImportCorrectCount()
        val result = TutorSessionLedger.apply(TutorEvidence.PracticeAttempts(attemptCount, correctCount), now())
        if (result !is TutorTransitionResult.Advanced) {
            // Shouldn't happen — GapTaskManager's repair test is now uncapped (every
            // wrong/skipped question for the concept, previously capped at 15), so
            // attemptCount only ever grows further past MIN_PRACTICE_ATTEMPTS=3,
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
            TutorDiagnosticFindingLedger.clear()
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
        TutorDiagnosticFindingLedger.clear()
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
