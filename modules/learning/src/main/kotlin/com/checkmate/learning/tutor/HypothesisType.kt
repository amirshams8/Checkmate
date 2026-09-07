package com.checkmate.learning.tutor

/**
 * Upgrade Blueprint Phase 3, P3.1 — closed vocabulary for
 * [LlmDiagnosticResponse.hypothesisType]. The LLM classifies into one of these; it never
 * invents a new label (see [parse]'s own fallback for what happens when it tries).
 *
 * Deliberately separate from the existing coarse [DiagnosticFinding]
 * (KNOWN/UNKNOWN/MISUNDERSTOOD/FORGOTTEN) — that enum is unchanged by this file and still
 * drives [TutorStateMachine]'s transition table (see that file's own "DELIBERATE SCOPE
 * NOTE" — P3.1 richens what PRODUCES a [DiagnosticFinding], not the finding shape itself).
 * [HypothesisType] is a finer-grained, sub-concept-scoped hypothesis about *why* a student
 * is stuck — carried on [TutorDiagnosticFinding], not fed to [TutorStateMachine] directly.
 */
enum class HypothesisType {
    PROCEDURAL_ERROR,
    CONCEPTUAL_MISUNDERSTANDING,
    PREREQUISITE_GAP,
    FORMULA_SELECTION_ERROR,
    REPRESENTATION_ERROR,
    UNIT_ERROR,
    SIGN_DIRECTION_ERROR,
    READING_INTERPRETATION_ERROR,
    CARELESS_EXECUTION,
    UNKNOWN;

    companion object {
        /**
         * Parses a raw, untrusted LLM string into a closed [HypothesisType], defaulting to
         * [UNKNOWN] for anything that doesn't match a member name exactly (including null/
         * blank). Same "don't assume a specific gap without positive evidence" caution
         * [com.checkmate.learning.engine.ErrorEngine.classify] and
         * [TutorDiagnostics.diagnose] already apply to their own unclassifiable cases.
         *
         * This is a soft fallback, not a schema-validation failure —
         * [TutorDiagnosticValidator] does NOT reject an otherwise-valid response solely for
         * an unparseable hypothesisType string; it just gets classified as [UNKNOWN] rather
         * than losing the rest of the diagnosis (hypothesis text, evidence, confidence).
         */
        fun parse(raw: String?): HypothesisType =
            entries.firstOrNull { it.name == raw } ?: UNKNOWN
    }
}
