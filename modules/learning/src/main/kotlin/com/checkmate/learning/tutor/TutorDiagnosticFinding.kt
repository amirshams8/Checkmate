package com.checkmate.learning.tutor

import java.util.UUID

/**
 * Upgrade Blueprint Phase 3, P3.1 — the validated, durable Checkmate representation of a
 * diagnosis. The ONLY way to construct one is [TutorDiagnosticValidator.validate] (an LLM
 * hypothesis that clears validation) or [TutorDiagnosticValidator.deterministicFallback]
 * (no LLM involved) — never built directly from an untrusted [LlmDiagnosticResponse].
 *
 * NAMED DELIBERATELY DIFFERENT FROM [DiagnosticFinding]: that enum (KNOWN/UNKNOWN/
 * MISUNDERSTOOD/FORGOTTEN) is the coarse, already-load-bearing evidence type
 * [TutorEvidence.Diagnostic] carries into [TutorStateMachine] — reusing its name for this
 * much richer shape would either collide or silently shadow it. This type does not feed
 * [TutorStateMachine] in P3.1 — wiring a [TutorDiagnosticFinding] into
 * [TutorEvidence.Diagnostic] (via some [hypothesisType] -> [DiagnosticFinding] mapping) is
 * the P3.2 follow-up the roadmap describes as "swap TutorDiagnostics's deterministic path
 * for the validated LLM path," not part of this pass.
 *
 * Not yet Room-persisted or CheckmatePrefs-backed — P3.1 defines the shape and the
 * validation boundary; a durable store (mirroring [TutorSessionLedger]'s
 * CheckmatePrefs-blob pattern, or a proper Room table once more than one finding per
 * session needs to coexist) is follow-up work, not invented here.
 */
data class TutorDiagnosticFinding(
    val findingId: String = UUID.randomUUID().toString(),
    val conceptId: String,
    val hypothesisType: HypothesisType,
    val hypothesis: String,
    val suspectedSubConcept: String?,
    /** Only the subset of the LLM's claimed evidence that
     *  [TutorDiagnosticValidator.validate] actually confirmed belongs to this concept — see
     *  that function's own doc for why a partially-hallucinated evidence list is filtered,
     *  not rejected outright. */
    val validatedEvidenceQuestionIds: List<String>,
    val confidence: Double,
    val createdAt: Long,
    val source: FindingSource,
    val status: FindingStatus = FindingStatus.ACTIVE
)

enum class FindingSource { LLM, DETERMINISTIC }

/** [ACTIVE] until something acts on it. [VERIFIED]/[REJECTED]/[SUPERSEDED] are declared
 *  per the P3.1 spec's own contract but nothing in this pass transitions a finding into
 *  them yet — that belongs to whatever P3.2 follow-up consumes [TutorDiagnosticFinding]
 *  (e.g. VERIFY resolving it, or a fresh DIAGNOSE superseding a stale one). */
enum class FindingStatus { ACTIVE, VERIFIED, REJECTED, SUPERSEDED }
