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

/**
 * One question from a report Checkmate parsed locally but that was never actually
 * taken on Testmate — see [TestmateApi.createTargetedTest]'s [externalQuestions]
 * param and app/api/tests/targeted/route.ts's own `external_questions` doc for why
 * this bypasses the normal by-chapter server lookup entirely. Deliberately not the
 * app module's own `Question` type — this module has no dependency on
 * `com.checkmate.learning.model` today, and this call site is the only thing that
 * needs a subset of those fields, so the caller (GapTaskManager) maps into this
 * shape rather than this module reaching across for the real one.
 */
data class TestmateExternalQuestion(
    val questionText: String,
    val options: Map<String, String>?,
    val correctOption: String?,
    val explanation: String?
)

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

// ── P0c: Q-bank daily-target bridge (FT-schedule-boosted coverage) ───────────
// Mirrors GET /api/qbank/daily-target's DailyTargetBreakdown shape
// (lib/daily-target-engine.ts) closely enough for QbankDailyTaskManager to act on
// it — NOT a 1:1 field mirror (e.g. `today_target`'s four numbers are flattened
// onto [TestmateDailyTarget] directly rather than nested, since nothing here needs
// the nested shape). Field naming intentionally drops the server's snake_case for
// Kotlin camelCase, same as every other model in this file.

data class TestmateSubjectCoverage(
    val subject: String,
    val total: Int,
    val completed: Int,
    val remaining: Int,
    val coverageTarget: Int
)

/** One chapter still needing questions, most urgent (soonest FT study deadline,
 *  then most remaining) first — server-sorted, see daily-target-engine.ts's own
 *  doc. [subject] lets [QbankDailyTaskManager] pick "this subject's #1 gap"
 *  without re-deriving the urgency ordering client-side. */
data class TestmateCoverageGap(
    val chapter: String,
    val subject: String,
    val totalQuestions: Int,
    val remainingQuestions: Int,
    val nextTest: String?,
    val nextTestDate: String?,
    val nextStudyDeadline: String?,
    val daysUntilStudyDeadline: Int?,
    val urgency: Double
)

data class TestmateUpcomingFt(
    val name: String,
    val testDate: String,
    val studyDeadline: String,
    val studyDeadlineIsCustom: Boolean,
    val daysUntilTest: Int,
    val daysUntilStudyDeadline: Int,
    val chapters: List<String>,
    val chaptersWithoutQuestions: List<String>,
    val remainingInScope: Int,
    val cumulativeRemaining: Int,
    val requiredPerDay: Double
)

data class TestmateFtPressure(
    val applied: Boolean,
    val globalRate: Double,
    val ftRate: Double,
    val baseTarget: Int,
    val boostedTarget: Int,
    val boost: Int,
    val bindingTest: String?
)

data class TestmateDailyTarget(
    val configured: Boolean,
    val examDate: String?,
    val syllabusDeadline: String?,
    val deadlineSource: String?,
    val daysLeft: Int?,
    val totalQuestions: Int,
    val completedQuestions: Int,
    val remainingQuestions: Int,
    val todayCoverageTarget: Int,
    val todayRepairTarget: Int,
    val todayRetentionTarget: Int,
    val todayTotalTarget: Int,
    /** Physics/Chemistry/Botany/Zoology (+ 'Unclassified'), server display order. */
    val subjectBreakdown: List<TestmateSubjectCoverage>,
    val todayCompleted: Int,
    val capacityCapped: Boolean,
    val upcomingTests: List<TestmateUpcomingFt>,
    /** Null when syllabus_chapters isn't seeded server-side — [QbankDailyTaskManager]
     *  treats that as "nothing to target yet" for every subject, not an error. */
    val coverageGaps: List<TestmateCoverageGap>?,
    val ftPressure: TestmateFtPressure?,
    val computedAt: String
)

sealed class TestmateDailyTargetOutcome {
    data class Success(val target: TestmateDailyTarget) : TestmateDailyTargetOutcome()
    data class Error(val message: String) : TestmateDailyTargetOutcome()
}

/**
 * Result of [TestmateApi.startQbankPractice] — the COVERAGE-practice sibling of
 * [TestmateTargetedTest]. Deliberately has no `interventionId` field: see
 * app/api/qbank/practice/route.ts's own doc for why a qbank_practice session must
 * stay structurally invisible to the intervention_id-keyed repair pipeline.
 */
data class TestmateQbankPractice(
    val testId: String,
    val sessionId: String,
    val questionCount: Int,
    /** True when an existing live session for the same chapter/topic/pool was
     *  handed back instead of a new one being created — see that route's
     *  idempotency note. */
    val reused: Boolean
)

sealed class TestmateQbankPracticeOutcome {
    data class Success(val result: TestmateQbankPractice) : TestmateQbankPracticeOutcome()
    data class Error(val message: String) : TestmateQbankPracticeOutcome()
}
