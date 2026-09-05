package com.checkmate.learning.tutor

import com.checkmate.learning.engine.MasteryEngine
import com.checkmate.learning.model.ConceptSnapshot
import com.checkmate.learning.model.RetentionDecisionSnapshot

/**
 * Upgrade Blueprint Phase 3, P3.2 item 1 ("DIAGNOSE -> real explanation... later this is
 * where the LLM can become useful") — explicitly deliberately NOT that yet. Per this
 * session's own instruction ("don't add LLM diagnosis yet... let DiagnosticFinding be
 * produced from the existing deterministic evidence: concept + wrong questions + error
 * patterns + mastery + difficulty + timing + retention"), this derives a
 * [DiagnosticFinding] purely from a [ConceptSnapshot] — the exact same read model
 * [com.checkmate.learning.engine.LearningDecisionEngine] itself already trusts, nothing
 * new.
 *
 * FUTURE SEAM (explicitly not built here): a P3.1 LLM-proposed hypothesis, once it exists,
 * replaces WHAT calls this / what feeds [TutorEvidence.Diagnostic] — not [TutorStateMachine]
 * itself, and not this function's signature. An LLM diagnoser would sit in FRONT of this
 * (or replace it) to produce a richer, sub-concept-scoped finding; [TutorStateMachine]'s own
 * transition table doesn't change either way — see [TutorState]'s own class doc.
 */
object TutorDiagnostics {

    /**
     * [snapshot] is null for a [com.checkmate.learning.engine.LearningDecisionEngine.LearningInterventionIntent.START_DIAGNOSTIC]
     * candidate specifically — that intent exists precisely because
     * [com.checkmate.learning.graph.KnowledgeGraph] flags a prerequisite the student has
     * never attempted, so no [ConceptSnapshot] row exists for it yet. [DiagnosticFinding.UNKNOWN]
     * is the only honest answer for "no evidence exists at all" — not a guess dressed up as
     * one of the other three findings.
     *
     * For a real snapshot (the
     * [com.checkmate.learning.engine.LearningDecisionEngine.LearningInterventionIntent.REPAIR_CONCEPT]
     * case — already attempted, mastery known), the heuristic:
     * - mastery cleared [MasteryEngine.MASTERY_THRESHOLD] but
     *   [RetentionDecisionSnapshot.REVIEW] means [com.checkmate.learning.engine.RetentionEngine]
     *   already independently flagged forgetting risk on this concept -> [DiagnosticFinding.FORGOTTEN]
     *   (learned it once, not retrievable now — re-teaching should be brief, not from scratch).
     * - real recorded errors ([ConceptSnapshot.errorCount] > 0) at still-below-threshold mastery
     *   -> [DiagnosticFinding.MISUNDERSTOOD] (they tried, and got a wrong pattern instilled —
     *   different from never having tried at all).
     * - mastery already at/above threshold with no retention flag -> [DiagnosticFinding.KNOWN]
     *   (skip re-teaching, confirm directly).
     * - anything else (below threshold, no errors recorded yet — e.g. only skipped/unattempted
     *   questions counted toward this concept so far) -> [DiagnosticFinding.UNKNOWN], the same
     *   "don't assume a specific gap without positive evidence of one" caution
     *   [com.checkmate.learning.engine.ErrorEngine.classify] already applies to its own
     *   unclassifiable case.
     */
    fun diagnose(snapshot: ConceptSnapshot?): DiagnosticFinding {
        if (snapshot == null) return DiagnosticFinding.UNKNOWN

        val known = snapshot.mastery >= MasteryEngine.MASTERY_THRESHOLD

        return when {
            known && snapshot.retentionDecision == RetentionDecisionSnapshot.REVIEW ->
                DiagnosticFinding.FORGOTTEN
            !known && snapshot.errorCount > 0 ->
                DiagnosticFinding.MISUNDERSTOOD
            known ->
                DiagnosticFinding.KNOWN
            else ->
                DiagnosticFinding.UNKNOWN
        }
    }
}
