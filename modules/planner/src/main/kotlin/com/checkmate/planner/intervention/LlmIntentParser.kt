package com.checkmate.planner.intervention

import org.json.JSONException
import org.json.JSONObject

/**
 * Proactive Execution Engine — Step 11 (Blueprint Part One, §9-11).
 *
 * Turns a raw LLM response string into a validated [LlmIntent]. This is the parser
 * [InterventionFallback]'s own doc comment flagged as missing: "there is nothing safe to
 * do with even a successful LLM response — no parser to trust it through." Anything that
 * doesn't cleanly match the closed schema — unparseable JSON, a missing speech field, an
 * intentType outside [InterventionIntentType] — comes back as null. Null is load-bearing:
 * every caller treats a failed parse identically to a timed-out/blank LLM call (fall back
 * to the deterministic path), so this function is never itself a way to reach a
 * [PermittedAction] — only PolicyValidator, downstream of a *successful* parse, can do
 * that.
 *
 * Expected shape (see [LlmIntent]'s own doc for the per-intentType `parameters` keys):
 *   {
 *     "speech": "...",
 *     "intentType": "REDUCE_DURATION",
 *     "targetTaskId": "...",           // optional — see fallbackTaskId below
 *     "parameters": { "newDurationMinutes": "35" }
 *   }
 */
object LlmIntentParser {

    /**
     * @param raw the LLM's raw text response. Tolerates a markdown code fence around the
     *   JSON (some models wrap output in ```json fences even when the system prompt asks
     *   for raw JSON only — stripping it here is cheaper and more robust than trying to
     *   prompt every provider out of the habit).
     * @param fallbackTaskId the negotiation screen is always scoped to exactly one task,
     *   so a response that omits targetTaskId defaults to it rather than being treated as
     *   malformed — the LLM shouldn't have to repeat back an ID it was never asked to track.
     */
    fun parse(raw: String, fallbackTaskId: String): LlmIntent? {
        val obj = try {
            JSONObject(stripCodeFence(raw))
        } catch (e: JSONException) {
            return null
        }

        val speech = obj.optString("speech", "").takeIf { it.isNotBlank() } ?: return null
        val intentType = LlmIntent.parseIntentType(obj.optStringOrNull("intentType")) ?: return null
        val targetTaskId = obj.optStringOrNull("targetTaskId")?.takeIf { it.isNotBlank() } ?: fallbackTaskId

        val parameters: Map<String, String> = obj.optJSONObject("parameters")?.let { paramsObj ->
            paramsObj.keys().asSequence().associateWith { key -> paramsObj.optString(key, "") }
        } ?: emptyMap()

        return LlmIntent(
            speech = speech,
            intentType = intentType,
            targetTaskId = targetTaskId,
            parameters = parameters
        )
    }

    /** org.json's [JSONObject.optString] returns the literal string "null" for a missing
     *  key by default in some call forms — using the two-arg overload with a sentinel and
     *  normalizing avoids that footgun landing inside [LlmIntent.parseIntentType]. */
    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key, null) else null

    private fun stripCodeFence(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```")
            .trim()
    }
}
