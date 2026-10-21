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
import org.json.JSONException
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
 * request body shape.
 *
 * FIX 1.5: gpt-5.5-pro rejected requests with HTTP 400 "Unsupported parameter: 'temperature'
 *          is not supported with this model" — confirmed from live proxy error. Reasoning-tier
 *          OpenAI models (gpt-5.x, o3, o4) don't accept sampling temperature at all.
 *          callOpenAiCompatible() now omits it for those models instead of always sending 0.7.
 *
 * FIX 1.5: claude-fable-5 via VibeBuild was returning "" silently — two stacked bugs, both
 *          confirmed via live curl:
 *          1. Auth: the Bearer guess was wrong for this route — CONFIRMED via curl that
 *             VibeBuild's /proxy/anthropic route requires native Anthropic-style "x-api-key",
 *             not "Authorization: Bearer". callVibeBuildAnthropic() now sends x-api-key directly.
 *          2. Parsing: Fable 5 returns extended-thinking output, so content[0] is a
 *             {"type":"thinking",...} block with no "text" key — content[1] is the actual
 *             {"type":"text","text":"..."} block. The old code always read content[0], which
 *             threw on Fable 5 responses and got swallowed by the catch block into "". Both
 *             callClaude() and callVibeBuildAnthropic() now scan the content array for the
 *             first block with type=="text" instead of assuming index 0.
 */
object LlmGateway {

    private const val TAG = "LlmGateway"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    object VibeBuildModels {
        const val GPT_5_5_PRO    = "gpt-5.5-pro"
        const val CLAUDE_FABLE_5 = "claude-fable-5"
    }

    private const val VIBEBUILD_DEFAULT_BASE_URL = "https://vibebuild.pro"

    // FIX: true "unrestricted" isn't possible — both Anthropic's and OpenAI-compatible APIs
    // require a numeric max_tokens — so this is set to each model's practical output ceiling
    // instead of the 512 used for the other providers. GPT-5.5 Pro and Claude Fable 5 both
    // support up to 64K output tokens; this is set conservatively below that so a very long
    // reply still can't blow the 60s OkHttp readTimeout above.
    private const val VIBEBUILD_MAX_TOKENS = 8192

    // FIX 1.5: reasoning-tier OpenAI models reject sampling params like temperature entirely.
    private val REASONING_MODEL_PREFIXES = listOf("gpt-5", "o3", "o4")

    private fun supportsTemperature(model: String): Boolean =
        REASONING_MODEL_PREFIXES.none { model.startsWith(it) }

