package com.checkmate.testmate

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
 * Thin REST client for the Testmate test-prep platform
 * (test-platform-blueprint.md §3 — Phase 6: Checkmate integration).
 *
 * Hits the already-stabilized API surface directly:
 *   GET  /api/sessions/:id/results
 *   GET  /api/sessions/:id/leaderboard
 *   POST /api/tests/targeted   (P0b — see [createTargetedTest])
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

    // ── Base URL allow-list ──────────────────────────────────────────────────
    // Settings → Test Platform only lets a value be saved if it passes
    // [isAllowedBaseUrl], and [baseUrl] below re-checks it on every read — so a
    // value written some other way (e.g. directly to SharedPrefs, or a stale
    // value saved before this allow-list existed) can't quietly redirect
    // Testmate API calls, which carry the access token, to an
    // attacker-controlled host. Exactly three hosts are allowed:
    //  1. testmate2.com, or any subdomain of it (e.g. api.testmate2.com).
    //  2. testmate2.vercel.app — exact match only, not any *.vercel.app, since
    //     that would allow-list every other Vercel-hosted app too. This is the
    //     current production deployment host (testmate2.com isn't live yet).
    //  3. This specific Testmate Supabase project's auth-verify endpoint —
    //     used for the magic-link flow, not general API calls, but it's a
    //     legitimate Testmate-owned endpoint so it's allow-listed too.
    private const val VERCEL_HOST = "testmate2.vercel.app"
    private const val SUPABASE_AUTH_HOST = "donhabgdgdkygcklqxdj.supabase.co"
    private const val SUPABASE_AUTH_PATH_PREFIX = "/auth/v1/verify"

    /**
     * True if [raw] is an https URL whose host is testmate2.com (or a subdomain),
     * exactly testmate2.vercel.app, or this project's Supabase auth-verify host+path.
     * Anything else — a different domain, a bare IP, http (not https), a lookalike
     * domain, etc. — is rejected.
     */
    fun isAllowedBaseUrl(raw: String): Boolean {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isBlank()) return false

        val uri = try { java.net.URI(trimmed) } catch (_: Exception) { return false }
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false

        if (host == "testmate2.com" || host.endsWith(".testmate2.com")) return true
        if (host == VERCEL_HOST) return true
        if (host == SUPABASE_AUTH_HOST && (uri.path ?: "").startsWith(SUPABASE_AUTH_PATH_PREFIX)) return true

        return false
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun baseUrl(): String? {
        val saved = CheckmatePrefs.getString(PREF_BASE_URL, null)?.trim()?.trimEnd('/')
            ?.takeIf { it.isNotBlank() } ?: return null
        if (!isAllowedBaseUrl(saved)) {
            Log.w(TAG, "Stored Testmate base URL failed the allow-list check — refusing to use it")
            return null
        }
        return saved
    }

    private fun token(): String? =
        CheckmatePrefs.getString(PREF_TOKEN, null)?.trim()?.takeIf { it.isNotBlank() }

    // BUGFIX (topic="null" corruption, P0b re-intervention loop part 3): Android's bundled
    // org.json turns a genuine JSON `null` into the four-character STRING "null" when read
    // via bare optString(key) — JSONObject.NULL's toString() is literally "null", and
    // optString has no way to distinguish "key held JSON null" from "key held the text
    // null" once it's already stringified. `.takeIf { it.isNotBlank() }` doesn't catch this
    // because "null" is non-blank text, so it silently survives as if it were a real value.
    // Confirmed live: a question with a genuine NULL topic in Testmate's DB (verified via
    // direct query) round-tripped through this exact code path as the STRING "null", which
    // then flowed into Checkmate's GapTaskLedger, changed KnowledgeGraph.conceptId()'s hash
    // (topic is only chapter-folded when it's Kotlin null, not the text "null"), minted a
    // brand-new concept instead of continuing the original one, and got sent back to
    // POST /api/tests/targeted as topic: "null" — which matches zero real questions via
    // that route's exact `.eq('topic', ...)` filter, producing the 422 "no questions
    // available" failure. Every optString(...) read below that can legitimately be null
    // server-side now checks o.isNull(key) FIRST, same pattern selectedAnswer/isCorrect
    // already used two lines below this comment (they were never affected — this closes
    // the gap for the fields that were still using bare optString).
    private fun optStringOrNull(o: JSONObject, key: String): String? =
        if (o.isNull(key)) null else o.optString(key).takeIf { it.isNotBlank() }

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

    /**
     * P0b — the actual seam that closes the evidence loop. Asks Testmate to build a
     * fresh test out of the student's own wrong/skipped history (or unseen questions,
     * depending on [pool]) for one chapter/topic, and immediately starts a solo session
     * against it, so Checkmate never has to also implement `POST /api/sessions` +
     * question selection itself. See app/api/tests/targeted/route.ts on the Testmate
     * side for exactly how [pool] resolves to a question set.
     *
     * Idempotent by [interventionId] — Testmate's own route returns the SAME test/session
     * on a repeated call with the same [interventionId] rather than creating a duplicate
     * (see that route's doc). Safe to call again if a caller isn't sure whether it already
     * succeeded; [com.checkmate.service.GapTaskManager] still avoids the redundant network
     * call by checking [com.checkmate.planner.intervention.GapTaskLedger.activeTestmateSessionId]
     * first, but this being idempotent is what makes that check "an optimization," not "a
     * correctness requirement."
     */
    suspend fun createTargetedTest(
        interventionId: String,
        chapter: String,
        topic: String?,
        questionCount: Int = 15,
        pool: TestmateQuestionPool = TestmateQuestionPool.WRONG_SKIPPED
    ): TestmateTargetedTestOutcome = withContext(Dispatchers.IO) {
        val base = baseUrl() ?: return@withContext TestmateTargetedTestOutcome.Error(
            "Set the Testmate base URL in Settings → Test Platform first."
        )
        val authToken = token() ?: return@withContext TestmateTargetedTestOutcome.Error(
            "Set the Testmate access token in Settings → Test Platform first."
        )
        if (chapter.isBlank()) {
            return@withContext TestmateTargetedTestOutcome.Error("chapter is required to target a test.")
        }

        val payload = JSONObject().apply {
            put("intervention_id", interventionId)
            put("chapter", chapter)
            // BUGFIX (topic-"null" 422 loop): isNotBlank() alone doesn't catch a [topic]
            // that holds the literal 4-character string "null" — that's non-blank text,
            // not a null reference, so it used to sail through and get sent verbatim as
            // "topic":"null", which is the exact text Testmate's 422 error echoed back.
            // See GapTaskLedger.sanitizeTopicOrChapter's doc for where that string
            // actually originates (a Question row corrupted by this same class's own
            // now-fixed parseResult bug, before this fix shipped). Guarding here too,
            // not just at the GapTaskLedger/GapTaskManager source, since this is the
            // last point before the value leaves the device.
            topic?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                ?.let { put("topic", it) }
            put("question_count", questionCount)
            put("pool", pool.name)
        }
        val body = payload.toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url("$base/api/tests/targeted")
            .addHeader("Authorization", "Bearer $authToken")
            .post(body)
            .build()

        try {
            val resp = client.newCall(req).execute()
            val bodyStr = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) {
                Log.e(TAG, "POST /api/tests/targeted HTTP ${resp.code} body=$bodyStr")
                return@withContext TestmateTargetedTestOutcome.Error(errorMessageFor(resp.code, bodyStr))
            }
            val json = JSONObject(bodyStr)
            val test = json.optJSONObject("test")
            val session = json.optJSONObject("session")
            if (test == null || session == null) {
                return@withContext TestmateTargetedTestOutcome.Error("Testmate response missing test/session.")
            }
            TestmateTargetedTestOutcome.Success(
                TestmateTargetedTest(
                    testId = test.optString("id"),
                    sessionId = session.optString("id"),
                    interventionId = session.optString("intervention_id", interventionId),
                    questionCount = json.optInt("question_count", questionCount)
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "createTargetedTest failed: ${e.message}")
            TestmateTargetedTestOutcome.Error("Couldn't reach Testmate: ${e.message ?: "unknown error"}")
        }
    }

    private fun errorMessageFor(code: Int, bodyStr: String): String = when (code) {
        401, 403 -> "Testmate rejected the token — check Settings → Test Platform."
        404      -> "No result found for that session ID."
        422      -> "Testmate couldn't build a test from the available questions."
        else     -> "Testmate returned HTTP $code."
    }.let { msg ->
        // Surface a server-provided `error` field when present, without letting
        // a malformed body crash the error path.
        runCatching { JSONObject(bodyStr).optString("error").takeIf { it.isNotBlank() } }
            .getOrNull()?.let { "$msg ($it)" } ?: msg
    }

    private fun parseResult(sessionId: String, json: JSONObject): TestmateResult {
        // BUGFIX (topic="null" corruption): chapter/topic here feed the exact same
        // KnowledgeGraph.conceptId() hash and Testmate `topic` filter as the breakdown
        // rows below — same optStringOrNull guard applies for the same reason.
        fun weakAreas(key: String): List<TestmateWeakArea> {
            val arr = json.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TestmateWeakArea(
                    name        = optStringOrNull(o, if (key == "weak_chapters") "chapter" else "topic") ?: "",
                    accuracyPct = o.optDouble("accuracy_pct", 0.0),
                    attempted   = o.optInt("attempted", 0)
                )
            }
        }

        // P0b addition: previously this route's `breakdown` array was fetched but never
        // read — every field below it existed server-side already (see
        // app/api/sessions/[id]/results/route.ts), this is just the client finally parsing
        // it into something TargetedTestEvidenceImporter can write as real evidence.
        fun optionsMap(o: JSONObject): Map<String, String>? {
            val opts = o.optJSONObject("options") ?: return null
            val map = mutableMapOf<String, String>()
            opts.keys().forEach { k -> map[k] = opts.optString(k) }
            return map.takeIf { it.isNotEmpty() }
        }

        val breakdownArr: JSONArray? = json.optJSONArray("breakdown")
        val breakdown = breakdownArr?.let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TestmateBreakdownRow(
                    questionId       = optStringOrNull(o, "question_id"),
                    questionNumber   = o.optInt("question_number", i + 1),
                    questionText     = optStringOrNull(o, "question_text"),
                    // BUGFIX (topic="null" corruption, P0b re-intervention loop part 3):
                    // chapter/topic/correctAnswer/explanation used to read via bare
                    // optString(...).takeIf { it.isNotBlank() } — see optStringOrNull's own
                    // doc for exactly how that let a genuine NULL survive as the literal
                    // text "null" and mint a bogus new concept. selectedAnswer/isCorrect
                    // were already correct (they used o.isNull(...) first); these four are
                    // now guarded the same way.
                    chapter          = optStringOrNull(o, "chapter"),
                    topic            = optStringOrNull(o, "topic"),
                    correctAnswer    = optStringOrNull(o, "correct_answer"),
                    selectedAnswer   = if (o.isNull("selected_answer")) null else o.optString("selected_answer").takeIf { it.isNotBlank() },
                    isCorrect        = if (o.isNull("is_correct")) null else o.optBoolean("is_correct"),
                    timeSpentSeconds = o.optInt("time_spent_seconds", 0),
                    options          = optionsMap(o),
                    explanation      = optStringOrNull(o, "explanation")
                )
            }
        } ?: emptyList()

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
            rankInSession      = if (json.isNull("rank_in_session")) null else json.optInt("rank_in_session"),
            interventionId     = optStringOrNull(json, "intervention_id"),
            breakdown          = breakdown
        )
    }
}
