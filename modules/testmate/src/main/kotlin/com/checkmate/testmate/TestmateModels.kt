package com.checkmate.testmate

/**
 * Mirrors Testmate's `result_summaries` row shape (test-platform-spec.md §1) plus
 * the parts of `tests` / `session_participants` needed to show a result natively.
 * Parsed by hand from JSON in TestmateApi — matches the org.json style already
 * used by LlmGateway rather than pulling in Retrofit/Moshi for one client.
 *
 * P0b addition: [TestmateResult.breakdown] + [TestmateResult.interventionId], and the
 * new [TestmateQuestionPool]/[TestmateTargetedTest]/[TestmateTargetedTestOutcome] types
 * for the targeted-repair-test creation call. See TestmateApi.createTargetedTest's doc.
 */
data class TestmateWeakArea(
    val name: String,
    val accuracyPct: Double,
    val attempted: Int
)

/**
 * One row of `GET /api/sessions/:id/results`'s `breakdown` array — added on the Testmate
 * side specifically so an external client (Checkmate) could get real per-question evidence
 * instead of only the aggregate fields below. [questionId] is Testmate's own `questions.id`
 * for this row, used to build a deterministic, idempotent Checkmate `Question.id` (see
 * TargetedTestEvidenceImporter) — NOT the same as [TestmateResult.sessionId].
 */
data class TestmateBreakdownRow(
    val questionId: String?,
    val questionNumber: Int,
    val questionText: String?,
    val chapter: String?,
    val topic: String?,
    val correctAnswer: String?,
    val selectedAnswer: String?,
    val isCorrect: Boolean?,
    val timeSpentSeconds: Int,
    val options: Map<String, String>?,
    val explanation: String?
)

data class TestmateResult(
    val sessionId: String,
    val testTitle: String,
    val score: Double,
    val totalMarks: Double,
    val accuracyPct: Double,
    val attemptedCount: Int,
    val correctCount: Int,
    val incorrectCount: Int,
    val skippedCount: Int,
    val avgTimePerQuestion: Double,
    val weakChapters: List<TestmateWeakArea>,
    val weakTopics: List<TestmateWeakArea>,
    val rankInSession: Int?,
    /** Null for a session created before this field existed server-side, or for any
     *  session not created via [TestmateApi.createTargetedTest] (e.g. a manually
     *  entered session ID on the Testmate Settings screen). */
    val interventionId: String? = null,
    /** Per-question detail — see [TestmateBreakdownRow]. Empty for a pre-P0b Testmate
     *  deployment that hasn't picked up the `breakdown` field on its results route yet;
     *  callers must treat an empty list as "no evidence available," not "zero questions." */
    val breakdown: List<TestmateBreakdownRow> = emptyList()
)

sealed class TestmateResultOutcome {
    data class Success(val result: TestmateResult) : TestmateResultOutcome()
    data class Error(val message: String) : TestmateResultOutcome()
}

/**
 * Which of the student's own question history to draw a targeted-repair test from —
 * mirrors the "selection modes" from the P0b design doc, minus the ones not built yet
 * (RECENT_ERRORS, REPAIR_MIX, difficulty-adaptive selection). Sent as-is (name.lowercase())
 * in the `pool` field of [TestmateApi.createTargetedTest]'s request body — see
 * app/api/tests/targeted/route.ts on the Testmate side for exactly how each is resolved.
 */
enum class TestmateQuestionPool {
    /** Previously answered incorrectly. */
    WRONG,
    /** Previously visited but left unanswered. */
    SKIPPED,
    /** WRONG + SKIPPED combined — the default; matches the doc's own example. */
    WRONG_SKIPPED,
    /** Never attempted by this student before, same chapter/topic — used as a standalone
     *  pool request, or automatically by the server as backfill when WRONG/SKIPPED/
     *  WRONG_SKIPPED don't have enough candidates to fill questionCount. */
    NEW
}

data class TestmateTargetedTest(
    val testId: String,
    val sessionId: String,
    val interventionId: String,
    val questionCount: Int
)

sealed class TestmateTargetedTestOutcome {
    data class Success(val test: TestmateTargetedTest) : TestmateTargetedTestOutcome()
    data class Error(val message: String) : TestmateTargetedTestOutcome()
}
