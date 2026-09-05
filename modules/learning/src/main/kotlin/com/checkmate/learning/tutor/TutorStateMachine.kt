package com.checkmate.learning.tutor

import com.checkmate.learning.engine.MasteryEngine
import com.checkmate.learning.model.RetentionDecisionSnapshot

/**
 * Upgrade Blueprint Phase 3 skeleton — "state machine, not a chat prompt." This is the
 * ONLY file that decides whether a [TutorSession] may move from one [TutorState] to
 * another; every transition [executeTopCandidate]-style caller might want has to come
 * through here, same "one file owns the decision" discipline
 * [com.checkmate.learning.engine.LearningDecisionEngine] already established for
 * candidate ranking.
 *
 * CORE INVARIANT (this session's explicit spec): the tutor must never declare mastery
 * because a task was marked DONE. Enforced structurally, not by convention — this file
 * has no import of, and no parameter of type, [com.checkmate.planner.model.StudyTask] or
 * `TaskState` anywhere. The ONLY evidence [TutorState.VERIFY] accepts is
 * [TutorEvidence.Verification], which carries a real
 * [com.checkmate.learning.model.ConceptSnapshot] — the same mastery number
 * [com.checkmate.learning.engine.LearningDecisionEngine] itself trusts. A caller cannot
 * fake this by passing "the task got checked off"; there is no parameter shape that
 * would accept it.
 *
 * SECOND INVARIANT: [TutorState.PRACTICE] -> [TutorState.VERIFY] requires real evidence
 * of practice actually happening ([MIN_PRACTICE_ATTEMPTS]), not merely "the EXPLAIN step
 * finished" — see class doc's own "PRACTICE -> VERIFY should eventually require actual
 * evidence" requirement. [TutorEvidence.PracticeAttempts] below that minimum is rejected
 * ([TutorTransitionResult.Invalid]), not silently accepted with a warning — a caller
 * cannot advance a session by simply asserting "practice happened" without the count to
 * back it.
 *
 * DELIBERATE SCOPE NOTE (session 2 discussion): [TutorEvidence.Diagnostic] is
 * intentionally coarse today (KNOWN/UNKNOWN/MISUNDERSTOOD/FORGOTTEN, scoped to the
 * existing topic-level conceptId) rather than a validated sub-concept hypothesis — no
 * finer-grained diagnosis data model exists in this codebase yet
 * ([com.checkmate.learning.graph.KnowledgeGraph.conceptId] keys on exam/chapter/topic
 * only). A future P3.1 pass (diagnostic hypothesis persistence, LLM-proposed/
 * deterministic-validated, confidence-scored) can richen what PRODUCES a
 * [DiagnosticFinding] without touching this file's transition table — [Diagnostic]
 * stays the evidence shape either way. Likewise, a failed [TutorState.VERIFY] here
 * routes directly back to [TutorState.EXPLAIN] or [TutorState.PRACTICE] (deterministic
 * re-classification of the same failure, via [classifyFailure]) rather than back to
 * [TutorState.DIAGNOSE] — reasonable today because there is no richer hypothesis to
 * revise yet; once P3.1 exists, inserting a genuine re-DIAGNOSE hop on VERIFY failure is
 * an additive change to the table below, not a rewrite of it.
 *
 * DETERMINISTIC BY DESIGN, same discipline as
 * [com.checkmate.learning.engine.LearningDecisionEngine] — no LLM call anywhere in this
 * file, and none should ever be added. An LLM may PRODUCE the [TutorEvidence] this file
 * consumes (e.g. a future LLM-assisted [DiagnosticFinding] or the explanation text
 * behind [TutorEvidence.ExplanationDelivered]) but never decides the transition itself.
 */
object TutorStateMachine {

    /** How many EXPLAIN/PRACTICE retry cycles a session may burn through after a failed
     *  VERIFY before [TutorState.ESCALATED] fires instead of looping forever. First-pass
     *  constant, not calibrated against real outcomes — same "flag it, don't pretend
     *  it's derived" honesty [com.checkmate.learning.engine.LearningDecisionEngine]'s own
     *  constants already model. */
    const val MAX_CYCLES = 3

    /** Minimum real practice attempts (this PRACTICE pass, not lifetime) before
     *  [TutorState.VERIFY] may be entered — see class doc's SECOND INVARIANT. */
    const val MIN_PRACTICE_ATTEMPTS = 3

