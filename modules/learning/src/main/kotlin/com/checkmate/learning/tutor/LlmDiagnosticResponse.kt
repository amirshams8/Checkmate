package com.checkmate.learning.tutor

import org.json.JSONException
import org.json.JSONObject

/**
 * Upgrade Blueprint Phase 3, P3.1 — exactly what the model says, untrusted. Never
 * persisted, never handed to [TutorStateMachine] directly — [TutorDiagnosticValidator]
 * is the only path from this type to a durable [TutorDiagnosticFinding]. Same "LLM output
 * is untrusted input, never a decision" boundary
 * [com.checkmate.planner.intervention.LlmIntentParser]'s own class doc already establishes
 * for the negotiation-screen intent schema — this is that same discipline applied to
 * diagnosis.
 */
data class LlmDiagnosticResponse(
    val hypothesis: String,
    /** Raw, unparsed — [HypothesisType.parse] resolves it downstream in
     *  [TutorDiagnosticValidator]. Kept as a raw string here rather than [HypothesisType]
     *  itself so an LLM inventing a label outside the closed vocabulary is a soft
     *  UNKNOWN-fallback, not a parse failure that discards the whole response. */
    val hypothesisType: String?,
    val suspectedSubConcept: String?,
    val reasoningSummary: String?,
    val evidenceQuestionIds: List<String>,
    val confidence: Double,
    val recommendedProbe: String?
) {
    companion object {
        /**
         * @param raw the LLM's raw text response. Tolerates a markdown code fence around
         *   the JSON — same [stripCodeFence] tolerance
         *   [com.checkmate.planner.intervention.LlmIntentParser.parse] already applies, for
         *   the same reason (some providers wrap JSON output in ```json fences regardless
         *   of system-prompt instructions).
         *
         * Expected shape:
         *   {
         *     "hypothesis": "...",
         *     "hypothesisType": "CONCEPTUAL_MISUNDERSTANDING",
         *     "suspectedSubConcept": "...",
         *     "reasoningSummary": "...",
         *     "evidenceQuestionIds": ["q1", "q2"],
         *     "confidence": 0.7,
         *     "recommendedProbe": "..."
         *   }
         *
         * Returns null — never throws — for unparseable JSON or a missing/blank
         * `hypothesis` (the one field with no honest default). Null is load-bearing, same
         * as [com.checkmate.planner.intervention.LlmIntentParser.parse]'s own null: every
         * caller treats a failed parse identically to a timed-out/blank LLM call and falls
         * back to [TutorDiagnosticValidator.deterministicFallback].
         */
        fun parse(raw: String): LlmDiagnosticResponse? {
            val obj = try {
                JSONObject(stripCodeFence(raw))
            } catch (e: JSONException) {
                return null
            }

            val hypothesis = obj.optString("hypothesis", "").takeIf { it.isNotBlank() } ?: return null
            val confidence = obj.optDouble("confidence", Double.NaN)
            if (confidence.isNaN()) return null

            val evidenceIds = obj.optJSONArray("evidenceQuestionIds")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optString(i, null)?.takeIf { it.isNotBlank() } }
            } ?: emptyList()

            return LlmDiagnosticResponse(
                hypothesis = hypothesis,
                hypothesisType = obj.optStringOrNull("hypothesisType"),
                suspectedSubConcept = obj.optStringOrNull("suspectedSubConcept"),
                reasoningSummary = obj.optStringOrNull("reasoningSummary"),
                evidenceQuestionIds = evidenceIds,
                confidence = confidence,
                recommendedProbe = obj.optStringOrNull("recommendedProbe")
            )
        }
    }
}

/** org.json's [JSONObject.optString] returns the literal string "null" for a missing key
 *  in some call forms — same footgun [com.checkmate.planner.intervention.LlmIntentParser]
 *  already guards against with an identical helper; duplicated here rather than shared
 *  since :modules:learning has no dependency on :modules:planner (only the reverse). */
internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key, null) else null

internal fun stripCodeFence(raw: String): String {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("```")) return trimmed
    return trimmed
        .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
        .removeSuffix("```")
        .trim()
}
