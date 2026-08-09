package com.checkmate.testmate

/**
 * Mirrors Testmate's `result_summaries` row shape (test-platform-spec.md §1) plus
 * the parts of `tests` / `session_participants` needed to show a result natively.
 * Parsed by hand from JSON in TestmateApi — matches the org.json style already
 * used by LlmGateway rather than pulling in Retrofit/Moshi for one client.
 */
data class TestmateWeakArea(
    val name: String,
    val accuracyPct: Double,
    val attempted: Int
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
    val rankInSession: Int?
)

sealed class TestmateResultOutcome {
    data class Success(val result: TestmateResult) : TestmateResultOutcome()
    data class Error(val message: String) : TestmateResultOutcome()
}
