package com.checkmate.learning.tutor

import com.checkmate.learning.model.ConceptSnapshot

/**
 * What causes each [TutorState] transition — see [TutorStateMachine] for the table this
 * feeds. One evidence type per state a session can be waiting IN, not per transition, so
 * [TutorStateMachine.transition] can reject an evidence/state mismatch before it even
 * looks at the evidence's contents (see [TutorTransitionResult.Invalid]).
 *
 * Deliberately NOT keyed on anything from [com.checkmate.planner.model.StudyTask]
 * (no `TaskState.DONE`, no `checksPassed`) — that is the core invariant this file
 * exists to enforce (Phase 3 spec: "the tutor must not declare mastery because a task
 * was marked DONE"). [Verification] carries a real
 * [com.checkmate.learning.model.ConceptSnapshot] — the same mastery/retention machinery
 * [com.checkmate.learning.engine.LearningDecisionEngine] itself reads — as the only
 * thing [TutorStateMachine] is allowed to treat as proof of mastery.
 */
sealed class TutorEvidence {

    /** Answers [TutorState.DIAGNOSE]. */
    data class Diagnostic(val finding: DiagnosticFinding) : TutorEvidence()

    /** Answers [TutorState.EXPLAIN] — the mentor actually delivered *some* explanatory
     *  response (micro-explanation / worked example / analogy / question / diagram /
     *  derivation, per the blueprint's own "not a fixed-length essay" framing). Which
     *  *type* of explanation was chosen is a teaching-layer concern this state machine
     *  deliberately has no opinion on — it only needs to know teaching happened before
     *  practice can start. */
    object ExplanationDelivered : TutorEvidence()

    /**
     * Answers [TutorState.PRACTICE]. `attemptCount`/`correctCount` are real recorded
     * attempts for THIS practice pass (i.e. since the current [TutorState.PRACTICE]
     * entry, not lifetime) — [TutorStateMachine] enforces
     * [TutorStateMachine.MIN_PRACTICE_ATTEMPTS] itself; a caller cannot shortcut the
     * check by pre-filtering. This is the second half of the "no evidence, no
     * advance" invariant: [TutorState.PRACTICE] -> [TutorState.VERIFY] requires this
     * evidence to clear a real minimum, exactly like the class doc's "PRACTICE -> VERIFY
     * should eventually require actual evidence" requirement.
     */
    data class PracticeAttempts(val attemptCount: Int, val correctCount: Int) : TutorEvidence()

    /**
     * Answers [TutorState.VERIFY]. `snapshot` must be freshly rebuilt from the
     * VERIFY-phase attempts specifically (a caller's responsibility — this class only
     * consumes whatever [ConceptSnapshot] it's given), so a stale, pre-session snapshot
     * can never be replayed to fake a mastery decision. `dominantErrorType` is the
     * `.name` of the most frequent [com.checkmate.learning.model.ErrorType] behind this
     * verification pass's wrong answers, if any — null when every attempt was correct
     * or no error classification exists yet — and is what routes a failed VERIFY back
     * to [TutorState.EXPLAIN] (conceptual) vs [TutorState.PRACTICE] (procedural); see
     * [TutorStateMachine.classifyFailure].
     */
    data class Verification(
        val snapshot: ConceptSnapshot,
        val dominantErrorType: String?
    ) : TutorEvidence()

    /** Answers [TutorState.MASTERED] — the one transition that needs no new evidence
     *  at all, since mastery was already confirmed to enter [TutorState.MASTERED] in
     *  the first place. Exists as an explicit evidence value (rather than a no-arg
     *  overload) so [TutorStateMachine.transition]'s signature stays uniform across
     *  every state. */
    object CloseOut : TutorEvidence()
}