    // FIX 1.5: Anthropic-style responses can lead with a {"type":"thinking",...} block
    // (extended thinking) before the {"type":"text","text":"..."} block. Always scan for
    // the first text block instead of assuming content[0] — assuming index 0 silently broke
    // Fable 5 responses since thinking blocks have no "text" key.
    private fun firstTextBlock(content: JSONArray): String {
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") {
                return block.getString("text").trim()
            }
        }
        throw JSONException("No content block with type=text found")
    }

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
                            model,
                            // FIX: VibeBuild responses were being cut off mid-sentence at the
                            // default 512-token cap. VibeBuild gets a much higher ceiling; other
                            // providers (Groq/OpenRouter) keep the original 512 default.
                            maxTokens = VIBEBUILD_MAX_TOKENS
                        )
                }
            }
            else          -> ""
        }
    }

    private fun callOpenAiCompatible(
        prompt: String, system: String, apiKey: String, url: String, model: String,
        maxTokens: Int = 512
    ): String {
        val messages = JSONArray().apply {
            if (system.isNotBlank()) put(JSONObject().put("role", "system").put("content", system))
            put(JSONObject().put("role", "user").put("content", prompt))
        }
        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("max_tokens", maxTokens)
            // FIX 1.5: reasoning-tier models (gpt-5.x, o3, o4) 400 on this param entirely.
            if (supportsTemperature(model)) put("temperature", 0.7)
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
            firstTextBlock(json.getJSONArray("content"))
        } catch (e: Exception) {
            Log.e(TAG, "Claude call failed: ${e.message}")
            ""
        }
    }

    /**
     * VibeBuild's Anthropic-compatible route (/proxy/anthropic). Reuses callClaude()'s
     * request body shape (model/max_tokens/system/messages, Anthropic's native /v1/messages
     * schema) since VibeBuild fronts the Anthropic API rather than translating it.
     *
     * FIX 1.5: CONFIRMED via live curl against this exact route that VibeBuild requires
     * native Anthropic-style "x-api-key" — "Authorization: Bearer" (which works on the
     * OpenAI route) gets a flat 401 here. Also, Fable 5 responses lead with a "thinking"
     * content block before the "text" block, so parsing now scans for the first
     * type=="text" block via firstTextBlock() instead of assuming content[0].
     */
    private fun callVibeBuildAnthropic(
        prompt: String, system: String, apiKey: String, baseUrl: String, model: String
    ): String {
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", VIBEBUILD_MAX_TOKENS)
            if (system.isNotBlank()) put("system", system)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        }.toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url("$baseUrl/proxy/anthropic/v1/messages")
            .addHeader("x-api-key", apiKey)
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
            firstTextBlock(json.getJSONArray("content"))
        } catch (e: Exception) {
            Log.e(TAG, "VibeBuild/Anthropic call failed: ${e.message}")
            ""
        }
    }

    // FIX 1.6: Gemini mentor replies were randomly blank. Live-reproduced on-device:
    // "gemini-flash-latest" returns HTTP 503 "high demand" in bursts, and with default thinking
    // a reply can take ~45s. callGemini() made ONE attempt on ONE model and turned any
    // non-200 into "" -> Mentor showed "No response. Check your API key in Settings." even
    // though the key was valid (confirmed 200 via direct curl). Now: ordered model fallback,
    // short retry on transient codes (429/500/503/504), thinkingLevel "low" to cut latency,
    // and text parts are joined while skipping any "thought" parts.
    private val GEMINI_MODELS = listOf(
        "gemini-flash-latest",
        "gemini-3-flash-preview",
        "gemini-flash-lite-latest"
    )
    private val GEMINI_TRANSIENT = setOf(429, 500, 502, 503, 504)

    private fun callGemini(prompt: String, system: String, apiKey: String): String {
        val fullPrompt = if (system.isNotBlank()) "$system\n\n$prompt" else prompt
        fun bodyFor(withThinkingCfg: Boolean) = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", fullPrompt)))
            ))
            if (withThinkingCfg) {
                put("generationConfig", JSONObject().put(
                    "thinkingConfig", JSONObject().put("thinkingLevel", "low")
                ))
            }
        }.toString().toRequestBody("application/json".toMediaType())

        for (model in GEMINI_MODELS) {
            var withThinkingCfg = true
            var attempt = 0
            while (attempt < 2) {
                attempt++
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val req = Request.Builder().url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(bodyFor(withThinkingCfg)).build()
                try {
                    val resp    = client.newCall(req).execute()
                    val bodyStr = resp.body?.string() ?: "{}"
                    if (resp.isSuccessful) {
                        val text = parseGeminiText(bodyStr)
                        if (text.isNotBlank()) return text
                        Log.w(TAG, "Gemini $model returned 200 but no text (safety/empty) — trying next model")
                        break
                    }
                    Log.e(TAG, "Gemini HTTP ${resp.code} model=$model attempt=$attempt body=$bodyStr")
                    if (resp.code == 400 && withThinkingCfg && bodyStr.contains("hinking", ignoreCase = true)) {
                        withThinkingCfg = false   // model doesn't accept thinkingLevel — retry plain
                        attempt--
                        continue
                    }
                    if (resp.code in GEMINI_TRANSIENT) {
                        if (attempt < 2) { Thread.sleep(1500L * attempt); continue }
                        break                     // still busy — fall through to next model
                    }
                    break                         // 400/401/403/404: next model (a bad key fails all, cheaply)
                } catch (e: Exception) {
                    Log.e(TAG, "Gemini call failed model=$model attempt=$attempt: ${e.message}")
                    if (attempt < 2) { Thread.sleep(1000L); continue }
                    break
                }
            }
        }
        return ""
    }

    private fun parseGeminiText(bodyStr: String): String = try {
        val parts = JSONObject(bodyStr).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        buildString {
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.optBoolean("thought", false)) continue
                append(part.optString("text", ""))
            }
        }.trim()
    } catch (e: Exception) {
        Log.e(TAG, "Gemini parse failed: ${e.message}")
        ""
    }
}
