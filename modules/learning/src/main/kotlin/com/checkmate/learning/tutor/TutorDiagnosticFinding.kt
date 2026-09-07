package com.checkmate.learning.tutor

import kotlinx.serialization.Serializable
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
 * much richer shape would either collide or silently shadow it. [TutorCycleManager]'s P3.2
 * wiring (see that file) keeps this deliberate: the LLM-produced [hypothesisType] here
 * enriches what EXPLAIN has to work with, but the coarse [DiagnosticFinding] driving
 * [TutorStateMachine]'s own transition stays [TutorDiagnostics]'s deterministic heuristic,
 * unchanged — matching that state machine's own "an LLM may PRODUCE the evidence this file
 * consumes... but never decides the transition itself" invariant.
 *
 * [Serializable]: P3.2's [TutorDiagnosticFindingLedger] persists this directly as one JSON
 * blob via CheckmatePrefs — same pattern [TutorSession] itself already establishes via
 * [TutorSessionLedger], for the same reason (a plain-data snapshot that needs to survive
 * one background-loop tick to the next, not a Room-modeled entity in its own right yet).
 */
@Serializable
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
