package com.checkmate.core.llm

import android.util.Log
import com.checkmate.core.CheckmatePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Unified LLM gateway supporting OpenRouter, Groq, Claude, Gemini, and VibeBuild.
 * FIX 1.4: Changed Groq model from deprecated "llama3-8b-8192" to "llama-3.1-8b-instant".
 * FIX 1.4: Added HTTP status check + response body logging on failure so errors are
 *           visible in logcat instead of silently returning empty string.
 *
 * VibeBuild (added): a third-party gateway (https://vibebuild.pro) fronting both an
 * OpenAI-compatible route (/proxy/openai) and an Anthropic-compatible route
 * (/proxy/anthropic) behind a single VibeBuild token. NOTE: this domain is not an
 * Anthropic- or OpenAI-published partner — it hasn't been independently verified as
 * trustworthy, only as reachable. The base URL is stored in prefs (not hardcoded) so
 * it can be repointed without a rebuild if it turns out to be unreliable. The OpenAI
 * route reuses the existing callOpenAiCompatible() request shape (confirmed working
 * against gpt-5.5-pro). The Anthropic route (callVibeBuildAnthropic) reuses callClaude()'s
 * request body shape but with a Bearer auth header instead of x-api-key, mirroring the
 * confirmed OpenAI-route auth — this half has NOT been confirmed against a real
 * claude-fable-5 response and should be verified before being relied on.
 */
object LlmGateway {

    private const val TAG = "LlmGateway"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    object VibeBuildModels {
        const val GPT_5_5_PRO    = "gpt-5.5-pro"
        const val CLAUDE_FABLE_5 = "claude-fable-5"
    }

    private const val VIBEBUILD_DEFAULT_BASE_URL = "https://vibebuild.pro"

    suspend fun complete(prompt: String, systemPrompt: String = ""): String = withContext(Dispatchers.IO) {
        val provider = CheckmatePrefs.getString("llm_provider", "Groq") ?: "Groq"
        val apiKey   = CheckmatePrefs.getString("llm_key_${provider.lowercase()}", null)

        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "No API key for $provider — returning empty (rule-based fallback will handle)")
            return@withContext ""
        }

        return@withContext when (provider) {
            "Claude"      -> callClaude(prompt, systemPrompt, apiKey)
            "Gemini"      -> callGemini(prompt, systemPrompt, apiKey)
            "Groq"        -> callOpenAiCompatible(
                prompt, systemPrompt, apiKey,
                "https://api.groq.com/openai/v1/chat/completions",
                // FIX: "llama3-8b-8192" was deprecated by Groq → HTTP 400/404 → silent empty return
                // → AdaptivePlanner always fell back to ruleBasedPlan() → vague tasks
                "llama-3.1-8b-instant"
            )
            "OpenRouter"  -> callOpenAiCompatible(
                prompt, systemPrompt, apiKey,
                "https://openrouter.ai/api/v1/chat/completions",
                "mistralai/mistral-7b-instruct:free"
            )
            "VibeBuild"   -> {
                val model = CheckmatePrefs.getString("llm_vibebuild_model", VibeBuildModels.GPT_5_5_PRO)
                    ?: VibeBuildModels.GPT_5_5_PRO
                val baseUrl = (CheckmatePrefs.getString("llm_vibebuild_base_url", null)
                    ?.trim()?.takeIf { it.isNotBlank() } ?: VIBEBUILD_DEFAULT_BASE_URL)
                    .trimEnd('/')
                when (model) {
                    VibeBuildModels.CLAUDE_FABLE_5 ->
                        callVibeBuildAnthropic(prompt, systemPrompt, apiKey, baseUrl, model)
                    else ->
                        callOpenAiCompatible(
                            prompt, systemPrompt, apiKey,
                            "$baseUrl/proxy/openai/v1/chat/completions",
                            model
                        )
                }
            }
            else          -> ""
        }
    }

    private fun callOpenAiCompatible(
        prompt: String, system: String, apiKey: String, url: String, model: String
    ): String {
        val messages = JSONArray().apply {
            if (system.isNotBlank()) put(JSONObject().put("role", "system").put("content", system))
            put(JSONObject().put("role", "user").put("content", prompt))
        }
        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("max_tokens", 512)
            put("temperature", 0.7)
        }.toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body).build()

        return try {
            val resp     = client.newCall(req).execute()
            val bodyStr  = resp.body?.string() ?: "{}"
            // FIX: previously body was consumed before status check — error JSON was lost
            if (!resp.isSuccessful) {
                Log.e(TAG, "OpenAI-compat HTTP ${resp.code} for model=$model url=$url body=$bodyStr")
                return ""
            }
            val json = JSONObject(bodyStr)
            json.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
        } catch (e: Exception) {
            Log.e(TAG, "OpenAI-compat call failed: ${e.message}")
            ""
        }
    }

    private fun callClaude(prompt: String, system: String, apiKey: String): String {
        val body = JSONObject().apply {
            put("model", "claude-3-haiku-20240307")
            put("max_tokens", 512)
            if (system.isNotBlank()) put("system", system)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        }.toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(body).build()

        return try {
            val resp    = client.newCall(req).execute()
            val bodyStr = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) {
                Log.e(TAG, "Claude HTTP ${resp.code} body=$bodyStr")
                return ""
            }
            val json = JSONObject(bodyStr)
            json.getJSONArray("content").getJSONObject(0).getString("text").trim()
        } catch (e: Exception) {
            Log.e(TAG, "Claude call failed: ${e.message}")
            ""
        }
    }

    /**
     * VibeBuild's Anthropic-compatible route (/proxy/anthropic). Reuses callClaude()'s
     * request body shape (model/max_tokens/system/messages, Anthropic's native /v1/messages
     * schema) since VibeBuild fronts the Anthropic API rather than translating it. Auth uses
     * "Authorization: Bearer $apiKey" — matching the pattern confirmed working on VibeBuild's
     * OpenAI route — rather than Anthropic's native "x-api-key", since this is a gateway
     * token, not a real Anthropic key. UNVERIFIED against a live response — if VibeBuild
     * actually expects x-api-key passthrough instead, this will need a one-line header swap.
     * Kept as its own function (not folded into callClaude) because the auth header and URL
     * differ, and future VibeBuild-specific quirks (rate-limit headers, error shape) shouldn't
     * leak into the direct-to-Anthropic path.
     */
    private fun callVibeBuildAnthropic(
        prompt: String, system: String, apiKey: String, baseUrl: String, model: String
    ): String {
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 512)
            if (system.isNotBlank()) put("system", system)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        }.toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url("$baseUrl/proxy/anthropic/v1/messages")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(body).build()

        return try {
            val resp    = client.newCall(req).execute()
            val bodyStr = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) {
                Log.e(TAG, "VibeBuild/Anthropic HTTP ${resp.code} model=$model body=$bodyStr")
                return ""
            }
            val json = JSONObject(bodyStr)
            json.getJSONArray("content").getJSONObject(0).getString("text").trim()
        } catch (e: Exception) {
            Log.e(TAG, "VibeBuild/Anthropic call failed: ${e.message}")
            ""
        }
    }

    private fun callGemini(prompt: String, system: String, apiKey: String): String {
        val fullPrompt = if (system.isNotBlank()) "$system\n\n$prompt" else prompt
        val body = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", fullPrompt)))
            ))
        }.toString().toRequestBody("application/json".toMediaType())

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent?key=$apiKey"
        val req = Request.Builder().url(url)
            .addHeader("Content-Type", "application/json")
            .post(body).build()

        return try {
            val resp    = client.newCall(req).execute()
            val bodyStr = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) {
                Log.e(TAG, "Gemini HTTP ${resp.code} body=$bodyStr")
                return ""
            }
            val json = JSONObject(bodyStr)
            json.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts").getJSONObject(0)
                .getString("text").trim()
        } catch (e: Exception) {
            Log.e(TAG, "Gemini call failed: ${e.message}")
            ""
        }
    }
}