    /** Starts a brand-new session in [TutorState.DIAGNOSE] — the only state a session
     *  may ever begin in. No evidence required to start; [DIAGNOSE]'s own evidence
     *  requirement ([TutorEvidence.Diagnostic]) governs leaving it. */
    fun start(conceptId: String, now: Long): TutorSession {
        require(conceptId.isNotBlank()) { "start() requires a non-blank conceptId" }
        return TutorSession(
            conceptId = conceptId,
            state = TutorState.DIAGNOSE,
            cycleCount = 0,
            startedAt = now,
            updatedAt = now,
            history = emptyList()
        )
    }

    /**
     * The one entry point every transition goes through. Returns
     * [TutorTransitionResult.Invalid] — never throws — for: a terminal session, an
     * evidence type that doesn't match [session]'s current state, or evidence whose own
     * content doesn't clear the bar to advance. Every accepted transition appends exactly
     * one [TutorTransitionRecord] to [TutorSession.history].
     */
    fun transition(session: TutorSession, evidence: TutorEvidence, now: Long): TutorTransitionResult {
        if (session.state.isTerminal) {
            return TutorTransitionResult.Invalid(
                session,
                "session for ${session.conceptId} is already terminal (${session.state}) — no further transitions"
            )
        }

        return when (session.state) {
            TutorState.DIAGNOSE -> fromDiagnose(session, evidence, now)
            TutorState.EXPLAIN -> fromExplain(session, evidence, now)
            TutorState.PRACTICE -> fromPractice(session, evidence, now)
            TutorState.VERIFY -> fromVerify(session, evidence, now)
            TutorState.MASTERED -> fromMastered(session, evidence, now)
            TutorState.MOVE_ON, TutorState.ESCALATED ->
                TutorTransitionResult.Invalid(session, "unreachable — terminal check above already handled this")
        }
    }

    // ── DIAGNOSE ─────────────────────────────────────────────────────────────

    private fun fromDiagnose(session: TutorSession, evidence: TutorEvidence, now: Long): TutorTransitionResult {
        val diagnostic = evidence as? TutorEvidence.Diagnostic
            ?: return wrongEvidence(session, TutorState.DIAGNOSE, evidence)

        return when (diagnostic.finding) {
            // Already knows it — no re-teach needed, go straight to a confirmation check.
            DiagnosticFinding.KNOWN -> advance(
                session, TutorState.VERIFY, now,
                "diagnostic=KNOWN — skipping re-teach, confirming mastery directly"
            )
            DiagnosticFinding.UNKNOWN, DiagnosticFinding.MISUNDERSTOOD, DiagnosticFinding.FORGOTTEN -> advance(
                session, TutorState.EXPLAIN, now,
                "diagnostic=${diagnostic.finding} — needs teaching before practice"
            )
        }
    }

    // ── EXPLAIN ──────────────────────────────────────────────────────────────

    private fun fromExplain(session: TutorSession, evidence: TutorEvidence, now: Long): TutorTransitionResult {
        if (evidence !is TutorEvidence.ExplanationDelivered) {
            return wrongEvidence(session, TutorState.EXPLAIN, evidence)
        }
        return advance(session, TutorState.PRACTICE, now, "explanation delivered — moving to guided practice")
    }

    // ── PRACTICE ─────────────────────────────────────────────────────────────

    private fun fromPractice(session: TutorSession, evidence: TutorEvidence, now: Long): TutorTransitionResult {
        val attempts = evidence as? TutorEvidence.PracticeAttempts
            ?: return wrongEvidence(session, TutorState.PRACTICE, evidence)

        require(attempts.attemptCount >= 0) { "attemptCount cannot be negative" }
        require(attempts.correctCount in 0..attempts.attemptCount) {
            "correctCount (${attempts.correctCount}) must be between 0 and attemptCount (${attempts.attemptCount})"
        }

        // SECOND INVARIANT — see class doc. Insufficient practice evidence is a
        // rejection, not a silent no-op or an early pass-through to VERIFY.
        if (attempts.attemptCount < MIN_PRACTICE_ATTEMPTS) {
            return TutorTransitionResult.Invalid(
                session,
                "only ${attempts.attemptCount} practice attempt(s) recorded, need at least " +
                    "$MIN_PRACTICE_ATTEMPTS before VERIFY can run"
            )
        }

        return advance(
            session, TutorState.VERIFY, now,
            "${attempts.attemptCount} practice attempts recorded (${attempts.correctCount} correct) — verifying"
        )
    }

    // ── VERIFY ───────────────────────────────────────────────────────────────

