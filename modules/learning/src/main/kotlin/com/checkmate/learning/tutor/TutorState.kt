package com.checkmate.learning.tutor

/**
 * Upgrade Blueprint Phase 3 ("Adaptive tutor — state machine, not a chat prompt").
 *
 * This is the tutor state machine sitting ABOVE the existing decision/intervention
 * infrastructure, not replacing it:
 * ```
 * Evidence -> StudentModel -> Mastery/Error/Retention -> LearningDecisionEngine
 *     -> CandidateIntervention -> [TutorStateMachine] -> Orchestrator -> StudyTask
 * ```
 * [LearningDecisionEngine][com.checkmate.learning.engine.LearningDecisionEngine] still
 * decides WHAT concept is worth working on next (unchanged — see [TutorSessionLedger]'s
 * connector for the one place this file's package reads a
 * [com.checkmate.learning.engine.LearningDecisionEngine.CandidateIntervention]). This
 * state machine only governs HOW a single teaching cycle for one already-chosen concept
 * progresses once started — a strictly narrower, additive concern, not a second
 * competing decision engine.
 *
 * States, per this session's spec:
 * ```
 * DIAGNOSE -> EXPLAIN -> PRACTICE -> VERIFY -> { MASTERED -> MOVE_ON | EXPLAIN/PRACTICE }
 * ```
 * [MASTERED] and [MOVE_ON] are kept as two distinct states rather than collapsed into
 * one, even though the transition between them never requires new evidence: [MASTERED]
 * is the moment mastery was confirmed; [MOVE_ON] is the moment that confirmation was
 * actually consumed (session closed, concept freed up for whatever
 * [com.checkmate.learning.engine.LearningDecisionEngine] ranks next). Giving the
 * close-out step its own state is what lets a caller hook "record this concept as
 * covered" at exactly one place ([MASTERED]->[MOVE_ON]) without conflating it with the
 * evidence-driven decision that produced [MASTERED] in the first place.
 *
 * [ESCALATED] is not in the user-facing diagram but is required by the core invariant
 * this machine exists to enforce (see [TutorStateMachine]'s class doc): an
 * EXPLAIN/PRACTICE retry loop that never converges must terminate into something other
 * than silently declaring [MASTERED] anyway. See [TutorStateMachine.MAX_CYCLES].
 */
enum class TutorState {
    DIAGNOSE,
    EXPLAIN,
    PRACTICE,
    VERIFY,
    /** Terminal-ish: mastery evidence just confirmed, close-out not yet run. */
    MASTERED,
    /** Terminal: close-out ran, session consumed. No further transitions valid. */
    MOVE_ON,
    /** Terminal: [TutorStateMachine.MAX_CYCLES] EXPLAIN/PRACTICE retries exhausted
     *  without clearing mastery. Deliberately NOT [MASTERED] — the whole point of this
     *  state existing is that [TutorStateMachine] must never launder "gave up" into
     *  "succeeded". A caller (next milestone: wiring into
     *  [com.checkmate.planner.intervention.LearningInterventionOrchestrator]) is
     *  expected to read this as a signal to fall back to
     *  [com.checkmate.learning.engine.LearningDecisionEngine.LearningInterventionIntent.REDUCE_DIFFICULTY]
     *  or a fresh [com.checkmate.learning.engine.LearningDecisionEngine.LearningInterventionIntent.REPAIR_CONCEPT]
     *  re-rank rather than assuming the concept is done — not built in this pass. */
    ESCALATED;

    val isTerminal: Boolean get() = this == MOVE_ON || this == ESCALATED
}

/**
 * Blueprint Phase 3's own framing: "first check known/unknown/misunderstood/forgotten,
 * then pick the type of response" — this is that four-way check, the required input to
 * the one [DIAGNOSE] transition.
 */
enum class DiagnosticFinding {
    /** Already knows it — skip re-teaching, go straight to a confirmation check. */
    KNOWN,
    /** Never learned it. */
    UNKNOWN,
    /** Learned it wrong — a misconception, not a gap. */
    MISUNDERSTOOD,
    /** Learned it correctly once, no longer retrievable — a retention lapse, not a
     *  fresh teaching gap. Routed the same as UNKNOWN/MISUNDERSTOOD today (both need
     *  [TutorState.EXPLAIN]); kept as its own case rather than merged so a future pass
     *  can special-case "re-explain briefer, they've seen this before" without
     *  reshaping this enum. */
    FORGOTTEN
}

/** Blueprint's own four-way error-type-driven split for what a failed [TutorState.VERIFY]
 *  should route back to — conceptual failure re-teaches, procedural failure just needs
 *  more reps. Named independently of [com.checkmate.learning.model.ErrorType] (kept as
 *  the `.name` string on [com.checkmate.learning.model.ErrorPatternSnapshot] already) so
 *  this package doesn't need to interpret every one of that enum's members — only which
 *  bucket each one falls into (see [TutorStateMachine.classifyFailure]). */
internal enum class VerifyFailureShape { CONCEPTUAL, PROCEDURAL }
