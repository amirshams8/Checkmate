package com.checkmate.learning.tutor

import kotlinx.serialization.Serializable

/**
 * One in-progress (or just-finished) teaching cycle for a single concept. Immutable —
 * every [TutorStateMachine.transition] call returns a NEW [TutorSession] rather than
 * mutating this one, same "derived snapshot, never mutated in place" discipline
 * [com.checkmate.learning.model.StudentModel]'s own class doc establishes. Persistence
 * (surviving process death/relaunch) is [TutorSessionLedger]'s job, not this class's —
 * this is a plain value type with no I/O of its own, so [TutorStateMachine] stays a pure
 * function of (session, evidence) that a JVM unit test can exercise with zero Android
 * dependencies.
 *
 * [Serializable]: [TutorSessionLedger] persists this directly as one JSON blob via
 * CheckmatePrefs — same "plain data, no engine/model-internal types" shape
 * [com.checkmate.planner.intervention.GapTaskLedger]'s own persisted `WarningLogEntry`
 * already establishes for its history — which is also why this type carries no
 * [com.checkmate.learning.model.ConceptSnapshot] (only [TutorEvidence.Verification]
 * does, and evidence itself is never persisted, only its effect on [state]/[history]).
 */
@Serializable
data class TutorSession(
    val conceptId: String,
    val state: TutorState,
    /** How many EXPLAIN/PRACTICE retry loops this session has burned through after a
     *  failed [TutorState.VERIFY] — see [TutorStateMachine.MAX_CYCLES]. Zero until the
     *  first VERIFY failure; never decremented. */
    val cycleCount: Int = 0,
    val startedAt: Long,
    val updatedAt: Long,
    /** Full audit trail, oldest first — every transition [TutorStateMachine] has ever
     *  accepted for this session, including the ones that looped back to EXPLAIN/
     *  PRACTICE rather than advancing toward MASTERED. Not capped here (unlike e.g.
     *  [com.checkmate.planner.intervention.GapTaskLedger]'s warning log) since a single
     *  tutor session's own history is inherently small — [TutorStateMachine.MAX_CYCLES]
     *  already bounds it. */
    val history: List<TutorTransitionRecord> = emptyList()
) {
    init {
        require(conceptId.isNotBlank()) { "TutorSession requires a non-blank conceptId" }
        require(cycleCount >= 0) { "cycleCount cannot be negative" }
    }
}

/** One accepted transition, kept for the session's audit trail. `reason` is a short,
 *  human-readable justification (e.g. "diagnostic=KNOWN, skipping re-teach") — the same
 *  kind of self-documenting-decision string
 *  [com.checkmate.learning.engine.LearningDecisionEngine]'s own `rationale` field on
 *  [com.checkmate.learning.engine.LearningDecisionEngine.CandidateIntervention] already
 *  establishes, not a free-text log line. */
@Serializable
data class TutorTransitionRecord(
    val from: TutorState,
    val to: TutorState,
    val reason: String,
    val timestamp: Long
)

/** What [TutorStateMachine.transition] returns — success carries the new session,
 *  failure carries WHY, so a caller (or a test) never has to guess whether "nothing
 *  changed" meant "already at that state" or "evidence didn't clear the bar". */
sealed class TutorTransitionResult {
    data class Advanced(val session: TutorSession) : TutorTransitionResult()

    /** Rejected — either the evidence type doesn't match what [session]'s current
     *  [TutorState] is waiting on, the session is already [TutorState.isTerminal], or
     *  the evidence's own content didn't clear the bar for advancing (e.g.
     *  [TutorEvidence.PracticeAttempts] below [TutorStateMachine.MIN_PRACTICE_ATTEMPTS]
     *  — see that val's own doc for why this is a rejection, not a silent no-op). The
     *  original, unmodified [session] is included so a caller doesn't need to track it
     *  separately across a failed call. */
    data class Invalid(val session: TutorSession, val reason: String) : TutorTransitionResult()
}
