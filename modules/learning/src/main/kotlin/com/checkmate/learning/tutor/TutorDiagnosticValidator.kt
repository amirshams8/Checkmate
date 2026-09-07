package com.checkmate.learning.tutor

/**
 * Upgrade Blueprint Phase 3, P3.1 — "substantive validation lives in Checkmate Kotlin
 * only, never duplicated into a gateway/Worker layer." This is that validation, and the
 * ONLY path from an untrusted [LlmDiagnosticResponse] to a durable [TutorDiagnosticFinding].
 * Pure and synchronous — no I/O, no Android dependency — same "engine tested pure" split
 * [TutorStateMachine] itself already models, so this is exercisable from a plain JVM test
 * with zero Context/Room/network involved.
 *
 * Pipeline this implements:
 * ```
 * TutorDiagnosticContext -> LLM Gateway -> LlmDiagnosticResponse (untrusted)
 *   -> TutorDiagnosticValidator:
 *        - concept exists?           (context.conceptId non-blank)
 *        - hypothesis non-empty?     (the one field with no honest default)
 *        - confidence in bounds?     ([0.0, 1.0])
 *        - evidence belongs here?    (filtered against context.knownEvidenceQuestionIds)
 *        - schema valid?             (LlmDiagnosticResponse.parse already enforced this
 *                                      before this function ever sees a response)
 *   -> TutorDiagnosticFinding (durable)
 * ```
 */
object TutorDiagnosticValidator {

    sealed class ValidationResult {
        data class Valid(val finding: TutorDiagnosticFinding) : ValidationResult()
        /** Never thrown, always returned — same "caller decides what happens next, this
         *  function never has side effects" discipline as [TutorTransitionResult.Invalid]. */
        data class Rejected(val reason: String) : ValidationResult()
    }

    /**
     * Validates [response] against [context] and produces a [TutorDiagnosticFinding] on
     * success. `evidenceQuestionIds` the LLM named that AREN'T in
     * [TutorDiagnosticContext.knownEvidenceQuestionIds] are silently dropped rather than
     * causing outright rejection — a hypothesis citing one hallucinated id alongside three
     * real ones is still a usable diagnosis; only the unbacked claim is discarded, not the
     * whole finding. An `hypothesisType` string that doesn't match [HypothesisType]'s
     * closed vocabulary is likewise soft-mapped to [HypothesisType.UNKNOWN] by
     * [HypothesisType.parse], not a rejection reason — see that function's own doc.
     *
     * Genuine rejections are reserved for the response being unusable as a diagnosis at
     * all: no hypothesis text, or a confidence value outside the range a caller could
     * sanely act on.
     */
    fun validate(
        response: LlmDiagnosticResponse,
        context: TutorDiagnosticContext,
        now: Long
    ): ValidationResult {
        if (context.conceptId.isBlank()) {
            return ValidationResult.Rejected("context has no concept to diagnose")
        }
        if (response.hypothesis.isBlank()) {
            return ValidationResult.Rejected("empty hypothesis")
        }
        if (response.confidence < 0.0 || response.confidence > 1.0) {
            return ValidationResult.Rejected("confidence ${response.confidence} out of [0.0, 1.0] range")
        }

        val validatedEvidence = response.evidenceQuestionIds.filter { it in context.knownEvidenceQuestionIds }

        return ValidationResult.Valid(
            TutorDiagnosticFinding(
                conceptId = context.conceptId,
                hypothesisType = HypothesisType.parse(response.hypothesisType),
                hypothesis = response.hypothesis.trim(),
                suspectedSubConcept = response.suspectedSubConcept?.takeIf { it.isNotBlank() },
                validatedEvidenceQuestionIds = validatedEvidence,
                confidence = response.confidence,
                createdAt = now,
                source = FindingSource.LLM
            )
        )
    }

    /**
     * Deterministic fallback finding — same [TutorDiagnosticFinding] shape an LLM diagnosis
     * would produce, so a downstream caller never has to special-case "no LLM was
     * involved." Mirrors
     * [com.checkmate.planner.intervention.InterventionFallback]'s own "the deterministic
     * path produces the same shape a real negotiation would" discipline.
     *
     * Used specifically for "an LLM diagnosis was attempted and didn't come back usable"
     * (blank/timeout/unparseable/[ValidationResult.Rejected]) — this does NOT replace
     * [TutorDiagnostics.diagnose], which is the existing four-way heuristic still governing
     * DIAGNOSE when no LLM is configured at all (see [TutorCycleManager]'s own class doc:
     * "NO LLM CALL ANYWHERE IN THIS FILE" — wiring this in is the P3.2 follow-up, not part
     * of P3.1).
     */
    fun deterministicFallback(context: TutorDiagnosticContext, now: Long): TutorDiagnosticFinding =
        TutorDiagnosticFinding(
            conceptId = context.conceptId,
            hypothesisType = HypothesisType.UNKNOWN,
            hypothesis = "No LLM diagnosis available — falling back to deterministic evidence only.",
            suspectedSubConcept = null,
            validatedEvidenceQuestionIds = emptyList(),
            confidence = 0.0,
            createdAt = now,
            source = FindingSource.DETERMINISTIC
        )
}
