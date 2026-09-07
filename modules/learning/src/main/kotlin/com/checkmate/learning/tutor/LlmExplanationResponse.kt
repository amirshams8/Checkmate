package com.checkmate.learning.tutor

import org.json.JSONException
import org.json.JSONObject

/**
 * Upgrade Blueprint Phase 3, P3.1 — structured, not prose. Per the Upgrade Blueprint's own
 * framing ("the mentor never free-lectures... first check known/unknown/misunderstood/
 * forgotten, then pick the *type* of response — not a fixed-length essay"), this carries
 * the separate structured pieces a teaching layer can compose from (a short explanation, a
 * worked example, a common trap, a check question, a hint ladder) rather than one long
 * generated paragraph. Same untrusted status as [LlmDiagnosticResponse] — this is
 * generated content, not a decision, and P3.1 does not build the "Checkmate pedagogical/
 * policy validation" step this feeds into (flagged as a later pass, not invented here).
 */
data class LlmExplanationResponse(
    val explanation: String,
    val keyIdea: String?,
    val workedExample: String?,
    val commonTrap: String?,
    val checkQuestion: String?,
    val hintLadder: List<String>,
    /** What the explanation is grounded in — e.g. concept ids, a specific
     *  [com.checkmate.learning.model.Question] id, a syllabus reference. Free-form strings
     *  rather than a typed reference on purpose: P3.1 doesn't define what a "grounding ref"
     *  resolves to yet (no single canonical id space covers concept/question/syllabus
     *  references today) — kept as opaque strings so this contract doesn't have to be
     *  revisited the moment that's decided. */
    val groundingRefs: List<String>
) {
    companion object {
        /**
         * Same tolerance/failure discipline as [LlmDiagnosticResponse.parse] — strips a
         * markdown code fence, returns null (never throws) for unparseable JSON or a
         * missing/blank `explanation` (the one field with no honest default).
         *
         * Expected shape:
         *   {
         *     "explanation": "...",
         *     "keyIdea": "...",
         *     "workedExample": "...",
         *     "commonTrap": "...",
         *     "checkQuestion": "...",
         *     "hintLadder": ["...", "..."],
         *     "groundingRefs": ["..."]
         *   }
         */
        fun parse(raw: String): LlmExplanationResponse? {
            val obj = try {
                JSONObject(stripCodeFence(raw))
            } catch (e: JSONException) {
                return null
            }

            val explanation = obj.optString("explanation", "").takeIf { it.isNotBlank() } ?: return null

            fun stringList(key: String): List<String> =
                obj.optJSONArray(key)?.let { arr ->
                    (0 until arr.length()).mapNotNull { i -> arr.optString(i, null)?.takeIf { it.isNotBlank() } }
                } ?: emptyList()

            return LlmExplanationResponse(
                explanation = explanation,
                keyIdea = obj.optStringOrNull("keyIdea"),
                workedExample = obj.optStringOrNull("workedExample"),
                commonTrap = obj.optStringOrNull("commonTrap"),
                checkQuestion = obj.optStringOrNull("checkQuestion"),
                hintLadder = stringList("hintLadder"),
                groundingRefs = stringList("groundingRefs")
            )
        }
    }
}