    private fun fromVerify(session: TutorSession, evidence: TutorEvidence, now: Long): TutorTransitionResult {
        val verification = evidence as? TutorEvidence.Verification
            ?: return wrongEvidence(session, TutorState.VERIFY, evidence)

        val snapshot = verification.snapshot
        require(snapshot.conceptId == session.conceptId) {
            "verification snapshot is for concept ${snapshot.conceptId}, session is for ${session.conceptId}"
        }

        val mastered = snapshot.mastery >= MasteryEngine.MASTERY_THRESHOLD &&
            snapshot.retentionDecision != RetentionDecisionSnapshot.REVIEW

        if (mastered) {
            return advance(
                session, TutorState.MASTERED, now,
                "verified mastery %.2f (>= %.2f threshold), retention=%s".format(
                    snapshot.mastery, MasteryEngine.MASTERY_THRESHOLD, snapshot.retentionDecision
                )
            )
        }

        // Not mastered. Before routing back, check the retry budget — this is the
        // ONLY place MAX_CYCLES is spent, since VERIFY is the only state that can loop.
        if (session.cycleCount >= MAX_CYCLES) {
            return advance(
                session, TutorState.ESCALATED, now,
                "mastery %.2f still below threshold after $MAX_CYCLES retry cycle(s) — escalating, " +
                    "not looping further".format(snapshot.mastery)
            )
        }

        val failureShape = classifyFailure(verification.dominantErrorType)
        val nextState = when (failureShape) {
            VerifyFailureShape.CONCEPTUAL -> TutorState.EXPLAIN
            VerifyFailureShape.PROCEDURAL -> TutorState.PRACTICE
        }
        val reason = "mastery %.2f below threshold, dominant error=%s (%s) — routing back to %s (cycle %d)".format(
            snapshot.mastery, verification.dominantErrorType ?: "none", failureShape, nextState, session.cycleCount + 1
        )
        return advance(session, nextState, now, reason, incrementCycle = true)
    }

    /**
     * Blueprint's own error taxonomy (Phase 1.6) split into two buckets: a genuine
     * knowledge gap re-teaches ([VerifyFailureShape.CONCEPTUAL]); a knowledge-is-there-
     * but-execution-failed error just needs more reps at the same explanation
     * ([VerifyFailureShape.PROCEDURAL]). Same reasoning
     * [com.checkmate.learning.engine.LearningDecisionEngine.classifyIntent] already
     * applies when it picks INCREASE_DIFFICULTY for a high-mastery CARELESS/
     * TIME_PRESSURE pattern instead of REPAIR_CONCEPT — that same distinction, reused
     * here for VERIFY-failure routing instead of intervention selection. `null` (no
     * error classification available, or every attempt was a near-miss with no single
     * dominant type) defaults to PROCEDURAL — same "don't assume a conceptual gap
     * without positive evidence of one" caution
     * [com.checkmate.learning.engine.ErrorEngine.classify] already applies when it
     * defaults an unclassifiable wrong answer to UNKNOWN_CONCEPT rather than a specific
     * bucket it has no basis for — the difference here is which unlabeled default is
     * SAFER for a retry loop: assuming procedural (more practice) wastes less of the
     * student's time on a wrong hypothesis than assuming conceptual (a full re-teach)
     * when the truth is unknown.
     */
    internal fun classifyFailure(dominantErrorType: String?): VerifyFailureShape = when (dominantErrorType) {
        "UNKNOWN_CONCEPT", "MISCONCEPTION", "FORMULA_RECALL", "FORMULA_SELECTION" ->
            VerifyFailureShape.CONCEPTUAL
        else -> VerifyFailureShape.PROCEDURAL
    }

    // ── MASTERED ─────────────────────────────────────────────────────────────

    private fun fromMastered(session: TutorSession, evidence: TutorEvidence, now: Long): TutorTransitionResult {
        if (evidence !is TutorEvidence.CloseOut) {
            return wrongEvidence(session, TutorState.MASTERED, evidence)
        }
        return advance(session, TutorState.MOVE_ON, now, "close-out — mastery confirmation consumed")
    }

    // ── shared helpers ───────────────────────────────────────────────────────

    private fun advance(
        session: TutorSession,
        to: TutorState,
        now: Long,
        reason: String,
        incrementCycle: Boolean = false
    ): TutorTransitionResult.Advanced {
        val record = TutorTransitionRecord(from = session.state, to = to, reason = reason, timestamp = now)
        return TutorTransitionResult.Advanced(
            session.copy(
                state = to,
                cycleCount = if (incrementCycle) session.cycleCount + 1 else session.cycleCount,
                updatedAt = now,
                history = session.history + record
            )
        )
    }

    private fun wrongEvidence(
        session: TutorSession,
        expectedState: TutorState,
        evidence: TutorEvidence
    ): TutorTransitionResult.Invalid = TutorTransitionResult.Invalid(
        session,
        "session is in $expectedState, cannot accept ${evidence::class.simpleName} evidence"
    )
}
