package com.checkmate.testmate

import android.util.Log
import com.checkmate.core.CheckmatePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin REST client for the Testmate test-prep platform
 * (test-platform-blueprint.md §3 — Phase 6: Checkmate integration).
 *
 * Hits the already-stabilized API surface directly:
 *   GET /api/sessions/:id/results
 *   GET /api/sessions/:id/leaderboard
 *
 * Base URL + token are configured in Settings → Test Platform and stored via
 * CheckmatePrefs (same pattern as the LLM provider keys in LlmGateway).
 * No coupling to the Next.js/Supabase web frontend — Checkmate is "just
 * another client hitting these endpoints" per spec §5.
 */
object TestmateApi {

    private const val TAG = "TestmateApi"
    const val PREF_BASE_URL = "testmate_base_url"
    const val PREF_TOKEN    = "testmate_token"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun baseUrl(): String? =
        CheckmatePrefs.getString(PREF_BASE_URL, null)?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }

    private fun token(): String? =
        CheckmatePrefs.getString(PREF_TOKEN, null)?.trim()?.takeIf { it.isNotBlank() }

    suspend fun fetchResult(sessionId: String): TestmateResultOutcome = withContext(Dispatchers.IO) {
        val base = baseUrl() ?: return@withContext TestmateResultOutcome.Error(
            "Set the Testmate base URL in Settings → Test Platform first."
        )
        val authToken = token() ?: return@withContext TestmateResultOutcome.Error(
            "Set the Testmate access token in Settings → Test Platform first."
        )
        val id = sessionId.trim()
        if (id.isEmpty()) return@withContext TestmateResultOutcome.Error("Enter a session ID.")

        val req = Request.Builder()
            .url("$base/api/sessions/$id/results")
            .addHeader("Authorization", "Bearer $authToken")
            .get()
            .build()

        try {
            val resp = client.newCall(req).execute()
            val bodyStr = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) {
                Log.e(TAG, "GET /api/sessions/$id/results HTTP ${resp.code} body=$bodyStr")
                return@withContext TestmateResultOutcome.Error(errorMessageFor(resp.code, bodyStr))
            }
            TestmateResultOutcome.Success(parseResult(id, JSONObject(bodyStr)))
        } catch (e: Exception) {
            Log.e(TAG, "fetchResult failed: ${e.message}")
            TestmateResultOutcome.Error("Couldn't reach Testmate: ${e.message ?: "unknown error"}")
        }
    }

    private fun errorMessageFor(code: Int, bodyStr: String): String = when (code) {
        401, 403 -> "Testmate rejected the token — check Settings → Test Platform."
        404      -> "No result found for that session ID."
        else     -> "Testmate returned HTTP $code."
    }.let { msg ->
        // Surface a server-provided `error` field when present, without letting
        // a malformed body crash the error path.
        runCatching { JSONObject(bodyStr).optString("error").takeIf { it.isNotBlank() } }
            .getOrNull()?.let { "$msg ($it)" } ?: msg
    }

    private fun parseResult(sessionId: String, json: JSONObject): TestmateResult {
        fun weakAreas(key: String): List<TestmateWeakArea> {
            val arr = json.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TestmateWeakArea(
                    name        = o.optString(if (key == "weak_chapters") "chapter" else "topic"),
                    accuracyPct = o.optDouble("accuracy_pct", 0.0),
                    attempted   = o.optInt("attempted", 0)
                )
            }
        }
        return TestmateResult(
            sessionId          = sessionId,
            testTitle          = json.optString("test_title", "Test"),
            score              = json.optDouble("score", 0.0),
            totalMarks         = json.optDouble("total_marks", 0.0),
            accuracyPct        = json.optDouble("accuracy_pct", 0.0),
            attemptedCount     = json.optInt("attempted_count", 0),
            correctCount       = json.optInt("correct_count", 0),
            incorrectCount     = json.optInt("incorrect_count", 0),
            skippedCount       = json.optInt("skipped_count", 0),
            avgTimePerQuestion = json.optDouble("avg_time_per_question", 0.0),
            weakChapters       = weakAreas("weak_chapters"),
            weakTopics         = weakAreas("weak_topics"),
            rankInSession      = if (json.isNull("rank_in_session")) null else json.optInt("rank_in_session")
        )
    }
}
