package com.checkmate.learning.tutor

import com.checkmate.learning.model.PrerequisiteRef
import com.checkmate.learning.model.RetentionDecisionSnapshot

/**
 * Upgrade Blueprint Phase 3, P3.1 — the context schema fed to [TutorLlmGateway.diagnose].
 * Assembled by [TutorDiagnosticContextBuilder] from already-derived intelligence
 * ([com.checkmate.learning.model.StudentModel] + [com.checkmate.learning.model.ErrorRecord]
 * history) — this type itself carries no I/O and derives nothing new, same "aggregates,
 * doesn't re-derive" discipline
 * [com.checkmate.learning.student.StudentModelBuilder]'s own class doc establishes for the
 * layer beneath it.
 *
 * [knownEvidenceQuestionIds] is the ONLY set of question ids
 * [TutorDiagnosticValidator.validate] will accept in a response's `evidenceQuestionIds` —
 * an id the LLM names that isn't in this set gets silently dropped, not trusted just
 * because the LLM said so (see that validator's own "evidence actually belongs to this
 * concept" check).
 */
data class TutorDiagnosticContext(
    val conceptId: String,
    val exam: String?,
    val subject: String?,
    val chapter: String?,
    val topic: String?,
    val mastery: Double,
    val retentionDecision: RetentionDecisionSnapshot,
    val forgettingRisk: Double,
    val attemptCount: Int,
    val recentAccuracy: Double,
    val lifetimeAccuracy: Double,
    val errorCount: Int,
    /** Most-recent-first, capped at [TutorDiagnosticContextBuilder.MAX_RECENT_ERRORS] —
     *  enough for an LLM prompt to reason over without unbounded growth for a concept with
     *  a long error history. */
    val recentErrors: List<EvidenceErrorRef>,
    val weakPrerequisites: List<PrerequisiteRef>,
    /** The tutor session's own state/cycleCount at the moment DIAGNOSE ran — lets the LLM
     *  (and a human reading a stored [TutorDiagnosticFinding] later) tell a first-pass
     *  diagnosis apart from one produced after EXPLAIN/PRACTICE already looped once. */
    val tutorState: TutorState,
    val cycleCount: Int
) {
    val knownEvidenceQuestionIds: Set<String> get() = recentErrors.map { it.questionId }.toSet()
}

/**
 * One [com.checkmate.learning.model.ErrorRecord], trimmed to what a diagnosis prompt (or
 * [TutorDiagnosticValidator]'s evidence check) actually needs — not the full Room entity.
 */
data class EvidenceErrorRef(
    val questionId: String,
    /** [com.checkmate.learning.model.ErrorType.name] — same "plain string, not the
     *  engine-internal enum" choice [com.checkmate.learning.model.ErrorPatternSnapshot]
     *  already makes for the same reason. */
    val errorType: String,
    val timestamp: Long
)

/**
 * Context for [TutorLlmGateway.explain] — the accepted diagnosis plus everything
 * [TutorDiagnosticContext] already carries about the concept, so the explain prompt never
 * needs to re-derive or re-fetch anything [diagnostic] already has. Composition over
 * duplicating fields, same reasoning [TutorExplanationContext]'s sibling types in this
 * package already follow.
 */
data class TutorExplanationContext(
    val diagnostic: TutorDiagnosticContext,
    val finding: TutorDiagnosticFinding
)
